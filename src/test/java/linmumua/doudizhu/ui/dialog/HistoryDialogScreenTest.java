package linmumua.doudizhu.ui.dialog;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import linmumua.doudizhu.storage.MatchParticipantRecord;
import linmumua.doudizhu.storage.MatchRecord;
import linmumua.doudizhu.storage.PlayerHistoryEntry;
import org.junit.jupiter.api.Test;

/**
 * 战绩 Dialog 的分页 / 越界 / 陈旧请求抛弃行为。
 *
 * <p>【为什么这些断言重要】这三点是旧箱子战绩界面里最容易被「顺手改坏」的语义，
 * 改坏后不会立刻爆错，只会悄悄少一条记录、翻页错位、或让迟到的查询把界面抢回来：
 * <ul>
 *   <li><b>分页</b>：每页容量必须与旧槽位 9..44（36 格）一致；偏移量错一位就整页错位。</li>
 *   <li><b>越界</b>：上一页钳到第 1 页、下一页不封顶（旧语义），超出容量的一律丢弃。</li>
 *   <li><b>陈旧请求</b>：异步读库完成时玩家可能已翻页 / 打开别的菜单 / 退出 / reload，旧结果必须丢弃。</li>
 * </ul>
 *
 * <p>陈旧闸门用的是 core 的共享导航代次（{@link MuzDialogService#beginRequest}/{@code requestCurrent}），
 * 底层 {@link DialogSessionRegistry} 是纯类，因此这里能在不起服务端的情况下直接驱动真实语义，
 * 覆盖「历史→其他菜单 / quit / reload」。
 */
class HistoryDialogScreenTest {

    private static final Path SCREEN =
        Path.of("src/main/java/linmumua/doudizhu/ui/dialog/HistoryDialogScreen.java");

    // ------------------------------------------------------------------
    // 分页
    // ------------------------------------------------------------------

    @Test
    void normalizePageBringsIllegalPagesBackToFirst() {
        assertEquals(1, HistoryDialogScreen.normalizePage(0), "第 0 页不是合法页，必须回到第 1 页");
        assertEquals(1, HistoryDialogScreen.normalizePage(-7), "负数页必须回到第 1 页");
        assertEquals(1, HistoryDialogScreen.normalizePage(1));
        assertEquals(4, HistoryDialogScreen.normalizePage(4), "合法页必须原样保留");
    }

    @Test
    void pageOffsetMatchesLegacySlots() {
        assertEquals(0, HistoryDialogScreen.pageOffset(1), "第 1 页 offset 必须是 0");
        assertEquals(36, HistoryDialogScreen.pageOffset(2), "第 2 页 offset 必须是 36");
        assertEquals(72, HistoryDialogScreen.pageOffset(3), "第 3 页 offset 必须是 72");
        assertEquals(0, HistoryDialogScreen.pageOffset(0), "非法页的 offset 必须按第 1 页算");
    }

    @Test
    void previousPageClampsAtFirstPage() {
        assertEquals(1, HistoryDialogScreen.previousPage(1), "第 1 页的上一页仍是第 1 页（旧 slot45 语义）");
        assertEquals(1, HistoryDialogScreen.previousPage(0), "非法页的上一页也必须收敛到第 1 页");
        assertEquals(2, HistoryDialogScreen.previousPage(3));
    }

    @Test
    void nextPageHasNoUpperBound() {
        // 旧箱子 slot53 直接 page+1，越界页显示「暂无战绩」；这里刻意保留，不做静默钳制。
        assertEquals(2, HistoryDialogScreen.nextPage(1));
        assertEquals(6, HistoryDialogScreen.nextPage(5));
    }

    // ------------------------------------------------------------------
    // 越界（行切片 / matchId 选择）
    // ------------------------------------------------------------------

