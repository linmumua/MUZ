package linmumua.doudizhu.compat;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.lang.reflect.Field;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.logging.Handler;
import java.util.logging.Level;
import java.util.logging.LogRecord;
import java.util.logging.Logger;
import linmumua.doudizhu.DoudizhuPlugin;
import org.bukkit.Location;
import org.bukkit.Server;
import org.bukkit.entity.Entity;
import org.bukkit.plugin.Plugin;
import org.bukkit.plugin.PluginManager;
import org.junit.jupiter.api.Test;
import sun.misc.Unsafe;

/**
 * CraftEngine 家具桥的反射兼容性：**父类/接口声明的方法必须能找到**，**可选 API 缺失必须降级而不是让插件启动失败**。
 *
 * <p>【真实缺陷】实测 CE 26.8（`javap` 核对运行期 jar）：
 * <pre>
 * net.momirealms.craftengine.bukkit.entity.furniture.BukkitFurnitureManager
 *     extends net.momirealms.craftengine.core.entity.furniture.AbstractFurnitureManager
 *     implements net.momirealms.craftengine.core.entity.furniture.FurnitureManager
 * // furnitureById(Key)【不】声明在 BukkitFurnitureManager 上，而在父类与接口上
 * </pre>
 * 而本服务的查找入口原先传的是 {@code getMethodByTypes(name, false, false, …)}——第二个布尔量是「是否在父类/
 * 接口里继续找」，传 false 等于只认【该类自己声明】的方法（旧 {@code Class.getMethod} 会搜父类，所以这是一处
 * 行为回归）。于是解析抛 {@code NoSuchMethodException}；更糟的是它是【受检异常】但在 Kotlin 的
 * ReflexClass 字节码里没有 throws 子句，javac 认为它不抛受检异常，直接 catch 会编译不过——它因此从
 * {@code bridge()} 既有的 {@code catch (ClassNotFoundException | RuntimeException | LinkageError)}
 * 旁边溜过去，一路冒到 {@code onEnable}，表现为「CE 存在时插件加载失败」。
 *
 * <p>本类用 {@link CraftEngineFurnitureService#ceClass(String, ClassLoader)}（包内可见的取类入口，与
 * {@code CraftEngineOffsetService#fontManagerClass} 同源同理）+ 形如真实 CE 的替身类，直接驱动**生产**
 * {@code bridge()} / {@code detectPlacementKind}，钉四条语义：
 * <ol>
 *   <li>父类（子类未声明）与接口默认方法声明的 {@code furnitureById} 都必须能解析，且真的被调用到；</li>
 *   <li>可选 API（{@code furnitureById}）彻底缺失时只降级牌型探测、留下期望/实际签名，整条家具桥仍可用；</li>
 *   <li>必需 API 缺失时整条桥降级、**不抛异常**（插件仍可启动），并留下期望/实际签名；</li>
 *   <li>缺方法被转成运行时异常（而不是会溜走的受检 {@code NoSuchMethodException}）。</li>
 * </ol>
 *
 * <p>为什么可以这么测：{@code Class.forName} 那一步（CE 在另一个类加载器里）没法在单测里复现——替身插件
 * 提供不了自定义类加载器——所以把取类收口成一个包内可见入口并在测试里顶替它；方法解析、诊断与降级全部仍走
 * 生产代码。夹具风格沿用 {@code CraftEngineOffsetDiagnosticsTest} / {@code CraftEngineFurnitureRegionGateTest}。
 */
class CraftEngineFurnitureReflectionCompatibilityTest {
    private static final Path SERVICE = Path.of(
        "src/main/java/linmumua/doudizhu/compat/CraftEngineFurnitureService.java");

