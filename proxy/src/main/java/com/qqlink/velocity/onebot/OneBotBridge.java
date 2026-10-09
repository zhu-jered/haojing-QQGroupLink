package com.qqlink.velocity.onebot;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Consumer;
import java.util.logging.Logger;

import com.google.gson.JsonObject;
import com.qqlink.velocity.config.model.OneBotConfig;
import com.qqlink.velocity.onebot.api.OneBotApi;
import com.qqlink.velocity.util.Strings;

/**
 * OneBot 桥接核心：把"连接层（正向 / 反向 WS）"与"业务层（转发、指令）"粘起来。
 *
 * <h2>消息流转总览</h2>
 * <pre>
 *  ┌──────────────┐  原始 JSON   ┌──────────────────┐  OneBotEvent   ┌──────────────┐
 *  │ 正向/反向 WS │ ───────────► │  OneBotBridge    │ ─────────────► │ 事件订阅者   │
 *  │ (网络线程)   │              │ (事件线程池)     │                │ ChatRouter   │
 *  └──────────────┘              └──────────────────┘                │ CommandMgr   │
 *         ▲                             │                           └──────────────┘
 *         │ 发送帧                       │ 入队
 *  ┌──────────────┐              ┌──────────────────┐
 *  │ 连接           │ ◄────────── │ 发送队列 + 限流  │
 *  │ (发送线程)    │              │ (单线程，令牌桶) │
 *  └──────────────┘              └──────────────────┘
 * </pre>
 *
 * <h2>三条关键设计原则</h2>
 * <ol>
 *   <li><strong>绝不阻塞代理主线程</strong>：所有"发送"都是入队即返回，
 *       真正的网络 IO 由一条独立的发送线程完成；</li>
 *   <li><strong>绝不阻塞网络读写线程</strong>：收到事件后先投递到业务线程池，
 *       解析和转发都在池里做。这样即使业务逻辑偶发变慢，也不会造成
 *       WebSocket 读缓冲积压、被机器人判定为掉线；</li>
 *   <li><strong>令牌桶限流</strong>：QQ 官方对机器人发消息有频率限制，
 *       超过就风控。发送线程按 {@code onebot.send-rate-limit} 匀速放行，
 *       并把队列长度控制在 {@code onebot.send-queue-limit} 以内。</li>
 * </ol>
 */
public final class OneBotBridge implements OneBotListener {

    /** 发送任务：一条待发送的 QQ 消息。 */
    private record SendTask(long target, String text, boolean group, int attempt, long created) {
    }

    private final OneBotConfig config;
    private final Logger logger;

    /** 事件订阅者（聊天路由、指令处理器等）。 */
    private final List<Consumer<OneBotEvent>> subscribers = new CopyOnWriteArrayList<>();

    /** 连接状态变化回调（用于把状态显示在 /qqlink status）。 */
    private final List<Consumer<Boolean>> stateCallbacks = new CopyOnWriteArrayList<>();

    private final ExecutorService eventExecutor;
    private final BlockingQueue<SendTask> sendQueue;
    private final Object senderLock = new Object();

    private volatile OneBotConnection primaryConnection;
    private volatile ReverseWebSocketListener reverseListener;
    private volatile ForwardWebSocketConnection forwardConnection;
    private volatile OneBotApi api;
    private volatile boolean running = false;
    private volatile long startTime = System.currentTimeMillis();
    private volatile long lastEventTime = 0L;
    private volatile long lastSendTime = 0L;

    /** 统计计数器 */
    private final AtomicLong receivedEvents = new AtomicLong();
    private final AtomicLong sentMessages = new AtomicLong();
    private final AtomicLong droppedMessages = new AtomicLong();
    private final AtomicLong failedMessages = new AtomicLong();

    private Thread senderThread;

