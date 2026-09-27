package linmumua.doudizhu.log;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;

/**
 * 统一日志门禁的源码覆盖契约：确保 {@code debug.enabled=false} 时【本插件的全部 WARNING 出口】
 * 都被门禁覆盖，没有绕过路径，也没有把门禁装到会波及别的插件的地方。
 *
 * <p>为什么需要源码级契约：门禁装在 logger 上，靠的是「本插件所有 WARNING 都经自有 logger 发出」这个
 * 全仓性质——单点行为测试测不到「某个新类又开了个自己的 logger 直接 warning」。这里扫描主源码钉住：
 * <ol>
 *   <li>WARNING 出口的接收者只能是插件自有 logger（{@code getLogger()} / {@code plugin.getLogger()}）
 *       或已知装着同一 logger 的字段/接缝，或者是显式门控的 {@link MuzLogGate#warn}；</li>
 *   <li>只有 {@code MuzLogGate} 自己改 logger 的 filter —— 防止有人把门禁装到 root/共享 logger 上，
 *       那会连别的插件的 WARNING 一起吞掉；</li>
 *   <li>门禁只依赖 {@code java.util.logging}，不碰 Bukkit、不碰玩家文本 API。</li>
 * </ol>
 */
class MuzLogGateCoverageContractTest {
    private static final Path MAIN_JAVA = Path.of("src/main/java");
    private static final Path GATE =
        Path.of("src/main/java/linmumua/doudizhu/log/MuzLogGate.java");
    private static final Path PLUGIN =
        Path.of("src/main/java/linmumua/doudizhu/DoudizhuPlugin.java");

    /**
     * 允许发出 WARNING 的接收者。除 {@link MuzLogGate} 外，其余都持有【本插件自己的】logger
     * （{@code plugin.getLogger()} 或由它注入的字段），因此门禁装到 {@code getLogger()} 上即覆盖它们。
     *
     * <p>新增一个「自己 new 了个 logger 直接 warning」的出口就会出现在 offender 列表里、让本测试变红——
     * 这正是要拦的：那种出口不会被门禁覆盖，debug=false 时仍会刷屏。
     */
    private static final Set<String> ALLOWED_RECEIVERS = Set.of(
        "getLogger()",
        "plugin.getLogger()",
        // MuzScheduler / OpenAiCompatibleAiChatGateway：logger 字段生产期由 plugin.getLogger() 注入。
        "logger",
        // GadgetPreviewSnapshotService：RuntimeAccess.logger() 生产期返回 plugin.getLogger()。
        "runtime.logger()"
    );

    /** 只匹配接收者本身、不匹配其参数列表里的内容（参数里的点/括号也已并入 receiver 捕获组）。 */
    private static final Pattern WARNING_CALL =
        Pattern.compile("([A-Za-z_$][\\w$.()]*)\\.warning\\(");
    private static final Pattern WARNING_LOG_CALL =
        Pattern.compile("([A-Za-z_$][\\w$.()]*)\\.log\\(\\s*(?:java\\.util\\.logging\\.)?Level\\.WARNING");

    /**
     * 全主源码扫描：WARNING 出口（{@code .warning(...)} 与 {@code .log(Level.WARNING, ...)}）的接收者
     * 必须落在允许集合内，且 {@link MuzLogGate} 自身除外。
     *
     * <p>失败条件：有人新增一个不受门禁覆盖的 WARNING 出口（例如 {@code Logger.getLogger(...).warning(...)}）。
     */
    @Test
    void 主源码没有绕过门禁的WARNING出口() throws IOException {
        List<String> offenders = new ArrayList<>();
        for (Path file : mainJavaFiles()) {
            if (file.equals(GATE)) {
                continue; // 门禁自己就是出口，其内部 logger.warning 是受控的转发。
            }
            String flat = flatten(dropCommentLines(Files.readString(file)));
            collectOffenders(file, flat, WARNING_CALL, offenders);
            collectOffenders(file, flat, WARNING_LOG_CALL, offenders);
        }
        assertTrue(offenders.isEmpty(),
            "以下 WARNING 出口不会经过装在 plugin.getLogger() 上的统一门禁，debug=false 时会漏喷日志；"
                + "请改走 plugin.getLogger() 或 MuzLogGate.warn(...)：" + offenders);
    }

