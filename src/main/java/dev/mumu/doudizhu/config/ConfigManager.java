package dev.mumu.doudizhu.config;

import dev.mumu.doudizhu.DoudizhuPlugin;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.configuration.file.YamlConfiguration;

import java.io.File;
import java.io.IOException;
import java.util.HashMap;
import java.util.Map;

/**
 * 配置管理器，负责管理插件的所有配置
 */
public class ConfigManager {
    private final DoudizhuPlugin plugin;
    private FileConfiguration config;
    private final Map<String, Object> configCache = new HashMap<>();
    
    // 配置组
    private RenderConfig renderConfig;
    private AIConfig aiConfig;
    private EconomyConfig economyConfig;
    private GameConfig gameConfig;
    private DatabaseConfig databaseConfig;
    private TexasConfig texasConfig;
    
    public ConfigManager(DoudizhuPlugin plugin) {
        this.plugin = plugin;
        reload();
    }
    
    /**
     * 重新加载配置
     */
    public void reload() {
        plugin.reloadConfig();
        config = plugin.getConfig();
        configCache.clear();
        loadAllConfigs();
    }
    
    /**
     * 保存配置
     */
    public void save() {
        plugin.saveConfig();
    }
    
    /**
     * 获取渲染配置
     */
    public RenderConfig getRenderConfig() {
        if (renderConfig == null) {
            renderConfig = new RenderConfig(config);
        }
        return renderConfig;
    }
    
    /**
     * 获取AI配置
     */
    public AIConfig getAIConfig() {
        if (aiConfig == null) {
            aiConfig = new AIConfig(config);
        }
        return aiConfig;
    }
    
    /**
     * 获取经济配置
     */
    public EconomyConfig getEconomyConfig() {
        if (economyConfig == null) {
            economyConfig = new EconomyConfig(config);
        }
        return economyConfig;
    }
    
    /**
     * 获取游戏配置
     */
    public GameConfig getGameConfig() {
        if (gameConfig == null) {
            gameConfig = new GameConfig(config);
        }
        return gameConfig;
    }
    
    /**
     * 获取数据库配置
     */
    public DatabaseConfig getDatabaseConfig() {
        if (databaseConfig == null) {
            databaseConfig = new DatabaseConfig(config);
        }
        return databaseConfig;
    }
    
    /**
     * 获取德州扑克配置
     */
    public TexasConfig getTexasConfig() {
        if (texasConfig == null) {
            texasConfig = new TexasConfig(config);
        }
        return texasConfig;
    }
    
    /**
     * 获取缓存配置值
     */
    @SuppressWarnings("unchecked")
    public <T> T getCached(String key, T defaultValue, Class<T> type) {
        String cacheKey = key + ":" + type.getSimpleName();
        if (configCache.containsKey(cacheKey)) {
            return (T) configCache.get(cacheKey);
        }
        
        T value = getConfigValue(key, defaultValue, type);
        configCache.put(cacheKey, value);
        return value;
    }
    
    /**
     * 从配置中获取值
     */
    @SuppressWarnings("unchecked")
    private <T> T getConfigValue(String path, T defaultValue, Class<T> type) {
        if (config == null) {
            return defaultValue;
        }
        
        if (config.contains(path)) {
            Object value = config.get(path);
            if (type.isInstance(value)) {
                return (T) value;
            }
        }
        return defaultValue;
    }
    
    /**
     * 加载所有配置
     */
    private void loadAllConfigs() {
        // 预加载所有配置
        getRenderConfig();
        getAIConfig();
        getEconomyConfig();
        getGameConfig();
        getDatabaseConfig();
        getTexasConfig();
    }
    
    /**
     * 渲染配置
     */
    public static class RenderConfig {
        private final FileConfiguration config;
        
        public RenderConfig(FileConfiguration config) {
            this.config = config;
        }
        
        public double getPrivateCardScale() {
            return config.getDouble("render.private-card-scale", 0.5);
        }
        
        public double getPublicCardScale() {
            return config.getDouble("render.public-card-scale", 0.58);
        }
        
        public double getCardHoverScale() {
            return config.getDouble("render.card-hover.scale", 1.08);
        }
        
