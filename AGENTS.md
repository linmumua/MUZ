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

- 版本号写在 `build.gradle.kts` 的 `version`，当前源码版本为 `1.10.51`（**1.10.51 唯一改动**：把原本只靠 `runtimeClasspath` 进包的 `gson:2.11.0` 与 `sqlite-jdbc:3.46.1.0` 显式并入 `embeddedLibraries` 并明确**保持原包名、不重定位**，同时在 `verifyRelocatedSnakeYaml` 与 `build/muz-release-audit.py` 各加一条「内嵌且原包名必须存在」的反向断言，把该决策固化为可执行契约；此前 1.10.50 的内容为：实体筹码已改为按背包与副手实际物品计数；桌内道具改为九格图标额外栏，第九格为语音入口；本轮继续迁移 Folia 牌桌 owner，新增单桌 region 世界刷新与自有实体 owner PDC，并继续收口 GameTable、TrickHudService、PhysicalTableManager、世界桌/手牌 GUI 监听器和麻将广播的 player lane；本轮又把 `rebuildAllTables`/`rebuildSingleTable`/`shiftAllAnchors` 改为「3×3 footprint 异步预热 + 锚点 region 世界体 + global 收口」的顺序化 stage 流水线；本轮又让重建在收口成功前**保留旧放置快照与索引**（不再提前 clear/remove + 切回未放置）、把身份/关闭/放置代次闸门**前移到 spawn 之前**并对无法提交的新桌做锚点 region 清理、并把 warmup/post-restore 门禁改为不依赖瞬时 placedTableCount；本轮又把 TabooLib 实际接入构建与反射路径，并在冒烟结束后**把 `folia-supported` 恢复为 `false`**，仍未声明 Folia 支持。本轮验证结果见下方记录）。`CLAUDE.md` 的版本约定必须与此保持同步。`paper-plugin.yml` 用 `${version}` 占位，不要手写。
  - **当前版本事实（勿与历史记录混用）**：版本 `1.10.51`；`folia-supported: false`（冒烟临时 `true` 已回滚）；TabooLib **已实际接入**（构建插件 + `common-reflex` 内嵌 + 源码 import）；内嵌库集合为 SnakeYAML（重定位）+ gson / sqlite-jdbc（**不重定位**）+ common-reflex 及其运行期依赖；**关闭清理仍未完成**（`onDisable` 内的 owner 清理注册会被 `MuzScheduler` 关闭闸门拒绝，不得宣称已生效）。
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
| `table-gadgets` | 桌内九格道具箱、道具效果与私有气泡语音实体面板；八个可持久化道具槽 + 固定第九格语音入口，互动只服务 `PLAYING` 阶段同桌在线真人 |
| `cards` / `audio` / `actionbar` | 牌面、音效、动作栏；资源包内 125 个 OGG 逐个使用 ffmpeg 压缩，保持相对路径、文件名与 OGG Vorbis，仅以有效且更小的结果替换 |
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
- 尺寸与缩放档位仍有合法值白名单，且只能取 `muz-resource-profile.yml` 当前已生成的离散资源：牌高 `53`、头像缩放 `4/6`、记牌器缩放 `100`。Trick 的三个 Y 字段 `offset-down`、`avatar-offset-down`、`counter.offset-down` 均为连续整数 `-128..512`；非法值必须拒绝或明确报错，不得 nearest/静默回退。原 profile 的 Y 档 `0/50` 与 `0/122` 继续作为基础 bundle 兼容，不扩展 profile 的全 Y 档。
- 玩家个人数据与运行时状态不要混进 `config.yml`，走 `player-settings` / 存储层。

## 领域模型约束

- `DoudizhuPlugin`：插件入口与全局装配。**注意：当前已超过 5500 行，是事实上的 God Class。** 禁止继续往里堆新逻辑，新功能应落到对应 service。
- `GameTable`（`game`）：单桌对局状态机，持有座位、手牌、出牌历史、剩余张数统计。出牌阶段真人跟牌若 `MoveAdvisor` 判定无可压，会保留 `currentTurn` 等待 20 tick；等待期间可用现有「不要」手动跳过，在线真人仍未响应才由独立 epoch/token 二次校验后自动不要。等待期间 `tickActionBar` 仍广播其他玩家的正常 ActionBar 与倒计时，只给当前真人追加无可压提示；在线判断已收口到 player lane：对局内资格/在线判断统一经 `PlayerOutputDispatcher.currentPlayer(UUID)`（其解析器必须显式过滤 `isOnline()`，见该类的门禁注释），不再走已移除的 `GameTable.onlinePlayer`；明牌资格 `canRevealHand` 与轮转判定都委托 `isPlayerPresent`，因此解析器一旦漏掉在线门禁，离线真人会被当成在线并拿到明牌资格（本轮修复的真实回归）。自动不要回调只调用一次正常回合续接链路，不重复 `refreshPhysicalTable`。机器人无可压继续沿用现有立即处理，不等待 1 秒；先手不能过与 `TimedOutPlayCoordinator` 的超时托管语义不变。
- `TableEffectCoordinator`（`game`）：桌内普通音效、出牌语音和倒计时统一从此出口播放；按玩家+音效做短窗口去重，倒计时按 16 tick 窗口抑制重复触发，避免重复事件造成音效叠加，不吞掉不同玩家或不同音效。
- `TableMusicCoordinator`（`game`）：背景音乐独立维护活动会话、轮播任务句柄和实际收听者 UUID。只有合法开局才能激活；结束先禁用会话、递增 epoch 并取消轮播，再向实际收听者与当前座位停止 `PackSounds.bgmTracks()` 的五首曲目。停止后即使牌桌尚未切回 LOBBY，`updateState` 或迟到回调也不得重启；旧局任务不得影响新局。只停止本插件背景曲目，不使用 `stopAllSounds`，不影响胜负音效或其他玩家。同一活动会话的重复开局请求必须幂等；每次轮播消费都递增 epoch，已执行回调不得再切曲或取消新任务。每次播放前必须先停止五首 BGM，测试须记录调用顺序与模拟活跃曲目，不能仅断言调用总数。普通提示音允许与 BGM 混音；可配置提示音及动作不能配置为 MUZ BGM，否则会绕过本协调器，外部插件/命令的声音不属于本协调器的互斥范围。
- `TrickHudService` / `TrickHudView`（`game`）：正式 Trick HUD 的数据组装与字形渲染分离，由牌桌对局状态、配置和玩家级调试棒行覆盖共同驱动；Service 产出 cell，View 负责 advance 宽度与居中算式。`/muz give debug` 保留为个人 HUD 调试棒：玩家未进牌桌时可显示与 Debug Web 对照的游戏内 Trick HUD；右键循环牌行、头像行、记牌行，Shift+右键隐藏。调试棒只影响持有者个人，不写入运行期正式配置；`/muz debug show|stick|hud` 继续移除。Debug Web 仅负责回环地址上的运行期 HUD 参数预览与配置。**记牌器行（counter row）显示本局累计已出张数**：固定 15 格，使用资源包分层位图 glyph 叠加牌类、矩形框和数字三层，不再使用纯文本 MiniMessage 作为正式记牌器渲染（详见「记牌器分层字形渲染」）。
- `PhysicalChipService`（`game`）：实体筹码唯一库存入口；以配置物品完整元数据匹配（忽略数量），统计主背包 0..35 与副手，不重复主手、不统计护甲/光标/容器内部。一件为一筹码，主线程实时读写，不持久化独立余额；设置模板不兑换旧物品或旧 `chip-balance`。增删与多人转移先预演全部槽位，批量转移必须零和并先扣后发；容量/数量不足时预检拒绝，写入异常按原槽位回滚，回滚失败必须明确提示库存需核查。`DoudizhuPlugin` 只委托；`RoundSettlementCoordinator` 对筹码使用批结算，不沿用 Vault 的逐人欠账语义；实体筹码付费局禁止机器人开局，正常结算具有单局防重入保护。离线/异步查询不返回伪造余额，旧余额不兑换成物品。
- `ActionBarOverlayService`（`game`）：承载对局普通 ActionBar 提示及短时叠加状态，不读取资源包或 CraftEngine。正式运行期不再构造退役的三图标 `HotbarHudService`；桌内道具栏改由 `TableGadgetBarHudService` 消费独立 `muz_gadget_bar` 九格字体资源，前八格显示虚拟道具图标，第九格显示语音入口，不覆盖原版 `hotbar.png`。该服务仅在 PLAYING 阶段同桌在线真人显示，滚轮/数字键选择槽位，右键第九格打开私有语音面板；Bukkit 九格 Inventory GUI 仅保留配置/编辑用途，不作为对局显示入口。**（勘误 2026-09-23：上面那句「桌内道具栏…由 TableGadgetBarHudService 消费…显示」此前隐含它经 ActionBar 显示，现已过时且不成立——九格栏已并入出牌 HUD（BossBar 第四行），由 `TrickHudService` 经 `TableGadgetBarHudService.hudBarGlyphText` 只读字形快照渲染；ActionBar 从此只给普通状态提示，两者不再抢同一槽位。第四行竖直位置由构建期固定下移档 `GADGET_BAR_ROW_DOWN_OFFSET` 决定，无独立配置键。详见「本轮：九格道具栏并入出牌 HUD BossBar（2026-09-23）」。原句保留不改，仅此括注。**）
- `CeActionExecutor`（`action`）：玩家消息、ActionBar、标题、玩家命令和玩家声音的执行路径已通过 `PlayerOutputDispatcher` 投递到 player lane；控制台命令仍通过 global 调度并使用 `Bukkit.getConsoleSender()`，不冒充玩家输出迁移。预览路径的消息、ActionBar、标题和声音同样走门面，命令行为只提示不能直接预览。该迁移只覆盖 CE 行为执行器，不代表全项目直接 `Player` API 已迁移。
- `TableGadgetService`、`TableGadgetSettings`、`TableGadgetEffectService` 与 `model.TableGadget`：桌内三道具互动的选择、资格、目标、冷却、并发和效果边界。`GadgetBoxGuiService` / `TableGadgetGuiService` 提供九格道具箱，`VirtualGadgetBarStore` 使用 `MuzYamlConfig`/SnakeYAML 将八个玩家槽位持久化到 `player-settings.yml`；配置路径缺失表示默认鸡蛋/水桶/番茄，显式空列表表示玩家已清空。`TableSpeechPanelService` 通过仅对发起者可见的 Display Entity 构造气泡语音面板，第九格固定为入口，不写入玩家道具栏。仅服务 `PLAYING` 阶段同桌在线真人；机器人、旁观者、跨桌玩家和无效目标不参与。目标高亮使用对附近观察者全局可见的短时真实发光，带引用计数，记录并恢复既有状态，多引用和所有离桌/死亡/传送/断线/关桌路径必须清理；清理目标时同时删除指向该目标的旧 actor 状态，避免同 UUID 返回后被短路。CE `FurnitureInteractEvent` 在手牌仲裁之后进入同一道具右键路由。鸡蛋/番茄为短暂无伤害投掷，水桶为贴身短时透明水幕，不放置真实水方块、不造成伤害或扩散。水幕实体与头顶水桶按两个实体计入上限；效果实体/粒子必须统一登记、限额和清理，不得遗留。`clearAll()` 用于可恢复 stop/reload，`shutdown()` 仅用于插件最终关闭。PlayerItemHeldEvent 无法严格区分滚轮与数字键，统一按同一虚拟索引映射处理。
- `DebugWebServer`（`debug/` 包）：内嵌 HTTP 服务只监听回环地址，正式页面与根目录原型均收敛为牌行、头像行、记牌器行三个可拖动图层；字段白名单固定 18 个 `trick-hud.*` 运行期键。页面只消费服务端下发的 `geometry.cards` / `avatars` / `counterTiers` 与白名单真实 PNG，不复算码位或字体宽度，不再下发 Hotbar geometry、图标或选中框。`/api/save` 与 `/api/reload` 继续通过 `HudWebApplyCoordinator` 串行执行异步 SnakeYAML 配置/三层 overlay、主线程 CE reload、异步生成并校验实际 ZIP、主线程发布快照；失败回滚三层自有资源并清 ready，客户端仍需重新下载资源包。
- Web 保存与磁盘重载共用插件提供的 HUD 配置锁，形成 Web 自身的配置快照边界；配置文件 I/O 保持在异步线程，不放回主线程。该锁不等同于全局配置事务：现有管理菜单及其它非 Web 配置入口尚未全部接入，若它们并发改写共享 `MuzYamlConfig`，仍存在既有竞态，后续需统一配置层处理。
- `DebugHudConfigController`（`debug/` 包）：维护 Trick HUD 白名单（18 键）、类型校验、增量保存与不可变配置快照。保存只写用户提交的键，并通过 `saveWithComments()` 保留模板注释。三个 Y 字段按连续整数校验，尺寸与 scale 仍按当前 profile 离散白名单校验；`currentGeometry()` 统一下发三层 geometry。`HudResourceRequest` 的 Hotbar 字段仅为兼容保留，不参与资源校验。
- `HudOverlayWriter`（`debug/` 包）：只为牌行、头像、记牌器三层 HUD 写出 `trick_hud_continuous.yml`，并在需要时生成 `resourcepack/assets/muz/textures/font/continuous/` 下的 padding PNG。覆盖层使用独立 `baseFont_continuous` 与 base tier 0 char；`ascent=baseAscent-rawY`，向上补透明行时保持可见尺寸与 advance 不变。文件 I/O 异步且由三层资源协调器串行。
- `PackAssets`（`assets`）：插件侧复算牌面、头像、记牌器字形码位与 geometry，必须与 `build.gradle.kts` 生成逻辑严格对齐。Hotbar 常量和方法仅为旧源码兼容保留，构建期不再生成对应 provider、字体或 PNG，正式运行期也不得消费。
- `CraftEngineBundleExporter`（`compat`）：导出内置 CraftEngine bundle，并在指纹提前返回前无条件删除旧版本遗留的原版 Hotbar sprite、`configuration/images/hotbar*.yml`、字体目录 `hotbar_*.png` 与旧 `muz_hotbar_debug` overlay 目录。旧文件可能继续进入客户端资源包，不能因为 bundle 指纹已是最新而跳过清理；桌内道具 `item/table_gadget_*` 资源不在清理范围。
- `CraftEngineHudResourceBridge`（`compat`）：仅在 CE 可用时创建，接收不可变 `HudResourceRequest`，按“配置与三层资源异步事务 → 主线程真实 CE reload → 异步 generateResourcePack → 实际 ZIP 字形/PNG 校验 → 主线程 `markVerified`/apply/snapshot”执行；Hotbar 官方字体归档及其运行期加载入口已退役；编译依赖 0.0.67 未提供 `issues()`，失败详情使用 `toString()`；缺失或签名不兼容时明确失败，不通过命令返回值推测完成。不声称隔离管理员自行发起的 CE 重载。
- `HudResourcePackVerifier`（`compat`）：异步读取实际 ZIP 中央目录并校验长度、CRC、pack 格式、Trick 字体声明、当前 profile PNG 及桌内道具独立 item 资源；继续拒绝原版 `hotbar.png` / `hotbar_selection.png` 覆盖及旧 Hotbar provider。服务端内容校验、远端上传完成、客户端应用仍是三件事，后两者无回执时保持未确认。
- `HudOverlayRuntimeState`（`game`）：保存 Trick 三层 `HudResourceRequest` 的已验证 ready 快照；任何资源流程失败都必须清除 ready，不能恢复先前 ready（CraftEngine 可能已经部分 reload）；连续非 profile Y 未 ready 时正式 HUD 必须隐藏并告警，不得 nearest，精确的旧 bundle 档位可兼容。Legacy offset-only Hotbar 写入口必须显式拒绝，只有完整 `HudResourceRequest` 才能写入/应用资源。`HudResourceRecoveryService`（`debug`）独立负责启动、`/muz reload` 与 CraftEngine enable 后的资源恢复，不受 Debug Web 开关影响；入口类只做装配委托，不堆业务逻辑。
- `PackTiers`（构建期生成）：档位容量常量，插件侧只读引用，**不要手改**。
- `MuzYamlConfig`（`config`）：SnakeYAML 读写封装。`readOnlyRoot(Path)` 为预览提供严格只读解析：损坏文件报错，不触发隔离重命名或补写。
- `GadgetPreviewSnapshotService`（`debug`）：异步读取 `VirtualGadgetBarStore.loadRaw`，主线程解码物品并发布不可变玩家快照，使用代次阻止旧读覆盖保存后状态；Web 只显示八槽内非空物品，顺序与重复项保留，不新增游戏 HUD。
- `model`：`CardRank`、牌型定义等纯数据对象，不含 IO 与 Bukkit 依赖。
- `storage`：存档读写，须异步执行。
- `compat`：CraftEngine / Vault / PlaceholderAPI 桥接，必须做存在性判断与降级。

边界要求：配置读取、运行时状态、渲染执行、外部桥接必须分离。禁止在 model 里碰 Bukkit API，禁止在 View 里读配置。

## 功能边界

### 1. 对局核心（`game`）

- 负责：发牌、叫地主、牌型校验、倍数、结算、剩余张数统计、机器人回合驱动。
- 不负责：渲染实体的具体摆放、经济扣款的最终落库、玩家权限判断。

### 2. 渲染与 HUD（`game` 的 View 层、`world`）

- 负责：Display Entity 生命周期、坐标与朝向、字形拼装、宽度居中算式、悬停动画。手牌 Hover/点击共用 `HandCardPickGeometry` 命中包络；当前仅收紧实际触发区域至 0.90 倍，不改变牌面显示尺寸。
- 不负责：对局规则、配置校验、玩家数据持久化。
- **桌边动态浮空字可见性**：`playDetail`（桌子上方「桌边动态」`TextDisplay`，`buildPlayDetail` 拼装）是公共实体，默认对所有人可见。开局后（`table.getPhase() != GamePhase.LOBBY`）对**坐在本桌的在线真人**隐藏，避免浮空字挡住其低头看牌；大厅阶段、旁观者、机器人一律照常显示。判定走 static 纯函数 `PhysicalTableManager.playDetailHiddenForSeatedPlayer(phase, seatedHuman)`（便于单测锁定「开局后才隐藏」边界，见 `PlayDetailVisibilityTest`）；按人可见性由 `updatePlayDetailVisibility` 遍历在线玩家 `show/hide`，在 `refreshPlayDetail`（`refresh`/`tick` 每 2 秒）与 `syncViewer` 末尾统一重算。`hideEntity` 状态按玩家持久，离桌或本局结束回 LOBBY 后靠下一次 refresh/tick 自动恢复可见，不单独改离桌路径。写法与 `updateSeatInfoVisibility`（座位名/信息对本人隐藏）一致。

#### 记牌器分层字形渲染

记牌器行（counter row）显示**本局累计已出张数**，不是当前剩余张数：

- 固定按 `CardRank.values()` 输出 15 格；普通点数按初始 4 张、小王/大王按初始 1 张，由剩余数推导累计已出数。`GameTable.getPlayedCounts()` 返回钳制到合法范围的只读快照，不维护第二份可变计数。
- 码位按每档 22 个声明排列：标签 `0..14`、数字 `15..19`、普通/耗尽框 `20..21`，当前 profile 仅生成 `scale=100` 的 `offset=0/122` 两档，共 44 条声明；offset 0 从 `0xE900` 起、offset 122 从 `0xE916` 起，当前范围为 `0xE900..0xE92B`。码位排列与运行期绘制顺序是不同概念；基础 PNG 仅 22 张，偏移档只增加 provider 声明。
- 每格使用紧凑仪表盘风格的资源包分层位图 glyph，按 `label → frame → digit` 顺序叠加三层：上方牌类标签、通用徽章框、下方累计数量；不生成“点数×数量×亮暗”的组合 PNG。基础几何为 label/frame `21×12px`、digit `21×8px`，images.yml 的垂直契约固定为 `label ascent=12-downOffset`、`frame ascent=-3-downOffset`、`digit ascent=-6-downOffset`；View 对后两层按当前档位 advance 回退，三层叠在同一格。
- 每格水平 advance 固定为 `22px`，默认 `counter.gap=2` 时固定 15 格总宽为 `15×22 + 14×2 = 358px`；三层 cell 视觉高度为 `27px`（框顶差 15px、数字内缩 3px）。label/digit PNG 右下角保留不可见 alpha=1 锚点，确保 Minecraft BitmapProvider 按 21px 实际宽度扫描；耗尽或隐藏时仍保留格子占位，后续格子不左移。
- 游戏内记牌器使用固定几何与服务端下发的分层 cell 数据，不依赖 MiniMessage 默认字体的中文或分隔线宽度。资源契约测试逐档检查每档 22 个 glyph（15 个 label、5 个 digit、普通/耗尽 frame）、PNG 尺寸与 alpha=1 右下角锚点；构建期 label/digit PNG 使用固定整数像素 bitmap：普通牌类为 7×9、10 为 11×9、王牌为 9×9，数字为 5×7，不经过抗锯齿。Debug Web 直接加载同一批 label/frame/digit 真实 PNG 并使用像素化采样，不能据此推导客户端字体替换。

### 8. Debug Web 调试面板（`debug/` 包）

- 负责：内嵌 JDK `com.sun.net.httpserver.HttpServer`，监听 `debug.web-ui.port`（默认 2000）并仅绑定回环地址；提供可编辑 HUD 配置页。
- 启用条件：`debug.web-ui.enabled: true`（默认 `false`，生产环境不启动）。浏览器在服务端本机访问 `http://127.0.0.1:2000` 或 `http://localhost:2000`。
- 页面可编辑字段固定为 **18 个** `trick-hud.*` 键：总开关、牌高/步进/偏移、头像缩放/间距/偏移/描边，以及记牌器开关/缩放/偏移/间距/耗尽隐藏。旧 `hotbar-hud.*` 不再进入白名单。
- 牌高、头像缩放与记牌器缩放按当前 profile 离散白名单校验；牌行、头像行、记牌器行三个 Y 偏移允许 `-128..512` 连续整数，非法值明确拒绝，不 nearest。
- 页面按 640×360 Minecraft 逻辑坐标绘制三层预览，行宽、advance、资源路径只取自服务端 `geometry.cards` / `avatars` / `counterTiers` 与白名单 manifest；GUI 缩放只影响浏览器 CSS 显示比例，不改变 MC 像素或客户端字体几何。
- **三层预览均可鼠标拖动**：CSS 位移除以当前 GUI 缩放后换算为 MC 像素；吸附默认关闭且只影响网页交互。拖动期间只更新当前节点样式，松手后才完整重绘，保存前仅标记 dirty。
- 「保存并应用」只写本次提交的 18 个白名单键；严格执行异步配置与三层资源事务、主线程 CE reload、异步生成与 ZIP 校验、主线程发布快照。空 patch 不触发资源流程，失败回滚三层自有资源并清 ready。
- 旧 `hotbar_debug.yml` 与三道具 Hotbar overlay 已退役；导出器只负责删除历史残留，不得重新生成。
- 桌内道具不再通过 ActionBar Hotbar 推送；玩家在 `PLAYING` 阶段使用九格道具箱，普通对局提示统一走 `ActionBarOverlayService`。
- `onDisable` 必须 `stop()` HttpServer，避免端口占用导致下次启动失败。
- `DebugWebServerTest` 与浏览器契约覆盖三层 geometry、真实 PNG、脚本可执行、拖动期间 DOM 稳定、松手重绘、Fullscreen 降级、快捷键与窄屏防溢出；资源契约覆盖固定 15 格分层记牌器、三种独立桌内道具 item 纹理及原版 Hotbar sprite 禁止项。
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
- 实体 owner：`MahjongTableManager` 的 Display/Interaction 创建与删除一律直接投递到**桌锚点 region**。`rerender(table)` 带「代次 + 实例身份」闸门淘汰迟到重绘；`removeVisuals(table)` 是终结删除，**不带**该闸门，因为 `removeTable`/`shutdown` 都会先把桌移出注册表，再要求「仍在注册表且代次不变」会让清理被自己的屏障挡掉、实体永久残留。`runOnTableRegion(table, task)` 是带同一闸门的通用桌 owner 入口，当前**无生产调用方**，只由契约测试钉住形状。
- 玩家输出：麻将全部经 `PlayerOutputDispatcher` 按 UUID 走 player lane；`broadcast` 只消费桌内 UUID 快照并逐条投递，不在生产者线程解析或直接触碰玩家 API。
- 关闭：`MahjongTableManager.shutdown()` 幂等；按 `plugin.getServer().isStopping()` 分支——关服只推进代次并 `clearVisuals()` 清追踪、不在非法 owner 上操作实体（与 `PhysicalTableManager.shutdown` 一致）；reload 分支把每张桌登记为 `RegionTaskBarrier.Request` 在桌 region 内联清理，并以 `shutdownCompletion()` 暴露完成屏障，只观察/记录失败日志、**不阻塞主线程**（`onDisable` 在主线程上，阻塞等待会饿死 Paper 的 region 任务）。
- 共享保护链：麻将实体 tag `muz_mahjong_protected` 已并入 `PhysicalTableManager.isProtectedEntity`，麻将实体因此获得破坏保护（攻击/破坏入口与 `CraftEngineProtectionListener` 一起生效）。两个子领域的 tag 字符串只在 `TableEntityGeometry.PROTECTED_TAGS` 登记一次，判定统一走 `TableEntityGeometry.hasProtectionTag(Entity)`；新增子领域必须在这里登记，不要各写各的字符串。**残留清理仍然只认牌桌 tag**（`PhysicalTableManager.PROTECTED_ENTITY_TAG`），不能跟着放宽，否则麻将实体会被当成「缺 owner 的牌桌实体」并产生误导告警。
- 右键放行口：`WorldTableInteractionListener.shouldCancelProtectedInteract` 的第三个放行口是麻将实体，与既有的椅子家具、动作按钮并列；判定由 `plugin.getMahjongTableManager()`（`integration.mahjong.enabled=false` 时为 null，调用方必须判空）提供，走麻将**实体登记表**而不是读 tag，以避开麻将领域「不得全局反查实体」的限制。不放行会取消麻将入座 Interaction（麻将实体既不是椅子家具也不是动作按钮，前两个放行口都盖不住）。

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

