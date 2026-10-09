package com.qqlink.fabric;

import java.util.List;

import com.mojang.brigadier.CommandDispatcher;

import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.fabricmc.fabric.api.entity.event.v1.ServerLivingEntityEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.message.v1.ServerMessageEvents;
import net.fabricmc.fabric.api.networking.v1.PayloadTypeRegistry;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.minecraft.entity.LivingEntity;
import net.minecraft.entity.damage.DamageSource;
import net.minecraft.network.packet.CustomPayload;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.command.ServerCommandSource;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.text.Text;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.qqlink.fabric.mixin.MinecraftServerTickTimesAccessor;

/**
 * qqlink-fabric 主入口（服务端模组，★可选组件）。
 *
 * <h2>本模组到底解决了什么问题</h2>
 * 代理端（Velocity）活在"协议层"，它看得到<strong>谁连了哪个子服</strong>，
 * 但看不到<strong>子服内部发生了什么</strong>。以下四类信息只有子服自己知道：
 * <ol>
 *   <li><strong>TPS / MSPT</strong> —— 只有跑游戏主循环的服务端才有 tick 耗时；</li>
 *   <li><strong>死亡原因</strong> —— Velocity 没有死亡事件（{@code PlayerDeathEvent} 不存在）；</li>
 *   <li><strong>成就解锁</strong> —— 同上；</li>
 *   <li><strong>服务器启动完成</strong> —— "端口可连"不等于"世界加载完毕"。</li>
 * </ol>
 * 本模组把这些信息通过自定义插件消息通道 {@code qqlink:main} 推给代理，
 * 代理再统一转发到 QQ 群。
 *
 * <h2>不装也能用（重要）</h2>
 * 以下功能<strong>完全由代理端实现</strong>，与本模组无关：
 * <pre>
 *   ✅ 双向聊天转发（MC → QQ，QQ → MC）
 *   ✅ 玩家加入 / 退出通知
 *   ✅ 玩家切换子服通知
 *   ✅ #list / #broadcast / #send / #help 等全部 QQ 指令
 *   ✅ 服务器上下线通知（代理侧启发式判定）
 *   ✅ 管理员公告（/say、/broadcast、/me，由代理从玩家聊天事件识别）
 * </pre>
 * 装了本模组后会<strong>增强</strong>：
 * <pre>
 *   ⭐ #tps 能显示真实 TPS / MSPT
 *   ⭐ 死亡通知带精确死因
 *   ⭐ 成就通知更准确（含成就分类，可过滤配方解锁）
 * </pre>
 *
 * <h2>上报时机</h2>
 * <table border="1">
 *   <tr><th>事件</th><th>上报内容</th><th>触发点</th></tr>
 *   <tr><td>模组加载完成</td><td>HELLO</td><td>SERVER_STARTED</td></tr>
 *   <tr><td>每 5 秒</td><td>METRICS</td><td>END_SERVER_TICK（有玩家时）</td></tr>
 *   <tr><td>代理索取</td><td>METRICS</td><td>收到 REQUEST_METRICS</td></tr>
 *   <tr><td>玩家死亡</td><td>DEATH</td><td>ServerLivingEntityEvents.AFTER_DEATH</td></tr>
 *   <tr><td>玩家达成成就</td><td>ADVANCEMENT</td><td>解析原版成就广播消息</td></tr>
 *   <tr><td>管理员 /say /me</td><td>BROADCAST</td><td>解析原版命令广播消息</td></tr>
 *   <tr><td>服务器关闭</td><td>SERVER_STOP</td><td>SERVER_STOPPING</td></tr>
 * </table>
 */
public final class QQGroupLinkFabric implements ModInitializer {

    private static final Logger LOGGER = LoggerFactory.getLogger("qqlink-fabric");

    /** 指标上报间隔（纳秒）：5 秒。 */
    private static final long METRICS_INTERVAL_NANOS = 5_000_000_000L;

    /** Minecraft 的 tickTimes 环形数组长度。 */
    private static final int TICK_WINDOW = 100;