    private static final String KEY = "net.momirealms.craftengine.core.util.Key";
    private static final String FURNITURE_API = "net.momirealms.craftengine.bukkit.api.CraftEngineFurniture";
    private static final String MANAGER = "net.momirealms.craftengine.bukkit.entity.furniture.BukkitFurnitureManager";
    private static final String ITEM_MANAGER = "net.momirealms.craftengine.bukkit.item.BukkitItemManager";
    private static final String ITEM = "net.momirealms.craftengine.core.item.Item";
    private static final String BLOCK_STATE_PARSER = "net.momirealms.craftengine.core.block.parser.BlockStateParser";
    private static final String IMMUTABLE_BLOCK_STATE = "net.momirealms.craftengine.core.block.ImmutableBlockState";
    private static final String BLOCKS_API = "net.momirealms.craftengine.bukkit.api.CraftEngineBlocks";
    private static final String FURNITURE = "net.momirealms.craftengine.core.entity.furniture.Furniture";
    private static final String SNAPSHOT = "net.momirealms.craftengine.core.entity.furniture.FurnitureSnapshotState";
    private static final String CE_PLAYER = "net.momirealms.craftengine.core.entity.player.Player";
    private static final String NETWORK_MANAGER = "net.momirealms.craftengine.bukkit.plugin.network.BukkitNetworkManager";

    // ---------------------------------------------------------------- 行为测试

    /**
     * 26.8 的真实形状：{@code furnitureById} 声明在**父类**上，子类自己没声明。
     *
     * <p>失败条件（旧实现）：查找只认本类声明 → 抛 NoSuchMethodException → 整条家具桥被误判为不可用
     * （{@code isAvailable()} 为 false、牌型探测恒为 UNKNOWN），实服上表现为「CE 装了但家具相关功能全没了」。
     */
    @Test
    void 父类声明的家具API在子类未声明时也能解析() throws Exception {
        Fixture fixture = new Fixture(fixtures());

        assertTrue(fixture.service.isAvailable(),
            "furnitureById 声明在父类 AbstractFurnitureManager 上；查找必须沿继承链，"
                + "否则整条家具桥会被误判为不可用。日志：" + fixture.logs.messages);
        assertEquals(CraftEngineFurnitureService.PlacementKind.FURNITURE,
            fixture.service.detectPlacementKind("muz:table"),
            "父类声明的 furnitureById 必须真的被调用到（替身返回 Optional.of 即判定为家具）。"
                + "日志：" + fixture.logs.messages);
    }

    /**
     * 接口默认方法声明的 {@code furnitureById}（实现类自己不再声明）同样必须能找到。
     *
     * <p>为什么单列一条：{@code Class.getMethod} 的语义是「先本类、再父类、再接口」；只补父类查找会在
     * CE 把 API 挪到接口时再次复发。这条用例的实现类只声明 {@code instance()}，
     * {@code furnitureById} 仅存在于接口的 default 方法上。
     */
    @Test
    void 接口默认方法声明的家具API也能解析() throws Exception {
        Map<String, Class<?>> fixtures = fixtures();
        fixtures.put(MANAGER, InterfaceOnlyFurnitureManagerFixture.class);
        Fixture fixture = new Fixture(fixtures);

        assertTrue(fixture.service.isAvailable(),
            "接口上声明的 CE API 必须同样能被解析到。日志：" + fixture.logs.messages);
        assertEquals(CraftEngineFurnitureService.PlacementKind.FURNITURE,
            fixture.service.detectPlacementKind("muz:table"),
            "接口 default 方法声明的 furnitureById 必须真的被调用到。日志：" + fixture.logs.messages);
    }

