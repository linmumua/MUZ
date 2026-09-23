# MUZ — Minecraft 实体化斗地主牌桌

> 在 Minecraft 世界里摆一张真正的牌桌，坐下来打一局斗地主。

## 什么是 MUZ

MUZ 是一个 Paper / Purpur 服务端插件，用 Display Entity 在真实世界坐标上渲染完整的斗地主牌桌。玩家右键坐下，看到手牌一张张发出、翻转排序，然后叫分、出牌、结算——整个过程就像在桌游馆里打牌一样自然。

<img width="480" height="270" alt="动画牌桌演示" src="https://github.com/user-attachments/assets/7ad85657-8eae-438f-a542-f3f0a91be695" />

牌面、头像与记牌器由资源包字形驱动；桌内道具通过服务端九格 GUI 与世界实体效果呈现，不依赖任何客户端模组。

## 核心特性

### 完整的对局体验

发牌时手牌按座位轮流出现，发完后逐张原地翻转 360° 并自动排序。翻转结束后有一个自愿明牌窗口，真人可以选择公开手牌换取额外倍率。整套叫分、出牌、炸弹加倍和结算流程完整可用，人数不够时可使用 `/muz bot add [名字]` 添加机器人补位。

### 高度可自定义的界面

插件内置一个全屏 HUD 编辑器（Debug Web），在浏览器里可以拖动牌行、头像行和记牌器行，预览使用服务端真实资源 PNG。横向偏移即时生效，纵向偏移经过资源重建和校验后安全应用；非法值会被明确拒绝，不会静默吸附到近似档位。

### 桌内道具互动

玩家在出牌阶段打开九格桌内道具箱，自由配置并持久化八个道具槽；第九格固定打开私有气泡语音实体面板。鸡蛋和番茄可朝同桌对手无伤害投掷，水桶生成短暂水幕；目标高亮及所有效果实体会在离桌、关桌或停服时自动清理。

<img width="480" height="270" alt="桌内道具互动演示" src="https://github.com/user-attachments/assets/3d892e41-6503-4f1d-9909-131c945fa701" />

### 沉浸式音效

125 个 OGG 音效覆盖发牌、出牌语音、倒计时和按钮点击。五首背景音乐按局轮播，出牌和提示音正常混音且不会重复叠加。

### 资源包自动生成

Gradle 构建期自动裁切牌面贴图、生成字形码位和 CraftEngine 配置分片。插件侧零文件 IO 复算——构建期和运行期的码位、宽度、档位严格对齐，不一致时构建会报错。

### 配置驱动

渲染偏移、HUD 档位、音效、经济、房间等级、机器人行为全部可配。玩家个人偏好（手牌微调等）走独立存储，不混进全局配置。

## 安装

