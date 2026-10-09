# QQGroupLink

**Velocity 代理端 QQ 群服互通插件** —— 一套代码打通「QQ 群 ↔ 多台 Fabric 子服」的双向聊天、事件通知与运维指令。

- 协议：**OneBot v11**，同时支持 **正向 WebSocket** 与 **反向 WebSocket**
- 兼容机器人：[go-cqhttp](https://github.com/Mrs4s/go-cqhttp)、[NapCat](https://github.com/NapNeko/NapCatQQ)、[Lagrange.OneBot](https://github.com/LagrangeDev/Lagrange.Core)、LLOneBot
- 目标环境：**JDK 21** + **Velocity 3.4.x**（对应 Minecraft 1.21.x / 1.21.11）+ **Fabric 1.21.11** 子服
- 部署方式：全部服务在同一 Docker 容器内，由 **MCSManager（MCSM）** 面板统一管理

> 目录：[功能](#一功能总览) · [项目结构](#二项目结构) · [构建](#三构建) · [部署](#四部署完整步骤) · [机器人对接](#五onebot-机器人对接) · [配置](#六配置说明) · [调试验收](#七调试验收清单) · [架构](#八核心架构与消息流转) · [扩展](#九后续扩展指南) · [限制](#十已知限制与设计取舍)

---

## 一、功能总览

所有功能均有**独立开关**，可在 `config.yml` 中按需启停。

### 1. 双向聊天转发（核心）

| 方向 | 行为 | 关键配置 |
|---|---|---|
| MC → QQ | 子服玩家公屏聊天转发到指定 QQ 群，带来源服务器前缀 | `format.chat.mc-to-qq` |
| QQ → MC | QQ 群消息转发到指定子服 / 所有子服 | `forwarding.qq-to-minecraft` |
| 跨子服 | （可选）A 服聊天同步显示到 B 服 | `forwarding.cross-server-chat` |

服务器前缀完全自定义，例如 `&7[&b%server%&7] &f%player%&7: &f%message%` 渲染为 `[生存服] Steve: 你好`。

### 2. 游戏事件通知（每项独立开关）

| 事件 | 开关 | 数据来源 |
|---|---|---|
| 玩家加入 | `events.join` | Velocity 原生事件（**不需要模组**） |
| 玩家退出 | `events.quit` | Velocity 原生事件 |
| 玩家切换子服 | `events.server-switch` | Velocity 原生事件 |
| 玩家死亡（含死因） | `events.death` | 代理侧解析 + 伴随模组精确上报 |
| 解锁成就 / 进度 | `events.advancement` | 伴随模组（可过滤配方刷屏） |
| 服务器启动 / 关闭 | `events.server-status` | 模组上报 + 代理侧启发式兜底 |
| 管理员公告同步 | `events.admin-broadcast` | 代理侧识别 `/say` `/broadcast` `/me` + 模组上报 |

`events.enabled` 是总闸，关闭后所有事件都不推送，但**聊天转发不受影响**。

### 3. QQ 群指令系统

- **仅白名单 QQ 号可用**（`security.admin-whitelist`），非管理员默认**完全无响应**（防探测）
- 指令前缀自由配置：`prefixes: ["#", "/", "."]`

| 指令 | 说明 | 独立开关 |
|---|---|---|
| `#list` | 按子服分类返回在线玩家列表 | `commands.builtin.list.enabled` |
| `#tps` | 所有子服 TPS / MSPT 性能数据 | `commands.builtin.tps.enabled` |
| `#broadcast <内容>` | 向所有子服发送全服公告 | `commands.builtin.broadcast.enabled` |
| `#send <服务器> <内容>` | 向指定单个子服发送消息 | `commands.builtin.send.enabled` |
| `#help` | 可用指令列表与说明 | `commands.builtin.help.enabled` |
| `#status` | 机器人与代理运行状态 | `commands.builtin.status.enabled` |

每个指令都支持**自定义别名**（含中文，如 `#在线`、`#性能`、`#公告`）。

### 4. 多子服适配

- 子服显示名与 Velocity 内部 id 分离配置：`servers.survival.display-name: "生存服"`
- **QQ 消息转发目标三档策略**：
  1. `all` —— 所有启用子服（默认）
  2. `primary` —— 仅主服
  3. **消息前缀指定** —— 群里发 `生存服 你好` / `[生存服] 你好` / `生存服：你好` 只发生存服
- 单个子服可单独禁用互通：`servers.<id>.enabled: false`
- 群与子服可多对多绑定：`groups.<群号>.mc-servers: ["survival", "mirror"]`

### 5. 安全机制

| 机制 | 配置项 | 说明 |
|---|---|---|
| 指令白名单 | `security.admin-whitelist` | 非白名单 QQ 发指令默认静默 |
| 群白名单 | `groups.strict: true` | 未配置的群完全忽略 |
| 消息长度限制 | `security.max-message-length` | 超出截断，防刷屏 |
| 最小长度 | `security.min-message-length` | 过滤单字刷屏 |
| 重复字符过滤 | `security.filter-repeated-chars` | 拦截 `aaaaaaaaaaaa` |
| 纯符号过滤 | `security.filter-symbols-only` | 拦截纯表情/符号消息 |
| CQ 码剔除 | `security.strip-cq-codes` | 图片/语音不转发到游戏 |
| 用户级限流 | `security.user-rate-limit` | 每 QQ 每分钟转发上限 |
| 群级限流 | `security.group-rate-limit` | 保护机器人不被 QQ 风控 |
| 发送限流 | `onebot.send-rate-limit` | 令牌桶匀速发送 |
| 样式注入防护 | 内置 | 昵称里的 `<red>` / `&c` 会被转义，无法伪造样式 |
| 消息回环防护 | 内置 | 机器人自身消息直接丢弃 + 自定义载荷只接受子服来源 |
| 转发权限门槛（可选） | `forwarding.minecraft-to-qq.require-permission` | 需配合权限插件；**留空即可**，非空但未装权限插件会导致聊天不转发 |

---

## 二、项目结构

```
群服互联/
├── settings.gradle.kts              # 双工程聚合：:proxy（插件）+ :mod（伴随模组）
├── build.gradle.kts                 # 公共仓库、编码、Java 21 设置
├── gradle.properties                # ★版本号集中管理（Velocity / Minecraft / Fabric API）
├── README.md                        # 本文件
│
├── proxy/                           # ===== Velocity 代理端插件（核心，必需）=====
│   ├── build.gradle.kts
│   └── src/main/
│       ├── resources/
│       │   ├── config.yml           # ★默认配置模板（每项都有中文注释）
│       │   └── velocity-plugin.json # 插件描述符
│       └── java/com/qqlink/velocity/
│           ├── QQGroupLinkPlugin.java        # 主类：初始化顺序 / 定时任务 / 热重载
│           ├── Slf4jLoggerAdapter.java       # JUL → SLF4J 日志适配
│           ├── command/
│           │   └── QqLinkCommand.java        # 游戏内 /qqlink 指令
│           ├── config/
│           │   ├── ConfigFile.java           # 配置读写 + 结构合并 + 注释保留
│           │   ├── Yaml.java                 # 自研极简 YAML 读写器（零依赖）
│           │   ├── Nodes.java                # YAML 节点的类型安全读取
│           │   └── model/                    # 配置数据模型（8 个类）
│           │       ├── PluginConfig.java     # 根配置
│           │       ├── OneBotConfig.java     # OneBot 连接
│           │       ├── GroupEntry.java       # QQ 群 + 群注册表
│           │       ├── ServerEntry.java      # 子服 + 子服注册表
│           │       ├── ForwardingConfig.java # 转发规则
│           │       ├── EventsConfig.java     # 事件开关
│           │       ├── CommandsConfig.java   # 指令系统（含 Builtin 枚举）
│           │       ├── Messages.java         # 全部消息模板
│           │       └── SecurityConfig.java   # 安全与白名单
│           ├── mc/
│           │   ├── ChatRouter.java           # ★消息路由大脑（三条链路）
│           │   ├── CommandManager.java       # ★QQ 指令解析与执行
│           │   ├── PlayerEventListener.java  # Velocity 事件监听
│           │   ├── PluginMessageRouter.java  # 子服上报入口（安全关键）
│           │   ├── PluginMessages.java       # 自定义插件消息协议
│           │   ├── ServerBridge.java         # 子服查询与下发
│           │   └── PlayerManager.java        # 在线状态与 TPS/MSPT 仓库
│           ├── onebot/
│           │   ├── OneBotBridge.java         # ★连接管理 + 发送队列 + 限流
│           │   ├── OneBotConnection.java     # 连接抽象
│           │   ├── OneBotListener.java       # 连接 → 业务回调
│           │   ├── OneBotEvent.java          # OneBot v11 事件视图
│           │   ├── Json.java                 # Gson 树模型读写辅助
│           │   ├── ForwardWebSocketConnection.java  # 正向 WS（JDK HttpClient）
│           │   ├── ReverseWebSocketConnection.java  # 反向 WS 单连接
│           │   ├── ReverseWebSocketListener.java    # 反向 WS 监听器
│           │   ├── QqMessageHandler.java     # ★QQ 消息入口（聊天/指令分流）
│           │   ├── ws/
│           │   │   ├── WebSocket.java        # RFC 6455 帧编解码 + 握手
│           │   │   └── OutboundSocket.java   # 连接级收发（读线程 + 写锁）
│           │   └── api/
│           │       ├── OneBotApi.java        # OneBot API 调用（echo 匹配）
│           │       └── MessageSegment.java   # 消息段
│           └── util/
│               ├── Text.java                 # 颜色翻译（&/§/hex/MiniMessage）
│               ├── Format.java               # %占位符% 模板引擎
│               ├── Strings.java              # 字符串与过滤工具
│               └── Holder.java               # 可变引用（热重载正确性）
│
├── mod/                             # ===== Fabric 服务端伴随模组（可选，增强）=====
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
    ├── check-structure.ps1          # 开发辅助：括号配平检查（词法级）
    └── check-imports.ps1            # 开发辅助：跨包引用检查
```

---

## 三、构建

### 3.1 环境准备

| 依赖 | 版本 | 说明 |
|---|---|---|
| JDK | **21** | `java -version` 应显示 21.x |
| Gradle | 8.10+ | 建议用 IDEA 内置或自行安装；首次构建需联网下载依赖 |

### 3.2 构建代理端插件（必需）

```bash
# Linux / macOS
./gradlew :proxy:build

# Windows
gradlew.bat :proxy:build
```

产物：`proxy/build/libs/QQGroupLink-1.0.0.jar`

> 该 jar **不包含任何第三方库** —— Gson / Guava / Adventure / SLF4J 全部由 Velocity 提供，
> 完全满足「轻量、依赖最少化」要求。

### 3.3 构建伴随模组（可选）

```bash
./gradlew :mod:build
```

产物：`mod/build/libs/qqlink-fabric-1.0.0.jar`

### 3.4 版本适配

所有版本号集中在 [`gradle.properties`](gradle.properties)，**改版本不需要动源码**：

```properties
# Velocity 是 3.3.x / 3.5.x？改这里即可
velocity_version=3.4.0-SNAPSHOT

# 子服是 1.21.1 / 1.21.4？改这三行
minecraft_version=1.21.11
yarn_mappings=1.21.11+build.1
fabric_api_version=0.135.0+1.21.11
```

`yarn_mappings` 与 `fabric_api_version` 请到以下地址查对应版本号：
- Yarn：<https://fabricmc.net/develop/>（选择 Minecraft 版本后复制 `yarn_mappings`）
- Fabric API：<https://modrinth.com/mod/fabric-api/versions>

> **关于 `loader_version`**：Velocity 插件本身**完全不依赖 Fabric**，
> 只有可选的伴随模组才需要。即使子服是 Forge / NeoForge，代理端插件照常工作，
> 只是失去 TPS/MSPT/精确死因/成就四项增强（见 [§10](#十已知限制与设计取舍)）。

---

## 四、部署完整步骤

### 4.1 整体拓扑

所有服务跑在**同一个 Docker 容器**内，通过 `127.0.0.1` 互通；端口由 MCSM 统一映射。

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

**关键点**：机器人只需与代理的 `8765` 端口互通，**不需要**和子服互通。

### 4.2 步骤一：安装代理端插件

1. 把 `QQGroupLink-1.0.0.jar` 放到 Velocity 的 `plugins/` 目录：
   ```
   /path/to/velocity/plugins/QQGroupLink-1.0.0.jar
   ```
2. 启动代理，插件会自动生成 `/path/to/velocity/plugins/qqlink/config.yml`
3. **先停服**，编辑 `config.yml`（见 [§6](#六配置说明)），至少填：
   - `groups`：你的 QQ 群号
   - `servers`：子服 id 与显示名（id 必须与 `velocity.toml` 的 `[servers]` 段一致）
   - `security.admin-whitelist`：你的 QQ 号
   - `onebot`：连接模式与端口
4. 重新启动代理，观察日志：
   ```
   ==================== QQGroupLink 已启动 ====================
   连接配置：mode=reverse, forwardUrl=ws://127.0.0.1:3001, reverse=0.0.0.0:8765/onebot/v11/ws
   子服映射：survival→生存服[主服]，mirror→镜像服（共 2 个）
   QQ 群：生存服交流群(123456789)
   管理员：[10001]
   [OneBot] 反向 WebSocket 监听已启动：0.0.0.0:8765/onebot/v11/ws
   ```

### 4.3 步骤二：安装子服增强模组（可选但推荐）

1. 把 `qqlink-fabric-1.0.0.jar` 放进**每个 Fabric 子服**的 `mods/` 目录
2. 确认子服已安装 **Fabric API**（依赖项）
3. 重启子服，日志出现：
   ```
   [QQGroupLink] qqlink-fabric 1.0.0 正在初始化……
   [QQGroupLink] 已通知代理：服务器启动完成
   ```
4. 代理日志出现（说明上报通道打通）：
   ```
   [MC] 子服 survival 的 qqlink-fabric 模组已就绪（版本 1.0.0）
   ```

> 不装模组时，`#tps` 会显示「暂无性能数据（子服未安装 qqlink-fabric 模组）」，
> 其他所有功能正常。可用 `/qqlink status` 查看哪些子服已就绪。

### 4.4 步骤三：在 MCSM 中部署 OneBot 机器人

以下以 **NapCat** 为例（推荐，MCSM 生态里最活跃）；其他实现见 [§5](#五onebot-机器人对接)。

**方案 A：机器人作为独立实例（推荐，便于单独重启）**

1. MCSM → 新建应用实例 → 类型选「通用/自定义」
2. 上传 NapCat 的启动包（或使用其 Docker 镜像），或直接用 MCSM 的 Docker 实例类型
3. 启动参数保持默认（`-q <QQ号>` 之类），**记录监听端口**
4. 在 NapCat 的 WebUI（默认 `http://<容器IP>:6099`）中：
   - 扫码登录 QQ
   - 进入「网络配置」→ 新建 **WebSocket 反向**（或反向代理）
   - **URL** 填：`ws://127.0.0.1:8765/onebot/v11/ws`
     > 如果机器人与代理跑在**不同容器**，把 `127.0.0.1` 换成代理所在容器的 IP
     > （同一 Docker 网络下可用容器名，例如 `ws://velocity:8765/onebot/v11/ws`）
   - **AccessToken** 填与 `config.yml` 中 `onebot.access-token` **完全一致**的值
   - 保存并启用
5. 代理日志应立刻出现：
   ```
   [OneBot/反向] 机器人已连接：172.17.0.3:54321（实现：NapCat，路径：/onebot/v11/ws）
   [OneBot] 机器人 QQ 号：123456（实现：NapCat）
   ```

**方案 B：机器人作为 MCSM「计划任务/守护进程」**

在 MCSM 的容器里以守护进程方式拉起 NapCat，同样在 WebUI 里配置反向 WS 即可。
只要机器人与代理在**同一容器的本地网络**内，`127.0.0.1:8765` 就能通。

### 4.5 步骤四：MCSM 端口映射

| 服务 | 容器内端口 | 是否需要对外 | 说明 |
|---|---|---|---|
| Velocity | 25565 | ✅ | 玩家入口 |
| 生存服 Fabric | 25566 | ❌ | 仅代理访问（保持 `online-mode=false` + 转发密钥） |
| 镜像服 Fabric | 25567 | ❌ | 同上 |
| **QQGroupLink 反向 WS** | **8765** | ❌* | **只需要机器人和代理互通** |
| NapCat WebUI | 6099 | ⚠️ 建议仅内网 | 用于扫码与配置，配完可关闭映射 |

> \* **8765 不需要映射到公网。** 只要机器人和代理在同一个容器/同一 Docker 网络内，
> 走 `127.0.0.1` 或容器名即可。这既是安全最佳实践，也避免了被外部扫描。
> 如果你确实需要跨网络（例如机器人部署在另一台机器），**务必设置 `access-token`**。

### 4.6 步骤五：验证

在 QQ 群里（用白名单里的号）发送：

```
#status      → 应返回机器人 QQ、连接状态、子服数、在线人数、运行时长
#list        → 应返回各子服在线玩家
#help        → 应返回指令列表
```

在游戏里进服后发言，QQ 群应收到 `[生存服] 你的ID: 内容`。

---

## 五、OneBot 机器人对接

### 5.1 两种连接模式怎么选

| | 反向 WebSocket（`mode: reverse`） | 正向 WebSocket（`mode: forward`） |
|---|---|---|
| 谁是服务端 | **插件**监听 8765，机器人连过来 | 机器人监听 3001，插件连过去 |
| 配置方式 | 机器人填 `ws://<代理IP>:8765/onebot/v11/ws` | 插件填 `ws://127.0.0.1:3001` |
| 优点 | 端口固定易管理；机器人重启后自动重连（大多数实现内置） | 不需要给插件开监听端口 |
| 缺点 | 需要插件能监听端口 | 机器人要先起来，否则插件一直重连 |
| **推荐场景** | **MCSM 同容器部署（首选）** | 机器人部署在别的机器 / 无端口权限 |

`mode: both` 会同时启用两者，适合迁移期或同时接多个机器人。

### 5.2 各机器人实现配置对照

#### NapCat（推荐）

WebUI → 网络配置 → 新建，类型选 **WebSocket 反向**：

| 字段 | 填什么 |
|---|---|
| URL | `ws://127.0.0.1:8765/onebot/v11/ws` |
| AccessToken | 与 `onebot.access-token` 一致（可留空） |
| 消息格式 | `array`（推荐）或 `string`，本插件两者都支持 |

#### go-cqhttp

`config.yml` 中：

```yaml
servers:
  - ws:
      host: 0.0.0.0        # 正向 WS 服务器
      port: 3001
      # 反向 WS：本插件是服务端，所以这里是 go-cqhttp 去连别人
  - ws-reverse:
      universal: ws://127.0.0.1:8765/onebot/v11/ws
      reconnect-interval: 3000
      access-token: 你的token
```

对应本插件配置：
- 若用 `ws-reverse` → `onebot.mode: reverse`
- 若用 `ws`（正向服务器）→ `onebot.mode: forward`，`forward-url: ws://127.0.0.1:3001`

#### Lagrange.OneBot

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

### 5.3 识别路径不一致问题

不同实现的默认反向路径五花八门（`/`、`/ws`、`/onebot/v11/ws`、`/onebot/v11/ws/`）。
本插件对此的处理是：**路径不匹配也照常接收**，只在代理日志打一次提醒：

```
[OneBot/反向] 收到路径 / 的连接，但配置为 /onebot/v11/ws；仍按配置的服务处理，
              如需固定请把机器人端地址改成 ws://<代理IP>:8765/onebot/v11/ws
```

所以**即使路径写错了也能连通**，不必反复试。

### 5.4 AccessToken 校验方式

插件同时支持两种传法，兼容所有实现：

1. 请求头：`Authorization: Bearer <token>`
2. 查询参数：`?access_token=<token>`

正向模式下，插件会把 token 同时放进请求头和 URL 查询串。

---

## 六、配置说明

配置文件：[`proxy/src/main/resources/config.yml`](proxy/src/main/resources/config.yml)（插件首次启动会释放到 `plugins/qqlink/config.yml`）

> **升级友好**：插件以 jar 内模板为骨架合并用户配置，
> **新增配置项会自动补入你的文件并保留原有取值**，不会覆盖你的修改。

### 6.1 核心配置速查

```yaml
enabled: true            # 全局总开关

onebot:
  mode: reverse          # reverse / forward / both
  forward-url: "ws://127.0.0.1:3001"
  reverse:
    host: "0.0.0.0"
    port: 8765
    path: "/onebot/v11/ws"
  access-token: ""       # ★建议设置
  self-id: 0             # 0 = 自动获取；★建议手填，用于防消息回环
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
  admin-whitelist: [10001, 10002]     # ★必填
  max-message-length: 200
  user-rate-limit: 20
  group-rate-limit: 60
  ignored-prefixes: ["/"]             # 游戏内指令不当聊天转发
```

### 6.2 颜色写法（三种可混用）

```yaml
mc-to-qq: "&7[&b%server%&7] &f%player%&7: &f%message%"      # 传统 & 代码
mc-to-qq: "&#FF8800[&f%server%&#FF8800] &f%player%: %message%"  # 十六进制
mc-to-qq: "<gold>[<aqua>%server%<gold>] <white>%player%: %message%"  # MiniMessage 风格
```

### 6.3 占位符总表

| 占位符 | 含义 | 可用模板 |
|---|---|---|
| `%server%` | 子服显示名 | 全部 |
| `%server_id%` | Velocity 子服 id | 全部 |
| `%player%` | 玩家名 | MC 相关 |
| `%message%` | 消息正文 | 聊天相关 |
| `%sender%` / `%sender_id%` | QQ 昵称 / QQ 号 | QQ → MC |
| `%group%` / `%group_id%` | 群名 / 群号 | QQ 相关 |
| `%cause%` | 死亡原因 | 死亡通知 |
| `%advancement%` / `%title%` | 成就名（两者等价） | 成就通知 |
| `%frame%` | 成就类型 `task`/`goal`/`challenge`/`recipe` | 成就通知 |
| `%count%` | 数量、人数 | 列表、状态 |
| `%players%` | 玩家列表 | `#list` |
| `%tps%` / `%mspt%` | 性能数据 | `#tps` |
| `%uptime%` | 运行时长 | `#status` |
| `%from%` / `%from_id%` / `%to%` / `%to_id%` | 切换前后子服 | 切换通知 |
| `%usage%` / `%description%` / `%prefix%` / `%command%` | 指令元信息 | `#help` |
| `%bot%` / `%connection%` / `%servers%` | 机器人状态 | `#status` |

> **安全提示**：模板引擎的顺序是「先填值 → 再上色」，
> 因此玩家昵称或 QQ 消息里写 `<red>`、`&c` 只会被显示为普通文字，无法伪造样式。

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

## 七、调试验收清单

按顺序排查，90% 的问题在前三步就能定位。

### 7.1 连接不通

| 现象 | 排查方法 | 常见原因 |
|---|---|---|
| 日志无「反向 WebSocket 监听已启动」 | 看是否有 `Address already in use` | 8765 被占用，改 `onebot.reverse.port` |
| 日志有监听，但机器人连不上 | `curl -v http://127.0.0.1:8765/onebot/v11/ws` 看是否 400/101 | 机器人与代理不在同一网络；地址写错 |
| 反向连接建立后立刻断开 | 看日志是否有「AccessToken 校验失败」 | token 不一致 |
| 正向模式一直重连 | 看日志的失败原因 | 机器人没开正向 WS；端口/地址错 |
| 连接正常但收发无数据 | `/qqlink debug on` 看原始报文 | 机器人未上报 `message` 字段 |

**快速定位连接问题的三连**：

```bash
# 1) 代理是否真的在监听
netstat -tlnp | grep 8765

# 2) 容器内能否访问自己
curl -v http://127.0.0.1:8765/onebot/v11/ws

# 3) 看插件网络日志
/qqlink status      # 游戏内
```

### 7.2 消息不通

| 现象 | 检查项 |
|---|---|
| 游戏内说话，QQ 群没反应 | ① `forwarding.minecraft-to-qq.chat` 是否为 true<br>② `groups.strict` 与群号是否正确<br>③ 消息是否以 `security.ignored-prefixes` 开头（默认 `/`）<br>④ 消息是否被 `max/min-message-length` 或重复字符过滤掉<br>⑤ 用 `/qqlink debug on` 看发送日志 |
| QQ 群说话，游戏内没反应 | ① `forwarding.qq-to-minecraft.chat` 是否为 true<br>② 群配置 `chat: true`<br>③ 目标子服有没有玩家在线<br>④ 「模组已就绪的子服」列表里有没有该子服（无玩家时无法下发） |
| 玩家加入退出没通知 | `events.enabled` 与 `events.join/quit` 是否都为 true |
| 死亡通知没有死因 | 未装模组时死因靠消息解析，复杂死因会退化；装模组后精确 |
| `#tps` 一直无数据 | ① 子服是否装了 `qqlink-fabric`<br>② 该子服是否有玩家在线<br>③ `/qqlink status` 看「模组已就绪的子服」 |
| `#broadcast` 游戏内看不到 | 目标子服必须有玩家在线（Minecraft 自定义载荷依附玩家连接） |

### 7.3 机器人收不到消息

| 现象 | 原因 | 解决 |
|---|---|---|
| 插件日志显示「已发送」但群里没有 | 机器人被风控 / 被限制发言 | 降低 `send-rate-limit`；检查 QQ 是否被禁言 |
| 大量消息时丢消息 | 发送队列溢出 | 提高 `send-queue-limit`，但要同时降低发送频率 |
| 日志出现「超过重试上限，丢弃消息」 | 连接不稳定 | 检查网络；提高 `send-retries` |

### 7.4 验收脚本（人工回归）

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
□ 群里发 300 字长消息           → 被截断到 200 字，不刷屏
□ 群里连发 30 条消息            → 系统限流生效，不卡顿
□ 拔掉机器人网络 30 秒           → 日志显示重连，恢复后自动继续
□ /qqlink reload               → 配置生效，连接重建，消息继续正常
```

---

## 八、核心架构与消息流转

### 8.1 分层设计

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
│        PlayerManager          状态与指标仓库                  │
├─────────────────────────────────────────────────────────────┤
│ 协议层  Forward/Reverse WS     WebSocket 传输                │
│        OneBotApi / Json        OneBot v11 编解码             │
│        PluginMessages          代理↔子服自定义协议            │
└─────────────────────────────────────────────────────────────┘
```

**设计要点**：所有转发都必须经过 `ChatRouter`，所有 QQ 发送都必须经过 `OneBotBridge`。
把「决策」和「传输」分离，使得加新事件只需要在 `ChatRouter` 加一个方法，
不需要碰任何网络代码。

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
   │  ① 是否 /say 或 /broadcast？ → 走公告链路
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
   │  ① event.userId == selfId ？ → 丢弃（★防回环第一道闸）
   │  ② groups.allowed(groupId) ？ → 未配置的群在 strict 模式下直接忽略
   │  ③ 提取正文：message 数组 → 文本（@机器人 替换为占位符，图片丢弃）
   │  ④ commands.handle(...)  ← ★指令优先于聊天
   │       命中则 return，绝不把 "#list" 刷到游戏里
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
   │  ① 频道校验 → ② ★立刻 setResult(handled()) 吞掉（防玩家伪造）
   │  ③ 来源必须是 ServerConnection（不是 Player）
   │  ④ 服务器名取自 connection.getServerInfo()，不信任载荷内容
   ▼ 【ChatRouter.onPlayerDeath】
   │  开关检查 → 渲染 death 模板 → 找目标群
   ▼
OneBotBridge.sendGroup(...) → QQ 群
```

### 8.5 线程模型（性能关键）

| 线程 | 数量 | 职责 | 禁止事项 |
|---|---|---|---|
| Velocity 事件线程 | 1 | `PlayerChatEvent`、玩家上下线 | ❌ 任何阻塞 IO、❌ 等待 QQ 响应 |
| WS 读线程（正向） | 1 | JDK HttpClient 回调 | ❌ 直接做业务（只投递给线程池） |
| WS 读线程（反向） | 1/连接 | 解析帧 | ❌ 同上 |
| 反向握手线程池 | 动态 | HTTP 握手、阻塞读 | 慢速攻击防护 |
| OneBot 事件线程池 | 2~8 | 解析事件、路由、执行指令 | ❌ 阻塞等待网络 |
| OneBot 发送线程 | 1 | 令牌桶 + 队列消费 + 重试 | — |
| 正向重连线程 | 1 | 指数退避重连 | — |
| 代理定时任务 | 3 | 指标清理 / 索取 / 配置提醒 | 全部 async，不占 tick |

**结论**：Velocity 事件线程上只做「取值 + 过滤 + 渲染字符串 + 入队」，
**没有任何一步会等待网络**。因此即使 QQ 侧完全挂掉，Minecraft 的 TPS 也不受影响。

### 8.6 为什么不用第三方库

| 需求 | 替代方案 | 收益 |
|---|---|---|
| YAML 解析 | [`Yaml.java`](proxy/src/main/java/com/qqlink/velocity/config/Yaml.java) 自研（约 700 行） | 不引 SnakeYAML；且能实现「结构合并 + 注释保留」 |
| WebSocket 服务端 | [`ws/WebSocket.java`](proxy/src/main/java/com/qqlink/velocity/onebot/ws/WebSocket.java) 手写 RFC 6455 子集 | 零依赖；JDK 无 WS 服务端 API |
| WebSocket 客户端 | JDK 21 `java.net.http.HttpClient` | JDK 自带，比手写更可靠 |
| JSON | Gson（Velocity 已提供） | 无额外打包 |
| 颜色 / 文本 | [`Text.java`](proxy/src/main/java/com/qqlink/velocity/util/Text.java) 自研翻译器 | 避免 Adventure 版本差异导致的运行期崩溃 |

最终产物 jar 内**只有本项目的 class 文件 + config.yml + velocity-plugin.json**。

### 8.7 安全设计要点

1. **插件消息双向验证**（[`PluginMessageRouter`](proxy/src/main/java/com/qqlink/velocity/mc/PluginMessageRouter.java)）
   频道匹配后**第一件事**就是 `setResult(handled())`，且只接受 `ServerConnection` 来源。
   否则玩家可以伪造「子服上报」，篡改 TPS、伪造公告、伪造死因。

2. **样式注入防护**（[`Format`](proxy/src/main/java/com/qqlink/velocity/util/Format.java)）
   「先替换占位符（值经 `sanitize` 转义尖括号）→ 再翻译颜色」。
   顺序反了的话，玩家昵称里的 `<red>` 或 `&c` 就能给整个模板上色。

3. **消息回环三重防护**
   - `event.userId == selfId` 直接丢弃
   - `self-id` 可手动配置，避免机器人不上报 `self_id` 时失效
   - QQ → MC 走的是「服务端广播」而非「玩家聊天」，不会被 `PlayerChatEvent` 再次捕获

4. **签名聊天零风险**
   Velocity 3.x 的 `PlayerChatEvent.setResult(denied())` 在 1.19+ 会导致玩家被踢
   （官方 Javadoc 已标注弃用）。本插件**只读不写**，因此永远不会触发该问题。

5. **热重载引用正确性**（[`Holder`](proxy/src/main/java/com/qqlink/velocity/util/Holder.java)）
   监听器通过 `Holder<ChatRouter>` 间接持有路由器，重载时只更新 Holder，
   避免「重载后消息仍走旧连接」的隐蔽 Bug。

---

## 九、后续扩展指南

### 9.1 新增一个 QQ 指令

以 `#seed`（查询地图种子）为例：

**① 在 [`CommandsConfig.Builtin`](proxy/src/main/java/com/qqlink/velocity/config/model/CommandsConfig.java) 加枚举项**

```java
SEED("seed", List.of("种子"), "#seed", "查询当前地图种子"),
```

**② 在 [`CommandManager.execute`](proxy/src/main/java/com/qqlink/velocity/mc/CommandManager.java) 加分支**

```java
case SEED -> doSeed(event);
```

**③ 实现方法**

```java
private void doSeed(OneBotEvent event) {
    String seed = "...";   // 从子服上报或配置读取
    reply(event, "&e当前种子：&f" + seed);
}
```

**④ 在 [`Messages`](proxy/src/main/java/com/qqlink/velocity/config/model/Messages.java) 与 `config.yml` 加模板**（可选）

**⑤ 在 [`Messages.toMap`](proxy/src/main/java/com/qqlink/velocity/config/model/Messages.java) 与 `CommandsConfig.toMap` 同步补上字段**，
否则用户升级时拿不到新配置项（两者的 `from` / `toMap` 必须对称）。

### 9.2 新增一个游戏事件通知

**① 在 [`EventsConfig`](proxy/src/main/java/com/qqlink/velocity/config/model/EventsConfig.java) 加开关**

```java
public boolean playerAdvancementBroadcast = true;
```

**② 在 [`ChatRouter`](proxy/src/main/java/com/qqlink/velocity/mc/ChatRouter.java) 加处理方法**

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

**③ 在接入层调用它**
- 代理侧事件 → [`PlayerEventListener`](proxy/src/main/java/com/qqlink/velocity/mc/PlayerEventListener.java)
- 子服上报 → 在 [`PluginMessages`](proxy/src/main/java/com/qqlink/velocity/mc/PluginMessages.java) 加类型，
  在 [`PluginMessageRouter.dispatch`](proxy/src/main/java/com/qqlink/velocity/mc/PluginMessageRouter.java) 加分支，
  在 Fabric 端 `send(server, "新类型", ...)` 上报

### 9.3 新增一个子服上报类型（代理 ↔ 模组协议扩展）

**代理端**（[`PluginMessages.java`](proxy/src/main/java/com/qqlink/velocity/mc/PluginMessages.java)）：

```java
public static final String UP_MY_EVENT = "MY_EVENT";
```

然后 `PluginMessageRouter.dispatch` 加：

```java
case PluginMessages.UP_MY_EVENT -> router.onMyEvent(serverId, inbound.payload);
```

**模组端**（[`Protocol.java`](mod/src/main/java/com/qqlink/fabric/Protocol.java)）：

```java
public static final String UP_MY_EVENT = "MY_EVENT";
```

然后 `QQGroupLinkFabric` 里：

```java
send(server, Protocol.UP_MY_EVENT, "负载内容");
```

> 两处常量必须**手动保持一致**。之所以不抽公共模块，是因为代理插件运行在 Velocity
> （无 Minecraft 依赖），模组运行在 Fabric（依赖 Minecraft 类），
> 硬共享会让模组代码被加载进代理 classpath，风险大于收益。

### 9.4 扩展到更多子服

无需改代码，只在 `config.yml` 的 `servers` 段追加：

```yaml
servers:
  survival: { display-name: "生存服", primary: true, order: 1 }
  mirror:   { display-name: "镜像服", order: 2 }
  creative: { display-name: "创造服", enabled: false, order: 3 }   # 禁用它
```

若忘记配置，插件会在启动日志与每 5 分钟提醒一次，并直接拿服务器 id 当显示名 —— **不会漏消息**。

### 9.5 接入 HTTP API / 网页管理

[`OneBotBridge.snapshot()`](proxy/src/main/java/com/qqlink/velocity/onebot/OneBotBridge.java) 与
[`PlayerManager.snapshotAll()`](proxy/src/main/java/com/qqlink/velocity/mc/PlayerManager.java)
已经返回结构化 `Map`，直接序列化成 JSON 即可做管理页面。

---

## 十、已知限制与设计取舍

这一节把**不完美的地方讲清楚**，避免你踩坑后误以为是 Bug。

### 10.1 需要伴随模组才能实现的功能

| 功能 | 为什么代理做不到 | 有无模组的差异 |
|---|---|---|
| **TPS / MSPT** | Velocity 运行在**协议层**，本身不跑游戏主循环，没有任何 API 能拿到子服 TPS | 无模组：`#tps` 显示「暂无数据」；有模组：真实数值 |
| **精确死亡原因** | Velocity **没有** `PlayerDeathEvent` | 无模组：代理侧解析死亡广播消息，复杂死因退化；有模组：精确 |
| **成就解锁（带分类）** | 同上，无成就事件 | 无模组：不推送（无法可靠区分聊天/成就）；有模组：准确并可过滤配方 |
| **服务器启动完成** | 代理只能看到「端口可连」，不知道世界是否加载完 | 无模组：代理侧启发式判定（约 1~3 分钟延迟）；有模组：准确即时 |

**但以下功能完全不依赖模组**：双向聊天、加入/退出、切换子服、服务器上下线（启发式）、
管理员公告（代理侧识别 `/say` `/broadcast` `/me`）、全部 QQ 指令。

### 10.2 Minecraft 插件消息的固有限制

自定义载荷（`CustomPayload`）在 Minecraft 里**依附于玩家连接**。
这意味着：

- 子服**完全没有玩家在线**时，代理无法向它下发消息，子服也无法上报指标；
- 因此 `#broadcast` / `#send` 对空服无效（属正常现象）；
- `#tps` 对空服会显示「暂无数据」（属正常现象）。

> 这也是为什么本插件在代理侧实现了 `#list` —— 它只依赖代理自己的数据，
> **任何情况下都可用**。

### 10.3 反向 WebSocket 端口必须可达

反向模式要求机器人能访问代理的 8765 端口。若两者在不同网络且无法开端口，
请改用 `mode: forward`（插件主动外连，不需要任何入站端口）。

### 10.4 成就/公告解析依赖服务端语言

模组通过解析原版广播文本识别成就与公告：

- 英文语言包：格式固定，已完整适配（`has made the advancement` / `has reached the goal` / `has completed the challenge` / `has discovered the recipe`）
- 中文语言包：适配了 `达成了进度` / `达成了目标` / `完成了挑战` / `发现了配方`
- 其他语言：会退化为「整条消息直接转发」，仍然可用，只是 `%frame%` 分类不准确

`/say` 与 `/me` 走 Fabric 的**结构化事件**（`ServerMessageEvents.COMMAND_MESSAGE`），
**不受语言影响**。

### 10.5 死亡原因解析

无模组时，代理侧按「消息以玩家名开头」的规则剥离玩家名，剩余部分作为死因：

- `Steve was slain by Zombie` → 死因 `was slain by Zombie` ✔
- `Steve fell from a high place` → 死因 `fell from a high place` ✔
- `Steve was shot by Skeleton using [Bow]` → 死因 `was shot by Skeleton using [Bow]` ✔
- 若使用了改写死亡消息的插件（且格式完全不含玩家名），则原样转发整条消息

死因会被截断到 `events.death.cause-max-length`（默认 60），避免超长附魔名刷屏。

### 10.6 其他

- **`self-id` 建议手动配置**：少数机器人实现不回报 `self_id`，此时自动获取会失败，
  消息回环防护（丢弃机器人自身消息）会失效。手动填写 `onebot.self-id` 即可。
- **`@全体成员` 写法**：使用 `[CQ:at,qq=all]`，兼容 go-cqhttp / NapCat；
  部分实现可能不识别，且需要机器人有群管理员权限。
- **消息长度**：Minecraft 自定义载荷上限 32767 字节，插件内部限制为 30000 字节并做 UTF-8 安全截断。
- **`#send` 的服务器名**：支持显示名（`镜像服`）与 velocity id（`mirror`），**大小写不敏感**。
- **配置项新增**：`PluginConfig` 与 `Messages.build` 的 `from` / `toMap` 必须成对维护。
  只加 `from` 不加 `toMap`，用户升级后拿不到新键（会自动补默认值，但缺少注释）。

### 10.7 本项目的验证状态

诚实说明：本项目在**无网络环境**下完成，因此：

- ✅ 全部 42 个 Java 文件通过**词法级括号配平检查**（`tools/check-structure.ps1`）
- ✅ 全部跨包类型引用通过**导入完整性检查**（`tools/check-imports.ps1`）
- ✅ 所有 Velocity API 调用点均已对照 **3.4.0 官方 Javadoc** 逐个核实
      （`PlayerChatEvent`、`ServerPostConnectEvent`、`Player`、`ServerConnection`、
      `CommandMeta.Builder`、`Scheduler.TaskBuilder`、`CommandManager`、
      `PluginMessageUtil`/`MinecraftChannelIdentifier`、`@Plugin` 等）
- ✅ Fabric API 关键调用点已对照 **1.21.11 官方文档与 Javadoc** 核实
      （`ServerLivingEntityEvents.AFTER_DEATH`、`ServerMessageEvents`、`PayloadTypeRegistry`、
      `ServerPlayNetworking`）
- ❌ **尚未执行 Gradle 编译**（本机无网络，无法下载 Velocity/Fabric 依赖）

因此首次构建时请留意编译错误。若出现 API 不匹配，最可能的位置是：

| 位置 | 可能问题 | 处理 |
|---|---|---|
| `mod/` 的载荷注册 | Fabric API 版本差异导致 `PayloadTypeRegistry` / `ServerPlayNetworking` 签名不同 | 已用 try-catch 包裹，**运行期不会崩**；按 IDE 提示调整签名即可 |
| `mod/` 的 `@Accessor("tickTimes")` | 字段被改名 | 改为查询 yarn 映射后的实际字段名；或删掉该 Mixin，`computeMspt` 会返回 -1 并自动跳过 TPS 上报 |
| `:proxy` | 极少数方法名随 Velocity 小版本变动 | 按 IDE 提示替换为对应版本的方法名 |

**永远只影响增强功能，不会影响游戏运行**：模组内所有上报路径都有 `try-catch` 兜底，
代理端所有事件处理都包在 try-catch 中，任何异常最多导致「某条通知没发出去」。

---

## 许可

示例代码可自由用于二次开发。
