package linmumua.doudizhu.world;

import java.util.ArrayList;
import java.util.List;

/**
 * 手牌拾取的纯几何：视线射线与每张牌所在平面求交。
 *
 * <p>这里刻意不依赖 Bukkit，因为拾取是纯数学，能单独测。渲染侧只做委托，保证
 * 「牌画在哪」和「点到哪张」来自同一份计算，不可能各算一遍而漂移。
 *
 * <h2>为什么不再用判定框切片</h2>
 *
 * <p>旧方案把每张牌切成若干个 Interaction 实体，靠实体的碰撞箱决定点到哪张。
 * 那条路有三个绕不开的问题：切片厚度决定采样精度、动画期间每 tick 全量 teleport、
 * 以及「每张牌该露出哪一条」必须由调用方算对并传进来——一旦叠放方向弄反，
 * 判定条就整体偏到牌被遮住的那一侧。
 *
 * <p>解析求交把这三件事一起消掉：每张牌就是一个**全宽**矩形，谁被谁遮住由
 * 「命中多张时取 index 最小」这条规则自动得出，不需要任何人去算可见条。
 *
 * <h2>叠放顺序（已取证，不要凭直觉改）</h2>
 *
 * <p>手牌的深度序是 {@code delta = -((n-1)/2) + index}，配上 {@code handDepth} 指向桌心
 * （即背离玩家），所以 <b>index 0 最靠近玩家、压在最上层</b>，index 越大压得越深。
 * 因此裁决规则是**取 index 最小**：视觉上最上层的那张赢。
 *
 * <p>这条规则的一个必然推论，方向和「后画的盖住前画的」那种直觉相反：如果调用方喂进来的
 * 是<b>逐张不同</b>的尺寸（只把被悬停那张放大），被悬停的牌会朝 <b>index 增大</b> 的方向多伸出
 * {@code (hoverScale - 1) × 牌宽 / 2}，那一侧就出现有界滞回；朝 <b>index 减小</b> 的方向零粘滞。
 *
 * <p>渲染侧现在<b>不走这条路</b>：它统一喂 {@link #envelope} 算出的包络，每张牌尺寸相同，
 * 于是滞回退化为 0、两侧都零粘滞。上面那段描述保留是因为 {@link #pick} 本身仍支持逐张不同
 * 尺寸，相关用例也还在守这个函数能力——但它已不是生产路径。
 *
 * <h2>局部坐标系</h2>
 *
 * <p>所有输入都在牌桌的局部正交系里，以消掉桌子 yaw 和座位朝向，让数学与座位无关：
 *
 * <ul>
 *   <li>{@code u} = 手牌铺开方向（{@code handStep} 归一化）
 *   <li>{@code v} = 世界 Y
 *   <li>{@code n} = 深度方向（{@code handDepth} 归一化，指向桌心 = 背离玩家）
 * </ul>
 *
 * <p>调用方必须保证两件事，否则下面的数学不成立：
 * {@code u} 与 {@code n} 互相垂直（三种座位的 {@code handStep}/{@code handDepth}
 * 分别是 X/Z、−Z/X、Z/−X，都满足），以及方向向量已归一化——那样求交参数 {@code t}
 * 才直接等于距离。
 */
public final class HandCardPickGeometry {

    /**
     * 牌模型的几何尺寸，单位是格。
     *
     * <p>数值来自 build.gradle.kts 的 writeCardModel：
     * {@code "from": [8, 4.25, 5.75], "to": [8.25, 10.6, 10.25]}，16 单位 = 1 格。
     * 若改动那份模型，必须同步改这里。
     *
     * <p>牌面宽度取 X/Z 两个跨度里较大的那个（4.5 单位）；较小的 0.25 单位是厚度。
     */
    public static final double MODEL_WIDTH = 4.5 / 16.0;

    /** 牌模型厚度（格）：0.25 单位。牌很薄，这也是为什么必须逐张求交而不能当一个平面。 */
    public static final double MODEL_THICKNESS = 0.25 / 16.0;

