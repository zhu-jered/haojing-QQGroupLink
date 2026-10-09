package com.qqlink.velocity.onebot;

import java.util.ArrayList;
import java.util.List;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.google.gson.JsonPrimitive;
import com.qqlink.velocity.onebot.api.MessageSegment;
import com.qqlink.velocity.util.Strings;

/**
 * OneBot 报文的 JSON 读写辅助。
 *
 * <p>OneBot 协议本身是 JSON over WebSocket，因此这里不写 POJO 反射映射
 * （机器人实现的字段总有差异，反射映射一旦缺字段就容易崩），
 * 而是用 Gson 的树模型做"宽容解析"：任何缺失字段都走默认值。
 *
 * <p>依赖说明：Gson 由 Velocity 自身提供，因此属于零额外依赖。
 */
public final class Json {

    private Json() {
    }

    // ------------------------------------------------------------------
    // 读
    // ------------------------------------------------------------------

    /** 解析为 JsonObject；解析失败返回 null（不抛异常）。 */
    public static JsonObject parseObject(String text) {
        if (Strings.isBlank(text)) {
            return null;
        }
        try {
            JsonElement element = JsonParser.parseString(text);
            return element != null && element.isJsonObject() ? element.getAsJsonObject() : null;
        } catch (Exception ignored) {
            return null;
        }
    }

    /** 解析为 JsonElement；失败返回 null。 */
    public static JsonElement parse(String text) {
        if (Strings.isBlank(text)) {
            return null;
        }
        try {
            return JsonParser.parseString(text);
        } catch (Exception ignored) {
            return null;
        }
    }

    /** 取字符串，缺失 / null 返回 def。 */
    public static String str(JsonObject object, String key, String def) {
        if (object == null) {
            return def;
        }
        JsonElement element = object.get(key);
        if (element == null || element.isJsonNull()) {
            return def;
        }
        if (element.isJsonPrimitive()) {
            return element.getAsString();
        }
        return def;
    }

    /** 取 long，兼容 "123" 与 123 两种写法。 */
    public static long longValue(JsonObject object, String key, long def) {
        String value = str(object, key, null);
        if (value == null) {
            return def;
        }
        return Strings.parseLong(value, def);
    }

    public static int intValue(JsonObject object, String key, int def) {
        return (int) longValue(object, key, def);
    }

    public static boolean bool(JsonObject object, String key, boolean def) {
        if (object == null) {
            return def;
        }
        JsonElement element = object.get(key);
        if (element == null || element.isJsonNull()) {
            return def;
        }
        if (element.isJsonPrimitive()) {
            JsonPrimitive primitive = element.getAsJsonPrimitive();
            if (primitive.isBoolean()) {
                return primitive.getAsBoolean();
            }
            return Strings.parseBoolean(primitive.getAsString(), def);
        }
        return def;
    }

    /** 取子对象。 */
    public static JsonObject obj(JsonObject object, String key) {
        if (object == null) {
            return null;
        }
        JsonElement element = object.get(key);
        return element != null && element.isJsonObject() ? element.getAsJsonObject() : null;
    }

    /** 取数组，缺失返回空数组。 */
    public static JsonArray array(JsonObject object, String key) {
        if (object == null) {
            return new JsonArray();
        }
        JsonElement element = object.get(key);
        return element != null && element.isJsonArray() ? element.getAsJsonArray() : new JsonArray();
    }

    public static String getString(JsonObject object, String key, String def) {
        return str(object, key, def);
    }

    // ------------------------------------------------------------------
    // 写
    // ------------------------------------------------------------------

    /** 创建一个新对象。 */
    public static JsonObject object() {
        return new JsonObject();
    }

    /** 链式设置字符串字段（null 会被写成"空字符串"，OneBot 里比 null 更安全）。 */
    public static JsonObject put(JsonObject object, String key, String value) {
        object.addProperty(key, value == null ? "" : value);
        return object;
    }

    public static JsonObject put(JsonObject object, String key, Number value) {
        object.addProperty(key, value);
        return object;
    }

    public static JsonObject put(JsonObject object, String key, Boolean value) {
        object.addProperty(key, value);
        return object;
    }

    public static JsonObject put(JsonObject object, String key, JsonElement value) {
        object.add(key, value);
        return object;
    }

    /** 构造 OneBot 动作报文：{"action": "...", "params": {...}, "echo": "..."}。 */
    public static String action(String action, JsonObject params, String echo) {
        JsonObject root = new JsonObject();
        root.addProperty("action", action);
        root.add("params", params == null ? new JsonObject() : params);
        if (echo != null) {
            root.addProperty("echo", echo);
        }
        return root.toString();
    }

