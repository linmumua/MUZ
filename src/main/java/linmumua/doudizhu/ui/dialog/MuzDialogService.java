package linmumua.doudizhu.ui.dialog;

import io.papermc.paper.dialog.Dialog;
import io.papermc.paper.dialog.DialogResponseView;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;
import linmumua.doudizhu.DoudizhuPlugin;
import linmumua.doudizhu.game.PlayerOutputDispatcher;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.entity.Player;

/**
 * Dialog 展示、导航与生命周期的唯一入口。
 *
 * <p>职责：
 * <ul>
 *   <li>所有 {@code player.showDialog} / {@code player.closeDialog} 都只在这里、且只在
 *       {@link PlayerOutputDispatcher} 的 player lane 内调用；</li>
 *   <li>每次打开某个屏都经 {@link DialogSessionRegistry#begin} 开新会话，使旧回调立即失效；</li>
 *   <li>维护每玩家的导航栈，支持「返回上一屏」；</li>
 *   <li>按钮回调经 {@link #invokeGuarded} 复核关闭期、权限、会话代次并切回 player lane；</li>
 *   <li>退出 / reload / disable 时清理会话与栈。</li>
 * </ul>
 *
 * <p>线程约定：{@link #openRoot} / {@link #push} / {@link #back} / {@link #close} / {@link #refresh}
 * 可在任意线程调用（内部统一投递到 player lane）；{@code build} 与按钮回调在 player lane 内执行。
 */
public final class MuzDialogService {
    /**
     * 「把构造好的 Dialog 交给玩家」这一动作的接缝。
     *
     * <p>【为什么要抽出来】生产实现就是 {@code player.showDialog(dialog)}；抽成接缝之后，
     * 行为测试可以观察「是否真的尝试展示」「构建失败有没有留痕并提示玩家」，而不必伪造
     * Paper 的 Dialog 实例（Dialog 的构建依赖服务端注册的 {@code DialogInstancesProvider}，
     * 纯 JVM 里造不出来）。
     *
     * <p>【为什么没有返回值】Adventure 的 {@code Audience#showDialog(DialogLike)} 是
     * {@code void}——客户端是否真的渲染出来无法在服务端观测，因此这里不假装能判断，
     * 只把「调用是否抛异常」当成失败判据。
     */
    @FunctionalInterface
    public interface DialogPresenter {
        void present(Player player, Dialog dialog);
    }

    /** MuzDialogService 的关闭/离线原因，仅用于日志与玩家提示，不含业务分支。 */
    private static final String REASON_SERVICE_CLOSED = "面板服务已关闭";

    private final DoudizhuPlugin plugin;
    private final PlayerOutputDispatcher output;
    private final DialogSessionRegistry sessions;
    private final DialogPresenter presenter;
    private final Map<UUID, Deque<MuzDialogScreen>> stacks = new ConcurrentHashMap<>();

    /** 关闭前是否已经清理过，避免重复清理抛异常。 */
    private volatile boolean closed;

    public MuzDialogService(DoudizhuPlugin plugin, PlayerOutputDispatcher output, DialogSessionRegistry sessions) {
        // 显式 lambda 而不是 Player::showDialog：Adventure 的 showDialog 形参是 DialogLike，
        // 方法引用不接受把 Dialog 收窄成父类型的适配，会直接编译失败。
        this(plugin, output, sessions, (player, dialog) -> player.showDialog(dialog));
    }

    /** 测试构造：注入展示接缝，生产代码一律用三参构造。 */
    public MuzDialogService(
        DoudizhuPlugin plugin,
        PlayerOutputDispatcher output,
        DialogSessionRegistry sessions,
        DialogPresenter presenter
    ) {
        this.plugin = plugin;
        this.output = output;
        this.sessions = sessions;
        this.presenter = presenter == null ? Player::showDialog : presenter;
    }

    public DialogSessionRegistry sessions() {
        return sessions;
    }

    /** 以给定屏为栈底打开（清空原有栈）。 */
    public void openRoot(UUID playerId, MuzDialogScreen screen) {
        if (playerId == null || screen == null) {
            return;
        }
        Deque<MuzDialogScreen> stack = stack(playerId);
        synchronized (stack) {
            stack.clear();
            stack.addLast(screen);
        }
        show(playerId, screen);
    }

    /** 打开并入栈。 */
    public void push(UUID playerId, MuzDialogScreen screen) {
        if (playerId == null || screen == null) {
            return;
        }
        Deque<MuzDialogScreen> stack = stack(playerId);
        synchronized (stack) {
            stack.addLast(screen);
        }
        show(playerId, screen);
    }

    /** 用目标屏替换当前屏（不入栈）。 */
    public void replace(UUID playerId, MuzDialogScreen screen) {
        if (playerId == null || screen == null) {
            return;
        }
        Deque<MuzDialogScreen> stack = stack(playerId);
        synchronized (stack) {
            if (!stack.isEmpty()) {
                stack.removeLast();
            }
            stack.addLast(screen);
        }
        show(playerId, screen);
    }

