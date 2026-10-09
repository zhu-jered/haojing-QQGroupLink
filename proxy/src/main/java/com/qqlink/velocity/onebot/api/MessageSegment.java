package com.qqlink.velocity.onebot.api;

import com.google.gson.JsonObject;
import com.qqlink.velocity.onebot.Json;

/**
 * OneBot 消息段（message segment）。
 *
 * <p>仅在需要"精确表达"时使用（例如把 MC 聊天里的 @ 转成 QQ 的 @）。
 * 纯文本转发走字符串拼接即可，语义更简单、兼容性最好。
 */
public final class MessageSegment {

    private final String type;
    private final JsonObject data;

    private MessageSegment(String type, JsonObject data) {
        this.type = type;
        this.data = data;
    }

    public String type() {
        return type;
    }

    public JsonObject data() {
        return data;
    }

    /** 纯文本段。 */
    public static MessageSegment text(String text) {
        JsonObject data = Json.object();
        Json.put(data, "text", text == null ? "" : text);
        return new MessageSegment("text", data);
    }

    /** @某人。 */
    public static MessageSegment at(long qq) {
        JsonObject data = Json.object();
        Json.put(data, "qq", String.valueOf(qq));
        return new MessageSegment("at", data);
    }

    /** @全体成员（需要机器人有管理员权限）。 */
    public static MessageSegment atAll() {
        JsonObject data = Json.object();
        Json.put(data, "qq", "all");
        return new MessageSegment("at", data);
    }

    /** 图片（URL 或本地路径，由机器人实现决定）。 */
    public static MessageSegment image(String file) {
        JsonObject data = Json.object();
        Json.put(data, "file", file);
        return new MessageSegment("image", data);
    }

    /** 转为 JSON 对象。 */
    public JsonObject toJson() {
        JsonObject object = Json.object();
        Json.put(object, "type", type);
        Json.put(object, "data", data);
        return object;
    }
}
