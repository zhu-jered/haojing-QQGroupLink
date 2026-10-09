package com.qqlink.velocity.onebot.ws;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;

/**
 * 手写 WebSocket（RFC 6455）编解码工具。
 *
 * <h2>为什么不引第三方库</h2>
 * 需求明确要求"依赖最少化"。JDK 21 自带的 {@code java.net.http.HttpClient} 可以做
 * WebSocket 客户端，但<strong>没有服务端</strong>；而反向 WS 又是本插件的推荐模式。
 * 与其引入 Netty 或 Java-WebSocket，不如手写一个只包含 OneBot 场景所需子集的实现：
 * 文本帧、分片、ping/pong、close —— 约 200 行，可审计、零依赖。
 *
 * <h2>实现要点</h2>
 * <ul>
 *   <li>客户端 → 服务端必须掩码（{@link #writeFrame} 的 mask 参数）；</li>
 *   <li>服务端 → 客户端<strong>禁止</strong>掩码，否则部分实现会直接断开；</li>
 *   <li>支持 126（16 位长度）与 127（64 位长度）扩展长度字段；</li>
 *   <li>GUID 为 RFC 6455 规定的固定值 {@code 258EAFA5-E914-47DA-95CA-C5AB0DC85B11}。</li>
 * </ul>
 */
public final class WebSocket {

    private WebSocket() {
    }

    /** RFC 6455 规定的握手魔数。 */
    public static final String GUID = "258EAFA5-E914-47DA-95CA-C5AB0DC85B11";

    // 操作码
    public static final int OP_CONTINUATION = 0x0;
    public static final int OP_TEXT = 0x1;
    public static final int OP_BINARY = 0x2;
    public static final int OP_CLOSE = 0x8;
    public static final int OP_PING = 0x9;
    public static final int OP_PONG = 0xA;

    /** 单个帧允许的最大负载（16 MB），防止恶意构造超大帧导致内存耗尽。 */
    private static final long MAX_FRAME_PAYLOAD = 16L * 1024L * 1024L;