    /** 返回上一屏；栈空则关闭。 */
    public void back(UUID playerId) {
        if (playerId == null) {
            return;
        }
        MuzDialogScreen previous;
        Deque<MuzDialogScreen> stack = stack(playerId);
        synchronized (stack) {
            if (stack.size() <= 1) {
                previous = null;
            } else {
                stack.removeLast();
                previous = stack.peekLast();
            }
        }
        if (previous == null) {
            close(playerId);
        } else {
            show(playerId, previous);
        }
    }

    /** 关闭当前 Dialog 并结束会话、清空栈。 */
    public void close(UUID playerId) {
        if (playerId == null) {
            return;
        }
        sessions.end(playerId);
        Deque<MuzDialogScreen> stack = stacks.remove(playerId);
        if (stack != null) {
            synchronized (stack) {
                stack.clear();
            }
        }
        output.enqueuePlayer(playerId, Player::closeDialog);
    }

    /** 用当前栈顶屏重开（例如保存后刷新数值）。 */
    public void refresh(UUID playerId) {
        if (playerId == null) {
            return;
        }
        MuzDialogScreen current;
        Deque<MuzDialogScreen> stack = stack(playerId);
        synchronized (stack) {
            current = stack.peekLast();
        }
        if (current != null) {
            show(playerId, current);
        }
    }

    /** 退出 / 离线清理。 */
    public void clearPlayer(UUID playerId) {
        if (playerId == null) {
            return;
        }
        sessions.clear(playerId);
        Deque<MuzDialogScreen> stack = stacks.remove(playerId);
        if (stack != null) {
            synchronized (stack) {
                stack.clear();
            }
        }
    }

    /** 插件关闭 / reload 清理。 */
    public void shutdown() {
        closed = true;
        invalidateAll();
    }

    /**
     * 作废所有会话与导航栈并关闭已打开的 Dialog，但**不关闭服务本身**（之后仍可新开）。
     *
     * <p>用于 {@code /muz reload}：既让所有旧 Dialog 回调与进行中的异步请求（如战绩查询）失效，
     * 又不至于让服务进入不可逆的关闭态——reload 之后玩家应当能立刻重新打开面板。
     *
     * <p>与 {@link #shutdown()} 的区别：{@code shutdown()} 额外置 {@code closed}，之后不再接受任何展示。
     */
    public void invalidateAll() {
        java.util.Set<UUID> affected = new java.util.HashSet<>(stacks.keySet());
        affected.addAll(sessionsTrackedPlayers());
        sessions.clearAll();
        for (Deque<MuzDialogScreen> stack : stacks.values()) {
            synchronized (stack) {
                stack.clear();
            }
        }
        stacks.clear();
        for (UUID playerId : affected) {
            if (playerId != null) {
                output.enqueuePlayer(playerId, Player::closeDialog);
            }
        }
    }

    private java.util.Set<UUID> sessionsTrackedPlayers() {
        return sessions.trackedPlayers();
    }

    /**
     * 开始一次「导航请求」：把一次即将发起的异步准备（如战绩读库）绑到当前会话代次上。
     *
     * <p>这样在异步结果回来之前，只要玩家换了菜单 / 关了面板 / 退出 / reload，会话代次就会变，
     * {@link #requestCurrent} 随即返回 false，迟到的查询结果不得再 {@code openRoot} 抢回屏幕。
     *
     * @return 本次请求代次（&gt; 0）
     */
    public long beginRequest(UUID playerId, String screenKey) {
        return sessions.begin(playerId, screenKey);
    }

    /** 该请求代次是否仍是该玩家的当前会话（未被替换 / 关闭 / 过期）。 */
    public boolean requestCurrent(UUID playerId, long requestGeneration) {
        return sessions.current(playerId, requestGeneration);
    }

    /** 周期推进：清理过期会话。 */
    public void tick() {
        sessions.expireStale();
    }

    /** 发送聊天提示。 */
    public void message(UUID playerId, Component text) {
        if (text != null) {
            output.sendMessage(playerId, text);
        }
    }

    /** 发送红色错误提示。 */
    public void error(UUID playerId, String text) {
        if (text != null && !text.isBlank()) {
            output.sendMessage(playerId, Component.text(text, NamedTextColor.RED));
        }
    }

