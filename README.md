# MikuChat

**作者：JunXieX** · **MikuMC系列插件交流群：1105054380** · **非开源项目，请勿二次分发**

MikuChat 是一套面向 Paper / Folia 服务端的聊天管理插件，配合 Velocity 代理端插件可实现跨服聊天、跨服私聊、跨服在线查询与禁言状态同步。所有文本基于 Adventure / MiniMessage，不依赖 NMS，服务端版本升级无需重新适配。

## 功能特性

- **聊天频道**：支持本地频道与跨服频道，可配置触发符号（例如消息以 `#` 开头自动发到全服频道），频道可单独设置权限、格式、过滤与防刷屏策略。
- **跨服同步**：跨服聊天、跨服私聊、`/mchat who` 在线查询、禁言状态全网同步；代理端持有禁言状态并落盘，后端重启后自动拉取。
- **内容过滤**：Aho-Corasick 多词匹配 + 文本归一化（全角/大小写/标点/重复字符），支持屏蔽词替换或拦截、广告与链接检测、按频道选择策略。
- **防刷屏**：冷却、令牌桶限频、相似内容检测、句内重复检测四重保护，管理员可绕过。
- **禁言系统**：`/mute`、`/unmute`，支持时长（s/m/h/d）与原因，跨服同步，代理不可用时本地回落持久化。
- **私聊与监听**：`/msg`、`/reply` 支持跨服投递与回复，管理员可开启私聊监听（spy），跨服场景两端均可见。
- **@ 提及**：`@玩家名` 与 `@all`（需权限），点击即可回复，可单独开关提醒。
- **物品/背包展示**：`[item]` / `[i]`、`[inv]`、`[ender]`，以悬停组件展示，无需客户端模组。
- **聊天格式**：按权限、世界、频道匹配格式规则，支持 LuckPerms 前后缀与 PlaceholderAPI 变量。
- **聊天留档**：公开聊天与私聊按天写入 `data/` 目录，支持按关键词检索与保留天数清理。
- **样式权限**：颜色、十六进制颜色、装饰、完整 MiniMessage 分级放行，默认转义玩家输入，防止 click / hover 注入。
- **Folia 支持**：所有玩家相关操作均通过区域化调度执行。

## 环境要求

| 项目 | 要求 |
| --- | --- |
| 服务端 | Paper 或 Folia 26.2（随 Minecraft 最新版本） |
| Java | 25 |
| 代理端（可选） | Velocity 4.x（仅跨服功能需要） |
| 可选前置 | LuckPerms（前后缀/权限组）、PlaceholderAPI（变量） |

## 安装

### 单服安装

1. 将 `MikuChat-<版本>.jar` 放入服务端 `plugins/` 目录。
2. 启动服务端，插件会自动生成 `plugins/MikuChat/` 与全部默认配置。
3. 按需修改 `config.yml`（语言、默认频道）与各模块配置文件，执行 `/mchat reload` 生效。
4. 单服可保持 `network.yml` 中 `enabled: true`；未配置 `shared-secret` 时插件会自动禁用跨服并输出提示，不影响本服聊天。

### 跨服安装（Paper/Folia + Velocity）

1. **后端**：把 `MikuChat-<版本>.jar` 放入每台子服 `plugins/`。
2. **代理端**：把 `MikuChat-Velocity-<版本>.jar` 放入 Velocity `plugins/`。
3. **配置签名密钥（必填）**：在两处的 `shared-secret` 填入**完全相同**的随机字符串（建议 32 位以上）：
   - 子服 `plugins/MikuChat/network.yml` → `shared-secret`
   - 代理 `plugins/MikuChat/config.yml` → `shared-secret`
   生成示例：`openssl rand -hex 32`。
4. **对齐服务器标识**：每台子服 `plugins/MikuChat/config.yml` 的 `server-id` 必须与该服在 `velocity.toml` 中 `[servers]` 的服务器名完全一致（大小写敏感）。
5. 重启代理与全部子服。日志出现 `跨服通道已就绪` 即表示配置正确；`/mchat info` 中「跨服」应显示「已连接」。

> 安全说明：客户端可以自行向插件消息通道发送数据，因此代理端下发的每条跨服消息都会用 `shared-secret` 做 HMAC-SHA256 签名，后端校验失败即丢弃。**不要**把 `shared-secret` 留空后仍期望跨服可用，也不要把子服端口直接暴露给玩家绕过代理。

## 命令

