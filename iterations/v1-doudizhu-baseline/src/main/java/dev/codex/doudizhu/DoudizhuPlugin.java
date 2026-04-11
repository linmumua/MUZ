package dev.codex.doudizhu;

import dev.codex.doudizhu.command.DoudizhuCommand;
import dev.codex.doudizhu.compat.CraftEngineBundleExporter;
import dev.codex.doudizhu.game.TableManager;
import dev.codex.doudizhu.listener.CraftEngineLifecycleListener;
import dev.codex.doudizhu.listener.HandGuiListener;
import dev.codex.doudizhu.listener.PlayerConnectionListener;
import dev.codex.doudizhu.listener.WorldTableInteractionListener;
import dev.codex.doudizhu.ui.HandGuiService;
import dev.codex.doudizhu.world.PhysicalTableManager;
import java.util.ArrayList;
import java.io.File;
import java.io.IOException;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import org.bukkit.NamespacedKey;
import org.bukkit.command.PluginCommand;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.inventory.ItemStack;
import org.bukkit.plugin.java.JavaPlugin;

public final class DoudizhuPlugin extends JavaPlugin {
    private static final String DEFAULT_TABLE_ITEM_MODEL = "muz:furniture/table_visual";
    private static final String DEFAULT_CHAIR_ITEM_MODEL = "muz:furniture/seat_chair";
    private static final String DEFAULT_TABLE_DISPLAY_NAME = "Dou Dizhu Table";
    private static final String DEFAULT_CHAIR_DISPLAY_NAME = "Dou Dizhu Chair";
    private static final double OLDER_BUTTON_DISTANCE = 1.10;
    private static final double LEGACY_BUTTON_DISTANCE = 1.45;
    private static final double DEFAULT_BUTTON_DISTANCE = 2.10;
    private static final double LEGACY_CARD_HITBOX_VERTICAL_OFFSET = 0.05;
    private static final double DEFAULT_CARD_HITBOX_VERTICAL_OFFSET = -0.45;
    private static final boolean DEFAULT_SELECTION_SOUND_ENABLED = true;
    private static final boolean DEFAULT_OPPONENT_PREVIEW_ENABLED = true;
    private static final String DEFAULT_SELECTION_SOUND_SPEC = "minecraft:block.note_block.pling 0.35 1.18 0.92";
    private static final String DEFAULT_COUNTDOWN_SOUND_SPEC = "minecraft:block.note_block.hat 0.45 1.00";
    private static final int PLAYER_OPTION_PROFILE_COUNT = 4;

    private TableManager tableManager;
    private HandGuiService handGuiService;
    private NamespacedKey cardIdKey;
    private NamespacedKey tableNameKey;
    private NamespacedKey interactionActionKey;
    private CraftEngineBundleExporter craftEngineBundleExporter;
    private PhysicalTableManager physicalTableManager;
    private boolean cardHologramLabelsEnabled;
    private boolean duplicateOnlyCardLabels;
    private double tableSpawnOffsetY;
    private float privateCardScale;
    private float publicCardScale;
    private float buttonScale;
    private float cardDepthOffset;
    private float handSpacing;
    private float publicTrickSpacing;
    private float buttonRollDegrees;
    private double buttonDistance;
    private double buttonHeight;
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
    private double cardHitboxWidth;
    private double cardHitboxHeight;
    private double statusHeight;
    private double playDetailHeight;
    private double publicTrickHeight;
    private float bgmVolume;
    private float effectVolume;
    private int turnCountdownSeconds;
    private String countdownSoundSpec;
    private int botActionDelayMinTicks;
    private int botActionDelayMaxTicks;
    private int hintGroupLimit;
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
    private final Map<UUID, PlayerHandOffsets> playerHandOffsets = new ConcurrentHashMap<>();
    private final List<OptionProfile> selectionSoundProfiles = new ArrayList<>();
    private final List<OptionProfile> playActionProfiles = new ArrayList<>();
    private File playerSettingsFile;
    private YamlConfiguration playerSettingsConfig;
    private File guiIconsFile;
    private YamlConfiguration guiIconsConfig;
    private volatile boolean shuttingDown;

