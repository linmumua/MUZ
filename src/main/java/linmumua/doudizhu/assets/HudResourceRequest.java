package linmumua.doudizhu.assets;

/**
 * 一次连续 HUD 资源覆盖层请求的不可变参数。
 *
 * <p>三层 Trick HUD 使用同一套连续偏移范围；Hotbar 的下界按已生成 scale 的基础 ascent
 * 计算，避免把未生成的 profile 档位误当成可用资源。
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
        PackAssets.requireHotbarScale(hotbarScale);
        PackAssets.requireHotbarOffset(hotbarOffsetY, hotbarScale);
    }
}
