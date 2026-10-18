package linmumua.doudizhu.ui.dialog;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.UUID;
import org.junit.jupiter.api.Test;

/**
 * 会话代次注册表的意图测试。
 *
 * <p>它守的是 Dialog 回调的核心安全前提：只有「当前仍打开的那一屏」的按钮回调才允许执行。
 * 因此重点断言——重新打开会立即使旧代次失效；退出清理由 clear 生效；TTL 到达后判据为假。
 */
class DialogSessionRegistryTest {
    private static final UUID PLAYER = UUID.fromString("00000000-0000-0000-0000-0000000000aa");

    @Test
    void openingAgainInvalidatesThePreviousGeneration() {
        DialogSessionRegistry registry = new DialogSessionRegistry();
        long first = registry.begin(PLAYER, "settings");
        assertTrue(registry.current(PLAYER, first), "刚打开的会话代次应当有效");

        long second = registry.begin(PLAYER, "admin.home");
        assertTrue(second != first, "重新打开必须是新代次");
        assertFalse(registry.current(PLAYER, first), "旧代次必须立即失效，旧回调不得再执行");
        assertTrue(registry.current(PLAYER, second));
        assertEquals("admin.home", registry.currentScreenKey(PLAYER));
    }

    /** 代次 0 与未知玩家一律无效：未开过会话的按钮回调不能被放行。 */
    @Test
    void unknownSessionAndZeroGenerationAreRejected() {
        DialogSessionRegistry registry = new DialogSessionRegistry();
        assertFalse(registry.current(PLAYER, 0L));
        assertFalse(registry.current(PLAYER, 12345L));
        assertEquals(0L, registry.currentGeneration(PLAYER));
    }

    @Test
    void clearEndsTheSession() {
        DialogSessionRegistry registry = new DialogSessionRegistry();
        long generation = registry.begin(PLAYER, "settings");
        registry.clear(PLAYER);
        assertFalse(registry.current(PLAYER, generation), "退出清理后回调必须失效");
        assertEquals(0, registry.size());
    }

    /** TTL 到达后判据为假；expireStale 把过期会话清掉。 */
    @Test
    void sessionsExpireAfterTtl() {
        long[] now = {0L};
        DialogSessionRegistry registry = new DialogSessionRegistry(() -> now[0], 1000L);
        long generation = registry.begin(PLAYER, "settings");
        assertTrue(registry.current(PLAYER, generation));

        now[0] = 999L;
        assertTrue(registry.current(PLAYER, generation), "TTL 内仍有效");

        now[0] = 1000L;
        assertFalse(registry.current(PLAYER, generation), "TTL 到达即失效");
        registry.expireStale();
        assertEquals(0, registry.size(), "过期会话必须被清理");
    }

    /** 同一毫秒内连续两次 begin 也必须得到不同代次（否则「关旧开新」会被误判为同一会话）。 */
    @Test
    void sameMillisecondBeginsStillDiffer() {
        DialogSessionRegistry registry = new DialogSessionRegistry(() -> 42L, 10_000L);
        long a = registry.begin(PLAYER, "a");
        long b = registry.begin(PLAYER, "b");
        assertTrue(a != b, "同毫秒两次打开不得撞代次");
        assertFalse(registry.current(PLAYER, a));
    }
}