**【当前事实勘误（本轮，2026-09-24）】以下长段落包含大量历史轮次记录，其中多处写着「`folia-supported` 仍为临时 `true`」「守护测试按设计变红」——那些都是**当时**的冒烟临时状态，**现已全部失效**。当前仓库与产物的真实状态是：**

- `src/main/resources/paper-plugin.yml` 的 `folia-supported` **已恢复为 `false`**（临时冒烟状态已回滚，文件内保留了「临时冒烟期间曾改为 true…现已恢复正式安全值」的说明注释）。
- 因此 `FoliaDeclarationMatchesSchedulerTest` **应当全绿**：历史记录里那些「2 项失败为 `FoliaDeclarationMatchesSchedulerTest` 在临时 `folia-supported: true` 下按设计变红」不再适用，若现在看到这两个用例失败，说明 `false` 被误改回去，属真实回归而非「按设计变红」。
- 历史段落里的全量 JUnit 计数（1097 / 1123 / 1127 / 1129 / 1130 / 1131 / 1132 / 1137 / 1141 项等）都是各自轮次的快照，**不是当前数字**；当前数字以「当前技术栈」一节的约定为准（以仓库实际测试目录为准，不要照抄历史数字）。
- 下方括注（保留原文、仅就地勘误）逐条说明哪些句子已过时。**不要**据任一句历史「临时 true」描述推断当前声明状态。

`folia-supported: false`（2026-09-22，当前 1.10.50）。Folia 实际迁移已经开始，但仍处于第二阶段：`MuzScheduler` 已建立并实际承载 global、region、entity/player、async 四条 owner lane，Paper 兼容后端分别映射到 `GlobalRegionScheduler`、`RegionScheduler`、`EntityScheduler` 和 `AsyncScheduler`。已确认收敛到门面的范围包括预览、Debug Web、皮肤渲染和旧 Hotbar 调度；这不是 Folia 兼容声明。另已新增可测试的 `game/PlayerOutputDispatcher` 与 `PlayerTaskRegistry`，门面目前覆盖 `runPlayer`（即时/延迟）、ActionBar、聊天消息、播放/停止声音、标题、BossBar 显示/隐藏、按玩家显示/隐藏实体、打开/关闭库存、按玩家粒子、玩家命令，以及按 UUID 取消玩家任务；当前接入 ActionBar、资源包消息/warmup、桌内音乐、桌内音效、九格道具栏，以及 `GameTable`、`TrickHudService`、`PhysicalTableManager` 的主要玩家输出。`GameTable` 对局核心已使用 UUID/name 快照，`TrickHudService` 的头像与记牌器输入已通过不可变 player-lane 快照采集，实体可见性门面已提供 UUID-first 解析；生产 `PlayerOutputDispatcher` 默认构造已通过 global UUID 查找阶段再进入 player owner lane，玩家输出闭包在执行阶段重新解析当前实例；兼容 `runPlayer` 名称仍保留给旧注入注册表。桌内道具箱的语音气泡入口已按 UUID 投递到 player lane，再在该 lane 查询牌桌；九格道具箱最终打开/关闭库存也通过 `PlayerOutputDispatcher` 的 UUID 窄接口执行，库存创建、事件读取和编辑状态仍保留主线程 GUI 边界。尚未迁移全部玩家输出调用点，`DoudizhuPlugin`、`HotbarHudService`、GUI/监听器和麻将相关路径仍可见直接 `Player` API；不宣称 Folia 支持。牌桌周期任务现在优先按已放置牌桌的锚点通过 `runRegionTimer` 执行；尚未放置的纯逻辑桌才暂时使用 global 兼容后端。`PhysicalTableManager.TableOwner` 已为 MUZ 自有实体写入 owner UUID、桌名、角色和 generation PDC，并在清理时严格匹配；`PlacedTable` 现维护以锚点为中心的固定 3×3 footprint，并建立 chunk-owner 与实体 UUID/owner O(1) 索引，残留清理不再通过 `placedTables` 全局遍历保护邻桌手牌；尚未完成 EntityScheduler 清理和实服验收，以及 remove/reload/shutdown 全量清理 barrier；ChunkLoad/Unload 事件重绑定与 ChunkLoad 单桌 cleanup barrier 已接入，但区块实际卸载重载仍未验证。ChunkLoad 修复现在先捕获并检查 CleanupPlan；tracked entity 缺失（包括 status/playDetail、牌实体和 CE 根实体）视为已不存在，可提交非空或空 cleanup barrier 并在完成后恢复，只有现存实体无法安全定位或 BlockState/anchor/owner/identity 异常时才保守保留 `placed` 与 footprint/entity 索引等待后续重试。本轮仍只是初步异步接入，不表示真实生命周期收口；运行期 owner 路由、缺失实体恢复、失败重试与代次边界仍待验证。本轮新增 `TableWorldLifecycleListener` 作为事件接缝：事件层只读取 world UUID/chunk 坐标并经 global 投递，`PhysicalTableManager` 按 footprint/world 索引切换 unplaced/global，并在 ChunkLoad 通过去重后经 `TableManager.runTableLater` 排入对应 owner lane 的延迟修复/刷新；本轮已接入 ChunkLoad 单桌 cleanup barrier 异步链；区块实际卸载重载、remove/reload/shutdown 全量清理 barrier 和真实实体清理仍未验证。`PlayerTaskRegistry` 在同一注册锁内完成在线 lookup、接收门禁、UUID 取消代次检查与 register；新增 UUID-first `enqueuePlayer` 入口后，调用线程可只登记 UUID，玩家解析延迟到 player owner lane；`cancel(UUID)` 在锁内递增代次并摘除任务，`cancelAll()`/`close()` 不可逆关闭后续接收。旧 Player scheduler 退休后不自动重排；后端丢弃已排任务时输出也随之丢弃。`PhysicalTableManager` 的 owner tick 只读取连接生命周期维护的不可变在线 UUID 快照，不在 owner lane 枚举 `Bukkit.getOnlinePlayers()`；`WorldTableInteractionListener` 的工具预览先在 player lane 读取玩家物品/视线快照，再将牌桌几何操作投递到对应 table owner。

当前异步流程已保留关闭代次/任务代次边界：道具预览在 stop、保存、离线和 close 时使旧 generation 失效；HUD Web 应用在超时或 close 后由 task gate 拦截迟到结果，不能发布旧 Snapshot。1.10.50 进一步补齐牌桌 footprint、实体 UUID 索引与区块生命周期事件接缝、自有实体 owner PDC 和严格清理匹配；已建立 `RegionTaskBarrier` 多 region completion barrier 基础设施，并接入 ChunkLoad 的单桌 cleanup barrier 异步链；但 remove/reload/shutdown 的全量 barrier、`PhysicalTableManager` 清理计划的完整收口和真实 Folia 验证仍未完成。超时或取消只会拦截尚未开始的 region action，已经进入 owner lane 的 action 必须真实返回后才发布聚合结果，禁止把仍在执行的世界写入伪装为已完成。`onDisable()` 先封住新入口并推进关闭代次，再依次停止 Web/业务、提交牌桌与麻将 owner 清理、flush 已登记数据库异步写入，最后关闭调度后端；这只能证明关闭流程有顺序契约，尚不能证明真实 Folia region 清理已完成。（**勘误（本轮）：这里的「提交牌桌与麻将 owner 清理」只是调用顺序上的源码形状，不代表关闭期真的执行了区域清理**——`MuzScheduler` 在 `shuttingDown` 置位后拒绝一切任务注册（关闭期返回已取消句柄、不抛异常），而 `onDisable` 阶段该标志早已置位，因此这些 owner 清理请求**实际不会注册成功**，相关的 reload 关闭分支在 `onDisable` 内退化为空操作。**关闭清理架构未完成、不得宣称已生效**；原文保留不改，仅此括注。**）牌桌中的 bot/超时 epoch 是既有对局状态保护，不等同于 Folia owner 迁移完成，也不能替代区域线程路由。

当前已完成一次性 owner 任务跨 unload/rebind 代次屏障，ChunkLoad 的单桌 cleanup barrier 已接入异步链；缺失 tracked entity 是可恢复场景，现存实体无法安全定位仍保守退出并保留索引；remove/reload/shutdown 的全量 barrier 与真实服务器验证仍未完成。当前阻塞仍是牌桌实体按 region 刷新、GUI/监听器及麻将相关玩家/实体输出、实体生命周期、跨区域状态消息、区块卸载/重载、关闭清理，以及麻将领域的 owner 路由；本阶段已将 `GameTable` 的开局、机器人、超时、AI 与延迟回调统一经 `TableManager` 的 table-owner 门面，已放置桌按锚点走 region，未放置桌才走 global，并完成相关定向回归。`GameTable`、`TrickHudService`、`PhysicalTableManager` 的主要玩家输出，以及世界桌/手牌 GUI 监听器的玩家反馈已按 UUID 投递到 `PlayerOutputDispatcher` 的 player lane；麻将桌管理器的广播也已收口到同一门面，session 已增加 UUID 快照与代次保护。测试夹具允许初始在线检查与 owner lane 内在线检查同时存在，但实际 Bukkit 输出调用必须在 owner lane。paper-26.1.2 与 paper-26.2 的本轮定向回归均为 142/142 通过，三目标强制编译通过；paper-26.1.2 的 1.10.49 JAR、资源包和 CraftEngine bundle 已重新构建并通过 SnakeYAML 重定位校验，但未复制或部署到现场。已核对用户提供的 `E:\我的世界插件\folia测试端` 中存在 `folia-26.1.2-8.jar`、独立世界及 plugins 目录；现场 plugins 没有匹配的 MUZ 1.10.49 JAR，且世界非空、无备份，尚未启动该测试端或取得真实 Folia 服务端验收记录（该现场此后已按用户选择部署 1.10.50 的 paper-26.1.2 JAR 且不备份世界；2026-09-22 真实启动时 MUZ 被 `not marked as supporting Folia` 拒载，插件未载入，因此 owner 路由仍无运行期验收）。已完成的 Paper/Leaf 编译、单测和调度契约只能证明兼容后端与部分 owner 边界，不能证明 Folia 运行兼容；真实 Folia 运行验收仍未完成。`FoliaDeclarationMatchesSchedulerTest` 继续守护声明值和调度接缝；在真实 Folia 服务端矩阵、关闭代次和上述 owner 路由完成前，必须保持 `folia-supported: false`，不得直接安装到 Folia。本轮完成一次性 owner 任务跨 unload/rebind 的代次失效与锁外取消，并补充 owner 锚点副本及生命周期契约测试；不改 Folia 支持状态。本轮继续收口麻将桌实体 owner：`MahjongTableManager.rerender` 与 `removeVisuals` 不再经由通用桌 region 门面，而是各自直接向桌锚点 `runRegion` 提交并带各自的闸门——重绘用 `table.generation() != expectedGeneration || tables.get(table.id()) != table` 淘汰迟到回调，删除是终结操作、**不带**注册/代次闸门。此前两者都走「桌仍在注册表且代次不变」的闸门，而 `removeTable` 与 `shutdown` 都会先把桌移出注册表，于是清理任务被自己的屏障挡掉、Display/Interaction 实体永久残留；这是本轮修掉的真实缺陷，不是测试适配。`MahjongTableManager.shutdown()` 现在按 `plugin.getServer().isStopping()` 分支：关服只推进代次并 `clearVisuals()` 清追踪、不在非法 owner 上操作实体（与 `PhysicalTableManager.shutdown` 的既有分支一致）；reload 分支把每张桌登记为 `RegionTaskBarrier.Request` 并在桌 region 内联执行 `cleanupOnTableRegion`，用完成屏障观察聚合结果、只记录失败日志，**不阻塞调用线程**（`onDisable` 在主线程上，阻塞等待会把 Paper 的 region 任务本身饿死），并新增 `shutdownCompletion()` 作为可观察句柄。麻将保护链已闭环：`muz_mahjong_protected` tag 并入 `TableEntityGeometry.PROTECTED_TAGS`，`PhysicalTableManager.isProtectedEntity` 因此也认麻将实体（破坏保护生效，但残留清理仍只认牌桌 tag），同时给 `WorldTableInteractionListener.shouldCancelProtectedInteract` 加了麻将放行口（判定取自 `plugin.getMahjongTableManager()`，麻将未启用时为 null），麻将入座 Interaction 不再被取消。`ChairInteractGuardTest` 守三个放行口各自的失败条件；共享链的 tag 判定由 `SharedProtectionTagTest` 做行为级验证（牌桌与麻将 tag 都算受保护，其它 tag 和 null 都不算）。麻将 runtime/session/entity 的 owner 路由已按上述收口：runtime 幂等关闭并委托管理器、`send` 走 `sendToPlayer`，session 只暴露 UUID 快照与 `AtomicLong` 代次且不持有 Bukkit 实体/玩家/调度器；**持久化没有可迁的 owner 路由**——麻将根本没有持久化实现（`persistMahjongTable` 是无 I/O 的日志桩），唯一 DB 入口 `deletePersistedTable("MAHJONG", …)` 已经 `DatabaseManager.runWrite` 异步执行，因此实现麻将持久化本身属新功能、不在本次迁移范围。`runOnTableRegion` 目前无生产调用方（仅被两份契约测试钉住）；麻将命令桥的 `sender.sendMessage` 仍直发，与整个 `/muz` 命令层一致，属既有命令层技术债而非麻将领域问题。remove/reload/shutdown 全量多 region 实体清理 completion barrier 已在斗地主侧补齐 reload 关闭与拆桌两条路径（见下一段），麻将侧沿用自身 completion barrier；连同上述测试契约不能替代的真实 Folia 服务端验证仍未完成。本轮三目标（`paper-1.21.11` / `paper-26.1.2` / `paper-26.2`）均强制执行 `compileJava compileTestJava --rerun-tasks`（各 6 tasks executed）成功；JUnit 只在 `paper-26.2` 实跑：麻将 owner/region/shutdown 契约 17/17、调度与牌桌生命周期等定向回归 94/94（14 容器）通过，无失败、跳过或中止，另两个目标只做了编译验证。仓库没有可用的 Bukkit 夹具来构造 `MahjongTableManager`，因此上述删除/关闭清理修复只有源码契约与编译证据，**没有行为级执行证据**，也不代表真实 Folia region 清理已完成。三目标发布构建（`clean shadowJar zipResourcePack zipCraftEngineBundle verifyRelocatedSnakeYaml`）各 10 tasks 全部实际执行成功，9 个 1.10.50 归档已通过 `build/muz-release-audit.py` 验收（麻将保护链改动后已重建，三份 JAR 的 SHA-256 见 `build/muz-release-audit.json`）：0 failures、版本 1.10.50、作者仅 `linmumua`、SnakeYAML 0 unrelocated / 270 relocated、各目标计数一致（JAR 1148 / resourcepack 450 / CE 467）、无原版 hotbar 覆盖；1.10.50 仍未发布（无 tag，`C:\PluginLibs` 与部署目录均无该版本），因此同版本重建不构成替换已发布构建。全量 JUnit 已在 `paper-26.2` 实跑 **1086/1086、162 个容器**通过。修这批失败时发现并修掉一个本批次重构引入的真实回归：`PlayerOutputDispatcher` 生产构造原先直接把 `Bukkit::getPlayer` 当解析器，违反 `PlayerTaskRegistry`「生产解析器只返回在线玩家」的约定，使 `isPlayerPresent`/`canRevealHand` 把离线真人判为在线、离线真人拿到明牌资格；现改为带 `isOnline()` 门禁的解析器。`PhysicalTableManager` 构造器也不再裸调 `Bukkit.getOnlinePlayers()`，改为注入 `PlayerPresenceRegistry` 并以空安全方式 seed 在线 UUID 快照；`GameTableOpeningFlowTest` 夹具据此补了最小 `PluginMeta` 桩（`NamespacedKey(plugin, …)` 需要 namespace）与只登记不执行的 `SchedulerBackend` 替身。真实 Folia 实服矩阵仍未验收。

注意 `TrickHudService` 的 `snapshot` 字段仍保留 `volatile` 与整份替换语义，那是为未来的区域线程读做的准备，不要因为现在不支持 Folia 就摘掉。

