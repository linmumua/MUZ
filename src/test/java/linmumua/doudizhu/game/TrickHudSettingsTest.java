package linmumua.doudizhu.game;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import linmumua.doudizhu.assets.PackAssets;
import linmumua.doudizhu.config.MuzYamlConfig;
import linmumua.doudizhu.model.CardRank;
import linmumua.doudizhu.model.CardSuit;
import linmumua.doudizhu.model.DoudizhuCard;
import org.junit.jupiter.api.Test;

/**
 * 守护出牌 HUD 的 config 解析。
 *
 * <p>这里真正要钉死的不是「getInt 能读到数」，而是【config 放行的值资源包里一定有对应
 * 字形】：头像倍数同时决定用哪一档 ascent 字形，而那些字形是构建期按当前 profile 的显式档位预生成的。
 * 一旦有人把范围写死成字面量、或改了 PackAssets 的范围而没同步，玩家看到的是整片豆腐块，
 * 编译和覆盖率都发现不了。
 */
class TrickHudSettingsTest {
    /** 不落磁盘的空 config，section 路径用点号写。 */
    private static MuzYamlConfig configWith(Map<String, Object> values) {
        MuzYamlConfig config = MuzYamlConfig.empty(Path.of("build", "tmp", "trick-hud-test.yml"));
        values.forEach(config::set);
        return config;
    }

    private final List<String> warnings = new ArrayList<>();

    /**
     * config 里没写 trick-hud 段时（老配置文件升级上来就是这样），必须拿到一套能用的默认值，
     * 而不是 0 或抛异常——否则升级插件的服主会看到 HUD 直接坏掉。
     */
    @Test
    void missingSectionFallsBackToWorkingDefaults() {
        TrickHudService.Settings settings = TrickHudService.readSettings(configWith(Map.of()), warnings::add);

        assertTrue(settings.enabled(), "缺省应当是开启的，否则老配置升级后 HUD 会静默消失");
        assertTrue(settings.cardStep() > 0, "牌间距必须为正，否则牌会倒着排");
        assertTrue(
            PackAssets.avatarPixelScaleTierOf(settings.avatarScale()) >= 0,
            "默认倍数必须落在资源包已生成的离散档位内，否则默认配置就是豆腐块"
        );
        assertTrue(warnings.isEmpty(), "默认值不该触发任何警告：" + warnings);
    }

    /**
     * 三个可调量都要真的被读取，否则「config 可调」只是写了个没人看的注释。
     *
     * <p>【为什么要一起写 avatar-offset-down】：头像行偏移拆成独立配置项之后，改 avatar-scale
     * 不会再自动带着头像行走。9 倍头像【行整体】高 12*9 = 108（描边 10 行 + 王冠凸出 2 行），
     * 两行相接需要 50 + 108 = 158，只写 avatar-scale=9 而留着默认的 122 就是真实重叠，
     * 会（且应该）触发重叠警告。
     * 所以这里把配套值一起写上，才能保持「全是合法值 → 一条警告都不该有」这个断言强度。
     */
    @Test
    void configuredValuesAreActuallyUsed() {
        TrickHudService.Settings settings = TrickHudService.readSettings(
            configWith(Map.of(
                "trick-hud.avatar-scale", 6,
                "trick-hud.avatar-gap", 13,
                "trick-hud.card-step", 30,
                "trick-hud.avatar-offset-down", 122
            )),
            warnings::add
        );

        assertEquals(6, settings.avatarScale());
        assertEquals(13, settings.avatarGap());
        assertEquals(30, settings.cardStep());
        assertEquals(122, PackAssets.avatarDownOffsetAt(settings.avatarDownOffsetTier()));
        assertTrue(warnings.isEmpty(), "全是合法值且两行不重叠，不该有警告：" + warnings);
    }

    /**
     * 缺省缩放档仍是「1:1」，缺省下移档则是实测调优后的 50 像素那一档。
     *
     * <p>下移量的默认值是有意从 0 改成 50 的：紧贴屏幕顶部的 HUD 会挡住准星，
     * 测试服实测下来 50 像素才是不挡视线的位置。所以这里不再断言「和加配置项之前一样」，
     * 而是断言「等于那个被选中的档位」——档位选错（比如把像素值当档序号用）仍会失败。
     *
     * <p>缩放档不同：53 像素就是贴图原始尺寸，没有更好的默认可选，所以保持 1:1。
     */
    @Test
    void missingScaleAndOffsetUseTheTunedDefaultTiers() {
        TrickHudService.Settings settings = TrickHudService.readSettings(configWith(Map.of()), warnings::add);

        // 【断言的是高度值而不是档序号】：档位改成按范围生成后，索引 0 是区间端点（56）
        // 而不是默认值，默认值改由显式常量 DEFAULT_CARD_HEIGHT 给。断言档序号会把
        // 「默认值正确」和「表的排列顺序」绑在一起，改一下生成顺序就假红。
        assertEquals(53, PackAssets.cardGlyphHeightAt(settings.heightTier()),
            "缺省牌面高必须是贴图原始高度 53（1:1 不插值）");
        assertEquals(PackAssets.DEFAULT_CARD_HEIGHT, PackAssets.cardGlyphHeightAt(settings.heightTier()),
            "缺省档必须与 DEFAULT_CARD_HEIGHT 一致，否则 config 缺键时会静默换一个高度");
        assertEquals(50, PackAssets.cardGlyphDownOffsetAt(settings.downOffsetTier()),
            "缺省下移量必须是实测调优的 50 像素；写成别的值会让 HUD 挡住准星或沉得太低");
        assertEquals(0, settings.offsetX(), "缺省不左右偏移");
        assertTrue(warnings.isEmpty(), "默认值不该触发任何警告：" + warnings);
    }

