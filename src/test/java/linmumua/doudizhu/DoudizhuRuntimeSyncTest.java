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
    void Hotbar默认配置与当前资源profile一致() throws IOException {
        String config = Files.readString(Path.of("src/main/resources/config.yml"));
        String profile = Files.readString(Path.of("muz-resource-profile.yml"));
        assertTrue(config.contains("hotbar-hud:\n")
                && config.contains("  enabled: false\n")
                && config.contains("  scale: 100\n")
                && config.contains("  offset-x: 0\n")
                && config.contains("  offset-y: 0\n"),
            "Hotbar 默认配置必须保持关闭、100% 档、零位移，避免默认启动时误推送或选到未生成档位");
        assertTrue(profile.contains("hotbar:\n") && profile.contains("  scale: 100\n"),
            "默认资源 profile 必须生成与 config.yml 对齐的 100% Hotbar 档位");
        assertTrue(config.contains("plugins/CraftEngine/resources/muz/configuration/images/hotbar_debug.yml"),
            "offset-y 注释必须指向实际 Hotbar 调试覆盖层路径");
        assertFalse(config.contains("plugins/CraftEngine/resources/muz_hotbar_debug/"),
            "配置说明不得继续引用已不存在的旧 Hotbar 覆盖层目录");
    }

    @Test
    void HUD轻量重载同时覆盖TrickHud和Hotbar运行态() throws IOException {
        String source = Files.readString(PLUGIN);
        int at = source.indexOf("public void reloadHudRuntimeState()");
        assertTrue(at > 0, "需要给 Web 编辑器和 reload 流程暴露轻量 HUD 重载入口");
        String body = source.substring(at, at + 360);
        assertTrue(body.contains("reloadTrickHudSettings()"),
            "轻量 HUD 重载不能漏掉正式 trick-hud 服务");
        assertTrue(body.contains("syncHotbarHudRuntime(isDebugWebServerRunning())"),
            "轻量 HUD 重载要按 Debug Web 占用状态同步 Hotbar，而不是无条件 start");

        String hotbar = Files.readString(HOTBAR);
        assertTrue(hotbar.contains("public void reloadEnabled(boolean configuredEnabled, boolean suspended)"),
            "HotbarHudService 需要一个按配置开关与外部接管状态同步的轻量入口");
    }

    @Test
    void DebugWeb接管时Hotbar仍继续推送() throws IOException {
        // 【这条守的是一个已经踩过的坑】：原先 suspended=true（Debug Web 面板开着）会直接
        // stop()，理由是「避免和 Web 页面争抢底部物品栏」。但那让调试闭环断掉了 ——
        // 在面板上拖 hotbar 位置时游戏内根本没有底图在推送，拖了也看不到任何变化。
        // 现在 suspended 的含义是「Web 接管定位参数」：周期任务照常，只把字形切到
        // 可拖动 ascent 的那个码位。实际接收者仍必须是 PLAYING 牌桌中的真人座位。
        String hotbar = Files.readString(HOTBAR);
        int at = hotbar.indexOf("public void reloadEnabled(boolean configuredEnabled, boolean suspended)");
        assertTrue(at > 0, "reloadEnabled 应当存在");
        String body = hotbar.substring(at, Math.min(hotbar.length(), at + 500));
        assertTrue(body.contains("if (configuredEnabled) {"),
            "启停只能由 configuredEnabled 决定；把 suspended 也纳入判断会让面板一开就停推送");
        assertTrue(body.contains("this.useDebugOverlayGlyph = suspended && overlayReady;"),
            "suspended 应当只在覆盖层已验证就绪时切换字形来源，不再用于停推送");
        assertTrue(body.contains("overlayReadyScale != scale"),
            "Debug Web 覆盖层必须绑定当前 hotbar scale，不能跨档发送未声明码位");
        assertTrue(hotbar.contains("hotbarHudDebugGlyphText(scale)"),
            "接管状态下必须改用当前缩放档的覆盖层字形，否则拖动 offset-y 在游戏内没有任何效果");
        assertTrue(hotbar.contains("private int overlayReadyScale = -1"),
            "overlay 就绪状态必须记录具体 scale，不能用全局布尔值跨档复用");
        assertTrue(hotbar.contains("overlayReadyScale == scale"),
            "发送调试字形前必须确认 overlay scale 与当前 Hotbar scale 一致");
    }

    @Test
    void Hotbar只在PLAYING真人座位显示() throws IOException {
        String hotbar = Files.readString(HOTBAR);
        int at = hotbar.indexOf("private void tick()");
        assertTrue(at > 0, "Hotbar 周期推送入口应当存在");
        int end = hotbar.indexOf("private boolean isPlayingPlayer", at);
        assertTrue(end > at, "tick 后应保留单玩家 PLAYING 判断入口");
        String body = stripComments(hotbar.substring(at, end));

        int tableLoop = body.indexOf("for (GameTable table : tableManager.getTables())");
        int phaseGate = body.indexOf("if (table.getPhase() != GamePhase.PLAYING)", tableLoop);
        int seatLoop = body.indexOf("for (UUID id : table.getSeats())", phaseGate);
        int botGate = body.indexOf("table.isBot(id)", seatLoop);
        int playerLookup = body.indexOf("Bukkit.getPlayer(id)", botGate);
        int receiverAdd = body.indexOf("currentPlayers.add(id)", playerLookup);
        int customSend = body.indexOf("player.sendActionBar(buildActionBar(entry))", receiverAdd);

        assertTrue(tableLoop >= 0,
            "接收者必须从实际牌桌集合筛选，不能遍历全服在线玩家");
        assertTrue(phaseGate > tableLoop,
            "每张牌桌必须先通过 GamePhase.PLAYING 阶段门，不能只在别处留下无效判断");
        assertTrue(seatLoop > phaseGate,
            "必须在 PLAYING 判断之后才遍历该桌座位，否则非出牌阶段也可能进入推送路径");
        assertTrue(botGate > seatLoop,
            "每个座位必须先排除 table.isBot(id)，机器人不能进入 Bukkit ActionBar 推送路径");
        assertTrue(playerLookup > botGate,
            "必须排除机器人后才查 Bukkit Player，不能把机器人 UUID 当真人接收者");
        assertTrue(receiverAdd > playerLookup && customSend > receiverAdd,
            "只有通过 PLAYING、真人座位、在线玩家三道门后，才允许加入接收集合并发送自定义 Hotbar");
        assertTrue(body.indexOf("!player.isOnline()", playerLookup) > playerLookup
                && body.indexOf("!player.isOnline()", playerLookup) < receiverAdd,
            "Bukkit Player 查找后必须显式确认 isOnline，不能把离线/失效对象当作 Hotbar 接收者");
        assertFalse(body.contains("Bukkit.getOnlinePlayers()"),
            "不能退回给所有在线玩家推送，否则非牌桌玩家也会被替换物品栏");

        int predicateAt = hotbar.indexOf("private boolean isPlayingPlayer(Player player)");
        assertTrue(predicateAt > 0, "showOverlay 依赖的单玩家阶段判断必须存在");
        String predicate = stripComments(hotbar.substring(predicateAt, hotbar.indexOf("public void clearOverlay", predicateAt)));
        assertTrue(predicate.contains("table.getPhase() == GamePhase.PLAYING"),
            "单玩家判断也必须锁定 PLAYING，不能让 BIDDING/DOUBLING/LOBBY 排入 overlay 队列");
        assertTrue(predicate.contains("!player.isOnline()"),
            "单玩家判断必须显式确认在线，不能只靠 Bukkit.getPlayer 的偶然空值兜底");
        assertTrue(predicate.contains("!table.isBot(player.getUniqueId())"),
            "单玩家判断必须排除机器人座位，不能只靠 Bukkit.getPlayer 的偶然空值兜底");
    }

    @Test
    void PLAYING阶段所有ActionBar路由都经过Hotbar服务() throws IOException {
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
            assertTrue(route.contains("phase == GamePhase.PLAYING")
                    && route.contains("hotbarHud.showOverlay"),
                "PLAYING 阶段的 ActionBar 必须交给 HotbarHudService，不能绕回裸 sendActionBar");
        }
        int onlineAt = source.indexOf("private Player onlinePlayer(UUID playerId)");
        assertTrue(onlineAt >= 0, "GameTable 必须集中提供在线玩家查询");
        String online = source.substring(onlineAt, source.indexOf("    private void playSoundAll", onlineAt));
        assertTrue(online.contains("player != null && player.isOnline()"),
            "GameTable 的 ActionBar 路由必须显式排除离线玩家");
    }

    @Test
    void 非PLAYING提示走普通ActionBar且退出阶段立即清屏() throws IOException {
        String hotbar = Files.readString(HOTBAR);
        int showAt = hotbar.indexOf("public void showOverlay(Collection<UUID> players");
        int tickAt = hotbar.indexOf("private void tick()", showAt);
        assertTrue(showAt > 0 && tickAt > showAt, "应能完整定位 showOverlay(Collection...) 方法");
        String showBody = stripComments(hotbar.substring(showAt, tickAt));
        int playingCheck = showBody.indexOf("boolean playing = isPlayingPlayer(player)");
        int plainBranch = showBody.indexOf("if (!enabled || !offsetService.isAvailable() || !playing)", playingCheck);
        int plainSend = showBody.indexOf("player.sendActionBar(plain)", plainBranch);
        int branchContinue = showBody.indexOf("continue;", plainSend);
        int overlayPut = showBody.indexOf("overlays.put(id", branchContinue);

        assertTrue(playingCheck >= 0,
            "消息合成前必须逐玩家确认 PLAYING 阶段");
        assertTrue(plainBranch > playingCheck,
            "非 PLAYING、未启用或 CE 不可用时都必须进入普通 ActionBar 分支");
        assertTrue(plainSend > plainBranch,
            "非 PLAYING 分支必须直接 player.sendActionBar(plain)，不能只清队列后吞掉提示");
        assertTrue(branchContinue > plainSend && overlayPut > branchContinue,
            "直接发送普通 ActionBar 后必须 continue，非 PLAYING 提示绝不能继续写入 overlay 队列");
        int overlayRemove = showBody.indexOf("overlays.remove(id)", plainBranch);
        int renderedRemove = showBody.indexOf("renderedPlayers.remove(id)", plainBranch);
        assertTrue(overlayRemove >= plainBranch && overlayRemove < plainSend,
            "走普通 ActionBar 前要移除旧 overlay，避免回到 PLAYING 后重放过期阶段提示");
        assertTrue(renderedRemove >= plainBranch && renderedRemove < plainSend,
            "普通 ActionBar 已替换最后一帧字形时要移出 renderedPlayers，避免 tick 紧接着发空消息清掉提示");

        String gameTable = Files.readString(GAME_TABLE);
        int resetAt = gameTable.indexOf("private void resetRound()");
        int resetEnd = gameTable.indexOf("private void detachAllSeatsForForceClose", resetAt);
        String resetBody = gameTable.substring(resetAt, resetEnd);
        assertTrue(resetBody.contains("hotbarHud.clearTable(this)"),
            "回大厅时必须主动清除最后一帧自定义物品栏");
        assertTrue(resetBody.indexOf("hotbarHud.clearTable(this)") < resetBody.indexOf("resetRoundStateForLobby()"),
            "必须在切回 LOBBY 前清屏，避免客户端继续显示上一帧");
        assertTrue(hotbar.contains("player.sendActionBar(Component.empty())"),
            "退出 PLAYING 或关闭服务时必须发送空 ActionBar 恢复原版物品栏");
    }

    @Test
    void DebugWeb生命周期支持端口变化失败重试且回调不无条件启动Hotbar() throws IOException {
        String source = Files.readString(PLUGIN);
        int at = source.indexOf("private void syncDebugWebServerRuntime()");
        assertTrue(at > 0, "reloadVisualState 应抽出 Debug Web 生命周期同步方法");
        String body = source.substring(at, at + 1500);
        assertTrue(body.contains("debugWebServer.isRunning() && debugWebServer.getPort() != configuredPort"),
            "debug.web-ui.port 改变时必须停旧端口并按新端口重启");
        assertTrue(body.contains("if (!debugWebServer.isRunning())"),
            "上次启动失败后实例仍非 null，下一次 reload 必须能重试 start");
        assertTrue(body.contains("createDebugWebServer()"),
            "Debug Web 构造应集中到一个小工厂，主类只保留最小编排");
        assertTrue(body.contains("this::suspendHotbarHudForDebugWeb")
                && body.contains("this::resumeHotbarHudAfterDebugWeb"),
            "Debug Web onStart/onStop 回调应走受控方法，不能直接 hotbarHudService::start");
        assertTrue(source.contains("hotbarHudService.reloadEnabled(hotbarHudEnabled, debugWebOverride)"),
            "恢复 Hotbar 时必须重新读取 hotbar-hud.enabled，避免 Debug Web stop 无条件拉起");
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

    /** 源码契约断言不应被注释里的示例或历史说明误命中。 */
    private static String stripComments(String source) {
        return source
            .replaceAll("(?s)/\\*.*?\\*/", "")
            .replaceAll("(?m)//.*$", "");
    }
}
