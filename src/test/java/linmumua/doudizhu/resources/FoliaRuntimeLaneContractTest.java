package linmumua.doudizhu.resources;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;

/**
 * 真实 Folia 运行期 lane 契约的守护测试。
 *
 * <p>这里的规则不是风格偏好，而是 2026-09-22 在真实 Folia 26.1.2 测试端冒烟跑出来的两条硬约束：
 * <ul>
 *   <li>区域线程里禁止同步传送：{@code /muz bot add} 在 region 线程上抛
 *       {@code UnsupportedOperationException("Must use teleportAsync while in region threading")}。</li>
 *   <li>global lane 既没有 ticking region、也没有实体所有权：语音面板的 global 扫描抛
 *       {@code IllegalStateException("No currently ticking region")}，且该 lane 不能操作实体。</li>
 * </ul>
 * 因此这里把「只用 teleportAsync」和「语音面板 global 扫描只派发」钉住，防止回归。
 *
 * <p><b>本文件只守字符串形状</b>：真正的行为级守护（伪造 Leaf/Paper/Folia 核心、以 fake World
 * 实际执行预热核心）在 {@code linmumua.doudizhu.world.RegionizedCoreDetectionBehaviorTest} 与
 * {@code linmumua.doudizhu.world.AnchorChunkPreloadCoreBehaviorTest} 里，本文件的断言不再单独
 * 承担 Folia 安全证明。
 */
class FoliaRuntimeLaneContractTest {
    private static final Path MAIN_ROOT = Path.of("src/main/java");
    private static final Path SPEECH_PANEL =
        Path.of("src/main/java/linmumua/doudizhu/game/TableSpeechPanelService.java");
    private static final Path GAME_TABLE =
        Path.of("src/main/java/linmumua/doudizhu/game/GameTable.java");
    private static final Path GADGET_SERVICE =
        Path.of("src/main/java/linmumua/doudizhu/game/TableGadgetService.java");
    private static final Path GADGET_EFFECT_SERVICE =
        Path.of("src/main/java/linmumua/doudizhu/game/TableGadgetEffectService.java");
    private static final Path GADGET_BAR_HUD_SERVICE =
        Path.of("src/main/java/linmumua/doudizhu/game/TableGadgetBarHudService.java");
    private static final Path PHYSICAL_TABLE_MANAGER =
        Path.of("src/main/java/linmumua/doudizhu/world/PhysicalTableManager.java");

    /** 区域线程禁止同步传送：主源码里不允许再出现同步 teleport 调用。 */
    @Test
    void mainCodeNeverUsesSynchronousTeleport() throws IOException {
        try (Stream<Path> paths = Files.walk(MAIN_ROOT)) {
            String offenders = paths
                .filter(path -> path.toString().endsWith(".java"))
                .filter(path -> {
                    try {
                        return stripComments(Files.readString(path)).contains(".teleport(");
                    } catch (IOException exception) {
                        throw new java.io.UncheckedIOException(exception);
                    }
                })
                .map(Path::toString)
                .reduce((left, right) -> left + ", " + right)
                .orElse("");
            assertTrue(offenders.isEmpty(),
                "Folia 区域线程里同步 teleport 会抛 Must use teleportAsync，必须改用 teleportAsync：\n"
                    + offenders);
        }
    }

