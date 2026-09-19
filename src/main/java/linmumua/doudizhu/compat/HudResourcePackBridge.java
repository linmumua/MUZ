package linmumua.doudizhu.compat;

import linmumua.doudizhu.assets.HotbarFontMetrics;
import linmumua.doudizhu.assets.HudResourceRequest;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;

/**
 * HUD 资源包同步桥接的最小运行期契约。
 *
 * <p>接口本身不引用 CraftEngine 类型，允许插件在 CraftEngine 缺失时继续加载；直接 API
 * 只存在于 {@link CraftEngineHudResourceBridge}，并且在确认依赖启用后才惰性创建。
 */
public interface HudResourcePackBridge {
    /**
     * 在主线程调用，返回当前不能开始资源任务的原因；返回 {@code null} 表示可以开始。
     */
    String preflightFailureOnMainThread();

    /**
     * 重载、生成并校验四层 HUD 资源包。返回 Future 完成前不得释放资源任务租约。
     * request 必须贯穿 card/avatar/counter/hotbar 四层，任何旧的 hotbar-only 实现都必须失败关闭。
     */
    CompletableFuture<Void> reloadGenerateAndVerify(HudResourceRequest request,
                                                     Executor ioExecutor, Executor mainExecutor);

    /**
     * 在资源 ZIP 已通过完整校验后异步加载客户端 Hotbar 字体快照；未知目标允许返回空。
     * 快照完成前不得发布到 HudOverlayRuntimeState。
     */
    default CompletableFuture<HotbarFontMetrics> loadVerifiedHotbarFontMetrics(
        HudResourceRequest request, Executor ioExecutor) {
        return CompletableFuture.completedFuture(null);
    }

    /**
     * 旧 hotbar-only 签名只为源码迁移保留，默认明确失败关闭，禁止冒充完整四层验证。
     */
    @Deprecated
    default CompletableFuture<Void> reloadGenerateAndVerify(int offsetY, Executor ioExecutor,
                                                              Executor mainExecutor) {
        return failedFuture(new UnsupportedOperationException("HUD 资源桥接必须提供完整 HudResourceRequest 校验。"));
    }

    /**
     * 旧带 scale 签名只为源码迁移保留，默认明确失败关闭。
     */
    @Deprecated
    default CompletableFuture<Void> reloadGenerateAndVerify(int offsetY, int hotbarScale,
                                                              Executor ioExecutor, Executor mainExecutor) {
        return failedFuture(new UnsupportedOperationException("HUD 资源桥接必须提供完整 HudResourceRequest 校验。"));
    }

    private static <T> CompletableFuture<T> failedFuture(Throwable throwable) {
        CompletableFuture<T> failed = new CompletableFuture<>();
        failed.completeExceptionally(throwable);
        return failed;
    }
}