    /**
     * config 里写的是像素值，Settings 里存的是档序号，这一步换算必须真的发生。
     *
     * <p>直接把像素值当档序号用（两者都是小整数，编译不会报错）会静默取到别的档：
     * 比如写 offset-down: 4 会被当成第 4 档也就是下移 16 像素。
     */
    @Test
    void pixelValuesAreTranslatedIntoTierIndexes() {
        TrickHudService.Settings settings = TrickHudService.readSettings(
            configWith(Map.of(
                "trick-hud.card-height", PackAssets.cardGlyphHeightAt(0),
                "trick-hud.offset-down", PackAssets.cardGlyphDownOffsetAt(0),
                "trick-hud.offset-x", -37
            )),
            warnings::add
        );

        assertEquals(PackAssets.cardGlyphHeightAt(0), PackAssets.cardGlyphHeightAt(settings.heightTier()),
            "card-height 应当选到当前资源包实际生成的档位");
        assertEquals(PackAssets.cardGlyphDownOffsetAt(0), PackAssets.cardGlyphDownOffsetAt(settings.downOffsetTier()),
            "offset-down 应当选到当前资源包实际生成的档位");
        // offset-x 是像素而不是档位：它靠负空格实现，负数（左移）也必须原样透传。
        assertEquals(-37, settings.offsetX(), "offset-x 必须原样透传，左移是合法用法");
        assertTrue(warnings.isEmpty(), "全是合法值，不该有警告：" + warnings);
    }

    /**
     * 缩放与偏移【范围内就近吸附且不警告，越界才钳边界并警告】。
     *
     * <p>height/ascent 是烧进资源包 images.yml 的，运行时改不了，所以取值仍限于预生成档位。
     * 但档位现在很密（card-height 步长 1、offset-down 步长 2），处理方式随之改了：
     *
     * <p><b>这条测试整体重写过。</b>旧契约是「非档位值 → 回退默认 + 必有一条警告」。
     * 那在档位稀疏时合理（合法值只有 5 个），档位放开后就变成了折磨：服主每次微调都被弹回
     * 出厂值。新契约是范围内静默吸附 —— 误差最多 1 像素，肉眼看不出；越界仍必须警告，
     * 因为那是真的没被满足（配 999 只能给 56，差得远）。
     */
    @Test
    void 范围内的值就近吸附_越界才警告() {
        // 只使用当前资源包实际生成的牌高档位，避免测试假设未生成的中间档。
        for (int heightTier = 0; heightTier < PackAssets.cardGlyphHeightTierCount(); heightTier++) {
            int height = PackAssets.cardGlyphHeightAt(heightTier);
            warnings.clear();
            TrickHudService.Settings settings = TrickHudService.readSettings(
                configWith(Map.of("trick-hud.card-height", height)),
                warnings::add
            );
            assertEquals(height, PackAssets.cardGlyphHeightAt(settings.heightTier()),
                "card-height=" + height + " 在范围内且步长 1，必须原样取到");
            assertTrue(warnings.isEmpty(), "范围内不该警告：" + warnings);
        }

        // 越界：必须钳到边界【并且】留警告 —— 静默钳边界会让服主以为配置没生效。
        for (int badHeight : new int[] {0, -53, 999}) {
            warnings.clear();
            TrickHudService.readSettings(
                configWith(Map.of("trick-hud.card-height", badHeight)), warnings::add);
            assertEquals(1, warnings.size(), "card-height=" + badHeight + " 越界必须留一条警告：" + warnings);
            assertTrue(warnings.getFirst().contains("card-height"), "警告要指名是哪一项：" + warnings);
        }

        // offset-down 步长 2：奇数值必须吸到相邻偶数档，且【不警告】。
        for (int odd : new int[] {1, 3, 27}) {
            warnings.clear();
            TrickHudService.Settings settings = TrickHudService.readSettings(
                configWith(Map.of("trick-hud.offset-down", odd)), warnings::add);
            int resolved = PackAssets.cardGlyphDownOffsetAt(settings.downOffsetTier());
            assertEquals(PackAssets.cardGlyphDownOffsetAt(PackAssets.nearestCardGlyphDownOffsetTier(odd)), resolved,
                "offset-down=" + odd + " 必须吸附到当前资源包最近档位");
            assertTrue(warnings.stream().noneMatch(w -> w.contains("offset-down")),
                "范围内的奇数值不该警告，误差只有 1 像素：" + warnings);
        }

        for (int badOffset : new int[] {-4, 200}) {
            warnings.clear();
            TrickHudService.readSettings(
                configWith(Map.of("trick-hud.offset-down", badOffset)), warnings::add);
            assertTrue(warnings.stream().anyMatch(w -> w.contains("offset-down")),
                "offset-down=" + badOffset + " 越界必须警告：" + warnings);
        }
    }

