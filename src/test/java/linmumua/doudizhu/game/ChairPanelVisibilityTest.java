package linmumua.doudizhu.game;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

/**
 * 椅侧面板**出现时机**的判据测试：锁的是用户语义「持续出现时机沿现有对局/座位边界」。
 *
 * <p>失败条件（都是会让玩家看到不该出现的面板、或该出现时看不到的回归）：
 * <ul>
 *   <li>把离线玩家判成「应显示」——会对着一个不在线的人调 MuMenu 玩家级 API；</li>
 *   <li>大厅/结算阶段仍显示面板——面板属于对局内的 HUD，离开对局就该收口；</li>
 *   <li>把机器人判成应显示——机器人没有客户端，多开一块面板只是白占实体；</li>
 *   <li>任一开关关闭后仍显示——用户关掉功能必须真的不出现。</li>
 * </ul>
 */
class ChairPanelVisibilityTest {

    @Test
    void 对局内真人且开关全开时才显示() {
        assertFalse(GadgetPanelService.shouldHideChairPanels(
            true, true, true, true, true),
            "在线 + 两个开关都开 + 在桌 + 对局内真人 → 必须显示，不能隐藏");
    }

    @Test
    void 离线一律隐藏() {
        assertTrue(GadgetPanelService.shouldHideChairPanels(false, true, true, true, true),
            "离线必须隐藏：不能对不在线的人调 MuMenu 玩家级 API");
    }

    @Test
    void 椅侧面板总开关关闭时隐藏() {
        assertTrue(GadgetPanelService.shouldHideChairPanels(true, false, true, true, true),
            "chair-side-panels.enabled=false 必须隐藏");
    }

    @Test
    void 道具功能关闭时隐藏() {
        assertTrue(GadgetPanelService.shouldHideChairPanels(true, true, false, true, true),
            "桌内道具功能关闭时面板没有内容，必须隐藏");
    }

    @Test
    void 不在牌桌或不在对局阶段时隐藏() {
        assertTrue(GadgetPanelService.shouldHideChairPanels(true, true, true, false, true),
            "没有牌桌必须隐藏");
        assertTrue(GadgetPanelService.shouldHideChairPanels(true, true, true, true, false),
            "大厅/结算等非对局阶段必须隐藏（面板属于对局内 HUD）");
    }

    @Test
    void 机器人即使在对局内也隐藏() {
        // playingAndHuman 为 false 同时覆盖「非对局」与「机器人」两种情形。
        assertTrue(GadgetPanelService.shouldHideChairPanels(true, true, true, true, false),
            "机器人没有客户端，不应为它开面板");
    }

    /**
     * 同步动作判据：**「已开着」不等于「位置正确」**。
     *
     * <p>这条锁的是一个真实缺陷（实现过、已修）：若只看「两块都开着」就早退，
     * 玩家换到另一张对局中的桌后，面板会安静地停在**上一个桌的位置**——
     * 玩家看得见面板，但它在错的地方，且永远不会自己纠正。
     */
    @Test
    void 两块都开但锚点变了必须就地更新而不是当作无事发生() {
        assertEquals(GadgetPanelService.ChairSyncAction.NO_CHANGE,
            GadgetPanelService.decideSyncAction(true, true),
            "两块都开且锚点一致 → 不该做任何多余动作");
        assertEquals(GadgetPanelService.ChairSyncAction.UPDATE_IN_PLACE,
            GadgetPanelService.decideSyncAction(true, false),
            "两块都开但锚点已变（换桌/改布局）→ 必须就地更新，不能当作无事发生");
    }

    @Test
    void 面板不齐全时必须整组重开绝不留半块() {
        assertEquals(GadgetPanelService.ChairSyncAction.REOPEN,
            GadgetPanelService.decideSyncAction(false, false),
            "一块都没开 → 重开");
        assertEquals(GadgetPanelService.ChairSyncAction.REOPEN,
            GadgetPanelService.decideSyncAction(false, true),
            "缺一块（半块状态）→ 必须整组重开，不能只补另一块");
    }
}
