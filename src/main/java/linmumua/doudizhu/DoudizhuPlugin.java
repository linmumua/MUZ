package linmumua.doudizhu;

import linmumua.doudizhu.ai.AiChatGateway;
import linmumua.doudizhu.ai.OpenAiCompatibleAiChatGateway;

import linmumua.doudizhu.assets.PlayerHeadRenderer;
import linmumua.doudizhu.compat.CraftEngineBundleExporter;
import linmumua.doudizhu.compat.CraftEngineFurnitureService;
import linmumua.doudizhu.compat.CraftEngineOffsetService;
import linmumua.doudizhu.compat.VaultEconomyBridge;
import linmumua.doudizhu.command.DoudizhuCommand;
import linmumua.doudizhu.config.MuzYamlConfig;
import linmumua.doudizhu.game.GameTable;
import linmumua.doudizhu.game.GamePhase;
import linmumua.doudizhu.game.PhysicalChipService;
import linmumua.doudizhu.game.PlayerOutputDispatcher;
import linmumua.doudizhu.game.ActionBarOverlayService;
import linmumua.doudizhu.debug.DebugHudConfigController;
import linmumua.doudizhu.debug.DebugWebServer;
import linmumua.doudizhu.debug.GadgetPreviewSnapshotService;
import linmumua.doudizhu.debug.HudResourceRecoveryService;
import linmumua.doudizhu.debug.HudWebApplyCoordinator;
import linmumua.doudizhu.game.HotbarHudService;
import linmumua.doudizhu.game.HudOverlayRuntimeState;
import linmumua.doudizhu.game.TableGadgetService;
import linmumua.doudizhu.game.TableGadgetSettings;
import linmumua.doudizhu.game.TableGadgetEffectService;
import linmumua.doudizhu.game.TableGadgetBarHudService;
import linmumua.doudizhu.game.TableSpeechPanelService;
import linmumua.doudizhu.ui.GadgetBoxGuiService;
import linmumua.doudizhu.ui.TableGadgetGuiService;
import linmumua.doudizhu.ui.VirtualGadgetBarStore;
import linmumua.doudizhu.game.TrickHudPreview;
import linmumua.doudizhu.game.TableManager;
import linmumua.doudizhu.mahjong.EmbeddedMahjongRuntime;
import linmumua.doudizhu.mahjong.MahjongTableManager;
import linmumua.doudizhu.listener.CraftEngineLifecycleListener;
import linmumua.doudizhu.listener.HandGuiListener;
import linmumua.doudizhu.listener.PlayerConnectionListener;
import linmumua.doudizhu.listener.TableWorldLifecycleListener;
import linmumua.doudizhu.listener.WorldTableInteractionListener;
import linmumua.doudizhu.placeholder.MuzPlaceholderExpansion;
import linmumua.doudizhu.room.TableLevel;
import linmumua.doudizhu.scheduler.MuzScheduler;
import linmumua.doudizhu.storage.DatabaseManager;
import linmumua.doudizhu.storage.MatchParticipantRecord;
import linmumua.doudizhu.storage.MatchRecord;
import linmumua.doudizhu.storage.PersistedTableRecord;
import linmumua.doudizhu.storage.PlayerHistoryEntry;
import linmumua.doudizhu.ui.HandGuiService;
import linmumua.doudizhu.ui.MuzTheme;
import linmumua.doudizhu.world.PhysicalTableManager;
import java.util.ArrayList;
import java.io.File;
import java.io.IOException;
import java.util.EnumMap;
import java.util.LinkedHashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.time.Instant;
import java.time.ZoneId;
import java.math.BigDecimal;
import java.time.format.DateTimeFormatter;
import net.kyori.adventure.bossbar.BossBar;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextDecoration;
import net.kyori.adventure.text.minimessage.MiniMessage;
import linmumua.doudizhu.compat.VersionCompat;
import net.milkbowl.vault.economy.EconomyResponse;
import org.bukkit.Bukkit;
import org.bukkit.OfflinePlayer;
import org.bukkit.Color;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.block.BlockFace;
import org.bukkit.command.CommandSender;
import org.bukkit.command.Command;
import org.bukkit.command.CommandMap;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.player.PlayerChangedWorldEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerKickEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.player.PlayerRespawnEvent;
import org.bukkit.event.player.PlayerTeleportEvent;
import org.bukkit.inventory.ItemFlag;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.java.JavaPlugin;

/**
 * MUZ 主插件入口。
 * Maintainer: linmumua
 */
public final class DoudizhuPlugin extends JavaPlugin {
    private static final MiniMessage MINI = MiniMessage.miniMessage();
    /**
     * 家具模型 id。必须和 build.gradle.kts 里的 tableFurnitureId / chairFurnitureId 一致，
     * 也要和源包 models/item/furniture/ 下的文件名一致，否则 CE 找不到模型、桌椅渲染不出来。
     */
    private static final String TABLE_FURNITURE_ID = "table_large";
    private static final String CHAIR_FURNITURE_ID = "chair_large";
    private static final String DEFAULT_TABLE_ITEM_MODEL = "muz:furniture/" + TABLE_FURNITURE_ID;
    private static final String DEFAULT_CHAIR_ITEM_MODEL = "muz:furniture/" + CHAIR_FURNITURE_ID;
    private static final String DEFAULT_TABLE_DISPLAY_NAME = "Dou Dizhu Table";
    private static final String DEFAULT_CHAIR_DISPLAY_NAME = "Dou Dizhu Chair";
    private static final String HISTORY_CYAN = "#5EAACA";
    private static final String HISTORY_SKY = "#8FC7DA";
    private static final String HISTORY_GOLD = "#D2B16D";
    private static final String HISTORY_CREAM = "#E8D7A6";
    private static final String HISTORY_PINK = "#D06A92";
    private static final String HISTORY_ROSE = "#B54A73";
    private static final String HISTORY_RED = "#8E314E";
    private static final String HISTORY_GREEN = "#3F9969";
    private static final String HISTORY_MINT = "#74BF98";
    /** 按钮离桌距离。1.40 是测试服实测调优值，比原来的 2.10 更贴近桌沿。 */
    private static final double DEFAULT_BUTTON_DISTANCE = 1.40;

    private static final boolean DEFAULT_SELECTION_SOUND_ENABLED = true;
    private static final String DEFAULT_SELECTION_SOUND_SPEC = "minecraft:block.note_block.pling 0.35 1.18 0.92";
    private static final String DEFAULT_COUNTDOWN_SOUND_SPEC = "minecraft:block.note_block.hat 0.45 1.00";
    private static final String DEFAULT_UNREADY_WARNING_SOUND_SPEC = "minecraft:block.note_block.didgeridoo 0.55 0.85";
    private static final String DEFAULT_PLACEMENT_BLOCKED_SOUND_SPEC = "minecraft:block.note_block.bass 0.55 0.75";
    private static final String DEFAULT_AI_SYSTEM_PROMPT = """
        你是 MUZ 的斗地主智能牌局助手，风格冷静、稳健、重视胜率与节奏控制。
        你的第一目标永远是做出合法且高胜率的决策，而不是为了炫技、搞节目效果或追求单手牌面最大。
        炸弹和王炸属于高价值终结资源：除非能直接建立明显优势、阻止对手冲刺、或已经进入收尾阶段，否则不要轻易交出。
        能用普通牌解决的问题，就不要升级到炸弹；能用炸弹解决的问题，就不要升级到王炸。
        先手时优先考虑低风险起手、整理手型、保留关键控制牌；跟牌时优先考虑是否有必要接，而不是见牌就压。
        当对手剩牌很少时，可以适当提高压制优先级；当队友仍有机会接管节奏时，避免过度消耗自己的终结资源。
        如果后续系统消息要求你只输出固定格式，你必须严格服从，不解释、不闲聊、不追加额外文本。
        """;
    // bot.action-delay-ticks 的 min/max 默认值必须通过常量共用：
    // L2849/L3083 读同一个旧键做 min 回退，L2850/L3087 读同一个旧键做 max 回退，
    // 若各处硬编码不同字面量，改一处漏一处会导致配置迁移与运行时读取不一致。
    private static final int DEFAULT_BOT_DELAY_MIN_TICKS = 10;
    private static final int DEFAULT_BOT_DELAY_MAX_TICKS = 30;
    private static final int PLAYER_OPTION_PROFILE_COUNT = 4;
    /** 手牌基础大小。0.8 是测试服实测调优值，原来的 0.35 在实机上偏小。 */
    private static final float DEFAULT_PRIVATE_CARD_SCALE = 0.8f;
    private static final float DEFAULT_PRIVATE_CARD_AXIS_SCALE = 0.50f;
    private static final List<GlowColorOption> GLOW_COLOR_OPTIONS = List.of(
        new GlowColorOption("默认", null),
        new GlowColorOption("金黄", Color.fromRGB(255, 226, 92)),
        new GlowColorOption("湖蓝", Color.fromRGB(96, 180, 255)),
        new GlowColorOption("青绿", Color.fromRGB(74, 222, 128)),
        new GlowColorOption("玫红", Color.fromRGB(255, 99, 132)),
        new GlowColorOption("紫晶", Color.fromRGB(180, 120, 255)),
        new GlowColorOption("橙金", Color.fromRGB(255, 170, 64))
    );

    private TableManager tableManager;
    /** 仅在 integration.mahjong.enabled=true 时装配；默认关闭时保持 null。 */
    private EmbeddedMahjongRuntime embeddedMahjongRuntime;

    private HandGuiService handGuiService;
    private MuzPlaceholderExpansion placeholderExpansion;
    private NamespacedKey cardIdKey;
    private NamespacedKey tableNameKey;
    private NamespacedKey interactionActionKey;
    private NamespacedKey tablePlacerKey;
    private NamespacedKey tablePlacerIdKey;
    private NamespacedKey tablePlacerLevelKey;
    private NamespacedKey tableRemoverKey;
    private NamespacedKey tableRemoverModeKey;
    private NamespacedKey tableRemoverIdKey;
    private NamespacedKey counterItemKey;
    private NamespacedKey hudDebugStickKey;
    private CraftEngineBundleExporter craftEngineBundleExporter;
    private CraftEngineFurnitureService craftEngineFurnitureService;
    private CraftEngineOffsetService craftEngineOffsetService;
    /** 普通 ActionBar 叠加服务；Hotbar HUD 不再进入正式运行期链路。 */
    private ActionBarOverlayService actionBarOverlayService;
    /** 玩家输出统一门面；生产构造先经 global UUID 查找再进入 player owner lane。 */
    private PlayerOutputDispatcher playerOutputDispatcher;
    /** 桌内道具状态与效果由专用服务管理，入口仅负责装配。 */
    private TableGadgetService tableGadgetService;
    private TableGadgetSettings tableGadgetSettings;
    private VirtualGadgetBarStore tableGadgetLoadoutStore;
    private GadgetPreviewSnapshotService gadgetPreviewSnapshotService;
    private TableGadgetBarHudService tableGadgetBarHudService;
    private TableGadgetGuiService tableGadgetGuiService;
    private TableSpeechPanelService tableSpeechPanelService;
    /** Debug Web 调试面板；仅 debug.web-ui.enabled=true 时非 null。 */
    private DebugWebServer debugWebServer;
    /** Debug Web 与启动恢复共用的单实例配置控制器与资源协调器。 */
    private DebugHudConfigController hudWebConfigController;
    private HudWebApplyCoordinator hudWebApplyCoordinator;
    private HudResourceRecoveryService hudResourceRecoveryService;
    /** 四层 HUD 资源的统一已验证 request；未验证时保持空。 */
    private HudOverlayRuntimeState hudOverlayRuntimeState;
    private PlayerHeadRenderer playerHeadRenderer;
    private VaultEconomyBridge vaultEconomyBridge;
    private PhysicalChipService physicalChipService;
    private AiChatGateway aiChatGateway;
    private AiChatGateway.ProviderConfig aiProviderConfig;
    private HookSnapshot lastVaultHookSnapshot;
    private DatabaseManager databaseManager;
    private MuzScheduler scheduler;
    private PhysicalTableManager physicalTableManager;
    private boolean cardHologramLabelsEnabled;
    private boolean duplicateOnlyCardLabels;
    private double tableSpawnOffsetY;
    private float privateCardScale;
    private float privateCardWidthScale;
    private float privateCardHeightScale;
    private float privateCardDepthScale;
    private float hoverCardScale;
    private double hoverCardLift;
    private int cardHoverInterpolationTicks;
    private int cardHoverAnimationTypeIndex;
    private float tableScale;
    private float chairScale;
    private float smallTextScale;
    private float statusTextScale;
    private float labelTextScale;
    private float statusNameScale;
    private double statusNameLateralOffset;
    private double statusNameVerticalOffset;
    private double statusNameDepthOffset;
    private float seatNameScale;
    private double seatNameLateralOffset;
    private double seatNameVerticalOffset;
    private double seatNameDepthOffset;
    private float emptySeatScale;
    private double emptySeatLateralOffset;
    private double emptySeatVerticalOffset;
    private double emptySeatDepthOffset;
    private float seatInfoScale;
    private double seatInfoLateralOffset;
    private double seatInfoVerticalOffset;
    private double seatInfoDepthOffset;
    private float cardDepthOffset;
    private float handSpacing;
    private float selectedCardScale;
    private double selectedCardLift;
    private boolean hoverGlowEnabled;
    private int hoverGlowRed;
    private int hoverGlowGreen;
    private int hoverGlowBlue;
    private boolean selectedGlowEnabled;
    private int selectedGlowRed;
    private int selectedGlowGreen;
    private int selectedGlowBlue;
    private double cardLabelHeight;
    private double cardLabelLateralOffset;
    private double cardLabelDepthOffset;
    private double buttonDistance;
    private double buttonHeight;
    private double tableDisplayHeight;
    private double tableColliderHeight;
    private double chairBaseHeight;
    private double chairColliderHeight;
    private double chairSeatHeight;
    private double chairInteractionHeight;
    private double chairLabelHeight;
    private double chairRotationDegrees;
    private double chairVisualLateralOffset;
    private double chairVisualVerticalOffset;
    private double chairHitboxLateralOffset;
    private double chairHitboxVerticalOffset;
    private double buttonHitboxLateralOffset;
    private double buttonHitboxDepthOffset;
    private double buttonHitboxVerticalOffset;
    private double statusHeight;
    private double playDetailHeight;
    private int statusLineWidth;
    private double handCenterDistance;
    private double handCenterHeight;
    private double chairDistance;
    private double joinLabelHeight;
    private float joinLabelScale;
    private double actionLabelHeight;
    private float actionLabelScale;
    private double buttonFrontBaseDistance;
    private double buttonSideBaseDistance;
    private double buttonDistanceFactor;
    private double buttonSpacingScale;
    private double buttonArcSmallAngleDegrees;
    private double buttonArcLargeAngleDegrees;
    private double buttonArcSmallRadius;
    private double buttonArcLargeRadius;
    private float bgmVolume;
    private float effectVolume;
    private int turnCountdownSeconds;
    private String countdownSoundSpec;
    private String unreadyWarningSoundSpec;
    private String placementBlockedSoundSpec;
    private int botActionDelayMinTicks;
    private int botActionDelayMaxTicks;
    private boolean botAiEnabled;
    private int botAiTimeoutMs;
    private int hintGroupLimit;
    private double debugTableSpacing;
    private boolean vaultEconomyEnabled;
    private double vaultDoudizhuCurrencyPerPoint;
    private List<String> vaultPreferredProviderNames = List.of();
    private boolean chipPaymentEnabled;
    private double globalPrivateHandLateralOffset;
    private double globalPrivateHandVerticalOffset;
    private double globalPrivateHandDepthOffset;
    private String tableItemModelId;
    private String tableDisplayName;
    private String chairItemModelId;
    private String chairDisplayName;
    private final Map<UUID, Boolean> playerCardLabelSettings = new ConcurrentHashMap<>();
    private final Map<UUID, Boolean> playerSelectionSoundSettings = new ConcurrentHashMap<>();
    private final Map<UUID, Integer> playerSelectionSoundProfileSettings = new ConcurrentHashMap<>();
    private final Map<UUID, Integer> playerPlayActionProfileSettings = new ConcurrentHashMap<>();
    private final Map<UUID, EnumMap<PlayActionKind, Integer>> playerPlayActionKindProfileSettings = new ConcurrentHashMap<>();
    private final Map<UUID, Integer> playerHoverGlowColorSettings = new ConcurrentHashMap<>();
    private final Map<UUID, Integer> playerSelectedGlowColorSettings = new ConcurrentHashMap<>();
    private final Map<UUID, PlayerHandOffsets> playerHandOffsets = new ConcurrentHashMap<>();

    /** HUD 调试棒给单个玩家临时覆盖的三行可见组合；不存在时默认三行全开。 */
    private final Map<UUID, Integer> hudRowOverrides = new ConcurrentHashMap<>();
    private volatile TrickHudPreview trickHudPreview;

    private final List<OptionProfile> selectionSoundProfiles = new ArrayList<>();
    private final List<OptionProfile> playActionProfiles = new ArrayList<>();
    private final EnumMap<PlayActionKind, List<OptionProfile>> playActionProfilesByKind = new EnumMap<>(PlayActionKind.class);
    private final Map<UUID, TableMode> playerPreferredModes = new ConcurrentHashMap<>();

    private final AtomicInteger nextBotNumericId = new AtomicInteger(1);
    private final Map<Integer, BotHandle> botHandlesByNumericId = new ConcurrentHashMap<>();
    private final Map<UUID, Integer> botNumericIdsByUuid = new ConcurrentHashMap<>();
    private final Map<TableLevel, RoomLevelProfile> roomLevelProfiles = new EnumMap<>(TableLevel.class);
    private File configFile;
    private MuzYamlConfig config;
    private File playerSettingsFile;
    private MuzYamlConfig playerSettingsConfig;
    private File optionProfilesFile;
    private MuzYamlConfig optionProfilesConfig;
    private volatile boolean shuttingDown;
    private volatile boolean sqlTablesLoaded;
    private volatile List<PersistedTableRecord> pendingPersistedTables = List.of();
    private volatile String persistedTableRestoreSummary = "未开始";
    private volatile int persistedTableRestorePasses;
    private volatile boolean postRestoreRebuildQueued;
    /**
     * 存档牌桌恢复的跨 lane 结算边界。
     *
     * <p>恢复不再在调用线程原地动世界：{@code restoreTable} 现在是异步 stage，
     * 完成回调可能落在锚点 region 线程上，与发起恢复的 global lane（onEnable / 5 秒重试定时器）
     * 并发。因此 {@link #pendingPersistedTables} 的读改写必须串行化，不能再用裸 volatile List
     * 在回调里直接改。
     */
    private final Object persistedTableRestoreLock = new Object();
    /** 正在飞行中的恢复请求（按桌名归一化后的 key 去重），保证同一张桌不会被重复派发、重复生成实体。 */
    private final Set<String> persistedTableRestoreInFlight = ConcurrentHashMap.newKeySet();
    /** 本次启动累计恢复成功的张数，供完成文案使用（异步下成功分散在多个回调里）。 */
    private final AtomicInteger persistedTableRestoredCount = new AtomicInteger();
    private boolean craftEngineProtectionListenerRegistered;
    /**
     * 已注册的 /muz 命令引用，供关闭阶段注销使用。
     *
     * <p>【为什么必须保存引用】paper-plugin.yml 不支持 commands 段，/muz 只能在
     * {@link #registerMuzCommand()} 里经 CommandMap 手动注册。CommandMap 不会随插件 disable
     * 自动摘除命令：插件被禁用后，玩家或控制台仍能选中 /muz，派发时会去解析插件类加载器里的类，
     * 而此时 Paper 已经关闭了插件 JAR（PluginClassLoader 持有的 JarFile 已 close），解析失败即
     * {@code java.util.zip.ZipException: zip file closed}。保存引用是为了在 onDisable
     * （插件类加载器关闭之前）把它从 CommandMap 摘掉。
     */
    private volatile Command muzCommand;
    private static final DateTimeFormatter HISTORY_TIME_FORMAT = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm").withZone(ZoneId.systemDefault());

    public MuzScheduler scheduler() {
        if (scheduler == null) {
            // 关闭中不再注册新任务：谓词用自制的 shuttingDown 而不是 Bukkit 的 isEnabled()。
            // isEnabled() 在 onEnable 装配早期仍可能为 false，据此拒绝会误伤启动期注册的周期任务，
            // 属灾难性回归；shuttingDown 只在 onDisable 首行置位，时序完全可控。
            scheduler = new MuzScheduler(this, this::isShuttingDown);
        }
        return scheduler;
    }

    /**
     * Debug Web HUD 配置的串行边界。Web 写盘在线程池中执行，主线程应用运行态时也持有同一把锁；
     * 这只能保证 Web 自身的保存/重载顺序，不能替代其它旧配置入口的全局配置事务。
     */
    private final Object hudWebConfigLock = new Object();

    /** Debug Web HUD 专用配置锁；仅供 HUD Web 协调器与运行态应用入口使用。 */
    public Object hudWebConfigLock() {
        return hudWebConfigLock;
    }

    public MuzYamlConfig yamlConfig() {
        if (config == null) {
            configFile = new File(getDataFolder(), "config.yml");
            config = new MuzYamlConfig(configFile.toPath());
        }
        return config;
    }

    private void saveDefaultYamlConfig() {
        configFile = new File(getDataFolder(), "config.yml");
        if (!configFile.isFile()) {
            saveResource("config.yml", false);
        }
        config = new MuzYamlConfig(configFile.toPath());
    }

    private void reloadYamlConfig() {
        yamlConfig().reload();
    }

    private void saveYamlConfig() {
        try {
            // 带上打包模板，把注释按键路径补回去；否则每次保存都会把玩家的
            // config.yml 冲成一份没有任何取值说明的裸配置。
            yamlConfig().saveWithComments(packagedConfigTemplate());
        } catch (IOException exception) {
            getLogger().warning("保存 config.yml 失败: " + exception.getMessage());
        }
    }

    /** 读 jar 里打包的 config.yml 模板；读不到就返回 null，调用方退回无注释保存。 */
    private String packagedConfigTemplate() {
        try (java.io.InputStream stream = getResource("config.yml")) {
            if (stream == null) {
                return null;
            }
            return new String(stream.readAllBytes(), java.nio.charset.StandardCharsets.UTF_8);
        } catch (IOException exception) {
            getLogger().warning("读取打包 config.yml 模板失败: " + exception.getMessage());
            return null;
        }
    }

    private boolean mergeDefaultYamlConfig() {
        try (java.io.InputStream stream = getResource("config.yml")) {
            return yamlConfig().mergeMissingFrom(stream);
        } catch (IOException exception) {
            getLogger().warning("合并默认 config.yml 失败: " + exception.getMessage());
            return false;
        }
    }

    @Override
    public void onEnable() {
        // 关服时保存玩家设置会输出空映射，那条 FLOW 分支的 Emitter 内部类必须在此刻加载好：
        // onDisable 阶段 Paper 已不再为插件 ClassLoader 提供新类。
        MuzYamlConfig.warmUpFlowEmitter();
        // 与 scheduler() 工厂保持一致：注入 shuttingDown 谓词，关闭阶段由门面拒绝新任务注册，
        // 否则 onEnable 直接建出的实例会用默认 () -> false 谓词覆盖工厂语义。
        scheduler = new MuzScheduler(this, this::isShuttingDown);
        playerOutputDispatcher = new PlayerOutputDispatcher(this);
        saveDefaultYamlConfig();
        ensureConfigIntegrity();
        optionProfilesFile = new File(getDataFolder(), "option-profiles.yml");
        loadRenderSettings();
        loadAiSettings();
        // 放在两个 load 之后：登记表要等读取点跑过一遍才填满
        migrateZeroableDefaults();
        playerSettingsFile = new File(getDataFolder(), "player-settings.yml");
        loadPlayerSettings();
        cardIdKey = new NamespacedKey(this, "card-id");
        tableNameKey = new NamespacedKey(this, "table-name");
        interactionActionKey = new NamespacedKey(this, "interaction-action");
        tablePlacerKey = new NamespacedKey(this, "table-placer");
        tablePlacerIdKey = new NamespacedKey(this, "table-placer-id");
        tablePlacerLevelKey = new NamespacedKey(this, "table-placer-level");
        tableRemoverKey = new NamespacedKey(this, "table-remover");
        tableRemoverModeKey = new NamespacedKey(this, "table-remover-mode");
        tableRemoverIdKey = new NamespacedKey(this, "table-remover-id");
        counterItemKey = new NamespacedKey(this, "counter-item");
        hudDebugStickKey = new NamespacedKey(this, "hud-debug-stick");
        handGuiService = new HandGuiService(this);
        tableManager = new TableManager(this);
        tableGadgetSettings = TableGadgetSettings.load(yamlConfig());
        tableGadgetLoadoutStore = new VirtualGadgetBarStore(playerSettingsFile.toPath());
        gadgetPreviewSnapshotService = new GadgetPreviewSnapshotService(this, tableGadgetLoadoutStore);
        tableGadgetGuiService = new TableGadgetGuiService(
            tableGadgetLoadoutStore,
            action -> {
                if (action == null || tableGadgetService == null) {
                    return;
                }
                switch (action.type()) {
                    case SELECTED -> {
                        if (action.item() != null) {
                            tableGadgetService.select(action.viewerId(), action.item());
                        }
                    }
                    case REMOVED -> {
                        ItemStack selected = tableGadgetGuiService.selectedItem(action.viewerId());
                        if (selected == null) {
                            tableGadgetService.clear(action.viewerId());
                        } else {
                            tableGadgetService.select(action.viewerId(), selected);
                        }
                    }
                    case BUBBLE -> playerOutputDispatcher.runPlayer(action.viewerId(), viewer -> {
                        if (tableSpeechPanelService == null) {
                            return;
                        }
                        GameTable table = tableManager.getTableOf(viewer);
                        if (table != null) {
                            tableGadgetGuiService.close(viewer);
                            tableSpeechPanelService.open(table, action.viewerId());
                        }
                    });
                    default -> { }
                }
            },
            playerId -> {
                if (gadgetPreviewSnapshotService != null) {
                    gadgetPreviewSnapshotService.onSaved(playerId);
                }
                if (tableGadgetBarHudService != null) {
                    tableGadgetBarHudService.onSaved(playerId);
                }
            },
            tableGadgetSettings.gui().title(),
            tableGadgetSettings.gui().bubbleName(),
            playerOutputDispatcher
        );
        databaseManager = new DatabaseManager(this);
        craftEngineBundleExporter = new CraftEngineBundleExporter(this);
        craftEngineFurnitureService = new CraftEngineFurnitureService(this);
        craftEngineOffsetService = new CraftEngineOffsetService(this);
        // 普通 ActionBar 仍由独立服务承载；正式运行期不再构造或启动 HotbarHudService。
        actionBarOverlayService = new ActionBarOverlayService(this);
        hudOverlayRuntimeState = new HudOverlayRuntimeState();
        hudWebConfigController = new DebugHudConfigController(this);
        hudWebApplyCoordinator = new HudWebApplyCoordinator(this, hudWebConfigController);
        hudResourceRecoveryService = new HudResourceRecoveryService(this, hudWebApplyCoordinator);
        tableGadgetService = new TableGadgetService(this, new TableGadgetEffectService(this), tableGadgetSettings,
            // 道具目标高亮的玩家级工作统一经门面投递到 player lane，见 TableGadgetService.tick。
            playerOutputDispatcher);
        tableSpeechPanelService = createTableSpeechPanelService(tableGadgetSettings);
        tableGadgetBarHudService = new TableGadgetBarHudService(
            this,
            tableGadgetLoadoutStore,
            tableGadgetService,
            actionBarOverlayService,
            craftEngineOffsetService,
            player -> {
                GameTable table = tableManager.getTableOf(player);
                if (table != null && tableSpeechPanelService != null) {
                    tableSpeechPanelService.open(table, player.getUniqueId());
                }
            }
        );
        // Debug Web 调试面板：仅在 debug.web-ui.enabled=true 时启动，生产环境默认关闭。
        // 面板只负责 HUD 配置与资源恢复，不再接管已退役的正式 Hotbar 运行期服务。
        syncDebugWebServerRuntime();
        playerHeadRenderer = new PlayerHeadRenderer(this, craftEngineOffsetService);
        // 预览复用正式 TrickHudService，但不挂到任何牌桌；它只服务 HUD 调试棒和配置对照。
        trickHudPreview = new TrickHudPreview(this);
        vaultEconomyBridge = new VaultEconomyBridge(this);
        physicalChipService = new PhysicalChipService(this::chipPaymentItem, getLogger());
        physicalTableManager = new PhysicalTableManager(this);
        if (isMahjongIntegrationEnabled()) {
            // 只按显式配置装配内嵌运行时；默认 false 时不创建麻将功能。
            embeddedMahjongRuntime = new EmbeddedMahjongRuntime(this);
        }
        initializePersistence();

        getServer().getPluginManager().registerEvents(new PlayerConnectionListener(this), this);
        getServer().getPluginManager().registerEvents(new WorldTableInteractionListener(this), this);
        getServer().getPluginManager().registerEvents(new TableWorldLifecycleListener(this), this);
        getServer().getPluginManager().registerEvents(new CraftEngineLifecycleListener(this), this);
        getServer().getPluginManager().registerEvents(new HandGuiListener(this), this);
        if (tableGadgetBarHudService != null) {
            getServer().getPluginManager().registerEvents(tableGadgetBarHudService, this);
            tableGadgetBarHudService.start();
        }
        if (gadgetPreviewSnapshotService != null) {
            getServer().getPluginManager().registerEvents(gadgetPreviewSnapshotService, this);
        }
        registerMuzCommand();
        ensureCraftEngineProtectionListenerRegistered();
        // 牌桌世界实体由各自的 owner region 周期任务刷新；这里仅保留语音面板的独立生命周期。
        scheduler().runTimer(1L, 1L, () -> {
            if (tableSpeechPanelService != null) {
                tableSpeechPanelService.tick();
            }
        });
        HookSnapshot placeholderHook = ensurePlaceholderHookReadyInternal();
        HookSnapshot vaultHook = ensureVaultEconomyHookReadyInternal();
        CraftEngineBundleExporter.BundleExportResult exportResult = craftEngineBundleExporter.exportIfAvailable();
        if (hudResourceRecoveryService != null) {
            hudResourceRecoveryService.onBundleExported("startup");
        }
        attemptPersistedTableRestore();
        logStartupSummary(exportResult, detectSupportedHooks(placeholderHook, vaultHook));
        logVaultHookDiagnosis(vaultHook);
        scheduleVaultHookRetries();
        schedulePersistedTableRestore();
    }

    @Override
    public void onDisable() {
        // 先封住所有新入口并推进代次；旧的 global/region/player/async 回调只能在门面中止。
        shuttingDown = true;
        runShutdownStep("推进关闭代次", () -> {
            if (scheduler != null) {
                scheduler.newGeneration();
            }
        });
        // 先注销 /muz：命令派发会解析插件类加载器里的类，必须在 Paper 关闭插件 JAR 之前摘掉，
        // 否则残留命令被触发时会抛 ZipException: zip file closed。放最前面也能让关闭期间到达的
        // /muz 直接落空，不再进入正在拆解的业务。摘除失败只记日志、不中断后续关闭步骤。
        runShutdownStep("注销 /muz 命令", this::unregisterMuzCommand);

        // Debug Web 和业务输出先停，但 scheduler 仍保持可用，给 owner 清理与已入队数据库任务留窗口。
        runShutdownStep("关闭 Debug Web", () -> {
            if (debugWebServer != null) {
                debugWebServer.close();
            }
        });
        runShutdownStep("关闭预览与 HUD 恢复", () -> {
            if (gadgetPreviewSnapshotService != null) {
                gadgetPreviewSnapshotService.close();
            }
            if (hudResourceRecoveryService != null) {
                hudResourceRecoveryService.close();
            }
            if (hudWebApplyCoordinator != null) {
                hudWebApplyCoordinator.close();
            }
        });
        runShutdownStep("关闭桌内业务入口", () -> {
            if (tableSpeechPanelService != null) {
                tableSpeechPanelService.shutdown();
            }
            if (tableGadgetBarHudService != null) {
                tableGadgetBarHudService.shutdown();
            }
            if (tableGadgetGuiService != null) {
                tableGadgetGuiService.shutdown();
            }
            if (tableGadgetService != null) {
                tableGadgetService.shutdown();
            }
            if (actionBarOverlayService != null) {
                actionBarOverlayService.stop();
            }
            TrickHudPreview preview = trickHudPreview;
            if (preview != null) {
                preview.hideAll();
            }
            hudRowOverrides.clear();
        });

        // 牌桌、麻将和实体清理必须在 scheduler/backend 关闭前发往各自合法 owner。
        runShutdownStep("关闭内嵌麻将", () -> {
            if (embeddedMahjongRuntime != null) {
                embeddedMahjongRuntime.shutdown();
            }
        });
        runShutdownStep("关闭斗地主牌桌", () -> {
            if (tableManager != null) {
                tableManager.shutdown();
            }
        });
        runShutdownStep("清理斗地主实体", () -> {
            if (physicalTableManager != null) {
                physicalTableManager.shutdown();
            }
        });
        runShutdownStep("保存玩家设置", this::savePlayerSettings);
        runShutdownStep("收尾诊断日志", this::logShutdownDiagnostics);
        runShutdownStep("注销 PlaceholderAPI", () -> {
            if (placeholderExpansion != null && placeholderExpansion.isRegistered()) {
                placeholderExpansion.unregister();
            }
        });

        // 数据库必须先 flush 已入队 I/O；最后才关闭 scheduler/backend。
        // 关服阶段落盘由 DatabaseManager.runWrite 的同步回退保证（宁可短暂阻塞调用线程，也要保住关服那一刻提交的写库），
        // 异步段仍保留，仅用于「插件仍启用但调度器已关闭」的残余窗口兜底。
        runShutdownStep("关闭数据库", () -> {
            if (databaseManager != null) {
                databaseManager.close();
            }
        });
        runShutdownStep("关闭调度器", () -> {
            if (scheduler != null) {
                scheduler.close();
            }
        });
    }

    private void runShutdownStep(String label, Runnable action) {
        try {
            action.run();
        } catch (RuntimeException | Error failure) {
            getLogger().log(java.util.logging.Level.SEVERE, "MUZ 关闭阶段失败: " + label, failure);
        }
    }

    public TableManager getTableManager() {
        return tableManager;
    }

    /** 普通 ActionBar 叠加服务；牌桌运行期统一复用这一实例。 */
    public ActionBarOverlayService getActionBarOverlayService() {
        return actionBarOverlayService;
    }

    /**
     * 兼容旧 API；HotbarHudService 已退出正式运行期装配，因此固定返回 null。
     * 新代码不得通过该入口获取或构造正式 Hotbar 链路。
     */
    @Deprecated
    public HotbarHudService getHotbarHudService() {
        return null;
    }

    /** 提供桌内道具交互入口，业务与临时实体均由服务持有。 */
    public TableGadgetService tableGadgets() {
        return tableGadgetService;
    }

    /** 牌桌对局中的屏幕额外道具栏；不打开 Inventory GUI。 */
    public TableGadgetBarHudService getTableGadgetBarHudService() {
        return tableGadgetBarHudService;
    }

