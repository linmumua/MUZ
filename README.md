# MUZ — 把斗地主牌桌真正搬进 Minecraft

MUZ 是面向 Paper / Purpur 的实体化斗地主插件。它不是把规则塞进箱子 GUI，而是用 Display Entity、资源包字形与 CraftEngine 家具在世界里搭出一张真正可坐、可看、可点击、会动的牌桌。

> **高度可自定义 × 动画化牌桌 × 资源包驱动 HUD**

**当前源码版本**：`1.10.37`（三个支持目标均已完成干净构建）<br>
**作者**：linmumua  
**平台**：Paper / Purpur `1.21.11`、`26.1.2`、`26.2`（不支持 Folia）

## 核心亮点

### 高度可自定义

- 牌面高度、手牌间距、悬停幅度、桌面与按钮位置、头像、记牌器、三道具 HUD、音效、背景音乐、机器人、经济与房间等级均由配置驱动。
- 管理员可通过 `/muz admin` 管理模型、渲染、音频与机器人；玩家可通过 `/muz settings` 单独调整自己的手牌显示。
- 内置全屏 Debug Web 编辑器，提供 **22 个 HUD 运行期字段**、四层独立拖动、逻辑屏幕边界、坐标提示与真实资源预览。横向偏移即时生效，四层纵向位置通过连续覆盖层重新生成并校验资源包。
- 构建资源由 `muz-resource-profile.yml` 控制，只允许使用实际生成的尺寸档位，不用 CSS 缩放或静默近似冒充客户端效果。

### 动画化牌桌体验

- 开局按座位顺序分批发牌，牌面依次出现；发完后逐张原地翻转 **360°**，转到 180° 时完成排序，再自然进入明牌与叫分流程。
- 手牌支持悬停、选中、清选与点击出牌；桌边按钮、上一手牌、头像、记牌器和 ActionBar 会随对局状态动态更新。
- 鸡蛋、番茄采用无伤害投掷动画，水桶生成短时水幕；目标高亮、冷却、实体限额和离桌清理均由桌内互动服务统一管理。
- 五首背景音乐按局维护会话与轮播，普通提示音、出牌语音和倒计时可正常混音，并避免重复事件造成声音叠加。

### 真正存在于世界中的斗地主

- 三人入座、准备、发牌、明牌、叫地主、加倍、出牌、炸弹倍率与结算形成完整闭环，支持机器人自动补位。
- 手牌、背面牌、座位信息、按钮和悬浮 HUD 都是世界实体；开局后会对入座真人隐藏遮挡视线的「桌边动态」，旁观者仍可正常观看。
- 支持 SQLite / MySQL 持久化，可选接入 Vault、PlaceholderAPI、CraftEngine 与 OpenAI 兼容 AI 接口。
- Gradle 自动生成插件 JAR、资源包 ZIP 与 CraftEngine bundle；当前资源包含 125 个 OGG 音效，并校验字体、PNG、码位、CRC 与禁止覆盖的原版 Hotbar 资源。

## 功能概览

- 3 人实体牌桌与 `low / mid / high / fun` 四种场次
- 完整斗地主牌型、提示、不要、倍数与结算
- 动画发牌、360° 翻转排序与自愿明牌窗口
- 悬浮 Trick HUD：上一手牌、三头像、固定 15 格分层记牌器
- 三道具 HUD：鸡蛋、水桶、番茄与虚拟选中高亮，不覆盖原版 9 槽
- 管理员 GUI、玩家个人微调、Debug Web 可视化 HUD 编辑器
- 机器人补位、经济桥接、PlaceholderAPI 变量、战绩持久化与可选 AI 决策

## 命令

| 命令 | 说明 |
|------|------|
| `/muz create <场次> [id]` | 创建并放置牌桌（场次：`high/mid/low/fun`） |
| `/muz create <类型> <场次> [id]` | 指定游戏类型创建牌桌 |
| `/muz set <牌桌id> <场次>` | 修改已有牌桌的场次 |
| `/muz remove <牌桌名>` | 移除牌桌 |
| `/muz chip mode <gold\|chip>` | 切换结算模式 |
| `/muz chip setitem` | 设置筹码物品 |
| `/muz chip balance <玩家> [数量]` | 查看/设置筹码余额 |
| `/muz bot <add\|remove> [名字]` | 管理机器人 |
| `/muz list` | 列出所有牌桌 |
| `/muz status` | 查看当前状态 |
| `/muz settings` | 打开个人微调菜单（不在牌桌也能用；`/muz labels` 为兼容别名） |
| `/muz admin` | 管理员配置菜单（需 `muz.admin`） |
| `/muz reload` | 重载配置并刷新牌桌 |
| `/muz forceend` | 强制结束当前对局 |
| `/muz history [玩家] [页码]` | 查看历史战绩 |

### 调试命令

| 命令 | 说明 |
|------|------|
| `/muz debug add [数量]` | 生成自动对局观察桌（跳过方块占用检测，仅供测试） |
| `/muz debug remove [1-50\|all]` | 移除观察桌 |
| `/muz debug web` | 查看 Debug Web 面板状态与访问地址（需 `muz.admin`） |
| `/muz debug web start` | 启动 Debug Web 面板 |
| `/muz debug web stop` | 停止 Debug Web 面板 |
| `/muz give debug` | 发放个人 HUD 调试棒 |

## 1.10.37 自定义动画牌桌体验更新

本次 GitHub 更新汇总了自上一公开版本以来的牌桌动画、可视化自定义、HUD 资源与稳定性改进。

### 动画亮点

- 新增分批发牌时间线：按座位轮流发牌，全部发完后逐张原地翻转 360°，并在半程完成牌序切换。
- 新增独立明牌窗口，真人可自愿明牌使整局最多 ×2；动画结束后再进入叫分，避免阶段交互串线。
- 完善鸡蛋、番茄投掷、水桶水幕、目标高亮与背景音乐轮播，让牌桌不再只是静态展示。
- 开局后对本桌入座真人隐藏「桌边动态」浮空字，减少低头看牌时的遮挡；大厅和旁观视角保持可见。

### 自定义亮点

- Debug Web 升级为全屏 Minecraft HUD 编辑器：支持 22 个运行期字段、牌行/头像/记牌器/三道具四层独立拖动、连续纵向坐标、逻辑屏幕边界与真实 PNG 预览。
- Trick HUD、头像、分层记牌器和三道具 Hotbar 共用经过校验的资源几何；配置值不再静默吸附到近似档位。
- 横向偏移可即时应用；纵向偏移会安全写入覆盖层，触发 CraftEngine 重载、资源包生成与实际 ZIP 校验，失败自动回滚。
- 保留管理员 GUI、玩家个人手牌微调、资源 profile、音频、机器人、经济和房间等级配置入口。

### 构建与验证

- `paper-26.1.2` 强制编译后，独立全量 JUnit 实跑 **894/894** 通过，124 个容器全部成功，无跳过、中止或失败；其中 `PlayDetailVisibilityTest` 3/3 通过。
- `paper-1.21.11`、`paper-26.1.2`、`paper-26.2` 均已干净执行 `clean shadowJar zipResourcePack zipCraftEngineBundle verifyRelocatedSnakeYaml`，全部构建成功并生成 JAR、资源包 ZIP、CraftEngine bundle。
- **仍需实服确认**：发牌翻转观感、客户端四层 HUD 最终位置、开局后按玩家隐藏浮空字，以及离桌/回大厅后的恢复效果。本次未部署或重启服务器。

