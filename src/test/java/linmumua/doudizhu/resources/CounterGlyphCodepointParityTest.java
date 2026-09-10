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
 * 记牌器分层字形：构建期生成的 22 个条目 必须等于 插件运行期复算出来的码位与几何。
 *
 * <p>每档固定 15 个标签、5 个数字和 2 个框。耗尽状态不再复制暗色标签/数字，
 * 而是由运行期叠加耗尽框表达，因此这里必须明确禁止旧的亮暗组合资源回归。
 */
class CounterGlyphCodepointParityTest {
    private static final Pattern HEADER = Pattern.compile("^ {2}muz:(\\S+):$");
    private static final Pattern FIELD = Pattern.compile("^ {4}(\\w+): (.+)$");

    @Test
    void everyTierContainsExactlyTwentyTwoLayeredGlyphs() throws IOException {
        Map<String, Map<String, String>> entries = glyphEntries();
        int tierCount = PackAssets.avatarDownOffsetTierCount();

        assertEquals(tierCount * PackAssets.COUNTER_GLYPHS_PER_TIER, entries.size(),
            "记牌器必须是每档 22 个分层条目，不能回退到亮暗组合资源");
        for (int tier = 0; tier < tierCount; tier++) {
            int downOffset = PackAssets.avatarDownOffsetAt(tier);
            for (CardRank rank : CardRank.values()) {
                assertTrue(entries.containsKey(PackAssets.counterRankAssetName(rank, tier)),
                    "缺少记牌器标签条目: " + PackAssets.counterRankAssetName(rank, tier));
            }
            for (int digit = 0; digit < PackAssets.COUNTER_DIGIT_COUNT; digit++) {
                assertTrue(entries.containsKey(PackAssets.counterDigitAssetName(digit, tier)),
                    "缺少记牌器数字条目: " + PackAssets.counterDigitAssetName(digit, tier));
            }
            assertTrue(entries.containsKey(PackAssets.counterFrameAssetName(false, tier)),
                "缺少记牌器普通框条目，档位 " + downOffset);
            assertTrue(entries.containsKey(PackAssets.counterFrameAssetName(true, tier)),
                "缺少记牌器耗尽框条目，档位 " + downOffset);
        }

        assertTrue(entries.keySet().stream().noneMatch(name -> name.contains("_dim_")),
            "分层记牌器不应再生成 _dim 组合字形");
    }

    @Test
    void layeredGlyphCodepointsMatchTheTierTimesTwentyTwoContract() throws IOException {
        Map<String, Map<String, String>> entries = glyphEntries();
        int tierCount = PackAssets.avatarDownOffsetTierCount();

        for (int tier = 0; tier < tierCount; tier++) {
            int downOffset = PackAssets.avatarDownOffsetAt(tier);
            int tierBase = PackAssets.COUNTER_GLYPH_CODEPOINT_START
                + tier * PackAssets.COUNTER_GLYPHS_PER_TIER;

            for (CardRank rank : CardRank.values()) {
                Map<String, String> entry = entries.get(PackAssets.counterRankAssetName(rank, tier));
                assertEquals(expectedEscape(PackAssets.counterRankChar(rank, tier)), entry.get("char"),
                    "记牌器标签码位不符合 tier*22 分配");
                assertEquals(Integer.toString(PackAssets.COUNTER_LABEL_HEIGHT), entry.get("height"));
                assertEquals(Integer.toString(PackAssets.COUNTER_LABEL_ASCENT - downOffset), entry.get("ascent"));
                assertEquals(PackAssets.COUNTER_GLYPH_FONT, entry.get("font"));
                assertEquals("muz:font/counter/" + labelFile(rank) + ".png", entry.get("file"));
                assertEquals(tierBase + rank.ordinal(), codePoint(PackAssets.counterRankChar(rank, tier)));
            }

            for (boolean exhausted : new boolean[] {false, true}) {
                String frameFile = exhausted ? "frame_exhausted" : "frame_normal";
                int frameIndex = PackAssets.COUNTER_FRAME_START_INDEX + (exhausted ? 1 : 0);
                assertFrameEntry(entries, PackAssets.counterFrameAssetName(exhausted, tier),
                    PackAssets.counterFrameChar(exhausted, tier), frameFile, downOffset,
                    tierBase + frameIndex);
            }

            for (int digit = 0; digit < PackAssets.COUNTER_DIGIT_COUNT; digit++) {
                Map<String, String> entry = entries.get(PackAssets.counterDigitAssetName(digit, tier));
                assertEquals(expectedEscape(PackAssets.counterDigitChar(digit, tier)), entry.get("char"));
                assertEquals(Integer.toString(PackAssets.COUNTER_DIGIT_HEIGHT), entry.get("height"));
                assertEquals(Integer.toString(PackAssets.COUNTER_DIGIT_ASCENT - downOffset), entry.get("ascent"));
                assertEquals(PackAssets.COUNTER_GLYPH_FONT, entry.get("font"));
                assertEquals("muz:font/counter/digit_" + digit + ".png", entry.get("file"));
                assertEquals(tierBase + PackAssets.COUNTER_DIGIT_START_INDEX + digit,
                    codePoint(PackAssets.counterDigitChar(digit, tier)));
            }
        }
    }

