# MUZ — 方块世界斗地主

一个 Paper / Purpur 服务端的实体化牌桌斗地主插件。用 Display Entity 在真实世界坐标上渲染牌桌、手牌和悬浮 HUD，玩家通过点击实体与坐下交互完成对局。

**当前版本**：`1.10.16`<br>
**作者**：linmumua  
**平台**：Paper 1.21.11+ / Purpur 1.21.11+（不支持 Folia）

## 功能概览

- 3 人建桌、加入、准备、开局；支持 `low / mid / high / fun` 四种场次
- 完整斗地主规则：随机叫分、发牌、地主底牌、出牌校验（单牌/对子/三带/顺子/连对/飞机/炸弹/王炸）、倍数结算
- 机器人自动补位，可用 `/muz bot` 管理
- Display Entity 实体渲染：固定朝向的手牌、背面对手牌、桌边按钮、座位信息牌
- 悬浮 Trick HUD：牌行、头像行、记牌器行（分层位图字形，固定 15 格显示累计已出张数）
- Hotbar HUD：出牌阶段显示自定义 9 槽底图（槽 0..5 纯色，槽 6/7/8 为物品图标+烘焙示例数字），并按玩家持槽叠加可移动的选中槽高亮框；非出牌阶段自动恢复原版物品栏
- 资源包自动生成：构建期生成字形、贴图、CraftEngine 配置分片，含 125 个 OGG 音效
- 自动导出 CraftEngine bundle，也可单独下发资源包 ZIP
- 可选接入 Vault 经济、PlaceholderAPI 变量、CraftEngine 资源
- SQLite / MySQL 持久化，支持 `/muz history` 查看历史战绩
- 管理员 GUI（`/muz admin`）：模型 / 渲染 / 音频 / 机器人四页配置
- 个人微调菜单（`/muz settings`）：手牌偏移、间距、点数标签等私人设置
- 第三方 OpenAI 兼容接口（默认关闭），可接入 DeepSeek 等
- Debug Web HUD 配置面板（默认关闭，仅监听回环地址）

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

## Debug Web HUD 配置面板

在 `config.yml` 中开启 `debug.web-ui.enabled: true`，重载后从服务端本机访问 `http://127.0.0.1:<port>`（默认端口 2000）。页面以全屏 Minecraft 风格画布为主，包含像素化背景、BossBar、ActionBar、准星、世界视口标识、操作提示、右键坐标提示和完整 HUD 场景，页首保留“只开放 22 个 HUD 运行期字段”、异步保存与客户端重下资源包提示；配置表单位于可折叠浮动面板，并提供浏览器“全屏”按钮。左键拖拽 HUD 层，右键查看左右位置，Shift+方向键精确平移选中层，Shift+拖拽空白区域平移视图，Minecraft 风格吸附默认开启。

- 可编辑 22 个 HUD 运行期参数（含记牌器独立 Y/缩放与 Hotbar 缩放）
- 全屏预览按 Minecraft 像素坐标系绘制（640×360 基准），几何数据由服务端下发；背景、BossBar、ActionBar、准星与 HUD 图层共用同一舞台
- 牌行、头像行、记牌行和 Hotbar 均支持 X/Y 拖动；牌高、头像倍数、记牌器/Hotbar 缩放使用真实资源档位并自动吸附
- 右键显示当前层左右/上下边界、指针逻辑坐标与偏移，Shift+方向键做 1 MC 像素微调，Shift+拖拽空白区域平移视图（只改变画布视图，不写 HUD 配置）；浏览器 Fullscreen API 失败时保留普通 viewport 编辑模式；舞台按浏览器 viewport 自动适配并修正全屏缩放后的拖动与缩放指针换算，表单和连续字段更新通过 requestAnimationFrame 合并，并保持拖拽/缩放状态变量单例；默认开启 Minecraft 风格吸附（进入页面时不继承旧快照的关闭状态）
- 预览静态结构与几何更新分离，连续 pointermove 使用 requestAnimationFrame 合并，避免整页频繁重建；浏览器 Fullscreen API 不可用时保留普通 viewport 编辑模式，资源加载失败只影响对应 hotbar 区域；辅助槽层不覆盖真实 hotbar PNG，图标和数字按构建期资源显示，契约测试已锁定该行为
- 支持 Ctrl+S 保存、Ctrl+R 重载的键盘快捷键
- 保存链路：异步写 config → CE 重载 → ZIP 生成与校验 → 主线程应用；任一步失败不报成功
- `offset-x` 运行期即时生效；`offset-y` 需客户端重新下载资源包

