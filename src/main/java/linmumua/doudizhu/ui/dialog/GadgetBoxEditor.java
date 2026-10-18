package linmumua.doudizhu.ui.dialog;

import linmumua.doudizhu.ui.VirtualGadgetBar;
import org.bukkit.inventory.ItemStack;

/**
 * 道具箱 Dialog 的纯逻辑：八槽占用、复制、移除、选中归一、语音槽判定。
 *
 * <p>【为什么单独一层】原生 Dialog 的回调跑在 player lane，本身很难在单测里驱动；
 * 但「哪些槽是空的、复制到第几格、满槽了没有、移除后选中态落到哪」这些判断与
 * Bukkit 服务端无关。把它们收口成纯函数，行为就能在测试 JVM 里逐条锁定，
 * 不必依赖无法构造真实 {@link ItemStack} 的测试环境（本仓库测试运行期没有 RegistryAccess，
 * 真的 {@code new ItemStack(Material.X)} 会抛 ExceptionInInitializerError）。
 *
 * <p>这里不持有状态、不做文件 IO、不碰 Bukkit 服务端；持久化与重绘由 Screen 走既有异步链。
 */
public final class GadgetBoxEditor {

    /** 编辑语义；{@link #FULL} 与 {@link #NO_SOURCE} 是要给玩家明确提示的两类拒绝。 */
    public enum Outcome {
        /** 已把来源物品的单件快照复制进某空槽。 */
        COPIED,
        /** 已移除某槽并左移紧凑。 */
        REMOVED,
        /** 选中态已落到某真实槽位。 */
        SELECTED,
        /** 八槽已满，无法再复制。 */
        FULL,
        /** 来源为空（没有物品可复制）。 */
        NO_SOURCE,
        /** 无效操作（越界槽、空槽移除等），不改动数据。 */
        IGNORED
    }

    /** 一次编辑的不可变结果：{@code bar} 是编辑后的新快照，{@code slot} 是受影响槽位（无则 -1）。 */
    public record Edit(Outcome outcome, VirtualGadgetBar bar, int slot) {
        public Edit {
            if (bar == null) {
                throw new IllegalArgumentException("编辑结果必须携带虚拟道具栏快照");
            }
        }
    }

    /** 八槽位图掩码（bit i = 槽 i 非空）。 */
    public static final int FULL_MASK = (1 << VirtualGadgetBar.SLOT_COUNT) - 1;

    private GadgetBoxEditor() {
    }

    public static boolean isVoiceSlot(int slot) {
        return slot == VirtualGadgetBar.BUBBLE_SLOT;
    }

    public static boolean isEditableSlot(int slot) {
        return slot >= 0 && slot < VirtualGadgetBar.SLOT_COUNT;
    }

    /**
     * 八槽占用位图。逐格只判「有没有值」，不复制也不比较物品内容，
     * 因此重复道具（两格同种）会得到两个独立的 bit，槽位映射天然按真实槽而不是按物品类型。
     */
    public static int occupiedMask(VirtualGadgetBar bar) {
        if (bar == null) {
            return 0;
        }
        int mask = 0;
        for (int slot = 0; slot < VirtualGadgetBar.SLOT_COUNT; slot++) {
            if (bar.slot(slot) != null) {
                mask |= 1 << slot;
            }
        }
        return mask & FULL_MASK;
    }

    public static int occupiedCount(int mask) {
        return Integer.bitCount(mask & FULL_MASK);
    }

    /** 第一个空槽；八槽全满返回 -1。 */
    public static int firstEmpty(int mask) {
        for (int slot = 0; slot < VirtualGadgetBar.SLOT_COUNT; slot++) {
            if ((mask & (1 << slot)) == 0) {
                return slot;
            }
        }
        return -1;
    }

    public static boolean isFull(int mask) {
        return firstEmpty(mask) < 0;
    }

    /**
     * 选中态归一：只有指向「已占用槽」才保留；越界、语音槽或空槽一律收敛为 -1（未选中）。
     * 这是「用旧快照打开面板时选中态已失效」的收口点，不会让选中态指向空槽。
     */
    public static int normalizeSelected(int selected, int mask) {
        return isEditableSlot(selected) && (mask & (1 << selected)) != 0 ? selected : -1;
    }

    public static int normalizeSelected(int selected, VirtualGadgetBar bar) {
        return normalizeSelected(selected, occupiedMask(bar));
    }

    /**
     * 移除槽位后选中态的新位置：与 {@link VirtualGadgetBar#remove(int)} 的左移紧凑保持一致。
     * 移除了选中项本身则清空选中，其余高于被删槽的选中项左移一位。
     */
    public static int compactedIndex(int selected, int removedSlot) {
        if (!isEditableSlot(selected) || !isEditableSlot(removedSlot)) {
            return isEditableSlot(selected) ? selected : -1;
        }
        if (selected == removedSlot) {
            return -1;
        }
        return selected > removedSlot ? selected - 1 : selected;
    }

    /**
     * 把来源物品复制进第一个空槽，**不消耗来源**：
     * 只保存单件快照（{@link ItemStack#clone()} 后 {@code setAmount(1)}），
     * 玩家的真实背包与主手物品都不被改写。八槽已满返回 {@link Outcome#FULL}。
     */
    public static Edit copyInto(VirtualGadgetBar bar, ItemStack source) {
        if (source == null || source.getType().isAir()) {
            return new Edit(Outcome.NO_SOURCE, bar, -1);
        }
        int slot = firstEmpty(occupiedMask(bar));
        if (slot < 0) {
            return new Edit(Outcome.FULL, bar, -1);
        }
        ItemStack copy = source.clone();
        copy.setAmount(1);
        return new Edit(Outcome.COPIED, bar.withSlot(slot, copy), slot);
    }

    /** 移除指定槽并左移紧凑；空槽或越界一律 {@link Outcome#IGNORED} 且不改动数据。 */
    public static Edit removeSlot(VirtualGadgetBar bar, int slot) {
        if (!isEditableSlot(slot) || bar.slot(slot) == null) {
            return new Edit(Outcome.IGNORED, bar, -1);
        }
        return new Edit(Outcome.REMOVED, bar.remove(slot), slot);
    }
}
