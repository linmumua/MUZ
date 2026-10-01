package linmumua.doudizhu.debug;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.atomic.AtomicLong;
import linmumua.doudizhu.DoudizhuPlugin;
import linmumua.doudizhu.assets.PackAssets;
import linmumua.doudizhu.ui.VirtualGadgetBar;
import linmumua.doudizhu.ui.VirtualGadgetBarStore;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;

/**
 * Debug Web 道具箱预览快照。
 *
 * <p>YAML 读取在异步线程完成，ItemStack 解码和在线玩家读取只在主线程完成。
 * HTTP 层只消费这里发布的不可变快照，不接触 Bukkit 或磁盘。
 */
public final class GadgetPreviewSnapshotService implements Listener, AutoCloseable {
    /** Bukkit 边界与执行器可在包内替换，使乱序回调测试不依赖全局服务端。 */
    interface RuntimeAccess {
        List<UUID> onlineIds();
        String onlineName(UUID id);
        boolean enabled();
        void executeMain(Runnable task);
        java.util.logging.Logger logger();
    }

    private final RuntimeAccess runtime;
    private final java.util.concurrent.Executor readerExecutor;
    private final VirtualGadgetBarStore store;
    private final Map<UUID, PlayerSnapshot> snapshots = new ConcurrentHashMap<>();
    private final Map<UUID, Long> generations = new ConcurrentHashMap<>();
    private final AtomicLong nextGeneration = new AtomicLong();
    private volatile boolean active;
    private volatile boolean closed;

    public GadgetPreviewSnapshotService(DoudizhuPlugin plugin, VirtualGadgetBarStore store) {
        this(store, new RuntimeAccess() {
            public List<UUID> onlineIds() {
                return Bukkit.getOnlinePlayers().stream().map(Player::getUniqueId).toList();
            }
            public String onlineName(UUID id) {
                Player player = Bukkit.getPlayer(id);
                return player != null && player.isOnline() ? player.getName() : null;
            }
            public boolean enabled() { return plugin.isEnabled(); }
            public void executeMain(Runnable task) { plugin.scheduler().runGlobal(task); }
            public java.util.logging.Logger logger() { return plugin.getLogger(); }
        }, java.util.concurrent.ForkJoinPool.commonPool());
    }

    GadgetPreviewSnapshotService(VirtualGadgetBarStore store, RuntimeAccess runtime,
                                java.util.concurrent.Executor readerExecutor) {
        this.store = java.util.Objects.requireNonNull(store);
        this.runtime = java.util.Objects.requireNonNull(runtime);
        this.readerExecutor = java.util.Objects.requireNonNull(readerExecutor);
    }

    public void start() {
        if (closed) {
            return;
        }
        active = true;
        for (UUID playerId : runtime.onlineIds()) {
            refresh(playerId);
        }
    }

    /** 停止预览读取并使所有未完成读取失效；不会永久关闭服务。 */
    public void stop() {
        active = false;
        generations.replaceAll((playerId, generation) -> nextGeneration.incrementAndGet());
        snapshots.clear();
    }

    /** GUI 成功保存后调用，先使旧读取失效，再按当前配置重新读取。 */
    public void onSaved(UUID playerId) {
        if (playerId == null || closed) {
            return;
        }
        generations.put(playerId, nextGeneration.incrementAndGet());
        snapshots.remove(playerId);
        if (active) {
            refresh(playerId);
        }
    }

    public void refresh(UUID playerId) {
        if (closed || !active || playerId == null) {
            return;
        }
        String name = runtime.onlineName(playerId);
        if (name == null) {
            snapshots.remove(playerId);
            return;
        }
        long generation = nextGeneration.incrementAndGet();
        generations.put(playerId, generation);
        snapshots.put(playerId, new PlayerSnapshot(playerId, name, "loading", List.of()));
        CompletableFuture
            .supplyAsync(() -> store.loadRaw(playerId), readerExecutor)
            .whenComplete((raw, failure) -> {
                if (closed || !active || !runtime.enabled()) {
                    return;
                }
                try {
                    runtime.executeMain(() -> {
                        if (closed || !active || !Long.valueOf(generation).equals(generations.get(playerId))) {
                            return;
                        }
                        String onlineName = runtime.onlineName(playerId);
                        if (onlineName == null) {
                            snapshots.remove(playerId);
                            generations.remove(playerId);
                            return;
                        }
                        if (failure != null) {
                            snapshots.put(playerId, new PlayerSnapshot(playerId, onlineName, "error", List.of()));
                            runtime.logger().warning("读取玩家道具预览失败 " + playerId + ": " + failure.getMessage());
                            return;
                        }
                        try {
                            VirtualGadgetBar bar = store.decodeRaw(raw);
                            snapshots.put(playerId, readySnapshot(playerId, onlineName, bar));
                        } catch (RuntimeException exception) {
                            snapshots.put(playerId, new PlayerSnapshot(playerId, onlineName, "error", List.of()));
                            runtime.logger().warning("解码玩家道具预览失败 " + playerId + ": " + exception.getMessage());
                        }
                    });
                } catch (IllegalStateException | RejectedExecutionException exception) {
                    if (!closed && runtime.enabled()) {
                        runtime.logger().fine("道具预览主线程已停止，忽略迟到读取 " + playerId + "：" + exception.getMessage());
                    }
                }
            });
    }

