package linmumua.doudizhu.assets;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import linmumua.doudizhu.assets.PackTiers;
import linmumua.doudizhu.model.CardRank;
import linmumua.doudizhu.model.CardSuit;
import linmumua.doudizhu.model.DoudizhuCard;
import org.junit.jupiter.api.Test;

/**
 * 守「PackAssets 算出的码位与字体，等于 images.yml 里真实生成的那一条」。
 *
 * <p>这是本次「档位放开 + 字体切分」最危险的地方：算式在构建期（Kotlin）与运行期（Java）
 * 各实现了一遍，错位的后果【不是豆腐块而是静默串牌】—— 比如把黑桃 3 显示成黑桃 4，
 * 服主根本不会怀疑是资源包问题。所以必须逐档拿真实产物比对，不能靠「张数对得上」推断。
 *
 * <p>重点覆盖【跨字体边界那一档】：牌族 144 档/张、头像族 40 档/张，fontIndex 从 0 变 1
 * 的那一档是算式最容易写歪的地方（容量按各族起点算而不是固定 8190）。
 */
class PackTierGlyphContractTest {

    /** images.yml 里一条 image 的三个字段。 */
    private record Entry(String font, int codepoint) {
    }

    private static final Pattern NAME = Pattern.compile("^ {2}muz:(\\S+):$");
    private static final Pattern FONT = Pattern.compile("^\\s+font: (\\S+)$");
    private static final Pattern CHAR = Pattern.compile("^\\s+char: \\\\u([0-9a-fA-F]{4})$");