    /** 是否为专用服务器（避免在单人存档里也去连代理）。 */
    private volatile boolean dedicatedServer = false;
    private volatile boolean playerSeen = false;
    private volatile long lastTickNanos = 0L;
    /** 自上次上报以来累计的 tick 间隔（纳秒）与 tick 次数 */
    private volatile long pendingIntervalNanos = 0L;
    private volatile int pendingTicks = 0;
    private volatile long lastMetricsSentNanos = 0L;

    @Override
    public void onInitialize() {
        LOGGER.info("[QQGroupLink] qqlink-fabric {} 正在初始化……", Protocol.MOD_VERSION);

        registerPayload();
        registerBroadcastReceiver();
        registerLifecycle();
        registerMetrics();
        registerDeath();
        registerMessageListener();
        registerCommandCallback();

        LOGGER.info("[QQGroupLink] 初始化完成。聊天互通由代理端负责，本模组只负责上报性能/死因/成就。");
    }

    // ==================================================================
    // 网络载荷
    // ==================================================================

    /** 自定义载荷：把一段文本从子服发到代理。 */
    public record QqLinkPayload(String text) implements CustomPayload {

        public static final CustomPayload.Id<QqLinkPayload> ID =
                CustomPayload.id("qqlink:main");

        public static final net.minecraft.network.codec.PacketCodec<
                net.minecraft.network.RegistryByteBuf, QqLinkPayload> CODEC =
                net.minecraft.network.codec.PacketCodec.tuple(
                        net.minecraft.network.codec.PacketCodecs.STRING,
                        QqLinkPayload::text,
                        QqLinkPayload::new);

        public static final CustomPayload.Type<net.minecraft.network.RegistryByteBuf, QqLinkPayload> TYPE =
                new CustomPayload.Type<>(ID, CODEC);

        @Override
        public CustomPayload.Id<? extends CustomPayload> getId() {
            return ID;
        }
    }

    private void registerPayload() {
        try {
            // 向"客户端 → 服务端"方向注册。在 Velocity 代理场景下，
            // 代理扮演的就是"客户端"角色：它以玩家连接的名义把插件消息发给子服。
            PayloadTypeRegistry.playC2S().register(QqLinkPayload.ID, QqLinkPayload.CODEC);
            LOGGER.info("[QQGroupLink] 已注册载荷类型 {}:{}（C2S）", Protocol.CHANNEL.split(":")[0],
                    Protocol.CHANNEL.split(":")[1]);
        } catch (Throwable throwable) {
            // 不同 Minecraft / Fabric API 版本的载荷注册 API 有差异，
            // 这里容错处理：注册失败只影响性能上报，不影响游戏运行与其他互通功能。
            LOGGER.error("[QQGroupLink] 注册载荷类型失败（性能上报将不可用）：{}", throwable.toString());
        }
    }

    /**
     * 接收代理下发的指令帧。
     *
     * <p>回调参数说明：
     * <ul>
     *   <li>{@code payload} —— 代理发来的文本帧（形如 {@code BROADCAST <文本>}）；</li>
     *   <li>{@code context.player()} —— 与代理通信所依托的玩家连接。
     *       代理端通过 {@code RegisteredServer.sendPluginMessage} 发送时，
     *       会借用该子服上的任意一名玩家连接；因此只有<strong>至少有一名玩家在线</strong>时
     *       代理才能向子服下发消息（这也是 Minecraft 插件消息机制的固有限制）。</li>
     * </ul>
     */
    private void registerBroadcastReceiver() {
        try {
            ServerPlayNetworking.registerGlobalReceiver(QqLinkPayload.ID, (payload, context) -> {
                String text = payload.text();
                if (text == null || text.isEmpty()) {
                    return;
                }
                // 网络回调可能不在主线程：凡是碰世界/玩家的操作都必须切回服务端线程
                MinecraftServer server = context.player().getEntityWorld().getServer();
                if (server == null) {
                    return;
                }
                server.execute(() -> handleDownlink(server, text));
            });
        } catch (Throwable throwable) {
            LOGGER.error("[QQGroupLink] 注册下行接收器失败（#broadcast/#send 在游戏内可能不生效）：{}",
                    throwable.toString());
        }
    }

