# MUZ（斗地主）- 项目提示词

> 本文档是 MUZ 插件的工程规范，用于约束所有后续协作者与开发者。
> 内容依据仓库实际代码事实撰写，**不是**通用模板套用。参与开发前必须先读完本文。

## 项目定位

本项目是一个 **Paper / Purpur 服务端的桌面卡牌插件**，插件名 `MUZ`，主类 `linmumua.doudizhu.DoudizhuPlugin`。

唯一核心领域是**方块世界内的实体化牌桌对局**：用 Display Entity 在真实世界坐标上渲染牌桌、手牌、悬浮 HUD，玩家通过点击实体与坐下交互完成一局斗地主。

所有后续开发必须围绕「牌桌对局体验 + 渲染精度 + 配置驱动」展开。

禁止引入与桌面对局无关的业务概念：经济商城、领地、任务系统、聊天管理、权限管理等一律不属于本项目边界。

## 作者与归属

项目作者只有 `linmumua`。

- `paper-plugin.yml` 的 `author` 字段只能是 `linmumua`。
- `build.gradle.kts` 的 `group` 为 `linmumua`，包根为 `linmumua.doudizhu`。
- 发布说明、构建信息、示例 YAML 中不得出现任何其他作者名、占位名或示例名。

## 技术框架约束

**本项目使用 TabooLib 作为工具库，但不使用其注解生命周期。** 插件主体仍是原生 `JavaPlugin`；反射等工具优先走 TabooLib，禁止把架构改成 TabooLib 注解驱动。源码、构建脚本与现有测试以当前仓库事实为准，不要套用旧版“完全不使用 TabooLib”的历史说明。

> **当前事实（TabooLib 已实际接入）**：`build.gradle.kts` 已应用 `io.izzel.taboolib` 2.0.38 插件，并以 `implementation` + `embeddedLibraries` 引入并内嵌 `io.izzel.taboolib:common-reflex:6.3.0-75b18a2`（连带 kotlin-stdlib 2.3.20 / asm / commons-lang3）；源码已真实 `import taboolib.library.reflex.*`（`CraftEngineOffsetService`、`CraftEngineHudResourceBridge`、`CraftEngineFurnitureService`）。因此「反射一律走 TabooLib」这条约定有仓库事实支撑；历史文档里「`build.gradle.kts` 无 TabooLib 依赖、源码 0 处 import」的表述已过时，不要再据此认为该约束待裁决。TabooLib 仍是**工具层**，不参与插件入口与注解生命周期。

因此框架规则如下：

- 主架构是**原生 Paper 插件**：`DoudizhuPlugin extends JavaPlugin`，走 `onEnable` / `onDisable`。
- 语言是 **Java**，不是 Kotlin。Kotlin 插件只用于 Gradle 构建脚本（`build.gradle.kts`），不用于插件源码。
- 打包用 `com.gradleup.shadow`，把 SnakeYAML 内嵌并重定位。
- 禁止把架构改成 TabooLib 注解驱动生命周期或 Kotlin 源码。TabooLib 仅作为工具库使用；要改架构必须先获得 linmumua 明确同意。
- `paper-plugin.yml` 不支持 `commands` 段，`/muz` 在 `onEnable` 里通过 `CommandMap` 手动注册（见 `registerMuzCommand()`）。
- 依赖插件默认独立 ClassLoader。有直接 Java import 的（CraftEngine / PlaceholderAPI / Vault）必须 `join-classpath: true`，否则启动抛 `NoClassDefFoundError`；仅做存在性检测的（CMI / CMILib / EzEconomy / XConomy）保持 `false`。

## 核心目标

1. **实体化牌桌渲染**：用 Display Entity 在世界坐标渲染桌面、手牌、按钮、悬浮 HUD，保证像素级对齐与不溢出桌面。
2. **完整斗地主对局闭环**：发牌、叫地主、出牌校验、倍数与结算，含机器人补位。
3. **资源包资产自动生成**：由 Gradle 构建期生成字形、贴图、CraftEngine 配置分片，插件侧零 IO 复算码位。
4. **配置驱动一切可调参数**：渲染偏移、HUD 档位、音效、经济、房间等级全部可配。
5. **数据持久化与外部桥接**：SQLite / MySQL 存档，Vault 经济、PlaceholderAPI 变量、CraftEngine 资源接入。

