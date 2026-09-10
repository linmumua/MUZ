package linmumua.doudizhu.game;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import linmumua.doudizhu.assets.PackAssets;
import linmumua.doudizhu.model.CardRank;
import org.junit.jupiter.api.Test;
import sun.misc.Unsafe;

/**
 * 记牌行从剩余张数摊成各格的那一步。
 *
 * <p>用 Unsafe 绕过构造器（沿用 {@code GameTableTrickHudSeatsTest} 的做法）：
 * TrickHudService 的构造器要插件实例，而 counterCells 只做纯数据变换。
 */
class TrickHudCounterCellsTest {
    /**
     * 每个有读数的点数各占一格，顺序跟着 CardRank 的声明顺序。
     *
     * <p>顺序固定才能让玩家形成肌肉记忆——记牌器每帧重画，格子位置跳动就没法用。
     *
     * <p>失败条件：改成按 map 迭代顺序输出（HashMap 不保证顺序）。
     */
    @Test
    void 每个点数一格且顺序跟着声明顺序() throws Exception {
        Map<CardRank, Integer> counts = fullDeck();

        List<TrickHudView.CounterCell> cells = counterCells(counts, counts, true);

        assertEquals(CardRank.values().length, cells.size(), "固定 15 格，每个点数都要有一格");
        assertEquals(TrickHudView.CounterCell.ADVANCE_PIXELS, cells.get(0).advancePixels());
        assertEquals(3, cells.get(0).layers().size(), "每格必须有标签、框、数字三层");
        assertTrue(cells.get(0).layers().get(0).contains(
            PackAssets.counterRankChar(CardRank.values()[0], 0)), "第一层必须是点数标签");
        assertTrue(cells.get(0).layers().get(1).contains(
            PackAssets.counterFrameChar(false, 0)), "第二层必须是通用矩形框");
        assertTrue(cells.get(0).layers().get(2).contains(
            PackAssets.counterDigitChar(4, 0)), "第三层必须是数字");
    }

    /**
     * 出完的点数在 hide 开启时只留空格子，宽度照旧。
     *
     * <p>【宽度必须保留】：格子宽度归零会让后面所有格子左移，整行重排。
     * 记牌器每出一张牌就可能触发一次重排，看起来像 HUD 在抽动。
     *
     * <p>失败条件：空格子的 advancePixels 被清成 0，或者干脆不产出这一格。
     */
    @Test
    void 出完的点数留空格子但宽度不变() throws Exception {
        Map<CardRank, Integer> played = new EnumMap<>(CardRank.class);
        Map<CardRank, Integer> remaining = new EnumMap<>(CardRank.class);
        played.put(CardRank.THREE, 4);
        remaining.put(CardRank.THREE, 0);
        remaining.put(CardRank.FOUR, 4);

        List<TrickHudView.CounterCell> cells = counterCells(played, remaining, true);

        assertEquals(CardRank.values().length, cells.size(), "耗尽点数也必须保留在固定 15 格中");
        assertTrue(cells.get(0).isEmpty(), "出完的点数不画内容");
        assertEquals(TrickHudView.CounterCell.ADVANCE_PIXELS, cells.get(0).advancePixels(),
            "空格子仍必须占据固定 34 像素，否则后面的格子会左移");
        assertFalse(cells.get(1).isEmpty(), "未耗尽点数必须继续绘制");
        for (TrickHudView.CounterCell cell : cells) {
            assertEquals(34, cell.advancePixels(), "每一格净前进量必须严格为 34");
        }
    }

