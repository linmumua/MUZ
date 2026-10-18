package linmumua.doudizhu.compat;

import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.logging.Logger;
import linmumua.doudizhu.DoudizhuPlugin;
import linmumua.doudizhu.game.ChairPanelAnchor;
import linmumua.doudizhu.log.MuzLogGate;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;
import taboolib.library.reflex.AnalyseMode;
import taboolib.library.reflex.ClassAnalyser;
import taboolib.library.reflex.ClassConstructor;
import taboolib.library.reflex.ClassMethod;
import taboolib.library.reflex.ReflexClass;

/**
 * 运行期对 MuMenu（悬浮面板插件）公开 API 的**窄桥**：判会话活动、开面板、关会话。
 *
 * <p>【为什么是反射而不是编译依赖】MuMenu 的产物面向 Java 25 / api-version 26.3，而 MUZ 要保持对
 * Java 21 的三目标（paper-1.21.11 / paper-26.1.2 / paper-26.2）可编译，直接 {@code import} 它的 API
 * 会把整条构建链拖上 Java 25。项目硬约束是「无编译期依赖、无新 API class imports，反射只用既有
 * TabooLib Reflex」，所以这里按**类名 + 方法名 + 参数类型**在运行期解析，绝不引用任何 MuMenu 类型。
 *
 * <p>【公开 API 事实（已核对 arcmenu 源码，不是猜测）】真实产品名 MuMenu，包 {@code dev.mumu.mumenu.api}
 * （其 docs/README 里的 {@code ArcMenuApi} 是旧名/示例名，不要用）。关键签名（文件
 * {@code arcmenu/api/src/main/kotlin/dev/mumu/mumenu/api/MuMenuApi.kt}）：
 * <ul>
 *   <li>{@code MuMenuSurface? surface(Player)} —— 非 null 表示该玩家有活动菜单/应用会话；这是本桥唯一的
 *       会话判据。</li>
 *   <li>{@code void close(Player)} —— 关闭当前会话。</li>
 *   <li>{@code boolean open(Player, String menuId, List<String> arguments)} —— 打开菜单。</li>
 *   <li>{@code int apiVersion()} —— 当前为 1；版本不符即判不兼容（不假装支持）。</li>
 * </ul>
 * 服务经 Bukkit {@code ServicesManager} 发布（{@code MuMenuPlugin.kt} 里
 * {@code servicesManager.register(MuMenuApi::class.java, api, host, ServicePriority.Normal)}），
 * 因此取服务必须用 **MuMenu 插件自己的类加载器**加载 API 接口，再把该 {@code Class} 交给
 * {@code ServicesManager}（其内部按类名查找，跨加载器可用）。
 *
 * <p>【线程约束】{@link #isSessionActive(Player)} / {@link #openPanel(Player)} / {@link #closeSession(Player)}
 * 必须在该玩家的 owner lane（player lane）上调用：MuMenu 的实现里 {@code surface} 等玩家级入口会
 * {@code check(MuMenuTasks.ownsWorldStateOf(player))}（见 {@code MuMenuApiImpl.sync(player, ...)}），
 * 在别的线程调用会抛「MuMenu API 必须在拥有该玩家所属区域的线程上调用」。这与 MUZ 既有的
 * 「玩家级输出走 player lane」口径一致。
 *
 * <p>【降级】MuMenu 缺失、服务未发布、API 版本不符或关键方法缺失时，所有入口都返回 false / 空，
 * 并由 {@link #status()} 给出可读原因；**绝不因此退回旧箱子 GUI，也不崩**。
 *
 * <p>【解析一次缓存，启停刷新】解析结果按 MuMenu 插件实例缓存（reload 会换实例，缓存自然不命中）；
 * 另提供 {@link #refresh()} 供插件启停/reload 时清缓存。
 */
public class MuMenuGadgetBridge {
    /** MuMenu 在 paper-plugin.yml / plugin.yml 里声明的插件名。 */
    public static final String PLUGIN_NAME = "MuMenu";

    /** MuMenu 公开服务接口的完全限定名。 */
    public static final String API_CLASS = "dev.mumu.mumenu.api.MuMenuApi";

    /** 位置测试面板的菜单 ID（与菜单示例、快捷命令保持一致）。 */
    public static final String PANEL_MENU_ID = "muz-gadget-position-test";

