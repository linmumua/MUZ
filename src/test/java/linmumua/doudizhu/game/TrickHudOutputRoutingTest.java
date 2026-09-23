package linmumua.doudizhu.game;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

/**
 * 守护出牌 HUD 的 owner/lane 边界。
 *
 * <p>牌桌状态、BossBar 容器和 MiniMessage/资源计算仍由当前桌子的 table/region owner 驱动；
 * 真正触碰玩家客户端的 ActionBar、BossBar 显隐与更新必须经 PlayerOutputDispatcher 投递到
 * player lane。这里用源码契约锁住边界，避免迁移时把实体/资源工作误搬到玩家线程，或重新把
 * Bukkit Player 输出直接放回牌桌 owner。
 */
class TrickHudOutputRoutingTest {
    private static final Path HUD =
        Path.of("src/main/java/linmumua/doudizhu/game/TrickHudService.java");

    @Test
    void 玩家客户端输出统一经PlayerOutputDispatcher() throws IOException {
        String source = Files.readString(HUD);

        assertTrue(source.contains("private final PlayerOutputDispatcher output"),
            "TrickHudService 必须持有玩家输出门面");
        assertTrue(source.contains("output.sendActionBar(viewer.getUniqueId()"),
            "故障提示的 ActionBar 必须走 player lane");
        assertTrue(source.contains("output.showBossBar(viewerId, bar)"),
            "新 BossBar 必须走 player lane 显示");
        assertTrue(source.contains("output.hideBossBar(viewerId, bar)"),
            "单个玩家隐藏 BossBar 必须走 player lane");
        assertTrue(source.contains("output.hideBossBar(entry.getKey(), entry.getValue())"),
            "整桌清理 BossBar 必须按 UUID 走 player lane");
        assertTrue(source.contains("output.runPlayer(viewerId, ignored -> currentBar.name(name))"),
            "BossBar 标题更新也必须在 player lane 执行");

        assertFalse(source.contains("viewer.sendActionBar"),
            "不得从 TrickHudService 直接调用 Player.sendActionBar");
        assertFalse(source.contains("viewer.showBossBar"),
            "不得从 TrickHudService 直接调用 Player.showBossBar");
        assertFalse(source.contains("viewer.hideBossBar"),
            "不得从 TrickHudService 直接调用 Player.hideBossBar");
        assertFalse(source.contains("Bukkit.getPlayer(entry.getKey())"),
            "hideAll 不应先在 table owner 查玩家再直接触碰 BossBar");
    }

    @Test
    void 桌内状态先在owner更新再投递玩家输出() throws IOException {
        String source = Files.readString(HUD);
        int apply = source.indexOf("private void apply(Player viewer, String line)");
        assertTrue(apply >= 0, "apply 方法必须存在");

        int cacheBar = source.indexOf("bars.put(viewerId, bar)", apply);
        int show = source.indexOf("output.showBossBar(viewerId, bar)", apply);
        assertTrue(cacheBar >= 0 && show > cacheBar,
            "BossBar 必须先登记在桌内状态，再投递给玩家；不能让 lane 回调拥有桌内状态");

        int cacheLine = source.indexOf("lastLines.put(viewerId, line)", apply);
        int update = source.indexOf("output.runPlayer(viewerId, ignored -> currentBar.name(name))", apply);
        assertTrue(cacheLine >= 0 && update > cacheLine,
            "牌行去重状态必须留在 table owner，标题更新才投递到 player lane");
    }

    @Test
    void 生产构造复用普通ActionBar共享的玩家输出门面() throws IOException {
        String source = Files.readString(HUD);
        int at = source.indexOf("private static PlayerOutputDispatcher outputDispatcherOf");
        assertTrue(at >= 0, "必须有统一的 dispatcher 装配入口");
        String body = source.substring(at, Math.min(source.length(), at + 420));
        assertTrue(body.contains("actionBar.outputDispatcher()"),
            "牌桌 HUD 应复用插件共享的玩家输出注册表，避免重复的玩家任务生命周期");
    }

    @Test
    void owner输入只消费playerLane不可变快照() throws IOException {
        String source = Files.readString(HUD);

        assertTrue(source.contains("private record PlayerInputSnapshot(boolean online, URL skinUrl, boolean hasCounterItem)"),
            "TrickHudService 必须保存不可变在线/皮肤/记牌器输入快照");
        assertTrue(source.contains("output.runPlayer(playerId, player ->"),
            "玩家输入必须通过 PlayerOutputDispatcher 的 UUID/player lane 采集");
        assertTrue(source.contains("PlayerHeadRenderer.skinUrlOf(player)"),
            "PlayerProfile 只能在 player lane 采集皮肤 URL");
        assertTrue(source.contains("plugin.hasCounterItem(player)"),
            "背包记牌器状态只能在 player lane 采集");
        assertTrue(source.contains("viewerInput.hasCounterItem()"),
            "render 必须消费快照而不是直接读取玩家背包");
        assertTrue(source.contains("input.skinUrl()"),
            "avatarSlot/prewarm 必须消费快照 URL");

        assertFalse(source.contains("Bukkit.getPlayer("),
            "owner lane 不得直接 Bukkit.getPlayer");
        assertFalse(source.contains("getPlayerProfile("),
            "owner lane 不得直接读取 PlayerProfile");
        assertFalse(source.contains("getInventory("),
            "owner lane 不得直接读取玩家背包");
    }

    @Test
    void 头像渲染保留URL兼容入口与原有缓存实现() throws IOException {
        Path renderer = Path.of("src/main/java/linmumua/doudizhu/assets/PlayerHeadRenderer.java");
        String source = Files.readString(renderer);

        assertTrue(source.contains("public static URL skinUrlOf(Player player)"),
            "player lane 必须能把 PlayerProfile 转成 URL 快照");
        assertTrue(source.contains("public String miniMessageFor(\n        URL skinUrl"),
            "头像渲染器必须接受 URL 快照，不能让 owner lane 重新取 PlayerProfile");
        assertTrue(source.contains("private String miniMessageForUrl("),
            "URL 入口必须复用原有缓存与异步下载实现");
    }
}
