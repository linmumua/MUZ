package linmumua.doudizhu.command;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

/**
 * /muz 关闭期注销与门禁的源码契约。
 *
 * <p>【为什么还需要源码契约】行为测试（{@link MuzCommandLifecycleBehaviorTest}）已经覆盖
 * 「门禁生效、注册无泄漏、摘除失败留痕不抛异常」；但有一条性质无法在单测里驱动：
 * <b>注销必须发生在 onDisable 内、且排在其它拆解步骤之前</b>——只有那样它才发生在 Paper
 * 关闭插件 JAR（PluginClassLoader 持有的 JarFile close）之前，残留命令才来得及被摘掉。
 * 这里用与 CreateCommandPermissionTest 同一套源码锚点做法把它钉住。
 *
 * <p>失败条件：
 * <ul>
 *   <li>有人把 onDisable 里的注销步骤删掉或挪到 onDisable 之外 / 挪到其它拆解步骤之后；</li>
 *   <li>有人把 /muz 的门禁从 onCommand 顶部挪到业务分支之后，或改成读取本插件类（会在 JAR 关闭后炸）；</li>
 *   <li>有人让 isRuntimeAvailable() 只看其中一个标志。</li>
 * </ul>
 */
class MuzCommandShutdownWiringContractTest {
    private static final Path PLUGIN =
        Path.of("src/main/java/linmumua/doudizhu/DoudizhuPlugin.java");
    private static final Path COMMAND =
        Path.of("src/main/java/linmumua/doudizhu/command/DoudizhuCommand.java");

    @Test
    void 注册必须保存命令引用() throws IOException {
        String source = Files.readString(PLUGIN);

        assertTrue(source.contains("private volatile Command muzCommand;"),
            "DoudizhuPlugin 必须保存已注册的 /muz 命令引用，否则关闭时无从注销");
        assertTrue(source.contains("muzCommand = command;"),
            "registerMuzCommand 必须把注册成功的命令实例写入 muzCommand 字段");
    }

    @Test
    void 重复注册前必须先摘掉旧命令() throws IOException {
        String source = Files.readString(PLUGIN);
        int body = source.indexOf("private void registerMuzCommand()");
        assertTrue(body >= 0, "找不到 registerMuzCommand，本条契约的锚点已失效");
        String registerBody = source.substring(body, source.indexOf("private void unregisterMuzCommand()"));

        assertTrue(registerBody.contains("detachMuzCommand(commandMap, previous,"),
            "重复注册前必须先摘掉上一份命令引用，否则旧实例会泄漏在 CommandMap 里");
    }

    @Test
    void 注销必须作为onDisable的第一步之一且在其它拆解之前() throws IOException {
        String source = Files.readString(PLUGIN);
        int onDisable = source.indexOf("public void onDisable()");
        assertTrue(onDisable >= 0, "找不到 onDisable，本条契约的锚点已失效");

        int unregisterStep = source.indexOf("runShutdownStep(\"注销 /muz 命令\", this::unregisterMuzCommand)", onDisable);
        assertTrue(unregisterStep > onDisable,
            "onDisable 必须在插件类加载器关闭之前经 runShutdownStep 注销 /muz 命令");

        // 必须排在其它拆解步骤之前：只有这样它才早于「插件 JAR 被关闭」这一刻。
        int firstTeardown = source.indexOf("runShutdownStep(\"关闭 Debug Web\"", onDisable);
        assertTrue(firstTeardown > 0, "找不到「关闭 Debug Web」步骤，本条契约的锚点已失效");
        assertTrue(unregisterStep < firstTeardown,
            "注销 /muz 必须排在其它拆解步骤之前，否则命令残留窗口会延伸到类加载器关闭之后");
    }

