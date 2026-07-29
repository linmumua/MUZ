package linmumua.doudizhu.world;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/**
 * 椅子 hover 映射必须按 id 撤销，不能按坐标扫。
 *
 * 实测过的泄漏：诊断桌放在 forceload 范围外，区块卸载后按坐标找不到椅子实体，
 * 每次 /muz reload 都会残留 3 条映射（27→30→33）。改成记录 id 后稳定在 15 不再增长。
 * 这里用同构的最小模型锁住"登记时记 id、撤销时按 id 清"这条语义。
 */
class ChairHoverLeakTest {
    /** 复刻 registerChairHoverTargets 的登记动作。 */
    private static void remember(
        Map<UUID, UUID> displayByBinding,
        Map<String, Set<UUID>> trackedByTable,
        String table,
        UUID chairId,
        UUID iconId
    ) {
        displayByBinding.put(chairId, iconId);
        trackedByTable.computeIfAbsent(table, key -> new LinkedHashSet<>()).add(chairId);
    }

    /** 复刻 clearChairHoverTargets 的撤销动作。 */
    private static void clear(
        Map<UUID, UUID> displayByBinding,
        Map<String, Set<UUID>> trackedByTable,
        String table
    ) {
        Set<UUID> tracked = trackedByTable.remove(table);
        if (tracked == null) {
            return;
        }
        tracked.forEach(displayByBinding::remove);
    }

    @Test
    void reloadDoesNotAccumulateMappings() {
        Map<UUID, UUID> displayByBinding = new LinkedHashMap<>();
        Map<String, Set<UUID>> trackedByTable = new LinkedHashMap<>();
        UUID icon = UUID.randomUUID();
        // 每轮 reload 椅子实体都是新 id，这正是按坐标撤销会漏掉的情形。
        for (int round = 0; round < 5; round++) {
            clear(displayByBinding, trackedByTable, "t1");
            for (int seat = 0; seat < 3; seat++) {
                remember(displayByBinding, trackedByTable, "t1", UUID.randomUUID(), icon);
            }
        }
        assertEquals(3, displayByBinding.size(), "反复 reload 后映射不该累积");
    }

    @Test
    void removingTableClearsAllItsMappings() {
        Map<UUID, UUID> displayByBinding = new LinkedHashMap<>();
        Map<String, Set<UUID>> trackedByTable = new LinkedHashMap<>();
        UUID icon = UUID.randomUUID();
        for (int seat = 0; seat < 3; seat++) {
            remember(displayByBinding, trackedByTable, "t1", UUID.randomUUID(), icon);
        }

        clear(displayByBinding, trackedByTable, "t1");

        assertTrue(displayByBinding.isEmpty(), "拆桌后该桌映射必须归零");
        assertTrue(trackedByTable.isEmpty(), "跟踪表本身也要清掉，否则换个地方泄漏");
    }

    @Test
    void clearingOneTableLeavesOtherTablesIntact() {
        Map<UUID, UUID> displayByBinding = new LinkedHashMap<>();
        Map<String, Set<UUID>> trackedByTable = new LinkedHashMap<>();
        UUID iconA = UUID.randomUUID();
        UUID iconB = UUID.randomUUID();
        remember(displayByBinding, trackedByTable, "t1", UUID.randomUUID(), iconA);
        UUID keptChair = UUID.randomUUID();
        remember(displayByBinding, trackedByTable, "t2", keptChair, iconB);

        clear(displayByBinding, trackedByTable, "t1");

        assertEquals(1, displayByBinding.size(), "拆一张桌不该影响别的桌");
        assertEquals(iconB, displayByBinding.get(keptChair));
    }
}