    /** 道具箱 GUI 服务；保留兼容入口，但牌桌按钮不再打开九格 Inventory GUI。 */
    public TableGadgetGuiService getTableGadgetGuiService() {
        return tableGadgetGuiService;
    }

    /** 玩家虚拟道具栏的唯一持久化入口。 */
    public VirtualGadgetBarStore getTableGadgetLoadoutStore() {
        return tableGadgetLoadoutStore;
    }

    /** 桌内语音面板服务。 */
    public TableSpeechPanelService getTableSpeechPanelService() {
        return tableSpeechPanelService;
    }

    /**
     * 兼容旧资源协调器 API；正式运行期已退役 HotbarHudService，调用不再启动任何服务。
     */
    @Deprecated
    public void setHotbarOverlayReady(boolean ready) {
        // 保留签名，避免旧桥接代码在升级期间链接失败；不构造或启动 HotbarHudService。
    }

    /** 兼容旧资源协调器 API；正式运行期不再消费 Hotbar overlay 就绪状态。 */
    @Deprecated
    public void setHotbarOverlayReady(boolean ready, int hotbarScale) {
        // 同上：资源生成/校验仍可执行，但不再接入已退役的正式 Hotbar 运行期链路。
    }

    /** Debug Web 调试面板实例；debug.web-ui.enabled=false 时返回 null。 */
    public DebugWebServer getDebugWebServer() {
        return debugWebServer;
    }

    /** 返回四层 HUD 资源统一就绪状态；未验证时仍返回单实例空状态。 */
    public HudOverlayRuntimeState getHudOverlayRuntimeState() {
        if (hudOverlayRuntimeState == null) {
            hudOverlayRuntimeState = new HudOverlayRuntimeState();
        }
        return hudOverlayRuntimeState;
    }

    /** 供 Debug Web 与启动恢复共享同一资源协调器。 */
    public HudWebApplyCoordinator getHudWebApplyCoordinator() {
        return hudWebApplyCoordinator;
    }

    /** 供 CraftEngine 生命周期监听器委托恢复已保存的 HUD 资源。 */
    public HudResourceRecoveryService getHudResourceRecoveryService() {
        return hudResourceRecoveryService;
    }

    public void ensurePlaceholderHookReady() {
        ensurePlaceholderHookReadyInternal();
    }

    public void ensureCraftEngineProtectionListenerRegistered() {
        if (craftEngineProtectionListenerRegistered) {
            return;
        }
        org.bukkit.plugin.Plugin craftEngine = getServer().getPluginManager().getPlugin("CraftEngine");
        if (craftEngine == null || !craftEngine.isEnabled()) {
            return;
        }
        getServer().getPluginManager().registerEvents(new linmumua.doudizhu.listener.CraftEngineProtectionListener(this), this);
        craftEngineProtectionListenerRegistered = true;
    }

    private HookSnapshot ensurePlaceholderHookReadyInternal() {
        org.bukkit.plugin.Plugin placeholderApi = getServer().getPluginManager().getPlugin("PlaceholderAPI");
        if (placeholderApi == null) {
            return new HookSnapshot("papi", "PlaceholderAPI", HookState.MISSING, "没装 PlaceholderAPI，%muz_*% 变量不生效");
        }
        if (!placeholderApi.isEnabled()) {
            return new HookSnapshot("papi", "PlaceholderAPI", HookState.DISABLED, "没装 PlaceholderAPI，%muz_*% 变量不生效");
        }
        if (placeholderExpansion == null) {
            placeholderExpansion = new MuzPlaceholderExpansion(this);
        }
        if (placeholderExpansion.isRegistered()) {
            return new HookSnapshot("papi", "PlaceholderAPI", HookState.HOOKED, "%muz_*% 占位符已启用");
        }
        boolean registered = placeholderExpansion.register();
        if (registered) {
            return new HookSnapshot("papi", "PlaceholderAPI", HookState.HOOKED, "%muz_*% 占位符已启用");
        } else {
            getLogger().warning("Failed to register PlaceholderAPI placeholders for identifier 'muz'. Check for duplicate expansions or restart the server after replacing the jar.");
            return new HookSnapshot("papi", "PlaceholderAPI", HookState.ERROR, "没装 PlaceholderAPI，%muz_*% 变量不生效");
        }
    }

    public boolean isPlaceholderApiEnabled() {
        return getServer().getPluginManager().isPluginEnabled("PlaceholderAPI");
    }

    public boolean isMuzPlaceholderRegistered() {
        return placeholderExpansion != null && placeholderExpansion.isRegistered();
    }

    public AiChatGateway getAiChatGateway() {
        return aiChatGateway;
    }

    public boolean isDeepseekAiEnabled() {
        return aiProviderConfig != null
            && aiProviderConfig.enabled()
            && aiProviderConfig.hasApiKey()
            && aiChatGateway != null
            && aiChatGateway.isEnabled();
    }

    public boolean isBotAiEnabled() {
        return botAiEnabled && aiChatGateway != null && aiChatGateway.isEnabled();
    }

    public int getBotAiTimeoutMs() {
        return botAiTimeoutMs;
    }

    public String aiStatusSummary() {
        if (aiProviderConfig == null) {
            return "还没配";
        }
        if (!aiProviderConfig.hasApiKey()) {
            return aiProviderConfig.providerName()
                + " | " + aiProviderConfig.model()
                + " @ " + aiProviderConfig.baseUrl()
                + aiProviderConfig.chatCompletionsPath()
                + " | 缺 API Key，机器人暂时按本地策略出牌";
        }
        return aiProviderConfig.providerName()
            + " | " + aiProviderConfig.model()
            + " @ " + aiProviderConfig.baseUrl()
            + aiProviderConfig.chatCompletionsPath()
            + " | API Key 已配置";
    }

    public String aiBaseUrl() {
        return aiProviderConfig == null ? "https://api.deepseek.com" : aiProviderConfig.baseUrl();
    }

    public String aiModelName() {
        return aiProviderConfig == null ? "deepseek-chat" : aiProviderConfig.model();
    }

    public String aiSystemPrompt() {
        return aiProviderConfig == null ? DEFAULT_AI_SYSTEM_PROMPT : normalizeNonBlank(aiProviderConfig.systemPrompt(), DEFAULT_AI_SYSTEM_PROMPT);
    }

    public List<String> aiSystemPromptPreviewLines() {
        String prompt = aiSystemPrompt().replace('\r', '\n');
        List<String> lines = new ArrayList<>();
        for (String rawLine : prompt.split("\\n+")) {
            String line = rawLine.trim();
            if (!line.isBlank()) {
                lines.add(line);
            }
            if (lines.size() >= 3) {
                break;
            }
        }
        if (lines.isEmpty()) {
            lines.add("未设置");
        }
        return lines;
    }

    public boolean hasAiApiKey() {
        return aiProviderConfig != null && aiProviderConfig.hasApiKey();
    }

    public String aiApiKeyMasked() {
        if (!hasAiApiKey()) {
            return "未设置";
        }
        String apiKey = aiProviderConfig.apiKey();
        int keep = Math.min(4, apiKey.length());
        return "已设置 · ****" + apiKey.substring(apiKey.length() - keep);
    }

    public void setAiBaseUrl(String rawUrl) {
        String normalized = rawUrl == null ? "" : rawUrl.trim();
        if (normalized.isBlank()) {
            throw new IllegalArgumentException("API 链接不能为空。");
        }
        java.net.URI uri;
        try {
            uri = java.net.URI.create(normalized);
        } catch (IllegalArgumentException exception) {
            throw new IllegalArgumentException("API 链接格式不对，示例：https://api.deepseek.com");
        }
        if (uri.getScheme() == null || uri.getScheme().isBlank() || uri.getHost() == null || uri.getHost().isBlank()) {
            throw new IllegalArgumentException("API 链接格式不对，示例：https://api.deepseek.com");
        }
        yamlConfig().set("ai.deepseek.enabled", true);
        yamlConfig().set("bot.ai.enabled", true);
        yamlConfig().set("ai.deepseek.url", normalized);
        saveYamlConfig();
        loadAiSettings();
    }

    public void setAiApiKey(String rawApiKey) {
        String normalized = rawApiKey == null ? "" : rawApiKey.trim();
        if (normalized.isBlank()) {
            throw new IllegalArgumentException("API 密钥不能为空。");
        }
        yamlConfig().set("ai.deepseek.enabled", true);
        yamlConfig().set("bot.ai.enabled", true);
        yamlConfig().set("ai.deepseek.api-key", normalized);
        saveYamlConfig();
        loadAiSettings();
    }

    public void setAiModelName(String rawModel) {
        String normalized = rawModel == null ? "" : rawModel.trim();
        if (normalized.isBlank()) {
            throw new IllegalArgumentException("模型不能为空。");
        }
        yamlConfig().set("ai.deepseek.enabled", true);
        yamlConfig().set("bot.ai.enabled", true);
        yamlConfig().set("ai.deepseek.model", normalized);
        saveYamlConfig();
        loadAiSettings();
    }

    public void setAiSystemPrompt(String rawPrompt) {
        String normalized = rawPrompt == null ? "" : rawPrompt.trim();
        if (normalized.isBlank()) {
            throw new IllegalArgumentException("全局人设词不能为空。");
        }
        yamlConfig().set("ai.deepseek.enabled", true);
        yamlConfig().set("bot.ai.enabled", true);
        yamlConfig().set("ai.deepseek.system-prompt", normalized);
        saveYamlConfig();
        loadAiSettings();
    }

    public void recordBotAiTrace(BotGameType gameType, UUID botId, String tableName, String phase, String prompt, AiChatGateway.ChatResponse response, String parsedDecision, boolean appliedAi, String fallbackReason, String errorMessage) {
        if (botId == null) {
            return;
        }
        File dir = new File(getDataFolder(), "bot");
        if (!dir.exists()) {
            dir.mkdirs();
        }
        Integer numericId = getBotNumericId(botId);
        String fileId = numericId == null ? botId.toString().substring(0, 8) : String.valueOf(numericId);
        File file = new File(dir, "bot-" + fileId + ".yml");
        MuzYamlConfig configuration = new MuzYamlConfig(file.toPath());
        long now = System.currentTimeMillis();
        configuration.set("bot.numeric-id", numericId);
        configuration.set("bot.uuid", botId.toString());
        configuration.set("bot.game-type", gameType == null ? "UNKNOWN" : gameType.name());
        configuration.set("bot.table-name", tableName);
        configuration.set("updated-at", now);
        configuration.set("last.phase", phase);
        configuration.set("last.prompt", prompt);
        configuration.set("last.applied-ai", appliedAi);
        configuration.set("last.parsed-decision", parsedDecision);
        configuration.set("last.fallback-reason", fallbackReason);
        configuration.set("last.error", errorMessage);
        if (response != null) {
            configuration.set("last.response.id", response.id());
            configuration.set("last.response.model", response.model());
            configuration.set("last.response.content", response.content());
            configuration.set("last.response.reasoning", response.reasoningContent());
            configuration.set("last.response.finish-reason", response.finishReason());
            if (response.usage() != null) {
                configuration.set("last.response.usage.prompt-tokens", response.usage().promptTokens());
                configuration.set("last.response.usage.completion-tokens", response.usage().completionTokens());
                configuration.set("last.response.usage.total-tokens", response.usage().totalTokens());
                configuration.set("last.response.usage.reasoning-tokens", response.usage().reasoningTokens());
            }
        } else {
            configuration.set("last.response", null);
        }
        try {
            configuration.save();
        } catch (IOException exception) {
            getLogger().warning("保存 bot AI 返回数据失败: " + exception.getMessage());
        }
    }

    private HookSnapshot ensureVaultEconomyHookReadyInternal() {
        if (!vaultEconomyEnabled) {
            HookSnapshot snapshot = new HookSnapshot("vault", "Vault", HookState.DISABLED, "没接上经济插件，输赢暂时不结算");
            lastVaultHookSnapshot = snapshot;
            return snapshot;
        }
        org.bukkit.plugin.Plugin vault = getServer().getPluginManager().getPlugin("Vault");
        if (vault == null) {
            HookSnapshot snapshot = new HookSnapshot("vault", "Vault", HookState.MISSING, "没接上经济插件，输赢暂时不结算");
            lastVaultHookSnapshot = snapshot;
            return snapshot;
        }
        if (!vault.isEnabled()) {
            HookSnapshot snapshot = new HookSnapshot("vault", "Vault", HookState.DISABLED, "没接上经济插件，输赢暂时不结算");
            lastVaultHookSnapshot = snapshot;
            return snapshot;
        }
        if (vaultEconomyBridge == null) {
            vaultEconomyBridge = new VaultEconomyBridge(this);
        }
        if (vaultEconomyBridge.refreshConnection(vaultPreferredProviderNames)) {
            String detail = "已挂钩 " + vaultEconomyBridge.providerName();
            if (!isBlank(vaultEconomyBridge.providerPluginName()) && !Objects.equals(vaultEconomyBridge.providerName(), vaultEconomyBridge.providerPluginName())) {
                detail += "@" + vaultEconomyBridge.providerPluginName();
            }
            detail += " | 已启用房间经济能力";
            HookSnapshot snapshot = new HookSnapshot("vault", "Vault", HookState.HOOKED, detail);
            lastVaultHookSnapshot = snapshot;
            return snapshot;
        }
        HookSnapshot snapshot = new HookSnapshot(
            "vault",
            "Vault",
            HookState.ERROR,
            "没接上经济插件，输赢暂时不结算 | 原因: " + safeEconomyError(vaultEconomyBridge.statusDetail())
        );
        lastVaultHookSnapshot = snapshot;
        return snapshot;
    }

    public String placeholderPointValue(String rawTarget, @org.jetbrains.annotations.Nullable OfflinePlayer viewer) {
        String target = linmumua.doudizhu.placeholder.MuzHeadPlaceholderFormat.normalizeTargetValue(rawTarget, viewer);
        if (isBlank(target)) {
            return "0";
        }
        PlaceholderTarget resolved = resolvePlaceholderTarget(target);
        if (resolved == null) {
            return "0";
        }
        return switch (resolved.kind()) {
            case DOUDIZHU -> String.valueOf(resolved.gameTable().getScore(resolved.playerId()));
        };
    }

    public String placeholderRoleValue(String rawTarget, @org.jetbrains.annotations.Nullable OfflinePlayer viewer) {
        String target = linmumua.doudizhu.placeholder.MuzHeadPlaceholderFormat.normalizeTargetValue(rawTarget, viewer);
        if (isBlank(target)) {
            return "无";
        }
        PlaceholderTarget resolved = resolvePlaceholderTarget(target);
        if (resolved == null) {
            return "无";
        }
        return switch (resolved.kind()) {
            case DOUDIZHU -> {
                linmumua.doudizhu.game.PlayerRole role = resolved.gameTable().getRole(resolved.playerId());
                yield role == null ? "无" : role.displayName();
            }
        };
    }

    public String placeholderHandValue(String rawTarget, @org.jetbrains.annotations.Nullable OfflinePlayer viewer) {
        String target = linmumua.doudizhu.placeholder.MuzHeadPlaceholderFormat.normalizeTargetValue(rawTarget, viewer);
        if (isBlank(target)) {
            return "0";
        }
        PlaceholderTarget resolved = resolvePlaceholderTarget(target);
        if (resolved == null) {
            return "0";
        }
        return switch (resolved.kind()) {
            case DOUDIZHU -> String.valueOf(resolved.gameTable().getHand(resolved.playerId()).size());
        };
    }

    public String placeholderBidValue(String rawTarget, @org.jetbrains.annotations.Nullable OfflinePlayer viewer) {
        String target = linmumua.doudizhu.placeholder.MuzHeadPlaceholderFormat.normalizeTargetValue(rawTarget, viewer);
        if (isBlank(target)) {
            return "0";
        }
        PlaceholderTarget resolved = resolvePlaceholderTarget(target);
        if (resolved == null) {
            return "0";
        }
        return switch (resolved.kind()) {
            case DOUDIZHU -> String.valueOf(resolved.gameTable().getBid(resolved.playerId()));
        };
    }

    public String placeholderTableValue(String rawTarget, @org.jetbrains.annotations.Nullable OfflinePlayer viewer) {
        String target = linmumua.doudizhu.placeholder.MuzHeadPlaceholderFormat.normalizeTargetValue(rawTarget, viewer);
        if (isBlank(target)) {
            return "无";
        }
        PlaceholderTarget resolved = resolvePlaceholderTarget(target);
        if (resolved == null) {
            return "无";
        }
        return switch (resolved.kind()) {
            case DOUDIZHU -> resolved.gameTable().getName();
        };
    }

    public String placeholderPhaseValue(String rawTarget, @org.jetbrains.annotations.Nullable OfflinePlayer viewer) {
        String target = linmumua.doudizhu.placeholder.MuzHeadPlaceholderFormat.normalizeTargetValue(rawTarget, viewer);
        if (isBlank(target)) {
            return "无";
        }
        PlaceholderTarget resolved = resolvePlaceholderTarget(target);
        if (resolved == null) {
            return "无";
        }
        return switch (resolved.kind()) {
            case DOUDIZHU -> resolved.gameTable().getPhase().displayName();
        };
    }

    public String placeholderChipValue(String rawTarget, @org.jetbrains.annotations.Nullable OfflinePlayer viewer) {
        if (!Bukkit.isPrimaryThread()) {
            return "不可查询（实体筹码仅支持在线主线程查询）";
        }
        String target = linmumua.doudizhu.placeholder.MuzHeadPlaceholderFormat.normalizeTargetValue(rawTarget, viewer);
        Player player;
        if (isBlank(target)) {
            player = viewer == null ? null : viewer.getPlayer();
        } else {
            player = Bukkit.getPlayerExact(target);
        }
        if (player == null || !player.isOnline()) {
            return "不可查询（实体筹码仅支持在线主线程查询）";
        }
        try {
            return String.valueOf(getChipBalance(player.getUniqueId()));
        } catch (RuntimeException exception) {
            getLogger().log(java.util.logging.Level.WARNING, "实体筹码占位符查询失败: " + player.getUniqueId(), exception);
            return "不可查询（实体筹码查询失败）";
        }
    }

    public List<PlayerHistoryEntry> loadPlayerHistory(UUID playerId, int limit, int offset) {
        return databaseManager == null ? List.of() : databaseManager.loadPlayerHistory(playerId, limit, offset);
    }

    public void recordDoudizhuMatch(GameTable table, List<UUID> winners, Map<UUID, Integer> scoreDeltas, Map<UUID, SettlementResult> settlements) {
        if (databaseManager == null || !databaseManager.isInitialized() || table == null) {
            return;
        }
        org.bukkit.Location anchor = physicalTableManager == null ? null : physicalTableManager.tableAnchor(table.getName());
        MatchRecord record = new MatchRecord(
            "DOUDIZHU",
            table.getName(),
            table.getRoomLevel(),
            winners.size() == 1 && winners.contains(table.getLandlord()) ? "地主胜" : "农民胜",
            System.currentTimeMillis(),
            anchor == null || anchor.getWorld() == null ? null : anchor.getWorld().getName(),
            anchor == null ? 0.0 : anchor.getX(),
            anchor == null ? 0.0 : anchor.getY(),
            anchor == null ? 0.0 : anchor.getZ()
        );
        List<MatchParticipantRecord> participants = new ArrayList<>();
        for (UUID seat : table.getSeats()) {
            int scoreDelta = scoreDeltas.getOrDefault(seat, 0);
            SettlementResult settlement = settlements.getOrDefault(seat, currentRoomStatus(table.getRoomLevel(), seat));
            // 实体筹码失败/不可查询时，战绩只保留积分；不得把 delta=0、余额=0 写成成功的筹码结算。
            double settlementDelta = settlement.hasCurrencySnapshot() ? settlement.delta() : scoreDelta;
            String settlementUnit = settlement.hasCurrencySnapshot() ? settlement.unitLabel() : "分";
            double debtAfter = settlement.hasCurrencySnapshot() ? settlement.debt() : 0.0;
            double balanceAfter = settlement.hasCurrencySnapshot() ? settlement.postBalance() : 0.0;
            boolean bankrupt = settlement.hasCurrencySnapshot() && settlement.bankrupt();
            participants.add(new MatchParticipantRecord(
                seat,
                resolvePlayerName(seat) == null ? table.displayName(seat) : resolvePlayerName(seat),
                table.getRole(seat) == null ? "无" : table.getRole(seat).displayName(),
                winners.contains(seat) ? "WIN" : "LOSE",
                scoreDelta,
                settlementDelta,
                settlementUnit,
                debtAfter,
                balanceAfter,
                bankrupt
            ));
        }
        databaseManager.insertMatch(record, participants);
    }

    public List<Component> buildHistoryComponents(UUID targetPlayerId, String fallbackName, int page, int pageSize) {
        List<PlayerHistoryEntry> entries = loadPlayerHistory(targetPlayerId, pageSize, Math.max(0, page - 1) * pageSize);
        if (entries.isEmpty()) {
            return List.of(MuzTheme.banner("MUMU 战绩", normalizeNonBlank(fallbackName, "该玩家"), MuzTheme.muted("暂时还没有历史战绩")));
        }
        List<Component> lines = new ArrayList<>();
        int displayIndex = 1 + Math.max(0, page - 1) * pageSize;
        for (PlayerHistoryEntry entry : entries) {
            lines.add(historyTitle(displayIndex++, entry));
            lines.add(historySelfLine(entry));
            if ("DOUDIZHU".equalsIgnoreCase(entry.match().gameType())) {
                lines.add(historyLandlordLine(entry));
                lines.addAll(historyFarmerLines(entry));
            }
            lines.add(historyPersonalSettlementLine(entry));
            lines.add(historyParticipantSectionTitle());
            for (MatchParticipantRecord participant : entry.participants()) {
                if (entry.self() != null && Objects.equals(participant.playerId(), entry.self().playerId())) {
                    continue;
                }
                lines.add(historyParticipantLine(participant));
            }
            lines.add(historyTimeLocationLine(entry));
            lines.add(Component.empty());
        }
        return lines;
    }

    private PlaceholderTarget resolvePlaceholderTarget(String target) {
        for (GameTable table : tableManager.getTables()) {
            for (UUID seat : table.getSeats()) {
                if (table.displayName(seat).equalsIgnoreCase(target)) {
                    return PlaceholderTarget.doudizhu(table, seat);
                }
            }
        }
        Player online = Bukkit.getPlayerExact(target);
        if (online != null) {
            GameTable ddzTable = tableManager.getTableOf(online);
            if (ddzTable != null) {
                return PlaceholderTarget.doudizhu(ddzTable, online.getUniqueId());
            }
        }
        return null;
    }

    public HandGuiService getHandGuiService() {
        return handGuiService;
    }

    public DatabaseManager getDatabaseManager() {
        return databaseManager;
    }

    public NamespacedKey getCardIdKey() {
        return cardIdKey;
    }

    public NamespacedKey getTableNameKey() {
        return tableNameKey;
    }

    public NamespacedKey getInteractionActionKey() {
        return interactionActionKey;
    }

    public NamespacedKey getTablePlacerKey() {
        return tablePlacerKey;
    }

    public NamespacedKey getTablePlacerIdKey() {
        return tablePlacerIdKey;
    }

    public NamespacedKey getTablePlacerLevelKey() {
        return tablePlacerLevelKey;
    }

    public NamespacedKey getTableRemoverKey() {
        return tableRemoverKey;
    }

    public NamespacedKey getTableRemoverModeKey() {
        return tableRemoverModeKey;
    }

    public NamespacedKey getTableRemoverIdKey() {
        return tableRemoverIdKey;
    }

    public PhysicalTableManager getPhysicalTableManager() {
        return physicalTableManager;
    }

    /**
     * 麻将桌管理器；{@code integration.mahjong.enabled=false}（默认）时为 null。
     *
     * <p>共享保护链需要它来放行麻将入座 Interaction（见
     * {@code WorldTableInteractionListener.shouldCancelProtectedInteract}），
     * 因此调用方必须做 null 判断，不能假定麻将已启用。
     */
    public MahjongTableManager getMahjongTableManager() {
        return embeddedMahjongRuntime == null ? null : embeddedMahjongRuntime.tableManager();
    }

    public CraftEngineBundleExporter getCraftEngineBundleExporter() {
        return craftEngineBundleExporter;
    }

    public CraftEngineFurnitureService getCraftEngineFurnitureService() {
        return craftEngineFurnitureService;
    }

    public CraftEngineOffsetService getCraftEngineOffsetService() {
        return craftEngineOffsetService;
    }

    public PlayerHeadRenderer getPlayerHeadRenderer() {
        return playerHeadRenderer;
    }

    public boolean isShuttingDown() {
        return shuttingDown;
    }

    public boolean isVaultEconomyEnabled() {
        return ensureVaultEconomyRuntimeReady();
    }

    public boolean isChipPaymentEnabled() {
        return chipPaymentEnabled;
    }

    public void setChipPaymentEnabled(boolean enabled) {
        ensureEconomyMutationAllowed("切换筹码支付模式");
        chipPaymentEnabled = enabled;
        yamlConfig().set("economy.payment.use-chip", enabled);
        saveYamlConfig();
    }

    public ItemStack chipPaymentItem() {
        ItemStack stored = yamlConfig().getItemStack("economy.payment.chip-item-stack");
        return stored == null ? defaultChipItem() : stored.clone();
    }

    public void setChipPaymentItem(ItemStack itemStack) {
        ensureEconomyMutationAllowed("修改实体筹码模板");
        ItemStack copy = itemStack == null ? defaultChipItem() : itemStack.clone();
        copy.setAmount(1);
        yamlConfig().set("economy.payment.chip-item-stack", copy);
        saveYamlConfig();
    }

    public int getChipBalance(UUID playerId) {
        if (physicalChipService == null || playerId == null) {
            throw new IllegalStateException("实体筹码服务尚未就绪或玩家 UUID 为空");
        }
        return physicalChipService.balance(playerId);
    }

    public int setChipBalance(UUID playerId, int amount) {
        if (physicalChipService == null || playerId == null) {
            throw new IllegalStateException("实体筹码服务尚未就绪或玩家 UUID 为空");
        }
        return physicalChipService.setBalance(playerId, amount);
    }

    public int adjustChipBalance(UUID playerId, int delta) {
        if (physicalChipService == null || playerId == null) {
            throw new IllegalStateException("实体筹码服务尚未就绪或玩家 UUID 为空");
        }
        return physicalChipService.adjustBalance(playerId, delta);
    }

    public PhysicalChipService physicalChipService() {
        return physicalChipService;
    }

    public int roomEntryRequirement(TableLevel level) {
        if (isChipPaymentEnabled() && level != null && isRoomEconomyEnabled(level)) {
            return exactChipDelta(level, 1);
        }
        return Math.max(0, (int) Math.round(roomMultiplier(level)));
    }

    public boolean canAffordEntry(UUID playerId, TableLevel level) {
        if (playerId == null || level == null || level == TableLevel.FUN || !isRoomEconomyEnabled(level)) {
            return true;
        }
        if (isChipPaymentEnabled()) {
            Player player = Bukkit.getPlayer(playerId);
            if (player == null || !player.isOnline()) {
                return false;
            }
            try {
                return getChipBalance(playerId) >= roomEntryRequirement(level);
            } catch (RuntimeException exception) {
                getLogger().log(java.util.logging.Level.WARNING, "实体筹码资格查询失败: " + playerId, exception);
                return false;
            }
        }
        if (!isVaultEconomyEnabled()) {
            return false;
        }
        return vaultEconomyBridge.balance(Bukkit.getOfflinePlayer(playerId)) >= roomEntryRequirement(level);
    }

    public String insufficientEntryMessage(UUID playerId, TableLevel level) {
        if (level == null || level == TableLevel.FUN || !isRoomEconomyEnabled(level)) {
            return "";
        }
        int required = roomEntryRequirement(level);
        if (isChipPaymentEnabled()) {
            Player online = playerId == null ? null : Bukkit.getPlayer(playerId);
            if (online == null || !online.isOnline()) {
                return "实体筹码只支持在线玩家查询，请重新上线后再试。";
            }
            int balance = getChipBalance(playerId);
            if (balance < 0) {
                return "你已破产，当前欠筹码 " + Math.abs(balance) + "，还清后才能参与" + roomDisplayLabel(level) + "。";
            }
            return "进入" + roomDisplayLabel(level) + "至少需要 " + required + " 筹码。";
        }
        if (!isVaultEconomyEnabled()) {
            return "当前未挂钩经济系统，暂时不能参与" + roomDisplayLabel(level) + "。";
        }
        double balance = vaultEconomyBridge.balance(Bukkit.getOfflinePlayer(playerId));
        if (balance < 0.0) {
            return "你当前经济为负数，已视为破产，暂时不能参与" + roomDisplayLabel(level) + "。";
        }
        return "进入" + roomDisplayLabel(level) + "至少需要 " + required + " 金币。";
    }

    public boolean isRoomEconomyEnabled(TableLevel level) {
        RoomLevelProfile profile = roomLevelProfile(level);
        return profile.economyEnabled() && profile.multiplier() > 0.0;
    }

    public String roomDisplayLabel(TableLevel level) {
        return roomLevelProfile(level).label();
    }

    public double roomMultiplier(TableLevel level) {
        return roomLevelProfile(level).multiplier();
    }

    public String roomDisplayTag(TableLevel level) {
        RoomLevelProfile profile = roomLevelProfile(level);
        return profile.label() + " " + formatMultiplier(profile.multiplier());
    }

    public TableLevel defaultCreateRoomLevel() {
        TableLevel level = TableLevel.parse(yamlConfig().getString("room-levels.default-create-level", "low"));
        return level == null ? TableLevel.LOW : level;
    }

    public String paymentModeLabel() {
        return isChipPaymentEnabled() ? "筹码" : "金币";
    }

    public String vaultProviderSummary() {
        HookSnapshot snapshot = lastVaultHookSnapshot == null ? ensureVaultEconomyHookReadyInternal() : lastVaultHookSnapshot;
        return snapshot == null ? "未检测" : snapshot.detail();
    }

    public String vaultProvidersSummary() {
        if (vaultEconomyBridge == null) {
            return "无";
        }
        ensureVaultEconomyHookReadyInternal();
        return vaultEconomyBridge.availableProvidersDetail();
    }

    public String vaultPreferredProvidersSummary() {
        return String.join(" -> ", vaultPreferredProviderNames);
    }

    public String databaseStatusSummary() {
        if (databaseManager == null) {
            return "尚未连接";
        }
        String base = databaseManager.status();
        return persistedTableRestoreSummary == null || persistedTableRestoreSummary.isBlank()
            ? base
            : base + " | " + persistedTableRestoreSummary;
    }

    public boolean isRoomLevelEconomyConfigured(TableLevel level) {
        return roomLevelProfile(level).economyEnabled();
    }

    public void setRoomLevelMultiplier(TableLevel level, double multiplier) {
        ensureEconomyMutationAllowed("修改房间倍率");
        if (level == null) {
            return;
        }
        double normalized = Math.max(0.0, multiplier);
        yamlConfig().set("room-levels." + level.key() + ".multiplier", normalized);
        saveYamlConfig();
        loadRoomLevelProfiles();
        refreshAllPlacedTables();
    }

    public boolean toggleRoomLevelEconomy(TableLevel level) {
        ensureEconomyMutationAllowed("切换房间经济");
        if (level == null) {
            return false;
        }
        boolean next = !isRoomLevelEconomyConfigured(level);
        yamlConfig().set("room-levels." + level.key() + ".economy-enabled", next);
        saveYamlConfig();
        loadRoomLevelProfiles();
        refreshAllPlacedTables();
        return next;
    }

    public SettlementResult settleDoudizhuCurrency(TableLevel level, UUID playerId, int scoreDelta) {
        if (isChipPaymentEnabled()) {
            throw new IllegalStateException("实体筹码正常结算必须使用批量 transfer；禁止单人结算入口直接增删筹码。");
        }
        if (playerId == null || !isRoomEconomyEnabled(level)) {
            return currentRoomStatus(level, playerId);
        }
        if (scoreDelta == 0) {
            return currentRoomStatus(level, playerId);
        }
        if (!isDoudizhuRoomEconomyEnabled(level)) {
            return currentRoomStatus(level, playerId);
        }
        OfflinePlayer player = Bukkit.getOfflinePlayer(playerId);
        if (player == null) {
            return currentRoomStatus(level, playerId);
        }
        vaultEconomyBridge.ensureAccount(player);
        double amount = Math.abs((long) scoreDelta) * doudizhuCurrencyPerPoint(level);
        if (!Double.isFinite(amount) || amount <= 0.0) {
            throw new IllegalArgumentException("斗地主货币金额溢出或无效: 分差=" + scoreDelta + " 金币倍率=" + doudizhuCurrencyPerPoint(level));
        }
        if (scoreDelta > 0) {
            EconomyResponse response = vaultEconomyBridge.deposit(player, amount);
            if (!response.transactionSuccess()) {
                throw new IllegalStateException("Vault 入账失败: " + safeEconomyError(response.errorMessage));
            }
            return settlementResult(level, amount, 0.0, response.balance, false);
        }
        // 【为什么先读余额再算 actual，而不是直接 withdraw(amount)】：本项目允许「余额不够
        // 就扣光、差额记欠账」（debt 会落到 match_participants.debt_after）。直接扣全额的话，
        // Vault 会因余额不足整笔失败，玩家反而一分不扣——那是另一套语义，会破坏欠账功能。
        //
        // 代价是读与扣之间存在竞态：另一路交易在这两步之间动了余额，actual 就是过期值。
        // 下面用 withdraw 的真实返回值收口：Vault 因余额不足拒绝时不再抛异常，而是回退成
        // 「按最新余额重扣一次」，把竞态窗口压到一次重试内。
        double balance = Math.max(0.0, vaultEconomyBridge.balance(player));
        double actual = Math.min(balance, amount);
        double postBalance = balance;
        if (actual > 0.0) {
            EconomyResponse response = vaultEconomyBridge.withdraw(player, actual);
            if (!response.transactionSuccess()) {
                // 竞态重试：余额被并发改小时，按最新余额再扣一次。只重试一次——
                // 无限重试会在 Vault 持续报错时把结算线程卡死。
                double latest = Math.max(0.0, vaultEconomyBridge.balance(player));
                double retryAmount = Math.min(latest, amount);
                if (retryAmount <= 0.0) {
                    // 余额已被扣空，这笔全额记欠账，不抛异常
                    return settlementResult(level, 0.0, amount, latest, false);
                }
                response = vaultEconomyBridge.withdraw(player, retryAmount);
                if (!response.transactionSuccess()) {
                    throw new IllegalStateException("Vault 扣款失败: " + safeEconomyError(response.errorMessage));
                }
                actual = retryAmount;
            }
            postBalance = response.balance;
        }
        double debt = Math.max(0.0, amount - actual);
        return settlementResult(level, -actual, debt, postBalance, false);
    }

