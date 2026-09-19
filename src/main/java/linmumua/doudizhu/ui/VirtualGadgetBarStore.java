package linmumua.doudizhu.ui;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import linmumua.doudizhu.config.MuzYamlConfig;
import org.bukkit.inventory.ItemStack;

/**
 * 独立玩家虚拟道具栏存储。
 *
 * <p>配置路径为 {@code players.<uuid>.gadget-bar}。路径缺失代表“沿用默认道具”，
 * 明确保存的空列表代表“玩家主动清空”，两者不能混为一谈。
 */
public final class VirtualGadgetBarStore {
    private static final String ROOT = "players.";
    private static final String KEY = ".gadget-bar";

    private final Path file;

    public VirtualGadgetBarStore(Path playerSettingsFile) {
        this.file = playerSettingsFile.toAbsolutePath().normalize();
    }

    public Path file() {
        return file;
    }

    public synchronized VirtualGadgetBar load(UUID playerId) {
        String path = path(playerId);
        MuzYamlConfig config = new MuzYamlConfig(file);
        if (!config.contains(path)) {
            return VirtualGadgetBar.defaults();
        }
        Object raw = config.get(path);
        if (!(raw instanceof List<?> list)) {
            return VirtualGadgetBar.empty();
        }
        List<ItemStack> items = new ArrayList<>(VirtualGadgetBar.SLOT_COUNT);
        for (int index = 0; index < Math.min(list.size(), VirtualGadgetBar.SLOT_COUNT); index++) {
            items.add(decodeItem(list.get(index)));
        }
        return VirtualGadgetBar.of(items);
    }

    public synchronized void save(UUID playerId, VirtualGadgetBar bar) {
        if (playerId == null || bar == null) {
            throw new IllegalArgumentException("玩家 UUID 和道具栏快照都不能为空");
        }
        MuzYamlConfig config = new MuzYamlConfig(file);
        // 空栏必须写成显式 []，与缺失路径（沿用默认道具）保持可辨别。
        config.set(path(playerId), bar.isEmpty() ? List.of() : bar.items());
        try {
            config.save();
        } catch (IOException exception) {
            throw new IllegalStateException("保存玩家虚拟道具栏失败: " + file, exception);
        }
    }

    /** 删除玩家的显式道具栏配置；下一次读取会回到默认鸡蛋/水桶/番茄。 */
    public synchronized void reset(UUID playerId) {
        MuzYamlConfig config = new MuzYamlConfig(file);
        config.set(path(playerId), null);
        try {
            config.save();
        } catch (IOException exception) {
            throw new IllegalStateException("重置玩家虚拟道具栏失败: " + file, exception);
        }
    }

    private String path(UUID playerId) {
        if (playerId == null) {
            throw new IllegalArgumentException("玩家 UUID 不能为空");
        }
        return ROOT + playerId + KEY;
    }

    @SuppressWarnings("unchecked")
    private ItemStack decodeItem(Object value) {
        if (!(value instanceof Map<?, ?> map)) {
            return null;
        }
        Object marker = map.get("==");
        if (marker == null || !String.valueOf(marker).contains("ItemStack")) {
            return null;
        }
        Map<String, Object> serialized = new java.util.LinkedHashMap<>();
        for (Map.Entry<?, ?> entry : map.entrySet()) {
            if (!"==".equals(String.valueOf(entry.getKey()))) {
                serialized.put(String.valueOf(entry.getKey()), entry.getValue());
            }
        }
        try {
            return ItemStack.deserialize(serialized);
        } catch (RuntimeException exception) {
            return null;
        }
    }
}
