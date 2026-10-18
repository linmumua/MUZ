package linmumua.doudizhu.ui.dialog;

import java.util.LinkedHashSet;
import java.util.Set;
import linmumua.doudizhu.DoudizhuPlugin;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * AI 文本输入 Dialog 的字段键与权限契约。
 *
 * <p>四个字段都属管理配置，必须 {muz.admin}；键必须稳定，供 dialog-admin 的 DDZ_AI 页面导航使用。
 */
class AiSettingsDialogScreenTest {

    @Test
    void 四个字段键稳定且互不相同() {
        Set<String> keys = new LinkedHashSet<>();
        keys.add(AiSettingsDialogScreen.AiField.URL.key());
        keys.add(AiSettingsDialogScreen.AiField.KEY.key());
        keys.add(AiSettingsDialogScreen.AiField.MODEL.key());
        keys.add(AiSettingsDialogScreen.AiField.SYSTEM_PROMPT.key());

        assertTrue(keys.contains("admin.ai.url"));
        assertTrue(keys.contains("admin.ai.key"));
        assertTrue(keys.contains("admin.ai.model"));
        assertTrue(keys.contains("admin.ai.system_prompt"));
        assertEquals(4, keys.size(), "AI 字段键不能重复");
    }

    @Test
    void 每屏键等于字段键且全部要求管理权限() {
        DoudizhuPlugin plugin = TestPlugins.newInstance();
        for (AiSettingsDialogScreen.AiField field : AiSettingsDialogScreen.AiField.values()) {
            AiSettingsDialogScreen screen = AiSettingsDialogScreen.forField(plugin, field);
            assertEquals(field.key(), screen.screenKey());
            assertEquals("muz.admin", screen.permission());
        }
    }

    @Test
    void 字段标签与提示非空() {
        for (AiSettingsDialogScreen.AiField field : AiSettingsDialogScreen.AiField.values()) {
            assertFalse(field.label().isBlank(), field + " 缺标签");
            assertFalse(field.hint().isBlank(), field + " 缺提示");
        }
    }

    @Test
    void 字符串键工厂大小写不敏感且未知键抛错() {
        DoudizhuPlugin plugin = TestPlugins.newInstance();
        assertEquals("admin.ai.url", AiSettingsDialogScreen.field(plugin, "URL").screenKey());
        assertEquals("admin.ai.key", AiSettingsDialogScreen.field(plugin, "key").screenKey());
        assertEquals("admin.ai.model", AiSettingsDialogScreen.field(plugin, "Model").screenKey());
        assertEquals("admin.ai.system_prompt", AiSettingsDialogScreen.field(plugin, "system_prompt").screenKey());
        assertThrows(IllegalArgumentException.class, () -> AiSettingsDialogScreen.field(plugin, "NOPE"));
        assertThrows(IllegalArgumentException.class, () -> AiSettingsDialogScreen.field(plugin, null));
    }
}
