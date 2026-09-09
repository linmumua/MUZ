package linmumua.doudizhu.command;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;

/**
 * HUD 调试棒相关的源码契约测试。
 *
 * <p>这些契约故意不实例化 Bukkit：命令分流依赖服务端运行时，
 * 这里直接锁住源码中的关键边界，避免旧调试子命令或错误的参数匹配重新混入正式入口。
 */
class HudDebugStickContractTest {
    private static final Path COMMAND =
        Path.of("src/main/java/linmumua/doudizhu/command/DoudizhuCommand.java");
    private static final List<Path> PREVIEW_CANDIDATES = List.of(
        Path.of("src/main/java/linmumua/doudizhu/game/TrickHudPreview.java"),
        Path.of("src/main/java/linmumua/doudizhu/TrickHudPreview.java")
    );

    @Test
    void giveDebug仅在恰好两参时分流并创建调试棒() throws IOException {
        String source = Files.readString(COMMAND);
        String give = topLevelBranchBody(source, "give");

        int twoArgs = give.indexOf("args.length == 2");
        int create = give.indexOf("createHudDebugStickItem");
        int normalGive = give.indexOf("requireArgs(args, 3");

        assertTrue(twoArgs >= 0,
            "/muz give debug 必须有恰好两参的分流条件，不能把 debug 当成玩家名位或三参语法");
        assertTrue(give.contains("args.length == 2 && isSelfGiveToken(args[1])"),
            "自发物品分流必须把恰好两参与第二参关键字一起作为前置条件");
        assertTrue(create > twoArgs,
            "恰好两参的 /muz give debug 分支必须调用 createHudDebugStickItem()");
        assertTrue(normalGive > create,
            "调试棒分流必须发生在普通 /muz give <玩家> ... 的三参校验之前");

        String route = give.substring(twoArgs, normalGive);
        assertTrue(route.contains("debug"), "两参分流必须明确识别 debug 关键字");
        assertTrue(route.contains("createHudDebugStickItem()"),
            "debug 关键字不能只出现在补全中，必须实际创建 HUD 调试棒");
    }

    @Test
    void give补全包含debug入口() throws IOException {
        String source = Files.readString(COMMAND);
        String completion = regionAfter(source,
            "if (args.length == 2 && args[0].equalsIgnoreCase(\"give\"))", 420);

        assertTrue(completion.contains("\"debug\""),
            "/muz give 的恰好两参补全必须包含 debug");
    }

    @Test
    void 若存在TrickHudPreview则保留可使用的公开预览API() throws IOException {
        Path preview = PREVIEW_CANDIDATES.stream().filter(Files::exists).findFirst().orElse(null);
        if (preview == null) {
            return;
        }

        String source = Files.readString(preview);
        assertTrue(source.contains("class TrickHudPreview"),
            "TrickHudPreview 文件存在时必须仍然声明 TrickHudPreview 类");
        for (String method : List.of("show(", "hide(", "reloadSettings(", "hideAll(")) {
            assertTrue(source.contains(method),
                "TrickHudPreview 存在时必须保留关键 API: " + method);
        }
    }

    private static String topLevelBranchBody(String source, String branch) {
        String anchor = "                case \"" + branch + "\" -> {";
        int start = source.indexOf(anchor);
        assertTrue(start >= 0, "找不到顶层 case \"" + branch + "\"");

        Matcher next = Pattern.compile("\\n {16}case \"").matcher(source);
        int end = next.find(start + anchor.length()) ? next.start() : source.length();
        return source.substring(start, end);
    }

    private static String regionAfter(String source, String anchor, int length) {
        int start = source.indexOf(anchor);
        assertTrue(start >= 0, "找不到源码锚点: " + anchor);
        return source.substring(start, Math.min(source.length(), start + length));
    }

}
