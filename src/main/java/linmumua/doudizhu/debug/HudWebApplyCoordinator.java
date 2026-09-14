package linmumua.doudizhu.debug;

import linmumua.doudizhu.DoudizhuPlugin;
import linmumua.doudizhu.compat.CraftEngineHudResourceBridge;
import linmumua.doudizhu.compat.HudResourcePackBridge;
import org.bukkit.plugin.Plugin;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;

/**
 * Debug Web HUD 的串行应用协调器。
 *
 * <p>保存顺序固定为：主线程确认 CraftEngine 可用并解析覆盖层目录，异步写 config.yml 与
 * 当前唯一的 hotbar CE 覆盖层，主线程启动 CraftEngine 真实 reload Future，随后异步生成
 * 并校验实际资源包，最后切回主线程应用 Trick HUD/Hotbar HUD 运行态并发布 Snapshot。
 * Trick HUD 当前没有独立的运行期 CE 字形资源，因此这里不会臆造 Trick HUD 资源。
 *
 * <p>HTTP 等待 Future 与 CraftEngine 原始 Future 明确分离：120 秒只让本次 Web 结果超时，
 * 不 cancel 原始 Future，也不释放共享租约。原始重载、生成、校验和迟到的应用链真正结束后
 * 才释放租约；超时或关闭后的迟到回调会被任务闸门拦截，不能应用旧 HUD 或发布旧 Snapshot。
 */
public final class HudWebApplyCoordinator implements AutoCloseable {
    static final long RAW_RESULT_TIMEOUT_SECONDS = HudWebApplyTaskGate.DEFAULT_TIMEOUT_SECONDS;

    private final DoudizhuPlugin plugin;
    private final DebugHudConfigController controller;
    private final HotbarDebugOverlayWriter overlayWriter;
    private final HudResourcePackBridge injectedBridge;
    private final ExecutorService executor;
    private final ScheduledExecutorService timeoutExecutor;
    private final Executor mainExecutor;
    private final HudWebApplyTaskGate taskGate;
    private final Object lifecycleLock = new Object();
    private volatile HudResourcePackBridge resolvedBridge;
    private volatile boolean closed;

    public HudWebApplyCoordinator(DoudizhuPlugin plugin, DebugHudConfigController controller) {
        this(plugin, controller, null, null, null, null, null);
    }

    /** 仅供测试注入纯 Java bridge、执行器与租约；不触碰 CraftEngine 类。 */
    HudWebApplyCoordinator(DoudizhuPlugin plugin, DebugHudConfigController controller,
                           HudResourcePackBridge bridge, ExecutorService executor,
                           ScheduledExecutorService timeoutExecutor, HudWebApplyLease lease) {
        this(plugin, controller, bridge, executor, timeoutExecutor, lease, null);
    }

    /** 测试可注入主线程执行器；生产构造仍统一切 Bukkit 主线程。 */
    HudWebApplyCoordinator(DoudizhuPlugin plugin, DebugHudConfigController controller,
                           HudResourcePackBridge bridge, ExecutorService executor,
                           ScheduledExecutorService timeoutExecutor, HudWebApplyLease lease,
                           Executor mainExecutor) {
        this.plugin = Objects.requireNonNull(plugin, "plugin");
        this.controller = Objects.requireNonNull(controller, "controller");
        this.overlayWriter = new HotbarDebugOverlayWriter(plugin);
        this.injectedBridge = bridge;
        this.executor = executor == null ? Executors.newSingleThreadExecutor(r -> {
            Thread thread = new Thread(r, "muz-debug-web-apply");
            thread.setDaemon(true);
            return thread;
        }) : executor;
        this.timeoutExecutor = timeoutExecutor == null ? Executors.newSingleThreadScheduledExecutor(r -> {
            Thread thread = new Thread(r, "muz-debug-web-timeout");
            thread.setDaemon(true);
            return thread;
        }) : timeoutExecutor;
        this.mainExecutor = mainExecutor == null
            ? command -> plugin.getServer().getScheduler().runTask(plugin, command)
            : mainExecutor;
        HudWebApplyLease sharedLease;
        if (lease != null) {
            // 纯 Java 测试注入共享租约时无需访问 Bukkit 的 dataFolder；生产构造仍按插件数据目录共享。
            sharedLease = lease;
        } else {
            String leaseKey = plugin.getDataFolder().toPath().toAbsolutePath().normalize().toString();
            sharedLease = HudWebApplyLease.forKey(leaseKey);
        }
        this.taskGate = new HudWebApplyTaskGate(sharedLease, this.timeoutExecutor,
            RAW_RESULT_TIMEOUT_SECONDS);
    }

