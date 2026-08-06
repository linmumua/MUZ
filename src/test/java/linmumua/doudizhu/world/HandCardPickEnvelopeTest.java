package linmumua.doudizhu.world;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import linmumua.doudizhu.world.HandCardPickGeometry.CardQuad;
import linmumua.doudizhu.world.HandCardPickGeometry.Envelope;
import linmumua.doudizhu.world.HandCardPickGeometry.Hit;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * 判定包络的回归测试：守两件事。
 *
 * <p>第一，未选中牌「悬停不能改变判定几何」：抬升和放大都是悬停的输出，一旦判定矩形跟着牌
 * 当帧的位置走，就成了闭环——命中 → 牌抬起 → 判定区上移 → 准星落到区外 → 取消 → 牌落回
 * → 又命中，周期等于动画时长。
 *
 * <p>第二，已选中牌的包络必须<b>把牌未抬起时的位置一起包住</b>。选中抬升默认 0.18 格，
 * 大于牌本体半高，牌抬到位后原来的牌面中心就落在自己判定区外，而右邻未选中牌的包络下界
 * 仍是静止底边、会完整盖住那块空位；再叠上「取 index 最小」，准星没动再点一次就被右邻牌
 * 抢走——右键选错张、左键出不了牌。已选中的牌同样不吃 hover 放大
 * （{@code previewAnimated = isHovered && !isSelected}），所以半宽仍是静止态。
 */
class HandCardPickEnvelopeTest {

    /**
     * 静止态缩放，取服务器实配 render.private-card-scale 0.8。
     *
     * <p>必须和服务器 config.yml 对齐，否则整套用例验的是没人在跑的配置。
     */
    private static final double REST_SCALE = 0.8 / 0.50 * 0.50;

    /** 悬停放大，取 render.card-hover.scale 实配。 */
    private static final double HOVER_SCALE = 1.08;

    /** 完全悬停时的缩放。 */
    private static final double MAX_SCALE = REST_SCALE * HOVER_SCALE;

    /** render.card-hover.lift 实配。 */
    private static final double HOVER_LIFT = 0.06;

    /** render.selected-card.lift 实配。 */
    private static final double SELECTED_LIFT = 0.18;

    /** BACK_OUT 曲线的过冲钳位，见 PhysicalTableManager#MAX_ANIMATION_OVERSHOOT。 */
    private static final double MAX_ANIMATION_OVERSHOOT = 1.15;

    /**
     * 未选中牌可能达到的最大抬升：只有 hover 抬升一项。
     *
     * <p>已选中的牌不会被 hover 放大或抬起（{@code previewAnimated = isHovered && !isSelected}），
     * 所以 {@link #envelope()} 的 maxLift 不再包含 selectedLift。
     */
    private static final double MAX_LIFT = HOVER_LIFT;

    /**
     * 生产传给 envelope 的抬升上界：hover 上限乘过冲钳位 1.15 = 0.06 × 1.15 = 0.069。
     *
     * <p>过冲那一截若不算进包络，牌上沿会在动画末段冲出判定区，抖动闭环从顶边回来。
     */
    private static final double MAX_LIFT_WITH_OVERSHOOT = MAX_LIFT * MAX_ANIMATION_OVERSHOOT;

    private static final int HAND_SIZE = 17;

    /** 手牌间距，取服务器实配 render.hand-spacing。 */
    private static final double SPACING = 0.1;

    /** 每张牌之间的深度递进，取服务器实配 render.card-depth-offset。 */
    private static final double DEPTH_STEP = 0.005;

    /** 半宽下限，与生产 {@code pickLaneHalfWidth} 同口径。 */
    private static final double LANE_HALF_WIDTH = SPACING * 0.5;



    private static final double MAX_DISTANCE = 6.0;

    /** 眼睛在深度轴上的位置：负侧代表玩家一侧，视线朝桌心（+n）看。 */
    private static final double EYE_N = -2.0;

    private static final double STEP = 0.0005;

    private static final double NONE = -1;

