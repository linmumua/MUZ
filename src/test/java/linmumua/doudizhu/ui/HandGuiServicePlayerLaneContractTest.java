package linmumua.doudizhu.ui;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class HandGuiServicePlayerLaneContractTest {
    private static final Path SOURCE = Path.of("src/main/java/linmumua/doudizhu/ui/HandGuiService.java");

    @Test
    void playerOutputMustGoThroughPlayerLaneDispatcher() throws IOException {
        String source = Files.readString(SOURCE);

        assertTrue(source.contains("private void dispatchPlayer"));
        assertTrue(source.contains("output.runPlayer(playerId, action)"));
        assertTrue(source.contains("dispatchOpenInventory(player, inventory)"));
        assertFalse(source.contains("player.openInventory("));
        assertFalse(source.contains("viewer.openInventory("));
        assertFalse(source.contains("player.closeInventory("));
        assertFalse(source.contains("viewer.closeInventory("));
        assertFalse(source.contains("player.sendMessage("));
        assertFalse(source.contains("viewer.sendMessage("));
        assertFalse(source.contains("player.sendActionBar("));
        assertFalse(source.contains("player.playSound("));
    }
}
