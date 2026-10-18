package linmumua.doudizhu.ui.dialog;

import java.util.UUID;
import java.util.function.BiConsumer;
import java.util.function.Consumer;
import java.util.function.Function;
import linmumua.doudizhu.DoudizhuPlugin;
import linmumua.doudizhu.debug.GadgetPreviewSnapshotService;
import linmumua.doudizhu.game.PlayerOutputDispatcher;
import linmumua.doudizhu.game.TableGadgetBarHudService;
import linmumua.doudizhu.ui.VirtualGadgetBar;
import linmumua.doudizhu.ui.VirtualGadgetBarStore;
import org.bukkit.entity.Player;

/**
 * 道具箱编辑 Dialog 的装配。
 *
 * <p>把「旧九格箱子 GUI → 道具箱编辑 Dialog」这条链路的三个接缝（读取快照、异步持久化、
 * 选择/语音业务）在装配点收口到一处，避免把业务堆进 {@code DoudizhuPlugin}。
 *
 * <p>旧箱子 GUI（{@code GadgetBoxGuiService} / {@code TableGadgetGuiService}）在装配时注入
 * {@link #opener()}，其 {@code open} 只做委托，不再 {@code createInventory}。
 */
public final class GadgetBoxDialogWiring {
    private final DoudizhuPlugin plugin;
    private final VirtualGadgetBarStore store;
    private final GadgetPreviewSnapshotService previewSnapshotService;
    private final TableGadgetBarHudService barHudService;
    private final PlayerOutputDispatcher output;

    public GadgetBoxDialogWiring(
        DoudizhuPlugin plugin,
        VirtualGadgetBarStore store,
        GadgetPreviewSnapshotService previewSnapshotService,
        TableGadgetBarHudService barHudService,
        PlayerOutputDispatcher output
    ) {
        this.plugin = plugin;
        this.store = store;
        this.previewSnapshotService = previewSnapshotService;
        this.barHudService = barHudService;
        this.output = output;
    }

    /** 旧箱子 GUI 的打开接缝：委托到编辑 Dialog。 */
    public GadgetBoxDialogOpener opener() {
        return this::openEditor;
    }

    /** 用编辑 Dialog 打开道具箱（player lane 内由 {@link MuzDialogService} 完成展示）。 */
    public void openEditor(Player player) {
        if (player == null) {
            return;
        }
        plugin.muzDialogService().openRoot(player.getUniqueId(), screen());
    }

    /** 构造道具箱编辑屏。 */
    public MuzDialogScreen screen() {
        return new GadgetBoxDialogScreen(barSource(), persistence(), selectAction(), voiceAction());
    }

    /**
     * 读取当前八槽只读快照。
     *
     * <p>在 Dialog 打开的 player lane 内调用一次，读的是 {@code TableGadgetBarHudService} 的只读视图，
     * 避免在 player lane 直接读 {@code player-settings.yml}（那是文件 IO）。返回 null 时屏幕侧兜底为默认三件。
     */
    private Function<UUID, VirtualGadgetBar> barSource() {
        return playerId -> barHudService == null ? null : barHudService.visibleBar(playerId);
    }

    /**
     * 异步持久化 + player lane 刷新。写盘在 async lane，完成后回 player lane 通知预览与 HUD 服务。
     */
    private GadgetBoxPersistence persistence() {
        return (playerId, bar, onSavedOnPlayerLane) -> plugin.scheduler().runAsync(() -> {
            store.save(playerId, bar);
            output.enqueuePlayer(playerId, player -> {
                if (previewSnapshotService != null) {
                    previewSnapshotService.onSaved(playerId);
                }
                if (barHudService != null) {
                    barHudService.onSaved(playerId);
                }
                if (onSavedOnPlayerLane != null) {
                    onSavedOnPlayerLane.run();
                }
            });
        });
    }

    /** 选择确认：真实槽位 0..7。 */
    private BiConsumer<UUID, Integer> selectAction() {
        return (playerId, realSlot) -> {
            if (barHudService != null) {
                barHudService.select(playerId, realSlot);
            }
        };
    }

    /** 语音入口：先关 Dialog，再在合法 lane 打开私有语音面板。 */
    private Consumer<UUID> voiceAction() {
        return playerId -> output.enqueuePlayer(playerId, player -> {
            if (barHudService != null) {
                barHudService.openVoicePanel(player);
            }
        });
    }
}
