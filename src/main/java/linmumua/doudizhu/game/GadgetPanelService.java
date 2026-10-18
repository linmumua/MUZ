package linmumua.doudizhu.game;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;
import linmumua.doudizhu.DoudizhuPlugin;
import linmumua.doudizhu.compat.MuMenuGadgetBridge;
import linmumua.doudizhu.config.ChairSidePanelSettings;
import linmumua.doudizhu.ui.VirtualGadgetBar;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;

/**
 * 道具面板的**命令门面**：把 {@code /muz gadget [panel|edit|select <1..8>|voice]} 的每一步都
 * 收口到「资格校验 → 执行 → 可读提示」，让命令层保持极薄。
 *
 * <p>【它做什么】校验玩家是否在 PLAYING 阶段的同桌在线真人（非机器人、非跨桌），再调用
 * {@link TableGadgetBarHudService} 的选择/语音接缝，必要时经 {@link MuMenuGadgetBridge} 开关 MuMenu 面板。
 * {@code edit} 不在这里：它交给 Dialog 侧的编辑屏幕（同一份选择/保存接缝，见下方 {@link #OK} 文档）。
 *
 * <p>【为什么单独一层】命令是「玩家可手动敲的」入口，必须给明确提示而不是静默失败；把校验与提示
 * 集中在这里，命令层（core 独占）只负责参数解析与把 {@link #hint(Status)} 发给玩家。
 *
 * <p>【不做什么】不重写选择逻辑（复用 {@link TableGadgetBarHudService#select(UUID, int)}）、不读背包实体、
 * 不碰资源包。真正的显示重绘与语音面板打开都在既有的 HUD/语音服务里。
 *
 * <p>【用户索引 vs 内部索引】命令与菜单一律用**用户 1-based**（1..8），内部真实槽 **0-based**（0..7）；
 * 两者只在本类转换一次。选择**永远传真实槽**（重复道具、稀疏槽位都正确，绝不按道具类型匹配）。
 */
public class GadgetPanelService {
    /** 一次命令调用的结果；命令层据此给玩家提示。 */
    public enum Status {
        /** 成功。select 成功时 MuMenu 面板已被请求关闭（面板点击选完即关，随后用准星右键投掷）。 */
        OK,
        /** 桌内道具功能被配置关闭。 */
        DISABLED,
        /** MuMenu 不可用（未安装 / 服务未发布 / API 不兼容）——明确提示，不退旧箱子 GUI。 */
        MENU_UNAVAILABLE,
        /** PlaceholderAPI 未就绪，面板里的动态标签无法解析。 */
        PLACEHOLDER_UNAVAILABLE,
        /** 玩家当前不在任何牌桌上。 */
        NOT_IN_TABLE,
        /** 在牌桌上但不在对局阶段（大厅/结算等）。 */
        NOT_PLAYING,
        /** 目标座位是机器人。 */
        BOT,
        /** 面板属于另一张牌桌（玩家已换桌），拒绝并提示重开面板。 */
        CROSS_TABLE,
        /** 指定的真实槽位是空的，无法选中。 */
        EMPTY_SLOT,
        /** 用户索引越界（不在 1..8 内）。 */
        OUT_OF_RANGE,
        /** 语音面板打不开（不在合法 lane 或不满足打开条件）。 */
        VOICE_UNAVAILABLE,
        /** 椅侧面板的椅子锚点快照还没拍（不在座位上，或尚未在桌 owner lane 采集）。 */
        ANCHORS_NOT_READY,
        /** 椅侧面板打开失败（画布比例与 1.5×1 不符、菜单不存在、权限不足等）。已回滚，不会留下半块面板。 */
        PANEL_OPEN_FAILED
    }

    private final DoudizhuPlugin plugin;
    private final TableGadgetBarHudService bar;
    private final TableGadgetService gadgets;
    private final Consumer<Player> voiceOpener;
    private final MuMenuGadgetBridge bridge;

    /** 椅侧面板的椅子锚点冻结快照；只在桌 owner lane 写，任何 lane 只读。 */
    private final ChairPanelAnchorService chairAnchors;

    /**
     * 椅侧双面板的**内置渲染器**（MUZ 自己画，不依赖 MuMenu）。
     *
     * <p>未注入时椅侧面板不可用（{@code PANEL_OPEN_FAILED}），但道具栏命令与
     * {@code /muz gadget panel} 的 MuMenu 路径照常工作——两者是独立的可选能力。
     */
    private volatile ChairPanelDisplayService chairPanelDisplay;

