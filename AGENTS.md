# MUZ（斗地主）— 项目提示词

> 本文档是 MUZ 插件的工程规范，用于约束 Claude Code、Codex 及后续开发者。
> 内容依据仓库实际代码事实撰写，**不是**通用模板套用。参与开发前必须先读完本文。

## 项目定位

本项目是一个 **Paper / Purpur 服务端的桌面卡牌插件**，插件名 `MUZ`，主类 `linmumua.doudizhu.DoudizhuPlugin`。

唯一核心领域是**方块世界内的实体化牌桌对局**：用 Display Entity 在真实世界坐标上渲染牌桌、手牌、悬浮 HUD，玩家通过点击实体与坐下交互完成一局斗地主。

所有后续开发必须围绕「牌桌对局体验 + 渲染精度 + 配置驱动」展开。禁止引入与桌面对局无关的业务概念：经济商城、领地、任务系统、聊天管理、权限管理等一律不属于本项目边界。

## 作者与归属

项目作者只有 `linmumua`。

- `paper-plugin.yml` 的 `author` 字段只能是 `linmumua`。
- `build.gradle.kts` 的 `group` 为 `linmumua`，包根为 `linmumua.doudizhu`。
- 发布说明、构建信息、示例 YAML 中不得出现任何其他作者名、占位名或示例名。

### 禁止 AI 署名（强约束）

**任何 AI 协作者禁止在本项目任何位置写入自己的署名、作者身份或协作痕迹。**

具体禁止：

- 禁止在 Git 提交信息中写入 `Co-authored-by:` 行，无论指向哪个 AI 身份。
- 禁止在提交信息中写入 `Generated with`、`Co-Authored-By`、`Assisted-by` 等任何形式的生成来源标注。
- 禁止在源码注释、文件头、Javadoc 中写入 AI 名称或模型名称。
- 禁止在 README、发布说明、CHANGELOG 中标注 AI 参与。
- 禁止在 `paper-plugin.yml`、`build.gradle.kts`、资源文件中添加 AI 相关的作者或贡献者字段。
- 禁止创建 `.github/` 下任何声明 AI 贡献者的元数据文件。

提交时必须确保：`git log --format='%an <%ae>%n%b'` 的输出中只出现 `linmumua`，不含任何第三方身份。此约束原因：GitHub 会依据 `Co-authored-by` 生成贡献者记录，写入后长期留存于仓库侧栏与贡献者图中。

## 技术框架约束

**本项目全面使用 TabooLib 作为工具库**（linmumua 明确要求，2026-08 起）：Java 反射一律走 TabooLib 工具类，禁止再用裸 `java.lang.reflect` API；其他通用工具场景也优先用 TabooLib。TabooLib **不作为注解生命周期框架**使用，插件主体仍是原生 `JavaPlugin`。

框架规则：

- 主架构是**原生 Paper 插件**：`DoudizhuPlugin extends JavaPlugin`，走 `onEnable` / `onDisable`。
- 语言是 **Java**，不是 Kotlin。Kotlin 仅用于 Gradle 构建脚本（`build.gradle.kts`）。
- 打包用 `com.gradleup.shadow`，把 SnakeYAML 内嵌并重定位。
- `paper-plugin.yml` 不支持 `commands` 段，`/muz` 在 `onEnable` 里通过 `CommandMap` 手动注册（见 `registerMuzCommand()`）。
- 禁止把插件源码改成 Kotlin，除非 linmumua 明确要求。不要把架构改成 TabooLib 注解驱动生命周期。
- 依赖插件默认独立 ClassLoader。有直接 Java import 的（CraftEngine / PlaceholderAPI / Vault）必须 `join-classpath: true`，否则启动抛 `NoClassDefFoundError`；仅做存在性检测的（CMI / CMILib / EzEconomy / XConomy）保持 `false`。

## 核心目标

