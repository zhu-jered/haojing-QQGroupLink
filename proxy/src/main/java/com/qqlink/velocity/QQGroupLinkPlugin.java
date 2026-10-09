package com.qqlink.velocity;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.TimeUnit;

import com.google.inject.Inject;
import com.velocitypowered.api.command.CommandMeta;
import com.velocitypowered.api.event.Subscribe;
import com.velocitypowered.api.event.proxy.ProxyInitializeEvent;
import com.velocitypowered.api.event.proxy.ProxyShutdownEvent;
import com.velocitypowered.api.plugin.Plugin;
import com.velocitypowered.api.plugin.annotation.DataDirectory;
import com.velocitypowered.api.proxy.ProxyServer;

import com.qqlink.velocity.command.QqLinkCommand;
import com.qqlink.velocity.config.ConfigFile;
import com.qqlink.velocity.config.model.PluginConfig;
import com.qqlink.velocity.config.model.ServerEntry;
import com.qqlink.velocity.mc.ChatRouter;
import com.qqlink.velocity.mc.CommandManager;
import com.qqlink.velocity.mc.PlayerEventListener;
import com.qqlink.velocity.mc.PlayerManager;
import com.qqlink.velocity.mc.PluginMessageRouter;
import com.qqlink.velocity.mc.PluginMessages;
import com.qqlink.velocity.mc.ServerBridge;
import com.qqlink.velocity.onebot.OneBotBridge;
import com.qqlink.velocity.onebot.QqMessageHandler;
import com.qqlink.velocity.util.Strings;

/**
 * QQGroupLink 主类。
 *
 * <h2>启动顺序（顺序不可随意调整）</h2>
 * <pre>
 *  ① 释放/读取 config.yml        —— 没有配置什么都做不了
 *  ② 构建各业务组件（此时不注册任何监听）—— 保证组件引用齐全
 *  ③ 注册插件消息频道            —— 必须先注册，否则子服上报收不到
 *  ④ 注册事件监听与游戏内指令
 *  ⑤ 启动 OneBot 连接（放最后）  —— 连接一起来就可能立刻收到 QQ 消息，
 *                                  此时所有下游组件必须已经就绪
 *  ⑥ 注册定时任务（指标清理、限流窗口回收）
 * </pre>
 *
 * <h2>线程与性能</h2>
 * <ul>
 *   <li>Velocity 事件处理器（本类的 {@code @Subscribe} 方法、监听器类）运行在代理事件线程上，
 *       只做"取值 + 入队 + 渲染"，<strong>没有任何阻塞 IO</strong>；</li>
 *   <li>OneBot 的网络读写各自独立线程；业务分发在固定线程池；
 *       发送队列由单独线程消费并按令牌桶限流；</li>
 *   <li>定时任务全部注册为 <strong>async</strong>，不占用代理 tick。</li>
 * </ul>
 */
@Plugin(
        id = "qqlink",
        name = "QQGroupLink",
        version = "1.0.0",
        description = "QQ 群与 Velocity 多子服双向互通（OneBot v11，正向/反向 WebSocket）",
        authors = {"QQGroupLink"}
)
public final class QQGroupLinkPlugin {

    private final ProxyServer proxy;
    private final org.slf4j.Logger slf4j;
    private final java.util.logging.Logger logger;
    private final Path dataDirectory;

    private ConfigFile configFile;
    private PluginConfig config;

    private OneBotBridge bridge;
    private PlayerManager players;
    private ServerBridge servers;
    /**
     * 路由器用 {@link Holder} 包装：{@code /qqlink reload} 会重建 ChatRouter
     * （因为它持有 OneBotBridge 引用），而监听器必须在重载后立刻用上新实例。
     */
    private final com.qqlink.velocity.util.Holder<ChatRouter> routerHolder =
            new com.qqlink.velocity.util.Holder<>();
    private ChatRouter router;
    private CommandManager commands;
    private QqMessageHandler qqHandler;
    private PlayerEventListener playerListener;
    private PluginMessageRouter messageRouter;

    /** 记录已经推送过"下线"通知的子服，避免重复。 */
    private final Set<String> offlineNotified = new LinkedHashSet<>();

    @Inject
    public QQGroupLinkPlugin(ProxyServer proxy, org.slf4j.Logger slf4j, @DataDirectory Path dataDirectory) {
        this.proxy = proxy;
        this.slf4j = slf4j;
        this.dataDirectory = dataDirectory;
        // 统一使用 java.util.logging，便于在组件里零依赖使用；同时转发给 Velocity 的 SLF4J 输出
        this.logger = new Slf4jLoggerAdapter(slf4j);
    }

