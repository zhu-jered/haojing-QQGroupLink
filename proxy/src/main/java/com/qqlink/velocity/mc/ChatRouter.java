package com.qqlink.velocity.mc;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.logging.Logger;

import com.qqlink.velocity.config.model.EventsConfig;
import com.qqlink.velocity.config.model.ForwardingConfig;
import com.qqlink.velocity.config.model.GroupEntry;
import com.qqlink.velocity.config.model.Messages;
import com.qqlink.velocity.config.model.PluginConfig;
import com.qqlink.velocity.config.model.SecurityConfig;
import com.qqlink.velocity.config.model.ServerEntry;
import com.qqlink.velocity.onebot.OneBotBridge;
import com.qqlink.velocity.onebot.OneBotEvent;
import com.qqlink.velocity.util.Format;
import com.qqlink.velocity.util.Strings;

/**
 * 消息路由器：本插件的"大脑"。
 *
 * <h2>三条流转链路</h2>
 * <pre>
 * ▍链路 A：MC → QQ（玩家公屏聊天）
 *   PlayerChatEvent（代理事件线程）
 *     → 取玩家所在子服 id → 查显示名
 *     → 安全过滤（长度 / 纯符号 / 重复字符 / 忽略前缀）
 *     → 按 format.chat.mc-to-qq 渲染
 *     → 投递到 OneBotBridge 发送队列（异步、限流）→ QQ 群
 *
 * ▍链路 B：QQ → MC（群消息）
 *   OneBot 事件（事件线程池）
 *     → 群白名单校验（groups.strict）
 *     → 前缀路由解析（"生存服 你好" → 只发生存服）
 *     → 目标子服集合 = 群配置 ∩ 全局策略（all / primary）∩ 启用状态
 *     → 按 format.chat.qq-to-mc 渲染
 *     → 通过插件消息 CHAT 下发给各子服，由子服的模组/原版广播给玩家
 *
 * ▍链路 C：子服事件 → QQ（加入 / 退出 / 死亡 / 成就 / 公告）
 *   代理事件 或 插件消息上报
 *     → 事件开关判定（总闸 × 单项开关）
 *     → 渲染模板 → 发送到"所有配置了该子服的群"
 * </pre>
 *
 * <h2>防环设计</h2>
 * <ul>
 *   <li>QQ 消息转发到 MC 时<strong>不会</strong>再被 {@code PlayerChatEvent} 捕获
 *       （它不是玩家聊天，而是服务端消息），因此天然不会回环；</li>
 *   <li>机器人自身发的消息（{@code user_id == self_id}）在入口处直接丢弃，
 *       即使管理员用机器人账号发言也不会造成循环；</li>
 *   <li>以 {@code security.ignored-prefixes} 开头的 MC 聊天（默认 {@code /}）
 *       不转发，避免把指令当作聊天刷到群里。</li>
 * </ul>
 */
public final class ChatRouter {

    private final Logger logger;
    private volatile PluginConfig config;
    private final OneBotBridge bridge;
    private final ServerBridge servers;

    public ChatRouter(PluginConfig config, OneBotBridge bridge, ServerBridge servers, Logger logger) {
        this.config = config;
        this.bridge = bridge;
        this.servers = servers;
        this.logger = logger;
    }

    /** 热重载后替换配置引用。 */
    public void updateConfig(PluginConfig config) {
        this.config = config;
    }

    public PluginConfig config() {
        return config;
    }

    // ==================================================================
    // 链路 A：MC → QQ
    // ==================================================================

