package linmumua.doudizhu.world;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

/**
 * 手牌点击仲裁的让位判据，按真值表逐格验。
 *
 * <p>为什么单独开一个文件：{@code HandCardClickRoutingTest} 走源码扫描，只能断言
 * 「代码里出现了某个调用」，证不了这些分支组合起来的结论对不对。
 * {@code yieldsToBlockingEntity} 抽成 static 布尔谓词正是为了能真的跑起来，
 * 写法沿用项目里 {@code shouldCancelProtectedInteract} 的做法。
 *
 * <p>背景：牌是 ItemDisplay 没有判定框，客户端的实体射线直接跳过它，命中的是牌后面
 * 桌子家具的 CE 判定框。桌子整棵实体树都登记为保护实体，右键会被静默取消，
 * 于是点牌彻底没反应、连提示都没有。仲裁要在那之前把点击抢回来，
 * 但不能连按钮、椅子、怪物的点击一起抢。
 */
class HandCardArbitrationVerdictTest {

    /**
     * 家具但按钮不可用：手牌必须赢。这一格覆盖两种到达方式。
     *
     * <p>其一是桌子本体、装饰这类压根没有 binding 的家具——这是「点牌没反应」的主路径。
     * 点它们本来就走到静默取消、什么都不发生，让手牌赢不损失任何既有行为。
     *
     * <p>其二是有 binding 但当前够不着的按钮。按钮只在 3.0 格内可点
     * （MAX_ACTION_INTERACTION_DISTANCE），而手牌按产品要求不设距离限制
     * （MAX_HAND_CARD_PICK_DISTANCE 取 256 格，实际等价于无限制），
     * 超出按钮那 3 格的部分按钮消费不了这次点击（handleInteraction 只会回一句
     * 「靠近一点再点击」再把事件吃掉），让位给它等于白丢一次点牌。
     *
     * <p>两者在这个谓词看来是同一格：距离在调用方就和绑定与起来了，传进来都是 false。
     * 所以「够不着的按钮」不在这里单独立一个用例——那会是入参完全相同的重复断言。
     * 调用方是否真的把距离与进来，由 HandCardClickRoutingTest 那侧锁住。
     *
     * <p>失败条件：让位。那样两种症状会一起复现。
     */
    @Test
    void plainFurnitureLosesToHandCards() {
        assertFalse(PhysicalTableManager.yieldsToBlockingEntity(true, false, false),
            "桌子/装饰抢走了点牌：这正是点牌没反应、连提示都没有的成因");
    }

    /**
     * 可用的按钮判定框：必须让位。
     *
     * <p>否则出牌/不要/清空等按钮会被手牌盖掉点不动——把一个 bug 换成另一个。
     *
     * <p>第二个参数是「有 ActionBinding <b>且</b>当前够得着」，不只看绑定。
     * 按钮只在 3.0 格内可点，手牌拾取到 6.0 格；中间那段按钮消费不了这次点击，
     * 调用方会传 false，于是走到手牌那一侧。距离条件在调用方短路计算，
     * 这个谓词本身只认这个合成后的布尔。
     */
    @Test
    void actionButtonsKeepTheirClicks() {
        assertTrue(PhysicalTableManager.yieldsToBlockingEntity(true, true, false),
            "按钮判定框被手牌盖掉：出牌/不要按钮会点不动");
    }

    /**
     * 椅子家具：必须让位。
     *
     * <p>椅子是被 shouldCancelProtectedInteract 特意放行的，抢掉它玩家就坐不上去。
     */
    @Test
    void chairFurnitureKeepsItsClicks() {
        assertTrue(PhysicalTableManager.yieldsToBlockingEntity(true, false, true),
            "椅子家具被手牌盖掉：玩家坐不上椅子");
    }

    /**
     * 非家具实体（怪物、玩家）：必须让位。
     *
     * <p>它们自己就能消费这次点击（战斗、交互）。不让位会有个具体回归：
     * 怪物晃到手牌那条带上时，左键攻击被判成点牌、被取消，还弹一句
     * 「请先右键选择要出的牌」。
     *
     * <p>三种 binding/chair 组合都得让位：非家具这一条优先级最高，
     * 不该被后两个标志翻转。
     */
    @Test
    void nonFurnitureAlwaysKeepsItsClicksRegardlessOfOtherFlags() {
        assertTrue(PhysicalTableManager.yieldsToBlockingEntity(false, false, false),
            "怪物/玩家的点击被判成点牌：左键攻击会被取消并弹出无关提示");
        assertTrue(PhysicalTableManager.yieldsToBlockingEntity(false, true, false),
            "非家具让位被 binding 标志翻转了");
        assertTrue(PhysicalTableManager.yieldsToBlockingEntity(false, false, true),
            "非家具让位被椅子标志翻转了");
        assertTrue(PhysicalTableManager.yieldsToBlockingEntity(false, true, true),
            "非家具让位被两个标志同时翻转了");
    }

