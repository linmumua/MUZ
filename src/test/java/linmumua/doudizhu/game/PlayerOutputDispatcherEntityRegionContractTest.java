package linmumua.doudizhu.game;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

/**
 * @author linmumua
 * @Desc PlayerOutputDispatcher 的实体可见性 lane 契约：必须投递到实体 owner region，不得回到 player lane
 * @date 2026-09-25
 *
 * <p>背景（本轮真实缺陷）：Paper 的 {@code CraftPlayer#showEntity0/hideEntity0} 会读实体状态
 * ——{@code Entity#isVisibleByDefault()} 与 {@code CraftPlayer#canSee(entity)} 都经
 * {@code CraftEntity#getHandle()}，而该方法在 Folia 上带 owner region 门禁
 * （{@code TickThread.ensureTickThread(entity, "Accessing entity state off owning region's thread")}）。
 * 改造前这两个入口投在**玩家自己的 lane** 上，只要玩家与实体不在同一个 region 就必然抛/跳过，
 * 实服表现是同一玩家对 15 个桌面实体的 {@code showEntity} 全部被跳过、每个实体每 30 秒刷一条告警。
 *
 * <p>本类把「换个写法把可见性再放回 player lane」这条回归路径钉死，并把「生产装配必须用调度版 lane」
 * 与「执行 lane 内先复核归属再碰玩家 API」这两条顺序要求钉住（源码契约级证据，不是行为级）。
 */
class PlayerOutputDispatcherEntityRegionContractTest {
    private static final Path DISPATCHER = Path.of(
        "src/main/java/linmumua/doudizhu/game/PlayerOutputDispatcher.java");

    /**
     * 生产装配必须用「调度到实体 owner region」的 lane，不能退回直接执行版。
     *
     * <p>{@code EntityRegionLane.direct()} 是留给单测的：它不换 lane，用在生产上会立刻把缺陷带回来，
     * 所以这里同时钉住「插件构造器用的是调度版」与「另外两个构造器用的是 direct」。
     */
    @Test
    void 生产装配必须把实体可见性投递到实体owner_region() throws IOException {
        String source = read();

        String pluginConstructor = methodBody(source, "public PlayerOutputDispatcher(DoudizhuPlugin plugin)");
        assertTrue(pluginConstructor.contains("schedulingEntityRegionLane(plugin)"),
            "插件构造器必须装配调度版实体 lane");
        assertFalse(pluginConstructor.contains("EntityRegionLane.direct()"),
            "插件构造器不得改用 direct()：那等于继续在错误 lane 上读实体状态");
        assertTrue(source.contains("this(tasks, millisClock, EntityRegionLane.direct());"),
            "direct() 仍须保留给单测与「已确认在实体本 region」的场景");

        String lane = methodBody(source, "private static EntityRegionLane schedulingEntityRegionLane(");
        assertTrue(lane.contains("plugin.scheduler().runEntity(entity,"),
            "实体 lane 必须经 MuzScheduler.runEntity 路由到实体所属 region");
        assertFalse(lane.contains("runRegion("),
            "不得用 runRegion(location) 代替实体调度：那要先读实体位置，且语义是「坐标所属 region」");
        assertFalse(lane.contains("runPlayer("),
            "实体 lane 不得退回玩家 lane");
    }

    /**
     * 可见性入口本身不得再出现 player lane 投递；玩家 API 只允许出现在实体 lane 的执行体里。
     *
     * <p>这是本次修复的正面契约：两个 UUID 入口只做参数校验与 {@code runEntityVisibility} 调用。
     */
    @Test
    void 可见性入口不得把操作排进player_lane() throws IOException {
        String source = read();

        for (String signature : new String[] {
            "public void showEntity(UUID viewerId, Plugin plugin, UUID entityId)",
            "public void hideEntity(UUID viewerId, Plugin plugin, UUID entityId)"
        }) {
            String body = methodBody(source, signature);
            assertTrue(body.contains("runEntityVisibility("),
                signature + " 必须经统一的实体 lane 投递入口");
            assertFalse(body.contains("runPlayer("),
                signature + " 不得把实体可见性排进 player lane（缺陷复发路径）");
            assertFalse(body.contains("enqueuePlayer("),
                signature + " 不得把实体可见性排进 player uuid lane");
        }
    }

    /**
     * 实体 lane 的执行体必须先复核归属、再碰玩家 API，且玩家侧只做「解析 + 发可见性包」。
     *
     * <p>顺序断言是实质要求：先调用玩家 API 再判归属，等于把「在错误 region 读实体状态」这个原始缺陷
     * 换个位置留下——{@code player.showEntity} 内部第一时间就会读实体状态。
     */
    @Test
    void 实体lane内必须先复核归属再调用玩家API() throws IOException {
        String source = read();
        String body = methodBody(source, "private void applyVisibilityInEntityRegion(");

        int validity = body.indexOf("!entity.isValid()");
        int ownership = body.indexOf("Bukkit.isOwnedByCurrentRegion(entity)");
        int resolvePlayer = body.indexOf("currentPlayer(viewerId)");
        int invoke = body.indexOf("action.accept(viewer, entity)");
        assertTrue(validity >= 0, "执行体必须先判实体有效性（投递后被移除是正常结果）");
        assertTrue(ownership >= 0, "执行体必须复核实体是否仍属于当前 region");
        assertTrue(resolvePlayer >= 0, "执行体必须在 lane 内按 UUID 重新解析当前玩家");
        assertTrue(invoke >= 0, "执行体是唯一允许调用玩家可见性 API 的位置");
        assertTrue(validity < ownership && ownership < resolvePlayer && resolvePlayer < invoke,
            "顺序必须是「有效性 → 归属 → 解析玩家 → 调用 API」，否则会在错误 region 读实体状态");
    }

    /**
     * 决策依据（Folia 门禁的具体文案）必须留在源码里。
     *
     * <p>与本仓库既有的决策注释契约同口径：删掉「为什么不能放 player lane」的证据，下一个人就会
     * 以「统一走 player lane 更一致」为由改回来，缺陷随之复发。
     */
    @Test
    void Folia门禁依据必须留在源码注释里() throws IOException {
        String source = read();

        assertTrue(source.contains("Accessing entity state off owning region's thread"),
            "必须保留 Folia 抛错原文，作为「为何不能放 player lane」的证据");
        assertTrue(source.contains("CraftEntity#getHandle()"),
            "必须点明门禁来自 CraftEntity#getHandle()");
        assertTrue(source.contains("invertedVisibilityEntities"),
            "必须说明隐藏状态按「玩家 × 实体」记账、对跨 region 玩家同样生效");
    }

    /** 按签名取方法体（含声明行到方法级闭合行）。 */
    private static String methodBody(String source, String signature) {
        int start = source.indexOf(signature);
        assertTrue(start >= 0, "找不到方法：" + signature);
        int end = source.indexOf("\n    }", start);
        assertTrue(end > start, "方法体必须在方法级闭合行处界定：" + signature);
        return source.substring(start, end);
    }

    /**
     * 读取时统一换行为 LF。
     *
     * <p>{@code Files.readString} 不做换行翻译（Windows 工作区源码为 CRLF），而本类有跨行片段断言；
     * 不归一化会把「实现没退化」误报成失败（与 {@code PhysicalTableOwnerTickContractTest} 同法）。
     */
    private static String read() throws IOException {
        return Files.readString(DISPATCHER).replace("\r\n", "\n");
    }
}
