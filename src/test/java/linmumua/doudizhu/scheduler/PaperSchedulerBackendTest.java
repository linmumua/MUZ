package linmumua.doudizhu.scheduler;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.papermc.paper.threadedregions.scheduler.EntityScheduler;
import io.papermc.paper.threadedregions.scheduler.GlobalRegionScheduler;
import io.papermc.paper.threadedregions.scheduler.RegionScheduler;
import io.papermc.paper.threadedregions.scheduler.ScheduledTask;
import java.lang.reflect.Proxy;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;
import org.bukkit.Location;
import org.bukkit.Server;
import org.bukkit.entity.Entity;
import org.bukkit.plugin.Plugin;
import org.junit.jupiter.api.Test;

class PaperSchedulerBackendTest {
    @Test
    void globalAndRegionZeroDelayUseImmediateApi() {
        AtomicReference<String> globalMethod = new AtomicReference<>();
        AtomicReference<String> regionMethod = new AtomicReference<>();
        TestHarness harness = new TestHarness(globalMethod, regionMethod);
        PaperSchedulerBackend backend = harness.backend();

        backend.runGlobal(0, 0, ignored -> { });
        backend.runRegion(location(), 0, 0, ignored -> { });

        assertEquals("run", globalMethod.get());
        assertEquals("run", regionMethod.get());
    }

    /**
     * 真实 Folia/Leaf 的 runAtFixedRate 对初始延迟 <= 0 直接抛 IllegalArgumentException；
     * 开局发牌 timer 以 runTableTimer(0L, 1L) 注册，若 0 延迟原样透传，开局会停在 DEALING。
     * 这里用会像真实内核一样拒绝 0 延迟的原生替身，锁定周期任务必须被钳到至少 1 tick。
     */
    @Test
    void repeatingZeroDelayIsClampedBecauseFoliaRejectsNonPositiveInitialDelay() {
        TestHarness harness = new TestHarness(new AtomicReference<>(), new AtomicReference<>());
        harness.rejectNonPositiveFixedRateDelay = true;
        PaperSchedulerBackend backend = harness.backend();

        MuzScheduler.TaskHandle global = backend.runGlobal(0, 1, ignored -> { });
        MuzScheduler.TaskHandle region = backend.runRegion(location(), 0, 1, ignored -> { });

        assertEquals(1L, harness.globalFixedRateDelay.get());
        assertEquals(1L, harness.regionFixedRateDelay.get());
        assertFalse(global.isCancelled());
        assertFalse(region.isCancelled());

        // 正延迟不得被改写。
        backend.runGlobal(5, 1, ignored -> { });
        backend.runRegion(location(), 7, 1, ignored -> { });
        assertEquals(5L, harness.globalFixedRateDelay.get());
        assertEquals(7L, harness.regionFixedRateDelay.get());
    }

    @Test
    void nullTargetsAndCallbacksAreRejectedBeforeNativeScheduling() {
        TestHarness harness = new TestHarness(new AtomicReference<>(), new AtomicReference<>());
        PaperSchedulerBackend backend = harness.backend();

        assertThrows(NullPointerException.class, () -> backend.runGlobal(0, 0, null));
        assertThrows(NullPointerException.class, () -> backend.runRegion(null, 0, 0, ignored -> { }));
        assertThrows(NullPointerException.class, () -> backend.runEntity(null, 0, 0, ignored -> { }));
        assertThrows(NullPointerException.class, () -> backend.runPlayer(null, 0, 0, ignored -> { }));
    }

    @Test
    void entityZeroDelayIsClampedToOneTick() {
        TestHarness harness = new TestHarness(new AtomicReference<>(), new AtomicReference<>());
        Entity entity = entity(harness.entityScheduler);

        harness.backend().runEntity(entity, 0, 0, ignored -> { });

        assertEquals(1L, harness.entityDelay.get());
    }

    @Test
    void nullNativeTaskTerminatesAndRetiredOnlyTerminates() {
        TestHarness harness = new TestHarness(new AtomicReference<>(), new AtomicReference<>());
        harness.globalReturnsNull = true;
        MuzScheduler.TaskHandle rejected = harness.backend().runGlobal(1, 0, ignored -> { });
        AtomicInteger terminations = new AtomicInteger();
        rejected.onTermination(terminations::incrementAndGet);

        Entity entity = entity(harness.entityScheduler);
        AtomicInteger callbackCalls = new AtomicInteger();
        MuzScheduler.TaskHandle entityHandle = harness.backend().runEntity(entity, 1, 0,
            ignored -> callbackCalls.incrementAndGet());
        entityHandle.onTermination(terminations::incrementAndGet);
        harness.entityRetired.get().run();
        harness.entityRetired.get().run();

        assertTrue(rejected.isCancelled());
        assertEquals(2, terminations.get());
        assertFalse(entityHandle.isCancelled());
        assertEquals(0, callbackCalls.get());
        assertNotNull(harness.entityCallback.get());
    }