    /** 装配期注入内置渲染器；可在任意线程调用（volatile 写）。 */
    public void applyChairPanelDisplay(ChairPanelDisplayService display) {
        this.chairPanelDisplay = display;
    }

    /**
     * 椅侧面板布局（世界格），由配置层下发的共享设置覆盖。
     *
     * <p>未下发时用 {@link ChairSidePanelSettings#defaults()}（1.5 × 1.0 方块）。
     * 这里刻意<b>不</b>自带第二套数值：尺寸与左右偏移的唯一定义处是配置层的
     * {@code chair-side-panels} 段。用 {@code volatile} 是因为 {@code /muz reload}
     * 会在配置线程写、player lane 读。
     */
    private volatile ChairSidePanelSettings chairSettings = ChairSidePanelSettings.defaults();

    /**
     * 面板打开时记录的牌桌名（玩家 UUID → 桌名），用于识别「跨桌」。
     *
     * <p>面板是给某张牌桌的玩家开的；玩家离开/换桌后仍在面板上点选属于旧桌的槽位时必须拒绝。
     * 只记录**打开成功**的那张桌；没有记录时不判跨桌（例如编辑屏幕自己调选择接缝）。
     */
    private final Map<UUID, String> panelTableNames = new ConcurrentHashMap<>();

    public GadgetPanelService(
        DoudizhuPlugin plugin,
        TableGadgetBarHudService bar,
        TableGadgetService gadgets,
        Consumer<Player> voiceOpener
    ) {
        this(plugin, bar, gadgets, voiceOpener, new MuMenuGadgetBridge(plugin));
    }

    /** 测试用构造器：注入替身桥，其余装配与生产一致。 */
    GadgetPanelService(
        DoudizhuPlugin plugin,
        TableGadgetBarHudService bar,
        TableGadgetService gadgets,
        Consumer<Player> voiceOpener,
        MuMenuGadgetBridge bridge
    ) {
        this(plugin, bar, gadgets, voiceOpener, bridge, null);
    }

    /**
     * 完整构造器：可注入椅子锚点快照服务（测试用替身，或装配期共享同一份快照）。
     *
     * @param chairAnchors 为 null 时本类自建一份（等价于只服务本类的面板开关）
     */
    GadgetPanelService(
        DoudizhuPlugin plugin,
        TableGadgetBarHudService bar,
        TableGadgetService gadgets,
        Consumer<Player> voiceOpener,
        MuMenuGadgetBridge bridge,
        ChairPanelAnchorService chairAnchors
    ) {
        this.plugin = java.util.Objects.requireNonNull(plugin, "plugin");
        this.bar = java.util.Objects.requireNonNull(bar, "bar");
        this.gadgets = java.util.Objects.requireNonNull(gadgets, "gadgets");
        this.voiceOpener = java.util.Objects.requireNonNull(voiceOpener, "voiceOpener");
        this.bridge = java.util.Objects.requireNonNull(bridge, "bridge");
        this.chairAnchors = chairAnchors != null ? chairAnchors : new ChairPanelAnchorService(plugin);
    }

    /** 椅子锚点快照服务（装配期/生命周期收口需要与其它服务共享时取用）。 */
    public ChairPanelAnchorService chairAnchors() {
        return chairAnchors;
    }

    /**
     * 由配置层下发共享布局设置（1.5 × 1.0 方块等）。
     *
     * <p>配置层是这套数值的唯一来源；本类不自带任何配置键，避免出现「配置一套、代码一套」。
     */
    public void applyChairSettings(ChairSidePanelSettings settings) {
        this.chairSettings = settings == null ? ChairSidePanelSettings.defaults() : settings;
        // 设置变了→已应用的锚点不再代表「正确位置」，全部作废，让下一次同步重新应用。
        appliedAnchors.clear();
    }

    /** 当前生效的椅侧面板设置（只读）。 */
    public ChairSidePanelSettings chairSettings() {
        return chairSettings;
    }

    /**
     * {@code /muz gadget panel}：打开位置测试面板。
     *
     * <p>IMPORTANT LANE: 必须在 player lane 调用（MuMenu 的 surface/open 都要求拥有该玩家 region 的线程）。
     */
    public Status openPanel(Player player) {
        // 1.10.64：面板改由 MUZ 内置渲染（{@link ChairPanelDisplayService}），
        // 不再要求服务端安装 MuMenu，也没有外部画布宽高比约束。
        // 语义与 {@link #openChairPanels} 完全一致：打开椅子左右两块板。
        return openChairPanels(player);
    }

