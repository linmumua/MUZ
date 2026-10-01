package linmumua.doudizhu.game;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import linmumua.doudizhu.assets.PackAssets;
import linmumua.doudizhu.game.TableGadgetBarHudService.HudBarSnapshot;
import linmumua.doudizhu.ui.VirtualGadgetBar;
import org.bukkit.Material;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * 桌内道具栏「按实际非空物品数显示 N 格 + 1 个语音格」的行为契约。
 *
 * <p>用户确认的语义：玩家道具槽里实际放了 N 个非空物品，就只显示 N 个道具格加 1 个语音入口格，
 * 空槽不再占位，整体居中。这里驱动的是一个纯排版核心（不碰 Bukkit 世界、不起任务），
 * 所以能直接验算「格数、叠加顺序、净前进量、选中映射」这四件事。
 *
 * <p>【为什么这些断言有意义】：旧实现固定渲染 9 格（空槽也画一格底图），宽度恒为满格常量。
 * 只要「空槽不占位」或「宽度按格数算」被改回去，下面的格数、字形序列与 advance 断言都会当场变红。
 */
class TableGadgetBarLayoutTest {
    private final List<Runnable> restoreCaches = new ArrayList<>();

    /**
     * 独立 JUnit 里没有服务端注册表，{@code Material.isAir()} 会走
     * {@code Registry} 并抛 {@code No RegistryAccess implementation found}（实跑确认）。
     * 这里按 {@code GadgetPreviewSnapshotServiceTest} 的同款做法，只对用到的这两个非方块材质
     * 把 {@code blockType} 缓存置空，退出时原样恢复；不修改任何生产代码。
     */
    @BeforeEach
    void isolateMaterialRegistry() throws Throwable {
        var lookup = java.lang.invoke.MethodHandles.lookup();
        var materialLookup = java.lang.invoke.MethodHandles.privateLookupIn(Material.class, lookup);
        var getter = materialLookup.findGetter(Material.class, "blockType", java.util.function.Supplier.class);
        for (Material material : List.of(Material.EGG, Material.WATER_BUCKET)) {
            Object cache = getter.invoke(material);
            var cacheLookup = java.lang.invoke.MethodHandles.privateLookupIn(cache.getClass(), lookup);
            var delegate = cacheLookup.findVarHandle(
                cache.getClass(), "delegate", com.google.common.base.Supplier.class);
            var value = cacheLookup.findVarHandle(cache.getClass(), "value", Object.class);
            Object originalDelegate = delegate.getVolatile(cache);
            Object originalValue = value.get(cache);
            restoreCaches.add(() -> {
                value.set(cache, originalValue);
                delegate.setVolatile(cache, originalDelegate);
            });
            value.set(cache, null);
            delegate.setVolatile(cache, (com.google.common.base.Supplier<Object>) () -> null);
        }
    }

    @AfterEach
    void restoreMaterialRegistry() {
        restoreCaches.forEach(Runnable::run);
    }

    /** 可读的负空格替身：把像素位移渲染成文本，便于逐格比对叠加顺序。 */
    private static String offset(int pixels) {
        return "[OFF:" + pixels + "]";
    }

    private static String glyph(int codepoint) {
        return "<font:" + PackAssets.GADGET_BAR_FONT + ">"
            + new String(Character.toChars(codepoint)) + "</font>";
    }

    private static String base() {
        return glyph(PackAssets.GADGET_BAR_BASE_CODEPOINT);
    }

    private static String icon(int kind) {
        return glyph(PackAssets.GADGET_BAR_ICON_CODEPOINT_START + kind - 1);
    }

    private static String select() {
        return glyph(PackAssets.GADGET_BAR_SELECT_CODEPOINT);
    }

    private static String back() {
        return offset(-PackAssets.GADGET_BAR_CELL_ADVANCE);
    }

    /** 一个格子的三层字形：底图 + 回退一格 + 图标（选中再叠选框）。 */
    private static String cell(int kind, boolean selected) {
        return base() + back() + icon(kind) + (selected ? back() + select() : "");
    }