    @Test
    void synchronousRegistrationCanCompleteAndCloseWithoutBackendLock() {
        AtomicReference<String> globalMethod = new AtomicReference<>();
        TestHarness harness = new TestHarness(globalMethod, new AtomicReference<>());
        PaperSchedulerBackend backend = harness.backend();
        AtomicInteger calls = new AtomicInteger();
        AtomicReference<MuzScheduler.TaskHandle> handle = new AtomicReference<>();
        harness.invokeGlobalSynchronously = true;
        harness.globalTask = ignored -> {
            calls.incrementAndGet();
            backend.close();
        };

        handle.set(backend.runGlobal(0, 0, harness.globalTask));

        assertNotNull(handle.get());
        assertEquals(1, calls.get());
        assertEquals("run", globalMethod.get());
        assertNotNull(harness.globalCallback.get());
    }

    @Test
    void repeatingCallbackFailureCancelsNativeTask() {
        TestHarness harness = new TestHarness(new AtomicReference<>(), new AtomicReference<>());
        PaperSchedulerBackend backend = harness.backend();

        backend.runGlobal(1, 1, ignored -> { throw new IllegalStateException("boom"); });

        assertThrows(IllegalStateException.class,
            () -> harness.globalCallback.get().accept(harness.nativeTask));
        assertTrue(harness.nativeCancelled.get());
    }

    @Test
    void oneShotCompletionDoesNotCancelNativeTask() {
        TestHarness harness = new TestHarness(new AtomicReference<>(), new AtomicReference<>());
        PaperSchedulerBackend backend = harness.backend();

        backend.runGlobal(1, 0, ignored -> { });
        harness.globalCallback.get().accept(harness.nativeTask);

        assertFalse(harness.nativeCancelled.get());
    }

    @Test
    void differentLanesDoNotSerializeUserCallbacks() throws Exception {
        TestHarness harness = new TestHarness(new AtomicReference<>(), new AtomicReference<>());
        PaperSchedulerBackend backend = harness.backend();
        CountDownLatch globalEntered = new CountDownLatch(1);
        CountDownLatch releaseGlobal = new CountDownLatch(1);
        CountDownLatch regionFinished = new CountDownLatch(1);

        backend.runGlobal(1, 0, ignored -> {
            globalEntered.countDown();
            await(releaseGlobal);
        });
        backend.runRegion(location(), 1, 0, ignored -> regionFinished.countDown());

        Thread globalThread = new Thread(() -> harness.globalCallback.get().accept(harness.nativeTask));
        globalThread.start();
        try {
            assertTrue(globalEntered.await(1, TimeUnit.SECONDS));
            harness.regionCallback.get().accept(harness.nativeTask);
            assertTrue(regionFinished.await(1, TimeUnit.SECONDS));
        } finally {
            releaseGlobal.countDown();
            globalThread.join(1000);
        }
        assertFalse(globalThread.isAlive());
    }

    private static void await(CountDownLatch latch) {
        try {
            latch.await();
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new AssertionError(interrupted);
        }
    }

    private static Location location() {
        return new Location(null, 0.0, 0.0, 0.0);
    }

    private static Entity entity(EntityScheduler scheduler) {
        return (Entity) Proxy.newProxyInstance(
            Entity.class.getClassLoader(),
            new Class<?>[]{Entity.class},
            (proxy, method, args) -> method.getName().equals("getScheduler")
                ? scheduler : defaultValue(method.getReturnType())
        );
    }

    private static Object defaultValue(Class<?> type) {
        if (!type.isPrimitive()) {
            return null;
        }
        if (type == boolean.class) return false;
        if (type == byte.class) return (byte) 0;
        if (type == short.class) return (short) 0;
        if (type == int.class) return 0;
        if (type == long.class) return 0L;
        if (type == float.class) return 0.0f;
        if (type == double.class) return 0.0d;
        if (type == char.class) return (char) 0;
        return null;
    }

