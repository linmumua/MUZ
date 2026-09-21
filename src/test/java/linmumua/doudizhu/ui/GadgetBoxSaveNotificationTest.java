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
}
