#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
配置校验脚本（零依赖版）。

本机无网络，无法安装 PyYAML，因此这里**复刻** Java 侧
`proxy/src/main/java/com/qqlink/velocity/config/Yaml.java` 的解析规则，
用它来验证 `config.yml` 模板：

  1) 语法能否被本插件自研解析器读入（这是真正重要的，因为运行期用的就是它）
  2) 关键配置路径是否齐备（与 Java 侧 Nodes 读取路径一一对应）
  3) 关键项的类型是否正确
  4) 模板中的 %占位符% 是否都在文档收录范围内
  5) 行尾注释剥离规则是否会误伤值（如 URL 里的 #）

之所以复刻而不是用标准库，是因为"能被 PyYAML 解析"不等于
"能被插件的解析器解析" —— 后者才是线上真正的行为。
"""
import os
import re
import sys

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
CONFIG = os.path.join(ROOT, "proxy", "src", "main", "resources", "config.yml")

INDENT = "  "


# ----------------------------------------------------------------------
# 复刻 Yaml.java 的解析逻辑
# ----------------------------------------------------------------------

def strip_comment(raw):
    """与 Yaml.stripComment 一致：# 在行首或前面是空白且不在引号内时才是注释。"""
    in_single = in_double = False
    for i, ch in enumerate(raw):
        if ch == "'" and not in_double:
            in_single = not in_single
        elif ch == '"' and not in_single:
            in_double = not in_double
        elif ch == '#' and not in_single and not in_double:
            if i == 0 or raw[i - 1].isspace():
                return raw[:i]
    return raw


def index_of_colon(text):
    in_single = in_double = False
    for i, ch in enumerate(text):
        if ch == "'" and not in_double:
            in_single = not in_single
        elif ch == '"' and not in_single:
            in_double = not in_double
        elif ch == ':' and not in_single and not in_double:
            return i
    return -1


def split_inline(body):
    parts, cur = [], []
    in_single = in_double = False
    for ch in body:
        if ch == "'" and not in_double:
            in_single = not in_single
        elif ch == '"' and not in_single:
            in_double = not in_double
        if ch == ',' and not in_single and not in_double:
            parts.append("".join(cur).strip())
            cur = []
            continue
        cur.append(ch)
    if cur:
        parts.append("".join(cur).strip())
    return parts


def parse_scalar(value):
    value = value.strip()
    if value == "":
        return ""
    if len(value) >= 2 and value[0] == value[-1] and value[0] in "\"'":
        inner = value[1:-1]
        if value[0] == '"':
            inner = inner.replace('\\"', '"').replace("\\\\", "\\")
            inner = inner.replace("\\n", "\n").replace("\\t", "\t")
        return inner
    if value.startswith("[") and value.endswith("]"):
        body = value[1:-1].strip()
        return [parse_scalar(p) for p in split_inline(body)] if body else []
    if value.startswith("{") and value.endswith("}"):
        body = value[1:-1].strip()
        result = {}
        if body:
            for part in split_inline(body):
                colon = index_of_colon(part)
                if colon > 0:
                    result[part[:colon].strip()] = parse_scalar(part[colon + 1:])
        return result
    low = value.lower()
    if low in ("true", "yes", "on"):
        return True
    if low in ("false", "no", "off"):
        return False
    if low in ("null", "~"):
        return ""
    if re.fullmatch(r"[+-]?\d+", value):
        return int(value)
    if re.fullmatch(r"[+-]?(\d+\.\d*|\.\d+)", value):
        return float(value)
    return value


class Line:
    def __init__(self, raw):
        self.raw = raw.replace("\t", INDENT)
        self.indent = len(self.raw) - len(self.raw.lstrip(" "))

    @property
    def content(self):
        return strip_comment(self.raw).strip()

    @property
    def blank(self):
        return self.content == ""

    @property
    def list_item(self):
        c = self.content
        return c.startswith("- ") or c == "-"

    @property
    def has_key(self):
        c = self.content
        if not c or c.startswith("#"):
            return False
        return index_of_colon(c) > 0

    @property
    def key(self):
        c = self.content
        colon = index_of_colon(c)
        key = c[:colon].strip() if colon > 0 else c
        if len(key) >= 2 and key[0] in "\"'" and key[-1] == key[0]:
            key = key[1:-1]
        return key

    @property
    def value(self):
        c = self.content
        colon = index_of_colon(c)
        if colon < 0:
            return None
        if colon + 1 >= len(c):
            return ""
        return c[colon + 1:].strip()


