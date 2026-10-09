package com.qqlink.velocity.config.model;

import java.util.LinkedHashMap;
import java.util.Map;

import com.qqlink.velocity.config.Nodes;

/**
 * 插件根配置对象。
 *
 * <p>{@link #load(Map)} 负责把 YAML 树映射为强类型对象；
 * {@link #toMap()} 反向映射，供写回配置文件使用（首次生成 / 升级补键）。
 * 两者必须保持对称，新增配置项时请同时修改这两处，否则用户升级后拿不到新键的注释。
 */
public final class PluginConfig {

    /** 全局功能总开关：false 时插件加载后不注册任何监听与指令 */
    public boolean enabled = true;
    /** 调试日志（打印每一条收发的 OneBot 报文，排查问题时开启） */
    public boolean debug = false;
    /** 配置结构版本，用于将来做配置迁移 */
    public String configVersion = "1.0.0";

    public OneBotConfig onebot = new OneBotConfig();
    public GroupEntry.Registry groups = new GroupEntry.Registry();
    public ServerEntry.Registry servers = new ServerEntry.Registry();
    public ForwardingConfig forwarding = new ForwardingConfig();
    public EventsConfig events = new EventsConfig();
    public CommandsConfig commands = new CommandsConfig();
    public Messages messages = new Messages();
    public SecurityConfig security = new SecurityConfig();

    public static PluginConfig load(Map<String, Object> root) {
        PluginConfig config = new PluginConfig();
        config.enabled = Nodes.bool(root, true, "enabled");
        config.debug = Nodes.bool(root, false, "debug");
        config.configVersion = Nodes.str(root, "1.0.0", "config-version");
        config.onebot = OneBotConfig.from(root);
        config.groups = GroupEntry.Registry.from(root);
        config.servers = ServerEntry.Registry.from(root);
        config.forwarding = ForwardingConfig.from(root);
        config.events = EventsConfig.from(root);
        config.commands = CommandsConfig.from(root);
        config.messages = Messages.from(root);
        config.security = SecurityConfig.from(root);
        return config;
    }

    /** 生成写回 YAML 的完整结构。 */
    public Map<String, Object> toMap() {
        Map<String, Object> root = new LinkedHashMap<>();
        root.put("config-version", configVersion);
        root.put("enabled", enabled);
        root.put("debug", debug);
        root.put("onebot", onebotMap());
        root.put("groups", groups.toMap());
        root.put("servers", servers.toMap());
        root.put("forwarding", forwarding.toMap());
        root.put("events", events.toMap());
        root.put("commands", commands.toMap());
        root.putAll(messages.toMap());
        root.put("security", security.toMap());
        return root;
    }

    private Map<String, Object> onebotMap() {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("mode", onebot.mode);
        map.put("forward-url", onebot.forwardUrl);
        Map<String, Object> reverse = new LinkedHashMap<>();
        reverse.put("host", onebot.reverseHost);
        reverse.put("port", onebot.reversePort);
        reverse.put("path", onebot.reversePath);
        map.put("reverse", reverse);
        map.put("access-token", onebot.accessToken);
        map.put("self-id", onebot.selfId);
        map.put("reconnect-interval", onebot.reconnectInterval);
        map.put("max-reconnect-delay", onebot.maxReconnectDelay);
        map.put("heartbeat-interval", onebot.heartbeatInterval);
        map.put("request-timeout", onebot.requestTimeout);
        map.put("send-retries", onebot.sendRetries);
        map.put("send-rate-limit", onebot.sendRateLimit);
        map.put("send-queue-limit", onebot.sendQueueLimit);
        map.put("debug", onebot.debug);
        return map;
    }
}