本轮补齐斗地主侧 close/remove 的 owner 路由：`PhysicalTableManager` 的 reload 关闭分支不再在调用线程直接 `cleanupPlacedTable`，而是逐桌登记锚点 region 请求并用 `RegionTaskBarrier` 聚合（`submitShutdownCleanupBarrier` + `shutdownCompletion()`，只观察不阻塞，与 `MahjongTableManager.shutdownCompletion()` 同口径）（**勘误（2026-09-23）：`MuzScheduler` 在 `shuttingDown` 为真时会拒绝一切任务注册（关闭期返回已取消句柄、不抛异常），而 `onDisable` 阶段该标志早已置位。因此这条 reload 分支**实际不会**注册任何 region 清理请求，「只观察不阻塞」在 `onDisable` 内退化为空操作。当时那句「逐桌登记锚点 region 请求」只是源码形状，不代表运行期真的执行了区域清理；不得据此宣称关闭清理已生效。关闭清理架构不在本轮范围，未实现。**）；`removeTable`/`forceRemoveTable` 的实体清理与锚点残留清扫也改经 `cleanupPlacedTableOnOwnerRegion` 投递到桌锚点 region（失败经 `exceptionally` 记日志，不静默吞）。关服分支保持只清运行态、不触碰实体，并由契约测试钉住「关服分支必须早于 region 请求登记返回」。`PhysicalTableChunkLifecycleContractTest` 的 shutdown 断言随之从「包含 `cleanupPlacedTable(placed);`」改为钉住「清理必须作为 `RegionTaskBarrier.Request` 提交 + 存在完成屏障」（机制变更，非弱化），并新增拆桌 region 路由契约。证据等级如实：这两条路径目前是源码契约级证据（沿用该文件既有约定），**没有**行为级执行证据，也不代表真实 Folia region 清理已完成；`rebuildAllTables`/`rebuildSingleTable`/`shiftAllAnchors` 三条重建路径本轮未动（**勘误（2026-09-23）：该句已过时**——三条重建路径随后已迁移为异步 lane 流水线，并在「重建路径状态保护与闸门前移」一轮补上旧状态保留、闸门前移与收口拒绝清理。**）。三目标发布构建各 10 tasks 实际执行成功，9 个 1.10.50 归档验收 0 failures（SHA-256 见 `build/muz-release-audit.json`），版本仍未发布。实服验收进展：已按用户选择把 1.10.50 的 `paper-26.1.2` JAR 部署到 `E:\我的世界插件\folia测试端\plugins`（**不备份世界**，用户明确选择），副本 SHA-256 与构建产物一致（`69c540e0…`），该目录此前无 MUZ JAR，也未覆盖任何既有产物。2026-09-22 23:11 该测试端已在真实 Folia 上启动过一次（Folia `26.1.2-8-ver/26.1.x@62dc0f2`，Java 26）：MUZ 的 `paper-plugin.yml` 被正常解析（日志读出 `MUZ v1.10.50`，说明描述文件与 JAR 结构在真实 Folia 上可用），但随即被拒载——`Could not load plugin 'MUZ v1.10.50' as it is not marked as supporting Folia!`，**声明拒载在真实 Folia 上得到确认**；且拒载**不致命**，同一条日志继续 `Initialized 3 plugins`（BetterModel / CraftEngine / BobingFolia 正常加载），服务端运行至 23:16:01 并干净停服（`RegionShutdownThread` 逐世界保存成功），**本轮未复现此前记录的世界目录锁冲突**。因此这次验收的结论是反向的、但必须如实记录：`folia-supported: false` **确实拦住了加载**，而**插件从未载入**意味着 `MuzScheduler` 的四条 owner lane、牌桌/麻将 region 路由、`RegionTaskBarrier` 清理屏障在这次运行中**一行都没有执行**——斗地主与麻将侧的所有 Folia 迁移仍**只有源码契约与编译证据，没有任何运行期证据**，本轮不构成 owner 路由的真实验收。要取得运行期证据，必须先让构建产物声明支持 Folia（见下条），而**正式产物必须继续为 false**。【临时冒烟状态，待回滚】按用户选择，已把源码 [src/main/resources/paper-plugin.yml](src/main/resources/paper-plugin.yml) 的 `folia-supported` 临时改为 `true`（带显式「临时冒烟状态」标记注释），并构建、部署了冒烟 JAR（`build/paper-26.1.2/libs/MUZ-1.10.50-paper-26.1.2.jar`，SHA-256 `bf019d4ec4f3b20d6f94bf7a6c011dee1b3910bf76edad5cc748a7aaebf9fcbe`，现场副本一致），目的是让 MUZ 能在真实 Folia 上加载一次以采集区域线程证据；在此状态下 `FoliaDeclarationMatchesSchedulerTest` **按设计变红**（实跑 2 failed / 2 succeeded，命中 `declarationRemainsFalseUntilRealFoliaAcceptance` 与 `decisionCommentIsPreserved`；**断言本身未被改动、未被弱化**，这正是守护生效的证据）。验证结束后必须把该键改回 `false` 并重建，此前正确的 `false` 产物已备份到仓库外临时目录（SHA-256 `69c540e0…`）。真实 Folia 实服矩阵仍 blocked。本轮取得**首批真实 Folia 运行期证据**（2026-09-22 23:29~23:31，Folia `26.1.2-8-ver/26.1.x@62dc0f2`，Java 26，日志 `E:\我的世界插件\folia测试端\logs\latest.log` 共 394 行）：冒烟 JAR **成功加载**（`Loading server plugin MUZ v1.10.50` → `Enabling MUZ v1.10.50` → 100% 就绪），无致命错误、无 `not marked as supporting Folia`，证明临时 `true` 的加载路径成立。日志共 4 类异常，其中**只有 2 类是本插件自身的真实 Folia 违规**；另 2 类经核实不属于本插件（CraftEngine 在 Java 26 下的 ByteBuddy `ClassInjector$UsingLookup`/Unsafe 受限；`/pl` 的 `CommandSourceStack.getLevel()` NPE 属 Folia 原生命令问题），**不得修这两类**。复核日志时另发现**同一 Bug A 类的第二处实例**（此前摘要漏记）：`TableGadgetEffectService.tick`（L110 `Bukkit.getCurrentTick()`）← `TableGadgetService.tick`（L220）← `MuzScheduler.lambda$runGlobalTimer$1`，即道具效果与目标高亮的 global 周期任务（`TableGadgetService.start()` 用 `runTimer(1L, 1L, this::tick)` 注册）同样在 global lane 上取时钟并操作实体；又因 `MuzScheduler.schedule` 在异常时执行 `managed.fail(repeating=true)` 会**取消该周期任务**，所以该任务在真实 Folia 上首帧抛异常后即被取消——道具效果推进与目标高亮实际上一直是**静默失效**状态（这解释了日志里同一异常只有 2 条）。该处已在本轮**一并修复**（用户明确选择「一并修好再一次性重启」）：`TableGadgetService.tick()` 改为纯派发——效果推进经 `TableManager.runTableNow(table, …)` 投到桌锚点 region，并捕获「桌在本轮扫描中被注销」的 `IllegalArgumentException`（否则异常会再次把 global 周期任务打挂）；目标高亮改按 actor 经 `PlayerOutputDispatcher.runPlayer` 投到 player lane（沿用 `TableGadgetBarHudService` 的合规模板），原一次性 `updateTargets()` 拆为 player lane 内的 `updateActorTarget(Player, GameTable)`；`TableGadgetEffectService.tick()` 改名 `tickTable(GameTable)`，并在读时钟/碰实体之前先用「本桌没有效果」早退（未放置桌的 owner lane 会回退 global，必须让该路径空转），`lastPlayTicks` 的 400 tick 清理下沉到交互路径（那里本就有合法时钟）；跨 lane 状态（`TableGadgetService` 的 7 个表、`TableGadgetEffectService` 的效果与冷却表）全部改并发容器，内部引用集改 `ConcurrentHashMap.newKeySet()`。**如实说明未验证部分**：发光引用清扫在 `src/test` 中**仍无行为测试**（本轮也没能补上行为级证据），改为 player lane 逐 actor 清扫后「永久发光泄漏」是否真被避免只有实服可验；跨桌失效 actor 的清理语义保持不变（`eligible` 仍由 global lane 汇总），但完成时机变成各 actor lane 的异步执行。`FoliaRuntimeLaneContractTest` 扩展为 5 项，新增钉住「道具扫描只派发、不得取时钟/改发光」「道具服务不得再用 `new HashMap<>()`」「`tickTable` 必须先判空再取时钟」；`paper-26.2` 全量 JUnit 实跑 **1091 项 / 163 容器**，仅 `FoliaDeclarationMatchesSchedulerTest` 2 项按设计变红（临时 `true`），其余 1089 项全绿。新冒烟 JAR SHA-256 `ab2ba8b2ac6839b5b9cb093c8f314d97499e1ff7eed18fcfea67af6e89536298` 已覆盖部署（上一版 `a833aacc…` 保留为 `.jar.bak`）。**【运行期验证结果，2026-09-23 00:20 会话】** 用 `ab2ba8b2` 在真实 Folia 上启动（`Loading server plugin MUZ v1.10.50` → `Enabling` → 100% 就绪，无拒载）：三处修复**全部通过运行期验证**——全日志 `No currently ticking region` 0 次、`teleportAsync while in region threading` 0 次、涉及三个服务的异常 0 次，说明道具/目标高亮的 global 周期任务不再被取消。**同一批日志暴露两处新的真实问题**：(1) `PhysicalTableManager.ensureChunkReady` 用 `anchor.getChunk()` / `chunk.load()` 做阻塞区块加载，在 Folia 上被拒（`Thread failed main thread check: Async chunk retrieval`），主线程与 region 线程（`region={null}`）两种上下文都失败，导致存档牌桌恢复失败并长期停在「待重试 1 张」；(2) 上一会话关闭时 `onDisable` 两个步骤抛 `IllegalPluginAccessException: Plugin attempted to register task while disabled`（链路 `TableGadgetBarHudService.shutdown` → `clearTable` → `sendActionBar` → `PlayerOutputDispatcher.runPlayer` → `runGlobal`），又因异常从 `shutdown()` 抛出并被 `runShutdownStep` 捕获，同一关闭步骤中其后的 `TableGadgetGuiService` / `TableGadgetService` / `ActionBarOverlayService` 以及「关闭斗地主牌桌」步骤全部被跳过——**Folia/Paper 在插件已禁用后拒绝一切任务注册**，所以关闭路径不能依赖「提交任务」，必须改为内联执行或不再注册（这条约束也意味着 AGENTS 前面描述的「提交牌桌与麻将 owner 清理屏障」在 onDisable 内实际无法注册）。另注：测试端 CraftEngine 版本与编译依赖不匹配（`NoSuchMethodError: CraftEngine.reloadPlugin(Executor, Executor, boolean)`），HUD 资源恢复在该端无法应用，属环境/CE 版本问题，不是 lane 违规。Bug B（同步传送）：`/muz bot add` 在 region 线程抛 `UnsupportedOperationException: Must use teleportAsync while in region threading`，链路 `PhysicalTableManager.teleportIfMoved` ← `syncActionWidgets` ← `refreshActionButtons` ← `refresh` ← `GameTable.refreshPhysicalTable`；已把 `PhysicalTableManager` 与 `TableGadgetEffectService`（飞行物推进、水幕本体与头顶水桶）共 4 处同步 `teleport` 改为 `teleportAsync`，全仓 `grep "\.teleport("` 已无残留（Paper 与 Folia 都提供 `teleportAsync`，项目内不存在 `isFolia` 检测，无需平台分支）。Bug A（global lane 取时钟并操作实体）：语音面板 tick 由 global lane 驱动，该 lane 既无 ticking region 也无实体所有权，`Bukkit.getCurrentTick()` 直接抛 `IllegalStateException: No currently ticking region`，tick 内内联的 raycast/`setRotation`/`remove` 同样非法；已按本项目 `TableGadgetBarHudService` 的既有合规模式（global 只扫描派发、真实玩家级工作经 `output.runPlayer` 投递 player lane）重写——`tick()` 只做纯派发（不再取时钟、不碰实体，含离线兜底分支），新增 `tickOwner(...)` 在 player lane 内取时钟并执行原有 hover/命中/失效清理，`tick` 字段下沉为局部变量消除跨 lane 竞争，`owners`/`ownerEpochs` 改为 `ConcurrentHashMap`。另补修一处**既有泄漏**：`GameTable` 的强制关桌与回 LOBBY 两条路径此前从不清理语音面板（兄弟服务都清），现与 `getTableGadgetBarHudService()`/`tableGadgets()` 同点位调用 `getTableSpeechPanelService().clearTable(this)`。新增源码契约测试 `FoliaRuntimeLaneContractTest`（4 项）钉住「主源码不得出现同步 `.teleport(`」「global 扫描只派发、取时钟必须在 `tickOwner` 内」「面板状态必须是并发容器」「关桌与回 LOBBY 都必须清理面板」；实跑 4/4 通过，传送与牌桌相关定向回归 52/52 通过（`TableSpeechPanelServiceSettingsTest` 只测 `Settings`/`phaseAllowed`、不构造服务实例，无需适配）。证据等级如实：本轮修复**只有编译与源码契约级证据**，且离线 owner 的残留面板实际不会被 tick 清理（离线分支用 `continue` 跳过，属已记录泄漏，依赖 `clearTable` 收口）。**尚有一条待验证的假设**：本轮把面板的实体操作投递到**玩家** lane，而面板实体位于**桌子**位置——观众就座时两者同 region 故合法（既有 `open()` 一直如此且本轮冒烟未报错，但也可能只是没人右键第九格），一旦观众走远导致两者不同 region，实体操作可能再次非法；若下一轮冒烟出现该类异常，正确方向是把实体删除/传送投到桌锚点 region（`TableManager.runTableNow`）、只把 `showEntity`/`hideEntity` 留在玩家 lane。reload/shutdown 分支的实体删除 lane 未动，真实运行验证必须靠下一轮冒烟。新冒烟 JAR SHA-256 `a833aacc796d557953128683c5352a163764fa0a5666bffd52b08e759953f5e9` 已覆盖部署到测试端 `plugins`（上一版 `bf019d4e…` 保留为 `.jar.bak` 以便回滚）；源码 `folia-supported` 仍为临时 `true`、守护测试仍按设计变红。

本轮在上一轮真实 Folia 冒烟日志与两位独立审查的基础上修掉三处问题，均属源码与编译级修复，**尚无运行期验证**（本轮未重启测试端、未取新日志），因此**仍不构成 Folia 支持声明**。修法如下：(1) `PhysicalTableManager.ensureChunkReady` 的阻塞区块获取（真实 Folia 日志 `Thread failed main thread check: Async chunk retrieval`，主线程与 `Folia Region Scheduler Thread`（`region={null}`）两种上下文都抛，导致存档牌桌恢复失败并每约 5 秒重试刷屏）——`restoreTable` 签名改为返回 `CompletionStage<GameTable>`，外层只做归一化/早退/CE 可用性检查，再 `ensureChunkLoadedAsync(anchor)`（`World#getChunkAtAsync(blockX>>4, blockZ>>4)`）→ 通过新增的 `runRegionStageValue` 投递到锚点 region 执行世界体（新方法 `restoreTableOnLoadedChunk`，并在 region 内再查一次 `placedTables.containsKey` 幂等早退）；`ensureChunkReady` 与 `ensureAnchorChunkLoaded` 改为只读 `world.isChunkLoaded(...)` 检查 + 告警（不再拉区块）；`DoudizhuPlugin` 的恢复流程改为**异步派发 + in-flight 去重 + 回调结算**（新增并发 in-flight 集合与计数，`pendingPersistedTables` 改为锁内不可变替换），100 tick 重试定时器与日志节流语义保持不变。(2) `onDisable` 期间注册任务抛 `IllegalPluginAccessException: Plugin attempted to register task while disabled`（使「关闭桌内业务入口」与「关闭斗地主牌桌」两步失败、其后步骤被整段跳过）——在调度门面 `MuzScheduler` 注入 `BooleanSupplier shuttingDown`（默认 `() -> false`，保证既有测试构造行为不变），`schedule(...)` 复用既有 reject 分支，关闭期**不抛异常**、返回已取消的 `ManagedTaskHandle`，并**每代次只记一次 WARNING**；`DoudizhuPlugin` 的 `scheduler()` 工厂与 onEnable 内实际构造点**两处**都传 `this::isShuttingDown`（用自制标志而非 `isEnabled()`，避免启动期误伤）。同批把 `DatabaseManager.runWrite` 的**关服同步回退按 HEAD 原意恢复**（用户明确裁定：宁可短暂阻塞调用线程也要保住关服那一刻提交的写库），并保留新增的「句柄 null/已取消」兜底分支作为「插件仍启用但调度器已关闭」的残余窗口；`DatabaseWriteIsAsyncTest` 恢复为 HEAD 原断言（该文件 `git diff` 为空）。(3) `PhysicalTableManager.placementBlockedBlocks` 仍用 `Location#getChunk()` 同步拉区块（放桌预览路径，经 `WorldTableInteractionListener` 跑在 player lane；由审查发现，上一轮修复未覆盖）——改为 `world.isChunkLoaded(blockX>>4, blockZ>>4)`，语义不变；`FoliaRuntimeLaneContractTest` 新增契约「`PhysicalTableManager.java` 不得出现 `getChunk()`」，并注明 `TableWorldLifecycleListener` 的 `event.getChunk()` 属事件自带的合法例外。本轮验证（全部真实执行）：三目标 `compileJava compileTestJava --rerun-tasks` 均为 `6 actionable tasks: 6 executed`、BUILD SUCCESSFUL；`paper-26.2` 全量 JUnit 实跑 **1097 项 / 164 容器 / 1095 通过 / 2 失败**，两项失败均为 `FoliaDeclarationMatchesSchedulerTest`（`declarationRemainsFalseUntilRealFoliaAcceptance`、`decisionCommentIsPreserved`）在临时 `folia-supported: true` 下**按设计变红**，断言未被改动；另修掉本轮新增测试夹具的一处 GC flaky（`TablePeriodicTaskLifecycleFixtureTest`：Paper 26.x 的 `Location` 用弱引用持有 world，`Proxy` 造的 world 被回收后抛 `World unloaded`，改为夹具持强引用，重复 3 次均通过）。本轮冒烟 JAR `build/paper-26.1.2/libs/MUZ-1.10.50-paper-26.1.2.jar` SHA-256 `571880329dc3940f3db457418c7f38aa4d7d6a439e679fb4f9b9552f5cf7a252`（JAR 内 `paper-plugin.yml` 仍为 `folia-supported: true`、`version: 1.10.50`）。**未完成/未验证**：上述三处修复**尚无运行期验证**（本轮未重启测试端、未取新日志），`getChunkAtAsync` 的回调线程、region 投递在真实 Folia 上的合法性、恢复成功后的端到端表现都只能靠下一轮实服冒烟确认；**重建路径仍未迁移（**勘误（2026-09-23）：该句已过时**——`rebuildAllTables` / `rebuildSingleTable` / `shiftAllAnchors` 已在本轮之后的「重建路径异步化」与「重建路径状态保护与闸门前移」两轮完成迁移，仍在 global lane 做世界/实体操作的说法不成立。**）**——`rebuildAllTables` / `rebuildSingleTable` / `shiftAllAnchors` 仍在 global lane 做世界/实体操作，且区块预热方法改为只读检查后，这些路径失去「执行前锚点区块已加载」的保证（`spawnTable` 开头那次 `ensureChunkReady` 亦然）。子代理评估后**主动停手**，理由是：`rebuildSingleTable` 被 `syncViewerOnOwner`（桌 owner lane）同步依赖并要求随后 `placedTable(name)` 非 null；`rebuildAllTables` 之后紧接 `repairIncompleteTables`，改异步会让 repair 看到空表；`PhysicalTableChunkLifecycleContractTest` 锁定了三个方法体内的语句顺序；`cleanupPlacedTable` 把非并发 Map 记账与实体操作耦合在一起，逐桌投 region 会并发写非并发容器。建议方案：拆「调用 lane 记账 + `ensureChunkLoadedAsync` → 锚点 region 世界体」两段，并让相关方法暴露完成句柄、同步调整 `DoudizhuPlugin` 的 warmup/reload/shift 调用点。另：测试端 CraftEngine 与编译依赖版本不匹配（`NoSuchMethodError: CraftEngine.reloadPlugin(Executor, Executor, boolean)`），HUD 资源恢复在该端无法应用——属环境/CE 版本问题，不是 lane 违规。本轮未部署、未重启、未做客户端/资源包验收。源码 `folia-supported` 仍为临时 `true`、守护测试仍按设计变红；正式产物必须继续为 `false`。

另外记录一批 2026-09-23 的新真实运行期证据（**Leaf 核心**，仍不构成 Folia 支持声明）：测试端 `E:\我的世界插件\folia测试端` 里**同时存在** `folia-26.1.2-8.jar` 与 `leaf-26.1.2-3.jar`；此前记录的 Folia 冒烟（00:45 会话）用的是 Folia `26.1.2-8-ver/26.1.x@62dc0f2`，而 **02:07 会话实际启动的是 Leaf**：`Loading Leaf 26.1.2-3-ver/26.1.2@3c591d7`（API `26.1.2.build.3-alpha`）。该 Leaf 会话当时部署的仍是本轮修复**之前**的冒烟 JAR（`ab2ba8b2…`），实测结果：MUZ 正常加载 → `Enabling MUZ v1.10.50` → 100% 就绪（Leaf **未拒载**；`folia-supported` 只是描述文件元数据，只有 Folia 的插件管理器会读它）；**存档牌桌恢复成功**——`已恢复牌桌: 斗地主 1 张 | 牌桌恢复完成，本次恢复 1 张`，说明 Leaf 上主线程允许同步拉区块，`ensureChunkReady` 的阻塞获取在 Leaf 不构成违规；视觉预热重建正常执行到 pass=1 / pass=2，无异常；全日志 **0 次** `Async chunk retrieval`、`No currently ticking region`、`teleportAsync while in region threading`、`Thread failed main thread check` —— 这三类违规是 Folia 区域线程特有的，Leaf 上不出现；且 Leaf 栈中出现 `FoliaGlobalRegionScheduler` 与 `leaf-26.1.2.jar`，说明 Leaf 提供 Folia 兼容调度器层。**同一次 Leaf 运行的关闭阶段（02:09:24）复现了完全相同的两处失败**：`[MUZ] MUZ 关闭阶段失败: 关闭桌内业务入口` 与 `…: 关闭斗地主牌桌`，均为 `org.bukkit.plugin.IllegalPluginAccessException: Plugin attempted to register task while disabled`（栈为 `FoliaGlobalRegionScheduler.runDelayed`），→ 该关闭链缺陷**不是 Folia 特有**，Leaf 同样受影响；本轮 `MuzScheduler` 的 `shuttingDown` 闸门对两个核心都是修复。CraftEngine HUD 重载在 Leaf 上同样报 `NoSuchMethodError: CraftEngine.reloadPlugin(Executor, Executor, boolean)`，属环境/CE 版本不匹配、与核心类型无关。部署状态：本轮新冒烟 JAR（SHA-256 `571880329dc3940f3db457418c7f38aa4d7d6a439e679fb4f9b9552f5cf7a252`）已部署到测试端 `plugins/MUZ-1.10.50-paper-26.1.2.jar`，副本哈希与构建产物一致，上一版 `ab2ba8b2ac6839b5b9cb093c8f314d97499e1ff7eed18fcfea67af6e89536298` 保留为 `.jar.bak`。**必须如实写清的未验证部分**：本轮新代码（`57188032…`）**尚未在任何核心上运行过**；异步恢复带来的时序变化（存档牌桌恢复由同步改为异步区块加载 + 锚点 region 投递，`sqlTablesLoaded` 可能延后一个 100 tick pass 才置位）在 Leaf 与 Folia 上都仍需实服确认；`rebuildAllTables` / `rebuildSingleTable` / `shiftAllAnchors` 等重建路径仍未迁移（**勘误（2026-09-23）：该句已过时**——`rebuildAllTables` / `rebuildSingleTable` / `shiftAllAnchors` 已在本轮之后的「重建路径异步化」与「重建路径状态保护与闸门前移」两轮完成迁移，仍在 global lane 做世界/实体操作的说法不成立。**），仍在 global lane 做世界/实体操作。任何核心的实服验收都不构成 Folia 支持声明，正式产物必须继续为 `false`。

【纠错：区域化核心判定（2026-09-23）】此前 `PhysicalTableManager` 用「`RegionizedServer` **或** `TickRegions` 类存在」判定区域化核心，并在注释里断言「这两个类都不在 Paper/Leaf 的核心 JAR 里」。**该断言是错的**，而且判据本身会误判：现场 `E:\我的世界插件\folia测试端\versions\26.1.2\leaf-26.1.2.jar` 只打了一个 484 字节的 `io/papermc/paper/threadedregions/TickRegions.class` 兼容桩、**没有** `RegionizedServer`；于是 Leaf 被判成区域化核心，`restoreTable` 的同步恢复/预热分支根本不执行、`ensureAnchorChunkLoaded` 退化为只告警。反编译两份内核（`folia-26.1.2.jar` / `leaf-26.1.2.jar`）拿到可用判据：Folia 的 `isBrandCompatible(k)` 只做 `k.equals(brandId)`、其 brandId 默认 `papermc:folia`；Leaf 的 `isBrandCompatible` 认 leaf/paper/gale/pufferfish/purpur、**不含 folia**——所以 `isBrandCompatible(Key.key("papermc","folia"))` 是精确的 Folia 判别器。判定现收口到新类 `linmumua.doudizhu.world.RegionizedCoreDetector`：优先官方 `ServerBuildInfo.buildInfo().isBrandCompatible(...)`（不裸反射；三目标 API 均含该接口），品牌不可用时才回退到**只认 `RegionizedServer`** 的类存在性；`ServerBuildInfo` provider 缺失（单测类路径）抛 `NoSuchElementException` 属预期降级点、静默回退，而类加载 `LinkageError` **不得**静默当作非区域化（否则未知核心会走同步世界写入），保守判为区域化并告警。同时把预热逻辑抽取为包内可见静态核心 `ensureChunkReadyCore` / `ensureAnchorChunkLoadedCore`（`if (regionized)` 分支形状与 Folia 安全强度不变，实例入口把 `REGIONIZED` 传进去），使「区域化核心严禁同步拉区块」可用 fake World 做**真实行为测试**（`RegionizedCoreDetectionBehaviorTest` / `AnchorChunkPreloadCoreBehaviorTest`，含旧 TickRegions 判据的 mutation 反例）。本轮只改核心判定与预热核，**不改** Folia 支持状态（`folia-supported` 仍为临时 `true`、`FoliaDeclarationMatchesSchedulerTest` 仍按设计变红，两个声明测试不得改），也**未**改动 scheduler/game/存储/部署现场。（**勘误（本轮）：该句描述的「临时 `true`」状态已失效**——冒烟结束后 `folia-supported` 已恢复为 `false`，守护测试现应全绿；「两个声明测试不得改」的约束依旧成立。原文保留不改，仅此括注。**）

【不得据此断言开局根因】截图现象是桌椅可见、卡在「正在发牌」。把「锚点区块未加载」的 WARN 直接写成该现象的**已证根因**是不成立的：本轮只证明核心判定被现场 Leaf 误判、修复了误判本身，并没有取得开局链（发牌时间线/协调器）的运行期证据。开局链另有只读代理在独立调查，结论出来前不要把它与本次判定修复合并成一句话结论。

【本轮：重建路径异步化（2026-09-23）】`PhysicalTableManager.rebuildAllTables` / `rebuildSingleTable` / `shiftAllAnchors`（含 `spawnTable` 预热）已从「调用线程同步拉区块 + 动实体」改为固定顺序的 lane 流水线：调用 lane 冻结 `RebuildRequest` 快照并摘除索引 → `ensureFootprintLoadedAsync` 异步加载**旧/新锚点各自的 3×3 footprint**（新增静态核 `ensureFootprintLoadedAsyncCore`；单锚点恢复链 `ensureChunkLoadedAsync` **保持单区块、没有被改成 3×3**）→ 旧锚点 region 清旧实体 → 新锚点 region 扫残留并生成新桌（`rebuildTableOnOwnerRegion`）→ **global 收口**写入 `placedTables` 与 footprint/实体索引并重绑 owner 周期任务（`commitRebuiltTable`，带 `isShuttingDown` + 桌实例身份双重闸门，迟到结果不得复活已被移除的桌）→ 锚点 region 刷新世界体与一次性收尾。`placedTables` / footprint 索引 / 实体索引这三份非并发 Map **只由调用 lane 与 global 收口写**（**勘误（2026-09-23，详见下文「重建路径状态保护与闸门前移」）：该表述不准确——重建流水线**自身**的收口写入确实只发生在调用 lane 与 global，但既有的 owner tick / 交互路径（`refresh`、`tickTable`、`syncViewerOnOwner` 等）仍会在各自 owner lane 上读写同一批非并发 Map，尚未统一并发边界，属待偿技术债；本轮只把「重建窗口」用世界体门禁从这些路径里隔出去，不做全局 Map 迁移。**），region 回调只读冻结快照并把新 `PlacedTable` 当返回值交回；整批用 `thenCompose` **顺序化**（`runRebuildBatch`），避免多 region 回调并发读写同一批 LinkedHashMap；单桌失败只记日志并继续后面的桌，不把失败桌伪装成已重建。`repairIncompleteTables` 改为先切回 global 再扫描、并返回完成 stage；`DoudizhuPlugin` 的 warmup 改为 `rebuildAllTables().thenCompose(repairIncompleteTables)` 串接（原本是无条件紧邻调用，异步化后会在旧实体已被清掉、新桌尚未生成时扫出一整批"不完整桌"），reload / shift / post-restore 三处调用点补 `exceptionally` 失败日志；`syncViewerOnOwner` 拆为「投递重建」与「重建完成后经 `runTableNow` 回本桌 owner lane 执行 `continueViewerSyncOnOwner`」，不再是重建后立即读取 `placedTable`。`ChairHitboxRestoreTimingTest` 与 `PhysicalTableChunkLifecycleContractTest` 中**因机制变化而过时的同步顺序断言**已升级（属于断言编码旧机制、**不是弱化**）：锚点 3×3 同步预热早于清理的顺序断言，改为「footprint 异步加载必须早于旧锚点清旧 / 新锚点生成 / global 收口 / 锚点刷新」；shutdown / remove / repair 契约未动。新增两个行为级回归：`FootprintChunkPreloadCoreBehaviorTest`（fake World 证明精确请求 3×3 九区块、九个全部到达才完成、任一失败即整体失败、绝不触碰同步区块 API、null world 静默失败）与 `RebuildPipelineLaneOrderBehaviorTest`（Unsafe 插件 + 只登记不执行的调度后端证明：加载未完成前不投递任何世界体、加载失败零投递、清旧与生成各投自己的锚点 region、投递本身不执行世界体、region 回调返回值交回 chain）。证据等级如实：`paper-26.2` 全量 JUnit 实跑 **1123 项 / 168 容器 / 1121 通过**，2 项失败为 `FoliaDeclarationMatchesSchedulerTest` 在临时 `folia-supported: true` 下按设计变红（断言未改）；**没有**真实 Folia / Leaf 运行期验证，本轮未构建发布、未部署、未重启测试端；`folia-supported` 仍为临时 `true`，正式产物必须继续为 `false`。

