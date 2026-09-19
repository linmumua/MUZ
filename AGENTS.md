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

- 版本号写在 `build.gradle.kts` 的 `version`，当前源码版本为 `1.10.37`（开局后对入座真人隐藏桌边动态浮空字；三个支持目标均已完成干净构建，未部署实服，客户端可见性待验）。`paper-plugin.yml` 用 `${version}` 占位，不要手写。
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
| `hotbar-hud` | 仅 `GamePhase.PLAYING` 在线真人座位使用的三个独立透明道具 HUD（鸡蛋、水桶、番茄）与虚拟选中高亮；每个图标盒 20×22、间隔 4、总宽 68、advance 69，基础 ascent 为 -43（按现代客户端 ActionBar BitmapProvider 基线校准，offset-y=0 时底部贴合逻辑屏幕）。默认仍关闭；保留原版 9 槽，不接 BetterHud、不覆盖 `minecraft` 原版 sprite。`offset-x` 运行期即时生效；`hotbar-hud.offset-y` 与 Trick HUD 的三个 `offset-down` 由四层连续覆盖层统一异步重载、生成并校验，客户端重下资源包后可见；`glyph-ascent` / `slot-count` 仅为构建约定，运行期不读；互动参数位于 `hotbar-hud.interaction` |
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
- 尺寸与缩放档位仍有合法值白名单，且只能取 `muz-resource-profile.yml` 当前已生成的离散资源：牌高 `53`、头像缩放 `4/6`、记牌器缩放 `100`、三道具 HUD 缩放 `100`。四个 Y 字段改为连续整数：Trick 的 `offset-down`、`avatar-offset-down`、`counter.offset-down` 均为 `-128..512`，hotbar 当前 100% 的 `offset-y` 为 `-299..512`；非法值必须拒绝或明确报错，不得 nearest/静默回退。原 profile 的 Y 档 `0/50` 与 `0/122` 继续作为基础 bundle 兼容，不扩展 profile 的全 Y 档。
- 玩家个人数据与运行时状态不要混进 `config.yml`，走 `player-settings` / 存储层。

## 领域模型约束

