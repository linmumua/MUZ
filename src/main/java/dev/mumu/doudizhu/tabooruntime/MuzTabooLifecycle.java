package dev.mumu.doudizhu.tabooruntime;

import taboolib.common.LifeCycle;
import taboolib.common.TabooLib;

/**
 * MUZ 的 TabooLib 生命周期挂钩。
 *
 * 插件入口仍是 Paper 的 JavaPlugin，因此 TabooLib 的 @Awake 注解扫描不会启动
 * （注解注入需要 taboolib.platform.BukkitPlugin 作为 main）。
 * 这里改用 registerLifeCycleTask 直接注册回调，不依赖注解扫描即可参与生命周期。
 */
public final class MuzTabooLifecycle {
    /* 回调注册优先级，数值越小越先执行 */
    private static final int TASK_PRIORITY = 0;

    private MuzTabooLifecycle() {
    }

    /**
     * 注册 TabooLib 生命周期回调
     * @param onEnable 启用阶段要执行的动作
     * @param onDisable 关闭阶段要执行的动作
     */
    public static void register(Runnable onEnable, Runnable onDisable) {
        TabooLib.registerLifeCycleTask(LifeCycle.ENABLE, TASK_PRIORITY, onEnable);
        TabooLib.registerLifeCycleTask(LifeCycle.DISABLE, TASK_PRIORITY, onDisable);
    }
}