【本轮：重建路径状态保护与闸门前移（2026-09-23）】针对上一轮异步重建流水线的 4 项审查问题做最小正确修复，只改 `PhysicalTableManager` / `DoudizhuPlugin` 与必要测试；**保持**原有「3×3 footprint 异步加载 → 旧锚点清旧 → 新锚点生成 → global 收口 → 锚点刷新」流程与新旧 owner 归属，不碰 `repairTableAfterChunkLoad` 的硬取消任务、`RegionizedCoreDetector`、`paper-plugin.yml` 的 `folia-supported`。

(1) **重建不再提前丢旧状态**。`rebuildAllTables` / `shiftAllAnchors` / `captureSingleRebuild` 原先在异步 stage 之前就 `placedTables.clear()` / `remove` + `unindexPlacedTable` + `markTableUnplaced`，开出一个「这张桌不存在」的秒级窗口：窗口内 `TableManager.cleanupIfEmpty` 只看 `isPlaced`，会把空桌**注销**（永久丢桌），重建失败也会连旧桌一起丢。现在旧 `PlacedTable` 与 footprint/实体索引**一直保留**，直到 global 收口把新桌 `putPlacedTable` 写进同一张 Map；失败/被闸门拒绝则原地保留旧状态（不是只记日志）。同桌互斥从「先摘除」改由新增的并发集合 `rebuildingTableKeys`（`ConcurrentHashMap.newKeySet()`）显式承担：已在飞的桌第二次冻结直接返回 null。新增 `hasPlacedOrRebuildingTables()` 作为「已放置或重建在飞」的合并门禁；新增世界体门禁 `placedTableForWorldBody(...)`——重建在飞时 `refresh` / `refreshPrivateHand` / `tickTable` / `dispatchTickPlayerSnapshot` 返回 null 让位（否则会按马上要被清掉的旧快照造出无人跟踪的孤儿实体）；重建流水线自己收尾的那次刷新走新的 `refreshWith(table, placed)` 直取刚提交的新桌，**不**受门禁影响。`syncViewerOnOwner` 在飞期间不再把「实体查不到」误判为不完整去触发第二次重建（避免撞互斥 + 刷误导告警），仍执行椅子判定框的一次性恢复。

(2) **闸门前移到 spawn 之前 + 收口拒绝必须清理**。身份 / 关闭 / 放置代次检查原先只在 spawn **之后**的 global 收口做；收口拒绝时新实体与方块已落到世界里而既无 owner 又无索引，只能变成永久孤儿。现抽成单一判据 `rebuildGateRejection(request)`（返回拒绝原因：`插件正在关闭` / `牌桌实例已注销或已替换` / `牌桌放置状态已被其它路径改写`，后者为新增的放置代次检查），**前置**在新锚点 region 的生成回调里先查一次（拒绝则不生成任何新桌），收口处复用同一判据（`commitRebuiltTable` 不再手写第二套）；收口拒绝（或收口抛异常）时，已生成的新桌经新增的 `dispatchRebuildCleanupOnOwnerRegion(newAnchor, tableName, () -> cleanupPlacedTable(value))` 投回**它自己的新锚点 region** 清理，失败只记日志、不打断整批。无论成功/被拒/异常，流水线末尾都 `rebuildingTableKeys.remove(request.tableKey())`，否则该桌永远无法再重建。

(3) **warmup / post-restore 门禁不再依赖瞬时 `placedTableCount()`**。`scheduleVisualWarmupRebuilds` 的每个 pass 与 `schedulePostRestoreRebuilds` 及其三档延迟 pass 全部改用 `hasPlacedOrRebuildingTables()`，避免在「恢复/重建尚未收口提交」的窗口里误判成没有桌可重建而整批跳过后面的 pass，多 pass 语义不变。

(4)(5) **只改文档**：上一段关于三份非并发 Map 的措辞与 `PhysicalTableManager` reload 关闭分支的描述已在上文就地勘误（原文保留，括注勘误）：重建流水线**自身**的收口写入由调用 lane + global；既有 owner/交互路径仍在各自 owner lane 读写同一批非并发 Map，统一并发边界属待偿技术债，本轮不做全局 Map 迁移；`onDisable` 的 reload 分支因 `MuzScheduler` 关闭期拒注册而实际不会执行任何 region 清理，不宣称关闭清理生效（关闭清理架构本任务不实现）。

测试与证据等级（全部真实执行）：`PhysicalTableChunkLifecycleContractTest` 中两条**编码旧机制**的重建断言按机制变更升级（`reload先切回未放置再冻结快照…` → `重建保持旧放置状态直到收口成功提交`，方向反转后禁止 `placedTables.clear()` / `markTableUnplaced()` / `unindexPlacedTable()` 出现在重建路径；`单桌修复和Y位移…` 的 pipeline 断言改为「footprint 异步加载 → 旧锚点清旧 → 闸门 → 生成 → 收口 → 刷新 → 收口拒绝清理」顺序），新增 `重建闸门前置到spawn之前且收口拒绝必须在自己的锚点清理`、`TablePeriodicTaskIntegrationContractTest` 的旧 `markTableUnplaced(previous.tableName())` 断言改为钉住 `captureSingleRebuild` 不提前切回未放置 + 由 `rebuildingTableKeys` 承担互斥（**断言编码旧机制，不是弱化**）；shutdown / remove / repair 契约一字未动。`RebuildPipelineLaneOrderBehaviorTest` 新增 3 个**行为级**回归：`uncommittedRebuildCleanupRunsOnTheTablesOwnAnchorLane`（记录型后端证明清理 stage 真实投递、投到**新锚点**而非旧锚点/global、投递不执行、fire 后落地）、`failedRebuildCleanupIsReportedButDoesNotBreakTheBatch`（清理异常不沿链传播打断整批）、`rebuildKeepsTheOldPlacedEntryUntilCommitAndIsMutuallyExclusive`（反射注入 `PlacedTable` 后直接驱动生产 `captureSingleRebuild`：冻结后 `isPlaced` 仍为真、同桌二次冻结返回 null、`hasPlacedOrRebuildingTables()` 为真）。实跑：`paper-26.2` `compileJava compileTestJava --rerun-tasks` 成功；定向 16 类 **96/96 通过**（17 容器，无失败/跳过/中止）；全量 JUnit **1127 项 / 168 容器 / 1125 通过**，2 项失败为 `FoliaDeclarationMatchesSchedulerTest` 在临时 `folia-supported: true` 下**按设计变红**，断言未被改动。

**未验证 / 剩余风险（如实）**：(a) 本轮**没有**真实 Folia / Leaf 运行期验证，未构建发布、未部署、未重启测试端；`rebuildingTableKeys` 跨 lane 读写的真实可见性、世界体门禁在真实 region 线程上的效果都只有编译 + 单测证据。(b) 保留旧放置快照带来一个新的、**已用门禁收窄但未消除**的窗口：重建在飞期间 `placedTable(...)` 仍返回旧桌，未接门禁的路径（`removeTable` / `canRemoveTable` / `describeJoinHitboxes` / `targetedJoinHitbox` 等拆桌与交互入口）会看到「即将被替换」的旧状态；本轮只给会**创建新实体**的路径加了门禁，其余路径保持既有语义（其中部分反而比原先「窗口内 placed 为空」更安全，例如放桌查重不再会重复放桌）。(c) 流水线失败若发生在「旧实体已清、新桌未提交」之间，旧 `PlacedTable` 仍登记但实体已不在，需靠 `repairIncompleteTables` / ChunkLoad 修复路径收口，本轮未新增该场景的行为测试。(d) `folia-supported` 仍为临时 `true`（正式产物必须为 `false`），真实 Folia 实服矩阵仍 blocked。

【本轮：ChunkLoad 修复路径的周期任务永久失效（2026-09-23）】`PhysicalTableManager.repairTableAfterChunkLoad` 原先在破坏性摘除放置状态前调用 `plugin.getTableManager().cancelOwnerPeriodicTasks(key)`。该方法是**永久摘除**语义：`TablePeriodicTaskRegistry.cancel` / `cancelOwner` 都走 `entries.remove` + `entry.cancelled = true`，因此之后 `rebindTablePeriodicTask` 里的 `periodicTasks.rebind(...)` 必然返回 `false`（`entries.get(key) == null`），被摘除的条目**没有任何复活路径**。两个真实后果：(1) `ownerPeriodicTasks` 里的开局发牌 timer（`GameTable` 的开局协调器经 `TableManager.runTableTimer` 注册，回调闭包携带单局状态、**无法由外部重新注册**）被永久打死，修复一次后发牌时间线不再 tick；(2) `periodicTasks` 里的 `tickActionBar` 同样消失，于是修复收口的 `notifyTableAnchorBinding` 直接抛「牌桌周期任务未重绑定」，把 `finishCleanupPlan` 里随后的 `refresh(plan.table())` 一起跳过——即已经重新生成世界体的桌也不再刷新。

修复（最小、只改一处）：删除那次 `cancelOwnerPeriodicTasks(key)`，改为只摘放置状态。理由是该取消**在语义上冗余**——紧随其后的 `removePlacedTableIfSame` 内部已经调用 `TableManager.markTableUnplaced`，而 `markTableUnplaced` 用**原子 rebind** 完成同一件事：`periodicTasks.rebind(key, table, null)` 与 `ownerPeriodicTasks.rebindOwner(table, null)` 带着 `bindingGeneration` 门禁把周期任务切到 global（旧 region/global 句柄被取消、迟到回调被代次拦下），`worldPeriodicTasks` 则按「未放置」语义摘除。于是「离开旧 owner lane」的既有目的一条不少，而应存在的周期任务被**保留**，并在修复成功收口时由 `notifyTableAnchorBinding` → `rebindTablePeriodicTask` 重绑到新锚点 region；保守保留（`plan.unresolved()` 非空）路径在任何破坏性步骤之前就返回，完全不触碰周期任务。**不存在「已取消且没有重建」的窗口**：唯一的取消是 `markTableUnplaced` 对 world 任务做的按放置语义取消，而该任务本来只应在「已放置」状态下存在，且同一条 `rebindWorldPeriodicTask` 在桌重新放置时必然重新注册（`rebind` 失败即 `register`）。跨 lane 复用沿用注册表既有的代次门禁，不新增 gate 代码。

行为级证据（新增 `ChunkLoadRepairPeriodicTaskSurvivalBehaviorTest`，`paper-26.2`）：用记录型 `SchedulerBackend`（只登记、不自动执行，可自行 `fire()`）经 Unsafe 夹具构造真实 `DoudizhuPlugin` / `TableManager` / `PhysicalTableManager` 与一张注入 `tables` 的逻辑桌，注册两个与生产同形的周期任务（`registerTablePeriodicTask` 的 ActionBar tick + `runTableTimer` 的开局发牌 timer），再通过反射直接驱动**生产** `repairTableAfterChunkLoad`（夹具的 `PlacedTable` 各 tracked 实体集合为空，因此全程不触碰 `Bukkit.getEntity`，无需 Bukkit 服务端）。断言：修复摘除放置状态后两个注册表条目**仍然存在**、收口入口 `rebindTablePeriodicTask` 返回 `true`、发牌 timer 被重绑到**该桌锚点 region** 且 `fire()` 后真的推进（这是「正在发牌」能继续的画面）、旧 global 句柄 `fire()` 后**零执行**（被取消 + 代次门禁双拦）、重绑后的 ActionBar 任务在锚点 region 上执行一次；另一条用例证明预检失败（owner 为 null）时放置状态与两个周期任务原样保留且任务仍可执行。**该行为用例在旧实现上实跑变红**（`ChunkLoad 修复不得摘除 ActionBar 周期任务 ==> expected: <1> but was: <0>`），证明它锁的是真实缺陷而非实现细节。`PhysicalTableChunkLifecycleContractTest` 中两条**编码旧机制**的断言按机制变更升级（`plugin.getTableManager().cancelOwnerPeriodicTasks(key)` 必须出现 → 必须**不**出现 + 仍走 `removePlacedTableIfSame`），方向反转后比原断言更强：既禁止永久摘除，也禁止漏掉 rebind（**断言编码旧机制，不是弱化**；断言字符串用完整调用表达式，因为修复注释里仍会以文字提到该方法名）。实跑：`paper-26.2` `compileJava compileTestJava --rerun-tasks` 成功（6 tasks executed）；定向 10 类 **64/64 通过**（11 容器，无失败/跳过/中止）；全量 JUnit **1129 项 / 169 容器 / 1127 通过**，2 项失败为 `FoliaDeclarationMatchesSchedulerTest` 在临时 `folia-supported: true` 下**按设计变红**，断言未被改动。

**未验证 / 剩余风险（如实）**：(a) 本轮只做本地编译与单测，**未打包、未部署、未重启测试端**，没有任何实服运行期证据；`markTableUnplaced` 的原子 rebind 在真实 region 线程上的效果仍只有单测证据。(b) 截图中「卡在正在发牌」的现场现象与本次缺陷的**因果关系未证实**：本轮证明了「修复路径会永久打死发牌 timer」这一缺陷真实存在且已修，但没有取得该现象的运行期日志证据，不得据此宣称根因已定位。(c) 本次未改动的既有残留风险：cleanup barrier 失败/epoch 失效时该桌已经从 `placedTables` 摘除而不会被 `captureIncompleteRebuildRequests` 重新扫到（它只扫已放置桌），因此该桌会停在未放置状态、只能等下一次异常路径；此行为在本次修复前后一致，属既有问题、不在本轮范围。`folia-supported` 仍为临时 `true`（正式产物必须为 `false`），真实 Folia 实服矩阵仍 blocked。

【本轮：开局发牌 timer 在 Folia/Leaf 上注册即失败——卡在「正在发牌」的已证根因（2026-09-23）】反编译测试端两份内核（`folia-26.1.2.jar` 与 `leaf-26.1.2.jar`）确认：`FoliaGlobalRegionScheduler.runAtFixedRate` 与 `FoliaRegionScheduler.runAtFixedRate` 在初始延迟 `<= 0` 时直接抛 `IllegalArgumentException("Initial delay ticks may not be <= 0")`（Leaf 的 Global 调度器实现相同；`FoliaAsyncScheduler` 只拒绝 `< 0`，不受影响）。而开局协调器以 `RoundOpeningCoordinator.start` → `support.runTableTimer(0L, 1L, …)` 注册发牌 timer，`PaperSchedulerBackend.runGlobal/runRegion` 原样把 `delay=0` 透传给 `runAtFixedRate`。于是 `startRound` 在 `setOpeningPhase(DEALING)` + `refresh()` **之后**才抛异常：牌桌已进入 DEALING 并渲染「正在发牌」，但发牌 timer 从未注册；异常沿 `PhysicalTableManager.handleInteraction` 的 `catch (RuntimeException)` 被转成一条玩家 ActionBar 提示（`hint(..., exception.getMessage(), RED)`），**不写服务端日志**——这解释了两个核心都卡住、日志却没有任何对应堆栈。该路径全仓只有这一处 0 延迟周期注册（其余 `runTimer`/`registerTablePeriodicTask` 均为 `>= 1`），且 Paper 单线程核心的 `runAtFixedRate` 接受 0 延迟，所以此前 Paper/Leaf 之前的版本与全部单测都未暴露。修复（最小、只改后端一处语义）：`PaperSchedulerBackend` 新增 `repeatingInitialDelay(delay) = max(1, delay)`，Global/Region 的 `runAtFixedRate` 分支统一钳到至少 1 tick（与既有 entity/player 分支 `Math.max(1L, delay)` 一致；旧 Bukkit 定时器的 0 延迟本就是下一 tick 执行，语义不变），一次性任务的 `run`/`runDelayed` 与 async 分支不变。**行为级证据**：`PaperSchedulerBackendTest.repeatingZeroDelayIsClampedBecauseFoliaRejectsNonPositiveInitialDelay` 用会像真实内核一样拒绝 0 初始延迟的原生替身，断言 Global/Region 周期任务传给 `runAtFixedRate` 的初始延迟为 1、正延迟原样保留；临时回退钳制后该测试实跑变红（`IllegalArgumentException: Initial delay ticks may not be <= 0`），恢复后 9/9 通过。实跑：`paper-26.2` `compileJava compileTestJava --rerun-tasks` 成功；定向（后端 + 开局流程 + ChunkLoad 周期任务存活）**17/17**；全量 JUnit **1130 项 / 169 容器 / 1128 通过**，2 项失败仅为 `FoliaDeclarationMatchesSchedulerTest` 在临时 `folia-supported: true` 下按设计变红（断言未改）。**未验证（如实）**：日志里没有该异常的直接堆栈（被交互层吞成玩家提示），「卡在正在发牌」与此缺陷的对应关系来自内核字节码 + 代码路径推导 + 回归测试，尚无实服复现；本轮未打包、未部署、未重启测试端，需下一轮冒烟确认开局能推进到 REVEALING/BIDDING。另记技术债：`handleInteraction` 把任意 `RuntimeException` 只转成玩家提示、不记服务端日志，会继续掩盖此类调度失败，本轮未改（非本缺陷的最小修复范围）。上一轮的 ChunkLoad 周期任务永久摘除修复仍然成立，但它只在发生 ChunkLoad 修复后才触发，不是首局就卡住的原因。`folia-supported` 仍为临时 `true`（正式产物必须为 `false`），真实 Folia 实服矩阵仍 blocked。

【本轮：Lophine（Folia 系，26.2）实服报错修复与删除道具按钮（2026-09-23）】用户在 Lophine 26.2（`RegionizedServer` 栈）上实测冒烟 JAR 报两类本插件异常，均已修：(1) `restoreOccupiedChairHitboxVisibility` 调 `getNearbyEntities` 抛 `Cannot getEntities asynchronously`，线程为 global（`region={null}`）——桌子区块卸载后 `markTableUnplaced` 清空 owner 锚点，`syncViewer` 投递的 `runTableNow` 回落到 global lane，随后在 global 上读实体。`syncViewerOnOwner` 与 `continueViewerSyncOnOwner` 入口现以 `Bukkit.isOwnedByCurrentRegion(placed.anchor())` 门禁，不在锚点 owner region 上就跳过（桌子不在任何玩家附近，区块重载后由 ChunkLoad 修复链刷新）。(2) `PlayerOutputDispatcher.showEntity` 在玩家 lane 调 `player.showEntity` 时，实体（桌边 TextDisplay）属于另一 region，抛 `Accessing entity state off owning region's thread`；`showEntity` / `hideEntity` 现增加 `Bukkit.isOwnedByCurrentRegion(entity)` 门禁，跨 region 实体直接跳过（本就不在该玩家追踪范围；私有实体出生即 `visibleByDefault=false`，跳过隐藏不会泄漏）。另按用户要求删除 PLAYING 阶段桌上的「道具」按钮（三处 `ButtonAction.GADGET` 状态移除，枚举与 `handleInteraction` 分支保留为兼容），当前回合按钮改为「提示/不要/清选」`-0.60/0/0.60`，非当前回合不显示按钮。默认道具保持鸡蛋/水桶/番茄 + 第九格聊天气泡（`VirtualGadgetBar.defaults()` 未改）。验证：`paper-26.2` 编译成功，全量 JUnit 1130 项 / 1128 通过，2 项为 `FoliaDeclarationMatchesSchedulerTest` 在临时 `folia-supported: true` 下按设计变红（断言未改）；两处门禁只有编译与全量回归证据，**没有**新增行为测试，也未在实服复验。冒烟 JAR（paper-26.1.2 目标）SHA-256 `a27c5dd1e2d3f9c675e8ac01e278ad5a3e69733fdade26c8c6b4ddb92a28be3b`，仅通过临时下载链接交付用户自测，未部署到测试端。用户同时提出的「物品栏直接做」「默认白色」「玩家头颅轮换」「开局后位置固定」四点语义尚待用户确认，本轮未实现。

【本轮：发牌中途停住防护与九格栏去染色（2026-09-23）】用户反馈实服对局中途「发牌动画停住并刷屏报错、TPS 掉到 0」，未拿到日志，以下为基于代码路径的防护修复，**根因未由日志证实**。(1) `RoundOpeningCoordinator` 的发牌状态在调用渲染（`refreshPhysicalTable` / `tickOpeningActionBar`）之前已推进；而 `MuzScheduler` 在周期回调抛异常时会取消该任务，所以任一渲染异常逃出 `tick()` 都会永久打死发牌 timer，牌桌停在 DEALING。现所有渲染调用经 `safely(step, action)` 隔离：异常不外抛、时间线继续推进，每 200 次失败最多记一次日志（`Support.reportRenderFailure`，`GameTable` 实现为带堆栈 WARNING），避免每 tick 刷屏。新增行为测试 `RoundOpeningCoordinatorTest.renderingFailuresNeverStopTheDealingTimeline`（每次渲染都抛异常仍须走到 BIDDING、失败日志 1..3 次）；临时让 `safely` 重新抛出时该测试实跑变红。(2) 九格道具栏被染成橙色：`TableGadgetBarHudService` 把栏作为 overlay 的子节点拼接，继承了 overlay 的颜色，位图字形被染色；现改为 `Component.empty()` 下的兄弟节点，并对栏显式 `WHITE`。按用户确认，头像行保持「小 大 小，中间为当前出牌者」（`GameTable.trickHudSeats` 既有实现，未改）；栏保持屏幕图标栏形式。**未完成**：ActionBar 上 overlay 文本仍拼在栏前，overlay 出现时栏会随之水平移动，不满足「开局后固定位置」，需另行按固定宽度布局处理。验证：`paper-26.2` 全量 JUnit 1131 项 / 1129 通过，2 项为临时 `folia-supported: true` 下按设计变红的声明守护测试。冒烟 JAR（paper-26.1.2）SHA-256 `7f1e31a2dcad2c374cef9aeb404d4ce432a3c1b577e176ad23b3ca498dd61931`，仅以临时链接交付，未部署、未实服复验。

【本轮：九格道具栏固定屏幕位置（2026-09-23）】上一轮遗留「overlay 出现时栏会水平移动」。本轮两处修复：(1) 资源侧：九格栏字形 PNG 原为 22×22 画布但未锁定宽度，Minecraft BitmapProvider 的 advance = 最右不透明列 + 2，实测底图 23、选框 22、图标 17..19，而运行期统一按 `GADGET_BAR_CELL_ADVANCE=22` 回退叠层，导致各格错位、栏宽随图标漂移。`build.gradle.kts` 的 `writeGadgetBarGlyph` 现把画布裁为 21 列并在右下角放 alpha=1 锚点（与记牌器同法），所有九格栏字形 advance 恰为 22；`CraftEngineBundleResourcesTest.gadgetBarUsesIndependentMuzGlyphFamilyAndRealSlotPngs` 的「PNG 宽 22」断言升级为「客户端 advance 等于 `GADGET_BAR_CELL_ADVANCE`」（旧断言只锁画布宽度，编码了不足以保证位置的条件，不是弱化）。(2) 运行期：`TableGadgetBarHudService.compose` 复用 `HotbarActionBarLayout` 固定宽度算式——栏先画，负空格回退后正文以栏中线居中叠加，末尾补偿使净前进量恒等于栏宽 `9×22`，客户端居中的始终是栏本身，正文长短不影响栏坐标；栏与正文为 `Component.empty()` 下的兄弟节点，栏不继承正文颜色。正文宽度只用已验证的客户端字体快照（`HudOverlayRuntimeState.verifiedHotbarFontMetrics()`）测量，测不准或 CE 偏移不可用时不猜，栏保持固定、正文改发聊天，每人每 10 秒最多一次（离线清理限频表）。新增契约测试 `提示文字出现时九格栏位置保持固定`。验证：`paper-26.2` 全量 JUnit 1132 项 / 1130 通过（2 项为临时 `folia-supported: true` 下按设计变红的声明守护测试）。**未验证**：客户端实际显示与位置固定需重下资源包后进服确认；字体快照在测试端因 CE 版本不匹配（`reloadPlugin` NoSuchMethodError）可能始终未就绪，此时提示文字会走聊天而非 ActionBar。冒烟 JAR（paper-26.1.2）SHA-256 `f973fe9602380845dcb9e28c999d886a3c7e3965ee8a400c4c796de887840e98`，资源包 `e7afbc9a…`、CE bundle `1ba921f8…`，仅以临时链接交付。

