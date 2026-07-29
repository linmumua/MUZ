package linmumua.doudizhu.world;

import static org.junit.jupiter.api.Assertions.assertNotEquals;

import linmumua.doudizhu.world.PhysicalTableManager.ButtonAction;
import org.junit.jupiter.api.Test;

/**
 * 不同按钮的签名片段必须互不相同，否则阶段切换时按钮不会重建。
 *
 * modelId 在多个阶段里重名：LOBBY 的"准备"和 DOUBLING 的"加倍"都是 "ready"，
 * PLAYING 的"不要"和 DOUBLING 的"不加倍"都是 "pass"。图标删掉后 modelId 已无视觉作用，
 * 只作为签名的一部分参与重建判定。如果签名只看 modelId，重名的按钮会被误判为"没变"，
 * 阶段切换后玩家会看到上一阶段的按钮文字。
 *
 * 这里直接调用真实的 buttonSignatureFragment，不复刻拼接逻辑——复刻的话实现改坏了测试照样绿。
 */
class ActionSignatureDistinctnessTest {
    @Test
    void readyAndDoubleYesShareModelIdButDifferInSignature() {
        String lobbyReady = PhysicalTableManager.buttonSignatureFragment(
            "ready", "准备", ButtonAction.READY, -0.64);
        String doublingYes = PhysicalTableManager.buttonSignatureFragment(
            "ready", "加倍", ButtonAction.DOUBLE_YES, 0.40);

        assertNotEquals(lobbyReady, doublingYes, "同名 modelId 的按钮签名必须能区分开");
    }

    @Test
    void passTurnAndDoubleNoShareModelIdButDifferInSignature() {
        String playingPass = PhysicalTableManager.buttonSignatureFragment(
            "pass", "不要", ButtonAction.PASS_TURN, -0.24);
        String doublingNo = PhysicalTableManager.buttonSignatureFragment(
            "pass", "不加倍", ButtonAction.DOUBLE_NO, -0.40);

        assertNotEquals(playingPass, doublingNo, "同名 modelId 的按钮签名必须能区分开");
    }

    @Test
    void readyToggleChangesSignature() {
        // 准备/取消准备用同一个 modelId、同一个 action、同一个偏移，只有文字变。
        // 签名必须跟着变，否则玩家点了准备，文字不会更新。
        String notReady = PhysicalTableManager.buttonSignatureFragment(
            "ready", "准备", ButtonAction.READY, -0.64);
        String isReady = PhysicalTableManager.buttonSignatureFragment(
            "ready", "取消准备", ButtonAction.READY, -0.64);

        assertNotEquals(notReady, isReady, "准备状态切换必须改变签名");
    }

    @Test
    void biddingButtonsAllShareModelIdButDifferInSignature() {
        // 四个叫分按钮 modelId 全是 "bid"，只靠 label/action/offsetX 区分。
        String bid0 = PhysicalTableManager.buttonSignatureFragment("bid", "不叫", ButtonAction.BID_0, -0.96);
        String bid1 = PhysicalTableManager.buttonSignatureFragment("bid", "叫1分", ButtonAction.BID_1, -0.32);
        String bid2 = PhysicalTableManager.buttonSignatureFragment("bid", "叫2分", ButtonAction.BID_2, 0.32);
        String bid3 = PhysicalTableManager.buttonSignatureFragment("bid", "叫3分", ButtonAction.BID_3, 0.96);

        assertNotEquals(bid0, bid1);
        assertNotEquals(bid1, bid2);
        assertNotEquals(bid2, bid3);
    }
}