## 版本与发布约定

- 版本号写在 `build.gradle.kts` 的 `version`，当前 `1.10.51`。`paper-plugin.yml` 用 `${version}` 占位，不要手写。当前版本的三条关键事实：`folia-supported: false`（冒烟临时 `true` 已回滚）、TabooLib 已实际接入（工具层）、关闭清理仍未完成（`onDisable` 内的 owner 清理注册会被调度闸门拒绝）。与 [AGENTS.md](AGENTS.md) 的版本约定必须保持同步，历史轮次记录里的全量测试计数不是当前数字。本版（1.10.51）唯一改动是把原本只在 `runtimeClasspath` 的 `gson:2.11.0` 与 `sqlite-jdbc:3.46.1.0` 显式并入 `embeddedLibraries`，并明确二者**保持原包名、不重定位**；`verifyRelocatedSnakeYaml` 与 `build/muz-release-audit.py` 各加一条「原包名必须存在」的反向断言，把这一决策写成可执行契约。
- 构建目标由 `MuzTarget` 表驱动，通过 `-PmuzTarget=<id>` 选择，默认 `paper-26.2`。产物落在 `build/<targetId>/`，**不是** `build/`。
- 禁止无版本号变化地替换已发布构建。改了行为就升版本。

## 沟通与编码规则

- 所有沟通、注释、文档、提交信息使用简体中文。
- 类名、方法名、字段名、包名、配置键使用英文。
- 禁止用 Unicode 转义表示中文或颜色符号。需要颜色时直接写 `§`，需要富文本时用 MiniMessage 标签。
- **禁止删除已有注释。** 本项目注释密度很高且承载了大量「为什么这么写」的决策记录（例如 `paper-plugin.yml` 里关于 `join-classpath` 的说明）。需要修正时在原基础上改写，不要整段清掉。
- 第三方资源 id、声音 id、字形码位、CraftEngine 条目名必须注明来源与用途。
- 涉及资源包码位、字形宽度这类构建期与运行期双向约定的改动，必须在两侧都留注释说明对齐关系。

## 玩家消息与文本规范

**当前状态必须如实记录**：`config.yml` 里**没有** `messages:` 或 `lang:` 段，代码中存在约 69 处带中文的 `sendMessage` 调用，即玩家可见文本目前是硬编码的。

这是与理想规范冲突的既有事实。处理原则：

- **不要**为了「符合规范」而擅自发起全量文本外置重构，那会触及 69 处调用点，属于大范围改动，必须先获得 linmumua 同意。
- 新增玩家可见文本时，优先走配置；若所在模块周边全是硬编码，允许保持一致（Rule 11：conformance > taste），但要在回复中指出这笔技术债。
- 一旦决定外置，必须同步补齐默认配置与注释，禁止「功能已实现但默认配置缺失」。

目标形态（供未来重构参考）：

```yaml
messages:
  prefix: "§7[§6MUZ§7] "
  no-permission: "§c你没有权限执行这个操作。"
  reload-success: "§a配置已重载。"
  not-in-game: "§c你当前不在任何牌桌上。"
```

控制台日志、开发日志、调试日志可以直接写中文，无需外置。

## 配置设计原则

运行期 YAML 一律走 `org.yaml:snakeyaml`，封装在 `MuzYamlConfig`。**禁止**用 Bukkit `YamlConfiguration` 作为运行期主方案，禁止引入 Jackson YAML。SnakeYAML 已 relocate 打进 JAR，`verifyRelocatedSnakeYaml` 任务会校验，不要绕过。

`config.yml` 现有顶层段及职责：

| 段 | 职责 |
|---|---|
| `table` | 牌桌基础尺寸与位置 |
| `craftengine-items` | CraftEngine 物品 id 映射 |
| `render` | 桌面、手牌、悬停动画等渲染参数 |
| `trick-hud` | 悬浮 HUD 的档位、头像、记牌器 |
| `cards` / `audio` / `actionbar` | 牌面、音效、动作栏 |
| `bot` / `ai` | 机器人行为与 AI 网关 |
| `hints` | 提示按钮最大方案数等 |
| `debug` / `debug.web-ui` | 观察桌间距与仅回环监听的 Debug Web HUD 面板（默认关闭，默认端口 2000） |
| `economy` / `room-levels` | 经济结算与房间等级 |
| `storage` | SQLite / MySQL 存储 |
| `player-options` | 玩家个人偏好 |

