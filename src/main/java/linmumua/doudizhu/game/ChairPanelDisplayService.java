package linmumua.doudizhu.game;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import linmumua.doudizhu.DoudizhuPlugin;
import linmumua.doudizhu.compat.VersionCompat;
import linmumua.doudizhu.world.DisplayPanelPickGeometry;
import net.kyori.adventure.text.Component;
import org.bukkit.Bukkit;
import org.bukkit.FluidCollisionMode;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.entity.Display;
import org.bukkit.entity.Entity;
import org.bukkit.entity.BlockDisplay;
import org.bukkit.entity.Interaction;
import org.bukkit.entity.ItemDisplay;
import org.bukkit.entity.TextDisplay;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.util.RayTraceResult;
import org.bukkit.util.Transformation;
import org.bukkit.util.Vector;
import org.joml.AxisAngle4f;
import org.joml.Vector3f;

/**
 * 椅侧左右两块面板的**内置渲染**：MUZ 自己用 {@code ItemDisplay} + 一个 {@code Interaction}
 * 在世界里画出来，不再要求服务端安装 MuMenu，也不再有「宿主菜单画布宽高比」这种外部约束。
 *
 * <p>【为什么内置】椅侧面板是本插件对局体验的一部分：玩家坐下就该看到左右两块可选道具的板子。
 * 依赖另一个插件提供渲染，会让「没装 / 版本不匹配 / 画布比例不符」直接变成「面板不存在」，
 * 而外部插件还各自有自己的一人一面、眼睛锚定、会话替换等语义。内置后 MUZ 只依赖
 * Bukkit 的 Display API，位置与三轴旋转完全由本地配置决定。
 *
 * <p>【几何与命中同源】显示位置、图标缩放、点击命中都来自 {@link ChairPanelSurface} 与
 * {@link DisplayPanelPickGeometry}：射线先与面板平面求交，再映射到九宫格。绝不按实体碰撞箱
 * 猜格位——{@code Interaction} 只有一个盒子，格位必须由局部坐标算，否则点哪个格子都落同一槽。
 *
 * <p>【lane】实体建、改、删一律在**锚点所属 region** 上执行（见 {@link #runOnAnchorRegion}）；
 * 玩家级调用（{@code showEntity}）也放在该 lane 内，这与项目既有结论一致：{@code showEntity}
 * 本身要读实体状态，只有在实体 owner region 上调用才合法。面板状态表用并发容器，
 * 任意 lane 只读快照。
 *
 * <p>【不做自动常驻】只提供显式的 open / refresh / close。选中道具后由调用方关闭面板
 * （「选完即关、再用准星右键投掷」的既有交互不变），不存在每 tick 自动重开的定时器。
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

    /** 面板底板：深灰混凝土，整块铺满面板尺寸，让两块板看起来是「面板」而不是漂浮的图标。 */
    private static final Material PANEL_BACKDROP = Material.GRAY_CONCRETE;

    /** 底板厚度（格）。太厚会看到边缘，太薄会与图标 z-fighting。 */
    private static final float BACKDROP_THICKNESS = 0.02f;

    /** 图标/文字相对底板的正面偏移（格），避免与底板共面闪烁。 */
    private static final double CONTENT_LIFT = 0.035;

    /** 空槽的占位图标：浅灰玻璃板，读起来像「这个格子是空的」。 */
    private static final Material EMPTY_ICON = Material.LIGHT_GRAY_STAINED_GLASS_PANE;

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

    public ChairPanelDisplayService(DoudizhuPlugin plugin) {
        this.plugin = java.util.Objects.requireNonNull(plugin, "plugin");
    }

    /** 一块已经渲染出来的面板。 */
    /**
     * 一块已经渲染出来的面板。
     *
     * @param visuals 该面板的显示实体（左板是 8 个物品图标，右板是一个文字块）
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
     * 该玩家是否已经有面板在渲染（纯内存查询，任意 lane 安全）。
     */
    public boolean isOpen(UUID playerId) {
        return playerId != null && rendered.containsKey(playerId);
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
     * 按锚点渲染两块面板；已有面板先整体拆除再重建。
     *
     * <p>「先拆后建」是有意的：Dialog 保存后的重绘是低频操作，重建能保证几何、图标与选中态
     * 一次到位，不会留下「位置更新了但图标还是旧槽」这种半更新状态。选中后关闭面板的语义
     * 由调用方负责，这里不做自动常驻。
     *
     * <p>IMPORTANT LANE: 必须在玩家 lane 调用；世界体操作会投递到各锚点 region。
     *
     * @param slots        八个真实槽位的物品快照（可用元素为 {@code null} 表示空槽）
     * @param selectedSlot 当前选中槽（0-based，{@code -1} 表示没有选中）
     * @return 两块都渲染成功才返回 {@code true}；任一块失败会回滚整组，绝不留半块
     */
    public boolean open(Player player, List<ChairPanelAnchor> anchors, List<ItemStack> slots, int selectedSlot) {
        if (player == null || anchors == null || anchors.isEmpty()) {
            return false;
        }
        UUID playerId = player.getUniqueId();
        close(playerId);
        List<PanelView> panels = new ArrayList<>(anchors.size());
        for (ChairPanelAnchor anchor : anchors) {
            PanelView view = renderPanel(player, anchor, slots, selectedSlot);
            if (view == null) {
                // 任一块失败：把已经建好的那几块也拆掉，避免「只有左半边」。
                for (PanelView built : panels) {
                    removeEntities(built);
                }
                return false;
            }
            panels.add(view);
        }
        rendered.put(playerId, new Rendered(anchors.getFirst().worldId(), List.copyOf(panels)));
        return true;
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
            DisplayPanelPickGeometry.Hit hit =
                DisplayPanelPickGeometry.intersect(eye, direction, rectangle, MAX_PICK_DISTANCE);
            if (hit == null || DisplayPanelPickGeometry.occluded(hit, blockDistance)) {
                continue;
            }
            int cell = ChairPanelSurface.cellAt(
                hit.localX(), hit.localY(), panel.halfWidth(), panel.halfHeight());
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

    // ------------------------------------------------------------------ 渲染

    private PanelView renderPanel(
        Player player,
        ChairPanelAnchor anchor,
        List<ItemStack> slots,
        int selectedSlot
    ) {
        if (anchor == null) {
            return null;
        }
        org.bukkit.World world = Bukkit.getWorld(anchor.worldId());
        if (world == null) {
            return null;
        }
        Location center = new Location(world, anchor.x(), anchor.y(), anchor.z());
        ChairPanelSurface.Axes axes =
            ChairPanelSurface.axes(anchor.yawDegrees(), anchor.pitchDegrees(), anchor.rollDegrees());
        double halfWidth = anchor.widthBlocks() * 0.5;
        double halfHeight = anchor.heightBlocks() * 0.5;

        // 判定盒与图标都在锚点 region 上创建；不同 region 时整块投递，调用方拿到的是"已提交"。
        PanelView[] holder = new PanelView[1];
        runOnAnchorRegion(center, () -> holder[0] = spawnPanel(player, anchor, center, axes, halfWidth, halfHeight, slots, selectedSlot));
        return holder[0];
    }

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
        float iconScale = ChairPanelSurface.iconScale(anchor.widthBlocks(), anchor.heightBlocks());
        List<org.bukkit.entity.Display> visuals = new ArrayList<>(ChairPanelSurface.CELL_COUNT);
        // 【左板放物品、右板放文字】用户明确要求：左侧全是物品、右侧全是语言文字。
        // 两块板仍然是各自独立的世界体与判定盒，只是内容分工不同。
        boolean textPanel = ChairPanelGeometry.PANEL_ID_RIGHT.equals(anchor.panelId());
        try {
            // 【先铺底板：这是「面板」的视觉主体】只有图标时玩家看到的是几块漂浮物品，
            // 不像菜单。这里按面板真实尺寸铺一块 1.5 x 1.0 的板，内容再贴到它正面上。
            Location backdropSpot = center.clone()
                .add(axes.normal().clone().multiply(-BACKDROP_THICKNESS * 0.5));
            backdropSpot.setYaw(anchor.yawDegrees());
            backdropSpot.setPitch(anchor.pitchDegrees());
            BlockDisplay backdrop = VersionCompat.spawnEntity(world, backdropSpot, BlockDisplay.class, spawned -> {
                spawned.setVisibleByDefault(false);
                spawned.setPersistent(false);
                spawned.setBlock(PANEL_BACKDROP.createBlockData());
                spawned.setBillboard(Display.Billboard.FIXED);
                spawned.setViewRange(VIEW_RANGE);
                spawned.setBrightness(new Display.Brightness(15, 15));
                // 1 格材质缩放到面板世界尺寸；厚度单独压薄。
                spawned.setTransformation(new Transformation(
                    new Vector3f(),
                    new AxisAngle4f((float) Math.toRadians(anchor.rollDegrees()), 0.0f, 0.0f, 1.0f),
                    new Vector3f((float) anchor.widthBlocks(), (float) anchor.heightBlocks(), BACKDROP_THICKNESS),
                    new AxisAngle4f()));
                spawned.addScoreboardTag(ENTITY_TAG);
            });
            backdrop.setRotation(anchor.yawDegrees(), anchor.pitchDegrees());
            visuals.add(backdrop);
            if (textPanel) {
                TextDisplay text = VersionCompat.spawnEntity(world, center.clone()
                    .add(axes.normal().clone().multiply(CONTENT_LIFT)), TextDisplay.class, spawned -> {
                    spawned.setVisibleByDefault(false);
                    spawned.setPersistent(false);
                    spawned.setBillboard(Display.Billboard.FIXED);
                    spawned.setViewRange(VIEW_RANGE);
                    spawned.setSeeThrough(false);
                    spawned.setShadowed(false);
                    spawned.setAlignment(TextDisplay.TextAlignment.CENTER);
                    spawned.text(panelGuideText(slots));
                    spawned.setTransformation(new Transformation(
                        new Vector3f(),
                        new AxisAngle4f((float) Math.toRadians(anchor.rollDegrees()), 0.0f, 0.0f, 1.0f),
                        new Vector3f(1.0f, 1.0f, 1.0f),
                        new AxisAngle4f()));
                    spawned.addScoreboardTag(ENTITY_TAG);
                });
                visuals.add(text);
            }
            for (int cell = 0; !textPanel && cell < ChairPanelSurface.CELL_COUNT; cell++) {
                double[] local = ChairPanelSurface.cellCenter(cell, anchor.widthBlocks(), anchor.heightBlocks());
                Location spot = center.clone()
                    .add(axes.right().clone().multiply(local[0]))
                    .add(axes.up().clone().multiply(local[1]))
                    .add(axes.normal().clone().multiply(CONTENT_LIFT));
                spot.setYaw(anchor.yawDegrees());
                spot.setPitch(anchor.pitchDegrees());
                ItemStack stack = iconFor(cell, slots);
                boolean selected = cell == selectedSlot;
                ItemDisplay display = VersionCompat.spawnEntity(world, spot, ItemDisplay.class, spawned -> {
                    spawned.setVisibleByDefault(false);
                    spawned.setPersistent(false);
                    spawned.setItemStack(stack);
                    spawned.setItemDisplayTransform(ItemDisplay.ItemDisplayTransform.FIXED);
                    spawned.setBillboard(Display.Billboard.FIXED);
                    spawned.setViewRange(VIEW_RANGE);
                    // roll 用左旋转表达；yaw/pitch 由实体自身朝向承担，两者叠加就是完整三轴。
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
                visuals.add(display);
            }
            Interaction hitbox = VersionCompat.spawnEntity(world, center, Interaction.class, spawned -> {
                spawned.setInteractionWidth((float) anchor.widthBlocks());
                spawned.setInteractionHeight((float) anchor.heightBlocks());
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
            for (org.bukkit.entity.Display visual : visuals) {
                removeQuietly(visual);
            }
            plugin.getLogger().severe("渲染椅侧面板失败（" + anchor.panelId() + "）：" + failure);
            return null;
        }
    }

    /**
     * 右板的引导文字。
     *
     * <p>左板是纯图标，玩家看不出「点了会发生什么」，所以右板只放说明与当前选中，
     * 不再重复列 8 个道具名——两板分工清楚，也不会让文字面板挤成一片。
     */
    private static Component panelGuideText(List<ItemStack> slots) {
        int filled = 0;
        if (slots != null) {
            for (ItemStack stack : slots) {
                if (stack != null && !stack.getType().isAir()) {
                    filled++;
                }
            }
        }
        return Component.text()
            .append(Component.text("道具栏\n", net.kyori.adventure.text.format.NamedTextColor.GOLD))
            .append(Component.text("点击左侧图标选中\n", net.kyori.adventure.text.format.NamedTextColor.GRAY))
            .append(Component.text("选好后瞄准右键投掷\n", net.kyori.adventure.text.format.NamedTextColor.GRAY))
            .append(Component.text("已装填 " + filled + " / 8", net.kyori.adventure.text.format.NamedTextColor.WHITE))
            .build();
    }

    private ItemStack iconFor(int cell, List<ItemStack> slots) {
        if (cell == ChairPanelSurface.VOICE_CELL) {
            return named(new ItemStack(VOICE_ICON), "语音");
        }
        ItemStack slot = slots == null || cell >= slots.size() ? null : slots.get(cell);
        if (slot == null || slot.getType().isAir()) {
            return named(new ItemStack(EMPTY_ICON), "空槽 " + (cell + 1));
        }
        return slot.clone();
    }

    private static ItemStack named(ItemStack stack, String name) {
        ItemMeta meta = stack.getItemMeta();
        if (meta != null) {
            meta.displayName(Component.text(name).decoration(net.kyori.adventure.text.format.TextDecoration.ITALIC, false));
            stack.setItemMeta(meta);
        }
        return stack;
    }

    private void removeEntities(PanelView panel) {
        if (panel == null) {
            return;
        }
        runOnAnchorRegion(panel.center(), () -> {
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
     * <p>已拥有该 region 时内联执行；否则投递。投递**意味着调用方拿不到同步结果**，
     * 因此 {@link #renderPanel} 只把「已提交」当成可行，失败会在 region 内留痕。
     */
    private void runOnAnchorRegion(Location anchor, Runnable task) {
        if (anchor == null || anchor.getWorld() == null) {
            return;
        }
        if (ownsRegion(anchor)) {
            task.run();
            return;
        }
        plugin.scheduler().runRegion(anchor, task);
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
