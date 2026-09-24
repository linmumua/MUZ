package linmumua.doudizhu.world;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/**
 * 牌桌区块卸载/重载、重建顺序与严格实体归属契约。
 *
 * @author linmumua
 * @Desc 锁定区块生命周期期间的清理、重建和 owner 边界
 * @date 2026-09-21
 */
class PhysicalTableChunkLifecycleContractTest {
    private static final Path PHYSICAL_MANAGER = Path.of(
        "src/main/java/linmumua/doudizhu/world/PhysicalTableManager.java");

    @Test
    void shutdown的reload分支把清理投递到锚点region而停服分支只清运行态() throws IOException {
        String source = readSource();
        String shutdown = between(source, "public void shutdown()", "private void ensureWorldVisualsReady");

        assertTrue(shutdown.contains("plugin.getTableManager().cancelOwnerPeriodicTasks(placed.tableName());"));
        // 机制变更（非弱化）：reload 分支原先在调用线程直接 cleanupPlacedTable(placed)，
        // 现改为逐桌登记锚点 region 请求 + 完成屏障。断言随之改为钉住「清理必须作为 region 请求提交」，
        // 比原来的字符串包含更强：它同时要求存在屏障聚合与请求登记。
        assertTrue(shutdown.contains("() -> cleanupPlacedTable(placed)"),
            "reload 分支必须把清理作为锚点 region 请求提交，而不是在调用线程直接清实体");
        assertTrue(shutdown.contains("new RegionTaskBarrier.Request("),
            "reload 分支必须逐桌登记清理请求");
        assertTrue(shutdown.contains("submitShutdownCleanupBarrier(requests)"),
            "reload 分支必须用完成屏障观察聚合结果，失败只记日志不阻塞调用线程");
        assertTrue(shutdown.contains("plugin.getServer().isStopping()"), "必须保留关服/reload 分支判定");
        assertTrue(shutdown.contains("placedTables.clear();"));
        assertTrue(shutdown.contains("actionBindings.clear();"));
        assertTrue(shutdown.contains("cardBindings.clear();"));
        assertTrue(shutdown.contains("handDealPresentations.clear();"), "停服分支也必须清理牌桌运行态");

        // 关服分支绝不能在非法 owner 上操作实体：它必须先 return，早于 region 请求登记。
        int stoppingCheck = shutdown.indexOf("plugin.getServer().isStopping()");
        int requestRegistration = shutdown.indexOf("new RegionTaskBarrier.Request(");
        assertTrue(stoppingCheck >= 0 && requestRegistration > stoppingCheck,
            "关服分支必须早于 region 请求登记返回，关服时不得再向 region 投递实体清理");
    }

    /**
     * 拆桌的世界操作必须投递到桌锚点 region。
     *
     * <p>牌桌实体属于桌的 owner region，不是发起拆桌的玩家所在 region，两者可以不在同一区域；
     * 在错误 region 上动实体会在 Folia 上非法。方向与麻将 {@code removeVisuals} 的收口一致。
     */
    @Test
    void 拆桌的实体清理与残留清扫都必须投递到桌锚点region() throws IOException {
        String source = readSource();
        String helper = between(source, "private void cleanupPlacedTableOnOwnerRegion",
            "private void cleanupPlacedTable(PlacedTable placed)");
        assertTrue(helper.contains("runRegionStage(placed.anchor(), () -> {"),
            "拆桌清理必须投递到桌锚点 region");
        assertTrue(helper.contains("cleanupPlacedTable(placed);"));
        assertTrue(helper.contains("purgeResidualWorldArtifacts(placed.anchor(), placed.yaw());"),
            "锚点残留清扫必须留在同一 region 任务内，不能另起一次调用线程调用");
        assertTrue(helper.contains("exceptionally("), "异步清理失败必须记录日志，不能静默吞掉");

        String remove = between(source, "public void removeTable(String tableName)",
            "public void forceRemoveTable(String tableName)");
        String force = between(source, "public void forceRemoveTable(String tableName)", "public void shutdown()");
        assertTrue(remove.contains("cleanupPlacedTableOnOwnerRegion(placed);"), "removeTable 必须走锚点 region 路由");
        assertTrue(force.contains("cleanupPlacedTableOnOwnerRegion(placed);"), "forceRemoveTable 必须走锚点 region 路由");
        assertFalse(remove.contains("purgeResidualWorldArtifacts("),
            "removeTable 不得在调用线程直接清扫世界");
        assertFalse(force.contains("purgeResidualWorldArtifacts("),
            "forceRemoveTable 不得在调用线程直接清扫世界");
    }

