package linmumua.doudizhu.config;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import java.util.function.Supplier;
import java.util.logging.Level;
import java.util.logging.Logger;
import org.bukkit.plugin.Plugin;

/**
 * 椅侧双面板布局的持久化出口：把一份 {@link ChairSidePanelSettings} 落进 {@code config.yml}。
 *
 * <p>【为什么单独一个类】布局是「经常被游戏内 Dialog 反复改」的配置，写盘频率远高于其它段；
 * 它的失败模式也特殊——玩家在界面里点了保存，如果写盘失败却提示成功，下次重载面板就会
 * 跳回旧位置，这种"假成功"比直接报错难排查得多。因此把整条事务收在一处：
 * <b>加锁 → 深拷贝旧根 → 写内存 → 落盘 → 失败回滚并如实返回失败</b>。
 *
 * <p>【线程边界】
 * <ul>
 *   <li>{@link #saveAsync} 是 Dialog 回调与命令层的<b>唯一</b>入口：它只做派发，
 *       立刻返回 {@link CompletableFuture}，调用方线程（玩家 lane / 主线程）绝不碰磁盘。</li>
 *   <li>{@link #saveToDisk} 是真正的事务，<b>只能在注入的 IO 执行器线程上调用</b>；
 *       本类刻意不检查当前是否主线程，因为区域化核心上"主线程"的判定本身就不可靠，
 *       改为把"谁来调度"交给调用方的执行器。</li>
 *   <li>读写共享同一把锁：{@link #snapshot} 也加锁，避免与写盘中的
 *       {@code MuzYamlConfig} 结构变更并发读出一半新一半旧的映射。</li>
 * </ul>
 *
 * <p>【锁的边界（如实）】这把锁只能串行化<b>同样使用它</b>的入口。项目里其它旧配置入口
 * （历史管理菜单等）尚未全部接入同一把锁，因此本类不能宣称消除了全部配置竞态；
 * 与 Debug Web 的 HUD 保存是同一口径的既有约束，不是本类新引入的缺陷。
 *
 * <p>【不碰 Bukkit】本类只依赖 {@link MuzYamlConfig}、JDK 与 {@link Logger}，
 * 不读玩家、不读实体、不查在线列表，因此可以在任意 lane 上安全构造与查询快照。
 */
public final class ChairSidePanelLayoutStore {
    /** 落盘动作接缝：生产实现是 {@link MuzYamlConfig#saveWithComments}，测试可注入失败实现。 */
    @FunctionalInterface
    public interface DiskWriter {
        void write(MuzYamlConfig config, String template) throws IOException;
    }

    /**
     * 一次保存的结果。
     *
     * @param success  是否真的写进了文件；任何异常路径都必须是 {@code false}
     * @param settings 保存后<b>实际生效</b>的快照（失败时是回滚后的旧值，供调用方重绘回旧布局）
     * @param message  失败原因（中文，可直接展示给管理员）；成功时为空串
     */
    public record SaveResult(boolean success, ChairSidePanelSettings settings, String message) {
        public SaveResult {
            Objects.requireNonNull(settings, "settings");
            message = message == null ? "" : message;
        }

        public static SaveResult saved(ChairSidePanelSettings settings) {
            return new SaveResult(true, settings, "");
        }

        public static SaveResult failed(ChairSidePanelSettings rolledBack, String message) {
            return new SaveResult(false, rolledBack, message);
        }
    }

    private final MuzYamlConfig config;
    private final Object lock;
    private final Supplier<String> templateSupplier;
    private final DiskWriter diskWriter;
    private final Executor ioExecutor;
    private final Logger logger;

    /**
     * 生产构造：落盘走 {@code saveWithComments(template)}，保留 config.yml 的注释。
     *
     * @param config           主配置对象（插件持有的那一份，不是临时读出来的副本）
     * @param lock             配置锁；传插件提供的同一把锁，才能与其它入口串行
     * @param templateSupplier 打包在 jar 里的 config.yml 模板；返回 null 时退化为无注释保存
     * @param ioExecutor       异步写盘执行器；必须是单线程或至少能保证串行消费
     * @param logger           日志出口；为 null 时静默（测试用）
     */
    public ChairSidePanelLayoutStore(MuzYamlConfig config, Object lock, Supplier<String> templateSupplier,
                                     Executor ioExecutor, Logger logger) {
        this(config, lock, templateSupplier, ioExecutor, logger,
            (target, template) -> target.saveWithComments(template));
    }