    /**
     * 解析构建产物里的 images.yml。
     *
     * <p>【找不到就 assumption 失败，不静默早退】：静默返回空表会让三条比对测试变成
     * 「永远不会红的测试」—— 那比没有测试更糟，因为它会让人以为码位算式被守着。
     * 用 JUnit 的 assumption 表达「这次没验证」，报告里会显示 skipped 而不是 passed。
     */
    private static Map<String, Entry> loadImages() {
        Map<String, Entry> result = new HashMap<>();
        try {
            List<Path> parts = imagePartFiles();
            for (Path yml : parts) {
                List<String> lines = Files.readAllLines(yml, StandardCharsets.UTF_8);
                String name = null;
                String font = null;
                for (String line : lines) {
                    Matcher nameMatcher = NAME.matcher(line);
                    if (nameMatcher.matches()) {
                        name = nameMatcher.group(1);
                        font = null;
                        continue;
                    }
                    Matcher fontMatcher = FONT.matcher(line);
                    if (fontMatcher.matches()) {
                        font = fontMatcher.group(1);
                        continue;
                    }
                    Matcher charMatcher = CHAR.matcher(line);
                    if (charMatcher.matches() && name != null && font != null) {
                        result.put(name, new Entry(font, Integer.parseInt(charMatcher.group(1), 16)));
                    }
                }
            }
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        return result;
    }

    /**
     * 列出构建产物里 configuration/images/ 下的所有拆分文件。
     *
     * <p>【为什么是目录而不是单份 images.yml】：这些条目是按当前 profile 乘出来的
     * （牌 55 张 × 生成的高度档 × 生成的偏移档，头像生成的偏移档 × 生成的 scale × 10 行）。
     * 单份会超过 SnakeYAML Engine 的 3,145,728 code point 上限，CraftEngine 直接抛
     * YamlEngineException、整包不生效——线上已经因此炸过一次，所以生成器按牌高与
     * scale 切成了多份。
     *
     * <p>【空目录要 assumption 失败，不能静默返回空表】：空表会让下面几条比对测试
     * 变成「永远不会红的测试」，那比没有测试更糟——它会让人以为码位算式被守着。
     * 这一点是这个测试类原本就有的约定，拆分后继续保持。
     */
    private static List<Path> imagePartFiles() throws IOException {
        Path dir = Path.of("build", "paper-26.2", "generated", "resources", "main",
            "craftengine", "muz", "configuration", "images");
        org.junit.jupiter.api.Assumptions.assumeTrue(Files.isDirectory(dir),
            "images/ 还没生成（先跑 generateCraftEngineBundle），这次没有验证码位契约：" + dir);
        try (var stream = Files.list(dir)) {
            List<Path> parts = stream
                .filter(path -> path.getFileName().toString().endsWith(".yml"))
                .sorted()
                .toList();
            org.junit.jupiter.api.Assumptions.assumeTrue(!parts.isEmpty(),
                "images/ 下没有任何 yml，这次没有验证码位契约：" + dir);
            return parts;
        }
    }

    private static int codepointOf(String glyph) {
        return glyph.codePointAt(0);
    }

    @Test
    void 牌面字形逐档与资源包一致_含跨字体边界那一档() {
        Map<String, Entry> images = loadImages();
        DoudizhuCard card = new DoudizhuCard(0, CardRank.THREE, CardSuit.SPADES);
        int downTierCount = PackAssets.cardGlyphDownOffsetTierCount();
        int tiersPerFont = 144;

        // 逐档全覆盖代价太大；按当前生成集合挑选两端和字体切分边界。
        // profile 缩减到单档时，越过实际集合的候选值会被安全跳过。
        int totalTiers = PackAssets.cardGlyphHeightTierCount() * downTierCount;
        int[] interesting = {
            0, 1, tiersPerFont - 1, tiersPerFont, tiersPerFont + 1,
            2 * tiersPerFont - 1, 2 * tiersPerFont, 3 * tiersPerFont,
            totalTiers - 1,
        };
        for (int tier : interesting) {
            int heightTier = tier / downTierCount;
            int downTier = tier % downTierCount;
            if (heightTier >= PackAssets.cardGlyphHeightTierCount()) {
                continue;
            }
            String name = PackAssets.cardGlyphAssetName(
                PackAssets.cardAssetName(card), heightTier, downTier);
            Entry entry = images.get(name);
            assertTrue(entry != null, "images.yml 里没有这条：" + name);
            assertEquals(entry.codepoint(),
                codepointOf(PackAssets.cardGlyphChar(card, heightTier, downTier)),
                "牌面码位与资源包不一致（档 " + tier + "，条目 " + name + "）—— 会静默串牌");
            assertEquals(entry.font(), PackAssets.cardGlyphFont(heightTier, downTier),
                "牌面字体名与资源包不一致（档 " + tier + "）—— 整段会变豆腐块");
        }
    }

    @Test
    void 头像字形逐档与资源包一致_含跨字体边界那一档() {
        Map<String, Entry> images = loadImages();
        int tiersPerFont = 40;
        int last = PackAssets.avatarDownOffsetTierCount() - 1;
        int[] interesting = {
            0, Math.min(1, last), Math.min(tiersPerFont - 1, last),
            Math.min(tiersPerFont, last), Math.min(tiersPerFont + 1, last), last,
        };
        for (int downTier : interesting) {
            if (downTier > last) {
                continue;
            }
            for (int scale : PackTiers.AVATAR_SCALE_TIERS) {
                for (int row : new int[] {0, PackAssets.AVATAR_OUTLINED_PIXELS - 1}) {
                    String name = PackAssets.avatarPixelAssetName(scale, row, downTier);
                    Entry entry = images.get(name);
                    assertTrue(entry != null, "images.yml 里没有这条：" + name);
                    assertEquals(entry.codepoint(),
                        codepointOf(PackAssets.avatarPixelChar(scale, row, downTier)),
                        "头像码位与资源包不一致（档 " + downTier + "，条目 " + name + "）");
                    assertEquals(entry.font(), PackAssets.avatarPixelFont(downTier),
                        "头像字体名与资源包不一致（档 " + downTier + "）");
                }
            }
        }
    }

    @Test
    void 王冠字形逐档与资源包一致() {
        Map<String, Entry> images = loadImages();
        int last = PackAssets.avatarDownOffsetTierCount() - 1;
        for (int downTier : new int[] {0, 1, 40, 100, last}) {
            if (downTier > last) {
                continue;
            }
            for (int scale : PackTiers.AVATAR_SCALE_TIERS) {
                for (int row = 0; row < PackAssets.AVATAR_CROWN_PIXELS; row++) {
                    String name = PackAssets.avatarCrownAssetName(scale, row, downTier);
                    Entry entry = images.get(name);
                    assertTrue(entry != null, "images.yml 里没有这条：" + name);
                    assertEquals(entry.codepoint(),
                        codepointOf(PackAssets.avatarCrownChar(scale, row, downTier)),
                        "王冠码位与资源包不一致（档 " + downTier + "，条目 " + name + "）");
                    assertEquals(entry.font(), PackAssets.avatarCrownFont(downTier),
                        "王冠字体名与资源包不一致（档 " + downTier + "）");
                }
            }
        }
    }

    /**
     * 王冠必须【接在头像正上方】，底边与头像同锚 —— 这是「向上凸出」的几何定义。
     *
     * <p>守的风险：这次改动的核心就是这个几何关系。王冠字形高度写成
     * {@code (12 - row) * scale}、头像写成 {@code (10 - row) * scale}，两者共用同一个
     * {@code ascent = height - downOffset}，于是底边都落在 {@code -downOffset}。
     * 如果谁把王冠的行数基数改回 10，王冠会【盖住】头像顶部两行（回到旧行为）；
     * 改成 13 则会与头像之间裂开一道缝。两种都不会报错，只是画得不对。
     *
     * <p>用资源包里真实的 height/ascent 验算，不是复算公式 —— 公式对不对正是要验的东西。
     */
    @Test
    void 王冠接在头像正上方且底边同锚() {
        Map<String, Entry> images = loadImages();
        List<String> lines = new java.util.ArrayList<>();
        try {
            for (Path part : imagePartFiles()) {
                lines.addAll(Files.readAllLines(part, StandardCharsets.UTF_8));
            }
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        Map<String, int[]> metrics = new HashMap<>();
        String name = null;
        int height = 0;
        for (String line : lines) {
            Matcher nameMatcher = NAME.matcher(line);
            if (nameMatcher.matches()) {
                name = nameMatcher.group(1);
                continue;
            }
            Matcher heightMatcher = Pattern.compile("^\\s+height: (-?\\d+)$").matcher(line);
            if (heightMatcher.matches()) {
                height = Integer.parseInt(heightMatcher.group(1));
                continue;
            }
            Matcher ascentMatcher = Pattern.compile("^\\s+ascent: (-?\\d+)$").matcher(line);
            if (ascentMatcher.matches() && name != null) {
                metrics.put(name, new int[] {height, Integer.parseInt(ascentMatcher.group(1))});
            }
        }
        assertTrue(!metrics.isEmpty(), "没解析到任何 height/ascent");

        int scale = PackTiers.AVATAR_SCALE_TIERS[0];
        int downTier = PackAssets.avatarDownOffsetTierCount() - 1;
        int downOffset = PackAssets.avatarDownOffsetAt(downTier);

        // 白块（可见的那一格）在基线上方的区间是 [height - d - scale, height - d]。
        int[] crownTop = metrics.get(PackAssets.avatarCrownAssetName(scale, 0, downTier));
        int[] crownBottom = metrics.get(PackAssets.avatarCrownAssetName(scale, 1, downTier));
        int[] faceTop = metrics.get(PackAssets.avatarPixelAssetName(scale, 0, downTier));
        int[] faceBottom = metrics.get(
            PackAssets.avatarPixelAssetName(scale, PackAssets.AVATAR_OUTLINED_PIXELS - 1, downTier));
        assertTrue(crownTop != null && crownBottom != null && faceTop != null && faceBottom != null,
            "缺少要比对的条目（scale=" + scale + " 档=" + downTier + "）");

        // 底边同锚：所有字形盒底都在基线下方 downOffset。
        for (int[] m : new int[][] {crownTop, crownBottom, faceTop, faceBottom}) {
            assertEquals(m[0] - m[1], downOffset,
                "字形盒底必须都落在 -downOffset（height - ascent 应等于 " + downOffset + "）");
        }

        // 王冠最下面那行的白块底边，必须正好等于头像第一行白块的顶边 —— 无缝且不重叠。
        int crownBottomEdge = crownBottom[0] - downOffset - scale;
        int faceTopEdge = faceTop[0] - downOffset;
        assertEquals(faceTopEdge, crownBottomEdge,
            "王冠底行必须紧接头像顶行：王冠底边 " + crownBottomEdge + " 应等于头像顶边 " + faceTopEdge
                + "。不相等意味着王冠盖住了头像（重叠）或与头像裂开一道缝");

        // 头像行整体高度必须等于 AVATAR_ROW_TOTAL_PIXELS * scale —— 重叠判定就按这个算。
        // 行顶（王冠 row 0 的白块顶边）在基线下方 downOffset - crownTop.height，
        // 行底在基线下方 downOffset，跨度就是 crownTop.height。
        assertEquals(PackAssets.AVATAR_ROW_TOTAL_PIXELS * scale, crownTop[0],
            "头像行整体（含王冠）应占 " + PackAssets.AVATAR_ROW_TOTAL_PIXELS + "*" + scale
                + " 像素，warnIfRowsOverlap 正是按这个判不重叠");
        assertTrue(images.isEmpty() || images.containsKey(
                PackAssets.avatarCrownAssetName(scale, 0, downTier)),
            "王冠条目名应与码位表一致");
    }

    @Test
    void 默认牌面高必须落在档位表里() {
        assertTrue(PackAssets.cardGlyphHeightTierOf(PackAssets.DEFAULT_CARD_HEIGHT) >= 0,
            "DEFAULT_CARD_HEIGHT=" + PackAssets.DEFAULT_CARD_HEIGHT
                + " 不在档位表里，默认配置一启动就会被吸附成别的高度");
    }

    @Test
    void 所有码位都留在BMP内() {
        // 越界会让 images.yml 产出 5 位 unicode 转义，被 YAML 截断后整族错位。
        // 构建期有 check 兜着，这里守运行期算式不会自己算出越界值。
        int last = PackAssets.avatarDownOffsetTierCount() - 1;
        for (int downTier = 0; downTier <= last; downTier++) {
            int cp = codepointOf(PackAssets.avatarPixelChar(
                PackTiers.AVATAR_SCALE_TIERS[PackTiers.AVATAR_SCALE_TIERS.length - 1],
                PackAssets.AVATAR_OUTLINED_PIXELS - 1, downTier));
            assertTrue(cp <= PackTiers.MAX_GLYPH_CODEPOINT,
                "头像档 " + downTier + " 的码位 0x" + Integer.toHexString(cp) + " 超出 BMP");
        }
        int cardTiers = PackAssets.cardGlyphHeightTierCount() * PackAssets.cardGlyphDownOffsetTierCount();
        for (int tier = 0; tier < cardTiers; tier++) {
            int heightTier = tier / PackAssets.cardGlyphDownOffsetTierCount();
            int downTier = tier % PackAssets.cardGlyphDownOffsetTierCount();
            int cp = codepointOf(PackAssets.cardGlyphChar(
                new DoudizhuCard(0, CardRank.THREE, CardSuit.SPADES), heightTier, downTier));
            assertTrue(cp <= PackTiers.MAX_GLYPH_CODEPOINT,
                "牌档 " + tier + " 的码位 0x" + Integer.toHexString(cp) + " 超出 BMP");
        }
    }
}
