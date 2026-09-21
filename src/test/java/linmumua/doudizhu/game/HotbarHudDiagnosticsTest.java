package linmumua.doudizhu.game;

import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

/**
 * Hotbar 运行期阻断诊断契约。
 *
 * <p>HotbarHudService 的真正发送路径依赖 Bukkit、牌桌和 CraftEngine，单元测试不启动完整
 * 服务端；这里锁住诊断必须存在、按原因限频，并且不能改变既有阶段降级分支。
 */
class HotbarHudDiagnosticsTest {
    private static final Path SERVICE =
        Path.of("src/main/java/linmumua/doudizhu/game/HotbarHudService.java");

    @Test
    void 诊断按原因限频而不是按tick刷屏() throws IOException {
        String source = Files.readString(SERVICE);
        assertTrue(source.contains("DIAGNOSTIC_INTERVAL_NANOS = 10_000_000_000L"),
            "诊断必须有明确的 10 秒限频窗口");
        int at = source.indexOf("private void diagnoseBlocked(");
        assertTrue(at > 0, "必须集中提供 Hotbar 阻断诊断入口");
        String body = source.substring(at, Math.min(source.length(), at + 520));
        assertTrue(body.contains("System.nanoTime()"), "限频时间必须使用单调时钟");
        assertTrue(body.contains("diagnosticLogAt.get(key)"), "必须按阻断原因读取最近记录时间");
        assertTrue(body.contains("diagnosticLogAt.put(key, now)"), "首次或过期诊断必须记录时间");
        assertTrue(body.contains("plugin.getLogger().log(level"), "阻断原因必须写入服务端日志");
    }

    @Test
    void 周期任务的启用与CraftEngine阻断有中文诊断() throws IOException {
        String source = Files.readString(SERVICE);
        int at = source.indexOf("private void diagnoseTickBlocked()");
        assertTrue(at > 0, "周期任务阻断需要独立诊断方法");
        String body = source.substring(at, Math.min(source.length(), at + 620));
        assertTrue(body.contains("hotbar-hud.enabled=false"), "关闭配置时要明确记录配置原因");
        assertTrue(body.contains("CraftEngine 偏移服务不可用"), "CE 不可用时要明确记录依赖原因");
        assertTrue(source.contains("diagnoseTickBlocked();"),
            "tick 的既有清理路径必须接入诊断而不能静默 return");
    }

    @Test
    void PLAYING真人与非PLAYING真人路径均保留且可诊断() throws IOException {
        String source = Files.readString(SERVICE);
        int tick = source.indexOf("private void tick()");
        int ready = source.indexOf("private boolean hotbarOverlayReady()", tick);
        assertTrue(tick > 0 && ready > tick, "应能定位完整周期推送路径");
        String body = source.substring(tick, ready);
        assertTrue(body.contains("table.getPhase() != GamePhase.PLAYING"),
            "非 PLAYING 牌桌仍必须被阶段门阻断");
        assertTrue(body.contains("table.isBot(id)"), "机器人仍必须被真人门阻断");
        assertTrue(body.contains("player == null || !player.isOnline()"),
            "离线真人仍必须被在线门阻断");
        assertTrue(body.contains("diagnoseBlocked(\"not-playing\""), "非 PLAYING 阻断要有诊断");
        assertTrue(body.contains("diagnoseBlocked(\"bot-seat\""), "机器人阻断要有诊断");
        assertTrue(body.contains("diagnoseBlocked(\"offline-seat\""), "离线阻断要有诊断");
    }

    @Test
    void overlay未ready仍降级普通ActionBar并记录原因() throws IOException {
        String source = Files.readString(SERVICE);
        int at = source.indexOf("if (!hotbarOverlayReady())");
        assertTrue(at > 0, "overlay 未就绪分支必须保留");
        String body = source.substring(at, Math.min(source.length(), at + 600));
        assertTrue(body.contains("diagnoseBlocked(\"overlay-not-ready\", Level.WARNING"),
            "overlay 未就绪必须记录中文告警");
        assertTrue(body.contains("player.sendActionBar(overlay == null"),
            "overlay 未就绪时仍必须沿用普通 ActionBar 降级语义");
        assertTrue(body.contains("continue;"), "overlay 未就绪后不能继续发送自定义字形");
    }

    @Test
    void 周期调度通过可注入入口而不是业务层直调Bukkit() throws IOException {
        String source = Files.readString(SERVICE);
        assertTrue(source.contains("private MuzScheduler.TaskHandle task"),
            "周期任务句柄必须使用统一调度器抽象");
        assertTrue(source.contains("private final MuzScheduler scheduler"),
            "调度器必须由服务持有并可注入");
        assertTrue(source.contains("MuzScheduler scheduler"),
            "必须提供带调度器参数的构造入口");
        assertTrue(source.contains("task = scheduler.runTimer(2L, 2L, this::tick)"),
            "周期任务必须通过注入的调度器创建");
        assertTrue(!source.contains("runTaskTimer"),
            "业务服务不得直接调用 Bukkit runTaskTimer");
        assertTrue(!source.contains("getServer().getScheduler()"),
            "业务服务不得直接访问 Bukkit 调度器");
    }

    @Test
    void 普通叠加职责已委托给独立服务() throws IOException {
        String source = Files.readString(SERVICE);
        assertTrue(source.contains("private final ActionBarOverlayService actionBarOverlay"),
            "Hotbar 必须持有普通 ActionBar 兼容服务");
        assertTrue(source.contains("actionBarOverlay.storeOverlay(List.of(id), message, durationTicks)"),
            "Hotbar 的兼容 showOverlay 必须把普通叠加委托新服务");
        assertTrue(source.contains("actionBarOverlay.clearOverlay(playerId)"),
            "Hotbar 的兼容 clearOverlay 必须委托新服务");
        assertTrue(!source.contains("Map<UUID, OverlayEntry>"),
            "普通叠加状态不得继续由 Hotbar 自己维护");
    }

    @Test
    void 三道具周期渲染从新服务读取普通正文() throws IOException {
        String source = Files.readString(SERVICE);
        int tick = source.indexOf("private void tick()");
        int ready = source.indexOf("private boolean hotbarOverlayReady()", tick);
        assertTrue(tick > 0 && ready > tick, "应能定位完整周期推送路径");
        String body = source.substring(tick, ready);
        assertTrue(body.contains("Component overlay = actionBarOverlay.currentOverlay(id)"),
            "三道具渲染必须从普通 ActionBar 服务读取当前正文");
        assertTrue(body.contains("player.sendActionBar(overlay == null ? Component.empty() : overlay)"),
            "覆盖层未就绪时仍必须发送普通正文而非未声明字形");
    }
}
