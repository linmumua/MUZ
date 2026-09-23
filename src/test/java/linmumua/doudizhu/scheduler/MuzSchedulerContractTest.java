package linmumua.doudizhu.scheduler;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;
import java.util.stream.Stream;
import org.bukkit.Location;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

/**
 * 第一阶段 MuzScheduler 调度契约。
 *
 * <p>测试真实的 MuzScheduler → SchedulerBackend 委托，不触碰 Bukkit 线程实现。当前生产接口已经
 * 暴露 global、region、entity、player、async 五类 lane；delay/period 仍统一由后端解释为 tick。
 * 生命周期测试通过 CaptureBackend 保留后端回调，再验证 close、代次与取消屏障。
 */
class MuzSchedulerContractTest {
    private static final Set<Lane> LANES = EnumSet.allOf(Lane.class);

    @ParameterizedTest(name = "{0} lane 委托目标、delay 与一次性 period")
    @MethodSource("lanes")
    void lanePreservesTargetAndDelay(Lane lane) {
        CaptureBackend backend = new CaptureBackend();
        MuzScheduler scheduler = new MuzScheduler(backend);
        Object target = lane.target();
        List<String> calls = new ArrayList<>();

        MuzScheduler.TaskHandle handle = lane.schedule(scheduler, target, 5,
            () -> calls.add(lane.name()));

        assertNotNull(handle);
        assertEquals(lane, backend.last.lane);
        assertSame(target, backend.last.target);
        assertEquals(5L, backend.last.delay);
        assertEquals(0L, backend.last.period);

        backend.last.fire();
        assertEquals(List.of(lane.name()), calls);
    }

    @ParameterizedTest(name = "{0} lane 周期任务传递 delay/period")
    @MethodSource("lanes")
    void timerPreservesDelayAndPeriod(Lane lane) {
        CaptureBackend backend = new CaptureBackend();
        MuzScheduler scheduler = new MuzScheduler(backend);
        Object target = lane.target();
        List<String> calls = new ArrayList<>();

        MuzScheduler.TaskHandle handle = lane.scheduleTimer(scheduler, target, 3, 4,
            () -> calls.add("tick"));

        assertNotNull(handle);
        assertEquals(lane, backend.last.lane);
        assertSame(target, backend.last.target);
        assertEquals(3L, backend.last.delay);
        assertEquals(4L, backend.last.period);

        backend.last.fire();
        backend.last.fire();
        assertEquals(List.of("tick", "tick"), calls);
    }

    @Test
    void consumerTimerReceivesTheSameHandleAndCanCancelItself() {
        CaptureBackend backend = new CaptureBackend();
        MuzScheduler scheduler = new MuzScheduler(backend);
        List<MuzScheduler.TaskHandle> received = new ArrayList<>();

        MuzScheduler.TaskHandle returned = scheduler.runGlobalTimer(2, 6, handle -> {
            received.add(handle);
            handle.cancel();
        });

        backend.last.fire();

        assertEquals(1, received.size());
        assertSame(returned, received.get(0));
        assertTrue(backend.last.cancelled);
    }

    @Test
    void cancellingReturnedHandleDelegatesToBackendHandle() {
        CaptureBackend backend = new CaptureBackend();
        MuzScheduler.TaskHandle returned = new MuzScheduler(backend)
            .runPlayer(null, 10, () -> { });

        returned.cancel();

        assertTrue(backend.last.cancelled);
    }

    @Test
    void currentSurfaceContainsFiveLanesAndLegacyGlobalAliases() {
        Set<String> methods = new HashSet<>();
        for (Method method : MuzScheduler.class.getDeclaredMethods()) {
            methods.add(method.getName());
        }

        assertTrue(methods.containsAll(Set.of(
            "runGlobal", "runRegion", "runEntity", "runPlayer", "runAsync",
            "runGlobalTimer", "runRegionTimer", "runEntityTimer", "runPlayerTimer",
            "runAsyncTimer", "runSync", "runLater", "runTimer",
            "close", "isClosed", "generation", "newGeneration", "cancelAll", "ownerId"
        )));
        assertEquals(2,
            Stream.of(MuzScheduler.class.getDeclaredMethods())
                .filter(method -> method.getName().equals("runTimer"))
                .count());
    }