        public double getCardHoverLift() {
            return config.getDouble("render.card-hover.lift", 0.06);
        }
        
        public double getButtonScale() {
            return config.getDouble("render.button-scale", 0.42);
        }
        
        public double getTableScale() {
            return config.getDouble("render.furniture-scale.table", 2.25);
        }
        
        public double getChairScale() {
            return config.getDouble("render.furniture-scale.chair", 1.35);
        }
        
        public boolean isHoverGlowEnabled() {
            return config.getBoolean("render.hover-glow.enabled", true);
        }
        
        public boolean isSelectedGlowEnabled() {
            return config.getBoolean("render.selected-glow.enabled", true);
        }
    }
    
    /**
     * AI配置
     */
    public static class AIConfig {
        private final FileConfiguration config;
        
        public AIConfig(FileConfiguration config) {
            this.config = config;
        }
        
        public boolean isEnabled() {
            return config.getBoolean("ai.deepseek.enabled", false);
        }
        
        public String getApiKey() {
            return config.getString("ai.deepseek.api-key", "");
        }
        
        public String getModel() {
            return config.getString("ai.deepseek.model", "deepseek-chat");
        }
        
        public String getBaseUrl() {
            return config.getString("ai.deepseek.url", "https://api.deepseek.com");
        }
        
        public int getTimeout() {
            return config.getInt("ai.deepseek.timeout-ms", 30000);
        }
    }
    
    /**
     * 经济配置
     */
    public static class EconomyConfig {
        private final FileConfiguration config;
        
        public EconomyConfig(FileConfiguration config) {
            this.config = config;
        }
        
        public boolean useChipSystem() {
            return config.getBoolean("economy.payment.use-chip", false);
        }
        
        public boolean isVaultEnabled() {
            return config.getBoolean("economy.vault.enabled", false);
        }
        
        public double getCurrencyPerPoint() {
            return config.getDouble("economy.doudizhu.currency-per-point", 1.0);
        }
        
        public double getCurrencyPerChip() {
            return config.getDouble("economy.texas.currency-per-chip", 1.0);
        }
    }
    
    /**
     * 游戏配置
     */
    public static class GameConfig {
        private final FileConfiguration config;
        
        public GameConfig(FileConfiguration config) {
            this.config = config;
        }
        
        public int getBotActionDelayMin() {
            return config.getInt("bot.action-delay-min-ticks", 10);
        }
        
        public int getBotActionDelayMax() {
            return config.getInt("bot.action-delay-max-ticks", 30);
        }
        
        public boolean isBotAIEnabled() {
            return config.getBoolean("bot.ai.enabled", false);
        }
        
        public int getBotAITimeout() {
            return config.getInt("bot.ai.timeout-ms", 5000);
        }
        
        public int getHintMaxGroups() {
            return config.getInt("hints.max-groups", 6);
        }
    }
    
    /**
     * 数据库配置
     */
    public static class DatabaseConfig {
        private final FileConfiguration config;
        
        public DatabaseConfig(FileConfiguration config) {
            this.config = config;
        }
        
        public String getType() {
            return config.getString("storage.sql.type", "sqlite");
        }
        
        public String getSqliteFile() {
            return config.getString("storage.sqlite.file", "storage/mumu-data.db");
        }
        
        public String getMySqlHost() {
            return config.getString("storage.mysql.host", "localhost");
        }
        
        public int getMySqlPort() {
            return config.getInt("storage.mysql.port", 3306);
        }
        
        public String getMySqlDatabase() {
            return config.getString("storage.mysql.database", "muz");
        }
    }
    
    /**
     * 德州扑克配置
     */
    public static class TexasConfig {
        private final FileConfiguration config;
        
        public TexasConfig(FileConfiguration config) {
            this.config = config;
        }
        
        public boolean shouldSpawnFurniture() {
            return config.getBoolean("texas.render.spawn-furniture", false);
        }
        
        public double getSeatDistance() {
            return config.getDouble("texas.layout.seat-distance", 3.1);
        }
        
        public double getCommunityCardHeight() {
            return config.getDouble("texas.cards.community-height", 1.18);
        }
        
        public double getHoleCardHeight() {
            return config.getDouble("texas.cards.hole-height", 1.18);
        }
    }
    
}
