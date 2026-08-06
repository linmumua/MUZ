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
 * 字形】：头像倍数同时决定用哪一档 ascent 字形，而那些字形是构建期按 4..10 预生成的。
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
            settings.avatarScale() >= PackAssets.AVATAR_PIXEL_MIN_SCALE
                && settings.avatarScale() <= PackAssets.AVATAR_PIXEL_MAX_SCALE,
            "默认倍数必须落在资源包预生成范围内，否则默认配置就是豆腐块"
        );
        assertTrue(warnings.isEmpty(), "默认值不该触发任何警告：" + warnings);
    }

    /**
     * 三个可调量都要真的被读取，否则「config 可调」只是写了个没人看的注释。
     *
     * <p>【为什么要一起写 avatar-offset-down】：头像行偏移拆成独立配置项之后，改 avatar-scale
     * 不会再自动带着头像行走。9 倍头像的盒高是 90，两行相接需要 50 + 90 = 140，
     * 只写 avatar-scale=9 而留着默认的 110 就是真实重叠，会（且应该）触发重叠警告。
     * 所以这里把配套值一起写上，才能保持「全是合法值 → 一条警告都不该有」这个断言强度。
     */
    @Test
    void configuredValuesAreActuallyUsed() {
        TrickHudService.Settings settings = TrickHudService.readSettings(
            configWith(Map.of(
                "trick-hud.avatar-scale", 9,
                "trick-hud.avatar-gap", 13,
                "trick-hud.card-step", 30,
                "trick-hud.avatar-offset-down", 140
            )),
            warnings::add
        );

        assertEquals(9, settings.avatarScale());
        assertEquals(13, settings.avatarGap());
        assertEquals(30, settings.cardStep());
        assertEquals(140, PackAssets.avatarDownOffsetAt(settings.avatarDownOffsetTier()));
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

        assertEquals(0, settings.heightTier(), "缺省缩放档必须是 1:1 那一档");
        assertEquals(53, PackAssets.cardGlyphHeightAt(settings.heightTier()), "1:1 档的高度就是贴图原始高度");
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
                "trick-hud.card-height", 42,
                "trick-hud.offset-down", 8,
                "trick-hud.offset-x", -37
            )),
            warnings::add
        );

        assertEquals(42, PackAssets.cardGlyphHeightAt(settings.heightTier()), "card-height=42 应当选到 42 像素那一档");
        assertEquals(8, PackAssets.cardGlyphDownOffsetAt(settings.downOffsetTier()), "offset-down=8 应当选到下移 8 像素那一档");
        // offset-x 是像素而不是档位：它靠负空格实现，负数（左移）也必须原样透传。
        assertEquals(-37, settings.offsetX(), "offset-x 必须原样透传，左移是合法用法");
        assertTrue(warnings.isEmpty(), "全是合法值，不该有警告：" + warnings);
    }

    /**
     * 缩放与偏移只能取构建期预生成的档位；写了没生成过的值必须回退并留警告。
     *
     * <p>height/ascent 是烧进资源包 images.yml 的，运行时改不了。放行一个没生成过的值
     * 不会报错，只会让整条 HUD 变成豆腐块，而服主完全没法把这个现象和某一行配置联系起来。
     */
    @Test
    void unpreparedScaleAndOffsetFallBackWithWarning() {
        for (int badHeight : new int[] {0, -53, 40, 64, 106}) {
            warnings.clear();
            TrickHudService.Settings settings = TrickHudService.readSettings(
                configWith(Map.of("trick-hud.card-height", badHeight)),
                warnings::add
            );

            assertEquals(0, settings.heightTier(), "没预生成的 card-height=" + badHeight + " 必须回退到默认档");
            assertEquals(1, warnings.size(), "card-height=" + badHeight + " 必须留一条警告：" + warnings);
            assertTrue(warnings.getFirst().contains("card-height"), "警告要指名是哪一项：" + warnings);
        }

        // 24 已经是合法档位了，换成仍未预生成的值：非 4 的倍数、以及超出 52 的。
        for (int badOffset : new int[] {-4, 1, 3, 26, 54, 100}) {
            warnings.clear();
            TrickHudService.Settings settings = TrickHudService.readSettings(
                configWith(Map.of("trick-hud.offset-down", badOffset)),
                warnings::add
            );

            assertEquals(0, settings.downOffsetTier(), "没预生成的 offset-down=" + badOffset + " 必须回退到不下移");
            assertEquals(1, warnings.size(), "offset-down=" + badOffset + " 必须留一条警告：" + warnings);
            assertTrue(warnings.getFirst().contains("offset-down"), "警告要指名是哪一项：" + warnings);
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
        for (int scale = PackAssets.AVATAR_PIXEL_MIN_SCALE; scale <= PackAssets.AVATAR_PIXEL_MAX_SCALE; scale++) {
            // 每个倍数都配上它对应的头像行偏移（默认牌行 50 + 盒高 10*scale），
            // 否则倍数一变就会真的重叠、触发重叠警告，把末尾那条「不该有警告」的断言污染掉。
            // 顺带验证了一件事：4..10 每个倍数都能在头像档位表里找到精确相接的那一档。
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
        int avatarBoxHeight = PackAssets.AVATAR_OUTLINED_PIXELS * settings.avatarScale();

        // 锁死默认组合的具体数值：改了任何一个都要回来重新核算垂直几何。
        assertEquals(50, cardDown, "牌行默认下移 50 像素（config 的 offset-down）");
        assertEquals(6, settings.avatarScale(), "大头像默认 6 倍");
        assertEquals(60, avatarBoxHeight, "6 倍头像的字形盒是 10*6=60 像素高，不是 8*6=48");
        assertEquals(110, avatarDown, "头像行必须落在 110 那一档（= 50 + 60），两行刚好相接");

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
     * <p>【这条的语义随拆表改写了，不是放宽】：拆表前头像档位是从 offset-down 与
     * avatar-scale 推导的，「缺档」指推导结果不在表里，回退到最深那一档；拆表后头像档位
     * 由 config 的 avatar-offset-down 直接给，「缺档」变成服主写了个非档位值，回退到默认档
     * 110（两行精确相接的那个点）。回退到 110 比回退到最深档（150）更合理：150 会让头像
     * 悬在牌下方 40 像素，而 110 就是出厂布局。断言强度没变，仍然是「必须回退到确定的那一档
     * 且必须恰好一条警告」。
     *
     * <p>守的风险：静默回退的表现是「头像位置莫名不对」，服主完全没法把这个现象和自己写的
     * 那行配置联系起来 —— 必须把合法档位直接写进警告。
     */
    @Test
    void 头像行偏移非预生成档时回退并留警告() {
        // 60 是头像表里有的，105 两张表都没有，正好当非法值。
        TrickHudService.Settings settings = TrickHudService.readSettings(
            configWith(Map.of("trick-hud.avatar-offset-down", 105)),
            warnings::add
        );

        assertEquals(
            110, PackAssets.avatarDownOffsetAt(settings.avatarDownOffsetTier()),
            "非预生成的 avatar-offset-down 必须回退到默认档 110（两行精确相接的那一档）"
        );
        assertEquals(1, warnings.size(), "必须恰好留一条警告：" + warnings);
        assertTrue(warnings.getFirst().contains("avatar-offset-down"), "警告要指名该改哪一项：" + warnings);
        assertTrue(
            warnings.getFirst().contains(PackAssets.avatarDownOffsetTierList()),
            "警告必须写清合法档位有哪些，否则服主只能猜：" + warnings
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
        int boxHeight = PackAssets.AVATAR_OUTLINED_PIXELS * settings.avatarScale();

        assertEquals(50, cardDown, "牌行默认下移 50 像素");
        assertEquals(6, settings.avatarScale(), "大头像默认 6 倍");
        assertEquals(110, avatarDown, "头像行默认落在 110 那一档");
        assertEquals(
            boxHeight, avatarDown - cardDown,
            "默认组合下头像顶边必须正好压在牌底上：两者之差应恰好等于头像盒高 " + boxHeight
                + "，差值大了两行之间有缝隙，小了头像压进牌里"
        );
        assertTrue(warnings.isEmpty(), "默认组合不该有任何警告：" + warnings);
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
            // 头像行比牌行还浅：整个头像都在牌上方，最严重的重叠。
            new Case("头像行比牌行浅", 50, 40, 6),
            // 差 10 像素，远不够 6 倍头像的 60 像素盒高。
            new Case("差值远不足盒高", 40, 50, 6),
            // 按错误的 8*scale 算出来的「相接」值：40 + 8*6 = 88 -> 表里最近的 90，仍差 10。
            new Case("按 8*scale 算的相接值仍重叠", 40, 90, 6),
            // 10 倍头像盒高 100，默认 110 只让开 60。
            new Case("大倍数头像挤不进默认档", 50, 110, 10)
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
        for (int scale = PackAssets.AVATAR_PIXEL_MIN_SCALE; scale <= PackAssets.AVATAR_PIXEL_MAX_SCALE; scale++) {
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
}
