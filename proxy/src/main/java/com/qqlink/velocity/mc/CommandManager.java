package com.qqlink.velocity.mc;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import java.util.logging.Logger;

import com.velocitypowered.api.proxy.Player;
import com.qqlink.velocity.config.model.CommandsConfig;
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
 * QQ 群指令系统。
 *
 * <h2>一条指令的完整生命周期</h2>
 * <pre>
 *  QQ 消息 "＃list"
 *    ① 入参过滤        isGroupMessage / 未被忽略 / 非机器人自己发的
 *    ② 触发判定        "以指令前缀开头" 或 "含 @机器人"
 *    ③ 权限判定        admin-whitelist → 命中才能进 ④；否则按 unauthorized 静默或回绝
 *    ④ 指令解析        前缀剥离 → 指令名 → Builtin 枚举（支持别名）
 *    ⑤ 开关判定        commands.enabled × commands.builtin.&lt;名&gt;.enabled
 *    ⑥ 限流判定        security.user-rate-limit
 *    ⑦ 业务执行        在 OneBotBridge 的事件线程池里执行（不阻塞任何主线程）
 *    ⑧ 回执            feedback=true 时把结果回发到来源群
 * </pre>
 *
 * <h2>安全设计</h2>
 * <ul>
 *   <li><strong>默认静默</strong>：非管理员发管理指令时，默认连"权限不足"都不回，
 *       避免被用来探测"这个群是否接了机器人"（配置 {@code commands.unauthorized: reply} 可改为提示）；</li>
 *   <li><strong>白名单双重校验</strong>：QQ 号白名单 + 群是否允许指令
 *       （{@code groups.<群号>.commands}），任一不通过都拒绝；</li>
 *   <li><strong>限流</strong>：每个 QQ 号每分钟最多 {@code user-rate-limit} 次，
 *       防止恶意刷指令打满子服；</li>
 *   <li><strong>长度限制</strong>：{@code #broadcast}/{@code #send} 的内容会被截断到
 *       {@code security.max-message-length}，避免一条消息刷满全服屏幕。</li>
 * </ul>
 */
public final class CommandManager {

    private final Logger logger;
    private volatile PluginConfig config;
    private final OneBotBridge bridge;
    /** 间接持有路由器，保证 /qqlink reload 重建后本管理器仍指向新实例。 */
    private final com.qqlink.velocity.util.Holder<ChatRouter> routerHolder;
    private final ServerBridge servers;
    private final PlayerManager players;

    /** QQ 号 → (窗口起点分钟, 次数) */
    private final Map<Long, RateWindow> userRate = new ConcurrentHashMap<>();
    /** 群号 → (窗口起点分钟, 次数) */
    private final Map<Long, RateWindow> groupRate = new ConcurrentHashMap<>();
    private final AtomicLong handledCommands = new AtomicLong();

    public CommandManager(PluginConfig config, OneBotBridge bridge,
                          com.qqlink.velocity.util.Holder<ChatRouter> routerHolder,
                          ServerBridge servers, PlayerManager players, Logger logger) {
        this.config = config;
        this.bridge = bridge;
        this.routerHolder = routerHolder;
        this.servers = servers;
        this.players = players;
        this.logger = logger;
    }

    /** 当前路由器实例。 */
    private ChatRouter router() {
        return routerHolder.get();
    }

    public void updateConfig(PluginConfig config) {
        this.config = config;
    }

    public long handledCommands() {
        return handledCommands.get();
    }

    // ==================================================================
    // 入口
    // ==================================================================

    /**
     * 尝试把一条 QQ 群消息当作指令处理。
     *
     * @param event  群消息事件
     * @param text   已剔除 CQ 码的纯文本（@机器人 已被替换为占位符或删除）
     * @param mentioned 该消息是否 @ 了机器人
     * @return true 表示已按指令处理（调用方不应再当作聊天转发）
     */
    public boolean handle(OneBotEvent event, String text, boolean mentioned) {
        PluginConfig cfg = this.config;
        CommandsConfig commands = cfg.commands;
        if (!commands.enabled) {
            return false;
        }
        String raw = Strings.safe(text).trim();
        if (raw.isEmpty()) {
            return false;
        }

        // ② 触发判定
        String prefix = commands.matchPrefix(raw);
        String body = prefix == null ? null : commands.strip(raw);
        boolean triggered = prefix != null || (mentioned && commands.replyOnMention);
        if (!triggered) {
            return false;
        }

        // 群是否允许使用指令
        GroupEntry group = cfg.groups.get(event.groupId);
        if (group != null && !group.commands) {
            return false;
        }

        // ③ 权限判定
        boolean admin = isAdmin(event);
        if (!admin) {
            if ("reply".equals(commands.unauthorized)) {
                // 仅在明确是指令时才提示，避免 @机器人聊天被误回
                if (prefix != null) {
                    reply(event, cfg.messages.noPermission);
                    return true;
                }
            }
            if (prefix != null) {
                // 静默模式：完全无响应，仅记录调试日志
                logger.fine("[指令] 拒绝非管理员 " + event.userId + " 的指令：" + Strings.truncate(raw, 40));
                return true;
            }
            return false;
        }

        // ④ 指令解析
        if (body == null || body.isEmpty()) {
            if (mentioned) {
                reply(event, Format.of(cfg.messages.unknownCommand)
                        .set("command", raw)
                        .set("prefix", commands.prefixes.isEmpty() ? "#" : commands.prefixes.get(0))
                        .renderPlain());
                return true;
            }
            return false;
        }

        String[] pieces = body.split("\\s+", 2);
        String name = pieces[0];
        String arguments = pieces.length > 1 ? pieces[1].trim() : "";

        CommandsConfig.Builtin builtin = commands.resolve(name);
        if (builtin == null) {
            if (prefix != null) {
                reply(event, Format.of(cfg.messages.unknownCommand)
                        .set("command", name)
                        .set("prefix", commands.prefixes.isEmpty() ? "#" : commands.prefixes.get(0))
                        .renderPlain());
                return true;
            }
            return false;
        }

        // ⑤ 单项开关
        if (!commands.isEnabled(builtin)) {
            logger.fine("[指令] " + builtin.id() + " 已在配置中禁用");
            return true;
        }

        // ⑥ 限流
        if (!checkUserRate(event.userId)) {
            if (commands.feedback) {
                reply(event, cfg.messages.rateLimited);
            }
            return true;
        }

        handledCommands.incrementAndGet();
        // ⑦ 执行业务（本方法已运行在 OneBotBridge 的事件线程池中）
        try {
            execute(event, builtin, arguments);
        } catch (Throwable throwable) {
            logger.warning("[指令] 执行 " + builtin.id() + " 出错：" + throwable);
            reply(event, "&c指令执行出错，请查看代理控制台日志");
        }
        return true;
    }

    /** 是否为管理员。 */
    public boolean isAdmin(OneBotEvent event) {
        SecurityConfig security = this.config.security;
        if (security.isAdmin(event.userId)) {
            return true;
        }
        if (security.allowGroupAdmin && event.isGroupAdmin()) {
            return true;
        }
        // 未启用白名单机制时，任何群成员都可用（危险，仅调试用）
        return !security.whitelistEnabled;
    }

    /** 执行具体指令。 */
    private void execute(OneBotEvent event, CommandsConfig.Builtin builtin, String arguments) {
        switch (builtin) {
            case LIST -> doList(event);
            case TPS -> doTps(event);
            case BROADCAST -> doBroadcast(event, arguments);
            case SEND -> doSend(event, arguments);
            case HELP -> doHelp(event);
            case STATUS -> doStatus(event);
        }
    }

    // ==================================================================
    // #list —— 各子服在线玩家
    // ==================================================================

    private void doList(OneBotEvent event) {
        PluginConfig cfg = this.config;
        Messages messages = cfg.messages;

        List<ServerEntry> entries = cfg.servers.enabledServers();
        List<String> lines = new ArrayList<>();
        List<String> emptyServers = new ArrayList<>();
        Set<String> counted = new LinkedHashSet<>();
        int total = 0;

        for (ServerEntry entry : entries) {
            counted.add(entry.id);
            List<Player> online = servers.playersOn(entry.id);
            total += online.size();
            players.setLastKnownCount(entry.id, online.size());

            if (online.isEmpty() && !cfg.commands.listIncludeEmpty) {
                continue;
            }
            String names;
            if (online.isEmpty()) {
                names = messages.listEmpty;
            } else {
                List<String> playerNames = new ArrayList<>(online.size());
                for (Player player : online) {
                    playerNames.add(player.getUsername());
                }
                names = String.join("&7, &f", playerNames);
            }
            lines.add(Format.of(messages.listLine)
                    .set("server", entry.displayName)
                    .set("server_id", entry.id)
                    .set("count", online.size())
                    .set("players", names)
                    .trustValues()
                    .renderPlain());
            if (online.isEmpty()) {
                emptyServers.add(entry.displayName);
            }
        }

        // 补上 velocity.toml 里注册但配置中没写的子服，避免"漏服"
        for (String serverId : servers.registeredIds()) {
            if (counted.contains(serverId)) {
                continue;
            }
            List<Player> online = servers.playersOn(serverId);
            total += online.size();
            if (online.isEmpty() && !cfg.commands.listIncludeEmpty) {
                continue;
            }
            List<String> playerNames = new ArrayList<>();
            for (Player player : online) {
                playerNames.add(player.getUsername());
            }
            lines.add(Format.of(messages.listLine)
                    .set("server", serverId)
                    .set("server_id", serverId)
                    .set("count", online.size())
                    .set("players", online.isEmpty() ? messages.listEmpty : String.join("&7, &f", playerNames))
                    .trustValues()
                    .renderPlain());
        }

        StringBuilder out = new StringBuilder();
        out.append(Format.of(messages.listHeader)
                .set("count", total)
                .set("servers", lines.size())
                .trustValues()
                .renderPlain());
        for (String line : lines) {
            out.append('\n').append(line);
        }
        reply(event, out.toString());
    }

    // ==================================================================
    // #tps —— 性能数据
    // ==================================================================

    private void doTps(OneBotEvent event) {
        PluginConfig cfg = this.config;
        Messages messages = cfg.messages;

        List<String> lines = new ArrayList<>();
        for (ServerEntry entry : cfg.servers.enabledServers()) {
            if (players.hasMetrics(entry.id)) {
                double tps = players.tps(entry.id);
                double mspt = players.mspt(entry.id);
                lines.add(Format.of(messages.tpsLine)
                        .set("server", entry.displayName)
                        .set("server_id", entry.id)
                        .set("tps", Strings.fixed(tps, 1) + "(" + PlayerManager.tpsQuality(tps) + ")")
                        .set("mspt", Strings.fixed(mspt, 1))
                        .set("count", servers.playerCount(entry.id))
                        .trustValues()
                        .renderPlain());
            } else {
                lines.add(Format.of(messages.tpsUnavailable)
                        .set("server", entry.displayName)
                        .set("server_id", entry.id)
                        .trustValues()
                        .renderPlain());
            }
        }
        for (String serverId : servers.registeredIds()) {
            if (cfg.servers.get(serverId) != null) {
                continue;
            }
            lines.add(Format.of(players.hasMetrics(serverId) ? messages.tpsLine : messages.tpsUnavailable)
                    .set("server", serverId)
                    .set("server_id", serverId)
                    .set("tps", Strings.fixed(players.tps(serverId), 1))
                    .set("mspt", Strings.fixed(players.mspt(serverId), 1))
                    .set("count", servers.playerCount(serverId))
                    .trustValues()
                    .renderPlain());
        }

        StringBuilder out = new StringBuilder();
        out.append(Format.of(messages.tpsHeader)
                .set("count", lines.size())
                .trustValues()
                .renderPlain());
        for (String line : lines) {
            out.append('\n').append(line);
        }
        reply(event, out.toString());
    }

    // ==================================================================
    // #broadcast —— 全服公告
    // ==================================================================

    private void doBroadcast(OneBotEvent event, String arguments) {
        PluginConfig cfg = this.config;
        if (Strings.isBlank(arguments)) {
            reply(event, Format.of(cfg.messages.usage)
                    .set("usage", CommandsConfig.Builtin.BROADCAST.usage())
                    .renderPlain());
            return;
        }
        String content = Strings.truncate(arguments, cfg.security.maxMessageLength);
        String rendered = Format.of(cfg.messages.inGameBroadcast)
                .set("message", content)
                .set("sender", event.senderName)
                .set("sender_id", event.userId)
                .trustValues()
                .render();

        int count = 0;
        ChatRouter router = router();
        if (router != null) {
            count = router.pushToAllServers(PluginMessages.DOWN_BROADCAST, rendered);
        }
        if (cfg.commands.feedback) {
            reply(event, Format.of(cfg.messages.success)
                    .set("count", count)
                    .set("servers", count)
                    .trustValues()
                    .renderPlain() + "（已发送到 " + count + " 个子服）");
        }
    }

    // ==================================================================
    // #send —— 指定子服发送
    // ==================================================================

    private void doSend(OneBotEvent event, String arguments) {
        PluginConfig cfg = this.config;
        if (Strings.isBlank(arguments)) {
            reply(event, Format.of(cfg.messages.usage)
                    .set("usage", CommandsConfig.Builtin.SEND.usage())
                    .renderPlain());
            return;
        }
        String[] parts = arguments.split("\\s+", 2);
        String targetName = parts[0];
        String content = parts.length > 1 ? parts[1].trim() : "";
        if (content.isEmpty()) {
            reply(event, Format.of(cfg.messages.usage)
                    .set("usage", CommandsConfig.Builtin.SEND.usage())
                    .renderPlain());
            return;
        }

        // 支持用显示名或 velocity id 指定，且大小写不敏感
        String serverId = resolveServerId(targetName);
        if (serverId == null) {
            reply(event, Format.of(cfg.messages.serverNotFound)
                    .set("server", targetName)
                    .set("servers", String.join(", ", availableServerNames()))
                    .trustValues()
                    .renderPlain());
            return;
        }

        content = Strings.truncate(content, cfg.security.maxMessageLength);
        String rendered = Format.of(cfg.messages.inGameBroadcast)
                .set("message", content)
                .set("sender", event.senderName)
                .set("sender_id", event.userId)
                .set("server", cfg.servers.displayName(serverId))
                .trustValues()
                .render();

        ChatRouter router = router();
        boolean ok = router != null && router.pushToServer(serverId, PluginMessages.DOWN_BROADCAST, rendered);
        if (cfg.commands.feedback) {
            if (ok) {
                reply(event, Format.of(cfg.messages.success)
                        .set("server", cfg.servers.displayName(serverId))
                        .set("count", servers.playerCount(serverId))
                        .trustValues()
                        .renderPlain() + "（已发送到 " + cfg.servers.displayName(serverId) + "）");
            } else {
                reply(event, "&c发送失败：目标子服没有可用连接（可能没有玩家在线）");
            }
        }
    }

    // ==================================================================
    // #help / #status
    // ==================================================================

    private void doHelp(OneBotEvent event) {
        PluginConfig cfg = this.config;
        Messages messages = cfg.messages;
        String prefix = cfg.commands.prefixes.isEmpty() ? "#" : cfg.commands.prefixes.get(0);

        StringBuilder out = new StringBuilder(Format.of(messages.helpHeader).renderPlain());
        for (CommandsConfig.Builtin builtin : CommandsConfig.Builtin.values()) {
            if (!cfg.commands.isEnabled(builtin)) {
                continue;
            }
            String usage = builtin.usage();
            if (!prefix.isEmpty() && usage.startsWith("#")) {
                // 按配置的前缀替换内置用法里的默认 "#"
                usage = prefix + usage.substring(1);
            }
            out.append('\n').append(Format.of(messages.helpLine)
                    .set("usage", usage)
                    .set("description", builtin.description())
                    .set("command", builtin.id())
                    .trustValues()
                    .renderPlain());
        }
        out.append('\n').append(Format.of(messages.helpFooter)
                .set("prefix", prefix)
                .trustValues()
                .renderPlain());
        reply(event, out.toString());
    }

    private void doStatus(OneBotEvent event) {
        PluginConfig cfg = this.config;
        int online = servers.proxy().getPlayerCount();
        String text = Format.of(cfg.messages.status)
                .set("bot", bridge.selfId() == 0L ? "未知" : String.valueOf(bridge.selfId()))
                .set("connection", bridge.connectionDescription())
                .set("servers", cfg.servers.enabledServers().size())
                .set("players", online)
                .set("uptime", Strings.formatDuration(bridge.uptimeMillis()))
                .trustValues()
                .renderPlain();
        StringBuilder out = new StringBuilder(text);
        out.append('\n').append("&7收发统计：收 &f").append(bridge.receivedEvents())
                .append(" &7发 &f").append(bridge.sentMessages())
                .append(" &7队列 &f").append(bridge.queueSize())
                .append(" &7指令 &f").append(handledCommands.get());
        reply(event, out.toString());
    }

    // ==================================================================
    // 工具
    // ==================================================================

    /**
     * 把结果回发到来源群（若来源是私聊则回私聊）。
     *
     * <p>私聊场景很常见：服主不想在群里刷屏，直接私聊机器人 {@code #list} 查状态。
     */
    private void reply(OneBotEvent event, String text) {
        if (!this.config.commands.feedback || Strings.isBlank(text)) {
            return;
        }
        if (event.isPrivateMessage()) {
            bridge.sendPrivate(event.userId, text);
            return;
        }
        if (!checkGroupRate(event.groupId)) {
            return;
        }
        bridge.sendGroup(event.groupId, text);
    }

    /** 解析子服：先按显示名，再按 velocity id，均大小写不敏感。 */
    public String resolveServerId(String name) {
        PluginConfig cfg = this.config;
        if (Strings.isBlank(name)) {
            return null;
        }
        String target = name.trim();
        String lower = target.toLowerCase(java.util.Locale.ROOT);
        for (ServerEntry entry : cfg.servers.sorted()) {
            if (entry.id.toLowerCase(java.util.Locale.ROOT).equals(lower)
                    || Strings.safe(entry.displayName).toLowerCase(java.util.Locale.ROOT).equals(lower)) {
                return servers.exists(entry.id) ? entry.id : null;
            }
        }
        for (String serverId : servers.registeredIds()) {
            if (serverId.toLowerCase(java.util.Locale.ROOT).equals(lower)) {
                return serverId;
            }
        }
        return null;
    }

    private List<String> availableServerNames() {
        PluginConfig cfg = this.config;
        List<String> names = new ArrayList<>();
        for (ServerEntry entry : cfg.servers.enabledServers()) {
            names.add(entry.displayName + "(" + entry.id + ")");
        }
        for (String serverId : servers.registeredIds()) {
            if (cfg.servers.get(serverId) == null) {
                names.add(serverId);
            }
        }
        return names;
    }

    /** 用户级限流。 */
    private boolean checkUserRate(long userId) {
        int limit = this.config.security.userRateLimit;
        if (limit <= 0) {
            return true;
        }
        long minute = System.currentTimeMillis() / 60_000L;
        RateWindow window = userRate.computeIfAbsent(userId, key -> new RateWindow());
        synchronized (window) {
            if (window.minute != minute) {
                window.minute = minute;
                window.count = 0;
            }
            window.count++;
            return window.count <= limit;
        }
    }

    /** 群级限流（保护机器人不因回执过频被风控）。 */
    private boolean checkGroupRate(long groupId) {
        int limit = this.config.security.groupRateLimit;
        if (limit <= 0) {
            return true;
        }
        long minute = System.currentTimeMillis() / 60_000L;
        RateWindow window = groupRate.computeIfAbsent(groupId, key -> new RateWindow());
        synchronized (window) {
            if (window.minute != minute) {
                window.minute = minute;
                window.count = 0;
            }
            window.count++;
            return window.count <= limit;
        }
    }

    /** 清理长时间未活跃的限流窗口，避免 Map 无限增长。 */
    public void evictRateWindows() {
        long minute = System.currentTimeMillis() / 60_000L;
        userRate.entrySet().removeIf(entry -> minute - entry.getValue().minute > 10);
        groupRate.entrySet().removeIf(entry -> minute - entry.getValue().minute > 10);
    }

    /** 可变限流窗口。 */
    private static final class RateWindow {
        long minute = -1L;
        int count = 0;
    }
}
