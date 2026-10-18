package linmumua.doudizhu.ui.dialog;

import org.bukkit.entity.Player;

/**
 * 旧九格道具箱 GUI 到 Dialog 的打开接缝。
 *
 * <p>旧箱子 UI 的生产可达路径必须不可达：{@code GadgetBoxGuiService.open(Player)} 改为委托本接缝，
 * 由 {@code DoudizhuPlugin} 在装配时注入「打开道具箱编辑 Dialog」的生产实现。没有注入时
 * {@code open} 直接返回，**不**回落到 {@code createInventory}。
 *
 * <p>做成接口而不是直接引用屏幕类，是为了让 {@code GadgetBoxGuiService} 不依赖 Dialog 实现、
 * 也便于测试注入替身。
 */
@FunctionalInterface
public interface GadgetBoxDialogOpener {
    /** 为玩家打开道具箱编辑 Dialog（实现内部负责 player lane 投递）。 */
    void open(Player player);
}