    @Test
    void counterLabelsKeepTheCardRankEnumOrder() {
        String[] expected = {
            "label_3", "label_4", "label_5", "label_6", "label_7", "label_8", "label_9",
            "label_10", "label_j", "label_q", "label_k", "label_a", "label_2", "label_small", "label_big"
        };
        for (CardRank rank : CardRank.values()) {
            assertEquals("counter_" + expected[rank.ordinal()] + "_d0",
                PackAssets.counterRankAssetName(rank, 0),
                "CardRank 顺序与构建期标签文件顺序不一致");
        }
    }

    private static void assertFrameEntry(
        Map<String, Map<String, String>> entries,
        String assetName,
        String glyph,
        String file,
        int downOffset,
        int expectedCodepoint
    ) {
        Map<String, String> entry = entries.get(assetName);
        assertTrue(entry != null, "缺少分层字形条目: " + assetName);
        assertEquals(expectedEscape(glyph), entry.get("char"));
        assertEquals(Integer.toString(PackAssets.COUNTER_FRAME_HEIGHT), entry.get("height"));
        assertEquals(Integer.toString(PackAssets.COUNTER_FRAME_ASCENT - downOffset), entry.get("ascent"));
        assertEquals(PackAssets.COUNTER_GLYPH_FONT, entry.get("font"));
        assertEquals("muz:font/counter/" + file + ".png", entry.get("file"));
        assertEquals(expectedCodepoint, codePoint(glyph));
    }

    private static String labelFile(CardRank rank) {
        return switch (rank) {
            case THREE -> "label_3";
            case FOUR -> "label_4";
            case FIVE -> "label_5";
            case SIX -> "label_6";
            case SEVEN -> "label_7";
            case EIGHT -> "label_8";
            case NINE -> "label_9";
            case TEN -> "label_10";
            case JACK -> "label_j";
            case QUEEN -> "label_q";
            case KING -> "label_k";
            case ACE -> "label_a";
            case TWO -> "label_2";
            case SMALL_JOKER -> "label_small";
            case BIG_JOKER -> "label_big";
        };
    }

    private static int codePoint(String glyph) {
        return glyph.codePointAt(0);
    }

    private static String expectedEscape(String glyph) {
        return "\\u" + String.format("%04x", codePoint(glyph));
    }

    private static Map<String, Map<String, String>> glyphEntries() throws IOException {
        String images = read("craftengine/muz/configuration/images/counter.yml").replace("\r\n", "\n");
        Map<String, Map<String, String>> entries = new HashMap<>();
        Map<String, String> current = null;
        for (String line : images.split("\n")) {
            Matcher header = HEADER.matcher(line);
            if (header.matches()) {
                current = new HashMap<>();
                entries.put(header.group(1), current);
                continue;
            }
            Matcher field = FIELD.matcher(line);
            if (current != null && field.matches()) {
                current.put(field.group(1), field.group(2).trim());
            }
        }
        return entries;
    }

    private static String read(String resource) throws IOException {
        try (InputStream stream = CounterGlyphCodepointParityTest.class.getClassLoader()
            .getResourceAsStream(resource)) {
            assertTrue(stream != null, "生成物不在 classpath 上，先跑 generateCraftEngineBundle: " + resource);
            return new String(stream.readAllBytes(), StandardCharsets.UTF_8);
        }
    }
}
