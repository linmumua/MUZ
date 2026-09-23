package linmumua.doudizhu;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

/**
 * 守护 /muz reload 与 Debug Web 之间的运行时同步边界。
 *
 * <p>这里用源码扫描而不是起 Bukkit：目标是锁住主类的编排接口，避免把 Web 表单逻辑继续塞进
 * DoudizhuPlugin，也避免 Debug Web 停止时无视 hotbar-hud.enabled 把热键栏 HUD 错误拉起。
 */
class DoudizhuRuntimeSyncTest {
    private static final Path PLUGIN = Path.of("src/main/java/linmumua/doudizhu/DoudizhuPlugin.java");
    private static final Path HOTBAR = Path.of("src/main/java/linmumua/doudizhu/game/HotbarHudService.java");
    private static final Path GAME_TABLE = Path.of("src/main/java/linmumua/doudizhu/game/GameTable.java");

    @Test
    void 默认配置合并变化会触发保存() throws IOException {
        String source = Files.readString(PLUGIN);
        assertTrue(source.contains("private boolean mergeDefaultYamlConfig()"),
            "mergeDefaultYamlConfig 必须返回 changed，否则补默认项不会触发 saveYamlConfig");
        int at = source.indexOf("private void ensureConfigIntegrity()");
        assertTrue(at > 0, "ensureConfigIntegrity 应当存在");
        String body = source.substring(at, at + 700);
        assertTrue(body.contains("changed |= mergeDefaultYamlConfig()"),
            "ensureConfigIntegrity 要把默认配置合并结果纳入 changed，并保留迁移结果");
        assertTrue(source.indexOf("saveYamlConfig()", at) > at,
            "changed=true 时必须保存，否则默认配置只进内存不落盘");
    }

    @Test
    void Hotbar默认配置与资源profile均已退役且保留启动迁移() throws IOException {
        String config = Files.readString(Path.of("src/main/resources/config.yml"));
        String profile = Files.readString(Path.of("muz-resource-profile.yml"));
        String plugin = Files.readString(PLUGIN);
        assertFalse(config.contains("\nhotbar-hud:\n"),
            "默认配置不得继续发布已退役的三道具 Hotbar 段");
        assertTrue(config.contains("\ntable-gadgets:\n")
                && config.contains("  interaction:\n")
                && config.contains("  panel:\n")
                && config.contains("  voices:\n"),
            "新桌内道具、私有语音面板和语音条目必须提供完整默认配置");
        assertFalse(profile.contains("hotbar:\n"),
            "当前资源 profile 不再生成正式 Hotbar 档位");
        assertTrue(plugin.contains("changed |= migrateRetiredHotbarConfig()"),
            "启动完整性流程必须迁移旧 hotbar-hud.interaction 后删除旧段");
        assertTrue(plugin.contains("yamlConfig().set(\"hotbar-hud\", null)"),
            "旧 Hotbar 配置迁移后必须从磁盘配置树删除");
    }

    @Test
    void HUD轻量重载只覆盖正式TrickHud且不启动Hotbar() throws IOException {
        String source = Files.readString(PLUGIN);
        int at = source.indexOf("public void reloadHudRuntimeState()");
        assertTrue(at > 0, "需要给 Web 编辑器和 reload 流程暴露轻量 HUD 重载入口");
        String body = source.substring(at, at + 360);
        assertTrue(body.contains("reloadTrickHudSettings()"),
            "轻量 HUD 重载不能漏掉正式 trick-hud 服务");
        assertFalse(body.contains("syncHotbarHudRuntime"),
            "正式运行期 Hotbar 已退役，HUD 轻量重载不得再同步或启动它");

        String hotbar = Files.readString(HOTBAR);
        assertTrue(hotbar.contains("public void reloadEnabled(boolean configuredEnabled, boolean suspended)"),
            "HotbarHudService 兼容 API 仍可保留，便于旧调用方链接");
    }

