package linmumua.doudizhu.compat;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.lang.reflect.Field;
import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.logging.Handler;
import java.util.logging.Level;
import java.util.logging.LogRecord;
import java.util.logging.Logger;
import linmumua.doudizhu.DoudizhuPlugin;
import org.bukkit.Bukkit;
import org.bukkit.Server;
import org.bukkit.plugin.Plugin;
import org.bukkit.plugin.PluginManager;
import org.junit.jupiter.api.Test;
import sun.misc.Unsafe;

/**
 * 「CraftEngine 换了内部结构」这条降级路径必须留下可排查的签名清单。
 *
 * <p>【真实缺陷】{@code getMethodByTypes} 找不到方法时【抛 NoSuchMethodException】，不是返回 null
 * （common-reflex 6.3.0 实测）。原实现没有就地捕获，异常冒泡到 {@code resolveFontManager} 尾部那唯一的
 * {@code catch (Exception)}，于是告警退化成一句通用的「offset service unavailable: null」
 * ——而 NoSuchMethodException 的 message 本身就可以是 null。结果与
 * {@code OffsetServiceRetryTest} 记录的现场完全一致：CE 换了形状，玩家端只看到 HUD 整条消失，
 * 服务端日志里既没有缺哪个方法，也没有 CE 实际提供了什么。
 *
 * <p>【为什么是行为测试而不是源码扫描】TabooLib 的 reflex 工具是纯 JVM 反射实现，
 * 不需要服务端就能真实驱动。这里把 {@code Bukkit.getPluginManager()} 换成一个返回替身插件的
 * 桩（照 {@code GameTableOpeningFlowTest} 的既有 {@code Bukkit.server} 代理写法），
 * 于是生产代码的 {@code resolveFontManager} 会完整跑一遍——找到「CE 插件」、分析替身
 * FontManager 类、走到「取不到方法」分支并真实写下日志。断言的是真实产生的日志文本，
 * 比断言源码里存在某个字符串强得多：后者在实现被改成「列出签名但完全不可读」时依然会通过。
 */
class CraftEngineOffsetDiagnosticsTest {
    private static final String OFFSETS = "createMiniMessageOffsets";

    /** 形如 26.x 改了宽度：方法存在，但签名是 {@code (long)} 而不是 {@code (int)}。 */
    public static final class WrongSignatureManager {
        public String createMiniMessageOffsets(long pixels) {
            return String.valueOf(pixels);
        }
    }

    /** CE 把方法改名后的形状：只有 {@code miniMessageOffsets(int)}。 */
    public static final class RenamedManager {
        public String miniMessageOffsets(int pixels) {
            return String.valueOf(pixels);
        }
    }

    /** 完全没有相关方法的形状（FontManager 若被整体重构就会长这样）。 */
    public static final class EmptyManager {
    }

    /**
     * 签名不匹配时必须点名缺的是哪个方法、并在告警里列出 CE 实际提供的同名签名。
     *
     * <p>失败条件（旧实现）：日志只有「CraftEngine offset service unavailable: null」，
     * 缺方法名、缺签名清单、缺 CE 实际形状的任何线索。
     */
    @Test
    void 签名不匹配时告警列出实际发现的同名签名() throws Exception {
        Logs collected = run(WrongSignatureManager.class);

        String warning = collected.onlyWarning();
        assertNotNull(warning, "取不到偏移方法必须留下告警，实际日志：" + collected.messages);
        assertTrue(warning.contains(OFFSETS + "(int)"),
            "告警必须点名我们期望的方法与参数类型，实际：" + warning);
        assertTrue(warning.contains("实际发现"), "告警必须包含签名清单段，实际：" + warning);
        assertTrue(warning.contains(OFFSETS + "(long)"),
            "告警必须列出 CE 实际提供的同名签名 (long)，否则维护者无从看出形状差异，实际：" + warning);
        assertFalse(warning.endsWith("null"),
            "不得再出现 message 为 null 拼出的空原因，实际：" + warning);
    }

    /**
     * CE 把方法改名时必须把整个方法表写进告警。
     *
     * <p>只报「无同名方法」在这条分支上是死路：维护者知道没有 {@code createMiniMessageOffsets}，
     * 但不知道它变成了什么。这里断言改名后的真实签名出现在日志里。
     */
    @Test
    void 方法被改名时告警列出实际方法表() throws Exception {
        Logs collected = run(RenamedManager.class);

        String warning = collected.onlyWarning();
        assertNotNull(warning, "取不到偏移方法必须留下告警，实际日志：" + collected.messages);
        assertTrue(warning.contains("FontManager 实际声明的方法"),
            "告警必须带出实际方法表段，实际：" + warning);
        assertTrue(warning.contains("miniMessageOffsets(int)"),
            "改名后的方法必须出现在告警里，否则根本查不出新名字，实际：" + warning);
        assertTrue(warning.contains("无"),
            "没有同名方法时清单段必须写明『无』，而不是留空，实际：" + warning);
    }

