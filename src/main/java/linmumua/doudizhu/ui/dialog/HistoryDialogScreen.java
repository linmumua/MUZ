package linmumua.doudizhu.ui.dialog;

import io.papermc.paper.dialog.Dialog;
import io.papermc.paper.registry.data.dialog.ActionButton;
import io.papermc.paper.registry.data.dialog.body.DialogBody;
import io.papermc.paper.registry.data.dialog.input.DialogInput;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import linmumua.doudizhu.DoudizhuPlugin;
import linmumua.doudizhu.storage.MatchParticipantRecord;
import linmumua.doudizhu.storage.PlayerHistoryEntry;
import linmumua.doudizhu.ui.MuzTheme;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextDecoration;
import org.bukkit.entity.Player;

/**
 * 历史战绩的 Dialog 屏幕，承接旧箱子 {@code HandGuiService.openHistory} 与
 * {@code openHistoryDetail} 的完整行为：列表分页、详情卡片、返回列表。
 *
 * <p>【为什么要单独一类】旧实现在 {@code HandGuiService}（事实上的巨型服务）里用 Bukkit 箱子渲染战绩，
 * 分页/详情/返回逻辑与渲染耦合，且读库发生在主线程（阻塞 tick）。本类把三件事拆开：
 * <ol>
 *   <li><b>纯逻辑</b>（页号归一化、偏移量、翻页钳制、行切片、按 matchId 选记录）——
 *       全部是 static / 无 Bukkit 依赖的纯函数，可被单测直接驱动；</li>
 *   <li><b>异步存储查询</b>——经 {@code MuzScheduler.runAsync} 在异步 lane 读库，
 *       <b>绝不在 player lane 做 IO</b>；查询结果随后交给 core；</li>
 *   <li><b>player lane 展示</b>——统一交给 {@link MuzDialogService}（core），它已在 player lane 内
 *       完成权限复核、会话代次与 {@code player.showDialog}。本类<b>不</b>直接调用
 *       {@code player.showDialog(..)}，屏实现只按 {@link MuzDialogScreen} 约定渲染当前数据。</li>
 * </ol>
 *
 * <p>【陈旧请求防护：复用 core 的共享会话代次，不新增框架】
 * <ul>
 *   <li>按钮回调的陈旧由 core 的 {@link DialogSessionRegistry} 在回调时比对代次（本类只经
 *       {@link MuzDialogContext#action} 建按钮，天然继承）；</li>
 *   <li><b>异步取数的陈旧</b>由同一份 core 共享代次覆盖：发起查询时先
 *       {@code service.beginRequest(viewerId, "history.pending")}，<b>把「准备查询」也计入导航代次</b>；
 *       查询完成时用 {@code service.requestCurrent(viewerId, token)} 复核代次仍在，才允许 {@code openRoot}。</li>
 * </ul>
 * 这正是关键：任何后续导航都会替换该玩家的会话代次——玩家在 pending 期间打开了个人设置 / 管理页
 * （core 的 {@code openRoot/push/replace/back} 经 {@code show()} 会 {@code begin} 新代次）、
 * 点了关闭（{@code close} → {@code end}）、退出游戏或 reload（core 生命周期接线调 {@code clearPlayer} /
 * {@code invalidateAll}）、插件 disable（{@code shutdown}）——旧代次一律失效，pending 结果回来时被丢弃，
 * <b>不会用 openRoot 把界面抢回来</b>。旧的 history 请求也会被后来的 history 请求（同样 {@code beginRequest}）
 * 取代，只有最后一次导航生效。
 *
 * <p>【已知不可覆盖】（如实）玩家按 Esc 关闭原生 Dialog 时 Paper 没有服务端关闭事件，本类与 core 都
 * 收不到，故「Esc 关闭后 pending 回来又弹出」这一极小窗口无法根除；查询通常毫秒级，风险很低。
 *
 * <p>【权限语义】与旧实现一致：观众可查看本人或他人的战绩（在线/离线皆可，命令按 UUID+名字解析，
 * 不区分本人/他人权限），因此 {@link MuzDialogScreen#permission()} 返回 null。
 *
 * <p>入口改薄（{@code HandGuiService} / {@code HandGuiListener} / {@code DoudizhuCommand} 的委托）
 * 不在本类范围，由集成代理负责；本类不修改任何共享类。
 */
