package linmumua.doudizhu.world;

import io.papermc.paper.ServerBuildInfo;
import java.util.NoSuchElementException;
import java.util.function.Consumer;
import net.kyori.adventure.key.Key;

/**
 * 判定当前服务端是否为「区域化核心」（Folia 及其区域线程分支）。
 *
 * <p><b>为什么单独做一次判定</b>：牌桌的「锚点区块就绪」在两类核心上合法且正确的实现不同——
 * Paper/Leaf 的主线程允许同步加载区块，牌桌放置/重建路径依赖「执行前锚点邻域已加载」
 * （区块未加载时实体与方块操作静默失效，会把旧实体清掉却建不回来，整桌桌椅按钮凭空消失）；
 * 而 Folia 在 <b>任何</b>上下文（主线程与 region 线程）都禁止同步区块获取，抛
 * {@code IllegalArgumentException("Async chunk retrieval")}，只能走异步加载 + region 投递。
 * 判定错向「非区域化」会让未知核心去执行同步世界写入（在 Folia 上直接失败并每 5 秒刷屏）；
 * 判定错向「区域化」会让 Paper/Leaf 丢掉上面的保证。两个方向都必须尽量判准。
 *
 * <p><b>判定顺序</b>
 * <ol>
 *   <li>官方品牌接口：{@code ServerBuildInfo.buildInfo().isBrandCompatible(Key.key("papermc","folia"))}。
 *       2026-09-23 反编译现场两份内核确认了判别力：
 *       <ul>
 *         <li>Folia（{@code folia-26.1.2.jar}）的 {@code isBrandCompatible} 只做
 *             {@code key.equals(brandId)}，而其 brandId 默认就是 {@code papermc:folia}，
 *             故对 {@code papermc:folia} 返回 true。</li>
 *         <li>Leaf（{@code leaf-26.1.2.jar}）的 {@code isBrandCompatible} 认
 *             leaf / paper / gale / pufferfish / purpur，<b>不含 folia</b>，
 *             故对 {@code papermc:folia} 返回 false。</li>
 *       </ul>
 *       这正是需要的判别力，且比裸反射更少臆断。</li>
 *   <li>品牌接口不可用时回退到类存在性，但<b>只认</b> Folia 真正的核心入口
 *       {@code io.papermc.paper.threadedregions.RegionizedServer}。<b>绝不认</b>
 *       {@code io.papermc.paper.threadedregions.TickRegions}：现场 {@code leaf-26.1.2.jar}
 *       只打了一个 484 字节的 {@code TickRegions} 兼容桩、却<b>没有</b> {@code RegionizedServer}，
 *       用 TickRegions 判定会把 Leaf 误判成 Folia，导致 Leaf 的同步恢复/预热分支永不执行。</li>
 * </ol>
 *
 * <p><b>失败策略</b>：
 * <ul>
 *   <li>品牌 provider 缺失（单元测试环境没有 {@code META-INF/services} 实现，
 *       {@code buildInfo()} 抛 {@link NoSuchElementException}）属于预期内的降级点，静默回退到类探测；</li>
 *   <li>运行期品牌接口抛其它异常属非预期，记录后仍回退；</li>
 *   <li>类探测本身抛 {@link LinkageError}（类存在但无法链接）时<b>不得</b>静默当作非区域化——
 *       那会让未知核心走同步世界写入。此时保守判为区域化（异步路径在 Paper/Leaf 上同样合法）
 *       并告警，交由排查。</li>
 * </ul>
 */
final class RegionizedCoreDetector {
    /** Folia 的官方品牌 key；只有 Folia 的 {@code isBrandCompatible} 对它返回 true。 */
    static final Key FOLIA_BRAND = Key.key("papermc", "folia");

    /**
     * 类加载回退唯一认的标记类。
     *
     * <p><b>不要加入 {@code io.papermc.paper.threadedregions.TickRegions}</b>：现场
     * {@code leaf-26.1.2.jar} 打包了它的 484 字节兼容桩，加了就会把 Leaf 误判成 Folia，
     * 导致 Leaf 走异步恢复、同步预热分支永不执行。
     */
    static final String REGIONIZED_FALLBACK_CLASS =
        "io.papermc.paper.threadedregions.RegionizedServer";

    /** 品牌探测接缝：返回 true 表示当前核心就是 Folia；不可用时抛异常表示「无法判定」。 */
    @FunctionalInterface
    interface BrandProbe {
        boolean isFolia();
    }

    /** 类探测接缝：返回 true 表示指定类可被服务端类加载器解析（不初始化）。 */
    @FunctionalInterface
    interface ClassProbe {
        boolean present(String className) throws ClassNotFoundException;
    }

    private RegionizedCoreDetector() {
    }

    /**
     * 纯判定逻辑，两个接缝与告警出口全部由调用方注入，便于以伪造核心行为做真实 Java 行为测试。
     *
     * @param brandProbe 品牌探测；抛 {@link NoSuchElementException} 表示 provider 缺失，抛其它异常表示运行期异常
     * @param classProbe 类探测；抛 {@link ClassNotFoundException} 表示类不存在，抛 {@link LinkageError} 表示无法链接
     * @param warn       回退与异常路径的告警出口；调用方负责落到服务器日志
     * @return 是否为区域化核心
     */
    static boolean isRegionized(BrandProbe brandProbe, ClassProbe classProbe, Consumer<String> warn) {
        try {
            return brandProbe.isFolia();
        } catch (NoSuchElementException providerMissing) {
            // 单测环境没有 ServerBuildInfo 的 ServiceLoader provider：预期内的降级点，静默回退到类探测。
        } catch (RuntimeException | LinkageError unexpected) {
            // 运行期品牌接口异常属非预期：记录后仍回退，不静默吞掉。
            warn.accept("读取服务端品牌失败（" + unexpected + "），回退到类存在性判定");
        }
        try {
            return classProbe.present(REGIONIZED_FALLBACK_CLASS);
        } catch (ClassNotFoundException absent) {
            return false;
        } catch (LinkageError broken) {
            // 类存在但无法链接：绝不静默当作非区域化，否则未知核心会执行同步世界写入。
            warn.accept("无法确定服务端核心类型（" + REGIONIZED_FALLBACK_CLASS
                + " 链接失败：" + broken + "），保守按区域化核心处理；若并非 Folia，请反馈核心信息");
            return true;
        }
    }

    /**
     * 生产接缝：品牌走官方 {@link ServerBuildInfo}（不裸反射），类存在性走 {@code Class.forName}。
     *
     * <p>品牌 provider 缺失时会静默回退（见 {@link #isRegionized}），因此本方法在单元测试类路径上
     * 也能安全求值（返回 false），不会因缺少 provider 而抛出。
     */
    static boolean isRegionized() {
        return isRegionized(
            () -> ServerBuildInfo.buildInfo().isBrandCompatible(FOLIA_BRAND),
            className -> {
                Class.forName(className, false, RegionizedCoreDetector.class.getClassLoader());
                return true;
            },
            message -> org.bukkit.Bukkit.getLogger().warning("[MUZ] " + message));
    }
}
