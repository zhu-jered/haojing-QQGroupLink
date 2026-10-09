package com.qqlink.velocity.command;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import com.velocitypowered.api.command.CommandSource;
import com.velocitypowered.api.command.SimpleCommand;
import com.velocitypowered.api.proxy.Player;

import com.qqlink.velocity.QQGroupLinkPlugin;
import com.qqlink.velocity.config.model.CommandsConfig;
import com.qqlink.velocity.config.model.GroupEntry;
import com.qqlink.velocity.config.model.PluginConfig;
import com.qqlink.velocity.config.model.ServerEntry;
import com.qqlink.velocity.mc.PluginMessages;
import com.qqlink.velocity.util.Format;
import com.qqlink.velocity.util.Strings;
import com.qqlink.velocity.util.Text;

/**
 * 游戏内指令 {@code /qqlink}（别名 {@code /qq}）。
 *
 * <h2>为什么只注册一个指令名</h2>
 * 如果为 {@code list}、{@code tps} 各注册一个 Velocity 指令，
 * 就会和 Essentials / LuckPerms 等插件的同名指令冲突，
 * 甚至抢走原本属于 `/list` 的功能。因此这里只有一个根指令，
 * 其余功能通过子命令实现：{@code /qqlink list}。
 *
 * <h2>子命令</h2>
 * <pre>
 *   /qqlink                —— 帮助
 *   /qqlink help           —— 帮助
 *   /qqlink status         —— 运行状态（连接、统计、子服、群、管理员）
 *   /qqlink reload         —— 热重载配置并重建 OneBot 连接
 *   /qqlink list           —— 各子服在线玩家
 *   /qqlink tps            —— 各子服 TPS / MSPT
 *   /qqlink broadcast &lt;文本&gt;      —— 向所有子服广播
 *   /qqlink send &lt;服务器&gt; &lt;文本&gt;  —— 向指定子服发送
 *   /qqlink sendtoqq &lt;群号|all&gt; &lt;文本&gt; —— 手动向 QQ 群发消息（测试用）
 *   /qqlink debug          —— 打印 OneBot 原始报文调试信息
 * </pre>
 *
 * <p>权限：{@code security.in-game-permission}（默认 {@code qqlink.admin}）。
 * Velocity 在没有权限插件时，控制台与 OP 通过；普通玩家需要权限节点。
 */
public final class QqLinkCommand implements SimpleCommand {

    private final QQGroupLinkPlugin plugin;

    public QqLinkCommand(QQGroupLinkPlugin plugin) {
        this.plugin = plugin;
    }

    @Override
    public void execute(Invocation invocation) {
        CommandSource source = invocation.source();
        PluginConfig config = plugin.config();
        if (config == null) {
            source.sendMessage(Text.toComponent("&c插件尚未初始化完成"));
            return;
        }
        String permission = config.security.inGamePermission;
        if (Strings.isNotBlank(permission) && !source.hasPermission(permission)) {
            source.sendMessage(Text.toComponent("&c你没有权限使用该指令（需要 " + permission + "）"));
            return;
        }

        String[] args = invocation.arguments();
        String sub = args.length == 0 ? "help" : args[0].toLowerCase(java.util.Locale.ROOT);

        switch (sub) {
            case "help", "?", "h" -> help(source);
            case "status", "st" -> status(source);
            case "reload", "rl" -> reload(source);
            case "list", "online" -> list(source);
            case "tps", "perf" -> tps(source);
            case "broadcast", "bc" -> broadcast(source, args);
            case "send", "msg" -> send(source, args);
            case "sendtoqq", "qq" -> sendToQq(source, args);
            case "debug" -> debug(source, args);
            default -> {
                source.sendMessage(Text.toComponent("&c未知子命令：" + sub));
                help(source);
            }
        }
    }

    // ------------------------------------------------------------------
    // 子命令实现
    // ------------------------------------------------------------------