## 1.10.36 右键提示与字体度量修复（73项定向通过，已替换JAR待重启）

- 2026-09-19 已完成26.1.2干净构建与SnakeYAML重定位检查，13项任务实际执行；重新实跑四类定向测试73/73通过，无失败、跳过或中止，三个归档与页面/字体内容核验通过。
- 已部署至 `E:\我的世界插件\斗地主\plugins\MUZ-1.10.36-paper-26.1.2.jar`，旧1.10.35备份为 `.jar.bak`，仅保留一个可加载MUZ JAR；副本同时位于 `C:\PluginLibs`。源文件与两处副本SHA-256均为 `844654c27a2bfb3966f3e9f3f9b8c8059655a6bbf20bab5adf2e6aa5e64719df`。
- 未重启服务端、未更改现场配置或CE资源；重启加载新版后仍需确认资源应用与游戏内显示，不能据此认定漂移解决。本轮未构建其他目标或运行全仓/Chromium测试；下方未打包部署说明为此前验证阶段记录。

- 26.1.2 最终强制编译及资源处理8项任务实际执行成功；字体度量、Hotbar布局、推送诊断与Web四类回归73/73成功，5个容器全部成功，无失败、跳过或中止。未运行正式Chromium、全仓测试，未打包发布或部署；尺寸档位未扩展，实服漂移仍待确认。
- 修正 bitmap 默认高度与字体选项组合检查；未知字体能力不再误用后置字宽。不同客户端字体选项存在宽度分歧时，沿用固定图标并将正文限频转聊天的降级方式，而非猜测补偿宽度。
- 首轮失败的真实字体测试已按资源证据修正：混合文本普通/Unicode宽度不同，必须拒绝确定测宽；另保留中文36px精确断言。上述本地反例不等同于实服漂移根因确认。

- 右键坐标框移除 `card` 等内部标识，保留坐标与牌行显示。
- 当前牌、记牌器、三道具均只生成一个尺寸档，因此缩放手柄不能改变这些图层的实际尺寸；新增真实尺寸必须重建资源包，本轮尚未扩展。
- 已从现场启动日志确认1.10.35曾运行；用户反馈为游戏内三道具随头像左右移动，目前尚无客户端复现证据确认最终根因。服务端上传成功不代表客户端应用成功。
- Vulcan误踢仍未修复：补充截图确认Ground Spoof B达到5/5，同时有Flight A/E告警，随后被Vulcan踢出；没有封禁记录。指定JAR内置配置支持B达到处罚门槛的判断，但截图服务器的实际配置尚未取得。
- 用户已确认行走正常的默认家具就是 `default:bench`。bench同样使用Shulker；与MUZ桌的明确差异为碰撞方向、peek、数量及额外交互实体。CE API与玩家放置共用碰撞初始化，interaction_entity也不改变碰撞尺寸，不能据此直接判为兼容缺陷。用户补充同款桌子手放也踢、peek=0也踢，说明MUZ专用生成流程不是复现的必要条件，peek=33也不能作为唯一原因；该次生效配置与日志尚未取得。清除226个实体仍只是待核查线索，下一步应保留桌模型、用bench完整hitboxes做对照，再逐项恢复差异。2026-09-19本轮仅只读调查并同步文档，未修改插件行为、反作弊或现场配置，未新增构建测试或部署。

### bench 碰撞独立对照配置

- 配置源：`reference/table-bench-collision-probe.yml`，已复制至本地 `E:\我的世界插件\斗地主\plugins\CraftEngine\resources\muz\configuration\table-bench-collision-probe.yml`。
- 独立物品 ID：`muz:table_bench_collision_probe`。保留 MUZ 桌模型，完整使用正常 bench 的 hitboxes（含两个 seats）；不覆盖正式桌子，不进入发布 JAR。
- 静态解析与结构对比通过：除名称、身份引用及 hitboxes 外与原桌定义一致，正式 `furniture.yml` 哈希未变。这不是 CE 运行期加载或反作弊验收。
- 在发生误踢的服务器放入该配置并完成 CE 配置重载后，获取独立测试物品重新放置；生存、非 OP、同一平坦地面测试，只步行、不坐下、不清实体。单个 bench 碰撞体不覆盖整张桌模型，沿 bench 实际支撑区域行走，并记录是否出现 Ground Spoof B。
- 尚未执行重载与游戏内测试。本地目录未发现 Vulcan；若误踢发生在另一台服务器，须将此文件部署到那台服务器，不能把本地复制当成该服已生效。

## 1.10.35 三道具固定定位（定向回归通过，实服待验）

- 修复玩家名、倒计时和提示长度变化导致三道具左右漂移的问题；图标与提示分别居中，图标位置只受自身偏移影响，不随头像变化。
- Web Hotbar 与26.1.2客户端按相同整数规则居中；不调整现有PNG、码位或纵向位置。
- 保留当前底部提示样式；实际资源字体无法精确测宽时，固定显示图标并将正文限频转发聊天（每玩家最多10秒一次），不靠猜测字宽补偿。
- 最终强制编译8项任务实际执行，定向回归 **184/184**、12个容器全部成功，无失败、跳过或中止。已完成26.1.2目标干净构建、三个归档完整性与SnakeYAML重定位核验；未运行全仓测试、正式Chromium或其它目标测试。
- JAR：`C:\PluginLibs\MUZ-1.10.35-paper-26.1.2.jar`，源副本SHA-256一致：`27ddd46a94df78a3eda908d07667952001b6c0095e89541f4ffe16b39b36fe9f`。两种ZIP位于 `build/paper-26.1.2/distributions/`。
- 本次未部署或重启实服，真实CraftEngine保存、资源上传、客户端重新下载及游戏内视觉仍待验。字体精确路径只针对26.1.2，客户端私有字体包及混版本连接不在保证范围。

## 1.10.34 Hotbar 根字体合并校验（自动化通过，实服待验）

- 处理保存时 `minecraft:muz_hotbar61191` 被误报为未声明映射的问题：`61191`（`U+EF07`）是鸡蛋连续字形，CraftEngine 可将其与基础字形合并到根目录 `muz_hotbar.json`。
- 校验器按基础声明与本次连续布局逐项核验共享根字体，保留未知码位、重复、缺失和几何错配拒绝；不修改现有字体码位或 HUD 尺寸。
- 新测试在旧实现中精确复现 `61191` 报错；修复后强制编译成功，四类资源定向回归 **100/100**、5/5 容器通过，无失败、跳过或中止。未运行全仓测试。
- 三目标 JAR、资源包和 CraftEngine bundle 已构建；九归档 CRC、重复条目、元数据、资源格式与原版 hotbar 禁止项核验通过，SnakeYAML 重定位通过。三个 `MUZ-1.10.34-<目标>.jar` 已同步到 `C:\PluginLibs`，副本哈希一致；旧版本文件保留。
- 2026-09-19 已按要求部署 `MUZ-1.10.34-paper-26.1.2.jar` 至 `E:\我的世界插件\斗地主\plugins`，副本 SHA-256 与发布产物一致；旧 `1.10.33` JAR 已改名为 `.jar.bak`，避免重复加载。未重启服务端、未修改现场配置，运行中版本不会自动切换；重启后需重新尝试保存并让客户端重下资源包，真实保存与视觉仍待验。

