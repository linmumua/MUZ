package linmumua.doudizhu.world;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import java.util.NoSuchElementException;
import java.util.Set;
import org.junit.jupiter.api.Test;

/**
 * {@link RegionizedCoreDetector} 的真实行为测试——执行生产检测逻辑，而不是源码 {@code indexOf}。
 *
 * <p>驱动这组测试的现场事实（2026-09-23，对两份内核 ZIP 与 {@code javap} 反编译确认）：
 * <ul>
 *   <li>{@code folia-26.1.2.jar}：{@code RegionizedServer} 与 {@code TickRegions} 都在；
 *       其 {@code isBrandCompatible(k)} 只做 {@code k.equals(brandId)}，brandId 默认 {@code papermc:folia}。</li>
 *   <li>{@code leaf-26.1.2.jar}：只有一个 484 字节的 {@code TickRegions} 兼容桩，<b>没有</b>
 *       {@code RegionizedServer}；其 {@code isBrandCompatible} 认 leaf/paper/gale/pufferfish/purpur，
 *       <b>不含 folia</b>。</li>
 * </ul>
 * 所以「{@code isBrandCompatible(Key.key("papermc","folia"))}」是精确的 Folia 判别器，而
 * 「{@code TickRegions} 是否存在」不是——后者正是被修掉的误判 bug。
 */
class RegionizedCoreDetectionBehaviorTest {
    private static final String REGIONIZED_SERVER_MARKER =
        "io.papermc.paper.threadedregions.RegionizedServer";
    private static final String TICK_REGIONS_STUB =
        "io.papermc.paper.threadedregions.TickRegions";

    /** 现场 {@code leaf-26.1.2.jar} 的真实类布局：TickRegions 兼容桩在，RegionizedServer 不在。 */
    private static final RegionizedCoreDetector.ClassProbe REAL_LEAF = classpath(TICK_REGIONS_STUB);
    /** 现场 {@code folia-26.1.2.jar}：两者都在。 */
    private static final RegionizedCoreDetector.ClassProbe REAL_FOLIA =
        classpath(REGIONIZED_SERVER_MARKER, TICK_REGIONS_STUB);
    /** Paper：两者都不在。 */
    private static final RegionizedCoreDetector.ClassProbe REAL_PAPER = classpath();

    private final List<String> warnings = new ArrayList<>();

    // --- 品牌路径：官方 ServerBuildInfo 的判别力 ---

    @Test
    void foliaBrandIsRegionized() {
        assertTrue(RegionizedCoreDetector.isRegionized(() -> true, REAL_FOLIA, warnings::add),
            "Folia 品牌对 papermc:folia 兼容，必须判为区域化核心");
    }

    @Test
    void leafBrandIsNotRegionized() {
        assertFalse(RegionizedCoreDetector.isRegionized(() -> false, REAL_LEAF, warnings::add),
            "Leaf 品牌对 papermc:folia 不兼容，必须判为非区域化核心");
    }

    @Test
    void paperBrandIsNotRegionized() {
        assertFalse(RegionizedCoreDetector.isRegionized(() -> false, REAL_PAPER, warnings::add),
            "Paper 品牌对 papermc:folia 不兼容，必须判为非区域化核心");
    }

    /**
     * 品牌不是 {@code papermc:folia} 但存在 {@code RegionizedServer} ⇒ 仍是区域化核心。
     *
     * <p>【这是 1.10.65 修掉的真实缺陷，必须有用例钉住】现场 mucore-26.3 是 Folia 派生核心：
     * {@code isBrandCompatible(papermc:folia)} 正常返回 {@code false}（它不是官方 Folia），
     * 而它有完整的 {@code RegionizedServer}。旧实现在品牌返回 false 时**直接短路**，
     * 永不执行类回退，于是把 mucore 判成 Paper/Leaf —— 恢复存档牌桌时在 global lane 同步
     * {@code getChunkAt}，真实抛 {@code Async chunk retrieval} 并每 5 秒刷屏。
     */
    @Test
    void forkedFoliaCoreWithoutFoliaBrandIsStillRegionized() {
        assertTrue(RegionizedCoreDetector.isRegionized(() -> false, REAL_FOLIA, warnings::add),
            "品牌不是 Folia 但有 RegionizedServer 的派生核心必须判为区域化，否则会走同步拉区块分支");
        assertTrue(warnings.isEmpty(), "这条判定是正常路径，不应产生告警");
    }

    /** 反证：同一条品牌为 false 的输入，若没有 RegionizedServer（Paper/Leaf），仍必须是非区域化。 */
    @Test
    void nonFoliaBrandWithoutMarkerClassStaysSingleThreaded() {
        assertFalse(RegionizedCoreDetector.isRegionized(() -> false, REAL_PAPER, warnings::add));
        assertFalse(RegionizedCoreDetector.isRegionized(() -> false, REAL_LEAF, warnings::add),
            "Leaf 只有 TickRegions 兼容桩，仍必须判为非区域化");
    }

    // --- 类加载回退：品牌 provider 缺失（单测环境）时只认 RegionizedServer ---

    @Test
    void leafFallbackWithoutBrandProviderIsNotRegionized() {
        // 关键用例：Leaf 有 TickRegions 兼容桩，但回退只认 RegionizedServer，因此不得被误判。
        assertFalse(RegionizedCoreDetector.isRegionized(providerMissing(), REAL_LEAF, warnings::add),
            "品牌缺失时回退只认 RegionizedServer：Leaf 无该类，绝不能因 TickRegions 兼容桩判为区域化");
    }

