package linmumua.doudizhu.mahjong;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;

/**
 * 麻将 owner 路由契约：只锁定桌 region、玩家 lane 与关闭代次边界。
 *
 * <p>这些测试不启动 Bukkit/Folia；它们验证业务入口没有把实体操作偷偷降级到 global，
 * 也不把源码夹具冒充真实服务端线程验收。
 */
class MahjongOwnerRoutingContractTest {
    private static final Path MAHJONG_MANAGER =
        Path.of("src/main/java/linmumua/doudizhu/mahjong/MahjongTableManager.java");
    @Test
    void sessionGenerationIsMonotonicAndStartsAtOne() {
        MahjongLayoutConfig layout = new MahjongLayoutConfig(
            0.0, 0.0, 0.0, 0.0, 1.0, 0.0, 0.0,
            0.0, 0.0, 0.0, 0.0, 1.0, 1.0, 1.0
        );
        MahjongTableSession session = new MahjongTableSession(
            "A", new org.bukkit.Location(null, 0.0, 64.0, 0.0),
            UUID.randomUUID(), "owner", 1L, layout
        );

        assertEquals(1L, session.generation());
        assertEquals(2L, session.nextGeneration());
        assertEquals(3L, session.nextGeneration());
    }

    @Test
    void sessionStateAndVisualRegistriesExposeSnapshotsOnly() {
        MahjongTableSession session = session();
        UUID playerId = UUID.randomUUID();
        UUID entityId = UUID.randomUUID();

        assertTrue(session.sit(MahjongTableSession.Seat.EAST, playerId, "玩家"));
        session.rememberVisualEntity(entityId);

        assertThrows(UnsupportedOperationException.class,
            () -> session.occupants().clear());
        assertThrows(UnsupportedOperationException.class,
            () -> session.occupantNames().clear());
        assertThrows(UnsupportedOperationException.class,
            () -> session.readyStates().clear());
        assertEquals(List.of(playerId), session.occupantIdsSnapshot());
        assertEquals(List.of(entityId), session.visualEntityIdsSnapshot());
        assertNotSame(session.visualEntityIds(), session.visualEntityIdsSnapshot());
        assertTrue(session.ownsVisualEntity(entityId));

        session.clearVisuals();
        assertTrue(session.visualEntityIdsSnapshot().isEmpty());
        assertFalse(session.ownsVisualEntity(entityId));
    }

    @Test
    void generationGuardRejectsLateOwnerCallback() {
        MahjongTableSession session = session();
        long expected = session.generation();
        assertTrue(session.isGeneration(expected));
        long next = session.nextGeneration();
        assertFalse(session.isGeneration(expected));
        assertTrue(session.isGeneration(next));
    }

    @Test
    void generationIncrementIsAtomicForConcurrentOwnerCallbacks() throws Exception {
        MahjongTableSession session = session();
        int workers = 4;
        int incrementsPerWorker = 250;
        CountDownLatch start = new CountDownLatch(1);
        ExecutorService executor = Executors.newFixedThreadPool(workers);
        List<java.util.concurrent.Future<?>> futures = new ArrayList<>();
        for (int worker = 0; worker < workers; worker++) {
            futures.add(executor.submit(() -> {
                start.await();
                for (int index = 0; index < incrementsPerWorker; index++) {
                    session.nextGeneration();
                }
                return null;
            }));
        }
        start.countDown();
        for (var future : futures) {
            future.get(10, TimeUnit.SECONDS);
        }
        executor.shutdownNow();
        assertEquals(1L + workers * incrementsPerWorker, session.generation());
    }

    @Test
    void tableRegionEntryCapturesGenerationAndTableIdentity() throws IOException {
        String source = Files.readString(MAHJONG_MANAGER);
        String body = methodBody(source, "public MuzScheduler.TaskHandle runOnTableRegion(");

        assertTrue(body.contains("long expectedGeneration = table.generation()"),
            "桌 region 任务必须捕获提交时的 session generation");
        assertTrue(body.contains("table.generation() == expectedGeneration"),
            "迟到的旧 generation 回调必须被拒绝");
        assertTrue(body.contains("tables.get(table.id()) == table"),
            "旧 table 实例回调不得写入同名的新桌实例");
        assertTrue(body.contains("plugin.scheduler().runRegion(table.anchor()"),
            "麻将实体任务必须投递到桌锚点 region");
        assertFalse(body.contains("runGlobal("), "桌实体 owner 入口不得降级到 global");
    }

    @Test
    void renderAndVisualRemovalAdvanceGenerationBeforeRegionSubmission() throws IOException {
        String source = Files.readString(MAHJONG_MANAGER);
        String rerender = methodBody(source, "private void rerender(");
        String removeVisuals = methodBody(source, "private void removeVisuals(");

        assertTrue(rerender.contains("table.nextGeneration()"),
            "重渲染提交前必须推进 generation，淘汰已排队旧回调");
        assertTrue(rerender.contains("plugin.scheduler().runRegion(table.anchor()"),
            "重渲染必须使用桌 region owner");
        assertTrue(removeVisuals.contains("table.nextGeneration()"),
            "实体清理提交前必须推进 generation");
        assertTrue(removeVisuals.contains("plugin.scheduler().runRegion(table.anchor()"),
            "实体删除必须使用桌 region owner");
        assertFalse(source.contains("plugin.scheduler().runGlobal("),
            "麻将管理器不得通过 global scheduler 执行桌实体操作");
        assertFalse(source.contains("plugin.scheduler().runSync("),
            "麻将管理器不得通过旧 runSync 入口执行桌实体操作");
    }