public final class HistoryDialogScreen {

    /** 列表每页 36 条：与旧箱子槽位 9..44（共 36 格）严格对齐，不新增/删减页容量。 */
    public static final int LIST_PAGE_SIZE = 36;
    /** 详情按 matchId 反查时一次读取的上限：与旧实现 {@code loadPlayerHistory(target, 100, 0)} 一致。 */
    public static final int DETAIL_LOOKUP_LIMIT = 100;
    /** 把「准备查询」计入导航代次时用的屏标识，仅用于 core 的会话审计/日志。 */
    private static final String PENDING_SCREEN_KEY = "history.pending";

    private static final DateTimeFormatter TIME_FORMAT = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm");
    private static final int BUTTON_WIDTH = 150;

    /**
     * 存储查询接缝。默认走 {@code plugin::loadPlayerHistory}；测试/替身可注入。
     * 签名刻意与旧实现相同：{@code (targetId, limit, offset)}，行为不会漂移。
     */
    @FunctionalInterface
    public interface HistoryQuery {
        List<PlayerHistoryEntry> load(UUID targetPlayerId, int limit, int offset);
    }

    /** 异步查询成功后的动作（参数为查询结果）。 */
    @FunctionalInterface
    private interface ResultConsumer {
        void accept(List<PlayerHistoryEntry> entries);
    }

    private final DoudizhuPlugin plugin;
    private final MuzDialogService service;
    private final HistoryQuery query;

    public HistoryDialogScreen(DoudizhuPlugin plugin, MuzDialogService service) {
        this(plugin, service, plugin == null ? null : plugin::loadPlayerHistory);
    }

    /** 测试用构造：允许注入查询接缝（不触发 Bukkit）。 */
    HistoryDialogScreen(DoudizhuPlugin plugin, MuzDialogService service, HistoryQuery query) {
        this.plugin = plugin;
        this.service = service;
        this.query = query;
    }

    // ------------------------------------------------------------------
    // 纯逻辑（可单测，无 Bukkit / Dialog 依赖）
    // ------------------------------------------------------------------

    /** 页号归一化：非法（<=0）一律回到第 1 页。 */
    public static int normalizePage(int page) {
        return Math.max(1, page);
    }

    /** 列表查询偏移：第 1 页 offset=0，第 N 页 offset=(N-1)*36。 */
    public static int pageOffset(int page) {
        return (normalizePage(page) - 1) * LIST_PAGE_SIZE;
    }

    /** 上一页：第 1 页保持第 1 页（旧箱子 slot45 用 {@code Math.max(1, page-1)}）。 */
    public static int previousPage(int page) {
        return Math.max(1, normalizePage(page) - 1);
    }

    /**
     * 下一页：无上界（旧箱子 slot53 直接 {@code page+1}），越界页会显示「暂无战绩」，
     * 这是旧语义，刻意保留而不是静默钳制。
     */
    public static int nextPage(int page) {
        return normalizePage(page) + 1;
    }

    /** 行切片：超过每页容量的记录一律丢弃（旧箱子在 slot>=45 处 break）。 */
    public static List<PlayerHistoryEntry> listRows(List<PlayerHistoryEntry> entries) {
        if (entries == null || entries.isEmpty()) {
            return List.of();
        }
        if (entries.size() <= LIST_PAGE_SIZE) {
            return List.copyOf(entries);
        }
        return List.copyOf(entries.subList(0, LIST_PAGE_SIZE));
    }

    /** 按 matchId 选记录：查不到返回 null（调用方据此回退到列表页）。 */
    public static PlayerHistoryEntry findEntry(List<PlayerHistoryEntry> entries, long matchId) {
        if (entries == null) {
            return null;
        }
        for (PlayerHistoryEntry entry : entries) {
            if (entry != null && entry.matchId() == matchId) {
                return entry;
            }
        }
        return null;
    }