    public SettlementResult currentRoomStatus(TableLevel level, UUID playerId) {
        if (playerId == null) {
            return unavailableSettlement(isChipPaymentEnabled() ? "筹码" : "金币");
        }
        if (isChipPaymentEnabled()) {
            Player online = Bukkit.getPlayer(playerId);
            if (physicalChipService == null || online == null || !online.isOnline()) {
                return unavailableSettlement("筹码");
            }
            int postBalance = getChipBalance(playerId);
            return settlementResult(level, 0.0, 0.0, postBalance, true);
        }
        if (!isVaultEconomyEnabled()) {
            return settlementResult(level, 0.0, 0.0, 0.0, false);
        }
        OfflinePlayer player = Bukkit.getOfflinePlayer(playerId);
        return settlementResult(level, 0.0, 0.0, player == null ? 0.0 : vaultEconomyBridge.balance(player), false);
    }

    public boolean isDoudizhuRoomEconomyEnabled(TableLevel level) {
        return isRoomEconomyEnabled(level) && (isChipPaymentEnabled() || isVaultEconomyEnabled());
    }

    public double doudizhuCurrencyPerPoint(TableLevel level) {
        double value = vaultDoudizhuCurrencyPerPoint * roomMultiplier(level);
        if (!Double.isFinite(value) || value < 0.0) {
            throw new IllegalArgumentException("斗地主货币倍率溢出或无效: " + value);
        }
        return value;
    }

    /** 实体筹码结算只接受可精确表示的整数倍率，避免 double/round 破坏零和。 */
    public int exactChipDelta(TableLevel level, int scoreDelta) {
        if (scoreDelta == 0) {
            return 0;
        }
        BigDecimal multiplier = BigDecimal.valueOf(roomMultiplier(level));
        try {
            return multiplier.multiply(BigDecimal.valueOf(scoreDelta)).intValueExact();
        } catch (ArithmeticException exception) {
            throw new IllegalArgumentException("实体筹码倍率必须产生精确整数: 分差=" + scoreDelta + " 倍率=" + roomMultiplier(level), exception);
        }
    }

    /** 一局实体筹码只允许单次批量转移；服务失败时不产生任何欠账快照。 */
    public Map<UUID, SettlementResult> settlePhysicalChips(TableLevel level, Map<UUID, Integer> scoreDeltas) {
        if (!isChipPaymentEnabled() || scoreDeltas == null || scoreDeltas.isEmpty()) {
            return Map.of();
        }
        if (!isRoomEconomyEnabled(level)) {
            return Map.of();
        }
        Map<UUID, Integer> chipDeltas = new LinkedHashMap<>();
        for (Map.Entry<UUID, Integer> entry : scoreDeltas.entrySet()) {
            UUID playerId = Objects.requireNonNull(entry.getKey(), "实体筹码结算玩家为空");
            if (isRegisteredBot(playerId)) {
                throw new IllegalStateException("实体筹码付费房禁止机器人参与: " + playerId);
            }
            Player player = Bukkit.getPlayer(playerId);
            if (player == null || !player.isOnline()) {
                throw new IllegalStateException("实体筹码结算要求所有玩家在线: " + playerId);
            }
            chipDeltas.put(playerId, exactChipDelta(level, entry.getValue()));
        }
        Map<UUID, Integer> postBalances = physicalChipService.transfer(chipDeltas);
        Map<UUID, SettlementResult> result = new LinkedHashMap<>();
        for (Map.Entry<UUID, Integer> entry : scoreDeltas.entrySet()) {
            int delta = chipDeltas.get(entry.getKey());
            int postBalance = postBalances.getOrDefault(entry.getKey(), 0);
            result.put(entry.getKey(), settlementResult(level, delta, 0.0, postBalance, true));
        }
        return result;
    }

    /**
     * 结算抛异常时的兜底快照：把这一笔记成全额欠账。
     *
     * <p>【为什么需要它】：{@link #settleDoudizhuCurrency} 在 Vault 拒绝交易时抛异常，
     * 而一局有三个人。调用方（RoundSettlementCoordinator）必须能接住异常继续处理其余人，
     * 但又不能给失败者塞一个「delta=0、无欠账」的假成功快照——那样账面上看不出这人没结算。
     *
     * <p>取值口径：{@code delta} 记 0（钱确实没动），{@code debt} 记这笔应结金额的全额，
     * 余额取当前真实值。赢家结算失败也记 debt，表示「这笔该给的没给出去」。
     *
     * @param scoreDelta 原始分差，正数是该拿钱，负数是该扣钱
     */
    public SettlementResult failedSettlement(TableLevel level, UUID playerId, int scoreDelta) {
        if (isChipPaymentEnabled()) {
            return failedChipSettlement();
        }
        double owed = Math.abs((long) scoreDelta) * doudizhuCurrencyPerPoint(level);
        double balance = 0.0;
        try {
            SettlementResult current = currentRoomStatus(level, playerId);
            if (current.status() == SettlementStatus.SETTLED) {
                balance = current.postBalance();
            }
        } catch (RuntimeException ignored) {
            // 失败路径不能再次把整局结算带崩。
        }
        return new SettlementResult(0.0, owed, balance, true, true, "金币", SettlementStatus.FAILED);
    }

    private SettlementResult settlementResult(TableLevel level, double delta, double debt, double postBalance, boolean chipMode) {
        int requirement = roomEntryRequirement(level);
        boolean bankrupt = postBalance <= 0.0 || debt > 0.0;
        boolean insufficient = level != null && level != TableLevel.FUN && isRoomEconomyEnabled(level) && postBalance < requirement;
        return new SettlementResult(delta, debt, postBalance, bankrupt, insufficient, chipMode ? "筹码" : "金币", SettlementStatus.SETTLED);
    }

    public SettlementResult unavailableSettlement(String unitLabel) {
        return new SettlementResult(0.0, 0.0, 0.0, false, false, unitLabel, SettlementStatus.UNAVAILABLE);
    }

    public SettlementResult failedChipSettlement() {
        return new SettlementResult(0.0, 0.0, 0.0, false, false, "筹码", SettlementStatus.FAILED);
    }

    private String safeEconomyError(String raw) {
        return isBlank(raw) ? "经济插件未返回详细错误" : raw;
    }

    private void ensureEconomyMutationAllowed(String action) {
        if (tableManager != null && tableManager.getTables().stream().anyMatch(table -> table.getPhase() != GamePhase.LOBBY)) {
            throw new IllegalStateException(action + "只能在所有斗地主牌桌处于大厅时执行。");
        }
    }

    public String economyFingerprint(TableLevel level) {
        RoomLevelProfile profile = roomLevelProfile(level);
        return chipPaymentEnabled + "|" + chipPaymentItem() + "|" + profile.multiplier()
            + "|" + profile.economyEnabled() + "|" + vaultDoudizhuCurrencyPerPoint;
    }

    private RoomLevelProfile roomLevelProfile(TableLevel level) {
        TableLevel resolved = level == null ? TableLevel.FUN : level;
        RoomLevelProfile profile = roomLevelProfiles.get(resolved);
        if (profile != null) {
            return profile;
        }
        RoomLevelProfile fallback = roomLevelProfiles.get(TableLevel.FUN);
        if (fallback != null) {
            return fallback;
        }
        return new RoomLevelProfile(TableLevel.FUN, TableLevel.FUN.defaultLabel(), TableLevel.FUN.defaultMultiplier(), TableLevel.FUN.defaultEconomyEnabled());
    }

    private List<String> normalizedStringList(List<String> raw, List<String> fallback) {
        List<String> result = new ArrayList<>();
        List<String> source = raw == null || raw.isEmpty() ? fallback : raw;
        for (String value : source) {
            if (value != null && !value.isBlank()) {
                result.add(value.trim());
            }
        }
        return result.isEmpty() ? List.copyOf(fallback) : List.copyOf(result);
    }

    private ItemStack defaultChipItem() {
        ItemStack item = new ItemStack(Material.GRAVEL);
        ItemMeta meta = item.getItemMeta();
        meta.displayName(Component.text("筹码", NamedTextColor.GOLD).decoration(TextDecoration.ITALIC, false));
        item.setItemMeta(meta);
        return item;
    }

    public String formatMultiplier(double multiplier) {
        if (Math.abs(multiplier - Math.rint(multiplier)) < 0.0001) {
            return "x" + (long) Math.rint(multiplier);
        }
        return "x" + String.format(java.util.Locale.ROOT, "%.2f", multiplier);
    }

    private boolean ensureVaultEconomyRuntimeReady() {
        if (!vaultEconomyEnabled) {
            return false;
        }
        if (vaultEconomyBridge == null) {
            vaultEconomyBridge = new VaultEconomyBridge(this);
        }
        if (vaultEconomyBridge.isHooked()) {
            return true;
        }
        return ensureVaultEconomyHookReadyInternal().state() == HookState.HOOKED;
    }

    public boolean isCardHologramLabelsEnabled() {
        return cardHologramLabelsEnabled;
    }

    public boolean isDuplicateOnlyCardLabels() {
        return duplicateOnlyCardLabels;
    }

    public boolean isCardLabelsEnabledFor(UUID playerId) {
        return playerCardLabelSettings.getOrDefault(playerId, cardHologramLabelsEnabled);
    }

    public boolean toggleCardLabelsFor(UUID playerId) {
        return togglePlayerPreference(playerId, playerCardLabelSettings, cardHologramLabelsEnabled);
    }

    public boolean isSelectionSoundEnabledFor(UUID playerId) {
        return playerSelectionSoundSettings.getOrDefault(playerId, DEFAULT_SELECTION_SOUND_ENABLED);
    }

    public boolean toggleSelectionSoundFor(UUID playerId) {
        return togglePlayerPreference(playerId, playerSelectionSoundSettings, DEFAULT_SELECTION_SOUND_ENABLED);
    }

    public String getSelectionSoundSpecFor(UUID playerId) {
        return getSelectionSoundProfile(getPlayerSelectionSoundProfileIndex(playerId)).spec();
    }

    public void setSelectionSoundSpecFor(UUID playerId, String rawSpec) {
        setSelectionSoundProfileDefinition(getPlayerSelectionSoundProfileIndex(playerId), optionProfile("玩家自定义", rawSpec, true));
    }

    public void cyclePreviewGlowColor(UUID playerId) {
        playerHoverGlowColorSettings.remove(playerId);
        savePlayerSettings();
    }

    public void cycleSelectionGlowColor(UUID playerId) {
        playerSelectedGlowColorSettings.remove(playerId);
        savePlayerSettings();
    }

    public String previewGlowColorLabel(UUID playerId) {
        Integer packed = playerHoverGlowColorSettings.get(playerId);
        return packed == null ? "默认(全局)" : packedRgbLabel(packed);
    }

    public String selectionGlowColorLabel(UUID playerId) {
        Integer packed = playerSelectedGlowColorSettings.get(playerId);
        return packed == null ? "默认(全局)" : packedRgbLabel(packed);
    }

    public Color previewGlowColorFor(UUID playerId) {
        Integer packed = playerHoverGlowColorSettings.get(playerId);
        return packed == null ? hoverGlowColor() : unpackRgb(packed);
    }

    public Color selectionGlowColorFor(UUID playerId) {
        Integer packed = playerSelectedGlowColorSettings.get(playerId);
        return packed == null ? selectedGlowColor() : unpackRgb(packed);
    }

    public String hoverGlowColorLabel() {
        return rgbLabel(hoverGlowColor());
    }

    public String selectedGlowColorLabel() {
        return rgbLabel(selectedGlowColor());
    }

    public void setPlayerPreviewGlowColor(UUID playerId, Color color) {
        if (color == null) {
            playerHoverGlowColorSettings.remove(playerId);
        } else {
            Color other = selectionGlowColorFor(playerId);
            if (sameColor(color, other)) {
                throw new IllegalArgumentException("预览色不能和选择色相同。");
            }
            playerHoverGlowColorSettings.put(playerId, packRgb(color));
        }
        savePlayerSettings();
    }

    public void setPlayerSelectionGlowColor(UUID playerId, Color color) {
        if (color == null) {
            playerSelectedGlowColorSettings.remove(playerId);
        } else {
            Color other = previewGlowColorFor(playerId);
            if (sameColor(color, other)) {
                throw new IllegalArgumentException("选择色不能和预览色相同。");
            }
            playerSelectedGlowColorSettings.put(playerId, packRgb(color));
        }
        savePlayerSettings();
    }

    public void setHoverGlowColor(Color color) {
        if (color == null) {
            throw new IllegalArgumentException("预览发光颜色不能为空。");
        }
        if (sameColor(color, selectedGlowColor())) {
            throw new IllegalArgumentException("全局预览色不能和全局选择色相同。");
        }
        hoverGlowRed = color.getRed();
        hoverGlowGreen = color.getGreen();
        hoverGlowBlue = color.getBlue();
        yamlConfig().set("render.hover-glow.color", rgbLabel(color));
        yamlConfig().set("render.hover-glow.color.red", color.getRed());
        yamlConfig().set("render.hover-glow.color.green", color.getGreen());
        yamlConfig().set("render.hover-glow.color.blue", color.getBlue());
        saveYamlConfig();
        reloadVisualState(false, ReloadFeedback.silent());
    }

    public void setSelectedGlowColor(Color color) {
        if (color == null) {
            throw new IllegalArgumentException("预选发光颜色不能为空。");
        }
        if (sameColor(color, hoverGlowColor())) {
            throw new IllegalArgumentException("全局选择色不能和全局预览色相同。");
        }
        selectedGlowRed = color.getRed();
        selectedGlowGreen = color.getGreen();
        selectedGlowBlue = color.getBlue();
        yamlConfig().set("render.selected-glow.color", rgbLabel(color));
        yamlConfig().set("render.selected-glow.color.red", color.getRed());
        yamlConfig().set("render.selected-glow.color.green", color.getGreen());
        yamlConfig().set("render.selected-glow.color.blue", color.getBlue());
        saveYamlConfig();
        reloadVisualState(false, ReloadFeedback.silent());
    }

    public int getPlayerSelectionSoundProfileIndex(UUID playerId) {
        return clampProfileIndex(playerSelectionSoundProfileSettings.getOrDefault(playerId, 0));
    }

    public void setPlayerSelectionSoundProfileIndex(UUID playerId, int index) {
        savePlayerProfileChoice(playerSelectionSoundProfileSettings, playerId, index);
    }

    public int getPlayerPlayActionProfileIndex(UUID playerId) {
        return clampProfileIndex(playerPlayActionProfileSettings.getOrDefault(playerId, 0));
    }

    public void setPlayerPlayActionProfileIndex(UUID playerId, int index) {
        savePlayerProfileChoice(playerPlayActionProfileSettings, playerId, index);
    }

    public int getPlayerPlayActionProfileIndex(UUID playerId, PlayActionKind kind) {
        if (kind == null) {
            return getPlayerPlayActionProfileIndex(playerId);
        }
        EnumMap<PlayActionKind, Integer> settings = playerPlayActionKindProfileSettings.get(playerId);
        if (settings == null) {
            return getPlayerPlayActionProfileIndex(playerId);
        }
        return clampProfileIndex(settings.getOrDefault(kind, getPlayerPlayActionProfileIndex(playerId)));
    }

    public void setPlayerPlayActionProfileIndex(UUID playerId, PlayActionKind kind, int index) {
        if (kind == null) {
            setPlayerPlayActionProfileIndex(playerId, index);
            return;
        }
        int normalized = clampProfileIndex(index);
        EnumMap<PlayActionKind, Integer> settings = playerPlayActionKindProfileSettings.computeIfAbsent(playerId, ignored -> new EnumMap<>(PlayActionKind.class));
        if (normalized == getPlayerPlayActionProfileIndex(playerId)) {
            settings.remove(kind);
        } else {
            settings.put(kind, normalized);
        }
        if (settings.isEmpty()) {
            playerPlayActionKindProfileSettings.remove(playerId);
        }
        savePlayerSettings();
    }

    public List<OptionProfile> getSelectionSoundProfiles() {
        return List.copyOf(selectionSoundProfiles);
    }

    public OptionProfile getSelectionSoundProfile(int index) {
        return selectionSoundProfiles.get(clampProfileIndex(index));
    }

    public void setSelectionSoundProfileDefinition(int index, OptionProfile profile) {
        selectionSoundProfiles.set(clampProfileIndex(index), sanitizeSelectionSoundProfile(profile));
        saveOptionProfilesToStorage("selection-sound-profiles", selectionSoundProfiles);
    }

    public List<OptionProfile> getPlayActionProfiles() {
        return List.copyOf(playActionProfiles);
    }

    public OptionProfile getPlayActionProfile(int index) {
        return playActionProfiles.get(clampProfileIndex(index));
    }

    public void setPlayActionProfileDefinition(int index, OptionProfile profile) {
        playActionProfiles.set(clampProfileIndex(index), sanitizePlayActionProfile(profile));
        saveOptionProfilesToStorage("play-action-profiles", playActionProfiles);
    }

    public List<OptionProfile> getPlayActionProfiles(PlayActionKind kind) {
        return List.copyOf(playActionProfilesByKind.getOrDefault(kind, playActionProfiles));
    }

    public OptionProfile getPlayActionProfile(PlayActionKind kind, int index) {
        List<OptionProfile> profiles = playActionProfilesByKind.get(kind);
        if (profiles == null || profiles.isEmpty()) {
            return getPlayActionProfile(index);
        }
        return profiles.get(clampProfileIndex(index));
    }

    public void setPlayActionProfileDefinition(PlayActionKind kind, int index, OptionProfile profile) {
        List<OptionProfile> profiles = playActionProfilesByKind.computeIfAbsent(kind, ignored -> new ArrayList<>(playActionProfiles));
        while (profiles.size() < PLAYER_OPTION_PROFILE_COUNT) {
            profiles.add(defaultPlayActionProfile(profiles.size()));
        }
        profiles.set(clampProfileIndex(index), sanitizePlayActionProfile(profile));
        savePlayActionProfilesByKind(kind, profiles);
    }

    public OptionProfile resolvePlayActionProfile(UUID playerId, linmumua.doudizhu.model.CardPattern pattern) {
        PlayActionKind kind = PlayActionKind.fromPattern(pattern);
        if (kind == null) {
            return getPlayActionProfile(getPlayerPlayActionProfileIndex(playerId));
        }
        return getPlayActionProfile(kind, getPlayerPlayActionProfileIndex(playerId, kind));
    }

    public void resetPlayerVisualSettings(UUID playerId) {
        playerCardLabelSettings.remove(playerId);
        playerSelectionSoundSettings.remove(playerId);
        playerSelectionSoundProfileSettings.remove(playerId);
        playerPlayActionProfileSettings.remove(playerId);
        playerPlayActionKindProfileSettings.remove(playerId);
        playerHoverGlowColorSettings.remove(playerId);
        playerSelectedGlowColorSettings.remove(playerId);
        playerHandOffsets.remove(playerId);
        savePlayerSettings();
    }

    private GlowColorOption glowColorOption(int index) {
        return GLOW_COLOR_OPTIONS.get(clampGlowColorIndex(index));
    }

    private int clampGlowColorIndex(int index) {
        return Math.clamp(index, 0, GLOW_COLOR_OPTIONS.size() - 1);
    }

    private Color resolveGlowColor(int index, boolean previewColor) {
        GlowColorOption option = glowColorOption(index);
        if (option.color() != null) {
            return option.color();
        }
        return previewColor ? hoverGlowColor() : selectedGlowColor();
    }

    private boolean sameColor(Color left, Color right) {
        return left.getRed() == right.getRed()
            && left.getGreen() == right.getGreen()
            && left.getBlue() == right.getBlue();
    }

    public int registerBot(UUID botId, String tableName, BotGameType gameType) {
        Integer existing = botNumericIdsByUuid.get(botId);
        if (existing != null) {
            botHandlesByNumericId.put(existing, new BotHandle(existing, botId, tableName, gameType));
            return existing;
        }
        int numericId = 1;
        while (botHandlesByNumericId.containsKey(numericId)) {
            numericId++;
        }
        nextBotNumericId.set(Math.max(nextBotNumericId.get(), numericId + 1));
        botNumericIdsByUuid.put(botId, numericId);
        botHandlesByNumericId.put(numericId, new BotHandle(numericId, botId, tableName, gameType));
        return numericId;
    }

    public void unregisterBot(UUID botId) {
        Integer numericId = botNumericIdsByUuid.remove(botId);
        if (numericId != null) {
            botHandlesByNumericId.remove(numericId);
        }
    }

    public Integer getBotNumericId(UUID botId) {
        return botNumericIdsByUuid.get(botId);
    }

    public boolean isRegisteredBot(UUID botId) {
        return botId != null && botNumericIdsByUuid.containsKey(botId);
    }

    public BotHandle getBotHandle(int numericId) {
        return botHandlesByNumericId.get(numericId);
    }

    public BotHandle latestBotHandle() {
        return botHandlesByNumericId.keySet().stream()
            .max(Integer::compareTo)
            .map(botHandlesByNumericId::get)
            .orElse(null);
    }

    public List<BotHandle> getBotHandles() {
        return botHandlesByNumericId.keySet().stream()
            .sorted()
            .map(botHandlesByNumericId::get)
            .filter(Objects::nonNull)
            .toList();
    }

    public double getTableSpawnOffsetY() {
        return tableSpawnOffsetY;
    }

    public Location defaultTableAnchor(Player owner) {
        Location standingBlock = owner.getLocation().getBlock().getRelative(BlockFace.DOWN).getLocation();
        return standingBlock.add(0.5, tableSpawnOffsetY, 0.5);
    }

    public float getPrivateCardScale() {
        return privateCardScale;
    }



    public float getTableScale() {
        return tableScale;
    }

    public float getChairScale() {
        return chairScale;
    }

    public float getSmallTextScale() {
        return smallTextScale;
    }

    public float getStatusTextScale() {
        return statusTextScale;
    }

    public float getLabelTextScale() {
        return labelTextScale;
    }

    public float getStatusNameScale() {
        return statusNameScale;
    }

    public double getStatusNameLateralOffset() {
        return statusNameLateralOffset;
    }

    public double getStatusNameVerticalOffset() {
        return statusNameVerticalOffset;
    }

    public double getStatusNameDepthOffset() {
        return statusNameDepthOffset;
    }

    public float getSeatNameScale() {
        return seatNameScale;
    }

    public double getSeatNameLateralOffset() {
        return seatNameLateralOffset;
    }

    public double getSeatNameVerticalOffset() {
        return seatNameVerticalOffset;
    }

    public double getSeatNameDepthOffset() {
        return seatNameDepthOffset;
    }

    public float getEmptySeatScale() {
        return emptySeatScale;
    }

    public double getEmptySeatLateralOffset() {
        return emptySeatLateralOffset;
    }

    public double getEmptySeatVerticalOffset() {
        return emptySeatVerticalOffset;
    }

    public double getEmptySeatDepthOffset() {
        return emptySeatDepthOffset;
    }

    public float getSeatInfoScale() {
        return seatInfoScale;
    }

    public double getSeatInfoLateralOffset() {
        return seatInfoLateralOffset;
    }

    public double getSeatInfoVerticalOffset() {
        return seatInfoVerticalOffset;
    }

    public double getSeatInfoDepthOffset() {
        return seatInfoDepthOffset;
    }

    public Component phaseComponent(linmumua.doudizhu.game.GamePhase phase, NamedTextColor color) {
        return MuzTheme.named(phase == null ? "未知" : phase.displayName(), color).decoration(TextDecoration.ITALIC, false);
    }

    public float getPrivateCardWidthScale() {
        return privateCardWidthScale;
    }

    public float getPrivateCardHeightScale() {
        return privateCardHeightScale;
    }

    public float getPrivateCardDepthScale() {
        return privateCardDepthScale;
    }

    // Mahjong layout config getters
    public double getMahjongDisplayCenterXOffset() {
        return yamlConfig().getDouble("integration.mahjong.render.layout.display-center-x-offset", 0.0);
    }

    public double getMahjongDisplayCenterYOffset() {
        return yamlConfig().getDouble("integration.mahjong.render.layout.display-center-y-offset", 0.0);
    }

    public double getMahjongDisplayCenterZOffset() {
        return yamlConfig().getDouble("integration.mahjong.render.layout.display-center-z-offset", 0.0);
    }

    public double getMahjongTableVisualYOffset() {
        return yamlConfig().getDouble("integration.mahjong.render.layout.table-visual-y-offset", 0.0);
    }

    public double getMahjongSeatDistanceFromHandBase() {
        return yamlConfig().getDouble("integration.mahjong.render.layout.seat-distance-from-hand-base", 0.0);
    }

    public double getMahjongSeatBaseYOffset() {
        return yamlConfig().getDouble("integration.mahjong.render.layout.seat-base-y-offset", 0.0);
    }

    public double getMahjongSeatAnchorYOffset() {
        return yamlConfig().getDouble("integration.mahjong.render.layout.seat-anchor-y-offset", 0.0);
    }

    public double getMahjongSeatLabelDepthOffset() {
        return yamlConfig().getDouble("integration.mahjong.render.layout.seat-label-depth-offset", 0.0);
    }

    public double getMahjongSeatActionLabelYOffset() {
        return yamlConfig().getDouble("integration.mahjong.render.layout.seat-action-label-y-offset", 0.0);
    }

    public double getMahjongSeatSideActionHorizontalOffset() {
        return yamlConfig().getDouble("integration.mahjong.render.layout.seat-side-action-horizontal-offset", 0.0);
    }

    public double getMahjongCenterLabelYOffset() {
        return yamlConfig().getDouble("integration.mahjong.render.layout.center-label-y-offset", 0.0);
    }

    public double getMahjongSeatActionLabelScale() {
        return yamlConfig().getDouble("integration.mahjong.render.layout.seat-action-label-scale", 0.0);
    }

    public double getMahjongSeatActionHitboxWidth() {
        return yamlConfig().getDouble("integration.mahjong.render.layout.seat-action-hitbox-width", 0.0);
    }

    public double getMahjongSeatActionHitboxHeight() {
        return yamlConfig().getDouble("integration.mahjong.render.layout.seat-action-hitbox-height", 0.0);
    }

    public void openExternalMahjongEntry(Player player) {
        // Placeholder for external mahjong entry
        player.sendMessage("External mahjong entry not implemented yet.");
    }

    public boolean isMahjongIntegrationEnabled() {
        return yamlConfig().getBoolean("integration.mahjong.enabled", false);
    }

    public void persistMahjongTable(String id, Location center, UUID ownerUuid, String ownerName) {
        // Placeholder for persisting mahjong table
        getLogger().info("Persisting mahjong table: " + id + " at " + center);
    }

    public float getHoverCardScale() {
        return hoverCardScale;
    }

    public double getHoverCardLift() {
        return hoverCardLift;
    }

    public int getCardHoverInterpolationTicks() {
        return cardHoverInterpolationTicks;
    }

    public AnimationCurve cardHoverAnimationCurve() {
        return AnimationCurve.fromIndex(cardHoverAnimationTypeIndex);
    }

    public float getCardDepthOffset() {
        return cardDepthOffset;
    }

    public float getHandSpacing() {
        return handSpacing;
    }



    public float getSelectedCardScale() {
        return selectedCardScale;
    }

    public double getSelectedCardLift() {
        return selectedCardLift;
    }

    public boolean isHoverGlowEnabled() {
        return hoverGlowEnabled;
    }

    public Color hoverGlowColor() {
        return Color.fromRGB(
            Math.clamp(hoverGlowRed, 0, 255),
            Math.clamp(hoverGlowGreen, 0, 255),
            Math.clamp(hoverGlowBlue, 0, 255)
        );
    }

    public boolean isSelectedGlowEnabled() {
        return selectedGlowEnabled;
    }

    public Color selectedGlowColor() {
        return Color.fromRGB(
            Math.clamp(selectedGlowRed, 0, 255),
            Math.clamp(selectedGlowGreen, 0, 255),
            Math.clamp(selectedGlowBlue, 0, 255)
        );
    }

    public double getCardLabelHeight() {
        return cardLabelHeight;
    }

    public double getCardLabelLateralOffset() {
        return cardLabelLateralOffset;
    }

    public double getCardLabelDepthOffset() {
        return cardLabelDepthOffset;
    }

    public double getStatusHeight() {
        return statusHeight;
    }

    public double getPlayDetailHeight() {
        return playDetailHeight;
    }

    public double getButtonDistance() {
        return buttonDistance;
    }

    public double getButtonHeight() {
        return buttonHeight;
    }

    public double getTableDisplayHeight() {
        return tableDisplayHeight;
    }

    public double getTableColliderHeight() {
        return tableColliderHeight;
    }

    public double getChairBaseHeight() {
        return chairBaseHeight;
    }

    public double getChairColliderHeight() {
        return chairColliderHeight;
    }

    public double getChairSeatHeight() {
        return chairSeatHeight;
    }

    public double getChairInteractionHeight() {
        return chairInteractionHeight;
    }

    public double getChairLabelHeight() {
        return chairLabelHeight;
    }

    public double getChairRotationDegrees() {
        return chairRotationDegrees;
    }

    public double getChairVisualLateralOffset() {
        return chairVisualLateralOffset;
    }

    public double getChairVisualVerticalOffset() {
        return chairVisualVerticalOffset;
    }

    public double getChairHitboxLateralOffset() {
        return chairHitboxLateralOffset;
    }

    public double getChairHitboxVerticalOffset() {
        return chairHitboxVerticalOffset;
    }



    public double getButtonHitboxLateralOffset() {
        return buttonHitboxLateralOffset;
    }

    public double getButtonHitboxDepthOffset() {
        return buttonHitboxDepthOffset;
    }

    public double getButtonHitboxVerticalOffset() {
        return buttonHitboxVerticalOffset;
    }

    public int getStatusLineWidth() {
        return statusLineWidth;
    }

    public double getHandCenterDistance() {
        return handCenterDistance;
    }

    public double getHandCenterHeight() {
        return handCenterHeight;
    }

    public double getChairDistance() {
        return chairDistance;
    }

    public double getJoinLabelHeight() {
        return joinLabelHeight;
    }

    public float getJoinLabelScale() {
        return joinLabelScale;
    }

    public double getActionLabelHeight() {
        return actionLabelHeight;
    }

    public float getActionLabelScale() {
        return actionLabelScale;
    }

    public double getButtonFrontBaseDistance() {
        return buttonFrontBaseDistance;
    }

    public double getButtonSideBaseDistance() {
        return buttonSideBaseDistance;
    }

    public double getButtonDistanceFactor() {
        return buttonDistanceFactor;
    }

    public double getButtonSpacingScale() {
        return buttonSpacingScale;
    }

    public double getButtonArcSmallAngleDegrees() {
        return buttonArcSmallAngleDegrees;
    }

    public double getButtonArcLargeAngleDegrees() {
        return buttonArcLargeAngleDegrees;
    }

    public double getButtonArcSmallRadius() {
        return buttonArcSmallRadius;
    }

    public double getButtonArcLargeRadius() {
        return buttonArcLargeRadius;
    }

    public float getBgmVolume() {
        return bgmVolume;
    }

    public float getEffectVolume() {
        return effectVolume;
    }

    public int getTurnCountdownSeconds() {
        return turnCountdownSeconds;
    }

    public String getCountdownSoundSpec() {
        return countdownSoundSpec;
    }

    public void setCountdownSoundSpec(String rawSpec) {
        countdownSoundSpec = normalizeCountdownSoundSpec(rawSpec);
        yamlConfig().set("actionbar.countdown-sound", countdownSoundSpec);
        saveYamlConfig();
    }

    public String getUnreadyWarningSoundSpec() {
        return unreadyWarningSoundSpec;
    }

    public void setUnreadyWarningSoundSpec(String rawSpec) {
        unreadyWarningSoundSpec = normalizeCountdownSoundSpec(rawSpec);
        yamlConfig().set("actionbar.unready-warning-sound", unreadyWarningSoundSpec);
        saveYamlConfig();
    }

    public String getPlacementBlockedSoundSpec() {
        return placementBlockedSoundSpec;
    }

    public void setPlacementBlockedSoundSpec(String rawSpec) {
        placementBlockedSoundSpec = normalizeCountdownSoundSpec(rawSpec);
        yamlConfig().set("table.placement-blocked-sound", placementBlockedSoundSpec);
        saveYamlConfig();
    }

    public SelectionSound selectionSoundFor(UUID playerId) {
        return parseSelectionSound(getSelectionSoundSpecFor(playerId));
    }

    public SelectionSound selectionSoundForProfile(int index) {
        return parseSelectionSound(getSelectionSoundProfile(index).spec());
    }

    public ConfiguredSound countdownSound() {
        return parseConfiguredSound(countdownSoundSpec);
    }

    public ConfiguredSound unreadyWarningSound() {
        return parseConfiguredSound(unreadyWarningSoundSpec);
    }

    public ConfiguredSound placementBlockedWarningSound() {
        return parseConfiguredSound(placementBlockedSoundSpec);
    }

    public void playPlacementBlockedWarning(Player player) {
        if (player == null) {
            return;
        }
        ConfiguredSound sound = placementBlockedWarningSound();
        if (sound.volume() > 0.0f) {
            player.playSound(player.getLocation(), sound.key(), sound.volume(), sound.pitch());
        }
    }

    public int getBotActionDelayMinTicks() {
        return botActionDelayMinTicks;
    }

    public int getBotActionDelayMaxTicks() {
        return botActionDelayMaxTicks;
    }

    public int randomBotActionDelayTicks(java.util.Random random) {
        if (botActionDelayMaxTicks <= botActionDelayMinTicks) {
            return botActionDelayMinTicks;
        }
        return botActionDelayMinTicks + random.nextInt(botActionDelayMaxTicks - botActionDelayMinTicks + 1);
    }

    public int getHintGroupLimit() {
        return hintGroupLimit;
    }

    public double getDebugTableSpacing() {
        return debugTableSpacing;
    }

    public double getGlobalPrivateHandLateralOffset() {
        return globalPrivateHandLateralOffset;
    }

    public double getGlobalPrivateHandVerticalOffset() {
        return globalPrivateHandVerticalOffset;
    }

    public double getGlobalPrivateHandDepthOffset() {
        return globalPrivateHandDepthOffset;
    }

    public double getPlayerHandLateralOffset(UUID playerId) {
        return playerHandOffsets.getOrDefault(playerId, PlayerHandOffsets.ZERO).lateral();
    }

    public double getPlayerHandVerticalOffset(UUID playerId) {
        return playerHandOffsets.getOrDefault(playerId, PlayerHandOffsets.ZERO).vertical();
    }

    public double getPlayerHandDepthOffset(UUID playerId) {
        return playerHandOffsets.getOrDefault(playerId, PlayerHandOffsets.ZERO).depth();
    }

    public double getPlayerHandSpacingOffset(UUID playerId) {
        return playerHandOffsets.getOrDefault(playerId, PlayerHandOffsets.ZERO).spacing();
    }

    public double getPlayerPreviewScaleOffset(UUID playerId) {
        return playerHandOffsets.getOrDefault(playerId, PlayerHandOffsets.ZERO).previewScale();
    }

