package linmumua.doudizhu.debug;

import linmumua.doudizhu.DoudizhuPlugin;
import linmumua.doudizhu.assets.HudOverlayLayout;
import linmumua.doudizhu.assets.HudResourceRequest;
import linmumua.doudizhu.assets.PackAssets;
import org.bukkit.plugin.Plugin;

import java.nio.file.Path;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import java.util.function.BooleanSupplier;

/**
 * 把 {@code hotbar-hud.offset-y} 写成一份 CraftEngine「调试覆盖层」资源，
 * 让 hotbar 底图的垂直位置能在运行期连续调整。
 *
 * <h2>为什么垂直方向必须走资源包，而水平方向不用</h2>
 *
 * <p>水平位移靠 CraftEngine 的负空格字形实现，是纯文本手段，运行期拼一段 MiniMessage
 * 就能生效。垂直位移没有这种手段：位图字形的纵向位置由 {@code ascent} 决定，而
 * {@code ascent} 是资源包里 {@code images.yml} 的字段，属于【客户端资源内容】。
 * 所以要连续调垂直位置，只能运行期重写那份 YAML 并让客户端重新下载资源包。
 *
 * <p>顺带排除两个看起来可行、其实不对的做法（本类不采用）：
 * <ul>
 *   <li>改小 {@code height} —— {@code height} 是【渲染高】不是裁剪高，改它会把贴图
 *       缩放，不是平移；</li>
 *   <li>在 PNG 上下补透明行 —— 字形盒顶边由 {@code ascent} 锁定，补在底部图案根本不动，
 *       补在顶部则要同步加大 {@code height}，又变回缩放问题。</li>
 * </ul>
 * 这个结论在 {@code PackAssets} 的注释里已有记录（「ascent 减小就行，能复用同一张贴图；
 * 向上则要求 height 跟着涨，那等于把牌拉伸，不是纯位移」）。
 *
     * <h2>为什么写入 muz bundle 目录</h2>
     *
     * <p>运行期 overlay 直接写入 {@code resources/muz/configuration/images/hotbar_debug.yml}，
     * 与正式 bundle 的 {@code hotbar_hud.yml} 分离；导出器会保留这一份受保护的运行期文件，
     * 不会把它当成清单残留删除，也不会覆盖正式 {@code pack.yml}。

 *
 * <p>覆盖层【只生成 YAML，不生成 PNG】：三张图标与选中框都直接引用 bundle 提供的
 * 同源 PNG，所以绘图逻辑仍然只有构建期一份，不存在两处画图代码要同步。
 */
public final class HotbarDebugOverlayWriter {
    private static final String CRAFT_ENGINE_PLUGIN = "CraftEngine";

    /** 覆盖层的命名空间与目录名，刻意与 bundle 的 {@code muz} 区分开。 */
    private static final String OVERLAY_NAMESPACE = "muz";

    /**
     * bundle 内烘焙的基准 ascent，与 build.gradle.kts 生成 {@code hotbar_hud.yml} 时
     * 使用的 {@code hotbarBaseAscent} 以及 {@link PackAssets#HOTBAR_BASE_ASCENT} 必须一致。
     *
     * <p>覆盖层的 ascent = 这个基准 - {@code offset-y}，所以 {@code offset-y = 0} 时
     * 覆盖层与 bundle 位置完全重合，拖动才有一个可预期的原点。
     */
    public static final int BASE_ASCENT = PackAssets.HOTBAR_BASE_ASCENT;

    /**
     * 贴图原生高度（像素），必须等于三张 Hotbar 图标与选中框 PNG 的真实高度。
     *
     * <p>{@code height} 恒等于原生高 = 1:1 渲染，不缩放。这是本方案「纯位移」的前提：
     * 只要 {@code height} 不动，改 {@code ascent} 就只改位置、不改大小。
     */
    public static final int GLYPH_HEIGHT = 22;

    private final DoudizhuPlugin plugin;

    public HotbarDebugOverlayWriter(DoudizhuPlugin plugin) {
        this.plugin = Objects.requireNonNull(plugin, "plugin");
    }

    /** {@code offset-y} 的合法下界（默认 100% 档，兼容旧调用方）。 */
    public static int minOffsetY() {
        return minOffsetY(PackAssets.HOTBAR_DEFAULT_SCALE);
    }

    /** 指定 hotbar scale 的合法下界；连续布局会为向上偏移补底部透明行。 */
    public static int minOffsetY(int scale) {
        return PackAssets.minHotbarOffsetY(scale);
    }

    /** {@code offset-y} 的合法上界（默认 100% 档，兼容旧调用方）。 */
    public static int maxOffsetY() {
        return maxOffsetY(PackAssets.HOTBAR_DEFAULT_SCALE);
    }

    /** 指定 hotbar scale 的合法上界。 */
    public static int maxOffsetY(int scale) {
        return PackAssets.maxHotbarOffsetY(scale);
    }

    /** 保留旧名称，但不再静默钳位；越界配置必须明确拒绝。 */
    public static int clampOffsetY(int offsetY) {
        return clampOffsetY(offsetY, PackAssets.HOTBAR_DEFAULT_SCALE);
    }

    /** 保留旧名称，但边界改为严格校验，避免写出未生成字形。 */
    public static int clampOffsetY(int offsetY, int scale) {
        if (offsetY < minOffsetY(scale) || offsetY > maxOffsetY(scale)) {
            throw new IllegalArgumentException("hotbar offset-y 超出连续资源范围（"
                + minOffsetY(scale) + ".." + maxOffsetY(scale) + "）：" + offsetY);
        }
        return offsetY;
    }

