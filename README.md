# MUZ

一个面向 `Paper 1.21.11+` 与 `Purpur 1.21.11+` 的斗地主插件原型。

## 已实现

- 3 人建桌、加入、准备、开局
- 随机叫分，最高叫分者成为地主
- 发 17 张手牌，地主获得 3 张底牌
- 实体手牌选择与出牌
- 牌面贴图、按钮图标与完整斗地主音效资源包
- 单牌、对子、三张、三带一、三带二
- 四带二、四带两对、顺子、连对
- 飞机、飞机带单翼、飞机带双翼
- 炸弹、王炸与基础倍数结算
- 真人跟牌无牌可压时保留当前回合等待 20 tick；期间可点击现有「不要」立即跳过，未操作才自动不要；等待期间其他玩家的 ActionBar 与倒计时仍正常更新，机器人仍立即处理
- 对局中离线/踢出自动重置
- 自动生成 CraftEngine bundle，可导出到 `CraftEngine/resources/doudizhupaper`
- 提供一套默认关闭的第三方 AI gateway，可按 DeepSeek 或其他 OpenAI 兼容接口接入

## 命令

- `/muz create <high|mid|low|fun> [id]`
- `/muz create <doudizhu|texas> <high|mid|low|fun> [id]`
- `/muz set <牌桌id> <high|mid|low|fun>`
- `/muz chip mode <gold|chip>`
- `/muz chip setitem`
- `/muz chip balance <玩家> [数量]`
- `/muz remove <牌桌名>`
- `/muz reload`
- `/muz admin`
- `/muz bot <add|remove> [名字]`
- `/muz list`
- `/muz settings`（不在牌桌里也能打开）
- `/muz labels`（兼容旧命令，等同 `/muz settings`）
- `/muz status`
- `/muz forceend`
- `/muz debug web` — 查看 Debug Web 面板运行状态与本机访问地址（需 `muz.admin`）
- `/muz debug web start` — 手动启动 Debug Web 面板（需 `debug.web-ui.enabled: true`；只监听回环地址）
- `/muz debug web stop` — 停止 Debug Web 面板
- `/muz give debug` — 发放个人 HUD 调试棒；未进牌桌时可显示与 Debug Web 对照的游戏内 Trick HUD。右键循环牌行、头像行、记牌行，Shift+右键隐藏。

正式 Trick HUD 由 `TrickHudService` / `TrickHudView` 在出牌阶段按配置和玩家级调试棒行覆盖渲染；Debug Web 只负责回环地址上的 HUD 参数预览与运行期配置。`/muz give debug` 的调试棒只影响持有者个人，不写入运行期正式配置；`/muz debug show|stick|hud` 继续移除。

## Debug Web HUD 配置页

