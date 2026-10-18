package linmumua.doudizhu.ui.dialog;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.TextDecoration;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 个人设置 Dialog 的纯逻辑契约。
 *
 * <p>这里只锁「颜色目标映射」与「按钮文本不斜体」两件事——它们不依赖服务端，可以在本地直接跑。
 * 需要真实 Dialog 渲染的部分（原生 Dialog 的显示 / 滑动 / 输入控件）只能进服验证，不在单测范围。
 */
class PlayerSettingsDialogScreenTest {

    @Test
    void 主页键唯一且个人侧无权限门槛() {
        assertEquals("settings", PlayerSettingsDialogScreen.KEY);
    }

    @Test
    void 个人两色无权限而全局两色需要管理权限() {
        assertNull(PlayerSettingsDialogScreen.ColorTarget.PLAYER_PREVIEW.permission());
        assertNull(PlayerSettingsDialogScreen.ColorTarget.PLAYER_SELECTED.permission());
        assertEquals("muz.admin", PlayerSettingsDialogScreen.ColorTarget.GLOBAL_HOVER.permission());
        assertEquals("muz.admin", PlayerSettingsDialogScreen.ColorTarget.GLOBAL_SELECTED.permission());
    }

    @Test
    void 颜色目标键稳定且互不相同() {
        String preview = PlayerSettingsDialogScreen.ColorTarget.PLAYER_PREVIEW.key();
        String selected = PlayerSettingsDialogScreen.ColorTarget.PLAYER_SELECTED.key();
        String hover = PlayerSettingsDialogScreen.ColorTarget.GLOBAL_HOVER.key();
        String globalSelected = PlayerSettingsDialogScreen.ColorTarget.GLOBAL_SELECTED.key();

        assertEquals("settings.color.preview", preview);
        assertEquals("settings.color.selected", selected);
        assertEquals("admin.color.hover", hover);
        assertEquals("admin.color.selected", globalSelected);
        assertEquals(4, java.util.Set.of(preview, selected, hover, globalSelected).size());
    }

    @Test
    void 个人色与全局色区分正确() {
        assertTrue(PlayerSettingsDialogScreen.ColorTarget.PLAYER_PREVIEW.personal());
        assertTrue(PlayerSettingsDialogScreen.ColorTarget.PLAYER_SELECTED.personal());
        assertFalse(PlayerSettingsDialogScreen.ColorTarget.GLOBAL_HOVER.personal());
        assertFalse(PlayerSettingsDialogScreen.ColorTarget.GLOBAL_SELECTED.personal());
    }

    @Test
    void 按钮文本不继承斜体() {
        Component decorated = PlayerSettingsDialogScreen.noItalic(Component.text("预览色"));
        assertEquals(TextDecoration.State.FALSE, decorated.decoration(TextDecoration.ITALIC));
    }

    @Test
    void 字符串键工厂映射全局色并拒绝未知键() {
        linmumua.doudizhu.DoudizhuPlugin plugin = TestPlugins.newInstance();
        PlayerSettingsDialogScreen.ColorScreen hover =
            PlayerSettingsDialogScreen.globalColor(plugin, "HOVER_GLOW");
        PlayerSettingsDialogScreen.ColorScreen selected =
            PlayerSettingsDialogScreen.globalColor(plugin, "selected_glow");

        assertEquals("admin.color.hover", hover.screenKey());
        assertEquals("muz.admin", hover.permission());
        assertEquals("admin.color.selected", selected.screenKey());
        assertEquals("muz.admin", selected.permission());
        assertThrows(IllegalArgumentException.class, () -> PlayerSettingsDialogScreen.globalColor(plugin, "NOPE"));
    }
}
