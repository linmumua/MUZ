package linmumua.doudizhu.world;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import linmumua.doudizhu.world.HandCardPickGeometry.CardQuad;
import linmumua.doudizhu.world.HandCardPickGeometry.Hit;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * 手牌解析拾取的回归测试。
 *
 * <p>这些用例守的不是「函数算得对」，而是四件会直接毁掉手感的事：
 * 判定区铺不满导致点击落空、赢家和视觉最上层那张不是同一张、
 * 悬停放大把滞回加到错误的一侧、以及悬停状态自己喂自己导致两 tick 之间反复翻转。
 *
 * <p><b>注意这里的两条滞回用例喂的是逐张不同的尺寸</b>（只放大被悬停那张），守的是
 * {@link HandCardPickGeometry#pick} 这个函数的能力。渲染侧现在改喂
 * {@link HandCardPickGeometry#envelope} 的包络，每张牌尺寸相同、滞回退化为 0，
 * 那条生产路径由 HandCardPickEnvelopeTest 负责。
 *
 * <p><b>叠放方向是这里所有断言的前提</b>：index 0 最靠近玩家、压在最上层，
 * 所以裁决取 index 最小。如果哪天把 {@code handDepth} 的符号翻了，
 * 下面第三条用例（赢家 = 离眼睛最近的那张）会第一个红，而不是等到玩家来报手感问题。
 */
class HandCardPickGeometryTest {

    /** 手牌张数，取斗地主发牌后的实际上限。 */
    private static final int HAND_SIZE = 17;

    /** 牌这一帧的静止缩放，取服务器实配：private-card-scale 0.8 / private-card-size 三轴 0.5。 */
    private static final double REST_SCALE = 0.8;

    /** 悬停缩放倍率，取 render.card-hover.scale 的默认值。 */
    private static final double HOVER_SCALE = 1.08;

    /** 手牌间距，取服务器实配 render.hand-spacing。 */
    private static final double SPACING = 0.1;

    /** 每张牌之间的深度递进，取服务器实配 render.card-depth-offset。 */
    private static final double DEPTH_STEP = 0.005;

    private static final double MAX_DISTANCE = 6.0;

    /** 眼睛在深度轴上的位置：负侧代表玩家一侧，视线朝桌心（+n）看。 */
    private static final double EYE_N = -2.0;

    /** 采样步长。整排跨度约 1.9 格，这个步长下单次扫描约两万个采样点，够细也够快。 */
    private static final double STEP = 0.0001;

    /** 没有任何牌被悬停。 */
    private static final int NONE = -1;

    private static double restHalfWidth() {
        return HandCardPickGeometry.halfWidth(REST_SCALE);
    }

    private static double centerU(int index) {
        return (-((HAND_SIZE - 1) * 0.5) + index) * SPACING;
    }

    /** 按实际渲染参数造一整手牌的矩形；hoveredIndex 那张按悬停倍率放大。 */
    private static List<CardQuad> hand(int hoveredIndex) {
        List<CardQuad> quads = new ArrayList<>(HAND_SIZE);
        for (int index = 0; index < HAND_SIZE; index++) {
            double scale = REST_SCALE * (index == hoveredIndex ? HOVER_SCALE : 1.0);
            quads.add(new CardQuad(
                1000 + index,
                index,
                centerU(index),
                HandCardPickGeometry.centerVOffset(scale),
                index * DEPTH_STEP,
                HandCardPickGeometry.halfWidth(scale),
                HandCardPickGeometry.halfHeight(scale)
            ));
        }
        return quads;
    }

    /** 从 u 处平行于深度轴看过去，返回赢家；瞄准静止态牌面的垂直中心。 */
    private static Hit pickAt(List<CardQuad> quads, double u) {
        return HandCardPickGeometry.pick(
            quads, u, HandCardPickGeometry.centerVOffset(REST_SCALE), EYE_N, 0.0, 0.0, 1.0, MAX_DISTANCE);
    }

    private static int winnerAt(List<CardQuad> quads, double u) {
        Hit hit = pickAt(quads, u);
        return hit == null ? NONE : hit.index();
    }

    /**
     * 二分找出「赢家不再是 expected」的那个 u 边界。
     *
     * @param lo 赢家仍是 expected 的一侧
     * @param hi 赢家已不是 expected 的一侧
     */
    private static double boundary(List<CardQuad> quads, int expected, double lo, double hi) {
        assertEquals(expected, winnerAt(quads, lo), "二分的起点必须仍判 expected");
        assertTrue(winnerAt(quads, hi) != expected, "二分的终点必须已经不判 expected");
        for (int i = 0; i < 200; i++) {
            double mid = (lo + hi) * 0.5;
            if (winnerAt(quads, mid) == expected) {
                lo = mid;
            } else {
                hi = mid;
            }
        }
        return (lo + hi) * 0.5;
    }

    @Test
    @DisplayName("求交基本正确性：正对命中，平行、背向、超距、出框都不命中")
    void intersectBasics() {
        CardQuad quad = new CardQuad(7, 0, 0.0, 0.0, 0.0, restHalfWidth(), HandCardPickGeometry.halfHeight(REST_SCALE));

        Hit head = HandCardPickGeometry.intersect(quad, 0.0, 0.0, EYE_N, 0.0, 0.0, 1.0, MAX_DISTANCE);
        assertNotNull(head, "正对牌面必须命中");
        assertEquals(7, head.cardId());
        assertEquals(2.0, head.distance(), 1.0e-9, "方向已归一化时距离就是求交参数");

        assertNull(
            HandCardPickGeometry.intersect(quad, 0.0, 0.0, EYE_N, 1.0, 0.0, 0.0, MAX_DISTANCE),
            "视线与牌面平行不能命中");
        assertNull(
            HandCardPickGeometry.intersect(quad, 0.0, 0.0, EYE_N, 0.0, 0.0, -1.0, MAX_DISTANCE),
            "牌在背后不能命中，否则转身也能选牌");
        assertNull(
            HandCardPickGeometry.intersect(quad, 0.0, 0.0, EYE_N, 0.0, 0.0, 1.0, 1.0),
            "超出距离上限不能命中");
        assertNull(
            HandCardPickGeometry.intersect(
                quad, restHalfWidth() + 1.0e-6, 0.0, EYE_N, 0.0, 0.0, 1.0, MAX_DISTANCE),
            "交点出了矩形不能命中");
    }

    @Test
    @DisplayName("静止态整排铺满：赢家单调不减，index 0 独占整个牌宽，其余每张独占一个间距")
    void restingRowTilesWithoutGapsOrOverlap() {
        List<CardQuad> quads = hand(NONE);
        double halfWidth = restHalfWidth();
        double left = centerU(0) - halfWidth;
        double right = centerU(HAND_SIZE - 1) + halfWidth;

        int[] samples = new int[HAND_SIZE];
        int previous = NONE;
        for (double u = left + STEP; u < right - STEP; u += STEP) {
            int winner = winnerAt(quads, u);
            assertTrue(winner != NONE, "整排内部不允许有点不到任何牌的空洞，u=" + u);
            assertTrue(winner >= previous, "赢家必须随 u 单调不减，u=" + u);
            previous = winner;
            samples[winner]++;
        }

        assertEquals(2.0 * halfWidth, samples[0] * STEP, 3.0 * STEP,
            "index 0 压在最上层、不被任何牌盖住，独占它的整个牌宽");
        for (int index = 1; index < HAND_SIZE; index++) {
            assertEquals(SPACING, samples[index] * STEP, 3.0 * STEP,
                "index " + index + " 的独占区间必须恰好等于它露在外面的那一条（= 手牌间距）");
        }
    }

    @Test
    @DisplayName("取 index 最小就等于取离眼睛最近的那张：把裁决规则和渲染深度序绑死")
    void winnerIsAlwaysTheCardNearestTheEye() {
        List<CardQuad> quads = hand(NONE);
        double halfWidth = restHalfWidth();
        double eyeV = HandCardPickGeometry.centerVOffset(REST_SCALE);

        for (double u = centerU(0) - halfWidth + STEP; u < centerU(HAND_SIZE - 1) + halfWidth; u += STEP) {
            Hit nearest = null;
            for (CardQuad quad : quads) {
                Hit hit = HandCardPickGeometry.intersect(quad, u, eyeV, EYE_N, 0.0, 0.0, 1.0, MAX_DISTANCE);
                if (hit != null && (nearest == null || hit.distance() < nearest.distance())) {
                    nearest = hit;
                }
            }
            Hit winner = pickAt(quads, u);
            assertNotNull(winner);
            assertNotNull(nearest);
            assertEquals(nearest.index(), winner.index(),
                "index 最小的那张必须就是深度上最靠前的那张，u=" + u);
        }
    }

    @Test
    @DisplayName("朝 index 减小方向零粘滞：悬停放大完全不能推迟切换")
    void noStickinessTowardSmallerIndex() {
        int hovered = 8;
        double halfWidth = restHalfWidth();
        // 第 8 张露在外面的那一条是 [u8 + (halfWidth - SPACING), u8 + halfWidth]，
        // 所以准星要放在这一条里面，而不是牌中心（牌中心其实已经被第 7 张压住了）。
        double inside = centerU(hovered) + halfWidth - SPACING * 0.5;
        double beyond = centerU(hovered) - SPACING;

        double restBoundary = boundary(hand(NONE), hovered, inside, beyond);
        double hoveredBoundary = boundary(hand(hovered), hovered, inside, beyond);

        assertEquals(restBoundary, hoveredBoundary, 1.0e-9,
            "朝 index 减小的方向，悬停放大不该让切换晚一点发生：上一张 index 更小，一进范围就该赢");
        assertEquals(hovered - 1, winnerAt(hand(hovered), hoveredBoundary - 1.0e-6),
            "越过边界后赢家必须立刻是上一张");
    }

    @Test
    @DisplayName("朝 index 增大方向的滞回有界且确实存在：正好等于悬停多伸出的那半张")
    void boundedHysteresisTowardLargerIndex() {
        int hovered = 8;
        double halfWidth = restHalfWidth();
        double inside = centerU(hovered) + halfWidth - SPACING * 0.5;
        // 终点要越过「悬停放大后的半宽」，否则第 8 张还在命中范围内，二分无从下手。
        double beyond = centerU(hovered) + halfWidth * HOVER_SCALE + SPACING;

        double restBoundary = boundary(hand(NONE), hovered, inside, beyond);
        double hoveredBoundary = boundary(hand(hovered), hovered, inside, beyond);
        double hysteresis = hoveredBoundary - restBoundary;
        double bound = (HOVER_SCALE - 1.0) * (2.0 * halfWidth) * 0.5;

        assertTrue(hysteresis > 0.0,
            "悬停放大必须确实产生滞回，否则说明实现根本没把缩放喂进四边形");
        assertTrue(hysteresis <= bound + 1.0e-9,
            "滞回不能超过悬停多伸出的那半张牌宽：" + hysteresis + " > " + bound);
        assertEquals(bound, hysteresis, 1.0e-9, "滞回量应当正好等于悬停多伸出的那半张牌宽");
    }

    @Test
    @DisplayName("不可能振荡：把上一帧的赢家喂回去重算，赢家不变")
    void pickingIsAFixedPoint() {
        double halfWidth = restHalfWidth();
        double left = centerU(0) - halfWidth * HOVER_SCALE;
        double right = centerU(HAND_SIZE - 1) + halfWidth * HOVER_SCALE;

        for (int previous : new int[] {NONE, 0, 1, 7, 8, 9, HAND_SIZE - 1}) {
            List<CardQuad> quads = hand(previous);
            for (double u = left; u <= right; u += STEP) {
                Hit first = pickAt(quads, u);
                if (first == null) {
                    continue;
                }
                Hit second = pickAt(hand(first.index()), u);
                assertNotNull(second, "赢家被放大后不可能反而落空，u=" + u);
                assertEquals(first.index(), second.index(),
                    "赢家喂回去重算必须收敛，否则悬停会在两张牌之间来回跳，u=" + u);
            }
        }
    }

    @Test
    @DisplayName("隔着方块选不到牌：方块交点更近时命中作废")
    void blocksOccludeCards() {
        Hit hit = pickAt(hand(NONE), centerU(0));
        assertNotNull(hit);

        assertTrue(HandCardPickGeometry.occluded(hit, hit.distance() - 0.1), "方块更近时必须判为被挡");
        assertFalse(HandCardPickGeometry.occluded(hit, hit.distance() + 0.1), "方块更远时不该判为被挡");
        assertFalse(HandCardPickGeometry.occluded(hit, Double.POSITIVE_INFINITY), "没有方块时不该判为被挡");
        assertFalse(HandCardPickGeometry.occluded(null, 0.1), "本来就没命中牌，谈不上被挡");
    }

    @Test
    @DisplayName("从牌反算出的判定尺寸与旧的手调常量基本一致：这次是换真源，不是放大判定区")
    void derivedSizeMatchesTheOldHandTunedConstants() {
        // 旧的 render.card-hitbox 是绝对世界单位，照着 private-card-scale 0.8 下的牌面手调出来的。
        // 这条用例是在说：本次改动不该悄悄改变手感，只该把这几个常量换成从牌反算。
        double legacyLength = 0.206;
        double legacyHeight = 0.322;
        double legacyVerticalCenter = -0.205 + legacyHeight * 0.5;

        assertEquals(legacyLength, 2.0 * HandCardPickGeometry.halfWidth(REST_SCALE), 0.02,
            "判定宽度不该和旧手调值差出量级");
        assertEquals(legacyHeight, 2.0 * HandCardPickGeometry.halfHeight(REST_SCALE), 0.02,
            "判定高度不该和旧手调值差出量级");
        assertEquals(legacyVerticalCenter, HandCardPickGeometry.centerVOffset(REST_SCALE), 0.02,
            "中心锚定后的垂直中心不该和旧的底部锚定结果差出量级");

        // 模型常量是从 build.gradle.kts 的 writeCardModel 抄来的，改模型必须同步改这里。
        assertEquals(0.28125, HandCardPickGeometry.MODEL_WIDTH, 1.0e-12);
        assertEquals(0.015625, HandCardPickGeometry.MODEL_THICKNESS, 1.0e-12);
        assertEquals(-0.234375, HandCardPickGeometry.MODEL_Y_MIN, 1.0e-12);
        assertEquals(0.1625, HandCardPickGeometry.MODEL_Y_MAX, 1.0e-12);
    }

    /**
     * 模型常量必须和 build.gradle.kts 里真正生成的牌模型对得上。
     *
     * <p>上面那条只断言常量还等于它自己写死的值——改了模型它照样绿。这条把资源包侧的
     * 真值读出来比对，堵住那个缺口：{@code MODEL_Y_MIN/MAX} 是从 {@code writeCardModel}
     * 的 {@code from}/{@code to} 手抄的，一旦模型的 Y 跨度变了而常量没跟着变，
     * 判定框会**静默**在竖直方向漂移——牌看着在这儿、判定在那儿，症状正是「只有一小块点得到」。
     * 手牌点击捕获器的高度与底边也从这两个常量推导，所以漂移会同时废掉空手右键选牌那条修复。
     *
     * <p>失败条件：改 {@code writeCardModel} 的 {@code from}/{@code to} 而不同步改常量。
     */
    @Test
    @DisplayName("模型常量与 build.gradle.kts 生成的牌模型一致")
    void modelConstantsMatchTheGeneratedCardModel() throws java.io.IOException {
        String build = java.nio.file.Files.readString(java.nio.file.Path.of("build.gradle.kts"));
        int at = build.indexOf("fun writeCardModel(");
        assertTrue(at >= 0, "writeCardModel 不见了，牌模型的真值来源已经变了，这条比对失效");

        java.util.regex.Matcher from = java.util.regex.Pattern
            .compile("\"from\":\\s*\\[\\s*([-\\d.]+),\\s*([-\\d.]+),\\s*([-\\d.]+)\\s*]")
            .matcher(build.substring(at));
        java.util.regex.Matcher to = java.util.regex.Pattern
            .compile("\"to\":\\s*\\[\\s*([-\\d.]+),\\s*([-\\d.]+),\\s*([-\\d.]+)\\s*]")
            .matcher(build.substring(at));
        assertTrue(from.find(), "writeCardModel 里找不到 from，无法比对模型 Y 跨度");
        assertTrue(to.find(), "writeCardModel 里找不到 to，无法比对模型 Y 跨度");

        // ItemDisplay 把模型 [0..16] 立方的中心对齐到实体原点，所以模型坐标要减 8 再除 16。
        double modelYMin = (Double.parseDouble(from.group(2)) - 8.0) / 16.0;
        double modelYMax = (Double.parseDouble(to.group(2)) - 8.0) / 16.0;
        assertEquals(modelYMin, HandCardPickGeometry.MODEL_Y_MIN, 1.0e-12,
            "模型 Y 下界变了但 MODEL_Y_MIN 没同步：判定框会静默向下漂移");
        assertEquals(modelYMax, HandCardPickGeometry.MODEL_Y_MAX, 1.0e-12,
            "模型 Y 上界变了但 MODEL_Y_MAX 没同步：判定框会静默向上漂移");

        // 宽度取 X/Z 两个跨度里较大的那个，较小的是厚度；牌绕 Y 旋转不改变这两个量。
        double spanX = Double.parseDouble(to.group(1)) - Double.parseDouble(from.group(1));
        double spanZ = Double.parseDouble(to.group(3)) - Double.parseDouble(from.group(3));
        assertEquals(Math.max(spanX, spanZ) / 16.0, HandCardPickGeometry.MODEL_WIDTH, 1.0e-12,
            "模型牌面跨度变了但 MODEL_WIDTH 没同步：判定宽度会和牌面脱钩");
        assertEquals(Math.min(spanX, spanZ) / 16.0, HandCardPickGeometry.MODEL_THICKNESS, 1.0e-12,
            "模型厚度变了但 MODEL_THICKNESS 没同步");
    }
}
