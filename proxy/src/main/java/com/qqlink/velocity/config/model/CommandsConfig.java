package com.qqlink.velocity.config.model;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import com.qqlink.velocity.config.Nodes;
import com.qqlink.velocity.util.Strings;

/**
 * QQ 群指令系统配置。
 *
 * <p>内置指令全部可通过 {@code commands.builtin.<指令名>.enabled} 独立开关，
 * 同时支持自定义别名、自定义用法提示。
 */
public final class CommandsConfig {

    /** 内置指令元信息（名称 / 别名 / 用法 / 说明）。 */
    public enum Builtin {
        LIST("list", List.of("在线", "玩家列表", "online"), "#list", "查询各子服在线玩家列表"),
        TPS("tps", List.of("性能", "mspt", "lag"), "#tps", "查询各子服 TPS / MSPT 性能数据"),
        BROADCAST("broadcast", List.of("bc", "公告", "announce"), "#broadcast <内容>", "向所有子服发送全服公告"),
        SEND("send", List.of("发送", "msg"), "#send <服务器名> <内容>", "向指定子服发送消息"),
        HELP("help", List.of("帮助", "?", "h"), "#help", "查看可用指令列表"),
        STATUS("status", List.of("状态", "st"), "#status", "查看机器人与代理运行状态");

        private final String id;
        private final List<String> defaultAliases;
        private final String usage;
        private final String description;

        Builtin(String id, List<String> defaultAliases, String usage, String description) {
            this.id = id;
            this.defaultAliases = defaultAliases;
            this.usage = usage;
            this.description = description;
        }

        public String id() {
            return id;
        }

        public String usage() {
            return usage;
        }

        public String description() {
            return description;
        }

        public List<String> defaultAliases() {
            return defaultAliases;
        }

        public static Builtin byId(String id) {
            for (Builtin builtin : values()) {
                if (builtin.id.equalsIgnoreCase(id)) {
                    return builtin;
                }
            }
            return null;
        }
    }

    /** 单个指令的配置。 */
    public static final class CommandSetting {
        public final Builtin builtin;
        public boolean enabled = true;
        public List<String> aliases = new ArrayList<>();

        CommandSetting(Builtin builtin) {
            this.builtin = builtin;
            this.aliases.addAll(builtin.defaultAliases());
            this.aliases.add(builtin.id());
        }

        /** 去掉非法字符后的别名集合（小写）。 */
        public List<String> normalizedAliases() {
            List<String> result = new ArrayList<>();
            for (String alias : aliases) {
                String normalized = Strings.safe(alias).trim().toLowerCase(java.util.Locale.ROOT);
                if (!normalized.isEmpty() && !result.contains(normalized)) {
                    result.add(normalized);
                }
            }
            return result;
        }
    }

    /** 是否响应 QQ 群指令（总开关） */
    public boolean enabled = true;
    /** 指令前缀列表，如 ["#", "/", "."] */
    public List<String> prefixes = new ArrayList<>(List.of("#", "/", "."));
    /** 执行指令后是否需要回执（关闭则静默执行） */
    public boolean feedback = true;

    /**
     * 非管理员发送管理指令时的行为：
     * <ul>
     *   <li>{@code silent}   —— 完全无响应（默认，防探测）</li>
     *   <li>{@code reply}    —— 回复"权限不足"</li>
     * </ul>
     */
    public String unauthorized = "silent";

    /** 非管理员 @ 机器人询问功能是否可用的场景下返回的提示 */
    public boolean replyOnMention = true;

    /** {@code #list} 输出样式：compact（一行一服）/ detail（含玩家列表换行） */
    public String listStyle = "compact";
    /** {@code #list} 是否包含空服务器 */
    public boolean listIncludeEmpty = true;

    /** {@code #broadcast} / {@code #send} 是否需要二次确认（防误操作，默认关闭） */
    public boolean confirmHeavy = false;

    /** 指令结果是否也同步回游戏内（让服内玩家知道是谁在 QQ 里发的） */
    public boolean echoToMinecraft = true;

    private final Map<Builtin, CommandSetting> builtinSettings = new EnumMap<>(Builtin.class);