    /**
     * 只有门禁自己改 logger 的 filter；插件装配处只调用 {@code MuzLogGate.install(...)}。
     *
     * <p>失败条件：在别处直接 {@code logger.setFilter(...)}（可能装到共享/root logger 上，波及别的插件），
     * 或插件没有把门禁装到自己的 logger 上。
     */
    @Test
    void 只有门禁自己改filter且装配装在插件logger上() throws IOException {
        for (Path file : mainJavaFiles()) {
            if (file.equals(GATE)) {
                continue;
            }
            assertFalse(flatten(Files.readString(file)).contains(".setFilter("),
                file + " 不应直接改 logger 的 filter：门禁只能由 MuzLogGate 统一安装，"
                    + "否则可能装到 root/共享 logger 上、吞掉别的插件的日志");
        }

        String plugin = Files.readString(PLUGIN);
        assertTrue(plugin.contains("MuzLogGate.install(getLogger())"),
            "onEnable 必须把门禁装到本插件自己的 logger（getLogger()）上，这是覆盖全部出口的前提");
        assertTrue(plugin.contains("MuzLogGate.setDebugEnabled(debugLoggingEnabled())"),
            "门禁开关必须由 debug.enabled 驱动");
    }

    /** {@code /muz reload} 路径必须重读开关：{@code loadRenderSettings} 里带同一句快照写入。 */
    @Test
    void reload会重读debug开关() throws IOException {
        String body = methodBody(PLUGIN, "private void loadRenderSettings()");
        assertTrue(body.contains("MuzLogGate.setDebugEnabled(debugLoggingEnabled())"),
            "loadRenderSettings 是 /muz reload 的入口，必须在这里重读 debug.enabled，"
                + "否则改完开关不重启不生效");
    }

    /**
     * 允许集合里的 {@code logger} / {@code runtime.logger()} 确实是「本插件自己的 logger」。
     *
     * <p>否则这些字段就成了绕过门禁的后门：如果它们指向的是 root/共享 logger，装在
     * {@code plugin.getLogger()} 上的门禁就覆盖不到、debug=false 时仍会喷日志。
     */
    @Test
    void 被放行的logger字段确实来自插件的getLogger() throws IOException {
        String plugin = Files.readString(PLUGIN);
        assertTrue(plugin.contains("new PhysicalChipService(this::chipPaymentItem, getLogger())"),
            "PhysicalChipService 的注入 logger 必须是 plugin 自己的 getLogger()");
        assertTrue(plugin.contains("new OpenAiCompatibleAiChatGateway(aiProviderConfig, getLogger())"),
            "AI 网关的注入 logger 必须是 plugin 自己的 getLogger()");

        String scheduler =
            Files.readString(Path.of("src/main/java/linmumua/doudizhu/scheduler/MuzScheduler.java"));
        assertTrue(scheduler.contains("plugin.getLogger()"),
            "MuzScheduler 的 Plugin 构造必须用 plugin.getLogger()（logger 字段生产期即它）");

        String preview =
            Files.readString(Path.of(
                "src/main/java/linmumua/doudizhu/debug/GadgetPreviewSnapshotService.java"));
        assertTrue(preview.contains("return plugin.getLogger();"),
            "GadgetPreviewSnapshotService 的生产 RuntimeAccess.logger() 必须返回 plugin.getLogger()");
    }

