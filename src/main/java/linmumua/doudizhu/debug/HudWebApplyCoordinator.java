package linmumua.doudizhu.debug;

import linmumua.doudizhu.DoudizhuPlugin;

import java.nio.file.Path;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Debug Web HUD 的串行应用协调器。
 *
 * <p>保存顺序固定为：异步写 config.yml 与当前唯一的 hotbar CE 覆盖层，
 * 主线程 dispatch {@code ce reload pack}，再应用 Trick HUD/Hotbar HUD 运行态，
 * 最后生成并发布 Snapshot。Trick HUD 当前没有独立的运行期 CE 字形资源，
 * 因此这里不会臆造 Trick HUD 资源，只统一写出 hotbar overlay。
 */
public final class HudWebApplyCoordinator implements AutoCloseable {
    private final DoudizhuPlugin plugin;
    private final DebugHudConfigController controller;
    private final HotbarDebugOverlayWriter overlayWriter;
    private final ExecutorService executor;
    private final Object queueLock = new Object();
    private CompletableFuture<Void> tail = CompletableFuture.completedFuture(null);
    private volatile boolean closed;
    private volatile long generation;

    public HudWebApplyCoordinator(DoudizhuPlugin plugin, DebugHudConfigController controller) {
        this.plugin = Objects.requireNonNull(plugin, "plugin");
        this.controller = Objects.requireNonNull(controller, "controller");
        this.overlayWriter = new HotbarDebugOverlayWriter(plugin);
        this.executor = Executors.newSingleThreadExecutor(r -> {
            Thread thread = new Thread(r, "muz-debug-web-apply");
            thread.setDaemon(true);
            return thread;
        });
    }

    public CompletableFuture<ApplyResult> submitSave(DebugHudConfigController.Patch patch) {
        Objects.requireNonNull(patch, "patch");
        if (patch.values().isEmpty()) {
            synchronized (queueLock) {
                if (closed) {
                    return CompletableFuture.completedFuture(ApplyResult.failed(controller.snapshot(),
                        List.of("Debug Web HUD 应用协调器已关闭。")));
                }
                return CompletableFuture.completedFuture(ApplyResult.success(
                    controller.snapshot(), List.of(), List.of("没有提交任何变更。")));
            }
        }
        return enqueue(taskGeneration -> resolveOverlayRootOnMainThread(taskGeneration)
            .thenCompose(root -> {
                if (!isActive(taskGeneration)) {
                    return CompletableFuture.completedFuture(ApplyResult.failed(controller.snapshot(),
                        List.of("Debug Web HUD 应用任务已失效，未写入配置。")));
                }
                return CompletableFuture.supplyAsync(
                    () -> controller.savePatchToDisk(patch), executor
                ).thenCompose(disk -> {
                    if (!disk.ok()) {
                        return CompletableFuture.completedFuture(ApplyResult.failed(
                            controller.snapshot(), disk.messages()));
                    }
                    if (!isActive(taskGeneration)) {
                        return CompletableFuture.completedFuture(ApplyResult.failed(controller.snapshot(),
                            List.of("Debug Web HUD 应用任务已失效，未写入 hotbar 覆盖层。")));
                    }
                    // 当前 HUD 没有独立的 Trick HUD CE 字形资源；每次保存统一写当前 hotbar overlay。
                    return overlayWriter.writeAsync(root, disk.offsetY(), executor)
                        .thenCompose(written -> dispatchAndApply(written, disk.appliedKeys(), disk.messages(), taskGeneration));
                });
            }));
    }

    public CompletableFuture<ApplyResult> submitReload() {
        return enqueue(taskGeneration -> resolveOverlayRootOnMainThread(taskGeneration)
            .thenCompose(root -> {
                if (!isActive(taskGeneration)) {
                    return CompletableFuture.completedFuture(ApplyResult.failed(controller.snapshot(),
                        List.of("Debug Web HUD 应用任务已失效，未重载配置。")));
                }
                return CompletableFuture.supplyAsync(
                    controller::reloadFromDiskForWeb, executor
                ).thenCompose(offsetY -> {
                    if (!isActive(taskGeneration)) {
                        return CompletableFuture.completedFuture(ApplyResult.failed(controller.snapshot(),
                            List.of("Debug Web HUD 应用任务已失效，未写入 hotbar 覆盖层。")));
                    }
                    return overlayWriter.writeAsync(root, offsetY, executor)
                        .thenCompose(written -> dispatchAndApply(written, List.of(),
                            List.of("已从磁盘重新读取 HUD 配置。"), taskGeneration));
                });
            }));
    }

