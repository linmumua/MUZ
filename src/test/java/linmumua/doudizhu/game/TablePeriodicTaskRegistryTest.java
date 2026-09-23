package linmumua.doudizhu.game;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.function.Consumer;
import linmumua.doudizhu.scheduler.MuzScheduler;
import linmumua.doudizhu.scheduler.SchedulerBackend;
import org.bukkit.Location;
import org.bukkit.World;
import org.junit.jupiter.api.Test;

/**
 * @author linmumua
 * @Desc 牌桌周期任务 global/region 重绑定行为测试
 * @date 2026-09-21
 */
class TablePeriodicTaskRegistryTest {
    private static final UUID TEST_WORLD_UID = UUID.fromString("00000000-0000-0000-0000-000000000001");

    @Test
    void 未放置桌从global切到region且迟到旧回调失效() {
        CaptureBackend backend = new CaptureBackend();
        TablePeriodicTaskRegistry registry = new TablePeriodicTaskRegistry(new MuzScheduler(backend));
        Object table = new Object();
        int[] calls = {0};

        MuzScheduler.TaskHandle stable = registry.register(
            "demo", table, new TablePeriodicTaskRegistry.PeriodicDescription(1, 10), null,
            () -> calls[0]++
        );
        CapturedTask global = backend.last();
        global.fire();
        assertEquals(1, calls[0]);

        assertTrue(registry.rebind("demo", table, location(10.5, 64.0, 20.5)));
        CapturedTask region = backend.last();
        assertEquals("global", global.lane);
        assertEquals("region", region.lane);
        assertTrue(global.cancelled);
        assertEquals(10.5, region.location.getX());
        assertEquals(64.0, region.location.getY());
        assertEquals(20.5, region.location.getZ());
        assertEquals(location(10.5, 64.0, 20.5).getWorld().getUID(), region.location.getWorld().getUID());

        global.forceFire();
        region.fire();
        assertEquals(2, calls[0]);
        assertTrue(stable.generation() > global.generation());
    }

    @Test
    void 一次性owner任务按锚点选择lane且注销后旧回调失效() {
        CaptureBackend backend = new CaptureBackend();
        TablePeriodicTaskRegistry registry = new TablePeriodicTaskRegistry(new MuzScheduler(backend));
        Object table = new Object();
        int[] calls = {0};
        registry.register(
            "owner", table, new TablePeriodicTaskRegistry.PeriodicDescription(1, 10), null, () -> { }
        );

        MuzScheduler.TaskHandle globalHandle = registry.scheduleLater("owner", table, null, 5L, () -> calls[0]++);
        CapturedTask global = backend.last();
        assertEquals("global", global.lane);
        assertEquals(5L, global.delay);

        MuzScheduler.TaskHandle regionHandle = registry.scheduleLater(
            "owner", table, location(12.5, 64.0, -3.5), 7L, () -> calls[0]++
        );
        CapturedTask region = backend.last();
        assertEquals("region", region.lane);
        assertEquals(7L, region.delay);
        assertFalse(globalHandle.isCancelled());
        assertFalse(regionHandle.isCancelled());

        assertTrue(registry.cancel("owner", table));
        global.forceFire();
        region.forceFire();
        assertEquals(0, calls[0], "owner 注销后一次性旧回调不能写回牌桌");
        assertTrue(globalHandle.isCancelled());
        assertTrue(regionHandle.isCancelled());
    }

