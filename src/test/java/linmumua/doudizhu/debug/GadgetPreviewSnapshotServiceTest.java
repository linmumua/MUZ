package linmumua.doudizhu.debug;

import static org.junit.jupiter.api.Assertions.*;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import linmumua.doudizhu.ui.VirtualGadgetBar;
import org.bukkit.Material;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.junit.jupiter.api.Test;

/** 验证网页只读道具数量、顺序、缺图与快照隔离，不启动 Bukkit 服务端。 */
class GadgetPreviewSnapshotServiceTest {
    private final List<Runnable> restoreCaches = new ArrayList<>();

    /** 这三个普通物品不是方块；仅替代 Paper 的延迟注册表查询，退出时恢复缓存。 */
    @org.junit.jupiter.api.BeforeEach
    void isolateMaterialRegistry() throws Throwable {
        var lookup = java.lang.invoke.MethodHandles.lookup();
        var materialLookup = java.lang.invoke.MethodHandles.privateLookupIn(Material.class, lookup);
        var getter = materialLookup.findGetter(Material.class, "blockType", java.util.function.Supplier.class);
        for (Material material : List.of(Material.EGG, Material.WATER_BUCKET, Material.DIAMOND)) {
            Object cache = getter.invoke(material);
            var cacheLookup = java.lang.invoke.MethodHandles.privateLookupIn(cache.getClass(), lookup);
            var delegate = cacheLookup.findVarHandle(cache.getClass(), "delegate", com.google.common.base.Supplier.class);
            var value = cacheLookup.findVarHandle(cache.getClass(), "value", Object.class);
            Object originalDelegate = delegate.getVolatile(cache);
            Object originalValue = value.get(cache);
            restoreCaches.add(() -> {
                value.set(cache, originalValue);
                delegate.setVolatile(cache, originalDelegate);
            });
            value.set(cache, null);
            delegate.setVolatile(cache, (com.google.common.base.Supplier<Object>) () -> null);
        }
    }

    @org.junit.jupiter.api.AfterEach
    void restoreMaterialRegistry() {
        restoreCaches.forEach(Runnable::run);
    }
    @Test
    void 空栏不会退回默认三件() {
        var snapshot = GadgetPreviewSnapshotService.readySnapshot(UUID.randomUUID(), "玩家", VirtualGadgetBar.empty());
        assertEquals("ready", snapshot.status());
        assertTrue(snapshot.items().isEmpty());
    }

    @Test
    void 一三八件保持原槽与重复顺序且不含语音入口() {
        for (int count : new int[]{1, 3, 8}) {
            List<ItemStack> items = new ArrayList<>();
            for (int i = 0; i < count; i++) items.add(new PlainItem(Material.EGG));
            var snapshot = GadgetPreviewSnapshotService.readySnapshot(UUID.randomUUID(), "玩家", VirtualGadgetBar.of(items));
            assertEquals(count, snapshot.items().size());
            for (int i = 0; i < count; i++) {
                assertEquals(i, snapshot.items().get(i).slot());
                assertEquals("/api/resource/minecraft:item/egg.png", snapshot.items().get(i).textureUrl());
                assertEquals("available", snapshot.items().get(i).iconStatus());
            }
            assertThrows(UnsupportedOperationException.class, () -> snapshot.items().clear());
        }
    }

    @Test
    void 空洞不增补物品且未知材质明确缺图() {
        List<ItemStack> slots = new ArrayList<>();
        slots.add(null);
        slots.add(new PlainItem(Material.WATER_BUCKET));
        slots.add(null);
        slots.add(new PlainItem(Material.DIAMOND));
        var snapshot = GadgetPreviewSnapshotService.readySnapshot(UUID.randomUUID(), "<玩家>", VirtualGadgetBar.of(slots));
        assertEquals(List.of(1, 3), snapshot.items().stream().map(GadgetPreviewSnapshotService.ItemPreview::slot).toList());
        assertEquals("/api/resource/minecraft:item/water_bucket.png", snapshot.items().get(0).textureUrl());
        assertEquals("unavailable", snapshot.items().get(1).iconStatus());
        assertEquals("", snapshot.items().get(1).textureUrl());
        assertEquals("<玩家>", snapshot.name());
    }