    /**
     * 迟到回调判据：发起查询时领到的导航代次是否仍是该玩家的当前会话。
     *
     * <p>单一判据入口，{@link #openList}/{@link #openDetail} 的迟到回调都只问这一个问题，
     * 便于行为测试与 mutation 覆盖本类的判断（而不仅仅覆盖 core 的注册表）。
     * 底层就是 core 的 {@code MuzDialogService.requestCurrent}。
     */
    static boolean pendingStillCurrent(MuzDialogService service, UUID viewerId, long token) {
        return service != null && viewerId != null && service.requestCurrent(viewerId, token);
    }

    // ------------------------------------------------------------------
    // 打开列表 / 详情（异步查询 → core 在 player lane 展示）
    // ------------------------------------------------------------------

    /**
     * 门面入口：{@code /muz history} 等经此打开「某玩家战绩列表第 page 页」。
     *
     * <p>【为什么是实例方法而不是 per-open 的 screen 构造】查询要在 {@code show} <b>之前</b>异步完成，
     * 而 {@link MuzDialogScreen#build} 在 player lane 内执行、不得做 IO。所以入口必须是「先异步取数、
     * 再交给 core 展示」的编排方法；一个绑定 target/page 的屏对象无法自己承担取数。
     */
    public void open(Player viewer, UUID targetPlayerId, String targetName, int page) {
        if (viewer == null) {
            return;
        }
        openList(viewer.getUniqueId(), targetPlayerId, targetName, page);
    }

    /**
     * 打开「某玩家的战绩列表第 page 页」。与旧 {@code openHistory} 对位。
     * 目标可以是本人也可以是他人（在线/离线皆可）。
     */
    public void openList(UUID viewerId, UUID targetPlayerId, String targetName, int page) {
        if (!canOpen(viewerId, targetPlayerId)) {
            return;
        }
        int normalized = normalizePage(page);
        // 把「准备查询」也计入 core 的共享导航代次：期间任何导航都会让这个 token 失效。
        long token = service.beginRequest(viewerId, PENDING_SCREEN_KEY);
        loadAsync(viewerId, targetPlayerId, LIST_PAGE_SIZE, pageOffset(normalized), entries -> {
            if (!pendingStillCurrent(service, viewerId, token)) {
                return; // 期间已翻页 / 打开别的菜单 / 关闭 / 退出 / reload / disable，陈旧结果直接丢弃。
            }
            service.openRoot(viewerId, new ListScreen(targetPlayerId, targetName, normalized, entries));
        });
    }

    /**
     * 打开「第 page 页里 matchId 那条记录的详情」。与旧 {@code openHistoryDetail} 对位：
     * 按 matchId 反查（命中不到时回退到同一 page 的列表页，旧实现即如此）。
     */
    public void openDetail(UUID viewerId, UUID targetPlayerId, String targetName, int page, long matchId) {
        if (!canOpen(viewerId, targetPlayerId)) {
            return;
        }
        int normalized = normalizePage(page);
        long token = service.beginRequest(viewerId, PENDING_SCREEN_KEY);
        loadAsync(viewerId, targetPlayerId, DETAIL_LOOKUP_LIMIT, 0, entries -> {
            if (!pendingStillCurrent(service, viewerId, token)) {
                return;
            }
            PlayerHistoryEntry selected = findEntry(entries, matchId);
            service.openRoot(viewerId,
                selected == null
                    ? new ListScreen(targetPlayerId, targetName, normalized, entries)
                    : new DetailScreen(targetPlayerId, targetName, normalized, selected));
        });
    }

    /**
     * 结束该玩家的战绩会话（关闭/重开/disable 清理）：交给 core 关闭并 end 会话，
     * 使所有在飞结果作废。
     */
    public void close(UUID viewerId) {
        if (viewerId != null && service != null) {
            service.close(viewerId);
        }
    }

    private boolean canOpen(UUID viewerId, UUID targetPlayerId) {
        return plugin != null && service != null && service.sessions() != null
            && viewerId != null && targetPlayerId != null;
    }

