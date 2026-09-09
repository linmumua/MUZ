package linmumua.doudizhu.game;

import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

/**
 * 真人无可压等待的源码契约测试：GameTable 构造依赖 Bukkit，避免伪造整桌集成状态。
 */
class GameTableAutoSkipTest {
    private static final Path GAME_TABLE = Path.of("src/main/java/linmumua/doudizhu/game/GameTable.java");

    @Test
    void waitsForHumanAndUsesIndependentTokenBeforeAutoPass() throws Exception {
        String source = Files.readString(GAME_TABLE, StandardCharsets.UTF_8);

        assertTrue(source.contains("plugin.scheduler().runLater(20L"), "真人无可压必须等待 20 tick");
        assertTrue(source.contains("token != noResponsePassToken"), "延迟任务必须进行 token 二次校验");
        assertTrue(source.contains("scheduledNoResponseEpoch != noResponsePassEpoch"), "延迟任务必须校验独立 epoch");
        assertTrue(source.contains("!Objects.equals(currentTurn, playerId)"), "延迟任务必须校验 currentTurn");
        assertTrue(source.contains("!Objects.equals(leadPlayer, scheduledLeadPlayer)"), "延迟任务必须校验 leadPlayer");
        assertTrue(source.contains("!Objects.equals(currentPattern, scheduledPattern)"), "延迟任务必须校验 currentPattern");
        assertTrue(source.contains("isBot(playerId)"), "延迟任务必须拒绝机器人回合");
        assertTrue(source.contains("MoveAdvisor.hasAnyBeatingMove(hand, currentPattern)"), "必须继续使用现有 MoveAdvisor 判定");
        assertTrue(source.contains("cancelPendingNoResponsePass()"), "状态结束和玩家响应必须取消等待任务");
        assertTrue(source.contains("没有能压过上一手，1 秒后自动不要；可点「不要」立即跳过。"), "必须提示真人等待期间可手动不要");
        assertTrue(source.contains("!(scheduledPlayer != null && scheduledPlayer.isOnline())"), "安排等待任务必须统一判断在线状态");
        assertTrue(source.contains("Player onlinePlayer = GameTable.this.onlinePlayer(playerId);"), "延迟回调必须只获取一次在线玩家");
    }

    @Test
    void keepsPersistentActionBarFlowDuringPendingHumanPass() throws Exception {
        String source = Files.readString(GAME_TABLE, StandardCharsets.UTF_8);

        int broadcastIndex = source.indexOf("broadcastPersistentActionBar(remaining);");
        int pendingHintIndex = source.indexOf("if (noResponsePassPending) {");
        assertTrue(broadcastIndex >= 0, "必须保留正常 ActionBar 与倒计时广播");
        assertTrue(pendingHintIndex > broadcastIndex, "无可压提示必须追加在正常广播之后");
        assertTrue(!source.contains("sendNoResponseHintIfPending();\n            return;"), "无可压提示不能阻断其他玩家的 ActionBar 与倒计时");
        assertTrue(source.contains("当前真人额外显示无可压提示；其他玩家仍沿用正常 ActionBar 与倒计时。"), "必须说明 pending 期间其他玩家仍正常更新");
    }

    @Test
    void autoPassCallbackUsesSingleNormalContinuationPath() throws Exception {
        String source = Files.readString(GAME_TABLE, StandardCharsets.UTF_8);

        int callbackStart = source.indexOf("pendingNoResponsePassTask = plugin.scheduler().runLater(20L");
        int callbackEnd = source.indexOf("\n    private boolean shouldAutoPassCurrentTurn()", callbackStart);
        assertTrue(callbackStart >= 0 && callbackEnd > callbackStart, "必须找到真人自动不要回调");
        String callback = source.substring(callbackStart, callbackEnd);
        assertTrue(callback.contains("performAutoSkippedPass(playerId);"), "回调必须执行一次自动不要");
        assertTrue(callback.contains("promptPlayTurn();\n            runBotActionIfNeeded();"), "自动不要后必须走一次正常回合续接链路");
        assertTrue(!callback.contains("refreshPhysicalTable();"), "自动不要回调不得重复刷新物理牌桌");
    }

    @Test
    void keepsBotImmediatePathSeparateFromHumanDelayedPath() throws Exception {
        String source = Files.readString(GAME_TABLE, StandardCharsets.UTF_8);

        assertTrue(source.contains("while (shouldAutoPassCurrentTurn() && isBot(currentTurn))"), "机器人仍应走立即自动不要路径");
        assertTrue(source.contains("!shouldAutoPassCurrentTurn() || currentTurn == null || isBot(currentTurn)"), "延迟路径必须排除机器人");
    }

    @Test
    void refreshesPhysicalTableOnlyThroughPromptPlayTurnAfterResolvedTurn() throws Exception {
        String source = Files.readString(GAME_TABLE, StandardCharsets.UTF_8);

        int methodStart = source.indexOf("private void advanceAfterResolvedTurn(UUID playerId, boolean continueFlow)");
        int methodEnd = source.indexOf("\n    private void executeDefaultTimedOutPlayDecision", methodStart);
        assertTrue(methodStart >= 0 && methodEnd > methodStart, "必须找到普通回合推进方法");
        String method = source.substring(methodStart, methodEnd);
        assertTrue(method.contains("promptPlayTurn();\n        runBotActionIfNeeded();"), "普通出牌/不要后必须经 promptPlayTurn 刷新并继续机器人流程");
        assertTrue(!method.contains("refreshPhysicalTable();"), "promptPlayTurn 已包含 refreshPhysicalTable，普通回合推进不得重复刷新");
    }
}
