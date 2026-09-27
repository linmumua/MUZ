package linmumua.doudizhu.log;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.logging.Handler;
import java.util.logging.Level;
import java.util.logging.LogRecord;
import java.util.logging.Logger;
import linmumua.doudizhu.DoudizhuPlugin;
import linmumua.doudizhu.config.MuzYamlConfig;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import sun.misc.Unsafe;

/**
 * 门禁装配契约（行为级）：证明 {@code DoudizhuPlugin.installLogGate()} 确实
 * <ol>
 *   <li>把 {@code debug.enabled} 读进 {@link MuzLogGate}（{@code onEnable} 与 {@code /muz reload}
 *       都经此快照）；</li>
 *   <li>把门禁装到【本插件自己的】{@code getLogger()} 上，从而 {@code debug=false} 时该 logger 的
 *       WARNING 被静默；</li>
 *   <li><b>不</b>影响其它 logger（门外汉 logger 的 WARNING 照旧输出，证明没装到 root/共享 logger）。</li>
 * </ol>
 *
 * <p>{@code installLogGate} 与 {@code loadRenderSettings} 里的重读是同一条装配逻辑
 * （{@code MuzLogGate.setDebugEnabled(debugLoggingEnabled())}），故这里翻转配置再重装一次即等价于
 * {@code /muz reload} 改 {@code debug.enabled} 后生效；{@code loadRenderSettings} 侧的重读由
 * {@code MuzLogGateCoverageContractTest} 用源码断言钉住。
 */
class MuzLogGateWiringTest {

    @AfterEach
    void resetGate() {
        MuzLogGate.setDebugEnabled(true);
    }

    /** 同一插件：配置 false → 门禁关、logger WARNING 被静默；改 true 重装 → 恢复。 */
    @Test
    void installLogGate把配置读进门禁并装到插件logger上() throws Exception {
        CapturingLogs pluginLogs = new CapturingLogs();
        DoudizhuPlugin plugin = unsafePlugin(pluginLogs.logger());

        setDebugInConfig(plugin, false);
        invoke(plugin, "installLogGate");
        assertFalse(MuzLogGate.isDebugEnabled(), "debug.enabled=false 必须写进门禁快照");
        pluginLogs.logger().warning("关闭时插件 WARNING 必须被静默");
        assertEquals(0, pluginLogs.records().size(),
            "门禁必须装在插件 logger 上，使 debug=false 时 WARNING 静默：" + pluginLogs.messages());

        // 等价 /muz reload 改成 debug.enabled=true 后重装。
        setDebugInConfig(plugin, true);
        invoke(plugin, "installLogGate");
        assertTrue(MuzLogGate.isDebugEnabled(), "debug.enabled=true 必须写进门禁快照");
        pluginLogs.logger().warning("开启时插件 WARNING 必须恢复");
        assertEquals(1, pluginLogs.records().size(),
            "开启后插件 logger 的 WARNING 必须恢复：" + pluginLogs.messages());
    }

    /** 门禁只装在本插件 logger 上：另一个未被装配的 logger 在 debug=false 时 WARNING 依旧输出。 */
    @Test
    void 门禁不影响其它logger因此没有装到root() throws Exception {
        CapturingLogs pluginLogs = new CapturingLogs();
        CapturingLogs otherPluginsLog = new CapturingLogs();
        DoudizhuPlugin plugin = unsafePlugin(pluginLogs.logger());

        setDebugInConfig(plugin, false);
        invoke(plugin, "installLogGate");

        otherPluginsLog.logger().warning("别的插件的 WARNING 不该被吞");
        assertEquals(1, otherPluginsLog.records().size(),
            "门禁绝不能装到 root/共享 logger 上，否则会吞掉别的插件的日志：" + otherPluginsLog.messages());
    }

    // ---- 夹具 ----

    private static DoudizhuPlugin unsafePlugin(Logger logger) throws Exception {
        DoudizhuPlugin plugin = (DoudizhuPlugin) unsafe().allocateInstance(DoudizhuPlugin.class);
        setField(plugin, "logger", logger);
        return plugin;
    }

    private static void setDebugInConfig(DoudizhuPlugin plugin, boolean enabled) throws Exception {
        MuzYamlConfig config = MuzYamlConfig.empty(
            Path.of("build", "tmp", "muz-log-gate-wiring-test.yml"));
        config.set("debug.enabled", enabled);
        setField(plugin, "config", config);
    }

    private static void invoke(DoudizhuPlugin plugin, String methodName) throws Exception {
        Method method = DoudizhuPlugin.class.getDeclaredMethod(methodName);
        method.setAccessible(true);
        method.invoke(plugin);
    }

    private static Unsafe unsafe() throws Exception {
        Field field = Unsafe.class.getDeclaredField("theUnsafe");
        field.setAccessible(true);
        return (Unsafe) field.get(null);
    }

    private static void setField(Object target, String name, Object value) throws Exception {
        Class<?> current = target.getClass();
        while (current != null) {
            try {
                Field field = current.getDeclaredField(name);
                field.setAccessible(true);
                field.set(target, value);
                return;
            } catch (NoSuchFieldException ignored) {
                current = current.getSuperclass();
            }
        }
        throw new NoSuchFieldException(name);
    }

    /** 收集日志的真实 Logger（唯一名字 + 独立 handler，不向上传播）。 */
    private static final class CapturingLogs {
        private final List<LogRecord> records = new ArrayList<>();
        private final Logger logger;

        private CapturingLogs() {
            this.logger = Logger.getLogger("muz-log-gate-wiring-" + System.nanoTime());
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
