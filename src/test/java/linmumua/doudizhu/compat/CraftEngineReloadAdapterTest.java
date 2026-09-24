package linmumua.doudizhu.compat;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.lang.reflect.Method;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;

/**
 * CraftEngine reload API 的运行期版本适配。
 *
 * <p>背景：MUZ 按 CraftEngine 0.0.67 编译，现场跑 26.x。26.x 把
 * {@code reloadPlugin(Executor, Executor, boolean)} 改成了
 * {@code reloadPlugin(Executor, Executor, boolean, boolean)}（javap 证据见
 * {@code build/tmp-ce-api}）。原先的静态调用在 26.x 上抛 {@link NoSuchMethodError}，
 * Debug Web 保存与启动期 HUD 资源恢复全部失败并回滚。这里用「假的 CraftEngine 替身类」
 * 真实驱动适配器，验证它能按形状选中正确重载、正确解析成功/失败，并在完全找不到时抛出
 * 带签名列表的异常，而不是静默当成功。
 */
class CraftEngineReloadAdapterTest {
    private static final Executor IO = Runnable::run;
    private static final Executor MAIN = Runnable::run;

    /** 0.0.67 的 ReloadResult：只有 success()，没有 issues()。 */
    public static final class OldReloadResult {
        private final boolean success;

        OldReloadResult(boolean success) {
            this.success = success;
        }

        public boolean success() {
            return success;
        }

        @Override
        public String toString() {
            return "OldReloadResult[success=" + success + "]";
        }
    }

    /** 26.x 的 ReloadResult：多一个 issues()，其余与 0.0.67 一致。 */
    public static final class NewReloadResult {
        private final boolean success;
        private final int issues;

        NewReloadResult(boolean success, int issues) {
            this.success = success;
            this.issues = issues;
        }

        public boolean success() {
            return success;
        }

        public int issues() {
            return issues;
        }

        @Override
        public String toString() {
            return "NewReloadResult[success=" + success + ", issues=" + issues + "]";
        }
    }

    /** 0.0.67 形状的替身：只有 3 参重载。 */
    public static final class OldEngine {
        final AtomicInteger calls = new AtomicInteger();
        final AtomicReference<Object[]> arguments = new AtomicReference<>();
        private final boolean success;

        OldEngine(boolean success) {
            this.success = success;
        }

        public CompletableFuture<OldReloadResult> reloadPlugin(Executor async, Executor sync, boolean force) {
            calls.incrementAndGet();
            arguments.set(new Object[] { async, sync, force });
            return CompletableFuture.completedFuture(new OldReloadResult(success));
        }
    }

    /** 26.8.2 形状的替身：只有 4 参重载。 */
    public static final class NewEngine {
        final AtomicInteger calls = new AtomicInteger();
        final AtomicReference<Object[]> arguments = new AtomicReference<>();
        private final boolean success;

        NewEngine(boolean success) {
            this.success = success;
        }

        public CompletableFuture<NewReloadResult> reloadPlugin(Executor async, Executor sync, boolean force,
                                                                boolean reloadHost) {
            calls.incrementAndGet();
            arguments.set(new Object[] { async, sync, force, reloadHost });
            return CompletableFuture.completedFuture(new NewReloadResult(success, 0));
        }
    }

    /**
     * 26.9.1 形状的替身：同时存在 4 参与 6 参，且 4 参像真实实现一样委托给 6 参。
     *
     * <p>这是「必须优先选 4 参」的关键夹具：SELECT_SHAPES 里 3→4→6 的偏好如果被写反成
     * 「先挑参数最多的」，就会直接命中 6 参、绕过 4 参的委托语义。
     */
    public static final class DualEngine {
        final AtomicInteger fourArgCalls = new AtomicInteger();
        final AtomicInteger sixArgCalls = new AtomicInteger();

        public CompletableFuture<NewReloadResult> reloadPlugin(Executor async, Executor sync, boolean force,
                                                                boolean reloadHost) {
            fourArgCalls.incrementAndGet();
            return reloadPlugin(async, sync, force, reloadHost, false, false);
        }

        public CompletableFuture<NewReloadResult> reloadPlugin(Executor async, Executor sync, boolean force,
                                                                boolean reloadHost, boolean a, boolean b) {
            sixArgCalls.incrementAndGet();
            return CompletableFuture.completedFuture(new NewReloadResult(true, 0));
        }
    }