    @Test
    void 注销失败必须留痕且不打断关闭() throws IOException {
        String source = Files.readString(PLUGIN);
        int start = source.indexOf("private boolean detachMuzCommand(CommandMap commandMap, Command command, String reason)");
        assertTrue(start >= 0, "找不到 detachMuzCommand，本条契约的锚点已失效");
        int end = source.indexOf("private void logShutdownDiagnostics", start);
        assertTrue(end > start, "找不到 detachMuzCommand 的结束边界，本条契约的锚点已失效");
        String body = source.substring(start, end);

        assertTrue(body.contains("catch (RuntimeException | Error"),
            "detachMuzCommand 必须兜住异常，否则注销失败会沿 onDisable 外抛打断关闭");
        assertTrue(body.contains("getLogger().log(java.util.logging.Level.WARNING")
                || body.contains("getLogger().warning("),
            "注销失败必须留痕，否则实服只看到命令失效、看不到原因");
        // 关键：真正决定「还能不能被派发」的是 knownCommands，必须按 key 摘除（Paper 的转发 map 会同步删 Brigadier 节点）。
        assertTrue(body.contains("getKnownCommands()") && body.contains("known.remove(key)"),
            "必须从 CommandMap.getKnownCommands() 按 key 摘除命令，否则 Brigadier 仍会派发残留命令");
    }

    @Test
    void 命令门禁必须先于业务分支且不读取本插件类() throws IOException {
        String source = Files.readString(COMMAND);
        int onCommand = source.indexOf("public boolean onCommand(");
        assertTrue(onCommand >= 0, "找不到 onCommand，本条契约的锚点已失效");

        int gate = source.indexOf("if (!isRuntimeAvailable())", onCommand);
        int dispatch = source.indexOf("args[0].equalsIgnoreCase(\"help\")", onCommand);
        assertTrue(gate > onCommand, "onCommand 必须有 isRuntimeAvailable() 门禁");
        assertTrue(gate < dispatch,
            "门禁必须排在业务分支之前，否则禁用后仍会执行到解析本插件类的代码（zip file closed）");

        // 门禁分支只能用服务端类路径的 Adventure 组件；一旦调用 message()（会解析 MuzTheme 等本插件类），
        // 插件 JAR 关闭后这条兜底路径自己就会炸。
        String gateBody = source.substring(gate, source.indexOf("try {", gate));
        assertTrue(gateBody.contains("Component.text("),
            "门禁提示必须用 Component.text(...)（服务端类路径）构造");
        assertFalse(gateBody.contains("message("),
            "门禁分支不得调用 message()/MuzTheme 等本插件类：JAR 关闭后解析这些类会抛 zip file closed");
    }

    @Test
    void 门禁同时检查启用与关闭两个标志() throws IOException {
        String source = Files.readString(COMMAND);
        int method = source.indexOf("private boolean isRuntimeAvailable()");
        assertTrue(method >= 0, "找不到 isRuntimeAvailable，本条契约的锚点已失效");
        String body = source.substring(method, source.indexOf('}', method));

        assertTrue(body.contains("plugin.isEnabled()"),
            "门禁必须检查 Plugin 是否仍启用（Paper 在进入 onDisable 之前就把它置为 false）");
        assertTrue(body.contains("plugin.isShuttingDown()"),
            "门禁必须检查插件自制的关闭标志，覆盖「仍启用但已开始关闭」的窗口");
    }

    @Test
    void 补全也要走同一道门() throws IOException {
        String source = Files.readString(COMMAND);
        int onTabComplete = source.indexOf("public List<String> onTabComplete(");
        assertTrue(onTabComplete >= 0, "找不到 onTabComplete，本条契约的锚点已失效");
        int gate = source.indexOf("if (!isRuntimeAvailable())", onTabComplete);
        int firstBranch = source.indexOf("args.length == 1", onTabComplete);

        assertTrue(gate > onTabComplete && gate < firstBranch,
            "补全同样会解析本插件类（zip file closed），必须与 onCommand 共用同一道门禁");
    }
}
