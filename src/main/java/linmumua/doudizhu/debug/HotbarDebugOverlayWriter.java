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
 * <h2>为什么写在独立目录而不是覆盖 bundle</h2>
 *
 * <p>{@code CraftEngineBundleExporter} 的目标根目录是 {@code resources/muz}，它会
 * {@code Files.walk} 整棵子树，把【清单外】的文件当残留删掉、把清单内的文件用 jar 内版本
 * 覆盖回去。所以任何写进 {@code resources/muz/} 的运行期产物都活不过下一次导出。
 * 这里改写到 {@code resources/muz_hotbar_debug/}，在那棵子树之外，导出流程完全不会碰它。
 *
 * <p>覆盖层【只生成 YAML，不生成 PNG】：贴图直接引用 bundle 提供的
 * {@code muz:font/hotbar_slots.png}，所以绘图逻辑仍然只有构建期一份，不存在两处画图
 * 代码要同步。
 */
public final class HotbarDebugOverlayWriter {
    private static final String CRAFT_ENGINE_PLUGIN = "CraftEngine";

    /**
     * CraftEngine 26.x 的无参数 {@code ce reload} 只重载配置，不会重建客户端资源包。
     * 覆盖层改了字形 ascent，直接请求 {@code pack} 才会生成新的 resource_pack.zip。
     */
    static final String CRAFT_ENGINE_RELOAD_COMMAND = "ce reload pack";

    /** 覆盖层的命名空间与目录名，刻意与 bundle 的 {@code muz} 区分开。 */
    private static final String OVERLAY_NAMESPACE = "muz_hotbar_debug";

    /**
     * bundle 内烘焙的基准 ascent，与 build.gradle.kts 生成 {@code hotbar_hud.yml} 时
     * 写的字面量 {@code -128} 必须一致。
     *
     * <p>覆盖层的 ascent = 这个基准 - {@code offset-y}，所以 {@code offset-y = 0} 时
     * 覆盖层与 bundle 位置完全重合，拖动才有一个可预期的原点。
     */
    public static final int BASE_ASCENT = -128;

    /**
     * 贴图原生高度（像素），必须等于 {@code hotbar_slots.png} 的真实高度。
     *
     * <p>{@code height} 恒等于原生高 = 1:1 渲染，不缩放。这是本方案「纯位移」的前提：
     * 只要 {@code height} 不动，改 {@code ascent} 就只改位置、不改大小。
     */
    public static final int GLYPH_HEIGHT = 22;

    private final DoudizhuPlugin plugin;

    public HotbarDebugOverlayWriter(DoudizhuPlugin plugin) {
        this.plugin = Objects.requireNonNull(plugin, "plugin");
    }

    /**
     * {@code offset-y} 的合法下界（向上偏移的极限）。
     *
     * <p>Minecraft 要求 {@code ascent <= height}。覆盖层的
     * {@code ascent = BASE_ASCENT - offsetY}，代入得
     * {@code BASE_ASCENT - offsetY <= GLYPH_HEIGHT}，即
     * {@code offsetY >= BASE_ASCENT - GLYPH_HEIGHT = -150}。
     * 取等号时字形贴到基线上方边界，实际可用性由进服实测确认。
     */
    public static int minOffsetY() {
        return BASE_ASCENT - GLYPH_HEIGHT;
    }

    /**
     * {@code offset-y} 的合法上界（向下偏移的极限）。
     *
     * <p>向下没有 Minecraft 层面的硬限制（ascent 越小越往下），但推到屏幕外就没有意义了。
     * 取 256 作为实用上界：1080p 下 GUI scale 3 时屏幕高约 360 MC 像素，
     * 256 已足够把底图从物品栏推到屏幕任意位置。
     */
    public static int maxOffsetY() {
        return 256;
    }

    /** 把 {@code offset-y} 钳到 {@link #minOffsetY()}..{@link #maxOffsetY()} 区间。 */
    public static int clampOffsetY(int offsetY) {
        return Math.max(minOffsetY(), Math.min(maxOffsetY(), offsetY));
    }