    public OneBotBridge(OneBotConfig config, Logger logger) {
        this.config = config;
        this.logger = logger;
        int threads = Math.max(2, Math.min(8, Runtime.getRuntime().availableProcessors()));
        this.eventExecutor = Executors.newFixedThreadPool(threads, runnable -> {
            Thread thread = new Thread(runnable, "qqlink-onebot-event");
            thread.setDaemon(true);
            return thread;
        });
        this.sendQueue = new ArrayBlockingQueue<>(config.sendQueueLimit);
        this.api = new OneBotApi(this::connection, this::debug);
        this.api.requestTimeout(config.requestTimeout);
    }

    // ------------------------------------------------------------------
    // 生命周期
    // ------------------------------------------------------------------

    /** 按配置启动正向 / 反向连接。 */
    public void start() {
        running = true;
        startTime = System.currentTimeMillis();

        boolean reverse = "reverse".equals(config.mode) || "both".equals(config.mode);
        boolean forward = "forward".equals(config.mode) || "both".equals(config.mode);

        // 发送线程先起来，保证任何一条入队消息都能被及时发出
        startSenderThread();

        if (reverse) {
            try {
                reverseListener = new ReverseWebSocketListener(config.reverseHost, config.reversePort,
                        config.reversePath, config.accessToken, this, logger);
                reverseListener.start();
                logger.info("[OneBot] 反向 WebSocket 监听已启动：" + reverseListener.describe()
                        + "（机器人端请填 ws://<代理所在容器IP>:" + config.reversePort + config.reversePath + "）");
            } catch (Exception exception) {
                reverseListener = null;
                logger.severe("[OneBot] 反向 WebSocket 启动失败：" + exception.getMessage());
                logPortHints(exception);
            }
        }
        if (forward) {
            forwardConnection = new ForwardWebSocketConnection(config, this, logger);
            forwardConnection.start();
            logger.info("[OneBot] 正向 WebSocket 连接线程已启动，目标：" + config.forwardUrl);
        }
        if (!reverse && !forward) {
            logger.warning("[OneBot] 未启用任何连接模式，QQ 互通将不可用（检查 onebot.mode）");
        }

        for (String warning : config.warnings()) {
            logger.warning("[OneBot] " + warning);
        }
    }

    private void logPortHints(Exception exception) {
        String message = Strings.safe(exception.getMessage());
        if (message.contains("Address already in use")) {
            logger.severe("[OneBot] 端口 " + config.reversePort
                    + " 已被占用：请确认没有第二个机器人/插件也在监听该端口，或改 onebot.reverse.port");
        } else if (message.contains("Permission denied")) {
            logger.severe("[OneBot] 无法绑定端口 " + config.reversePort
                    + "（1024 以下端口需要特权，建议改用 8765 等高位端口）");
        } else {
            logger.severe("[OneBot] 提示：反向模式需要插件所在容器把端口映射给机器人访问；"
                    + "若无法开放端口，请把 onebot.mode 改成 forward 让插件主动外连");
        }
    }

    /** 停止所有连接与线程。 */
    public void stop() {
        running = false;
        if (forwardConnection != null) {
            forwardConnection.close();
            forwardConnection = null;
        }
        if (reverseListener != null) {
            reverseListener.stop();
            reverseListener = null;
        }
        primaryConnection = null;
        if (api != null) {
            api.failAllPending("插件已停止");
        }
        sendQueue.clear();
        eventExecutor.shutdownNow();
        synchronized (senderLock) {
            senderLock.notifyAll();
        }
    }

    // ------------------------------------------------------------------
    // 连接管理
    // ------------------------------------------------------------------

    /** 当前用于发送的可用连接。 */
    public OneBotConnection connection() {
        OneBotConnection current = primaryConnection;
        if (current != null && current.isOpen()) {
            return current;
        }
        if (reverseListener != null) {
            ReverseWebSocketConnection reverse = reverseListener.anyConnection();
            if (reverse != null && reverse.isOpen()) {
                primaryConnection = reverse;
                return reverse;
            }
        }
        if (forwardConnection != null && forwardConnection.isOpen()) {
            primaryConnection = forwardConnection;
            return forwardConnection;
        }
        return null;
    }

    public boolean isConnected() {
        return connection() != null;
    }

    public OneBotApi api() {
        return api;
    }

