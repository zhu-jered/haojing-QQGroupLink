package com.qqlink.velocity.onebot.ws;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.Socket;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 一条已建立 WebSocket 连接的底层收发封装。
 *
 * <h2>线程模型</h2>
 * <ul>
 *   <li>一个<strong>读线程</strong>：阻塞在 {@link WebSocket#readFrame} 上，
 *       把文本消息交给 {@link Listener}；读线程同时负责自动回 pong、累积分片帧。</li>
 *   <li>任意写线程：{@link #sendText(String)} 内部用 {@code sendLock} 串行化，
 *       保证同一时刻不会有两个线程交叉写帧（WebSocket 帧不允许交错）。</li>
 * </ul>
 *
 * <p>所有网络异常统一走 {@link #fail(String)}，由上层决定是否重连，避免异常向上冒泡打断代理主流程。
 */
public final class OutboundSocket implements AutoCloseable {

    /** 上层回调。实现方必须自己保证线程安全。 */
    public interface Listener {
        /** 收到一条完整的文本消息。 */
        void onText(String payload);

        /** 连接因任何原因结束。 */
        void onClose(String reason);
    }

    private final Socket socket;
    private final InputStream in;
    private final OutputStream out;
    private final boolean clientSide;
    private final Listener listener;
    private final String name;

    private final Object sendLock = new Object();
    private final AtomicBoolean open = new AtomicBoolean(true);

    private Thread readerThread;
    /** 分片帧累积缓冲 */
    private java.io.ByteArrayOutputStream fragmentBuffer;
    /** 分片帧首帧的操作码 */
    private int fragmentOpcode = -1;

    public OutboundSocket(Socket socket, InputStream in, OutputStream out, boolean clientSide,
                          Listener listener, String name) {
        this.socket = socket;
        this.in = in;
        this.out = out;
        this.clientSide = clientSide;
        this.listener = listener;
        this.name = name;
    }

    public boolean isOpen() {
        return open.get();
    }

    public String name() {
        return name;
    }

    /** 启动读线程。 */
    public void startReader() {
        readerThread = new Thread(this::readLoop, "qqlink-ws-reader-" + name);
        readerThread.setDaemon(true);
        readerThread.start();
    }

    private void readLoop() {
        try {
            while (open.get()) {
                WebSocket.Frame frame = WebSocket.readFrame(in);
                if (frame == null) {
                    fail("对端关闭连接");
                    return;
                }
                switch (frame.opcode) {
                    case WebSocket.OP_TEXT, WebSocket.OP_BINARY -> {
                        if (frame.fin) {
                            deliver(frame.text());
                        } else {
                            fragmentOpcode = frame.opcode;
                            fragmentBuffer = new java.io.ByteArrayOutputStream(frame.payload.length * 2);
                            fragmentBuffer.write(frame.payload);
                        }
                    }
                    case WebSocket.OP_CONTINUATION -> {
                        if (fragmentBuffer != null) {
                            fragmentBuffer.write(frame.payload);
                            if (frame.fin) {
                                byte[] whole = fragmentBuffer.toByteArray();
                                fragmentBuffer = null;
                                fragmentOpcode = -1;
                                deliver(new String(whole, java.nio.charset.StandardCharsets.UTF_8));
                            }
                        }
                    }
                    case WebSocket.OP_PING -> sendControl(WebSocket.OP_PONG, frame.payload);
                    case WebSocket.OP_PONG -> {
                        // 心跳响应，无需处理
                    }
                    case WebSocket.OP_CLOSE -> {
                        sendControl(WebSocket.OP_CLOSE, new byte[0]);
                        fail("对端发送 Close 帧");
                        return;
                    }
                    default -> {
                        // 未知操作码：忽略，保持连接
                    }
                }
            }
        } catch (IOException exception) {
            if (open.get()) {
                fail("读线程异常：" + exception.getMessage());
            }
        } catch (Throwable throwable) {
            if (open.get()) {
                fail("读线程未捕获异常：" + throwable);
            }
        }
    }

    private void deliver(String payload) {
        try {
            listener.onText(payload);
        } catch (Throwable throwable) {
            // 业务处理异常不能影响连接
            listener.onClose("事件处理异常：" + throwable);
        }
    }

    /** 发送文本帧。 */
    public boolean sendText(String payload) {
        if (!open.get()) {
            return false;
        }
        byte[] data = payload.getBytes(java.nio.charset.StandardCharsets.UTF_8);
        try {
            synchronized (sendLock) {
                WebSocket.writeFrame(out, WebSocket.OP_TEXT, data, clientSide);
            }
            return true;
        } catch (IOException exception) {
            fail("发送失败：" + exception.getMessage());
            return false;
        }
    }

    /** 发送 Ping 帧（保活）。 */
    public boolean sendPing() {
        return sendControl(WebSocket.OP_PING, new byte[0]);
    }

    private boolean sendControl(int opcode, byte[] payload) {
        if (!open.get()) {
            return false;
        }
        try {
            synchronized (sendLock) {
                WebSocket.writeFrame(out, opcode, payload, clientSide);
            }
            return true;
        } catch (IOException exception) {
            fail("控制帧发送失败：" + exception.getMessage());
            return false;
        }
    }

    /** 标记连接结束（幂等），并关闭底层 socket。 */
    public void fail(String reason) {
        if (!open.compareAndSet(true, false)) {
            return;
        }
        closeQuietly();
        try {
            listener.onClose(reason);
        } catch (Throwable ignored) {
            // 回调异常忽略
        }
    }

    @Override
    public void close() {
        if (!open.compareAndSet(true, false)) {
            closeQuietly();
            return;
        }
        sendControl(WebSocket.OP_CLOSE, new byte[0]);
        closeQuietly();
        try {
            listener.onClose("本地主动关闭");
        } catch (Throwable ignored) {
            // 忽略
        }
    }

    private void closeQuietly() {
        try {
            socket.close();
        } catch (IOException ignored) {
            // 忽略
        }
        open.set(false);
    }
}
