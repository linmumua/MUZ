package dev.mumu.doudizhu.placeholder;

import dev.mumu.doudizhu.DoudizhuPlugin;
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
        MuzHeadPlaceholderFormat.HeadRequest request = MuzHeadPlaceholderFormat.parse(params, player);
        if (request == null) {
            return null;
        }
        return MuzHeadPlaceholderFormat.buildTag(request);
    }
}
