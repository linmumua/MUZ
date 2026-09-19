package linmumua.doudizhu.ui;

import java.util.Objects;
import java.util.UUID;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;

/** 道具箱 GUI 的 holder，携带打开者和本次展示快照。 */
public final class GadgetBoxInventoryHolder implements InventoryHolder {
    private final UUID viewerId;
    private VirtualGadgetBar snapshot;
    private Inventory inventory;

    public GadgetBoxInventoryHolder(UUID viewerId, VirtualGadgetBar snapshot) {
        this.viewerId = Objects.requireNonNull(viewerId, "viewerId");
        this.snapshot = Objects.requireNonNull(snapshot, "snapshot");
    }

    public UUID viewerId() {
        return viewerId;
    }

    public VirtualGadgetBar snapshot() {
        return snapshot;
    }

    public void setSnapshot(VirtualGadgetBar snapshot) {
        this.snapshot = Objects.requireNonNull(snapshot, "snapshot");
    }

    public void setInventory(Inventory inventory) {
        this.inventory = inventory;
    }

    @Override
    public Inventory getInventory() {
        return inventory;
    }
}
