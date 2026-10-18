package linmumua.doudizhu.game;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.lang.reflect.Field;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Consumer;
import linmumua.doudizhu.DoudizhuPlugin;
import linmumua.doudizhu.compat.MuMenuGadgetBridge;
import linmumua.doudizhu.game.GadgetPanelService.Status;
import org.bukkit.entity.Player;
import sun.misc.Unsafe;
import org.junit.jupiter.api.Test;

/**
 * {@link GadgetPanelService} 的判据与提示测试。
 *
 * <p>【为什么以纯判据为主】命令路径的每一层都要 Bukkit 的 Player / GameTable / Inventory，
 * 无法在无服务端环境端到端驱动；本轮把资格、边界、跨桌三处判据抽成纯函数逐条验算，
 * 端到端的「命令 → 选择/语音」由 Dialog 集成与 {@code TableGadgetBarHudService} 侧行为覆盖。
 * 提示文本用真实实例（Unsafe 构造，不跑构造器）逐项验证「每个状态都有可读提示」。
 */
class GadgetPanelServiceTest {
    /**
     * 资格判据真值表。
     *
     * <p>失败条件：条件判定顺序或语义被改动（例如把「机器人」判成可操作、或把「功能关闭」判成在线异常），
     * 这些都是会让命令给出错误提示的回归。
     */
    @Test
    void 资格判据逐条成立() {
        assertEquals(Status.OK, GadgetPanelService.eligibilityStatus(true, true, true, true, false));
        assertEquals(Status.NOT_IN_TABLE, GadgetPanelService.eligibilityStatus(false, true, true, true, false),
            "离线必须按「不在牌桌」拒绝");
        assertEquals(Status.NOT_IN_TABLE, GadgetPanelService.eligibilityStatus(true, true, false, true, false),
            "没有牌桌必须拒绝");
        assertEquals(Status.DISABLED, GadgetPanelService.eligibilityStatus(true, false, true, true, false),
            "道具功能关闭时必须拒绝");
        assertEquals(Status.NOT_PLAYING, GadgetPanelService.eligibilityStatus(true, true, true, false, false),
            "非对局阶段必须拒绝");
        assertEquals(Status.BOT, GadgetPanelService.eligibilityStatus(true, true, true, true, true),
            "机器人必须拒绝");
    }

    /**
     * 用户索引边界：只在 1..8 内合法（用户 1-based）。
     *
     * <p>失败条件：边界写成 0-based、或把上界写成 9 之类（会让 `/muz gadget select 9` 静默落到语音格
     * 或越界）。这是本轮「用户槽 1..8 / 内部 0..7」约定的可执行定义。
     */
    @Test
    void 用户索引只接受1到8() {
        for (int userSlot = 1; userSlot <= 8; userSlot++) {
            assertEquals(Status.OK, GadgetPanelService.slotBoundsStatus(userSlot), "1..8 必须合法：" + userSlot);
        }
        assertEquals(Status.OUT_OF_RANGE, GadgetPanelService.slotBoundsStatus(0), "0 越界");
        assertEquals(Status.OUT_OF_RANGE, GadgetPanelService.slotBoundsStatus(9), "9 越界");
        assertEquals(Status.OUT_OF_RANGE, GadgetPanelService.slotBoundsStatus(-1), "负数越界");
    }

    /**
     * 跨桌判据：只有「面板记录过桌名且与当前桌不同」才拒绝。
     *
     * <p>失败条件：无记录时也拒绝（会让编辑屏幕的选择接缝失灵）、或换桌时不拒绝（面板能操作别的桌的槽位）。
     */
    @Test
    void 跨桌判据只在记录桌与当前桌不同时拒绝() {
        assertEquals(Status.OK, GadgetPanelService.crossTableStatus(null, "A"), "没记录过就不判跨桌");
        assertEquals(Status.OK, GadgetPanelService.crossTableStatus("A", "A"), "同桌放行");
        assertEquals(Status.CROSS_TABLE, GadgetPanelService.crossTableStatus("A", "B"), "换桌拒绝");
        assertEquals(Status.CROSS_TABLE, GadgetPanelService.crossTableStatus("A", null), "已离桌也算跨桌");
    }

    /**
     * 每个状态都必须有可读中文提示（命令层直接把提示发给玩家，缺失就是「点了没反应」）。
     *
     * <p>失败条件：新增状态却漏写提示（switch 会编译不过，属于强约束），或提示为空串。
     */
    @Test
    void 每个状态都有非空提示() throws Exception {
        GadgetPanelService service = instance();
        for (Status status : EnumSet.allOf(Status.class)) {
            String hint = service.hint(status);
            assertNotNull(hint, "状态 " + status + " 没有提示");
            assertFalse(hint.isBlank(), "状态 " + status + " 的提示不能为空");
        }
    }

    /** 只有成功状态允许面板命令照常结束；失败状态必须可区分。 */
    @Test
    void 成功状态唯一() {
        int okCount = 0;
        for (Status status : EnumSet.allOf(Status.class)) {
            if (status == Status.OK) {
                okCount++;
            }
        }
        assertEquals(1, okCount);
    }

