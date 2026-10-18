package linmumua.doudizhu.world;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

/**
 * 「发牌期就能明牌」必须在**世界层**也成立：状态机允许、按钮不产出 = 玩家点不到。
 *
 * <p>1.10.63 起明牌窗口在首批（每人 3 张）发完就打开，而阶段切换仍由翻转结束负责，
 * 因此「窗口已开」的事实会出现在 {@code GamePhase.DEALING} 期。按钮判据若继续写
 * {@code phase == REVEALING}，玩家在整个发牌期都看不到按钮——这正是本轮要修的真实缺陷。
 *
 * <p>为什么用源级契约而不是行为测试：{@code actionStatesForSeat} 是私有方法且依赖
 * {@code GameTable} / {@code PlacedTable} / Bukkit 实体夹具，本仓库没有可用服务端夹具；
 * 这里直接把「判据是谁」「顺序对不对」钉成可执行的约束，避免以后被重构悄悄改回阶段判据。
 */
class RevealButtonWindowWiringContractTest {
    private static final Path MANAGER =
        Path.of("src/main/java/linmumua/doudizhu/world/PhysicalTableManager.java");
    private static final Path STATUS =
        Path.of("src/main/java/linmumua/doudizhu/game/TableStatusViews.java");

    private static String actionStatesBody(String source) {
        int start = source.indexOf("private List<ActionButtonState> actionStatesForSeat(");
        assertTrue(start >= 0, "找不到 actionStatesForSeat");
        int end = source.indexOf("private Component buildStatus(", start);
        assertTrue(end > start, "找不到 actionStatesForSeat 的结束边界");
        return source.substring(start, end);
    }

    /**
     * 明牌按钮必须以**窗口**为判据，而不是阶段。
     */
    @Test
    void revealButtonIsGatedByWindowNotByPhase() throws IOException {
        String body = actionStatesBody(Files.readString(MANAGER));

        assertTrue(body.contains("table.isRevealWindowOpen()"),
            "明牌按钮必须以 isRevealWindowOpen() 为判据，否则发牌期不产出按钮");
        assertTrue(body.contains("ButtonAction.REVEAL_HAND"),
            "按钮动作必须仍是 REVEAL_HAND（交互链路未改）");
        assertTrue(body.contains("table.openingRevealButtonLabel()"),
            "按钮文案必须取 GameTable 的当刻倍率文案（明牌 ×3/×2/×1）");
        assertTrue(body.contains("table.canRevealHand(owner)"),
            "是否可点仍由 canRevealHand 统一决定（已明牌/机器人/不在线都不给按钮）");
    }

    /**
     * 判据顺序：窗口分支必须排在「DEALING 返回空表」之前，否则发牌期永远走不到窗口分支。
     *
     * <p>这是本轮最容易在重构中被调换的一处：只看代码「有没有」isRevealWindowOpen 会漏掉
     * 分支被挪到 DEALING 早退之后的情况——那时按钮依然永远不出现。
     */
    @Test
    void windowBranchPrecedesDealingEmptyBranch() throws IOException {
        String body = actionStatesBody(Files.readString(MANAGER));

        int window = body.indexOf("table.isRevealWindowOpen()");
        int dealing = body.indexOf("if (table.getPhase() == GamePhase.DEALING)");

        assertTrue(window >= 0 && dealing > window,
            "窗口分支必须排在 DEALING 早退之前，否则发牌期按钮不可达");
    }

    /**
     * 窗口刚关、阶段还没切到 BIDDING 的短暂窗口：不发按钮，避免一个点了必然报错的入口。
     */
    @Test
    void revealPhaseWithoutOpenWindowOffersNoButton() throws IOException {
        String body = actionStatesBody(Files.readString(MANAGER));

        int window = body.indexOf("table.isRevealWindowOpen()");
        int revealingFallback = body.indexOf("if (table.getPhase() == GamePhase.REVEALING)", window);

        assertTrue(revealingFallback > window,
            "REVEALING 兜底必须留在窗口分支之后，承接「窗口已关但阶段未切」的间隙");
        String fallback = body.substring(revealingFallback);
        assertTrue(fallback.contains("return List.of();"),
            "窗口关闭后不得再产出明牌按钮");
    }

    /**
     * 状态文案不得再出现硬编码的整局明牌上限：倍率已是 3→2→1 阶梯。
     */
    @Test
    void statusViewsNoLongerHardcodeRevealCap() throws IOException {
        String source = Files.readString(STATUS);

        assertFalse(source.contains("整局最多"),
            "明牌倍率已改为 3→2→1 阶梯，文案不能再写「整局最多 ×N」");
        assertFalse(source.contains("自愿明牌（整局"),
            "旧的固定明牌文案必须整体移除");
        assertTrue(source.contains("GameTable.RevealWindowSnapshot"),
            "明牌状态必须消费 GameTable 的统一窗口快照，而不是各算一份");
        assertTrue(source.contains("revealWindow.open()"),
            "窗口开关必须读快照的 open 字段");
        assertTrue(source.contains("currentMultiplier()"),
            "当刻倍率必须读快照，不能在视图里另算批次/秒数");
        assertTrue(source.contains("remainingSeconds()"),
            "剩余秒必须读快照，不能在视图里另算");
        assertEquals(0, count(source, "Math.min("),
            "视图不该再对明牌倍率做上限钳制（上限归 RevealMultiplierLadder）");
    }

    private static int count(String source, String token) {
        int found = 0;
        int offset = 0;
        while ((offset = source.indexOf(token, offset)) >= 0) {
            found++;
            offset += token.length();
        }
        return found;
    }
}
