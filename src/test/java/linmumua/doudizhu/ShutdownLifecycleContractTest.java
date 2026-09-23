package linmumua.doudizhu;

import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

/**
 * 关闭顺序契约：只检查源码中明确的生命周期边界，不把测试夹具冒充 Paper/Folia 真实关服。
 */
class ShutdownLifecycleContractTest {
    private static final Path PLUGIN =
        Path.of("src/main/java/linmumua/doudizhu/DoudizhuPlugin.java");
    private static final Path PHYSICAL_TABLE_MANAGER =
        Path.of("src/main/java/linmumua/doudizhu/world/PhysicalTableManager.java");

    @Test
    void onDisableStopsEntriesBeforeOwnerCleanupAndClosesSchedulerLast() throws IOException {
        String source = Files.readString(PLUGIN);
        int start = source.indexOf("public void onDisable()");
        int end = source.indexOf("\n    private void runShutdownStep", start);
        assertTrue(start >= 0 && end > start, "找不到 onDisable 生命周期边界");
        String body = source.substring(start, end);

        assertBefore(body, "shuttingDown = true", "scheduler.newGeneration");
        assertBefore(body, "scheduler.newGeneration", "debugWebServer.close");
        assertBefore(body, "debugWebServer.close", "embeddedMahjongRuntime.shutdown");
        assertBefore(body, "embeddedMahjongRuntime.shutdown", "tableManager.shutdown");
        assertBefore(body, "tableManager.shutdown", "physicalTableManager.shutdown");
        assertBefore(body, "physicalTableManager.shutdown", "databaseManager.close");
        assertBefore(body, "databaseManager.close", "scheduler.close");
    }

    @Test
    void onDisableCallsMahjongRuntimeOnlyThroughNullableAssembly() throws IOException {
        String source = Files.readString(PLUGIN);
        int start = source.indexOf("public void onDisable()");
        int end = source.indexOf("\n    private void runShutdownStep", start);
        String body = source.substring(start, end);

        assertTrue(body.contains("if (embeddedMahjongRuntime != null)"),
            "未装配麻将 runtime 时不应在关闭阶段强行创建或调用它");
        assertTrue(body.contains("embeddedMahjongRuntime.shutdown()"),
            "已装配麻将 runtime 时 onDisable 必须调用 shutdown");
    }

    @Test
    void serverStoppingBranchClearsTrackingWithoutOwnerEntityOperations() throws IOException {
        String source = Files.readString(PHYSICAL_TABLE_MANAGER);
        int start = source.indexOf("public void shutdown()");
        int stopping = source.indexOf("isStopping()", start);
        int returnIndex = source.indexOf("return;", stopping);
        int normalCleanup = source.indexOf("cleanupPlacedTable(placed)", returnIndex);
        assertTrue(start >= 0 && stopping > start && returnIndex > stopping,
            "找不到 PhysicalTableManager 关服分支");
        assertTrue(!source.substring(stopping, returnIndex).contains("cleanupPlacedTable"),
            "server stopping 分支不应在非法 owner 上直接操作实体");
        assertTrue(normalCleanup > returnIndex,
            "非关服分支仍必须保留正常实体清理");
    }

    private static void assertBefore(String body, String first, String second) {
        int firstIndex = body.indexOf(first);
        int secondIndex = body.indexOf(second);
        assertTrue(firstIndex >= 0, "缺少关闭步骤: " + first);
        assertTrue(secondIndex >= 0, "缺少关闭步骤: " + second);
        assertTrue(firstIndex < secondIndex, first + " 必须早于 " + second);
    }
}
