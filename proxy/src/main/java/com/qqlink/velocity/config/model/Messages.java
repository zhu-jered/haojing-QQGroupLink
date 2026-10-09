package com.qqlink.velocity.config.model;

import java.util.LinkedHashMap;
import java.util.Map;

import com.qqlink.velocity.config.Nodes;

/**
 * 全部消息格式模板。
 *
 * <h2>占位符总表</h2>
 * <table border="1">
 *   <tr><th>占位符</th><th>含义</th><th>可用模板</th></tr>
 *   <tr><td>%server%</td><td>子服显示名（如 生存服）</td><td>全部</td></tr>
 *   <tr><td>%server_id%</td><td>Velocity 子服 id（如 survival）</td><td>全部</td></tr>
 *   <tr><td>%player%</td><td>玩家名</td><td>MC 相关</td></tr>
 *   <tr><td>%message%</td><td>消息正文</td><td>聊天相关</td></tr>
 *   <tr><td>%sender%</td><td>QQ 昵称</td><td>QQ → MC</td></tr>
 *   <tr><td>%sender_id%</td><td>QQ 号</td><td>QQ → MC</td></tr>
 *   <tr><td>%group%</td><td>群名（未配置则群号）</td><td>QQ 相关</td></tr>
 *   <tr><td>%group_id%</td><td>群号</td><td>QQ 相关</td></tr>
 *   <tr><td>%cause%</td><td>死亡原因</td><td>玩家死亡</td></tr>
 *   <tr><td>%advancement% / %title%</td><td>成就名称（两个占位符等价）</td><td>成就</td></tr>
 *   <tr><td>%frame%</td><td>成就类型（task/goal/challenge/recipe）</td><td>成就</td></tr>
 *   <tr><td>%count%</td><td>数量 / 人数 / 服务器数 / 在线数</td><td>列表、状态、服务器状态</td></tr>
 *   <tr><td>%players%</td><td>玩家列表</td><td>#list</td></tr>
 *   <tr><td>%tps% / %mspt%</td><td>TPS / MSPT</td><td>#tps</td></tr>
 *   <tr><td>%uptime%</td><td>代理运行时长</td><td>状态类</td></tr>
 *   <tr><td>%from% / %from_id% / %to% / %to_id%</td><td>切换前后子服</td><td>切换通知</td></tr>
 *   <tr><td>%usage% / %description% / %command% / %prefix%</td><td>指令元信息</td><td>#help</td></tr>
 *   <tr><td>%bot% / %connection% / %servers%</td><td>机器人 QQ / 连接状态 / 子服数</td><td>#status</td></tr>
 * </table>
 */
public final class Messages {

    // ---------------- 聊天 ----------------

    /** MC 公屏聊天 → QQ 群 */
    public String chatToQq = "&7[&b%server%&7] &f%player%&7: &f%message%";
    /** QQ 群消息 → MC 公屏（发送到子服玩家看到的样式） */
    public String chatToMc = "&7[&9QQ&7] &f%sender%&7: &f%message%";
    /** QQ 群消息记录到控制台的样式（可留空关闭） */
    public String chatToConsole = "[QQ][%group%] %sender%(%sender_id%): %message%";

    // ---------------- 玩家事件 ----------------

    /** 玩家加入 */
    public String join = "&a+ &f%player% &7加入了 &b%server%";
    /** 玩家退出 */
    public String quit = "&c- &f%player% &7离开了 &b%server%";
    /** 玩家切换子服 */
    public String serverSwitch = "&e→ &f%player% &7从 &b%from% &7切换到了 &b%to%";
    /** 玩家死亡 */
    public String death = "&8[&c死亡&8] &f%player% &7在 &b%server% &7%cause%";
    /** 玩家解锁成就 */
    public String advancement = "&6[成就] &f%player% &7达成了 &e%advancement%&7（%frame%）";

    // ---------------- 服务器状态 ----------------