    /**
     * {@code /muz gadget select <1..8>}：选中一个真实道具槽。
     *
     * <p>用户 1-based → 内部 0-based，**传真实槽**（重复/稀疏都对）。空槽、越界、非对局、机器人、跨桌
     * 一律拒绝并给出可读提示（空槽必须明确告知，而不是静默选了个不存在的格）。
     *
     * <p>IMPORTANT LANE: 必须在 player lane 调用。
     */
    public Status selectSlot(Player player, int userSlot) {
        Status bounds = slotBoundsStatus(userSlot);
        if (bounds != Status.OK) {
            return bounds;
        }
        Status eligibility = eligibility(player);
        if (eligibility != Status.OK) {
            return eligibility;
        }
        Status crossTable = crossTable(player);
        if (crossTable != Status.OK) {
            return crossTable;
        }
        UUID playerId = player.getUniqueId();
        int realSlot = userSlot - 1;
        if (bar.visibleBar(playerId).slot(realSlot) == null) {
            return Status.EMPTY_SLOT;
        }
        bar.select(playerId, realSlot);
        // 选择成功先关面板：面板点完即关，随后用准星右键投掷（避免面板继续吃掉右键）。
        bridge.closeSession(player);
        if (chairPanelDisplay != null) {
            chairPanelDisplay.close(playerId);
        }
        return Status.OK;
    }

    /**
     * {@code /muz gadget voice}：进入语音入口。
     *
     * <p>先关闭 MuMenu 会话（面板不能让位给语音实体面板），再在合法 lane 打开语音面板。
     *
     * <p>IMPORTANT LANE: 必须在 player lane 调用。
     */
    public Status openVoice(Player player) {
        Status eligibility = eligibility(player);
        if (eligibility != Status.OK) {
            return eligibility;
        }
        Status crossTable = crossTable(player);
        if (crossTable != Status.OK) {
            return crossTable;
        }
        // 语音面板是独立实体面板，必须先让悬浮菜单与椅侧双面板退出，避免两套捕获面同时吃掉输入。
        bridge.closeSession(player);
        if (chairPanelDisplay != null) {
            chairPanelDisplay.close(player.getUniqueId());
        }
        return bar.openVoicePanel(player) ? Status.OK : Status.VOICE_UNAVAILABLE;
    }

    /**
     * 打开**椅侧左右两块世界锚定面板**（{@code chair-left} / {@code chair-right}）。
     *
     * <p>【与 {@link #openPanel} 的区别】{@code openPanel} 走的是 MuMenu 既有的
     * 「近眼屏面」路径：一个玩家同时只有一面、朝向随视线变。这里走的是世界锚定面板：
     * 左右各一块、位置与朝向来自椅子局部坐标的冻结快照、与玩家看向无关。
     *
     * <p>【两块要么都开、要么都不开】MuMenu 按 {@code panelId} 单块打开，所以这里必须自己保证
     * 原子性：任一块失败就 {@code closePanels} 回滚并返回失败状态，绝不给玩家留「只有左半边」
     * 这种半开状态（那比不开更糟：他会以为右面板丢了）。
     *
     * <p>【锚点必须事先拍好】快照由 {@link ChairPanelAnchorService#capture} 在**桌 owner lane**
     * 产生；本方法在 player lane 只读这份不可变快照，不跨 lane 读牌桌放置索引。
     *
     * <p>IMPORTANT LANE: 必须在 player lane 调用。
     */
    public Status openChairPanels(Player player) {
        Status eligibility = eligibility(player);
        if (eligibility != Status.OK) {
            return eligibility;
        }
        Status crossTable = crossTable(player);
        if (crossTable != Status.OK) {
            return crossTable;
        }
        ChairPanelDisplayService display = chairPanelDisplay;
        if (display == null) {
            return Status.PANEL_OPEN_FAILED;
        }
        // 每次开面板都按当前配置重拍一次：配置可能在 /muz reload 后变过，冻结旧几何会摆错位置。
        java.util.List<ChairPanelAnchor> anchors =
            chairAnchors.capture(player.getUniqueId(), chairSettings);
        if (anchors.isEmpty()) {
            return Status.ANCHORS_NOT_READY;
        }
        if (!display.open(player, anchors, chairSlots(player.getUniqueId()), chairSelected(player.getUniqueId()))) {
            return Status.PANEL_OPEN_FAILED;
        }
        panelTableNames.put(player.getUniqueId(), tableNameOf(player));
        return Status.OK;
    }