    @Test
    void 正式运行期不构造Hotbar且共享普通ActionBar服务() throws IOException {
        String plugin = Files.readString(PLUGIN);
        String table = Files.readString(GAME_TABLE);
        assertFalse(plugin.contains("new HotbarHudService"),
            "主类正式装配不得构造 HotbarHudService");
        assertFalse(plugin.contains("hotbarHudService ="),
            "主类不得保留 HotbarHudService 运行期字段赋值");
        assertTrue(plugin.contains("new ActionBarOverlayService(this)"),
            "主类必须装配共享普通 ActionBar 服务");
        assertTrue(table.contains("plugin.getActionBarOverlayService()"),
            "GameTable 必须从主类获取共享普通 ActionBar 服务");
        assertFalse(table.contains("getHotbarHudService()"),
            "GameTable 正式路由不得再依赖 HotbarHudService");
    }

    @Test
    void 玩家设置保存基于现有树并保留gadgetBar与未知键() throws IOException {
        String source = Files.readString(PLUGIN);
        int at = source.indexOf("private void savePlayerSettings()");
        assertTrue(at > 0, "玩家设置保存入口必须存在");
        String body = source.substring(at, Math.min(source.length(), at + 2400));
        assertTrue(body.contains("new MuzYamlConfig(playerSettingsFile.toPath())"),
            "玩家设置必须从现有 YAML 树加载后合并保存");
        assertTrue(body.contains("clearManagedPlayerSettings(configuration, \"players.\" + rawId)"),
            "保存时只能清理本类负责的已知键");
        assertFalse(body.contains("configuration.set(\"players\", new LinkedHashMap<String, Object>())"),
            "不得重建 players 根节点而丢失未知键");
        assertTrue(body.contains("gadget-bar"),
            "玩家设置保存契约必须明确保留 gadget-bar");
    }

    @Test
    void reload的HUD恢复失败必须异步补发红色反馈() throws IOException {
        String source = Files.readString(PLUGIN);
        assertTrue(source.contains("hudRecovery.whenComplete((result, failure) ->"),
            "/muz reload 必须观察 HUD 恢复 Future，不能 fire-and-forget 后永远报告成功");
        assertTrue(source.contains("feedback.recoveryFailed(\"HUD 四层资源恢复失败：\""),
            "HUD 恢复失败必须通过 ReloadFeedback 补发独立失败反馈");
        assertTrue(source.contains("void recoveryFailed(String detail);"),
            "ReloadFeedback 必须提供 HUD 恢复失败出口");
        assertTrue(source.contains("NamedTextColor.RED"),
            "HUD 恢复失败反馈必须使用红色文案");
    }

    @Test
    void DebugWeb生命周期不再接管Hotbar运行期服务() throws IOException {
        String source = Files.readString(PLUGIN);
        int at = source.indexOf("private void syncDebugWebServerRuntime()");
        assertTrue(at > 0, "reloadVisualState 应抽出 Debug Web 生命周期同步方法");
        String body = source.substring(at, Math.min(source.length(), at + 1500));
        assertTrue(body.contains("if (!debugWebServer.isRunning())"),
            "上次启动失败后实例仍非 null，下一次 reload 必须能重试 start");
        assertTrue(body.contains("createDebugWebServer()"),
            "Debug Web 构造应集中到一个小工厂，主类只保留最小编排");
        assertFalse(body.contains("syncHotbarHudRuntime"),
            "Debug Web 生命周期不得再同步已退役的 Hotbar 运行期服务");
        assertFalse(body.contains("suspendHotbarHudForDebugWeb")
                || body.contains("resumeHotbarHudAfterDebugWeb"),
            "Debug Web 不得通过回调接管已退役的 Hotbar 服务");
    }

    @Test
    void Hotbar兼容类仍可编译但不承担正式主链() throws IOException {
        String plugin = Files.readString(PLUGIN);
        String hotbar = Files.readString(HOTBAR);
        assertTrue(hotbar.contains("public final class HotbarHudService"),
            "兼容 HotbarHudService 类仍需保留");
        assertTrue(plugin.contains("public HotbarHudService getHotbarHudService()"),
            "旧 getter 仍需保留以兼容链接");
        assertTrue(plugin.contains("return null;"),
            "正式主类不得返回运行期 Hotbar 实例");
        assertFalse(plugin.contains("new HotbarHudService"),
            "主类不得构造 HotbarHudService");
    }

