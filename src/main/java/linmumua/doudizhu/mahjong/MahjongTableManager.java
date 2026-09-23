package linmumua.doudizhu.mahjong;

import linmumua.doudizhu.DoudizhuPlugin;
import linmumua.doudizhu.compat.VersionCompat;
import linmumua.doudizhu.game.PlayerOutputDispatcher;
import linmumua.doudizhu.ui.MuzTheme;
import linmumua.doudizhu.ui.TypewriterTextStyle;
import linmumua.doudizhu.scheduler.MuzScheduler;
import linmumua.doudizhu.scheduler.RegionTaskBarrier;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import net.kyori.adventure.text.Component;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.entity.Display;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Interaction;
import org.bukkit.entity.ItemDisplay;
import org.bukkit.entity.Player;
import org.bukkit.entity.TextDisplay;
import org.bukkit.World;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerInteractAtEntityEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.util.Transformation;
import org.joml.AxisAngle4f;
import org.joml.Vector3f;

public final class MahjongTableManager implements Listener {
    // tag 字符串登记在 TableEntityGeometry.PROTECTED_TAGS（共享保护链），这里只负责写入。
    // 注意表内登记（visualEntityIds）与 tag 是两条独立的保护依据：前者用于右键放行口，
    // 后者用于破坏保护，两者都指向同一批实体。
    private static final String PROTECTED_ENTITY_TAG =
        linmumua.doudizhu.world.TableEntityGeometry.MAHJONG_PROTECTED_TAG;
    /** 关闭清理屏障的 global 超时 tick；只用于观察聚合结果，不阻塞调用线程。 */
    private static final long CLEANUP_BARRIER_TIMEOUT_TICKS = 100L;
    private final DoudizhuPlugin plugin;
    /** 玩家输出只按 UUID 投递到 player lane；麻将桌与实体状态仍走本类的桌 owner facade。 */
    private final PlayerOutputDispatcher playerOutput;
    private final Map<String, MahjongTableSession> tables = new ConcurrentHashMap<>();
    private final Map<UUID, SeatBinding> seatBindings = new ConcurrentHashMap<>();
    private final Map<UUID, String> playerTableIds = new ConcurrentHashMap<>();
    private volatile MahjongLayoutConfig layoutConfig;
    private volatile boolean shutdown;
    /**
     * 最近一次关闭清理的完成屏障；未关闭时保持已完成的空 future。
     *
     * <p>只做观察与日志，不在 {@code onDisable} 的主线程上阻塞等待：Paper 后端把 region 任务
     * 排到主线程，阻塞等待会把任务本身饿死，Folia 下未加载 region 更是永远不会返回。
     */
    private volatile CompletableFuture<RegionTaskBarrier.Result> shutdownCompletion =
        CompletableFuture.completedFuture(null);

    public MahjongTableManager(DoudizhuPlugin plugin, MahjongLayoutConfig layoutConfig) {
        this.plugin = plugin;
        this.playerOutput = new PlayerOutputDispatcher(plugin);
        this.layoutConfig = layoutConfig;
    }

    public void reloadLayout(MahjongLayoutConfig layoutConfig) {
        if (shutdown) {
            return;
        }
        this.layoutConfig = layoutConfig;
        for (MahjongTableSession table : tables.values()) {
            table.applyLayout(layoutConfig);
            rerender(table);
        }
    }

    public int tableCount() {
        return tables.size();
    }

    public Collection<MahjongTableSession> tables() {
        return List.copyOf(tables.values());
    }

    public String nextAvailableId() {
        return nextId();
    }

    public boolean containsPlayer(UUID playerId) {
        return !shutdown && playerId != null && playerTableIds.containsKey(playerId);
    }

    public boolean isShutdown() {
        return shutdown;
    }

    public MahjongTableSession createTable(Player owner, String requestedId) {
        return createTable(owner, requestedId, plugin.defaultTableAnchor(owner));
    }

