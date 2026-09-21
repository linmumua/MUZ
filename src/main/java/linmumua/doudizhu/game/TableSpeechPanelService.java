package linmumua.doudizhu.game;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.function.LongSupplier;
import linmumua.doudizhu.compat.VersionCompat;
import linmumua.doudizhu.world.DisplayPanelPickGeometry;
import net.kyori.adventure.text.Component;
import org.bukkit.Bukkit;
import org.bukkit.FluidCollisionMode;
import org.bukkit.Location;
import org.bukkit.entity.Display;
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
    private final OcclusionTester occlusionTester;
    private final LongSupplier tickSource;
    private final Map<UUID, OwnerSession> owners = new HashMap<>();
    private final Map<UUID, Integer> ownerEpochs = new HashMap<>();
    private final Map<UUID, Long> lastActionTicks = new HashMap<>();
    private Config config;
    private long tick;
    private boolean stopped;

    /**
     * 以 Bukkit 世界方块射线为默认遮挡判断的构造器。
     */
    public TableSpeechPanelService(
        Plugin plugin,
        Config config,
        EntryProvider entryProvider,
        TableProvider tableProvider,
        ActionHandler actionHandler
    ) {
        this(plugin, config, entryProvider, tableProvider, actionHandler,
            TableSpeechPanelService::traceBlocks, () -> Bukkit.getCurrentTick());
    }

    /** 可注入遮挡和时钟的构造器，供离线测试或其他世界实现使用。 */
    public TableSpeechPanelService(
        Plugin plugin,
        Config config,
        EntryProvider entryProvider,
        TableProvider tableProvider,
        ActionHandler actionHandler,
        OcclusionTester occlusionTester,
        LongSupplier tickSource
    ) {
        this.plugin = Objects.requireNonNull(plugin, "plugin");
        this.config = Objects.requireNonNull(config, "config");
        this.entryProvider = Objects.requireNonNull(entryProvider, "entryProvider");
        this.tableProvider = Objects.requireNonNull(tableProvider, "tableProvider");
        this.actionHandler = Objects.requireNonNull(actionHandler, "actionHandler");
        this.occlusionTester = Objects.requireNonNull(occlusionTester, "occlusionTester");
        this.tickSource = Objects.requireNonNull(tickSource, "tickSource");
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
     * 每 tick 调用；命中扫描严格每 2 tick 执行一次。期间同步清理阶段、座位和 epoch 失效会话。
     */
    public void tick() {
        if (stopped) {
            return;
        }
        tick = tickSource.getAsLong();
        if (!config.enabled()) {
            clearAll();
            return;
        }
        for (UUID ownerId : new ArrayList<>(owners.keySet())) {
            OwnerSession session = owners.get(ownerId);
            if (session == null || !isSessionValid(session)) {
                clearPlayer(ownerId);
                continue;
            }
            if (Math.floorMod(tick, hoverIntervalTicks()) != 0) {
                continue;
            }
            Player owner = Bukkit.getPlayer(ownerId);
            Pick pick = pick(owner, session);
            String nextId = pick == null ? null : pick.entry().id();
            if (!Objects.equals(nextId, session.hoveredId)) {
                session.hoveredId = nextId;
                applyHover(session, nextId != null);
            }
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
        clearAll();
        stopped = true;
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

    private void removePanels(OwnerSession session) {
        for (PanelView view : session.panels) {
            if (view.display != null && view.display.isValid()) {
                view.display.remove();
            }
        }
        session.panels.clear();
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
}