    /** 未选中牌的包络。 */
    private static Envelope envelope() {
        return unified()[0];
    }

    private static double centerU(int index) {
        return (-((HAND_SIZE - 1) * 0.5) + index) * SPACING;
    }

    /** 整手未选中牌的包络矩形：每张的尺寸完全相同，与谁被悬停无关。 */
    private static List<CardQuad> envelopeHand() {
        Envelope envelope = envelope();
        List<CardQuad> quads = new ArrayList<>(HAND_SIZE);
        for (int index = 0; index < HAND_SIZE; index++) {
            quads.add(new CardQuad(
                1000 + index,
                index,
                centerU(index),
                envelope.centerVOffset(),
                index * DEPTH_STEP,
                envelope.halfWidth(),
                envelope.halfHeight()
            ));
        }
        return quads;
    }

    /** 旧实现的做法：矩形跟着牌当帧的抬升和缩放走。只在对照用例里出现。 */
    private static CardQuad animatedQuad(int index, double lift, double scale) {
        return new CardQuad(
            1000 + index,
            index,
            centerU(index),
            lift + HandCardPickGeometry.centerVOffset(scale),
            index * DEPTH_STEP,
            HandCardPickGeometry.halfWidth(scale),
            HandCardPickGeometry.halfHeight(scale)
        );
    }

    private static Hit pickAt(List<CardQuad> quads, double u, double v) {
        return HandCardPickGeometry.pick(quads, u, v, EYE_N, 0.0, 0.0, 1.0, MAX_DISTANCE);
    }

    @Test
    @DisplayName("未选中包络同时包住静止态、放大未抬起态与 hover 完全抬起态")
    void envelopeContainsBothExtremeStates() {
        Envelope envelope = rawUnselected();
        double bottom = envelope.centerVOffset() - envelope.halfHeight();
        double top = envelope.centerVOffset() + envelope.halfHeight();

        double restBottom = HandCardPickGeometry.centerVOffset(REST_SCALE) - HandCardPickGeometry.halfHeight(REST_SCALE);
        double restTop = HandCardPickGeometry.centerVOffset(REST_SCALE) + HandCardPickGeometry.halfHeight(REST_SCALE);
        // 「已放大到最大、但抬升还没跟上」是一个真实存在的中间帧：缩放对进度线性插值，
        // 抬升走动画曲线，EASE_IN_OUT 起步导数为 0，动画早期牌就是这个样子。
        double grownBottom = HandCardPickGeometry.centerVOffset(MAX_SCALE) - HandCardPickGeometry.halfHeight(MAX_SCALE);
        double liftedBottom = MAX_LIFT_WITH_OVERSHOOT
            + HandCardPickGeometry.centerVOffset(MAX_SCALE) - HandCardPickGeometry.halfHeight(MAX_SCALE);
        double liftedTop = MAX_LIFT_WITH_OVERSHOOT
            + HandCardPickGeometry.centerVOffset(MAX_SCALE) + HandCardPickGeometry.halfHeight(MAX_SCALE);

        assertTrue(bottom <= restBottom + 1.0e-12, "包络必须包住静止态的底边");
        assertTrue(top >= restTop - 1.0e-12, "包络必须包住静止态的顶边");
        assertTrue(bottom <= grownBottom + 1.0e-12, "包络必须包住放大后还没抬起时的底边");
        assertTrue(bottom <= liftedBottom + 1.0e-12, "包络必须包住 hover 抬起放大后的底边");
        assertTrue(top >= liftedTop - 1.0e-12, "包络必须包住 hover 抬起放大后的顶边");

        // 独立算出的期望值，用来防止 min/max 取错一侧后断言仍然侥幸通过。
        // 这里刻意不写死数字而是从 REST_SCALE / MAX_SCALE 推：写死的数字会和常量脱节，
        // 上一版就是这么把 0.8 的手算结果留在了 0.35 的配置里，测试全绿却什么都没守住。
        assertEquals(grownBottom, bottom, 1.0e-12,
            "底边应当取自放大未抬起态：这一帧牌下沿比静止时更低，差出来的那一条不盖住就会脱靶");
        assertEquals(liftedTop, top, 1.0e-12, "顶边应当取自 hover 抬起放大后的状态（不含 selectedLift）");

        double bodyHalfWidth = Math.max(
            HandCardPickGeometry.halfWidth(REST_SCALE),
            HandCardPickGeometry.halfWidth(MAX_SCALE));
        assertEquals(Math.max(bodyHalfWidth, LANE_HALF_WIDTH), envelope.halfWidth(), 1.0e-12,
            "半宽取 max(牌本体半宽, 半个牌距)：前者保证牌面每一处都点得到，后者保证稀疏排布不留缝");
        assertTrue(envelope.halfWidth() >= bodyHalfWidth - 1.0e-12,
            "判定半宽绝不能窄于牌本体：窄了牌面就有点不到的死区，且与可见区错位导致选错张");
        // 再钉住"底边确实比静止态低"这条因果，否则上面三条在放大倍数为 1 时会退化成恒等式
        assertTrue(grownBottom < restBottom - 1.0e-12,
            "放大未抬起态的下沿必须真的比静止态低，否则这条用例的前提不成立");
    }

