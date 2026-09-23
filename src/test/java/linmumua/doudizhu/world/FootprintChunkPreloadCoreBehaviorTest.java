package linmumua.doudizhu.world;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import org.bukkit.Chunk;
import org.bukkit.World;
import org.junit.jupiter.api.Test;

/**
 * 锚点 footprint（3x3）异步加载核的真实行为测试：用 fake {@link World} 实际执行
 * {@link PhysicalTableManager#ensureFootprintLoadedAsyncCore}，而不是只对源码做 {@code indexOf}。
 *
 * <p>要证明的核心行为：
 * <ul>
 *   <li>请求的必须是锚点所在区块及其八邻域共 9 个区块，不多不少，且全部走
 *       {@code getChunkAtAsync}；</li>
 *   <li>整体 stage 必须等 9 个区块全部到达才完成——半加载状态下清旧实体同样会丢整桌；</li>
 *   <li>任一区块失败即整体失败，不做部分成功；</li>
 *   <li><b>绝不</b>触碰 {@code getChunkAt} / {@code getChunk} / {@code isChunkLoaded} / {@code loadChunk}
 *       这些同步区块 API：fake World 一旦被调用就记录违规，断言直接失败，而不是等实服报
 *       {@code Async chunk retrieval}；</li>
 *   <li>null world 静默失败（不抛异常），返回以异常完成的 stage。</li>
 * </ul>
 */
class FootprintChunkPreloadCoreBehaviorTest {
    private static final int BLOCK_X = 1000; // 1000 >> 4 == 62
    private static final int BLOCK_Z = 1000;

    /** 记录 fake World 上的异步请求、同步违规调用与待完成的区块 future。 */
    private static final class Recorder {
        final List<String> asyncRequests = new ArrayList<>();
        final List<String> syncViolations = new ArrayList<>();
        final Map<String, CompletableFuture<Chunk>> pending = new LinkedHashMap<>();

        void completeChunk(int chunkX, int chunkZ) {
            pending.get(chunkX + ":" + chunkZ).complete(null);
        }

        void failChunk(int chunkX, int chunkZ) {
            pending.get(chunkX + ":" + chunkZ)
                .completeExceptionally(new IllegalStateException("区块加载失败: " + chunkX + ":" + chunkZ));
        }
    }

    @Test
    void footprintRequestsExactlyTheNineNeighbourhoodChunksAsynchronously() {
        Recorder recorder = new Recorder();
        CompletionStage<Void> stage =
            PhysicalTableManager.ensureFootprintLoadedAsyncCore(fakeWorld(recorder), BLOCK_X, BLOCK_Z);

        assertEquals(expectedFootprint(62, 62), new LinkedHashSet<>(recorder.asyncRequests),
            "必须请求锚点 3x3 的九个区块，且不含多余区块");
        assertEquals(9, recorder.asyncRequests.size(), "一枚锚点只应发起九次异步加载");
        assertTrue(recorder.syncViolations.isEmpty(),
            "footprint 异步加载核不得触碰同步区块 API：" + recorder.syncViolations);
        assertFalse(stage.toCompletableFuture().isDone(), "九个区块都没到达时 stage 不得完成");
    }

    @Test
    void footprintStageCompletesOnlyAfterAllNineChunksArrive() {
        Recorder recorder = new Recorder();
        CompletionStage<Void> stage =
            PhysicalTableManager.ensureFootprintLoadedAsyncCore(fakeWorld(recorder), BLOCK_X, BLOCK_Z);

        for (int dx = -1; dx <= 1; dx++) {
            for (int dz = -1; dz <= 1; dz++) {
                if (dx == 1 && dz == 1) {
                    continue;
                }
                recorder.completeChunk(62 + dx, 62 + dz);
            }
        }
        assertFalse(stage.toCompletableFuture().isDone(),
            "还剩一个区块未到时 stage 不得完成：半加载状态下清旧实体同样会丢整桌");

        recorder.completeChunk(63, 63);
        assertTrue(stage.toCompletableFuture().isDone(), "九个区块全部到达后 stage 必须完成");
        assertFalse(stage.toCompletableFuture().isCompletedExceptionally(), "全部成功时不得以异常完成");
    }