    /**
     * 牌模型的 Y 下界，相对 ItemDisplay 原点（格）。
     *
     * <p>ItemDisplay 把模型 {@code [0..16]} 立方的中心对齐到实体原点，所以模型坐标要减 8 再除 16：
     * {@code (4.25 - 8) / 16}。绕 Y 轴的那条模型旋转不改变 Y 跨度，所以这两个界是确定的，
     * 不受「牌面宽度沿哪个轴」那个歧义影响。
     */
    public static final double MODEL_Y_MIN = (4.25 - 8.0) / 16.0;

    /** 牌模型的 Y 上界，相对 ItemDisplay 原点（格）：{@code (10.6 - 8) / 16}。 */
    public static final double MODEL_Y_MAX = (10.6 - 8.0) / 16.0;

    /** 视线与牌面近乎平行时的判平行阈值。 */
    private static final double EPS = 1.0e-9;

    private HandCardPickGeometry() {
    }

    /**
     * 一张牌在拾取空间里的矩形：中心锚定。
     *
     * <p>未选中的牌，半宽半高应当来自 {@link #envelope}，也就是牌从静止到完全悬停扫过的空间
     * 的并集，<b>不要</b>传牌这一帧真正拿到的缩放和抬升——理由见 {@link #envelope} 的说明。
     * 已选中的牌使用 {@link #envelopeForSelected}，并集里额外包住选中抬升。
     *
     * @param cardId 牌的实体标识，用于回传给渲染侧
     * @param index 在手牌里的下标，裁决时取最小的那个
     * @param centerU 沿铺开方向的中心
     * @param centerV 垂直方向的中心（世界 Y）
     * @param centerN 深度方向的中心
     * @param halfWidth 半宽，取自包络
     * @param halfHeight 半高，取自包络
     */
    public record CardQuad(
        int cardId,
        int index,
        double centerU,
        double centerV,
        double centerN,
        double halfWidth,
        double halfHeight
    ) {
    }

    /**
     * 一次命中。
     *
     * @param cardId 命中的牌
     * @param index 命中牌的下标
     * @param distance 眼睛到交点的距离（方向已归一化时就等于求交参数 t）
     * @param localU 交点相对牌中心的横向偏移，调试可视化用
     * @param localV 交点相对牌中心的纵向偏移，调试可视化用
     */
    public record Hit(int cardId, int index, double distance, double localU, double localV) {
    }

    /**
     * 牌面矩形的半宽。
     *
     * @param faceWidthScale 牌面宽度方向上牌这一帧实际拿到的缩放分量
     */
    public static double halfWidth(double faceWidthScale) {
        return MODEL_WIDTH * 0.5 * faceWidthScale;
    }

    /**
     * 牌面矩形的半高。
     *
     * @param scaleY 牌这一帧实际拿到的 Y 缩放分量
     */
    public static double halfHeight(double scaleY) {
        return (MODEL_Y_MAX - MODEL_Y_MIN) * 0.5 * scaleY;
    }

    /**
     * 牌面矩形中心相对 ItemDisplay 原点的垂直偏移。
     *
     * <p>这就是「中心锚定」的落点：来自牌模型自身的 Y 跨度中点，而不是手调的配置值。
     * 旧的 {@code render.card-hitbox-offset.vertical} 是绝对世界单位，玩家一改
     * {@code private-card-scale} 牌会变大而判定不会；这里跟着 {@code scaleY} 走就不会失配。
     *
     * @param scaleY 牌这一帧实际拿到的 Y 缩放分量
     */
    public static double centerVOffset(double scaleY) {
        return (MODEL_Y_MIN + MODEL_Y_MAX) * 0.5 * scaleY;
    }

    /**
     * 判定矩形的包络尺寸。
     *
     * <p>对于未选中的牌，来自 {@link #envelope}，覆盖静止态到完全悬停态的全部空间并集。
     * 对于已选中的牌，来自 {@link #envelopeForSelected}，覆盖静止态到完全抬起态的并集。
     *
     * @param halfWidth 半宽
     * @param centerVOffset 包络中心相对 ItemDisplay 原点的垂直偏移
     * @param halfHeight 包络半高
     */
    public record Envelope(double halfWidth, double centerVOffset, double halfHeight) {
    }

