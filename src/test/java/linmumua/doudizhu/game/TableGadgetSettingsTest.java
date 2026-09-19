package linmumua.doudizhu.game;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import linmumua.doudizhu.config.MuzYamlConfig;
import org.junit.jupiter.api.Test;

/** 牌桌道具配置的真实内存解析测试，不读写配置文件。 */
class TableGadgetSettingsTest {
    private static final Path SERVICE = Path.of(
        "src/main/java/linmumua/doudizhu/game/TableGadgetService.java");
    private static final Path EFFECT_SERVICE = Path.of(
        "src/main/java/linmumua/doudizhu/game/TableGadgetEffectService.java");
    private static final Path CE_LISTENER = Path.of(
        "src/main/java/linmumua/doudizhu/listener/CraftEngineProtectionListener.java");

    private static MuzYamlConfig configWith(Map<String, Object> values) {
        MuzYamlConfig config = MuzYamlConfig.empty(Path.of("build", "tmp", "table-gadget-settings-test.yml"));
        values.forEach(config::set);
        return config;
    }

    @Test
    void missingInteractionUsesApprovedDefaults() {
        TableGadgetSettings settings = TableGadgetSettings.load(configWith(Map.of()));

        assertTrue(settings.enabled());
        assertEquals(6.0, settings.range());
        assertEquals(40, settings.cooldownTicks());
        assertEquals(10, settings.flightTicks());
        assertEquals(16, settings.waterTicks());
        assertEquals(32, settings.maxActive());
    }

    @Test
    void configuredValuesAreReadWithoutFileIo() {
        TableGadgetSettings settings = TableGadgetSettings.load(configWith(Map.ofEntries(
            Map.entry("table-gadgets.interaction.enabled", false),
            Map.entry("table-gadgets.interaction.range", 4.5),
            Map.entry("table-gadgets.interaction.cooldown-ticks", 20),
            Map.entry("table-gadgets.interaction.flight-ticks", 8),
            Map.entry("table-gadgets.interaction.water-ticks", 24),
            Map.entry("table-gadgets.interaction.max-active", 7),
            Map.entry("table-gadgets.gui.title", "道具箱"),
            Map.entry("table-gadgets.gui.bubble-name", "喊话"),
            Map.entry("table-gadgets.panel.enabled", true),
            Map.entry("table-gadgets.panel.forward-offset", 1.8),
            Map.entry("table-gadgets.panel.vertical-offset", -0.2),
            Map.entry("table-gadgets.panel.width", 2.0),
            Map.entry("table-gadgets.panel.row-height", 0.4),
            Map.entry("table-gadgets.panel.row-gap", 0.1),
            Map.entry("table-gadgets.panel.max-entries", 6),
            Map.entry("table-gadgets.panel.hover-interval-ticks", 3),
            Map.entry("table-gadgets.panel.voice-cooldown-ticks", 30),
            Map.entry("table-gadgets.voices.enabled", true),
            Map.entry("table-gadgets.voices.entries", List.of(Map.of(
                "id", "urge",
                "text", "快点出牌",
                "sound", "doudizhu.v22",
                "target", "current-turn",
                "volume", 0.8,
                "pitch", 1.1
            )))
        )));

        assertFalse(settings.enabled());
        assertEquals(4.5, settings.range());
        assertEquals(20, settings.cooldownTicks());
        assertEquals(8, settings.flightTicks());
        assertEquals(24, settings.waterTicks());
        assertEquals(7, settings.maxActive());
        assertEquals("道具箱", settings.gui().title());
        assertEquals("喊话", settings.gui().bubbleName());
        assertTrue(settings.panel().enabled());
        assertEquals(1.8, settings.panel().forwardOffset());
        assertEquals(-0.2, settings.panel().verticalOffset());
        assertEquals(2.0, settings.panel().width());
        assertEquals(0.4, settings.panel().rowHeight());
        assertEquals(0.1, settings.panel().rowGap());
        assertEquals(6, settings.panel().maxEntries());
        assertEquals(3, settings.panel().hoverIntervalTicks());
        assertEquals(30, settings.panel().voiceCooldownTicks());
        assertEquals(1, settings.voices().entries().size());
        assertEquals("current-turn", settings.voices().entries().get(0).target());
        assertEquals("doudizhu.v22", settings.voices().entries().get(0).sound());
    }

    @Test
    void oldInteractionKeysAreFallbackOnly() {
        TableGadgetSettings oldOnly = TableGadgetSettings.load(configWith(Map.of(
            "hotbar-hud.interaction.enabled", false,
            "hotbar-hud.interaction.range", 4.5
        )));
        assertFalse(oldOnly.enabled());
        assertEquals(4.5, oldOnly.range());

        TableGadgetSettings newWins = TableGadgetSettings.load(configWith(Map.of(
            "table-gadgets.interaction.enabled", true,
            "hotbar-hud.interaction.enabled", false
        )));
        assertTrue(newWins.enabled());
    }

    @Test
    void defaultVoicesContainCurrentTurnPrompt() {
        TableGadgetSettings settings = TableGadgetSettings.load(configWith(Map.of()));
        assertTrue(settings.voices().entries().size() >= 2);
        assertTrue(settings.voices().entries().stream().anyMatch(voice ->
            voice.target().equals("current-turn")));
    }

