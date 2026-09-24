package linmumua.doudizhu.game;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.LongSupplier;
import java.util.logging.Level;
import linmumua.doudizhu.compat.VersionCompat;
import linmumua.doudizhu.world.DisplayPanelPickGeometry;
import net.kyori.adventure.text.Component;
import org.bukkit.Bukkit;
import org.bukkit.FluidCollisionMode;
import org.bukkit.Location;
import org.bukkit.entity.Display;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.entity.TextDisplay;
import org.bukkit.plugin.Plugin;
import org.bukkit.util.RayTraceResult;
import org.bukkit.util.Transformation;
import org.bukkit.util.Vector;
import org.joml.AxisAngle4f;
import org.joml.Vector3f;

/**
 * 牌桌语音催促面板服务。
 *
 * <p>服务刻意不接入入口类或世界监听器：调用方只需要在既有生命周期里转发
 * {@link #open(GameTable, UUID)}、{@link #tick()} 和 {@link #tryHandleRightClick(Player)}。
 * 面板是按 owner 分配的私有 TextDisplay，拾取则统一走
 * {@link DisplayPanelPickGeometry}，因此 hover 与右键不会各自维护一套命中规则。
 */
public final class TableSpeechPanelService {
    private static final int DEFAULT_HOVER_INTERVAL_TICKS = 2;
    private static final double DEFAULT_MAX_DISTANCE = 6.0;
    private static final double PANEL_BASE_WIDTH = 4.0 / 40.0;
    private static final double PANEL_BASE_HEIGHT = 9.0 / 40.0;

    private final Plugin plugin;
    private final EntryProvider entryProvider;
    private final TableProvider tableProvider;
    private final ActionHandler actionHandler;
    private final PlayerOutputDispatcher output;
    private final OcclusionTester occlusionTester;
    private final LongSupplier tickSource;
    // 面板实体 lane 归属与删除的可注入接缝。生产路径用 BukkitPanelEntityLane（直接调 Bukkit）；测试可注入
    // 替身，指定「当前线程不拥有该实体」并让删除投递失败。见 PanelEntityLane。
    private final PanelEntityLane lane;
    // FOLIA: 这三个表会被 global 扫描线程与多个 player lane 并发访问（open 在 player lane，
    // tick 的派发在 global lane），因此必须是并发容器，不能再用 HashMap。
    private final Map<UUID, OwnerSession> owners = new ConcurrentHashMap<>();
    private final Map<UUID, Integer> ownerEpochs = new ConcurrentHashMap<>();
    private final Map<UUID, Long> lastActionTicks = new ConcurrentHashMap<>();
    private Config config;
    // 原先这里是 long tick 字段。派发改造后它只在 tickOwner 内部「写入后立即读取」，
    // 变成一个跨 player lane 共享的可变字段反而引入数据竞争，因此下沉为方法内局部变量。
    private boolean stopped;

    /**
     * 以 Bukkit 世界方块射线为默认遮挡判断的构造器。
     */
    public TableSpeechPanelService(
        Plugin plugin,
        Config config,
        EntryProvider entryProvider,
        TableProvider tableProvider,
        ActionHandler actionHandler,
        PlayerOutputDispatcher output
    ) {
        this(plugin, config, entryProvider, tableProvider, actionHandler, output,
            TableSpeechPanelService::traceBlocks, () -> Bukkit.getCurrentTick());
    }

    /** 可注入遮挡和时钟的构造器，供离线测试或其他世界实现使用。 */
    public TableSpeechPanelService(
        Plugin plugin,
        Config config,
        EntryProvider entryProvider,
        TableProvider tableProvider,
        ActionHandler actionHandler,
        PlayerOutputDispatcher output,
        OcclusionTester occlusionTester,
        LongSupplier tickSource
    ) {
        this(plugin, config, entryProvider, tableProvider, actionHandler, output, occlusionTester, tickSource, null);
    }