    /**
     * 椅侧面板**正式菜单 ID**（左右两块共用同一份菜单，靠 {@code panelId} 区分实例）。
     *
     * <p>【为什么不是 muz-chair-panel】`muz-chair-panel` 是只画两块静态板的 3:2 骨架示例，
     * 内容为空壳（没有 8 槽道具选择、没有空槽状态、没有语音动作）。正式面板必须能真正选择道具，
     * 所以正式菜单单独用 `muz-chair-gadget`（300×200 = 3:2），内容复用既有
     * {@code /muz gadget select <1..8>} 与 {@code /muz gadget voice}。
     * 两块面板用**同一个** menuId、不同 panelId，是 MuMenu 明确允许的用法（两个独立实例）。
     */
    public static final String CHAIR_PANEL_MENU_ID = "muz-chair-gadget";

    /** 本桥支持（且要求）的 MuMenu API 版本。 */
    public static final int SUPPORTED_API_VERSION = 1;

    /**
     * 世界锚定多面板能力名。
     *
     * <p>【为什么按能力而不是版本号协商】MuMenu 新增锚定面板时**没有**提升
     * {@code apiVersion()}（实现里仍是 {@code 1}），提升它的是另一个改动。若本桥用
     * {@code apiVersion() >= 2} 判定，装了支持锚定面板的版本也会被判成不支持。
     * MuMenu 在自己的能力枚举上写得很直白：附属插件**必须**检查
     * {@code capabilities()}，不要只靠 {@code apiVersion()} 或方法存在性判断。
     * 所以这里按能力名协商，并且**同时**要求方法齐全，两者都满足才算可用。
     */
    public static final String CAPABILITY_ANCHORED_PANELS = "ANCHORED_PANELS";

    /** MuMenu 世界锚定面板锚点类型的完全限定名（由本桥在运行期构造，绝不 import）。 */
    public static final String ANCHOR_CLASS = "dev.mumu.mumenu.api.MuMenuPanelAnchor";

    private static final String METHOD_SURFACE = "surface";
    private static final String METHOD_CLOSE = "close";
    private static final String METHOD_OPEN = "open";
    private static final String METHOD_API_VERSION = "apiVersion";
    private static final String METHOD_CAPABILITIES = "capabilities";
    private static final String METHOD_OPEN_PANEL = "openPanel";
    private static final String METHOD_UPDATE_PANEL_ANCHOR = "updatePanelAnchor";
    private static final String METHOD_CLOSE_PANEL = "closePanel";
    private static final String METHOD_CLOSE_PANELS = "closePanels";
    private static final String METHOD_PANELS = "panels";

    /** 拿不到插件 logger 时的兜底 logger；一律经 {@link MuzLogGate} 门控，避免绕过 WARNING 门禁。 */
    private static final Logger FALLBACK_LOGGER = Logger.getLogger("MUZ");

    /**
     * 已解析结果的缓存：键是 MuMenu 的 {@link Plugin} 实例。
     *
     * <p>用插件实例而不是类名：MuMenu reload 会换掉插件实例，缓存自然不命中并重新解析；同一个实例
     * 则命中，实现「解析一次缓存」。只缓存**成功**的解析，失败不缓存以便服务稍后注册完成时自愈。
     */
    private static final ConcurrentHashMap<Plugin, Resolved> CACHE = new ConcurrentHashMap<>();

    /** 已分析过的 API 接口结构（按 {@code Class} 缓存），避免每次调用都重做全类分析。 */
    private static final ConcurrentHashMap<Class<?>, ReflexClass> ANALYSED = new ConcurrentHashMap<>();

    /** 可为 null（静态守卫路径没有插件实例，日志走兜底 logger）。 */
    private final DoudizhuPlugin plugin;

    public MuMenuGadgetBridge(DoudizhuPlugin plugin) {
        this.plugin = plugin;
    }

    /**
     * 面板会话判据（供各事件入口在进入道具/语音前调用，无需注入任何服务）。
     *
     * <p>等价于「该玩家有活动 MuMenu 会话」。必须在 player lane 调用（见类注释）。
     * MuMenu 缺失或 API 不兼容时返回 false（此时也不存在面板，放行 MUZ 自己的路由是对的）。
     */
    public static boolean sessionActive(Player player) {
        return new MuMenuGadgetBridge(null).isSessionActive(player);
    }

