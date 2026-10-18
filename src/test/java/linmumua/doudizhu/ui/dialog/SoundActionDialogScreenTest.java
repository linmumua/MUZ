package linmumua.doudizhu.ui.dialog;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import linmumua.doudizhu.DoudizhuPlugin;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 音效 / 动作 Dialog 的键、权限与纯解析逻辑契约。
 *
 * <p>「谁需要管理权限」与「每个屏的稳定键」是跨代理约定（dialog-admin 按这些键导航、core 按
 * {@code permission()} 复核），因此在这里逐条钉死，避免改名后静默失配。
 * 解析与预设是纯函数，直接断言；渲染需进服验证，不在单测范围。
 */
class SoundActionDialogScreenTest {

    @Test
    void 个人屏无权限而管理屏需要管理权限() {
        assertNull(SoundActionDialogScreen.Kind.PLAYER_SELECTION.permission());
        assertNull(SoundActionDialogScreen.Kind.PLAYER_ACTION_KIND.permission());
        assertNull(SoundActionDialogScreen.Kind.PLAYER_ACTION_PICKER.permission());
        for (SoundActionDialogScreen.Kind kind : List.of(
            SoundActionDialogScreen.Kind.ADMIN_SELECTION_EDITOR,
            SoundActionDialogScreen.Kind.ADMIN_ACTION_KIND,
            SoundActionDialogScreen.Kind.ADMIN_ACTION_EDITOR,
            SoundActionDialogScreen.Kind.ADMIN_COUNTDOWN,
            SoundActionDialogScreen.Kind.ADMIN_UNREADY,
            SoundActionDialogScreen.Kind.ADMIN_PLACEMENT
        )) {
            assertEquals("muz.admin", kind.permission(), kind + " 必须要求 muz.admin");
        }
    }

    @Test
    void 各屏键稳定且携带参数() {
        DoudizhuPlugin plugin = TestPlugins.newInstance();

        assertEquals("settings.selection", SoundActionDialogScreen.playerSelection(plugin).screenKey());
        assertEquals("settings.action", SoundActionDialogScreen.playerActionKindMenu(plugin).screenKey());
        assertEquals("settings.action.picker:bomb",
            SoundActionDialogScreen.playerActionPicker(plugin, DoudizhuPlugin.PlayActionKind.BOMB).screenKey());
        assertEquals("admin.sound.selection:2", SoundActionDialogScreen.selectionSoundEditor(plugin, 2).screenKey());
        assertEquals("admin.action.kind:joker_bomb",
            SoundActionDialogScreen.playActionKind(plugin, DoudizhuPlugin.PlayActionKind.JOKER_BOMB).screenKey());
        assertEquals("admin.action.editor:straight:1",
            SoundActionDialogScreen.actionEditor(plugin, DoudizhuPlugin.PlayActionKind.STRAIGHT, 1).screenKey());
        assertEquals("admin.sound.countdown", SoundActionDialogScreen.countdownSound(plugin).screenKey());
        assertEquals("admin.sound.unready", SoundActionDialogScreen.unreadyWarningSound(plugin).screenKey());
        assertEquals("admin.sound.placement", SoundActionDialogScreen.placementBlockedSound(plugin).screenKey());
    }

    @Test
    void 方案槽索引按四档钳制() {
        DoudizhuPlugin plugin = TestPlugins.newInstance();
        assertEquals("admin.sound.selection:3", SoundActionDialogScreen.selectionSoundEditor(plugin, 9).screenKey());
        assertEquals("admin.action.editor:bomb:3",
            SoundActionDialogScreen.actionEditor(plugin, DoudizhuPlugin.PlayActionKind.BOMB, 42).screenKey());
    }