    /**
     * readSettings 放行的每一个【牌行】档位，PackAssets 都必须能给出对应的牌面字形。
     *
     * <p>和下面那个头像倍数的测试同一个目的：把「config 放行区间」和「资源包预生成区间」
     * 焊死。放行了一档而资源包里没有它，玩家看到的就是整片豆腐块。
     *
     * <p>【拆表后这条只管牌】：头像与 bot 的字形改由头像那张独立档位表覆盖，用牌行的档序号
     * 去取头像字形是串表（同一个下标在两张表里是不同像素值），已挪到
     * {@link #everyAcceptedAvatarTierHasGlyphs}。这里把 avatar-offset-down 固定成最深那一档，
     * 是为了让任何牌行偏移都不会触发重叠警告，把这条测试的关注点严格限定在「档位有没有字形」。
     */
    @Test
    void everyAcceptedTierHasGlyphs() {
        int deepestAvatarOffset = PackAssets.avatarDownOffsetAt(PackAssets.avatarDownOffsetTierCount() - 1);
        for (int heightTier = 0; heightTier < PackAssets.cardGlyphHeightTierCount(); heightTier++) {
            for (int downTier = 0; downTier < PackAssets.cardGlyphDownOffsetTierCount(); downTier++) {
                warnings.clear();
                TrickHudService.Settings settings = TrickHudService.readSettings(
                    configWith(Map.of(
                        "trick-hud.card-height", PackAssets.cardGlyphHeightAt(heightTier),
                        "trick-hud.offset-down", PackAssets.cardGlyphDownOffsetAt(downTier),
                        "trick-hud.avatar-offset-down", deepestAvatarOffset
                    )),
                    warnings::add
                );

                assertEquals(heightTier, settings.heightTier());
                assertEquals(downTier, settings.downOffsetTier());
                assertTrue(warnings.isEmpty(), "预生成的档位不该被拒：" + warnings);

                int checkedHeightTier = heightTier;
                int checkedDownTier = downTier;
                assertDoesNotThrow(
                    () -> PackAssets.cardGlyphChar(
                        new DoudizhuCard(0, CardRank.THREE, CardSuit.SPADES), checkedHeightTier, checkedDownTier),
                    "牌行档位 (" + heightTier + "," + downTier + ") 被 config 放行了，但字形取不出来"
                );
            }
        }
    }

    /**
     * readSettings 放行的每一个【头像行】档位，头像字形和 bot 兜底图标都必须取得出来。
     *
     * <p>这是上一条的头像侧对应物，拆表后必须单独有一条：头像与 bot 的码位公式用的是
     * {@code avatarDownOffsetTierCount()}，牌那条测试完全覆盖不到。少了这条，头像表就成了
     * 「config 放行但没人验证字形存在」的一侧 —— 服主写一个表里有、但插件取不出来的值时，
     * 表现是头像整片豆腐块。
     *
     * <p>bot 图标一并验：它画在头像行，档位必须跟头像表。跟错表在这里会直接越界抛异常。
     *
     * <p>不断言「无警告」：浅档（0/40/50 配默认 6 倍头像）本来就会与牌行重叠并留警告，
     * 那是「完全自由」方案的预期行为。这里只断言没有【回退】警告 —— 回退才意味着档位没被放行。
     */
    @Test
    void everyAcceptedAvatarTierHasGlyphs() {
        for (int downTier = 0; downTier < PackAssets.avatarDownOffsetTierCount(); downTier++) {
            warnings.clear();
            TrickHudService.Settings settings = TrickHudService.readSettings(
                configWith(Map.of(
                    "trick-hud.avatar-offset-down", PackAssets.avatarDownOffsetAt(downTier)
                )),
                warnings::add
            );

            assertEquals(
                downTier, settings.avatarDownOffsetTier(),
                "预生成的头像偏移档 " + PackAssets.avatarDownOffsetAt(downTier) + " 被拒了：" + warnings
            );
            assertTrue(
                warnings.stream().noneMatch(message -> message.contains("不是预生成")),
                "预生成的头像档位不该触发回退：" + warnings
            );

            int checkedDownTier = downTier;
            assertDoesNotThrow(
                () -> {
                    PackAssets.avatarPixelChar(PackAssets.AVATAR_PIXEL_MIN_SCALE, 0, checkedDownTier);
                    PackAssets.avatarPixelChar(PackAssets.AVATAR_PIXEL_MAX_SCALE,
                        PackAssets.AVATAR_OUTLINED_PIXELS - 1, checkedDownTier);
                    PackAssets.botAvatarChar(PlayerRole.LANDLORD, checkedDownTier);
                },
                "头像行档位 " + downTier + " 被 config 放行了，但字形取不出来"
            );
        }
    }

    /**
     * 这是本文件的核心：readSettings 放行的每一个倍数，PackAssets 都必须能给出全部 8 行字形。
     *
     * <p>换句话说它把「config 校验区间」和「资源包预生成区间」焊死在一起。谁改了一边
     * 没改另一边，这里就会红。
     */
    @Test
    void everyAcceptedAvatarScaleHasGlyphsInThePack() {
        for (int scale : PackAssets.AVATAR_PIXEL_SCALE_TIERS) {
            // 每个倍数都配上它对应的头像行偏移（默认牌行 50 + 盒高 10*scale），
            // 否则倍数一变就会真的重叠、触发重叠警告，把末尾那条「不该有警告」的断言污染掉。
            // 顺带验证每个 profile 档位都能在头像档位表里找到精确相接的那一档。
            TrickHudService.Settings settings = TrickHudService.readSettings(
                configWith(Map.of(
                    "trick-hud.avatar-scale", scale,
                    "trick-hud.avatar-offset-down", PackAssets.avatarRowDownOffset(50, scale)
                )),
                warnings::add
            );
            assertEquals(scale, settings.avatarScale(), "范围内的倍数被误判成越界了");

            int accepted = settings.avatarScale();
            for (int row = 0; row < PackAssets.AVATAR_HEAD_PIXELS; row++) {
                int currentRow = row;
                assertDoesNotThrow(
                    () -> PackAssets.avatarPixelChar(accepted, currentRow),
                    "config 放行了倍数 " + accepted + " 但资源包没有第 " + currentRow + " 行的字形"
                );
            }
        }
        assertTrue(warnings.isEmpty(), "区间内的倍数不该触发警告：" + warnings);
    }

