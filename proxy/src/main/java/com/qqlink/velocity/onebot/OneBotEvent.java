package com.qqlink.velocity.onebot;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.qqlink.velocity.util.Strings;

/**
 * OneBot v11 上报事件（上游 → 本插件）的只读视图。
 *
 * <p>字段命名严格对应 OneBot v11 标准，同时兼容 go-cqhttp / NapCat / Lagrange
 * 的少量扩展字段。所有 getter 都做了空值兜底，机器人少传字段也不会 NPE。
 */
public final class OneBotEvent {

    /** post_type：message / notice / request / meta_event */
    public final String postType;
    /** message_type：private / group */
    public final String messageType;
    /** notice_type / request_type / meta_event_type */
    public final String subType;
    /** 群号（仅群消息 / 群相关通知） */
    public final long groupId;
    /** 发送者 QQ 号 */
    public final long userId;
    /** 机器人自身 QQ 号（上报里带 self_id） */
    public final long selfId;
    /** 原始 message 数组（可能为空） */
    public final JsonArray message;
    /** 原始 message 字符串（CQ 码写法，部分实现只在 raw_message 里给全） */
    public final String rawMessage;
    /** 发送者昵称 */
    public final String senderName;
    /** 发送者在群内的角色：owner / admin / member */
    public final String senderRole;
    /** 原始 JSON（保留给扩展功能使用） */
    public final JsonObject raw;

    private OneBotEvent(JsonObject json) {
        this.raw = json;
        this.postType = Json.str(json, "post_type", "");
        this.messageType = Json.str(json, "message_type", "");
        this.subType = firstNonEmpty(
                Json.str(json, "notice_type", ""),
                Json.str(json, "request_type", ""),
                Json.str(json, "meta_event_type", ""),
                Json.str(json, "sub_type", "")
        );
        this.groupId = Json.longValue(json, "group_id", 0L);
        this.userId = Json.longValue(json, "user_id", 0L);
        this.selfId = Json.longValue(json, "self_id", 0L);
        this.message = Json.array(json, "message");
        this.rawMessage = Json.str(json, "raw_message", "");
        JsonObject sender = Json.obj(json, "sender");
        this.senderName = firstNonEmpty(
                Json.str(sender, "card", ""),
                Json.str(sender, "nickname", ""),
                String.valueOf(this.userId)
        );
        this.senderRole = Json.str(sender, "role", "member");
    }

    /** 从原始 JSON 构建；非法输入返回 null。 */
    public static OneBotEvent of(JsonObject json) {
        if (json == null) {
            return null;
        }
        if (Json.str(json, "post_type", "").isEmpty() && Json.str(json, "echo", "").isEmpty()) {
            return null;
        }
        return new OneBotEvent(json);
    }

    public boolean isGroupMessage() {
        return "message".equals(postType) && ("group".equals(messageType) || groupId != 0L);
    }

    public boolean isPrivateMessage() {
        return "message".equals(postType) && "private".equals(messageType);
    }

    public boolean isNotice() {
        return "notice".equals(postType);
    }

    public boolean isMetaEvent() {
        return "meta_event".equals(postType);
    }

    /** 是否为心跳 / 生命周期等元事件（不需要业务处理）。 */
    public boolean isLifecycleOrHeartbeat() {
        return isMetaEvent() && ("heartbeat".equals(subType) || "lifecycle".equals(subType));
    }

    /** 群消息文本提取，自动处理 @机器人 与图片等非文本段。 */
    public String text(long botSelfId, String atPlaceholder, String cqPlaceholder) {
        String fromArray = Json.messageToText(message, botSelfId, atPlaceholder, cqPlaceholder);
        if (!fromArray.isEmpty()) {
            return fromArray;
        }
        // 部分实现（或 message_format=string）只给 raw_message
        return Strings.safe(rawMessage);
    }

    /** 是否 @ 了机器人。 */
    public boolean mentionsBot(long botSelfId) {
        if (Json.mentionsBot(message, botSelfId)) {
            return true;
        }
        // CQ 码兜底
        return botSelfId != 0L && Strings.safe(rawMessage).contains("[CQ:at,qq=" + botSelfId);
    }

    /** 是否为群主 / 群管理员。 */
    public boolean isGroupAdmin() {
        return "admin".equals(senderRole) || "owner".equals(senderRole);
    }

    private static String firstNonEmpty(String... values) {
        for (String value : values) {
            if (Strings.isNotBlank(value)) {
                return value;
            }
        }
        return "";
    }

    @Override
    public String toString() {
        return "OneBotEvent{post=" + postType + ", type=" + messageType + "/" + subType
                + ", group=" + groupId + ", user=" + userId + "}";
    }
}