    /** 机器人自身 QQ 号（优先取连接上报的 self_id，其次取配置）。 */
    public long selfId() {
        OneBotConnection current = connection();
        if (current != null && current.selfId() != 0L) {
            return current.selfId();
        }
        return config.selfId;
    }

    public void subscribe(Consumer<OneBotEvent> subscriber) {
        subscribers.add(subscriber);
    }

    public void onStateChange(Consumer<Boolean> callback) {
        stateCallbacks.add(callback);
    }

    // ------------------------------------------------------------------
    // OneBotListener 实现（连接层 → 业务层）
    // ------------------------------------------------------------------

    @Override
    public void onOpen(OneBotConnection connection) {
        primaryConnection = connection;
        for (Consumer<Boolean> callback : stateCallbacks) {
            try {
                callback.accept(true);
            } catch (Throwable ignored) {
                // 忽略回调异常
            }
        }
        // 主动查询登录信息：拿到机器人 QQ 号 + 验证 API 通路可用
        api.getLoginInfo().thenAccept(response -> {
            long id = OneBotApi.loginId(response);
            if (id != 0L) {
                connection.selfId(id);
                logger.info("[OneBot] 机器人 QQ 号：" + id + "（实现：" + connection.implementation() + "）");
            }
        }).exceptionally(error -> {
            logger.fine("[OneBot] get_login_info 调用失败：" + error.getMessage());
            return null;
        });
    }

    @Override
    public void onRawEvent(String payload) {
        receivedEvents.incrementAndGet();
        lastEventTime = System.currentTimeMillis();
        if (config.debug) {
            debug("← " + Strings.truncate(payload, 400));
        }
        if (!running) {
            return;
        }
        eventExecutor.execute(() -> handleEvent(payload));
    }

    private void handleEvent(String payload) {
        try {
            JsonObject json = Json.parseObject(payload);
            OneBotEvent event = OneBotEvent.of(json);
            if (event == null) {
                return;
            }
            // 记录机器人自身 QQ 号（多数实现会带 self_id）
            if (event.selfId != 0L) {
                OneBotConnection current = primaryConnection;
                if (current != null) {
                    current.selfId(event.selfId);
                }
            }
            if (event.isLifecycleOrHeartbeat()) {
                return;
            }
            for (Consumer<OneBotEvent> subscriber : subscribers) {
                try {
                    subscriber.accept(event);
                } catch (Throwable throwable) {
                    logger.warning("[OneBot] 事件处理异常（" + event + "）：" + throwable);
                }
            }
        } catch (Throwable throwable) {
            logger.warning("[OneBot] 处理上报事件失败：" + throwable);
        }
    }

    @Override
    public void onRawApiResponse(JsonObject json) {
        if (config.debug) {
            debug("← " + json);
        }
        if (api != null) {
            api.handleResponse(json);
        }
    }

    @Override
    public void onClose(OneBotConnection connection, String reason) {
        if (primaryConnection == connection) {
            primaryConnection = null;
        }
        logger.info("[OneBot] 连接结束（" + connection.name() + "）：" + reason);
        if (api != null) {
            api.failAllPending("连接已断开：" + reason);
        }
        if (!isConnected()) {
            for (Consumer<Boolean> callback : stateCallbacks) {
                try {
                    callback.accept(false);
                } catch (Throwable ignored) {
                    // 忽略
                }
            }
        }
    }

    // ------------------------------------------------------------------
    // 发送（业务层 → 连接层）
    // ------------------------------------------------------------------

    /** 发送群消息（异步、限流、自动重试）。 */
    public void sendGroup(long groupId, String text) {
        enqueue(new SendTask(groupId, Strings.safe(text), true, 0, System.currentTimeMillis()));
    }

    /** 发送私聊消息。 */
    public void sendPrivate(long userId, String text) {
        enqueue(new SendTask(userId, Strings.safe(text), false, 0, System.currentTimeMillis()));
    }

