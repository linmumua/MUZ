package linmumua.doudizhu.world;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

/**
 * 锁定 CE 家具与 MUZ 自有实体的持久化边界。
 *
 * <p>CE 家具必须保留持久化状态，才能让 CraftEngine 自己处理区块卸载、加载和旧映射失效；
 * fallback Display 则继续走 MUZ 的非持久化保护路径。
 */
class FurniturePersistenceBoundaryTest {
    private static final Path MANAGER =
        Path.of("src/main/java/linmumua/doudizhu/world/PhysicalTableManager.java");

    @Test
    void ceTableBranchOnlyCollectsEntityIds() throws IOException {
        String source = Files.readString(MANAGER, StandardCharsets.UTF_8);
        String branch = between(source, "if (tablePlacement.craftEngineEntity()) {", "} else {");

        assertTrue(branch.contains("collectEntityTreeIds(tablePlacement.entityId(), staticEntities)"));
        assertTrue(branch.contains("collectEntityTreeIds(tablePlacement.entityId(), placed.craftEngineVisualEntities())"));
        assertFalse(branch.contains("addEntityTreeIds(tablePlacement.entityId(), staticEntities)"),
            "CE 桌面不能进入 protectEntityTree，否则会被设为 persistent=false");
    }

    @Test
    void ceChairBranchOnlyCollectsEntityIds() throws IOException {
        String source = Files.readString(MANAGER, StandardCharsets.UTF_8);
        String branch = between(source, "if (chairPlacement.craftEngineEntity()) {", "} else {");

        assertTrue(branch.contains("collectEntityTreeIds(chairPlacement.entityId(), staticEntities)"));
        assertTrue(branch.contains("collectEntityTreeIds(chairPlacement.entityId(), placed.craftEngineVisualEntities())"));
        assertFalse(branch.contains("addEntityTreeIds(chairPlacement.entityId(), staticEntities)"),
            "CE 椅子不能进入 protectEntityTree，否则区块返回后会留下失效家具映射");
    }

    @Test
    void fallbackBranchesStillUseProtectedEntityPath() throws IOException {
        String source = Files.readString(MANAGER, StandardCharsets.UTF_8);
        String tableFallback = between(source, "} else {\n            ItemDisplay fallbackTableDisplay", "        if (tablePlacement.blockRestore() != null)");
        String chairFallback = between(source, "} else {\n                    addEntityTreeIds(chairPlacement.entityId(), staticEntities, placed.owner(), ENTITY_ROLE_CHAIR);", "            }\n            if (chairPlacement.blockRestore() != null)");

        assertTrue(tableFallback.contains("staticEntities.add(fallbackTableDisplay.getUniqueId())"));
        assertTrue(chairFallback.contains(
            "addEntityTreeIds(chairPlacement.entityId(), staticEntities, placed.owner(), ENTITY_ROLE_CHAIR)"));
    }

    private static String between(String source, String start, String end) {
        int startIndex = source.indexOf(start);
        assertTrue(startIndex >= 0, "缺少源码片段：" + start);
        int endIndex = source.indexOf(end, startIndex + start.length());
        assertTrue(endIndex > startIndex, "缺少源码结束片段：" + end);
        return source.substring(startIndex, endIndex);
    }
}