    @Test
    @DisplayName("未选中包络让命中与动画状态无关：hover 抖动带内，当帧几何会落空，包络不会")
    void envelopeMakesHitIndependentOfAnimationState() {
        List<CardQuad> envelopeHand = envelopeHand();
        double restBottom =
            HandCardPickGeometry.centerVOffset(REST_SCALE) - HandCardPickGeometry.halfHeight(REST_SCALE);
        double u = centerU(0);
        int sampled = 0;
        int animatedMisses = 0;

        // 抖动带 = hover 抬起后被腾空的那一截：静止底 → 静止底 + hoverMaxLift。
        for (double v = restBottom + STEP; v < restBottom + MAX_LIFT; v += STEP) {
            sampled++;

            Hit resting = HandCardPickGeometry.intersect(
                animatedQuad(0, 0.0, REST_SCALE), u, v, EYE_N, 0.0, 0.0, 1.0, MAX_DISTANCE);
            Hit lifted = HandCardPickGeometry.intersect(
                animatedQuad(0, MAX_LIFT, MAX_SCALE), u, v, EYE_N, 0.0, 0.0, 1.0, MAX_DISTANCE);
            if (resting != null && lifted == null) {
                // 这就是闭环的证据：静止时命中 → 牌抬起 → 同一准星位置反而落空 → 牌落回 → 再命中。
                animatedMisses++;
            }

            Hit hit = pickAt(envelopeHand, u, v);
            assertNotNull(hit, "包络在整条抖动带内都必须命中，否则环没被掐断，v=" + v);
            assertEquals(0, hit.index(), "抖动带内的赢家仍应是压在最上层的那张，v=" + v);
        }

        assertTrue(sampled > 0, "抖动带必须被采样到，否则这条用例什么都没验");
        assertTrue(animatedMisses > 0,
            "对照组必须真的落空过：如果当帧几何在这条带里从不落空，说明抖动的成因判断错了");
    }