    /**
     * 从消息数组中提取纯文本内容。
     *
     * <p>处理规则：
     * <ul>
     *   <li>{@code text} 段直接拼接；</li>
     *   <li>{@code at} 段：如果 {@code qq} 等于 {botSelfId} 则替换为配置的 @ 占位符，否则忽略；</li>
     *   <li>{@code image}/{@code face}/{@code record} 等非文本段：按配置替换为占位符或删除。</li>
     * </ul>
     *
     * @param message      OneBot 的 message 数组
     * @param botSelfId    机器人自身 QQ 号，用于识别 @机器人
     * @param atPlaceholder @机器人时的替换文本，如 "" 或 "机器人"
     * @param cqPlaceholder 图片等非文本段的替换文本，如 "[图片]"，null 表示删除
     * @return 拼好的纯文本（未 trim）
     */
    public static String messageToText(JsonArray message, long botSelfId, String atPlaceholder, String cqPlaceholder) {
        if (message == null || message.isEmpty()) {
            return "";
        }
        StringBuilder sb = new StringBuilder(64);
        for (JsonElement element : message) {
            if (!element.isJsonObject()) {
                continue;
            }
            JsonObject segment = element.getAsJsonObject();
            String type = str(segment, "type", "");
            JsonObject data = obj(segment, "data");
            switch (type) {
                case "text" -> sb.append(str(data, "text", ""));
                case "at" -> {
                    long qq = longValue(data == null ? null : data, "qq", 0L);
                    if (qq == botSelfId && botSelfId != 0L) {
                        sb.append(atPlaceholder == null ? "" : atPlaceholder);
                    }
                    // 其他 @ 直接在转发时丢弃（避免把无关用户昵称带到 MC）
                }
                case "image" -> appendPlaceholder(sb, cqPlaceholder, "[图片]");
                case "face" -> appendPlaceholder(sb, cqPlaceholder, "[表情]");
                case "record" -> appendPlaceholder(sb, cqPlaceholder, "[语音]");
                case "video" -> appendPlaceholder(sb, cqPlaceholder, "[视频]");
                case "forward" -> appendPlaceholder(sb, cqPlaceholder, "[合并转发]");
                case "json" -> appendPlaceholder(sb, cqPlaceholder, "[卡片消息]");
                case "reply" -> {
                    // 引用回复：忽略引用本身，只保留正文
                }
                default -> {
                    // 未知类型忽略，保证向前兼容
                }
            }
        }
        return sb.toString();
    }

    /** 判断消息数组里是否包含 @机器人。 */
    public static boolean mentionsBot(JsonArray message, long botSelfId) {
        if (message == null || botSelfId == 0L) {
            return false;
        }
        for (JsonElement element : message) {
            if (!element.isJsonObject()) {
                continue;
            }
            JsonObject segment = element.getAsJsonObject();
            if (!"at".equals(str(segment, "type", ""))) {
                continue;
            }
            JsonObject data = obj(segment, "data");
            if (data != null && longValue(data, "qq", 0L) == botSelfId) {
                return true;
            }
        }
        return false;
    }

    private static void appendPlaceholder(StringBuilder sb, String configured, String fallback) {
        if (configured == null) {
            return;
        }
        sb.append(configured.isEmpty() ? fallback : configured);
    }

    /**
     * 把 OneBot 消息数组解析为"文本 + 消息段列表"。
     *
     * <p>文本用于转发到 MC（要求是干净的纯文本）；
     * 段列表保留原始语义，供 {@code @} 转换、关键词匹配等高级功能使用。
     *
     * @param message       message 数组
     * @param botSelfId     机器人 QQ 号，用于把 @机器人 转成占位文本
     * @param atPlaceholder @机器人 时的替换文本
     * @param cqPlaceholder 图片等非文本段的替换文本（null 表示丢弃）
     */
    public static MessageParse parseMessage(JsonArray message, long botSelfId,
                                            String atPlaceholder, String cqPlaceholder) {
        java.util.List<MessageSegment> segments = new ArrayList<>();
        StringBuilder text = new StringBuilder(64);
        if (message == null) {
            return new MessageParse("", segments);
        }
        for (JsonElement element : message) {
            if (!element.isJsonObject()) {
                continue;
            }
            JsonObject segment = element.getAsJsonObject();
            String type = str(segment, "type", "");
            JsonObject data = obj(segment, "data");
            switch (type) {
                case "text" -> {
                    String value = str(data, "text", "");
                    text.append(value);
                    segments.add(MessageSegment.text(value));
                }
                case "at" -> {
                    long qq = longValue(data, "qq", 0L);
                    if (qq == botSelfId && botSelfId != 0L) {
                        text.append(atPlaceholder == null ? "" : atPlaceholder);
                    } else if (qq != 0L) {
                        segments.add(MessageSegment.at(qq));
                    }
                }
                case "image" -> {
                    appendPlaceholder(text, cqPlaceholder, "[图片]");
                    segments.add(MessageSegment.image(str(data, "file", "")));
                }
                case "face" -> appendPlaceholder(text, cqPlaceholder, "[表情]");
                case "record" -> appendPlaceholder(text, cqPlaceholder, "[语音]");
                case "video" -> appendPlaceholder(text, cqPlaceholder, "[视频]");
                case "forward" -> appendPlaceholder(text, cqPlaceholder, "[合并转发]");
                case "json" -> appendPlaceholder(text, cqPlaceholder, "[卡片消息]");
                default -> {
                    // reply / 未知类型：忽略，保证向前兼容
                }
            }
        }
        return new MessageParse(text.toString(), segments);
    }

    /** {@link #parseMessage} 的结果。 */
    public static final class MessageParse {
        public final String text;
        public final java.util.List<MessageSegment> segments;

        MessageParse(String text, java.util.List<MessageSegment> segments) {
            this.text = text;
            this.segments = segments;
        }
    }

    /** 把字符串数组转为 JsonArray（用于 action 参数）。 */
    public static JsonArray toArray(List<String> values) {
        JsonArray array = new JsonArray();
        for (String value : values) {
            array.add(value);
        }
        return array;
    }

    /** JsonArray → List&lt;String&gt;。 */
    public static List<String> toList(JsonArray array) {
        List<String> list = new ArrayList<>();
        if (array == null) {
            return list;
        }
        for (JsonElement element : array) {
            list.add(element.isJsonPrimitive() ? element.getAsString() : element.toString());
        }
        return list;
    }
}
