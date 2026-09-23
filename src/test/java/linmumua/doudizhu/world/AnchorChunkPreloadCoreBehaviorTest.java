package linmumua.doudizhu.world;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import org.bukkit.Chunk;
import org.bukkit.World;
import org.junit.jupiter.api.Test;

/**
 * 锚点区块预热核心的真实行为测试：用 fake {@link World}/{@link Chunk} 实际执行
 * {@link PhysicalTableManager#ensureChunkReadyCore} 与
 * {@link PhysicalTableManager#ensureAnchorChunkLoadedCore}，而不是只对源码做 {@code indexOf}。
 *
 * <p>要证明的核心行为：
 * <ul>
 *   <li>非区域化核心（Paper/Leaf）必须同步加载缺失区块；已加载的不重复加载；</li>
 *   <li>区域化核心（Folia）<b>严禁同步获取断言</b>——fake World 的 {@code getChunkAt} 一旦被调用
 *       就直接抛 {@link AssertionError}，因此测试会当场失败，而不是等实服报
 *       {@code Async chunk retrieval}；</li>
 *   <li>区域化核心对未加载区块走只读检查 + 告警。</li>
 * </ul>
 */
class AnchorChunkPreloadCoreBehaviorTest {
    private static final int BLOCK_X = 1000; // 1000 >> 4 == 62
    private static final int BLOCK_Z = 1000;

    /** 记录 fake World/Chunk 上的调用，供断言检查。 */
    private static final class Recorder {
        final List<String> syncFetches = new ArrayList<>();
        final List<String> loads = new ArrayList<>();
        final List<String> warnings = new ArrayList<>();
    }

    // --- ensureChunkReadyCore：锚点单区块 ---

    @Test
    void nonRegionizedCoreLoadsMissingAnchorChunk() {
        Recorder recorder = new Recorder();
        World world = fakeWorld(Set.of(), recorder, false, false);
        PhysicalTableManager.ensureChunkReadyCore(world, BLOCK_X, BLOCK_Z, false, recorder.warnings::add);

        assertEquals(List.of("62:62"), recorder.syncFetches, "非区域化核心必须同步拉取锚点区块");
        assertEquals(List.of("load"), recorder.loads, "缺失区块必须 load()");
        assertTrue(recorder.warnings.isEmpty(), "非区域化核心不应告警未加载");
    }

    @Test
    void nonRegionizedCoreDoesNotReloadAlreadyLoadedAnchorChunk() {
        Recorder recorder = new Recorder();
        World world = fakeWorld(Set.of(), recorder, false, true); // 返回的 chunk 已加载
        PhysicalTableManager.ensureChunkReadyCore(world, BLOCK_X, BLOCK_Z, false, recorder.warnings::add);

        assertEquals(List.of("62:62"), recorder.syncFetches, "非区域化核心仍会同步获取区块");
        assertTrue(recorder.loads.isEmpty(), "已加载区块不得再次 load()");
    }

    @Test
    void regionizedCoreNeverSynchronouslyFetchesAnchorChunk() {
        Recorder recorder = new Recorder();
        // forbidSync=true：getChunkAt 若被调用直接抛 AssertionError，同步拉区块的改动会当场失败。
        World world = fakeWorld(Set.of(), recorder, true, false);
        assertDoesNotThrow(
            () -> PhysicalTableManager.ensureChunkReadyCore(world, BLOCK_X, BLOCK_Z, true, recorder.warnings::add),
            "区域化核心绝不能同步拉区块：fake getChunkAt 被调用会抛 AssertionError");
        assertTrue(recorder.syncFetches.isEmpty(), "区域化核心不得调用同步 getChunkAt");
        assertEquals(1, recorder.warnings.size(), "区域化核心对未加载区块必须告警");
        assertTrue(recorder.warnings.get(0).contains("62"), "告警必须包含锚点区块坐标");
    }

    @Test
    void regionizedCoreIsSilentWhenAnchorChunkAlreadyLoaded() {
        Recorder recorder = new Recorder();
        World world = fakeWorld(Set.of(pack(62, 62)), recorder, true, false);
        PhysicalTableManager.ensureChunkReadyCore(world, BLOCK_X, BLOCK_Z, true, recorder.warnings::add);

        assertTrue(recorder.syncFetches.isEmpty(), "区域化核心不得同步拉区块");
        assertTrue(recorder.warnings.isEmpty(), "区块已加载时区域化核心不应告警");
    }

    // --- ensureAnchorChunkLoadedCore：锚点 3x3 邻域 ---

    @Test
    void nonRegionizedCorePreloadsOnlyUnloadedNeighbourhoodChunks() {
        Recorder recorder = new Recorder();
        Set<Long> loaded = Set.of(pack(62, 62));
        World world = fakeWorld(loaded, recorder, false, true);
        PhysicalTableManager.ensureAnchorChunkLoadedCore(world, BLOCK_X, BLOCK_Z, false, recorder.warnings::add);

        assertEquals(8, recorder.syncFetches.size(), "3x3 里已加载 1 格，只应同步拉其余 8 格");
        assertFalse(recorder.syncFetches.contains("62:62"), "已加载区块必须跳过，不得重复获取");
        assertTrue(recorder.loads.isEmpty(), "返回的 chunk 已加载，不得再 load()");
        assertTrue(recorder.warnings.isEmpty(), "非区域化核心不应告警");
    }