    /**
     * 调试线框上的一段，局部坐标。
     *
     * <p>{@code u} 沿手牌铺开方向，{@code v} 是世界 Y（两者定义见类注释的局部坐标系）。
     * 记的是<b>段中点</b>，渲染侧把一个实体摆在这里、按 {@code length} 沿 {@code horizontal}
     * 指的那个轴拉长，就得到一条边。
     *
     * @param u 段中点的 u 分量
     * @param v 段中点的 v 分量
     * @param length 这一段的长度（格）
     * @param horizontal true 表示这段沿 u 轴延伸（上下两条边），false 表示沿 v 轴（左右两条边）
     */
    public record WireDot(double u, double v, double length, boolean horizontal) {
    }

    /**
     * 调试线框每条边切成几段。
     *
     * <p>刻意按「每边固定段数」而不是「固定步长」采样：段数固定，实体总数就恒定
     * （{@code 4 × SEGMENTS_PER_EDGE} 个），渲染侧可以池化复用而不必设动态上限。
     * 按步长采样则实体数随包络尺寸浮动，配置一改就可能爆量。
     */
    public static final int SEGMENTS_PER_EDGE = 8;

    /**
     * 算出一个包络矩形的调试线框采样点。
     *
     * <p>四条边各切 {@link #SEGMENTS_PER_EDGE} 段，返回每段的中点。返回顺序是
     * 下边、上边、左边、右边，每条边内部沿坐标递增——顺序本身不影响渲染，
     * 但固定下来便于测试逐段核对。
     *
     * <p>这个方法和 {@link #intersect} 读的是同一个 {@link Envelope}，所以画出来的框
     * 就是真正的判定边界，不存在「显示一套、判定另一套」的漂移。
     *
     * @param centerU 包络中心的 u 分量
     * @param env 包络尺寸
     * @return 线框采样点，长度恒为 {@code 4 × SEGMENTS_PER_EDGE}
     */
    public static List<WireDot> wireframe(double centerU, Envelope env) {
        double left = centerU - env.halfWidth();
        double right = centerU + env.halfWidth();
        double bottom = env.centerVOffset() - env.halfHeight();
        double top = env.centerVOffset() + env.halfHeight();
        double horizontalStep = (right - left) / SEGMENTS_PER_EDGE;
        double verticalStep = (top - bottom) / SEGMENTS_PER_EDGE;
        List<WireDot> dots = new ArrayList<>(4 * SEGMENTS_PER_EDGE);
        for (int i = 0; i < SEGMENTS_PER_EDGE; i++) {
            double midU = left + horizontalStep * (i + 0.5);
            dots.add(new WireDot(midU, bottom, horizontalStep, true));
            dots.add(new WireDot(midU, top, horizontalStep, true));
        }
        for (int i = 0; i < SEGMENTS_PER_EDGE; i++) {
            double midV = bottom + verticalStep * (i + 0.5);
            dots.add(new WireDot(left, midV, verticalStep, false));
            dots.add(new WireDot(right, midV, verticalStep, false));
        }
        return dots;
    }

    /**
     * 牌本体（发光轮廓所在）的矩形，供调试对照用。
     *
     * <p>与包络的区别正是「不严丝合缝」的来源：包络额外并进了 hover 抬升和选中抬升，
     * 而牌本体只有模型盒那么大。
     *
     * @param scaleX 牌面宽度方向的缩放分量
     * @param scaleY Y 缩放分量
     * @return 贴合牌模型盒的矩形
     */
    public static Envelope cardBody(double scaleX, double scaleY) {
        return new Envelope(halfWidth(scaleX), centerVOffset(scaleY), halfHeight(scaleY));
    }

