package linmumua.doudizhu.compat;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.lang.reflect.Field;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.logging.Handler;
import java.util.logging.Level;
import java.util.logging.LogRecord;
import java.util.logging.Logger;
import linmumua.doudizhu.DoudizhuPlugin;
import org.bukkit.Server;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.plugin.PluginManager;
import org.junit.jupiter.api.Test;
import sun.misc.Unsafe;

/**
 * CraftEngine 家具桥在跨 region 时必须【在读实体之前】放弃。
 *
 * <p>意图（对应实服 Lophine 26.2 的真实日志）：
 * <pre>
 * CraftEngineFurnitureService.setFurnitureHitboxesVisible(181)
 *   → resolveLoadedFurniture(205) → CraftEngineFurniture.getLoadedFurnitureByMetaEntity
 *   → CraftEntity.getEntityId
 *   → java.lang.IllegalStateException: Accessing entity state off owning region's thread
 * 随后 [MUZ] CraftEngine furniture hitbox visibility bridge failed: null
 * </pre>
 * 调用来自 {@code PhysicalTableManager.restoreOccupiedChairHitboxVisibility} 的 lambda，经
 * {@code PlayerOutputDispatcher.runPlayer} 跑在 **viewer 的 player lane**，而 Interaction 实体属于
 * **牌桌所在 region**。那条 lane 不拥有该实体，于是 CE 在 {@code CraftEntity#getEntityId} 抛异常；
 * 异常被包装成 {@code InvocationTargetException}（message 为 null），日志只剩 “bridge failed: null”。
 *
 * <p>本测试用注入的 {@link CraftEngineFurnitureService.RegionGate} 替身 + 记录型 fake {@link Entity}
 * 直接驱动**生产公共方法**，钉三条语义：
 * <ol>
 *   <li>跨 region（gate 返回 false）时，两个碰实体的公共入口都必须直接返回 false，
 *       <b>且一次都不读实体状态</b>——这是「必须在读实体之前放弃」的可观察形式；</li>
 *   <li>该放弃不能是静默的：必须留下一条可排查的日志（按实体 UUID 限频）；</li>
 *   <li>{@code describeFailure} 必须在异常 message 为 null 时给出异常类型，
 *       避免日志再出现 “failed: null”（实服那条告警完全无法排查）。</li>
 * </ol>
 *
 * <p>为什么可以这么测：{@code Bukkit.isOwnedByCurrentRegion} 是静态方法，单测无法伪造，所以本类把
 * 归属判定抽成包内可见的注入点（沿用 {@code TableGadgetEffectService.EffectRuntime} 的既有做法）；
 * 生产实现仍直接调用 Bukkit。夹具风格沿用 {@code TableGadgetEffectReleaseBehaviorTest}。
 */
class CraftEngineFurnitureRegionGateTest {
    private static final Path SERVICE = Path.of(
        "src/main/java/linmumua/doudizhu/compat/CraftEngineFurnitureService.java");

    /**
     * 跨 region 时 {@code setFurnitureHitboxesVisible} 必须放弃，且绝不读实体状态。
     *
     * <p>可观察量取「实体上任何方法被调用过没有」：CE 的 {@code getLoadedFurnitureByMetaEntity}
     * 必然要碰实体（真实实现走 {@code CraftEntity#getEntityId}）。只要门禁排在前面，实体就一次都不会被碰。
     */
    @Test
    void crossRegionNeverReadsEntityStateForHitboxVisibility() throws Exception {
        RecordingEntity entity = new RecordingEntity();
        Fixture fixture = new Fixture(new StubGate(false));

        boolean result = fixture.service.setFurnitureHitboxesVisible(
            entity.proxy(), fixture.viewer, true);

        assertFalse(result, "跨 region 时不得报告成功");
        assertEquals(0, entity.entityCalls.get(),
            "跨 region 时必须在读实体状态【之前】放弃：实体上一个方法都不该被调用"
                + "（真实实现在这里抛 Accessing entity state off owning region's thread）");
        assertEquals(0, fixture.serverLookups.get(),
            "归属判定必须早于 CE 桥探测：跨 region 时连 getServer() 都不该发生"
                + "（这条断言不依赖测试环境是否装了 CE，因此不会变成空断言）");
        assertTrue(fixture.logs.contains("跳过跨 region 的 CraftEngine 家具操作"),
            "按设计放弃但不能无声，必须留下可排查的日志");
    }