    @Test
    void closeCancelsAllTasksAndIsIdempotent() {
        CaptureBackend backend = new CaptureBackend();
        MuzScheduler scheduler = new MuzScheduler(backend);
        List<String> calls = new ArrayList<>();

        MuzScheduler.TaskHandle first = scheduler.runGlobalTimer(20, 5, () -> calls.add("first"));
        CapturedTask firstTask = backend.last;
        MuzScheduler.TaskHandle second = scheduler.runAsync(40, () -> calls.add("second"));
        CapturedTask secondTask = backend.last;

        scheduler.close();
        scheduler.close();

        assertTrue(scheduler.isClosed());
        assertEquals(1L, scheduler.generation(), "首次 close 必须推进一次代次");
        assertEquals(1, backend.closeCalls, "重复 close 不得重复关闭后端");
        assertTrue(first.isCancelled());
        assertTrue(second.isCancelled());
        assertTrue(firstTask.cancelled);
        assertTrue(secondTask.cancelled);
        assertTrue(calls.isEmpty(), "close 后不得执行已排队任务");
    }

    @Test
    void lateCallbackCannotEnterAfterClose() {
        CaptureBackend backend = new CaptureBackend();
        MuzScheduler scheduler = new MuzScheduler(backend);
        List<String> calls = new ArrayList<>();

        MuzScheduler.TaskHandle handle = scheduler.runGlobal(20, () -> calls.add("late"));
        CapturedTask task = backend.last;
        scheduler.close();
        task.forceFire();

        assertTrue(handle.isCancelled());
        assertTrue(calls.isEmpty(), "close 后迟到回调不得进入业务");
    }

    @Test
    void restartGenerationInvalidatesOldCallbacksAndKeepsNewGeneration() {
        CaptureBackend backend = new CaptureBackend();
        MuzScheduler scheduler = new MuzScheduler(backend);
        List<String> calls = new ArrayList<>();

        MuzScheduler.TaskHandle oldHandle = scheduler.runGlobal(20, () -> calls.add("old"));
        CapturedTask oldTask = backend.last;
        assertEquals(0L, oldHandle.generation());

        long nextGeneration = scheduler.newGeneration();
        MuzScheduler.TaskHandle newHandle = scheduler.runGlobal(0, () -> calls.add("new"));
        CapturedTask newTask = backend.last;
        oldTask.forceFire();
        newTask.fire();

        assertEquals(1L, nextGeneration);
        assertEquals(1L, scheduler.generation());
        assertTrue(oldHandle.isCancelled(), "旧代次任务必须取消");
        assertFalse(newHandle.isCancelled(), "新代次任务不得继承旧代次取消状态");
        assertEquals(1L, newHandle.generation());
        assertEquals(List.of("new"), calls, "旧代次迟到回调不得污染新代次");
    }

    @Test
    void cancelAllCancelsPendingTasksWithoutChangingGeneration() {
        CaptureBackend backend = new CaptureBackend();
        MuzScheduler scheduler = new MuzScheduler(backend);
        List<String> calls = new ArrayList<>();

        MuzScheduler.TaskHandle first = scheduler.runGlobal(20, () -> calls.add("first"));
        CapturedTask firstTask = backend.last;
        MuzScheduler.TaskHandle second = scheduler.runGlobalTimer(20, 5, () -> calls.add("second"));
        CapturedTask secondTask = backend.last;
        long generation = scheduler.generation();

        scheduler.cancelAll();
        firstTask.forceFire();
        secondTask.forceFire();

        assertEquals(generation, scheduler.generation(), "cancelAll 不得推进代次");
        assertTrue(first.isCancelled());
        assertTrue(second.isCancelled());
        assertTrue(calls.isEmpty(), "cancelAll 后迟到回调不得执行");
    }

    @Test
    void cancellingTaskIsIdempotentAndExposesOwnerAndGeneration() {
        CaptureBackend backend = new CaptureBackend();
        MuzScheduler scheduler = new MuzScheduler(backend);
        List<String> calls = new ArrayList<>();

        MuzScheduler.TaskHandle handle = scheduler.runGlobalTimer(2, 6, () -> calls.add("cancelled"));
        CapturedTask task = backend.last;

        assertEquals("global", handle.ownerId());
        assertEquals(scheduler.ownerId(), backend.ownerId());
        assertEquals(scheduler.generation(), handle.generation());
        handle.cancel();
        handle.cancel();
        task.forceFire();

        assertTrue(handle.isCancelled());
        assertTrue(task.cancelled);
        assertTrue(calls.isEmpty(), "取消后的任务不得执行");
    }