    /** profile 中未声明的倍数必须被拒 + 回退 + 出警告，不能把稀疏档位误当连续区间。 */
    @Test
    void avatarScaleMissingFromSparseProfileIsRejectedLoudly() {
        int missing = PackAssets.AVATAR_PIXEL_SCALE_TIERS[0] + 1;
        while (PackAssets.avatarPixelScaleTierOf(missing) >= 0) {
            missing++;
        }
        TrickHudService.Settings settings = TrickHudService.readSettings(
            configWith(Map.of("trick-hud.avatar-scale", missing)),
            warnings::add
        );
        assertEquals(
            TrickHudService.readSettings(configWith(Map.of()), warnings::add).avatarScale(),
            settings.avatarScale());
        assertEquals(1, warnings.size(), "稀疏 profile 的缺档必须恰好发一条警告：" + warnings);
    }

    /** 越界倍数必须被拒 + 回退 + 出警告，三者缺一都会让人对着豆腐块猜半天。 */
    @Test
    void avatarScaleOutOfPackRangeIsRejectedLoudly() {
        int tooSmall = PackAssets.AVATAR_PIXEL_MIN_SCALE - 1;
        TrickHudService.Settings low = TrickHudService.readSettings(
            configWith(Map.of("trick-hud.avatar-scale", tooSmall)),
            warnings::add
        );
        assertTrue(
            low.avatarScale() >= PackAssets.AVATAR_PIXEL_MIN_SCALE,
            "越界值被照用了，头像会变豆腐块"
        );
        assertEquals(1, warnings.size(), "必须恰好发一条警告：" + warnings);
        assertTrue(warnings.get(0).contains("avatar-scale"), "警告要点明是哪个配置项：" + warnings.get(0));

        warnings.clear();
        int tooLarge = PackAssets.AVATAR_PIXEL_MAX_SCALE + 1;
        TrickHudService.Settings high = TrickHudService.readSettings(
            configWith(Map.of("trick-hud.avatar-scale", tooLarge)),
            warnings::add
        );
        assertTrue(high.avatarScale() <= PackAssets.AVATAR_PIXEL_MAX_SCALE, "越界值被照用了");
        assertEquals(1, warnings.size(), "必须恰好发一条警告：" + warnings);
    }

    /**
     * 牌间距为 0 或负数会让整手牌叠成一张、甚至从右往左排，属于纯笔误，必须回退。
     */
    @Test
    void nonPositiveCardStepIsRejectedLoudly() {
        for (int badStep : List.of(0, -22)) {
            warnings.clear();
            TrickHudService.Settings settings = TrickHudService.readSettings(
                configWith(Map.of("trick-hud.card-step", badStep)),
                warnings::add
            );

            assertTrue(settings.cardStep() > 0, "牌间距 " + badStep + " 被照用了，整手牌会叠成一张");
            assertEquals(1, warnings.size(), "必须恰好发一条警告：" + warnings);
            assertTrue(warnings.get(0).contains("card-step"), "警告要点明配置项：" + warnings.get(0));
        }
    }

    /**
     * 负的 avatar-gap 是【有意】允许的（让第一张牌压在头像上做紧凑排版），
     * 不能跟着牌间距一起被当成笔误拦掉。
     */
    @Test
    void negativeAvatarGapIsAllowedOnPurpose() {
        TrickHudService.Settings settings = TrickHudService.readSettings(
            configWith(Map.of("trick-hud.avatar-gap", -6)),
            warnings::add
        );

        assertEquals(-6, settings.avatarGap(), "负间距是刻意支持的用法，不该被回退");
        assertTrue(warnings.isEmpty(), "负间距不是错误，不该警告：" + warnings);
    }

