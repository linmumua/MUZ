package linmumua.doudizhu.game;

/**
 * 明牌倍率阶梯：纯策略类，无 Bukkit、无 IO、不读配置。
 *
 * <p>【为什么要有这个类】：明牌倍率以前是硬编码的「一局最多 ×2」，写在
 * {@code GameTable.revealHand} 与 {@code GameTable.coreScoreFor} 两处，两边各算一份。
 * 现在倍率取决于「发牌发到第几批时点的明牌」，是一条随时间递减的阶梯，
 * 因此把「批数 → 倍率」收口成唯一一处纯函数：状态判定（{@code currentRevealMultiplier()}）、
 * 点击冻结（{@code revealHand}）、结算（{@code coreScoreFor}）、按钮与占位文案
 * 全部消费同一份结果，不允许任何地方再写第二个算式。
 *
 * <p>【阶梯语义（默认，非配置项）】：首批（每人 3 张）发完即可明牌，此时 ×3；
 * 第二批发完 ×2；第三批及以后固定 ×1。「以此类推」若继续递减会跌到 ×0，
 * ×0 在倍率语义里等于「白送」，因此按 {@link #MIN_MULTIPLIER} 兜底成 1，
 * 即实际的固定阶梯是 3 → 2 → 1 → 1 → 1（最后一批 2 张的批次同样是 1）。
 *
 * <p>【为什么 0 批返回 0 而不是 1】：0 批表示「首批还没发完，明牌窗口尚未打开」，
 * 它必须能被与「窗口已开但倍率已降到 1」区分开，否则调用方无法据返回值判断能不能点。
 * 因此 0 是本类的「不可明牌」哨兵值，不会出现在任何合法倍率位置上。
 */
public final class RevealMultiplierLadder {
    /** 明牌倍率下限：第三批及以后固定为 1，永不降到 0。 */
    public static final int MIN_MULTIPLIER = 1;
    /** 明牌倍率上限：首批发完时的倍率，同时也是整局明牌倍率的天花板（不叠乘）。 */
    public static final int MAX_MULTIPLIER = 3;
    /** 首批（每人 3 张）发完时的倍率；与 {@link #MAX_MULTIPLIER} 同源，单独命名是为了让调用点可读。 */
    public static final int FIRST_BATCH_MULTIPLIER = 3;
    /** 每多完成一批，倍率下降的步长。 */
    private static final int LADDER_STEP = 1;

    private RevealMultiplierLadder() {
    }

    /**
     * 按「已完成发牌批数」给出当刻可冻结的明牌倍率。
     *
     * @param completedDealBatches 已完整发完的批次数量；0 表示首批未发完
     * @return 0 表示窗口未开、不可明牌；否则为 {@code MIN_MULTIPLIER..MAX_MULTIPLIER} 之间的倍率
     */
    public static int multiplierForCompletedBatches(int completedDealBatches) {
        if (completedDealBatches <= 0) {
            return 0;
        }
        int multiplier = FIRST_BATCH_MULTIPLIER - (completedDealBatches - 1) * LADDER_STEP;
        return Math.clamp(multiplier, MIN_MULTIPLIER, MAX_MULTIPLIER);
    }

    /**
     * 把模板里的 {@code %multiplier%} 换成倍率的裸数字（模板自带 × 之类的修饰符）。
     *
     * <p>模板里没有该占位符时原样返回：文案归配置与 UI 所有，这里不猜测、不追加后缀。
     *
     * @param template 文案模板，可为 null
     * @param multiplier 倍率；低于下限时按 {@link #MIN_MULTIPLIER} 处理，避免文案出现 ×0
     * @return 替换后的文案；template 为 null 时返回空串
     */
    public static String withMultiplier(String template, int multiplier) {
        if (template == null || template.isEmpty()) {
            return "";
        }
        return template.replace("%multiplier%", String.valueOf(Math.max(MIN_MULTIPLIER, multiplier)));
    }
}