    public void adjustPlayerHandOffset(UUID playerId, HandOffsetAxis axis, double delta) {
        PlayerHandOffsets current = playerHandOffsets.getOrDefault(playerId, PlayerHandOffsets.ZERO);
        PlayerHandOffsets next = (switch (axis) {
            case LATERAL -> new PlayerHandOffsets(current.lateral() + delta, current.vertical(), current.depth(), current.spacing(), current.previewScale());
            case VERTICAL -> new PlayerHandOffsets(current.lateral(), current.vertical() + delta, current.depth(), current.spacing(), current.previewScale());
            case DEPTH -> new PlayerHandOffsets(current.lateral(), current.vertical(), current.depth() + delta, current.spacing(), current.previewScale());
            case SPACING -> new PlayerHandOffsets(current.lateral(), current.vertical(), current.depth(), current.spacing() + delta, current.previewScale());
            case PREVIEW_SCALE -> new PlayerHandOffsets(current.lateral(), current.vertical(), current.depth(), current.spacing(), current.previewScale() + delta);
        }).normalized();
        if (next.isZero()) {
            playerHandOffsets.remove(playerId);
        } else {
            playerHandOffsets.put(playerId, next);
        }
        savePlayerSettings();
    }

    public void resetPlayerHandOffsets(UUID playerId) {
        playerHandOffsets.remove(playerId);
        savePlayerSettings();
    }

    public String getTableItemModelId() {
        return tableItemModelId;
    }

    public List<String> getTableFurnitureItemIdCandidates() {
        ItemStack configured = getConfiguredFurnitureItem(FurnitureType.TABLE);
        if (configured != null) {
            linmumua.doudizhu.compat.CraftEngineFurnitureService.ResolvedItem resolved =
                craftEngineFurnitureService == null ? null : craftEngineFurnitureService.resolveCustomItem(configured);
            if (resolved != null) {
                return List.of(resolved.itemId());
            }
            List<String> configuredCandidates = furnitureItemIdCandidates(configured, null, TABLE_FURNITURE_ID);
            if (!configuredCandidates.isEmpty()) {
                return configuredCandidates;
            }
        }
        return furnitureItemIdCandidates(tableItemModelId, TABLE_FURNITURE_ID);
    }

    public String getTableDisplayName() {
        ItemStack configured = getConfiguredFurnitureItem(FurnitureType.TABLE);
        return configured == null ? tableDisplayName : configured.getType().name();
    }

    /**
     * 桌子家具 id，供渲染层拼装模型键使用。
     * @return 家具 id
     */
    public static String tableFurnitureId() {
        return TABLE_FURNITURE_ID;
    }

    /**
     * 椅子家具 id，供渲染层拼装模型键使用。
     * @return 家具 id
     */
    public static String chairFurnitureId() {
        return CHAIR_FURNITURE_ID;
    }

    public String getChairItemModelId() {
        return chairItemModelId;
    }

    public List<String> getChairFurnitureItemIdCandidates() {
        ItemStack configured = getConfiguredFurnitureItem(FurnitureType.CHAIR);
        if (configured != null) {
            linmumua.doudizhu.compat.CraftEngineFurnitureService.ResolvedItem resolved =
                craftEngineFurnitureService == null ? null : craftEngineFurnitureService.resolveCustomItem(configured);
            if (resolved != null) {
                return List.of(resolved.itemId());
            }
            List<String> configuredCandidates = furnitureItemIdCandidates(configured, null, CHAIR_FURNITURE_ID);
            if (!configuredCandidates.isEmpty()) {
                return configuredCandidates;
            }
        }
        return furnitureItemIdCandidates(chairItemModelId, CHAIR_FURNITURE_ID);
    }

    public boolean canUseHeldItemAsChairFurniture(ItemStack itemStack) {
        if (itemStack == null || itemStack.getType().isAir()) {
            return false;
        }
        if (craftEngineFurnitureService != null && craftEngineFurnitureService.resolveCustomItem(itemStack) != null) {
            return true;
        }
        return !furnitureItemIdCandidates(itemStack, null, CHAIR_FURNITURE_ID).isEmpty() || itemStack.getType().isBlock();
    }

    public String getChairDisplayName() {
        ItemStack configured = getConfiguredFurnitureItem(FurnitureType.CHAIR);
        return configured == null ? chairDisplayName : configured.getType().name();
    }

    public TableMode getPreferredMode(UUID playerId) {
        return playerPreferredModes.getOrDefault(playerId, TableMode.DOUDIZHU);
    }

    public void setPreferredMode(UUID playerId, TableMode mode) {
        if (mode == null || mode == TableMode.DOUDIZHU) {
            playerPreferredModes.remove(playerId);
        } else {
            playerPreferredModes.put(playerId, mode);
        }
    }

    public void reloadPluginState() {
        reloadPluginState(null);
    }

    public void reloadPluginState(CommandSender initiator) {
        reloadVisualState(true, ReloadFeedback.create(this, initiator));
    }

    /**
     * Web 编辑器保存配置后调用的最小重载入口；只串起现有运行时同步，不承载表单解析逻辑。
     */
    public void reloadVisualStateFromWebEditor() {
        reloadVisualState(false, ReloadFeedback.silent());
    }

    /** 轻量同步 HUD 运行态：仅重读正式 Trick HUD 设置；Hotbar 正式运行期链路已退役。 */
    public void reloadHudRuntimeState() {
        reloadTrickHudSettings();
    }

    /**
     * Debug Web 专用 HUD 应用入口：只刷新 HUD 服务，不重载完整视觉状态或重建牌桌。
     *
     * <p>调用方必须已经完成异步 config.yml/overlay 写盘；这里仅在主线程读取共享配置并应用运行态。
     * 与 Web coordinator 共用配置锁，避免主线程 reload 或管理菜单在 Web 写盘期间观察到半提交状态。
     * 该锁只覆盖 Web 专用边界，旧的非 Web 配置入口仍可能在自身事务外修改配置。
     */
    public void applyHudRuntimeStateFromWeb() {
        synchronized (hudWebConfigLock) {
            reloadTrickHudSettings();
        }
    }

    public void scheduleAutomaticReloadSeries(String reason, long... delayTicks) {
        if (shuttingDown) {
            return;
        }
        if (delayTicks == null || delayTicks.length == 0) {
            return;
        }
        getLogger().info("已计划自动重载 MUZ: reason=" + reason + " delays=" + java.util.Arrays.toString(delayTicks));
        for (int index = 0; index < delayTicks.length; index++) {
            final int pass = index + 1;
            final long delay = Math.max(1L, delayTicks[index]);
            scheduler().runLater(delay, () -> {
                if (shuttingDown) {
                    return;
                }
                getLogger().info("执行自动重载 MUZ: reason=" + reason + " pass=" + pass);
                reloadPluginState();
            });
        }
    }

    public void scheduleVisualWarmupRebuilds(String reason, long... delayTicks) {
        if (shuttingDown || delayTicks == null || delayTicks.length == 0) {
            return;
        }
        getLogger().info("已计划视觉预热重建: reason=" + reason + " delays=" + java.util.Arrays.toString(delayTicks));
        for (int index = 0; index < delayTicks.length; index++) {
            final int pass = index + 1;
            final long delay = Math.max(1L, delayTicks[index]);
            scheduler().runLater(delay, () -> {
                if (shuttingDown) {
                    return;
                }
                // HARD-CODED VISUAL REBUILD:
                // This must stay here even if reload already rebuilt tables once.
                // Some startup cases still miss TextDisplay or furniture visuals on the first rebuild pass.
                getLogger().info("执行视觉预热重建: reason=" + reason + " pass=" + pass);
                attemptPersistedTableRestore();
                // 门禁用 hasPlacedOrRebuildingTables 而不是 placedTableCount：重建已改为异步 stage 流水线，
                // 只看瞬时 placed 数量会在"恢复/重建尚未提交"的窗口里误判成没有桌可重建，把后续 pass 整批跳过。
                // 该判据同时覆盖"已放置"与"重建在飞"，多 pass 语义保持不变。
                if (physicalTableManager != null && physicalTableManager.hasPlacedOrRebuildingTables()) {
                    // 机制变更：重建已改为异步 stage 流水线。修复扫描会读改写 placedTables，必须等
                    // 重建整批收口完成（完成线程是 global lane，与扫描同 lane）再执行；照旧原地串行会在
                    // 旧实体刚被清掉、新桌尚未生成时扫出一整批"不完整桌"并重复重建。
                    physicalTableManager.rebuildAllTables()
                        .thenCompose(ignored -> physicalTableManager.repairIncompleteTables(
                            reason + "-ddz-pass-" + pass))
                        .exceptionally(failure -> {
                            getLogger().warning("视觉预热重建失败: reason=" + reason + " pass=" + pass
                                + "，原因=" + failure.getMessage());
                            return null;
                        });
                }

            });
        }
    }

    public void setFurnitureDisplayItem(FurnitureType type, ItemStack itemStack) {
        String base = type.configBasePath();
        ItemStack copy = itemStack == null ? null : itemStack.clone();
        if (copy != null) {
            copy.setAmount(1);
        }
        yamlConfig().set(base + ".item-stack", copy);
        yamlConfig().set(base + ".namespace", null);
        yamlConfig().set(base + ".model-path", null);
        saveYamlConfig();
        reloadVisualState(false, ReloadFeedback.silent());
    }

    public void resetFurnitureDisplayItem(FurnitureType type) {
        String base = type.configBasePath();
        yamlConfig().set(base + ".item-stack", null);
        yamlConfig().set(base + ".item-model", type.defaultItemModelId());
        yamlConfig().set(base + ".item-name", type.defaultDisplayName());
        saveYamlConfig();
        reloadVisualState(false, ReloadFeedback.silent());
    }

    public ItemStack getConfiguredFurnitureItem(FurnitureType type) {
        ItemStack stored = yamlConfig().getItemStack(type.configBasePath() + ".item-stack");
        return stored == null ? null : stored.clone();
    }

    public ItemStack createDoudizhuTablePlacerItem(String tableId, TableLevel level) {
        return createTablePlacerItem(TableMode.DOUDIZHU, tableId, level);
    }

    public ItemStack createTablePlacerItem(TableMode mode, String tableId, TableLevel level) {
        String normalizedId = normalizeNonBlank(tableId, "1");
        TableLevel normalizedLevel = level == null ? TableLevel.FUN : level;
        TableMode normalizedMode = mode == null ? TableMode.DOUDIZHU : mode;
        ItemStack item = new ItemStack(Material.CARTOGRAPHY_TABLE);
        ItemMeta meta = item.getItemMeta();
        meta.displayName(MuzTheme.accent("MUZ 放桌器 · " + normalizedId + " 号桌"));
        meta.lore(List.of(
            MuzTheme.muted("玩法 · " + tableModeLabel(normalizedMode)),
            MuzTheme.muted("场次 · " + roomDisplayTag(normalizedLevel)),
            MuzTheme.muted("右键一次预览，再右键放置。")
        ));
        meta.addItemFlags(ItemFlag.HIDE_ATTRIBUTES);
        meta.getPersistentDataContainer().set(tablePlacerKey, PersistentDataType.STRING, tableModeKey(normalizedMode));
        meta.getPersistentDataContainer().set(tablePlacerIdKey, PersistentDataType.STRING, normalizedId);
        meta.getPersistentDataContainer().set(tablePlacerLevelKey, PersistentDataType.STRING, normalizedLevel.key());
        item.setItemMeta(meta);
        return item;
    }

    public ItemStack createTableRemoverItem(TableMode mode, String tableId) {
        TableMode normalizedMode = mode == null ? TableMode.DOUDIZHU : mode;
        String normalizedId = normalizeNonBlank(tableId, "1");
        ItemStack item = new ItemStack(Material.STICK);
        ItemMeta meta = item.getItemMeta();
        meta.displayName(MuzTheme.warning("MUZ 拆桌棍"));
        meta.lore(List.of(
            MuzTheme.muted("玩法 · " + tableModeLabel(normalizedMode)),
            MuzTheme.muted("牌桌 ID · " + normalizedId),
            MuzTheme.muted("只能拆这一张桌子，对准后右键两次拆掉。")
        ));
        meta.addItemFlags(ItemFlag.HIDE_ATTRIBUTES);
        meta.getPersistentDataContainer().set(tableRemoverKey, PersistentDataType.STRING, "table");
        meta.getPersistentDataContainer().set(tableRemoverModeKey, PersistentDataType.STRING, tableModeKey(normalizedMode));
        meta.getPersistentDataContainer().set(tableRemoverIdKey, PersistentDataType.STRING, normalizedId);
        item.setItemMeta(meta);
        return item;
    }

    /**
     * 记牌器物品：拿在手上才显示 HUD 的记牌器行（第三行）。
     *
     * <p>【为什么做成物品而不是纯配置】：config 的 {@code trick-hud.counter.enabled} 是
     * 服务器级开关，决定这功能"存不存在"；这件物品是玩家级开关，决定"这一局我要不要看"。
     * 记牌降低难度，同桌里想看和不想看的人得能各自作数，不能靠一个全局开关一刀切。
     */
    public ItemStack createCounterItem() {
        ItemStack item = new ItemStack(Material.PAPER);
        ItemMeta meta = item.getItemMeta();
        meta.displayName(MuzTheme.accent("MUZ 记牌器"));
        meta.lore(List.of(
            MuzTheme.muted("带在身上时，HUD 底部显示每个点数累计已出几张。"),
            MuzTheme.muted("放在背包任意位置都生效，不影响同桌其他人。"),
            MuzTheme.muted("想临时关闭时移出背包即可。")
        ));
        meta.addItemFlags(ItemFlag.HIDE_ATTRIBUTES);
        meta.getPersistentDataContainer().set(counterItemKey, PersistentDataType.STRING, "counter");
        item.setItemMeta(meta);
        return item;
    }

    public boolean isCounterItem(ItemStack itemStack) {
        return hasStringMarker(itemStack, counterItemKey, "counter");
    }

    /** HUD 调试棒：右键循环三行可见组合，Shift+右键由调用方设置为全关。 */
    public ItemStack createHudDebugStickItem() {
        ItemStack item = new ItemStack(Material.BLAZE_ROD);
        ItemMeta meta = item.getItemMeta();
        meta.displayName(MuzTheme.warning("MUZ HUD 调试棒"));
        meta.lore(List.of(
            MuzTheme.muted("右键 · 循环牌行/头像行/记牌行的 7 种可见组合"),
            MuzTheme.muted("Shift+右键 · 三行全关"),
            MuzTheme.muted("只改你自己看到的 HUD，不写配置文件。")
        ));
        meta.addItemFlags(ItemFlag.HIDE_ATTRIBUTES);
        meta.getPersistentDataContainer().set(hudDebugStickKey, PersistentDataType.STRING, "hud");
        item.setItemMeta(meta);
        return item;
    }

    public static final int HUD_ROW_CARD = 1;
    public static final int HUD_ROW_AVATAR = 2;
    public static final int HUD_ROW_COUNTER = 4;
    private static final int HUD_ROWS_ALL = HUD_ROW_CARD | HUD_ROW_AVATAR | HUD_ROW_COUNTER;

    public int cycleHudRowOverride(UUID playerId) {
        int next = nextHudRows(hudRowOverrides.getOrDefault(playerId, HUD_ROWS_ALL));
        setHudRowOverride(playerId, next);
        return next;
    }

    public static int nextHudRows(int current) {
        return current % 7 + 1;
    }

    public void setHudRowOverride(UUID playerId, int rows) {
        if (playerId == null) {
            return;
        }
        hudRowOverrides.put(playerId, rows & HUD_ROWS_ALL);
    }

    public void hideAllHudRows(UUID playerId) {
        setHudRowOverride(playerId, 0);
    }

    public int hudRowOverride(UUID playerId) {
        return hudRowOverrides.getOrDefault(playerId, HUD_ROWS_ALL);
    }

    public void clearHudRowOverride(UUID playerId) {
        if (playerId != null) {
            hudRowOverrides.remove(playerId);
        }
    }

    public TrickHudPreview trickHudPreview() {
        TrickHudPreview local = trickHudPreview;
        if (local == null) {
            synchronized (this) {
                local = trickHudPreview;
                if (local == null) {
                    local = new TrickHudPreview(this);
                    trickHudPreview = local;
                }
            }
        }
        return local;
    }

    /** 显示当前玩家的 HUD 调试预览；实际绘制仍复用正式 TrickHudService。 */
    public void showHudDebugPreview(Player player) {
        if (player != null) {
            trickHudPreview().show(player);
        }
    }

    /** 把调试棒的位掩码转换为玩家可读的行名称。 */
    public static String describeHudRows(int rows) {
        if ((rows & HUD_ROWS_ALL) == 0) {
            return "无";
        }
        List<String> names = new ArrayList<>(3);
        if ((rows & HUD_ROW_CARD) != 0) {
            names.add("牌行");
        }
        if ((rows & HUD_ROW_AVATAR) != 0) {
            names.add("头像行");
        }
        if ((rows & HUD_ROW_COUNTER) != 0) {
            names.add("记牌行");
        }
        return String.join("、", names);
    }

    public boolean isHudDebugStick(ItemStack itemStack) {
        return hasStringMarker(itemStack, hudDebugStickKey, "hud");
    }

    /**
     * 这名玩家背包里是否有记牌器。
     *
     * <p>【为什么不看主手】：打牌要右键选牌、左键出牌，主手被占住就没法顺手操作。
     * 只要拿到过这件物品就一直生效，想临时关闭时移出背包即可。
     *
     * <p>遍历整个背包（含副手与盔甲位）：getStorageContents 漏掉副手，
     * 玩家把记牌器换到副手会莫名失效。
     */
    public boolean hasCounterItem(Player player) {
        for (ItemStack item : player.getInventory().getContents()) {
            if (isCounterItem(item)) {
                return true;
            }
        }
        return isCounterItem(player.getInventory().getItemInOffHand());
    }

    private boolean hasStringMarker(ItemStack itemStack, NamespacedKey key, String expected) {
        if (itemStack == null || itemStack.getType().isAir() || !itemStack.hasItemMeta()) {
            return false;
        }
        String marker = itemStack.getItemMeta().getPersistentDataContainer().get(key, PersistentDataType.STRING);
        return expected.equalsIgnoreCase(marker);
    }

    public NamespacedKey getCounterItemKey() {
        return counterItemKey;
    }

    public NamespacedKey getHudDebugStickKey() {
        return hudDebugStickKey;
    }

    public boolean isTablePlacer(ItemStack itemStack) {
        if (itemStack == null || itemStack.getType().isAir() || !itemStack.hasItemMeta()) {
            return false;
        }
        ItemMeta meta = itemStack.getItemMeta();
        String marker = meta.getPersistentDataContainer().get(tablePlacerKey, PersistentDataType.STRING);
        return "doudizhu".equalsIgnoreCase(marker);
    }

    public boolean isDoudizhuTablePlacer(ItemStack itemStack) {
        return tablePlacerMode(itemStack) == TableMode.DOUDIZHU;
    }

    public String doudizhuTablePlacerId(ItemStack itemStack) {
        if (!isTablePlacer(itemStack)) {
            return "";
        }
        ItemMeta meta = itemStack.getItemMeta();
        return normalizeNonBlank(meta.getPersistentDataContainer().get(tablePlacerIdKey, PersistentDataType.STRING), "");
    }

    public TableLevel doudizhuTablePlacerLevel(ItemStack itemStack) {
        if (!isTablePlacer(itemStack)) {
            return TableLevel.FUN;
        }
        ItemMeta meta = itemStack.getItemMeta();
        TableLevel parsed = TableLevel.parse(meta.getPersistentDataContainer().get(tablePlacerLevelKey, PersistentDataType.STRING));
        return parsed == null ? TableLevel.FUN : parsed;
    }

    public TableMode tablePlacerMode(ItemStack itemStack) {
        if (itemStack == null || itemStack.getType().isAir() || !itemStack.hasItemMeta()) {
            return null;
        }
        ItemMeta meta = itemStack.getItemMeta();
        String marker = meta.getPersistentDataContainer().get(tablePlacerKey, PersistentDataType.STRING);
        if ("doudizhu".equalsIgnoreCase(marker)) {
            return TableMode.DOUDIZHU;
        }
        return null;
    }

    public boolean isDoudizhuTableRemover(ItemStack itemStack) {
        if (itemStack == null || itemStack.getType().isAir() || !itemStack.hasItemMeta()) {
            return false;
        }
        ItemMeta meta = itemStack.getItemMeta();
        String marker = meta.getPersistentDataContainer().get(tableRemoverKey, PersistentDataType.STRING);
        return "table".equalsIgnoreCase(marker);
    }

    public TableMode tableRemoverMode(ItemStack itemStack) {
        if (!isDoudizhuTableRemover(itemStack)) {
            return null;
        }
        ItemMeta meta = itemStack.getItemMeta();
        String marker = meta.getPersistentDataContainer().get(tableRemoverModeKey, PersistentDataType.STRING);
        if ("doudizhu".equalsIgnoreCase(marker)) {
            return TableMode.DOUDIZHU;
        }
        return null;
    }

    public String tableRemoverId(ItemStack itemStack) {
        if (!isDoudizhuTableRemover(itemStack)) {
            return "";
        }
        ItemMeta meta = itemStack.getItemMeta();
        return normalizeNonBlank(meta.getPersistentDataContainer().get(tableRemoverIdKey, PersistentDataType.STRING), "");
    }

    public String tableModeKey(TableMode mode) {
        return "doudizhu";
    }

    public String tableModeLabel(TableMode mode) {
        return "斗地主";
    }

    /**
     * 读一项管理设置当前生效的小数值。
     *
     * 归零项在 config.yml 里写 0 表示"用默认"，菜单必须显示默认值而不是 0。
     * 否则玩家打开菜单看到的基准是 0，按一下调整就从 0 起步，等于把调好的值清掉。
     *
     * @param setting 设置项
     * @return 生效值
     */
    private double adminSettingCurrentValue(AdminSetting setting) {
        double raw = yamlConfig().getDouble(setting.path(), setting.defaultValue());
        return setting.zeroIsRealValue() ? raw : zeroMeansDefault(raw, setting.defaultValue());
    }

    /**
     * {@link #adminSettingCurrentValue(AdminSetting)} 的整数版本。
     *
     * @param setting 设置项
     * @return 生效值
     */
    private int adminSettingCurrentInt(AdminSetting setting) {
        int fallback = (int) setting.defaultValue();
        int raw = yamlConfig().getInt(setting.path(), fallback);
        return setting.zeroIsRealValue() ? raw : zeroMeansDefault(raw, fallback);
    }

    public void adjustAdminSetting(AdminSetting setting, boolean increase, int multiplier) {
        adjustAdminSetting(setting, increase, multiplier, Double.NaN);
    }

    public void adjustAdminSetting(AdminSetting setting, boolean increase, int multiplier, double stepOverride) {
        if (setting.booleanSetting()) {
            yamlConfig().set(setting.path(), !yamlConfig().getBoolean(setting.path(), setting.defaultBoolean()));
        } else if (setting.integerSetting()) {
            int current = adminSettingCurrentInt(setting);
            int delta = (int) setting.step() * Math.max(1, multiplier);
            // 不再按 min/max 夹紧：调整范围已按用户要求放开，声明值只留作默认与步长的参考。
            int next = current + (increase ? delta : -delta);
            yamlConfig().set(setting.path(), next);
        } else {
            double current = normalizeAdminCurrentValue(
                setting,
                adminSettingCurrentValue(setting)
            );
            double next = linmumua.doudizhu.config.AdminSettingArithmetic.nextValue(
                current,
                stepOverride,
                adminSettingStep(setting),
                multiplier,
                increase,
                // 传 ±无穷等于不夹紧。nextValue 本身保留夹紧能力（它是纯函数、按传入区间办事），
                // 这里只是不再把声明的 min/max 当硬边界，调整范围因此放开。
                Double.NEGATIVE_INFINITY,
                Double.POSITIVE_INFINITY
            );
            // 方块家具那几项要吸附到整格/90 度，所以仍然过一遍专用归一化
            next = normalizeAdminStoredValue(setting, next);
            yamlConfig().set(setting.path(), next);
            if (setting == AdminSetting.TABLE_SPAWN_OFFSET_Y) {
                saveYamlConfig();
                loadRenderSettings();
                double shift = next - current;
                if (physicalTableManager != null) {
                    // 机制变更：位移重建现在是异步流水线。失败必须记录日志，不能静默丢弃，
                    // 否则整批桌子会停在"未放置"状态而看不出原因。
                    physicalTableManager.shiftAllAnchors(shift).exceptionally(failure -> {
                        getLogger().warning("牌桌锚点位移重建失败: deltaY=" + shift
                            + "，原因=" + failure.getMessage());
                        return null;
                    });
                }

                return;
            }
        }
        saveYamlConfig();
        reloadVisualState(false, ReloadFeedback.silent());
    }

    public String adminSettingValue(AdminSetting setting) {
        if (setting == AdminSetting.HOVER_CARD_ANIMATION_TYPE) {
            return cardHoverAnimationCurve().label();
        }
        if (setting.booleanSetting()) {
            return yamlConfig().getBoolean(setting.path(), setting.defaultBoolean()) ? "开启" : "关闭";
        }
        if (setting.integerSetting()) {
            return String.valueOf(adminSettingCurrentInt(setting));
        }
        if (setting == AdminSetting.TABLE_SPAWN_OFFSET_Y && usesBlockTablePlacement()) {
            return String.valueOf((int) Math.round(normalizeBlockTableOffset(adminSettingCurrentValue(setting))));
        }
        if (setting == AdminSetting.CHAIR_ROTATION_DEGREES && usesBlockChairPlacement()) {
            return String.valueOf((int) Math.round(normalizeBlockChairRotation(adminSettingCurrentValue(setting))));
        }
        if (setting == AdminSetting.CHAIR_DISTANCE && usesBlockChairPlacement()) {
            return String.valueOf((int) Math.round(normalizeBlockChairDistance(adminSettingCurrentValue(setting))));
        }
        // 小数位数跟着该项的实际步长走。
        //
        // 固定 0.0001 步长的压层项如果只显示两位小数，
        // 调一次界面上根本看不出变化，玩家会以为按钮坏了。
        // 所以按步长推算需要几位：0.0001 → 4 位，0.01 → 2 位。
        return String.format(
            java.util.Locale.ROOT,
            "%." + adminSettingDisplayDecimals(setting) + "f",
            adminSettingCurrentValue(setting)
        );
    }

    /**
     * 算出某项设置在界面上该显示几位小数。
     *
     * 以该项实际生效的步长为准：步长比 0.001 还细就显示 4 位，
     * 比 0.01 细显示 3 位，其余一律 2 位。
     *
     * @param setting 目标设置
     * @return 小数位数
     */
    private int adminSettingDisplayDecimals(AdminSetting setting) {
        double step = setting.hasFixedStep() ? setting.fixedStep() : setting.step();
        if (step < 0.001) {
            return 4;
        }
        if (step < 0.01) {
            return 3;
        }
        return 2;
    }

    public Component playerIdentityComponent(UUID playerId, String fallbackText, NamedTextColor fallbackColor) {
        String playerName = resolvePlayerName(playerId);
        if (playerName != null && !playerName.isBlank()) {
            Component head = createPlayerHeadComponent(playerId, playerName);
            Component name = MuzTheme.named(" " + playerName, fallbackColor)
                .decoration(TextDecoration.ITALIC, false);
            return head.append(name).decoration(TextDecoration.ITALIC, false);
        }
        return MuzTheme.named(normalizeNonBlank(fallbackText, "未知玩家"), fallbackColor)
            .decoration(TextDecoration.ITALIC, false);
    }

    public Component playerHeadComponent(UUID playerId, String fallbackText, NamedTextColor fallbackColor) {
        String playerName = resolvePlayerName(playerId);
        if (playerName != null && !playerName.isBlank()) {
            return createPlayerHeadComponent(playerId, playerName);
        }
        return MuzTheme.named(normalizeNonBlank(fallbackText, "未知玩家"), fallbackColor)
            .decoration(TextDecoration.ITALIC, false);
    }

    private Component createPlayerHeadComponent(UUID playerId, String playerName) {
        org.bukkit.entity.Player online = Bukkit.getPlayer(playerId);
        return VersionCompat.createPlayerHeadComponent(
            playerId,
            playerName,
            online == null ? null : online.getPlayerProfile()
        );
    }

    public Component playerNameComponent(UUID playerId, String fallbackText, NamedTextColor fallbackColor) {
        String playerName = resolvePlayerName(playerId);
        return MuzTheme.named(normalizeNonBlank(playerName, normalizeNonBlank(fallbackText, "未知玩家")), fallbackColor)
            .decoration(TextDecoration.ITALIC, false);
    }

    private String resolvePlayerName(UUID playerId) {
        if (playerId == null) {
            return null;
        }
        org.bukkit.entity.Player online = Bukkit.getPlayer(playerId);
        if (online != null) {
            return online.getName();
        }
        OfflinePlayer offline = Bukkit.getOfflinePlayer(playerId);
        return offline.getName();
    }

    /**
     * 把 config 里读到的原始值解析成生效值，0 表示"这一项没调过，用源码里固化的默认"。
     *
     * config.yml 出厂时这些项统一写 0：调好的值固化在源码里，配置文件只承担"我改过什么"。
     * 玩家一眼能看出哪些项动过，想调直接填数字覆盖，不必先去猜出厂值是多少。
     * 键不存在时 getDouble 同样返回 0，所以缺键和写 0 走的是同一条路。
     *
     * 做成静态纯函数是为了能直接测：0 取默认、非 0 覆盖、负值不被当成未设定。
     *
     * @param raw config 里读到的原始值
     * @param codeDefault 源码里固化的默认值
     * @return raw 非 0 时用 raw，否则用 codeDefault
     */
    static double zeroMeansDefault(double raw, double codeDefault) {
        return raw == 0.0 ? codeDefault : raw;
    }

    /**
     * {@link #zeroMeansDefault(double, double)} 的整数版本。
     *
     * @param raw config 里读到的原始值
     * @param codeDefault 源码里固化的默认值
     * @return raw 非 0 时用 raw，否则用 codeDefault
     */
    static int zeroMeansDefault(int raw, int codeDefault) {
        return raw == 0 ? codeDefault : raw;
    }

    /**
     * 所有走"0 = 用默认"的项及其源码默认值，由 {@link #cfgDouble} / {@link #cfgInt} 读取时自动登记。
     *
     * 不单独维护一张路径到默认值的表：那张表会和读取点脱节，
     * 改了读取点忘了改表，归零迁移就会拿旧默认值去比对。登记表由读取点自己填，天然同步。
     */
    private final java.util.Map<String, Double> zeroableDefaults = new java.util.LinkedHashMap<>();

    /**
     * 一次性归零迁移：把 config.yml 里"值恰好等于源码默认"的项写回 0。
     *
     * 老服务器的 config.yml 里这些项都是显式写着出厂值的。逐项比对，
     * 只有和源码默认完全一致的才归零；玩家自己调过的值一律不动——
     * 那才是配置文件该留下的内容。
     *
     * 因此这个迁移是幂等的：跑第二遍时能归零的都已经是 0，不会再动任何东西，
     * 也不需要记一个"迁移已执行"的标记位。
     *
     * 必须在 {@link #loadRenderSettings()} 之后调用，那时登记表才填满。
     *
     * @return 被归零的项数
     */
    private int migrateZeroableDefaults() {
        int zeroed = 0;
        for (java.util.Map.Entry<String, Double> entry : zeroableDefaults.entrySet()) {
            String path = entry.getKey();
            if (!yamlConfig().contains(path)) {
                // 缺键读出来就是 0，已经等于"取默认"，不用补写
                continue;
            }
            double current = yamlConfig().getDouble(path, 0.0);
            if (current == 0.0) {
                continue;
            }
            // YAML 往返可能带来末位误差，直接用 == 比会漏掉本该归零的项
            if (Math.abs(current - entry.getValue()) < 1.0e-9) {
                yamlConfig().set(path, 0);
                zeroed++;
            }
        }
        if (zeroed > 0) {
            saveYamlConfig();
            getLogger().info("已把 " + zeroed + " 项仍是出厂值的配置写回 0（0 表示用插件内置默认），你调过的值都保留了。");
        }
        return zeroed;
    }

    /**
     * 按"0 = 用默认"语义读一个小数配置项。
     *
     * @param path config 路径
     * @param codeDefault 源码里固化的默认值
     * @return 生效值
     */
    private double cfgDouble(String path, double codeDefault) {
        zeroableDefaults.put(path, codeDefault);
        return zeroMeansDefault(yamlConfig().getDouble(path, 0.0), codeDefault);
    }

    /**
     * 按"0 = 用默认"语义读一个整数配置项。
     *
     * @param path config 路径
     * @param codeDefault 源码里固化的默认值
     * @return 生效值
     */
    private int cfgInt(String path, int codeDefault) {
        zeroableDefaults.put(path, (double) codeDefault);
        return zeroMeansDefault(yamlConfig().getInt(path, 0), codeDefault);
    }

    /**
     * 头像描边色（ARGB），0 表示不描边。
     *
     * <p>不走 {@link #cfgInt} 的「0 = 用默认」语义：这里 0 就是字面意思「关掉」。
     */
    public int getTrickHudAvatarOutlineArgb() {
        // 默认开启描边：测试服实测下来，不描边的头像在浅色背景上几乎看不出轮廓
        if (!yamlConfig().getBoolean("trick-hud.avatar-outline.enabled", true)) {
            return 0;
        }
        String hex = yamlConfig().getString("trick-hud.avatar-outline.color", "#000000");
        return parseOutlineArgb(hex);
    }

    /**
     * 解析 {@code #rrggbb} 或 {@code #aarrggbb}。
     *
     * <p>写错不抛异常也不静默变透明：回落成不透明黑并留日志。描边色写错最多是颜色不对，
     * 不值得让整个 HUD 不显示。
     */
    private int parseOutlineArgb(String raw) {
        String hex = raw == null ? "" : raw.trim();
        if (hex.startsWith("#")) {
            hex = hex.substring(1);
        }
        try {
            if (hex.length() == 6) {
                return 0xFF000000 | Integer.parseInt(hex, 16);
            }
            if (hex.length() == 8) {
                return (int) Long.parseLong(hex, 16);
            }
        } catch (NumberFormatException ignored) {
            // 落到下面统一告警
        }
        getLogger().warning("trick-hud.avatar-outline.color=" + raw
            + " 不是 #rrggbb 或 #aarrggbb，已回退为不透明黑");
        return 0xFF000000;
    }

