package linmumua.doudizhu.debug;

import linmumua.doudizhu.DoudizhuPlugin;
import linmumua.doudizhu.assets.HudResourceRequest;
import linmumua.doudizhu.compat.CraftEngineHudResourceBridge;
import linmumua.doudizhu.compat.HudResourcePackBridge;
import org.bukkit.plugin.Plugin;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
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
 * 三层 Trick HUD CE 覆盖层，主线程启动 CraftEngine 真实 reload Future，随后异步生成并校验
 * 实际资源包，最后切回主线程原子标记完整 request 已验证并发布 Snapshot。

 *
 * <p>HTTP 等待 Future 与 CraftEngine 原始 Future 明确分离：120 秒只让本次 Web 结果超时，
 * 不 cancel 原始 Future，也不释放共享租约。原始重载、生成、校验和迟到的应用链真正结束后
 * 才释放租约；超时或关闭后的迟到回调会被任务闸门拦截，不能应用旧 HUD 或发布旧 Snapshot。
 */
public final class HudWebApplyCoordinator implements AutoCloseable {
    static final long RAW_RESULT_TIMEOUT_SECONDS = HudWebApplyTaskGate.DEFAULT_TIMEOUT_SECONDS;

    private final DoudizhuPlugin plugin;
    private final DebugHudConfigController controller;
    private final HudOverlayWriter overlayWriter;
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
        this(plugin, controller, bridge, executor, timeoutExecutor, lease, null,
            RAW_RESULT_TIMEOUT_SECONDS);
    }

    /** 测试可注入主线程执行器；生产构造仍统一切 Bukkit 主线程。 */
    HudWebApplyCoordinator(DoudizhuPlugin plugin, DebugHudConfigController controller,
                           HudResourcePackBridge bridge, ExecutorService executor,
                           ScheduledExecutorService timeoutExecutor, HudWebApplyLease lease,
                           Executor mainExecutor) {
        this(plugin, controller, bridge, executor, timeoutExecutor, lease, mainExecutor,
            RAW_RESULT_TIMEOUT_SECONDS);
    }

    /** 仅测试使用：缩短 raw 结果租约，验证超时后迟到结果不会应用。 */
    HudWebApplyCoordinator(DoudizhuPlugin plugin, DebugHudConfigController controller,
                           HudResourcePackBridge bridge, ExecutorService executor,
                           ScheduledExecutorService timeoutExecutor, HudWebApplyLease lease,
                           Executor mainExecutor, long timeoutSeconds) {
        this.plugin = Objects.requireNonNull(plugin, "plugin");
        this.controller = Objects.requireNonNull(controller, "controller");
        this.overlayWriter = new HudOverlayWriter(plugin);
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
        this.taskGate = new HudWebApplyTaskGate(sharedLease, this.timeoutExecutor, timeoutSeconds);
    }

    /** 判断保存 patch 是否需要进入资源写入/重载流程；空 patch 只返回当前快照。 */
    static boolean shouldRunResourceFlow(DebugHudConfigController.Patch patch) {
        return patch != null && !patch.values().isEmpty();
    }

    public CompletableFuture<ApplyResult> submitSave(DebugHudConfigController.Patch patch) {
        Objects.requireNonNull(patch, "patch");
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
        java.util.concurrent.atomic.AtomicReference<SaveTransaction> transactionRef =
            new java.util.concurrent.atomic.AtomicReference<>();
        return resolveOnMainThread(task).thenCompose(resolved -> {
            // 关闭/超时可能发生在前一个主线程阶段完成之后；提交 supplyAsync 前必须再次检查。
            if (!isTaskActive(task)) {
                return CompletableFuture.completedFuture(inactiveResult("未写入配置。"));
            }
            return CompletableFuture.supplyAsync(() -> {
                try {
                    SaveTransaction transaction = captureTransaction(resolved.configPath(), resolved.root());
                    transactionRef.set(transaction);
                    DebugHudConfigController.DiskSaveResult disk = taskGate.runIfActive(
                        task,
                        () -> controller.savePatchToDisk(patch),
                        () -> null
                    );
                    Boolean writtenConfigExists = Files.isRegularFile(resolved.configPath());
                    byte[] writtenConfigBytes = writtenConfigExists
                        ? Files.readAllBytes(resolved.configPath()) : new byte[0];
                    SaveTransaction committed = transaction.withExpectedConfigState(
                        writtenConfigExists, writtenConfigBytes);
                    transactionRef.set(committed);
                    return new DiskStage(committed, disk);
                } catch (java.io.IOException exception) {
                    throw new java.util.concurrent.CompletionException(exception);
                }
            }, executor).thenCompose(stage -> {
                if (stage == null || stage.disk() == null) {
                    return CompletableFuture.completedFuture(inactiveResult("未写入配置或 HUD 覆盖层。"));
                }
                DebugHudConfigController.DiskSaveResult disk = stage.disk();
                if (!disk.ok()) {
                    String detail = disk.messages().isEmpty()
                        ? "保存 config.yml 失败。" : String.join("；", disk.messages());
                    return rollbackAndFailure(task, stage.transaction(), detail + " 已回滚可安全回滚的 HUD 配置与全部 HUD 覆盖层；检测到并发修改时保留其它配置。");
                }
                SaveTransaction transaction = stage.transaction();
                HudResourceRequest request = disk.resources();
                return overlayWriter.writeAsync(resolved.root(), request, executor,
                        () -> isTaskActive(task))
                    .thenCompose(written -> {
                        if (!isTaskActive(task)) {
                            return rollbackAndFailure(task, transaction, "任务已失效，已回滚配置与 HUD 覆盖层。");
                        }
                        if (!written) {
                            return rollbackAndFailure(task, transaction,
                                "写出当前 HUD 调试覆盖层失败，已回滚配置，未触发 CraftEngine 重载。");
                        }
                        return resourceAndApply(resolved.bridge(), request,
                            disk.appliedKeys(), disk.messages(), task)
                            .handle((result, failure) -> {
                                if (failure == null && result != null && result.ok()) {
                                    return CompletableFuture.completedFuture(result);
                                }
                                String detail = failure == null
                                    ? (result == null ? "资源同步返回空结果。" : String.join("；", result.messages()))
                                    : "CraftEngine 资源同步失败：" + failure.getMessage();
                                return rollbackAndFailure(task, transaction, detail + " 已回滚配置与 HUD 覆盖层。");
                            }).thenCompose(future -> future);
                    });
            });
        }).exceptionallyCompose(failure -> {
            SaveTransaction transaction = transactionRef.get();
            if (transaction == null) {
                return CompletableFuture.completedFuture(failedFromThrowable(failure));
            }
            return rollbackAndFailure(task, transaction,
                "HUD 保存流程异常：" + rootCauseMessage(failure) + " 已回滚配置与全部 HUD 覆盖层。");
        });
    }

    private SaveTransaction captureTransaction(Path configPath, Path overlayRoot) throws java.io.IOException {
        boolean configExists = Files.isRegularFile(configPath);
        byte[] configBytes = configExists ? Files.readAllBytes(configPath) : new byte[0];
        Map<String, Object> configRoot;
        HudResourceRequest previousRequest = plugin.getHudOverlayRuntimeState() == null
            ? null : plugin.getHudOverlayRuntimeState().verifiedRequest();
        synchronized (plugin.hudWebConfigLock()) {
            configRoot = deepCopyRoot(plugin.yamlConfig().rawRoot());
        }
        return new SaveTransaction(configPath, configExists, configBytes, configRoot, previousRequest,
            overlayRoot, overlayWriter.capture(overlayRoot), null, null);
    }

    private static void restoreConfigBytes(Path path, byte[] bytes) throws java.io.IOException {
        Files.createDirectories(path.getParent());
        Path temp = Files.createTempFile(path.getParent(), "config.yml.rollback.", ".tmp");
        try {
            Files.write(temp, bytes);
            try {
                Files.move(temp, path, java.nio.file.StandardCopyOption.ATOMIC_MOVE,
                    java.nio.file.StandardCopyOption.REPLACE_EXISTING);
            } catch (java.nio.file.AtomicMoveNotSupportedException exception) {
                Files.move(temp, path, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
            }
        } finally {
            Files.deleteIfExists(temp);
        }
    }

    private CompletableFuture<ApplyResult> rollbackAndFailure(
        HudWebApplyTaskGate.Task<ApplyResult> task, SaveTransaction transaction, String detail) {
        return CompletableFuture.runAsync(() -> {
            try {
                Boolean expectedExists = transaction.expectedConfigExists();
                byte[] expected = transaction.expectedConfigBytes();
                if (expectedExists != null) {
                    boolean currentExists = Files.isRegularFile(transaction.configPath());
                    byte[] current = currentExists ? Files.readAllBytes(transaction.configPath()) : new byte[0];
                    if (expectedExists == currentExists
                        && (!currentExists || java.util.Arrays.equals(expected, current))) {
                        if (transaction.configExists()) {
                            restoreConfigBytes(transaction.configPath(), transaction.configBytes());
                        } else {
                            Files.deleteIfExists(transaction.configPath());
                        }
                        controller.restoreWebFieldsInMemory(transaction.configRoot());
                    } else {
                        plugin.getLogger().warning("HUD 失败回滚检测到 config.yml 已被其它入口修改，保留非 Web 配置并跳过整文件覆盖。");
                    }
                } else {
                    // 磁盘重载未写 config.yml；失败时不回写旧整棵 root，避免抹掉并发配置变更。
                    plugin.getLogger().info("HUD 磁盘重载失败，未回写 config.yml，仅恢复自有 overlay 并清除 ready。");
                }
                overlayWriter.restore(transaction.overlayRoot(), transaction.overlayState());
            } catch (Exception rollbackFailure) {
                throw new java.util.concurrent.CompletionException(rollbackFailure);
            }
        }, executor).thenCompose(ignored -> {
            // 失活后的磁盘补偿仍须完成，但停服后不能再依赖一个可能永远不执行的主线程任务。
            if (!isTaskActive(task)) {
                return CompletableFuture.completedFuture(inactiveResult("任务已失效，迟到回滚结果未应用。"));
            }
            return runOnMain(() -> taskGate.runIfActiveAtomically(
            task,
            () -> {
                // 失败可能发生在 CraftEngine reload/generate/ZIP 校验之后，磁盘回滚不等于
                // CE 内存内容已恢复。旧 request 不能重新标记 ready，否则运行态会继续渲染
                // 与客户端/CE 实际内容不一致的字形；必须等下一次完整校验成功后再 ready。
                plugin.getHudOverlayRuntimeState().clear();
                    plugin.applyHudRuntimeStateFromWeb();
                return ApplyResult.failed(controller.snapshot(), List.of(detail));
            },
            () -> inactiveResult("任务已失效，迟到回滚结果未应用。")
            ));
        }).exceptionally(failure -> {
            String message = detail + " 但补偿失败：" + rootCauseMessage(failure);
            plugin.getLogger().warning(message);
            return ApplyResult.failed(controller.snapshot(), List.of(message));
        });
    }

    private CompletableFuture<ApplyResult> reloadPipeline(HudWebApplyTaskGate.Task<ApplyResult> task) {
        java.util.concurrent.atomic.AtomicReference<SaveTransaction> transactionRef =
            new java.util.concurrent.atomic.AtomicReference<>();
        return resolveOnMainThread(task).thenCompose(resolved -> {
            if (!isTaskActive(task)) {
                return CompletableFuture.completedFuture(inactiveResult("未重载配置。"));
            }
            return CompletableFuture.supplyAsync(() -> {
                try {
                    SaveTransaction transaction = captureTransaction(resolved.configPath(), resolved.root());
                    transactionRef.set(transaction);
                    if (!isTaskActive(task)) {
                        return new ReloadStage(transaction, null);
                    }
                    HudResourceRequest request = controller.reloadResourcesFromDiskForWeb();
                    return new ReloadStage(transaction, request);
                } catch (java.io.IOException exception) {
                    throw new java.util.concurrent.CompletionException(exception);
                }
            }, executor).thenCompose(stage -> {
                if (stage == null || stage.request() == null || !isTaskActive(task)) {
                    return CompletableFuture.completedFuture(inactiveResult("未写入连续 HUD 覆盖层。"));
                }
                HudResourceRequest request = stage.request();
                return overlayWriter.writeAsync(resolved.root(), request, executor,
                        () -> isTaskActive(task)).thenCompose(written -> {
                    if (!isTaskActive(task)) {
                        return rollbackAndFailure(task, stage.transaction(), "任务已失效，已恢复运行态配置与全部 HUD 覆盖层。");
                    }
                    if (!written) {
                        return rollbackAndFailure(task, stage.transaction(), "写出连续 HUD 覆盖层失败，未触发 CraftEngine 重载。");
                    }
                    return resourceAndApply(resolved.bridge(), request, List.of(),
                        List.of("已从磁盘重新读取 HUD 配置。"), task)
                        .handle((result, failure) -> {
                            if (failure == null && result != null && result.ok()) {
                                return CompletableFuture.completedFuture(result);
                            }
                            String detail = failure == null
                                ? (result == null ? "资源同步返回空结果。" : String.join("；", result.messages()))
                                : "CraftEngine 资源同步失败：" + failure.getMessage();
                            return rollbackAndFailure(task, stage.transaction(), detail + " 已恢复运行态配置与全部 HUD 覆盖层。");
                        }).thenCompose(future -> future);
                });
            });
        }).exceptionallyCompose(failure -> {
            SaveTransaction transaction = transactionRef.get();
            if (transaction == null) {
                return CompletableFuture.completedFuture(failedFromThrowable(failure));
            }
            return rollbackAndFailure(task, transaction,
                "HUD 磁盘重载流程异常：" + rootCauseMessage(failure) + " 已恢复配置与全部 HUD 覆盖层。");
        });
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

    private CompletableFuture<ApplyResult> resourceAndApply(HudResourcePackBridge selected,
                                                              HudResourceRequest request,
                                                              List<String> appliedKeys,
                                                              List<String> messages,
                                                              HudWebApplyTaskGate.Task<ApplyResult> task) {
        CompletableFuture<CompletableFuture<Void>> started = runOnMain(() -> {
            if (!isTaskActive(task)) {
                throw new IllegalStateException("HUD 资源任务已失效，未启动 CraftEngine 重载。");
            }
            // 三层 overlay 尚未完成真实重载、生成和校验，先清除统一 ready；失败时绝不虚报成功。
            plugin.getHudOverlayRuntimeState().clear();
            return selected.reloadGenerateAndVerify(request, executor, mainExecutor);
        });
        return started.thenCompose(resource -> {
            if (resource == null) {
                return failedFuture(new IllegalStateException("CraftEngine 资源任务返回空 Future。"));
            }
            return resource;
        }).thenCompose(ignored -> applyOnMain(request, appliedKeys, messages, task));
    }

    private CompletableFuture<ApplyResult> applyOnMain(HudResourceRequest request,
                                                        List<String> appliedKeys,
                                                        List<String> messages,
                                                        HudWebApplyTaskGate.Task<ApplyResult> task) {
        return runOnMain(() -> taskGate.runIfActiveAtomically(
            task,
            () -> {
                // 到这里才表示三层 CE reload/generate/ZIP 校验已结束；无客户端回执仍不宣称客户端已应用。
                plugin.getHudOverlayRuntimeState().markVerified(request);
                plugin.applyHudRuntimeStateFromWeb();
                List<String> resultMessages = new ArrayList<>(messages);
                resultMessages.add("服务端三层资源包内容已校验；CraftEngine 自动上传结果未确认，客户端待重新下载。");
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

    private static String rootCauseMessage(Throwable failure) {
        Throwable cause = failure;
        while ((cause instanceof java.util.concurrent.CompletionException
            || cause instanceof java.util.concurrent.ExecutionException)
            && cause.getCause() != null) {
            cause = cause.getCause();
        }
        String message = cause.getMessage();
        return message == null || message.isBlank() ? cause.getClass().getSimpleName() : message;
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

    @SuppressWarnings("unchecked")
    private static Map<String, Object> deepCopyRoot(Map<String, Object> source) {
        java.util.LinkedHashMap<String, Object> copy = new java.util.LinkedHashMap<>();
        for (Map.Entry<String, Object> entry : source.entrySet()) {
            Object value = entry.getValue();
            if (value instanceof Map<?, ?> map) {
                copy.put(entry.getKey(), deepCopyRoot((Map<String, Object>) map));
            } else if (value instanceof List<?> list) {
                copy.put(entry.getKey(), new ArrayList<>(list));
            } else {
                copy.put(entry.getKey(), value);
            }
        }
        return copy;
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
            plugin.getHudOverlayRuntimeState().clear();
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

    private record SaveTransaction(Path configPath, boolean configExists, byte[] configBytes,
                                   Map<String, Object> configRoot, HudResourceRequest previousRequest,
                                   Path overlayRoot, HudOverlayWriter.OverlayState overlayState,
                                   Boolean expectedConfigExists, byte[] expectedConfigBytes) {
        private SaveTransaction {
            configBytes = configBytes.clone();
            configRoot = deepCopyRoot(configRoot);
            expectedConfigBytes = expectedConfigBytes == null ? null : expectedConfigBytes.clone();
        }

        private SaveTransaction withExpectedConfigState(Boolean exists, byte[] bytes) {
            return new SaveTransaction(configPath, configExists, configBytes, configRoot, previousRequest,
                overlayRoot, overlayState, exists, bytes);
        }

        @Override
        public byte[] configBytes() {
            return configBytes.clone();
        }

        @Override
        public Boolean expectedConfigExists() {
            return expectedConfigExists;
        }

        @Override
        public byte[] expectedConfigBytes() {
            return expectedConfigBytes == null ? null : expectedConfigBytes.clone();
        }

        @Override
        public Map<String, Object> configRoot() {
            return deepCopyRoot(configRoot);
        }
    }

    private record DiskStage(SaveTransaction transaction, DebugHudConfigController.DiskSaveResult disk) {}

    private record ReloadStage(SaveTransaction transaction, HudResourceRequest request) {}

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