    @Test
    void listRowsDropsEntriesBeyondPageCapacity() {
        List<PlayerHistoryEntry> many = new ArrayList<>();
        for (int index = 0; index < HistoryDialogScreen.LIST_PAGE_SIZE + 5; index++) {
            many.add(entry(index + 1L));
        }

        List<PlayerHistoryEntry> rows = HistoryDialogScreen.listRows(many);

        assertEquals(HistoryDialogScreen.LIST_PAGE_SIZE, rows.size(),
            "超过每页容量（36）的记录必须被丢弃，否则会溢出旧槽位映射");
        assertSame(many.get(0), rows.get(0), "切片必须保持原顺序，且不复制出新对象");
        assertEquals(36L, rows.get(35).matchId());
    }

    @Test
    void listRowsHandlesNullAndEmpty() {
        assertTrue(HistoryDialogScreen.listRows(null).isEmpty(), "null 输入必须按空页处理，不能抛 NPE");
        assertTrue(HistoryDialogScreen.listRows(List.of()).isEmpty());
    }

    @Test
    void findEntryReturnsNullForMissingMatchId() {
        List<PlayerHistoryEntry> entries = List.of(entry(11L), entry(22L));

        assertNull(HistoryDialogScreen.findEntry(entries, 99L), "查不到的 matchId 必须返回 null 以触发回退");
        assertNull(HistoryDialogScreen.findEntry(null, 11L), "null 列表必须安全返回 null");
        assertNull(HistoryDialogScreen.findEntry(entries, 0L));
    }

    @Test
    void findEntryPicksTheMatchingRecord() {
        PlayerHistoryEntry target = entry(22L);
        List<PlayerHistoryEntry> entries = List.of(entry(11L), target, entry(33L));

        assertSame(target, HistoryDialogScreen.findEntry(entries, 22L),
            "必须按 matchId 精确命中，而不是按位置取");
    }

    // ------------------------------------------------------------------
    // 陈旧请求抛弃（core 共享导航代次）
    // ------------------------------------------------------------------

    private static MuzDialogService service() {
        return new MuzDialogService(null, null, new DialogSessionRegistry());
    }

    @Test
    void pendingRequestStaysValidUntilSomethingNavigates() {
        MuzDialogService service = service();
        UUID viewer = UUID.randomUUID();

        long token = service.beginRequest(viewer, "history.pending");

        assertTrue(HistoryDialogScreen.pendingStillCurrent(service, viewer, token), "没人动过，pending 请求应保持有效");
    }

    @Test
    void openingAnotherMenuInvalidatesPendingHistory() {
        MuzDialogService service = service();
        UUID viewer = UUID.randomUUID();
        long historyToken = service.beginRequest(viewer, "history.pending");

        // 玩家在查询 pending 期间打开了个人设置（同样经 core 领新代次）。
        service.beginRequest(viewer, "settings");

        assertFalse(HistoryDialogScreen.pendingStillCurrent(service, viewer, historyToken),
            "打开了别的菜单后，迟到的历史查询绝不能再用 openRoot 抢回屏幕");
    }

    @Test
    void quittingInvalidatesPendingHistory() {
        MuzDialogService service = service();
        UUID viewer = UUID.randomUUID();
        long historyToken = service.beginRequest(viewer, "history.pending");

        // core 的 PlayerQuitEvent 接线调用 clearPlayer → sessions.clear。
        service.clearPlayer(viewer);

        assertFalse(HistoryDialogScreen.pendingStillCurrent(service, viewer, historyToken), "退出游戏后 pending 必须作废");
    }

    @Test
    void reloadOrDisableInvalidatesPendingHistory() {
        MuzDialogService service = service();
        UUID viewer = UUID.randomUUID();
        long historyToken = service.beginRequest(viewer, "history.pending");

        // core 的 /muz reload → invalidateAll / disable → shutdown，底层都是 sessions.clearAll。
        service.sessions().clearAll();

        assertFalse(HistoryDialogScreen.pendingStillCurrent(service, viewer, historyToken), "reload / disable 后 pending 必须作废");
    }

    @Test
    void onlyTheLatestHistoryRequestSurvives() {
        MuzDialogService service = service();
        UUID viewer = UUID.randomUUID();

        long firstPageToken = service.beginRequest(viewer, "history.pending");
        long secondPageToken = service.beginRequest(viewer, "history.pending");

        assertFalse(HistoryDialogScreen.pendingStillCurrent(service, viewer, firstPageToken), "翻页后旧页的查询必须被丢弃");
        assertTrue(HistoryDialogScreen.pendingStillCurrent(service, viewer, secondPageToken), "最后一页的查询才允许上屏");
    }

