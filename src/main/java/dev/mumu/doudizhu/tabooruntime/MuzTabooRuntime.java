package dev.mumu.doudizhu.tabooruntime;

import java.util.logging.Level;
import java.util.logging.Logger;
import taboolib.common.LifeCycle;
import taboolib.common.PrimitiveLoader;
import taboolib.common.TabooLib;

/**
 * 驱动打包在 JAR 内的 TabooLib loader。
 *
 * MUZ 的 Bukkit 入口是自己的 JavaPlugin，不是 taboolib.platform.BukkitPlugin，
 * 因此 loader 不会被自动引导。这里手动执行引导并推进生命周期，
 * 让 TabooLib 的功能模块在首次启动时从远程仓库下载到 libraries 目录，
 * 而不是把这些模块直接打进插件 JAR。
 */
public final class MuzTabooRuntime {
    /* TabooLib 引导是否成功，失败时不再推进后续生命周期 */
    private static boolean bootstrapped;

    private MuzTabooRuntime() {
    }

    /**
     * 引导 TabooLib 并推进到 ENABLE 之前的生命周期
     * @param logger 用于输出引导结果的日志器
     */
    public static void bootstrap(Logger logger) {
        try {
            PrimitiveLoader.init();
            bootstrapped = true;
        } catch (Throwable throwable) {
            // 首次启动需要联网下载模块，失败时只降级掉 TabooLib，不影响斗地主本体。
            logger.log(Level.WARNING, "TabooLib 运行时加载失败，已跳过其功能模块。", throwable);
            for (Throwable cause = throwable.getCause(); cause != null; cause = cause.getCause()) {
                logger.log(Level.WARNING, "  根因: " + cause, cause);
            }
            return;
        }
        advance(logger, LifeCycle.CONST);
        advance(logger, LifeCycle.INIT);
        advance(logger, LifeCycle.LOAD);
        // 注解扫描在非 TabooLib 入口下不会启动，改为显式注册生命周期回调。
        MuzTabooLifecycle.register(
            () -> logger.info("MUZ TabooLib runtime enabled."),
            () -> logger.info("MUZ TabooLib runtime disabled.")
        );
    }

    /**
     * 推进 TabooLib 到启用阶段
     * @param logger 用于输出异常的日志器
     */
    public static void enable(Logger logger) {
        advance(logger, LifeCycle.ENABLE);
        advance(logger, LifeCycle.ACTIVE);
    }

    /**
     * 推进 TabooLib 到关闭阶段
     * @param logger 用于输出异常的日志器
     */
    public static void disable(Logger logger) {
        advance(logger, LifeCycle.DISABLE);
    }

    /**
     * 推进单个生命周期阶段
     * @param logger 用于输出异常的日志器
     * @param lifeCycle 目标生命周期
     */
    private static void advance(Logger logger, LifeCycle lifeCycle) {
        if (!bootstrapped) {
            return;
        }
        try {
            TabooLib.lifeCycle(lifeCycle);
        } catch (Throwable throwable) {
            logger.log(Level.WARNING, "TabooLib 生命周期 " + lifeCycle + " 执行失败。", throwable);
        }
    }
}