- `DoudizhuPlugin`：插件入口与全局装配。**注意：当前已超过 5500 行，是事实上的 God Class。** 禁止继续往里堆新逻辑，新功能应落到对应 service。
- `GameTable`（`game`）：单桌对局状态机，持有座位、手牌、出牌历史、剩余张数统计。出牌阶段真人跟牌若 `MoveAdvisor` 判定无可压，会保留 `currentTurn` 等待 20 tick；等待期间可用现有「不要」手动跳过，在线真人仍未响应才由独立 epoch/token 二次校验后自动不要。等待期间 `tickActionBar` 仍广播其他玩家的正常 ActionBar 与倒计时，只给当前真人追加无可压提示；在线判断统一使用 `onlinePlayer != null && isOnline()`。自动不要回调只调用一次正常回合续接链路，不重复 `refreshPhysicalTable`。机器人无可压继续沿用现有立即处理，不等待 1 秒；先手不能过与 `TimedOutPlayCoordinator` 的超时托管语义不变。
- `TableEffectCoordinator`（`game`）：桌内普通音效、出牌语音和倒计时统一从此出口播放；按玩家+音效做短窗口去重，倒计时按 16 tick 窗口抑制重复触发，避免重复事件造成音效叠加，不吞掉不同玩家或不同音效。
- `TableMusicCoordinator`（`game`）：背景音乐独立维护活动会话、轮播任务句柄和实际收听者 UUID。只有合法开局才能激活；结束先禁用会话、递增 epoch 并取消轮播，再向实际收听者与当前座位停止 `PackSounds.bgmTracks()` 的五首曲目。停止后即使牌桌尚未切回 LOBBY，`updateState` 或迟到回调也不得重启；旧局任务不得影响新局。只停止本插件背景曲目，不使用 `stopAllSounds`，不影响胜负音效或其他玩家。同一活动会话的重复开局请求必须幂等；每次轮播消费都递增 epoch，已执行回调不得再切曲或取消新任务。每次播放前必须先停止五首 BGM，测试须记录调用顺序与模拟活跃曲目，不能仅断言调用总数。普通提示音允许与 BGM 混音；可配置提示音及动作不能配置为 MUZ BGM，否则会绕过本协调器，外部插件/命令的声音不属于本协调器的互斥范围。
- `TrickHudService` / `TrickHudView`（`game`）：正式 Trick HUD 的数据组装与字形渲染分离，由牌桌对局状态、配置和玩家级调试棒行覆盖共同驱动；Service 产出 cell，View 负责 advance 宽度与居中算式。`/muz give debug` 保留为个人 HUD 调试棒：玩家未进牌桌时可显示与 Debug Web 对照的游戏内 Trick HUD；右键循环牌行、头像行、记牌行，Shift+右键隐藏。调试棒只影响持有者个人，不写入运行期正式配置；`/muz debug show|stick|hud` 继续移除。Debug Web 仅负责回环地址上的运行期 HUD 参数预览与配置。**记牌器行（counter row）显示本局累计已出张数**：固定 15 格，使用资源包分层位图 glyph 叠加牌类、矩形框和数字三层，不再使用纯文本 MiniMessage 作为正式记牌器渲染（详见「记牌器分层字形渲染」）。
- `HotbarHudService`（`game`）：热键栏 HUD，管理 hotbar 字形渲染与 Action Bar 推送。`GameTable` 的 ActionBar 路由走此 service，独立于 TrickHudService。当前正式契约改为三个独立透明道具图标（鸡蛋、水桶、番茄）与虚拟选择高亮：每个图标盒 20×22、图标间隔 4、总宽 68、advance 69，基础 ascent 为 -43（按现代客户端 ActionBar BitmapProvider 基线校准，offset-y=0 时底部贴合逻辑屏幕）。仅在 `hotbar-hud.enabled: true`、牌桌处于 `GamePhase.PLAYING` 正式出牌阶段、且座位为在线真人时显示；其余阶段、离桌、停服及普通玩家保持或恢复原版 9 槽。Hotbar 不接 BetterHud，也不覆盖 `minecraft` 原版 hotbar sprite。虚拟选择索引由桌内道具交互维护；滚轮与数字键换槽事件无法严格区分，按同一映射处理，不宣称捕获原始滚轮。`offset-x` 仍即时生效；`offset-y` 与 Trick 三个 `offset-down` 统一走四层连续覆盖层、CE 重载、ZIP 重建与客户端重新下载；Debug Web 仅切换已验证完整请求对应的 overlay 字形。所有非 `PLAYING` 阶段的对局提示仍走普通 ActionBar。
- `TableGadgetService`、`TableGadgetSettings`、`TableGadgetEffectService` 与 `model.TableGadget`：桌内三道具互动的选择、资格、目标、冷却、并发和效果边界。仅服务 `PLAYING` 阶段同桌在线真人；机器人、旁观者、跨桌玩家和无效目标不参与。目标高亮使用对附近观察者全局可见的短时真实发光，带引用计数，记录并恢复既有状态，多引用和所有离桌/死亡/传送/断线/关桌路径必须清理；清理目标时同时删除指向该目标的旧 actor 状态，避免同 UUID 返回后被短路。CE `FurnitureInteractEvent` 在手牌仲裁之后进入同一道具右键路由。鸡蛋/番茄为短暂无伤害投掷，水桶为贴身短时透明水幕，不放置真实水方块、不造成伤害或扩散。水幕实体与头顶水桶按两个实体计入上限；效果实体/粒子必须统一登记、限额和清理，不得遗留。`clearAll()` 用于可恢复 stop/reload，`shutdown()` 仅用于插件最终关闭。PlayerItemHeldEvent 无法严格区分滚轮与数字键，统一按同一虚拟索引映射处理。
- `DebugWebServer`（`debug/` 包）：内嵌 HTTP 服务器，监听 `debug.web-ui.port`（默认 2000）且仅绑定回环地址。提供可编辑 HUD 配置页；启用条件为 `debug.web-ui.enabled: true`，默认关闭。页面只编辑运行期配置键，不修改构建期字体绘图参数；页面文案与字段白名单统一显示 22 个 HUD 参数，四个 Y 字段均为连续整数范围。当前页面采用占满 viewport 的 Minecraft 风格全屏画布，顶部为无圆角、全宽的草方块风格直角顶栏；中央 640×360 Minecraft 逻辑 GUI 按顶栏以下可用 viewport 自适应到最大尺寸，配置表单作为可折叠磨砂玻璃浮动面板，并提供浏览器 Fullscreen API 按钮；四个 HUD 图层在预览中始终可见，关闭运行期开关时仅标记状态，不隐藏调试几何；画布包含像素化背景、准星、世界视口标识、操作提示、右键坐标提示和四层 HUD 编辑场景；页首保留“只开放 22 个 HUD 运行期字段”、异步配置/overlay 保存与客户端重下资源包提示。右键显示当前层左右边界、上下边界与指针逻辑坐标，Shift+方向键做 1 MC 像素微调；三道具图标辅助层不覆盖原版 hotbar，资源失败仅显示三道具 HUD 局部占位；资源预览通过 `/api/preview-resources` 下发当前 profile 白名单，再由 `/api/resource/{完整资源键}` 提供牌面、头像、记牌器与三道具 HUD 的同源真实 PNG，牌行预览优先直接显示 manifest 对应的真实牌面 PNG；头像与记牌器分层也通过同一白名单加载真实 PNG，禁止任意路径读取。根目录 `debug-hud-preview.html` 是不依赖后端的前端原型，打开即为占满窗口的 Minecraft 视口；四层 HUD 直接叠加并可视化拖动，配置面板默认隐藏，仅按需展开。正式 Debug Web 首页直接加载资源内嵌的 `debug-hud-preview.html`，缺失时明确返回错误；保留的 Java 内联构建页只作为测试/兼容模板，不写入具体旧版本号；内嵌页面采用现代新拟物视觉（柔和内外阴影、凹陷输入框、凸起按钮、响应式磨砂面板）；预览舞台只保留上一手牌、三头像（小/大/小）、记牌器与三道具 HUD，不显示 BossBar；同时通过 `/api/preview-resources` 拉取白名单后只显示真实 PNG（牌面、头像、记牌器三层、鸡蛋/水桶/番茄三个独立图标与选中高亮），禁止使用 emoji 或 CSS 绘制假资源；拖动使用 requestAnimationFrame 合并 pointermove，避免每个事件同步重排导致左右拖动卡顿；拖动位移按当前 GUI 缩放倍数换算回 MC 像素（`CSS 位移 ÷ zoom`），否则放大 3 倍时鼠标移 1px 会跳 3 个 MC 像素；三道具图标与选中高亮的宽高、间隔、总宽和 advance 一律取自 `/api/state` 下发的 `geometry.hotbars`，禁止在 CSS 里写死 20×22、4px、68px 或 69；牌行、头像行、记牌行同样只消费 `geometry.cards` / `avatars` / `counterTiers` 的宽高与 advance（由 `applyGeo()` 统一写入内联样式），CSS 里不得再出现 74px / 48px / 22px 这类写死尺寸；GUI 缩放只作用于浏览器 640×360 逻辑舞台的 CSS 显示比例，不改变下发的 MC 像素值或客户端字体几何；记牌器 label/frame/digit 在 Web 预览中与游戏内共用白名单真实 PNG，并使用 `image-rendering: pixelated`；这保证浏览器与 Minecraft BitmapProvider 消费同一套像素资源。契约测试锁定鸡蛋、水桶、番茄三道具真实图标与选中高亮可见，Shift+空白画布拖动平移视图；浏览器 Fullscreen API 失败时保留普通 viewport 编辑模式；视图平移状态与 HUD 配置偏移分离；舞台按浏览器 viewport 自动适配缩放，保持 640×360 MC 逻辑坐标和全屏舞台缩放后的拖动与缩放指针换算一致；表单和连续字段更新通过 requestAnimationFrame 合并，并保持拖拽/缩放状态变量单例，页面吸附默认关闭，以自由拖动为主（用户最新交互要求覆盖历史默认开启；开关只影响网页，不写入配置）；页面初始化时从浏览器 `localStorage` 恢复日间/夜间主题状态；该状态不写入 HUD 配置。`/api/save` 与 `/api/reload` 通过 `HudWebApplyCoordinator` 串行执行：HTTP 线程先完成方法、Token、Content-Type、JSON 与大小校验，只提交任务；单线程异步阶段用 SnakeYAML 写 `config.yml` 与四层自有资源，随后由主线程触发 CraftEngine 真实 reload，再异步生成并验证实际 ZIP 中的字体与 PNG，最后执行 `markVerified`、运行态应用并发布 Snapshot；保存与重新读取均会重载 CE，确保磁盘资源已被 CE 重新读取；空 patch 不触发资源流程，任一步失败都回滚配置与自有资源、清除 ready 且不报成功。`HudWebApplyLease` 按插件数据目录共享独占租约：120 秒只废弃本次结果，底层任务真正结束前不取消、不释放占用，后续请求在写盘前拒绝；关闭或超时后不应用迟到结果；`HudWebApplyTaskGate` 在 raw 真正结束后才释放租约并执行实例收尾，最终主线程应用使用原子闸门避免 close/timeout 竞态。HTTP 外层等待 125 秒且不取消底层任务。
- Web 保存与磁盘重载共用插件提供的 HUD 配置锁，形成 Web 自身的配置快照边界；配置文件 I/O 保持在异步线程，不放回主线程。该锁不等同于全局配置事务：现有管理菜单及其它非 Web 配置入口尚未全部接入，若它们并发改写共享 `MuzYamlConfig`，仍存在既有竞态，后续需统一配置层处理。
- `DebugHudConfigController`（`debug/` 包）：维护 Web HUD 白名单（22 键）、类型校验、增量保存与不可变配置快照。保存只写用户提交的键，并通过 `saveWithComments()` 保留模板注释。四个 Y 字段按连续整数校验，尺寸与 scale 仍按当前 profile 离散白名单校验；`currentGeometry()` 由 Controller 统一生成并将 geometry 直接下发，前端不复算字形表。`HudResourceRequest` 是不可变的 card/avatar/counter/hotbar 完整请求，`HudOverlayLayout` 只提供覆盖层 metadata 与字形布局；正式 Trick 使用独立 `baseFont_continuous` 字体和 base tier 0 char，不覆盖原 font/char。
- `HudOverlayWriter`（`debug/` 包）：为四层 HUD 写出 `trick_hud_continuous.yml` 与 `hotbar_debug.yml`，并在需要时生成连续目录 `resourcepack/assets/muz/textures/font/continuous/` 下的 padding PNG。Trick 覆盖层使用 `baseFont_continuous` 独立字体和 base tier 0 char，不覆盖正式 bundle 的 font/char；hotbar 复用既有 debug char/font。`ascent=baseAscent-rawY`；向上导致 ascent 超过原 height 时只在底部补透明像素并同比增加 provider height，保持 PNG 比例、可见尺寸与 advance 不变；bot 的 18px PNG 按 gcd 整数规则对齐 height 10/11。源 PNG 绘图逻辑只保留构建期一份。文件 I/O 异步且由四层资源协调器串行，Writer 本身不分发 CE 命令；客户端必须重新下载资源包才能看到 Y 位置变化。
- `PackAssets`（`assets`）：插件侧复算资源包字形码位、字体名与覆盖层 geometry，零文件 IO。必须与 `build.gradle.kts` 生成逻辑严格对齐，两侧不一致会导致游戏内显示豆腐块；紧凑记牌器继续按 15 格、每档 22 个 glyph、label/frame `21×12px`、digit `21×8px`、cell `21×27px`、advance=22 的三层几何对齐；三道具 HUD 继续按每个图标盒 20×22、间隔 4、总宽 68、advance 69、基础 ascent=-43 对齐。连续覆盖层使用独立 `baseFont_continuous` 与 base tier 0 char；动态 PNG 只位于 `resourcepack/assets/muz/textures/font/continuous/`，不覆盖正式 font/char，也不复算旧九槽底图。
- `CraftEngineBundleExporter`（`compat`）：导出内置 CraftEngine bundle，并在指纹提前返回前无条件删除旧版本遗留的 `resourcepack/assets/minecraft/textures/gui/sprites/hud/hotbar.png` 与 `hotbar_selection.png`。这两张旧文件会全局影响客户端物品栏，不能因为 bundle 指纹已是最新而跳过清理。
- `CraftEngineHudResourceBridge`（`compat`）：仅在 CE 可用时创建，接收不可变 `HudResourceRequest`，按“配置与四层资源异步事务 → 主线程真实 CE reload → 异步 generateResourcePack → 实际 ZIP 字形/PNG 校验 → 主线程 `markVerified`/apply/snapshot”执行。Hotbar 资源选择与 Debug Web 是否 enabled/running 独立，必须由完整 `HudResourceRequest` 的 verified ready 决定；编译依赖 0.0.67 未提供 `issues()`，失败详情使用 `toString()`；缺失或签名不兼容时明确失败，不通过命令返回值推测完成。不声称隔离管理员自行发起的 CE 重载。
- `HudResourcePackVerifier`（`compat`）：异步从实际 ZIP 中央目录读取 HUD 白名单内容，核对解压长度/CRC、内置 SnakeYAML 声明的 font/char/file/height/ascent、bundle 与 Debug Web overlay 合并后的 font/char 声明、当前 profile 的记牌器 PNG、三道具图标 PNG、选中高亮和覆盖层；兼容 CraftEngine 生成的单值或数组形式 `pack.pack_format`，但数组中至少一个值必须属于项目目标格式；记牌器码位按 `scale + downTier×22 + layer` 对齐；拒绝旧字体分页、旧大号数字映射和原版 hotbar 覆盖。生成路径与 CE 上传源文件路径须一致或逐字节一致。旧 verifier 仅可用于离线 legacy 校验，不能作为 ready 凭据；服务端内容校验、远端上传完成、客户端应用是三件事，后两者无回执时保持未确认。
- `HudOverlayRuntimeState`（`game`）：保存完整四层 `HudResourceRequest` 的已验证 ready 快照；任何资源流程失败都必须清除 ready，不能恢复先前 ready（CraftEngine 可能已经部分 reload）；连续非 profile Y 未 ready 时正式 HUD 必须隐藏并告警，不得 nearest，精确的旧 bundle 档位可兼容。Legacy offset-only Hotbar 写入口必须显式拒绝，只有完整 `HudResourceRequest` 才能写入/应用资源。`HudResourceRecoveryService`（`debug`）独立负责启动、`/muz reload` 与 CraftEngine enable 后的资源恢复，不受 Debug Web 开关影响；入口类只做装配委托，不堆业务逻辑。
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
- 页面可编辑字段仅限 HUD 运行期配置，共 **22 个**：`trick-hud.enabled`、`trick-hud.avatar-scale`、`trick-hud.avatar-gap`、`trick-hud.card-step`、`trick-hud.card-height`、`trick-hud.offset-down`、`trick-hud.avatar-offset-down`、`trick-hud.offset-x`、`trick-hud.card-offset-x`、`trick-hud.avatar-offset-x`、`trick-hud.avatar-outline.enabled`、`trick-hud.avatar-outline.color`、`trick-hud.counter.enabled`、`trick-hud.counter.scale`、`trick-hud.counter.offset-down`、`trick-hud.counter.gap`、`trick-hud.counter.hide-exhausted`、`trick-hud.counter.offset-x`、`hotbar-hud.scale`、`hotbar-hud.enabled`、`hotbar-hud.offset-x`、`hotbar-hud.offset-y`。
- 牌高、头像缩放、记牌器缩放与 hotbar 缩放仍按当前资源包的离散档位白名单校验；四个 Y 字段改为连续整数，Trick 三项允许 `-128..512`，hotbar 当前 100% 允许 `-299..512`。非法值由后端拒绝保存，不静默 nearest、不改写成相邻档位。正式两份 HTML 的 geometry 直接消费服务端 raw 值。
- 页面右侧的预览按 **Minecraft 像素坐标系**绘制，屏幕几何由服务端固定下发：`screenWidth=640`、`screenHeight=360`、`bossBarBaselineY=20`、`actionBarBottomY=360`。各行宽度与字形前进量由 `DebugHudConfigController.currentGeometry()` 汇总 `PackAssets`、`PlayerHeadRenderer`、`HotbarDebugOverlayWriter` 后随 `Snapshot.geometry` 下发，前端**只消费 `PreviewGeometry` 数组**（`cards[{tier,height,width,advance}]` / `avatars[{scale,plainAdvance,outlinedAdvance,rowHeight}]` / `counterTiers[{scale,cellWidth,cellHeight,advance,...}]` / `hotbars[{scale,width,height,advance,slot/select geometry,...}]` / `counters` 的固定 15 格动态数据），不复算任何字形表（避免把「两处手写表不同步」的问题搬到前端）。预览复刻了游戏内的居中规则并**用整数 MC 像素**执行：`W = max(cardRowWidth, avatarRowWidth, counterRowWidth)`，`baseLeft = floor((screenWidth - W)/2)`，每行前再垫 `floor((W - 行宽)/2)`。顶部基线上的牌行/头像行走 `baseline - ascent` 公式（`cardAscent = card.height - offset-down`、`avatarAscent = avatar.rowHeight - avatar-offset-down`，`top = bossBarBaselineY - ascent`，因此 `offset-down` 增大会让行下移）。三道具 HUD 走 `hbY = actionBarBottomY - hotbarHeight + (hotbarBaseAscent - (hotbarBaseAscent - offset-y))`、`hbX = floor(screenWidth/2) - floor(hotbarAdvance/2) + offset-x`；预览按 `hotbar-hud.scale` 从服务端下发的当前 profile 档位读取三个独立图标、选中高亮与间隔几何，默认基础 ascent 为 -43；图片通过固定白名单同源路由加载，不能用纯 CSS 缩放冒充运行期资源。记牌行按 `trick-hud.counter.scale` 使用服务端下发的三层 cell 几何，Y 由独立 `counter.offset-down` 控制；默认 100% 为紧凑仪表盘固定 15 格、每格 advance 22px、cell 21×27px、gap=2、总宽 358px。GUI 缩放由页面选择器控制（2/3/4，默认 3），只影响 CSS 放大倍数，不影响下发的 MC 像素值。
- **预览各层可鼠标拖动**：牌行、头像行、记牌行、三道具 HUD 各有一个拖动层，拖动位移按 `CSS 位移 ÷ 预览缩放` 换算回 MC 像素后写入对应偏移键；四个 Y 字段直接保留连续 raw 值，网页吸附开关只影响页面交互，不把 nearest 写入配置。页面提供真正可操作的 `snapToggle` 吸附开关，按最新需求默认关闭、自由拖动；它只改变当前页面的拖动行为，不属于 22 个 HUD patch，阈值按实际 layer 几何使用 MC 像素。逻辑画布外边界、垂直中轴线与水平中分线始终可见，打开吸附按钮时两条轴增强显示并执行中心吸附；中心线与 Alt 首次有效位移锁轴状态仍保留。真实指针拖动或缩放期间只改当前层的 CSS `transform`/尺寸，不重建预览 DOM；`pointerup`/`pointercancel` 后才完整 `renderPreview()`。`setField()` 在 dragging/resizing 期间只更新 dirty 状态，避免句柄或隐式指针捕获因重建而失效。拖动只标记 dirty，须点「保存并应用」才写回 `config.yml`。
- 「保存并应用」只写回本次提交的白名单键并应用到当前运行态；保存严格执行“异步 config I/O + 四层自有资源 → 主线程触发 CraftEngine 真实 reload → 异步生成资源包与实际 ZIP 字体/PNG 校验 → 主线程 `markVerified` / apply / snapshot → 发布结果”的顺序。保存与「重新读取配置」都会重载 CE，确保四层资源已被重新读取；空 patch 不触发资源流程，任一步失败都回滚配置与自有资源、清除 ready 且不报成功。「重新读取配置」只从磁盘重新载入现有 `config.yml`，会丢弃页面上尚未保存的改动。租约 120 秒只 abandon 本次结果，底层 raw 任务结束前不 cancel、不释放。
- 三道具 HUD overlay 的 `pack.yml` 与 `hotbar_debug.yml` 均先写临时文件再原子替换，异步阶段不访问 Bukkit PluginManager；协调器每次提交在主线程重新解析 CraftEngine overlay 路径，关闭后拒绝新任务且已排队任务会失效，避免关闭后继续应用。
- 三道具 HUD 是否推送由 `hotbar-hud.enabled`、`GamePhase.PLAYING` 正式出牌阶段、牌桌座位为在线真人共同决定；三项同时满足时才显示鸡蛋、水桶、番茄三个独立透明图标与虚拟选中高亮。互动资格进一步由 `hotbar-hud.interaction.enabled`、同桌真人目标和视线范围共同限制；其余阶段、离桌、停服及普通玩家保持原版 9 槽物品栏，非 `PLAYING` 对局提示走普通 ActionBar。资源包不得生成或覆盖 `minecraft` 原版 `hotbar.png` / `hotbar_selection.png`；三道具不接 BetterHud。滚轮与数字键换槽无法严格区分，统一按 `previousSlot/newSlot` 方向处理，不宣称捕获原始滚轮。
- **hotbar 定位的两个方向机制完全不同，不要混谈**：
  - `hotbar-hud.offset-x` 走 CraftEngine 负空格，**运行期即时生效**，任意整数像素，不需要重新构建或重下资源包。渲染侧必须首尾对称抵消偏移量（见 `HotbarHudService.buildActionBar` 注释），否则客户端按总宽居中会把偏移吃掉一半。
  - `hotbar-hud.offset-y` 必须落在位图字形的 `ascent` 上，属于**客户端资源内容**。由 `HotbarDebugOverlayWriter` 运行期写出覆盖层（基础 ascent=-43，按 `offset-y` 调整，可见图标恒 22；向上补透明行后 provider height 可增加，但保持 PNG 比例、可见尺寸与 advance 不变），再通过 CE API 重读配置、生成与验证 ZIP，**客户端需重新下载资源包才能看到**。所以纵向调整不是即时的，每改一次都要走一轮重打包。