    /**
     * 方法表为空时清单段也要可读，不能退化成空串。
     *
     * <p>失败条件：两个清单段在没有内容时返回 ""，日志变成「实际发现：；实际声明的方法：」
     * ——看上去像被截断，维护者会怀疑是日志丢失而不是 CE 真的什么都没提供。
     */
    @Test
    void 空方法表也写明无而不是留空() throws Exception {
        Logs collected = run(EmptyManager.class);

        String warning = collected.onlyWarning();
        assertNotNull(warning, "取不到偏移方法必须留下告警，实际日志：" + collected.messages);
        assertEquals(2, countOccurrences(warning, "无"),
            "同名清单与全量清单两处都要写明『无』，实际：" + warning);
        assertFalse(warning.contains("实际发现：；") || warning.contains("方法：；"),
            "不得留空导致日志看上去像被截断，实际：" + warning);
    }

    /**
     * 诊断不得改变降级语义：偏移仍返回空串、{@code isAvailable} 仍为 false，
     * 并且仍然只告警一次（渲染路径每帧调用，多告警就是刷屏）。
     *
     * <p>失败条件：为了让日志更详细而顺手把 {@code warned} 去重去掉，
     * 或把「取不到」从降级改成上抛——两者都会破坏 {@code OffsetServiceRetryTest} 锁定的既有语义。
     */
    @Test
    void 诊断不改变降级语义且仍然只警告一次() throws Exception {
        Logs collected = new Logs();
        withCraftEngine(WrongSignatureManager.class, collected, service -> {
            assertFalse(service.isAvailable(), "签名不匹配时 isAvailable 必须为 false");
            assertEquals("", service.offset(16), "签名不匹配时偏移必须退化为空串");
            service.offset(16);
            service.offset(-8);
            assertFalse(service.isAvailable(), "多次调用后 isAvailable 仍必须为 false");
        });

        assertEquals(1, collected.warnings().size(),
            "告警必须仍然只发一次，否则渲染路径每帧一条、直接刷屏。实际：" + collected.messages);
    }

    /** 装好桩并驱动一次解析（pixels != 0 才会进 resolveFontManager）。 */
    private static Logs run(Class<?> managerClass) throws Exception {
        Logs collected = new Logs();
        withCraftEngine(managerClass, collected, service -> service.offset(16));
        return collected;
    }

    /**
     * 在「CE 替身已挂到 Bukkit」的窗口内构造并驱动服务，退出时恢复 {@code Bukkit.server}。
     *
     * <p>窗口必须覆盖【构造 + 调用】：构造本身不解析，{@code offset(16)} 才进
     * {@code resolveFontManager} 并查 {@code Bukkit.getPluginManager()}。
     *
     * <p>恢复原值而不是置 null：同 JVM 里其它测试可能已经装了自己的桩。
     */
    private static void withCraftEngine(Class<?> managerClass, Logs collected,
                                        java.util.function.Consumer<CraftEngineOffsetService> action) throws Exception {
        // 【为什么反射写 Bukkit.server 而不是用公开的 Bukkit.setServer】
        // setServer 内部会调 getVersionMessage() → ServerBuildInfo.buildInfo()，
        // 而裸测试 JVM 里没有 ServerBuildInfo 的 provider，会抛
        // NoSuchElementException: No value present（实测）。直接写静态字段绕开那段副作用，
        // 这也是仓库既有夹具（GameTableOpeningFlowTest）的做法。
        Field serverField = Bukkit.class.getDeclaredField("server");
        serverField.setAccessible(true);
        Server previous = (Server) serverField.get(null);
        serverField.set(null, serverStub(pluginStub()));
        try {
            DoudizhuPlugin plugin = (DoudizhuPlugin) unsafe().allocateInstance(DoudizhuPlugin.class);
            setField(plugin, "logger", collected.logger());
            // 替身 FontManager 的类型在 Class.forName 那一步拿不到（Proxy 的 getClass 不被拦截，
            // 见 pluginStub 的说明），所以覆写那个唯一的取类入口，直接给出已解析的 Class。
            // 这绕开的是「CE 在另一个类加载器」这一步，诊断逻辑本身仍走生产代码。
            CraftEngineOffsetService service = new CraftEngineOffsetService(plugin) {
                @Override
                Class<?> craftEngineClass(ClassLoader loader) {
                    return StubCraftEngine.class;
                }

                @Override
                Class<?> fontManagerClass(ClassLoader loader) {
                    return managerClass;
                }
            };
            action.accept(service);
        } finally {
            // 恢复原值而不是置 null：同 JVM 里其它测试可能已经装了自己的桩。
            serverField.set(null, previous);
        }
    }

