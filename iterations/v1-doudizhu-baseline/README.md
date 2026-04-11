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
- 无牌可压时自动跳过
- 对局中离线/踢出自动重置
- 自动生成 CraftEngine bundle，可导出到 `CraftEngine/resources/doudizhupaper`

## 命令

- `/muz place <牌桌名>`
- `/muz remove <牌桌名>`
- `/muz reload`
- `/muz admin`
- `/muz bot <add|remove> [名字]`
- `/muz list`
- `/muz join <牌桌名>`
- `/muz leave`
- `/muz settings`（不在牌桌里也能打开）
- `/muz labels`（兼容旧命令，等同 `/muz settings`）
- `/muz status`
- `/muz forceend`

## 构建

```powershell
./gradlew.bat build
```

产物位于：

- `build/libs/MUZ-1.2.2.jar`
- `build/libs/MUZ-1.2.2-dev.jar`
- `build/distributions/MUZ-resourcepack-1.2.2.zip`
- `build/distributions/MUZ-craftengine-1.2.2.zip`

推荐把重映射后的 `MUZ-1.2.2.jar` 放进服务端 `plugins/`。
如果你不用 CraftEngine，就给客户端下发 `MUZ-resourcepack-1.2.2.zip`。
如果你使用 CraftEngine，可以直接用 `MUZ-craftengine-1.2.2.zip`，或者让插件在检测到 CraftEngine 后自动把 bundle 导出到其数据目录。

## 说明

- 这个版本优先实现可玩的核心流程，暂时没有 Bot 和积分持久化。
- 现在已经有最简单可用的机器人，可以补满 3 人桌。
- 现在已经支持实体桌椅与桌边交互按钮，桌体使用 CraftEngine 家具资源。
- 每个座位上方会显示玩家/机器人信息、准备状态、角色和剩余牌数。
- 手牌与出牌区的牌现在是固定朝向的摆放实体，不再是朝向玩家的 billboard。
- 对手手牌现在会以背面牌实体围桌显示，自己只会看到自己的正面手牌。
- 叫分/出牌/不要/清选/点数切换都可以直接通过桌下小按钮完成。
- `place` 现在会在需要时自动创建牌桌，不再需要单独执行 `create`。
- 可以通过 `/muz reload` 动态重载配置、重新导出 CE bundle，并刷新已放置的实体牌桌。
- 可以通过 `/muz admin` 打开管理员全局配置菜单；现在已经拆成 `模型 / 渲染 / 音频 / 机器人` 四页，左键增加、右键减少、Shift 可按 10 倍步长调整。
- 现在支持单独的个人微调菜单 GUI，不在牌桌里也能打开；每位玩家都可以单独调整自己的私人手牌横向/竖向/纵深偏移、左右牌间距、出牌预览大小，并单独切换点数标签显示；Shift 也可以按 10 倍步长调整。
- 手牌箱子 GUI 已移除，当前只保留实体手牌与桌边按钮交互。
- `config.yml` 里桌子和椅子现在直接使用完整 `item-model` 写法，像 `magicstore:medieval_furnitures_fullpack_v4_6` 这种可以直接填写。
- `config.yml` 里的私人手牌偏移是全局默认值，个人微调会在这个基础上额外叠加。
- 插件启动和 `/muz reload` 时会自动补全空配置、迁移旧版 `namespace/model-path` 配置，并对错误模型配置给出警告后回退到默认桌椅。
- `gui-icons.yml` 现在会随插件一起生成到数据目录，用来集中配置这些 GUI 页面使用的图标 id，结构风格接近 CE 的 yml 配置。
- 可以通过 `config.yml` 开关控制牌面上的全息字符标签，并支持仅在重复点数牌上显示。
- GUI 现在已经使用独立命名空间 `item_model` 资源，不再是纯原版占位图标。
- 服务器重启时会自动检查并导出 CraftEngine bundle；执行 `/ce reload` 前也会自动检查并导出。
- 开局会随机播放斗地主 BGM，播完会继续下一首，结束时停止并播放结算音效。
- 判型与流程参考了 `tml104/-Minecraft-Dou-Dizhu` 的状态机思路，并借鉴了 `Arbousier1/MahjongEngine` 的 Paper 1.21.11+ 与 CraftEngine bundle 构建路线。