    @Test
    void 区块卸载或重绑定会先失效旧一次性任务且新任务走目标lane() {
        CaptureBackend backend = new CaptureBackend();
        TablePeriodicTaskRegistry registry = new TablePeriodicTaskRegistry(new MuzScheduler(backend));
        Object table = new Object();
        int[] calls = {0};
        Location first = location(10.0, 64.0, 20.0);
        Location second = location(30.0, 64.0, 40.0);

        registry.register("owner", table, new TablePeriodicTaskRegistry.PeriodicDescription(1, 10), first, () -> { });
        MuzScheduler.TaskHandle oldHandle = registry.scheduleLater("owner", table, first, 5L, () -> calls[0]++);
        CapturedTask oldTask = backend.last();

        assertTrue(registry.rebind("owner", table, null));
        assertTrue(oldHandle.isCancelled());
        oldTask.forceFire();
        assertEquals(0, calls[0], "旧 region 一次性任务即使迟到 forceFire 也不能执行");

        MuzScheduler.TaskHandle globalHandle = registry.scheduleLater("owner", table, null, 5L, () -> calls[0]++);
        CapturedTask globalTask = backend.last();
        assertEquals("global", globalTask.lane);
        globalTask.fire();
        globalTask.forceFire();
        assertEquals(1, calls[0], "重绑定后的新一次性任务只能执行一次");
        assertTrue(globalHandle.isCancelled(), "一次性任务执行后应失效");

        assertTrue(registry.rebind("owner", table, second));
        MuzScheduler.TaskHandle regionHandle = registry.scheduleLater("owner", table, second, 5L, () -> calls[0]++);
        CapturedTask regionTask = backend.last();
        assertEquals("region", regionTask.lane);
        regionTask.fire();
        regionTask.forceFire();
        assertEquals(2, calls[0], "重绑定到目标 region 后新任务只执行一次");
        assertTrue(regionHandle.isCancelled());
    }

    @Test
    void 同锚点幂等重绑定不得取消一次性任务() {
        CaptureBackend backend = new CaptureBackend();
        TablePeriodicTaskRegistry registry = new TablePeriodicTaskRegistry(new MuzScheduler(backend));
        Object table = new Object();
        int[] calls = {0};
        Location anchor = location(10.0, 64.0, 20.0);

        registry.register("owner", table, new TablePeriodicTaskRegistry.PeriodicDescription(1, 10), anchor, () -> { });
        MuzScheduler.TaskHandle oneShotHandle = registry.scheduleLater(
            "owner", table, anchor.clone(), 5L, () -> calls[0]++
        );
        CapturedTask oneShot = backend.last();
        assertTrue(registry.rebind("owner", table, anchor.clone()));
        assertFalse(oneShotHandle.isCancelled(), "同一活跃 region 绑定必须保持幂等");
        oneShot.fire();
        oneShot.forceFire();
        assertEquals(1, calls[0]);
        assertTrue(oneShotHandle.isCancelled());
    }

    @Test
    void mark为global后新一次性任务走global并清除旧region任务() {
        CaptureBackend backend = new CaptureBackend();
        TablePeriodicTaskRegistry registry = new TablePeriodicTaskRegistry(new MuzScheduler(backend));
        Object table = new Object();
        int[] calls = {0};
        Location anchor = location(10.0, 64.0, 20.0);

        registry.register("owner", table, new TablePeriodicTaskRegistry.PeriodicDescription(1, 10), anchor, () -> { });
        MuzScheduler.TaskHandle oldHandle = registry.scheduleLater("owner", table, anchor, 5L, () -> calls[0]++);
        CapturedTask oldTask = backend.last();
        assertTrue(registry.rebind("owner", table, null));
        assertTrue(oldHandle.isCancelled());
        oldTask.forceFire();

        MuzScheduler.TaskHandle globalHandle = registry.scheduleLater("owner", table, null, 5L, () -> calls[0]++);
        CapturedTask globalTask = backend.last();
        assertEquals("global", globalTask.lane);
        globalTask.fire();
        assertEquals(1, calls[0]);
        assertTrue(globalHandle.isCancelled());
    }