    // ==================================================================
    // 生命周期
    // ==================================================================

    @Subscribe
    public void onProxyInitialize(ProxyInitializeEvent event) {
        try {
            // ① 配置
            configFile = new ConfigFile(dataDirectory, logger);
            boolean firstRun = configFile.saveDefault();
            configFile.load();
            this.config = PluginConfig.load(configFile.root());
            List<String> added = configFile.syncMissingKeys();
            if (firstRun) {
                logger.info("首次运行：已生成默认配置 " + configFile.path()
                        + "，请填写 QQ 群号与管理员白名单后执行 /qqlink reload");
            } else if (!added.isEmpty()) {
                logger.info("检测到 " + added.size() + " 个新增配置项，已自动补入 config.yml："
                        + Strings.truncate(String.join(", ", added), 200));
            }
            for (String warning : configFile.warnings()) {
                logger.warning(warning);
            }

            if (!config.enabled) {
                logger.warning("配置 enabled=false，插件不会注册任何功能。修改后执行 /qqlink reload 生效。");
                return;
            }

            // ② 业务组件
            this.players = new PlayerManager();
            this.servers = new ServerBridge(proxy, logger);
            this.bridge = new OneBotBridge(config.onebot, logger);
            this.router = new ChatRouter(config, bridge, servers, logger);
            this.routerHolder.set(this.router);
            this.commands = new CommandManager(config, bridge, routerHolder, servers, players, logger);
            this.qqHandler = new QqMessageHandler(config, routerHolder, commands, logger);
            this.playerListener = new PlayerEventListener(servers, routerHolder, players, logger);
            this.messageRouter = new PluginMessageRouter(routerHolder, players, servers, logger);

            // ③ 插件消息频道（必须先注册）
            messageRouter.register(proxy);

            // ④ 事件监听
            proxy.getEventManager().register(this, this);
            proxy.getEventManager().register(this, playerListener);
            proxy.getEventManager().register(this, messageRouter);
            registerInGameCommand();

            // ⑤ OneBot：QQ 事件订阅 + 连接启动
            qqHandler.register(bridge);
            bridge.start();

            // ⑥ 定时任务
            scheduleTasks();

            logStartupSummary();
        } catch (Throwable throwable) {
            logger.severe("插件初始化失败：" + throwable);
            slf4j.error("QQGroupLink 初始化失败", throwable);
        }
    }

    @Subscribe
    public void onProxyShutdown(ProxyShutdownEvent event) {
        logger.info("代理正在关闭，通知 QQ 群并停止 OneBot 连接……");
        try {
            if (bridge != null && bridge.isConnected()) {
                for (Long groupId : config.groups.ids()) {
                    bridge.sendGroup(groupId, "&c[系统] 代理服务器正在关闭，互通已断开");
                }
                // 给发送线程最多 3 秒把"关闭通知"发出去
                bridge.flush(3000L);
            }
        } catch (Throwable throwable) {
            logger.fine("关闭通知发送失败：" + throwable.getMessage());
        }
        shutdown();
    }

    /** 释放全部资源（重载时也会调用）。 */
    private void shutdown() {
        if (bridge != null) {
            bridge.stop();
            bridge = null;
        }
        if (playerListener != null) {
            playerListener.clear();
        }
        offlineNotified.clear();
    }

    // ==================================================================
    // 定时任务
    // ==================================================================

    private void scheduleTasks() {
        // ① 每 60 秒：清理过期指标 + 回收限流窗口 + 检测"无人且长时间无上报"的子服下线
        proxy.getScheduler().buildTask(this, this::housekeeping)
                .repeat(60L, TimeUnit.SECONDS)
                .delay(60L, TimeUnit.SECONDS)
                .schedule();

        // ② 每 30 秒：向子服主动索取一次指标，兜底"模组上报丢失"的情况
        proxy.getScheduler().buildTask(this, () -> {
            String payload = PluginMessages.DOWN_REQUEST_METRICS;
            for (ServerEntry entry : config.servers.enabledServers()) {
                if (servers.playerCount(entry.id) > 0) {
                    servers.sendToServer(entry.id,
                            payload,
                            PluginMessages.CHANNEL_NAME);
                }
            }
        }).repeat(30L, TimeUnit.SECONDS).delay(15L, TimeUnit.SECONDS).schedule();

        // ③ 每 5 分钟：把配置里未登记的、但 velocity.toml 中存在的子服提示给服主
        proxy.getScheduler().buildTask(this, this::warnUnconfiguredServers)
                .repeat(5L, TimeUnit.MINUTES)
                .delay(2L, TimeUnit.MINUTES)
                .schedule();
    }