    /**
     * 异步读库：不在 player lane 做 IO。
     *
     * <p>失败语义（与需求一致）：<b>读取失败不伪造空成功</b>——{@link #safeQuery} 抛异常时返回 null，
     * 这里直接中止、不改动界面（原始失败日志已按 WARNING 日志门禁留痕），并给发起者一条提示，
     * 而不是显示一个假的「暂无战绩」。只有「查询成功但确实没有记录」才走空页。
     */
    private void loadAsync(UUID viewerId, UUID targetPlayerId, int limit, int offset, ResultConsumer onLoaded) {
        Runnable task = () -> {
            List<PlayerHistoryEntry> entries = safeQuery(targetPlayerId, limit, offset);
            if (entries == null) {
                // 读取失败：不伪造空成功，不抢屏；只给发起者一条提示。
                if (service != null) {
                    service.error(viewerId, "读取历史战绩失败，请稍后再试。");
                }
                return;
            }
            onLoaded.accept(entries);
        };
        if (plugin == null || plugin.scheduler() == null) {
            // 无调度器（单测/未装配）时退化为同步，但仍只把结果交给 onLoaded。
            task.run();
            return;
        }
        plugin.scheduler().runAsync(task);
    }

    /** @return 查询结果；读取失败（异常）返回 {@code null}，与「成功但空列表」区分开。 */
    private List<PlayerHistoryEntry> safeQuery(UUID targetPlayerId, int limit, int offset) {
        if (query == null) {
            return List.of();
        }
        try {
            List<PlayerHistoryEntry> result = query.load(targetPlayerId, limit, offset);
            return result == null ? List.of() : result;
        } catch (RuntimeException exception) {
            if (plugin != null && plugin.getLogger() != null) {
                plugin.getLogger().warning("读取历史战绩失败: " + exception.getMessage());
            }
            return null;
        }
    }

    // ------------------------------------------------------------------
    // 屏实现（只在 player lane 内渲染已取好的数据，不做 IO、不直接 showDialog）
    // ------------------------------------------------------------------

    /** 列表屏。 */
    private final class ListScreen implements MuzDialogScreen {
        private final UUID targetPlayerId;
        private final String targetName;
        private final int page;
        private final List<PlayerHistoryEntry> entries;

        private ListScreen(UUID targetPlayerId, String targetName, int page, List<PlayerHistoryEntry> entries) {
            this.targetPlayerId = targetPlayerId;
            this.targetName = targetName;
            this.page = page;
            this.entries = entries;
        }

        @Override
        public String screenKey() {
            return "history.list";
        }

        @Override
        public Dialog build(MuzDialogContext ctx) {
            List<PlayerHistoryEntry> rows = listRows(entries);
            List<DialogBody> body = new ArrayList<>();
            if (rows.isEmpty()) {
                body.add(MuzDialogFactory.body(MuzTheme.muted("这里暂时还没有可展示的历史记录。")));
            } else {
                body.add(MuzDialogFactory.body(MuzTheme.muted("查看 ")
                    .append(Component.text(normalizeHistoryName(targetName), NamedTextColor.WHITE))
                    .append(MuzTheme.muted(" 的历史对局。"))));
                body.add(MuzDialogFactory.body(MuzTheme.muted("当前第 " + page + " 页，点下方箭头翻页。")));
            }

            List<ActionButton> actions = new ArrayList<>();
            for (PlayerHistoryEntry entry : rows) {
                actions.add(ctx.action(Component.text(entryLabel(entry)), Component.text(entryTooltip(entry)),
                    BUTTON_WIDTH, view -> openDetail(ctx.playerId(), targetPlayerId, targetName, page,
                        entry.matchId())));
            }
            actions.add(ctx.action(Component.text("上一页"), Component.text("查看更早的记录。"), BUTTON_WIDTH,
                view -> openList(ctx.playerId(), targetPlayerId, targetName, previousPage(page))));
            actions.add(ctx.action(Component.text("第 " + page + " 页"), Component.text("点击刷新当前页。"), BUTTON_WIDTH,
                view -> openList(ctx.playerId(), targetPlayerId, targetName, page)));
            actions.add(ctx.action(Component.text("下一页"), Component.text("继续查看后续记录。"), BUTTON_WIDTH,
                view -> openList(ctx.playerId(), targetPlayerId, targetName, nextPage(page))));

            return MuzDialogFactory.screen(listTitle(targetName, page), true, body, noInputs(),
                actions, ctx.exit(Component.text("关闭")), 2);
        }
    }

