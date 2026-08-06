package linmumua.doudizhu.ui;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import linmumua.doudizhu.DoudizhuPlugin.AdminSetting;
import org.junit.jupiter.api.Test;

/**
 * 锁住「悬停放大倍数」这一项的数值语义与菜单接线。
 *
 * 为什么值得单独一条：悬停突出效果一度被改成「沿法向向后平移」，
 * 平移会让射线与牌平面的交点跟着悬停漂移，形成抖动闭环，因此那一项已被删除，
 * 突出效果重新由这一项独家承担。它的上下限、步长归属、以及它在菜单里到底
 * 调不调得到，都是玩家能直接感知、而编译器完全管不到的东西。
 */
class HoverCardScaleSettingTest {
    private static final Path GUI = Path.of("src/main/java/linmumua/doudizhu/ui/HandGuiService.java");
    private static final Path LISTENER = Path.of("src/main/java/linmumua/doudizhu/listener/HandGuiListener.java");

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
     * 可用区间 [1.0, 2.5] 只有 1.5 宽，全局最小档 0.01 就能给出 150 个档位，
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
     * 手牌页每个槽位在 GUI 里画的设置，必须和 Listener 里同一槽位调整的设置一致。
     *
     * 这条守的是真实踩过的坑：槽位与 case 分开写在两个文件里，
     * 加新项时很容易只补一边，或者两边编号错位一格——
     * 结果是点了「悬停放大倍数」实际在改「悬停上移高度」，
     * 而 AdminSettingGuiCoverageTest 只查「这一项有没有出现过」，查不出错位。
     */
    @Test
    void everyCardPageSlotAdjustsTheSettingItDraws() throws IOException {
        Map<Integer, String> drawn = drawnCardPageSlots();
        Map<Integer, String> handled = handledCardPageSlots();

        assertEquals(drawn, handled, "手牌页的槽位与 adjust 分派对不上，点击会改错设置");
        assertEquals(
            "HOVER_CARD_SCALE",
            drawn.get(25),
            "悬停放大倍数应当占手牌页槽位 25"
        );
    }

    private static Map<Integer, String> drawnCardPageSlots() throws IOException {
        String block = section(Files.readString(GUI), "case DDZ_CARDS -> {", "\n            }");
        Pattern pattern = Pattern.compile(
            "setItem\\((\\d+), adminSettingItem\\(Material\\.\\w+, DoudizhuPlugin\\.AdminSetting\\.(\\w+)\\)\\)"
        );
        return collect(pattern, block);
    }

    private static Map<Integer, String> handledCardPageSlots() throws IOException {
        String block = section(Files.readString(LISTENER), "private void handleAdminCardsPage", "\n    }");
        Pattern pattern = Pattern.compile(
            "case (\\d+) -> adjust\\(player, DoudizhuPlugin\\.AdminSetting\\.(\\w+),"
        );
        return collect(pattern, block);
    }

    private static Map<Integer, String> collect(Pattern pattern, String block) {
        Map<Integer, String> found = new LinkedHashMap<>();
        Matcher matcher = pattern.matcher(block);
        while (matcher.find()) {
            found.put(Integer.parseInt(matcher.group(1)), matcher.group(2));
        }
        assertFalse(found.isEmpty(), "没有从源码里解析出任何槽位，说明这条测试的正则已经跟代码脱节");
        return found;
    }

    private static String section(String source, String from, String to) {
        int start = source.indexOf(from);
        assertTrue(start >= 0, "源码里找不到 " + from + "，这条测试的锚点已失效");
        int end = source.indexOf(to, start);
        assertTrue(end > start, "源码里找不到 " + from + " 之后的结束锚点");
        return source.substring(start, end);
    }
}