    @Test
    void 所有工厂产出互不相同的键() {
        DoudizhuPlugin plugin = TestPlugins.newInstance();
        Set<String> keys = new LinkedHashSet<>();
        keys.add(SoundActionDialogScreen.playerSelection(plugin).screenKey());
        keys.add(SoundActionDialogScreen.playerActionKindMenu(plugin).screenKey());
        for (int index = 0; index < 4; index++) {
            keys.add(SoundActionDialogScreen.selectionSoundEditor(plugin, index).screenKey());
        }
        for (DoudizhuPlugin.PlayActionKind kind : DoudizhuPlugin.PlayActionKind.values()) {
            keys.add(SoundActionDialogScreen.playerActionPicker(plugin, kind).screenKey());
            keys.add(SoundActionDialogScreen.playActionKind(plugin, kind).screenKey());
            for (int index = 0; index < 4; index++) {
                keys.add(SoundActionDialogScreen.actionEditor(plugin, kind, index).screenKey());
            }
        }
        keys.add(SoundActionDialogScreen.countdownSound(plugin).screenKey());
        keys.add(SoundActionDialogScreen.unreadyWarningSound(plugin).screenKey());
        keys.add(SoundActionDialogScreen.placementBlockedSound(plugin).screenKey());

        // 2（个人）+ 4（选牌音效编辑，与牌型无关）+ 每牌型 6（1 个动作方案选择 + 1 个牌型子页 + 4 个动作编辑）× 6 + 3（音效）
        int expected = 2 + 4 + DoudizhuPlugin.PlayActionKind.values().length * 6 + 3;
        assertEquals(expected, keys.size(), "存在重复的 screenKey");
    }

    @Test
    void 选牌音效名翻译与旧实现等价() {
        assertEquals("清脆提示", SoundActionDialogScreen.selectionSoundLabel("minecraft:block.note_block.pling 0.35 1.18 0.92"));
        assertEquals("告示牌提示", SoundActionDialogScreen.selectionSoundLabel("minecraft:block.hanging_sign.place 0.35 1.12 0.92"));
        assertEquals("洞穴提示", SoundActionDialogScreen.selectionSoundLabel("minecraft:ambient.cave 0.25 1.00 0.90"));
        assertEquals("钟声提示", SoundActionDialogScreen.selectionSoundLabel("minecraft:block.note_block.bell 0.28 1.10 0.96"));
        assertEquals("金属提示", SoundActionDialogScreen.selectionSoundLabel("minecraft:block.iron_trapdoor.open 0.24 1.22 0.98"));
        assertEquals("按钮提示", SoundActionDialogScreen.selectionSoundLabel("minecraft:ui.button.click 0.30 1.06 0.92"));
        assertEquals("静音", SoundActionDialogScreen.selectionSoundLabel("minecraft:block.note_block.hat 0.00 1.00 1.00"));
        assertEquals("木质提示", SoundActionDialogScreen.selectionSoundLabel("minecraft:block.note_block.hat 0.45 1.00"));
        assertEquals("自定义音效", SoundActionDialogScreen.selectionSoundLabel("some:other.sound 1.0 1.0"));
        assertEquals("自定义音效", SoundActionDialogScreen.selectionSoundLabel(null));
        assertEquals("自定义音效", SoundActionDialogScreen.selectionSoundLabel("   "));
    }

    @Test
    void 方案显示名优先用翻译名否则退回自身标签() {
        assertEquals("清脆提示",
            SoundActionDialogScreen.selectionSoundDisplayLabel(
                new DoudizhuPlugin.OptionProfile("玩家自定义", "minecraft:block.note_block.pling 0.35 1.18 0.92")));
        assertEquals("自定义音效",
            SoundActionDialogScreen.selectionSoundDisplayLabel(
                new DoudizhuPlugin.OptionProfile("我的方案", "some:other.sound 1.0 1.0")));
    }

    @Test
    void 动作格式不对时抛中文异常() {
        IllegalArgumentException noSeparator = assertThrows(
            IllegalArgumentException.class,
            () -> SoundActionDialogScreen.parseActionProfileInput(null, "缺少分隔符"));
        assertTrue(noSeparator.getMessage().contains("显示名"));
    }

    @Test
    void 预设数量与规格与旧实现一致() {
        assertEquals(7, SoundActionDialogScreen.countdownPresets().size());
        assertEquals(7, SoundActionDialogScreen.selectionSoundPresets().size());
        assertEquals(7, SoundActionDialogScreen.playActionPresets().size());
        for (SoundActionDialogScreen.Preset preset : SoundActionDialogScreen.countdownPresets()) {
            assertNotEquals("", preset.spec().trim(), preset.title() + " 规格不能为空");
        }
        assertTrue(SoundActionDialogScreen.selectionSoundPresets().stream()
            .anyMatch(preset -> "静音".equals(preset.title())));
        assertTrue(SoundActionDialogScreen.playActionPresets().stream()
            .anyMatch(preset -> preset.spec().contains("type: none")));
    }
}
