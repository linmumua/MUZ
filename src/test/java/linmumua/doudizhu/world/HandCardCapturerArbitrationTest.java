package linmumua.doudizhu.world;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

/**
 * 手牌点击捕获器在仲裁链路上的归属，以及判不中时的放行语义。
 *
 * <p>捕获器是原版 {@code Interaction}，而 {@code isFurnitureEntityClass} 显式把 Interaction
 * 算作家具。这让它天然落进两处「像家具就当成别人的东西」的兜底逻辑，两处都会把点击吃掉：
 *
 * <ul>
 *   <li>{@code isChairFurnitureEntity} 的兜底分支把它判成椅子家具，于是
 *       {@code yieldsToBlockingEntity} 让位——点击既不选牌也不 cancel，被静默丢弃；</li>
 *   <li>{@code clearResidualEntities} 对 Interaction 一律强删，捕获器会被邻桌清场删掉，
 *       之后这手牌再也点不动，而且毫无报错。</li>
 * </ul>
 *
 * <p>两处的豁免口都挂在「这是插件登记的牌实体」上，所以捕获器必须同时进
 * {@code privateEntitiesByPlayer} 和 {@code cardBindings}。这些用例锁的就是这条链路真的通了。
 */
class HandCardCapturerArbitrationTest {
    private static final Path MANAGER =
        Path.of("src/main/java/linmumua/doudizhu/world/PhysicalTableManager.java");

    /**
     * 登记为牌实体的捕获器不能被兜底判成椅子家具。
     *
     * <p>这是整条修法的命门。捕获器四个条件本来全部满足兜底分支：是家具类型（Interaction）、
     * 不在 {@code staticEntities}、不在 {@code actionEntities}；只剩
     * {@code trackedCard} 这一条能挡住它。一旦挡不住，
     * {@code yieldsToBlockingEntity(furniture=true, usableButton=false, chairFurniture=true)}
     * 判定<b>让位</b>，点击被静默丢弃——症状与完全没修一模一样，而且更难查，
     * 因为捕获器明明存在、事件明明发出来了。牌本体和牌面标签当年踩的是同一个坑。
     *
     * <p>失败条件：从兜底判据里去掉 {@code trackedCard} 这一条，或把它的极性写反。
     */
    @Test
    void trackedCardEntitiesAreNotMistakenForChairFurniture() {
        assertFalse(
            PhysicalTableManager.fallsBackToChairFurniture(true, false, false, true),
            "捕获器被兜底判成椅子家具：仲裁会让位，右键点牌既不选牌也不取消事件，"
                + "点击被静默丢弃，症状与完全没修一样");
    }

    /**
     * 兜底判据的其余各格必须保持原样。
     *
     * <p>加 {@code trackedCard} 这条排除时不能顺手改坏别的分支：真正的 CE 椅子家具在重启后
     * 可能没被插件记录，兜底是它唯一的识别路径；识别不到玩家就坐不上椅子
     * （{@code shouldCancelProtectedInteract} 靠椅子这一档放行事件给 CraftEngine）。
     */
    @Test
    void untrackedFurnitureNearChairsStillFallsBackToChair() {
        assertTrue(
            PhysicalTableManager.fallsBackToChairFurniture(true, false, false, false),
            "重启后未被记录的 CE 椅子家具不再被识别：事件不会放行给 CraftEngine，玩家坐不上椅子");
        assertFalse(
            PhysicalTableManager.fallsBackToChairFurniture(false, false, false, false),
            "非家具实体被判成椅子：怪物晃到椅子附近会被放行，点击行为错乱");
        assertFalse(
            PhysicalTableManager.fallsBackToChairFurniture(true, true, false, false),
            "插件自己的 staticEntities 被判成椅子家具");
        assertFalse(
            PhysicalTableManager.fallsBackToChairFurniture(true, false, true, false),
            "插件自己的 actionEntities 被判成椅子家具：按钮会走进椅子那一档");
    }

