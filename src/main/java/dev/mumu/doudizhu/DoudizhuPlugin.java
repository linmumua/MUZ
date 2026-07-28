package dev.mumu.doudizhu;

import dev.mumu.doudizhu.ai.AiChatGateway;
import dev.mumu.doudizhu.ai.OpenAiCompatibleAiChatGateway;

import dev.mumu.doudizhu.compat.CraftEngineBundleExporter;
import dev.mumu.doudizhu.compat.CraftEngineFurnitureService;
import dev.mumu.doudizhu.compat.VaultEconomyBridge;
import dev.mumu.doudizhu.config.MuzYamlConfig;
import dev.mumu.doudizhu.game.GameTable;
import dev.mumu.doudizhu.game.TableManager;
import dev.mumu.doudizhu.listener.CraftEngineLifecycleListener;
import dev.mumu.doudizhu.listener.HandGuiListener;
import dev.mumu.doudizhu.listener.PlayerConnectionListener;
import dev.mumu.doudizhu.listener.WorldTableInteractionListener;
import dev.mumu.doudizhu.placeholder.MuzPlaceholderExpansion;
import dev.mumu.doudizhu.room.TableLevel;
import dev.mumu.doudizhu.scheduler.MuzScheduler;
import dev.mumu.doudizhu.tabooruntime.MuzTabooRuntime;
import dev.mumu.doudizhu.storage.DatabaseManager;
import dev.mumu.doudizhu.storage.MatchParticipantRecord;
import dev.mumu.doudizhu.storage.MatchRecord;
import dev.mumu.doudizhu.storage.PersistedTableRecord;
import dev.mumu.doudizhu.storage.PlayerHistoryEntry;
import dev.mumu.doudizhu.ui.HandGuiService;
import dev.mumu.doudizhu.ui.MuzTheme;
import dev.mumu.doudizhu.world.PhysicalTableManager;
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
import java.time.format.DateTimeFormatter;
import net.kyori.adventure.bossbar.BossBar;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextDecoration;
import net.kyori.adventure.text.minimessage.MiniMessage;
import dev.mumu.doudizhu.compat.VersionCompat;
import net.milkbowl.vault.economy.EconomyResponse;
import org.bukkit.Bukkit;
import org.bukkit.OfflinePlayer;
import org.bukkit.Color;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.block.BlockFace;
import org.bukkit.command.CommandSender;
import org.bukkit.command.PluginCommand;
import org.bukkit.entity.Player;
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
    private static final String DEFAULT_TABLE_ITEM_MODEL = "muz:furniture/table_visual";
    private static final String DEFAULT_CHAIR_ITEM_MODEL = "muz:furniture/seat_chair";
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
    private static final double OLDER_BUTTON_DISTANCE = 1.10;
    private static final double LEGACY_BUTTON_DISTANCE = 1.45;
    private static final double DEFAULT_BUTTON_DISTANCE = 2.10;
    private static final double LEGACY_BUTTON_ROLL_DEGREES = 90.0;
    private static final double DEFAULT_BUTTON_ROLL_DEGREES = 0.0;
    private static final double LEGACY_CARD_HITBOX_VERTICAL_OFFSET = 0.05;
    private static final double DEFAULT_CARD_HITBOX_VERTICAL_OFFSET = -0.45;
    private static final boolean DEFAULT_SELECTION_SOUND_ENABLED = true;
    private static final boolean DEFAULT_OPPONENT_PREVIEW_ENABLED = true;
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
    private static final int PLAYER_OPTION_PROFILE_COUNT = 4;
    private static final float DEFAULT_PRIVATE_CARD_SCALE = 0.50f;
    private static final float DEFAULT_PUBLIC_CARD_SCALE = 0.58f;
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
    private CraftEngineBundleExporter craftEngineBundleExporter;
    private CraftEngineFurnitureService craftEngineFurnitureService;
    private VaultEconomyBridge vaultEconomyBridge;
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
    private float publicCardScale;
    private float privateCardWidthScale;
    private float privateCardHeightScale;
    private float privateCardDepthScale;
    private float publicCardWidthScale;
    private float publicCardHeightScale;
    private float publicCardDepthScale;
    private float hoverCardScale;
    private double hoverCardLift;
    private int cardHoverInterpolationTicks;
    private int cardHoverAnimationTypeIndex;
    private float hoverButtonScale;
    private double hoverButtonLift;
    private int buttonHoverInterpolationTicks;
    private int buttonHoverAnimationTypeIndex;
    private float buttonScale;
    private float tableScale;
    private float chairScale;
    private float smallTextScale;
    private float statusTextScale;
    private float labelTextScale;
    private float playerHeadScale;
    private PlayerHeadDisplayMode playerHeadDisplayMode = PlayerHeadDisplayMode.BOTH;
    private float statusAvatarScale;
    private double statusAvatarLateralOffset;
    private double statusAvatarVerticalOffset;
    private double statusAvatarDepthOffset;
    private float statusNameScale;
    private double statusNameLateralOffset;
    private double statusNameVerticalOffset;
    private double statusNameDepthOffset;
    private float seatAvatarScale;
    private double seatAvatarLateralOffset;
    private double seatAvatarVerticalOffset;
    private double seatAvatarDepthOffset;
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
    private float publicTrickSpacing;
    private float buttonRollDegrees;
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
    private double chairHitboxWidth;
    private double chairHitboxHeight;
    private double buttonHitboxLateralOffset;
    private double buttonHitboxDepthOffset;
    private double buttonHitboxVerticalOffset;
    private double buttonHitboxWidth;
    private double buttonHitboxHeight;
    private double cardHitboxLateralOffset;
    private double cardHitboxDepthOffset;
    private double cardHitboxVerticalOffset;
    private double cardHitboxLength;
    private double cardHitboxWidth;
    private double cardHitboxHeight;
    private double statusHeight;
    private double playDetailHeight;
    private double publicTrickHeight;
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
    private int previewCardsPerRow;
    private double publicPreviewCompareRowOffset;
    private double publicPreviewSelectedRowOffset;
    private double publicPreviewRowDepthSpacing;
    private double publicPreviewLabelHeight;
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
    private final Map<UUID, Boolean> playerOpponentPreviewSettings = new ConcurrentHashMap<>();
    private final Map<UUID, Integer> playerSelectionSoundProfileSettings = new ConcurrentHashMap<>();
    private final Map<UUID, Integer> playerPlayActionProfileSettings = new ConcurrentHashMap<>();
    private final Map<UUID, EnumMap<PlayActionKind, Integer>> playerPlayActionKindProfileSettings = new ConcurrentHashMap<>();
    private final Map<UUID, Integer> playerHoverGlowColorSettings = new ConcurrentHashMap<>();
    private final Map<UUID, Integer> playerSelectedGlowColorSettings = new ConcurrentHashMap<>();
    private final Map<UUID, Integer> playerChipBalances = new ConcurrentHashMap<>();
    private final Map<UUID, PlayerHandOffsets> playerHandOffsets = new ConcurrentHashMap<>();
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
    private boolean craftEngineProtectionListenerRegistered;
    private static final DateTimeFormatter HISTORY_TIME_FORMAT = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm").withZone(ZoneId.systemDefault());

    public MuzScheduler scheduler() {
        if (scheduler == null) {
            scheduler = new MuzScheduler(this);
        }
        return scheduler;
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
            yamlConfig().save();
        } catch (IOException exception) {
            getLogger().warning("保存 config.yml 失败: " + exception.getMessage());
        }
    }

    private void mergeDefaultYamlConfig() {
        try (java.io.InputStream stream = getResource("config.yml")) {
            yamlConfig().mergeMissingFrom(stream);
        } catch (IOException exception) {
            getLogger().warning("合并默认 config.yml 失败: " + exception.getMessage());
        }
    }

    @Override
    public void onEnable() {
        // 引导放在 onEnable：Paper 在 onLoad 阶段不允许向插件 ClassLoader 追加类，
        // 会导致 TabooLib 注入 Kotlin 后仍找不到 kotlin.Lazy。
        MuzTabooRuntime.bootstrap(getLogger());
        MuzTabooRuntime.enable(getLogger());
        scheduler = new MuzScheduler(this);
        saveDefaultYamlConfig();
        ensureConfigIntegrity();
        optionProfilesFile = new File(getDataFolder(), "option-profiles.yml");
        loadRenderSettings();
        loadAiSettings();
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
        handGuiService = new HandGuiService(this);
        tableManager = new TableManager(this);
        databaseManager = new DatabaseManager(this);
        craftEngineBundleExporter = new CraftEngineBundleExporter(this);
        craftEngineFurnitureService = new CraftEngineFurnitureService(this);
        vaultEconomyBridge = new VaultEconomyBridge(this);
        physicalTableManager = new PhysicalTableManager(this);
        initializePersistence();

        getServer().getPluginManager().registerEvents(new PlayerConnectionListener(this), this);
        getServer().getPluginManager().registerEvents(new WorldTableInteractionListener(this), this);
        getServer().getPluginManager().registerEvents(new CraftEngineLifecycleListener(this), this);
        getServer().getPluginManager().registerEvents(new HandGuiListener(this), this);
        ensureCraftEngineProtectionListenerRegistered();
        scheduler().runTimer(1L, 1L, () -> physicalTableManager.tick());
        scheduler().runTimer(1L, 10L, () -> tableManager.tick());
        HookSnapshot placeholderHook = ensurePlaceholderHookReadyInternal();
        HookSnapshot vaultHook = ensureVaultEconomyHookReadyInternal();
        CraftEngineBundleExporter.BundleExportResult exportResult = craftEngineBundleExporter.exportIfAvailable();
        attemptPersistedTableRestore();
        logStartupSummary(exportResult, detectSupportedHooks(placeholderHook, vaultHook));
        logVaultHookDiagnosis(vaultHook);
        scheduleVaultHookRetries();
        schedulePersistedTableRestore();
    }

    @Override
    public void onDisable() {
        shuttingDown = true;
        savePlayerSettings();
        logShutdownDiagnostics();
        if (placeholderExpansion != null && placeholderExpansion.isRegistered()) {
            placeholderExpansion.unregister();
        }
        if (tableManager != null) {
            tableManager.shutdown();
        }
        if (physicalTableManager != null) {
            physicalTableManager.shutdown();
        }
        if (databaseManager != null) {
            databaseManager.close();
        }
        MuzTabooRuntime.disable(getLogger());
    }

    public TableManager getTableManager() {
        return tableManager;
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
        getServer().getPluginManager().registerEvents(new dev.mumu.doudizhu.listener.CraftEngineProtectionListener(this), this);
        craftEngineProtectionListenerRegistered = true;
    }

    private HookSnapshot ensurePlaceholderHookReadyInternal() {
        org.bukkit.plugin.Plugin placeholderApi = getServer().getPluginManager().getPlugin("PlaceholderAPI");
        if (placeholderApi == null) {
            return new HookSnapshot("papi", "PlaceholderAPI", HookState.MISSING, "未挂钩，不启用 %muz_*% 占位符");
        }
        if (!placeholderApi.isEnabled()) {
            return new HookSnapshot("papi", "PlaceholderAPI", HookState.DISABLED, "未挂钩，不启用 %muz_*% 占位符");
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
            return new HookSnapshot("papi", "PlaceholderAPI", HookState.ERROR, "未挂钩，不启用 %muz_*% 占位符");
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
            return "DeepSeek 未初始化";
        }
        if (!aiProviderConfig.hasApiKey()) {
            return aiProviderConfig.providerName()
                + " | " + aiProviderConfig.model()
                + " @ " + aiProviderConfig.baseUrl()
                + aiProviderConfig.chatCompletionsPath()
                + " | API Key 未配置";
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
            HookSnapshot snapshot = new HookSnapshot("vault", "Vault", HookState.DISABLED, "未挂钩，不启用 Vault 经济同步");
            lastVaultHookSnapshot = snapshot;
            return snapshot;
        }
        org.bukkit.plugin.Plugin vault = getServer().getPluginManager().getPlugin("Vault");
        if (vault == null) {
            HookSnapshot snapshot = new HookSnapshot("vault", "Vault", HookState.MISSING, "未挂钩，不启用 Vault 经济同步");
            lastVaultHookSnapshot = snapshot;
            return snapshot;
        }
        if (!vault.isEnabled()) {
            HookSnapshot snapshot = new HookSnapshot("vault", "Vault", HookState.DISABLED, "未挂钩，不启用 Vault 经济同步");
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
            "未挂钩，不启用 Vault 经济同步 | 原因: " + safeEconomyError(vaultEconomyBridge.statusDetail())
        );
        lastVaultHookSnapshot = snapshot;
        return snapshot;
    }

    public String placeholderPointValue(String rawTarget, @org.jetbrains.annotations.Nullable OfflinePlayer viewer) {
        String target = dev.mumu.doudizhu.placeholder.MuzHeadPlaceholderFormat.normalizeTargetValue(rawTarget, viewer);
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
        String target = dev.mumu.doudizhu.placeholder.MuzHeadPlaceholderFormat.normalizeTargetValue(rawTarget, viewer);
        if (isBlank(target)) {
            return "无";
        }
        PlaceholderTarget resolved = resolvePlaceholderTarget(target);
        if (resolved == null) {
            return "无";
        }
        return switch (resolved.kind()) {
            case DOUDIZHU -> {
                dev.mumu.doudizhu.game.PlayerRole role = resolved.gameTable().getRole(resolved.playerId());
                yield role == null ? "无" : role.displayName();
            }
        };
    }

    public String placeholderHandValue(String rawTarget, @org.jetbrains.annotations.Nullable OfflinePlayer viewer) {
        String target = dev.mumu.doudizhu.placeholder.MuzHeadPlaceholderFormat.normalizeTargetValue(rawTarget, viewer);
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
        String target = dev.mumu.doudizhu.placeholder.MuzHeadPlaceholderFormat.normalizeTargetValue(rawTarget, viewer);
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
        String target = dev.mumu.doudizhu.placeholder.MuzHeadPlaceholderFormat.normalizeTargetValue(rawTarget, viewer);
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
        String target = dev.mumu.doudizhu.placeholder.MuzHeadPlaceholderFormat.normalizeTargetValue(rawTarget, viewer);
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
        String target = dev.mumu.doudizhu.placeholder.MuzHeadPlaceholderFormat.normalizeTargetValue(rawTarget, viewer);
        if (isBlank(target)) {
            return viewer == null ? "0" : String.valueOf(getChipBalance(viewer.getUniqueId()));
        }
        Player online = Bukkit.getPlayerExact(target);
        if (online != null) {
            return String.valueOf(getChipBalance(online.getUniqueId()));
        }
        OfflinePlayer offline = Bukkit.getOfflinePlayer(target);
        if (offline != null && offline.getName() != null && offline.getUniqueId() != null) {
            return String.valueOf(getChipBalance(offline.getUniqueId()));
        }
        return "0";
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
            SettlementResult settlement = settlements.getOrDefault(seat, currentRoomStatus(table.getRoomLevel(), seat));
            participants.add(new MatchParticipantRecord(
                seat,
                resolvePlayerName(seat) == null ? table.displayName(seat) : resolvePlayerName(seat),
                table.getRole(seat) == null ? "无" : table.getRole(seat).displayName(),
                winners.contains(seat) ? "WIN" : "LOSE",
                scoreDeltas.getOrDefault(seat, 0),
                settlement.delta(),
                settlement.unitLabel(),
                settlement.debt(),
                settlement.postBalance(),
                settlement.bankrupt()
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

    public CraftEngineBundleExporter getCraftEngineBundleExporter() {
        return craftEngineBundleExporter;
    }

    public CraftEngineFurnitureService getCraftEngineFurnitureService() {
        return craftEngineFurnitureService;
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
        chipPaymentEnabled = enabled;
        yamlConfig().set("economy.payment.use-chip", enabled);
        saveYamlConfig();
    }

    public ItemStack chipPaymentItem() {
        ItemStack stored = yamlConfig().getItemStack("economy.payment.chip-item-stack");
        return stored == null ? defaultChipItem() : stored.clone();
    }

    public void setChipPaymentItem(ItemStack itemStack) {
        ItemStack copy = itemStack == null ? defaultChipItem() : itemStack.clone();
        copy.setAmount(1);
        yamlConfig().set("economy.payment.chip-item-stack", copy);
        saveYamlConfig();
    }

    public int getChipBalance(UUID playerId) {
        return playerChipBalances.getOrDefault(playerId, 0);
    }

    public int setChipBalance(UUID playerId, int amount) {
        if (playerId == null) {
            return 0;
        }
        playerChipBalances.put(playerId, amount);
        savePlayerSettings();
        return amount;
    }

    public int adjustChipBalance(UUID playerId, int delta) {
        if (playerId == null || delta == 0) {
            return getChipBalance(playerId);
        }
        int next = playerChipBalances.getOrDefault(playerId, 0) + delta;
        playerChipBalances.put(playerId, next);
        savePlayerSettings();
        return next;
    }

    public int roomEntryRequirement(TableLevel level) {
        return Math.max(0, (int) Math.round(roomMultiplier(level)));
    }

    public boolean canAffordEntry(UUID playerId, TableLevel level) {
        if (playerId == null || level == null || level == TableLevel.FUN || !isRoomEconomyEnabled(level)) {
            return true;
        }
        if (isChipPaymentEnabled()) {
            return getChipBalance(playerId) >= roomEntryRequirement(level);
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
            return "未初始化";
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
        if (playerId == null || !isRoomEconomyEnabled(level)) {
            return currentRoomStatus(level, playerId);
        }
        if (scoreDelta == 0) {
            return currentRoomStatus(level, playerId);
        }
        if (isChipPaymentEnabled()) {
            int chipDelta = (int) Math.round(scoreDelta * roomMultiplier(level));
            int postBalance = adjustChipBalance(playerId, chipDelta);
            double debt = postBalance < 0 ? -postBalance : 0.0;
            return settlementResult(level, chipDelta, debt, postBalance, true);
        }
        if (!isDoudizhuRoomEconomyEnabled(level)) {
            return currentRoomStatus(level, playerId);
        }
        OfflinePlayer player = Bukkit.getOfflinePlayer(playerId);
        if (player == null) {
            return currentRoomStatus(level, playerId);
        }
        vaultEconomyBridge.ensureAccount(player);
        double amount = Math.abs(scoreDelta) * doudizhuCurrencyPerPoint(level);
        if (amount <= 0.0) {
            return currentRoomStatus(level, playerId);
        }
        if (scoreDelta > 0) {
            EconomyResponse response = vaultEconomyBridge.deposit(player, amount);
            if (!response.transactionSuccess()) {
                throw new IllegalStateException("Vault 入账失败: " + safeEconomyError(response.errorMessage));
            }
            return settlementResult(level, amount, 0.0, response.balance, false);
        }
        double balance = Math.max(0.0, vaultEconomyBridge.balance(player));
        double actual = Math.min(balance, amount);
        double postBalance = balance;
        if (actual > 0.0) {
            EconomyResponse response = vaultEconomyBridge.withdraw(player, actual);
            if (!response.transactionSuccess()) {
                throw new IllegalStateException("Vault 扣款失败: " + safeEconomyError(response.errorMessage));
            }
            postBalance = response.balance;
        }
        double debt = Math.max(0.0, amount - actual);
        return settlementResult(level, -actual, debt, postBalance, false);
    }

    public SettlementResult currentRoomStatus(TableLevel level, UUID playerId) {
        if (playerId == null) {
            return settlementResult(level, 0.0, 0.0, 0.0, isChipPaymentEnabled());
        }
        if (isChipPaymentEnabled()) {
            int postBalance = getChipBalance(playerId);
            double debt = postBalance < 0 ? -postBalance : 0.0;
            return settlementResult(level, 0.0, debt, postBalance, true);
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
        return vaultDoudizhuCurrencyPerPoint * roomMultiplier(level);
    }

    private SettlementResult settlementResult(TableLevel level, double delta, double debt, double postBalance, boolean chipMode) {
        int requirement = roomEntryRequirement(level);
        boolean bankrupt = postBalance <= 0.0 || debt > 0.0;
        boolean insufficient = level != null && level != TableLevel.FUN && isRoomEconomyEnabled(level) && postBalance < requirement;
        return new SettlementResult(delta, debt, postBalance, bankrupt, insufficient, chipMode ? "筹码" : "金币");
    }

    private String safeEconomyError(String raw) {
        return isBlank(raw) ? "经济插件未返回详细错误" : raw;
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

    public boolean isOpponentPreviewEnabledFor(UUID playerId) {
        return playerOpponentPreviewSettings.getOrDefault(playerId, DEFAULT_OPPONENT_PREVIEW_ENABLED);
    }

    public boolean toggleOpponentPreviewFor(UUID playerId) {
        return togglePlayerPreference(playerId, playerOpponentPreviewSettings, DEFAULT_OPPONENT_PREVIEW_ENABLED);
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

    public OptionProfile resolvePlayActionProfile(UUID playerId, dev.mumu.doudizhu.model.CardPattern pattern) {
        PlayActionKind kind = PlayActionKind.fromPattern(pattern);
        if (kind == null) {
            return getPlayActionProfile(getPlayerPlayActionProfileIndex(playerId));
        }
        return getPlayActionProfile(kind, getPlayerPlayActionProfileIndex(playerId, kind));
    }

    public void resetPlayerVisualSettings(UUID playerId) {
        playerCardLabelSettings.remove(playerId);
        playerSelectionSoundSettings.remove(playerId);
        playerOpponentPreviewSettings.remove(playerId);
        playerSelectionSoundProfileSettings.remove(playerId);
        playerPlayActionProfileSettings.remove(playerId);
        playerPlayActionKindProfileSettings.remove(playerId);
        playerHoverGlowColorSettings.remove(playerId);
        playerSelectedGlowColorSettings.remove(playerId);
        playerHandOffsets.remove(playerId);
        savePlayerSettings();
    }

    private void cyclePlayerGlowColor(UUID playerId, Map<UUID, Integer> settings, boolean previewColor) {
        int current = clampGlowColorIndex(settings.getOrDefault(playerId, 0));
        int other = previewColor
            ? clampGlowColorIndex(playerSelectedGlowColorSettings.getOrDefault(playerId, 0))
            : clampGlowColorIndex(playerHoverGlowColorSettings.getOrDefault(playerId, 0));
        Color otherColor = resolveGlowColor(other, !previewColor);
        for (int attempt = 1; attempt <= GLOW_COLOR_OPTIONS.size(); attempt++) {
            int next = clampGlowColorIndex((current + attempt) % GLOW_COLOR_OPTIONS.size());
            Color candidateColor = resolveGlowColor(next, previewColor);
            if (!sameColor(candidateColor, otherColor)) {
                if (next == 0) {
                    settings.remove(playerId);
                } else {
                    settings.put(playerId, next);
                }
                savePlayerSettings();
                return;
            }
        }
    }

    private GlowColorOption glowColorOption(int index) {
        return GLOW_COLOR_OPTIONS.get(clampGlowColorIndex(index));
    }

    private int clampGlowColorIndex(int index) {
        return Math.max(0, Math.min(GLOW_COLOR_OPTIONS.size() - 1, index));
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

    public float getPublicCardScale() {
        return publicCardScale;
    }

    public float getButtonScale() {
        return buttonScale;
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

    public float getPlayerHeadScale() {
        return playerHeadScale;
    }

    public PlayerHeadDisplayMode playerHeadDisplayMode() {
        return playerHeadDisplayMode == null ? PlayerHeadDisplayMode.BOTH : playerHeadDisplayMode;
    }

    public boolean shouldShowPlayerHeadAvatar() {
        return playerHeadDisplayMode().showAvatar();
    }

    public boolean shouldShowPlayerHeadName() {
        return playerHeadDisplayMode().showName();
    }

    public String playerHeadDisplayModeLabel() {
        return playerHeadDisplayMode().label();
    }

    public void cyclePlayerHeadDisplayMode() {
        playerHeadDisplayMode = playerHeadDisplayMode().next();
        yamlConfig().set("render.player-head-show-id", playerHeadDisplayMode.configValue());
        saveYamlConfig();
        reloadVisualState(false, ReloadFeedback.silent());
    }

    public float getStatusAvatarScale() {
        return statusAvatarScale;
    }

    public double getStatusAvatarLateralOffset() {
        return statusAvatarLateralOffset;
    }

    public double getStatusAvatarVerticalOffset() {
        return statusAvatarVerticalOffset;
    }

    public double getStatusAvatarDepthOffset() {
        return statusAvatarDepthOffset;
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

    public float getSeatAvatarScale() {
        return seatAvatarScale;
    }

    public double getSeatAvatarLateralOffset() {
        return seatAvatarLateralOffset;
    }

    public double getSeatAvatarVerticalOffset() {
        return seatAvatarVerticalOffset;
    }

    public double getSeatAvatarDepthOffset() {
        return seatAvatarDepthOffset;
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

    public Component phaseComponent(dev.mumu.doudizhu.game.GamePhase phase, NamedTextColor color) {
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

    public float getPublicCardWidthScale() {
        return publicCardWidthScale;
    }

    public float getPublicCardHeightScale() {
        return publicCardHeightScale;
    }

    public float getPublicCardDepthScale() {
        return publicCardDepthScale;
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

    public float getHoverButtonScale() {
        return hoverButtonScale;
    }

    public double getHoverButtonLift() {
        return hoverButtonLift;
    }

    public int getButtonHoverInterpolationTicks() {
        return buttonHoverInterpolationTicks;
    }

    public AnimationCurve buttonHoverAnimationCurve() {
        return AnimationCurve.fromIndex(buttonHoverAnimationTypeIndex);
    }

    public float getCardDepthOffset() {
        return cardDepthOffset;
    }

    public float getHandSpacing() {
        return handSpacing;
    }

    public float getPublicTrickSpacing() {
        return publicTrickSpacing;
    }

    public float getButtonRollDegrees() {
        return buttonRollDegrees;
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
            Math.max(0, Math.min(255, hoverGlowRed)),
            Math.max(0, Math.min(255, hoverGlowGreen)),
            Math.max(0, Math.min(255, hoverGlowBlue))
        );
    }

    public boolean isSelectedGlowEnabled() {
        return selectedGlowEnabled;
    }

    public Color selectedGlowColor() {
        return Color.fromRGB(
            Math.max(0, Math.min(255, selectedGlowRed)),
            Math.max(0, Math.min(255, selectedGlowGreen)),
            Math.max(0, Math.min(255, selectedGlowBlue))
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

    public double getChairHitboxWidth() {
        return chairHitboxWidth;
    }

    public double getChairHitboxHeight() {
        return chairHitboxHeight;
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

    public double getButtonHitboxWidth() {
        return buttonHitboxWidth;
    }

    public double getButtonHitboxHeight() {
        return buttonHitboxHeight;
    }

    public double getCardHitboxLateralOffset() {
        return cardHitboxLateralOffset;
    }

    public double getCardHitboxDepthOffset() {
        return cardHitboxDepthOffset;
    }

    public double getCardHitboxVerticalOffset() {
        return cardHitboxVerticalOffset;
    }

    public double getCardHitboxWidth() {
        return cardHitboxWidth;
    }

    public double getCardHitboxLength() {
        return cardHitboxLength;
    }

    public double getCardHitboxHeight() {
        return cardHitboxHeight;
    }

    public double getPublicTrickHeight() {
        return publicTrickHeight;
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

    public int getPreviewCardsPerRow() {
        return previewCardsPerRow;
    }

    public double getPublicPreviewCompareRowOffset() {
        return publicPreviewCompareRowOffset;
    }

    public double getPublicPreviewSelectedRowOffset() {
        return publicPreviewSelectedRowOffset;
    }

    public double getPublicPreviewRowDepthSpacing() {
        return publicPreviewRowDepthSpacing;
    }

    public double getPublicPreviewLabelHeight() {
        return publicPreviewLabelHeight;
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
            dev.mumu.doudizhu.compat.CraftEngineFurnitureService.ResolvedItem resolved =
                craftEngineFurnitureService == null ? null : craftEngineFurnitureService.resolveCustomItem(configured);
            if (resolved != null) {
                return List.of(resolved.itemId());
            }
            List<String> configuredCandidates = furnitureItemIdCandidates(configured, null, "table_visual");
            if (!configuredCandidates.isEmpty()) {
                return configuredCandidates;
            }
        }
        return furnitureItemIdCandidates(tableItemModelId, "table_visual");
    }

    public String getTableDisplayName() {
        ItemStack configured = getConfiguredFurnitureItem(FurnitureType.TABLE);
        return configured == null ? tableDisplayName : configured.getType().name();
    }

    public String getChairItemModelId() {
        return chairItemModelId;
    }

    public List<String> getChairFurnitureItemIdCandidates() {
        ItemStack configured = getConfiguredFurnitureItem(FurnitureType.CHAIR);
        if (configured != null) {
            dev.mumu.doudizhu.compat.CraftEngineFurnitureService.ResolvedItem resolved =
                craftEngineFurnitureService == null ? null : craftEngineFurnitureService.resolveCustomItem(configured);
            if (resolved != null) {
                return List.of(resolved.itemId());
            }
            List<String> configuredCandidates = furnitureItemIdCandidates(configured, null, "seat_chair");
            if (!configuredCandidates.isEmpty()) {
                return configuredCandidates;
            }
        }
        return furnitureItemIdCandidates(chairItemModelId, "seat_chair");
    }

    public boolean canUseHeldItemAsChairFurniture(ItemStack itemStack) {
        if (itemStack == null || itemStack.getType().isAir()) {
            return false;
        }
        if (craftEngineFurnitureService != null && craftEngineFurnitureService.resolveCustomItem(itemStack) != null) {
            return true;
        }
        return !furnitureItemIdCandidates(itemStack, null, "seat_chair").isEmpty() || itemStack.getType().isBlock();
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
                if (physicalTableManager != null && physicalTableManager.placedTableCount() > 0) {
                    physicalTableManager.rebuildAllTables();
                    physicalTableManager.repairIncompleteTables(reason + "-ddz-pass-" + pass);
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

    public void adjustAdminSetting(AdminSetting setting, boolean increase, int multiplier) {
        if (setting.booleanSetting()) {
            yamlConfig().set(setting.path(), !yamlConfig().getBoolean(setting.path(), setting.defaultBoolean()));
        } else if (setting.integerSetting()) {
            int current = yamlConfig().getInt(setting.path(), (int) setting.defaultValue());
            int delta = (int) setting.step() * Math.max(1, multiplier);
            int next = current + (increase ? delta : -delta);
            next = Math.max((int) setting.minValue(), Math.min((int) setting.maxValue(), next));
            yamlConfig().set(setting.path(), next);
        } else {
            double current = yamlConfig().getDouble(setting.path(), setting.defaultValue());
            double delta = adminSettingStep(setting) * Math.max(1, multiplier);
            current = normalizeAdminCurrentValue(setting, current);
            double next = current + (increase ? delta : -delta);
            next = Math.max(setting.minValue(), Math.min(setting.maxValue(), next));
            next = normalizeAdminStoredValue(setting, next);
            yamlConfig().set(setting.path(), next);
            if (setting == AdminSetting.TABLE_SPAWN_OFFSET_Y) {
                saveYamlConfig();
                loadRenderSettings();
                double shift = next - current;
                if (physicalTableManager != null) {
                    physicalTableManager.shiftAllAnchors(shift);
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
        if (setting == AdminSetting.HOVER_BUTTON_ANIMATION_TYPE) {
            return buttonHoverAnimationCurve().label();
        }
        if (setting == AdminSetting.PLAYER_HEAD_SHOW_ID) {
            return playerHeadDisplayModeLabel();
        }
        if (setting.booleanSetting()) {
            return yamlConfig().getBoolean(setting.path(), setting.defaultBoolean()) ? "开启" : "关闭";
        }
        if (setting.integerSetting()) {
            return String.valueOf(yamlConfig().getInt(setting.path(), (int) setting.defaultValue()));
        }
        if (setting == AdminSetting.TABLE_SPAWN_OFFSET_Y && usesBlockTablePlacement()) {
            return String.valueOf((int) Math.round(normalizeBlockTableOffset(yamlConfig().getDouble(setting.path(), setting.defaultValue()))));
        }
        if (setting == AdminSetting.CHAIR_ROTATION_DEGREES && usesBlockChairPlacement()) {
            return String.valueOf((int) Math.round(normalizeBlockChairRotation(yamlConfig().getDouble(setting.path(), setting.defaultValue()))));
        }
        if (setting == AdminSetting.CHAIR_DISTANCE && usesBlockChairPlacement()) {
            return String.valueOf((int) Math.round(normalizeBlockChairDistance(yamlConfig().getDouble(setting.path(), setting.defaultValue()))));
        }
        if (usesFinePrecision(setting)) {
            return String.format(java.util.Locale.ROOT, "%.2f", yamlConfig().getDouble(setting.path(), setting.defaultValue()));
        }
        return String.format(java.util.Locale.ROOT, "%.1f", yamlConfig().getDouble(setting.path(), setting.defaultValue()));
    }

    public Component playerIdentityComponent(UUID playerId, String fallbackText, NamedTextColor fallbackColor) {
        String playerName = resolvePlayerName(playerId);
        if (playerName != null && !playerName.isBlank()) {
            Component head = VersionCompat.createPlayerHeadComponent(playerName);
            Component name = MuzTheme.named(" " + playerName, fallbackColor)
                .decoration(TextDecoration.ITALIC, false);
            return switch (playerHeadDisplayMode()) {
                case HEAD_ONLY -> head.decoration(TextDecoration.ITALIC, false);
                case BOTH -> head.append(name).decoration(TextDecoration.ITALIC, false);
                case NAME_ONLY -> name;
            };
        }
        return MuzTheme.named(normalizeNonBlank(fallbackText, "未知玩家"), fallbackColor)
            .decoration(TextDecoration.ITALIC, false);
    }

    public Component playerHeadComponent(UUID playerId, String fallbackText, NamedTextColor fallbackColor) {
        String playerName = resolvePlayerName(playerId);
        if (playerName != null && !playerName.isBlank()) {
            return VersionCompat.createPlayerHeadComponent(playerName);
        }
        return MuzTheme.named(normalizeNonBlank(fallbackText, "未知玩家"), fallbackColor)
            .decoration(TextDecoration.ITALIC, false);
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

    private void loadRenderSettings() {
        cardHologramLabelsEnabled = yamlConfig().getBoolean("cards.hologram-labels.enabled", true);
        duplicateOnlyCardLabels = yamlConfig().getBoolean("cards.hologram-labels.duplicate-ranks-only", false);
        tableSpawnOffsetY = yamlConfig().getDouble("table.spawn-offset-y", 0.18);
        privateCardScale = (float) yamlConfig().getDouble("render.private-card-scale", DEFAULT_PRIVATE_CARD_SCALE);
        publicCardScale = (float) yamlConfig().getDouble("render.public-trick-card-scale", DEFAULT_PUBLIC_CARD_SCALE);
        privateCardWidthScale = (float) yamlConfig().getDouble("render.private-card-size.width", privateCardScale);
        privateCardHeightScale = (float) yamlConfig().getDouble("render.private-card-size.height", privateCardScale);
        privateCardDepthScale = (float) yamlConfig().getDouble("render.private-card-size.depth", privateCardScale);
        publicCardWidthScale = (float) yamlConfig().getDouble("render.public-card-size.width", publicCardScale);
        publicCardHeightScale = (float) yamlConfig().getDouble("render.public-card-size.height", publicCardScale);
        publicCardDepthScale = (float) yamlConfig().getDouble("render.public-card-size.depth", publicCardScale);
        hoverCardScale = (float) yamlConfig().getDouble("render.card-hover.scale", 1.08);
        hoverCardLift = yamlConfig().getDouble("render.card-hover.lift", 0.06);
        cardHoverInterpolationTicks = Math.max(1, yamlConfig().getInt("render.card-hover.interpolation-ticks", 6));
        cardHoverAnimationTypeIndex = Math.max(0, Math.min(AnimationCurve.values().length - 1, yamlConfig().getInt("render.card-hover.animation-type", 1)));
        hoverButtonScale = (float) yamlConfig().getDouble("render.button-hover.scale", 1.06);
        hoverButtonLift = yamlConfig().getDouble("render.button-hover.lift", 0.03);
        buttonHoverInterpolationTicks = Math.max(1, yamlConfig().getInt("render.button-hover.interpolation-ticks", 8));
        buttonHoverAnimationTypeIndex = Math.max(0, Math.min(AnimationCurve.values().length - 1, yamlConfig().getInt("render.button-hover.animation-type", 3)));
        buttonScale = (float) yamlConfig().getDouble("render.button-scale", 0.42);
        tableScale = (float) yamlConfig().getDouble("render.furniture-scale.table", 2.25);
        chairScale = (float) yamlConfig().getDouble("render.furniture-scale.chair", 1.35);
        smallTextScale = (float) yamlConfig().getDouble("render.text-scale.small", 0.46);
        statusTextScale = (float) yamlConfig().getDouble("render.text-scale.status", 0.72);
        labelTextScale = (float) yamlConfig().getDouble("render.text-scale.label", 0.40);
        playerHeadScale = (float) yamlConfig().getDouble("render.player-head-scale", 1.00);
        playerHeadDisplayMode = PlayerHeadDisplayMode.fromConfig(yamlConfig().get("render.player-head-show-id"));
        statusAvatarScale = (float) yamlConfig().getDouble("render.status-avatar.scale", playerHeadScale);
        statusAvatarLateralOffset = yamlConfig().getDouble("render.status-avatar-offset.lateral", 0.0);
        statusAvatarVerticalOffset = yamlConfig().getDouble("render.status-avatar-offset.vertical", 0.82);
        statusAvatarDepthOffset = yamlConfig().getDouble("render.status-avatar-offset.depth", 0.0);
        statusNameScale = (float) yamlConfig().getDouble("render.status-name.scale", smallTextScale);
        statusNameLateralOffset = yamlConfig().getDouble("render.status-name-offset.lateral", 0.0);
        statusNameVerticalOffset = yamlConfig().getDouble("render.status-name-offset.vertical", 0.56);
        statusNameDepthOffset = yamlConfig().getDouble("render.status-name-offset.depth", 0.0);
        seatAvatarScale = (float) yamlConfig().getDouble("render.seat-avatar.scale", playerHeadScale);
        seatAvatarLateralOffset = yamlConfig().getDouble("render.seat-avatar-offset.lateral", 0.0);
        seatAvatarVerticalOffset = yamlConfig().getDouble("render.seat-avatar-offset.vertical", 0.18);
        seatAvatarDepthOffset = yamlConfig().getDouble("render.seat-avatar-offset.depth", 0.0);
        seatNameScale = (float) yamlConfig().getDouble("render.seat-name.scale", smallTextScale);
        seatNameLateralOffset = yamlConfig().getDouble("render.seat-name-offset.lateral", 0.0);
        seatNameVerticalOffset = yamlConfig().getDouble("render.seat-name-offset.vertical", -0.04);
        seatNameDepthOffset = yamlConfig().getDouble("render.seat-name-offset.depth", 0.0);
        emptySeatScale = (float) yamlConfig().getDouble("render.empty-seat.scale", seatNameScale);
        emptySeatLateralOffset = yamlConfig().getDouble("render.empty-seat-offset.lateral", seatNameLateralOffset);
        emptySeatVerticalOffset = yamlConfig().getDouble("render.empty-seat-offset.vertical", seatNameVerticalOffset);
        emptySeatDepthOffset = yamlConfig().getDouble("render.empty-seat-offset.depth", seatNameDepthOffset);
        seatInfoScale = (float) yamlConfig().getDouble("render.seat-info.scale", smallTextScale);
        seatInfoLateralOffset = yamlConfig().getDouble("render.seat-info-offset.lateral", 0.0);
        seatInfoVerticalOffset = yamlConfig().getDouble("render.seat-info-offset.vertical", -0.22);
        seatInfoDepthOffset = yamlConfig().getDouble("render.seat-info-offset.depth", 0.0);
        selectedCardScale = (float) yamlConfig().getDouble("render.selected-card.scale", 1.00);
        selectedCardLift = yamlConfig().getDouble("render.selected-card.lift", 0.18);
        hoverGlowEnabled = yamlConfig().getBoolean("render.hover-glow.enabled", true);
        Color loadedHoverGlow = parseRgbSpec(
            yamlConfig().getString("render.hover-glow.color"),
            Color.fromRGB(
                yamlConfig().getInt("render.hover-glow.color.red", 96),
                yamlConfig().getInt("render.hover-glow.color.green", 180),
                yamlConfig().getInt("render.hover-glow.color.blue", 255)
            )
        );
        hoverGlowRed = loadedHoverGlow.getRed();
        hoverGlowGreen = loadedHoverGlow.getGreen();
        hoverGlowBlue = loadedHoverGlow.getBlue();
        selectedGlowEnabled = yamlConfig().getBoolean("render.selected-glow.enabled", true);
        Color loadedSelectedGlow = parseRgbSpec(
            yamlConfig().getString("render.selected-glow.color"),
            Color.fromRGB(
                yamlConfig().getInt("render.selected-glow.color.red", 255),
                yamlConfig().getInt("render.selected-glow.color.green", 226),
                yamlConfig().getInt("render.selected-glow.color.blue", 92)
            )
        );
        selectedGlowRed = loadedSelectedGlow.getRed();
        selectedGlowGreen = loadedSelectedGlow.getGreen();
        selectedGlowBlue = loadedSelectedGlow.getBlue();
        cardLabelHeight = yamlConfig().getDouble("render.card-label-height", 0.34);
        cardLabelLateralOffset = yamlConfig().getDouble("render.card-label-offset.lateral", 0.0);
        cardLabelDepthOffset = yamlConfig().getDouble("render.card-label-offset.depth", 0.0);
        cardDepthOffset = (float) yamlConfig().getDouble("render.card-depth-offset", 0.01);
        handSpacing = (float) yamlConfig().getDouble("render.hand-spacing", 0.21);
        publicTrickSpacing = (float) yamlConfig().getDouble("render.public-trick-spacing", 0.22);
        buttonRollDegrees = (float) yamlConfig().getDouble("render.button-roll-degrees", DEFAULT_BUTTON_ROLL_DEGREES);
        buttonDistance = yamlConfig().getDouble("render.button-offset.distance", DEFAULT_BUTTON_DISTANCE);
        buttonHeight = yamlConfig().getDouble("render.button-offset.height", 1.02);
        tableDisplayHeight = yamlConfig().getDouble("render.layout.table-display-height", 0.55);
        tableColliderHeight = yamlConfig().getDouble("render.layout.table-collider-height", 0.72);
        chairBaseHeight = yamlConfig().getDouble("render.layout.chair-base-height", 0.20);
        chairColliderHeight = yamlConfig().getDouble("render.layout.chair-collider-height", 0.18);
        chairSeatHeight = yamlConfig().getDouble("render.layout.chair-seat-height", 0.18);
        chairInteractionHeight = yamlConfig().getDouble("render.layout.chair-interaction-height", 0.38);
        chairLabelHeight = yamlConfig().getDouble("render.layout.chair-label-height", 1.35);
        chairRotationDegrees = yamlConfig().getDouble("render.chair-rotation-degrees", 0.0);
        chairVisualLateralOffset = yamlConfig().getDouble("render.chair-visual-offset.lateral", 0.0);
        chairVisualVerticalOffset = yamlConfig().getDouble("render.chair-visual-offset.vertical", -0.04);
        chairHitboxLateralOffset = yamlConfig().getDouble("render.chair-hitbox-offset.lateral", 0.0);
        chairHitboxVerticalOffset = yamlConfig().getDouble("render.chair-hitbox-offset.vertical", -0.18);
        chairHitboxWidth = yamlConfig().getDouble("render.chair-hitbox.width", 0.22);
        chairHitboxHeight = yamlConfig().getDouble("render.chair-hitbox.height", 0.30);
        buttonHitboxLateralOffset = yamlConfig().getDouble("render.button-hitbox-offset.lateral", 0.0);
        buttonHitboxDepthOffset = yamlConfig().getDouble("render.button-hitbox-offset.depth", 0.0);
        buttonHitboxVerticalOffset = yamlConfig().getDouble("render.button-hitbox-offset.vertical", 0.02);
        buttonHitboxWidth = yamlConfig().getDouble("render.button-hitbox.width", 0.22);
        buttonHitboxHeight = yamlConfig().getDouble("render.button-hitbox.height", 0.34);
        cardHitboxLateralOffset = yamlConfig().getDouble("render.card-hitbox-offset.lateral", 0.0);
        cardHitboxDepthOffset = yamlConfig().getDouble("render.card-hitbox-offset.depth", 0.0);
        cardHitboxVerticalOffset = yamlConfig().getDouble("render.card-hitbox-offset.vertical", DEFAULT_CARD_HITBOX_VERTICAL_OFFSET);
        cardHitboxLength = yamlConfig().getDouble("render.card-hitbox.length", 0.30);
        cardHitboxWidth = yamlConfig().getDouble("render.card-hitbox.width", 0.18);
        cardHitboxHeight = yamlConfig().getDouble("render.card-hitbox.height", 0.62);
        statusHeight = yamlConfig().getDouble("render.status-height", 3.10);
        playDetailHeight = yamlConfig().getDouble("render.play-detail-height", 2.35);
        publicTrickHeight = yamlConfig().getDouble("render.public-trick-height", 1.55);
        statusLineWidth = yamlConfig().getInt("render.layout.status-line-width", 250);
        handCenterDistance = yamlConfig().getDouble("render.layout.hand-center.distance", 1.62);
        handCenterHeight = yamlConfig().getDouble("render.layout.hand-center.height", 1.23);
        chairDistance = yamlConfig().getDouble("render.layout.chair-distance", 2.35);
        joinLabelHeight = yamlConfig().getDouble("render.button-layout.join-label-height", 0.18);
        joinLabelScale = (float) yamlConfig().getDouble("render.button-layout.join-label-scale", 0.46);
        actionLabelHeight = yamlConfig().getDouble("render.button-layout.action-label-height", 0.18);
        actionLabelScale = (float) yamlConfig().getDouble("render.button-layout.action-label-scale", 0.20);
        buttonFrontBaseDistance = yamlConfig().getDouble("render.button-layout.front-base-distance", 1.40);
        buttonSideBaseDistance = yamlConfig().getDouble("render.button-layout.side-base-distance", 1.72);
        buttonDistanceFactor = yamlConfig().getDouble("render.button-layout.distance-factor", 0.45);
        buttonSpacingScale = yamlConfig().getDouble("render.button-layout.spacing-scale", 1.0);
        buttonArcSmallAngleDegrees = yamlConfig().getDouble("render.button-layout.arc-angle-small", 30.0);
        buttonArcLargeAngleDegrees = yamlConfig().getDouble("render.button-layout.arc-angle-large", 42.0);
        buttonArcSmallRadius = yamlConfig().getDouble("render.button-layout.arc-radius-small", 0.70);
        buttonArcLargeRadius = yamlConfig().getDouble("render.button-layout.arc-radius-large", 0.86);
        previewCardsPerRow = Math.max(1, yamlConfig().getInt("render.public-trick.cards-per-row", 6));
        publicPreviewCompareRowOffset = yamlConfig().getDouble("render.public-trick.compare-row-offset", 0.28);
        publicPreviewSelectedRowOffset = yamlConfig().getDouble("render.public-trick.selected-row-offset", -0.24);
        publicPreviewRowDepthSpacing = yamlConfig().getDouble("render.public-trick.row-depth-spacing", 0.22);
        publicPreviewLabelHeight = yamlConfig().getDouble("render.public-trick.label-height", 0.22);
        globalPrivateHandLateralOffset = yamlConfig().getDouble("render.private-hand-offset.lateral", 0.0);
        globalPrivateHandVerticalOffset = yamlConfig().getDouble("render.private-hand-offset.vertical", 0.0);
        globalPrivateHandDepthOffset = yamlConfig().getDouble("render.private-hand-offset.depth", 0.0);
        bgmVolume = (float) yamlConfig().getDouble("audio.bgm-volume", 0.55);
        effectVolume = (float) yamlConfig().getDouble("audio.effect-volume", 1.0);
        turnCountdownSeconds = yamlConfig().getInt("actionbar.turn-countdown-seconds", 20);
        countdownSoundSpec = safeNormalizeCountdownSoundSpec(yamlConfig().getString("actionbar.countdown-sound", DEFAULT_COUNTDOWN_SOUND_SPEC));
        unreadyWarningSoundSpec = safeNormalizeCountdownSoundSpec(yamlConfig().getString("actionbar.unready-warning-sound", DEFAULT_UNREADY_WARNING_SOUND_SPEC));
        placementBlockedSoundSpec = safeNormalizeCountdownSoundSpec(yamlConfig().getString("table.placement-blocked-sound", DEFAULT_PLACEMENT_BLOCKED_SOUND_SPEC));
        botActionDelayMinTicks = yamlConfig().getInt("bot.action-delay-min-ticks", yamlConfig().getInt("bot.action-delay-ticks", 20));
        botActionDelayMaxTicks = yamlConfig().getInt("bot.action-delay-max-ticks", yamlConfig().getInt("bot.action-delay-ticks", 20));
        botAiEnabled = yamlConfig().getBoolean("bot.ai.enabled", false);
        botAiTimeoutMs = Math.max(1000, yamlConfig().getInt("bot.ai.timeout-ms", 5000));
        if (botActionDelayMaxTicks < botActionDelayMinTicks) {
            int swapped = botActionDelayMinTicks;
            botActionDelayMinTicks = botActionDelayMaxTicks;
            botActionDelayMaxTicks = swapped;
        }
        hintGroupLimit = yamlConfig().getInt("hints.max-groups", 6);
        debugTableSpacing = yamlConfig().getDouble("debug.table-spacing", 6.5);
        vaultEconomyEnabled = yamlConfig().getBoolean("economy.vault.enabled", true);
        chipPaymentEnabled = yamlConfig().getBoolean("economy.payment.use-chip", false);
        vaultDoudizhuCurrencyPerPoint = Math.max(0.0001, yamlConfig().getDouble("economy.vault.doudizhu.currency-per-point", 1.0));
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
            yamlConfig().getInt("ai.deepseek.connect-timeout-ms", 10000),
            yamlConfig().getInt("ai.deepseek.request-timeout-ms", 45000),
            roundToSingleDecimal(yamlConfig().getDouble("ai.deepseek.temperature", 0.7)),
            yamlConfig().getInt("ai.deepseek.max-tokens", 0),
            normalizeNonBlank(yamlConfig().getString("ai.deepseek.system-prompt"), DEFAULT_AI_SYSTEM_PROMPT)
        );
        aiChatGateway = new OpenAiCompatibleAiChatGateway(aiProviderConfig, getLogger());
    }

    private void ensureConfigIntegrity() {
        mergeDefaultYamlConfig();
        boolean changed = false;
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
        if (changed) {
            saveYamlConfig();
        }
    }

    private boolean ensureBotAiConfig() {
        boolean changed = false;
        changed |= ensureMissingConfigValue("bot.ai.enabled", true);
        changed |= ensureMissingConfigValue("bot.ai.timeout-ms", 5000);
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
        changed |= ensureMissingConfigValue("ai.deepseek.connect-timeout-ms", 10000);
        changed |= ensureMissingConfigValue("ai.deepseek.request-timeout-ms", 45000);
        changed |= ensureMissingConfigValue("ai.deepseek.temperature", 0.7);
        changed |= ensureMissingConfigValue("ai.deepseek.max-tokens", 0);
        changed |= ensureMissingConfigValue("ai.deepseek.system-prompt", DEFAULT_AI_SYSTEM_PROMPT);
        return changed;
    }

    private boolean ensureAvatarConfig() {
        boolean changed = false;
        double legacyScale = yamlConfig().getDouble("render.player-head-scale", 1.00);
        double defaultSmallTextScale = yamlConfig().getDouble("render.text-scale.small", 0.46);
        changed |= ensureDoubleConfig("render.status-avatar.scale", legacyScale);
        changed |= ensureDoubleConfig("render.status-avatar-offset.lateral", 0.0);
        changed |= ensureDoubleConfig("render.status-avatar-offset.vertical", 0.82);
        changed |= ensureDoubleConfig("render.status-avatar-offset.depth", 0.0);
        changed |= ensureDoubleConfig("render.status-name.scale", defaultSmallTextScale);
        changed |= ensureDoubleConfig("render.status-name-offset.lateral", 0.0);
        changed |= ensureDoubleConfig("render.status-name-offset.vertical", 0.56);
        changed |= ensureDoubleConfig("render.status-name-offset.depth", 0.0);
        changed |= ensureDoubleConfig("render.seat-avatar.scale", legacyScale);
        changed |= ensureDoubleConfig("render.seat-avatar-offset.lateral", 0.0);
        changed |= ensureDoubleConfig("render.seat-avatar-offset.vertical", 0.18);
        changed |= ensureDoubleConfig("render.seat-avatar-offset.depth", 0.0);
        changed |= ensureDoubleConfig("render.seat-name.scale", defaultSmallTextScale);
        changed |= ensureDoubleConfig("render.seat-name-offset.lateral", 0.0);
        changed |= ensureDoubleConfig("render.seat-name-offset.vertical", -0.04);
        changed |= ensureDoubleConfig("render.seat-name-offset.depth", 0.0);
        changed |= ensureDoubleConfig("render.empty-seat.scale", defaultSmallTextScale);
        changed |= ensureDoubleConfig("render.empty-seat-offset.lateral", 0.0);
        changed |= ensureDoubleConfig("render.empty-seat-offset.vertical", -0.04);
        changed |= ensureDoubleConfig("render.empty-seat-offset.depth", 0.0);
        changed |= ensureDoubleConfig("render.seat-info.scale", defaultSmallTextScale);
        changed |= ensureDoubleConfig("render.seat-info-offset.lateral", 0.0);
        changed |= ensureDoubleConfig("render.seat-info-offset.vertical", -0.22);
        changed |= ensureDoubleConfig("render.seat-info-offset.depth", 0.0);
        changed |= ensureDoubleConfig("render.button-layout.join-label-scale", 0.46);
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
        if (
            yamlConfig().contains("render.button-offset.distance")
                && (
                    Math.abs(yamlConfig().getDouble("render.button-offset.distance", Double.NaN) - OLDER_BUTTON_DISTANCE) < 0.0001
                        || Math.abs(yamlConfig().getDouble("render.button-offset.distance", Double.NaN) - LEGACY_BUTTON_DISTANCE) < 0.0001
                )
        ) {
            yamlConfig().set("render.button-offset.distance", DEFAULT_BUTTON_DISTANCE);
            changed = true;
        }
        if (
            yamlConfig().contains("render.card-hitbox-offset.vertical")
                && Math.abs(yamlConfig().getDouble("render.card-hitbox-offset.vertical", Double.NaN) - LEGACY_CARD_HITBOX_VERTICAL_OFFSET) < 0.0001
        ) {
            yamlConfig().set("render.card-hitbox-offset.vertical", DEFAULT_CARD_HITBOX_VERTICAL_OFFSET);
            changed = true;
        }
        if (
            yamlConfig().contains("render.button-roll-degrees")
                && Math.abs(yamlConfig().getDouble("render.button-roll-degrees", Double.NaN) - LEGACY_BUTTON_ROLL_DEGREES) < 0.0001
        ) {
            yamlConfig().set("render.button-roll-degrees", DEFAULT_BUTTON_ROLL_DEGREES);
            changed = true;
        }
        if (yamlConfig().contains("bot.action-delay-ticks")) {
            if (!yamlConfig().contains("bot.action-delay-min-ticks")) {
                yamlConfig().set("bot.action-delay-min-ticks", yamlConfig().getInt("bot.action-delay-ticks", 20));
                changed = true;
            }
            if (!yamlConfig().contains("bot.action-delay-max-ticks")) {
                yamlConfig().set("bot.action-delay-max-ticks", yamlConfig().getInt("bot.action-delay-ticks", 20));
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
        int doudizhuTables = physicalTableManager == null ? 0 : physicalTableManager.placedTableCount();
        int ddzStageIndex = exportBundle ? 3 : 2;
        feedback.update(stageProgress(ddzStageIndex, totalStages), "刷新斗地主牌桌", rebuildDetail("斗地主牌桌", doudizhuTables));
        if (physicalTableManager != null) {
            physicalTableManager.rebuildAllTables();
        }
        ReloadSummary summary = new ReloadSummary(exportResult, detectSupportedHooks(placeholderHook, vaultHook), doudizhuTables);
        feedback.complete(summary);
        return summary;
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
            yamlConfig().set("economy.vault.doudizhu.currency-per-point", 1.0);
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
        if (!yamlConfig().contains("storage.sql.type")) {
            yamlConfig().set("storage.sql.type", "sqlite");
            changed = true;
        }
        if (!yamlConfig().contains("render.player-head-show-id")) {
            yamlConfig().set("render.player-head-show-id", PlayerHeadDisplayMode.BOTH.configValue());
            changed = true;
        }
        if (!yamlConfig().contains("storage.sql.sqlite.file")) {
            yamlConfig().set("storage.sql.sqlite.file", "storage/mumu-data.db");
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
        pendingPersistedTables = List.copyOf(loaded);
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

    private boolean restorePendingSqlTables() {
        if (databaseManager == null || !databaseManager.isInitialized()) {
            persistedTableRestoreSummary = "数据库尚未就绪";
            return false;
        }
        if (pendingPersistedTables.isEmpty()) {
            persistedTableRestoreSummary = "没有待恢复牌桌";
            return true;
        }

        int restored = 0;
        int waitingWorld = 0;
        int failed = 0;
        List<PersistedTableRecord> remaining = new ArrayList<>();

        for (PersistedTableRecord record : pendingPersistedTables) {
            org.bukkit.World world = Bukkit.getWorld(record.worldName());
            if (world == null) {
                waitingWorld++;
                remaining.add(record);
                continue;
            }
            org.bukkit.Location anchor = new org.bukkit.Location(world, record.x(), record.y(), record.z(), record.yaw(), 0.0f);
            try {
                if ("DOUDIZHU".equalsIgnoreCase(record.gameType())) {
                    physicalTableManager.restoreTable(record.tableName(), record.roomLevel(), anchor, record.yaw(), parseNullableUuid(record.ownerUuid()), record.ownerName());
                } else {
                    // 德州玩法已移除，遗留的旧牌桌记录直接清理掉，避免每次启动都尝试恢复。
                    databaseManager.deleteTable(record.gameType(), record.tableName());
                    continue;
                }
                restored++;
            } catch (RuntimeException exception) {
                failed++;
                remaining.add(record);
                if (persistedTableRestorePasses <= 1) {
                    getLogger().warning("恢复牌桌失败 [" + record.gameType() + "/" + record.tableName() + "]: " + exception.getMessage());
                }
            }
        }

        pendingPersistedTables = List.copyOf(remaining);
        persistedTableRestorePasses++;
        if (remaining.isEmpty()) {
            persistedTableRestoreSummary = "牌桌恢复完成，本次恢复 " + restored + " 张";
            return true;
        }
        persistedTableRestoreSummary = "已恢复 " + restored + " 张，待世界加载 " + waitingWorld + " 张，待重试 " + failed + " 张";
        return false;
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
                "SQL牌桌已加载: 斗地主 "
                    + (physicalTableManager == null ? 0 : physicalTableManager.placedTableCount())
                    + " 张 | " + persistedTableRestoreSummary
            );
        } else if (persistedTableRestorePasses <= 1 || persistedTableRestorePasses % 10 == 0) {
            getLogger().info("SQL牌桌恢复重试中: " + persistedTableRestoreSummary);
        }
    }

    private void schedulePostRestoreRebuilds() {
        if (postRestoreRebuildQueued || shuttingDown) {
            return;
        }
        int doudizhuCount = physicalTableManager == null ? 0 : physicalTableManager.placedTableCount();
        if (doudizhuCount <= 0) {
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
                if (physicalTableManager != null && physicalTableManager.placedTableCount() > 0) {
                    physicalTableManager.rebuildAllTables();
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

    private Component historySelfLine(PlayerHistoryEntry entry) {
        MatchParticipantRecord self = entry.self();
        if (self == null) {
            return plain(Component.text("玩家信息缺失", NamedTextColor.GRAY));
        }
        return concat(
            gradientLabel("玩家", HISTORY_CYAN, HISTORY_SKY),
            Component.text(" ", NamedTextColor.WHITE).decoration(TextDecoration.ITALIC, false),
            playerIdentityComponent(self.playerId(), self.playerName(), NamedTextColor.WHITE),
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
        return concat(
            gradientLabel("地主", HISTORY_PINK, HISTORY_ROSE),
            Component.text(" ", NamedTextColor.WHITE).decoration(TextDecoration.ITALIC, false),
            playerIdentityComponent(landlord.playerId(), landlord.playerName(), NamedTextColor.WHITE)
        );
    }

    private List<Component> historyFarmerLines(PlayerHistoryEntry entry) {
        List<MatchParticipantRecord> farmers = entry.participants().stream()
            .filter(participant -> "农民".equals(participant.roleLabel()))
            .toList();
        List<Component> lines = new ArrayList<>(farmers.size());
        for (MatchParticipantRecord farmer : farmers) {
            lines.add(concat(
                gradientLabel("农民", HISTORY_GOLD, HISTORY_CREAM),
                Component.text(" ", NamedTextColor.WHITE).decoration(TextDecoration.ITALIC, false),
                playerIdentityComponent(farmer.playerId(), farmer.playerName(), NamedTextColor.WHITE)
            ));
        }
        return lines;
    }

    private Component historyPersonalSettlementLine(PlayerHistoryEntry entry) {
        MatchParticipantRecord self = entry.self();
        if (self == null) {
            return plain(Component.text("个人结算 未知", NamedTextColor.GRAY));
        }
        boolean win = "WIN".equalsIgnoreCase(self.outcome());
        String unit = selfUnitLabel(entry);
        double income = Math.max(0.0, self.settlementDelta());
        double expense = Math.max(0.0, -self.settlementDelta());
        NamedTextColor deltaColor = self.settlementDelta() >= 0 ? NamedTextColor.GREEN : NamedTextColor.RED;
        Component line = concat(
            historyMatchChip(win),
            historyDivider(),
            historyOutcomeChip(win),
            historyDivider(),
            historyGainLossChip(self.settlementDelta() >= 0, formatAmount(Math.abs(self.settlementDelta())) + unit),
            historyDivider(),
            gradientLabel("收入", HISTORY_GREEN, HISTORY_MINT),
            Component.text(" " + formatAmount(income) + unit, NamedTextColor.GREEN).decoration(TextDecoration.ITALIC, false),
            historyDivider(),
            gradientLabel("支出", HISTORY_PINK, HISTORY_RED),
            Component.text(" " + formatAmount(expense) + unit, expense > 0.0001 ? NamedTextColor.RED : NamedTextColor.DARK_GRAY).decoration(TextDecoration.ITALIC, false),
            historyDivider(),
            historyNetChip(self.settlementDelta() >= 0),
            Component.text(" " + formatSigned(self.settlementDelta()) + unit, deltaColor).decoration(TextDecoration.ITALIC, false)
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
        double income = Math.max(0.0, participant.settlementDelta());
        double expense = Math.max(0.0, -participant.settlementDelta());
        NamedTextColor deltaColor = participant.settlementDelta() >= 0 ? NamedTextColor.GREEN : NamedTextColor.RED;
        return concat(
            Component.text("• ", NamedTextColor.DARK_GRAY).decoration(TextDecoration.ITALIC, false),
            playerIdentityComponent(participant.playerId(), participant.playerName(), NamedTextColor.WHITE),
            historyDivider(),
            historyRoleChip(participant.roleLabel()),
            historyDivider(),
            historyGainLossChip(participant.settlementDelta() >= 0, formatAmount(Math.abs(participant.settlementDelta())) + unit),
            historyDivider(),
            gradientLabel("收入", HISTORY_GREEN, HISTORY_MINT),
            Component.text(" " + formatAmount(income) + unit, NamedTextColor.GREEN).decoration(TextDecoration.ITALIC, false),
            historyDivider(),
            gradientLabel("支出", HISTORY_PINK, HISTORY_RED),
            Component.text(" " + formatAmount(expense) + unit, expense > 0.0001 ? NamedTextColor.RED : NamedTextColor.DARK_GRAY).decoration(TextDecoration.ITALIC, false),
            historyDivider(),
            historyNetChip(participant.settlementDelta() >= 0),
            Component.text(" " + formatSigned(participant.settlementDelta()) + unit, deltaColor).decoration(TextDecoration.ITALIC, false)
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

    private Component historyMatchChip(boolean win) {
        return win ? gradientLabel("对局", HISTORY_CYAN, HISTORY_GREEN) : gradientLabel("对局", HISTORY_PINK, HISTORY_RED);
    }

    private Component historyOutcomeChip(boolean win) {
        return win ? gradientLabel("胜利", HISTORY_CYAN, HISTORY_GREEN) : gradientLabel("失利", HISTORY_PINK, HISTORY_RED);
    }

    private Component historyNetChip(boolean positive) {
        return positive ? gradientLabel("净变化", HISTORY_CYAN, HISTORY_GREEN) : gradientLabel("净变化", HISTORY_PINK, HISTORY_RED);
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
        Component result = Component.empty();
        for (Component component : components) {
            if (component != null) {
                result = result.append(component);
            }
        }
        return plain(result);
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

    private String formatAmount(double value) {
        if (Math.abs(value - Math.rint(value)) < 0.0001) {
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
        if (Math.abs(value - Math.rint(value)) < 0.0001) {
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
        if (usesFinePrecision(setting)) {
            return setting.step();
        }
        return setting.integerSetting() ? setting.step() : Math.max(0.1, roundToSingleDecimal(setting.step()));
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
        if (usesFinePrecision(setting)) {
            return current;
        }
        return roundToSingleDecimal(current);
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
        if (usesFinePrecision(setting)) {
            return value;
        }
        return roundToSingleDecimal(value);
    }

    private boolean usesFinePrecision(AdminSetting setting) {
        return setting == AdminSetting.TABLE_SPAWN_OFFSET_Y
            || setting == AdminSetting.HAND_SPACING
            || setting == AdminSetting.CARD_DEPTH_OFFSET
            || setting == AdminSetting.HOVER_CARD_SCALE
            || setting == AdminSetting.HOVER_CARD_LIFT
            || setting == AdminSetting.HOVER_BUTTON_SCALE
            || setting == AdminSetting.HOVER_BUTTON_LIFT;
    }

    private double roundToSingleDecimal(double value) {
        return Math.round(value * 10.0) / 10.0;
    }

    private double normalizeBlockChairRotation(double value) {
        return Math.round(value / 90.0) * 90.0;
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
            try {
                java.lang.reflect.Method hasItemModel = meta.getClass().getMethod("hasItemModel");
                java.lang.reflect.Method getItemModel = meta.getClass().getMethod("getItemModel");
                if ((boolean) hasItemModel.invoke(meta)) {
                    Object model = getItemModel.invoke(meta);
                    candidates.addAll(furnitureItemIdCandidates(model.toString(), fallbackKey));
                }
            } catch (Exception ignored) {
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
            int red = Math.max(0, Math.min(255, Integer.parseInt(parts[0])));
            int green = Math.max(0, Math.min(255, Integer.parseInt(parts[1])));
            int blue = Math.max(0, Math.min(255, Integer.parseInt(parts[2])));
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
        playerOpponentPreviewSettings.clear();
        playerSelectionSoundProfileSettings.clear();
        playerPlayActionProfileSettings.clear();
        playerPlayActionKindProfileSettings.clear();
        playerHoverGlowColorSettings.clear();
        playerSelectedGlowColorSettings.clear();
        playerChipBalances.clear();
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
                if (playerSettingsConfig.contains(base + ".opponent-preview")) {
                    playerOpponentPreviewSettings.put(playerId, playerSettingsConfig.getBoolean(base + ".opponent-preview", false));
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
                if (playerSettingsConfig.contains(base + ".chip-balance")) {
                    playerChipBalances.put(playerId, playerSettingsConfig.getInt(base + ".chip-balance", 0));
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
            } catch (IllegalArgumentException ignored) {
            }
        }
    }

    private void savePlayerSettings() {
        if (playerSettingsFile == null) {
            return;
        }
        MuzYamlConfig configuration = MuzYamlConfig.empty(playerSettingsFile.toPath());
        Set<UUID> players = new LinkedHashSet<>();
        players.addAll(playerCardLabelSettings.keySet());
        players.addAll(playerSelectionSoundSettings.keySet());
        players.addAll(playerOpponentPreviewSettings.keySet());
        players.addAll(playerSelectionSoundProfileSettings.keySet());
        players.addAll(playerPlayActionProfileSettings.keySet());
        players.addAll(playerPlayActionKindProfileSettings.keySet());
        players.addAll(playerHoverGlowColorSettings.keySet());
        players.addAll(playerSelectedGlowColorSettings.keySet());
        players.addAll(playerChipBalances.keySet());
        players.addAll(playerHandOffsets.keySet());
        configuration.set("players", new LinkedHashMap<String, Object>());
        for (UUID playerId : players) {
            String base = "players." + playerId;
            if (playerCardLabelSettings.containsKey(playerId)) {
                configuration.set(base + ".labels-enabled", playerCardLabelSettings.get(playerId));
            }
            if (playerSelectionSoundSettings.containsKey(playerId)) {
                configuration.set(base + ".selection-sound", playerSelectionSoundSettings.get(playerId));
            }
            if (playerOpponentPreviewSettings.containsKey(playerId)) {
                configuration.set(base + ".opponent-preview", playerOpponentPreviewSettings.get(playerId));
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
            if (playerChipBalances.containsKey(playerId)) {
                configuration.set(base + ".chip-balance", playerChipBalances.get(playerId));
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
        return Math.max(0, Math.min(PLAYER_OPTION_PROFILE_COUNT - 1, index));
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

    public enum PlayerHeadDisplayMode {
        HEAD_ONLY(0, "只显示头像"),
        BOTH(1, "都显示"),
        NAME_ONLY(2, "只显示名字");

        private final int configValue;
        private final String label;

        PlayerHeadDisplayMode(int configValue, String label) {
            this.configValue = configValue;
            this.label = label;
        }

        public int configValue() {
            return configValue;
        }

        public String label() {
            return label;
        }

        public boolean showAvatar() {
            return this != NAME_ONLY;
        }

        public boolean showName() {
            return this != HEAD_ONLY;
        }

        public PlayerHeadDisplayMode next() {
            return switch (this) {
                case HEAD_ONLY -> BOTH;
                case BOTH -> NAME_ONLY;
                case NAME_ONLY -> HEAD_ONLY;
            };
        }

        public static PlayerHeadDisplayMode fromConfig(Object raw) {
            if (raw instanceof Boolean bool) {
                return bool ? BOTH : HEAD_ONLY;
            }
            if (raw instanceof Number number) {
                return switch (number.intValue()) {
                    case 0 -> HEAD_ONLY;
                    case 2 -> NAME_ONLY;
                    default -> BOTH;
                };
            }
            String normalized = raw == null ? "" : raw.toString().trim().toLowerCase(Locale.ROOT);
            return switch (normalized) {
                case "0", "head_only", "head-only", "avatar", "avatar_only", "avatar-only" -> HEAD_ONLY;
                case "2", "name_only", "name-only", "name" -> NAME_ONLY;
                default -> BOTH;
            };
        }
    }

    public enum AdminSetting {
        TABLE_SPAWN_OFFSET_Y("table.spawn-offset-y", "桌子高度", 0.18, -5.0, 5.0, 0.05, false, false, false),
        PRIVATE_CARD_SCALE("render.private-card-scale", "实体手牌大小", DEFAULT_PRIVATE_CARD_SCALE, 0.10, 5.0, 0.02, false, false, false),
        PUBLIC_TRICK_CARD_SCALE("render.public-trick-card-scale", "出牌预览大小", DEFAULT_PUBLIC_CARD_SCALE, 0.10, 5.0, 0.02, false, false, false),
        PRIVATE_CARD_WIDTH_SCALE("render.private-card-size.width", "手牌宽度缩放", DEFAULT_PRIVATE_CARD_SCALE, 0.05, 5.0, 0.02, false, false, false),
        PRIVATE_CARD_HEIGHT_SCALE("render.private-card-size.height", "手牌高度缩放", DEFAULT_PRIVATE_CARD_SCALE, 0.05, 5.0, 0.02, false, false, false),
        PRIVATE_CARD_DEPTH_SCALE("render.private-card-size.depth", "手牌厚度缩放", DEFAULT_PRIVATE_CARD_SCALE, 0.01, 5.0, 0.02, false, false, false),
        PUBLIC_CARD_WIDTH_SCALE("render.public-card-size.width", "预览宽度缩放", DEFAULT_PUBLIC_CARD_SCALE, 0.05, 5.0, 0.02, false, false, false),
        PUBLIC_CARD_HEIGHT_SCALE("render.public-card-size.height", "预览高度缩放", DEFAULT_PUBLIC_CARD_SCALE, 0.05, 5.0, 0.02, false, false, false),
        PUBLIC_CARD_DEPTH_SCALE("render.public-card-size.depth", "预览厚度缩放", DEFAULT_PUBLIC_CARD_SCALE, 0.01, 5.0, 0.02, false, false, false),
        HOVER_CARD_SCALE("render.card-hover.scale", "悬停放大倍数", 1.08, 1.0, 2.5, 0.01, false, false, false),
        HOVER_CARD_LIFT("render.card-hover.lift", "悬停上移高度", 0.06, 0.0, 1.0, 0.01, false, false, false),
        HOVER_CARD_INTERPOLATION_TICKS("render.card-hover.interpolation-ticks", "牌预览动画时长", 6.0, 1.0, 20.0, 1.0, false, true, false),
        HOVER_CARD_ANIMATION_TYPE("render.card-hover.animation-type", "牌预览动画类型", 1.0, 0.0, 3.0, 1.0, false, true, false),
        HOVER_BUTTON_SCALE("render.button-hover.scale", "按钮悬停放大", 1.08, 1.0, 2.0, 0.01, false, false, false),
        HOVER_BUTTON_LIFT("render.button-hover.lift", "按钮悬停上移", 0.04, 0.0, 1.0, 0.01, false, false, false),
        HOVER_BUTTON_INTERPOLATION_TICKS("render.button-hover.interpolation-ticks", "按钮预览动画时长", 8.0, 1.0, 20.0, 1.0, false, true, false),
        HOVER_BUTTON_ANIMATION_TYPE("render.button-hover.animation-type", "按钮预览动画类型", 3.0, 0.0, 3.0, 1.0, false, true, false),
        HAND_SPACING("render.hand-spacing", "默认手牌间距", 0.21, 0.02, 2.0, 0.01, false, false, false),
        PUBLIC_TRICK_SPACING("render.public-trick-spacing", "出牌预览间距", 0.22, 0.02, 2.0, 0.01, false, false, false),
        PUBLIC_PREVIEW_ROW_DEPTH_SPACING("render.public-trick.row-depth-spacing", "预览前后错开", 0.22, 0.0, 3.0, 0.01, false, false, false),
        CARD_LABEL_HEIGHT("render.card-label-height", "牌面标签高度", 0.34, 0.0, 3.0, 0.02, false, false, false),
        CARD_LABEL_LATERAL("render.card-label-offset.lateral", "牌面标签左右偏移", 0.0, -2.0, 2.0, 0.02, false, false, false),
        CARD_LABEL_DEPTH("render.card-label-offset.depth", "牌面标签前后偏移", 0.0, -2.0, 2.0, 0.02, false, false, false),
        BUTTON_SCALE("render.button-scale", "按钮大小", 0.42, 0.05, 3.0, 0.02, false, false, false),
        PLAYER_HEAD_SCALE("render.player-head-scale", "玩家头像大小", 1.00, 0.50, 4.0, 0.10, false, false, false),
        PLAYER_HEAD_SHOW_ID("render.player-head-show-id", "头像/名字显示模式", 1.0, 0.0, 2.0, 1.0, false, true, false),
        STATUS_AVATAR_SCALE("render.status-avatar.scale", "顶栏头像大小", 1.00, 0.40, 4.0, 0.05, false, false, false),
        STATUS_AVATAR_LATERAL("render.status-avatar-offset.lateral", "顶栏头像左右偏移", 0.0, -3.0, 3.0, 0.02, false, false, false),
        STATUS_AVATAR_VERTICAL("render.status-avatar-offset.vertical", "顶栏头像上下偏移", 0.82, -2.0, 4.0, 0.05, false, false, false),
        STATUS_AVATAR_DEPTH("render.status-avatar-offset.depth", "顶栏头像前后偏移", 0.0, -3.0, 3.0, 0.02, false, false, false),
        STATUS_NAME_SCALE("render.status-name.scale", "顶栏名字大小", 0.46, 0.20, 4.0, 0.05, false, false, false),
        STATUS_NAME_LATERAL("render.status-name-offset.lateral", "顶栏名字左右偏移", 0.0, -3.0, 3.0, 0.02, false, false, false),
        STATUS_NAME_VERTICAL("render.status-name-offset.vertical", "顶栏名字上下偏移", 0.56, -2.0, 4.0, 0.05, false, false, false),
        STATUS_NAME_DEPTH("render.status-name-offset.depth", "顶栏名字前后偏移", 0.0, -3.0, 3.0, 0.02, false, false, false),
        SEAT_AVATAR_SCALE("render.seat-avatar.scale", "座位头像大小", 1.00, 0.40, 4.0, 0.05, false, false, false),
        SEAT_AVATAR_LATERAL("render.seat-avatar-offset.lateral", "座位头像左右偏移", 0.0, -3.0, 3.0, 0.02, false, false, false),
        SEAT_AVATAR_VERTICAL("render.seat-avatar-offset.vertical", "座位头像上下偏移", 0.18, -2.0, 4.0, 0.05, false, false, false),
        SEAT_AVATAR_DEPTH("render.seat-avatar-offset.depth", "座位头像前后偏移", 0.0, -3.0, 3.0, 0.02, false, false, false),
        SEAT_NAME_SCALE("render.seat-name.scale", "座位名字大小", 0.46, 0.20, 4.0, 0.05, false, false, false),
        SEAT_NAME_LATERAL("render.seat-name-offset.lateral", "座位名字左右偏移", 0.0, -3.0, 3.0, 0.02, false, false, false),
        SEAT_NAME_VERTICAL("render.seat-name-offset.vertical", "座位名字上下偏移", -0.04, -2.0, 4.0, 0.05, false, false, false),
        SEAT_NAME_DEPTH("render.seat-name-offset.depth", "座位名字前后偏移", 0.0, -3.0, 3.0, 0.02, false, false, false),
        EMPTY_SEAT_SCALE("render.empty-seat.scale", "空位主文字大小", 0.46, 0.20, 4.0, 0.05, false, false, false),
         EMPTY_SEAT_LATERAL("render.empty-seat-offset.lateral", "空位主文字左右偏移", 0.0, -3.0, 3.0, 0.02, false, false, false),
        EMPTY_SEAT_VERTICAL("render.empty-seat-offset.vertical", "空位主文字上下偏移", -0.04, -2.0, 4.0, 0.05, false, false, false),
        EMPTY_SEAT_DEPTH("render.empty-seat-offset.depth", "空位主文字前后偏移", 0.0, -3.0, 3.0, 0.02, false, false, false),
        SEAT_INFO_SCALE("render.seat-info.scale", "座位副标题大小", 0.46, 0.20, 4.0, 0.05, false, false, false),
        SEAT_INFO_LATERAL("render.seat-info-offset.lateral", "座位副标题左右偏移", 0.0, -3.0, 3.0, 0.02, false, false, false),
        SEAT_INFO_VERTICAL("render.seat-info-offset.vertical", "座位副标题上下偏移", -0.22, -2.0, 4.0, 0.05, false, false, false),
        SEAT_INFO_DEPTH("render.seat-info-offset.depth", "座位副标题前后偏移", 0.0, -3.0, 3.0, 0.02, false, false, false),
        HOVER_GLOW_ENABLED("render.hover-glow.enabled", "预览发光", 1.0, 0.0, 1.0, 1.0, true, false, true),
        HOVER_GLOW_RED("render.hover-glow.color.red", "预览发光红", 96.0, 0.0, 255.0, 1.0, false, true, false),
        HOVER_GLOW_GREEN("render.hover-glow.color.green", "预览发光绿", 180.0, 0.0, 255.0, 1.0, false, true, false),
        HOVER_GLOW_BLUE("render.hover-glow.color.blue", "预览发光蓝", 255.0, 0.0, 255.0, 1.0, false, true, false),
        SELECTED_GLOW_ENABLED("render.selected-glow.enabled", "预选发光", 1.0, 0.0, 1.0, 1.0, true, false, true),
        SELECTED_GLOW_RED("render.selected-glow.color.red", "预选发光红", 255.0, 0.0, 255.0, 1.0, false, true, false),
        SELECTED_GLOW_GREEN("render.selected-glow.color.green", "预选发光绿", 226.0, 0.0, 255.0, 1.0, false, true, false),
        SELECTED_GLOW_BLUE("render.selected-glow.color.blue", "预选发光蓝", 92.0, 0.0, 255.0, 1.0, false, true, false),
        BUTTON_ROLL_DEGREES("render.button-roll-degrees", "按钮旋转", DEFAULT_BUTTON_ROLL_DEGREES, -180.0, 180.0, 5.0, false, false, false),
        BUTTON_DISTANCE("render.button-offset.distance", "按钮离桌距离", 2.10, 0.20, 4.0, 0.05, false, false, false),
        BUTTON_HEIGHT("render.button-offset.height", "按钮高度", 1.02, 0.20, 4.0, 0.05, false, false, false),
        CHAIR_ROTATION_DEGREES("render.chair-rotation-degrees", "椅子旋转角度", 0.0, -360.0, 360.0, 5.0, false, false, false),
        CHAIR_DISTANCE("render.layout.chair-distance", "椅子离桌距离", 2.35, 1.0, 8.0, 0.05, false, false, false),
        CHAIR_VISUAL_LATERAL("render.chair-visual-offset.lateral", "椅子左右偏移", 0.0, -2.0, 2.0, 0.02, false, false, false),
        CHAIR_VISUAL_VERTICAL("render.chair-visual-offset.vertical", "椅子上下偏移", -0.04, -2.0, 2.0, 0.02, false, false, false),
        CHAIR_HITBOX_LATERAL("render.chair-hitbox-offset.lateral", "椅子交互箱左右偏移", 0.0, -2.0, 2.0, 0.02, false, false, false),
        CHAIR_HITBOX_VERTICAL("render.chair-hitbox-offset.vertical", "椅子交互箱上下偏移", -0.18, -2.0, 2.0, 0.02, false, false, false),
        CHAIR_HITBOX_WIDTH("render.chair-hitbox.width", "椅子交互箱宽度", 0.22, 0.10, 3.0, 0.05, false, false, false),
        CHAIR_HITBOX_HEIGHT("render.chair-hitbox.height", "椅子交互箱高度", 0.30, 0.10, 3.0, 0.05, false, false, false),
        BUTTON_HITBOX_LATERAL("render.button-hitbox-offset.lateral", "按钮交互箱左右偏移", 0.0, -2.0, 2.0, 0.02, false, false, false),
        BUTTON_HITBOX_DEPTH("render.button-hitbox-offset.depth", "按钮交互箱前后偏移", 0.0, -2.0, 2.0, 0.02, false, false, false),
        BUTTON_HITBOX_VERTICAL("render.button-hitbox-offset.vertical", "按钮交互箱上下偏移", 0.02, -2.0, 2.0, 0.02, false, false, false),
        BUTTON_HITBOX_WIDTH("render.button-hitbox.width", "按钮交互箱宽度", 0.22, 0.05, 3.0, 0.05, false, false, false),
        BUTTON_HITBOX_HEIGHT("render.button-hitbox.height", "按钮交互箱高度", 0.34, 0.05, 3.0, 0.05, false, false, false),
        CARD_HITBOX_LATERAL("render.card-hitbox-offset.lateral", "扑克牌交互箱左右偏移", 0.0, -2.0, 2.0, 0.02, false, false, false),
        CARD_HITBOX_DEPTH("render.card-hitbox-offset.depth", "扑克牌交互箱前后偏移", 0.0, -2.0, 2.0, 0.02, false, false, false),
        CARD_HITBOX_VERTICAL("render.card-hitbox-offset.vertical", "扑克牌交互箱上下偏移", -0.45, -2.0, 2.0, 0.02, false, false, false),
        CARD_HITBOX_LENGTH("render.card-hitbox.length", "扑克牌交互箱长度", 0.30, 0.05, 3.0, 0.05, false, false, false),
        CARD_HITBOX_WIDTH("render.card-hitbox.width", "扑克牌交互箱宽度", 0.18, 0.05, 3.0, 0.05, false, false, false),
        CARD_HITBOX_HEIGHT("render.card-hitbox.height", "扑克牌交互箱高度", 0.62, 0.05, 3.0, 0.05, false, false, false),
        CARD_DEPTH_OFFSET("render.card-depth-offset", "手牌压层深度", 0.01, 0.01, 1.0, 0.01, false, false, false),
        STATUS_HEIGHT("render.status-height", "状态文字高度", 3.10, 0.0, 10.0, 0.05, false, false, false),
        PLAY_DETAIL_HEIGHT("render.play-detail-height", "上一手文字高度", 2.35, 0.0, 10.0, 0.05, false, false, false),
        PUBLIC_TRICK_HEIGHT("render.public-trick-height", "出牌预览高度", 1.55, 0.0, 10.0, 0.05, false, false, false),
        GLOBAL_HAND_LATERAL("render.private-hand-offset.lateral", "全局手牌横向偏移", 0.0, -5.0, 5.0, 0.02, false, false, false),
        GLOBAL_HAND_VERTICAL("render.private-hand-offset.vertical", "全局手牌竖向偏移", 0.0, -5.0, 5.0, 0.02, false, false, false),
        GLOBAL_HAND_DEPTH("render.private-hand-offset.depth", "全局手牌纵深偏移", 0.0, -5.0, 5.0, 0.02, false, false, false),
        LABELS_ENABLED("cards.hologram-labels.enabled", "全局点数标签", 1.0, 0.0, 1.0, 1.0, true, false, true),
        DUPLICATE_ONLY("cards.hologram-labels.duplicate-ranks-only", "仅重复牌显示标签", 0.0, 0.0, 1.0, 1.0, true, false, false),
        BGM_VOLUME("audio.bgm-volume", "背景音乐音量", 0.55, 0.0, 6.0, 0.05, false, false, false),
        EFFECT_VOLUME("audio.effect-volume", "音效音量", 1.0, 0.0, 6.0, 0.05, false, false, false),
        TURN_COUNTDOWN_SECONDS("actionbar.turn-countdown-seconds", "回合倒计时秒数", 20.0, 0.0, 120.0, 1.0, false, true, false),
        BOT_DELAY_MIN("bot.action-delay-min-ticks", "机器人最短思考", 10.0, 0.0, 200.0, 1.0, false, true, false),
        BOT_DELAY_MAX("bot.action-delay-max-ticks", "机器人最长思考", 30.0, 0.0, 400.0, 1.0, false, true, false),
        HINT_GROUP_LIMIT("hints.max-groups", "提示组数上限", 6.0, 1.0, 20.0, 1.0, false, true, false),
        JOIN_LABEL_HEIGHT("render.button-layout.join-label-height", "空位加入文字高度", 0.18, 0.0, 3.0, 0.02, false, false, false),
        JOIN_LABEL_SCALE("render.button-layout.join-label-scale", "空位加入文字大小", 0.46, 0.08, 4.0, 0.05, false, false, false),
        ACTION_LABEL_HEIGHT("render.button-layout.action-label-height", "按钮文字高度", 0.18, 0.0, 3.0, 0.02, false, false, false),
        ACTION_LABEL_SCALE("render.button-layout.action-label-scale", "按钮文字大小", 0.20, 0.08, 4.0, 0.05, false, false, false),
        BUTTON_FRONT_BASE_DISTANCE("render.button-layout.front-base-distance", "前座按钮基准距离", 1.40, 0.2, 5.0, 0.02, false, false, false),
        BUTTON_SIDE_BASE_DISTANCE("render.button-layout.side-base-distance", "侧座按钮基准距离", 1.72, 0.2, 5.0, 0.02, false, false, false),
        BUTTON_DISTANCE_FACTOR("render.button-layout.distance-factor", "按钮距离增量系数", 0.45, 0.0, 2.0, 0.01, false, false, false),
        BUTTON_SPACING("render.button-layout.spacing-scale", "按钮间距倍率", 1.0, 0.2, 2.5, 0.02, false, false, false),
        BUTTON_ARC_SMALL_ANGLE("render.button-layout.arc-angle-small", "三按钮弧度", 30.0, 0.0, 90.0, 1.0, false, false, false),
        BUTTON_ARC_LARGE_ANGLE("render.button-layout.arc-angle-large", "多按钮弧度", 42.0, 0.0, 120.0, 1.0, false, false, false),
        BUTTON_ARC_SMALL_RADIUS("render.button-layout.arc-radius-small", "三按钮半径", 0.70, 0.05, 3.0, 0.02, false, false, false),
        BUTTON_ARC_LARGE_RADIUS("render.button-layout.arc-radius-large", "多按钮半径", 0.86, 0.05, 3.0, 0.02, false, false, false);

        private final String path;
        private final String label;
        private final double defaultValue;
        private final double minValue;
        private final double maxValue;
        private final double step;
        private final boolean booleanSetting;
        private final boolean integerSetting;
        private final boolean defaultBoolean;

        AdminSetting(String path, String label, double defaultValue, double minValue, double maxValue, double step, boolean booleanSetting, boolean integerSetting, boolean defaultBoolean) {
            this.path = path;
            this.label = label;
            this.defaultValue = defaultValue;
            this.minValue = minValue;
            this.maxValue = maxValue;
            this.step = step;
            this.booleanSetting = booleanSetting;
            this.integerSetting = integerSetting;
            this.defaultBoolean = defaultBoolean;
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
            int normalized = Math.max(0, Math.min(values.length - 1, index));
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

        public static PlayActionKind fromPattern(dev.mumu.doudizhu.model.CardPattern pattern) {
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
    }

    private List<String> buildStartupInfoLines(CraftEngineBundleExporter.BundleExportResult exportResult, List<HookSnapshot> hooks, int consoleWidth) {
        int barWidth = consoleWidth >= 130 ? 20 : consoleWidth >= 104 ? 18 : 16;
        List<String> lines = new ArrayList<>();
        lines.add(startupInfoPart(0.18, "配置", "config.yml 已加载", barWidth));
        lines.add(startupInfoPart(0.38, "CraftEngine", describeHookCompact(findHook(hooks, "ce")), barWidth));
        lines.add(startupInfoPart(0.58, "PAPI", describeHookCompact(findHook(hooks, "papi")), barWidth));
        lines.add(startupInfoPart(0.68, "Vault", describeHookCompact(findHook(hooks, "vault")), barWidth));
        lines.add(startupInfoPart(0.78, "第三方 AI", aiStatusSummary(), barWidth));
        lines.add(startupInfoPart(0.88, "数据存储", databaseStatusSummary(), barWidth));
        lines.add(startupInfoPart(0.94, "资源同步", bundleSummaryPlain(exportResult), barWidth));
        lines.add(
            startupInfoPart(
                0.98,
                "已放置牌桌",
                "斗地主 " + (physicalTableManager == null ? 0 : physicalTableManager.placedTableCount()) + " 张",
                barWidth
            )
        );
        lines.add(startupInfoPart(1.0, "完成", "linmumua | MUZ v" + getDescription().getVersion(), barWidth));
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
            return new HookSnapshot("ce", "CraftEngine", HookState.MISSING, "未挂钩，不启用 CraftEngine 家具与 bundle 同步");
        }
        if (!craftEngine.isEnabled()) {
            return new HookSnapshot("ce", "CraftEngine", HookState.DISABLED, "未挂钩，不启用 CraftEngine 家具与 bundle 同步");
        }
        if (craftEngineFurnitureService != null && craftEngineFurnitureService.isAvailable()) {
            return new HookSnapshot("ce", "CraftEngine", HookState.HOOKED, "已启用家具放置与 bundle 同步");
        }
        return new HookSnapshot("ce", "CraftEngine", HookState.ERROR, "未挂钩，不启用 CraftEngine 家具与 bundle 同步");
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
        getLogger().warning("Vault 未挂钩: " + vaultHook.detail());
        if (vaultEconomyBridge != null) {
            getLogger().warning("  已注册 Provider: " + vaultEconomyBridge.availableProvidersDetail());
        }
        getLogger().warning("  已检测插件: "
            + "CMI=" + pluginState("CMI")
            + ", CMILib=" + pluginState("CMILib")
            + ", EzEconomy=" + pluginState("EzEconomy")
            + ", XConomy=" + pluginState("XConomy")
            + ", Vault=" + pluginState("Vault"));
        getLogger().warning("  Provider 顺序: " + String.join(" -> ", vaultPreferredProviderNames));
        if (isPluginEnabled("CMI")) {
            getLogger().warning("  提示: CMI 经济若要接入 Vault，通常还需要额外的 Vault 注入支持。");
        }
        getLogger().warning("  提示: 仅安装 Vault 不够，还需要一个真正的 Economy Provider。");
    }

    private boolean isPluginEnabled(String name) {
        return getServer().getPluginManager().isPluginEnabled(name);
    }

    private String pluginState(String name) {
        org.bukkit.plugin.Plugin plugin = getServer().getPluginManager().getPlugin(name);
        if (plugin == null) {
            return "missing";
        }
        return plugin.isEnabled() ? "enabled" : "disabled";
    }

    private double stageProgress(int stageIndex, int totalStages) {
        if (totalStages <= 0) {
            return 0.0;
        }
        double base = (double) stageIndex / (double) totalStages;
        return Math.max(0.05, Math.min(0.95, base + 0.02));
    }

    private double stageProgress(int stageIndex, int totalStages, int currentStep, int totalSteps) {
        if (totalStages <= 0 || totalSteps <= 0) {
            return stageProgress(stageIndex, totalStages);
        }
        double perStage = 1.0 / (double) totalStages;
        double base = stageIndex * perStage;
        double withinStage = Math.max(0.0, Math.min(1.0, (double) currentStep / (double) totalSteps));
        return Math.max(0.05, Math.min(0.95, base + withinStage * perStage));
    }

    private String rebuildDetail(String label, int count) {
        return count <= 0 ? "没有已放置的" + label : "即将重建 " + count + " 张" + label;
    }

    private String buildAsciiProgressBar(double progress, int width) {
        int normalizedWidth = Math.max(8, width);
        double clamped = Math.max(0.0, Math.min(1.0, progress));
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

    private String startupInfoLine(double progress, String label, String detail) {
        return buildAsciiProgressBar(progress, 20) + " " + label + ": " + detail;
    }

    private String describeHookForStartup(HookSnapshot hook) {
        if (hook == null) {
            return "未检测到";
        }
        return hook.state().label() + " | " + hook.detail();
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
            case EXPORTED -> "CraftEngine bundle 已同步 " + result.copiedEntries() + "/" + result.totalEntries();
            case UP_TO_DATE -> "CraftEngine bundle 已是最新";
            case SKIPPED -> "未启用 bundle 同步: " + result.detail();
            case FAILED -> "CraftEngine bundle 失败: " + result.detail();
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
        return component.decoration(TextDecoration.ITALIC, false);
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
        double verticalRatio = totalLines <= 1 ? 0.0 : Math.max(0.0, Math.min(1.0, (double) lineIndex / (double) (totalLines - 1)));
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
        double clamped = Math.max(0.0, Math.min(1.0, ratio));
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

    public record SettlementResult(
        double delta,
        double debt,
        double postBalance,
        boolean bankrupt,
        boolean insufficientForRoom,
        String unitLabel
    ) {
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
            bossBar.progress((float) Math.max(0.0, Math.min(1.0, progress)));
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
    }

    private void logShutdownDiagnostics() {
        int ddzTables = tableManager == null ? 0 : tableManager.getTables().size();
        getLogger().info("[MUZ/shutdown] version=" + getDescription().getVersion() + " doudizhuTables=" + ddzTables + " shuttingDown=true");
    }
}

