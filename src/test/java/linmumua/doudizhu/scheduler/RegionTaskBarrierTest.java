package linmumua.doudizhu.scheduler;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;
import org.bukkit.Location;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.junit.jupiter.api.Test;

/** 多 region completion barrier 的最小行为契约。 */
class RegionTaskBarrierTest {
    private static final Location OWNER = new Location(null, 0, 64, 0);

    @Test
    void futureStaysPendingUntilEveryRegionActionFinishes() {
        CapturingBackend backend = new CapturingBackend();
        RegionTaskBarrier barrier = new RegionTaskBarrier(new MuzScheduler(backend), List.of(
            request("first", () -> { }), request("second", () -> { })
        ), 20);
        CompletableFuture<RegionTaskBarrier.Result> future = barrier.start();

        assertFalse(future.isDone());
        backend.regionTasks.get(0).fire();
        assertFalse(future.isDone());
        backend.regionTasks.get(1).fire();

        RegionTaskBarrier.Result result = future.join();
        assertEquals(RegionTaskBarrier.Status.COMPLETED, result.status());
        assertEquals(RegionTaskBarrier.RequestStatus.COMPLETED,
            result.request("first").status());
        assertEquals(RegionTaskBarrier.RequestStatus.COMPLETED,
            result.request("second").status());
    }

    @Test
    void synchronousRegionCallbacksCannotCompleteBeforeRegistrationEnds() {
        CapturingBackend backend = new CapturingBackend();
        backend.invokeRegionOnRegistration = true;
        AtomicReference<RegionTaskBarrier> reference = new AtomicReference<>();
        List<Boolean> futureStatesDuringRegistration = new ArrayList<>();
        RegionTaskBarrier barrier = new RegionTaskBarrier(new MuzScheduler(backend), List.of(
            request("first", () -> futureStatesDuringRegistration.add(reference.get().future().isDone())),
            request("second", () -> futureStatesDuringRegistration.add(reference.get().future().isDone()))
        ), 20);
        reference.set(barrier);
        CompletableFuture<RegionTaskBarrier.Result> future = barrier.start();

        assertEquals(List.of(false, false), futureStatesDuringRegistration);
        assertTrue(future.isDone());
    }

    @Test
    void actionFailureIsCapturedAndDoesNotHangTheBarrier() {
        CapturingBackend backend = new CapturingBackend();
        IllegalStateException failure = new IllegalStateException("region failed");
        RegionTaskBarrier barrier = new RegionTaskBarrier(new MuzScheduler(backend), List.of(
            request("bad", () -> { throw failure; }), request("good", () -> { })
        ), 20);
        CompletableFuture<RegionTaskBarrier.Result> future = barrier.start();

        backend.regionTasks.get(0).fire();
        assertFalse(future.isDone());
        backend.regionTasks.get(1).fire();

        RegionTaskBarrier.Result result = future.join();
        assertEquals(RegionTaskBarrier.Status.FAILED, result.status());
        assertSame(failure, result.firstFailure());
        assertEquals(RegionTaskBarrier.RequestStatus.FAILED, result.request("bad").status());
        assertSame(failure, result.request("bad").failure());
        assertEquals(RegionTaskBarrier.RequestStatus.COMPLETED, result.request("good").status());
    }

    @Test
    void regionRegistrationFailureIsAggregatedAndOtherRequestsStillDrain() {
        CapturingBackend backend = new CapturingBackend();
        backend.failRegionRegistrationAt = 1;
        RegionTaskBarrier barrier = new RegionTaskBarrier(new MuzScheduler(backend), List.of(
            request("registration", () -> { }), request("normal", () -> { })
        ), 20);
        CompletableFuture<RegionTaskBarrier.Result> future = barrier.start();

        assertFalse(future.isDone());
        backend.regionTasks.get(0).fire();
        RegionTaskBarrier.Result result = future.join();
        assertEquals(RegionTaskBarrier.Status.FAILED, result.status());
        assertEquals(RegionTaskBarrier.RequestStatus.FAILED,
            result.request("registration").status());
        assertEquals(RegionTaskBarrier.RequestStatus.COMPLETED,
            result.request("normal").status());
        assertEquals(1, backend.regionTasks.size());
    }