    /** 可注入落盘动作的构造，供测试模拟写盘失败与回滚。 */
    ChairSidePanelLayoutStore(MuzYamlConfig config, Object lock, Supplier<String> templateSupplier,
                              Executor ioExecutor, Logger logger, DiskWriter diskWriter) {
        this.config = Objects.requireNonNull(config, "config");
        this.lock = Objects.requireNonNull(lock, "lock");
        this.templateSupplier = templateSupplier == null ? () -> null : templateSupplier;
        this.ioExecutor = Objects.requireNonNull(ioExecutor, "ioExecutor");
        this.diskWriter = Objects.requireNonNull(diskWriter, "diskWriter");
        this.logger = logger;
    }

    /**
     * 生产装配入口：插件在 {@code onEnable} 里调一次，把返回的实例保存为字段并对外提供。
     *
     * <p>这是给 screen（Dialog 编辑）与 bridge（面板渲染/桥接）的<b>同一个</b>真实端口——
     * 两侧都必须读这一份快照、走这一个保存出口，不允许任何一侧自己 new 一个 MuzYamlConfig
     * 或自己拼键写盘，否则「谁说了算」又会分裂成多套来源。
     *
     * <p>模板从插件 JAR 里的 {@code config.yml} 现读（{@link Plugin#getResource(String)}），
     * 因此保存不会把注释冲掉；读不到时退化为无注释保存，与既有 {@code saveYamlConfig} 同语义。
     *
     * <p>调用方必须传入<b>插件持有的那份</b> {@code MuzYamlConfig} 与<b>同一把</b>配置锁；
     * 传新建的副本会让保存与运行期读取脱钩。
     *
     * @param config     插件主配置对象（{@code plugin.yamlConfig()} 那一份）
     * @param lock       配置锁（{@code plugin.hudWebConfigLock()} 那一把）
     * @param plugin     用于读打包模板与取 logger 的插件实例
     * @param ioExecutor 异步写盘执行器（建议单线程，保证保存串行）
     */
    public static ChairSidePanelLayoutStore production(MuzYamlConfig config, Object lock,
                                                       Plugin plugin, Executor ioExecutor) {
        Objects.requireNonNull(plugin, "plugin");
        return new ChairSidePanelLayoutStore(config, lock, () -> packagedTemplate(plugin), ioExecutor,
            plugin.getLogger());
    }

