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
}