- 开启 `debug.web-ui.enabled: true` 后，仅可从服务端本机访问 `http://127.0.0.1:<port>` / `http://localhost:<port>`。
- 页面可编辑字段共 19 个：`trick-hud.enabled`、`trick-hud.avatar-scale`、`trick-hud.avatar-gap`、`trick-hud.card-step`、`trick-hud.card-height`、`trick-hud.offset-down`、`trick-hud.avatar-offset-down`、`trick-hud.offset-x`、`trick-hud.card-offset-x`、`trick-hud.avatar-offset-x`、`trick-hud.avatar-outline.enabled`、`trick-hud.avatar-outline.color`、`trick-hud.counter.enabled`、`trick-hud.counter.gap`、`trick-hud.counter.hide-exhausted`、`trick-hud.counter.offset-x`、`hotbar-hud.enabled`、`hotbar-hud.offset-x`、`hotbar-hud.offset-y`。
- 牌高、牌行下移、头像行下移等档位字段按当前资源包的合法档位白名单校验，非法值会拒绝保存而不是静默改写。
- 页面右侧的预览按 Minecraft 像素坐标系绘制，屏幕基准几何固定为 `640×360`，BossBar baseline=20，ActionBar bottom=360。行宽与字形前进量由服务端 `DebugHudConfigController.currentGeometry()` 汇总 `PackAssets`、`PlayerHeadRenderer`、`HotbarDebugOverlayWriter` 后下发（`cards` / `avatars` / `counters` 三个数组 + hotbar 六项），前端**只消费这份 `PreviewGeometry`**，不再由 JS 复算字形表；居中用**整数 MC 像素**执行（`baseLeft=floor((screenWidth-W)/2)`，行内 `floor((W-行宽)/2)`），牌/头像走 `top = bossBarBaselineY - ascent` 公式，hotbar 走 `actionBarBottomY - hotbarHeight + ascentDelta`。画出 BossBar 轨道、屏幕中线、屏幕底边作参照物；GUI 缩放可在页面上切换（2/3/4，默认 3）。
- **预览各层可鼠标拖动**：牌行、头像行、记牌行、hotbar 拖动时只更新页面里的待提交偏移值（档位字段会吸附到最近的合法档）。页面提供 `snapToggle` Minecraft 风格吸附开关，默认开启；它只改变当前页面拖动行为，不混入 19 个 HUD patch，阈值按实际 layer 几何使用 Minecraft 像素。中心线与 Alt 首次有效位移锁轴状态保留。牌行来自 `geometry.cards` 按 `height` 查档，头像行来自 `geometry.avatars` 按 `scale` 查档并根据描边开关选 `plainAdvance` / `outlinedAdvance`；记牌器只共享水平 advance（每格 `advance` 逐格累加），垂直沿用现有普通 MiniMessage 预览语义。拖动期间只更新当前层的 CSS `transform`，不重建预览 DOM；松手后才完整刷新。拖动只标记未保存，需点「保存并应用」才写回 `config.yml`。
- 「保存并应用」只写回本次提交的白名单键；保存链路仍严格按“异步写入 `config.yml` 与当前 hotbar overlay YAML → 主线程执行 `ce reload pack` → 主线程应用 `applyHudRuntimeStateFromWeb()` → 返回成功并发布 Snapshot”执行。空 patch 不触发资源流程，配置或资源写入失败不会报成功。两个 overlay YAML 使用临时文件后原子替换，协调器每次提交在主线程重新解析 CraftEngine 路径，关闭后拒绝新任务并使排队任务失效。
- Web 保存与 Web 磁盘重载共用单线程队列和插件提供的 HUD 配置锁：Web 自身不会让两次请求乱序，也不会把 `config.yml` 写盘放回主线程。该锁是清晰的 Web 快照边界，不等同于全局配置事务；旧的管理菜单或其他非 Web 配置入口若并发改写共享 `MuzYamlConfig`，仍需后续统一配置层才能完全消除竞态。
- 当前没有独立的 Trick HUD 运行期 CE 字形资源，Trick HUD 通过运行时配置与玩家级调试棒行覆盖应用；Debug Web 不会臆造 Trick HUD 字形资源。运行期 CE overlay 目前只有 hotbar 覆盖层，客户端必须重新下载资源包才能看到 `offset-y` 的变化。
- 「重新读取配置」同样通过 HUD 专用异步重载链路，不调用完整 `reloadVisualState`，不会重建物理牌桌；它会丢弃网页里尚未保存的改动。
- `DebugWebServerTest` 已验证下发 geometry schema、15 格记牌器 fixture、资源 API 对齐、旧几何魔数已移除，以及拖动期间不重建预览 DOM、松手后再刷新。
- Hotbar 由 `hotbar-hud.enabled`、牌桌阶段与座位状态共同控制：**仅向处于 `GamePhase.PLAYING` 正式出牌阶段的牌桌在线真人座位显示**。出牌阶段推送 182×22 不透明遮罩，完整盖住原版 9 槽背景，并在中央显示红/橙/黄/绿/蓝 5 个调试槽；`hotbar-hud.offset-x` 走运行期负空格，`hotbar-hud.offset-y` 走覆盖层 ascent。`LOBBY`、`BIDDING`、`DOUBLING`、结算、离桌、停服及不在牌桌的普通玩家始终保持或恢复原版 9 槽物品栏。所有非 `PLAYING` 阶段的对局提示走普通 ActionBar。资源包不会覆盖 `minecraft` 原版 `hotbar.png` / `hotbar_selection.png`，插件启动导出 bundle 时还会清除旧版本遗留的这两张全局透明贴图，因此不会继续影响普通物品栏。
- Hotbar 两个偏移的生效方式不同：`offset-x` 走负空格**运行期即时生效**；`offset-y` 要写成资源包字形的 ascent，会自动触发 `ce reload pack` 重建客户端资源包，**客户端需重新下载资源包才能看到**，因此纵向调整不是即时的。`hotbar-hud.glyph-ascent`、`hotbar-hud.slot-count` 仍是纯文档键，运行期不读；当前遮罩固定为 182×22、advance 为 183。若现有 `plugins/CraftEngine/generated/resource_pack.zip` 仍残留旧版全局 hotbar 条目，先安全备份并移走该 ZIP，再执行 `ce reload pack`，最后检查 ZIP 条目。
- 不要通过运行期配置修改构建期 hotbar 字形参数：`glyph-ascent`、`slot-count` 运行期不读取；当前遮罩固定为 182×22、advance 为 183，尺寸、码位与 advance 必须同步修改 `build.gradle.kts` 和 `PackAssets`。要调垂直位置请用 `offset-y`。

