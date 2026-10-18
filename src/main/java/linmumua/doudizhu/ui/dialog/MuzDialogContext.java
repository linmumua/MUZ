package linmumua.doudizhu.ui.dialog;

import io.papermc.paper.dialog.DialogResponseView;
import io.papermc.paper.registry.data.dialog.ActionButton;
import java.util.UUID;
import java.util.function.Consumer;
import linmumua.doudizhu.DoudizhuPlugin;
import net.kyori.adventure.text.Component;
import org.bukkit.entity.Player;

/**
 * 构建与操作一个屏的上下文。
 *
 * <p>由 {@link MuzDialogService} 在 player lane 内构造并交给 {@link MuzDialogScreen#build}。
 * 它把玩家、本次会话代次、权限与按钮/导航入口一次性交给屏实现，屏因此不需要接触调度、
 * 会话代次或 {@code player.showDialog}。
 *
 * <p>所有按钮都通过 {@link #action} 构建，回调自动绑定本上下文的玩家、代次与权限；点击时
 * core 会复核关闭期、权限与会话代次，并切回 player lane 后才执行 handler。
 *
 * <p>{@link #player()} 只在 build 与回调的 player lane 内有效；需要长期保存玩家标识时请用
 * {@link #playerId()}。
 */
public final class MuzDialogContext {
    private final DoudizhuPlugin plugin;
    private final MuzDialogService service;
    private final Player player;
    private final long generation;
    private final String screenKey;
    private final String screenPermission;

    MuzDialogContext(
        DoudizhuPlugin plugin,
        MuzDialogService service,
        Player player,
        long generation,
        String screenKey,
        String screenPermission
    ) {
        this.plugin = plugin;
        this.service = service;
        this.player = player;
        this.generation = generation;
        this.screenKey = screenKey;
        this.screenPermission = screenPermission;
    }

    public DoudizhuPlugin plugin() {
        return plugin;
    }

    public UUID playerId() {
        return player.getUniqueId();
    }

    public Player player() {
        return player;
    }

    public long generation() {
        return generation;
    }

    public String screenKey() {
        return screenKey;
    }

    public MuzDialogService service() {
        return service;
    }

    public boolean hasPermission(String permission) {
        return permission == null || permission.isBlank() || player.hasPermission(permission);
    }

    // —— 按钮 ——

    public ActionButton action(Component label, Consumer<DialogResponseView> handler) {
        return action(label, null, 1, handler);
    }

    public ActionButton action(Component label, Component tooltip, int width, Consumer<DialogResponseView> handler) {
        return DialogButtons.callback(
            plugin, service, playerId(), generation, screenPermission, label, tooltip, width, handler
        );
    }

    public ActionButton action(Component label, Runnable handler) {
        return action(label, null, 1, view -> handler.run());
    }

    /** 退出按钮（无动作，仅关闭）。 */
    public ActionButton exit(Component label) {
        return MuzDialogFactory.exitButton(label);
    }

    // —— 输入读取 ——

    /**
     * 读取文本输入。内部兼容命名空间前缀：先按传入 key 读，再按 {@code "muz:" + key} 读。
     */
    public String text(DialogResponseView view, String key) {
        if (view == null || key == null) {
            return null;
        }
        for (String candidate : candidates(key)) {
            String value = view.getText(candidate);
            if (value != null) {
                return value;
            }
        }
        return null;
    }

    public Boolean bool(DialogResponseView view, String key) {
        if (view == null || key == null) {
            return null;
        }
        for (String candidate : candidates(key)) {
            Boolean value = view.getBoolean(candidate);
            if (value != null) {
                return value;
            }
        }
        return null;
    }

    public Float number(DialogResponseView view, String key) {
        if (view == null || key == null) {
            return null;
        }
        for (String candidate : candidates(key)) {
            Float value = view.getFloat(candidate);
            if (value != null) {
                return value;
            }
        }
        return null;
    }

    private static String[] candidates(String key) {
        return new String[] { key, "muz:" + key };
    }

    // —— 导航 ——

    /** 用目标屏替换当前屏（不入栈）。 */
    public void open(MuzDialogScreen screen) {
        service.replace(player.getUniqueId(), screen);
    }

    /** 打开并入栈；之后 {@link #back()} 可返回本屏。 */
    public void push(MuzDialogScreen screen) {
        service.push(player.getUniqueId(), screen);
    }

    /** 返回上一屏；栈空则关闭。 */
    public void back() {
        service.back(player.getUniqueId());
    }

    public void close() {
        service.close(player.getUniqueId());
    }

    public void message(Component text) {
        service.message(player.getUniqueId(), text);
    }

    public void error(String text) {
        service.error(player.getUniqueId(), text);
    }
}
