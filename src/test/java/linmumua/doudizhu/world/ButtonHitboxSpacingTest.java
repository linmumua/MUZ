package linmumua.doudizhu.world;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * 相邻文字按钮的精确判定框不能重叠，否则点击一个会误触旁边按钮。
 *
 * <p>【1.10.73：BIDDING 不再是"固定四按钮"】抢地主加赛（{@code bidRound == 2}）且明牌资格仍在时，
 * 当前叫分者看到的是**四个叫分 + 明牌共五个按钮**，非当前叫分者只看到自己的明牌按钮。
 * 因此本测试不再自己抄一份偏移数组，而是直接取
 * {@link PhysicalTableManager#biddingWithRevealStates(String)} /
 * {@link PhysicalTableManager#biddingRevealOnlyStates(String)} 返回的**真实 ActionButtonState**，
 * 用它们的 offsetX 与 label 做碰撞断言——生产改布局，这里立刻跟着变红。
 */
class ButtonHitboxSpacingTest {
    // 直接取生产默认值：配置中的 0 是「使用代码默认值」哨兵，不能拿旧测试里的 0.20 冒充线上值。
    private static final float ACTION_LABEL_SCALE = (float) linmumua.doudizhu.DoudizhuPlugin.AdminSetting
        .ACTION_LABEL_SCALE.defaultValue();
    private static final double BUTTON_SPACING_SCALE = linmumua.doudizhu.DoudizhuPlugin.AdminSetting
        .BUTTON_SPACING.defaultValue();
    private static final double ARC_ANGLE_SMALL = linmumua.doudizhu.DoudizhuPlugin.AdminSetting
        .BUTTON_ARC_SMALL_ANGLE.defaultValue();
    private static final double ARC_ANGLE_LARGE = linmumua.doudizhu.DoudizhuPlugin.AdminSetting
        .BUTTON_ARC_LARGE_ANGLE.defaultValue();
    private static final double ARC_RADIUS_SMALL = linmumua.doudizhu.DoudizhuPlugin.AdminSetting
        .BUTTON_ARC_SMALL_RADIUS.defaultValue();
    private static final double ARC_RADIUS_LARGE = linmumua.doudizhu.DoudizhuPlugin.AdminSetting
        .BUTTON_ARC_LARGE_RADIUS.defaultValue();

    /** 与渲染一致的横向映射：先把 offset 按列表内最大绝对值归一化，再走弧线。 */
    private static double lateralOf(double offset, double maxOffset, double arcAngleDegrees, double radius) {
        double normalized = maxOffset <= 0.0001 ? 0.0 : offset / maxOffset;
        return Math.sin(normalized * Math.toRadians(arcAngleDegrees) * BUTTON_SPACING_SCALE) * radius;
    }

    private static double maxAbsOffset(List<PhysicalTableManager.ActionButtonState> states) {
        double max = 0.0;
        for (PhysicalTableManager.ActionButtonState state : states) {
            max = Math.max(max, Math.abs(state.offsetX()));
        }
        return max;
    }

    /** 逐个相邻对断言：中心距必须大于两个判定框的半宽和（精确的不重叠条件）。 */
    private static void assertNoOverlap(List<PhysicalTableManager.ActionButtonState> states,
                                        double arcAngleDegrees, double radius, String scene) {
        double maxOffset = maxAbsOffset(states);
        for (int i = 0; i + 1 < states.size(); i++) {
            PhysicalTableManager.ActionButtonState left = states.get(i);
            PhysicalTableManager.ActionButtonState right = states.get(i + 1);
            double a = lateralOf(left.offsetX(), maxOffset, arcAngleDegrees, radius);
            double b = lateralOf(right.offsetX(), maxOffset, arcAngleDegrees, radius);
            double gap = Math.abs(b - a);
            float leftWidth = PhysicalTableManager.resolveHitboxWidth(left.label(), ACTION_LABEL_SCALE, true);
            float rightWidth = PhysicalTableManager.resolveHitboxWidth(right.label(), ACTION_LABEL_SCALE, true);
            float occupied = (leftWidth + rightWidth) * 0.5f;
            assertTrue(gap > occupied,
                scene + " 相邻按钮判定框重叠：「" + left.label() + "」与「" + right.label()
                    + "」间距=" + gap + " 两框半宽和=" + occupied);
        }
    }

    @Test
    void lobbyButtonsDoNotOverlap() {
        double gap = minimumGap(new double[] {-0.64, 0.0, 0.64}, ARC_ANGLE_SMALL, ARC_RADIUS_SMALL);
        float widest = PhysicalTableManager.resolveHitboxWidth("准备", ACTION_LABEL_SCALE, true);

        assertTrue(gap > widest, "LOBBY 相邻按钮判定框重叠：间距=" + gap + " 框宽=" + widest);
    }

    /** 第一个叫分轮：直接消费生产使用的四键状态，不手抄偏移数组。 */
    @Test
    void biddingFirstRoundKeepsFourBidButtonsWithoutOverlap() {
        List<PhysicalTableManager.ActionButtonState> states =
            PhysicalTableManager.biddingFirstRoundStates();

        assertEquals(4, states.size(), "第一轮叫分仍是四个按钮");
        assertNoOverlap(states, ARC_ANGLE_LARGE, ARC_RADIUS_LARGE, "BIDDING 首轮四按钮");
    }

    /**
     * 抢地主加赛期（当前叫分者）：**五个按钮**（四个叫分 + 明牌）不得互相碰撞。
     *
     * <p>这条替换掉旧的「BIDDING 固定四按钮 / 明牌已移到 REVEALING」断言——那是 1.10.73 之前的口径，
     * 那时延长期根本不产出明牌按钮，玩家看不到入口。
     */
    @Test
    void biddingTieBreakFiveButtonsDoNotOverlap() {
        List<PhysicalTableManager.ActionButtonState> states =
            PhysicalTableManager.biddingWithRevealStates("明牌 ×1");

        assertEquals(5, states.size(), "当前叫分者在延长期必须看到四个叫分 + 明牌共五个按钮");
        assertEquals(1, states.stream().filter(s -> s.label().equals("明牌 ×1")).count(),
            "五个按钮里恰好一个明牌按钮");
        assertEquals(4, states.stream().filter(s -> s.label().startsWith("叫") || s.label().equals("不叫")).count(),
            "四个叫分按钮一个都不能少（明牌是追加，不是替换）");
        assertNoOverlap(states, ARC_ANGLE_LARGE, ARC_RADIUS_LARGE, "BIDDING 加赛五按钮");
    }

    /** 抢地主加赛期（非当前叫分者）：只有自己的明牌按钮，单个按钮不存在碰撞，但必须仍可点。 */
    @Test
    void biddingTieBreakNonCurrentBidderGetsOnlyRevealButton() {
        List<PhysicalTableManager.ActionButtonState> states =
            PhysicalTableManager.biddingRevealOnlyStates("明牌 ×1");

        assertEquals(1, states.size(), "非当前叫分者只应该有明牌按钮");
        assertEquals("明牌 ×1", states.getFirst().label());
        assertEquals(PhysicalTableManager.ButtonAction.REVEAL_HAND, states.getFirst().action(),
            "按钮动作必须仍是 REVEAL_HAND");
        // 单个按钮居中：偏移为 0，判定框不会与任何东西重叠。
        assertEquals(0.0, states.getFirst().offsetX(), 1.0e-9);
    }

    @Test
    void doublingButtonsDoNotReceiveArtificialExtraWidth() {
        double gap = minimumGap(new double[] {-0.40, 0.40}, ARC_ANGLE_SMALL, ARC_RADIUS_SMALL);
        float width = PhysicalTableManager.resolveHitboxWidth("加倍", ACTION_LABEL_SCALE, true);

        assertTrue(gap > width, "加倍按钮判定框重叠：间距=" + gap + " 框宽=" + width);
    }

    @Test
    void largerConfiguredTextStillUsesItsExactWidth() {
        double gap = minimumGap(new double[] {-0.64, 0.0, 0.64}, ARC_ANGLE_SMALL, ARC_RADIUS_SMALL);
        float widthAtLargerScale = PhysicalTableManager.resolveHitboxWidth("准备", 0.30f, true);

        assertTrue(gap > widthAtLargerScale, "文字缩放到 0.30 后判定框重叠：间距=" + gap + " 框宽=" + widthAtLargerScale);
    }

    private static double minimumGap(double[] offsets, double arcAngleDegrees, double radius) {
        double maxOffset = 0.0;
        for (double offset : offsets) {
            maxOffset = Math.max(maxOffset, Math.abs(offset));
        }
        double smallest = Double.MAX_VALUE;
        for (int i = 0; i + 1 < offsets.length; i++) {
            double a = lateralOf(offsets[i], maxOffset, arcAngleDegrees, radius);
            double b = lateralOf(offsets[i + 1], maxOffset, arcAngleDegrees, radius);
            smallest = Math.min(smallest, Math.abs(b - a));
        }
        return smallest;
    }
}
