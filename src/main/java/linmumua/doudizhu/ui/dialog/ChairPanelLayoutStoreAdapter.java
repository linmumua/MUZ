package linmumua.doudizhu.ui.dialog;

import java.util.Objects;
import java.util.function.Consumer;
import linmumua.doudizhu.config.ChairSidePanelLayoutStore;
import linmumua.doudizhu.config.ChairSidePanelSettings;

/**
 * 把配置层的 {@link ChairSidePanelLayoutStore} 接到编辑屏上的适配器。
 *
 * <p>这一层只做三件事，刻意不含任何策略：
 * <ol>
 *   <li>{@link #read()} 直接取 {@code store.snapshot()}（内存快照，不碰磁盘）；</li>
 *   <li>{@link #save} 调 {@code store.saveAsync(...)} 并**立刻返回**，在 future 完成时把结果
 *       翻译成 {@code onSaved} / {@code onRejected} 回调。绝不 {@code get()} / {@code join()}：</li>
 *   <li>{@link #apply} 把已生效的布局交给渲染侧的刷新入口。</li>
 * </ol>
 *
 * <p>为什么不在 {@code save} 里等结果：调用点是玩家的 owner lane（player lane）。在那里等待磁盘
 * 会把整条 lane 卡住（Folia 上尤其明显），而配置层已经把「派发 → IO 线程写 → 失败回滚」
 * 做完了，我们只需要接回调。
 *
 * <p>回调线程：future 在配置层的 IO 线程完成，所以 {@code onSaved}/{@code onRejected} 可能在
 * 非玩家线程执行。屏一侧的提示与重开都经既有 player lane 门面转投，因此这里不需要额外切线程；
 * {@code repaint} 的实现方负责自己的 lane（见 {@link ChairPanelLayoutPort} 的线程约定）。
 */
public final class ChairPanelLayoutStoreAdapter implements ChairPanelLayoutPort {
    private final ChairSidePanelLayoutStore store;
    private final Consumer<ChairSidePanelSettings> repaint;

    /**
     * @param store   配置层的读写入口（插件持有的那一份，不要自己新建）
     * @param repaint 渲染侧的重建/刷新入口，接收已生效的布局
     */
    public ChairPanelLayoutStoreAdapter(ChairSidePanelLayoutStore store, Consumer<ChairSidePanelSettings> repaint) {
        this.store = Objects.requireNonNull(store, "布局存储不能为空");
        this.repaint = Objects.requireNonNull(repaint, "渲染刷新入口不能为空");
    }

    @Override
    public ChairPanelLayout read() {
        return ChairPanelLayout.of(store.snapshot());
    }

    @Override
    public void save(ChairPanelLayout next, Consumer<ChairPanelLayout> onSaved, Consumer<String> onRejected) {
        Objects.requireNonNull(next, "要保存的布局不能为空");
        ChairSidePanelSettings target = next.toSettings();
        store.saveAsync(target).whenComplete((result, failure) -> {
            if (failure != null) {
                reject(onRejected, "保存失败：" + describe(failure));
                return;
            }
            if (result == null) {
                reject(onRejected, "保存失败：配置层没有返回结果。");
                return;
            }
            if (!result.success()) {
                reject(onRejected, result.message().isBlank() ? "保存失败。" : result.message());
                return;
            }
            if (onSaved != null) {
                // 用配置层返回的「实际生效」快照，而不是我们提交的那份：精度归一 / 回写
                // 之后真实值可能略有差别，界面必须显示真的有功的那一份。
                onSaved.accept(ChairPanelLayout.of(result.settings()));
            }
        });
    }

    @Override
    public void apply(ChairPanelLayout layout) {
        Objects.requireNonNull(layout, "布局不能为空");
        repaint.accept(layout.toSettings());
    }

    private static void reject(Consumer<String> onRejected, String message) {
        if (onRejected != null) {
            onRejected.accept(message);
        }
    }

    private static String describe(Throwable failure) {
        String message = failure.getMessage();
        return message == null || message.isBlank() ? failure.getClass().getSimpleName() : message;
    }
}
