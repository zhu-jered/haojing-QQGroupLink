package com.qqlink.velocity.mc;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.logging.Logger;

import com.velocitypowered.api.proxy.Player;
import com.velocitypowered.api.proxy.ProxyServer;
import com.velocitypowered.api.proxy.server.RegisteredServer;

/**
 * 子服查询 / 插件消息发送的薄封装。
 *
 * <p>把 "Velocity 的 RegisteredServer 可能不存在"、"子服可能离线" 这些边界情况
 * 集中在这里处理，业务层只需要调用 {@code sendToServer(id, payload)}。
 *
 * <h2>插件消息长度限制</h2>
 * 单条自定义载荷上限 32767 字节（Minecraft 协议限制）。广播公告和聊天都远小于该值，
 * 但这里仍然做了保护性截断，避免玩家发送超长消息导致后端抛异常。
 */
public final class ServerBridge {

    /** 单条插件消息的安全上限（字节）。 */
    public static final int MAX_PAYLOAD_BYTES = 30_000;

    private final ProxyServer proxy;
    private final Logger logger;
    /** 每个子服的最近一次插件消息发送结果，用于状态展示。 */
    private final Map<String, Long> lastSuccess = new ConcurrentHashMap<>();
    private final Map<String, String> lastError = new ConcurrentHashMap<>();

    public ServerBridge(ProxyServer proxy, Logger logger) {
        this.proxy = proxy;
        this.logger = logger;
    }

    /** 是否存在该子服（velocity.toml 中已注册）。 */
    public boolean exists(String serverId) {
        return proxy.getServer(serverId).isPresent();
    }

    public Optional<RegisteredServer> server(String serverId) {
        return proxy.getServer(serverId);
    }

    /** 面向玩家的显示名。这里不依赖配置，配置层的显示名由 ServerEntry.Registry 提供。 */
    public String rawId(RegisteredServer server) {
        return server.getServerInfo().getName();
    }

    /**
     * 向指定子服发送一条插件消息。
     *
     * @param serverId    目标子服
     * @param payload     文本载荷（UTF-8 编码）
     * @param channelName 频道名
     * @return 是否成功发出
     */
    public boolean sendToServer(String serverId, String payload, String channelName) {
        Optional<RegisteredServer> optional = proxy.getServer(serverId);
        if (optional.isEmpty()) {
            lastError.put(serverId, "子服未在 velocity.toml 中注册");
            return false;
        }
        byte[] data = payload.getBytes(StandardCharsets.UTF_8);
        if (data.length > MAX_PAYLOAD_BYTES) {
            data = new byte[MAX_PAYLOAD_BYTES];
            byte[] truncated = payload.getBytes(StandardCharsets.UTF_8);
            System.arraycopy(truncated, 0, data, 0, MAX_PAYLOAD_BYTES);
            logger.warning("[MC] 发往 " + serverId + " 的插件消息过长，已截断为 " + MAX_PAYLOAD_BYTES + " 字节");
        }
        try {
            boolean ok = optional.get().sendPluginMessage(
                    com.velocitypowered.api.proxy.messages.MinecraftChannelIdentifier.from(channelName), data);
            if (ok) {
                lastSuccess.put(serverId, System.currentTimeMillis());
                lastError.remove(serverId);
            } else {
                lastError.put(serverId, "子服无可用连接（可能没有玩家在线）");
            }
            return ok;
        } catch (Throwable throwable) {
            lastError.put(serverId, throwable.getMessage());
            logger.fine("[MC] 向 " + serverId + " 发送插件消息失败：" + throwable.getMessage());
            return false;
        }
    }

    /**
     * 在某个子服内广播一条消息。
     *
     * <p>Velocity 没有"向子服发系统消息"的 API，因此实现方式是：
     * 找出所有当前在该子服的玩家，逐个 {@code sendMessage}。
     * 这也是 Velocity 生态插件的通行做法。
     */
    public int broadcastToServer(String serverId, net.kyori.adventure.text.Component message) {
        int count = 0;
        for (Player player : playersOn(serverId)) {
            player.sendMessage(message);
            count++;
        }
        return count;
    }

    /** 该子服当前的在线玩家。 */
    public java.util.List<Player> playersOn(String serverId) {
        java.util.List<Player> result = new ArrayList<>();
        for (Player player : proxy.getAllPlayers()) {
            Optional<com.velocitypowered.api.proxy.ServerConnection> connection = player.getCurrentServer();
            if (connection.isPresent() && connection.get().getServerInfo().getName().equals(serverId)) {
                result.add(player);
            }
        }
        return result;
    }

    /** 该子服当前在线人数。 */
    public int playerCount(String serverId) {
        int count = 0;
        for (Player player : proxy.getAllPlayers()) {
            Optional<com.velocitypowered.api.proxy.ServerConnection> connection = player.getCurrentServer();
            if (connection.isPresent() && connection.get().getServerInfo().getName().equals(serverId)) {
                count++;
            }
        }
        return count;
    }

    /** 某玩家所在子服 id（不在任何子服时返回 null）。 */
    public String serverOf(Player player) {
        return player.getCurrentServer()
                .map(connection -> connection.getServerInfo().getName())
                .orElse(null);
    }

    /** 某玩家所在子服 id（按 UUID 查，玩家已离线返回 null）。 */
    public String serverOf(UUID uuid) {
        return proxy.getPlayer(uuid).flatMap(Player::getCurrentServer)
                .map(connection -> connection.getServerInfo().getName())
                .orElse(null);
    }

    public ProxyServer proxy() {
        return proxy;
    }

    /** 所有已注册子服 id（来自 velocity.toml）。 */
    public java.util.List<String> registeredIds() {
        java.util.List<String> ids = new ArrayList<>();
        for (RegisteredServer server : proxy.getAllServers()) {
            ids.add(server.getServerInfo().getName());
        }
        return ids;
    }

    /** 所有已注册子服的 id + 地址，用于 /qqlink status。 */
    public Map<String, String> registeredWithAddress() {
        Map<String, String> map = new LinkedHashMap<>();
        for (RegisteredServer server : proxy.getAllServers()) {
            map.put(server.getServerInfo().getName(), String.valueOf(server.getServerInfo().getAddress()));
        }
        return map;
    }

    /** 最近一次向该子服发送是否成功。 */
    public boolean lastSendOk(String serverId) {
        Long time = lastSuccess.get(serverId);
        if (time == null) {
            return false;
        }
        return System.currentTimeMillis() - time < 60_000L;
    }

    public String lastError(String serverId) {
        return lastError.get(serverId);
    }
}
