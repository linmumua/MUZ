package linmumua.doudizhu.game;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

/**
 * 一个玩家的结算失败不许带走同局其他人的结算。
 *
 * <p>【原来的行为有多糟】：settleEconomy 用普通 for 循环逐人调
 * settleDoudizhuCurrency，而后者在 Vault 拒绝交易时抛 IllegalStateException。
 * 循环不接异常，异常会一路穿到 GameTable.finishRound —— 那里也没有 try/catch。
 * 结果是首个失败的玩家让整局收尾全部中止：
 * 后面的人不结算、战绩不落库、结算聊天不发、HUD 不刷新。
 * 而失败之前已经完成的扣款是真扣了的，账面就此不一致。
 *
 * <p>【为什么用源码断言】：Support 接口直接返回具体的 DoudizhuPlugin，
 * 单测里没法注入一个会抛异常的假 plugin（要构造真 JavaPlugin 得起服务端）。
 * 项目里已有多处用源码锚点守结构性不变式的先例，这里沿用。
 */
class SettlementFailureIsolationTest {
    private static final Path COORDINATOR =
        Path.of("src/main/java/linmumua/doudizhu/game/RoundSettlementCoordinator.java");
    private static final Path PLUGIN =
        Path.of("src/main/java/linmumua/doudizhu/DoudizhuPlugin.java");

    /**
     * 结算循环必须逐人接住异常。
     * 失败条件：有人把 try/catch 去掉，退回裸循环。
     */
    @Test
    void settlementLoopCatchesPerPlayerFailure() throws IOException {
        String body = settleEconomyBody();

        assertTrue(
            body.contains("try {") && body.contains("catch (RuntimeException"),
            "结算循环没有逐人接异常，一个人失败会中止整局结算与收尾"
        );
        assertTrue(
            body.contains("settleDoudizhuCurrency"),
            "找不到 settleDoudizhuCurrency 调用，这条测试的锚点已失效"
        );
    }

    /**
     * 失败者必须留下一条可查的快照，而不是被跳过。
     *
     * <p>跳过（continue）看起来也能让循环继续，但账面上这人就凭空消失了，
     * 事后无法区分"没参与结算"和"结算失败了"。必须记成欠账快照。
     */
    @Test
    void failedPlayerGetsDebtSnapshotInsteadOfBeingSkipped() throws IOException {
        String body = settleEconomyBody();

        assertTrue(
            body.contains("failedSettlement"),
            "失败分支没有写入兜底快照，失败的玩家会从结算账面上消失"
        );

        int catchAt = body.indexOf("catch (RuntimeException");
        String catchBlock = body.substring(catchAt);
        assertFalse(
            catchBlock.contains("continue;"),
            "catch 里用 continue 跳过了失败者，等于把失败悄悄咽掉"
        );
    }

    /**
     * 经济失败必须记日志，且带上玩家与分差。
     *
     * <p>项目规范明确禁止吞异常不记录。这里更进一步要求日志带上下文：
     * 玩家来问"我的钱怎么没结算"时，没有 playerId 和分差就无从查证。
     */
    @Test
    void failureIsLoggedWithEnoughContextToInvestigate() throws IOException {
        String body = settleEconomyBody();
        int catchAt = body.indexOf("catch (RuntimeException");
        assertTrue(catchAt >= 0, "找不到 catch 块");
        String catchBlock = body.substring(catchAt);

        assertTrue(
            catchBlock.contains("getLogger()"),
            "结算失败没记日志，违反项目规范的「禁止吞异常不记录」"
        );
        assertTrue(
            catchBlock.contains("playerId"),
            "日志里没有玩家标识，事后无法定位是谁没结算成功"
        );
        assertTrue(
            catchBlock.contains("exception"),
            "日志没带上原始异常，丢掉了 Vault 的失败原因"
        );
    }

    /**
     * 兜底快照必须记满额欠账，不能记成零欠账的假成功。
     *
     * <p>失败条件：把 failedSettlement 的 debt 改成 0，
     * 那样失败会伪装成"这人本来就不该结算"。
     */
    @Test
    void fallbackSnapshotRecordsFullDebt() throws IOException {
        String source = Files.readString(PLUGIN);
        int start = source.indexOf("public SettlementResult failedSettlement(");
        assertTrue(start >= 0, "找不到 failedSettlement，兜底快照工厂被删了");
        String body = source.substring(start, source.indexOf("\n    }", start));

        assertTrue(
            body.contains("owed"),
            "兜底快照没有算应结金额，欠账记不出来"
        );
        assertTrue(
            body.contains("new SettlementResult(0.0, owed"),
            "兜底快照的 delta 必须是 0（钱确实没动）、debt 必须是全额应结金额"
        );
    }

    /**
     * 兜底路径自己不许再抛异常。
     *
     * <p>failedSettlement 是异常处理路径上最后一道，它再抛就会把整局重新带崩，
     * 那正是这次修复要消除的行为。查余额失败时必须静默降级。
     */
    @Test
    void fallbackPathDoesNotThrowAgain() throws IOException {
        String source = Files.readString(PLUGIN);
        int start = source.indexOf("public SettlementResult failedSettlement(");
        String body = source.substring(start, source.indexOf("\n    }", start));

        assertTrue(
            body.contains("catch (RuntimeException ignored)"),
            "兜底路径里查余额没有接异常，它一抛就会把整局结算重新带崩"
        );
    }

    /** 取 settleEconomy 方法体。 */
    private static String settleEconomyBody() throws IOException {
        String source = Files.readString(COORDINATOR);
        String signature =
            "private Map<UUID, DoudizhuPlugin.SettlementResult> settleEconomy(Map<UUID, Integer> scoreDeltas)";
        int start = source.indexOf(signature);
        assertTrue(start >= 0, "找不到 settleEconomy，这条测试的锚点已失效");
        int end = source.indexOf("\n    }", start);
        assertTrue(end > start, "找不到 settleEconomy 的结束锚点");
        return source.substring(start, end);
    }
}
