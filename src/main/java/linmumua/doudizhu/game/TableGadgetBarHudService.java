package linmumua.doudizhu.game;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ForkJoinPool;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Consumer;
import java.util.function.IntFunction;
import linmumua.doudizhu.DoudizhuPlugin;
import linmumua.doudizhu.assets.PackAssets;
import linmumua.doudizhu.compat.CraftEngineOffsetService;
import linmumua.doudizhu.scheduler.MuzScheduler;
import linmumua.doudizhu.ui.VirtualGadgetBar;
import linmumua.doudizhu.ui.VirtualGadgetBarStore;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerItemHeldEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.inventory.ItemStack;

/**
 * 牌桌对局中的九格道具栏数据源。
 *
 * <p>不打开 Bukkit Inventory GUI。八个虚拟道具槽和语音入口由本服务维护数据与选择状态，
 * 栏【本身】已经并入出牌 HUD（BossBar 第四行），由 {@link TrickHudService} 经
 * {@link #hudBarSnapshot(UUID)} 读取只读渲染快照后渲染；本服务不再拼 ActionBar、也不碰 ActionBar
 * 槽位（那是普通状态提示的），所以道具栏与状态提示不再互相覆盖闪烁。
 * 玩家仍用滚轮/数字键选择槽位，右键沿既有桌内道具路由执行；选择第九槽时右键打开仅自己可见的实体语音面板。
 */
public final class TableGadgetBarHudService implements Listener {
    /**
     * 九格栏的【最大】格数：8 个道具格 + 1 个语音入口格。
     *
     * <p>【实际显示格数是变长的】：按用户确认的语义，玩家道具槽里有 N 个非空物品就只显示
     * N 个道具格加 1 个语音格，共 N+1 格、整体居中；空槽不再占位。所以 {@code SLOT_COUNT}
     * 只用于「原版快捷栏索引」与「真实槽位」的合法范围校验，不再等于实际渲染格数。
     */
    public static final int SLOT_COUNT = VirtualGadgetBar.SLOT_COUNT + 1;

    /**
     * 桌内道具栏一帧的只读渲染快照。
     *
     * <p>【为什么宽度要和字形一起交出去】：栏现在按实际非空道具数变长，宽度不再是固定常量
     * （满格 198px 只是 N=8 时的特例）。若调用方按常量自己推算宽度，两侧一改一漏就会让整条
     * BossBar 的水平居中漂掉，所以由本服务一次性给出字形与真实宽度，{@code TrickHudView} 只负责居中。
     *
     * @param glyphText     底图 + 图标 + 选框三层字形；每个片段自带 {@code muz_gadget_bar} 字体标签
     * @param advancePixels 整条栏的净前进量 = (visibleSlots + 1) × {@link PackAssets#GADGET_BAR_CELL_ADVANCE}
     * @param visibleSlots  实际显示的道具格数 N（不含固定在末位的语音格）
     */
    public record HudBarSnapshot(String glyphText, int advancePixels, int visibleSlots) {
        public HudBarSnapshot {
            glyphText = glyphText == null ? "" : glyphText;
        }
    }

    /* 预取失败限频窗口：本预取每 40 tick 跑一次，失败可能持续复现，限频既避免刷屏也不静默吞掉。 */
    private static final long FAILURE_LOG_INTERVAL_MILLIS = 10_000L;

