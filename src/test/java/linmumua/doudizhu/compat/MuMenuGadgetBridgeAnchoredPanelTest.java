package linmumua.doudizhu.compat;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import linmumua.doudizhu.game.ChairPanelAnchor;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

/**
 * {@link MuMenuGadgetBridge} 世界锚定面板入口的行为测试：真实走 TabooLib 反射解析与构造。
 *
 * <p>【锁的三条业务性质】
 * <ol>
 *   <li><b>旧版必须 fail-closed</b>：能力缺失时 {@code openPanel} 返回 {@code UNSUPPORTED}，
 *       <b>绝不</b>退化成「先开一个再开第二个」——那样第二个会把第一个顶掉（REPLACED），
 *       玩家只看到一块板，与「左右各一块」的需求直接冲突。</li>
 *   <li><b>能力 + 方法双重协商</b>：只有能力没有方法、或只有方法没有能力，都判不可用
 *       （MuMenu 明确要求不能只靠方法存在性判断）。</li>
 *   <li><b>双面板用不同 panelId</b>：两块各自独立，调用参数里必须能区分。</li>
 * </ol>
 *
 * <p>替身 API 的方法名与参数类型刻意与真实 MuMenuApi 一致（{@code arcmenu/api/.../MuMenuApi.kt}）。
 */
class MuMenuGadgetBridgeAnchoredPanelTest {
    /** 替身锚点：构造器形状与真实 MuMenuPanelAnchor 的便捷构造一致（含三轴变体）。 */
    public static final class FakeAnchor {
        final World world;
        final Location origin;
        final float yaw;
        final double width;
        final double height;

        public FakeAnchor(World world, Location origin, float yaw, double width, double height) {
            this.world = world;
            this.origin = origin;
            this.yaw = yaw;
            this.width = width;
            this.height = height;
        }
    }

    /** 支持锚定面板的替身 API：签名与 MuMenu 源码定稿一致（openPanel 首参是 owner Plugin）。 */
    interface AnchoredApi {
        Set<String> capabilities();

        Object surface(Player player);

        boolean open(Player player, String menuId, List<String> arguments);

        int apiVersion();

        boolean openPanel(Plugin owner, Player player, String panelId, String menuId, FakeAnchor anchor);

        boolean updatePanelAnchor(Player player, String panelId, FakeAnchor anchor);

        void closePanel(Player player, String panelId);

        void closePanels(Player player);

        Set<String> panels(Player player);
    }

    /** 声明了能力但缺少面板方法的 API：必须判不可用。 */
    interface CapabilityOnlyApi {
        Set<String> capabilities();

        Object surface(Player player);

        boolean open(Player player, String menuId, List<String> arguments);

        int apiVersion();
    }

    static final class AnchoredApiImpl implements AnchoredApi {
        final List<Plugin> openedOwners = new ArrayList<>();
        final List<String> openedPanels = new ArrayList<>();
        final List<String> openedMenus = new ArrayList<>();
        final List<FakeAnchor> anchors = new ArrayList<>();
        final List<String> updatedPanels = new ArrayList<>();
        final List<String> closedPanels = new ArrayList<>();
        int closePanelsCalls;
        boolean openResult = true;
        boolean updateResult = true;
        Float lastYaw;
        Float lastPitch;
        Float lastRoll;

        @Override
        public Set<String> capabilities() {
            return Set.of("SCREEN_SURFACE", MuMenuGadgetBridge.CAPABILITY_ANCHORED_PANELS);
        }

        @Override
        public Object surface(Player player) {
            return null;
        }

        @Override
        public boolean open(Player player, String menuId, List<String> arguments) {
            return true;
        }

        @Override
        public int apiVersion() {
            return MuMenuGadgetBridge.SUPPORTED_API_VERSION;
        }

        @Override
        public boolean openPanel(Plugin owner, Player player, String panelId, String menuId,
            FakeAnchor anchor) {
            openedOwners.add(owner);
            openedPanels.add(panelId);
            openedMenus.add(menuId);
            anchors.add(anchor);
            lastYaw = anchor.yaw;
            return openResult;
        }

        @Override
        public boolean updatePanelAnchor(Player player, String panelId, FakeAnchor anchor) {
            updatedPanels.add(panelId);
            lastYaw = anchor.yaw;
            return updateResult;
        }

        @Override
        public void closePanel(Player player, String panelId) {
            closedPanels.add(panelId);
        }

        @Override
        public void closePanels(Player player) {
            closePanelsCalls++;
        }

        @Override
        public Set<String> panels(Player player) {
            return Set.copyOf(openedPanels);
        }
    }

    static final class CapabilityOnlyApiImpl implements CapabilityOnlyApi {
        @Override
        public Set<String> capabilities() {
            return Set.of(MuMenuGadgetBridge.CAPABILITY_ANCHORED_PANELS);
        }

        @Override
        public Object surface(Player player) {
            return null;
        }

        @Override
        public boolean open(Player player, String menuId, List<String> arguments) {
            return true;
        }

