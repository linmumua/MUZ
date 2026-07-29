package linmumua.doudizhu.game;

import linmumua.doudizhu.ai.AiChatGateway;
import linmumua.doudizhu.model.CardPattern;
import linmumua.doudizhu.model.CardRank;
import linmumua.doudizhu.model.DoudizhuCard;
import linmumua.doudizhu.model.PatternAnalyzer;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.stream.Collectors;

final class BotAiDecisionCodec {
    private BotAiDecisionCodec() {
    }

    static String botSystemPrompt() {
        return "你是 MUZ 斗地主实战决策引擎。"
            + "你必须冷静、稳健、重视资源管理。"
            + "系统可能已经额外提供全局人设词，你必须与当前规则共同遵守。"
            + "你只能根据给出的牌桌信息做决策。"
            + "你必须只输出要求的结果格式，不要解释，不要寒暄，不要额外标点。"
            + "如果拿不准，也必须在允许格式里给出一个保守、合法、可执行的答案。";
    }

    static String timedOutSystemPrompt() {
        return "你是 MUZ 斗地主超时托管决策引擎。"
            + "你现在要替一名超时玩家做最稳妥的自动出牌。"
            + "系统可能已经额外提供全局人设词，你必须与当前规则共同遵守。"
            + "你必须优先保证合法、稳健、降低失误，不要为了牌面好看乱炸。"
            + "你必须只输出 PASS 或者手牌 id 列表，不要解释。";
    }

    static String buildBidPrompt(String botName, int bidRound, int highestBid, List<DoudizhuCard> hand) {
        String roundText = bidRound == 1 ? "第一轮叫分" : "同分加赛抢地主";
        return """
            当前阶段：%s
            你的名字：%s
            当前最高分：%d
            你的手牌：%s
            手牌摘要：%s
            只输出一个数字：0 或 1 或 2 或 3
            决策原则：
            - 不想叫分就输出 0
            - 第一轮时，非零分不能低于当前最高分
            - 有高牌、炸弹、王炸时才适合更积极
            - 不要为了气势乱叫高分，稳健优先
            - 不要输出任何解释
            """.formatted(roundText, botName, highestBid, handSummary(hand), handFeatureSummary(hand));
    }

    static String buildDoublingPrompt(String botName, boolean landlord, int baseMultiplier, List<DoudizhuCard> hand) {
        return """
            当前阶段：加倍决策
            你的名字：%s
            你的身份：%s
            当前基础倍率：%d
            你的手牌：%s
            手牌摘要：%s
            只输出一个词：PASS 或 DOUBLE
            决策原则：
            - PASS 表示不加倍
            - DOUBLE 表示加倍
            - 只有在牌力明显强、节奏主动、资源充足时再加倍
            - 不要为了气势乱加倍
            - 不要输出解释
            """.formatted(botName, landlord ? "地主" : "农民", baseMultiplier, handSummary(hand), handFeatureSummary(hand));
    }

    static String buildPlayPrompt(
        String botName,
        String identityText,
        String tableStateText,
        String leadText,
        String targetText,
        List<DoudizhuCard> hand,
        String conservativePlanText
    ) {
        return """
            当前阶段：出牌
            你的名字：%s
            你的身份：%s
            当前先手：%s
            上一手：%s
            牌桌态势：
            %s
            本地保守建议：%s
            你的手牌：
            %s
            手牌摘要：%s
            只允许两种输出：
            1. PASS
            2. 只输出手牌 id，用英文逗号分隔，例如：12,18
            决策原则：
            - 如果你是先手，不能输出 PASS
            - 只能从你当前手牌里选 id
            - 必须保证选出的牌是合法牌型；如果要压上一手，必须能压过
            - 非必要不要先手开炸弹或王炸
            - 普通牌能压住就不要交炸弹；炸弹能解决就不要升级到王炸
            - 只有在以下场景才提高炸弹/王炸优先级：对手马上出完、这一手不压会严重失控、或你已经接近收尾
            - 农民要优先阻止地主冲刺；地主要优先压制剩牌最少的农民
            - 如果拿不准，优先参考“本地保守建议”并保持稳健
            - 不要输出任何解释
            """.formatted(botName, identityText, leadText, targetText, tableStateText, conservativePlanText, handSummaryLines(hand), handFeatureSummary(hand));
    }