    private void enqueue(SendTask task) {
        if (Strings.isBlank(task.text())) {
            return;
        }
        if (!running) {
            return;
        }
        if (!sendQueue.offer(task)) {
            // 队列已满：丢弃最旧的一条，保证新消息（通常是更重要的通知）能进队
            SendTask dropped = sendQueue.poll();
            if (dropped != null) {
                droppedMessages.incrementAndGet();
            }
            if (!sendQueue.offer(task)) {
                droppedMessages.incrementAndGet();
            }
        }
        synchronized (senderLock) {
            senderLock.notifyAll();
        }
    }

    /**
     * 发送线程：令牌桶限流 + 队列消费 + 失败重试。
     *
     * <p>令牌桶算法：桶容量 1，按 {@code sendRateLimit} 条/秒 匀速补充。
     * 这样即使瞬时入队 100 条消息，也只会以配置的速率平滑发出，避免触发 QQ 风控。
     */
    private void startSenderThread() {
        senderThread = new Thread(() -> {
            double rate = Math.max(0.1D, config.sendRateLimit);
            long intervalNanos = (long) (1_000_000_000D / rate);
            long nextAllowed = System.nanoTime();

            while (!Thread.currentThread().isInterrupted()) {
                SendTask task;
                try {
                    task = sendQueue.poll(500, TimeUnit.MILLISECONDS);
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                    return;
                }
                if (task == null) {
                    // 空闲时也做一次心跳保活（部分机器人长时间无流量会主动断链）
                    maybeHeartbeat();
                    continue;
                }

                // 限流等待
                long now = System.nanoTime();
                if (now < nextAllowed) {
                    long waitMillis = (nextAllowed - now) / 1_000_000L;
                    if (waitMillis > 0) {
                        try {
                            Thread.sleep(waitMillis);
                        } catch (InterruptedException interrupted) {
                            Thread.currentThread().interrupt();
                            return;
                        }
                    }
                }
                nextAllowed = System.nanoTime() + intervalNanos;

                sendNow(task);
            }
        }, "qqlink-onebot-sender");
        senderThread.setDaemon(true);
        senderThread.start();
    }

    private void sendNow(SendTask task) {
        OneBotConnection connection = connection();
        if (connection == null) {
            retry(task, "当前没有可用的 OneBot 连接");
            return;
        }
        boolean ok = task.group()
                ? api.sendGroupMessage(task.target(), task.text())
                : api.sendPrivateMessage(task.target(), task.text());
        if (ok) {
            sentMessages.incrementAndGet();
            lastSendTime = System.currentTimeMillis();
            if (config.debug) {
                debug("→ " + (task.group() ? "群" + task.target() : "私聊" + task.target())
                        + "：" + Strings.truncate(task.text(), 200));
            }
        } else {
            retry(task, "发送失败");
        }
    }

    private void retry(SendTask task, String reason) {
        int attempt = task.attempt() + 1;
        long age = System.currentTimeMillis() - task.created();
        // 重试窗口 30 秒，最多 config.sendRetries 次；超出直接丢弃并计数
        if (attempt <= config.sendRetries && age < 30_000L) {
            sendQueue.offer(new SendTask(task.target(), task.text(), task.group(), attempt, task.created()));
            logger.fine("[OneBot] " + reason + "，稍后重试（第 " + attempt + " 次）："
                    + Strings.truncate(task.text(), 60));
        } else {
            failedMessages.incrementAndGet();
            logger.warning("[OneBot] " + reason + "且已超过重试上限，丢弃消息："
                    + Strings.truncate(task.text(), 80));
        }
    }

    /** 保活：连接空闲超过心跳间隔时发一个 WebSocket Ping。 */
    private void maybeHeartbeat() {
        if (config.heartbeatInterval <= 0) {
            return;
        }
        long idle = System.currentTimeMillis() - Math.max(lastSendTime, lastEventTime);
        if (idle < config.heartbeatInterval * 1000L) {
            return;
        }
        // JDK HttpClient 的 WebSocket 内部已有 ping 机制；反向连接需要我们自己发
        OneBotConnection connection = connection();
        if (connection instanceof ReverseWebSocketConnection reverse) {
            reverse.sendHeartbeatPing();
        }
        lastSendTime = System.currentTimeMillis();
    }

