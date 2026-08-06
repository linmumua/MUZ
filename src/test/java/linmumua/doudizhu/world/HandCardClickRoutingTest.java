package linmumua.doudizhu.world;

import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

/**
 * 手牌点击改走 {@code PlayerInteractEvent} 之后必须守住的几件事。
 *
 * <p>背景：每张牌原来挂一个 Interaction 触发器，点击靠实体事件进来。触发器带来两个真实缺陷：
 * 它的碰撞箱是正方形，在牌面之外鼓出一圈，那圈里右键会触发事件但解析求交判不中，只能吞掉，
 * 于是贴着牌边点桌面既选不到牌也放不了方块；另外每桌多出 17 个实体。
 * 触发器删掉后点牌不再产生任何实体事件，只能从方块/空气事件转进解析拾取。
 *
 * <p>用源码扫描而不是调用方法：这条链路要 Bukkit 的事件、Player 和实体，这个项目跑不起
 * Bukkit。写法沿用 {@code TableRemoverEntityPathTest}。
 */
class HandCardClickRoutingTest {
    private static final Path LISTENER =
        Path.of("src/main/java/linmumua/doudizhu/listener/WorldTableInteractionListener.java");
    private static final Path MANAGER =
        Path.of("src/main/java/linmumua/doudizhu/world/PhysicalTableManager.java");
    private static final Path CE_LISTENER =
        Path.of("src/main/java/linmumua/doudizhu/listener/CraftEngineProtectionListener.java");

    /**
     * 手牌带诊断必须不过滤实体类型，且裁决走生产同一个谓词。
     *
     * <p>这条诊断存在的理由就是现有 describeChairInteractGuards 的两个盲区：
     * 它只扫椅子周围，且用 isLikelyFurnitureEntity 把非家具过滤掉。
     * 吞掉点牌的桌子家具判定框不在椅子周围，怪物挡牌也看不见——
     * 恰好是仲裁最需要确认的两类。加了过滤就等于把盲区照搬过来，诊断白写。
     *
     * <p>失败条件：给循环加回类型过滤，或裁决自己另写一套判断。
     */
    @Test
    void handBandDiagnosticsListEveryEntityAndReuseTheProductionVerdict() throws IOException {
        String body = methodBody(MANAGER, "public List<String> describeHandCardArbitration(String tableName)");
        assertTrue(body.contains("yieldsToBlockingEntity"),
            "手牌带诊断的裁决没走生产谓词：会给出和实际点击不一致的结论");
        assertTrue(!body.contains("if (!isLikelyFurnitureEntity(nearby)) {"),
            "手牌带诊断过滤掉了非家具实体：怪物挡牌这一类看不见，等于照搬旧诊断的盲区");
        // 这条诊断按牌桌扫、没有 Player，算不出「按钮够不着」那一半判据。
        // 那就必须在文本里标明前提，不能报一个它其实不知道的确定裁决。
        assertTrue(body.contains("让位(仅玩家在3格内)"),
            "按钮裁决没标距离前提：这条诊断没有 Player 算不了距离，"
                + "报成确定的「让位」会让人以为站多远都是按钮赢");
    }

