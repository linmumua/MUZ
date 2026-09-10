package linmumua.doudizhu.world;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

/**
 * 守护 /muz debug add 的专用调试放桌入口与正式放桌入口之间的边界。
 *
 * <p>核心契约：
 * <ul>
 *   <li>placeDebugTableAt 仅绕过 placementObstruction（桌面/椅子方块占用检测），
 *       保留玩家已在其他桌、重复桌名、区块预加载、残留实体清理与实体生成等保护。</li>
 *   <li>正式入口 placeNewTableInternal 必须继续做 placementObstruction 检测。</li>
 *   <li>/muz debug add 命令必须调用 placeDebugTableAt，不走 placeNewTableAt。</li>
 * </ul>
 */
class DebugPlacementBypassTest {
    private static final Path MANAGER = Path.of(
        "src/main/java/linmumua/doudizhu/world/PhysicalTableManager.java");
    private static final Path COMMAND = Path.of(
        "src/main/java/linmumua/doudizhu/command/DoudizhuCommand.java");

    // ── placeDebugTableAt 必须存在且不调用 placementObstruction ──

    @Test
    void placeDebugTableAt方法存在() throws IOException {
        String source = Files.readString(MANAGER);
        assertTrue(source.contains("public GameTable placeDebugTableAt("),
            "PhysicalTableManager 必须提供 placeDebugTableAt 专用调试放桌入口");
    }

    @Test
    void placeDebugTableAt不做方块占用检测() throws IOException {
        String source = Files.readString(MANAGER);
        String body = extractMethodBody(source, "public GameTable placeDebugTableAt(");
        assertFalse(body.contains("placementObstruction"),
            "placeDebugTableAt 不应调用 placementObstruction——调试放桌的核心目的就是绕过方块占用检测");
    }

    @Test
    void placeDebugTableAt保留重复桌名保护() throws IOException {
        String source = Files.readString(MANAGER);
        String body = extractMethodBody(source, "public GameTable placeDebugTableAt(");
        assertTrue(body.contains("placedTables.containsKey(key)"),
            "placeDebugTableAt 必须保留重复桌名检测，防止同名覆盖");
    }

    @Test
    void placeDebugTableAt仍检查玩家已在其他桌() throws IOException {
        String source = Files.readString(MANAGER);
        String body = extractMethodBody(source, "public GameTable placeDebugTableAt(");
        assertTrue(body.contains("getTableOf(owner)"),
            "placeDebugTableAt 只能绕过方块占用检测，必须保留玩家已在其他桌的保护");
    }

    @Test
    void placeDebugTableAt保留区块预加载() throws IOException {
        String source = Files.readString(MANAGER);
        String body = extractMethodBody(source, "public GameTable placeDebugTableAt(");
        assertTrue(body.contains("ensureChunkReady("),
            "placeDebugTableAt 必须保留区块预加载，否则实体生成会失败");
    }

    @Test
    void placeDebugTableAt保留残留实体清理() throws IOException {
        String source = Files.readString(MANAGER);
        String body = extractMethodBody(source, "public GameTable placeDebugTableAt(");
        assertTrue(body.contains("purgeResidualWorldArtifacts("),
            "placeDebugTableAt 必须保留残留实体清理，否则会在重复坐标累积实体");
    }

    @Test
    void placeDebugTableAt沿用持久化隔离入口() throws IOException {
        String source = Files.readString(MANAGER);
        String pluginSource = Files.readString(Path.of(
            "src/main/java/linmumua/doudizhu/DoudizhuPlugin.java"));
        String body = extractMethodBody(source, "public GameTable placeDebugTableAt(");
        assertTrue(body.contains("persistDoudizhuTable("),
            "placeDebugTableAt 应沿用统一持久化调用，保持正式放桌流程一致");
        assertTrue(pluginSource.contains("isDebugTableName(tableName)"),
            "debug- 测试桌必须由持久化层隔离，不得写入数据库");
    }

    // ── 正式入口必须继续做 placementObstruction ──

    @Test
    void placeNewTableInternal仍做方块占用检测() throws IOException {
        String source = Files.readString(MANAGER);
        String body = extractMethodBody(source, "private GameTable placeNewTableInternal(");
        assertTrue(body.contains("placementObstructionReason("),
            "placeNewTableInternal 正式入口必须继续做 placementObstruction 检测");
    }

    @Test
    void placeNewTableInternal仍检查玩家已在桌() throws IOException {
        String source = Files.readString(MANAGER);
        String body = extractMethodBody(source, "private GameTable placeNewTableInternal(");
        assertTrue(body.contains("getTableOf(owner)"),
            "placeNewTableInternal 正式入口必须继续检查玩家是否已在其他牌桌");
    }

    // ── /muz debug add 必须走 placeDebugTableAt ──

    @Test
    void debugAdd命令调用placeDebugTableAt() throws IOException {
        String source = Files.readString(COMMAND);
        // 定位到 debug add 分支
        int addBlock = source.indexOf("args[1].equalsIgnoreCase(\"add\")");
        assertTrue(addBlock > 0, "命令源码中必须有 debug add 分支");
        // 取 add 分支到下一个 else 的范围
        String addBody = source.substring(addBlock, source.indexOf("} else if", addBlock));
        assertTrue(addBody.contains("placeDebugTableAt("),
            "/muz debug add 必须调用 placeDebugTableAt，不能走 placeNewTableAt");
        assertFalse(addBody.contains("placeNewTableAt("),
            "/muz debug add 不应调用 placeNewTableAt——调试放桌需要绕过方块占用检测");
    }

    /**
     * 从源码中提取方法体（从方法签名到第一个同级 } 的粗略匹配）。
     * 只用于源码扫描断言，不用于运行期。
     */
    private static String extractMethodBody(String source, String signature) {
        int start = source.indexOf(signature);
        assertTrue(start >= 0, "未找到方法签名: " + signature);
        int braceStart = source.indexOf('{', start);
        assertTrue(braceStart > start, "方法签名后未找到开大括号");
        int depth = 1;
        int pos = braceStart + 1;
        while (pos < source.length() && depth > 0) {
            char c = source.charAt(pos);
            if (c == '{') depth++;
            else if (c == '}') depth--;
            pos++;
        }
        return source.substring(braceStart, pos);
    }
}
