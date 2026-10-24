package linmumua.doudizhu.game;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import linmumua.doudizhu.DoudizhuPlugin;
import linmumua.doudizhu.compat.VersionCompat;
import linmumua.doudizhu.world.DisplayPanelPickGeometry;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextDecoration;
import org.bukkit.Bukkit;
import org.bukkit.Color;
import org.bukkit.FluidCollisionMode;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.entity.Display;
import org.bukkit.entity.Entity;
import org.bukkit.entity.BlockDisplay;
import org.bukkit.entity.Interaction;
import org.bukkit.entity.ItemDisplay;
import org.bukkit.entity.Player;
import org.bukkit.entity.TextDisplay;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.util.RayTraceResult;
import org.bukkit.util.Transformation;
import org.bukkit.util.Vector;
import org.joml.AxisAngle4f;
import org.joml.Vector3f;

/**
 * 椅侧左右两块面板的**内置渲染**：MUZ 自己用 {@code BlockDisplay} / {@code ItemDisplay} /
 * {@code TextDisplay} + 一个 {@code Interaction} 在世界里画出来，不要求服务端安装 MuMenu，
 * 也不再有「宿主菜单画布宽高比」这种外部约束。
 *
 * <p>【为什么内置】椅侧面板是本插件对局体验的一部分：玩家坐下就该看到左右两块可选道具的板子。
 * 依赖另一个插件提供渲染，会让「没装 / 版本不匹配 / 画布比例不符」直接变成「面板不存在」，
 * 而外部插件还各自有自己的一人一面、眼睛锚定、会话替换等语义。内置后 MUZ 只依赖
 * Bukkit 的 Display API，位置与三轴旋转完全由本地配置决定。
 *
 * <p>【1.10.73：与 MuMenu {@code muz-chair-gadget.yml} 同款布局】用户明确要求左右两块都做成那套
 * 完整 3×3（8 道具 + 第 9 格语音）菜单的外观，但**不装 MuMenu、不投放菜单 YAML**，
 * 因此这里按那份菜单的参考画布逐项复刻：
 * <ul>
 *   <li>底板 300×200、边框厚 2 像素；</li>
 *   <li>九张卡片 80×36，中心 x = -90 / 0 / 90、y = 30 / -14 / -58（**不等距**，卡片间有 10 像素空隙）；</li>
 *   <li>标题、当前选中副标题、每格名称与空/选中状态、底部提示；</li>
 *   <li>坐标全部经 {@link ChairPanelSurface} 的 300×200 → 1.5×1.0 世界格映射换算，
 *       显示矩形与命中矩形是同一份数值。</li>
 * </ul>
 *
 * <p>【配色】目标色板来自菜单：底板 {@code #0E1626}、边框 {@code #4C6EA8}、槽卡片 {@code #22304D}、
 * 语音卡片 {@code #3A2A4D}。Minecraft 的原版方块没有任意 RGB 染色能力，这里选用同色系最接近的方块
 * （见下方 {@code CARD_*} 常量旁的注释）。**要拿到精确色号，需要资源包替换这些方块的外观**；
 * 布局（尺寸/位置/层级）已经是精确的，不会随配色替换而变。
 *
 * <p>【几何与命中同源】显示位置、图标缩放、点击命中都来自 {@link ChairPanelSurface} 与
 * {@link DisplayPanelPickGeometry}：射线先与面板平面求交，再映射到九张卡片矩形。绝不按实体碰撞箱
 * 猜格位——{@code Interaction} 只有一个盒子，格位必须由局部坐标算，否则点哪个格子都落同一槽。
 * 卡片之间的空隙命中 {@link ChairPanelSurface#OUTSIDE}，调用方必须拒绝（不会就近取格子）。
 *
 * <p>【lane 与「可观察完成状态」】实体建、改、删一律在**锚点所属 region** 上执行
 * （见 {@link #runOnAnchorRegion}）；玩家级调用（{@code showEntity}）也在该 lane 内。
 * 面板可能跨 region 创建：{@link #open} 在「已提交」时返回 {@code true} 并把结果交给
 * {@link #completePanel} 收口，**绝不**在异步任务尚未执行时同步读一个空 holder 就判失败——
 * 那会留下「调用方以为失败、实体却晚到」的孤儿面板。异步创建失败时会整组回滚并标记失败，
 * 失败状态可由 {@link #openFailed(UUID)} 观察。
 *
 * <p>【为什么不是 final】移除路径需要可观测：测试用子类记录 {@code close(UUID)} 调用，
 * 才能证明「清理真的通知到渲染器」，而不是只把内存字段抹掉却留下实体。
 */
public class ChairPanelDisplayService {
    /** 面板实体统一标签：用于调试与残留识别，不参与命中判定。 */
    public static final String ENTITY_TAG = "muz_chair_panel";

    /** 面板的可视距离倍数；太小时玩家稍微后仰就看不到图标。 */
    private static final float VIEW_RANGE = 1.6f;

    /** 命中距离上限（格）。面板贴在椅子旁，正常坐姿下远小于这个值。 */
    private static final double MAX_PICK_DISTANCE = 4.0;

    /** 语音格的图标。用铃铛表示「说话」，不引入额外资源。 */
    private static final Material VOICE_ICON = Material.BELL;

    /** 空槽的占位图标：浅灰玻璃板，读起来像「这个格子是空的」。 */
    private static final Material EMPTY_ICON = Material.LIGHT_GRAY_STAINED_GLASS_PANE;