    /**
     * 计算握手用的 Sec-WebSocket-Accept 值。
     *
     * @param key 请求头 Sec-WebSocket-Key 的原始值
     */
    public static String acceptKey(String key) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-1");
            byte[] hash = digest.digest((key + GUID).getBytes(StandardCharsets.UTF_8));
            return Base64.getEncoder().encodeToString(hash);
        } catch (NoSuchAlgorithmException exception) {
            // SHA-1 是 JDK 必备算法，理论上不会走到这里
            throw new IllegalStateException("JDK 缺少 SHA-1 实现", exception);
        }
    }

    /** 写入一个 WebSocket 帧。 */
    public static void writeFrame(OutputStream out, int opcode, byte[] payload, boolean mask) throws IOException {
        writeFrame(out, opcode, payload, 0, payload.length, mask, true);
    }

    /**
     * 写入一个 WebSocket 帧（支持分片与"非结束帧"）。
     *
     * @param opcode  操作码
     * @param payload 数据缓冲
     * @param offset  起始偏移
     * @param length  长度
     * @param mask    是否需要掩码（客户端发往服务端时为 true）
     * @param fin     是否为最后一帧
     */
    public static void writeFrame(OutputStream out, int opcode, byte[] payload,
                                  int offset, int length, boolean mask, boolean fin) throws IOException {
        ByteArrayOutputStream header = new ByteArrayOutputStream(14);
        header.write((fin ? 0x80 : 0x00) | (opcode & 0x0F));

        int maskBit = mask ? 0x80 : 0x00;
        if (length < 126) {
            header.write(maskBit | length);
        } else if (length <= 0xFFFF) {
            header.write(maskBit | 126);
            header.write((length >>> 8) & 0xFF);
            header.write(length & 0xFF);
        } else {
            header.write(maskBit | 127);
            long value = length;
            for (int i = 7; i >= 0; i--) {
                header.write((int) ((value >>> (8 * i)) & 0xFF));
            }
        }

        byte[] maskKey = null;
        if (mask) {
            maskKey = new byte[4];
            new java.security.SecureRandom().nextBytes(maskKey);
            header.write(maskKey, 0, 4);
        }

        out.write(header.toByteArray());
        if (mask) {
            byte[] masked = new byte[length];
            for (int i = 0; i < length; i++) {
                masked[i] = (byte) (payload[offset + i] ^ maskKey[i & 3]);
            }
            out.write(masked);
        } else {
            out.write(payload, offset, length);
        }
        out.flush();
    }

    /** 读取到的一个帧。 */
    public static final class Frame {
        public final int opcode;
        public final boolean fin;
        public final byte[] payload;

        Frame(int opcode, boolean fin, byte[] payload) {
            this.opcode = opcode;
            this.fin = fin;
            this.payload = payload;
        }

        public String text() {
            return new String(payload, StandardCharsets.UTF_8);
        }
    }

    /**
     * 从输入流读取一个帧。<strong>阻塞</strong>，必须在专用线程里调用。
     *
     * @return 帧；流正常结束返回 null
     * @throws IOException 协议错误或对端关闭
     */
    public static Frame readFrame(InputStream in) throws IOException {
        int b0 = in.read();
        if (b0 < 0) {
            return null;
        }
        int b1 = in.read();
        if (b1 < 0) {
            return null;
        }

        boolean fin = (b0 & 0x80) != 0;
        int opcode = b0 & 0x0F;
        boolean masked = (b1 & 0x80) != 0;
        long length = b1 & 0x7F;

        if (length == 126) {
            length = ((long) readByte(in) << 8) | readByte(in);
        } else if (length == 127) {
            length = 0;
            for (int i = 0; i < 8; i++) {
                length = (length << 8) | readByte(in);
            }
        }

        if (length < 0 || length > MAX_FRAME_PAYLOAD) {
            throw new IOException("WebSocket 帧过大或长度非法: " + length);
        }

        byte[] maskKey = null;
        if (masked) {
            maskKey = readFully(in, 4);
        }

        byte[] payload = readFully(in, (int) length);
        if (masked && maskKey != null) {
            for (int i = 0; i < payload.length; i++) {
                payload[i] = (byte) (payload[i] ^ maskKey[i & 3]);
            }
        }
        return new Frame(opcode, fin, payload);
    }

    private static int readByte(InputStream in) throws IOException {
        int value = in.read();
        if (value < 0) {
            throw new IOException("WebSocket 流意外结束");
        }
        return value;
    }

    private static byte[] readFully(InputStream in, int length) throws IOException {
        byte[] buffer = new byte[length];
        int read = 0;
        while (read < length) {
            int count = in.read(buffer, read, length - read);
            if (count < 0) {
                throw new IOException("WebSocket 流意外结束（期望 " + length + " 字节，实际 " + read + "）");
            }
            read += count;
        }
        return buffer;
    }

    /**
     * HTTP 请求头解析结果。
     *
     * <p>关键点：{@code socket.getInputStream()} 是<strong>一次性流</strong>。
     * 某些机器人（尤其是快速握手的实现）会在同一 TCP 段里
     * "HTTP 升级头 + 第一个 WebSocket 帧"一起发过来，如果我们直接丢弃已读出的多余额度，
     * 第一个数据帧就永久丢失了。因此这里把超读出来的字节保留在 {@link #extra} 中，
     * 握手完成后用 {@link #toInputStream(InputStream)} 重新拼成完整流。
     */
    public static final class HttpHeaders {
        /** 请求行，例如 {@code GET /onebot/v11/ws HTTP/1.1} */
        public final String requestLine;
        /** 头名（全小写） → 值 */
        public final Map<String, String> headers;
        /** 已从流中读出但属于"请求头之后"的字节 */
        public final byte[] extra;

        HttpHeaders(String requestLine, Map<String, String> headers, byte[] extra) {
            this.requestLine = requestLine;
            this.headers = headers;
            this.extra = extra;
        }

        public String method() {
            int space = requestLine == null ? -1 : requestLine.indexOf(' ');
            return space > 0 ? requestLine.substring(0, space) : "";
        }

        /** 请求路径（不含查询串）。 */
        public String path() {
            if (requestLine == null) {
                return "/";
            }
            String[] parts = requestLine.split(" ");
            if (parts.length < 2) {
                return "/";
            }
            String target = parts[1];
            int query = target.indexOf('?');
            return query >= 0 ? target.substring(0, query) : target;
        }

        /** 查询串（不含 ?），可能为空。 */
        public String query() {
            if (requestLine == null) {
                return "";
            }
            String[] parts = requestLine.split(" ");
            if (parts.length < 2) {
                return "";
            }
            int query = parts[1].indexOf('?');
            return query >= 0 ? parts[1].substring(query + 1) : "";
        }

        public String header(String name) {
            return headers.get(name.toLowerCase(Locale.ROOT));
        }

        /** 把 extra 字节重新接回流的前端。 */
        public InputStream toInputStream(InputStream source) {
            if (extra == null || extra.length == 0) {
                return source;
            }
            java.io.SequenceInputStream sequence =
                    new java.io.SequenceInputStream(new java.io.ByteArrayInputStream(extra), source);
            return new java.io.BufferedInputStream(sequence, 8192);
        }
    }

    /**
     * 读取 HTTP 请求头（直到空行为止）。
     *
     * @return 解析结果；流已结束返回 null
     */
    public static HttpHeaders readHttpHeaders(InputStream rawInput) throws IOException {
        InputStream in = rawInput.markSupported() ? rawInput : new java.io.BufferedInputStream(rawInput, 8192);
        Map<String, String> headers = new LinkedHashMap<>();
        String requestLine = null;
        StringBuilder current = new StringBuilder();
        int totalBytes = 0;
        while (true) {
            int value = in.read();
            if (value < 0) {
                return null;
            }
            totalBytes++;
            if (totalBytes > 32 * 1024) {
                throw new IOException("HTTP 请求头过大");
            }
            if (value == '\n') {
                String line = current.toString();
                current.setLength(0);
                if (line.endsWith("\r")) {
                    line = line.substring(0, line.length() - 1);
                }
                if (line.isEmpty()) {
                    break;
                }
                int colon = line.indexOf(':');
                if (colon > 0) {
                    headers.put(line.substring(0, colon).trim().toLowerCase(Locale.ROOT),
                            line.substring(colon + 1).trim());
                } else if (requestLine == null) {
                    requestLine = line;
                }
                continue;
            }
            current.append((char) value);
        }

        // 把缓冲区中"已经到达但还未消费"的字节抽出来，避免丢失紧跟握手头的数据帧。
        // 注意：这里绝对不能用 read() 读到 -1 —— socket 仍然打开时那会永久阻塞。
        // available() 在 BufferedInputStream 上会返回"缓冲区内可用 + 底层已到达"的字节数，
        // 正好满足"只取已到达的部分"这一需求。
        byte[] extra = new byte[0];
        int available = in.available();
        if (available > 0) {
            java.io.ByteArrayOutputStream buffer = new java.io.ByteArrayOutputStream(available);
            byte[] chunk = new byte[Math.min(available, 8192)];
            int remaining = available;
            while (remaining > 0) {
                int count = in.read(chunk, 0, Math.min(chunk.length, remaining));
                if (count <= 0) {
                    break;
                }
                buffer.write(chunk, 0, count);
                remaining -= count;
            }
            extra = buffer.toByteArray();
        }
        return new HttpHeaders(requestLine, headers, extra);
    }

    /** 把消息按 WebSocket 帧上限切分为若干片段（OneBot 文本消息通常远小于此值）。 */
    public static int maxPayloadSize() {
        return 1024 * 1024;
    }
}
