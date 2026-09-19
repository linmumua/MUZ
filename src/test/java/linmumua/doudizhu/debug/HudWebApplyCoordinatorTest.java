package linmumua.doudizhu.debug;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import javax.imageio.ImageIO;
import linmumua.doudizhu.assets.HudOverlayLayout;
import linmumua.doudizhu.assets.HudResourceRequest;
import linmumua.doudizhu.compat.HudResourcePackBridge;
import linmumua.doudizhu.DoudizhuPlugin;
import linmumua.doudizhu.config.MuzYamlConfig;
import org.bukkit.Bukkit;
import org.bukkit.Server;
import org.bukkit.plugin.Plugin;
import org.bukkit.plugin.PluginManager;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import sun.misc.Unsafe;

/**
 * 通过真实协调器 monitor 路径验证 raw 完成后的执行器收尾。
 *
 * <p>使用 Unsafe 只绕过 Bukkit 插件构造器；本测试不启动服务端、不触碰 CraftEngine，验证的
 * 仍是生产 coordinator → taskGate → raw Future 连接，而不是复制一份闸门逻辑。
 */
class HudWebApplyCoordinatorTest {
    @AfterEach
    void clearLeases() {
        HudWebApplyLease.clearForTests();
    }

    @Test
    void 无自己任务但共享租约被其他实例占用时仍关闭自己的执行器() throws Exception {
        DoudizhuPlugin plugin = testPlugin();
        HudWebApplyLease lease = HudWebApplyLease.forKey("coordinator-shared-close");
        ExecutorService firstExecutor = Executors.newSingleThreadExecutor();
        ExecutorService secondExecutor = Executors.newSingleThreadExecutor();
        ScheduledExecutorService firstTimeout = Executors.newSingleThreadScheduledExecutor();
        ScheduledExecutorService secondTimeout = Executors.newSingleThreadScheduledExecutor();
        HudWebApplyCoordinator first = new HudWebApplyCoordinator(
            plugin, new DebugHudConfigController(plugin), null, firstExecutor, firstTimeout, lease, Runnable::run);
        HudWebApplyCoordinator second = new HudWebApplyCoordinator(
            plugin, new DebugHudConfigController(plugin), null, secondExecutor, secondTimeout, lease, Runnable::run);
        try {
            assertTrue(taskGate(first).tryAcquire() != null, "第一个实例应先占用共享租约");
            assertTrue(lease.isBusy());
            second.close();
            assertTrue(secondExecutor.isShutdown(), "没有自己任务时第二实例应立即关闭自己的执行器");
            assertTrue(taskGate(first).hasLocalTask(), "共享租约所属的第一个实例不能被第二实例关闭影响");
        } finally {
            first.close();
            second.close();
            firstExecutor.shutdownNow();
            secondExecutor.shutdownNow();
            firstTimeout.shutdownNow();
            secondTimeout.shutdownNow();
        }
    }

    @Test
    void fakeBridge覆盖37_83_157并支持两次保存() throws Exception {
        Fixture fixture = fixture(new RecordingBridge());
        RecordingBridge bridge = fixture.bridge();
        try {
            HudWebApplyCoordinator.ApplyResult first = fixture.coordinator().submitSave(
                new DebugHudConfigController.Patch(java.util.Map.of(
                    "trick-hud.offset-down", 37,
                    "trick-hud.avatar-offset-down", 83,
                    "trick-hud.counter.offset-down", 157
                ))).get(5, TimeUnit.SECONDS);
            assertTrue(first.ok(), "第一轮三层保存必须成功：" + first.messages());
            assertEquals(new HudResourceRequest(37, 83, 157, 0, 100), bridge.requests().get(0));
            assertTrue(fixture.plugin().getHudOverlayRuntimeState().matchesTrick(37, 83, 157));

        } finally {
            fixture.close();
        }
    }