    /**
     * 诊断输出必须和仲裁走同一套判据。
     *
     * <p>/muz debug hitbox &lt;桌&gt; player &lt;名&gt; 是人工排查点牌问题的唯一手段。
     * 它得能区分两种成因：拾取压根没命中（准星没对准牌，几何问题），
     * 还是命中了但仲裁让位给了实体（路由问题）。只报"目标=xxx"区分不了。
     *
     * <p>失败条件：诊断自己另写一套判断。那样仲裁改了诊断不改，
     * 排查时会照着一份过期输出找错方向，比没有诊断更糟。
     */
    @Test
    void diagnosticsReportTheSameArbitrationVerdictAsProduction() throws IOException {
        String body = methodBody(MANAGER, "public String describePlayerInteractionState(String tableName, Player player)");
        assertTrue(body.contains("pickHandCardForArbitration"),
            "诊断没报手牌拾取结果：分不清准星没对准和仲裁让位");
        assertTrue(body.contains("isLikelyFurnitureEntity") && body.contains("isChairFurnitureEntity"),
            "诊断的让位判据和仲裁不同源：仲裁改动后诊断会给出过期结论");
        // 按钮那一档必须连距离一起判。只查函数名在不在是不够的：
        // 距离条件加进生产时，这处诊断没跟上，站 3~6 格排查会得出和实际相反的结论，
        // 而当时的断言全都通过。所以这里锁的是「按钮判定带上了距离」这个具体形状。
        assertTrue(body.contains("isWithinActionInteractionRange(player, target)"),
            "诊断的按钮让位没带距离条件：站 3~6 格时诊断报让位、生产判给手牌，结论相反");
        assertTrue(body.contains("手牌/按钮超距"),
            "诊断没有「按钮超距」这一档：够不着的按钮会被报成让位，把排查带向错误方向");
    }

    /**
     * 悬停和点击必须共用同一个 pickHandCard。
     *
     * <p>这是「悬停亮了那张，点击就命中那张」的唯一依据，也是人工验收时肉眼能判断的全部：
     * 看到牌抬起来了就该点得中，点不中就是路由坏了而不是准星没对准。
     * 两边一旦各用一套拾取，这个对应关系会静默失效——牌照常亮，点击却落到别处，
     * 而且没有任何报错，只能靠玩家反馈「手感不对」。
     *
     * <p>失败条件：给 updateHoverState 单独写一套拾取，或让 handleHandCardClick 换用别的函数。
     */
    @Test
    void hoverAndClickShareTheSamePickSoHighlightPredictsHit() throws IOException {
        String hover = methodBody(MANAGER, "private void updateHoverState(GameTable table, Player viewer)");
        String click = methodBody(MANAGER, "public boolean handleHandCardClick(Player player, boolean rightClick)");
        assertTrue(hover.contains("pickHandCard(table, placed, viewer)"),
            "悬停没走 pickHandCard：高亮和点击可能命中不同的牌");
        assertTrue(click.contains("pickHandCard(table, placed, player)"),
            "点击没走 pickHandCard：高亮不再预示点击结果，验收失去肉眼依据");
    }

    /**
     * 实体事件<b>三</b>条路都得补一次手牌仲裁：INTERACT、INTERACT_AT、攻击。
     *
     * <p>为什么：客户端的实体射线跳过没有判定框的牌，命中牌【后面】桌子家具的 CE 判定框，
     * 而它 setResponsive(true)。准星落在判定框上时客户端只发实体事件，PlayerInteractEvent
     * 压根不触发，onHandCardClick 就永远不执行——牌照常高亮（悬停走 tick 求交不看实体）
     * 却选不动、出不掉。这正是「左右键一直有问题」的成因。
     *
     * <p>AT 那一路尤其不能漏，这是本用例原先真实存在的盲区：{@code setResponsive(true)}
     * 让客户端把 interactAt 视为已消费，<b>后续 INTERACT 包不再发出</b>。所以只挂
     * onInteract 时，右键在这些角度下一个包都等不到，事件走到保护判定被静默取消，
     * 表现为「牌能高亮但右键完全没反应」。按钮当年踩的是同一个坑，已经两路都接了；
     * 手牌漏了 AT 那一路，而本用例当时只查 onInteract 和 onAttack，正好没照到。
     *
     * <p>失败条件：删掉任一路的仲裁调用。删 INTERACT 或 AT → 那些角度选不了牌；
     * 删攻击那路 → 那些角度出不了牌。
     */
    @Test
    void allThreeEntityEventPathsAlsoArbitrateHandCardClicks() throws IOException {
        String interact = methodBody(LISTENER, "public void onInteract(PlayerInteractEntityEvent event)");
        String interactAt = methodBody(LISTENER, "public void onInteractAt(PlayerInteractAtEntityEvent event)");
        String attack = methodBody(LISTENER, "public void onAttack(EntityDamageByEntityEvent event)");
        assertTrue(interact.contains("handleHandCardClickBlockedBy"),
            "右键 INTERACT 路径没有补手牌仲裁：准星落在按钮判定框上时右键选不了牌");
        assertTrue(interactAt.contains("handleHandCardClickBlockedBy"),
            "右键 INTERACT_AT 路径没有补手牌仲裁："
                + "判定框 setResponsive(true) 会吞掉后续 INTERACT 包，"
                + "只挂 onInteract 时右键完全收不到事件");
        assertTrue(attack.contains("handleHandCardClickBlockedBy"),
            "左键实体路径没有补手牌仲裁：准星落在按钮判定框上时左键出不了牌");
    }

