package com.qqlink.velocity.onebot;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.WebSocket;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;
import java.util.logging.Logger;

import com.google.gson.JsonObject;
import com.qqlink.velocity.util.Strings;
import com.qqlink.velocity.config.model.OneBotConfig;

/**
 * 正向 WebSocket 连接（本插件作为<strong>客户端</strong>主动连机器人）。
 *
 * <h2>适用场景</h2>
 * 在 go-cqhttp / NapCat / Lagrange.OneBot 里打开 "正向 WS 服务器"，
 * 然后在配置里填 {@code onebot.forward-url}。
 *
 * <h2>实现要点</h2>
 * <ul>
 *   <li>网络层使用 JDK 11+ 自带的 {@link HttpClient#newWebSocketBuilder()}，
 *       它已经实现了 RFC 6455 客户端（掩码、分片、ping/pong、关闭握手），
 *       比手写更可靠，而且<strong>不引入任何第三方依赖</strong>；</li>
 *   <li>{@link #connectLoop()} 在独立守护线程里运行，采用<strong>指数退避</strong>重连：</li>
 * </ul>
 * <pre>
 *   第 1 次失败 → 等 3s
 *   第 2 次失败 → 等 6s
 *   第 3 次失败 → 等 12s
 *   ... 上限由 onebot.max-reconnect-delay 控制（默认 60s）
 *   连接成功后立即把退避重置为初始值
 * </pre>
 * 这样机器人短暂重启时不会把代理日志刷爆，也不会因为固定间隔重连造成风暴。
 *
 * <h2>AccessToken</h2>
 * 优先使用 {@code Authorization: Bearer <token>} 请求头（OneBot v11 标准），
 * 同时把 {@code ?access_token=} 追加到 URL 上，兼容只认查询串的老实现。
 */
public final class ForwardWebSocketConnection implements OneBotConnection {

    private final OneBotConfig config;
    private final OneBotListener listener;
    private final Logger logger;

    private final AtomicBoolean stopped = new AtomicBoolean(false);
    private volatile boolean open = false;
    private volatile WebSocket webSocket;
    private volatile long selfId;
    private volatile String implementation = "unknown";

    private Consumer<Boolean> stateListener;
    private Thread worker;

    public ForwardWebSocketConnection(OneBotConfig config, OneBotListener listener, Logger logger) {
        this.config = config;
        this.listener = listener;
        this.logger = logger;
    }

    @Override
    public String name() {
        return "forward";
    }

    @Override
    public boolean isOpen() {
        return open;
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
        return implementation;
    }

    @Override
    public void setStateListener(Consumer<Boolean> listener) {
        this.stateListener = listener;
    }

    /** 启动后台重连线程（幂等）。 */
    public void start() {
        if (worker != null) {
            return;
        }
        worker = new Thread(this::connectLoop, "qqlink-onebot-forward");
        worker.setDaemon(true);
        worker.start();
    }

    private void connectLoop() {
        long delay = config.reconnectInterval;
        while (!stopped.get()) {
            try {
                connectOnce();
                // connectOnce 正常返回说明连接已就绪，重置退避
                delay = config.reconnectInterval;
            } catch (Throwable throwable) {
                if (stopped.get()) {
                    return;
                }
                String reason = throwable.getMessage() == null
                        ? throwable.getClass().getSimpleName() : throwable.getMessage();
                logger.warning("[OneBot/正向] 连接 " + safeUrl() + " 失败：" + reason
                        + "，" + (delay / 1000) + " 秒后重试");
            }
            if (stopped.get()) {
                return;
            }
            // 退避等待（可被 stop() 提前打断）
            sleepQuietly(delay);
            delay = Math.min(config.maxReconnectDelay, Math.max(config.reconnectInterval, delay * 2));
        }
    }

    /** 建立一次连接并阻塞直到连接结束。 */
    private void connectOnce() throws IOException, InterruptedException, ExecutionException, java.util.concurrent.TimeoutException {
        URI uri = buildUri();
        HttpClient client = HttpClient.newBuilder()
                .connectTimeout(java.time.Duration.ofMillis(config.requestTimeout))
                .build();

        WebSocket.Builder builder = client.newWebSocketBuilder()
                .connectTimeout(java.time.Duration.ofMillis(config.requestTimeout));
        if (Strings.isNotBlank(config.accessToken)) {
            builder.header("Authorization", "Bearer " + config.accessToken);
        }

        CountDownLatch closed = new CountDownLatch(1);
        ClosingListener socketListener = new ClosingListener(closed);
        WebSocket socket = builder.buildAsync(uri, socketListener)
                .get(config.requestTimeout, TimeUnit.MILLISECONDS);

        this.webSocket = socket;
        this.open = true;
        this.implementation = "unknown";
        notifyState(true);
        logger.info("[OneBot/正向] 已连接到 " + safeUrl());

        try {
            closed.await();
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
        } finally {
            this.open = false;
            this.webSocket = null;
            notifyState(false);
        }
    }

    /** 构造带 token 的 WebSocket URI。 */
    private URI buildUri() throws IOException {
        String raw = Strings.safe(config.forwardUrl).trim();
        if (raw.isEmpty()) {
            throw new IOException("onebot.forward-url 未配置");
        }
        if (raw.startsWith("https://")) {
            raw = "wss://" + raw.substring("https://".length());
        } else if (raw.startsWith("http://")) {
            raw = "ws://" + raw.substring("http://".length());
        } else if (!raw.startsWith("ws://") && !raw.startsWith("wss://")) {
            raw = "ws://" + raw;
        }
        if (Strings.isNotBlank(config.accessToken) && !raw.contains("access_token=")) {
            raw = raw + (raw.contains("?") ? "&" : "?") + "access_token=" + config.accessToken;
        }
        try {
            return URI.create(raw);
        } catch (IllegalArgumentException exception) {
            throw new IOException("onebot.forward-url 不是合法地址：" + raw, exception);
        }
    }

