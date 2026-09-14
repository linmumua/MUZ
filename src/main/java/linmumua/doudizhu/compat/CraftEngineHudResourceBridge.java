package linmumua.doudizhu.compat;

import linmumua.doudizhu.DoudizhuPlugin;
import linmumua.doudizhu.assets.PackAssets;
import net.momirealms.craftengine.core.plugin.CraftEngine;
import net.momirealms.craftengine.core.plugin.config.Config;

import java.io.IOException;
import java.nio.file.Path;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;

/**
 * CraftEngine HUD 资源包的直接 API 桥接。
 *
 * <p>这份桥接只在确认 CraftEngine 插件已启用后由 Debug Web 协调器惰性创建；类本身不参与
 * 插件生命周期，也不使用反射。CraftEngine 是 compileOnly 依赖，因此调用方必须把直接 API
 * 的创建包在 {@link LinkageError} 降级边界内，CraftEngine 缺失时不能影响 MUZ 其它功能。
 *
 * <p>一次资源任务的顺序固定为：CraftEngine 真实 reload Future 成功、异步生成资源包、校验
 * 生成路径和配置中的上传路径内容，全部完成后 Future 才成功。CraftEngine 的生成方法会
 * 吞掉部分 IOException，所以不能把 generateResourcePack() 正常返回当成成功证据。
 */
public final class CraftEngineHudResourceBridge implements HudResourcePackBridge {
    private static final String CRAFT_ENGINE_PLUGIN = "CraftEngine";

    private final DoudizhuPlugin plugin;
    private final ResourceVerifier verifier;
    private volatile EngineAccess engineAccess;
    private volatile boolean linkageFailureLogged;

    /**
     * 创建生产桥接。构造阶段不读取资源包文件；实际 CE API 访问延迟到
     * {@link #preflightFailureOnMainThread()} 或资源任务开始时。
     */
    public CraftEngineHudResourceBridge(DoudizhuPlugin plugin) {
        this(plugin, new HudResourcePackVerifier(plugin::getResource)::verify, null);
    }

    /**
     * 仅供同包测试注入纯 Java fake，避免测试运行时必须加载 CraftEngine compileOnly 类。
     */
    CraftEngineHudResourceBridge(DoudizhuPlugin plugin, ResourceVerifier verifier,
                                 EngineAccess engineAccess) {
        this.plugin = Objects.requireNonNull(plugin, "plugin");
        this.verifier = Objects.requireNonNull(verifier, "verifier");
        this.engineAccess = engineAccess;
    }

    @Override
    public String preflightFailureOnMainThread() {
        if (!isCraftEngineEnabled()) {
            return "CraftEngine 未启用，未写入 HUD 配置。";
        }
        try {
            EngineAccess access = resolveEngineAccess();
            if (access == null) {
                return "CraftEngine 实例尚未就绪，未写入 HUD 配置。";
            }
            if (access.isReloading()) {
                return "CraftEngine 资源仍在重载，未写入 HUD 配置。";
            }
            return null;
        } catch (LinkageError error) {
            logLinkageFailure(error);
            return "CraftEngine API 不兼容，已跳过 HUD 资源同步。";
        } catch (RuntimeException exception) {
            return "读取 CraftEngine 状态失败，未写入 HUD 配置：" + messageOf(exception);
        }
    }

    @Override
    public CompletableFuture<Void> reloadGenerateAndVerify(int offsetY, Executor ioExecutor,
                                                             Executor mainExecutor) {
        return reloadGenerateAndVerify(offsetY, PackAssets.HOTBAR_DEFAULT_SCALE, ioExecutor, mainExecutor);
    }

