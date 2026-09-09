package linmumua.doudizhu.storage;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

/**
 * 写库必须走异步线程，且关服时必须退化成同步。
 *
 * <p>【为什么写操作要异步】：项目规范明确禁止在主线程做数据库 I/O。
 * 这三个写方法是最频繁的主线程 I/O 来源：每局结算写战绩、每次摆桌/移桌写存档。
 * SQLite 尚能忍，换成 MySQL 后每次结算都要等一个网络往返，主线程直接卡住。
 *
 * <p>【为什么关服必须退化成同步】：这是本次改动最容易踩的坑。
 * Bukkit 在插件 disable 之后再提交异步任务会抛 IllegalPluginAccessException，
 * 而且已排队未执行的任务会被直接丢弃——关服那一刻的写库就永久丢失了。
 * 所以关服路径宁可阻塞主线程也要把数据落盘。
 */
class DatabaseWriteIsAsyncTest {
    private static final Path MANAGER =
        Path.of("src/main/java/linmumua/doudizhu/storage/DatabaseManager.java");

    /** 三个写方法都必须经由 runWrite 分发，不许自己直连。 */
    private static final String[] WRITE_METHODS = {
        "public void upsertTable(",
        "public void deleteTable(",
        "public void insertMatch(",
    };

    @Test
    void allWriteMethodsDispatchThroughRunWrite() throws IOException {
        String source = Files.readString(MANAGER);

        for (String signature : WRITE_METHODS) {
            String body = methodBody(source, signature);
            assertTrue(
                body.contains("runWrite("),
                signature + " 没走 runWrite，写库会留在主线程上"
            );
            assertFalse(
                body.contains("openConnection()"),
                signature + " 里还在直接开连接，说明没有真正下沉到异步体"
            );
        }
    }

    /**
     * runWrite 必须在关服/禁用时退化成同步执行。
     * 失败条件：有人把同步回退删掉，只留 runAsync。
     */
    @Test
    void runWriteFallsBackToSyncOnShutdown() throws IOException {
        String body = methodBody(Files.readString(MANAGER), "private void runWrite(");

        assertTrue(
            body.contains("isShuttingDown()"),
            "runWrite 没判断关服状态，关服时提交异步任务会抛 IllegalPluginAccessException，"
                + "而且排队中的写库会被丢弃"
        );
        assertTrue(
            body.contains("isEnabled()"),
            "runWrite 没判断插件是否仍启用，disable 之后调度会抛异常"
        );
        assertTrue(
            body.contains("runAsync("),
            "runWrite 根本没用异步，那这次改动等于没做"
        );

        int shutdownCheck = body.indexOf("isShuttingDown()");
        int asyncCall = body.indexOf("runAsync(");
        assertTrue(
            shutdownCheck < asyncCall,
            "关服判断必须在 runAsync 之前，否则关服时仍会走异步分支把数据丢掉"
        );
    }

    /**
     * 异步体内必须自己记日志。
     *
     * <p>异步线程抛出的异常不会自动出现在控制台。不记日志的话，
     * 写库失败就是彻底静默的数据丢失——这正是项目规范禁止的「吞异常不记录」。
     */
    @Test
    void asyncBodyLogsItsOwnFailures() throws IOException {
        String body = methodBody(Files.readString(MANAGER), "private void runWrite(");

        assertTrue(
            body.contains("catch (SQLException"),
            "runWrite 没接 SQLException，异步线程里抛出会静默丢数据"
        );
        assertTrue(
            body.contains("getLogger()"),
            "写库失败没记日志，违反规范的「禁止吞异常不记录」"
        );
    }

    /**
     * insertMatch 不许再返回 matchId。
     *
     * <p>异步化之后 matchId 只能在异步线程拿到，同步返回值必然是假的。
     * 留着一个「永远返回 -1」的 long 会误导调用方以为拿到了真实 id。
     */
    @Test
    void insertMatchDoesNotPretendToReturnAnId() throws IOException {
        String source = Files.readString(MANAGER);

        assertFalse(
            source.contains("public long insertMatch("),
            "insertMatch 仍然声明返回 long，但异步化后这个值只能是假的"
        );
        assertTrue(
            source.contains("public void insertMatch("),
            "insertMatch 的签名应该收成 void"
        );
    }

    /** 读方法保持同步：本轮刻意不动它们，避免牵动启动时序与 GUI 生命周期。 */
    @Test
    void readMethodsStaySynchronousForNow() throws IOException {
        String source = Files.readString(MANAGER);

        assertTrue(
            source.contains("public List<PersistedTableRecord> loadTables()"),
            "loadTables 的签名变了。它的结果要用来创建 Display Entity，"
                + "异步化必须连带重设计启动时序，不能顺手改"
        );
        assertTrue(
            source.contains("public List<PlayerHistoryEntry> loadPlayerHistory("),
            "loadPlayerHistory 的签名变了。改异步要同时处理"
                + "「查完时玩家已关界面/已下线」，属于单独一轮的事"
        );
    }

    private static String methodBody(String source, String signature) {
        int start = source.indexOf(signature);
        assertTrue(start >= 0, "找不到 " + signature + "，这条测试的锚点已失效");
        int end = source.indexOf("\n    }", start);
        assertTrue(end > start, "找不到 " + signature + " 的结束锚点");
        return source.substring(start, end);
    }
}