    /**
     * 周期性维护。
     *
     * <p>这里实现了一个"无模组也能感知子服掉线"的启发式规则：
     * 某子服曾经有人，现在代理侧统计为 0 人，且连续 3 个周期（约 3 分钟）都没有收到
     * 该子服的性能上报 —— 就判定为已关闭并推送通知。
     * 装了模组的场景下会先收到 {@code SERVER_STOP} 上报，不会走到这里。
     */
    private void housekeeping() {
        if (config == null || !config.enabled) {
            return;
        }
        players.evictStale();
        commands.evictRateWindows();

        for (ServerEntry entry : config.servers.enabledServers()) {
            int count = servers.playerCount(entry.id);
            if (count > 0) {
                players.setLastKnownCount(entry.id, count);
                offlineNotified.remove(entry.id);
                continue;
            }
            if (players.hasMetrics(entry.id)) {
                // 还有新鲜指标，说明子服活着（只是没人）
                offlineNotified.remove(entry.id);
                continue;
            }
            if (offlineNotified.contains(entry.id)) {
                continue;
            }
            // 从未见过这个子服有活动，也没有模组上报：不误报，静默跳过
            if (players.metricTime(entry.id) == 0L && players.lastKnownCount(entry.id) == 0) {
                continue;
            }
            offlineNotified.add(entry.id);
            logger.info("[MC] 子服 " + entry.id + " 已无玩家且长时间无指标上报，判定为已关闭");
            router.onServerStop(entry.id);
            players.markOffline(entry.id);
        }
    }

    /** 提示服主把 velocity.toml 里的子服补进插件配置。 */
    private void warnUnconfiguredServers() {
        if (config == null) {
            return;
        }
        List<String> missing = new ArrayList<>();
        for (String serverId : servers.registeredIds()) {
            if (config.servers.get(serverId) == null) {
                missing.add(serverId);
            }
        }
        if (!missing.isEmpty()) {
            logger.info("[MC] 以下子服已在 velocity.toml 注册但未在插件 config.yml 中配置显示名，"
                    + "将直接使用服务器 id 作为显示名：" + String.join(", ", missing));
        }
    }

    // ==================================================================
    // 游戏内指令
    // ==================================================================

    private void registerInGameCommand() {
        com.velocitypowered.api.command.CommandManager manager = proxy.getCommandManager();
        CommandMeta meta = manager.metaBuilder("qqlink")
                .aliases("qq", "群服互联")
                .plugin(this)
                .build();
        manager.register(meta, new QqLinkCommand(this));
        logger.info("[指令] 已注册游戏内指令 /qqlink（别名 /qq）");
    }

    // ==================================================================
    // 热重载
    // ==================================================================