    /**
     * 包内可见：额外注入面板实体 lane 判定，供行为测试指定「当前线程不拥有该实体」等场景。
     *
     * <p>{@code lane} 为 null 时使用默认的 {@link BukkitPanelEntityLane}（生产路径行为不变）。写法与
     * {@code TableGadgetEffectService(DoudizhuPlugin, EffectRuntime)} 一致。
     */
    TableSpeechPanelService(
        Plugin plugin,
        Config config,
        EntryProvider entryProvider,
        TableProvider tableProvider,
        ActionHandler actionHandler,
        PlayerOutputDispatcher output,
        OcclusionTester occlusionTester,
        LongSupplier tickSource,
        PanelEntityLane lane
    ) {
        this.plugin = Objects.requireNonNull(plugin, "plugin");
        this.config = Objects.requireNonNull(config, "config");
        this.entryProvider = Objects.requireNonNull(entryProvider, "entryProvider");
        this.tableProvider = Objects.requireNonNull(tableProvider, "tableProvider");
        this.actionHandler = Objects.requireNonNull(actionHandler, "actionHandler");
        this.output = Objects.requireNonNull(output, "output");
        this.occlusionTester = Objects.requireNonNull(occlusionTester, "occlusionTester");
        // IMPORTANT FOLIA: 默认时钟是 Bukkit.getCurrentTick()，它只在「存在 ticking region」的
        // 线程上合法。global lane 调用会抛 IllegalStateException("No currently ticking region")
        // ——实服验收已复现。因此 tick() 只做派发，真正读取时钟的动作发生在 player lane 内。
        this.tickSource = Objects.requireNonNull(tickSource, "tickSource");
        // 默认使用生产 lane：面板实体的删除会先判归属，跨 region 时投回实体自己的 region。
        this.lane = lane != null ? lane : new BukkitPanelEntityLane(this.plugin);
    }

    /** 兼容批量入口：为当前桌每位在线真人分别创建私有面板。 */
    public void open(GameTable table) {
        if (stopped || table == null) {
            return;
        }
        for (UUID ownerId : table.getSeats()) {
            open(table, ownerId);
        }
    }

    /** 只为点击气泡的 owner 创建私有语音面板，不影响同桌其他玩家。 */
    public void open(GameTable table, UUID ownerId) {
        if (stopped || table == null || ownerId == null) {
            return;
        }
        clearPlayer(ownerId);
        if (!phaseAllowed(table.getPhase(), config) || !table.contains(ownerId) || table.isBot(ownerId)) {
            return;
        }
        Player owner = Bukkit.getPlayer(ownerId);
        if (owner == null || !owner.isOnline()) {
            return;
        }
        List<SpeechEntry> entries = safeEntries(table, ownerId);
        if (entries.isEmpty()) {
            return;
        }
        OwnerSession session = new OwnerSession(table, ownerId, nextOwnerEpoch(ownerId));
        for (SpeechEntry entry : entries) {
            if (entry == null || !entry.enabled() || entry.panel() == null) {
                continue;
            }
            TextDisplay display = spawnPanel(owner, entry);
            if (display != null) {
                session.panels.add(new PanelView(entry, display));
            }
        }
        if (!session.panels.isEmpty()) {
            owners.put(ownerId, session);
        }
    }

