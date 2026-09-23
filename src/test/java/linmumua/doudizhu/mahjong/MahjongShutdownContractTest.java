package linmumua.doudizhu.mahjong;

import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

/**
 * 内嵌麻将关闭契约：验证幂等闸门与 region owner 清理边界，不模拟真实 Folia 服务端。
 */
class MahjongShutdownContractTest {
    private static final Path RUNTIME =
        Path.of("src/main/java/linmumua/doudizhu/mahjong/EmbeddedMahjongRuntime.java");
    private static final Path MANAGER =
        Path.of("src/main/java/linmumua/doudizhu/mahjong/MahjongTableManager.java");

    @Test
    void runtimeShutdownIsIdempotentAndDelegatesToManager() throws IOException {
        String source = Files.readString(RUNTIME);
        int start = source.indexOf("public void shutdown()");
        int end = source.indexOf("\n    private void send", start);
        String body = source.substring(start, end);

        assertTrue(source.contains("AtomicBoolean shutdown"), "runtime 缺少幂等关闭闸门");
        assertTrue(body.contains("compareAndSet(false, true)"), "runtime.shutdown 必须幂等");
        assertTrue(body.contains("tableManager.shutdown()"), "runtime.shutdown 必须委托桌管理器");
        assertTrue(source.contains("return !shutdown.get() && plugin.isMahjongIntegrationEnabled()"),
            "关闭后的 runtime 不得继续作为可用入口");
    }

    @Test
    void managerRejectsNewEntriesAfterShutdownAndKeepsRegionCleanup() throws IOException {
        String source = Files.readString(MANAGER);
        int shutdownStart = source.indexOf("public void shutdown()");
        int shutdownEnd = source.indexOf("\n    private void ensureOpen", shutdownStart);
        String shutdownBody = source.substring(shutdownStart, shutdownEnd);

        assertTrue(shutdownBody.contains("if (shutdown)"), "manager.shutdown 必须重复调用安全");
        assertTrue(shutdownBody.contains("shutdown = true"), "manager 必须先进入关闭态再清理");
        // 机制变更（非弱化）：关闭清理从「逐桌 fire-and-forget 调 removeVisuals」改为
        // 「逐桌登记 owner region 清理 request，并交给完成屏障」。断言随之改为检查更强的新形态：
        // 既锁定真实删除必须在桌 region 内联执行，也锁定清理屏障确实被创建。
        assertTrue(shutdownBody.contains("cleanupOnTableRegion(table)"),
            "已有麻将桌必须进入视觉清理，且在桌 region 内联执行真实删除");
        assertTrue(shutdownBody.contains("new RegionTaskBarrier(") && shutdownBody.contains("requests"),
            "关闭清理必须逐桌登记为完成屏障的 request 集合");
        assertTrue(source.contains("public CompletableFuture<RegionTaskBarrier.Result> shutdownCompletion()"),
            "关闭清理必须暴露可观察的完成屏障句柄");
        assertTrue(shutdownBody.contains("isStopping()"),
            "关服分支必须与 PhysicalTableManager 一致：region 可能已不可用时不在非法 owner 上操作实体");
        assertTrue(source.contains("if (shutdown || table == null || task == null"),
            "关闭后不得继续接受麻将桌 region 任务");
        assertTrue(source.contains("ensureOpen();"), "创建入口必须拒绝关闭后的新麻将桌");
        assertTrue(shutdownBody.contains("scheduler 由插件入口最后关闭"),
            "关闭契约必须记录 scheduler/backend 在 owner 清理后再关闭");
    }
}