    /**
     * 可选 API（{@code furnitureById}）**彻底缺失**时：只降级牌型探测、留下期望与实际签名，整条桥仍可用。
     *
     * <p>为什么是「只降级」而不是「整条桥不可用」：{@code detectPlacementKind} 本来就按字段为 null 降级，
     * 放置/清理/自定义物品解析都不依赖它；一个可选 API 的形状变化不该连带关掉家具放置。
     *
     * <p>失败条件：缺失被当成必需 API 处理（整条桥 unavailable），或缺失无声无息（实服只看到家具被当成
     * 未知物品，日志里查不出是哪个 API 变了、变成了什么）。
     */
    @Test
    void 可选家具API彻底缺失时只降级牌型探测并留下期望与实际签名() throws Exception {
        Map<String, Class<?>> fixtures = fixtures();
        fixtures.put(MANAGER, EmptyFurnitureManagerFixture.class);
        Fixture fixture = new Fixture(fixtures);

        assertTrue(fixture.service.isAvailable(),
            "furnitureById 缺失只该降级牌型探测，不该把整条家具桥（放置/清理）一起关掉。"
                + "日志：" + fixture.logs.messages);
        assertEquals(CraftEngineFurnitureService.PlacementKind.UNKNOWN,
            fixture.service.detectPlacementKind("muz:table"),
            "拿不到 furnitureById 时必须降级为未知，而不是抛异常或假装是家具");

        String warning = fixture.logs.onlyContaining("furnitureById");
        assertNotNull(warning, "可选 API 缺失不能无声，必须留下告警。实际日志：" + fixture.logs.messages);
        assertTrue(warning.contains("furnitureById") && warning.contains("(Key)"),
            "告警必须点名期望的方法与参数类型（furnitureById(Key)），实际：" + warning);
        assertTrue(warning.contains("实际同名方法：无"),
            "没有同名方法时清单段必须写明『无』而不是留空/省略，实际：" + warning);
        assertTrue(warning.contains("实际声明的方法：") && warning.contains("instance()"),
            "必须带出该类实际声明的方法表，否则维护者看不出 CE 到底提供了什么，实际：" + warning);
    }

    /**
     * 必需 API 缺失时：整条家具桥降级、**不抛异常**（MUZ 仍可启动），并留下期望与实际签名。
     *
     * <p>失败条件（旧实现）：受检的 {@code NoSuchMethodException} 从 {@code bridge()} 的 catch 子句旁溜到
     * {@code onEnable}，插件直接加载失败——这正是实服「CE 装了但 MUZ 起不来」的形态。
     */
    @Test
    void 必需API缺失时整条家具桥降级且不抛出并留下期望与实际签名() throws Exception {
        Map<String, Class<?>> fixtures = fixtures();
        fixtures.put(KEY, KeyWithoutOf.class);
        Fixture fixture = new Fixture(fixtures);

        boolean available = assertDoesNotThrow(() -> fixture.service.isAvailable(),
            "缺 API 必须降级而不是抛异常——异常逃出 onEnable 会让插件加载失败。日志：" + fixture.logs.messages);
        assertFalse(available, "必需 API 缺失时家具桥必须报告不可用");

        String warning = fixture.logs.onlyContaining("could not initialize");
        assertNotNull(warning, "桥初始化失败必须留下告警。实际日志：" + fixture.logs.messages);
        assertTrue(warning.contains("CraftEngine API 缺失："),
            "告警必须点名是「CE 缺了这个 API」，而不是通用的初始化失败，实际：" + warning);
        assertTrue(warning.contains("of(String)"),
            "必须写出我们期望的签名（Key#of(String)），实际：" + warning);
        assertTrue(warning.contains("实际同名方法：无") && warning.contains("实际声明的方法："),
            "必须同时给出该类实际有什么（同名清单 + 方法表），实际：" + warning);
    }

    /**
     * hitbox 可见性桥缺方法：只降级 hitbox（{@code hitboxVisibilityUnavailable}），**不关掉家具桥**，
     * 并留下签名清单。
     *
     * <p>为什么单列一条：{@code initializeHitboxVisibilityBridge} 有自己的一套查找（snapshot 的
     * hide/showHitboxes 等），它缺方法时同样不能让受检异常逃出 {@code onEnable}；但它只是可选能力，
     * 降级范围必须限制在 hitbox 可见性上。
     */
    @Test
    void hitbox桥缺方法只降级hitbox而不关掉家具桥并留下签名清单() throws Exception {
        Map<String, Class<?>> fixtures = fixtures();
        fixtures.put(SNAPSHOT, FurnitureSnapshotStateWithoutHide.class);
        Fixture fixture = new Fixture(fixtures);

        assertTrue(fixture.service.isAvailable(),
            "hitbox 桥缺方法只该关掉 hitbox 可见性，不该让整条家具桥（放置/清理）不可用。"
                + "日志：" + fixture.logs.messages);
        String warning = fixture.logs.onlyContaining("hitbox visibility bridge unavailable");
        assertNotNull(warning, "hitbox 桥降级必须留痕。实际日志：" + fixture.logs.messages);
        assertTrue(warning.contains("hideHitboxes(Player)"),
            "必须点名期望的签名（含 CE 的 Player 形参），实际：" + warning);
        assertTrue(warning.contains("showHitboxes(Player)"),
            "必须带出该类实际声明的方法表——CE 改名时只能靠它看出新名字，实际：" + warning);
    }