    /** 详情屏（含牌局细节、序号与返回列表）。 */
    private final class DetailScreen implements MuzDialogScreen {
        private final UUID targetPlayerId;
        private final String targetName;
        private final int page;
        private final PlayerHistoryEntry entry;

        private DetailScreen(UUID targetPlayerId, String targetName, int page, PlayerHistoryEntry entry) {
            this.targetPlayerId = targetPlayerId;
            this.targetName = targetName;
            this.page = page;
            this.entry = entry;
        }

        @Override
        public String screenKey() {
            return "history.detail";
        }

        @Override
        public Dialog build(MuzDialogContext ctx) {
            List<DialogBody> body = new ArrayList<>();
            for (Component line : detailLines(targetName, entry)) {
                body.add(MuzDialogFactory.body(line));
            }
            List<ActionButton> actions = new ArrayList<>();
            // 返回列表：回到「进入详情时的那一页」，与旧 openHistoryDetail 的回退一致。
            actions.add(ctx.action(Component.text("返回列表"), Component.text("回到历史战绩列表。"), BUTTON_WIDTH,
                view -> openList(ctx.playerId(), targetPlayerId, targetName, page)));

            return MuzDialogFactory.screen(detailTitle(targetName, entry), true, body, noInputs(),
                actions, ctx.exit(Component.text("关闭")), 2);
        }
    }

    private static List<DialogInput> noInputs() {
        return List.of();
    }

    // ------------------------------------------------------------------
    // 文本（复刻旧箱子卡片语义；因不能改 HandGuiService 的私有 helper，此处等价重写）
    // ------------------------------------------------------------------

    private Component listTitle(String targetName, int page) {
        return MuzTheme.banner("历史战绩", normalizeHistoryName(targetName), MuzTheme.muted("第 " + page + " 页"));
    }

    private Component detailTitle(String targetName, PlayerHistoryEntry entry) {
        return MuzTheme.banner("战绩详情", normalizeHistoryName(targetName),
            MuzTheme.named(historyTitleText(entry), NamedTextColor.GOLD));
    }

    private String entryLabel(PlayerHistoryEntry entry) {
        return (isDoudizhu(entry) ? "斗地主档案" : "德州档案") + " | " + outcomeLabel(entry)
            + " · #" + entry.matchId();
    }

    private String entryTooltip(PlayerHistoryEntry entry) {
        StringBuilder tooltip = new StringBuilder("对局编号 #").append(entry.matchId());
        tooltip.append(" · ").append(formatTime(entry));
        MatchParticipantRecord self = entry.self();
        if (self != null) {
            tooltip.append(" · ").append(formatSigned(self.settlementDelta()))
                .append(normalizeUnit(self.unitLabel()));
        }
        return tooltip.toString();
    }

    private List<Component> detailLines(String targetName, PlayerHistoryEntry entry) {
        List<Component> lines = new ArrayList<>();
        MatchParticipantRecord self = entry.self();
        // 牌局细节 + 序号：对局编号必须保留（旧详情标题里的「对局编号 N」）。
        lines.add(line("对局编号", "#" + entry.matchId(), NamedTextColor.GRAY));
        lines.add(line("玩家", normalizeHistoryName(targetName), NamedTextColor.WHITE));
        lines.add(line("玩法", isDoudizhu(entry) ? "斗地主" : "德州",
            isDoudizhu(entry) ? NamedTextColor.GOLD : NamedTextColor.AQUA));
        lines.add(line("结果", outcomeLabel(entry), isWin(entry) ? NamedTextColor.GREEN : NamedTextColor.RED));
        if (self != null) {
            lines.add(line("身份", self.roleLabel(), NamedTextColor.GOLD));
        }
        if (isDoudizhu(entry)) {
            lines.add(line("地主", findRole(entry, "地主"), NamedTextColor.GOLD));
            lines.add(line("农民阵营", joinRole(entry, "农民"), NamedTextColor.YELLOW));
        } else if (self != null) {
            lines.add(line("桌上位置", self.roleLabel(), NamedTextColor.GOLD));
        }
        if (self != null) {
            lines.add(line("个人盈亏",
                formatSigned(self.settlementDelta()) + normalizeUnit(self.unitLabel()),
                self.settlementDelta() >= 0 ? NamedTextColor.GREEN : NamedTextColor.RED));
            if (self.debtAfter() > 0.0001) {
                lines.add(line("当前欠款", formatMoney(self.debtAfter()) + normalizeUnit(self.unitLabel()),
                    NamedTextColor.RED));
            }
            if (self.bankrupt()) {
                lines.add(MuzTheme.danger("这一局后已破产"));
            }
        }
        lines.add(line("时间", formatTime(entry), NamedTextColor.WHITE));
        lines.add(line("地点", formatLocation(entry), NamedTextColor.WHITE));
        if (!entry.participants().isEmpty()) {
            lines.add(MuzTheme.warm("全桌结算"));
            for (MatchParticipantRecord participant : entry.participants()) {
                NamedTextColor deltaColor = participant.settlementDelta() >= 0
                    ? NamedTextColor.GREEN : NamedTextColor.RED;
                lines.add(MuzTheme.divider("• " + normalizeHistoryName(participant.playerName()))
                    .append(MuzTheme.warm(" · " + participant.roleLabel()))
                    .append(MuzTheme.named(" · " + formatSigned(participant.settlementDelta())
                        + normalizeUnit(participant.unitLabel()), deltaColor))
                    .decoration(TextDecoration.ITALIC, false));
            }
        }
        return lines;
    }