    static String buildTimedOutPlayPrompt(
        String playerName,
        String identityText,
        String tableStateText,
        String targetText,
        List<DoudizhuCard> hand,
        String conservativePlanText
    ) {
        return """
            当前阶段：玩家超时托管出牌
            玩家名字：%s
            玩家身份：%s
            上一手：%s
            牌桌态势：
            %s
            本地保守建议：%s
            当前手牌：
            %s
            手牌摘要：%s
            只允许两种输出：
            1. PASS
            2. 只输出手牌 id，用英文逗号分隔，例如：12,18
            决策原则：
            - 如果你是先手，不能输出 PASS
            - 你必须选择当前最稳妥、最不容易犯错的一手
            - 非必要不要动用炸弹或王炸
            - 对手剩牌很少时，阻止其冲刺优先级更高
            - 如果拿不准，优先采用“本地保守建议”
            - 不要输出解释
            """.formatted(playerName, identityText, targetText, tableStateText, conservativePlanText, handSummaryLines(hand), handFeatureSummary(hand));
    }

    static Integer parseBidDecision(AiChatGateway.ChatResponse response) {
        String content = decisionContent(response);
        if (content.isBlank()) {
            return null;
        }
        for (String token : content.replaceAll("[^0-3]", " ").trim().split("\\s+")) {
            if (token.matches("[0-3]")) {
                return Integer.parseInt(token);
            }
        }
        return null;
    }

    static String parseKeywordDecision(AiChatGateway.ChatResponse response) {
        String content = decisionContent(response).toUpperCase(Locale.ROOT).replace('-', '_').replace(' ', '_');
        if (content.contains("DOUBLE")) {
            return "DOUBLE";
        }
        if (content.contains("PASS")) {
            return "PASS";
        }
        return null;
    }

    static List<DoudizhuCard> parsePlayDecision(UUIDOwnerHand ownerHand, CardPattern currentPattern, UUIDHolder leadHolder, AiChatGateway.ChatResponse response) {
        String content = decisionContent(response).trim();
        if (content.isBlank()) {
            return null;
        }
        if (content.toUpperCase(Locale.ROOT).contains("PASS")) {
            return List.of();
        }
        List<Integer> ids = new ArrayList<>();
        for (String token : content.replaceAll("[^0-9,]", "").split(",")) {
            if (!token.isBlank()) {
                try {
                    ids.add(Integer.parseInt(token));
                } catch (NumberFormatException ignored) {
                }
            }
        }
        if (ids.isEmpty()) {
            return null;
        }
        List<DoudizhuCard> hand = new ArrayList<>(ownerHand.hand());
        List<DoudizhuCard> chosen = new ArrayList<>();
        for (Integer id : ids) {
            DoudizhuCard matched = hand.stream().filter(card -> card.id() == id).findFirst().orElse(null);
            if (matched == null) {
                return null;
            }
            chosen.add(matched);
            hand.remove(matched);
        }
        CardPattern analyzed = PatternAnalyzer.analyze(chosen).orElse(null);
        if (analyzed == null) {
            return null;
        }
        if (leadHolder.leadPlayer() != null && !Objects.equals(leadHolder.leadPlayer(), ownerHand.owner()) && currentPattern != null && !analyzed.canBeat(currentPattern)) {
            return null;
        }
        return chosen;
    }

    private static String handSummary(List<DoudizhuCard> hand) {
        return hand.stream()
            .map(card -> card.id() + ":" + card.displayLabel())
            .collect(Collectors.joining(" "));
    }

    private static String handSummaryLines(List<DoudizhuCard> hand) {
        return hand.stream()
            .map(card -> "- " + card.id() + " = " + card.displayLabel())
            .collect(Collectors.joining("\n"));
    }

    private static String handFeatureSummary(List<DoudizhuCard> hand) {
        Map<CardRank, Integer> counts = new EnumMap<>(CardRank.class);
        for (DoudizhuCard card : hand) {
            counts.merge(card.rank(), 1, Integer::sum);
        }
        long highCards = hand.stream().filter(card -> card.rank().strength() >= CardRank.ACE.strength()).count();
        long pairs = counts.values().stream().filter(count -> count == 2).count();
        long triples = counts.values().stream().filter(count -> count == 3).count();
        long bombs = counts.values().stream().filter(count -> count == 4).count();
        boolean jokerBomb = counts.containsKey(CardRank.SMALL_JOKER) && counts.containsKey(CardRank.BIG_JOKER);
        return "高牌 " + highCards + " 张，对子 " + pairs + " 组，三张 " + triples + " 组，炸弹 " + bombs + " 个"
            + (jokerBomb ? "，有王炸" : "，无王炸");
    }

    private static String decisionContent(AiChatGateway.ChatResponse response) {
        if (response == null) {
            return "";
        }
        String content = response.content();
        if (content == null || content.isBlank()) {
            content = response.reasoningContent();
        }
        return content == null ? "" : content.trim();
    }

    record UUIDOwnerHand(UUID owner, List<DoudizhuCard> hand) {
    }

    record UUIDHolder(java.util.UUID leadPlayer) {
    }
}