    @Test
    void foliaFallbackWithoutBrandProviderIsRegionized() {
        assertTrue(RegionizedCoreDetector.isRegionized(providerMissing(), REAL_FOLIA, warnings::add),
            "品牌缺失时回退认 RegionizedServer 存在，Folia 必须判为区域化");
    }

    @Test
    void paperFallbackWithoutBrandProviderIsNotRegionized() {
        assertFalse(RegionizedCoreDetector.isRegionized(providerMissing(), REAL_PAPER, warnings::add),
            "品牌缺失且无 RegionizedServer 时必须判为非区域化");
    }

    // --- 失败策略 ---

    @Test
    void markerProbeLinkageErrorIsConservativeAndWarns() {
        RegionizedCoreDetector.ClassProbe brokenLinkage = className -> {
            throw new LinkageError("simulated broken class");
        };
        assertTrue(RegionizedCoreDetector.isRegionized(providerMissing(), brokenLinkage, warnings::add),
            "类加载 LinkageError 时不得静默当作非区域化（否则未知核心会走同步世界写入），必须保守判为区域化");
        assertFalse(warnings.isEmpty(), "类加载异常必须出声告警，不能静默吞掉");
    }

    @Test
    void unexpectedBrandFailureWarnsThenFallsBack() {
        RegionizedCoreDetector.BrandProbe brokenBrand = () -> {
            throw new IllegalStateException("simulated brand failure");
        };
        assertFalse(RegionizedCoreDetector.isRegionized(brokenBrand, REAL_PAPER, warnings::add),
            "运行期品牌异常属非预期，记录后仍回退到类探测");
        assertFalse(warnings.isEmpty(), "运行期品牌异常必须记录，不能静默吞掉");
    }

    @Test
    void providerMissingIsSilentFallback() {
        RegionizedCoreDetector.isRegionized(providerMissing(), REAL_PAPER, warnings::add);
        assertTrue(warnings.isEmpty(),
            "品牌 provider 缺失（单测环境常态）属预期降级点，应静默回退而不是刷告警");
    }

    /** 生产无参接缝必须在单测类路径上安全求值（provider 缺失被降级吸收），不得抛出。 */
    @Test
    void productionProbeDoesNotThrowOnTestClasspath() {
        assertDoesNotThrow(() -> {
            RegionizedCoreDetector.isRegionized();
        }, "单测类路径没有 ServerBuildInfo provider，buildInfo() 抛 NoSuchElementException 必须被降级吸收");
    }

    // --- 旧逻辑反例（不修改用户未提交代码；只复刻已删除的判据作为对照） ---

    /**
     * 反例：证明**被删除的旧判据**会误判现场 Leaf。
     *
     * <p>旧实现是 {@code RegionizedServer <b>或</b> TickRegions 任一存在即视为区域化}。这里原样复刻该判据，
     * 用现场 Leaf 的真实类布局（只有 TickRegions 兼容桩）证明它对 Leaf 返回 true，而新实现返回 false。
     * 这不是新算法的副本，而是刻意保留的、用来钉住「为什么必须删掉 TickRegions 判据」的对照。
     */
    @Test
    void removedTickRegionsDetectionMisjudgesRealLeafWhileNewDetectionIsCorrect() {
        // 旧判据对 Leaf 与 Folia 都返回 true —— 它压根区分不了两者，这正是 bug。
        assertTrue(removedTickRegionsBasedDetection(Set.of(TICK_REGIONS_STUB)),
            "旧判据把现场 Leaf（只有 TickRegions 兼容桩）误判为区域化——这就是被修掉的 bug");
        assertTrue(removedTickRegionsBasedDetection(Set.of(REGIONIZED_SERVER_MARKER, TICK_REGIONS_STUB)),
            "旧判据对 Folia 同样返回 true（所以它无法区分 Leaf 与 Folia）");

        // 新实现能区分：Leaf 两条路径都判非区域化，Folia 判区域化。
        assertFalse(RegionizedCoreDetector.isRegionized(() -> false, REAL_LEAF, warnings::add),
            "新实现走品牌：Leaf 对 papermc:folia 不兼容 → 非区域化");
        assertFalse(RegionizedCoreDetector.isRegionized(providerMissing(), REAL_LEAF, warnings::add),
            "新实现走回退：只认 RegionizedServer，Leaf 无该类 → 非区域化");
        assertTrue(RegionizedCoreDetector.isRegionized(() -> true, REAL_FOLIA, warnings::add),
            "新实现走品牌：Folia 对 papermc:folia 兼容 → 区域化");
    }

    /** 复刻已删除的旧判据：RegionizedServer 或 TickRegions 任一存在即视为区域化。 */
    private static boolean removedTickRegionsBasedDetection(Set<String> presentClasses) {
        for (String marker : List.of(REGIONIZED_SERVER_MARKER, TICK_REGIONS_STUB)) {
            if (presentClasses.contains(marker)) {
                return true;
            }
        }
        return false;
    }

    /** 品牌 provider 缺失：{@code ServerBuildInfo.buildInfo()} 在单测类路径上的真实异常。 */
    private static RegionizedCoreDetector.BrandProbe providerMissing() {
        return () -> {
            throw new NoSuchElementException("No value present（模拟缺少 ServerBuildInfo provider）");
        };
    }

    /** 以「哪些类可解析」模拟一个核心的类路径。 */
    private static RegionizedCoreDetector.ClassProbe classpath(String... presentClasses) {
        Set<String> present = Set.of(presentClasses);
        return className -> {
            if (present.contains(className)) {
                return true;
            }
            throw new ClassNotFoundException(className);
        };
    }
}