    @Override
    public void onEnable() {
        saveDefaultConfig();
        ensureConfigIntegrity();
        loadRenderSettings();
        playerSettingsFile = new File(getDataFolder(), "player-settings.yml");
        loadPlayerSettings();
        saveResource("gui-icons.yml", false);
        guiIconsFile = new File(getDataFolder(), "gui-icons.yml");
        loadGuiIcons();
        cardIdKey = new NamespacedKey(this, "card-id");
        tableNameKey = new NamespacedKey(this, "table-name");
        interactionActionKey = new NamespacedKey(this, "interaction-action");
        handGuiService = new HandGuiService(this);
        tableManager = new TableManager(this);
        craftEngineBundleExporter = new CraftEngineBundleExporter(this);
        physicalTableManager = new PhysicalTableManager(this);

        DoudizhuCommand command = new DoudizhuCommand(this);
        PluginCommand pluginCommand = Objects.requireNonNull(getCommand("muz"), "muz command missing");
        pluginCommand.setExecutor(command);
        pluginCommand.setTabCompleter(command);

        getServer().getPluginManager().registerEvents(new PlayerConnectionListener(this), this);
        getServer().getPluginManager().registerEvents(new WorldTableInteractionListener(this), this);
        getServer().getPluginManager().registerEvents(new CraftEngineLifecycleListener(this), this);
        getServer().getPluginManager().registerEvents(new HandGuiListener(this), this);
        getServer().getScheduler().runTaskTimer(this, () -> physicalTableManager.tick(), 1L, 2L);
        getServer().getScheduler().runTaskTimer(this, () -> tableManager.tick(), 1L, 10L);
        craftEngineBundleExporter.exportIfAvailable();
        getLogger().info("MUZ enabled.");
    }

    @Override
    public void onDisable() {
        shuttingDown = true;
        savePlayerSettings();
        if (tableManager != null) {
            tableManager.shutdown();
        }
        if (physicalTableManager != null) {
            physicalTableManager.shutdown();
        }
    }

    public TableManager getTableManager() {
        return tableManager;
    }