1. **实体化牌桌渲染**：用 Display Entity 在世界坐标渲染桌面、手牌、按钮、悬浮 HUD，保证像素级对齐与不溢出桌面。
2. **完整斗地主对局闭环**：发牌、叫地主、出牌校验、倍数与结算，含机器人补位。
3. **资源包资产自动生成**：由 Gradle 构建期生成字形、贴图、CraftEngine 配置分片，插件侧零 IO 复算码位。
4. **配置驱动一切可调参数**：渲染偏移、HUD 档位、音效、经济、房间等级全部可配。
5. **数据持久化与外部桥接**：SQLite / MySQL 存档，Vault 经济、PlaceholderAPI 变量、CraftEngine 资源接入。

## 版本与发布约定

- 版本号写在 `build.gradle.kts` 的 `version`，当前 `1.10.11`。`paper-plugin.yml` 用 `${version}` 占位，不要手写。
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

- **不要**擅自发起全量文本外置重构，那会触及 69 处调用点，必须先获得 linmumua 同意。
- 新增玩家可见文本时，优先走配置；若所在模块周边全是硬编码，允许保持一致（conformance > taste），但要在回复中指出这笔技术债。
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
| `hotbar-hud` | 仅 `GamePhase.PLAYING` 在线真人座位使用的热键栏 HUD 开关与定位参数；其余阶段/玩家保留原版 9 槽（`offset-x` 运行期即时生效；`offset-y` 走覆盖层需重下资源包；`glyph-ascent` / `slot-count` 是纯文档键，运行期不读） |
| `cards` / `audio` / `actionbar` | 牌面、音效、动作栏 |
| `bot` / `ai` | 机器人行为与 AI 网关 |
| `hints` | 提示按钮最大方案数等 |
| `debug` | 观察桌间距等调试开关 |
| `debug.web-ui` | Debug Web 可编辑 HUD 配置页（只监听回环地址，默认 `enabled: false`、`port: 2000`） |
| `economy` / `room-levels` | 经济结算与房间等级 |
| `storage` | SQLite / MySQL 存储 |
| `player-options` | 玩家个人偏好 |

规则：

- 新增功能涉及配置时，必须同步更新默认 `config.yml` 与其注释，并说明取值范围。
- 影响兼容性的键改动必须写明迁移方式。`RETIRED_RENDER_KEYS` 会主动删键，`PRESERVED_RENDER_KEYS` 是用户明确要求保留的键，**勿动**。
- `ensureConfigIntegrity()` 会在启动时把默认值写回 `config.yml`。改默认值时必须同时检查这里，否则会被启动流程覆盖。
- 档位型键有合法值白名单（如 `trick-hud.card-height` 按当前资源表取 32..56，`avatar-scale` 按当前资源表取 2..16），填非法值会 warn 回退。新增档位必须同步白名单与构建期字形表。
- 玩家个人数据与运行时状态不要混进 `config.yml`，走 `player-settings` / 存储层。

## 领域模型约束

