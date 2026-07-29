package linmumua.doudizhu.world;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.Set;
import org.junit.jupiter.api.Test;

/**
 * 右键椅子该入座还是安静坐下。
 *
 * 加入按钮点不到（椅子离桌 3.1 格、按钮 2.01 格，21 次射线全部先命中椅子），
 * 所以入座改由椅子本体触发。两条分支的语义必须钉死：
 * 空位要真的入座，已占座位只能安静让 CraftEngine 坐下，绝不能把座位主人顶掉。
 */
class ChairSeatDecisionTest {
    @Test
    void emptySeatJoins() {
        assertEquals(
            PhysicalTableManager.ChairSeatDecision.JOIN,
            PhysicalTableManager.decideChairSeat(0, true, Set.of()),
            "空位右键椅子应当入座"
        );
    }

    @Test
    void occupiedSeatStaysSilentSoTheOwnerIsNotKicked() {
        assertEquals(
            PhysicalTableManager.ChairSeatDecision.OCCUPIED,
            PhysicalTableManager.decideChairSeat(0, true, Set.of(0)),
            "座位有人时只能安静坐下，不能改绑定"
        );
    }

    @Test
    void otherSeatsRemainJoinableWhileOneIsTaken() {
        Set<Integer> occupied = Set.of(0);

        assertEquals(
            PhysicalTableManager.ChairSeatDecision.OCCUPIED,
            PhysicalTableManager.decideChairSeat(0, true, occupied)
        );
        assertEquals(
            PhysicalTableManager.ChairSeatDecision.JOIN,
            PhysicalTableManager.decideChairSeat(1, true, occupied),
            "别的空位不该被邻座占用影响"
        );
        assertEquals(
            PhysicalTableManager.ChairSeatDecision.JOIN,
            PhysicalTableManager.decideChairSeat(2, true, occupied)
        );
    }

    @Test
    void unresolvedSeatDoesNothing() {
        // 椅子认出来了但算不出座位，只让 CraftEngine 坐下，别乱塞人。
        assertEquals(
            PhysicalTableManager.ChairSeatDecision.NO_SEAT,
            PhysicalTableManager.decideChairSeat(-1, true, Set.of())
        );
    }

    @Test
    void missingTableDoesNothing() {
        // 牌桌已经注销但椅子家具还在世界里，此时不能尝试入座。
        assertEquals(
            PhysicalTableManager.ChairSeatDecision.NO_SEAT,
            PhysicalTableManager.decideChairSeat(0, false, Set.of()),
            "牌桌不存在时不该入座"
        );
    }

    @Test
    void fullTableLeavesEverySeatOccupied() {
        Set<Integer> occupied = Set.of(0, 1, 2);

        for (int seat = 0; seat < 3; seat++) {
            assertEquals(
                PhysicalTableManager.ChairSeatDecision.OCCUPIED,
                PhysicalTableManager.decideChairSeat(seat, true, occupied),
                "满桌时每把椅子都只能安静坐下"
            );
        }
    }
}
