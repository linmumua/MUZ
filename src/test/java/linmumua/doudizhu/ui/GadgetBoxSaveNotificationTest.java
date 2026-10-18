package linmumua.doudizhu.ui;

import static org.junit.jupiter.api.Assertions.*;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.UUID;
import java.util.logging.Handler;
import java.util.logging.Level;
import java.util.logging.LogRecord;
import java.util.logging.Logger;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** 实际注入预览通知异常，并锁定保存与 GUI 更新的调用边界。 */
class GadgetBoxSaveNotificationTest {
    @TempDir Path directory;

    @Test
    void 通知异常不外抛且记录原始异常并保留持久化() {
        UUID id = UUID.randomUUID();
        VirtualGadgetBarStore store = new VirtualGadgetBarStore(directory.resolve("players.yml"));
        RuntimeException failure = new IllegalStateException("预览监听失败");
        var records = new ArrayList<LogRecord>();
        Logger logger = Logger.getLogger(GadgetBoxGuiService.class.getName());
        Handler handler = new Handler() {
            public void publish(LogRecord record) { records.add(record); }
            public void flush() { }
            public void close() { }
        };
        GadgetBoxGuiService service = new GadgetBoxGuiService(store, action -> { }, playerId -> {
            assertEquals(id, playerId);
            assertTrue(store.loadRaw(playerId).present());
            throw failure;
        }, "道具箱", "语音");
        logger.addHandler(handler);
        try {
            store.save(id, VirtualGadgetBar.empty());
            assertDoesNotThrow(() -> service.notifySaved(id));
            assertTrue(store.loadRaw(id).present());
            assertTrue(store.load(id).isEmpty());
            assertEquals(1, records.size());
            assertEquals(Level.WARNING, records.get(0).getLevel());
            assertSame(failure, records.get(0).getThrown());
        } finally {
            logger.removeHandler(handler);
        }
    }

    @Test
    void 配置编辑GUI的玩家输出只允许同步主线程() throws Exception {
        String source = Files.readString(Path.of("src/main/java/linmumua/doudizhu/ui/GadgetBoxGuiService.java"));
        assertTrue(source.contains("private static void requirePrimaryThread(String operation)"));
        assertMethodRequiresPrimaryThread(source, "public void open(Player player)");
        assertMethodRequiresPrimaryThread(source, "public void refresh(Player player)");
        assertMethodRequiresPrimaryThread(source, "public ItemStack selectedItem(UUID playerId)");
        assertMethodRequiresPrimaryThread(source, "public void close(Player player)");
        assertMethodRequiresPrimaryThread(source, "public void handleClick(InventoryClickEvent event)");
        assertMethodRequiresPrimaryThread(source, "public void handleDrag(InventoryDragEvent event)");
        assertFalse(source.contains("CompletableFuture"));
        assertFalse(source.contains("runAsync"));
    }

    @Test
    void 保存通知之后继续快照选择和重绘() throws Exception {
        String source = Files.readString(Path.of("src/main/java/linmumua/doudizhu/ui/GadgetBoxGuiService.java"));
        int start = source.indexOf("private void saveAndRender(");
        int end = source.indexOf("void notifySaved(", start);
        assertTrue(start >= 0 && end > start);
        String method = source.substring(start, end);
        int save = method.indexOf("store.save(player.getUniqueId(), next);");
        int listener = method.indexOf("notifySaved(player.getUniqueId());");
        int snapshot = method.indexOf("holder.setSnapshot(next);");
        int normalize = method.indexOf("int normalized = normalizeSelection(selected, next);");
        int render = method.indexOf("render(holder.getInventory(), next, normalized);");
        assertTrue(save >= 0 && listener > save);
        assertTrue(snapshot > listener && normalize > snapshot && render > normalize);
    }

    /**
     * 生产入口不再打开旧的 Inventory 箱子。
     *
     * <p>用户要求：道具箱从箱子 Inventory 迁到原生 Dialog 后，旧箱子入口的生产路径必须不可达——
     * 「新建了 Dialog 但旧箱子入口仍可打开」正是要避免的迁移回归。判定去掉注释后再看：
     * 退役说明会以注释方式保留「旧箱子 createInventory + GadgetBoxInventoryHolder」的因果链，
     * 直接对原文 contains 会被注释误判。
     */
    @Test
    void 生产入口不再创建Inventory箱子() throws Exception {
        String code = stripComments(
            Files.readString(Path.of("src/main/java/linmumua/doudizhu/ui/GadgetBoxGuiService.java")));

        assertFalse(code.contains("createInventory"),
            "open 路径不得再 createInventory：道具箱已迁 Dialog，旧箱子生产路径必须不可达");
        assertTrue(code.contains("setDialogOpener"),
            "必须保留 Dialog 委托注入点，装配方才能把编辑屏接上去");
    }

    /** 去掉块注释与行注释，只留可执行代码（退役说明会以注释保留被退役 API 的名字）。 */
    private static String stripComments(String source) {
        return source.replaceAll("(?s)/\\*.*?\\*/", "").replaceAll("(?m)//.*$", "");
    }

    private static void assertMethodRequiresPrimaryThread(String source, String signature) {
        int start = source.indexOf(signature);
        assertTrue(start >= 0, "缺少方法：" + signature);
        int guard = source.indexOf("requirePrimaryThread(", start);
        int nextMethod = source.indexOf("\n    public ", start + signature.length());
        if (nextMethod < 0) {
            nextMethod = source.length();
        }
        assertTrue(guard >= start && guard < nextMethod, "方法未证明主线程：" + signature);
    }
}