    public MahjongTableSession createTable(Player owner, String requestedId, Location center) {
        ensureOpen();
        if (owner == null) {
            throw new IllegalArgumentException("创建麻将桌需要有效玩家。" );
        }
        if (center == null || center.getWorld() == null) {
            throw new IllegalArgumentException("这里暂时还不能创建麻将桌。" );
        }
        String id = normalizeId(requestedId == null || requestedId.isBlank() ? nextId() : requestedId);
        if (tables.containsKey(id)) {
            throw new IllegalArgumentException("麻将桌 " + id + " 已存在。");
        }
        MahjongTableSession session = new MahjongTableSession(id, center.clone(), owner.getUniqueId(), owner.getName(), System.currentTimeMillis(), layoutConfig);
        tables.put(id, session);
        rerender(session);
        plugin.persistMahjongTable(id, center, owner.getUniqueId(), owner.getName());
        return session;
    }

    public MahjongTableSession restoreTable(String tableId, Location center, java.util.UUID ownerId, String ownerName) {
        if (shutdown) {
            return null;
        }
        if (center == null || center.getWorld() == null) {
            return null;
        }
        String id = normalizeId(tableId == null || tableId.isBlank() ? nextId() : tableId);
        if (tables.containsKey(id)) {
            return tables.get(id);
        }
        MahjongTableSession session = new MahjongTableSession(id, center.clone(), ownerId, ownerName, System.currentTimeMillis(), layoutConfig);
        tables.put(id, session);
        rerender(session);
        return session;
    }

    public MahjongTableSession removeTable(String tableId) {
        if (shutdown) {
            return null;
        }
        MahjongTableSession removed;
        if (tableId == null || tableId.isBlank()) {
            removed = nearestTable(null);
            if (removed != null) {
                tables.remove(removed.id());
            }
        } else {
            removed = tables.remove(normalizeId(tableId));
        }
        if (removed != null) {
            for (UUID playerId : List.copyOf(removed.occupants().values())) {
                playerTableIds.remove(playerId);
            }
            removeVisuals(removed);
            plugin.deletePersistedTable("MAHJONG", removed.id());
        }
        return removed;
    }

    public MahjongTableSession table(String tableId) {
        return tableId == null ? null : tables.get(normalizeId(tableId));
    }

    public Location tableAnchor(String tableId) {
        MahjongTableSession table = table(tableId);
        return table == null ? null : table.center();
    }

    public Location previewTableCenter(Location anchor) {
        if (anchor == null || anchor.getWorld() == null) {
            return null;
        }
        MahjongLayoutConfig layout = layoutConfig;
        return anchor.clone().add(
            layout.displayCenterXOffset(),
            layout.displayCenterYOffset() + layout.tableVisualYOffset(),
            layout.displayCenterZOffset()
        );
    }

    public List<Location> previewSeatBases(Location anchor) {
        if (anchor == null || anchor.getWorld() == null) {
            return List.of();
        }
        MahjongLayoutConfig layout = layoutConfig;
        Location displayCenter = anchor.clone().add(layout.displayCenterXOffset(), layout.displayCenterYOffset(), layout.displayCenterZOffset());
        double distance = Math.max(0.6, layout.seatDistanceFromHandBase());
        List<Location> seats = new ArrayList<>();
        for (MahjongTableSession.Seat seat : MahjongTableSession.Seat.values()) {
            seats.add(displayCenter.clone().add(seat.xFactor() * distance, layout.seatBaseYOffset(), seat.zFactor() * distance));
        }
        return seats;
    }

    public String placementObstructionReason(Location anchor) {
        if (anchor == null || anchor.getWorld() == null) {
            return "这里暂时还不能放麻将桌。";
        }
        MahjongLayoutConfig layout = layoutConfig;
        Location previewDisplayCenter = anchor.clone().add(layout.displayCenterXOffset(), layout.displayCenterYOffset(), layout.displayCenterZOffset());
        for (MahjongTableSession existing : tables.values()) {
            Location existingCenter = displayCenter(existing);
            if (existingCenter.getWorld() == null || !existingCenter.getWorld().equals(previewDisplayCenter.getWorld())) {
                continue;
            }
            if (existingCenter.distanceSquared(previewDisplayCenter) < 3.24) {
                return "离另一张麻将桌太近了，稍微挪开一点再放。";
            }
        }
        return null;
    }

