package linmumua.doudizhu.scheduler;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;
import org.bukkit.Location;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.junit.jupiter.api.Test;

/** 区域重绑定与调度代次必须形成可控的迟到回调屏障。 */
class RegionBarrierContractTest {
    private static final Path REGISTRY = Path.of(
        "src/main/java/linmumua/doudizhu/game/TablePeriodicTaskRegistry.java");

    @Test
    void lateRegionCallbackCannotCrossGenerationBarrier() {
        CaptureBackend backend = new CaptureBackend();
        MuzScheduler scheduler = new MuzScheduler(backend);
        List<String> calls = new ArrayList<>();

        MuzScheduler.TaskHandle oldHandle = scheduler.runRegion(null, () -> calls.add("old"));
        CapturedTask oldTask = backend.last;
        long nextGeneration = scheduler.newGeneration();
        MuzScheduler.TaskHandle newHandle = scheduler.runRegion(null, () -> calls.add("new"));
        CapturedTask newTask = backend.last;

        oldTask.forceFire();
        newTask.fire();

        assertEquals(1L, nextGeneration);
        assertTrue(oldHandle.isCancelled(), "推进代次必须取消旧 region 句柄");
        assertFalse(newHandle.isCancelled(), "新 region 句柄不得继承旧代次取消状态");
        assertEquals(List.of("new"), calls,
            "旧 region 回调即使迟到也不能穿过代次屏障写回新状态");
    }

    @Test
    void rebindInvalidatesOldGenerationBeforeCancelAndRunsBusinessOutsideLock() throws IOException {
        String source = Files.readString(REGISTRY);
        int bindStart = source.indexOf("private boolean bind(Entry entry, Location anchor)");
        assertTrue(bindStart >= 0, "周期注册表必须保留统一重绑定入口");
        int bindEnd = source.indexOf("\n    private void clearFailedBinding", bindStart);
        assertTrue(bindEnd > bindStart, "重绑定入口必须有完整方法体");
        String bind = source.substring(bindStart, bindEnd);

        int invalidate = bind.indexOf("generation = ++entry.bindingGeneration;");
        int cancel = bind.indexOf("cancelQuietly(oldTask);");
        assertTrue(invalidate >= 0 && cancel > invalidate,
            "重绑定必须先推进 binding generation，再取消旧 region 任务");

        int dispatchStart = source.indexOf("private void dispatch(Entry entry, long generation)");
        assertTrue(dispatchStart >= 0, "周期注册表必须保留统一回调入口");
        int dispatchEnd = source.indexOf("\n    private List<MuzScheduler.TaskHandle> invalidateOneShots", dispatchStart);
        assertTrue(dispatchEnd > dispatchStart, "回调入口必须有完整方法体");
        String dispatch = source.substring(dispatchStart, dispatchEnd);
        int callbackRead = dispatch.indexOf("callback = entry.callback;");
        int lockExit = dispatch.indexOf("\n        }\n        // 严禁在共享注册表锁内执行牌桌业务。", callbackRead);
        int callbackRun = dispatch.indexOf("callback.run();");
        assertTrue(callbackRead >= 0 && lockExit > callbackRead && callbackRun > lockExit,
            "区域回调必须先在锁内取快照，再在锁外执行业务，避免 barrier 互锁");
    }

    private static final class CaptureBackend implements SchedulerBackend {
        private CapturedTask last;

        @Override
        public String ownerId() {
            return "capture-region";
        }

        @Override
        public MuzScheduler.TaskHandle runGlobal(long delay, long period,
                                                  Consumer<MuzScheduler.TaskHandle> task) {
            return capture("global", task);
        }

        @Override
        public MuzScheduler.TaskHandle runRegion(Location location, long delay, long period,
                                                  Consumer<MuzScheduler.TaskHandle> task) {
            return capture("region", task);
        }

        @Override
        public MuzScheduler.TaskHandle runEntity(Entity entity, long delay, long period,
                                                  Consumer<MuzScheduler.TaskHandle> task) {
            return capture("entity", task);
        }

        @Override
        public MuzScheduler.TaskHandle runPlayer(Player player, long delay, long period,
                                                  Consumer<MuzScheduler.TaskHandle> task) {
            return capture("player", task);
        }

        @Override
        public MuzScheduler.TaskHandle runAsync(long delay, long period,
                                                 Consumer<MuzScheduler.TaskHandle> task) {
            return capture("async", task);
        }

        private CapturedTask capture(String owner, Consumer<MuzScheduler.TaskHandle> callback) {
            last = new CapturedTask(owner, callback);
            return last;
        }
    }

    private static final class CapturedTask implements MuzScheduler.TaskHandle {
        private final String owner;
        private final Consumer<MuzScheduler.TaskHandle> callback;
        private boolean cancelled;

        private CapturedTask(String owner, Consumer<MuzScheduler.TaskHandle> callback) {
            this.owner = owner;
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
