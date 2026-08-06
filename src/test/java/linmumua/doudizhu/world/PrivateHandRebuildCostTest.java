package linmumua.doudizhu.world;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

/**
 * 整手重建的代价：什么时候该重建、重建时哪些东西必须活下来。
 *
 * <p><b>守的是哪个 bug。</b>实机反馈「每次出牌整排手牌闪烁、闪烁期间选牌卡住点不动」。
 * 除了重建次数过多（见 {@code GameTableRefreshPathTest}），单次重建本身还有两处退化：
 *
 * <ul>
 *   <li>重建把动画进度表整个清掉，悬停中的牌出生即满态，下一 tick 从 0 起步——
 *       牌先掉下去缩一下、再花约 6 tick 长回来；</li>
 *   <li>重建销毁旧的点击捕获器再新建一个<b>新 entity id</b>，而客户端在收到 remove + add
 *       之前仍按旧 id 发交互包，服务端解析不到实体 → 事件压根不触发 → 点击静默消失。</li>
 * </ul>
 *
 * <p>这些用例用源码扫描守住修法，与项目里其他 {@code methodBody} 系列用例同口径。
 */
class PrivateHandRebuildCostTest {
    private static final Path MANAGER =
        Path.of("src/main/java/linmumua/doudizhu/world/PhysicalTableManager.java");

    /**
     * 签名不带完整 phase，但 LOBBY 这一位必须在。
     *
     * <p>牌的几何、物品、标签、可见性都不看 phase，它只驱动按钮，而按钮有 {@code actionSignature}
     * 这道独立闸门。带上完整 phase 会让叫分→加倍→出牌每次阶段切换都白白整手重建一次。
     *
     * <p>但 LOBBY 是真正会改变「牌面存在与否」的维度：{@code renderPrivateHandCards} 在
     * {@code phase == LOBBY} 时直接 return 不铺牌。签名漏了它，开局时牌铺不出来、散局时牌收不回去。
     * 这是删 phase 时最容易踩的坑。
     *
     * <p>失败条件：把 phase 整个删掉（LOBBY 那一位也没了），或把 {@code getPhase().displayName()}
     * 加回签名。
     */
    @Test
    void handSignatureDropsPhaseButKeepsTheLobbyBit() throws IOException {
        String body = methodBody(MANAGER,
            "private String handSignature(GameTable table, PlacedTable placed, UUID playerId)");

        assertFalse(body.contains("getPhase().displayName()"),
            "完整 phase 又进了手牌签名：叫分→加倍→出牌每次阶段切换都会白白整手重建一次，"
                + "整排手牌闪一下。phase 只驱动按钮，按钮有 actionSignature 独立闸门");
        assertTrue(body.contains("GamePhase.LOBBY"),
            "手牌签名丢了 LOBBY 这一位：renderPrivateHandCards 在 LOBBY 时直接 return 不铺牌，"
                + "所以 LOBBY ↔ 非 LOBBY 是真正改变牌面存在与否的维度——"
                + "漏了它开局牌铺不出来、散局牌收不回去");
        assertTrue(body.contains("revealed=") && body.contains("isHandRevealed"),
            "手牌签名丢了明牌状态：点明牌时手牌没变，签名不带这一位就不会重建，牌面翻不过来");
    }

    /**
     * {@code clearPrivateEntities} 不许再清动画进度表。
     *
     * <p>清掉会让重建后的牌丢掉动画存量：{@code renderPrivateHandCards} 读到 0，
     * 而如果那一帧还按阶跃值出生就会是满态（lift 0.06、scale 1.08），
     * 下一 tick {@code updatePrivateSelection} 从 0 起步只推进一步（lift 0.025、scale ~1.034）——
     * 牌先掉下去缩一下、再花约 6 tick 长回来。玩家眼睛正盯着那张牌，这一下必然看得见。
     *
     * <p>不会泄漏：{@code advanceAnimation} 在进度归零时自己 remove，{@code clearHover}
     * 每 tick 兜 hover 那张表，玩家离桌与关服路径另有整表清理。
     *
     * <p>失败条件：把 {@code hoverProgressByPlayer.remove(playerId)} 或
     * {@code selectedProgressByPlayer.remove(playerId)} 加回这个方法。
     */
    @Test
    void clearingPrivateEntitiesKeepsAnimationProgress() throws IOException {
        String body = methodBody(MANAGER,
            "private void clearPrivateEntities(PlacedTable placed, UUID playerId, Map<Integer, Interaction> keepCapturers)");

        assertFalse(body.contains("hoverProgressByPlayer.remove"),
            "clearPrivateEntities 又清了悬停进度：重建后悬停中的牌会「落下再长起来」");
        assertFalse(body.contains("selectedProgressByPlayer.remove"),
            "clearPrivateEntities 又清了选中进度：重建后已选牌的抬升会跳一下再补回来");
    }

