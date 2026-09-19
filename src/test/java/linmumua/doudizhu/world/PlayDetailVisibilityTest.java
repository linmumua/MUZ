package linmumua.doudizhu.world;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import linmumua.doudizhu.game.GamePhase;
import org.junit.jupiter.api.Test;

/**
 * 桌边动态浮空字可见性边界：开局后（非 LOBBY）对入座真人隐藏，避免浮空字挡住其低头看牌。
 *
 * 这条边界是用户明确要求的语义（“开局后看不到”，不是整局都隐藏），
 * 锁进单测防止有人把判据改成“只要入座就隐藏”或“只有 PLAYING 才隐藏”而回归。
 * 旁观者与机器人在调用方就被排除（seatedHuman=false），此处只校验纯决策函数。
 */
class PlayDetailVisibilityTest {
    @Test
    void lobbySeatedHumanStillSees() {
        assertFalse(
            PhysicalTableManager.playDetailHiddenForSeatedPlayer(GamePhase.LOBBY, true),
            "大厅阶段坐在桌上的玩家仍应看到桌边动态"
        );
    }

    @Test
    void afterRoundStartSeatedHumanIsHidden() {
        // 开局后的每个阶段都必须对入座真人隐藏。
        for (GamePhase phase : new GamePhase[] {
            GamePhase.DEALING,
            GamePhase.REVEALING,
            GamePhase.BIDDING,
            GamePhase.DOUBLING,
            GamePhase.PLAYING
        }) {
            assertTrue(
                PhysicalTableManager.playDetailHiddenForSeatedPlayer(phase, true),
                "开局后（" + phase + "）入座真人应隐藏桌边动态"
            );
        }
    }

    @Test
    void nonSeatedViewerAlwaysSees() {
        // 旁观者（seatedHuman=false）在任何阶段都应看到，包括开局后的阶段。
        for (GamePhase phase : GamePhase.values()) {
            assertFalse(
                PhysicalTableManager.playDetailHiddenForSeatedPlayer(phase, false),
                "旁观者在任何阶段（" + phase + "）都应看到桌边动态"
            );
        }
    }
}