    /** 清空解析缓存；插件启停 / reload / MuMenu enable-disable 后调用。 */
    public static void refresh() {
        CACHE.clear();
    }

    /** MuMenu 服务是否可用且 API 兼容。 */
    public boolean isAvailable() {
        return resolve() != null;
    }

    /**
     * 可读的降级原因，用于提示与排查。
     *
     * @return 可用时返回 {@code "可用"}；否则给出「未安装 / 未发布服务 / 版本不符 / 方法缺失」等具体原因
     */
    public String status() {
        Plugin mu = muMenuPlugin();
        if (mu == null) {
            return "未安装 MuMenu";
        }
        if (!mu.isEnabled()) {
            return "MuMenu 未启用";
        }
        Resolved resolved = resolve();
        return resolved == null ? "MuMenu API 不可用或不兼容" : "可用";
    }

    /**
     * 该玩家当前是否有活动 MuMenu 会话（菜单、应用**或**世界锚定面板）。
     *
     * <p>IMPORTANT LANE: 必须在 player lane 调用。
     *
     * <p>【为什么不只看 surface】{@code surface(player)} 返回的是「近眼屏面」那条会话的平面
     * （应用会话 → 该会话平面，其次菜单会话）。世界锚定面板走的是**另一套**平面来源，
     * 面板开着时 {@code surface} 完全可能仍是 null。若这里只判 surface，MUZ 会把面板
     * 下面的世界点击当成正常投掷/交互，等于面板挡不住右键。所以判据是
     * 「surface 非空 **或** 锚定面板清单非空」。
     *
     * @return 有活动会话返回 true；MuMenu 不可用 / 查询失败返回 false（按「无会话」降级）
     */
    public boolean isSessionActive(Player player) {
        if (player == null) {
            return false;
        }
        Resolved resolved = resolve();
        if (resolved == null) {
            return false;
        }
        try {
            if (resolved.surface().invoke(resolved.service(), new Object[] {player}) != null) {
                return true;
            }
        } catch (Throwable failure) {
            warn("查询 MuMenu 会话状态失败", failure);
            return false;
        }
        // 锚定面板是独立平面来源：surface 为空也要检查，否则面板挡不住下面的世界点击。
        return !panelIds(player).isEmpty();
    }

    /**
     * 打开位置测试面板（菜单 ID 固定 {@link #PANEL_MENU_ID}）。
     *
     * <p>IMPORTANT LANE: 必须在 player lane 调用。
     *
     * @return 打开成功返回 true；MuMenu 不可用或调用失败返回 false
     */
    public boolean openPanel(Player player) {
        if (player == null) {
            return false;
        }
        Resolved resolved = resolve();
        if (resolved == null) {
            return false;
        }
        try {
            Object result = resolved.open().invoke(
                resolved.service(), new Object[] {player, PANEL_MENU_ID, List.of()});
            return result instanceof Boolean opened && opened;
        } catch (Throwable failure) {
            warn("打开 MuMenu 道具面板失败", failure);
            return false;
        }
    }

    /**
     * 关闭该玩家的 MuMenu 会话（选择成功、或进入语音前调用）。
     *
     * <p>IMPORTANT LANE: 必须在 player lane 调用。
     *
     * @return 成功发起关闭返回 true；MuMenu 不可用或无关闭入口返回 false
     */
    public boolean closeSession(Player player) {
        if (player == null) {
            return false;
        }
        Resolved resolved = resolve();
        if (resolved == null || resolved.close() == null) {
            return false;
        }
        try {
            resolved.close().invoke(resolved.service(), new Object[] {player});
            return true;
        } catch (Throwable failure) {
            warn("关闭 MuMenu 会话失败", failure);
            return false;
        }
    }

    /**
     * 世界锚定面板是否可用（能力 {@code ANCHORED_PANELS} + 方法齐全 + 锚点类型可解析）。
     *
     * <p>【为什么必须问这个】旧版 MuMenu 上 {@code open}/{@code surface} 照常可用，但**没有**
     * 世界锚定面板：直接开第二个面板会把第一个顶掉（关闭原因 {@code REPLACED}），
     * 于是玩家只看到一块板、另一块静默消失。所以椅侧双面板必须先用本方法协商，
     * 不满足就明确提示不可用，绝不退化成「开一个再开第二个」。
     */
    public boolean supportsAnchoredPanels() {
        Resolved resolved = resolve();
        return resolved != null && resolved.anchored() != null;
    }