    /**
     * 同步拉区块只在**文档化的、按核心类型分支保护的非区域化路径**里允许出现。
     *
     * <p>实服证据（Folia 26.1.2，2026-09-23）：{@code getChunkAt(} 与 {@code chunk.load()}
     * 在 region 线程与主线程都抛 {@code IllegalArgumentException("Async chunk retrieval")}，
     * 导致存档牌桌永久恢复失败、5 秒重试持续刷屏。
     *
     * <p>但 Paper/Leaf 的主线程允许同步加载区块，且放置/重建路径依赖「执行前锚点邻域已加载」
     * 这一保证——缺了这一保证会把旧实体清掉却建不回来，整桌桌椅按钮凭空消失
     * （真实 Leaf 26.1.2 实服复现的回归）。因此实现按核心类型分支：非区域化核心保留阻塞强加载，
     * 区域化核心退化为只读 + 告警。
     *
     * <p>本契约**收窄但加固**：同步逻辑收口到两个包内可见、不依赖实例的静态核心
     * {@code ensureChunkReadyCore} 与 {@code ensureAnchorChunkLoadedCore}（便于 fake World 行为测试），
     * 因此断言改为
     * <ul>
     *   <li>除 {@code PhysicalTableManager} 外的主源码：一律不得出现同步令牌；</li>
     *   <li>{@code PhysicalTableManager} 内：同步令牌只允许出现在这两个核心方法体内，
     *       把这两个方法体挖掉后其余部分必须干净；</li>
     *   <li>这两个核心必须真的带 {@code if (regionized)} 分支，且同步调用位于该分支的
     *       {@code return;} / {@code continue;} **之后**——即只有非区域化核心才可达；</li>
     *   <li>实例入口 {@code ensureChunkReady} / {@code ensureAnchorChunkLoaded} 必须把
     *       {@code REGIONIZED} 传进核心，禁止硬编码非区域化绕过守卫。</li>
     * </ul>
     * 这样任何「把同步令牌搬到别处」「去掉 regionized 分支保护」「把阻塞调用提到分支之前」
     * 「在入口处硬编码放行同步获取」的改动都会命中相应断言。判据仍用精确子串：
     * {@code getChunkAtAsync(} 不含 {@code getChunkAt(}（"getChunkAt" 之后紧跟 'A' 而非 '('），
     * {@code isChunkLoaded} 也不含 {@code chunk.load()}。
     */
    @Test
    void syncChunkFetchOnlyInDocumentedRegionizedGuardedPaths() throws IOException {
        // 1. 除 PhysicalTableManager 外的主源码：绝不允许同步令牌。
        try (Stream<Path> paths = Files.walk(MAIN_ROOT)) {
            String offenders = paths
                .filter(path -> path.toString().endsWith(".java"))
                .filter(path -> !path.equals(PHYSICAL_TABLE_MANAGER))
                .filter(path -> {
                    try {
                        String source = stripComments(Files.readString(path));
                        return source.contains("getChunkAt(") || source.contains("chunk.load()");
                    } catch (IOException exception) {
                        throw new java.io.UncheckedIOException(exception);
                    }
                })
                .map(Path::toString)
                .reduce((left, right) -> left + ", " + right)
                .orElse("");
            assertTrue(offenders.isEmpty(),
                "只有 PhysicalTableManager 的两条文档化路径可用同步区块获取，其它文件必须走 getChunkAtAsync：\n"
                    + offenders);
        }

        // 2. 两个静态核心必须带 regionized 分支，且同步调用只在该分支 return/continue 之后可达。
        String source = stripComments(Files.readString(PHYSICAL_TABLE_MANAGER));

        String ready = methodBody(source, "static void ensureChunkReadyCore(");
        assertFalse(ready.isEmpty(), "未找到 ensureChunkReadyCore 方法体");
        int readyGuard = ready.indexOf("if (regionized)");
        assertTrue(readyGuard >= 0, "ensureChunkReadyCore 必须按核心类型分支");
        int readyReturn = ready.indexOf("return;", readyGuard);
        assertTrue(readyReturn > readyGuard,
            "ensureChunkReadyCore 的区域化分支必须先 return，非区域化路径才继续");
        assertTrue(ready.indexOf("chunk.load()") > readyReturn,
            "ensureChunkReadyCore 的阻塞 chunk.load() 必须在区域化分支 return 之后（仅非区域化核心可达）");

        String anchorReady = methodBody(source, "static void ensureAnchorChunkLoadedCore(");
        assertFalse(anchorReady.isEmpty(), "未找到 ensureAnchorChunkLoadedCore 方法体");
        int anchorGuard = anchorReady.indexOf("if (regionized)");
        assertTrue(anchorGuard >= 0, "ensureAnchorChunkLoadedCore 必须按核心类型分支");
        int anchorContinue = anchorReady.indexOf("continue;", anchorGuard);
        assertTrue(anchorContinue > anchorGuard,
            "ensureAnchorChunkLoadedCore 的区域化分支必须先 continue，非区域化路径才继续");
        assertTrue(anchorReady.indexOf("getChunkAt(") > anchorContinue,
            "ensureAnchorChunkLoadedCore 的阻塞 getChunkAt( 必须在区域化分支 continue 之后（仅非区域化核心可达）");

        // 3. 挖掉两个核心方法体后，文件其余部分不得再出现同步令牌。
        String remainder = source.replace(ready, "\n").replace(anchorReady, "\n");
        assertFalse(remainder.contains("getChunkAt("),
            "同步 getChunkAt( 只允许出现在 ensureAnchorChunkLoadedCore 的文档化非区域化分支内");
        assertFalse(remainder.contains("chunk.load()"),
            "chunk.load() 只允许出现在 ensureChunkReadyCore 的文档化非区域化分支内");

        // 4. 实例入口必须把 REGIONIZED 传进核心，禁止硬编码放行同步获取。
        String readyWrapper = methodBody(source, "private void ensureChunkReady(");
        assertFalse(readyWrapper.isEmpty(), "未找到 ensureChunkReady 入口");
        assertTrue(readyWrapper.contains("ensureChunkReadyCore(") && readyWrapper.contains("REGIONIZED"),
            "ensureChunkReady 必须把 REGIONIZED 传进 ensureChunkReadyCore，不能硬编码非区域化绕过守卫");
        String anchorWrapper = methodBody(source, "private void ensureAnchorChunkLoaded(");
        assertFalse(anchorWrapper.isEmpty(), "未找到 ensureAnchorChunkLoaded 入口");
        assertTrue(anchorWrapper.contains("ensureAnchorChunkLoadedCore(") && anchorWrapper.contains("REGIONIZED"),
            "ensureAnchorChunkLoaded 必须把 REGIONIZED 传进 ensureAnchorChunkLoadedCore");
    }