    /**
     * 缺方法的失败必须被转成**运行时异常**，不能是受检的 {@code NoSuchMethodException}。
     *
     * <p>这是「不逃出 onEnable」的机制本身：{@code bridge()} 与 hitbox 桥的 catch 子句都只列
     * {@code RuntimeException}/{@code ClassNotFoundException}/{@code LinkageError}，受检异常会从旁边溜走。
     * 由于 ReflexClass 是 Kotlin 类（字节码无 throws 子句），javac 没法直接 catch 它，所以只能靠
     * {@code instanceof} 判定后统一转换——这条用例把这个转换钉住。
     */
    @Test
    void 缺方法被转成运行时异常而不是受检的NoSuchMethodException() throws Exception {
        Method methodByTypes = CraftEngineFurnitureService.class.getDeclaredMethod(
            "methodByTypes", Class.class, String.class, Class[].class);
        methodByTypes.setAccessible(true);

        InvocationTargetException thrown = assertThrows(InvocationTargetException.class,
            () -> methodByTypes.invoke(null, EmptyFurnitureManagerFixture.class, "furnitureById",
                new Class<?>[] {Key.class}),
            "缺方法必须抛异常（调用点靠它降级），这里验证抛的是哪一种");

        Throwable failure = thrown.getCause();
        assertInstanceOf(IllegalStateException.class, failure,
            "缺方法必须转成运行时异常，供调用点既有的 catch (RuntimeException) 兜住");
        assertFalse(failure instanceof NoSuchMethodException,
            "受检的 NoSuchMethodException 会从 bridge() 的 catch 子句旁溜到 onEnable，必须被转换掉");
        assertNotNull(failure.getMessage(), "转换后的异常必须带可排查的签名清单，不能是 null");
        assertTrue(failure.getMessage().contains("furnitureById(Key)"),
            "消息必须点名期望签名，实际：" + failure.getMessage());
        assertTrue(failure.getMessage().contains("实际同名方法：无"),
            "消息必须写明实际有哪些同名方法（没有就写『无』），实际：" + failure.getMessage());
        assertTrue(failure.getMessage().contains("实际声明的方法："),
            "消息必须带出该类实际声明的方法表，实际：" + failure.getMessage());
    }

    // ---------------------------------------------------------------- 源码契约

    /**
     * 源码契约：查找必须沿继承链（第二个布尔量为 true），且反射一律走 TabooLib，不得引入裸反射 API。
     *
     * <p>为什么在行为测试之外还要一条源码契约：上面几条行为用例只覆盖「父类 / 接口 / 可选缺失 / 必需缺失」
     * 这几个具体形状，门禁本身若被改回 {@code false} 而夹具恰好同层声明，行为用例不会全部变红；这条把
     * 「必须沿继承链」与「不引入裸反射」两条硬约束直接钉在源码上。
     */
    @Test
    void 源码契约_查找沿继承链且不引入裸反射API() throws IOException {
        String source = Files.readString(SERVICE);

        assertTrue(source.contains("getMethodByTypes(name, true, false, parameterTypes)"),
            "必须在父类/接口里继续找（第二个布尔量 true），否则父类声明的 CE API 会被误判为缺失");
        assertTrue(source.contains("getMethodByTypeSilently(name, true, false, parameterTypes)"),
            "静默入口同样要沿继承链查找，否则可选 API 会被误判为缺失");

        // 只查 import：类注释里会以文字提到历史写法（「从 java.lang.reflect.Method 换成 ClassMethod」），
        // 直接扫全文会把注释误判成违规。
        for (String bare : List.of("java.lang.reflect.Method", "java.lang.reflect.Field",
            "java.lang.reflect.Constructor")) {
            assertFalse(source.contains("import " + bare + ";"),
                "反射必须走 TabooLib 的 reflex 工具，不得引入裸反射 API：" + bare);
        }
    }