    /** 处理代理下发的文本指令。 */
    private void handleDownlink(MinecraftServer server, String text) {
        int space = text.indexOf(' ');
        String type = space < 0 ? text : text.substring(0, space);
        String payload = space < 0 ? "" : text.substring(space + 1);

        switch (type) {
            case Protocol.DOWN_REQUEST_METRICS -> sendMetrics(server, true);
            case Protocol.DOWN_BROADCAST, Protocol.DOWN_CHAT -> {
                if (payload.isEmpty()) {
                    return;
                }
                // 单行发送：把 \n 转成空格，避免刷屏
                String single = payload.replace('\\', '/').replace('\n', ' ').replace('\r', ' ');
                server.getPlayerManager().broadcast(Text.literal(single), false);
            }
            default -> LOGGER.debug("[QQGroupLink] 忽略未知下行指令：{}", type);
        }
    }

    // ==================================================================
    // 生命周期
    // ==================================================================

    private void registerLifecycle() {
        ServerLifecycleEvents.SERVER_STARTED.register(server -> {
            // 通过"是否为专用服务器"判断，避免在单人存档里也去上报
            dedicatedServer = server instanceof net.minecraft.server.dedicated.DedicatedServer;
            lastTickNanos = 0L;
            pendingIntervalNanos = 0L;
            pendingTicks = 0;
            if (!dedicatedServer) {
                LOGGER.info("[QQGroupLink] 检测到集成（单人）服务器，跳过上报");
                return;
            }
            // 此时世界已加载完毕，是"服务器启动完成"的准确时刻
            send(server, Protocol.UP_SERVER_START, null);
            LOGGER.info("[QQGroupLink] 已通知代理：服务器启动完成");
        });

        ServerLifecycleEvents.SERVER_STOPPING.register(server -> {
            if (!dedicatedServer) {
                return;
            }
            send(server, Protocol.UP_SERVER_STOP, null);
            LOGGER.info("[QQGroupLink] 已通知代理：服务器正在关闭");
        });

        // 玩家第一次进入时才真正与代理建立插件消息通道，这里补发一次 HELLO 与指标
        net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents.JOIN.register((handler, sender, server) -> {
            if (!dedicatedServer) {
                return;
            }
            boolean first = !playerSeen;
            playerSeen = true;
            send(server, Protocol.UP_HELLO, Protocol.MOD_VERSION);
            if (first) {
                LOGGER.info("[QQGroupLink] 检测到首位玩家连接，已与代理建立上报通道");
            }
            sendMetrics(server, true);
        });
    }

    // ==================================================================
    // TPS / MSPT
    // ==================================================================

    private void registerMetrics() {
        net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents.END_SERVER_TICK.register(server -> {
            if (!dedicatedServer) {
                return;
            }
            long now = System.nanoTime();
            long previous = lastTickNanos;
            lastTickNanos = now;
            if (previous != 0L) {
                long delta = now - previous;
                // 过滤掉极端异常值（例如服务器暂停、断点调试）避免污染统计
                if (delta > 0L && delta < 10_000_000_000L) {
                    pendingIntervalNanos += delta;
                    pendingTicks++;
                }
            }
            if (now - lastMetricsSentNanos >= METRICS_INTERVAL_NANOS) {
                // 没有任何玩家时不必上报（代理也收不到：插件消息需要一条玩家连接）
                if (server.getPlayerManager().getPlayerList().size() > 0) {
                    sendMetrics(server, false);
                } else {
                    resetMetricsWindow();
                }
            }
        });
    }