    /**
     * 每 tick 调用。本方法只负责派发，不碰实体、不读世界、不取当前 tick。
     *
     * <p>IMPORTANT FOLIA: 它由 global lane 驱动，而 global lane 没有 ticking region：
     * {@code Bukkit.getCurrentTick()} 会抛 {@code IllegalStateException("No currently ticking
     * region")}（实服验收已复现），{@code getEyeLocation}／{@code rayTraceBlocks}／
     * {@code setGlowing}／{@code remove} 这些实体与世界访问也必须发生在 region owner lane。
     * 因此每个 owner 的实际工作统一投递到该玩家的 player lane——与同伴服务
     * {@code TableGadgetBarHudService} 同形（global 扫描 + runPlayer 投递）。
     */
    public void tick() {
        if (stopped) {
            return;
        }
        for (UUID ownerId : new ArrayList<>(owners.keySet())) {
            OwnerSession session = owners.get(ownerId);
            if (session == null) {
                continue;
            }
            if (output.currentPlayer(ownerId) == null) {
                // 玩家已不可解析（离线）：他的 player lane 不会再执行，而 global lane 同样无权
                // 操作面板实体，所以这里既不派发也不直接删实体——会话留给关桌 clearTable 收口。
                // 代价：玩家离线且桌子仍开着时，这份会话要等关桌或重连才失效（已计入待验收项）。
                continue;
            }
            output.runPlayer(ownerId, player -> tickOwner(player, ownerId));
        }
    }

    /** player lane 内的单 owner 工作：会话校验、命中扫描与 hover 同步。 */
    private void tickOwner(Player player, UUID ownerId) {
        if (stopped) {
            return;
        }
        OwnerSession session = owners.get(ownerId);
        if (session == null) {
            return;
        }
        if (!config.enabled() || player == null || !player.isOnline() || !isSessionValid(session)) {
            clearPlayer(ownerId);
            return;
        }
        // player lane 归属于某个 ticking region，getCurrentTick 在这里才合法。
        long now = tickSource.getAsLong();
        if (Math.floorMod(now, hoverIntervalTicks()) != 0) {
            return;
        }
        Pick pick = pick(player, session);
        String nextId = pick == null ? null : pick.entry().id();
        if (!Objects.equals(nextId, session.hoveredId)) {
            session.hoveredId = nextId;
            applyHover(session, nextId != null);
        }
    }

    /**
     * 尝试消费一次右键。返回 true 表示该玩家当前视线落在本服务面板上，
     * 即使语音条目处于冷却也应由调用方阻止原版交互。
     */
    public boolean tryHandleRightClick(Player player) {
        if (stopped || player == null || !config.enabled()) {
            return false;
        }
        OwnerSession session = owners.get(player.getUniqueId());
        if (session == null || !isSessionValid(session)) {
            if (session != null) {
                clearPlayer(player.getUniqueId());
            }
            return false;
        }
        long now = tickSource.getAsLong();
        if (session.lastClickTick == now) {
            return session.lastClickConsumed;
        }
        session.lastClickTick = now;
        Pick pick = pick(player, session);
        session.lastClickConsumed = pick != null;
        if (pick == null) {
            return false;
        }
        String pickedId = pick.entry().id();
        if (!Objects.equals(session.hoveredId, pickedId)) {
            session.hoveredId = pickedId;
            applyHover(session, true);
            return true;
        }
        Long lastAction = lastActionTicks.get(session.ownerId);
        if (lastAction != null && now - lastAction < Math.max(1, config.voiceCooldownTicks())) {
            return true;
        }
        // 催促目标永远从桌面当前回合读取，不缓存旧目标，避免轮转后点到过期玩家。
        UUID targetId = session.table.getCurrentTurn();
        actionHandler.execute(session.table, session.ownerId, targetId, pick.entry());
        lastActionTicks.put(session.ownerId, now);
        return true;
    }

    /** 清理某个 owner 的所有私有面板和 hover/click 状态。 */
    public void clearPlayer(UUID playerId) {
        if (playerId == null) {
            return;
        }
        nextOwnerEpoch(playerId);
        OwnerSession session = owners.remove(playerId);
        if (session != null) {
            removePanels(session);
        }
    }

    /** 清理整桌，并让该桌已有 owner 会话立即失效。 */
    public void clearTable(GameTable table) {
        if (table == null) {
            return;
        }
        for (UUID ownerId : new ArrayList<>(owners.keySet())) {
            OwnerSession session = owners.get(ownerId);
            if (session != null && session.table == table) {
                clearPlayer(ownerId);
            }
        }
    }