    /**
     * 底板色 {@code #0E1626}（alpha 235，与参考菜单 {@code opacity: 235} 一致）。
     *
     * <p>【1.10.73：改用 TextDisplay 背景矩形，拿到精确 ARGB】原版方块没有任意 RGB 染色能力，
     * 用 BLACK_CONCRETE 之类的近似会让「暗蓝」变「纯黑」。本机 MuMenu 的
     * {@code BukkitMenuRenderer.quad()} 证明可以用 {@code text(Component.space())} +
     * {@code setDefaultBackground(false)} + {@code backgroundColor(Color.fromARGB(...))}
     * 画任意颜色矩形——MUZ 自身的 Display API 完全够用，**不需要引入 MuMenu 依赖**。
     * 校准系数见 {@link ChairPanelSurface#QUAD_SCALE_X}。
     */
    private static final Color PANEL_BACKDROP = argb(235, 0x0E, 0x16, 0x26);

    /** 边框色 {@code #4C6EA8}。 */
    private static final Color PANEL_BORDER = argb(235, 0x4C, 0x6E, 0xA8);

    /** 槽位卡片色 {@code #22304D}。 */
    private static final Color CARD_BACKGROUND = argb(235, 0x22, 0x30, 0x4D);

    /** 语音卡片色 {@code #3A2A4D}。 */
    private static final Color VOICE_BACKGROUND = argb(235, 0x3A, 0x2A, 0x4D);

    /** 选中卡片高亮：同色系提亮，让「当前选中」在九格里一眼可见。 */
    private static final Color CARD_SELECTED = argb(255, 0x3A, 0x5A, 0x8C);

    /** {@code Color.fromARGB(a, r, g, b)} 的简写，避免每处重复算 alpha 位。 */
    private static Color argb(int alpha, int red, int green, int blue) {
        return Color.fromARGB(alpha, red, green, blue);
    }

    /** 内容相对底板的正面偏移（格），避免与底板共面闪烁。 */
    private static final double CONTENT_LIFT = 0.06;

    /**
     * 层级（沿面板法线的格数），对应 MuMenu 参考里的 z 顺序。
     *
     * <p>参考菜单：底板 z=0、边框 z=0.5、卡片 z=1、文字 z=2（画布像素）。
     * 这里换算成世界格后取三个量级相近的值：底板 0、边框 0.006、卡片 0.012；
     * 文字用 {@link #CONTENT_LIFT}，远在卡片之外，不会再与底色 z-fighting。
     * **三层必须是正向前后叠放**：旧实现把边框放在负偏移（底板背后），
     * 结果边框被底板整块盖住、只剩外扩的一圈，看起来像「板子变大」。
     */
    private static final double BACKDROP_LAYER = 0.0;
    private static final double BORDER_LAYER = 0.006;
    private static final double CARD_LAYER = 0.012;

    /** 文字行高（格，scale=1 时）。用于把画布字号换算成 TextDisplay 的 scale。 */
    private static final double FONT_LINE_BLOCKS_AT_SCALE_ONE = 0.35;

    private final DoudizhuPlugin plugin;

    /** 玩家 → 已渲染的两块面板。写入/删除都在锚点 lane 或玩家 lane 的显式入口，读取可任意 lane。 */
    private final Map<UUID, Rendered> rendered = new ConcurrentHashMap<>();

    /**
     * 面板实体 UUID → 玩家 UUID。
     *
     * <p>事件入口只拿到被点实体的 UUID，而 {@code getUniqueId()} 是裸字段读、不受 region 门禁，
     * 因此用它做归属判定是安全的；反查表也是纯内存，不需要碰实体状态。
     */
    private final Map<UUID, UUID> entityOwners = new ConcurrentHashMap<>();

    /** 创建尝试序号；用于把异步回调归因到「最近一次 open」。 */
    private final AtomicLong attemptSequence = new AtomicLong();

    /** 玩家 → 进行中的创建尝试（跨 region 投递时存在）。 */
    private final Map<UUID, OpenAttempt> openAttempts = new ConcurrentHashMap<>();

    /** 玩家 → 最近一次创建尝试的失败标记；供调用方观察异步失败（纯内存，任意 lane 安全）。 */
    private final Map<UUID, Boolean> openFailures = new ConcurrentHashMap<>();

    public ChairPanelDisplayService(DoudizhuPlugin plugin) {
        this.plugin = java.util.Objects.requireNonNull(plugin, "plugin");
    }

    /**
     * 一块已经渲染出来的面板。
     *
     * @param visuals 该面板的显示实体（底板、边框、九张卡片、九张图标、九段槽位文字、标题/副标题/提示）
     */
    private record PanelView(
        String panelId,
        Location center,
        ChairPanelSurface.Axes axes,
        double halfWidth,
        double halfHeight,
        Interaction hitbox,
        List<org.bukkit.entity.Display> visuals
    ) {
    }

    /** 一个玩家当前已渲染的两块面板。 */
    private record Rendered(UUID worldId, List<PanelView> panels) {
    }

    /** 一次命中：哪块板、第几格（0..8，8 为语音）、距离。 */
    public record Click(String panelId, int cell, double distance) {
    }

    /**
     * 一次跨 region 的创建尝试。
     *
     * <p>为什么需要它：{@code open} 可能在玩家 lane 调用，而两块面板各自位于椅子所在的 region。
     * 投递出去的创建任务**晚于** {@code open} 返回才执行，因此不能用「返回时 holder 还是 null」判失败。
     * 这里用剩余计数收口：全部回调到齐后一次性登记；任何一块失败就把已建实体拆干净。
     */
    private static final class OpenAttempt {
        private final long id;
        private final int total;
        private final AtomicInteger remaining;
        private final List<PanelView> built = Collections.synchronizedList(new ArrayList<>());
        private volatile boolean failed;

        private OpenAttempt(long id, int total) {
            this.id = id;
            this.total = total;
            this.remaining = new AtomicInteger(total);
        }
    }

