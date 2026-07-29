package linmumua.doudizhu.world;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/**
 * 座位绑定必须与牌桌实际玩家一一对应。
 *
 * 去重原先写成 removeIf(entry -> !list.add(value))，而 List.add 永远返回 true，
 * 于是条件恒为 false，等于压根没去重。后果是同一个玩家占住两个座位，
 * 第三个真人点"加入座位"时发现没有空位可用。
 */
class SeatAssignmentTest {
    private static final UUID ALICE = UUID.nameUUIDFromBytes("alice".getBytes());
    private static final UUID BOB = UUID.nameUUIDFromBytes("bob".getBytes());
    private static final UUID CAROL = UUID.nameUUIDFromBytes("carol".getBytes());

    @Test
    void duplicateBindingsForOnePlayerCollapseToASingleSeat() {
        Map<Integer, UUID> seats = new LinkedHashMap<>();
        seats.put(0, ALICE);
        seats.put(1, ALICE);

        PhysicalTableManager.reconcileSeatAssignments(seats, List.of(ALICE));

        assertEquals(1, seats.size(), "同一玩家不能同时占住两个座位");
        assertEquals(ALICE, seats.get(0), "应当保留先出现的那个座位");
    }

    @Test
    void freedSeatBecomesAvailableToAnotherPlayer() {
        // 这是重复绑定的真实后果：不去重的话 Carol 永远坐不进来。
        Map<Integer, UUID> seats = new LinkedHashMap<>();
        seats.put(0, ALICE);
        seats.put(1, ALICE);
        seats.put(2, BOB);

        PhysicalTableManager.reconcileSeatAssignments(seats, List.of(ALICE, BOB, CAROL));

        assertEquals(3, seats.size(), "三个玩家应当各占一个座位");
        assertTrue(seats.containsValue(CAROL), "腾出来的座位要能让新玩家坐进去");
        assertEquals(3, seats.values().stream().distinct().count(), "不能有人重复占座");
    }

    @Test
    void bindingsForPlayersWhoLeftAreDropped() {
        Map<Integer, UUID> seats = new LinkedHashMap<>();
        seats.put(0, ALICE);
        seats.put(1, BOB);

        PhysicalTableManager.reconcileSeatAssignments(seats, List.of(ALICE));

        assertEquals(1, seats.size(), "已离桌玩家的绑定要清掉");
        assertFalse(seats.containsValue(BOB), "离桌的人不该继续占着座位");
    }

    @Test
    void seatedPlayersWithoutBindingGetTheLowestFreeSeat() {
        Map<Integer, UUID> seats = new LinkedHashMap<>();
        seats.put(1, ALICE);

        PhysicalTableManager.reconcileSeatAssignments(seats, List.of(ALICE, BOB));

        assertEquals(ALICE, seats.get(1), "已有绑定的玩家不该被挪走");
        assertEquals(BOB, seats.get(0), "新玩家应当补到最小的空位");
    }

    @Test
    void existingBindingsSurviveWhenNothingChanged() {
        Map<Integer, UUID> seats = new LinkedHashMap<>();
        seats.put(0, ALICE);
        seats.put(1, BOB);
        seats.put(2, CAROL);

        PhysicalTableManager.reconcileSeatAssignments(seats, List.of(ALICE, BOB, CAROL));

        assertEquals(ALICE, seats.get(0));
        assertEquals(BOB, seats.get(1));
        assertEquals(CAROL, seats.get(2));
    }

    @Test
    void emptyTableClearsEverything() {
        Map<Integer, UUID> seats = new LinkedHashMap<>();
        seats.put(0, ALICE);
        seats.put(2, BOB);

        PhysicalTableManager.reconcileSeatAssignments(seats, List.of());

        assertTrue(seats.isEmpty(), "桌上没人时不该留下任何绑定，否则空位判定框收不回来");
    }
}