    /**
     * 玩家在子服里发了一条公屏消息（代理事件路径，可做权限检查）。
     *
     * @param player     发送者（Velocity 玩家对象，用于可选的权限门槛检查）
     * @param serverId   玩家所在子服 id
     * @param rawMessage 原始消息
     * @return 是否实际转发（false 表示被开关、权限或过滤器拦下）
     */
    public boolean onPlayerChat(com.velocitypowered.api.proxy.Player player, String serverId, String rawMessage) {
        PluginConfig cfg = this.config;
        if (!cfg.forwarding.chatToQq || Strings.isBlank(serverId)) {
            return false;
        }
        // 可选的权限门槛：只有拥有该权限节点的玩家才会被转发到 QQ。
        // 说明：Velocity 本身没有权限系统，hasPermission 在未安装权限插件时
        //      通常返回 false；因此这里只在配置了非空权限节点时才检查，
        //      并且把 hasPermission 包在 try-catch 中，避免权限插件异常拖垮聊天转发。
        if (Strings.isNotBlank(cfg.forwarding.chatPermission)) {
            try {
                if (!player.hasPermission(cfg.forwarding.chatPermission)) {
                    return false;
                }
            } catch (Throwable throwable) {
                logger.fine("[路由] 权限检查失败（" + cfg.forwarding.chatPermission + "）：" + throwable.getMessage());
            }
        }
        return onPlayerChat(player.getUsername(), serverId, rawMessage);
    }

    /**
     * 玩家在子服里发了一条公屏消息（仅按名字，用于模组上报路径）。
     *
     * @param playerName 玩家名
     * @param serverId   玩家所在子服 id
     * @param rawMessage 原始消息
     * @return 是否实际转发（false 表示被开关或过滤器拦下）
     */
    public boolean onPlayerChat(String playerName, String serverId, String rawMessage) {
        PluginConfig cfg = this.config;
        ForwardingConfig forwarding = cfg.forwarding;
        SecurityConfig security = cfg.security;

        if (!forwarding.chatToQq) {
            return false;
        }
        if (Strings.isBlank(serverId)) {
            return false;
        }
        // 单子服级别的开关（servers.<id>.forward-chat）
        ServerEntry entry = cfg.servers.get(serverId);
        if (entry != null && entry.forwardChat != null && !entry.forwardChat) {
            return false;
        }
        if (!cfg.servers.isEnabled(serverId)) {
            return false;
        }
        // 忽略前缀（默认 "/"）：不把指令当聊天转发
        if (security.isIgnored(rawMessage)) {
            return false;
        }

        String message = normalize(rawMessage);
        if (message.isEmpty()) {
            return false;
        }

        String displayName = cfg.servers.displayName(serverId);
        String rendered = Format.of(cfg.messages.chatToQq)
                .set("server", displayName)
                .set("server_id", serverId)
                .set("player", playerName)
                .set("message", message)
                .trustValues()
                .render();

        if (security.stripColorsToQq) {
            rendered = Strings.normalizeQqMessage(rendered, false);
        }
        broadcastToGroups(serverId, rendered, true);
        return true;
    }

    // ==================================================================
    // 链路 B：QQ → MC
    // ==================================================================

    /**
     * 处理一条来自 QQ 群的聊天消息。
     *
     * @param event         OneBot 事件（已确认是群消息）
     * @param normalizedText 已剔除 CQ 码、@机器人 的纯文本
     * @return 实际转发到的子服数量（0 表示未转发）
     */
    public int onGroupChat(OneBotEvent event, String normalizedText) {
        PluginConfig cfg = this.config;
        if (!cfg.forwarding.chatToMc) {
            return 0;
        }
        GroupEntry group = cfg.groups.get(event.groupId);
        if (group != null && !group.chat) {
            return 0;
        }
        String message = normalize(normalizedText);
        if (message.isEmpty()) {
            return 0;
        }

        QqTarget target = resolveTargets(event.groupId, message);
        if (target.servers().isEmpty()) {
            return 0;
        }

        String groupName = group == null ? String.valueOf(event.groupId) : group.display();
        String rendered = Format.of(cfg.messages.chatToMc)
                .set("server", target.servers().size() == 1
                        ? cfg.servers.displayName(target.servers().iterator().next()) : "全服")
                .set("sender", event.senderName)
                .set("sender_id", event.userId)
                .set("group", groupName)
                .set("group_id", event.groupId)
                .set("message", target.message())
                .trustValues()
                .render();

        int count = 0;
        for (String serverId : target.servers()) {
            if (pushToServer(serverId, PluginMessages.DOWN_CHAT, rendered)) {
                count++;
            }
        }

        // 控制台留档：便于服主在代理日志里核对 QQ 消息
        if (Strings.isNotBlank(cfg.messages.chatToConsole)) {
            logger.info(Format.of(cfg.messages.chatToConsole)
                    .set("group", groupName)
                    .set("group_id", event.groupId)
                    .set("sender", event.senderName)
                    .set("sender_id", event.userId)
                    .set("message", target.message())
                    .renderPlain());
        }
        return count;
    }

