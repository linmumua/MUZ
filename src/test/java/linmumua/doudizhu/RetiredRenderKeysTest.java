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
    void cardHitboxAndHoverOffsetAreRetired() {
        // 手牌判定改为射线与牌平面解析求交，牌身上再没有交互箱实体，
        // 这两组尺寸/偏移键连读取点都没有了，留着只会误导玩家去调无效项。
        // 悬停向后偏移同理必须清掉：沿法向平移会让交点跟着悬停漂移，
        // 玩家若从旧配置里继承这个值，抖动会带着一份"看起来是我调坏的"错觉回来。
        Set<String> retired = new HashSet<>(Arrays.asList(DoudizhuPlugin.RETIRED_RENDER_KEYS));

        for (String gone : List.of(
            "render.card-hitbox.length",
            "render.card-hitbox.width",
            "render.card-hitbox.height",
            "render.card-hitbox",
            "render.card-hitbox-offset.lateral",
            "render.card-hitbox-offset.depth",
            "render.card-hitbox-offset.vertical",
            "render.card-hitbox-offset",
            "render.card-hover.backward-offset"
        )) {
            assertTrue(retired.contains(gone), gone + " 应当退役");
        }
    }

    /**
     * 悬停反馈这两项必须活着：突出效果全靠它们，删掉等于悬停毫无反馈。
     */
    @Test
    void hoverScaleAndLiftSurvive() {
        Set<String> retired = new HashSet<>(Arrays.asList(DoudizhuPlugin.RETIRED_RENDER_KEYS));

        assertFalse(retired.contains("render.card-hover.scale"), "悬停放大是唯一的突出手段，不能删");
        assertFalse(retired.contains("render.card-hover.lift"), "悬停上移不能删");
    }

    /**
     * 父节点必须排在自己的子键之后：清理是按顺序执行的，
     * 先删父节点会让后面的子键路径失效，留下空 section。
     */
    @Test
    void parentSectionsAreRetiredAfterTheirChildren() {
        List<String> retired = Arrays.asList(DoudizhuPlugin.RETIRED_RENDER_KEYS);

        for (String parent : List.of(
            "render.card-hitbox", "render.card-hitbox-offset", "render.current-play-head")) {
            int parentAt = retired.indexOf(parent);
            assertTrue(parentAt >= 0, parent + " 应当在退役表里");
            for (int i = 0; i < retired.size(); i++) {
                String key = retired.get(i);
                if (key.startsWith(parent + ".")) {
                    assertTrue(
                        i < parentAt,
                        "子键 " + key + " 排在父节点 " + parent + " 之后，父节点先删会让它清不掉"
                    );
                }
            }
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
