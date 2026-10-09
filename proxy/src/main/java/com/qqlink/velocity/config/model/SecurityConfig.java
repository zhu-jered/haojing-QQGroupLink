package com.qqlink.velocity.config.model;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

import com.qqlink.velocity.config.Nodes;
import com.qqlink.velocity.util.Strings;

/**
 * 安全 / 白名单 / 限流配置。
 */
public final class SecurityConfig {

    /** 可执行管理指令的 QQ 白名单（超级管理员，对所有群生效） */
    public Set<Long> adminWhitelist = Set.of();
    /** 是否允许群主/管理员执行指令（依赖机器人上报的 role 字段） */
    public boolean allowGroupAdmin = false;
    /** 是否启用白名单机制：false 表示任何群成员都能用指令（极不推荐） */
    public boolean whitelistEnabled = true;

    /** 管理员在游戏内的权限节点（用于 {@code /qqlink} 指令） */
    public String inGamePermission = "qqlink.admin";

    /** 消息长度上限（字符）：超出部分截断，防止刷屏 */
    public int maxMessageLength = 200;
    /** 单条消息最小长度：低于该长度的消息不转发 */
    public int minMessageLength = 1;

    /** 过滤连续重复字符刷屏（如 aaaaaaaaaaaa） */
    public boolean filterRepeatedChars = true;
    /** 过滤纯符号 / 表情消息 */
    public boolean filterSymbolsOnly = true;
    /** 是否剔除 CQ 码（图片/语音/@ 等），只保留纯文本 */
    public boolean stripCqCodes = true;

    /** 每个 QQ 用户每分钟最多发送多少条会被转发到 MC 的消息（0 = 不限） */
    public int userRateLimit = 20;
    /** 每个 QQ 群每分钟最多接受多少条来自 MC 的消息（0 = 不限） */
    public int groupRateLimit = 60;

    /** 是否把 MC → QQ 的消息中的颜色代码去掉 */
    public boolean stripColorsToQq = true;

    /** 忽略的消息前缀（MC → QQ 与 QQ → MC 双向生效），例如 ["#", "/"] 可避免指令被转发 */
    public java.util.List<String> ignoredPrefixes = new java.util.ArrayList<>(java.util.List.of("/"));

    public static SecurityConfig from(Map<String, Object> root) {
        SecurityConfig config = new SecurityConfig();
        Map<String, Object> section = Nodes.section(root, "security");
        config.adminWhitelist = Nodes.longSet(section, "admin-whitelist");
        config.allowGroupAdmin = Nodes.bool(section, false, "allow-group-admin");
        config.whitelistEnabled = Nodes.bool(section, true, "whitelist-enabled");
        config.inGamePermission = Nodes.str(section, "qqlink.admin", "in-game-permission");
        config.maxMessageLength = Math.max(16, Nodes.integer(section, 200, "max-message-length"));
        config.minMessageLength = Math.max(1, Nodes.integer(section, 1, "min-message-length"));
        config.filterRepeatedChars = Nodes.bool(section, true, "filter-repeated-chars");
        config.filterSymbolsOnly = Nodes.bool(section, true, "filter-symbols-only");
        config.stripCqCodes = Nodes.bool(section, true, "strip-cq-codes");
        config.userRateLimit = Math.max(0, Nodes.integer(section, 20, "user-rate-limit"));
        config.groupRateLimit = Math.max(0, Nodes.integer(section, 60, "group-rate-limit"));
        config.stripColorsToQq = Nodes.bool(section, true, "strip-colors-to-qq");
        java.util.List<String> ignored = Nodes.strList(section, "ignored-prefixes");
        if (!ignored.isEmpty()) {
            config.ignoredPrefixes = new java.util.ArrayList<>(ignored);
        }
        return config;
    }

    /** 判断 QQ 号是否为管理员。 */
    public boolean isAdmin(long qq) {
        return adminWhitelist.contains(qq);
    }

    /** 是否被忽略的前缀（例如以 / 开头的消息不当聊天转发）。 */
    public boolean isIgnored(String message) {
        if (Strings.isBlank(message)) {
            return true;
        }
        for (String prefix : ignoredPrefixes) {
            if (prefix != null && !prefix.isEmpty() && message.startsWith(prefix)) {
                return true;
            }
        }
        return false;
    }

    public Map<String, Object> toMap() {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("admin-whitelist", new java.util.ArrayList<>(adminWhitelist));
        map.put("allow-group-admin", allowGroupAdmin);
        map.put("whitelist-enabled", whitelistEnabled);
        map.put("in-game-permission", inGamePermission);
        map.put("max-message-length", maxMessageLength);
        map.put("min-message-length", minMessageLength);
        map.put("filter-repeated-chars", filterRepeatedChars);
        map.put("filter-symbols-only", filterSymbolsOnly);
        map.put("strip-cq-codes", stripCqCodes);
        map.put("user-rate-limit", userRateLimit);
        map.put("group-rate-limit", groupRateLimit);
        map.put("strip-colors-to-qq", stripColorsToQq);
        map.put("ignored-prefixes", new java.util.ArrayList<>(ignoredPrefixes));
        return map;
    }
}
