package linmumua.doudizhu.log;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import java.util.logging.Handler;
import java.util.logging.Level;
import java.util.logging.LogRecord;
import java.util.logging.Logger;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

/**
 * 统一日志门禁 {@link MuzLogGate} 的行为测试。
 *
 * <p>钉死的语义（linmumua 明确要求，覆盖了「故障类 WARNING 永远留痕」的历史约定）：
 * <ul>
 *   <li>{@code debug.enabled=false}（出厂默认）→ 本插件的<b>全部</b> WARNING 不输出；</li>
 *   <li>{@code SEVERE}（及以上/其它级别）<b>不受开关影响</b>，永远输出；</li>
 *   <li>开关可运行期翻转（{@code /muz reload} 语义）；</li>
 *   <li>门禁只按 {@link Level} 判定，与文本内容/名字无关，因此不会误伤
 *       {@code MuzTheme.warning(...)} 这类玩家文本 API；</li>
 *   <li>装到自有 logger 上即生效（含该 logger 自己的 handler），不影响别的 logger。</li>
 * </ul>
 */
class MuzLogGateTest {

    /** 每个用例后复位为 fail-open 初值，避免静态开关在用例间泄漏。 */
    @AfterEach
    void resetGate() {
        MuzLogGate.setDebugEnabled(true);
    }

    /**
     * 核心语义：{@code debug=false} 时 WARNING 被静默，但 SEVERE 照常输出。
     *
     * <p>守的风险有两个方向：写成「不看级别一律吞」会把 SEVERE 也吞掉（排查彻底失明）；
     * 写成「按文本/名字挑」会漏掉直接 Logger 路径。这条用同一个 logger 依次打 WARNING 与 SEVERE 把两者钉死。
     */
    @Test
    void debug关闭时WARNING静默而SEVERE不受影响() {
        CapturingLogs logs = new CapturingLogs();
        MuzLogGate.install(logs.logger());

        MuzLogGate.setDebugEnabled(false);
        logs.logger().warning("普通 WARNING 必须被静默");
        logs.logger().log(Level.SEVERE, "SEVERE 必须照常输出");
        logs.logger().info("INFO 不被本开关门控");

        assertEquals(2, logs.records().size(),
            "debug=false 只该静默 WARNING，SEVERE/INFO 必须照常：" + logs.messages());
        assertTrue(logs.messages().contains("SEVERE 必须照常输出"),
            "SEVERE 是「不能无声」的底线，绝不能被 debug 开关吞掉：" + logs.messages());
        assertFalse(logs.messages().contains("普通 WARNING 必须被静默"),
            "WARNING 必须在 debug=false 时被静默：" + logs.messages());
    }

    /** 开关打开后 WARNING 恢复输出（{@code /muz reload} 把 debug.enabled 改成 true 的等价语义）。 */
    @Test
    void debug开启时WARNING恢复输出() {
        CapturingLogs logs = new CapturingLogs();
        MuzLogGate.install(logs.logger());

        MuzLogGate.setDebugEnabled(false);
        logs.logger().warning("关闭时静默");
        assertEquals(0, logs.records().size(), "关闭时应静默：" + logs.messages());

        // 模拟 /muz reload 重新读入 debug.enabled=true。
        MuzLogGate.setDebugEnabled(true);
        logs.logger().warning("开启时恢复");
        assertEquals(1, logs.records().size(), "开启后 WARNING 必须恢复：" + logs.messages());
        assertEquals(Level.WARNING, logs.records().get(0).getLevel());
        assertTrue(logs.messages().get(0).contains("开启时恢复"));
    }

    /**
     * 门禁只按级别判定、与文本内容无关：一条文本恰好等于 {@code MuzTheme.warning} 风格的 WARNING
     * 在 debug 关闭时同样被静默。
     *
     * <p>守的风险：把门禁实现成「按名字/按文本关键字挑」会误伤玩家文本、又漏掉任意真正日志；
     * 这条从「内容不影响裁决」一侧证明判据与文本无关。
     */
    @Test
    void 门禁只按级别判定与文本内容无关() {
        CapturingLogs logs = new CapturingLogs();
        MuzLogGate.install(logs.logger());
        MuzLogGate.setDebugEnabled(false);

        // 文本与玩家可见的主题文本同形，但它是经 logger 发出的 WARNING，仍应被静默。
        logs.logger().warning("MUZ 拆桌棍");
        logs.logger().warning("空位");

        assertEquals(0, logs.records().size(),
            "按级别判定的门禁必须与文本无关，同形文本的 WARNING 也静默：" + logs.messages());
    }