    /**
     * 计算并上报一次性能指标。
     *
     * <h3>MSPT</h3>
     * 直接读取 {@code MinecraftServer#tickTimes}（最近 100 次 tick 的纳秒耗时），
     * 取平均后除以 1e6。这是游戏主循环自己记录的"纯 tick 耗时"，
     * 与 Spark / {@code /debug} 的数据源一致。
     *
     * <h3>TPS</h3>
     * <pre>
     *   实际 tick 间隔(ms) = 本窗口内累计的 tick 间隔 / 本窗口内的 tick 次数
     *   TPS = min(20, 1000 / 实际 tick 间隔(ms))
     * </pre>
     * 服务端以 20 TPS 为目标：当每 tick 耗时 &lt; 50ms 时 TPS 恒为 20；
     * 一旦超过 50ms，实际 tick 频率下降，此时上式给出真实 TPS。
     * 这是 Spark 等性能工具通用的估算方式。
     */
    private void sendMetrics(MinecraftServer server, boolean forced) {
        if (!dedicatedServer) {
            return;
        }
        double mspt = computeMspt(server);
        double tps = computeTps(mspt, forced);
        int players = server.getPlayerManager().getPlayerList().size();

        if (mspt < 0D || tps < 0D) {
            resetMetricsWindow();
            return;
        }
        send(server, Protocol.UP_METRICS, String.format(java.util.Locale.ROOT,
                "%.2f %.2f %d", tps, mspt, players));

        resetMetricsWindow();
    }

    private void resetMetricsWindow() {
        lastMetricsSentNanos = System.nanoTime();
        pendingIntervalNanos = 0L;
        pendingTicks = 0;
    }

    private double computeMspt(MinecraftServer server) {
        try {
            long[] tickTimes = ((MinecraftServerTickTimesAccessor) server).qqlink$tickTimes();
            if (tickTimes == null || tickTimes.length == 0) {
                return 50D;
            }
            int count = Math.min(TICK_WINDOW, tickTimes.length);
            long total = 0L;
            int used = 0;
            for (int i = 0; i < count; i++) {
                long value = tickTimes[i];
                // 0 表示该槽位还没被填充（服务器刚启动）
                if (value > 0L) {
                    total += value;
                    used++;
                }
            }
            if (used == 0) {
                return 50D;
            }
            return (total / (double) used) / 1_000_000D;
        } catch (Throwable throwable) {
            LOGGER.debug("[QQGroupLink] 读取 tickTimes 失败：{}", throwable.toString());
            return -1D;
        }
    }

    private double computeTps(double mspt, boolean forced) {
        if (pendingTicks <= 0 || pendingIntervalNanos <= 0L) {
            // 采样窗口还没有数据（服务器刚启动）：
            // 此时用 MSPT 反推——每 tick 耗时不超过 50ms 就是满速 20 TPS
            if (mspt >= 0D) {
                return mspt <= 50D ? 20D : Math.min(20D, 1000D / mspt);
            }
            return forced ? 20D : -1D;
        }
        double averageTickMillis = pendingIntervalNanos / 1_000_000D / pendingTicks;
        if (averageTickMillis <= 0D) {
            return 20D;
        }
        return Math.min(20D, 1000D / averageTickMillis);
    }

    // ==================================================================
    // 玩家死亡
    // ==================================================================

    private void registerDeath() {
        ServerLivingEntityEvents.AFTER_DEATH.register((LivingEntity entity, DamageSource damageSource) -> {
            if (!dedicatedServer) {
                return;
            }
            if (!(entity instanceof ServerPlayerEntity player)) {
                return;
            }
            String cause = extractCause(player, damageSource);
            send(player.getEntityWorld().getServer(), Protocol.UP_DEATH, player.getGameProfile().name() + " " + cause);
        });
    }