    /**
     * 按最新的冻结快照就地刷新两块椅侧面板的位置与朝向（Dialog 改完几何后应用）。
     *
     * <p>只用 {@code updatePanelAnchor}，不重建会话：重建会丢点击态与动画，且会闪一下。
     * 面板尚未打开时返回 false（由调用方决定是否改走 {@link #openChairPanels}）。
     *
     * <p>IMPORTANT LANE: 必须在 player lane 调用。
     */
    public boolean refreshChairPanels(Player player) {
        if (player == null) {
            return false;
        }
        java.util.List<ChairPanelAnchor> anchors = chairAnchors.frozen(player.getUniqueId());
        if (anchors.isEmpty()) {
            return false;
        }
        ChairPanelDisplayService display = chairPanelDisplay;
        if (display == null) {
            return false;
        }
        // 内置渲染按「先拆后建」重建：Dialog 保存是低频操作，重建能保证几何与图标一次到位。
        return display.open(player, anchors, chairSlots(player.getUniqueId()), chairSelected(player.getUniqueId()));
    }

    /**
     * 关闭该玩家的椅侧面板（离桌/断线/关桌/替换时收口）。
     *
     * <p>只关锚定面板，不影响普通菜单/应用会话——玩家可能正在用别的 MuMenu 界面。
     *
     * <p>IMPORTANT LANE: 必须在 player lane 调用。
     */
    public void closeChairPanels(Player player) {
        if (player == null) {
            return;
        }
        // 内置渲染只拥有 MUZ 自己的两块板，不触碰别的插件的世界体。
        ChairPanelDisplayService display = chairPanelDisplay;
        if (display != null) {
            display.close(player.getUniqueId());
        }
        panelTableNames.remove(player.getUniqueId());
        // 已应用的锚点随之作废：否则下次 sync 会误判「位置没变」而不重开。
        appliedAnchors.remove(player.getUniqueId());
    }

    /** 八个槽位的物品快照（0-based，空槽为 {@code null}），供内置渲染逐个画图标。 */
    private List<org.bukkit.inventory.ItemStack> chairSlots(UUID playerId) {
        return bar.visibleBar(playerId).items();
    }

    /**
     * 当前选中格（0-based；{@code 8} 表示语音格）。
     *
     * <p>这里刻意直接复用道具栏的 {@code selectedIndex}：内置面板九宫格的最后一路就是语音，
     * 与 {@code VirtualGadgetBar.BUBBLE_SLOT} 同一个值，因此不需要第二套选中态映射。
     */
    private int chairSelected(UUID playerId) {
        return bar.selectedIndex(playerId);
    }

    /**
     * 任意 lane 的终态清理：缓存立即失效，MuMenu 调用只投递到 player lane。
     *
     * <p>迟到的旧桌清理不得关掉玩家刚在新桌打开的面板；新的成功打开会重新登记桌名，
     * 因此执行时有新登记就让位。已离线玩家的实体由 MuMenu 的退出收口负责。
     */
    public void clearPlayer(UUID playerId) {
        if (playerId == null) {
            return;
        }
        discardPlayer(playerId);
        // 内置面板自带 lane 处理（实体删除投递到锚点 region），因此不依赖 player lane 投递器：
        // 玩家可能已经离线，那时再排队只会丢掉这次清理。
        if (chairPanelDisplay != null) {
            chairPanelDisplay.close(playerId);
        }
    }

    /** 桌 owner 在清空座位之前调用；getSeats 返回 UUID 快照，不跨 lane 读取玩家实体。 */
    public void clearTable(GameTable table) {
        if (table != null) {
            for (UUID playerId : table.getSeats()) {
                clearPlayer(playerId);
            }
        }
    }

    /**
     * 玩家 UUID → 上一次**真正应用成功**的面板锚点。
     *
     * <p>用途：判断「已开着的面板是不是已经落在正确位置」。只看 {@code panelIds} 是否齐全是不够的——
     * 玩家换桌后锚点会变，面板会安静地停在上一张桌的位置。用锚点相等性做快速路径，
     * 才能真正做到「位置没变就不动、变了就更新」。
     */
    private final Map<UUID, java.util.List<ChairPanelAnchor>> appliedAnchors = new ConcurrentHashMap<>();

    /**
     * 玩家 UUID → 该玩家 player lane 的投递器；由装配方注入。
     *
     * <p>签名刻意与 {@code PlayerOutputDispatcher.runPlayer(UUID, Consumer<Player>)} 对齐，
     * 装配方直接传 {@code output::runPlayer} 即可。本类不持有 dispatcher：它是命令门面，
     * 链路上游能拿到 dispatcher，由上游注入比自己穿透 {@code DoudizhuPlugin} 取私有字段干净。
     * 未注入时 {@link #repaintAfterLayoutSaved} 只记日志、不跨 lane 硬调 MuMenu。
     */
    private volatile java.util.function.BiConsumer<UUID, Consumer<Player>> laneDispatcher;