    public boolean canRemoveTable(Player player, String tableId) {
        MahjongTableSession table = table(tableId);
        if (player == null || table == null) {
            return false;
        }
        return player.hasPermission("muz.admin") || table.ownerId().equals(player.getUniqueId());
    }

    public String removeDeniedReason(Player player, String tableId) {
        MahjongTableSession table = table(tableId);
        if (table == null) {
            return "找不到这张麻将桌。";
        }
        if (player != null && player.hasPermission("muz.admin")) {
            return "";
        }
        String owner = table.ownerName() == null || table.ownerName().isBlank() ? "原放置者" : table.ownerName();
        return "这张麻将桌是 " + owner + " 放的，你不能拆。";
    }

    public String targetedTable(Player player, double maxDistance) {
        if (player == null) {
            return null;
        }
        Location eye = player.getEyeLocation();
        org.bukkit.util.Vector direction = eye.getDirection().normalize();
        String bestTable = null;
        double bestDistance = Double.MAX_VALUE;
        for (MahjongTableSession table : tables.values()) {
            double distance = sightDistance(eye, direction, displayCenter(table), 1.15, maxDistance);
            if (distance >= 0.0 && distance < bestDistance) {
                bestDistance = distance;
                bestTable = table.id();
            }
        }
        return bestTable;
    }

    /**
     * 只按麻将桌自己的实体登记表判断保护对象。
     *
     * <p>这里不能通过全局 UUID 实体索引做反查：麻将领域尚未完成 Folia owner 路由，且
     * 实体可能正处于未加载区块。实体生成、删除和真正的 Bukkit 实体操作一律经
     * {@code rerender}/{@code removeVisuals} 直接投递到桌锚点 region；{@link #runOnTableRegion(MahjongTableSession, Runnable)}
     * 是带「代次 + 实例身份」闸门的通用桌 owner 入口，当前没有生产调用方，仅由契约测试钉住其形状。
     *
     * <p>本方法服务于共享保护链的<b>右键放行口</b>：麻将 tag 已经并入
     * {@code PhysicalTableManager.isProtectedEntity}（走 {@code TableEntityGeometry.PROTECTED_TAGS}），
     * 使麻将实体同样获得破坏保护；而右键不能只看「受保护」就拦下，还要在这里判定它属于麻将，
     * 由 {@code WorldTableInteractionListener.shouldCancelProtectedInteract} 放行，
     * 否则麻将入座 Interaction 会被取消（麻将实体既不是椅子家具也不是动作按钮）。
     * 用登记表而不是读实体 tag，正是为了避开上面那条全局实体反查限制。
     */
    public boolean isProtectedEntity(UUID entityId) {
        if (entityId == null) {
            return false;
        }
        return tables.values().stream().anyMatch(table -> table.visualEntityIds().contains(entityId));
    }

    public MahjongTableSession nearestTable(Location location) {
        if (tables.isEmpty()) {
            return null;
        }
        if (location == null || location.getWorld() == null) {
            return tables.values().stream().sorted(Comparator.comparing(MahjongTableSession::id)).findFirst().orElse(null);
        }
        MahjongTableSession nearest = null;
        double best = Double.MAX_VALUE;
        for (MahjongTableSession table : tables.values()) {
            Location center = displayCenter(table);
            if (center.getWorld() == null || !center.getWorld().equals(location.getWorld())) {
                continue;
            }
            double distance = center.distanceSquared(location);
            if (distance < best) {
                best = distance;
                nearest = table;
            }
        }
        return nearest;
    }

    public List<Component> openLobbyLines(Player player) {
        List<Component> lines = new ArrayList<>();
        lines.add(MuzTheme.banner("MUZ Mahjong", "内嵌运行时", MuzTheme.muted("第一版骨架已启用")));
        lines.add(MuzTheme.body("/muz mahjong create [桌号]"));
        lines.add(MuzTheme.body("/muz mahjong list"));
        lines.add(MuzTheme.body("/muz mahjong state [桌号]"));
        lines.add(MuzTheme.body("/muz mahjong remove <桌号>"));
        lines.add(MuzTheme.muted("当前桌数 · " + tableCount() + " · " + layoutConfig.summary()));
        return lines;
    }

