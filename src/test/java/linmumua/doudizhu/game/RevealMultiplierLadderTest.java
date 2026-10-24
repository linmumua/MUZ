package linmumua.doudizhu.game;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

/**
 * 明牌倍率阶梯的纯函数契约。
 *
 * <p>【1.10.73：基准从「首批」移到「第 3 批」】明牌窗口不再是「首批即开、贯穿整条时间线」，
 * 而是「第 3 批发完只开 60 tick」。若阶梯仍从第 1 批起算，窗口期内永远只能拿到下限 ×1，
 * 「早明牌倍率高」这条设计就名存实亡。因此阶梯锚点必须与窗口锚点同批：
 * 3 批 → ×3、4 批 → ×2、5 批及以后 → ×1；0..2 批返回 0 表示「还没有明牌资格」。
 *
 * <p>0 刻意与下限 1 区分：否则「窗口未到」会被误当成「1 倍可点」。上限 3 同时是
 * {@code GameTable.coreScoreFor} 的钳制上界，两边必须一致，否则会出现
 * 「显示 3 倍但结算只按 2 倍算」的静默错账。
 */
class RevealMultiplierLadderTest {
    @Test
    void ladderAnchorsOnTheThirdBatchAndNeverGoesBelowOne() {
        // 0..2 批：窗口还没到，必须是「不可明牌」哨兵 0，而不是 1/2/3。
        for (int batches = 0; batches <= 2; batches++) {
            assertEquals(0, RevealMultiplierLadder.multiplierForCompletedBatches(batches),
                batches + " 批时窗口尚未开启，必须返回 0（不可明牌）");
        }
        assertEquals(3, RevealMultiplierLadder.multiplierForCompletedBatches(3),
            "窗口开启批次（第 3 批，每人 9 张）是 3 倍");
        assertEquals(2, RevealMultiplierLadder.multiplierForCompletedBatches(4),
            "第 4 批是 2 倍");
        for (int batches = 5; batches <= 6; batches++) {
            assertEquals(1, RevealMultiplierLadder.multiplierForCompletedBatches(batches),
                "第 " + batches + " 批及以后必须固定在下限 1 倍");
        }
        assertEquals(1, RevealMultiplierLadder.multiplierForCompletedBatches(99),
            "超出批次数也不得降成 0 或负数");
    }

    @Test
    void ladderNeverProducesValuesOutsideOneToThreeOnceAvailable() {
        for (int batches : new int[] {3, 4, 5, 6, 7, 100, Integer.MAX_VALUE}) {
            int multiplier = RevealMultiplierLadder.multiplierForCompletedBatches(batches);
            assertTrue(multiplier >= RevealMultiplierLadder.MIN_MULTIPLIER
                    && multiplier <= RevealMultiplierLadder.MAX_MULTIPLIER,
                "已开窗时的倍率必须落在 1..3，实际 " + multiplier + "（已完成 " + batches + " 批）");
        }
        assertEquals(3, RevealMultiplierLadder.MAX_MULTIPLIER, "上限必须与结算钳制上界一致");
        assertEquals(1, RevealMultiplierLadder.MIN_MULTIPLIER);
        assertEquals(3, RevealMultiplierLadder.FIRST_BATCH_MULTIPLIER);
        assertEquals(3, RevealMultiplierLadder.FIRST_REVEALABLE_BATCH,
            "窗口/阶梯的锚点批次必须公开可读，供协调器与测试共用同一常量");
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