    @Test
    void regionizedCoreNeverPreloadsNeighbourhoodChunks() {
        Recorder recorder = new Recorder();
        World world = fakeWorld(Set.of(), recorder, true, false);
        assertDoesNotThrow(
            () -> PhysicalTableManager.ensureAnchorChunkLoadedCore(world, BLOCK_X, BLOCK_Z, true, recorder.warnings::add),
            "区域化核心绝不能同步拉邻域区块：fake getChunkAt 被调用会抛 AssertionError");
        assertTrue(recorder.syncFetches.isEmpty(), "区域化核心不得同步拉任何邻域区块");
        assertEquals(9, recorder.warnings.size(), "3x3 共 9 个未加载区块，必须逐个告警");
    }

    @Test
    void regionizedCoreNeighbourhoodIsSilentWhenAllChunksLoaded() {
        Recorder recorder = new Recorder();
        Set<Long> all = new HashSet<>(PhysicalTableManager.anchorChunkKeys(BLOCK_X, BLOCK_Z));
        World world = fakeWorld(all, recorder, true, false);
        PhysicalTableManager.ensureAnchorChunkLoadedCore(world, BLOCK_X, BLOCK_Z, true, recorder.warnings::add);

        assertTrue(recorder.syncFetches.isEmpty(), "区域化核心不得同步拉区块");
        assertTrue(recorder.warnings.isEmpty(), "全部已加载时区域化核心不应告警");
    }

    @Test
    void nullWorldIsToleratedOnBothBranches() {
        Recorder recorder = new Recorder();
        assertDoesNotThrow(() -> PhysicalTableManager.ensureChunkReadyCore(null, 0, 0, false, recorder.warnings::add));
        assertDoesNotThrow(() -> PhysicalTableManager.ensureChunkReadyCore(null, 0, 0, true, recorder.warnings::add));
        assertDoesNotThrow(() -> PhysicalTableManager.ensureAnchorChunkLoadedCore(null, 0, 0, false, recorder.warnings::add));
        assertDoesNotThrow(() -> PhysicalTableManager.ensureAnchorChunkLoadedCore(null, 0, 0, true, recorder.warnings::add));
        assertTrue(recorder.warnings.isEmpty(), "null world 应静默返回，不告警");
    }

    private static long pack(int chunkX, int chunkZ) {
        return ((long) chunkX << 32) | (chunkZ & 0xFFFFFFFFL);
    }

    /**
     * 以 JDK 动态代理构造 fake {@link World}（与 fake {@link Chunk}）。
     *
     * @param loadedChunks       已加载区块（打包坐标）集合，供 {@code isChunkLoaded} 查表
     * @param recorder           记录同步拉取/加载调用
     * @param forbidSync         为 true 时 {@code getChunkAt} 直接抛 {@link AssertionError}，
     *                           模拟 Folia「禁止同步获取」——被调用即测试失败
     * @param returnedChunkLoaded 非区域化分支经 {@code getChunkAt} 拿到的 chunk 的 {@code isLoaded()}
     */
    private static World fakeWorld(
        Set<Long> loadedChunks, Recorder recorder, boolean forbidSync, boolean returnedChunkLoaded) {
        ClassLoader loader = AnchorChunkPreloadCoreBehaviorTest.class.getClassLoader();
        Chunk chunk = (Chunk) Proxy.newProxyInstance(loader, new Class<?>[] {Chunk.class}, (proxy, method, args) -> {
            switch (method.getName()) {
                case "isLoaded":
                    return returnedChunkLoaded;
                case "load":
                    // 注意 Bukkit 的 Chunk#load() 返回 boolean（不是 void），不能返回 null。
                    recorder.loads.add("load");
                    return true;
                case "toString":
                    return "fakeChunk";
                case "hashCode":
                    return System.identityHashCode(proxy);
                case "equals":
                    return proxy == args[0];
                default:
                    return defaultValue(method.getReturnType());
            }
        });
        return (World) Proxy.newProxyInstance(loader, new Class<?>[] {World.class}, (proxy, method, args) -> {
            switch (method.getName()) {
                case "getName":
                    return "fake-world";
                case "isChunkLoaded":
                    return loadedChunks.contains(pack((Integer) args[0], (Integer) args[1]));
                case "getChunkAt":
                    if (forbidSync) {
                        throw new AssertionError(
                            "区域化核心不得同步拉区块：getChunkAt(" + args[0] + ", " + args[1] + ")");
                    }
                    recorder.syncFetches.add(args[0] + ":" + args[1]);
                    return chunk;
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