    @Test
    @DisplayName("未选中包络高度完全由配置算出：hover 抬升与放大各贡献多少可逐项对上")
    void envelopeHeightIsDrivenByConfig() {
        double restHeight = 2.0 * HandCardPickGeometry.halfHeight(REST_SCALE);
        double envelopeHeight = 2.0 * rawUnselected().halfHeight();

        // 牌模型全高 0.396875 单位，乘以静止缩放
        assertEquals(0.396875 * REST_SCALE, restHeight, 1.0e-12, "静止牌高 = 0.396875 × 静止缩放");
        // 包络高 = hover 抬升上界 + 放大后的牌高。两项都从常量推，不写死数字。
        assertEquals(
            MAX_LIFT_WITH_OVERSHOOT + 2.0 * HandCardPickGeometry.halfHeight(MAX_SCALE),
            envelopeHeight,
            1.0e-12,
            "包络高 = hover 抬升上界 + 放大后的牌高：底边取放大未抬起态，顶边取 hover 抬到最高态");
        assertTrue(envelopeHeight > restHeight + MAX_LIFT - 1.0e-12,
            "包络至少要比静止牌高出整个最大 hover 抬升，否则抖动带没被完全盖住");

        // 抬升和放大都关掉时，包络必须退化成牌自身的矩形——包络不该自带任何常数余量。
        Envelope degenerate = HandCardPickGeometry.envelope(REST_SCALE, REST_SCALE, REST_SCALE, REST_SCALE, 0.0, 0.0);
        assertEquals(HandCardPickGeometry.centerVOffset(REST_SCALE), degenerate.centerVOffset(), 1.0e-12);
        assertEquals(HandCardPickGeometry.halfHeight(REST_SCALE), degenerate.halfHeight(), 1.0e-12);
        assertEquals(HandCardPickGeometry.halfWidth(REST_SCALE), degenerate.halfWidth(), 1.0e-12);

        // 只关放大：包络恰好等于牌高加 hover 抬升。
        Envelope liftOnly = HandCardPickGeometry.envelope(REST_SCALE, REST_SCALE, REST_SCALE, REST_SCALE, MAX_LIFT, 0.0);
        assertEquals(restHeight + MAX_LIFT, 2.0 * liftOnly.halfHeight(), 1.0e-12);
    }

    @Test
    @DisplayName("整排包络仍然铺满且赢家单调：包络只在竖直方向放宽，没有破坏横向归属")
    void envelopeRowStillTilesWithoutGaps() {
        List<CardQuad> quads = envelopeHand();
        Envelope envelope = envelope();
        double left = centerU(0) - envelope.halfWidth();
        double right = centerU(HAND_SIZE - 1) + envelope.halfWidth();
        double v = envelope.centerVOffset();

        int[] samples = new int[HAND_SIZE];
        double previous = NONE;
        for (double u = left + STEP; u < right - STEP; u += STEP) {
            Hit hit = pickAt(quads, u, v);
            assertNotNull(hit, "整排内部不允许有点不到任何牌的空洞，u=" + u);
            assertTrue(hit.index() >= previous, "赢家必须随 u 单调不减，u=" + u);
            previous = hit.index();
            samples[hit.index()]++;
        }

        assertEquals(2.0 * envelope.halfWidth(), samples[0] * STEP, 3.0 * STEP,
            "index 0 压在最上层，独占它的整个牌宽");
        for (int index = 1; index < HAND_SIZE; index++) {
            assertEquals(SPACING, samples[index] * STEP, 3.0 * STEP,
                "index " + index + " 的独占区间仍应等于它露在外面的那一条（= 手牌间距）");
        }
    }

    @Test
    @DisplayName("包络下滞回归零：边界不再取决于哪张牌正被悬停")
    void envelopeRemovesHysteresisEntirely() {
        // 每张牌尺寸相同，所以「谁被悬停」这个输入根本进不了几何：无论上一帧选中谁，
        // 同一准星位置算出的赢家都是同一张。这比原来朝 index 增大方向的有界滞回更强。
        List<CardQuad> quads = envelopeHand();
        Envelope envelope = envelope();
        double v = envelope.centerVOffset();

        for (double u = centerU(0) - envelope.halfWidth(); u <= centerU(HAND_SIZE - 1) + envelope.halfWidth();
            u += STEP) {
            Hit first = pickAt(quads, u, v);
            if (first == null) {
                continue;
            }
            // 把赢家喂回去：包络与状态无关，重算必须逐字段一致，而不只是 index 一致。
            Hit second = pickAt(envelopeHand(), u, v);
            assertNotNull(second, "赢家喂回去不可能落空，u=" + u);
            assertEquals(first.index(), second.index(), "赢家必须收敛，u=" + u);
            assertEquals(first.localV(), second.localV(), 1.0e-12, "交点也必须完全不变，u=" + u);
        }
    }

