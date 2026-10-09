package com.qqlink.velocity.onebot;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.Locale;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;
import java.util.logging.Logger;

import com.qqlink.velocity.onebot.ws.OutboundSocket;
import com.qqlink.velocity.onebot.ws.WebSocket;
import com.qqlink.velocity.util.Strings;

/**
 * 反向 WebSocket 连接：机器人主动连到本插件，本插件作为<strong>服务端</strong>。
 *
 * <h2>握手流程（RFC 6455）</h2>
 * <pre>
 *   机器人 → 代理   GET /onebot/v11/ws HTTP/1.1
 *                   Upgrade: websocket
 *                   Connection: Upgrade
 *                   Sec-WebSocket-Key: dGhlIHNhbXBsZSBub25jZQ==
 *                   Sec-WebSocket-Version: 13
 *                   Authorization: Bearer &lt;access-token&gt;
 *
 *   代理  → 机器人  HTTP/1.1 101 Switching Protocols
 *                   Upgrade: websocket
 *                   Connection: Upgrade
 *                   Sec-WebSocket-Accept: &lt;base64(sha1(key + GUID))&gt;
 * </pre>
 *
 * <p>随后双方在该 TCP 连接上收发 WebSocket 帧：机器人发事件（不加掩码），
 * 本插件发动作（也不加掩码 —— 服务端发帧禁止掩码）。
 */
public final class ReverseWebSocketConnection implements OneBotConnection {

    private final Socket socket;
    private final WebSocket.HttpHeaders request;
    private final OneBotListener listener;
    private final Logger logger;
    private final String remote;

    private final AtomicBoolean open = new AtomicBoolean(false);
    private OutboundSocket outbound;
    private Consumer<Boolean> stateListener;
    private volatile long selfId;
    private volatile String implementation = "unknown";

    public ReverseWebSocketConnection(Socket socket, WebSocket.HttpHeaders request,
                                      OneBotListener listener, Logger logger) {
        this.socket = socket;
        this.request = request;
        this.listener = listener;
        this.logger = logger;
        String address = socket.getInetAddress() == null ? "unknown"
                : socket.getInetAddress().getHostAddress();
        this.remote = address + ":" + socket.getPort();
    }

    /** 对端地址，用于日志区分多个机器人连接。 */
    public String remote() {
        return remote;
    }

    /** 机器人请求的路径，用于校验是否配置一致。 */
    public String requestPath() {
        return request == null ? "/" : request.path();
    }

    /** 机器人上报的 User-Agent，可用于识别具体实现（NapCat / go-cqhttp 等）。 */
    public String userAgent() {
        return request == null ? "" : Strings.safe(request.header("user-agent"));
    }

    @Override
    public String name() {
        return "reverse/" + remote;
    }

    @Override
    public boolean isOpen() {
        return open.get();
    }

    @Override
    public long selfId() {
        return selfId;
    }

    @Override
    public void selfId(long value) {
        if (value != 0L) {
            this.selfId = value;
        }
    }

    @Override
    public String implementation() {
        String agent = userAgent();
        if (Strings.isBlank(agent)) {
            return implementation;
        }
        String lower = agent.toLowerCase(Locale.ROOT);
        if (lower.contains("napcat")) {
            return "NapCat";
        }
        if (lower.contains("lagrange")) {
            return "Lagrange.OneBot";
        }
        if (lower.contains("go-cqhttp")) {
            return "go-cqhttp";
        }
        if (lower.contains("llonebot")) {
            return "LLOneBot";
        }
        return Strings.truncate(agent, 40);
    }

    @Override
    public void setStateListener(Consumer<Boolean> listener) {
        this.stateListener = listener;
    }

    /**
     * 完成握手并开始收发。
     *
     * @param accessToken 期望的 token，为空表示不校验
     * @return 握手是否成功（失败时已写出 HTTP 错误响应并关闭 socket）
     */
    public boolean handshake(String accessToken) {
        try {
            String key = request == null ? null : request.header("sec-websocket-key");
            if (Strings.isBlank(key)) {
                writeHttpError(400, "Bad Request", "缺少 Sec-WebSocket-Key");
                closeQuietly();
                return false;
            }
            String upgrade = request.header("upgrade");
            if (upgrade == null || !upgrade.toLowerCase(Locale.ROOT).contains("websocket")) {
                writeHttpError(400, "Bad Request", "不是 WebSocket 升级请求");
                closeQuietly();
                return false;
            }
            if (!tokenMatches(accessToken)) {
                logger.warning("[OneBot/反向] 拒绝来自 " + remote + " 的连接：AccessToken 校验失败");
                writeHttpError(401, "Unauthorized", "AccessToken 校验失败");
                closeQuietly();
                return false;
            }

            OutputStream out = socket.getOutputStream();
            String response = "HTTP/1.1 101 Switching Protocols\r\n"
                    + "Upgrade: websocket\r\n"
                    + "Connection: Upgrade\r\n"
                    + "Sec-WebSocket-Accept: " + WebSocket.acceptKey(key) + "\r\n"
                    + "Sec-WebSocket-Version: 13\r\n"
                    + "\r\n";
            out.write(response.getBytes(StandardCharsets.UTF_8));
            out.flush();

            // 注意：必须用 readHttpHeaders 保留的 extra 字节重建输入流，
            // 否则机器人"握手 + 首帧"合并发送时首帧会丢失。
            InputStream in = request.toInputStream(socket.getInputStream());

            this.outbound = new OutboundSocket(socket, in, out, false, new OutboundSocket.Listener() {
                @Override
                public void onText(String payload) {
                    dispatch(payload);
                }

                @Override
                public void onClose(String reason) {
                    markClosed(reason);
                }
            }, "reverse-" + remote);

            open.set(true);
            outbound.startReader();
            notifyState(true);
            return true;
        } catch (IOException exception) {
            logger.warning("[OneBot/反向] 与 " + remote + " 握手失败：" + exception.getMessage());
            closeQuietly();
            return false;
        }
    }