    @Test
    void 同一owner的多个周期任务可一起重绑定并在注销时全部失效() {
        CaptureBackend backend = new CaptureBackend();
        TablePeriodicTaskRegistry registry = new TablePeriodicTaskRegistry(new MuzScheduler(backend));
        Object table = new Object();
        registry.register("owner-a", table, new TablePeriodicTaskRegistry.PeriodicDescription(0, 5), null, () -> { });
        CapturedTask first = backend.last();
        registry.register("owner-b", table, new TablePeriodicTaskRegistry.PeriodicDescription(0, 5), null, () -> { });
        CapturedTask second = backend.last();

        assertEquals(2, registry.rebindOwner(table, location(40.0, 70.0, 80.0)));
        CapturedTask reboundFirst = backend.tasks.get(2);
        CapturedTask reboundSecond = backend.tasks.get(3);
        assertEquals("region", reboundFirst.lane);
        assertEquals("region", reboundSecond.lane);
        assertTrue(first.cancelled);
        assertTrue(second.cancelled);
        assertTrue(registry.cancelOwner(table));
        assertTrue(reboundFirst.cancelled);
        assertTrue(reboundSecond.cancelled);
    }

    @Test
    void 同位置重复通知幂等且移动与恢复逐次替换绑定() {
        CaptureBackend backend = new CaptureBackend();
        TablePeriodicTaskRegistry registry = new TablePeriodicTaskRegistry(new MuzScheduler(backend));
        Object table = new Object();
        Location first = location(1.0, 2.0, 3.0);
        Location moved = location(101.0, 2.0, 3.0);
        MuzScheduler.TaskHandle stable = registry.register(
            "demo", table, new TablePeriodicTaskRegistry.PeriodicDescription(1, 10), first, () -> { }
        );
        CapturedTask initial = backend.last();

        assertTrue(registry.rebind("demo", table, first.clone()));
        assertSame(initial, backend.last());
        assertTrue(registry.rebind("demo", table, moved));
        CapturedTask movedTask = backend.last();
        assertNotSame(initial, movedTask);
        assertTrue(initial.cancelled);
        assertTrue(registry.rebind("demo", table, null));
        CapturedTask restored = backend.last();
        assertEquals("global", restored.lane);
        assertTrue(movedTask.cancelled);
        assertFalse(stable.isCancelled());
    }

    @Test
    void 重绑定注册失败后保留锚点并允许同锚点重试() {
        CaptureBackend backend = new CaptureBackend();
        TablePeriodicTaskRegistry registry = new TablePeriodicTaskRegistry(new MuzScheduler(backend));
        Object table = new Object();
        Location anchor = location(5.0, 6.0, 7.0);
        MuzScheduler.TaskHandle stable = registry.register(
            "retry", table, new TablePeriodicTaskRegistry.PeriodicDescription(1, 10), anchor, () -> { }
        );
        CapturedTask oldTask = backend.last();
        oldTask.terminate();
        backend.throwOnSchedule = true;

        // world unload 后旧 Location 可能已经失效；重试必须重新构造同锚点的合法 Location。
        assertThrows(RuntimeException.class, () -> registry.rebind("retry", table, sameAnchor()));
        oldTask.forceFire();
        assertFalse(stable.isCancelled());
        assertEquals(1, registry.size());

        backend.throwOnSchedule = false;
        assertTrue(registry.rebind("retry", table, sameAnchor()));
        CapturedTask retriedTask = backend.last();
        assertEquals("region", retriedTask.lane);
        assertEquals(5.0, retriedTask.location.getX());
        assertEquals(6.0, retriedTask.location.getY());
        assertEquals(7.0, retriedTask.location.getZ());
        assertEquals(TEST_WORLD_UID, retriedTask.location.getWorld().getUID());
        assertFalse(stable.isCancelled());
    }

