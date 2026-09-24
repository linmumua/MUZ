package linmumua.doudizhu.resources;

import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;

/**
 * Folia 声明与调度迁移状态的守护测试。
 *
 * <p>2026-09-24 起本键为**正式声明**：调度后端已建立 global、region、entity 与 async 四条
 * owner lane，牌桌、玩家、实体与麻将领域的 owner 路由收口到门面，契约测试与真实 Folia
 * 服务端运行期冒烟均已通过，因此 {@code folia-supported} 必须为 {@code true}。
 *
 * <p>本测试仍然守住两件事：(1) 调度接缝不许被拆掉——声明 true 的前提是后端真的提供了这些
 * Folia 调度入口；(2) 声明回退到 {@code false} 会被立刻发现，需要连同文档一起说明原因。
 * 业务层绕过 {@code MuzScheduler} 直调 Bukkit 调度器的扫描与其他 owner 路由断言一律不变。
 */
class FoliaDeclarationMatchesSchedulerTest {
    private static final Path DESCRIPTOR =
        Path.of("src/main/resources/paper-plugin.yml");
    private static final Path SCHEDULER_BACKEND =
        Path.of("src/main/java/linmumua/doudizhu/scheduler/PaperSchedulerBackend.java");

    /** Folia 上不可用的 BukkitScheduler 入口。 */
    private static final String[] BUKKIT_SCHEDULER_CALLS = {
        "getScheduler().runTask(",
        "getScheduler().runTaskLater(",
        "getScheduler().runTaskTimer(",
        "getScheduler().runTaskAsynchronously(",
    };

    /** Folia 调度入口必须进入兼容后端，这是声明 true 的前提。 */
    private static final String[] FOLIA_SCHEDULER_APIS = {
        "getGlobalRegionScheduler",
        "getRegionScheduler",
        "getAsyncScheduler",
    };

    /** 业务层禁止绕过 MuzScheduler 直接调用 Bukkit 调度 API。 */
    private static final String[] FORBIDDEN_DIRECT_CALLS = {
        "Bukkit.getScheduler()",
        "getServer().getScheduler()",
        "BukkitRunnable",
        "runTaskAsynchronously(",
        "runTaskLater(",
        "runTaskTimer(",
        "runTask(",
    };

    @Test
    void declarationIsTrueAndBackedBySchedulerAdapters() throws IOException {
        String scheduler = Files.readString(SCHEDULER_BACKEND);
        String descriptor = Files.readString(DESCRIPTOR);

        assertTrue(scheduler.contains("getGlobalRegionScheduler"),
            "调度后端必须建立 global scheduler 接缝");
        assertTrue(scheduler.contains("getRegionScheduler"),
            "调度后端必须建立 region scheduler 接缝");
        assertTrue(scheduler.contains("getAsyncScheduler"),
            "调度后端必须建立 async scheduler 接缝");
        assertTrue("true".equals(declaredFoliaSupport(descriptor)),
            "正式声明支持 Folia：调度接缝存在时 folia-supported 必须为 true；"
                + "若回退为 false，必须同时更新文档说明原因");
    }

    @Test
    void productionCodeCannotBypassSchedulerFacade() throws IOException {
        Path sourceRoot = Path.of("src/main/java");
        try (Stream<Path> paths = Files.walk(sourceRoot)) {
            String violations = paths
                .filter(path -> path.toString().endsWith(".java"))
                .filter(path -> !path.startsWith(Path.of(
                    "src/main/java/linmumua/doudizhu/scheduler")))
                .map(path -> {
                    try {
                        String source = stripComments(Files.readString(path));
                        String matches = java.util.Arrays.stream(FORBIDDEN_DIRECT_CALLS)
                            .filter(source::contains)
                            .reduce((left, right) -> left + ", " + right)
                            .orElse("");
                        return matches.isEmpty() ? "" : path + ": " + matches;
                    } catch (IOException exception) {
                        throw new java.io.UncheckedIOException(exception);
                    }
                })
                .filter(line -> !line.isEmpty())
                .reduce((left, right) -> left + "\n" + right)
                .orElse("");
            assertTrue(violations.isEmpty(),
                "业务代码不得绕过 MuzScheduler 直接调用 Bukkit 调度器：\n" + violations);
        }
    }

    /**
     * 描述文件里必须真的有这个键，且值只能是 true / false。
     * 失败条件：键被整行删掉（Paper 会按默认值处理，等于把决策交给运气）。
     */
    @Test
    void declarationKeyExistsAndIsBoolean() throws IOException {
        String value = declaredFoliaSupport(Files.readString(DESCRIPTOR));
        assertTrue(
            "true".equals(value) || "false".equals(value),
            "folia-supported 的值必须是 true 或 false，实际读到: " + value
        );
    }

    /**
     * 声明与决策注释必须同时在场。
     *
     * <p>这个键只有一行，改动成本是 4 个字符，但无论方向如何都会改变管理员能否安装到 Folia，
     * 因此值本身与「为什么是这个值」的注释一起纳入测试：注释被删掉时，下一个人会看不到
     * 声明依据与残余风险就顺手改值。
     */
    @Test
    void decisionCommentIsPreserved() throws IOException {
        String descriptor = Files.readString(DESCRIPTOR);
        assertTrue(
            descriptor.contains("真实 Folia 服务端"),
            "folia-supported 上方关于真实 Folia 验收事实与残余风险的注释被删了，"
                + "下一个人会不知道为什么是 true 而顺手改回 false（或反之）"
        );
        assertTrue(
            descriptor.contains("folia-supported: true"),
            "正式声明的值应为 true；若确实要回退为 false，必须同时更新 AGENTS.md 与 README.md"
        );
    }

    /** 去掉源码注释，避免注释中的迁移说明触发业务直调扫描。 */
    private static String stripComments(String source) {
        return source
            .replaceAll("(?s)/\\*.*?\\*/", "")
            .replaceAll("(?m)//.*$", "");
    }

    /** 读 folia-supported 的值，忽略注释行。 */
    private static String declaredFoliaSupport(String descriptor) {
        for (String line : descriptor.replace("\r\n", "\n").split("\n")) {
            String trimmed = line.trim();
            if (trimmed.startsWith("#")) {
                continue;
            }
            if (trimmed.startsWith("folia-supported:")) {
                return trimmed.substring("folia-supported:".length()).trim();
            }
        }
        return "<缺失>";
    }
}
