package linmumua.doudizhu.compat;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.Executor;

/**
 * HUD 资源包重载、生成、内容校验的纯 Java 流程。
 *
 * <p>这里不引用 Bukkit 或 CraftEngine 类型，便于在没有可选依赖运行时的测试中验证最重要的
 * 时序：真实 reload Future 成功后才生成；生成方法正常返回后仍必须检查实际 ZIP；生成路径
 * 与上传路径不同则必须逐字节一致并分别校验。该 helper 不会取消传入的任何 Future。
 */
final class HudResourcePackSync {
    private HudResourcePackSync() {
    }

    static CompletableFuture<Void> run(
        ReloadOperation reload,
        GenerateOperation generate,
        PathSupplier generatedPack,
        PathSupplier uploadPack,
        Verifier verifier,
        int offsetY,
        Executor ioExecutor,
        Executor mainExecutor
    ) {
        return run(reload, generate, generatedPack, uploadPack, verifier, offsetY, 100,
            ioExecutor, mainExecutor);
    }

    static CompletableFuture<Void> run(
        ReloadOperation reload,
        GenerateOperation generate,
        PathSupplier generatedPack,
        PathSupplier uploadPack,
        Verifier verifier,
        int offsetY,
        int hotbarScale,
        Executor ioExecutor,
        Executor mainExecutor
    ) {
        Objects.requireNonNull(reload, "reload");
        Objects.requireNonNull(generate, "generate");
        Objects.requireNonNull(generatedPack, "generatedPack");
        Objects.requireNonNull(uploadPack, "uploadPack");
        Objects.requireNonNull(verifier, "verifier");
        Objects.requireNonNull(ioExecutor, "ioExecutor");
        Objects.requireNonNull(mainExecutor, "mainExecutor");

        final CompletableFuture<ReloadResult> reloaded;
        try {
            reloaded = reload.reload(ioExecutor, mainExecutor);
        } catch (Throwable throwable) {
            return failedFuture(throwable);
        }
        if (reloaded == null) {
            return failedFuture(new IOException("CraftEngine 资源重载 API 返回空 Future。"));
        }

        return reloaded.thenCompose(result -> {
            if (result == null || !result.success()) {
                String detail = result == null ? "无结果" : result.detail();
                return failedFuture(new IOException("CraftEngine 资源重载失败：" + detail));
            }
            // generateResourcePack() 是阻塞 API，必须在资源队列的异步执行器运行。
            return CompletableFuture.runAsync(() -> {
                try {
                    generate.generate();
                } catch (Throwable throwable) {
                    throw new CompletionException(new IOException(
                        "CraftEngine 资源包生成失败：" + messageOf(throwable), throwable));
                }
            }, ioExecutor).thenRunAsync(() -> {
                try {
                    verifyGeneratedAndUploaded(generatedPack.get(), uploadPack.get(), verifier, offsetY, hotbarScale);
                } catch (IOException exception) {
                    throw new CompletionException("生成的 CraftEngine 资源包校验失败："
                        + messageOf(exception), exception);
                } catch (Throwable throwable) {
                    throw new CompletionException("读取 CraftEngine 资源包路径失败："
                        + messageOf(throwable), throwable);
                }
            }, ioExecutor);
        });
    }

    static void verifyGeneratedAndUploaded(Path generated, Path upload, Verifier verifier, int offsetY)
        throws IOException {
        verifyGeneratedAndUploaded(generated, upload, verifier, offsetY, 100);
    }

    static void verifyGeneratedAndUploaded(Path generated, Path upload, Verifier verifier, int offsetY,
                                           int hotbarScale) throws IOException {
        Path generatedFile = requireRegularFile(generated, "CraftEngine 生成资源包");
        Path uploadFile = requireRegularFile(upload, "CraftEngine 上传资源包");

        verifier.verify(generatedFile, offsetY, hotbarScale);
        if (!samePath(generatedFile, uploadFile)) {
            if (Files.mismatch(generatedFile, uploadFile) != -1L) {
                throw new IOException("生成资源包与上传资源包内容不一致："
                    + generatedFile + " != " + uploadFile);
            }
            // 两条路径即使字节一致也分别验证，防止上传目标是未被校验的旧文件或链接目标。
            verifier.verify(uploadFile, offsetY, hotbarScale);
        }
    }

    private static Path requireRegularFile(Path path, String label) throws IOException {
        if (path == null) {
            throw new IOException(label + "路径为空。");
        }
        Path normalized = path.toAbsolutePath().normalize();
        if (!Files.isRegularFile(normalized)) {
            throw new IOException(label + "不存在或不是普通文件：" + normalized);
        }
        return normalized;
    }

    private static boolean samePath(Path first, Path second) {
        return first.equals(second);
    }

    private static String messageOf(Throwable throwable) {
        String message = throwable.getMessage();
        return message == null || message.isBlank()
            ? throwable.getClass().getSimpleName() : message;
    }

    private static <T> CompletableFuture<T> failedFuture(Throwable throwable) {
        CompletableFuture<T> failed = new CompletableFuture<>();
        failed.completeExceptionally(throwable);
        return failed;
    }

    @FunctionalInterface
    interface ReloadOperation {
        CompletableFuture<ReloadResult> reload(Executor ioExecutor, Executor mainExecutor);
    }

    @FunctionalInterface
    interface GenerateOperation {
        void generate() throws Exception;
    }

    @FunctionalInterface
    interface PathSupplier {
        Path get();
    }

    @FunctionalInterface
    interface Verifier {
        void verify(Path packPath, int offsetY) throws IOException;

        /** 旧 verifier 默认按 100% hotbar 档校验；生产实现可覆盖以校验指定档位。 */
        default void verify(Path packPath, int offsetY, int hotbarScale) throws IOException {
            verify(packPath, offsetY);
        }
    }

    record ReloadResult(boolean success, String detail) {
        ReloadResult {
            detail = detail == null ? "" : detail;
        }
    }
}
