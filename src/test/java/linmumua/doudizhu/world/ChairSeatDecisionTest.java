package linmumua.doudizhu.world;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;

import java.util.Set;
import org.junit.jupiter.api.Test;

/**
 * 椅子改成纯装饰后，右键椅子只识别座位状态，任何分支都不加入牌桌。
 *
 * 语义变更说明：以前空位会返回 JOIN 并真的把玩家塞进牌桌，
 * 现在空位只返回 EMPTY，加入牌桌的唯一入口是桌面上的加入按钮（ButtonAction.JOIN）。
 * 这里仍然要钉死座位映射：空位、已占、算不出座位、牌桌注销四种情况必须能区分开，
 * 因为 /doudizhu 的诊断输出和椅子归属排查都靠它。
 */
class ChairSeatDecisionTest {
    @Test
    void emptySeatIsReportedAsEmptyAndNeverJoins() {
        assertEquals(
            PhysicalTableManager.ChairSeatDecision.EMPTY,
            PhysicalTableManager.decideChairSeat(0, true, Set.of()),
            "空位右键椅子只识别为空位，不加入牌桌"
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
    void emptyAndOccupiedSeatsStayDistinguishableOnTheSameTable() {
        Set<Integer> occupied = Set.of(0);

        assertEquals(
            PhysicalTableManager.ChairSeatDecision.OCCUPIED,
            PhysicalTableManager.decideChairSeat(0, true, occupied)
        );
        assertEquals(
            PhysicalTableManager.ChairSeatDecision.EMPTY,
            PhysicalTableManager.decideChairSeat(1, true, occupied),
            "别的空位不该被邻座占用影响"
        );
        assertEquals(
            PhysicalTableManager.ChairSeatDecision.EMPTY,
            PhysicalTableManager.decideChairSeat(2, true, occupied)
        );
    }

    @Test
    void unresolvedSeatDoesNothing() {
        // 椅子认出来了但算不出座位，只让 CraftEngine 坐下。
        assertEquals(
            PhysicalTableManager.ChairSeatDecision.NO_SEAT,
            PhysicalTableManager.decideChairSeat(-1, true, Set.of())
        );
    }

    @Test
    void missingTableIsReportedAsNoSeat() {
        // 牌桌已经注销但椅子家具还在世界里，此时只剩坐下这一件事。
        assertEquals(
            PhysicalTableManager.ChairSeatDecision.NO_SEAT,
            PhysicalTableManager.decideChairSeat(0, false, Set.of()),
            "牌桌不存在时只能识别为没有座位"
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

    @Test
    void noBranchEverAsksForAJoin() {
        // 兜底：三种输入都不能产生"加入牌桌"语义的分支，椅子已经是纯装饰。
        for (PhysicalTableManager.ChairSeatDecision decision : new PhysicalTableManager.ChairSeatDecision[] {
            PhysicalTableManager.decideChairSeat(0, true, Set.of()),
            PhysicalTableManager.decideChairSeat(0, true, Set.of(0)),
            PhysicalTableManager.decideChairSeat(-1, false, Set.of())
        }) {
            assertNotEquals(
                "JOIN",
                decision.name(),
                "椅子分支里不该再出现加入牌桌的语义"
            );
        }
    }

    /**
     * 椅子交互必须永远放行，不能吞掉事件。
     *
     * 这条断言补的是一个真实缺口：把 handleChairSeatInteraction 的返回值
     * 从 false 改成 true 时，原有全部椅子测试依然通过，
     * 但实际后果是 CraftEngine 收不到右键、玩家连椅子都坐不下去。
     * 椅子作为纯装饰，唯一还需要保住的能力就是「能坐」，
     * 所以这个返回值必须被锁死。
     */
    @Test
    void chairInteractionMustNeverConsumeTheEvent() {
        assertFalse(
            PhysicalTableManager.CHAIR_INTERACTION_NEVER_CONSUMED,
            "椅子交互一旦吞掉事件，CraftEngine 的 seats 就收不到右键，玩家会坐不下去"
        );
    }
}
