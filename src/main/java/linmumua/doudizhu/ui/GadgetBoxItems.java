package linmumua.doudizhu.ui;

import linmumua.doudizhu.compat.VersionCompat;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;

/** 道具箱中的固定展示物品。 */
final class GadgetBoxItems {
    private GadgetBoxItems() {
    }

    static ItemStack tomato() {
        ItemStack item = new ItemStack(Material.PAPER);
        ItemMeta meta = item.getItemMeta();
        if (meta == null) {
            return item;
        }
        VersionCompat.setItemModel(meta, NamespacedKey.fromString("muz:table_gadget_tomato"));
        item.setItemMeta(meta);
        return item;
    }

    static ItemStack bubble() {
        return bubble("语音气泡");
    }

    static ItemStack bubble(String displayName) {
        ItemStack item = new ItemStack(Material.PAPER);
        ItemMeta meta = item.getItemMeta();
        if (meta == null) {
            return item;
        }
        NamespacedKey model = NamespacedKey.fromString("muz:table_gadget_speech_bubble");
        if (model != null) {
            VersionCompat.setItemModel(meta, model);
        }
        meta.displayName(linmumua.doudizhu.ui.MuzTheme.accent(
            displayName == null || displayName.isBlank() ? "语音气泡" : displayName));
        meta.lore(java.util.List.of(linmumua.doudizhu.ui.MuzTheme.muted("右键打开桌内语音面板")));
        item.setItemMeta(meta);
        return item;
    }
}
