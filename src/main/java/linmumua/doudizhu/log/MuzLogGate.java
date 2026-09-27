package linmumua.doudizhu.log;

import java.util.logging.Filter;
import java.util.logging.Level;
import java.util.logging.LogRecord;
import java.util.logging.Logger;

/**
 * MUZ 统一日志门禁：{@code debug.enabled=false} 时静默本插件的【全部】WARNING，{@code SEVERE} 不受影响。
 *
 * <p>【本插件的 WARNING 语义（linmumua 明确要求，覆盖历史约定）】过去本插件刻意区分「诊断类」与
 * 「故障类」，并保证故障类 WARNING 永远留痕。现在该区分【已被用户明确取消】：{@code debug.enabled=false}
 * （出厂默认）时，本插件发出的所有 {@code WARNING} 一律不输出；{@code SEVERE}（含 {@code Error} 级）
 * 照常输出。需要排查故障时把 {@code debug.enabled} 改为 {@code true} 并执行 {@code /muz reload}，
 * WARNING 立刻恢复（含原有的限频与堆栈语义，本门禁不改变它们）。
 *
 * <p>【怎么做到「本插件的 WARNING」而不是「全局 WARNING」】门禁实现为 {@link Filter}，由
 * {@code DoudizhuPlugin.installLogGate()} 装在【本插件自己的】logger 上：
 * <ul>
 *   <li>{@code getLogger()} —— Paper 的 {@code PluginLogger}，其 {@code log} 会走
 *       {@code Logger.log(LogRecord)}，因此 {@code Logger} 自身的 filter 会在发布前被查询，
 *       丢弃记录即不会传播给父 logger / 控制台 handler，本插件 170 余处
 *       {@code plugin.getLogger().warning(...)} 一次覆盖；</li>
 *   <li>{@code Logger.getLogger("MUZ")} —— 插件的兜底命名 logger（无 Plugin 注入时用）。</li>
 * </ul>
 * <b>绝不装在 root logger 或服务端共享 logger 上</b>：那会连别的插件的 WARNING 一起吞掉
 * （本插件的 {@code Bukkit.getLogger()} 出口只有静态核心探测器一处，走 {@link #warn} 显式门控）。
 *
 * <p>【为什么用 Filter 而不是逐个改写调用点】调用点有 170 余处且分布在几十个类里，逐点改写既易漏又会
 * 制造巨大 diff；装在自有 logger 上则天然覆盖「直接 Logger 路径」且以后新增的调用点自动受控。
 * {@code MuzLogGateCoverageContractTest} 扫描主源码，钉住「除自有 logger 与 {@link #warn} 外，
 * 没有绕过门禁的 WARNING 出口」。
 *
 * <p>【绝不动玩家文本】{@code MuzTheme.warning(...)}、{@code TypewriterTextStyle.warning(...)} 等是
 * 文本/配色 API，不是日志出口，本门禁按 {@link Level} 判定、与名字无关，不会误伤它们。
 *
 * <p>【线程安全】{@link #debugEnabled} 是 {@code volatile}：写入发生在启动/重载的主线程，读取发生在任意
 * owner lane（含 region 线程与异步线程）的日志路径上，靠 volatile 保证可见性。{@code install} 只由
 * {@code onEnable} / 服务装配在主线程调用，装好后不再变更。本类【只依赖 {@code java.util.logging}，
 * 不碰任何 Bukkit API】，因此不会引入 Folia 线程违规。
 *
 * <p>【未配置前放行（fail-open）】{@link #debugEnabled} 初值为 {@code true}：在
 * {@code DoudizhuPlugin.installLogGate()} 写入真实快照（{@code onEnable} 第一句）之前不拦任何东西，
 * 避免「配置还没读到就把线索一起静默」。出厂 {@code config.yml} 的 {@code debug.enabled} 默认 {@code false}，
 * 故正常运行期 WARNING 默认是关闭的。
 */
public final class MuzLogGate {

