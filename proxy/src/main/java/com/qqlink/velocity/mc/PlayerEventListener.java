package com.qqlink.velocity.mc;

import java.util.concurrent.ConcurrentHashMap;
import java.util.logging.Logger;

import com.velocitypowered.api.event.Subscribe;
import com.velocitypowered.api.event.connection.DisconnectEvent;
import com.velocitypowered.api.event.connection.PluginMessageEvent;
import com.velocitypowered.api.event.player.PlayerChatEvent;
import com.velocitypowered.api.event.player.ServerPostConnectEvent;
import com.velocitypowered.api.proxy.Player;
import com.velocitypowered.api.proxy.ServerConnection;

import com.qqlink.velocity.util.Strings;

/**
 * Velocity 事件监听：把代理层能观测到的玩家行为翻译成"业务事件"。
 *
 * <h2>为什么加入/退出要监听两个不同的事件</h2>
 * Velocity 里"进入代理"和"进入某个子服"是两件事：
 * <ul>
 *   <li>{@link LoginEvent} —— 玩家通过了登录校验（Mojang 认证 / 离线模式均可），
 *       此时<strong>还没有</strong>连上任何子服，{@code getCurrentServer()} 为空；</li>
 *   <li>{@link ServerPostConnectEvent} —— 玩家已连上某个子服，此时才能拿到
 *       {@code serverId}，也才有意义发"[生存服] 玩家加入"。</li>
 * </ul>
 * 因此：
 * <ul>
 *   <li>"加入"以 {@code ServerPostConnectEvent} 为准（能带上子服名）；</li>
 *   <li>"退出"以 {@link DisconnectEvent} 为准，子服名从
 *       {@link PlayerManager#lastServer(java.util.UUID)} 里取最后记录（因为断开时
 *       {@code getCurrentServer()} 可能已经为空）；</li>
 *   <li>子服之间跳转时，{@code ServerPostConnectEvent} 会再次触发，
 *       此时把 {@code getPreviousServer()} 有值的情况识别为"切换"，
 *       并按 {@code forwarding.suppress-switch-noise} 决定是发两条（退+进）
 *       还是发一条"切换服务器"。</li>
 * </ul>
 *
 * <h2>聊天监听的安全说明</h2>
 * Velocity 3.x 的 {@link PlayerChatEvent} <strong>没有可用的取消/改写 API</strong>
 * （{@code setResult(denied())} 在 1.19.1+ 会导致玩家被踢出，官方已在 Javadoc 标注弃用）。
 * 因此本插件<strong>只读取</strong>消息内容用于转发到 QQ，绝不修改结果，
 * 保证聊天能正常下发到子服，也不会因为签名校验失败踢人。
 */
public final class PlayerEventListener {

    private final Logger logger;
    private final ServerBridge servers;
    /**
     * 通过 {@link Holder} 间接持有路由器，而不是直接持有实例。
     * 这样 {@code /qqlink reload} 重建路由器后，本监听器无需重新注册即可用上新实例，
     * 避免"重载后消息发不出去"的隐蔽 Bug。
     */
    private final com.qqlink.velocity.util.Holder<ChatRouter> routerHolder;
    private final PlayerManager players;
    /** 玩家 UUID → 上一次所在子服（用于识别"切换"与提供退出时的服务器名） */
    private final ConcurrentHashMap<java.util.UUID, String> lastServer = new ConcurrentHashMap<>();

    public PlayerEventListener(ServerBridge servers, com.qqlink.velocity.util.Holder<ChatRouter> routerHolder,
                               PlayerManager players, Logger logger) {
        this.servers = servers;
        this.routerHolder = routerHolder;
        this.players = players;
        this.logger = logger;
    }

    /** 当前路由器实例（重载后自动指向新实例）。 */
    private ChatRouter router() {
        return routerHolder.get();
    }

    // ------------------------------------------------------------------
    // 玩家聊天：MC → QQ
    // ------------------------------------------------------------------

