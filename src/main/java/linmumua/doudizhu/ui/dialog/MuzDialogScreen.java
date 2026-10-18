package linmumua.doudizhu.ui.dialog;

import io.papermc.paper.dialog.Dialog;

/**
 * 一个可展示的原生 Dialog 屏。
 *
 * <p>实现类由各功能 owner 提供（管理页、个人设置、音效动作、战绩、道具箱）。屏只负责「把当前
 * 状态画成一个 Dialog」：不碰调度、不做 IO、不直接调 {@code player.showDialog}。展示、关闭、
 * 会话代次与权限复核统一由 {@link MuzDialogService} 在 player lane 内完成。
 *
 * <p>{@link #build(MuzDialogContext)} 在 player lane 内被调用，因此可以安全读取实时玩家状态
 * （物品栏、权限、当前牌桌等）；但**不要**在这里做文件 IO 或阻塞调用。
 */
public interface MuzDialogScreen {
    /** 稳定唯一标识，例如 {@code "settings"}、{@code "admin.home"}，仅用于会话审计。 */
    String screenKey();

    /**
     * 打开该屏所需权限；返回 null 表示不需要权限。
     *
     * <p>由 core 在「打开前」与「每次按钮回调内」各复核一次，实现类无需自己再判权限。
     */
    default String permission() {
        return null;
    }

    /**
     * 在 player lane 内构建该屏。
     *
     * @param ctx 构建上下文（携带玩家、会话代次、按钮与导航入口）
     * @return 要展示的 Dialog
     */
    Dialog build(MuzDialogContext ctx);
}