    /**
     * 该玩家是否已经有面板在渲染（纯内存查询，任意 lane 安全）。
     */
    public boolean isOpen(UUID playerId) {
        return playerId != null && rendered.containsKey(playerId);
    }

    /** 是否还有一次创建尝试尚未收口（跨 region 投递中）。 */
    public boolean isOpenPending(UUID playerId) {
        return playerId != null && openAttempts.containsKey(playerId);
    }

    /** 最近一次创建尝试是否失败（异步失败也能被观察到）。 */
    public boolean openFailed(UUID playerId) {
        return playerId != null && Boolean.TRUE.equals(openFailures.get(playerId));
    }

    /**
     * 读取并清除失败标记（一次性消费）。
     *
     * <p>调用方据此进入失败退避：若只读不清，退避到期后旧标记仍在，会被误判成「又失败了」而永久退避。
     * 标记会在下一次 {@link #open} 开头被清掉，因此留存的标记一定来自上次 open 之后的失败。
     */
    public boolean consumeOpenFailure(UUID playerId) {
        return playerId != null && openFailures.remove(playerId) != null;
    }

    /** 当前已渲染的面板标识集合；没有时为空集。 */
    public Set<String> openPanelIds(UUID playerId) {
        Rendered state = playerId == null ? null : rendered.get(playerId);
        if (state == null) {
            return Set.of();
        }
        java.util.Set<String> ids = new java.util.LinkedHashSet<>();
        for (PanelView panel : state.panels()) {
            ids.add(panel.panelId());
        }
        return Set.copyOf(ids);
    }

    /**
     * 该实体是不是某块椅侧面板的判定盒（纯 UUID 查表，不读实体状态）。
     */
    public boolean ownsPanelEntity(UUID entityId) {
        return entityId != null && entityOwners.containsKey(entityId);
    }

    /**
     * 该面板实体属于哪个玩家；不是面板实体时返回 {@code null}。
     *
     * <p>【为什么必须暴露 owner】面板实体是**逐玩家私有**的（{@code visibleByDefault(false)} +
     * 只对 owner {@code showEntity}），但事件层拿到的是「某个实体被点了」。若只判
     * {@link #ownsPanelEntity}，任何玩家的面板实体都会让事件被"这是面板点击"消费掉——
     * 包括别人的板子（例如跨 region 可见性异常、或未来改成共享可见时）。调用方必须再比对 owner。
     */
    public UUID ownerOf(UUID entityId) {
        return entityId == null ? null : entityOwners.get(entityId);
    }

    /**
     * 按锚点渲染两块面板；已有面板先整体拆除再重建。
     *
     * <p>「先拆后建」是有意的：Dialog 保存后的重绘是低频操作，重建能保证几何、图标与选中态
     * 一次到位，不会留下「位置更新了但图标还是旧槽」这种半更新状态。选中后**不**关闭面板
     * （用户要求常驻），由调用方决定何时重绘。
     *
     * <p>IMPORTANT LANE: 必须在玩家 lane 调用；世界体操作会投递到各锚点 region。
     *
     * <p>【返回值的语义】{@code true} 表示「请求已受理」：要么两块都已同步建好，
     * 要么已投递到锚点 region、结果由 {@link #completePanel} 收口。{@code false} 表示
     * 受理即失败（世界缺失、投递抛异常、同步创建失败），此时已建的实体全部拆除、不会留下孤儿。
     * 异步失败通过 {@link #openFailed(UUID)} 观察，不会伪装成成功。
     *
     * @param slots        八个真实槽位的物品快照（可用元素为 {@code null} 表示空槽）
     * @param selectedSlot 当前选中槽（0-based，{@code -1} 表示没有选中）
     */
    public boolean open(Player player, List<ChairPanelAnchor> anchors, List<ItemStack> slots, int selectedSlot) {
        if (player == null || anchors == null || anchors.isEmpty()) {
            return false;
        }
        UUID playerId = player.getUniqueId();
        close(playerId);
        openFailures.remove(playerId);
        long attemptId = attemptSequence.incrementAndGet();
        OpenAttempt attempt = new OpenAttempt(attemptId, anchors.size());
        openAttempts.put(playerId, attempt);
        for (ChairPanelAnchor anchor : anchors) {
            if (!renderPanel(player, anchor, slots, selectedSlot, attempt)) {
                // 受理阶段就失败（世界不存在 / 投递抛异常）：整组回滚，绝不留半块。
                failAttempt(playerId, attempt);
                return false;
            }
        }
        if (attempt.remaining.get() == 0) {
            // 全部在本次调用里同步完成：直接收口，避免多留一个「进行中」状态。
            finishAttempt(playerId, attempt);
        }
        return !attempt.failed;
    }

    /**
     * 拆除该玩家的两块面板（幂等）。
     *
     * <p>可在任意 lane 调用：状态先摘除，实体删除投递到各自锚点 region，投不出去只记日志。
     * 面板实体全部 {@code persistent = false}，即使删除失败也不会跨重启残留。
     */
    public void close(UUID playerId) {
        if (playerId == null) {
            return;
        }
        openAttempts.remove(playerId);
        Rendered state = rendered.remove(playerId);
        if (state != null) {
            for (PanelView panel : state.panels()) {
                removeEntities(panel);
            }
        }
        // 兜底：所有登记到该玩家的实体 UUID 都要从反查表摘掉，避免事件路由命中已拆实体。
        entityOwners.entrySet().removeIf(entry -> playerId.equals(entry.getValue()));
    }

    /** 停机清理。 */
    public void shutdown() {
        for (UUID playerId : new ArrayList<>(rendered.keySet())) {
            close(playerId);
        }
        rendered.clear();
        entityOwners.clear();
        openAttempts.clear();
        openFailures.clear();
    }

