package linmumua.doudizhu.ui;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;
import org.bukkit.inventory.ItemStack;

/**
 * 玩家道具箱的八格虚拟快照。
 *
 * <p>快照只保存 0..7 槽位；第 8 槽是 GUI 固定的气泡入口，不属于玩家数据。
 * 所有 ItemStack 都会在进入和离开 API 时复制，调用方不能通过修改返回对象污染快照。
 */
public final class VirtualGadgetBar {
    public static final int SLOT_COUNT = 8;
    public static final int BUBBLE_SLOT = 8;

    private final List<ItemStack> items;

    private VirtualGadgetBar(List<ItemStack> items) {
        if (items.size() != SLOT_COUNT) {
            throw new IllegalArgumentException("虚拟道具栏必须有 " + SLOT_COUNT + " 个槽位");
        }
        List<ItemStack> copy = new ArrayList<>(SLOT_COUNT);
        for (ItemStack item : items) {
            if (item == null || item.getType().isAir()) {
                copy.add(null);
                continue;
            }
            ItemStack snapshot = item.clone();
            snapshot.setAmount(1);
            copy.add(snapshot);
        }
        this.items = Collections.unmodifiableList(copy);
    }

    /** 返回默认的鸡蛋、水桶、番茄，其余槽位为空。 */
    public static VirtualGadgetBar defaults() {
        List<ItemStack> items = emptyItems();
        items.set(0, new ItemStack(org.bukkit.Material.EGG));
        items.set(1, new ItemStack(org.bukkit.Material.WATER_BUCKET));
        items.set(2, GadgetBoxItems.tomato());
        return new VirtualGadgetBar(items);
    }

    /** 返回明确的八格空列表；这和配置缺失时的默认道具不同。 */
    public static VirtualGadgetBar empty() {
        return new VirtualGadgetBar(emptyItems());
    }

    public static VirtualGadgetBar of(List<ItemStack> items) {
        Objects.requireNonNull(items, "items");
        if (items.size() > SLOT_COUNT) {
            throw new IllegalArgumentException("虚拟道具栏最多有 " + SLOT_COUNT + " 个槽位");
        }
        List<ItemStack> normalized = emptyItems();
        for (int index = 0; index < items.size(); index++) {
            normalized.set(index, items.get(index));
        }
        return new VirtualGadgetBar(normalized);
    }

    public int size() {
        return SLOT_COUNT;
    }

    public ItemStack slot(int slot) {
        checkSlot(slot);
        ItemStack item = items.get(slot);
        return item == null ? null : item.clone();
    }

    public List<ItemStack> items() {
        List<ItemStack> copy = new ArrayList<>(SLOT_COUNT);
        for (ItemStack item : items) {
            copy.add(item == null ? null : item.clone());
        }
        return Collections.unmodifiableList(copy);
    }

    public VirtualGadgetBar withSlot(int slot, ItemStack item) {
        checkSlot(slot);
        List<ItemStack> copy = items();
        copy = new ArrayList<>(copy);
        copy.set(slot, item);
        return new VirtualGadgetBar(copy);
    }

    /** 删除指定项并向左紧凑，保证 GUI 只显示实际存在的连续道具格。 */
    public VirtualGadgetBar remove(int slot) {
        checkSlot(slot);
        List<ItemStack> compacted = new ArrayList<>(SLOT_COUNT);
        for (int index = 0; index < SLOT_COUNT; index++) {
            if (index != slot && items.get(index) != null) {
                compacted.add(items.get(index).clone());
            }
        }
        while (compacted.size() < SLOT_COUNT) {
            compacted.add(null);
        }
        return new VirtualGadgetBar(compacted);
    }

    public int firstEmpty() {
        for (int index = 0; index < SLOT_COUNT; index++) {
            if (items.get(index) == null) {
                return index;
            }
        }
        return -1;
    }

    public boolean isEmpty() {
        for (ItemStack item : items) {
            if (item != null) {
                return false;
            }
        }
        return true;
    }

    private static List<ItemStack> emptyItems() {
        return new ArrayList<>(Collections.nCopies(SLOT_COUNT, null));
    }

    private static void checkSlot(int slot) {
        if (slot < 0 || slot >= SLOT_COUNT) {
            throw new IndexOutOfBoundsException("虚拟道具栏槽位必须在 0..7：" + slot);
        }
    }
}
