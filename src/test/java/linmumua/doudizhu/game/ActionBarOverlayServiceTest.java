package linmumua.doudizhu.game;

import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

/** 普通 ActionBar 叠加职责必须独立于三道具 Hotbar 字形。 */
class ActionBarOverlayServiceTest {
    private static final Path SERVICE =
        Path.of("src/main/java/linmumua/doudizhu/game/ActionBarOverlayService.java");
    private static final Path TABLE =
        Path.of("src/main/java/linmumua/doudizhu/game/GameTable.java");

    @Test
    void 独立服务提供完整兼容API且不依赖Hotbar() throws IOException {
        String source = Files.readString(SERVICE);
        assertTrue(source.contains("ActionBarOverlayService(DoudizhuPlugin plugin)"));
        assertTrue(source.contains("showOverlay(Collection<UUID> players, Component message, int durationTicks)"));
        assertTrue(source.contains("showOverlay(Collection<UUID> players, Component message)"));
        assertTrue(source.contains("showOverlay(UUID playerId, Component message)"));
        assertTrue(source.contains("showOverlay(UUID playerId, Component message, int durationTicks)"));
        assertTrue(source.contains("sendActionBar(UUID playerId, Component message)"));
        assertTrue(source.contains("sendActionBar(Collection<UUID> playerIds, Component message)"));
        assertTrue(source.contains("clearOverlay(UUID playerId)"));
        assertTrue(source.contains("clearTable(GameTable table)"));
        assertTrue(source.contains("void stop()"));
        assertTrue(!source.contains("import linmumua.doudizhu.game.HotbarHudService"),
            "普通叠加服务不得导入三道具 Hotbar");
        assertTrue(!source.contains("CraftEngineOffsetService"),
            "普通叠加服务不得依赖 CraftEngine 偏移服务");
    }

    @Test
    void GameTable普通ActionBar路径统一走独立服务() throws IOException {
        String source = Files.readString(TABLE);
        assertTrue(source.contains("private final ActionBarOverlayService actionBarOverlay"));
        assertTrue(source.contains("dispatchActionBar(currentTurn, hint, 60)"));
        assertTrue(source.contains("dispatchActionBar(seat, actionBar, 60)"));
        assertTrue(source.contains("dispatchActionBar(playerId, bar, 60)"));
        assertTrue(source.contains("actionBarOverlay.clearTable(this)"));
        assertTrue(!source.contains("hotbarHud.showOverlay"), "GameTable 不得绕过普通 ActionBar 服务访问 Hotbar");
    }
}