    private final DoudizhuPlugin plugin;
    private final VirtualGadgetBarStore store;
    private final TableGadgetService gadgets;
    private final ActionBarOverlayService actionBarOverlay;
    private final CraftEngineOffsetService offsetService;
    private final Consumer<Player> voiceOpener;
    private final Map<UUID, VirtualGadgetBar> bars = new ConcurrentHashMap<>();
    private final Map<UUID, Integer> selectedSlots = new ConcurrentHashMap<>();
    private final Map<UUID, Long> loading = new ConcurrentHashMap<>();
    private final Map<UUID, RefreshState> refreshStates = new ConcurrentHashMap<>();
    /** 已记录过「重画被跳过」告警的「桌名/玩家」键；同一组合只记一次，避免 clearTable 遍历座位时刷屏。 */
    private final Set<String> redrawSkipLogs = ConcurrentHashMap.newKeySet();
    /**
     * 预取失败限频时间戳（毫秒）。
     *
     * <p>预取只在 global 周期任务上写本字段，故用普通 {@code volatile} 字段即可，无需原子类型；
     * 用原始 {@code long} 而非 {@code AtomicLong} 也让空心测试实例（{@code allocateInstance} 不跑构造器）
     * 天然取到默认值 0，首次失败必然放行。
     */
    private volatile long lastPrefetchFailureMillis;
    private MuzScheduler.TaskHandle task;
    private volatile boolean stopped;

    public TableGadgetBarHudService(
        DoudizhuPlugin plugin,
        VirtualGadgetBarStore store,
        TableGadgetService gadgets,
        ActionBarOverlayService actionBarOverlay,
        CraftEngineOffsetService offsetService,
        Consumer<Player> voiceOpener
    ) {
        this.plugin = java.util.Objects.requireNonNull(plugin, "plugin");
        this.store = java.util.Objects.requireNonNull(store, "store");
        this.gadgets = java.util.Objects.requireNonNull(gadgets, "gadgets");
        this.actionBarOverlay = java.util.Objects.requireNonNull(actionBarOverlay, "actionBarOverlay");
        this.offsetService = java.util.Objects.requireNonNull(offsetService, "offsetService");
        this.voiceOpener = java.util.Objects.requireNonNull(voiceOpener, "voiceOpener");
    }

    /**
     * 开局/入座时预取一次的玩家列表。
     *
     * <p>并入 HUD 后本服务不再有每秒派发 ActionBar 的 tick，也就没有「顺手把没加载过的玩家补上」的时机。
     * 所以这里保留一个【低频】预取定时器：只为「当前在打、且本服务还没加载过其道具数据」的玩家触发异步读取。
     * 真正的显示重绘由 {@link TrickHudService} 的周期刷新自然带上。
     */
    public void start() {
        if (stopped) {
            return;
        }
        for (Player player : Bukkit.getOnlinePlayers()) {
            refresh(player.getUniqueId());
        }
        task = plugin.scheduler().runTimer(1L, 40L, this::prefetchActivePlayers);
    }

    /**
     * 为「在打且数据未加载」的玩家补一次异步读取。
     *
     * <p>【这是数据预取，不是渲染】：每秒只处理尚未加载的玩家，已加载的直接跳过，因此稳态下几乎空转。
     * 不再合成任何 ActionBar，也不再读取普通提示叠加。
     *
     * <p>IMPORTANT FOLIA: 本方法是 global 周期任务入口，{@code MuzScheduler.schedule} 在周期回调抛异常
     * 时会 {@code managed.fail(repeating=true)} 并取消后端任务；预取一旦被打死，未加载道具数据的同桌玩家
     * 就再也补不回来。而 {@code TableManager} 的 {@code tables} / {@code playerToTable} 仍是非并发
     * {@code LinkedHashMap}（全局 Map 迁移属既有技术债，不在本轮范围）：其它 lane 的结构性修改与本 lane
     * 并发时，{@code getTables()} 内部的 {@code new ArrayList<>(tables.values())} 就可能抛
     * {@code ConcurrentModificationException} / 越界。所以整个预取体（含快照获取）都要兜底，失败只跳过
     * 本轮并限频记录；同时按 {@code TableGadgetService.tick} 的同口径补上 manager 空值早退。
     */
    private void prefetchActivePlayers() {
        if (stopped) {
            return;
        }
        try {
            TableManager manager = plugin.getTableManager();
            if (manager == null) {
                return;
            }
            for (GameTable table : manager.getTables()) {
                if (table.getPhase() != GamePhase.PLAYING || !gadgets.settings().enabled()) {
                    continue;
                }
                for (UUID playerId : table.getSeats()) {
                    if (table.isBot(playerId)) {
                        continue;
                    }
                    if (!bars.containsKey(playerId)) {
                        refresh(playerId);
                    }
                }
            }
        } catch (RuntimeException failure) {
            reportPrefetchFailure(failure);
        }
    }