    /** 一次世界锚定面板操作的结论。 */
    public enum PanelResult {
        /** 成功。 */
        OK,
        /** MuMenu 不支持世界锚定面板（旧版 / 缺能力 / 缺方法）。 */
        UNSUPPORTED,
        /** 支持但本次调用失败（画布比例不符、菜单不存在、权限不足、内部异常等）。 */
        FAILED
    }

    /**
     * 打开（或替换）一块世界锚定面板。
     *
     * <p>IMPORTANT LANE: 必须在 player lane 调用。
     *
     * <p>【原子性由调用方保证】MuMenu 按 {@code panelId} 单块打开；要「两块要么都开、要么都不开」
     * 必须由调用方在做完两次调用后检查结果并回滚（见 {@code GadgetPanelService}）。
     * 本方法只如实返回这一块的结果。
     *
     * @param anchor MUZ 侧的不可变锚点快照（世界格尺寸）
     */
    public PanelResult openPanel(Player player, String panelId, String menuId, ChairPanelAnchor anchor) {
        if (player == null || panelId == null || panelId.isBlank() || menuId == null || anchor == null) {
            return PanelResult.FAILED;
        }
        Resolved resolved = resolve();
        if (resolved == null || resolved.anchored() == null) {
            return PanelResult.UNSUPPORTED;
        }
        Anchored anchored = resolved.anchored();
        try {
            // owner 是 MuMenu 的归属收口依据（插件禁用时统一收回面板）。拿不到 owner 就不能开：
            // 传 null 会让面板失去归属、插件被禁后残留一块无法回收的界面。
            Plugin owner = ownerPlugin();
            if (anchored.openPanelTakesOwner() && owner == null) {
                warn("无法确定面板 owner，拒绝打开锚定面板（panelId=" + panelId + "）", null);
                return PanelResult.FAILED;
            }
            Object anchorObject = buildAnchor(anchored.anchorType(), anchor);
            if (anchorObject == null) {
                return PanelResult.UNSUPPORTED;
            }
            // 带 owner 的版本需要把「本插件」作为第一个参数传进去（MuMenu 用它做 owner 收口）。
            Object result = anchored.openPanelTakesOwner()
                ? anchored.openPanel().invoke(resolved.service(),
                    new Object[] {owner, player, panelId, menuId, anchorObject})
                : anchored.openPanel().invoke(resolved.service(),
                    new Object[] {player, panelId, menuId, anchorObject});
            return result instanceof Boolean opened && opened ? PanelResult.OK : PanelResult.FAILED;
        } catch (Throwable failure) {
            warn("打开 MuMenu 世界锚定面板失败（panelId=" + panelId + "）", failure);
            return PanelResult.FAILED;
        }
    }

    /**
     * 就地更新已有面板的位置 / 朝向 / 世界尺寸（Dialog 调完几何后应用）。
     *
     * <p>IMPORTANT LANE: 必须在 player lane 调用。
     *
     * @return 成功返回 true；面板不存在、参数非法或画布比例不符返回 false（保留原面板不变）
     */
    public boolean updatePanelAnchor(Player player, String panelId, ChairPanelAnchor anchor) {
        if (player == null || panelId == null || panelId.isBlank() || anchor == null) {
            return false;
        }
        Resolved resolved = resolve();
        if (resolved == null || resolved.anchored() == null) {
            return false;
        }
        Anchored anchored = resolved.anchored();
        if (anchored.updatePanelAnchor() == null) {
            return false;
        }
        try {
            Object anchorObject = buildAnchor(anchored.anchorType(), anchor);
            if (anchorObject == null) {
                return false;
            }
            Object result = anchored.updatePanelAnchor().invoke(
                resolved.service(), new Object[] {player, panelId, anchorObject});
            return result instanceof Boolean updated && updated;
        } catch (Throwable failure) {
            warn("更新 MuMenu 面板锚点失败（panelId=" + panelId + "）", failure);
            return false;
        }
    }

