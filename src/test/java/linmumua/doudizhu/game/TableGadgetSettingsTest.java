package linmumua.doudizhu.game;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import linmumua.doudizhu.config.MuzYamlConfig;
import org.junit.jupiter.api.Test;

/** 牌桌道具配置的真实内存解析测试，不读写配置文件。 */
class TableGadgetSettingsTest {
    private static final Path SERVICE = Path.of(
        "src/main/java/linmumua/doudizhu/game/TableGadgetService.java");
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
        TableGadgetSettings settings = TableGadgetSettings.load(configWith(Map.of(
            "hotbar-hud.interaction.enabled", false,
            "hotbar-hud.interaction.range", 4.5,
            "hotbar-hud.interaction.cooldown-ticks", 20,
            "hotbar-hud.interaction.flight-ticks", 8,
            "hotbar-hud.interaction.water-ticks", 24,
            "hotbar-hud.interaction.max-active", 7
        )));

        assertFalse(settings.enabled());
        assertEquals(4.5, settings.range());
        assertEquals(20, settings.cooldownTicks());
        assertEquals(8, settings.flightTicks());
        assertEquals(24, settings.waterTicks());
        assertEquals(7, settings.maxActive());
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
    }

    @Test
    void realHotbarWrapUsesShortestDirection() {
        assertEquals(1, TableGadgetService.slotDelta(8, 0));
        assertEquals(-1, TableGadgetService.slotDelta(0, 8));
        assertEquals(4, TableGadgetService.slotDelta(0, 4));
        assertEquals(-4, TableGadgetService.slotDelta(0, 5));
    }

    @Test
    void unavailableHudDoesNotInterceptHeldChange() throws IOException {
        String source = Files.readString(SERVICE);
        int readiness = source.indexOf("private boolean isHotbarHudReady()");
        assertTrue(readiness >= 0, "道具资格必须经过 Hotbar HUD 就绪闸门");
        String readinessBody = source.substring(readiness, Math.min(source.length(), readiness + 520));
        assertTrue(readinessBody.contains("!hotbarHud.isRunning()"),
            "HUD 未启动时不得截获真实换槽");
        assertTrue(readinessBody.contains("offsetService != null && offsetService.isAvailable()"),
            "只有 CraftEngine 偏移可用时才允许道具交互");

        int held = source.indexOf("public void onHeldChange(PlayerItemHeldEvent event)");
        assertTrue(held >= 0, "必须保留真实换槽事件入口");
        String heldBody = source.substring(held, Math.min(source.length(), held + 360));
        assertTrue(heldBody.contains("if (!isEligibleActor(player))"),
            "换槽事件必须先经过资格判断，不能无条件取消");
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