    @Test
    void PLAYING阶段所有ActionBar路由都经过独立叠加服务() throws IOException {
        String source = Files.readString(GAME_TABLE);
        int broadcastAt = source.indexOf("private void broadcast(Component message)");
        int actionAt = source.indexOf("private void broadcastActionBar(Component message)", broadcastAt);
        int persistentAt = source.indexOf("private void broadcastPersistentActionBar(int remainingSeconds)");
        assertTrue(broadcastAt >= 0 && actionAt > broadcastAt && persistentAt > actionAt,
            "GameTable 的三条 ActionBar 路由必须可定位");

        String broadcast = source.substring(broadcastAt, actionAt);
        String action = source.substring(actionAt, persistentAt);
        String persistent = source.substring(persistentAt, source.indexOf("    /**", persistentAt));
        for (String route : new String[] {broadcast, action, persistent}) {
            assertTrue(route.contains("dispatchActionBar"),
                "ActionBar 必须交给统一阶段分流助手，不能绕回裸 sendActionBar");
        }
        // 机制变更（非弱化）：离线排除已从 GameTable 内部的 onlinePlayer(UUID) 访问器迁到
        // player lane 门面。GameTable 现在只把 UUID 交给叠加服务，离线判断由 PlayerOutputDispatcher
        // 负责，因此断言改为：GameTable 必须委托叠加服务，且门面里确实存在在线门禁。
        assertTrue(source.contains("private void dispatchActionBar(UUID playerId, Component message, int durationTicks)"),
            "GameTable 必须集中提供 ActionBar 分流助手");
        assertTrue(source.contains("actionBarOverlay.showOverlay(playerId, message, durationTicks);"),
            "GameTable 的 ActionBar 必须委托叠加服务，不能绕回裸 sendActionBar");
        String dispatcher = Files.readString(Path.of(
            "src/main/java/linmumua/doudizhu/game/PlayerOutputDispatcher.java"));
        assertTrue(dispatcher.contains("player == null || !player.isOnline()"),
            "player lane 门面必须显式排除离线玩家");
    }

    @Test
    void 普通ActionBar路由与回大厅清屏不依赖Hotbar() throws IOException {
        String gameTable = Files.readString(GAME_TABLE);
        int broadcastAt = gameTable.indexOf("private void broadcast(Component message)");
        int actionAt = gameTable.indexOf("private void broadcastActionBar(Component message)", broadcastAt);
        int persistentAt = gameTable.indexOf("private void broadcastPersistentActionBar(int remainingSeconds)");
        assertTrue(broadcastAt >= 0 && actionAt > broadcastAt && persistentAt > actionAt,
            "GameTable 的三条 ActionBar 路由必须可定位");
        String broadcast = gameTable.substring(broadcastAt, actionAt);
        String action = gameTable.substring(actionAt, persistentAt);
        String persistent = gameTable.substring(persistentAt, gameTable.indexOf("    /**", persistentAt));
        for (String route : new String[] {broadcast, action, persistent}) {
            assertTrue(route.contains("dispatchActionBar"),
                "ActionBar 必须交给统一普通服务路由，不能绕回 Hotbar");
        }
        int resetAt = gameTable.indexOf("private void resetRound()");
        int resetEnd = gameTable.indexOf("private void detachAllSeatsForForceClose", resetAt);
        String resetBody = gameTable.substring(resetAt, resetEnd);
        assertTrue(resetBody.contains("actionBarOverlay.clearTable(this)"),
            "回大厅时必须主动清除最后一帧 ActionBar");
        assertTrue(resetBody.indexOf("actionBarOverlay.clearTable(this)") < resetBody.indexOf("resetRoundStateForLobby()"),
            "必须在切回 LOBBY 前清屏，避免客户端继续显示上一帧");
        assertFalse(gameTable.contains("getHotbarHudService()"),
            "GameTable 不得通过兼容 getter 重新接入 Hotbar");
    }

    @Test
    void DebugWeb生命周期支持端口变化失败重试且不接管Hotbar() throws IOException {
        String source = Files.readString(PLUGIN);
        int at = source.indexOf("private void syncDebugWebServerRuntime()");
        assertTrue(at > 0, "reloadVisualState 应抽出 Debug Web 生命周期同步方法");
        String body = source.substring(at, Math.min(source.length(), at + 1500));
        assertTrue(body.contains("debugWebServer.isRunning() && debugWebServer.getPort() != configuredPort"),
            "debug.web-ui.port 改变时必须停旧端口并按新端口重启");
        assertTrue(body.contains("if (!debugWebServer.isRunning())"),
            "上次启动失败后实例仍非 null，下一次 reload 必须能重试 start");
        assertTrue(body.contains("createDebugWebServer()"),
            "Debug Web 构造应集中到一个小工厂，主类只保留最小编排");
        assertFalse(body.contains("suspendHotbarHudForDebugWeb")
                || body.contains("resumeHotbarHudAfterDebugWeb"),
            "Debug Web onStart/onStop 不得接管已退役的 Hotbar");
    }