    /**
     * AT 那一路的手牌仲裁必须排在按钮处理和保护取消之前，理由与 INTERACT 路完全相同。
     *
     * <p>失败条件：把 AT 路里的仲裁挪到 handleActionButtonOnce 或
     * shouldCancelProtectedInteract 之后——前者让按钮抢走点击，后者让事件先被静默取消，
     * 两种都等于仲裁没写。
     */
    @Test
    void handCardArbitrationRunsFirstOnTheInteractAtPath() throws IOException {
        String body = methodBody(LISTENER, "public void onInteractAt(PlayerInteractAtEntityEvent event)");
        int arbitration = body.indexOf("handleHandCardClickBlockedBy");
        int buttons = body.indexOf("handleActionButtonOnce");
        int protection = body.indexOf("shouldCancelProtectedInteract");
        assertTrue(arbitration >= 0, "onInteractAt 里缺少手牌仲裁");
        assertTrue(buttons >= 0 && protection >= 0, "onInteractAt 里缺少按钮处理或保护取消");
        assertTrue(arbitration < buttons,
            "AT 路的手牌仲裁必须排在按钮处理之前，否则按钮永远抢在手牌前面");
        assertTrue(arbitration < protection,
            "AT 路的手牌仲裁必须排在保护取消之前，否则点牌被静默吞掉、连提示都没有");
    }

    /**
     * 右键那路的仲裁必须排在 handleInteraction 之前。
     *
     * <p>handleInteraction 命中按钮就返回 true 并让调用方取消事件。它先跑就等于
     * 按钮永远抢在手牌前面，仲裁写了也白写。
     *
     * <p>失败条件：把两个调用顺序调过来。
     */
    @Test
    void handCardArbitrationRunsBeforeActionButtonHandling() throws IOException {
        String body = methodBody(LISTENER, "public void onInteract(PlayerInteractEntityEvent event)");
        // 匹配调用语法而不是裸方法名：两个名字都在方法头注释里出现过，
        // 只按名字找会命中注释，量到的顺序不是真实执行顺序。
        int arbitration = body.indexOf(".handleHandCardClickBlockedBy(event.getPlayer()");
        int buttons = body.indexOf(".handleInteraction(event.getPlayer()");
        assertTrue(arbitration >= 0 && buttons >= 0, "onInteract 里缺少手牌仲裁或按钮处理");
        assertTrue(arbitration < buttons,
            "按钮处理排在手牌仲裁前面：按钮会吃掉所有点牌，仲裁形同不存在");
    }