        @Override
        public int apiVersion() {
            return MuMenuGadgetBridge.SUPPORTED_API_VERSION;
        }
    }

    private static final UUID WORLD_ID = UUID.fromString("00000000-0000-0000-0000-0000000000aa");

    @AfterEach
    void clearCache() {
        MuMenuGadgetBridge.refresh();
    }

    /** 桥替身：顶替三处无法在单测复现的步骤，其余走生产反射逻辑。 */
    private static MuMenuGadgetBridge bridgeFor(Class<?> apiType, Object service) {
        return bridgeFor(apiType, service, fakeOwnerPlugin());
    }

    private static Plugin fakeOwnerPlugin() {
        return (Plugin) java.lang.reflect.Proxy.newProxyInstance(
            Plugin.class.getClassLoader(), new Class<?>[] {Plugin.class},
            (proxy, method, args) -> switch (method.getName()) {
                case "getName" -> "MuMenu";
                case "isEnabled" -> true;
                case "hashCode" -> System.identityHashCode(proxy);
                case "equals" -> proxy == args[0];
                case "toString" -> "MuMenu";
                default -> null;
            });
    }

    private static MuMenuGadgetBridge bridgeFor(Class<?> apiType, Object service, Plugin owner) {
        Plugin fakePlugin = fakeOwnerPlugin();
        World fakeWorld = (World) java.lang.reflect.Proxy.newProxyInstance(
            World.class.getClassLoader(), new Class<?>[] {World.class},
            (proxy, method, args) -> switch (method.getName()) {
                case "getName" -> "world";
                case "hashCode" -> 1;
                case "equals" -> false;
                case "toString" -> "world";
                default -> null;
            });
        return new MuMenuGadgetBridge(null) {
            @Override
            Plugin muMenuPlugin() {
                return fakePlugin;
            }

            @Override
            Class<?> apiClass(ClassLoader loader) {
                return apiType;
            }

            @Override
            Object apiService(Class<?> type) {
                return service;
            }

            @Override
            Class<?> anchorClass(ClassLoader loader) {
                return FakeAnchor.class;
            }

            @Override
            Plugin ownerPlugin() {
                return owner;
            }

            @Override
            World worldOf(UUID worldId) {
                return fakeWorld;
            }
        };
    }

    private static ChairPanelAnchor anchor(String panelId, float yaw, float pitch, float roll) {
        return new ChairPanelAnchor(
            panelId, WORLD_ID, 1.0, 65.0, 2.0, yaw, pitch, roll, 1.5, 1.0);
    }

    @Test
    void 支持锚定面板时两块用不同panelId各自独立打开() {
        AnchoredApiImpl api = new AnchoredApiImpl();
        MuMenuGadgetBridge bridge = bridgeFor(AnchoredApi.class, api);
        Player fakePlayer = (Player) java.lang.reflect.Proxy.newProxyInstance(
            Player.class.getClassLoader(), new Class<?>[] {Player.class},
            (proxy, method, args) -> null);

        assertTrue(bridge.supportsAnchoredPanels(), "能力与方法齐全时必须可用");
        assertEquals(MuMenuGadgetBridge.PanelResult.OK,
            bridge.openPanel(fakePlayer, "chair-left", "muz-chair-panel", anchor("chair-left", 90f, 0f, 0f)));
        assertEquals(MuMenuGadgetBridge.PanelResult.OK,
            bridge.openPanel(fakePlayer, "chair-right", "muz-chair-panel", anchor("chair-right", -90f, 0f, 0f)));

        assertEquals(List.of("chair-left", "chair-right"), api.openedPanels,
            "两块必须用不同 panelId 分别打开，不能互相顶替");
        assertEquals(List.of("muz-chair-panel", "muz-chair-panel"), api.openedMenus);
        assertEquals(2, api.anchors.size(), "两次调用都必须真的构造出锚点对象");
        assertEquals(2, api.openedOwners.size(), "带 owner 的签名必须真的把 owner 传进去");
        assertTrue(api.openedOwners.stream().allMatch(java.util.Objects::nonNull),
            "owner 不能是 null：MuMenu 靠它做归属收口（插件禁用时统一收回面板）");
        assertEquals(1.5, api.anchors.get(0).width, 1.0e-9, "世界尺寸必须是 1.5 格");
        assertEquals(1.0, api.anchors.get(0).height, 1.0e-9, "世界尺寸必须是 1.0 格");
    }