    /** 门禁只依赖 JUL：不碰 Bukkit（Folia 线程安全）、不碰玩家文本 API（不误伤 MuzTheme）。 */
    @Test
    void 门禁只按级别且不碰Bukkit与玩家文本() throws IOException {
        // 只看代码，不看注释/文档：注释里正当地引用了 MuzTheme.warning 作为「不要误处理」的反例。
        String gate = stripComments(Files.readString(GATE));
        assertFalse(gate.contains("org.bukkit"),
            "门禁不能在任意 lane 上触发 Bukkit API，否则可能引入 Folia 线程违规");
        assertFalse(gate.contains("MuzTheme") || gate.contains("TypewriterTextStyle"),
            "门禁是日志出口，不得耦合玩家文本 API（那是 MuzTheme.warning 等，不是日志）");
        assertTrue(gate.contains("Level.WARNING"),
            "门禁必须显式以 WARNING 为判据");
        assertTrue(gate.contains("java.util.logging"),
            "门禁只允许依赖 java.util.logging");
    }

    /** 去掉块注释与行注释，避免源码契约把注释里的示例误当成实际调用。 */
    private static String stripComments(String source) {
        return source.replaceAll("(?s)/\\*.*?\\*/", " ").replaceAll("(?m)//[^\\n]*", " ");
    }

    /**
     * 只丢掉「整行都是注释」的行（{@code //}、{@code /*}、javadoc 的 {@code *}）。
     *
     * <p>刻意不整段删注释：行内 {@code //} 可能是字符串里的 URL（{@code "https://…"}），
     * 整行判断不会截断字符串、造成「真实出口被吞掉」的假通过。
     */
    private static String dropCommentLines(String source) {
        StringBuilder out = new StringBuilder(source.length());
        for (String line : source.split("\n", -1)) {
            String trimmed = line.stripLeading();
            if (trimmed.startsWith("//") || trimmed.startsWith("*") || trimmed.startsWith("/*")) {
                out.append('\n');
                continue;
            }
            out.append(line).append('\n');
        }
        return out.toString();
    }

    private static void collectOffenders(Path file, String flat, Pattern pattern, List<String> offenders) {
        Matcher matcher = pattern.matcher(flat);
        while (matcher.find()) {
            String receiver = matcher.group(1);
            if (isPlayerTextApi(receiver) || ALLOWED_RECEIVERS.contains(receiver)) {
                continue;
            }
            offenders.add(file + " -> " + receiver);
        }
    }

    /**
     * 玩家可见文本 API 不是日志出口，必须排除：{@code MuzTheme.warning(...)} 与
     * {@code TypewriterTextStyle.warning(...)} 生成的是给玩家看的富文本（物品名/ActionBar 等），
     * 与 {@code debug.enabled} 无关（linmumua 明确要求「不要误处理 MuzTheme.warning 等玩家文本」）。
     *
     * <p>接收者可能被外层调用包住（例如 {@code meta.displayName(MuzTheme.warning(...))}），
     * 故按后缀判定而不是全等。
     */
    private static boolean isPlayerTextApi(String receiver) {
        return receiver.endsWith("MuzTheme") || receiver.endsWith("TypewriterTextStyle");
    }

    /** 折叠空白，使跨行的 {@code .log(} 与 {@code Level.WARNING} 也能被同一条正则捕获。 */
    private static String flatten(String source) {
        return source.replaceAll("\\s+", " ");
    }

    private static List<Path> mainJavaFiles() throws IOException {
        try (Stream<Path> stream = Files.walk(MAIN_JAVA)) {
            return stream.filter(path -> path.toString().toLowerCase(Locale.ROOT).endsWith(".java"))
                .toList();
        }
    }

    private static String methodBody(Path file, String signature) throws IOException {
        String source = Files.readString(file);
        int start = source.indexOf(signature);
        assertTrue(start >= 0, "找不到 " + signature + "，这条测试的锚点已失效");
        int end = source.indexOf("\n    }", start);
        assertTrue(end > start, "找不到 " + signature + " 的结束锚点");
        return source.substring(start, end);
    }

    /** 自检：允许集合不能把整个类别放水（例如误加通配）。 */
    @Test
    void 允许集合非空且点名具体接收者() {
        assertEquals(4, ALLOWED_RECEIVERS.size(),
            "允许集合必须是明确点名的具体接收者，新增出口要显式评审而不是放水");
    }
}
