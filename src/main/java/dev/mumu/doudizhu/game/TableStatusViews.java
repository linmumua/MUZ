package dev.mumu.doudizhu.game;

import dev.mumu.doudizhu.model.CardPattern;
import dev.mumu.doudizhu.model.DoudizhuCard;
import dev.mumu.doudizhu.ui.MuzTheme;
import java.util.List;
import java.util.UUID;
import java.util.function.BiFunction;
import java.util.function.Function;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;

final class TableStatusViews {
    private TableStatusViews() {
    }

    static String multiplierStatusText(
        GamePhase phase,
        int highestBid,
        UUID landlord,
        int bombMultiplier,
        int boostedFarmerCount,
        int farmerSeatCount,
        Integer landlordBoostFactor,
        String pairMultiplierSummary
    ) {
        if (highestBid <= 0 || landlord == null) {
            return phase == GamePhase.LOBBY ? "等待本局开局" : "等待叫分结果";
        }
        StringBuilder builder = new StringBuilder()
            .append("底分 ").append(Math.max(1, highestBid))
            .append(" · 炸弹 x").append(bombMultiplier)
            .append(" · 农民加倍 ").append(boostedFarmerCount).append("/").append(farmerSeatCount).append(" 人");
        if (landlordBoostFactor != null) {
            builder.append(landlordBoostFactor > 1 ? " · 地主加倍 x" + landlordBoostFactor : " · 地主不加倍");
        }
        builder.append(" · ").append(pairMultiplierSummary);
        return builder.toString();
    }

    static Component multiplierStatusComponent(
        GamePhase phase,
        int highestBid,
        UUID landlord,
        int bombMultiplier,
        int boostedFarmerCount,
        int farmerSeatCount,
        Integer landlordBoostFactor,
        String pairMultiplierSummary
    ) {
        if (highestBid <= 0 || landlord == null) {
            return phase == GamePhase.LOBBY ? MuzTheme.muted("等待本局开局") : MuzTheme.muted("等待叫分结果");
        }
        Component line = MuzTheme.warm("底分 " + Math.max(1, highestBid))
            .append(MuzTheme.divider(" · "))
            .append(MuzTheme.hotValue("x" + bombMultiplier))
            .append(MuzTheme.divider(" · "))
            .append(MuzTheme.hotMetric("农民加倍", boostedFarmerCount + "/" + farmerSeatCount, "人"));
        if (landlordBoostFactor != null) {
            line = line.append(MuzTheme.divider(" · "))
                .append(landlordBoostFactor > 1 ? MuzTheme.hotMetric("地主加倍", "x" + landlordBoostFactor) : MuzTheme.muted("地主不加倍"));
        }
        return line.append(MuzTheme.divider(" · "))
            .append(MuzTheme.hotValue(pairMultiplierSummary));
    }

    static String currentTrickPreviewText(
        UUID leadPlayer,
        CardPattern currentPattern,
        List<DoudizhuCard> currentTrickCards,
        Function<UUID, String> playerName,
        BiFunction<List<DoudizhuCard>, CardPattern, String> describeCards
    ) {
        if (currentPattern == null || currentTrickCards.isEmpty()) {
            return "上一手 · 暂无";
        }
        return "上一手 · " + playerName.apply(leadPlayer) + " · " + describeCards.apply(currentTrickCards, currentPattern);
    }

    static Component currentTrickPreviewComponent(
        UUID leadPlayer,
        CardPattern currentPattern,
        List<DoudizhuCard> currentTrickCards,
        Function<UUID, Component> identity,
        BiFunction<List<DoudizhuCard>, CardPattern, String> describeCards
    ) {
        if (currentPattern == null || currentTrickCards.isEmpty()) {
            return MuzTheme.orange("上一手")
                .append(MuzTheme.divider(" · "))
                .append(MuzTheme.muted("暂未出现"));
        }
        return MuzTheme.orange("上一手")
            .append(MuzTheme.divider(" · "))
            .append(identity.apply(leadPlayer))
            .append(MuzTheme.divider(" · "))
            .append(MuzTheme.orange(describeCards.apply(currentTrickCards, currentPattern)));
    }

    static Component lobbyActionBar(int seatCount, int playerCount, List<String> unreadyNames) {
        if (seatCount == 0) {
            return Component.text("等待玩家加入。", NamedTextColor.GRAY);
        }
        if (seatCount < playerCount) {
            return Component.text("等待更多玩家入座 · " + seatCount + "/" + playerCount, NamedTextColor.GRAY);
        }
        if (unreadyNames.isEmpty()) {
            return MuzTheme.success("全员就绪").append(MuzTheme.divider(" · ")).append(MuzTheme.muted("任意一位可开始"));
        }
        return Component.text("未准备：", NamedTextColor.YELLOW)
            .append(MuzTheme.warning(String.join("、", unreadyNames)));
    }

    static Component persistentActionBar(
        GamePhase phase,
        UUID viewerId,
        UUID currentTurn,
        boolean currentTurnIsBot,
        int bidRound,
        int remainingSeconds,
        int selectedCount,
        Function<UUID, Component> identity,
        Component lobbyActionBar,
        int currentTurnTimeoutSeconds
    ) {
        if (phase == GamePhase.LOBBY) {
            return lobbyActionBar;
        }
        if (currentTurn == null) {
            return Component.text("牌桌正在整理下一轮。", NamedTextColor.GRAY);
        }
        if (currentTurnIsBot) {
            return Component.text("当前由 ", NamedTextColor.GRAY)
                .append(identity.apply(currentTurn))
                .append(Component.text(" 正在思考。", NamedTextColor.GRAY));
        }
        String countdown = currentTurnTimeoutSeconds > 0 ? " | " + remainingSeconds + " 秒" : "";
        return switch (phase) {
            case BIDDING -> viewerId.equals(currentTurn)
                ? Component.text((bidRound == 1 ? "轮到你定叫分" : "轮到你抢地主") + " · 点桌边按钮确认" + countdown, NamedTextColor.AQUA)
                : Component.text("当前由 ", NamedTextColor.GRAY)
                    .append(identity.apply(currentTurn))
                    .append(Component.text((bidRound == 1 ? " 正在定叫分" : " 正在抢地主") + countdown, NamedTextColor.GRAY));
            case DOUBLING -> viewerId.equals(currentTurn)
                ? Component.text("轮到你决定加倍或不加倍 · 6 秒内点桌边按钮" + countdown, NamedTextColor.AQUA)
                : Component.text("当前由 ", NamedTextColor.GRAY)
                    .append(identity.apply(currentTurn))
                    .append(Component.text(" 正在决定是否加倍" + countdown, NamedTextColor.GRAY));
            case PLAYING -> viewerId.equals(currentTurn)
                ? Component.text("轮到你出牌了 · 已选 " + selectedCount + " 张" + countdown, NamedTextColor.AQUA)
                : Component.text("当前由 ", NamedTextColor.GRAY)
                    .append(identity.apply(currentTurn))
                    .append(Component.text(" 在出牌" + countdown, NamedTextColor.GRAY));
            case LOBBY -> lobbyActionBar;
        };
    }
}
