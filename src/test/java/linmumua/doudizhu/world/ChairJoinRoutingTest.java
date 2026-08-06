package linmumua.doudizhu.world;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/**
 * 椅子是纯装饰：右键椅子只能坐下，不会加入牌桌。
 *
 * 语义变更说明：以前因为"加入座位"按钮的判定框夹在椅子和桌子之间点不到，
 * 入座曾经由椅子本体触发。现在改回按钮唯一入口（ButtonAction.JOIN），
 * 椅子不再动 seatAssignments，所以本文件不再断言"点空椅子会加入"。
 *
 * 仍然要保住的两件事：
 * 1. 右键事件绝不能被吞掉，CraftEngine 椅子家具自带 seats，事件被 cancel 就坐不下去。
 * 2. 椅子实体到牌桌/座位的路由必须正确，诊断和占位 hitbox 都靠它。
 */
class ChairJoinRoutingTest {
    private static final UUID ALICE = UUID.nameUUIDFromBytes("alice".getBytes());
    private static final UUID BOB = UUID.nameUUIDFromBytes("bob".getBytes());

    @Test
    void clickingAnOccupiedChairKeepsItsOwner() {
        // 座位有人时点椅子，decideChairSeat 必须返回 OCCUPIED——椅子只坐下，不改绑定。
        // 失败条件：如果 decideChairSeat 在座位有人时不再返回 OCCUPIED（比如误改成 EMPTY 或 NO_SEAT），
        // 本测试会失败，说明椅子路由对已占座位的判断逻辑被破坏。
        PhysicalTableManager.ChairSeatDecision decision =
            PhysicalTableManager.decideChairSeat(0, true, Set.of(0));

        assertEquals(PhysicalTableManager.ChairSeatDecision.OCCUPIED, decision,
            "座位已有人时，decideChairSeat 必须返回 OCCUPIED：椅子不改绑定，只让 CE 坐下");
    }

    @Test
    void clickingAnEmptyChairLeavesTheSeatEmpty() {
        // 椅子改成纯装饰后，点空椅子不写入 seatAssignments——decideChairSeat 返回 EMPTY。
        // 失败条件：如果 decideChairSeat 在座位为空时不再返回 EMPTY（比如误改成加入逻辑返回其他值），
        // 本测试会失败，说明椅子不再是纯装饰入口。
        PhysicalTableManager.ChairSeatDecision decision =
            PhysicalTableManager.decideChairSeat(1, true, Set.of(0));

        assertEquals(PhysicalTableManager.ChairSeatDecision.EMPTY, decision,
            "座位空着时，decideChairSeat 必须返回 EMPTY：椅子不触发加入牌桌，只让 CE 坐下");
        // 额外验证：没有牌桌时也不能误判
        PhysicalTableManager.ChairSeatDecision noTable =
            PhysicalTableManager.decideChairSeat(1, false, Set.of());
        assertEquals(PhysicalTableManager.ChairSeatDecision.NO_SEAT, noTable,
            "牌桌不存在时必须返回 NO_SEAT，椅子什么都不做");
    }

    @Test
    void joiningThroughTheTableButtonDoesNotDisturbExistingOccupants() {
        // 加入只能来自桌面的加入按钮，走的还是同一套座位对齐逻辑。
        Map<Integer, UUID> seats = new LinkedHashMap<>();
        seats.put(0, ALICE);
        seats.put(2, BOB);

        UUID carol = UUID.nameUUIDFromBytes("carol".getBytes());
        seats.put(1, carol);

        PhysicalTableManager.reconcileSeatAssignments(seats, List.of(ALICE, BOB, carol));

        assertEquals(ALICE, seats.get(0), "原有座位主人不能被挤走");
        assertEquals(carol, seats.get(1), "新玩家应当落在分配到的座位上");
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