    /**
     * 关闭一块面板；不存在时静默。
     *
     * <p>IMPORTANT LANE: 必须在 player lane 调用。
     */
    public boolean closePanel(Player player, String panelId) {
        if (player == null || panelId == null || panelId.isBlank()) {
            return false;
        }
        Resolved resolved = resolve();
        if (resolved == null || resolved.anchored() == null) {
            return false;
        }
        try {
            resolved.anchored().closePanel().invoke(resolved.service(), new Object[] {player, panelId});
            return true;
        } catch (Throwable failure) {
            warn("关闭 MuMenu 面板失败（panelId=" + panelId + "）", failure);
            return false;
        }
    }

    /**
     * 关闭该玩家的全部世界锚定面板（不影响普通菜单/应用会话）。
     *
     * <p>IMPORTANT LANE: 必须在 player lane 调用。
     *
     * @return 成功发起关闭返回 true；MuMenu 不支持锚定面板时返回 false
     */
    public boolean closePanels(Player player) {
        if (player == null) {
            return false;
        }
        Resolved resolved = resolve();
        if (resolved == null || resolved.anchored() == null) {
            return false;
        }
        try {
            resolved.anchored().closePanels().invoke(resolved.service(), new Object[] {player});
            return true;
        } catch (Throwable failure) {
            warn("关闭 MuMenu 全部面板失败", failure);
            return false;
        }
    }

    /**
     * 该玩家当前的锚定面板标识快照。
     *
     * <p>IMPORTANT LANE: 必须在 player lane 调用。
     *
     * @return 不可变集合；MuMenu 不支持或读取失败时返回空集合（调用方据此判断「板已经没了」）
     */
    public Set<String> panelIds(Player player) {
        if (player == null) {
            return Set.of();
        }
        Resolved resolved = resolve();
        if (resolved == null || resolved.anchored() == null) {
            return Set.of();
        }
        try {
            Object result = resolved.anchored().panels().invoke(resolved.service(), new Object[] {player});
            if (!(result instanceof java.util.Collection<?> values)) {
                return Set.of();
            }
            Set<String> ids = new java.util.LinkedHashSet<>();
            for (Object value : values) {
                if (value != null) {
                    ids.add(value.toString());
                }
            }
            return Set.copyOf(ids);
        } catch (Throwable failure) {
            warn("读取 MuMenu 面板清单失败", failure);
            return Set.of();
        }
    }

    /** 指定面板当前是否存在（{@link #panelIds} 的便捷判据）。 */
    public boolean panelActive(Player player, String panelId) {
        return panelId != null && panelIds(player).contains(panelId);
    }