    /**
     * {@code debug.enabled} 的运行期快照；{@code true} 表示允许 WARNING 输出。
     *
     * <p>初值 {@code true} 只是 fail-open 窗口（见类注释），不是「默认开启」：真实值由
     * {@code DoudizhuPlugin.installLogGate()} 在 {@code onEnable} 第一句写入。
     */
    private static volatile boolean debugEnabled = true;

    /** 全局唯一的门禁过滤器；所有 MUZ 自有 logger 共用同一实例，便于 {@link #install} 做幂等判定。 */
    private static final Filter GATE = MuzLogGate::isLoggable;

    private MuzLogGate() {
    }

    /**
     * 门禁判据：{@code debug} 关闭时只丢弃 {@code WARNING}，{@code SEVERE} 及以上（与其它级别）一律放行。
     *
     * <p>按 {@link Level} 的整型值判定而不是按引用/名字：调用方可能用 {@code Level.WARNING} 常量，
     * 也可能经 {@code log(level, ...)} 传入；名字无关的判据同时保证不会误伤同名的文本 API。
     *
     * @param record 待判定记录；{@code null} 时按 {@link Filter} 约定交由下游处理
     */
    public static boolean isLoggable(LogRecord record) {
        if (record == null) {
            return true;
        }
        if (debugEnabled) {
            return true;
        }
        return record.getLevel().intValue() != Level.WARNING.intValue();
    }

    /** 写入 {@code debug.enabled} 快照。启动与每次 {@code /muz reload} 各写一次。 */
    public static void setDebugEnabled(boolean enabled) {
        debugEnabled = enabled;
    }

    /** 当前是否允许本插件输出 WARNING（{@code debug.enabled} 的快照）。 */
    public static boolean isDebugEnabled() {
        return debugEnabled;
    }

    /**
     * 把门禁装到指定的【本插件自有】logger 上（幂等）。
     *
     * <p>只在 {@code DoudizhuPlugin.installLogGate()} 里对本插件自己的 logger 调用；<b>不要</b>对
     * root / 服务端共享 logger 调用。
     *
     * <p>若目标 logger 已有第三方 filter，则链式组合而不是覆盖，避免悄悄关掉别人设好的丢弃逻辑；
     * 重复安装时识别已链过的情形，不叠加。
     *
     * @param logger 目标 logger；{@code null} 时忽略
     */
    public static void install(Logger logger) {
        if (logger == null) {
            return;
        }
        Filter existing = logger.getFilter();
        if (existing == GATE) {
            return;
        }
        if (existing == null) {
            logger.setFilter(GATE);
            return;
        }
        // 已有 filter：链式保留双方语义；GATE 在右，故本门禁对 WARNING 的裁决仍生效。
        logger.setFilter(record -> existing.isLoggable(record) && isLoggable(record));
    }

    /**
     * 受门禁的 WARNING 出口，供【拿不到本插件自有 logger】的静态出口使用（例如静态核心探测器要往
     * {@code Bukkit.getLogger()} 写一行带 {@code [MUZ]} 前缀的文本）。
     *
     * <p>这是显式门控：{@code debug} 关闭时直接返回，绝不触碰共享 logger，因此不会影响别的插件。
     *
     * @param logger  目标 logger（可为共享 logger，因为是否输出由本方法先行裁决）；{@code null} 时忽略
     * @param message 诊断文本
     */
    public static void warn(Logger logger, String message) {
        if (logger == null || !debugEnabled) {
            return;
        }
        logger.warning(message);
    }

    /**
     * 受门禁的带堆栈 WARNING 出口（见 {@link #warn(Logger, String)}）。
     *
     * @param logger  目标 logger；{@code null} 时忽略
     * @param message 诊断文本
     * @param thrown  异常本体，用于保留堆栈
     */
    public static void warn(Logger logger, String message, Throwable thrown) {
        if (logger == null || !debugEnabled) {
            return;
        }
        logger.log(Level.WARNING, message, thrown);
    }
}