    @Test
    void CE故障会回滚配置覆盖层并清除ready() throws Exception {
        RecordingBridge bridge = new RecordingBridge();
        bridge.failure = new IllegalStateException("fake CE failure");
        Fixture fixture = fixture(bridge);
        try {
            Path config = fixture.configPath();
            byte[] before = Files.exists(config) ? Files.readAllBytes(config) : new byte[0];
            HudWebApplyCoordinator.ApplyResult result = fixture.coordinator().submitSave(
                new DebugHudConfigController.Patch(java.util.Map.of(
                    "trick-hud.offset-down", 37,
                    "trick-hud.avatar-offset-down", 83,
                    "trick-hud.counter.offset-down", 157
                ))).get(5, TimeUnit.SECONDS);
            assertFalse(result.ok(), "CE 失败不得报告保存成功");
            assertTrue(result.messages().stream().anyMatch(message -> message.contains("已回滚")),
                "失败结果必须明确说明已回滚：" + result.messages());
            org.junit.jupiter.api.Assertions.assertArrayEquals(before,
                Files.exists(config) ? Files.readAllBytes(config) : new byte[0],
                "CE 失败后 config.yml 必须恢复原字节");
            assertNull(fixture.plugin().getHudOverlayRuntimeState().verifiedRequest(),
                "没有旧 ready 快照时，CE 失败必须保持未 ready");
            assertTrue(bridge.requests().size() == 1, "失败流程仍必须传递完整 request 给 bridge");
            assertTrue(ownedOverlayFiles(fixture.overlayRoot()).isEmpty(),
                "CE 失败回滚后不得残留本次三层 overlay 文件");
        } finally {
            fixture.close();
        }
    }

    @Test
    void 已有ready时后续资源失败也必须清除旧ready() throws Exception {
        RecordingBridge bridge = new RecordingBridge();
        Fixture fixture = fixture(bridge);
        try {
            HudWebApplyCoordinator.ApplyResult first = fixture.coordinator().submitSave(
                new DebugHudConfigController.Patch(java.util.Map.of("trick-hud.offset-down", 37)))
                .get(5, TimeUnit.SECONDS);
            assertTrue(first.ok(), "前一轮保存必须成功，才能验证失败时不能恢复旧 ready");
            assertTrue(fixture.plugin().getHudOverlayRuntimeState().verifiedRequest() != null,
                "前一轮成功后应存在 ready 快照");

            bridge.failure = new IllegalStateException("second fake CE failure");
            HudWebApplyCoordinator.ApplyResult second = fixture.coordinator().submitSave(
                new DebugHudConfigController.Patch(java.util.Map.of("trick-hud.offset-down", 83)))
                .get(5, TimeUnit.SECONDS);
            assertFalse(second.ok(), "后续 CE 失败不得报告成功");
            assertNull(fixture.plugin().getHudOverlayRuntimeState().verifiedRequest(),
                "后续资源失败后不能恢复旧 request 的 ready 状态");
        } finally {
            fixture.close();
        }
    }

    @Test
    void 失败回滚检测到并发配置修改时保留非Web文件() throws Exception {
        RecordingBridge bridge = new RecordingBridge();
        Fixture fixture = fixture(bridge);
        try {
            HudWebApplyCoordinator.ApplyResult first = fixture.coordinator().submitSave(
                new DebugHudConfigController.Patch(java.util.Map.of("trick-hud.offset-down", 37)))
                .get(5, TimeUnit.SECONDS);
            assertTrue(first.ok(), "前一轮保存必须成功");

            bridge.pending = new CompletableFuture<>();
            CompletableFuture<HudWebApplyCoordinator.ApplyResult> secondFuture = fixture.coordinator().submitSave(
                new DebugHudConfigController.Patch(java.util.Map.of("trick-hud.offset-down", 83)));
            assertTrue(waitUntil(() -> bridge.requests().size() >= 2), "第二轮必须进入 bridge");
            Files.writeString(fixture.configPath(), "# concurrent non-web change\n",
                java.nio.charset.StandardCharsets.UTF_8, java.nio.file.StandardOpenOption.APPEND);
            bridge.pending.completeExceptionally(new IllegalStateException("concurrent fake CE failure"));

            HudWebApplyCoordinator.ApplyResult second = secondFuture.get(5, TimeUnit.SECONDS);
            assertFalse(second.ok(), "并发配置修改期间的资源失败不得报告成功");
            String config = Files.readString(fixture.configPath());
            assertTrue(config.contains("# concurrent non-web change"),
                "检测到并发配置修改后不得用旧整文件覆盖其它入口的修改");
            assertNull(fixture.plugin().getHudOverlayRuntimeState().verifiedRequest(),
                "冲突失败后仍必须清除 ready");
        } finally {
            fixture.close();
        }
    }