可通过 `/muz reload` 同步 Debug Web 生命周期（`enabled` 变化时自动启停）。

## 构建

构建目标由 `MuzTarget` 表驱动，用 `-PmuzTarget=<id>` 选择，默认 `paper-26.2`：

```bash
./gradlew.bat -PmuzTarget=paper-26.1.2 clean shadowJar zipResourcePack zipCraftEngineBundle verifyRelocatedSnakeYaml
```

三个目标依次串行构建；仅执行 `shadowJar` 不会生成两种 ZIP。

可选目标：`paper-1.21.11`、`paper-26.1.2`、`paper-26.2`。

产物位于 `build/<targetId>/`（不是 `build/`），以 `paper-26.1.2` 为例：

- `build/paper-26.1.2/libs/MUZ-1.10.16-paper-26.1.2.jar` — 插件 JAR
- `build/paper-26.1.2/distributions/MUZ-resourcepack-1.10.16.zip` — 客户端资源包
- `build/paper-26.1.2/distributions/MUZ-craftengine-1.10.16.zip` — CraftEngine bundle

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

### 1.10.16（当前源码版本，验证中）

- 全屏 HUD 编辑器已完成稳定 DOM、四层拖动/缩放、右键坐标、Shift 微调/视图平移、默认吸附、同源 Hotbar 资源回退与异步保存链路；`DebugWebServerTest` 独立实跑 42/42 通过。
- 三目标九个 1.10.16 产物已完成 5114 项资源审计，包含归档完整性、目标 API/字节码、125 个 OGG、三档 hotbar、三档记牌器、SnakeYAML relocation、bundle 字节一致性与原版 hotbar 覆盖检查；三个 JAR 已复制到 `C:\PluginLibs` 且副本哈希一致。另外两个目标没有当次测试编译输出，未宣称其测试通过。
- 稳定 DOM 修复后的真实 Chromium/CDP 全流程已实际运行，但仍有 7 项连续拖动 dirty 断言失败；已通过四种 viewport、无应用 JS 错误、四层切换、40px 视图平移、Alt 轴锁、右键坐标、Hotbar 资源复用/失败回退、保存重载和 Fullscreen API。截图文件已更新。新版本尚未部署，游戏内资源包重载、客户端下载和进服渲染仍待人工确认。

### 1.10.15（历史源码与构建记录）

已完成的验证：

- 三个目标（`paper-1.21.11`、`paper-26.1.2`、`paper-26.2`）均完成干净发布构建（`shadowJar zipResourcePack zipCraftEngineBundle verifyRelocatedSnakeYaml`）
- 独立 JUnit 定向回归 150/150 通过，无跳过、无失败容器
- Debug Web 的 Chromium fixture 已验证全屏 MC 编辑器布局、`id='screen'` 舞台、图层交互和资源 22 个字段、15 格记牌器、9 槽 hotbar 和 640×360 几何；本轮修复图层选择、坐标输入、拖动结束及 Hotbar wheel 监听器之间缺少分号的真实脚本语法问题、缩放事件访问不到档位映射及缩放 pointermove 重建 DOM 的问题，并同步修正页面残留的“19 个字段”文案。真实浏览器已验证全屏画布、右键坐标、Shift 平移、拖拽、缩放期间 DOM 不重建、松手后刷新、Hotbar wheel、保存失败保留 dirty、保存成功淡出、Ctrl+S/Ctrl+R 防重复及重新读取丢弃未保存值；截图已更新到 `docs/screenshots/muz-hud-debug-web-initial.png` 和 `docs/screenshots/muz-hud-debug-web-final.png`，2026-09-12 真实 Chromium 已确认全屏 MC 画布、四层 HUD、浮动配置面板及 hotbar 构建期图标/数字可见
- 三个目标产物均无原版 `hotbar.png` / `hotbar_selection.png` 覆盖，JAR 已复制到 `C:\PluginLibs` 且副本哈希一致
- 资源包校验器接受全部目标格式 `pack_format` 75/84/88（修正原先只认 84/88 导致 `paper-1.21.11` 误判），并按各 scale 的 `downTier×22` 记牌器码位核对实际字体 JSON；本轮独立 JUnit HUD 定向回归 150/150 通过

Hotbar HUD 字形重构（本次改动）：

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
