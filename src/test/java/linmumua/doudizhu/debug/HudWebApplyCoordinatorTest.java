package linmumua.doudizhu.debug;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import linmumua.doudizhu.DoudizhuPlugin;
import linmumua.doudizhu.config.MuzYamlConfig;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import sun.misc.Unsafe;

/**
 * 通过真实协调器 monitor 路径验证 raw 完成后的执行器收尾。
 *
 * <p>使用 Unsafe 只绕过 Bukkit 插件构造器；本测试不启动服务端、不触碰 CraftEngine，验证的
 * 仍是生产 coordinator → taskGate → raw Future 连接，而不是复制一份闸门逻辑。
 */
class HudWebApplyCoordinatorTest {
    @AfterEach
    void clearLeases() {
        HudWebApplyLease.clearForTests();
    }

    @Test
    void 无自己任务但共享租约被其他实例占用时仍关闭自己的执行器() throws Exception {
        DoudizhuPlugin plugin = testPlugin();
        HudWebApplyLease lease = HudWebApplyLease.forKey("coordinator-shared-close");
        ExecutorService firstExecutor = Executors.newSingleThreadExecutor();
        ExecutorService secondExecutor = Executors.newSingleThreadExecutor();
        ScheduledExecutorService firstTimeout = Executors.newSingleThreadScheduledExecutor();
        ScheduledExecutorService secondTimeout = Executors.newSingleThreadScheduledExecutor();
        HudWebApplyCoordinator first = new HudWebApplyCoordinator(
            plugin, new DebugHudConfigController(plugin), null, firstExecutor, firstTimeout, lease, Runnable::run);
        HudWebApplyCoordinator second = new HudWebApplyCoordinator(
            plugin, new DebugHudConfigController(plugin), null, secondExecutor, secondTimeout, lease, Runnable::run);
        try {
            assertTrue(taskGate(first).tryAcquire() != null, "第一个实例应先占用共享租约");
            assertTrue(lease.isBusy());
            second.close();
            assertTrue(secondExecutor.isShutdown(), "没有自己任务时第二实例应立即关闭自己的执行器");
            assertTrue(taskGate(first).hasLocalTask(), "共享租约所属的第一个实例不能被第二实例关闭影响");
        } finally {
            first.close();
            second.close();
            firstExecutor.shutdownNow();
            secondExecutor.shutdownNow();
            firstTimeout.shutdownNow();
            secondTimeout.shutdownNow();
        }
    }

    @Test
    void 关闭后raw完成才关闭本协调器执行器() throws Exception {
        DoudizhuPlugin plugin = testPlugin();
        DebugHudConfigController controller = new DebugHudConfigController(plugin);
        ExecutorService executor = Executors.newSingleThreadExecutor();
        ScheduledExecutorService timeoutExecutor = Executors.newSingleThreadScheduledExecutor();
        HudWebApplyLease lease = HudWebApplyLease.forKey("coordinator-shutdown");
        HudWebApplyCoordinator coordinator = new HudWebApplyCoordinator(
            plugin, controller, null, executor, timeoutExecutor, lease, Runnable::run);
        try {
            HudWebApplyTaskGate gate = taskGate(coordinator);
            HudWebApplyTaskGate.Task<HudWebApplyCoordinator.ApplyResult> task = gate.tryAcquire();
            CompletableFuture<HudWebApplyCoordinator.ApplyResult> raw = new CompletableFuture<>();
            monitor(coordinator, task, raw);

            coordinator.close();
            assertFalse(executor.isShutdown(), "raw 未完成时不能关闭本协调器执行器");
            assertTrue(lease.isBusy(), "raw 未完成时共享租约必须保持占用");

            raw.complete(new HudWebApplyCoordinator.ApplyResult(
                false, controller.snapshot(), List.of(), List.of("late")));
            assertTrue(executor.awaitTermination(2, TimeUnit.SECONDS),
                "raw 释放租约后 coordinator 必须关闭自己的执行器");
            assertTrue(timeoutExecutor.isShutdown(), "关闭后的超时执行器也必须停止");
            assertFalse(lease.isBusy(), "raw 完成后共享租约必须释放");
        } finally {
            coordinator.close();
            executor.shutdownNow();
            timeoutExecutor.shutdownNow();
        }
    }

    private static void monitor(HudWebApplyCoordinator coordinator,
                                 HudWebApplyTaskGate.Task<HudWebApplyCoordinator.ApplyResult> task,
                                 CompletableFuture<HudWebApplyCoordinator.ApplyResult> raw) throws Exception {
        Method method = HudWebApplyCoordinator.class.getDeclaredMethod(
            "monitor", HudWebApplyTaskGate.Task.class, CompletableFuture.class);
        method.setAccessible(true);
        method.invoke(coordinator, task, raw);
    }

    private static HudWebApplyTaskGate taskGate(HudWebApplyCoordinator coordinator) throws Exception {
        Field field = HudWebApplyCoordinator.class.getDeclaredField("taskGate");
        field.setAccessible(true);
        return (HudWebApplyTaskGate) field.get(coordinator);
    }

    private static DoudizhuPlugin testPlugin() throws Exception {
        Unsafe unsafe = unsafe();
        DoudizhuPlugin plugin = (DoudizhuPlugin) unsafe.allocateInstance(DoudizhuPlugin.class);
        setObject(unsafe, plugin, "hudWebConfigLock", new Object());
        Path configFile = Files.createTempFile("muz-hud-coordinator-test", ".yml");
        setObject(unsafe, plugin, "config", MuzYamlConfig.empty(configFile));
        return plugin;
    }

    private static void setObject(Unsafe unsafe, Object target, String fieldName, Object value) throws Exception {
        Field field = target.getClass().getDeclaredField(fieldName);
        unsafe.putObject(target, unsafe.objectFieldOffset(field), value);
    }

    private static Unsafe unsafe() throws Exception {
        Field field = Unsafe.class.getDeclaredField("theUnsafe");
        field.setAccessible(true);
        return (Unsafe) field.get(null);
    }
}
