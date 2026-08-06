# MUZ - 项目提示词

## 项目定位

MUZ 是一个 Minecraft 服务端牌桌插件，运行在 Paper / Purpur 上。

本项目的唯一核心领域是**实体牌桌上的棋牌对局**：斗地主为主玩法，麻将为在建玩法。所有后续开发、重构、命名、配置、文档必须围绕「玩家在世界里的物理牌桌上打牌」这一体验展开。

禁止引入与牌桌对局无关的业务概念，包括但不限于：经济系统自建实现、领地保护、聊天管理、任务系统、称号系统。经济只通过既有桥接读写，不自行记账。

## 作者与归属

项目作者只有 `linmumua`。

- `paper-plugin.yml` 的 `author` 字段只能是 `linmumua`。
- `build.gradle.kts` 的 `group` 只能是 `linmumua`。
- 发布说明、README、提交信息中不得出现其他作者名。
- 示例 YAML、示例代码中也只能出现 `linmumua`。

### 禁止 AI 署名（强约束）

**任何 AI 协作者禁止在本项目任何位置写入自己的署名、作者身份或协作痕迹。**

具体禁止：

- 禁止在 Git 提交信息中写入 `Co-authored-by:` 行，无论指向哪个 AI 身份。
- 禁止在提交信息中写入 `Generated with`、`Co-Authored-By`、`Assisted-by` 等任何形式的生成来源标注。
- 禁止在源码注释、文件头、Javadoc 中写入 AI 名称或模型名称。
- 禁止在 README、发布说明、CHANGELOG 中标注 AI 参与。
- 禁止在 `paper-plugin.yml`、`build.gradle.kts`、资源文件中添加 AI 相关的作者或贡献者字段。
- 禁止创建 `.github/` 下任何声明 AI 贡献者的元数据文件。

提交时必须确保：`git log --format='%an <%ae>%n%b'` 的输出中只出现 `linmumua`，不含任何第三方身份。

此约束的原因：GitHub 会依据提交元数据中的 `Co-authored-by` 生成贡献者记录，一旦写入就会在仓库侧栏与贡献者图中长期显示，且删除后缓存仍需约 24 小时才失效。

## 技术框架约束

**本项目不使用 TabooLib。** 主类 `linmumua.doudizhu.DoudizhuPlugin` 直接继承 `JavaPlugin`，打包走 Gradle `shadowJar`。

如果你（AI 协作者）的默认习惯是 TabooLib 项目结构，本项目必须放弃该假设：

- 生命周期用 `onLoad` / `onEnable` / `onDisable`，不用 `@Awake(LifeCycle.*)`。
- 周期任务用 Bukkit / Folia 调度器，不用 `@Schedule`。
- 命令在 `onEnable` 中通过 `CommandMap` 注册（`paper-plugin.yml` 不支持 `commands` 段）。
- 配置用 SnakeYAML，已重定位打进 JAR，不用 TabooLib Configuration。
- 禁止为了「符合框架惯例」而引入 TabooLib 依赖。

其他框架约束：

- 插件声明使用 `paper-plugin.yml`，不使用 `plugin.yml`。
- `folia-supported: true`，所有调度必须考虑 Folia 区域线程模型，禁止假设单一主线程。
- 软依赖（CraftEngine、PlaceholderAPI、Vault）通过 `dependencies.server` 声明；有直接 Java import 的必须 `join-classpath: true`，否则启动抛 `NoClassDefFoundError`。
- 仅通过插件管理器检测存在性的依赖（CMI、CMILib、EzEconomy、XConomy）保持 `join-classpath: false`。

## 版本与发布约定

- 版本号在 `build.gradle.kts` 的 `version` 中维护，当前 `1.10.3`。
- 每个发布对应多个 Paper 目标（如 `paper-26.1.2`、`paper-1.21.11`、`paper-26.2`），产物命名 `MUZ-<版本>-paper-<目标>.jar`。
- 禁止无版本号变化地替换已发布构建。
- 发布前必须完成干净构建，并确认测试 `failures=0`、`errors=0`、`skipped=0`。

## 沟通与编码规则

- 所有沟通、注释、文档使用简体中文。
- 类名、包名、方法名、字段名、配置键使用英文。
- 禁止使用 Unicode 转义表示中文或颜色符号；颜色直接写 `§`，或使用 MiniMessage 标签。
- **禁止删除已有注释。** 本项目注释大量记录了踩坑原因（如 `paper-plugin.yml` 中关于 `libraries` 和 `join-classpath` 的说明），删掉等于丢失事故记录。如需修正，在原基础上改写。
- 第三方资源 id、声音 id、GUI id、CraftEngine 家具 id 必须注明来源与用途。

## 玩家消息与文本规范

所有玩家可见文本必须来自 `config.yml` 中的消息配置，禁止在代码中硬编码。

```yaml
messages:
  prefix: "§7[§6MUZ§7] "
  no-permission: "§c你没有权限执行这个操作。"
  table-created: "§a牌桌已放置。"
  seat-occupied: "§c这个座位已经有人了。"
```

- 控制台日志、开发日志、调试日志可以直接写中文字面量。
- 新增任何玩家可见文本时，必须同步在 `config.yml` 中补默认值。
- 禁止出现「功能已实现但消息键缺失」的状态。

## 配置设计原则

- `config.yml`：全局开关、性能参数、默认行为、玩家消息、经济与场次配置。
- `resourcepack/`：资源包源文件，包含字体位图、模型、纹理。
- 运行时数据走数据库或独立数据文件，禁止写回 `config.yml`。

要求：