- `DoudizhuPlugin`：插件入口与全局装配。**注意：当前已超过 5500 行，是事实上的 God Class。** 禁止继续往里堆新逻辑，新功能应落到对应 service。
- `GameTable`（`game`）：单桌对局状态机，持有座位、手牌、出牌历史、剩余张数统计。出牌阶段真人跟牌若 `MoveAdvisor` 判定无可压，会保留 `currentTurn` 等待 20 tick；等待期间可用现有「不要」手动跳过，在线真人仍未响应才由独立 epoch/token 二次校验后自动不要。等待期间 `tickActionBar` 仍广播其他玩家的正常 ActionBar 与倒计时，只给当前真人追加无可压提示；在线判断统一使用 `onlinePlayer != null && isOnline()`。自动不要回调只调用一次正常回合续接链路，不重复 `refreshPhysicalTable`。机器人无可压继续沿用现有立即处理，不等待 1 秒；先手不能过与 `TimedOutPlayCoordinator` 的超时托管语义不变。
- `TrickHudService` / `TrickHudView`（`game`）：正式 Trick HUD 的数据组装与字形渲染分离，由牌桌对局状态、配置和玩家级调试棒行覆盖共同驱动；Service 产出 cell，View 负责 advance 宽度与居中算式。`/muz give debug` 保留为个人 HUD 调试棒：玩家未进牌桌时可显示与 Debug Web 对照的游戏内 Trick HUD；右键循环牌行、头像行、记牌行，Shift+右键隐藏。调试棒只影响持有者个人，不写入运行期正式配置；`/muz debug show|stick|hud` 继续移除。Debug Web 仅负责回环地址上的运行期 HUD 参数预览与配置。**记牌器行（counter row）使用纯文本 MiniMessage 渲染**：每格上行白字显示点数名、下行灰字显示剩余张数，格间用格子分隔线（详见「记牌器文字渲染」）。
- `HotbarHudService`（`game`）：热键栏 HUD，管理 hotbar 字形渲染与 Action Bar 推送。`GameTable` 的 ActionBar 路由走此 service，独立于 TrickHudService。自定义 5 槽的显示契约是：仅在 `hotbar-hud.enabled: true`、牌桌处于 `GamePhase.PLAYING` 正式出牌阶段、且座位为在线真人时显示。每 2 tick 从 `TableManager.getTables()` 筛选符合条件的非 bot 座位并推送一张 182×22 的不透明遮罩字形：遮罩完整盖住原版 9 槽背景，中央 5 槽使用红/橙/黄/绿/蓝调试配色；`LOBBY`、`BIDDING`、`DOUBLING`、结算、离桌、停服、关闭开关以及不在牌桌的普通玩家一律保持或恢复原版 9 槽物品栏。资源包不得覆盖 `minecraft` 原版 `hotbar.png` / `hotbar_selection.png`，因为该覆盖是客户端全局状态，无法只对牌桌玩家启用。所有非 `PLAYING` 阶段的对局提示仍走普通 ActionBar。水平偏移 `hotbar-hud.offset-x` 由 `setOffsetX` 注入（独立 setter，因为 `reloadEnabled(boolean, boolean)` 的签名被 `DoudizhuRuntimeSyncTest` 按文本锁死）。
- `DebugWebServer`（`debug/` 包）：内嵌 HTTP 服务器，监听 `debug.web-ui.port`（默认 2000）且仅绑定回环地址。提供可编辑 HUD 配置页；启用条件为 `debug.web-ui.enabled: true`，默认关闭。页面只编辑运行期配置键，不修改构建期 hotbar 字形参数。`/api/save` 与 `/api/reload` 通过 `HudWebApplyCoordinator` 串行执行：HTTP 线程先完成方法、Token、Content-Type、JSON 与大小校验，只提交任务；单线程异步阶段写 `config.yml` 和当前唯一的 hotbar CE overlay，写完后切主线程 dispatch `ce reload pack`，再执行专用 `reloadTrickHudSettings + syncHotbarHudRuntime`，最后才发布 Snapshot；空 patch 不触发资源流程，任一步失败都不报成功。协调器关闭后拒绝新任务，已排队任务在主线程入口再次检查 `closed/generation` 后失效。
- Web 保存与磁盘重载共用插件提供的 HUD 配置锁，形成 Web 自身的配置快照边界；配置文件 I/O 保持在异步线程，不放回主线程。该锁不等同于全局配置事务：现有管理菜单及其它非 Web 配置入口尚未全部接入，若它们并发改写共享 `MuzYamlConfig`，仍存在既有竞态，后续需统一配置层处理。
- `DebugHudConfigController`（`debug/` 包）：维护 Web HUD 白名单（19 键）、类型/档位校验、增量保存与不可变配置快照。保存只写用户提交的键，并通过 `saveWithComments()` 保留模板注释。当前全部 HUD 没有独立的运行期 CE 字形资源，Trick HUD 由运行时配置与玩家级调试棒行覆盖应用，不能臆造 Trick HUD 字形；运行期 CE 资源只有 hotbar overlay。`currentGeometry()` 由 Controller 统一从 `PackAssets`、`PlayerHeadRenderer`、`HotbarDebugOverlayWriter` 生成 `Snapshot.geometry`；前端只消费这份 geometry，不复算字形表。记牌器这次只共享水平 advance，垂直仍沿用现有普通 MiniMessage 预览语义。
- `HotbarDebugOverlayWriter`（`debug/` 包）：把 `hotbar-hud.offset-y` 写成 CraftEngine 覆盖层资源（`plugins/CraftEngine/resources/muz_hotbar_debug/`），实现 hotbar 底图垂直位置的**连续**调整。只生成 `pack.yml` + `configuration/images/hotbar_debug.yml`，**不生成 PNG**——贴图直接引用 bundle 的 `muz:font/hotbar_slots.png`，所以绘图逻辑仍只有构建期一份。其 `GLYPH_HEIGHT` / `BASE_ASCENT` / `minOffsetY()` / `maxOffsetY()` 直接作为 `PreviewGeometry` 的 hotbar 字段来源。写在独立命名空间目录是刻意的：`CraftEngineBundleExporter` 的清理与覆盖只作用于 `resources/muz` 子树，写进那里的运行期产物活不过下一次导出。文件 I/O 异步且由单线程协调器串行，写完切主线程触发 `ce reload pack`，确保客户端 `resource_pack.zip` 重新生成；客户端必须重新下载资源包才能看到新的纵向位置；若发现现有 ZIP 仍残留旧版全局 hotbar 条目，先备份并移走 `plugins/CraftEngine/generated/resource_pack.zip`，再执行该命令，最后检查 ZIP 条目确认清理结果。
- `PackAssets`（`assets`）：插件侧复算资源包字形码位与字体名，零文件 IO。必须与 `build.gradle.kts` 生成逻辑严格对齐，两侧不一致会导致游戏内显示豆腐块。
- `CraftEngineBundleExporter`（`compat`）：导出内置 CraftEngine bundle，并在指纹提前返回前无条件删除旧版本遗留的 `resourcepack/assets/minecraft/textures/gui/sprites/hud/hotbar.png` 与 `hotbar_selection.png`。这两张旧文件会全局影响客户端物品栏，不能因为 bundle 指纹已是最新而跳过清理。
- `PackTiers`（构建期生成）：档位容量常量，插件侧只读引用，**不要手改**。
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

