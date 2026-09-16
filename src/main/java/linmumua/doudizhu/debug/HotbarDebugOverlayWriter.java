package linmumua.doudizhu.debug;

import linmumua.doudizhu.DoudizhuPlugin;
import linmumua.doudizhu.assets.PackAssets;
import org.bukkit.Bukkit;
import org.bukkit.plugin.Plugin;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Objects;

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
     * 写的字面量 {@code -100} 必须一致。
     *
     * <p>覆盖层的 ascent = 这个基准 - {@code offset-y}，所以 {@code offset-y = 0} 时
     * 覆盖层与 bundle 位置完全重合，拖动才有一个可预期的原点。
     */
    public static final int BASE_ASCENT = -100;

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

    /** 指定 hotbar scale 的合法下界；ascent 必须不大于该档位的字形 height。 */
    public static int minOffsetY(int scale) {
        return PackAssets.hotbarTier(scale).minOffsetY();
    }

    /** {@code offset-y} 的合法上界（默认 100% 档，兼容旧调用方）。 */
    public static int maxOffsetY() {
        return maxOffsetY(PackAssets.HOTBAR_DEFAULT_SCALE);
    }

    /** 指定 hotbar scale 的合法上界。 */
    public static int maxOffsetY(int scale) {
        return PackAssets.hotbarTier(scale).maxOffsetY();
    }

    /** 把默认 100% 档的 {@code offset-y} 钳位，保留旧调用方行为。 */
    public static int clampOffsetY(int offsetY) {
        return clampOffsetY(offsetY, PackAssets.HOTBAR_DEFAULT_SCALE);
    }

    /** 把指定 hotbar scale 的 {@code offset-y} 钳到该档位的合法区间。 */
    public static int clampOffsetY(int offsetY, int scale) {
        return Math.max(minOffsetY(scale), Math.min(maxOffsetY(scale), offsetY));
    }

    /** 给定默认 100% 档的 {@code offset-y} 算出要写进 images.yml 的 ascent。 */
    public static int ascentFor(int offsetY) {
        return ascentFor(offsetY, PackAssets.HOTBAR_DEFAULT_SCALE);
    }

    /** 给定指定 scale 的 {@code offset-y} 算出要写进 images.yml 的 ascent。 */
    public static int ascentFor(int offsetY, int scale) {
        PackAssets.HotbarTier tier = PackAssets.hotbarTier(scale);
        return tier.baseAscent() - clampOffsetY(offsetY, scale);
    }

    /**
     * 生成覆盖层的 {@code images.yml} 正文。
     *
     * <p>做成静态纯函数是为了能直接单测：断言 ascent 与 offsetY 的换算关系、
     * height 恒等于贴图原生高、以及码位与 {@link PackAssets#HOTBAR_HUD_DEBUG_CODEPOINT}
     * 对齐 —— 这些正是构建期/运行期双向约定的守护点，埋进需要活 plugin 实例的私有方法里
     * 就只能靠肉眼审查了。
     *
     * @param offsetY 垂直偏移，正数向下；内部会钳位
     */
    public static String buildImagesYaml(int offsetY) {
        return buildImagesYaml(offsetY, PackAssets.HOTBAR_DEFAULT_SCALE);
    }

    /** 生成指定 hotbar scale 的三图标与选中框 overlay 声明。 */
    public static String buildImagesYaml(int offsetY, int scale) {
        PackAssets.HotbarTier tier = PackAssets.hotbarTier(scale);
        int clamped = clampOffsetY(offsetY, scale);
        int ascent = ascentFor(clamped, scale);
        // char 用 \\uXXXX 转义写进 YAML：CraftEngine 按转义序列解析，避免 PUA 字符被编辑器误改。
        String suffix = scale == PackAssets.HOTBAR_DEFAULT_SCALE ? "" : "_s" + scale;
        StringBuilder yaml = new StringBuilder()
            .append("# 【运行期生成，不要手改】由 MUZ 的 HotbarDebugOverlayWriter 按\n")
            .append("# hotbar-hud.offset-y 写出，每次在 Debug Web 保存垂直偏移都会覆盖这个文件。\n")
            .append("# scale = ").append(scale).append("%，ascent = ").append(tier.baseAscent())
            .append(" - offset-y(").append(clamped).append(") = ").append(ascent).append("\n")
            .append("# 三张独立图标共用 bundle PNG；高度恒等于原生贴图高，保证只位移不缩放。\n")
            .append("images:\n");
        for (int index = 0; index < PackAssets.HOTBAR_ICON_COUNT; index++) {
            yaml.append("  ").append(OVERLAY_NAMESPACE).append(":hotbar_")
                .append(new String[] {"egg", "water", "tomato"}[index]).append("_debug").append(suffix).append(":\n")
                .append("    height: ").append(PackAssets.hotbarIconHeight(scale)).append("\n")
                .append("    ascent: ").append(ascent).append("\n")
                .append("    font: ").append(tier.font()).append("\n")
                .append("    file: ").append(PackAssets.hotbarIconTexture(index, scale)).append("\n")
                .append("    char: ").append(String.format("\\u%04x", tier.debugCodepoint() + index)).append("\n");
        }
        yaml.append("  ").append(OVERLAY_NAMESPACE).append(":hotbar_select_debug").append(suffix).append(":\n")
            .append("    height: ").append(tier.selectHeight()).append("\n")
            .append("    ascent: ").append(ascent).append("\n")
            .append("    font: ").append(tier.font()).append("\n")
            .append("    file: ").append(tier.selectTexture()).append("\n")
            .append("    char: ").append(String.format("\\u%04x", tier.selectDebugCodepoint())).append("\n");
        return yaml.toString();
    }

    /** 生成覆盖层的 {@code pack.yml} 正文。 */
    public static String buildPackYaml() {
        return "author: linmumua\n"
            + "description: \"MUZ hotbar 调试覆盖层（运行期生成，仅 Debug Web 调试用）\"\n"
            + "namespace: " + OVERLAY_NAMESPACE + "\n";
    }

    /**
     * 解析覆盖层目录。必须在主线程调用，异步写入阶段只使用返回的 Path，避免异步访问 Bukkit PluginManager。
     */
    public Path resolveOverlayRoot() {
        return overlayRoot();
    }

    /**
     * 在指定异步执行器中等待写出覆盖层资源。调用方负责后续在主线程触发 CraftEngine 重载。
     */
    public java.util.concurrent.CompletableFuture<Boolean> writeAsync(Path root, int offsetY,
                                                                       java.util.concurrent.Executor executor) {
        return writeAsync(root, offsetY, PackAssets.HOTBAR_DEFAULT_SCALE, executor, () -> true);
    }

    /** 带 scale 的兼容重载；覆盖层底图与选中框必须使用同一档位。 */
    public java.util.concurrent.CompletableFuture<Boolean> writeAsync(Path root, int offsetY, int scale,
                                                                       java.util.concurrent.Executor executor) {
        return writeAsync(root, offsetY, scale, executor, () -> true);
    }

    /**
     * 带任务有效性检查的异步写出。检查放在真正文件 I/O 所在线程，并且紧邻 writeNow，
     * 这样关闭或超时后已经排队但尚未开始的写盘不会继续落地旧状态。
     */
    public java.util.concurrent.CompletableFuture<Boolean> writeAsync(Path root, int offsetY, int scale,
                                                                       java.util.concurrent.Executor executor,
                                                                       java.util.function.BooleanSupplier active) {
        int clamped = clampOffsetY(offsetY, scale);
        return java.util.concurrent.CompletableFuture.supplyAsync(
            () -> active.getAsBoolean() && writeNow(root, clamped, scale), executor);
    }

    /** 旧参数顺序保留给已有调用点。 */
    public java.util.concurrent.CompletableFuture<Boolean> writeAsync(Path root, int offsetY,
                                                                       java.util.concurrent.Executor executor,
                                                                       java.util.function.BooleanSupplier active) {
        return writeAsync(root, offsetY, PackAssets.HOTBAR_DEFAULT_SCALE, executor, active);
    }


    /**
     * 同步写出覆盖层资源；仅保留给旧调用点，实际异步流程必须先在主线程解析 Path。
     *
     * @return 是否真的写成功；CraftEngine 缺失或写失败都返回 false
     */
    boolean writeNow(int offsetY) {
        return writeNow(offsetY, PackAssets.HOTBAR_DEFAULT_SCALE);
    }

    boolean writeNow(int offsetY, int scale) {
        if (!Bukkit.isPrimaryThread()) {
            plugin.getLogger().warning("异步写出 hotbar 调试覆盖层时未提供主线程解析的目录，已拒绝访问 CraftEngine PluginManager。");
            return false;
        }
        Path root = overlayRoot();
        if (root == null) {
            plugin.getLogger().info("CraftEngine 未检测到，跳过 hotbar 调试覆盖层生成。");
            return false;
        }
        return writeNow(root, offsetY, scale);
    }

    /**
     * 使用主线程预先解析的目录写出资源；异步阶段不得再调用 Bukkit PluginManager。
     */
    boolean writeNow(Path root, int offsetY) {
        return writeNow(root, offsetY, PackAssets.HOTBAR_DEFAULT_SCALE);
    }

    boolean writeNow(Path root, int offsetY, int scale) {
        if (root == null) {
            return false;
        }
        Path imagesDirectory = root.resolve("configuration").resolve("images");
        Path imagesFile = imagesDirectory.resolve("hotbar_debug.yml");
        Path imagesTemp = null;
        try {
            Files.createDirectories(imagesDirectory);
            // 只原子替换运行期 images 文件；resources/muz/pack.yml 属于正式 bundle，不得被覆盖。
            imagesTemp = Files.createTempFile(imagesDirectory, "hotbar_debug.yml.", ".tmp");
            Files.writeString(imagesTemp, buildImagesYaml(offsetY, scale), StandardCharsets.UTF_8);
            atomicReplace(imagesTemp, imagesFile);
            plugin.getLogger().info("hotbar 调试覆盖层已写出，scale=" + scale + "%，offset-y="
                + clampOffsetY(offsetY, scale) + "（ascent=" + ascentFor(offsetY, scale) + "）：" + root);
            return true;
        } catch (Exception exception) {
            try {
                if (imagesTemp != null) {
                    Files.deleteIfExists(imagesTemp);
                }
            } catch (Exception cleanupException) {
                plugin.getLogger().warning("清理 hotbar 调试覆盖层临时文件失败：" + cleanupException.getMessage());
            }
            // 不吞异常：写失败时垂直偏移不会生效，必须让服主看到原因
            plugin.getLogger().warning("写出 hotbar 调试覆盖层失败：" + exception.getMessage());
            return false;
        }
    }

    private static void atomicReplace(Path source, Path target) throws IOException {
        try {
            Files.move(source, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        } catch (java.nio.file.AtomicMoveNotSupportedException exception) {
            Files.move(source, target, StandardCopyOption.REPLACE_EXISTING);
        }
    }

    /** 在保存事务开始前捕获当前 overlay 文件，供后续资源失败时补偿。 */
    OverlayFileState capture(Path root) throws IOException {
        Path file = overlayFile(root);
        return Files.isRegularFile(file)
            ? new OverlayFileState(true, Files.readAllBytes(file))
            : new OverlayFileState(false, new byte[0]);
    }

    /** 原子恢复保存前的 overlay；不存在的旧文件会被删除。 */
    void restore(Path root, OverlayFileState state) throws IOException {
        Objects.requireNonNull(state, "state");
        Path file = overlayFile(root);
        if (!state.exists()) {
            Files.deleteIfExists(file);
            return;
        }
        Files.createDirectories(file.getParent());
        Path temp = Files.createTempFile(file.getParent(), "hotbar_debug.yml.rollback.", ".tmp");
        try {
            Files.write(temp, state.bytes());
            atomicReplace(temp, file);
        } finally {
            Files.deleteIfExists(temp);
        }
    }

    private static Path overlayFile(Path root) {
        return root.resolve("configuration").resolve("images").resolve("hotbar_debug.yml");
    }

    record OverlayFileState(boolean exists, byte[] bytes) {
        OverlayFileState {
            bytes = bytes.clone();
        }

        @Override
        public byte[] bytes() {
            return bytes.clone();
        }
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