    // ---------------------------------------------------------------- 夹具

    /** 生产环境里 CE 的 12 个类名 → 替身类；测试按场景替换其中一两个来驱动不同形状。 */
    private static Map<String, Class<?>> fixtures() {
        Map<String, Class<?>> map = new LinkedHashMap<>();
        map.put(KEY, Key.class);
        map.put(FURNITURE_API, CraftEngineFurnitureApi.class);
        map.put(MANAGER, BukkitFurnitureManagerFixture.class);
        map.put(ITEM_MANAGER, BukkitItemManager.class);
        map.put(ITEM, Item.class);
        map.put(BLOCK_STATE_PARSER, BlockStateParser.class);
        map.put(IMMUTABLE_BLOCK_STATE, ImmutableBlockState.class);
        map.put(BLOCKS_API, CraftEngineBlocks.class);
        map.put(FURNITURE, Furniture.class);
        map.put(SNAPSHOT, FurnitureSnapshotState.class);
        map.put(CE_PLAYER, Player.class);
        map.put(NETWORK_MANAGER, BukkitNetworkManager.class);
        return map;
    }

    /** 模拟 {@code net.momirealms.craftengine.core.util.Key}。 */
    public static final class Key {
        public static Key of(String value) {
            return new Key();
        }

        public String asString() {
            return "muz:table";
        }
    }

    /** 模拟「没有 of(String) 的 Key」，用来驱动「必需 API 缺失 → 整条桥降级」。 */
    public static final class KeyWithoutOf {
        public String asString() {
            return "muz:table";
        }
    }

    /** 模拟 {@code core.item.Item} 的四个查询方法。 */
    public static final class Item {
        public boolean isCustomItem() {
            return true;
        }

        public boolean isBlockItem() {
            return false;
        }

        public Optional<Key> customId() {
            return Optional.empty();
        }

        public Key id() {
            return new Key();
        }
    }

    /** 模拟 {@code core.block.ImmutableBlockState}：只作为形参类型出现。 */
    public static final class ImmutableBlockState {
    }

    /** 模拟 {@code core.entity.player.Player}：只作为 hitbox 方法的形参类型出现（简单名读作 Player）。 */
    public static final class Player {
    }

    /** 模拟 {@code CraftEngineFurniture} 的静态 API（本测试只解析，不调用）。 */
    public static final class CraftEngineFurnitureApi {
        public static Object place(Location location, Key key) {
            return null;
        }

        public static boolean remove(Entity entity, boolean dropLoot, boolean playSound) {
            return true;
        }

        public static Object getLoadedFurnitureByMetaEntity(Entity entity) {
            return null;
        }

        public static Object getLoadedFurnitureBySeat(Entity entity) {
            return null;
        }

        public static Object getLoadedFurnitureByCollider(Entity entity) {
            return null;
        }
    }

    /** 模拟 {@code BukkitItemManager}。 */
    public static final class BukkitItemManager {
        public static BukkitItemManager instance() {
            return new BukkitItemManager();
        }

        public Item wrap(Object itemStack) {
            return new Item();
        }
    }

    /** 模拟 {@code BlockStateParser.deserialize(String)}。 */
    public static final class BlockStateParser {
        public static ImmutableBlockState deserialize(String blockState) {
            return null;
        }
    }