    /**
     * {@code PhysicalTableManager} 里 {@code getChunk()} 形式的同步区块获取一律禁止。
     *
     * <p>{@code Location#getChunk()} 内部就是 {@code World#getChunkAt}，在真实 Folia 上抛
     * {@code Async chunk retrieval}。历史漏网实例是 {@code placementBlockedBlocks} 里的
     * {@code anchor.getChunk().isLoaded()}，经 {@code WorldTableInteractionListener} 的放桌预览
     * （{@code tickTablePlacerPreviews} 的 player lane）可达，属真实回归。
     *
     * <p>本轮把 {@code ensureChunkReady} 的「非区域化核心阻塞强加载」也改为
     * {@code world.getChunkAt(...)}，于是全文件已无 {@code getChunk()}——本契约因此**加固**为
     * 「全文件禁止 {@code getChunk()}」：任何把它加回来（无论是否在 regionized 分支之后）都会命中。
     * 区块判断统一走只读 {@code world.isChunkLoaded(...)}，同步强加载只允许 {@code world.getChunkAt(...)}
     * （受 {@link #syncChunkFetchOnlyInDocumentedRegionizedGuardedPaths} 的 regionized 分支约束），
     * 异步入口走 {@code world.getChunkAtAsync(...)}。
     *
     * <p><b>合法例外（显式放行，不属本契约范围）：</b>{@code listener/TableWorldLifecycleListener} 的
     * {@code event.getChunk()}。那两处（{@code onChunkLoad}/{@code onChunkUnload}）读取的是
     * {@code ChunkLoadEvent}/{@code ChunkUnloadEvent} 事件自带的、已经加载好的 chunk 对象，
     * 只用它取 x/z 坐标再投递到 global lane，不会触发任何区块检索，因此合法。
     * 本契约只扫描 {@code PhysicalTableManager.java}，该例外天然不在断言范围内，这里写明以免后人误判为遗漏。
     */
    @Test
    void physicalTableManagerNeverUsesSynchronousGetChunk() throws IOException {
        String source = stripComments(Files.readString(PHYSICAL_TABLE_MANAGER));
        assertFalse(source.contains("getChunk()"),
            "PhysicalTableManager 不得出现 Location#getChunk()（内部即 World#getChunkAt，Folia 上非法）；"
                + "区域化分支用 world.isChunkLoaded(...)，非区域化分支用 world.getChunkAt(...)，异步用 getChunkAtAsync(...)");
    }