    private void help(CommandSource source) {
        source.sendMessage(Text.toComponent("&e===== QQGroupLink 帮助 ====="));
        line(source, "/qqlink status", "查看连接状态与收发统计");
        line(source, "/qqlink reload", "热重载 config.yml 并重建 OneBot 连接");
        line(source, "/qqlink list", "查看各子服在线玩家");
        line(source, "/qqlink tps", "查看各子服 TPS / MSPT（需装伴随模组）");
        line(source, "/qqlink broadcast <文本>", "向所有子服广播");
        line(source, "/qqlink send <服务器> <文本>", "向指定子服发送（服务器名可写显示名或 id）");
        line(source, "/qqlink sendtoqq <群号|all> <文本>", "手动向 QQ 群发消息（测试机器人）");
        line(source, "/qqlink debug on|off", "开启/关闭 OneBot 原文调试输出");
        source.sendMessage(Text.toComponent("&7QQ 群内可用指令：" + qqHelpSummary()));
    }

    private void line(CommandSource source, String command, String description) {
        source.sendMessage(Text.toComponent("&b" + command + " &7- " + description));
    }

    private String qqHelpSummary() {
        PluginConfig config = plugin.config();
        String prefix = config.commands.prefixes.isEmpty() ? "#" : config.commands.prefixes.get(0);
        List<String> names = new ArrayList<>();
        for (CommandsConfig.Builtin builtin : CommandsConfig.Builtin.values()) {
            if (config.commands.isEnabled(builtin)) {
                names.add(prefix + builtin.id());
            }
        }
        return String.join("、", names);
    }

    private void status(CommandSource source) {
        PluginConfig config = plugin.config();
        source.sendMessage(Text.toComponent("&e===== QQGroupLink 状态 ====="));
        if (plugin.bridge() == null) {
            source.sendMessage(Text.toComponent("&c插件未启动（config.yml 中 enabled: false）"));
            return;
        }
        for (String desc : plugin.bridge().describe()) {
            source.sendMessage(Text.toComponent("&7" + desc));
        }
        source.sendMessage(Text.toComponent("&7在线玩家：&f" + plugin.proxy().getPlayerCount()
                + " &7子服数：&f" + plugin.proxy().getAllServers().size()));
        source.sendMessage(Text.toComponent("&7已知群：&f" + describeGroups(config)));
        source.sendMessage(Text.toComponent("&7管理员数：&f" + config.security.adminWhitelist.size()));
        source.sendMessage(Text.toComponent("&7模组已就绪的子服：&f"
                + (plugin.messageRouter() == null ? "-" : String.join(", ", plugin.messageRouter().modReadyServers()))));
        source.sendMessage(Text.toComponent("&7提示：TPS/MSPT 需要子服安装 qqlink-fabric 模组"));
    }

    private String describeGroups(PluginConfig config) {
        if (!config.groups.hasAny()) {
            return "未配置";
        }
        List<String> parts = new ArrayList<>();
        for (GroupEntry group : config.groups.all().values()) {
            parts.add(group.display());
        }
        return String.join(", ", parts);
    }

    private void reload(CommandSource source) {
        source.sendMessage(Text.toComponent("&7正在重载配置……"));
        List<String> result = plugin.reload();
        for (String line : result) {
            source.sendMessage(Text.toComponent("&a" + line));
        }
        source.sendMessage(Text.toComponent("&a配置重载完成"));
    }