    @Test
    void owner匹配严格拒绝桌名代次或任一身份字段不一致() {
        UUID ownerId = UUID.randomUUID();
        PhysicalTableManager.TableOwner expected = new PhysicalTableManager.TableOwner(
            ownerId, "Alice", "table-a", 9L);

        assertTrue(PhysicalTableManager.matchesEntityOwner(
            expected, ownerId.toString(), "Alice", "table-a", "table", 9L));
        assertFalse(PhysicalTableManager.matchesEntityOwner(
            expected, ownerId.toString(), "Alice", "table-a", "table", 10L));
        assertFalse(PhysicalTableManager.matchesEntityOwner(
            expected, ownerId.toString(), "Alice", "table-b", "table", 9L));
        assertFalse(PhysicalTableManager.matchesEntityOwner(
            expected, UUID.randomUUID().toString(), "Alice", "table-a", "table", 9L));
        assertFalse(PhysicalTableManager.matchesEntityOwner(
            expected, ownerId.toString(), "Bob", "table-a", "table", 9L));
    }

    @Test
    void owner字段缺失或空角色不能让残留实体被误认领() {
        UUID ownerId = UUID.randomUUID();
        PhysicalTableManager.TableOwner expected = new PhysicalTableManager.TableOwner(
            ownerId, "Alice", "table-a", 1L);

        assertFalse(PhysicalTableManager.matchesEntityOwner(
            expected, ownerId.toString(), "Alice", "table-a", "", 1L));
        assertFalse(PhysicalTableManager.matchesEntityOwner(
            expected, ownerId.toString(), "Alice", null, "table", 1L));
        assertFalse(PhysicalTableManager.matchesEntityOwner(
            expected, ownerId.toString(), null, "table-a", "table", 1L));
        assertFalse(PhysicalTableManager.matchesEntityOwner(
            expected, ownerId.toString(), "Alice", "table-a", "table", null));
        assertFalse(PhysicalTableManager.matchesEntityOwner(
            null, ownerId.toString(), "Alice", "table-a", "table", 1L));
    }

    @Test
    void footprint覆盖负坐标锚点的完整3x3区块() {
        java.util.List<Long> keys = PhysicalTableManager.anchorChunkKeys(-1, -1);
        assertEquals(9, keys.size());
        assertEquals(9, new java.util.LinkedHashSet<>(keys).size());
        assertTrue(keys.contains(pack(-2, -2)));
        assertTrue(keys.contains(pack(-1, -1)));
        assertTrue(keys.contains(pack(0, 0)));
    }

    @Test
    void 邻桌已登记实体通过UUID索引保留而未登记实体不受保护() {
        UUID neighborEntity = UUID.randomUUID();
        java.util.Map<UUID, PhysicalTableManager.TableOwner> owners = new java.util.LinkedHashMap<>();
        owners.put(
            neighborEntity,
            new PhysicalTableManager.TableOwner(UUID.randomUUID(), "Alice", "neighbor", 1L));

        assertTrue(PhysicalTableManager.isTrackedEntity(owners, neighborEntity));
        assertFalse(PhysicalTableManager.isTrackedEntity(owners, UUID.randomUUID()));
    }

    @Test
    void 动态手牌重建后立即回填UUID索引() throws IOException {
        String source = readSource();
        String privateSelection = between(source, "private void updatePrivateSelection", "private void updateBacksideSelection");
        String backsideSelection = between(source, "private void updateBacksideSelection", "private void clearPrivateEntities");

        assertTrue(privateSelection.contains("reindexPlacedTableEntities(normalize(placed.tableName()), placed);"));
        assertTrue(backsideSelection.contains("reindexPlacedTableEntities(normalize(placed.tableName()), placed);"));
    }