    @Test
    void submitReload的raw未完成时不释放租约且关闭后迟到不能应用() throws Exception {
        RecordingBridge bridge = new RecordingBridge();
        bridge.pending = new CompletableFuture<>();
        Fixture fixture = fixture(bridge);
        try {
            CompletableFuture<HudWebApplyCoordinator.ApplyResult> exposed = fixture.coordinator().submitReload();
            assertTrue(bridge.called.await(5, TimeUnit.SECONDS), "submitReload 必须启动 bridge raw Future");
            assertFalse(exposed.isDone(), "raw 未完成时对外结果不能提前成功");
            assertFalse(bridge.pending.isCancelled(), "协调器不能取消 bridge raw Future");
            assertTrue(fixture.lease().isBusy(), "raw 未完成时共享租约必须保持占用");

            fixture.coordinator().close();
            HudWebApplyCoordinator.ApplyResult closed = exposed.get(5, TimeUnit.SECONDS);
            assertFalse(closed.ok(), "关闭后对外结果必须失效");
            assertFalse(bridge.pending.isCancelled(), "关闭不能取消迟到 raw Future");
            assertNull(fixture.plugin().getHudOverlayRuntimeState().verifiedRequest(),
                "迟到结果应用前不得发布 ready");

            bridge.pending.complete(null);
            assertTrue(waitUntil(() -> !fixture.lease().isBusy()), "raw 完成后才释放租约");
            assertNull(fixture.plugin().getHudOverlayRuntimeState().verifiedRequest(),
                "迟到 raw 完成后仍不能应用运行态");
        } finally {
            fixture.close();
        }
    }

    @Test
    void raw超时后迟到结果不能应用且租约直到raw完成才释放() throws Exception {
        RecordingBridge bridge = new RecordingBridge();
        bridge.pending = new CompletableFuture<>();
        Fixture fixture = fixture(bridge, 1L);
        try {
            CompletableFuture<HudWebApplyCoordinator.ApplyResult> exposed = fixture.coordinator().submitReload();
            assertTrue(bridge.called.await(5, TimeUnit.SECONDS), "超时测试必须启动 bridge raw Future");
            HudWebApplyCoordinator.ApplyResult timedOut = exposed.get(5, TimeUnit.SECONDS);
            assertFalse(timedOut.ok(), "raw 超时不得报告成功");
            assertTrue(timedOut.messages().stream().anyMatch(message -> message.contains("失效")),
                "超时结果必须标记任务失效：" + timedOut.messages());
            assertFalse(bridge.pending.isCancelled(), "超时不能取消 bridge raw Future");
            assertTrue(fixture.lease().isBusy(), "raw 未完成时超时任务仍应占用租约");
            assertNull(fixture.plugin().getHudOverlayRuntimeState().verifiedRequest(),
                "超时后不得发布 ready");

            bridge.pending.complete(null);
            assertTrue(waitUntil(() -> !fixture.lease().isBusy()), "raw 完成后才释放租约");
            assertNull(fixture.plugin().getHudOverlayRuntimeState().verifiedRequest(),
                "迟到 raw 完成后仍不得应用运行态");
        } finally {
            fixture.close();
        }
    }