    /**
     * 【两行垂直不重叠】头像行的偏移档必须比牌行深至少一个头像字形盒高。
     *
     * <p>这是两行布局的全部几何依据，也是这次踩的坑所在：位图字形占据基线上方
     * {@code [ascent - height, ascent]}，两者都取 {@code ascent = height - d}，
     * 于是字形盒是「基线下方 d 到基线上方 height - d」。头像顶边在基线下方
     * {@code d_头像 - 10*scale}，要求它不高于牌底（基线下方 {@code d_牌}）。
     *
     * <p>守的风险：头像盒高是 {@code 10 * scale} 而不是 {@code 8 * scale}。字形恒按
     * {@code AVATAR_OUTLINED_PIXELS}(=10) 行预生成，与运行期 avatar-outline 开关无关 ——
     * 关掉描边只是不画最外那圈像素，字形度量一个都没变。按 48（可见的 8x8 脸）算的话
     * 头像顶边会压进牌里 12 像素，而这在任何字符串断言里都看不出来。
     */
    @Test
    void 头像行比牌行沉得足够深以免两行重叠() {
        TrickHudService.Settings settings = TrickHudService.readSettings(configWith(Map.of()), warnings::add);

        int cardDown = PackAssets.cardGlyphDownOffsetAt(settings.downOffsetTier());
        // 头像行查【头像自己那张表】。拆表后这里不能再用 cardGlyphDownOffsetAt：
        // 同一个下标在两张表里是完全不同的像素值，串表读出来的数是假的。
        int avatarDown = PackAssets.avatarDownOffsetAt(settings.avatarDownOffsetTier());
        // 【按 AVATAR_ROW_TOTAL_PIXELS(12) 算，不是 AVATAR_OUTLINED_PIXELS(10)】：
        // 王冠向上凸出 2 行也占位置，按 10 算会漏报地主王冠压进牌行。
        int avatarBoxHeight = PackAssets.AVATAR_ROW_TOTAL_PIXELS * settings.avatarScale();

        // 锁死默认组合的具体数值：改了任何一个都要回来重新核算垂直几何。
        assertEquals(50, cardDown, "牌行默认下移 50 像素（config 的 offset-down）");
        assertEquals(6, settings.avatarScale(), "大头像默认 6 倍");
        assertEquals(72, avatarBoxHeight, "6 倍头像【行整体】是 12*6=72 像素高（描边 10 行 + 王冠 2 行），不是 10*6=60 也不是 8*6=48");
        assertEquals(122, avatarDown, "头像行必须落在 122 那一档（= 50 + 12*6），两行刚好相接");

        assertTrue(
            avatarDown - cardDown >= avatarBoxHeight,
            "头像行只比牌行深 " + (avatarDown - cardDown) + " 像素，不足头像盒高 "
                + avatarBoxHeight + " 像素，头像顶边会压进牌里"
        );
        assertTrue(warnings.isEmpty(), "默认组合必须有预生成的头像行档位，不该有警告：" + warnings);
    }

    /**
     * 头像行偏移写了没预生成的值时，必须回退【并留警告】，不能静默降级。
     *
     * <p>【这条的语义随「档位放开」再次改写】：先前是「非档位值 → 回退默认档 + 警告并枚举
     * 合法值」；现在档位密到步长 2，改成【范围内就近吸附且不警告】。
     *
     * <p>为什么这样更对：回退到默认值会让服主觉得「我配了没用」—— 他写 105 想要的是
     * 「105 附近」，而不是回到 122。吸附到 104 的误差是 1 像素，肉眼看不出。
     * 而枚举两百多个合法值的警告是天书，写了也没人看。
     *
     * <p>守的风险：如果实现退回「非档位值就回退默认」，服主每次微调都会被弹回出厂位置，
     * 且看不出原因。这条测试就是钉住「任意整数都能配」这个承诺。
     */
    @Test
    void 头像行偏移取任意整数时就近吸附且不警告() {
        // 105 不一定命中当前资源包的头像偏移档位，但仍应按实际集合就近吸附。
        TrickHudService.Settings settings = TrickHudService.readSettings(
            configWith(Map.of("trick-hud.avatar-offset-down", 105)),
            warnings::add
        );

        int resolved = PackAssets.avatarDownOffsetAt(settings.avatarDownOffsetTier());
        assertEquals(
            PackAssets.avatarDownOffsetAt(PackAssets.nearestAvatarDownOffsetTier(105)), resolved,
            "105 必须吸附到当前资源包最近的头像偏移档位"
        );
        assertTrue(
            warnings.stream().noneMatch(w -> w.contains("avatar-offset-down 超出")),
            "范围内的值不该刷越界警告，误差只有 1 像素：" + warnings
        );
    }

    /**
     * 越界【必须】警告：那是真的没被满足。
     *
     * <p>与上一条是配套的一对：范围内静默是因为误差看不出来，越界不静默是因为服主
     * 配 500 却只得到 400，差了 100 像素 —— 不说他会一直以为配置没生效。
     */
    @Test
    void 头像行偏移越界时钳到边界并留警告() {
        TrickHudService.Settings settings = TrickHudService.readSettings(
            configWith(Map.of("trick-hud.avatar-offset-down", 500)),
            warnings::add
        );

        assertEquals(
            PackAssets.avatarDownOffsetMax(),
            PackAssets.avatarDownOffsetAt(settings.avatarDownOffsetTier()),
            "越界必须钳到最大档"
        );
        assertTrue(
            warnings.stream().anyMatch(w -> w.contains("avatar-offset-down") && w.contains("超出")),
            "越界必须留警告，否则服主以为配置没生效：" + warnings
        );
    }