    /**
     * 把 MUZ 的不可变锚点快照反射构造成 MuMenu 的锚点对象。
     *
     * <p>构造器签名取自 MuMenu 公开 API（{@code MuMenuPanelAnchor} 的便捷构造）：
     * {@code (World, Location, float yawDegrees, double widthBlocks, double heightBlocks)}，
     * 其中 {@code origin} 是面板**中心**、尺寸单位是**世界格**。全程不 import MuMenu 类型。
     *
     * @return 构造出的锚点对象；构造器缺失（API 形状不符）时返回 null（上层按不支持处理）
     */
    /**
     * 把 MUZ 的不可变锚点快照反射构造成 MuMenu 的锚点对象。
     *
     * <p>【构造器来源：按真实存在性探测，不假设】当前已核实的公开形状是
     * {@code (World, Location, float yawDegrees, double widthBlocks, double heightBlocks)}
     * （{@code MuMenuPanelAnchor} 的便捷构造，origin 是面板<b>中心</b>、尺寸是<b>世界格</b>）。
     * 若引擎将来把锚点扩成三轴，本方法会优先命中带有 pitch/roll 的重载并原样投递，
     * 因此旋转 UI 暴露的轴不会出现「有的生效、有的被静默吞掉」：命中三轴就三轴生效，
     * 只命中单轴就<b>如实报告</b>降级（见 {@link #tiltUnsupportedReported}）。
     *
     * @return 构造出的锚点对象；构造器缺失（API 形状不符）时返回 null（上层按不支持处理）
     */
    private Object buildAnchor(ReflexClass anchorType, ChairPanelAnchor anchor) {
        World world = worldOf(anchor.worldId());
        if (world == null) {
            return null;
        }
        Location origin = new Location(world, anchor.x(), anchor.y(), anchor.z());
        // 优先三轴（引擎若已扩展）：(World, Location, float yaw, float pitch, float roll, double w, double h)
        ClassConstructor tilted = anchorType.getConstructorByTypeSilently(
            World.class, Location.class, float.class, float.class, float.class,
            double.class, double.class);
        if (tilted != null) {
            return tilted.instance(
                world, origin,
                anchor.yawDegrees(), anchor.pitchDegrees(), anchor.rollDegrees(),
                anchor.width(), anchor.height());
        }
        // 回退到已核实的单轴形状。
        ClassConstructor flat = anchorType.getConstructorByTypeSilently(
            World.class, Location.class, float.class, double.class, double.class);
        if (flat == null) {
            warn("MuMenu 锚点类型缺少可用构造器（单轴与三轴都不匹配），锚定面板降级", null);
            return null;
        }
        if (anchor.hasUnsupportedTilt()) {
            // 不静默丢弃：pitch/roll 是玩家在 UI 里能设的轴，没生效必须说清楚。
            tiltUnsupportedReported = true;
            warn("当前 MuMenu 锚点只接受 yaw，pitch/roll 不会生效（panelId="
                + anchor.panelId() + "，pitch=" + anchor.pitchDegrees()
                + "，roll=" + anchor.rollDegrees() + "）", null);
        }
        return flat.instance(
            world, origin, anchor.yawDegrees(), anchor.width(), anchor.height());
    }

    /** 是否已报告过「引擎只支持 yaw」的降级（供装配层一次性向管理员提示，避免刷屏）。 */
    private volatile boolean tiltUnsupportedReported;

    /** 是否发生过「pitch/roll 无法生效」的降级（只读）。 */
    public boolean tiltUnsupported() {
        return tiltUnsupportedReported;
    }

    /** 按 MuMenu 插件实例解析（成功才缓存）。 */
    private Resolved resolve() {
        Plugin mu = muMenuPlugin();
        if (mu == null) {
            return null;
        }
        if (!mu.isEnabled()) {
            // MuMenu 被禁用时，服务已被注销；同一个插件实例再次启用时必须重新解析。
            // 否则会命中禁用前留下的旧服务实例（已失效），这就是「启停刷新」的自愈路径。
            CACHE.remove(mu);
            return null;
        }
        Resolved cached = CACHE.get(mu);
        if (cached != null) {
            return cached;
        }
        Resolved built = build(mu);
        if (built != null) {
            CACHE.put(mu, built);
        }
        return built;
    }

    /** 真正做一次解析；任一步失败都返回 null（降级，不抛）。 */
    private Resolved build(Plugin mu) {
        try {
            ClassLoader loader = mu.getClass().getClassLoader();
            Class<?> apiType = apiClass(loader);
            Object service = apiService(apiType);
            if (service == null) {
                // 服务尚未发布（MuMenu 可能还没注册完）——不缓存，等下次自愈。
                return null;
            }
            ReflexClass analysed = analyse(apiType);
            ClassMethod surface = methodSilently(analysed, METHOD_SURFACE, Player.class);
            ClassMethod open = methodSilently(
                analysed, METHOD_OPEN, Player.class, String.class, List.class);
            ClassMethod close = methodSilently(analysed, METHOD_CLOSE, Player.class);
            ClassMethod apiVersion = methodSilently(analysed, METHOD_API_VERSION);
            if (surface == null || open == null) {
                warn("MuMenu API 缺少必需方法（surface/open），视为不兼容", null);
                return null;
            }
            if (apiVersion != null) {
                Object version = apiVersion.invoke(service, new Object[0]);
                if (!(version instanceof Integer value) || value != SUPPORTED_API_VERSION) {
                    warn("MuMenu API 版本不受支持：" + version + "（需要 " + SUPPORTED_API_VERSION + "）", null);
                    return null;
                }
            }
            Anchored anchored = resolveAnchored(loader, analysed, service);
            return new Resolved(service, surface, close, open, anchored);
        } catch (ClassNotFoundException | LinkageError | RuntimeException failure) {
            warn("解析 MuMenu API 失败", failure);
            return null;
        }
    }