    /**
     * 解析一条 QQ 消息应转发到哪些子服。
     *
     * <p>规则优先级（从高到低）：
     * <ol>
     *   <li><strong>消息前缀</strong>：{@code "生存服 你好"} / {@code "[生存服] 你好"}
     *       / {@code "生存服: 你好"} —— 只发该子服（需 {@code prefix-routing.enabled: true}）；</li>
     *   <li><strong>群配置</strong>：{@code groups.<群号>.mc-servers} 限定范围；</li>
     *   <li><strong>全局策略</strong>：{@code forwarding.qq-to-minecraft.target}
     *       = {@code all}（所有启用子服）或 {@code primary}（仅主服）。</li>
     * </ol>
     */
    public QqTarget resolveTargets(long groupId, String rawMessage) {
        PluginConfig cfg = this.config;
        Set<String> candidates = new LinkedHashSet<>();

        // (2) 群配置限定范围
        GroupEntry group = cfg.groups.get(groupId);
        if (group != null && !group.servers().isEmpty()) {
            for (String serverId : group.servers()) {
                if (cfg.servers.isEnabled(serverId) && servers.exists(serverId)) {
                    candidates.add(serverId);
                }
            }
        }

        // (3) 全局策略
        if (candidates.isEmpty()) {
            if ("primary".equals(cfg.forwarding.chatTarget)) {
                String primary = cfg.servers.primaryId();
                if (Strings.isNotBlank(primary)) {
                    candidates.add(primary);
                }
            } else {
                for (ServerEntry entry : cfg.servers.enabledServers()) {
                    if (servers.exists(entry.id)) {
                        candidates.add(entry.id);
                    }
                }
                // 配置里没写的子服（例如刚在 velocity.toml 里新增）也一并包含
                for (String serverId : servers.registeredIds()) {
                    if (cfg.servers.isEnabled(serverId)) {
                        candidates.add(serverId);
                    }
                }
            }
        }

        // (1) 前缀路由：命中则收敛为单个子服
        String message = rawMessage;
        if (cfg.forwarding.prefixRouting) {
            PrefixMatch match = matchPrefix(rawMessage, cfg);
            if (match != null) {
                message = match.remainder();
                if (cfg.servers.isEnabled(match.serverId()) && servers.exists(match.serverId())) {
                    return new QqTarget(Set.of(match.serverId()), message);
                }
            }
        }
        return new QqTarget(candidates, message);
    }

    /**
     * 前缀匹配。
     *
     * <p>支持的写法（{@code mode} 控制用显示名还是 velocity id）：
     * <pre>
     *   生存服 你好        ← 空格分隔
     *   生存服: 你好       ← 冒号分隔
     *   生存服：你好       ← 中文冒号
     *   [生存服] 你好      ← 方括号包裹
     *   【生存服】你好     ← 中文方括号
     *   @生存服 你好       ← @ 前缀
     * </pre>
     */
    private PrefixMatch matchPrefix(String message, PluginConfig cfg) {
        if (Strings.isBlank(message)) {
            return null;
        }
        String mode = cfg.forwarding.prefixRoutingMode;
        boolean allowSeparators = cfg.forwarding.prefixSeparators;

        for (ServerEntry entry : cfg.servers.sorted()) {
            List<String> names = new ArrayList<>();
            if (mode.equals("display") || mode.equals("both")) {
                if (Strings.isNotBlank(entry.displayName)) {
                    names.add(entry.displayName);
                }
            }
            if (mode.equals("id") || mode.equals("both")) {
                names.add(entry.id);
            }
            for (String name : names) {
                String remainder = stripPrefix(message, name, allowSeparators);
                if (remainder != null && Strings.isNotBlank(remainder)) {
                    return new PrefixMatch(entry.id, remainder.trim());
                }
            }
        }
        return null;
    }

