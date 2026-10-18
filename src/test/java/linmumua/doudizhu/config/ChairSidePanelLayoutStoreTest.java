package linmumua.doudizhu.config;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** 椅侧面板布局的持久化事务：异步派发、失败回滚、绝不假成功。 */
class ChairSidePanelLayoutStoreTest {
    @TempDir
    Path tempDir;

    /** 只登记不执行，用来证明 saveAsync 不会在调用线程上碰磁盘。 */
    private static final class ManualExecutor implements Executor {
        private final Deque<Runnable> queued = new ArrayDeque<>();

        @Override
        public void execute(Runnable command) {
            queued.add(command);
        }

        int size() {
            return queued.size();
        }

        void drain() {
            while (!queued.isEmpty()) {
                queued.poll().run();
            }
        }
    }

    private MuzYamlConfig configFile(String yaml) throws IOException {
        Path file = tempDir.resolve("config.yml");
        Files.writeString(file, yaml, StandardCharsets.UTF_8);
        return new MuzYamlConfig(file);
    }

    private ChairSidePanelLayoutStore store(MuzYamlConfig config, Executor executor,
                                            ChairSidePanelLayoutStore.DiskWriter writer, String template) {
        return new ChairSidePanelLayoutStore(config, new Object(), () -> template, executor, null, writer);
    }

    @Test
    void saveWritesThroughToDiskAndReportsTheAppliedSnapshot() throws IOException {
        MuzYamlConfig config = configFile("chair-side-panels: {}\n");
        List<String> writtenTemplates = new ArrayList<>();
        ChairSidePanelLayoutStore store = store(config, Runnable::run,
            (target, template) -> {
                writtenTemplates.add(template);
                target.saveWithComments(template);
            }, "# 模板\nchair-side-panels:\n  enabled: true\n");

        ChairSidePanelSettings submitted = ChairSidePanelSettings.defaults()
            .withLayout(ChairSidePanelSettings.Side.LEFT,
                new ChairSidePanelSettings.PanelLayout(-2.25, 0.5, 1.1, 8.0, 15.0, -3.0));

        ChairSidePanelLayoutStore.SaveResult result = store.saveToDisk(submitted);

        assertTrue(result.success(), "写盘成功时必须如实返回成功：" + result.message());
        assertEquals("", result.message());
        assertEquals(submitted, result.settings());
        assertEquals(List.of("# 模板\nchair-side-panels:\n  enabled: true\n"), writtenTemplates,
            "落盘必须带上打包模板，否则保存会把 config.yml 的注释冲掉");

        // 真的落到文件里了：重新从磁盘读一遍，而不是只看内存。
        ChairSidePanelSettings onDisk = ChairSidePanelSettings.from(new MuzYamlConfig(config.file()));
        assertEquals(submitted, onDisk);
        assertTrue(Files.readString(config.file(), StandardCharsets.UTF_8).contains("# 模板"),
            "保存后注释必须还在");
    }

    @Test
    void failedDiskWriteRollsBackInMemoryAndNeverReportsSuccess() throws IOException {
        // 这是本类存在的核心理由：写盘失败却提示成功，玩家下次重载会看到面板跳回旧位置。
        MuzYamlConfig config = configFile("chair-side-panels: {}\n");
        ChairSidePanelSettings original = ChairSidePanelSettings.defaults();
        ChairSidePanelLayoutStore writerStore = store(config, Runnable::run,
            (target, template) -> target.saveWithComments(template), null);
        assertTrue(writerStore.saveToDisk(original).success());

        ChairSidePanelLayoutStore failing = store(config, Runnable::run,
            (target, template) -> {
                throw new IOException("磁盘只读");
            }, null);
        ChairSidePanelSettings submitted = original.withSize(3.0, 2.0)
            .withLayout(ChairSidePanelSettings.Side.RIGHT,
                new ChairSidePanelSettings.PanelLayout(9.5, 1.0, 2.0, 5.0, 6.0, 7.0));

        ChairSidePanelLayoutStore.SaveResult result = failing.saveToDisk(submitted);

        assertFalse(result.success(), "写盘失败必须返回失败，这是不能妥协的一条");
        assertTrue(result.message().contains("磁盘只读"), "失败原因必须原样带出来：" + result.message());
        assertEquals(original, result.settings(), "失败结果里给的必须是回滚后真实生效的旧值");
        assertEquals(original, failing.snapshot(), "内存配置必须回滚到保存前，不能留下半套新值");
        // 文件也没被动过：回滚发生在写盘之前/之后都不允许出现「文件新、内存旧」的错位。
        assertEquals(original, ChairSidePanelSettings.from(new MuzYamlConfig(config.file())));
    }

    @Test
    void rollbackRestoresPreviousSavedLayoutNotTheFactoryDefault() throws IOException {
        // 回滚必须回到「上一次保存成功的值」，而不是出厂默认：
        // 否则一次失败会把玩家调了很久的布局抹掉。
        MuzYamlConfig config = configFile("chair-side-panels: {}\n");
        ChairSidePanelLayoutStore healthy = store(config, Runnable::run,
            (target, template) -> target.saveWithComments(template), null);
        ChairSidePanelSettings tuned = ChairSidePanelSettings.defaults()
            .withLayout(ChairSidePanelSettings.Side.LEFT,
                new ChairSidePanelSettings.PanelLayout(-1.75, 0.4, 0.95, -6.0, 12.0, 2.0));
        assertTrue(healthy.saveToDisk(tuned).success());

        ChairSidePanelLayoutStore failing = store(config, Runnable::run,
            (target, template) -> {
                throw new IOException("写入被拒绝");
            }, null);
        assertFalse(failing.saveToDisk(tuned.withEnabled(false)).success());

        assertEquals(tuned, failing.snapshot(), "回滚目标必须是上次成功保存的布局");
    }

