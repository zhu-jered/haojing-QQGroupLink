package com.qqlink.velocity.onebot.api;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Supplier;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.qqlink.velocity.onebot.Json;
import com.qqlink.velocity.onebot.OneBotConnection;
import com.qqlink.velocity.onebot.OneBotEvent;

/**
 * OneBot v11 API 调用客户端。
 *
 * <h2>请求 / 响应匹配</h2>
 * OneBot 的 WebSocket 是<strong>全双工无请求 ID 语义</strong>的：
 * 我们发出 {@code {"action":"send_group_msg","params":{...},"echo":"qqlink-7"}}，
 * 机器人处理后回一条 {@code {"status":"ok","retcode":0,"echo":"qqlink-7","data":{...}}}。
 * 因此这里用 {@code echo} 字段做关联键，把响应派发给对应的 {@link CompletableFuture}。
 *
 * <h2>为什么要用 Future 而不是同步阻塞</h2>
 * 所有调用都可能从代理的<strong>事件线程</strong>发起（例如玩家聊天）。
 * 一旦同步等待网络往返，就会阻塞事件线程、间接影响 TPS。
 * 因此：
 * <ul>
 *   <li>{@link #sendGroupMessage} 等"只求送达"的调用，内部走
 *       {@link #sendNoReply} —— 发出去立刻返回，<strong>永不阻塞</strong>；</li>
 *   <li>需要读取结果的调用（{@link #getLoginInfo}、{@link #getGroupMemberList}）
 *       返回 Future，由调用方在异步线程里 {@code join}。</li>
 * </ul>
 */
public final class OneBotApi {

    /** 一条可发送的连接。用 Supplier 而不是固定实例，以便断线重连后自动切换。 */
    private final Supplier<OneBotConnection> connectionSupplier;
    private final java.util.function.Consumer<String> debugLogger;

    private final Map<String, CompletableFuture<JsonObject>> pending = new ConcurrentHashMap<>();
    private final AtomicLong echoCounter = new AtomicLong();
    private volatile long requestTimeoutMillis = 10_000L;

    public OneBotApi(Supplier<OneBotConnection> connectionSupplier,
                     java.util.function.Consumer<String> debugLogger) {
        this.connectionSupplier = connectionSupplier;
        this.debugLogger = debugLogger;
    }

    public void requestTimeout(long millis) {
        this.requestTimeoutMillis = Math.max(1000L, millis);
    }

    // ------------------------------------------------------------------
    // 底层发送
    // ------------------------------------------------------------------

    /**
     * fire-and-forget 发送：不关心返回值，绝不阻塞调用线程。
     *
     * @return true 表示已成功写入 WebSocket
     */
    public boolean sendNoReply(String action, JsonObject params) {
        OneBotConnection connection = connectionSupplier.get();
        if (connection == null || !connection.isOpen()) {
            return false;
        }
        String payload = Json.action(action, params, null);
        debug("→ " + payload);
        return connection.send(payload);
    }

    /** 带 echo 的调用，返回 Future。 */
    public CompletableFuture<JsonObject> call(String action, JsonObject params) {
        OneBotConnection connection = connectionSupplier.get();
        CompletableFuture<JsonObject> future = new CompletableFuture<>();
        if (connection == null || !connection.isOpen()) {
            future.completeExceptionally(new IllegalStateException("OneBot 连接不可用"));
            return future;
        }
        String echo = "qqlink-" + echoCounter.incrementAndGet();
        pending.put(echo, future);
        String payload = Json.action(action, params, echo);
        debug("→ " + payload);
        if (!connection.send(payload)) {
            pending.remove(echo);
            future.completeExceptionally(new IllegalStateException("发送失败：" + action));
            return future;
        }
        // 超时兜底：即使机器人完全没回响应，Future 也会被清理，不会泄漏内存
        future.orTimeout(requestTimeoutMillis, TimeUnit.MILLISECONDS)
                .whenComplete((result, error) -> pending.remove(echo));
        return future;
    }

    /** 由连接层把收到的响应文本交给这里。 */
    public void handleResponse(JsonObject json) {
        if (json == null) {
            return;
        }
        String echo = Json.str(json, "echo", null);
        if (echo == null) {
            return;
        }
        CompletableFuture<JsonObject> future = pending.remove(echo);
        if (future == null) {
            return;
        }
        String status = Json.str(json, "status", "");
        int retcode = Json.intValue(json, "retcode", 0);
        if ("failed".equals(status) || retcode != 0) {
            future.completeExceptionally(new IllegalStateException(
                    "OneBot 调用失败 status=" + status + " retcode=" + retcode
                            + " msg=" + Json.str(json, "message", Json.str(json, "wording", ""))));
        } else {
            future.complete(json);
        }
    }

