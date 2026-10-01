package linmumua.doudizhu.game;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

/**
 * 桌内道具栏第四行「什么时候显示、什么时候不显示」的门禁清单。
 *
 * <p>背景：实服反馈「游戏内道具栏不显示」。排查时先要把门禁逐条确认下来——哪些条件会让第四行
 * 整条不产出、哪些不会。这些结论必须由测试锁定，否则以后任何一次改动都可能悄悄把某一加入
 * 或去掉，而症状只是「栏不见了」，没有任何日志。
 *
 * <p>【同时锁定的一个反直觉结论】：门禁里【没有】「道具数据已加载」这一条。数据没加载时
 * {@code bar(playerId)} 会回退到 {@code VirtualGadgetBar.defaults()}，第四行照常显示默认三件。
 * 所以「道具栏完全不显示」不可能由数据未加载造成——这条由源码契约钉住，避免以后有人
 * 按错误的直觉去加一个「数据未加载就不显示」的分支（那才会让栏在预取完成前整条消失）。
 */
class TableGadgetBarRowVisibilityTest {
    private static final Path SERVICE = Path.of(
        "src/main/java/linmumua/doudizhu/game/TableGadgetBarHudService.java");

    @Test
    void 五个门禁全部成立时才显示第四行() {
        assertTrue(TableGadgetBarHudService.barRowVisible(false, true, true, true, false),
            "未停止 + 道具开启 + 偏移可用 + 在 PLAYING 桌上 + 非机器人：必须显示");
    }

    @Test
    void 任一门禁不成立时第四行整条不产出() {
        assertFalse(TableGadgetBarHudService.barRowVisible(true, true, true, true, false),
            "服务已停止（关桌/停服）时不产出");
        assertFalse(TableGadgetBarHudService.barRowVisible(false, false, true, true, false),
            "table-gadgets.interaction.enabled=false 时不产出");
        assertFalse(TableGadgetBarHudService.barRowVisible(false, true, false, true, false),
            "CraftEngine 字体偏移层不可用时不产出（三层无法叠回同一格，会出现错位）");
        assertFalse(TableGadgetBarHudService.barRowVisible(false, true, true, false, false),
            "不在 PLAYING 阶段（大厅/发牌/结算）时不产出");
        assertFalse(TableGadgetBarHudService.barRowVisible(false, true, true, true, true),
            "机器人座位不产出");
    }

    /**
     * 门禁必须收口在一处，且渲染入口要先判空 TableManager。
     *
     * <p>这两条都是「改坏就出问题、但本地测不出来」的性质：门禁散开写就会像 1.10.5x 那批
     * lane 缺陷一样出现「两个入口条件不一致」；不判空就会在 TableManager 尚未装配/已拆解时
     * 从出牌 HUD 渲染链里抛 NPE，把这个玩家整条 BossBar 的刷新打死（表现是「不是栏不见了，
     * 是三行都不动了」——两种症状的排查方向完全不同，所以必须区分开）。
     */
    @Test
    void 门禁收口且渲染入口判空TableManager() throws IOException {
        String source = Files.readString(SERVICE);
        assertTrue(source.contains("static boolean barRowVisible("),
            "门禁判据必须是一个可测的独立方法");
        assertTrue(source.contains("if (!barRowVisible("),
            "渲染入口必须走这条唯一判据");
        assertTrue(source.contains("TableManager manager = plugin.getTableManager();")
                && source.contains("manager == null ? null : manager.getTableOf(playerId)"),
            "渲染入口必须先判空 TableManager（与 TableGadgetService.tick 的同口径空值早退一致）");
        assertFalse(source.contains("plugin.getTableManager().getTableOf(playerId)"),
            "不得再直接对 plugin.getTableManager() 解引用");
        // 「数据已加载」不是门禁：数据缺失只影响显示内容，不影响栏是否存在。
        assertTrue(source.contains("VirtualGadgetBar.defaults()"),
            "数据未加载必须回退默认三件，而不是让第四行消失");
    }
}