    /**
     * CE 插件的替身。
     *
     * <p>【为什么是 Proxy 而不是手写实现】{@code Plugin} 接口很大（还继承 TabExecutor /
     * LifecycleEventOwner / Namespaced），手写会写出一大堆只为满足签名的空方法；
     * 仓库既有夹具（{@code GameTableOpeningFlowTest}）统一用 Proxy + 默认值兜底，
     * 这里沿用同一做法。
     *
     * <p>注意一个真实限制：{@code getClass()} 在 Proxy 上【不会路由到调用处理器】（实测），
     * 所以替身【无法】提供自定义类加载器。这正是 withCraftEngine 要额外注入
     * FontManager Class 的原因。
     */
    private static Plugin pluginStub() {
        return (Plugin) Proxy.newProxyInstance(
            Plugin.class.getClassLoader(),
            new Class<?>[] {Plugin.class},
            (proxy, method, args) -> switch (method.getName()) {
                case "isEnabled" -> true;
                case "getName" -> "CraftEngine";
                case "getLogger" -> Logger.getLogger("muz-ce-offset-stub-plugin");
                case "toString" -> "StubCraftEnginePlugin";
                default -> defaultValue(method.getReturnType());
            });
    }

    /**
     * 服务器桩：只满足 {@code getPluginManager().getPlugin("CraftEngine")} 这一次查询。
     *
     * <p>用 Proxy 是安全的：生产代码从 Server 上只调 {@code getPluginManager()}。
     */
    private static Server serverStub(Plugin craftEngine) {
        PluginManager manager = (PluginManager) Proxy.newProxyInstance(
            PluginManager.class.getClassLoader(),
            new Class<?>[] {PluginManager.class},
            (proxy, method, args) -> switch (method.getName()) {
                case "getPlugin" ->
                    args != null && args.length == 1 && "CraftEngine".equals(args[0]) ? craftEngine : null;
                default -> defaultValue(method.getReturnType());
            });
        return (Server) Proxy.newProxyInstance(
            Server.class.getClassLoader(),
            new Class<?>[] {Server.class},
            (proxy, method, args) -> {
                if (method.getName().equals("getPluginManager")) {
                    return manager;
                }
                return defaultValue(method.getReturnType());
            });
    }

    /**
     * 替身 CraftEngine 主类：{@code instance()} 与 {@code fontManager()} 都是无参访问器，
     * 与生产代码 {@code firstMethod} 的查找形状一致。
     */
    public static final class StubCraftEngine {
        /** 非 null 即可：生产代码只做 null 检查，随后按它的运行时类取 FontManager 类。 */
        private static final Object FONT_MANAGER = new Object();

        public static StubCraftEngine instance() {
            return new StubCraftEngine();
        }

        public Object fontManager() {
            return FONT_MANAGER;
        }
    }

    private static Object defaultValue(Class<?> type) {
        if (!type.isPrimitive()) {
            return null;
        }
        if (type == boolean.class) {
            return false;
        }
        if (type == int.class) {
            return 0;
        }
        if (type == long.class) {
            return 0L;
        }
        if (type == float.class) {
            return 0.0f;
        }
        if (type == double.class) {
            return 0.0d;
        }
        if (type == short.class) {
            return (short) 0;
        }
        if (type == byte.class) {
            return (byte) 0;
        }
        if (type == char.class) {
            return '\0';
        }
        return null;
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

    private static int countOccurrences(String text, String needle) {
        int total = 0;
        int at = text.indexOf(needle);
        while (at >= 0) {
            total++;
            at = text.indexOf(needle, at + needle.length());
        }
        return total;
    }

    /** 记录 (级别, 文本) 的日志收集器，照 CraftEngineUnsupportedApiTest 的既有夹具。 */
    private static final class Logs {
        private final List<String> messages = new ArrayList<>();
        private final List<Level> levels = new ArrayList<>();
        private final Logger logger = Logger.getLogger("muz-ce-offset-diagnostics-" + UUID.randomUUID());

        private Logs() {
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

        private List<String> warnings() {
            List<String> found = new ArrayList<>();
            for (int index = 0; index < messages.size(); index++) {
                if (Level.WARNING.equals(levels.get(index))) {
                    found.add(messages.get(index));
                }
            }
            return found;
        }

        /** 唯一那条 WARNING；没有或多于一条时返回 null，由调用方给出可读的失败信息。 */
        private String onlyWarning() {
            List<String> found = warnings();
            return found.size() == 1 ? found.get(0) : null;
        }
    }
}