## 1.10.33 CE HUD 连续资源校验修复（自动化验证通过，实服待验）

- 修复 Debug Web 保存时将根目录 `assets/minecraft/font/muz_counter_continuous.json` 误判为 profile 未生成分页的问题：根 bundle 校验只豁免当前请求明确生成的连续字体，连续资源再按 `pack.mcmeta` 声明的 overlay 前缀统一校验。
- 连续字体与动态 PNG 支持根目录或已声明 overlay 目录二选一；同一逻辑资源重复出现、缺失、未声明前缀或 profile 外分页仍拒绝。
- 连续 bot 图像使用独立 CraftEngine ID：`muz:trick_hud_continuous_bot_avatar`、`muz:trick_hud_continuous_bot_avatar_landlord`、`muz:trick_hud_continuous_bot_avatar_farmer`，不再与基础 `bot_avatar.yml` 重复；font、char、贴图和几何保持不变。
- 已强制编译并完成 HUD 资源契约定向回归：98/98 测试通过，5/5 容器成功，无失败、跳过或中止。
- 已完成三个目标的 `clean shadowJar zipResourcePack zipCraftEngineBundle verifyRelocatedSnakeYaml` 构建，并将三个 JAR 同步至 `C:\PluginLibs`；源文件与副本 SHA-256 已逐一核对一致。构建产物版本与作者元数据正确，CraftEngine bundle 无重复条目且未覆盖原版 hotbar sprite。
- 本地构建、归档元数据与 SnakeYAML 重定位已核对通过；真实 CraftEngine reload、客户端资源包下载和游戏内视觉验收仍未完成。

## 1.10.32 CE家具区块卸载修复（验证中）

- 修复桌面和椅子的 CraftEngine 根实体被 MUZ 统一标记为 `persistent=false` 的问题；CE 家具现在只登记实体树 UUID，不再进入 MUZ 的非持久化保护路径。
- 保留 fallback Display/Interaction 实体的原有保护逻辑，避免桌面和椅子在没有 CE 时被破坏或受重力影响。
- 根因是 CE 26.8.2 在区块卸载时会跳过非持久家具的失效清理，导致玩家返回区块后旧家具映射继续指向失效 BukkitEntity，右键时触发 `platformEntity()` 空指针。
- 已补充 CE 桌面/椅子持久化边界契约测试；真实区块卸载返回、`/ce reload all` 与游戏内坐椅仍需测试服确认。
- 2026-09-18 重新执行三个目标的 `clean shadowJar zipResourcePack zipCraftEngineBundle verifyRelocatedSnakeYaml`，均 BUILD SUCCESSFUL；三份最新版 JAR 已覆盖同步至 `C:\PluginLibs`，源文件与副本 SHA-256 一致：`paper-1.21.11` 为 `d3df514e9dfe1cc699534e37e0638d7ed1b862b1f5d7859d31c9d732bcfcb5fb`，`paper-26.1.2` 为 `5d91689b985f59474fee7c676a7b78f412f1a7c068d23435a6c722b13ac3f884`，`paper-26.2` 为 `d5a1a6b1b3abff496a1a97f11af5fbba47f08da68e685f792559bad8f52d7d29`。
- 本次仅完成本地构建、JAR 同步与哈希核对；真实 CraftEngine reload、客户端资源包下载和游戏内视觉验收仍未完成。

## 1.10.31 三道具Hotbar坐标与运行期诊断（验证中）

- 三道具 Hotbar 基础 ascent 从 `-100` 校准为 `-43`，与现代客户端 ActionBar 的 BitmapProvider 基线对齐；当前 100% 档 `offset-y` 范围同步为 `-299..512`。Debug Web 逻辑画布始终显示外边界、垂直中轴和水平中分线，顶部吸附按钮控制两条轴的增强显示与中心吸附。
- Debug Web 两份页面与 Java 内联模板统一消费 `baseAscent`/`offset-y` 计算 Hotbar 位置，避免网页显示在屏内而游戏字体落到屏外；独立打开前端原型且后端 API 不可用时，逻辑屏幕边界也会先按顶栏以下 viewport 自适应到最大尺寸。
- Hotbar 关闭、CraftEngine 不可用、非 PLAYING、机器人/离线座位及 overlay 未 ready 时新增限频诊断日志；不改变原有降级语义。
- 已补充资源声明、选中框 ascent、网页公式和运行诊断回归；Debug Web 顶栏改为无圆角的草方块风格全宽顶栏，中央 Minecraft GUI 会按顶栏以下可用 viewport 自适应到最大尺寸；真实 CE 重载、客户端下载与游戏内视觉仍未验证。

## 1.10.30 CraftEngine元数据与网页逻辑屏幕边界（验证中）

- 兼容 CraftEngine 新生成的 `pack.mcmeta`：当不存在旧 `pack_format` 时，按二元 `min_format` / `max_format` 范围校验，覆盖项目目标格式才允许保存，不再因现代包格式误回滚配置。
- Debug Web 两份正式页面在 640×360 Minecraft 逻辑舞台绘制四边金色边界和尺寸标签；边界不拦截鼠标事件，十字线保留为辅助定位。
- 新增现代元数据与真实 Chromium 边界回归，Java 内联兼容模板也同步绘制边界；1.10.30 的本地验收已完成，实服 CE 重载、客户端下载和游戏内显示仍未验证。

## 1.10.29 网页居中补充修复（本地验收通过）

- 牌行、头像、记牌器使用与游戏内一致的净前进量居中，真实内容包络只负责屏幕边界；修复负头像间距会被网页保存成错误水平偏移的问题；槽内透明留白不参与边界，逻辑槽原点越界但真实头像仍在屏内时保留用户偏移。
- 新增 `avatar-gap=-360`、X 为零时内容应恰好覆盖 `0..640` 且偏移不变的真实浏览器回归；缩放测试先验证手柄真实可见、可命中，保留拖动和 DOM 稳定性断言。
- 浏览器测试补齐启动失败、超时和中断清理，只终止自身 Node/Chromium 进程树，并实际测试挂起父子进程退出；脚本注入兼容 Windows CRLF 与 LF，避免换行转换误报缺少测试注入点。
- 2026-09-17 原目录最终强制编译 7 个任务全部执行成功；定向含正式 Chromium 与父子进程清理 51/51、3 个容器成功，26.2 全量 859/859、119 个容器成功，无失败、跳过或中止。其他两个目标未运行 JUnit。
- 三目标各 10 个构建任务成功；九归档 CRC、重复条目、版本/作者/API/字节码、资源格式、429 个 bundle 文件内嵌/独立一致、125 个 OGG、counter/hotbar PNG、内嵌 HTML/背景及禁止原版 sprite 覆盖均核验通过。
- 三个 1.10.29 JAR 已复制至 `C:\PluginLibs`，源与副本哈希一致；保留旧版 1.10.28，不原地覆盖。详细审计见 `build/junit-runner/release-129-audit.json`。
- 实服 CE 重载、客户端资源包下载及游戏内显示仍未验证。