    /** 断线时让所有挂起请求立即失败，避免调用方一直挂在超时上。 */
    public void failAllPending(String reason) {
        for (Map.Entry<String, CompletableFuture<JsonObject>> entry : pending.entrySet()) {
            entry.getValue().completeExceptionally(new IllegalStateException(reason));
        }
        pending.clear();
    }

    public int pendingCount() {
        return pending.size();
    }

    // ------------------------------------------------------------------
    // 常用 API
    // ------------------------------------------------------------------

    /** 发送群消息。 */
    public boolean sendGroupMessage(long groupId, String message) {
        JsonObject params = Json.object();
        Json.put(params, "group_id", String.valueOf(groupId));
        Json.put(params, "message", message);
        Json.put(params, "auto_escape", false);
        return sendNoReply("send_group_msg", params);
    }

    /** 发送群消息（消息段数组，可精确控制 @ 等）。 */
    public boolean sendGroupMessage(long groupId, List<MessageSegment> segments) {
        JsonObject params = Json.object();
        Json.put(params, "group_id", String.valueOf(groupId));
        params.add("message", segmentsToArray(segments));
        return sendNoReply("send_group_msg", params);
    }

    /** 发送私聊消息。 */
    public boolean sendPrivateMessage(long userId, String message) {
        JsonObject params = Json.object();
        Json.put(params, "user_id", String.valueOf(userId));
        Json.put(params, "message", message);
        return sendNoReply("send_private_msg", params);
    }

    /** 撤回消息。 */
    public boolean deleteMessage(String messageId) {
        JsonObject params = Json.object();
        Json.put(params, "message_id", messageId);
        return sendNoReply("delete_msg", params);
    }

    /** 获取登录号信息（用于自动获取机器人 QQ 号）。 */
    public CompletableFuture<JsonObject> getLoginInfo() {
        return call("get_login_info", Json.object());
    }

    /** 获取机器人自身状态。 */
    public CompletableFuture<JsonObject> getStatus() {
        return call("get_status", Json.object());
    }

    /** 获取群成员信息（用于取昵称 / 群名片）。 */
    public CompletableFuture<JsonObject> getGroupMemberInfo(long groupId, long userId) {
        JsonObject params = Json.object();
        Json.put(params, "group_id", String.valueOf(groupId));
        Json.put(params, "user_id", String.valueOf(userId));
        Json.put(params, "no_cache", false);
        return call("get_group_member_info", params);
    }

    /** 获取版本信息，用于识别具体机器人实现。 */
    public CompletableFuture<JsonObject> getVersionInfo() {
        return call("get_version_info", Json.object());
    }

    /**
     * 从 {@code get_group_member_info} 的响应里取显示名。
     * 优先取群名片(card)，其次昵称(nickname)，最后回退 QQ 号。
     */
    public static String displayName(JsonObject response, long fallbackQq) {
        JsonObject data = Json.obj(response, "data");
        if (data == null) {
            return String.valueOf(fallbackQq);
        }
        String card = Json.str(data, "card", "");
        if (!card.isBlank()) {
            return card;
        }
        String nickname = Json.str(data, "nickname", "");
        return nickname.isBlank() ? String.valueOf(fallbackQq) : nickname;
    }

    /** 从 {@code get_login_info} 的响应里取机器人 QQ 号。 */
    public static long loginId(JsonObject response) {
        JsonObject data = Json.obj(response, "data");
        return Json.longValue(data, "user_id", 0L);
    }

    /** 判断事件是否由机器人自己发出（防止消息回环）。 */
    public static boolean isSelf(OneBotEvent event, long selfId) {
        return selfId != 0L && event.userId == selfId;
    }

    private static JsonArray segmentsToArray(List<MessageSegment> segments) {
        JsonArray array = new JsonArray();
        for (MessageSegment segment : segments) {
            array.add(segment.toJson());
        }
        return array;
    }

    /** JSON 数组 → 字符串列表（例如群成员列表里的昵称）。 */
    public static List<String> mapArray(JsonObject response, String field) {
        List<String> result = new ArrayList<>();
        JsonObject data = Json.obj(response, "data");
        if (data == null) {
            return result;
        }
        JsonElement element = data.get(field);
        if (element == null || !element.isJsonArray()) {
            return result;
        }
        for (JsonElement item : element.getAsJsonArray()) {
            if (item.isJsonPrimitive()) {
                result.add(item.getAsString());
            } else if (item.isJsonObject()) {
                JsonObject object = item.getAsJsonObject();
                result.add(Json.str(object, "nickname", Json.str(object, "card", "")));
            }
        }
        return result;
    }

    private void debug(String message) {
        if (debugLogger != null) {
            debugLogger.accept(message);
        }
    }

    /** 兼容某些实现把 retcode 当字符串返回的情况。 */
    public static boolean isOk(JsonObject json) {
        if (json == null) {
            return false;
        }
        String status = Json.str(json, "status", "");
        if ("ok".equals(status)) {
            return true;
        }
        return Json.intValue(json, "retcode", -1) == 0;
    }
}
