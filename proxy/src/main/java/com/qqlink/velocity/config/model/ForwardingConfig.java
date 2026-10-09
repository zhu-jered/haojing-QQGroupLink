package com.qqlink.velocity.config.model;

import java.util.LinkedHashMap;
import java.util.Map;

import com.qqlink.velocity.config.Nodes;

/**
 * 双向转发规则配置。
 *
 * <p>这里只管"转发路径"，不管"消息长什么样"（后者在 {@link Messages} 中）。
 */
public final class ForwardingConfig {

    // ---------------- MC → QQ ----------------

    /** 是否转发玩家公屏聊天到 QQ */
    public boolean chatToQq = true;
    /** 玩家需要有权限才能转发（留空表示不检查） */
    public String chatPermission = "";

    // ---------------- QQ → MC ----------------

    /** 是否把 QQ 群消息转发到 MC */
    public boolean chatToMc = true;
    /**
     * QQ 消息默认转发目标：
     * <ul>
     *   <li>{@code all}   —— 所有启用的子服（默认）</li>
     *   <li>{@code primary} —— 仅主服</li>
     * </ul>
     */
    public String chatTarget = "all";

    // ---------------- 前缀指定目标服务器 ----------------

    /** 是否允许用消息前缀指定目标子服，例如 "生存服 你好" 或 "[生存服]你好" */
    public boolean prefixRouting = true;
    /** 前缀写法：display（按显示名匹配）/ id（按 velocity 服务器名匹配）/ both */
    public String prefixRoutingMode = "both";
    /** 前缀与内容之间是否允许没有空格（如 "生存服:你好"） */
    public boolean prefixSeparators = true;

    // ---------------- 跨子服转发 ----------------

    /** 是否把 A 服的聊天同步显示到 B 服（跨服聊天） */
    public boolean crossServerChat = false;
    /** 跨服聊天时是否包含消息来源者所在服务器 */
    public boolean crossServerEchoSelf = false;

    // ---------------- 玩家上下线时的服务器切换提示 ----------------

    /** 忽略"切换子服"产生的退出+加入通知（只发一条"切换服务器"） */
    public boolean suppressSwitchNoise = true;

    public static ForwardingConfig from(Map<String, Object> root) {
        ForwardingConfig config = new ForwardingConfig();
        Map<String, Object> section = Nodes.section(root, "forwarding");
        config.chatToQq = Nodes.bool(section, true, "minecraft-to-qq", "chat");
        config.chatPermission = Nodes.str(section, "", "minecraft-to-qq", "require-permission");
        config.chatToMc = Nodes.bool(section, true, "qq-to-minecraft", "chat");
        config.chatTarget = Nodes.str(section, "all", "qq-to-minecraft", "target").toLowerCase(java.util.Locale.ROOT);
        if (!config.chatTarget.equals("all") && !config.chatTarget.equals("primary")) {
            config.chatTarget = "all";
        }
        config.prefixRouting = Nodes.bool(section, true, "qq-to-minecraft", "prefix-routing", "enabled");
        config.prefixRoutingMode = Nodes.str(section, "both", "qq-to-minecraft", "prefix-routing", "mode")
                .toLowerCase(java.util.Locale.ROOT);
        config.prefixSeparators = Nodes.bool(section, true, "qq-to-minecraft", "prefix-routing", "allow-separators");
        config.crossServerChat = Nodes.bool(section, false, "cross-server-chat", "enabled");
        config.crossServerEchoSelf = Nodes.bool(section, false, "cross-server-chat", "echo-to-source");
        config.suppressSwitchNoise = Nodes.bool(section, true, "suppress-switch-noise");
        return config;
    }

    /** 写回配置（用于首次生成默认文件）。 */
    public Map<String, Object> toMap() {
        Map<String, Object> map = new LinkedHashMap<>();
        Map<String, Object> toQq = new LinkedHashMap<>();
        toQq.put("chat", chatToQq);
        toQq.put("require-permission", chatPermission);
        map.put("minecraft-to-qq", toQq);

        Map<String, Object> routing = new LinkedHashMap<>();
        routing.put("enabled", prefixRouting);
        routing.put("mode", prefixRoutingMode);
        routing.put("allow-separators", prefixSeparators);

        Map<String, Object> toMc = new LinkedHashMap<>();
        toMc.put("chat", chatToMc);
        toMc.put("target", chatTarget);
        toMc.put("prefix-routing", routing);
        map.put("qq-to-minecraft", toMc);

        Map<String, Object> cross = new LinkedHashMap<>();
        cross.put("enabled", crossServerChat);
        cross.put("echo-to-source", crossServerEchoSelf);
        map.put("cross-server-chat", cross);

        map.put("suppress-switch-noise", suppressSwitchNoise);
        return map;
    }
}