#### 记牌器文字渲染

记牌器行（counter row）改用**纯文本 MiniMessage**，不再依赖资源包字形字符：

- 每格两行（借助 offset service 叠层）：**上行白字**显示点数标签（`3 4 5 6 7 8 9 10 J Q K A 小 大`），**下行灰字**显示剩余张数。
- 格与格之间用 `│` 等格子分隔线，视觉上呈网格。
- 出完的点数（剩 0 张）灰色半透明，不隐藏格子位置（与原来的 `dim` 字形行为一致）。
- 此渲染不依赖 `PackAssets.counterRankChar` / `counterDigitChar`，资源包未加载时仍可显示。

### 8. Debug Web 调试面板（`debug/` 包）

- 负责：内嵌 JDK `com.sun.net.httpserver.HttpServer`，监听 `debug.web-ui.port`（默认 2000）并仅绑定回环地址；提供可编辑 HUD 配置页。
- 启用条件：`debug.web-ui.enabled: true`（默认 `false`，生产环境不启动）。浏览器在服务端本机访问 `http://127.0.0.1:2000` 或 `http://localhost:2000`。
- 页面可编辑字段仅限 HUD 运行期配置，共 **19 个**：`trick-hud.enabled`、`trick-hud.avatar-scale`、`trick-hud.avatar-gap`、`trick-hud.card-step`、`trick-hud.card-height`、`trick-hud.offset-down`、`trick-hud.avatar-offset-down`、`trick-hud.offset-x`、`trick-hud.card-offset-x`、`trick-hud.avatar-offset-x`、`trick-hud.avatar-outline.enabled`、`trick-hud.avatar-outline.color`、`trick-hud.counter.enabled`、`trick-hud.counter.gap`、`trick-hud.counter.hide-exhausted`、`trick-hud.counter.offset-x`、`hotbar-hud.enabled`、`hotbar-hud.offset-x`、`hotbar-hud.offset-y`。
- 牌高、牌行下移、头像行下移等档位字段按当前资源包的合法档位白名单校验；非法值由后端拒绝保存，不静默改写成相邻档位。
- 页面右侧的预览按 **Minecraft 像素坐标系**绘制，屏幕几何由服务端固定下发：`screenWidth=640`、`screenHeight=360`、`bossBarBaselineY=20`、`actionBarBottomY=360`。各行宽度与字形前进量由 `DebugHudConfigController.currentGeometry()` 汇总 `PackAssets`、`PlayerHeadRenderer`、`HotbarDebugOverlayWriter` 后随 `Snapshot.geometry` 下发，前端**只消费 `PreviewGeometry` 数组**（`cards[{tier,height,width,advance}]` / `avatars[{scale,plainAdvance,outlinedAdvance,rowHeight}]` / `counters[{label,remaining,advance}]` + hotbar 六项），不复算任何字形表（避免把「两处手写表不同步」的问题搬到前端）。预览复刻了游戏内的居中规则并**用整数 MC 像素**执行：`W = max(cardRowWidth, avatarRowWidth, counterRowWidth)`，`baseLeft = floor((screenWidth - W)/2)`，每行前再垫 `floor((W - 行宽)/2)`。BossBar 上的牌行/头像行走 `baseline - ascent` 公式（`cardAscent = card.height - offset-down`、`avatarAscent = avatar.rowHeight - avatar-offset-down`，`top = bossBarBaselineY - ascent`，因此 `offset-down` 增大会让行下移）。Hotbar 走 `hbY = actionBarBottomY - hotbarHeight + (hotbarBaseAscent - (hotbarBaseAscent - offset-y))`、`hbX = floor((screenWidth - hotbarAdvance)/2) + offset-x`。记牌行只共享每格水平 `advance` 用于精确定位，垂直仍沿用现有普通 MiniMessage 预览语义（不引入 `counterCellHeight` / `counterLineHeight` / `counterBaselineY` 字段）。GUI 缩放由页面选择器控制（2/3/4，默认 3），只影响 CSS 放大倍数，不影响下发的 MC 像素值。
- **预览各层可鼠标拖动**：牌行、头像行、记牌行、hotbar 各有一个拖动层，拖动位移按 `CSS 位移 ÷ 预览缩放` 换算回 MC 像素后写入对应偏移键（档位型字段会吸附到资源包实际生成的最近合法档，避免写出后端必然拒绝的值）。页面提供真正可操作的 `snapToggle` Minecraft 风格吸附开关，默认开启；它只改变当前页面的拖动行为，不属于 19 个 HUD patch，阈值按实际 layer 几何使用 MC 像素。中心线与 Alt 首次有效位移锁轴状态仍保留。真实指针拖动期间只改当前层的 CSS `transform`，不重建预览 DOM；`pointerup`/`pointercancel` 后才完整 `renderPreview()`。拖动只标记 dirty，须点「保存并应用」才写回 `config.yml`。
- 「保存并应用」只写回本次提交的白名单键并应用到当前运行态；保存仍严格执行“异步 config I/O + hotbar overlay YAML → 主线程 `ce reload pack` → 主线程 `applyHudRuntimeStateFromWeb()` → 发布结果”的顺序，空 patch 不触发资源流程，任一步失败不得报成功。「重新读取配置」只从磁盘重新载入现有 `config.yml`，会丢弃页面上尚未保存的改动。
- hotbar overlay 的 `pack.yml` 与 `hotbar_debug.yml` 均先写临时文件再原子替换，异步阶段不访问 Bukkit PluginManager；协调器每次提交在主线程重新解析 CraftEngine overlay 路径，关闭后拒绝新任务且已排队任务会失效，避免关闭后继续应用。
- Hotbar 是否推送由 `hotbar-hud.enabled`、`GamePhase.PLAYING` 正式出牌阶段、牌桌座位是否为在线真人共同决定；只有三项同时满足才进入自定义 5 槽推送集合。`LOBBY`、`BIDDING`、`DOUBLING`、结算、离桌、停服及普通玩家保持原版 9 槽，非 `PLAYING` 对局提示走普通 ActionBar。资源包不得生成或覆盖 `minecraft` 原版 `hotbar.png` / `hotbar_selection.png`。**注意语义已变更**：`HotbarHudService.reloadEnabled(configuredEnabled, suspended)` 的 `suspended` 参数（Debug Web 接管标记）原先会直接 `stop()` 推送，现在改为「Web 接管定位参数」——符合 PLAYING 条件时推送照常，只把字形从 bundle 固定 ascent 的码位（`0xEF00`）切到覆盖层可拖 ascent 的码位（`0xEF01`）。改回「接管即停推送」会让拖动 hotbar 时游戏内看不到任何变化，`DoudizhuRuntimeSyncTest.DebugWeb接管时Hotbar仍继续推送` 守这条。
- **hotbar 定位的两个方向机制完全不同，不要混谈**：
  - `hotbar-hud.offset-x` 走 CraftEngine 负空格，**运行期即时生效**，任意整数像素，不需要重新构建或重下资源包。渲染侧必须首尾对称抵消偏移量（见 `HotbarHudService.buildActionBar` 注释），否则客户端按总宽居中会把偏移吃掉一半。
  - `hotbar-hud.offset-y` 必须落在位图字形的 `ascent` 上，属于**客户端资源内容**。由 `HotbarDebugOverlayWriter` 运行期写出覆盖层（`ascent = -128 - offset-y`，`height` 恒 22 保证 1:1 不缩放），再自动执行 `ce reload pack` 重新打包，**客户端需重新下载资源包才能看到**。所以纵向调整不是即时的，每改一次都要走一轮重打包。
