package linmumua.doudizhu.game;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import sun.misc.Unsafe;

/**
 * 出牌 HUD 头像行那三个槽位取谁。
 *
 * <p>这是两行布局带来的【语义变更】：中间那个大头像取 {@code currentTurn}（该谁出牌），
 * 不是 {@code leadPlayer}（刚打出那手牌的人）。两者在真实对局里通常只差一位，
 * 所以肉眼验收极容易看错 —— 必须有测试钉住。
 *
 * <p>用 Unsafe 绕过构造器直接摆字段（沿用 {@code GameTableStartGuardTest} 的做法）：
 * GameTable 的构造器要 Bukkit 插件实例，单测里起不来，而这几个方法只依赖 seats 与
 * currentTurn 两个字段。
 */
class GameTableTrickHudSeatsTest {
    /**
     * 中间那个大头像必须是 currentTurn，不是 leadPlayer。
     *
     * <p>守的风险：改回 leadPlayer 的话，玩家看到的「该谁出牌」指向刚出过牌的那个人，
     * 正好差一位 —— 这种错位在实机上看着像「HUD 慢了一步」，而所有排版几何测试都全绿。
     * 这里故意让 leadPlayer 与 currentTurn 不同，混用就会失败。
     */
    @Test
    void 中间那个头像取currentTurn而不是leadPlayer() throws Exception {
        UUID first = UUID.randomUUID();
        UUID second = UUID.randomUUID();
        UUID third = UUID.randomUUID();
        GameTable table = tableWithSeats(List.of(first, second, third));
        // leadPlayer 是上一位（刚打出那手牌的人），currentTurn 是该行动的那位。
        setField(table, "leadPlayer", first);
        setField(table, "currentTurn", second);

        List<TrickHudService.Seat> trio = table.trickHudSeats();

        assertEquals(second, trio.get(1).playerId(), "中间的大头像必须是该出牌的人（currentTurn）");
        assertNotEquals(
            trio.get(1).playerId(),
            first,
            "中间的大头像取成了 leadPlayer —— 那是刚出过牌的人，差一位"
        );
        assertEquals(first, trio.get(0).playerId(), "左侧小头像是上一位");
        assertEquals(third, trio.get(2).playerId(), "右侧小头像是下一位");
    }

    /**
     * 三连必须以 currentTurn 为中心绕圈，绕到头要回卷。
     *
     * <p>守的风险：上一位是靠 {@code (index - 1 + size) % size} 算的。少写那个 {@code + size}
     * 时 Java 的负数取模仍为负（{@code -1 % 3 == -1}），直接拿去索引会抛
     * IndexOutOfBounds —— 而它只在 currentTurn 恰好是第一个座位时才触发，
     * 也就是每局的第一手牌，属于必现但只在特定时刻现形的崩溃。
     */
    @Test
    void 三连以currentTurn为中心并在座位表头尾回卷() throws Exception {
        UUID first = UUID.randomUUID();
        UUID second = UUID.randomUUID();
        UUID third = UUID.randomUUID();
        List<UUID> seats = List.of(first, second, third);

        for (int index = 0; index < seats.size(); index++) {
            GameTable table = tableWithSeats(seats);
            setField(table, "currentTurn", seats.get(index));

            List<TrickHudService.Seat> trio = table.trickHudSeats();

            assertEquals(
                seats.get((index + seats.size() - 1) % seats.size()),
                trio.get(0).playerId(),
                "座位 " + index + " 的上一位算错了（负数取模没回卷）"
            );
            assertEquals(seats.get(index), trio.get(1).playerId());
            assertEquals(
                seats.get((index + 1) % seats.size()),
                trio.get(2).playerId(),
                "座位 " + index + " 的下一位算错了"
            );
        }
    }

    /**
     * 人数不足 3 时两侧留空，中间仍是 currentTurn。
     *
     * <p>守的风险：座位只剩 2 人（有人中途离桌）时按取模绕圈会让某个小头像绕回
     * currentTurn 自己，屏幕上就出现同一个人的两个头像。留空比重复更清楚，
     * 而排版那边空槽照样占宽度，所以中间那个大头像不会跟着滑走。
     */
    @Test
    void 座位不足三人时两侧留空() throws Exception {
        UUID alone = UUID.randomUUID();
        UUID other = UUID.randomUUID();

        for (List<UUID> seats : List.of(List.of(alone), List.of(alone, other))) {
            GameTable table = tableWithSeats(seats);
            setField(table, "currentTurn", alone);

            List<TrickHudService.Seat> trio = table.trickHudSeats();

            assertEquals(alone, trio.get(1).playerId(), "中间仍该是 currentTurn");
            assertNull(trio.get(0).playerId(), seats.size() + " 人时上一位该留空，不能绕回自己");
            assertNull(trio.get(2).playerId(), seats.size() + " 人时下一位该留空，不能绕回自己");
        }
    }

    private static GameTable tableWithSeats(List<UUID> seats) throws Exception {
        Unsafe unsafe = unsafe();
        GameTable table = (GameTable) unsafe.allocateInstance(GameTable.class);
        setField(table, "seats", new ArrayList<>(seats));
        setField(table, "roles", new HashMap<UUID, PlayerRole>());
        setField(table, "botNames", new java.util.LinkedHashMap<UUID, String>());
        return table;
    }

    private static void setField(Object target, String fieldName, Object value) throws Exception {
        Field field = GameTable.class.getDeclaredField(fieldName);
        field.setAccessible(true);
        field.set(target, value);
    }

    private static Unsafe unsafe() throws Exception {
        Field field = Unsafe.class.getDeclaredField("theUnsafe");
        field.setAccessible(true);
        return (Unsafe) field.get(null);
    }
}