    /** 尝试从消息开头剥离指定前缀，成功返回剩余内容，失败返回 null。 */
    private String stripPrefix(String message, String name, boolean allowSeparators) {
        if (Strings.isBlank(name)) {
            return null;
        }
        String trimmed = message.trim();
        // 大小写不敏感匹配（velocity id 常为英文小写）
        if (!trimmed.regionMatches(true, 0, name, 0, name.length())) {
            return null;
        }
        String rest = trimmed.substring(name.length()).trim();
        if (rest.isEmpty()) {
            return null;
        }
        if (!allowSeparators) {
            // 严格要求"名字 + 空格"，避免 "生存服主" 被误判为 "生存服" 前缀
            return trimmed.length() > name.length() && Character.isWhitespace(trimmed.charAt(name.length()))
                    ? rest : null;
        }
        char first = rest.charAt(0);
        // 允许的分隔符：空格已在 trim 中处理，这里处理标点与括号
        if (first == ':' || first == '\uff1a' || first == '\uff1b' || first == ';'
                || first == '-' || first == '\u2014' || first == '\u3001' || first == ',') {
            return rest.substring(1).trim();
        }
        if (first == ']' || first == '\uff3d' || first == '\u3011' || first == ')'
                || first == '\uff09' || first == '>' || first == '\u300b') {
            // 形如 [生存服]你好 / 【生存服】你好
            return rest.substring(1).trim();
        }
        // 形如 "生存服你好"：只要不是数字/字母开头就认为是紧贴写法
        if (!Character.isLetterOrDigit(first) && !Character.isWhitespace(first)) {
            return rest;
        }
        return null;
    }

    // ==================================================================
    // 链路 C：子服事件 → QQ
    // ==================================================================

    /** 玩家加入子服。 */
    public void onPlayerJoin(String playerName, String serverId) {
        PluginConfig cfg = this.config;
        EventsConfig events = cfg.events;
        if (!events.isActive(events.join)) {
            return;
        }
        String text = Format.of(cfg.messages.join)
                .set("server", cfg.servers.displayName(serverId))
                .set("server_id", serverId)
                .set("player", playerName)
                .set("count", servers.playerCount(serverId))
                .trustValues()
                .renderPlain();
        broadcastToGroups(serverId, text, false);
    }

    /** 玩家退出子服。 */
    public void onPlayerQuit(String playerName, String serverId) {
        PluginConfig cfg = this.config;
        EventsConfig events = cfg.events;
        if (!events.isActive(events.quit)) {
            return;
        }
        String text = Format.of(cfg.messages.quit)
                .set("server", cfg.servers.displayName(serverId))
                .set("server_id", serverId)
                .set("player", playerName)
                .set("count", servers.playerCount(serverId))
                .trustValues()
                .renderPlain();
        broadcastToGroups(serverId, text, false);
    }

    /** 玩家切换子服。 */
    public void onServerSwitch(String playerName, String fromServerId, String toServerId) {
        PluginConfig cfg = this.config;
        EventsConfig events = cfg.events;
        if (!events.isActive(events.serverSwitch)) {
            return;
        }
        String text = Format.of(cfg.messages.serverSwitch)
                .set("player", playerName)
                .set("from", cfg.servers.displayName(fromServerId))
                .set("from_id", Strings.safe(fromServerId))
                .set("to", cfg.servers.displayName(toServerId))
                .set("to_id", Strings.safe(toServerId))
                .set("server", cfg.servers.displayName(toServerId))
                .trustValues()
                .renderPlain();
        // 切换通知发到"两个子服都相关的群"更合理，这里取源子服作为归属
        broadcastToGroups(Strings.isNotBlank(fromServerId) ? fromServerId : toServerId, text, false);
    }

    /** 玩家死亡。 */
    public void onPlayerDeath(String playerName, String serverId, String cause) {
        PluginConfig cfg = this.config;
        EventsConfig events = cfg.events;
        if (!events.isActive(events.death)) {
            return;
        }
        if (!cfg.events.deathCauseMode.equals("none")) {
            cause = Strings.truncate(Strings.safe(cause), events.deathCauseMaxLength);
        } else {
            cause = "";
        }
        String text = Format.of(cfg.messages.death)
                .set("player", playerName)
                .set("server", cfg.servers.displayName(serverId))
                .set("server_id", serverId)
                .set("cause", cause)
                .trustValues()
                .renderPlain();
        broadcastToGroups(serverId, text, false);
    }