    /** 给定默认 100% 档的 {@code offset-y} 算出要写进 images.yml 的 ascent。 */
    public static int ascentFor(int offsetY) {
        return ascentFor(offsetY, PackAssets.HOTBAR_DEFAULT_SCALE);
    }

    /** 给定指定 scale 的 {@code offset-y} 算出要写进 images.yml 的 ascent。 */
    public static int ascentFor(int offsetY, int scale) {
        HudResourceRequest request = new HudResourceRequest(0, 0, 0, clampOffsetY(offsetY, scale), scale);
        return HudOverlayLayout.hotbarGlyphs(request).stream()
            .findFirst()
            .orElseThrow(() -> new IllegalStateException("连续布局未返回 hotbar 字形"))
            .ascent();
    }

    /**
     * 生成覆盖层的 {@code images.yml} 正文。
     *
     * <p>做成静态纯函数是为了能直接单测：断言 ascent 与 offsetY 的换算关系、
     * height 恒等于贴图原生高、以及码位与 {@link PackAssets#HOTBAR_HUD_DEBUG_CODEPOINT}
     * 对齐 —— 这些正是构建期/运行期双向约定的守护点，埋进需要活 plugin 实例的私有方法里
     * 就只能靠肉眼审查了。
     *
     * @param offsetY 垂直偏移，正数向下；越界会明确拒绝
     */
    public static String buildImagesYaml(int offsetY) {
        return buildImagesYaml(offsetY, PackAssets.HOTBAR_DEFAULT_SCALE);
    }

    /** 生成指定 hotbar scale 的三图标与选中框 overlay 声明。 */
    public static String buildImagesYaml(int offsetY, int scale) {
        int valid = clampOffsetY(offsetY, scale);
        HudResourceRequest request = new HudResourceRequest(0, 0, 0, valid, scale);
        return HudOverlayWriter.buildImagesYaml(HudOverlayLayout.hotbarGlyphs(request));
    }

    /** 生成覆盖层的 {@code pack.yml} 正文。 */
    public static String buildPackYaml() {
        return HudOverlayWriter.buildPackYaml();
    }

    /**
     * 解析覆盖层目录。必须在主线程调用，异步写入阶段只使用返回的 Path，避免异步访问 Bukkit PluginManager。
     */
    public Path resolveOverlayRoot() {
        return overlayRoot();
    }

    /**
     * 完整资源快照写入口；HotbarDebugOverlayWriter 不再接受 hotbar-only 写盘请求，
     * 避免把 card/avatar/counter 的当前状态重置为 0。
     */
    public CompletableFuture<Boolean> writeAsync(Path root, HudResourceRequest request, Executor executor,
                                                   BooleanSupplier active) {
        return new HudOverlayWriter(plugin).writeAsync(root, Objects.requireNonNull(request, "request"),
            executor, active);
    }

    /** 旧 hotbar-only 写入口明确拒绝，调用方必须改用完整 HudResourceRequest。 */
    @Deprecated
    public CompletableFuture<Boolean> writeAsync(Path root, int offsetY, int scale, Executor executor,
                                                  BooleanSupplier active) {
        return CompletableFuture.failedFuture(new UnsupportedOperationException(
            "Hotbar-only 覆盖层写入已禁用，请传入完整 HudResourceRequest"));
    }

    @Deprecated
    public CompletableFuture<Boolean> writeAsync(Path root, int offsetY, Executor executor) {
        return writeAsync(root, offsetY, PackAssets.HOTBAR_DEFAULT_SCALE, executor, () -> true);
    }

    @Deprecated
    public CompletableFuture<Boolean> writeAsync(Path root, int offsetY, Executor executor,
                                                  BooleanSupplier active) {
        return writeAsync(root, offsetY, PackAssets.HOTBAR_DEFAULT_SCALE, executor, active);
    }

    /** 旧 hotbar-only 同步入口明确拒绝，防止覆盖其它三层。 */
    @Deprecated
    boolean writeNow(int offsetY) {
        throw new UnsupportedOperationException("Hotbar-only 覆盖层写入已禁用，请传入完整 HudResourceRequest");
    }

    @Deprecated
    boolean writeNow(int offsetY, int scale) {
        throw new UnsupportedOperationException("Hotbar-only 覆盖层写入已禁用，请传入完整 HudResourceRequest");
    }

    @Deprecated
    boolean writeNow(Path root, int offsetY) {
        throw new UnsupportedOperationException("Hotbar-only 覆盖层写入已禁用，请传入完整 HudResourceRequest");
    }

    @Deprecated
    boolean writeNow(Path root, int offsetY, int scale) {
        throw new UnsupportedOperationException("Hotbar-only 覆盖层写入已禁用，请传入完整 HudResourceRequest");
    }

    /**
     * 覆盖层目录；CraftEngine 未安装时返回 null。
     *
     * <p>路径取 {@code plugins/CraftEngine/resources/muz}，与正式 bundle 共用目录，
     * 文件名使用 {@code configuration/images/hotbar_debug.yml}，由导出器专门保留。
     */
    private Path overlayRoot() {
        Plugin craftEngine = plugin.getServer().getPluginManager().getPlugin(CRAFT_ENGINE_PLUGIN);
        if (craftEngine == null || !craftEngine.isEnabled()) {
            return null;
        }
        return craftEngine.getDataFolder().toPath().resolve("resources").resolve(OVERLAY_NAMESPACE);
    }

}
