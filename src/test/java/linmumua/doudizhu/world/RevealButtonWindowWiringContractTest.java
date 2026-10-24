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
 * <p>1.10.73 起明牌窗口在第 3 批（每人 9 张）发完后只开 60 tick，而阶段切换仍由翻转结束负责，
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

    /**
     * 【1.10.73 抢地主加赛】BIDDING 阶段也必须能产出明牌按钮。
     *
     * <p>旧实现这里先按「当前叫分者」分流：非当前叫分者直接返回空表、当前叫分者只给四个叫分键。
     * 于是明牌资格在 {@code BIDDING && bidRound == 2} 期间虽然成立，玩家却**永远看不到入口**——
     * 状态机允许、UI 点不到，是本轮要修的真实缺陷。
     *
     * <p>这里锁三件事：判据仍由 {@code GameTable} 统一给出（window + canRevealHand）、
     * 明牌动作仍是 {@code REVEAL_HAND}、且当前叫分者的四个叫分键**不被明牌键挤掉**。
     */
    @Test
    void biddingPhaseOffersRevealButtonWithoutDroppingBidButtons() throws IOException {
        String body = actionStatesBody(Files.readString(MANAGER));

        int bidding = body.indexOf("if (table.getPhase() == GamePhase.BIDDING)");
        assertTrue(bidding >= 0, "BIDDING 分支必须存在");
        int biddingEnd = body.indexOf("// 明牌按钮的判据是「窗口」而不是「阶段」", bidding);
        assertTrue(biddingEnd > bidding, "找不到 BIDDING 分支的结束边界");
        String branch = body.substring(bidding, biddingEnd);

        assertTrue(branch.contains("table.isRevealWindowOpen()"),
            "BIDDING 分支必须用统一的窗口判据（含抢地主加赛延长），不能只看阶段");
        assertTrue(branch.contains("table.canRevealHand(owner)"),
            "是否可以点仍由 canRevealHand 统一决定（已明牌/机器人/离线都不给按钮）");
        // 【1.10.73】五按钮布局已抽成「生产/测试共用」的静态工厂（ButtonHitboxSpacingTest 直接拿它的
        // 真实 ActionButtonState 做碰撞断言），因此这里断言 BIDDING 分支**委托给工厂**，
        // 再在工厂体内核对四个叫分键与 REVEAL_HAND 一个不少。
        assertTrue(branch.contains("biddingWithRevealStates("),
            "当前叫分者必须委托 biddingWithRevealStates（五按钮的唯一来源）");
        assertTrue(branch.contains("biddingRevealOnlyStates("),
            "非当前叫分者必须委托 biddingRevealOnlyStates（只有自己的明牌按钮）");
        assertTrue(branch.contains("table.openingRevealButtonLabel()"),
            "按钮文案必须取 GameTable 的当刻倍率文案（明牌 ×3/×2/×1）");

        String source = Files.readString(MANAGER);
        int factory = source.indexOf("static List<ActionButtonState> biddingWithRevealStates(");
        assertTrue(factory >= 0, "找不到 biddingWithRevealStates 工厂");
        String factoryBody = source.substring(factory,
            source.indexOf("static List<ActionButtonState> biddingRevealOnlyStates(", factory));
        assertTrue(factoryBody.contains("ButtonAction.REVEAL_HAND"),
            "五按钮工厂必须真的产出 REVEAL_HAND 动作");
        // 当前叫分者：四个叫分键一个都不能少（明牌是**追加**，不是替换）。
        for (String bidAction : new String[] {
            "ButtonAction.BID_0", "ButtonAction.BID_1", "ButtonAction.BID_2", "ButtonAction.BID_3"}) {
            assertTrue(factoryBody.contains(bidAction),
                "当前叫分者必须保留 " + bidAction + "（明牌按钮不得挤掉叫分入口）");
        }
        // 非当前叫分者仍要能拿到自己的明牌按钮：empty 早退必须排在明牌分支之后。
        int revealBranch = branch.indexOf("revealAvailable");
        int emptyReturn = branch.indexOf("if (!currentBidder)");
        assertTrue(revealBranch >= 0 && emptyReturn > revealBranch,
            "非当前叫分者的空表早退必须排在明牌分支之后，否则加赛期看不到自己的明牌按钮");
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