    /** {@code install} 幂等：重复安装不叠加、不明显改变行为；已有第三方 filter 时链式保留其语义。 */
    @Test
    void install幂等且保留既有filter() {
        CapturingLogs logs = new CapturingLogs();
        MuzLogGate.install(logs.logger());
        MuzLogGate.install(logs.logger());
        MuzLogGate.install(logs.logger());

        MuzLogGate.setDebugEnabled(true);
        logs.logger().warning("三次安装后仍应且仅应输出一条");
        assertEquals(1, logs.records().size(), "install 必须幂等：" + logs.messages());

        // 已有一条「丢弃 DEBUG 记录」的第三方 filter：链式保留，不得被我们的门禁覆盖掉。
        CapturingLogs chained = new CapturingLogs();
        chained.logger().setFilter(record -> record.getLevel().intValue() > Level.FINE.intValue());
        MuzLogGate.install(chained.logger());

        MuzLogGate.setDebugEnabled(true);
        chained.logger().log(Level.FINE, "既有 filter 已丢弃的 FINE");
        chained.logger().warning("既有 filter 放行的 WARNING");
        assertEquals(1, chained.records().size(),
            "既有第三方 filter 的丢弃语义必须保留（链式而不是覆盖）：" + chained.messages());
        assertTrue(chained.messages().get(0).contains("既有 filter 放行的 WARNING"));
    }

    /** null 入参安全：门禁是横切关注点，不能因为 logger 或 record 为 null 就把调用方搞崩。 */
    @Test
    void null入参安全() {
        MuzLogGate.setDebugEnabled(false);
        MuzLogGate.install(null);                 // 不抛
        MuzLogGate.warn(null, "空 logger");        // 不抛、不输出

        // Filter 语义：null record 交给下游处理（返回 true），不能抛 NPE。
        assertTrue(MuzLogGate.isLoggable(null), "null record 必须交回下游，不能抛 NPE");
    }

    /** {@code warn(Logger,...)} 是给拿不到自有 logger 的静态出口用的显式门控：关闭时不触发任何输出。 */
    @Test
    void warn出口在关闭时完全不触发logger() {
        CapturingLogs logs = new CapturingLogs();
        MuzLogGate.setDebugEnabled(false);
        MuzLogGate.warn(logs.logger(), "关闭时不该输出");
        MuzLogGate.warn(logs.logger(), "关闭时不该输出堆栈", new IllegalStateException("x"));
        assertEquals(0, logs.records().size(), "关闭时 warn 出口必须零输出：" + logs.messages());

        MuzLogGate.setDebugEnabled(true);
        MuzLogGate.warn(logs.logger(), "开启时输出");
        MuzLogGate.warn(logs.logger(), "开启时输出堆栈", new IllegalStateException("boom"));
        assertEquals(2, logs.records().size(), "开启时 warn 出口必须输出：" + logs.messages());
        assertTrue(logs.records().get(1).getThrown() instanceof IllegalStateException,
            "带堆栈重载必须保留异常本体");
    }

    /** {@code isLoggable} 的级别判据：只对 WARNING 的整型值生效。 */
    @Test
    void isLoggable按WARNING整型值判定() {
        MuzLogGate.setDebugEnabled(false);
        assertFalse(MuzLogGate.isLoggable(new LogRecord(Level.WARNING, "w")), "WARNING 必须被丢弃");
        assertTrue(MuzLogGate.isLoggable(new LogRecord(Level.SEVERE, "s")), "SEVERE 必须放行");
        assertTrue(MuzLogGate.isLoggable(new LogRecord(Level.INFO, "i")), "INFO 不在本开关范围");
        assertTrue(MuzLogGate.isLoggable(new LogRecord(Level.OFF, "o")), "OFF 不在本开关范围");

        MuzLogGate.setDebugEnabled(true);
        assertTrue(MuzLogGate.isLoggable(new LogRecord(Level.WARNING, "w")), "开启后 WARNING 必须放行");
    }

    /** 收集日志的真实 Logger：本测试据此断言门禁是否真的拦下了记录。 */
    private static final class CapturingLogs {
        private final List<LogRecord> records = new ArrayList<>();
        private final Logger logger;

        private CapturingLogs() {
            // 唯一名字避免与其它测试共享 JUL logger，也不向上传播污染控制台。
            this.logger = Logger.getLogger("muz-log-gate-test-" + System.nanoTime());
            this.logger.setUseParentHandlers(false);
            this.logger.setLevel(Level.ALL);
            this.logger.addHandler(new Handler() {
                @Override
                public void publish(LogRecord record) {
                    records.add(record);
                }

                @Override
                public void flush() {
                }

                @Override
                public void close() {
                }
            });
        }

        private Logger logger() {
            return logger;
        }

        private List<LogRecord> records() {
            return records;
        }

        private List<String> messages() {
            List<String> snapshot = new ArrayList<>();
            for (LogRecord record : records) {
                snapshot.add(String.valueOf(record.getMessage()));
            }
            return snapshot;
        }
    }
}