    @Test
    @DisplayName("病态配置不会算出倒过来的包络：悬停缩放比静止还小时半宽仍取较大者")
    void degenerateConfigStillYieldsSaneEnvelope() {
        // getHoverCardScale 已经 clamp 到 ≥1，但包络不该依赖调用方先做过 clamp。
        Envelope shrunk = HandCardPickGeometry.envelope(REST_SCALE, REST_SCALE, REST_SCALE * 0.5, REST_SCALE * 0.5, 0.0, 0.0);

        assertEquals(HandCardPickGeometry.halfWidth(REST_SCALE), shrunk.halfWidth(), 1.0e-12,
            "半宽取两态较大者，缩小的那一态不能把判定区吃掉");
        assertTrue(shrunk.halfHeight() >= HandCardPickGeometry.halfHeight(REST_SCALE) - 1.0e-12,
            "半高同理，不能比静止态还矮");
        assertNull(
            HandCardPickGeometry.intersect(
                new CardQuad(1, 0, 0.0, shrunk.centerVOffset(), 0.0, shrunk.halfWidth(), shrunk.halfHeight()),
                shrunk.halfWidth() + 1.0e-6, shrunk.centerVOffset(), EYE_N, 0.0, 0.0, 1.0, MAX_DISTANCE),
            "包络之外仍必须判不命中，包络放宽的是范围而不是判据");
    }

    // ==== 以下是本次修复新增的测试，锁定包络重做的两条核心语义 ====

    @Test
    @DisplayName("未选中牌包络不再包含 selectedLift：顶边显著低于旧的含选中抬升值")
    void unselectedEnvelopeExcludesSelectedLift() {
        Envelope envelope = envelope();
        double top = envelope.centerVOffset() + envelope.halfHeight();

        // 旧实现把 selectedLift + hoverLift 都算进 maxLift，
        // 旧实现把 selectedLift 也并进了未选中包络，顶边会高出整整一个 selectedLift。
        // 从常量推而不是写死数字：写死的值会和 REST_SCALE 脱节。
        double oldTopWithSelectedLift = (SELECTED_LIFT + MAX_LIFT) * MAX_ANIMATION_OVERSHOOT
            + HandCardPickGeometry.centerVOffset(MAX_SCALE)
            + HandCardPickGeometry.halfHeight(MAX_SCALE);
        assertTrue(top < oldTopWithSelectedLift - SELECTED_LIFT * 0.5,
            "未选中牌的包络顶边 " + top + " 必须显著低于旧值 " + oldTopWithSelectedLift
                + "——锁定 selectedLift 不再并入未选中包络");
    }

    /**
     * 已选中牌的包络：静止态与完全抬起态（含过冲）的并集。
     *
     * <p>横向传 {@link #MAX_SCALE}：已选中的牌视觉上不放大，但判定半宽必须和未选中态一致，
     * 否则同一根准星在选中前后落进不同判定区。
     */
    private static Envelope selectedEnvelope() {
        return unified()[1];
    }

    /**
     * 未经统一的原始未选中包络。
     *
     * <p>验证 {@code envelope()} 自身语义（高度由哪几个配置项算出）的用例要用这个：
     * 统一之后的高度被选中抬升抬高了，那反映的是 {@code unifiedEnvelopes} 的行为，
     * 不是 {@code envelope()} 的行为。
     */
    private static Envelope rawUnselected() {
        return HandCardPickGeometry.envelope(
            REST_SCALE, REST_SCALE, MAX_SCALE, MAX_SCALE, MAX_LIFT_WITH_OVERSHOOT, LANE_HALF_WIDTH);
    }

    /**
     * 统一尺寸后的两个包络：[0] 未选中、[1] 已选中，宽高相同、只有竖直中心不同。
     *
     * <p>和生产 pickHandCard 里的算法保持一致，否则测的就不是实际跑的几何。
     */
    private static Envelope[] unified() {
        Envelope raw = HandCardPickGeometry.envelope(
            REST_SCALE, REST_SCALE, MAX_SCALE, MAX_SCALE, MAX_LIFT_WITH_OVERSHOOT, LANE_HALF_WIDTH);
        Envelope selectedRaw = HandCardPickGeometry.envelopeForSelected(
            raw, REST_SCALE, SELECTED_LIFT * MAX_ANIMATION_OVERSHOOT);
        return HandCardPickGeometry.unifiedEnvelopes(raw, selectedRaw);
    }

