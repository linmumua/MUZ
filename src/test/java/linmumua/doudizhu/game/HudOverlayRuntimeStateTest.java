package linmumua.doudizhu.game;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import linmumua.doudizhu.assets.HudResourceRequest;
import org.junit.jupiter.api.Test;

/** 运行态资源请求必须以整份已校验快照作为四层 HUD 的就绪边界。 */
class HudOverlayRuntimeStateTest {
    @Test
    void 未验证时所有匹配都失败() {
        HudOverlayRuntimeState state = new HudOverlayRuntimeState();

        assertNull(state.verifiedRequest());
        assertFalse(state.matchesTrick(0, 0, 0));
        assertFalse(state.matchesHotbar(0, 100));
    }

    @Test
    void 只接受完整请求的精确匹配并可清空() {
        HudOverlayRuntimeState state = new HudOverlayRuntimeState();
        HudResourceRequest request = new HudResourceRequest(12, 122, 122, 5, 100);

        state.markVerified(request);

        assertEquals(request, state.verifiedRequest());
        assertTrue(state.matchesTrick(request));
        assertTrue(state.matchesTrick(12, 122, 122));
        assertFalse(state.matchesTrick(13, 122, 122));
        assertTrue(state.matchesHotbar(5, 100));
        assertFalse(state.matchesHotbar(0, 100));
        assertFalse(state.matchesHotbar(5, 75));

        state.clear();
        assertNull(state.verifiedRequest());
        assertFalse(state.matchesTrick(request));
    }
}