    /** 子服启动（代理检测到可连接） */
    public String serverStart = "&a[服务器] &b%server% &a已启动，当前在线 %count% 人";
    /** 子服关闭 */
    public String serverStop = "&c[服务器] &b%server% &c已关闭";
    /** 管理员公告 */
    public String broadcast = "&6[公告] &f%message%";

    // ---------------- 指令回执 ----------------

    /** #list 单行样式（compact） */
    public String listLine = "&7- &b%server% &7(&f%count%&7人): &f%players%";
    /** #list 头部 */
    public String listHeader = "&e===== 子服在线情况（共 %count% 人在线）=====";
    /** #list 空服务器占位 */
    public String listEmpty = "&7无人在线";
    /** #tps 单服样式 */
    public String tpsLine = "&7- &b%server% &7TPS: %tps% &7MSPT: %mspt% &7在线: %count%";
    /** #tps 头部 */
    public String tpsHeader = "&e===== 子服性能（共 %count% 个服）=====";
    /** #tps 无数据（未装伴随模组）提示 */
    public String tpsUnavailable = "&7- &b%server% &7暂无性能数据（未安装 qqlink-fabric 模组）";
    /** #help 头部 */
    public String helpHeader = "&e===== 可用指令 =====";
    /** #help 单条指令样式 */
    public String helpLine = "&b%usage% &7- %description%";
    /** #help 结尾 */
    public String helpFooter = "&7提示：把消息发给机器人所在群即可执行，例：%prefix%list";
    /** #status 样式 */
    public String status = "&e机器人：%bot% &7| &e连接：%connection% &7| &e子服：%servers% &7| &e在线：%players% &7| &e运行：%uptime%";

    // ---------------- 权限 / 错误提示 ----------------

    /** 权限不足 */
    public String noPermission = "&c你没有权限执行该指令";
    /** 未知指令 */
    public String unknownCommand = "&c未知指令：%command%（输入 %prefix%help 查看帮助）";
    /** 参数不足 */
    public String usage = "&c参数不足，正确用法：%usage%";
    /** 找不到目标子服 */
    public String serverNotFound = "&c找不到子服：%server%（可用：%servers%）";
    /** 操作成功回执 */
    public String success = "&a操作成功";
    /** 触发限流 */
    public String rateLimited = "&c操作过于频繁，请稍后再试";
    /** 消息被过滤 */
    public String filtered = "&7消息包含违规内容或格式不正确，已忽略";

    // ---------------- 游戏内回执（发给 MC 玩家） ----------------

    /** 游戏内看到 QQ 指令回执 */
    public String inGameQqCommand = "&7[&9QQ&7] &f%sender% &7执行了 &f%command%";
    /** 游戏内看到 QQ 全服公告 */
    public String inGameBroadcast = "&6[公告] &f%message%";