    /**
     * 预取失败限频记录：同一原因 10 秒最多一条。
     *
     * <p>与 {@code TableGadgetEffectService.reportFailure} 同口径：既不刷屏，也不静默吞掉异常
     * （项目硬约束）。这条路径被打断时服务端不会有任何其它痕迹，无日志等于「预取链路整体失效」不可查。
     */
    private void reportPrefetchFailure(RuntimeException failure) {
        long now = System.currentTimeMillis();
        if (now - lastPrefetchFailureMillis < FAILURE_LOG_INTERVAL_MILLIS) {
            return;
        }
        lastPrefetchFailureMillis = now;
        plugin.getLogger().warning("读取牌桌列表失败，跳过本轮桌内道具栏预取: " + describeFailure(failure));
    }

    public void refresh(UUID playerId) {
        if (stopped || playerId == null) {
            return;
        }
        RefreshState state = refreshStates.computeIfAbsent(playerId, ignored -> new RefreshState());
        long generation = state.currentGeneration();
        if (loading.putIfAbsent(playerId, generation) != null) {
            return;
        }
        CompletableFuture
            .supplyAsync(() -> store.loadRaw(playerId), ForkJoinPool.commonPool())
            .whenComplete((raw, failure) -> {
                loading.remove(playerId, generation);
                if (failure != null) {
                    plugin.getLogger().warning("读取玩家桌内道具栏失败 " + playerId + ": " + failure.getMessage());
                    return;
                }
                PlayerOutputDispatcher output = actionBarOverlay.outputDispatcher();
                output.runPlayer(playerId, ignored -> {
                    if (!refreshResultAllowed(stopped, state, generation)) {
                        return;
                    }
                    try {
                        bars.put(playerId, store.decodeRaw(raw));
                        normalizeSelection(playerId);
                    } catch (RuntimeException exception) {
                        plugin.getLogger().warning("解码玩家桌内道具栏失败 " + playerId + ": " + exception.getMessage());
                    }
                });
            });
    }

    public int selectedIndex(UUID playerId) {
        return selectedSlots.getOrDefault(playerId, 0);
    }

    public boolean isVoiceSelected(UUID playerId) {
        return selectedIndex(playerId) == VirtualGadgetBar.BUBBLE_SLOT;
    }

    public void select(UUID playerId, int slot) {
        if (playerId == null || slot < 0 || slot >= SLOT_COUNT) {
            return;
        }
        selectedSlots.put(playerId, slot);
        if (slot < VirtualGadgetBar.SLOT_COUNT) {
            ItemStack item = bar(playerId).slot(slot);
            if (item == null) {
                gadgets.clear(playerId);
            } else {
                gadgets.select(playerId, slot, item);
            }
        } else {
            gadgets.clear(playerId);
        }
    }