    // ------------------------------------------------------------------
    // 状态与统计
    // ------------------------------------------------------------------

    public long receivedEvents() {
        return receivedEvents.get();
    }

    public long sentMessages() {
        return sentMessages.get();
    }

    public long droppedMessages() {
        return droppedMessages.get();
    }

    public long failedMessages() {
        return failedMessages.get();
    }

    public int queueSize() {
        return sendQueue.size();
    }

    public long uptimeMillis() {
        return System.currentTimeMillis() - startTime;
    }

    public long lastEventTime() {
        return lastEventTime;
    }

    public String connectionDescription() {
        OneBotConnection connection = connection();
        if (connection == null) {
            return "未连接";
        }
        return (config.mode.equals("forward") ? "正向" : "反向") + "·" + connection.implementation();
    }

    public String implementation() {
        OneBotConnection connection = connection();
        return connection == null ? "unknown" : connection.implementation();
    }

    /** 反向监听端口信息，用于启动提示与状态显示。 */
    public String reverseDescribe() {
        ReverseWebSocketListener listener = this.reverseListener;
        return listener == null ? "未启用" : listener.describe();
    }

    public int reverseConnectionCount() {
        ReverseWebSocketListener listener = this.reverseListener;
        return listener == null ? 0 : listener.connectionCount();
    }

    /** 一次性把队列里的消息全部发出（用于插件关闭前兜底，避免丢失"服务器关闭"通知）。 */
    public void flush(long timeoutMillis) {
        long deadline = System.currentTimeMillis() + timeoutMillis;
        while (!sendQueue.isEmpty() && System.currentTimeMillis() < deadline) {
            try {
                Thread.sleep(50L);
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                return;
            }
        }
    }

    private void debug(String message) {
        if (config.debug) {
            logger.info("[OneBot/调试] " + message);
        }
    }

    /** 供状态命令展示的结构化信息。 */
    public List<String> describe() {
        List<String> lines = new ArrayList<>();
        lines.add("连接模式: " + config.mode);
        if (!"forward".equals(config.mode)) {
            lines.add("反向监听: " + reverseDescribe() + "（活跃连接 " + reverseConnectionCount() + "）");
        }
        if (!"reverse".equals(config.mode)) {
            lines.add("正向目标: " + config.forwardUrl);
        }
        lines.add("连接状态: " + connectionDescription());
        lines.add("机器人QQ: " + selfId());
        lines.add("收发统计: 收 " + receivedEvents() + " / 发 " + sentMessages()
                + " / 丢弃 " + droppedMessages() + " / 失败 " + failedMessages()
                + " / 队列 " + queueSize());
        return lines;
    }

    /** 尚未使用的连接缓存（未来支持多机器人广播时使用）。 */
    public List<OneBotConnection> allConnections() {
        List<OneBotConnection> list = new ArrayList<>();
        ReverseWebSocketListener listener = this.reverseListener;
        if (listener != null) {
            // 反向连接没有暴露全量列表，这里通过 anyConnection 做单个兜底
            ReverseWebSocketConnection any = listener.anyConnection();
            if (any != null) {
                list.add(any);
            }
        }
        if (forwardConnection != null && forwardConnection.isOpen()) {
            list.add(forwardConnection);
        }
        return list;
    }

    /** 供外部（例如 Web 面板）查询的内部映射，保持轻量。 */
    public java.util.Map<String, Object> snapshot() {
        java.util.Map<String, Object> map = new java.util.LinkedHashMap<>();
        map.put("connected", isConnected());
        map.put("mode", config.mode);
        map.put("implementation", implementation());
        map.put("self_id", selfId());
        map.put("reverse_connections", reverseConnectionCount());
        map.put("received", receivedEvents());
        map.put("sent", sentMessages());
        map.put("dropped", droppedMessages());
        map.put("failed", failedMessages());
        map.put("queue", queueSize());
        map.put("uptime_ms", uptimeMillis());
        return map;
    }

    /** 便于测试：手动派发一个事件（不经过网络）。 */
    public void dispatchTestEvent(String payload) {
        onRawEvent(payload);
    }
}