    /**
     * 提取"死因"文本。
     *
     * <p>Minecraft 已经帮我们把死亡原因拼好了：{@code CombatTracker#getDeathMessage()}
     * 返回形如 {@code "Steve was slain by Zombie"} 的完整句子，
     * 且会把玩家名放在最前面。因此这里取该句子并<strong>去掉开头的玩家名</strong>，
     * 就得到 {@code "was slain by Zombie"} 这段可直接读的死因。
     *
     * <p>同时把 {@code Text} 转成纯文本（{@code getString()}），
     * 避免把 § 颜色代码原样带到 QQ 里。
     */
    private String extractCause(ServerPlayerEntity player, DamageSource damageSource) {
        try {
            Text message = player.getDamageTracker().getDeathMessage();
            if (message != null) {
                String full = message.getString();
                String name = player.getGameProfile().name();
                if (full.startsWith(name)) {
                    String rest = full.substring(name.length()).trim();
                    if (!rest.isEmpty()) {
                        return rest;
                    }
                }
                if (!full.isEmpty()) {
                    return full;
                }
            }
        } catch (Throwable throwable) {
            LOGGER.debug("[QQGroupLink] 获取死亡消息失败：{}", throwable.toString());
        }
        // 兜底：用伤害类型名，读起来不完美但不会缺字段
        try {
            return "died (" + damageSource.getName() + ")";
        } catch (Throwable throwable) {
            return "died";
        }
    }

    // ==================================================================
    // 成就 / 公告：通过解析原版广播消息实现
    // ==================================================================

    /**
     * 监听原版广播的游戏消息。
     *
     * <h2>为什么用"解析消息"而不是专门的成就事件</h2>
     * Minecraft 的成就广播本身就走 {@code GAME_MESSAGE} 通道，且原版已经做好了
     * 措辞、分类（任务/目标/挑战）与去重（同一成就不会重复广播）。
     * 直接解析这条消息可以一次性拿到：
     * <ul>
     *   <li>成就通知（含分类：任务 / 目标 / 挑战）；</li>
     *   <li>配方解锁（可被代理端配置过滤）；</li>
     *   <li>管理员通过 {@code /say}、{@code /me} 发送的命令广播。</li>
     * </ul>
     *
     * <h2>已知限制</h2>
     * <ul>
     *   <li>解析依赖服务端语言。英文语言包下格式固定，已做完整适配；
     *       中文语言包下同时适配了常见的"达成了进度"措辞。
     *       若服务端使用其他语言，成就通知会退化为"整条消息直接转发"，
     *       仍然可用，只是分类信息不准确；</li>
     *   <li>死亡消息<strong>不</strong>在这里处理 —— 它由 {@code AFTER_DEATH} 事件
     *       提供更精确的死因，两者都处理会导致重复推送。</li>
     * </ul>
     */
    private void registerMessageListener() {
        ServerMessageEvents.GAME_MESSAGE.register((server, message, overlay) -> {
            if (!dedicatedServer || overlay) {
                // overlay = true 表示这条消息显示在快捷栏上方（如"床已设置"），不是广播
                return;
            }
            String text = message.getString();
            if (text.isEmpty()) {
                return;
            }
            try {
                handleGameMessage(server, text);
            } catch (Throwable throwable) {
                LOGGER.debug("[QQGroupLink] 解析游戏消息失败：{}", throwable.toString());
            }
        });

        // /say 与 /me 走命令广播通道，单独监听
        ServerMessageEvents.COMMAND_MESSAGE.register((message, source, params) -> {
            if (!dedicatedServer) {
                return;
            }
            try {
                String text = message.getSignedContent();
                if (!text.isEmpty()) {
                    send(source.getServer(), Protocol.UP_BROADCAST, text);
                }
            } catch (Throwable throwable) {
                LOGGER.debug("[QQGroupLink] 解析命令广播失败：{}", throwable.toString());
            }
        });
    }

    // ---- 原版成就消息的格式（英文语言包）----
    private static final String[] ADVANCEMENT_EN = {
            " has made the advancement ",   // 普通进度
            " has reached the goal ",       // 目标
            " has completed the challenge ",// 挑战
            " has discovered the recipe "   // 配方（会被代理过滤）
    };
    // ---- 中文语言包常见措辞 ----
    private static final String[] ADVANCEMENT_CN = {
            " 达成了进度 ",
            " 达成了目标 ",
            " 完成了挑战 ",
            " 发现了配方 "
    };