    /** 替换配置并关闭旧面板；玩家再次点击气泡时按新配置重建。 */
    public void reload(Config config) {
        this.config = Objects.requireNonNull(config, "config");
        clearAll();
    }

    /** 关闭服务并移除所有私有实体。 */
    public void shutdown() {
        if (stopped) {
            return;
        }
        // 先封新入口：无论清理是否成功，关闭语义都必须落地。旧实现把 stopped = true 放在 clearAll() 之后，
        // 一旦清理抛异常，stopped 永远为 false——服务仍在接收 open/tick，且异常会沿 onDisable 链路外溢。
        // 清理本身现在已逐张面板自兜底（见 removePanels），这里再补上顺序保证。
        stopped = true;
        clearAll();
    }

    public Config config() {
        return config;
    }

    public int ownerCount() {
        return owners.size();
    }

    /** 供测试和外部协调器复用的阶段门。 */
    public static boolean phaseAllowed(GamePhase phase, Config config) {
        return phase != null && config != null && config.enabled() && config.allowedPhase(phase);
    }

    private int hoverIntervalTicks() {
        return Math.max(1, config.hoverIntervalTicks());
    }

    private boolean isSessionValid(OwnerSession session) {
        return session.table != null
            && session.table.contains(session.ownerId)
            && !session.table.isBot(session.ownerId)
            && phaseAllowed(session.table.getPhase(), config)
            && ownerEpochs.getOrDefault(session.ownerId, -1) == session.epoch
            && playerOnline(session.ownerId);
    }

    private boolean playerOnline(UUID playerId) {
        Player player = Bukkit.getPlayer(playerId);
        return player != null && player.isOnline();
    }

    private List<SpeechEntry> safeEntries(GameTable table, UUID ownerId) {
        List<SpeechEntry> entries = entryProvider.entries(table, ownerId);
        return entries == null ? List.of() : List.copyOf(entries);
    }

    private TextDisplay spawnPanel(Player owner, SpeechEntry entry) {
        Panel panel = entry.panel();
        Location location = panel.center().clone();
        location.setYaw(panel.yaw());
        location.setPitch(0.0f);
        if (location.getWorld() == null || owner.getWorld() == null || !location.getWorld().equals(owner.getWorld())) {
            return null;
        }
        return VersionCompat.spawnEntity(location.getWorld(), location, TextDisplay.class, display -> {
            display.setVisibleByDefault(false);
            display.text(entry.text() == null ? Component.text(" ") : entry.text());
            display.setBillboard(Display.Billboard.FIXED);
            display.setDefaultBackground(true);
            display.setAlignment(TextDisplay.TextAlignment.CENTER);
            display.setShadowed(false);
            display.setSeeThrough(false);
            display.setTextOpacity((byte) 255);
            display.setBrightness(new Display.Brightness(15, 15));
            display.setViewRange((float) Math.max(8.0, config.maxDistance() + 2.0));
            // panel.width/height 只描述视觉盒与命中范围；文字保持固定比例，不能因面板宽度变化被横向拉伸。
            // 统一使用行高对应的文本缩放，命中矩形仍由 Panel.width/height 提供，避免显示与交互几何脱节。
            float textScale = (float) (panel.height() / PANEL_BASE_HEIGHT);
            display.setTransformation(new Transformation(
                new Vector3f(), new AxisAngle4f(), new Vector3f(textScale, textScale, 1.0f), new AxisAngle4f()));
            owner.showEntity(plugin, display);
        });
    }