    @Subscribe
    public void onPlayerChat(PlayerChatEvent event) {
        Player player = event.getPlayer();
        String message = event.getMessage();
        if (Strings.isBlank(message)) {
            return;
        }
        String serverId = servers.serverOf(player);
        if (serverId == null) {
            // 玩家尚未进入任何子服（例如刚登录、正在传送），此时无处归属，直接忽略
            return;
        }

        // 管理员公告：/say 与 /broadcast 在子服执行，代理只能看到原始指令文本。
        // 这里做一次"本地识别"，让公告能立刻同步到 QQ，而不必等模组的 BROADCAST 上报。
        String trimmed = message.trim();
        if (trimmed.startsWith("/say ") || trimmed.startsWith("/broadcast ")
                || trimmed.startsWith("/me ") || trimmed.startsWith("/公告 ")) {
            int space = trimmed.indexOf(' ');
            String content = space > 0 ? trimmed.substring(space + 1) : "";
            if (Strings.isNotBlank(content) && router() != null) {
                router().onAdminBroadcast(serverId, content);
                return;
            }
        }

        if (router() == null) {
            return;
        }
        if (router().onPlayerChat(player, serverId, message)) {
            // 可选：把这条聊天同步到其他子服（跨服聊天）
            router().relayToOtherServers(player.getUsername(), serverId, message);
        }
    }

    // ------------------------------------------------------------------
    // 玩家进入子服 / 切换子服
    // ------------------------------------------------------------------

    @Subscribe
    public void onServerPostConnect(ServerPostConnectEvent event) {
        Player player = event.getPlayer();
        String current = servers.serverOf(player);
        if (current == null) {
            return;
        }
        java.util.UUID id = player.getUniqueId();
        String previous = lastServer.put(id, current);
        players.rememberServer(id, current);
        players.setLastKnownCount(current, servers.playerCount(current));

        // Velocity 的 getPreviousServer() 在"切换"时才有值（刚加入代理时为 null），用作交叉验证
        com.velocitypowered.api.proxy.server.RegisteredServer previousByEvent = event.getPreviousServer();
        boolean switched = previous != null && !previous.equals(current);
        if (!switched && previousByEvent != null) {
            String byEvent = previousByEvent.getServerInfo().getName();
            switched = !byEvent.equals(current);
            previous = byEvent;
        }

        if (switched) {
            if (router() != null && router().config().forwarding.suppressSwitchNoise) {
                router().onServerSwitch(player.getUsername(), previous, current);
            } else if (router() != null) {
                router().onPlayerQuit(player.getUsername(), previous);
                router().onPlayerJoin(player.getUsername(), current);
            }
        } else if (router() != null) {
            router().onPlayerJoin(player.getUsername(), current);
        }
    }

    // ------------------------------------------------------------------
    // 玩家退出代理
    // ------------------------------------------------------------------

    @Subscribe
    public void onDisconnect(DisconnectEvent event) {
        Player player = event.getPlayer();
        java.util.UUID id = player.getUniqueId();
        String serverId = lastServer.remove(id);
        if (serverId == null) {
            serverId = players.lastServer(id);
        }
        players.forget(id);
        if (serverId == null) {
            // 从未进入任何子服（例如登录失败），没有可通知的内容
            return;
        }
        players.setLastKnownCount(serverId, servers.playerCount(serverId));
        if (router() != null) {
            router().onPlayerQuit(player.getUsername(), serverId);
        }
    }

    // ------------------------------------------------------------------
    // 兜底：处理"没有模组时"的服务器状态变化
    // ------------------------------------------------------------------

    /**
     * 观察任意插件消息，用于在<strong>没有安装伴随模组</strong>的情况下
     * 粗粒度感知子服上下线。
     *
     * <p>原理：玩家能从 A 服切到 B 服，说明 B 服当时是可用的。
     * 这不能替代模组的 SERVER_START 上报，但能让 {@code #tps} 之外的状态
     * 至少有个"该子服最近有活动"的信号。
     */
    @Subscribe
    public void onAnyPluginMessage(PluginMessageEvent event) {
        if (!(event.getSource() instanceof ServerConnection connection)) {
            return;
        }
        String serverId = connection.getServerInfo().getName();
        players.setLastKnownCount(serverId, servers.playerCount(serverId));
    }

    // ------------------------------------------------------------------
    // 供主类在关闭时清理
    // ------------------------------------------------------------------

    public void clear() {
        lastServer.clear();
    }

    /** 调试用：当前记录的子服归属。 */
    public java.util.Map<java.util.UUID, String> snapshot() {
        return java.util.Map.copyOf(lastServer);
    }

    /** 记录一条日志（避免未使用字段告警，同时保留扩展点）。 */
    public void debug(String message) {
        logger.fine("[事件] " + message);
    }
}