    @Test
    void saveAsyncQueuesTheIoAndDoesNotTouchDiskOnTheCallingThread() throws IOException {
        MuzYamlConfig config = configFile("chair-side-panels: {}\n");
        ManualExecutor executor = new ManualExecutor();
        AtomicReference<Thread> writerThread = new AtomicReference<>();
        ChairSidePanelLayoutStore store = store(config, executor,
            (target, template) -> {
                writerThread.set(Thread.currentThread());
                target.saveWithComments(template);
            }, null);

        ChairSidePanelSettings submitted = ChairSidePanelSettings.defaults().withSize(1.6, 1.2);
        CompletableFuture<ChairSidePanelLayoutStore.SaveResult> future = store.saveAsync(submitted);

        assertNotNull(future);
        assertEquals(1, executor.size(), "saveAsync 必须把落盘派发到 IO 执行器");
        assertFalse(future.isDone(), "派发后必须立刻返回，不能在调用线程上同步写完");
        assertEquals(ChairSidePanelSettings.DEFAULT_WIDTH, ChairSidePanelSettings.from(config).width(),
            "调用线程返回时内存还应该是旧值（此时还没执行落盘）");

        executor.drain();

        assertTrue(future.isDone());
        assertTrue(future.join().success());
        assertEquals(submitted, future.join().settings());
        assertNotNull(writerThread.get(), "落盘必须真的执行过");
    }

    @Test
    void rejectedExecutorReportsFailureInsteadOfThrowing() throws IOException {
        // 插件关闭或调度器拒绝时，命令层与 UI 层不能被异常炸掉，
        // 但也绝不能因此报告"已保存"。
        MuzYamlConfig config = configFile("chair-side-panels: {}\n");
        Executor rejecting = command -> {
            throw new RejectedExecutionException("调度器已关闭");
        };
        ChairSidePanelLayoutStore store = store(config, rejecting,
            (target, template) -> target.saveWithComments(template), null);

        ChairSidePanelLayoutStore.SaveResult result = store.saveAsync(
            ChairSidePanelSettings.defaults().withEnabled(false)).join();

        assertFalse(result.success(), "任务没能提交时不能报告成功");
        assertTrue(result.message().contains("调度器已关闭"), result.message());
        assertTrue(store.snapshot().enabled(), "内存不能被没执行的保存改掉");
    }

    @Test
    void snapshotReadsTheLiveConfigurationRatherThanACachedCopy() throws IOException {
        // 快照必须每次从共享配置现读：/muz reload 或其它入口改了键之后，
        // 面板层下一次取值就应该看到新布局，不需要谁去"通知"它刷新缓存。
        MuzYamlConfig config = configFile("chair-side-panels: {}\n");
        Object lock = new Object();
        ChairSidePanelLayoutStore store = new ChairSidePanelLayoutStore(config, lock, () -> null,
            Runnable::run, null, (target, template) -> target.saveWithComments(template));

        ChairSidePanelSettings tuned = ChairSidePanelSettings.defaults()
            .withLayout(ChairSidePanelSettings.Side.RIGHT,
                new ChairSidePanelSettings.PanelLayout(2.5, 0.0, 1.0, 0.0, -20.0, 0.0));
        assertTrue(store.saveToDisk(tuned).success());
        assertEquals(tuned, store.snapshot());

        // 模拟外部入口（/muz reload 从磁盘重读、或别的配置写入方）直接改共享配置。
        config.set("chair-side-panels.right.lateral", 3.75);
        assertEquals(3.75, store.snapshot().right().lateral(),
            "快照必须是实时读取，不能是构造时拍下的缓存副本");
    }

    @Test
    void invalidSubmittedValueIsRejectedByTheSnapshotItself() throws IOException {
        // 非法值不该等到落盘才发现：写进内存后 from() 会立刻抛，
        // 本次保存因此失败并回滚，配置不会停留在含 NaN 的状态。
        MuzYamlConfig config = configFile("chair-side-panels: {}\n");
        ChairSidePanelLayoutStore store = store(config, Runnable::run,
            (target, template) -> target.saveWithComments(template), null);
        ChairSidePanelSettings valid = ChairSidePanelSettings.defaults();
        assertTrue(store.saveToDisk(valid).success());

        // 直接构造非法值在紧凑构造器就被拦住，不存在"带 NaN 的快照"这种状态。
        assertTrue(org.junit.jupiter.api.Assertions.assertThrows(IllegalArgumentException.class,
            () -> valid.withLayout(ChairSidePanelSettings.Side.LEFT,
                new ChairSidePanelSettings.PanelLayout(Double.NaN, 0, 0, 0, 0, 0))).getMessage()
            .contains("有限"));
        assertEquals(valid, store.snapshot(), "被拒的非法值不得影响当前布局");
    }
}