【本轮：Folia 上桌内道具投掷卡住修复（2026-09-23）】用户报告在真实 Folia 系服务端（Lophine 26.2，`RegionizedServer`）上桌内道具「投掷」（鸡蛋/番茄投向另一位玩家、水桶贴身水幕）会卡住且**没有日志**，其余功能暂无明显 bug。逐 lane 追踪「右键 → 效果结束」的完整链路后确认：投掷链路上有多处 lane 依赖，任一处不成立都会让一次投掷「没反应」，而**真正的「没反应」都是静默 `return false` 或静默丢弃**——这正是「没有日志」的来源。**归因如实**：本轮**未取得该现象的实服日志**，「投掷卡住」与下列缺陷的因果关系属**推断**（依据代码路径 + Folia lane 语义 + 回归测试），不是已证根因；缺陷清单里 (1)(2)(3)(4)(5) 在接手时**已由前序轮次/上一代理收口**（本轮保留并复核），本轮**实际新增/补完**的是 (6) 与下面「本轮改动」列出的四项。缺陷清单：(1) 效果推进曾跑在 global lane（`TableGadgetService.tick()` → `TableGadgetEffectService.tick()` → `Bukkit.getCurrentTick()`）；global 没有 ticking region（Folia 抛 `No currently ticking region`），而 `MuzScheduler` 会 `cancel` 抛异常的周期任务，扫描任务一死则所有桌的推进与目标高亮永久失效。(2) 清理顺序曾是「先删实体、后摘状态」，跨 region 的 `entity.remove()` 抛 `Accessing entity state off owning region's thread`，一次异常就把该条效果永久留在表里、`activeEntityCount()` 永不归还，之后所有投掷都被静默拒绝。(3) 效果生成曾在玩家 lane、推进与清理在 global lane，两边都不拥有对方 region 的实体。(4) 飞行物推进与水幕本体/头顶水桶曾用同步 `entity.teleport(...)`（区域线程抛 `Must use teleportAsync while in region threading`）。(5) `effects`/`lastPlayTicks` 曾是 `LinkedHashMap`，被多个 lane 并发读写。(6) **扫描任务可被单桌异常打挂**：`tick()` 中读阶段/座位与投递高亮不在保护范围内，`runTableNow` 之外的任何一步抛异常都会取消整条扫描周期任务——这是本轮补完的一处。**本轮改动**（只碰 `TableGadgetService` 与 `TableGadgetEffectService`）：① 跨 region 放弃投掷时**限频记录日志**（新增 `reportFailure(String)` 无异常重载，专门覆盖「按设计放弃但不该无声」的分支），把「玩家看不到任何反应、服务端也没有日志」变成可排查的 WARN；② `tickTable` 读时钟包 try/catch：未放置桌回退 global 时只跳过本轮，效果保留、等重新放置后由锚点 region 收尾，异常绝不逃到调度器；③ `playGenericSound` 读 `viewer.getLocation()` 前加 `isOwnedByCurrentRegion` 门禁，避免一次跨 region 音效把整个成功投掷打成失败；④ `tick()` 的每张桌处理整段包进 try/catch（此前只包住 `runTableNow` 调用），单桌失败不得取消扫描任务；⑤ 合并 `tickTable` 上方与其后重复的 javadoc（上一代理半成品留下的失效旧注释）。**行为级证据**（新增 `TableGadgetEffectReleaseBehaviorTest`，`paper-26.2`，5 项）：① actor/target 跨 region 时放弃投掷、不消费任何状态、**且必须留痕日志**，且归属判定早于时钟读取；② 目标在推进中途离开本 region 时必须摘条目 + 归还名额，之后投掷必须重新尝试生成；③ 实体清理失败（模拟跨 region `remove` 抛异常）仍必须摘条目 + 归还名额；④ 非 ticking lane 上推进必须安静跳过、保留效果并留痕；⑤ 单桌派发抛异常后扫描周期任务必须仍然存活（限频只落一条日志）。**mutation 反证**：分别回退「归属判定日志」「先摘状态后删实体」「扫描 try/catch」「时钟读 try/catch」「单条效果 try/catch」五处核心修复后，对应 5 个用例逐一实跑变红（红因分别为 `跨 region 放弃投掷不能是静默失败`、`模拟跨 region 实体删除失败`、`模拟牌桌 owner 派发失败`、`No currently ticking region`、`Accessing entity state off owning region's thread`），恢复后 5/5 通过。同批把 `FoliaRuntimeLaneContractTest.gadgetSweepOnlyDispatchesAndEffectsGuardBeforeClock` 的 `tickTable` 形状断言从「必须出现 `getCurrentTick()`」改为「时钟读取（现已收口到 `EffectRuntime` 边界）必须排在 `pending.isEmpty()` 早退之后」，并**加固**为同时钉住「生产 `EffectRuntime` 实现仍直接调用 `Bukkit.getCurrentTick()`」，防止顺序断言被假时钟架空——该旧断言在上一代理引入 `EffectRuntime` 时就已经失效（`tickTable` 已改调 `runtime.currentTick()`），属**断言编码旧机制**而非本轮弱化。实跑：`paper-26.2` `compileJava compileTestJava --rerun-tasks` 成功（6 tasks executed）；定向 15 类 **103/103 通过**（16 容器）；全量 JUnit **1137 项 / 170 容器 / 1135 通过**，2 项失败为 `FoliaDeclarationMatchesSchedulerTest` 在临时 `folia-supported: true` 下**按设计变红**（断言未改动）。**未验证 / 剩余风险（如实）**：(a) 本轮只做本地编译与单测，**未打包、未部署、未重启测试端**，没有任何实服运行期证据；「投掷卡住且无日志」与该组缺陷的因果关系属**推断**，尚无实服日志复现。(b) `playGenericSound` 的跨 region 归属门禁**没有专属行为测试**：`play` 成功路径会取 `Sound.ENTITY_SNOWBALL_THROW`，而 `Sound` 是注册表背书的枚举、仓库现有夹具没有服务端无法初始化（实跑报 `ExceptionInInitializerError`），因此该项只有编译与全量回归证据。(c) 目标高亮（`acquireReference`/`releaseTargetCompletely` 的 `setGlowing`）仍在 actor 所在 player lane 上改 target 的发光，同桌同 region 时成立；跨 region 的 target 仍可能抛异常，按「非本缺陷最小范围」本轮未动，属既有残留风险。(d) 上一代理留给本轮的两处半成品已修正：`tickTable` 上方失效的旧 javadoc 已合并；`FoliaRuntimeLaneContractTest` 的过时断言已如上更新。`folia-supported` 仍为临时 `true`（正式产物必须为 `false`），真实 Folia 实服矩阵仍 blocked。

【本轮：投掷修复复查跟进（2026-09-23）】独立复查（只读）确认投掷修复主体成立，并指出目标高亮仍在 actor 的 player lane 上直接读写 target 实体的发光（跨 region 会抛异常、每 2 tick 刷屏，且 release 失败会让 target 永久发光）。已修：`TableGadgetService.acquireReference` / `releaseTargetCompletely` 的发光读写改为经 `PlayerOutputDispatcher.runPlayer(targetId, …)` 投到 target 自己的 lane，执行时再核对引用表（已被重新引用则不撤发光）；`originalGlowing` 改为 `putIfAbsent`。`TableGadgetEffectService.viewers()` 补与 `playGenericSound` 同口径的归属门禁；`validTarget()` 把归属门禁提到读实体状态之前。验证：`paper-26.2` 全量 JUnit 1137 项 / 1135 通过（2 项为临时 `folia-supported: true` 下按设计变红的声明守护测试）。发光改投 target lane **没有新增行为测试**，只有编译与全量回归证据；复查另列出的低风险项（孤儿效果兜底过期、名额上限跨 region 竞态、几何无效分支无日志、测试夹具 `foreignPlayers` 未写入导致 `validTarget` 门禁未被直接覆盖）本轮未处理。

【本轮：九格道具栏并入出牌 HUD BossBar（2026-09-23）】用户反馈 `TableGadgetBarHudService.tick` 用 `output.sendActionBar` 发九格栏，而 `GameTable.tickActionBar` → `ActionBarOverlayService.showOverlay` 也直发 ActionBar，两者抢同一个 ActionBar 槽位来回覆盖闪烁；要求把九格栏从 ActionBar 独立出来，并入出牌 HUD（牌行 / 头像行 / 记牌器行，`TrickHudService` + `TrickHudView` 渲染到 BossBar 标题）作为**第四行**，共用同一条 BossBar，位置固定、开局后不随状态变化移动，ActionBar 从此只给普通状态提示。(1) 资源档位：九格栏并入 BossBar 后不能再贴顶，构建期把它整条固定下移——`build.gradle.kts` 新增 `val gadgetBarRowDownOffset = 160`，`images.yml` 的 `gadget_bar_*` provider 的 `ascent` 从 `gadgetBarCellHeight` 改为 `gadgetBarCellHeight - gadgetBarRowDownOffset`（即 `22 - 160 = -138`），`height` 仍为 22、码位、PNG、advance 全部不变；生成的 `PackTiers` 新增 `GADGET_BAR_ROW_DOWN_OFFSET`，插件侧 `PackAssets.GADGET_BAR_ROW_DOWN_OFFSET` 同源，另新增 `PackAssets.gadgetBarRowAdvance()`（`= 9 × GADGET_BAR_CELL_ADVANCE = 198`）作为第四行净前进量。这是**固定常量档**（同一 JVM 内运行期不可改），不是可连续调的自由整数，所以**没有新增配置键**，Debug Web 白名单保持 18 键不变（第四行不参与 Web 预览），`DoudizhuPlugin.ensureConfigIntegrity` 无需改动。两侧各自留了交叉引用注释说明对齐关系。选的 160 让第四行底边落在记牌器行（默认 `counter.offset-down=122`）下方。(2) `TrickHudView`：新增 `GadgetBarRow`（glyph 文本 + 自报 advance，`EMPTY` 表示不渲染）与带第四行的 `buildMiniMessage` 重载；第四行沿用与前三行完全相同的容器宽 `W` 居中算式（行首垫 `(W - 栏宽)/2`、末行补到 `W`、行首尾 `offset-x` 配对），所以「净前进量恒等于 `W`」的闭合条件不变；`containerAdvance` 增四参版本把九格栏宽度也计入取最大。九格栏没有独立 Y 配置：竖直位置由字形自带的固定下移档决定。(3) 数据接入：`TableGadgetBarHudService` 新增 `public String hudBarGlyphText(UUID)` 只读字形快照（已套 `muz_gadget_bar` 字体标签），仅当 `stopped=false`、道具功能开启、CraftEngine 偏移层可用、该玩家在 `PLAYING` 同桌且非机器人时返回非 null；`TrickHudService.render` 经新增私有 `gadgetBarRowOf(viewerId)` 把它包成第四行，宽度用 `PackAssets.gadgetBarRowAdvance()`；该行不可用时第四行整条不产出、其余三行照旧（含「只显示指定行」与调试棒预览不显示第四行的语义）。(4) `TableGadgetBarHudService` 去 ActionBar 化：删除 `tick()` 里的 `sendActionBar` 合成以及只为它服务的 `compose` / `measureOverlay` / `offsetComponent` / `chatFallback` / `render` 与两个限频表；`tick` 改为低频（40 tick）`prefetchActivePlayers`——只给「在打且数据未加载」的玩家补一次异步读取，稳态几乎空转，不再生产任何 ActionBar。选槽（`PlayerItemHeldEvent`）、数据加载、语音入口（`tryOpenVoice`）、代次保护（`refreshResultAllowed`）全部保留；选槽后新增 `redrawHud`——经 `PlayerOutputDispatcher.runPlayer` 投到 player lane、再 `TableManager.runTableNow` 回到桌 owner lane，调用新增的 `GameTable.refreshTrickHudFor(Player)`（复用既有 `sendTrickHud`）即时重画，选框才会跟着移动。`clearTable` 不再发空 ActionBar（那会擦掉别人正在显示的状态提示），改为丢弃该桌座位的道具缓存并投递一次重画让第四行立刻消失。`ActionBarOverlayService` 完全保持只发普通提示。新增 `TableManager.getTableOf(UUID)` 供不需要 Player 实体的查表路径使用（与 `getTableOf(Player)` 同源同语义）。(5) 测试：`TableGadgetBarHudServiceTest` 中两条**编码旧机制**的断言按机制变更升级——`对局额外栏复用八格数据并保留第九格语音入口` 删除 `actionBarOverlay.currentOverlay` 与 `output.sendActionBar(playerId` 断言，新增「九格栏并入出牌 HUD 后不再占用 ActionBar」（钉住 `hudBarGlyphText` 公开、`output.sendActionBar(playerId`/`HotbarActionBarLayout`/`chatFallback`/`MINI.deserialize` 必须**不**出现）与「九格栏竖直位置由固定下移档决定且两侧同源」（钉住 `build.gradle.kts` 的 `gadgetBarRowDownOffset`、ascent 算式、下移档低于记牌器行、`gadgetBarRowAdvance()==198`）；原 `提示文字出现时九格栏位置保持固定` 的 `HotbarActionBarLayout` 固定宽度断言随机制退役（**断言编码旧机制，不是弱化**）。`CraftEngineBundleResourcesTest` 的九格栏 `ascent` 断言从 `"22"` 改为 `cellHeight - GADGET_BAR_ROW_DOWN_OFFSET`。`TrickHudViewTest` 新增三条**行为级**回归：`第四行九格栏计入容器宽且净前进量守恒`（198 成为最宽行、净前进量仍等于 `containerAdvance`）、`无九格栏快照时第四行不产出且容器宽不变`（与三行版逐字符相同）、`只有九格栏时仍然渲染并居中`；绘图解释器补认 `<gadget:宽>` 标签。实跑：`paper-26.2` `compileJava compileTestJava processResources --rerun-tasks` 成功（70 警告均为既有 Unsafe/API 弃用），`paper-26.1.2` `compileJava --rerun-tasks` 成功；`paper-26.2` 全量 JUnit **1141 项 / 170 容器 / 1139 通过**，2 项失败为 `FoliaDeclarationMatchesSchedulerTest` 在临时 `folia-supported: true` 下**按设计变红**（断言未改动）。**未验证（如实）**：本轮**未构建发布、未部署、未重启测试端**，没有实服运行期证据；第四行的客户端实际显示（是否落在记牌器行下方、是否与前三行重叠、九格栏是否居中且位置固定）必须**重新生成并加载资源包后进服确认**，纯逻辑与单测测不了「字形 ascent 在客户端 BossBar 上的真实像素位置」；`clearTable` 与选槽的即时重画在真实 region 线程上的合法性也只有编译与单测证据。`folia-supported` 仍为临时 `true`（正式产物必须为 `false`），真实 Folia 实服矩阵仍 blocked。（复查勘误：初版取 152 时九格栏顶在基线下 130，压进默认记牌器 frame 底（基线下 137）7 像素，已改为 160 留 1 像素间隙；对应测试由「下移档大于记牌器偏移」升级为按字形盒边比较。）

【本轮：Lophine 实服日志三组修复（2026-09-23）】依据 `latest (2).log`（Lophine 26.2-766，CraftEngine 26.8.2-假人修复版），由三个 ds 子代理并行修复、一个 ds 子代理只读复查。(A) **HUD 保存 NoSuchMethodError**：javap 三代 CE 证实 26.x 已无 `reloadPlugin(Executor, Executor, boolean)`，只有 4 参（26.9.1 另有 6 参）。`CraftEngineHudResourceBridge` 去掉全部 CE 静态引用，改为运行期反射，按显式偏好 `{3,4,6}` 精确 arity 查找；第 4 位在 26.x 是「是否触发 `CraftEngineReloadEvent`」，0.0.67 的 3 参实现无条件触发，因此传 `true` 保持等价（复查勘误：初版称「多余位补 false、语义完全一致」不成立）；找不到重载时抛带签名清单的 `UnsupportedOperationException`，失败仍回滚。新增 `CraftEngineReloadAdapterTest`（14 项）。另：~~AGENTS 前文「反射一律走 TabooLib」与仓库事实冲突——`build.gradle.kts` 无 TabooLib 依赖、源码 0 处 import，现有反射均为 `java.lang.reflect`，本轮沿用既有写法，该约束需 linmumua 裁决。~~（**勘误（本轮）：该断言已过时**——TabooLib 现已实际接入：`build.gradle.kts` 已应用 `io.izzel.taboolib` 2.0.38 插件、声明并内嵌 `io.izzel.taboolib:common-reflex:6.3.0-75b18a2`（连同 kotlin-stdlib / asm / commons-lang3），源码已真实 `import taboolib.library.reflex.*`（`CraftEngineOffsetService`、`CraftEngineHudResourceBridge`、`CraftEngineFurnitureService`），「反射一律走 TabooLib」约束自此有仓库事实支撑、不再需要裁决。原文保留不改，仅此括注。**）(B) **牌桌实体在 global 线程被刷新（136 次）与 ChunkLoad 修复失败**：根因链——`onChunkUnload → markTableUnplaced` 清锚点并把开局 timer 切 global；修复任务随之排到 global，`captureCleanupPlan` 读 `getVehicle()` 抛异常；异常使 `notifyTableAnchorBinding` 永远不执行，桌子永久停在 global，开局 timer 在 global 写 TextDisplay。修复：`PhysicalTableManager` 新增可注入 seam `WorldBodyLane`，`refreshWith` / `refreshPrivateHand` / `tickTable` 入口在不拥有锚点 region 时跳过世界体并限频记录；`captureCleanupPlan` 读实体前判归属，不归属记 `entity-off-lane:` 走保守分支；`queueChunkLoadRepair` 排修复前经 `restoreOwnerAnchorForLoadedChunk` 把周期任务重绑回锚点。门禁自身在世界卸载时 `Location.getWorld()` 会抛，已包 try/catch 按「不拥有」处理（复查发现）。新增 `WorldBodyLaneGateBehaviorTest`（5 项）；`PhysicalTableChunkLifecycleContractTest` 两处断言因实体解析改走 seam 而升级（编码旧机制，非弱化）。(C) `TableGadgetBarHudService.redrawHud` 投递前检查牌桌仍注册，并捕获 `RuntimeException`（不止 `IllegalArgumentException`，复查发现）；`CraftEngineFurnitureService` 新增 `RegionGate`，`setFurnitureHitboxesVisible` / `removeFurniture` 跨 region 跳过并限频告警，`describeFailure` 修复 `bridge failed: null`。新增 2 个测试类（10 项）。验证：`paper-26.2` 全量 JUnit 1171 项 / 174 容器 / 1169 通过（2 项为临时 `folia-supported: true` 下按设计变红的声明守护测试），`paper-26.1.2` 编译通过。**未验证**：均无实服运行期证据；CE 26.8.2 上反射重载是否成功、重绑锚点在真实 region 线程上的效果需进服确认；「Paper 上 isOwnedByCurrentRegion 恒真」本机无 Paper 内核字节码证据。复查列出的低风险项未处理：锚点重绑早于 footprint 校验、PackManager 反射方法构造期缓存、`skippedCrossRegionLogs` 无上限、跨 region 判定存在 4 份重复实现（技术债）。

**【本段末尾勘误（本轮，2026-09-24）】** 上面这条 2026-09-23 记录里写的「2 项为临时 `folia-supported: true` 下按设计变红的声明守护测试」只是**该轮次当时**的状态快照。此后冒烟结束、`folia-supported` 已恢复为 `false`，守护测试现应全绿；该段中的 1171 项 / 174 容器同样是历史快照，不是当前数字。当前事实见本节开头的「当前事实勘误」。原文保留不改，仅此追加说明。

## 当前技术栈

| 层 | 技术 | 备注 |
|---|---|---|
| 平台 | Paper / Purpur，`folia-supported: false` | 目标 API 由 `MuzTarget` 表驱动，默认 `paper-26.2` |
| 主框架 | 原生 Bukkit `JavaPlugin` + TabooLib（工具层，**已实际接入**） | 反射走 TabooLib `taboolib.library.reflex`；不用注解生命周期。构建期应用 `io.izzel.taboolib` 2.0.38，内嵌 `common-reflex:6.3.0-75b18a2`（含 kotlin-stdlib/asm/commons-lang3） |
| 语言 | Java（主源码约 117 个 `.java`，0 个 Kotlin 源码；以仓库实际文件数为准，勿照抄历史数字） | Kotlin 仅用于 Gradle DSL |
| 渲染 | Display Entity + MiniMessage / Adventure | 字形由资源包驱动 |
| 配置 | `org.yaml:snakeyaml:2.6`（已 relocate） | 封装于 `MuzYamlConfig` |
| 存储 | `sqlite-jdbc:3.46.1.0`，可选 `mysql-connector-j:8.4.0` | MySQL 为 `compileOnly` |
| 序列化 | `gson:2.11.0` | |
| 可选依赖 | CraftEngine 0.0.67、PlaceholderAPI 2.12.2、VaultAPI 1.7 | 全部 `compileOnly` |
| 构建 | Gradle + Kotlin DSL + `com.gradleup.shadow:9.3.0` + `io.izzel.taboolib:2.0.38` | 产物在 `build/<targetId>/`；shadow 合并、taboolibMainTask 重定位、packagePluginJar 收口三段式 |
| 测试 | JUnit 5（junit-bom 5.13.4），约 170 个测试类（`src/test/java` 下 `.java` 文件数，以仓库实际目录为准） | 仓库路径含中文时 Gradle 测试 worker 会报 `ClassNotFoundException`，需改用独立 JUnit Launcher 实跑（classpath 用 argfile 传，避免 MSYS2 搅坏 `;` 分隔符） |

### 1.10.44（实体筹码收付与失败状态保护，定向验证通过）

- 筹码余额改为实时统计玩家主背包 0..35 与副手中的匹配物品，每件物品按实际数量计一筹码；不统计护甲、光标或容器内部，不再读取或写入独立 `chip-balance` 虚拟余额。主手设置的 `chip-item-stack` 只作为完整物品元数据匹配模板，忽略数量，不自动兑换或发放旧物品。
- 新增 `PhysicalChipService` 作为实体筹码唯一库存入口：所有库存读写限定主线程；设置、增减和多人转移先预演全部槽位，容量或数量不足整批拒绝，批量转移先扣后发，写入异常按槽位回滚，回滚失败明确提示库存需核查。实体筹码付费局禁止机器人开局，单局结算增加防重入保护。
- 实体筹码结算不沿用 Vault 的逐人欠账语义；失败状态为 `FAILED`，聊天提示“实体筹码结算未完成，请核查日志与库存”，不可伪造持平、余额 0 或已回滚成功。异步/离线余额查询返回不可查询，不伪造缓存值；Placeholder 先做主线程门禁。
- 对局冻结经济指纹，活动局修改支付模式、筹码模板、场次倍率或经济开关后拒绝结算，避免错扣/错付；非大厅经济设置入口拒绝修改。核心分乘法使用精确算术并由统一上限保护；结算收尾使用 `finally` 恢复状态并重置牌局。
- 26.1.2 强制执行 `compileJava compileTestJava processResources processTestResources --rerun-tasks`，7 项任务成功；实体筹码、结算、入口、溢出和 HUD 定向回归 **61/61**，8 个容器成功，无失败、跳过或中止。保留既有 Unsafe/API 与独立启动器注解依赖警告。
- 本轮未打包、未部署或重启实服；真实背包操作、CraftEngine 资源应用和客户端显示仍需人工确认。实体筹码保存失败/数据库兼容的现场升级路径未在实服验证。

