package linmumua.doudizhu.compat;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.logging.Handler;
import java.util.logging.Level;
import java.util.logging.LogRecord;
import java.util.logging.Logger;
import linmumua.doudizhu.DoudizhuPlugin;
import org.junit.jupiter.api.Test;
import sun.misc.Unsafe;

/**
 * CraftEngine「API 形状不受支持」时的失败语义与告警限频。
 *
 * <p>背景（真实现场）：MUZ 按 CraftEngine 0.0.67 编译，现场跑 26.x。26.8.x 与 0.0.67 的
 * {@code PackManager} 都有 {@code generateResourcePack()} / {@code resourcePackPath()}，
 * 但 26.9.1 上这两个方法与 {@code Config.fileToUpload()} 【都不存在】（javap 证据见
 * {@code build/tmp-ce-api}）。旧实现在这种情况下返回 null，上层只会报一句「路径为空」，
 * 无法区分「CE 版本形状不同」与「配置写错」——排查时完全没有线索。
 *
 * <p>本测试钉住三条语义：
 * <ol>
 *   <li>缺失时抛【带版本信息与缺失方法名】的明确错误，而不是返回 null；</li>
 *   <li>错误信息必须写明「当前只支持 0.0.67 / 26.8.x 形状、26.9.1 需另行适配」，
 *       这样维护者不会去怀疑配置；</li>
 *   <li>同一条「不支持的 API」告警在【进程内】只 warning 一次，之后同类原因降为 fine，
 *       避免启动期恢复与每次 Web 保存各刷一条。</li>
 * </ol>
 *
 * <p>夹具风格沿用 {@code CraftEngineFurnitureRegionGateTest}：CraftEngine 是 compileOnly，
 * 单测类路径里没有它，所以用纯 Java 替身驱动，并用 Unsafe 分配插件实例只为拿到一个可收集的
 * Logger（告警限频是实例方法、却依赖进程级静态去重，必须真实调用生产代码才测得到）。
 */
class CraftEngineUnsupportedApiTest {

    /** 26.9.1 形状：有 PackManager，但两个资源包方法都不在。 */
    public static final class ModernPackManager {
    }

    public static final class ModernEngine {
        private final ModernPackManager manager = new ModernPackManager();

        public boolean isReloading() {
            return false;
        }

        public ModernPackManager packManager() {
            return manager;
        }
    }

    /** 连 PackManager 入口都没有的引擎。 */
    public static final class NoPackManagerEngine {
        public boolean isReloading() {
            return false;
        }
    }

    /**
     * 26.9.1 形状下读生成路径必须抛异常，且信息里能看出「版本 + 缺哪个方法 + 需另行适配」。
     *
     * <p>失败条件（旧行为）：返回 null。那样上层只会报「路径为空」，
     * 维护者无法判断是 CE 版本问题还是配置问题。
     */
    @Test
    void missingResourcePackPathFailsLoudlyWithVersionAndMethodNames() {
        CraftEngineHudResourceBridge.EngineAccess access =
            CraftEngineHudResourceBridge.DirectEngineAccess.of(new ModernEngine());

        UnsupportedOperationException thrown = assertThrows(UnsupportedOperationException.class,
            access::generatedPackPath, "26.9.1 形状下读路径必须明确失败，不能返回 null");

        String message = thrown.getMessage();
        assertTrue(message.contains("resourcePackPath()"),
            "错误必须点名缺失的方法，实际：" + message);
        assertTrue(message.contains("generateResourcePack()"),
            "同一次构造里两个方法都缺失，错误应一并列出，实际：" + message);
        assertTrue(message.contains("26.9.1"),
            "错误必须写明 26.9.1 需另行适配，实际：" + message);
        assertTrue(message.contains("0.0.67") || message.contains("26.8.x"),
            "错误必须写明当前支持哪一代 PackManager 形状，实际：" + message);
    }

    /** 生成资源包同样必须明确失败，不能因为反射查不到方法就静默跳过。 */
    @Test
    void missingGenerateResourcePackFailsLoudlyWithVersion() {
        CraftEngineHudResourceBridge.EngineAccess access =
            CraftEngineHudResourceBridge.DirectEngineAccess.of(new ModernEngine());

        UnsupportedOperationException thrown = assertThrows(UnsupportedOperationException.class,
            access::generateResourcePack,
            "生成资源包时缺少 API 必须抛异常；静默跳过会让上层拿旧 ZIP 当成功");

        assertTrue(thrown.getMessage().contains("generateResourcePack()"),
            "错误必须点名缺失的方法，实际：" + thrown.getMessage());
        assertTrue(thrown.getMessage().contains("26.9.1"),
            "错误必须写明 26.9.1 需另行适配，实际：" + thrown.getMessage());
    }

    /** 连 packManager() 都没有时，错误要指向那一个缺失入口。 */
    @Test
    void missingPackManagerEntryPointIsReportedByName() {
        CraftEngineHudResourceBridge.EngineAccess access =
            CraftEngineHudResourceBridge.DirectEngineAccess.of(new NoPackManagerEngine());

        UnsupportedOperationException thrown = assertThrows(UnsupportedOperationException.class,
            access::generatedPackPath, "没有 PackManager 时必须明确失败");
        assertTrue(thrown.getMessage().contains("packManager()"),
            "错误必须点名缺失的入口方法，实际：" + thrown.getMessage());
    }

