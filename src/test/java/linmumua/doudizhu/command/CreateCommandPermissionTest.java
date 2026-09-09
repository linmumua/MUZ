package linmumua.doudizhu.command;

import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;

/**
 * /muz create 必须自带 muz.admin 权限门。
 *
 * <p>【为什么必须锁住】：paper-plugin.yml 里 muz.command 是 {@code default: true}，
 * 也就是全服玩家默认拿得到。而 create 会在世界里实打实摆出一张牌桌——一批 Display Entity
 * 加一条持久化记录。这个分支一旦漏掉权限判断，任何玩家都能无限刷实体，
 * 既是世界污染也是性能问题，而且残留实体很难人工收拾。
 *
 * <p>【为什么用源码文本断言】：权限判断发生在 Bukkit 的 CommandSender 上，
 * 单测里没有真实服务端可以驱动 onCommand。项目里 AdminSettingRangeReleasedTest
 * 已经用了同一套源码锚点做法，这里沿用，保持一致。
 *
 * <p>失败条件：有人把 create 分支里的 hasPermission("muz.admin") 删掉或改宽。
 */
class CreateCommandPermissionTest {
    private static final Path COMMAND =
        Path.of("src/main/java/linmumua/doudizhu/command/DoudizhuCommand.java");

    /** 会改变世界状态的顶层子命令，必须逐个带 muz.admin。 */
    private static final String[] WORLD_MUTATING_BRANCHES = {
        "create", "set", "remove", "forceend", "reload"
    };

    @Test
    void createBranchRequiresAdminPermission() throws IOException {
        String body = topLevelBranchBody("create");

        assertTrue(
            body.contains("hasPermission(\"muz.admin\")"),
            "create 分支没有 muz.admin 权限门。muz.command 默认 true，"
                + "这意味着任何玩家都能刷牌桌实体。"
        );
    }

    /**
     * 权限判断必须在 placeNewTable 之前，否则牌桌已经摆出来了才拦，等于没拦。
     * 失败条件：把 hasPermission 挪到实际落地动作之后。
     */
    @Test
    void permissionCheckHappensBeforeTableIsPlaced() throws IOException {
        String body = topLevelBranchBody("create");

        int gate = body.indexOf("hasPermission(\"muz.admin\")");
        int place = body.indexOf("placeNewTable");

        assertTrue(gate >= 0, "create 分支找不到权限判断");
        assertTrue(place >= 0, "create 分支找不到 placeNewTable，这条测试的锚点已失效");
        assertTrue(
            gate < place,
            "权限判断排在 placeNewTable 之后，牌桌会先被摆出来再报错"
        );
    }

    /**
     * 所有会改变世界状态的顶层子命令都不能裸奔。
     * 这条比单看 create 更宽，防的是"下次再新增一个 create 类命令又忘了加门"。
     */
    @Test
    void allWorldMutatingBranchesAreGated() throws IOException {
        for (String branch : WORLD_MUTATING_BRANCHES) {
            String body = topLevelBranchBody(branch);
            assertTrue(
                body.contains("hasPermission(\"muz.admin\")"),
                "顶层子命令 " + branch + " 会改变世界或全局状态，却没有 muz.admin 权限门"
            );
        }
    }

    /**
     * 取顶层 switch 里某个 case 分支的分支体。
     *
     * <p>按缩进定位：顶层 switch 的 case 缩进是 16 个空格，嵌套 switch（如 chip 下面的
     * mode / setitem）缩进更深。不按缩进区分的话，嵌套分支会被误当成顶层分支，
     * 让"未设门"的判断出现假阳性——chip 的子分支其实继承了 chip 自己的门。
     */
    private static String topLevelBranchBody(String branch) throws IOException {
        String source = Files.readString(COMMAND);
        String anchor = "                case \"" + branch + "\" -> {";
        int start = source.indexOf(anchor);
        assertTrue(start >= 0, "找不到顶层 case \"" + branch + "\"，这条测试的锚点已失效");

        // 分支体结束于下一个同缩进的 case，或顶层 switch 的收尾
        Matcher next = Pattern.compile("\n {16}case \"").matcher(source);
        int end = next.find(start + anchor.length()) ? next.start() : source.length();
        return source.substring(start, end);
    }
}