    /** 判断保存 patch 是否需要进入资源写入/重载流程；空 patch 只返回当前快照。 */
    static boolean shouldRunResourceFlow(DebugHudConfigController.Patch patch) {
        return patch != null && !patch.values().isEmpty();
    }

    public CompletableFuture<ApplyResult> submitSave(DebugHudConfigController.Patch patch) {
        Objects.requireNonNull(patch, "patch");
        try {
            controller.validatePatchAgainstCurrentHotbar(patch);
        } catch (DebugHudConfigController.ValidationException exception) {
            return completedFailure(exception.getMessage());
        }
        synchronized (lifecycleLock) {
            if (closed || taskGate.isClosed()) {
                return completedFailure("Debug Web HUD 应用协调器已关闭。");
            }
            if (taskGate.isBusy()) {
                return completedFailure("HUD 资源任务仍在运行，请等待当前重载、生成与校验结束。");
            }
            if (!shouldRunResourceFlow(patch)) {
                return CompletableFuture.completedFuture(ApplyResult.success(
                    controller.snapshot(), List.of(), List.of("没有提交任何变更。")));
            }
            HudWebApplyTaskGate.Task<ApplyResult> task = taskGate.tryAcquire();
            if (task == null) {
                return completedFailure("HUD 资源任务仍在运行，请等待当前重载、生成与校验结束。");
            }
            CompletableFuture<ApplyResult> raw = safePipeline(() -> savePipeline(patch, task));
            CompletableFuture<ApplyResult> exposed = monitor(task, raw);
            return exposed;
        }
    }

    public CompletableFuture<ApplyResult> submitReload() {
        synchronized (lifecycleLock) {
            if (closed || taskGate.isClosed()) {
                return completedFailure("Debug Web HUD 应用协调器已关闭。");
            }
            if (taskGate.isBusy()) {
                return completedFailure("HUD 资源任务仍在运行，请等待当前重载、生成与校验结束。");
            }
            HudWebApplyTaskGate.Task<ApplyResult> task = taskGate.tryAcquire();
            if (task == null) {
                return completedFailure("HUD 资源任务仍在运行，请等待当前重载、生成与校验结束。");
            }
            CompletableFuture<ApplyResult> raw = safePipeline(() -> reloadPipeline(task));
            return monitor(task, raw);
        }
    }

    private CompletableFuture<ApplyResult> savePipeline(DebugHudConfigController.Patch patch,
                                                          HudWebApplyTaskGate.Task<ApplyResult> task) {
        return resolveOnMainThread(task).thenCompose(resolved -> {
            // 关闭/超时可能发生在前一个主线程阶段完成之后；提交 supplyAsync 前必须再次检查。
            if (!isTaskActive(task)) {
                return CompletableFuture.completedFuture(inactiveResult("未写入配置。"));
            }
            return CompletableFuture.supplyAsync(() -> {
                try {
                    SaveTransaction transaction = captureTransaction(resolved.configPath(), resolved.root());
                    DebugHudConfigController.DiskSaveResult disk = taskGate.runIfActive(
                        task,
                        () -> controller.savePatchToDisk(patch),
                        () -> null
                    );
                    return new DiskStage(transaction, disk);
                } catch (java.io.IOException exception) {
                    throw new java.util.concurrent.CompletionException(exception);
                }
            }, executor).thenCompose(stage -> {
                if (stage == null || stage.disk() == null) {
                    return CompletableFuture.completedFuture(inactiveResult("未写入配置或 hotbar 覆盖层。"));
                }
                DebugHudConfigController.DiskSaveResult disk = stage.disk();
                if (!disk.ok()) {
                    return CompletableFuture.completedFuture(ApplyResult.failed(
                        controller.snapshot(), disk.messages()));
                }
                SaveTransaction transaction = stage.transaction();
                // 当前 HUD 没有独立的 Trick HUD CE 字形资源；每次保存统一写当前 hotbar overlay。
                return overlayWriter.writeAsync(resolved.root(), disk.offsetY(), disk.hotbarScale(), executor,
                        () -> isTaskActive(task))
                    .thenCompose(written -> {
                        if (!isTaskActive(task)) {
                            return rollbackAndFailure(transaction, "任务已失效，已回滚配置与 hotbar 覆盖层。");
                        }
                        if (!written) {
                            return rollbackAndFailure(transaction,
                                "写出当前 hotbar 调试覆盖层失败，已回滚配置，未触发 CraftEngine 重载。");
                        }
                        return resourceAndApply(resolved.bridge(), disk.offsetY(), disk.hotbarScale(),
                            disk.appliedKeys(), disk.messages(), task)
                            .handle((result, failure) -> {
                                if (failure == null && result != null && result.ok()) {
                                    return CompletableFuture.completedFuture(result);
                                }
                                String detail = failure == null
                                    ? (result == null ? "资源同步返回空结果。" : String.join("；", result.messages()))
                                    : "CraftEngine 资源同步失败：" + failure.getMessage();
                                return rollbackAndFailure(transaction, detail + " 已回滚配置与 hotbar 覆盖层。");
                            }).thenCompose(future -> future);
                    });
            });
        }).exceptionally(this::failedFromThrowable);
    }