规则：

- 新增功能涉及配置时，必须同步更新默认 `config.yml` 与其注释，并说明取值范围。
- 影响兼容性的键改动必须写明迁移方式。`RETIRED_RENDER_KEYS` 会主动删键，`PRESERVED_RENDER_KEYS` 是用户明确要求保留的键，**勿动**。
- `ensureConfigIntegrity()` 会在启动时把默认值写回 `config.yml`。改默认值时必须同时检查这里，否则会被启动流程覆盖回去。
- 档位型键有合法值白名单；当前默认 `muz-resource-profile.yml` 仅生成牌高 53、头像 4/6、记牌器 100、Hotbar 100。未生成档位必须拒绝，不得静默回退；新增档位必须同步白名单与构建期字形表。
- 玩家个人数据与运行时状态不要混进 `config.yml`，走 `player-settings` / 存储层。

## 领域模型约束

- `DoudizhuPlugin`：插件入口与全局装配。**注意：当前已超过 2800 行，是事实上的 God Class。** 禁止继续往里堆新逻辑，新功能应落到对应 service。
- `GameTable`（`game`）：单桌对局状态机，持有座位、手牌、出牌历史、剩余张数统计。
- `TrickHudService` / `TrickHudView`（`game`）：HUD 数据组装与字形渲染分离。Service 产出 cell，View 负责 advance 宽度与居中算式。
- `PackAssets`（`assets`）：插件侧复算资源包字形码位与字体名，零文件 IO。必须与 `build.gradle.kts` 生成逻辑严格对齐。
- `PackTiers`（构建期生成）：档位容量常量，插件侧只读引用，不要手改。
- `MuzYamlConfig`（`config`）：SnakeYAML 读写封装。
- `model`：`CardRank`、牌型定义等纯数据对象，不含 IO 与 Bukkit 依赖。
- `storage`：存档读写，须异步执行。
- `compat`：CraftEngine / Vault / PlaceholderAPI 桥接，必须做存在性判断与降级。

边界要求：配置读取、运行时状态、渲染执行、外部桥接必须分离。禁止在 model 里碰 Bukkit API，禁止在 View 里读配置。

## 功能边界

### 1. 对局核心（`game`）

- 负责：发牌、叫地主、牌型校验、倍数、结算、剩余张数统计、机器人回合驱动。
- 不负责：渲染实体的具体摆放、经济扣款的最终落库、玩家权限判断。

### 2. 渲染与 HUD（`game` 的 View 层、`world`）

- 负责：Display Entity 生命周期、坐标与朝向、字形拼装、宽度居中算式、悬停动画。
- 不负责：对局规则、配置校验、玩家数据持久化。

### 2.5 Debug Web（`debug/`）

- 正式首页直接读取 JAR 内嵌的 `debug-hud-preview.html`；缺失时明确报错，不回退到带旧版本号的 Java 内联页面。根目录同名文件只是独立前端原型。
- 页面通过 `/api/preview-resources` 和 `/api/state` 消费当前 profile 的真实 PNG 白名单与服务端几何，不得用 emoji、CSS 假图或未生成档位资源。保存异步写配置/overlay、生成并校验 ZIP，保存本身不触发 CraftEngine 重载；重载由重新读取配置或管理员手动 `/ce reload all` 负责。
- 当前白名单对应默认 profile 的 100% 记牌器与 Hotbar，测试必须覆盖资源键、尺寸和未生成档位拒绝。

### 3. 资源资产（`assets` + 构建期任务）

- 负责：字形码位分配、贴图裁切、CraftEngine YAML 分片生成、字体分页（每档 50 码位、117 档一张）。
- 不负责：运行期读取资源包文件。插件侧一律靠枚举复算。

### 4. 配置（`config`）

- 负责：YAML 读写、默认值补全、完整性校验、迁移。
- 不负责：业务语义判断。

### 5. 存储（`storage`）

- 负责：SQLite / MySQL 建表、玩家战绩与偏好读写。
- 不负责：主线程同步查询。所有 I/O 必须异步。

### 6. 外部桥接（`compat`、`placeholder`、`action`）

- 负责：CraftEngine 物品与资源、Vault 经济、PlaceholderAPI 变量、CE Action 执行。
- 不负责：在依赖缺失时崩溃。必须降级并记录日志。