    private String safeUrl() {
        String url = Strings.safe(config.forwardUrl);
        if (Strings.isNotBlank(config.accessToken)) {
            url = url.replace(config.accessToken, "****");
        }
        return url;
    }

    @Override
    public boolean send(String payload) {
        WebSocket socket = this.webSocket;
        if (!open || socket == null) {
            return false;
        }
        try {
            socket.sendText(payload, true);
            return true;
        } catch (Throwable throwable) {
            logger.fine("[OneBot/正向] 发送失败：" + throwable.getMessage());
            return false;
        }
    }

    @Override
    public void onEventText(String payload) {
        // 网络线程只负责投递，真正的业务分发由 OneBotBridge 的线程池完成
        listener.onRawEvent(payload);
    }

    @Override
    public void onApiResponse(String payload) {
        JsonObject json = Json.parseObject(payload);
        if (json != null) {
            listener.onRawApiResponse(json);
        }
    }

    @Override
    public void close() {
        stopped.set(true);
        open = false;
        WebSocket socket = this.webSocket;
        if (socket != null) {
            try {
                socket.sendClose(WebSocket.NORMAL_CLOSURE, "plugin shutdown")
                        .orTimeout(2, TimeUnit.SECONDS)
                        .exceptionally(error -> null);
            } catch (Throwable ignored) {
                // 忽略关闭异常
            }
            try {
                socket.abort();
            } catch (Throwable ignored) {
                // 忽略
            }
        }
        if (worker != null) {
            worker.interrupt();
        }
        notifyState(false);
    }

    private void notifyState(boolean state) {
        Consumer<Boolean> consumer = this.stateListener;
        if (consumer != null) {
            try {
                consumer.accept(state);
            } catch (Throwable ignored) {
                // 回调异常不影响连接
            }
        }
    }

    private void sleepQuietly(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
        }
    }

    /**
     * JDK WebSocket 监听器。
     *
     * <p>JDK 的 {@code WebSocket.Listener} 采用<strong>拉模式</strong>：
     * 必须在每次回调里调用 {@code request(1)} 才会继续投递后续数据。
     * 这里小心处理三件事：
     * <ol>
     *   <li>分片文本：{@code onText} 的 last 参数区分是否结束，需要自行拼接；</li>
     *   <li>心跳：收到 ping 必须回 pong（JDK 会自动回，但这里显式处理更保险）；</li>
     *   <li>计数器：每个回调都要 request(1)，否则连接会静默停止投递。</li>
     * </ol>
     */
    private final class ClosingListener implements WebSocket.Listener {

        private final CountDownLatch closed;
        private final StringBuilder buffer = new StringBuilder(4096);

        ClosingListener(CountDownLatch closed) {
            this.closed = closed;
        }

        @Override
        public void onOpen(WebSocket webSocket) {
            webSocket.request(1);
            WebSocket.Listener.super.onOpen(webSocket);
        }

        @Override
        public CompletionStage<?> onText(WebSocket socket, CharSequence data, boolean last) {
            buffer.append(data);
            if (last) {
                String payload = buffer.toString();
                buffer.setLength(0);
                dispatch(payload);
            }
            socket.request(1);
            return null;
        }

        private void dispatch(String payload) {
            if (Strings.isBlank(payload)) {
                return;
            }
            // OneBot 的 API 响应带 echo / status 字段，事件带 post_type 字段
            JsonObject json = Json.parseObject(payload);
            if (json == null) {
                if (config.debug) {
                    logger.info("[OneBot/正向] 收到非法 JSON：" + Strings.truncate(payload, 200));
                }
                return;
            }
            if (json.has("post_type")) {
                listener.onRawEvent(payload);
            } else if (json.has("echo") || json.has("retcode") || json.has("status")) {
                listener.onRawApiResponse(json);
            } else {
                // meta 事件或未知格式，一律当事件交给上层（上层会自行过滤）
                listener.onRawEvent(payload);
            }
        }

        @Override
        public CompletionStage<?> onBinary(WebSocket socket, ByteBuffer data, boolean last) {
            byte[] bytes = new byte[data.remaining()];
            data.get(bytes);
            buffer.append(new String(bytes, StandardCharsets.UTF_8));
            if (last) {
                String payload = buffer.toString();
                buffer.setLength(0);
                dispatch(payload);
            }
            socket.request(1);
            return null;
        }

        @Override
        public CompletionStage<?> onPing(WebSocket socket, ByteBuffer message) {
            socket.sendPong(message);
            socket.request(1);
            return null;
        }

        @Override
        public CompletionStage<?> onPong(WebSocket socket, ByteBuffer message) {
            socket.request(1);
            return null;
        }

        @Override
        public CompletionStage<?> onClose(WebSocket socket, int statusCode, String reason) {
            open = false;
            logger.info("[OneBot/正向] 连接关闭 code=" + statusCode + " reason=" + reason);
            closed.countDown();
            return null;
        }

        @Override
        public void onError(WebSocket socket, Throwable error) {
            open = false;
            logger.warning("[OneBot/正向] 连接异常：" + error);
            closed.countDown();
        }
    }
}
