package linmumua.doudizhu.listener;

import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

/**
 * 「进入桌内道具/语音前先检测活动 MuMenu 会话并早退」的源码契约。
 *
 * <p>背景：MUZ 的事件优先级是 LOWEST、MuMenu 是 HIGHEST。一次右键若被 MuMenu 面板接住，MUZ 仍会先跑
 * 自己的道具/语音路由；此时若 MUZ 投掷了道具或开了语音面板，就等于在面板打开期间误操作，而且**不能**
 * 依赖「MuMenu 晚取消」来兜底——MUZ 早就做完决定了。所以必须在 MUZ 自己的入口先判会话并返回 false
 * （不消费事件，把它让给 MuMenu）。
 *
 * <p>覆盖两个入口类：{@code WorldTableInteractionListener}（普通右键/实体右键）与
 * {@code CraftEngineProtectionListener}（CE 发包家具的右键）。两者各有同名
 * {@code tryOpenSpeechPanel}/{@code tryUseTableGadget}。
 *
 * <p>【为什么用源码契约】要真实 Bukkit 事件与玩家才能行为级驱动；这里钉住「守卫是方法体的第一句」，
 * 这与「不能假设晚取消有效」的语义直接对应。
 */
class MuMenuSessionGuardContractTest {
    private static final Path WORLD_LISTENER =
        Path.of("src/main/java/linmumua/doudizhu/listener/WorldTableInteractionListener.java");
    private static final Path CE_LISTENER =
        Path.of("src/main/java/linmumua/doudizhu/listener/CraftEngineProtectionListener.java");
    private static final String GUARD = "MuMenuGadgetBridge.sessionActive(player)";

    /** 普通右键 / 实体右键两个入口：语音与道具路由都必须先判会话。 */
    @Test
    void 世界监听器的语音与道具入口都先判会话() throws IOException {
        assertGuardIsFirst(
            WORLD_LISTENER,
            "private boolean tryOpenSpeechPanel(Player player)",
            "plugin.getTableSpeechPanelService()",
            "语音入口");
        assertGuardIsFirst(
            WORLD_LISTENER,
            "private boolean tryUseTableGadget(Player player, ItemStack item)",
            "plugin.isHudDebugStick(item)",
            "道具入口");
        assertTrue(Files.readString(WORLD_LISTENER)
                .contains("import linmumua.doudizhu.compat.MuMenuGadgetBridge;"),
            "必须 import 会话桥，守卫才编译得过");
    }

    /**
     * CE 发包家具的右键入口（{@code CraftEngineProtectionListener}）同样必须先判会话。
     *
     * <p>【跨 owner 契约】该文件归集成 owner（dialog-core）维护；这条断言把「CE 交互也要早退」这个
     * 功能要求变成可执行契约。它依赖 owner 在此处加同一行守卫——若失败，说明 CE 路径漏了让位。
     */
    @Test
    void CE家具交互入口也先判会话() throws IOException {
        assertGuardIsFirst(
            CE_LISTENER,
            "private boolean tryOpenSpeechPanel(Player player)",
            "plugin.getTableSpeechPanelService()",
            "CE 语音入口");
        assertGuardIsFirst(
            CE_LISTENER,
            "private boolean tryUseTableGadget(Player player)",
            "plugin.isHudDebugStick(",
            "CE 道具入口");
    }

    /**
     * 断言某方法的会话守卫出现在「下一个业务调用」之前（即守在最前）。
     *
     * <p>失败条件：守卫被删、被移到业务判断之后（等于没拦截）、或换成了别的判据。
     */
    private static void assertGuardIsFirst(
        Path file, String signature, String nextStatementMarker, String what
    ) throws IOException {
        String source = Files.readString(file);
        int start = source.indexOf(signature);
        assertTrue(start >= 0, "找不到 " + signature + "（" + what + "），这条测试的锚点已失效");
        int end = source.indexOf("\n    }", start);
        assertTrue(end > start, "找不到 " + signature + " 的结束锚点");
        String body = source.substring(start, end);

        int guard = body.indexOf(GUARD);
        assertTrue(guard >= 0, what + " 缺少 MuMenu 会话守卫：面板打开期间会误投掷/误开语音");
        int next = body.indexOf(nextStatementMarker);
        assertTrue(next < 0 || guard < next,
            what + " 的会话守卫必须排在最前（早于 " + nextStatementMarker + "），否则拦截无效");
    }
}