    private static VirtualGadgetBar barOf(ItemStack... slots) {
        List<ItemStack> items = new ArrayList<>(Arrays.asList(slots));
        while (items.size() < VirtualGadgetBar.SLOT_COUNT) {
            items.add(null);
        }
        return VirtualGadgetBar.of(items);
    }

    /**
     * 裸物品替身：不经过 Paper 的延迟注册表。
     *
     * <p>{@code new ItemStack(Material.EGG)} 会走 {@code Material.asItemType} → {@code Registry}，
     * 而独立 JUnit 里没有服务端注册表（实跑报 {@code Could not initialize class org.bukkit.Registry}）。
     * 本测试只关心「非空 / 图标种类」，所以按 {@code GadgetPreviewSnapshotServiceTest} 的同款替身构造。
     */
    private static final class PlainItem extends ItemStack {
        private final Material material;

        PlainItem(Material material) {
            this.material = material;
        }

        @Override
        public Material getType() {
            return material;
        }

        @Override
        public ItemMeta getItemMeta() {
            return null;
        }

        @Override
        public boolean hasItemMeta() {
            return false;
        }

        @Override
        public void setAmount(int amount) {
        }

        @Override
        public ItemStack clone() {
            return new PlainItem(material);
        }
    }

    private static ItemStack egg() {
        return new PlainItem(Material.EGG);
    }

    private static ItemStack water() {
        return new PlainItem(Material.WATER_BUCKET);
    }

    @Test
    void 三件非空道具只显示三格加语音格且宽度按格数算() {
        HudBarSnapshot snapshot = TableGadgetBarHudService.layout(
            barOf(egg(), water(), egg()), 0, TableGadgetBarLayoutTest::offset);

        assertEquals(3, snapshot.visibleSlots(), "三个非空道具只算三格");
        assertEquals(4 * PackAssets.GADGET_BAR_CELL_ADVANCE, snapshot.advancePixels(),
            "整条栏宽必须按 (N+1) 格算，不能再写死满格 9 格");
        assertEquals(
            cell(PackAssets.GADGET_BAR_EGG, true)
                + cell(PackAssets.GADGET_BAR_WATER, false)
                + cell(PackAssets.GADGET_BAR_EGG, false)
                + cell(PackAssets.GADGET_BAR_SPEECH, false),
            snapshot.glyphText(),
            "顺序必须是「非空道具按槽位升序 + 语音格」，且只有选中格叠选框");
    }

    @Test
    void 空栏只显示语音格不再有八个空占位() {
        HudBarSnapshot snapshot = TableGadgetBarHudService.layout(
            VirtualGadgetBar.empty(), VirtualGadgetBar.BUBBLE_SLOT, TableGadgetBarLayoutTest::offset);

        assertEquals(0, snapshot.visibleSlots(), "清空后不应再有道具格");
        assertEquals(PackAssets.GADGET_BAR_CELL_ADVANCE, snapshot.advancePixels(),
            "只剩语音格时宽度就是一格");
        assertEquals(cell(PackAssets.GADGET_BAR_SPEECH, true), snapshot.glyphText(),
            "空栏必须且只能渲染带选框的语音格");
        assertFalse(snapshot.glyphText().contains(icon(PackAssets.GADGET_BAR_EGG)),
            "空栏不得再出现鸡蛋图标");
    }

    @Test
    void 非连续槽位按显示顺序紧凑排列() {
        // 中间槽为空：显示列表必须是 [0, 2]，而不是保留一个空位的 0/1/2。
        HudBarSnapshot snapshot = TableGadgetBarHudService.layout(
            barOf(egg(), null, water()), 2, TableGadgetBarLayoutTest::offset);

        assertEquals(2, snapshot.visibleSlots());
        assertEquals(3 * PackAssets.GADGET_BAR_CELL_ADVANCE, snapshot.advancePixels());
        assertEquals(
            cell(PackAssets.GADGET_BAR_EGG, false)
                + cell(PackAssets.GADGET_BAR_WATER, true)
                + cell(PackAssets.GADGET_BAR_SPEECH, false),
            snapshot.glyphText(),
            "空槽不占位：第二件道具必须紧挨在鸡蛋之后，选框落在它上面");
    }

