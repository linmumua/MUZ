package linmumua.doudizhu.resources;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import linmumua.doudizhu.assets.PackAssets;
import linmumua.doudizhu.model.CardRank;
import org.junit.jupiter.api.Test;

/**
 * 记牌行字形：构建期生成的码位 必须等于 插件运行期复算出来的码位。
 *
 * <p>【为什么必须有这条测试】：牌族、头像族、王冠族、bot 族在
 * CraftEngineBundleResourcesTest 里都有逐条比对，记牌族此前一条都没有。
 * 两侧目前是同一套算式（亮版 = ordinal，暗版 = ordinal + RANK，数字接在 2*RANK 之后），
 * 但这套下标完全依赖 build.gradle.kts 里 counterRankGlyphFiles 的书写顺序。
 * 那个 listOf 一旦改序，构建期和插件侧会「各自自洽地错位」——
 * 客户端能找到字形，于是不显示豆腐块，而是显示成【另一个点数的图】。
 * 这比豆腐块更难发现：服务端不报错，肉眼也未必立刻看出 3 被画成了 4。
 *
 * <p>所以这里不比对"有没有这个条目"，而是逐档逐条比对 char 的具体数值。
 */
class CounterGlyphCodepointParityTest {
    /** 与 build.gradle.kts 的 avatarDownOffsetTiers 同源，记牌族复用头像偏移档表。 */
    private static final int TIER_COUNT = 201;

    /**
     * 亮版点数字形逐档比对。
     *
     * <p>失败条件：counterRankGlyphFiles 改序、COUNTER_RANK_GLYPHS 改值、
     * 或 tierCodepointBase 的分页公式两侧不同步。
     */
    @Test
    void litRankGlyphsMatchGeneratedCodepoints() throws IOException {
        Map<String, Map<String, String>> entries = glyphEntries();
        int checked = 0;

        for (int tier = 0; tier < TIER_COUNT; tier++) {
            int downOffset = PackAssets.avatarDownOffsetAt(tier);
            for (CardRank rank : CardRank.values()) {
                String asset = "counter_" + rankFileName(rank) + "_d" + downOffset;
                Map<String, String> entry = entries.get(asset);
                assertTrue(entry != null, "生成物里缺少记牌字形条目: " + asset);

                assertEquals(
                    expectedEscape(PackAssets.counterRankChar(rank, false, tier)),
                    entry.get("char"),
                    "档 " + tier + " 的 " + asset + " 码位与插件侧复算不一致，"
                        + "记牌行会显示成别的点数（不是豆腐块，更难发现）"
                );
                checked++;
            }
        }

        assertTrue(checked > 0, "一条都没比对到，说明锚点或文件名规则已失效");
    }

    /**
     * 暗版（已出完置灰）点数字形逐档比对。
     *
     * <p>暗版下标是 ordinal + COUNTER_RANK_GLYPHS，与亮版共用同一段码位。
     * 两版算错任一个都会让「出完变灰」显示成别的图。
     */
    @Test
    void dimRankGlyphsMatchGeneratedCodepoints() throws IOException {
        Map<String, Map<String, String>> entries = glyphEntries();

        for (int tier = 0; tier < TIER_COUNT; tier++) {
            int downOffset = PackAssets.avatarDownOffsetAt(tier);
            for (CardRank rank : CardRank.values()) {
                String asset = "counter_" + rankFileName(rank) + "_dim_d" + downOffset;
                Map<String, String> entry = entries.get(asset);
                assertTrue(entry != null, "生成物里缺少暗版记牌字形条目: " + asset);

                assertEquals(
                    expectedEscape(PackAssets.counterRankChar(rank, true, tier)),
                    entry.get("char"),
                    "档 " + tier + " 的 " + asset + " 暗版码位与插件侧复算不一致"
                );
            }
        }
    }

    /**
     * 数字字形（张数用）逐档比对，亮暗两版都查。
     *
     * <p>数字段接在 2 * COUNTER_RANK_GLYPHS 之后。这个偏移是两侧各写一次的常量，
     * 最容易在增删点数字形时忘记同步。
     */
    @Test
    void digitGlyphsMatchGeneratedCodepoints() throws IOException {
        Map<String, Map<String, String>> entries = glyphEntries();

        for (int tier = 0; tier < TIER_COUNT; tier++) {
            int downOffset = PackAssets.avatarDownOffsetAt(tier);
            for (int digit = 0; digit <= 9; digit++) {
                for (boolean dim : new boolean[] {false, true}) {
                    String asset = "counter_digit_" + digit + (dim ? "_dim" : "") + "_d" + downOffset;
                    Map<String, String> entry = entries.get(asset);
                    assertTrue(entry != null, "生成物里缺少数字字形条目: " + asset);

                    assertEquals(
                        expectedEscape(PackAssets.counterDigitChar(digit, dim, tier)),
                        entry.get("char"),
                        "档 " + tier + " 的 " + asset + " 码位与插件侧复算不一致，"
                            + "记牌行的张数会显示成别的数字"
                    );
                }
            }
        }
    }

