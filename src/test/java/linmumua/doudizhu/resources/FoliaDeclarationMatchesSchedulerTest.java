package linmumua.doudizhu.resources;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

/**
 * folia-supported 的声明必须与调度层的实际能力一致。
 *
 * <p>【为什么这条比"补 Folia 支持"更紧要】：声明 {@code true} 而实际不支持，
 * 比声明 {@code false} 危害更大。管理员会照着声明把插件装到 Folia 上，
 * 然后插件在 onEnable 启动周期任务时直接抛 UnsupportedOperationException 崩掉——
 * Folia 把 BukkitScheduler 整个废弃了。声明 false 至少让人一眼知道别装。
 *
 * <p>这条测试把两件事绑在一起：只要 MuzScheduler 还在用 BukkitScheduler，
 * 声明就必须是 false；哪天真做了 Folia 适配，这条测试会提醒把声明改回来。
 */
class FoliaDeclarationMatchesSchedulerTest {
    private static final Path DESCRIPTOR =
        Path.of("src/main/resources/paper-plugin.yml");
    private static final Path SCHEDULER =
        Path.of("src/main/java/linmumua/doudizhu/scheduler/MuzScheduler.java");

    /** Folia 上不可用的 BukkitScheduler 入口。 */
    private static final String[] BUKKIT_SCHEDULER_CALLS = {
        "getScheduler().runTask(",
        "getScheduler().runTaskLater(",
        "getScheduler().runTaskTimer(",
        "getScheduler().runTaskAsynchronously(",
    };

    /** Folia 的区域调度入口，做了适配才会出现。 */
    private static final String[] FOLIA_SCHEDULER_APIS = {
        "getGlobalRegionScheduler",
        "getRegionScheduler",
        "getEntityScheduler",
        "getAsyncScheduler",
    };

    @Test
    void declarationIsFalseWhileSchedulerIsBukkitOnly() throws IOException {
        String scheduler = Files.readString(SCHEDULER);
        String descriptor = Files.readString(DESCRIPTOR);

        boolean usesBukkitScheduler = false;
        for (String call : BUKKIT_SCHEDULER_CALLS) {
            if (scheduler.contains(call)) {
                usesBukkitScheduler = true;
                break;
            }
        }

        boolean usesFoliaScheduler = false;
        for (String api : FOLIA_SCHEDULER_APIS) {
            if (scheduler.contains(api)) {
                usesFoliaScheduler = true;
                break;
            }
        }

        if (usesBukkitScheduler && !usesFoliaScheduler) {
            assertTrue(
                declaredFoliaSupport(descriptor).equals("false"),
                "MuzScheduler 仍然只用 BukkitScheduler，folia-supported 必须声明 false。"
                    + "声明 true 会让管理员在 Folia 上装，插件启动周期任务时就会抛"
                    + " UnsupportedOperationException 崩掉。"
            );
        } else if (usesFoliaScheduler) {
            assertTrue(
                declaredFoliaSupport(descriptor).equals("true"),
                "MuzScheduler 已经接了 Folia 区域调度，folia-supported 该改回 true 了"
            );
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
            descriptor.contains("BukkitScheduler"),
            "folia-supported 上方解释原因的注释被删了，"
                + "下一个人会不知道为什么是 false 而顺手改回 true"
        );
        assertFalse(
            descriptor.contains("folia-supported: true"),
            "又改回 true 了，但 MuzScheduler 并没有做区域调度适配"
        );
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