    @Override
    public CompletableFuture<Void> reloadGenerateAndVerify(int offsetY, int hotbarScale,
                                                             Executor ioExecutor, Executor mainExecutor) {
        Objects.requireNonNull(ioExecutor, "ioExecutor");
        Objects.requireNonNull(mainExecutor, "mainExecutor");

        final EngineAccess access;
        try {
            String failure = preflightFailureOnMainThread();
            if (failure != null) {
                return failedFuture(new IOException(failure));
            }
            access = resolveEngineAccess();
            if (access == null) {
                return failedFuture(new IOException("CraftEngine 实例尚未就绪。"));
            }
            // 二次检查与真正 reload 紧邻，避免 preflight 后被其它 CE 任务抢占。
            if (access.isReloading()) {
                return failedFuture(new IOException("CraftEngine 资源仍在重载。"));
            }
        } catch (LinkageError error) {
            logLinkageFailure(error);
            return failedFuture(new IOException("CraftEngine API 不兼容。", error));
        } catch (RuntimeException exception) {
            return failedFuture(new IOException("读取 CraftEngine 状态失败：" + messageOf(exception), exception));
        }

        HudResourcePackSync.Verifier syncVerifier = new HudResourcePackSync.Verifier() {
            @Override
            public void verify(Path packPath, int configuredOffsetY) throws IOException {
                verifier.verify(packPath, configuredOffsetY);
            }

            @Override
            public void verify(Path packPath, int configuredOffsetY, int configuredScale) throws IOException {
                verifier.verify(packPath, configuredOffsetY, configuredScale);
            }
        };
        return HudResourcePackSync.run(
            // overlay YAML 只有在 CE 真实 reload 完成后才会进入 pack manager；不能只生成旧内存快照。
            // 保存与磁盘重载都经过同一条 reload → generate → verify 链，避免“写成功但包未更新”。
            access::reload,
            access::generateResourcePack,
            access::generatedPackPath,
            access::uploadPackPath,
            syncVerifier,
            offsetY,
            hotbarScale,
            ioExecutor,
            mainExecutor
        );
    }

    private boolean isCraftEngineEnabled() {
        try {
            var detected = plugin.getServer().getPluginManager().getPlugin(CRAFT_ENGINE_PLUGIN);
            return detected != null && detected.isEnabled();
        } catch (LinkageError error) {
            logLinkageFailure(error);
            return false;
        }
    }

    private EngineAccess resolveEngineAccess() {
        EngineAccess current = engineAccess;
        if (current != null) {
            return current;
        }
        if (!isCraftEngineEnabled()) {
            return null;
        }
        CraftEngine engine = CraftEngine.instance();
        if (engine == null) {
            return null;
        }
        EngineAccess created = new DirectEngineAccess(engine);
        engineAccess = created;
        return created;
    }

    private void logLinkageFailure(LinkageError error) {
        if (linkageFailureLogged) {
            return;
        }
        linkageFailureLogged = true;
        plugin.getLogger().warning("CraftEngine HUD 资源桥接不可用，已降级：" + messageOf(error));
    }

    private static String messageOf(Throwable throwable) {
        String message = throwable.getMessage();
        return message == null || message.isBlank() ? throwable.getClass().getSimpleName() : message;
    }

    private static <T> CompletableFuture<T> failedFuture(Throwable throwable) {
        CompletableFuture<T> failed = new CompletableFuture<>();
        failed.completeExceptionally(throwable);
        return failed;
    }

    @FunctionalInterface
    interface ResourceVerifier {
        void verify(Path packPath, int offsetY) throws IOException;

        /** 旧 verifier 默认按 100% hotbar 档校验；生产实现可覆盖以校验指定档位。 */
        default void verify(Path packPath, int offsetY, int hotbarScale) throws IOException {
            verify(packPath, offsetY);
        }
    }

    interface EngineAccess {
        boolean isReloading();

        CompletableFuture<HudResourcePackSync.ReloadResult> reload(Executor ioExecutor, Executor mainExecutor);

        void generateResourcePack() throws Exception;

        Path generatedPackPath();

        Path uploadPackPath();
    }

    private static final class DirectEngineAccess implements EngineAccess {
        private final CraftEngine engine;

        private DirectEngineAccess(CraftEngine engine) {
            this.engine = engine;
        }

        @Override
        public boolean isReloading() {
            return engine.isReloading();
        }

        @Override
        public CompletableFuture<HudResourcePackSync.ReloadResult> reload(Executor ioExecutor,
                                                                            Executor mainExecutor) {
            return engine.reloadPlugin(ioExecutor, mainExecutor, false)
                .thenApply(result -> new HudResourcePackSync.ReloadResult(
                    result != null && result.success(), result == null ? "无结果" : result.toString()));
        }

        @Override
        public void generateResourcePack() throws Exception {
            engine.packManager().generateResourcePack();
        }

        @Override
        public Path generatedPackPath() {
            return engine.packManager().resourcePackPath();
        }

        @Override
        public Path uploadPackPath() {
            return Config.fileToUpload();
        }
    }
}