    /**
     * 字体名必须两侧一致，且不能挂 minecraft:default。
     *
     * <p>记牌族一档 50 码位、117 档一张，201 档会切成 2 张字体。
     * 消费点若写死第 0 张的名字，第 117 档以后就会取错字体 —— 那才是真豆腐块。
     */
    @Test
    void fontNamesMatchAcrossThePageBoundary() throws IOException {
        Map<String, Map<String, String>> entries = glyphEntries();

        for (int tier = 0; tier < TIER_COUNT; tier++) {
            int downOffset = PackAssets.avatarDownOffsetAt(tier);
            String asset = "counter_" + rankFileName(CardRank.THREE) + "_d" + downOffset;
            Map<String, String> entry = entries.get(asset);
            assertTrue(entry != null, "缺少条目: " + asset);

            assertEquals(
                PackAssets.counterGlyphFont(tier),
                entry.get("font"),
                "档 " + tier + " 的字体名与 PackAssets.counterGlyphFont 不一致，"
                    + "跨字体分页处会整片显示豆腐块"
            );
            assertFalse(
                "minecraft:default".equals(entry.get("font")),
                "记牌字形不能挂 minecraft:default，会和其他族抢同一张码位表"
            );
        }
    }

    /**
     * 点数到贴图文件名的映射，必须与 build.gradle.kts 的 counterRankGlyphFiles 同序同名。
     *
     * <p>这里刻意写成显式 switch 而不是复用生产代码的映射：如果两边都从同一个
     * 方法取名字，那个方法改错时测试会跟着一起错，等于没守住。
     */
    private static String rankFileName(CardRank rank) {
        return switch (rank) {
            case THREE -> "rank_3";
            case FOUR -> "rank_4";
            case FIVE -> "rank_5";
            case SIX -> "rank_6";
            case SEVEN -> "rank_7";
            case EIGHT -> "rank_8";
            case NINE -> "rank_9";
            // 注意是 rank_ten 而不是 rank_10：构建期 counterRankGlyphFiles 里就写的是 ten，
            // 和 digit_0..digit_9 的纯数字命名刻意区分开，避免文件名撞车。
            case TEN -> "rank_ten";
            case JACK -> "rank_j";
            case QUEEN -> "rank_q";
            case KING -> "rank_k";
            case ACE -> "rank_a";
            case TWO -> "rank_2";
            case SMALL_JOKER -> "rank_small_joker";
            case BIG_JOKER -> "rank_big_joker";
        };
    }

    private static String expectedEscape(String glyph) {
        return "\\u" + String.format("%04x", glyph.codePointAt(0));
    }

    /** 只读记牌族那两份分片，避免把整包 images 都读进内存。 */
    private static Map<String, Map<String, String>> glyphEntries() throws IOException {
        String images = read("craftengine/muz/configuration/images/counter_rank.yml")
            + "\n"
            + read("craftengine/muz/configuration/images/counter_digit.yml");
        images = images.replace("\r\n", "\n");

        Map<String, Map<String, String>> entries = new HashMap<>();
        Map<String, String> current = null;
        for (String line : images.split("\n")) {
            Matcher header = Pattern.compile("^ {2}muz:(\\S+):$").matcher(line);
            if (header.matches()) {
                current = new HashMap<>();
                entries.put(header.group(1), current);
                continue;
            }
            Matcher field = Pattern.compile("^ {4}(\\w+): (.+)$").matcher(line);
            if (current != null && field.matches()) {
                current.put(field.group(1), field.group(2).trim());
            }
        }
        return entries;
    }

    private static String read(String resource) throws IOException {
        try (InputStream stream =
                 CounterGlyphCodepointParityTest.class.getClassLoader().getResourceAsStream(resource)) {
            assertTrue(stream != null, "生成物不在 classpath 上，先跑 generateCraftEngineBundle: " + resource);
            return new String(stream.readAllBytes(), StandardCharsets.UTF_8);
        }
    }
}