    /**
     * 注入「把任务投递到该玩家 player lane」的能力。
     *
     * @param dispatcher 接收 (玩家 UUID, lane 内任务) 并在其 owner region 上执行；null 表示不接线
     */
    public void applyLaneDispatcher(java.util.function.BiConsumer<UUID, Consumer<Player>> dispatcher) {
        this.laneDispatcher = dispatcher;
    }

    /**
     * 布局保存成功后的**重绘入口**（供 Dialog 布局编辑注入为 {@code Consumer<ChairSidePanelSettings>}）。
     *
     * <p>【为什么是 Consumer 而不是"设置并刷新"两个方法】Dialog 那侧在保存成功的完成回调里直接
     * {@code repaint.accept(newSettings)}；把「记下新设置 + 重算快照 + 刷新已开面板」收成一个原子入口，
     * 调用方就不会漏掉其中一步，也不会出现「配置已改、面板还是旧位置」的中间态。
     *
     * <p>【线程：任意线程都可调用】它通常在配置保存的 IO 回调里被触发（不是 player lane）。
     * 因此这里**不**假设线程：先原子地换掉设置（任何 lane 都安全），再把「重算 + 刷新」逐个玩家
     * 投递到各自的 player lane（MuMenu 的玩家级 API 只能在玩家 owner region 上调用）。
     * 投递失败只记日志、不抛——配置已经落盘成功，重绘失败不该让保存流程看起来失败。
     *
     * <p>【失败不 apply 的语义由调用方保证】Dialog 那侧只在保存成功时调用本方法；
     * 本方法自身不做「回滚配置」。
     */
    public void repaintAfterLayoutSaved(ChairSidePanelSettings settings) {
        if (settings == null) {
            return;
        }
        // 先落设置：即便下面的投递失败，后续新开的面板也用新布局，不会退回旧值。
        applyChairSettings(settings);
        TableManager manager = plugin.getTableManager();
        if (manager == null) {
            return;
        }
        java.util.Collection<GameTable> tables = manager.getTables();
        if (tables == null) {
            return;
        }
        for (GameTable table : java.util.List.copyOf(tables)) {
            if (table == null) {
                continue;
            }
            // 只刷新「当前在这张桌上且面板开着」的真人：其余玩家下次开面板自然会用新布局。
            for (UUID playerId : java.util.List.copyOf(table.getSeats())) {
                if (playerId == null || table.isBot(playerId)) {
                    continue;
                }
                refreshOnLane(playerId);
            }
        }
    }

    /**
     * 把一次「重算快照 + 刷新面板」投递到该玩家的 player lane；投递失败只记日志。
     *
     * <p>【为什么用注入的投递器】布局保存的回调在 IO 线程，MuMenu 的玩家级 API 只能在玩家
     * owner region 上调用。本类不持有 {@code PlayerOutputDispatcher}（它由装配方持有），
     * 所以投递方式由构造时注入：装配方传 {@code output::runPlayer} 即可。
     * 未注入时**不猜线程**：直接记一条日志说明重绘未接线，而不是在 IO 线程上硬调 MuMenu。
     */
    private void refreshOnLane(UUID playerId) {
        if (laneDispatcher == null) {
            plugin.getLogger().warning(
                "椅侧面板布局已保存，但未注入 player lane 投递器，本次未重绘已开面板（下次开面板会用新布局）");
            return;
        }
        if (!plugin.isEnabled() || plugin.isShuttingDown()) {
            return;
        }
        try {
            laneDispatcher.accept(playerId, this::repaintOnLane);
        } catch (RuntimeException failure) {
            // 配置已经落盘成功，重绘失败不该让保存流程看起来失败。
            plugin.getLogger().warning("椅侧面板重绘投递失败: " + failure);
        }
    }

    /**
     * 重绘的**lane 内**部分：按最新设置重算快照，并只对「本来就开着」的面板做更新。
     *
     * <p>IMPORTANT LANE: 必须在 player lane 调用（它会调 MuMenu 玩家级 API）。
     *
     * <p>【为什么不主动开】布局保存不是玩家要求打开面板的动作。若玩家此刻没开面板，
     * 我们只更新快照，等他下次开面板自然用新布局；否则会出现「改个偏移，界面自己弹出来」。
     * 反过来，若新布局让面板不产出（例如 enabled=false），则把已开的收口掉。
     *
     * @return 是否做了可见变更（true = 更新或关闭了面板）；供测试断言
     */
    boolean repaintOnLane(Player player) {
        if (player == null || !player.isOnline()) {
            return false;
        }
        UUID playerId = player.getUniqueId();
        java.util.List<ChairPanelAnchor> anchors = chairAnchors.capture(playerId, chairSettings);
        if (anchors.isEmpty()) {
            if (bridge.panelIds(player).isEmpty()) {
                return false;
            }
            closeChairPanels(player);
            return true;
        }
        if (bridge.panelIds(player).isEmpty()) {
            return false;
        }
        refreshChairPanels(player);
        return true;
    }

