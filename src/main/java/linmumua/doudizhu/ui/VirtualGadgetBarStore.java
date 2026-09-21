package linmumua.doudizhu.ui;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
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
        RawBar raw = loadRaw(playerId);
        if (!raw.present()) {
            return VirtualGadgetBar.defaults();
        }
        if (raw.values().isEmpty()) {
            return VirtualGadgetBar.empty();
        }
        return decodeRaw(raw);
    }

    /** 主线程把异步读取的原始 YAML 快照解码成 Bukkit ItemStack。 */
    public VirtualGadgetBar decodeRaw(RawBar raw) {
        if (raw == null || !raw.present()) {
            return VirtualGadgetBar.defaults();
        }
        if (raw.values().isEmpty()) {
            return VirtualGadgetBar.empty();
        }
        List<ItemStack> items = new ArrayList<>(VirtualGadgetBar.SLOT_COUNT);
        for (Object value : raw.values()) {
            items.add(decodeItem(value));
        }
        return VirtualGadgetBar.of(items);
    }

    /**
     * 异步预览使用的原始 YAML 快照。这里只读取 SnakeYAML 结果，不调用 Bukkit 解码。
     * 调用方必须在异步线程执行，ItemStack 解码由 {@link #decodeItem(Object)} 在主线程完成。
     */
    public synchronized RawBar loadRaw(UUID playerId) {
        String[] parts = path(playerId).split("\\.");
        Object value = configValue(MuzYamlConfig.readOnlyRoot(file), parts, 0);
        if (value == Missing.VALUE) {
            return new RawBar(false, List.of());
        }
        if (!(value instanceof List<?> list)) {
            return new RawBar(true, List.of());
        }
        return new RawBar(true, immutableAllowingNulls(list.subList(0, Math.min(list.size(), VirtualGadgetBar.SLOT_COUNT))));
    }

    private static List<Object> immutableAllowingNulls(List<?> values) {
        List<Object> copy = new ArrayList<>(values.size());
        copy.addAll(values);
        return Collections.unmodifiableList(copy);
    }

    private static Object configValue(Map<String, Object> current, String[] parts, int index) {
        if (index >= parts.length) {
            return current;
        }
        Object value = current.get(parts[index]);
        if (value == null) {
            return Missing.VALUE;
        }
        if (index == parts.length - 1) {
            return value;
        }
        if (!(value instanceof Map<?, ?> map)) {
            return Missing.VALUE;
        }
        Map<String, Object> next = new java.util.LinkedHashMap<>();
        for (Map.Entry<?, ?> entry : map.entrySet()) {
            next.put(String.valueOf(entry.getKey()), entry.getValue());
        }
        return configValue(next, parts, index + 1);
    }

    private enum Missing { VALUE }

    public record RawBar(boolean present, List<Object> values) {
        public RawBar {
            values = values == null ? List.of() : immutableAllowingNulls(values);
        }
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
