package linmumua.doudizhu.listener;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.UUID;
import linmumua.doudizhu.listener.WorldTableInteractionListener.ConsumedButtonClick;
import org.junit.jupiter.api.Test;

/**
 * 按钮点击必须在 INTERACT_AT 与 INTERACT 两路都处理，且同一次右键只执行一次。
 *
 * <p>为什么两路都要接：按钮判定框是 {@code setResponsive(true)} 的 Interaction 实体，
 * 客户端把 interactAt 视为已消费，后续 INTERACT 包不再发出。只挂 INTERACT 那一路时
 * 按钮完全没反应——这是已在测试服复现的故障。
 *
 * <p>为什么必须去重：{@code handleInteraction} 内部没有去重，两路都到达时执行两遍的后果已取证：
 * READY / DOUBLE_* 这类 toggle 切两次等于没切（表现为「点了没反应」），
 * BID_n / JOIN / PLAY_SELECTED 第二遍抛异常变成红字报错。
 */
class ActionButtonClickDedupTest {
    private static final UUID BUTTON = UUID.randomUUID();
    private static final UUID OTHER_BUTTON = UUID.randomUUID();

    @Test
    void theSecondPacketOfOneRightClickIsRecognisedAsDuplicate() {
        // AT 已经消费过这次点击，紧随其后的 INTERACT 落在同一 tick，必须跳过。
        ConsumedButtonClick consumedByAt = new ConsumedButtonClick(BUTTON, 100);

        assertTrue(
            WorldTableInteractionListener.isDuplicateButtonClick(consumedByAt, BUTTON, 100),
            "同一实体同一 tick 的第二个包属于同一次右键，必须去重，否则按钮动作执行两遍"
        );
    }

    @Test
    void clickingTheSameButtonAgainLaterIsNotADuplicate() {
        // 玩家隔了几 tick 再点同一个按钮，是真实的第二次操作，必须放行。
        ConsumedButtonClick earlier = new ConsumedButtonClick(BUTTON, 100);

        assertFalse(
            WorldTableInteractionListener.isDuplicateButtonClick(earlier, BUTTON, 103),
            "不同 tick 是玩家真的点了第二次，不能当成重复投递丢掉"
        );
    }

    @Test
    void clickingADifferentButtonInTheSameTickIsNotADuplicate() {
        // 同一 tick 内命中另一个按钮实体，属于不同的操作对象，不能误判为重复。
        ConsumedButtonClick consumedByAt = new ConsumedButtonClick(BUTTON, 100);

        assertFalse(
            WorldTableInteractionListener.isDuplicateButtonClick(consumedByAt, OTHER_BUTTON, 100),
            "不同实体即使同 tick 也是不同的点击目标，必须各自执行"
        );
    }

    @Test
    void theFirstClickOfASessionIsNeverADuplicate() {
        assertFalse(
            WorldTableInteractionListener.isDuplicateButtonClick(null, BUTTON, 100),
            "玩家还没点过任何按钮时不存在重复投递"
        );
    }
}