- 仍然禁止通过运行期配置修改构建期烘焙参数：`hotbar-hud.glyph-ascent`、`hotbar-hud.slot-count` 这两个键**运行期从未被读取**（纯文档键，记录构建期约定）。当前 hotbar 遮罩字形固定为 **182×22**、advance 为 **183**，中央 5 槽占 108px、左右各 37px 为不透明遮罩；尺寸、码位与 advance 必须同时修改 `build.gradle.kts` 和 `PackAssets`。要调垂直位置请用 `offset-y` 走覆盖层，不要改这些构建期约定。
- `onDisable` 必须 `stop()` HttpServer，避免端口占用导致下次启动失败。
- `DebugWebServerTest` 已覆盖 geometry schema、15 格记牌器 fixture、PackAssets/PlayerHeadRenderer/HotbarDebugOverlayWriter 资源 API 对齐、旧几何魔数消失，以及 pointermove 拖动期间不重建 DOM、松手后再刷新。
- 游戏内通过 `/muz debug web [start|stop]`（需 `muz.admin` 权限）查看状态或手动启停；不带子参数时显示运行状态与访问地址。`/muz give debug` 必须保留，用于发放个人 HUD 调试棒：玩家未进牌桌时可显示与 Debug Web 对照的游戏内 Trick HUD，右键循环牌行、头像行、记牌行，Shift+右键隐藏；调试棒只影响持有者个人，不写入运行期正式配置。旧 `/muz debug show`、`/muz debug stick`、`/muz debug hud` 入口继续移除，不得恢复为正式 HUD 控制路径。
- `DebugWebServer.getPort()` 返回当前监听端口（未运行时为 0）。
- **`/muz reload` 会同步 `debugWebServer` 生命周期**：`reloadVisualState()` 里检测 `debug.web-ui.enabled` 变化——`enabled` 由 false→true 时自动新建并启动，由 true→false 时自动停止并置 null。因此修改 config.yml 后执行 `/muz reload` 即可生效，无需重启服务端。
- 不负责：对局规则、皮肤渲染、资源包生成。禁止在 Web 请求线程直接操作 Bukkit API，须 `BukkitScheduler.runTask` 切回主线程。

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

