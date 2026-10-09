package com.qqlink.velocity.onebot;

import com.google.gson.JsonObject;

/**
 * OneBot 连接层向上层（{@link OneBotBridge}）汇报事件的回调接口。
 *
 * <p>设计成单一接口而不是让连接直接持有 Bridge，是为了让连接类保持"纯传输"职责，
 * 便于单元测试与替换（例如未来接入 HTTP POST 上报模式）。
 */
public interface OneBotListener {

    /** 连接握手完成、可以收发数据。 */
    void onOpen(OneBotConnection connection);

    /** 收到一条上游上报事件（原始 JSON 文本）。 */
    void onRawEvent(String payload);

    /** 收到一条 API 调用响应。 */
    void onRawApiResponse(JsonObject json);

    /** 连接结束（正常关闭或异常断开）。 */
    void onClose(OneBotConnection connection, String reason);
}
