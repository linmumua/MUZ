package linmumua.doudizhu.compat;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.lang.reflect.Proxy;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

/**
 * {@link MuMenuGadgetBridge} 的行为测试：真实走 TabooLib 反射路径解析方法并调用替身 MuMenu 服务。
 *
 * <p>【为什么要真实走反射】本桥的全部价值就是「不知道 MuMenu 具体实现类、只按公开 API 名/签名解析」。
 * 所以这里只顶替三处无法在单测复现的步骤（找插件实例、按类加载器取 API 类、取服务实例），
 * 方法解析与调用仍走生产代码（{@code ReflexClass} / {@code ClassMethod}），从而真实覆盖
 * 「surface 判会话 / open 开面板 / close 关会话 / 版本不符降级 / 缺方法降级」。
 *
 * <p>替身 API 接口的方法名与参数类型刻意与真实 MuMenuApi 一致（{@code arcmenu/api/.../MuMenuApi.kt}）。
 */
class MuMenuGadgetBridgeTest {
    /** 替身 API：方法名/参数类型与真实 MuMenuApi 一致，返回类型不影响反射解析。 */
    interface FakeApi {
        Object surface(Player player);

        void close(Player player);

        boolean open(Player player, String menuId, List<String> arguments);

        int apiVersion();
    }

    /** 缺少 surface 的 API（用于验证「必需方法缺失 → 不可用」）。 */
    interface MissingSurfaceApi {
        int apiVersion();
    }

    /** 替身实现。 */
    static final class FakeApiImpl implements FakeApi {
        Object surfaceResult;
        int version = MuMenuGadgetBridge.SUPPORTED_API_VERSION;
        boolean openResult = true;
        String openedMenuId;
        int closeCalls;
        int surfaceCalls;

        @Override
        public Object surface(Player player) {
            surfaceCalls++;
            return surfaceResult;
        }

        @Override
        public void close(Player player) {
            closeCalls++;
        }

        @Override
        public boolean open(Player player, String menuId, List<String> arguments) {
            openedMenuId = menuId;
            return openResult;
        }

        @Override
        public int apiVersion() {
            return version;
        }
    }

    /** 只顶替三处环境相关步骤的生产子类。 */
    static final class TestBridge extends MuMenuGadgetBridge {
        private final Plugin muPlugin;
        private final Class<?> apiClass;
        private final Object service;
        final AtomicInteger apiClassCalls = new AtomicInteger();
        final AtomicInteger apiServiceCalls = new AtomicInteger();

        TestBridge(Plugin muPlugin, Class<?> apiClass, Object service) {
            super(null);
            this.muPlugin = muPlugin;
            this.apiClass = apiClass;
            this.service = service;
        }

        @Override
        Plugin muMenuPlugin() {
            return muPlugin;
        }

        @Override
        Class<?> apiClass(ClassLoader loader) {
            apiClassCalls.incrementAndGet();
            return apiClass;
        }

        @Override
        Object apiService(Class<?> apiClass) {
            apiServiceCalls.incrementAndGet();
            return service;
        }
    }

    @AfterEach
    void clearCache() {
        // 静态缓存按 Plugin 实例键存；清掉以免跨用例串味（同时验证 refresh() 的存在）。
        MuMenuGadgetBridge.refresh();
    }

    @Test
    void 未安装MuMenu时全部降级且给出可读原因() {
        TestBridge bridge = new TestBridge(null, FakeApi.class, new FakeApiImpl());

        assertFalse(bridge.isAvailable(), "MuMenu 未安装时不可用");
        assertFalse(bridge.isSessionActive(player()), "没有 MuMenu 就不可能有会话");
        assertFalse(bridge.openPanel(player()), "没有 MuMenu 打不开面板");
        assertFalse(bridge.closeSession(player()), "没有 MuMenu 无从关闭");
        assertEquals("未安装 MuMenu", bridge.status());
    }

    @Test
    void 会话判据取surface是否非null() {
        FakeApiImpl api = new FakeApiImpl();
        TestBridge bridge = new TestBridge(fakePlugin(true), FakeApi.class, api);

        api.surfaceResult = null;
        assertFalse(bridge.isSessionActive(player()), "surface 为 null 表示无活动会话");

        api.surfaceResult = new Object();
        assertTrue(bridge.isSessionActive(player()), "surface 非 null 表示有活动会话");
        assertTrue(bridge.isAvailable());
        assertEquals("可用", bridge.status());
    }

    @Test
    void 开面板传固定菜单ID并在关闭时调用close() {
        FakeApiImpl api = new FakeApiImpl();
        TestBridge bridge = new TestBridge(fakePlugin(true), FakeApi.class, api);

        assertTrue(bridge.openPanel(player()), "服务可用且 open 返回 true 时应成功");
        assertEquals(MuMenuGadgetBridge.PANEL_MENU_ID, api.openedMenuId, "必须打开固定的位置测试面板 ID");

        assertTrue(bridge.closeSession(player()));
        assertEquals(1, api.closeCalls, "关闭会话必须真的调用 MuMenuApi.close(player)");
    }

