package linmumua.doudizhu.storage;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.lang.reflect.Field;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.Test;

/**
 * 关闭阶段的数据库 flush 契约。
 *
 * <p>这里不启动 Bukkit/Folia，也不伪造真实 JDBC；只验证 DatabaseManager 会等待已经登记的
 * pending operation 完成，给 onDisable 在关闭 scheduler/backend 前收尾的窗口。
 */
class DatabasePendingWriteFlushTest {
    @Test
    void flushWaitsForPendingOperationBeforeReturning() throws Exception {
        DatabaseManager manager = new DatabaseManager(null);
        CompletableFuture<Void> pending = new CompletableFuture<>();
        pendingSet(manager).add(pending);
        AtomicBoolean returned = new AtomicBoolean();

        Thread waiter = new Thread(() -> {
            manager.flushPendingWrites(2_000L);
            returned.set(true);
        }, "muz-database-flush-test");
        waiter.start();

        for (int attempt = 0; attempt < 10_000 && !returned.get(); attempt++) {
            Thread.State state = waiter.getState();
            if (state == Thread.State.WAITING || state == Thread.State.TIMED_WAITING) {
                break;
            }
            Thread.onSpinWait();
        }
        assertFalse(returned.get(), "pending write 尚未完成时 flush 不应提前返回");

        pending.complete(null);
        waiter.join(2_000L);
        assertTrue(returned.get(), "pending write 完成后 flush 应返回");
    }

    @SuppressWarnings("unchecked")
    private static Set<CompletableFuture<?>> pendingSet(DatabaseManager manager) throws Exception {
        Field field = DatabaseManager.class.getDeclaredField("pendingOperations");
        field.setAccessible(true);
        return (Set<CompletableFuture<?>>) field.get(manager);
    }
}