    private SaveTransaction captureTransaction(Path configPath, Path overlayRoot) throws java.io.IOException {
        boolean configExists = Files.isRegularFile(configPath);
        byte[] configBytes = configExists ? Files.readAllBytes(configPath) : new byte[0];
        return new SaveTransaction(configPath, configExists, configBytes,
            overlayRoot, overlayWriter.capture(overlayRoot));
    }

    private CompletableFuture<ApplyResult> rollbackAndFailure(SaveTransaction transaction, String detail) {
        return CompletableFuture.supplyAsync(() -> {
            try {
                if (transaction.configExists()) {
                    Files.createDirectories(transaction.configPath().getParent());
                    Path temp = Files.createTempFile(transaction.configPath().getParent(), "config.yml.rollback.", ".tmp");
                    try {
                        Files.write(temp, transaction.configBytes());
                        try {
                            Files.move(temp, transaction.configPath(), java.nio.file.StandardCopyOption.ATOMIC_MOVE,
                                java.nio.file.StandardCopyOption.REPLACE_EXISTING);
                        } catch (java.nio.file.AtomicMoveNotSupportedException exception) {
                            Files.move(temp, transaction.configPath(), java.nio.file.StandardCopyOption.REPLACE_EXISTING);
                        }
                    } finally {
                        Files.deleteIfExists(temp);
                    }
                } else {
                    Files.deleteIfExists(transaction.configPath());
                }
                overlayWriter.restore(transaction.overlayRoot(), transaction.overlayState());
                controller.reloadFromDiskForWeb();
                return ApplyResult.failed(controller.snapshot(), List.of(detail));
            } catch (Exception rollbackFailure) {
                String message = detail + " 但补偿失败：" + rollbackFailure.getMessage();
                plugin.getLogger().warning(message);
                return ApplyResult.failed(controller.snapshot(), List.of(message));
            }
        }, executor);
    }

    private CompletableFuture<ApplyResult> reloadPipeline(HudWebApplyTaskGate.Task<ApplyResult> task) {
        return resolveOnMainThread(task).thenCompose(resolved -> {
            if (!isTaskActive(task)) {
                return CompletableFuture.completedFuture(inactiveResult("未重载配置。"));
            }
            return CompletableFuture.supplyAsync(() -> {
                if (!isTaskActive(task)) {
                    return null;
                }
                // 保留 reloadFromDiskForWeb() 这个旧调用入口；scale 在同一配置锁边界内读取。
                int offsetY = controller.reloadFromDiskForWeb();
                int hotbarScale = controller.hotbarScaleForWeb();
                return new RuntimeValues(offsetY, hotbarScale);
            }, executor)
                .thenCompose(values -> {
                    if (values == null || !isTaskActive(task)) {
                        return CompletableFuture.completedFuture(inactiveResult("未写入 hotbar 覆盖层。"));
                    }
                    return overlayWriter.writeAsync(resolved.root(), values.offsetY(), values.hotbarScale(), executor,
                            () -> isTaskActive(task))
                        .thenCompose(written -> {
                            if (!isTaskActive(task)) {
                                return CompletableFuture.completedFuture(inactiveResult(
                                    "未写入 hotbar 覆盖层，也未启动 CraftEngine 重载。"));
                            }
                            if (!written) {
                                return CompletableFuture.completedFuture(ApplyResult.failed(controller.snapshot(),
                                    List.of("写出当前 hotbar 调试覆盖层失败，未触发 CraftEngine 重载。")));
                            }
                            return resourceAndApply(resolved.bridge(), values.offsetY(), values.hotbarScale(),
                                List.of(), List.of("已从磁盘重新读取 HUD 配置。"), task);
                        });
                });
        }).exceptionally(this::failedFromThrowable);
    }