    public static CommandsConfig from(Map<String, Object> root) {
        CommandsConfig config = new CommandsConfig();
        Map<String, Object> section = Nodes.section(root, "commands");
        config.enabled = Nodes.bool(section, true, "enabled");
        List<String> prefixes = Nodes.strList(section, "prefixes");
        if (!prefixes.isEmpty()) {
            config.prefixes = new ArrayList<>(prefixes);
        }
        config.feedback = Nodes.bool(section, true, "feedback");
        config.unauthorized = Nodes.str(section, "silent", "unauthorized").toLowerCase(java.util.Locale.ROOT);
        if (!config.unauthorized.equals("reply")) {
            config.unauthorized = "silent";
        }
        config.replyOnMention = Nodes.bool(section, true, "reply-on-mention");
        config.listStyle = Nodes.str(section, "compact", "list-style").toLowerCase(java.util.Locale.ROOT);
        config.listIncludeEmpty = Nodes.bool(section, true, "list-include-empty");
        config.confirmHeavy = Nodes.bool(section, false, "confirm-heavy");
        config.echoToMinecraft = Nodes.bool(section, true, "echo-to-minecraft");

        Map<String, Object> builtin = Nodes.section(section, "builtin");
        for (Builtin value : Builtin.values()) {
            CommandSetting setting = new CommandSetting(value);
            Map<String, Object> node = Nodes.section(builtin, value.id());
            if (!node.isEmpty()) {
                // 单指令总开关：兼容 enabled: true / #list: true 两种写法
                Object raw = node.get("enabled");
                if (raw != null) {
                    setting.enabled = Nodes.bool(node, true, "enabled");
                }
                List<String> aliases = Nodes.strList(node, "aliases");
                if (!aliases.isEmpty()) {
                    setting.aliases = new ArrayList<>(aliases);
                    if (!setting.aliases.contains(value.id())) {
                        setting.aliases.add(value.id());
                    }
                }
            }
            config.builtinSettings.put(value, setting);
        }
        return config;
    }

    public CommandSetting setting(Builtin builtin) {
        return builtinSettings.computeIfAbsent(builtin, CommandSetting::new);
    }

    public boolean isEnabled(Builtin builtin) {
        return enabled && setting(builtin).enabled;
    }

    /**
     * 解析群消息里携带的指令前缀。
     *
     * @param raw 已去掉 @ 与首尾空白的消息
     * @return 命中的前缀，未命中返回 null
     */
    public String matchPrefix(String raw) {
        if (Strings.isBlank(raw)) {
            return null;
        }
        for (String prefix : prefixes) {
            if (prefix != null && !prefix.isEmpty() && raw.startsWith(prefix)) {
                return prefix;
            }
        }
        return null;
    }

    /** 去掉指令前缀与空白后的剩余内容。 */
    public String strip(String raw) {
        String prefix = matchPrefix(raw);
        if (prefix == null) {
            return raw;
        }
        return raw.substring(prefix.length()).trim();
    }

    /**
     * 根据指令名 / 别名解析出内置指令。
     *
     * @param name 已小写的指令名
     */
    public Builtin resolve(String name) {
        if (Strings.isBlank(name)) {
            return null;
        }
        String lower = name.trim().toLowerCase(java.util.Locale.ROOT);
        for (Map.Entry<Builtin, CommandSetting> entry : builtinSettings.entrySet()) {
            if (entry.getKey().id().equals(lower)) {
                return entry.getKey();
            }
        }
        for (Map.Entry<Builtin, CommandSetting> entry : builtinSettings.entrySet()) {
            if (entry.getValue().normalizedAliases().contains(lower)) {
                return entry.getKey();
            }
        }
        return null;
    }

    public Map<String, Object> toMap() {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("enabled", enabled);
        map.put("prefixes", new ArrayList<>(prefixes));
        map.put("feedback", feedback);
        map.put("unauthorized", unauthorized);
        map.put("reply-on-mention", replyOnMention);
        map.put("list-style", listStyle);
        map.put("list-include-empty", listIncludeEmpty);
        map.put("confirm-heavy", confirmHeavy);
        map.put("echo-to-minecraft", echoToMinecraft);

        Map<String, Object> builtin = new LinkedHashMap<>();
        for (Builtin value : Builtin.values()) {
            CommandSetting setting = setting(value);
            Map<String, Object> node = new LinkedHashMap<>();
            node.put("enabled", setting.enabled);
            node.put("aliases", new ArrayList<>(setting.aliases));
            node.put("usage", value.usage());
            node.put("description", value.description());
            builtin.put(value.id(), node);
        }
        map.put("builtin", builtin);
        return map;
    }
}