    @Test
    void 重建保持旧放置状态直到收口成功提交() throws IOException {
        String source = readSource();
        String rebuild = between(source, "public CompletionStage<Void> rebuildAllTables()",
            "public CompletionStage<Void> repairIncompleteTables(");
        String shift = between(source, "public CompletionStage<Void> shiftAllAnchors(double deltaY)",
            "private CompletionStage<Void> rebuildSingleTable(String tableName)");
        String capture = between(source, "private RebuildRequest captureSingleRebuild(String tableKey, double deltaY)",
            "private RebuildRequest freezeRebuildRequest(");

        // 机制变更（非弱化）：旧实现先 placedTables.clear() / remove + unindex + markTableUnplaced，会开出
        // 一个"这张桌不存在"的秒级窗口——窗口内 TableManager.cleanupIfEmpty 会把空桌注销（永久丢桌），
        // 重建失败也会连旧桌一起丢。断言因此反过来钉住"旧状态必须活到收口提交"，比原来的"必须先清空"更强：
        // 它同时禁止清空、禁止摘索引、禁止切回未放置。
        for (String body : List.of(rebuild, shift)) {
            assertFalse(body.contains("placedTables.clear();"), "重建不得提前整批清空放置状态");
            assertFalse(body.contains("markTableUnplaced("),
                "重建不得提前把桌切回未放置：那会放开 cleanupIfEmpty 把空桌注销");
            assertFalse(body.contains("unindexPlacedTable("), "重建不得提前摘除 footprint/实体索引");
            assertTrue(body.contains("captureSingleRebuild("), "重建必须在调用 lane 冻结快照并打上在飞标记");
        }
        assertOrdered(rebuild,
            "captureSingleRebuild(",
            "runRebuildBatch(requests,",
            "restoreOccupiedChairHitboxVisibility"
        );

        // 冻结快照只读旧状态，用在飞标记做同桌互斥，不再摘除旧桌。
        assertTrue(capture.contains("placedTables.get(tableKey)"),
            "冻结快照必须只读 placedTables，不得摘除旧桌");
        assertTrue(capture.contains("rebuildingTableKeys.add(tableKey)"),
            "冻结快照必须打上在飞标记");
        assertFalse(capture.contains("placedTables.remove("), "冻结快照不得摘除旧桌");
        assertFalse(capture.contains("markTableUnplaced("), "冻结快照不得把桌切回未放置");
        assertTrue(capture.contains("freezeRebuildRequest("), "冻结快照必须产出不可变重建参数");
        assertTrue(capture.contains("previous.anchor().clone().add(0.0, deltaY, 0.0)"),
            "Y 位移必须把新锚点写进冻结快照");
    }

    /**
     * 闸门必须前移到 spawn 之前，收口拒绝必须清理已生成的新桌。
     *
     * <p>意图：旧实现先在**新锚点 region 生成新桌**、再在 global 收口做身份检查；收口拒绝时新实体/方块
     * 已经落到世界里，而它们既没有 owner 也没有索引，只能变成永久孤儿。本条锁定两个修复方向：
     * 闸门前移（拒绝时根本不生成）+ 收口拒绝时把新桌投回**自己的锚点 region** 清理。
     */
    @Test
    void 重建闸门前置到spawn之前且收口拒绝必须在自己的锚点清理() throws IOException {
        String source = readSource();
        String pipeline = between(source, "private CompletionStage<Void> runSingleRebuild(",
            "private String rebuildGateRejection(");
        String gate = between(source, "private String rebuildGateRejection(RebuildRequest request)",
            "CompletionStage<Void> dispatchRebuildCleanupOnOwnerRegion(");
        String commit = between(source, "private PlacedTable commitRebuiltTable(",
            "private void refreshRebuiltTableOnOwnerRegion(");
        String cleanup = between(source, "CompletionStage<Void> dispatchRebuildCleanupOnOwnerRegion(",
            "private CompletionStage<Void> footprintLoadsForRebuild(");

        assertOrdered(pipeline,
            "String rejection = rebuildGateRejection(request);",
            "return null;",
            "return rebuildTableOnOwnerRegion(request);"
        );
        assertTrue(pipeline.contains("dispatchRebuildCleanupOnOwnerRegion("),
            "收口被闸门拦下时必须清理已生成的新桌，不能只丢结果留下孤儿实体/方块");
        assertTrue(pipeline.contains("() -> cleanupPlacedTable(value)"),
            "孤儿清理必须针对刚生成的新桌");
        assertTrue(pipeline.contains("rebuildingTableKeys.remove(request.tableKey())"),
            "流水线无论成败都必须撤掉在飞标记，否则该桌永远无法再重建");

        // 前置与收口共用同一份判据，避免两处漂移。
        assertTrue(gate.contains("plugin.isShuttingDown()"), "闸门必须检查插件关闭");
        assertTrue(gate.contains("plugin.getTableManager().getTable(request.tableName())"),
            "闸门必须检查桌实例身份");
        assertTrue(gate.contains("placedTables.get(request.tableKey()) != request.previous()"),
            "闸门必须检查放置代次（期间被拆桌/清过的桌不得再复活）");
        assertTrue(commit.contains("rebuildGateRejection(request)"),
            "收口必须复用同一闸门判据");
        assertFalse(commit.contains("plugin.isShuttingDown()"),
            "收口不得再手写第二套独立闸门：判据只许有一处");

        // 清理必须投到该桌自己的锚点 region，失败记日志不静默吞。
        assertTrue(cleanup.contains("runRegionStage(ownerAnchor, cleanupBody)"),
            "孤儿清理必须投到该桌自己的锚点 region，不能在 global 或旧锚点 region 上删新锚点侧实体");
        assertTrue(cleanup.contains("exceptionally("), "孤儿清理失败必须记录日志");
    }