    /**
     * 受控回调入口：复核关闭期、权限、会话代次后切回 player lane 执行 handler。
     *
     * <p>由 {@link DialogButtons} 在按钮构建时绑定到 {@code DialogAction.customClick}。
     */
    void invokeGuarded(
        UUID playerId,
        long generation,
        String permission,
        Consumer<DialogResponseView> handler,
        DialogResponseView view
    ) {
        if (closed || plugin.isShuttingDown()) {
            // 关闭期不提交被拒绝的任务：直接丢弃，不排入 player lane。
            return;
        }
        if (playerId == null || handler == null) {
            return;
        }
        output.enqueuePlayer(playerId, player -> {
            if (!plugin.isEnabled() || plugin.isShuttingDown()) {
                return;
            }
            if (permission != null && !permission.isBlank() && !player.hasPermission(permission)) {
                player.sendMessage(Component.text("你没有权限执行这个操作。", NamedTextColor.RED));
                return;
            }
            if (!sessions.current(playerId, generation)) {
                // 陈旧回调：屏已被替换 / 关闭 / 过期。
                return;
            }
            handler.accept(view);
        });
    }

    /**
     * 展示一个屏：切到该玩家的 player lane，复核关闭期/权限/会话代次后 build 并展示。
     *
     * <p>【为什么这里必须留痕且必须给玩家提示】此前这条链上的失败有四种静默出口：
     * 服务已关闭、插件停用/关闭中、{@code build} 抛异常（原先只发 WARNING）、{@code dialog == null}；
     * 而 {@code debug.enabled=false}（出厂默认）会把本插件**全部** WARNING 静默（见 {@code MuzLogGate}），
     * 于是「牌桌面板点了没反应」在服务端日志与玩家屏幕两侧都没有任何痕迹，只能靠猜。
     * 现在：关闭期/停用直接拒绝且不投递（不做无意义的排队），build 失败与展示未生效
     * 一律按 SEVERE 记录（MuzLogGate 只过滤 WARNING，SEVERE 永远输出）并给玩家一句明确提示。
     */
    private void show(UUID playerId, MuzDialogScreen screen) {
        if (closed) {
            reportUnavailable(playerId, screen, REASON_SERVICE_CLOSED, null);
            return;
        }
        if (!plugin.isEnabled() || plugin.isShuttingDown()) {
            // 关闭期不投递：投进去也只会在 lane 里被同一判据挡掉，白白占一个任务。
            reportUnavailable(playerId, screen, "插件已停用或正在关闭", null);
            return;
        }
        output.enqueuePlayer(playerId, player -> {
            if (!plugin.isEnabled() || plugin.isShuttingDown()) {
                reportUnavailable(playerId, screen, "插件已停用或正在关闭", null);
                return;
            }
            String permission = screen.permission();
            if (permission != null && !permission.isBlank() && !player.hasPermission(permission)) {
                player.sendMessage(Component.text("你没有权限执行这个操作。", NamedTextColor.RED));
                return;
            }
            long generation = sessions.begin(playerId, screen.screenKey());
            MuzDialogContext ctx = new MuzDialogContext(
                plugin, this, player, generation, screen.screenKey(), permission
            );
            Dialog dialog;
            try {
                dialog = screen.build(ctx);
            } catch (RuntimeException | Error failure) {
                reportUnavailable(playerId, screen, "构建面板失败", failure);
                return;
            }
            if (dialog == null) {
                reportUnavailable(playerId, screen, "构建面板返回空", null);
                return;
            }
            try {
                presenter.present(player, dialog);
            } catch (RuntimeException | Error failure) {
                reportUnavailable(playerId, screen, "展示面板时抛异常", failure);
            }
        });
    }

    /**
     * 面向「面板打不开」的统一失败出口：SEVERE（带栈，不受 debug 门禁影响）+ 玩家可见提示。
     *
     * <p>【关闭期只留痕、不投递】服务已关闭或插件停用/关闭中时不再往 player lane 排提示任务：
     * 那时侯玩家要么已经离线，要么服务端正在拆解，排在队伍里的消息只会变成噪声，而且会违反
     * 「关闭期不误投递」这条约束。此时只有服务端日志需要这条线索。
     *
     * <p>【其余情况一律提示玩家】面板打不开必须让玩家看见，否则表现为「点了没反应」；
     * 提示仍经 dispatcher 回到该玩家自己的 player lane，不在这里直接碰 Player API。
     */
    private void reportUnavailable(UUID playerId, MuzDialogScreen screen, String reason, Throwable failure) {
        String screenKey = screen == null ? "unknown" : screen.screenKey();
        if (failure == null) {
            plugin.getLogger().severe("Dialog 未能展示（" + screenKey + "）：" + reason);
        } else {
            plugin.getLogger().log(
                java.util.logging.Level.SEVERE, "Dialog 未能展示（" + screenKey + "）：" + reason, failure
            );
        }
        if (playerId == null || closed || !plugin.isEnabled() || plugin.isShuttingDown()) {
            return;
        }
        Component hint = Component.text("面板打开失败：" + reason + "（详情见服务端日志）", NamedTextColor.RED);
        output.sendMessage(playerId, hint);
    }

    private Deque<MuzDialogScreen> stack(UUID playerId) {
        return stacks.computeIfAbsent(playerId, ignored -> new ArrayDeque<>());
    }
}