    private Pick pick(Player player, OwnerSession session) {
        if (player == null || session == null || !isSessionValid(session)) {
            return null;
        }
        Location eye = player.getEyeLocation();
        Vector direction = eye.getDirection();
        double maxDistance = Math.max(0.01, config.maxDistance());
        double blockDistance = occlusionTester.firstBlockDistance(player, maxDistance);
        Pick best = null;
        for (PanelView view : session.panels) {
            SpeechEntry entry = view.entry;
            if (!entry.enabled() || entry.panel() == null) {
                continue;
            }
            DisplayPanelPickGeometry.Rectangle rectangle = entry.panel().rectangle();
            DisplayPanelPickGeometry.Hit hit = DisplayPanelPickGeometry.intersect(
                eye, direction, rectangle, maxDistance);
            if (hit == null || DisplayPanelPickGeometry.occluded(hit, blockDistance)) {
                continue;
            }
            Pick candidate = new Pick(entry, hit);
            if (best == null || hit.distance() < best.hit().distance()) {
                best = candidate;
            }
        }
        return best;
    }

    private void applyHover(OwnerSession session, boolean hovered) {
        for (PanelView view : session.panels) {
            TextDisplay display = view.display;
            if (display == null || !display.isValid()) {
                continue;
            }
            // 这里只保留实体状态的统一出口；具体颜色/文本仍由条目 API 决定。
            display.setGlowing(hovered && Objects.equals(view.entry.id(), session.hoveredId));
        }
    }

    /**
     * 删除一个 owner 的全部面板实体。
     *
     * <p>逐张面板自兜底：单张面板的删除异常不得中断整轮清理，也不得逃出 {@code onDisable} 链路
     * （关闭期 MuzScheduler 后端与 Paper 调度器都会拒绝注册，跨 region 删除也会抛异常，都属预期失败）。
     * 状态已在 {@link #clearPlayer(UUID)} 里先行摘除，因此这里失败只会留痕、不会让条目残留。
     */
    private void removePanels(OwnerSession session) {
        for (PanelView view : session.panels) {
            try {
                removePanelEntity(view);
            } catch (RuntimeException failure) {
                // 留痕而不是静默吞掉：删不掉又没有日志正是实服最难排查的一类问题。
                reportFailure("语音面板实体删除失败", failure);
            }
        }
        session.panels.clear();
    }

    /**
     * 删除单个面板实体。
     *
     * <p>IMPORTANT FOLIA: 面板实体只能在它所属 region 的线程上删除。清理入口可能跑在玩家 lane（玩家可能
     * 已走远、离开面板所在 region）、桌 owner lane，或 {@code onDisable} 的主线程（global，没有 region）
     * 上；在错误 lane 上直接 {@code remove} 会抛
     * {@code Accessing entity state off owning region's thread}。因此这里先判归属：本 region 直接删，
     * 否则把删除投回实体自己的 region。投不出去（世界已卸载、调度拒绝等）时留痕日志，绝不静默。
     */
    private void removePanelEntity(PanelView view) {
        TextDisplay display = view.display;
        if (display == null) {
            return;
        }
        // 关服分支：区域线程可能已不可用，实体随世界保存销毁，只清追踪、绝不在非法 owner 上操作实体
        //（与 PhysicalTableManager / MahjongTableManager 的 shutdown 分支同口径）。
        if (plugin.getServer().isStopping()) {
            return;
        }
        if (lane.isOwnedByCurrentRegion(display)) {
            if (display.isValid()) {
                lane.removeNow(display);
            }
            return;
        }
        Location anchor = view.entry.panel().center();
        boolean dispatched = lane.removeOnOwnerRegion(anchor, () -> {
            // 这段在实体所属 region 内执行：此时 isValid/remove 都合法。
            if (display.isValid()) {
                lane.removeNow(display);
            }
        });
        if (!dispatched) {
            reportFailure("语音面板实体不在当前 region 且删除任务无法提交，已跳过删除", null);
        }
    }

    /** 记录一次面板清理失败。失败可能发生在 onDisable 链路上，必须留痕且不得外抛（见调用点）。 */
    private void reportFailure(String message, Throwable failure) {
        plugin.getLogger().log(Level.WARNING, message + "。", failure);
    }

    private void clearAll() {
        for (UUID ownerId : new ArrayList<>(owners.keySet())) {
            clearPlayer(ownerId);
        }
        ownerEpochs.clear();
    }