    public HandGuiService getHandGuiService() {
        return handGuiService;
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

    public PhysicalTableManager getPhysicalTableManager() {
        return physicalTableManager;
    }

    public CraftEngineBundleExporter getCraftEngineBundleExporter() {
        return craftEngineBundleExporter;
    }

    public boolean isShuttingDown() {
        return shuttingDown;
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

    public List<OptionProfile> getSelectionSoundProfiles() {
        return List.copyOf(selectionSoundProfiles);
    }

    public OptionProfile getSelectionSoundProfile(int index) {
        return selectionSoundProfiles.get(clampProfileIndex(index));
    }

    public void setSelectionSoundProfileDefinition(int index, OptionProfile profile) {
        selectionSoundProfiles.set(clampProfileIndex(index), sanitizeSelectionSoundProfile(profile));
        saveOptionProfilesToConfig("player-options.selection-sound-profiles", selectionSoundProfiles);
    }

    public List<OptionProfile> getPlayActionProfiles() {
        return List.copyOf(playActionProfiles);
    }

    public OptionProfile getPlayActionProfile(int index) {
        return playActionProfiles.get(clampProfileIndex(index));
    }

    public void setPlayActionProfileDefinition(int index, OptionProfile profile) {
        playActionProfiles.set(clampProfileIndex(index), sanitizePlayActionProfile(profile));
        saveOptionProfilesToConfig("player-options.play-action-profiles", playActionProfiles);
    }

    public void resetPlayerVisualSettings(UUID playerId) {
        playerCardLabelSettings.remove(playerId);
        playerSelectionSoundSettings.remove(playerId);
        playerOpponentPreviewSettings.remove(playerId);
        playerSelectionSoundProfileSettings.remove(playerId);
        playerPlayActionProfileSettings.remove(playerId);
        playerHandOffsets.remove(playerId);
        savePlayerSettings();
    }

    public double getTableSpawnOffsetY() {
        return tableSpawnOffsetY;
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

    public double getCardHitboxHeight() {
        return cardHitboxHeight;
    }

    public double getPublicTrickHeight() {
        return publicTrickHeight;
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
        getConfig().set("actionbar.countdown-sound", countdownSoundSpec);
        saveConfig();
    }

    public SelectionSound selectionSoundFor(UUID playerId) {
        return parseSelectionSound(getSelectionSoundSpecFor(playerId));
    }

    public ConfiguredSound countdownSound() {
        return parseConfiguredSound(countdownSoundSpec);
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
        return 0.0;
    }

    public double getPlayerHandVerticalOffset(UUID playerId) {
        return 0.0;
    }

    public double getPlayerHandDepthOffset(UUID playerId) {
        return 0.0;
    }

    public double getPlayerHandSpacingOffset(UUID playerId) {
        return 0.0;
    }

    public double getPlayerPreviewScaleOffset(UUID playerId) {
        return 0.0;
    }

    public void adjustPlayerHandOffset(UUID playerId, HandOffsetAxis axis, double delta) {
        PlayerHandOffsets current = playerHandOffsets.getOrDefault(playerId, PlayerHandOffsets.ZERO);
        PlayerHandOffsets next = switch (axis) {
            case LATERAL -> new PlayerHandOffsets(current.lateral() + delta, current.vertical(), current.depth(), current.spacing(), current.previewScale());
            case VERTICAL -> new PlayerHandOffsets(current.lateral(), current.vertical() + delta, current.depth(), current.spacing(), current.previewScale());
            case DEPTH -> new PlayerHandOffsets(current.lateral(), current.vertical(), current.depth() + delta, current.spacing(), current.previewScale());
            case SPACING -> new PlayerHandOffsets(current.lateral(), current.vertical(), current.depth(), current.spacing() + delta, current.previewScale());
            case PREVIEW_SCALE -> new PlayerHandOffsets(current.lateral(), current.vertical(), current.depth(), current.spacing(), current.previewScale() + delta);
        };
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

    public String getTableDisplayName() {
        ItemStack configured = getConfiguredFurnitureItem(FurnitureType.TABLE);
        return configured == null ? tableDisplayName : configured.getType().name();
    }

    public String getChairItemModelId() {
        return chairItemModelId;
    }

    public String getChairDisplayName() {
        ItemStack configured = getConfiguredFurnitureItem(FurnitureType.CHAIR);
        return configured == null ? chairDisplayName : configured.getType().name();
    }

    public void reloadPluginState() {
        reloadVisualState(true);
    }

    public void setFurnitureDisplayItem(FurnitureType type, ItemStack itemStack) {
        String base = type.configBasePath();
        ItemStack copy = itemStack == null ? null : itemStack.clone();
        if (copy != null) {
            copy.setAmount(1);
        }
        getConfig().set(base + ".item-stack", copy);
        getConfig().set(base + ".namespace", null);
        getConfig().set(base + ".model-path", null);
        saveConfig();
        reloadVisualState(false);
    }

    public void resetFurnitureDisplayItem(FurnitureType type) {
        String base = type.configBasePath();
        getConfig().set(base + ".item-stack", null);
        getConfig().set(base + ".item-model", type.defaultItemModelId());
        getConfig().set(base + ".item-name", type.defaultDisplayName());
        saveConfig();
        reloadVisualState(false);
    }

    public ItemStack getConfiguredFurnitureItem(FurnitureType type) {
        ItemStack stored = getConfig().getItemStack(type.configBasePath() + ".item-stack");
        return stored == null ? null : stored.clone();
    }

    public void adjustAdminSetting(AdminSetting setting, boolean increase, int multiplier) {
        if (setting.booleanSetting()) {
            getConfig().set(setting.path(), !getConfig().getBoolean(setting.path(), setting.defaultBoolean()));
        } else if (setting.integerSetting()) {
            int current = getConfig().getInt(setting.path(), (int) setting.defaultValue());
            int delta = (int) setting.step() * Math.max(1, multiplier);
            int next = current + (increase ? delta : -delta);
            next = Math.max((int) setting.minValue(), Math.min((int) setting.maxValue(), next));
            getConfig().set(setting.path(), next);
        } else {
            double current = getConfig().getDouble(setting.path(), setting.defaultValue());
            double delta = setting.step() * Math.max(1, multiplier);
            double next = current + (increase ? delta : -delta);
            next = Math.max(setting.minValue(), Math.min(setting.maxValue(), next));
            getConfig().set(setting.path(), next);
        }
        saveConfig();
        reloadVisualState(false);
    }

    public String adminSettingValue(AdminSetting setting) {
        if (setting.booleanSetting()) {
            return getConfig().getBoolean(setting.path(), setting.defaultBoolean()) ? "开启" : "关闭";
        }
        if (setting.integerSetting()) {
            return String.valueOf(getConfig().getInt(setting.path(), (int) setting.defaultValue()));
        }
        return String.format(java.util.Locale.ROOT, "%.2f", getConfig().getDouble(setting.path(), setting.defaultValue()));
    }

    public String guiIcon(String path, String fallback) {
        if (guiIconsConfig == null) {
            return fallback;
        }
        String value = guiIconsConfig.getString(path);
        return value == null || value.isBlank() ? fallback : value.trim();
    }

    private void loadRenderSettings() {
        cardHologramLabelsEnabled = getConfig().getBoolean("cards.hologram-labels.enabled", true);
        duplicateOnlyCardLabels = getConfig().getBoolean("cards.hologram-labels.duplicate-ranks-only", false);
        tableSpawnOffsetY = getConfig().getDouble("table.spawn-offset-y", 0.18);
        privateCardScale = (float) getConfig().getDouble("render.private-card-scale", 0.50);
        publicCardScale = (float) getConfig().getDouble("render.public-trick-card-scale", 0.58);
        buttonScale = (float) getConfig().getDouble("render.button-scale", 0.42);
        cardDepthOffset = (float) getConfig().getDouble("render.card-depth-offset", 0.01);
        handSpacing = (float) getConfig().getDouble("render.hand-spacing", 0.21);
        publicTrickSpacing = (float) getConfig().getDouble("render.public-trick-spacing", 0.22);
        buttonRollDegrees = (float) getConfig().getDouble("render.button-roll-degrees", 90.0);
        buttonDistance = getConfig().getDouble("render.button-offset.distance", DEFAULT_BUTTON_DISTANCE);
        buttonHeight = getConfig().getDouble("render.button-offset.height", 1.02);
        chairVisualLateralOffset = getConfig().getDouble("render.chair-visual-offset.lateral", 0.0);
        chairVisualVerticalOffset = getConfig().getDouble("render.chair-visual-offset.vertical", -0.04);
        chairHitboxLateralOffset = getConfig().getDouble("render.chair-hitbox-offset.lateral", 0.0);
        chairHitboxVerticalOffset = getConfig().getDouble("render.chair-hitbox-offset.vertical", -0.18);
        chairHitboxWidth = getConfig().getDouble("render.chair-hitbox.width", 0.22);
        chairHitboxHeight = getConfig().getDouble("render.chair-hitbox.height", 0.30);
        buttonHitboxLateralOffset = getConfig().getDouble("render.button-hitbox-offset.lateral", 0.0);
        buttonHitboxDepthOffset = getConfig().getDouble("render.button-hitbox-offset.depth", 0.0);
        buttonHitboxVerticalOffset = getConfig().getDouble("render.button-hitbox-offset.vertical", 0.02);
        buttonHitboxWidth = getConfig().getDouble("render.button-hitbox.width", 0.22);
        buttonHitboxHeight = getConfig().getDouble("render.button-hitbox.height", 0.34);
        cardHitboxLateralOffset = getConfig().getDouble("render.card-hitbox-offset.lateral", 0.0);
        cardHitboxDepthOffset = getConfig().getDouble("render.card-hitbox-offset.depth", 0.0);
        cardHitboxVerticalOffset = getConfig().getDouble("render.card-hitbox-offset.vertical", DEFAULT_CARD_HITBOX_VERTICAL_OFFSET);
        cardHitboxWidth = getConfig().getDouble("render.card-hitbox.width", 0.18);
        cardHitboxHeight = getConfig().getDouble("render.card-hitbox.height", 0.62);
        statusHeight = getConfig().getDouble("render.status-height", 3.10);
        playDetailHeight = getConfig().getDouble("render.play-detail-height", 2.35);
        publicTrickHeight = getConfig().getDouble("render.public-trick-height", 1.55);
        globalPrivateHandLateralOffset = getConfig().getDouble("render.private-hand-offset.lateral", 0.0);
        globalPrivateHandVerticalOffset = getConfig().getDouble("render.private-hand-offset.vertical", 0.0);
        globalPrivateHandDepthOffset = getConfig().getDouble("render.private-hand-offset.depth", 0.0);
        bgmVolume = (float) getConfig().getDouble("audio.bgm-volume", 0.55);
        effectVolume = (float) getConfig().getDouble("audio.effect-volume", 1.0);
        turnCountdownSeconds = getConfig().getInt("actionbar.turn-countdown-seconds", 20);
        countdownSoundSpec = safeNormalizeCountdownSoundSpec(getConfig().getString("actionbar.countdown-sound", DEFAULT_COUNTDOWN_SOUND_SPEC));
        botActionDelayMinTicks = getConfig().getInt("bot.action-delay-min-ticks", getConfig().getInt("bot.action-delay-ticks", 20));
        botActionDelayMaxTicks = getConfig().getInt("bot.action-delay-max-ticks", getConfig().getInt("bot.action-delay-ticks", 20));
        if (botActionDelayMaxTicks < botActionDelayMinTicks) {
            int swapped = botActionDelayMinTicks;
            botActionDelayMinTicks = botActionDelayMaxTicks;
            botActionDelayMaxTicks = swapped;
        }
        hintGroupLimit = getConfig().getInt("hints.max-groups", 6);
        tableItemModelId = normalizeItemModelId(getConfig().getString("craftengine-items.table.item-model"), DEFAULT_TABLE_ITEM_MODEL);
        tableDisplayName = normalizeNonBlank(getConfig().getString("craftengine-items.table.item-name"), DEFAULT_TABLE_DISPLAY_NAME);
        chairItemModelId = normalizeItemModelId(getConfig().getString("craftengine-items.chair.item-model"), DEFAULT_CHAIR_ITEM_MODEL);
        chairDisplayName = normalizeNonBlank(getConfig().getString("craftengine-items.chair.item-name"), DEFAULT_CHAIR_DISPLAY_NAME);
        loadOptionProfiles();
    }

    private void ensureConfigIntegrity() {
        getConfig().options().copyDefaults(true);
        boolean changed = false;
        changed |= migrateLegacyFurnitureConfig(FurnitureType.TABLE);
        changed |= migrateLegacyFurnitureConfig(FurnitureType.CHAIR);
        changed |= migrateLegacyRenderConfig();
        changed |= ensureFurnitureConfig(FurnitureType.TABLE);
        changed |= ensureFurnitureConfig(FurnitureType.CHAIR);
        if (changed) {
            saveConfig();
        }
    }

    private boolean migrateLegacyRenderConfig() {
        boolean changed = false;
        if (
            getConfig().contains("render.button-offset.distance")
                && (
                    Math.abs(getConfig().getDouble("render.button-offset.distance") - OLDER_BUTTON_DISTANCE) < 0.0001
                        || Math.abs(getConfig().getDouble("render.button-offset.distance") - LEGACY_BUTTON_DISTANCE) < 0.0001
                )
        ) {
            getConfig().set("render.button-offset.distance", DEFAULT_BUTTON_DISTANCE);
            changed = true;
        }
        if (
            getConfig().contains("render.card-hitbox-offset.vertical")
                && Math.abs(getConfig().getDouble("render.card-hitbox-offset.vertical") - LEGACY_CARD_HITBOX_VERTICAL_OFFSET) < 0.0001
        ) {
            getConfig().set("render.card-hitbox-offset.vertical", DEFAULT_CARD_HITBOX_VERTICAL_OFFSET);
            changed = true;
        }
        if (getConfig().contains("bot.action-delay-ticks")) {
            if (!getConfig().contains("bot.action-delay-min-ticks")) {
                getConfig().set("bot.action-delay-min-ticks", getConfig().getInt("bot.action-delay-ticks", 20));
                changed = true;
            }
            if (!getConfig().contains("bot.action-delay-max-ticks")) {
                getConfig().set("bot.action-delay-max-ticks", getConfig().getInt("bot.action-delay-ticks", 20));
                changed = true;
            }
        }
        return changed;
    }

    private void loadGuiIcons() {
        if (guiIconsFile == null) {
            return;
        }
        guiIconsConfig = YamlConfiguration.loadConfiguration(guiIconsFile);
    }

    private void reloadVisualState(boolean exportBundle) {
        reloadConfig();
        ensureConfigIntegrity();
        loadRenderSettings();
        loadGuiIcons();
        if (exportBundle && craftEngineBundleExporter != null) {
            craftEngineBundleExporter.ensureBundleReady("manual-reload", true);
        }
        if (physicalTableManager != null) {
            physicalTableManager.rebuildAllTables();
        }
    }

    private boolean migrateLegacyFurnitureConfig(FurnitureType type) {
        String base = type.configBasePath();
        String itemModel = getConfig().getString(base + ".item-model");
        String namespace = getConfig().getString(base + ".namespace");
        String modelPath = getConfig().getString(base + ".model-path");
        boolean hasLegacy = namespace != null || modelPath != null;
        if (!isBlank(itemModel) || !hasLegacy) {
            return false;
        }
        if (!isBlank(namespace) && !isBlank(modelPath)) {
            String merged = namespace.trim() + ":" + modelPath.trim();
            getLogger().warning("检测到旧版 " + type.label() + " 配置键 namespace/model-path，已自动迁移为 item-model: " + merged);
            getConfig().set(base + ".item-model", merged);
        } else {
            getLogger().warning("检测到不完整的旧版 " + type.label() + " 配置，已回退为默认模型。");
            getConfig().set(base + ".item-model", type.defaultItemModelId());
        }
        getConfig().set(base + ".namespace", null);
        getConfig().set(base + ".model-path", null);
        return true;
    }

    private boolean ensureFurnitureConfig(FurnitureType type) {
        boolean changed = false;
        String base = type.configBasePath();
        if (getConfig().getItemStack(base + ".item-stack") != null) {
            return false;
        }
        String itemModel = getConfig().getString(base + ".item-model");
        if (!isBlank(itemModel)) {
            NamespacedKey parsed = NamespacedKey.fromString(itemModel.trim());
            if (parsed != null && parsed.getKey().startsWith("item/")) {
                String corrected = parsed.getNamespace() + ":" + parsed.getKey().substring("item/".length());
                getLogger().warning("配置里的 " + type.label() + " item-model 写成了模型路径 " + itemModel + "，已自动改为物品定义键 " + corrected);
                getConfig().set(base + ".item-model", corrected);
                itemModel = corrected;
                changed = true;
            }
        }
        if (isBlank(itemModel) || NamespacedKey.fromString(itemModel.trim()) == null) {
            getLogger().warning("配置里的 " + type.label() + " item-model 无效或为空，已改回默认值 " + type.defaultItemModelId());
            getConfig().set(base + ".item-model", type.defaultItemModelId());
            changed = true;
        }
        String itemName = getConfig().getString(base + ".item-name");
        if (isBlank(itemName)) {
            getLogger().warning("配置里的 " + type.label() + " item-name 为空，已改回默认显示名。");
            getConfig().set(base + ".item-name", type.defaultDisplayName());
            changed = true;
        }
        return changed;
    }

    private String normalizeItemModelId(String value, String fallback) {
        if (isBlank(value)) {
            return fallback;
        }
        NamespacedKey key = NamespacedKey.fromString(value.trim());
        return key == null ? fallback : key.asString();
    }

    private String normalizeNonBlank(String value, String fallback) {
        return isBlank(value) ? fallback : value.trim();
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
        playerHandOffsets.clear();
        if (playerSettingsFile == null) {
            return;
        }
        playerSettingsConfig = YamlConfiguration.loadConfiguration(playerSettingsFile);
        ConfigurationSection playersSection = playerSettingsConfig.getConfigurationSection("players");
        if (playersSection == null) {
            return;
        }
        for (String rawId : playersSection.getKeys(false)) {
            try {
                UUID playerId = UUID.fromString(rawId);
                ConfigurationSection section = playersSection.getConfigurationSection(rawId);
                if (section == null) {
                    continue;
                }
                if (section.contains("labels-enabled")) {
                    playerCardLabelSettings.put(playerId, section.getBoolean("labels-enabled"));
                }
                if (section.contains("selection-sound")) {
                    playerSelectionSoundSettings.put(playerId, section.getBoolean("selection-sound"));
                }
                if (section.contains("opponent-preview")) {
                    playerOpponentPreviewSettings.put(playerId, section.getBoolean("opponent-preview"));
                }
                if (section.contains("selection-sound-profile")) {
                    playerSelectionSoundProfileSettings.put(playerId, clampProfileIndex(section.getInt("selection-sound-profile", 0)));
                }
                if (section.contains("play-action-profile")) {
                    playerPlayActionProfileSettings.put(playerId, clampProfileIndex(section.getInt("play-action-profile", 0)));
                }
            } catch (IllegalArgumentException ignored) {
            }
        }
    }

    private void savePlayerSettings() {
        if (playerSettingsFile == null) {
            return;
        }
        YamlConfiguration configuration = new YamlConfiguration();
        Set<UUID> players = new LinkedHashSet<>();
        players.addAll(playerCardLabelSettings.keySet());
        players.addAll(playerSelectionSoundSettings.keySet());
        players.addAll(playerOpponentPreviewSettings.keySet());
        players.addAll(playerSelectionSoundProfileSettings.keySet());
        players.addAll(playerPlayActionProfileSettings.keySet());
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
        }
        try {
            getDataFolder().mkdirs();
            configuration.save(playerSettingsFile);
            playerSettingsConfig = configuration;
        } catch (IOException exception) {
            getLogger().warning("保存玩家微调设置失败: " + exception.getMessage());
        }
    }

    private void loadOptionProfiles() {
        selectionSoundProfiles.clear();
        playActionProfiles.clear();
        for (int index = 0; index < PLAYER_OPTION_PROFILE_COUNT; index++) {
            String selectionBase = "player-options.selection-sound-profiles.profile-" + (index + 1);
            selectionSoundProfiles.add(sanitizeSelectionSoundProfile(optionProfile(
                getConfig().getString(selectionBase + ".label", defaultSelectionSoundProfile(index).label()),
                getConfig().getString(selectionBase + ".spec", defaultSelectionSoundProfile(index).spec()),
                true
            )));
            String actionBase = "player-options.play-action-profiles.profile-" + (index + 1);
            playActionProfiles.add(sanitizePlayActionProfile(optionProfile(
                getConfig().getString(actionBase + ".label", defaultPlayActionProfile(index).label()),
                getConfig().getString(actionBase + ".spec", defaultPlayActionProfile(index).spec()),
                false
            )));
        }
    }

    private void saveOptionProfilesToConfig(String basePath, List<OptionProfile> profiles) {
        for (int index = 0; index < profiles.size(); index++) {
            OptionProfile profile = profiles.get(index);
            String path = basePath + ".profile-" + (index + 1);
            getConfig().set(path + ".label", profile.label());
            getConfig().set(path + ".spec", profile.spec());
        }
        saveConfig();
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
            case 1 -> new OptionProfile("告示牌提示", "minecraft:block.hanging_sign.place 0.35 1.12 0.92");
            case 2 -> new OptionProfile("洞穴提示", "minecraft:ambient.cave 0.25 1.00 0.90");
            default -> new OptionProfile("静音", "minecraft:block.note_block.hat 0.00 1.00 1.00");
        };
    }

    public OptionProfile defaultPlayActionProfile(int index) {
        return switch (clampProfileIndex(index)) {
            case 0 -> new OptionProfile("无操作", "type: none");
            case 1 -> new OptionProfile("聊天提示", "type: message; message: <yellow>你打出了 <arg:pattern></yellow>");
            case 2 -> new OptionProfile("动作栏提示", "type: actionbar; actionbar: <gold><arg:player.name></gold> 打出了 <yellow><arg:pattern></yellow>");
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
            builder.append(' ').append(String.format(java.util.Locale.ROOT, "%.2f", parsed));
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
        TABLE_SPAWN_OFFSET_Y("table.spawn-offset-y", "桌子高度", 0.18, -5.0, 5.0, 0.05, false, false, false),
        PRIVATE_CARD_SCALE("render.private-card-scale", "实体手牌大小", 0.50, 0.10, 5.0, 0.02, false, false, false),
        PUBLIC_TRICK_CARD_SCALE("render.public-trick-card-scale", "出牌预览大小", 0.58, 0.10, 5.0, 0.02, false, false, false),
        HAND_SPACING("render.hand-spacing", "默认手牌间距", 0.21, 0.02, 2.0, 0.01, false, false, false),
        PUBLIC_TRICK_SPACING("render.public-trick-spacing", "出牌预览间距", 0.22, 0.02, 2.0, 0.01, false, false, false),
        BUTTON_SCALE("render.button-scale", "按钮大小", 0.42, 0.05, 3.0, 0.02, false, false, false),
        BUTTON_ROLL_DEGREES("render.button-roll-degrees", "按钮旋转", 90.0, -180.0, 180.0, 5.0, false, false, false),
        BUTTON_DISTANCE("render.button-offset.distance", "按钮离桌距离", 2.10, 0.20, 4.0, 0.05, false, false, false),
        BUTTON_HEIGHT("render.button-offset.height", "按钮高度", 1.02, 0.20, 4.0, 0.05, false, false, false),
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
        CARD_HITBOX_WIDTH("render.card-hitbox.width", "扑克牌交互箱宽度", 0.18, 0.05, 3.0, 0.05, false, false, false),
        CARD_HITBOX_HEIGHT("render.card-hitbox.height", "扑克牌交互箱高度", 0.62, 0.05, 3.0, 0.05, false, false, false),
        CARD_DEPTH_OFFSET("render.card-depth-offset", "手牌压层深度", 0.01, 0.0, 1.0, 0.01, false, false, false),
        STATUS_HEIGHT("render.status-height", "状态文字高度", 3.10, 0.0, 10.0, 0.05, false, false, false),
        PLAY_DETAIL_HEIGHT("render.play-detail-height", "上一手文字高度", 2.35, 0.0, 10.0, 0.05, false, false, false),
        PUBLIC_TRICK_HEIGHT("render.public-trick-height", "出牌预览高度", 1.55, 0.0, 10.0, 0.05, false, false, false),
        GLOBAL_HAND_LATERAL("render.private-hand-offset.lateral", "全局手牌横向偏移", 0.0, -5.0, 5.0, 0.02, false, false, false),
        GLOBAL_HAND_VERTICAL("render.private-hand-offset.vertical", "全局手牌竖向偏移", 0.0, -5.0, 5.0, 0.02, false, false, false),
        GLOBAL_HAND_DEPTH("render.private-hand-offset.depth", "全局手牌纵深偏移", 0.0, -5.0, 5.0, 0.02, false, false, false),
        LABELS_ENABLED("cards.hologram-labels.enabled", "全局点数标签", 1.0, 0.0, 1.0, 1.0, true, false, true),
        DUPLICATE_ONLY("cards.hologram-labels.duplicate-ranks-only", "仅重复牌显示标签", 0.0, 0.0, 1.0, 1.0, true, false, false),
        BGM_VOLUME("audio.bgm-volume", "背景音乐音量", 0.55, 0.0, 2.0, 0.05, false, false, false),
        EFFECT_VOLUME("audio.effect-volume", "音效音量", 1.0, 0.0, 2.0, 0.05, false, false, false),
        TURN_COUNTDOWN_SECONDS("actionbar.turn-countdown-seconds", "回合倒计时秒数", 20.0, 0.0, 120.0, 1.0, false, true, false),
        BOT_DELAY_MIN("bot.action-delay-min-ticks", "机器人最短思考", 10.0, 0.0, 200.0, 1.0, false, true, false),
        BOT_DELAY_MAX("bot.action-delay-max-ticks", "机器人最长思考", 30.0, 0.0, 400.0, 1.0, false, true, false),
        HINT_GROUP_LIMIT("hints.max-groups", "提示组数上限", 6.0, 1.0, 20.0, 1.0, false, true, false);

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

        private boolean isZero() {
            return Math.abs(lateral) < EPSILON
                && Math.abs(vertical) < EPSILON
                && Math.abs(depth) < EPSILON
                && Math.abs(spacing) < EPSILON
                && Math.abs(previewScale) < EPSILON;
        }
    }

    public record SelectionSound(String key, float volume, float selectedPitch, float deselectedPitch) {
    }

    public record ConfiguredSound(String key, float volume, float pitch) {
    }

    public record OptionProfile(String label, String spec) {
    }
}
