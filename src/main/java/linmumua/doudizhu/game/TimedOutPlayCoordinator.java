package linmumua.doudizhu.game;

import linmumua.doudizhu.DoudizhuPlugin;
import linmumua.doudizhu.ai.AiChatGateway;
import linmumua.doudizhu.model.DoudizhuCard;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

final class TimedOutPlayCoordinator {
    interface Support {
        DoudizhuPlugin plugin();
        boolean canScheduleTasks();
        int botActionEpoch();
        GamePhase phase();
        UUID currentTurn();
        boolean isBot(UUID playerId);
        UUID leadPlayer();
        boolean isDeepseekAiEnabled();
        AiChatGateway aiGateway();
        String aiModelName();
        int botAiTimeoutMs();
        String timedOutPlayAiSystemPrompt();
        String buildTimedOutPlayAiPrompt(UUID playerId);
        List<DoudizhuCard> parseAiPlayDecision(UUID playerId, AiChatGateway.ChatResponse response);
        void executeDefaultTimedOutPlayDecision(UUID playerId);
        void performTimedOutPass(UUID playerId);
        void performTimedOutPlay(UUID playerId, List<DoudizhuCard> move);
    }

    private final Support support;
    private UUID pendingPlayer;
    private int pendingEpoch = Integer.MIN_VALUE;

    TimedOutPlayCoordinator(Support support) {
        this.support = support;
    }

    void reset() {
        pendingPlayer = null;
        pendingEpoch = Integer.MIN_VALUE;
    }

    void handleTimedOutPlayerTurn(UUID playerId, int epoch) {
        if (pendingPlayer != null && pendingPlayer.equals(playerId) && pendingEpoch == epoch) {
            return;
        }
        pendingPlayer = playerId;
        pendingEpoch = epoch;
        if (requestAiTimedOutPlayDecision(playerId, epoch)) {
            return;
        }
        clearPending(playerId, epoch);
        support.executeDefaultTimedOutPlayDecision(playerId);
    }

    private boolean requestAiTimedOutPlayDecision(UUID playerId, int epoch) {
        AiChatGateway gateway = support.aiGateway();
        if (!support.isDeepseekAiEnabled() || gateway == null || !gateway.isEnabled()) {
            return false;
        }
        String prompt = support.buildTimedOutPlayAiPrompt(playerId);
        gateway.chatAsync(new AiChatGateway.ChatRequest(
                List.of(
                    AiChatGateway.Message.system(support.timedOutPlayAiSystemPrompt()),
                    AiChatGateway.Message.user(prompt)
                ),
                support.aiModelName(),
                0.2,
                140
            ))
            .orTimeout(support.botAiTimeoutMs(), TimeUnit.MILLISECONDS)
            .whenComplete((response, error) -> support.plugin().scheduler().runSync(() -> {
                if (!isDecisionStillValid(playerId, epoch)) {
                    clearPending(playerId, epoch);
                    return;
                }
                List<DoudizhuCard> aiMove = error == null ? support.parseAiPlayDecision(playerId, response) : null;
                if (aiMove == null) {
                    clearPending(playerId, epoch);
                    support.executeDefaultTimedOutPlayDecision(playerId);
                    return;
                }
                if (aiMove.isEmpty()) {
                    if (support.leadPlayer() == null || Objects.equals(support.leadPlayer(), playerId)) {
                        clearPending(playerId, epoch);
                        support.executeDefaultTimedOutPlayDecision(playerId);
                        return;
                    }
                    clearPending(playerId, epoch);
                    support.performTimedOutPass(playerId);
                    return;
                }
                clearPending(playerId, epoch);
                support.performTimedOutPlay(playerId, aiMove);
            }));
        return true;
    }

    private boolean isDecisionStillValid(UUID playerId, int epoch) {
        return support.canScheduleTasks()
            && epoch == support.botActionEpoch()
            && support.phase() == GamePhase.PLAYING
            && support.currentTurn() != null
            && Objects.equals(support.currentTurn(), playerId)
            && !support.isBot(playerId);
    }

    private void clearPending(UUID playerId, int epoch) {
        if (pendingPlayer != null && pendingPlayer.equals(playerId) && pendingEpoch == epoch) {
            pendingPlayer = null;
            pendingEpoch = Integer.MIN_VALUE;
        }
    }
}
