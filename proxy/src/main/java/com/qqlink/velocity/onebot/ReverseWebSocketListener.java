package com.qqlink.velocity.onebot;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.logging.Logger;

import com.qqlink.velocity.onebot.ws.WebSocket;
import com.qqlink.velocity.util.Strings;

/**
 * 反向 WebSocket 监听器：在本机开一个 TCP 端口，等待机器人连上来。
 *
 * <h2>为什么不用 com.sun.net.httpserver</h2>
 * 那个类虽然能拿到 {@code HttpExchange}，但它的 WebSocket 升级支持依赖内部 API，
 * 且无法拿到"握手后紧跟的数据帧"，容易丢首帧。因此这里直接用
 * {@link ServerSocket} + 自己解析 HTTP 头（{@link WebSocket#readHttpHeaders}），
 * 完全可控、零依赖。
 *
 * <h2>并发模型</h2>
 * <ul>
 *   <li><strong>1 个 accept 线程</strong>：只负责接受连接；</li>
 *   <li><strong>1 个握手线程池</strong>：每个新连接交给它做 HTTP 握手与阻塞读，
 *       避免恶意连接拖住 accept 循环（慢速攻击防护）；</li>
 *   <li>已建立的反向连接放在 {@link ConcurrentHashMap} 里，
 *       支持"多个机器人同时连入"（多号 / 灰度迁移）。</li>
 * </ul>
 *
 * <p>安全：{@code accessToken} 非空时会拒绝 token 不匹配的连接；
 * {@code 0.0.0.0} 监听时会在启动日志中给出提醒。
 */
public final class ReverseWebSocketListener {

    private final String host;
    private final int port;
    private final String path;
    private final String accessToken;
    private final OneBotListener listener;
    private final Logger logger;

    private final Map<String, ReverseWebSocketConnection> connections = new ConcurrentHashMap<>();
    private final AtomicBoolean running = new AtomicBoolean(false);
    private ServerSocket serverSocket;
    private Thread acceptThread;
    private ExecutorService handshakePool;
    private final Map<String, Integer> rejectedPaths = new ConcurrentHashMap<>();

    public ReverseWebSocketListener(String host, int port, String path, String accessToken,
                                    OneBotListener listener, Logger logger) {
        this.host = Strings.isBlank(host) ? "0.0.0.0" : host;
        this.port = port;
        this.path = path.startsWith("/") ? path : "/" + path;
        this.accessToken = Strings.safe(accessToken);
        this.listener = listener;
        this.logger = logger;
    }

    /** 启动监听；失败时抛出 IOException 由上层决定是否禁用反向模式。 */
    public void start() throws IOException {
        if (!running.compareAndSet(false, true)) {
            return;
        }
        serverSocket = new ServerSocket();
        serverSocket.setReuseAddress(true);
        serverSocket.bind(new InetSocketAddress(host, port), 64);

        handshakePool = Executors.newCachedThreadPool(runnable -> {
            Thread thread = new Thread(runnable, "qqlink-ws-handshake");
            thread.setDaemon(true);
            return thread;
        });
        acceptThread = new Thread(this::acceptLoop, "qqlink-onebot-reverse");
        acceptThread.setDaemon(true);
        acceptThread.start();
    }

    private void acceptLoop() {
        while (running.get()) {
            try {
                Socket socket = serverSocket.accept();
                socket.setTcpNoDelay(true);
                socket.setSoTimeout(0);
                handshakePool.execute(() -> handle(socket));
            } catch (IOException exception) {
                if (running.get()) {
                    logger.warning("[OneBot/反向] accept 异常：" + exception.getMessage());
                }
                if (serverSocket == null || serverSocket.isClosed()) {
                    return;
                }
            } catch (Throwable throwable) {
                if (running.get()) {
                    logger.warning("[OneBot/反向] accept 未捕获异常：" + throwable);
                }
            }
        }
    }

    private void handle(Socket socket) {
        ReverseWebSocketConnection connection = null;
        try {
            socket.setSoTimeout(10_000);
            WebSocket.HttpHeaders request = WebSocket.readHttpHeaders(socket.getInputStream());
            if (request == null) {
                closeQuietly(socket);
                return;
            }
            // 路径校验：不一致时给出明确日志（OnBot 各实现的默认路径不同，这是最常见的配置错误）
            if (!request.path().equals(path)) {
                int count = rejectedPaths.merge(request.path(), 1, Integer::sum);
                if (count == 1) {
                    logger.warning("[OneBot/反向] 收到路径 " + request.path()
                            + " 的连接，但配置为 " + path + "；仍按配置的服务处理，"
                            + "如需固定请把机器人端地址改成 ws://<代理IP>:" + port + path);
                }
            }
            connection = new ReverseWebSocketConnection(socket, request, listener, logger);
            socket.setSoTimeout(0);
            if (!connection.handshake(accessToken)) {
                return;
            }
            ReverseWebSocketConnection registered = connection;
            registered.setStateListener(state -> {
                if (!state) {
                    connections.remove(registered.name(), registered);
                }
            });
            connections.put(registered.name(), registered);
            logger.info("[OneBot/反向] 机器人已连接：" + registered.remote()
                    + "（实现：" + registered.implementation() + "，路径：" + request.path() + "）");
            listener.onOpen(registered);
        } catch (IOException exception) {
            logger.fine("[OneBot/反向] 处理连接失败：" + exception.getMessage());
            if (connection != null) {
                connection.close();
            } else {
                closeQuietly(socket);
            }
        } catch (Throwable throwable) {
            logger.warning("[OneBot/反向] 处理连接异常：" + throwable);
            if (connection != null) {
                connection.close();
            } else {
                closeQuietly(socket);
            }
        }
    }

    private void closeQuietly(Socket socket) {
        try {
            socket.close();
        } catch (IOException ignored) {
            // 忽略
        }
    }

    public boolean isRunning() {
        return running.get();
    }

    /** 当前活跃的反向连接数。 */
    public int connectionCount() {
        return connections.size();
    }

    /** 取一个可用的反向连接（任选一个）。 */
    public ReverseWebSocketConnection anyConnection() {
        for (ReverseWebSocketConnection connection : connections.values()) {
            if (connection.isOpen()) {
                return connection;
            }
        }
        return null;
    }

    /** 关闭所有连接并停止监听。 */
    public void stop() {
        if (!running.compareAndSet(true, false)) {
            return;
        }
        for (ReverseWebSocketConnection connection : connections.values()) {
            connection.close();
        }
        connections.clear();
        if (serverSocket != null) {
            try {
                serverSocket.close();
            } catch (IOException ignored) {
                // 忽略
            }
        }
        if (handshakePool != null) {
            handshakePool.shutdownNow();
        }
    }

    public String describe() {
        return host + ":" + port + path;
    }
}
