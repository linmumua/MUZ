package linmumua.doudizhu.compat;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * 资源同步流程的纯 Java 契约测试。
 *
 * <p>这些测试直接驱动生产 {@link HudResourcePackSync#run}，而不是只测试无关的辅助方法：
 * reload Future 成功后才生成，生成与实际 ZIP 校验在异步阶段完成，生成路径和上传路径必须
 * 内容一致且分别验证；失败时不伪报成功，也不取消 CraftEngine 原始 Future。
 */
class HudResourcePackSyncTest {
    @TempDir
    Path temporaryDirectory;

    @Test
    void reload成功后才生成并校验实际上传路径() throws Exception {
        Path generated = Files.writeString(temporaryDirectory.resolve("generated.zip"), "zip");
        Path uploaded = Files.writeString(temporaryDirectory.resolve("uploaded.zip"), "zip");
        CompletableFuture<HudResourcePackSync.ReloadResult> reload = new CompletableFuture<>();
        List<String> events = new ArrayList<>();
        List<Path> verified = new ArrayList<>();

        CompletableFuture<Void> result = HudResourcePackSync.run(
            (io, main) -> {
                events.add("reload-start");
                return reload;
            },
            () -> events.add("generate"),
            () -> generated,
            () -> uploaded,
            (path, offsetY) -> {
                events.add("verify:" + path.getFileName());
                verified.add(path);
            },
            50,
            Runnable::run,
            Runnable::run
        );

        assertTrue(events.contains("reload-start"));
        assertFalse(events.contains("generate"), "reload Future 完成前不得生成资源包");
        reload.complete(new HudResourcePackSync.ReloadResult(true, "success"));
        result.get(2, TimeUnit.SECONDS);

        assertEquals(List.of("reload-start", "generate", "verify:generated.zip", "verify:uploaded.zip"), events);
        assertEquals(List.of(generated, uploaded), verified);
    }

    @Test
    void reload失败不会生成或校验且原始Future不被取消() throws Exception {
        CompletableFuture<HudResourcePackSync.ReloadResult> reload = new CompletableFuture<>();
        CompletableFuture<Void> result = HudResourcePackSync.run(
            (io, main) -> reload,
            () -> { throw new AssertionError("不应生成"); },
            () -> temporaryDirectory.resolve("missing-generated.zip"),
            () -> temporaryDirectory.resolve("missing-uploaded.zip"),
            (path, offsetY) -> { throw new AssertionError("不应校验"); },
            0,
            Runnable::run,
            Runnable::run
        );

        reload.complete(new HudResourcePackSync.ReloadResult(false, "reload failed"));
        Throwable failure = result.handle((ignored, error) -> error).get(2, TimeUnit.SECONDS);
        assertNotNull(failure);
        assertInstanceOf(IOException.class, unwrap(failure));
        assertFalse(reload.isCancelled(), "同步流程不得取消 CraftEngine 原始 reload Future");
    }

    @Test
    void 生成失败不会校验并向外暴露失败() throws Exception {
        Path generated = Files.writeString(temporaryDirectory.resolve("generated.zip"), "zip");
        CompletableFuture<HudResourcePackSync.ReloadResult> reload =
            CompletableFuture.completedFuture(new HudResourcePackSync.ReloadResult(true, "success"));
        int[] verified = {0};

        CompletableFuture<Void> result = HudResourcePackSync.run(
            (io, main) -> reload,
            () -> { throw new IOException("disk full"); },
            () -> generated,
            () -> generated,
            (path, offsetY) -> verified[0]++,
            0,
            Runnable::run,
            Runnable::run
        );

        Throwable failure = result.handle((ignored, error) -> error).get(2, TimeUnit.SECONDS);
        assertNotNull(failure);
        assertTrue(unwrap(failure).getMessage().contains("资源包生成失败"));
        assertEquals(0, verified[0]);
    }

    @Test
    void 生成路径和上传路径字节不一致时拒绝成功() throws Exception {
        Path generated = Files.writeString(temporaryDirectory.resolve("generated.zip"), "generated");
        Path uploaded = Files.writeString(temporaryDirectory.resolve("uploaded.zip"), "uploaded");
        CompletableFuture<Void> result = HudResourcePackSync.run(
            (io, main) -> CompletableFuture.completedFuture(new HudResourcePackSync.ReloadResult(true, "success")),
            () -> { },
            () -> generated,
            () -> uploaded,
            (path, offsetY) -> { },
            0,
            Runnable::run,
            Runnable::run
        );

        Throwable failure = result.handle((ignored, error) -> error).get(2, TimeUnit.SECONDS);
        assertNotNull(failure);
        assertTrue(unwrap(failure).getMessage().contains("内容不一致"));
    }

    private static Throwable unwrap(Throwable failure) {
        Throwable current = failure;
        while ((current instanceof java.util.concurrent.CompletionException
            || current instanceof java.util.concurrent.ExecutionException)
            && current.getCause() != null) {
            current = current.getCause();
        }
        return current;
    }
}