### 1.10.45（九格图标额外栏与语音面板等比修复，定向验证通过）

- 桌内道具不再通过九格 Bukkit Inventory GUI 作为对局入口；新增独立 `muz_gadget_bar` 九格字体资源，前八格按 `VirtualGadgetBar` 显示真实道具图标，第九格显示语音入口，玩家通过滚轮/数字键选择，右键第九格打开私有实体语音面板。原版 `hotbar.png` / `hotbar_selection.png` 不被覆盖，退役三图标 Hotbar 字体链不重新接回。
- `TableGadgetBarHudService` 仅向 PLAYING 阶段同桌在线真人发送额外栏，异步读取玩家道具配置并在主线程组合字形；空槽保留占位，选择状态和语音资格由同一服务维护。牌桌按钮只刷新额外栏，不打开配置 Inventory；配置 GUI 仅保留编辑用途。**（勘误 2026-09-23：其中「发送额外栏」当时指经 ActionBar 发送，现已过时——九格栏并入出牌 HUD（BossBar 第四行）后本服务不再合成/发送 ActionBar，只暴露 `hudBarGlyphText(UUID)` 只读字形快照供 `TrickHudService` 渲染，选槽后经 `GameTable.refreshTrickHudFor` 即时重画；原句保留不改，仅此括注。详见「本轮：九格道具栏并入出牌 HUD BossBar（2026-09-23）」。）**
- `TableSpeechPanelService` 文字变换改为等比缩放，面板宽度只影响命中区域，不再按单空格基准横向放大中文语音文字。
- `paper-26.1.2` 强制执行 `compileJava compileTestJava processResources processTestResources --rerun-tasks`，7 项任务成功；九格图标栏、语音面板、资源包与校验器定向回归 **83/83**，5 个容器成功，无失败、跳过或中止。保留既有 Unsafe/API 与独立启动器注解依赖警告。
- 本轮未打包、未部署或重启实服；九格图标在客户端资源包中的最终显示、槽位对齐、语音面板实际宽度仍需进服并重新下载资源包确认。

### 1.10.43（移除 trace 命令，定向验证通过）

- 移除 `/muz debug trace` 执行分支、Tab 补全、参数用法提示及无用 import；不改其它 debug 子命令或 `/muz give debug`。内部追踪实现与日志收尾暂保留，不扩展为底层清理。
- 26.1.2 强制编译及资源处理 7 项任务实际执行成功，三类定向测试 10/10、4 个容器成功，无失败、跳过、中止。原日志测试要求命令提示显示日志路径，现按明确的命令删除需求改为禁止入口与提示复活，内部日志路径及收尾断言保留。
- 未跑全仓或其它目标测试，未打包部署；保留既有 Unsafe/API 与启动器注解依赖警告。

### 1.10.42（预览令牌与图片 CSP 修复，本地回归通过）

- 正式两份 HTML 的道具预览和全部 HUD/道具图标 fetch 显式传递 `X-MUZ-Token`，不依赖 `no-referrer` 页面不会发送的 Referer。保留严格 GET 来源授权，不增加查询参数令牌或关闭校验。
- CSP 仅将 `blob:` 加入 `img-src`，其余脚本、连接和框架限制不变；图标继续复用 Object URL 并在页面退出时释放。Java 兼容模板同样带令牌读取图片并释放 Object URL。
- 1.10.41 的后端专项未覆盖真实响应头组合；已确认旧页面请求缺 Token 且无来源头、旧 CSP 拒绝 blob 图片，不能将历史单测通过视为端到端可用。26.1.2 最终强制编译与资源处理 7 项任务实际执行成功；全量后端 889/889、130 个容器成功，最终浏览器及后端专项联合 52/52、5 个容器成功，均无失败、跳过或中止。
- 浏览器夹具从生产 `addSecurityHeaders` 导出响应头，受保护 GET 在 Node 夹具中严格要求 Token（不是完整 Java HTTP 服务）；确认请求无 Origin/Referer 但带 Token、道具 blob 图片 naturalWidth 非零及无非预期浏览器错误。首次真实头回归还发现 HUD 图片直接 src 的 403，已统一复用认证图片缓存并同步两份页面；未削弱鉴权。新增源码断言曾误用 Java 兼容模板检测正式页面缓存调用，已改为读取正式 HTML，不放宽断言。
- 保留既有 Unsafe/API、Gradle 和启动器注解依赖警告；本轮未打包发布、未部署、未构建其它目标，真实 CE 保存、资源包下发和客户端显示仍待单独验收。

### 1.10.41（后端边界专项验证通过，未发布）

- 道具预览 GET 使用有效页面令牌，或 HTTP 同源来源与 Host、监听端口精确匹配；无来源无令牌、显式错误令牌、其它本机端口和 HTTPS 来源不再由回环地址兜底放行。
- 道具箱持久化成功后的预览监听异常记录堆栈，不再阻断游戏内快照、选择和重绘。没有新增命令、配置键或游戏 HUD。
- `GadgetPreviewSnapshotService` 增加包内 `RuntimeAccess` 与读取执行器注入点；公开构造仍使用 Bukkit 主线程调度及公共异步池，测试可控制读取和发布队列的乱序。Origin 不接受路径、查询和片段，Referer 允许页面路径。
- 26.1.2 强制编译与资源处理共 7 项任务实际执行成功；三个专项测试类合计 50/50、4 个容器成功，无失败、跳过或中止。覆盖保存后旧错误回调、停止后已排回调、停止再启动错代、关闭后迟到读取及来源边界。包内 `GadgetBoxGuiService.notifySaved(UUID)` 实际注入异常并检查日志和持久化；GUI 快照、选择和重绘的续接仍由源码契约校验，不代表实服库存交互验证。旧页面断言改为允许只读道具预览与可编辑 HUD 并存，HTTP 测试头改用合法 ASCII 值，未弱化来源边界。未部署实服。

- 本轮未运行全仓与浏览器回归、未打包发布、未部署或重启实服；保留 Unsafe/API 及独立启动器注解依赖警告。专项结果仅覆盖上述后端边界，不代表真实 Bukkit 库存界面与客户端验收。

### 1.10.40（道具箱同源 Web 只读预览，26.1.2 自动化通过）

- 用户明确选择仅在 Web 预览既有游戏道具箱，不新增游戏屏幕 HUD，不恢复退役 Hotbar 字形资源或原版 sprite 覆盖。
- 预览按在线玩家选择，0～8 个非空槽位顺序及重复物品与游戏快照一致；第九格语音入口不计投掷物品。缺失配置沿用默认道具，显式空列表仍为空栏。
- 只读预览与 18 键 HUD 保存流程隔离，不将玩家道具数据写入 config.yml，不因预览图标缺失要求 CE 新增 provider。无法解析的复杂自定义模型明确显示缺图，不冒充真实客户端渲染。
- 26.1.2 强制编译与资源处理成功；定向回归 54/54、独立全量 JUnit 881/881、真实 Chromium 契约 2/2（含进程清理）通过，无跳过、中止或失败。新增测试覆盖空栏、1/3/8 件、重复顺序、空洞、缺图、不可变快照与损坏 YAML 不改写；存储源码断言仅更新为新的 raw.present 缺失路径分支。首轮两项失败源于 Paper 注册表夹具缺失，补齐夹具后通过，未修改生产逻辑迁就测试。
- 26.1.2 的 JAR、资源包与 CraftEngine bundle 强制构建及 SnakeYAML 重定位成功；三个归档 CRC、重复条目、禁止原版 Hotbar sprite、JAR 内正式页面与两张原版道具图标一致性检查通过。JAR SHA-256 为 `254127815d92814e2c2d32f21694082faf33a0a70580bd0cab883fb521e48093`。
- 本轮未构建其它目标、未部署或重启实服。后端异步旧读/停止迟到回调、保存通知异常及 HTTP 来源边界仍需专项测试；实服保存和客户端应用仍需单独确认。

### 1.10.39（根目录连续 HUD 校验修复，现场 ZIP 离线验证通过）

- 现场启动日志确认 MUZ 1.10.38；保存失败不是头像重叠导致。CE 生成包的 `pack.mcmeta` 只有 `min_format/max_format`，五个连续字体均在根目录且 CRC 正常；验证器却在读取字体前强制要求非空 overlays 声明。
- 连续资源允许根目录无 overlays 声明，后续 provider、ascent、PNG、CRC、重复路径与基础 bundle 校验仍严格执行；未声明目录及额外连续资源继续拒绝，不影响其它插件字体。未改 YAML 入口或现场配置。
- 独立 agent 分别实施校验器回归及只读追查重复告警，主流程收紧误伤基础字体的检查后实跑四类定向 56/56、5 个容器成功，无跳过、中止或失败。首轮 51/56 暴露新增检查误伤基础 muz_cards.json，修的是检查实现，没有弱化原测试。
- 旧 1.10.38 JAR 对真实 CE ZIP 复现同一错误；1.10.39 发布 JAR 按包内实际请求 81/78/1 完整验证通过。磁盘配置是回滚后的 75/78/1，严格拒绝其与生成 ZIP 的错代，不能把回滚配置等同于 ZIP 已恢复。
- paper-26.1.2 强制编译、JAR/资源包/CE bundle 构建及 SnakeYAML 重定位成功，三个归档 CRC 通过；JAR 已复制到 C:\PluginLibs，SHA-256 为 `12386735497e53a2856435f13a71a5046ef05d7f3122407f32aa1de873455221`。
- 头像告警由各桌及个人预览各自读取配置重复产生；本轮只定位原因，未实现去重，也未擅改玩家布局。未运行全仓测试、其他目标构建，未部署、重启或执行真实保存，客户端应用仍待确认。

### 1.10.38（桌内九格道具箱、私有气泡语音面板与三层 HUD，自动化通过）

- 新增桌内九格道具箱：八个玩家可配置槽位持久化到 `player-settings.yml`，第九格固定打开仅发起者可见的气泡语音 Display Entity 面板；缺失路径使用默认鸡蛋/水桶/番茄，显式空列表表示清空。普通 ActionBar 统一由 `ActionBarOverlayService` 承载。
- 退役三道具 Hotbar 运行期资源链：正式装配不再构造 `HotbarHudService`，删除无调用的 `HotbarDebugOverlayWriter` 与官方字体 ZIP 加载入口；构建期不再生成 Hotbar provider、字体或 PNG。`CraftEngineBundleExporter` 在 bundle 指纹提前返回前清理旧 Hotbar YAML、字体 PNG、原版 sprite 与旧 overlay 目录，同时保留桌内道具 item 资源。
- Debug Web 收敛为牌行、头像行、记牌器行三层，字段白名单从 22 个降为 18 个 `trick-hud.*` 键；三层连续 Y 事务、CE reload、实际 ZIP 校验和失败回滚语义保持不弱化。`HudResourceRequest` / `PackAssets` 的部分 Hotbar 字段仅作旧源码兼容，不参与生成或校验。
- `paper-26.2` 强制执行 `compileJava compileTestJava processResources processTestResources --rerun-tasks`，7 项任务全部实际执行成功。定向回归 184/184、15 个容器成功；独立全量 JUnit 873/873、128 个容器成功，均无失败、跳过或中止。
- `paper-1.21.11`、`paper-26.1.2`、`paper-26.2` 均干净执行 `clean shadowJar zipResourcePack zipCraftEngineBundle verifyRelocatedSnakeYaml`，每目标 10 项任务实际执行成功。九归档 CRC、重复条目、版本 `1.10.38`、作者 `linmumua`、资源格式 75/84/88、三种桌内道具资源及退役 Hotbar 禁止项均通过。
- 三目标 JAR SHA-256 依次为：`5e62727e1b4c3e3351308097a6761fc4209b6cd9917066132dcaf615f0c1d826`、`857ed544fe257b3a0eb17f8dbd28f00930f062d5b5635f0fd3b43b8b5c821cd4`、`0b55fb14a129d3477d2b8886d91502594d2a721d90ac45c74ba2b0e76d73a668`。
- 推送收口保留 GitHub 远程 README 的三张托管演示图，并将真实存在的 `/muz bot add|remove` 补入命令列表；同时沿用远程对旧 `docs/` 截图与说明文件的删除，不恢复已退役文档。
- **未验证（需进服人工确认）**：真实 CraftEngine 保存/重载、资源上传与客户端下载、三层 HUD 和私有语音面板的实际显示；家具行走及真实尺寸档位仍由独立阻塞项跟踪。本轮未部署或重启实服。

### 1.10.37（开局后对入座真人隐藏桌边动态，编译与单测通过，客户端待验）

- 需求：开局后（`phase != LOBBY`）坐在桌上的在线真人看不到桌子上方「桌边动态」浮空字，方便低头看牌；大厅阶段仍可见，旁观者与机器人任何阶段照常可见。用户明确纠正为「开局后看不到」而非「整局都隐藏」。
- 实现：`PhysicalTableManager` 新增 static 纯决策 `playDetailHiddenForSeatedPlayer(GamePhase, boolean)` 与按人可见性方法 `updatePlayDetailVisibility`；在 `refreshPlayDetail`（`refresh`/`tick` 每 2 秒）与 `syncViewer` 末尾统一重算，覆盖 `staticEntities`/显式 show 会无条件显示 playDetail 的问题。写法对齐既有 `updateSeatInfoVisibility`。未新增配置键、未改对局规则、未进 `DoudizhuPlugin`。
- 验证：`paper-26.1.2` 强制编译后，独立全量 JUnit 实跑 894/894、124 个容器全部成功，无失败/跳过/中止；其中 `PlayDetailVisibilityTest` 3/3 通过，版本读到 1.10.37。`paper-1.21.11`、`paper-26.1.2`、`paper-26.2` 均干净执行 `clean shadowJar zipResourcePack zipCraftEngineBundle verifyRelocatedSnakeYaml` 成功，分别 10 / 11 / 10 tasks executed；26.1.2 额外生成并嵌入 14 个官方字体依赖文件。
- **未验证（需进服人工确认）**：客户端上开局后坐桌玩家确实看不到桌边动态、旁观者仍可见、离桌/回 LOBBY 后恢复可见——按玩家 `hideEntity` 的客户端可见性纯逻辑测不了。本轮未部署或重启服务端、未复制 JAR 到 `C:\PluginLibs`。

### 1.10.36（右键提示与字体度量定向验证通过，实服漂移待确认）

- 2026-09-19 按要求完成26.1.2干净构建、SnakeYAML重定位检查及测试编译，13项任务实际执行；四类定向回归再次73/73通过，5个容器成功，无失败、跳过或中止。三个归档CRC、重复条目、插件元数据、两份HTML与JAR内页面一致、14个内嵌字体文件及禁止原版hotbar覆盖检查通过。
- 已替换 `E:\我的世界插件\斗地主\plugins\MUZ-1.10.36-paper-26.1.2.jar`，旧1.10.35保留为 `.jar.bak`，目录仅有一个可加载MUZ JAR；同时同步至 `C:\PluginLibs`。构建源、部署副本与库副本SHA-256均为 `844654c27a2bfb3966f3e9f3f9b8c8059655a6bbf20bab5adf2e6aa5e64719df`。未重启服务端、未修改现场配置或CE资源；运行版本切换、真实重载与客户端视觉仍待确认。本次未构建其他目标或执行全仓/Chromium测试；下方未打包部署记录属于此前验证阶段。

- 最终重新强制执行26.1.2编译与资源处理，8项任务实际执行成功；`HotbarFontMetricsTest`、`HotbarActionBarLayoutTest`、`HotbarHudDiagnosticsTest`、`DebugWebServerTest` 合计73/73通过，5个容器成功，无失败、跳过或中止。未执行正式Chromium、全仓测试、发布打包或部署；现有Unsafe/API和独立Launcher注解依赖警告保留。不代表真实客户端漂移已解决。
- `HotbarFontMetrics` 修复 bitmap 省略 height 时未使用默认8、字体选项组合不可测却被跳过的问题；未支持的 provider 与未知 filter 以未知结果穿透 reference，不再猜测缺字并采用后置宽度。任一选项组合不可测或组合间宽度分歧均沿用固定图标、正文限频转聊天的降级路径。
- 首轮73项中72项成功，失败的旧真实字体断言错误地要求混合文本一定可测：本地官方字体资源核对显示“玩家测试 Hello 123”普通/Unicode宽度为86/73，现改为必须拒绝不确定宽度，并增加“玩家测试”精确36px断言；这不是弱化测宽保证。最小反例证明了测宽缺陷，但尚无本次实服同步移动的客户端复现证据。

- 两份正式 HTML 与 Java 兼容模板的右键框不再显示 `card` 等内部图层标识，仅保留边界、指针与原有坐标信息，不隐藏牌行。
- 当前 profile 的牌高仅53、记牌器/Hotbar仅100%，缩放手柄只能选择这些资源档，不能连续改变客户端尺寸。真实尺寸扩展需调整构建 profile 并重建资源，不能用网页 CSS 假装生效；本轮尚未扩展档位。
- 现场2026-09-19 17:53启动日志已记录 MUZ 1.10.35，17:58:19停服；不能将本次仍漂移归咎于未更新。CE保护ZIP通过Java ZipFile中央目录可正常读取，Hotbar基础及offset-y=-140覆盖层几何自洽；无客户端资源包应用回执，亦未复现具体漂移方向，不宣称根因已解决。
- 指定 Vulcan 文件名为2.9.7.23但plugin.yml为2.9.7.22。补充截图已确认14:41:47–48触发Ground Spoof B（到5/5）、Flight A（6/30）与E（4/30），随后Vulcan以Unfair Advantage踢出，未显示封禁。指定JAR内置配置的groundspoof.b处罚门槛为5、Flight A/E为30，支持B触发处罚的判断，但不是截图服务器实际配置的替代证据；ghost-block消息末尾对应模板的ticks，不是检查编号。
- 2026-09-19只读对照：CE默认bench同样使用Shulker（east、peek=100），MUZ桌为四个Shulker（up、peek=33、scale=1）。bench的interaction_entity=true只增加交互伪实体，不改变CE碰撞AABB，不能凭MUZ桌缺少此项就判根因或直接补键。CE 26.8.2 API放置与玩家放置共用BukkitFurnitureManager.place碰撞初始化，尚无MUZ漏注册碰撞的证据；用户已确认正常对照家具就是default:bench，行走正常；用户随后补充：同款桌子直接手放也踢，peek改为0也踢。此为用户实测反馈，尚未取得该次生效配置与日志；说明MUZ专用生成流程不是复现的必要条件，peek=33也不能作为唯一原因。后续优先以bench完整hitboxes替换测试桌碰撞定义做受控对照，再逐项恢复数量、位置、方向与交互实体选项；不能直接认定其中某一项为根因。
- 已新增离线对照配置 `reference/table-bench-collision-probe.yml`，并复制到本地CE的 `resources/muz/configuration/table-bench-collision-probe.yml`；独立ID为 `muz:table_bench_collision_probe`，不进入正式构建、不覆盖 `muz:table_large`。静态YAML解析及结构对比确认仅名称/身份引用/hitboxes变化，完整hitboxes与bench一致（含两个seats），正式furniture.yml哈希未变。测试须只步行、不坐下，单个bench碰撞不覆盖整个桌模型；未执行CE重载、客户端资源包应用或实服行走验收，不能称为修复。
- 当前MUZ的CE分支只收集实体UUID，不改持久化状态；桌椅不进入自有Display传送逻辑。incompleteReason只检查HUD和座位文字，不直接检查CE家具根实体；不能因此断言所有清实体场景均不重建，因为文字丢失也可能触发恢复。截图中的Killed 226 entities是时间线索，不证明碰撞残留；默认配置的桌椅根Y经锚点补偿落在支撑面，模型position与碰撞原点不同也不单独构成错误。后续需要无清实体干扰的同款家具直接CE放置/MUZ生成对照及实际玩家脚下Y、碰撞状态；本轮未改插件行为、Vulcan或现场配置，未重载或执行实服A/B。

### 1.10.35（Hotbar 固定屏幕定位，定向回归与产物核验通过）

- 三道具漂移来自 ActionBar 合成后的净宽度等于对局正文宽度，不是头像绑定。修复方向为图标与正文独立居中，图标总 advance 固定；`offset-x` 只移动图标，不移动正文。
- 26.1.2 官方客户端 SHA-1 `4e618f09a0c649dde3fdf829df443ce0b8831e65` 已核验，ActionBar 起点为 `floor(screenWidth/2)-floor(textWidth/2)`；Web Hotbar 同步该取整，不修改其它 HUD 行。H=69、屏宽640时图标起点286，正文宽100/101时均从270起。
- 字体度量仅服务 Hotbar，使用构建期核验的26.1.2原版字体资源与实际 CE ZIP；所有加载异步，主线程消费不可变快照。未知 provider、字体条件分歧或小数 advance 不猜测，图标固定、正文转聊天且每玩家最多10秒一次；客户端私有字体覆盖不在精确一致保证内。
- 新增 `HotbarFontMetrics.load(InputStream, Path, int)` / `measure(Component)`，只在异步资源阶段解析原版字体归档和实际 CE ZIP；`HotbarActionBarLayout` 负责固定 advance 的正文补偿，`HudOverlayRuntimeState` 随完整请求原子发布字体快照。构建任务 `generateHotbarVanillaFonts` 仅为 `paper-26.1.2` 下载并校验官方 SHA-1，将14个字体依赖文件嵌入 `hotbar-font/vanilla-26.1.2.zip`。
- 最终强制编译8项任务全部实际执行；11类定向回归184/184、12个容器全部成功，无失败、跳过或中止，包含真实官方字体归档加载。首轮失败中，overlay未ready发送字形是实现错误，已恢复普通正文ActionBar；`markVerified`测试只更新携带字体快照后的签名，并加强校验后加载顺序断言。保留既有Unsafe/API与独立Launcher注解依赖警告，未运行全仓测试、正式Chromium或其他目标测试。
- `paper-26.1.2` 干净执行 `clean shadowJar zipResourcePack zipCraftEngineBundle verifyRelocatedSnakeYaml`，11项任务实际执行成功。三个归档CRC、无重复条目、元数据、嵌入14个字体文件、两份HTML及JAR内HTML一致、禁止原版hotbar sprite覆盖均通过。JAR已复制至 `C:\PluginLibs\MUZ-1.10.35-paper-26.1.2.jar`，源副本SHA-256均为 `27ddd46a94df78a3eda908d07667952001b6c0095e89541f4ffe16b39b36fe9f`；CraftEngine ZIP为 `4c4fe0027d70750822454facf64ba2ae71ec9aa0cb8a63c00c4e9f1891598412`，资源包ZIP为 `8e1ce6849b4adbde0fe665069cea15d473671b18474e0bb67e85fb47df8e711e`。
- 未部署或重启实服，未执行真实CraftEngine保存、资源上传、客户端下载或游戏内视觉验收。字体精确路径只面向26.1.2；其它服务器版本、未知字体与条件分歧降级，客户端私有包及混版本连接不在保证范围。未新增玩家文案，保留既有文本配置技术债。

### 1.10.34（Hotbar 根字体合并校验，自动化通过，实服待验）

- 现场 `minecraft:muz_hotbar` 的十进制码位 `61191` 即 `U+EF07`，属于当前 100% 鸡蛋连续字形。1.10.33 仅覆盖独立连续字体根路径，漏掉基础与连续 Hotbar provider 合并在同一个根字体 JSON 的情况；新增夹具已复现完全相同的拒绝错误。
- 连续校验先选择根/overlay 字体路径；共享根字体按基础 YAML 加本次布局的精确声明一起校验，再仅统计连续码位。基础字形、当前请求的 file/height/ascent、重复/未知/缺失码位及根/overlay 双份连续映射仍严格拒绝；不跳过整份 Hotbar 字体，不修改码位或渲染几何。
- 新增根字体合并的正偏移、负偏移与下界测试，以及基础/连续 provider 篡改、缺失、重复、未知和双路径拒绝测试。旧实现实跑 31/32，唯一失败精确复现 `61191`；修复后强制编译 7 项任务全部执行，四类资源定向回归 100/100、5/5 容器成功，无失败、跳过或中止。未运行全仓测试及其它目标 JUnit。
- 三目标各执行 10 项干净发布任务；九归档 CRC、重复条目、版本/作者、资源格式及原版 hotbar 禁止项通过，SnakeYAML 重定位通过。三份 JAR 已同步至 `C:\PluginLibs` 并逐一核对副本 SHA-256：1.21.11 为 `6fd483e8a13e5b5a80c52c3986c1729d63a7f826599856e81abc552c02769ea5`，26.1.2 为 `38627d5e65fa591cb585ec6fba9f31f50b12237c51899f4e7ae8a23e29ee1a63`，26.2 为 `d96e736f7f47e1948044b00b746532de904a7f787551e8133ff10292d7c6d4b0`。2026-09-19 已按要求将 26.1.2 目标 JAR 部署至 `E:\我的世界插件\斗地主\plugins`，部署副本哈希与上述值一致；旧 `MUZ-1.10.33-paper-26.1.2.jar` 改名为 `.jar.bak`，插件目录仅保留一个可加载 MUZ JAR。未重启服务端、未修改现场配置，运行中版本尚未确认切换；真实保存与客户端验收仍待确认。