    /**
     * {@code Config.fileToUpload()} 缺失时必须抛带版本信息的错误。
     *
     * <p>本测试类路径里没有 CraftEngine（compileOnly），所以 {@code Class.forName} 必然失败，
     * 正好等价于「现场那个类/方法不可用」的分支。
     */
    @Test
    void missingUploadPathApiFailsLoudlyInsteadOfReturningNull() {
        CraftEngineHudResourceBridge.EngineAccess access =
            CraftEngineHudResourceBridge.DirectEngineAccess.of(new ModernEngine());

        UnsupportedOperationException thrown = assertThrows(UnsupportedOperationException.class,
            access::uploadPackPath, "读不到上传路径时必须明确失败，不能静默返回 null");

        String message = thrown.getMessage();
        assertTrue(message.contains("fileToUpload()"),
            "错误必须点名缺失的方法，实际：" + message);
        assertTrue(message.contains("版本"), "错误必须带上版本信息，实际：" + message);
        assertTrue(thrown.getCause() != null,
            "必须保留底层原因（ClassNotFound 等），否则排查时看不出真实失败点");
    }

    /**
     * 同一条原因的「不支持」告警进程内只 warning 一次，之后降为 fine。
     *
     * <p>失败条件（旧行为没有限频）：启动期恢复 + 每次 Debug Web 保存各刷一条 warning，
     * 玩家看到满屏告警却不知道只有第一条有价值。
     *
     * <p>【为什么用唯一原因字符串】去重集合是进程级静态的，用一个带随机 UUID 的原因可以避免
     * 与其它测试互相干扰。
     */
    @Test
    void unsupportedApiWarningIsThrottledPerReasonAcrossInstances() throws Exception {
        CollectingLogs logs = new CollectingLogs();
        DoudizhuPlugin plugin = fakePlugin(logs);
        String reason = "不支持的 CraftEngine API 测试原因 " + UUID.randomUUID();

        // 两个不同的 Bridge 实例模拟「每次 HUD 资源恢复都会重建桥接」的真实生命周期。
        CraftEngineHudResourceBridge first = new CraftEngineHudResourceBridge(plugin);
        CraftEngineHudResourceBridge second = new CraftEngineHudResourceBridge(plugin);
        Method warnOnce =
            CraftEngineHudResourceBridge.class.getDeclaredMethod("warnUnsupportedApiOnce", String.class);
        warnOnce.setAccessible(true);

        warnOnce.invoke(first, reason);
        warnOnce.invoke(second, reason);
        warnOnce.invoke(second, reason);

        assertEquals(1, logs.countAt(Level.WARNING, reason),
            "同一条原因第一次必须 warning，之后不得再刷 warning");
        assertEquals(2, logs.countAt(Level.FINE, reason),
            "被限频的重复告警必须降为 fine（保留线索但不刷屏）");
    }

    /** 空白原因不产生任何日志，避免把 null 当成一条「原因」写进去重集合。 */
    @Test
    void blankUnsupportedApiReasonIsIgnored() throws Exception {
        CollectingLogs logs = new CollectingLogs();
        CraftEngineHudResourceBridge bridge = new CraftEngineHudResourceBridge(fakePlugin(logs));
        Method warnOnce =
            CraftEngineHudResourceBridge.class.getDeclaredMethod("warnUnsupportedApiOnce", String.class);
        warnOnce.setAccessible(true);

        warnOnce.invoke(bridge, new Object[] { null });
        warnOnce.invoke(bridge, "   ");

        assertEquals(0, logs.messages.size(), "空白原因不该产生任何日志，实际：" + logs.messages);
    }

    /** 用 Unsafe 分配插件实例，只为了给它换上一个可收集的 Logger。 */
    private static DoudizhuPlugin fakePlugin(CollectingLogs logs) throws Exception {
        DoudizhuPlugin plugin = (DoudizhuPlugin) unsafe().allocateInstance(DoudizhuPlugin.class);
        setField(plugin, "logger", logs.logger());
        return plugin;
    }

    private static Unsafe unsafe() throws Exception {
        Field field = Unsafe.class.getDeclaredField("theUnsafe");
        field.setAccessible(true);
        return (Unsafe) field.get(null);
    }

    private static void setField(Object target, String name, Object value) throws Exception {
        Class<?> type = target.getClass();
        while (type != null) {
            try {
                Field field = type.getDeclaredField(name);
                field.setAccessible(true);
                field.set(target, value);
                return;
            } catch (NoSuchFieldException ignored) {
                type = type.getSuperclass();
            }
        }
        throw new NoSuchFieldException(name);
    }

    /** 记录 (级别, 文本) 的日志收集器。 */
    private static final class CollectingLogs {
        private final List<String> messages = new ArrayList<>();
        private final List<Level> levels = new ArrayList<>();
        private final Logger logger = Logger.getLogger("muz-ce-unsupported-api-test-" + UUID.randomUUID());

        private CollectingLogs() {
            logger.setUseParentHandlers(false);
            logger.setLevel(Level.ALL);
            logger.addHandler(new Handler() {
                @Override
                public void publish(LogRecord record) {
                    if (record.getMessage() != null) {
                        messages.add(record.getMessage());
                        levels.add(record.getLevel());
                    }
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

        private int countAt(Level level, String fragment) {
            int total = 0;
            for (int index = 0; index < messages.size(); index++) {
                if (levels.get(index).equals(level) && messages.get(index).contains(fragment)) {
                    total++;
                }
            }
            return total;
        }
    }
}
