package com.qqlink.velocity.mc;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.LongSupplier;

import com.qqlink.velocity.config.model.EventsConfig;
import com.qqlink.velocity.util.Strings;

/**
 * 子服运行时状态仓库：在线玩家分布、TPS / MSPT、上下线时间。
 *
 * <h2>数据来源</h2>
 * <ul>
 *   <li><strong>在线玩家</strong>：完整来自代理本身
 *       （{@code ProxyServer.getAllPlayers()} + {@code player.getCurrentServer()}），
 *       因此 <em>不装模组也能用 #list</em>；</li>
 *   <li><strong>TPS / MSPT</strong>：只能由后端上报。Velocity 运行在代理层，
 *       本身不跑游戏主循环，<strong>没有任何 API 能拿到子服 TPS</strong>。
 *       因此本插件提供可选伴随模组 {@code qqlink-fabric}，
 *       通过自定义插件消息通道 {@code qqlink:metrics} 每 5 秒上报一次；</li>
 *   <li><strong>上线时间</strong>：代理第一次收到该子服的指标上报时记录，
 *       用于估算"子服已运行多久"。</li>
 * </ul>
 *
 * <h2>线程安全</h2>
 * 数据既会被事件线程写、也会被指令线程（异步）读，因此全部使用
 * {@link ConcurrentHashMap}；对同一子服的多字段读取做"近似一致"处理
 * （TPS 与 MSPT 独立存储，不追求严格原子），因为展示场景对此不敏感。
 */
public final class PlayerManager {

    /** 指标有效期：超过该时长视为过期（子服可能已关闭）。 */
    public static final long METRIC_TTL_MILLIS = 30_000L;

    private final LongSupplier clock;

    /** 玩家 UUID → 所在子服 id（用于退出/切换时定位服务器） */
    private final Map<UUID, String> lastServer = new ConcurrentHashMap<>();
    /** 玩家 UUID → 上次加入该子服的时间 */
    private final Map<UUID, Long> joinTime = new ConcurrentHashMap<>();
    /** 子服 id → TPS */
    private final Map<String, Double> tps = new ConcurrentHashMap<>();
    /** 子服 id → MSPT */
    private final Map<String, Double> mspt = new ConcurrentHashMap<>();
    /** 子服 id → 上次收到指标的时间 */
    private final Map<String, Long> metricTime = new ConcurrentHashMap<>();
    /** 子服 id → 首次收到指标的时间（≈ 子服启动时间） */
    private final Map<String, Long> firstSeen = new ConcurrentHashMap<>();
    /** 子服 id → 是否已被代理确认 "启动完成" */
    private final Map<String, Boolean> online = new ConcurrentHashMap<>();
    /** 子服 id → 最近一次玩家数（由代理侧统计，作为模组数据的兜底） */
    private final Map<String, Integer> lastKnownCount = new ConcurrentHashMap<>();
    /** 记录已经被通知过"启动"的子服，避免重复推送 */
    private final Map<String, Long> startNotified = new ConcurrentHashMap<>();

    public PlayerManager() {
        this(System::currentTimeMillis);
    }

    /** 允许注入时钟，便于单元测试。 */
    public PlayerManager(LongSupplier clock) {
        this.clock = clock;
    }

    // ------------------------------------------------------------------
    // 玩家
    // ------------------------------------------------------------------

    /** 记录玩家当前所在子服（加入 / 切换时调用）。 */
    public void rememberServer(UUID playerId, String serverId) {
        if (playerId == null || serverId == null) {
            return;
        }
        lastServer.put(playerId, serverId);
        joinTime.put(playerId, clock.getAsLong());
    }

    /** 取玩家上次所在子服（用于退出通知；玩家已离线也能取到）。 */
    public String lastServer(UUID playerId) {
        return playerId == null ? null : lastServer.get(playerId);
    }

    /** 玩家是否刚刚加入（用于抑制"切换服务器"被误报为"加入"）。 */
    public long joinTime(UUID playerId) {
        Long time = playerId == null ? null : joinTime.get(playerId);
        return time == null ? 0L : time;
    }

    /** 玩家离开，清理状态。 */
    public void forget(UUID playerId) {
        if (playerId == null) {
            return;
        }
        lastServer.remove(playerId);
        joinTime.remove(playerId);
    }

    // ------------------------------------------------------------------
    // 指标
    // ------------------------------------------------------------------

    /** 记录一次指标上报。 */
    public void updateMetrics(String serverId, double tps, double mspt) {
        if (Strings.isBlank(serverId)) {
            return;
        }
        long now = clock.getAsLong();
        this.tps.put(serverId, tps);
        this.mspt.put(serverId, mspt);
        this.metricTime.put(serverId, now);
        this.firstSeen.putIfAbsent(serverId, now);
        this.online.put(serverId, true);
    }

