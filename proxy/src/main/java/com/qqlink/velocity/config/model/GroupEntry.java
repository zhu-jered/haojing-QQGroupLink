package com.qqlink.velocity.config.model;

import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;

import com.qqlink.velocity.config.Nodes;
import com.qqlink.velocity.util.Strings;

/**
 * QQ 群配置。
 *
 * <p>支持"一群一服"（每个 QQ 群只跟一个子服互通）与"一群多服"
 * （一个群同时连接多个子服）两种拓扑，通过 {@code mc-servers} 控制。
 */
public final class GroupEntry {

    /** QQ 群号 */
    public long groupId;
    /** 群备注名，仅用于日志与 {@code %group%} 占位符（留空则显示群号） */
    public String name = "";
    /** 该群绑定哪些子服；为空表示"全部启用的子服" */
    public Set<String> servers = new LinkedHashSet<>();
    /** 是否转发该群的聊天到 MC */
    public boolean chat = true;
    /** 是否允许该群使用管理指令 */
    public boolean commands = true;

    public GroupEntry(long groupId) {
        this.groupId = groupId;
    }

    public static GroupEntry from(long groupId, Map<String, Object> node) {
        GroupEntry entry = new GroupEntry(groupId);
        entry.name = Nodes.str(node, "", "name");
        entry.servers.addAll(Nodes.strList(node, "mc-servers"));
        entry.chat = Nodes.bool(node, true, "chat");
        entry.commands = Nodes.bool(node, true, "commands");
        return entry;
    }

    public Map<String, Object> toMap() {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("name", name);
        map.put("mc-servers", new java.util.ArrayList<>(servers));
        map.put("chat", chat);
        map.put("commands", commands);
        return map;
    }

    public Set<String> servers() {
        return servers;
    }

    public String display() {
        return Strings.isBlank(name) ? String.valueOf(groupId) : name + "(" + groupId + ")";
    }

    /** 群注册表。 */
    public static final class Registry {

        private final Map<Long, GroupEntry> byId = new LinkedHashMap<>();
        /** 是否启用"未配置的群一律不放行"的严格模式 */
        public boolean strict = false;

        public static Registry from(Map<String, Object> root) {
            Registry registry = new Registry();
            Map<String, Object> groups = Nodes.section(root, "groups");
            registry.strict = Nodes.bool(groups, false, "strict");
            for (Map.Entry<String, Object> entry : groups.entrySet()) {
                String key = entry.getKey();
                if ("strict".equals(key) || !Strings.isLong(key)) {
                    continue;
                }
                long groupId = Strings.parseLong(key, 0L);
                if (groupId == 0L) {
                    continue;
                }
                Map<String, Object> node = entry.getValue() instanceof Map<?, ?>
                        ? Nodes.section(groups, key) : Map.of();
                registry.byId.put(groupId, GroupEntry.from(groupId, node));
            }
            return registry;
        }

        public GroupEntry get(long groupId) {
            return byId.get(groupId);
        }

        /** 该群是否被允许使用（严格模式下未配置的群直接忽略）。 */
        public boolean allowed(long groupId) {
            if (byId.containsKey(groupId)) {
                return true;
            }
            return !strict;
        }

        public boolean hasAny() {
            return !byId.isEmpty();
        }

        public Set<Long> ids() {
            return byId.keySet();
        }

        public Map<Long, GroupEntry> all() {
            return byId;
        }

        public Map<String, Object> toMap() {
            Map<String, Object> map = new LinkedHashMap<>();
            map.put("strict", strict);
            for (GroupEntry entry : byId.values()) {
                map.put(String.valueOf(entry.groupId), entry.toMap());
            }
            return map;
        }
    }
}
