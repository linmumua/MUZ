package linmumua.doudizhu.world;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.UUID;
import org.junit.jupiter.api.Test;

/**
 * 占座玩家必须看不见自己的椅子判定框，其他玩家与空椅仍保持可交互。
 */
class OccupiedChairHitboxVisibilityTest {
    @Test
    void occupiedChairHitboxIsHiddenOnlyFromItsOwner() {
        UUID owner = UUID.randomUUID();

        assertTrue(PhysicalTableManager.shouldHideOccupiedChairHitbox(owner, owner));
        assertFalse(PhysicalTableManager.shouldHideOccupiedChairHitbox(owner, UUID.randomUUID()));
    }

    @Test
    void emptyChairHitboxRemainsVisible() {
        assertFalse(PhysicalTableManager.shouldHideOccupiedChairHitbox(null, UUID.randomUUID()));
    }
}