    /**
     * 热重载配置。
     *
     * <p>{@code /qqlink reload} 会：
     * <ol>
     *   <li>重新读盘并补全新增配置项；</li>
     *   <li>把新的 {@link PluginConfig} 分发给所有持有配置引用的组件
     *       （它们内部是 {@code volatile} 字段，替换引用是原子的）；</li>
     *   <li><strong>重建</strong> OneBot 连接与 {@link ChatRouter}
     *       —— 因为连接模式/端口/token 属于"启动期参数"，就地修改比重新连接更容易出错。
     *       重建期间 QQ 互通会短暂中断（通常 &lt; 1 秒），这是刻意的取舍：可靠性优先。</li>
     * </ol>
     *
     * <p><strong>引用更新策略</strong>：监听器（{@code PlayerEventListener} /
     * {@code PluginMessageRouter}）与 {@link CommandManager} 都通过
     * {@link com.qqlink.velocity.util.Holder} 间接持有路由器，
     * 因此这里只需把新实例放进 Holder，无需重新注册任何事件监听，
     * 从根本上避免"重载后仍走旧连接"的隐蔽 Bug。
     *
     * @return 重载结果描述（用于回显给执行者）
     */
    public List<String> reload() {
        List<String> result = new ArrayList<>();
        try {
            configFile.reload();
            PluginConfig fresh = PluginConfig.load(configFile.root());
            List<String> added = configFile.syncMissingKeys();
            this.config = fresh;

            // 先停掉旧连接，再重建（顺序不能反：否则新连接会和旧监听抢端口）
            if (bridge != null) {
                bridge.stop();
            }

            if (!fresh.enabled) {
                bridge = null;
                routerHolder.set(null);
                router = null;
                result.add("配置 enabled=false，已停止全部互通功能");
            } else {
                // 顺序很重要：先建连接、再建路由器（路由器需要 bridge 引用来发消息）
                bridge = new OneBotBridge(fresh.onebot, logger);
                ChatRouter newRouter = new ChatRouter(fresh, bridge, servers, logger);
                this.router = newRouter;
                // 关键：所有下游立刻看到新路由器（它们通过 Holder 间接持有）
                routerHolder.set(newRouter);
                commands.updateConfig(fresh);
                qqHandler.updateConfig(fresh);
                bridge.start();
                result.add("OneBot 连接已按新配置重建（模式：" + fresh.onebot.mode + "）");
            }

            if (!added.isEmpty()) {
                result.add("自动补入 " + added.size() + " 个新增配置项");
            }
            result.add("群数量：" + fresh.groups.all().size()
                    + "，子服数量：" + fresh.servers.all().size()
                    + "，管理员：" + fresh.security.adminWhitelist.size());
            logger.info("配置热重载完成：" + String.join("；", result));
        } catch (Exception exception) {
            logger.severe("配置重载失败：" + exception.getMessage());
            result.add("重载失败：" + exception.getMessage());
        }
        return result;
    }

    // ==================================================================
    // 启动摘要
    // ==================================================================

    private void logStartupSummary() {
        logger.info("==================== QQGroupLink 已启动 ====================");
        logger.info("连接配置：" + config.onebot);
        logger.info("子服映射：" + describeServers());
        logger.info("QQ 群：" + describeGroups());
        logger.info("管理员：" + (config.security.adminWhitelist.isEmpty()
                ? "未配置（QQ 指令将无人可用，请在 security.admin-whitelist 中填写）"
                : config.security.adminWhitelist.toString()));
        logger.info("事件开关：加入=" + config.events.join + " 退出=" + config.events.quit
                + " 死亡=" + config.events.death + " 成就=" + config.events.advancement
                + " 服务器状态=" + config.events.serverStatus
                + " 公告=" + config.events.adminBroadcast);
        logger.info("指令前缀：" + config.commands.prefixes);
        for (String warning : config.onebot.warnings()) {
            logger.warning(warning);
        }
        logger.info("==========================================================");
    }

    private String describeServers() {
        if (config.servers.isEmpty()) {
            List<String> ids = servers.registeredIds();
            if (ids.isEmpty()) {
                return "未配置任何子服（velocity.toml 里也没有 [servers]）";
            }
            return "配置为空，将按 velocity.toml 自动识别：" + String.join(", ", ids);
        }
        Map<String, ServerEntry> all = config.servers.all();
        List<String> parts = new ArrayList<>();
        for (ServerEntry entry : config.servers.sorted()) {
            parts.add(entry.id + "→" + entry.displayName
                    + (entry.enabled ? "" : "(已禁用)")
                    + (entry.primary ? "[主服]" : ""));
        }
        return String.join("，", parts) + "（共 " + all.size() + " 个）";
    }

    private String describeGroups() {
        if (!config.groups.hasAny()) {
            return "未配置任何群（请在 groups 段添加群号，否则消息无处可发）";
        }
        List<String> parts = new ArrayList<>();
        config.groups.all().forEach((id, group) -> parts.add(group.display()));
        return String.join("，", parts);
    }

    // ==================================================================
    // 供指令 / 其他组件访问的 getter
    // ==================================================================

    public ProxyServer proxy() {
        return proxy;
    }

    public java.util.logging.Logger logger() {
        return logger;
    }

    public PluginConfig config() {
        return config;
    }

    public ConfigFile configFile() {
        return configFile;
    }

    public OneBotBridge bridge() {
        return bridge;
    }

    public PlayerManager players() {
        return players;
    }

    public ServerBridge servers() {
        return servers;
    }

    public ChatRouter router() {
        return router;
    }

    public CommandManager commands() {
        return commands;
    }

    public PluginMessageRouter messageRouter() {
        return messageRouter;
    }

    /** 是否已经初始化完成（供指令判断）。 */
    public boolean ready() {
        return bridge != null && router != null;
    }
}
