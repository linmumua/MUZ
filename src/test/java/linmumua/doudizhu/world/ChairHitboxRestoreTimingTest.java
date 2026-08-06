package linmumua.doudizhu.world;

import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * 椅子判定框的可见性恢复只许挂一次性时机，不许回到刷新链路。
 *
 * 意图：守的是"每次出牌椅子抖一下把人挤开"这个真实缺陷不许复发。
 *
 * 因果链（已按调用链核实）：
 * 玩家出牌 → GameTable.finalizePlayedMove → advanceAfterResolvedTurn 使 currentTurn 易主
 * → refreshPhysicalTable → PhysicalTableManager.refresh → refreshActionButtons。
 * refreshActionButtons 靠 actionSignature 做早退，而 actionSignature 里嵌了
 * actionStatesForSeat，后者在 PLAYING / BIDDING / DOUBLING 阶段都按 currentTurn 分叉
 * （不是当前回合就返回空列表），所以每次出牌签名必变、早退必然失效，
 * 必然走到 syncActionWidgets。恢复调用一旦挂在那里，就等于每次出牌都对
 * 正坐在椅子上的玩家重发一遍判定框实体的显示，频率与用户报告的"每次出牌抖一下"吻合。
 *
 * 推断（非已验证事实）：CE 的 showHitboxes 会重发 Shulker hitbox 的 spawn 包，
 * 客户端每次收到都重新判定一次"卡在实体里"并把玩家挤出去。
 * CE 不在本项目依赖里、读不到 showHitboxes 实现，这一步是按调用频率、
 * "离散单次抖动、无周期任务、这是刷新链路里唯一碰椅子的操作"推出来的成因。
 * 已排除：椅子实体从不被 teleport（teleportIfMoved 的调用点都不传椅子）、
 * 从不被 respawn（唯一生成点是 spawnTable）、也没有周期任务重算椅子。
 *
 * 恢复本身不能删：hideEntity 的隐藏状态按玩家持久，旧版本已经把实体藏起来的
 * 在线玩家不会自愈，必须主动显式 show 一次。所以这里同时钉住"确实还有人在调"，
 * 否则"摘掉高频调用"会退化成"功能没了"。
 *
 * 用源码扫描而不是调用方法：这段逻辑依赖 Bukkit 的 World / Player / Entity，
 * 这个项目的测试跑不起 Bukkit；而"哪个方法里调了它"恰好能在源码层面判定，
 * 且正是本次修复的要点。写法沿用 OccupiedChairHitboxVisibilityTest 与 SeatLabelParityTest。
 */
class ChairHitboxRestoreTimingTest {
    private static final Path MANAGER = Path.of("src/main/java/linmumua/doudizhu/world/PhysicalTableManager.java");
    private static final Path LISTENER =
        Path.of("src/main/java/linmumua/doudizhu/listener/PlayerConnectionListener.java");
    private static final String RESTORE = "restoreOccupiedChairHitboxVisibility";

    /**
     * 出牌链路上的方法体里不许出现恢复调用。
     *
     * 什么情况下会失败：有人把 restoreOccupiedChairHitboxVisibility(...) 加回
     * syncActionWidgets / refreshActionButtons / refresh 三者任一的方法体
     * （注释里提到方法名不算，断言前已剥掉注释）。这三个都在每次出牌必经的路径上，
     * 加回去就等于恢复"每次出牌重发判定框、把坐着的玩家挤开"的旧行为。
     *
     * 为什么锚这三个：syncActionWidgets 是原来的调用点；refreshActionButtons 是它的
     * 直接调用者，也是签名早退所在处，最容易被"顺手补一下"；refresh 是每次出牌的统一
     * 刷新入口。三者覆盖了从 refresh 到原调用点的整条热路径。
     */
    @Test
    void thePerPlayRefreshPathNeverRestoresChairHitboxVisibility() throws IOException {
        List<String> offenders = new ArrayList<>();
        for (String signature : List.of(
            "private void syncActionWidgets(",
            "private void refreshActionButtons(",
            "public void refresh(GameTable"
        )) {
            if (stripComments(methodBody(MANAGER, signature)).contains(RESTORE + "(")) {
                offenders.add(signature);
            }
        }

        assertTrue(
            offenders.isEmpty(),
            "椅子判定框恢复又被挂回每次出牌都会走的刷新链路，坐着的玩家会被反复挤开：" + offenders
        );
    }

    /**
     * 一次性时机必须确实存在，否则"摘掉高频调用"会变成"功能没了"。
     *
     * 什么情况下会失败：有人把 syncViewer 或 rebuildAllTables 里的恢复调用也删掉。
     * 那样旧版本已经对某个在线玩家 hideEntity 过的判定框永远不会恢复
     * —— 隐藏状态按玩家持久、不会自愈 —— 该玩家会一直点不到椅子、坐不下。
     */
    @Test
    void theOneShotTimingsStillRestoreChairHitboxVisibility() throws IOException {
        List<String> missing = new ArrayList<>();
        for (String signature : List.of("public void syncViewer(", "public void rebuildAllTables(")) {
            if (!stripComments(methodBody(MANAGER, signature)).contains(RESTORE + "(")) {
                missing.add(signature);
            }
        }

        assertTrue(
            missing.isEmpty(),
            "一次性恢复时机被删了，旧版本隐藏过判定框的在线玩家会永久坐不下：" + missing
        );
    }

    /**
     * 玩家上线这条路必须真的能走到恢复。
     *
     * 恢复挂在 syncViewer 上，而玩家上线是靠 PlayerConnectionListener 把 syncViewer
     * 排进延迟任务的（join 时区块与实体可能还没就绪，所以沿用项目既有的 runLater 多次
     * 预热惯例，而不是在事件里同步调一次）。
     *
     * 什么情况下会失败：PlayerJoinEvent 不再最终走到 syncViewer（比如 onJoin 被改成
     * 别的处理、或 scheduleViewerWarmup 里不再调 syncViewer）。那时上线玩家身上的
     * 历史隐藏状态就没有任何恢复时机了。
     */
    @Test
    void playerJoinStillReachesTheViewerSyncThatRestores() throws IOException {
        String listener = stripComments(Files.readString(LISTENER));

        assertTrue(
            listener.contains("PlayerJoinEvent"),
            "PlayerConnectionListener 不再监听上线事件，上线玩家的判定框隐藏状态没有恢复时机"
        );
        assertTrue(
            listener.contains("syncViewer("),
            "上线处理不再调用 syncViewer，椅子判定框的一次性恢复走不到了"
        );
        assertTrue(
            listener.contains("runLater("),
            "上线处理不再延迟执行，join 瞬间区块与实体可能还没就绪，恢复会打空"
        );
    }

    /**
     * 剥掉注释再做断言。
     *
     * syncActionWidgets 的注释里特意写了方法名来解释"为什么这里不再调它"，
     * 直接查子串会把这段说明误判成调用。只按真实代码判定。
     */
    private static String stripComments(String source) {
        return source.replaceAll("(?s)/\\*.*?\\*/", "").replaceAll("(?m)//.*$", "");
    }

    private static String methodBody(Path file, String signature) throws IOException {
        String source = Files.readString(file);
        int start = source.indexOf(signature);
        assertTrue(start >= 0, "找不到 " + signature + "，这条测试的锚点已失效");
        int end = source.indexOf("\n    }", start);
        assertTrue(end > start, "找不到 " + signature + " 的结束锚点");
        return source.substring(start, end);
    }
}