## 1.10.28 网页屏幕边界（本地验收通过）

- 鸡蛋、水桶、番茄及选中框与牌行、头像、记牌器一起受屏幕边界约束，不用裁切掩盖越界；越界整数位置在页面修正后需要手动保存。负头像间距现按三头像真实左右包络计算，极端负值不再漏掉左侧边界。
- `card-step`、`avatar-gap`、`counter.gap` 及四个连续 Y 字段的小数输入都会保留原值并由保存接口明确拒绝，不会先行取整冒充合法配置。resize 拖动期间保持 DOM 节点稳定，松手后才正式刷新；同时补齐逻辑屏幕的 `.screen` 类，缩放句柄可正常显示和命中。浏览器测试子进程也改为真正受超时控制。
- 2026-09-17 最终强制编译 7 个任务全部实际执行成功；定向 `DebugWebServerTest` + 正式 Chromium 契约 50/50、3 个容器成功，26.2 全量 859/859、119 个容器成功，均无失败、跳过或中止。其他两个目标未运行 JUnit。
- 三目标均重新完成 10 个构建任务；九个 JAR/ZIP 归档的 CRC、重复条目、版本/作者/API/字节码、资源包格式、429 个 bundle 文件、125 个 OGG、三道具与选中框 PNG、内嵌 HTML/背景及禁止原版 hotbar 覆盖均核验通过。
- 三个 JAR 已安全复制至 `C:\PluginLibs`，SHA-256（1.21.11 / 26.1.2 / 26.2）为 `8a2771ae21f2407288e88b1aba895f09033e18ae4f734bef1155a40dde423297`、`5b6a3bd6175d5ae6a8eeac3358e321bd2ae92e08de409d40957feab1adb9415b`、`9eb6dd77acdc2cf1107459af517efa78d339a5e3e9af85f2bb9fdb8ead8caf07`。
- 本轮未部署或重启测试服，未执行真实 CraftEngine reload、资源上传、客户端下载或游戏内视觉验收；网页受控 fixture 与 ZIP 校验不代表实服验收。

## 1.10.27 背景音乐防重复

- 同一局重复请求开局音乐不再打断重播；轮播回调消费后立即失效，重复执行不会切歌或取消下一首任务。
- 背景音乐维持先停止五首 MUZ BGM、再播放一首的顺序；叫牌、出牌、胜负提示仍允许与背景音乐正常混音。
- 26.2 干净强制编译、SnakeYAML 重定位及定向回归 30/30 通过（含音乐 12 项），JAR 已复制至 `C:\PluginLibs\MUZ-1.10.27-paper-26.2.jar` 并核对哈希。未跑全仓测试、其他目标或实服客户端听感验收。
- 可配置提示音、动作 `play_sound` 不要填写 MUZ 的 `opening` / `middle` / `bgm1..3` 背景音乐键，这些通用入口及外部命令不受牌桌音乐协调器管理；不承诺屏蔽其他插件音频。

## 1.10.26 音乐清理修复

- 对局结束、强制结束、离桌及关桌沿用统一停止入口：禁用音乐会话并取消轮播任务，停止后不会因迟到更新或旧局回调重新播放。
- 清理同时覆盖实际收到背景音乐的玩家，避免座位状态改变后漏停；只停止 MUZ 的五首背景曲目，保留胜负提示及其他声音。
- 不新增命令或配置，不改资源包声音 ID。自动化验证已通过；实服结束停播效果尚未确认，不把代码保护当作客户端验收。

## 开局发牌与明牌

1. 按固定座位顺序轮流发牌，每人每批 3 张，最后一批 2 张；自己的手牌从左到右依次出现，发完前保持洗牌顺序。
2. 三家发完后，每张牌绕自身竖直中心轴原地翻转 360°；180° 时切成排好序的牌序，继续转完后归位。
3. 接着显示 3 秒“明牌×2”窗口。每位在线真人可以自愿公开自己的手牌；任意一人明牌后整局最多乘 2，多人明牌不叠乘，无人点击则不加倍。机器人默认不明牌。
4. 倒计时结束后进入原来的叫分、同分抢地主及后续加倍流程；叫分阶段不再提供旧的无倍率明牌入口。

开局速度由 `round-opening` 配置：`batch-interval-ticks` 默认 4（3..200）、`flip-ticks` 默认 20（8..200 偶数）、`reveal-ticks` 默认 60（1..1200）。每局开始时固定快照，非法值会明确拒绝开局，重载不改变正在播放的节奏；窗口期间不能提前叫分或出牌。提示可通过 `round-opening.messages` 调整，实时倍率与结算摘要都显示明牌倍率；无人叫分重发也会重新走完整流程并清空旧底牌。牌面与私有点数标签出生即隐藏，再按资格显示；切入叫分时复用发牌实体，避免整排删除重建。配置中的非整数、越界和非法文本不会被静默截断或回退。本轮游戏内翻转流畅度、左右方向及多人同步仍需实服确认。

## Debug Web HUD 配置面板

根目录提供独立前端原型 [debug-hud-preview.html](debug-hud-preview.html)，打开即显示占满浏览器窗口的 Minecraft 视口；预览只保留上一手牌、三头像（小/大/小）、记牌器与三道具 HUD，不显示 BossBar；配置面板默认隐藏并可按需展开；它通过 `/api/preview-resources` 拉取白名单，并只显示服务端真实 PNG（牌面、头像、记牌器三层、鸡蛋/水桶/番茄图标与选中高亮），不使用 emoji 或 CSS 假图；三道具仅叠加在原版 9 槽物品栏上方，不接 BetterHud、不覆盖 `minecraft` 原版 sprite；正式 Debug Web 首页直接加载 JAR 内嵌的 `debug-hud-preview.html`；内嵌资源缺失时明确返回错误，不再回退到带旧版本号的 Java 内联页面。

资源预览通过 `/api/preview-resources` 获取当前 profile 白名单，再使用 `/api/resource/{完整资源键}` 加载牌面、头像、记牌器 label/frame/digit 与三道具 HUD 的真实 PNG；记牌器三层和三道具图标在浏览器中与游戏内共用同一批像素资源，避免系统字体、伪图标与客户端 BitmapProvider 出现视觉分叉。拖动通过 requestAnimationFrame 合并 pointermove，减少连续拖动时的卡顿，并按 GUI 缩放倍数把位移换算回 MC 像素；三道具图标与选中高亮尺寸、间隔和 advance 取自服务端 `geometry.hotbars`，牌行、头像行、记牌行也改为消费 `geometry.cards` / `avatars` / `counterTiers`，不再写死 CSS 尺寸；服务端拒绝任意未声明路径。

记牌器采用紧凑仪表盘契约：固定按 `CardRank.values()` 输出 15 格，数字表示本局累计已出数量，剩余数量仅用于耗尽/隐藏状态。每档固定 22 个分层 glyph（15 个牌类 label、5 个数字 0..4、普通/耗尽 frame），绘制顺序为 `label → frame → digit`；默认 100% 几何为 label/frame `21×12px`、digit `21×8px`、cell `21×27px`、每格 advance `22px`，`counter.gap=2` 时默认总宽 `358px`。耗尽且启用隐藏时只隐藏内容，格子仍占位，后续格子不左移。

