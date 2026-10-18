package linmumua.doudizhu.placeholder;

import java.util.List;
import java.util.Locale;
import linmumua.doudizhu.DoudizhuPlugin;
import linmumua.doudizhu.game.TableGadgetBarHudService;
import linmumua.doudizhu.game.TableGadgetBarHudService.GadgetSlotSnapshot;
import linmumua.doudizhu.ui.VirtualGadgetBar;
import me.clip.placeholderapi.expansion.PlaceholderExpansion;
import org.bukkit.OfflinePlayer;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

public final class MuzPlaceholderExpansion extends PlaceholderExpansion {
    private final DoudizhuPlugin plugin;

    public MuzPlaceholderExpansion(DoudizhuPlugin plugin) {
        this.plugin = plugin;
    }

    @Override
    public @NotNull String getIdentifier() {
        return "muz";
    }

    @Override
    public @NotNull String getAuthor() {
        return String.join(", ", plugin.getDescription().getAuthors());
    }

    @Override
    public @NotNull String getVersion() {
        return plugin.getDescription().getVersion();
    }

    @Override
    public boolean persist() {
        return true;
    }

    @Override
    public @Nullable String onRequest(OfflinePlayer player, @NotNull String params) {
        String trimmed = params == null ? "" : params.trim();
        if (trimmed.regionMatches(true, 0, "point_", 0, "point_".length())) {
            return plugin.placeholderPointValue(trimmed.substring("point_".length()), player);
        }
        if (trimmed.regionMatches(true, 0, "role_", 0, "role_".length())) {
            return plugin.placeholderRoleValue(trimmed.substring("role_".length()), player);
        }
        if (trimmed.regionMatches(true, 0, "identity_", 0, "identity_".length())) {
            return plugin.placeholderRoleValue(trimmed.substring("identity_".length()), player);
        }
        if (trimmed.regionMatches(true, 0, "hand_", 0, "hand_".length())) {
            return plugin.placeholderHandValue(trimmed.substring("hand_".length()), player);
        }
        if (trimmed.regionMatches(true, 0, "bid_", 0, "bid_".length())) {
            return plugin.placeholderBidValue(trimmed.substring("bid_".length()), player);
        }
        if (trimmed.regionMatches(true, 0, "table_", 0, "table_".length())) {
            return plugin.placeholderTableValue(trimmed.substring("table_".length()), player);
        }
        if (trimmed.regionMatches(true, 0, "phase_", 0, "phase_".length())) {
            return plugin.placeholderPhaseValue(trimmed.substring("phase_".length()), player);
        }
        if (trimmed.regionMatches(true, 0, "chip_", 0, "chip_".length())) {
            return plugin.placeholderChipValue(trimmed.substring("chip_".length()), player);
        }
        if (trimmed.regionMatches(true, 0, "gadget_", 0, "gadget_".length())) {
            return gadgetValue(trimmed.substring("gadget_".length()), player);
        }
        MuzHeadPlaceholderFormat.HeadRequest request = MuzHeadPlaceholderFormat.parse(params, player);
        if (request == null) {
            return null;
        }
        return MuzHeadPlaceholderFormat.buildTag(request);
    }

    /**
     * 道具栏动态标签（面板按钮用），**只读已发布的不可变快照**。
     *
     * <p>【为什么不能直接读数据源】PlaceholderAPI 的 {@code onRequest} 可能被别的插件在任意线程调用，
     * 而 {@code VirtualGadgetBar} 的读取会克隆 {@code ItemStack}（Bukkit 访问）、{@code VirtualGadgetBarStore}
     * 还会做同步文件 IO。快照由 {@link TableGadgetBarHudService#gadgetSlotSnapshot(java.util.UUID)} 在
     * player lane 上拍成纯 String/Boolean，这里只做无副作用读取，于是**任何线程调用都安全**。
     *
     * <p>支持：{@code slot_<n>}（槽 n 显示名，空槽 ""）、{@code empty_<n>}、{@code selected_<n>}
     * （{@code true}/{@code false}，n 为用户 1-based 1..8）、{@code selected}（{@code 1}..{@code 8} 或
     * {@code voice}）。无法解析或无快照时返回空串（不返回 null，避免面板出现「未知占位符」）。
     */
    private String gadgetValue(String key, OfflinePlayer player) {
        if (player == null || key == null) {
            return "";
        }
        TableGadgetBarHudService bar = plugin.getTableGadgetBarHudService();
        if (bar == null) {
            return "";
        }
        GadgetSlotSnapshot snapshot = bar.gadgetSlotSnapshot(player.getUniqueId());
        if (snapshot == null) {
            return "";
        }
        String lower = key.toLowerCase(Locale.ROOT);
        if (lower.equals("selected")) {
            int slot = snapshot.selectedSlot();
            return slot == VirtualGadgetBar.BUBBLE_SLOT ? "voice" : Integer.toString(slot + 1);
        }
        int index = gadgetSlotIndex(lower);
        if (index < 0 || index >= VirtualGadgetBar.SLOT_COUNT) {
            return "";
        }
        if (lower.startsWith("slot_")) {
            List<String> names = snapshot.slotNames();
            return names.size() > index ? names.get(index) : "";
        }
        if (lower.startsWith("empty_")) {
            return Boolean.toString(gadgetSlotEmpty(snapshot, index));
        }
        if (lower.startsWith("selected_")) {
            return Boolean.toString(snapshot.selectedSlot() == index);
        }
        return "";
    }

    /** 把 {@code slot_<n>}/{@code empty_<n>}/{@code selected_<n>} 解析成内部 0-based 槽号；非法返回 -1。 */
    static int gadgetSlotIndex(String key) {
        for (String prefix : new String[] {"slot_", "empty_", "selected_"}) {
            if (key.startsWith(prefix)) {
                try {
                    return Integer.parseInt(key.substring(prefix.length())) - 1;
                } catch (NumberFormatException ignored) {
                    return -1;
                }
            }
        }
        return -1;
    }

    /** 快照里第 index 个槽是否为空；越界按「空」处理（防御，不应发生）。 */
    static boolean gadgetSlotEmpty(GadgetSlotSnapshot snapshot, int index) {
        List<Boolean> empty = snapshot.empty();
        return empty.size() <= index || Boolean.TRUE.equals(empty.get(index));
    }
}