    private CompletableFuture<Resolved> resolveOnMainThread(HudWebApplyTaskGate.Task<ApplyResult> task) {
        return runOnMain(() -> {
            if (!isTaskActive(task)) {
                throw new IllegalStateException("Debug Web HUD 应用任务已失效，未写入配置。");
            }
            HudResourcePackBridge selected = injectedBridge != null ? injectedBridge : resolvedBridge;
            if (selected == null) {
                selected = createBridgeOnMainThread();
                if (selected != null) {
                    resolvedBridge = selected;
                }
            }
            if (selected == null) {
                throw new IllegalStateException("CraftEngine 未启用，未写入 HUD 配置。");
            }
            String failure = selected.preflightFailureOnMainThread();
            if (failure != null) {
                throw new IllegalStateException(failure);
            }
            Path root = overlayWriter.resolveOverlayRoot();
            if (root == null) {
                throw new IllegalStateException("CraftEngine 覆盖层目录不可用，未写入 HUD 配置。");
            }
            Path configPath = plugin.getDataFolder().toPath().resolve("config.yml");
            return new Resolved(root, configPath, selected);
        });
    }

    private HudResourcePackBridge createBridgeOnMainThread() {
        try {
            Plugin craftEngine = plugin.getServer().getPluginManager().getPlugin("CraftEngine");
            if (craftEngine == null || !craftEngine.isEnabled()) {
                return null;
            }
            return new CraftEngineHudResourceBridge(plugin);
        } catch (LinkageError error) {
            plugin.getLogger().warning("CraftEngine HUD 资源桥接不可用，已降级：" + error.getMessage());
            return null;
        }
    }

    private CompletableFuture<ApplyResult> resourceAndApply(HudResourcePackBridge selected, int offsetY,
                                                              int hotbarScale, List<String> appliedKeys,
                                                              List<String> messages,
                                                              HudWebApplyTaskGate.Task<ApplyResult> task) {
        CompletableFuture<CompletableFuture<Void>> started = runOnMain(() -> {
            if (!isTaskActive(task)) {
                throw new IllegalStateException("HUD 资源任务已失效，未启动 CraftEngine 重载。");
            }
            // 本次 overlay 尚未完成真实重载与 ZIP 校验，先撤销旧的 ready 声明；失败时绝不虚报成功。
            plugin.setHotbarOverlayReady(false);
            return selected.reloadGenerateAndVerify(offsetY, hotbarScale, executor, mainExecutor);
        });
        return started.thenCompose(resource -> {
            if (resource == null) {
                return failedFuture(new IllegalStateException("CraftEngine 资源任务返回空 Future。"));
            }
            return resource;
        }).thenCompose(ignored -> applyOnMain(hotbarScale, appliedKeys, messages, task));
    }

    private CompletableFuture<ApplyResult> applyOnMain(int hotbarScale, List<String> appliedKeys,
                                                        List<String> messages,
                                                        HudWebApplyTaskGate.Task<ApplyResult> task) {
        return runOnMain(() -> taskGate.runIfActiveAtomically(
            task,
            () -> {
                // 到这里才表示真实 CE reload/generate/ZIP 校验全链路成功；无客户端回执仍不宣称客户端已应用。
                plugin.setHotbarOverlayReady(true, hotbarScale);
                plugin.applyHudRuntimeStateFromWeb();
                List<String> resultMessages = new ArrayList<>(messages);
                resultMessages.add("服务端资源包内容已校验；CraftEngine 自动上传结果未确认，客户端待重新下载。");
                return ApplyResult.success(controller.snapshot(), appliedKeys, resultMessages);
            },
            () -> inactiveResult("未应用 HUD 运行态，也未发布旧 Snapshot。")
        ));
    }