    /** 完全没有 reloadPlugin 的替身。 */
    public static final class NoReloadEngine {
        public boolean isReloading() {
            return false;
        }
    }

    private static CraftEngineHudResourceBridge.ReloadInvoker resolve(Object engine) {
        return CraftEngineHudResourceBridge.ReloadInvoker.resolve(engine, engine.getClass());
    }

    /**
     * 0.0.67 现场：必须命中 3 参重载，并把 (ioExecutor, mainExecutor, false) 原样传下去。
     *
     * <p>失败条件：适配器强行按 4 参调用，或把第三个参数从 false 改成 true
     * ——后者会改变 CraftEngine 的重载语义。
     */
    @Test
    void 零版本三分之三参重载被选中且参数保持原样() {
        OldEngine engine = new OldEngine(true);
        CraftEngineHudResourceBridge.ReloadInvoker invoker = resolve(engine);
        assertEquals(3, invoker.arity(), "0.0.67 只有 3 参重载，必须命中 3 参");

        HudResourcePackSync.ReloadResult result = invoker.invoke(IO, MAIN).join();

        assertEquals(1, engine.calls.get(), "必须真的调用替身的 reloadPlugin");
        assertTrue(result.success(), "success() 返回 true 时必须判为成功");
        Object[] arguments = engine.arguments.get();
        assertNotNull(arguments, "必须记录到调用参数");
        assertEquals(IO, arguments[0], "第一个参数必须是 ioExecutor");
        assertEquals(MAIN, arguments[1], "第二个参数必须是 mainExecutor");
        assertEquals(Boolean.FALSE, arguments[2], "第三个参数必须与原实现一致传 false");
    }

    /**
     * 26.8.2 现场：必须命中 4 参重载，而不是抛 NoSuchMethodError。
     *
     * <p>这条正是用户遇到的故障：静态按 3 参编译，在只有 4 参的 26.8.2 上抛
     * {@code NoSuchMethodError}。用替身证明适配器不会走到那个分支。
     */
    @Test
    void 二六八二四分四参重载被选中且补位为false() {
        NewEngine engine = new NewEngine(true);
        CraftEngineHudResourceBridge.ReloadInvoker invoker = resolve(engine);
        assertEquals(4, invoker.arity(), "26.8.2 只有 4 参重载，必须命中 4 参");

        HudResourcePackSync.ReloadResult result = invoker.invoke(IO, MAIN).join();

        assertEquals(1, engine.calls.get(), "必须真的调用替身的 4 参 reloadPlugin");
        assertTrue(result.success(), "26.x 的 success() 同样能解析成功");
        Object[] arguments = engine.arguments.get();
        assertEquals(IO, arguments[0]);
        assertEquals(MAIN, arguments[1]);
        assertEquals(Boolean.FALSE, arguments[2], "第 3 位与老实现一致传 false");
        // 语义修正（非弱化）：26.x 第 4 位是「是否触发 CE 重载事件」，0.0.67 无条件触发，
        // 传 true 才与编译依赖版本等价；此前断言 false 编码了错误的等价性假设。
        assertEquals(Boolean.TRUE, arguments[3], "第 4 位（26.x 是否触发重载事件）必须补 true，与 0.0.67 行为等价");
    }

    /**
     * 26.9.1 现场：4 参与 6 参并存时必须优先 4 参。
     *
     * <p>失败条件：偏好顺序被改成「参数多的优先」。那样会直接命中 6 参、绕开 4 参的委托实现，
     * 而 6 参多出来的两个开关在 26.9.1 里控制是否重载 pack/host，语义与项目原本的调用不同。
     */
    @Test
    void 二六九一同时存在四参与六参时优先四分() {
        DualEngine engine = new DualEngine();
        CraftEngineHudResourceBridge.ReloadInvoker invoker = resolve(engine);
        assertEquals(4, invoker.arity(), "4 参与 6 参并存时必须优先 4 参");

        HudResourcePackSync.ReloadResult result = invoker.invoke(IO, MAIN).join();

        assertTrue(result.success());
        assertEquals(1, engine.fourArgCalls.get(), "必须先走 4 参入口");
        // 4 参替身会委托给 6 参，因此 6 参也应为 1；关键是 4 参没有被绕过。
        assertTrue(engine.sixArgCalls.get() >= 1, "4 参入口内部委托到 6 参，属正常");
    }