    /** 跨 region 时 {@code removeFurniture} 同样必须放弃，且不碰实体。 */
    @Test
    void crossRegionNeverReadsEntityStateForRemoval() throws Exception {
        RecordingEntity entity = new RecordingEntity();
        Fixture fixture = new Fixture(new StubGate(false));

        boolean result = fixture.service.removeFurniture(entity.proxy());

        assertFalse(result, "跨 region 时不得报告清理成功");
        assertEquals(0, entity.entityCalls.get(),
            "跨 region 的家具清理必须在碰实体之前放弃，交给重试/ChunkLoad 修复路径收口");
        assertTrue(fixture.logs.contains("跳过跨 region 的 CraftEngine 家具操作"),
            "跨 region 的清理跳过必须留痕");
    }

    /** 同一个实体的跨 region 跳过只记一次，避免上线事件里对每个 viewer 各刷一条。 */
    @Test
    void skippedCrossRegionLogIsThrottledPerEntity() throws Exception {
        RecordingEntity entity = new RecordingEntity();
        Fixture fixture = new Fixture(new StubGate(false));

        for (int i = 0; i < 5; i++) {
            fixture.service.setFurnitureHitboxesVisible(entity.proxy(), fixture.viewer, true);
        }

        assertEquals(1, fixture.logs.count("跳过跨 region 的 CraftEngine 家具操作"),
            "同一实体的跳过告警必须限频，否则重发 hitbox 的路径会刷屏");
    }

    /**
     * 归属成立时不得被门禁误伤：仍要走到 CE 桥（本测试无 CE，故在 bridge() 处正常返回 false），
     * 并且【不该】产生跨 region 跳过日志。
     */
    @Test
    void sameRegionDoesNotReportCrossRegionSkip() throws Exception {
        RecordingEntity entity = new RecordingEntity();
        Fixture fixture = new Fixture(new StubGate(true));

        assertDoesNotThrow(() -> fixture.service.setFurnitureHitboxesVisible(
            entity.proxy(), fixture.viewer, true));

        assertEquals(0, fixture.logs.count("跳过跨 region 的 CraftEngine 家具操作"),
            "归属成立时不该记录跨 region 跳过（否则会掩盖真实问题）");
    }

    /** 归属判定必须早于 CE 桥探测：跨 region 时无论 CE 是否可用都不该继续碰实体。 */
    @Test
    void regionGateRunsBeforeCraftEngineBridgeLookup() throws Exception {
        Fixture fixture = new Fixture(new StubGate(false));
        RecordingEntity entity = new RecordingEntity();

        fixture.service.removeFurniture(entity.proxy());

        assertEquals(1, fixture.gate.calls.get(),
            "归属判定必须被真实调用一次（漏掉就会在错误的 lane 上读实体）");
        assertEquals(0, entity.entityCalls.get(), "门禁不生效时实体就会被读");
    }

    /**
     * {@code describeFailure} 必须在 message 为 null 时给出异常类型与目标异常。
     *
     * <p>实服那条告警是 “bridge failed: null”，完全看不出原因；原因是 CE 抛出的
     * {@code IllegalStateException} 被反射调用包装，而包装异常的 {@code getMessage()} 就是 null。
     *
     * <p>【为什么查 {@code Throwable} 而不是 {@code ReflectiveOperationException}】：
     * 反射调用改用 TabooLib 的 {@code ClassMethod.invoke} 后，目标异常【不再被包成
     * {@code InvocationTargetException}】（实测原样抛出），调用点 catch 的是 {@code Throwable}，
     * 因此 {@code describeFailure} 的形参相应放宽为 {@code Throwable}。这是形参类型随机制变更，
     * 不是断言弱化：下面两条断言仍然要求「包装异常场景」与「原样抛出场景」各自都能给出
     * 目标异常类型与消息，覆盖比原来更全。
     */
    @Test
    void failureDescriptionNeverDegeneratesToNull() throws Exception {
        Method describeFailure = CraftEngineFurnitureService.class.getDeclaredMethod(
            "describeFailure", Throwable.class);
        describeFailure.setAccessible(true);

        // 场景一：历史形态（反射把目标异常包进 InvocationTargetException，其 message 为 null）。
        InvocationTargetException wrapped = new InvocationTargetException(
            new IllegalStateException("Accessing entity state off owning region's thread"));
        String text = (String) describeFailure.invoke(null, wrapped);

        assertTrue(text.contains("InvocationTargetException"),
            "message 为 null 时必须退回到异常类型，不能只留 null：" + text);
        assertTrue(text.contains("IllegalStateException"),
            "必须带上被包装的目标异常类型：" + text);
        assertTrue(text.contains("Accessing entity state off owning region's thread"),
            "目标异常的 message 是有价值的排查线索，必须保留：" + text);
        assertFalse(text.trim().equalsIgnoreCase("null"), "不能再退化回 null");

        // 场景二：TabooLib 形态（目标异常原样抛出、仅通过 getCause 携带线索）。
        IllegalStateException direct = new IllegalStateException(
            "CraftEngine furniture failed", new IllegalArgumentException("off owning region"));
        String directText = (String) describeFailure.invoke(null, direct);
        assertTrue(directText.contains("CraftEngine furniture failed"),
            "直接抛出的异常必须原样保留自己的 message：" + directText);
        assertTrue(directText.contains("IllegalArgumentException") && directText.contains("off owning region"),
            "cause 的类型与消息也必须带出，否则排查线索又会丢失：" + directText);
    }