    /**
     * 仲裁必须排在保护实体的静默取消之前。
     *
     * <p>这是「点牌没反应」最主要的一条路，比按钮抢事件常见得多：牌是 ItemDisplay 没有判定框，
     * 客户端的实体射线直接跳过它，命中的是牌【后面】桌子家具的 CE 判定框。桌子整棵实体树都
     * 登记为保护实体（addEntityTreeIds → protectEntityTree），而 shouldCancelProtectedInteract
     * 对非椅子的保护实体一律取消。于是右键落到桌子判定框上时：PlayerInteractEvent 压根不发，
     * onHandCardClick 不执行；onInteract 收到了但桌子没有 ActionBinding，一路走到静默取消。
     * 点击彻底消失，连提示都没有。
     *
     * <p>失败条件：把仲裁挪到 shouldCancelProtectedInteract 之后。那样桌子会先把事件吞掉，
     * 症状原样复现。
     */
    @Test
    void handCardArbitrationRunsBeforeProtectedEntityCancellation() throws IOException {
        String body = methodBody(LISTENER, "public void onInteract(PlayerInteractEntityEvent event)");
        int arbitration = body.indexOf(".handleHandCardClickBlockedBy(event.getPlayer()");
        int cancellation = body.indexOf("shouldCancelProtectedInteract(");
        assertTrue(arbitration >= 0 && cancellation >= 0,
            "onInteract 里缺少手牌仲裁或保护实体取消");
        assertTrue(arbitration < cancellation,
            "保护实体取消排在手牌仲裁前面：桌子家具判定框会静默吞掉点牌，连提示都没有");
    }

    /**
     * 仲裁按「实体自己能不能消费这次点击」让位，不能无条件偏袒手牌，也不能靠距离。
     *
     * <p>为什么不用距离：桌子家具的 CE 判定框是一整块，手牌很可能落在它内部，
     * 此时射线先撞判定框前表面，算出来的实体距离反而比牌近，按距离仲裁会判桌子赢，
     * 点击照样被吞。而那个尺寸由 CraftEngine 配置决定，不在本插件控制下。
     *
     * <p>必须让位的两类：带 ActionBinding 的按钮（否则出牌/不要按钮点不动），
     * 椅子家具（否则坐不上去，它是被 shouldCancelProtectedInteract 特意放行的）。
     *
     * <p>失败条件：删掉任一让位判断，或改回按距离比较。
     */
    @Test
    void arbitrationYieldsToEntitiesThatCanConsumeTheClick() throws IOException {
        String body = methodBody(MANAGER, "public boolean handleHandCardClickBlockedBy(Player player, boolean rightClick, Entity blocking)");
        assertTrue(body.contains("actionBindings.containsKey"),
            "仲裁没给按钮让位：出牌/不要等按钮会被手牌盖掉，点不动");
        // 按钮让位必须连距离一起判：手牌拾取到 6.0 格，按钮只在 3.0 格内可点。
        // 中间那段按钮消费不了这次点击（只会回一句「靠近一点再点击」再吃掉事件），
        // 让位给它等于白丢一次点牌。提示不会丢：没命中牌时仲裁仍返回 false，
        // handleInteraction 照样跑到那句提示。
        assertTrue(body.contains("isWithinActionInteractionRange(player, blocking)"),
            "按钮让位没带距离条件：够不着的按钮会白吞一次点牌");
        assertTrue(body.contains("isChairFurnitureEntity"),
            "仲裁没给椅子家具让位：玩家可能坐不上椅子");
        assertTrue(body.contains("isLikelyFurnitureEntity"),
            "仲裁没把非家具实体排除：怪物晃到手牌带上时左键攻击会被判成点牌并取消");
        // 三个标志必须喂给同一个可测谓词，别在这里就地判断：
        // 就地判断没法真的跑起来测，只能靠本文件的源码扫描，覆盖不到分支组合。
        // 真值表由 HandCardArbitrationVerdictTest 用真实调用逐格验。
        assertTrue(body.contains("yieldsToBlockingEntity"),
            "让位判断没走可测谓词：分支组合无法被真实执行验证");
        assertTrue(!body.contains("hit.distance()"),
            "仲裁又用回了距离比较：桌子判定框包住手牌时距离会判反，点击仍被吞");
    }