    /** 模拟 {@code CraftEngineBlocks.place(...)}。 */
    public static final class CraftEngineBlocks {
        public static boolean place(Location location, ImmutableBlockState state, int flags, boolean applyPhysics) {
            return true;
        }
    }

    /** 模拟 {@code core.entity.furniture.Furniture}：{@code snapshotState()} 声明在本类。 */
    public static class Furniture {
        public FurnitureSnapshotState snapshotState() {
            return new FurnitureSnapshotState();
        }
    }

    /** 模拟 {@code FurnitureSnapshotState}：hide/showHitboxes 都声明在本类。 */
    public static class FurnitureSnapshotState {
        public void hideHitboxes(Player player) {
        }

        public void showHitboxes(Player player) {
        }
    }

    /** 模拟「CE 把 hideHitboxes 去掉/改名」的形状：只剩 showHitboxes。 */
    public static class FurnitureSnapshotStateWithoutHide {
        public void showHitboxes(Player player) {
        }
    }

    /** 模拟 {@code BukkitNetworkManager}。 */
    public static final class BukkitNetworkManager {
        public static BukkitNetworkManager instance() {
            return new BukkitNetworkManager();
        }

        public Object getOnlineUser(UUID playerId) {
            return null;
        }
    }

    /** 模拟 26.8 的 {@code AbstractFurnitureManager}：{@code furnitureById(Key)} 声明在**父类**上。 */
    public static class AbstractFurnitureManagerFixture {
        public Optional<Object> furnitureById(Key key) {
            return Optional.of(new Object());
        }
    }

    /** 模拟 26.8 的 {@code BukkitFurnitureManager}：自己**不声明** {@code furnitureById}，只声明 instance()。 */
    public static final class BukkitFurnitureManagerFixture extends AbstractFurnitureManagerFixture {
        public static BukkitFurnitureManagerFixture instance() {
            return new BukkitFurnitureManagerFixture();
        }
    }

    /** 模拟「接口 default 方法声明 furnitureById，实现类自己不再声明」的形状。 */
    public interface FurnitureManagerContract {
        default Optional<Object> furnitureById(Key key) {
            return Optional.of(new Object());
        }
    }

    /** 只声明 instance()，{@code furnitureById} 仅存在于接口 default 方法上。 */
    public static final class InterfaceOnlyFurnitureManagerFixture implements FurnitureManagerContract {
        public static InterfaceOnlyFurnitureManagerFixture instance() {
            return new InterfaceOnlyFurnitureManagerFixture();
        }
    }

    /** 完全没有 {@code furnitureById} 的 manager（CE 若整体重构就会长这样）。 */
    public static final class EmptyFurnitureManagerFixture {
        public static EmptyFurnitureManagerFixture instance() {
            return new EmptyFurnitureManagerFixture();
        }
    }

    /**
     * 装好 CE 替身并驱动生产 {@code bridge()} 的夹具。
     *
     * <p>关键点：只顶替「按名取 CE 类」这一步（生产环境跨类加载器，单测无法复现），
     * 方法解析、诊断与降级全部仍走生产代码。
     */
    private static final class Fixture {
        private final CraftEngineFurnitureService service;
        private final Logs logs;

        private Fixture(Map<String, Class<?>> fixtures) throws Exception {
            this.logs = new Logs();
            DoudizhuPlugin plugin = (DoudizhuPlugin) unsafe().allocateInstance(DoudizhuPlugin.class);
            setField(plugin, "logger", logs.logger());
            // bridge() 经 plugin.getServer() 问插件管理器要 CraftEngine（JavaPlugin 的 server 字段）。
            setField(plugin, "server", fakeServer(fakeCraftEnginePlugin()));
            this.service = new CraftEngineFurnitureService(plugin) {
                @Override
                Class<?> ceClass(String name, ClassLoader loader) throws ClassNotFoundException {
                    Class<?> fixture = fixtures.get(name);
                    if (fixture == null) {
                        throw new ClassNotFoundException(name);
                    }
                    return fixture;
                }
            };
        }
    }