### 1.10.33（CE HUD 连续资源校验修复，自动化验证通过，实服待验）

- 修复 Debug Web 保存时将根目录 `assets/minecraft/font/muz_counter_continuous.json` 误判为 profile 未生成分页的问题：根 bundle 校验只豁免当前请求明确生成的连续字体，连续资源再按 `pack.mcmeta` 声明的 overlay 前缀统一校验。
- 连续字体与动态 PNG 支持根目录或已声明 overlay 目录二选一；同一逻辑资源重复出现、缺失、未声明前缀或 profile 外分页仍拒绝。
- 连续 bot 图像使用独立 CraftEngine ID：`muz:trick_hud_continuous_bot_avatar`、`muz:trick_hud_continuous_bot_avatar_landlord`、`muz:trick_hud_continuous_bot_avatar_farmer`，不再与基础 `bot_avatar.yml` 重复；font、char、贴图和几何保持不变。
- 已强制编译并完成 HUD 资源契约定向回归：98/98 测试通过，5/5 容器成功，无失败、跳过或中止。
- 已完成 `paper-1.21.11`、`paper-26.1.2`、`paper-26.2` 三目标 `clean shadowJar zipResourcePack zipCraftEngineBundle verifyRelocatedSnakeYaml` 构建；三份 JAR 已同步至 `C:\PluginLibs`，源文件与副本 SHA-256 一致。JAR 哈希依次为 `37be0de575a7c3938c1341fe77b34f5d688cd062c873173112ce4e750f83c034`、`421ed846dd4dffbcc583edd1563f069ad6956891dbb746d14e311a9c2294eea9`、`fd8122dd4eabee8438f6802a091d4b01b011237e17156d81cd47bbceaab52035`；对应资源包为 `3c2a24e7c4b6d2a27972a48648199146821d377bc7f59c47e548717b41deaa25`、`8e1ce6849b4adbde0fe665069cea15d473671b18474e0bb67e85fb47df8e711e`、`b17bca7e2c943943a862fac49f041f9f53a24f446a56bb0e0a1962ef7f529892`；对应 CraftEngine bundle 为 `4198557aa901503141b9afd4fbfa6f12fab7b20f5c31c696ecbb42237e5f934f`、`1f360aaa2e025ed6dc7fef78922ff00aa46b55ac7acb3a301330ed55a025ed78`、`352d0b68a0de38ac696447653dfed2647cbe2fd67846bedf172bc5b9a57fa11f`。
- 本地构建、归档元数据、无重复条目、无原版 hotbar 覆盖及 SnakeYAML 重定位已核对通过；真实 CraftEngine reload、客户端资源包下载和游戏内视觉验收仍未完成。

### 1.10.32（CE家具区块卸载修复，验证中）

- CE 桌面和椅子的根实体及乘客不再进入 MUZ 的 `protectEntityTree` / `setPersistent(false)` 路径，只登记实体树 UUID；fallback Display/Interaction 仍保持原有非持久化保护。
- 根因是 CE 26.8.2 对 `persistent=false` 家具跳过区块卸载失效清理，旧 `byMetaEntityId` / `byInteractableEntityId` 映射在玩家返回区块后继续指向失效 `BukkitEntity`，右键最终在 `position()` 触发 NPE。
- 1.10.21 与 1.10.31 的家具 YAML、碰撞参数和 CE 桥接实际对照无差异；飞行告警仍需按 CE Shulker 碰撞体与 Vulcan 做实服 A/B，不在本次改动中调整玩家移动逻辑。
- 新增 `FurniturePersistenceBoundaryTest` 锁定 CE 分支只收集 UUID、fallback 分支继续保护；真实区块返回、`/ce reload all` 和游戏内验收仍未完成。
- 2026-09-18 重新执行三个目标的 `clean shadowJar zipResourcePack zipCraftEngineBundle verifyRelocatedSnakeYaml`，均 BUILD SUCCESSFUL；三份最新版 JAR 已覆盖同步至 `C:\PluginLibs`，源文件与副本 SHA-256 一致：`paper-1.21.11` 为 `d3df514e9dfe1cc699534e37e0638d7ed1b862b1f5d7859d31c9d732bcfcb5fb`，`paper-26.1.2` 为 `5d91689b985f59474fee7c676a7b78f412f1a7c068d23435a6c722b13ac3f884`，`paper-26.2` 为 `d5a1a6b1b3abff496a1a97f11af5fbba47f08da68e685f792559bad8f52d7d29`。
- 本次仅完成本地构建、JAR 同步与哈希核对；真实 CraftEngine reload、客户端资源包下载和游戏内视觉验收仍未完成。

### 1.10.31（三道具Hotbar客户端坐标与运行期诊断，验证中）

- 三道具 Hotbar 基础 ascent 从 `-100` 校准为 `-43`，构建期 `hotbar_hud.yml`、运行期 overlay、`PackAssets` 与 `HotbarDebugOverlayWriter` 必须同源；当前 100% 连续 `offset-y` 下界同步为 `-299`。
- Debug Web 两份页面与 Java 内联兼容模板按服务端 `baseAscent`/`offset-y` 计算 Hotbar 逻辑位置，不能把旧的 `-100` 屏外资源预览成屏内；三图标和选中框仍必须消费同一 geometry。独立打开前端原型且后端 API 不可用时，逻辑屏幕边界也必须先按顶栏以下 viewport 自适应到最大尺寸。
- `HotbarHudService` 对关闭配置、CraftEngine offset 不可用、非 PLAYING、机器人/离线座位及 overlay 未 ready 增加 10 秒限频中文诊断；不改变原有阶段门、普通 ActionBar 降级或资源 ready 语义。
- 本地 1.21.11 官方客户端字节码已确认 ActionBar/BitmapProvider 坐标换算；Paper 26.2 exact 客户端包未在本机取得，仍需实服重载、客户端重下资源包和游戏内确认。

### 1.10.30（CraftEngine现代pack元数据与网页四边界，本地验收通过）

- CraftEngine 新生成的 `pack.mcmeta` 可能只包含 `pack.min_format` / `pack.max_format`（如 `[88,0]`），不再携带旧 `pack_format`；`HudResourcePackVerifier` 现同时兼容旧单值/数组与现代二元范围格式，并只接受覆盖项目目标格式 75/84/88 的范围。
- Debug Web 两份页面在 640×360 Minecraft 逻辑舞台内增加四边可见的金色边框与尺寸标签，边界 `pointer-events:none`，不拦截拖动、缩放、滚轮或平移；十字线仍作为辅助参考。
- 新增现代 pack 元数据校验、逻辑屏幕边界静态契约与真实 Chromium 四边框可见性回归；Java 内联兼容模板同步保留边框样式。当前版本正在重新强制编译与定向验收，旧 1.10.29 产物保留。

### 1.10.29（网页居中与进程清理补充修复，本地验收通过）

- 发现 1.10.28 的视觉包围盒不等于游戏内净前进量；负头像间距若使用视觉宽度居中，会自动生成错误的水平偏移。网页三行现在分别用服务端的 card/avatar/counter advance 计算共同容器与行起点，再加真实内容包络；包络仅用于边界约束，不改变游戏内居中语义。头像包络以首个实际内容边界起算，不能把槽原点到头像之间的透明留白纳入边界；新增槽原点为 -5 而真实内容仍在屏内时不改写偏移的 Chromium 回归。
- 新增真实 Chromium 回归：描边头像 scale=6、`avatar-gap=-360`、X 偏移为零时，内容恰好覆盖 `0..640`，网页不得改写为 +590px。缩放夹具显式选择头像层，校验手柄可见且实际命中，并保留 pointerdown/拖动/DOM 稳定性断言。
- 浏览器测试在超时或中断时只清理自身 Node/Chromium 进程树，启动失败也纳入统一收尾。新增真实挂起 Node 父子进程的限时和清理回归，不以调用次数代替进程退出证明。脚本注入前统一 CRLF/LF：原目录首轮 50/51 暴露 Git 换行转换造成固定 LF 注入点误报，现修正读取归一化，保持注入点存在性断言。
- 合并前独立快照定向 51/51、全量 859/859；2026-09-17 合并后的原目录再次强制执行 `compileJava compileTestJava processResources processTestResources --rerun-tasks`，7 tasks executed；最终定向含正式 Chromium 和挂起父子进程回归 51/51、3 容器成功，全量 859/859、119 容器成功，均无失败、跳过或中止。另两个目标未运行 JUnit，保留既有 Unsafe/API 与独立 launcher 注解依赖警告。
- 三目标均串行执行 `clean shadowJar zipResourcePack zipCraftEngineBundle verifyRelocatedSnakeYaml`，每目标 10 tasks executed。九归档 CRC/重复条目、版本/作者/API/字节码 65/69/69、资源格式 75/84/88、各 429 个内嵌与独立 bundle 文件字节一致、125 个 OGG Vorbis 标识、22 张 counter PNG 尺寸与锚点、四张 hotbar PNG 尺寸与 alpha、正式 HTML/背景及禁止原版 sprite/旧九槽覆盖均通过。审计结果位于 `build/junit-runner/release-129-audit.json`。
- 三个 JAR 已复制至 `C:\PluginLibs`，按 1.21.11/26.1.2/26.2 的源与副本 SHA-256 分别为 `440a83c5686fd6094bd3504d65626a27e01ab80d30fd1d5e1682d7305ca3bcbc`、`f37cdeb127b00363b7326a83794c0c54fe661577c57fe593aabe26edc82c05ba`、`de597466893206212f6c31e4ee253b552669bc465a3fb2154423566608e48569`。保留既有 1.10.28 文件，没有原地替换旧发布构建。
- 未部署测试服，未执行 CE 真实重载、资源上传、客户端下载或游戏内视觉验收。

### 1.10.28（网页屏幕边界，本地验收通过）

- 四层 HUD 包含鸡蛋、水桶、番茄及选中框，按真实内容边界限制在网页逻辑屏幕内；GUI 倍率和平移不得将整个逻辑屏幕推出浏览器 viewport。越界整数位置仅在页面修正并标记未保存，不自动写盘；非法小数输入不得被边界修正偷偷取整，保存仍须拒绝。
- 2026-09-17 专项审查确认并修复真实缺陷：负 `avatar-gap` 现在按三头像实际左右包络计算并平移子节点，极端负间距不再漏掉左侧边界；`card-step`、`avatar-gap`、`counter.gap` 的小数保留原值交给后端拒绝，不再被 `clampLayoutValue` 静默取整；resize 的 pointermove 期间只更新现有图层样式，松手后才重建 DOM；补齐 `#screen` 的 `.screen` 类，恢复缩放句柄 CSS 命中；Java 浏览器子进程改为先限时等待、超时强制结束后再读日志，避免输出读取绕过超时。两份 HTML 已再次字节同步。
- 最终强制编译 `compileJava compileTestJava processResources processTestResources --rerun-tasks` 成功，7 tasks executed。`DebugWebServerTest` 与正式 `DebugWebBrowserContract` 合计 50/50、3 个容器全部成功；正式 Chromium 单测 1/1 通过。`paper-26.2` 独立全量为 859/859、119 个容器成功，无失败、跳过或中止；另两个目标未运行 JUnit。
- 三目标均重新执行 `clean shadowJar zipResourcePack zipCraftEngineBundle verifyRelocatedSnakeYaml`，每目标 10 tasks executed。九个归档 CRC/重复条目、版本 1.10.28、作者 linmumua、API/字节码 65/69/69、资源包格式 75/84/88、JAR 内 HTML/背景与源文件一致、各 429 个 bundle 文件内嵌/独立一致、125 个 OGG Vorbis、三道具与选中框 20×22 及 alpha、禁止原版 hotbar sprite/旧九槽均已核对通过。
- 三个 JAR 已安全复制至 `C:\PluginLibs`，源与副本 SHA-256（按 `paper-1.21.11` / `paper-26.1.2` / `paper-26.2`）分别为 `8a2771ae21f2407288e88b1aba895f09033e18ae4f734bef1155a40dde423297`、`5b6a3bd6175d5ae6a8eeac3358e321bd2ae92e08de409d40957feab1adb9415b`、`9eb6dd77acdc2cf1107459af517efa78d339a5e3e9af85f2bb9fdb8ead8caf07`。
- 本轮未部署或重启测试服，未执行真实 CraftEngine reload、资源上传、客户端下载或游戏内视觉验收；浏览器受控 fixture 与 ZIP 校验不代表上述实服环节已完成。

### 1.10.27（背景音乐防重复核验）

- 同局重复开局音乐请求幂等，轮播消费时递增 epoch；已执行回调不能再次切曲或取消新任务，保持五首背景音乐先停后播。测试模拟活跃曲目并记录有序停止/播放调用，不把调用次数等同于客户端实际无叠音。
- `paper-26.2` 干净强制编译、打包及 SnakeYAML 重定位校验通过，10 tasks executed；独立 JUnit 五类定向 30/30（音乐 12 项），6 个容器全部成功，无失败、跳过或中止。保留既有 Unsafe/API 与独立 launcher 注解依赖警告。
- JAR CRC、无重复条目、版本/作者、音乐 class 与编译输出一致均通过；已复制 `C:\PluginLibs\MUZ-1.10.27-paper-26.2.jar`，源与副本 SHA-256 均为 `0cde09fb03b48c2fa8e01ca89a5cc7b140e2e8f466c999ec0a2883f2a3c7db4a`。本轮未执行全仓测试、其他目标构建、独立 ZIP 打包或实服听感验收。

### 1.10.25（四层 HUD 连续 Y 覆盖层，自动化与产物核验通过）

- 22 个 HUD 字段保持不增减；card-height=53、avatar scale=4/6、counter scale=100、hotbar scale=100 仍按 profile 离散白名单校验。Trick 的 `offset-down`、`avatar-offset-down`、`counter.offset-down` 连续允许 `-128..512`，hotbar 当前 100% 的 `offset-y` 连续允许 `-356..512`；原 profile Y 档 `0/50` 与 `0/122` 保留为基础 bundle 兼容，不扩展全 Y 档。
- `HudResourceRequest(cardOffsetDown, avatarOffsetDown, counterOffsetDown, hotbarOffsetY, hotbarScale)` 为不可变完整请求；`HudOverlayLayout` 只提供覆盖层 metadata 与字形布局。`HudOverlayWriter` 写出 `trick_hud_continuous.yml`、`hotbar_debug.yml` 与必要 padding PNG；Trick 使用独立 `baseFont_continuous` 与 base tier 0 char，不覆盖正式 font/char，hotbar 复用既有 debug char/font，动态 PNG 位于 `resourcepack/assets/muz/textures/font/continuous/`。
- 覆盖层使用 `ascent=baseAscent-rawY`；向上导致 ascent 超过原 height 时只在底部补透明像素并同比增加 provider height，保持 PNG 比例、可见尺寸与 advance 不变；bot 原图按通用 16px、farmer/landlord 18px 处理，并按 gcd 整数规则对齐 height 10/11，保持 PNG 比率。运行期复用构建期源 PNG，不重复绘图。
- 保存链路为配置与四层资源异步事务 → 主线程真实 CE reload → 异步 generate 与实际 ZIP 字体/PNG 验证 → 主线程 `markVerified`/apply/snapshot；任一步失败都回滚配置与自有资源并清 ready，不能恢复先前 ready（CraftEngine 可能已部分 reload）。Hotbar 资源选择独立于 Debug Web 是否 enabled/running，只由完整 `HudResourceRequest` 的 verified ready 决定；Legacy offset-only Hotbar 写入口显式拒绝，旧 verifier 仅离线 legacy 校验、不能作为 ready 凭据。`HudOverlayRuntimeState` 保存完整请求 ready，连续非 profile Y 未 ready 时正式 HUD 隐藏并告警，不 nearest；精确旧 bundle 档位可兼容。`HudResourceRecoveryService` 独立负责启动、`/muz reload` 与 CraftEngine enable 恢复，不受 Debug Web 开关影响。
- 回归夹具覆盖完整资源请求、失败清 ready、超时租约与关闭收尾；补偿异步完成，失活后不排新的运行态应用；测试锁定统一四层原子写与完整回滚；浏览器回归区分非法坐标的预期 400 与真实脚本/资源错误，相关回归已完成统一实跑。
- 保存成功后继续编辑会立即恢复“未保存”提示并清除旧的成功/错误状态；重新读取配置会丢弃尚未提交的页面值。
- 强制编译 `compileJava compileTestJava processResources processTestResources --rerun-tasks` 已成功（7 tasks executed）；`paper-26.2` 定向回归 90/90、6 个容器成功（含正式 Chromium BrowserContract），全量回归 847/847、118 个容器全部成功，均无失败、跳过或中止。保留既有 Unsafe/API 弃用警告；另两个目标仅完成编译与产物核验，未运行 JUnit。
- 三目标均完成 `clean shadowJar zipResourcePack zipCraftEngineBundle verifyRelocatedSnakeYaml`，每目标 10 tasks executed。九个归档 CRC/无重复条目、插件版本 1.10.25/API/作者 linmumua、字节码 65/69/69、pack_format 75/84/88、125 个 OGG Vorbis、22 张 counter PNG 尺寸与 alpha=1 锚点、4 张 hotbar PNG 20×22、禁止原版 sprite/旧九槽、内嵌与独立 bundle 字节一致性、JAR 内 HTML/背景与最终源文件一致性均已通过；审计日志为 `build/junit-runner/continuous-release-audit.log`。
- 三个 JAR 已复制到 `C:\PluginLibs`，源文件与副本 SHA-256（按 `paper-1.21.11` / `paper-26.1.2` / `paper-26.2`）分别为 `43e243cd9b23c95ff81d20773601201426cda6cf93d24a3642b770dd4dfa3c57`、`1f7e129ce41e0cca57b77d587a96a0ae8394f1cbf1795a0a6fe5bdf7ee6e5f99`、`e2e8587935333275e199e0460ecee301e5bcb5e500ae1a4583d78ffb1ad7738a`。
- 仍未完成真实 CraftEngine reload、资源上传、客户端重新下载与游戏内视觉人工确认；网页/ZIP fixture 不代表 CE 联调。Web HUD 配置锁只覆盖 Web 自身快照边界，非 Web 配置入口仍仅有有限并发冲突检测，不宣称全局配置事务。

### 1.10.24（网页预览与自由拖动，自动化验证通过）

- 两份 `debug-hud-preview.html` 同步修复 GUI 倍率：默认 3 档适配窗口，2/4 档按相对比例缩放；拖动与视图平移统一除以最终 CSS 倍率，不修改服务端 geometry 或资源包字形尺寸。
- `/api/preview-background` 只提供内置 `debug-world-background.png`，不接受任意路径，使用 `no-store` 避免换图后缓存旧内容。图片来自用户提供的实服截图无 HUD 区域裁切，仅作为网页背景，不进入 CE bundle；模糊层与清晰 HUD 分离。
- 三道具通过 geometry texture 匹配真实 manifest PNG；头像字形白名单按 `PackAssets.AVATAR_PIXEL_SCALE_TIERS` 覆盖 4/6 档。`pixel_*` 是字体像素遮罩而非完整头像，网页使用 bundle 内的 `bot_avatar_farmer.png` / `bot_avatar_landlord.png` 完整示例头像，按服务端槽位几何显示，不冒充在线玩家皮肤。J/Q/K/A 牌类须映射到 jack/queen/king/ace 文件名，避免预览牌行空洞。
- 用户本轮自由拖动要求覆盖历史默认吸附开启约定，现默认关闭吸附。pointerup 提交末帧；指针 ID、丢失捕获、保存 busy 状态须正确门控。网页的自由预览偏移与合法配置值分离：非法离散纵向档位明确阻止保存，不静默吸回或伪报成功；从表单选择合法值后须清除同键预览覆盖。三道具纵向连续范围不受此离散限制。
- 本轮重新强制执行 26.2 `compileJava compileTestJava processResources processTestResources --rerun-tasks`。最终定向 73/73（含正式 HTML 的 Chromium/真实 PNG 浏览器契约）及全量 806/806、114 个容器均通过，无跳过、中止或失败；不是沿用历史结果。浏览器覆盖三道具真实 20×22 图片、四层拖动末帧、指针门控、GUI 倍率、非法离散 Y 拒绝保存、合法 Hotbar 连续 Y、保存成功清除旧提示及 320/375px 防溢出。测试保存接口为受控 fixture，不代表 CE 真实应用。
- 三目标完成干净 `clean shadowJar zipResourcePack zipCraftEngineBundle verifyRelocatedSnakeYaml`，保存提示最后修正后再次更新 JAR；九个归档 CRC、版本、禁止原版 hotbar 覆盖与 JAR 内最终 HTML/背景字节一致性已校验。三个 JAR 已复制至 `C:\PluginLibs`，副本 SHA-256（1.21.11/26.1.2/26.2）为 `8be2e2057d6bcbefe6eec0982bbdb7f4c4c9b6177d476138ab81bfc77b697998`、`9455898de115bdfa05f568404a76d72e43078d0b63c0aa27ad1c29ab6da05a22`、`8e7ff1e5632b5bbc51e65a64ca79337f459f4e7b07a943a80679ad1abfce5365`。其余两目标未跑 JUnit，保留既有 Unsafe/API 弃用及独立 launcher 注解类警告；未部署测试服，未执行 CE 重载或客户端验收。

### 1.10.23（逐批发牌与明牌窗口，客户端验收待确认）

- 开局由专用协调器管理 `DEALING → REVEALING → BIDDING`：按固定座位顺序每人三张一批，末批两张；发牌保持洗牌顺序，使用最终 17 槽布局避免手牌随着发牌左右跳动。
- 所有手牌发完后，每张牌绕自身竖直中心轴翻转完整 360°；180° 时只排序一次，继续转到 360°。开局旋转与普通悬停/选中的稳定朝向路径隔离，不绕桌公转。
- 翻转完成后提供默认 60 tick 自愿明牌窗口；真人各自公开手牌，任意一人明牌使整局最多乘 2，多人不叠乘，机器人默认不明牌。公共明牌倍率与炸弹倍数分开存储，实时显示和结算必须一致。
- `RoundOpeningCoordinator` 驱动时间线，`RoundOpeningSettings` 读取 `round-opening` 不可变单局快照，`HandDealPresentation` 保存渲染槽位；配置沿用 `MuzYamlConfig` / SnakeYAML。批次起点间隔默认 4 tick（3..200），翻转默认 20 tick（8..200 偶数），明牌默认 60 tick（1..1200）；非法值须在清理开局状态前拒绝。重发、重置、离桌、关桌及停服必须使旧开局回调失效，重新发牌必须清空旧底牌，只有明牌窗口结束后才能启动叫分。
- 明牌资格统一限制在线真人，公共倍率同时传递至 `TableStatusViews` 与 `RoundSettlementView`，保证实时状态和结算摘要显示其来源；新窗口提示由 `round-opening.messages` 配置，旧倍率视图标签仍沿用周边硬编码，不发起全量文本外置。
- 开局和普通手牌的牌面、私有点数标签出生即隐藏，再按牌主/明牌资格显式放行；开局只在槽位牌内容变化时更新 ItemStack，转入叫分时接管现有牌面实体并补齐正常交互组件，实体缺失才回退重建并记录异常。
- 2026-09-16 最终三目标均干净执行 `clean shadowJar zipResourcePack zipCraftEngineBundle verifyRelocatedSnakeYaml`；26.2 额外强制执行 `compileJava compileTestJava --rerun-tasks`。定向首轮的夹具/时间点断言问题已修复，定向 45/45 通过；最终增加严格整数配置测试后，全量独立 JUnit 为 806/806、114 个容器全部通过，无跳过、中止或失败。其余两目标只验证编译与产物，未运行其 JUnit。
- 九个归档 CRC、版本、字节码 65/69/69、资源包格式 75/84/88、125 个 OGG、三道具及选中框 PNG、内嵌/独立 bundle 字节一致性和禁止原版 hotbar 覆盖均已核验。三个 JAR 已复制到 `C:\PluginLibs`，副本 SHA-256 依次为 `33109450141baaee2384c041f9e8232e66a92dcb021c5292d1a5e51e425aa4af`、`0bea7f51317b584a2d1c4cd3fa9b8cd31699504309345c7a2519c866e55d2e1e`、`05903d3466f9ccb9ba4371332aa1b21634e9ecc8c3f0c6b8bdd5faff71ab5a45`。
- 未部署/重启测试服，未执行客户端资源包重下和多人视觉验收；需要实服确认从左到右、360°翻转流畅度、中点排序和明牌按钮点击。测试夹具仍沿用仓库已有的 Unsafe/反射构造方式，存在弃用警告；未借此扩展为全仓测试工具迁移。