    @Test
    void 关闭后raw完成才关闭本协调器执行器() throws Exception {
        DoudizhuPlugin plugin = testPlugin();
        DebugHudConfigController controller = new DebugHudConfigController(plugin);
        ExecutorService executor = Executors.newSingleThreadExecutor();
        ScheduledExecutorService timeoutExecutor = Executors.newSingleThreadScheduledExecutor();
        HudWebApplyLease lease = HudWebApplyLease.forKey("coordinator-shutdown");
        HudWebApplyCoordinator coordinator = new HudWebApplyCoordinator(
            plugin, controller, null, executor, timeoutExecutor, lease, Runnable::run);
        try {
            HudWebApplyTaskGate gate = taskGate(coordinator);
            HudWebApplyTaskGate.Task<HudWebApplyCoordinator.ApplyResult> task = gate.tryAcquire();
            CompletableFuture<HudWebApplyCoordinator.ApplyResult> raw = new CompletableFuture<>();
            monitor(coordinator, task, raw);

            coordinator.close();
            assertFalse(executor.isShutdown(), "raw 未完成时不能关闭本协调器执行器");
            assertTrue(lease.isBusy(), "raw 未完成时共享租约必须保持占用");

            raw.complete(new HudWebApplyCoordinator.ApplyResult(
                false, controller.snapshot(), List.of(), List.of("late")));
            assertTrue(executor.awaitTermination(2, TimeUnit.SECONDS),
                "raw 释放租约后 coordinator 必须关闭自己的执行器");
            assertTrue(timeoutExecutor.isShutdown(), "关闭后的超时执行器也必须停止");
            assertFalse(lease.isBusy(), "raw 完成后共享租约必须释放");
        } finally {
            coordinator.close();
            executor.shutdownNow();
            timeoutExecutor.shutdownNow();
        }
    }

    private static Fixture fixture(RecordingBridge bridge) throws Exception {
        return fixture(bridge, HudWebApplyCoordinator.RAW_RESULT_TIMEOUT_SECONDS);
    }

    private static Fixture fixture(RecordingBridge bridge, long timeoutSeconds) throws Exception {
        Path root = Files.createTempDirectory("muz-hud-coordinator-pipeline");
        Path data = root.resolve("data");
        Path craftEngine = root.resolve("craftengine");
        Files.createDirectories(data);
        Files.createDirectories(craftEngine);
        Path configPath = data.resolve("config.yml");
        DoudizhuPlugin plugin = testPlugin();
        Unsafe unsafe = unsafe();
        setObject(unsafe, plugin, "dataFolder", data.toFile());
        setObject(unsafe, plugin, "config", MuzYamlConfig.empty(configPath));
        setObject(unsafe, plugin, "hudOverlayRuntimeState", new linmumua.doudizhu.game.HudOverlayRuntimeState());
        writeBaseTextures(craftEngine.resolve("resources").resolve("muz"));
        Server previous = installCraftEngineServer(craftEngine);
        // Unsafe 绕过 JavaPlugin 构造，静态 Bukkit.server 不会自动注入实例的 server 字段。
        setObject(unsafe, plugin, "server", Bukkit.getServer());
        ExecutorService executor = Executors.newSingleThreadExecutor();
        ScheduledExecutorService timeout = Executors.newSingleThreadScheduledExecutor();
        HudWebApplyLease lease = HudWebApplyLease.forKey(root.toString());
        HudWebApplyCoordinator coordinator = new HudWebApplyCoordinator(
            plugin, new DebugHudConfigController(plugin), bridge, executor, timeout, lease,
            Runnable::run, timeoutSeconds);
        return new Fixture(root, data, craftEngine.resolve("resources").resolve("muz"), configPath,
            plugin, coordinator, bridge, lease, executor, timeout, previous);
    }

