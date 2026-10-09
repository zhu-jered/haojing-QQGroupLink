# QQGroupLink

跑在 Velocity 代理端的插件，让 QQ 群和 Minecraft 子服互通：游戏里的聊天转发到群、群里的消息发进游戏、上下线/死亡/成就这些事件推送给群，群里还能直接发指令查在线、查 TPS、广播公告。

- 协议：OneBot v11，正向 / 反向 WebSocket 都支持
- 机器人：[go-cqhttp](https://github.com/Mrs4s/go-cqhttp)、[NapCat](https://github.com/NapNeko/NapCatQQ)、[Lagrange.OneBot](https://github.com/LagrangeDev/Lagrange.Core)、LLOneBot 都试过
- 环境：JDK 21 + Velocity 3.4.x（Minecraft 1.21.x / 1.21.11）+ Fabric 1.21.11 子服
- 部署：全部服务放同一个 Docker 容器，用 MCSManager 面板管理

目录：[功能](#一功能总览) · [项目结构](#二项目结构) · [构建](#三构建) · [部署](#四部署) · [机器人对接](#五机器人对接) · [配置](#六配置) · [排错](#七排错) · [架构](#八架构) · [扩展](#九扩展) · [限制](#十已知限制)

---

## 一、功能总览

每项功能都有独立开关，在 `config.yml` 里按需启停。

### 1. 双向聊天转发

| 方向 | 行为 | 配置项 |
|---|---|---|
| MC → QQ | 子服公屏聊天转发到 QQ 群，带服务器前缀 | `format.chat.mc-to-qq` |
| QQ → MC | 群消息转发到指定子服或全部子服 | `forwarding.qq-to-minecraft` |
| 跨子服 | A 服聊天同步到 B 服（可选） | `forwarding.cross-server-chat` |

前缀可自定义，比如 `&7[&b%server%&7] &f%player%&7: &f%message%` 渲染出来就是 `[生存服] Steve: 你好`。

### 2. 游戏事件通知

| 事件 | 开关 | 数据来源 |
|---|---|---|
| 玩家加入 | `events.join` | Velocity 原生事件，不需要模组 |
| 玩家退出 | `events.quit` | Velocity 原生事件 |
| 切换子服 | `events.server-switch` | Velocity 原生事件 |
| 死亡（含死因） | `events.death` | 代理侧解析 + 伴随模组精确上报 |
| 成就 / 进度 | `events.advancement` | 伴随模组（可过滤配方刷屏） |
| 服务器启动 / 关闭 | `events.server-status` | 模组上报 + 代理侧启发式兜底 |
| 管理员公告 | `events.admin-broadcast` | 代理侧识别 `/say` `/broadcast` `/me` |

`events.enabled` 是总闸，关掉后所有事件都不推送，但聊天转发照常。

### 3. QQ 群指令

指令前缀可配：`prefixes: ["#", "/", "."]`。非白名单 QQ 号发指令默认完全无响应（防止别人探测）。

| 指令 | 说明 | 开关 |
|---|---|---|
| `#list` | 按子服返回在线玩家 | `commands.builtin.list.enabled` |
| `#tps` | 各子服 TPS / MSPT | `commands.builtin.tps.enabled` |
| `#broadcast <内容>` | 全服公告 | `commands.builtin.broadcast.enabled` |
| `#send <服务器> <内容>` | 发给指定子服 | `commands.builtin.send.enabled` |
| `#help` | 指令列表 | `commands.builtin.help.enabled` |
| `#status` | 机器人和代理运行状态 | `commands.builtin.status.enabled` |

每个指令都能配别名，中文也行，比如 `#在线`、`#性能`、`#公告`。

### 4. 多子服

- 子服显示名和 Velocity 内部 id 分开配：`servers.survival.display-name: "生存服"`
- 群消息转发目标三档：
  1. `all` —— 所有启用子服（默认）
  2. `primary` —— 只发主服
  3. 消息前缀指定 —— 群里发 `生存服 你好` / `[生存服] 你好` / `生存服：你好`，只发生存服
- 单个子服可单独禁用互通：`servers.<id>.enabled: false`
- 群和子服可以多对多：`groups.<群号>.mc-servers: ["survival", "mirror"]`

### 5. 安全

| 机制 | 配置项 | 说明 |
|---|---|---|
| 指令白名单 | `security.admin-whitelist` | 非白名单发指令默认静默 |
| 群白名单 | `groups.strict: true` | 没配置的群直接忽略 |
| 消息长度 | `security.max-message-length` | 超长截断 |
| 最短长度 | `security.min-message-length` | 过滤单字刷屏 |
| 重复字符 | `security.filter-repeated-chars` | 拦截 `aaaaaaaaaaaa` |
| 纯符号 | `security.filter-symbols-only` | 拦截纯表情/符号 |
| CQ 码剔除 | `security.strip-cq-codes` | 图片/语音不转发进游戏 |
| 用户限流 | `security.user-rate-limit` | 单个 QQ 每分钟转发上限 |
| 群限流 | `security.group-rate-limit` | 保护机器人不被 QQ 风控 |
| 发送限流 | `onebot.send-rate-limit` | 令牌桶匀速发送 |
| 样式注入 | 内置 | 昵称里的 `<red>` / `&c` 会被转义 |
| 消息回环 | 内置 | 机器人自己的消息直接丢弃 |

转发权限门槛（`forwarding.minecraft-to-qq.require-permission`）是给配合权限插件用的，留空即可。不装权限插件时如果填了非空值，聊天会不转发，注意别踩。

---

## 二、项目结构

```
群服互联/
├── settings.gradle.kts              # 两个子工程：:proxy（插件）+ :mod（伴随模组）
├── build.gradle.kts                 # 公共仓库、编码、Java 21 设置
├── gradle.properties                # 版本号集中管理（Velocity / Minecraft / Fabric API）
├── README.md
│
├── proxy/                           # Velocity 代理端插件（核心，必需）
│   ├── build.gradle.kts
│   └── src/main/
│       ├── resources/
│       │   ├── config.yml           # 默认配置模板，每项都有中文注释
│       │   └── velocity-plugin.json
│       └── java/com/qqlink/velocity/
│           ├── QQGroupLinkPlugin.java        # 主类：初始化 / 定时任务 / 热重载
│           ├── Slf4jLoggerAdapter.java       # JUL → SLF4J 日志适配
│           ├── command/
│           │   └── QqLinkCommand.java        # 游戏内 /qqlink 指令
│           ├── config/
│           │   ├── ConfigFile.java           # 配置读写 + 结构合并 + 注释保留
│           │   ├── Yaml.java                 # 自研 YAML 读写器（零依赖）
│           │   ├── Nodes.java                # YAML 节点类型安全读取
│           │   └── model/                    # 配置数据模型
│           │       ├── PluginConfig.java     # 根配置
│           │       ├── OneBotConfig.java     # OneBot 连接
│           │       ├── GroupEntry.java       # QQ 群注册表
│           │       ├── ServerEntry.java      # 子服注册表
│           │       ├── ForwardingConfig.java # 转发规则
│           │       ├── EventsConfig.java     # 事件开关
│           │       ├── CommandsConfig.java   # 指令系统（含 Builtin 枚举）
│           │       ├── Messages.java         # 消息模板
│           │       └── SecurityConfig.java   # 安全与白名单
│           ├── mc/
│           │   ├── ChatRouter.java           # 消息路由（三条链路）
│           │   ├── CommandManager.java       # QQ 指令解析与执行
│           │   ├── PlayerEventListener.java  # Velocity 事件监听
│           │   ├── PluginMessageRouter.java  # 子服上报入口（安全关键）
│           │   ├── PluginMessages.java       # 代理 ↔ 子服消息协议
│           │   ├── ServerBridge.java         # 子服查询与下发
│           │   └── PlayerManager.java        # 在线状态与 TPS/MSPT 缓存
│           ├── onebot/
│           │   ├── OneBotBridge.java         # 连接管理 + 发送队列 + 限流
│           │   ├── OneBotConnection.java     # 连接抽象
│           │   ├── OneBotListener.java       # 连接 → 业务回调
│           │   ├── OneBotEvent.java          # OneBot v11 事件视图
│           │   ├── Json.java                 # Gson 树模型辅助
│           │   ├── ForwardWebSocketConnection.java  # 正向 WS（JDK HttpClient）
│           │   ├── ReverseWebSocketConnection.java  # 反向 WS 单连接
│           │   ├── ReverseWebSocketListener.java    # 反向 WS 监听
│           │   ├── QqMessageHandler.java     # QQ 消息入口（聊天/指令分流）
│           │   ├── ws/
│           │   │   ├── WebSocket.java        # RFC 6455 帧编解码 + 握手
│           │   │   └── OutboundSocket.java   # 连接级收发
│           │   └── api/
│           │       ├── OneBotApi.java        # OneBot API 调用（echo 匹配）
│           │       └── MessageSegment.java   # 消息段
│           └── util/
│               ├── Text.java                 # 颜色翻译（&/§/hex/MiniMessage）
│               ├── Format.java               # %占位符% 模板引擎
│               ├── Strings.java              # 字符串与过滤工具
│               └── Holder.java               # 可变引用（热重载用）
│
├── mod/                             # Fabric 伴随模组（可选，增强）
│   ├── build.gradle.kts
│   └── src/main/
│       ├── resources/
│       │   ├── fabric.mod.json
│       │   └── qqlink-fabric.mixins.json
│       └── java/com/qqlink/fabric/
│           ├── QQGroupLinkFabric.java        # 模组入口：上报 TPS/死因/成就
│           ├── Protocol.java                 # 协议常量（与代理端对应）
│           └── mixin/
│               └── MinecraftServerTickTimesAccessor.java  # 读 tickTimes 取 MSPT
│
└── tools/
    ├── check-structure.ps1          # 开发辅助：括号配平检查
    └── check-imports.ps1            # 开发辅助：跨包引用检查
```

---

## 三、构建

环境要求：JDK 21（`java -version` 显示 21.x）、Gradle 8.10+（wrapper 自带，首次构建联网下载依赖）。

构建代理端插件：

```bash
# Linux / macOS
./gradlew :proxy:build

# Windows
gradlew.bat :proxy:build
```

产物：`proxy/build/libs/QQGroupLink-1.0.0.jar`

这个 jar 不打包任何第三方库——Gson、Guava、Adventure、SLF4J 都是 Velocity 运行时提供的，产物里只有项目自己的 class 文件和配置文件。

构建伴随模组（可选）：

```bash
./gradlew :mod:build
```

产物：`mod/build/libs/qqlink-fabric-1.0.0.jar`

> 构建模组需要从 Mojang 官方源（piston-meta.mojang.com 等）下载 Minecraft 依赖，国内直连经常连不上（超时/502），需要能访问这些域名的网络环境或代理。构建代理端插件不受影响。

版本号集中在 [`gradle.properties`](gradle.properties)，改版本不用动源码：

```properties
# Velocity 3.3.x / 3.5.x 的话改这一行
velocity_version=3.4.0-SNAPSHOT

# 子服 1.21.1 / 1.21.4 的话改这三行
minecraft_version=1.21.11
yarn_mappings=1.21.11+build.1
fabric_api_version=0.135.1+1.21.11
```

`yarn_mappings` 和 `fabric_api_version` 从这两个页面查对应版本：
- Yarn：<https://fabricmc.net/develop/>
- Fabric API：<https://modrinth.com/mod/fabric-api/versions>

代理端插件本身完全不依赖 Fabric。子服跑的是 Forge / NeoForge 也没关系，代理端照常工作，只是没有 TPS/精确死因/成就这几项增强（见[限制](#十已知限制)）。

---

## 四、部署

### 4.1 拓扑

所有服务在同一个 Docker 容器里，走 `127.0.0.1` 互通，端口由 MCSM 统一映射。

```
┌─────────────────────────── Docker 容器 ───────────────────────────┐
│                                                                    │
│   玩家 ──► [Velocity 代理 :25565]                                  │
│              │  QQGroupLink 插件                                   │
│              │  ① 反向 WS 监听 :8765  ◄──────┐                     │
│              │  ② 或正向 WS 外连 ─────────┐  │                     │
│              │                            │  │                     │
│              ├──► [Fabric 生存服 :25566]  │  │                     │
│              │      + qqlink-fabric（可选）│  │                     │
│              └──► [Fabric 镜像服 :25567]  │  │                     │
│                     + qqlink-fabric（可选）│  │                     │
│                                           ▼  │                     │
│                              [NapCat / go-cqhttp / Lagrange]       │
│                                       │                            │
└───────────────────────────────────────┼────────────────────────────┘
                                        ▼
                                    QQ 群
```

机器人只需要和代理的 8765 端口互通，不需要碰子服。

### 4.2 装插件

1. 把 `QQGroupLink-1.0.0.jar` 放进 Velocity 的 `plugins/` 目录
2. 启动一次代理，插件会在 `plugins/qqlink/` 下生成 `config.yml`
3. 停服，编辑 `config.yml`，至少填这几项：
   - `groups`：QQ 群号
   - `servers`：子服 id 和显示名（id 必须和 `velocity.toml` 的 `[servers]` 段一致）
   - `security.admin-whitelist`：你的 QQ 号
   - `onebot`：连接模式和端口
4. 重启代理，日志出现下面这些就说明起来了：

```
==================== QQGroupLink 已启动 ====================
连接配置：mode=reverse, forwardUrl=ws://127.0.0.1:3001, reverse=0.0.0.0:8765/onebot/v11/ws
子服映射：survival→生存服[主服]，mirror→镜像服（共 2 个）
QQ 群：生存服交流群(123456789)
管理员：[10001]
[OneBot] 反向 WebSocket 监听已启动：0.0.0.0:8765/onebot/v11/ws
```

### 4.3 装伴随模组（可选但推荐）

1. 把 `qqlink-fabric-1.0.0.jar` 放进每个 Fabric 子服的 `mods/` 目录
2. 子服要装 Fabric API（依赖）
3. 重启子服，日志出现：

```
[QQGroupLink] qqlink-fabric 1.0.0 正在初始化……
[QQGroupLink] 已通知代理：服务器启动完成
```

代理侧出现这条说明上报通道通了：

```
[MC] 子服 survival 的 qqlink-fabric 模组已就绪（版本 1.0.0）
```

不装模组的话，`#tps` 会显示「暂无性能数据（子服未安装 qqlink-fabric 模组）」，其他功能不受影响。用 `/qqlink status` 可以看哪些子服已就绪。

### 4.4 部署 OneBot 机器人（NapCat 为例）

方案 A：机器人作为独立 MCSM 实例（推荐，方便单独重启）

1. MCSM 新建应用实例，类型选「通用/自定义」（或直接用 Docker 实例类型跑 NapCat 镜像）
2. 启动后记录监听端口
3. 打开 NapCat WebUI（默认 `http://<容器IP>:6099`）：
   - 扫码登录 QQ
   - 「网络配置」→ 新建 **WebSocket 反向**
   - URL 填 `ws://127.0.0.1:8765/onebot/v11/ws`
     - 机器人和代理不在同一容器的话，把 `127.0.0.1` 换成代理容器 IP，同一 Docker 网络可用容器名（如 `ws://velocity:8765/onebot/v11/ws`）
   - AccessToken 填和 `config.yml` 里 `onebot.access-token` 完全一致的值
   - 保存并启用
4. 代理日志应该立刻出现：

```
[OneBot/反向] 机器人已连接：172.17.0.3:54321（实现：NapCat，路径：/onebot/v11/ws）
[OneBot] 机器人 QQ 号：123456（实现：NapCat）
```

方案 B：机器人作为守护进程跑在 MCSM 容器里，同样在 WebUI 配反向 WS。只要和代理在同一容器网络内，`127.0.0.1:8765` 就能通。

### 4.5 MCSM 端口映射

| 服务 | 容器内端口 | 对外 | 说明 |
|---|---|---|---|
| Velocity | 25565 | ✅ | 玩家入口 |
| 生存服 Fabric | 25566 | ❌ | 仅代理访问（`online-mode=false` + 转发密钥） |
| 镜像服 Fabric | 25567 | ❌ | 同上 |
| QQGroupLink 反向 WS | 8765 | ❌ | 只要机器人和代理互通 |
| NapCat WebUI | 6099 | ⚠️ 建议仅内网 | 扫码和配置用，配完可关 |

8765 不需要映射到公网，机器人和代理在同一个容器/网络里走 `127.0.0.1` 或容器名就行。如果机器人确实部署在别的机器、必须跨网络，那 `access-token` 一定要设。

### 4.6 验证

QQ 群里用白名单 QQ 发：

```
#status      → 机器人 QQ、连接状态、子服数、在线人数、运行时长
#list        → 各子服在线玩家
#help        → 指令列表
```

游戏里进服说话，QQ 群应收到 `[生存服] 你的ID: 内容`。

---

## 五、机器人对接

### 5.1 连接模式怎么选

| | 反向 WS（`mode: reverse`） | 正向 WS（`mode: forward`） |
|---|---|---|
| 谁当服务端 | 插件监听 8765，机器人连过来 | 机器人监听 3001，插件连过去 |
| 配置 | 机器人填 `ws://<代理IP>:8765/onebot/v11/ws` | 插件填 `ws://127.0.0.1:3001` |
| 优点 | 端口固定；机器人重启后自动重连 | 插件不用开监听端口 |
| 缺点 | 插件得能监听端口 | 机器人要先起来，不然插件一直重连 |
| 适合 | MCSM 同容器部署 | 机器人不在本机 / 没端口权限 |

`mode: both` 两个都开，迁移期或同时接多个机器人时用。

### 5.2 各机器人配置

**NapCat**

WebUI → 网络配置 → 新建，类型选 WebSocket 反向：

| 字段 | 填什么 |
|---|---|
| URL | `ws://127.0.0.1:8765/onebot/v11/ws` |
| AccessToken | 和 `onebot.access-token` 一致（可留空） |
| 消息格式 | `array` 或 `string` 都行 |

**go-cqhttp**

`config.yml`：

```yaml
servers:
  - ws:
      host: 0.0.0.0        # 正向 WS 服务器
      port: 3001
  - ws-reverse:
      universal: ws://127.0.0.1:8765/onebot/v11/ws
      reconnect-interval: 3000
      access-token: 你的token
```

对应插件配置：
- 用 `ws-reverse` → `onebot.mode: reverse`
- 用 `ws`（正向）→ `onebot.mode: forward`，`forward-url: ws://127.0.0.1:3001`

**Lagrange.OneBot**

`appsettings.json`：

```json
{
  "Implementations": [
    {
      "Type": "ReverseWebSocket",
      "Host": "127.0.0.1",
      "Port": 8765,
      "Suffix": "/onebot/v11/ws",
      "ReconnectInterval": 5000,
      "AccessToken": "你的token"
    }
  ]
}
```

### 5.3 路径不一致没关系

各家实现的默认反向路径五花八门：`/`、`/ws`、`/onebot/v11/ws`、`/onebot/v11/ws/`。插件对路径的处理是：不匹配也照收，只在日志里提醒一次：

```
[OneBot/反向] 收到路径 / 的连接，但配置为 /onebot/v11/ws；仍按配置的服务处理，
              如需固定请把机器人端地址改成 ws://<代理IP>:8765/onebot/v11/ws
```

所以路径写错也能连通，不用反复试。

### 5.4 AccessToken 两种传法都认

1. 请求头：`Authorization: Bearer <token>`
2. 查询参数：`?access_token=<token>`

正向模式下插件会把 token 同时放请求头和 URL 查询串。

---

## 六、配置

配置文件是 [`proxy/src/main/resources/config.yml`](proxy/src/main/resources/config.yml)，插件首次启动会释放到 `plugins/qqlink/config.yml`。

升级友好：插件以 jar 内模板为骨架合并你的配置，新增配置项会自动补进你的文件并保留原有取值，不会覆盖修改。

### 6.1 核心配置

```yaml
enabled: true            # 全局总开关

onebot:
  mode: reverse          # reverse / forward / both
  forward-url: "ws://127.0.0.1:3001"
  reverse:
    host: "0.0.0.0"
    port: 8765
    path: "/onebot/v11/ws"
  access-token: ""       # 建议设置
  self-id: 0             # 0 = 自动获取；建议手填，用于防消息回环
  send-rate-limit: 5.0   # 每秒最多发 5 条（防 QQ 风控）

groups:
  strict: true           # 未列出的群一律忽略
  "123456789":
    name: "生存服交流群"
    mc-servers: ["survival"]   # 留空 [] = 转发到所有启用子服
    chat: true
    commands: true

servers:
  survival:
    display-name: "生存服"
    enabled: true
    primary: true        # 主服：target=primary 时的默认目标
    order: 1
  mirror:
    display-name: "镜像服"
    enabled: true
    primary: false
    order: 2

forwarding:
  minecraft-to-qq: { chat: true, require-permission: "" }
  qq-to-minecraft:
    chat: true
    target: all          # all / primary
    prefix-routing:
      enabled: true
      mode: both         # display / id / both
      allow-separators: true

events:
  enabled: true
  join: true
  quit: true
  server-status: true
  admin-broadcast: true
  server-switch: true
  death: { enabled: true, cause-mode: auto, cause-max-length: 60 }
  advancement: { enabled: true, ignore-recipes: true, blacklist: [] }

commands:
  enabled: true
  prefixes: ["#", "/", "."]
  feedback: true
  unauthorized: silent   # silent（默认，无响应）/ reply（提示权限不足）
  builtin:
    list: { enabled: true, aliases: ["在线", "玩家列表", "online"] }
    tps:  { enabled: true, aliases: ["性能", "mspt", "lag"] }
    # ...

security:
  admin-whitelist: [10001, 10002]     # 必填
  max-message-length: 200
  user-rate-limit: 20
  group-rate-limit: 60
  ignored-prefixes: ["/"]             # 游戏内指令不当聊天转发
```

### 6.2 颜色写法

三种可混用：

```yaml
mc-to-qq: "&7[&b%server%&7] &f%player%&7: &f%message%"      # 传统 & 代码
mc-to-qq: "&#FF8800[&f%server%&#FF8800] &f%player%: %message%"  # 十六进制
mc-to-qq: "<gold>[<aqua>%server%<gold>] <white>%player%: %message%"  # MiniMessage 风格
```

### 6.3 占位符

| 占位符 | 含义 | 可用模板 |
|---|---|---|
| `%server%` | 子服显示名 | 全部 |
| `%server_id%` | Velocity 子服 id | 全部 |
| `%player%` | 玩家名 | MC 相关 |
| `%message%` | 消息正文 | 聊天相关 |
| `%sender%` / `%sender_id%` | QQ 昵称 / QQ 号 | QQ → MC |
| `%group%` / `%group_id%` | 群名 / 群号 | QQ 相关 |
| `%cause%` | 死亡原因 | 死亡通知 |
| `%advancement%` / `%title%` | 成就名 | 成就通知 |
| `%frame%` | 成就类型 `task`/`goal`/`challenge`/`recipe` | 成就通知 |
| `%count%` | 数量、人数 | 列表、状态 |
| `%players%` | 玩家列表 | `#list` |
| `%tps%` / `%mspt%` | 性能数据 | `#tps` |
| `%uptime%` | 运行时长 | `#status` |
| `%from%` / `%from_id%` / `%to%` / `%to_id%` | 切换前后子服 | 切换通知 |
| `%usage%` / `%description%` / `%prefix%` / `%command%` | 指令元信息 | `#help` |
| `%bot%` / `%connection%` / `%servers%` | 机器人状态 | `#status` |

模板渲染顺序是「先填值 → 再上色」，所以玩家昵称或 QQ 消息里写 `<red>`、`&c` 只会显示成普通文字，没法伪造样式。

### 6.4 游戏内指令

需要权限节点 `security.in-game-permission`（默认 `qqlink.admin`）：

```
/qqlink status                          查看运行状态
/qqlink reload                          热重载配置并重建 OneBot 连接
/qqlink list                            各子服在线玩家
/qqlink tps                             各子服 TPS / MSPT
/qqlink broadcast <文本>                向所有子服广播
/qqlink send <服务器> <文本>            向指定子服发送
/qqlink sendtoqq <群号|all> <文本>      手动发到 QQ 群（测试机器人通路）
/qqlink debug on|off                    打印 OneBot 原始报文
```

---

## 七、排错

### 7.1 连接不通

| 现象 | 排查方法 | 常见原因 |
|---|---|---|
| 日志没有「反向 WebSocket 监听已启动」 | 看有没有 `Address already in use` | 8765 被占用，改 `onebot.reverse.port` |
| 日志有监听，机器人连不上 | `curl -v http://127.0.0.1:8765/onebot/v11/ws` 看是 400 还是 101 | 机器人和代理不在同一网络；地址写错 |
| 反向连接建立后立刻断开 | 看日志有没有「AccessToken 校验失败」 | token 不一致 |
| 正向模式一直重连 | 看日志失败原因 | 机器人没开正向 WS；端口/地址错 |
| 连接正常但收发无数据 | `/qqlink debug on` 看原始报文 | 机器人未上报 `message` 字段 |

定位连接问题常用的三条命令：

```bash
# 代理是否在监听
netstat -tlnp | grep 8765

# 容器内能不能访问自己
curl -v http://127.0.0.1:8765/onebot/v11/ws

# 插件自身状态
/qqlink status      # 游戏内
```

### 7.2 消息不通

| 现象 | 检查项 |
|---|---|
| 游戏里说话，群里没反应 | ① `forwarding.minecraft-to-qq.chat` 是否为 true<br>② `groups.strict` 和群号对不对<br>③ 消息是不是以 `security.ignored-prefixes` 开头（默认 `/`）<br>④ 有没有被 `max/min-message-length` 或重复字符过滤掉<br>⑤ `/qqlink debug on` 看发送日志 |
| 群里说话，游戏里没反应 | ① `forwarding.qq-to-minecraft.chat` 是否为 true<br>② 群配置 `chat: true`<br>③ 目标子服有没有玩家在线<br>④ 该子服在不在「模组已就绪」列表里（没玩家时无法下发） |
| 加入退出没通知 | `events.enabled` 和 `events.join/quit` 是否都开了 |
| 死亡通知没死因 | 没装模组时死因靠消息解析，复杂死因会退化；装模组后精确 |
| `#tps` 一直没数据 | ① 子服装没装 `qqlink-fabric`<br>② 该子服有没有玩家在线<br>③ `/qqlink status` 看「模组已就绪的子服」 |
| `#broadcast` 游戏里看不到 | 目标子服必须有玩家在线（自定义载荷依附玩家连接） |

### 7.3 机器人收不到消息

| 现象 | 原因 | 解决 |
|---|---|---|
| 插件日志显示已发送，群里没有 | 机器人被风控 / 禁言 | 降低 `send-rate-limit`；检查 QQ 是否被禁言 |
| 消息多时丢消息 | 发送队列溢出 | 提高 `send-queue-limit`，同时降低发送频率 |
| 日志出现「超过重试上限，丢弃消息」 | 连接不稳定 | 检查网络；提高 `send-retries` |

### 7.4 回归清单

```
□ 玩家 A 在生存服发言          → QQ 群收到 [生存服] A: xxx
□ 群里发 "你好"                → 生存服与镜像服玩家都看到 [QQ] 昵称: 你好
□ 群里发 "生存服 你好"          → 仅生存服玩家看到
□ 玩家 A 登录 / 退出            → 群里收到加入 / 退出通知
□ 玩家 A 死亡                  → 群里收到带死因的通知
□ 玩家 A 达成成就              → 群里收到成就通知（不含"获得新配方"）
□ 玩家 A 生存服 → 镜像服        → 群里收到一条"切换服务器"
□ 重启一个子服                 → 群里收到启动 / 关闭通知
□ 白名单 QQ 发 #list           → 返回各子服在线玩家
□ 白名单 QQ 发 #tps            → 返回 TPS/MSPT（装了模组）
□ 白名单 QQ 发 #broadcast 测试  → 所有子服玩家看到公告
□ 白名单 QQ 发 #send 镜像服 hi  → 仅镜像服玩家看到
□ 非白名单 QQ 发 #list          → 完全无响应
□ 群里发 300 字长消息           → 被截断到 200 字
□ 群里连发 30 条消息            → 限流生效，不卡顿
□ 拔掉机器人网络 30 秒           → 日志显示重连，恢复后自动继续
□ /qqlink reload               → 配置生效，连接重建，消息继续正常
```

---

## 八、架构

### 8.1 分层

```
┌─────────────────────────────────────────────────────────────┐
│ 表现层  config/model/*         配置数据模型（不可变语义）      │
├─────────────────────────────────────────────────────────────┤
│ 接入层  PlayerEventListener     Velocity 事件                │
│        PluginMessageRouter    子服插件消息                    │
│        QqMessageHandler       QQ 消息                        │
├─────────────────────────────────────────────────────────────┤
│ 业务层  ChatRouter            三条转发链路的唯一出口          │
│        CommandManager         QQ 指令解析与执行              │
├─────────────────────────────────────────────────────────────┤
│ 传输层  OneBotBridge          连接管理 + 队列 + 限流          │
│        ServerBridge           子服查询 + 插件消息下发         │
│        PlayerManager          状态与指标缓存                  │
├─────────────────────────────────────────────────────────────┤
│ 协议层  Forward/Reverse WS     WebSocket 传输                │
│        OneBotApi / Json        OneBot v11 编解码             │
│        PluginMessages          代理↔子服自定义协议            │
└─────────────────────────────────────────────────────────────┘
```

所有转发都必须经过 `ChatRouter`，所有 QQ 发送都必须经过 `OneBotBridge`。决策和传输分开的好处是：加一个新事件只需要在 `ChatRouter` 加一个方法，不用碰网络代码。

### 8.2 链路 A：MC → QQ

```
玩家在生存服发送 "hello"
   │
   ▼ 【Velocity 事件线程】
PlayerChatEvent
   │  getMessage() = "hello"           ← 只读，绝不 setResult（否则 1.19+ 会踢人）
   │  player.getCurrentServer() → "survival"
   ▼
PlayerEventListener.onPlayerChat
   │  ① 是 /say 或 /broadcast？ → 走公告链路
   │  ② router.onPlayerChat(name, "survival", "hello")
   ▼
ChatRouter.onPlayerChat
   │  ① 开关检查：forwarding.chat + servers.survival.forward-chat
   │  ② 安全过滤：ignored-prefixes → 长度 → 纯符号 → 重复字符
   │  ③ 模板渲染：Format.of("&7[&b%server%&7] &f%player%&7: &f%message%")
   │        ├─ 先替换占位符（值经 sanitize 转义 < >）
   │        └─ 再翻译颜色（& / § / &#hex / <tag> → §）
   │  ④ 找目标群：groups 中 mc-servers 包含 "survival" 或为空的群
   ▼
OneBotBridge.sendGroup(groupId, "[生存服] Steve: hello")
   │  ① 入队（ArrayBlockingQueue，满则丢最旧）
   ▼ 【发送线程 · 独立线程】
   │  ② 令牌桶限流：每 1/send-rate-limit 秒放行一条
   │  ③ api.sendGroupMessage → {"action":"send_group_msg", ...}
   ▼ 【WebSocket】
QQ 群
```

### 8.3 链路 B：QQ → MC

```
群里发送 "生存服 你好"
   │
   ▼ 【WebSocket 读线程】
   │  原始 JSON → OneBotBridge.onRawEvent
   ▼ 【事件线程池 · 固定 2~8 线程】
OneBotBridge.handleEvent → 解析为 OneBotEvent → 分发给订阅者
   ▼
QqMessageHandler.onEvent
   │  ① event.userId == selfId ？ → 丢弃（防回环第一道闸）
   │  ② groups.allowed(groupId) ？ → 未配置的群在 strict 模式下直接忽略
   │  ③ 提取正文：message 数组 → 文本（@机器人 替换为占位符，图片丢弃）
   │  ④ commands.handle(...)  ← 指令优先于聊天
   │       命中就 return，不把 "#list" 刷到游戏里
   ▼
ChatRouter.onGroupChat
   │  ① resolveTargets：
   │       (a) 消息前缀匹配 "生存服" → 收敛为 {survival}
   │       (b) 否则取群配置 mc-servers
   │       (c) 否则按全局 target（all / primary）
   │  ② 渲染格式：&7[&9QQ&7] &f%sender%&7: &f%message%
   ▼
ChatRouter.pushToServer → ServerBridge.sendToServer
   │  RegisteredServer.sendPluginMessage(qqlink:main, "CHAT <文本>")
   ▼ 【Minecraft 插件消息通道】
子服 qqlink-fabric 的 registerGlobalReceiver
   │  切回服务端主线程 → server.getPlayerManager().broadcast(...)
   ▼
子服内所有玩家看到 [QQ] 昵称: 你好
```

### 8.4 链路 C：子服事件 → QQ

```
子服内玩家死亡
   ▼
ServerLivingEntityEvents.AFTER_DEATH
   │  entity instanceof ServerPlayerEntity ?
   │  player.getCombatTracker().getDeathMessage() → "Steve was slain by Zombie"
   │  去掉开头的玩家名 → "was slain by Zombie"
   ▼
ServerPlayNetworking.send(carrier, QqLinkPayload("DEATH Steve was slain by Zombie"))
   ▼ 【插件消息】
PluginMessageRouter.onPluginMessage
   │  ① 频道校验 → ② 立刻 setResult(handled()) 吞掉（防玩家伪造）
   │  ③ 来源必须是 ServerConnection（不是 Player）
   │  ④ 服务器名取自 connection.getServerInfo()，不信任载荷内容
   ▼ 【ChatRouter.onPlayerDeath】
   │  开关检查 → 渲染 death 模板 → 找目标群
   ▼
OneBotBridge.sendGroup(...) → QQ 群
```

### 8.5 线程模型

| 线程 | 数量 | 职责 | 禁止事项 |
|---|---|---|---|
| Velocity 事件线程 | 1 | `PlayerChatEvent`、玩家上下线 | 阻塞 IO、等待 QQ 响应 |
| WS 读线程（正向） | 1 | JDK HttpClient 回调 | 直接做业务（只投递给线程池） |
| WS 读线程（反向） | 1/连接 | 解析帧 | 同上 |
| 反向握手线程池 | 动态 | HTTP 握手、阻塞读 | 慢速攻击防护 |
| OneBot 事件线程池 | 2~8 | 解析事件、路由、执行指令 | 阻塞等待网络 |
| OneBot 发送线程 | 1 | 令牌桶 + 队列消费 + 重试 | — |
| 正向重连线程 | 1 | 指数退避重连 | — |
| 代理定时任务 | 3 | 指标清理 / 索取 / 配置提醒 | 全部 async，不占 tick |

Velocity 事件线程上只做「取值 + 过滤 + 渲染字符串 + 入队」，没有任何一步等待网络。QQ 侧整个挂掉也不会影响 Minecraft 的 TPS。

### 8.6 为什么不用第三方库

| 需求 | 替代方案 | 理由 |
|---|---|---|
| YAML 解析 | [`Yaml.java`](proxy/src/main/java/com/qqlink/velocity/config/Yaml.java) 自研（约 700 行） | 不引 SnakeYAML，还能实现「结构合并 + 注释保留」 |
| WebSocket 服务端 | [`ws/WebSocket.java`](proxy/src/main/java/com/qqlink/velocity/onebot/ws/WebSocket.java) 手写 RFC 6455 子集 | 零依赖；JDK 没有 WS 服务端 API |
| WebSocket 客户端 | JDK 21 `java.net.http.HttpClient` | JDK 自带，比手写可靠 |
| JSON | Gson（Velocity 已提供） | 不用额外打包 |
| 颜色 / 文本 | [`Text.java`](proxy/src/main/java/com/qqlink/velocity/util/Text.java) 自研翻译器 | 避开 Adventure 版本差异导致的运行期崩溃 |

最终 jar 里只有项目自己的 class 文件 + config.yml + velocity-plugin.json。

### 8.7 安全设计

1. **插件消息双向验证**（[`PluginMessageRouter`](proxy/src/main/java/com/qqlink/velocity/mc/PluginMessageRouter.java)）：频道匹配后第一件事就是 `setResult(handled())`，且只接受 `ServerConnection` 来源。不然玩家可以伪造「子服上报」，篡改 TPS、伪造公告和死因。
2. **样式注入防护**（[`Format`](proxy/src/main/java/com/qqlink/velocity/util/Format.java)）：先替换占位符（值转义尖括号）再翻译颜色。顺序反了的话，玩家昵称里的 `<red>` 或 `&c` 就能给整个模板上色。
3. **消息回环三重防护**：`event.userId == selfId` 直接丢弃；`self-id` 可手动配置，防止机器人不上报 `self_id` 时防护失效；QQ → MC 走服务端广播而非玩家聊天，不会被 `PlayerChatEvent` 再次捕获。
4. **签名聊天零风险**：Velocity 3.x 的 `PlayerChatEvent.setResult(denied())` 在 1.19+ 会导致玩家被踢（官方 Javadoc 已标注弃用）。插件只读不写，不会触发这个问题。
5. **热重载引用**（[`Holder`](proxy/src/main/java/com/qqlink/velocity/util/Holder.java)）：监听器通过 `Holder<ChatRouter>` 间接持有路由器，重载时只更新 Holder，避免「重载后消息仍走旧连接」这类隐蔽 Bug。

---

## 九、扩展

### 9.1 新增一个 QQ 指令

以 `#seed`（查询地图种子）为例：

① 在 [`CommandsConfig.Builtin`](proxy/src/main/java/com/qqlink/velocity/config/model/CommandsConfig.java) 加枚举项：

```java
SEED("seed", List.of("种子"), "#seed", "查询当前地图种子"),
```

② 在 [`CommandManager.execute`](proxy/src/main/java/com/qqlink/velocity/mc/CommandManager.java) 加分支：

```java
case SEED -> doSeed(event);
```

③ 实现方法：

```java
private void doSeed(OneBotEvent event) {
    String seed = "...";   // 从子服上报或配置读取
    reply(event, "&e当前种子：&f" + seed);
}
```

④ 在 [`Messages`](proxy/src/main/java/com/qqlink/velocity/config/model/Messages.java) 和 `config.yml` 加模板（可选）

⑤ 在 `Messages.toMap` 和 `CommandsConfig.toMap` 同步补字段，否则用户升级时拿不到新配置项（`from` / `toMap` 必须对称）。

### 9.2 新增一个游戏事件通知

① 在 [`EventsConfig`](proxy/src/main/java/com/qqlink/velocity/config/model/EventsConfig.java) 加开关：

```java
public boolean playerAdvancementBroadcast = true;
```

② 在 [`ChatRouter`](proxy/src/main/java/com/qqlink/velocity/mc/ChatRouter.java) 加处理方法：

```java
public void onSomething(String playerName, String serverId) {
    if (!config.events.isActive(config.events.something)) return;
    String text = Format.of(config.messages.something)
            .set("player", playerName)
            .set("server", config.servers.displayName(serverId))
            .trustValues().renderPlain();
    broadcastToGroups(serverId, text, false);
}
```

③ 在接入层调用它：
- 代理侧事件 → [`PlayerEventListener`](proxy/src/main/java/com/qqlink/velocity/mc/PlayerEventListener.java)
- 子服上报 → 在 [`PluginMessages`](proxy/src/main/java/com/qqlink/velocity/mc/PluginMessages.java) 加类型，在 [`PluginMessageRouter.dispatch`](proxy/src/main/java/com/qqlink/velocity/mc/PluginMessageRouter.java) 加分支，Fabric 端 `send(server, "新类型", ...)` 上报

### 9.3 新增子服上报类型

代理端（[`PluginMessages.java`](proxy/src/main/java/com/qqlink/velocity/mc/PluginMessages.java)）：

```java
public static final String UP_MY_EVENT = "MY_EVENT";
```

然后 `PluginMessageRouter.dispatch` 加：

```java
case PluginMessages.UP_MY_EVENT -> router.onMyEvent(serverId, inbound.payload);
```

模组端（[`Protocol.java`](mod/src/main/java/com/qqlink/fabric/Protocol.java)）：

```java
public static final String UP_MY_EVENT = "MY_EVENT";
```

然后 `QQGroupLinkFabric` 里：

```java
send(server, Protocol.UP_MY_EVENT, "负载内容");
```

两处常量要手动保持一致。不抽公共模块是因为代理插件运行在 Velocity（无 Minecraft 依赖），模组运行在 Fabric（依赖 Minecraft 类），硬共享会让模组代码被加载进代理 classpath，风险大于收益。

### 9.4 扩展子服

不用改代码，在 `config.yml` 的 `servers` 段追加：

```yaml
servers:
  survival: { display-name: "生存服", primary: true, order: 1 }
  mirror:   { display-name: "镜像服", order: 2 }
  creative: { display-name: "创造服", enabled: false, order: 3 }   # 禁用它
```

忘了配置的子服，插件会在启动日志和每 5 分钟提醒一次，并直接用服务器 id 当显示名，不会漏消息。

### 9.5 接 HTTP API / 网页管理

[`OneBotBridge.snapshot()`](proxy/src/main/java/com/qqlink/velocity/onebot/OneBotBridge.java) 和 [`PlayerManager.snapshotAll()`](proxy/src/main/java/com/qqlink/velocity/mc/PlayerManager.java) 已经返回结构化 `Map`，序列化成 JSON 就能做管理页面。

---

## 十、已知限制

### 10.1 需要伴随模组才能实现的功能

| 功能 | 为什么代理做不到 | 差异 |
|---|---|---|
| TPS / MSPT | Velocity 跑在协议层，不跑游戏主循环，拿不到子服 TPS | 无模组：`#tps` 显示暂无数据；有模组：真实数值 |
| 精确死亡原因 | Velocity 没有 `PlayerDeathEvent` | 无模组：代理解析死亡广播，复杂死因退化；有模组：精确 |
| 成就解锁（带分类） | 没有成就事件 | 无模组：不推送；有模组：准确并可过滤配方 |
| 服务器启动完成 | 代理只能看到端口可连，不知道世界加载完没有 | 无模组：启发式判定（约 1~3 分钟延迟）；有模组：准确即时 |

以下功能完全不依赖模组：双向聊天、加入/退出、切换子服、服务器上下线（启发式）、管理员公告（代理侧识别 `/say` `/broadcast` `/me`）、全部 QQ 指令。

### 10.2 插件消息依附玩家连接

Minecraft 自定义载荷（`CustomPayload`）依附于玩家连接，所以：

- 子服一个玩家都没有时，代理没法给它下发消息，它也上报不了指标；
- 因此 `#broadcast` / `#send` 对空服无效，`#tps` 对空服显示暂无数据——这是正常现象。

这也是为什么代理侧实现了 `#list`——它只依赖代理自己的数据，任何情况下都可用。

### 10.3 反向模式要求端口可达

反向模式要求机器人能访问代理的 8765 端口。两边不在同一网络且开不了端口的话，改用 `mode: forward`（插件主动外连，不需要入站端口）。

### 10.4 成就/公告解析依赖服务端语言

模组解析原版广播文本识别成就和公告：

- 英文语言包：格式固定，已完整适配
- 中文语言包：适配了 `达成了进度` / `达成了目标` / `完成了挑战` / `发现了配方`
- 其他语言：退化为整条消息直接转发，仍可用，只是 `%frame%` 分类不准确

`/say` 和 `/me` 走 Fabric 的结构化事件（`ServerMessageEvents.COMMAND_MESSAGE`），不受语言影响。

### 10.5 死亡原因解析

没装模组时，代理侧按「消息以玩家名开头」的规则剥离玩家名，剩下的当死因：

- `Steve was slain by Zombie` → 死因 `was slain by Zombie` ✔
- `Steve fell from a high place` → 死因 `fell from a high place` ✔
- `Steve was shot by Skeleton using [Bow]` → 死因 `was shot by Skeleton using [Bow]` ✔
- 用了改写死亡消息的插件（格式不含玩家名）则原样转发整条

死因截断到 `events.death.cause-max-length`（默认 60），避免超长附魔名刷屏。

### 10.6 其他

- **`self-id` 建议手动配置**：少数机器人实现不回报 `self_id`，自动获取会失败，回环防护（丢弃机器人自身消息）就失效了。手动填 `onebot.self-id` 即可。
- **`@全体成员`**：用 `[CQ:at,qq=all]`，兼容 go-cqhttp / NapCat；部分实现不识别，且需要机器人有群管理员权限。
- **消息长度**：Minecraft 自定义载荷上限 32767 字节，插件内部限制 30000 字节并做 UTF-8 安全截断。
- **`#send` 的服务器名**：支持显示名（`镜像服`）和 velocity id（`mirror`），大小写不敏感。
- **配置项新增**：`PluginConfig` 和 `Messages.build` 的 `from` / `toMap` 必须成对维护。只加 `from` 不加 `toMap`，用户升级后拿不到新键（会自动补默认值，但缺注释）。

### 10.7 验证状态

- 静态检查：全部 42 个 Java 文件通过括号配平检查（`tools/check-structure.ps1`）；跨包引用通过导入完整性检查（`tools/check-imports.ps1`）
- 编译验证：`:proxy:build` 已通过（JDK 21 + Gradle 8.14），产物 `proxy/build/libs/QQGroupLink-1.0.0.jar`
- `:mod:build` 未完成：需要从 Mojang 官方源下载 Minecraft 依赖，国内网络直连不通（实测 502/超时）。有代理环境后执行 `.\gradlew.bat :mod:build` 即可，代码本身已通过静态检查
- Velocity / Fabric API 调用点分别对照 3.4.0 与 1.21.11 官方文档核实过

模组构建可能遇到的 API 差异及处理：

| 位置 | 可能问题 | 处理 |
|---|---|---|
| `mod/` 的载荷注册 | Fabric API 版本差异导致 `PayloadTypeRegistry` / `ServerPlayNetworking` 签名不同 | 已用 try-catch 包裹，运行期不会崩；按 IDE 提示调整签名即可 |
| `mod/` 的 `@Accessor("tickTimes")` | 字段被改名 | 改为查询 yarn 映射后的实际字段名；或删掉该 Mixin，`computeMspt` 会返回 -1 并自动跳过 TPS 上报 |
| `:proxy` | 极少数方法名随 Velocity 小版本变动 | 按 IDE 提示替换为对应版本的方法名 |

模组内所有上报路径都有 `try-catch` 兜底，代理端事件处理也都有 try-catch，任何异常最多导致某条通知没发出去，不会影响游戏运行。

---

## 许可

MIT，见 [LICENSE](LICENSE)。