### 1.10.22（三道具 HUD 与互动，验证中）

- `TableGadgetEffectService.clearAll()` / `reset()` 可恢复清理；互动暂停和配置禁用不调用永久 `shutdown()`。水幕和头顶水桶为两个 Display，统一计数、回滚与清理，模型由构建期资源提供。
- 修复覆盖层字面换行、测试静态导入及 Web 循环资源路由的过时计数断言后，已再次强制执行 `paper-26.2 compileJava compileTestJava --rerun-tasks`；独立全量 JUnit 最终 773/773 通过，108 个容器全部成功，无跳过、中止或失败。首轮 771 项的 24 个失败已消除。其余两目标只验证编译与产物，未运行其 JUnit。
- 覆盖层必须输出真实换行，而非字面量 `\\n`；测试保留换行和 YAML 根节点约束。当前构建生成 212 条 image 声明，其中三图标及选中框占 4 条。
- 三目标 JAR、资源包及 CraftEngine bundle 已构建并通过 SnakeYAML 重定位校验；九个归档 CRC、目标格式 75/84/88、三图标及选中框 20×22、125 个 OGG、内嵌/独立 bundle 字节一致性及禁止旧九槽/原版 sprite 覆盖均通过。三个 JAR 已复制到 `C:\PluginLibs`，副本 SHA-256 分别为 `4978c8b6bba651c086a91104dbdf4fbd96b1e5731a6f6ed368dd3c0c49931f89`、`88cc850c8ae090d91e0eda92b8aba9fe677491c81f3040022e6c482a510fef1c`、`be4d6aa73b37ca0086bfb65e2f3420f83153bd28b8255f830472793d82e06a34`。
- Chromium 兼容模板 fixture 已验证四张同源 PNG、滚轮 0→1→2→0、普通区域不拦滚动、CSS 30px 对应 20 MC 像素、保存成功清 dirty/模拟失败保留 dirty；这是 mock 后端浏览器验证，不代表正式内嵌首页或测试服联调。服务端部署、CE 真实重载、客户端下载和多人视觉/交互仍未确认。

### 1.10.21（记牌器上下两行标签与数量）

- 记牌器继续保持固定 15 格与三层 glyph 契约：上方显示牌类标签，下方显示累计已出数量；小王、大王标签改为单字“小”“大”，避免牌类标签拥挤。
- 保持现有 22 个 glyph、21×12/21×8 资源几何、码位、ascent、advance 与 Debug Web 同源真实 PNG 预览；构建期仍使用固定整数像素字模，不依赖系统字体。
- 三目标 `paper-1.21.11`、`paper-26.1.2`、`paper-26.2` 的 JAR、资源包、CraftEngine bundle 与 SnakeYAML 重定位校验均成功；`paper-26.2` 定向回归 109/109 通过，无跳过、失败或中止。三个 JAR 已复制至 `C:\PluginLibs`，副本哈希分别为 `c15476ee62429fccfca942e373d1234a488aa02c47a20f1eb763e7431c4964a0`、`5862b75b3a4ab8821cb7631f1ed14206484499725e7e5195e5d12d47a8efc9b7`、`00687005dee478c38aa8dfc55d7755757580d2439e78f5d7fc6ea82a352fac7b`。
- CraftEngine 重载、客户端下载和游戏内视觉仍需单独人工确认。

### 1.10.20（方正像素记牌器与同源 Web 预览）

- 记牌器 label/digit 全部改为固定整数像素字模：普通牌类 7×9、10 为 11×9、王牌为 9×9、数量数字为 5×7；移除记牌器旧矢量笔画路径，保留原有字体、码位、PNG 路径、ascent、height、advance 与三层叠加契约。
- Debug Web 的记牌器 label/frame/digit 改为加载 manifest 白名单中的真实 PNG，与游戏内消费同一套资源；前端继续使用 `image-rendering: pixelated`，不再用浏览器系统字体模拟字形。
- `paper-26.2` 强制编译与定向回归实跑 102/102，通过无跳过、无失败容器；`shadowJar`、资源包、CraftEngine bundle 与 SnakeYAML 重定位校验均成功。JAR 已复制到 `C:\PluginLibs\MUZ-1.10.20-paper-26.2.jar`，源文件与副本 SHA-256 均为 `ea3672720f69d34f14ff27f0f67bf52909ced3b70b4b427777381547f0994e54`。现场 CraftEngine 重载、客户端下载和游戏内视觉仍需人工确认。

### 1.10.19（清晰记牌器预览与像素字形）

- 构建期记牌器数字改用固定 5×7 bitmap glyph，关闭抗锯齿并保持原有 font、码位、PNG 路径、ascent、height、advance 与三层叠加契约；运行期不改为普通文本字体。
- Debug Web 的 label/digit 使用服务端 counter geometry 定位的浏览器矢量文字，frame 仍加载白名单真实 PNG；GUI 缩放只改变网页 CSS 显示比例，不代表客户端 GUI Scale 或新的 Minecraft 字体档位。
- 预览移除牌行双重 0.9 缩放，头像、记牌器和 Hotbar 缺失资源档位改为显式错误而非旧几何 fallback；源码契约与定向编译已检查，CraftEngine 现场重载、客户端下载和游戏内视觉仍待人工确认。

### 1.10.18（紧凑记牌器重做）

- 记牌器标签/数字字身按高度限制宽度后居中，不横向撑满 cell；小/大王分别使用独立竖钩点画和撇捺，避免误画成木字。透明锚点只锁定 advance，不参与可见笔画。
- 记牌器默认 gap 保持 2，不采用讨论中的 1px 改值；默认总宽为 358px。新资源以独立版本构建，避免继续覆盖已部署的 1.10.17。
- 预览定位需用快照中的已偏移 ascent 加上当前表单与快照偏移的差值，既避免重复下移，又保留未保存拖动预览。
- 2026-09-14 最终 `paper-26.2` 强制执行 `compileJava compileTestJava --rerun-tasks` 成功，独立 JUnit 七类定向回归 181/181 通过，无跳过、无失败容器；包含实际 Node 执行的记牌器 Y 数值测试。测试锁定默认几何 21×27/advance22/总宽358，不能仅靠生成与运行期自洽断言。
- 三目标 1.10.18 JAR 与两种 ZIP 均构建并通过 SnakeYAML 重定位校验；九个归档 CRC、版本、目标格式75/84/88、44条counter声明、22张PNG及锚点、内嵌/独立bundle字节一致性、125个OGG和禁止原版hotbar覆盖均核验通过。三个 JAR 已复制到 C:\PluginLibs，副本哈希一致。
- 已检查真实 PNG 分层合成图；未执行本轮完整 Chromium 交互、全仓库测试或测试服部署，CraftEngine现场重载及客户端视觉仍未确认。

### 1.10.17（历史源码记录）

- 当前默认 profile 生成 210 条 CraftEngine image provider：牌 110、头像 40、王冠 8、bot 6、记牌器 44、Hotbar 2；头像为 4/6，记牌器与 Hotbar 均为 100%。记牌器为紧凑仪表盘几何（21×12/21×8 分层图形、cell 21×27、advance 22、默认总宽 358px），未生成档位不得由 Debug Web 或运行期渲染路径静默回退。
- 本轮只同步版本、provider、profile、保存语义、内嵌页面与资源白名单契约；现场部署、客户端资源包应用和全仓库测试不在本轮已验证范围内。

### 1.10.16 历史验证记录（2026-09-12）

- `paper-26.2` 已强制执行 `compileJava compileTestJava --rerun-tasks`，均成功；独立 `DebugWebServerTest` 实跑 42/42，通过且无跳过、无失败容器。稳定 DOM 版本已补齐四层选择、坐标输入、FullScreen API、指针门控、资源失败提示与静态节点更新。
- 三目标九个 1.10.16 产物已完成 5114 项资源审计：归档 CRC、目标 API/字节码、唯一作者、SnakeYAML relocation、125 个 OGG、当前 profile 的 100% hotbar、100% 记牌器、内嵌/独立 CraftEngine bundle 字节一致性及原版 hotbar 覆盖均通过；`paper-1.21.11` 与 `paper-26.1.2` 没有当次测试编译输出，未宣称其测试通过。三个 JAR 已复制到 `C:\PluginLibs`，副本 SHA-256 与构建产物一致。
- README 与发布示例已同步 1.10.16；截图文件已更新。稳定 DOM 修复后的真实 Chromium/CDP 全流程已实际运行，但仍有 7 项连续拖动 dirty 断言失败；已通过的部分包括四种 viewport、无应用 JS 错误、四层切换、40px 视图平移、Alt 轴锁、右键坐标、Hotbar 资源复用/失败回退、保存重载与 Fullscreen API。现场部署、`/ce reload all`、客户端资源包应用及进服渲染仍未确认。

### 1.10.12 构建与验证记录（2026-09-09）

- 三个目标 `paper-1.21.11`、`paper-26.1.2`、`paper-26.2` 均串行执行 `clean shadowJar zipResourcePack zipCraftEngineBundle verifyRelocatedSnakeYaml`；单独执行 `shadowJar` 不会生成两种 ZIP，必须显式调用 ZIP 任务。
- 26.2 独立 JUnit 实跑资源契约 49 项、HUD/对局/Debug Web/overlay/旧 hotbar 清理契约 93 项，均成功且无跳过；这是定向回归，不代表全仓库测试已运行。
- 独立 Launcher 必须检查发现数非零、容器失败数和成功数；Windows 下 UTF-8 argfile 中的中文路径可能仍导致类加载失败，本轮通过 Python `subprocess.run` 参数列表直接传入完整 classpath。版本属性使用 `1.10.12` / `26.2` / 资源包格式 `88`，不能把 Java 版本 `25` 当作资源包格式。
- 已逐个核对 9 个 JAR/ZIP：版本与目标 API、Java 字节码级别、125 个 OGG 源字节一致性、22 张记牌器 PNG 的尺寸与最右列有效宽度、20 张 label/digit 的 alpha=1 锚点、完整 9 槽逐像素颜色、禁止原版 hotbar 覆盖，以及内嵌与独立 CraftEngine bundle 字节一致性。三个目标的插件 JAR 已额外复制到 `C:\PluginLibs`，副本 SHA-256 与构建产物一致；未覆盖其他版本文件。
- 游戏内显示、边缘点击及 legacy 皮肤 hat 决策仍待确认；本轮未部署或重启测试服。记牌器物品 lore 仅同步为累计已出语义，仍沿用既有硬编码文本，不扩展为全量文本外置。

### 1.10.15 修复取证（2026-09-10，实施中）

- 现场 2000 端口属于 Leaf 26.1.2，日志确认运行 MUZ 1.10.12；工作区版本号不等于已部署版本。
- CE 资源目录已是每档 22 个声明的三层记牌器，但 `generated/resource_pack.zip` 仍保存 2026-09-09 的旧 48px 字体。偏移 50 时 `U+EB35` 应为数字 0，旧 ZIP 却对应 `rank_3_dim.png`，造成灰色大号 3 重叠；不能通过改玩家偏移或缩小新版字形掩盖此错配。
- 现场 ZIP 与 28 个相关资源文件已备份到 `C:\Users\Admin\AppData\Local\Temp\muz-hud-parity-backup-op1pbk92`，29 份副本哈希一致；ZIP SHA-256 为 `a0937b7b292cc7d9afb03e515dd549e09ab132248c0ed08f72d5edb13ea52ac6`。
- 已安装 CE 26.8 的 `reload pack` 只生成包，不先重读 YAML；`reload all` 才先重读再生成。JDK `ZipFile` 可读取现场受保护 ZIP，已核对字体 JSON 的长度与 CRC；Python 本地头名称不一致错误不代表 Minecraft 无法加载。
- 已实际强制执行 26.1.2 `compileJava compileTestJava processResources`，六项 Gradle 任务均执行成功；编译保留既有 `Unsafe` 弃用警告。独立 JUnit 首轮 87 项中 80 项通过，补齐 PlaceholderAPI 运行时依赖后为 83 项通过、4 项失败；修正校验器映射错误分类、资源生成失败保留、测试插件数据目录夹具及过时源码断言后，最终 87/87 通过、无跳过、无失败容器。真实 Chromium 浏览器控制通道已打开生成页面，验证 19 个字段、5 张同源样例牌、15 格记牌器、9 槽 hotbar、640×360 MC 视口、dirty 往返、保存成功/失败、保存期间禁用与 Ctrl+S/Ctrl+R 防重复、重新读取丢弃未保存值；页面无应用 JS 错误，唯一控制台错误是 fixture 静态服务缺少 favicon 的 404。Playwright 直启脚本因 Windows/Git Bash 下 Chromium `process_title` 断言未执行，不能据此宣称已完成多 viewport Playwright 验收。现场完整重载和客户端重新下载单独验收，不与网页 mock 验证混称完成。验收截图归档于 `docs/screenshots/muz-hud-debug-web-initial.png` 与 `docs/screenshots/muz-hud-debug-web-final.png`。最终干净构建 `1.10.15 / paper-26.1.2` 的 `shadowJar zipResourcePack zipCraftEngineBundle verifyRelocatedSnakeYaml` 均成功；JAR SHA-256 为 `f997eba7be218aeb5d83b416f837aa1d31c2cf3bba1c9f078e28738f5bc61870`，资源包 ZIP 为 `1acf28d8cb773f9f5d0668f7d7d757d3e01d2c6eaf0d7953aa1378d8f47351dd`，CraftEngine ZIP 为 `6a5bdaaef930fbfc6c31cf2d071a3914cecd11decad86915e38eb89d62f4631c`；三者均无原版 `hotbar.png` / `hotbar_selection.png` 覆盖，JAR 已复制至 `C:\PluginLibs` 且副本哈希一致。
- 阶段 A 校验器格式兼容：`HudResourcePackVerifier.verifyPackMetadata` 原先硬编码只接受 `pack_format` 84/88，部署 `paper-1.21.11`（格式 75）时会把合法资源包误判为「pack_format 不受支持」。现改为读取 `PackAssets.SUPPORTED_RESOURCE_PACK_FORMATS`（`{75, 84, 88}`，与 `build.gradle.kts` 的 `supportedMuzTargets` 表逐项对应）判定。新常量放在插件侧是因为运行期 Java 读不到 Kotlin 构建脚本的 `muzTarget.resourcePackFormat`，而同一 JAR 可能部署到任一目标，校验器无法预知当前目标，只能接受项目可能产出的全部格式；两侧必须同步增删。`HudResourcePackVerifierTest` 已把 `packFormat84与88都通过` 扩为 `packFormat75_84_88都通过`，并新增 `不受支持的packFormat被拒绝`（格式 100 仍抛「pack_format 不受支持」）。独立 JUnit 实跑该测试 21/21 通过，`HudResourcePackSyncTest`+`LegacyHotbarSpriteCleanupTest`+`CraftEngineBundleResourcesTest` 合计 53/53 通过。
- 现场满屏大号数字与保存报「pack.mcmeta 缺少有效 pack.pack_format」的主因仍是现场部署陈旧（跑 1.10.12 旧 48px 包），Debug Web 保存链路不重导 bundle。修复需部署对应版本 1.10.15、完整 `/ce reload all` 重读并重打包、客户端重下资源包；校验器格式兼容只是消除部署 1.21.11 时的独立误判，不替代现场重载。
- 2026-09-11 真实 Chromium fixture 复验通过：拖拽与缩放期间不重建当前层 DOM，pointerup 后再刷新；Hotbar wheel 可循环持槽；保存失败保留 dirty 与页面值、保存成功清空 dirty 并显示成功淡出、Ctrl+S/Ctrl+R 分别只发起一次请求且重新读取会丢弃未保存值。最新截图归档于 `docs/screenshots/muz-hud-debug-web-initial.png` 与 `docs/screenshots/muz-hud-debug-web-final.png`。

### 按 profile 精简资源生成（1.10.16）

- 构建期资源不再默认生成全部牌高、偏移、头像、记牌器与 Hotbar 档位；根目录 `muz-resource-profile.yml` 是版本化构建输入，也可用 `-PmuzResourceProfile=<path>` 指定。profile 必须列出各族离散档位，且必须显式包含 `0` 基准偏移才能兼容无档位旧调用方。
- Hotbar 按用户确认只生成 `hotbar.scale` 的单一档位；当前 profile 的头像 scale 为 4/6，左右头像固定使用 4 倍、中间头像使用 6 倍。切换档位必须重新生成 bundle、重新生成/校验资源包并重新下载。运行期收到未生成档位时必须明确拒绝，不得静默回退或发送未声明字形。
- `PackAssets`、`PackTiers`、校验器、Debug Web 资源白名单和测试均以当前生成集合为边界；生成结果与运行期复算必须保持同源。

### Hotbar HUD 字形重构（历史 1.10.17 九槽契约；不代表当前三道具契约）

- 底图字形（0xEF00，`hotbar_slots.png`）保持 182×22、advance=183、ascent=-128 契约不变。槽 0..5 仍是无边框纯色块；槽 6/7/8 改为构建期烘焙的物品图标（水桶/鸡蛋/番茄）+ 右下角固定示例数字（17/3/5，3×5 白字带 1px 全不透明深色描边）。整幅仍全不透明、非槽区仍为 `#121216`。绘图逻辑只保留构建期一份（`build.gradle.kts` 的 `writeHotbarSlotsGlyph` + `drawHotbarSlotIcon` / `drawHotbarSlotNumber`）。
- 新增可移动「选中槽」字形（0xEF02，`hotbar_select.png`，`writeHotbarSelectGlyph`）：20×22、advance=21、四周 2px 亮黄（`#FFE040`）空心描边、中间透明；与 0xEF00 同字体族 `minecraft:muz_hotbar`、同 ascent=-128，独立贴图与码位。CE 声明加在 `configuration/images/hotbar_hud.yml` 的 `muz:hotbar_select` 条目。
- 两侧常量严格同源：`PackAssets.HOTBAR_SELECT_CODEPOINT/WIDTH/HEIGHT/ADVANCE` 对应 `build.gradle.kts` 的 `hotbarSelectCodepoint/hotbarSelectGlyphWidth/Height/Advance`，均带交叉引用注释。
- 运行期 `HotbarHudService.buildActionBar` 扩为可组合多字形：底图之后用零净前进量的负空格夹心插入选中框，定位到 `player.getInventory().getHeldItemSlot()`（主线程读取、clamp 到 0..8）对应槽像素位置（槽 i 左 x=2+i*20，选中框左 x=1+i*20）。夹心 `lead + HOTBAR_SELECT_GLYPH_ADVANCE + trail = 0`，底图净前进量与客户端居中不变；`offset-x` 首尾对称抵消逻辑照旧包在整体外层。持槽经 `pendingHeldSlot` 字段传入（不改 `buildActionBar(entry)` 调用形态，避免破坏 `DoudizhuRuntimeSyncTest` 的文本锁定断言）。Debug Web 接管（`useDebugOverlayGlyph=true`）期间仅在当前 hotbar scale 的 overlay 已完成 CE 重载与 ZIP 校验后切换到对应调试底图/选中框码位（默认 100% 为 0xEF01/0xEF03）；就绪状态必须同时记录具体 scale；切换到未生成或未校验的 scale 时明确拒绝并提示重新生成资源包，退回当前 scale 的 bundle 固定码位，绝不发送其它档位的未声明字形。
- 校验器 `HudResourcePackVerifier`：`verifyHotbarPng` 对槽 0..5 逐像素锁纯色、对槽 6/7/8 只断言全不透明 + 出现白色数字像素；新增 `verifySelectPng`（20×22、2px 亮黄描边、内部透明）；`verifyHotbarGeometry` 增校验 `muz:hotbar_select` 声明；`HOTBAR_SELECT_TEXTURE` 加入 `requiredEntries`/`isSelected`/`isRelevantOverlayEntry`。
- 契约测试：`CraftEngineBundleResourcesTest` 拆分槽契约并新增 `hotbarSelectTextureIsHollowHighlightFrame`；`HudResourcePackVerifierTest` fixture 补 0xEF02 provider 与 `hotbar_select.png` 打包。
- 已实跑（真实执行，非 up-to-date）：`compileJava`、`compileTestJava`（paper-26.2）均成功；独立 JUnit `run.py` 定向回归共 found=150 succeeded=150 failed=0，无跳过、无失败容器。三个目标 `paper-1.21.11`、`paper-26.1.2`、`paper-26.2` 均完成 `shadowJar zipResourcePack zipCraftEngineBundle verifyRelocatedSnakeYaml`，资源包格式分别为 75/84/88，均含 125 个 OGG 且无原版 hotbar sprite 覆盖；三个 JAR 已复制到 `C:\PluginLibs`，副本 SHA-256 与构建产物一致。
- **未验证（需进服人工确认）**：图标可辨识度、烘焙数字位置、选中框随持槽移动的对位是否精确。这些属渲染类改动，无法自测，须重下资源包后进服确认。未运行全仓库测试；未部署或重启测试服。

### 1.10.14 变更记录

- `/muz debug add [数量]` 改用 `PhysicalTableManager.placeDebugTableAt` 专用调试放桌入口，仅绕过桌面/椅子方块占用检测（`placementObstruction`），仍保留玩家已在其他桌、重复桌名、区块预加载、残留实体清理与实体生成等保护；`debug-` 测试桌沿用现有持久化入口但由 `isDebugTableName` 隔离，不写入数据库。正式入口 `placeNewTableAt` 不受影响，继续做完整的方块占用检测。
- `/muz debug add 99` 可一次生成 99 张自动对局观察桌，仅供测试，可能产生大量 Display Entity 并造成主线程卡顿；执行前应确认测试服可承受该负载。
- 新增契约测试 `DebugPlacementBypassTest`（10 项），源码扫描守护调试入口与正式入口的边界不退化，并确认调试桌不会写入数据库。
- 本轮已完成 `compileJava`、`compileTestJava` 与独立定向回归，结果见下方 1.10.14 验证记录。

### 1.10.13 前端验证记录（2026-09-09）

- `paper-26.2` 的 `compileJava`、`compileTestJava` 已实际执行成功；独立 JUnit 资源契约 **49/49**、HUD/对局/Debug Web/overlay/旧 hotbar 清理 **94/94**，合计 **143/143**，无跳过、无失败容器。未运行全仓库测试。
- 使用当前 `buildHtml` 和真实服务端 geometry 导出本地页面，在 Chromium 中检查 1440、1024、900、768、501、375、320px 七种宽度：页面无横向溢出、表单控件不被裁切、操作栏吸附有效；GUI 缩放到 4 时预览仍在面板内横滚。19 个字段、15 格记牌器与 9 槽 hotbar 数量保持。
- 浏览器验证了增量 patch、Token、Ctrl+S/Ctrl+R、撤销、成功淡出、错误常驻与耗尽隐藏保留占位；HTTP 响应使用 mock，不代表测试服联调。2000 端口当前未监听，未部署或重启测试服，未构建本版本三目标发布 JAR/ZIP；上方 1.10.12 九个产物记录仍属于历史版本。

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

