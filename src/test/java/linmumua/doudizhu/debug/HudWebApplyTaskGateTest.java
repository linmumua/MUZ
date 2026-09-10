package linmumua.doudizhu.debug;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

/**
 * 直接验证 Debug Web 任务闸门的生产租约语义：超时只废弃对外结果，raw Future 仍继续完成，
 * 共享租约直到 raw 完成才释放；关闭后的迟到结果不能重新发布。
 */
class HudWebApplyTaskGateTest {
    private final ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor();

    @AfterEach
    void stopScheduler() {
        scheduler.shutdownNow();
        HudWebApplyLease.clearForTests();
    }

    @Test
    void 超时不取消raw且在raw完成前保留共享租约() throws Exception {
        HudWebApplyLease lease = HudWebApplyLease.forKey("timeout");
        HudWebApplyTaskGate gate = new HudWebApplyTaskGate(lease, scheduler, 0);
        HudWebApplyTaskGate.Task<String> task = gate.tryAcquire();
        CompletableFuture<String> raw = new CompletableFuture<>();

        CompletableFuture<String> exposed = gate.monitor(task, raw,
            () -> "expired", failure -> "failed");

        assertTrue(exposed.get(2, TimeUnit.SECONDS).equals("expired"));
        assertFalse(raw.isCancelled(), "超时不得取消原始 Future");
        assertTrue(lease.isBusy(), "raw 完成前共享租约必须保持占用");
        assertFalse(gate.isActive(task));

        raw.complete("late");
        assertTrue(raw.isDone());
        assertFalse(lease.isBusy(), "raw 真正完成后才释放共享租约");
        assertTrue(exposed.isDone());
        assertTrue(exposed.get().equals("expired"), "迟到 raw 结果不得替换已失效的对外结果");
    }

    @Test
    void 关闭后迟到raw不发布且不会取消原始Future() throws Exception {
        HudWebApplyLease lease = HudWebApplyLease.forKey("close");
        HudWebApplyTaskGate gate = new HudWebApplyTaskGate(lease, scheduler, 60);
        HudWebApplyTaskGate.Task<String> task = gate.tryAcquire();
        CompletableFuture<String> raw = new CompletableFuture<>();
        CompletableFuture<String> exposed = gate.monitor(task, raw,
            () -> "closed", failure -> "failed");

        gate.close();
        assertTrue(exposed.get(2, TimeUnit.SECONDS).equals("closed"));
        assertFalse(raw.isCancelled());
        assertTrue(lease.isBusy(), "关闭只废弃结果，不能提前释放 raw 的共享租约");

        raw.complete("late");
        assertFalse(lease.isBusy());
        assertTrue(exposed.get().equals("closed"));
    }

    @Test
    void 共享租约阻止新实例重叠而本实例关闭不受其他实例影响() {
        HudWebApplyLease lease = HudWebApplyLease.forKey("shared");
        HudWebApplyTaskGate first = new HudWebApplyTaskGate(lease, scheduler, 60);
        HudWebApplyTaskGate second = new HudWebApplyTaskGate(lease, scheduler, 60);
        HudWebApplyTaskGate.Task<String> task = first.tryAcquire();

        assertNotNull(task);
        assertNull(second.tryAcquire());
        second.close();
        assertTrue(second.isClosed());
        assertTrue(first.hasLocalTask());
        first.close();
        assertTrue(lease.isBusy(), "first 的 raw 尚未完成，关闭不应释放共享租约");
    }

    @Test
    void raw失败仍释放租约并把失败传给对外结果() throws Exception {
        HudWebApplyLease lease = HudWebApplyLease.forKey("failure");
        HudWebApplyTaskGate gate = new HudWebApplyTaskGate(lease, scheduler, 60);
        HudWebApplyTaskGate.Task<String> task = gate.tryAcquire();
        CompletableFuture<String> raw = new CompletableFuture<>();
        CompletableFuture<String> exposed = gate.monitor(task, raw,
            () -> "inactive", failure -> "mapped:" + failure.getMessage());

        raw.completeExceptionally(new IllegalStateException("raw failed"));
        assertTrue(exposed.get(2, TimeUnit.SECONDS).equals("mapped:raw failed"));
        assertFalse(lease.isBusy(), "raw 失败同样必须释放共享租约");
    }

    @Test
    void raw收尾回调发生在租约和本地任务释放之后() throws Exception {
        HudWebApplyLease lease = HudWebApplyLease.forKey("finished-callback");
        HudWebApplyTaskGate gate = new HudWebApplyTaskGate(lease, scheduler, 60);
        HudWebApplyTaskGate.Task<String> task = gate.tryAcquire();
        CompletableFuture<String> raw = new CompletableFuture<>();
        AtomicReference<Throwable> callbackFailure = new AtomicReference<>();

        gate.monitor(task, raw, () -> "inactive", failure -> "failed", () -> {
            try {
                assertFalse(lease.isBusy(), "收尾回调不能早于共享租约释放");
                assertFalse(gate.hasLocalTask(), "收尾回调不能早于本地 raw 清理");
            } catch (Throwable throwable) {
                callbackFailure.set(throwable);
            }
        });

        raw.complete("done");
        assertNull(callbackFailure.get(), "收尾回调不得在租约释放前执行");
        assertFalse(lease.isBusy());
    }

    @Test
    void raw已完成时不注册超时定时器() throws Exception {
        HudWebApplyLease lease = HudWebApplyLease.forKey("already-done");
        HudWebApplyTaskGate gate = new HudWebApplyTaskGate(lease, scheduler, 60);
        HudWebApplyTaskGate.Task<String> task = gate.tryAcquire();
        CompletableFuture<String> raw = CompletableFuture.completedFuture("done");

        CompletableFuture<String> exposed = gate.monitor(task, raw,
            () -> "inactive", failure -> "failed");
        assertEquals("done", exposed.get(2, TimeUnit.SECONDS));
        assertFalse(lease.isBusy(), "已完成 raw 不应留下租约");
    }

    @Test
    void 运行阻塞回调时gate关闭不会被锁住() throws Exception {
        HudWebApplyLease lease = HudWebApplyLease.forKey("io-outside-lock");
        HudWebApplyTaskGate gate = new HudWebApplyTaskGate(lease, scheduler, 60);
        HudWebApplyTaskGate.Task<String> task = gate.tryAcquire();
        ExecutorService worker = Executors.newSingleThreadExecutor();
        CountDownLatch entered = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        try {
            CompletableFuture<String> running = CompletableFuture.supplyAsync(() -> gate.runIfActive(
                task,
                () -> {
                    entered.countDown();
                    try {
                        assertTrue(release.await(2, TimeUnit.SECONDS));
                    } catch (InterruptedException exception) {
                        Thread.currentThread().interrupt();
                        throw new AssertionError(exception);
                    }
                    return "active";
                },
                () -> "inactive"), worker);
            assertTrue(entered.await(2, TimeUnit.SECONDS));
            gate.close();
            assertTrue(gate.isClosed(), "关闭不应等待阻塞回调释放");
            release.countDown();
            assertEquals("active", running.get(2, TimeUnit.SECONDS));
        } finally {
            release.countDown();
            worker.shutdownNow();
        }
    }
}