    /** 玩家解锁成就。 */
    public void onAdvancement(String playerName, String serverId, String title, String frame) {
        PluginConfig cfg = this.config;
        EventsConfig events = cfg.events;
        if (!events.isActive(events.advancement)) {
            return;
        }
        EventsConfig.Advancement filter = events.advancementFilter;
        if (filter.ignoreRecipes && "recipe".equalsIgnoreCase(Strings.safe(frame))) {
            return;
        }
        if (filter.ignoreChallenges && "challenge".equalsIgnoreCase(Strings.safe(frame))) {
            return;
        }
        for (String keyword : filter.blacklist) {
            if (Strings.isNotBlank(keyword) && Strings.safe(title).contains(keyword)) {
                return;
            }
        }
        String text = Format.of(cfg.messages.advancement)
                .set("player", playerName)
                .set("server", cfg.servers.displayName(serverId))
                .set("server_id", serverId)
                .set("advancement", title)
                .set("title", title)
                .set("frame", frame)
                .trustValues()
                .renderPlain();
        broadcastToGroups(serverId, text, false);
    }

    /** 管理员公告（子服上报或代理侧捕获）。 */
    public void onAdminBroadcast(String serverId, String message) {
        PluginConfig cfg = this.config;
        EventsConfig events = cfg.events;
        if (!events.isActive(events.adminBroadcast)) {
            return;
        }
        String text = Format.of(cfg.messages.broadcast)
                .set("server", cfg.servers.displayName(serverId))
                .set("server_id", Strings.safe(serverId))
                .set("message", message)
                .trustValues()
                .renderPlain();
        broadcastToGroups(serverId, text, false);
    }

    /** 子服启动。 */
    public void onServerStart(String serverId) {
        PluginConfig cfg = this.config;
        EventsConfig events = cfg.events;
        if (!events.isActive(events.serverStatus)) {
            return;
        }
        String text = Format.of(cfg.messages.serverStart)
                .set("server", cfg.servers.displayName(serverId))
                .set("server_id", serverId)
                .set("count", servers.playerCount(serverId))
                .trustValues()
                .renderPlain();
        broadcastToGroups(serverId, text, events.serverStatusAtAll);
    }

    /** 子服关闭。 */
    public void onServerStop(String serverId) {
        PluginConfig cfg = this.config;
        EventsConfig events = cfg.events;
        if (!events.isActive(events.serverStatus)) {
            return;
        }
        String text = Format.of(cfg.messages.serverStop)
                .set("server", cfg.servers.displayName(serverId))
                .set("server_id", serverId)
                .set("count", 0)
                .trustValues()
                .renderPlain();
        broadcastToGroups(serverId, text, events.serverStatusAtAll);
    }

    // ==================================================================
    // 跨子服聊天（可选）
    // ==================================================================

    /**
     * 把一条 MC 聊天同步到其他子服。
     *
     * <p>默认关闭（{@code forwarding.cross-server-chat.enabled: false}）。
     * 开启后，生存服的聊天会以 {@code [生存服] 玩家: 消息} 的形式
     * 出现在镜像服玩家屏幕上，实现"双服同一世界感"。
     */
    public void relayToOtherServers(String playerName, String sourceServerId, String message) {
        PluginConfig cfg = this.config;
        if (!cfg.forwarding.crossServerChat) {
            return;
        }
        String rendered = Format.of(cfg.messages.chatToQq)
                .set("server", cfg.servers.displayName(sourceServerId))
                .set("server_id", sourceServerId)
                .set("player", playerName)
                .set("message", message)
                .trustValues()
                .render();
        for (ServerEntry entry : cfg.servers.enabledServers()) {
            if (!cfg.forwarding.crossServerEchoSelf && entry.id.equals(sourceServerId)) {
                continue;
            }
            if (!entry.enabled || !servers.exists(entry.id)) {
                continue;
            }
            pushToServer(entry.id, PluginMessages.DOWN_CHAT, rendered);
        }
    }