    /** 读 JAR 内打包的 config.yml 模板；读不到返回 null，调用方退化为无注释保存。 */
    private static String packagedTemplate(Plugin plugin) {
        try (InputStream stream = plugin.getResource("config.yml")) {
            if (stream == null) {
                return null;
            }
            return new String(stream.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException exception) {
            plugin.getLogger().warning("读取打包 config.yml 模板失败: " + exception.getMessage());
            return null;
        }
    }

    /** 读取当前生效的布局快照；缺键按默认值，非法值抛 {@link IllegalArgumentException}。 */
    public ChairSidePanelSettings snapshot() {
        synchronized (lock) {
            return ChairSidePanelSettings.from(config);
        }
    }

    /**
     * 异步保存：立即返回，实际写盘在注入的 IO 执行器上完成。
     *
     * <p>Dialog 按钮回调只应调用这个方法。<b>不要在回调里 await 它的结果</b>——
     * 那等于把磁盘 IO 拉回玩家 lane，正是本类要避免的事；结果通过回调把面板重画一遍即可。
     *
     * <p>执行器拒绝任务时返回一个已失败的 future，而不是抛给调用方：命令层与 UI 层
     * 不应该因为调度被关停而炸掉。
     */
    public CompletableFuture<SaveResult> saveAsync(ChairSidePanelSettings settings) {
        Objects.requireNonNull(settings, "settings");
        try {
            return CompletableFuture.supplyAsync(() -> saveToDisk(settings), ioExecutor);
        } catch (RuntimeException exception) {
            // 执行器已关闭/拒绝：如实报失败，绝不假装已保存。此时内存还没被改过，
            // 因此回给调用方的是「当前真实生效」的快照（不是它刚提交的那份）。
            ChairSidePanelSettings current = safeSnapshot();
            return CompletableFuture.completedFuture(SaveResult.failed(
                current != null ? current : ChairSidePanelSettings.defaults(),
                "保存布局失败（写盘任务无法提交）：" + describe(exception)));
        }
    }

    /**
     * 同步执行整条保存事务；<b>必须在 IO 线程调用</b>（见类注释的线程边界）。
     *
     * <p>成功时返回写进文件的快照；任何一步失败都先回滚内存配置再返回失败结果，
     * 因此"界面显示已保存、文件里其实没写"在结构上不可能发生。
     */
    public SaveResult saveToDisk(ChairSidePanelSettings settings) {
        Objects.requireNonNull(settings, "settings");
        synchronized (lock) {
            // 保存前的真实状态：写盘失败且回滚后连读都读不出来时，用它兜底，
            // 绝不能把「刚提交但没生效」的值当成当前布局回给调用方。
            ChairSidePanelSettings previous = safeSnapshot();
            Map<String, Object> before = deepCopy(config.rawRoot());
            try {
                settings.writeTo(config);
                diskWriter.write(config, templateSupplier.get());
                return SaveResult.saved(settings);
            } catch (Exception exception) {
                rollback(before);
                String message = "保存椅侧面板布局失败，已回滚内存配置：" + describe(exception);
                log(Level.WARNING, message);
                ChairSidePanelSettings rolledBack = safeSnapshot();
                ChairSidePanelSettings reportable = rolledBack != null ? rolledBack
                    : previous != null ? previous : ChairSidePanelSettings.defaults();
                return SaveResult.failed(reportable, message);
            }
        }
    }

    /**
     * 回滚内存配置。
     *
     * <p>回滚本身也可能抛（例如根节点已被换成非 Map）；那时保留异常现场比吞掉更安全，
     * 因此只记日志、不再抛出——调用方已经在处理失败路径了。
     */
    private void rollback(Map<String, Object> before) {
        try {
            config.set(null, before);
        } catch (RuntimeException rollbackFailure) {
            log(Level.SEVERE, "椅侧面板布局回滚内存配置失败，配置可能处于部分写入状态：" + describe(rollbackFailure));
        }
    }

    /**
     * 尽力读一份快照：内存配置已不可解析（例如回滚本身失败）时返回 {@code null}，
     * 让调用方决定用什么兜底，而不是在这里猜一个"看起来合理"的值。
     */
    private ChairSidePanelSettings safeSnapshot() {
        synchronized (lock) {
            try {
                return ChairSidePanelSettings.from(config);
            } catch (RuntimeException ignored) {
                return null;
            }
        }
    }

    /** 深拷贝根映射：{@code MuzYamlConfig.set} 会就地改嵌套 Map，浅拷贝无法回滚。 */
    @SuppressWarnings("unchecked")
    private static Map<String, Object> deepCopy(Map<String, Object> root) {
        Map<String, Object> copy = new LinkedHashMap<>();
        for (Map.Entry<String, Object> entry : root.entrySet()) {
            copy.put(entry.getKey(), deepCopyValue(entry.getValue()));
        }
        return copy;
    }

    @SuppressWarnings("unchecked")
    private static Object deepCopyValue(Object value) {
        if (value instanceof Map<?, ?> map) {
            Map<String, Object> copy = new LinkedHashMap<>();
            for (Map.Entry<?, ?> entry : map.entrySet()) {
                copy.put(String.valueOf(entry.getKey()), deepCopyValue(entry.getValue()));
            }
            return copy;
        }
        if (value instanceof List<?> list) {
            List<Object> copy = new ArrayList<>(list.size());
            for (Object element : list) {
                copy.add(deepCopyValue(element));
            }
            return copy;
        }
        return value;
    }

    private static String describe(Throwable throwable) {
        String message = throwable.getMessage();
        return message == null || message.isBlank() ? throwable.getClass().getSimpleName() : message;
    }

    private void log(Level level, String message) {
        if (logger != null) {
            logger.log(level, message);
        }
    }
}
