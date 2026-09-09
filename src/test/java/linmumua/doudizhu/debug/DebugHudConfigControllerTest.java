package linmumua.doudizhu.debug;

import linmumua.doudizhu.config.MuzYamlConfig;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

public class DebugHudConfigControllerTest {
    @TempDir
    Path tempDir;

    @Test
    void onlyExposesNineteenRuntimeHudFields() {
        Set<String> keys = new LinkedHashSet<>(DebugHudConfigController.fields().keySet());

        assertEquals(Set.of(
            "trick-hud.enabled",
            "trick-hud.avatar-scale",
            "trick-hud.avatar-gap",
            "trick-hud.card-step",
            "trick-hud.card-height",
            "trick-hud.offset-down",
            "trick-hud.avatar-offset-down",
            "trick-hud.offset-x",
            "trick-hud.card-offset-x",
            "trick-hud.avatar-offset-x",
            "trick-hud.avatar-outline.enabled",
            "trick-hud.avatar-outline.color",
            "trick-hud.counter.enabled",
            "trick-hud.counter.gap",
            "trick-hud.counter.hide-exhausted",
            "trick-hud.counter.offset-x",
            "hotbar-hud.enabled",
            // hotbar 定位两键：offset-x 走 CE 负空格运行期即时生效，
            // offset-y 要落到字形 ascent 上（由 HotbarDebugOverlayWriter 写覆盖层）。
            "hotbar-hud.offset-x",
            "hotbar-hud.offset-y"
        ), keys);
        assertEquals(19, keys.size());
    }

    @Test
    void rejectsUnknownPatchKeys() {
        DebugHudConfigController.ValidationException exception = assertThrows(
            DebugHudConfigController.ValidationException.class,
            () -> DebugHudConfigController.parsePatch("{\"render.scale\":2}")
        );

        assertTrue(exception.getMessage().contains("非白名单键"));
    }

    @Test
    void rejectsOutOfRangeNumber() {
        DebugHudConfigController.ValidationException exception = assertThrows(
            DebugHudConfigController.ValidationException.class,
            () -> DebugHudConfigController.parsePatch("{\"trick-hud.avatar-scale\":999}")
        );

        assertTrue(exception.getMessage().contains("超出范围"));
    }

    @Test
    void rejectsNumberThatIsNotAResourceTier() {
        DebugHudConfigController.ValidationException exception = assertThrows(
            DebugHudConfigController.ValidationException.class,
            () -> DebugHudConfigController.parsePatch("{\"trick-hud.offset-down\":51}")
        );

        assertTrue(exception.getMessage().contains("合法档位"));
    }

    @Test
    void validatesRgbAndArgbColors() {
        DebugHudConfigController.Patch patch = DebugHudConfigController.parsePatch("{\"trick-hud.avatar-outline.color\":\"#80aabbcc\"}");
        assertEquals("#80AABBCC", patch.values().get("trick-hud.avatar-outline.color"));

        DebugHudConfigController.ValidationException exception = assertThrows(
            DebugHudConfigController.ValidationException.class,
            () -> DebugHudConfigController.parsePatch("{\"trick-hud.avatar-outline.color\":\"#xyz\"}")
        );
        assertTrue(exception.getMessage().contains("#RRGGBB"));
    }

    @Test
    void incrementalPatchSaveKeepsUnsubmittedKeys() throws Exception {
        Path file = tempDir.resolve("config.yml");
        MuzYamlConfig config = MuzYamlConfig.empty(file);
        config.set("trick-hud.enabled", true);
        config.set("trick-hud.avatar-scale", 6);
        config.set("trick-hud.offset-down", 50);
        config.set("trick-hud.avatar-offset-down", 122);
        config.set("hotbar-hud.enabled", true);
        config.set("render.unrelated", 7);
        config.saveWithComments("trick-hud:\n  enabled: true\nhotbar-hud:\n  enabled: true\nrender:\n  unrelated: 0\n");

        DebugHudConfigController.Patch patch = DebugHudConfigController.parsePatch("{\"values\":{\"trick-hud.enabled\":false}}");
        for (Map.Entry<String, Object> entry : patch.values().entrySet()) {
            config.set(entry.getKey(), entry.getValue());
        }
        config.saveWithComments("trick-hud:\n  enabled: true\nhotbar-hud:\n  enabled: true\nrender:\n  unrelated: 0\n");

        MuzYamlConfig reloaded = new MuzYamlConfig(file);
        assertFalse(reloaded.getBoolean("trick-hud.enabled", true));
        assertTrue(reloaded.getBoolean("hotbar-hud.enabled", false));
        assertEquals(7, reloaded.getInt("render.unrelated", 0));
    }

    @Test
    void snapshotReportsAvatarOverlapWarning() {
        List<String> warnings = DebugHudConfigController.overlapWarnings(Map.of(
            "trick-hud.offset-down", 50,
            "trick-hud.avatar-offset-down", 60,
            "trick-hud.avatar-scale", 6
        ));

        assertEquals(1, warnings.size());
        assertTrue(warnings.get(0).contains("重叠"));
    }
}
