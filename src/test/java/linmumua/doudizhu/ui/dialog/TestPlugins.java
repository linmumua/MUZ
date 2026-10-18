package linmumua.doudizhu.ui.dialog;

import java.lang.reflect.Field;
import linmumua.doudizhu.DoudizhuPlugin;
import sun.misc.Unsafe;

/**
 * 测试用插件替身。
 *
 * <p>Dialog 屏的 {@code screenKey()} / {@code permission()} / 静态解析方法都不触达插件内部状态，
 * 因此用 {@link Unsafe} 直接分配一个「未装配」的 {@link DoudizhuPlugin} 实例即可满足构造器的
 * 非空校验，无需启动服务端。这与仓库既有的 {@code MuzCommandLifecycleBehaviorTest} 夹具同源。
 */
final class TestPlugins {

    private TestPlugins() {
    }

    static DoudizhuPlugin newInstance() {
        try {
            Field field = Unsafe.class.getDeclaredField("theUnsafe");
            field.setAccessible(true);
            Unsafe unsafe = (Unsafe) field.get(null);
            return (DoudizhuPlugin) unsafe.allocateInstance(DoudizhuPlugin.class);
        } catch (ReflectiveOperationException exception) {
            throw new IllegalStateException("无法用 Unsafe 分配 DoudizhuPlugin 测试替身", exception);
        }
    }
}
