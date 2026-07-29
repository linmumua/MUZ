package linmumua.doudizhu.world;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import linmumua.doudizhu.world.PhysicalTableManager.ButtonAction;
import linmumua.doudizhu.world.PhysicalTableManager.Vector;
import org.junit.jupiter.api.Test;

/**
 * 按钮判定框按文字缩放推算，且不能大到盖住椅子。
 *
 * 图标已经删掉，玩家点的就是文字，所以判定框改为贴合文字自动推算。
 * 但那条老约束依然有效：曾经把 JOIN 判定框放大到 0.85x1.05 并挪到椅子本体上，
 * 结果在椅子上多出一个插件判定框，抢掉了 CraftEngine 椅子自带 interaction
 * hitbox（那个才带 seats）的射线命中，玩家既坐不上椅子又点不动按钮。
 * 所以无论文字配多大，判定框都必须被夹在椅子量级以下。
 */
class ChairHitboxTest {
    /** 加入座位的文字缩放，对应 button-layout.join-label-scale 默认值。 */
    private static final float JOIN_LABEL_SCALE = 0.46f;
    /** 普通动作按钮的文字缩放，对应 button-layout.action-label-scale 默认值。 */
    private static final float ACTION_LABEL_SCALE = 0.20f;

    @Test
    void hitboxStaysBelowChairScaleSoItDoesNotSwallowTheSitHitbox() {
        float width = PhysicalTableManager.resolveHitboxWidth(ButtonAction.JOIN, JOIN_LABEL_SCALE);
        float height = PhysicalTableManager.resolveHitboxHeight(ButtonAction.JOIN, JOIN_LABEL_SCALE);

        // 椅子模型缩放 1.35，约 1 格见方。判定框一旦接近这个量级就会盖住椅子，
        // CraftEngine 的坐下 hitbox 就再也命中不到。
        assertTrue(width < 0.6f, "判定框不能大到盖住椅子，实测值=" + width);
        assertTrue(height < 0.8f, "判定框不能高到盖住椅子，实测值=" + height);
    }

    @Test
    void absurdLabelScaleIsStillClampedBelowChairScale() {
        // 就算有人把文字缩放调到 5.0，判定框也不能长回椅子尺寸。
        float width = PhysicalTableManager.resolveHitboxWidth(ButtonAction.JOIN, 5.0f);
        float height = PhysicalTableManager.resolveHitboxHeight(ButtonAction.JOIN, 5.0f);

        assertTrue(width < 0.6f, "超大文字缩放也必须被夹住，实测值=" + width);
        assertTrue(height < 0.8f, "超大文字缩放也必须被夹住，实测值=" + height);
    }

    @Test
    void tinyLabelScaleStillLeavesSomethingClickable() {
        // 文字缩到极小时判定框也要留住下限，否则按钮彻底点不到。
        float width = PhysicalTableManager.resolveHitboxWidth(ButtonAction.READY, 0.001f);
        float height = PhysicalTableManager.resolveHitboxHeight(ButtonAction.READY, 0.001f);

        assertTrue(width >= 0.12f, "判定框不能小到点不着，实测值=" + width);
        assertTrue(height >= 0.16f, "判定框不能矮到点不着，实测值=" + height);
    }

    @Test
    void biggerLabelGetsBiggerHitbox() {
        // 判定框贴合文字：文字大的按钮点击范围也该更大。
        float joinWidth = PhysicalTableManager.resolveHitboxWidth(ButtonAction.JOIN, JOIN_LABEL_SCALE);
        float actionWidth = PhysicalTableManager.resolveHitboxWidth(ButtonAction.READY, ACTION_LABEL_SCALE);

        assertTrue(
            joinWidth > actionWidth,
            "加入座位文字更大，判定框也该更大：join=" + joinWidth + " action=" + actionWidth
        );
    }

    @Test
    void doublingButtonsStayEnlarged() {
        // 加倍只有两个按钮且间距宽，判定框放大一点更好点。
        float plain = PhysicalTableManager.resolveHitboxWidth(ButtonAction.READY, ACTION_LABEL_SCALE);
        float doubling = PhysicalTableManager.resolveHitboxWidth(ButtonAction.DOUBLE_YES, ACTION_LABEL_SCALE);

        assertTrue(doubling > plain, "加倍按钮应保持放大");
    }

    @Test
    void missingBindingStillProducesUsableHitbox() {
        float width = PhysicalTableManager.resolveHitboxWidth(null, ACTION_LABEL_SCALE);
        float height = PhysicalTableManager.resolveHitboxHeight(null, ACTION_LABEL_SCALE);

        assertTrue(width >= 0.12f, "无绑定也要给个能点的判定框");
        assertTrue(height >= 0.16f, "无绑定也要给个能点的判定框");
    }

    @Test
    void lateralOffsetFollowsSeatAxis() {
        Vector lateralAxis = new Vector(0.0, 0.0, -1.0);

        Vector adjustment = PhysicalTableManager.chairHitboxAdjustment(lateralAxis, 0.25, 0.02);

        assertEquals(0.0, adjustment.x(), 1.0E-9);
        assertEquals(-0.25, adjustment.z(), 1.0E-9);
        assertEquals(0.02, adjustment.y(), 1.0E-9);
    }
}
