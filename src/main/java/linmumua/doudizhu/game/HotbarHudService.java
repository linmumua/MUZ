package linmumua.doudizhu.game;

import linmumua.doudizhu.DoudizhuPlugin;
import linmumua.doudizhu.assets.PackAssets;
import linmumua.doudizhu.compat.CraftEngineOffsetService;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.TextDecoration;
import net.kyori.adventure.text.minimessage.MiniMessage;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.scheduler.BukkitTask;

import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * 底部物品栏 HUD 服务：每 2 格刻只向处于 {@link GamePhase#PLAYING} 的真人玩家发送
 * ActionBar，通过字形负 ascent 把鸡蛋、水桶、番茄三个独立图标渲染在原版物品栏上方。
 *
 * <p>资源包【不再】透明覆盖原版 {@code hotbar.png} / {@code hotbar_selection.png}：
 * 那两张贴图一旦进资源包就是客户端全局状态，无法按牌桌阶段切换，会导致没打牌的玩家
 * 也只剩悬空物品。现在只有正式出牌阶段持续推送自定义三道具字形；等待、叫地主、加倍、
 * 结算、离桌及普通游玩时都不推送，原版 9 槽物品栏保持可见。
 *
 * <p>消息叠加机制：出牌阶段消息（倍率、剩余秒数等）作为 ActionBar 正文渲染在
 * 槽位图上方，与槽位背景字形纵向错开（正文在 baseline 附近，槽位字形在负 ascent
 * 位置），两层视觉上不冲突。其它阶段仍走普通 ActionBar，不会因为 HUD 开关而丢提示。
 *
 * <p>CraftEngine 不可用时自动降级：不渲染槽位背景，叠加消息直接走普通 sendActionBar，
 * 对局功能不受影响。
 */
public final class HotbarHudService {
    private static final MiniMessage MINI = MiniMessage.miniMessage();

    /**
     * 叠加消息的有效期，单位毫秒。
     *
     * <p>ActionBar 原生约3秒淡出；把内部过期时间设略短，到期后下一个 tick
     * 不再叠加，让背景层静默继续，而不是等 ActionBar 超时变空。
     */
    private static final long DEFAULT_OVERLAY_DURATION_MS = 2_800L;

    private final DoudizhuPlugin plugin;
    private final CraftEngineOffsetService offsetService;

    /**
     * 每位玩家的叠加消息条目，过期后在 tick 里惰性清理。
     *
     * <p>只在主线程访问（BukkitScheduler 保证），无需并发容器。
     */
    private final Map<UUID, OverlayEntry> overlays = new HashMap<>();

    /** 周期任务引用，{@code null} 表示尚未启动或已停止。 */
    private BukkitTask task;

    /**
     * 上一轮 tick 实际收到过自定义三道具 HUD 的玩家。
     *
     * <p>离开 {@link GamePhase#PLAYING} 后必须主动发一次空 ActionBar；否则客户端会让
     * 最后一帧字形继续停留到原生淡出结束，看起来像「结算/回大厅后还替换了几秒」。
     */
    private final Set<UUID> renderedPlayers = new HashSet<>();

    /** 是否启用（由 {@code config.yml} 里的 {@code hotbar-hud.enabled} 控制）。 */
    private boolean enabled;

    /**
     * 槽位底图的水平偏移像素（{@code hotbar-hud.offset-x}），正右负左。
     *
     * <p>走 CraftEngine 负空格实现，所以运行期改完立刻生效，不需要重新生成资源包。
     * 与之相对的垂直偏移（{@code hotbar-hud.offset-y}）必须落在字形 ascent 上，
     * 属于资源包内容，不在这里处理。
     */
    private int offsetX;

    /**
     * 是否使用「可拖动 ascent」的三道具调试覆盖层，而不是 bundle 内的固定 ascent。
     * 图标码位取自 PackAssets，避免沿用已退役的整幅底板 EF00/EF01。
     *
     * <p>由 Debug Web 的接管状态驱动（见 {@link #reloadEnabled}）。对应码位同属
     * {@code minecraft:muz_hotbar} 字体、共用三张独立贴图，差别只在 ascent 来自哪里：
     * 基础声明烘焙在构建产物里，覆盖声明由 {@code HotbarDebugOverlayWriter} 运行期写出。
     */
    private boolean useDebugOverlayGlyph;

    /** 当前使用的构建期 hotbar 缩放档（百分比），只接受当前 profile/PackTiers 已生成的档位。 */
    private int scale = 100;

    /**
     * 当前 offset-y 覆盖层是否已由 CE 重载、实际 ZIP 校验并在主线程应用。
     * 未就绪时即使 Debug Web 正在接管，也必须退回 bundle 固定码位，避免客户端豆腐块。
     */
    private boolean overlayReady;

    /**
     * 已通过资源重载与 ZIP 校验的 overlay 所属 scale；-1 表示没有可用覆盖层。
     *
     * <p>覆盖层只为本次应用的 hotbar scale 生成。不能只记一个全局 ready 布尔值，
     * 否则从 100% 切到 75% 后会误把 100% 的 EF01/EF03 声明套到 75% 字形上，
     * 客户端收到未声明码位就会显示豆腐块。
     */
    private int overlayReadyScale = -1;

    /**
     * 当前正在合成的虚拟道具下标（0..2），供 {@link #buildActionBar(OverlayEntry)} 叠加选中框。
     *
     * <p>【为什么用字段而不是给 buildActionBar 加参数】：{@code DoudizhuRuntimeSyncTest}
     * 按文本锁死了 {@code player.sendActionBar(buildActionBar(entry))} 这一行调用形态，
     * 扩参数会让那条守护断言失效。选中框定位是纯渲染态，只在主线程 {@link #tick()} 内
     * 「读取 selectedIndex → 立刻调用 buildActionBar」这一步使用，读写都在主线程，无并发问题。
     */
    private int pendingHeldSlot;

    public HotbarHudService(DoudizhuPlugin plugin, CraftEngineOffsetService offsetService) {
        this.plugin = plugin;
        this.offsetService = offsetService;
    }

    /** 设置启用开关（在 onEnable / reload 时由插件主类调用）。 */
    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public boolean isEnabled() {
        return enabled;
    }

    /**
     * 按配置开关与外部接管状态同步任务。
     *
     * <h2>{@code suspended} 的语义已改：接管定位参数，不再停推送</h2>
     *
     * <p>原先 {@code suspended=true}（Debug Web 面板开着）会直接 {@link #stop()}，
     * 理由是「避免和 Web 页面争抢底部物品栏」。但那让调试闭环断掉了：面板上拖动
     * hotbar 位置时游戏内根本没有底图在推送，玩家看不到任何变化，拖了也无法判断对不对。
     *
     * <p>现在 {@code suspended=true} 表示「**Debug Web 正在接管定位参数**」：
     * 推送照常进行，只是把字形从 bundle 内的固定 ascent 码位切到调试覆盖层那个
     * 可拖动 ascent 的码位，于是面板上的调整能在游戏里直接看到。
     * 周期任务是否运行仍只由 {@code configuredEnabled} 决定；实际接收者还必须通过
     * {@link GamePhase#PLAYING} 与真人座位筛选，Debug Web 不得绕过这道阶段门。
     *
     * @param configuredEnabled {@code hotbar-hud.enabled} 当前值，决定周期任务是否运行
     * @param suspended         Debug Web 正在接管定位参数时为 true；只切换字形来源，不停推送
     */
    public void reloadEnabled(boolean configuredEnabled, boolean suspended) {
        this.enabled = configuredEnabled;
        this.useDebugOverlayGlyph = suspended && overlayReady;
        if (this.useDebugOverlayGlyph && overlayReadyScale != scale) {
            // overlay 只对应生成并校验过的那一档；切换 scale 后必须先重生成资源包。
            this.useDebugOverlayGlyph = false;
        }
        if (configuredEnabled) {
            start();
        } else {
            stop();
        }
    }

    /**
     * 注入槽位底图的水平偏移（{@code hotbar-hud.offset-x}）。
     *
     * <p>【为什么单独开一个 setter 而不是加进 {@link #reloadEnabled} 的参数】：
     * {@code reloadEnabled(boolean, boolean)} 的签名被 {@code DoudizhuRuntimeSyncTest}
     * 按文本锁死（它扫源码断言这一行存在），扩参数会让那条守护断言失效。偏移是与
     * 「要不要推送」无关的独立维度，分开注入本身也更清楚。
     */
    public void setOffsetX(int offsetX) {
        this.offsetX = offsetX;
    }

    /** 设置构建期 hotbar 缩放档；非法值拒绝切换并保留当前档位。 */
    public void setScale(int scale) {
        if (PackAssets.hotbarScaleTierOf(scale) < 0) {
            plugin.getLogger().warning("hotbar-hud.scale=" + scale
                + " 不是当前资源包已生成的档位（可用："
                + java.util.Arrays.toString(PackAssets.HOTBAR_SCALE_TIERS)
                + "），已拒绝切换并继续使用 " + this.scale
                + "；如需该档位，请重新生成资源包。" );
            return;
        }
        this.scale = scale;
        if (overlayReadyScale != scale) {
            // 旧 scale 的 overlay 不能跨档复用；bundle 固定码位仍可安全显示。
            this.useDebugOverlayGlyph = false;
        }
    }

    /**
     * 设置运行期覆盖层就绪状态。只有资源协调器完成真实 CE Future 与 ZIP 校验后才能传 true。
     * 兼容旧调用点：未显式提供 scale 时按当前档记录，但新资源流程应使用带 scale 的重载。
     */
    public void setOverlayReady(boolean ready) {
        setOverlayReady(ready, ready ? scale : -1);
    }

    /**
     * 设置指定 hotbar scale 的运行期覆盖层就绪状态。
     *
     * <p>ready=true 只允许记录已通过校验的合法 scale；其它值一律拒绝并保持 bundle 码位，
     * 防止发送当前资源包没有声明的 EF01/EF03 变体。
     */
    public void setOverlayReady(boolean ready, int readyScale) {
        if (!ready) {
            this.overlayReady = false;
            this.overlayReadyScale = -1;
            this.useDebugOverlayGlyph = false;
            return;
        }
        if (PackAssets.hotbarScaleTierOf(readyScale) < 0) {
            plugin.getLogger().warning("hotbar 调试覆盖层 scale=" + readyScale
                + " 未在资源包中生成，已拒绝标记为就绪；请重新生成资源包。" );
            this.overlayReady = false;
            this.overlayReadyScale = -1;
            this.useDebugOverlayGlyph = false;
            return;
        }
        this.overlayReady = true;
        this.overlayReadyScale = readyScale;
        if (readyScale != scale) {
            this.useDebugOverlayGlyph = false;
        }
    }

    public boolean isOverlayReady() {
        return overlayReady;
    }

    /** 周期任务是否正在运行。 */
    public boolean isRunning() {
        return task != null;
    }

    /**
     * 启动周期任务（每 2 格刻刷一次 ActionBar）。
     *
     * <p>重复调用无效，已启动时直接返回。
     */
    public void start() {
        if (task != null) {
            return;
        }
        task = plugin.getServer().getScheduler().runTaskTimer(plugin, this::tick, 2L, 2L);
    }

    /**
     * 停止周期任务并清空叠加消息表。
     *
     * <p>在 {@code onDisable} 里调用，确保资源干净释放。
     */
    public void stop() {
        if (task != null) {
            task.cancel();
            task = null;
        }
        clearRenderedPlayers();
        overlays.clear();
    }

    /**
     * 向指定玩家集合设置叠加消息，显示在槽位背景图上方的 ActionBar 文字区域。
     *
     * <p>若服务未启用（{@link #isEnabled()} 为 {@code false}），则退化为直接
     * {@code sendActionBar}，对局消息不丢失。
     *
     * @param players       要接收消息的玩家 UUID 集合
     * @param message       消息 Component（调用方不必手动去斜体）
     * @param durationTicks 消息持续格刻数；一般传 60（3 秒）
     */
    public void showOverlay(Collection<UUID> players, Component message, int durationTicks) {
        Component plain = message.decoration(TextDecoration.ITALIC, false);
        long expireAt = System.currentTimeMillis() + (long) durationTicks * 50L;
        for (UUID id : players) {
            Player player = Bukkit.getPlayer(id);
            if (player == null || !player.isOnline()) {
                overlays.remove(id);
                renderedPlayers.remove(id);
                continue;
            }

            // 只有正式出牌阶段才把消息和自定义三道具底图合成；叫地主、加倍、结算
            // 等阶段仍然必须显示普通 ActionBar，不能因为全局开关开启就把提示吞掉。
            boolean playing = isPlayingPlayer(player);
            if (!enabled || !offsetService.isAvailable() || !playing) {
                overlays.remove(id);
                // 普通 ActionBar 会替换客户端上一帧字形；同时移出集合，避免下一轮 tick
                // 再发一个空 ActionBar 把这条阶段提示清掉。
                renderedPlayers.remove(id);
                player.sendActionBar(plain);
                continue;
            }
            overlays.put(id, new OverlayEntry(message, expireAt));
        }
    }

    /**
     * 向指定玩家集合设置叠加消息，默认持续 60 格刻（3 秒）。
     */
    public void showOverlay(Collection<UUID> players, Component message) {
        showOverlay(players, message, 60);
    }

    /**
     * 向单个玩家设置叠加消息，默认持续 60 格刻。
     */
    public void showOverlay(UUID playerId, Component message) {
        showOverlay(List.of(playerId), message, 60);
    }

    /**
     * 向单个玩家设置叠加消息，指定持续格刻。
     */
    public void showOverlay(UUID playerId, Component message, int durationTicks) {
        showOverlay(List.of(playerId), message, durationTicks);
    }

    // ─────────────────────────────────────────────────────────────────────────

    private void tick() {
        if (!enabled || !offsetService.isAvailable()) {
            clearRenderedPlayers();
            overlays.clear();
            return;
        }

        long now = System.currentTimeMillis();
        Set<UUID> currentPlayers = new HashSet<>();
        TableManager tableManager = plugin.getTableManager();
        if (tableManager != null) {
            // 从牌桌状态出发，而不是扫描全服在线玩家：只有正式出牌阶段的真人座位
            // 才能收到自定义三道具字形，机器人 UUID 没有 Bukkit Player，自然不会发送。
            for (GameTable table : tableManager.getTables()) {
                if (table.getPhase() != GamePhase.PLAYING) {
                    continue;
                }
                for (UUID id : table.getSeats()) {
                    if (table.isBot(id) || currentPlayers.contains(id)) {
                        continue;
                    }
                    Player player = Bukkit.getPlayer(id);
                    if (player == null || !player.isOnline()) {
                        continue;
                    }
                    currentPlayers.add(id);
                    OverlayEntry entry = overlays.get(id);
                    if (entry != null && entry.expireAt() <= now) {
                        overlays.remove(id);
                        entry = null;
                    }
                    // 虚拟道具索引由交互服务维护；不能再绑定真实物品栏，否则取消换槽后高亮不动。
                    int selected = plugin.tableGadgets() == null ? 0 : plugin.tableGadgets().selectedIndex(id);
                    pendingHeldSlot = Math.max(0, Math.min(PackAssets.HOTBAR_HUD_SLOT_COUNT - 1, selected));
                    player.sendActionBar(buildActionBar(entry));
                }
            }
        }

        // 玩家离开 PLAYING、离桌或下线后，立即撤掉上一帧自定义字形；下一次客户端绘制
        // 就会恢复原版物品栏，而不是等待 ActionBar 自然淡出。
        for (UUID id : renderedPlayers) {
            if (currentPlayers.contains(id)) {
                continue;
            }
            Player player = Bukkit.getPlayer(id);
            if (player != null) {
                player.sendActionBar(Component.empty());
            }
        }
        renderedPlayers.clear();
        renderedPlayers.addAll(currentPlayers);
        overlays.keySet().removeIf(id -> !currentPlayers.contains(id));
    }

    /** 判断玩家是否属于正式出牌阶段的真人座位。 */
    private boolean isPlayingPlayer(Player player) {
        if (player == null || !player.isOnline() || plugin.getTableManager() == null) {
            return false;
        }
        GameTable table = plugin.getTableManager().getTableOf(player);
        return table != null
            && table.getPhase() == GamePhase.PLAYING
            && !table.isBot(player.getUniqueId());
    }

    /** 主动清除某个玩家的自定义底图及其待显示消息。 */
    public void clearOverlay(UUID playerId) {
        overlays.remove(playerId);
        if (!renderedPlayers.remove(playerId)) {
            return;
        }
        Player player = Bukkit.getPlayer(playerId);
        if (player != null) {
            player.sendActionBar(Component.empty());
        }
    }

    /** 在牌桌离开 PLAYING、强制关闭或停服时立即清除整桌真人的最后一帧。 */
    public void clearTable(GameTable table) {
        if (table == null) {
            return;
        }
        for (UUID playerId : table.getSeats()) {
            clearOverlay(playerId);
        }
    }

    private void clearRenderedPlayers() {
        for (UUID id : new HashSet<>(renderedPlayers)) {
            clearOverlay(id);
        }
    }

    /**
     * 组合发给玩家的 ActionBar Component。
     *
     * <p>【合成结构】：
     * <pre>
     *   [槽位字形（负 ascent → 渲染在屏幕底部）]
     *   [CE 负空格偏移，将光标归零]
     *   [叠加文字（正常 ascent → 渲染在 ActionBar 正常位置）]
     * </pre>
     * 字形的前进量（当前 {@link PackAssets.HotbarTier#advance()}）由 CraftEngine 负空格
     * 抵消，使整条文本的有效宽度 = 叠加消息宽度，客户端按叠加消息居中。
     * 无叠加时仅送字形，前进量自然成为文本宽，字形自动居中。
     *
     * <h2>水平偏移必须首尾配对</h2>
     *
     * <p>{@code offset-x} 在字形前插入一段正/负空格，画完之后【必须再插入等量的反向空格】
     * 把净前进量抵回去。原因和 {@code TrickHudView} 的居中约定同源（见那个类的注释）：
     * 客户端按【文本总宽】居中，负空格计入总宽。只在前面加偏移的话总宽跟着变，
     * 客户端会把整条文本重新居中，于是「右移 10 像素」实际只右移 5 像素 —— 偏移量被
     * 居中算式吃掉一半，而且方向看起来还是对的，很难察觉。首尾配对后总宽恒定，
     * 偏移才是纯粹的位移。
     *
     * <p>CraftEngine 不可用时降级：有叠加则只发叠加文字，无叠加则不发任何内容。
     */
    private Component buildActionBar(OverlayEntry overlay) {
        if (!offsetService.isAvailable()) {
            if (overlay != null) {
                return overlay.message().decoration(TextDecoration.ITALIC, false);
            }
            return Component.empty();
        }

        PackAssets.HotbarTier geometry = PackAssets.hotbarTier(scale);
        // 三图标各有独立基础/overlay 码位；只有同 scale 的覆盖层校验完成才整体切换。
        // 选中框与图标共享 ascent，未就绪时一律使用 bundle，绝不发送未声明的调试码位。
        boolean useOverlay = useDebugOverlayGlyph && overlayReady && overlayReadyScale == scale;
        Component glyph = Component.empty();
        for (int i = 0; i < PackAssets.HOTBAR_HUD_SLOT_COUNT; i++) {
            glyph = glyph.append(Component.text(String.valueOf(PackAssets.hotbarIconChar(i, scale, useOverlay)))
                .font(net.kyori.adventure.key.Key.key(geometry.font()))
                .decoration(TextDecoration.ITALIC, false));
            if (i + 1 < PackAssets.HOTBAR_HUD_SLOT_COUNT) {
                glyph = glyph.append(miniOrEmpty(offsetService.offset(
                    PackAssets.hotbarIconStep(scale) - PackAssets.hotbarIconAdvance(scale))));
            }
        }

        // 选中框叠加：三图标之后用零净前进量夹心定位到虚拟道具，真实持槽不参与。
        // slotStep、图标 advance 和整体 advance 均来自 PackAssets 的同源档位几何，
        // 避免缩放后仍手抄默认 24 / 69 / 21 导致错位。
        int selectLeftX = geometry.selectStartX() + pendingHeldSlot * geometry.slotStep();
        int lead = selectLeftX - geometry.advance();
        int trail = -(lead + geometry.selectAdvance());
        String selectMm = useOverlay
            ? PackAssets.hotbarSelectDebugGlyphText(scale)
            : PackAssets.hotbarSelectGlyphText(scale);
        Component select = MINI.deserialize(selectMm).decoration(TextDecoration.ITALIC, false);
        glyph = glyph
            .append(miniOrEmpty(offsetService.offset(lead)))
            .append(select)
            .append(miniOrEmpty(offsetService.offset(trail)));

        // 水平偏移：字形前推 offsetX，字形后回拉 offsetX，净前进量不变（见方法注释）
        if (offsetX != 0) {
            Component offsetLead = miniOrEmpty(offsetService.offset(offsetX));
            Component offsetTrail = miniOrEmpty(offsetService.offset(-offsetX));
            glyph = offsetLead.append(glyph).append(offsetTrail);
        }

        if (overlay == null) {
            // 仅字形，前进量 = 当前 scale 的底图 advance，客户端将其居中
            return glyph;
        }

        // 字形 + 光标归零偏移 + 叠加文字。重置量必须使用当前 scale 的真实 advance。
        Component reset = miniOrEmpty(offsetService.offset(-geometry.advance()));

        return glyph
            .append(reset)
            .append(overlay.message().decoration(TextDecoration.ITALIC, false));
    }

    /**
     * 把偏移服务返回的 MiniMessage 片段解析成 Component；空串返回 {@link Component#empty()}。
     *
     * <p>CraftEngine 取不到偏移时返回的就是空串（见 {@code CraftEngineOffsetService.offset}），
     * 直接丢给 MiniMessage 解析虽然不会抛，但白跑一次解析，这里统一短路。
     */
    private static Component miniOrEmpty(String miniMessage) {
        if (miniMessage.isEmpty()) {
            return Component.empty();
        }
        return MINI.deserialize(miniMessage).decoration(TextDecoration.ITALIC, false);
    }

    // ─────────────────────────────────────────────────────────────────────────

    /** 叠加消息条目。{@code expireAt} 取 {@link System#currentTimeMillis()} 基准。 */
    private record OverlayEntry(Component message, long expireAt) {}
}