    /**
     * 既是按钮又是椅子这种理论组合仍然让位。
     *
     * <p>现实中不该出现，但判据不能因为两个标志同时为真就翻转成手牌赢——
     * 那意味着某个真实存在的可点实体被静默吞掉。
     */
    @Test
    void overlappingConsumerFlagsStillYield() {
        assertTrue(PhysicalTableManager.yieldsToBlockingEntity(true, true, true),
            "两个消费者标志同时为真时反而判给手牌：会吞掉一个真实可点实体");
    }

    /**
     * 按钮可点范围必须严格小于手牌拾取范围。
     *
     * <p>为什么值得单独锁：按钮让位那一路特意与上了距离条件，而这个条件只有在
     * 「存在够得着牌、却够不着按钮」的那段距离里才有意义。若哪天有人把按钮范围调到
     * 大于等于手牌范围，距离条件就退化成恒真——整段判据变成死代码，
     * 上面那些真值表用例照样全绿，漂移会静默发生。
     *
     * <p>这条断言也是上面几处注释里那个「够得着牌、够不着按钮」窗口的出处：
     * 窗口宽度就是这两个常量的差。注释里的数字与其说是背景说明，
     * 不如说是判据的前提，所以要有东西盯着。
     */
    @Test
    void buttonRangeStaysInsideHandCardPickRange() {
        assertTrue(
            PhysicalTableManager.MAX_ACTION_INTERACTION_DISTANCE
                < PhysicalTableManager.MAX_HAND_CARD_PICK_DISTANCE,
            "按钮可点范围不再小于手牌拾取范围：按钮让位的距离条件退化成恒真、变成死代码，"
                + "「够得着牌但够不着按钮」那段距离不复存在");
    }

    /**
     * 撤距离限制只针对牌：按钮仍必须守住 3 格。
     *
     * <p>为什么单独锁：上面那条 {@code buttonRangeStaysInsideHandCardPickRange} 只断言
     * 「按钮 &lt; 手牌」，有人把按钮一起提到 100 格它照样绿。而按钮的 3 格是有意义的产品行为
     * （超距时提示「靠近一点再点击」），也是手牌让位判据的前提之一——
     * 放开它会让隔着老远就能按到出牌/不要，属于把一个问题换成另一个。
     *
     * <p>失败条件：调大或调小按钮那 3.0 格。
     */
    @Test
    void removingTheDistanceLimitAppliesToCardsOnlyNotButtons() {
        assertEquals(
            3.0, PhysicalTableManager.MAX_ACTION_INTERACTION_DISTANCE, 1.0e-9,
            "按钮可点距离被改成了 " + PhysicalTableManager.MAX_ACTION_INTERACTION_DISTANCE
                + " 格：撤距离限制只针对手牌，按钮必须仍是 3 格");
    }

    /**
     * 左右键点牌不设距离限制：拾取射程必须远超原版能发出交互包的距离。
     *
     * <p>这是产品要求，不是推导出来的性能参数，所以要锁住。原版客户端只在约 3～4.5 格内
     * 发交互包，取一个远大于它的值就等价于「本插件这一侧没有闸门」——真正的上限交给客户端。
     *
     * <p>阈值取 64 格：够低，不会因为以后微调 256 这个具体数字而误报；
     * 又够高，任何把射程调回 6 格那类「顺手收紧」的改动都会在这里变红。
     *
     * <p>失败条件：把射程改回原来的 6.0 格，或任何小于 64 的值。
     */
    @Test
    void handCardPickHasNoPracticalDistanceLimit() {
        assertTrue(
            PhysicalTableManager.MAX_HAND_CARD_PICK_DISTANCE >= 64.0,
            "手牌拾取射程被收紧到 " + PhysicalTableManager.MAX_HAND_CARD_PICK_DISTANCE
                + " 格：左右键点牌按要求不设距离限制，射程应远超原版发包距离");
    }
}