    @Test
    @DisplayName("已选中牌包络必须保住静止底边：牌抬走后原位置不能让给邻牌")
    void selectedEnvelopeKeepsRestBottomEdge() {
        Envelope env = selectedEnvelope();

        double restBottom = HandCardPickGeometry.centerVOffset(REST_SCALE)
            - HandCardPickGeometry.halfHeight(REST_SCALE);
        double envBottom = env.centerVOffset() - env.halfHeight();

        assertEquals(restBottom, envBottom, 1.0e-12,
            "包络下边必须仍是静止态底边——这是「选中后还能在原处取消选中、还能左键出牌」的唯一保证");
    }

    @Test
    @DisplayName("已选中牌包络顶边覆盖含过冲的满抬升，牌上沿不会冲出判定区")
    void selectedEnvelopeTopCoversOvershoot() {
        Envelope env = selectedEnvelope();

        double liftedTop = SELECTED_LIFT * MAX_ANIMATION_OVERSHOOT
            + HandCardPickGeometry.centerVOffset(REST_SCALE)
            + HandCardPickGeometry.halfHeight(REST_SCALE);
        double envTop = env.centerVOffset() + env.halfHeight();

        assertEquals(liftedTop, envTop, 1.0e-12, "包络上边 = 抬到过冲峰值时的牌顶边");
        // 选中态与未选中态的横向判定必须完全一样宽，否则原地再点一次会被邻牌抢走。
        // 具体宽度由 unifiedEnvelopes 统一，这里只锁「两者相等」这条约束本身。
        assertEquals(envelope().halfWidth(), env.halfWidth(), 1.0e-12,
            "选中态与未选中态的横向判定必须一样宽，否则原地再点一次会被邻牌抢走");
    }

    @Test
    @DisplayName("已选中牌包络与动画状态无关：不同动画帧的牌位置都落在同一个包络内")
    void selectedEnvelopeIsAnimationInvariant() {
        // 验证核心不变量：包络由配置常量一次算定，不读当帧 lift/scale。
        // 不同动画进度（0%、50%、100%、过冲 115%）下，牌的实际位置都必须落在包络里。
        // 失败条件：如果有人让包络去读当帧 lift 而不是取 max，不同帧会算出不同包络，
        // 某些中间帧的牌位置就会落在缩小了的包络之外，断言失败。
        Envelope env = selectedEnvelope();
        double envBottom = env.centerVOffset() - env.halfHeight();
        double envTop = env.centerVOffset() + env.halfHeight();

        // 模拟不同动画帧下牌的实际 top/bottom
        double[] animationProgresses = {0.0, 0.25, 0.5, 0.75, 1.0, MAX_ANIMATION_OVERSHOOT};
        for (double progress : animationProgresses) {
            double currentLift = SELECTED_LIFT * progress;
            double cardBottom = currentLift + HandCardPickGeometry.centerVOffset(REST_SCALE)
                - HandCardPickGeometry.halfHeight(REST_SCALE);
            double cardTop = currentLift + HandCardPickGeometry.centerVOffset(REST_SCALE)
                + HandCardPickGeometry.halfHeight(REST_SCALE);

            assertTrue(envBottom <= cardBottom + 1.0e-12,
                "动画进度 " + progress + " 时牌底边必须落在包络内，envBottom=" + envBottom + " cardBottom=" + cardBottom);
            assertTrue(envTop >= cardTop - 1.0e-12,
                "动画进度 " + progress + " 时牌顶边必须落在包络内，envTop=" + envTop + " cardTop=" + cardTop);
        }

        // 对照：如果包络只取当帧 lift=0 的位置（退化成贴合牌本体），满抬升帧必然溢出
        double fullLiftTop = SELECTED_LIFT * MAX_ANIMATION_OVERSHOOT
            + HandCardPickGeometry.centerVOffset(REST_SCALE) + HandCardPickGeometry.halfHeight(REST_SCALE);
        double restOnlyTop = HandCardPickGeometry.centerVOffset(REST_SCALE) + HandCardPickGeometry.halfHeight(REST_SCALE);
        assertTrue(fullLiftTop > restOnlyTop + 1.0e-6,
            "对照组前提：满抬升顶边必须真的超过静止顶边，否则本用例的失败条件不成立");
    }

