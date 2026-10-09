package com.qqlink.velocity.config.model;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import com.qqlink.velocity.config.Nodes;
import com.qqlink.velocity.util.Strings;

/**
 * OneBot v11 连接配置。
 *
 * <p>支持三种模式：
 * <ul>
 *   <li>{@code forward} 正向 WebSocket：本插件作为<strong>客户端</strong>主动连接机器人
 *       （go-cqhttp / NapCat / Lagrange 开启 "正向 WS 服务器"），地址如 {@code ws://127.0.0.1:3001}</li>
 *   <li>{@code reverse} 反向 WebSocket：本插件作为<strong>服务端</strong>监听端口，
 *       由机器人的 "反向 WS 客户端" 连上来（推荐，MCSM 里端口更好管理）</li>
 *   <li>{@code both} 两者同时启用（多机器人 / 迁移期平滑切换）</li>
 * </ul>
 */
public final class OneBotConfig {

    /** 连接模式：forward / reverse / both */
    public String mode = "reverse";

    /** 正向连接地址（支持 ws:// 与 wss://） */
    public String forwardUrl = "ws://127.0.0.1:3001";

    /** 反向监听地址，0.0.0.0 表示监听所有网卡 */
    public String reverseHost = "0.0.0.0";
    /** 反向监听端口 */
    public int reversePort = 8765;
    /** 反向监听的路径，机器人端填 ws://<代理IP>:8765/onebot/v11/ws */
    public String reversePath = "/onebot/v11/ws";

    /** AccessToken，机器人端配置了就必须一致；留空表示不校验 */
    public String accessToken = "";

    /** 正/反向统一使用的机器人自身 QQ 号，用于识别 @ 与自身消息过滤（可留 0 自动获取） */
    public long selfId = 0L;

    /** 断线重连基准间隔（毫秒），实际使用指数退避（间隔 × 2^n，上限 maxReconnectDelay） */
    public long reconnectInterval = 3000L;
    /** 最大重连间隔（毫秒） */
    public long maxReconnectDelay = 60000L;
    /** 心跳发送间隔（秒），0 表示关闭 */
    public int heartbeatInterval = 30;
    /** 连接/请求超时（毫秒） */
    public long requestTimeout = 10000L;
    /** 单次发送失败后的重试次数 */
    public int sendRetries = 3;

    /** 消息发送限速：每秒最多发送多少条（防刷屏，保护 QQ 账号不被风控） */
    public double sendRateLimit = 5.0D;
    /** 消息队列上限，超过则丢弃最旧消息 */
    public int sendQueueLimit = 500;

    /** 是否启用 WebSocket 分片/大消息支持（消息过长时自动切分） */
    public boolean debug = false;

    public static OneBotConfig from(Map<String, Object> root) {
        OneBotConfig config = new OneBotConfig();
        Map<String, Object> section = Nodes.section(root, "onebot");
        config.mode = Strings.safe(section.get("mode") == null ? null : String.valueOf(section.get("mode")));
        if (config.mode.isEmpty()) {
            config.mode = "reverse";
        }
        config.mode = config.mode.trim().toLowerCase(java.util.Locale.ROOT);
        if (!List.of("forward", "reverse", "both").contains(config.mode)) {
            config.mode = "reverse";
        }
        config.forwardUrl = Nodes.str(section, config.forwardUrl, "forward-url");
        config.reverseHost = Nodes.str(section, config.reverseHost, "reverse", "host");
        config.reversePort = Nodes.integer(section, config.reversePort, "reverse", "port");
        config.reversePath = Nodes.str(section, config.reversePath, "reverse", "path");
        if (!config.reversePath.startsWith("/")) {
            config.reversePath = "/" + config.reversePath;
        }
        config.accessToken = Nodes.str(section, "", "access-token");
        config.selfId = Nodes.longValue(section, 0L, "self-id");
        config.reconnectInterval = Math.max(500L, Nodes.longValue(section, config.reconnectInterval, "reconnect-interval"));
        config.maxReconnectDelay = Math.max(config.reconnectInterval,
                Nodes.longValue(section, config.maxReconnectDelay, "max-reconnect-delay"));
        config.heartbeatInterval = Math.max(0, Nodes.integer(section, config.heartbeatInterval, "heartbeat-interval"));
        config.requestTimeout = Math.max(1000L, Nodes.longValue(section, config.requestTimeout, "request-timeout"));
        config.sendRetries = Math.max(0, Nodes.integer(section, config.sendRetries, "send-retries"));
        config.sendRateLimit = Math.max(0.1D, Nodes.decimal(section, config.sendRateLimit, "send-rate-limit"));
        config.sendQueueLimit = Math.max(16, Nodes.integer(section, config.sendQueueLimit, "send-queue-limit"));
        config.debug = Nodes.bool(section, false, "debug");
        return config;
    }

    @Override
    public String toString() {
        return "mode=" + mode + ", forwardUrl=" + forwardUrl
                + ", reverse=" + reverseHost + ":" + reversePort + reversePath;
    }

    /** 供调试输出：隐藏 token。 */
    public Map<String, Object> describe() {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("mode", mode);
        map.put("forward-url", forwardUrl);
        map.put("reverse", reverseHost + ":" + reversePort + reversePath);
        map.put("access-token", accessToken.isEmpty() ? "<未设置>" : "<已设置:" + accessToken.length() + "字符>");
        map.put("self-id", selfId);
        map.put("heartbeat-interval", heartbeatInterval);
        map.put("send-rate-limit", sendRateLimit);
        return map;
    }

    /** 便于日志/调试的可变副本。 */
    public List<String> warnings() {
        List<String> warnings = new ArrayList<>();
        if (accessToken.isEmpty()) {
            warnings.add("onebot.access-token 为空：机器人若暴露在公网且未设 token，任何人都能伪造消息");
        }
        if (("reverse".equals(mode) || "both".equals(mode)) && "0.0.0.0".equals(reverseHost)) {
            warnings.add("onebot.reverse.host=0.0.0.0（监听所有网卡）：请确保只有容器内网/可信网络能访问 " + reversePort + " 端口");
        }
        return warnings;
    }
}
