package linmumua.doudizhu.resources;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import linmumua.doudizhu.assets.PackAssets;
import linmumua.doudizhu.assets.PackTiers;
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
        int tierCount = PackAssets.counterDownOffsetTierCount();
        int scaleCount = PackAssets.COUNTER_SCALE_TIERS.length;

        assertEquals(scaleCount * tierCount * PackAssets.COUNTER_GLYPHS_PER_TIER, entries.size(),
            "每个 counter scale 的每档必须是 22 个分层条目，不能回退到亮暗组合资源");
        for (int scale : PackAssets.COUNTER_SCALE_TIERS) {
            for (int tier = 0; tier < tierCount; tier++) {
                int downOffset = PackAssets.counterDownOffsetAt(tier);
                for (CardRank rank : CardRank.values()) {
                    assertTrue(entries.containsKey(PackAssets.counterRankAssetName(rank, scale, tier)),
                        "缺少记牌器标签条目: " + PackAssets.counterRankAssetName(rank, scale, tier));
                }
                for (int digit = 0; digit < PackAssets.COUNTER_DIGIT_COUNT; digit++) {
                    assertTrue(entries.containsKey(PackAssets.counterDigitAssetName(digit, scale, tier)),
                        "缺少记牌器数字条目: " + PackAssets.counterDigitAssetName(digit, scale, tier));
                }
                assertTrue(entries.containsKey(PackAssets.counterFrameAssetName(false, scale, tier)),
                    "缺少记牌器普通框条目，scale=" + scale + " 档位 " + downOffset);
                assertTrue(entries.containsKey(PackAssets.counterFrameAssetName(true, scale, tier)),
                    "缺少记牌器耗尽框条目，scale=" + scale + " 档位 " + downOffset);
            }
        }

        assertTrue(entries.keySet().stream().noneMatch(name -> name.contains("_dim_")),
            "分层记牌器不应再生成 _dim 组合字形");
    }

    @Test
    void layeredGlyphCodepointsMatchTheTierTimesTwentyTwoContract() throws IOException {
        Map<String, Map<String, String>> entries = glyphEntries();
        int tierCount = PackAssets.counterDownOffsetTierCount();

        for (int scale : PackAssets.COUNTER_SCALE_TIERS) {
            for (int tier = 0; tier < tierCount; tier++) {
                int downOffset = PackAssets.counterDownOffsetAt(tier);
                PackAssets.CounterTier geometry = PackAssets.counterTier(scale, tier);
                int tierBase = geometry.codepointStart() + tier * PackAssets.COUNTER_GLYPHS_PER_TIER;

                for (CardRank rank : CardRank.values()) {
                    Map<String, String> entry = entries.get(PackAssets.counterRankAssetName(rank, scale, tier));
                    assertEquals(expectedEscape(PackAssets.counterRankChar(rank, scale, tier)), entry.get("char"),
                        "记牌器标签码位不符合 scale + tier*22 分配");
                    assertEquals(Integer.toString(geometry.labelHeight()), entry.get("height"));
                    assertEquals(Integer.toString(geometry.labelAscent()), entry.get("ascent"));
                    assertEquals(geometry.font(), entry.get("font"));
                    assertEquals(PackAssets.counterRankTexturePath(rank, scale), entry.get("file"));
                    assertEquals(tierBase + rank.ordinal(), codePoint(PackAssets.counterRankChar(rank, scale, tier)));
                }

                for (boolean exhausted : new boolean[] {false, true}) {
                    String frameFile = exhausted ? "frame_exhausted" : "frame_normal";
                    int frameIndex = PackAssets.COUNTER_FRAME_START_INDEX + (exhausted ? 1 : 0);
                    assertFrameEntry(entries, PackAssets.counterFrameAssetName(exhausted, scale, tier),
                        PackAssets.counterFrameChar(exhausted, scale, tier), frameFile, geometry.frameHeight(),
                        geometry.frameAscent(), geometry.font(),
                        PackAssets.counterFrameTexturePath(exhausted, scale), tierBase + frameIndex);
                }

                for (int digit = 0; digit < PackAssets.COUNTER_DIGIT_COUNT; digit++) {
                    Map<String, String> entry = entries.get(PackAssets.counterDigitAssetName(digit, scale, tier));
                    assertEquals(expectedEscape(PackAssets.counterDigitChar(digit, scale, tier)), entry.get("char"));
                    assertEquals(Integer.toString(geometry.digitHeight()), entry.get("height"));
                    assertEquals(Integer.toString(geometry.digitAscent()), entry.get("ascent"));
                    assertEquals(geometry.font(), entry.get("font"));
                    assertEquals(PackAssets.counterDigitTexturePath(digit, scale), entry.get("file"));
                    assertEquals(tierBase + PackAssets.COUNTER_DIGIT_START_INDEX + digit,
                        codePoint(PackAssets.counterDigitChar(digit, scale, tier)));
                }
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

    @Test
    void counterAndAvatarOffsetTablesMatchTheProfileGeneratedCollections() {
        assertEquals(PackTiers.COUNTER_DOWN_OFFSET_TIERS.length, PackAssets.counterDownOffsetTierCount());
        assertEquals(PackTiers.AVATAR_DOWN_OFFSET_TIERS.length, PackAssets.avatarDownOffsetTierCount());
        assertTrue(PackAssets.counterDownOffsetTierCount() > 0, "当前 profile 至少要生成一个 counter 偏移档");
        assertTrue(PackAssets.avatarDownOffsetTierCount() > 0, "当前 profile 至少要生成一个头像偏移档");
        assertEquals(PackTiers.COUNTER_DOWN_OFFSET_TIERS[0], PackAssets.counterDownOffsetAt(0));
        assertEquals(PackTiers.AVATAR_DOWN_OFFSET_TIERS[0], PackAssets.avatarDownOffsetAt(0));
        assertTrue(PackAssets.counterDownOffsetTierCount() < 201,
            "当前 profile 已精简 counter 资源，不应退回旧版 0..400 step2 全量 201 档");
        assertTrue(PackAssets.avatarDownOffsetTierCount() < 401,
            "当前 profile 已精简头像资源，不应退回旧版 0..400 step1 全量 401 档");
        int ungenerated = PackAssets.counterDownOffsetAt(0) + 1;
        if (PackAssets.counterDownOffsetTierOf(ungenerated) >= 0) {
            ungenerated++;
        }
        assertEquals(-1, PackAssets.counterDownOffsetTierOf(ungenerated),
            "counter 不应为 profile 未生成的偏移档提供声明");
    }

    @Test
    void counterScaleGeometryAndLayerAnchorsAreIntegerAndStable() {
        // 独立锁定批准的基础几何，不能仅让生成侧与运行期互相证明自洽。
        PackAssets.CounterTier base = PackAssets.counterTier(100, 0);
        assertEquals(21, base.width());
        assertEquals(27, base.height());
        assertEquals(22, base.advance());
        assertEquals(12, base.labelHeight());
        assertEquals(12, base.frameHeight());
        assertEquals(8, base.digitHeight());
        assertEquals(12, base.labelAscent());
        assertEquals(-3, base.frameAscent());
        assertEquals(-6, base.digitAscent());
        assertEquals(358, 15 * base.advance() + 14 * 2);
        for (int scale : PackAssets.COUNTER_SCALE_TIERS) {
            PackAssets.CounterTier tier = PackAssets.counterTier(scale, 0);

            assertEquals(tier.labelWidth(), tier.width());
            assertEquals(tier.labelAdvance(), tier.advance());
            assertEquals(tier.frameAdvance(), tier.advance());
            assertEquals(tier.digitAdvance(), tier.advance());
            assertEquals(tier.labelWidth(), tier.frameWidth());
            assertEquals(tier.labelWidth(), tier.digitWidth());
            assertEquals(Math.max(
                tier.frameY() + tier.frameHeight(),
                tier.digitY() + tier.digitHeight()), tier.height());
            assertEquals(tier.labelAscent() - tier.frameAscent(), tier.frameTopDelta());
            assertEquals(tier.frameAscent() - tier.digitAscent(), tier.digitInset());
            assertEquals(tier.frameTopDelta(), tier.frameY());
            assertEquals(tier.labelAscent() - tier.digitAscent(), tier.digitY());
        }
    }

    @Test
    void counterDefaultCodepointStartsAtE900AndKeepsFifteenRanks() {
        assertEquals(0xE900, PackAssets.COUNTER_GLYPH_CODEPOINT_START);
        assertEquals(CardRank.values().length, PackAssets.COUNTER_LABEL_COUNT);
        assertEquals(15, PackAssets.COUNTER_LABEL_COUNT);

        PackAssets.CounterTier tier = PackAssets.counterTier(PackAssets.DEFAULT_HUD_SCALE, 0);
        assertEquals(PackAssets.COUNTER_GLYPH_CODEPOINT_START, tier.codepointStart());
        for (CardRank rank : CardRank.values()) {
            assertEquals(
                tier.codepointStart() + rank.ordinal(),
                PackAssets.counterRankChar(rank, PackAssets.DEFAULT_HUD_SCALE, 0).codePointAt(0),
                "默认 counter 起点后的标签必须严格按 CardRank 顺序连续分配"
            );
        }
    }

    @Test
    void counterScaleCodepointsStayBmpAndUseIndependentFonts() {
        Map<String, Set<Integer>> seen = new HashMap<>();
        for (int scale : PackAssets.COUNTER_SCALE_TIERS) {
            PackAssets.CounterTier tier = PackAssets.counterTier(scale, 0);
            Set<Integer> codepoints = new HashSet<>();
            for (int down = 0; down < PackAssets.counterDownOffsetTierCount(); down++) {
                for (int layer = 0; layer < PackAssets.COUNTER_GLYPHS_PER_TIER; layer++) {
                    String glyph;
                    if (layer < PackAssets.COUNTER_LABEL_COUNT) {
                        glyph = PackAssets.counterRankChar(CardRank.values()[layer], scale, down);
                    } else if (layer < PackAssets.COUNTER_FRAME_START_INDEX) {
                        glyph = PackAssets.counterDigitChar(layer - PackAssets.COUNTER_DIGIT_START_INDEX, scale, down);
                    } else {
                        glyph = PackAssets.counterFrameChar(
                            layer == PackAssets.COUNTER_FRAME_START_INDEX + 1, scale, down);
                    }
                    int codepoint = codePoint(glyph);
                    assertTrue(codepoint >= 0xE000 && codepoint <= 0xFFFF,
                        "counter scale=" + scale + " 的码位越出 BMP：" + Integer.toHexString(codepoint));
                    assertTrue(codepoints.add(codepoint), "counter scale=" + scale + " 内码位重用");
                }
            }
            seen.put(tier.font(), codepoints);
        }
        assertEquals(PackAssets.COUNTER_SCALE_TIERS.length, seen.size(), "每个 counter scale 必须使用独立字体");
        for (int scale : PackAssets.COUNTER_SCALE_TIERS) {
            PackAssets.CounterTier tier = PackAssets.counterTier(scale, 0);
            int lastCodepoint = tier.codepointStart()
                + (PackAssets.counterDownOffsetTierCount() - 1) * PackAssets.COUNTER_GLYPHS_PER_TIER
                + PackAssets.COUNTER_GLYPHS_PER_TIER - 1;
            assertTrue(tier.codepointStart() >= 0xE000 && lastCodepoint <= 0xFFFF,
                "counter scale=" + scale + " 的当前 profile 码位必须留在 BMP 内");
        }
    }

    private static void assertFrameEntry(
        Map<String, Map<String, String>> entries,
        String assetName,
        String glyph,
        String file,
        int height,
        int ascent,
        String font,
        String texture,
        int expectedCodepoint
    ) {
        Map<String, String> entry = entries.get(assetName);
        assertTrue(entry != null, "缺少分层字形条目: " + assetName);
        assertEquals(expectedEscape(glyph), entry.get("char"));
        assertEquals(Integer.toString(height), entry.get("height"));
        assertEquals(Integer.toString(ascent), entry.get("ascent"));
        assertEquals(font, entry.get("font"));
        assertEquals(texture, entry.get("file"));
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
        Map<String, Map<String, String>> entries = new HashMap<>();
        for (int scale : PackAssets.COUNTER_SCALE_TIERS) {
            String part = scale == PackAssets.DEFAULT_HUD_SCALE ? "counter" : "counter_s" + scale;
            String images = read("craftengine/muz/configuration/images/" + part + ".yml").replace("\r\n", "\n");
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
