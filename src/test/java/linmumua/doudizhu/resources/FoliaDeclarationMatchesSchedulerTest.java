package linmumua.doudizhu.resources;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;

/**
 * Folia 声明与第一阶段调度迁移状态的守护测试。
 *
 * <p>当前调度后端已经能够调用 Paper 提供的 global、region、entity 与 async scheduler，
 * 但牌桌、玩家、实体和麻将领域尚未完成 owner 路由，也没有真实 Folia 服务端验收，
 * 因此声明必须继续保持 false。
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

    /** Folia 调度入口已进入兼容后端，但业务 owner 迁移完成前仍不得声明支持。 */
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
    void declarationRemainsFalseUntilRealFoliaAcceptance() throws IOException {
        String scheduler = Files.readString(SCHEDULER_BACKEND);
        String descriptor = Files.readString(DESCRIPTOR);

        assertTrue(scheduler.contains("getGlobalRegionScheduler"),
            "调度后端必须建立 global scheduler 接缝");
        assertTrue(scheduler.contains("getRegionScheduler"),
            "调度后端必须建立 region scheduler 接缝");
        assertTrue(scheduler.contains("getAsyncScheduler"),
            "调度后端必须建立 async scheduler 接缝");
        assertTrue("false".equals(declaredFoliaSupport(descriptor)),
            "没有真实 Folia 服务端验收前，folia-supported 必须保持 false");
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
     * 决策注释必须留着。
     *
     * <p>这个键只有一行，改回 true 的成本是 4 个字符，但后果是 Folia 上直接崩。
     * 注释是唯一能拦住"顺手改回来"的东西，所以把它也纳入测试。
     */
    @Test
    void decisionCommentIsPreserved() throws IOException {
        String descriptor = Files.readString(DESCRIPTOR);
        assertTrue(
            descriptor.contains("真实 Folia 服务端"),
            "folia-supported 上方关于真实 Folia 验收门槛的注释被删了，"
                + "下一个人会不知道为什么是 false 而顺手改回 true"
        );
        assertFalse(
            descriptor.contains("folia-supported: true"),
            "又改回 true 了，但 MuzScheduler 并没有做区域调度适配"
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
