package linmumua.doudizhu.listener;

import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

/** HUD 调试棒必须先于桌器、手牌与保护逻辑消费右键，并保持正式 HUD 路由。 */
class HudDebugStickListenerContractTest {
    private static final Path LISTENER =
        Path.of("src/main/java/linmumua/doudizhu/listener/WorldTableInteractionListener.java");

    @Test
    void 调试棒路由排在桌器和手牌之前() throws IOException {
        String source = Files.readString(LISTENER);
        String useBody = methodBody(source, "public void onUseTablePlacer(PlayerInteractEvent event)");
        String handBody = methodBody(source, "public void onHandCardClick(PlayerInteractEvent event)");

        assertTrue(useBody.indexOf("isHudDebugStick") < useBody.indexOf("isTablePlacer"),
            "调试棒必须先于桌器/拆桌棍路由消费方块右键");
        assertTrue(handBody.indexOf("isHudDebugStick") < handBody.indexOf("handleHandCardClick"),
            "调试棒必须先于手牌点击与保护逻辑消费事件");
    }

    @Test
    void 调试棒循环和正式阶段路由保持旧语义() throws IOException {
        String source = Files.readString(LISTENER);
        String body = methodBody(source, "private void handleHudDebugStick(Player player)");

        assertTrue(body.contains("cycleHudRowOverride"), "普通右键必须走插件的 1..7 行组合循环");
        assertTrue(body.contains("hideAllHudRows"), "Shift+右键必须把行覆盖设为 0");
        assertTrue(body.contains("GamePhase.PLAYING"), "PLAYING 阶段必须区分正式 HUD 与假预览");
        assertTrue(body.contains("showHudDebugPreview"), "非 PLAYING 阶段必须显示 TrickHudPreview");
        assertTrue(body.contains("describeHudRows"), "右键后必须提示当前可见行");
    }

    @Test
    void 预览和临时覆盖具备清理入口() throws IOException {
        String source = Files.readString(LISTENER);
        assertTrue(source.contains("PlayerItemHeldEvent"), "切换物品时必须清理调试预览");
        assertTrue(source.contains("PlayerQuitEvent"), "退出时必须清理调试预览");
        assertTrue(source.contains("PlayerKickEvent"), "被踢时必须清理调试预览");
        assertTrue(source.contains("clearHudRowOverride"), "离桌/退出必须清理临时行覆盖");
        assertTrue(source.contains("trickHudPreview().hide"), "清理路径必须隐藏 TrickHudPreview");
    }

    @Test
    void 退出和踢出同时清理按钮去重缓存() throws IOException {
        String source = Files.readString(LISTENER);
        String body = methodBody(source, "private void clearHudDebugState(Player player)");
        assertTrue(body.contains("consumedButtonClicks.remove(playerId)"),
            "退出/踢出必须清理按钮去重缓存，避免玩家 UUID 长期滞留");
    }

    private static String methodBody(String source, String signature) {
        int start = source.indexOf(signature);
        assertTrue(start >= 0, "找不到方法: " + signature);
        int open = source.indexOf('{', start);
        assertTrue(open > start, "找不到方法体: " + signature);
        int depth = 0;
        for (int index = open; index < source.length(); index++) {
            char character = source.charAt(index);
            if (character == '{') {
                depth++;
            } else if (character == '}' && --depth == 0) {
                return source.substring(start, index);
            }
        }
        throw new AssertionError("找不到方法结束位置: " + signature);
    }
}
