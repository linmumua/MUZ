package linmumua.doudizhu.assets;

import linmumua.doudizhu.game.PlayerRole;
import linmumua.doudizhu.model.CardRank;
import linmumua.doudizhu.model.CardSuit;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;

/**
 * 连续 HUD 覆盖层的纯内存布局器。
 *
 * <p>这里不读 PNG、不写 YAML，也不读取配置文件；所有原始几何、码位、字体和资源键均由
 * {@link PackAssets} 复算。需要生成 PNG 的 writer 只消费返回的 {@link Glyph} 元数据。
 */
public final class HudOverlayLayout {
    public static final int MIN_TRICK_OFFSET = PackAssets.MIN_TRICK_OFFSET;
    public static final int MAX_TRICK_OFFSET = PackAssets.MAX_TRICK_OFFSET;

    /** 覆盖层字形的完整资源元数据，字段顺序是 writer/verifier 的共享契约。 */
    public record Glyph(
        String id,
        String font,
        int codepoint,
        String baseTexture,
        int baseHeight,
        int baseAscent,
        int offset,
        String texture,
        int height,
        int ascent,
        int paddingRasterRows,
        int originalWidth,
        int originalHeight,
        int advance
    ) {
        public Glyph {
            Objects.requireNonNull(id, "id");
            Objects.requireNonNull(font, "font");
            Objects.requireNonNull(baseTexture, "baseTexture");
            Objects.requireNonNull(texture, "texture");
            if (id.isBlank() || font.isBlank() || baseTexture.isBlank() || texture.isBlank()) {
                throw new IllegalArgumentException("Glyph 的字符串字段不能为空");
            }
            if (baseHeight <= 0 || height <= 0 || originalWidth <= 0 || originalHeight <= 0
                || advance <= 0 || paddingRasterRows < 0) {
                throw new IllegalArgumentException("Glyph 几何必须为正数，padding 不得为负数：" + id);
            }
            int rasterHeight = originalHeight + paddingRasterRows;
            int rasterWidth = Math.max(1, Math.round((float) originalWidth * height / rasterHeight));
            if (height > 256 || rasterWidth > 256 || rasterHeight > 256) {
                throw new IllegalArgumentException("连续 HUD 单格不得超过 256x256：" + id);
            }
            if (rasterWidth + 1 != advance) {
                throw new IllegalArgumentException(
                    "连续 HUD 显示宽度与 advance 不一致：" + id
                        + "（显示宽度=" + rasterWidth + "，advance=" + advance + "）");
            }
        }

        /** 补行后的 PNG 栅格高度。 */
        public int rasterHeight() {
            return originalHeight + paddingRasterRows;
        }

        /** 按最终 provider 高度与补行后栅格比例计算客户端显示宽度。 */
        public int rasterWidth() {
            return Math.max(1, Math.round((float) originalWidth * height / rasterHeight()));
        }

        public String finalTexture() {
            return texture;
        }
    }

    private HudOverlayLayout() {
    }

    /** 基准字体的连续覆盖层别名。 */
    public static String continuousFont(String baseFont) {
        Objects.requireNonNull(baseFont, "baseFont");
        if (baseFont.isBlank()) {
            throw new IllegalArgumentException("基础字体不能为空");
        }
        return baseFont + "_continuous";
    }

    public static int minHotbarOffsetY(int scale) {
        return PackAssets.minHotbarOffsetY(scale);
    }

    public static int maxHotbarOffsetY(int scale) {
        return PackAssets.maxHotbarOffsetY(scale);
    }

    /** 牌面、头像、王冠、bot、counter 的连续覆盖层字形。 */
    public static List<Glyph> trickGlyphs(HudResourceRequest request) {
        Objects.requireNonNull(request, "request");
        List<Glyph> result = new ArrayList<>();
        addCards(result, request.cardOffsetDown());
        addAvatars(result, request.avatarOffsetDown());
        addCrowns(result, request.avatarOffsetDown());
        addBots(result, request.avatarOffsetDown());
        addCounters(result, request.counterOffsetDown());
        return List.copyOf(result);
    }