    @Test
    void pendingTokensAreIsolatedPerViewer() {
        MuzDialogService service = service();
        UUID viewerA = UUID.randomUUID();
        UUID viewerB = UUID.randomUUID();

        long tokenA = service.beginRequest(viewerA, "history.pending");
        service.beginRequest(viewerB, "settings");

        assertTrue(HistoryDialogScreen.pendingStillCurrent(service, viewerA, tokenA), "别人开菜单不能作废我的 pending");
        assertFalse(HistoryDialogScreen.pendingStillCurrent(service, viewerB, tokenA), "代次按玩家隔离，别人的 token 不认");
    }

    @Test
    void openAndCloseAreNullSafeWithoutPlugin() {
        // 未装配（plugin/service 为 null，单测环境）时既不能抛异常，也不能误领代次。
        HistoryDialogScreen screen = new HistoryDialogScreen(null, null, null);

        screen.openList(UUID.randomUUID(), UUID.randomUUID(), "小明", 1);
        screen.openDetail(UUID.randomUUID(), UUID.randomUUID(), "小明", 2, 123L);
        screen.close(UUID.randomUUID());

        assertNotNull(screen);
    }

    // ------------------------------------------------------------------
    // 源码契约（把「异步 IO 不在 player lane」「展示交给 core」「失败不伪造空成功」钉死）
    // ------------------------------------------------------------------

    @Test
    void storageQueryNeverRunsInsideAPlayerLaneBuild() throws IOException {
        String source = Files.readString(SCREEN);

        for (int occurrence = 1; occurrence <= 2; occurrence++) {
            String build = methodBody(source, "public Dialog build(MuzDialogContext ctx)", occurrence);
            assertFalse(build.contains("safeQuery("),
                "屏的 build 在 player lane 内执行，绝不能在这里读库");
            assertFalse(build.contains("query.load("),
                "屏的 build 直接调用了 query.load，违反「不在 player lane 做 IO」");
            assertFalse(build.contains("beginRequest("),
                "屏的 build 里不应再发起查询/领代次，取数发生在 build 之前");
        }

        String loader = methodBody(source, "private void loadAsync(", 1);
        assertTrue(loader.contains("runAsync("), "读库必须经 MuzScheduler.runAsync 下沉到异步 lane");
        assertTrue(loader.contains("safeQuery("), "异步体内应调用 safeQuery 读库");
    }

    @Test
    void displayIsDelegatedToCoreNotToPlayerShowDialog() throws IOException {
        String source = Files.readString(SCREEN);

        // 只看代码，不看注释：类注释里会以 {@code player.showDialog(..)} 提到它，那是说明文本。
        assertFalse(stripComments(source).contains("player.showDialog("),
            "player lane 展示必须统一交给 core 的 MuzDialogService，本类不得自己 showDialog");
        String listOpen = methodBody(source, "public void openList(", 1);
        String detailOpen = methodBody(source, "public void openDetail(", 1);
        assertTrue(listOpen.contains("service.openRoot("), "列表屏必须经 core 打开");
        assertTrue(detailOpen.contains("service.openRoot("), "详情屏（含回退）必须经 core 打开");
    }

    @Test
    void pendingQueryIsCountedIntoTheSharedNavigationGeneration() throws IOException {
        String source = Files.readString(SCREEN);

        for (String signature : List.of("public void openList(", "public void openDetail(")) {
            String body = methodBody(source, signature, 1);
            assertTrue(body.contains("service.beginRequest(viewerId, PENDING_SCREEN_KEY)"),
                signature + " 发起查询前必须把「准备查询」计入 core 共享导航代次");
            assertTrue(body.contains("pendingStillCurrent(service, viewerId, token)"),
                signature + " 的迟到回调必须复核代次，否则会抢回已被其它导航替换的屏幕");
            assertTrue(body.contains("return;"), signature + " 的复核不通过时必须直接返回，不能继续 openRoot");
        }
    }