    @Test
    void timeoutCancelsUnfinishedRegionHandlesAndRejectsLateCallbacks() {
        CapturingBackend backend = new CapturingBackend();
        AtomicInteger calls = new AtomicInteger();
        RegionTaskBarrier barrier = new RegionTaskBarrier(new MuzScheduler(backend), List.of(
            request("done", calls::incrementAndGet), request("late", calls::incrementAndGet)
        ), 5);
        CompletableFuture<RegionTaskBarrier.Result> future = barrier.start();

        backend.regionTasks.get(0).fire();
        backend.globalTasks.get(0).fire();

        RegionTaskBarrier.Result result = future.join();
        assertEquals(RegionTaskBarrier.Status.TIMED_OUT, result.status());
        assertEquals(RegionTaskBarrier.RequestStatus.COMPLETED, result.request("done").status());
        assertEquals(RegionTaskBarrier.RequestStatus.TIMED_OUT, result.request("late").status());
        assertTrue(backend.regionTasks.get(1).cancelled);
        assertTrue(backend.globalTasks.get(0).cancelled);
        backend.regionTasks.get(1).forceFire();
        assertEquals(1, calls.get(), "超时后的迟到 region 回调不得执行 action");
        assertEquals(RegionTaskBarrier.Status.TIMED_OUT, future.join().status());
    }

    @Test
    void activeCancellationCancelsRegionAndTimeoutHandles() {
        CapturingBackend backend = new CapturingBackend();
        AtomicInteger calls = new AtomicInteger();
        RegionTaskBarrier barrier = new RegionTaskBarrier(new MuzScheduler(backend), List.of(
            request("one", calls::incrementAndGet), request("two", calls::incrementAndGet)
        ), 20);
        CompletableFuture<RegionTaskBarrier.Result> future = barrier.start();

        assertTrue(barrier.cancel());
        assertFalse(barrier.cancel());
        RegionTaskBarrier.Result result = future.join();
        assertEquals(RegionTaskBarrier.Status.CANCELLED, result.status());
        assertEquals(RegionTaskBarrier.RequestStatus.CANCELLED, result.request("one").status());
        assertEquals(RegionTaskBarrier.RequestStatus.CANCELLED, result.request("two").status());
        assertTrue(backend.regionTasks.stream().allMatch(task -> task.cancelled));
        assertTrue(backend.globalTasks.get(0).cancelled);
        backend.regionTasks.forEach(CapturingTask::forceFire);
        assertEquals(0, calls.get());
    }

    @Test
    void timeoutWaitsForAlreadyRunningRegionActionToReturn() {
        CapturingBackend backend = new CapturingBackend();
        AtomicReference<RegionTaskBarrier> reference = new AtomicReference<>();
        List<Boolean> completionStates = new ArrayList<>();
        RegionTaskBarrier barrier = new RegionTaskBarrier(new MuzScheduler(backend), List.of(
            request("running", () -> {
                backend.globalTasks.get(0).forceFire();
                completionStates.add(reference.get().future().isDone());
            })
        ), 5);
        reference.set(barrier);
        CompletableFuture<RegionTaskBarrier.Result> future = barrier.start();

        backend.regionTasks.get(0).fire();

        assertEquals(List.of(false), completionStates,
            "超时不能让 Future 先于已经开始的 region 世界写入完成");
        RegionTaskBarrier.Result result = future.join();
        assertEquals(RegionTaskBarrier.Status.TIMED_OUT, result.status());
        assertEquals(RegionTaskBarrier.RequestStatus.COMPLETED, result.request("running").status());
    }

    @Test
    void cancellationWaitsForAlreadyRunningRegionActionToReturn() {
        CapturingBackend backend = new CapturingBackend();
        AtomicReference<RegionTaskBarrier> reference = new AtomicReference<>();
        List<Boolean> completionStates = new ArrayList<>();
        RegionTaskBarrier barrier = new RegionTaskBarrier(new MuzScheduler(backend), List.of(
            request("running", () -> {
                assertTrue(reference.get().cancel());
                completionStates.add(reference.get().future().isDone());
            })
        ), 20);
        reference.set(barrier);
        CompletableFuture<RegionTaskBarrier.Result> future = barrier.start();

        backend.regionTasks.get(0).fire();

        assertEquals(List.of(false), completionStates,
            "取消不能把仍在执行的 region action 伪装为已经完成");
        RegionTaskBarrier.Result result = future.join();
        assertEquals(RegionTaskBarrier.Status.CANCELLED, result.status());
        assertEquals(RegionTaskBarrier.RequestStatus.COMPLETED, result.request("running").status());
    }