    /**
     * 捕获器必须登记进 {@code cardBindings}，而且<b>不能</b>登记进 {@code actionBindings}。
     *
     * <p>{@code cardBindings} 一次解决两件事：{@code isTrackedActionEntity} 认它，
     * 于是 {@code clearResidualEntities} 的强删豁免生效（邻桌清场半径会波及本桌手牌）；
     * {@code isTrackedCardEntity} 也认它，于是上面那条椅子误判自动消失。
     *
     * <p>反过来<b>绝不能</b>放进 {@code actionBindings}：那会让
     * {@code isActionButtonEntity} 认它是按钮，{@code yieldsToBlockingEntity} 的
     * {@code usableButton} 为真而让位，点击又一次被丢掉——换个地方复现同一个 bug。
     *
     * <p>失败条件：改成 actionBindings，或干脆不登记。
     */
    @Test
    void capturerIsRegisteredAsACardBindingNotAnActionBinding() throws IOException {
        String body = methodBody(MANAGER, "private UUID spawnHandCardCapturer(");

        assertTrue(body.contains("cardBindings.put(capturer.getUniqueId()"),
            "捕获器没登记进 cardBindings：会被 clearResidualEntities 强删（之后这手牌再也点不动），"
                + "而且会被 isChairFurnitureEntity 的兜底判成椅子而让位");
        assertTrue(!body.contains("actionBindings.put"),
            "捕获器登记进了 actionBindings：仲裁会把它当按钮而让位，点击照样被丢掉");
    }

    /**
     * 捕获器必须进 {@code privateEntitiesByPlayer}，否则换手牌时留一堆孤儿实体。
     *
     * <p>{@code clearPrivateEntities} 只回收这个桶里的实体。捕获器是不可见的 Interaction，
     * 漏回收不会被肉眼发现，只会在牌桌周围越积越多，最后表现为「点牌命中了别的牌」——
     * 旧牌的捕获器还在原地接事件。
     */
    @Test
    void capturerJoinsThePrivateEntityBucketSoItGetsRecycled() throws IOException {
        String body = methodBody(MANAGER, "private UUID spawnHandCardCapturer(");
        assertTrue(body.contains("spawned.add(capturer.getUniqueId())"),
            "捕获器没进 privateEntitiesByPlayer：clearPrivateEntities 回收不到它，"
                + "换手牌后旧捕获器留在原地继续接事件，表现为点牌命中别的牌");
    }

    /**
     * 捕获器只对牌主可见。
     *
     * <p>别人看到的是背面牌（{@code renderBacksideHand} 刻意不挂捕获器），用不着捕获器。
     * 而隐藏的实体不会同步到那个客户端，也就不会有三家的捕获器互相抢射线。
     *
     * <p>刻意<b>不</b>跟 revealed 走：明牌时把捕获器也发给旁观者，旁观者右键它会走进手牌仲裁，
     * 而 {@code pickHandCard} 按【点击者自己】的手牌求交必然判不中，事件最后被保护判定静默取消
     * ——等于在别人牌面上凭空造出一片点不动方块的死区。
     *
     * <p>失败条件：改用带 revealed 的那个重载。
     */
    @Test
    void capturerIsVisibleOnlyToTheCardOwner() throws IOException {
        String body = methodBody(MANAGER, "private UUID spawnHandCardCapturer(");
        assertTrue(body.contains("applyPrivateVisibility(playerId, capturer)"),
            "捕获器不是只对牌主可见：三家的捕获器会互相抢射线");
        assertTrue(!body.contains("applyPrivateVisibility(playerId, capturer, revealed)"),
            "捕获器跟着 revealed 发给了旁观者：旁观者右键它会走进手牌仲裁并判不中，"
                + "事件被保护判定静默取消，等于在别人牌面上造出一片点不动方块的死区");
    }

    /**
     * 背面牌不挂捕获器。
     *
     * <p>背面牌是给别人看的，点它没有任何语义。挂上去只会多出三倍实体，
     * 并且在别人视角里造出一片吞事件的区域。
     */
    @Test
    void backsideHandGetsNoCapturer() throws IOException {
        String body = methodBody(MANAGER,
            "private void renderBacksideHand(GameTable table, PlacedTable placed, UUID playerId)");
        assertTrue(!body.contains("spawnInteraction") && !body.contains("spawnHandCardCapturer"),
            "背面牌也挂了捕获器：点它没有任何语义，只会在别人视角造出吞事件的区域");
    }

    /**
     * {@code pickHandCard} 判不中时必须放行，这是死区缺陷的回归防线。
     *
     * <p>捕获器把「点得到事件」的范围重新变大了，所以这条放行语义比以前更关键：
     * 捕获器 0.1×0.1 的横截面里若有求交判不中的角落，判不中却返回 true 就等于在那里
     * 重建当年那圈吞事件的死区——贴着牌边点桌面既选不到牌也放不了方块。
     *
     * <p>失败条件：{@code hit == null} 时返回 true。
     */
    @Test
    void missingAllCardsStillLetsTheEventThrough() throws IOException {
        String body = methodBody(MANAGER,
            "public boolean handleHandCardClick(Player player, boolean rightClick)");

        int missAt = body.indexOf("if (hit == null)");
        assertTrue(missAt >= 0, "找不到判不中的分支，这条测试的锚点已失效");
        String miss = body.substring(missAt, Math.min(body.length(), missAt + 80));
        assertTrue(miss.contains("return false"),
            "判不中却没有放行：捕获器范围内求交判不中的角落会重建吞事件的死区，"
                + "贴着牌边点桌面既选不到牌也放不了方块。实际片段：" + miss);
    }