1. 从 [Releases](https://github.com/linmumua/MUZ/releases) 下载与服务器版本对应的三个文件：
   - 插件 JAR
   - 资源包 ZIP
   - CraftEngine bundle ZIP

2. 将 JAR 放入 `plugins/` 目录。

3. 资源包有两种使用方式：
   - **有 CraftEngine**：将 bundle ZIP 导入 CraftEngine，或让插件检测到 CE 后自动导出
   - **无 CraftEngine**：直接把资源包 ZIP 下发给客户端

> 三个文件必须来自同一个构建目标（如都选 `paper-26.1.2`），不要混用不同目标的 JAR 和资源包。

## 支持的服务器版本

| 目标 | 说明 |
|------|------|
| `paper-1.21.11` | Paper / Purpur 1.21.11 |
| `paper-26.1.2` | Paper / Purpur 26.1.2 |
| `paper-26.2` | Paper / Purpur 26.2（默认构建目标） |

已完成一次性 owner 任务跨 unload/rebind 代次屏障；已建立 `RegionTaskBarrier` 多 region completion barrier 基础设施，并接入 ChunkLoad 的单桌 cleanup barrier 异步链，超时或取消不会在已开始的 region action 返回前发布伪完成结果；但 remove/reload/shutdown 的全量 barrier、`PhysicalTableManager` 清理计划的完整收口和真实 Folia 验证仍未完成。Folia 实际迁移已经开始，但当前仍不是兼容声明：三个目标已建立并使用 `MuzScheduler` 的 global、region、entity/player 与 async 四条 owner lane，预览、Web、皮肤渲染和旧 Hotbar 的调度已收敛到门面。另已新增可测试的 `game/PlayerOutputDispatcher` 与 `PlayerTaskRegistry`；门面目前覆盖 `runPlayer`（即时/延迟）、ActionBar、聊天消息、播放/停止声音、标题、BossBar 显示/隐藏、按玩家显示/隐藏实体、打开/关闭库存、按玩家粒子、玩家命令，以及按 UUID 取消玩家任务。当前已接入 ActionBar、`PlayerConnectionListener` 的资源包消息/warmup，以及桌内音乐、桌内音效、九格道具栏、`GameTable`、`TrickHudService` 和 `PhysicalTableManager` 的主要玩家输出。`GameTable` 对局核心已使用 UUID/name 快照，`TrickHudService` 的头像与记牌器输入已通过不可变 player-lane 快照采集，实体可见性门面已提供 UUID-first 解析；生产 `PlayerOutputDispatcher` 默认构造已通过 global UUID 查找阶段再进入 player owner lane，玩家输出闭包在执行阶段重新解析当前实例；兼容 `runPlayer` 名称仍保留给旧注入注册表。桌内道具箱的语音气泡入口已按 UUID 投递到 player lane，再在该 lane 查询牌桌；九格道具箱最终打开/关闭库存也通过 `PlayerOutputDispatcher` 的 UUID 窄接口执行，库存创建、事件读取和编辑状态仍保留主线程 GUI 边界。尚未迁移全部玩家输出调用点，`DoudizhuPlugin`、`HotbarHudService`、GUI/监听器和麻将相关路径仍可见直接 `Player` API，不宣称 Folia 支持。已放置牌桌的周期任务开始按锚点使用 `runRegionTimer`，未放置的纯逻辑桌暂时保留 global 兼容路径；`PhysicalTableManager.TableOwner` 已覆盖自有实体归属 PDC，`PlacedTable` 另维护锚点 3×3 footprint、chunk-owner 与实体 UUID/owner O(1) 索引，残留清理不再扫描全局 `placedTables` 保护邻桌手牌；ChunkLoad/Unload 事件重绑定与 ChunkLoad 单桌 cleanup barrier 已接入，但区块实际卸载重载仍未验证。ChunkLoad 修复现在先捕获并检查 CleanupPlan；tracked entity 缺失（包括 status/playDetail、牌实体和 CE 根实体）视为已不存在，可提交非空或空 cleanup barrier 并在完成后恢复，只有现存实体无法安全定位或 BlockState/anchor/owner/identity 异常时才保守保留 `placed` 与 footprint/entity 索引等待后续重试。本轮仍只是初步异步接入，不表示真实生命周期收口；运行期 owner 路由、缺失实体恢复、失败重试与代次边界仍待验证。EntityScheduler 清理、实服验收，以及 remove/reload/shutdown 全量清理 barrier 仍未完成。本轮新增 `TableWorldLifecycleListener` 事件接缝：事件层只读取 world UUID/chunk 坐标并经 global 投递，`PhysicalTableManager` 按 3×3 footprint/world 索引切换 unplaced/global，并在 ChunkLoad 通过去重后经 `TableManager.runTableLater` 排入对应桌 owner lane 的延迟修复/刷新；本轮已接入 ChunkLoad 单桌 cleanup barrier 异步链，但区块实际卸载重载、全量清理 barrier 和真实实体清理仍未验证。异步流程同时保留关闭代次/任务代次保护：道具预览会使 stop、保存、离线和 close 前的旧 generation 失效，HUD Web 超时或 close 后会拦截迟到结果；这些边界不等于 Folia 兼容。`PlayerTaskRegistry` 在同一注册锁内完成在线 lookup、接收门禁、UUID 取消代次检查与 register；新增 UUID-first `enqueuePlayer` 入口后，调用线程可只登记 UUID，玩家解析延迟到 player owner lane；`cancel(UUID)` 在锁内递增代次并摘除任务，`cancelAll()`/`close()` 不可逆关闭后续接收。旧 Player scheduler 退休后不自动重排；后端丢弃已排任务时输出也随之丢弃。`PhysicalTableManager` 的 owner tick 只读取连接生命周期维护的不可变在线 UUID 快照；世界桌工具预览先在 player lane 读取玩家物品/视线快照，再将牌桌几何操作投递到对应 table owner。

牌桌锚点与区域线程、GUI/监听器及麻将相关玩家/实体输出、实体生命周期、跨区域状态消息、区块卸载/重载、关闭清理、麻将 owner 路由和真实 Folia 服务端矩阵仍是阻塞项。1.10.50 已补充牌桌 footprint、实体 UUID 索引与区块生命周期事件接缝、数据库 pending write flush、麻将 shutdown 与区块生命周期契约测试；本轮 `GameTable`、`TrickHudService`、`PhysicalTableManager` 主要玩家输出，以及世界桌/手牌 GUI 监听器的玩家反馈已收口到 player lane；麻将桌管理器广播已接入同一门面，session 增加 UUID 快照与代次保护。paper-26.1.2 与 26.2 的相关 Folia 定向回归为 142/142，但这些测试不能替代实际 region 清理屏障。已核对用户提供的独立 Folia 26.1.2 测试端核心和世界目录，现场 plugins 当时没有匹配的 MUZ JAR，且世界非空、无备份，尚未执行 MUZ 的真实 Folia 验收（此后已按用户选择部署 1.10.50 的 paper-26.1.2 JAR 且不备份世界；2026-09-22 真实启动时 MUZ 被 `not marked as supporting Folia` 拒载，插件未载入）。Paper/Leaf 的编译、单测和调度契约不能替代 Folia 验收。当前版本继续保持 `folia-supported: false`，不能直接安装到 Folia。本轮完成一次性 owner 任务跨 unload/rebind 的代次失效与锁外取消，并补充 owner 锚点副本及生命周期契约测试；ChunkLoad 单桌 cleanup barrier 已接入，但 remove/reload/shutdown 的全量 barrier 与真实 Folia 服务端验证仍未完成；不改 Folia 支持状态。本轮继续收口麻将桌实体 owner：`MahjongTableManager.rerender`/`removeVisuals` 各自直接向桌锚点 `runRegion` 提交，重绘带代次+实例身份闸门、删除作为终结操作不带闸门（此前两者共用闸门，而 `removeTable`/`shutdown` 会先注销桌，导致清理被自己的屏障挡掉、实体残留，本轮已修）。`MahjongTableManager.shutdown()` 按 `getServer().isStopping()` 分支：关服只清追踪不在非法 owner 上操作实体（与 `PhysicalTableManager.shutdown` 一致），reload 分支把每张桌登记为 `RegionTaskBarrier.Request` 内联清理并把聚合结果记为日志，新增 `shutdownCompletion()` 句柄且不阻塞主线程。麻将 `muz_mahjong_protected` tag 已并入共享保护链（与牌桌 tag 一起登记在 `TableEntityGeometry.PROTECTED_TAGS`，`PhysicalTableManager.isProtectedEntity` 因此也认麻将实体，破坏保护生效而残留清理仍只认牌桌 tag），并为 `shouldCancelProtectedInteract` 新增麻将右键放行口，麻将入座 Interaction 不再被取消。麻将 runtime/session/entity 的 owner 路由已收口（runtime 幂等关闭并委托管理器、`send` 走 `sendToPlayer`，session 只暴露 UUID 快照与 `AtomicLong` 代次）；麻将**没有持久化实现**（`persistMahjongTable` 是无 I/O 日志桩），唯一 DB 入口 `deletePersistedTable("MAHJONG", …)` 已由 `DatabaseManager.runWrite` 异步执行，故无待迁的持久化 owner 路由，实现麻将持久化属新功能。麻将命令桥玩家反馈仍直发，与整个 `/muz` 命令层一致，属既有命令层技术债。全量 region 清理 barrier 已在斗地主侧补齐 reload 关闭与拆桌两条路径（逐桌锚点 region 请求 + `RegionTaskBarrier` 聚合，见 AGENTS.md），麻将侧沿用自身 completion barrier；真实 Folia 验收仍未完成。本轮三目标强制编译（`compileJava compileTestJava --rerun-tasks`）各 6 tasks 成功，全量 JUnit 随后在 `paper-26.2` 实跑 **1086/1086、162 个容器**通过（修掉了此前 10 个失败：4 处是编码过时机制的断言已替换为更强断言，其余是 `PhysicalTableManager` 构造器裸调 `Bukkit.getOnlinePlayers()` 造成的夹具 NPE；顺带修掉一个真实回归——`PlayerOutputDispatcher` 的解析器漏了在线门禁，离线真人会拿到明牌资格）。三目标发布构建（`clean shadowJar zipResourcePack zipCraftEngineBundle verifyRelocatedSnakeYaml`）各 10 tasks 实际执行成功，9 个 1.10.50 归档经 `build/muz-release-audit.py` 验收 **0 failures**（作者仅 `linmumua`、SnakeYAML 0 unrelocated / 270 relocated、计数 1148/450/467 各目标一致、无原版 hotbar 覆盖）；1.10.50 仍未发布。但 `MahjongTableManager` 的删除/关闭清理仍只有源码契约与编译证据，没有行为级执行证据，真实 Folia 实服验收仍 blocked。本轮又把斗地主侧的 reload 关闭与拆桌清理收口到桌锚点 region（`submitShutdownCleanupBarrier` + `shutdownCompletion()` 完成屏障，拆桌走 `cleanupPlacedTableOnOwnerRegion`），这两条路径同样只有源码契约级证据，`rebuildAllTables`/`rebuildSingleTable`/`shiftAllAnchors` 三条重建路径未动。真实 Folia 验收已取得第一条记录：2026-09-22 在 `folia-26.1.2-8`（Java 26）上启动时，MUZ v1.10.50 的描述文件被正常解析（日志读出 `MUZ v1.10.50`），但被 `not marked as supporting Folia` 拒载——**声明确实拦住了加载**，且拒载不致命（服务端继续 `Initialized 3 plugins`，运行约 4 分钟后干净停服，本轮未复现世界目录锁冲突）；因为插件从未载入，owner 路由、区域调度与清理屏障一行都没执行，所以这**不构成 owner 路由的真实验收**。为取得运行期证据，已按用户选择把源码 [src/main/resources/paper-plugin.yml](src/main/resources/paper-plugin.yml) 的 `folia-supported` **临时**改为 `true` 并构建、部署冒烟 JAR（SHA-256 `bf019d4e…`，现场副本一致）；`FoliaDeclarationMatchesSchedulerTest` 在该状态下**按设计变红**（实跑 2 failed / 2 succeeded，断言未改动、未弱化），验证结束必须改回 `false` 并重建（正确的 `false` 产物已备份到仓库外，SHA-256 `69c540e0…`）。本轮已取得首批真实运行期证据：冒烟 JAR 在真实 Folia 上成功加载（无致命错误），并暴露出两处本插件自身的 Folia 违规——语音面板的 global 扫描取时钟抛 `No currently ticking region`（已改为 global 只派发、取时钟与实体操作落在 player lane），以及 `/muz bot add` 的同步传送抛 `Must use teleportAsync while in region threading`（4 处已改 `teleportAsync`）。日志中另 2 类异常经核实属 CraftEngine/Java 26 与 Folia 原生命令问题，不属于本插件。复核日志另发现同一类的**第二处实例**：道具效果与目标高亮的 global 周期任务（`TableGadgetService.tick` → `TableGadgetEffectService.tick`）同样在 global lane 上取时钟，且因调度器在异常时会取消周期任务，该功能在真实 Folia 上实际一直静默失效；该处已一并修复：`TableGadgetService.tick` 改为只派发（效果推进投桌锚点 region、目标高亮投 player lane），效果服务的 tick 改为按桌推进并在取时钟前判空，跨 lane 状态改并发容器；但发光的失效清扫仍无本地行为测试覆盖，需进服确认没有残留发光。新增 `FoliaRuntimeLaneContractTest` 钉住上述 lane 规则，并补修 `GameTable` 关桌与回大厅从不清理语音面板的既有泄漏。新冒烟 JAR SHA-256 `a833aacc796d557953128683c5352a163764fa0a5666bffd52b08e759953f5e9` 已部署到测试端（上一版保留为 `.jar.bak`）。运行期验证（2026-09-23 会话，`ab2ba8b2` 产物）：三处修复**全部通过**——日志中 `No currently ticking region` 与 `teleportAsync while in region threading` 均为 0 次，MUZ 正常加载并 100% 就绪。同一批日志暴露两处新问题：`PhysicalTableManager.ensureChunkReady` 的阻塞区块加载被 Folia 拒绝（存档牌桌恢复失败、长期处于待重试）；`onDisable` 内注册任务抛 `IllegalPluginAccessException`，使关闭链从该步骤起被跳过（Folia/Paper 在插件禁用后拒绝一切任务注册）。本轮在同一批真实 Folia 日志与独立审查基础上再修三处：`PhysicalTableManager.ensureChunkReady` 的阻塞区块获取（`Async chunk retrieval`，改用 `ensureChunkLoadedAsync` + 锚点 region 世界体、恢复流程改异步派发 + in-flight 去重）、`onDisable` 注册任务抛 `IllegalPluginAccessException`（`MuzScheduler` 注入 `shuttingDown` 关门、关闭期返回已取消句柄且每代次一次 WARNING，并恢复 `DatabaseManager.runWrite` 的关服同步回退）、放桌预览路径 `placementBlockedBlocks` 残留的 `getChunk()`（改只读 `isChunkLoaded`，`FoliaRuntimeLaneContractTest` 新增 `PhysicalTableManager` 禁 `getChunk()` 契约）。三目标 `compileJava compileTestJava --rerun-tasks` 各 6 tasks 成功；`paper-26.2` 全量 JUnit 实跑 **1097 项 / 164 容器 / 1095 通过**，2 项为 `FoliaDeclarationMatchesSchedulerTest` 在临时 `true` 下按设计变红（断言未改），并修掉一个 `TablePeriodicTaskLifecycleFixtureTest` 夹具 GC flaky。新冒烟 JAR SHA-256 `571880329dc3940f3db457418c7f38aa4d7d6a439e679fb4f9b9552f5cf7a252`（JAR 内仍 `folia-supported: true`）。**未验证**：三处修复尚无运行期验证（本轮未重启测试端、未取新日志），`getChunkAtAsync` 回调线程与 region 投递合法性只能靠下一轮实服冒烟确认；`rebuildAllTables`/`rebuildSingleTable`/`shiftAllAnchors` 三条重建路径仍未迁移（区块预热改只读后失去「执行前锚点区块已加载」保证，子代理评估后主动停手）；未部署、未重启、未做客户端/资源包验收。

2026-09-23 核心判定纠错：`PhysicalTableManager` 原先用「`RegionizedServer` 或 `TickRegions` 类存在」判定区域化核心，并据此断言这两个类都不在 Paper/Leaf 核心 JAR 里——**该断言是错的**。现场 `E:\我的世界插件\folia测试端\versions\26.1.2\leaf-26.1.2.jar` 打进了一个 484 字节的 `io/papermc/paper/threadedregions/TickRegions.class` 兼容桩、却**没有** `RegionizedServer`，于是 Leaf 被误判为区域化核心，`restoreTable` 的同步预热分支不再执行、`ensureAnchorChunkLoaded` 退化为只告警。反编译两份内核得到可用判据：Folia 的 `ServerBuildInfo.isBrandCompatible(Key.key("papermc","folia"))` 返回 true，Leaf 返回 false。判定现收口到新类 `linmumua.doudizhu.world.RegionizedCoreDetector`（官方品牌接口优先；仅在品牌不可用时回退到**只认 `RegionizedServer`** 的类存在性；provider 缺失静默降级、类加载失败不静默当作非区域化），预热逻辑抽取为包内可见静态核心 `ensureChunkReadyCore` / `ensureAnchorChunkLoadedCore` 以便用 fake World 做真实行为测试。本轮**不改** Folia 支持状态（`folia-supported` 仍为临时 `true`、对应守护测试按设计失败），也未改动调度/对局/存储/部署现场。另需如实说明：截图中桌椅可见、卡在「正在发牌」；**不得**把「锚点区块未加载」的 WARN 直接当成该现象的已证根因——本轮只证明核心判定被 Leaf 误判，开局链另有只读调查在进行。

2026-09-23 补充部署记录：测试端 `E:\我的世界插件\folia测试端` 里**同时存在** `folia-26.1.2-8.jar` 与 `leaf-26.1.2-3.jar`。此前 00:45 会话的冒烟用的是 Folia，而 02:07 会话实际启动的是 **Leaf**（`Loading Leaf 26.1.2-3-ver/26.1.2@3c591d7`，API `26.1.2.build.3-alpha`）；该次部署的仍是本轮修复前的冒烟 JAR `ab2ba8b2ac6839b5b9cb093c8f314d97499e1ff7eed18fcfea67af6e89536298`。实测：MUZ 在 Leaf 上正常加载（`Enabling MUZ v1.10.50` → 100% 就绪，Leaf 未读 `folia-supported` 元数据故未拒载），**存档牌桌恢复成功**（`已恢复牌桌: 斗地主 1 张 | 牌桌恢复完成，本次恢复 1 张`），视觉预热重建执行到 pass=1 / pass=2 且无异常，全日志 0 次 `Async chunk retrieval` / `No currently ticking region` / `teleportAsync while in region threading` / `Thread failed main thread check`（Leaf 栈中出现 `FoliaGlobalRegionScheduler` 与 `leaf-26.1.2.jar`，说明 Leaf 提供 Folia 兼容调度器层）。**同一次 Leaf 运行的关闭阶段（02:09:24）复现了完全相同的两处失败**（`关闭桌内业务入口` 与 `关闭斗地主牌桌`，均为 `IllegalPluginAccessException: Plugin attempted to register task while disabled`），说明该关闭链缺陷不是 Folia 特有、Leaf 同样受影响，本轮 `MuzScheduler` 的 `shuttingDown` 闸门对两个核心都是修复（**待验证**）；CraftEngine HUD 重载在 Leaf 上同样报 `NoSuchMethodError: CraftEngine.reloadPlugin(Executor, Executor, boolean)`，属环境/CE 版本不匹配、与核心类型无关。本轮新冒烟 JAR（SHA-256 `571880329dc3940f3db457418c7f38aa4d7d6a439e679fb4f9b9552f5cf7a252`）已部署到测试端 `plugins/MUZ-1.10.50-paper-26.1.2.jar`（副本哈希与构建产物一致），上一版 `ab2ba8b2…` 保留为 `.jar.bak`。需如实说明：新冒烟 JAR 尚未在任何核心上运行过，异步恢复的时序变化在 Leaf 与 Folia 上都待实服确认，`rebuildAllTables` / `rebuildSingleTable` / `shiftAllAnchors` 仍在 global lane 做世界/实体操作；任何核心的实服验收都不构成 Folia 支持声明，正式产物必须继续为 `false`。

2026-09-23 重建路径异步化：`PhysicalTableManager.rebuildAllTables` / `rebuildSingleTable` / `shiftAllAnchors`（含 `spawnTable` 预热）已改为固定顺序的 lane 流水线——调用 lane 冻结重建快照并摘除索引（**勘误（2026-09-23）：后续已改为「重建在收口成功提交前保留旧放置快照与索引」，不再摘除索引，详见下文「重建路径状态保护与闸门前移」。**）、`ensureFootprintLoadedAsync` 异步加载旧/新锚点各自的 3×3 footprint（单锚点恢复链 `ensureChunkLoadedAsync` 保持单区块不变）、旧锚点 region 清旧实体、新锚点 region 扫残留并生成新桌、global 收口才写入 `placedTables` 与 footprint/实体索引并重绑 owner 周期任务、最后由锚点 region 刷新世界体；非并发 Map 只由调用 lane 与 global 收口写，整批顺序化以避免并发读写同一批 Map，单桌失败只记日志并继续后续桌。`DoudizhuPlugin` 的视觉预热改为 `rebuildAllTables` 完成后串接 `repairIncompleteTables`，reload / shift / post-restore 三处补失败日志；`syncViewerOnOwner` 拆为「投递重建」与「重建完成后回本桌 owner lane 同步」。新增两个行为级回归 `FootprintChunkPreloadCoreBehaviorTest`（fake World 覆盖 3×3 精确请求、九区块全部到达才完成、任一失败即整体失败、不碰同步区块 API）与 `RebuildPipelineLaneOrderBehaviorTest`（记录型调度后端证明加载先于 owner region 世界体、加载失败零投递、清旧/生成各投自己锚点），并升级 `PhysicalTableChunkLifecycleContractTest` / `ChairHitboxRestoreTimingTest` 中因机制变化而过时的同步顺序断言（不弱化 shutdown/remove/repair 契约）。`paper-26.2` 全量 JUnit 实跑 1123 项 / 168 容器 / 1121 通过，2 项失败为 `FoliaDeclarationMatchesSchedulerTest` 在临时 `folia-supported: true` 下按设计变红（断言未改）。**本轮没有真实 Folia / Leaf 运行期验证**，未构建发布、未部署、未重启测试端；`folia-supported` 仍为临时 `true`，正式产物必须继续为 `false`。

2026-09-23 重建路径状态保护与闸门前移：修复上一轮异步重建的 4 项审查问题，只改 `PhysicalTableManager` / `DoudizhuPlugin` 与必要测试。(1) 重建不再在异步 stage 之前 `placedTables.clear()` / `remove` + 摘索引 + 切回未放置——旧 `PlacedTable` 与 footprint/实体索引一直保留到 global 收口成功提交，避免「桌子短暂不存在」窗口里被 `TableManager.cleanupIfEmpty` 注销（永久丢桌），失败/被拒也原地保留旧状态；同桌互斥改由新增并发集合 `rebuildingTableKeys` 承担，并新增世界体门禁让 `refresh` / `tickTable` 等会创建实体的路径在重建期间让位（重建收尾的那次刷新走 `refreshWith` 直取刚提交的新桌）。(2) 身份/关闭/放置代次检查抽成单一判据 `rebuildGateRejection` 并**前移到 spawn 之前**（拒绝时不生成任何新桌）；收口处复用同一判据，收口拒绝或抛异常时把已生成的新桌经 `dispatchRebuildCleanupOnOwnerRegion` 投回**它自己的新锚点 region** 清理，失败只记日志不打断整批；流水线末尾无条件撤掉在飞标记。(3) warmup / post-restore 的每个 pass 门禁从 `placedTableCount()` 改为 `hasPlacedOrRebuildingTables()`，不再因「尚未收口提交」而整批跳过后面的预热 pass。(4)(5) 只改文档：三份非并发 Map 的边界措辞与 `onDisable` 的 reload 清理分支描述已就地勘误——重建流水线**自身**的收口写入由调用 lane + global，既有 owner/交互路径仍在各自 owner lane 读写同一批 Map（统一并发边界属待偿技术债）；`onDisable` 期间 `MuzScheduler` 拒注册任务，reload 分支实际不会执行任何 region 清理，不宣称关闭清理生效。验证：`paper-26.2` `compileJava compileTestJava --rerun-tasks` 成功；定向 16 类 **96/96 通过**；全量 JUnit **1127 项 / 168 容器 / 1125 通过**（2 项失败为 `FoliaDeclarationMatchesSchedulerTest` 在临时 `folia-supported: true` 下按设计变红，断言未改）。**无真实 Folia / Leaf 运行期验证**，未构建发布、未部署、未重启测试端；`folia-supported` 仍为临时 `true`，正式产物必须继续为 `false`。

## 依赖

| 依赖 | 类型 | 说明 |
|------|------|------|
| Paper API | 必需 | 目标版本由构建参数决定 |
| CraftEngine 0.0.67 | 可选 | 资源包与家具渲染 |
| PlaceholderAPI 2.12.2 | 可选 | 变量支持 |
| VaultAPI 1.7 | 可选 | 经济桥接 |
| CMI / EzEconomy / XConomy | 可选 | 经济 provider 检测 |

所有可选依赖缺失时自动降级，不会导致启动失败。

## 命令

### 玩家命令

| 命令 | 说明 |
|------|------|
| `/muz` | 打开主菜单 |
| `/muz bot add [名字]` | 向当前牌桌添加机器人 |
| `/muz bot remove [数字id]` | 移除机器人 |

### 管理命令（需要 `muz.admin` 权限）

| 命令 | 说明 |
|------|------|
| `/muz reload` | 重载配置（同步 Debug Web 生命周期） |
| `/muz debug add [数量]` | 生成自动对局观察桌（跳过方块占用检测，仅供测试） |
| `/muz debug remove [1-50\|all]` | 移除观察桌 |
| `/muz debug web [start\|stop]` | 管理 Debug Web HUD 编辑器 |
| `/muz give debug` | 发放个人 HUD 调试棒 |
| `/muz chip setitem` | 将主手物品设为实体筹码匹配模板，不兑换或发放物品 |
| `/muz chip balance <玩家> [数量]` | 查询实际筹码；指定数量则真实增删至该非负数量，容量不足则拒绝 |

`/muz debug trace` 已移除，不再提供执行入口和命令补全。

## Debug Web HUD 编辑器

在 `config.yml` 中开启 `debug.web-ui.enabled: true` 并重载，从服务端本机访问 `http://127.0.0.1:2000` 即可打开编辑器。

<img width="1193" height="675" alt="Debug Web HUD 编辑器" src="https://github.com/user-attachments/assets/7dada293-fcd2-4026-97d7-649d5c6948d6" />

编辑器提供一个 640×360 的 Minecraft 逻辑画布，牌行、头像行与记牌器行分别为可拖动图层。预览使用服务端真实资源 PNG，不是 CSS 假图。左键拖拽移动图层，右键查看坐标，Shift+方向键精确微调。

共 18 个可编辑的 Trick HUD 运行期参数。横向偏移即时生效；三个纵向偏移会触发资源重建、CraftEngine 重载和 ZIP 校验，客户端需要重新下载资源包才能看到变化。任何一步失败都会自动回滚。

## 构建

当前源码版本为 `1.10.50`，并与 `AGENTS.md`、`CLAUDE.md` 的版本约定同步。本轮已修复调度生命周期、玩家任务登记竞态，以及牌桌先创建后放置时的周期任务 global→region 重绑定；放置/恢复绑定失败会传播错误并清理已生成实体与本次新建逻辑桌。paper-1.21.11、paper-26.1.2 与 paper-26.2 均已强制编译源码、测试与资源处理成功；paper-26.1.2 与 paper-26.2 的 Folia owner、玩家门面、跨区域和关闭定向回归均为 101/101，通过且无跳过、失败或中止；`onDisable()` 已接入有序关闭、数据库 pending write flush 与调度门面关闭。paper-26.1.2 的 1.10.49 JAR、资源包和 CraftEngine bundle 已重新构建并通过 SnakeYAML 重定位校验，但未部署。本轮已将桌内音乐、桌内音效、九格道具栏，以及 `GameTable`、`TrickHudService`、`PhysicalTableManager`、世界桌/手牌 GUI 监听器和麻将桌广播的主要玩家输出迁移到 `PlayerOutputDispatcher` 的 player lane；异步道具栏刷新增加停止、退出和代次门禁；`CeActionExecutor` 的玩家消息、ActionBar、标题、玩家命令和玩家声音执行路径，以及消息/ActionBar/标题/声音预览路径均已走该门面，控制台命令仍走 global + console；其它直接 `Player` API 尚未全部迁移。`PlayerTaskRegistry` 现以注册锁、UUID 取消代次和不可逆 close 门禁防止 lookup/register/cancel 竞态，旧 Player scheduler 退休后不自动重排。`GameTable` 的开局、机器人、超时、AI 与延迟回调统一经 `TableManager` 的 table-owner 门面，已放置桌按锚点走 region，未放置桌才走 global；已放置桌的世界刷新拆为单桌 owner tick，MUZ 自有实体写入 owner PDC 并在清理时严格匹配。尚未迁移全部玩家/实体 API、麻将 owner 路由、remove/reload/shutdown 的全量 cleanup barrier 或真实 Folia 测试端；ChunkLoad 单桌 cleanup barrier 已接入；缺失 tracked entity 是可恢复场景，可提交非空或空 barrier，只有现存实体无法安全定位或 BlockState/anchor/owner/identity 异常才保留 `placed` 与 footprint/entity 索引并等待后续重试。本轮仍只是初步异步接入，不表示真实生命周期收口；运行期 owner 路由、缺失实体恢复、失败重试与代次边界仍待验证，`folia-supported: false` 继续保持。

历史 `1.10.47`：筹码按玩家实际持有的匹配物品计数，不再使用独立虚拟余额；独立 `muz_gadget_bar` 九格图标额外栏与私有语音面板保持不变。本轮确认 Folia 实际迁移已经开始：`MuzScheduler` 已承载 global、region、entity/player、async 四条 owner lane，预览、Web、皮肤渲染和旧 Hotbar 已收敛到调度门面；道具预览与 HUD Web 同时具备关闭代次/任务代次保护，能拒绝 stop、保存、离线、超时或 close 后的迟到结果。牌桌、实体生命周期、跨区域玩家输出、麻将 owner 路由和真实 Folia 服务端验收尚未完成，因此仍保持 `folia-supported: false`。本次按 paper-26.1.2 目标强制执行 `shadowJar verifyRelocatedSnakeYaml --rerun-tasks`，7 项任务实际执行成功，并核验归档 CRC、无重复条目、版本、作者、API、Folia 声明及复制前后 SHA-256 一致；JAR 已按要求放入独立 Folia 测试端 plugins 目录。本次未重新执行单测、未启动服务端或进行 Folia 实服验证；文件复制不代表可加载或兼容，`folia-supported: false` 仍保持不变。

本轮修复 ChunkLoad 修复路径的周期任务永久失效：`PhysicalTableManager.repairTableAfterChunkLoad` 原先在摘除放置状态前调用 `TableManager.cancelOwnerPeriodicTasks`，而该方法会**永久摘除**三个 `TablePeriodicTaskRegistry` 里的条目（`entries.remove` + `cancelled=true`），导致后续 `rebind` 只能返回 `false`：开局发牌 timer（`GameTable → TableManager.runTableTimer`）没有任何重新注册路径，修复一次就把发牌时间线永久打死；`tickActionBar` 消失还会让修复收口的 `notifyTableAnchorBinding` 抛异常并跳过刷新。现在只摘除放置状态，离开旧 owner lane 交给 `removePlacedTableIfSame → markTableUnplaced` 的原子 rebind（带 `bindingGeneration` 门禁）完成，周期任务被保留并在修复成功后重绑到新锚点。验证：`paper-26.2` 强制 `compileJava compileTestJava --rerun-tasks` 成功（6 tasks executed）；新增行为级测试 `ChunkLoadRepairPeriodicTaskSurvivalBehaviorTest`（记录型调度后端直接驱动生产修复入口与收口重绑）加定向回归共 **64/64** 通过（11 容器，无失败/跳过/中止），该行为测试在旧实现上实跑变红（`expected: <1> but was: <0>`）；全量 JUnit **1129 项 / 169 容器 / 1127 通过**，2 项失败仅为 `FoliaDeclarationMatchesSchedulerTest` 在临时 `folia-supported: true` 冒烟状态下按设计变红（断言未改动）。本轮只做本地测试，未打包、未部署、未重启测试端，也未取得实服运行期证据；截图中"正在发牌"卡住现象与本次缺陷的因果关系**未证实**。

本轮定位并修复开局卡在「正在发牌」的根因：真实 Folia 与 Leaf 的 Global/Region `runAtFixedRate` 拒绝初始延迟 `<= 0`（`Initial delay ticks may not be <= 0`），而开局发牌 timer 以 `runTableTimer(0L, 1L)` 注册；异常在牌桌已进入 DEALING 之后抛出，并被按钮交互层转成玩家提示、不写服务端日志，因此牌桌停在发牌阶段且日志无堆栈。`PaperSchedulerBackend` 现把 Global/Region 周期任务的初始延迟钳到至少 1 tick（与实体/玩家分支一致）。新增行为测试 `PaperSchedulerBackendTest.repeatingZeroDelayIsClampedBecauseFoliaRejectsNonPositiveInitialDelay`，回退修复时实跑变红；`paper-26.2` 全量 JUnit 1130 项 / 1128 通过（2 项为临时 `folia-supported: true` 下按设计变红的声明守护测试）。尚未打包部署，开局能否推进到明牌/叫分仍需实服冒烟确认。

本轮在 Lophine（Folia 系）实测后修复两处区域线程报错：桌子区块卸载后视角同步落到 global 线程读实体（`Cannot getEntities asynchronously`），以及玩家线程对另一 region 的实体调用显示/隐藏（`Accessing entity state off owning region's thread`）；两处都改为不在所属 region 就跳过。同时删除出牌阶段桌上的「道具」按钮，按钮改为「提示 / 不要 / 清选」三个，默认道具仍为鸡蛋、水桶、番茄与第九格聊天气泡。

本轮加固开局发牌：发牌过程中实体或提示栏渲染出错不再让发牌停住，报错也改为限频记录，不会每 tick 刷屏。另修复对局中九格道具栏被染成橙色的问题，恢复为贴图原色。头像行保持两侧小、中间大，中间为当前出牌者。

本轮让九格道具栏在屏幕上固定不动：修正道具栏字形宽度（此前每格宽度随图标不同而变化），并让提示文字以道具栏中线居中叠加、不再挤动道具栏。客户端字体宽度无法精确测量时，提示文字改为聊天栏显示（每人 10 秒最多一次）。此项需要重新下载资源包。

本轮进一步把九格道具栏从 ActionBar 独立出来，并入出牌 HUD（牌行 / 头像行 / 记牌器行）作为同一条 BossBar 的第四行：栏不再与对局状态提示抢 ActionBar 槽位来回覆盖闪烁，位置由构建期固定下移档决定、开局后不随状态变化移动，ActionBar 从此只给普通状态提示用。九格栏竖直位置是固定的资源档（无新增配置项），需要重新下载资源包才能看到第四行。

本轮修复真实 Folia 系服务器上桌内道具「投掷」（鸡蛋/番茄/水桶）会卡住且没有日志的问题：跨 region 放弃投掷改为限频记录日志，牌桌 owner lane 读不到时钟时只跳过本轮而不打断效果，跨 region 音效不再把整个成功投掷打成失败，道具扫描任务不再被单桌异常打掉（效果生成与推进统一到牌桌锚点 region、先摘状态后删实体、`teleportAsync` 等 lane 收口此前已完成）。新增行为级回归 `TableGadgetEffectReleaseBehaviorTest`（5 项，含 mutation 反证）。本轮未打包部署，实服复现与验收待做。

历史 `1.10.43`：移除 `/muz debug trace`；26.1.2 强制编译通过，命令移除、调试棒与内部日志契约定向测试 10/10 通过；未运行全仓测试，未打包部署。

历史 `1.10.42`：道具预览及牌行、头像、记牌器和道具图标统一复用带 Token 的图片加载与 Object URL 缓存，保留 `no-referrer` 和严格 GET 鉴权，CSP 仅在图片来源中补充 `blob:`；正式两份页面与兼容模板同步。26.1.2 强制编译通过；全量后端 889/889、最终浏览器与后端专项联合 52/52 通过，无失败、跳过或中止。浏览器使用生产安全响应头与严格 Token 夹具，确认无来源头请求带 Token、blob 图标实际加载；不是实服 CE 联调。未打包发布、未部署，其他目标和客户端显示未验收。

历史 1.10.41：收紧道具预览的同源访问校验，并隔离保存后的预览通知异常；新增可控调度的异步快照回归与保存通知异常注入测试，26.1.2 强制编译及专项测试 50/50 通过，无失败、跳过或中止（GUI 续接为源码契约，非实服交互验收）。1.10.41 未运行全仓和浏览器回归、未打包发布或部署，仍有 Unsafe/API 与独立测试启动器注解依赖警告。Origin 必须为精确 HTTP 同源且不含路径，Referer 可包含页面路径。以下验证结果属于 1.10.40：新增与游戏内道具箱同源的 Web 只读预览，不恢复旧 Hotbar HUD。保留 1.10.39 根目录连续字体无需 `overlays` 声明的修复，字形、PNG 与资源格式校验保持严格。26.1.2 强制编译、定向回归 54/54、全量 JUnit 881/881 与真实 Chromium 契约 2/2（含进程清理）通过，无跳过、中止或失败；JAR、资源包与 CraftEngine bundle 已构建，并通过 SnakeYAML 重定位、归档 CRC、页面及图标一致性检查。其它目标本轮未构建，未部署或重启实服。道具预览以 SnakeYAML 严格只读方式读取玩家文件，损坏时提示失败而不重命名或补写原文件；实服保存、客户端下载和游戏内显示仍需独立验证。头像重叠警告只是布局建议，本版未去重该警告。

```bash
./gradlew.bat -PmuzTarget=paper-26.1.2 clean shadowJar zipResourcePack zipCraftEngineBundle verifyRelocatedSnakeYaml
```

可选目标：`paper-1.21.11`、`paper-26.1.2`、`paper-26.2`。产物位于 `build/<targetId>/`。

构建期尺寸由根目录 `muz-resource-profile.yml` 控制。运行期支持牌行、头像行、记牌器行三层连续 Y 偏移覆盖层，不需要为每个纵向位置生成独立资源档位；旧 Hotbar 字形资源链不再生成，升级导出时会主动清理遗留 YAML、字体 PNG 与原版 Hotbar sprite。

## 技术栈

- **语言**：Java（Kotlin 仅用于 Gradle DSL）
- **框架**：原生 Paper `JavaPlugin` + TabooLib（工具层，不用注解生命周期）
- **渲染**：Display Entity + MiniMessage / Adventure
- **配置**：SnakeYAML 2.6（已 relocate 打进 JAR）
- **存储**：SQLite（默认） / MySQL（可选）
- **构建**：Gradle + Shadow Plugin，产物按目标版本隔离
- **测试**：JUnit 5，127 个测试类

## 致谢

判型与流程参考了 [tml104/-Minecraft-Dou-Dizhu](https://github.com/tml104/-Minecraft-Dou-Dizhu) 的状态机思路，借鉴了 [Arbousier1/MahjongEngine](https://github.com/Arbousier1/MahjongEngine) 的 Paper 1.21.11+ 与 CraftEngine bundle 构建路线。
