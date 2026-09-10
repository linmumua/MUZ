package linmumua.doudizhu.compat;

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
     * 重载、生成并校验资源包。返回 Future 完成前不得释放资源任务租约。
     */
    CompletableFuture<Void> reloadGenerateAndVerify(int offsetY, Executor ioExecutor, Executor mainExecutor);
}
