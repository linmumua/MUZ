package linmumua.doudizhu.game;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

/**
 * 明牌倍率阶梯的纯函数契约。
 *
 * <p>阶梯是「按实际已发批次」而不是按墙上时间：1 批 = 3、2 批 = 2、3 批及以后 = 1（下限 1）。
 * 0 表示窗口还没开（不可点），刻意与下限 1 区分开——否则「窗口未开」会被误当成「1 倍可点」。
 * 上限 3 同时是 {@code GameTable.coreScoreFor} 的钳制上界，两边必须一致，否则会出现
 * 「显示 3 倍但结算只按 2 倍算」的静默错账。
 */
class RevealMultiplierLadderTest {
    @Test
    void ladderIsThreeThenTwoThenFloorOfOne() {
        assertEquals(0, RevealMultiplierLadder.multiplierForCompletedBatches(0),
            "窗口未开时必须返回 0，与下限 1 区分");
        assertEquals(3, RevealMultiplierLadder.multiplierForCompletedBatches(1), "首批（每人 3 张）后是 3 倍");
        assertEquals(2, RevealMultiplierLadder.multiplierForCompletedBatches(2), "次批后是 2 倍");
        for (int batches = 3; batches <= 6; batches++) {
            assertEquals(1, RevealMultiplierLadder.multiplierForCompletedBatches(batches),
                "第 " + batches + " 批及以后必须固定在下限 1 倍");
        }
        assertEquals(1, RevealMultiplierLadder.multiplierForCompletedBatches(99), "超出批次数也不得降成 0 或负数");
    }

    @Test
    void ladderNeverProducesValuesOutsideOneToThree() {
        for (int batches : new int[] {1, 2, 3, 4, 5, 6, 7, 100, Integer.MAX_VALUE}) {
            int multiplier = RevealMultiplierLadder.multiplierForCompletedBatches(batches);
            assertTrue(multiplier >= RevealMultiplierLadder.MIN_MULTIPLIER
                    && multiplier <= RevealMultiplierLadder.MAX_MULTIPLIER,
                "已开窗时的倍率必须落在 1..3，实际 " + multiplier + "（已完成 " + batches + " 批）");
        }
        assertEquals(3, RevealMultiplierLadder.MAX_MULTIPLIER, "上限必须与结算钳制上界一致");
        assertEquals(1, RevealMultiplierLadder.MIN_MULTIPLIER);
        assertEquals(3, RevealMultiplierLadder.FIRST_BATCH_MULTIPLIER);
    }

    @Test
    void templatePlaceholderIsReplacedWithAPlainNumber() {
        assertEquals("明牌 ×3", RevealMultiplierLadder.withMultiplier("明牌 ×%multiplier%", 3));
        assertEquals("明牌 ×1", RevealMultiplierLadder.withMultiplier("明牌 ×%multiplier%", 0),
            "非法/未开窗值按下限 1 显示，不能把 ×0 甩给玩家");
        assertEquals("还剩 4 秒 · 当前 ×2", RevealMultiplierLadder.withMultiplier("还剩 4 秒 · 当前 ×%multiplier%", 2));
        assertEquals("无占位符", RevealMultiplierLadder.withMultiplier("无占位符", 3));
    }
}