    /** global 扫描只派发：取时钟与实体访问必须留在 player lane 的 tickOwner 内。 */
    @Test
    void speechPanelSweepOnlyDispatchesToPlayerLane() throws IOException {
        String source = stripComments(Files.readString(SPEECH_PANEL));
        String sweep = methodBody(source, "public void tick()");
        assertTrue(sweep.contains("output.runPlayer("),
            "tick() 必须把每 owner 工作投递到 player lane");
        assertFalse(sweep.contains("tickSource.getAsLong()"),
            "tick() 在 global lane 上取当前 tick 会抛 No currently ticking region");
        assertFalse(sweep.contains("removePanels("),
            "tick() 不得在 global lane 上直接删面板实体");

        String ownerBody = methodBody(source, "private void tickOwner(");
        assertTrue(ownerBody.contains("tickSource.getAsLong()"),
            "读取当前 tick 必须发生在 player lane 的 tickOwner 内");
    }

    /** 面板状态会被 global 扫描与多个 player lane 并发访问，必须是并发容器。 */
    @Test
    void speechPanelStateUsesConcurrentCollections() throws IOException {
        String source = Files.readString(SPEECH_PANEL);
        assertFalse(source.contains("new HashMap<>()"),
            "面板状态被 global 扫描与多个 player lane 并发访问，不能再用 HashMap");
        assertTrue(source.contains("new ConcurrentHashMap<>()"),
            "面板状态必须使用并发容器");
    }

    /**
     * 面板实体的删除必须按 lane 归属收口：先判归属，不是本 region 就把删除投回实体自己的 region。
     *
     * <p>这是 shutdown-review 指出的关闭期问题：{@code clearPlayer}/{@code clearTable}/{@code clearAll}/
     * {@code shutdown} 可能跑在玩家 lane（玩家已走远）、桌 owner lane 或 {@code onDisable} 的主线程上，
     * 在错误 lane 上直接 {@code remove} 会抛
     * {@code Accessing entity state off owning region's thread}，并把异常沿 {@code onDisable} 链路外溢。
     * 因此 {@code removePanels} 必须逐张走 {@code removePanelEntity}，而后者必须带归属判定与投递分支，
     * 不得再出现「直接 {@code view.display.remove()}」这种无门禁删除。
     */
    @Test
    void speechPanelRemovalIsLaneGated() throws IOException {
        String source = stripComments(Files.readString(SPEECH_PANEL));

        String removePanels = methodBody(source, "private void removePanels(");
        assertFalse(removePanels.isEmpty(), "未找到 removePanels 方法体");
        assertTrue(removePanels.contains("removePanelEntity("),
            "removePanels 必须逐张走 removePanelEntity，把 lane 判定收口到一处");
        assertFalse(removePanels.contains("display.remove()"),
            "removePanels 不得再直接删面板实体：错误 lane 上会抛 Accessing entity state off owning region's thread");

        String removeOne = methodBody(source, "private void removePanelEntity(");
        assertFalse(removeOne.isEmpty(), "未找到 removePanelEntity 方法体");
        assertTrue(removeOne.contains("isOwnedByCurrentRegion("),
            "removePanelEntity 必须先判 lane 归属");
        assertTrue(removeOne.contains("removeOnOwnerRegion("),
            "非本 region 时必须把删除投回实体自己的 region");
        assertFalse(removeOne.contains("display.remove()"),
            "removePanelEntity 不得直接 display.remove()：必须经 PanelEntityLane 收口");

        // 归属判定必须早于任何删除动作，否则判定等于没写。
        int guard = removeOne.indexOf("isOwnedByCurrentRegion(");
        int removal = removeOne.indexOf("removeNow(");
        assertTrue(guard >= 0 && removal > guard,
            "lane 归属判定必须排在删除动作之前");
    }

    /**
     * {@code shutdown()} 必须先封入口再清理。
     *
     * <p>旧实现把 {@code stopped = true} 放在 {@code clearAll()} 之后：清理一旦抛异常，{@code stopped}
     * 永远为 false——服务既不关闭也不清空，异常还会沿 {@code onDisable} 链路外溢。先置位后清理可保证
     * 关闭语义一定落地。
     */
    @Test
    void speechPanelShutdownClosesEntryBeforeCleanup() throws IOException {
        String source = stripComments(Files.readString(SPEECH_PANEL));
        String shutdown = methodBody(source, "public void shutdown(");
        assertFalse(shutdown.isEmpty(), "未找到 shutdown 方法体");
        int closeEntry = shutdown.indexOf("stopped = true;");
        int cleanup = shutdown.indexOf("clearAll();");
        assertTrue(closeEntry >= 0 && cleanup > closeEntry,
            "shutdown 必须先封入口（stopped = true）再清理，否则清理异常会让服务既不关闭也不清空");
    }