    public List<Component> listLines() {
        if (tables.isEmpty()) {
            return List.of(MuzTheme.muted("当前还没有内嵌麻将桌。"));
        }
        List<Component> lines = new ArrayList<>();
        tables.values().stream()
            .sorted(Comparator.comparing(MahjongTableSession::id))
            .forEach(table -> {
                Location center = displayCenter(table);
                lines.add(MuzTheme.body("- " + table.id()
                    + " · " + table.ownerName()
                    + " · " + safeWorld(center)
                    + " (" + center.getBlockX() + ", " + center.getBlockY() + ", " + center.getBlockZ() + ")"));
            });
        return lines;
    }

    public List<Component> stateLines(MahjongTableSession table) {
        if (table == null) {
            return List.of(MuzTheme.danger("没找到目标麻将桌。"));
        }
        Location center = displayCenter(table);
        List<Component> lines = new ArrayList<>();
        lines.add(MuzTheme.banner("麻将桌 " + table.id(), table.ownerName(), MuzTheme.muted("内嵌运行时")));
        lines.add(MuzTheme.body("位置 · " + safeWorld(center) + " (" + center.getBlockX() + ", " + center.getBlockY() + ", " + center.getBlockZ() + ")"));
        lines.add(MuzTheme.body("布局 · " + table.layoutConfig().summary()));
        lines.add(MuzTheme.body("座位 · " + seatStateSummary(table)));
        lines.add(MuzTheme.body("创建时间戳 · " + table.createdAtMillis()));
        return lines;
    }

    public Map<String, String> statusMap() {
        Map<String, String> status = new LinkedHashMap<>();
        status.put("tables", String.valueOf(tableCount()));
        status.put("layout", layoutConfig.summary());
        return status;
    }

    @EventHandler(priority = EventPriority.NORMAL, ignoreCancelled = true)
    public void onSeatInteract(PlayerInteractAtEntityEvent event) {
        SeatBinding binding = seatBindings.get(event.getRightClicked().getUniqueId());
        if (binding == null) {
            return;
        }
        event.setCancelled(true);
        Player player = event.getPlayer();
        MahjongTableSession table = tables.get(binding.tableId());
        if (table == null) {
            seatBindings.remove(event.getRightClicked().getUniqueId());
            return;
        }
        String currentTableId = playerTableIds.get(player.getUniqueId());
        MahjongTableSession.Seat currentSeat = table.seatOf(player.getUniqueId());
        if (currentSeat == binding.seat()) {
            if (player.isSneaking()) {
                table.leave(player.getUniqueId());
                playerTableIds.remove(player.getUniqueId());
                rerender(table);
                sendToPlayer(player.getUniqueId(), MuzTheme.warning("你已从 " + table.id() + " 的" + binding.seat().label() + "离座。"));
                return;
            }
            boolean ready = table.toggleReady(player.getUniqueId());
            rerender(table);
            sendToPlayer(player.getUniqueId(), ready
                ? MuzTheme.success("你已在 " + table.id() + " 的" + binding.seat().label() + "准备。")
                : MuzTheme.warning("你已取消 " + table.id() + " 的" + binding.seat().label() + "准备。"));
            if (table.occupants().size() == MahjongTableSession.Seat.values().length && table.readyCount() == MahjongTableSession.Seat.values().length) {
                broadcast(table, MuzTheme.success("麻将桌 " + table.id() + " 四人已就位且全部准备；下一步接完整发牌流程。"));
            }
            return;
        }
        if (currentTableId != null && !currentTableId.equalsIgnoreCase(table.id())) {
            sendToPlayer(player.getUniqueId(), MuzTheme.danger("你已经在另一张麻将桌入座了：" + currentTableId));
            return;
        }
        UUID occupiedBy = table.occupants().get(binding.seat());
        if (occupiedBy != null && !occupiedBy.equals(player.getUniqueId())) {
            sendToPlayer(player.getUniqueId(), MuzTheme.danger(binding.seat().label() + " 已有人。"));
            return;
        }
        if (currentSeat != null) {
            table.leave(player.getUniqueId());
        }
        if (!table.sit(binding.seat(), player.getUniqueId(), player.getName())) {
            sendToPlayer(player.getUniqueId(), MuzTheme.danger("这个座位暂时没坐进去。"));
            return;
        }
        playerTableIds.put(player.getUniqueId(), table.id());
        rerender(table);
        sendToPlayer(player.getUniqueId(), MuzTheme.success("你已坐到麻将桌 " + table.id() + " 的" + binding.seat().label() + "。"));
    }