    public void refreshAll() {
        start();
    }

    public Snapshot snapshot() {
        List<PlayerSnapshot> players = new ArrayList<>(snapshots.values());
        players.sort(Comparator.comparing(PlayerSnapshot::name, String.CASE_INSENSITIVE_ORDER)
            .thenComparing(PlayerSnapshot::uuid));
        return new Snapshot(List.copyOf(players));
    }

    /** 纯快照转换入口，测试无需启动 Bukkit HTTP 服务。 */
    public static PlayerSnapshot readySnapshot(UUID uuid, String name, VirtualGadgetBar bar) {
        List<ItemPreview> items = new ArrayList<>();
        if (bar != null) {
            for (int slot = 0; slot < VirtualGadgetBar.SLOT_COUNT; slot++) {
                ItemStack item = bar.slot(slot);
                if (item != null && !item.getType().isAir()) {
                    items.add(itemPreview(slot, item));
                }
            }
        }
        return new PlayerSnapshot(uuid, name == null ? "" : name, "ready", List.copyOf(items));
    }

    private static PlayerSnapshot readySnapshot(Player player, VirtualGadgetBar bar) {
        return readySnapshot(player.getUniqueId(), player.getName(), bar);
    }

    private static ItemPreview itemPreview(int slot, ItemStack item) {
        String model = itemModel(item);
        String textureUrl = "";
        String iconStatus = "unavailable";
        if ("muz:table_gadget_tomato".equals(model)) {
            textureUrl = "/api/resource/muz:item/table_gadget_tomato.png";
            iconStatus = "available";
        } else if (model.isEmpty() && !hasCustomModelData(item)) {
            if (item.getType() == org.bukkit.Material.EGG) {
                textureUrl = "/api/resource/minecraft:item/egg.png";
                iconStatus = "available";
            } else if (item.getType() == org.bukkit.Material.WATER_BUCKET) {
                textureUrl = "/api/resource/minecraft:item/water_bucket.png";
                iconStatus = "available";
            }
        }
        return new ItemPreview(
            slot, itemName(item), item.getType().name(), textureUrl, iconStatus,
            barIconTexture(PackAssets.gadgetBarKind(item)));
    }

    /**
     * 桌内道具栏在客户端真正用的那层图标 PNG（{@code muz:font/gadget_bar/*.png}）的 texture 名。
     *
     * <p>【为什么要在这里给】：道具箱面板里的 {@code textureUrl} 是【物品贴图】，
     * 而道具栏字形里嵌的是构建期烘焙过的【独立图标 PNG】——两者不是同一张图（番茄尤其明显）。
     * 只读道具栏预览要跟游戏内一致，就必须复用同一个种类映射
     * {@link PackAssets#gadgetBarKind(org.bukkit.inventory.ItemStack)}，
     * 而不是让前端按物品贴图名自己猜。
     *
     * <p>返回的是与 {@code DebugHudConfigController} 下发的
     * {@code geometry.gadgetBar.textures} 完全同一套 texture 名，前端用
     * {@code assetByTexture} 直接解析，不需要第二份映射表。空槽/未知物品返回空串，前端只画底图。
     */
    static String barIconTexture(int kind) {
        if (kind == PackAssets.GADGET_BAR_EMPTY) {
            return "";
        }
        return "muz:" + PackAssets.gadgetBarIconTexturePath(kind);
    }

    private static String itemName(ItemStack item) {
        ItemMeta meta = item.getItemMeta();
        if (meta != null && meta.hasDisplayName()) {
            String display = meta.getDisplayName();
            if (display != null && !display.isBlank()) {
                return display;
            }
        }
        return item.getType().name();
    }

    private static String itemModel(ItemStack item) {
        ItemMeta meta = item.getItemMeta();
        if (meta == null || !meta.hasItemModel()) {
            return "";
        }
        Object model = meta.getItemModel();
        return model == null ? "" : model.toString();
    }

    private static boolean hasCustomModelData(ItemStack item) {
        ItemMeta meta = item.getItemMeta();
        return meta != null && meta.hasCustomModelData();
    }

    @EventHandler
    public void onJoin(PlayerJoinEvent event) {
        refresh(event.getPlayer().getUniqueId());
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        UUID playerId = event.getPlayer().getUniqueId();
        generations.remove(playerId);
        snapshots.remove(playerId);
    }

    @Override
    public void close() {
        active = false;
        closed = true;
        generations.clear();
        snapshots.clear();
    }

    public record Snapshot(List<PlayerSnapshot> players) {
        public Snapshot {
            players = players == null ? List.of() : List.copyOf(players);
        }
    }

    public record PlayerSnapshot(UUID uuid, String name, String status, List<ItemPreview> items) {
        public PlayerSnapshot {
            items = items == null ? List.of() : List.copyOf(items);
        }
    }

    /**
     * 一个非空道具槽的只读预览数据。
     *
     * @param textureUrl 物品贴图资源键（道具箱面板用）
     * @param barIcon    桌内道具栏里真正嵌的图标 PNG 的 texture 名（与 geometry.gadgetBar.textures 同一套）；
     *                   空串表示该物品在栏里只显示底图
     */
    public record ItemPreview(int slot, String name, String material, String textureUrl, String iconStatus,
                              String barIcon) {
        public ItemPreview {
            barIcon = barIcon == null ? "" : barIcon;
        }
    }
}
