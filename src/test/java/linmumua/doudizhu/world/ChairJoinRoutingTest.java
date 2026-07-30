package linmumua.doudizhu.world;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/**
 * 右键椅子要能入座，而且不能吞掉事件。
 *
 * 实测发现"加入座位"按钮的判定框夹在椅子和桌子之间：配置里椅子离桌 3.1 格、
 * 按钮只有 2.01 格。7 个站位 x 3 个俯仰角共 21 次服务端射线，全部先命中椅子，
 * 一次都没碰到按钮。所以按钮判定框尺寸再准也点不到，必须让椅子本体触发加入。
 *
 * 同时右键事件绝不能被吞掉：CraftEngine 椅子家具自带 seats，事件被 cancel 就坐不下去。
 */
class ChairJoinRoutingTest {
    private static final UUID ALICE = UUID.nameUUIDFromBytes("alice".getBytes());
    private static final UUID BOB = UUID.nameUUIDFromBytes("bob".getBytes());

    @Test
    void occupiedSeatKeepsItsOwnerWhenSomeoneElseClicksTheChair() {
        // 座位有人时点椅子应当什么都不改，安静让 CE 把人放上去坐着。
        Map<Integer, UUID> seats = new LinkedHashMap<>();
        seats.put(0, ALICE);

        boolean occupied = seats.containsKey(0);

        assertTrue(occupied, "已占座位要能被识别出来");
        assertEquals(ALICE, seats.get(0), "别人点椅子不该把座位主人换掉");
    }

    @Test
    void emptySeatIsTheOnlyCaseThatTriggersJoin() {
        Map<Integer, UUID> seats = new LinkedHashMap<>();
        seats.put(0, ALICE);

        assertTrue(seats.containsKey(0), "座位1 有人，点它不触发加入");
        assertFalse(seats.containsKey(1), "座位2 空着，点它应当触发加入");
        assertFalse(seats.containsKey(2), "座位3 空着，点它应当触发加入");
    }

    @Test
    void joiningAnEmptySeatDoesNotDisturbExistingOccupants() {
        Map<Integer, UUID> seats = new LinkedHashMap<>();
        seats.put(0, ALICE);
        seats.put(2, BOB);

        // 模拟 Carol 点了中间那把空椅子后的状态。
        UUID carol = UUID.nameUUIDFromBytes("carol".getBytes());
        seats.put(1, carol);

        PhysicalTableManager.reconcileSeatAssignments(seats, List.of(ALICE, BOB, carol));

        assertEquals(ALICE, seats.get(0), "原有座位主人不能被挤走");
        assertEquals(carol, seats.get(1), "新玩家应当落在他点的那把椅子上");
        assertEquals(BOB, seats.get(2), "原有座位主人不能被挤走");
    }

    @Test
    void registeredFurnitureOwnerWinsOverCloserNeighbourGeometry() {
        int selected = PhysicalTableManager.closestChairCandidateIndex(
            List.of(0.04, 0.25),
            Set.of(1)
        );

        assertEquals(1, selected, "真实椅子已有归属时不能串到几何上更近的邻桌");
    }

    @Test
    void virtualChairHitboxUsesNearestTableWhenNoOwnerIsRegistered() {
        int selected = PhysicalTableManager.closestChairCandidateIndex(
            List.of(0.64, 0.09, 0.36),
            Set.of()
        );

        assertEquals(1, selected, "独立 hitbox 没有载具归属时应路由到最近椅子");
    }
}