    @Test
    void 旧版MuMenu没有锚定能力时failClosed且不回退成单面板() {
        // 只有 surface/open、没有 capabilities 的旧版 API。
        interface LegacyApi {
            Object surface(Player player);

            boolean open(Player player, String menuId, List<String> arguments);

            int apiVersion();
        }
        LegacyApi legacy = new LegacyApi() {
            @Override
            public Object surface(Player player) {
                return null;
            }

            @Override
            public boolean open(Player player, String menuId, List<String> arguments) {
                return true;
            }

            @Override
            public int apiVersion() {
                return MuMenuGadgetBridge.SUPPORTED_API_VERSION;
            }
        };
        MuMenuGadgetBridge bridge = bridgeFor(LegacyApi.class, legacy);
        Player fakePlayer = (Player) java.lang.reflect.Proxy.newProxyInstance(
            Player.class.getClassLoader(), new Class<?>[] {Player.class},
            (proxy, method, args) -> null);

        assertFalse(bridge.supportsAnchoredPanels(), "旧版必须判为不支持");
        assertEquals(MuMenuGadgetBridge.PanelResult.UNSUPPORTED,
            bridge.openPanel(fakePlayer, "chair-left", "muz-chair-panel", anchor("chair-left", 0f, 0f, 0f)),
            "旧版必须明确 UNSUPPORTED，绝不退化成普通 open 冒充面板");
        assertFalse(bridge.closePanels(fakePlayer), "旧版没有 closePanels，必须返回 false");
        assertTrue(bridge.panelIds(fakePlayer).isEmpty(), "旧版没有面板清单，必须返回空集合");
    }

    @Test
    void 只有能力没有方法时判不可用而不是半套API() {
        MuMenuGadgetBridge bridge = bridgeFor(CapabilityOnlyApi.class, new CapabilityOnlyApiImpl());
        assertFalse(bridge.supportsAnchoredPanels(),
            "声明了能力但缺少 openPanel 等方法时必须判不可用（不能只信能力位）");
    }

    @Test
    void 打开失败时如实返回FAILED而不是伪装成功() {
        AnchoredApiImpl api = new AnchoredApiImpl();
        api.openResult = false;
        MuMenuGadgetBridge bridge = bridgeFor(AnchoredApi.class, api);
        Player fakePlayer = (Player) java.lang.reflect.Proxy.newProxyInstance(
            Player.class.getClassLoader(), new Class<?>[] {Player.class},
            (proxy, method, args) -> null);

        assertEquals(MuMenuGadgetBridge.PanelResult.FAILED,
            bridge.openPanel(fakePlayer, "chair-left", "muz-chair-panel", anchor("chair-left", 0f, 0f, 0f)),
            "MuMenu 返回 false 时必须如实报告 FAILED（由调用方回滚另一块并提示玩家）");
    }

    @Test
    void 单轴锚点类型遇到pitch或roll时如实报告降级而不是静默丢弃() {
        AnchoredApiImpl api = new AnchoredApiImpl();
        MuMenuGadgetBridge bridge = bridgeFor(AnchoredApi.class, api);
        Player fakePlayer = (Player) java.lang.reflect.Proxy.newProxyInstance(
            Player.class.getClassLoader(), new Class<?>[] {Player.class},
            (proxy, method, args) -> null);

        assertFalse(bridge.tiltUnsupported(), "尚未投递过倾斜时不应有降级标记");
        bridge.openPanel(fakePlayer, "chair-left", "muz-chair-panel", anchor("chair-left", 30f, 12f, 5f));
        assertTrue(bridge.tiltUnsupported(),
            "替身锚点只有单轴构造器：pitch/roll 无法表达时必须留下降级标记，不能静默丢弃");
    }

    @Test
    void 拿不到owner时拒绝打开而不是把null传下去() {
        // 回归：owner 是 MuMenu 的归属收口依据（插件被禁用时统一收回面板）。
        // 传 null 会让面板失去归属、插件禁用后残留一块无法回收的界面，所以必须 fail-closed。
        AnchoredApiImpl api = new AnchoredApiImpl();
        MuMenuGadgetBridge bridge = bridgeFor(AnchoredApi.class, api, null);
        Player fakePlayer = (Player) java.lang.reflect.Proxy.newProxyInstance(
            Player.class.getClassLoader(), new Class<?>[] {Player.class},
            (proxy, method, args) -> null);

        assertTrue(bridge.supportsAnchoredPanels(), "能力与方法齐全时能力判定仍然为真");
        assertEquals(MuMenuGadgetBridge.PanelResult.FAILED,
            bridge.openPanel(fakePlayer, "chair-left", "muz-chair-panel", anchor("chair-left", 0f, 0f, 0f)),
            "owner 为 null 必须拒绝打开，不能让 null 流进 MuMenu");
        assertTrue(api.openedPanels.isEmpty(), "拒绝时不得产生任何实际打开调用");
    }

    @Test
    void 能力清单按名字判断不依赖枚举类型() {
        assertTrue(MuMenuGadgetBridge.declaresCapability(
            Set.of("ANCHORED_PANELS"), MuMenuGadgetBridge.CAPABILITY_ANCHORED_PANELS));
        assertFalse(MuMenuGadgetBridge.declaresCapability(
            Set.of("SCREEN_SURFACE"), MuMenuGadgetBridge.CAPABILITY_ANCHORED_PANELS));
        assertFalse(MuMenuGadgetBridge.declaresCapability(null, "ANCHORED_PANELS"));
        assertFalse(MuMenuGadgetBridge.declaresCapability("ANCHORED_PANELS", "ANCHORED_PANELS"),
            "非集合返回值一律视为未声明");
    }
}