    /**
     * 算出未选中牌的判定矩形包络，<b>与牌当帧的动画状态无关</b>。
     *
     * <h2>为什么判定不能用牌这一帧的真实几何</h2>
     *
     * <p>抬升和放大都是「被悬停」这件事的<b>输出</b>。如果判定矩形跟着牌当帧的位置和缩放走，
     * 就形成闭环：命中 → 牌抬起 → 判定区跟着上移 → 准星落到区外 → 取消命中 → 牌落回
     * → 又命中。周期正好是动画时长，表现为牌以固定频率上下抖。准星落在牌底部那一条
     * 高 {@code maxLift} 的带子里时必然进环——牌一抬起，这条带子就空了。
     *
     * <p>加时间冷却或距离滞回只能压低抖动频率，环还在。要根治只有一条路：<b>让判定几何
     * 成为悬停状态的不动点</b>。包络就是这个不动点——它同时包住静止态和最大态，所以
     * 「命中 / 不命中」的判定结果根本不随牌的动画状态改变，第三步被从几何上掐断。
     *
     * <p>代价是判定区比牌的静止视觉范围高出 {@code maxLift} 加上放大增量：牌抬起后它下方
     * 那截空气、牌静止时它上方那截空气都算命中区。这个宽容度是有意接受的，它让准星
     * 稍微偏出牌外仍能选中，比抖动好得多。
     *
     * <p>横向不存在这个环（悬停不横移牌），但半宽仍按放大后取，否则牌放大后多伸出的
     * 那一圈会点不到；取最大态同样保证了半宽与动画状态无关。
     *
     * <p><b>不要改成「取通道半宽」</b>。牌宽大于间距时牌互相重叠，此时牌的几何中心被
     * 上层邻居遮住，玩家看得见的只是右侧那一条（宽度恰好等于间距）。取 max 让每张牌的
     * 获胜条落在那条可见区上；改成通道半宽会把判定区移到被遮住的几何中心，
     * 反而与视觉错位。{@code HandCardPickEnvelopeTest} 的
     * {@code visibleSliverOfEachCardHitsItself} 守着这条性质。
     *
     * <p>深度方向同样不存在这个环：悬停既不沿法向平移牌，也不改变牌的厚度，
     * 牌平面在世界里的位置与朝向完全不随悬停变化，交点因此没有漂移源。
     *
     * @param restScaleX 静止态牌面宽度方向的缩放分量
     * @param restScaleY 静止态的 Y 缩放分量
     * @param maxScaleX 完全悬停时牌面宽度方向的缩放分量
     * @param maxScaleY 完全悬停时的 Y 缩放分量
     * @param maxLift 未选中牌在悬停动画里可能达到的最大抬升值，单位格。只取悬停抬升
     *     （不含选中抬升：已选中的牌走 {@link #envelopeForSelected}），
     *     并且必须把 BACK_OUT 这类会过冲的曲线算进去——过冲那一截若不在包络里，
     *     牌上沿会在动画末段冲出判定区
     * @param laneHalfWidth 半宽下限，单位格，应传 {@code render.hand-spacing / 2}
     * @return 与动画状态无关的包络尺寸
     */
    public static Envelope envelope(
        double restScaleX,
        double restScaleY,
        double maxScaleX,
        double maxScaleY,
        double maxLift,
        double laneHalfWidth
    ) {
        double restCenter = centerVOffset(restScaleY);
        double restHalf = halfHeight(restScaleY);
        // 「放大到最大但还没抬起来」这一帧必须单独纳入并集，它不是两个端点的插值。
        // 缩放对原始进度线性插值，抬升却走动画曲线：EASE_IN_OUT 这类曲线起步导数为 0，
        // 于是动画早期牌已经涨大、却几乎没抬起，牌下缘比静止态更低——低出来的那一条
        // 若不在包络里，准星停在牌最下沿就会命中即脱靶，抖动闭环从这条缝里回来。
        double grownCenter = centerVOffset(maxScaleY);
        double grownHalf = halfHeight(maxScaleY);
        double liftedCenter = maxLift + grownCenter;
        double bottom = Math.min(restCenter - restHalf, grownCenter - grownHalf);
        bottom = Math.min(bottom, liftedCenter - grownHalf);
        double top = Math.max(restCenter + restHalf, grownCenter + grownHalf);
        top = Math.max(top, liftedCenter + grownHalf);
        double halfWidth = Math.max(
            Math.max(halfWidth(restScaleX), halfWidth(maxScaleX)),
            Math.max(0.0, laneHalfWidth));
        return new Envelope(halfWidth, (bottom + top) * 0.5, (top - bottom) * 0.5);
    }

