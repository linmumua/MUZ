package linmumua.doudizhu.compat;

import linmumua.doudizhu.DoudizhuPlugin;
import java.lang.reflect.Method;
import org.bukkit.Bukkit;
import org.bukkit.plugin.Plugin;

/**
 * 借 CraftEngine 的负空格字形做像素级水平定位。
 *
 * <p>为什么必须借：把头像和牌排进同一行文本后，头像的第二行要回到行首才能画，
 * 这需要「负宽度的空格」。Minecraft 原版靠字体的 space provider 实现，但那要求
 * 覆写 assets/minecraft/font/default.json —— 而 default.json 已经被 CraftEngine
 * 用来注册我们的牌面与方块字形了，抢着写必然冲突。CraftEngine 自己就带了一套
 * 偏移字形（内置资源包里的 font/offset/*），并暴露成 MiniMessage 的 shift 标签，
 * 直接复用它比自己造一套安全。
 *
 * <p>拿到的是一段【标准 MiniMessage】文本（形如 {@code <font:...>某些字符</font>}），
 * 所以可以交给 Paper 自带的 MiniMessage 解析，不需要碰 CraftEngine 内部那套被
 * 重定位过的 Adventure 类（{@code craftengine.libraries.adventure.*} 和插件用的
 * {@code net.kyori.adventure.*} 是两个不同的类，Component 没法直接互传）。
 *
 * <p>整个类是「取不到就降级」的：CraftEngine 缺失或换了内部结构时不抛异常，
 * 只是偏移变成空串（头像会挤成一坨而不是整个功能崩掉），并且只警告一次。
 */
public final class CraftEngineOffsetService {
    private final DoudizhuPlugin plugin;

    /** CraftEngine 的 FontManager 实例，反射拿到后一直复用。 */
    private Object fontManager;

    /** {@code FontManager.createMiniMessageOffsets(int)}，把像素偏移量转成 MiniMessage。 */
    private Method createMiniMessageOffsetsMethod;

    private boolean initialised;

    /** 只警告一次，避免每帧刷屏。 */
    private boolean warned;

    public CraftEngineOffsetService(DoudizhuPlugin plugin) {
        this.plugin = plugin;
    }

    /**
     * 取一段把光标水平移动 {@code pixels} 像素的 MiniMessage 文本。
     *
     * @param pixels 正数右移、负数左移；0 直接返回空串
     * @return 可以直接拼进 MiniMessage 的文本；CraftEngine 不可用时返回空串
     */
    public String offset(int pixels) {
        if (pixels == 0) {
            return "";
        }
        if (!initialised) {
            initialised = true;
            resolveFontManager();
        }
        if (fontManager == null || createMiniMessageOffsetsMethod == null) {
            return "";
        }
        try {
            Object result = createMiniMessageOffsetsMethod.invoke(fontManager, pixels);
            return result == null ? "" : result.toString();
        } catch (Exception exception) {
            warnOnce("CraftEngine offset lookup failed: " + exception.getMessage());
            return "";
        }
    }

    /** CraftEngine 在线且偏移可用时为 true，调用方可以据此决定要不要画头像。 */
    public boolean isAvailable() {
        if (!initialised) {
            initialised = true;
            resolveFontManager();
        }
        return fontManager != null && createMiniMessageOffsetsMethod != null;
    }

    private void resolveFontManager() {
        Plugin craftEngine = Bukkit.getPluginManager().getPlugin("CraftEngine");
        if (craftEngine == null || !craftEngine.isEnabled()) {
            warnOnce("CraftEngine is not enabled, avatar offsets are disabled.");
            return;
        }
        try {
            ClassLoader loader = craftEngine.getClass().getClassLoader();
            Class<?> craftEngineClass =
                Class.forName("net.momirealms.craftengine.core.plugin.CraftEngine", true, loader);
            Class<?> fontManagerClass =
                Class.forName("net.momirealms.craftengine.core.font.FontManager", true, loader);
            Object instance = craftEngineClass.getMethod("instance").invoke(null);
            if (instance == null) {
                warnOnce("CraftEngine instance is not ready, avatar offsets are disabled.");
                return;
            }
            Object manager = craftEngineClass.getMethod("fontManager").invoke(instance);
            if (manager == null) {
                warnOnce("CraftEngine font manager is missing, avatar offsets are disabled.");
                return;
            }
            createMiniMessageOffsetsMethod =
                fontManagerClass.getMethod("createMiniMessageOffsets", int.class);
            fontManager = manager;
        } catch (Exception exception) {
            warnOnce("CraftEngine offset service unavailable: " + exception.getMessage());
        }
    }

    private void warnOnce(String message) {
        if (warned) {
            return;
        }
        warned = true;
        plugin.getLogger().warning(message);
    }
}
