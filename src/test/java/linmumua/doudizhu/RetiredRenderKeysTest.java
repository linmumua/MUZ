package linmumua.doudizhu;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;

/**
 * 退役配置键要清干净，但绝不能顺手删掉用户还要用的项。
 *
 * 按钮图标删掉、判定框改为按文字缩放自动推算之后，button-scale、button-roll-degrees、
 * button-hitbox.width/height、chair-hitbox.width/height 都失去作用，启动时会被清掉。
 * 但用户明确要求保留"按钮远近高低"和判定框位置微调，这两组必须严格互斥——
 * 一旦有人往退役表里多写一个键，用户的调节能力就会静默消失。
 */
class RetiredRenderKeysTest {
    @Test
    void retiredAndPreservedKeysDoNotIntersect() {
        Set<String> retired = new HashSet<>(Arrays.asList(DoudizhuPlugin.RETIRED_RENDER_KEYS));
        Set<String> preserved = new HashSet<>(Arrays.asList(DoudizhuPlugin.PRESERVED_RENDER_KEYS));

        Set<String> both = new HashSet<>(retired);
        both.retainAll(preserved);

        assertTrue(both.isEmpty(), "这些键既要删又要留，冲突了: " + both);
    }

    @Test
    void buttonDistanceAndHeightSurvive() {
        // 用户原话要求"保留按钮远近高低调整"。
        Set<String> retired = new HashSet<>(Arrays.asList(DoudizhuPlugin.RETIRED_RENDER_KEYS));

        assertFalse(retired.contains("render.button-offset.distance"), "按钮离桌距离不能删");
        assertFalse(retired.contains("render.button-offset.height"), "按钮高度不能删");
    }

    @Test
    void hitboxOffsetsSurvive() {
        // 判定框尺寸退役了，但位置微调保留。
        Set<String> retired = new HashSet<>(Arrays.asList(DoudizhuPlugin.RETIRED_RENDER_KEYS));

        for (String kept : List.of(
            "render.button-hitbox-offset.lateral",
            "render.button-hitbox-offset.depth",
            "render.button-hitbox-offset.vertical"
        )) {
            assertFalse(retired.contains(kept), kept + " 是位置微调，不能删");
        }
    }

    @Test
    void cardHitboxSizeStaysConfigurable() {
        // 牌的判定框仍然要能手调，用户是靠它把碰撞箱调吻合的。
        Set<String> retired = new HashSet<>(Arrays.asList(DoudizhuPlugin.RETIRED_RENDER_KEYS));

        for (String kept : List.of(
            "render.card-hitbox.length",
            "render.card-hitbox.width",
            "render.card-hitbox.height"
        )) {
            assertFalse(retired.contains(kept), kept + " 必须保持可配置");
        }
    }

    @Test
    void buttonSizeAndRollAreRetired() {
        // 图标删了，这两项没有作用对象，必须清掉而不是留着误导。
        Set<String> retired = new HashSet<>(Arrays.asList(DoudizhuPlugin.RETIRED_RENDER_KEYS));

        assertTrue(retired.contains("render.button-scale"), "按钮大小应当退役");
        assertTrue(retired.contains("render.button-roll-degrees"), "按钮旋转应当退役");
    }

    @Test
    void hitboxSizeKeysAreRetired() {
        Set<String> retired = new HashSet<>(Arrays.asList(DoudizhuPlugin.RETIRED_RENDER_KEYS));

        for (String gone : List.of(
            "render.button-hitbox.width",
            "render.button-hitbox.height",
            "render.chair-hitbox.width",
            "render.chair-hitbox.height"
        )) {
            assertTrue(retired.contains(gone), gone + " 应当退役");
        }
    }

    @Test
    void buttonHoverConfigIsRetired() {
        Set<String> retired = new HashSet<>(Arrays.asList(DoudizhuPlugin.RETIRED_RENDER_KEYS));

        assertTrue(retired.contains("render.button-hover"), "文字按钮不再有 hover，旧配置必须清掉");
    }
}