    /**
     * 桌内道具栏的只读渲染快照，供 {@link TrickHudService} 拼出出牌 HUD 的第四行。
     *
     * <p>返回的字形片段都自带 {@code muz_gadget_bar} 字体标签，调用方直接接进第四行；
     * 该玩家不该显示这道栏（不在 PLAYING、机器人、道具功能关闭、字体偏移层不可用）时返回 {@code null}，
     * 第四行整条不产出，其余三行照旧。
     *
     * <p>【为什么在 Service 侧取字体偏移而不是回退】：并入 BossBar 后栏不再是客户端居中的主体，
     * 但底图/图标/选框三层仍靠 CraftEngine 的负空格叠回同一槽位；偏移不可用时回退会让三层错位，
     * 不如整行不显示。宽度由 {@link #layout} 按真实格数算出，与 TrickHudView 同源。
     */
    public HudBarSnapshot hudBarSnapshot(UUID playerId) {
        if (stopped || playerId == null || !gadgets.settings().enabled()) {
            return null;
        }
        if (!offsetService.isAvailable()) {
            return null;
        }
        // 【为什么先判空 TableManager】：本方法从出牌 HUD 的渲染链上被调用，异常会一路抛到
        // PlayerTaskRegistry，把这个玩家整条 BossBar 的刷新打死（表现是「三行也不动了」）。
        // 而「manager 可能为 null」在本项目是既有共识：TableGadgetService.tick 与本类的
        // prefetchActivePlayers 都有同口径的空值早退。这里对齐，把「查不到桌」收敛成「第四行不产出」。
        TableManager manager = plugin.getTableManager();
        GameTable table = manager == null ? null : manager.getTableOf(playerId);
        if (!barRowVisible(
            stopped,
            gadgets.settings().enabled(),
            offsetService.isAvailable(),
            table != null && table.getPhase() == GamePhase.PLAYING,
            table != null && table.isBot(playerId))) {
            return null;
        }
        return layout(bar(playerId), selectedIndex(playerId), offsetService::offset);
    }

    /**
     * 第四行是否应当产出的唯一判据。
     *
     * <p>门禁条件全部集中在这里，便于逐条验算（也便于把「为什么没显示」讲清楚）：
     * 服务未停止、桌内道具功能开启、CraftEngine 字体偏移层可用、玩家在 PLAYING 阶段的牌桌上、且不是机器人。
     * 任何一条不成立就整条不产出（返回 null），其余三行照旧渲染 —— 这是设计语义，不是缺陷。
     *
     * <p>【注意这里没有「数据已加载」这一条】：道具数据用的是「加载到就用玩家的、没加载到就用
     * {@code VirtualGadgetBar.defaults()}」的兜底，所以数据缺失只会让栏显示默认三件，不会让栏消失；
     * 排查「道具栏完全不显示」时不要往这条上找。
     */
    static boolean barRowVisible(
        boolean stopped,
        boolean gadgetEnabled,
        boolean offsetsAvailable,
        boolean tablePlaying,
        boolean bot
    ) {
        return !stopped && gadgetEnabled && offsetsAvailable && tablePlaying && !bot;
    }

    /**
     * 道具栏纯排版核心：出「N 个非空道具格 + 1 个语音格」的三层字形与整条净前进量。
     *
     * <p>不依赖插件状态、不起任务、不碰 Bukkit 世界（ItemStack 只用来判空与选图标种类），
     * 所以行为测试可以直接驱动它验算格数、叠加顺序与宽度。
     *
     * <p>叠加顺序与旧九格版一致：每格先画底图，再回退一个格宽叠图标（图标自带本格 advance，
     * 因此净前进量不变），选中格再回退一个格宽叠选框。语音格恒在末位且永远存在。
     *
     * @param bar          该玩家的八格快照
     * @param selectedSlot 选中的【真实槽位】（0..7，或 {@link VirtualGadgetBar#BUBBLE_SLOT}）
     * @param offsetProvider 给定位移像素数返回一段负空格文本（CraftEngine 偏移字形）
     */
    static HudBarSnapshot layout(VirtualGadgetBar bar, int selectedSlot, IntFunction<String> offsetProvider) {
        List<Integer> displayed = displayedSlots(bar);
        StringBuilder text = new StringBuilder();
        for (int slot : displayed) {
            appendCell(text, PackAssets.gadgetBarKind(bar.slot(slot)), slot == selectedSlot, offsetProvider);
        }
        appendCell(text, PackAssets.GADGET_BAR_SPEECH,
            selectedSlot == VirtualGadgetBar.BUBBLE_SLOT, offsetProvider);
        int cells = displayed.size() + 1;
        return new HudBarSnapshot(text.toString(), PackAssets.gadgetBarRowAdvance(cells), displayed.size());
    }

