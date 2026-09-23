package linmumua.doudizhu.game;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotSame;
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
 * 牌桌周期任务区块生命周期的独立调度夹具。
 *
 * @author linmumua
 * @Desc 用可控 global/region 后端验证 owner 重绑和迟到 tick 取消
 * @date 2026-09-21
 */
class TablePeriodicTaskLifecycleFixtureTest {
    /**
     * 强引用夹具 Proxy world。
     *
     * <p>Paper 26.x 的 {@link Location} 只用 {@link java.lang.ref.WeakReference} 持有 world，
     * 若这里不额外持强引用，全量套件运行期间该 Proxy 可能被 GC 回收，使 {@code Location.getWorld()}
     * 抛 {@code IllegalArgumentException: World unloaded}——该用例曾在全量套件下偶发命中
     * {@code AnchorKey.of}。这里只是测试夹具自身的 GC 稳定性保障，不改变生产代码语义，也不影响断言。
     */
    private static final List<World> WORLD_STRONG_REFS = new ArrayList<>();

    @Test
    void unload后旧RegionTick不会执行Reload后的新Region任务接管() {
        CapturingBackend backend = new CapturingBackend();
        TablePeriodicTaskRegistry registry = new TablePeriodicTaskRegistry(new MuzScheduler(backend));
        Object owner = new Object();
        int[] calls = {0};

        MuzScheduler.TaskHandle stable = registry.register(
            "lifecycle", owner,
            new TablePeriodicTaskRegistry.PeriodicDescription(1L, 1L),
            location(0.0, 64.0, 0.0),
            () -> calls[0]++
        );
        CapturedTask oldRegion = backend.last();

        assertTrue(registry.rebind("lifecycle", owner, null), "模拟区块卸载后应切回 global owner");
        CapturedTask global = backend.last();
        assertEquals("global", global.lane);
        assertTrue(oldRegion.cancelled);

        assertTrue(registry.rebind("lifecycle", owner, location(32.0, 64.0, 48.0)), "模拟区块重新加载后应重新绑定 region");
        CapturedTask reloadedRegion = backend.last();
        assertEquals("region", reloadedRegion.lane);
        assertNotSame(oldRegion, reloadedRegion);

        oldRegion.forceFire();
        global.forceFire();
        assertEquals(0, calls[0], "已失效的旧 region/global 回调不能越过当前绑定代次");
        reloadedRegion.fire();
        assertEquals(1, calls[0]);
        assertFalse(stable.isCancelled());
    }

    @Test
    void 取消owner同时使周期tick和一次性迟到回调失效() {
        CapturingBackend backend = new CapturingBackend();
        TablePeriodicTaskRegistry registry = new TablePeriodicTaskRegistry(new MuzScheduler(backend));
        Object owner = new Object();
        int[] calls = {0};

        registry.register(
            "cancel-owner", owner,
            new TablePeriodicTaskRegistry.PeriodicDescription(1L, 5L),
            location(4.0, 70.0, 4.0),
            () -> calls[0]++
        );
        CapturedTask periodic = backend.last();
        registry.scheduleLater("cancel-owner", owner, location(4.0, 70.0, 4.0), 20L, () -> calls[0]++);
        CapturedTask oneShot = backend.last();

        assertTrue(registry.cancelOwner(owner));
        periodic.forceFire();
        oneShot.forceFire();

        assertEquals(0, calls[0]);
        assertTrue(periodic.cancelled);
        assertTrue(oneShot.cancelled);
        assertEquals(0, registry.size());
    }

    @Test
    void 同名新owner不会接收旧owner的迟到tick() {
        CapturingBackend backend = new CapturingBackend();
        TablePeriodicTaskRegistry registry = new TablePeriodicTaskRegistry(new MuzScheduler(backend));
        Object oldOwner = new Object();
        Object newOwner = new Object();
        int[] oldCalls = {0};
        int[] newCalls = {0};

        registry.register(
            "same-name", oldOwner,
            new TablePeriodicTaskRegistry.PeriodicDescription(1L, 1L),
            null,
            () -> oldCalls[0]++
        );
        CapturedTask oldTask = backend.last();
        assertTrue(registry.cancel("same-name", oldOwner));

        registry.register(
            "same-name", newOwner,
            new TablePeriodicTaskRegistry.PeriodicDescription(1L, 1L),
            location(80.0, 64.0, 80.0),
            () -> newCalls[0]++
        );
        CapturedTask newTask = backend.last();

        oldTask.forceFire();
        newTask.fire();
        assertEquals(0, oldCalls[0]);
        assertEquals(1, newCalls[0]);
    }

    private static Location location(double x, double y, double z) {
        World world = (World) Proxy.newProxyInstance(
            World.class.getClassLoader(),
            new Class<?>[]{World.class},
            (proxy, method, args) -> {
                if (method.getName().equals("getUID")) {
                    return UUID.fromString("00000000-0000-0000-0000-000000000021");
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
        // 持强引用：Location 只弱引用 world，套件运行中一旦被 GC 回收，getWorld() 会抛 World unloaded。
        WORLD_STRONG_REFS.add(world);
        return new Location(world, x, y, z);
    }

    private static final class CapturingBackend implements SchedulerBackend {
        private final List<CapturedTask> tasks = new ArrayList<>();

        private CapturedTask last() {
            return tasks.get(tasks.size() - 1);
        }

        private CapturedTask capture(
            String lane,
            Location location,
            long delay,
            long period,
            Consumer<MuzScheduler.TaskHandle> callback
        ) {
            CapturedTask task = new CapturedTask(lane, location, delay, period, callback);
            tasks.add(task);
            return task;
        }

        @Override
        public MuzScheduler.TaskHandle runGlobal(long delay, long period, Consumer<MuzScheduler.TaskHandle> task) {
            return capture("global", null, delay, period, task);
        }

        @Override
        public MuzScheduler.TaskHandle runRegion(
            Location location,
            long delay,
            long period,
            Consumer<MuzScheduler.TaskHandle> task
        ) {
            return capture("region", location, delay, period, task);
        }

        @Override
        public MuzScheduler.TaskHandle runEntity(
            org.bukkit.entity.Entity entity,
            long delay,
            long period,
            Consumer<MuzScheduler.TaskHandle> task
        ) {
            return capture("entity", null, delay, period, task);
        }

        @Override
        public MuzScheduler.TaskHandle runPlayer(
            org.bukkit.entity.Player player,
            long delay,
            long period,
            Consumer<MuzScheduler.TaskHandle> task
        ) {
            return capture("player", null, delay, period, task);
        }

        @Override
        public MuzScheduler.TaskHandle runAsync(long delay, long period, Consumer<MuzScheduler.TaskHandle> task) {
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

        private CapturedTask(
            String lane,
            Location location,
            long delay,
            long period,
            Consumer<MuzScheduler.TaskHandle> callback
        ) {
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

        @Override
        public void cancel() {
            if (cancelled) {
                return;
            }
            cancelled = true;
            for (Runnable listener : List.copyOf(terminationListeners)) {
                listener.run();
            }
            terminationListeners.clear();
        }

        @Override
        public void onTermination(Runnable listener) {
            if (cancelled) {
                listener.run();
            } else {
                terminationListeners.add(listener);
            }
        }

        @Override
        public boolean isCancelled() {
            return cancelled;
        }

        @Override
        public String ownerId() {
            return lane;
        }
    }
}