- 现状：存在独立子领域，与斗地主对局状态互不污染。
- 要求：新增功能不要跨领域复用 `GameTable`。

## 推荐包结构

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
├── mahjong/                麻将子领域
└── room/ · scheduler/ · action/ · placeholder/
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

## Folia 支持状态

`folia-supported: false`（2026-08 起）。原先声明 `true` 但 `MuzScheduler` 五个方法全走 `BukkitScheduler`，Folia 已废弃该接口，调用即抛 `UnsupportedOperationException`——声明支持却跑不起来比不声明更糟，因此改为 `false`。

要恢复 `true`，必须先把 `MuzScheduler` 换成 `GlobalRegionScheduler` / `RegionScheduler` / `EntityScheduler` / `AsyncScheduler` 分发，并逐个确认实体操作跑在正确的区域线程上。`FoliaDeclarationMatchesSchedulerTest` 会把声明与调度层实现绑定校验，改一边不改另一边会红。

注意 `TrickHudService` 的 `snapshot` 字段仍保留 `volatile` 与整份替换语义，那是为未来的区域线程读做的准备，不要因为现在不支持 Folia 就摘掉。

## 当前技术栈

| 层 | 技术 | 备注 |
|---|---|---|
| 平台 | Paper / Purpur，`folia-supported: false` | 目标 API 由 `MuzTarget` 表驱动，默认 `paper-26.2` |
| 主框架 | 原生 Bukkit `JavaPlugin` + TabooLib（工具层） | 反射等工具方法走 TabooLib；不用注解生命周期 |
| 语言 | Java（主源码 75 文件，0 Kotlin 源码） | Kotlin 仅用于 Gradle DSL |
| 渲染 | Display Entity + MiniMessage / Adventure | 字形由资源包驱动 |
| 配置 | `org.yaml:snakeyaml:2.6`（已 relocate） | 封装于 `MuzYamlConfig` |
| 存储 | `sqlite-jdbc:3.46.1.0`，可选 `mysql-connector-j:8.4.0` | MySQL 为 `compileOnly` |
| 序列化 | `gson:2.11.0` | |
| 可选依赖 | CraftEngine 0.0.67、PlaceholderAPI 2.12.2、VaultAPI 1.7 | 全部 `compileOnly` |
| 构建 | Gradle + Kotlin DSL + `com.gradleup.shadow:9.3.0` | 产物在 `build/<targetId>/` |
| 测试 | JUnit 5（junit-bom 5.13.4），94 个测试文件 | 仓库路径含中文时 Gradle 测试 worker 会报 `ClassNotFoundException`，需改用独立 JUnit Launcher 实跑（classpath 用 argfile 传，避免 MSYS2 搅坏 `;` 分隔符） |