    /**
     * 【这次改动的全部意义】两行位置必须真的能各自独立调：动一边不许影响另一边。
     *
     * <p>守的风险：拆表最容易出的错不是编译不过，而是「看起来拆了、实际上还联动」——
     * 比如 readSettings 里把头像档位又写回由 offset-down 推导，或者两个字段读了同一个 config 键。
     * 那种实现下服主改 avatar-offset-down 会毫无反应（或者改 offset-down 时头像莫名跟着跳），
     * 而所有「档位有字形」「码位对齐」的测试都照旧全绿。
     *
     * <p>所以这里直接断言矩阵的独立性：固定一边、扫另一边，被固定那边的档位一个像素都不许动。
     */
    @Test
    void 两行偏移可以各自独立调整() {
        // 固定头像行在最深那一档（150，任何牌行偏移都不会与它重叠），扫牌行全部档位。
        int fixedAvatarOffset = PackAssets.avatarDownOffsetAt(PackAssets.avatarDownOffsetTierCount() - 1);
        for (int cardTier = 0; cardTier < PackAssets.cardGlyphDownOffsetTierCount(); cardTier++) {
            warnings.clear();
            int cardOffset = PackAssets.cardGlyphDownOffsetAt(cardTier);
            TrickHudService.Settings settings = TrickHudService.readSettings(
                configWith(Map.of(
                    "trick-hud.offset-down", cardOffset,
                    "trick-hud.avatar-offset-down", fixedAvatarOffset
                )),
                warnings::add
            );

            assertEquals(cardOffset, PackAssets.cardGlyphDownOffsetAt(settings.downOffsetTier()),
                "牌行偏移没被读进去");
            assertEquals(
                fixedAvatarOffset, PackAssets.avatarDownOffsetAt(settings.avatarDownOffsetTier()),
                "改 offset-down=" + cardOffset + " 把头像行也带动了 —— 两行必须独立，这是本次改动的全部意义"
            );
        }

        // 反向：固定牌行在默认档，扫头像行全部档位。
        for (int avatarTier = 0; avatarTier < PackAssets.avatarDownOffsetTierCount(); avatarTier++) {
            warnings.clear();
            int avatarOffset = PackAssets.avatarDownOffsetAt(avatarTier);
            TrickHudService.Settings settings = TrickHudService.readSettings(
                configWith(Map.of(
                    "trick-hud.offset-down", 50,
                    "trick-hud.avatar-offset-down", avatarOffset
                )),
                warnings::add
            );

            assertEquals(
                50, PackAssets.cardGlyphDownOffsetAt(settings.downOffsetTier()),
                "改 avatar-offset-down=" + avatarOffset + " 把牌行也带动了 —— 两行必须独立"
            );
            assertEquals(avatarOffset, PackAssets.avatarDownOffsetAt(settings.avatarDownOffsetTier()),
                "头像行偏移 " + avatarOffset + " 没被读进去，avatar-offset-down 这个键等于没生效");
        }
    }

    /**
     * 【默认组合仍然精确相接】默认配置下牌底与头像顶严格零重叠、零缝隙。
     *
     * <p>和上面那条「沉得足够深」的区别：那条只要求「不重叠」（差值 &gt;= 盒高），这条要求
     * 【恰好等于】。默认值是出厂观感，多一像素缝隙或少一像素重叠都要有人知道。
     *
     * <p>守的风险：拆成独立配置项之后，默认值成了一个可以随手改的常量。谁把
     * DEFAULT_AVATAR_OFFSET_DOWN 改成 120「留点空隙」，或者把它写死成字面量之后又改了
     * DEFAULT_OFFSET_DOWN，这条会红；只验「不重叠」的测试不会。
     */
    @Test
    void 默认组合两行精确相接且不留缝隙() {
        TrickHudService.Settings settings = TrickHudService.readSettings(configWith(Map.of()), warnings::add);

        int cardDown = PackAssets.cardGlyphDownOffsetAt(settings.downOffsetTier());
        int avatarDown = PackAssets.avatarDownOffsetAt(settings.avatarDownOffsetTier());
        // 【按 AVATAR_ROW_TOTAL_PIXELS(12) 算】：王冠向上凸出那 2 行也占位置。
        int boxHeight = PackAssets.AVATAR_ROW_TOTAL_PIXELS * settings.avatarScale();

        assertEquals(50, cardDown, "牌行默认下移 50 像素");
        assertEquals(6, settings.avatarScale(), "大头像默认 6 倍");
        assertEquals(122, avatarDown, "头像行默认落在 122 那一档（盒高含王冠凸出的 2 行）");
        assertEquals(
            boxHeight, avatarDown - cardDown,
            "默认组合下头像行顶边必须正好压在牌底上：两者之差应恰好等于头像行整体高 " + boxHeight
                + "（12*scale，含王冠凸出的 2 行）；差值大了两行之间有缝隙，小了压进牌里"
        );
        assertTrue(warnings.isEmpty(), "默认组合不该有任何警告：" + warnings);
    }