    @Test
    void 单桌修复和Y位移也必须先加载锚点footprint再清理旧实体() throws IOException {
        String source = readSource();
        String single = between(source, "private CompletionStage<Void> rebuildSingleTable(String tableName)",
            "private RebuildRequest captureSingleRebuild(String tableKey, double deltaY)");
        String shift = between(source, "public CompletionStage<Void> shiftAllAnchors(double deltaY)",
            "private CompletionStage<Void> rebuildSingleTable(String tableName)");
        String pipeline = between(
            source,
            "private CompletionStage<Void> runSingleRebuild(RebuildRequest request, Consumer<PlacedTable> afterRefresh)",
            "private String rebuildGateRejection(RebuildRequest request)");

        assertTrue(single.contains("captureSingleRebuild(normalize(tableName), 0.0)"),
            "单桌修复必须先冻结重建参数");
        assertTrue(single.contains("runRebuildBatch(List.of(request), null)"),
            "单桌修复必须走同一条顺序化 stage 流水线");

        assertTrue(shift.contains("captureSingleRebuild(tableKey, deltaY)"),
            "Y 位移必须冻结重建参数（含新锚点位移）");

        // 机制变更（非弱化）：同步的「ensureAnchorChunkLoaded 预热 → cleanupPlacedTable 清旧」已改为
        // 异步 stage 顺序。旧断言（锚点 3x3 同步预热必须早于清理）随之升级为：锚点 footprint 的**异步
        // 加载**必须排在旧锚点清旧、spawn 前闸门、新锚点生成、global 收口与锚点刷新之前；
        // 清旧投旧锚点、生成投新锚点；收口拒绝时在锚点 region 清理孤儿。
        assertTrue(pipeline.contains("footprintLoadsForRebuild(oldAnchor, newAnchor)"),
            "重建流水线必须先发起新旧锚点 footprint 的异步加载");
        assertOrdered(pipeline,
            "footprintLoadsForRebuild(oldAnchor, newAnchor)",
            "dispatchOwnerRegionAfter(",
            "() -> cleanupPlacedTable(request.previous())",
            "dispatchOwnerRegionValueAfter(",
            "String rejection = rebuildGateRejection(request);",
            "return rebuildTableOnOwnerRegion(request);",
            "commitRebuiltTable(request, value)",
            "refreshRebuiltTableOnOwnerRegion(request, committedTable, afterRefresh)",
            "dispatchRebuildCleanupOnOwnerRegion("
        );
    }