    @Test
    void synchronousBackendCallbackCompletesOnceAndCleansRegistration() {
        CaptureBackend backend = new CaptureBackend();
        backend.invokeSynchronously = true;
        MuzScheduler scheduler = new MuzScheduler(backend);
        AtomicInteger calls = new AtomicInteger();
        AtomicInteger terminations = new AtomicInteger();

        MuzScheduler.TaskHandle handle = scheduler.runGlobal(() -> calls.incrementAndGet());
        handle.onTermination(terminations::incrementAndGet);
        backend.last.forceFire();

        assertEquals(1, calls.get());
        assertEquals(1, terminations.get());
        assertFalse(handle.isCancelled());
    }

    @Test
    void nullBackendHandleIsRejectedAndTerminated() {
        CaptureBackend backend = new CaptureBackend();
        backend.returnNull = true;
        MuzScheduler scheduler = new MuzScheduler(backend);
        AtomicInteger terminations = new AtomicInteger();

        MuzScheduler.TaskHandle handle = scheduler.runGlobal(() -> { });
        handle.onTermination(terminations::incrementAndGet);

        assertTrue(handle.isCancelled());
        assertEquals(1, terminations.get());
    }

    @Test
    void shuttingDownPredicateRejectsNewTasksBeforeBackendRegistration() {
        CaptureBackend backend = new CaptureBackend();
        MuzScheduler scheduler = new MuzScheduler(backend, () -> true);
        List<String> calls = new ArrayList<>();
        AtomicInteger terminations = new AtomicInteger();

        MuzScheduler.TaskHandle handle = scheduler.runGlobal(0, () -> calls.add("rejected"));
        handle.onTermination(terminations::incrementAndGet);

        assertTrue(handle.isCancelled(), "关闭中必须返回已取消句柄");
        assertEquals(1, terminations.get(), "被拒绝的句柄必须终止一次");
        // 关键：关闭中必须在触碰后端之前就拦下；Paper/Folia 在插件禁用后会拒绝注册并抛异常。
        assertNull(backend.last, "关闭中不得把任务交给后端注册");
        assertTrue(calls.isEmpty(), "关闭中拒绝的任务不得执行回调");
    }

    @Test
    void shuttingDownRejectionIsLoggedOnlyOnceAndResetByNewGeneration() {
        CaptureBackend backend = new CaptureBackend();
        MuzScheduler scheduler = new MuzScheduler(backend, () -> true);
        java.util.logging.Logger logger = java.util.logging.Logger.getLogger("MUZ");
        java.util.logging.Level previousLevel = logger.getLevel();
        List<String> messages = new java.util.concurrent.CopyOnWriteArrayList<>();
        java.util.logging.Handler handler = new java.util.logging.Handler() {
            @Override
            public void publish(java.util.logging.LogRecord record) {
                messages.add(record.getMessage());
            }

            @Override
            public void flush() {
            }

            @Override
            public void close() {
            }
        };
        logger.setLevel(java.util.logging.Level.ALL);
        logger.addHandler(handler);
        try {
            scheduler.runGlobal(() -> { });
            scheduler.runGlobalTimer(0, 1, () -> { });
            scheduler.runAsync(() -> { });
            assertEquals(1L, shutdownRejectionWarnings(messages),
                "关服阶段每个调用点都会重试，同一代次内关闭拒绝只应告警一次");

            // 代次推进后必须重新允许一次告警，避免偶尔一次拒绝把后续真实违规永久静音。
            scheduler.newGeneration();
            scheduler.runGlobal(() -> { });
            assertEquals(2L, shutdownRejectionWarnings(messages),
                "代次推进后应重新允许一次关闭拒绝告警");
        } finally {
            logger.removeHandler(handler);
            logger.setLevel(previousLevel);
        }
    }

    private static long shutdownRejectionWarnings(List<String> messages) {
        return messages.stream()
            .filter(message -> message != null && message.contains("正在关闭，已拒绝在"))
            .count();
    }

    @Test
    void repeatingCallbackExceptionCleansRegistrationExactlyOnce() {
        CaptureBackend backend = new CaptureBackend();
        MuzScheduler scheduler = new MuzScheduler(backend);
        AtomicInteger terminations = new AtomicInteger();
        MuzScheduler.TaskHandle handle = scheduler.runGlobalTimer(0, 1,
            ignored -> { throw new IllegalStateException("boom"); });
        handle.onTermination(terminations::incrementAndGet);

        assertThrows(IllegalStateException.class, backend.last::forceFire);
        backend.last.forceFire();

        assertEquals(1, terminations.get());
        assertTrue(handle.isCancelled(), "重复任务异常结束必须停止后端任务");
        assertTrue(backend.last.cancelled, "重复任务异常必须取消 fake 后端句柄");
    }

