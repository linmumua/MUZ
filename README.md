# MUZ — Minecraft 实体化斗地主牌桌

> 在 Minecraft 世界里摆一张真正的牌桌，坐下来打一局斗地主。

## 什么是 MUZ

MUZ 是一个 Paper / Purpur 服务端插件，用 Display Entity 在真实世界坐标上渲染完整的斗地主牌桌。玩家右键坐下，看到手牌一张张发出、翻转排序，然后叫分、出牌、结算——整个过程就像在桌游馆里打牌一样自然。
![Uploading donghua.gif…]()


所有界面元素（牌面、头像、记牌器、道具图标）都由资源包字形驱动，不依赖任何客户端模组。

## 核心特性

### 完整的对局体验

发牌时手牌按座位轮流出现，发完后逐张原地翻转 360° 并自动排序。翻转结束后有一个自愿明牌窗口，真人可以选择公开手牌换取额外倍率。整套叫分、出牌、炸弹加倍和结算流程完整可用，人数不够时由机器人补位 /muz bot add
并且兼容ds。

### 高度可自定义的界面

插件内置一个全屏 HUD 编辑器（Debug Web），在浏览器里就能拖动牌行、头像、记牌器和道具图标的位置，预览用的是服务端真实资源 PNG。横向偏移即时生效，纵向偏移经过资源重建和校验后安全应用。不合法的值会被明确拒绝，不会静默吸附到近似档位。

### 桌内道具互动

鸡蛋和番茄可以朝同桌对手投掷（无伤害），水桶会在目标身边生成短暂的水幕效果。被选中的目标会短时发光提示，所有效果实体在离桌、关桌或停服时自动清理。

<img width="480" height="270" alt="2026-09-20 00-03-14_batch_1" src="https://github.com/user-attachments/assets/3d892e41-6503-4f1d-9909-131c945fa701" />
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

### 管理命令（需要 `muz.admin` 权限）

| 命令 | 说明 |
|------|------|
| `/muz reload` | 重载配置（同步 Debug Web 生命周期） |
| `/muz debug add [数量]` | 生成自动对局观察桌（跳过方块占用检测，仅供测试） |
| `/muz debug remove [1-50\|all]` | 移除观察桌 |
| `/muz debug web [start\|stop]` | 管理 Debug Web HUD 编辑器 |
| `/muz give debug` | 发放个人 HUD 调试棒 |

## Debug Web HUD 编辑器

在 `config.yml` 中开启 `debug.web-ui.enabled: true` 并重载，从服务端本机访问 `http://127.0.0.1:2000` 即可打开编辑器。

编辑器提供一个 640×360 的 Minecraft 逻辑画布，牌行、头像行、记牌器和三道具 HUD 各为一个可拖动图层。预览使用的是服务端真实资源 PNG，不是 CSS 假图。左键拖拽移动图层，右键查看坐标，Shift+方向键精确微调。
<img width="1193" height="675" alt="image" src="https://github.com/user-attachments/assets/7dada293-fcd2-4026-97d7-649d5c6948d6" />

共 22 个可编辑的 HUD 运行期参数。横向偏移即时生效；纵向偏移会触发资源重建、CraftEngine 重载和 ZIP 校验，客户端需要重新下载资源包才能看到变化。任何一步失败都会自动回滚。

## 构建

```bash
./gradlew.bat -PmuzTarget=paper-26.1.2 clean shadowJar zipResourcePack zipCraftEngineBundle verifyRelocatedSnakeYaml
```

可选目标：`paper-1.21.11`、`paper-26.1.2`、`paper-26.2`。产物位于 `build/<targetId>/`。

构建期尺寸由根目录 `muz-resource-profile.yml` 控制。运行期支持四层连续 Y 偏移覆盖层，不需要为每个纵向位置生成独立资源档位。

## 技术栈

- **语言**：Java（Kotlin 仅用于 Gradle DSL）
- **框架**：原生 Paper `JavaPlugin` + TabooLib（工具层，不用注解生命周期）
- **渲染**：Display Entity + MiniMessage / Adventure
- **配置**：SnakeYAML 2.6（已 relocate 打进 JAR）
- **存储**：SQLite（默认） / MySQL（可选）
- **构建**：Gradle + Shadow Plugin，产物按目标版本隔离
- **测试**：JUnit 5，94 个测试文件