    /**
     * 用玩家当前视线判定命中的面板格位；没有命中返回 {@code null}。
     *
     * <p>IMPORTANT LANE: 必须在玩家 lane 调用（要读眼睛位置与朝向）。
     */
    public Click click(Player player) {
        if (player == null) {
            return null;
        }
        Rendered state = rendered.get(player.getUniqueId());
        if (state == null || state.panels().isEmpty()) {
            return null;
        }
        Location eye = player.getEyeLocation();
        Vector direction = eye.getDirection();
        double blockDistance = firstBlockDistance(player, eye, direction);
        Click best = null;
        for (PanelView panel : state.panels()) {
            DisplayPanelPickGeometry.Rectangle rectangle = new DisplayPanelPickGeometry.Rectangle(
                panel.center(), panel.axes().normal(), panel.axes().right(), panel.axes().up(),
                panel.halfWidth(), panel.halfHeight());
            // 【只认正面命中】面板的可见正面就是 axes().normal 一侧（文字与图标都朝它绘制）。
            // 若允许从背面命中，localX 仍是同一个 right 轴，玩家看到的却是镜像画面——
            // 他会点「视觉左边」而系统判成右边的格子。射线从正面射入时 direction·normal < 0。
            if (direction.dot(panel.axes().normal()) >= 0.0) {
                continue;
            }
            DisplayPanelPickGeometry.Hit hit =
                DisplayPanelPickGeometry.intersect(eye, direction, rectangle, MAX_PICK_DISTANCE);
            if (hit == null || DisplayPanelPickGeometry.occluded(hit, blockDistance)) {
                continue;
            }
            // 命中用 **MuMenu 的不等距 80×36 卡片矩形**，与显示完全一致；间隙返回 OUTSIDE。
            int cell = ChairPanelSurface.cellAt(
                hit.localX(), hit.localY(), panel.halfWidth() * 2.0, panel.halfHeight() * 2.0);
            if (cell == ChairPanelSurface.OUTSIDE) {
                continue;
            }
            if (best == null || hit.distance() < best.distance()) {
                best = new Click(panel.panelId(), cell, hit.distance());
            }
        }
        return best;
    }

    /** 该玩家已渲染面板的判定盒 UUID 集合；用于把实体事件精确归因到某块板。 */
    public Set<UUID> hitboxIds(UUID playerId) {
        Rendered state = playerId == null ? null : rendered.get(playerId);
        if (state == null) {
            return Set.of();
        }
        java.util.Set<UUID> ids = new java.util.LinkedHashSet<>();
        for (PanelView panel : state.panels()) {
            if (panel.hitbox() != null) {
                ids.add(panel.hitbox().getUniqueId());
            }
        }
        return Set.copyOf(ids);
    }

    // ------------------------------------------------------------------ 创建收口

    /**
     * 单块面板的创建（同步或投递）。
     *
     * @return {@code false} 表示「连受理都失败」（世界缺失 / 投递被拒 / 投递抛异常）；
     *     {@code true} 表示已内联完成或已投递；异步创建失败走
     *     {@link #failAttempt}，不会在这里返回
     */
    private boolean renderPanel(
        Player player,
        ChairPanelAnchor anchor,
        List<ItemStack> slots,
        int selectedSlot,
        OpenAttempt attempt
    ) {
        if (anchor == null) {
            return false;
        }
        org.bukkit.World world = Bukkit.getWorld(anchor.worldId());
        if (world == null) {
            return false;
        }
        Location center = new Location(world, anchor.x(), anchor.y(), anchor.z());
        ChairPanelSurface.Axes axes =
            ChairPanelSurface.axes(anchor.yawDegrees(), anchor.pitchDegrees(), anchor.rollDegrees());
        double halfWidth = anchor.widthBlocks() * 0.5;
        double halfHeight = anchor.heightBlocks() * 0.5;
        try {
            // 已拥有该 region 时内联执行；否则投递，结果由 completePanel 收口。
            return runOnAnchorRegion(center, () -> {
                PanelView view = spawnPanel(player, anchor, center, axes, halfWidth, halfHeight, slots, selectedSlot);
                completePanel(player.getUniqueId(), attempt, view);
            });
        } catch (RuntimeException failure) {
            plugin.getLogger().severe("渲染椅侧面板失败（" + anchor.panelId() + "）：" + failure);
            return false;
        }
    }

    /** 单块面板创建完成（或失败）后的收口：全部到齐才登记，任何一块失败就整组回滚。 */
    private void completePanel(UUID playerId, OpenAttempt attempt, PanelView view) {
        if (view == null) {
            failAttempt(playerId, attempt);
            return;
        }
        OpenAttempt current = openAttempts.get(playerId);
        if (current != attempt) {
            // 这次尝试已被新一次 open/close 取代：把刚建好的实体拆掉，不能让它成为孤儿。
            removeEntities(view);
            return;
        }
        attempt.built.add(view);
        if (attempt.remaining.decrementAndGet() == 0) {
            finishAttempt(playerId, attempt);
        }
    }

    private void finishAttempt(UUID playerId, OpenAttempt attempt) {
        OpenAttempt current = openAttempts.get(playerId);
        if (current != attempt) {
            return;
        }
        openAttempts.remove(playerId);
        if (attempt.failed || attempt.built.size() != attempt.total) {
            failAttempt(playerId, attempt);
            return;
        }
        openFailures.remove(playerId);
        rendered.put(playerId, new Rendered(
            attempt.built.getFirst().center().getWorld().getUID(), List.copyOf(attempt.built)));
    }

