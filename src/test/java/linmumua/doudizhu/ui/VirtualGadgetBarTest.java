package linmumua.doudizhu.ui;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

class VirtualGadgetBarTest {
    private static final Path SOURCE = Path.of("src/main/java/linmumua/doudizhu/ui/VirtualGadgetBar.java");

    @Test
    void emptySnapshotHasEightStableSlots() {
        VirtualGadgetBar empty = VirtualGadgetBar.empty();

        assertTrue(empty.isEmpty());
        assertEquals(VirtualGadgetBar.SLOT_COUNT, empty.size());
        assertEquals(0, empty.firstEmpty());
        assertEquals(8, empty.items().size());
        assertThrows(IndexOutOfBoundsException.class, () -> empty.slot(VirtualGadgetBar.BUBBLE_SLOT));
    }

    @Test
    void sourceLocksDefaultItemsBubbleBoundaryAndSingleItemSnapshots() throws IOException {
        String source = Files.readString(SOURCE);

        assertTrue(source.contains("items.set(0, new ItemStack(org.bukkit.Material.EGG))"));
        assertTrue(source.contains("items.set(1, new ItemStack(org.bukkit.Material.WATER_BUCKET))"));
        assertTrue(source.contains("items.set(2, GadgetBoxItems.tomato())"));
        assertTrue(source.contains("public static final int BUBBLE_SLOT = 8"));
        assertTrue(source.contains("ItemStack snapshot = item.clone()"), "入库必须复制 ItemStack");
        assertTrue(source.contains("snapshot.setAmount(1)"), "虚拟栏只保存单件快照，不复制真实堆叠数量");
        assertTrue(source.contains("return item == null ? null : item.clone()"), "出库必须再次复制 ItemStack");
    }
}