    private CompletableFuture<ApplyResult> monitor(HudWebApplyTaskGate.Task<ApplyResult> task,
                                                    CompletableFuture<ApplyResult> raw) {
        return taskGate.monitor(
            task,
            raw,
            () -> inactiveResult("任务已失效，迟到结果未应用。"),
            this::failedFromThrowable,
            this::shutdownIfClosed
        );
    }

    private <T> CompletableFuture<T> runOnMain(java.util.function.Supplier<T> supplier) {
        CompletableFuture<T> result = new CompletableFuture<>();
        try {
            mainExecutor.execute(() -> {
                try {
                    result.complete(supplier.get());
                } catch (Throwable throwable) {
                    result.completeExceptionally(throwable);
                }
            });
        } catch (Throwable throwable) {
            result.completeExceptionally(throwable);
        }
        return result;
    }

    private CompletableFuture<ApplyResult> safePipeline(
        java.util.function.Supplier<CompletableFuture<ApplyResult>> pipeline
    ) {
        try {
            CompletableFuture<ApplyResult> result = pipeline.get();
            return result == null
                ? CompletableFuture.completedFuture(failedFromThrowable(
                    new IllegalStateException("HUD 应用流程返回空 Future。")))
                : result;
        } catch (Throwable throwable) {
            return CompletableFuture.completedFuture(failedFromThrowable(throwable));
        }
    }

    private boolean isTaskActive(HudWebApplyTaskGate.Task<ApplyResult> task) {
        return !closed && taskGate.isActive(task);
    }

    private ApplyResult failedFromThrowable(Throwable failure) {
        Throwable cause = failure;
        while ((cause instanceof java.util.concurrent.CompletionException
            || cause instanceof java.util.concurrent.ExecutionException)
            && cause.getCause() != null) {
            cause = cause.getCause();
        }
        String detail = "HUD 资源应用失败：" + String.valueOf(cause.getMessage());
        try {
            plugin.getLogger().warning(detail);
        } catch (Throwable loggingFailure) {
            java.util.logging.Logger.getLogger(HudWebApplyCoordinator.class.getName())
                .log(java.util.logging.Level.WARNING, detail, loggingFailure);
        }
        return ApplyResult.failed(controller.snapshot(), List.of(detail));
    }

    private ApplyResult inactiveResult(String detail) {
        return ApplyResult.failed(controller.snapshot(), List.of("Debug Web HUD " + detail));
    }

    private CompletableFuture<ApplyResult> completedFailure(String detail) {
        return CompletableFuture.completedFuture(ApplyResult.failed(controller.snapshot(), List.of(detail)));
    }

    private static <T> CompletableFuture<T> failedFuture(Throwable throwable) {
        CompletableFuture<T> failed = new CompletableFuture<>();
        failed.completeExceptionally(throwable);
        return failed;
    }

    private void shutdownIfClosed() {
        // raw 回调可能来自 taskGate 的锁内完成路径；这里不能反向获取 lifecycleLock，
        // 否则 close() 持有 lifecycleLock 等待 taskGate 时会形成锁顺序反转。
        if (closed && !taskGate.hasLocalTask()) {
            executor.shutdown();
            timeoutExecutor.shutdownNow();
        }
    }

    @Override
    public void close() {
        synchronized (lifecycleLock) {
            if (closed) {
                return;
            }
            closed = true;
            taskGate.close();
            // 不释放当前租约：CraftEngine 原始 Future 可能仍在生成/校验，迟到回调必须被挡住。
            // raw Future 完成后 shutdownIfClosed 才会释放执行器；未启动本实例任务时可立即停止。
            // 这里必须检查本实例的 task，而不是共享 lease：其它 Web 实例的任务不能阻止本实例收尾。
            if (taskGate.hasLocalTask()) {
                timeoutExecutor.shutdownNow();
            } else {
                executor.shutdownNow();
                timeoutExecutor.shutdownNow();
            }
        }
    }

    private record Resolved(Path root, Path configPath, HudResourcePackBridge bridge) {}

    private record RuntimeValues(int offsetY, int hotbarScale) {}

    private record SaveTransaction(Path configPath, boolean configExists, byte[] configBytes,
                                   Path overlayRoot,
                                   HotbarDebugOverlayWriter.OverlayFileState overlayState) {
        private SaveTransaction {
            configBytes = configBytes.clone();
        }

        @Override
        public byte[] configBytes() {
            return configBytes.clone();
        }
    }

    private record DiskStage(SaveTransaction transaction, DebugHudConfigController.DiskSaveResult disk) {}

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
