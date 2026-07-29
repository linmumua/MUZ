package linmumua.doudizhu.world;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import linmumua.doudizhu.world.PhysicalTableManager.ButtonAction;
import linmumua.doudizhu.world.PhysicalTableManager.Vector;
import org.junit.jupiter.api.Test;

/**
 * 空位"加入座位"的判定框必须贴在按钮图标上，不能去包整张椅子。
 *
 * 曾经把 JOIN 判定框放大到 0.85x1.05 并挪到椅子本体上，想让玩家点椅子入座。
 * 实际结果是椅子上多出一个插件判定框，抢掉了 CraftEngine 椅子自带 interaction
 * hitbox（那个 hitbox 才带 seats）的射线命中，玩家既坐不上椅子、又点不动按钮。
 * 坐下由 CraftEngine 家具负责，入座由按钮负责，两者不能挤在同一处。
 */
class ChairHitboxTest {
    private static final float BUTTON_WIDTH = 0.22f;
    private static final float BUTTON_HEIGHT = 0.34f;
    private static final float JOIN_WIDTH = 0.34f;
    private static final float JOIN_HEIGHT = 0.48f;

    @Test
    void joinHitboxStaysNearButtonScaleSoItDoesNotSwallowTheChairHitbox() {
        float width = PhysicalTableManager.resolveHitboxWidth(ButtonAction.JOIN, BUTTON_WIDTH, JOIN_WIDTH);
        float height = PhysicalTableManager.resolveHitboxHeight(ButtonAction.JOIN, BUTTON_HEIGHT, JOIN_HEIGHT);

        assertTrue(width >= BUTTON_WIDTH, "加入按钮判定框不该比普通按钮还小");
        assertTrue(height >= BUTTON_HEIGHT, "加入按钮判定框不该比普通按钮还矮");
        // 椅子模型缩放 1.35，约 1 格见方。判定框一旦接近这个量级就会盖住椅子，
        // CraftEngine 的坐下 hitbox 就再也命中不到。
        assertTrue(width < 0.6f, "加入按钮判定框不能大到盖住椅子");
        assertTrue(height < 0.8f, "加入按钮判定框不能高到盖住椅子");
    }

    @Test
    void oversizedChairConfigIsClampedBackToButtonScale() {
        // 老配置可能还留着 0.85/1.05。即使用户没改配置，也不能让判定框长回椅子尺寸。
        float width = PhysicalTableManager.resolveHitboxWidth(ButtonAction.JOIN, BUTTON_WIDTH, 0.85f);
        float height = PhysicalTableManager.resolveHitboxHeight(ButtonAction.JOIN, BUTTON_HEIGHT, 1.05f);

        assertEquals(BUTTON_WIDTH * 1.6f, width, 1.0E-6f);
        assertEquals(BUTTON_HEIGHT * 1.6f, height, 1.0E-6f);
    }

    @Test
    void otherButtonsKeepTheirOwnSizes() {
        assertEquals(
            BUTTON_WIDTH,
            PhysicalTableManager.resolveHitboxWidth(ButtonAction.PLAY_SELECTED, BUTTON_WIDTH, JOIN_WIDTH),
            1.0E-6f
        );
        assertEquals(
            BUTTON_HEIGHT,
            PhysicalTableManager.resolveHitboxHeight(ButtonAction.PLAY_SELECTED, BUTTON_HEIGHT, JOIN_HEIGHT),
            1.0E-6f
        );
    }

    @Test
    void doublingButtonsStayEnlarged() {
        assertTrue(
            PhysicalTableManager.resolveHitboxWidth(ButtonAction.DOUBLE_YES, BUTTON_WIDTH, JOIN_WIDTH) > BUTTON_WIDTH,
            "加倍按钮应保持放大"
        );
    }

    @Test
    void missingBindingFallsBackToButtonSize() {
        assertEquals(
            BUTTON_WIDTH,
            PhysicalTableManager.resolveHitboxWidth(null, BUTTON_WIDTH, JOIN_WIDTH),
            1.0E-6f
        );
        assertEquals(
            BUTTON_HEIGHT,
            PhysicalTableManager.resolveHitboxHeight(null, BUTTON_HEIGHT, JOIN_HEIGHT),
            1.0E-6f
        );
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
