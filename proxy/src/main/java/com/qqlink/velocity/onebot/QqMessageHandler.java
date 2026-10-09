package com.qqlink.velocity.onebot;

import java.util.logging.Logger;

import com.qqlink.velocity.config.model.PluginConfig;
import com.qqlink.velocity.mc.ChatRouter;
import com.qqlink.velocity.mc.CommandManager;
import com.qqlink.velocity.util.Strings;

/**
 * QQ 消息入口：把 OneBot 事件翻译成"聊天转发"或"群指令"。
 *
 * <h2>处理顺序（顺序很重要）</h2>
 * <ol>
 *   <li><strong>丢弃机器人自己的消息</strong> —— {@code user_id == self_id}。
 *       这是防环的第一道闸：某些机器人实现会把机器人自己发的消息也上报一次，
 *       不拦就会 "MC 消息 → QQ → 又被当群消息 → 回 MC" 无限循环；</li>
 *   <li><strong>群允许性校验</strong> —— {@code groups.strict: true} 时，
 *       未在配置里列出的群直接被忽略（不是"发到所有群"，而是完全不理）；</li>
 *   <li><strong>指令优先于聊天</strong> —— 先尝试 {@link CommandManager#handle}，
 *       命中（返回 true）则不再作为聊天转发，避免 "＃list" 被刷到游戏里；</li>
 *   <li><strong>聊天转发</strong> —— {@link ChatRouter#onGroupChat}；</li>
 *   <li><strong>私聊兜底</strong> —— 管理员私聊机器人执行指令也可用
 *       （很多服主习惯私聊机器人查状态）。</li>
 * </ol>
 *
 * <h2>@机器人 的处理</h2>
 * 提取文本时把 "@机器人" 替换为配置的占位文本（默认空串），
 * 这样群里常见写法 "生存服 你好"、"@Bot ＃list" 都能正确解析，
 * 而不会把 "@Bot" 这几个字转发到游戏里。
 */
public final class QqMessageHandler {

    private final Logger logger;
    private volatile PluginConfig config;
    /** 间接持有路由器：/qqlink reload 重建路由器后无需重新订阅本处理器。 */
    private final com.qqlink.velocity.util.Holder<ChatRouter> routerHolder;
    private final CommandManager commands;

    /** @机器人 时替换成的文本；留空表示直接删除。 */
    private static final String AT_PLACEHOLDER = "";
    /** 图片 / 语音等非文本段的替换文本。 */
    private static final String CQ_PLACEHOLDER = "";

    public QqMessageHandler(PluginConfig config, com.qqlink.velocity.util.Holder<ChatRouter> routerHolder,
                            CommandManager commands, Logger logger) {
        this.config = config;
        this.routerHolder = routerHolder;
        this.commands = commands;
        this.logger = logger;
    }

    /** 当前路由器实例。 */
    private ChatRouter router() {
        return routerHolder.get();
    }

    public void updateConfig(PluginConfig config) {
        this.config = config;
    }

    /** 订阅到 {@link OneBotBridge}。 */
    public void register(OneBotBridge bridge) {
        bridge.subscribe(this::onEvent);
    }

    private void onEvent(OneBotEvent event) {
        try {
            if (event.isGroupMessage()) {
                handleGroupMessage(event);
            } else if (event.isPrivateMessage()) {
                handlePrivateMessage(event);
            }
            // notice / request 事件本插件暂不处理，直接忽略
        } catch (Throwable throwable) {
            logger.warning("[QQ] 处理事件失败（" + event + "）：" + throwable);
        }
    }

    // ------------------------------------------------------------------
    // 群消息
    // ------------------------------------------------------------------

    private void handleGroupMessage(OneBotEvent event) {
        PluginConfig cfg = this.config;
        long selfId = resolveSelfId(event);

        // ① 丢弃机器人自己的消息（防回环）
        if (selfId != 0L && event.userId == selfId) {
            return;
        }

        // ② 群允许性校验
        if (!cfg.groups.allowed(event.groupId)) {
            logger.fine("[QQ] 群 " + event.groupId + " 未在配置中列出且 groups.strict=true，已忽略");
            return;
        }

        // 提取正文：@机器人 → 占位符；图片等 → 占位符
        String text = event.text(selfId, AT_PLACEHOLDER, CQ_PLACEHOLDER);
        boolean mentioned = event.mentionsBot(selfId);
        if (Strings.isBlank(text) && !mentioned) {
            return;
        }

        // ③ 指令优先
        if (commands.handle(event, text, mentioned)) {
            return;
        }

        // ④ 普通聊天转发
        String normalized = normalize(text, cfg);
        if (normalized.isEmpty()) {
            return;
        }
        if (cfg.security.isIgnored(normalized)) {
            return;
        }
        int targets = 0;
        ChatRouter router = router();
        if (router != null) {
            targets = router.onGroupChat(event, normalized);
        }
        if (targets == 0 && cfg.debug) {
            logger.info("[QQ] 群 " + event.groupId + " 的消息没有匹配到任何子服，已忽略："
                    + Strings.truncate(normalized, 60));
        }
    }

    // ------------------------------------------------------------------
    // 私聊消息（仅管理员可用指令）
    // ------------------------------------------------------------------

    private void handlePrivateMessage(OneBotEvent event) {
        PluginConfig cfg = this.config;
        if (!cfg.commands.enabled || !cfg.security.whitelistEnabled) {
            return;
        }
        if (!cfg.security.isAdmin(event.userId)) {
            // 非管理员私聊一律不回应，避免被当作"查询机器人"的探测入口
            return;
        }
        long selfId = resolveSelfId(event);
        String text = event.text(selfId, AT_PLACEHOLDER, CQ_PLACEHOLDER);
        if (Strings.isBlank(text)) {
            return;
        }
        // 私聊场景下 CommandManager 会把回执私聊回发送者（reply() 内部判断 isPrivateMessage）
        commands.handle(event, text, true);
    }

    // ------------------------------------------------------------------
    // 工具
    // ------------------------------------------------------------------

    /** 机器人自身 QQ 号：优先用事件里的 self_id，其次用配置。 */
    private long resolveSelfId(OneBotEvent event) {
        if (event.selfId != 0L) {
            return event.selfId;
        }
        return config.onebot.selfId;
    }

    /** 统一清洗：去 CQ 残留、折叠空白、长度截断。 */
    private String normalize(String raw, PluginConfig cfg) {
        String message = cfg.security.stripCqCodes
                ? Strings.normalizeQqMessage(raw, true)
                : Strings.safe(raw).trim();
        if (message.length() > cfg.security.maxMessageLength) {
            message = Strings.truncate(message, cfg.security.maxMessageLength);
        }
        return message;
    }
}