    private Component line(String label, String value, NamedTextColor color) {
        return MuzTheme.muted(label + " ")
            .append(MuzTheme.named(value, color))
            .decoration(TextDecoration.ITALIC, false);
    }

    private String historyTitleText(PlayerHistoryEntry entry) {
        return (isDoudizhu(entry) ? "斗地主档案" : "德州档案") + " | " + outcomeLabel(entry);
    }

    private boolean isDoudizhu(PlayerHistoryEntry entry) {
        return entry.match() != null && "DOUDIZHU".equalsIgnoreCase(entry.match().gameType());
    }

    private boolean isWin(PlayerHistoryEntry entry) {
        return entry.self() != null && "WIN".equalsIgnoreCase(entry.self().outcome());
    }

    private String outcomeLabel(PlayerHistoryEntry entry) {
        return isWin(entry) ? "胜利" : "失败";
    }

    private String findRole(PlayerHistoryEntry entry, String role) {
        return entry.participants().stream()
            .filter(participant -> role.equals(participant.roleLabel()))
            .map(MatchParticipantRecord::playerName)
            .findFirst()
            .map(this::normalizeHistoryName)
            .orElse("未知");
    }

    private String joinRole(PlayerHistoryEntry entry, String role) {
        List<String> names = entry.participants().stream()
            .filter(participant -> role.equals(participant.roleLabel()))
            .map(MatchParticipantRecord::playerName)
            .map(this::normalizeHistoryName)
            .toList();
        return names.isEmpty() ? "无" : String.join("、", names);
    }

    private String formatTime(PlayerHistoryEntry entry) {
        long occurredAt = entry.match() == null ? 0L : entry.match().occurredAt();
        ZonedDateTime time = Instant.ofEpochMilli(occurredAt).atZone(ZoneId.systemDefault());
        return TIME_FORMAT.format(time);
    }

    private String formatLocation(PlayerHistoryEntry entry) {
        if (entry.match() == null) {
            return "未知";
        }
        return entry.match().worldName() + " (" + trimDouble(entry.match().x()) + ", "
            + trimDouble(entry.match().y()) + ", " + trimDouble(entry.match().z()) + ")";
    }

    private String normalizeHistoryName(String name) {
        return name == null || name.isBlank() ? "未知玩家" : name;
    }

    private String normalizeUnit(String unitLabel) {
        return unitLabel == null || unitLabel.isBlank() ? "" : unitLabel;
    }

    private String formatSigned(double value) {
        String prefix = value >= 0 ? "+" : "-";
        return prefix + formatMoney(Math.abs(value));
    }

    private String formatMoney(double value) {
        return plugin == null ? trimDouble(value) : plugin.formatCompactAmount(value);
    }

    private String trimDouble(double value) {
        if (Math.abs(value - Math.rint(value)) < 0.0001) {
            return String.valueOf((long) Math.rint(value));
        }
        return String.format(Locale.ROOT, "%.2f", value);
    }
}