    /** 关桌与回大厅都必须清掉语音面板，否则面板实体成为孤儿。 */
    @Test
    void tableCloseAndLobbyResetClearSpeechPanels() throws IOException {
        String source = stripComments(Files.readString(GAME_TABLE));
        int occurrences = 0;
        int index = source.indexOf("getTableSpeechPanelService().clearTable(");
        while (index >= 0) {
            occurrences++;
            index = source.indexOf("getTableSpeechPanelService().clearTable(", index + 1);
        }
        assertTrue(occurrences >= 2,
            "关桌与回 LOBBY 两条路径都必须清理语音面板，实际出现 " + occurrences + " 次");
    }

    /**
     * 道具效果与目标高亮的 global 扫描同样只允许派发。
     *
     * <p>实服日志记录到第二个同类实例：{@code TableGadgetEffectService.tick} 在 global lane 上抛
     * {@code No currently ticking region}，而调度器随即取消了该周期任务，导致这两项功能静默失效。
     */
    @Test
    void gadgetSweepOnlyDispatchesAndEffectsGuardBeforeClock() throws IOException {
        String gadget = stripComments(Files.readString(GADGET_SERVICE));
        String sweep = methodBody(gadget, "private void tick()");
        assertTrue(sweep.contains("runTableNow("),
            "道具效果推进必须投递到桌子 owner lane");
        assertTrue(sweep.contains("output.runPlayer("),
            "目标高亮属玩家级工作，必须投递到 player lane");
        assertFalse(sweep.contains("getCurrentTick()"),
            "global 扫描不得取时钟：global lane 没有 ticking region");
        assertFalse(sweep.contains("setGlowing("),
            "global 扫描不得直接改玩家发光");
        assertFalse(gadget.contains("new HashMap<>()"),
            "道具服务状态被 global 扫描与各 owner lane 并发访问，必须使用并发容器");

        String effects = stripComments(Files.readString(GADGET_EFFECT_SERVICE));
        String tableTick = methodBody(effects, "public void tickTable(");
        int guard = tableTick.indexOf("pending.isEmpty()");
        // 时钟读取已收口到 EffectRuntime 边界（生产实现即 Bukkit.getCurrentTick()）；这里钉的是
        // 「早退必须排在时钟读取之前」这一顺序语义，不再绑定具体的调用拼写。
        int clock = tableTick.indexOf("currentTick()");
        assertTrue(guard >= 0 && clock > guard,
            "必须在读取时钟之前用「本桌没有效果」早退：未放置桌的 owner lane 会回退到 global");
        // 顺序断言只有在「生产边界真的取真实时钟」时才有意义：若把 EffectRuntime 的生产实现换成
        // 永不抛异常的假时钟，global lane 的 No currently ticking region 就被掩盖、上面这条断言被架空。
        // 因此额外钉住生产实现仍然直接调用 Bukkit.getCurrentTick()。
        String productionRuntime = methodBody(effects, "public int currentTick()");
        assertTrue(productionRuntime.contains("Bukkit.getCurrentTick()"),
            "EffectRuntime 的生产实现必须取真实 Bukkit 时钟（global lane 上它正是会抛 No currently ticking region 的调用）");
    }

