package dev.mumu.doudizhu.game;

import dev.mumu.doudizhu.ui.MuzTheme;
import net.kyori.adventure.text.Component;

record DoublingDecisionView(String actionText, Component actionComponent, String actionDetail) {
    static DoublingDecisionView of(boolean landlordTurn, int boostFactor, boolean autoSkipped) {
        if (autoSkipped) {
            return new DoublingDecisionView(" 超时跳过", MuzTheme.muted("跳过"), "6 秒内未操作，已自动跳过");
        }
        if (boostFactor >= 2) {
            return new DoublingDecisionView(
                " 加倍",
                MuzTheme.hotMetric("加倍", "x2"),
                landlordTurn ? "地主侧倍率 x2" : "农民侧倍率 x2"
            );
        }
        return new DoublingDecisionView(" 不加倍", MuzTheme.muted("不加倍"), "保持当前倍率");
    }
}