    /**
     * 左右键都得接。
     * 右键选牌、左键出牌原来分走 PlayerInteractEntityEvent 和 EntityDamageByEntityEvent 两条路，
     * 现在两条都塌到 PlayerInteractEvent 上。
     * 失败条件：只接右键——那样左键出牌彻底没入口，牌选上了却打不出去。
     */
    @Test
    void bothClickSidesRouteIntoHandCardHandling() throws IOException {
        String body = methodBody(LISTENER, "public void onHandCardClick(PlayerInteractEvent event)");

        assertTrue(body.contains("RIGHT_CLICK_AIR"), "右键空气没接，抬头看着牌右键会选不了");
        assertTrue(body.contains("RIGHT_CLICK_BLOCK"), "右键方块没接，准星穿过牌打到方块时选不了牌");
        assertTrue(body.contains("LEFT_CLICK_AIR"), "左键空气没接，出牌没有入口");
        assertTrue(body.contains("LEFT_CLICK_BLOCK"), "左键方块没接，准星穿过牌打到方块时出不了牌");
        assertTrue(body.contains("handleHandCardClick"), "没有转进手牌处理，这条链路是断的");
    }

    /**
     * 准星没落在牌上时必须完全放行，这是死区缺陷的回归防线。
     * 失败条件：{@code hit == null} 时返回 true——那就等于把旧触发器那圈吞事件的死区
     * 换了个地方重建，而且范围更大（整个牌桌周围），方块都放不了。
     */
    @Test
    void missingAllCardsLetsTheEventThroughInsteadOfSwallowingIt() throws IOException {
        String body = methodBody(MANAGER, "public boolean handleHandCardClick(Player player, boolean rightClick)");

        int missAt = body.indexOf("if (hit == null)");
        assertTrue(missAt >= 0, "找不到判不中的分支，这条测试的锚点已失效");
        String miss = body.substring(missAt, Math.min(body.length(), missAt + 80));
        assertTrue(
            miss.contains("return false"),
            "判不中却没有放行，牌桌周围会重新出现点不到方块的死区：" + miss
        );
    }

    /**
     * 主手/副手同一次点击会各发一次事件，去重必须按 tick 做。
     * 不能靠"只认主手"过滤：主手空手时那一次未必发得出来，玩家会变成空手点不了牌。
     * 失败条件：去掉 tick 去重——右键会 toggle 两遍，选中立刻被取消，表现为"点了没反应"。
     */
    @Test
    void sameTickDoubleFireIsDeduplicatedRatherThanFilteredByHand() throws IOException {
        String handler = methodBody(LISTENER, "public void onHandCardClick(PlayerInteractEvent event)");
        String body = methodBody(MANAGER, "public boolean handleHandCardClick(Player player, boolean rightClick)");

        assertTrue(
            !handler.contains("EquipmentSlot.HAND"),
            "用只认主手来去重：主手空手时那一次事件未必发得出来，会变成空手点不了牌"
        );
        assertTrue(body.contains("getCurrentTick"), "没有按 tick 去重，一次右键会 toggle 两遍等于没点");
        assertTrue(body.contains("lastHandCardClickTicks"), "去重没有记录状态，第二只手的事件会重复处理");
    }

    /**
     * 只有真的当成手牌点击处理掉时才记 tick。
     * 失败条件：在求交之前就记 tick——那样"没点到牌"的那一次会把同 tick 的另一只手也堵掉，
     * 而它本该被放行去放方块。
     */
    @Test
    void tickIsRecordedOnlyAfterAHitIsConfirmed() throws IOException {
        String body = methodBody(MANAGER, "public boolean handleHandCardClick(Player player, boolean rightClick)");

        int hitAt = body.indexOf("if (hit == null)");
        int recordAt = body.indexOf("lastHandCardClickTicks.put");
        assertTrue(hitAt >= 0 && recordAt >= 0, "找不到判定或记录锚点，这条测试已失效");
        assertTrue(
            hitAt < recordAt,
            "还没确认命中就记下 tick，同 tick 的另一只手会被无故堵掉，方块放不出去"
        );
    }

