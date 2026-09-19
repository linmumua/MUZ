package linmumua.doudizhu.debug;

import linmumua.doudizhu.assets.HudOverlayLayout;
import linmumua.doudizhu.assets.PackAssets;
import linmumua.doudizhu.config.MuzYamlConfig;
import linmumua.doudizhu.game.TrickHudPreview;
import linmumua.doudizhu.model.CardRank;
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
    void onlyExposesTwentyTwoRuntimeHudFields() {
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
            "trick-hud.counter.scale",
            "trick-hud.counter.offset-down",
            "trick-hud.counter.gap",
            "trick-hud.counter.hide-exhausted",
            "trick-hud.counter.offset-x",
            "hotbar-hud.scale",
            "hotbar-hud.enabled",
            // hotbar 定位两键：offset-x 走 CE 负空格运行期即时生效，
            // offset-y 要落到字形 ascent 上（由 HotbarDebugOverlayWriter 写覆盖层）。
            "hotbar-hud.offset-x",
            "hotbar-hud.offset-y"
        ), keys);
        assertEquals(22, keys.size());
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

        assertTrue(exception.getMessage().contains("合法档位"));
    }

    @Test
    void snapshotRejectsAvatarScaleMissingFromSparseProfileInsteadOfSnapping() {
        MuzYamlConfig config = MuzYamlConfig.empty(tempDir.resolve("invalid-avatar-scale.yml"));
        int invalidScale = PackAssets.AVATAR_PIXEL_SCALE_TIERS[0] + 1;
        while (PackAssets.avatarPixelScaleTierOf(invalidScale) >= 0) {
            invalidScale++;
        }
        config.set("trick-hud.avatar-scale", invalidScale);

        DebugHudConfigController.FieldSpec field = DebugHudConfigController.fields().get("trick-hud.avatar-scale");
        DebugHudConfigController.ValidationException exception = assertThrows(
            DebugHudConfigController.ValidationException.class,
            () -> field.read(config));

        assertTrue(exception.getMessage().contains("avatar-scale=" + invalidScale));
        assertTrue(exception.getMessage().contains("当前资源包已生成的档位"));
    }

    @Test
    void continuousTrickOffsetsAreNumberFieldsAndKeepArbitraryIntegers() {
        for (String key : List.of(
            "trick-hud.offset-down",
            "trick-hud.avatar-offset-down",
            "trick-hud.counter.offset-down")) {
            DebugHudConfigController.FieldSpec field = DebugHudConfigController.fields().get(key);
            assertEquals("number", field.control(), key + " 必须是普通 number 控件");
            assertTrue(field.options().isEmpty(), key + " 不得暴露旧离散档位");
        }

        DebugHudConfigController.Patch patch = DebugHudConfigController.parsePatch(
            "{\"trick-hud.offset-down\":37,\"trick-hud.avatar-offset-down\":83,\"trick-hud.counter.offset-down\":-17}");
        assertEquals(37, patch.values().get("trick-hud.offset-down"));
        assertEquals(83, patch.values().get("trick-hud.avatar-offset-down"));
        assertEquals(-17, patch.values().get("trick-hud.counter.offset-down"));
    }

    @Test
    void continuousOffsetsRejectFractionalAndOverflowNumbers() {
        for (String json : List.of(
            "{\"trick-hud.offset-down\":37.5}",
            "{\"trick-hud.avatar-offset-down\":999999999999999999999}",
            "{\"trick-hud.counter.offset-down\":-128.1}")) {
            DebugHudConfigController.ValidationException exception = assertThrows(
                DebugHudConfigController.ValidationException.class,
                () -> DebugHudConfigController.parsePatch(json));
            assertTrue(exception.getMessage().contains("整数") || exception.getMessage().contains("32 位"));
        }
    }

    @Test
    void rejectsHotbarScaleOutsideGeneratedTiers() {
        int invalidScale = PackAssets.HOTBAR_SCALE_TIERS[0] + 1;
        DebugHudConfigController.ValidationException exception = assertThrows(
            DebugHudConfigController.ValidationException.class,
            () -> DebugHudConfigController.parsePatch("{\"hotbar-hud.scale\":" + invalidScale + "}"));

        assertTrue(exception.getMessage().contains("合法档位")
            || exception.getMessage().contains("已生成的档位"));
        assertTrue(exception.getMessage().contains("重新生成资源包"));
    }

    @Test
    void rejectsOffsetOutsideSelectedHotbarScaleBounds() {
        final int scale = PackAssets.HOTBAR_SCALE_TIERS[0];
        final int invalidOffset = HudOverlayLayout.minHotbarOffsetY(scale) - 1;
        final DebugHudConfigController.Patch invalidPatch = new DebugHudConfigController.Patch(
            java.util.Map.of("hotbar-hud.offset-y", invalidOffset));

        DebugHudConfigController.ValidationException exception = assertThrows(
            DebugHudConfigController.ValidationException.class,
            () -> DebugHudConfigController.validateHotbarPatch(scale, 0, invalidPatch));

        assertTrue(exception.getMessage().contains("scale=" + scale + "%"));
        assertTrue(exception.getMessage().contains("重新生成该档位资源包"));
    }

    @Test
    void acceptsOffsetWithinSelectedHotbarScaleBounds() {
        final int scale = PackAssets.HOTBAR_SCALE_TIERS[0];
        final int min = HudOverlayLayout.minHotbarOffsetY(scale);
        final DebugHudConfigController.Patch patch = DebugHudConfigController.parsePatch(
            "{\"hotbar-hud.scale\":" + scale + ",\"hotbar-hud.offset-y\":" + min + "}");
        DebugHudConfigController.validateHotbarPatch(scale, 0, patch);

        final DebugHudConfigController.Patch upperPatch = DebugHudConfigController.parsePatch(
            "{\"hotbar-hud.scale\":" + scale + ",\"hotbar-hud.offset-y\":" + HudOverlayLayout.MAX_TRICK_OFFSET + "}");
        DebugHudConfigController.validateHotbarPatch(scale, 0, upperPatch);
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

    @Test
    void hotbarGeometryPublishesThreeIndependentIconsAndVirtualSelection() {
        DebugHudConfigController.PreviewGeometry geometry = DebugHudConfigController.currentGeometry();
        assertEquals(PackAssets.HOTBAR_SCALE_TIERS.length, geometry.hotbars().size());
        for (DebugHudConfigController.PreviewGeometry.HotbarGeometry hotbar : geometry.hotbars()) {
            assertEquals(68, hotbar.width());
            assertEquals(22, hotbar.height());
            assertEquals(69, hotbar.advance());
            assertEquals(3, hotbar.slotCount());
            assertEquals(24, hotbar.slotStep());
            assertEquals(0, hotbar.selectStartX());
            assertEquals(3, hotbar.icons().size());
            for (int index = 0; index < 3; index++) {
                var icon = hotbar.icons().get(index);
                assertEquals(index, icon.index());
                assertEquals(20, icon.width());
                assertEquals(22, icon.height());
                assertEquals(24, icon.step());
                assertEquals(21, icon.advance());
                assertEquals(PackAssets.hotbarIconTexture(index, hotbar.scale()), icon.texture());
                assertTrue(icon.codepoint() > 0);
                assertTrue(icon.debugCodepoint() > 0);
            }
        }
    }

    @Test
    void avatarLayoutGeometryCoversEveryScaleAndOutlineCombination() {
        DebugHudConfigController.PreviewGeometry geometry = DebugHudConfigController.currentGeometry();

        assertEquals(PackAssets.AVATAR_PIXEL_SCALE_TIERS.length * 2,
            geometry.avatarLayouts().size());
        for (int scale : PackAssets.AVATAR_PIXEL_SCALE_TIERS) {
            final int selectedScale = scale;
            for (boolean outlined : new boolean[]{false, true}) {
                final boolean selectedOutlined = outlined;
                DebugHudConfigController.PreviewGeometry.AvatarLayoutGeometry layout = geometry.avatarLayouts().stream()
                    .filter(candidate -> candidate.middleScale() == selectedScale && candidate.outlined() == selectedOutlined)
                    .findFirst()
                    .orElseThrow();
                assertEquals(3, layout.slots().size());
                assertEquals(layout.slotWidth() * 3 + 2 * 0, layout.slots().stream()
                    .mapToInt(DebugHudConfigController.PreviewGeometry.AvatarSlotGeometry::slotWidth)
                    .sum(), "三槽必须使用同一服务端 slotWidth");
                assertEquals(scale, layout.slots().get(1).scale());
                assertEquals(4, layout.slots().get(0).scale());
                assertEquals(4, layout.slots().get(2).scale());
                assertTrue(layout.rowHeight() >= layout.slots().get(1).rowHeight());
                assertTrue(layout.slots().get(1).crowned());
            }
        }
    }

    @Test
    void counterGeometryUsesFixedLayeredCellAndCumulativeState() {
        DebugHudConfigController.PreviewGeometry geometry = DebugHudConfigController.currentGeometry();

        assertEquals(PackAssets.COUNTER_CELL_WIDTH, geometry.counterCellWidth());
        assertEquals(PackAssets.COUNTER_CELL_HEIGHT, geometry.counterCellHeight());
        assertEquals(PackAssets.COUNTER_CELL_ADVANCE, geometry.counterAdvance());
        assertEquals(PackAssets.COUNTER_LABEL_HEIGHT, geometry.counterLabelHeight());
        assertEquals(PackAssets.COUNTER_FRAME_HEIGHT, geometry.counterFrameHeight());
        assertEquals(PackAssets.COUNTER_DIGIT_HEIGHT, geometry.counterDigitHeight());
        assertEquals(PackAssets.COUNTER_LABEL_ASCENT, geometry.counterLabelAscent());
        assertEquals(PackAssets.COUNTER_FRAME_TOP_DELTA, geometry.counterFrameTopDelta());
        assertEquals(PackAssets.COUNTER_DIGIT_INSET, geometry.counterDigitInset());

        TrickHudPreview.CounterSnapshot fixture = TrickHudPreview.counterSnapshot();
        assertEquals(CardRank.values().length, geometry.counters().size());
        for (CardRank rank : CardRank.values()) {
            DebugHudConfigController.PreviewGeometry.CounterGeometry cell = geometry.counters().get(rank.ordinal());
            assertEquals(fixture.remaining(rank), cell.remaining(), rank + " 的 Web fixture 剩余数必须和调试棒一致");
            assertEquals(fixture.played(rank), cell.playedCount(), rank + " 的 Web fixture 已出数必须和调试棒一致");
            assertEquals(fixture.exhausted(rank), cell.exhausted(), rank + " 的 Web fixture 耗尽状态必须和调试棒一致");
        }
        assertEquals(0, geometry.counters().get(CardRank.THREE.ordinal()).playedCount(), "fixture 必须覆盖 0 已出");
        assertEquals(1, geometry.counters().get(CardRank.FOUR.ordinal()).playedCount(), "fixture 必须覆盖 1 已出");
        assertEquals(4, geometry.counters().get(CardRank.FIVE.ordinal()).playedCount(), "fixture 必须覆盖普通牌 4 已出");
        assertEquals(0, geometry.counters().get(CardRank.SMALL_JOKER.ordinal()).playedCount(), "fixture 必须覆盖小王未出");
        assertEquals(1, geometry.counters().get(CardRank.BIG_JOKER.ordinal()).playedCount(), "fixture 必须覆盖大王已出");
    }

    @Test
    void sampleCardsFixtureHasFiveCardsMatchingDebugStick() {
        // TrickHudPreview 公开的 5 张固定牌——调试棒与 Web 预览同源
        List<linmumua.doudizhu.model.DoudizhuCard> cards = TrickHudPreview.sampleCards();
        assertEquals(5, cards.size(), "必须恰好 5 张牌：10/J/Q/小王/大王");
        assertEquals(CardRank.TEN, cards.get(0).rank(), "第 1 张必须是 10");
        assertEquals(CardRank.JACK, cards.get(1).rank(), "第 2 张必须是 J");
        assertEquals(CardRank.QUEEN, cards.get(2).rank(), "第 3 张必须是 Q");
        assertEquals(CardRank.SMALL_JOKER, cards.get(3).rank(), "第 4 张必须是小王");
        assertEquals(CardRank.BIG_JOKER, cards.get(4).rank(), "第 5 张必须是大王");
    }

    @Test
    void sampleCardFixturesInGeometryMatchTrickHudPreview() {
        DebugHudConfigController.PreviewGeometry geometry = DebugHudConfigController.currentGeometry();
        List<DebugHudConfigController.PreviewGeometry.CardFixture> fixtures = geometry.sampleCards();

        assertEquals(5, fixtures.size(), "几何中的样例牌必须恰好 5 张");
        List<linmumua.doudizhu.model.DoudizhuCard> source = TrickHudPreview.sampleCards();
        for (int i = 0; i < 5; i++) {
            DebugHudConfigController.PreviewGeometry.CardFixture fixture = fixtures.get(i);
            assertEquals(source.get(i).displayLabel(), fixture.label(),
                "fixture[" + i + "].label 必须和 TrickHudPreview 同源");
            assertEquals(source.get(i).rank().label(), fixture.rank(),
                "fixture[" + i + "].rank 必须和 TrickHudPreview 同源");
        }
    }

    @Test
    void avatarLayoutsProvideAllScaleOutlineCombinations() {
        DebugHudConfigController.PreviewGeometry geometry = DebugHudConfigController.currentGeometry();
        List<DebugHudConfigController.PreviewGeometry.AvatarLayoutGeometry> layouts = geometry.avatarLayouts();

        int expectedScales = PackAssets.AVATAR_PIXEL_SCALE_TIERS.length;
        // 每个 scale 都有 outlined=true 和 outlined=false 两种组合
        assertEquals(expectedScales * 2, layouts.size(),
            "avatarLayouts 必须覆盖所有合法 scale × outline 组合");

        for (DebugHudConfigController.PreviewGeometry.AvatarLayoutGeometry layout : layouts) {
            assertEquals(3, layout.slots().size(), "每个 layout 必须有 3 个槽");
            assertTrue(layout.slotWidth() > 0, "slotWidth 必须为正数");
            assertTrue(layout.rowHeight() > 0, "rowHeight 必须为正数");
            // 三槽统一宽度
            for (DebugHudConfigController.PreviewGeometry.AvatarSlotGeometry slot : layout.slots()) {
                assertEquals(layout.slotWidth(), slot.slotWidth(),
                    "三个槽的 slotWidth 必须相同");
            }
            // 左右 side 固定 scale=4
            assertEquals(4, layout.slots().get(0).scale(), "左槽固定 scale=4");
            assertEquals(4, layout.slots().get(2).scale(), "右槽固定 scale=4");
            // 中间 scale 必须等于 layout 的 middleScale
            assertEquals(layout.middleScale(), layout.slots().get(1).scale(),
                "中间槽 scale 必须等于 layout.middleScale");
            // 中间必须有王冠
            assertTrue(layout.slots().get(1).crowned(), "中间槽必须标记 crowned");
        }
    }

    @Test
    void avatarSlotGeometryUsesPlayerHeadRendererAdvance() {
        // 验证 contentAdvance 与 PlayerHeadRenderer.advanceWidth 对齐
        // 使用 avatarLayouts 表（包含所有 scale/outline 组合），不调用私有方法
        DebugHudConfigController.PreviewGeometry geometry = DebugHudConfigController.currentGeometry();
        for (DebugHudConfigController.PreviewGeometry.AvatarLayoutGeometry layout : geometry.avatarLayouts()) {
            int scale = layout.middleScale();
            boolean outlined = layout.outlined();
            List<DebugHudConfigController.PreviewGeometry.AvatarSlotGeometry> slots = layout.slots();
            assertEquals(3, slots.size());
            // 中间槽
            int expectedMiddle = linmumua.doudizhu.assets.PlayerHeadRenderer.advanceWidth(scale, outlined);
            assertEquals(expectedMiddle, slots.get(1).contentAdvance(),
                "中间槽(scale=" + scale + ",outlined=" + outlined + ") contentAdvance 必须等于 PlayerHeadRenderer.advanceWidth");
            // 侧边 scale=4
            int expectedSide = linmumua.doudizhu.assets.PlayerHeadRenderer.advanceWidth(4, outlined);
            assertEquals(expectedSide, slots.get(0).contentAdvance(),
                "侧边槽(outlined=" + outlined + ") contentAdvance 必须等于 PlayerHeadRenderer.advanceWidth(4, " + outlined + ")");
        }
    }
}
