package linmumua.doudizhu.ui.dialog;

import java.util.Objects;
import java.util.function.Consumer;

/**
 * 椅子左右面板布局的**唯一读写接缝**。
 *
 * <p>为什么是接口而不是直接调插件：布局的持久化归配置层（config）、世界面板的重建归渲染侧
 * （panel），编辑屏只是「读数 → 改一个字段 → 存回」。三件事拆开后，屏才能在无 Bukkit 的环境里
 * 被测试覆盖，配置层与渲染侧也能各自演进而不动 UI。
 *
 * <p>【保存必须是异步的】保存走既有配置保存流程，接口因此是**回调式**而不是「调完就当成功」：
 * <ul>
 *   <li>落盘可能失败（磁盘写不进、校验失败）。失败时 schema 会在锁内回滚内存，
 *       并给出**实际生效的旧值**与可直接展示的中文原因；</li>
 *   <li>回调前**不得**阻塞等待（{@code future.get()} 之类）：那会把磁盘 IO 拉回玩家的
 *       owner lane（player lane），在 Folia 上是实打实的卡服。</li>
 * </ul>
 *
 * <p>线程约定：
 * <ul>
 *   <li>{@link #read()} 由屏在 player lane 内调用，只读内存快照，不碰磁盘；</li>
 *   <li>{@link #save} 由屏在 player lane 的回调里调用，实现方只许派发、立刻返回；</li>
 *   <li>{@link #onSaved} / {@link #onRejected} 可能在**任意线程**（IO 线程）被调用，
 *       屏一侧只做「提示 / 重开面板」，都由既有的 player lane 门面转投；</li>
 *   <li>{@link #apply} 同样可能在任意线程被调用，实现方必须自行投递到正确的 lane，
 *       且不得在回调里阻塞。</li>
 * </ul>
 */
public interface ChairPanelLayoutPort {
    /** 读取当前布局快照（已归一化；配置缺键回落出厂默认，绝不返回 null）。 */
    ChairPanelLayout read();

    /**
     * 保存整份布局。
     *
     * @param next       要保存的布局
     * @param onSaved    保存成功回调，收到**实际生效**的布局（可能与传入值精度不同）
     * @param onRejected 保存失败回调，消息可直接展示给管理员；此时不应再刷新世界面板
     */
    void save(ChairPanelLayout next, Consumer<ChairPanelLayout> onSaved, Consumer<String> onRejected);

    /**
     * 让渲染方按最新布局重建/刷新面板。
     *
     * @param layout 已生效的布局
     */
    void apply(ChairPanelLayout layout);

    /**
     * 一次修改的唯一落点：保存 → 成功后应用。
     *
     * <p>抽成静态方法而不是让每个屏自己写：{@code save} 与 {@code apply} 必须成对，
     * 少一个就会出现「配置改了但世界面板没动」或反过来的半截状态；
     * 而且**失败时绝不能 apply**——那会让世界面板先于配置变样，回滚后两边对不上。
     *
     * <p>抽出来之后这条性质可以用记录型替身直接打行为测试，不必退化成扫源码字符串。
     */
    static void saveThenApply(
        ChairPanelLayoutPort port,
        ChairPanelLayout next,
        Consumer<ChairPanelLayout> afterApplied,
        Consumer<String> onRejected
    ) {
        if (port == null || next == null) {
            throw new IllegalArgumentException("接缝与布局不能为空");
        }
        port.save(
            next,
            saved -> {
                port.apply(saved);
                if (afterApplied != null) {
                    afterApplied.accept(saved);
                }
            },
            message -> {
                if (onRejected != null) {
                    onRejected.accept(message);
                }
            }
        );
    }
}