在 `config.yml` 中开启 `debug.web-ui.enabled: true`，重载后从服务端本机访问 `http://127.0.0.1:<port>`（默认端口 2000）。页面以全屏 Minecraft 风格画布为主，包含像素化背景、准星、世界视口标识、操作提示、右键坐标提示和四层 HUD 编辑场景；控制面板采用简约磨砂玻璃风格，四个 HUD 图层始终显示用于调试；页首保留“只开放 22 个 HUD 运行期字段”、异步保存与客户端重下资源包提示；配置表单位于可折叠浮动面板，并提供浏览器“全屏”按钮。左键拖拽 HUD 层，右键查看左右位置，Shift+方向键精确平移选中层，Shift+拖拽空白区域平移视图；四层 Y 字段均支持连续整数，网页默认自由拖动且不将 nearest 吸附写入配置。

- 可编辑 22 个 HUD 运行期参数（含记牌器独立 Y/缩放与三道具 HUD 缩放）；桌内互动参数不进入 Web 白名单，单独由 `hotbar-hud.interaction` 配置
- 全屏预览按 Minecraft 像素坐标系绘制（640×360 基准），几何数据由服务端下发；背景、准星与四层 HUD 图层共用同一舞台（三道具图标层替代历史九槽底图）
- 牌行、头像行、记牌行和三道具 HUD 均支持 X/Y 拖动；三道具图标、选中高亮的尺寸与间隔由服务端 geometry 下发，牌高、头像倍数、记牌器/三道具缩放仍使用真实资源档位，四个 Y 字段使用连续整数范围且不 nearest；三道具覆盖层及 Trick 连续字体 ready 状态绑定完整 `HudResourceRequest`，Hotbar 资源选择不依赖 Debug Web enabled/running，未验证或失败清 ready 时不发送动态字形
- 右键显示当前层左右/上下边界、指针逻辑坐标与偏移，Shift+方向键做 1 MC 像素微调，Shift+拖拽空白区域平移视图（只改变画布视图，不写 HUD 配置）；浏览器 Fullscreen API 失败时保留普通 viewport 编辑模式；舞台按浏览器 viewport 自动适配并修正全屏缩放后的拖动与缩放指针换算，表单和连续字段更新通过 requestAnimationFrame 合并，并保持拖拽/缩放状态变量单例；页面默认自由拖动，吸附开关只影响网页、不写入 22 字段 patch；页面初始化时从 `localStorage` 恢复日间/夜间主题按钮的浏览器本地选择
- 预览静态结构与几何更新分离，连续 pointermove 使用 requestAnimationFrame 合并，避免整页频繁重建；浏览器 Fullscreen API 不可用时保留普通 viewport 编辑模式，资源加载失败只影响三道具 HUD 局部；三道具辅助层不覆盖原版 hotbar，真实鸡蛋/水桶/番茄图标与选中高亮按构建期资源显示
- 支持 Ctrl+S 保存、Ctrl+R 重载的键盘快捷键
- 保存链路：配置与四层自有资源异步事务 → 主线程触发 CraftEngine 真实重载 → 异步生成并校验实际 ZIP 字体/PNG → 主线程 `markVerified`、应用运行态并发布 Snapshot；任一步失败都回滚配置与自有资源、清除 ready 且不报成功。租约 120 秒只 abandon 本次结果，底层 raw 任务结束前不 cancel、不释放；资源恢复独立于 Debug Web 开关，并负责启动、`/muz reload` 与 CraftEngine enable 场景
- `offset-x` 运行期即时生效；四个 Y 字段由覆盖层 ascent/PNG 资源决定，客户端必须重新下载新包才能看到，不能据此称为游戏端已验收

可通过 `/muz reload` 同步 Debug Web 生命周期（`enabled` 变化时自动启停）。

## 构建

构建期尺寸与 scale 仍由根目录 `muz-resource-profile.yml` 控制：牌高 `53`、头像 `4/6`、counter `100`、hotbar `100` 等离散资源档位不变；原 profile Y 档 `0/50`、`0/122` 保留作为基础 bundle 兼容，不扩展全 Y 档。运行期新增四层连续请求：Trick 三个 Y 为 `-128..512`，hotbar 当前 100% Y 为 `-299..512`。连续覆盖层使用独立 Trick 字体 alias 与动态 PNG 目录，不覆盖正式 font/char；GUI 缩放只改变 640×360 逻辑舞台的 CSS 显示比例，不改变客户端 bitmap 的可见尺寸或 advance。

构建目标由 `MuzTarget` 表驱动，用 `-PmuzTarget=<id>` 选择，默认 `paper-26.2`：

```bash
./gradlew.bat -PmuzTarget=paper-26.1.2 clean shadowJar zipResourcePack zipCraftEngineBundle verifyRelocatedSnakeYaml
```

三个目标依次串行构建；仅执行 `shadowJar` 不会生成两种 ZIP。

可选目标：`paper-1.21.11`、`paper-26.1.2`、`paper-26.2`。

产物位于 `build/<targetId>/`（不是 `build/`），以 `paper-26.1.2` 为例：

- `build/paper-26.1.2/libs/MUZ-1.10.25-paper-26.1.2.jar` — 插件 JAR
- `build/paper-26.1.2/distributions/MUZ-resourcepack-1.10.25.zip` — 执行 ZIP 任务后生成的客户端资源包
- `build/paper-26.1.2/distributions/MUZ-craftengine-1.10.25.zip` — 执行 ZIP 任务后生成的 CraftEngine bundle

源码包需另外执行 `sourcesJar`；上述发布命令只生成插件 JAR 与两种 ZIP。

把与服务端版本对应的 JAR 放进 `plugins/`。不用 CraftEngine 时直接下发资源包 ZIP 给客户端；用 CraftEngine 时可手动导入 bundle，或让插件检测到 CE 后自动导出。

## 依赖

| 依赖 | 类型 | 说明 |
|------|------|------|
| Paper API | 必需 | 目标版本由构建参数决定 |
| CraftEngine 0.0.67 | 可选 | 资源包与家具渲染 |
| PlaceholderAPI 2.12.2 | 可选 | 变量支持 |
| VaultAPI 1.7 | 可选 | 经济桥接 |
| CMI / CMILib / EzEconomy / XConomy | 可选 | 经济 provider 检测 |

所有可选依赖缺失时自动降级，不会导致启动失败。

## 技术栈

- **语言**：Java（Kotlin 仅用于 Gradle DSL）
- **框架**：原生 Paper `JavaPlugin` + TabooLib（工具层，不用注解生命周期）
- **渲染**：Display Entity + MiniMessage / Adventure
- **配置**：SnakeYAML 2.6（已 relocate 打进 JAR）
- **存储**：SQLite（默认） / MySQL（可选）
- **构建**：Gradle + Shadow Plugin，产物按目标版本隔离
- **测试**：JUnit 5，94 个测试文件

## 版本状态

### 1.10.25（四层 HUD 连续 Y 覆盖层，自动化与产物核验通过）