    private void list(CommandSource source) {
        PluginConfig config = plugin.config();
        Map<String, ServerEntry> configured = config.servers.all();
        source.sendMessage(Text.toComponent("&e===== 子服在线玩家 ====="));
        int total = 0;
        List<String> handled = new ArrayList<>();
        for (ServerEntry entry : config.servers.sorted()) {
            handled.add(entry.id);
            List<Player> online = plugin.servers().playersOn(entry.id);
            total += online.size();
            source.sendMessage(Text.toComponent("&7- &b" + entry.displayName + " &7(" + entry.id + "): &f"
                    + online.size() + " 人" + (online.isEmpty() ? "" : " &7[" + joinNames(online) + "&7]")));
        }
        for (String serverId : plugin.servers().registeredIds()) {
            if (handled.contains(serverId)) {
                continue;
            }
            List<Player> online = plugin.servers().playersOn(serverId);
            total += online.size();
            source.sendMessage(Text.toComponent("&7- &b" + serverId + " &7(未在配置中): &f"
                    + online.size() + " 人" + (online.isEmpty() ? "" : " &7[" + joinNames(online) + "&7]")));
        }
        if (configured.isEmpty() && handled.isEmpty()) {
            source.sendMessage(Text.toComponent("&c没有发现任何子服，请检查 velocity.toml 的 [servers] 段"));
        }
        source.sendMessage(Text.toComponent("&7总计：&f" + total + " &7人"));
    }

    private String joinNames(List<Player> online) {
        List<String> names = new ArrayList<>();
        for (Player player : online) {
            names.add(player.getUsername());
        }
        return String.join(", ", names);
    }

    private void tps(CommandSource source) {
        source.sendMessage(Text.toComponent("&e===== 子服性能 ====="));
        boolean any = false;
        for (ServerEntry entry : plugin.config().servers.enabledServers()) {
            if (plugin.players().hasMetrics(entry.id)) {
                any = true;
                double tps = plugin.players().tps(entry.id);
                double mspt = plugin.players().mspt(entry.id);
                source.sendMessage(Text.toComponent("&7- &b" + entry.displayName + " &7TPS: &f"
                        + Strings.fixed(tps, 2) + " &7(" + com.qqlink.velocity.mc.PlayerManager.tpsQuality(tps)
                        + ") &7MSPT: &f" + Strings.fixed(mspt, 2)
                        + " &7在线: &f" + plugin.servers().playerCount(entry.id)));
            } else {
                source.sendMessage(Text.toComponent("&7- &b" + entry.displayName
                        + " &c暂无数据（未安装 qqlink-fabric 模组）"));
            }
        }
        if (!any) {
            source.sendMessage(Text.toComponent("&c没有任何子服上报性能数据。"
                    + "请在子服安装本项目的 qqlink-fabric 模组后重启子服。"));
        }
    }

    private void broadcast(CommandSource source, String[] args) {
        if (args.length < 2) {
            source.sendMessage(Text.toComponent("&c用法：/qqlink broadcast <文本>"));
            return;
        }
        String content = String.join(" ", java.util.Arrays.copyOfRange(args, 1, args.length));
        content = Strings.truncate(content, plugin.config().security.maxMessageLength);
        String rendered = Format.of(plugin.config().messages.inGameBroadcast)
                .set("message", content)
                .set("sender", sourceName(source))
                .trustValues()
                .render();
        int count = plugin.router().pushToAllServers(PluginMessages.DOWN_BROADCAST, rendered);
        source.sendMessage(Text.toComponent("&a已向 " + count + " 个子服发送公告"));
    }

    private void send(CommandSource source, String[] args) {
        if (args.length < 3) {
            source.sendMessage(Text.toComponent("&c用法：/qqlink send <服务器> <文本>"));
            return;
        }
        String serverId = plugin.commands().resolveServerId(args[1]);
        if (serverId == null) {
            source.sendMessage(Text.toComponent("&c找不到子服：" + args[1]));
            return;
        }
        String content = String.join(" ", java.util.Arrays.copyOfRange(args, 2, args.length));
        content = Strings.truncate(content, plugin.config().security.maxMessageLength);
        String rendered = Format.of(plugin.config().messages.inGameBroadcast)
                .set("message", content)
                .set("sender", sourceName(source))
                .set("server", plugin.config().servers.displayName(serverId))
                .trustValues()
                .render();
        boolean ok = plugin.router().pushToServer(serverId, PluginMessages.DOWN_BROADCAST, rendered);
        source.sendMessage(Text.toComponent(ok
                ? "&a已发送到 " + plugin.config().servers.displayName(serverId)
                : "&c发送失败：目标子服没有可用连接"));
    }