    private void failAttempt(UUID playerId, OpenAttempt attempt) {
        attempt.failed = true;
        // 【绝不无条件 remove/标记失败】异步创建可能跨多次 open：旧 attempt 的失败回调晚到时，
        // 若这里直接 remove(playerId)，会把**新** attempt 从表里摘掉、并给新一次开板打上失败标记，
        // 结果新面板创建完成后无法登记（看起来"面板打不开"）。只有当前 attempt 才允许改共享状态。
        boolean current = openAttempts.get(playerId) == attempt;
        if (current) {
            openAttempts.remove(playerId);
            openFailures.put(playerId, Boolean.TRUE);
        }
        for (PanelView built : new ArrayList<>(attempt.built)) {
            removeEntities(built);
        }
        attempt.built.clear();
        if (current) {
            plugin.getLogger().severe("椅侧面板创建失败，已整组回滚（" + playerId + "）");
        }
    }

    // ------------------------------------------------------------------ 渲染

    private PanelView spawnPanel(
        Player player,
        ChairPanelAnchor anchor,
        Location center,
        ChairPanelSurface.Axes axes,
        double halfWidth,
        double halfHeight,
        List<ItemStack> slots,
        int selectedSlot
    ) {
        org.bukkit.World world = center.getWorld();
        if (world == null) {
            return null;
        }
        double width = anchor.widthBlocks();
        double height = anchor.heightBlocks();
        List<org.bukkit.entity.Display> visuals = new ArrayList<>(40);
        // 判定盒在 try 内创建、在 catch 里也要能清理，因此提升到 try 外声明。
        Interaction hitbox = null;
        try {
            // 1) 边框：按 MuMenu 参考做成 **298×198、2 像素粗的描边**，画在底板**前层**
            //    （参考菜单 panel-border 为 frame + z: 0.5）。旧实现是一条 304×204 的实心板、
            //    还压在底板背后（负偏移），既放大了 4 像素又把边露在板外——视觉完全不对。
            //    这里拆成上/下/左/右四条细条，外沿正好 298×198、相对 300×200 各内缩 1 像素。
            double[][] strips = ChairPanelSurface.borderStrips();
            for (double[] strip : strips) {
                double[] stripCenter = ChairPanelSurface.canvasToLocal(strip[0], strip[1], width, height);
                double[] stripSize = ChairPanelSurface.canvasToLocal(strip[2], strip[3], width, height);
                visuals.add(spawnQuadAt(world, center, axes, anchor, stripCenter,
                    stripSize[0], stripSize[1], BORDER_LAYER, PANEL_BORDER));
            }
            // 2) 底板 300×200。
            double[] backdropSize = ChairPanelSurface.canvasToLocal(
                ChairPanelSurface.CANVAS_WIDTH, ChairPanelSurface.CANVAS_HEIGHT, width, height);
            visuals.add(spawnQuadAt(world, center, axes, anchor, new double[] {0.0, 0.0},
                backdropSize[0], backdropSize[1], BACKDROP_LAYER, PANEL_BACKDROP));

            // 3) 标题 / 当前选中 / 底部提示（文字色直接对应菜单的 &6 / &7 / &8 语义）。
            visuals.add(spawnText(world, center, axes, anchor,
                ChairPanelSurface.TITLE_Y, Component.text("MUZ 桌内道具", NamedTextColor.GOLD)
                    .decoration(TextDecoration.BOLD, true), 16, width, height));
            visuals.add(spawnText(world, center, axes, anchor,
                ChairPanelSurface.SUBTITLE_Y, selectedLine(slots, selectedSlot), 10, width, height));
            visuals.add(spawnText(world, center, axes, anchor,
                ChairPanelSurface.HINT_Y, Component.text("点击选择道具槽；第九格打开语音", NamedTextColor.DARK_GRAY),
                8, width, height));

            // 4) 九张卡片 + 图标 + 名称/状态。左右两块内容同构，区别只在位置。
            for (int cell = 0; cell < ChairPanelSurface.CELL_COUNT; cell++) {
                double[] cardCenter = ChairPanelSurface.cellCenter(cell, width, height);
                double[] cardSize = ChairPanelSurface.cardSize(width, height);
                boolean voice = cell == ChairPanelSurface.VOICE_CELL;
                boolean selected = cell == selectedSlot;
                Color cardMaterial = voice
                    ? VOICE_BACKGROUND
                    : (selected ? CARD_SELECTED : CARD_BACKGROUND);
                visuals.add(spawnQuadAt(world, center, axes, anchor, cardCenter,
                    cardSize[0], cardSize[1], CARD_LAYER, cardMaterial));

                // 【图标与两行文字必须分列，否则在 80×36 的卡片里必然重叠】
                // 图标固定在卡片左侧 22px 方框；名称（cy+6）与状态（cy−8）放在右侧 52px 文字列。
                // 图标右沿 −16px、文字列左沿 −14px，中间留 2px；纵向两行之间也留 4.5px。
                // 这组数字由 ChairPanelSurface 的常量唯一给出，并被 ChairPanelSurfaceTest 验算。
                double[] iconSpot = ChairPanelSurface.iconCenter(cell, width, height);
                ItemStack stack = iconFor(cell, slots);
                if (stack != null) {
                    visuals.add(spawnIcon(world, center, axes, anchor, iconSpot, stack, width, height, selected));
                }
                double textX = ChairPanelSurface.textColumnCenterX(cell, width);
                visuals.add(spawnTextAt(world, center, axes, anchor,
                    textX, cardCenter[1] + ChairPanelSurface.SLOT_NAME_DY * ChairPanelSurface.scaleY(height),
                    slotNameLine(cell, slots), 11, width, height, selected && !voice));
                visuals.add(spawnTextAt(world, center, axes, anchor,
                    textX, cardCenter[1] + ChairPanelSurface.SLOT_STATE_DY * ChairPanelSurface.scaleY(height),
                    slotStateLine(cell, slots, selected), 8, width, height, false));
            }

            // 【判定盒锚点必须落在显示矩形的底边】Interaction 的 AABB 从实体位置向上生长，
            // 而显示平面以实体位置居中：把盒子放在 center 并给面板高度，覆盖的是
            // [centerY, centerY+h]，下半块（第 2/3 行含语音格）完全没有判定盒 → 点不到。
            // ChairPanelSurface.interactionBox 按四角求竖直投影的 min/max 与水平最大投影。
            ChairPanelSurface.InteractionBox box = ChairPanelSurface.interactionBox(
                width, height, anchor.yawDegrees(), anchor.pitchDegrees(), anchor.rollDegrees());
            Location hitboxAnchor = center.clone().add(0.0, box.anchorYOffset(), 0.0);
            hitbox = VersionCompat.spawnEntity(world, hitboxAnchor, Interaction.class, spawned -> {
                // 【判定盒同样必须"默认不可见、只对 owner 显示"】
                // 只对 Display 调用 visibleByDefault(false) 是不够的：Interaction 也是实体，
                // 默认会被所有追踪到它的玩家看到并参与客户端拾取——于是同桌其他人也能点到我的板子。
                // 这里与其余面板实体同口径：默认不可见 + 仅对 owner showEntity。
                spawned.setVisibleByDefault(false);
                spawned.setInteractionWidth((float) box.coverWidth());
                spawned.setInteractionHeight((float) box.coverHeight());
                spawned.setResponsive(true);
                spawned.setPersistent(false);
                spawned.addScoreboardTag(ENTITY_TAG);
            });
            hitbox.setRotation(anchor.yawDegrees(), anchor.pitchDegrees());
            // 私有可见 + 逐个 showEntity：面板只对该玩家可见，不会影响同桌其它人的画面。
            player.showEntity(plugin, hitbox);
            for (org.bukkit.entity.Display visual : visuals) {
                player.showEntity(plugin, visual);
            }
            entityOwners.put(hitbox.getUniqueId(), player.getUniqueId());
            for (org.bukkit.entity.Display visual : visuals) {
                entityOwners.put(visual.getUniqueId(), player.getUniqueId());
            }
            return new PanelView(anchor.panelId(), center.clone(), axes, halfWidth, halfHeight, hitbox, visuals);
        } catch (RuntimeException | Error failure) {
            // 【必须连判定盒一起拆】判定盒是在 try 内最后创建的，若它之后的 showEntity/登记抛异常，
            // 只清 visuals 会把 Interaction 留在世界里——它对 owner 可见可点，却没有任何面板，
            // 而且没人再持有它的 UUID，属于无法回收的孤儿实体。
            for (org.bukkit.entity.Display visual : visuals) {
                removeQuietly(visual);
            }
            removeQuietly(hitbox);
            if (hitbox != null) {
                entityOwners.remove(hitbox.getUniqueId());
            }
            plugin.getLogger().severe("渲染椅侧面板失败（" + anchor.panelId() + "）：" + failure);
            return null;
        }
    }