| 命令 | 说明 | 权限 |
| --- | --- | --- |
| `/mchat help` | 查看命令帮助 | 无 |
| `/mchat channel <id\|list>` | 切换 / 查看聊天频道 | 频道自身权限 |
| `/mchat toggle mention` | 开关 @ 提醒 | 无 |
| `/mchat spy` | 开关私聊监听 | `mikuchat.private.spy`（可在 `private.yml` 修改） |
| `/mchat who` | 查看跨服在线玩家 | `mikuchat.command.who` |
| `/mchat log <玩家\|关键词> [页码]` | 检索聊天留档 | `mikuchat.command.log` |
| `/mchat filter test <文本>` | 测试过滤结果 | `mikuchat.command.filter` |
| `/mchat info` | 查看运行信息 | `mikuchat.command.info` |
| `/mchat reload [模块]` | 重载全部配置或单个模块 | `mikuchat.command.reload` |
| `/mute <玩家> [时长] [原因]` | 禁言（如 `10m`、`2h`、`7d`，省略为永久） | `mikuchat.mute` |
| `/unmute <玩家>` | 解除禁言 | `mikuchat.mute` |
| `/msg <玩家> <内容>` | 发送私聊 | 无 |
| `/reply <内容>` | 回复上一次私聊 | 无 |

## 权限节点

| 权限 | 默认 | 说明 |
| --- | --- | --- |
| `mikuchat.admin` | OP | 包含全部管理类权限 |
| `mikuchat.chat.color` | 所有人 | 使用 `<red>` 等命名颜色 |
| `mikuchat.chat.color.hex` | OP | 使用 `<#rrggbb>` 十六进制颜色 |
| `mikuchat.chat.decoration` | 所有人 | 使用粗体/斜体/下划线等装饰 |
| `mikuchat.chat.minimessage` | OP | 使用完整 MiniMessage（含 click / hover / gradient） |
| `mikuchat.mention.all` | OP | 使用 `@all` |
| `mikuchat.display.item` / `.inventory` / `.enderchest` | 所有人 | 使用 `[item]` / `[inv]` / `[ender]` |
| `mikuchat.bypass.filter` | OP | 绕过屏蔽词与广告过滤 |
| `mikuchat.bypass.antispam` | OP | 绕过防刷屏 |
| `mikuchat.format.admin` | OP | 使用管理组聊天格式 |
| `mikuchat.mute` | OP | 禁言 / 解除禁言 |
| `mikuchat.private.spy` | OP | 私聊监听 |

## 配置文件

所有配置位于 `plugins/MikuChat/`，修改后执行 `/mchat reload`（或 `/mchat reload <模块>`）：

| 文件 | 内容 |
| --- | --- |
| `config.yml` | 服务器标识、语言、默认频道、调试开关 |
| `chat.yml` | 消息长度上限、频道触发符号、跨服不可用时的降级策略 |
| `channels/*.yml` | 频道定义：显示名、作用域（LOCAL/NETWORK）、权限、格式、过滤/防刷屏策略、是否留档 |
| `format.yml` | 聊天格式规则（按 priority 匹配权限 / 世界 / 频道） |
| `filter/config.yml`、`filter/words.yml`、`filter/words.txt` | 屏蔽词与广告检测策略、词库 |
| `antispam.yml` | 冷却、令牌桶、相似度、重复检测策略 |
| `mute.yml` | 禁言本地回落存储（由插件维护，无需手改） |
| `private.yml` | 私聊格式、监听、留档、投递超时 |
| `mention.yml` | @ 提醒方式、冷却、`@all` 权限 |
| `display.yml` | `[item]` / `[inv]` / `[ender]` 标记、权限与冷却 |
| `chatlog.yml` | 聊天留档格式、保留天数、检索上限 |
| `network.yml` | 跨服通道、签名密钥、队列与超时 |
| `style.yml` | 样式权限映射 |
| `integrations.yml` | LuckPerms / PlaceholderAPI 集成开关 |
| `lang/zh_CN.yml`、`lang/en_US.yml` | 提示文案（MiniMessage） |

## 常见问题

**Q：升级到 1.7.0 后跨服失效，日志提示「shared-secret 为空」？**
A：1.7.0 起跨服消息必须签名。请在后端 `network.yml` 与 Velocity 端 `config.yml` 配置相同的 `shared-secret` 后重启两端。

**Q：可以只升级后端或只升级代理端吗？**
A：不可以。1.7.0 的签名与分页协议是两端配套的，请**同时**替换后端与代理端两个 jar；单独升级任一端会导致跨服消息被丢弃（后端日志会输出「未通过签名校验」限频告警）。

**Q：`/mchat info` 显示「通道已注册（未连通代理）」？**
A：说明本服插件消息通道已注册但还没收到过代理端应答。请确认 Velocity 端已安装 `MikuChat-Velocity`、两端 `channel` 一致、`shared-secret` 一致，并且 `server-id` 与 `velocity.toml` 的服务器名一致。

**Q：改了配置没有生效？**
A：多数文件支持 `/mchat reload` 热重载；涉及 `server-id`、`channel`、`shared-secret` 的修改建议重启。

**Q：支持 Folia 吗？**
A：支持。`paper-plugin.yml` 已声明 `folia-supported: true`，插件内部对玩家操作使用区域化调度。

**Q：如何反馈问题？**
A：加入 MikuMC系列插件交流群 **1105054380**。