    /** 源码契约：归属门禁必须排在两个公共入口的实体访问之前。 */
    @Test
    void sourceKeepsTheRegionGateBeforeEntityAccess() throws IOException {
        String source = Files.readString(SERVICE);
        assertTrue(source.contains("Bukkit.isOwnedByCurrentRegion(entity)"),
            "生产实现必须使用 Bukkit 的 region 归属判定");
        for (String signature : List.of(
            "public boolean setFurnitureHitboxesVisible(",
            "public boolean removeFurniture("
        )) {
            String body = methodBody(source, signature);
            int gate = body.indexOf("ownsEntityState(entity)");
            assertTrue(gate >= 0, signature + " 缺少归属门禁，跨 region 读实体会复发");
            // 注意比较的是【调用】而不是标识符首次出现：门禁的注释里会提到 resolveLoadedFurniture，
            // 用它做锚点会让断言被注释文本架空。
            int entityRead = body.indexOf("resolveLoadedFurniture(entity)");
            if (entityRead >= 0) {
                assertTrue(gate < entityRead,
                    signature + " 的归属门禁必须排在 resolveLoadedFurniture（读实体）之前");
            }
        }
    }

    /**
     * 源码契约：本类所有「反射失败」告警都必须经 {@code describeFailure}，不得直拼 {@code getMessage()}。
     *
     * <p>意图：{@code InvocationTargetException} 这类包装异常的 {@code getMessage()} 常为 {@code null}，
     * 直拼会让实服日志变成 “... failed: null”，完全无法排查（这正是 {@code describeFailure} 存在的理由）。
     * 上面那条 {@code @Test} 走的是 {@code describeFailure} 本身；这一条钉的是【调用点】——
     * 光有方法、调用点退回直拼，日志质量一样会退化。
     *
     * <p>此处只对「日志」施加约束，不断言整体形状：{@code bridge()} 里初始化失败的两条告警
     * 用的是 {@code ClassNotFoundException}/{@code LinkageError} 等自带可用 message 的异常，
     * 属于「同一文件里两种既有写法并存」，本任务不顺手改（一致性 < 最小改动）。
     */
    @Test
    void everyReflectiveFailureLogGoesThroughDescribeFailure() throws IOException {
        String source = Files.readString(SERVICE);
        for (String prefix : List.of(
            "CraftEngine furniture placement failed: ",
            "CraftEngine placement detection failed: ",
            "CraftEngine custom item resolve failed: ",
            "CraftEngine block placement failed: "
        )) {
            int start = source.indexOf(prefix);
            assertTrue(start >= 0, "找不到告警文案 " + prefix + "，这条测试的锚点已失效");
            // 只看这条 warning 的拼接表达式（同一行到行尾）。
            int end = source.indexOf('\n', start);
            String logLine = source.substring(start, end);
            assertTrue(logLine.contains("describeFailure(exception)"),
                prefix + " 的告警必须经 describeFailure，直拼 getMessage() 会写出 “: null”： " + logLine);
            assertFalse(logLine.contains("exception.getMessage()"),
                prefix + " 的告警不得再直拼 getMessage()： " + logLine);
        }
    }

    private static String methodBody(String source, String signature) {
        int start = source.indexOf(signature);
        assertTrue(start >= 0, "找不到 " + signature + "，这条测试的锚点已失效");
        int end = source.indexOf("\n    }", start);
        assertTrue(end > start, "找不到 " + signature + " 的结束锚点");
        return source.substring(start, end);
    }

    private static final class Fixture {
        private final CraftEngineFurnitureService service;
        private final StubGate gate;
        private final CollectingLogs logs;
        private final Player viewer;
        private final AtomicInteger serverLookups;

        private Fixture(StubGate gate) throws Exception {
            this.gate = gate;
            DoudizhuPlugin plugin = (DoudizhuPlugin) unsafe().allocateInstance(DoudizhuPlugin.class);
            this.logs = new CollectingLogs();
            setField(plugin, "logger", logs.logger());
            // bridge() 会问插件管理器要 CraftEngine；本测试没有 CE，替身返回 null 即「未安装」，
            // 于是 CE 桥在归属门禁之后正常降级返回 false，不会碰实体。
            this.serverLookups = new AtomicInteger();
            setField(plugin, "server", fakeServer(serverLookups));
            this.service = new CraftEngineFurnitureService(plugin, gate);
            this.viewer = fakePlayer(UUID.randomUUID());
        }
    }