- 新增功能涉及配置时，必须同步更新默认配置与注释。
- 影响兼容性的配置改动，必须写明迁移方式。
- 禁止把运行时状态混进配置文件。

## 领域模型约束

按现有包结构划分职责：

- `model/`：牌、牌型、牌桌、座位等领域对象。
- `game/`：对局流程、回合状态、HUD 服务。
- `ai/`：机器人决策。
- `assets/`：资源包渲染，含字体位图与头像合成。
- `config/`：配置读取与缓存。
- `storage/`：持久化。
- `ui/`：菜单与交互界面。
- `world/`：牌桌实体、家具、判定框。
- `listener/`：事件监听。
- `command/`：命令处理。
- `compat/`：外部插件桥接。

约束：

- 禁止将所有职责堆进单个 God Class。
- 配置读取、运行时状态、执行逻辑、渲染必须分离。
- 跨包调用应通过服务接口，不直接操作对方内部状态。

## 功能边界

### 1. 牌桌与家具（`world/`）

- 负责：牌桌与椅子实体的放置、恢复、拆除、判定框、CraftEngine 家具交互。
- 不负责：对局规则、出牌合法性、渲染文本内容。

### 2. 对局流程（`game/`）

- 负责：发牌、叫地主、出牌轮转、结算、HUD 状态推送。
- 不负责：实体生成、资源包生成、经济落账。

### 3. 资源渲染（`assets/`）

- 负责：字体位图生成、牌面与头像渲染、`images.yml` 产出、BossBar 载荷构造。
- 不负责：对局逻辑、玩家权限判断。

### 4. 机器人（`ai/`）

- 负责：出牌决策、皮肤分配、难度策略。
- 不负责：真人玩家的输入处理。

### 5. 外部桥接（`compat/`）

- 负责：Vault / PlaceholderAPI / CraftEngine / 经济插件适配。
- 不负责：自建经济账本、自建占位符解析。

## 推荐包结构

```text
linmumua.doudizhu
├── DoudizhuPlugin.java
├── action/
├── ai/
├── assets/
├── command/
├── compat/
├── config/
├── game/
├── listener/
├── mahjong/
├── model/
├── placeholder/
├── room/
├── scheduler/
├── storage/
├── ui/
└── world/
```

## 生命周期建议

- `onLoad`：释放默认配置，读取配置，不接触世界与实体。
- `onEnable`：初始化服务 → 加载持久化数据 → 注册监听 → 通过 `CommandMap` 注册命令 → 启动周期任务 → 恢复已放置牌桌。
- `onDisable`：停止周期任务 → 结束进行中对局 → 持久化状态 → 清理实体与临时会话 → 释放缓存。

Folia 注意事项：

- 牌桌实体操作必须调度到对应区域线程。
- 区块未加载时 `remove()` 是空操作、`spawn()` 的实体也留不住，恢复逻辑必须先确保区块加载。

## 当前技术栈

| 层 | 技术 | 备注 |
|---|---|---|
| 平台 | Paper / Purpur | 支持 Folia |
| 主框架 | 原生 JavaPlugin | **不使用 TabooLib** |
| 打包 | Gradle Shadow (`shadowJar`) | SnakeYAML 重定位打包 |
| 配置 | SnakeYAML | 运行期唯一 YAML 方案 |
| 文本 | Adventure / MiniMessage | HUD 与消息渲染 |
| 资源 | CraftEngine | 家具与自定义模型 |
| 经济 | Vault / CMI / XConomy / EzEconomy | 仅桥接，不自建 |
| 语言 | Java | 无 Kotlin 运行时依赖 |
| 构建 | Gradle + Kotlin DSL | 多 Paper 目标 |

## 开发优先级建议

1. 对局闭环正确性：发牌、出牌合法性、结算、异常中断恢复。
2. 物理交互可靠性：坐下、入座、点按钮、拆桌、重启恢复。
3. 渲染正确性：牌面、HUD、头像、字体前进量。
4. 配置与可维护性：默认值完整、迁移路径清晰。
5. 性能与 Folia 兼容：调度正确、无主线程阻塞。

## 明确禁止事项

- 禁止 AI 在任何位置写入自己的署名或协作痕迹（见「禁止 AI 署名」）。
- 禁止作者字段出现 `linmumua` 以外的名字。
- 禁止引入 TabooLib 或将项目改造为 TabooLib 架构。
- 禁止偏离牌桌对局这一核心领域。
- 禁止硬编码玩家可见文本。
- 禁止删除已有注释。
- 禁止在主线程或区域线程执行耗时 I/O 与网络请求。
- 禁止吞异常不记录日志。
- 禁止新增功能不同步更新默认配置与注释。
- 禁止把所有职责堆进单个 God Class。
- 禁止无版本号变化地替换发布构建。
- 禁止将本地备份、参考源码、模型源文件、工具目录提交进仓库。
- 禁止在运行期使用 Jackson YAML 或 `YamlConfiguration` 作为主 YAML 方案。

## AI 协作者执行要求

- 参与开发前必须先完整阅读本文档。
- 必须先读相关代码再写代码，禁止基于框架惯例猜测本项目结构。
- 修改代码时必须遵守领域边界，不主动扩展无关功能。
- 新增玩家文本必须同步补配置。
- 提交前必须自查：无 AI 署名、无 `Co-authored-by`、无本地目录泄漏、无空白警告。
- 声称「测试通过」前必须实际执行测试并核对输出；禁止采信未执行的验证结果。
- 发现需求与项目核心冲突时，先说明风险，再提出收敛方案。
- 报告结论时必须区分「已验证」与「未验证」，禁止把假设当事实。