    /**
     * success=false 时必须判为失败，不能因为「Future 正常完成」就当成成功。
     *
     * <p>失败条件：适配器丢掉 ReloadResult.success() 只看 Future 完成状态。
     */
    @Test
    void 重载结果为fail时映射为失败() {
        OldEngine old = new OldEngine(false);
        assertFalse(resolve(old).invoke(IO, MAIN).join().success(),
            "0.0.67 形状的 success()==false 必须映射为失败");

        NewEngine modern = new NewEngine(false);
        HudResourcePackSync.ReloadResult modernResult = resolve(modern).invoke(IO, MAIN).join();
        assertFalse(modernResult.success(), "26.x 形状的 success()==false 同样必须映射为失败");
    }

    /**
     * 0.0.67 与 26.x 的 ReloadResult 都没有 success() 之外的共同接口，成功判定只能靠反射；
     * 完全没有 success() 的结果必须明确失败，不能默认成功。
     */
    @Test
    void 结果缺少success方法时明确失败而不是默认成功() {
        HudResourcePackSync.ReloadResult result =
            CraftEngineHudResourceBridge.ReloadInvoker.toResultForTest("不是一个 ReloadResult");
        assertFalse(result.success(), "无法判定结果时必须是失败，不能默认成功");
        assertTrue(result.detail().contains("success"),
            "失败详情要指出缺的是 success()，实际：" + result.detail());
    }

    /**
     * 完全没有可用重载时必须抛异常，并且异常信息里带上「实际发现的重载签名」。
     *
     * <p>失败条件：返回一个永远不完成的 Future、返回成功、或抛一条不含签名列表的通用异常
     * ——三者都会让现场排查无从下手，正是本次 NoSuchMethodError 难以定位的原因。
     */
    @Test
    void 找不到重载时抛出带实际签名列表的异常() {
        NoReloadEngine engine = new NoReloadEngine();
        UnsupportedOperationException thrown =
            assertThrows(UnsupportedOperationException.class, () -> resolve(engine));
        String message = thrown.getMessage();
        assertTrue(message.contains("reloadPlugin"), "异常必须点名方法名，实际：" + message);
        assertTrue(message.contains("实际发现"), "异常必须包含『实际发现』段，实际：" + message);
        assertTrue(message.contains("无"), "没有重载时要写明『无』，实际：" + message);
    }

    /**
     * 有 reloadPlugin 但参数形状全都不受支持时，异常必须逐个列出找到的签名，
     * 让维护者一眼看出运行期 CE 到底提供了什么。
     */
    @Test
    void 参数形状不支持时异常列出发现到的签名() {
        class OddEngine {
            public CompletableFuture<OldReloadResult> reloadPlugin(boolean onlyOne) {
                return CompletableFuture.completedFuture(new OldReloadResult(true));
            }

            public CompletableFuture<OldReloadResult> reloadPlugin() {
                return CompletableFuture.completedFuture(new OldReloadResult(true));
            }
        }
        UnsupportedOperationException thrown =
            assertThrows(UnsupportedOperationException.class, () -> resolve(new OddEngine()));
        String message = thrown.getMessage();
        assertTrue(message.contains("reloadPlugin()"),
            "必须列出无参重载的签名，实际：" + message);
        assertTrue(message.contains("reloadPlugin(boolean)"),
            "必须列出单参重载的签名，实际：" + message);
    }

    /**
     * reloadPlugin 抛出的异常（含反射包装的 InvocationTargetException）必须归一化成
     * Future 失败，而不是从 invoke 里逃逸——调用方 HudResourcePackSync 依赖 Future 语义。
     */
    @Test
    void 重载内部异常被归一化为失败Future() {
        class ExplodingEngine {
            public CompletableFuture<OldReloadResult> reloadPlugin(Executor async, Executor sync, boolean force) {
                throw new IllegalStateException("模拟 CraftEngine 重载内部异常");
            }
        }
        CompletableFuture<HudResourcePackSync.ReloadResult> future =
            resolve(new ExplodingEngine()).invoke(IO, MAIN);
        assertTrue(future.isCompletedExceptionally(), "内部异常必须转成失败的 Future");
    }