    /** 追加一个格子的三层字形；空槽不叠图标，但仍然只前进底图那一格。 */
    private static void appendCell(StringBuilder text, int kind, boolean selected, IntFunction<String> offsetProvider) {
        text.append(PackAssets.gadgetBarBaseGlyphText());
        if (kind != PackAssets.GADGET_BAR_EMPTY) {
            // 图标与底图共用一个 22px 槽位：先回退到底图起点，再让图标自身承担本槽 advance。
            text.append(offsetProvider.apply(-PackAssets.GADGET_BAR_CELL_ADVANCE));
            text.append(PackAssets.gadgetBarIconGlyphText(kind));
        }
        if (selected) {
            // 选框独立叠加，保持底图/图标的净前进量不变。
            text.append(offsetProvider.apply(-PackAssets.GADGET_BAR_CELL_ADVANCE));
            text.append(PackAssets.gadgetBarSelectGlyphText());
        }
    }

    /**
     * 栏里实际显示的道具槽：八格快照中的【非空】槽，按槽位升序（也就是玩家在道具箱里看到的顺序）。
     *
     * <p>空槽不再占位，这是本轮语义变更的核心；语音格不属于本列表，由 {@link #layout} 固定追加在末位。
     */
    static List<Integer> displayedSlots(VirtualGadgetBar bar) {
        List<Integer> slots = new ArrayList<>();
        if (bar == null) {
            return slots;
        }
        for (int slot = 0; slot < VirtualGadgetBar.SLOT_COUNT; slot++) {
            if (bar.slot(slot) != null) {
                slots.add(slot);
            }
        }
        return slots;
    }

    /**
     * 原版快捷栏索引（滚轮/数字键给出的 0..8）映射到九格栏的真实槽位。
     *
     * <p>前 N 个索引按显示顺序命中实际道具槽（N = 当前显示的道具格数），其余索引一律落到语音格。
     * 语音格恒在末位且永远存在，所以「选中索引超出显示范围」有确定归宿：永远能高亮到最后一格，
     * 不会出现「选了个不存在的格子，屏幕上没有任何选框」这种没有反馈的状态。
     */
    static int slotForHotbarIndex(List<Integer> displayed, int hotbarIndex) {
        if (displayed != null && hotbarIndex >= 0 && hotbarIndex < displayed.size()) {
            return displayed.get(hotbarIndex);
        }
        return VirtualGadgetBar.BUBBLE_SLOT;
    }

    /** 用当前快照取该玩家的显示列表；{@link #slotForHotbarIndex} 的实例入口。 */
    private List<Integer> displayedSlots(UUID playerId) {
        return displayedSlots(bar(playerId));
    }

    /**
     * 把选中槽收敛到一个当前【确实显示】的槽位。
     *
     * <p>旧语义是「越界就回 0」，在变长栏下不够用：选中槽位即使还在 0..8 内，
     * 只要它对应的道具已经被移除（空槽不显示），屏幕上就没有任何格子带选框。
     * 现在统一回落到语音格——它恒在末位且永远存在，是唯一一定有对应格子的归宿。
     */
    static int normalizeSelectedSlot(List<Integer> displayed, int selected) {
        if (selected == VirtualGadgetBar.BUBBLE_SLOT) {
            return VirtualGadgetBar.BUBBLE_SLOT;
        }
        return displayed != null && displayed.contains(selected)
            ? selected
            : VirtualGadgetBar.BUBBLE_SLOT;
    }

    private VirtualGadgetBar bar(UUID playerId) {
        return bars.getOrDefault(playerId, VirtualGadgetBar.defaults());
    }

    /** 载入/保存后把选中槽收敛到仍显示的格子上；判定见 {@link #normalizeSelectedSlot}。 */
    private void normalizeSelection(UUID playerId) {
        select(playerId, normalizeSelectedSlot(displayedSlots(playerId), selectedIndex(playerId)));
    }