class Cursor:
    def __init__(self, lines):
        self.lines = lines
        self.i = 0

    def has_next(self):
        return self.i < len(self.lines)

    def peek(self):
        return self.lines[self.i]

    def next(self):
        line = self.lines[self.i]
        self.i += 1
        return line

    def peek_meaningful(self):
        for j in range(self.i, len(self.lines)):
            if not self.lines[j].blank:
                return self.lines[j]
        return None


def parse_block(cur, indent):
    result = {}
    while cur.has_next():
        line = cur.peek()
        if line.blank:
            cur.next()
            continue
        if line.indent < indent:
            break
        if line.indent > indent:
            cur.next()
            continue
        if not line.has_key:
            cur.next()
            continue
        cur.next()
        key = line.key
        inline = line.value
        if inline:
            result[key] = parse_scalar(inline)
            continue
        nxt = cur.peek_meaningful()
        if nxt is None or nxt.indent <= indent:
            result[key] = ""
            continue
        if nxt.list_item:
            result[key] = parse_list(cur, nxt.indent)
        else:
            result[key] = parse_block(cur, nxt.indent)
    return result


def parse_list(cur, indent):
    result = []
    while cur.has_next():
        line = cur.peek()
        if line.blank:
            cur.next()
            continue
        if line.indent < indent or not line.list_item:
            break
        cur.next()
        result.append(parse_scalar(line.raw.strip()[1:].strip()))
    return result


def load(text):
    lines = [Line(l) for l in text.splitlines()]
    return parse_block(Cursor(lines), 0)


# ----------------------------------------------------------------------
# 校验
# ----------------------------------------------------------------------

with open(CONFIG, encoding="utf-8") as fh:
    raw = fh.read()

data = load(raw)
print("自研解析器读取成功。顶层键：%s" % ", ".join(data.keys()))
print()

REQUIRED = [
    "config-version", "enabled", "debug",
    "onebot.mode", "onebot.forward-url",
    "onebot.reverse.host", "onebot.reverse.port", "onebot.reverse.path",
    "onebot.access-token", "onebot.self-id",
    "onebot.reconnect-interval", "onebot.max-reconnect-delay",
    "onebot.heartbeat-interval", "onebot.request-timeout",
    "onebot.send-retries", "onebot.send-rate-limit", "onebot.send-queue-limit",
    "onebot.debug",
    "groups.strict",
    "groups.123456789.name", "groups.123456789.mc-servers",
    "groups.123456789.chat", "groups.123456789.commands",
    "groups.987654321.mc-servers",
    "servers.survival.display-name", "servers.survival.enabled",
    "servers.survival.primary", "servers.survival.forward-chat", "servers.survival.order",
    "servers.mirror.display-name", "servers.mirror.enabled", "servers.mirror.primary",
    "servers.mirror.forward-chat", "servers.mirror.order",
    "forwarding.minecraft-to-qq.chat", "forwarding.minecraft-to-qq.require-permission",
    "forwarding.qq-to-minecraft.chat", "forwarding.qq-to-minecraft.target",
    "forwarding.qq-to-minecraft.prefix-routing.enabled",
    "forwarding.qq-to-minecraft.prefix-routing.mode",
    "forwarding.qq-to-minecraft.prefix-routing.allow-separators",
    "forwarding.cross-server-chat.enabled", "forwarding.cross-server-chat.echo-to-source",
    "forwarding.suppress-switch-noise",
    "events.enabled", "events.join", "events.quit", "events.server-status",
    "events.server-status-at-all", "events.admin-broadcast", "events.server-switch",
    "events.death.enabled", "events.death.cause-mode", "events.death.cause-max-length",
    "events.advancement.enabled", "events.advancement.ignore-recipes",
    "events.advancement.ignore-challenges", "events.advancement.announce-to-all",
    "events.advancement.blacklist",
    "commands.enabled", "commands.prefixes", "commands.feedback", "commands.unauthorized",
    "commands.reply-on-mention", "commands.list-style", "commands.list-include-empty",
    "commands.confirm-heavy", "commands.echo-to-minecraft",
    "commands.builtin.list.enabled", "commands.builtin.list.aliases",
    "commands.builtin.tps.enabled", "commands.builtin.tps.aliases",
    "commands.builtin.broadcast.enabled", "commands.builtin.broadcast.aliases",
    "commands.builtin.send.enabled", "commands.builtin.send.aliases",
    "commands.builtin.help.enabled", "commands.builtin.help.aliases",
    "commands.builtin.status.enabled", "commands.builtin.status.aliases",
    "format.chat.mc-to-qq", "format.chat.qq-to-mc", "format.chat.qq-to-console",
    "format.event.join", "format.event.quit", "format.event.server-switch",
    "format.event.death", "format.event.advancement",
    "format.server.start", "format.server.stop", "format.server.broadcast",
    "format.command.list-header", "format.command.list-line", "format.command.list-empty",
    "format.command.tps-header", "format.command.tps-line", "format.command.tps-unavailable",
    "format.command.help-header", "format.command.help-line", "format.command.help-footer",
    "format.command.status",
    "format.error.no-permission", "format.error.unknown-command", "format.error.usage",
    "format.error.server-not-found", "format.error.success", "format.error.rate-limited",
    "format.error.filtered",
    "format.in-game.qq-command", "format.in-game.qq-broadcast",
    "security.admin-whitelist", "security.allow-group-admin", "security.whitelist-enabled",
    "security.in-game-permission", "security.max-message-length", "security.min-message-length",
    "security.filter-repeated-chars", "security.filter-symbols-only",
    "security.strip-cq-codes", "security.user-rate-limit", "security.group-rate-limit",
    "security.strip-colors-to-qq", "security.ignored-prefixes",
]