    /**
     * 【随包发布的 config.yml 必须与代码默认一致】上面那条走的是 {@code configWith(Map.of())}，
     * 读的是代码里的 fallback 默认值，<b>完全不碰</b> src/main/resources/config.yml。
     *
     * <p>所以只有上面那条时，config.yml 里写着的值可以任意漂移而全树照绿 —— 盒高基数从
     * 10 改到 12（王冠凸出 2 行）时相接点从 110 变成 122，如果漏改 config.yml，
     * 服主拿到的默认配置一进服就会触发重叠警告、地主王冠压在牌上，而测试一片绿。
     *
     * <p>这条直接读随包发布的那份 config.yml 文本，把它和 {@code avatarRowDownOffset} 算出来的
     * 相接点比对。注释里的数字不管（注释错了不影响运行），只钉真正会被加载的那三行值。
     */
    @Test
    void 随包发布的configYml默认值与代码默认一致() throws java.io.IOException {
        String yaml = java.nio.file.Files.readString(
            java.nio.file.Path.of("src/main/resources/config.yml"));

        int cardDown = readTrickHudInt(yaml, "offset-down");
        int scale = readTrickHudInt(yaml, "avatar-scale");
        int avatarDown = readTrickHudInt(yaml, "avatar-offset-down");

        assertEquals(
            PackAssets.avatarRowDownOffset(cardDown, scale), avatarDown,
            "config.yml 里的 avatar-offset-down=" + avatarDown + " 与 offset-down=" + cardDown
                + " / avatar-scale=" + scale + " 算出的相接点 "
                + PackAssets.avatarRowDownOffset(cardDown, scale)
                + " 不一致。服主拿到的默认配置一进服就会触发重叠警告、地主王冠压在牌行上。"
                + "盒高基数改动（8→10→12）时这三行必须一起改"
        );
        assertTrue(
            PackAssets.nearestAvatarDownOffsetTier(avatarDown) >= 0,
            "config.yml 的 avatar-offset-down=" + avatarDown + " 在资源包档位表里找不到对应档"
        );
        assertTrue(
            PackAssets.cardGlyphHeightTierOf(readTrickHudInt(yaml, "card-height")) >= 0,
            "config.yml 的 card-height 在资源包档位表里找不到对应档"
        );
        // avatar-scale 走的是「越界回退成 6」而不是就近吸附（TrickHudService.readSettings），
        // 所以随包默认值必须自己就落在预生成区间内，否则默认配置一进服就先吃一条回退警告。
        assertTrue(
            PackAssets.avatarPixelScaleTierOf(scale) >= 0,
            "config.yml 的 avatar-scale=" + scale + " 不在资源包预生成档位 "
                + java.util.Arrays.toString(PackAssets.AVATAR_PIXEL_SCALE_TIERS)
                + " 中。它不会被吸附，会直接回退成 6 并留警告"
        );
    }

    /**
     * 从 config.yml 文本里取 trick-hud 段某一项的值。
     *
     * <p>只认「行首两个空格 + 键名」的那一行，避开注释行与其他段里的同名键。
     */
    private static int readTrickHudInt(String yaml, String key) {
        java.util.regex.Matcher matcher = java.util.regex.Pattern
            .compile("^ {2}" + java.util.regex.Pattern.quote(key) + ": *(-?\\d+)",
                java.util.regex.Pattern.MULTILINE)
            .matcher(yaml);
        assertTrue(matcher.find(), "config.yml 的 trick-hud 段里找不到 " + key + " 这一行");
        return Integer.parseInt(matcher.group(1));
    }

    /**
     * 【重叠必须留警告】两行配重叠时必须出警告，这是「完全自由」方案唯一的防线。
     *
     * <p>拆表前头像行档位是推导出来的，结构上保证不重叠；拆表后两行各自随便调，
     * 结构保证没了，用户明确接受用警告替代。那么这条警告就必须真的存在 ——
     * 静默重叠的表现是「头像糊在牌上」，服主完全没法把这个现象和自己改的那行配置联系起来。
     *
     * <p>盒高按 10*scale 算也在这里守：{@code avatar-offset-down = offset-down + 8*scale}
     * 这个组合（按错的 8 算出来的「刚好相接」）实际重叠 2*scale 像素，必须报警。
     * 若有人把重叠判据改回 8，这一格就会静默放过。
     */
    @Test
    void 两行重叠时必须留警告() {
        record Case(String name, int cardOffset, int avatarOffset, int scale) {
        }
        List<Case> overlapping = List.of(
            new Case("小头像档位重叠", PackAssets.cardGlyphDownOffsetAt(0), 0,
                PackAssets.AVATAR_PIXEL_SCALE_TIERS[0]),
            new Case("大头像档位重叠", PackAssets.cardGlyphDownOffsetAt(0), 0,
                PackAssets.AVATAR_PIXEL_SCALE_TIERS[1]),
            new Case("牌行下移后仍重叠", PackAssets.cardGlyphDownOffsetMax(), 0,
                PackAssets.AVATAR_PIXEL_SCALE_TIERS[0])
        );

        for (Case testCase : overlapping) {
            warnings.clear();
            TrickHudService.readSettings(
                configWith(Map.of(
                    "trick-hud.offset-down", testCase.cardOffset(),
                    "trick-hud.avatar-offset-down", testCase.avatarOffset(),
                    "trick-hud.avatar-scale", testCase.scale()
                )),
                warnings::add
            );

            assertEquals(
                1, warnings.size(),
                testCase.name() + "：重叠组合（牌行 " + testCase.cardOffset() + " / 头像行 "
                    + testCase.avatarOffset() + " / " + testCase.scale() + " 倍）必须恰好留一条警告，"
                    + "静默重叠时服主只会看到头像糊在牌上、无从排查：" + warnings
            );
            assertTrue(
                warnings.getFirst().contains("avatar-offset-down"),
                testCase.name() + "：警告要指名该改哪一项：" + warnings
            );
            // 警告必须把「应该设成多少」直接写出来，否则服主还得自己算 10*scale。
            int required = PackAssets.avatarRowDownOffset(testCase.cardOffset(), testCase.scale());
            assertTrue(
                warnings.getFirst().contains(String.valueOf(required)),
                testCase.name() + "：警告里必须写出精确相接需要的值 " + required + "：" + warnings
            );
            // 【必须是「建议值」而不只是「差多少像素」】：档位放开后有两百多档，枚举合法值是天书，
            // 所以这条警告的全部价值就在于给出一个可直接抄进 config 的数。上面那条 contains(required)
            // 单独看是不够的 —— required 也出现在「压进牌里 N 像素」那半句里，把建议语整句删掉
            // 它依然会绿。这条钉住「建议把 avatar-offset-down 设为 <required> 或更大」这个句式本身。
            assertTrue(
                warnings.getFirst().contains("建议把 avatar-offset-down 设为 " + required),
                testCase.name() + "：警告必须给出可直接抄的建议值（建议把 avatar-offset-down 设为 "
                    + required + "），只报「重叠了多少像素」等于让服主自己算 12*scale：" + warnings
            );
        }
    }