    @Test
    void singleChunkFailureFailsTheWholeFootprint() {
        Recorder recorder = new Recorder();
        CompletionStage<Void> stage =
            PhysicalTableManager.ensureFootprintLoadedAsyncCore(fakeWorld(recorder), BLOCK_X, BLOCK_Z);

        for (String key : new ArrayList<>(recorder.pending.keySet())) {
            if (!key.equals("61:61")) {
                recorder.pending.get(key).complete(null);
            }
        }
        assertFalse(stage.toCompletableFuture().isDone(), "还有区块未到达时 stage 不得提前完成");
        recorder.failChunk(61, 61);

        assertTrue(stage.toCompletableFuture().isCompletedExceptionally(),
            "任一区块加载失败必须让整份 footprint 失败，不做部分成功");
    }

    @Test
    void footprintCoversNegativeChunkCoordinates() {
        Recorder recorder = new Recorder();
        // -1000 >> 4 == -63（算术右移，向下取整到正确区块）
        PhysicalTableManager.ensureFootprintLoadedAsyncCore(fakeWorld(recorder), -1000, -1000);

        assertEquals(expectedFootprint(-63, -63), new LinkedHashSet<>(recorder.asyncRequests),
            "负坐标区块必须同样覆盖完整 3x3，不能因取整偏差漏格或串格");
    }

    @Test
    void nullWorldFailsWithoutThrowing() {
        Recorder recorder = new Recorder();
        CompletionStage<Void> stage = assertDoesNotThrow(
            () -> PhysicalTableManager.ensureFootprintLoadedAsyncCore(null, 0, 0),
            "null world 必须静默失败，不能把异常抛给调用方打断整条重建流水线");
        assertTrue(stage.toCompletableFuture().isCompletedExceptionally(),
            "缺少世界时 stage 必须以异常完成，调用方据此放弃本桌重建");
        assertTrue(recorder.syncViolations.isEmpty(), "没有世界时也不得触碰任何区块 API");
    }

    /** 3x3 期望区块集合；用集合比较，不锁死实现的遍历顺序。 */
    private static Set<String> expectedFootprint(int chunkX, int chunkZ) {
        Set<String> expected = new LinkedHashSet<>();
        for (int dx = -1; dx <= 1; dx++) {
            for (int dz = -1; dz <= 1; dz++) {
                expected.add((chunkX + dx) + ":" + (chunkZ + dz));
            }
        }
        return expected;
    }

    /**
     * 以 JDK 动态代理构造 fake {@link World}：只支持 {@code getChunkAtAsync}，其它同步区块 API
     * 一旦被调用就记进 {@code syncViolations}，断言随即失败。
     */
    private static World fakeWorld(Recorder recorder) {
        ClassLoader loader = FootprintChunkPreloadCoreBehaviorTest.class.getClassLoader();
        return (World) Proxy.newProxyInstance(loader, new Class<?>[] {World.class}, (proxy, method, args) -> {
            switch (method.getName()) {
                case "getName":
                    return "fake-world";
                case "getChunkAtAsync": {
                    int chunkX = (Integer) args[0];
                    int chunkZ = (Integer) args[1];
                    String key = chunkX + ":" + chunkZ;
                    recorder.asyncRequests.add(key);
                    return recorder.pending.computeIfAbsent(key, ignored -> new CompletableFuture<>());
                }
                case "getChunkAt":
                case "getChunk":
                case "loadChunk":
                case "isChunkLoaded":
                    recorder.syncViolations.add(method.getName());
                    return defaultValue(method.getReturnType());
                case "toString":
                    return "fakeWorld";
                case "hashCode":
                    return System.identityHashCode(proxy);
                case "equals":
                    return proxy == args[0];
                default:
                    return defaultValue(method.getReturnType());
            }
        });
    }

    private static Object defaultValue(Class<?> type) {
        if (type == boolean.class) {
            return false;
        }
        if (type == int.class) {
            return 0;
        }
        if (type == long.class) {
            return 0L;
        }
        if (type == double.class) {
            return 0.0d;
        }
        if (type == float.class) {
            return 0.0f;
        }
        if (type == short.class) {
            return (short) 0;
        }
        if (type == byte.class) {
            return (byte) 0;
        }
        if (type == char.class) {
            return (char) 0;
        }
        return null;
    }
}
