package linmumua.doudizhu.game;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

import java.util.UUID;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.ObjectComponent;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.junit.jupiter.api.Test;

/**
 * 当前出牌 HUD 固定显示在 sendActionBar 所在的经验条正上方。
 */
class TableStatusViewsActionBarTest {
    private static final PlainTextComponentSerializer PLAIN = PlainTextComponentSerializer.plainText();
    private static final UUID TURN = UUID.fromString("c2b73fa8-6703-3d89-943d-7cc09d51fc9a");
    private static final UUID VIEWER = UUID.fromString("0d88ac75-df02-466d-a59c-19f8384e4732");

    /**
     * 行动栏身份只放纯文本，不许夹带原生头像对象。
     *
     * <p>屏幕中央那条头像 Title 已整体移除，头像不该顺势挪回行动栏：
     * 行动栏一行的宽度有限，塞进 PLAYER_HEAD 对象会把玩家名挤出可见区。
     */
    @Test
    void currentPlayIdentityStaysPlainTextWithoutHeadObject() {
        Component line = TableStatusViews.currentPlayIdentity(Component.text("NativeProbe"));

        assertEquals("当前出牌 · NativeProbe", PLAIN.serialize(line));
        assertFalse(line instanceof ObjectComponent, "行动栏不应携带头像对象");
        assertTrue(
            line.children().stream().noneMatch(ObjectComponent.class::isInstance),
            "行动栏的子节点里也不许夹带头像对象"
        );
    }

    @Test
    void currentPlayerSeesSelectedCountAndCountdown() {
        Component line = TableStatusViews.persistentActionBar(
            GamePhase.PLAYING,
            TURN,
            TURN,
            false,
            1,
            12,
            3,
            id -> Component.text("普通身份"),
            id -> Component.text("当前出牌 · NativeProbe"),
            Component.empty(),
            20
        );

        assertEquals("当前出牌 · NativeProbe · 已选 3 张 | 12 秒", PLAIN.serialize(line));
    }

    @Test
    void otherPlayersDoNotSeePrivateSelectionCount() {
        Component line = TableStatusViews.persistentActionBar(
            GamePhase.PLAYING,
            VIEWER,
            TURN,
            false,
            1,
            12,
            3,
            id -> Component.text("普通身份"),
            id -> Component.text("当前出牌 · NativeProbe"),
            Component.empty(),
            20
        );

        String plain = PLAIN.serialize(line);
        assertEquals("当前出牌 · NativeProbe | 12 秒", plain);
        assertFalse(plain.contains("已选"));
    }

    @Test
    void botThinkingKeepsTextIdentityAndSkipsPlayerHeadRenderer() {
        Component line = TableStatusViews.persistentActionBar(
            GamePhase.PLAYING,
            VIEWER,
            TURN,
            true,
            1,
            0,
            0,
            id -> Component.text("Bot-1"),
            id -> {
                fail("机器人不该调用真人 player_head 渲染器");
                return Component.empty();
            },
            Component.empty(),
            20
        );

        assertEquals("当前由 Bot-1 正在思考。", PLAIN.serialize(line));
        assertTrue(line.children().stream().noneMatch(ObjectComponent.class::isInstance));
    }
}
