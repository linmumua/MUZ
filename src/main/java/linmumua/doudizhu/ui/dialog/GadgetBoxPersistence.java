package linmumua.doudizhu.ui.dialog;

import java.util.UUID;
import linmumua.doudizhu.ui.VirtualGadgetBar;

/**
 * 道具箱编辑的持久化接缝。
 *
 * <p>规划要求「不在 UI 回调里做文件 IO」。道具箱 Dialog 保存时只把「要写什么」交给本接缝，
 * 由生产实现负责异步写 {@code VirtualGadgetBarStore}，写完后切回 player lane 调
 * {@code onSavedOnPlayerLane}（用于刷新道具预览与出牌 HUD 第四行）。
 *
 * <p>屏实现只依赖本接口，不做 IO、不碰调度；生产实现由 {@code DoudizhuPlugin} 装配注入。
 */
public interface GadgetBoxPersistence {
    /**
     * 异步持久化八个道具槽，并在完成后回 player lane 执行刷新回调。
     *
     * @param playerId 所属玩家
     * @param bar 新的道具槽快照
     * @param onSavedOnPlayerLane 写盘成功后在 player lane 执行的刷新回调；可为 null
     */
    void persist(UUID playerId, VirtualGadgetBar bar, Runnable onSavedOnPlayerLane);
}