    /**
     * hide 关闭时，出完的点数仍显示三层 glyph，并使用耗尽框与 dim 颜色。
     */
    @Test
    void hide关闭时出完的点数仍显示并置灰() throws Exception {
        Map<CardRank, Integer> played = new EnumMap<>(CardRank.class);
        Map<CardRank, Integer> remaining = new EnumMap<>(CardRank.class);
        played.put(CardRank.THREE, 4);
        remaining.put(CardRank.THREE, 0);

        List<TrickHudView.CounterCell> cells = counterCells(played, remaining, false);

        TrickHudView.CounterCell cell = cells.get(0);
        assertFalse(cell.isEmpty(), "hide 关闭时出完的点数也要画出来");
        assertEquals(3, cell.layers().size(), "显示格必须保留标签、框、数字三层");
        assertTrue(cell.text().contains("dark_gray"),
            "出完的点数应用 dark_gray（dim 效果）渲染");
        assertTrue(cell.text().contains(PackAssets.counterFrameChar(true, 0)),
            "出完的点数必须使用耗尽框");
        assertTrue(cell.text().contains(PackAssets.counterRankChar(CardRank.THREE, 0)),
            "耗尽格仍必须保留点数标签");
        assertTrue(cell.text().contains(PackAssets.counterDigitChar(4, 0)),
            "正式记牌器显示累计已出数量，而不是剩余数量");
    }

    /** 空读数返回空列表，让 View 直接不画这一行。 */
    @Test
    void 没有读数时返回空列表() throws Exception {
        Map<CardRank, Integer> empty = new EnumMap<>(CardRank.class);
        assertTrue(counterCells(empty, empty, true).isEmpty(),
            "牌局没开始就不该有记牌行，空列表让 View 跳过整行");
    }

    /**
     * 记牌器每格固定 34 像素；点数标签、数字宽度变化不能改变格子位置。
     */
    @Test
    void 每格净前进量固定为34像素() throws Exception {
        Map<CardRank, Integer> played = new EnumMap<>(CardRank.class);
        Map<CardRank, Integer> remaining = new EnumMap<>(CardRank.class);
        played.put(CardRank.THREE, 0);
        played.put(CardRank.TEN, 4);
        remaining.put(CardRank.THREE, 4);
        remaining.put(CardRank.TEN, 0);

        List<TrickHudView.CounterCell> cells = counterCells(played, remaining, false);
        assertEquals(CardRank.values().length, cells.size(), "记牌器必须固定输出 CardRank.values() 的 15 格");
        for (TrickHudView.CounterCell cell : cells) {
            assertEquals(34, cell.advancePixels(), "每格净前进量必须严格为 34");
        }
        assertEquals(3, cells.get(CardRank.THREE.ordinal()).layers().size());
        assertTrue(cells.get(CardRank.TEN.ordinal()).text().contains(
            PackAssets.counterDigitChar(4, 0)), "数字层应显示累计已出数量");
    }

    @Test
    void 兼容入口由剩余张数推导累计已出并钳制异常值() {
        Map<CardRank, Integer> remaining = new EnumMap<>(CardRank.class);
        remaining.put(CardRank.THREE, 4);
        remaining.put(CardRank.FOUR, 3);
        remaining.put(CardRank.FIVE, 0);
        remaining.put(CardRank.SIX, 99);
        remaining.put(CardRank.SEVEN, -8);
        remaining.put(CardRank.SMALL_JOKER, 1);
        remaining.put(CardRank.BIG_JOKER, 0);

        Map<CardRank, Integer> played = TrickHudService.playedCountsFromRemaining(remaining);

        assertEquals(0, played.get(CardRank.THREE), "剩 4 张应显示 0 已出，而不是显示剩余 4");
        assertEquals(1, played.get(CardRank.FOUR), "剩 3 张应显示 1 已出");
        assertEquals(4, played.get(CardRank.FIVE), "普通牌剩 0 张应显示 4 已出");
        assertEquals(0, played.get(CardRank.SIX), "超过初始牌数的剩余值要先钳制到初始值");
        assertEquals(4, played.get(CardRank.SEVEN), "负剩余值要先钳制到 0");
        assertEquals(0, played.get(CardRank.SMALL_JOKER), "小王剩 1 张应显示 0 已出");
        assertEquals(1, played.get(CardRank.BIG_JOKER), "大王剩 0 张应显示 1 已出");
    }