    /**
     * 同步时应采取的**动作**（纯判据的返回值）。
     *
     * <p>抽成枚举 + 纯函数，是为了让「什么情况下该动、什么情况下不该动」可以被逐条验算：
     * 这里最容易出的错是「看到两块都开着就不动」，于是玩家换桌后面板安静地停在上一个桌的位置。
     */
    enum ChairSyncAction {
        /** 两块都开着、且锚点与上次应用的一致 → 什么都不做。 */
        NO_CHANGE,
        /** 两块都开着但锚点变了 → 就地更新（不重建会话）。 */
        UPDATE_IN_PLACE,
        /** 面板不全或根本没有 → 整组重开。 */
        REOPEN
    }

    /**
     * 同步动作纯判据。
     *
     * @param bothOpen     两块椅侧面板当前是否都在
     * @param anchorsMatch 本次算出的锚点是否与上次**应用成功**的锚点完全相同
     */
    static ChairSyncAction decideSyncAction(boolean bothOpen, boolean anchorsMatch) {
        if (!bothOpen) {
            return ChairSyncAction.REOPEN;
        }
        return anchorsMatch ? ChairSyncAction.NO_CHANGE : ChairSyncAction.UPDATE_IN_PLACE;
    }

    /**
     * **运行期同步**：按当前对局状态，把该玩家椅侧面板收敛到「该开就开、该关就关」。
     *
     * <p>【为什么做成幂等的单一入口】用户要求面板「持续出现时机沿现有对局/座位边界」，
     * 而离桌、断线、关桌、换桌、阶段切换这些边界分散在多个 owner 的文件里。若每个边界各写一套
     * 开/关判断，迟早会漂移出「某个边界忘了关」。所以这里只暴露一个判定入口：
     * 装配方在任意边界调用它一次即可，**重复调用安全**（已在正确状态就什么都不做）。
     *
     * <p>判定链（与 {@link #eligibility} 同口径，但把「无桌/非对局」视为**应当关闭**而不是错误）：
     * <ol>
     *   <li>不在线 / 不在桌 / 非 PLAYING / 是机器人 / 道具功能关闭 → 收起面板（幂等）；</li>
     *   <li>MuMenu 不支持锚定面板 → 收起（不留半块）；</li>
     *   <li>布局未启用 → 收起；</li>
     *   <li>锚点快照拍不出来（未放置/不在座）→ 收起；</li>
     *   <li>否则：两块都开且都在 → 什么都不做；否则按当前快照整组重开（保证「要么都没有，要么两块都在」）。</li>
     * </ol>
     *
     * <p>IMPORTANT LANE: 必须在 player lane 调用。
     *
     * @return 本次是否真的改变了面板状态（true = 开了或关了）；供测试与调用方判断是否需要提示
     */
    public boolean syncChairPanels(Player player) {
        if (player == null || !player.isOnline()) {
            return false;
        }
        UUID playerId = player.getUniqueId();
        if (shouldHideChairPanels(player) || !bridge.supportsAnchoredPanels()) {
            return closeChairPanelsIfPresent(player);
        }
        // 换桌是**合法**的状态变化，不是「跨桌违规」：先丢掉旧桌记录，避免 openChairPanels 里的
        // crossTable 判据把这次同步判成违规、白白空转一个周期（面板要等下一次同步才回来）。
        // 真正的跨桌违规仍由「面板开着、玩家已换桌」时的手动点击路径拦截（那里会提示重开面板）。
        forget(playerId);
        // 每次同步都按最新配置重拍快照：/muz reload 改过布局后，下一次同步就会用新几何。
        java.util.List<ChairPanelAnchor> anchors = chairAnchors.capture(playerId, chairSettings);
        if (anchors.isEmpty()) {
            return closeChairPanelsIfPresent(player);
        }
        java.util.Set<String> open = chairPanelDisplay == null
            ? java.util.Set.of()
            : chairPanelDisplay.openPanelIds(playerId);
        boolean bothOpen = open.contains(ChairPanelGeometry.PANEL_ID_LEFT)
            && open.contains(ChairPanelGeometry.PANEL_ID_RIGHT)
            && open.size() == 2;
        // 「已开着」不等于「位置正确」：玩家换桌后锚点会变，若只看是否已开，面板会停在上一个桌的位置。
        // 所以必须比对本次算出的锚点与上次真正应用成功的锚点。
        ChairSyncAction action = decideSyncAction(bothOpen, anchors.equals(appliedAnchors.get(playerId)));
        if (action == ChairSyncAction.NO_CHANGE) {
            return false;
        }
        if (action == ChairSyncAction.UPDATE_IN_PLACE) {
            // 已开但锚点变了 → 就地更新，不重建会话（避免闪一下、丢点击态）。
            if (refreshChairPanels(player)) {
                appliedAnchors.put(playerId, anchors);
                return true;
            }
            // 更新失败（面板可能刚被 MuMenu 收掉）→ 落到下面整组重开。
        }
        // 数量不对（0 / 1 / 混入别人的面板）或锚点更新失败 → 整组重开，绝不留半块。
        return switch (openChairPanels(player)) {
            case OK -> {
                appliedAnchors.put(playerId, anchors);
                yield true;
            }
            default -> {
                appliedAnchors.remove(playerId);
                closeChairPanelsIfPresent(player);
                yield true;
            }
        };
    }

