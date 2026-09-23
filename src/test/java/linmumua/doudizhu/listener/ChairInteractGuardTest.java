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
 *
 * <p>按钮是第二个放行口，成因不同但同样已取证：取消 INTERACT_AT 会让后续的 INTERACT
 * 不再送达，而按钮处理只挂在 INTERACT 那一路，不放行就完全没反应。
 *
 * <p>麻将实体是第三个放行口：麻将 tag 并入共享保护链后，麻将实体也成了「受保护实体」，
 * 于是它同样会走到这里。麻将实体既不是椅子家具、也不是动作按钮，前两个放行口都盖不住它，
 * 不放行就会取消麻将入座 Interaction。
 */
class ChairInteractGuardTest {
    @Test
    void chairFurnitureIsLetThroughSoCraftEngineCanSeatThePlayer() {
        assertFalse(
            WorldTableInteractionListener.shouldCancelProtectedInteract(true, true, false, false),
            "椅子家具即使受保护也要放行，否则 CraftEngine 收不到右键，玩家坐不上去"
        );
    }

    /**
     * 按钮即使受保护也要放行，否则右键按钮完全没反应。
     *
     * <h2>已取证的故障链</h2>
     *
     * <p>客户端一次右键实体会先发 INTERACT_AT 再发 INTERACT。{@code onInteractAt} 只做保护判定，
     * 见到保护实体就取消事件；而取消 AT 会让后续的 INTERACT 不再送达。
     * {@code handleInteraction} 为避免同一次右键被执行两遍（toggle 切两次净效果为零、
     * BID_n 第二遍抛异常），只挂在纯 INTERACT 那一路 —— 于是唯一的出路也被 AT 的取消堵死，
     * 按钮表现为「点了完全没反应，连提示都没有」。
     *
     * <p>失败条件：把按钮的放行口去掉。那样按钮会重新变成完全失效，而且因为
     * {@code handleInteraction} 本身没报错、日志里一片干净，极难定位。
     *
     * <p>这条测试的存在本身也是补一个缺口：修双执行时只有「不许跑两遍」的测试，
     * 没有任何测试守「AT 被取消后按钮仍然可用」，于是那次回归静默通过了全部测试。
     */
    @Test
    void actionButtonsAreLetThroughSoTheClickStillReachesTheHandler() {
        assertFalse(
            WorldTableInteractionListener.shouldCancelProtectedInteract(true, false, true, false),
            "按钮即使受保护也要放行：取消 INTERACT_AT 会让 INTERACT 不再送达，"
                + "而 handleInteraction 只挂在 INTERACT 那一路，按钮会完全没反应"
        );
    }

    /**
     * 麻将实体即使受保护也要放行，否则麻将入座 Interaction 被取消。
     *
     * <h2>为什么必须单独有这个放行口</h2>
     *
     * <p>麻将 tag 经 {@code TableEntityGeometry.PROTECTED_TAGS} 并入
     * {@code PhysicalTableManager.isProtectedEntity}，让麻将实体获得破坏保护；
     * 代价是「受保护」不再等价于「右键要拦」——麻将实体会走到本方法并命中拦下分支。
     * 它既不是椅子家具也不是动作按钮，前两个放行口都盖不住，于是必须新增这一个。
     *
     * <p>失败条件：去掉麻将放行口（把第四个形参恒传 false 或从判定里删掉）。那样
     * 麻将入座 Interaction 会在 LOWEST 优先级被取消，玩家点麻将实体坐不下去，
     * 而日志里不会有任何报错。
     */
    @Test
    void mahjongEntitiesAreLetThroughSoSeatingStillWorks() {
        assertFalse(
            WorldTableInteractionListener.shouldCancelProtectedInteract(true, false, false, true),
            "麻将实体受保护但右键必须放行，否则麻将入座 Interaction 被取消"
        );
    }

    @Test
    void otherProtectedTablePartsStayBlocked() {
        assertTrue(
            WorldTableInteractionListener.shouldCancelProtectedInteract(true, false, false, false),
            "桌面、状态牌这类保护实体仍要拦住，别让玩家推走或拆掉"
        );
    }

    @Test
    void unprotectedEntitiesAreNeverTouched() {
        assertFalse(
            WorldTableInteractionListener.shouldCancelProtectedInteract(false, false, false, false),
            "跟牌桌无关的实体不该被插件干预"
        );
    }
}
