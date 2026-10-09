package com.qqlink.velocity.mc;

import java.nio.charset.StandardCharsets;
import java.util.Set;
import java.util.logging.Logger;

import com.velocitypowered.api.event.Subscribe;
import com.velocitypowered.api.event.connection.PluginMessageEvent;
import com.velocitypowered.api.proxy.ServerConnection;
import com.velocitypowered.api.proxy.messages.MinecraftChannelIdentifier;

/**
 * 子服 → 代理 插件消息入口。
 *
 * <h2>安全：为什么第一件事就是 setResult(handled())</h2>
 * {@link PluginMessageEvent} 会被投递两次：
 * <ul>
 *   <li><strong>客户端 → 代理</strong>（source 是 {@code Player}）</li>
 *   <li><strong>子服 → 代理</strong>（source 是 {@code ServerConnection}）</li>
 * </ul>
 * 如果不把结果显式设为 {@code handled()}，代理会把消息<strong>继续转发给对端</strong>，
 * 于是玩家就能伪造 {@code qqlink:main} 频道消息冒充"子服上报"，甚至伪造 TPS、
 * 伪造管理员公告。因此这里的顺序严格是：
 * <pre>
 *   ① 频道不匹配 → 直接 return（不碰 result，交给其他插件）
 *   ② 频道匹配   → 立刻 setResult(handled())   ← 无论来源是谁都吞掉
 *   ③ 再判断来源是不是 ServerConnection，不是就丢弃
 *   ④ 最后才解析内容
 * </pre>
 * 详见 Velocity 官方文档 "Plugin messaging" 的 caution 段落。
 *
 * <h2>上报类型分发表</h2>
 * <pre>
 *   HELLO &lt;版本&gt;                        → 记录模组在线，回发 REQUEST_METRICS
 *   METRICS &lt;tps&gt; &lt;mspt&gt; &lt;players&gt;       → 更新性能数据（#tps 数据来源）
 *   CHAT &lt;玩家&gt; &lt;消息&gt;                  → 走 MC→QQ 聊天链路
 *   DEATH &lt;玩家&gt; &lt;死亡原因&gt;              → 走死亡通知
 *   ADVANCEMENT &lt;玩家&gt; &lt;标题&gt; &lt;类型&gt;     → 走成就通知
 *   BROADCAST &lt;文本&gt;                     → 走管理员公告通知
 *   SERVER_START / SERVER_STOP           → 走服务器状态通知
 * </pre>
 */
public final class PluginMessageRouter {

    private final Logger logger;
    /** 间接持有路由器，保证 /qqlink reload 重建后本监听器仍指向新实例。 */
    private final com.qqlink.velocity.util.Holder<ChatRouter> routerHolder;
    private final PlayerManager players;
    private final ServerBridge servers;
    private final Set<String> modReported = java.util.concurrent.ConcurrentHashMap.newKeySet();

    public PluginMessageRouter(com.qqlink.velocity.util.Holder<ChatRouter> routerHolder,
                               PlayerManager players, ServerBridge servers, Logger logger) {
        this.routerHolder = routerHolder;
        this.players = players;
        this.servers = servers;
        this.logger = logger;
    }

    /** 当前路由器实例。 */
    private ChatRouter router() {
        return routerHolder.get();
    }

    /** 注册到 Velocity 的频道监听。 */
    public void register(com.velocitypowered.api.proxy.ProxyServer proxy) {
        proxy.getChannelRegistrar().register(PluginMessages.CHANNEL);
        logger.info("[MC] 已注册插件消息频道 " + PluginMessages.CHANNEL_NAME);
    }

    @Subscribe
    public void onPluginMessage(PluginMessageEvent event) {
        // ① 频道校验
        if (!PluginMessages.CHANNEL.equals(event.getIdentifier())) {
            return;
        }
        // ② 立刻吞掉，防止消息在客户端与子服之间被转发（安全关键步骤）
        event.setResult(PluginMessageEvent.ForwardResult.handled());

        // ③ 来源校验：只接受后端服务器
        if (!(event.getSource() instanceof ServerConnection connection)) {
            logger.fine("[MC] 丢弃来自非子服来源的 " + PluginMessages.CHANNEL_NAME + " 消息");
            return;
        }

        // 服务器名以"事件来源连接"为准，绝不读取载荷里自称的名字（防伪造）
        String serverId = connection.getServerInfo().getName();
        String raw = new String(event.getData(), StandardCharsets.UTF_8);

        PluginMessages.Inbound inbound = PluginMessages.parse(raw);
        if (inbound == null) {
            return;
        }

        try {
            dispatch(inbound, serverId);
        } catch (Throwable throwable) {
            logger.warning("[MC] 处理来自 " + serverId + " 的上报失败（" + inbound.type + "）：" + throwable);
        }
    }