    // ==================================================================
    // 工具
    // ==================================================================

    /**
     * 安全过滤 + 长度限制。
     *
     * @return 处理后的消息；被过滤时返回空字符串
     */
    private String normalize(String raw) {
        SecurityConfig security = this.config.security;
        String message = security.stripCqCodes
                ? Strings.normalizeQqMessage(raw, true)
                : Strings.safe(raw).trim();
        if (message.length() > security.maxMessageLength) {
            message = Strings.truncate(message, security.maxMessageLength);
        }
        if (security.filterSymbolsOnly
                && !Strings.isMeaningful(message, security.minMessageLength, security.filterRepeatedChars)) {
            return "";
        }
        if (!security.filterSymbolsOnly
                && !Strings.isMeaningful(message, security.minMessageLength, false)) {
            return "";
        }
        return message;
    }

    /**
     * 把一条已渲染好的消息发到"所有绑定了该子服的群"。
     *
     * @param serverId 消息来源子服
     * @param rendered 已渲染的纯文本
     * @param atAll    是否 @全体成员
     * @return 实际发送的群数量
     */
    public int broadcastToGroups(String serverId, String rendered, boolean atAll) {
        PluginConfig cfg = this.config;
        if (!bridge.isConnected()) {
            logger.fine("[路由] OneBot 未连接，消息被丢弃：" + Strings.truncate(rendered, 60));
            return 0;
        }
        String text;
        if (atAll) {
            // @全体成员 在不同 OneBot 实现里写法不统一；CQ 码写法兼容面最广（go-cqhttp / NapCat）
            text = rendered + "[CQ:at,qq=all]";
        } else {
            text = rendered;
        }

        int count = 0;
        if (cfg.groups.hasAny()) {
            for (GroupEntry group : cfg.groups.all().values()) {
                if (!group.chat && !group.commands) {
                    // 既不聊天也不指令的群，只接收"服务器状态"这类系统消息
                    if (!cfg.events.serverStatus) {
                        continue;
                    }
                }
                if (!groupAccepts(group, serverId)) {
                    continue;
                }
                bridge.sendGroup(group.groupId, text);
                count++;
            }
        }
        return count;
    }

    /**
     * 判断某个群是否关心某个子服的消息。
     *
     * <ul>
     *   <li>群未配置 {@code mc-servers}（或为空）→ 接收所有子服消息（一群多服 / 广播群）；</li>
     *   <li>群配置了子服列表 → 只在列表包含该子服时接收。</li>
     * </ul>
     */
    private boolean groupAccepts(GroupEntry group, String serverId) {
        if (group.servers().isEmpty()) {
            return true;
        }
        if (Strings.isBlank(serverId)) {
            return true;
        }
        return group.servers().contains(serverId);
    }

    /** 下发一条插件消息到子服。 */
    public boolean pushToServer(String serverId, String type, String payload) {
        return servers.sendToServer(serverId, new String(PluginMessages.encodeDown(type, payload),
                java.nio.charset.StandardCharsets.UTF_8), PluginMessages.CHANNEL_NAME);
    }

    /** 供指令系统复用：向所有启用的子服下发一条消息。 */
    public int pushToAllServers(String type, String payload) {
        PluginConfig cfg = this.config;
        int count = 0;
        for (ServerEntry entry : cfg.servers.enabledServers()) {
            if (pushToServer(entry.id, type, payload)) {
                count++;
            }
        }
        // 补上配置里没有但 velocity.toml 里注册了的子服
        for (String serverId : servers.registeredIds()) {
            if (cfg.servers.get(serverId) == null && pushToServer(serverId, type, payload)) {
                count++;
            }
        }
        return count;
    }

    /** 目标子服集合 + 剥离前缀后的消息。 */
    public record QqTarget(Set<String> servers, String message) {
    }

    /** 前缀匹配结果。 */
    private record PrefixMatch(String serverId, String remainder) {
    }

    /** 供指令系统使用：向指定 QQ 群发送一条消息（直接走 bridge）。 */
    public void sendToGroup(long groupId, String text) {
        bridge.sendGroup(groupId, text);
    }
}