    private static Server installCraftEngineServer(Path craftEngineRoot) throws Exception {
        Field field = Bukkit.class.getDeclaredField("server");
        field.setAccessible(true);
        Server previous = (Server) field.get(null);
        Plugin craftEngine = (Plugin) Proxy.newProxyInstance(
            Plugin.class.getClassLoader(), new Class<?>[]{Plugin.class}, (proxy, method, args) -> {
                return switch (method.getName()) {
                    case "getName" -> "CraftEngine";
                    case "isEnabled" -> true;
                    case "getDataFolder" -> craftEngineRoot.toFile();
                    default -> defaultValue(method.getReturnType());
                };
            });
        PluginManager manager = (PluginManager) Proxy.newProxyInstance(
            PluginManager.class.getClassLoader(), new Class<?>[]{PluginManager.class}, (proxy, method, args) ->
                method.getName().equals("getPlugin") && args != null && args.length == 1
                    && "CraftEngine".equalsIgnoreCase(String.valueOf(args[0]))
                    ? craftEngine : defaultValue(method.getReturnType()));
        Server server = (Server) Proxy.newProxyInstance(
            Server.class.getClassLoader(), new Class<?>[]{Server.class}, (proxy, method, args) ->
                method.getName().equals("getPluginManager") ? manager : defaultValue(method.getReturnType()));
        field.set(null, server);
        return previous;
    }

    private static void writeBaseTextures(Path overlayRoot) throws Exception {
        HudResourceRequest request = new HudResourceRequest(50, 122, 122, 0, 100);
        for (HudOverlayLayout.Glyph glyph : HudOverlayLayout.glyphs(request)) {
            String relative = glyph.baseTexture().substring("muz:".length());
            Path target = overlayRoot.resolve("resourcepack/assets/muz/textures").resolve(relative);
            Files.createDirectories(target.getParent());
            BufferedImage image = new BufferedImage(glyph.originalWidth(), glyph.originalHeight(),
                BufferedImage.TYPE_INT_ARGB);
            ByteArrayOutputStream bytes = new ByteArrayOutputStream();
            assertTrue(ImageIO.write(image, "png", bytes), "测试夹具必须能写出 PNG：" + glyph.id());
            Files.write(target, bytes.toByteArray());
        }
    }

    private static List<Path> ownedOverlayFiles(Path overlayRoot) throws Exception {
        List<Path> owned = new ArrayList<>();
        for (String relative : List.of("pack.yml", "configuration/images/trick_hud_continuous.yml")) {
            Path path = overlayRoot.resolve(relative);
            if (Files.exists(path)) {
                owned.add(path);
            }
        }
        Path dynamic = overlayRoot.resolve("resourcepack/assets/muz/textures/font/continuous");
        if (Files.isDirectory(dynamic)) {
            try (var stream = Files.walk(dynamic)) {
                stream.filter(Files::isRegularFile).forEach(owned::add);
            }
        }
        return owned;
    }