    private int nextOwnerEpoch(UUID ownerId) {
        int next = ownerEpochs.getOrDefault(ownerId, 0) + 1;
        ownerEpochs.put(ownerId, next);
        return next;
    }

    private static double traceBlocks(Player player, double maxDistance) {
        if (player == null || player.getWorld() == null) {
            return Double.POSITIVE_INFINITY;
        }
        RayTraceResult result = player.getWorld().rayTraceBlocks(
            player.getEyeLocation(), player.getEyeLocation().getDirection(), maxDistance,
            FluidCollisionMode.NEVER, true);
        return result == null ? Double.POSITIVE_INFINITY
            : result.getHitPosition().distance(player.getEyeLocation().toVector());
    }

    private static final class OwnerSession {
        private final GameTable table;
        private final UUID ownerId;
        private final int epoch;
        private final List<PanelView> panels = new ArrayList<>();
        private String hoveredId;
        private long lastClickTick = Long.MIN_VALUE;
        private boolean lastClickConsumed;

        private OwnerSession(GameTable table, UUID ownerId, int epoch) {
            this.table = table;
            this.ownerId = ownerId;
            this.epoch = epoch;
        }
    }

    private static final class PanelView {
        private final SpeechEntry entry;
        private final TextDisplay display;

        private PanelView(SpeechEntry entry, TextDisplay display) {
            this.entry = entry;
            this.display = display;
        }
    }

    private record Pick(SpeechEntry entry, DisplayPanelPickGeometry.Hit hit) {
    }

    /** 运行期可注入的语音面板配置；不承担 YAML 读写。 */
    public interface Config {
        boolean enabled();

        default int hoverIntervalTicks() {
            return DEFAULT_HOVER_INTERVAL_TICKS;
        }

        default double maxDistance() {
            return DEFAULT_MAX_DISTANCE;
        }

        default int voiceCooldownTicks() {
            return 20;
        }

        default boolean allowedPhase(GamePhase phase) {
            return phase == GamePhase.PLAYING;
        }
    }

    /** 一个可直接用于测试或外部配置桥接的不可变配置。 */
    public record Settings(
        boolean enabled,
        int hoverIntervalTicks,
        double maxDistance,
        int voiceCooldownTicks,
        Set<GamePhase> phases
    ) implements Config {
        public Settings {
            hoverIntervalTicks = Math.max(1, hoverIntervalTicks);
            maxDistance = Double.isFinite(maxDistance) && maxDistance > 0.0 ? maxDistance : DEFAULT_MAX_DISTANCE;
            voiceCooldownTicks = Math.max(1, voiceCooldownTicks);
            phases = phases == null ? Set.of(GamePhase.PLAYING) : Set.copyOf(phases);
        }

        public Settings(boolean enabled, int hoverIntervalTicks, double maxDistance, Set<GamePhase> phases) {
            this(enabled, hoverIntervalTicks, maxDistance, 20, phases);
        }

        public Settings(boolean enabled) {
            this(enabled, DEFAULT_HOVER_INTERVAL_TICKS, DEFAULT_MAX_DISTANCE, 20, Set.of(GamePhase.PLAYING));
        }

        @Override
        public boolean allowedPhase(GamePhase phase) {
            return phases.contains(phase);
        }
    }

    /** 语音条目由配置/资源桥接提供，面板几何与文案均不写死在服务里。 */
    public record SpeechEntry(String id, Component text, Panel panel, boolean enabled) {
        public SpeechEntry {
            if (id == null || id.isBlank()) {
                throw new IllegalArgumentException("语音条目 id 不能为空");
            }
            Objects.requireNonNull(panel, "panel");
        }

        public SpeechEntry(String id, Component text, Panel panel) {
            this(id, text, panel, true);
        }
    }

    /** 面板世界位置与可点击矩形。 */
    public record Panel(Location center, double width, double height, float yaw) {
        public Panel {
            Objects.requireNonNull(center, "center");
            if (!Double.isFinite(width) || width <= 0.0
                || !Double.isFinite(height) || height <= 0.0) {
                throw new IllegalArgumentException("面板位置和尺寸无效");
            }
        }