    @Test
    void 单桌修复必须先完成CleanupPlan预检再进入破坏性步骤() throws IOException {
        String source = readSource();
        String repair = between(source, "private CompletionStage<Void> repairTableAfterChunkLoad", "private CleanupPlan captureCleanupPlan");
        String submit = between(source, "private CompletionStage<Void> submitCleanupPlan", "private RegionTaskBarrier.Result validateCleanupBarrier");

        assertOrdered(
            repair,
            "CleanupPlan plan = captureCleanupPlan(key, table, current, epoch);",
            "if (!plan.unresolved().isEmpty())",
            "return CompletableFuture.completedFuture(null);",
            "if (placedTable(key) != current)",
            "removePlacedTableIfSame(key, current);",
            "return submitCleanupPlan(plan);"
        );
        // 机制变更（非弱化）：旧实现在摘除放置状态前调 cancelOwnerPeriodicTasks(key)，它会
        // entries.remove + cancelled=true 地**永久摘除**三个注册表里的条目；ownerPeriodicTasks 的开局
        // 发牌 timer 没有任何重新注册路径，于是修复一次就把发牌时间线永久打死，periodicTasks 的
        // tickActionBar 消失还会让收口的 notifyTableAnchorBinding 抛异常并跳过刷新。现在改为只摘放置
        // 状态——removePlacedTableIfSame → markTableUnplaced 已用原子 rebind（带 bindingGeneration 门禁）
        // 把周期任务切到 global，修复成功后由 notifyTableAnchorBinding 重绑回新锚点。
        // 断言随之从"必须先取消"反转为"严禁取消"，比原来更强：它同时禁止永久摘除与漏掉 rebind。
        assertFalse(repair.contains("plugin.getTableManager().cancelOwnerPeriodicTasks("),
            "修复路径严禁硬取消周期任务：永久摘除后 rebind 只会返回 false，任务不会复活");
        assertTrue(repair.contains("removePlacedTableIfSame(key, current);"),
            "修复必须只摘放置状态，离开旧 owner lane 交给 markTableUnplaced 的原子 rebind");
        assertTrue(repair.contains("保留牌桌索引"), "预检失败必须明确记录并保留 placed/footprint/entity 索引");
        assertFalse(repair.contains("removePlacedTable(key)"), "修复路径不得在预检前使用无身份校验的摘除");
        assertTrue(submit.contains("new RegionTaskBarrier(plugin.scheduler(), requests, 100L)"), "清理必须提交多 region 屏障");
        assertOrdered(
            submit,
            "barrier.start();",
            "return barrier.completion()",
            "runGlobalStage(() -> validateCleanupBarrier(plan, result))",
            "finishCleanupPlan(plan, result)"
        );
    }

