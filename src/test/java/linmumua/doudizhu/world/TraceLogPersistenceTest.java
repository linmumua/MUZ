package linmumua.doudizhu.world;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

/**
 * /muz debug trace 落盘必须守住的几件事。
 *
 * <p>背景：trace 原来只发聊天，排查时 AI 助手在 logs/latest.log 里翻不到任何内容，
 * 只能靠人截图复述。现在同一条消息会写进 plugins/MUZ/debug/trace.log。
 *
 * <p>这个功能有四个真实的翻车方向，每条测试各守一个：关闭态被引进 IO、
 * Supplier 被 get 两次、文件里混进颜色码、以及在点击链路上做阻塞写盘。
 *
 * <p>用源码扫描而不是调用方法：trace 要 Bukkit 的 Player 和 Plugin，这个项目跑不起
 * Bukkit。写法沿用同目录的 {@code HandCardClickRoutingTest}。
 */
class TraceLogPersistenceTest {
    private static final Path MANAGER =
        Path.of("src/main/java/linmumua/doudizhu/world/PhysicalTableManager.java");
    private static final Path COMMAND =
        Path.of("src/main/java/linmumua/doudizhu/command/DoudizhuCommand.java");
    private static final String TRACE_SIGNATURE =
        "void trace(Player player, NamedTextColor color, Supplier<String> message)";

    /**
     * 关闭 trace 时一行代码都不许多跑，落盘更不许碰。
     *
     * <p>守的风险：trace 挂在每次点击、每个事件入口上。把落盘写在开关判断之前，
     * 等于给全服所有玩家的每次交互都加一次文件操作——哪怕没人开 trace。
     * 这是原实现刻意把开关放第一行的理由，加落盘不能把它抵消掉。
     *
     * <p>失败条件：把 appendTraceLine 或任何文件/时间戳操作提到 return 之前。
     */
    @Test
    void traceStillReturnsBeforeTouchingAnythingWhenDisabled() throws IOException {
        String body = methodBody(MANAGER, TRACE_SIGNATURE);
        int guard = body.indexOf("if (!traceViewers.contains(player.getUniqueId())) {");
        assertTrue(guard >= 0, "trace 的开关判断不见了：关闭态零开销这个前提已经没了");
        int earlyReturn = body.indexOf("return;", guard);
        assertTrue(earlyReturn > guard, "trace 的开关判断没有立刻 return");

        String beforeGuard = body.substring(0, guard);
        assertTrue(!beforeGuard.contains("message.get()"),
            "开关判断之前就 get 了 Supplier：关闭态也会拼字符串，零开销语义被破坏");

        String uptoReturn = body.substring(0, earlyReturn);
        for (String forbidden : new String[] {"appendTraceLine", "traceLineBuffer", "LocalTime", "traceWriter"}) {
            assertTrue(!uptoReturn.contains(forbidden),
                "关闭态就执行了 " + forbidden + "：等于给每个没开 trace 的玩家的每次点击都加一次落盘开销");
        }
        // 落盘必须在闸门之后。只查「不在之前」不够：得确认它真的存在于开启分支里。
        assertTrue(body.indexOf("appendTraceLine", earlyReturn) > earlyReturn,
            "开启态没有落盘调用：trace 又只发聊天了，排查还得靠人复述");
    }

    /**
     * Supplier 只许 get 一次，结果同时喂给聊天和文件。
     *
     * <p>守的风险：调用点大量是 {@code String.format(...)} 和长连接串。get 两次不只是
     * 白算一遍——Supplier 里若有任何副作用（计数、取当前状态），两次执行会得到不同结果，
     * 于是聊天里和文件里出现两份对不上的内容，排查时直接被误导。
     *
     * <p>失败条件：为了落盘再调一次 message.get()。
     */
    @Test
    void traceEvaluatesTheSupplierExactlyOnceAndSharesTheResult() throws IOException {
        String body = methodBody(MANAGER, TRACE_SIGNATURE);
        assertEquals(1, countOccurrences(body, "message.get()"),
            "trace 里 message.get() 不是恰好一次：Supplier 带副作用时聊天和文件会出现两份不一致的内容");
        assertTrue(body.contains("String raw = message.get();"),
            "trace 没把 Supplier 结果存进局部变量：聊天和落盘无法共用同一份结果");
    }

    /**
     * 落盘内容必须是纯文本，不带颜色码和 MiniMessage 标签。
     *
     * <p>守的风险：现有 trace 用 {@code MuzTheme.named(...)} 上色。顺手把上好色的
     * Component 序列化下去，文件里就会塞满 §x 或 &lt;color&gt; 标签。这个文件的读者是人和
     * AI，颜色码是纯噪音，还会让关键值被标签切碎、搜不到。
     *
     * <p>失败条件：落盘改成传 Component、或走 MiniMessage/Legacy 序列化器。
     */
    @Test
    void tracePersistsPlainTextWithoutAnyColorMarkup() throws IOException {
        String body = methodBody(MANAGER, TRACE_SIGNATURE);
        assertTrue(body.contains("appendTraceLine(player.getName(), raw)"),
            "落盘没有直接用未上色的 raw：一旦改传 Component，文件里会混进颜色码");

        String append = methodBody(MANAGER, "private void appendTraceLine(String playerName, String raw)");
        for (String forbidden : new String[] {"MuzTheme", "MINI", "MiniMessage", "Component", "serialize"}) {
            assertTrue(!append.contains(forbidden),
                "落盘路径里出现了 " + forbidden + "：文件会带上颜色码/标签，人和 AI 读起来全是噪音");
        }
        // 时间戳和玩家名是多人同时开 trace 时唯一的区分手段，缺一个就无法把交错的行分开。
        assertTrue(append.contains("TRACE_TIME_FORMAT") && append.contains("playerName"),
            "落盘行缺时间戳或玩家名：多人同时开 trace 时输出交错在一起，分不清谁触发的");
    }