    private static boolean waitUntil(java.util.function.BooleanSupplier condition) throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(2);
        while (System.nanoTime() < deadline) {
            if (condition.getAsBoolean()) {
                return true;
            }
            Thread.sleep(20L);
        }
        return condition.getAsBoolean();
    }

    private static Object defaultValue(Class<?> type) {
        if (!type.isPrimitive()) {
            return null;
        }
        if (type == boolean.class) return false;
        if (type == byte.class) return (byte) 0;
        if (type == short.class) return (short) 0;
        if (type == int.class) return 0;
        if (type == long.class) return 0L;
        if (type == float.class) return 0F;
        if (type == double.class) return 0D;
        if (type == char.class) return '\0';
        return null;
    }

    private record Fixture(Path root, Path dataRoot, Path overlayRoot, Path configPath,
                           DoudizhuPlugin plugin, HudWebApplyCoordinator coordinator,
                           RecordingBridge bridge, HudWebApplyLease lease,
                           ExecutorService executor, ScheduledExecutorService timeout,
                           Server previousServer) implements AutoCloseable {
        @Override
        public void close() {
            coordinator.close();
            executor.shutdownNow();
            timeout.shutdownNow();
            try {
                Field field = Bukkit.class.getDeclaredField("server");
                field.setAccessible(true);
                field.set(null, previousServer);
            } catch (Exception ignored) {
                // 测试收尾不能覆盖主断言；每个测试仍使用唯一租约避免污染后续用例。
            }
        }
    }

    private static final class RecordingBridge implements HudResourcePackBridge {
        private final List<HudResourceRequest> requests = java.util.Collections.synchronizedList(new ArrayList<>());
        private final CountDownLatch called = new CountDownLatch(1);
        private volatile CompletableFuture<Void> pending;
        private volatile Throwable failure;

        @Override
        public String preflightFailureOnMainThread() {
            return null;
        }

        @Override
        public CompletableFuture<Void> reloadGenerateAndVerify(HudResourceRequest request,
                                                                java.util.concurrent.Executor ioExecutor,
                                                                java.util.concurrent.Executor mainExecutor) {
            requests.add(request);
            called.countDown();
            if (failure != null) {
                CompletableFuture<Void> failed = new CompletableFuture<>();
                failed.completeExceptionally(failure);
                return failed;
            }
            if (pending != null) {
                return pending;
            }
            return CompletableFuture.completedFuture(null);
        }

        private List<HudResourceRequest> requests() {
            synchronized (requests) {
                return List.copyOf(requests);
            }
        }
    }

    private static void monitor(HudWebApplyCoordinator coordinator,
                                 HudWebApplyTaskGate.Task<HudWebApplyCoordinator.ApplyResult> task,
                                 CompletableFuture<HudWebApplyCoordinator.ApplyResult> raw) throws Exception {
        Method method = HudWebApplyCoordinator.class.getDeclaredMethod(
            "monitor", HudWebApplyTaskGate.Task.class, CompletableFuture.class);
        method.setAccessible(true);
        // 调用协调器自身的二参包装，保留其 raw 完成后关闭执行器的真实回调。
        method.invoke(coordinator, task, raw);
    }

    private static HudWebApplyTaskGate taskGate(HudWebApplyCoordinator coordinator) throws Exception {
        Field field = HudWebApplyCoordinator.class.getDeclaredField("taskGate");
        field.setAccessible(true);
        return (HudWebApplyTaskGate) field.get(coordinator);
    }

    private static DoudizhuPlugin testPlugin() throws Exception {
        Unsafe unsafe = unsafe();
        DoudizhuPlugin plugin = (DoudizhuPlugin) unsafe.allocateInstance(DoudizhuPlugin.class);
        setObject(unsafe, plugin, "hudWebConfigLock", new Object());
        setObject(unsafe, plugin, "logger", java.util.logging.Logger.getLogger("muz-coordinator-test"));
        setObject(unsafe, plugin, "classLoader", DoudizhuPlugin.class.getClassLoader());
        Path configFile = Files.createTempFile("muz-hud-coordinator-test", ".yml");
        setObject(unsafe, plugin, "config", MuzYamlConfig.empty(configFile));
        return plugin;
    }

    private static void setObject(Unsafe unsafe, Object target, String fieldName, Object value) throws Exception {
        Class<?> type = target.getClass();
        Field field = null;
        while (type != null && field == null) {
            try {
                field = type.getDeclaredField(fieldName);
            } catch (NoSuchFieldException ignored) {
                type = type.getSuperclass();
            }
        }
        if (field == null) {
            throw new NoSuchFieldException(fieldName);
        }
        unsafe.putObject(target, unsafe.objectFieldOffset(field), value);
    }

    private static Unsafe unsafe() throws Exception {
        Field field = Unsafe.class.getDeclaredField("theUnsafe");
        field.setAccessible(true);
        return (Unsafe) field.get(null);
    }
}