    private void loadRenderSettings() {
        cardHologramLabelsEnabled = yamlConfig().getBoolean("cards.hologram-labels.enabled", true);
        duplicateOnlyCardLabels = yamlConfig().getBoolean("cards.hologram-labels.duplicate-ranks-only", false);
        // -0.55 是测试服实测调优值，正好是桌面与地面齐平的理论点
        tableSpawnOffsetY = cfgDouble("table.spawn-offset-y", -0.55);
        privateCardScale = (float) cfgDouble("render.private-card-scale", DEFAULT_PRIVATE_CARD_SCALE);
        privateCardWidthScale = (float) cfgDouble("render.private-card-size.width", DEFAULT_PRIVATE_CARD_AXIS_SCALE);
        privateCardHeightScale = (float) cfgDouble("render.private-card-size.height", DEFAULT_PRIVATE_CARD_AXIS_SCALE);
        privateCardDepthScale = (float) cfgDouble("render.private-card-size.depth", DEFAULT_PRIVATE_CARD_AXIS_SCALE);
        hoverCardScale = (float) cfgDouble("render.card-hover.scale", 1.08);
        hoverCardLift = cfgDouble("render.card-hover.lift", 0.06);
        cardHoverInterpolationTicks = Math.max(1, yamlConfig().getInt("render.card-hover.interpolation-ticks", 6));
        cardHoverAnimationTypeIndex = Math.clamp(yamlConfig().getInt("render.card-hover.animation-type", 1), 0, AnimationCurve.values().length - 1);
        // 新桌椅模型是按成品尺寸导出的（桌 2.5x2.5 格、椅 0.875x1.56 格），所以默认不再放大。
        // 旧模型只有 0.875 格，当年默认值 2.25 / 1.35 是为了把它撑到可用大小；
        // 换模型后若继续沿用旧默认值，桌子会变成 5.6 格宽，椅子会被埋进桌子里。
        tableScale = (float) cfgDouble("render.furniture-scale.table", 1.0);
        chairScale = (float) cfgDouble("render.furniture-scale.chair", 1.0);
        smallTextScale = (float) cfgDouble("render.text-scale.small", 0.46);
        statusTextScale = (float) cfgDouble("render.text-scale.status", 0.72);
        labelTextScale = (float) cfgDouble("render.text-scale.label", 0.40);
        statusNameScale = (float) cfgDouble("render.status-name.scale", smallTextScale);
        statusNameLateralOffset = yamlConfig().getDouble("render.status-name-offset.lateral", 0.0);
        statusNameVerticalOffset = cfgDouble("render.status-name-offset.vertical", 0.56);
        statusNameDepthOffset = yamlConfig().getDouble("render.status-name-offset.depth", 0.0);
        seatNameScale = (float) cfgDouble("render.seat-name.scale", smallTextScale);
        seatNameLateralOffset = yamlConfig().getDouble("render.seat-name-offset.lateral", 0.0);
        seatNameVerticalOffset = cfgDouble("render.seat-name-offset.vertical", -0.04);
        seatNameDepthOffset = yamlConfig().getDouble("render.seat-name-offset.depth", 0.0);
        // 0.9 是测试服实测调优值：空位字条要比入座后的名字更醒目，
        // 所以不再跟随 seatNameScale（0.46），两者从此有意分开
        emptySeatScale = (float) cfgDouble("render.empty-seat.scale", 0.9);
        emptySeatLateralOffset = yamlConfig().getDouble("render.empty-seat-offset.lateral", seatNameLateralOffset);
        // 1.5 是测试服实测调优值：空位字条要抬到椅背上方，不再跟随 seatNameVerticalOffset（-0.04）
        emptySeatVerticalOffset = cfgDouble("render.empty-seat-offset.vertical", 1.5);
        emptySeatDepthOffset = yamlConfig().getDouble("render.empty-seat-offset.depth", seatNameDepthOffset);
        // 0.5 是测试服实测调优值，略大于 smallTextScale（0.46）
        seatInfoScale = (float) cfgDouble("render.seat-info.scale", 0.5);
        seatInfoLateralOffset = yamlConfig().getDouble("render.seat-info-offset.lateral", 0.0);
        seatInfoVerticalOffset = cfgDouble("render.seat-info-offset.vertical", -0.5);
        seatInfoDepthOffset = yamlConfig().getDouble("render.seat-info-offset.depth", 0.0);
        selectedCardScale = (float) cfgDouble("render.selected-card.scale", 1.00);
        selectedCardLift = cfgDouble("render.selected-card.lift", 0.18);
        hoverGlowEnabled = yamlConfig().getBoolean("render.hover-glow.enabled", true);
        Color loadedHoverGlow = parseRgbSpec(
            yamlConfig().getString("render.hover-glow.color"),
            Color.fromRGB(
                cfgInt("render.hover-glow.color.red", 96),
                cfgInt("render.hover-glow.color.green", 180),
                cfgInt("render.hover-glow.color.blue", 255)
            )
        );
        hoverGlowRed = loadedHoverGlow.getRed();
        hoverGlowGreen = loadedHoverGlow.getGreen();
        hoverGlowBlue = loadedHoverGlow.getBlue();
        selectedGlowEnabled = yamlConfig().getBoolean("render.selected-glow.enabled", true);
        Color loadedSelectedGlow = parseRgbSpec(
            yamlConfig().getString("render.selected-glow.color"),
            Color.fromRGB(
                cfgInt("render.selected-glow.color.red", 255),
                cfgInt("render.selected-glow.color.green", 226),
                cfgInt("render.selected-glow.color.blue", 92)
            )
        );
        selectedGlowRed = loadedSelectedGlow.getRed();
        selectedGlowGreen = loadedSelectedGlow.getGreen();
        selectedGlowBlue = loadedSelectedGlow.getBlue();
        cardLabelHeight = cfgDouble("render.card-label-height", 0.05);
        cardLabelLateralOffset = yamlConfig().getDouble("render.card-label-offset.lateral", 0.05);
        cardLabelDepthOffset = yamlConfig().getDouble("render.card-label-offset.depth", 0.0);
        cardDepthOffset = (float) cfgDouble("render.card-depth-offset", 0.005);
        handSpacing = (float) cfgDouble("render.hand-spacing", 0.1);
        buttonDistance = cfgDouble("render.button-offset.distance", DEFAULT_BUTTON_DISTANCE);
        buttonHeight = cfgDouble("render.button-offset.height", 2.5);
        tableDisplayHeight = cfgDouble("render.layout.table-display-height", 0.55);
        tableColliderHeight = cfgDouble("render.layout.table-collider-height", 0.72);
        chairBaseHeight = cfgDouble("render.layout.chair-base-height", 0.20);
        chairColliderHeight = cfgDouble("render.layout.chair-collider-height", 0.18);
        chairSeatHeight = cfgDouble("render.layout.chair-seat-height", 0.18);
        chairInteractionHeight = cfgDouble("render.layout.chair-interaction-height", 0.38);
        chairLabelHeight = cfgDouble("render.layout.chair-label-height", 1.35);
        // 180 是测试服实测调优值：椅子模型默认朝向与桌子相反，转半圈才朝向桌心
        chairRotationDegrees = yamlConfig().getDouble("render.chair-rotation-degrees", 180.0);
        chairVisualLateralOffset = yamlConfig().getDouble("render.chair-visual-offset.lateral", 0.0);
        chairVisualVerticalOffset = cfgDouble("render.chair-visual-offset.vertical", 0.35);
        chairHitboxLateralOffset = yamlConfig().getDouble("render.chair-hitbox-offset.lateral", 0.0);
        chairHitboxVerticalOffset = cfgDouble("render.chair-hitbox-offset.vertical", 0.02);
        // 判定框尺寸不再配置，改为按按钮文字缩放自动推算，只保留位置微调。
        buttonHitboxLateralOffset = yamlConfig().getDouble("render.button-hitbox-offset.lateral", 0.0);
        buttonHitboxDepthOffset = yamlConfig().getDouble("render.button-hitbox-offset.depth", 0.0);
        buttonHitboxVerticalOffset = cfgDouble("render.button-hitbox-offset.vertical", 0.02);
        // render.card-hitbox / render.card-hitbox-offset 已整组删除：手牌上没有交互箱了，
        // 点到哪张牌由射线与牌平面解析求交裁决，没有可偏移、可调尺寸的判定框。
        // 两组键都在 RETIRED_RENDER_KEYS 里，启动时会从老配置里清掉。
        statusHeight = cfgDouble("render.status-height", 5.0);
        playDetailHeight = cfgDouble("render.play-detail-height", 4.0);
        statusLineWidth = yamlConfig().getInt("render.layout.status-line-width", 250);
        handCenterDistance = cfgDouble("render.layout.hand-center.distance", 1.62);
        handCenterHeight = cfgDouble("render.layout.hand-center.height", 1.23);
        chairDistance = cfgDouble("render.layout.chair-distance", 2.5);
        joinLabelHeight = cfgDouble("render.button-layout.join-label-height", 0.18);
        joinLabelScale = (float) cfgDouble("render.button-layout.join-label-scale", 0.4);
        actionLabelHeight = cfgDouble("render.button-layout.action-label-height", 0.2);
        actionLabelScale = (float) cfgDouble("render.button-layout.action-label-scale", 0.4);
        buttonFrontBaseDistance = cfgDouble("render.button-layout.front-base-distance", 1.40);
        buttonSideBaseDistance = cfgDouble("render.button-layout.side-base-distance", 1.72);
        buttonDistanceFactor = cfgDouble("render.button-layout.distance-factor", 0.45);
        buttonSpacingScale = cfgDouble("render.button-layout.spacing-scale", 1.2);
        buttonArcSmallAngleDegrees = cfgDouble("render.button-layout.arc-angle-small", 30.0);
        buttonArcLargeAngleDegrees = cfgDouble("render.button-layout.arc-angle-large", 42.0);
        buttonArcSmallRadius = cfgDouble("render.button-layout.arc-radius-small", 0.70);
        buttonArcLargeRadius = cfgDouble("render.button-layout.arc-radius-large", 0.8);
        // 三家手牌的整体位置：全是测试服实测调优值，把手牌抬到桌沿上方、略微前推
        globalPrivateHandLateralOffset = yamlConfig().getDouble("render.private-hand-offset.lateral", 0.03);
        globalPrivateHandVerticalOffset = yamlConfig().getDouble("render.private-hand-offset.vertical", 1.9);
        globalPrivateHandDepthOffset = yamlConfig().getDouble("render.private-hand-offset.depth", 0.55);
        bgmVolume = (float) yamlConfig().getDouble("audio.bgm-volume", 0.55);
        effectVolume = (float) yamlConfig().getDouble("audio.effect-volume", 1.0);
        turnCountdownSeconds = yamlConfig().getInt("actionbar.turn-countdown-seconds", 20);
        countdownSoundSpec = safeNormalizeCountdownSoundSpec(yamlConfig().getString("actionbar.countdown-sound", DEFAULT_COUNTDOWN_SOUND_SPEC));
        unreadyWarningSoundSpec = safeNormalizeCountdownSoundSpec(yamlConfig().getString("actionbar.unready-warning-sound", DEFAULT_UNREADY_WARNING_SOUND_SPEC));
        placementBlockedSoundSpec = safeNormalizeCountdownSoundSpec(yamlConfig().getString("table.placement-blocked-sound", DEFAULT_PLACEMENT_BLOCKED_SOUND_SPEC));
        botActionDelayMinTicks = yamlConfig().getInt("bot.action-delay-min-ticks", yamlConfig().getInt("bot.action-delay-ticks", DEFAULT_BOT_DELAY_MIN_TICKS));
        botActionDelayMaxTicks = cfgInt("bot.action-delay-max-ticks", yamlConfig().getInt("bot.action-delay-ticks", DEFAULT_BOT_DELAY_MAX_TICKS));
        // 默认 true：ensureBotAiConfig 补键时写的是 true，config.yml 出厂也是 true，
        // 这里原先写 false，只有"键被手动删掉"时才会显出差异——那种情况下机器人会莫名不走 AI。
        botAiEnabled = yamlConfig().getBoolean("bot.ai.enabled", true);
        botAiTimeoutMs = Math.max(1000, cfgInt("bot.ai.timeout-ms", 5000));
        if (botActionDelayMaxTicks < botActionDelayMinTicks) {
            int swapped = botActionDelayMinTicks;
            botActionDelayMinTicks = botActionDelayMaxTicks;
            botActionDelayMaxTicks = swapped;
        }
        hintGroupLimit = cfgInt("hints.max-groups", 6);
        debugTableSpacing = cfgDouble("debug.table-spacing", 6.5);
        vaultEconomyEnabled = yamlConfig().getBoolean("economy.vault.enabled", true);
        chipPaymentEnabled = yamlConfig().getBoolean("economy.payment.use-chip", false);
        vaultDoudizhuCurrencyPerPoint = Math.max(0.0001, cfgDouble("economy.vault.doudizhu.currency-per-point", 1.0));
        vaultPreferredProviderNames = normalizedStringList(yamlConfig().getStringList("economy.vault.preferred-providers"), List.of("EzEconomy", "XConomy", "CMI"));
        loadRoomLevelProfiles();
        tableItemModelId = normalizeItemModelId(yamlConfig().getString("craftengine-items.table.item-model"), DEFAULT_TABLE_ITEM_MODEL);
        tableDisplayName = normalizeNonBlank(yamlConfig().getString("craftengine-items.table.item-name"), DEFAULT_TABLE_DISPLAY_NAME);
        chairItemModelId = normalizeItemModelId(yamlConfig().getString("craftengine-items.chair.item-model"), DEFAULT_CHAIR_ITEM_MODEL);
        chairDisplayName = normalizeNonBlank(yamlConfig().getString("craftengine-items.chair.item-name"), DEFAULT_CHAIR_DISPLAY_NAME);
        loadOptionProfiles();
    }

    private void loadAiSettings() {
        aiProviderConfig = new AiChatGateway.ProviderConfig(
            yamlConfig().getBoolean("ai.deepseek.enabled", false),
            yamlConfig().getString("ai.deepseek.provider-name", "DeepSeek"),
            yamlConfig().getString("ai.deepseek.url", yamlConfig().getString("ai.deepseek.base-url", "https://api.deepseek.com")),
            yamlConfig().getString("ai.deepseek.chat-completions-path", "/chat/completions"),
            yamlConfig().getString("ai.deepseek.models-path", "/models"),
            yamlConfig().getString("ai.deepseek.api-key", ""),
            yamlConfig().getString("ai.deepseek.model", "deepseek-chat"),
            cfgInt("ai.deepseek.connect-timeout-ms", 10000),
            cfgInt("ai.deepseek.request-timeout-ms", 45000),
            roundToSingleDecimal(cfgDouble("ai.deepseek.temperature", 0.7)),
            yamlConfig().getInt("ai.deepseek.max-tokens", 0),
            normalizeNonBlank(yamlConfig().getString("ai.deepseek.system-prompt"), DEFAULT_AI_SYSTEM_PROMPT)
        );
        aiChatGateway = new OpenAiCompatibleAiChatGateway(aiProviderConfig, getLogger());
    }

    private void ensureConfigIntegrity() {
        // 先迁移旧配置再合并模板：counter.offset-down 缺键时必须继承用户原有的
        // avatar-offset-down；若先 mergeDefaultYamlConfig() 写入 122，就会丢掉这条兼容语义。
        boolean changed = migrateMissingCounterOffsetDown();
        changed |= migrateRetiredHotbarConfig();
        changed |= mergeDefaultYamlConfig();
        changed |= migrateLegacyFurnitureConfig(FurnitureType.TABLE);
        changed |= migrateLegacyFurnitureConfig(FurnitureType.CHAIR);
        changed |= migrateLegacyRenderConfig();
        changed |= ensureFurnitureConfig(FurnitureType.TABLE);
        changed |= ensureFurnitureConfig(FurnitureType.CHAIR);
        changed |= ensureEconomyConfig();
        changed |= ensureAiConfig();
        changed |= ensureAvatarConfig();
        changed |= ensurePlacementSoundConfig();
        changed |= ensureBotAiConfig();
        changed |= migrateChairHitboxConfig();
        if (changed) {
            saveYamlConfig();
        }
    }

    /**
     * 退役旧三道具 Hotbar 配置：只保留 interaction 语义并迁移到新段，其余资源/HUD 键全部删除。
     */
    private boolean migrateRetiredHotbarConfig() {
        if (!yamlConfig().contains("hotbar-hud")) {
            return false;
        }
        String[] leaves = {"enabled", "range", "cooldown-ticks", "flight-ticks", "water-ticks", "max-active"};
        for (String leaf : leaves) {
            String target = "table-gadgets.interaction." + leaf;
            String legacy = "hotbar-hud.interaction." + leaf;
            if (!yamlConfig().contains(target) && yamlConfig().contains(legacy)) {
                yamlConfig().set(target, yamlConfig().get(legacy));
            }
        }
        yamlConfig().set("hotbar-hud", null);
        getLogger().info("已迁移并移除退役的 hotbar-hud 配置；桌内道具改用 table-gadgets 与九格道具箱。");
        return true;
    }

    /**
     * 把旧版偏小的椅子交互箱升级成能包住椅子的尺寸
     * @return 配置是否发生变化
     */
    static final String[] PRESERVED_RENDER_KEYS = {
        "render.button-offset.distance",
        "render.button-offset.height",
        "render.button-hitbox-offset.lateral",
        "render.button-hitbox-offset.depth",
        "render.button-hitbox-offset.vertical"
    };

    static final String[] RETIRED_RENDER_KEYS = {
        "render.button-scale",
        "render.button-roll-degrees",
        "render.button-hover",
        "render.button-hitbox.width",
        "render.button-hitbox.height",
        "render.chair-hitbox.width",
        "render.chair-hitbox.height",
        "render.chair-hitbox",
        "render.card-hover.backward-offset",
        "render.card-hitbox.length",
        "render.card-hitbox.width",
        "render.card-hitbox.height",
        "render.card-hitbox",
        "render.card-hitbox-offset.lateral",
        "render.card-hitbox-offset.depth",
        "render.card-hitbox-offset.vertical",
        "render.card-hitbox-offset",
        "render.current-play-head.enabled",
        "render.current-play-head.drop",
        "render.current-play-head"
    };

    private boolean migrateChairHitboxConfig() {
        // 判定框尺寸改为按按钮文字缩放自动推算后，这些手调项已经没有作用。
        // 留在配置里只会让人以为还能调，所以直接清掉。
        boolean changed = false;
        for (String stale : RETIRED_RENDER_KEYS) {
            if (yamlConfig().contains(stale)) {
                yamlConfig().set(stale, null);
                changed = true;
            }
        }
        if (yamlConfig().getDouble("render.chair-hitbox-offset.vertical", 0.02) >= 0.08) {
            yamlConfig().set("render.chair-hitbox-offset.vertical", 0);
            changed = true;
        }
        return changed;
    }

    private boolean ensureBotAiConfig() {
        boolean changed = false;
        changed |= ensureMissingConfigValue("bot.ai.enabled", true);
        changed |= ensureMissingConfigValue("bot.ai.timeout-ms", 0);
        return changed;
    }

    private boolean ensurePlacementSoundConfig() {
        if (yamlConfig().contains("table.placement-blocked-sound")) {
            return false;
        }
        yamlConfig().set("table.placement-blocked-sound", DEFAULT_PLACEMENT_BLOCKED_SOUND_SPEC);
        return true;
    }

    private boolean ensureMissingConfigValue(String path, Object value) {
        if (yamlConfig().contains(path)) {
            return false;
        }
        yamlConfig().set(path, value);
        return true;
    }

    private boolean ensureAiConfig() {
        boolean changed = false;
        changed |= ensureMissingConfigValue("ai.deepseek.enabled", false);
        changed |= ensureMissingConfigValue("ai.deepseek.provider-name", "DeepSeek");
        changed |= ensureMissingConfigValue("ai.deepseek.url", yamlConfig().getString("ai.deepseek.base-url", "https://api.deepseek.com"));
        changed |= ensureMissingConfigValue("ai.deepseek.chat-completions-path", "/chat/completions");
        changed |= ensureMissingConfigValue("ai.deepseek.models-path", "/models");
        changed |= ensureMissingConfigValue("ai.deepseek.api-key", "");
        changed |= ensureMissingConfigValue("ai.deepseek.model", "deepseek-chat");
        changed |= ensureMissingConfigValue("ai.deepseek.connect-timeout-ms", 0);
        changed |= ensureMissingConfigValue("ai.deepseek.request-timeout-ms", 0);
        changed |= ensureMissingConfigValue("ai.deepseek.temperature", 0);
        changed |= ensureMissingConfigValue("ai.deepseek.max-tokens", 0);
        changed |= ensureMissingConfigValue("ai.deepseek.system-prompt", DEFAULT_AI_SYSTEM_PROMPT);
        return changed;
    }

    private boolean ensureAvatarConfig() {
        boolean changed = false;
        // 补键统一写 0（= 取源码默认），不写字面量：
        // 写字面量的话，源码默认值改了而这里漏改，删键重生成就会拿到旧值。
        changed |= ensureDoubleConfig("render.status-name.scale", 0);
        changed |= ensureDoubleConfig("render.status-name-offset.lateral", 0.0);
        changed |= ensureDoubleConfig("render.status-name-offset.vertical", 0);
        changed |= ensureDoubleConfig("render.status-name-offset.depth", 0.0);
        changed |= ensureDoubleConfig("render.seat-name.scale", 0);
        changed |= ensureDoubleConfig("render.seat-name-offset.lateral", 0.0);
        changed |= ensureDoubleConfig("render.seat-name-offset.vertical", 0);
        changed |= ensureDoubleConfig("render.seat-name-offset.depth", 0.0);
        changed |= ensureDoubleConfig("render.empty-seat.scale", 0);
        changed |= ensureDoubleConfig("render.empty-seat-offset.lateral", 0.0);
        changed |= ensureDoubleConfig("render.empty-seat-offset.vertical", 0);
        changed |= ensureDoubleConfig("render.empty-seat-offset.depth", 0.0);
        changed |= ensureDoubleConfig("render.seat-info.scale", 0);
        changed |= ensureDoubleConfig("render.seat-info-offset.lateral", 0.0);
        changed |= ensureDoubleConfig("render.seat-info-offset.vertical", 0);
        changed |= ensureDoubleConfig("render.seat-info-offset.depth", 0.0);
        changed |= ensureDoubleConfig("render.button-layout.join-label-scale", 0);
        return changed;

    }

    private boolean ensureDoubleConfig(String path, double defaultValue) {
        if (yamlConfig().contains(path)) {
            return false;
        }
        yamlConfig().set(path, defaultValue);
        return true;
    }

    private boolean migrateLegacyRenderConfig() {
        boolean changed = false;
        // 所有"数值精确等于旧默认值就强制覆盖成新默认值"式的迁移都已删除
        // （按钮离桌距离 1.45/1.10 → 2.10、手牌缩放 0.50、公牌缩放 0.58）。
        //
        // 两个致命缺陷：
        // 1. 无法区分"这是旧默认值残留"和"用户就是想要这个值"，一律当前者处理；
        // 2. 它不只在升级时跑一次——每次 reloadVisualState 都会跑，而管理菜单里
        //    每点一下调节按钮都会触发重载，于是用户把数值调到某个旧默认值上时，
        //    下一帧就被强制弹到新默认值，表现为"这个值调不过去"的跳档。
        //    按钮距离从 1.45 直接跳 2.10 就是这么来的。
        //
        // 迁移已完成历史使命，新安装一律以 AdminSetting 枚举默认值为准，不再做值覆盖。
        // 下面 bot.action-delay-ticks 的迁移保留：它是"缺键才补"的幂等写入，
        // 不覆盖任何已有值，重复执行无副作用。
        if (yamlConfig().contains("bot.action-delay-ticks")) {
            if (!yamlConfig().contains("bot.action-delay-min-ticks")) {
                yamlConfig().set("bot.action-delay-min-ticks", yamlConfig().getInt("bot.action-delay-ticks", DEFAULT_BOT_DELAY_MIN_TICKS));
                changed = true;
            }
            if (!yamlConfig().contains("bot.action-delay-max-ticks")) {
                yamlConfig().set("bot.action-delay-max-ticks", yamlConfig().getInt("bot.action-delay-ticks", DEFAULT_BOT_DELAY_MAX_TICKS));
                changed = true;
            }
        }
        return changed;
    }

    private ReloadSummary reloadVisualState(boolean exportBundle, ReloadFeedback feedback) {
        int totalStages = exportBundle ? 5 : 4;
        feedback.update(stageProgress(0, totalStages), "重载配置", "config.yml / 渲染参数");
        reloadYamlConfig();
        ensureConfigIntegrity();
        loadRenderSettings();
        loadAiSettings();
        reloadHudRuntimeState();
        reloadTableGadgetRuntime();
        syncDebugWebServerRuntime();
        // 【偏移服务也要重解析】：它的 initialised 只置一次，解析失败后永不重试。
        // 若 CraftEngine 曾因资源包配置错误而没就绪，整条 HUD 会被 render 直接 hide；
        // 不在这里重置的话，服主修好配置执行 /muz reload 依然什么都看不到，只能重启。
        if (craftEngineOffsetService != null) {
            craftEngineOffsetService.invalidate();
        }
        feedback.update(stageProgress(1, totalStages), "刷新界面资源", "PlaceholderAPI / 渲染缓存");
        HookSnapshot placeholderHook = ensurePlaceholderHookReadyInternal();
        HookSnapshot vaultHook = ensureVaultEconomyHookReadyInternal();
        CraftEngineBundleExporter.BundleExportResult exportResult = CraftEngineBundleExporter.BundleExportResult.skipped("未请求同步");
        if (exportBundle && craftEngineBundleExporter != null) {
            int bundleStageIndex = 2;
            feedback.update(stageProgress(bundleStageIndex, totalStages), "同步 CraftEngine 资源", "准备写入 bundle");
            exportResult = craftEngineBundleExporter.ensureBundleReady(
                "manual-reload",
                true,
                (copiedEntries, totalEntries, relativePath) -> feedback.update(
                    stageProgress(bundleStageIndex, totalStages, copiedEntries, totalEntries),
                    "同步 CraftEngine 资源",
                    "bundle " + copiedEntries + "/" + totalEntries + " · " + relativePath
                )
            );
        }
        java.util.concurrent.CompletableFuture<HudWebApplyCoordinator.ApplyResult> hudRecovery = null;
        if (hudResourceRecoveryService != null && exportBundle) {
            hudRecovery = hudResourceRecoveryService.reloadFromDisk("manual-reload");
        }
        int doudizhuTables = physicalTableManager == null ? 0 : physicalTableManager.placedTableCount();
        int ddzStageIndex = exportBundle ? 3 : 2;
        feedback.update(stageProgress(ddzStageIndex, totalStages), "刷新斗地主牌桌", rebuildDetail("斗地主牌桌", doudizhuTables));
        if (physicalTableManager != null) {
            // 机制变更：重建已改为异步 stage 流水线，不再阻塞 reload 流程；失败记录日志而非静默丢弃。
            physicalTableManager.rebuildAllTables().exceptionally(failure -> {
                getLogger().warning("斗地主牌桌重建失败: " + failure.getMessage());
                return null;
            });
        }
        ReloadSummary summary = new ReloadSummary(exportResult, detectSupportedHooks(placeholderHook, vaultHook), doudizhuTables);
        feedback.complete(summary);
        if (hudRecovery != null) {
            hudRecovery.whenComplete((result, failure) -> {
                if (failure != null) {
                    feedback.recoveryFailed("HUD 四层资源恢复失败：" + failure.getMessage());
                } else if (result == null || !result.ok()) {
                    String detail = result == null || result.messages().isEmpty()
                        ? "未返回成功结果。"
                        : String.join("；", result.messages());
                    feedback.recoveryFailed("HUD 四层资源恢复失败：" + detail);
                }
            });
        }
        return summary;
    }

    /**
     * 同步 Debug Web 调试面板运行态。
     *
     * <p>【Debug Web 调试面板重载】：enabled/port 可能在 reload 之间被用户修改，
     * 因此必须在每次 reloadVisualState 里同步字段与运行状态：
     *   enabled=true 且未运行 → 按当前 port 创建并启动；之前启动失败的实例也会重试
     *   enabled=true 且 port 改变 → 停掉旧端口并按新端口重启
     *   enabled=false 且 debugWebServer!=null → 停止并置 null
     */
    private void syncDebugWebServerRuntime() {
        boolean webUiEnabled = yamlConfig().getBoolean("debug.web-ui.enabled", false);
        int configuredPort = yamlConfig().getInt("debug.web-ui.port", 2000);
        if (!webUiEnabled) {
            if (debugWebServer != null) {
                debugWebServer.close();
                debugWebServer = null;
            }
            return;
        }
        if (debugWebServer != null && debugWebServer.isRunning() && debugWebServer.getPort() != configuredPort) {
            debugWebServer.close();
            debugWebServer = null;
        }
        if (debugWebServer == null) {
            debugWebServer = createDebugWebServer();
        }
        if (!debugWebServer.isRunning()) {
            debugWebServer.start(configuredPort);
        }
        // Debug Web 只负责配置与资源恢复，不再接管已退役的 Hotbar 运行期服务。
    }

    private DebugWebServer createDebugWebServer() {
        return new DebugWebServer(this, null, null, hudWebConfigController, hudWebApplyCoordinator,
            gadgetPreviewSnapshotService);
    }

    /** 按新配置独立同步桌内道具、语音面板；不再借用 hotbar-hud.enabled 作为生命周期开关。 */
    private void reloadTableGadgetRuntime() {
        if (tableGadgetService == null) {
            return;
        }
        try {
            tableGadgetSettings = TableGadgetSettings.load(yamlConfig());
            tableGadgetService.reload(tableGadgetSettings);
            if (tableGadgetSettings.enabled()) {
                tableGadgetService.start();
            } else {
                tableGadgetService.stop();
            }
            if (tableSpeechPanelService != null) {
                tableSpeechPanelService.reload(createSpeechPanelConfig(tableGadgetSettings));
            }
        } catch (IllegalArgumentException exception) {
            tableGadgetService.stop();
            getLogger().log(java.util.logging.Level.WARNING, "桌内道具配置无效，已停止互动", exception);
        }
    }

    private TableSpeechPanelService createTableSpeechPanelService(TableGadgetSettings settings) {
        TableGadgetSettings initial = settings == null ? TableGadgetSettings.load(yamlConfig()) : settings;
        if (tableGadgetSettings == null) {
            tableGadgetSettings = initial;
        }
        return new TableSpeechPanelService(
            this,
            createSpeechPanelConfig(initial),
            (table, ownerId) -> {
                TableGadgetSettings current = tableGadgetSettings == null ? initial : tableGadgetSettings;
                return buildSpeechEntries(table, ownerId, current);
            },
            () -> tableManager == null ? List.of() : tableManager.getTables(),
            (table, ownerId, targetId, entry) -> {
                TableGadgetSettings current = tableGadgetSettings == null ? initial : tableGadgetSettings;
                executeSpeechAction(table, ownerId, targetId, entry, current);
            },
            // 语音面板的每 owner 工作必须落在玩家 owner lane：global 扫描线程既没有 ticking
            // region（getCurrentTick 直接抛），也无权访问面板实体。见 TableSpeechPanelService.tick。
            playerOutputDispatcher
        );
    }

    private TableSpeechPanelService.Config createSpeechPanelConfig(TableGadgetSettings settings) {
        TableGadgetSettings.Voices voices = settings == null ? null : settings.voices();
        TableGadgetSettings.Panel panel = settings == null ? null : settings.panel();
        return new TableSpeechPanelService.Settings(
            voices != null && voices.enabled() && panel != null && panel.enabled(),
            panel == null ? TableGadgetSettings.DEFAULT_PANEL_HOVER_INTERVAL_TICKS : panel.hoverIntervalTicks(),
            settings == null ? TableGadgetSettings.DEFAULT_RANGE : settings.range(),
            panel == null ? TableGadgetSettings.DEFAULT_PANEL_VOICE_COOLDOWN_TICKS : panel.voiceCooldownTicks(),
            Set.of(GamePhase.PLAYING)
        );
    }

    private List<TableSpeechPanelService.SpeechEntry> buildSpeechEntries(GameTable table, UUID ownerId, TableGadgetSettings settings) {
        if (table == null || ownerId == null || settings == null || settings.voices() == null
            || !settings.voices().enabled() || settings.panel() == null || !settings.panel().enabled()) {
            return List.of();
        }
        Player owner = Bukkit.getPlayer(ownerId);
        if (owner == null || !owner.isOnline()) {
            return List.of();
        }
        TableGadgetSettings.Panel panel = settings.panel();
        org.bukkit.util.Vector forward = owner.getEyeLocation().getDirection().setY(0.0);
        if (forward.lengthSquared() < 1.0e-6) {
            float yaw = owner.getEyeLocation().getYaw();
            double radians = Math.toRadians(yaw);
            forward = new org.bukkit.util.Vector(-Math.sin(radians), 0.0, Math.cos(radians));
        } else {
            forward.normalize();
        }
        Location base = owner.getEyeLocation().clone().add(forward.clone().multiply(panel.forwardOffset()));
        base.add(0.0, panel.verticalOffset(), 0.0);
        float panelYaw = owner.getEyeLocation().getYaw() + 180.0f;
        UUID currentTurn = table.getCurrentTurn();
        Player target = currentTurn == null ? null : Bukkit.getPlayer(currentTurn);
        String senderName = owner.getName();
        String targetName = target == null ? "当前玩家" : target.getName();
        List<TableSpeechPanelService.SpeechEntry> entries = new ArrayList<>();
        int limit = Math.min(panel.maxEntries(), settings.voices().entries().size());
        double totalHeight = limit * panel.rowHeight() + Math.max(0, limit - 1) * panel.rowGap();
        for (int index = 0; index < limit; index++) {
            TableGadgetSettings.Voice voice = settings.voices().entries().get(index);
            if (voice == null) {
                continue;
            }
            String rendered = voice.text().replace("{sender}", senderName).replace("{target}", targetName);
            double y = totalHeight * 0.5 - panel.rowHeight() * 0.5
                - entries.size() * (panel.rowHeight() + panel.rowGap());
            Location center = base.clone().add(0.0, y, 0.0);
            entries.add(new TableSpeechPanelService.SpeechEntry(
                voice.id(), Component.text(rendered),
                new TableSpeechPanelService.Panel(center, panel.width(), panel.rowHeight(), panelYaw)
            ));
        }
        return List.copyOf(entries);
    }

    private void executeSpeechAction(
        GameTable table,
        UUID ownerId,
        UUID targetId,
        TableSpeechPanelService.SpeechEntry entry,
        TableGadgetSettings settings
    ) {
        if (table == null || ownerId == null || entry == null || settings == null || settings.voices() == null) {
            return;
        }
        TableGadgetSettings.Voice voice = settings.voices().entries().stream()
            .filter(candidate -> candidate != null && candidate.id().equals(entry.id()))
            .findFirst().orElse(null);
        if (voice == null) {
            return;
        }
        Player sender = Bukkit.getPlayer(ownerId);
        if (sender == null || !sender.isOnline()) {
            return;
        }
        Player target = targetId == null ? null : Bukkit.getPlayer(targetId);
        if ("current-turn".equals(voice.target())) {
            if (targetId == null || ownerId.equals(targetId) || table.isBot(targetId)
                || target == null || !target.isOnline()) {
                sender.sendMessage(Component.text("当前没有可催促的在线真人玩家。"));
                return;
            }
        }
        String targetName = target == null ? "当前玩家" : target.getName();
        String rendered = voice.text().replace("{sender}", sender.getName()).replace("{target}", targetName);
        Component message = Component.text(rendered);
        table.playTableSound(voice.sound(), voice.volume(), voice.pitch());
        for (UUID recipient : table.getSeats()) {
            Player player = Bukkit.getPlayer(recipient);
            if (player != null && player.isOnline() && !table.isBot(recipient)) {
                player.sendMessage(message);
            }
        }
        if (actionBarOverlayService != null) {
            actionBarOverlayService.showOverlay(table.getSeats(), message, 30);
        }
    }