        public DisplayPanelPickGeometry.Rectangle rectangle() {
            return DisplayPanelPickGeometry.vertical(center, width, height, yaw);
        }
    }

    @FunctionalInterface
    public interface EntryProvider {
        List<SpeechEntry> entries(GameTable table, UUID ownerId);
    }

    @FunctionalInterface
    public interface TableProvider {
        Collection<GameTable> tables();
    }

    @FunctionalInterface
    public interface ActionHandler {
        void execute(GameTable table, UUID ownerId, UUID targetId, SpeechEntry entry);
    }

    @FunctionalInterface
    public interface OcclusionTester {
        double firstBlockDistance(Player player, double maxDistance);
    }

    /**
     * 面板实体 lane 归属与删除的可注入接缝。
     *
     * <p>【为什么需要这一层】面板实体在哪个 region 生成、由谁删除，只有持有该 region 的线程才能安全操作；
     * 而清理入口（{@code clearPlayer}/{@code clearTable}/{@code clearAll}/{@code shutdown}）可能跑在玩家
     * lane、桌 owner lane，或 {@code onDisable} 的主线程上。{@code Bukkit.isOwnedByCurrentRegion} 与实体
     * {@code remove} 都是静态/实体调用，单测里没有运行中的服务端可控制，因此把判定与删除收口到接口后面，
     * 让行为测试能指定「当前线程不拥有该实体」并让删除投递失败。写法与
     * {@code PhysicalTableManager.WorldBodyLane} / {@code TableGadgetEffectService.EffectRuntime} 一致。
     */
    interface PanelEntityLane {
        /** 当前线程是否拥有该实体所在 region；实体为 null 或读取失败一律按「不拥有」处理（安全侧）。 */
        boolean isOwnedByCurrentRegion(Entity entity);

        /** 在 owner lane 内直接删除实体；调用方必须先确认归属。 */
        void removeNow(Entity entity);

        /**
         * 把一次性删除投到该实体所属 region（按面板中心 {@link Location} 定位）。
         *
         * @return true 表示删除任务已成功提交；false 表示无法提交（世界未加载、调度拒绝等），调用方必须留痕。
         */
        boolean removeOnOwnerRegion(Location anchor, Runnable removal);
    }

    /** 生产边界：以 {@code RegionScheduler} 把删除投到锚点所在 region。 */
    private static final class BukkitPanelEntityLane implements PanelEntityLane {
        private final Plugin plugin;

        private BukkitPanelEntityLane(Plugin plugin) {
            this.plugin = plugin;
        }

        // 门禁自身绝不抛异常：Location.getWorld() 在世界已卸载时会抛，读取失败一律按「不拥有」处理
        //（安全侧：宁可把删除投回去，也不在非法 lane 上直接删）。
        @Override
        public boolean isOwnedByCurrentRegion(Entity entity) {
            if (entity == null) {
                return false;
            }
            try {
                return Bukkit.isOwnedByCurrentRegion(entity);
            } catch (RuntimeException failure) {
                return false;
            }
        }

        @Override
        public void removeNow(Entity entity) {
            entity.remove();
        }

        @Override
        public boolean removeOnOwnerRegion(Location anchor, Runnable removal) {
            try {
                if (anchor == null || anchor.getWorld() == null || plugin.getServer() == null) {
                    return false;
                }
                // 与 MuzScheduler 的 region lane 后端同一 API（Folia 语义：任务由该坐标的 region 拥有）。
                plugin.getServer().getRegionScheduler().run(plugin, anchor, task -> removal.run());
                return true;
            } catch (RuntimeException failure) {
                // 关闭期插件已禁用、Paper 调度器拒绝注册；投递失败按 false 交回调用方留痕，绝不外抛。
                return false;
            }
        }
    }
}