    /**
     * 两个 global 扫描周期任务必须**整段兜底**，不允许任何一步异常逃到调度器。
     *
     * <p>硬约束来自 {@code MuzScheduler.schedule}：周期回调抛异常会走
     * {@code managed.fail(repeating=true)} 并取消后端句柄，于是「一次扫描失败」升级成「整条周期任务被
     * 永久取消」，功能静默失效且无法自愈。这两条 global 扫描都会用 {@code TableManager.getTables()}
     * 读取仍属非并发 {@code LinkedHashMap} 的 {@code tables}（全局 Map 迁移是被明确推迟的技术债），
     * 而 {@code getTables()} 内部就是 {@code new ArrayList<>(tables.values())}——并发结构修改下它本身
     * 就可能抛 {@code ConcurrentModificationException} / 越界。旧写法把这个快照调用放在逐桌 try
     * **之外**（{@code for (GameTable table : tables())}），兜不住；九格道具栏预取更是整段无 try。
     *
     * <p>因此本契约钉住三条形状，任何回退都会命中：
     * <ul>
     *   <li>快照获取（{@code tables()} / {@code getTables()}）必须位于方法的兜底 {@code try} 之内
     *       （其下标必须晚于方法体里第一个 {@code try { }），且其后存在 {@code catch (RuntimeException}；</li>
     *   <li>逐桌「注销桌静默跳过」的 {@code catch (IllegalArgumentException ignored)} 不得被外层兜底取代；</li>
     *   <li>九格道具栏预取必须保留 {@code this::prefetchActivePlayers} 的 global 周期注册，并且按
     *       {@code TableGadgetService.tick} 同口径做 manager 空值早退。</li>
     * </ul>
     */
    @Test
    void globalSweepPeriodicTasksAreExceptionGuarded() throws IOException {
        String gadget = stripComments(Files.readString(GADGET_SERVICE));
        String sweep = methodBody(gadget, "private void tick()");
        int gadgetTry = sweep.indexOf("try {");
        int gadgetSnapshot = sweep.indexOf("tables()");
        assertTrue(gadgetTry >= 0, "道具扫描 tick() 必须整段兜底：周期回调抛异常会被调度器取消整条任务");
        assertTrue(gadgetSnapshot > gadgetTry,
            "getTables 快照（tables()）必须在兜底 try 之内，否则并发结构修改的 CME 会避开逐桌 catch 打死周期任务");
        assertTrue(sweep.indexOf("catch (RuntimeException", gadgetSnapshot) >= 0,
            "快照之后必须存在能接住 ConcurrentModificationException 的兜底 catch");
        assertTrue(sweep.contains("catch (IllegalArgumentException ignored)"),
            "逐桌「注销桌静默跳过」的 catch 不得被外层兜底取代（注销桌必须安静跳过，不是记日志）");

        String barHud = stripComments(Files.readString(GADGET_BAR_HUD_SERVICE));
        String prefetch = methodBody(barHud, "private void prefetchActivePlayers()");
        int prefetchTry = prefetch.indexOf("try {");
        int prefetchSnapshot = prefetch.indexOf("getTables()");
        assertTrue(prefetchTry >= 0, "九格道具栏预取是 global 周期任务，必须整段兜底");
        assertTrue(prefetchSnapshot > prefetchTry,
            "九格道具栏预取的 getTables 快照必须在兜底 try 之内（旧写法整段无 try，CME 会打死预取任务）");
        assertTrue(prefetch.indexOf("catch (RuntimeException", prefetchSnapshot) >= 0,
            "预取的快照之后必须存在兜底 catch");
        assertTrue(prefetch.contains("getTableManager()") && prefetch.contains("== null"),
            "预取必须按 TableGadgetService.tick 同口径做 manager 空值早退，而不是直接解引用");
        assertTrue(barHud.contains("this::prefetchActivePlayers"),
            "global 周期任务仍必须指向被兜底保护的 prefetchActivePlayers");
    }

    /** 取一段方法体（从签名到配对的收尾大括号）；找不到返回空串。 */
    private static String methodBody(String source, String signature) {
        int start = source.indexOf(signature);
        if (start < 0) {
            return "";
        }
        int depth = 0;
        boolean opened = false;
        for (int i = start; i < source.length(); i++) {
            char current = source.charAt(i);
            if (current == '{') {
                depth++;
                opened = true;
            } else if (current == '}') {
                depth--;
                if (opened && depth == 0) {
                    return source.substring(start, i + 1);
                }
            }
        }
        return source.substring(start);
    }

    /** 去掉行注释与块注释，避免注释里的字样把断言带偏。 */
    private static String stripComments(String source) {
        StringBuilder out = new StringBuilder(source.length());
        boolean inLine = false;
        boolean inBlock = false;
        for (int i = 0; i < source.length(); i++) {
            char current = source.charAt(i);
            char next = i + 1 < source.length() ? source.charAt(i + 1) : '\0';
            if (inLine) {
                if (current == '\n') {
                    inLine = false;
                    out.append(current);
                }
                continue;
            }
            if (inBlock) {
                if (current == '*' && next == '/') {
                    inBlock = false;
                    i++;
                }
                continue;
            }
            if (current == '/' && next == '/') {
                inLine = true;
                i++;
                continue;
            }
            if (current == '/' && next == '*') {
                inBlock = true;
                i++;
                continue;
            }
            out.append(current);
        }
        return out.toString();
    }
}