    /** 右键语音入口由监听器调用，避免把面板实体逻辑塞进本 HUD。 */
    public boolean tryOpenVoice(Player player) {
        if (player == null || !player.isOnline() || !isVoiceSelected(player.getUniqueId())) {
            return false;
        }
        GameTable table = plugin.getTableManager().getTableOf(player);
        if (table == null || table.getPhase() != GamePhase.PLAYING || table.isBot(player.getUniqueId())) {
            return false;
        }
        voiceOpener.accept(player);
        return true;
    }

    @EventHandler
    public void onJoin(PlayerJoinEvent event) {
        UUID playerId = event.getPlayer().getUniqueId();
        invalidateRefresh(playerId);
        refresh(playerId);
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        UUID playerId = event.getPlayer().getUniqueId();
        invalidateRefresh(playerId);
        bars.remove(playerId);
        selectedSlots.remove(playerId);
    }

    @EventHandler
    public void onHeldChange(PlayerItemHeldEvent event) {
        Player player = event.getPlayer();
        GameTable table = plugin.getTableManager().getTableOf(player);
        if (table == null || table.getPhase() != GamePhase.PLAYING || table.isBot(player.getUniqueId())) {
            return;
        }
        event.setCancelled(true);
        // 快捷栏索引按【显示顺序】映射到真实槽位（见 slotForHotbarIndex）：空槽不占位后，
        // 索引 i 对应的就是第 i 个可见道具；超过可见道具数的索引一律落到语音格。
        select(player.getUniqueId(), slotForHotbarIndex(displayedSlots(player.getUniqueId()), event.getNewSlot()));
        // 并入 BossBar 后选槽不会自己触发重绘（本服务已经不派发 ActionBar），
        // 所以这里主动让该玩家的出牌 HUD 立刻重画一次，选框才会跟着移动到新槽位。
        redrawHud(table, player);
    }

    public void onSaved(UUID playerId) {
        if (playerId == null) {
            return;
        }
        invalidateRefresh(playerId);
        bars.remove(playerId);
        selectedSlots.remove(playerId);
        refresh(playerId);
    }

    /**
     * 拆桌、回 LOBBY、停服时收尾。
     *
     * <p>【不再清 ActionBar】：并入 BossBar 后九格栏不在 ActionBar 上，而 ActionBar 现在只承载普通
     * 状态提示；往这里发空 ActionBar 会把别人正在显示的状态提示一并擦掉。栏本身随第四行自然隐藏即可。
     * 这里丢掉该桌座位的道具数据缓存，避免换桌后沿用上一桌的选中槽。
     */
    public void clearTable(GameTable table) {
        if (table == null) {
            return;
        }
        for (UUID playerId : table.getSeats()) {
            bars.remove(playerId);
            selectedSlots.remove(playerId);
        }
        // 主动让仍在线的同桌玩家重画一次 HUD。注意回 LOBBY 路径上本方法在切阶段之前调用，
        // 此时重画的是默认九格栏，要等阶段切到 LOBBY 后的下一次刷新才整条隐藏；关桌路径则立即消失。
        // redrawHud 内部已自行投递到 player lane 与桌 owner lane，这里不再额外包一层 runTableNow。
        for (UUID playerId : table.getSeats()) {
            redrawHud(table, playerId);
        }
    }

    /**
     * 让某玩家的出牌 HUD 立刻重画一次（选槽、拆桌收尾时用）。
     *
     * <p>复用 {@link GameTable} 的既有 HUD 刷新入口，与每秒的周期刷新同一条路径，避免另开一套渲染。
     */
    private void redrawHud(GameTable table, Player player) {
        if (table != null && player != null) {
            redrawHud(table, player.getUniqueId());
        }
    }

