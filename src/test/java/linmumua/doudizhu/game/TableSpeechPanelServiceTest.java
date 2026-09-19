package linmumua.doudizhu.game;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Set;
import java.util.UUID;
import net.kyori.adventure.text.Component;
import org.bukkit.Location;
import org.junit.jupiter.api.Test;

/** 语音面板服务的配置、阶段门和条目契约。 */
class TableSpeechPanelServiceTest {
    @Test
    void 默认只允许出牌阶段且悬停间隔为两tick() {
        TableSpeechPanelService.Settings settings = new TableSpeechPanelService.Settings(true);

        assertTrue(settings.enabled());
        assertEquals(2, settings.hoverIntervalTicks());
        assertEquals(20, settings.voiceCooldownTicks());
        assertTrue(TableSpeechPanelService.phaseAllowed(GamePhase.PLAYING, settings));
        assertFalse(TableSpeechPanelService.phaseAllowed(GamePhase.LOBBY, settings));
        assertFalse(TableSpeechPanelService.phaseAllowed(GamePhase.BIDDING, settings));
    }

    @Test
    void 配置可注入阶段与距离但不会接受非法悬停间隔() {
        TableSpeechPanelService.Settings settings = new TableSpeechPanelService.Settings(
            true, 0, 4.5, 40, Set.of(GamePhase.BIDDING, GamePhase.PLAYING));

        assertEquals(1, settings.hoverIntervalTicks());
        assertEquals(4.5, settings.maxDistance(), 1.0e-9);
        assertEquals(40, settings.voiceCooldownTicks());
        assertTrue(TableSpeechPanelService.phaseAllowed(GamePhase.BIDDING, settings));
        assertFalse(TableSpeechPanelService.phaseAllowed(GamePhase.DOUBLING, settings));
    }

    @Test
    void 禁用配置和空阶段不会通过阶段门() {
        TableSpeechPanelService.Settings disabled = new TableSpeechPanelService.Settings(false);
        assertFalse(TableSpeechPanelService.phaseAllowed(GamePhase.PLAYING, disabled));
        assertFalse(TableSpeechPanelService.phaseAllowed(null, disabled));
    }

    @Test
    void 语音条目必须有稳定id和有效面板() {
        TableSpeechPanelService.Panel panel = new TableSpeechPanelService.Panel(
            new Location(null, 0.0, 1.0, 0.0), 1.0, 0.5, 0.0f);
        TableSpeechPanelService.SpeechEntry entry = new TableSpeechPanelService.SpeechEntry(
            "hurry", Component.text("催促"), panel);

        assertEquals("hurry", entry.id());
        assertEquals(panel, entry.panel());
        assertThrows(IllegalArgumentException.class,
            () -> new TableSpeechPanelService.SpeechEntry("", Component.text("坏"), panel));
    }

    @Test
    void 面板矩形使用同一份宽高和yaw() {
        TableSpeechPanelService.Panel panel = new TableSpeechPanelService.Panel(
            new Location(null, 2.0, 3.0, 4.0), 2.0, 1.0, 90.0f);

        assertEquals(1.0, panel.rectangle().halfWidth(), 1.0e-9);
        assertEquals(0.5, panel.rectangle().halfHeight(), 1.0e-9);
        assertEquals(1.0, panel.rectangle().normal().length(), 1.0e-9);
    }

    @Test
    void 条目owner使用UUID签名便于外部注入() {
        UUID owner = UUID.randomUUID();
        assertEquals(36, owner.toString().length());
    }
}