- 新契约保持 Debug Web 22 个字段不增减；card-height=53、avatar scale=4/6、counter scale=100、hotbar scale=100 仍受 profile 离散白名单约束。Trick 的 `offset-down`、`avatar-offset-down`、`counter.offset-down` 连续允许 `-128..512`，hotbar 当前 100% 的 `offset-y` 连续允许 `-299..512`；原 profile 的 `0/50` 与 `0/122` 仅作为基础 bundle 兼容，不扩展 profile 的全 Y 档。
- 新增不可变 `HudResourceRequest(cardOffsetDown, avatarOffsetDown, counterOffsetDown, hotbarOffsetY, hotbarScale)`；`HudOverlayLayout` 只提供覆盖层 metadata 与字形布局。`HudOverlayWriter` 写出 `trick_hud_continuous.yml`、`hotbar_debug.yml` 与必要 padding PNG；Trick 使用独立 `baseFont_continuous` 与 base tier 0 char，hotbar 复用既有 debug font/char，动态 PNG 位于 `resourcepack/assets/muz/textures/font/continuous/`，不覆盖正式 font/char。
- 覆盖层按 `ascent=baseAscent-rawY` 生成；向上导致 ascent 超过原 height 时只在底部补透明像素并同比增加 provider height，保持 PNG 比例、可见尺寸与 advance 不变；bot 原图按通用 16px、farmer/landlord 18px 处理，并按 gcd 整数规则补齐 height 10/11，保持 PNG 比率。PNG 绘图逻辑仍只有构建期一份，运行期只复用源 PNG。
- 保存流程为“config 与四层资源异步事务 → 主线程真实 CE reload → 异步 generate 与实际 ZIP 字形/PNG 验证 → 主线程 `markVerified`/apply/snapshot”；任一步失败都回滚配置与自有资源并清 ready，不能恢复先前 ready（CraftEngine 可能已部分 reload）。Hotbar 资源选择独立于 Debug Web 是否 enabled/running，只由完整 `HudResourceRequest` 的 verified ready 决定；Legacy offset-only Hotbar 写入口必须显式拒绝，旧 verifier 仅可做离线 legacy 校验，不能作为 ready 凭据。`HudOverlayRuntimeState` 只接受完整请求的 verified 快照，连续非 profile Y 未 ready 时正式 HUD 隐藏并告警，不 nearest；精确旧 bundle 档位仍可兼容。`HudResourceRecoveryService` 独立负责启动、`/muz reload` 与 CE enable 恢复，不受 Debug Web 开关影响。
- 回归夹具覆盖完整资源请求、失败清 ready、超时租约与关闭收尾；补偿异步完成，失活后不排新的运行态应用；测试锁定统一四层原子写与完整回滚；浏览器回归区分非法坐标的预期 400 与真实脚本/资源错误，相关回归已完成统一实跑。
- 保存成功后继续编辑会立即恢复“未保存”提示并清除旧的成功/错误状态；重新读取配置会丢弃尚未提交的页面值。
- 强制编译 `compileJava compileTestJava processResources processTestResources --rerun-tasks` 已成功（7 tasks executed）；`paper-26.2` 定向回归 90/90、6 个容器成功（含正式 Chromium BrowserContract），全量回归 847/847、118 个容器全部成功，均无失败、跳过或中止。保留既有 Unsafe/API 弃用警告；另两个目标仅完成编译与产物核验，未运行 JUnit。
- 三目标均完成 `clean shadowJar zipResourcePack zipCraftEngineBundle verifyRelocatedSnakeYaml`，每目标 10 tasks executed。九个归档 CRC/无重复条目、插件版本 1.10.25/API/作者 linmumua、字节码 65/69/69、pack_format 75/84/88、125 个 OGG Vorbis、22 张 counter PNG 尺寸与 alpha=1 锚点、4 张 hotbar PNG 20×22、禁止原版 sprite/旧九槽、内嵌与独立 bundle 字节一致性、JAR 内 HTML/背景与最终源文件一致性均已通过；审计日志为 `build/junit-runner/continuous-release-audit.log`。
- 三个 JAR 已复制到 `C:\PluginLibs`，源文件与副本 SHA-256（按 `paper-1.21.11` / `paper-26.1.2` / `paper-26.2`）分别为 `43e243cd9b23c95ff81d20773601201426cda6cf93d24a3642b770dd4dfa3c57`、`1f7e129ce41e0cca57b77d587a96a0ae8394f1cbf1795a0a6fe5bdf7ee6e5f99`、`e2e8587935333275e199e0460ecee301e5bcb5e500ae1a4583d78ffb1ad7738a`。
- 仍未完成真实 CraftEngine reload、资源上传、客户端重新下载与游戏内视觉人工确认；网页/ZIP fixture 不代表 CE 联调。Web HUD 配置锁只覆盖 Web 自身快照边界，非 Web 配置入口仍仅有有限并发冲突检测，不宣称全局配置事务。

### 1.10.24（网页预览尺寸、真实资源与自由拖动）

- 修复网页 GUI 倍率计算：默认视图按窗口放大，倍率控制与拖动坐标使用同一实际 CSS 比例，不修改客户端字形尺寸。
- 背景使用实服截图中的无 HUD 风景裁切，作为 JAR 内置同源图片提供；亚克力模糊仅作用于背景，HUD 保持清晰。工具栏精简、辅助线和标签按需显示。
- 三道具按 geometry 的真实纹理与 manifest 绑定；预览头像使用完整机器人示例图片（非在线玩家皮肤），不再把字体像素遮罩显示成白点框。修复 J/Q/K/A 牌面映射导致的预览空洞。
- 默认自由拖动，松手提交最后一帧。网页可预览任意纵向位置，但牌行、头像、记牌器仍受资源包离散档位限制：非法位置明确阻止保存，不能把网页移动误报为游戏内已应用；三道具纵向仍支持现有合法范围内的连续偏移。
- 本轮 26.2 强制编译通过；最终定向 **73/73**（含正式 HTML 的真实 Chromium/PNG 交互契约）与全量 JUnit **806/806** 均通过，无跳过、中止或失败。浏览器覆盖四层拖动、最后一帧、倍率换算、非法档位保存阻止、合法连续 Hotbar Y、保存成功清除旧错误及 320/375px 布局；保存接口为受控 fixture，不代表 CE 实服联调。
- 三目标 JAR 和两类 ZIP 已构建，九个归档 CRC、版本、禁止原版 hotbar 覆盖和 JAR 内最终 HTML/背景已核验；三个 JAR 已复制到 `C:\PluginLibs` 并核对哈希。其余两目标未跑 JUnit。本轮未部署、未重启测试服、未提交或推送。

### 1.10.23（逐批发牌、翻转排序与明牌窗口）

- 新流程：每人三张轮发、末批两张，全部发完后原地翻转 360°，180° 时排序，随后 3 秒自愿明牌窗口，再进入正常叫分/抢地主。
- 明牌只公开自己的手牌，整局最多 ×2；机器人默认不明牌，重复点击与离线玩家不能增加倍率。实时状态与结算摘要均展示明牌来源。
- 已完成三目标干净构建及 SnakeYAML 重定位校验；26.2 最终独立全量 JUnit **806/806** 通过，114 个容器全部成功，无跳过、中止或失败。其余目标未运行 JUnit；测试夹具保留既有 Unsafe/反射弃用警告。
- 九个 JAR/ZIP 的 CRC、目标格式、125 个 OGG、三道具资源、内嵌 bundle 一致性及禁止原版 hotbar 覆盖已核验。三个插件 JAR 已复制至 `C:\PluginLibs`，副本哈希一致。
- 新提示由 `round-opening.messages` 配置；旧倍率视图标签沿用现有硬编码，是保留的文本外置技术债。未部署测试服；需安装对应 JAR、确认资源包应用后进服检查翻转、左右顺序与多人明牌效果。本轮未自动提交或推送。