    /**
     * 把 trick-hud 的新配置推给每张桌的 HUD 服务。
     *
     * <p>【为什么不能靠重建牌桌顺带解决】：{@code rebuildAllTables()} 重建的是牌桌实体
     * （桌椅、按钮、显示体），不碰 {@link GameTable} 对象本身，HUD 服务是 GameTable 的
     * final 字段，重建实体不会让它重读 config。
     */
    private void reloadTrickHudSettings() {
        TrickHudPreview preview = trickHudPreview;
        if (preview != null) {
            preview.reloadSettings();
        }
        if (tableManager == null) {
            return;
        }
        for (GameTable table : tableManager.getTables()) {
            table.reloadTrickHudSettings();
        }
    }

    /**
     * 迁移阶段 C 新增的独立记牌器 Y：旧配置没有该键时沿用头像行位置，避免升级后
     * 记牌器突然跳回固定默认值。迁移只在缺键时写入，用户已有值绝不覆盖。
     */
    private boolean migrateMissingCounterOffsetDown() {
        String key = "trick-hud.counter.offset-down";
        if (yamlConfig().contains(key)) {
            return false;
        }
        int inherited = yamlConfig().getInt("trick-hud.avatar-offset-down", 122);
        yamlConfig().set(key, inherited);
        getLogger().info("检测到旧版配置缺少 " + key + "，已继承 trick-hud.avatar-offset-down=" + inherited);
        return true;
    }

    private boolean migrateLegacyFurnitureConfig(FurnitureType type) {
        String base = type.configBasePath();
        String itemModel = yamlConfig().getString(base + ".item-model");
        String namespace = yamlConfig().getString(base + ".namespace");
        String modelPath = yamlConfig().getString(base + ".model-path");
        boolean hasLegacy = namespace != null || modelPath != null;
        if (!isBlank(itemModel) || !hasLegacy) {
            return false;
        }
        if (!isBlank(namespace) && !isBlank(modelPath)) {
            String merged = namespace.trim() + ":" + modelPath.trim();
            getLogger().warning("检测到旧版 " + type.label() + " 配置键 namespace/model-path，已自动迁移为 item-model: " + merged);
            yamlConfig().set(base + ".item-model", merged);
        } else {
            getLogger().warning("检测到不完整的旧版 " + type.label() + " 配置，已回退为默认模型。");
            yamlConfig().set(base + ".item-model", type.defaultItemModelId());
        }
        yamlConfig().set(base + ".namespace", null);
        yamlConfig().set(base + ".model-path", null);
        return true;
    }

    private boolean ensureFurnitureConfig(FurnitureType type) {
        boolean changed = false;
        String base = type.configBasePath();
        if (yamlConfig().getItemStack(base + ".item-stack") != null) {
            return false;
        }
        String itemModel = yamlConfig().getString(base + ".item-model");
        if (!isBlank(itemModel)) {
            NamespacedKey parsed = NamespacedKey.fromString(itemModel.trim());
            if (parsed != null && parsed.getKey().startsWith("item/")) {
                String corrected = parsed.getNamespace() + ":" + parsed.getKey().substring("item/".length());
                getLogger().warning("配置里的 " + type.label() + " item-model 写成了模型路径 " + itemModel + "，已自动改为物品定义键 " + corrected);
                yamlConfig().set(base + ".item-model", corrected);
                itemModel = corrected;
                changed = true;
            }
        }
        if (isBlank(itemModel) || NamespacedKey.fromString(itemModel.trim()) == null) {
            getLogger().warning("配置里的 " + type.label() + " item-model 无效或为空，已改回默认值 " + type.defaultItemModelId());
            yamlConfig().set(base + ".item-model", type.defaultItemModelId());
            changed = true;
        }
        String itemName = yamlConfig().getString(base + ".item-name");
        if (isBlank(itemName)) {
            getLogger().warning("配置里的 " + type.label() + " item-name 为空，已改回默认显示名。");
            yamlConfig().set(base + ".item-name", type.defaultDisplayName());
            changed = true;
        }
        return changed;
    }

    private boolean ensureEconomyConfig() {
        boolean changed = false;
        if (!yamlConfig().contains("economy.payment.use-chip")) {
            yamlConfig().set("economy.payment.use-chip", false);
            changed = true;
        }
        if (!yamlConfig().contains("economy.payment.chip-item-stack")) {
            yamlConfig().set("economy.payment.chip-item-stack", defaultChipItem());
            changed = true;
        }
        if (!yamlConfig().contains("economy.vault.enabled")) {
            yamlConfig().set("economy.vault.enabled", true);
            changed = true;
        }
        if (!yamlConfig().contains("economy.vault.preferred-providers")) {
            yamlConfig().set("economy.vault.preferred-providers", List.of("EzEconomy", "XConomy", "CMI"));
            changed = true;
        }
        if (!yamlConfig().contains("economy.vault.doudizhu.currency-per-point")) {
            yamlConfig().set("economy.vault.doudizhu.currency-per-point", 0);
            changed = true;
        }
        for (TableLevel level : TableLevel.values()) {
            String base = "room-levels." + level.key();
            if (!yamlConfig().contains(base + ".label")) {
                yamlConfig().set(base + ".label", level.defaultLabel());
                changed = true;
            }
            if (!yamlConfig().contains(base + ".multiplier")) {
                yamlConfig().set(base + ".multiplier", level.defaultMultiplier());
                changed = true;
            } else {
                double current = yamlConfig().getDouble(base + ".multiplier", level.defaultMultiplier());
                if (level == TableLevel.LOW && Math.abs(current - 1.0) < 0.0001) {
                    yamlConfig().set(base + ".multiplier", level.defaultMultiplier());
                    changed = true;
                } else if (level == TableLevel.MID && Math.abs(current - 3.0) < 0.0001) {
                    yamlConfig().set(base + ".multiplier", level.defaultMultiplier());
                    changed = true;
                } else if (level == TableLevel.HIGH && Math.abs(current - 10.0) < 0.0001) {
                    yamlConfig().set(base + ".multiplier", level.defaultMultiplier());
                    changed = true;
                }
            }
            if (!yamlConfig().contains(base + ".economy-enabled")) {
                yamlConfig().set(base + ".economy-enabled", level.defaultEconomyEnabled());
                changed = true;
            }
        }
        if (!yamlConfig().contains("room-levels.default-create-level")) {
            yamlConfig().set("room-levels.default-create-level", "low");
            changed = true;
        }
        if (!yamlConfig().contains("debug.web-ui.enabled")) {
            yamlConfig().set("debug.web-ui.enabled", false);
            changed = true;
        }
        if (!yamlConfig().contains("debug.web-ui.port")) {
            yamlConfig().set("debug.web-ui.port", 2000);
            changed = true;
        }
        if (!yamlConfig().contains("storage.sql.type")) {
            yamlConfig().set("storage.sql.type", "sqlite");
            changed = true;
        }
        if (!yamlConfig().contains("storage.sql.sqlite.file")) {
            yamlConfig().set("storage.sql.sqlite.file", "storage/data.db");
            changed = true;
        }
        if (!yamlConfig().contains("storage.sql.mysql.host")) {
            yamlConfig().set("storage.sql.mysql.host", "127.0.0.1");
            changed = true;
        }
        if (!yamlConfig().contains("storage.sql.mysql.port")) {
            yamlConfig().set("storage.sql.mysql.port", 3306);
            changed = true;
        }
        if (!yamlConfig().contains("storage.sql.mysql.database")) {
            yamlConfig().set("storage.sql.mysql.database", "muz");
            changed = true;
        }
        if (!yamlConfig().contains("storage.sql.mysql.username")) {
            yamlConfig().set("storage.sql.mysql.username", "root");
            changed = true;
        }
        if (!yamlConfig().contains("storage.sql.mysql.password")) {
            yamlConfig().set("storage.sql.mysql.password", "");
            changed = true;
        }
        if (!yamlConfig().contains("storage.sql.mysql.parameters")) {
            yamlConfig().set("storage.sql.mysql.parameters", "useSSL=false&characterEncoding=utf8&serverTimezone=Asia/Shanghai");
            changed = true;
        }
        return changed;
    }

    private void loadRoomLevelProfiles() {
        roomLevelProfiles.clear();
        for (TableLevel level : TableLevel.values()) {
            String base = "room-levels." + level.key();
            String label = normalizeNonBlank(yamlConfig().getString(base + ".label"), level.defaultLabel());
            double multiplier = Math.max(0.0, yamlConfig().getDouble(base + ".multiplier", level.defaultMultiplier()));
            boolean economyEnabled = yamlConfig().getBoolean(base + ".economy-enabled", level.defaultEconomyEnabled());
            roomLevelProfiles.put(level, new RoomLevelProfile(level, label, multiplier, economyEnabled));
        }
    }

    private void initializePersistence() {
        if (databaseManager == null) {
            return;
        }
        postRestoreRebuildQueued = false;
        if (!databaseManager.initialize()) {
            pendingPersistedTables = List.of();
            persistedTableRestoreSummary = "牌桌恢复未启动";
            return;
        }
        List<PersistedTableRecord> loaded = new ArrayList<>();
        int removedDebug = 0;
        for (PersistedTableRecord record : databaseManager.loadTables()) {
            if (isDebugTableName(record.tableName())) {
                databaseManager.deleteTable(record.gameType(), record.tableName());
                removedDebug++;
                continue;
            }
            loaded.add(record);
        }
        synchronized (persistedTableRestoreLock) {
            pendingPersistedTables = List.copyOf(loaded);
        }
        // 恢复改为异步派发后，本方法仍是唯一的初始化入口：在这里把上一轮的 in-flight/计数清干净，
        // 避免 reload 场景残留上一代的飞行中 key 把新记录误判成「已在恢复中」。
        persistedTableRestoreInFlight.clear();
        persistedTableRestoredCount.set(0);
        persistedTableRestorePasses = 0;
        if (loaded.isEmpty()) {
            sqlTablesLoaded = true;
            persistedTableRestoreSummary = removedDebug > 0 ? "已清理 " + removedDebug + " 张旧观察桌记录" : "没有待恢复牌桌";
        } else {
            sqlTablesLoaded = false;
            postRestoreRebuildQueued = false;
            persistedTableRestoreSummary = "待恢复牌桌 " + loaded.size() + " 张";
        }
    }

    /**
     * 派发一轮存档牌桌恢复。
     *
     * <p>真实 Folia 上不能原地拉区块、更不能在 global lane 上动世界，所以每条记录只做「派发」
     * （{@code restoreTable} 现在返回异步 stage），实际世界体在锚点 region 里执行。派发用过
     * {@link #persistedTableRestoreInFlight} 按桌名去重：已在飞行中的桌直接跳过，这就是
     * 「不重复生成实体」的保证；同名的 {@code restoreTable} 内部还有一次 placed 表早退兜底。
     *
     * <p>回调结算：成功从 {@link #pendingPersistedTables} 摘除该记录并移出 in-flight；
     * 失败只移出 in-flight，记录留在 pending，交给下一个 5 秒 pass 重试。
     *
     * @return 是否「所有待恢复记录的 pending 与 in-flight 都已清空」，语义等同原来的 sqlTablesLoaded
     */
    private boolean restorePendingSqlTables() {
        if (databaseManager == null || !databaseManager.isInitialized()) {
            persistedTableRestoreSummary = "数据库尚未就绪";
            return false;
        }
        List<PersistedTableRecord> snapshot;
        synchronized (persistedTableRestoreLock) {
            snapshot = pendingPersistedTables;
            if (snapshot.isEmpty()) {
                // pending 为空并不等于完成：上一轮派发的恢复可能还在飞行中，必须等回调结算。
                boolean inFlightEmpty = persistedTableRestoreInFlight.isEmpty();
                persistedTableRestoreSummary = inFlightEmpty ? "没有待恢复牌桌" : "牌桌恢复仍在进行";
                return inFlightEmpty;
            }
        }

        int waitingWorld = 0;
        for (PersistedTableRecord record : snapshot) {
            org.bukkit.World world = Bukkit.getWorld(record.worldName());
            if (world == null) {
                // 世界尚未加载：保留在 pending，等世界建出来后的下一轮。
                waitingWorld++;
                continue;
            }
            if (!"DOUDIZHU".equalsIgnoreCase(record.gameType())) {
                // 德州玩法已移除，遗留的旧牌桌记录直接清理掉，避免每次启动都尝试恢复。
                databaseManager.deleteTable(record.gameType(), record.tableName());
                removePendingPersistedTable(record);
                continue;
            }
            String key = persistedTableRestoreKey(record.tableName());
            if (!persistedTableRestoreInFlight.add(key)) {
                // 同一张桌的恢复已在飞行中（上一轮派发、回调尚未结算），跳过以免重复生成实体。
                continue;
            }
            org.bukkit.Location anchor = new org.bukkit.Location(world, record.x(), record.y(), record.z(), record.yaw(), 0.0f);
            // 回调在派发之后才跑，passes 会被后续 pass 递增；先冻结派发时的 pass，保证「首轮才打失败日志」的节流语义。
            final int dispatchPass = persistedTableRestorePasses;
            try {
                physicalTableManager
                    .restoreTable(record.tableName(), record.roomLevel(), anchor, record.yaw(),
                        parseNullableUuid(record.ownerUuid()), record.ownerName())
                    .whenComplete((table, failure) -> {
                        if (failure == null) {
                            // 先摘 pending 再移出 in-flight：反过来的话，两者之间的一轮 pass 会因为
                            // in-flight 已空而把这条已成功的记录重新派发一遍。
                            removePendingPersistedTable(record);
                            persistedTableRestoreInFlight.remove(key);
                            persistedTableRestoredCount.incrementAndGet();
                        } else {
                            // 失败只移出 in-flight，记录留在 pending，等下个 5 秒 pass 重试。
                            persistedTableRestoreInFlight.remove(key);
                            if (dispatchPass <= 1) {
                                getLogger().warning("恢复牌桌失败 [" + record.gameType() + "/" + record.tableName() + "]: " + failure.getMessage());
                            }
                        }
                    });
            } catch (RuntimeException exception) {
                // 派发本身同步抛错（anchor/参数不合法）：移出 in-flight，留下个 pass 重试。
                persistedTableRestoreInFlight.remove(key);
                if (dispatchPass <= 1) {
                    getLogger().warning("恢复牌桌失败 [" + record.gameType() + "/" + record.tableName() + "]: " + exception.getMessage());
                }
            }
        }

        persistedTableRestorePasses++;
        boolean done;
        int pendingCount;
        int inFlightCount;
        synchronized (persistedTableRestoreLock) {
            pendingCount = pendingPersistedTables.size();
            inFlightCount = persistedTableRestoreInFlight.size();
            done = pendingCount == 0 && inFlightCount == 0;
        }
        if (done) {
            persistedTableRestoreSummary = "牌桌恢复完成，本次恢复 " + persistedTableRestoredCount.get() + " 张";
            return true;
        }
        // 「已恢复」用本次启动的累计成功数（异步下成功分散在各回调里，单轮派发数不等于成功数）。
        // 「待重试」= pending 里既不在飞行中、也非世界未加载的残留，即上一轮失败等待重试的数量。
        int failed = Math.max(0, pendingCount - inFlightCount - waitingWorld);
        persistedTableRestoreSummary = "已恢复 " + persistedTableRestoredCount.get() + " 张，待世界加载 "
            + waitingWorld + " 张，待重试 " + failed + " 张";
        return false;
    }

    /** in-flight 去重与 pending 摘除共用的桌名归一化；与 PhysicalTableManager 的 normalize 同口径（去空白+小写）。 */
    private static String persistedTableRestoreKey(String tableName) {
        return tableName == null ? "" : tableName.trim().toLowerCase(Locale.ROOT);
    }

    /**
     * 从 {@link #pendingPersistedTables} 摘除一条已成功恢复的记录（不可变替换 + 锁保护）。
     *
     * <p>用 {@code record} 的值相等做摘除，重复调用是幂等的：成功回调与随后可能发生的
     * 重复派发（restoreTable 幂等早退）都会经过这里。
     */
    private void removePendingPersistedTable(PersistedTableRecord record) {
        synchronized (persistedTableRestoreLock) {
            List<PersistedTableRecord> current = pendingPersistedTables;
            if (!current.contains(record)) {
                return;
            }
            List<PersistedTableRecord> next = new ArrayList<>(current);
            next.remove(record);
            pendingPersistedTables = List.copyOf(next);
        }
    }

    private void schedulePersistedTableRestore() {
        scheduler().runTimer(1L, 100L, task -> {
            if (sqlTablesLoaded || shuttingDown) {
                task.cancel();
                return;
            }
            attemptPersistedTableRestore();
            if (sqlTablesLoaded) {
                task.cancel();
            }
        });
    }

    public void attemptPersistedTableRestore() {
        if (sqlTablesLoaded || shuttingDown || databaseManager == null || !databaseManager.isInitialized()) {
            return;
        }
        sqlTablesLoaded = restorePendingSqlTables();
        if (sqlTablesLoaded) {
            schedulePostRestoreRebuilds();
            getLogger().info(
                "已恢复牌桌: 斗地主 "
                    + (physicalTableManager == null ? 0 : physicalTableManager.placedTableCount())
                    + " 张 | " + persistedTableRestoreSummary
            );
        } else if (persistedTableRestorePasses <= 1 || persistedTableRestorePasses % 10 == 0) {
            getLogger().info("牌桌还在恢复: " + persistedTableRestoreSummary);
        }
    }

    private void schedulePostRestoreRebuilds() {
        if (postRestoreRebuildQueued || shuttingDown) {
            return;
        }
        // 门禁用 hasPlacedOrRebuildingTables 而不是 placedTableCount：重建改为异步 stage 流水线后，
        // 只看瞬时 placed 数量会在"尚未收口提交"的窗口里把预热 pass 整批跳过（判据见 PhysicalTableManager）。
        if (physicalTableManager == null || !physicalTableManager.hasPlacedOrRebuildingTables()) {
            return;
        }
        postRestoreRebuildQueued = true;
        // Startup restore can finish before CraftEngine and distant chunks are visually stable.
        // Run a couple of delayed rebuild passes, effectively doing an automatic "warmup reload" for persisted tables.
        long[] delays = {40L, 120L, 240L};
        for (long delay : delays) {
            scheduler().runLater(delay, () -> {
                if (shuttingDown) {
                    return;
                }
                if (physicalTableManager != null && physicalTableManager.hasPlacedOrRebuildingTables()) {
                    // 机制变更：异步流水线；每一轮预热都独立推进，失败记录日志而不静默丢弃。
                    physicalTableManager.rebuildAllTables().exceptionally(failure -> {
                        getLogger().warning("存档桌预热重建失败: delay=" + delay
                            + "，原因=" + failure.getMessage());
                        return null;
                    });
                }
            });
        }
    }

    public void persistDoudizhuTable(String tableName, TableLevel roomLevel, org.bukkit.Location anchor, float yaw, UUID ownerId, String ownerName) {
        if (databaseManager == null || anchor == null || anchor.getWorld() == null || isDebugTableName(tableName)) {
            return;
        }
        databaseManager.upsertTable(new PersistedTableRecord(
            "DOUDIZHU",
            tableName,
            roomLevel == null ? TableLevel.FUN : roomLevel,
            anchor.getWorld().getName(),
            anchor.getX(),
            anchor.getY(),
            anchor.getZ(),
            yaw,
            3,
            ownerId == null ? null : ownerId.toString(),
            normalizeNonBlank(ownerName, "")
        ));
    }

    private UUID parseNullableUuid(String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        try {
            return UUID.fromString(raw.trim());
        } catch (IllegalArgumentException ignored) {
            return null;
        }
    }

    public void deletePersistedTable(String gameType, String tableName) {
        if (databaseManager == null) {
            return;
        }
        databaseManager.deleteTable(gameType, tableName);
    }

    private void refreshAllPlacedTables() {
        if (physicalTableManager != null) {
            for (GameTable table : tableManager.getTables()) {
                physicalTableManager.refresh(table);
            }
        }
    }

    private String normalizeItemModelId(String value, String fallback) {
        if (isBlank(value)) {
            return fallback;
        }
        NamespacedKey key = NamespacedKey.fromString(value.trim());
        return key == null ? fallback : key.asString();
    }

    private boolean isDebugTableName(String tableName) {
        return tableName != null && tableName.trim().toLowerCase(Locale.ROOT).startsWith("debug-");
    }

    private Component historyTitle(int index, PlayerHistoryEntry entry) {
        boolean win = entry.self() != null && "WIN".equalsIgnoreCase(entry.self().outcome());
        String gameLabel = entry.match().gameType().equalsIgnoreCase("DOUDIZHU") ? "斗地主" : "德州";
        return concat(
            gradientLabel("[MUMU 战绩-" + index + "]", HISTORY_CYAN, HISTORY_GOLD),
            historyDivider(),
            gradientLabel(gameLabel, HISTORY_CYAN, HISTORY_GOLD),
            historyDivider(),
            historyOutcomeChip(win)
        );
    }

    /**
     * 渲染角色标签 + 玩家身份的通用行前缀。
     * <p>被 historyLandlordLine / historyFarmerLines / historySelfLine 共用。</p>
     */
    private Component historyRoleLine(String roleLabel, String startColor, String endColor,
                                       UUID playerId, String playerName) {
        return concat(
            gradientLabel(roleLabel, startColor, endColor),
            Component.text(" ", NamedTextColor.WHITE).decoration(TextDecoration.ITALIC, false),
            playerIdentityComponent(playerId, playerName, NamedTextColor.WHITE)
        );
    }

    private Component historySelfLine(PlayerHistoryEntry entry) {
        MatchParticipantRecord self = entry.self();
        if (self == null) {
            return plain(Component.text("玩家信息缺失", NamedTextColor.GRAY));
        }
        return concat(
            historyRoleLine("玩家", HISTORY_CYAN, HISTORY_SKY, self.playerId(), self.playerName()),
            historyDivider(),
            historyRoleChip(self.roleLabel())
        );
    }

    private Component historyLandlordLine(PlayerHistoryEntry entry) {
        MatchParticipantRecord landlord = entry.participants().stream()
            .filter(participant -> "地主".equals(participant.roleLabel()))
            .findFirst()
            .orElse(null);
        if (landlord == null) {
            return plain(Component.text("地主 未知", NamedTextColor.GRAY));
        }
        return historyRoleLine("地主", HISTORY_PINK, HISTORY_ROSE, landlord.playerId(), landlord.playerName());
    }

    private List<Component> historyFarmerLines(PlayerHistoryEntry entry) {
        List<MatchParticipantRecord> farmers = entry.participants().stream()
            .filter(participant -> "农民".equals(participant.roleLabel()))
            .toList();
        List<Component> lines = new ArrayList<>(farmers.size());
        for (MatchParticipantRecord farmer : farmers) {
            lines.add(historyRoleLine("农民", HISTORY_GOLD, HISTORY_CREAM, farmer.playerId(), farmer.playerName()));
        }
        return lines;
    }

    /**
     * 渲染结算行通用的 收入/支出/净变化 尾部组件。
     * <p>被 historyPersonalSettlementLine 和 historyParticipantLine 共用。</p>
     */
    private Component settlementTail(double delta, String unit) {
        double income = Math.max(0.0, delta);
        double expense = Math.max(0.0, -delta);
        NamedTextColor deltaColor = delta >= 0 ? NamedTextColor.GREEN : NamedTextColor.RED;
        return concat(
            gradientLabel("收入", HISTORY_GREEN, HISTORY_MINT),
            Component.text(" " + formatAmount(income) + unit, NamedTextColor.GREEN).decoration(TextDecoration.ITALIC, false),
            historyDivider(),
            gradientLabel("支出", HISTORY_PINK, HISTORY_RED),
            Component.text(" " + formatAmount(expense) + unit, expense > 0.0001 ? NamedTextColor.RED : NamedTextColor.DARK_GRAY).decoration(TextDecoration.ITALIC, false),
            historyDivider(),
            historyNetChip(delta >= 0),
            Component.text(" " + formatSigned(delta) + unit, deltaColor).decoration(TextDecoration.ITALIC, false)
        );
    }

    private Component historyPersonalSettlementLine(PlayerHistoryEntry entry) {
        MatchParticipantRecord self = entry.self();
        if (self == null) {
            return plain(Component.text("个人结算 未知", NamedTextColor.GRAY));
        }
        boolean win = "WIN".equalsIgnoreCase(self.outcome());
        String unit = selfUnitLabel(entry);
        Component line = concat(
            historyMatchChip(win),
            historyDivider(),
            historyOutcomeChip(win),
            historyDivider(),
            historyGainLossChip(self.settlementDelta() >= 0, formatAmount(Math.abs(self.settlementDelta())) + unit),
            historyDivider(),
            settlementTail(self.settlementDelta(), unit)
        );
        if (self.debtAfter() > 0.0001) {
            line = line.append(Component.text(" | 欠 " + formatCompactAmount(self.debtAfter()) + unit, NamedTextColor.RED));
        }
        if (self.bankrupt()) {
            line = line.append(Component.text(" | 已破产", NamedTextColor.RED));
        }
        return plain(line);
    }

    private Component historyParticipantLine(MatchParticipantRecord participant) {
        String unit = normalizeNonBlank(participant.unitLabel(), "金币");
        return concat(
            Component.text("• ", NamedTextColor.DARK_GRAY).decoration(TextDecoration.ITALIC, false),
            playerIdentityComponent(participant.playerId(), participant.playerName(), NamedTextColor.WHITE),
            historyDivider(),
            historyRoleChip(participant.roleLabel()),
            historyDivider(),
            historyGainLossChip(participant.settlementDelta() >= 0, formatAmount(Math.abs(participant.settlementDelta())) + unit),
            historyDivider(),
            settlementTail(participant.settlementDelta(), unit)
        );
    }

    private Component historyTimeLocationLine(PlayerHistoryEntry entry) {
        MatchRecord match = entry.match();
        String world = normalizeNonBlank(match.worldName(), "unknown");
        String time = HISTORY_TIME_FORMAT.format(Instant.ofEpochMilli(match.occurredAt()));
        String place = world + " (" + formatAmount(match.x()) + ", " + formatAmount(match.y()) + ", " + formatAmount(match.z()) + ")";
        return concat(
            Component.text(time, NamedTextColor.WHITE).decoration(TextDecoration.ITALIC, false),
            Component.text(" · ", NamedTextColor.DARK_GRAY).decoration(TextDecoration.ITALIC, false),
            Component.text(place, NamedTextColor.GRAY).decoration(TextDecoration.ITALIC, false)
        );
    }

    private Component historyParticipantSectionTitle() {
        return gradientLabel("其他玩家", HISTORY_CYAN, HISTORY_GOLD);
    }

    /**
     * 根据正/负状态渲染带颜色切换的标签芯片。
     * <p>被 historyMatchChip / historyNetChip 等共用。</p>
     */
    private Component historyToggleChip(String text, boolean positive) {
        return positive
            ? gradientLabel(text, HISTORY_CYAN, HISTORY_GREEN)
            : gradientLabel(text, HISTORY_PINK, HISTORY_RED);
    }

    private Component historyMatchChip(boolean win) {
        return historyToggleChip("对局", win);
    }

    private Component historyOutcomeChip(boolean win) {
        return win ? gradientLabel("胜利", HISTORY_CYAN, HISTORY_GREEN) : gradientLabel("失利", HISTORY_PINK, HISTORY_RED);
    }

    private Component historyNetChip(boolean positive) {
        return historyToggleChip("净变化", positive);
    }

    private Component historyGainLossChip(boolean positive, String amountText) {
        return positive
            ? gradientLabel("赢了 " + amountText, HISTORY_CYAN, HISTORY_GREEN)
            : gradientLabel("输了 " + amountText, HISTORY_PINK, HISTORY_RED);
    }

    private Component historyRoleChip(String roleLabel) {
        if ("地主".equals(roleLabel)) {
            return gradientLabel("地主", HISTORY_PINK, HISTORY_ROSE);
        }
        if ("农民".equals(roleLabel)) {
            return gradientLabel("农民", HISTORY_GOLD, HISTORY_CREAM);
        }
        return gradientLabel(normalizeNonBlank(roleLabel, "玩家"), HISTORY_CYAN, HISTORY_SKY);
    }

    private Component gradientLabel(String text, String startColor, String endColor) {
        return mini("<gradient:" + startColor + ":" + endColor + "><bold>" + text + "</bold></gradient>");
    }

    private Component mini(String raw) {
        return plain(MINI.deserialize(raw));
    }

    private Component historyDivider() {
        return Component.text(" | ", NamedTextColor.DARK_GRAY).decoration(TextDecoration.ITALIC, false);
    }

    private Component concat(Component... components) {
        return MuzTheme.concat(components);
    }

    private String selfUnitLabel(PlayerHistoryEntry entry) {
        MatchParticipantRecord self = entry.self();
        if (self == null) {
            return "";
        }
        return normalizeNonBlank(self.unitLabel(), "金币");
    }

    private String formatSigned(double value) {
        return formatSignedCompactAmount(value);
    }

    /**
     * 判断浮点值是否实质上为整数（误差 < 0.0001）。
     * <p>被 formatAmount / compactNumber 共用，避免重复的整数检测逻辑。</p>
     */
    private static boolean isEffectivelyInteger(double value) {
        return Math.abs(value - Math.rint(value)) < 0.0001;
    }

    private String formatAmount(double value) {
        if (isEffectivelyInteger(value)) {
            return String.valueOf((long) Math.rint(value));
        }
        return String.format(Locale.ROOT, "%.2f", value);
    }

    public String formatCompactAmount(double value) {
        double abs = Math.abs(value);
        if (abs >= 100000000.0) {
            return String.format(Locale.ROOT, "%.2f", abs / 100000000.0) + "亿";
        }
        if (abs >= 10000.0) {
            return String.format(Locale.ROOT, "%.2f", abs / 10000.0) + "万";
        }
        return compactNumber(abs);
    }

    public String formatSignedCompactAmount(double value) {
        return (value >= 0 ? "+" : "-") + formatCompactAmount(Math.abs(value));
    }

    private String compactNumber(double value) {
        if (isEffectivelyInteger(value)) {
            return String.valueOf((long) Math.rint(value));
        }
        String formatted = String.format(Locale.ROOT, "%.2f", value);
        return formatted.replaceAll("0+$", "").replaceAll("\\.$", "");
    }

    private boolean usesBlockChairPlacement() {
        ItemStack configured = getConfiguredFurnitureItem(FurnitureType.CHAIR);
        if (configured != null) {
            if (craftEngineFurnitureService != null) {
                CraftEngineFurnitureService.ResolvedItem resolved = craftEngineFurnitureService.resolveCustomItem(configured);
                if (resolved != null) {
                    return resolved.kind() == CraftEngineFurnitureService.PlacementKind.BLOCK;
                }
            }
            return configured.getType().isBlock();
        }
        if (craftEngineFurnitureService != null) {
            for (String itemId : getChairFurnitureItemIdCandidates()) {
                CraftEngineFurnitureService.PlacementKind kind = craftEngineFurnitureService.detectPlacementKind(itemId);
                if (kind == CraftEngineFurnitureService.PlacementKind.BLOCK) {
                    return true;
                }
                if (kind == CraftEngineFurnitureService.PlacementKind.FURNITURE) {
                    return false;
                }
            }
        }
        return false;
    }

    private boolean usesBlockTablePlacement() {
        ItemStack configured = getConfiguredFurnitureItem(FurnitureType.TABLE);
        if (configured != null) {
            if (craftEngineFurnitureService != null) {
                CraftEngineFurnitureService.ResolvedItem resolved = craftEngineFurnitureService.resolveCustomItem(configured);
                if (resolved != null) {
                    return resolved.kind() == CraftEngineFurnitureService.PlacementKind.BLOCK;
                }
            }
            return configured.getType().isBlock();
        }
        if (craftEngineFurnitureService != null) {
            for (String itemId : getTableFurnitureItemIdCandidates()) {
                CraftEngineFurnitureService.PlacementKind kind = craftEngineFurnitureService.detectPlacementKind(itemId);
                if (kind == CraftEngineFurnitureService.PlacementKind.BLOCK) {
                    return true;
                }
                if (kind == CraftEngineFurnitureService.PlacementKind.FURNITURE) {
                    return false;
                }
            }
        }
        return false;
    }

    private double adminSettingStep(AdminSetting setting) {
        if (setting == AdminSetting.TABLE_SPAWN_OFFSET_Y && usesBlockTablePlacement()) {
            return 1.0;
        }
        if (setting == AdminSetting.CHAIR_ROTATION_DEGREES && usesBlockChairPlacement()) {
            return 90.0;
        }
        if (setting == AdminSetting.CHAIR_DISTANCE && usesBlockChairPlacement()) {
            return 1.0;
        }
        // 直接用各项自己声明的 step 作为兜底。
        //
        // 原先非白名单项会被 Math.max(0.1, ...) 抬到至少 0.1，
        // 那是为了配合当时"只保留一位小数"的存储精度；
        // 现在存储保留三位小数，不需要再抬底，
        // 否则声明了 0.02 步长的设置（如手牌宽高缩放）会被硬拉成 0.1。
        return setting.step();
    }

    private double normalizeAdminCurrentValue(AdminSetting setting, double current) {
        if (setting == AdminSetting.TABLE_SPAWN_OFFSET_Y && usesBlockTablePlacement()) {
            return normalizeBlockTableOffset(current);
        }
        if (setting == AdminSetting.CHAIR_ROTATION_DEGREES && usesBlockChairPlacement()) {
            return normalizeBlockChairRotation(current);
        }
        if (setting == AdminSetting.CHAIR_DISTANCE && usesBlockChairPlacement()) {
            return normalizeBlockChairDistance(current);
        }
        // 同 normalizeAdminStoredValue：统一走存储精度（五位小数）。
        // 这里若按更粗的精度取整，0.01 / 0.0001 的加减会在读出当前值时就被抹平，
        // 表现为连点多次数字都不动。
        return roundToStorePrecision(current);
    }

    private double normalizeAdminStoredValue(AdminSetting setting, double value) {
        if (setting == AdminSetting.TABLE_SPAWN_OFFSET_Y && usesBlockTablePlacement()) {
            return normalizeBlockTableOffset(value);
        }
        if (setting == AdminSetting.CHAIR_ROTATION_DEGREES && usesBlockChairPlacement()) {
            return normalizeBlockChairRotation(value);
        }
        if (setting == AdminSetting.CHAIR_DISTANCE && usesBlockChairPlacement()) {
            return normalizeBlockChairDistance(value);
        }
        // 统一走 AdminSettingArithmetic 的存储精度（五位小数）。
        //
        // 原先这里对不在 usesFinePrecision 白名单里的设置调 roundToSingleDecimal，
        // 结果 0.01 步长会被四舍五入抹成 0 或 0.1，等于步长失效。
        // 白名单只有 17 项，漏掉了桌子高度、弧度等一堆设置。
        // 现在精度既能吃住 0.01 步长，也能吃住压层类设置的 0.0001 固定步长。
        return roundToStorePrecision(value);
    }

    private double roundToSingleDecimal(double value) {
        return Math.round(value * 10.0) / 10.0;
    }

    /**
     * 按存储精度取整，用于消浮点噪音又不破坏细步长（0.01 / 0.0001）。
     *
     * @param value 原始值
     * @return 按存储精度取整后的值
     */
    private double roundToStorePrecision(double value) {
        return linmumua.doudizhu.config.AdminSettingArithmetic.roundToStorePrecision(value);
    }