- 仍然禁止通过运行期配置修改构建期烘焙参数：`hotbar-hud.glyph-ascent`、`hotbar-hud.slot-count` 这两个键**运行期从未被读取**（纯文档键，记录构建期约定）。当前三道具 HUD 使用三个独立透明图标（鸡蛋、水桶、番茄），每个图标盒 **20×22**、透明间隔 **4px**，总宽 **68px**、advance **69**；默认基础 ascent 为 **-43**，按现代客户端 ActionBar BitmapProvider 基线校准。运行期只允许选择当前 `muz-resource-profile.yml` 已生成的真实资源档位；档位的图片、字体、advance、图标位置与选中框几何由 `PackAssets` 和 `PreviewGeometry.hotbars` 同源提供，不能只改 CSS。保留原版九槽物品栏，不接 BetterHud，不覆盖 `minecraft` 原版 hotbar sprite。要调垂直位置请用当前 scale 的 `offset-y` 覆盖层；保存/重载会重新读取 CE、重建并校验 ZIP，客户端必须重新下载资源包。
- `onDisable` 必须 `stop()` HttpServer，避免端口占用导致下次启动失败。
- `DebugWebServerTest` 现有覆盖 geometry schema、资源 API 对齐、旧几何魔数消失，以及 pointermove 拖动期间不重建 DOM、松手后再刷新；相关资源契约覆盖固定 15 格分层记牌器 fixture、三层记牌器 glyph 的文件/尺寸/码位/YAML 对齐、15 格固定 advance、鸡蛋/水桶/番茄三个独立图标的 20×22 尺寸、4px 间隔、68px 总宽、69 advance 与原版 sprite 禁止项；字体 JSON 校验夹具必须按 `scale + tier×22 + layer` 生成记牌器码位，避免把不同偏移档误判为重复映射；音频契约测试覆盖 125 个 OGG 的数量闭合、非空、Vorbis 流和资源索引引用。1.10.13 新增前端交互改进测试：粘性操作栏 sticky 定位、Ctrl+S/Ctrl+R 键盘快捷键、focus-visible 焦点环样式、成功消息 msg-fade 自动淡出、窄屏溢出防护（main minmax(0,...)、.panel min-width:0、.key word-break:break-all、input min-width:0、.preview max-width:100%，500px 以下字段堆叠）。溢出防护靠 minmax(0,...)/min-width:0/word-break 从源头约束子元素尺寸，不在 body 或 .panel 上用 overflow:hidden 裁切——裁切会创建新的滚动容器导致 .actions sticky 失效；测试通过提取 .panel 和 body 的 CSS 规则内容精确断言不含 overflow:hidden/auto/scroll，防止 sticky 祖先链回归；浏览器 fixture 还必须实际解析并执行生成脚本，当前已覆盖图层选择、坐标输入、拖动结束和 Hotbar wheel 监听器之间的语句分隔，并校验缩放档位映射位于 `renderPreview()` 外部以供事件处理器访问，避免仅靠源码字符串断言漏掉 JavaScript 语法错误；当前全屏编辑器测试还覆盖 `main.mc-editor`、`id='screen'` 舞台、Fullscreen API、右键左右坐标、Shift 微调/视图平移、默认吸附和 requestAnimationFrame 合并；2026-09-12 真实 Chromium 截图已确认全屏 MC 画布、四层 HUD、浮动配置面板以及 hotbar 构建期图标/数字均可见。
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