    /**
     * ReloadInvoker 必须暴露实际存在的全部签名，供诊断与后续适配使用。
     */
    @Test
    void 诊断信息包含全部实际重载签名() {
        DualEngine engine = new DualEngine();
        List<String> discovered = resolve(engine).discovered();
        assertEquals(2, discovered.size(), "替身有两个 reloadPlugin 重载，实际：" + discovered);
        assertTrue(discovered.stream().anyMatch(s -> s.contains("Executor, Executor, boolean, boolean")
                && !s.contains("boolean, boolean, boolean")),
            "必须记录 4 参签名，实际：" + discovered);
    }

    /**
     * 生产桥接必须以「access::reload」形态接入 HudResourcePackSync，并且不得再出现
     * 静态调用 engine.reloadPlugin(...)（那正是 NoSuchMethodError 的来源）。
     *
     * <p>这是源码契约断言：真实 CraftEngine 类在本仓库是 compileOnly，单测类路径里没有，
     * 无法用真实例驱动 resolveEngineAccess。
     */
    @Test
    void 生产桥接不再静态调用reloadPlugin() throws Exception {
        String source = Files.readString(
            Path.of("src/main/java/linmumua/doudizhu/compat/CraftEngineHudResourceBridge.java"));
        assertTrue(source.contains("access::reload"),
            "资源任务仍必须经 EngineAccess::reload 进入统一链");
        // 断言必须只看真实代码：这份文件在 Javadoc 里刻意保留了「旧写法
        // engine.reloadPlugin(...) 会抛 NoSuchMethodError」的说明，直接扫全文会命中注释而误报。
        String code = stripCommentLines(source);
        assertFalse(code.contains("engine.reloadPlugin("),
            "不得再保留静态 engine.reloadPlugin(...) 调用，否则 26.x 上仍是 NoSuchMethodError");
        assertFalse(code.contains("import net.momirealms.craftengine.core.plugin.CraftEngine;"),
            "不得再 import CraftEngine 主类，否则静态签名会重新进入运行期");
    }

    /** 去掉整行注释与块注释内容，只留可执行的 Java 代码行用于契约断言。 */
    private static String stripCommentLines(String source) {
        StringBuilder code = new StringBuilder();
        boolean inBlock = false;
        for (String line : source.split("\n", -1)) {
            String trimmed = line.strip();
            if (inBlock) {
                int end = trimmed.indexOf("*/");
                if (end < 0) {
                    continue;
                }
                inBlock = false;
                trimmed = trimmed.substring(end + 2).strip();
            }
            if (trimmed.startsWith("//")) {
                continue;
            }
            if (trimmed.startsWith("/*")) {
                int end = trimmed.indexOf("*/");
                if (end < 0) {
                    inBlock = true;
                    continue;
                }
                trimmed = trimmed.substring(end + 2).strip();
            }
            code.append(trimmed).append('\n');
        }
        return code.toString();
    }

    /**
     * 适配器必须基于反射可见的公开重载做选择，而不是依赖私有成员或类内部结构。
     *
     * <p>用反射独立枚举替身的公开方法做交叉验证：如果适配器改用
     * {@code getDeclaredMethods()} 之外的非常规查找（例如硬编码类名），这里的期望值就会与它
     * 看到的方法集合不一致，从而暴露「测试夹具伪装成通过」。
     */
    @Test
    void 适配器按反射可见的重载选择且不依赖私有成员() {
        NewEngine engine = new NewEngine(true);
        long visibleReloads = 0;
        for (Method method : engine.getClass().getMethods()) {
            if ("reloadPlugin".equals(method.getName())) {
                visibleReloads++;
            }
        }
        assertEquals(1, visibleReloads, "替身必须只暴露一个公开 reloadPlugin，与实际可见方法一致");
        CraftEngineHudResourceBridge.ReloadInvoker invoker = resolve(engine);
        assertEquals(4, invoker.arity(), "必须选到那个唯一的公开 4 参重载");
        assertTrue(invoker.discovered().stream().allMatch(s -> s.startsWith("reloadPlugin(")),
            "诊断列表里的每一项都必须是 reloadPlugin 重载：" + invoker.discovered());
    }

    /** 带 PackManager 的替身：isReloading / packManager / generateResourcePack / resourcePackPath 齐备。 */
    public static final class ManagerEngine {
        private final boolean reloading;
        final AtomicInteger generateCalls = new AtomicInteger();

        ManagerEngine(boolean reloading) {
            this.reloading = reloading;
        }

        public boolean isReloading() {
            return reloading;
        }

        public FakePackManager packManager() {
            return new FakePackManager(generateCalls);
        }
    }

