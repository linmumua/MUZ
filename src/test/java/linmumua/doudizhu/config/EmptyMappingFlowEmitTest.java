package linmumua.doudizhu.config;

import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * 锁定「空映射会走 SnakeYAML FLOW 分支」这一事实，以及启动预热必须覆盖该分支。
 *
 * <p>savePlayerSettings() 先写入空的 players 映射再逐个填充玩家条目。当没有任何玩家设置时，
 * 该映射保持为空，SnakeYAML 无视全局 BLOCK 样式、按花括号输出，从而首次触碰
 * Emitter 的 FLOW 内部类（ExpectFirstFlowMappingKey）。
 *
 * <p>这在 onDisable 阶段是致命的：Paper 此时已停止为插件 ClassLoader 提供新类，
 * 首次加载会抛 NoClassDefFoundError，导致玩家设置静默丢失。因此这些类必须在启动阶段就加载过一次。
 */
class EmptyMappingFlowEmitTest {

    @Test
    void emptyMappingIsEmittedAsInlineFlowSoItTouchesFlowEmitterClasses(@TempDir Path dir) throws IOException {
        Path file = dir.resolve("player-settings.yml");
        MuzYamlConfig configuration = MuzYamlConfig.empty(file);

        // 与 savePlayerSettings() 中「无玩家数据」的情形一致
        configuration.set("players", new LinkedHashMap<String, Object>());
        configuration.save();

        String dumped = Files.readString(file);
        // prettyFlow=true 会把空映射排成 "{\n  }"，但花括号本身证明走的是 FLOW 分支。
        assertTrue(
                dumped.contains("{"),
                "空映射应按 FLOW 样式输出（含花括号），实际内容: " + dumped);
    }

    @Test
    void warmUpExercisesTheSameFlowBranchThatShutdownSavesNeed() {
        // 预热必须真的把 FLOW 输出跑一遍（而不是空转），这样关服写入时相关 Emitter
        // 内部类已在 ClassLoader 中，不会在 onDisable 阶段首次加载失败。
        String warmed = MuzYamlConfig.warmUpFlowEmitter();

        // 花括号是 FLOW 样式的标记；缺了它说明预热没覆盖关服写入实际走的分支。
        assertTrue(
                warmed.contains("{"),
                "预热应当产生 FLOW 样式输出（含花括号），实际内容: " + warmed);
    }
}
