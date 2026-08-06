package linmumua.doudizhu.game;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

/**
 * 手牌刷新只能走一条路：{@code refreshPhysicalTable()}。
 *
 * <p><b>守的是哪个 bug。</b>实机反馈「每次出牌整排手牌闪烁、闪烁期间选牌点不动」。
 * 根因是一次出牌触发了 2~6 次整手重建，而不是 1 次：
 *
 * <ul>
 *   <li>{@code PhysicalTableManager.renderPrivateHand} 第一行就 {@code clearPrivateEntities}
 *       销毁整手实体再逐张 spawn；</li>
 *   <li>去重靠 {@code privateHandSignatureByTable} 这道签名闸门，但签名<b>只在</b>
 *       {@code refreshPrivateHands} 里写，{@code renderPrivateHand} 自己从不写；</li>
 *   <li>于是任何绕过 {@code refreshPhysicalTable} 直接调 {@code refreshPrivateHand} 的路径，
 *       对签名闸门<b>完全免疫</b>——每次都无条件整手重建。</li>
 * </ul>
 *
 * <p>GameTable 里曾有三处这样的绕过（{@code promptPlayTurn} 里补的那次、
 * {@code refreshHands()}、{@code openHandsForAll()}），而每一处的调用点后面都紧跟着
 * {@code refreshPhysicalTable}。每个 bot 回合也走同一条链，一轮三家下来真人手牌被重建
 * 4~6 次——这就是「不是我出牌时也在闪」。
 *
 * <p>这个类锁的不是某一次删除，而是<b>不许再长回来</b>：新增手牌刷新点时必须走
 * {@code refreshPhysicalTable()}，让签名闸门管得住。
 */
class GameTableRefreshPathTest {
    private static final Path TABLE = Path.of("src/main/java/linmumua/doudizhu/game/GameTable.java");

    /**
     * GameTable 不得再出现绕过签名闸门直接调 {@code refreshPrivateHand} 的路径。
     *
     * <p>这是这次修复最重要的一条回归防线，它防的是根因复发。判据取「一次调用都不许有」
     * 而不是「不许超过 N 次」：{@code refreshPhysicalTable → refresh → refreshPrivateHands}
     * 已经覆盖 cards / revealed / seatIndex / isLobby 全部会改变牌面的维度，
     * GameTable 侧没有任何需要绕过闸门的正当理由。
     *
     * <p>失败条件：在 GameTable 里任何位置写下
     * {@code plugin.getPhysicalTableManager().refreshPrivateHand(...)}。
     */
    @Test
    void gameTableNeverCallsRefreshPrivateHandDirectly() throws IOException {
        String source = stripComments(Files.readString(TABLE));

        assertEquals(0, countOccurrences(source, "refreshPrivateHand"),
            "GameTable 又出现了直接调 refreshPrivateHand 的路径：renderPrivateHand 不写签名表，"
                + "签名闸门对这条路径完全无效，每个回合（含每个 bot 回合）都会无条件整手重建，"
                + "实机表现是别人出牌时自己的整排手牌也在闪，且闪烁期间选牌点不动。"
                + "手牌刷新请一律走 refreshPhysicalTable()。");
    }

    /**
     * 出牌回合推进后仍然必须刷新一次牌桌，否则牌面根本不更新。
     *
     * <p>上一条只说「不许绕过闸门」，不能变成「干脆不刷新」——那会把闪烁换成
     * 「打出去的牌还留在手上」。这条守住正路仍然在：
     * {@code advanceAfterResolvedTurn} 与 {@code promptPlayTurn} 都得走 refreshPhysicalTable。
     */
    @Test
    void turnAdvanceStillRefreshesThroughTheSignatureGate() throws IOException {
        String source = Files.readString(TABLE);

        assertTrue(methodBody(source, "private void advanceAfterResolvedTurn(UUID playerId, boolean continueFlow)")
                .contains("refreshPhysicalTable()"),
            "回合推进后不再刷新牌桌：打出去的牌会留在手上不消失");
        assertTrue(methodBody(source, "private void promptPlayTurn()").contains("refreshPhysicalTable()"),
            "出牌回合开始时不再刷新牌桌：按钮与牌面停留在上一个回合的状态");
    }

    /** 去掉注释，避免注释里解释历史的文字被判成真实调用。 */
    private static String stripComments(String source) {
        return source.replaceAll("(?s)/\\*.*?\\*/", "").replaceAll("(?m)//.*$", "");
    }

    private static int countOccurrences(String source, String needle) {
        int count = 0;
        int at = source.indexOf(needle);
        while (at >= 0) {
            count++;
            at = source.indexOf(needle, at + needle.length());
        }
        return count;
    }

    private static String methodBody(String source, String signature) {
        int start = source.indexOf(signature);
        assertTrue(start >= 0, "找不到 " + signature + "，这条测试的锚点已失效");
        int end = source.indexOf("\n    }", start);
        assertTrue(end > start, "找不到 " + signature + " 的结束锚点");
        return source.substring(start, end);
    }
}