    private static Stream<Arguments> lanes() {
        return LANES.stream().map(Arguments::of);
    }

    private enum Lane {
        GLOBAL,
        REGION,
        ENTITY,
        PLAYER,
        ASYNC;

        private Object target() {
            return switch (this) {
                case GLOBAL, ASYNC -> null;
                case REGION -> (Location) null;
                case ENTITY -> (Entity) null;
                case PLAYER -> (Player) null;
            };
        }

        private MuzScheduler.TaskHandle schedule(MuzScheduler scheduler, Object target, long delay,
                                                 Runnable runnable) {
            return switch (this) {
                case GLOBAL -> scheduler.runGlobal(delay, runnable);
                case REGION -> scheduler.runRegion((Location) target, delay, runnable);
                case ENTITY -> scheduler.runEntity((Entity) target, delay, runnable);
                case PLAYER -> scheduler.runPlayer((Player) target, delay, runnable);
                case ASYNC -> scheduler.runAsync(delay, runnable);
            };
        }

        private MuzScheduler.TaskHandle scheduleTimer(MuzScheduler scheduler, Object target,
                                                      long delay, long period, Runnable runnable) {
            return switch (this) {
                case GLOBAL -> scheduler.runGlobalTimer(delay, period, runnable);
                case REGION -> scheduler.runRegionTimer((Location) target, delay, period, runnable);
                case ENTITY -> scheduler.runEntityTimer((Entity) target, delay, period, runnable);
                case PLAYER -> scheduler.runPlayerTimer((Player) target, delay, period, runnable);
                case ASYNC -> scheduler.runAsyncTimer(delay, period, runnable);
            };
        }
    }

    private static final class CaptureBackend implements SchedulerBackend {
        private CapturedTask last;
        private int closeCalls;
        private boolean invokeSynchronously;
        private boolean returnNull;

        @Override
        public String ownerId() {
            return "capture-backend";
        }

        @Override
        public void close() {
            closeCalls++;
        }

        @Override
        public MuzScheduler.TaskHandle runGlobal(long delay, long period,
                                                  Consumer<MuzScheduler.TaskHandle> task) {
            return capture(Lane.GLOBAL, null, delay, period, task);
        }

        @Override
        public MuzScheduler.TaskHandle runRegion(Location location, long delay, long period,
                                                  Consumer<MuzScheduler.TaskHandle> task) {
            return capture(Lane.REGION, location, delay, period, task);
        }

        @Override
        public MuzScheduler.TaskHandle runEntity(Entity entity, long delay, long period,
                                                  Consumer<MuzScheduler.TaskHandle> task) {
            return capture(Lane.ENTITY, entity, delay, period, task);
        }

        @Override
        public MuzScheduler.TaskHandle runPlayer(Player player, long delay, long period,
                                                  Consumer<MuzScheduler.TaskHandle> task) {
            return capture(Lane.PLAYER, player, delay, period, task);
        }

        @Override
        public MuzScheduler.TaskHandle runAsync(long delay, long period,
                                                Consumer<MuzScheduler.TaskHandle> task) {
            return capture(Lane.ASYNC, null, delay, period, task);
        }

        private MuzScheduler.TaskHandle capture(Lane lane, Object target, long delay, long period,
                                                Consumer<MuzScheduler.TaskHandle> callback) {
            last = new CapturedTask(lane, target, delay, period, callback);
            if (invokeSynchronously) {
                last.fire();
            }
            return returnNull ? null : last;
        }
    }

    private static final class CapturedTask implements MuzScheduler.TaskHandle {
        private final Lane lane;
        private final Object target;
        private final long delay;
        private final long period;
        private final Consumer<MuzScheduler.TaskHandle> callback;
        private boolean cancelled;

        private CapturedTask(Lane lane, Object target, long delay, long period,
                             Consumer<MuzScheduler.TaskHandle> callback) {
            this.lane = lane;
            this.target = target;
            this.delay = delay;
            this.period = period;
            this.callback = callback;
        }

        private void fire() {
            if (!cancelled) {
                callback.accept(this);
            }
        }

        private void forceFire() {
            callback.accept(this);
        }

        @Override
        public void cancel() {
            cancelled = true;
        }

        @Override
        public boolean isCancelled() {
            return cancelled;
        }
    }

}
