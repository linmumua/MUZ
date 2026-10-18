package linmumua.doudizhu.config;

/**
 * 管理菜单数值加减的纯算术，抽出来是为了能脱离 Bukkit 单测。
 *
 * 这些逻辑原先内联在 DoudizhuPlugin 里，而 DoudizhuPlugin 需要真实服务器才能实例化，
 * 于是步长相关的 bug 一直没有测试能覆盖到。
 */
public final class AdminSettingArithmetic {
    /** 存储精度：四位小数。 */
    private static final double STORE_SCALE = 10000.0;

    private AdminSettingArithmetic() {
    }

    /**
     * 保留四位小数。
     *
     * 不能退回一位小数：那样 0.01 步长的加减会在写回时被抹平，
     * 表现为连点多次数字都不动。
     *
     * 也不能停在三位小数：压层类设置（手牌压层深度、预览前后错开）
     * 用的是固定 0.0001 步长，三位小数会把这个增量直接取整抹平，
     * 同样表现为连点数字不动。
     *
     * 四位正好等于当前最细的步长 0.0001，不多留位数。
     * 之前用过五位，但第五位没有任何设置能产生，
     * 反而会把手工配置或历史残留里的第五位一直写回 config.yml。
     *
     * @param value 原始值
     * @return 保留四位小数后的值
     */
    /**
     * 把角度吸附到 90 度的整数倍，仅用于方块椅。
     *
     * 方块椅只能朝四个方向，非 90 倍数的角度放不下去，所以必须吸附。
     *
     * 代价是：方块椅模式下椅子旋转角度那个按钮实际调不动——
     * 固定 1 度步长算出 1.0，吸附后又回到 0。
     * 这是方块椅的固有限制，不是按钮坏了；
     * CE 家具椅子不走这条路，1 度步长正常生效。
     *
     * @param value 原始角度
     * @return 吸附到 90 度整数倍后的角度
     */
    public static double snapToBlockChairRotation(double value) {
        return Math.round(value / 90.0) * 90.0;
    }

    public static double roundToStorePrecision(double value) {
        return Math.round(value * STORE_SCALE) / STORE_SCALE;
    }

    /**
     * 算出本次点击应当使用的步长。
     *
     * @param stepOverride 玩家在 GUI 里选的步长，非正或非有限时忽略
     * @param declaredStep 该设置自己声明的步长，作为兜底
     * @return 实际生效的步长
     */
    public static double effectiveStep(double stepOverride, double declaredStep) {
        return Double.isFinite(stepOverride) && stepOverride > 0.0 ? stepOverride : declaredStep;
    }

    /**
     * 算一次点击后的新值。
     *
     * Shift 的语义是"当前步长的 multiplier 倍"，
     * 所以倍数必须乘在 effectiveStep 的结果上，不能乘在别的基数上。
     *
     * @param current 当前值
     * @param stepOverride 玩家选的步长
     * @param declaredStep 该设置声明的步长
     * @param multiplier 倍数，Shift 点击时为 10
     * @param increase true 为增加
     * @param minValue 下限
     * @param maxValue 上限
     * @return 夹紧并取整后的新值
     */
    public static double nextValue(
        double current,
        double stepOverride,
        double declaredStep,
        int multiplier,
        boolean increase,
        double minValue,
        double maxValue
    ) {
        double step = effectiveStep(stepOverride, declaredStep);
        double delta = step * Math.max(1, multiplier);
        double base = roundToStorePrecision(current);
        double next = base + (increase ? delta : -delta);
        next = Math.max(minValue, Math.min(maxValue, next));
        return roundToStorePrecision(next);
    }

    /**
     * 把一个值换算成「整数步索引」（绝对量）。
     *
     * <p>【滑块不要用这个】滑块窗口请用 {@link #sliderWindow} 的相对索引 —— 绝对索引在大值+小步长时
     * 会超出 float 可精确表示范围。本方法保留给需要「值 ↔ 绝对步数」换算的场景。
     *
     * @param value 当前值
     * @param step 该值使用的步长（&gt; 0 且有限）
     * @return 最接近的整数步索引
     * @throws IllegalArgumentException step 非正或非有限
     */
    public static long toStepIndex(double value, double step) {
        requirePositiveFiniteStep(step);
        return Math.round(roundToStorePrecision(value) / step);
    }

    /**
     * 把整数步索引映射回值（与 {@link #toStepIndex} 互逆）。
     *
     * @param index 步索引
     * @param step 步长
     * @return 对应的值，按存储精度取整
     */
    public static double fromStepIndex(long index, double step) {
        requirePositiveFiniteStep(step);
        return roundToStorePrecision(index * step);
    }

    /**
     * 以当前值为中心构造一个有限的滑块窗口。
     *
     * <p>【关键：窗口锚在原值，不许吸值】滑块第 0 档必须**正好是当前值本身**，不能是
     * {@code round(value/step)*step}——否则玩家把步长切到 0.1、当前值 0.1234，一打开滑块不拖直接确认
     * 就会被悄悄吸成 0.1。第 k 档 = 原值 + k×step，所以「不拖」保持原值，「±1 步」严格加减一个 step。
     *
     * <p>【为什么用相对索引】滑块输入是 32 位 float。若用「值/步长」的绝对长整数当端点，当前值极大
     * （如 1000000）配极小步长（0.0001）时，绝对索引会超出 float 可精确表示的整数范围，相邻档被并
     * 成一个数、拖动跳多步。这里改用**小范围相对指数**（{@code -halfSteps..halfSteps}），float 可精确
     * 表示，映射回 double 时再做 {@code center + index×step}。
     *
     * <p>刻意**不**接收该设置声明的 min/max：调整范围已按用户要求放开，用声明的 min/max 当滑块边界
     * 等于把放开的硬上下限重新施加回来。窗口自身（中心 ± 步数）就是边界。
     *
     * @param center 当前值（作为窗口锚点，不会被吸附）
     * @param step 步长
     * @param halfSteps 中心两侧各留多少步；小于 0 视作 0
     * @return 滑块窗口
     */
    public static SliderWindow sliderWindow(double center, double step, int halfSteps) {
        requirePositiveFiniteStep(step);
        return new SliderWindow(roundToStorePrecision(center), step, Math.max(0, halfSteps));
    }

    private static void requirePositiveFiniteStep(double step) {
        if (!Double.isFinite(step) || step <= 0.0) {
            throw new IllegalArgumentException("步长必须是正有限值：" + step);
        }
    }

    /**
     * 有限滑块窗口：以原值为锚、用**相对**整数指数表达位置。
     *
     * <p>索引是相对量（{@code -halfSteps..halfSteps}），不是「值/步长」的绝对值——这样 float 端点总能
     * 精确表示，且第 0 档恰好等于原值。屏实现用 {@link #valueAt(long)} 把相对索引换算成实际值。
     */
    public record SliderWindow(double center, double step, long halfSteps) {
        /** 起始相对索引（= -halfSteps）。 */
        public long startIndex() {
            return -halfSteps;
        }

        /** 结束相对索引（= +halfSteps）。 */
        public long endIndex() {
            return halfSteps;
        }

        /** 初始相对索引（恒为 0，即原值本身）。 */
        public long initialIndex() {
            return 0L;
        }

        /** 相对索引对应的值：原值 + index×step，按存储精度取整。 */
        public double valueAt(long relativeIndex) {
            return roundToStorePrecision(center + relativeIndex * step);
        }

        public float start() {
            return (float) (-halfSteps);
        }

        public float end() {
            return (float) halfSteps;
        }

        public Float initial() {
            return 0f;
        }
    }
}