    /** 给定（已钳位的）{@code offset-y} 算出要写进 images.yml 的 ascent。 */
    public static int ascentFor(int offsetY) {
        return BASE_ASCENT - clampOffsetY(offsetY);
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
        int ascent = ascentFor(offsetY);
        // char 用 \\uXXXX 转义写进 YAML：CraftEngine 按转义序列解析，
        // 直接写真实字符会因为它落在 PUA 区而在各种编辑器里显示成豆腐块，不可读也易被误改。
        String charEscape = String.format("\\u%04x", PackAssets.HOTBAR_HUD_DEBUG_CODEPOINT);
        return "# 【运行期生成，不要手改】由 MUZ 的 HotbarDebugOverlayWriter 按\n"
            + "# hotbar-hud.offset-y 写出，每次在 Debug Web 保存垂直偏移都会覆盖这个文件。\n"
            + "# ascent = " + BASE_ASCENT + " - offset-y(" + clampOffsetY(offsetY) + ") = " + ascent + "\n"
            + "# height 恒等于贴图原生高 " + GLYPH_HEIGHT + "，保证 1:1 渲染、只位移不缩放。\n"
            + "# file 指向 bundle 提供的贴图，本覆盖层不自带 PNG。\n"
            + "images:\n"
            + "  " + OVERLAY_NAMESPACE + ":hotbar_slots_debug:\n"
            + "    height: " + GLYPH_HEIGHT + "\n"
            + "    ascent: " + ascent + "\n"
            + "    font: " + PackAssets.HOTBAR_HUD_FONT + "\n"
            + "    file: muz:font/hotbar_slots.png\n"
            + "    char: " + charEscape + "\n";
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
        int clamped = clampOffsetY(offsetY);
        return java.util.concurrent.CompletableFuture.supplyAsync(() -> writeNow(root, clamped), executor);
    }

