package linmumua.doudizhu.world;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/**
 * @author linmumua
 * @Desc 牌桌自有实体 owner PDC 与严格清理边界契约
 * @date 2026-09-21
 */
class PhysicalTableEntityOwnershipTest {
    private static final Path PHYSICAL_MANAGER = Path.of(
        "src/main/java/linmumua/doudizhu/world/PhysicalTableManager.java");

    @Test
    void 自有实体写入桌标识放置者角色与代次且CE家具不走保护入口() throws IOException {
        String source = Files.readString(PHYSICAL_MANAGER);
        assertTrue(source.contains("new NamespacedKey(plugin, \"table-owner\")"));
        assertTrue(source.contains("new NamespacedKey(plugin, \"table-owner-name\")"));
        assertTrue(source.contains("new NamespacedKey(plugin, \"table-name\")"));
        assertTrue(source.contains("new NamespacedKey(plugin, \"table-role\")"));
        assertTrue(source.contains("new NamespacedKey(plugin, \"table-generation\")"));
        assertTrue(source.contains("entityOwnerKey"));
        assertTrue(source.contains("entityOwnerNameKey"));
        assertTrue(source.contains("entityTableKey"));
        assertTrue(source.contains("entityRoleKey"));
        assertTrue(source.contains("entityGenerationKey"));
        assertTrue(source.contains("writeEntityOwnership(entity, owner, role)"));
        assertTrue(source.contains("addEntityTreeIds(tablePlacement.entityId(), staticEntities, placed.owner(), ENTITY_ROLE_TABLE)"));
        assertTrue(source.contains("collectEntityTreeIds(tablePlacement.entityId(), staticEntities)"));
        assertTrue(source.contains("collectEntityTreeIds(chairPlacement.entityId(), staticEntities)"));
    }

    @Test
    void owner匹配必须同时校验桌名代次角色和放置者字段() {
        UUID ownerId = UUID.randomUUID();
        PhysicalTableManager.TableOwner expected = new PhysicalTableManager.TableOwner(
            ownerId, "Alice", "table-a", 7L);

        assertTrue(PhysicalTableManager.matchesEntityOwner(
            expected, ownerId.toString(), "Alice", "table-a", "card", 7L));
        assertFalse(PhysicalTableManager.matchesEntityOwner(
            expected, ownerId.toString(), "Alice", "table-b", "card", 7L));
        assertFalse(PhysicalTableManager.matchesEntityOwner(
            expected, ownerId.toString(), "Alice", "table-a", "card", 8L));
        assertFalse(PhysicalTableManager.matchesEntityOwner(
            expected, ownerId.toString(), "Alice", "table-a", "", 7L));
        assertFalse(PhysicalTableManager.matchesEntityOwner(
            expected, ownerId.toString(), "Bob", "table-a", "card", 7L));
        assertFalse(PhysicalTableManager.matchesEntityOwner(
            expected, null, "Alice", "table-a", "card", 7L));
    }

    @Test
    void 清理缺失或不匹配PDC时必须跳过并保留诊断() throws IOException {
        String source = Files.readString(PHYSICAL_MANAGER);
        assertTrue(source.contains("if (entity != null && ownedBy(entity, owner))"));
        assertFalse(source.contains("owner == null || ownedBy(entity, owner)"));
        assertTrue(source.contains("跳过牌桌实体清理：owner PDC 不匹配"));
        assertTrue(source.contains("跳过残留实体清理：MUZ 实体缺少当前牌桌 owner 匹配"));
    }
}