    /**
     * 解析世界锚定面板能力；不支持的旧版返回 {@code null}（降级，不抛）。
     *
     * <p>协商条件是**双重的**：{@code capabilities()} 里必须声明 {@link #CAPABILITY_ANCHORED_PANELS}，
     * 且五个方法都能解析出来。只有能力没有方法（或反之）都判为不可用——MuMenu 明确要求不要
     * 只靠方法存在性判断，而这里进一步要求两者同时成立，避免半套 API 把面板开成半截。
     */
    private Anchored resolveAnchored(ClassLoader loader, ReflexClass analysed, Object service) {
        ClassMethod capabilities = methodSilently(analysed, METHOD_CAPABILITIES);
        if (capabilities == null) {
            return null;
        }
        Object declared;
        try {
            declared = capabilities.invoke(service, new Object[0]);
        } catch (Throwable failure) {
            warn("读取 MuMenu 能力清单失败，锚定面板降级", failure);
            return null;
        }
        if (!declaresCapability(declared, CAPABILITY_ANCHORED_PANELS)) {
            return null;
        }
        Class<?> anchorClass;
        try {
            anchorClass = anchorClass(loader);
        } catch (ClassNotFoundException | LinkageError failure) {
            warn("MuMenu 声明了锚定面板能力但缺少 " + ANCHOR_CLASS + "，锚定面板降级", failure);
            return null;
        }
        ReflexClass anchorType = analyse(anchorClass);
        // openPanel 的签名以 MuMenu 源码为准：**第一个参数是调用方 Plugin（owner）**，
        // 然后是玩家、panelId、menuId、锚点。早期草案里没有 owner，故同时探测两种形状：
        // 命中带 owner 的版本就用它（当前实现），否则回退到不带 owner 的形状（更旧草案）。
        ClassMethod openPanelWithOwner = methodSilently(
            analysed, METHOD_OPEN_PANEL, Plugin.class, Player.class, String.class, String.class, anchorClass);
        ClassMethod openPanelLegacy = openPanelWithOwner == null
            ? methodSilently(analysed, METHOD_OPEN_PANEL, Player.class, String.class, String.class, anchorClass)
            : null;
        ClassMethod openPanel = openPanelWithOwner != null ? openPanelWithOwner : openPanelLegacy;
        ClassMethod updatePanelAnchor = methodSilently(
            analysed, METHOD_UPDATE_PANEL_ANCHOR, Player.class, String.class, anchorClass);
        ClassMethod closePanel = methodSilently(analysed, METHOD_CLOSE_PANEL, Player.class, String.class);
        ClassMethod closePanels = methodSilently(analysed, METHOD_CLOSE_PANELS, Player.class);
        ClassMethod panels = methodSilently(analysed, METHOD_PANELS, Player.class);
        if (openPanel == null || closePanel == null || closePanels == null || panels == null) {
            warn("MuMenu 锚定面板方法不完整（openPanel/closePanel/closePanels/panels），降级", null);
            return null;
        }
        return new Anchored(
            anchorType,
            openPanel,
            openPanelWithOwner != null,
            updatePanelAnchor,
            closePanel,
            closePanels,
            panels);
    }

    /**
     * 能力清单里是否声明了指定能力（按**名字**比较，不 import MuMenu 的枚举）。
     *
     * @param declared {@code capabilities()} 的返回值，通常是 {@code Set}；非集合一律视为未声明
     */
    static boolean declaresCapability(Object declared, String capabilityName) {
        if (!(declared instanceof java.util.Collection<?> values) || capabilityName == null) {
            return false;
        }
        for (Object value : values) {
            if (value != null && capabilityName.equals(value.toString())) {
                return true;
            }
        }
        return false;
    }

    private static ReflexClass analyse(Class<?> type) {
        // REFLECTION_ONLY：API 类来自 MuMenu 的插件类加载器，纯反射只依赖 Class 对象本身。
        return ANALYSED.computeIfAbsent(type, key -> new ReflexClass(
            ClassAnalyser.INSTANCE.analyse(key, AnalyseMode.REFLECTION_ONLY), AnalyseMode.REFLECTION_ONLY));
    }