### 7. 麻将（`mahjong`）

- 现状：存在 5 个文件的独立子领域。
- 要求：与斗地主对局状态互不污染。新增功能不要跨领域复用 `GameTable`。

## 推荐包结构

现有结构即规范，新增代码按此归位：

```text
linmumua.doudizhu
├── DoudizhuPlugin.java     入口（勿再膨胀）
├── game/                   对局状态机 + HUD Service/View
├── model/                  纯数据对象（无 Bukkit 依赖）
├── ui/                     交互界面
├── world/                  世界实体与坐标
├── assets/                 资源包码位复算
├── config/                 SnakeYAML 配置层
├── storage/                持久化
├── compat/                 外部插件桥接
├── listener/               事件监听
├── command/                命令
├── ai/                     AI 网关
├── bot 相关（在 game 内）   机器人决策
├── mahjong/                麻将子领域
├── room/ · scheduler/ · action/ · placeholder/
```

## 生命周期建议

`onEnable` 顺序（不得随意调换）：

1. 释放并加载 `config.yml`，执行 `ensureConfigIntegrity()` 补全与校验。
2. 初始化 `MuzYamlConfig` 及各配置快照（Settings）。
3. 初始化存储层，异步建表。
4. 检测可选依赖（CraftEngine / Vault / PlaceholderAPI / CMI 等），登记降级标记。
5. 构造各 service。
6. 通过 `CommandMap` 注册 `/muz`（`paper-plugin.yml` 不支持 `commands` 段）。
7. 注册事件监听。
8. 启动周期任务。

`onDisable` 必须完成：

- 结束所有进行中对局并落库。
- **清理所有 Display Entity**。这是最容易出错的一步，残留实体会在世界里长期悬浮。
- 取消周期任务、关闭数据库连接、清空缓存与会话。

`folia-supported: false`（当前 1.10.51 仍是 `false`）。最初的 `false` 是因为 `MuzScheduler` 全走 `BukkitScheduler`（Folia 已废弃该接口，调用即抛 `UnsupportedOperationException`）；此后 Folia 迁移进入第二阶段，`MuzScheduler` 已建立并实际承载 global / region / entity-player / async 四条 owner lane 的兼容后端，但仍未完成牌桌、玩家、实体与麻将领域的 owner 路由、关闭清理和真实 Folia 服务端验收，因此**继续保持 `false`**。

需特别注意：迁移期间曾为采集实服证据把 `paper-plugin.yml` 的 `folia-supported` **临时**改为 `true`（冒烟状态），**现已恢复为正式的 `false`**；`paper-plugin.yml` 内保留了相应说明注释。守护测试 `FoliaDeclarationMatchesSchedulerTest` 在临时 `true` 期间按设计失败，现在应全绿——若它再次失败，说明 `false` 被误改回 `true`，属真实回归，不得当作「按设计变红」。

要恢复 `true`，必须先在真实 Folia 服务端完成 owner 路由与关闭清理验收，并确认 `MuzScheduler` 的四条 lane 在实体操作上跑在正确的区域线程。`FoliaDeclarationMatchesSchedulerTest` 会把声明与调度层实现绑定校验，改一边不改另一边会红。

**关闭清理仍未完成**：`onDisable()` 里对牌桌/麻将 owner 清理的「提交」在 `shuttingDown` 置位后会被 `MuzScheduler` 拒绝注册，因此在 `onDisable` 内实际不会执行区域清理，不得据此宣称关闭清理已生效。

注意 `TrickHudService` 的 `snapshot` 字段仍保留 `volatile` 与整份替换语义，那是为未来的区域线程读做的准备，不要因为现在不支持 Folia 就摘掉。

## 当前技术栈

