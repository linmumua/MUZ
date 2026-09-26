package linmumua.doudizhu.game;

/**
 * 「对玩家可见的业务拒绝」：插件主动拒绝玩家操作、且异常文本本身就是给玩家看的中文提示时抛出的异常。
 *
 * <p>【为什么需要单独一个类型，而不是继续用裸 {@link IllegalStateException}】
 * 交互入口（牌桌按钮、手牌选牌/出牌）的 catch 会把任意 {@code RuntimeException} 转成一条玩家 ActionBar
 * 提示，同时按「系统异常」记一条带完整堆栈的 WARNING（见
 * {@code PhysicalTableManager#reportInteractionFailure}）。但「金币/筹码门槛不足」这类拒绝是玩家操作的
 * 常见结果，不是故障——玩家自己看得懂那句提示，服务端也没有任何需要排查的东西。把它按系统异常记满堆栈，
 * 只会让真正的故障（调度拒绝、反射失败、IO 错误）淹没在同一种告警里，实服日志因此失去信噪比。
 *
 * <p>【判据是异常类型，不是消息文本】用消息文本判「是不是业务拒绝」会随文案改动而失效，也会误伤同样
 * 抛出「门槛不足」以外文本的真实异常。类型是编译期确定的，既不会漂移，也不会把「Hotbar 布局算式不闭合」
 * 这类内部不一致（必须继续留栈）误判成业务拒绝。
 *
 * <p>【为什么继承 IllegalStateException】调用点原来就抛 {@code IllegalStateException(玩家提示文本)}，
 * 换成这个子类型后，任何既有的 {@code IllegalStateException} 捕获/判定语义都不变——它只是给一个既有语义
 * 补上可判别的子类型，不改变控制流。玩家提示照旧：catch 里仍用 {@code getMessage()} 发给玩家。
 *
 * <p>【适用范围】当前用于「进入门槛/资格」这一类：{@code DoudizhuPlugin.insufficientEntryMessage} 的
 * 三个抛点（加入、准备、开局资格复核）与 {@code PhysicalTableManager.joinSeat} 的换桌门槛。其它规则类
 * 拒绝（座位已有人、阶段不符等）仍按 {@code IllegalStateException} 记录——那是有意保留的既有契约，
 * 不要顺手扩大本类型的适用范围。
 */
public class InteractionRejectionException extends IllegalStateException {
    public InteractionRejectionException(String message) {
        super(message);
    }
}
