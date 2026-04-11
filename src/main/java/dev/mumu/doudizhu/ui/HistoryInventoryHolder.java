package dev.mumu.doudizhu.ui;

import java.util.UUID;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;

public final class HistoryInventoryHolder implements InventoryHolder {
    public enum Mode {
        LIST,
        DETAIL
    }

    private final UUID viewerId;
    private final UUID targetPlayerId;
    private final String targetName;
    private final int page;
    private final Mode mode;
    private final long matchId;
    private Inventory inventory;

    public HistoryInventoryHolder(UUID viewerId, UUID targetPlayerId, String targetName, int page) {
        this(viewerId, targetPlayerId, targetName, page, Mode.LIST, -1L);
    }

    public HistoryInventoryHolder(UUID viewerId, UUID targetPlayerId, String targetName, int page, Mode mode, long matchId) {
        this.viewerId = viewerId;
        this.targetPlayerId = targetPlayerId;
        this.targetName = targetName;
        this.page = page;
        this.mode = mode;
        this.matchId = matchId;
    }

    public UUID viewerId() {
        return viewerId;
    }

    public UUID targetPlayerId() {
        return targetPlayerId;
    }

    public String targetName() {
        return targetName;
    }

    public int page() {
        return page;
    }

    public Mode mode() {
        return mode;
    }

    public long matchId() {
        return matchId;
    }

    public void setInventory(Inventory inventory) {
        this.inventory = inventory;
    }

    @Override
    public Inventory getInventory() {
        return inventory;
    }
}