    @Test
    void 汇总快照不暴露可变列表() {
        var players = new ArrayList<GadgetPreviewSnapshotService.PlayerSnapshot>();
        var snapshot = new GadgetPreviewSnapshotService.Snapshot(players);
        players.add(GadgetPreviewSnapshotService.readySnapshot(UUID.randomUUID(), "玩家", VirtualGadgetBar.empty()));
        assertTrue(snapshot.players().isEmpty());
        assertThrows(UnsupportedOperationException.class, () -> snapshot.players().clear());
    }

    @org.junit.jupiter.api.io.TempDir
    java.nio.file.Path tempDir;

    @Test
    void 保存后新快照不被旧读取回调覆盖() throws Exception {
        Harness h = new Harness(tempDir.resolve("saved.yml"));
        h.service.start();
        h.corrupt();
        h.reads.remove(0).run(); // 旧读取失败已排入主线程，但还未发布。
        h.store.save(h.id, VirtualGadgetBar.empty());
        h.service.onSaved(h.id);
        h.reads.remove(0).run();
        h.main.remove(1).run(); // 新请求先完成。
        var current = h.service.snapshot();
        assertEquals("ready", current.players().get(0).status());
        h.main.remove(0).run();
        assertEquals(current, h.service.snapshot(), "旧错误不得覆盖保存后 ready 快照");
    }

    @Test
    void 停止后已排主线程回调不得发布() throws Exception {
        Harness h = new Harness(tempDir.resolve("stop.yml"));
        h.service.start();
        h.reads.remove(0).run();
        assertEquals(1, h.main.size());
        h.service.stop();
        h.main.remove(0).run();
        assertTrue(h.service.snapshot().players().isEmpty());
    }

    @Test
    void 停止再启动旧代次不得覆盖新快照() throws Exception {
        Harness h = new Harness(tempDir.resolve("restart.yml"));
        h.service.start();
        h.corrupt();
        h.reads.remove(0).run();
        h.service.stop();
        java.nio.file.Files.delete(h.store.file());
        h.store.save(h.id, VirtualGadgetBar.empty());
        h.service.start();
        h.reads.remove(0).run();
        h.main.remove(1).run();
        var current = h.service.snapshot();
        assertEquals("ready", current.players().get(0).status());
        h.main.remove(0).run();
        assertEquals(current, h.service.snapshot());
    }

    @Test
    void 关闭后迟到读取不排任务且已排回调不发布() throws Exception {
        for (boolean alreadyQueued : List.of(false, true)) {
            Harness h = new Harness(tempDir.resolve("closed-" + alreadyQueued + ".yml"));
            h.service.start();
            if (alreadyQueued) h.reads.remove(0).run();
            h.service.close();
            if (alreadyQueued) h.main.remove(0).run();
            else h.reads.remove(0).run();
            h.service.start();
            h.service.onSaved(h.id);
            assertTrue(h.main.isEmpty());
            assertTrue(h.reads.isEmpty());
            assertTrue(h.service.snapshot().players().isEmpty());
        }
    }

    /** 控制两个执行队列；真实读取文件和正式代次判定不作替换。 */
    private static final class Harness implements GadgetPreviewSnapshotService.RuntimeAccess {
        final UUID id = UUID.randomUUID();
        final List<Runnable> reads = new ArrayList<>();
        final List<Runnable> main = new ArrayList<>();
        final linmumua.doudizhu.ui.VirtualGadgetBarStore store;
        final GadgetPreviewSnapshotService service;
        Harness(java.nio.file.Path file) {
            store = new linmumua.doudizhu.ui.VirtualGadgetBarStore(file);
            store.save(id, VirtualGadgetBar.empty());
            service = new GadgetPreviewSnapshotService(store, this, reads::add);
        }
        void corrupt() throws Exception { java.nio.file.Files.writeString(store.file(), "players: [\n"); }
        public List<UUID> onlineIds() { return List.of(id); }
        public String onlineName(UUID playerId) { return id.equals(playerId) ? "玩家" : null; }
        public boolean enabled() { return true; }
        public void executeMain(Runnable task) { main.add(task); }
        public java.util.logging.Logger logger() { return java.util.logging.Logger.getLogger("快照测试"); }
    }

    /** 只替代需要服务端 ItemFactory 的操作，其余转换由正式代码执行。 */
    private static final class PlainItem extends ItemStack {
        private final Material material;
        PlainItem(Material material) { this.material = material; }
        @Override public Material getType() { return material; }
        @Override public ItemMeta getItemMeta() { return null; }
        @Override public void setAmount(int amount) { }
        @Override public ItemStack clone() { return new PlainItem(material); }
    }
}
