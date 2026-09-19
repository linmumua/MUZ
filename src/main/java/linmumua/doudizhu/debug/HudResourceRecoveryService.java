package linmumua.doudizhu.debug;

import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicBoolean;
import linmumua.doudizhu.DoudizhuPlugin;

/**
 * 已保存 HUD 连续资源的独立恢复服务。
 *
 * <p>恢复不依赖 Debug Web 是否启用：启动完成 bundle 导出、/muz reload，以及 CraftEngine
 * 启用事件都通过同一个 {@link HudWebApplyCoordinator} 重读磁盘并验证四层资源。服务本身不
 * 直接操作 CraftEngine，也不递归触发自身生命周期；共享数据目录租约由 coordinator 统一负责。
 */
public final class HudResourceRecoveryService implements AutoCloseable {
    private final DoudizhuPlugin plugin;
    private final HudWebApplyCoordinator coordinator;
    private final AtomicBoolean closed = new AtomicBoolean();

    public HudResourceRecoveryService(DoudizhuPlugin plugin, HudWebApplyCoordinator coordinator) {
        this.plugin = Objects.requireNonNull(plugin, "plugin");
        this.coordinator = Objects.requireNonNull(coordinator, "coordinator");
    }

    /** bundle 导出完成后恢复网页之前保存的连续 HUD 值。 */
    public CompletableFuture<HudWebApplyCoordinator.ApplyResult> onBundleExported(String reason) {
        return submit(reason == null || reason.isBlank() ? "bundle-export" : reason);
    }

    /** CraftEngine 启用后重试恢复；CraftEngine 缺失时由 coordinator fail-closed。 */
    public CompletableFuture<HudWebApplyCoordinator.ApplyResult> onCraftEngineEnabled(String reason) {
        return submit(reason == null || reason.isBlank() ? "craftengine-enable" : reason);
    }

    /** /muz reload 在 bundle 处理后调用的恢复委托。 */
    public CompletableFuture<HudWebApplyCoordinator.ApplyResult> reloadFromDisk(String reason) {
        return submit(reason == null || reason.isBlank() ? "manual-reload" : reason);
    }

    private CompletableFuture<HudWebApplyCoordinator.ApplyResult> submit(String reason) {
        if (closed.get() || plugin.isShuttingDown()) {
            return CompletableFuture.completedFuture(failure("HUD 资源恢复服务已关闭，未执行：" + reason));
        }
        CompletableFuture<HudWebApplyCoordinator.ApplyResult> future;
        try {
            future = coordinator.submitReload();
        } catch (Throwable failure) {
            plugin.getLogger().warning("HUD 资源恢复提交失败（" + reason + "）：" + failure.getMessage());
            return CompletableFuture.completedFuture(failure("HUD 资源恢复提交失败：" + failure.getMessage()));
        }
        if (future == null) {
            return CompletableFuture.completedFuture(failure("HUD 资源恢复协调器返回空 Future。"));
        }
        return future.whenComplete((result, failure) -> {
            if (failure != null) {
                plugin.getLogger().warning("HUD 资源恢复失败（" + reason + "）：" + failure.getMessage());
            } else if (result == null || !result.ok()) {
                String detail = result == null ? "空结果" : String.join("；", result.messages());
                plugin.getLogger().warning("HUD 资源恢复未应用（" + reason + "）：" + detail);
            } else {
                plugin.getLogger().info("HUD 资源恢复完成（" + reason + "），四层资源已在服务端校验。");
            }
        });
    }

    private HudWebApplyCoordinator.ApplyResult failure(String message) {
        return new HudWebApplyCoordinator.ApplyResult(false, null, java.util.List.of(), java.util.List.of(message));
    }

    @Override
    public void close() {
        closed.set(true);
    }
}