    public static Messages from(Map<String, Object> root) {
        Messages messages = new Messages();
        Map<String, Object> chat = Nodes.section(root, "format", "chat");
        Map<String, Object> event = Nodes.section(root, "format", "event");
        Map<String, Object> server = Nodes.section(root, "format", "server");
        Map<String, Object> command = Nodes.section(root, "format", "command");
        Map<String, Object> error = Nodes.section(root, "format", "error");
        Map<String, Object> inGame = Nodes.section(root, "format", "in-game");

        messages.chatToQq = Nodes.str(chat, messages.chatToQq, "mc-to-qq");
        messages.chatToMc = Nodes.str(chat, messages.chatToMc, "qq-to-mc");
        messages.chatToConsole = Nodes.str(chat, messages.chatToConsole, "qq-to-console");

        messages.join = Nodes.str(event, messages.join, "join");
        messages.quit = Nodes.str(event, messages.quit, "quit");
        messages.serverSwitch = Nodes.str(event, messages.serverSwitch, "server-switch");
        messages.death = Nodes.str(event, messages.death, "death");
        messages.advancement = Nodes.str(event, messages.advancement, "advancement");

        messages.serverStart = Nodes.str(server, messages.serverStart, "start");
        messages.serverStop = Nodes.str(server, messages.serverStop, "stop");
        messages.broadcast = Nodes.str(server, messages.broadcast, "broadcast");

        messages.listLine = Nodes.str(command, messages.listLine, "list-line");
        messages.listHeader = Nodes.str(command, messages.listHeader, "list-header");
        messages.listEmpty = Nodes.str(command, messages.listEmpty, "list-empty");
        messages.tpsLine = Nodes.str(command, messages.tpsLine, "tps-line");
        messages.tpsHeader = Nodes.str(command, messages.tpsHeader, "tps-header");
        messages.tpsUnavailable = Nodes.str(command, messages.tpsUnavailable, "tps-unavailable");
        messages.helpHeader = Nodes.str(command, messages.helpHeader, "help-header");
        messages.helpLine = Nodes.str(command, messages.helpLine, "help-line");
        messages.helpFooter = Nodes.str(command, messages.helpFooter, "help-footer");
        messages.status = Nodes.str(command, messages.status, "status");

        messages.noPermission = Nodes.str(error, messages.noPermission, "no-permission");
        messages.unknownCommand = Nodes.str(error, messages.unknownCommand, "unknown-command");
        messages.usage = Nodes.str(error, messages.usage, "usage");
        messages.serverNotFound = Nodes.str(error, messages.serverNotFound, "server-not-found");
        messages.success = Nodes.str(error, messages.success, "success");
        messages.rateLimited = Nodes.str(error, messages.rateLimited, "rate-limited");
        messages.filtered = Nodes.str(error, messages.filtered, "filtered");

        messages.inGameQqCommand = Nodes.str(inGame, messages.inGameQqCommand, "qq-command");
        messages.inGameBroadcast = Nodes.str(inGame, messages.inGameBroadcast, "qq-broadcast");
        return messages;
    }

    /** 写回 YAML（用于首次生成 / 结构同步）。 */
    public Map<String, Object> toMap() {
        Map<String, Object> format = new LinkedHashMap<>();

        Map<String, Object> chat = new LinkedHashMap<>();
        chat.put("mc-to-qq", chatToQq);
        chat.put("qq-to-mc", chatToMc);
        chat.put("qq-to-console", chatToConsole);
        format.put("chat", chat);

        Map<String, Object> event = new LinkedHashMap<>();
        event.put("join", join);
        event.put("quit", quit);
        event.put("server-switch", serverSwitch);
        event.put("death", death);
        event.put("advancement", advancement);
        format.put("event", event);

        Map<String, Object> server = new LinkedHashMap<>();
        server.put("start", serverStart);
        server.put("stop", serverStop);
        server.put("broadcast", broadcast);
        format.put("server", server);

        Map<String, Object> command = new LinkedHashMap<>();
        command.put("list-header", listHeader);
        command.put("list-line", listLine);
        command.put("list-empty", listEmpty);
        command.put("tps-header", tpsHeader);
        command.put("tps-line", tpsLine);
        command.put("tps-unavailable", tpsUnavailable);
        command.put("help-header", helpHeader);
        command.put("help-line", helpLine);
        command.put("help-footer", helpFooter);
        command.put("status", status);
        format.put("command", command);

        Map<String, Object> error = new LinkedHashMap<>();
        error.put("no-permission", noPermission);
        error.put("unknown-command", unknownCommand);
        error.put("usage", usage);
        error.put("server-not-found", serverNotFound);
        error.put("success", success);
        error.put("rate-limited", rateLimited);
        error.put("filtered", filtered);
        format.put("error", error);

        Map<String, Object> inGame = new LinkedHashMap<>();
        inGame.put("qq-command", inGameQqCommand);
        inGame.put("qq-broadcast", inGameBroadcast);
        format.put("in-game", inGame);

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("format", format);
        return result;
    }
}
