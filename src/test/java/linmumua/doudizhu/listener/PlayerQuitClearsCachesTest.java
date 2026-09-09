package linmumua.doudizhu.listener;

import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

/**
 * 玩家下线必须清掉他在 PhysicalTableManager 里的按玩家分组缓存。
 *
 * <p>【为什么 tick 兜不住】：PhysicalTableManager.tick() 里确实有
 * "离桌就 clearHover + clearPickDebug" 的逻辑，看起来能兜底。
 * 但那个循环遍历的是 {@code Bukkit.getOnlinePlayers()} —— 玩家一旦下线，
 * 他的 UUID 就再也不会被遍历到，那几张 map 里的 key 从此永久留着。
 *
 * <p>涉及 7 个容器：hoveredCardIds、lastHandCardClickTicks、hoverCandidateCardIds、
 * hoverCandidateTicksByViewer、hoverGraceTicksByViewer、hoverProgressByPlayer、
 * selectedProgressByPlayer，外加 pickDebugPool / pickDebugSignatures。
 * 单个 key 很小，但玩家反复进出时累积没有上限，属于典型的慢性内存泄漏。
 */
class PlayerQuitClearsCachesTest {
    private static final Path LISTENER =
        Path.of("src/main/java/linmumua/doudizhu/listener/PlayerConnectionListener.java");
    private static final Path MANAGER =
        Path.of("src/main/java/linmumua/doudizhu/world/PhysicalTableManager.java");

    /** 退出和被踢都是离线，两条路径都必须清。 */
    private static final String[] OFFLINE_HANDLERS = {
        "public void onQuit(PlayerQuitEvent event)",
        "public void onKick(PlayerKickEvent event)",
    };

    @Test
    void bothOfflinePathsClearPlayerCaches() throws IOException {
        String source = Files.readString(LISTENER);

        for (String signature : OFFLINE_HANDLERS) {
            String body = methodBody(source, signature);
            assertTrue(
                body.contains("clearPlayerCaches"),
                signature + " 没清 PhysicalTableManager 的按玩家缓存，"
                    + "该玩家的 key 会永久留在 hover/选中/调试面板那几张 map 里"
            );
        }
    }

    /**
     * clearPlayerCaches 必须覆盖全部三类：hover、调试面板、选中进度。
     *
     * <p>selectedProgressByPlayer 单独列出来是因为它不在 clearHover 里，
     * 而是跟着私有手牌实体走。只调 clearHover 会漏掉它。
     */
    @Test
    void clearPlayerCachesCoversAllPerPlayerContainers() throws IOException {
        String body = methodBody(Files.readString(MANAGER), "public void clearPlayerCaches(UUID playerId)");

        assertTrue(body.contains("clearHover("), "没清 hover 系列缓存");
        assertTrue(body.contains("clearPickDebug("), "没清调试面板实体池");
        assertTrue(
            body.contains("selectedProgressByPlayer.remove("),
            "没清 selectedProgressByPlayer。它不在 clearHover 里，"
                + "只调 clearHover 会把这一张漏掉"
        );
    }

    /**
     * clearHover 必须清干净它名下那 6 张 map。
     *
     * <p>失败条件：有人往按玩家分组的缓存里加了新 map，却忘了在这里一起清。
     * 那种漏法很隐蔽——功能正常，只是内存慢慢涨。
     */
    @Test
    void clearHoverRemovesEveryHoverContainer() throws IOException {
        String body = methodBody(Files.readString(MANAGER), "private void clearHover(UUID playerId)");

        String[] containers = {
            "hoveredCardIds",
            "lastHandCardClickTicks",
            "hoverCandidateCardIds",
            "hoverCandidateTicksByViewer",
            "hoverGraceTicksByViewer",
            "hoverProgressByPlayer",
        };
        for (String container : containers) {
            assertTrue(
                body.contains(container + ".remove("),
                "clearHover 漏了 " + container + "，该玩家的条目会留在里面"
            );
        }
    }

    /**
     * 空 UUID 必须直接返回，不能往下走。
     *
     * <p>退出事件理论上不会给 null，但 clearPlayerCaches 是 public 的，
     * 别处误传 null 时不该在这里抛 NPE。
     */
    @Test
    void nullPlayerIdIsIgnored() throws IOException {
        String body = methodBody(Files.readString(MANAGER), "public void clearPlayerCaches(UUID playerId)");

        assertTrue(
            body.contains("playerId == null"),
            "clearPlayerCaches 没做 null 保护，误传时会抛 NPE"
        );
    }

    private static String methodBody(String source, String signature) {
        int start = source.indexOf(signature);
        assertTrue(start >= 0, "找不到 " + signature + "，这条测试的锚点已失效");
        int end = source.indexOf("\n    }", start);
        assertTrue(end > start, "找不到 " + signature + " 的结束锚点");
        return source.substring(start, end);
    }
}