    @Test
    void queryFailureIsNotFakedAsAnEmptyPage() throws IOException {
        String source = Files.readString(SCREEN);

        String loader = methodBody(source, "private void loadAsync(", 1);
        assertTrue(loader.contains("entries == null"),
            "读取失败必须以 null 与「成功但空」区分开，不能继续走 openRoot 显示空页");
        assertTrue(loader.contains("service.error("), "失败时应给发起者一条提示，并按门禁留痕");

        String query = methodBody(source, "private List<PlayerHistoryEntry> safeQuery(", 1);
        assertTrue(query.contains("catch (RuntimeException"), "读库异常必须被捕获");
        assertTrue(query.contains("return null;"), "读库异常必须返回 null（失败），而不是空列表");
    }

    @Test
    void detailBackReturnsToTheSameListPage() throws IOException {
        String source = Files.readString(SCREEN);

        String detail = methodBody(source, "public Dialog build(MuzDialogContext ctx)", 2);
        assertTrue(detail.contains("openList(ctx.playerId(), targetPlayerId, targetName, page)"),
            "详情返回按钮必须带上进入详情时的 page，否则返回页会丢");
    }

    @Test
    void listHasPagingButtonsAndEntryCarriesMatchId() throws IOException {
        String source = Files.readString(SCREEN);
        String list = methodBody(source, "public Dialog build(MuzDialogContext ctx)", 1);

        assertTrue(list.contains("previousPage(page)"), "列表必须提供上一页");
        assertTrue(list.contains("nextPage(page)"), "列表必须提供下一页");
        assertTrue(list.contains("entry.matchId()"), "列表条目必须按真实 matchId 打开详情，不能按位置猜");
        assertTrue(source.contains("\"#\" + entry.matchId()"), "详情必须显示对局编号（序号）");
    }

    // ------------------------------------------------------------------
    // 夹具
    // ------------------------------------------------------------------

    private static PlayerHistoryEntry entry(long matchId) {
        MatchRecord match = new MatchRecord(
            "DOUDIZHU", "斗地主", null, "地主胜", 1_700_000_000_000L + matchId, "world", 1.0, 2.0, 3.0);
        MatchParticipantRecord self = new MatchParticipantRecord(
            UUID.randomUUID(), "小明", "地主", "WIN", 3, 30.0, "金币", 0.0, 120.0, false);
        return new PlayerHistoryEntry(matchId, match, List.of(self), self);
    }

    /** 去掉块注释与行注释，便于对「代码」而不是「注释里的说明文字」做契约断言。 */
    private static String stripComments(String source) {
        String noBlock = source.replaceAll("(?s)/\\*.*?\\*/", "");
        return noBlock.replaceAll("(?m)//.*$", "");
    }

    /**
     * 取某个方法的方法体（从签名到匹配的右花括号）。
     *
     * <p>用花括号配对而不是「下一个类级右括号」：本类的屏实现在嵌套类里，方法体的缩进比外层深，
     * 简单的 {@code "\n    }"} 锚点会越过方法真正的结尾。{@code occurrence} 用于区分同名方法
     * （两处 {@code build(MuzDialogContext)}）。
     */
    private static String methodBody(String source, String signature, int occurrence) {
        int start = -1;
        int from = 0;
        for (int index = 0; index < occurrence; index++) {
            start = source.indexOf(signature, from);
            if (start < 0) {
                break;
            }
            from = start + signature.length();
        }
        assertTrue(start >= 0, "找不到锚点 " + signature + "（第 " + occurrence + " 次），测试已失效");
        int brace = source.indexOf('{', start);
        assertTrue(brace > start, "找不到 " + signature + " 的方法体花括号");
        int depth = 0;
        int cursor = brace;
        for (; cursor < source.length(); cursor++) {
            char current = source.charAt(cursor);
            if (current == '{') {
                depth++;
            } else if (current == '}') {
                depth--;
                if (depth == 0) {
                    break;
                }
            }
        }
        assertTrue(depth == 0, "找不到 " + signature + " 的右花括号");
        return source.substring(start, cursor + 1);
    }
}