### 1.10.22（三道具 HUD 与桌内互动，实施中）

- 当前契约改为 MUZ 自绘的三个独立透明道具图标：鸡蛋、水桶、番茄；不接 BetterHud，不覆盖原版 9 槽物品栏或 `minecraft` hotbar sprite。每个图标盒 20×22 像素，透明间隔 4 像素，总宽 68 像素，advance 为 69；默认基础 ascent 为 -43，按现代客户端 ActionBar BitmapProvider 基线校准。`hotbar-hud.enabled` 仍默认为 `false`。
- 仅在 `GamePhase.PLAYING`、同桌、在线真人之间生效；机器人、旁观者、跨桌玩家和无效目标不参与。滚轮与数字键换槽事件无法严格区分，按同一虚拟索引映射处理，不宣称捕获原始滚轮。
- `TableGadgetService` / `TableGadgetSettings` / `TableGadgetEffectService` 与 `model.TableGadget` 分离负责选择、资格、目标、冷却、并发和效果。默认互动参数为 `enabled=true`、`range=6.0`、`cooldown-ticks=40`、`flight-ticks=10`、`water-ticks=16`、`max-active=32`；参数不进入 Debug Web 的 22 个 HUD 字段白名单。
- 目标高亮使用对附近观察者全局可见的短时真实发光，带引用计数，记录并恢复既有状态，只撤回 MUZ 自己设置的发光。目标传送、离开或重生后会删除旧的 actor→target 状态，允许同一目标重新建立发光引用。CE 桌面家具的右键事件在手牌仲裁之后接入同一道具路由。鸡蛋/番茄为短暂无伤害投掷；水桶为贴身短时透明水幕，不放置真实水方块、不产生流体扩散、不造成伤害。水幕和顶部水桶按两个实体计入并发上限，所有效果实体与粒子统一登记，离桌、死亡、传送、断线、关桌和超时均清理。
- 暂停或禁用互动使用可恢复的清理，重新启用后可继续投掷；只有插件最终关闭才永久停止效果服务。倒水同时显示头顶水桶和贴身水幕，两实体均计入上限并统一移除。
- 保存与重新读取配置会重载 CraftEngine，并重建、校验资源包 ZIP；客户端必须重新下载资源包才能看到新的图标、ascent 或 `offset-y`。
- 2026-09-16 最终强制编译通过，`paper-26.2` 独立全量 JUnit **773/773** 通过，无跳过、中止或失败容器。三个目标的 JAR、资源包、CraftEngine bundle 与 SnakeYAML 重定位校验通过；九个归档已核验 CRC、目标格式、四张 20×22 HUD PNG、125 个 OGG、内嵌 bundle 一致性和禁止原版 hotbar 覆盖。三个 JAR 已复制至 `C:\PluginLibs` 并核对 SHA-256；其余两目标未运行 JUnit。
- Chromium 兼容模板 fixture 的三图标、选中框、滚轮循环、拖动比例及保存成功/失败状态验证通过（mock 后端）；正式内嵌首页的完整浏览器回归、服务端部署、真实 CE 重载、客户端下载和多人进服验收仍待确认。

### 1.10.21（记牌器上下两行标签与数量）

- 记牌器继续保持固定 15 格与三层 glyph 契约：上方显示牌类标签，下方显示累计已出数量；小王、大王标签改为单字“小”“大”，避免牌类标签拥挤。
- 保持现有 22 个 glyph、21×12/21×8 资源几何、码位、ascent、advance 与 Debug Web 同源真实 PNG 预览；构建期仍使用固定整数像素字模，不依赖系统字体。
- 三目标 `paper-1.21.11`、`paper-26.1.2`、`paper-26.2` 的 JAR、资源包、CraftEngine bundle 与 SnakeYAML 重定位校验均成功；`paper-26.2` 定向回归 109/109 通过，无跳过、失败或中止。三个 JAR 已复制至 `C:\PluginLibs`，副本哈希分别为 `c15476ee62429fccfca942e373d1234a488aa02c47a20f1eb763e7431c4964a0`、`5862b75b3a4ab8821cb7631f1ed14206484499725e7e5195e5d12d47a8efc9b7`、`00687005dee478c38aa8dfc55d7755757580d2439e78f5d7fc6ea82a352fac7b`。
- CraftEngine 重载、客户端下载和游戏内视觉仍需单独人工确认。

### 1.10.20（方正像素记牌器与同源 Web 预览）

- 记牌器 label/digit 全部改为固定整数像素 bitmap：普通牌类 7×9、10 为 11×9、王牌为 9×9、数量数字为 5×7；移除旧矢量笔画生成路径，保留原有字体、码位、PNG 路径、ascent、height、advance 与三层叠加契约。
- Debug Web 的记牌器 label/frame/digit 直接加载 manifest 白名单中的真实 PNG，与游戏内共用同一套资源，并使用像素化采样；根目录原型与 JAR 内嵌页面保持一致。
- `paper-26.2` 强制编译与定向回归实跑 102/102，通过无跳过、无失败容器；`shadowJar`、资源包、CraftEngine bundle 与 SnakeYAML 重定位校验均成功。JAR 已复制到 `C:\PluginLibs\MUZ-1.10.20-paper-26.2.jar`，源文件与副本 SHA-256 均为 `ea3672720f69d34f14ff27f0f67bf52909ced3b70b4b427777381547f0994e54`。
- 现场 CraftEngine 重载、客户端下载和游戏内视觉仍需人工确认，未将构建验证等同于客户端显示验收。

### 1.10.19（清晰记牌器预览与像素字形）

- 记牌器数字改用固定 5×7 bitmap glyph 生成，关闭抗锯齿并保留原有 font、码位、PNG 路径、ascent、height、advance 与三层叠加契约；游戏内不切换为普通文本字体。
- Debug Web 根据服务端 `counterTiers` geometry 绘制 label/digit 矢量文字，frame 继续加载白名单真实 PNG；GUI 缩放只作用于浏览器显示层，拖动仍按 CSS 位移除以预览倍率换算为整数 MC 像素。
- 修正预览牌行双重 `0.9` 缩放，并在头像、记牌器、Hotbar 资源档位缺失时显式报错，不伪造旧几何。已完成源码契约与定向编译检查；CraftEngine 现场重载、客户端重新下载资源包和游戏内视觉仍待人工确认。

### 1.10.18（紧凑记牌器重做）

- 标签/框 21×12、数字 21×8，固定 15 格显示累计已出数量；默认 gap 保持 2，总宽 358px。数字字身按高度居中绘制，避免横向拉宽；大小王使用清晰的独立笔画。
- 记牌器预览通过快照 ascent 与当前表单偏移差值定位，避免双重下移，同时支持保存前拖动预览。
- `paper-26.2` 强制 Java 编译及七类定向回归已执行：181/181 通过，无跳过、无失败容器，含 Node 真实坐标测试。三目标九个归档的版本、CRC、资源格式、22张PNG、44条声明、bundle一致性及原版hotbar禁止项均通过核验；三个 JAR 已复制至 `C:\PluginLibs`，哈希一致。
- 本版本需使用对应的新 JAR 与 bundle，重载 CraftEngine 后让客户端重新下载资源包；尚未部署或确认游戏内视觉。本轮未运行全仓库测试与完整 Chromium 交互验收，已检查真实PNG分层合成预览。

