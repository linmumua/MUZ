package linmumua.doudizhu.world;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/**
 * 射线命中判定框，抬升的必须是图标。
 *
 * 一个按钮是三个实体：ItemDisplay 图标、TextDisplay 文字、Interaction 判定框。
 * 玩家射线命中的几乎总是判定框，而需要放大上移的是图标。三个实体都得映射到
 * 同一个图标 id，漏掉任何一个都会让 hover 在那部分上失效。
 *
 * 空位的"加入座位"按钮尤其容易踩这个坑：它的判定框和图标是分开生成的两个实体。
 */
class ActionHoverTargetTest {
    private static final UUID ICON = UUID.nameUUIDFromBytes("icon".getBytes());
    private static final UUID LABEL = UUID.nameUUIDFromBytes("label".getBytes());
    private static final UUID INTERACTION = UUID.nameUUIDFromBytes("interaction".getBytes());
    private static final UUID UNRELATED = UUID.nameUUIDFromBytes("sheep".getBytes());

    private static Map<UUID, UUID> oneButton() {
        Map<UUID, UUID> map = new LinkedHashMap<>();
        map.put(ICON, ICON);
        map.put(LABEL, ICON);
        map.put(INTERACTION, ICON);
        return map;
    }

    @Test
    void hittingTheHitboxLiftsTheIcon() {
        // 这是最常见的情形：玩家瞄准按钮时射线打在 Interaction 上。
        assertEquals(ICON, PhysicalTableManager.resolveHoverDisplay(oneButton(), INTERACTION));
    }

    @Test
    void hittingTheLabelLiftsTheSameIcon() {
        assertEquals(ICON, PhysicalTableManager.resolveHoverDisplay(oneButton(), LABEL));
    }

    @Test
    void hittingTheIconItselfStillWorks() {
        assertEquals(ICON, PhysicalTableManager.resolveHoverDisplay(oneButton(), ICON));
    }

    @Test
    void allThreePartsResolveToOneIconSoHoverDoesNotFlicker() {
        Map<UUID, UUID> map = oneButton();

        UUID viaIcon = PhysicalTableManager.resolveHoverDisplay(map, ICON);
        UUID viaLabel = PhysicalTableManager.resolveHoverDisplay(map, LABEL);
        UUID viaHitbox = PhysicalTableManager.resolveHoverDisplay(map, INTERACTION);

        assertEquals(viaIcon, viaLabel, "命中文字和命中图标必须抬升同一个实体");
        assertEquals(viaIcon, viaHitbox, "命中判定框和命中图标必须抬升同一个实体");
    }

    @Test
    void lookingAtNothingClearsHover() {
        // getTargetEntity 返回 null 时不能抛异常，也不能沿用上一次的目标。
        assertNull(PhysicalTableManager.resolveHoverDisplay(oneButton(), null));
    }

    @Test
    void lookingAtSomethingElseClearsHover() {
        assertNull(
            PhysicalTableManager.resolveHoverDisplay(oneButton(), UNRELATED),
            "瞄到无关实体时要清掉 hover，否则按钮会卡在放大状态"
        );
    }

    @Test
    void staleMappingForRemovedButtonYieldsNoHover() {
        // 按钮被清掉后映射也会被移除，此时命中残留 id 不该抬升任何东西。
        Map<UUID, UUID> empty = new LinkedHashMap<>();

        assertNull(PhysicalTableManager.resolveHoverDisplay(empty, INTERACTION));
    }
}
