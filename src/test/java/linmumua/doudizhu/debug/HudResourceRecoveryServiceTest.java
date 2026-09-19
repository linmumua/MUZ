package linmumua.doudizhu.debug;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.lang.reflect.Field;
import java.nio.file.Files;
import java.nio.file.Path;
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
 * 守护启动、CraftEngine enable 和 /muz reload 共用同一份磁盘恢复委托。
 *
 * <p>这里不启动 Bukkit；资源桥缺失时只验证恢复服务 fail-closed，避免把「提交恢复任务」
 * 误报成「三层资源已验证」。
 */
class HudResourceRecoveryServiceTest {
    private final ExecutorService executor = Executors.newSingleThreadExecutor();
    private final ScheduledExecutorService timeout = Executors.newSingleThreadScheduledExecutor();

    @AfterEach
    void stopExecutors() {
        executor.shutdownNow();
        timeout.shutdownNow();
        HudWebApplyLease.clearForTests();
    }

    @Test
    void 三个恢复入口都提交完整submitReload并在桥接不可用时失败关闭() throws Exception {
        DoudizhuPlugin plugin = testPlugin(false);
        HudWebApplyLease lease = HudWebApplyLease.forKey("recovery-entrypoints");
        HudWebApplyCoordinator coordinator = new HudWebApplyCoordinator(
            plugin, new DebugHudConfigController(plugin), null, executor, timeout, lease, Runnable::run);
        HudResourceRecoveryService recovery = new HudResourceRecoveryService(plugin, coordinator);

        var bundle = recovery.onBundleExported("bundle").get(2, TimeUnit.SECONDS);
        var enabled = recovery.onCraftEngineEnabled("enable").get(2, TimeUnit.SECONDS);
        var reload = recovery.reloadFromDisk("reload").get(2, TimeUnit.SECONDS);

        assertFalse(bundle.ok(), "缺少 CraftEngine 时启动恢复必须 fail-closed");
        assertFalse(enabled.ok(), "CraftEngine enable 恢复失败时不能伪报成功");
        assertFalse(reload.ok(), "/muz reload 恢复失败时不能伪报成功");
        assertTrue(bundle.messages().stream().anyMatch(message -> message.contains("失败")),
            "恢复失败必须保留可诊断消息：" + bundle.messages());
        coordinator.close();
    }

    @Test
    void close后拒绝迟到恢复提交() throws Exception {
        DoudizhuPlugin plugin = testPlugin(false);
        HudWebApplyCoordinator coordinator = new HudWebApplyCoordinator(
            plugin, new DebugHudConfigController(plugin), null, executor, timeout,
            HudWebApplyLease.forKey("recovery-close"), Runnable::run);
        HudResourceRecoveryService recovery = new HudResourceRecoveryService(plugin, coordinator);
        recovery.close();

        var result = recovery.onBundleExported("late").get(2, TimeUnit.SECONDS);
        assertFalse(result.ok());
        assertTrue(result.messages().getFirst().contains("已关闭"),
            "关闭后必须明确拒绝恢复：" + result.messages());
        coordinator.close();
    }

    @Test
    void 插件正在关闭时不提交恢复任务() throws Exception {
        DoudizhuPlugin plugin = testPlugin(true);
        HudWebApplyCoordinator coordinator = new HudWebApplyCoordinator(
            plugin, new DebugHudConfigController(plugin), null, executor, timeout,
            HudWebApplyLease.forKey("recovery-shutdown"), Runnable::run);
        HudResourceRecoveryService recovery = new HudResourceRecoveryService(plugin, coordinator);

        var result = recovery.onCraftEngineEnabled("shutdown").get(2, TimeUnit.SECONDS);
        assertFalse(result.ok());
        assertTrue(result.messages().getFirst().contains("已关闭"),
            "插件停服期间必须拒绝恢复：" + result.messages());
        coordinator.close();
    }

    private static DoudizhuPlugin testPlugin(boolean shuttingDown) throws Exception {
        Unsafe unsafe = unsafe();
        DoudizhuPlugin plugin = (DoudizhuPlugin) unsafe.allocateInstance(DoudizhuPlugin.class);
        setObject(unsafe, plugin, "hudWebConfigLock", new Object());
        Path config = Files.createTempFile("muz-hud-recovery", ".yml");
        setObject(unsafe, plugin, "config", MuzYamlConfig.empty(config));
        trySetObject(unsafe, plugin, "logger", java.util.logging.Logger.getLogger("muz-recovery-test"));
        setBoolean(unsafe, plugin, "shuttingDown", shuttingDown);
        return plugin;
    }

    private static void setObject(Unsafe unsafe, Object target, String name, Object value) throws Exception {
        Field field = field(target.getClass(), name);
        unsafe.putObject(target, unsafe.objectFieldOffset(field), value);
    }

    private static void trySetObject(Unsafe unsafe, Object target, String name, Object value) throws Exception {
        try {
            setObject(unsafe, target, name, value);
        } catch (NoSuchFieldException ignored) {
            // 不同 Bukkit API 版本可能把 logger 放在不同层级；缺失时仍可验证 fail-closed。
        }
    }

    private static void setBoolean(Unsafe unsafe, Object target, String name, boolean value) throws Exception {
        Field field = field(target.getClass(), name);
        unsafe.putBoolean(target, unsafe.objectFieldOffset(field), value);
    }

    private static Field field(Class<?> type, String name) throws Exception {
        while (type != null) {
            try {
                return type.getDeclaredField(name);
            } catch (NoSuchFieldException ignored) {
                type = type.getSuperclass();
            }
        }
        throw new NoSuchFieldException(name);
    }

    private static Unsafe unsafe() throws Exception {
        Field field = Unsafe.class.getDeclaredField("theUnsafe");
        field.setAccessible(true);
        return (Unsafe) field.get(null);
    }
}