### 1.10.17（历史验证记录）

- 当前版本号、默认 profile、Debug Web 保存语义与资源白名单说明已按源码同步；Debug Web 首页已重做为现代新拟物界面，预览只保留上一手牌、三头像（小/大/小）、记牌器与 Hotbar，不显示 BossBar，并保留真实资源预览与原有交互契约；全量独立 JUnit 实跑 752/752 通过，无跳过、无失败容器。三目标 clean 发布构建与 SnakeYAML 重定位校验均通过。
- 服务端已替换 1.10.17 JAR 与 CraftEngine bundle，旧资源保留在带版本备份目录；2026年9月14日已启动测试服并确认 MUZ/CraftEngine 加载，Debug Web 重载暴露 CraftEngine 生成包的 `pack.mcmeta` 格式兼容问题，已补充单值/数组格式校验，仍需再次重载确认。Java 内联兼容模板不再写入具体旧版本号，正式首页仍以 JAR 内嵌页面为准。
- 默认 profile 当前生成 210 条 CraftEngine image provider：牌 110、头像 40、王冠 8、bot 6、记牌器 44、Hotbar 2；头像为 4/6，记牌器与 Hotbar 均为 100%。记牌器 44 条来自 2 个 offset 档 × 每档 22 个分层 glyph；未生成档位不得在运行期发送。

### 1.10.16（历史验证记录）

- 全屏 HUD 编辑器已完成稳定 DOM、四层拖动/缩放、右键坐标、Shift 微调/视图平移、默认吸附、同源 Hotbar 资源回退与异步保存链路；`DebugWebServerTest` 独立实跑 42/42 通过。
- 三目标九个 1.10.16 产物已完成 5114 项资源审计，包含归档完整性、目标 API/字节码、125 个 OGG、当前 profile 的 100% hotbar、100% 记牌器、SnakeYAML relocation、bundle 字节一致性与原版 hotbar 覆盖检查；三个 JAR 已复制到 `C:\PluginLibs` 且副本哈希一致。另外两个目标没有当次测试编译输出，未宣称其测试通过。
- 稳定 DOM 修复后的真实 Chromium/CDP 全流程已实际运行，但仍有 7 项连续拖动 dirty 断言失败；已通过四种 viewport、无应用 JS 错误、四层切换、40px 视图平移、Alt 轴锁、右键坐标、Hotbar 资源复用/失败回退、保存重载和 Fullscreen API。截图文件已更新。新版本尚未部署，游戏内资源包重载、客户端下载和进服渲染仍待人工确认。

### 1.10.15（历史源码与构建记录）

已完成的验证：

- 三个目标（`paper-1.21.11`、`paper-26.1.2`、`paper-26.2`）均完成干净发布构建（`shadowJar zipResourcePack zipCraftEngineBundle verifyRelocatedSnakeYaml`）
- 独立 JUnit 定向回归 150/150 通过，无跳过、无失败容器
- Debug Web 的 Chromium fixture 已验证全屏 MC 编辑器布局、`id='screen'` 舞台、图层交互和资源 22 个字段、15 格记牌器、9 槽 hotbar 和 640×360 几何；本轮修复图层选择、坐标输入、拖动结束及 Hotbar wheel 监听器之间缺少分号的真实脚本语法问题、缩放事件访问不到档位映射及缩放 pointermove 重建 DOM 的问题，并同步修正页面残留的“19 个字段”文案。真实浏览器已验证全屏画布、右键坐标、Shift 平移、拖拽、缩放期间 DOM 不重建、松手后刷新、Hotbar wheel、保存失败保留 dirty、保存成功淡出、Ctrl+S/Ctrl+R 防重复及重新读取丢弃未保存值；截图已更新到 `docs/screenshots/muz-hud-debug-web-initial.png` 和 `docs/screenshots/muz-hud-debug-web-final.png`，2026-09-12 真实 Chromium 已确认全屏 MC 画布、四层 HUD、浮动配置面板及 hotbar 构建期图标/数字可见
- 默认 profile 生成 210 条 CraftEngine image provider（牌 110、头像 40、王冠 8、bot 6、记牌器 44、Hotbar 2），头像实际生成 scale 4/6，记牌器与 Hotbar 仅生成 100% 档；未生成档位会被运行期和资源校验器明确拒绝
- 三个目标产物均无原版 `hotbar.png` / `hotbar_selection.png` 覆盖，JAR 已复制到 `C:\PluginLibs` 且副本哈希一致
- 资源包校验器接受全部目标格式 `pack_format` 75/84/88，并按当前 profile 的 `downTier×22` 记牌器码位及 bundle+Debug Web overlay 合并声明核对实际字体 JSON；本轮独立 JUnit HUD 定向回归 150/150 通过

Hotbar HUD 字形重构（1.10.17）：

- 底图字形（0xEF00）槽 6/7/8 改为烘焙物品图标（水桶/鸡蛋/番茄）+ 固定示例数字（17/3/5），槽 0..5 仍为纯色块
- 新增 20×22 可移动「选中槽」高亮框字形（0xEF02，`hotbar_select.png`），运行期按玩家 `getHeldItemSlot()` 用零净前进量负空格夹心定位
- 构建期资源生成、`compileJava`/`compileTestJava` 均成功；独立 JUnit HUD 定向回归（含 hotbar 逐槽图像、选中框像素/尺寸/码位/CE 声明、`DoudizhuRuntimeSyncTest`、Debug Web 几何与资源校验）150/150 项全部通过
- 三目标 JAR、资源包和 CraftEngine bundle 已生成并完成格式、OGG 数量、禁止原版 hotbar 覆盖及 JAR 副本哈希检查
- 图标可辨识度与游戏内选中框对位仍需重下资源包后进服人工确认

尚未完成：

- **现场部署与客户端验收**：当前测试服仍运行 1.10.12，需要部署新 JAR 并执行 `/ce reload all` 重新打包资源，客户端重新下载后才能确认游戏内显示正确
- 旧版 CE `resource_pack.zip` 中记牌器字形与新版三层分层不匹配，需要完整重载才能修复
- 多 viewport Playwright 自动化验收未执行（Windows/Git Bash 环境限制）
- 全仓库测试未全量运行

### 历史版本

- **1.10.14**：`/muz debug add` 改用专用调试放桌入口，新增 `DebugPlacementBypassTest`（10 项）
- **1.10.13**：Debug Web 前端交互改进，143/143 测试通过，七种宽度响应式验证
- **1.10.12**：三目标九产物完整构建与校验

## 致谢

判型与流程参考了 [tml104/-Minecraft-Dou-Dizhu](https://github.com/tml104/-Minecraft-Dou-Dizhu) 的状态机思路，借鉴了 [Arbousier1/MahjongEngine](https://github.com/Arbousier1/MahjongEngine) 的 Paper 1.21.11+ 与 CraftEngine bundle 构建路线。