    /** 替身的 PackManager：generateResourcePack 抛受检异常路径与 resourcePackPath 都要能走到。 */
    public static final class FakePackManager {
        private final AtomicInteger generateCalls;
        private final Path path;

        FakePackManager(AtomicInteger generateCalls) {
            this(generateCalls, Path.of("build", "fake-resource-pack.zip"));
        }

        FakePackManager(AtomicInteger generateCalls, Path path) {
            this.generateCalls = generateCalls;
            this.path = path;
        }

        public void generateResourcePack() throws Exception {
            generateCalls.incrementAndGet();
        }

        public Path resourcePackPath() {
            return path;
        }
    }

    /**
     * isReloading / packManager / generateResourcePack / resourcePackPath 必须能通过反射命中。
     *
     * <p>这些方法在三代 CraftEngine 里签名一致（javap 已核对），但既然主入口改成了反射，
     * 就必须一起锁住：否则某天 CE 改动其中一个名字，重载能过、生成却静默失败。
     */
    @Test
    void 非重载API通过反射命中且路径正确返回() throws Exception {
        ManagerEngine engine = new ManagerEngine(true);
        CraftEngineHudResourceBridge.EngineAccess access =
            CraftEngineHudResourceBridge.DirectEngineAccess.of(engine);

        assertTrue(access.isReloading(), "isReloading() 返回 true 时必须反射读到 true");

        access.generateResourcePack();
        assertEquals(1, engine.generateCalls.get(), "generateResourcePack 必须真的转发到 PackManager");

        assertNotNull(access.generatedPackPath(), "resourcePackPath() 必须反射读到路径");
        assertEquals(Path.of("build", "fake-resource-pack.zip"), access.generatedPackPath(),
            "返回的必须是 PackManager 给的同一个 Path");
    }

    /**
     * PackManager 缺失时生成必须明确失败，不能因为反射查不到方法就静默跳过。
     *
     * <p>失败条件：生成的异常被吞掉。那会让上层拿着旧 ZIP 去校验，看上去「成功」。
     */
    @Test
    void PackManager缺失时生成资源包明确失败() {
        class NoPackManagerEngine {
            public boolean isReloading() {
                return false;
            }
        }
        CraftEngineHudResourceBridge.EngineAccess access =
            CraftEngineHudResourceBridge.DirectEngineAccess.of(new NoPackManagerEngine());

        UnsupportedOperationException thrown = assertThrows(UnsupportedOperationException.class,
            access::generateResourcePack, "没有 PackManager 时必须抛异常，不能静默当成功");
        assertTrue(thrown.getMessage().contains("PackManager"),
            "错误必须点名缺失的是 PackManager，实际：" + thrown.getMessage());
        assertTrue(thrown.getMessage().contains("26.9.1"),
            "错误必须写明 26.9.1 需另行适配，否则维护者会以为是配置问题，实际：" + thrown.getMessage());

        // 【本轮要求变更，不是弱化断言】旧实现对「拿不到路径」返回 null，由上层统一报「路径为空」，
        // 但那样无法区分「CE 版本形状不同」与「配置写错」。现在按需求改成抛带 CE 版本号与缺失
        // 方法名的明确错误，因此这里从 assertNull 改为 assertThrows，且额外要求信息里能看出
        // 「只支持 0.0.67 / 26.8.x」这一事实。
        UnsupportedOperationException pathThrown = assertThrows(UnsupportedOperationException.class,
            access::generatedPackPath,
            "PackManager 形状不支持时读路径也必须明确失败，不能返回 null 让上层误判成『路径为空』");
        assertTrue(pathThrown.getMessage().contains("0.0.67") || pathThrown.getMessage().contains("26.8.x"),
            "错误必须写明当前支持的 PackManager 形状，实际：" + pathThrown.getMessage());
    }

    /**
     * isReloading() 反射不到时必须保守返回 false，而不是抛异常打断整个 preflight。
     *
     * <p>理由：isReloading 只是「能不能现在保存」的前置判断，读不到不该阻止后续的错误报告
     * ——真正的问题会在 reload 阶段以明确异常暴露。
     */
    @Test
    void isReloading不可用时保守返回false() {
        class BareEngine {
        }
        CraftEngineHudResourceBridge.EngineAccess access =
            CraftEngineHudResourceBridge.DirectEngineAccess.of(new BareEngine());
        assertFalse(access.isReloading(), "反射不到 isReloading 时必须保守返回 false");
    }
}