    /**
     * 铺牌必须用动画的当前值出生，不用阶跃值。
     *
     * <p>这是上一条的另一半：进度留住了，但如果 spawn 仍然读
     * {@code selectedCardLift(isSelected, ...)} / {@code privateCardScale(isSelected, ...)}
     * 这种按布尔算的阶跃值，牌照旧出生即满态，跳变原样存在。
     *
     * <p>用 {@code currentAnimationProgress} 而不是 {@code advanceAnimation}：铺牌不该替
     * 下一 tick 走一步，否则同一 tick 内进度被推进两次，动画比配置的时长更快。
     *
     * <p>失败条件：把 spawn 那两处换回带布尔参数的重载。
     */
    @Test
    void spawnedCardsStartFromTheCurrentAnimationValue() throws IOException {
        String body = methodBody(MANAGER, "private void renderPrivateHandCards(");

        assertTrue(body.contains("currentAnimationProgress(selectedProgressByPlayer")
                && body.contains("currentAnimationProgress(hoverProgressByPlayer"),
            "铺牌没有读动画存量进度：悬停中的牌会出生即满态，下一 tick 掉下去再长回来");
        assertTrue(body.contains("animatedCardLift(selectedProgress, hoverProgress)"),
            "抬升不是按插值进度算的：牌的出生高度与下一 tick 的动画值不连续");
        assertFalse(body.contains("selectedCardLift("),
            "铺牌又用回了阶跃抬升 selectedCardLift(...)：牌出生即满态，跳变原样存在");
        assertFalse(body.contains("privateCardScale(isSelected"),
            "铺牌又用回了阶跃缩放 privateCardScale(isSelected, ...)：牌出生即放大，下一 tick 缩回去");
        assertFalse(body.contains("advanceAnimation("),
            "铺牌推进了动画进度：同一 tick 内会被推进两次，动画比配置时长更快");
    }

    /**
     * 铺牌必须尝试复用捕获器，而不是无条件新建。
     *
     * <p>这条守的就是「闪烁期间选牌卡住点不动」。销毁再新建会换一个 entity id，
     * 客户端在收到 remove + add 之前仍按<b>旧 id</b> 发 {@code ServerboundInteractPacket}，
     * 服务端按 id 解析不到实体，事件压根不触发——点击被静默丢弃，
     * 窗口约 1 tick 加半个 RTT（50ms ping 约 1~2 tick，150ms 约 3~4 tick）。
     *
     * <p>退一步说，{@code clearEntities} 还会抹掉 {@code cardBindings}，
     * 而没有 binding 的 Interaction 会被 {@code isChairFurnitureEntity} 兜底判成椅子，
     * {@code yieldsToBlockingEntity} 让位——照样静默丢弃。两道都是死的。
     *
     * <p>这个模式在本文件里早有先例：{@link PhysicalTableManager} 的 {@code syncActionWidgets}
     * 就是按钮区的复用池。
     *
     * <p>失败条件：去掉复用分支，回到只调 {@code spawnHandCardCapturer}。
     */
    @Test
    void rebuildReusesCapturersInsteadOfRespawningThem() throws IOException {
        String body = methodBody(MANAGER, "private void renderPrivateHandCards(");

        assertTrue(body.contains("reusableCapturers.remove(card.id())"),
            "铺牌不再按牌 id 认领旧捕获器：每次重建都换新 entity id，"
                + "客户端仍按旧 id 发交互包，服务端解析不到实体 → 点击被静默丢弃（选牌卡住）");
        assertTrue(body.contains("reuseHandCardCapturer("),
            "铺牌不再复用捕获器：出牌后约 1 tick + 半个 RTT 内的点击全部丢失");
        assertTrue(body.contains("spawnHandCardCapturer("),
            "复用不成时没有 spawn 兜底：捕获器缺失后这手牌会永久失去右键选牌能力且毫无报错");
    }

    /**
     * 要复用的捕获器必须在 clear 之前摘出来，并被 clear 跳过。
     *
     * <p>顺序错了就白改：{@code clearPrivateEntities} 先跑，捕获器已经被删掉，
     * 后面「复用」拿到的只会是死实体。
     *
     * <p>反过来，没被认领的旧捕获器（对应的牌已经打出去了）必须销毁：它已经不在
     * {@code privateEntitiesByPlayer} 里，漏掉就是永久的孤儿实体，留在原地继续接事件，
     * 表现为「点空气选中了一张不存在的牌」。
     */
    @Test
    void reusedCapturersSurviveTheClearAndOrphansGetDestroyed() throws IOException {
        String body = methodBody(MANAGER,
            "private void renderPrivateHand(GameTable table, PlacedTable placed, UUID playerId)");

        int pickAt = body.indexOf("reusableHandCardCapturers(");
        int clearAt = body.indexOf("clearPrivateEntities(");
        assertTrue(pickAt >= 0, "铺牌没有先摘出可复用的捕获器，这条测试的锚点已失效");
        assertTrue(clearAt >= 0, "铺牌不再清旧实体，这条测试的锚点已失效");
        assertTrue(pickAt < clearAt,
            "先 clear 再摘可复用捕获器：捕获器已经被删掉，复用拿到的是死实体，等于没改");
        assertTrue(body.contains("clearPrivateEntities(placed, playerId, reusableCapturers)"),
            "clear 没有收到要跳过的捕获器集合：它们会被连带删掉，复用失效");
        assertTrue(body.contains("discardUnclaimedCapturers("),
            "没被认领的旧捕获器没有销毁：它已不在 privateEntitiesByPlayer 里，"
                + "会变成永久孤儿实体留在原地接事件，表现为点空气选中一张不存在的牌");
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
