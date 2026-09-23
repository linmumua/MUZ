package linmumua.doudizhu.ui;

import java.nio.file.Path;
import java.util.function.Consumer;
import linmumua.doudizhu.game.PlayerOutputDispatcher;

/**
 * 牌桌道具箱的主装配门面。
 *
 * <p>继承独立 GUI 实现，保持主类只依赖牌桌语义命名；实例自身也是 Listener，
 * 注册该实例即可。
 */
public class TableGadgetGuiService extends GadgetBoxGuiService {
    public TableGadgetGuiService(Path playerSettingsFile) {
        super(new VirtualGadgetBarStore(playerSettingsFile));
    }

    public TableGadgetGuiService(VirtualGadgetBarStore store) {
        super(store);
    }

    public TableGadgetGuiService(
        VirtualGadgetBarStore store,
        Consumer<GadgetBoxGuiService.Action> actionConsumer
    ) {
        super(store, actionConsumer);
    }

    public TableGadgetGuiService(
        VirtualGadgetBarStore store,
        Consumer<GadgetBoxGuiService.Action> actionConsumer,
        String title,
        String bubbleName
    ) {
        super(store, actionConsumer, title, bubbleName);
    }

    public TableGadgetGuiService(
        VirtualGadgetBarStore store,
        Consumer<GadgetBoxGuiService.Action> actionConsumer,
        Consumer<java.util.UUID> saveListener,
        String title,
        String bubbleName
    ) {
        super(store, actionConsumer, saveListener, title, bubbleName);
    }

    public TableGadgetGuiService(
        VirtualGadgetBarStore store,
        Consumer<GadgetBoxGuiService.Action> actionConsumer,
        Consumer<java.util.UUID> saveListener,
        String title,
        String bubbleName,
        PlayerOutputDispatcher output
    ) {
        super(store, actionConsumer, saveListener, title, bubbleName, output);
    }
}