## 开发优先级建议

1. **对局体验闭环**：规则正确性 > 渲染精度 > 交互手感。
2. **渲染与资源包一致性**：构建期与插件侧码位、宽度、档位必须严格对齐。
3. **配置与可维护性**：默认值合理、校验完备、迁移清晰。
4. **性能与安全**：异步 I/O、实体数量控制、异常不吞。
5. **技术债偿还**：`DoudizhuPlugin` 拆分、玩家文本外置。需先获批准。

## 明确禁止事项

- 禁止偏离「实体化牌桌对局」核心领域，禁止塞入无关业务系统。
- 禁止把插件源码改成 Kotlin，除非 linmumua 明确要求。禁止把架构改成 TabooLib 注解驱动生命周期。
- 禁止用 `YamlConfiguration` 或 Jackson YAML 作为运行期 YAML 主方案。
- 禁止作者字段出现 `linmumua` 以外的名字。
- 禁止 AI 在任何位置写入自己的署名或协作痕迹（见「禁止 AI 署名」）。
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

## AI / Codex 执行要求

- 参与开发前必须先读本文档，并核对仓库实际事实，不要凭默认模板假设架构。
- 本项目使用 TabooLib 作为工具库（反射等），但**不是 TabooLib 注解生命周期项目**。不要把架构改成 TabooLib 注解驱动，不要建议用 `@Inject`、`@Awake` 等注解替换现有装配方式。
- 修改代码必须遵守领域边界，不主动扩展无关功能。
- 涉及资源包字形、码位、档位时，必须同时检查 `build.gradle.kts` 生成侧与 `PackAssets` 复算侧，两侧不一致会导致游戏内显示豆腐块。
- 新增玩家可见文本优先走配置；若与周边硬编码风格冲突，按既有风格实现但必须指出技术债。
- 大范围重构（文本外置、入口类拆分、架构替换）必须先说明风险与影响面，取得同意后再动手。
- 发现需求与项目核心或既有约定冲突时，先说明冲突，再给收敛方案，不要静默按自己的偏好实现。
- 修改测试断言时必须说明改的是「断言编码了过时格式」还是「实现有 bug」，禁止为了变绿而弱化断言。
- 报告结论时必须区分「已验证」与「未验证」，禁止把假设当事实。
- 声称「测试通过」前必须实际执行测试并核对输出，禁止采信未执行的验证结果。