    public void shutdown() {
        if (shutdown) {
            return;
        }
        // 先封住麻将新入口再清理；scheduler 由插件入口最后关闭，
        // 所以这里的 owner 清理仍能真实投递（DoudizhuPlugin.onDisable 的顺序契约）。
        shutdown = true;
        List<MahjongTableSession> remaining = List.copyOf(tables.values());
        tables.clear();
        seatBindings.clear();
        playerTableIds.clear();
        if (plugin.getServer().isStopping()) {
            // 关服分支与 PhysicalTableManager.shutdown 一致：region 可能已不可用，实体随世界销毁，
            // 这里只推进代次并清追踪，绝不在非法 owner 上操作实体。
            for (MahjongTableSession table : remaining) {
                table.nextGeneration();
                table.clearVisuals();
            }
            return;
        }
        // reload 分支：调度仍可用。每张桌登记为一个清理 request，真实删除在桌 region 内执行；
        // 用完成屏障观察聚合结果，失败只记录日志，不阻塞调用线程。
        List<RegionTaskBarrier.Request> requests = new ArrayList<>();
        for (MahjongTableSession table : remaining) {
            table.nextGeneration();
            requests.add(new RegionTaskBarrier.Request(
                table.id(), table.anchor(), () -> cleanupOnTableRegion(table)));
        }
        shutdownCompletion = new RegionTaskBarrier(
            plugin.scheduler(), requests, CLEANUP_BARRIER_TIMEOUT_TICKS)
            .start()
            .whenComplete((result, failure) -> {
                if (failure != null) {
                    plugin.getLogger().warning("麻将桌清理屏障异常结束: " + failure.getMessage());
                } else if (result == null || result.status() != RegionTaskBarrier.Status.COMPLETED) {
                    plugin.getLogger().warning("麻将桌清理屏障未全部完成，状态="
                        + (result == null ? "null" : result.status()));
                }
            });
    }

    /** 返回最近一次关闭清理的完成屏障（未关闭时为已完成的空 future），供关闭流程观察，不阻塞等待。 */
    public CompletableFuture<RegionTaskBarrier.Result> shutdownCompletion() {
        return shutdownCompletion;
    }

    private void ensureOpen() {
        if (shutdown) {
            throw new IllegalStateException("内嵌麻将运行时已关闭。");
        }
    }

    private String nextId() {
        int index = 1;
        while (tables.containsKey(String.valueOf(index))) {
            index++;
        }
        return String.valueOf(index);
    }

    /**
     * 在麻将桌锚点所属 region 执行实体操作，并绑定桌实例 generation。
     *
     * <p>业务状态仍由各自事件入口维护；凡是 Display/Interaction 的创建、删除和实体
     * 属性修改，都必须从这个入口进入，避免把麻将实体操作投递到 global scheduler。
     */
    public MuzScheduler.TaskHandle runOnTableRegion(MahjongTableSession table, Runnable task) {
        if (shutdown || table == null || task == null || table.anchor().getWorld() == null) {
            return cancelledTask("region");
        }
        long expectedGeneration = table.generation();
        return plugin.scheduler().runRegion(table.anchor(), () -> {
            if (table.generation() == expectedGeneration && tables.get(table.id()) == table) {
                task.run();
            }
        });
    }

    /** 在玩家自己的 player/entity scheduler 上执行玩家输出；任务只接收 UUID 对应的当前玩家。 */
    public MuzScheduler.TaskHandle runForPlayer(UUID playerId, java.util.function.Consumer<Player> task) {
        if (shutdown || playerId == null || task == null) {
            return cancelledTask("player");
        }
        return playerOutput.runPlayer(playerId, task);
    }