    /** 标记子服离线（收到下线通知或指标过期）。 */
    public void markOffline(String serverId) {
        online.put(serverId, false);
        metricTime.remove(serverId);
        firstSeen.remove(serverId);
        startNotified.remove(serverId);
    }

    /** 是否有新鲜的性能数据。 */
    public boolean hasMetrics(String serverId) {
        Long time = metricTime.get(serverId);
        return time != null && clock.getAsLong() - time <= METRIC_TTL_MILLIS;
    }

    public double tps(String serverId) {
        Double value = tps.get(serverId);
        return value == null ? -1D : value;
    }

    public double mspt(String serverId) {
        Double value = mspt.get(serverId);
        return value == null ? -1D : value;
    }

    /** 指标时间戳，0 表示从未上报。 */
    public long metricTime(String serverId) {
        Long time = metricTime.get(serverId);
        return time == null ? 0L : time;
    }

    /** 子服（近似）启动时间。 */
    public long startTime(String serverId) {
        Long time = firstSeen.get(serverId);
        return time == null ? 0L : time;
    }

    /** 子服是否在线（有新鲜指标或代理侧确认过）。 */
    public boolean isOnline(String serverId) {
        if (hasMetrics(serverId)) {
            return true;
        }
        Boolean value = online.get(serverId);
        return value != null && value;
    }

    /**
     * 判断是否需要推送"服务器启动"通知。
     *
     * <p>使用"首次见到该子服"作为启动信号，并保证在一次运行周期内只推送一次。
     *
     * @return true 表示这次应该推送
     */
    public boolean shouldNotifyStart(String serverId) {
        if (Strings.isBlank(serverId)) {
            return false;
        }
        Long previous = startNotified.get(serverId);
        if (previous != null) {
            return false;
        }
        startNotified.put(serverId, clock.getAsLong());
        return true;
    }

    /** 强制允许下次重新推送启动通知（子服重启后使用）。 */
    public void resetStartNotification(String serverId) {
        startNotified.remove(serverId);
    }

    public void setLastKnownCount(String serverId, int count) {
        lastKnownCount.put(serverId, count);
    }

    public int lastKnownCount(String serverId) {
        Integer value = lastKnownCount.get(serverId);
        return value == null ? 0 : value;
    }

    /** 清理过期指标（由定时任务调用），并返回被清理的子服列表。 */
    public List<String> evictStale() {
        List<String> evicted = new ArrayList<>();
        long now = clock.getAsLong();
        for (Map.Entry<String, Long> entry : metricTime.entrySet()) {
            if (now - entry.getValue() > METRIC_TTL_MILLIS * 3) {
                evicted.add(entry.getKey());
            }
        }
        for (String serverId : evicted) {
            // 注意：只清理"指标新鲜度"，不删除 firstSeen —— 子服重启后启动时间需要重置，
            // 但这里保守处理，交由 markOffline 显式清理，避免误判。
            metricTime.remove(serverId);
            tps.remove(serverId);
            mspt.remove(serverId);
            online.put(serverId, false);
        }
        return evicted;
    }

    /** 供状态输出使用的快照。 */
    public Map<String, Object> snapshot(String serverId) {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("server", serverId);
        map.put("online", isOnline(serverId));
        map.put("tps", hasMetrics(serverId) ? tps(serverId) : null);
        map.put("mspt", hasMetrics(serverId) ? mspt(serverId) : null);
        map.put("metric_time", metricTime(serverId));
        map.put("start_time", startTime(serverId));
        map.put("players", lastKnownCount(serverId));
        return map;
    }

    /** 全部已知子服的指标快照。 */
    public Map<String, Map<String, Object>> snapshotAll() {
        Map<String, Map<String, Object>> map = new LinkedHashMap<>();
        for (String serverId : tps.keySet()) {
            map.put(serverId, snapshot(serverId));
        }
        return map;
    }

    /**
     * 根据 TPS 生成一个直观的状态描述，用于 #tps 输出。
     * 不引入额外配置项，阈值是业界通行经验值。
     */
    public static String tpsQuality(double tps) {
        if (tps < 0) {
            return "未知";
        }
        if (tps >= 19.5D) {
            return "流畅";
        }
        if (tps >= 18.0D) {
            return "良好";
        }
        if (tps >= 15.0D) {
            return "卡顿";
        }
        return "严重卡顿";
    }

    /** 是否开启了死亡事件（供事件处理器快速判断）。 */
    public static boolean deathEnabled(EventsConfig events) {
        return events.isActive(events.death);
    }
}