    /** 当前 hotbar scale 的三图标 Debug 覆盖层与选中框。 */
    public static List<Glyph> hotbarGlyphs(HudResourceRequest request) {
        Objects.requireNonNull(request, "request");
        int scale = request.hotbarScale();
        PackAssets.HotbarTier tier = PackAssets.hotbarTier(scale);
        List<Glyph> result = new ArrayList<>(PackAssets.HOTBAR_ICON_COUNT + 1);
        String[] names = {"egg", "water", "tomato"};
        for (int index = 0; index < PackAssets.HOTBAR_ICON_COUNT; index++) {
            int width = PackAssets.hotbarIconWidth(scale);
            int height = PackAssets.hotbarIconHeight(scale);
            result.add(glyph(
                "hotbar_" + names[index] + "_debug_s" + scale,
                tier.font(),
                tier.debugCodepoint() + index,
                PackAssets.hotbarIconTexture(index, scale),
                height,
                tier.baseAscent(),
                request.hotbarOffsetY(),
                width,
                height,
                width + 1
            ));
        }
        result.add(glyph(
            "hotbar_select_debug_s" + scale,
            tier.font(),
            tier.selectDebugCodepoint(),
            tier.selectTexture(),
            tier.selectHeight(),
            tier.baseAscent(),
            request.hotbarOffsetY(),
            tier.selectWidth(),
            tier.selectHeight(),
            tier.selectAdvance()
        ));
        return List.copyOf(result);
    }

    /** 四层事务统一消费的完整不可变字形列表。 */
    public static List<Glyph> glyphs(HudResourceRequest request) {
        Objects.requireNonNull(request, "request");
        List<Glyph> result = new ArrayList<>();
        result.addAll(trickGlyphs(request));
        result.addAll(hotbarGlyphs(request));
        return List.copyOf(result);
    }

    private static void addCards(List<Glyph> result, int offset) {
        for (int heightTier = 0; heightTier < PackAssets.cardGlyphHeightTierCount(); heightTier++) {
            int baseHeight = PackAssets.cardGlyphHeightAt(heightTier);
            String font = PackAssets.cardGlyphFont(heightTier, 0);
            for (String asset : cardAssetNames()) {
                String baseTexture = "muz:font/cards/" + asset + ".png";
                int codepoint = PackAssets.cardGlyphCharByAssetName(asset, heightTier, 0).codePointAt(0);
                result.add(glyph(
                    "card_" + asset + "_h" + baseHeight,
                    continuousFont(font),
                    codepoint,
                    baseTexture,
                    baseHeight,
                    baseHeight,
                    offset,
                    35,
                    53,
                    PackAssets.cardGlyphAdvance(heightTier)
                ));
            }
        }
    }

    private static void addAvatars(List<Glyph> result, int offset) {
        for (int scale : PackAssets.AVATAR_PIXEL_SCALE_TIERS) {
            for (int row = 0; row < PackAssets.AVATAR_OUTLINED_PIXELS; row++) {
                int baseHeight = (PackAssets.AVATAR_OUTLINED_PIXELS - row) * scale;
                result.add(glyph(
                    "avatar_px_" + scale + "_" + row,
                    continuousFont(PackAssets.avatarPixelFont(0)),
                    PackAssets.avatarPixelChar(scale, row, 0).codePointAt(0),
                    "muz:font/avatar/pixel_" + scale + "_" + row + ".png",
                    baseHeight,
                    baseHeight,
                    offset,
                    scale,
                    baseHeight,
                    scale + 1
                ));
            }
        }
    }

    private static void addCrowns(List<Glyph> result, int offset) {
        for (int scale : PackAssets.AVATAR_PIXEL_SCALE_TIERS) {
            for (int row = 0; row < PackAssets.AVATAR_CROWN_PIXELS; row++) {
                int baseHeight = (PackAssets.AVATAR_ROW_TOTAL_PIXELS - row) * scale;
                result.add(glyph(
                    "avatar_crown_" + scale + "_" + row,
                    continuousFont(PackAssets.avatarCrownFont(0)),
                    PackAssets.avatarCrownChar(scale, row, 0).codePointAt(0),
                    "muz:font/avatar/crown_" + scale + "_" + row + ".png",
                    baseHeight,
                    baseHeight,
                    offset,
                    scale,
                    baseHeight,
                    scale + 1
                ));
            }
        }
    }

