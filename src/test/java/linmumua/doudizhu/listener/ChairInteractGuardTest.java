package linmumua.doudizhu.listener;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

/**
 * 右键椅子家具必须放行，否则玩家坐不上椅子。
 *
 * onInteract 挂在 LOWEST 优先级，早期版本对所有保护实体无条件 setCancelled(true)。
 * 椅子的 CraftEngine 实体树被 protectEntityTree 打了保护标记，于是右键事件在最早的
 * 优先级就被掐掉，CraftEngine 那个带 seats 的 hitbox 永远收不到事件。
 * 桌面、状态牌之类的保护实体仍要拦住，避免被玩家拆掉或推走。
 */
class ChairInteractGuardTest {
    @Test
    void chairFurnitureIsLetThroughSoCraftEngineCanSeatThePlayer() {
        assertFalse(
            WorldTableInteractionListener.shouldCancelProtectedInteract(true, true),
            "椅子家具即使受保护也要放行，否则 CraftEngine 收不到右键，玩家坐不上去"
        );
    }

    @Test
    void otherProtectedTablePartsStayBlocked() {
        assertTrue(
            WorldTableInteractionListener.shouldCancelProtectedInteract(true, false),
            "桌面、状态牌这类保护实体仍要拦住，别让玩家推走或拆掉"
        );
    }

    @Test
    void unprotectedEntitiesAreNeverTouched() {
        assertFalse(
            WorldTableInteractionListener.shouldCancelProtectedInteract(false, false),
            "跟牌桌无关的实体不该被插件干预"
        );
    }
}