    /**
     * 左键是"确认出这一手"，不是"选这张再出"。
     * 失败条件：不检查这张牌是否已在选中集合里——那样左键会把随便点到的牌直接打出去。
     */
    @Test
    void leftClickOnlyPlaysCardsThatAreAlreadySelected() throws IOException {
        String body = methodBody(MANAGER, "private void playSelectedHandCard(GameTable table, Player player, int cardId)");

        assertTrue(body.contains("selection.isEmpty()"), "没先看有没有选牌，空手左键会直接抛异常");
        assertTrue(body.contains("selection.contains(cardId)"), "没校验点的是已选中的牌，左键会误出牌");
        assertTrue(body.contains("catch (RuntimeException"), "出牌规则异常没接住，会变成服务端报错");
    }

    /**
     * 手牌捕获器的判定框不许胖过拾取通道。
     *
     * <p>这条用例的前身是 {@code handCardsSpawnNoInteractionEntities}，当年断言「手牌上不许有任何
     * Interaction」。那个断言已经作废：牌上没有判定框时，玩家空手右键空气，服务端
     * {@code handleUseItem} 在 {@code ItemStack.isEmpty()} 处直接 return，
     * {@code PlayerInteractEvent} 压根不发，选牌 100% 失效——「没有判定框」本身才是 bug。
     *
     * <p>但当年那条用例防的死区是真的：Interaction 横截面是正方形，判定框一旦胖过拾取几何，
     * 胖出来那圈里右键会触发事件却求交判不中，只能吞掉。所以防线不是取消，而是换位置——
     * 从「不许有判定框」改成「判定框不许胖过拾取通道」。
     *
     * <p>失败条件：捕获器宽度改用比 {@code handSpacing} 更大的量（例如牌本体宽 0.225），
     * 或高度不再取两态并集而回退成单态——两者都会重新造出「触发得到、判不中」的鼓包死区。
     */
    @Test
    void handCardCapturerNeverOutgrowsThePickLane() throws IOException {
        String source = Files.readString(MANAGER);
        String widthBody = methodBody(MANAGER, "static double handCardCapturerWidth(double handSpacing)");

        assertTrue(widthBody.contains("handSpacing"), "捕获器宽度不再由 handSpacing 决定，会与拾取通道脱钩");
        assertTrue(!widthBody.contains("MODEL_WIDTH"), "捕获器宽度改用了牌本体宽，胖过通道会造出鼓包死区");
        assertTrue(source.contains("handCardCapturerEnvelope"), "捕获器高度不再取两态并集，选中的牌会有一截点不到");
        // 旧触发器的两个方法名不许复活：它们是按牌切片的老方案，精度由切片厚度决定。
        assertTrue(!source.contains("spawnCardHitbox"), "spawnCardHitbox 又出现了，切片式触发器已被捕获器取代");
        assertTrue(!source.contains("teleportCardHitbox"), "teleportCardHitbox 又出现了，切片式触发器已被捕获器取代");
    }

    /**
     * 手持放桌/拆桌棍时不认手牌点击。
     * {@code onUseTablePlacer} 不看 isCancelled，抢先取消事件会让同一次右键既选牌又去放桌子。
     * 失败条件：去掉这条提前 return。
     */
    @Test
    void tableToolsTakePrecedenceOverHandCardClicks() throws IOException {
        String body = methodBody(LISTENER, "public void onHandCardClick(PlayerInteractEvent event)");

        int toolAt = body.indexOf("isTablePlacer");
        int handleAt = body.indexOf("handleHandCardClick");
        assertTrue(toolAt >= 0, "没有给放桌棍让路，一次右键会既选牌又放桌");
        assertTrue(body.contains("isDoudizhuTableRemover"), "没有给拆桌棍让路，一次右键会既选牌又拆桌");
        assertTrue(toolAt < handleAt, "工具判定排在手牌处理之后，让路等于没让");
    }

