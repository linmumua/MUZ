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
 * <p>【阶梯语义（默认，非配置项）】：<b>第三批（每人 9 张）发完才允许明牌</b>，此时 ×3；
 * 第四批 ×2；第五批及以后固定 ×1。「以此类推」若继续递减会跌到 ×0，
 * ×0 在倍率语义里等于「白送」，因此按 {@link #MIN_MULTIPLIER} 兜底成 1，
 * 即实际的固定阶梯是 3 → 2 → 1 → 1（末批每人 2 张的批次同样是 1）。
 *
 * <p>【为什么基准移到第 3 批（1.10.73 语义变更）】明牌窗口从「首批即开、贯穿整条时间线」
 * 改成「第三批发完只开 60 tick」。窗口只覆盖第 3 批那一刻，若阶梯仍从第 1 批起算，
 * 窗口期内能拿到的永远只有下限 ×1，「早明牌倍率高」这条设计就名存实亡。
 * 因此阶梯锚点跟着窗口一起后移到第 3 批：窗口开在第 3 批 → 可冻结 ×3；
 * 抢地主加赛延长到第 4/5 批 → 依次 ×2/×1。
 *
 * <p>【为什么 0..2 批返回 0 而不是 1】：0 表示「窗口还没到（或本局已过窗口）」，
 * 它必须能被与「窗口已开但倍率已降到 1」区分开，否则调用方无法据返回值判断能不能点。
 * 因此 0 是本类的「不可明牌」哨兵值，不会出现在任何合法倍率位置上。
 */
public final class RevealMultiplierLadder {
    /** 明牌倍率下限：第五批及以后固定为 1，永不降到 0。 */
    public static final int MIN_MULTIPLIER = 1;
    /** 明牌倍率上限：窗口开启批次（第 3 批）的倍率，同时也是整局明牌倍率的天花板（不叠乘）。 */
    public static final int MAX_MULTIPLIER = 3;
    /** 窗口开启那一批的倍率；与 {@link #MAX_MULTIPLIER} 同源，单独命名是为了让调用点可读。 */
    public static final int FIRST_BATCH_MULTIPLIER = 3;
    /**
     * 允许明牌的第一批（1-based 的「已完整发完批数」）：每人 3 张 × 3 批 = 9 张。
     *
     * <p>与 {@code RoundOpeningCoordinator.REVEAL_WINDOW_AFTER_BATCHES} 必须同值：
     * 一处定义「窗口什么时候开」，一处定义「倍率从第几批起算」，两者错开就会出现
     * 「窗口开着但倍率是 0」或「倍率已 3 但窗口没开」。
     */
    public static final int FIRST_REVEALABLE_BATCH = 3;
    /** 每多完成一批，倍率下降的步长。 */
    private static final int LADDER_STEP = 1;

    private RevealMultiplierLadder() {
    }

    /**
     * 按「已完成发牌批数」给出当刻可冻结的明牌倍率。
     *
     * @param completedDealBatches 已完整发完的批次数量；0..2 表示尚未到第 3 批
     * @return 0 表示窗口未开、不可明牌；否则为 {@code MIN_MULTIPLIER..MAX_MULTIPLIER} 之间的倍率
     */
    public static int multiplierForCompletedBatches(int completedDealBatches) {
        if (completedDealBatches < FIRST_REVEALABLE_BATCH) {
            return 0;
        }
        int steps = completedDealBatches - FIRST_REVEALABLE_BATCH;
        int multiplier = FIRST_BATCH_MULTIPLIER - steps * LADDER_STEP;
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