    /**
     * 异步写出覆盖层资源，写完后切回主线程触发 CraftEngine 重载。
     *
     * <p>【必须异步】：这里有建目录与两次文件写入，主线程 I/O 是明令禁止的。
     * 回调里的 {@code /ce reload pack} 又必须在主线程执行（Bukkit 命令分发不是线程安全的），
     * 所以是「异步写盘 → 切主线程重载资源包并重建客户端 ZIP」两段。
     *
     * @param offsetY   垂直偏移，正数向下；内部会钳位
     * @param onApplied 主线程回调，参数是实际采用的（钳位后的）offsetY；可为 null
     */
    public void writeAsync(int offsetY, java.util.function.IntConsumer onApplied) {
        int clamped = clampOffsetY(offsetY);
        // 先在主线程解析 CraftEngine 数据目录，异步阶段只接触已解析 Path。
        Bukkit.getScheduler().runTask(plugin, () -> {
            Path root = overlayRoot();
            Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> {
                boolean written = writeNow(root, clamped);
                Bukkit.getScheduler().runTask(plugin, () -> {
                    if (written) {
                        reloadCraftEngine();
                    }
                    if (onApplied != null) {
                        onApplied.accept(clamped);
                    }
                });
            });
        });
    }

    /**
     * 同步写出覆盖层资源；仅保留给旧调用点，实际异步流程必须先在主线程解析 Path。
     *
     * @return 是否真的写成功；CraftEngine 缺失或写失败都返回 false
     */
    boolean writeNow(int offsetY) {
        if (!Bukkit.isPrimaryThread()) {
            plugin.getLogger().warning("异步写出 hotbar 调试覆盖层时未提供主线程解析的目录，已拒绝访问 CraftEngine PluginManager。");
            return false;
        }
        Path root = overlayRoot();
        if (root == null) {
            plugin.getLogger().info("CraftEngine 未检测到，跳过 hotbar 调试覆盖层生成。");
            return false;
        }
        return writeNow(root, offsetY);
    }

    /**
     * 使用主线程预先解析的目录写出资源；异步阶段不得再调用 Bukkit PluginManager。
     */
    boolean writeNow(Path root, int offsetY) {
        if (root == null) {
            return false;
        }
        Path imagesDirectory = root.resolve("configuration").resolve("images");
        Path packFile = root.resolve("pack.yml");
        Path imagesFile = imagesDirectory.resolve("hotbar_debug.yml");
        Path packTemp = null;
        Path imagesTemp = null;
        try {
            Files.createDirectories(imagesDirectory);
            // 两份 YAML 分别先写唯一临时文件再替换，避免并发旧调用互相覆盖或被 CraftEngine 读到半写内容。
            packTemp = Files.createTempFile(root, "pack.yml.", ".tmp");
            imagesTemp = Files.createTempFile(imagesDirectory, "hotbar_debug.yml.", ".tmp");
            Files.writeString(packTemp, buildPackYaml(), StandardCharsets.UTF_8);
            Files.writeString(imagesTemp, buildImagesYaml(offsetY), StandardCharsets.UTF_8);
            atomicReplace(packTemp, packFile);
            atomicReplace(imagesTemp, imagesFile);
            plugin.getLogger().info("hotbar 调试覆盖层已写出，offset-y=" + clampOffsetY(offsetY)
                + "（ascent=" + ascentFor(offsetY) + "）：" + root);
            return true;
        } catch (IOException exception) {
            try {
                if (packTemp != null) {
                    Files.deleteIfExists(packTemp);
                }
                if (imagesTemp != null) {
                    Files.deleteIfExists(imagesTemp);
                }
            } catch (IOException cleanupException) {
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

    /**
     * 覆盖层目录；CraftEngine 未安装时返回 null。
     *
     * <p>路径取 {@code plugins/CraftEngine/resources/muz_hotbar_debug}，
     * 与 {@code CraftEngineBundleExporter} 的 {@code resources/muz} 是兄弟目录，
     * 不在它的清理范围内。
     */
    private Path overlayRoot() {
        Plugin craftEngine = plugin.getServer().getPluginManager().getPlugin(CRAFT_ENGINE_PLUGIN);
        if (craftEngine == null || !craftEngine.isEnabled()) {
            return null;
        }
        return craftEngine.getDataFolder().toPath().resolve("resources").resolve(OVERLAY_NAMESPACE);
    }

    /**
     * 触发 CraftEngine 重新加载并重打包资源包。
     *
     * <p>走控制台命令分发而不是反射调 CraftEngine 内部 API：命令是它的公开契约，
     * 内部类名在版本间会变。这个模式仓库里已有先例（{@code CeActionExecutor} 执行
     * 配置里的命令时也是 {@code dispatchCommand(getConsoleSender(), ...)}）。
     *
     * <p>【必须在主线程调用】。重载完成后客户端还需要重新下载资源包才能看到新 ascent，
     * 是否需要玩家手动重新接受取决于 CraftEngine 的下发设置。
     */
    /** 在主线程触发 CraftEngine 完整重载，并返回命令分发结果。 */
    boolean reloadCraftEngineOnMainThread() {
        try {
            boolean dispatched = Bukkit.dispatchCommand(Bukkit.getConsoleSender(), CRAFT_ENGINE_RELOAD_COMMAND);
            if (dispatched) {
                plugin.getLogger().info("已触发 CraftEngine 完整重载并重建客户端资源包，客户端可能需要重新下载才能看到新的垂直偏移。");
            } else {
                plugin.getLogger().warning("CraftEngine 重载命令未被接受，未应用 HUD 运行态。");
            }
            return dispatched;
        } catch (RuntimeException exception) {
            plugin.getLogger().warning("触发 CraftEngine 完整重载失败，请手动执行 /ce reload all：" + exception.getMessage());
            return false;
        }
    }

    private void reloadCraftEngine() {
        reloadCraftEngineOnMainThread();
    }
}