    private void redrawHud(GameTable table, UUID playerId) {
        if (table == null || playerId == null) {
            return;
        }
        // 牌桌已经注销时无需重画，也不能投递：TableManager.runTableNow 对未注册桌会抛
        // IllegalArgumentException（“牌桌尚未注册”）。这条路径在实服真的发生过——拆桌/回 LOBBY
        // 与选槽回调交错时，闭包进入 player lane 时桌已被注销，异常从 PlayerTaskRegistry.dispatch
        // 逃到调度器，在日志里刷成 Entity task 异常。这里先做一次注册检查，把绝大多数情况挡在投递之前。
        if (!isTableRegistered(table)) {
            return;
        }
        PlayerOutputDispatcher output = actionBarOverlay.outputDispatcher();
        output.runPlayer(playerId, player -> {
            try {
                plugin.getTableManager().runTableNow(table, () -> {
                    if (plugin.getTableManager().getTableOf(player) == table) {
                        table.refreshTrickHudFor(player);
                    }
                });
            } catch (RuntimeException exception) {
                // 注册检查与投递之间仍可能被注销（TOCTOU），runTableNow 此时会抛 IllegalArgumentException；
                // 同一链上还可能抛 IllegalStateException（后端返回已终止任务）或关闭期的 IllegalPluginAccessException。
                // 这些都只意味着本次重画无需进行；但【绝不能让异常逃到调度器】，
                // 否则它会打挂玩家任务/周期任务，后续重画彻底失效。
                reportRedrawSkipped(table, playerId, exception);
            }
        });
    }

    /** 牌桌是否仍登记在 TableManager 上（同名且同一实例）；注销或换实例后返回 false。 */
    private boolean isTableRegistered(GameTable table) {
        TableManager tables = plugin.getTableManager();
        return tables != null && table.getName() != null && tables.getTable(table.getName()) == table;
    }

    /**
     * 记录一次「按设计放弃」的重画跳过。
     *
     * <p>【为什么不能完全无声】：这条路径是「桌在投递与执行之间被拆掉」的正常竞态，本身无需处理；
     * 但它同时也是「本服务的重画链路已经整体打不通」的唯一信号——例如球员任务后端持续返回已终止句柄、
     * 或关闭期注册被拒。旧写法整段空 catch，实服表现是「选框不跟着动、九格栏不再更新」而日志毫无痕迹，
     * 排查时只能靠猜，这与桌内道具投掷那次「静默返回 false 导致无日志」是同一类问题。
     * 所以这里按【桌 + 玩家】限频记录：既留下可排查的线索，又不会在 clearTable 遍历座位时逐人刷屏。
     */
    private void reportRedrawSkipped(GameTable table, UUID playerId, RuntimeException exception) {
        String key = table.getName() + "/" + playerId;
        if (!redrawSkipLogs.add(key)) {
            return;
        }
        plugin.getLogger().warning("跳过桌内道具栏重画 " + key + "（牌桌可能已注销或调度器已关闭）: "
            + describeFailure(exception));
    }

    /**
     * 把异常描述成可排查的文本。
     *
     * <p>与 {@code CraftEngineFurnitureService.describeFailure} 同口径：包装异常的 {@code getMessage()}
     * 常为 null，直接拼接只会得到 “: null”。message 为空时退回到异常类名。
     */
    private static String describeFailure(Throwable exception) {
        String message = exception.getMessage();
        return message == null || message.isBlank() ? exception.getClass().getName() : message;
    }

    private void invalidateRefresh(UUID playerId) {
        refreshStates.computeIfAbsent(playerId, ignored -> new RefreshState()).invalidate();
        loading.remove(playerId);
    }

    static boolean refreshResultAllowed(boolean serviceStopped, RefreshState state, long generation) {
        return !serviceStopped && state != null && state.matches(generation);
    }

    static final class RefreshState {
        private final AtomicLong generation = new AtomicLong();

        long currentGeneration() {
            return generation.get();
        }

        void invalidate() {
            generation.incrementAndGet();
        }

        boolean matches(long expectedGeneration) {
            return generation.get() == expectedGeneration;
        }
    }

    public void shutdown() {
        stopped = true;
        if (plugin.getTableManager() != null) {
            for (GameTable table : plugin.getTableManager().getTables()) {
                clearTable(table);
            }
        }
        if (task != null) {
            task.cancel();
            task = null;
        }
        bars.clear();
        selectedSlots.clear();
        loading.clear();
        refreshStates.clear();
    }
}
