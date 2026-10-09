package com.qqlink.velocity.mc;

import java.nio.charset.StandardCharsets;

import com.velocitypowered.api.proxy.messages.MinecraftChannelIdentifier;

/**
 * 代理 ↔ 子服 之间的自定义插件消息协议。
 *
 * <h2>为什么需要它</h2>
 * 以下几类信息<strong>代理层拿不到</strong>，只能由后端主动上报：
 * <ul>
 *   <li><strong>TPS / MSPT</strong> —— 只有跑游戏主循环的服务端才知道；</li>
 *   <li><strong>死亡原因</strong> —— Velocity 没有死亡事件；</li>
 *   <li><strong>成就解锁</strong> —— 同上；</li>
 *   <li><strong>管理员公告</strong> —— {@code /say}、{@code /broadcast} 在子服执行，
 *       代理默认看不到（需要发送者带权限节点时才能从 PlayerChatEvent 捕获）。</li>
 * </ul>
 * 因此提供可选伴随模组 {@code qqlink-fabric}，通过本协议把上述信息推给代理。
 * <strong>不装模组也能用</strong>：只有 TPS/MSPT、死亡原因、成就三项降级，
 * 聊天 / 加入退出 / 服务器状态 / 指令全部照常工作。
 *
 * <h2>线格式（UTF-8 文本，简单且便于用抓包工具排查）</h2>
 * 第一个空格之前的 token 是消息类型，其余是负载：
 * <pre>
 *   子服 → 代理（上行）
 *     HELLO &lt;模组版本&gt;                  模组加载完成
 *     METRICS &lt;tps&gt; &lt;mspt&gt; &lt;players&gt;    每 5 秒一次性能上报
 *     CHAT &lt;玩家名&gt; &lt;消息&gt;              玩家聊天（走模组通道时启用）
 *     DEATH &lt;玩家名&gt; &lt;死亡原因&gt;          玩家死亡
 *     ADVANCEMENT &lt;玩家名&gt; &lt;进度标题&gt; &lt;类型&gt;  解锁成就
 *     BROADCAST &lt;文本&gt;                  管理员公告（/say、/broadcast、控制台）
 *     SERVER_START / SERVER_STOP        子服生命周期
 *
 *   代理 → 子服（下行）
 *     CHAT &lt;文本&gt;                        要求子服把文本广播给所有玩家
 *     BROADCAST &lt;文本&gt;                  全服公告
 *     REQUEST_METRICS                    要求立即上报一次指标
 * </pre>
 *
 * <h2>安全性</h2>
 * 插件消息频道在代理侧注册后，只有<strong>后端服务器</strong>能发到代理；
 * 客户端发来的同频道消息会被标记为 handled 并丢弃，防止玩家伪造 TPS 或公告。
 */
public final class PluginMessages {

    /** 主通信频道。 */
    public static final String CHANNEL_NAME = "qqlink:main";
    public static final MinecraftChannelIdentifier CHANNEL = MinecraftChannelIdentifier.from(CHANNEL_NAME);

    // ---------------- 上行类型 ----------------
    public static final String UP_HELLO = "HELLO";
    public static final String UP_METRICS = "METRICS";
    public static final String UP_CHAT = "CHAT";
    public static final String UP_DEATH = "DEATH";
    public static final String UP_ADVANCEMENT = "ADVANCEMENT";
    public static final String UP_BROADCAST = "BROADCAST";
    public static final String UP_SERVER_START = "SERVER_START";
    public static final String UP_SERVER_STOP = "SERVER_STOP";

    // ---------------- 下行类型 ----------------
    public static final String DOWN_CHAT = "CHAT";
    public static final String DOWN_BROADCAST = "BROADCAST";
    public static final String DOWN_REQUEST_METRICS = "REQUEST_METRICS";

    /** 单条消息的最大字节数（Minecraft 协议上限 32767，这里留出余量）。 */
    public static final int MAX_BYTES = 30_000;

    private PluginMessages() {
    }

    /**
     * 解析上行消息。
     *
     * @param raw 原始文本（UTF-8 解码后）
     * @return 解析结果；格式非法返回 null
     */
    public static Inbound parse(String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        int space = raw.indexOf(' ');
        String type = space < 0 ? raw : raw.substring(0, space);
        String rest = space < 0 ? "" : raw.substring(space + 1);
        return new Inbound(type.toUpperCase(java.util.Locale.ROOT), rest);
    }

    /** 编码一条下行消息。 */
    public static byte[] encodeDown(String type, String payload) {
        String text = payload == null || payload.isEmpty() ? type : type + " " + payload;
        byte[] bytes = text.getBytes(StandardCharsets.UTF_8);
        if (bytes.length <= MAX_BYTES) {
            return bytes;
        }
        // 超长消息按字节安全截断（避免截断到多字节字符中间产生乱码）
        String truncated = text;
        while (truncated.getBytes(StandardCharsets.UTF_8).length > MAX_BYTES && truncated.length() > 1) {
            truncated = truncated.substring(0, truncated.length() - 1);
        }
        return truncated.getBytes(StandardCharsets.UTF_8);
    }

    /** 上行消息视图。 */
    public static final class Inbound {
        /** 类型 token（已大写） */
        public final String type;
        /** 类型之后的全部内容 */
        public final String payload;

        Inbound(String type, String payload) {
            this.type = type;
            this.payload = payload;
        }

        /** 按空白切分负载；limit 为返回的最大段数（最后一段包含剩余内容）。 */
        public String[] split(int limit) {
            if (payload.isEmpty()) {
                return new String[0];
            }
            return payload.split(" ", Math.max(1, limit));
        }

        /**
         * 取第 index 段（0 基），越界返回空字符串。
         * 使用 {@code split(" ", limit)} 时最后一段会保留剩余内容（含空格），
         * 因此"消息正文"这类可能含空格的内容应放在最后一段读取。
         */
        public String part(int index, int limit) {
            String[] parts = split(limit);
            return index < parts.length ? parts[index] : "";
        }

        public double decimal(int index, int limit) {
            return com.qqlink.velocity.util.Strings.parseDouble(part(index, limit), -1D);
        }

        public int integer(int index, int limit) {
            return com.qqlink.velocity.util.Strings.parseInt(part(index, limit), 0);
        }

        @Override
        public String toString() {
            return type + " " + payload;
        }
    }
}