    @Test
    void outOfRangeValuesAreRejectedLoudly() {
        assertThrows(IllegalArgumentException.class, () -> TableGadgetSettings.load(configWith(
            Map.of("hotbar-hud.interaction.range", 6.01))));
        assertThrows(IllegalArgumentException.class, () -> TableGadgetSettings.load(configWith(
            Map.of("hotbar-hud.interaction.cooldown-ticks", 0))));
        assertThrows(IllegalArgumentException.class, () -> TableGadgetSettings.load(configWith(
            Map.of("hotbar-hud.interaction.flight-ticks", 41))));
        assertThrows(IllegalArgumentException.class, () -> TableGadgetSettings.load(configWith(
            Map.of("hotbar-hud.interaction.water-ticks", 0))));
        assertThrows(IllegalArgumentException.class, () -> TableGadgetSettings.load(configWith(
            Map.of("hotbar-hud.interaction.max-active", 65))));
    }

    @Test
    void wrongTypesAreNotSilentlyConverted() {
        assertThrows(IllegalArgumentException.class, () -> TableGadgetSettings.load(configWith(
            Map.of("hotbar-hud.interaction.enabled", "false"))));
        assertThrows(IllegalArgumentException.class, () -> TableGadgetSettings.load(configWith(
            Map.of("hotbar-hud.interaction.range", "6"))));
        assertThrows(IllegalArgumentException.class, () -> TableGadgetSettings.load(configWith(
            Map.of("table-gadgets.gui.title", 123))));
        assertThrows(IllegalArgumentException.class, () -> TableGadgetSettings.load(configWith(
            Map.of("table-gadgets.panel.width", "1.5"))));
        assertThrows(IllegalArgumentException.class, () -> TableGadgetSettings.load(configWith(
            Map.of("table-gadgets.voices.entries", "not-a-list"))));
        assertThrows(IllegalArgumentException.class, () -> TableGadgetSettings.load(configWith(
            Map.of("table-gadgets.voices.entries", List.of(Map.of(
                "id", "urge",
                "text", "快点出牌",
                "sound", "doudizhu.v22",
                "target", "previous",
                "volume", 1.0,
                "pitch", 1.0
            ))))));
    }

    @Test
    void newFieldsRejectOutOfRangeValues() {
        assertThrows(IllegalArgumentException.class, () -> TableGadgetSettings.load(configWith(
            Map.of("table-gadgets.panel.row-gap", 2.01))));
        assertThrows(IllegalArgumentException.class, () -> TableGadgetSettings.load(configWith(
            Map.of("table-gadgets.panel.max-entries", 0))));
        assertThrows(IllegalArgumentException.class, () -> TableGadgetSettings.load(configWith(
            Map.of("table-gadgets.panel.voice-cooldown-ticks", 201))));
        assertThrows(IllegalArgumentException.class, () -> TableGadgetSettings.load(configWith(
            Map.of("table-gadgets.voices.entries", List.of(Map.of(
                "id", "urge",
                "text", "快点出牌",
                "sound", "doudizhu.v22",
                "target", "current-turn",
                "volume", 2.01,
                "pitch", 1.0
            ))))));
    }

    @Test
    void itemStackSelectionApiReplacesHotbarAndCraftEngineGates() throws IOException {
        String source = Files.readString(SERVICE);
        String effects = Files.readString(EFFECT_SERVICE);
        assertTrue(source.contains("void select(UUID playerId, ItemStack item)"));
        assertTrue(source.contains("void clear(UUID playerId)"));
        assertTrue(source.contains("ItemStack current(UUID playerId)"));
        assertFalse(source.contains("PlayerItemHeldEvent"));
        assertFalse(source.contains("CraftEngineOffsetService"));
        assertFalse(source.contains("isHotbarHudReady"));
        assertTrue(source.contains("item.clone()"), "选择必须保存独立 ItemStack 快照");
        assertTrue(effects.contains("ItemStack item"));
        assertTrue(effects.contains("Material.WATER_BUCKET"));
        assertTrue(effects.contains("spawnDisplay(world, start, item.clone(), table)"));
        assertFalse(effects.contains("playGadgetSound"), "不应新增按固定枚举区分的落地音效");
        assertFalse(effects.contains("model.TableGadget"));
    }



    @Test
    void clearingTargetAlsoRemovesStaleActorTargetState() throws IOException {
        String source = Files.readString(SERVICE);
        int start = source.indexOf("private void releaseTargetCompletely(UUID targetId)");
        int end = source.indexOf("private void clearAllTargeting()", start);
        assertTrue(start >= 0 && end > start, "必须存在统一目标引用清理方法");
        String body = source.substring(start, end);
        assertTrue(body.contains("new ArrayList<>(targets.keySet())"),
            "清理目标时必须扫描所有 actor 状态，不能只清 glow 引用");
        assertTrue(body.contains("targetId.equals(state.targetId())")
                && body.contains("targets.remove(actorId)"),
            "目标离开/传送后必须删除 actor→target 旧状态，避免同 UUID 目标短路");
    }

    @Test
    void craftEngineFurnitureRightClickRoutesGadgetAfterHandCard() throws IOException {
        String source = Files.readString(CE_LISTENER);
        int start = source.indexOf("public void onFurnitureInteract(FurnitureInteractEvent event)");
        int end = source.indexOf("public void onFurnitureHit(FurnitureHitEvent event)", start);
        assertTrue(start >= 0 && end > start, "必须存在 CE 家具右键路由入口");
        String body = source.substring(start, end);
        int hand = body.indexOf("handleHandCardClickOnFurniture");
        int gadget = body.indexOf("tryUseTableGadget(player)");
        assertTrue(hand >= 0 && gadget > hand,
            "CE 家具右键必须保持手牌优先，再路由桌内道具");
        assertTrue(body.contains("event.setCancelled(true)"),
            "有效 CE 家具道具右键必须取消原始家具交互");
        assertTrue(source.contains("plugin.tableGadgets()"),
            "CE 家具道具路由必须使用主类服务，不得复制静态注册表");
    }
}