    /**
     * 已选中牌的判定包络：<b>静止态与完全抬起态的并集</b>，与动画状态无关。
     *
     * <h2>为什么不能贴合牌本体（已取证的故障）</h2>
     *
     * <p>旧实现让包络严格贴合牌本体、整体随当帧 {@code currentLift} 上移。它只顾了
     * 「判定区与视觉一致」，却漏掉一条更硬的约束：<b>判定区必须继续覆盖这张牌未抬起时的位置</b>。
     *
     * <p>{@code render.selected-card.lift} 默认 0.18 格，而牌本体全高只有
     * {@code (MODEL_Y_MAX - MODEL_Y_MIN) × scaleY ≈ 0.397 × 0.35 ≈ 0.139} 格。
     * <b>抬升大于牌自身高度</b>，所以牌抬到位后判定区与原位置完全不重叠，原位置整块空出来；
     * 而未选中牌的包络下界是静止底部（只向上扩张 hover 抬升），会完整覆盖那块空位。
     * 再叠上 {@link #resolve} 的「取 index 最小」，准星停在原处点击必然被相邻的未选中牌抢走：
     * 右键表现为「选不中 / 选错张」，左键则因为命中的牌不在选中集合里而被拒绝出牌，
     * 表现为「出牌没反应」。两个现象是同一个根因。
     *
     * <p>所以这里把静止位置一起并进包络：{@code bottom} 取静止底部，{@code top} 取抬到位
     * （含过冲）后的顶部。这与 {@link #envelope} 处理 hover 抬升的做法是同一套哲学——
     * 判定几何是动画的不动点，用宽容度换稳定。
     *
     * <p>代价是牌抬起后它下方腾出来的那一条仍归它自己，相邻牌在那一条里点不到。这是有意
     * 接受的：相邻牌自己的独占可见条（宽度等于 spacing）不受影响，而「取消选中和出牌必须
     * 能在原处点到」比那一条的归属重要得多。
     *
     * <p>尺寸沿用未选中包络，只沿竖直方向平移，保证判定与选中状态无关。
     *
     * @param unselected 未选中态的包络，尺寸将被原样沿用
     * @param restScaleY 静止态的 Y 缩放分量，用来定位牌未抬起时的位置
     * @param maxSelectedLift 选中抬升可能达到的最大值，单位格；必须把 BACK_OUT 这类曲线的
     *     过冲算进去，否则牌上沿会在动画末段冲出判定区
     * @return 与动画状态、选中状态都无关的包络
     */
    public static Envelope envelopeForSelected(
        Envelope unselected,
        double restScaleY,
        double maxSelectedLift
    ) {
        double restBottom = centerVOffset(restScaleY) - halfHeight(restScaleY);
        // 抬升可能被配成 0 或负数，取 max 保证不会算出比静止位置还低的上界
        double liftedTop = Math.max(0.0, maxSelectedLift)
            + centerVOffset(restScaleY) + halfHeight(restScaleY);
        return new Envelope(
            unselected.halfWidth(),
            (restBottom + liftedTop) * 0.5,
            (liftedTop - restBottom) * 0.5
        );
    }

    /**
     * 把未选中与已选中两个包络统一成同一尺寸，返回两者共用的那个尺寸。
     *
     * <p>两个包络宽高相同，只有竖直中心不同，各自盖住自己那套位置。
     *
     * @param unselected 未选中包络
     * @param selected 已选中包络
     * @return 长度为 2 的数组，[0] 是统一后的未选中包络，[1] 是统一后的已选中包络
     */
    public static Envelope[] unifiedEnvelopes(Envelope unselected, Envelope selected) {
        double halfWidth = Math.max(unselected.halfWidth(), selected.halfWidth());
        double halfHeight = Math.max(unselected.halfHeight(), selected.halfHeight());
        return new Envelope[] {
            new Envelope(halfWidth, unselected.centerVOffset(), halfHeight),
            new Envelope(halfWidth, selected.centerVOffset(), halfHeight)
        };
    }