    /** 保留旧 Player + Runnable 签名；实际投递仍转换为 UUID 并进入 player lane。 */
    public MuzScheduler.TaskHandle runForPlayer(Player player, Runnable task) {
        if (player == null || task == null) {
            return cancelledTask("player");
        }
        return runForPlayer(player.getUniqueId(), ignored -> task.run());
    }

    public void sendToPlayer(UUID playerId, Component message) {
        if (!shutdown && playerId != null && message != null) {
            playerOutput.sendMessage(playerId, message);
        }
    }

    public void sendToPlayer(Player player, Component message) {
        if (player != null) {
            sendToPlayer(player.getUniqueId(), message);
        }
    }

    public void sendToPlayer(UUID playerId, List<Component> messages) {
        if (shutdown || playerId == null || messages == null || messages.isEmpty()) {
            return;
        }
        for (Component message : messages) {
            sendToPlayer(playerId, message);
        }
    }

    public void sendToPlayer(Player player, List<Component> messages) {
        if (player != null) {
            sendToPlayer(player.getUniqueId(), messages);
        }
    }

    private MuzScheduler.TaskHandle cancelledTask(String owner) {
        return new MuzScheduler.TaskHandle() {
            @Override
            public void cancel() { }

            @Override
            public boolean isCancelled() {
                return true;
            }

            @Override
            public String ownerId() {
                return owner;
            }
        };
    }

    private void render(MahjongTableSession table) {
        if (table == null) {
            return;
        }
        cleanupOnTableRegion(table);
        Location center = displayCenter(table);
        MahjongLayoutConfig layout = table.layoutConfig();
        // [MUZ-DEBUG] 详细日志：查看渲染时使用的配置值
        plugin.getLogger().info("[MUZ-DEBUG] render() called for table=" + table.id());
        plugin.getLogger().info("[MUZ-DEBUG]   layoutConfig: tableVisualYOffset=" + layout.tableVisualYOffset()
            + " displayCenterXOffset=" + layout.displayCenterXOffset()
            + " displayCenterYOffset=" + layout.displayCenterYOffset()
            + " displayCenterZOffset=" + layout.displayCenterZOffset());
        plugin.getLogger().info("[MUZ-DEBUG]   table.center=" + table.center());
        plugin.getLogger().info("[MUZ-DEBUG]   displayCenter=" + center);
        Location tableVisualLocation = center.clone().add(0.0, layout.tableVisualYOffset(), 0.0);
        plugin.getLogger().info("[MUZ-DEBUG]   tableVisualLocation=" + tableVisualLocation);
        ItemDisplay tableDisplay = spawnItemDisplay(tableVisualLocation, tableVisualItem(), 2.25f);
        remember(table, tableDisplay);
        TextDisplay centerText = spawnText(center.clone().add(0.0, layout.centerLabelYOffset(), 0.0), buildCenterText(table), 0.55f);
        remember(table, centerText);
        for (MahjongTableSession.Seat seat : MahjongTableSession.Seat.values()) {
            Location seatLocation = seatLocation(table, seat);
            ItemDisplay chair = spawnItemDisplay(seatLocation.clone().add(0.0, layout.seatBaseYOffset(), 0.0), chairVisualItem(), 1.35f, seatYaw(seat));
            remember(table, chair);
            Location labelLocation = applySeatOffset(seatLocation, seat, sideSeatHorizontalOffset(seat, layout), layout.seatAnchorYOffset(), layout.seatLabelDepthOffset());
            TextDisplay label = spawnText(labelLocation, buildSeatText(table, seat), (float) layout.seatActionLabelScale());
            remember(table, label);
            Location interactionLocation = applySeatOffset(seatLocation, seat, sideSeatHorizontalOffset(seat, layout), layout.seatActionLabelYOffset(), layout.seatLabelDepthOffset());
            Interaction interaction = spawnInteraction(interactionLocation, (float) layout.seatActionHitboxWidth(), (float) layout.seatActionHitboxHeight());
            seatBindings.put(interaction.getUniqueId(), new SeatBinding(table.id(), seat));
            remember(table, interaction);
        }
    }