    /**
     * 【不该报的别报】两行不重叠时不许有警告，否则警告会被服主当噪音忽略。
     *
     * <p>这是上一条的反面。只验「重叠会报」的测试挡不住「无条件报警」的实现 ——
     * 那种实现下每次启动都刷一条警告，真正的重叠警告就被淹没了。
     */
    @Test
    void 两行不重叠时不许有警告() {
        for (int scale : PackAssets.AVATAR_PIXEL_SCALE_TIERS) {
            for (int cardOffset : new int[] {0, 20, 40, 50}) {
                int required = PackAssets.avatarRowDownOffset(cardOffset, scale);
                if (PackAssets.avatarDownOffsetTierOf(required) < 0) {
                    continue;
                }
                warnings.clear();
                TrickHudService.readSettings(
                    configWith(Map.of(
                        "trick-hud.offset-down", cardOffset,
                        "trick-hud.avatar-offset-down", required,
                        "trick-hud.avatar-scale", scale
                    )),
                    warnings::add
                );
                assertTrue(
                    warnings.isEmpty(),
                    "精确相接的组合（牌行 " + cardOffset + " / 头像行 " + required + " / " + scale
                        + " 倍）不该有任何警告，否则真正的重叠警告会被噪音淹没：" + warnings
                );
            }
        }
    }

    /** 总开关要能真的关掉，否则「不想要这条 BossBar」的服主没有退路。 */
    @Test
    void disabledSwitchIsHonoured() {
        TrickHudService.Settings settings = TrickHudService.readSettings(
            configWith(Map.of("trick-hud.enabled", false)),
            warnings::add
        );

        assertFalse(settings.enabled());
        assertTrue(warnings.isEmpty());
    }

    /**
     * 老配置文件里没有 counter 段，升级上来必须直接能用。
     *
     * <p>断言的重点是【默认开着】而不是「有个默认值」：记牌器如果默认关闭，
     * 绝大多数服主根本不会知道有这个功能，等于白做。
     * 同时 hide-exhausted 默认必须是 false —— 出完仍显示 0 是「确认它出完了」，
     * 默认把格子藏起来会让人以为是插件出了 bug。
     */
    @Test
    void missingCounterSectionDefaultsToVisibleCounter() {
        TrickHudService.Settings settings = TrickHudService.readSettings(configWith(Map.of()), warnings::add);

        assertTrue(settings.counterEnabled(), "记牌器默认必须开着，否则没人会发现有这个功能");
        assertTrue(settings.counterGap() >= 0, "默认间距不能为负，否则默认配置就是压字的");
        assertFalse(settings.counterHideExhausted(), "默认应显示 0 而不是把格子藏掉，否则会被当成 bug");
        assertTrue(warnings.isEmpty(), "默认值不该触发任何警告：" + warnings);
    }

    /**
     * 负间距必须被拒。
     *
     * <p>这一条【刻意和 avatar-gap 相反】：那里负值是有意义的紧凑排版（让牌压在头像上），
     * 而记牌器 15 格一字排开，负间距会让点数图标和邻格的数字直接叠在一起，
     * 没有任何一种看法能读出剩几张。如果哪天有人图省事把两处校验统一成「都不校验」，
     * 这条测试就会失败。
     *
     * <p>同时必须留警告：静默回退会让服主一直以为自己配的值生效了。
     */
    @Test
    void negativeCounterGapIsRejectedWithWarning() {
        TrickHudService.Settings settings = TrickHudService.readSettings(
            configWith(Map.of("trick-hud.counter.gap", -3)),
            warnings::add
        );

        assertTrue(settings.counterGap() >= 0, "负间距会让相邻两格压字，必须回退");
        assertTrue(
            warnings.stream().anyMatch(w -> w.contains("trick-hud.counter.gap")),
            "静默回退会让服主以为配置生效了，必须留警告：" + warnings
        );
    }

    /**
     * 记牌器开关与 HUD 总开关必须【互相独立】。
     *
     * <p>两个方向都要钉：只关记牌器不能连整条 HUD 一起关掉（否则嫌它降低难度的人
     * 就得连「谁出了什么」一起放弃），而合法值不该产生警告。
     */
    @Test
    void counterSwitchIsIndependentFromTheMainSwitch() {
        TrickHudService.Settings settings = TrickHudService.readSettings(
            configWith(Map.of("trick-hud.counter.enabled", false)),
            warnings::add
        );

        assertFalse(settings.counterEnabled(), "记牌器要能单独关掉");
        assertTrue(settings.enabled(), "关记牌器不该顺带关掉整条 HUD");
        assertTrue(warnings.isEmpty(), "合法值不该有警告：" + warnings);
    }
}