    @Test
    void 缺失实体允许提交清理屏障但现存实体无法定位仍保守退出() throws IOException {
        String source = readSource();
        String repair = between(source, "private CompletionStage<Void> repairTableAfterChunkLoad", "private CleanupPlan captureCleanupPlan");
        String capture = between(source, "private CleanupPlan captureCleanupPlan", "private static List<UUID> flattenEntityBuckets");
        String queue = between(source, "private void queueChunkLoadRepair", "private CompletionStage<Void> repairTableAfterChunkLoad");

        // 【机制升级】实体解析从直接调 Bukkit.getEntity 收口到 worldBodyLane.resolveEntity，
        // 并且在读位置之前必须先过 lane 归属门禁——实服（Lophine 26.2）就是在这里读
        // entity.getLocation()/getVehicle() 抛 "Accessing entity state off owning region's thread"，
        // 中断整条修复、使牌桌永久停在 global。断言随之从"钉具体调用表达式"升级为
        // "钉解析入口 + 门禁必须先于位置读取"，方向更强（旧断言只锁住调用文本，锁不住顺序保证）。
        assertOrdered(
            capture,
            "Entity entity = worldBodyLane.resolveEntity(entityId);",
            "if (entity == null) {",
            "continue;",
            "if (!worldBodyLane.isOwnedByCurrentRegion(entity)) {",
            "unresolved.add(\"entity-off-lane:\" + entityId);",
            "Location location = entity.getLocation();",
            "if (location == null || location.getWorld() == null)",
            "unresolved.add(\"entity-location:\" + entityId)"
        );
        int missingBranch = capture.indexOf("if (entity == null) {");
        int locationRead = capture.indexOf("Location location = entity.getLocation();", missingBranch);
        assertTrue(missingBranch >= 0 && locationRead > missingBranch, "必须先处理缺失实体，再读取现存实体位置");
        // 断言意图不变（"缺失 tracked entity 视为已不存在、不得记 unresolved"），只是把范围从
        // "null 分支到读位置之间的整段"收窄到 **null 分支体本身**：这段区间现在还合法地包含
        // 跨 region 实体的 unresolved 记账（那是"现存但不可安全定位"，与缺失是两回事）。
        // 这是断言编码旧机制后的必要收窄，不是放宽——缺失分支仍被逐字钉住。
        int missingBranchBodyEnd = capture.indexOf("continue;", missingBranch);
        assertTrue(missingBranchBodyEnd > missingBranch, "缺失分支必须以 continue 结束");
        assertFalse(capture.substring(missingBranch, missingBranchBodyEnd).contains("unresolved.add("),
            "缺失 tracked entity 不得加入 unresolved");
        assertTrue(capture.contains("不归属当前 lane 的实体按\"不可安全定位\"处理"),
            "跨 region 实体必须走既有保守分支，不得抛异常中断修复");
        assertTrue(capture.contains("tracked UUID 查不到实体表示它已经不存在"), "缺失 tracked entity 必须视为已不存在");
        assertTrue(capture.contains("只有实体对象仍存在但无法安全定位时才阻断"), "现存但无法定位的实体必须阻断清理");
        assertOrdered(
            repair,
            "CleanupPlan plan = captureCleanupPlan(key, table, current, epoch);",
            "if (!plan.unresolved().isEmpty())",
            "return CompletableFuture.completedFuture(null);",
            "removePlacedTableIfSame(key, current);",
            "return submitCleanupPlan(plan);"
        );
        assertFalse(repair.contains("plugin.getTableManager().cancelOwnerPeriodicTasks("),
            "保守保留与破坏性摘除两条路径都不得永久取消周期任务");
        assertTrue(queue.contains("stage = failedStage(failure);"), "capture 异常必须传播为失败 stage，不能吞掉");
        assertTrue(queue.contains("chunkLoadRepairQueued.remove(key);"), "capture 异常结束后必须释放 queued 状态以便重试");
        assertTrue(queue.contains("activeChunkLoadRepairs.remove(key);"), "capture 异常结束后必须释放 active 状态以便重试");
    }

    @Test
    void CE根实体缺失视为已不存在但根存在时按根位置原子清理() throws IOException {
        String source = readSource();
        String capture = between(source, "private CleanupPlan captureCleanupPlan", "private static List<UUID> flattenEntityBuckets");
        String execute = between(source, "private void executeCleanupRegion", "private static boolean sameWorldAndChunk");

        assertTrue(capture.contains("for (UUID entityId : craftEngineEntities)"), "CE 实体必须单独冻结根实体计划");
        assertTrue(capture.contains("if (location == null) {\n                continue;"), "缺失 CE 根实体不得伪造 region request");
        assertOrdered(
            execute,
            "Entity root = Bukkit.getEntity(cleanup.rootId());",
            "if (root == null || !sameWorldAndChunk(root.getLocation(), part.ownerLocation()))",
            "plugin.getCraftEngineFurnitureService().removeFurniture(root);",
            "forceRemoveEntityTree(root);"
        );
        assertTrue(execute.contains("CE 家具不写 MUZ owner PDC；root 是一个原子操作"), "CE 根存在时必须按根原子清理");
    }

    @Test
    void 清理完成后才允许残留扫描和spawn且失败超时不得重建() throws IOException {
        String source = readSource();
        String finish = between(source, "private CompletionStage<Void> finishCleanupPlan", "private boolean canRebuildCleanupPlan");

        assertOrdered(
            finish,
            "if (result == null || result.status() != RegionTaskBarrier.Status.COMPLETED)",
            "return CompletableFuture.completedFuture(null);",
            "return runRegionStage(plan.anchor(), () -> {",
            "if (!canRebuildCleanupPlan(plan))",
            "if (canPurgeResidualForPlan(plan))",
            "purgeResidualWorldArtifacts(plan.anchor().clone(), plan.yaw());",
            "PlacedTable rebuilt = spawnTable("
        );
        assertTrue(finish.contains("区块加载修复清理屏障失败"), "失败/超时必须记录并退出");
        assertTrue(finish.contains("区块加载修复残留清理失败，不重建"), "残留清理失败不得 spawn");
        assertTrue(finish.contains("return;"), "失败路径必须在 spawn 前返回");
    }

