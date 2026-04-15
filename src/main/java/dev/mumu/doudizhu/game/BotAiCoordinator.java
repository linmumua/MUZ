package dev.mumu.doudizhu.game;

import dev.mumu.doudizhu.DoudizhuPlugin;
import dev.mumu.doudizhu.ai.AiChatGateway;
import dev.mumu.doudizhu.model.DoudizhuCard;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

final class BotAiCoordinator {
    interface Support {
        DoudizhuPlugin plugin();
        boolean canScheduleTasks();
        int botActionEpoch();
        GamePhase phase();
        UUID currentTurn();
        boolean isBot(UUID botId);
        String tableName();
        String aiModelName();
        int botAiTimeoutMs();
        boolean isBotAiEnabled();
        AiChatGateway aiGateway();
        String botAiSystemPrompt();
        String buildBidAiPrompt(UUID botId);
        String buildDoublingAiPrompt(UUID botId);
        String buildPlayAiPrompt(UUID botId);
        Integer parseAiBidDecision(AiChatGateway.ChatResponse response);
        String parseAiKeywordDecision(AiChatGateway.ChatResponse response);
        List<DoudizhuCard> parseAiPlayDecision(UUID botId, AiChatGateway.ChatResponse response);
        void recordTrace(UUID botId, String stage, String prompt, AiChatGateway.ChatResponse response, String parsedDecision, boolean applied, String fallbackReason, String errorMessage);
        int normalizeBidDecision(int points);
        void executeLocalBotBid(UUID botId);
        void executeLocalBotDouble(UUID botId);
        void executeLocalBotPlay(UUID botId);
        void processBidChoice(UUID botId, int points);
        void processDoublingChoice(UUID botId, int boostFactor, boolean autoSkipped);
        void applyBotMove(UUID botId, List<DoudizhuCard> move);
        void performBotPass(UUID botId);
    }

    private final Support support;

    BotAiCoordinator(Support support) {
        this.support = support;
    }

    boolean requestBidDecision(UUID botId, int epoch) {
        AiChatGateway gateway = support.aiGateway();
        if (!support.isBotAiEnabled() || gateway == null) {
            return false;
        }
        String prompt = support.buildBidAiPrompt(botId);
        gateway.chatAsync(new AiChatGateway.ChatRequest(
                List.of(
                    AiChatGateway.Message.system(support.botAiSystemPrompt()),
                    AiChatGateway.Message.user(prompt)
                ),
                support.aiModelName(),
                0.2,
                80
            ))
            .orTimeout(support.botAiTimeoutMs(), TimeUnit.MILLISECONDS)
            .whenComplete((response, error) -> support.plugin().getServer().getScheduler().runTask(support.plugin(), () -> {
                if (!isDecisionStillValid(botId, epoch, GamePhase.BIDDING)) {
                    return;
                }
                Integer parsed = error == null ? support.parseAiBidDecision(response) : null;
                support.recordTrace(botId, "BIDDING", prompt, response, parsed == null ? "" : String.valueOf(parsed), parsed != null, parsed == null ? "fallback_local" : "", error == null ? "" : error.getMessage());
                if (parsed == null) {
                    support.executeLocalBotBid(botId);
                    return;
                }
                support.processBidChoice(botId, support.normalizeBidDecision(parsed));
            }));
        return true;
    }

    boolean requestDoublingDecision(UUID botId, int epoch) {
        AiChatGateway gateway = support.aiGateway();
        if (!support.isBotAiEnabled() || gateway == null) {
            return false;
        }
        String prompt = support.buildDoublingAiPrompt(botId);
        gateway.chatAsync(new AiChatGateway.ChatRequest(
                List.of(
                    AiChatGateway.Message.system(support.botAiSystemPrompt()),
                    AiChatGateway.Message.user(prompt)
                ),
                support.aiModelName(),
                0.2,
                100
            ))
            .orTimeout(support.botAiTimeoutMs(), TimeUnit.MILLISECONDS)
            .whenComplete((response, error) -> support.plugin().getServer().getScheduler().runTask(support.plugin(), () -> {
                if (!isDecisionStillValid(botId, epoch, GamePhase.DOUBLING)) {
                    return;
                }
                String parsed = error == null ? support.parseAiKeywordDecision(response) : null;
                support.recordTrace(botId, "DOUBLING", prompt, response, parsed == null ? "" : parsed, parsed != null, parsed == null ? "fallback_local" : "", error == null ? "" : error.getMessage());
                if (parsed == null) {
                    support.executeLocalBotDouble(botId);
                    return;
                }
                support.processDoublingChoice(botId, "DOUBLE".equals(parsed) ? 2 : 1, false);
            }));
        return true;
    }

    boolean requestPlayDecision(UUID botId, int epoch) {
        AiChatGateway gateway = support.aiGateway();
        if (!support.isBotAiEnabled() || gateway == null) {
            return false;
        }
        String prompt = support.buildPlayAiPrompt(botId);
        gateway.chatAsync(new AiChatGateway.ChatRequest(
                List.of(
                    AiChatGateway.Message.system(support.botAiSystemPrompt()),
                    AiChatGateway.Message.user(prompt)
                ),
                support.aiModelName(),
                0.2,
                140
            ))
            .orTimeout(support.botAiTimeoutMs(), TimeUnit.MILLISECONDS)
            .whenComplete((response, error) -> support.plugin().getServer().getScheduler().runTask(support.plugin(), () -> {
                if (!isDecisionStillValid(botId, epoch, GamePhase.PLAYING)) {
                    return;
                }
                List<DoudizhuCard> aiMove = error == null ? support.parseAiPlayDecision(botId, response) : null;
                String parsedDecision = aiMove == null
                    ? ""
                    : aiMove.isEmpty()
                        ? "PASS"
                        : aiMove.stream().map(card -> Integer.toString(card.id())).collect(Collectors.joining(","));
                support.recordTrace(botId, "PLAYING", prompt, response, parsedDecision, aiMove != null, aiMove == null ? "fallback_local" : "", error == null ? "" : error.getMessage());
                if (aiMove == null) {
                    support.executeLocalBotPlay(botId);
                    return;
                }
                if (aiMove.isEmpty()) {
                    support.performBotPass(botId);
                    return;
                }
                support.applyBotMove(botId, aiMove);
            }));
        return true;
    }

    private boolean isDecisionStillValid(UUID botId, int epoch, GamePhase expectedPhase) {
        return support.canScheduleTasks()
            && epoch == support.botActionEpoch()
            && support.phase() == expectedPhase
            && support.currentTurn() != null
            && Objects.equals(support.currentTurn(), botId)
            && support.isBot(botId);
    }
}
