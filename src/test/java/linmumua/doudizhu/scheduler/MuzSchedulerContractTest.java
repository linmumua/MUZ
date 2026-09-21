package linmumua.doudizhu.scheduler;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.lang.reflect.Method;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.HashSet;
import java.util.List;
import java.util.Queue;
import java.util.Set;
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
 * close/late callback 尚未进入生产接口，本类底部的测试模型先固定预期，待生命周期 API 稳定后接入同一组断言。
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
            "runAsyncTimer", "runSync", "runLater", "runTimer"
        )));
        assertEquals(2,
            Stream.of(MuzScheduler.class.getDeclaredMethods())
                .filter(method -> method.getName().equals("runTimer"))
                .count());
    }

    @Test
    void closeAndLateCallbackModelDocumentsTheNotYetStableLifecycleContract() {
        CloseAwareModel model = new CloseAwareModel();
        List<String> calls = new ArrayList<>();

        CloseAwareModel.Handle handle = model.schedule(20, () -> calls.add("late"));
        Runnable detachedCallback = model.detachNextCallback();
        model.close();
        model.close();
        detachedCallback.run();
        model.schedule(0, () -> calls.add("after-close"));
        model.drain();

        assertTrue(handle.cancelled);
        assertTrue(model.closed);
        assertTrue(calls.isEmpty(), "close 后不得执行排队或迟到回调");
        assertTrue(model.lastHandle.cancelled, "close 后新建任务应立即失效");
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
            return last;
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

        @Override
        public void cancel() {
            cancelled = true;
        }
    }

    /**
     * 测试侧先锁定 close 语义：幂等关闭、取消现有任务、关闭后新任务立即失效、迟到回调不再进入业务。
     * 当前 MuzScheduler 没有 close 方法，不能把这个预期冒充成生产行为。
     */
    private static final class CloseAwareModel {
        private final Queue<Runnable> callbacks = new ArrayDeque<>();
        private boolean closed;
        private Handle lastHandle;

        private Handle schedule(long delay, Runnable callback) {
            lastHandle = new Handle();
            if (closed) {
                lastHandle.cancel();
            } else {
                callbacks.add(() -> {
                    if (!lastHandle.cancelled && !closed) {
                        callback.run();
                    }
                });
            }
            return lastHandle;
        }

        private Runnable detachNextCallback() {
            Runnable callback = callbacks.poll();
            assert callback != null;
            return callback;
        }

        private void drain() {
            while (!callbacks.isEmpty()) {
                callbacks.remove().run();
            }
        }

        private void close() {
            closed = true;
            if (lastHandle != null) {
                lastHandle.cancel();
            }
            callbacks.clear();
        }

        private static final class Handle {
            private boolean cancelled;

            private void cancel() {
                cancelled = true;
            }
        }
    }
}