    /**
     * CE 插件的替身（Proxy：{@code Plugin} 接口很大，手写要写一堆空方法）。
     *
     * <p>注意一个真实限制：Proxy 上 {@code getClass()} 不路由到调用处理器，因此替身给不出自定义类加载器
     * ——这正是需要顶替 {@code ceClass(...)} 的原因（与 {@code CraftEngineOffsetDiagnosticsTest} 同一处限制）。
     */
    private static Plugin fakeCraftEnginePlugin() {
        return (Plugin) Proxy.newProxyInstance(
            CraftEngineFurnitureReflectionCompatibilityTest.class.getClassLoader(),
            new Class<?>[] {Plugin.class},
            (proxy, method, args) -> switch (method.getName()) {
                case "isEnabled" -> true;
                case "getName" -> "CraftEngine";
                case "getLogger" -> Logger.getLogger("muz-ce-furniture-stub-plugin");
                case "hashCode" -> System.identityHashCode(proxy);
                case "equals" -> proxy == args[0];
                case "toString" -> "StubCraftEnginePlugin";
                default -> null;
            });
    }

    /** 服务器替身：只满足 {@code getPluginManager().getPlugin("CraftEngine")} 这一次查询。 */
    private static Server fakeServer(Plugin craftEngine) {
        PluginManager manager = (PluginManager) Proxy.newProxyInstance(
            CraftEngineFurnitureReflectionCompatibilityTest.class.getClassLoader(),
            new Class<?>[] {PluginManager.class},
            (proxy, method, args) -> switch (method.getName()) {
                case "getPlugin" ->
                    args != null && args.length == 1 && "CraftEngine".equals(args[0]) ? craftEngine : null;
                case "hashCode" -> System.identityHashCode(proxy);
                case "equals" -> proxy == args[0];
                case "toString" -> "fake-plugin-manager";
                default -> null;
            });
        return (Server) Proxy.newProxyInstance(
            CraftEngineFurnitureReflectionCompatibilityTest.class.getClassLoader(),
            new Class<?>[] {Server.class},
            (proxy, method, args) -> switch (method.getName()) {
                case "getPluginManager" -> manager;
                case "hashCode" -> System.identityHashCode(proxy);
                case "equals" -> proxy == args[0];
                case "toString" -> "fake-server";
                default -> null;
            });
    }

    /** 收集日志文本的 handler（照 {@code CraftEngineOffsetDiagnosticsTest} 的既有夹具）。 */
    private static final class Logs {
        private final List<String> messages = new ArrayList<>();
        private final Logger logger = Logger.getLogger("muz-furniture-reflection-" + UUID.randomUUID());

        private Logs() {
            logger.setUseParentHandlers(false);
            logger.setLevel(Level.ALL);
            logger.addHandler(new Handler() {
                @Override
                public void publish(LogRecord record) {
                    if (record.getMessage() != null) {
                        messages.add(record.getMessage());
                    }
                }

                @Override
                public void flush() {
                }

                @Override
                public void close() {
                }
            });
        }

        private Logger logger() {
            return logger;
        }

        /** 唯一那条含指定片段的日志；没有或多于一条时返回 null，由调用方给出可读的失败信息。 */
        private String onlyContaining(String fragment) {
            List<String> found = new ArrayList<>();
            for (String message : messages) {
                if (message.contains(fragment)) {
                    found.add(message);
                }
            }
            return found.size() == 1 ? found.get(0) : null;
        }
    }

    private static Unsafe unsafe() throws Exception {
        Field field = Unsafe.class.getDeclaredField("theUnsafe");
        field.setAccessible(true);
        return (Unsafe) field.get(null);
    }

    private static void setField(Object target, String name, Object value) throws Exception {
        Class<?> type = target.getClass();
        while (type != null) {
            try {
                Field field = type.getDeclaredField(name);
                field.setAccessible(true);
                field.set(target, value);
                return;
            } catch (NoSuchFieldException ignored) {
                type = type.getSuperclass();
            }
        }
        throw new NoSuchFieldException(name);
    }
}
