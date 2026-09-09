package linmumua.doudizhu.compat;

import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

/**
 * 偏移服务失效后必须能重试，且失败要让玩家看得见。
 *
 * <p>背景：{@code CraftEngineOffsetService.initialised} 只置一次，解析失败后永不重试。
 * 而 {@code TrickHudService.render} 在偏移不可用时会把整条 HUD hide 掉——于是只要
 * CraftEngine 比 MUZ 晚就绪、或资源包配置出错导致它没加载成功，整条 HUD 就永久消失，
 * 且只能重启服务器。实际排查时这一步最难定位，因为玩家端只表现为「什么都没有」。
 *
 * <p>用源码扫描：这条链路要 Bukkit 的 PluginManager 与反射目标，单测里起不来。
 */
class OffsetServiceRetryTest {
    private static final Path SERVICE =
        Path.of("src/main/java/linmumua/doudizhu/compat/CraftEngineOffsetService.java");
    private static final Path LIFECYCLE =
        Path.of("src/main/java/linmumua/doudizhu/listener/CraftEngineLifecycleListener.java");
    private static final Path PLUGIN =
        Path.of("src/main/java/linmumua/doudizhu/DoudizhuPlugin.java");
    private static final Path HUD =
        Path.of("src/main/java/linmumua/doudizhu/game/TrickHudService.java");

    /**
     * 必须有重置解析结果的入口，且真的把标志位清掉。
     *
     * <p>失败条件：invalidate 被删，或它只清 fontManager 却忘了把 initialised 置回 false
     * ——那样下次仍会走「已初始化」的分支，等于没重试。
     */
    @Test
    void 有重置入口且真的清掉初始化标志() throws IOException {
        String source = Files.readString(SERVICE);
        int at = source.indexOf("public void invalidate()");
        assertTrue(at > 0, "必须有 invalidate 入口，否则解析失败后永不重试");
        String body = source.substring(at, at + 320);
        assertTrue(body.contains("initialised = false"),
            "必须把 initialised 置回 false，只清引用不清标志等于没重试");
        assertTrue(body.contains("fontManager = null"), "要丢掉旧的 FontManager 引用");
        assertTrue(body.contains("warned = false"),
            "也要重置警告去重，否则修好前那批人再也收不到提示");
    }

    /**
     * CraftEngine 启用与 reload 三个入口都要重置。
     *
     * <p>这三条是偏移服务可能失效的全部时机：CE 晚于 MUZ 就绪、玩家执行 CE reload、
     * 控制台执行 CE reload。漏掉任一条，那种情况下就仍然只能重启。
     *
     * <p>失败条件：任一入口漏掉 invalidate。
     */
    @Test
    void CE启用与两条reload路径都重置偏移缓存() throws IOException {
        String source = Files.readString(LIFECYCLE);
        assertTrue(source.split("invalidate\\(\\)", -1).length - 1 >= 3,
            "CE 启用、玩家 reload、控制台 reload 三处都要重置");
        int enableAt = source.indexOf("public void onPluginEnable");
        assertTrue(enableAt > 0 && source.substring(enableAt, enableAt + 500).contains("invalidate()"),
            "CraftEngine 启用时要重置——首次解析必然早于它就绪");
    }

    /**
     * /muz reload 也要重置。
     *
     * <p>服主修好资源包配置后的第一反应是 /muz reload。不在这里重置的话，
     * 修对了也依然什么都看不到，会以为问题没解决。
     *
     * <p>失败条件：reloadVisualState 里不再调 invalidate。
     */
    @Test
    void muz的reload也重试偏移解析() throws IOException {
        String source = Files.readString(PLUGIN);
        int at = source.indexOf("private ReloadSummary reloadVisualState");
        assertTrue(at > 0, "reloadVisualState 应当存在");
        assertTrue(source.substring(at, at + 900).contains("craftEngineOffsetService.invalidate()"),
            "/muz reload 要重试偏移解析，否则修好配置仍要重启");
    }

    /**
     * 偏移不可用要给玩家提示，且与「服主主动关掉」分开处理。
     *
     * <p>两者都会导致 HUD 消失，但一个是故障、一个是配置意图。合在一个 if 里就没法
     * 只对故障发提示——而这正是排查耗时最长的一环：玩家端毫无线索。
     *
     * <p>失败条件：两种情况被合回同一个条件判断，或提示被删掉。
     */
    @Test
    void 偏移不可用有玩家可见提示且与主动关闭分开() throws IOException {
        String source = Files.readString(HUD);
        assertTrue(source.contains("warnOffsetsUnavailableOnce"),
            "偏移不可用要给玩家提示，否则只表现为「什么都没有」");
        int enabledAt = source.indexOf("if (!settings.enabled())");
        int availableAt = source.indexOf("if (!offsetService.isAvailable())");
        assertTrue(enabledAt > 0 && availableAt > enabledAt,
            "主动关闭与偏移不可用必须是两个独立分支，前者静默、后者要提示");
    }

    /**
     * 提示要按人去重。
     *
     * <p>render 挂在每秒的倒计时广播上，不去重就是每秒一条、直接刷屏。
     *
     * <p>失败条件：去掉去重集合，或改成无条件发送。
     */
    @Test
    void 提示按人去重以免每秒刷屏() throws IOException {
        String source = Files.readString(HUD);
        int at = source.indexOf("private void warnOffsetsUnavailableOnce");
        assertTrue(at > 0, "提示方法应当存在");
        String body = source.substring(at, at + 400);
        assertTrue(body.contains("offsetWarnedViewers.add"),
            "必须按人去重——render 每秒都跑，不去重就是刷屏");
    }
}
