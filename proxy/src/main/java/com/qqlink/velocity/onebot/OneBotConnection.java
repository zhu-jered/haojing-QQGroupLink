package com.qqlink.velocity.onebot;

import java.util.function.Consumer;

/**
 * OneBot 连接抽象。
 *
 * <p>插件内部<strong>不关心</strong>连接是正向还是反向，只依赖这个接口收发数据：
 * <ul>
 *   <li>{@link ForwardWebSocketConnection} —— 正向 WS（本插件作为客户端）</li>
 *   <li>{@link ReverseWebSocketConnection} —— 反向 WS（本插件作为服务端，一个连接一个实例）</li>
 * </ul>
 */
public interface OneBotConnection {

    /** 连接标识，用于日志，例如 {@code forward#1} 或 {@code reverse/172.17.0.3}。 */
    String name();

    /** 连接是否可用（已握手完成且未关闭）。 */
    boolean isOpen();

    /** 主动关闭并释放资源。 */
    void close();

    /**
     * 发送一段文本（必须是完整的 OneBot JSON 报文）。
     *
     * @param payload JSON 文本
     * @return 是否成功交给底层发送
     */
    boolean send(String payload);

    /** 收到一条上游事件（原始 JSON 文本）。 */
    void onEventText(String payload);

    /** 收到一条 API 调用返回。 */
    void onApiResponse(String payload);

    /**
     * 设置连接状态变化回调。
     *
     * @param listener 参数为 true 表示连接已就绪，false 表示已断开
     */
    void setStateListener(Consumer<Boolean> listener);

    /** 底层 OneBot 实现名（如 go-cqhttp / NapCat），未知返回 unknown。 */
    String implementation();

    /** 机器人自身 QQ 号（来自上报的 self_id）。 */
    long selfId();

    /** 记录机器人自身 QQ 号（首次收到上报时更新）。 */
    void selfId(long value);
}