    private void dispatch(PluginMessages.Inbound inbound, String serverId) {
        // 路由器可能因为"插件正在重载"而短暂为 null，这里统一兜底，避免 NPE 刷日志
        ChatRouter router = router();
        if (router == null) {
            return;
        }
        switch (inbound.type) {
            case PluginMessages.UP_HELLO -> {
                if (modReported.add(serverId)) {
                    logger.info("[MC] 子服 " + serverId + " 的 qqlink-fabric 模组已就绪（版本 "
                            + inbound.payload + "）");
                }
                // 让模组立刻上报一次指标，这样 #tps 不用等 5 秒
                servers.sendToServer(serverId,
                        new String(PluginMessages.encodeDown(PluginMessages.DOWN_REQUEST_METRICS, ""),
                                StandardCharsets.UTF_8),
                        PluginMessages.CHANNEL_NAME);
            }
            case PluginMessages.UP_METRICS -> {
                // 格式：<tps> <mspt> [players]
                String[] parts = inbound.payload.split("\\s+");
                if (parts.length < 2) {
                    return;
                }
                double tps = com.qqlink.velocity.util.Strings.parseDouble(parts[0], -1D);
                double mspt = com.qqlink.velocity.util.Strings.parseDouble(parts[1], -1D);
                if (tps < 0D || mspt < 0D) {
                    return;
                }
                players.updateMetrics(serverId, tps, mspt);
                if (parts.length >= 3) {
                    players.setLastKnownCount(serverId, com.qqlink.velocity.util.Strings.parseInt(parts[2], 0));
                }
                // 首次收到指标 = 子服刚启动完成，推一次"服务器启动"通知
                if (players.shouldNotifyStart(serverId)) {
                    router.onServerStart(serverId);
                }
            }
            case PluginMessages.UP_CHAT -> {
                // 格式：<玩家名> <消息>
                String[] parts = inbound.payload.split(" ", 2);
                if (parts.length < 2) {
                    return;
                }
                router.onPlayerChat(parts[0], serverId, parts[1]);
                router.relayToOtherServers(parts[0], serverId, parts[1]);
            }
            case PluginMessages.UP_DEATH -> {
                String[] parts = inbound.payload.split(" ", 2);
                if (parts.length < 1 || parts[0].isEmpty()) {
                    return;
                }
                String cause = parts.length > 1 ? parts[1] : "";
                router.onPlayerDeath(parts[0], serverId, cause);
            }
            case PluginMessages.UP_ADVANCEMENT -> {
                // 格式：<玩家名> <进度标题> [类型]
                String[] parts = inbound.payload.split(" ", 3);
                if (parts.length < 2) {
                    return;
                }
                String frame = parts.length > 2 ? parts[2] : "task";
                router.onAdvancement(parts[0], serverId, parts[1], frame);
            }
            case PluginMessages.UP_BROADCAST -> router.onAdminBroadcast(serverId, inbound.payload);
            case PluginMessages.UP_SERVER_START -> {
                players.resetStartNotification(serverId);
                router.onServerStart(serverId);
            }
            case PluginMessages.UP_SERVER_STOP -> {
                players.markOffline(serverId);
                router.onServerStop(serverId);
            }
            default -> logger.fine("[MC] 未知上报类型：" + inbound.type);
        }
    }

    /** 模组是否已就绪（用于状态展示，说明 #tps 是否可用）。 */
    public boolean modReady(String serverId) {
        return modReported.contains(serverId);
    }

    public Set<String> modReadyServers() {
        return Set.copyOf(modReported);
    }

    /** 兼容旧版本：直接注册频道标识。 */
    public static MinecraftChannelIdentifier channel() {
        return PluginMessages.CHANNEL;
    }
}