    private static void addBots(List<Glyph> result, int offset) {
        addBot(result, "bot_avatar", null, PackAssets.BOT_AVATAR_HEIGHT, PackAssets.BOT_AVATAR_CHAR, offset);
        addBot(result, "bot_avatar_landlord", PlayerRole.LANDLORD,
            PackAssets.BOT_AVATAR_OUTLINED_HEIGHT, PackAssets.BOT_AVATAR_LANDLORD_CHAR, offset);
        addBot(result, "bot_avatar_farmer", PlayerRole.FARMER,
            PackAssets.BOT_AVATAR_OUTLINED_HEIGHT, PackAssets.BOT_AVATAR_FARMER_CHAR, offset);
    }

    private static void addBot(List<Glyph> result, String id, PlayerRole role, int baseHeight,
                               String baseChar, int offset) {
        result.add(glyph(
            id,
            continuousFont(PackAssets.BOT_AVATAR_FONT),
            baseChar.codePointAt(0),
            "muz:font/" + id + ".png",
            baseHeight,
            8,
            offset,
            role == null ? 16 : 18,
            role == null ? 16 : 18,
            PackAssets.botAvatarAdvanceWidth(role)
        ));
    }

    private static void addCounters(List<Glyph> result, int offset) {
        for (int scale : PackAssets.COUNTER_SCALE_TIERS) {
            PackAssets.CounterTier tier = PackAssets.counterTierForOffset(scale, offset);
            for (CardRank rank : CardRank.values()) {
                addCounter(result, "counter_label_" + rankSlug(rank) + "_s" + scale,
                    PackAssets.counterGlyphFont(scale, 0), PackAssets.counterRankChar(rank, scale, 0),
                    PackAssets.counterRankTexturePath(rank, scale),
                    tier.labelHeight(), scaleSigned(PackAssets.COUNTER_LABEL_ASCENT, scale),
                    scalePixel(PackAssets.COUNTER_LABEL_WIDTH, scale), tier.labelHeight(), tier.labelAdvance(), offset);
            }
            for (int digit = 0; digit < PackAssets.COUNTER_DIGIT_COUNT; digit++) {
                addCounter(result, "counter_digit_" + digit + "_s" + scale,
                    PackAssets.counterGlyphFont(scale, 0), PackAssets.counterDigitChar(digit, scale, 0),
                    PackAssets.counterDigitTexturePath(digit, scale),
                    scalePixel(PackAssets.COUNTER_DIGIT_HEIGHT, scale), scaleSigned(PackAssets.COUNTER_DIGIT_ASCENT, scale),
                    scalePixel(PackAssets.COUNTER_DIGIT_WIDTH, scale), scalePixel(PackAssets.COUNTER_DIGIT_HEIGHT, scale),
                    scalePixel(PackAssets.COUNTER_DIGIT_WIDTH, scale) + 1, offset);
            }
            for (boolean exhausted : new boolean[] {false, true}) {
                addCounter(result, "counter_frame_" + (exhausted ? "exhausted" : "normal") + "_s" + scale,
                    PackAssets.counterGlyphFont(scale, 0), PackAssets.counterFrameChar(exhausted, scale, 0),
                    PackAssets.counterFrameTexturePath(exhausted, scale),
                    scalePixel(PackAssets.COUNTER_FRAME_HEIGHT, scale), scaleSigned(PackAssets.COUNTER_FRAME_ASCENT, scale),
                    scalePixel(PackAssets.COUNTER_FRAME_WIDTH, scale), scalePixel(PackAssets.COUNTER_FRAME_HEIGHT, scale),
                    scalePixel(PackAssets.COUNTER_FRAME_WIDTH, scale) + 1, offset);
            }
        }
    }

