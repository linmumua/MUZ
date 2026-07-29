package linmumua.doudizhu.game;

import linmumua.doudizhu.ui.MuzTheme;
import net.kyori.adventure.text.Component;

record RoundSettlementView(
    boolean landlordWin,
    int resolvedCoreScore,
    int highestBid,
    int bombMultiplier,
    int boostedFarmerCount,
    int farmerSeatCount,
    Integer landlordBoostFactor,
    boolean spring,
    String springLabel,
    String pairMultiplierSummary
) {
    Component summary(Component winnersComponent) {
        Component result = winnersComponent
            .append(MuzTheme.divider(" · "))
            .append(landlordWin ? MuzTheme.landlord("地主") : MuzTheme.farmer("农民"));
        result = result.append(Component.newline())
            .append(MuzTheme.hotMetric("最终倍数", MuzTheme.multiplierToken(pairMultiplierSummary)))
            .append(Component.newline())
            .append(landlordWin ? MuzTheme.landlord("地主阵营胜出") : MuzTheme.farmer("农民阵营胜出"))
            .append(Component.newline())
            .append(MuzTheme.hotMetric("本局核心", MuzTheme.multiplierToken("x" + resolvedCoreScore)))
            .append(Component.newline())
            .append(MuzTheme.hotMetric("叫分", MuzTheme.multiplierToken("x" + highestBid)))
            .append(Component.newline())
            .append(MuzTheme.hotMetric("炸弹", MuzTheme.multiplierToken("x" + bombMultiplier)));
        if (farmerSeatCount > 0) {
            result = result.append(Component.newline())
                .append(MuzTheme.hotMetric("农民加倍", boostedFarmerCount + "/" + farmerSeatCount, "人"));
        }
        if (landlordBoostFactor != null && landlordBoostFactor > 1) {
            result = result.append(Component.newline())
                .append(MuzTheme.hotMetric("地主加倍", MuzTheme.multiplierToken("x" + landlordBoostFactor)));
        }
        if (spring) {
            result = result.append(Component.newline())
                .append(MuzTheme.warning(springLabel).append(MuzTheme.space()).append(MuzTheme.multiplierToken("x2")));
        }
        result = result.append(Component.newline())
            .append(MuzTheme.hotMetric("结算倍数", MuzTheme.multiplierToken(pairMultiplierSummary)));
        return MuzTheme.plain(result);
    }

    Component playerLine(Component identity, PlayerRole role, String amount, double delta, String unitLabel) {
        Component line = identity
            .append(MuzTheme.divider(" · "))
            .append(role == null ? MuzTheme.muted("玩家") : role == PlayerRole.LANDLORD ? MuzTheme.landlord(role.displayName()) : MuzTheme.farmer(role.displayName()));
        if (Math.abs(delta) > 0.0001) {
            line = line.append(MuzTheme.divider(" · "))
                .append(delta >= 0 ? MuzTheme.success("赢了 " + amount + unitLabel) : MuzTheme.danger("输了 " + amount + unitLabel));
        } else {
            line = line.append(MuzTheme.divider(" · "))
                .append(MuzTheme.muted("持平"));
        }
        return MuzTheme.plain(line);
    }
}
