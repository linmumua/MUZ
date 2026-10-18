package linmumua.doudizhu.ui;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import linmumua.doudizhu.DoudizhuPlugin.AdminSetting;
import linmumua.doudizhu.ui.HandInventoryHolder.AdminPage;
import linmumua.doudizhu.ui.dialog.AdminMenuModel;
import org.junit.jupiter.api.Test;

/**
 * 锁住「悬停放大倍数」这一项的数值语义与菜单接线。
 *
 * <p>为什么值得单独一条：悬停突出效果一度被改成「沿法向向后平移」，
 * 平移会让射线与牌平面的交点跟着悬停漂移，形成抖动闭环，因此那一项已被删除，
 * 突出效果重新由这一项独家承担。它的上下限、步长归属、以及它在菜单里到底
 * 调不调得到，都是玩家能直接感知、而编译器完全管不到的东西。
 */
class HoverCardScaleSettingTest {
    private static final Path DIALOG_SCREEN = Path.of(
        "src/main/java/linmumua/doudizhu/ui/dialog/AdminDialogScreen.java");

    /**
     * 下限必须是 1.0：小于 1 等于悬停时牌反而缩小，
     * 那不是「突出」而是反向反馈，玩家会以为准星没对上。
     * 默认值 1.08 严格大于 1，即「开箱就有可见的放大」。
     */
    @Test
    void hoverScaleNeverShrinksTheCard() {
        assertEquals(1.0, AdminSetting.HOVER_CARD_SCALE.minValue(), 1.0E-12, "下限必须是 1.0，小于 1 会让悬停牌缩小");
        assertTrue(
            AdminSetting.HOVER_CARD_SCALE.defaultValue() > AdminSetting.HOVER_CARD_SCALE.minValue(),
            "默认值必须严格大于下限，否则开箱看不出悬停反馈"
        );
        assertEquals(1.08, AdminSetting.HOVER_CARD_SCALE.defaultValue(), 1.0E-12, "默认放大倍数应当是 1.08");
    }

    /**
     * 这一项跟随 GUI 的全局微调精度，不钉死自己的步长。
     *
     * <p>可用区间 [1.0, 2.5] 只有 1.5 宽，全局最小档 0.01 就能给出 150 个档位，
     * 够细了；额外钉一个固定步长只会让它和同页其它缩放项手感不一致。
     */
    @Test
    void hoverScaleFollowsTheGlobalStep() {
        assertFalse(
            AdminSetting.HOVER_CARD_SCALE.hasFixedStep(),
            "悬停放大倍数应当跟随全局调节精度，与同页其它缩放项手感一致"
        );
        assertTrue(
            (AdminSetting.HOVER_CARD_SCALE.maxValue() - AdminSetting.HOVER_CARD_SCALE.minValue()) >= 1.0,
            "可用区间太窄，放大倍数调不出层次"
        );
    }

    /**
     * 卡牌页每个槽位声明的设置，必须与旧箱子版完全一致，且悬停放大倍数仍在槽位 25。
     *
     * <p>【迁移说明：断言对象从「旧箱子绘制/分派源码」改为「AdminMenuModel 纯数据」】
     * 旧版本从 {@code HandGuiService} 的 {@code case DDZ_CARDS} 块里抠 {@code setItem(N, adminSettingItem(...))}，
     * 再从 {@code HandGuiListener.handleAdminCardsPage} 抠 {@code case N -> adjust(...)}，两边逐槽对齐，
     * 专门守「槽位与分派写死在两个文件里、加新项只补一边导致点错设置」这个坑。
     * 管理菜单迁到 Dialog 后，绘制与分派合并成一份 {@link AdminMenuModel}（页 → 槽位 → 动作），
     * 槽位错位这个坑在结构上被消除：按钮由 {@code AdminDialogScreen} 遍历 {@code AdminMenuModel} 生成。
     * 因此这里改为核对 AdminMenuModel 声明的卡牌页槽位→设置映射，并顺带钉住这个「单一来源」结构本身。
     */
    @Test
    void everyCardPageSlotDeclaresTheSettingItAdjusts() throws IOException {
        Map<Integer, AdminSetting> declared = settingSlotsOf(AdminPage.DDZ_CARDS);

        Map<Integer, AdminSetting> expected = new LinkedHashMap<>();
        expected.put(10, AdminSetting.PRIVATE_CARD_SCALE);
        expected.put(12, AdminSetting.PRIVATE_CARD_WIDTH_SCALE);
        expected.put(13, AdminSetting.PRIVATE_CARD_HEIGHT_SCALE);
        expected.put(14, AdminSetting.PRIVATE_CARD_DEPTH_SCALE);
        expected.put(19, AdminSetting.HAND_SPACING);
        expected.put(22, AdminSetting.CARD_DEPTH_OFFSET);
        expected.put(24, AdminSetting.HOVER_CARD_LIFT);
        expected.put(25, AdminSetting.HOVER_CARD_SCALE);
        expected.put(28, AdminSetting.GLOBAL_HAND_LATERAL);
        expected.put(29, AdminSetting.GLOBAL_HAND_VERTICAL);
        expected.put(30, AdminSetting.GLOBAL_HAND_DEPTH);

        assertEquals(expected, declared, "卡牌页声明的槽位与旧箱子版不一致，玩家会点不到或点到别处");
        assertEquals(AdminSetting.HOVER_CARD_SCALE, declared.get(25), "悬停放大倍数应当占卡牌页槽位 25");

        // 单一来源结构：绘制屏必须遍历 AdminMenuModel，而不是自己再写一份槽位表。
        String screen = Files.readString(DIALOG_SCREEN);
        assertTrue(screen.contains("AdminMenuModel.page(page)") || screen.contains("AdminMenuModel.page("),
            "管理 Dialog 屏必须从 AdminMenuModel 取页内容，否则槽位又会散成两份");
        assertTrue(screen.contains("model.entries()"),
            "管理 Dialog 屏必须遍历 AdminMenuModel 的按钮列表来生成动作");
    }

    /** 某一管理页里所有 SETTING 按钮的「旧槽位 → 设置项」映射。 */
    private static Map<Integer, AdminSetting> settingSlotsOf(AdminPage page) {
        Map<Integer, AdminSetting> slots = new LinkedHashMap<>();
        for (AdminMenuModel.Entry entry : AdminMenuModel.page(page).entries()) {
            if (entry.kind() == AdminMenuModel.Kind.SETTING) {
                slots.put(entry.slot(), (AdminSetting) entry.target());
            }
        }
        assertFalse(slots.isEmpty(), "从 AdminMenuModel 里没解析出任何槽位，这条测试的锚点已失效");
        return slots;
    }

    /** 这张表本身不能烂掉：卡牌页必须仍存在且仍是非空的真实页。 */
    @Test
    void cardPageIsStillDeclared() {
        List<AdminSetting> settings = AdminMenuModel.settingsOf(AdminPage.DDZ_CARDS);
        assertTrue(settings.contains(AdminSetting.HOVER_CARD_SCALE), "卡牌页必须包含悬停放大倍数");
    }
}