    private static void addCounter(List<Glyph> result, String id, String font, String baseChar,
                                   String texture, int baseHeight, int baseAscent, int width,
                                   int originalHeight, int advance, int offset) {
        result.add(glyph(id, continuousFont(font), baseChar.codePointAt(0), texture,
            baseHeight, baseAscent, offset, width, originalHeight, advance));
    }

    private static Glyph glyph(String id, String font, int codepoint, String baseTexture,
                               int baseHeight, int baseAscent, int offset,
                               int originalWidth, int originalHeight, int advance) {
        int ascent = baseAscent - offset;
        int padding = 0;
        int height = baseHeight;
        if (ascent > baseHeight) {
            int gcd = gcd(baseHeight, originalHeight);
            int displayStep = baseHeight / gcd;
            int rasterStep = originalHeight / gcd;
            int units = (ascent - baseHeight + displayStep - 1) / displayStep;
            padding = units * rasterStep;
            height = baseHeight + units * displayStep;
        }
        if (height > 256 || originalHeight + padding > 256
            || Math.max(1, Math.round((float) originalWidth * height / (originalHeight + padding))) > 256) {
            throw new IllegalArgumentException("连续 HUD 单格补行后超过 256x256：" + id);
        }
        String texture = padding == 0
            ? baseTexture
            : "muz:font/continuous/" + safe(id) + "_o" + offset + ".png";
        return new Glyph(id, font, codepoint, baseTexture, baseHeight, baseAscent, offset,
            texture, height, ascent, padding, originalWidth, originalHeight, advance);
    }

    private static List<String> cardAssetNames() {
        List<String> names = new ArrayList<>();
        names.add("card_back");
        for (CardSuit suit : CardSuit.values()) {
            if (suit == CardSuit.JOKER) {
                continue;
            }
            for (CardRank rank : CardRank.values()) {
                if (rank == CardRank.SMALL_JOKER || rank == CardRank.BIG_JOKER) {
                    continue;
                }
                names.add(suitName(suit) + "_" + rankName(rank));
            }
        }
        names.add("small_joker");
        names.add("big_joker");
        names.sort(Comparator.naturalOrder());
        return names;
    }

    private static int scalePixel(int value, int scale) {
        return Math.max(1, Math.round(value * scale / 100.0f));
    }

    private static int scaleSigned(int value, int scale) {
        return Math.round(value * scale / 100.0f);
    }

    private static int gcd(int left, int right) {
        int a = Math.abs(left);
        int b = Math.abs(right);
        while (b != 0) {
            int next = a % b;
            a = b;
            b = next;
        }
        return a == 0 ? 1 : a;
    }

    private static String safe(String id) {
        return id.replaceAll("[^A-Za-z0-9_.-]", "_");
    }

    private static String suitName(CardSuit suit) {
        return switch (suit) {
            case CLUBS -> "clubs";
            case DIAMONDS -> "diamonds";
            case HEARTS -> "hearts";
            case SPADES -> "spades";
            case JOKER -> "joker";
        };
    }

    private static String rankName(CardRank rank) {
        return switch (rank) {
            case THREE -> "3";
            case FOUR -> "4";
            case FIVE -> "5";
            case SIX -> "6";
            case SEVEN -> "7";
            case EIGHT -> "8";
            case NINE -> "9";
            case TEN -> "10";
            case JACK -> "jack";
            case QUEEN -> "queen";
            case KING -> "king";
            case ACE -> "ace";
            case TWO -> "2";
            case SMALL_JOKER -> "small_joker";
            case BIG_JOKER -> "big_joker";
        };
    }

    private static String rankSlug(CardRank rank) {
        return switch (rank) {
            case THREE -> "3";
            case FOUR -> "4";
            case FIVE -> "5";
            case SIX -> "6";
            case SEVEN -> "7";
            case EIGHT -> "8";
            case NINE -> "9";
            case TEN -> "10";
            case JACK -> "j";
            case QUEEN -> "q";
            case KING -> "k";
            case ACE -> "a";
            case TWO -> "2";
            case SMALL_JOKER -> "small";
            case BIG_JOKER -> "big";
        };
    }
}