    /**
     * 在面板局部坐标处铺一块**精确 ARGB** 的矩形（底板 / 边框条 / 卡片底色）。
     *
     * <p>手法与 MuMenu {@code BukkitMenuRenderer.quad()} 相同：一个只含空格的 {@code TextDisplay}，
     * 关掉默认背景、把背景色设成目标 ARGB，再用 {@code Transformation} 把「单空格背景四边形」
     * 放大成目标世界尺寸。校准与居中推导见 {@link ChairPanelSurface#quadTranslation} 与
     * {@link ChairPanelSurface#quadScale}：translation 必须随 roll 一起旋转，
     * 否则一开 roll 矩形就会朝一侧甩出去。
     */
    private TextDisplay spawnQuadAt(org.bukkit.World world, Location center, ChairPanelSurface.Axes axes,
                                    ChairPanelAnchor anchor, double[] local, double width, double height,
                                    double layerBlocks, Color color) {
        Location spot = center.clone()
            .add(axes.right().clone().multiply(local[0]))
            .add(axes.up().clone().multiply(local[1]))
            .add(axes.normal().clone().multiply(layerBlocks));
        spot.setYaw(anchor.yawDegrees());
        spot.setPitch(anchor.pitchDegrees());
        double[] translation = ChairPanelSurface.quadTranslation(width, height, anchor.rollDegrees());
        double[] scale = ChairPanelSurface.quadScale(width, height);
        TextDisplay quad = VersionCompat.spawnEntity(world, spot, TextDisplay.class, spawned -> {
            spawned.setVisibleByDefault(false);
            spawned.setPersistent(false);
            spawned.setBillboard(Display.Billboard.FIXED);
            spawned.setViewRange(VIEW_RANGE);
            spawned.setSeeThrough(false);
            spawned.setShadowed(false);
            spawned.setDefaultBackground(false);
            spawned.setAlignment(TextDisplay.TextAlignment.CENTER);
            spawned.setTextOpacity((byte) 255);
            spawned.setBrightness(new Display.Brightness(15, 15));
            spawned.text(Component.space());
            spawned.setBackgroundColor(color);
            spawned.setTransformation(new Transformation(
                new Vector3f((float) translation[0], (float) translation[1], 0.0f),
                new AxisAngle4f((float) Math.toRadians(anchor.rollDegrees()), 0.0f, 0.0f, 1.0f),
                new Vector3f((float) scale[0], (float) scale[1], 1.0f),
                new AxisAngle4f()));
            spawned.addScoreboardTag(ENTITY_TAG);
        });
        quad.setRotation(anchor.yawDegrees(), anchor.pitchDegrees());
        return quad;
    }

