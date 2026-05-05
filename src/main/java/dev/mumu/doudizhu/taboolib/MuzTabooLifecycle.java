package dev.mumu.doudizhu.taboolib;

import taboolib.common.LifeCycle;
import taboolib.common.platform.Awake;
import taboolib.common.platform.function.IOKt;

/**
 * Minimal TabooLib lifecycle hook for MUZ.
 *
 * The plugin still uses Paper's JavaPlugin entry for its Paper-only game runtime,
 * while this class makes the packaged TabooLib runtime participate in the plugin lifecycle.
 */
public final class MuzTabooLifecycle {
    private MuzTabooLifecycle() {
    }

    @Awake(LifeCycle.ENABLE)
    public static void onTabooEnable() {
        IOKt.info("MUZ TabooLib runtime enabled.");
    }

    @Awake(LifeCycle.DISABLE)
    public static void onTabooDisable() {
        IOKt.info("MUZ TabooLib runtime disabled.");
    }
}
