package linmumua.doudizhu.world;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * 邻桌串座防护：hitbox 只能按离它最近的那把椅子同步显隐。
 *
 * CE 的 interaction hitbox 不挂在家具载具链上，ownsChairEntity 对它恒为 false，
 * 没法靠归属判断，只能比距离。实测两桌锚点相距 6 格时椅子判定框会重叠，
 * 某把椅子附近会冒出邻桌的 hitbox（解析座位=-）。
 * 这里锁住比较方向：把 < 写反会让防护彻底失效，而且不影响任何其他测试。
 */
class ChairHitboxOwnershipTest {
    @Test
    void ownChairWinsWhenItIsNearest() {
        assertTrue(
            PhysicalTableManager.ownChairIsClosest(0.25, List.of(4.0, 9.0)),
            "本桌椅子最近时应当登记"
        );
    }

    @Test
    void neighbourChairWinsWhenItIsNearer() {
        assertFalse(
            PhysicalTableManager.ownChairIsClosest(9.0, List.of(0.25)),
            "邻桌椅子更近时不能处理，否则会错误隐藏邻桌判定框"
        );
    }

    @Test
    void onlyOneNearerNeighbourIsEnoughToReject() {
        // 多数邻桌都更远，但只要有一个更近就必须拒绝。
        assertFalse(
            PhysicalTableManager.ownChairIsClosest(4.0, List.of(16.0, 25.0, 1.0, 36.0)),
            "任意一个更近的邻桌椅子都应当否决登记"
        );
    }

    @Test
    void noNeighbourMeansOwnChairWins() {
        assertTrue(
            PhysicalTableManager.ownChairIsClosest(100.0, List.of()),
            "没有邻桌时无论多远都归本桌"
        );
    }

    @Test
    void tieGoesToOwnTable() {
        // 两桌椅子完全重合属于摆放错误，此时沿用本桌扫描结果。
        assertTrue(
            PhysicalTableManager.ownChairIsClosest(4.0, List.of(4.0)),
            "同距时应当判归本桌"
        );
    }
}