    /**
     * 正式记牌行仍由配置开关与背包记牌器控制，但调试棒行覆盖也必须进入正式 HUD 路由。
     */
    @Test
    void 记牌行由正式配置与记牌器控制且正式路由传入调试棒覆盖() throws Exception {
        String source = Files.readString(
            Path.of("src/main/java/linmumua/doudizhu/game/TrickHudService.java"));
        int at = source.indexOf("boolean showCounter");
        assertTrue(at > 0, "showCounter 的判定应当存在");
        String region = source.substring(at, source.indexOf(';', at));

        assertTrue(region.contains("counterEnabled()"), "要看配置开关");
        assertTrue(region.contains("hasCounterItem"), "要看背包里有没有记牌器");
        assertFalse(region.contains("getItemInMainHand"),
            "记牌器不能要求握在主手，那会和选牌/出牌抢主手");

        int renderStart = source.indexOf(
            "void render(\n        Player viewer,\n        Seat previous,\n        Seat current,\n        Seat next,\n        List<DoudizhuCard> cards,\n        Map<CardRank, Integer> remainingCounts\n    )");
        assertTrue(renderStart >= 0, "正式带记牌器 render 重载应当存在");
        int renderOpen = source.indexOf('{', renderStart);
        assertTrue(renderOpen > renderStart, "找不到正式 render 重载的方法体");
        int depth = 0;
        int renderEnd = -1;
        for (int index = renderOpen; index < source.length(); index++) {
            char character = source.charAt(index);
            if (character == '{') {
                depth++;
            } else if (character == '}' && --depth == 0) {
                renderEnd = index;
                break;
            }
        }
        assertTrue(renderEnd > renderStart, "找不到正式 render 重载结束位置");
        String renderRegion = source.substring(renderStart, renderEnd);
        assertTrue(renderRegion.contains("plugin.hudRowOverride(viewer.getUniqueId())"),
            "正式 HUD 路由必须读取玩家级调试棒行覆盖");
        assertTrue(renderRegion.contains("playedCountsFromRemaining(remainingCounts), remainingCounts,\n            plugin.hudRowOverride(viewer.getUniqueId()), false)"),
            "兼容入口必须把 hudRowOverride 传入 debug-aware render 重载，并由剩余张数推导累计已出数");
        assertTrue(source.contains("Map<CardRank, Integer> playedCounts"),
            "正式 render 必须显式接收累计已出数量");
        assertTrue(source.contains("counterCells(playedCounts, remainingCounts"),
            "正式 HUD 必须把已出与剩余快照分别传给记牌器");
    }

    @SuppressWarnings("unchecked")
    private static List<TrickHudView.CounterCell> counterCells(
        Map<CardRank, Integer> playedCounts,
        Map<CardRank, Integer> remainingCounts,
        boolean hideWhenExhausted
    ) throws Exception {
        TrickHudService service =
            (TrickHudService) unsafe().allocateInstance(TrickHudService.class);
        Method method = TrickHudService.class.getDeclaredMethod(
            "counterCells", Map.class, Map.class, boolean.class, int.class);
        method.setAccessible(true);
        return (List<TrickHudView.CounterCell>) method.invoke(
            service, playedCounts, remainingCounts, hideWhenExhausted, 0);
    }

    private static Map<CardRank, Integer> fullDeck() {
        Map<CardRank, Integer> counts = new EnumMap<>(CardRank.class);
        for (CardRank rank : CardRank.values()) {
            counts.put(rank, rank.isJoker() ? 1 : 4);
        }
        return counts;
    }

    private static Unsafe unsafe() throws Exception {
        Field field = Unsafe.class.getDeclaredField("theUnsafe");
        field.setAccessible(true);
        return (Unsafe) field.get(null);
    }
}