    /** 校验 AccessToken：支持 Authorization 头与 access_token 查询参数两种方式。 */
    private boolean tokenMatches(String accessToken) {
        if (Strings.isBlank(accessToken)) {
            return true;
        }
        String header = request.header("authorization");
        if (header != null) {
            String value = header.trim();
            if (value.regionMatches(true, 0, "Bearer ", 0, 7)) {
                value = value.substring(7).trim();
            }
            if (accessToken.equals(value)) {
                return true;
            }
        }
        String query = request.query();
        if (!query.isEmpty()) {
            for (String pair : query.split("&")) {
                int equals = pair.indexOf('=');
                if (equals <= 0) {
                    continue;
                }
                if ("access_token".equalsIgnoreCase(pair.substring(0, equals))
                        && accessToken.equals(pair.substring(equals + 1))) {
                    return true;
                }
            }
        }
        return false;
    }

    private void dispatch(String payload) {
        if (Strings.isBlank(payload)) {
            return;
        }
        com.google.gson.JsonObject json = Json.parseObject(payload);
        if (json == null) {
            if (logger != null) {
                logger.fine("[OneBot/反向] 收到非法 JSON：" + Strings.truncate(payload, 200));
            }
            return;
        }
        if (json.has("post_type")) {
            listener.onRawEvent(payload);
        } else if (json.has("echo") || json.has("retcode") || json.has("status")) {
            listener.onRawApiResponse(json);
        } else {
            listener.onRawEvent(payload);
        }
    }

    @Override
    public boolean send(String payload) {
        OutboundSocket socketRef = this.outbound;
        if (!open.get() || socketRef == null) {
            return false;
        }
        return socketRef.sendText(payload);
    }

    @Override
    public void onEventText(String payload) {
        dispatch(payload);
    }

    @Override
    public void onApiResponse(String payload) {
        com.google.gson.JsonObject json = Json.parseObject(payload);
        if (json != null) {
            listener.onRawApiResponse(json);
        }
    }

    @Override
    public void close() {
        markClosed("本地主动关闭");
    }

    /**
     * 发送一个 WebSocket Ping 保活。
     *
     * <p>正向模式由 JDK 的 {@code HttpClient} 内部维护 ping；反向模式是我们自己
     * 实现的帧读写，因此需要显式保活，否则部分机器人（如 NapCat）在长时间
     * 无数据往来后会主动断开空闲连接。
     */
    public boolean sendHeartbeatPing() {
        OutboundSocket socketRef = this.outbound;
        return socketRef != null && socketRef.sendPing();
    }

    private void markClosed(String reason) {
        if (!open.compareAndSet(true, false)) {
            closeQuietly();
            return;
        }
        OutboundSocket socketRef = this.outbound;
        if (socketRef != null) {
            socketRef.close();
        }
        closeQuietly();
        notifyState(false);
        listener.onClose(this, reason);
    }

    private void closeQuietly() {
        try {
            socket.close();
        } catch (IOException ignored) {
            // 忽略
        }
    }

    private void writeHttpError(int code, String status, String message) {
        try {
            OutputStream out = socket.getOutputStream();
            byte[] body = (message + "\n").getBytes(StandardCharsets.UTF_8);
            String response = "HTTP/1.1 " + code + " " + status + "\r\n"
                    + "Content-Type: text/plain; charset=utf-8\r\n"
                    + "Content-Length: " + body.length + "\r\n"
                    + "Connection: close\r\n"
                    + "\r\n";
            out.write(response.getBytes(StandardCharsets.UTF_8));
            out.write(body);
            out.flush();
        } catch (IOException ignored) {
            // 对端可能已经断开，忽略
        }
    }

    private void notifyState(boolean state) {
        Consumer<Boolean> consumer = this.stateListener;
        if (consumer != null) {
            try {
                consumer.accept(state);
            } catch (Throwable ignored) {
                // 忽略
            }
        }
    }
}
