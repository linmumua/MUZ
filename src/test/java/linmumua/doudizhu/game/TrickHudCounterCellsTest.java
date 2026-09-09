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

        List<TrickHudView.CounterCell> cells = counterCells(counts, true);

        assertEquals(CardRank.values().length, cells.size(), "每个点数都要有一格");
        // 文字渲染版：首格文本必须包含对应点数的标签文字（如 "3"），顺序由 CardRank 枚举声明顺序决定。
        assertTrue(
            cells.get(0).text().contains(CardRank.values()[0].label()),
            "首格应对应 CardRank 的第一个常量，顺序不能随 map 迭代而变");
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
        Map<CardRank, Integer> counts = new EnumMap<>(CardRank.class);
        counts.put(CardRank.THREE, 0);
        counts.put(CardRank.FOUR, 4);

        List<TrickHudView.CounterCell> cells = counterCells(counts, true);

        assertEquals(2, cells.size(), "出完的点数照样占一格");
        assertTrue(cells.get(0).isEmpty(), "出完的点数不画内容");
        assertTrue(cells.get(0).advancePixels() > 0,
            "空格子必须自报宽度，否则后面的格子会左移、整行抽动");
    }

    /**
     * hide 关闭时，出完的点数照样显示「剩 0 张」。
     *
     * <p>这是配置项存在的意义：有人想看到「这个点数已经出完了」而不是一片空白。
     *
     * <p>失败条件：无视 hideWhenExhausted 一律留空。
     */
    @Test
    void hide关闭时出完的点数仍显示读数() throws Exception {
        Map<CardRank, Integer> counts = new EnumMap<>(CardRank.class);
        counts.put(CardRank.THREE, 0);

        List<TrickHudView.CounterCell> cells = counterCells(counts, false);

        assertFalse(cells.get(0).isEmpty(), "hide 关闭时出完的点数也要画出来");
        // 文字渲染版：出完的点数用 dark_gray 渲染（dim 效果），仍包含点数标签与张数 "0"。
        assertTrue(cells.get(0).text().contains(CardRank.THREE.label()),
            "应显示点数标签（" + CardRank.THREE.label() + "）");
        assertTrue(cells.get(0).text().contains("0"),
            "应显示剩 0 张");
        assertTrue(cells.get(0).text().contains("dark_gray"),
            "出完的点数应用 dark_gray（dim 效果）渲染，而不是正常亮色");
    }

    /**
     * 空读数返回空列表，让 View 直接不画这一行。
     *
     * <p>失败条件：返回 13 个空格子——View 会因此认为「有记牌行」而画出一条
     * 只有宽度没有内容的空行，把另两行的居中基准推歪。
     */
    @Test
    void 没有读数时返回空列表() throws Exception {
        assertTrue(counterCells(new EnumMap<>(CardRank.class), true).isEmpty(),
            "牌局没开始就不该有记牌行，空列表让 View 跳过整行");
    }

    /**
     * 格子宽度必须随文本长度变化。
     *
     * <p>剩 4 张是一位数、剩 10 张以上是两位数，宽度写死会让两位数那几格压字。
     * 这正是 config 里不提供「每格固定宽度」配置项的原因。
     *
     * <p>失败条件：advancePixels 改成常量。
     */
    @Test
    void 格子宽度随文本长度变化而不是写死() throws Exception {
        Map<CardRank, Integer> oneDigit = new EnumMap<>(CardRank.class);
        oneDigit.put(CardRank.THREE, 4);
        Map<CardRank, Integer> twoDigit = new EnumMap<>(CardRank.class);
        twoDigit.put(CardRank.THREE, 12);

        int narrow = counterCells(oneDigit, true).get(0).advancePixels();
        int wide = counterCells(twoDigit, true).get(0).advancePixels();

        assertTrue(wide > narrow,
            "两位数的格子必须更宽（" + wide + " vs " + narrow + "），写死宽度会压字");
    }

    /**
     * 双宽字形（「10」、双王）的格子必须比单宽点数更宽。
     *
     * <p>构建期 {@code rankGlyphWidth} 给 "10" 和 "王" 画的是双宽图（48px），其余是单宽（29px）。
     * 插件侧的宽度分档必须跟着这条规则走：一律按单宽算，双王和 10 那三格的实际渲染
     * 就会超出自报宽度，压到相邻格子上，整行居中也跟着偏。
     *
     * <p>【必须选同位数读数对比】：都取 1 张，消掉「数字位数」这一维，
     * 宽度差就只可能来自点数字形本身的宽窄分档。
     *
     * <p>失败条件：把双宽分档去掉、或把双王错划进单宽。
     */
    @Test
    void 双宽点数字形的格子比单宽更宽() throws Exception {
        Map<CardRank, Integer> narrowRank = new EnumMap<>(CardRank.class);
        narrowRank.put(CardRank.THREE, 1);
        Map<CardRank, Integer> wideTen = new EnumMap<>(CardRank.class);
        wideTen.put(CardRank.TEN, 1);
        Map<CardRank, Integer> wideJoker = new EnumMap<>(CardRank.class);
        wideJoker.put(CardRank.SMALL_JOKER, 1);

        int narrow = counterCells(narrowRank, true).get(0).advancePixels();
        int ten = counterCells(wideTen, true).get(0).advancePixels();
        int joker = counterCells(wideJoker, true).get(0).advancePixels();

        // 文字渲染版：宽度由 label 字符数决定（每字符 6px + 竖线 4px + 张数字符数 * 6px）。
        // 「10」和「小王」/「大王」的 label 长度 > 「3」等单字符点数，所以格子更宽。
        assertTrue(CardRank.TEN.label().length() > CardRank.THREE.label().length(),
            "「10」的标签字符数必须多于「3」，否则文字版无法体现宽格子");
        assertTrue(CardRank.SMALL_JOKER.label().length() > CardRank.THREE.label().length(),
            "「小王」的标签字符数必须多于「3」，否则文字版无法体现宽格子");
        assertTrue(ten > narrow && joker > narrow,
            "多字符点数的格子必须更宽（" + ten + "/" + joker + " vs " + narrow
                + "）；label 更长则 advance 更大，确保不压字");
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
        assertTrue(renderRegion.contains("remainingCounts,\n            plugin.hudRowOverride(viewer.getUniqueId()), false)"),
            "正式 HUD 路由必须把 hudRowOverride 传入 debug-aware render 重载");
    }

    @SuppressWarnings("unchecked")
    private static List<TrickHudView.CounterCell> counterCells(
        Map<CardRank, Integer> counts,
        boolean hideWhenExhausted
    ) throws Exception {
        TrickHudService service =
            (TrickHudService) unsafe().allocateInstance(TrickHudService.class);
        Method method = TrickHudService.class.getDeclaredMethod(
            "counterCells", Map.class, boolean.class, int.class);
        method.setAccessible(true);
        return (List<TrickHudView.CounterCell>) method.invoke(service, counts, hideWhenExhausted, 0);
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