    @Test
    void lateForceFireCannotOverwriteCompletedResult() {
        CapturingBackend backend = new CapturingBackend();
        AtomicInteger calls = new AtomicInteger();
        RegionTaskBarrier barrier = new RegionTaskBarrier(new MuzScheduler(backend), List.of(
            request("only", calls::incrementAndGet)
        ), 20);
        CompletableFuture<RegionTaskBarrier.Result> future = barrier.start();
        CapturingTask region = backend.regionTasks.get(0);
        region.fire();
        RegionTaskBarrier.Result completed = future.join();

        region.forceFire();
        backend.globalTasks.get(0).forceFire();
        assertEquals(1, calls.get());
        assertSame(completed, future.join());
        assertEquals(RegionTaskBarrier.Status.COMPLETED, future.join().status());
    }

    @Test
    void emptyRequestsCompleteImmediatelyWithoutScheduling() {
        CapturingBackend backend = new CapturingBackend();
        RegionTaskBarrier barrier = new RegionTaskBarrier(new MuzScheduler(backend), List.of(), 0);
        CompletableFuture<RegionTaskBarrier.Result> future = barrier.start();

        assertTrue(future.isDone());
        assertEquals(RegionTaskBarrier.Status.COMPLETED, future.join().status());
        assertTrue(future.join().requests().isEmpty());
        assertTrue(backend.regionTasks.isEmpty());
        assertTrue(backend.globalTasks.isEmpty());
    }

    @Test
    void duplicateIdsAndNegativeTimeoutAreRejected() {
        assertThrows(IllegalArgumentException.class, () -> new RegionTaskBarrier(
            new MuzScheduler(new CapturingBackend()), List.of(
                request("same", () -> { }), request("same", () -> { })
            ), 1));
        assertThrows(IllegalArgumentException.class, () -> new RegionTaskBarrier(
            new MuzScheduler(new CapturingBackend()), List.of(request("one", () -> { })), -1));
    }

    private static RegionTaskBarrier.Request request(String id, Runnable action) {
        return new RegionTaskBarrier.Request(id, OWNER, action);
    }

    private static final class CapturingBackend implements SchedulerBackend {
        private final List<CapturingTask> regionTasks = new ArrayList<>();
        private final List<CapturingTask> globalTasks = new ArrayList<>();
        private int failRegionRegistrationAt = -1;
        private int regionRegistrationCount;
        private boolean invokeRegionOnRegistration;

        @Override
        public MuzScheduler.TaskHandle runGlobal(long delay, long period,
                                                  Consumer<MuzScheduler.TaskHandle> task) {
            CapturingTask captured = new CapturingTask("global", delay, period, task);
            globalTasks.add(captured);
            return captured;
        }

        @Override
        public MuzScheduler.TaskHandle runRegion(Location location, long delay, long period,
                                                  Consumer<MuzScheduler.TaskHandle> task) {
            if (++regionRegistrationCount == failRegionRegistrationAt) {
                throw new IllegalStateException("region registration failed");
            }
            CapturingTask captured = new CapturingTask("region", delay, period, task);
            regionTasks.add(captured);
            if (invokeRegionOnRegistration) {
                captured.fire();
            }
            return captured;
        }

        @Override
        public MuzScheduler.TaskHandle runEntity(Entity entity, long delay, long period,
                                                  Consumer<MuzScheduler.TaskHandle> task) {
            return new CapturingTask("entity", delay, period, task);
        }

        @Override
        public MuzScheduler.TaskHandle runPlayer(Player player, long delay, long period,
                                                  Consumer<MuzScheduler.TaskHandle> task) {
            return new CapturingTask("player", delay, period, task);
        }

        @Override
        public MuzScheduler.TaskHandle runAsync(long delay, long period,
                                                 Consumer<MuzScheduler.TaskHandle> task) {
            return new CapturingTask("async", delay, period, task);
        }
    }

    private static final class CapturingTask implements MuzScheduler.TaskHandle {
        private final String owner;
        private final long delay;
        private final long period;
        private final Consumer<MuzScheduler.TaskHandle> callback;
        private boolean cancelled;

        private CapturingTask(String owner, long delay, long period,
                              Consumer<MuzScheduler.TaskHandle> callback) {
            this.owner = owner;
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

        @Override
        public String ownerId() {
            return owner;
        }
    }
}