    /**
     * 仲裁那一路判不中时同样必须放行。
     *
     * <p>捕获器抢到点击时走的是 {@code handleHandCardClickBlockedBy}，它有自己的判不中出口。
     * 只守 {@code handleHandCardClick} 那一处是不够的——捕获器恰恰让这条路成了常态。
     */
    @Test
    void arbitrationAlsoLetsMissesThrough() throws IOException {
        String body = methodBody(MANAGER,
            "public boolean handleHandCardClickBlockedBy(Player player, boolean rightClick, Entity blocking)");

        int missAt = body.indexOf("if (arbitrationPick == null)");
        assertTrue(missAt >= 0, "找不到仲裁的判不中分支，这条测试的锚点已失效");
        String miss = body.substring(missAt, Math.min(body.length(), missAt + 80));
        assertTrue(miss.contains("return false"),
            "仲裁判不中却没有放行：捕获器周围会出现点不了方块的死区。实际片段：" + miss);
    }

    /**
     * 捕获器缺失时必须被补齐，而且补齐后玩家仍然点得动。
     *
     * <p>捕获器是右键选牌唯一的事件入口。被邻桌清场之类的路径删掉后若不补回来，
     * 这手牌就再也点不动，而且没有任何报错——只能靠玩家反馈。这个风险<b>没有变</b>。
     *
     * <p><b>语义改写说明（不是放宽）。</b>这条用例原来锁的是实现手段：
     * 「{@code instanceof Interaction} 之后 160 字符内必须出现 {@code renderPrivateHand}」。
     * 捕获器改成复用池后，{@code renderPrivateHand} 不再无条件新建捕获器，
     * 所以「触发一次整手重建」本身已经<b>不足以</b>保证捕获器被补齐——
     * 必须同时确认重建那一侧在复用不成时会 spawn 一个新的。
     * 于是这里把断言从「调了哪个方法」换成两条更强的不变量：
     *
     * <ul>
     *   <li>增量路径发现捕获器不在了，必须走到重建（下半段）；</li>
     *   <li>重建路径解析不到旧捕获器时，必须新建一个（上半段）——
     *       这一条是原来那版<b>压根没有守</b>的。</li>
     * </ul>
     *
     * <p>失败条件：任一侧断掉。去掉增量路径的存活检查，或让重建路径在复用不成时不 spawn。
     */
    @Test
    void aMissingCapturerGetsReplenishedAndStaysClickable() throws IOException {
        String incremental = methodBody(MANAGER,
            "private void updatePrivateSelection(GameTable table, PlacedTable placed, UUID playerId)");

        assertTrue(incremental.contains("capturerId()"),
            "增量更新完全不看捕获器：它被删掉后永远不会被补回来，这手牌再也点不动");
        int checkAt = incremental.indexOf("instanceof Interaction");
        assertTrue(checkAt >= 0, "找不到捕获器存活检查，这条测试的锚点已失效");
        String tail = incremental.substring(checkAt, Math.min(incremental.length(), checkAt + 160));
        assertTrue(tail.contains("renderPrivateHand"),
            "捕获器缺失时没有走到重建：这手牌会永久失去右键选牌能力且毫无报错。实际片段：" + tail);

        // 重建那一侧：复用池解析不到旧实体时，必须 spawn 一个新的补上。
        // 复用改造之后，这才是「捕获器一定会被补齐」的真正落点。
        String pick = methodBody(MANAGER,
            "private Map<Integer, Interaction> reusableHandCardCapturers(PlacedTable placed, UUID playerId)");
        assertTrue(pick.contains("instanceof Interaction"),
            "复用池不检查旧捕获器是否还活着：死实体会被当成可复用，牌永久点不动");

        String rebuild = methodBody(MANAGER, "private void renderPrivateHandCards(");
        assertTrue(rebuild.contains("spawnHandCardCapturer("),
            "重建路径在复用不成时不新建捕获器：缺失的捕获器永远补不回来，"
                + "这手牌会永久失去右键选牌能力且毫无报错");
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