    /**
     * 诊断必须报出这次点击走哪条事件路。
     *
     * <p>这条是为一个真实的误导补的：{@code actionTarget} 用 rayTraceEntities 且只认带
     * ActionBinding 的实体，所以它永远只找得到按钮；而桌子判定框是 CE 的 shulker 发包伪实体，
     * 服务端没有对应 Bukkit 实体，rayTraceEntities 本来也扫不到。
     * 于是点牌时 target 恒为 null，仲裁那一栏直接落到「手牌」。
     * 在仲裁还挂在 PlayerInteractEntityEvent 上的那段时间里，那条路收不到任何事件，
     * 玩家实际是点了没反应——诊断报「手牌」、现实是「无反应」，结论正好相反，
     * 照着它排查会一路走错方向。
     *
     * <p>所以「拾取命中 + 仲裁判给手牌」这两栏不足以说明点得动，还必须知道点击走哪条路。
     * 失败条件：把路由那一栏删掉，或不再区分原版与 CE 两条路。
     */
    @Test
    void diagnosticsReportWhichEventRouteTheClickTakes() throws IOException {
        String body = methodBody(MANAGER,
            "public String describePlayerInteractionState(String tableName, Player player)");

        assertTrue(body.contains("路由="),
            "诊断没有报路由：拾取命中且仲裁判给手牌时，仍可能因为那条事件路收不到事件而点了没反应，"
                + "少这一栏会让排查得出和实际相反的结论");
        assertTrue(body.contains("CE家具事件"),
            "诊断没区分出 CE 家具事件那条路：点牌命中的是发包伪判定框，只有 CE 事件收得到");
        assertTrue(body.contains("原版/按钮"),
            "诊断没区分出原版实体事件那条路：按钮是真 Interaction 实体，走的是原版事件");
    }

    /**
     * 手牌仲裁必须挂在 CE 的家具事件上，这是点牌唯一到得了的那条路。
     *
     * <p>这条测试是为一个真实故障补的，代价是「右键选牌一直没作用」而 403 个测试全绿。
     *
     * <p>成因：桌子判定框在 furniture.yml 里配的是 {@code type: shulker}，
     * 而 CE 的 ShulkerFurnitureHitbox 只有 {@code spawnPacket / despawnPacket / int[] entityIds}
     * 三个字段——发包伪实体，服务端没有对应 Bukkit 实体。客户端命中它发来的 entityId
     * 在服务端查不到实体，原版链路不会构造 PlayerInteractEntityEvent；
     * CE 的 network InteractListener 在包层截住，自己 fire FurnitureInteractEvent。
     * 所以仲裁只挂 PlayerInteractEntityEvent / EntityDamageByEntityEvent 时，
     * 不是「被别的插件取消了」，是那条路上压根没有事件送到面前。
     *
     * <p>牌是 ItemDisplay 没判定框，客户端射线必然穿过它命中后面的 shulker 判定框，
     * 于是每一次点牌都走 CE 那条包路径。少了这两个挂载点，右键选牌与左键出牌同时失效。
     *
     * <p>失败条件：把 CE 家具事件上的仲裁删掉，或改成不调用生产同一个 handleHandCardClickBlockedBy。
     */
    @Test
    void handCardArbitrationIsMountedOnCraftEngineFurnitureEvents() throws IOException {
        String source = Files.readString(CE_LISTENER);

        assertTrue(source.contains("FurnitureInteractEvent"),
            "右键没挂 CE 的 FurnitureInteractEvent：点牌命中的是发包伪实体判定框，"
                + "PlayerInteractEntityEvent 收不到，右键选牌会整体失效");
        assertTrue(source.contains("FurnitureHitEvent"),
            "左键没挂 CE 的 FurnitureHitEvent：同样成因，出牌会整体失效");

        String interact = methodBody(CE_LISTENER, "public void onFurnitureInteract(FurnitureInteractEvent event)");
        assertTrue(interact.contains("handleHandCardClickOnFurniture"),
            "右键家具事件没走手牌仲裁");
        String hit = methodBody(CE_LISTENER, "public void onFurnitureHit(FurnitureHitEvent event)");
        assertTrue(hit.contains("handleHandCardClickOnFurniture"),
            "左键家具事件没走手牌仲裁");

        String shared = methodBody(CE_LISTENER,
            "private boolean handleHandCardClickOnFurniture(");
        assertTrue(shared.contains("handleHandCardClickBlockedBy"),
            "CE 那条路另写了一套裁决：两条路径必须复用同一份让位判据与拾取几何，"
                + "否则点同一个位置走哪条事件路结果不同，会变成极难复现的偶发问题");
    }