    @Test
    void ChunkLoad修复同时受queued和active去重并在迟到终止时释放状态() throws IOException {
        String source = readSource();
        String queue = between(source, "private void queueChunkLoadRepair", "private CompletionStage<Void> repairTableAfterChunkLoad");
        String repair = between(source, "private CompletionStage<Void> repairTableAfterChunkLoad", "private CleanupPlan captureCleanupPlan");

        assertOrdered(
            queue,
            "if (activeChunkLoadRepairs.contains(key))",
            "if (!chunkLoadRepairQueued.add(key))",
            "stage = repairTableAfterChunkLoad(table);",
            "chunkLoadRepairQueued.remove(key);",
            "activeChunkLoadRepairs.remove(key);"
        );
        assertTrue(queue.contains("handle.onTermination"), "owner lane 迟到取消必须释放去重状态");
        assertTrue(queue.contains("stageRef[0] == null"), "回调尚未开始时终止才释放 queued/active");
        assertTrue(repair.contains("if (!activeChunkLoadRepairs.add(key))"), "单桌 repair barrier 必须再次阻止重复 active barrier");
        assertTrue(repair.contains("cleanupEpochByTable.merge(key, 1L, Long::sum)"), "每次冻结 cleanup plan 都必须递增 epoch");
    }

    @Test
    void CleanupPlan重建必须校验epoch身份和完整footprint() throws IOException {
        String source = readSource();
        String capture = between(source, "private CleanupPlan captureCleanupPlan", "private static List<UUID> flattenEntityBuckets");
        String validate = between(source, "private boolean canRebuildCleanupPlan", "private boolean canPurgeResidualForPlan");

        assertTrue(capture.contains("footprintChunkKeys = List.copyOf(placed.footprintChunkKeys())"), "CleanupPlan 必须冻结 footprint 快照");
        assertTrue(capture.contains("epoch"), "CleanupPlan 必须携带 cleanup epoch");
        assertOrdered(
            validate,
            "cleanupEpochByTable.getOrDefault(plan.tableKey(), -1L) == plan.epoch()",
            "plugin.getTableManager().getTable(plan.tableKey()) == plan.table()",
            "placedTable(plan.tableKey()) == null",
            "isFootprintLoaded(plan.anchor(), plan.footprintChunkKeys())"
        );
    }

    private static long pack(int chunkX, int chunkZ) {
        return ((long) chunkX << 32) | (chunkZ & 0xFFFFFFFFL);
    }

    private static String between(String source, String startMarker, String endMarker) {
        int start = source.indexOf(startMarker);
        assertTrue(start >= 0, "缺少源码入口: " + startMarker);
        int end = source.indexOf(endMarker, start + startMarker.length());
        assertTrue(end > start, "缺少源码结束边界: " + endMarker);
        return source.substring(start, end);
    }

    /**
     * 读取 {@code PhysicalTableManager} 源码并统一换行为 LF，供跨行契约片段匹配。
     *
     * <p>本类断言的是「这些源码片段存在、且顺序正确」这一语义，而不是文件用哪种换行符；Windows 工作区里
     * 源码是 CRLF，而 {@link Files#readString} 不做换行翻译，直接写 {@code \n} 的跨行片段永远匹配不上——
     * 那会把「实现没退化」误报成「生命周期顺序缺少或错位」。这里只归一化换行，不放松任何片段内容与顺序要求。
     */
    private static String readSource() throws IOException {
        return Files.readString(PHYSICAL_MANAGER).replace("\r\n", "\n");
    }

    private static void assertOrdered(String source, String... snippets) {
        int cursor = -1;
        for (String snippet : snippets) {
            int next = source.indexOf(snippet, cursor + 1);
            assertTrue(next > cursor, "生命周期顺序缺少或错位: " + snippet);
            cursor = next;
        }
    }
}
