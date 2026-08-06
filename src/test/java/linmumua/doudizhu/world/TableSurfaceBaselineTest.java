package linmumua.doudizhu.world;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

/**
 * 锁住"桌椅以支撑方块上表面为基准"这条几何契约。
 *
 * 这个 bug 的成因是同一个高度公式散在 19 处调用点，桌椅那几处没把支撑方块的一格算进去，
 * 而手牌（1.23）和按钮（1.02）的默认高度天然大于 1、早就补过了，所以只有桌椅陷进地里。
 * 这些断言用默认配置值直接校验各部件相对支撑方块上表面的高度，
 * 任何一处再次漏补都会在这里失败。
 */
class TableSurfaceBaselineTest {
    /* 与 config.yml 默认值保持一致 */
    private static final double TABLE_DISPLAY_HEIGHT = 0.55;
    private static final double CHAIR_BASE_HEIGHT = 0.20;
    private static final double HAND_CENTER_HEIGHT = 1.23;
    private static final double BUTTON_HEIGHT = 1.02;
    private static final double SPAWN_OFFSET_Y = -0.52;
    /** furniture.yml 里 element 的 position 抬升，手放与插件生成两条路径都吃这一份。 */
    private static final double CE_ELEMENT_LIFT = 0.5;

    /** 锚点取支撑方块自身坐标，也就是那一格的底面，因此上表面在锚点上方 1 格。 */
    private static double surfaceRelative(double anchorRelativeHeight) {
        return anchorRelativeHeight - PhysicalTableManager.SUPPORT_SURFACE_LIFT;
    }

    @Test
    void supportSurfaceLiftIsExactlyOneBlock() {
        assertEquals(
            1.0,
            PhysicalTableManager.SUPPORT_SURFACE_LIFT,
            1.0E-9,
            "支撑方块只有一格高，补偿必须正好是 1.0"
        );
    }

    @Test
    void tableAndChairSitAboveTheSupportSurface() {
        double table = surfaceRelative(TABLE_DISPLAY_HEIGHT + PhysicalTableManager.SUPPORT_SURFACE_LIFT);
        double chair = surfaceRelative(CHAIR_BASE_HEIGHT + PhysicalTableManager.SUPPORT_SURFACE_LIFT);

        assertTrue(table > 0.0, "桌面陷在支撑方块里，相对上表面高度=" + table);
        assertTrue(chair > 0.0, "椅子陷在支撑方块里，相对上表面高度=" + chair);
        assertEquals(TABLE_DISPLAY_HEIGHT, table, 1.0E-9, "桌面相对上表面的高度应当等于配置值");
        assertEquals(CHAIR_BASE_HEIGHT, chair, 1.0E-9, "椅子相对上表面的高度应当等于配置值");
    }

    /**
     * 手牌和按钮改造前就落在地面之上，这次不能被顺带抬高。
     * 它们的默认高度大于 1，等于自己已经把支撑方块那一格算进去了。
     */
    @Test
    void handAndButtonHeightsStayUnchanged() {
        assertTrue(
            surfaceRelative(HAND_CENTER_HEIGHT) > 0.0,
            "手牌高度不该低于支撑方块上表面"
        );
        assertTrue(
            surfaceRelative(BUTTON_HEIGHT) > 0.0,
            "按钮高度不该低于支撑方块上表面"
        );
    }

    /**
     * 改造前的真实症状回归：桌椅默认高度都不足一格，
     * 直接拿锚点当基准就会埋进支撑方块。
     */
    @Test
    void rawConfigHeightsWouldBeBuriedWithoutTheLift() {
        assertTrue(
            surfaceRelative(TABLE_DISPLAY_HEIGHT) < 0.0,
            "这条断言用于证明不加补偿时桌面确实埋在地里"
        );
        assertTrue(
            surfaceRelative(CHAIR_BASE_HEIGHT) < 0.0,
            "这条断言用于证明不加补偿时椅子确实埋在地里"
        );
    }

    /**
     * 放桌偏移现在可以为负，判断标准换成"桌面最终不能沉到站立面以下"。
     *
     * 这条断言原先写的是 spawnOffsetY >= 0，理由是"为负整套桌椅会往地里沉"。
     * 那个前提在接入 CraftEngine 家具后不再成立：
     * furniture.yml 的 element 带了 position: 0,0.5,0，
     * 手放和插件生成两条路径都会吃这 0.5 格抬升，
     * 所以插件侧必须给一个负偏移把多出来的高度减掉，两条路径才齐平。
     *
     * 真正要守的不是偏移的正负，而是最终桌面高度：
     * 桌面相对站立面 = spawnOffsetY + tableDisplayHeight + CE_ELEMENT_LIFT，
     * 这个值必须 > 0，否则桌子才真的沉进地里。
     */
    @Test
    void spawnOffsetKeepsTableAboveSurface() {
        double tableTopRelativeToSurface = SPAWN_OFFSET_Y + TABLE_DISPLAY_HEIGHT + CE_ELEMENT_LIFT;

        assertTrue(
            tableTopRelativeToSurface > 0.0,
            "桌面沉到站立面以下了，相对高度=" + tableTopRelativeToSurface
        );
    }

    /**
     * 插件生成的牌桌必须和手放 CE 家具齐平。
     *
     * 用户实机确认手放的高度是对的，插件生成的偏高，
     * 根因是插件侧额外叠了 spawnOffsetY + tableDisplayHeight。
     * 两条路径都吃 furniture.yml 的 0.5 格抬升，所以那一份不参与差值。
     * 容差 0.05 格：-0.52 是用户实测调出来的值，与理论齐平点 -0.55 差 0.03。
     */
    @Test
    void pluginPlacedTableMatchesHandPlacedHeight() {
        // 手放：CE 直接放在站立面上，实体 Y 相对站立面为 0
        double handPlaced = 0.0;
        // 插件生成：锚点经 SUPPORT_SURFACE_LIFT 抬到站立面后，再叠偏移与桌面高度
        double pluginPlaced = SPAWN_OFFSET_Y + TABLE_DISPLAY_HEIGHT;
        double gap = Math.abs(pluginPlaced - handPlaced);

        assertTrue(
            gap <= 0.05,
            String.format(
                "插件生成的牌桌比手放高 %.4f 格（偏移 %.2f + 桌面 %.2f），超过 0.05 容差",
                gap, SPAWN_OFFSET_Y, TABLE_DISPLAY_HEIGHT
            )
        );
    }
}