    private static final class TestHarness {
        private final AtomicReference<String> globalMethod;
        private final AtomicReference<String> regionMethod;
        private final AtomicBoolean nativeCancelled = new AtomicBoolean();
        private final ScheduledTask nativeTask = (ScheduledTask) Proxy.newProxyInstance(
            ScheduledTask.class.getClassLoader(),
            new Class<?>[]{ScheduledTask.class},
            (proxy, method, args) -> {
                if (method.getName().equals("cancel")) {
                    nativeCancelled.set(true);
                    return null;
                }
                if (method.getName().equals("isCancelled")) {
                    return nativeCancelled.get();
                }
                return defaultValue(method.getReturnType());
            }
        );
        private final AtomicReference<Consumer<ScheduledTask>> globalCallback = new AtomicReference<>();
        private final AtomicReference<Consumer<ScheduledTask>> regionCallback = new AtomicReference<>();
        private final AtomicReference<Long> entityDelay = new AtomicReference<>();
        private final AtomicReference<Consumer<ScheduledTask>> entityCallback = new AtomicReference<>();
        private final AtomicReference<Runnable> entityRetired = new AtomicReference<>();
        private final EntityScheduler entityScheduler;
        private final AtomicReference<Long> globalFixedRateDelay = new AtomicReference<>();
        private final AtomicReference<Long> regionFixedRateDelay = new AtomicReference<>();
        /* 模拟 Folia/Leaf 的 "Initial delay ticks may not be <= 0" 校验。 */
        private boolean rejectNonPositiveFixedRateDelay;
        private boolean globalReturnsNull;
        private boolean invokeGlobalSynchronously;
        private Consumer<MuzScheduler.TaskHandle> globalTask = ignored -> { };

        private TestHarness(AtomicReference<String> globalMethod, AtomicReference<String> regionMethod) {
            this.globalMethod = globalMethod;
            this.regionMethod = regionMethod;
            this.entityScheduler = (EntityScheduler) Proxy.newProxyInstance(
                EntityScheduler.class.getClassLoader(),
                new Class<?>[]{EntityScheduler.class},
                (proxy, method, args) -> {
                    if (method.getName().equals("runDelayed")) {
                        entityDelay.set((Long) args[3]);
                        @SuppressWarnings("unchecked") Consumer<ScheduledTask> callback =
                            (Consumer<ScheduledTask>) args[1];
                        entityCallback.set(callback);
                        entityRetired.set((Runnable) args[2]);
                    }
                    return nativeTask;
                }
            );
        }

        private PaperSchedulerBackend backend() {
            GlobalRegionScheduler global = (GlobalRegionScheduler) Proxy.newProxyInstance(
                GlobalRegionScheduler.class.getClassLoader(),
                new Class<?>[]{GlobalRegionScheduler.class},
                (proxy, method, args) -> {
                    globalMethod.set(method.getName());
                    if (method.getName().equals("runAtFixedRate")) {
                        long delay = (Long) args[2];
                        globalFixedRateDelay.set(delay);
                        if (rejectNonPositiveFixedRateDelay && delay <= 0L) {
                            throw new IllegalArgumentException("Initial delay ticks may not be <= 0");
                        }
                    }
                    if (method.getName().equals("run") || method.getName().equals("runDelayed")
                        || method.getName().equals("runAtFixedRate")) {
                        @SuppressWarnings("unchecked") Consumer<ScheduledTask> callback =
                            (Consumer<ScheduledTask>) args[1];
                        globalCallback.set(callback);
                        if (invokeGlobalSynchronously) {
                            callback.accept(nativeTask);
                        }
                    }
                    return globalReturnsNull ? null : nativeTask;
                }
            );
            RegionScheduler region = (RegionScheduler) Proxy.newProxyInstance(
                RegionScheduler.class.getClassLoader(),
                new Class<?>[]{RegionScheduler.class},
                (proxy, method, args) -> {
                    regionMethod.set(method.getName());
                    if (method.getName().equals("runAtFixedRate") && args != null && args.length == 5
                        && args[1] instanceof Location) {
                        long delay = (Long) args[3];
                        regionFixedRateDelay.set(delay);
                        if (rejectNonPositiveFixedRateDelay && delay <= 0L) {
                            throw new IllegalArgumentException("Initial delay ticks may not be <= 0");
                        }
                    }
                    if ((method.getName().equals("run") || method.getName().equals("runDelayed"))
                        && args != null && args.length > 2 && args[2] instanceof Consumer<?> callback) {
                        @SuppressWarnings("unchecked") Consumer<ScheduledTask> typed =
                            (Consumer<ScheduledTask>) callback;
                        regionCallback.set(typed);
                    }
                    return nativeTask;
                }
            );
            Server server = (Server) Proxy.newProxyInstance(
                Server.class.getClassLoader(),
                new Class<?>[]{Server.class},
                (proxy, method, args) -> switch (method.getName()) {
                    case "getGlobalRegionScheduler" -> global;
                    case "getRegionScheduler" -> region;
                    case "getAsyncScheduler" -> null;
                    default -> defaultValue(method.getReturnType());
                }
            );
            Plugin plugin = (Plugin) Proxy.newProxyInstance(
                Plugin.class.getClassLoader(),
                new Class<?>[]{Plugin.class},
                (proxy, method, args) -> method.getName().equals("getServer")
                    ? server : defaultValue(method.getReturnType())
            );
            return new PaperSchedulerBackend(plugin);
        }
    }
}