    private void sendToQq(CommandSource source, String[] args) {
        if (args.length < 3) {
            source.sendMessage(Text.toComponent("&c用法：/qqlink sendtoqq <群号|all> <文本>"));
            return;
        }
        if (plugin.bridge() == null || !plugin.bridge().isConnected()) {
            source.sendMessage(Text.toComponent("&cOneBot 未连接，请先用 /qqlink status 排查"));
            return;
        }
        String target = args[1];
        String content = String.join(" ", java.util.Arrays.copyOfRange(args, 2, args.length));
        List<Long> targets = new ArrayList<>();
        if ("all".equalsIgnoreCase(target)) {
            targets.addAll(plugin.config().groups.ids());
        } else if (Strings.isLong(target)) {
            targets.add(Strings.parseLong(target, 0L));
        } else {
            source.sendMessage(Text.toComponent("&c群号必须是数字，或写 all 表示所有已配置的群"));
            return;
        }
        if (targets.isEmpty()) {
            source.sendMessage(Text.toComponent("&c没有可用的群，请先在 config.yml 的 groups 段配置群号"));
            return;
        }
        for (Long groupId : targets) {
            plugin.bridge().sendGroup(groupId, content);
        }
        source.sendMessage(Text.toComponent("&a已投递到 " + targets.size() + " 个群（实际发送见代理日志）"));
    }

    private void debug(CommandSource source, String[] args) {
        if (args.length < 2) {
            source.sendMessage(Text.toComponent("&7当前 debug = &f" + plugin.config().debug
                    + "&7，用法：/qqlink debug on|off"));
            return;
        }
        boolean value = Strings.parseBoolean(args[1], !plugin.config().debug);
        plugin.config().debug = value;
        plugin.config().onebot.debug = value;
        source.sendMessage(Text.toComponent("&a已将调试输出设置为 " + value
                + "（仅本次运行有效，重启后以 config.yml 为准）"));
    }

    private String sourceName(CommandSource source) {
        if (source instanceof Player player) {
            return player.getUsername();
        }
        return "控制台";
    }

    // ------------------------------------------------------------------
    // Tab 补全
    // ------------------------------------------------------------------

    @Override
    public List<String> suggest(Invocation invocation) {
        String[] args = invocation.arguments();
        if (args.length <= 1) {
            String partial = args.length == 0 ? "" : args[0].toLowerCase(java.util.Locale.ROOT);
            List<String> candidates = List.of("help", "status", "reload", "list", "tps",
                    "broadcast", "send", "sendtoqq", "debug");
            List<String> result = new ArrayList<>();
            for (String candidate : candidates) {
                if (candidate.startsWith(partial)) {
                    result.add(candidate);
                }
            }
            return result;
        }
        String sub = args[0].toLowerCase(java.util.Locale.ROOT);
        if ("send".equals(sub) && args.length == 2) {
            List<String> names = new ArrayList<>();
            for (ServerEntry entry : plugin.config().servers.sorted()) {
                names.add(entry.id);
                if (Strings.isNotBlank(entry.displayName) && !entry.displayName.equals(entry.id)) {
                    names.add(entry.displayName);
                }
            }
            return names;
        }
        if ("sendtoqq".equals(sub) && args.length == 2) {
            List<String> targets = new ArrayList<>();
            targets.add("all");
            for (Long groupId : plugin.config().groups.ids()) {
                targets.add(String.valueOf(groupId));
            }
            return targets;
        }
        if ("debug".equals(sub) && args.length == 2) {
            return List.of("on", "off");
        }
        return List.of();
    }
}
