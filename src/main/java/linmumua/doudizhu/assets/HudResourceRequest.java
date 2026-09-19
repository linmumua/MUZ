package linmumua.doudizhu.assets;

/**
 * 一次连续 HUD 资源覆盖层请求的不可变参数。
 *
 * <p>请求形状保留 Hotbar 字段以兼容并行 worker；资源请求与就绪判定只消费前三层
 * Trick HUD 偏移，Hotbar 字段不参与校验。
 */
public record HudResourceRequest(
    int cardOffsetDown,
    int avatarOffsetDown,
    int counterOffsetDown,
    int hotbarOffsetY,
    int hotbarScale
) {
    public HudResourceRequest {
        PackAssets.requireTrickOffset(cardOffsetDown, "牌面");
        PackAssets.requireTrickOffset(avatarOffsetDown, "头像");
        PackAssets.requireTrickOffset(counterOffsetDown, "记牌器");
        // Hotbar 字段保留旧构造形状，但不参与三层资源请求校验。
    }
}
