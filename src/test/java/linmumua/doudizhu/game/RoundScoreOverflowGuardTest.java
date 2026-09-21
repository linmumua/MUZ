package linmumua.doudizhu.game;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

/**
 * 单局基础分必须夹上限，避免倍数链整数溢出后把输赢方向算反。
 *
 * <p>【为什么这件事必须守】：roundScore = 叫分 × 炸弹倍数 × 春天2 × 加倍系数，
 * 全程 int 相乘。溢出后结果变成负数，而负数会一路流到 scoreDeltas，
 * 最终表现为【输家收钱、赢家付钱】——这是最坏的一类经济 bug：
 * 不报错、不崩服，只是账算反了，等玩家发现时钱已经乱了。
 *
 * <p>当前配置下峰值约 3.2 万，离溢出很远。但这条链上每加一个乘子
 * （明牌、欢乐豆加倍之类），溢出门槛就掉一个数量级。上限是为那一天准备的。
 */
class RoundScoreOverflowGuardTest {
    private static final Path COORDINATOR =
        Path.of("src/main/java/linmumua/doudizhu/game/RoundSettlementCoordinator.java");

    /**
     * 上限值本身必须留足累加余量。
     *
     * <p>一局最多三家结算，单家分差还要再乘座位系数（最大 2）。
     * 上限 × 2 × 3 必须仍在 int 范围内，否则夹了基础分也会在累加阶段二次溢出。
     */
    @Test
    void limitLeavesHeadroomForPerSeatAccumulation() {
        long worstCaseTotal = (long) RoundSettlementCoordinator.MAX_ROUND_SCORE * 2L * 3L;

        assertTrue(
            worstCaseTotal <= Integer.MAX_VALUE,
            "上限 " + RoundSettlementCoordinator.MAX_ROUND_SCORE
                + " 乘座位系数再三家累加会溢出 int（" + worstCaseTotal + "），"
                + "夹了基础分也白夹"
        );
    }

    /**
     * 上限必须远高于正常对局量级，否则会误伤真实玩法。
     *
     * <p>正常峰值约 3.2 万，这里要求上限至少是它的 1000 倍，
     * 保证夹子只在配置被填成极端值时才生效。
     */
    @Test
    void limitIsFarAboveRealisticGameplay() {
        int observedPeak = 32_000;

        assertTrue(
            RoundSettlementCoordinator.MAX_ROUND_SCORE >= observedPeak * 1000L,
            "上限太低，会夹到正常对局的分数"
        );
    }

    /**
     * 夹取必须发生在 scoreDeltas 计算之前。
     *
     * <p>夹在乘法之后、分配之前是唯一正确的位置：
     * 分配之后再夹，负数已经进了每个人的账；每个乘法点各夹一次则既啰嗦又容易漏。
     * 失败条件：有人把 clamp 挪到循环里面或删掉。
     */
    @Test
    void clampHappensBeforeScoreDistribution() throws IOException {
        String source = Files.readString(COORDINATOR);

        int clamp = source.indexOf("Math.clamp(roundScore");
        int distribution = source.indexOf("scoreDeltas.put(");

        assertTrue(clamp >= 0, "roundScore 的上限夹取被删了，倍数链会溢出成负数");
        assertTrue(distribution >= 0, "找不到 scoreDeltas.put，这条测试的锚点已失效");
        assertTrue(
            clamp < distribution,
            "夹取排在分数分配之后，负数已经进了每个人的账再夹就晚了"
        );
    }

    /**
     * 夹取下界必须是 0，不能是负数。
     *
     * <p>下界给 0 的意义：即便上游算出负的 roundScore（已经溢出过一次），
     * 也退化成"这局不结算"，而不是把方向反过来结算一遍。
     */
    @Test
    void clampLowerBoundIsZero() throws IOException {
        String source = Files.readString(COORDINATOR);

        assertTrue(
            source.contains("Math.clamp(roundScore, 0, MAX_ROUND_SCORE)"),
            "夹取的下界必须是 0：负的 roundScore 要退化成不结算，而不是反向结算"
        );
    }

    /** 夹取语义自检：负数归 0、超限归上限、正常值不变。 */
    @Test
    void clampSemanticsMatchIntent() {
        int limit = RoundSettlementCoordinator.MAX_ROUND_SCORE;

        assertEquals(0, Math.clamp(-1, 0, limit), "负数必须归 0，否则输赢方向会反");
        assertEquals(limit, Math.clamp(Integer.MAX_VALUE, 0, limit), "超限值必须夹到上限");
        assertEquals(32_000, Math.clamp(32_000, 0, limit), "正常量级不能被改动");
    }

    /** 核心倍率链必须拒绝 int 溢出，而不是静默回绕成负分。 */
    @Test
    void coreScoreMultiplicationRejectsIntegerOverflow() {
        assertEquals(48, GameTable.coreScoreFor(3, 2, 4, 2));
        assertThrows(
            ArithmeticException.class,
            () -> GameTable.coreScoreFor(Integer.MAX_VALUE, 2, Integer.MAX_VALUE, 2),
            "核心分乘法溢出必须显式失败"
        );
    }
}