    /** 按「方法名 + 参数类型」取方法（沿继承链），缺失返回 null。 */
    private static ClassMethod methodSilently(ReflexClass analysed, String name, Class<?>... parameterTypes) {
        return analysed.getMethodByTypeSilently(name, true, false, parameterTypes);
    }

    private void warn(String message, Throwable failure) {
        if (plugin != null) {
            if (failure == null) {
                plugin.getLogger().warning(message + "（MuMenu 道具面板已降级）");
            } else {
                // 与项目其它 compat 桥同口径：包装异常 getMessage() 常为 null，带异常类型与本体。
                plugin.getLogger().warning(message + ": " + describeFailure(failure));
            }
            return;
        }
        // 静态守卫路径拿不到自有 logger，经门禁显式门控，绝不直接落到共享 logger。
        MuzLogGate.warn(FALLBACK_LOGGER, message, failure);
    }

    private static String describeFailure(Throwable failure) {
        String detail = failure.getMessage();
        return detail == null || detail.isBlank() ? failure.getClass().getName() : detail;
    }

    // --- 测试接缝：这几处是本桥唯一「无法在单测里复现真实环境」的步骤，放开继承只给测试顶替。 ---

    /** 取 MuMenu 插件实例；测试可覆写以注入替身，避免依赖真实 Bukkit 插件管理器。 */
    Plugin muMenuPlugin() {
        return Bukkit.getPluginManager().getPlugin(PLUGIN_NAME);
    }

    /** 按给定的 MuMenu 类加载器加载 API 接口；测试可覆写以返回替身接口。 */
    Class<?> apiClass(ClassLoader loader) throws ClassNotFoundException {
        return Class.forName(API_CLASS, false, loader);
    }

    /** 取 API 服务实例；测试可覆写以注入替身实现。 */
    @SuppressWarnings({"unchecked", "rawtypes"})
    Object apiService(Class<?> apiClass) {
        return Bukkit.getServicesManager().load((Class) apiClass);
    }

    /** 按 MuMenu 类加载器加载锚点类型；测试可覆写以返回替身锚点类。 */
    Class<?> anchorClass(ClassLoader loader) throws ClassNotFoundException {
        return Class.forName(ANCHOR_CLASS, false, loader);
    }

    /** 按世界 UUID 取世界；测试可覆写以避开 Bukkit 服务端。 */
    World worldOf(UUID worldId) {
        return Bukkit.getWorld(worldId);
    }

    /**
     * 作为面板 owner 传給 MuMenu 的插件实例。
     *
     * <p>MuMenu 用 owner 做归属收口（插件被禁用时统一收回面板），所以必须传**本插件**。
     * 静态守卫路径（{@code plugin == null}）拿不到实例，返回 null；那种调用只会发生在
     * 只读判定里，不会开面板。
     */
    Plugin ownerPlugin() {
        return plugin;
    }

    /** 一次成功解析：服务实例 + 已解析的方法句柄。 */
    private record Resolved(
        Object service,
        ClassMethod surface,
        ClassMethod close,
        ClassMethod open,
        /**
         * 世界锚定面板的解析结果；MuMenu 不支持该能力时为 {@code null}（此时所有面板入口
         * 一律 fail-closed，绝不用「先开一个再开第二个」的旧写法冒充双面板）。
         */
        Anchored anchored
    ) {
    }

    /**
     * 世界锚定面板的一组句柄：能力、锚点类型与五个方法。
     *
     * <p>{@code capabilities} 存的是能力**名字符串**，不是枚举对象：能力枚举来自 MuMenu 自己的
     * 类加载器，本桥不 import、不 cast，只按名字判断。
     */
    private record Anchored(
        ReflexClass anchorType,
        ClassMethod openPanel,
        /**
         * {@code openPanel} 是否带调用方 {@code Plugin owner} 首参。
         *
         * <p>MuMenu 源码定稿的形状是 {@code openPanel(Plugin owner, Player, panelId, menuId, anchor)}；
         * 早期草案是 4 参。两种都在运行期探测，命中哪个就按哪个传参——绝不臆造。
         */
        boolean openPanelTakesOwner,
        ClassMethod updatePanelAnchor,
        ClassMethod closePanel,
        ClassMethod closePanels,
        ClassMethod panels
    ) {
    }
}