## 构建

构建目标由 `MuzTarget` 表驱动，用 `-PmuzTarget=<id>` 选择，默认 `paper-26.2`：

```powershell
./gradlew.bat build -PmuzTarget=paper-26.1.2
```

可选目标：`paper-1.21.11`、`paper-26.1.2`、`paper-26.2`。

产物位于 `build/<targetId>/`（**不是** `build/`），以 `paper-26.1.2` 为例：

- `build/paper-26.1.2/libs/MUZ-1.10.11-paper-26.1.2.jar`
- `build/paper-26.1.2/libs/MUZ-1.10.11-sources.jar`
- `build/paper-26.1.2/distributions/MUZ-resourcepack-1.10.11.zip`
- `build/paper-26.1.2/distributions/MUZ-craftengine-1.10.11.zip`

推荐把与服务端版本对应的 `MUZ-1.10.11-<targetId>.jar` 放进服务端 `plugins/`。
如果你不用 CraftEngine，就给客户端下发 `MUZ-resourcepack-1.10.11.zip`。
如果你使用 CraftEngine，可以直接用 `MUZ-craftengine-1.10.11.zip`，或者让插件在检测到 CraftEngine 后自动把 bundle 导出到其数据目录。

## 说明

- 这个版本优先实现可玩的核心流程，暂时没有 Bot 和积分持久化。
- 现在已经有最简单可用的机器人，可以补满 3 人桌。
- 现在已经支持实体桌椅与桌边交互按钮，桌体使用 CraftEngine 家具资源。
- 现在可选接入 Vault；启用后德州桌的筹码会直接同步到服务器经济余额，支持通过 `config.yml` 配置汇率。
- 现在会额外诊断 `CMI / EzEconomy / XConomy / Vault` 挂钩状态，并支持用 `economy.vault.preferred-providers` 指定优先使用哪个 Vault provider。
- 现在牌桌支持 `low / mid / high / fun` 四种场次；顶栏会显示场次名和倍率，`fun` 娱乐场默认不走金币结算。
- 现在支持 `/muz set` 直接修改已创建牌桌的场次倍率，也支持全局金币/筹码两种结算模式。
- 筹码模式会用全局玩家筹码余额结算，支持 `%muz_chip_<玩家>%` 占位符。
- 现在支持 SQL 持久化，默认使用插件目录内的 SQLite 文件，也可切换到 MySQL。
- 重启后会自动恢复已放置牌桌；并新增 `/muz history [玩家] [页码]` 查看历史战绩。
- 每个座位上方会显示玩家/机器人信息、准备状态、角色和剩余牌数。
- 手牌与出牌区的牌现在是固定朝向的摆放实体，不再是朝向玩家的 billboard。
- 对手手牌现在会以背面牌实体围桌显示，自己只会看到自己的正面手牌。
- 叫分/出牌/不要/清选/点数切换都可以直接通过桌下小按钮完成。
- `create` 现在会直接创建并放置牌桌。
- 可以通过 `/muz reload` 动态重载配置、重新导出 CE bundle，并刷新已放置的实体牌桌。
- 可以通过 `/muz admin` 打开管理员全局配置菜单；现在已经拆成 `模型 / 渲染 / 音频 / 机器人` 四页，且名字调节已经进一步拆到独立子页，左键增加、右键减少、Shift 可按 10 倍步长调整。
- 现在支持单独的个人微调菜单 GUI，不在牌桌里也能打开；每位玩家都可以单独调整自己的私人手牌横向/竖向/纵深偏移、左右牌间距，并单独切换点数标签显示；Shift 也可以按 10 倍步长调整。
- 手牌箱子 GUI 已移除，当前只保留实体手牌与桌边按钮交互。
- `config.yml` 里桌子和椅子现在直接使用完整 `item-model` 写法，像 `magicstore:medieval_furnitures_fullpack_v4_6` 这种可以直接填写。
- `config.yml` 里的私人手牌偏移是全局默认值，个人微调会在这个基础上额外叠加。
- 插件启动和 `/muz reload` 时会自动补全空配置、迁移旧版 `namespace/model-path` 配置，并对错误模型配置给出警告后回退到默认桌椅。
- GUI 图标与入口定义现在直接跟随主配置和页面逻辑维护，不再依赖额外的独立图标配置文件。
- 可以通过 `config.yml` 开关控制牌面上的全息字符标签，并支持仅在重复点数牌上显示。
- GUI 现在已经使用独立命名空间 `item_model` 资源，不再是纯原版占位图标。
- 服务器重启时会自动检查并导出 CraftEngine bundle；执行 `/ce reload` 前也会自动检查并导出。
- `config.yml` 现在会生成 `ai.deepseek` 配置段，默认关闭，但保留了公开的 OpenAI 兼容 AI gateway；只改 `base-url / api-key / model` 就能切换到 DeepSeek 或其他兼容第三方接口。
- 开局会随机播放斗地主 BGM，播完会继续下一首，结束时停止并播放结算音效。
- 判型与流程参考了 `tml104/-Minecraft-Dou-Dizhu` 的状态机思路，并借鉴了 `Arbousier1/MahjongEngine` 的 Paper 1.21.11+ 与 CraftEngine bundle 构建路线。

## 第三方 AI API

- 插件会暴露 `DoudizhuPlugin#getAiChatGateway()`，返回一个可直接调用的 OpenAI 兼容 gateway。
- 默认配置走 DeepSeek 兼容格式；官方文档可参考 [DeepSeek API 文档](https://api-docs.deepseek.com/zh-cn/)。
- 如果你要接别的兼容平台，只需要改 `config.yml` 里的 `ai.deepseek.base-url`、`api-key` 和 `model`。

```java
AiChatGateway gateway = plugin.getAiChatGateway();
if (gateway.isEnabled()) {
    AiChatGateway.ChatResponse response = gateway.chat(
        new AiChatGateway.ChatRequest(
            List.of(AiChatGateway.Message.user("帮我总结这一局的战绩")),
            null,
            null,
            null
        )
    );
    String reply = response.content();
}
```