    /** 卡片里的真实物品图标；空槽用占位玻璃板，语音格用铃铛。 */
    private ItemDisplay spawnIcon(org.bukkit.World world, Location center, ChairPanelSurface.Axes axes,
                                  ChairPanelAnchor anchor, double[] iconSpot, ItemStack stack,
                                  double width, double height, boolean selected) {
        Location spot = center.clone()
            .add(axes.right().clone().multiply(iconSpot[0]))
            .add(axes.up().clone().multiply(iconSpot[1]))
            .add(axes.normal().clone().multiply(CONTENT_LIFT));
        spot.setYaw(anchor.yawDegrees());
        spot.setPitch(anchor.pitchDegrees());
        float iconScale = ChairPanelSurface.iconScale(width, height);
        return VersionCompat.spawnEntity(world, spot, ItemDisplay.class, spawned -> {
            spawned.setVisibleByDefault(false);
            spawned.setPersistent(false);
            spawned.setItemStack(stack);
            spawned.setItemDisplayTransform(ItemDisplay.ItemDisplayTransform.FIXED);
            spawned.setBillboard(Display.Billboard.FIXED);
            spawned.setViewRange(VIEW_RANGE);
            spawned.setTransformation(new Transformation(
                new Vector3f(),
                new AxisAngle4f((float) Math.toRadians(anchor.rollDegrees()), 0.0f, 0.0f, 1.0f),
                new Vector3f(iconScale, iconScale, 1.0f),
                new AxisAngle4f()));
            spawned.addScoreboardTag(ENTITY_TAG);
            if (selected) {
                spawned.setGlowing(true);
            }
        });
    }

    /** 画布上的一行文字（标题/副标题/提示）。 */
    private TextDisplay spawnText(org.bukkit.World world, Location center, ChairPanelSurface.Axes axes,
                                  ChairPanelAnchor anchor, double canvasY, Component text, int canvasPixels,
                                  double width, double height) {
        double localY = canvasY * ChairPanelSurface.scaleY(height);
        return spawnTextAt(world, center, axes, anchor, 0.0, localY, text, canvasPixels, width, height, false);
    }

    /** 卡片里的名称/状态文字。 */
    private TextDisplay spawnTextAt(org.bukkit.World world, Location center, ChairPanelSurface.Axes axes,
                                    ChairPanelAnchor anchor, double localX, double localY, Component text,
                                    int canvasPixels, double width, double height, boolean highlight) {
        return spawnTextAt(world, center, axes, anchor, localX, localY, text, canvasPixels, width, height,
            highlight, ChairPanelSurface.CANVAS_WIDTH);
    }

    /** 带显式换行宽度的版本：卡片文字必须限制在右列宽度内，不能按整块面板折行。 */
    private TextDisplay spawnTextAt(org.bukkit.World world, Location center, ChairPanelSurface.Axes axes,
                                    ChairPanelAnchor anchor, double localX, double localY, Component text,
                                    int canvasPixels, double width, double height, boolean highlight,
                                    double lineWidthCanvasPixels) {
        Location spot = center.clone()
            .add(axes.right().clone().multiply(localX))
            .add(axes.up().clone().multiply(localY))
            .add(axes.normal().clone().multiply(CONTENT_LIFT * 1.2));
        spot.setYaw(anchor.yawDegrees());
        spot.setPitch(anchor.pitchDegrees());
        // 画布字号 → 世界 scale：一行文字在 scale=1 时约占 FONT_LINE_BLOCKS_AT_SCALE_ONE 格。
        float textScale = (float) Math.max(0.03,
            canvasPixels * ChairPanelSurface.scaleY(height) / FONT_LINE_BLOCKS_AT_SCALE_ONE);
        return VersionCompat.spawnEntity(world, spot, TextDisplay.class, spawned -> {
            spawned.setVisibleByDefault(false);
            spawned.setPersistent(false);
            spawned.setBillboard(Display.Billboard.FIXED);
            spawned.setViewRange(VIEW_RANGE);
            spawned.setSeeThrough(false);
            spawned.setShadowed(false);
            spawned.setAlignment(TextDisplay.TextAlignment.CENTER);
            spawned.setDefaultBackground(false);
            spawned.setTextOpacity((byte) 255);
            spawned.text(text);
            // 换行宽度按**画布像素**给：卡片文字用右列宽，标题/提示用整块面板宽。
            spawned.setLineWidth((int) Math.round(lineWidthCanvasPixels));
            spawned.setTransformation(new Transformation(
                new Vector3f(),
                new AxisAngle4f((float) Math.toRadians(anchor.rollDegrees()), 0.0f, 0.0f, 1.0f),
                new Vector3f(textScale, textScale, 1.0f),
                new AxisAngle4f()));
            spawned.addScoreboardTag(ENTITY_TAG);
            if (highlight) {
                spawned.setBackgroundColor(Color.fromARGB(90, 0x4C, 0x6E, 0xA8));
            }
        });
    }

    // ------------------------------------------------------------------ 文案（同一份槽快照）

    /** 副标题：当前选中（{@code 1..8} 或 {@code 语音}）。 */
    private static Component selectedLine(List<ItemStack> slots, int selectedSlot) {
        String selected = selectedSlot == ChairPanelSurface.VOICE_CELL
            ? "语音"
            : String.valueOf(selectedSlot + 1);
        return Component.text("当前选中：", NamedTextColor.GRAY)
            .append(Component.text(selected, NamedTextColor.YELLOW));
    }