    /**
     * 终态清理必须**立即**清缓存并关掉内置面板，不再依赖玩家周期投递。
     *
     * <p>【为什么改了这一条】1.10.64 起椅侧双面板内置渲染，实体删除自身会投递到锚点 region；
     * 再经 player lane 排队反而会在玩家已离线时丢掉这次清理。这里锁的是新机制：
     * 调用即清缓存、即通知渲染器；重复调用安全（渲染器的 close 幂等）。
     */
    @Test
    void 清理立即清缓存并关闭内置面板且重复调用安全() throws Exception {
        RecordingPanels service = instance();
        RecordingDisplay display = display();
        service.applyChairPanelDisplay(display);
        UUID playerId = UUID.randomUUID();
        panelTables(service).put(playerId, "A");
        service.clearPlayer(playerId);
        service.clearPlayer(playerId);
        assertTrue(panelTables(service).isEmpty(), "终态清理不得留下旧桌记录");
        assertEquals(List.of(playerId, playerId), display.closed,
            "每次清理都必须通知渲染器；幂等由 close 自身保证");
    }

    /** 关桌只按座位快照清理该桌的人，不得误伤不在座位的玩家。 */
    @Test
    void 关桌只清理座位内玩家而不误伤他人() throws Exception {
        RecordingPanels service = instance();
        RecordingDisplay display = display();
        service.applyChairPanelDisplay(display);
        List<UUID> seats = List.of(UUID.randomUUID(), UUID.randomUUID());
        GameTable table = (GameTable) unsafe().allocateInstance(GameTable.class);
        Field field = GameTable.class.getDeclaredField("seats");
        field.setAccessible(true);
        field.set(table, new ArrayList<>(seats));
        UUID bystander = UUID.randomUUID();
        for (UUID id : seats) {
            panelTables(service).put(id, "A");
        }
        panelTables(service).put(bystander, "A");
        service.clearTable(table);
        assertEquals(seats, display.closed, "只清理座位快照里的 UUID");
        assertEquals("A", panelTables(service).get(bystander), "非座位玩家不受影响");
    }


    /** 行为用例之外钉住真正的入口，防止只写出从未被调用的清理方法。 */
    @Test
    void 清理必须接入离桌关局与玩家生命周期() throws Exception {
        String game = Files.readString(Path.of("src/main/java/linmumua/doudizhu/game/GameTable.java"));
        String leave = game.substring(game.indexOf("public void removePlayer(UUID"), game.indexOf("private int nextAvailableBotIndex"));
        assertTrue(leave.indexOf("getGadgetPanelService().clearPlayer(playerId)") >= 0);
        assertTrue(leave.indexOf("getGadgetPanelService().clearPlayer(playerId)") < leave.indexOf("discardSeatState(playerId)"));
        assertEquals(2, game.split("getGadgetPanelService\\(\\)\\.clearTable\\(this\\)", -1).length - 1,
            "强制关桌与回大厅都必须在清空座位之前清理");
        String listener = Files.readString(Path.of("src/main/java/linmumua/doudizhu/listener/TableGadgetLifecycleListener.java"));
        assertTrue(listener.contains("panels.closeChairPanels(player)"));
        assertTrue(listener.contains("panels.discardPlayer(player.getUniqueId())"));
    }

    @SuppressWarnings("unchecked")
    private static Map<UUID, String> panelTables(GadgetPanelService service) throws Exception {
        Field field = GadgetPanelService.class.getDeclaredField("panelTableNames");
        field.setAccessible(true);
        return (Map<UUID, String>) field.get(service);
    }

    private static final class RecordingPanels extends GadgetPanelService {
        private RecordingPanels(DoudizhuPlugin plugin, TableGadgetBarHudService bar,
            TableGadgetService gadgets, Consumer<Player> voiceOpener, MuMenuGadgetBridge bridge) {
            super(plugin, bar, gadgets, voiceOpener, bridge);
        }
    }

    /**
     * 记录 close 调用的内置渲染器替身。
     *
     * <p>【为什么需要它】清理路径的关键不变量是「缓存清掉之后必须真的通知渲染器拆实体」。
     * 只断言字段被清空，会让「忘记关面板」这种泄漏在测试里看起来通过。
     */
    private static final class RecordingDisplay extends ChairPanelDisplayService {
        private final List<UUID> closed = new ArrayList<>();

        private RecordingDisplay(DoudizhuPlugin plugin) {
            super(plugin);
        }

        @Override
        public void close(UUID playerId) {
            closed.add(playerId);
            super.close(playerId);
        }
    }

    private static RecordingDisplay display() throws Exception {
        return new RecordingDisplay(
            (DoudizhuPlugin) unsafe().allocateInstance(DoudizhuPlugin.class));
    }

    /** 用 Unsafe 构造一个不跑构造器的实例：只为了驱动不依赖任何字段的 {@code hint(...)}。 */
    private static RecordingPanels instance() throws Exception {
        Unsafe unsafe = unsafe();
        DoudizhuPlugin plugin = (DoudizhuPlugin) unsafe.allocateInstance(DoudizhuPlugin.class);
        TableGadgetBarHudService bar = (TableGadgetBarHudService) unsafe.allocateInstance(TableGadgetBarHudService.class);
        TableGadgetService gadgets = (TableGadgetService) unsafe.allocateInstance(TableGadgetService.class);
        return new RecordingPanels(plugin, bar, gadgets, player -> {
        }, new MuMenuGadgetBridge(null));
    }

    private static Unsafe unsafe() throws Exception {
        Field field = Unsafe.class.getDeclaredField("theUnsafe");
        field.setAccessible(true);
        return (Unsafe) field.get(null);
    }
}