    /**
     * 射线与单张牌的矩形求交。
     *
     * @param quad 目标矩形
     * @param eyeU 眼睛位置的 u 分量
     * @param eyeV 眼睛位置的 v 分量
     * @param eyeN 眼睛位置的 n 分量
     * @param dirU 视线方向的 u 分量，必须已归一化
     * @param dirV 视线方向的 v 分量，必须已归一化
     * @param dirN 视线方向的 n 分量，必须已归一化
     * @param maxDistance 距离上限
     * @return 命中信息，未命中返回 null
     */
    public static Hit intersect(
        CardQuad quad,
        double eyeU,
        double eyeV,
        double eyeN,
        double dirU,
        double dirV,
        double dirN,
        double maxDistance
    ) {
        if (quad == null || Math.abs(dirN) < EPS) {
            // 视线与牌面平行：没有交点，也不该退化成「无限远处命中」。
            return null;
        }
        double t = (quad.centerN() - eyeN) / dirN;
        if (t < 0.0 || t > maxDistance) {
            // t < 0 是牌在背后。不判负向，否则转身也能选牌。
            return null;
        }
        double localU = eyeU + t * dirU - quad.centerU();
        double localV = eyeV + t * dirV - quad.centerV();
        if (Math.abs(localU) > quad.halfWidth() || Math.abs(localV) > quad.halfHeight()) {
            return null;
        }
        return new Hit(quad.cardId(), quad.index(), t, localU, localV);
    }

    /**
     * 从全部命中里裁决赢家：<b>取 index 最小</b>，也就是视觉上压在最上层的那张。
     *
     * <p>这个函数无状态、只看 index，所以「上一帧选中谁」不可能影响这一帧的结果——
     * 这是防振荡的证明点，比按交点距离比较更结实（牌只有 0.0156 格厚，
     * 距离比较会被浮点噪声翻掉）。
     *
     * @param hits 命中集合，可以为空
     * @return index 最小的那次命中，没有命中则返回 null
     */
    public static Hit resolve(List<Hit> hits) {
        if (hits == null || hits.isEmpty()) {
            return null;
        }
        Hit best = null;
        for (Hit hit : hits) {
            if (best == null || hit.index() < best.index()) {
                best = hit;
            }
        }
        return best;
    }

    /**
     * 对整手牌求交并裁决，一步到位。
     *
     * @param quads 整手牌的矩形
     * @param eyeU 眼睛位置的 u 分量
     * @param eyeV 眼睛位置的 v 分量
     * @param eyeN 眼睛位置的 n 分量
     * @param dirU 视线方向的 u 分量，必须已归一化
     * @param dirV 视线方向的 v 分量，必须已归一化
     * @param dirN 视线方向的 n 分量，必须已归一化
     * @param maxDistance 距离上限
     * @return 赢家，没命中任何牌则返回 null
     */
    public static Hit pick(
        List<CardQuad> quads,
        double eyeU,
        double eyeV,
        double eyeN,
        double dirU,
        double dirV,
        double dirN,
        double maxDistance
    ) {
        if (quads == null || quads.isEmpty()) {
            return null;
        }
        List<Hit> hits = new ArrayList<>(quads.size());
        for (CardQuad quad : quads) {
            Hit hit = intersect(quad, eyeU, eyeV, eyeN, dirU, dirV, dirN, maxDistance);
            if (hit != null) {
                hits.add(hit);
            }
        }
        return resolve(hits);
    }

    /**
     * 命中是否被方块挡住。
     *
     * <p>旧方案用 {@code getTargetEntity} 顺手带了视线阻挡，改成解析求交后这件事必须显式补。
     * 判据放在这里而不是散在调用点，是为了让「隔墙不能选牌」也进回归测试。
     *
     * @param hit 牌的命中，可为 null
     * @param blockDistance 视线打到方块的距离，没打到方块传 {@link Double#POSITIVE_INFINITY}
     * @return 被挡住则返回 true
     */
    public static boolean occluded(Hit hit, double blockDistance) {
        return hit != null && blockDistance < hit.distance();
    }
}
