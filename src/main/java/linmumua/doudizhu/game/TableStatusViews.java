package linmumua.doudizhu.game;

import linmumua.doudizhu.model.CardPattern;
import linmumua.doudizhu.model.DoudizhuCard;
import linmumua.doudizhu.ui.MuzTheme;
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
        int revealMultiplier,
        int boostedFarmerCount,
        int farmerSeatCount,
        Integer landlordBoostFactor,
        String pairMultiplierSummary,
        GameTable.RevealWindowSnapshot revealWindow
    ) {
        if (phase == GamePhase.DEALING) {
            String revealLine = revealWindowText(revealWindow);
            return revealLine != null ? revealLine : "发牌中 · 请稍候";
        }
        if (phase == GamePhase.REVEALING) {
            String revealLine = revealWindowText(revealWindow);
            return revealLine != null ? revealLine : "明牌窗口已结束 · 等待叫分";
        }
        if (highestBid <= 0 || landlord == null) {
            return phase == GamePhase.LOBBY ? "等待本局开局" : "等待叫分结果";
        }
        StringBuilder builder = new StringBuilder()
            .append("底分 ").append(Math.max(1, highestBid))
            .append(" · 明牌 x").append(revealMultiplier)
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
        int revealMultiplier,
        int boostedFarmerCount,
        int farmerSeatCount,
        Integer landlordBoostFactor,
        String pairMultiplierSummary,
        GameTable.RevealWindowSnapshot revealWindow
    ) {
        if (phase == GamePhase.DEALING) {
            Component revealLine = revealWindowComponent(revealWindow);
            return revealLine != null ? revealLine : MuzTheme.muted("发牌中 · 请稍候");
        }
        if (phase == GamePhase.REVEALING) {
            Component revealLine = revealWindowComponent(revealWindow);
            return revealLine != null ? revealLine : MuzTheme.muted("明牌窗口已结束 · 等待叫分");
        }
        if (highestBid <= 0 || landlord == null) {
            return phase == GamePhase.LOBBY ? MuzTheme.muted("等待本局开局") : MuzTheme.muted("等待叫分结果");
        }
        Component line = MuzTheme.warm("底分 " + Math.max(1, highestBid))
            .append(MuzTheme.divider(" · "))
            .append(MuzTheme.hotMetric("明牌", "x" + revealMultiplier))
            .append(MuzTheme.divider(" · "))
            .append(MuzTheme.hotMetric("炸弹", "x" + bombMultiplier))
            .append(MuzTheme.divider(" · "))
            .append(MuzTheme.hotMetric("农民加倍", boostedFarmerCount + "/" + farmerSeatCount, "人"));
        if (landlordBoostFactor != null) {
            line = line.append(MuzTheme.divider(" · "))
                .append(landlordBoostFactor > 1 ? MuzTheme.hotMetric("地主加倍", "x" + landlordBoostFactor) : MuzTheme.muted("地主不加倍"));
        }
        return line.append(MuzTheme.divider(" · "))
            .append(MuzTheme.hotValue(pairMultiplierSummary));
    }

    /**
     * 明牌窗口的一行纯文本；窗口未开时返回 {@code null}，由调用方按阶段给保底文案。
     *
     * <p>【唯一数据源】倍率与剩余秒都来自 {@link GameTable.RevealWindowSnapshot}（服务端已算好），
     * 这里只负责措辞。明牌倍率自 1.10.63 起是「首批发完 ×3、第二批 ×2、之后固定 ×1」的递减阶梯
     * （见 {@code RevealMultiplierLadder}），因此**不允许**再在视图里写死一个固定的整局上限：
     * 那会让每一次明牌提示都在误导玩家。
     *
     * <p>【为什么 DEALING 也要看它】明牌窗口不再只在 REVEALING 打开：首批（每人 3 张）发完窗口就开，
     * 而阶段切换仍由翻转结束负责，所以「窗口已开」的事实会出现在 DEALING 期。
     */
    private static String revealWindowText(GameTable.RevealWindowSnapshot revealWindow) {
        if (revealWindow == null || !revealWindow.open()) {
            return null;
        }
        if (revealWindow.youRevealed()) {
            return "已明牌 · 本局明牌 x" + Math.max(1, revealWindow.appliedMultiplier());
        }
        return "明牌窗口 · 现在明牌 x" + revealWindow.currentMultiplier()
            + " · 剩 " + Math.max(0, revealWindow.remainingSeconds()) + " 秒";
    }

    /** @see #revealWindowText(GameTable.RevealWindowSnapshot) 同一份事实的富文本版本 */
    private static Component revealWindowComponent(GameTable.RevealWindowSnapshot revealWindow) {
        if (revealWindow == null || !revealWindow.open()) {
            return null;
        }
        if (revealWindow.youRevealed()) {
            return MuzTheme.accent("已明牌")
                .append(MuzTheme.divider(" · "))
                .append(MuzTheme.hotMetric("本局明牌", "x" + Math.max(1, revealWindow.appliedMultiplier())));
        }
        return MuzTheme.accent("明牌窗口")
            .append(MuzTheme.divider(" · "))
            .append(MuzTheme.hotMetric("现在明牌", "x" + revealWindow.currentMultiplier()))
            .append(MuzTheme.divider(" · "))
            .append(MuzTheme.warning("剩 " + Math.max(0, revealWindow.remainingSeconds()) + " 秒"));
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

    /**
     * 组合行动栏里的当前出牌身份，固定为“当前出牌 · 玩家名”。
     */
    static Component currentPlayIdentity(Component name) {
        Component safeName = name == null ? Component.empty() : name;
        return MuzTheme.orange("当前出牌")
            .append(MuzTheme.divider(" · "))
            .append(safeName);
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
        Function<UUID, Component> currentPlayIdentity,
        Component lobbyActionBar,
        int currentTurnTimeoutSeconds,
        GameTable.RevealWindowSnapshot revealWindow
    ) {
        if (phase == GamePhase.LOBBY) {
            return lobbyActionBar;
        }
        if (phase == GamePhase.DEALING) {
            Component revealLine = revealWindowComponent(revealWindow);
            return revealLine != null ? revealLine : Component.text("正在发牌，请稍候。", NamedTextColor.GRAY);
        }
        if (phase == GamePhase.REVEALING) {
            Component revealLine = revealWindowComponent(revealWindow);
            return revealLine != null ? revealLine : Component.text("明牌窗口已结束 · 等待叫分。", NamedTextColor.GRAY);
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
            // DEALING / REVEALING 已在上面按快照早退；这里保留同样口径的实现，
            // 是为了万一将来早退被改动也不会退化回写死文案（不写死 ×2、不只认阶段）。
            case DEALING -> {
                Component revealLine = revealWindowComponent(revealWindow);
                yield revealLine != null ? revealLine : Component.text("正在发牌，请稍候。", NamedTextColor.GRAY);
            }
            case REVEALING -> {
                Component revealLine = revealWindowComponent(revealWindow);
                yield revealLine != null ? revealLine : Component.text("明牌窗口已结束 · 等待叫分。", NamedTextColor.GRAY);
            }
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
            case PLAYING -> {
                Component line = currentPlayIdentity.apply(currentTurn);
                if (viewerId.equals(currentTurn)) {
                    line = line.append(MuzTheme.divider(" · "))
                        .append(MuzTheme.accent("已选 " + selectedCount + " 张"));
                }
                if (currentTurnTimeoutSeconds > 0) {
                    line = line.append(MuzTheme.divider(" | "))
                        .append(MuzTheme.warning(remainingSeconds + " 秒"));
                }
                yield line;
            }
            case LOBBY -> lobbyActionBar;
        };
    }
}