    private void rerender(MahjongTableSession table) {
        if (table == null || table.anchor().getWorld() == null) {
            return;
        }
        // 提交前推进 generation：淘汰已排队的旧重绘；并在 region 内复核实例身份，
        // 防止同名新桌实例被旧实例的迟到回调写入实体。
        long expectedGeneration = table.nextGeneration();
        plugin.scheduler().runRegion(table.anchor(), () -> {
            if (table.generation() != expectedGeneration || tables.get(table.id()) != table) {
                return;
            }
            render(table);
        });
    }

    /**
     * 终结删除：推进 generation 淘汰在途重绘，再把真实实体删除投递到桌 region。
     *
     * <p>清理本身不设代次/注册闸门——{@code removeTable} 与 {@code shutdown} 会先把桌移出注册表，
     * 若这里再要求「桌仍在注册表且代次不变」，删除会被自己的屏障挡掉，实体永久残留。
     * 删除只按本桌已登记的 UUID 列表执行，重复执行是安全的。
     */
    private void removeVisuals(MahjongTableSession table) {
        if (table == null || table.anchor().getWorld() == null) {
            return;
        }
        table.nextGeneration();
        plugin.scheduler().runRegion(table.anchor(), () -> cleanupOnTableRegion(table));
    }

    /** 只能在桌锚点 region 中调用；不再通过 Bukkit 全局实体索引反查。 */
    private void cleanupOnTableRegion(MahjongTableSession table) {
        for (UUID entityId : List.copyOf(table.visualEntityIds())) {
            seatBindings.remove(entityId);
            Entity entity = entityAt(table, entityId);
            if (entity != null) {
                entity.remove();
            }
        }
        table.clearVisuals();
    }

    private Entity entityAt(MahjongTableSession table, UUID entityId) {
        World world = table.anchor().getWorld();
        return world == null || entityId == null ? null : world.getEntity(entityId);
    }

    private void remember(MahjongTableSession table, Entity entity) {
        if (table == null || entity == null) {
            return;
        }
        table.visualEntityIds().add(entity.getUniqueId());
    }

    private void broadcast(MahjongTableSession table, Component message) {
        if (table == null || message == null) {
            return;
        }
        for (UUID playerId : table.occupants().values()) {
            sendToPlayer(playerId, message);
        }
    }

    private Location displayCenter(MahjongTableSession table) {
        MahjongLayoutConfig layout = table.layoutConfig();
        return table.center().clone().add(layout.displayCenterXOffset(), layout.displayCenterYOffset(), layout.displayCenterZOffset());
    }

    private Location seatLocation(MahjongTableSession table, MahjongTableSession.Seat seat) {
        Location center = displayCenter(table);
        double distance = Math.max(0.6, table.layoutConfig().seatDistanceFromHandBase());
        return center.clone().add(seat.xFactor() * distance, 0.0, seat.zFactor() * distance);
    }

    private float seatYaw(MahjongTableSession.Seat seat) {
        return switch (seat) {
            case EAST -> 0.0f;
            case SOUTH -> 90.0f;
            case WEST -> 180.0f;
            case NORTH -> -90.0f;
        };
    }

    private double sideSeatHorizontalOffset(MahjongTableSession.Seat seat, MahjongLayoutConfig layout) {
        return seat == MahjongTableSession.Seat.SOUTH || seat == MahjongTableSession.Seat.NORTH
            ? layout.seatSideActionHorizontalOffset()
            : 0.0;
    }

    private Location applySeatOffset(Location base, MahjongTableSession.Seat seat, double lateral, double vertical, double towardCenter) {
        double forwardX = -seat.xFactor();
        double forwardZ = -seat.zFactor();
        double lateralX = -forwardZ;
        double lateralZ = forwardX;
        return base.clone().add(
            lateralX * lateral + forwardX * towardCenter,
            vertical,
            lateralZ * lateral + forwardZ * towardCenter
        );
    }

    private double sightDistance(Location eye, org.bukkit.util.Vector direction, Location center, double radius, double maxDistance) {
        return linmumua.doudizhu.world.TableEntityGeometry.sightDistance(eye, direction, center, radius, maxDistance);
    }

