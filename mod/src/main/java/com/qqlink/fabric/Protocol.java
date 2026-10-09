package com.qqlink.fabric;

/**
 * 代理 ↔ 子服 插件消息协议常量（与代理端 {@code com.qqlink.velocity.mc.PluginMessages} 保持一致）。
 *
 * <h2>为什么把协议抄两份而不是共享模块</h2>
 * 代理端插件运行在 Velocity（Java 21 / 无 Minecraft 依赖），
 * 子服模组运行在 Fabric（依赖 Minecraft 类）。
 * 两者<strong>没有任何公共依赖</strong>，硬抽一个共享模块会引入
 * "模组代码被代理插件 classpath 加载" 的风险（可能触发类加载失败）。
 * 因此这里采用"常量复制 + 注释交叉引用"的方式，代价是需要同步修改两处；
 * 协议本身极简（一行 token + 空格分隔负载），版本兼容压力很小。
 *
 * <h2>线格式</h2>
 * 第一个空格之前是类型，其余是负载，全部 UTF-8：
 * <pre>
 *   HELLO &lt;模组版本&gt;
 *   METRICS &lt;tps&gt; &lt;mspt&gt; &lt;players&gt;
 *   CHAT &lt;玩家名&gt; &lt;消息&gt;
 *   DEATH &lt;玩家名&gt; &lt;死亡原因&gt;
 *   ADVANCEMENT &lt;玩家名&gt; &lt;进度标题&gt; &lt;类型&gt;
 *   BROADCAST &lt;文本&gt;
 *   SERVER_START
 *   SERVER_STOP
 * </pre>
 * 下行（代理 → 子服）：
 * <pre>
 *   CHAT &lt;文本&gt;              以 玩家名: 消息 的形式广播给本服玩家
 *   BROADCAST &lt;文本&gt;         全服公告
 *   REQUEST_METRICS          要求立刻上报一次指标
 * </pre>
 */
public final class Protocol {

    /** 频道名，必须与代理端完全一致（命名空间:路径）。 */
    public static final String CHANNEL = "qqlink:main";

    /** 模组版本，随 HELLO 上报，便于代理端日志显示。 */
    public static final String MOD_VERSION = "1.0.0";

    // ---------------- 上行 ----------------
    public static final String UP_HELLO = "HELLO";
    public static final String UP_METRICS = "METRICS";
    public static final String UP_CHAT = "CHAT";
    public static final String UP_DEATH = "DEATH";
    public static final String UP_ADVANCEMENT = "ADVANCEMENT";
    public static final String UP_BROADCAST = "BROADCAST";
    public static final String UP_SERVER_START = "SERVER_START";
    public static final String UP_SERVER_STOP = "SERVER_STOP";

    // ---------------- 下行 ----------------
    public static final String DOWN_CHAT = "CHAT";
    public static final String DOWN_BROADCAST = "BROADCAST";
    public static final String DOWN_REQUEST_METRICS = "REQUEST_METRICS";

    /** 单条消息的安全上限（Minecraft 自定义载荷上限 32767 字节）。 */
    public static final int MAX_BYTES = 30_000;

    private Protocol() {
    }

    /** 拼接一条上行消息文本。 */
    public static String encode(String type, String payload) {
        if (payload == null || payload.isEmpty()) {
            return type;
        }
        String text = type + " " + payload;
        // 安全截断：极端情况下（超长公告）避免超过协议上限导致断连
        if (text.getBytes(java.nio.charset.StandardCharsets.UTF_8).length > MAX_BYTES) {
            while (text.getBytes(java.nio.charset.StandardCharsets.UTF_8).length > MAX_BYTES
                    && text.length() > type.length() + 1) {
                text = text.substring(0, text.length() - 1);
            }
        }
        return text;
    }
}