    /**
     * 该玩家此刻是否**应该**没有椅侧面板（纯判据，便于逐条验算）。
     *
     * <p>与 {@link #eligibility} 的区别：那条给命令用、要区分「不在桌」与「非对局」等失败原因；
     * 这条只回答「该不该显示面板」，因此把多种「不该显示」合并成一个布尔。
     */
    public boolean shouldHideChairPanels(Player player) {
        if (player == null || !player.isOnline()) {
            return true;
        }
        TableManager manager = plugin.getTableManager();
        GameTable table = manager == null ? null : manager.getTableOf(player);
        boolean playing = table != null
            && table.getPhase() == GamePhase.PLAYING
            && !table.isBot(player.getUniqueId());
        return shouldHideChairPanels(
            true, chairSettings.enabled(), gadgets.settings().enabled(), table != null, playing);
    }

    /**
     * 「该不该隐藏椅侧面板」的纯判据（便于逐条验算）。
     *
     * <p>条件依次是：玩家在线、椅侧面板总开关、道具功能开关、有桌、在对局阶段且非机器人。
     * 任何一条不成立都应当隐藏；全部成立才显示。顺序刻意与 {@link #eligibilityStatus} 不同：
     * 这里在线排在第一位，因为离线的玩家既没有桌也不该被当成「非对局」。
     */
    static boolean shouldHideChairPanels(
        boolean online,
        boolean chairPanelsEnabled,
        boolean gadgetEnabled,
        boolean tablePresent,
        boolean playingAndHuman
    ) {
        if (!online) {
            return true;
        }
        if (!chairPanelsEnabled || !gadgetEnabled) {
            return true;
        }
        return !tablePresent || !playingAndHuman;
    }

    /** 只在确实有面板可关时才调 closePanels，避免对每个边界都白跑一次反射调用。 */
    private boolean closeChairPanelsIfPresent(Player player) {
        if (bridge.panelIds(player).isEmpty() && !chairAnchors.hasSnapshot(player.getUniqueId())) {
            return false;
        }
        closeChairPanels(player);
        return true;
    }

    /**
     * 玩家离开牌桌/断线时的**终态收口**：任何 lane 安全。
     *
     * <p>与 {@link #closeChairPanels} 的分工：那个要在 player lane 上真去关 MuMenu 面板；
     * 这个是「人已经不在了」的兜底——先丢弃本类持有的全部玩家态（跨桌记录、椅子锚点快照），
     * 保证下一位坐这个位置的人不会继承上一位的坐标。MuMenu 侧的面板由 owner 收口
     * （玩家断线时 MuMenu 自己会关；MuZ 不能对离线玩家调玩家级 API）。
     */
    public void discardPlayer(UUID playerId) {
        if (playerId == null) {
            return;
        }
        panelTableNames.remove(playerId);
        appliedAnchors.remove(playerId);
        chairAnchors.forget(playerId);
    }

    /**
     * 玩家换桌/离桌时清理跨桌记录（命令层或生命周期可调用）。
     */
    public void forget(UUID playerId) {
        if (playerId != null) {
            panelTableNames.remove(playerId);
            // 锚点快照同样作废：离桌后椅位不再属于这个玩家，留着只会在下次入座时给出旧坐标。
            chairAnchors.forget(playerId);
        }
    }

    /** 停机清理。 */
    public void shutdown() {
        panelTableNames.clear();
        chairAnchors.shutdown();
    }