    /**
     * 主线程不许做阻塞 IO，且关服时不许丢数据。
     *
     * <p>守的风险：trace 是高频路径。在主线程上每条消息 open/write/close 一次文件，
     * 会把磁盘延迟直接加到点击响应里——排查工具本身成了卡顿源，比不排查更糟。
     * 方案是主线程只做一次内存 add，写盘全在 runAsync 里。
     *
     * <p>另一半是关闭时的完整性：异步任务在关服阶段已经不会再被调度，
     * 必须在 shutdown 里同步把尾巴写掉，否则崩服前最后几行——恰好是最有用的那几行——会丢。
     *
     * <p>失败条件：把写盘搬回主线程，或去掉 shutdown 的收尾。
     */
    @Test
    void traceWritesOffTheMainThreadYetLosesNothingOnShutdown() throws IOException {
        String append = methodBody(MANAGER, "private void appendTraceLine(String playerName, String raw)");
        // 主线程侧只允许纯内存操作 + 一次异步调度。
        assertTrue(append.contains("traceLineBuffer.add(line)"),
            "主线程侧没有走内存缓冲：说明写盘被放回了点击链路上");
        assertTrue(append.contains("plugin.scheduler().runAsync(this::flushTraceBuffer)"),
            "落盘没有异步调度：主线程会在每次点击时阻塞在磁盘 IO 上");
        for (String forbidden : new String[] {"FileOutputStream", "write(", "flush()"}) {
            assertTrue(!append.contains(forbidden),
                "主线程侧出现了 " + forbidden + "：阻塞 IO 又回到了高频点击链路里");
        }
        // 一次点击会连打好几行。没有合并标志就会为同一批内容排好几个任务去抢同一个文件。
        assertTrue(append.contains("traceFlushScheduled"),
            "缺少 flush 合并标志：一次点击的多行会调度多个异步任务并发写同一个文件");

        String flush = methodBody(MANAGER, "private void flushTraceBuffer()");
        assertTrue(flush.contains("BufferedWriter") && flush.contains("traceWriter.flush()"),
            "异步侧没有 buffered writer 或没在批尾 flush：要么每批重开文件，要么内容留在缓冲里读不到");
        assertTrue(flush.contains("mkdirs()"),
            "落盘没建目录：全新服第一次开 trace 时 debug/ 不存在，会直接写失败");
        assertTrue(flush.contains("catch") && flush.contains("traceWriteFailureWarned"),
            "写盘异常没被吞掉或没做告警去重：前者会把游戏逻辑带崩，后者会把控制台刷满");

        // 文件必须有界，否则一场长时间排查就能写出几百 MB。
        String rotate = methodBody(MANAGER, "private void rotateTraceLogIfTooLarge(java.io.File file)");
        assertTrue(rotate.contains("TRACE_LOG_MAX_BYTES") && rotate.contains("renameTo"),
            "追踪日志没有大小上限或不轮转：长时间排查会把硬盘吃满");

        String shutdown = methodBody(MANAGER, "public void shutdown()");
        int stopping = shutdown.indexOf("isStopping()");
        int traceShutdown = shutdown.indexOf("shutdownTraceLog()");
        assertTrue(traceShutdown >= 0, "shutdown 没收尾追踪日志：关服前最后几行会丢，而那几行最有用");
        assertTrue(stopping < 0 || traceShutdown < stopping,
            "追踪日志收尾放在了 isStopping 分支之后：关服那条路会直接 return，尾巴写不下去");
    }

    /**
     * 开关提示必须告诉玩家文件在哪。
     *
     * <p>守的风险：落盘了但没人知道路径，等于没落盘——用户还是会来问「文件在哪」，
     * 这个功能的全部价值就是让人能直接把文件取走。
     *
     * <p>失败条件：提示文案里的路径被删掉，或代码里另写一份字面量路径导致两处漂移。
     */
    @Test
    void toggleFeedbackTellsThePlayerWhereTheFileIs() throws IOException {
        String command = Files.readString(COMMAND);
        assertTrue(command.contains("PhysicalTableManager.TRACE_LOG_RELATIVE_PATH"),
            "trace 开关提示没带日志路径常量：用户不知道去哪儿拿文件，或路径改动后提示会过期");

        String manager = Files.readString(MANAGER);
        assertTrue(manager.contains("\"plugins/MUZ/debug/trace.log\""),
            "追踪日志的公开路径常量不见了：命令提示和实际写入位置会脱节");
        // 常量和真正的写入位置必须指同一处，否则提示把人指向一个空目录。
        String file = methodBody(MANAGER, "private java.io.File traceLogFile()");
        assertTrue(file.contains("\"debug\"") && file.contains("\"trace.log\""),
            "实际写入位置和公开的相对路径不一致：提示会把人指向错误的目录");
    }

    private static int countOccurrences(String haystack, String needle) {
        int count = 0;
        int from = 0;
        while (true) {
            int at = haystack.indexOf(needle, from);
            if (at < 0) {
                return count;
            }
            count++;
            from = at + needle.length();
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
}