def lookup(tree, dotted):
    node = tree
    for part in dotted.split("."):
        if not isinstance(node, dict):
            return False, None
        if part in node:
            node = node[part]
            continue
        if part.isdigit() and int(part) in node:
            node = node[int(part)]
            continue
        return False, None
    return True, node


missing = [p for p in REQUIRED if not lookup(data, p)[0]]
if missing:
    print("缺失的配置路径（%d 个）：" % len(missing))
    for p in missing:
        print("  - %s" % p)
else:
    print("全部 %d 个配置路径齐备 OK" % len(REQUIRED))

type_checks = [
    ("onebot.reverse.port", int), ("onebot.send-rate-limit", float),
    ("onebot.heartbeat-interval", int), ("onebot.max-reconnect-delay", int),
    ("onebot.self-id", int), ("onebot.send-queue-limit", int),
    ("commands.prefixes", list), ("security.admin-whitelist", list),
    ("security.ignored-prefixes", list), ("events.advancement.blacklist", list),
    ("groups.123456789.mc-servers", list), ("groups.987654321.mc-servers", list),
    ("commands.builtin.list.aliases", list),
    ("enabled", bool), ("onebot.debug", bool), ("groups.strict", bool),
    ("servers.survival.primary", bool), ("servers.survival.enabled", bool),
    ("events.death.enabled", bool), ("commands.feedback", bool),
    ("security.max-message-length", int), ("security.user-rate-limit", int),
]
type_errors = []
for path, expected in type_checks:
    ok, value = lookup(data, path)
    if not ok:
        type_errors.append("%s 不存在" % path)
    elif not isinstance(value, expected) or isinstance(value, bool) != (expected is bool):
        type_errors.append("%s 期望 %s，实际 %s (%r)" % (path, expected.__name__, type(value).__name__, value))

print()
if type_errors:
    print("类型问题：")
    for e in type_errors:
        print("  - %s" % e)
else:
    print("类型抽查通过 OK")

# ---- URL / token 未被注释剥离误伤 ----
print()
ok, fwd = lookup(data, "onebot.forward-url")
print("onebot.forward-url 解析结果：%r  %s" % (fwd, "OK" if fwd == "ws://127.0.0.1:3001" else "!! 被误解析"))

# ---- 占位符 ----
# 只扫描"非注释行"：注释里出现 %名称% 这类说明文字是正常的，不应报警
placeholders = set()
for line in raw.splitlines():
    if line.lstrip().startswith("#"):
        continue
    for m in re.findall(r"%(\w+)%", line):
        placeholders.add(m)

KNOWN = {
    "server", "server_id", "player", "message", "sender", "sender_id",
    "group", "group_id", "cause", "advancement", "title", "frame",
    "count", "players", "tps", "mspt", "time", "uptime", "from", "to",
    "usage", "description", "prefix", "command", "bot", "connection", "servers",
    "max",
}
unknown = sorted(placeholders - KNOWN)
print()
print("模板占位符（%d 个）：%s" % (len(placeholders), ", ".join(sorted(placeholders))))
print("未知占位符：%s" % (", ".join(unknown) if unknown else "无 OK"))

# ---- 群示例键确实是数字型 ----
print()
g = data.get("groups", {})
sample = [k for k in g.keys() if k != "strict"]
print("groups 下的群号键：%s" % sample)
print("（Java 侧 GroupsConfig 通过 Strings.isLong(key) 过滤，非数字键会被跳过，因此 'strict' 不会被当成群号）")

print()
print("=" * 62)
fail = bool(missing or type_errors or unknown) or fwd != "ws://127.0.0.1:3001"
if fail:
    print("配置校验存在问题，见上方输出。")
    sys.exit(1)
print("配置校验全部通过。")