    private double normalizeBlockChairRotation(double value) {
        return linmumua.doudizhu.config.AdminSettingArithmetic.snapToBlockChairRotation(value);
    }

    private double normalizeBlockChairDistance(double value) {
        return Math.rint(value);
    }

    private double normalizeBlockTableOffset(double value) {
        return Math.rint(value);
    }

    private List<String> furnitureItemIdCandidates(String rawValue, String fallbackKey) {
        java.util.LinkedHashSet<String> candidates = new java.util.LinkedHashSet<>();
        String normalized = normalizeItemModelId(rawValue, "muz:furniture/" + fallbackKey);
        candidates.add(normalized);
        NamespacedKey key = NamespacedKey.fromString(normalized);
        if (key != null) {
            String path = key.getKey();
            if (path.startsWith("item/furniture/")) {
                candidates.add(key.getNamespace() + ":" + path.substring("item/furniture/".length()));
            }
            if (path.startsWith("item/")) {
                candidates.add(key.getNamespace() + ":" + path.substring("item/".length()));
            }
            if (path.startsWith("furniture/")) {
                candidates.add(key.getNamespace() + ":" + path.substring("furniture/".length()));
            }
            int slash = path.lastIndexOf('/');
            if (slash >= 0 && slash + 1 < path.length()) {
                candidates.add(key.getNamespace() + ":" + path.substring(slash + 1));
            }
        }
        return List.copyOf(candidates);
    }

    private List<String> furnitureItemIdCandidates(ItemStack itemStack, String rawFallbackValue, String fallbackKey) {
        java.util.LinkedHashSet<String> candidates = new java.util.LinkedHashSet<>();
        if (itemStack == null || itemStack.getType().isAir()) {
            return rawFallbackValue == null ? List.of() : furnitureItemIdCandidates(rawFallbackValue, fallbackKey);
        }
        ItemMeta meta = itemStack.getItemMeta();
        if (meta != null && VersionCompat.supportsItemModel()) {
            if (meta.hasItemModel()) {
                candidates.addAll(furnitureItemIdCandidates(meta.getItemModel().toString(), fallbackKey));
            }
        }
        addCandidatesFromTranslationKey(candidates, itemStack.translationKey());
        if (!candidates.isEmpty()) {
            return List.copyOf(candidates);
        }
        return rawFallbackValue == null ? List.of() : furnitureItemIdCandidates(rawFallbackValue, fallbackKey);
    }

    private void addCandidatesFromTranslationKey(java.util.Set<String> candidates, String translationKey) {
        if (isBlank(translationKey)) {
            return;
        }
        String normalized = translationKey.trim().toLowerCase(java.util.Locale.ROOT);
        String[] parts = normalized.split("\\.");
        if (parts.length < 3) {
            return;
        }
        String namespace = parts[1];
        String path = String.join("_", java.util.Arrays.copyOfRange(parts, 2, parts.length));
        if (!namespace.isBlank() && !path.isBlank()) {
            candidates.add(namespace + ":" + path);
        }
    }

    private String normalizeNonBlank(String value, String fallback) {
        return isBlank(value) ? fallback : value.trim();
    }

    private Color parseRgbSpec(String raw, Color fallback) {
        if (isBlank(raw)) {
            return fallback;
        }
        String[] parts = raw.trim().split("\\s*,\\s*");
        if (parts.length != 3) {
            return fallback;
        }
        try {
            int red = Math.clamp(Integer.parseInt(parts[0]), 0, 255);
            int green = Math.clamp(Integer.parseInt(parts[1]), 0, 255);
            int blue = Math.clamp(Integer.parseInt(parts[2]), 0, 255);
            return Color.fromRGB(red, green, blue);
        } catch (NumberFormatException exception) {
            return fallback;
        }
    }

    private int packRgb(Color color) {
        return (color.getRed() << 16) | (color.getGreen() << 8) | color.getBlue();
    }

    private Color unpackRgb(int packed) {
        return Color.fromRGB((packed >> 16) & 255, (packed >> 8) & 255, packed & 255);
    }

    private String rgbLabel(Color color) {
        return color.getRed() + "," + color.getGreen() + "," + color.getBlue();
    }

    private String packedRgbLabel(int packed) {
        return rgbLabel(unpackRgb(packed));
    }

    private boolean isBlank(String value) {
        return value == null || value.isBlank();
    }

    private void loadPlayerSettings() {
        playerCardLabelSettings.clear();
        playerSelectionSoundSettings.clear();
        playerSelectionSoundProfileSettings.clear();
        playerPlayActionProfileSettings.clear();
        playerPlayActionKindProfileSettings.clear();
        playerHoverGlowColorSettings.clear();
        playerSelectedGlowColorSettings.clear();
        playerHandOffsets.clear();
        if (playerSettingsFile == null) {
            return;
        }
        playerSettingsConfig = new MuzYamlConfig(playerSettingsFile.toPath());
        for (String rawId : playerSettingsConfig.getKeys("players")) {
            try {
                UUID playerId = UUID.fromString(rawId);
                String base = "players." + rawId;
                if (playerSettingsConfig.contains(base + ".labels-enabled")) {
                    playerCardLabelSettings.put(playerId, playerSettingsConfig.getBoolean(base + ".labels-enabled", false));
                }
                if (playerSettingsConfig.contains(base + ".selection-sound")) {
                    playerSelectionSoundSettings.put(playerId, playerSettingsConfig.getBoolean(base + ".selection-sound", false));
                }
                if (playerSettingsConfig.contains(base + ".selection-sound-profile")) {
                    playerSelectionSoundProfileSettings.put(playerId, clampProfileIndex(playerSettingsConfig.getInt(base + ".selection-sound-profile", 0)));
                }
                if (playerSettingsConfig.contains(base + ".play-action-profile")) {
                    playerPlayActionProfileSettings.put(playerId, clampProfileIndex(playerSettingsConfig.getInt(base + ".play-action-profile", 0)));
                }
                EnumMap<PlayActionKind, Integer> typed = new EnumMap<>(PlayActionKind.class);
                String actionProfilesBase = base + ".play-action-profiles";
                for (PlayActionKind kind : PlayActionKind.values()) {
                    if (playerSettingsConfig.contains(actionProfilesBase + "." + kind.key())) {
                        typed.put(kind, clampProfileIndex(playerSettingsConfig.getInt(actionProfilesBase + "." + kind.key(), getPlayerPlayActionProfileIndex(playerId))));
                    }
                }
                if (!typed.isEmpty()) {
                    playerPlayActionKindProfileSettings.put(playerId, typed);
                }
                if (playerSettingsConfig.contains(base + ".hover-glow-color")) {
                    playerHoverGlowColorSettings.put(playerId, clampGlowColorIndex(playerSettingsConfig.getInt(base + ".hover-glow-color", 0)));
                }
                if (playerSettingsConfig.contains(base + ".selected-glow-color")) {
                    playerSelectedGlowColorSettings.put(playerId, clampGlowColorIndex(playerSettingsConfig.getInt(base + ".selected-glow-color", 0)));
                }
                if (playerSettingsConfig.contains(base + ".hand-offset.lateral")
                    || playerSettingsConfig.contains(base + ".hand-offset.vertical")
                    || playerSettingsConfig.contains(base + ".hand-offset.depth")
                    || playerSettingsConfig.contains(base + ".hand-offset.spacing")
                    || playerSettingsConfig.contains(base + ".hand-offset.preview-scale")) {
                    PlayerHandOffsets offsets = new PlayerHandOffsets(
                        roundToSingleDecimal(playerSettingsConfig.getDouble(base + ".hand-offset.lateral", 0.0)),
                        roundToSingleDecimal(playerSettingsConfig.getDouble(base + ".hand-offset.vertical", 0.0)),
                        roundToSingleDecimal(playerSettingsConfig.getDouble(base + ".hand-offset.depth", 0.0)),
                        roundToSingleDecimal(playerSettingsConfig.getDouble(base + ".hand-offset.spacing", 0.0)),
                        roundToSingleDecimal(playerSettingsConfig.getDouble(base + ".hand-offset.preview-scale", 0.0))
                    ).normalized();
                    if (!offsets.isZero()) {
                        playerHandOffsets.put(playerId, offsets);
                    }
                }
            } catch (IllegalArgumentException e) {
                getLogger().warning("加载玩家设置失败，跳过条目 '" + rawId + "': " + e.getMessage());
            }
        }
    }

    private static void clearManagedPlayerSettings(MuzYamlConfig configuration, String base) {
        configuration.set(base + ".labels-enabled", null);
        configuration.set(base + ".selection-sound", null);
        configuration.set(base + ".selection-sound-profile", null);
        configuration.set(base + ".play-action-profile", null);
        for (PlayActionKind kind : PlayActionKind.values()) {
            configuration.set(base + ".play-action-profiles." + kind.key(), null);
        }
        configuration.set(base + ".hover-glow-color", null);
        configuration.set(base + ".selected-glow-color", null);
        configuration.set(base + ".hand-offset.lateral", null);
        configuration.set(base + ".hand-offset.vertical", null);
        configuration.set(base + ".hand-offset.depth", null);
        configuration.set(base + ".hand-offset.spacing", null);
        configuration.set(base + ".hand-offset.preview-scale", null);
    }

    private void savePlayerSettings() {
        if (playerSettingsFile == null) {
            return;
        }
        // 以磁盘现有 YAML 树为基础合并，只清理本类负责的已知键；未知键及
        // players.<uuid>.gadget-bar 必须原样保留，不能再从空配置重建 players 根节点。
        MuzYamlConfig configuration = new MuzYamlConfig(playerSettingsFile.toPath());
        Set<String> existingPlayerIds = new LinkedHashSet<>(configuration.getKeys("players"));
        Set<UUID> players = new LinkedHashSet<>();
        players.addAll(playerCardLabelSettings.keySet());
        players.addAll(playerSelectionSoundSettings.keySet());
        players.addAll(playerSelectionSoundProfileSettings.keySet());
        players.addAll(playerPlayActionProfileSettings.keySet());
        players.addAll(playerPlayActionKindProfileSettings.keySet());
        players.addAll(playerHoverGlowColorSettings.keySet());
        players.addAll(playerSelectedGlowColorSettings.keySet());
        players.addAll(playerHandOffsets.keySet());
        for (String rawId : existingPlayerIds) {
            clearManagedPlayerSettings(configuration, "players." + rawId);
        }
        for (UUID playerId : players) {
            String base = "players." + playerId;
            if (playerCardLabelSettings.containsKey(playerId)) {
                configuration.set(base + ".labels-enabled", playerCardLabelSettings.get(playerId));
            }
            if (playerSelectionSoundSettings.containsKey(playerId)) {
                configuration.set(base + ".selection-sound", playerSelectionSoundSettings.get(playerId));
            }
            if (playerSelectionSoundProfileSettings.containsKey(playerId)) {
                configuration.set(base + ".selection-sound-profile", playerSelectionSoundProfileSettings.get(playerId));
            }
            if (playerPlayActionProfileSettings.containsKey(playerId)) {
                configuration.set(base + ".play-action-profile", playerPlayActionProfileSettings.get(playerId));
            }
            if (playerPlayActionKindProfileSettings.containsKey(playerId)) {
                EnumMap<PlayActionKind, Integer> typed = playerPlayActionKindProfileSettings.get(playerId);
                for (Map.Entry<PlayActionKind, Integer> entry : typed.entrySet()) {
                    configuration.set(base + ".play-action-profiles." + entry.getKey().key(), entry.getValue());
                }
            }
            if (playerHoverGlowColorSettings.containsKey(playerId)) {
                configuration.set(base + ".hover-glow-color", playerHoverGlowColorSettings.get(playerId));
            }
            if (playerSelectedGlowColorSettings.containsKey(playerId)) {
                configuration.set(base + ".selected-glow-color", playerSelectedGlowColorSettings.get(playerId));
            }
            if (playerHandOffsets.containsKey(playerId)) {
                PlayerHandOffsets offsets = playerHandOffsets.get(playerId).normalized();
                if (!offsets.isZero()) {
                    configuration.set(base + ".hand-offset.lateral", offsets.lateral());
                    configuration.set(base + ".hand-offset.vertical", offsets.vertical());
                    configuration.set(base + ".hand-offset.depth", offsets.depth());
                    configuration.set(base + ".hand-offset.spacing", offsets.spacing());
                    configuration.set(base + ".hand-offset.preview-scale", offsets.previewScale());
                }
            }
        }
        try {
            getDataFolder().mkdirs();
            configuration.save();
            playerSettingsConfig = configuration;
        } catch (IOException exception) {
            getLogger().warning("保存玩家微调设置失败: " + exception.getMessage());
        }
    }

    private void loadOptionProfiles() {
        ensureOptionProfilesStorage();
        selectionSoundProfiles.clear();
        playActionProfiles.clear();
        playActionProfilesByKind.clear();
        for (int index = 0; index < PLAYER_OPTION_PROFILE_COUNT; index++) {
            String legacySelectionBase = "player-options.selection-sound-profiles.profile-" + (index + 1);
            String selectionBase = "selection-sound-profiles.profile-" + (index + 1);
            OptionProfile defaultSelection = defaultSelectionSoundProfile(index);
            selectionSoundProfiles.add(sanitizeSelectionSoundProfile(optionProfile(
                optionProfilesConfig.getString(selectionBase + ".label", yamlConfig().getString(legacySelectionBase + ".label", defaultSelection.label())),
                optionProfilesConfig.getString(selectionBase + ".spec", yamlConfig().getString(legacySelectionBase + ".spec", defaultSelection.spec())),
                true
            )));
            String legacyActionBase = "player-options.play-action-profiles.profile-" + (index + 1);
            String actionBase = "play-action-profiles.profile-" + (index + 1);
            OptionProfile defaultAction = defaultPlayActionProfile(index);
            playActionProfiles.add(sanitizePlayActionProfile(optionProfile(
                optionProfilesConfig.getString(actionBase + ".label", yamlConfig().getString(legacyActionBase + ".label", defaultAction.label())),
                optionProfilesConfig.getString(actionBase + ".spec", yamlConfig().getString(legacyActionBase + ".spec", defaultAction.spec())),
                false
            )));
        }
        for (PlayActionKind kind : PlayActionKind.values()) {
            List<OptionProfile> profiles = new ArrayList<>();
            for (int index = 0; index < PLAYER_OPTION_PROFILE_COUNT; index++) {
                String actionBase = "play-action-type-profiles." + kind.key() + ".profile-" + (index + 1);
                OptionProfile fallback = playActionProfiles.get(index);
                profiles.add(sanitizePlayActionProfile(optionProfile(
                    optionProfilesConfig.getString(actionBase + ".label", fallback.label()),
                    optionProfilesConfig.getString(actionBase + ".spec", fallback.spec()),
                    false
                )));
            }
            playActionProfilesByKind.put(kind, profiles);
            savePlayActionProfilesByKind(kind, profiles);
        }
        saveOptionProfilesToStorage("selection-sound-profiles", selectionSoundProfiles);
        saveOptionProfilesToStorage("play-action-profiles", playActionProfiles);
    }

    private void ensureOptionProfilesStorage() {
        if (optionProfilesFile == null) {
            optionProfilesFile = new File(getDataFolder(), "option-profiles.yml");
        }
        getDataFolder().mkdirs();
        optionProfilesConfig = new MuzYamlConfig(optionProfilesFile.toPath());
    }

    private void saveOptionProfilesToStorage(String basePath, List<OptionProfile> profiles) {
        ensureOptionProfilesStorage();
        for (int index = 0; index < profiles.size(); index++) {
            OptionProfile profile = profiles.get(index);
            String path = basePath + ".profile-" + (index + 1);
            optionProfilesConfig.set(path + ".label", profile.label());
            optionProfilesConfig.set(path + ".spec", profile.spec());
        }
        try {
            optionProfilesConfig.save();
        } catch (IOException exception) {
            getLogger().warning("保存音效/行为方案失败: " + exception.getMessage());
        }
    }

    private void savePlayActionProfilesByKind(PlayActionKind kind, List<OptionProfile> profiles) {
        ensureOptionProfilesStorage();
        for (int index = 0; index < profiles.size(); index++) {
            OptionProfile profile = profiles.get(index);
            String path = "play-action-type-profiles." + kind.key() + ".profile-" + (index + 1);
            optionProfilesConfig.set(path + ".label", profile.label());
            optionProfilesConfig.set(path + ".spec", profile.spec());
        }
        try {
            optionProfilesConfig.save();
        } catch (IOException exception) {
            getLogger().warning("保存按牌型动作方案失败: " + exception.getMessage());
        }
    }

    private void savePlayerProfileChoice(Map<UUID, Integer> settings, UUID playerId, int index) {
        int normalized = clampProfileIndex(index);
        if (normalized == 0) {
            settings.remove(playerId);
        } else {
            settings.put(playerId, normalized);
        }
        savePlayerSettings();
    }

    private int clampProfileIndex(int index) {
        return Math.clamp(index, 0, PLAYER_OPTION_PROFILE_COUNT - 1);
    }

    private OptionProfile optionProfile(String label, String spec, boolean soundProfile) {
        String normalizedLabel = normalizeNonBlank(label, "方案");
        String normalizedSpec = soundProfile ? normalizeSelectionSoundSpec(spec) : normalizePlayActionSpec(spec);
        return new OptionProfile(normalizedLabel, normalizedSpec);
    }

    private OptionProfile sanitizeSelectionSoundProfile(OptionProfile profile) {
        return new OptionProfile(
            normalizeNonBlank(profile.label(), "音效方案"),
            safeNormalizeSelectionSoundSpec(profile.spec())
        );
    }

    private OptionProfile sanitizePlayActionProfile(OptionProfile profile) {
        return new OptionProfile(
            normalizeNonBlank(profile.label(), "执行方案"),
            normalizePlayActionSpec(profile.spec())
        );
    }

    private String safeNormalizeSelectionSoundSpec(String rawSpec) {
        try {
            return normalizeSelectionSoundSpec(rawSpec);
        } catch (IllegalArgumentException exception) {
            getLogger().warning("选牌音效方案无效，已回退默认值: " + exception.getMessage());
            return DEFAULT_SELECTION_SOUND_SPEC;
        }
    }

    public String normalizePlayActionSpec(String rawSpec) {
        String value = rawSpec == null ? "" : rawSpec.trim();
        if (value.isEmpty()) {
            return "type: none";
        }
        return value;
    }

    public OptionProfile defaultSelectionSoundProfile(int index) {
        return switch (clampProfileIndex(index)) {
            case 0 -> new OptionProfile("清脆提示", DEFAULT_SELECTION_SOUND_SPEC);
            case 1 -> new OptionProfile("告示牌提示", "minecraft:block.hanging_sign.place 0.4 1.1 0.9");
            case 2 -> new OptionProfile("洞穴提示", "minecraft:ambient.cave 0.3 1.0 0.9");
            default -> new OptionProfile("静音", "minecraft:block.note_block.hat 0.0 1.0 1.0");
        };
    }

    public OptionProfile defaultPlayActionProfile(int index) {
        return switch (clampProfileIndex(index)) {
            case 0 -> new OptionProfile("无操作", "type: none");
            case 1 -> new OptionProfile("聊天提示", "type: message; message: <#8FD4FF>出牌完成</#8FD4FF><dark_gray> · </dark_gray><#F1D398><arg:pattern></#F1D398>");
            case 2 -> new OptionProfile("动作栏提示", "type: actionbar; actionbar: <#9AA8B6><arg:player.name></#9AA8B6><dark_gray> · </dark_gray><#F1D398><arg:pattern></#F1D398>");
            default -> new OptionProfile("播放音效", "type: play_sound; sound: minecraft:entity.player.levelup; volume: 0.35; pitch: 1.05; source: master");
        };
    }

    private boolean togglePlayerPreference(UUID playerId, Map<UUID, Boolean> settings, boolean defaultValue) {
        boolean next = !settings.getOrDefault(playerId, defaultValue);
        if (next == defaultValue) {
            settings.remove(playerId);
        } else {
            settings.put(playerId, next);
        }
        savePlayerSettings();
        return next;
    }

    public String normalizeSelectionSoundSpec(String rawSpec) {
        return normalizeSoundSpec(rawSpec, DEFAULT_SELECTION_SOUND_SPEC, 4);
    }

    public String normalizeCountdownSoundSpec(String rawSpec) {
        return normalizeSoundSpec(rawSpec, DEFAULT_COUNTDOWN_SOUND_SPEC, 3);
    }

    private String safeNormalizeCountdownSoundSpec(String rawSpec) {
        try {
            return normalizeCountdownSoundSpec(rawSpec);
        } catch (IllegalArgumentException exception) {
            getLogger().warning("倒计时音效配置无效，已回退默认值: " + exception.getMessage());
            return DEFAULT_COUNTDOWN_SOUND_SPEC;
        }
    }

    private String normalizeSoundSpec(String rawSpec, String fallback, int maxParts) {
        String value = rawSpec == null ? "" : rawSpec.trim();
        if (value.isEmpty()) {
            return fallback;
        }
        String[] parts = value.split("\\s+");
        if (parts.length == 0 || parts.length > maxParts) {
            throw new IllegalArgumentException("音效格式不正确。");
        }
        String soundKey = parts[0].trim();
        if (soundKey.isEmpty()) {
            throw new IllegalArgumentException("音效名不能为空。");
        }
        StringBuilder builder = new StringBuilder(soundKey);
        for (int index = 1; index < parts.length; index++) {
            float parsed;
            try {
                parsed = Float.parseFloat(parts[index]);
            } catch (NumberFormatException exception) {
                throw new IllegalArgumentException("音效参数必须是数字。");
            }
            builder.append(' ').append(String.format(java.util.Locale.ROOT, "%.1f", parsed));
        }
        return builder.toString();
    }

    private SelectionSound parseSelectionSound(String rawSpec) {
        String[] parts = normalizeSelectionSoundSpec(rawSpec).split("\\s+");
        String key = parts[0];
        float volume = parts.length >= 2 ? Float.parseFloat(parts[1]) : 0.35f;
        float selectedPitch = parts.length >= 3 ? Float.parseFloat(parts[2]) : 1.15f;
        float deselectedPitch = parts.length >= 4 ? Float.parseFloat(parts[3]) : 0.85f;
        return new SelectionSound(key, volume, selectedPitch, deselectedPitch);
    }

    private ConfiguredSound parseConfiguredSound(String rawSpec) {
        String[] parts = normalizeCountdownSoundSpec(rawSpec).split("\\s+");
        String key = parts[0];
        float volume = parts.length >= 2 ? Float.parseFloat(parts[1]) : 0.45f;
        float pitch = parts.length >= 3 ? Float.parseFloat(parts[2]) : 1.0f;
        return new ConfiguredSound(key, volume, pitch);
    }

    public enum HandOffsetAxis {
        LATERAL,
        VERTICAL,
        DEPTH,
        SPACING,
        PREVIEW_SCALE
    }

    public enum AdminSetting {
        TABLE_SPAWN_OFFSET_Y("table.spawn-offset-y", "桌子高度", -0.55, -5.0, 5.0, 0.05, false, false, false),
        PRIVATE_CARD_SCALE("render.private-card-scale", "实体手牌大小", DEFAULT_PRIVATE_CARD_SCALE, 0.10, 5.0, 0.02, false, false, false),
        PRIVATE_CARD_WIDTH_SCALE("render.private-card-size.width", "手牌宽度缩放", DEFAULT_PRIVATE_CARD_AXIS_SCALE, 0.05, 5.0, 0.02, false, false, false),
        PRIVATE_CARD_HEIGHT_SCALE("render.private-card-size.height", "手牌高度缩放", DEFAULT_PRIVATE_CARD_AXIS_SCALE, 0.05, 5.0, 0.02, false, false, false),
        PRIVATE_CARD_DEPTH_SCALE("render.private-card-size.depth", "手牌厚度缩放", DEFAULT_PRIVATE_CARD_AXIS_SCALE, 0.01, 5.0, 0.02, false, false, false),
        // 悬停突出效果由这一项独家承担：牌只放大长与宽，厚度恒定、位置不动。
        // 曾经并存的「悬停向后偏移」已删除——沿法向平移会让射线与牌平面的交点跟着
        // 悬停漂移，形成命中即脱靶的抖动闭环；长宽放大不移动牌面所在的平面，安全。
        HOVER_CARD_SCALE("render.card-hover.scale", "悬停放大倍数", 1.08, 1.0, 2.5, 0.01, false, false, false),
        HOVER_CARD_LIFT("render.card-hover.lift", "悬停上移高度", 0.06, 0.0, 1.0, 0.01, false, false, false),
        HOVER_CARD_INTERPOLATION_TICKS("render.card-hover.interpolation-ticks", "牌预览动画时长", 6.0, 1.0, 20.0, 1.0, false, true, false),
        HOVER_CARD_ANIMATION_TYPE("render.card-hover.animation-type", "牌预览动画类型", 1.0, 0.0, 3.0, 1.0, false, true, false),
        HAND_SPACING("render.hand-spacing", "默认手牌间距", 0.1, 0.02, 2.0, 0.01, false, false, false),
        CARD_LABEL_HEIGHT("render.card-label-height", "牌面标签高度", 0.05, 0.0, 3.0, 0.02, false, false, false),
        CARD_LABEL_LATERAL("render.card-label-offset.lateral", "牌面标签左右偏移", 0.05, -2.0, 2.0, 0.02, false, false, false),
        CARD_LABEL_DEPTH("render.card-label-offset.depth", "牌面标签前后偏移", 0.0, -2.0, 2.0, 0.02, false, false, false),
        STATUS_NAME_SCALE("render.status-name.scale", "顶栏名字大小", 0.46, 0.20, 4.0, 0.05, false, false, false),
        STATUS_NAME_LATERAL("render.status-name-offset.lateral", "顶栏名字左右偏移", 0.0, -3.0, 3.0, 0.02, false, false, false),
        STATUS_NAME_VERTICAL("render.status-name-offset.vertical", "顶栏名字上下偏移", 0.56, -2.0, 4.0, 0.05, false, false, false),
        STATUS_NAME_DEPTH("render.status-name-offset.depth", "顶栏名字前后偏移", 0.0, -3.0, 3.0, 0.02, false, false, false),
        // SEAT_NAME_* 四项已停用：入座后的字条改为统一读 EMPTY_SEAT_*。
        // 两组出厂默认值本来就相同，但玩家只调得到看得见的空位字条，
        // 一入座就切到没调过的 SEAT_NAME_*，字条尺寸和位置会瞬间跳变。
        // 枚举项与配置键保留（含槽位 10/12/14/16 的 GUI 入口），兼容既有 config.yml。
        SEAT_NAME_SCALE("render.seat-name.scale", "座位名字大小", 0.46, 0.20, 4.0, 0.05, false, false, false),
        SEAT_NAME_LATERAL("render.seat-name-offset.lateral", "座位名字左右偏移", 0.0, -3.0, 3.0, 0.02, false, false, false),
        SEAT_NAME_VERTICAL("render.seat-name-offset.vertical", "座位名字上下偏移", -0.04, -2.0, 4.0, 0.05, false, false, false),
        SEAT_NAME_DEPTH("render.seat-name-offset.depth", "座位名字前后偏移", 0.0, -3.0, 3.0, 0.02, false, false, false),
        EMPTY_SEAT_SCALE("render.empty-seat.scale", "空位主文字大小", 0.9, 0.20, 4.0, 0.05, false, false, false),
         EMPTY_SEAT_LATERAL("render.empty-seat-offset.lateral", "空位主文字左右偏移", 0.0, -3.0, 3.0, 0.02, false, false, false),
        EMPTY_SEAT_VERTICAL("render.empty-seat-offset.vertical", "空位主文字上下偏移", 1.5, -2.0, 4.0, 0.05, false, false, false),
        EMPTY_SEAT_DEPTH("render.empty-seat-offset.depth", "空位主文字前后偏移", 0.0, -3.0, 3.0, 0.02, false, false, false),
        SEAT_INFO_SCALE("render.seat-info.scale", "座位副标题大小", 0.5, 0.20, 4.0, 0.05, false, false, false),
        SEAT_INFO_LATERAL("render.seat-info-offset.lateral", "座位副标题左右偏移", 0.0, -3.0, 3.0, 0.02, false, false, false),
        SEAT_INFO_VERTICAL("render.seat-info-offset.vertical", "座位副标题上下偏移", -0.5, -2.0, 4.0, 0.05, false, false, false),
        SEAT_INFO_DEPTH("render.seat-info-offset.depth", "座位副标题前后偏移", 0.0, -3.0, 3.0, 0.02, false, false, false),
        HOVER_GLOW_ENABLED("render.hover-glow.enabled", "预览发光", 1.0, 0.0, 1.0, 1.0, true, false, true),
        HOVER_GLOW_RED("render.hover-glow.color.red", "预览发光红", 96.0, 0.0, 255.0, 1.0, false, true, false),
        HOVER_GLOW_GREEN("render.hover-glow.color.green", "预览发光绿", 180.0, 0.0, 255.0, 1.0, false, true, false),
        HOVER_GLOW_BLUE("render.hover-glow.color.blue", "预览发光蓝", 255.0, 0.0, 255.0, 1.0, false, true, false),
        SELECTED_GLOW_ENABLED("render.selected-glow.enabled", "预选发光", 1.0, 0.0, 1.0, 1.0, true, false, true),
        SELECTED_GLOW_RED("render.selected-glow.color.red", "预选发光红", 255.0, 0.0, 255.0, 1.0, false, true, false),
        SELECTED_GLOW_GREEN("render.selected-glow.color.green", "预选发光绿", 226.0, 0.0, 255.0, 1.0, false, true, false),
        SELECTED_GLOW_BLUE("render.selected-glow.color.blue", "预选发光蓝", 92.0, 0.0, 255.0, 1.0, false, true, false),
        BUTTON_DISTANCE("render.button-offset.distance", "按钮离桌距离", DEFAULT_BUTTON_DISTANCE, 0.20, 4.0, 0.05, false, false, false),
        BUTTON_HEIGHT("render.button-offset.height", "按钮高度", 2.5, 0.20, 4.0, 0.05, false, false, false),
        // 角度类设置：固定 1 度步长，全局 0.01 调角度没有意义。
        // 方块椅模式下 normalizeBlockChairRotation 仍会把结果吸附到 90 度整数倍。
        CHAIR_ROTATION_DEGREES("render.chair-rotation-degrees", "椅子旋转角度", 180.0, -360.0, 360.0, 5.0, false, false, false, 1.0),
        CHAIR_DISTANCE("render.layout.chair-distance", "椅子离桌距离", 2.5, 1.0, 8.0, 0.05, false, false, false),
        CHAIR_VISUAL_LATERAL("render.chair-visual-offset.lateral", "椅子左右偏移", 0.0, -2.0, 2.0, 0.02, false, false, false),
        CHAIR_VISUAL_VERTICAL("render.chair-visual-offset.vertical", "椅子上下偏移", 0.35, -2.0, 2.0, 0.02, false, false, false),
        CHAIR_HITBOX_LATERAL("render.chair-hitbox-offset.lateral", "加入按钮交互箱左右偏移", 0.0, -2.0, 2.0, 0.02, false, false, false),
        CHAIR_HITBOX_VERTICAL("render.chair-hitbox-offset.vertical", "加入按钮交互箱上下偏移", 0.02, -2.0, 2.0, 0.02, false, false, false),
        BUTTON_HITBOX_LATERAL("render.button-hitbox-offset.lateral", "按钮交互箱左右偏移", 0.0, -2.0, 2.0, 0.02, false, false, false),
        BUTTON_HITBOX_DEPTH("render.button-hitbox-offset.depth", "按钮交互箱前后偏移", 0.0, -2.0, 2.0, 0.02, false, false, false),
        BUTTON_HITBOX_VERTICAL("render.button-hitbox-offset.vertical", "按钮交互箱上下偏移", 0.02, -2.0, 2.0, 0.02, false, false, false),
        // 压层类设置：固定 0.0001 步长。
        // min 从 0.01 下调到 0.0001：原来的下限正好等于默认值，
        // 0.0001 步长往下调会被立刻夹回 0.01，等于只能加不能减。
        // 下限跟步长对齐后才调得动，且仍然大于 0，不会退化成"完全不压层"。
        CARD_DEPTH_OFFSET("render.card-depth-offset", "手牌压层深度", 0.005, 0.0001, 1.0, 0.01, false, false, false, 0.0001),
        STATUS_HEIGHT("render.status-height", "状态文字高度", 5.0, 0.0, 10.0, 0.05, false, false, false),
        PLAY_DETAIL_HEIGHT("render.play-detail-height", "上一手文字高度", 4.0, 0.0, 10.0, 0.05, false, false, false),
        GLOBAL_HAND_LATERAL("render.private-hand-offset.lateral", "全局手牌横向偏移", 0.03, -5.0, 5.0, 0.02, false, false, false),
        GLOBAL_HAND_VERTICAL("render.private-hand-offset.vertical", "全局手牌竖向偏移", 1.9, -5.0, 5.0, 0.02, false, false, false),
        GLOBAL_HAND_DEPTH("render.private-hand-offset.depth", "全局手牌纵深偏移", 0.55, -5.0, 5.0, 0.02, false, false, false),
        LABELS_ENABLED("cards.hologram-labels.enabled", "全局点数标签", 1.0, 0.0, 1.0, 1.0, true, false, true),
        DUPLICATE_ONLY("cards.hologram-labels.duplicate-ranks-only", "仅重复牌显示标签", 0.0, 0.0, 1.0, 1.0, true, false, false),
        BGM_VOLUME("audio.bgm-volume", "背景音乐音量", 0.55, 0.0, 6.0, 0.05, false, false, false),
        EFFECT_VOLUME("audio.effect-volume", "音效音量", 1.0, 0.0, 6.0, 0.05, false, false, false),
        TURN_COUNTDOWN_SECONDS("actionbar.turn-countdown-seconds", "回合倒计时秒数", 20.0, 0.0, 120.0, 1.0, false, true, false),
        BOT_DELAY_MIN("bot.action-delay-min-ticks", "机器人最短思考", 10.0, 0.0, 200.0, 1.0, false, true, false),
        BOT_DELAY_MAX("bot.action-delay-max-ticks", "机器人最长思考", 30.0, 0.0, 400.0, 1.0, false, true, false),
        HINT_GROUP_LIMIT("hints.max-groups", "提示组数上限", 6.0, 1.0, 20.0, 1.0, false, true, false),
        JOIN_LABEL_HEIGHT("render.button-layout.join-label-height", "空位加入文字高度", 0.18, 0.0, 3.0, 0.02, false, false, false),
        JOIN_LABEL_SCALE("render.button-layout.join-label-scale", "空位加入文字大小", 0.4, 0.08, 4.0, 0.05, false, false, false),
        ACTION_LABEL_HEIGHT("render.button-layout.action-label-height", "按钮文字高度", 0.2, 0.0, 3.0, 0.02, false, false, false),
        ACTION_LABEL_SCALE("render.button-layout.action-label-scale", "按钮文字大小", 0.4, 0.08, 4.0, 0.05, false, false, false),
        BUTTON_FRONT_BASE_DISTANCE("render.button-layout.front-base-distance", "前座按钮基准距离", 1.40, 0.2, 5.0, 0.02, false, false, false),
        BUTTON_SIDE_BASE_DISTANCE("render.button-layout.side-base-distance", "侧座按钮基准距离", 1.72, 0.2, 5.0, 0.02, false, false, false),
        BUTTON_DISTANCE_FACTOR("render.button-layout.distance-factor", "按钮距离增量系数", 0.45, 0.0, 2.0, 0.01, false, false, false),
        BUTTON_SPACING("render.button-layout.spacing-scale", "按钮间距倍率", 1.2, 0.2, 2.5, 0.02, false, false, false),
        // 角度类设置：固定 1 度步长。
        BUTTON_ARC_SMALL_ANGLE("render.button-layout.arc-angle-small", "三按钮弧度", 30.0, 0.0, 90.0, 1.0, false, false, false, 1.0),
        BUTTON_ARC_LARGE_ANGLE("render.button-layout.arc-angle-large", "多按钮弧度", 42.0, 0.0, 120.0, 1.0, false, false, false, 1.0),
        BUTTON_ARC_SMALL_RADIUS("render.button-layout.arc-radius-small", "三按钮半径", 0.70, 0.05, 3.0, 0.02, false, false, false),
        BUTTON_ARC_LARGE_RADIUS("render.button-layout.arc-radius-large", "多按钮半径", 0.8, 0.05, 3.0, 0.02, false, false, false);