    /**
     * 桌子判定框仍然是 shulker，上面那条挂载点才有意义。
     *
     * <p>这条锁的是上一条测试的前提。判定框类型决定点击走哪条事件路：
     * shulker 是发包伪实体走 CE 包路径；换成 interaction 就是真实体、
     * 会正常产生 PlayerInteractEntityEvent 走原版路径。
     * 哪天有人改了 furniture.yml 的 hitbox 类型，这条会失败，
     * 提醒去核对仲裁的挂载点还对不对——而不是等玩家再报一次「点牌没反应」。
     */
    @Test
    void tableHitboxesStayShulkerSoTheCraftEngineMountPointIsTheRightOne() throws IOException {
        // furniture.yml 是构建时生成的，源码树里没有，所以锚点取生成它的构建脚本。
        String build = Files.readString(Path.of("build.gradle.kts"));

        assertTrue(build.contains("- type: shulker"),
            "桌子判定框不再是 shulker：判定框类型变了，点击走的事件路可能也变了，"
                + "请核对手牌仲裁的挂载点（shulker=发包伪实体走 CE 包路径，"
                + "interaction=真实体走原版 PlayerInteractEntityEvent）");
    }

    /**
     * 桌子的判定框不许加 interaction_entity，否则事件拓扑变了。
     *
     * <p>椅子那段配了 {@code interaction_entity: true}，CE 会在 shulker 之外再挂一个真实
     * Interaction 实体，于是椅子的点击原版事件也收得到。桌子<b>没有</b>这一项，
     * 所以点桌子（射线穿过无判定框的牌命中桌子）只有 CE 的 FurnitureInteractEvent 一条路。
     *
     * <p>这条守的是「桌子只有一条路」这个前提本身。哪天有人给桌子也加上 interaction_entity，
     * 同一次点牌就会同时走 CE 事件和原版事件两条路，全靠 handleHandCardClick 的同 tick 去重
     * 兜住；去重万一同时被改坏，右键会一次切两下（选中又立刻取消，表现又是"点了没反应"）。
     * 失败时请一并复核那处去重还在不在。
     */
    @Test
    void tableHitboxesStayWithoutAnExtraInteractionEntity() throws IOException {
        String build = Files.readString(Path.of("build.gradle.kts"));

        // 只认真正写进 YAML 的那一行（appendLine 里的），不能用源码里 interaction_entity
        // 这个词出现过就算——它在注释里也出现，那样断言会恒真。
        // 桌子判定框段与椅子判定框段各自 appendLine("              - type: shulker")，
        // 桌子在前、椅子在后（构建脚本里桌子段先生成），所以取第二次出现处切开。
        String emitLine = "appendLine(\"                interaction_entity: true\")";
        int chairHitboxAt = build.indexOf("- type: shulker\")", build.indexOf("- type: shulker\")") + 1);
        assertTrue(chairHitboxAt > 0, "找不到椅子那段 shulker 判定框，这条测试的锚点已失效");

        int emitAt = build.indexOf(emitLine);
        assertTrue(emitAt > 0,
            "椅子那段不再写出 interaction_entity：椅子少了真实交互实体，点击精度和坐下会受影响");
        assertTrue(emitAt > chairHitboxAt,
            "interaction_entity 写在了桌子那段判定框里：点牌会同时走 CE 事件与原版实体事件两条路，"
                + "请复核 handleHandCardClick 的同 tick 去重仍能防住重复 toggle");
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