    @Test
    @DisplayName("已选中包络与未选中包络完全同尺寸：横向和竖直都不许差，否则出不了牌且闪烁")
    void selectedEnvelopeMatchesUnselectedDimensions() {
        Envelope unselected = envelope();
        Envelope selected = selectedEnvelope();

        assertEquals(unselected.halfWidth(), selected.halfWidth(), 1.0e-12,
            "横向必须完全一致，否则选中后那一条会被邻牌抢走，表现为出不了牌");
        assertEquals(unselected.halfHeight(), selected.halfHeight(), 1.0e-12,
            "竖直高度必须完全一致，否则选中瞬间 hover 归属跳变，表现为画面闪烁");
    }

    @Test
    @DisplayName("回归：牌面可见处（被右邻遮挡后的那一条）任意一点，必须命中它自己")
    void visibleSliverOfEachCardHitsItself() {
        Envelope envelope = envelope();
        double bodyHalf = HandCardPickGeometry.halfWidth(REST_SCALE);

        for (int index = 0; index < HAND_SIZE; index++) {
            double visibleLeft = index == 0
                ? centerU(index) - bodyHalf
                : Math.min(centerU(index - 1) + bodyHalf, centerU(index) + bodyHalf);
            double visibleRight = centerU(index) + bodyHalf;
            assertTrue(visibleRight > visibleLeft,
                "牌 " + index + " 必须有可见区，否则这条用例的前提不成立");

            for (double fraction : new double[] {0.15, 0.25, 0.5, 0.75, 0.99}) {
                double u = visibleLeft + (visibleRight - visibleLeft) * fraction;
                List<CardQuad> quads = new ArrayList<>(HAND_SIZE);
                for (int k = 0; k < HAND_SIZE; k++) {
                    quads.add(new CardQuad(1000 + k, k, centerU(k), envelope.centerVOffset(),
                        k * DEPTH_STEP, envelope.halfWidth(), envelope.halfHeight()));
                }
                Hit hit = pickAt(quads, u, envelope.centerVOffset());
                assertNotNull(hit, String.format(
                    "牌 %d 可见区 %.0f%% 处（u=%.4f）必须命中某张牌，不能落空",
                    index, fraction * 100, u));
                assertEquals(index, hit.index(), String.format(
                    "牌 %d 可见区 %.0f%% 处（u=%.4f）必须命中它自己，命中别张就是选错张",
                    index, fraction * 100, u));
            }
        }
    }

    @Test
    @DisplayName("抬升配成 0 或负数时已选中包络不出现上下颠倒，且仍盖住牌本体")
    void selectedEnvelopeStaysSaneWithoutLift() {
        for (double lift : new double[] {0.0, -0.5}) {
            Envelope env = HandCardPickGeometry.envelopeForSelected(envelope(), REST_SCALE, lift);

            assertTrue(env.halfHeight() > 0.0, "lift=" + lift + " 时半高仍必须为正，不能算出反向矩形");

            // 不论抬升怎么配，判定都必须继续盖住牌未抬起时的位置——
            // 那是「取消选中」和「左键出牌」时准星实际停留的地方。
            double restBottom = HandCardPickGeometry.centerVOffset(REST_SCALE)
                - HandCardPickGeometry.halfHeight(REST_SCALE);
            double restTop = HandCardPickGeometry.centerVOffset(REST_SCALE)
                + HandCardPickGeometry.halfHeight(REST_SCALE);
            assertTrue(env.centerVOffset() - env.halfHeight() <= restBottom + 1.0e-12,
                "lift=" + lift + " 时包络底边必须盖住牌本体底边");
            assertTrue(env.centerVOffset() + env.halfHeight() >= restTop - 1.0e-12,
                "lift=" + lift + " 时包络顶边必须盖住牌本体顶边");
        }
    }