    @Test
    void playerOutputIsSeparatedFromTableEntityOwner() throws IOException {
        String source = Files.readString(MAHJONG_MANAGER);
        String runForPlayer = methodBody(source, "public MuzScheduler.TaskHandle runForPlayer(UUID playerId, java.util.function.Consumer<Player> task)");
        String sendSingle = methodBody(source, "public void sendToPlayer(UUID playerId, Component message)");
        String sendMany = methodBody(source, "public void sendToPlayer(UUID playerId, List<Component> messages)");

        assertTrue(runForPlayer.contains("playerOutput.runPlayer(playerId, task)"),
            "玩家消息必须按 UUID 进入玩家自己的 owner lane");
        assertTrue(sendSingle.contains("playerOutput.sendMessage(playerId, message)"),
            "单条玩家消息不得直接在桌或 global 线程发送");
        assertTrue(sendMany.contains("sendToPlayer(playerId, message)"),
            "批量玩家消息必须逐条经过 UUID player lane 门面");
        assertFalse(sendSingle.contains("plugin.scheduler().runGlobal("),
            "玩家消息不得走 global scheduler");
        assertFalse(sendMany.contains("plugin.scheduler().runGlobal("),
            "玩家消息不得走 global scheduler");
    }

    @Test
    void shutdownAndReloadKeepOwnerGenerationBoundary() throws IOException {
        String source = Files.readString(MAHJONG_MANAGER);
        String shutdown = methodBody(source, "public void shutdown()");
        String reload = methodBody(source, "public void reloadLayout(");

        assertTrue(shutdown.contains("shutdown = true"), "关闭必须先封住麻将新入口");
        // 机制变更（非弱化）：关闭清理从「逐桌 fire-and-forget 调 removeVisuals」改为
        // 「逐桌登记 owner region 清理 request 并交给完成屏障」，断言改为检查更强的新形态。
        assertTrue(shutdown.contains("cleanupOnTableRegion(table)"),
            "关闭必须为每张麻将桌在桌 region 内联提交 owner 清理");
        assertTrue(shutdown.contains("new RegionTaskBarrier("),
            "关闭清理必须登记为完成屏障");
        assertTrue(shutdown.contains("tables.clear()"), "关闭必须清空桌会话");
        assertTrue(reload.contains("if (shutdown)"), "关闭后的配置重载不得再排桌任务");
        assertTrue(source.contains("table.nextGeneration()"),
            "重载/重渲染与关闭清理必须具备代次淘汰屏障");
    }

    @Test
    void sessionKeepsSchedulingAndBukkitObjectsOutsideItsUuidFacade() throws IOException {
        String source = Files.readString(Path.of(
            "src/main/java/linmumua/doudizhu/mahjong/MahjongTableSession.java"));
        assertFalse(source.contains("import org.bukkit.entity.Player"),
            "Session 不应持有 Player 或直接执行玩家输出");
        assertFalse(source.contains("import org.bukkit.entity.Entity"),
            "Session 不应持有 Entity 或直接执行实体操作");
        assertFalse(source.contains("MuzScheduler"),
            "Session 不应直接创建延迟任务，owner 调度必须由外部门面负责");
        assertTrue(source.contains("AtomicLong"),
            "延迟回调代次必须使用原子 owner 闸门");
        assertTrue(source.contains("visualEntityIdsSnapshot"),
            "清理回调必须消费 UUID 快照，而不是跨线程持有实体对象");
        assertTrue(source.contains("occupantIdsSnapshot"),
            "玩家输出必须消费 UUID 快照并交给 player lane 门面");
    }

    private static MahjongTableSession session() {
        MahjongLayoutConfig layout = new MahjongLayoutConfig(
            0.0, 0.0, 0.0, 0.0, 1.0, 0.0, 0.0,
            0.0, 0.0, 0.0, 0.0, 1.0, 1.0, 1.0
        );
        return new MahjongTableSession(
            "A", new org.bukkit.Location(null, 0.0, 64.0, 0.0),
            UUID.randomUUID(), "owner", 1L, layout
        );
    }

    private static String methodBody(String source, String signature) {
        int start = source.indexOf(signature);
        assertTrue(start >= 0, "找不到方法锚点: " + signature);
        int open = source.indexOf('{', start);
        assertTrue(open > start, "找不到方法起始大括号: " + signature);
        int depth = 0;
        boolean quoted = false;
        boolean escaped = false;
        for (int index = open; index < source.length(); index++) {
            char current = source.charAt(index);
            if (quoted) {
                if (escaped) {
                    escaped = false;
                } else if (current == '\\') {
                    escaped = true;
                } else if (current == '"') {
                    quoted = false;
                }
                continue;
            }
            if (current == '"') {
                quoted = true;
            } else if (current == '{') {
                depth++;
            } else if (current == '}') {
                depth--;
                if (depth == 0) {
                    return source.substring(start, index);
                }
            }
        }
        throw new AssertionError("找不到方法结束大括号: " + signature);
    }
}
