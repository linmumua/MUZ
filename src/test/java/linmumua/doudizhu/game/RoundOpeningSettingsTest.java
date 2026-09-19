package linmumua.doudizhu.game;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import linmumua.doudizhu.config.MuzYamlConfig;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

class RoundOpeningSettingsTest {
    @Test
    void rejectsInvalidRoundOpeningValuesAndReadsMessages() {
        MuzYamlConfig config = MuzYamlConfig.empty(Path.of("build", "tmp", "round-opening-settings-test.yml"));
        config.set("round-opening.batch-interval-ticks", 1);
        config.set("round-opening.flip-ticks", 9);
        config.set("round-opening.reveal-ticks", 0);
        config.set("round-opening.messages.revealing", "剩余 %seconds% 秒");

        assertThrows(IllegalArgumentException.class, () -> RoundOpeningSettings.from(config));

        config.set("round-opening.batch-interval-ticks", 3);
        config.set("round-opening.flip-ticks", 10);
        config.set("round-opening.reveal-ticks", 1);
        RoundOpeningSettings settings = RoundOpeningSettings.from(config);
        assertEquals(3, settings.batchIntervalTicks());
        assertEquals(10, settings.flipTicks());
        assertEquals(1, settings.revealTicks());
        assertEquals("剩余 %seconds% 秒", settings.messages().revealing());
    }

    @Test
    void acceptsConfiguredUpperBounds() {
        RoundOpeningSettings settings = new RoundOpeningSettings(
            200,
            200,
            1200,
            new RoundOpeningSettings.Messages(null, null, null, null, null, null, null, null, null)
        );

        assertEquals(200, settings.batchIntervalTicks());
        assertEquals(200, settings.flipTicks());
        assertEquals(1200, settings.revealTicks());
    }

    @Test
    void rejectsValuesOutsideSafeTimelineBounds() {
        RoundOpeningSettings.Messages messages =
            new RoundOpeningSettings.Messages(null, null, null, null, null, null, null, null, null);

        assertThrows(IllegalArgumentException.class, () -> new RoundOpeningSettings(2, 8, 1, messages));
        assertThrows(IllegalArgumentException.class, () -> new RoundOpeningSettings(201, 8, 1, messages));
        assertThrows(IllegalArgumentException.class, () -> new RoundOpeningSettings(3, 7, 1, messages));
        assertThrows(IllegalArgumentException.class, () -> new RoundOpeningSettings(3, 202, 1, messages));
        assertThrows(IllegalArgumentException.class, () -> new RoundOpeningSettings(3, 8, 0, messages));
        assertThrows(IllegalArgumentException.class, () -> new RoundOpeningSettings(3, 8, 1201, messages));
    }

    @Test
    void malformedOrFractionalTicksNeverSilentlyBecomeValidDefaults() {
        MuzYamlConfig config = MuzYamlConfig.empty(Path.of("build", "tmp", "round-opening-invalid-test.yml"));
        for (Object value : java.util.List.of("invalid", true, 4.5, 4294967300L, Double.NaN)) {
            config.set("round-opening.batch-interval-ticks", value);
            assertThrows(IllegalArgumentException.class, () -> RoundOpeningSettings.from(config),
                "非法时长必须拒绝，不能截断、溢出或回退默认值：" + value);
        }
    }

    @Test
    void keepsDefaultsWhenRoundOpeningSectionIsMissing() {
        RoundOpeningSettings settings = RoundOpeningSettings.from(
            MuzYamlConfig.empty(Path.of("build", "tmp", "round-opening-defaults-test.yml"))
        );

        assertEquals(4, settings.batchIntervalTicks());
        assertEquals(20, settings.flipTicks());
        assertEquals(60, settings.revealTicks());
    }
}