        private final String path;
        private final String label;
        private final double defaultValue;
        private final double minValue;
        private final double maxValue;
        private final double step;
        private final boolean booleanSetting;
        private final boolean integerSetting;
        private final boolean defaultBoolean;
        private final double fixedStep;

        AdminSetting(String path, String label, double defaultValue, double minValue, double maxValue, double step, boolean booleanSetting, boolean integerSetting, boolean defaultBoolean) {
            this(path, label, defaultValue, minValue, maxValue, step, booleanSetting, integerSetting, defaultBoolean, 0.0);
        }

        AdminSetting(String path, String label, double defaultValue, double minValue, double maxValue, double step, boolean booleanSetting, boolean integerSetting, boolean defaultBoolean, double fixedStep) {
            this.path = path;
            this.label = label;
            this.defaultValue = defaultValue;
            this.minValue = minValue;
            this.maxValue = maxValue;
            this.step = step;
            this.booleanSetting = booleanSetting;
            this.integerSetting = integerSetting;
            this.defaultBoolean = defaultBoolean;
            this.fixedStep = fixedStep;
        }

        public String path() {
            return path;
        }

        public String label() {
            return label;
        }

        public double defaultValue() {
            return defaultValue;
        }

        public double minValue() {
            return minValue;
        }

        public double maxValue() {
            return maxValue;
        }

        public double step() {
            return step;
        }

        public boolean booleanSetting() {
            return booleanSetting;
        }

        public boolean integerSetting() {
            return integerSetting;
        }

        public boolean defaultBoolean() {
            return defaultBoolean;
        }

        /**
         * 该设置自己钉死的步长，不吃玩家在 GUI 里选的全局微调步长。
         *
         * 做成枚举元数据而不是放在 HandGuiListener 里判定：
         * 步长本来就和上下限、声明步长一样属于"这一项的数值语义"，
         * 放在枚举里只有一处定义，新增设置时也不会漏改 Listener。
         *
         * @return 固定步长；返回 0 表示这一项跟随全局步长
         */
        public double fixedStep() {
            return fixedStep;
        }

        /**
         * 是否钉死了自己的步长。
         *
         * @return true 表示该项忽略全局微调步长
         */
        public boolean hasFixedStep() {
            return Double.isFinite(fixedStep) && fixedStep > 0.0;
        }

        /**
         * 这几项写 0 是它的真实取值，不表示"用默认值"。
         *
         * 绝大多数数值项在 config.yml 里出厂写 0，含义是"没调过，用源码里固化的默认"。
         * 但下面这些项的 0 本身就是一档有效设置：音量 0 是静音、倒计时 0 是关闭、
         * 机器人最短思考 0 是立刻出牌、显示模式与动画类型的 0 是第一个档位。
         * 它们必须按字面值读，否则玩家把音量调到 0 会被当成"没设置"而弹回 0.55。
         *
         * 放在枚举里而不是各读取点各判一次：这属于"这一项的数值语义"，
         * 和上下限、步长同级，只在一处定义，新增设置时不会漏改。
         */
        private static final java.util.Set<String> ZERO_IS_REAL_VALUE = java.util.Set.of(
            "audio.bgm-volume",
            "audio.effect-volume",
            "actionbar.turn-countdown-seconds",
            "bot.action-delay-min-ticks",
            "render.card-hover.interpolation-ticks",
            "render.card-hover.animation-type"
        );

        /**
         * @return true 表示这一项的 0 按字面值生效，不会被换成默认值
         */
        public boolean zeroIsRealValue() {
            return ZERO_IS_REAL_VALUE.contains(path);
        }
    }

    public enum FurnitureType {
        TABLE("craftengine-items.table", DEFAULT_TABLE_ITEM_MODEL, DEFAULT_TABLE_DISPLAY_NAME, "桌子"),
        CHAIR("craftengine-items.chair", DEFAULT_CHAIR_ITEM_MODEL, DEFAULT_CHAIR_DISPLAY_NAME, "椅子");

        private final String configBasePath;
        private final String defaultItemModelId;
        private final String defaultDisplayName;
        private final String label;

        FurnitureType(String configBasePath, String defaultItemModelId, String defaultDisplayName, String label) {
            this.configBasePath = configBasePath;
            this.defaultItemModelId = defaultItemModelId;
            this.defaultDisplayName = defaultDisplayName;
            this.label = label;
        }

        public String configBasePath() {
            return configBasePath;
        }

        public String defaultItemModelId() {
            return defaultItemModelId;
        }

        public String defaultDisplayName() {
            return defaultDisplayName;
        }

        public String label() {
            return label;
        }
    }

    private record PlayerHandOffsets(double lateral, double vertical, double depth, double spacing, double previewScale) {
        private static final double EPSILON = 0.0001;
        private static final PlayerHandOffsets ZERO = new PlayerHandOffsets(0.0, 0.0, 0.0, 0.0, 0.0);

        private PlayerHandOffsets normalized() {
            return new PlayerHandOffsets(
                round(lateral),
                round(vertical),
                round(depth),
                round(spacing),
                round(previewScale)
            );
        }

        private boolean isZero() {
            return Math.abs(lateral) < EPSILON
                && Math.abs(vertical) < EPSILON
                && Math.abs(depth) < EPSILON
                && Math.abs(spacing) < EPSILON
                && Math.abs(previewScale) < EPSILON;
        }

        private static double round(double value) {
            return Math.round(value * 10.0) / 10.0;
        }
    }

    public record SelectionSound(String key, float volume, float selectedPitch, float deselectedPitch) {
    }

    public record ConfiguredSound(String key, float volume, float pitch) {
    }

    public record OptionProfile(String label, String spec) {
    }

    private record PlaceholderTarget(PlaceholderTargetKind kind, GameTable gameTable, UUID playerId) {
        private static PlaceholderTarget doudizhu(GameTable table, UUID playerId) {
            return new PlaceholderTarget(PlaceholderTargetKind.DOUDIZHU, table, playerId);
        }
    }

    private enum PlaceholderTargetKind {
        DOUDIZHU
    }

    public enum AnimationCurve {
        LINEAR("线性"),
        EASE_OUT("缓出"),
        EASE_IN_OUT("缓入缓出"),
        BACK_OUT("回弹");

        private final String label;

        AnimationCurve(String label) {
            this.label = label;
        }

        public String label() {
            return label;
        }

        public static AnimationCurve fromIndex(int index) {
            AnimationCurve[] values = values();
            int normalized = Math.clamp(index, 0, values.length - 1);
            return values[normalized];
        }
    }

    private record GlowColorOption(String label, Color color) {
    }

    public record BotHandle(int numericId, UUID botId, String tableName, BotGameType gameType) {
    }

    public enum BotGameType {
        DOUDIZHU
    }

    public enum TableMode {
        DOUDIZHU
    }

    public enum PlayActionKind {
        AIRPLANE("airplane", "飞机"),
        STRAIGHT("straight", "顺子"),
        PAIR_STRAIGHT("pair_straight", "连对"),
        TRIPLE_WITH_SINGLE("triple_with_single", "三带一"),
        BOMB("bomb", "炸弹"),
        JOKER_BOMB("joker_bomb", "王炸");

        private final String key;
        private final String label;

        PlayActionKind(String key, String label) {
            this.key = key;
            this.label = label;
        }

        public String key() {
            return key;
        }

        public String label() {
            return label;
        }

        public static PlayActionKind fromPattern(linmumua.doudizhu.model.CardPattern pattern) {
            if (pattern == null) {
                return null;
            }
            return switch (pattern.type()) {
                case AIRPLANE, AIRPLANE_WITH_SINGLES, AIRPLANE_WITH_PAIRS -> AIRPLANE;
                case STRAIGHT -> STRAIGHT;
                case PAIR_STRAIGHT -> PAIR_STRAIGHT;
                case TRIPLE_WITH_SINGLE, TRIPLE_WITH_PAIR -> TRIPLE_WITH_SINGLE;
                case BOMB -> BOMB;
                case JOKER_BOMB -> JOKER_BOMB;
                default -> null;
            };
        }
    }

    private void logStartupSummary(CraftEngineBundleExporter.BundleExportResult exportResult, List<HookSnapshot> hooks) {
        int consoleWidth = detectConsoleWidth();
        String[] art = startupArt(consoleWidth);
        List<String> loadingLines = buildStartupInfoLines(exportResult, hooks, consoleWidth);
        int contentWidth = Math.min(
            Math.max(maxWidth(art), maxWidth(loadingLines.toArray(String[]::new))),
            Math.max(56, consoleWidth - 2)
        );
        String separator = "=".repeat(Math.max(56, contentWidth));
        int totalGradientLines = art.length + 1 + loadingLines.size();
        getLogger().info(separator);
        for (int index = 0; index < art.length; index++) {
            getLogger().info(applyStartupGradient(padRight(art[index], separator.length()), index, totalGradientLines));
        }
        getLogger().info(separator);
        getLogger().info(applyStartupGradient(centerLine("linmumua | MUZ v" + getDescription().getVersion(), separator.length()), art.length, totalGradientLines));
        for (int index = 0; index < loadingLines.size(); index++) {
            getLogger().info(applyStartupGradient(fitToWidth(loadingLines.get(index), separator.length()), art.length + 1 + index, totalGradientLines));
        }
        getLogger().info("声明：娱乐插件严禁赌博！");
    }

    private List<String> buildStartupInfoLines(CraftEngineBundleExporter.BundleExportResult exportResult, List<HookSnapshot> hooks, int consoleWidth) {
        int barWidth = consoleWidth >= 130 ? 20 : consoleWidth >= 104 ? 18 : 16;
        List<String> lines = new ArrayList<>();
        lines.add(startupInfoPart(0.18, "配置", "config.yml 读取完成", barWidth));
        lines.add(startupInfoPart(0.38, "CraftEngine", describeHookCompact(findHook(hooks, "ce")), barWidth));
        lines.add(startupInfoPart(0.58, "PAPI", describeHookCompact(findHook(hooks, "papi")), barWidth));
        lines.add(startupInfoPart(0.68, "Vault", describeHookCompact(findHook(hooks, "vault")), barWidth));
        lines.add(startupInfoPart(0.78, "AI 出牌", aiStatusSummary(), barWidth));
        lines.add(startupInfoPart(0.88, "数据", databaseStatusSummary(), barWidth));
        lines.add(startupInfoPart(0.94, "材质", bundleSummaryPlain(exportResult), barWidth));
        lines.add(
            startupInfoPart(
                0.98,
                "牌桌",
                "斗地主 " + (physicalTableManager == null ? 0 : physicalTableManager.placedTableCount()) + " 张",
                barWidth
            )
        );
        lines.add(startupInfoPart(1.0, "就绪", "linmumua | MUZ v" + getDescription().getVersion(), barWidth));
        return lines;
    }

    private String startupInfoPart(double progress, String label, String detail, int barWidth) {
        return buildAsciiProgressBar(progress, barWidth) + " " + label + "=" + detail;
    }

    private String describeHookCompact(HookSnapshot hook) {
        if (hook == null) {
            return "未检测";
        }
        return hook.state().label() + " " + hook.detail();
    }

    private int detectConsoleWidth() {
        final int logPrefixWidth = 24;
        String[] candidates = {
            System.getProperty("jline.terminal.width"),
            System.getProperty("terminal.width"),
            System.getenv("COLUMNS")
        };
        for (String candidate : candidates) {
            Integer parsed = parsePositiveInt(candidate);
            if (parsed != null) {
                return Math.max(56, parsed - logPrefixWidth);
            }
        }
        return 96;
    }

    private Integer parsePositiveInt(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        try {
            int parsed = Integer.parseInt(value.trim());
            return parsed > 0 ? parsed : null;
        } catch (NumberFormatException exception) {
            return null;
        }
    }

    private String[] startupArt(int consoleWidth) {
        if (consoleWidth >= 84) {
            return new String[] {
                " __         __     __   __     __    __     __  __     __    __     __  __    ",
                "/\\ \\       /\\ \\   /\\ \"-.\\ \\   /\\ \"-./  \\   /\\ \\/\\ \\   /\\ \"-./  \\   /\\ \\/\\ \\   ",
                "\\ \\ \\____  \\ \\ \\  \\ \\ \\-.  \\  \\ \\ \\-./\\ \\  \\ \\ \\_\\ \\  \\ \\ \\-./\\ \\  \\ \\ \\_\\ \\  ",
                " \\ \\_____\\  \\ \\_\\  \\ \\_\\\\\"\\_\\  \\ \\_\\ \\ \\_\\  \\ \\_____\\  \\ \\_\\ \\ \\_\\  \\ \\_____\\ ",
                "  \\/_____/   \\/_/   \\/_/ \\/_/   \\/_/  \\/_/   \\/_____/   \\/_/  \\/_/   \\/_____/ "
            };
        }
        if (consoleWidth >= 56) {
            return new String[] {
                " __  __ _   _ ______",
                "|  \\/  | | | |___  /",
                "| |\\/| | |_| | / / ",
                "|_|  |_|\\___/ /_/  "
            };
        }
        return new String[] {"MUZ"};
    }

    private List<HookSnapshot> detectSupportedHooks(HookSnapshot placeholderHook, HookSnapshot vaultHook) {
        return List.of(detectCraftEngineHook(), placeholderHook, vaultHook);
    }

    private HookSnapshot detectCraftEngineHook() {
        org.bukkit.plugin.Plugin craftEngine = getServer().getPluginManager().getPlugin("CraftEngine");
        if (craftEngine == null) {
            return new HookSnapshot("ce", "CraftEngine", HookState.MISSING, "没装 CraftEngine，桌椅改用原版方块");
        }
        if (!craftEngine.isEnabled()) {
            return new HookSnapshot("ce", "CraftEngine", HookState.DISABLED, "没装 CraftEngine，桌椅改用原版方块");
        }
        if (craftEngineFurnitureService != null && craftEngineFurnitureService.isAvailable()) {
            return new HookSnapshot("ce", "CraftEngine", HookState.HOOKED, "CraftEngine 已接入，桌椅与材质可用");
        }
        return new HookSnapshot("ce", "CraftEngine", HookState.ERROR, "没装 CraftEngine，桌椅改用原版方块");
    }

    private void scheduleVaultHookRetries() {
        if (!vaultEconomyEnabled) {
            return;
        }
        List<Long> delays = List.of(20L, 60L, 120L);
        for (int index = 0; index < delays.size(); index++) {
            long delay = delays.get(index);
            boolean finalAttempt = index == delays.size() - 1;
            scheduler().runLater(delay, () -> {
                if (!isEnabled() || shuttingDown) {
                    return;
                }
                HookSnapshot previous = lastVaultHookSnapshot;
                HookSnapshot current = ensureVaultEconomyHookReadyInternal();
                if (current.state() == HookState.HOOKED
                    && (previous == null || previous.state() != HookState.HOOKED || !Objects.equals(previous.detail(), current.detail()))) {
                    getLogger().info("[MUZ] Vault 延迟挂钩成功: " + current.detail());
                    return;
                }
                if (finalAttempt && current.state() != HookState.HOOKED) {
                    logVaultHookDiagnosis(current);
                }
            });
        }
    }

    private void logVaultHookDiagnosis(HookSnapshot vaultHook) {
        if (vaultHook == null || vaultHook.state() == HookState.HOOKED || vaultHook.state() == HookState.DISABLED) {
            return;
        }
        getLogger().warning("未接入经济插件，不可结算");

    }

    private boolean isPluginEnabled(String name) {
        return getServer().getPluginManager().isPluginEnabled(name);
    }

    private double stageProgress(int stageIndex, int totalStages) {
        if (totalStages <= 0) {
            return 0.0;
        }
        double base = (double) stageIndex / (double) totalStages;
        return Math.clamp(base + 0.02, 0.05, 0.95);
    }

    private double stageProgress(int stageIndex, int totalStages, int currentStep, int totalSteps) {
        if (totalStages <= 0 || totalSteps <= 0) {
            return stageProgress(stageIndex, totalStages);
        }
        double perStage = 1.0 / (double) totalStages;
        double base = stageIndex * perStage;
        double withinStage = Math.clamp((double) currentStep / (double) totalSteps, 0.0, 1.0);
        return Math.clamp(base + withinStage * perStage, 0.05, 0.95);
    }

    private String rebuildDetail(String label, int count) {
        return count <= 0 ? "没有已放置的" + label : "即将重建 " + count + " 张" + label;
    }

    private String buildAsciiProgressBar(double progress, int width) {
        int normalizedWidth = Math.max(8, width);
        double clamped = Math.clamp(progress, 0.0, 1.0);
        int filled = (int) Math.round(clamped * normalizedWidth);
        StringBuilder builder = new StringBuilder();
        builder.append('[');
        for (int index = 0; index < normalizedWidth; index++) {
            builder.append(index < filled ? '=' : '-');
        }
        builder.append("] ");
        builder.append((int) Math.round(clamped * 100.0));
        builder.append('%');
        return builder.toString();
    }

    private HookSnapshot findHook(List<HookSnapshot> hooks, String key) {
        for (HookSnapshot hook : hooks) {
            if (hook.key().equalsIgnoreCase(key)) {
                return hook;
            }
        }
        return null;
    }

    private String bundleSummaryPlain(CraftEngineBundleExporter.BundleExportResult result) {
        return switch (result.state()) {
            // 只有真的拷过文件才提示重载。材质落进 CraftEngine 的 resources 目录不等于
            // 客户端能拿到：CraftEngine 是在自己启动时打 resource_pack.zip 的，而它按
            // paper-plugin.yml 的 load: BEFORE 先于 MUZ 加载，所以这一批文件进的是
            // 「已经打完包」的目录。不重载 CraftEngine 客户端下载到的仍是旧 zip，
            // 表现为字形变豆腐块，而这一行却是绿色的「已同步」，等于把人往错方向带。
            //
            // 不需要重启第二次：/muz reload 走 ensureBundleReady(force=true)，
            // 绕过指纹全量重拷一遍，效果和重启时的导出等价。
            // 所以恢复路径是「重载 CraftEngine → 客户端重新下载」，
            // 只有在 MUZ 这边的文件也需要重新推一遍时才额外跑一次 /muz reload。
            case EXPORTED -> "材质已同步 " + result.copiedEntries() + "/" + result.totalEntries()
                + " 个，还需重载 CraftEngine 让它重新打包，再让客户端重新下载资源包才会生效";
            case UP_TO_DATE -> "材质已是最新";
            case SKIPPED -> "跳过材质同步: " + result.detail();
            case FAILED -> "材质同步失败: " + result.detail();
        };
    }

    private NamedTextColor bundleSummaryColor(CraftEngineBundleExporter.BundleExportResult result) {
        return switch (result.state()) {
            case EXPORTED -> NamedTextColor.GREEN;
            case UP_TO_DATE -> NamedTextColor.AQUA;
            case SKIPPED -> NamedTextColor.GRAY;
            case FAILED -> NamedTextColor.RED;
        };
    }

    private String formatHookSummaryPlain(List<HookSnapshot> hooks) {
        List<String> parts = new ArrayList<>(hooks.size());
        for (HookSnapshot hook : hooks) {
            parts.add(hook.displayName() + "(" + hook.state().label() + ", " + hook.detail() + ")");
        }
        return String.join(" | ", parts);
    }

    private Component reloadSummaryComponent(ReloadSummary summary) {
        return plain(
            MuzTheme.success("MUZ 已重载")
                .append(MuzTheme.divider(" | "))
                .append(MuzTheme.warm("斗地主桌 " + summary.doudizhuTables() + " 张"))
                .append(MuzTheme.divider(" | "))
                .append(MuzTheme.named(bundleSummaryPlain(summary.bundleExport()), bundleSummaryColor(summary.bundleExport())))
        );
    }

    private String reloadSummaryPlain(ReloadSummary summary) {
        return "MUZ 已重载 | ddz="
            + summary.doudizhuTables()
            + " | "
            + bundleSummaryPlain(summary.bundleExport());
    }

    private Component hookSummaryComponent(List<HookSnapshot> hooks) {
        Component line = MuzTheme.warm("自动挂钩:");
        for (int index = 0; index < hooks.size(); index++) {
            HookSnapshot hook = hooks.get(index);
            if (index > 0) {
                line = line.append(MuzTheme.divider(" | "));
            }
            line = line.append(MuzTheme.body(hook.displayName() + " "));
            line = line.append(MuzTheme.named(hook.state().label(), hook.state().color()));
            line = line.append(MuzTheme.muted(" (" + hook.detail() + ")"));
        }
        return plain(line);
    }

    private Component bossBarComponent(String title, String detail) {
        Component component = MuzTheme.accent("MUZ 重载中 · " + title);
        if (detail != null && !detail.isBlank()) {
            component = component.append(MuzTheme.muted(" · " + detail));
        }
        return plain(component);
    }

    private Component plain(Component component) {
        return MuzTheme.plain(component);
    }

    private int maxWidth(String[] lines) {
        int max = 0;
        for (String line : lines) {
            max = Math.max(max, line.length());
        }
        return max;
    }

    private String centerLine(String line, int width) {
        if (line.length() >= width) {
            return line;
        }
        int left = (width - line.length()) / 2;
        return " ".repeat(Math.max(0, left)) + line;
    }

    private String padRight(String value, int width) {
        if (value.length() >= width) {
            return value;
        }
        return value + " ".repeat(width - value.length());
    }

    private String fitToWidth(String value, int width) {
        if (value.length() <= width) {
            return value;
        }
        if (width <= 1) {
            return value.substring(0, Math.max(0, width));
        }
        return value.substring(0, width - 1) + "…";
    }

    private String applyStartupGradient(String line, int lineIndex, int totalLines) {
        int visibleCount = 0;
        for (int index = 0; index < line.length(); index++) {
            if (line.charAt(index) != ' ') {
                visibleCount++;
            }
        }
        if (visibleCount == 0) {
            return line;
        }
        StringBuilder builder = new StringBuilder();
        int painted = 0;
        double verticalRatio = totalLines <= 1 ? 0.0 : Math.clamp((double) lineIndex / (double) (totalLines - 1), 0.0, 1.0);
        for (int index = 0; index < line.length(); index++) {
            char character = line.charAt(index);
            if (character == ' ') {
                builder.append(character);
                continue;
            }
            double horizontalRatio = visibleCount <= 1 ? 1.0 : (double) painted / (visibleCount - 1);
            RgbColor leftColor = interpolateColor(new RgbColor(255, 224, 92), new RgbColor(255, 128, 196), verticalRatio);
            RgbColor rightColor = interpolateColor(new RgbColor(64, 132, 255), new RgbColor(170, 245, 190), verticalRatio);
            RgbColor color = interpolateColor(leftColor, rightColor, horizontalRatio);
            builder.append("\u001B[38;2;")
                .append(color.red()).append(';')
                .append(color.green()).append(';')
                .append(color.blue()).append('m')
                .append(character);
            painted++;
        }
        builder.append("\u001B[0m");
        return builder.toString();
    }

    private RgbColor interpolateColor(RgbColor from, RgbColor to, double ratio) {
        double clamped = Math.clamp(ratio, 0.0, 1.0);
        return new RgbColor(
            (int) Math.round(from.red() + (to.red() - from.red()) * clamped),
            (int) Math.round(from.green() + (to.green() - from.green()) * clamped),
            (int) Math.round(from.blue() + (to.blue() - from.blue()) * clamped)
        );
    }

    private record ReloadSummary(
        CraftEngineBundleExporter.BundleExportResult bundleExport,
        List<HookSnapshot> hooks,
        int doudizhuTables
    ) {
    }

    private record HookSnapshot(String key, String displayName, HookState state, String detail) {
    }

    private record RoomLevelProfile(TableLevel level, String label, double multiplier, boolean economyEnabled) {
    }

    public enum SettlementStatus {
        SETTLED,
        FAILED,
        UNAVAILABLE
    }

    public record SettlementResult(
        double delta,
        double debt,
        double postBalance,
        boolean bankrupt,
        boolean insufficientForRoom,
        String unitLabel,
        SettlementStatus status
    ) {
        public SettlementResult(
            double delta,
            double debt,
            double postBalance,
            boolean bankrupt,
            boolean insufficientForRoom,
            String unitLabel
        ) {
            this(delta, debt, postBalance, bankrupt, insufficientForRoom, unitLabel, SettlementStatus.SETTLED);
        }

        public boolean hasCurrencySnapshot() {
            return status == SettlementStatus.SETTLED;
        }
    }

    private record RgbColor(int red, int green, int blue) {
    }

    private enum HookState {
        HOOKED("已挂钩", NamedTextColor.GREEN),
        DISABLED("未挂钩", NamedTextColor.YELLOW),
        MISSING("未挂钩", NamedTextColor.GRAY),
        ERROR("未挂钩", NamedTextColor.RED);

        private final String label;
        private final NamedTextColor color;

        HookState(String label, NamedTextColor color) {
            this.label = label;
            this.color = color;
        }

        public String label() {
            return label;
        }

        public NamedTextColor color() {
            return color;
        }
    }

    private interface ReloadFeedback {
        void update(double progress, String title, String detail);

        void complete(ReloadSummary summary);

        void recoveryFailed(String detail);

        static ReloadFeedback silent() {
            return SilentReloadFeedback.INSTANCE;
        }

        static ReloadFeedback create(DoudizhuPlugin plugin, CommandSender sender) {
            if (sender == null) {
                return silent();
            }
            return new SenderReloadFeedback(plugin, sender);
        }
    }

    private static final class SilentReloadFeedback implements ReloadFeedback {
        private static final SilentReloadFeedback INSTANCE = new SilentReloadFeedback();

        @Override
        public void update(double progress, String title, String detail) {
        }

        @Override
        public void complete(ReloadSummary summary) {
        }

        @Override
        public void recoveryFailed(String detail) {
        }
    }

    private static final class SenderReloadFeedback implements ReloadFeedback {
        private final DoudizhuPlugin plugin;
        private final CommandSender sender;
        private final Player player;
        private final BossBar bossBar;

        private SenderReloadFeedback(DoudizhuPlugin plugin, CommandSender sender) {
            this.plugin = plugin;
            this.sender = sender;
            this.player = sender instanceof Player onlinePlayer ? onlinePlayer : null;
            if (player != null) {
                bossBar = BossBar.bossBar(
                    plugin.bossBarComponent("准备重载", "正在刷新 MUZ 状态"),
                    0.05f,
                    BossBar.Color.BLUE,
                    BossBar.Overlay.PROGRESS
                );
                player.showBossBar(bossBar);
            } else {
                bossBar = null;
            }
        }

        @Override
        public void update(double progress, String title, String detail) {
            if (bossBar == null) {
                return;
            }
            bossBar.progress((float) Math.clamp(progress, 0.0, 1.0));
            bossBar.name(plugin.bossBarComponent(title, detail));
        }

        @Override
        public void complete(ReloadSummary summary) {
            if (bossBar != null) {
                bossBar.color(BossBar.Color.GREEN);
                bossBar.progress(1.0f);
                bossBar.name(plugin.bossBarComponent("重载完成", plugin.bundleSummaryPlain(summary.bundleExport())));
                if (player.isOnline()) {
                    plugin.scheduler().runLater(40L, () -> {
                        if (player.isOnline()) {
                            player.hideBossBar(bossBar);
                        }
                    });
                }
                player.sendMessage(plugin.reloadSummaryComponent(summary));
                player.sendMessage(plugin.hookSummaryComponent(summary.hooks()));
                return;
            }
            sender.sendMessage(plugin.reloadSummaryPlain(summary) + " | hooks=" + plugin.formatHookSummaryPlain(summary.hooks()));
        }

        @Override
        public void recoveryFailed(String detail) {
            Runnable notify = () -> {
                if (bossBar != null) {
                    bossBar.color(BossBar.Color.RED);
                    bossBar.progress(1.0f);
                    bossBar.name(plugin.bossBarComponent("重载部分失败", detail));
                }
                sender.sendMessage(plugin.plain(MuzTheme.named(detail, NamedTextColor.RED)));
            };
            if (Bukkit.isPrimaryThread()) {
                notify.run();
            } else {
                plugin.scheduler().runSync(notify);
            }
        }
    }

    /**
     * 绑定 /muz 命令的执行器与补全器
     */
    /**
     * 注册 /muz。
     *
     * paper-plugin.yml 不支持 commands 段，getCommand("muz") 只会返回 null，
     * 所以命令改在这里通过 CommandMap 手动注册。命令的元信息（别名、描述、用法、权限）
     * 原先写在 plugin.yml 里，现在跟着注册代码一起放在这。
     *
     * 只包一层转发，不改 DoudizhuCommand：它那 15 个子命令和状态感知的 tab 补全
     * 是已经跑通的逻辑，重写成 Brigadier 树只会凭空多出一批出错的机会。
     *
     * <p>注册成功后把命令实例存进 {@link #muzCommand}：CommandMap 不会随插件 disable
     * 自动摘除命令，关闭阶段必须靠这个引用手动注销，否则插件 JAR 关闭后残留的 /muz
     * 派发会抛 {@code ZipException: zip file closed}。本方法幂等——重复注册时先摘掉旧实例，
     * 不会把上一份命令引用泄漏在 CommandMap 里。
     */
    private void registerMuzCommand() {
        CommandMap commandMap = getServer().getCommandMap();
        // 重复注册（重复 enable / 外部 reload）会留下指向上一个插件实例的命令引用：先摘掉旧的再注册，
        // 避免旧实例被 CommandMap 长期持有（那正是泄漏，也是「注销时摘错对象」的来源）。
        Command previous = muzCommand;
        if (previous != null) {
            detachMuzCommand(commandMap, previous, "重复注册");
        }
        DoudizhuCommand executor = new DoudizhuCommand(this);
        Command command = new Command("muz", "管理 MUZ 牌桌与对局。", "/muz help", List.of("MUZ")) {
            @Override
            public boolean execute(CommandSender sender, String label, String[] args) {
                if (!testPermission(sender)) {
                    // testPermission 自己会把无权限提示发给玩家
                    return true;
                }
                return executor.onCommand(sender, this, label, args);
            }

            @Override
            public List<String> tabComplete(CommandSender sender, String alias, String[] args) {
                if (!testPermissionSilent(sender)) {
                    // 没权限的人不该从补全里看出有哪些子命令
                    return List.of();
                }
                List<String> completions = executor.onTabComplete(sender, this, alias, args);
                return completions == null ? List.of() : completions;
            }
        };
        command.setPermission("muz.command");
        // 前缀用插件名小写：万一别的插件也占了 muz，玩家还能用 /muz:muz 兜底
        boolean registered = commandMap.register("muz", "muz", command);
        if (registered) {
            muzCommand = command;
        } else {
            // 没注册上就不能记成自己的命令：否则关闭时会去摘一个不属于本插件的命令。
            getLogger().warning("/muz 命令注册失败：CommandMap 已存在同名命令，可能被其它插件占用。");
        }
    }

    /**
     * 关闭阶段注销 /muz。
     *
     * <p>必须在 onDisable（插件类加载器被 Paper 关闭之前）调用。命令摘除失败只记日志、
     * 不抛异常：关闭流程不能因为一条命令注销不掉而中断，其余实体的清理优先级更高。
     */
    private void unregisterMuzCommand() {
        Command command = muzCommand;
        // 先清引用再摘除：即使摘除抛异常，也不会留下指向已失效命令的字段（幂等，重复调用安全）。
        muzCommand = null;
        if (command == null) {
            return;
        }
        detachMuzCommand(getServer().getCommandMap(), command, "关闭");
    }

    /**
     * 把命令从 CommandMap 真正摘掉，返回是否确认已不再被派发。
     *
     * <p>【为什么要两步】{@code Command.unregister(commandMap)} 只清掉命令自身的注册引用，
     * 不会把条目从 CommandMap 的 knownCommands 里删掉；真正决定「还能不能被派发」的是 knownCommands。
     * Paper 的 {@code CraftCommandMap} 用的是一张转发到 Brigadier dispatcher 的 map
     * （{@code BukkitBrigForwardingMap}），按 key remove 会同步删掉 Brigadier 节点，所以第二步必须做。
     * 两个调用都可能抛异常，各自兜住并留痕，绝不外抛打断关闭。
     */
    private boolean detachMuzCommand(CommandMap commandMap, Command command, String reason) {
        if (commandMap == null) {
            getLogger().warning("注销 /muz 命令（" + reason + "）失败：CommandMap 不可用，命令可能残留为未注销状态。");
            return false;
        }
        boolean detached = false;
        try {
            detached = command.unregister(commandMap);
        } catch (RuntimeException | Error failure) {
            getLogger().log(java.util.logging.Level.WARNING, "注销 /muz 命令（" + reason + "）时 Command.unregister 抛出异常。", failure);
        }
        int removed = 0;
        try {
            Map<String, Command> known = commandMap.getKnownCommands();
            if (known != null) {
                // 先快照 key 再删：knownCommands 在 Paper 上转发自 Brigadier，边遍历边删会踩并发修改。
                List<String> stale = new ArrayList<>();
                for (Map.Entry<String, Command> entry : known.entrySet()) {
                    if (entry.getValue() == command) {
                        stale.add(entry.getKey());
                    }
                }
                for (String key : stale) {
                    if (known.remove(key) != null) {
                        removed++;
                    }
                }
            }
        } catch (RuntimeException | Error failure) {
            getLogger().log(java.util.logging.Level.WARNING, "注销 /muz 命令（" + reason + "）时从 CommandMap 摘除登记抛出异常。", failure);
        }
        if (removed > 0 || detached) {
            getLogger().info("已注销 /muz 命令（" + reason + "）：摘除 CommandMap 登记 " + removed + " 条。");
            return true;
        }
        getLogger().warning("注销 /muz 命令（" + reason + "）未生效：CommandMap 中没有本插件的 /muz 登记。");
        return false;
    }

    private void logShutdownDiagnostics() {
        int ddzTables = tableManager == null ? 0 : tableManager.getTables().size();
        getLogger().info("[MUZ/shutdown] version=" + getDescription().getVersion() + " doudizhuTables=" + ddzTables + " shuttingDown=true");
    }
}

