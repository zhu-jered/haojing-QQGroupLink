package com.qqlink.velocity.config.model;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import com.qqlink.velocity.config.Nodes;

/**
 * 游戏事件推送配置。
 *
 * <p>需求要求"每个功能可单独启用/禁用"，因此这里每一项都是一个独立布尔开关，
 * 由 {@link #enabled} 作为总闸：总闸关闭时所有事件都不推送（但聊天转发不受影响）。
 */
public final class EventsConfig {

    /** 事件推送总开关 */
    public boolean enabled = true;

    /** 玩家加入服务器通知 */
    public boolean join = true;
    /** 玩家退出服务器通知 */
    public boolean quit = true;
    /** 玩家死亡通知（携带死亡原因） */
    public boolean death = true;
    /** 玩家解锁成就 / 进度通知 */
    public boolean advancement = true;
    /** 服务器启动 / 关闭通知 */
    public boolean serverStatus = true;
    /** 游戏内管理员公告（/say、/broadcast、控制台公告）同步到 QQ */
    public boolean adminBroadcast = true;
    /** 玩家切换子服通知（跨服跳转） */
    public boolean serverSwitch = true;

    /** 服务器上线 / 下线通知是否 @全体成员（慎用） */
    public boolean serverStatusAtAll = false;

    /** 是否推送被静音的进度类型 */
    public Advancement advancementFilter = new Advancement();

    /** 死亡原因解析方式：auto（优先用模组上报，其次正则解析）/ regex / none */
    public String deathCauseMode = "auto";
    /** 死亡原因最大长度 */
    public int deathCauseMaxLength = 60;

    public static EventsConfig from(Map<String, Object> root) {
        EventsConfig config = new EventsConfig();
        Map<String, Object> section = Nodes.section(root, "events");
        config.enabled = Nodes.bool(section, true, "enabled");
        config.join = Nodes.bool(section, true, "join");
        config.quit = Nodes.bool(section, true, "quit");
        config.death = Nodes.bool(section, true, "death");
        config.advancement = Nodes.bool(section, true, "advancement");
        config.serverStatus = Nodes.bool(section, true, "server-status");
        config.adminBroadcast = Nodes.bool(section, true, "admin-broadcast");
        config.serverSwitch = Nodes.bool(section, true, "server-switch");
        config.serverStatusAtAll = Nodes.bool(section, false, "server-status-at-all");
        config.deathCauseMode = Nodes.str(section, "auto", "death", "cause-mode").toLowerCase(java.util.Locale.ROOT);
        config.deathCauseMaxLength = Math.max(8, Nodes.integer(section, 60, "death", "cause-max-length"));
        config.advancementFilter = Advancement.from(Nodes.section(section, "advancement"));
        return config;
    }

    /** 判断某一项事件是否真正启用（受总闸约束）。 */
    public boolean isActive(boolean specific) {
        return enabled && specific;
    }

    public Map<String, Object> toMap() {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("enabled", enabled);
        map.put("join", join);
        map.put("quit", quit);
        map.put("server-status", serverStatus);
        map.put("admin-broadcast", adminBroadcast);
        map.put("server-switch", serverSwitch);
        map.put("server-status-at-all", serverStatusAtAll);

        Map<String, Object> deathMap = new LinkedHashMap<>();
        deathMap.put("enabled", death);
        deathMap.put("cause-mode", deathCauseMode);
        deathMap.put("cause-max-length", deathCauseMaxLength);
        map.put("death", deathMap);

        Map<String, Object> advancementMap = new LinkedHashMap<>();
        advancementMap.put("enabled", advancement);
        advancementMap.putAll(advancementFilter.toMap());
        map.put("advancement", advancementMap);
        return map;
    }

    /** 成就 / 进度推送的细粒度过滤。 */
    public static final class Advancement {

        /** 是否过滤掉配方解锁（"获得新配方" 会刷屏，默认过滤） */
        public boolean ignoreRecipes = true;
        /** 是否过滤掉原版"进度已达成"以外的普通挑战（默认全推） */
        public boolean ignoreChallenges = false;
        /** 是否过滤掉非首个进度（fabric 的 grant 事件在重载时会重放） */
        public boolean announceToAll = true;
        /** 关键词黑名单：进度标题包含其中任意一个就跳过 */
        public List<String> blacklist = new ArrayList<>(List.of());

        public static Advancement from(Map<String, Object> section) {
            Advancement advancement = new Advancement();
            advancement.ignoreRecipes = Nodes.bool(section, true, "ignore-recipes");
            advancement.ignoreChallenges = Nodes.bool(section, false, "ignore-challenges");
            advancement.announceToAll = Nodes.bool(section, true, "announce-to-all");
            advancement.blacklist = Nodes.strList(section, "blacklist");
            return advancement;
        }

        public Map<String, Object> toMap() {
            Map<String, Object> map = new LinkedHashMap<>();
            map.put("ignore-recipes", ignoreRecipes);
            map.put("ignore-challenges", ignoreChallenges);
            map.put("announce-to-all", announceToAll);
            map.put("blacklist", blacklist);
            return map;
        }
    }
}