    @Test
    @DisplayName("回归：选中一张牌后准星停在原处，仍命中它自己而不是右邻牌")
    void selectedCardStillWinsAtItsRestPosition() {
        // 复现用户实测的故障。旧实现让已选中牌的判定矩形整块跟着抬升平移：
        // selectedLift 0.18 格（含过冲 0.207）超过了牌本体半高，牌抬到位后原来的牌面中心
        // 已落在它自己的判定区之外，而右邻未选中牌的包络下界仍是静止底边、完整盖住那里。
        // 于是准星没动、再点一次时命中的是右邻牌：右键表现为「选不中 / 选错张」，
        // 左键则因为命中牌不在选中集合里被拒绝出牌，表现为「出牌没反应」。
        int selectedIndex = 8;

        Envelope unselected = envelope();
        Envelope selected = selectedEnvelope();
        List<CardQuad> quads = new ArrayList<>(HAND_SIZE);
        for (int index = 0; index < HAND_SIZE; index++) {
            Envelope env = index == selectedIndex ? selected : unselected;
            quads.add(new CardQuad(
                1000 + index,
                index,
                centerU(index),
                env.centerVOffset(),
                index * DEPTH_STEP,
                env.halfWidth(),
                env.halfHeight()
            ));
        }

        // 判定带锁成半个牌距后，每张牌的独占区就是它自己那条带子，
        // 相邻带首尾相接不重叠。取带子内部靠右一点（不贴边，避开浮点边界）。
        double exclusiveU = centerU(selectedIndex) + LANE_HALF_WIDTH * 0.5;
        assertTrue(exclusiveU < centerU(selectedIndex) + LANE_HALF_WIDTH,
            "取样点必须落在这张牌自己的判定带内，否则测到的是邻牌，用例就失去意义");
        assertTrue(exclusiveU < centerU(selectedIndex + 1) - LANE_HALF_WIDTH,
            "取样点同时必须在右邻牌的带子之外，才能证明归属是唯一的");

        // 竖直方向取牌【静止时】的牌面中心：玩家刚点完选中，手没动，准星就停在这儿。
        double restCenterV = HandCardPickGeometry.centerVOffset(REST_SCALE);
        Hit hit = pickAt(quads, exclusiveU, restCenterV);

        assertNotNull(hit, "准星停在原处必须仍能命中某张牌");
        assertEquals(selectedIndex, hit.index(),
            "必须命中已选中的那张牌自己；命中右邻牌就是用户报的选错张 / 出不了牌");

        // 对照组：换成旧实现的「贴合本体 + 跟着抬升整块平移」，同一个取样点必须选不中这张牌。
        // 这段是给上面那条断言做的有效性证明——否则它可能只是恒真，改坏了也发现不了。
        List<CardQuad> legacyQuads = new ArrayList<>(HAND_SIZE);
        for (int index = 0; index < HAND_SIZE; index++) {
            legacyQuads.add(index == selectedIndex
                ? animatedQuad(index, SELECTED_LIFT * MAX_ANIMATION_OVERSHOOT, REST_SCALE)
                : new CardQuad(
                    1000 + index,
                    index,
                    centerU(index),
                    unselected.centerVOffset(),
                    index * DEPTH_STEP,
                    unselected.halfWidth(),
                    unselected.halfHeight()
                ));
        }
        Hit legacyHit = pickAt(legacyQuads, exclusiveU, restCenterV);
        assertTrue(legacyHit == null || legacyHit.index() != selectedIndex,
            "对照组：旧包络在同一取样点必须选不中这张牌，否则本用例证明不了修复有效");
    }
}
