package linmumua.doudizhu.world;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/**
 * 椅子该不该跟着 JOIN 图标一起 hover。
 *
 * 椅子离桌 3.1 格、按钮 2.01 格，21 次射线探针全部先命中椅子，
 * 按钮被完全遮挡。所以 hover 必须挂在椅子上，否则玩家永远看不到抬升反馈。
 * 但只有空位才有 JOIN 图标，座位有人时椅子不能再触发 hover。
 */
class ChairHoverTargetTest {
    private static final UUID SEAT0_ICON = UUID.randomUUID();
    private static final UUID SEAT2_ICON = UUID.randomUUID();

    @Test
    void emptySeatChairLiftsItsJoinIcon() {
        assertEquals(
            SEAT0_ICON,
            PhysicalTableManager.resolveChairHoverDisplay(0, Map.of(0, SEAT0_ICON)),
            "空位椅子应当抬起自己的加入图标"
        );
    }

    @Test
    void eachChairLiftsOnlyItsOwnSeatIcon() {
        Map<Integer, UUID> joinIcons = Map.of(0, SEAT0_ICON, 2, SEAT2_ICON);

        assertEquals(SEAT0_ICON, PhysicalTableManager.resolveChairHoverDisplay(0, joinIcons));
        assertEquals(
            SEAT2_ICON,
            PhysicalTableManager.resolveChairHoverDisplay(2, joinIcons),
            "椅子不能抬错座位的图标"
        );
    }

    @Test
    void occupiedSeatChairDoesNotHover() {
        // 座位1有人，它的 JOIN 图标已经删掉，映射里自然没有这个座位号。
        assertNull(
            PhysicalTableManager.resolveChairHoverDisplay(1, Map.of(0, SEAT0_ICON, 2, SEAT2_ICON)),
            "座位有人时椅子不该再触发 hover"
        );
    }

    @Test
    void unresolvedSeatDoesNotHover() {
        assertNull(
            PhysicalTableManager.resolveChairHoverDisplay(-1, Map.of(0, SEAT0_ICON)),
            "算不出座位的椅子不该乱抬图标"
        );
    }

    @Test
    void fullTableHasNoHoverTargets() {
        // 满桌没有任何 JOIN 图标，三把椅子都不该抬东西。
        for (int seat = 0; seat < 3; seat++) {
            assertNull(
                PhysicalTableManager.resolveChairHoverDisplay(seat, Map.of()),
                "满桌时椅子不该触发 hover"
            );
        }
    }

    @Test
    void occupiedChairHitboxIsHiddenOnlyFromItsOwner() {
        UUID owner = UUID.randomUUID();
        assertTrue(
            PhysicalTableManager.shouldHideOccupiedChairHitbox(owner, owner),
            "入座玩家不该再被自己的椅子判定框挡住按钮"
        );
        assertFalse(
            PhysicalTableManager.shouldHideOccupiedChairHitbox(owner, UUID.randomUUID()),
            "其他玩家仍需看见椅子判定框，避免空位交互状态串掉"
        );
    }

    @Test
    void emptyChairHitboxRemainsVisible() {
        assertFalse(
            PhysicalTableManager.shouldHideOccupiedChairHitbox(null, UUID.randomUUID()),
            "空椅判定框必须保留，玩家才能右键坐下"
        );
    }
}
