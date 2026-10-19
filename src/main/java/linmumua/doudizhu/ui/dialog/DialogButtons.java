package linmumua.doudizhu.ui.dialog;

import io.papermc.paper.registry.data.dialog.ActionButton;
import io.papermc.paper.registry.data.dialog.action.DialogAction;
import java.time.Duration;
import java.util.UUID;
import java.util.function.Consumer;
import linmumua.doudizhu.DoudizhuPlugin;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.event.ClickCallback;

/**
 * 把「按钮点击」包装成一次受控回调。
 *
 * <p>原生 Dialog 的 {@code DialogAction.customClick} 回调会携带 {@code DialogResponseView}
 * 与 {@code Audience} 直接执行。若直接把它交给屏实现，玩家点了旧面板的按钮、或在插件关闭后
 * 才收到回调，就会执行不该执行的动作。因此这里统一经 {@link MuzDialogService#invokeGuarded}
 * 投递：回调内复核关闭期、权限、会话代次，并切回该玩家的 owner lane 后才执行。
 *
 * <p>屏实现通常用 {@link MuzDialogContext#action}，它已经绑定好玩家、代次与权限；本类是更下层
 * 的入口，便于测试与特殊场景。
 */
public final class DialogButtons {
    /**
     * 单次回调的有效期。取 5 分钟：足够玩家从容填完表，又不会让一个挂着的面板在很久之后
     * 仍然回调成功。与 {@link DialogSessionRegistry#DEFAULT_TTL_MILLIS} 配合，二者取先到者。
     */
    private static final Duration CALLBACK_LIFETIME = Duration.ofMinutes(5);

    private DialogButtons() {
    }

    /** 单次可用、5 分钟有效的回调选项。 */
    public static ClickCallback.Options singleUseOptions() {
        return ClickCallback.Options.builder()
            .uses(1)
            .lifetime(CALLBACK_LIFETIME)
            .build();
    }

    /** 带输入读取的按钮回调。 */
    public static ActionButton callback(
        DoudizhuPlugin plugin,
        MuzDialogService service,
        UUID playerId,
        long generation,
        String permission,
        Component label,
        Component tooltip,
        int width,
        Consumer<io.papermc.paper.dialog.DialogResponseView> handler
    ) {
        DialogAction action = DialogAction.customClick(
            (view, audience) -> service.invokeGuarded(playerId, generation, permission, handler, view),
            singleUseOptions()
        );
        return MuzDialogFactory.button(label, tooltip, width, action);
    }

    /** 无输入的按钮回调。 */
    public static ActionButton callback(
        DoudizhuPlugin plugin,
        MuzDialogService service,
        UUID playerId,
        long generation,
        String permission,
        Component label,
        Runnable handler
    ) {
        return callback(plugin, service, playerId, generation, permission, label, null,
            MuzDialogFactory.DEFAULT_BUTTON_WIDTH, view -> handler.run());
    }
}