    private Component buildCenterText(MahjongTableSession table) {
        return MuzTheme.row(
            MuzTheme.accent("麻将桌 " + table.id()),
            List.of(MuzTheme.muted("已入座 " + table.occupants().size() + "/4"), MuzTheme.muted("已准备 " + table.readyCount() + "/4"), MuzTheme.muted("点击入座，同位再点准备，潜行点离座"))
        );
    }

    private Component buildSeatText(MahjongTableSession table, MahjongTableSession.Seat seat) {
        String occupant = table.occupantNames().get(seat);
        return MuzTheme.row(
            MuzTheme.warm(seat.label()),
            List.of(
                occupant == null || occupant.isBlank()
                    ? MuzTheme.muted("点击入座")
                    : (table.isReady(seat) ? MuzTheme.success(occupant + " · 已准备") : MuzTheme.warning(occupant + " · 未准备"))
            )
        );
    }

    private String seatStateSummary(MahjongTableSession table) {
        List<String> parts = new ArrayList<>();
        for (MahjongTableSession.Seat seat : MahjongTableSession.Seat.values()) {
            String occupant = table.occupantNames().get(seat);
            parts.add(seat.label() + ":" + (occupant == null ? "空" : occupant));
        }
        return String.join(" · ", parts);
    }

    private ItemDisplay spawnItemDisplay(Location location, ItemStack item, float scale) {
        return spawnItemDisplay(location, item, scale, 0.0f);
    }

    private ItemDisplay spawnItemDisplay(Location location, ItemStack item, float scale, float yaw) {
        return VersionCompat.spawnEntity(location.getWorld(), location, ItemDisplay.class, spawned -> {
            spawned.setItemStack(item);
            spawned.setBillboard(Display.Billboard.FIXED);
            spawned.setTransformation(new Transformation(new Vector3f(), new AxisAngle4f(), new Vector3f(scale, scale, scale), new AxisAngle4f()));
            spawned.setRotation(yaw, 0.0f);
            protect(spawned);
        });
    }

    private TextDisplay spawnText(Location location, Component text, float scale) {
        return VersionCompat.spawnEntity(location.getWorld(), location, TextDisplay.class, spawned -> {
            spawned.text(text);
            TypewriterTextStyle.apply(spawned, Display.Billboard.CENTER, false, scale);
            protect(spawned);
        });
    }

    private Interaction spawnInteraction(Location location, float width, float height) {
        return VersionCompat.spawnEntity(location.getWorld(), location, Interaction.class, spawned -> {
            spawned.setInteractionWidth(Math.max(0.1f, width));
            spawned.setInteractionHeight(Math.max(0.1f, height));
            spawned.setResponsive(true);
            protect(spawned);
        });
    }

    private void protect(Entity entity) {
        linmumua.doudizhu.world.TableEntityGeometry.protectEntity(entity, PROTECTED_ENTITY_TAG);
    }

    private ItemStack tableVisualItem() {
        ItemStack configured = plugin.getConfiguredFurnitureItem(DoudizhuPlugin.FurnitureType.TABLE);
        if (configured != null && !configured.getType().isAir()) {
            return configured.clone();
        }
        ItemStack item = new ItemStack(Material.CARTOGRAPHY_TABLE);
        ItemMeta meta = item.getItemMeta();
        meta.displayName(MuzTheme.accent("麻将桌"));
        item.setItemMeta(meta);
        return item;
    }

    private ItemStack chairVisualItem() {
        ItemStack configured = plugin.getConfiguredFurnitureItem(DoudizhuPlugin.FurnitureType.CHAIR);
        if (configured != null && !configured.getType().isAir()) {
            return configured.clone();
        }
        ItemStack item = new ItemStack(Material.OAK_STAIRS);
        ItemMeta meta = item.getItemMeta();
        meta.displayName(MuzTheme.warm("麻将座位"));
        item.setItemMeta(meta);
        return item;
    }

    private String normalizeId(String id) {
        return id.trim().toUpperCase(Locale.ROOT);
    }

    private String safeWorld(Location location) {
        return location.getWorld() == null ? "unknown" : location.getWorld().getName();
    }

    private record SeatBinding(String tableId, MahjongTableSession.Seat seat) {
    }
}
