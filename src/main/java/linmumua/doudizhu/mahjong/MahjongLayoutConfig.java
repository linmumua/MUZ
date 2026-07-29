package linmumua.doudizhu.mahjong;

import linmumua.doudizhu.DoudizhuPlugin;

public record MahjongLayoutConfig(
    double displayCenterXOffset,
    double displayCenterYOffset,
    double displayCenterZOffset,
    double tableVisualYOffset,
    double seatDistanceFromHandBase,
    double seatBaseYOffset,
    double seatAnchorYOffset,
    double seatLabelDepthOffset,
    double seatActionLabelYOffset,
    double seatSideActionHorizontalOffset,
    double centerLabelYOffset,
    double seatActionLabelScale,
    double seatActionHitboxWidth,
    double seatActionHitboxHeight
) {
    public static MahjongLayoutConfig from(DoudizhuPlugin plugin) {
        return new MahjongLayoutConfig(
            plugin.getMahjongDisplayCenterXOffset(),
            plugin.getMahjongDisplayCenterYOffset(),
            plugin.getMahjongDisplayCenterZOffset(),
            plugin.getMahjongTableVisualYOffset(),
            plugin.getMahjongSeatDistanceFromHandBase(),
            plugin.getMahjongSeatBaseYOffset(),
            plugin.getMahjongSeatAnchorYOffset(),
            plugin.getMahjongSeatLabelDepthOffset(),
            plugin.getMahjongSeatActionLabelYOffset(),
            plugin.getMahjongSeatSideActionHorizontalOffset(),
            plugin.getMahjongCenterLabelYOffset(),
            plugin.getMahjongSeatActionLabelScale(),
            plugin.getMahjongSeatActionHitboxWidth(),
            plugin.getMahjongSeatActionHitboxHeight()
        );
    }

    public String summary() {
        return "centerX=" + trim(displayCenterXOffset)
            + " centerY=" + trim(displayCenterYOffset)
            + " centerZ=" + trim(displayCenterZOffset)
            + " tableY=" + trim(tableVisualYOffset)
            + " seatDist=" + trim(seatDistanceFromHandBase)
            + " actionScale=" + trim(seatActionLabelScale);
    }

    private static String trim(double value) {
        String text = String.format(java.util.Locale.ROOT, "%.2f", value);
        return text.replaceAll("0+$", "").replaceAll("\\.$", "");
    }
}