    private void handleGameMessage(MinecraftServer server, String text) {
        if (tryAdvancement(server, text, ADVANCEMENT_EN, new String[]{"task", "goal", "challenge", "recipe"})) {
            return;
        }
        if (tryAdvancement(server, text, ADVANCEMENT_CN, new String[]{"task", "goal", "challenge", "recipe"})) {
            return;
        }
        // 其他游戏消息（如"玩家加入了游戏"）由代理端负责，避免重复推送
    }

    private boolean tryAdvancement(MinecraftServer server, String text, String[] markers, String[] frames) {
        for (int i = 0; i < markers.length; i++) {
            int index = text.indexOf(markers[i]);
            if (index <= 0) {
                continue;
            }
            String playerName = text.substring(0, index).trim();
            String rest = text.substring(index + markers[i].length()).trim();
            // rest 形如 "[Stone Age]" 或 "「石器时代」"，去掉首尾括号
            String title = trimBrackets(rest);
            if (playerName.isEmpty() || title.isEmpty()) {
                continue;
            }
            send(server, Protocol.UP_ADVANCEMENT,
                    playerName + " " + title + " " + frames[i]);
            return true;
        }
        return false;
    }

    /** 去掉标题两侧的方括号 / 中文括号。 */
    private String trimBrackets(String value) {
        String result = value.trim();
        if (result.length() < 2) {
            return result;
        }
        char first = result.charAt(0);
        char last = result.charAt(result.length() - 1);
        if ((first == '[' && last == ']')
                || (first == '\u3010' && last == '\u3011')
                || (first == '<' && last == '>')
                || (first == '\u300c' && last == '\u300d')
                || (first == '"' && last == '"')) {
            return result.substring(1, result.length() - 1).trim();
        }
        return result;
    }

    // ==================================================================
    // 命令派发器回调（仅用于日志排障）
    // ==================================================================

    /**
     * 注册命令派发器构建完成的回调。
     *
     * <p>这里只打一条 debug 日志，用于排查"#broadcast 在游戏内没生效"这类问题。
     * 最常见的原因不是本模组的问题，而是：<strong>该子服当前没有玩家在线</strong>，
     * 导致代理无法通过插件消息把内容下发过来（Minecraft 自定义载荷依附在玩家连接上）。
     * 遇到该情况可执行 {@code /qqlink status} 检查"模组已就绪的子服"列表。
     */
    private void registerCommandCallback() {
        try {
            CommandRegistrationCallback.EVENT.register((CommandDispatcher<ServerCommandSource> dispatcher,
                                                        net.minecraft.command.CommandRegistryAccess registryAccess,
                                                        net.minecraft.server.command.CommandManager.RegistrationEnvironment environment) ->
                    LOGGER.debug("[QQGroupLink] 命令派发器已就绪（环境：{}）", environment));
        } catch (Throwable throwable) {
            LOGGER.debug("[QQGroupLink] 注册命令回调失败（不影响功能）：{}", throwable.toString());
        }
    }

    // ==================================================================
    // 发送
    // ==================================================================

    /**
     * 把一条帧发给代理。
     *
     * <p>实现细节：{@link ServerPlayNetworking#send} 需要一名玩家作为载体
     * （Minecraft 的自定义载荷依附在玩家连接上）。
     * 代理端通过 {@code RegisteredServer#sendPluginMessage} 下发时也是同样的机制。
     * 因此这里取在线玩家列表里的第一个作为载体；
     * <strong>没有玩家在线时无法上报</strong>，这是协议层的固有限制，
     * 代理端已通过"启发式下线判定"做了兜底。
     */
    private void send(MinecraftServer server, String type, String payload) {
        try {
            if (server == null) {
                return;
            }
            List<ServerPlayerEntity> players = server.getPlayerManager().getPlayerList();
            if (players.isEmpty()) {
                return;
            }
            String frame = Protocol.encode(type, payload);
            ServerPlayerEntity carrier = players.get(0);
            ServerPlayNetworking.send(carrier, new QqLinkPayload(frame));
        } catch (Throwable throwable) {
            LOGGER.debug("[QQGroupLink] 上报 {} 失败：{}", type, throwable.toString());
        }
    }
}