    @Test
    void 后端返回已取消句柄不会被接受且稳定句柄可重试() {
        CaptureBackend backend = new CaptureBackend();
        TablePeriodicTaskRegistry registry = new TablePeriodicTaskRegistry(new MuzScheduler(backend));
        Object table = new Object();
        Location anchor = location(8.0, 9.0, 10.0);
        MuzScheduler.TaskHandle stable = registry.register(
            "closed", table, new TablePeriodicTaskRegistry.PeriodicDescription(1, 10), anchor, () -> { }
        );
        backend.last().terminate();
        backend.returnCancelled = true;

        assertThrows(IllegalStateException.class, () -> registry.rebind("closed", table, location(8.0, 9.0, 10.0)));
        assertFalse(stable.isCancelled());
        assertEquals(1, registry.size());

        backend.returnCancelled = false;
        assertTrue(registry.rebind("closed", table, location(8.0, 9.0, 10.0)));
        assertFalse(stable.isCancelled());
    }

    @Test
    void 同名新桌不接收旧桌迟到回调且旧句柄不能复活() {
        CaptureBackend backend = new CaptureBackend();
        TablePeriodicTaskRegistry registry = new TablePeriodicTaskRegistry(new MuzScheduler(backend));
        Object oldTable = new Object();
        Object newTable = new Object();
        int[] calls = {0};
        MuzScheduler.TaskHandle oldHandle = registry.register(
            "same", oldTable, new TablePeriodicTaskRegistry.PeriodicDescription(1, 10), null,
            () -> calls[0]++
        );
        CapturedTask oldTask = backend.last();
        assertTrue(registry.cancel("same", oldTable));
        oldTask.forceFire();
        assertEquals(0, calls[0]);
        assertTrue(oldHandle.isCancelled());

        MuzScheduler.TaskHandle newHandle = registry.register(
            "same", newTable, new TablePeriodicTaskRegistry.PeriodicDescription(1, 10), null,
            () -> calls[0]++
        );
        oldTask.forceFire();
        backend.last().fire();
        assertEquals(1, calls[0]);
        assertFalse(newHandle.isCancelled());
        assertFalse(registry.rebind("same", oldTable, null));
    }

    @Test
    void 取消幂等并吞掉后端取消异常且业务回调可在锁外取消自身() {
        CaptureBackend backend = new CaptureBackend();
        TablePeriodicTaskRegistry registry = new TablePeriodicTaskRegistry(new MuzScheduler(backend));
        Object table = new Object();
        MuzScheduler.TaskHandle[] handle = new MuzScheduler.TaskHandle[1];
        handle[0] = registry.register(
            "demo", table, new TablePeriodicTaskRegistry.PeriodicDescription(1, 10), null,
            () -> handle[0].cancel()
        );
        CapturedTask task = backend.last();
        task.throwOnCancel = true;
        task.fire();
        assertTrue(handle[0].isCancelled());
        handle[0].cancel();
        assertEquals(0, registry.size());
    }

    @Test
    void 注册失败不留下条目或无效绑定() {
        CaptureBackend backend = new CaptureBackend();
        backend.throwOnSchedule = true;
        TablePeriodicTaskRegistry registry = new TablePeriodicTaskRegistry(new MuzScheduler(backend));
        assertThrows(RuntimeException.class, () -> registry.register(
            "broken", new Object(), new TablePeriodicTaskRegistry.PeriodicDescription(1, 10), null, () -> { }
        ));
        assertEquals(0, registry.size());
    }

    @Test
    void 取消全部任务会在锁外完成且不会执行迟到回调() {
        CaptureBackend backend = new CaptureBackend();
        TablePeriodicTaskRegistry registry = new TablePeriodicTaskRegistry(new MuzScheduler(backend));
        int[] calls = {0};
        registry.register("a", new Object(), new TablePeriodicTaskRegistry.PeriodicDescription(1, 10), null,
            () -> calls[0]++);
        CapturedTask first = backend.last();
        registry.register("b", new Object(), new TablePeriodicTaskRegistry.PeriodicDescription(1, 10), null,
            () -> calls[0]++);
        CapturedTask second = backend.last();
        registry.cancelAll();
        first.forceFire();
        second.forceFire();
        assertTrue(first.cancelled);
        assertTrue(second.cancelled);
        assertEquals(0, calls[0]);
        assertEquals(0, registry.size());
    }

