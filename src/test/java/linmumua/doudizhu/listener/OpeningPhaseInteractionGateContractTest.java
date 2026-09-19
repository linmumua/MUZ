package linmumua.doudizhu.listener;

import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

/** 新增开局阶段只能由核心状态机推进，外层入口不得把手牌或旧动作放行。 */
class OpeningPhaseInteractionGateContractTest {
    private static final Path WORLD_LISTENER =
        Path.of("src/main/java/linmumua/doudizhu/listener/WorldTableInteractionListener.java");
    private static final Path CE_LISTENER =
        Path.of("src/main/java/linmumua/doudizhu/listener/CraftEngineProtectionListener.java");
    private static final Path COMMAND =
        Path.of("src/main/java/linmumua/doudizhu/command/DoudizhuCommand.java");
    private static final Path STATUS =
        Path.of("src/main/java/linmumua/doudizhu/game/TableStatusViews.java");

    @Test
    void 世界交互入口统一拒绝发牌和明牌窗口的手牌点击() throws IOException {
        String source = Files.readString(WORLD_LISTENER);
        assertTrue(source.contains("private boolean isHandInteractionBlocked(Player player)"));
        assertTrue(source.contains("phase == GamePhase.DEALING || phase == GamePhase.REVEALING"));
        assertTrue(count(source, "!isHandInteractionBlocked(event.getPlayer())") >= 2,
            "实体右键和实体 AT 两条手牌路径都必须经过阶段门");
        assertTrue(source.contains("!isHandInteractionBlocked(damager)"),
            "左键出牌路径也必须经过阶段门");
    }

    @Test
    void ce家具入口不能在新增阶段抢走手牌点击() throws IOException {
        String source = Files.readString(CE_LISTENER);
        assertTrue(source.contains("private boolean isHandInteractionBlocked(Player player)"));
        assertTrue(source.contains("player == null || base == null || isHandInteractionBlocked(player)"));
    }

    @Test
    void 状态命令在新增阶段不调用可能包含底牌的完整状态() throws IOException {
        String source = Files.readString(COMMAND);
        int status = source.indexOf("case \"status\"");
        int buildStatus = source.indexOf("ddzTable.buildStatusLines()", status);
        assertTrue(status >= 0 && buildStatus > status);
        String gate = source.substring(status, buildStatus);
        assertTrue(gate.contains("GamePhase.DEALING"));
        assertTrue(gate.contains("GamePhase.REVEALING"));
        assertTrue(gate.contains("完整手牌和底牌不会在此处展示"));
    }

    @Test
    void 状态栏显式覆盖两个新增阶段() throws IOException {
        String source = Files.readString(STATUS);
        assertTrue(source.contains("case DEALING"));
        assertTrue(source.contains("case REVEALING"));
        assertTrue(source.contains("GamePhase.DEALING"));
        assertTrue(source.contains("GamePhase.REVEALING"));
    }

    private static int count(String source, String token) {
        int count = 0;
        int offset = 0;
        while ((offset = source.indexOf(token, offset)) >= 0) {
            count++;
            offset += token.length();
        }
        return count;
    }
}