    /**
     * 没有 CraftEngine 的服务端替身，并统计 CE 桥被探测的次数。
     *
     * <p>统计次数是为了让「归属判定早于 CE 桥探测」可观察：跨 region 时 `getServer()` 一次都不该被调用，
     * 这条断言不依赖 CE 是否安装，因而不会因为测试环境没有 CE 而变成空断言。
     */
    private static Server fakeServer(AtomicInteger serverLookups) {
        PluginManager pluginManager = (PluginManager) Proxy.newProxyInstance(
            CraftEngineFurnitureRegionGateTest.class.getClassLoader(),
            new Class<?>[] {PluginManager.class},
            (proxy, method, args) -> switch (method.getName()) {
                case "getPlugin" -> null;
                case "hashCode" -> System.identityHashCode(proxy);
                case "equals" -> proxy == args[0];
                case "toString" -> "fake-plugin-manager";
                default -> null;
            });
        return (Server) Proxy.newProxyInstance(
            CraftEngineFurnitureRegionGateTest.class.getClassLoader(),
            new Class<?>[] {Server.class},
            (proxy, method, args) -> switch (method.getName()) {
                case "getPluginManager" -> {
                    serverLookups.incrementAndGet();
                    yield pluginManager;
                }
                case "hashCode" -> System.identityHashCode(proxy);
                case "equals" -> proxy == args[0];
                case "toString" -> "fake-server";
                default -> null;
            });
    }

    /** region 归属判定替身：Bukkit 静态方法无法在单测里伪造，只能从这个注入点驱动。 */
    private static final class StubGate implements CraftEngineFurnitureService.RegionGate {
        private final boolean owned;
        private final AtomicInteger calls = new AtomicInteger();

        private StubGate(boolean owned) {
            this.owned = owned;
        }

        @Override
        public boolean isOwnedByCurrentRegion(Entity entity) {
            calls.incrementAndGet();
            return owned;
        }
    }

    /**
     * 记录型 fake 实体：任何方法调用都计数。
     *
     * <p>Folia 上真正的实体状态读取会抛 “Accessing entity state off owning region's thread”，
     * 这里用「有没有被调用过」作为等价可观察量——只要门禁在前，就一次都不会被调用。
     */
    private static final class RecordingEntity {
        private final UUID id = UUID.randomUUID();
        private final AtomicInteger entityCalls = new AtomicInteger();

        private Entity proxy() {
            return (Entity) Proxy.newProxyInstance(
                CraftEngineFurnitureRegionGateTest.class.getClassLoader(),
                new Class<?>[] {Entity.class},
                (proxy, method, args) -> {
                    switch (method.getName()) {
                        case "getUniqueId":
                            return id;
                        case "hashCode":
                            return System.identityHashCode(proxy);
                        case "equals":
                            return proxy == args[0];
                        case "toString":
                            return "recording-entity:" + id;
                        default:
                            // 任何其它调用（getEntityId/getLocation/remove/…）都是「碰了实体状态」。
                            entityCalls.incrementAndGet();
                            return null;
                    }
                });
        }
    }

    private static Player fakePlayer(UUID playerId) {
        return (Player) Proxy.newProxyInstance(
            CraftEngineFurnitureRegionGateTest.class.getClassLoader(),
            new Class<?>[] {Player.class},
            (proxy, method, args) -> switch (method.getName()) {
                case "getUniqueId" -> playerId;
                case "isOnline" -> true;
                case "hashCode" -> System.identityHashCode(proxy);
                case "equals" -> proxy == args[0];
                case "toString" -> "fake-player:" + playerId;
                default -> null;
            });
    }

    /** 收集日志文本的 handler。 */
    private static final class CollectingLogs {
        private final List<String> messages = new ArrayList<>();
        private final Logger logger = Logger.getLogger("muz-furniture-gate-test-" + UUID.randomUUID());

        private CollectingLogs() {
            logger.setUseParentHandlers(false);
            logger.setLevel(Level.ALL);
            logger.addHandler(new Handler() {
                @Override
                public void publish(LogRecord record) {
                    if (record.getMessage() != null) {
                        messages.add(record.getMessage());
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

        private boolean contains(String fragment) {
            return count(fragment) > 0;
        }

        private int count(String fragment) {
            int total = 0;
            for (String message : messages) {
                if (message.contains(fragment)) {
                    total++;
                }
            }
            return total;
        }
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
}