    /** 结果对应的玩家提示文本。 */
    public String hint(Status status) {
        return switch (status) {
            case OK -> "已选中。";
            case DISABLED -> "桌内道具功能已关闭。";
            case MENU_UNAVAILABLE -> "道具面板不可用（需安装 MuMenu 且版本兼容），未打开面板。";
            case PLACEHOLDER_UNAVAILABLE -> "道具面板需要 PlaceholderAPI 才能显示槽位状态。";
            case NOT_IN_TABLE -> "你当前不在牌桌上。";
            case NOT_PLAYING -> "只有对局进行中才能操作道具栏。";
            case BOT -> "机器人不能操作道具栏。";
            case CROSS_TABLE -> "这个面板属于另一张牌桌，请重新打开道具面板。";
            case EMPTY_SLOT -> "这个槽位是空的，先在道具箱里放入道具。";
            case OUT_OF_RANGE -> "槽位编号只能是 1 到 8。";
            case VOICE_UNAVAILABLE -> "语音面板暂时打不开。";
            case ANCHORS_NOT_READY -> "面板位置还没准备好，请坐下后再试。";
            case PANEL_OPEN_FAILED -> "面板打不开（面板尺寸与菜单画布比例不一致时会被拒绝），请把这个问题报告给管理员。";
        };
    }

    /** 资格判据（在线 + 道具开启 + 在桌 + PLAYING + 非机器人）。 */
    private Status eligibility(Player player) {
        if (player == null || !player.isOnline()) {
            return Status.NOT_IN_TABLE;
        }
        if (!gadgets.settings().enabled()) {
            return Status.DISABLED;
        }
        TableManager manager = plugin.getTableManager();
        GameTable table = manager == null ? null : manager.getTableOf(player);
        if (table == null) {
            return Status.NOT_IN_TABLE;
        }
        return eligibilityStatus(true, true, true, table.getPhase() == GamePhase.PLAYING,
            table.isBot(player.getUniqueId()));
    }

    /**
     * 资格纯判据（便于逐条验算）。
     *
     * <p>条件分别是：玩家在线、道具功能开启、有桌、桌在对局阶段、不是机器人。任何一条不成立都给出
     * 对应的拒绝状态；全部成立才返回 {@link Status#OK}。
     */
    static Status eligibilityStatus(
        boolean online,
        boolean gadgetEnabled,
        boolean tablePresent,
        boolean playing,
        boolean bot
    ) {
        if (!online || !tablePresent) {
            return Status.NOT_IN_TABLE;
        }
        if (!gadgetEnabled) {
            return Status.DISABLED;
        }
        if (!playing) {
            return Status.NOT_PLAYING;
        }
        return bot ? Status.BOT : Status.OK;
    }

    /**
     * 用户索引纯判据：只在 1..{@link VirtualGadgetBar#SLOT_COUNT} 内合法。
     *
     * <p>越界必须在**任何**桌面/玩家查询之前拒绝，所以独立成纯函数（也给「用户 1-based」这条约定一个
     * 可执行定义，避免散落各处各写一遍边界）。
     */
    static Status slotBoundsStatus(int userSlot) {
        return userSlot >= 1 && userSlot <= VirtualGadgetBar.SLOT_COUNT ? Status.OK : Status.OUT_OF_RANGE;
    }

    /**
     * 跨桌纯判据：面板记录过桌名、且与玩家当前桌不同（含当前不在桌）时拒绝。
     *
     * <p>没有记录（例如编辑屏幕自己调选择接缝）时不判跨桌，返回 {@link Status#OK}。
     */
    static Status crossTableStatus(String recordedTable, String currentTable) {
        if (recordedTable == null) {
            return Status.OK;
        }
        return recordedTable.equals(currentTable) ? Status.OK : Status.CROSS_TABLE;
    }

    /** 跨桌判定：面板记录的桌存在且与玩家当前桌不同时拒绝。 */
    private Status crossTable(Player player) {
        String recorded = panelTableNames.get(player.getUniqueId());
        Status status = crossTableStatus(recorded, tableNameOf(player));
        if (status != Status.OK) {
            panelTableNames.remove(player.getUniqueId());
        }
        return status;
    }

    private String tableNameOf(Player player) {
        TableManager manager = plugin.getTableManager();
        GameTable table = manager == null ? null : manager.getTableOf(player);
        return table == null ? null : table.getName();
    }

    /** PlaceholderAPI 是否已启用（面板里的动态标签依赖它）。 */
    private boolean placeholderReady() {
        return Bukkit.getPluginManager().isPluginEnabled("PlaceholderAPI");
    }
}