    private static Location sameAnchor() {
        return location(5.0, 6.0, 7.0);
    }

    private static Location location(double x, double y, double z) {
        World world = (World) Proxy.newProxyInstance(
            World.class.getClassLoader(), new Class<?>[]{World.class},
            (proxy, method, args) -> {
                if (method.getName().equals("getUID")) {
                    return TEST_WORLD_UID;
                }
                if (method.getReturnType() == boolean.class) {
                    return false;
                }
                if (method.getReturnType() == int.class) {
                    return 0;
                }
                if (method.getReturnType() == long.class) {
                    return 0L;
                }
                if (method.getReturnType() == double.class) {
                    return 0.0D;
                }
                return null;
            }
        );
        return new Location(world, x, y, z);
    }

    private static final class CaptureBackend implements SchedulerBackend {
        private final List<CapturedTask> tasks = new ArrayList<>();
        private boolean throwOnSchedule;
        private boolean returnCancelled;

        private CapturedTask last() {
            return tasks.get(tasks.size() - 1);
        }

        private CapturedTask capture(String lane, Location location, long delay, long period,
                                     Consumer<MuzScheduler.TaskHandle> callback) {
            if (throwOnSchedule) {
                throw new IllegalStateException("模拟注册失败");
            }
            CapturedTask task = new CapturedTask(lane, location, delay, period, callback);
            tasks.add(task);
            if (returnCancelled) {
                task.terminate();
            }
            return task;
        }

        @Override
        public MuzScheduler.TaskHandle runGlobal(long delay, long period, Consumer<MuzScheduler.TaskHandle> task) {
            return capture("global", null, delay, period, task);
        }

        @Override
        public MuzScheduler.TaskHandle runRegion(Location location, long delay, long period,
                                                  Consumer<MuzScheduler.TaskHandle> task) {
            return capture("region", location, delay, period, task);
        }

        @Override
        public MuzScheduler.TaskHandle runEntity(org.bukkit.entity.Entity entity, long delay, long period,
                                                  Consumer<MuzScheduler.TaskHandle> task) {
            return capture("entity", null, delay, period, task);
        }

        @Override
        public MuzScheduler.TaskHandle runPlayer(org.bukkit.entity.Player player, long delay, long period,
                                                  Consumer<MuzScheduler.TaskHandle> task) {
            return capture("player", null, delay, period, task);
        }

        @Override
        public MuzScheduler.TaskHandle runAsync(long delay, long period,
                                                 Consumer<MuzScheduler.TaskHandle> task) {
            return capture("async", null, delay, period, task);
        }
    }

    private static final class CapturedTask implements MuzScheduler.TaskHandle {
        private final String lane;
        private final Location location;
        private final long delay;
        private final long period;
        private final Consumer<MuzScheduler.TaskHandle> callback;
        private final List<Runnable> terminationListeners = new ArrayList<>();
        private boolean cancelled;
        private boolean terminated;
        private boolean throwOnCancel;

        private CapturedTask(String lane, Location location, long delay, long period,
                             Consumer<MuzScheduler.TaskHandle> callback) {
            this.lane = lane;
            this.location = location;
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

        private void terminate() {
            if (terminated) {
                return;
            }
            terminated = true;
            cancelled = true;
            for (Runnable listener : List.copyOf(terminationListeners)) {
                listener.run();
            }
            terminationListeners.clear();
        }

        @Override
        public void cancel() {
            if (throwOnCancel) {
                throw new IllegalStateException("模拟取消失败");
            }
            terminate();
        }

        @Override
        public void onTermination(Runnable listener) {
            if (terminated) {
                listener.run();
            } else {
                terminationListeners.add(listener);
            }
        }

        @Override
        public boolean isCancelled() {
            return cancelled || terminated;
        }

        @Override
        public String ownerId() {
            return lane;
        }
    }
}