    @Test
    void Web编辑器只拿最小回调接口不承载表单逻辑() throws IOException {
        String source = Files.readString(PLUGIN);
        assertTrue(source.contains("public void reloadVisualStateFromWebEditor()"),
            "Web 编辑器需要最小公开回调接口，避免请求线程直接拼主类内部流程");
        int at = source.indexOf("public void reloadVisualStateFromWebEditor()");
        String body = source.substring(at, at + 260);
        assertTrue(body.contains("reloadVisualState(false, ReloadFeedback.silent())"),
            "Web 编辑器回调只应复用现有轻量 reload，不把表单逻辑堆进主类");
    }

    @Test
    void 启动恢复与CraftEngine启用事件共用独立恢复服务() throws IOException {
        String plugin = Files.readString(PLUGIN);
        String lifecycle = Files.readString(Path.of(
            "src/main/java/linmumua/doudizhu/listener/CraftEngineLifecycleListener.java"));
        assertTrue(plugin.contains("hudResourceRecoveryService = new HudResourceRecoveryService(this, hudWebApplyCoordinator)"),
            "插件启动必须装配独立 HUD 资源恢复服务");
        assertTrue(plugin.contains("hudResourceRecoveryService.reloadFromDisk(\"manual-reload\")"),
            "/muz reload 必须从磁盘恢复四层 request，而不是只重读普通 HUD 设置");
        assertTrue(lifecycle.contains("plugin.getHudResourceRecoveryService() != null"),
            "CraftEngine enable 事件必须防护恢复服务为空");
        assertTrue(lifecycle.contains("onCraftEngineEnabled(\"craftengine-enable\")"),
            "CraftEngine enable 必须触发完整四层恢复");
        assertFalse(lifecycle.contains("reloadFromDiskForWeb()"),
            "生命周期监听器不得绕过 coordinator 直接读 Web 配置");
    }

    @Test
    void 四层运行态应用只在coordinator验证后调用() throws IOException {
        String coordinator = Files.readString(Path.of(
            "src/main/java/linmumua/doudizhu/debug/HudWebApplyCoordinator.java"));
        int resource = coordinator.indexOf("private CompletableFuture<ApplyResult> resourceAndApply");
        int apply = coordinator.indexOf("private CompletableFuture<ApplyResult> applyOnMain");
        assertTrue(resource >= 0 && apply > resource, "必须存在资源同步与最终运行态应用两个阶段");
        String body = coordinator.substring(resource, apply);
        assertTrue(body.contains("plugin.getHudOverlayRuntimeState().clear()"),
            "CE reload/generate/ZIP 校验前必须清除旧 ready");
        assertTrue(body.contains("plugin.getHudOverlayRuntimeState().clear()"),
            "四层资源未验证前必须清除统一 HUD ready 快照");
        String applyBody = coordinator.substring(apply);
        assertTrue(applyBody.contains("markVerified(request)"),
            "只有最终应用阶段才能原子发布完整 request 和对应 ready 快照");
        assertTrue(body.indexOf("plugin.getHudOverlayRuntimeState().clear()")
                < body.indexOf("reloadGenerateAndVerify"),
            "真实资源校验前必须先清除旧 ready，不能独立沿用旧快照");
        assertTrue(applyBody.contains("runIfActiveAtomically"),
            "close/timeout 与最终 apply 必须通过原子闸门仲裁");
        assertTrue(applyBody.contains("applyHudRuntimeStateFromWeb()"),
            "资源校验成功后必须应用当前完整 HUD 运行态");
    }

    /** 源码契约断言不应被注释里的示例或历史说明误命中。 */
    private static String stripComments(String source) {
        return source
            .replaceAll("(?s)/\\*.*?\\*/", "")
            .replaceAll("(?m)//.*$", "");
    }
}
