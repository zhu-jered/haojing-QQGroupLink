package com.qqlink.velocity.config.model;

import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import com.qqlink.velocity.config.Nodes;
import com.qqlink.velocity.config.Yaml;
import com.qqlink.velocity.util.Strings;

/**
 * 子服务器配置。
 *
 * <p>{@code id} 必须与 Velocity 的 {@code velocity.toml} 中
 * {@code [servers]} 段里的键名完全一致（大小写敏感），插件靠它把
 * "消息来自哪个子服" 映射成人类可读的 {@code display-name}。
 */
public final class ServerEntry {

    /** Velocity 中的服务器名（velocity.toml [servers] 的键） */
    public String id;
    /** QQ 消息里显示的服务器名，例如 "生存服" */
    public String displayName;
    /** 是否启用与 QQ 的互通（false 表示该子服完全隔离） */
    public boolean enabled = true;
    /** 是否为"主服"：QQ → MC 默认转发目标；多重启后玩家自动回到该服 */
    public boolean primary = false;
    /** 该子服里玩家聊天的转发策略，可覆盖全局 */
    public Boolean forwardChat = null;
    /** 该子服的显示排序（用于 #list 输出顺序），越小越靠前 */
    public int order = 100;

    public ServerEntry(String id, String displayName) {
        this.id = id;
        this.displayName = displayName;
    }

    public static ServerEntry from(String id, Map<String, Object> node) {
        ServerEntry entry = new ServerEntry(id, Nodes.str(node, id, "display-name"));
        entry.enabled = Nodes.bool(node, true, "enabled");
        entry.primary = Nodes.bool(node, false, "primary");
        entry.order = Nodes.integer(node, 100, "order");
        if (node.containsKey("forward-chat")) {
            entry.forwardChat = Nodes.bool(node, true, "forward-chat");
        }
        return entry;
    }

    public Map<String, Object> toMap() {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("display-name", displayName);
        map.put("enabled", enabled);
        map.put("primary", primary);
        map.put("order", order);
        if (forwardChat != null) {
            map.put("forward-chat", forwardChat);
        }
        return map;
    }

    /** 子服列表容器，提供 id → entry 的快速查询。 */
    public static final class Registry {

        private final Map<String, ServerEntry> byId = new LinkedHashMap<>();
        private final Set<String> disabled = new LinkedHashSet<>();

        public static Registry from(Map<String, Object> root) {
            Registry registry = new Registry();
            Map<String, Object> servers = Nodes.section(root, "servers");
            for (Map.Entry<String, Object> entry : servers.entrySet()) {
                String id = entry.getKey();
                Map<String, Object> node = entry.getValue() instanceof Map<?, ?>
                        ? Nodes.section(servers, id) : Map.of();
                registry.register(ServerEntry.from(id, node));
            }
            return registry;
        }

        public void register(ServerEntry entry) {
            byId.put(entry.id, entry);
            if (!entry.enabled) {
                disabled.add(entry.id);
            }
        }

        public ServerEntry get(String id) {
            return byId.get(id);
        }

        /** 取显示名；未配置的子服直接回退为服务器 id，保证永远有可读输出。 */
        public String displayName(String id) {
            ServerEntry entry = byId.get(id);
            return entry == null ? Strings.safe(id) : entry.displayName;
        }

        public boolean isEnabled(String id) {
            ServerEntry entry = byId.get(id);
            if (entry == null) {
                // 未在配置里出现的子服默认按"启用 + 使用 id 作为显示名"处理，
                // 这样新加子服时即使忘记改配置也能正常工作，只是提示用户补配置。
                return true;
            }
            return entry.enabled;
        }

        public boolean isEmpty() {
            return byId.isEmpty();
        }

        /** 按 order 排序后的所有子服。 */
        public List<ServerEntry> sorted() {
            return byId.values().stream()
                    .sorted((a, b) -> {
                        int cmp = Integer.compare(a.order, b.order);
                        return cmp != 0 ? cmp : a.id.compareTo(b.id);
                    })
                    .toList();
        }

        public List<ServerEntry> enabledServers() {
            return sorted().stream().filter(entry -> entry.enabled).toList();
        }

        /** 主服 id；未显式配置时取第一个启用的子服。 */
        public String primaryId() {
            for (ServerEntry entry : sorted()) {
                if (entry.primary && entry.enabled) {
                    return entry.id;
                }
            }
            List<ServerEntry> enabled = enabledServers();
            return enabled.isEmpty() ? null : enabled.get(0).id;
        }

        public Map<String, ServerEntry> all() {
            return byId;
        }

        /** 写回 YAML 用的结构。 */
        public Map<String, Object> toMap() {
            Map<String, Object> map = new LinkedHashMap<>();
            for (ServerEntry entry : sorted()) {
                map.put(entry.id, entry.toMap());
            }
            return map;
        }

        /** 把某个子服标记为禁用（用于自动发现后写回配置）。 */
        public void addDiscovered(String id) {
            if (!byId.containsKey(id)) {
                ServerEntry entry = new ServerEntry(id, id);
                entry.order = 999;
                register(entry);
            }
        }

        /** 便于外部构建：从 Map 快速生成（用于写回）。 */
        public static Map<String, Object> newMap() {
            return Yaml.orderedMap();
        }
    }
}