    /** 卡片第一行：槽号 + 道具名（空槽写「空槽 n」）。名称来自 MUZ 自己的槽快照，不依赖 PAPI。 */
    private static Component slotNameLine(int cell, List<ItemStack> slots) {
        if (cell == ChairPanelSurface.VOICE_CELL) {
            return Component.text("语音", NamedTextColor.AQUA);
        }
        ItemStack stack = slotAt(cell, slots);
        if (stack == null) {
            return Component.text("空槽 " + (cell + 1), NamedTextColor.DARK_GRAY);
        }
        return Component.text(displayNameOf(stack), NamedTextColor.WHITE);
    }

    /** 卡片第二行：空/有 + 是否选中，对应菜单里的「空:… 选:…」。 */
    private static Component slotStateLine(int cell, List<ItemStack> slots, boolean selected) {
        boolean empty = cell != ChairPanelSurface.VOICE_CELL && slotAt(cell, slots) == null;
        NamedTextColor stateColor = selected ? NamedTextColor.YELLOW : NamedTextColor.GRAY;
        return Component.text("空:" + (empty ? "是" : "否"), NamedTextColor.GRAY)
            .append(Component.text(" 选:" + (selected ? "是" : "否"), stateColor));
    }

    private static ItemStack slotAt(int cell, List<ItemStack> slots) {
        if (cell < 0 || cell >= ChairPanelSurface.VOICE_CELL || slots == null || cell >= slots.size()) {
            return null;
        }
        ItemStack stack = slots.get(cell);
        return stack == null || stack.getType().isAir() ? null : stack;
    }

    private static String displayNameOf(ItemStack stack) {
        ItemMeta meta = stack.getItemMeta();
        if (meta != null && meta.hasDisplayName()) {
            // 名称快照只用于面板文字，不参与任何逻辑判定，因此退化为纯文本即可。
            return net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer.plainText()
                .serialize(meta.displayName());
        }
        return stack.getType().name();
    }

    private ItemStack iconFor(int cell, List<ItemStack> slots) {
        if (cell == ChairPanelSurface.VOICE_CELL) {
            return named(new ItemStack(VOICE_ICON), "语音");
        }
        ItemStack slot = slotAt(cell, slots);
        if (slot == null) {
            return named(new ItemStack(EMPTY_ICON), "空槽 " + (cell + 1));
        }
        return slot.clone();
    }

    private static ItemStack named(ItemStack stack, String name) {
        ItemMeta meta = stack.getItemMeta();
        if (meta != null) {
            meta.displayName(Component.text(name).decoration(TextDecoration.ITALIC, false));
            stack.setItemMeta(meta);
        }
        return stack;
    }

    private void removeEntities(PanelView panel) {
        if (panel == null) {
            return;
        }
        runOnAnchorRegionQuietly(panel.center(), () -> {
            removeQuietly(panel.hitbox());
            for (org.bukkit.entity.Display visual : panel.visuals()) {
                removeQuietly(visual);
            }
        });
        if (panel.hitbox() != null) {
            entityOwners.remove(panel.hitbox().getUniqueId());
        }
        for (org.bukkit.entity.Display visual : panel.visuals()) {
            entityOwners.remove(visual.getUniqueId());
        }
    }

    private void removeQuietly(Entity entity) {
        if (entity == null) {
            return;
        }
        try {
            if (entity.isValid()) {
                entity.remove();
            }
        } catch (RuntimeException failure) {
            // 面板实体全部 non-persistent，删不掉也只影响本次会话；留痕便于排查跨 region 情况。
            plugin.getLogger().warning("移除椅侧面板实体失败（" + entity.getUniqueId() + "）：" + failure);
        }
    }

    // ------------------------------------------------------------------ lane

    /**
     * 在锚点所属 region 上执行世界体操作。
     *
     * @return {@code true} 表示**已受理**：要么已内联执行完，要么已成功投递、结果异步到达；
     *     {@code false} 表示没有受理（世界缺失，或调度器拒绝投递并返回已取消句柄——
     *     例如关闭期）。【勘误】旧版把「已投递」也返回 {@code false}，{@link #renderPanel}
     *     据此把跨 region 的开板当成受理失败立即回滚，晚到的创建任务又因尝试已失效而自拆，
     *     结果跨 region 时面板永远打不开；而被拒绝的投递又没有任何回调，尝试会永久挂起。
     */
    private boolean runOnAnchorRegion(Location anchor, Runnable task) {
        if (anchor == null || anchor.getWorld() == null) {
            return false;
        }
        if (ownsRegion(anchor)) {
            task.run();
            return true;
        }
        linmumua.doudizhu.scheduler.MuzScheduler.TaskHandle handle = plugin.scheduler().runRegion(anchor, task);
        return handle != null && !handle.isCancelled();
    }

    /** 删除路径专用：投递失败只记日志，不影响「状态已摘除」的结论。 */
    private void runOnAnchorRegionQuietly(Location anchor, Runnable task) {
        try {
            runOnAnchorRegion(anchor, task);
        } catch (RuntimeException failure) {
            plugin.getLogger().warning("椅侧面板清理投递失败：" + failure);
        }
    }

    private static boolean ownsRegion(Location anchor) {
        try {
            return Bukkit.isOwnedByCurrentRegion(anchor);
        } catch (RuntimeException | LinkageError failure) {
            // 认不出就按「不拥有」处理：宁可多投递一次，也不在错误线程上直接写世界。
            return false;
        }
    }

    private static double firstBlockDistance(Player player, Location eye, Vector direction) {
        try {
            RayTraceResult result = player.getWorld().rayTraceBlocks(
                eye, direction, MAX_PICK_DISTANCE, FluidCollisionMode.NEVER, true);
            return result == null || result.getHitPosition() == null
                ? Double.POSITIVE_INFINITY
                : result.getHitPosition().distance(eye.toVector());
        } catch (RuntimeException failure) {
            return Double.POSITIVE_INFINITY;
        }
    }
}