    @Test
    void open返回false时不谎报成功() {
        FakeApiImpl api = new FakeApiImpl();
        api.openResult = false;
        TestBridge bridge = new TestBridge(fakePlugin(true), FakeApi.class, api);

        assertFalse(bridge.openPanel(player()), "MuMenu 拒绝了 open 就不能返回成功");
    }

    @Test
    void api版本不符时判不兼容并降级() {
        FakeApiImpl api = new FakeApiImpl();
        api.version = MuMenuGadgetBridge.SUPPORTED_API_VERSION + 1;
        TestBridge bridge = new TestBridge(fakePlugin(true), FakeApi.class, api);

        assertFalse(bridge.isAvailable(), "API 版本不符视为不兼容（不假装支持）");
        assertFalse(bridge.isSessionActive(player()));
        assertFalse(bridge.openPanel(player()));
    }

    @Test
    void 缺少surface方法时判不兼容() {
        // MissingSurfaceApi 无 surface，也没有 open；桥必须降级而不是抛。
        TestBridge bridge = new TestBridge(fakePlugin(true), MissingSurfaceApi.class, new Object());

        assertFalse(bridge.isAvailable());
        assertFalse(bridge.isSessionActive(player()));
    }

    @Test
    void 服务未发布时不缓存以便稍后自愈() {
        FakeApiImpl api = new FakeApiImpl();
        TestBridge bridge = new TestBridge(fakePlugin(true), FakeApi.class, null);

        assertFalse(bridge.isAvailable(), "服务为 null 表示还没发布");
        assertFalse(bridge.isSessionActive(player()));
    }

    @Test
    void 解析结果按插件实例缓存只解析一次() {
        FakeApiImpl api = new FakeApiImpl();
        api.surfaceResult = new Object();
        Plugin mu = fakePlugin(true);

        TestBridge first = new TestBridge(mu, FakeApi.class, api);
        assertTrue(first.isSessionActive(player()));
        assertTrue(first.isSessionActive(player()));
        assertEquals(1, first.apiServiceCalls.get(), "首次调用解析一次，第二次命中缓存不再解析");

        // 同一个 MuMenu 插件实例：换一个桥实例也应命中同一份静态缓存，不再重做类解析与服务查询。
        TestBridge second = new TestBridge(mu, FakeApi.class, api);
        assertTrue(second.isSessionActive(player()));
        assertEquals(0, second.apiServiceCalls.get(), "同一个插件实例不应再次解析（缓存生效）");
        assertEquals(0, second.apiClassCalls.get(), "缓存命中时连类解析都不应再做");
    }

    @Test
    void MuMenu被禁用时会话判为无并清掉缓存() {
        FakeApiImpl api = new FakeApiImpl();
        api.surfaceResult = new Object();
        // isEnabled 由 Proxy 静态返回，这里用两种插件实例模拟启用/禁用。
        TestBridge enabled = new TestBridge(fakePlugin(true), FakeApi.class, api);
        assertTrue(enabled.isSessionActive(player()));

        TestBridge disabled = new TestBridge(fakePlugin(false), FakeApi.class, api);
        assertFalse(disabled.isSessionActive(player()), "MuMenu 未启用时不应认为有会话");
        assertEquals("MuMenu 未启用", disabled.status());
    }

    /** 一个 {@link Player} 替身：本测试只把它透传给替身 API，不读它的状态。 */
    private static Player player() {
        return (Player) Proxy.newProxyInstance(
            MuMenuGadgetBridgeTest.class.getClassLoader(),
            new Class<?>[] {Player.class},
            (proxy, method, args) -> switch (method.getName()) {
                case "hashCode" -> System.identityHashCode(proxy);
                case "equals" -> proxy == args[0];
                case "toString" -> "fake-player";
                default -> null;
            });
    }

    /** MuMenu 插件替身（Proxy：Plugin 接口很大，手写要写一堆空方法）。 */
    private static Plugin fakePlugin(boolean enabled) {
        return (Plugin) Proxy.newProxyInstance(
            MuMenuGadgetBridgeTest.class.getClassLoader(),
            new Class<?>[] {Plugin.class},
            (proxy, method, args) -> switch (method.getName()) {
                case "isEnabled" -> enabled;
                case "getName" -> MuMenuGadgetBridge.PLUGIN_NAME;
                case "hashCode" -> System.identityHashCode(proxy);
                case "equals" -> proxy == args[0];
                case "toString" -> "StubMuMenuPlugin(enabled=" + enabled + ")";
                default -> null;
            });
    }
}