| 层 | 技术 | 备注 |
|---|---|---|
| 平台 | Paper / Purpur，`folia-supported: false` | 目标 API 由 `MuzTarget` 表驱动，默认 `paper-26.2`；为安全值，冒烟临时 `true` 已回滚 |
| 主框架 | 原生 Bukkit `JavaPlugin` + TabooLib 工具层（**已实际接入**） | 不使用 TabooLib 注解生命周期；构建期应用 `io.izzel.taboolib:2.0.38` 并内嵌 `common-reflex` |
| 语言 | Java（主源码约 117 个 `.java`，0 Kotlin 源码；以仓库实际文件数为准） | Kotlin 仅用于 Gradle DSL |
| 渲染 | Display Entity + MiniMessage / Adventure | 字形由资源包驱动 |
| 配置 | `org.yaml:snakeyaml:2.6`（已 relocate） | 封装于 `MuzYamlConfig` |
| 存储 | `sqlite-jdbc:3.46.1.0`，可选 `mysql-connector-j:8.4.0` | MySQL 为 `compileOnly` |
| 序列化 | `gson:2.11.0` | |
| 可选依赖 | CraftEngine 0.0.67、PlaceholderAPI 2.12.2、VaultAPI 1.7 | 全部 `compileOnly` |
| 构建 | Gradle + Kotlin DSL + `com.gradleup.shadow:9.3.0` + `io.izzel.taboolib:2.0.38` | 产物在 `build/<targetId>/` |
| 测试 | JUnit 5（junit-bom 5.13.4），以当前仓库测试目录为准 | 独立 runner 需要显式核对发现数、失败容器与成功数 |

## 开发优先级建议

1. **对局体验闭环**：规则正确性 > 渲染精度 > 交互手感。
2. **渲染与资源包一致性**：构建期与插件侧码位、宽度、档位必须严格对齐。
3. **配置与可维护性**：默认值合理、校验完备、迁移清晰。
4. **性能与安全**：异步 I/O、实体数量控制、异常不吞。
5. **技术债偿还**：`DoudizhuPlugin` 拆分、玩家文本外置。需先获批准。

## 明确禁止事项

- 禁止偏离「实体化牌桌对局」核心领域，禁止塞入无关业务系统。
- 禁止把架构改成 TabooLib 注解驱动生命周期，禁止把插件源码改成 Kotlin，除非 linmumua 明确要求；TabooLib 工具库按现有约定使用。
- 禁止用 `YamlConfiguration` 或 Jackson YAML 作为运行期 YAML 主方案。
- 禁止作者字段出现 `linmumua` 以外的名字。
- 禁止在主线程执行数据库、文件、网络 I/O（含 AI 网关请求）。
- 禁止吞异常不记录日志。
- 禁止新增功能不同步更新默认配置与注释。
- 禁止继续向 `DoudizhuPlugin` 堆积业务逻辑。
- 禁止删除既有注释。
- 禁止手改构建期生成物（`PackTiers`、`build/` 下的 YAML 与 PNG）。
- 禁止在 `onDisable` 漏清 Display Entity。
- 禁止无版本号变化地替换发布构建。
- 禁止在 Git Bash 里使用 Windows cmd 内建命令（`findstr` / `dir` / `type` 等），MSYS2 会把 `/flag` 误转成路径。

## 工作准则

- 改动前先读相关代码与调用方，尤其是构建期与运行期双向约定的部分。
- 改动范围保持最小，不顺手「优化」相邻代码与格式。
- 任何代码改动后必须跑 `compileJava`，并跑相关测试；测试结果要确认是真实执行而非 up-to-date 空跑。
- 渲染类改动无法自测的部分（是否豆腐块、是否居中、是否溢出）必须如实说明需要重启测试服与重下资源包确认。
- 跳过了什么就说跳过了什么，不要报「已完成」。

## 协作者执行要求

- 参与开发前必须先读本文档，并核对仓库实际事实，不要凭默认模板假设架构。
- **本项目不是 TabooLib 注解生命周期项目**（TabooLib 已作为工具库实际接入构建与反射路径，见「技术框架约束」）。任何基于 TabooLib 注解生命周期的建议都是错的。
- 修改代码必须遵守领域边界，不主动扩展无关功能。
- 涉及资源包字形、码位、档位时，必须同时检查 `build.gradle.kts` 生成侧与 `PackAssets` 复算侧，两侧不一致会导致游戏内显示豆腐块。
- 新增玩家可见文本优先走配置；若与周边硬编码风格冲突，按既有风格实现但必须指出技术债。
- 大范围重构（文本外置、入口类拆分、架构替换）必须先说明风险与影响面，取得同意后再动手。
- 发现需求与项目核心或既有约定冲突时，先说明冲突，再给收敛方案，不要静默按自己的偏好实现。
- 修改测试断言时必须说明改的是「断言编码了过时格式」还是「实现有 bug」，禁止为了变绿而弱化断言。