    private CompletableFuture<Path> resolveOverlayRootOnMainThread(long taskGeneration) {
        CompletableFuture<Path> result = new CompletableFuture<>();
        if (!isActive(taskGeneration)) {
            result.completeExceptionally(new IllegalStateException("Debug Web HUD 应用协调器已关闭。"));
            return result;
        }
        try {
            plugin.getServer().getScheduler().runTask(plugin, () -> {
                if (!isActive(taskGeneration)) {
                    result.completeExceptionally(new IllegalStateException("Debug Web HUD 应用任务已失效。"));
                    return;
                }
                try {
                    result.complete(overlayWriter.resolveOverlayRoot());
                } catch (Throwable throwable) {
                    result.completeExceptionally(throwable);
                }
            });
        } catch (Throwable throwable) {
            result.completeExceptionally(throwable);
        }
        return result;
    }

    private CompletableFuture<ApplyResult> dispatchAndApply(boolean written, List<String> appliedKeys,
                                                              List<String> messages, long taskGeneration) {
        CompletableFuture<ApplyResult> result = new CompletableFuture<>();
        if (!written) {
            result.complete(ApplyResult.failed(controller.snapshot(),
                List.of("写出当前 hotbar 调试覆盖层失败，未触发 CraftEngine 重载。")));
            return result;
        }
        if (!isActive(taskGeneration)) {
            result.complete(ApplyResult.failed(controller.snapshot(),
                List.of("Debug Web HUD 应用协调器已关闭，未应用 HUD 运行态。")));
            return result;
        }
        try {
            plugin.getServer().getScheduler().runTask(plugin, () -> {
                synchronized (queueLock) {
                    if (!isActive(taskGeneration)) {
                        result.complete(ApplyResult.failed(controller.snapshot(),
                            List.of("Debug Web HUD 应用任务已失效，未应用 HUD 运行态。")));
                        return;
                    }
                    try {
                        if (!overlayWriter.reloadCraftEngineOnMainThread()) {
                            result.complete(ApplyResult.failed(controller.snapshot(),
                                List.of("CraftEngine 重载命令执行失败，未应用 HUD 运行态。")));
                            return;
                        }
                        if (!isActive(taskGeneration)) {
                            result.complete(ApplyResult.failed(controller.snapshot(),
                                List.of("Debug Web HUD 应用协调器已关闭，未应用 HUD 运行态。")));
                            return;
                        }
                        plugin.applyHudRuntimeStateFromWeb();
                        if (!isActive(taskGeneration)) {
                            result.complete(ApplyResult.failed(controller.snapshot(),
                                List.of("Debug Web HUD 应用协调器已关闭，未发布 HUD 快照。")));
                            return;
                        }
                        result.complete(ApplyResult.success(controller.snapshot(), appliedKeys, messages));
                    } catch (Throwable throwable) {
                        plugin.getLogger().warning("Debug Web HUD 运行态应用失败：" + throwable.getMessage());
                        result.complete(ApplyResult.failed(controller.snapshot(),
                            List.of("HUD 运行态应用失败：" + String.valueOf(throwable.getMessage()))));
                    }
                }
            });
        } catch (Throwable throwable) {
            result.complete(ApplyResult.failed(controller.snapshot(),
                List.of("提交 HUD 主线程任务失败：" + String.valueOf(throwable.getMessage()))));
        }
        return result;
    }

    private CompletableFuture<ApplyResult> enqueue(
        java.util.function.Function<Long, CompletableFuture<ApplyResult>> action
    ) {
        synchronized (queueLock) {
            if (closed) {
                return CompletableFuture.completedFuture(ApplyResult.failed(controller.snapshot(),
                    List.of("Debug Web HUD 应用协调器已关闭。")));
            }
            long taskGeneration = generation;
            CompletableFuture<ApplyResult> next = tail.handle((ignored, failure) -> null)
                .thenCompose(ignored -> {
                    if (!isActive(taskGeneration)) {
                        return CompletableFuture.completedFuture(ApplyResult.failed(controller.snapshot(),
                            List.of("Debug Web HUD 应用任务已失效。")));
                    }
                    return action.apply(taskGeneration);
                });
            tail = next.handle((ignored, failure) -> null);
            return next;
        }
    }

    private boolean isActive(long taskGeneration) {
        return !closed && generation == taskGeneration;
    }

    @Override
    public void close() {
        synchronized (queueLock) {
            if (closed) {
                return;
            }
            // 先取得协调器临界区：已进入主线程应用阶段的任务会在 close 返回前完成，
            // 尚未进入的排队任务则在 generation 检查处失效，不会在关闭后继续应用。
            closed = true;
            generation++;
        }
        executor.shutdownNow();
    }

    public record ApplyResult(boolean ok, DebugHudConfigController.Snapshot snapshot,
                              List<String> appliedKeys, List<String> messages) {
        public ApplyResult {
            appliedKeys = List.copyOf(appliedKeys);
            messages = List.copyOf(messages);
        }

        static ApplyResult success(DebugHudConfigController.Snapshot snapshot, List<String> appliedKeys,
                                   List<String> messages) {
            return new ApplyResult(true, snapshot, appliedKeys, messages);
        }

        static ApplyResult failed(DebugHudConfigController.Snapshot snapshot, List<String> messages) {
            return new ApplyResult(false, snapshot, List.of(), messages);
        }
    }
}