    @Test
    void 满格时与既有满格常量一致() {
        ItemStack[] slots = new ItemStack[VirtualGadgetBar.SLOT_COUNT];
        Arrays.fill(slots, egg());
        HudBarSnapshot snapshot = TableGadgetBarHudService.layout(
            barOf(slots), 0, TableGadgetBarLayoutTest::offset);

        assertEquals(8, snapshot.visibleSlots());
        assertEquals(PackAssets.gadgetBarRowAdvance(), snapshot.advancePixels(),
            "满格宽度必须等于兼容入口的满格常量，两侧同源");
    }

    @Test
    void 快照宽度恒等于显示格数加一的格宽() {
        // 单一来源校验：可见格数说几格，宽度就必须是几格，不允许两处各算一份。
        for (VirtualGadgetBar bar : List.of(
            VirtualGadgetBar.empty(),
            barOf(egg(), water(), egg()),
            barOf(egg(), null, null, egg()))) {
            HudBarSnapshot snapshot = TableGadgetBarHudService.layout(
                bar, 0, TableGadgetBarLayoutTest::offset);
            assertEquals(PackAssets.gadgetBarRowAdvance(snapshot.visibleSlots() + 1),
                snapshot.advancePixels(),
                "宽度必须由显示格数唯一推出，实得 " + snapshot.advancePixels());
        }
    }

    @Test
    void 选中空槽时不会画出悬空选框() {
        HudBarSnapshot snapshot = TableGadgetBarHudService.layout(
            barOf(egg(), water(), egg()), 5, TableGadgetBarLayoutTest::offset);

        assertFalse(snapshot.glyphText().contains(select()),
            "选中一个空槽时不能出现悬空选框（收敛职责在 normalizeSelectedSlot）");
        assertEquals(3, snapshot.visibleSlots());
    }

    @Test
    void 快捷栏索引按显示顺序映射到真实槽位其余落到语音格() {
        List<Integer> displayed = List.of(0, 2, 5);

        assertEquals(0, TableGadgetBarHudService.slotForHotbarIndex(displayed, 0));
        assertEquals(2, TableGadgetBarHudService.slotForHotbarIndex(displayed, 1));
        assertEquals(5, TableGadgetBarHudService.slotForHotbarIndex(displayed, 2));
        for (int index = 3; index <= 8; index++) {
            assertEquals(VirtualGadgetBar.BUBBLE_SLOT,
                TableGadgetBarHudService.slotForHotbarIndex(displayed, index),
                "超出可见道具数的快捷栏索引必须落到语音格：index=" + index);
        }
        assertEquals(VirtualGadgetBar.BUBBLE_SLOT, TableGadgetBarHudService.slotForHotbarIndex(displayed, -1));
        assertEquals(VirtualGadgetBar.BUBBLE_SLOT,
            TableGadgetBarHudService.slotForHotbarIndex(List.of(), 0),
            "空栏时任何索引都只能落到语音格");
    }

    @Test
    void 选中槽不在显示集合时收敛到语音格() {
        assertEquals(0, TableGadgetBarHudService.normalizeSelectedSlot(List.of(0, 1, 2), 0));
        assertEquals(VirtualGadgetBar.BUBBLE_SLOT,
            TableGadgetBarHudService.normalizeSelectedSlot(List.of(0, 1, 2), 5),
            "道具被移除后选中槽不再显示，必须收敛到永远在场的语音格");
        assertEquals(VirtualGadgetBar.BUBBLE_SLOT,
            TableGadgetBarHudService.normalizeSelectedSlot(List.of(), 0));
        assertEquals(VirtualGadgetBar.BUBBLE_SLOT,
            TableGadgetBarHudService.normalizeSelectedSlot(List.of(0), VirtualGadgetBar.BUBBLE_SLOT),
            "已经是语音格时保持不变");
    }

    @Test
    void 显示列表只包含非空槽且按槽位升序() {
        assertEquals(List.of(1, 3), TableGadgetBarHudService.displayedSlots(barOf(null, egg(), null, water())));
        assertTrue(TableGadgetBarHudService.displayedSlots(null).isEmpty(), "null 快照按空列表处理");
    }
}
