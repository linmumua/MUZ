package dev.mumu.doudizhu.game;

import dev.mumu.doudizhu.DoudizhuPlugin;
import dev.mumu.doudizhu.action.CeActionExecutor;
import dev.mumu.doudizhu.ai.AiChatGateway;
import dev.mumu.doudizhu.assets.PackSounds;
import dev.mumu.doudizhu.model.CardPattern;
import dev.mumu.doudizhu.model.CardRank;
import dev.mumu.doudizhu.model.DoudizhuCard;
import dev.mumu.doudizhu.model.DoudizhuDeck;
import dev.mumu.doudizhu.model.MoveAdvisor;
import dev.mumu.doudizhu.model.PatternAnalyzer;
import dev.mumu.doudizhu.room.TableLevel;
import dev.mumu.doudizhu.ui.MuzTheme;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Random;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextDecoration;
import net.kyori.adventure.text.minimessage.MiniMessage;
import net.kyori.adventure.title.Title;
import org.bukkit.Bukkit;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;

public final class GameTable {
    private static final int PLAYER_COUNT = 3;
    private static final MiniMessage MINI = MiniMessage.miniMessage();

    private final DoudizhuPlugin plugin;
    private final TableManager manager;
    private final String name;
    private TableLevel roomLevel;
    private final Random random = new Random();
    private final List<UUID> seats = new ArrayList<>(PLAYER_COUNT);
    private final Set<UUID> readyPlayers = new HashSet<>();
    private final Map<UUID, Integer> totalScores = new LinkedHashMap<>();
    private final Map<UUID, Integer> bids = new LinkedHashMap<>();
    private final Map<UUID, PlayerRole> roles = new HashMap<>();
    private final Map<UUID, List<DoudizhuCard>> hands = new HashMap<>();
    private final Map<UUID, Set<Integer>> selections = new HashMap<>();
    private final Map<UUID, Integer> playedHandCounts = new HashMap<>();
    private final Map<UUID, String> botNames = new LinkedHashMap<>();
    private final List<Component> recentLobbyEntries = new ArrayList<>();
    private final List<RecentTrickEntry> recentTrickEntries = new ArrayList<>();

    private GamePhase phase = GamePhase.LOBBY;
    private List<DoudizhuCard> bottomCards = List.of();
    private List<UUID> bidOrder = List.of();
    private UUID currentTurn;
    private UUID leadPlayer;
    private UUID landlord;
    private UUID highestBidder;
    private int highestBid;
    // 1 = 叫地主，2 = 抢地主；同分时只在并列最高的玩家之间进入第二轮。
    private int bidRound = 1;
    private List<UUID> tieBreakOrder = List.of();
    private final Map<UUID, Integer> tieBreakBids = new LinkedHashMap<>();
    private List<UUID> doublingOrder = List.of();
    private final Map<UUID, Integer> farmerBoostChoices = new LinkedHashMap<>();
    private Integer landlordBoostFactor;
    private final Set<UUID> mingPaiPlayers = new HashSet<>();
    private int bombMultiplier = 1;
    private CardPattern currentPattern;
    private List<DoudizhuCard> currentTrickCards = List.of();
    private int botActionEpoch = 0;
    private String currentMusicKey;
    private int musicEpoch = 0;
    private long turnDeadlineMillis = -1L;
    private int lastCountdownSecond = Integer.MIN_VALUE;
    private long lastLobbyWarningSoundAt;
    private long lobbyUiResumeAtMillis;
    private long delayedUnreadyReminderAtMillis;
    private UUID pendingTimedOutPlayDecisionPlayer;
    private int pendingTimedOutPlayDecisionEpoch = Integer.MIN_VALUE;
    private String lastRandomEffectKey;
    private int lastRandomEffectStreak;
    private boolean debugAutoLoop;
    private String lastActionText = "等待加入";
    private Component lastActionComponent = Component.text("等待加入", NamedTextColor.GRAY).decoration(TextDecoration.ITALIC, false);

    public GameTable(DoudizhuPlugin plugin, TableManager manager, String name, TableLevel roomLevel) {
        this.plugin = plugin;
        this.manager = manager;
        this.name = name.trim();
        this.roomLevel = roomLevel == null ? TableLevel.FUN : roomLevel;
    }

    public String getName() {
        return name;
    }

    public TableLevel getRoomLevel() {
        return roomLevel;
    }

    public void setRoomLevel(TableLevel roomLevel) {
        this.roomLevel = roomLevel == null ? TableLevel.FUN : roomLevel;
    }

    public GamePhase getPhase() {
        return phase;
    }

    public List<UUID> getSeats() {
        return List.copyOf(seats);
    }

    public boolean isEmpty() {
        return seats.isEmpty();
    }

    public boolean contains(UUID playerId) {
        return seats.contains(playerId);
    }

    public boolean isBot(UUID playerId) {
        return botNames.containsKey(playerId);
    }

    public boolean isReady(UUID playerId) {
        return readyPlayers.contains(playerId);
    }

    public String displayName(UUID playerId) {
        if (playerId == null) {
            return "空位";
        }
        String botName = botNames.get(playerId);
        if (botName != null) {
            return botName;
        }
        Player player = Bukkit.getPlayer(playerId);
        return player == null ? playerId.toString().substring(0, 8) : player.getName();
    }

    public List<DoudizhuCard> getHand(UUID playerId) {
        return List.copyOf(hands.getOrDefault(playerId, List.of()));
    }

    public Set<Integer> getSelection(UUID playerId) {
        return Set.copyOf(selections.getOrDefault(playerId, Set.of()));
    }

    public UUID getCurrentTurn() {
        return currentTurn;
    }

    public UUID getLandlord() {
        return landlord;
    }

    public CardPattern getCurrentPattern() {
        return currentPattern;
    }

    public UUID getLeadPlayer() {
        return leadPlayer;
    }

    public List<DoudizhuCard> getCurrentTrickCards() {
        return List.copyOf(currentTrickCards);
    }

    public List<DoudizhuCard> getBottomCards() {
        return List.copyOf(bottomCards);
    }

    public PlayerRole getRole(UUID playerId) {
        return roles.get(playerId);
    }

    public int getBid(UUID playerId) {
        if (bidRound == 2 && tieBreakBids.containsKey(playerId)) {
            return tieBreakBids.getOrDefault(playerId, 0);
        }
        return bids.getOrDefault(playerId, 0);
    }

    public int getScore(UUID playerId) {
        return totalScores.getOrDefault(playerId, 0);
    }

    public UUID addBot(String preferredName) {
        // 机器人只允许在大厅阶段补位，避免中途进入打乱牌局状态
        ensurePhase(GamePhase.LOBBY, "开局后不能再加机器人。");
        if (seats.size() >= PLAYER_COUNT) {
            throw new IllegalStateException("牌桌已经满了。");
        }
        UUID botId = UUID.randomUUID();
        String name = preferredName == null || preferredName.isBlank() ? "Bot-" + nextAvailableBotIndex() : preferredName.trim();
        botNames.put(botId, name);
        plugin.registerBot(botId, this.name, DoudizhuPlugin.BotGameType.DOUDIZHU);
        seats.add(botId);
        readyPlayers.add(botId);
        totalScores.putIfAbsent(botId, 0);
        Component update = compactLobbyEvent(
            Component.text(name, NamedTextColor.WHITE).decoration(TextDecoration.ITALIC, false),
            MuzTheme.accent("加入"),
            MuzTheme.success("就绪")
        );
        announceChat(name + " 加入 · 就绪", update);
        recordLobbyEntry(update);
        refreshPhysicalTable();
        return botId;
    }

    private int nextAvailableBotIndex() {
        int index = 1;
        while (botNames.containsValue("Bot-" + index)) {
            index++;
        }
        return index;
    }

    public UUID removeBot() {
        return removeBot(null);
    }

    public UUID removeBot(String token) {
        ensurePhase(GamePhase.LOBBY, "开局后不能移除机器人。");
        UUID target = resolveBotId(token);
        if (target == null) {
            throw new IllegalStateException("当前没有机器人可移除。");
        }
        String name = botNames.get(target);
        seats.remove(target);
        readyPlayers.remove(target);
        bids.remove(target);
        roles.remove(target);
        hands.remove(target);
        selections.remove(target);
        botNames.remove(target);
        plugin.unregisterBot(target);
        Component update = compactLobbyEvent(
            Component.text(name, NamedTextColor.WHITE).decoration(TextDecoration.ITALIC, false),
            MuzTheme.muted("离桌"),
            null
        );
        announceAction(name + " 离桌", update);
        recordLobbyEntry(update);
        refreshPhysicalTable();
        return target;
    }

    public void addPlayer(Player player) {
        if (contains(player.getUniqueId())) {
            return;
        }
        if (phase != GamePhase.LOBBY) {
            throw new IllegalStateException("这一局已经开始了，暂时不能中途加入。");
        }
        if (seats.size() >= PLAYER_COUNT) {
            throw new IllegalStateException("牌桌已满。");
        }
        UUID playerId = player.getUniqueId();
        if (!plugin.canAffordEntry(playerId, roomLevel)) {
            throw new IllegalStateException(plugin.insufficientEntryMessage(playerId, roomLevel));
        }
        seats.add(playerId);
        totalScores.putIfAbsent(playerId, 0);
        Component update = compactLobbyEvent(playerId, MuzTheme.accent("加入"), MuzTheme.muted("未准备"));
        announceChat(displayName(playerId) + " 加入 · 未准备", update);
        recordLobbyEntry(update);
        refreshPhysicalTable();
    }

    public void removePlayer(Player player, String reason) {
        UUID playerId = player.getUniqueId();
        if (!contains(playerId)) {
            return;
        }
        Component leaveMessage = MuzTheme.field("离桌", MuzTheme.danger(reason));
        if (phase != GamePhase.LOBBY) {
            announceAction(displayName(playerId) + " 离桌", leaveMessage);
            resetRound();
        } else {
            Component update = compactLobbyEvent(playerId, MuzTheme.muted("离桌"), null);
            announceAction(displayName(playerId) + " 离桌", update);
            recordLobbyEntry(update);
        }
        seats.remove(playerId);
        readyPlayers.remove(playerId);
        bids.remove(playerId);
        roles.remove(playerId);
        hands.remove(playerId);
        selections.remove(playerId);
        if (botNames.remove(playerId) != null) {
            plugin.unregisterBot(playerId);
        }
        manager.unregisterPlayer(playerId);
        if (seats.isEmpty() && !plugin.getPhysicalTableManager().isPlaced(name)) {
            manager.unregisterTable(name);
        }
        refreshPhysicalTable();
    }

    public void toggleReady(Player player) {
        requireAtTable(player);
        ensurePhase(GamePhase.LOBBY, "现在不是准备阶段。");
        UUID playerId = player.getUniqueId();
        if (!readyPlayers.contains(playerId) && !plugin.canAffordEntry(playerId, roomLevel)) {
            throw new IllegalStateException(plugin.insufficientEntryMessage(playerId, roomLevel));
        }
        if (readyPlayers.contains(playerId)) {
            readyPlayers.remove(playerId);
            Component update = compactLobbyEvent(playerId, MuzTheme.muted("未准备"), null);
            announceChat(displayName(playerId) + " 未准备", update);
            recordLobbyEntry(update);
        } else {
            readyPlayers.add(playerId);
            Component update = compactLobbyEvent(playerId, MuzTheme.success("就绪"), null);
            announceChat(displayName(playerId) + " 就绪", update);
            recordLobbyEntry(update);
        }
        if (readyPlayers.size() == PLAYER_COUNT) {
            Component ready = compactLobbyEvent(MuzTheme.success("全员就绪"), MuzTheme.muted("可开始"), null);
            announceChat("全员就绪", ready);
            recordLobbyEntry(ready);
        }
        refreshPhysicalTable();
    }

    public void startRound(CommandSender sender) {
        // 开局前必须满足“刚好 3 个座位 + 全员准备”
        ensurePhase(GamePhase.LOBBY, "当前不是可开局状态。");
        if (seats.size() != PLAYER_COUNT) {
            throw new IllegalStateException("斗地主需要刚好 3 位玩家。");
        }
        if (readyPlayers.size() != PLAYER_COUNT) {
            warnUnreadyPlayersForStartAttempt();
            throw new IllegalStateException("三位玩家都准备后才能开局。");
        }
        for (UUID seat : seats) {
            if (!isBot(seat) && !plugin.canAffordEntry(seat, roomLevel)) {
                throw new IllegalStateException(displayName(seat) + " 资格不足: " + plugin.insufficientEntryMessage(seat, roomLevel));
            }
        }
        dealFreshRound();
        broadcastActionBar(MuzTheme.field(
            "开局",
            senderIdentity(sender, NamedTextColor.WHITE)
                .append(MuzTheme.divider(" · "))
                .append(MuzTheme.body("新一局已经开始"))
        ));
        announceAction("叫分顺序", MuzTheme.field("叫分顺序", orderedPlayersComponent(bidOrder)));
        playRoundMusic();
        openHandsForAll();
        promptBidTurn();
        refreshPhysicalTable();
        runBotActionIfNeeded();
    }

    public void bid(Player player, int points) {
        // 叫分逻辑只允许当前操作位执行，并且不能小于当前最高分
        requireAtTable(player);
        ensurePhase(GamePhase.BIDDING, "当前不是叫分阶段。");
        requireCurrentTurn(player);
        if (points < 0 || points > 3) {
            throw new IllegalArgumentException("叫分只能是 0 到 3。");
        }
        if (bidRound == 1 && points != 0 && points < highestBid) {
            throw new IllegalArgumentException("你不能叫比当前最高分更低的分，或者输入 0 放弃。");
        }

        UUID playerId = player.getUniqueId();
        processBidChoice(playerId, points);
    }

    public void chooseDouble(Player player, boolean doubled) {
        requireAtTable(player);
        ensurePhase(GamePhase.DOUBLING, "当前不是加倍阶段。");
        requireCurrentTurn(player);
        processDoublingChoice(player.getUniqueId(), doubled ? 2 : 1, false, false);
    }

    public void chooseMingPai(Player player) {
        requireAtTable(player);
        ensurePhase(GamePhase.DOUBLING, "当前不是加倍阶段。");
        requireCurrentTurn(player);
        processDoublingChoice(player.getUniqueId(), 1, true, false);
    }

    public void chooseSuperDouble(Player player) {
        requireAtTable(player);
        ensurePhase(GamePhase.DOUBLING, "当前不是加倍阶段。");
        requireCurrentTurn(player);
        processDoublingChoice(player.getUniqueId(), 4, false, false);
    }

    public void toggleSelection(UUID playerId, int cardId) {
        requireAtTable(playerId);
        Set<Integer> selection = selections.computeIfAbsent(playerId, ignored -> new HashSet<>());
        if (selection.contains(cardId)) {
            selection.remove(cardId);
        } else {
            selection.add(cardId);
        }
    }

    public void clearSelection(UUID playerId) {
        selections.remove(playerId);
    }

    public void replaceSelection(UUID playerId, List<DoudizhuCard> cards) {
        requireAtTable(playerId);
        Set<Integer> next = new HashSet<>();
        for (DoudizhuCard card : cards) {
            next.add(card.id());
        }
        if (next.isEmpty()) {
            selections.remove(playerId);
        } else {
            selections.put(playerId, next);
        }
    }

    public void playSelected(Player player) {
        // 真人玩家的出牌入口：从当前 selection 中组牌并校验
        requireAtTable(player);
        ensurePhase(GamePhase.PLAYING, "当前不是出牌阶段。");
        requireCurrentTurn(player);

        UUID playerId = player.getUniqueId();
        Set<Integer> selection = selections.getOrDefault(playerId, Set.of());
        if (selection.isEmpty()) {
            throw new IllegalStateException("你还没有选择任何牌。");
        }

        List<DoudizhuCard> hand = hands.getOrDefault(playerId, List.of());
        List<DoudizhuCard> chosen = hand.stream()
            .filter(card -> selection.contains(card.id()))
            .sorted(DoudizhuCard.ORDER)
            .toList();
        if (chosen.size() != selection.size()) {
            clearSelection(playerId);
            throw new IllegalStateException("已选择的实体手牌状态过期，请重新选择。");
        }

        CardPattern pattern = PatternAnalyzer.analyze(chosen)
            .orElseThrow(() -> new IllegalArgumentException("这组牌型不合法，不能这样出。"));

        if (leadPlayer != null && !Objects.equals(leadPlayer, playerId) && currentPattern != null && !pattern.canBeat(currentPattern)) {
            throw new IllegalArgumentException("这手牌压不过上一手。");
        }

        hand.removeIf(card -> selection.contains(card.id()));
        hand.sort(DoudizhuCard.ORDER);
        clearSelection(playerId);

        UUID previousLead = leadPlayer;
        currentPattern = pattern;
        currentTrickCards = List.copyOf(chosen);
        leadPlayer = playerId;
        boolean pressurePlay = previousLead != null && !Objects.equals(previousLead, playerId);
        boolean multiplierRaised = pattern.type().isBombFamily();
        recordPlayedHand(playerId);
        if (multiplierRaised) {
            bombMultiplier *= 2;
        }

        boolean threeCardsLeft = hand.size() == 3;
        playPatternVoice(pattern, pattern.primaryRank(), pressurePlay, threeCardsLeft);
        announceAction(
            displayName(playerId) + " " + pattern.displayName(),
            actorUpdate(playerId, MuzTheme.success(pattern.displayName()), chosen.stream().map(DoudizhuCard::displayLabel).collect(Collectors.joining(" ")))
        );
        recordTrickEntry(playerId, chosen, pattern);
        if (multiplierRaised) {
            announceAction("倍率抬升", MuzTheme.field("倍率", liveMultiplierComponent()));
        }
        CeActionExecutor.executePlayProfile(
            plugin,
            player,
            this,
            pattern,
            chosen,
            plugin.resolvePlayActionProfile(playerId, pattern)
        );

        if (hand.isEmpty()) {
            finishRound(playerId);
            return;
        }

        currentTurn = nextSeat(playerId);
        promptPlayTurn();
        refreshHands();
        refreshPhysicalTable();
        runBotActionIfNeeded();
    }

    public void pass(Player player) {
        // 不要只允许跟牌玩家执行，领牌方不能“不要”
        requireAtTable(player);
        ensurePhase(GamePhase.PLAYING, "当前不是出牌阶段。");
        requireCurrentTurn(player);
        UUID playerId = player.getUniqueId();
        if (leadPlayer == null || Objects.equals(leadPlayer, playerId)) {
            throw new IllegalStateException("这轮是你先手，不能直接点不要。");
        }

        clearSelection(playerId);
        playEffectAll(PackSounds.autoPass());
        announceAction(displayName(playerId) + " 不要", actorUpdate(playerId, MuzTheme.muted("不要"), "这轮先不压牌"));
        UUID next = nextSeat(playerId);
        if (Objects.equals(next, leadPlayer)) {
            currentTurn = leadPlayer;
            currentPattern = null;
            currentTrickCards = List.of();
        announceAction(displayName(leadPlayer) + " 获得先手", actorUpdate(leadPlayer, MuzTheme.warm("先手"), "拿到这一轮的先手"));
        } else {
            currentTurn = next;
        }
        promptPlayTurn();
        refreshHands();
        refreshPhysicalTable();
        runBotActionIfNeeded();
    }

    public void forceEnd(CommandSender sender) {
        if (phase == GamePhase.LOBBY) {
            throw new IllegalStateException("当前没有正在进行的对局。");
        }
        stopMusicAll();
        announceAction(sender.getName() + " 强制结束", actorUpdate(senderIdentity(sender, NamedTextColor.WHITE), MuzTheme.danger("强制结束"), "这一局先提前结束了"));
        resetRound();
    }

    public List<Component> buildStatusLines() {
        List<Component> lines = new ArrayList<>();
        lines.add(MuzTheme.header("斗地主", name + " 号桌", plugin.roomDisplayTag(roomLevel)));
        lines.add(MuzTheme.field("阶段", MuzTheme.accent(phase.displayName())));
        lines.add(MuzTheme.field("座位", MuzTheme.body(seats.size() + "/" + PLAYER_COUNT)));
        for (UUID seat : seats) {
            List<Component> details = new ArrayList<>();
            Integer botNumericId = plugin.getBotNumericId(seat);
            if (botNumericId != null) {
                details.add(MuzTheme.muted("Bot " + botNumericId));
            }
            if (readyPlayers.contains(seat)) {
                details.add(MuzTheme.success("已准备"));
            }
            if (landlord != null && landlord.equals(seat)) {
                details.add(MuzTheme.warm("地主"));
            }
            if (phase == GamePhase.PLAYING) {
                details.add(MuzTheme.accent("手牌 " + getHand(seat).size() + " 张"));
            }
            details.add(MuzTheme.muted("总分 " + getScore(seat)));
            lines.add(MuzTheme.row(identity(seat, NamedTextColor.WHITE), details));
        }
        if (currentTurn != null) {
            lines.add(MuzTheme.field("当前操作", identity(currentTurn, NamedTextColor.WHITE)));
        }
        if (highestBid > 0 && highestBidder != null) {
            lines.add(MuzTheme.field(
                bidRound == 1 ? "最高叫分" : "最高抢分",
                MuzTheme.warm(highestBid + " 分").append(MuzTheme.divider(" · ")).append(identity(highestBidder, NamedTextColor.WHITE))
            ));
            lines.add(MuzTheme.field("倍率", liveMultiplierComponent()));
        }
        if (bidRound == 2 && !tieBreakOrder.isEmpty()) {
            lines.add(MuzTheme.field("抢地主顺序", orderedPlayersComponent(tieBreakOrder)));
        }
        if (currentPattern != null && !currentTrickCards.isEmpty()) {
            lines.add(currentTrickPreviewComponent());
        }
        if (!bottomCards.isEmpty()) {
            lines.add(MuzTheme.field("底牌", MuzTheme.warm(bottomCards.stream().map(DoudizhuCard::displayLabel).collect(Collectors.joining(" ")))));
        }
        return lines;
    }

    public String currentTrickPreviewText() {
        if (currentPattern == null || currentTrickCards.isEmpty()) {
            return "上一手 · 暂无";
        }
        return "上一手 · " + playerName(leadPlayer) + " · " + describeCards(currentTrickCards, currentPattern);
    }

    public Component currentTrickPreviewComponent() {
        if (currentPattern == null || currentTrickCards.isEmpty()) {
            return MuzTheme.orange("上一手")
                .append(MuzTheme.divider(" · "))
                .append(MuzTheme.muted("暂未出现"));
        }
        return MuzTheme.orange("上一手")
            .append(MuzTheme.divider(" · "))
            .append(identity(leadPlayer, NamedTextColor.WHITE))
            .append(MuzTheme.divider(" · "))
            .append(MuzTheme.orange(describeCards(currentTrickCards, currentPattern)));
    }

    public List<Component> slidingTrickPreviewComponents(long nowMillis) {
        pruneExpiredTrickEntries(nowMillis);
        if (recentTrickEntries.isEmpty()) {
            return List.of(currentTrickPreviewComponent());
        }
        List<RecentTrickEntry> visible = recentTrickEntries.size() <= 5
            ? List.copyOf(recentTrickEntries)
            : List.copyOf(recentTrickEntries.subList(recentTrickEntries.size() - 5, recentTrickEntries.size()));
        List<Component> lines = new ArrayList<>(visible.size());
        for (int index = 0; index < visible.size(); index++) {
            RecentTrickEntry entry = visible.get(index);
            long ageMillis = Math.max(0L, nowMillis - entry.createdAtMillis());
            lines.add(styleRecentTrickLine(entry.component(), ageMillis, index, visible.size()));
        }
        return lines;
    }

    public List<Component> recentLobbyPreviewComponents() {
        if (recentLobbyEntries.isEmpty()) {
            if (seats.isEmpty()) {
                return List.of(MuzTheme.muted("等待玩家加入"));
            }
            return seats.stream()
                .limit(3)
                .map(this::lobbySnapshotLine)
                .toList();
        }
        return List.copyOf(recentLobbyEntries.subList(0, Math.min(3, recentLobbyEntries.size())));
    }

    public String lastActionText() {
        return lastActionText;
    }

    public Component lastActionComponent() {
        return lastActionComponent;
    }

    public String describePlayedCards(CardPattern pattern, List<DoudizhuCard> cards) {
        return describeCards(cards, pattern);
    }

    public String liveMultiplierStatusText() {
        if (highestBid <= 0 || landlord == null) {
            return phase == GamePhase.LOBBY ? "等待本局开局" : "等待叫分结果";
        }
        StringBuilder builder = new StringBuilder()
            .append("底分 ").append(Math.max(1, highestBid))
            .append(" · 炸弹 x").append(bombMultiplier);
        if (landlord != null) {
        builder.append(" · 农民加倍 ").append(boostedFarmerCount()).append("/").append(farmerSeatCount()).append(" 人");
            if (landlordBoostFactor != null) {
                builder.append(landlordBoostFactor > 1 ? " · 地主加倍 x" + landlordBoostFactor : " · 地主不加倍");
            }
            if (!mingPaiPlayers.isEmpty()) {
                builder.append(" · 明牌 ").append(mingPaiPlayers.size()).append(" 人");
            }
        }
        builder.append(" · ").append(pairMultiplierSummary(false, false));
        return builder.toString();
    }

    public Component liveMultiplierStatusComponent() {
        if (highestBid <= 0 || landlord == null) {
            return phase == GamePhase.LOBBY ? MuzTheme.muted("等待本局开局") : MuzTheme.muted("等待叫分结果");
        }
        Component line = MuzTheme.warm("底分 " + Math.max(1, highestBid))
            .append(MuzTheme.divider(" · "))
            .append(MuzTheme.hotValue("x" + bombMultiplier));
        line = line.append(MuzTheme.divider(" · "))
            .append(MuzTheme.hotMetric("农民加倍", boostedFarmerCount() + "/" + farmerSeatCount(), "人"));
        if (landlordBoostFactor != null) {
            line = line.append(MuzTheme.divider(" · "))
                .append(landlordBoostFactor > 1 ? MuzTheme.hotMetric("地主加倍", "x" + landlordBoostFactor) : MuzTheme.muted("地主不加倍"));
        }
        return line.append(MuzTheme.divider(" · "))
            .append(MuzTheme.hotValue(pairMultiplierSummary(false, false)));
    }

    public void enableDebugAutoLoop() {
        debugAutoLoop = true;
    }

    public void disableDebugAutoLoop() {
        debugAutoLoop = false;
    }

    public void shutdown() {
        debugAutoLoop = false;
        plugin.getHandGuiService().closeHands(this);
        resetRound();
    }

    public void forceClose(String reason) {
        debugAutoLoop = false;
        plugin.getHandGuiService().closeHands(this);
        stopMusicAll();
        for (UUID seat : new ArrayList<>(seats)) {
            if (!isBot(seat)) {
                Player player = onlinePlayer(seat);
                if (player != null) {
                    player.sendMessage(text(reason, NamedTextColor.RED));
                }
                manager.unregisterPlayer(seat);
            } else {
                plugin.unregisterBot(seat);
            }
        }
        seats.clear();
        readyPlayers.clear();
        totalScores.clear();
        bids.clear();
        tieBreakBids.clear();
        roles.clear();
        hands.clear();
        selections.clear();
        botNames.clear();
        bottomCards = List.of();
        bidOrder = List.of();
        tieBreakOrder = List.of();
        doublingOrder = List.of();
        currentTurn = null;
        leadPlayer = null;
        landlord = null;
        highestBidder = null;
        highestBid = 0;
        bidRound = 1;
        farmerBoostChoices.clear();
        landlordBoostFactor = null;
        mingPaiPlayers.clear();
        bombMultiplier = 1;
        currentPattern = null;
        currentTrickCards = List.of();
        turnDeadlineMillis = -1L;
        lastCountdownSecond = Integer.MIN_VALUE;
        lastLobbyWarningSoundAt = 0L;
        delayedUnreadyReminderAtMillis = 0L;
        pendingTimedOutPlayDecisionPlayer = null;
        pendingTimedOutPlayDecisionEpoch = Integer.MIN_VALUE;
        phase = GamePhase.LOBBY;
    }

    public void tickActionBar() {
        if (seats.isEmpty()) {
            return;
        }
        if (phase == GamePhase.LOBBY) {
            if (System.currentTimeMillis() < lobbyUiResumeAtMillis) {
                return;
            }
            for (UUID seat : seats) {
                Player player = onlinePlayer(seat);
                if (player != null) {
                    player.sendActionBar(buildPersistentActionBar(player.getUniqueId(), 0));
                }
            }
            return;
        }

        int remaining = remainingCountdownSeconds();
        if (phase == GamePhase.DOUBLING && remaining <= 0 && currentTurn != null && !isBot(currentTurn)) {
            processDoublingChoice(currentTurn, 1, false, true);
            return;
        }
        if (phase == GamePhase.PLAYING && remaining <= 0 && currentTurn != null && !isBot(currentTurn)) {
            handleTimedOutPlayerTurn(currentTurn, botActionEpoch);
            return;
        }
        if (!isBot(currentTurn) && remaining != lastCountdownSecond) {
            if (remaining > 0 && remaining <= 5) {
                DoudizhuPlugin.ConfiguredSound sound = plugin.countdownSound();
                if (sound.volume() > 0.0f) {
                    playSoundAll(sound.key(), sound.volume(), sound.pitch());
                }
            }
            lastCountdownSecond = remaining;
        }

        for (UUID seat : seats) {
            Player player = onlinePlayer(seat);
            if (player != null) {
                player.sendActionBar(buildPersistentActionBar(player.getUniqueId(), remaining));
            }
        }
    }

    private void dealFreshRound() {
        // 每局重新洗牌、发牌、重置叫分与炸弹倍数
        phase = GamePhase.BIDDING;
        bids.clear();
        tieBreakBids.clear();
        roles.clear();
        hands.clear();
        selections.clear();
        playedHandCounts.clear();
        currentPattern = null;
        currentTrickCards = List.of();
        leadPlayer = null;
        landlord = null;
        highestBidder = null;
        highestBid = 0;
        bidRound = 1;
        tieBreakOrder = List.of();
        doublingOrder = List.of();
        farmerBoostChoices.clear();
        landlordBoostFactor = null;
        mingPaiPlayers.clear();
        bombMultiplier = 1;
        readyPlayers.clear();

        List<DoudizhuCard> deck = DoudizhuDeck.shuffled(random);
        for (int index = 0; index < PLAYER_COUNT; index++) {
            List<DoudizhuCard> hand = new ArrayList<>(deck.subList(index * 17, index * 17 + 17));
            hand.sort(DoudizhuCard.ORDER);
            hands.put(seats.get(index), hand);
        }
        bottomCards = new ArrayList<>(deck.subList(51, 54));
        bottomCards.sort(DoudizhuCard.ORDER);

        int startIndex = random.nextInt(PLAYER_COUNT);
        List<UUID> order = new ArrayList<>(PLAYER_COUNT);
        for (int index = 0; index < PLAYER_COUNT; index++) {
            order.add(seats.get((startIndex + index) % PLAYER_COUNT));
        }
        bidOrder = List.copyOf(order);
        tieBreakOrder = List.of();
        currentTurn = bidOrder.get(0);
    }

    private void confirmLandlord(UUID playerId, int bid) {
        confirmLandlord(playerId, bid, null);
    }

    private void confirmLandlord(UUID playerId, int bid, String priorTriggerSound) {
        // 地主确定后追加底牌，并进入加倍阶段
        landlord = playerId;
        highestBid = Math.max(1, bid);
        roles.clear();
        for (UUID seat : seats) {
            roles.put(seat, seat.equals(landlord) ? PlayerRole.LANDLORD : PlayerRole.FARMER);
        }
        List<DoudizhuCard> landlordHand = new ArrayList<>(hands.getOrDefault(playerId, List.of()));
        landlordHand.addAll(bottomCards);
        landlordHand.sort(DoudizhuCard.ORDER);
        hands.put(playerId, landlordHand);
        currentTurn = playerId;
        currentPattern = null;
        currentTrickCards = List.of();
        leadPlayer = null;

        announceAction("地主确认", MuzTheme.field(
            "地主确认",
            identity(playerId, NamedTextColor.WHITE)
                .append(MuzTheme.divider(" · "))
                .append(MuzTheme.body("底牌 " + bottomCards.stream().map(DoudizhuCard::displayLabel).collect(Collectors.joining(" "))))
        ));
        if (priorTriggerSound == null || priorTriggerSound.isBlank()) {
            playEffectAll(PackSounds.landlordConfirmed());
        } else {
            playRandomEffectAll(List.of(priorTriggerSound, PackSounds.landlordConfirmed()));
        }
        broadcastActionBar(MuzTheme.field("当前倍率", liveMultiplierComponent()));
        openHandsForAll();
        startDoublingPhase();
        refreshPhysicalTable();
        runBotActionIfNeeded();
    }

    private void finishRound(UUID winner) {
        boolean landlordWin = Objects.equals(winner, landlord);
        List<UUID> winningSeats = landlordWin ? List.of(landlord) : seats.stream().filter(seat -> !seat.equals(landlord)).toList();
        int roundScore = resolvedCoreScore(landlordWin);
        Map<UUID, Integer> scoreDeltas = new LinkedHashMap<>();
        stopMusicAll();
        if (landlordWin) {
            int landlordGain = 0;
            for (UUID seat : seats) {
                if (!seat.equals(landlord)) {
                    int loss = roundScore * seatPairFactor(seat);
                    landlordGain += loss;
                    scoreDeltas.put(seat, -loss);
                    totalScores.computeIfPresent(seat, (ignored, score) -> score - loss);
                }
            }
            scoreDeltas.put(landlord, landlordGain);
            int finalLandlordGain = landlordGain;
            totalScores.computeIfPresent(landlord, (ignored, score) -> score + finalLandlordGain);
        } else {
            int landlordLoss = 0;
            for (UUID seat : seats) {
                if (!seat.equals(landlord)) {
                    int gain = roundScore * seatPairFactor(seat);
                    landlordLoss += gain;
                    scoreDeltas.put(seat, gain);
                    totalScores.computeIfPresent(seat, (ignored, score) -> score + gain);
                }
            }
            scoreDeltas.put(landlord, -landlordLoss);
            int finalLandlordLoss = landlordLoss;
            totalScores.computeIfPresent(landlord, (ignored, score) -> score - finalLandlordLoss);
        }
        Component summary = settlementSummaryComponent(winningSeats, landlordWin);
        // IMPORTANT:
        // Keep round-end chat on a single send path.
        // `sendRoundChatBundles(...)` already includes the full multiplier block, so broadcasting summary here again
        // would resend the same final multiplier text and cause duplicate settlement chat after a round ends.
        setLastActionText(landlordWin ? "地主阵营胜出" : "农民阵营胜出", summary);
        Map<UUID, DoudizhuPlugin.SettlementResult> settlementSnapshots = broadcastEconomySettlement(scoreDeltas);
        plugin.recordDoudizhuMatch(this, winningSeats, scoreDeltas, settlementSnapshots);
        sendRoundChatBundles(winningSeats, settlementSnapshots, scoreDeltas);
        broadcastStickyOutcomeActionBar(winningSeats);
        for (UUID seat : seats) {
            playEffect(seat, (landlordWin == seat.equals(landlord)) ? PackSounds.win() : PackSounds.lose());
        }
        resetRound();
    }

    private void broadcastStickyOutcomeActionBar(List<UUID> winners) {
        lobbyUiResumeAtMillis = System.currentTimeMillis() + 5500L;
        for (int index = 0; index < 5; index++) {
            long delay = index * 20L;
            plugin.getServer().getScheduler().runTaskLater(plugin, () -> {
                if (plugin.isShuttingDown()) {
                    return;
                }
                for (UUID seat : seats) {
                    Player player = onlinePlayer(seat);
                    if (player == null) {
                        continue;
                    }
                    player.sendActionBar(winners.contains(seat) ? MuzTheme.success("胜利") : MuzTheme.danger("失利"));
                }
            }, delay);
        }
    }

    private void resetRound() {
        stopMusicAll();
        phase = GamePhase.LOBBY;
        readyPlayers.clear();
        bids.clear();
        roles.clear();
        hands.clear();
        selections.clear();
        playedHandCounts.clear();
        for (UUID botId : botNames.keySet()) {
            readyPlayers.add(botId);
        }
        reseedLobbyEntries();
        recentTrickEntries.clear();
        bottomCards = List.of();
        bidOrder = List.of();
        currentTurn = null;
        leadPlayer = null;
        landlord = null;
        highestBidder = null;
        highestBid = 0;
        doublingOrder = List.of();
        farmerBoostChoices.clear();
        landlordBoostFactor = null;
        mingPaiPlayers.clear();
        bombMultiplier = 1;
        currentPattern = null;
        currentTrickCards = List.of();
        plugin.getHandGuiService().closeHands(this);
        clearTurnCountdown();
        refreshPhysicalTable();
        if (canScheduleTasks() && debugAutoLoop && seats.size() == PLAYER_COUNT && seats.stream().allMatch(this::isBot)) {
            plugin.getServer().getScheduler().runTaskLater(plugin, () -> {
                try {
                    startRound(plugin.getServer().getConsoleSender());
                } catch (RuntimeException ignored) {
                }
            }, 2L);
        }
    }

    private void promptBidTurn() {
        // 通过 actionbar 给当前真人玩家提示，机器人则走自动行为
        botActionEpoch++;
        armTurnCountdown();
        tickActionBar();
    }

    private void promptDoublingTurn() {
        botActionEpoch++;
        armTurnCountdown();
        tickActionBar();
    }

    private void promptPlayTurn() {
        // 先判断是否“手里根本没有能压的牌”，有的话直接自动跳过
        skipIfNoResponse();
        refreshPhysicalTable();
        botActionEpoch++;
        armTurnCountdown();
        tickActionBar();
        Player player = onlinePlayer(currentTurn);
        if (player != null) {
            plugin.getPhysicalTableManager().refreshPrivateHand(this, player.getUniqueId());
        }
        updateMusicState();
    }

    private void startDoublingPhase() {
        phase = GamePhase.DOUBLING;
        farmerBoostChoices.clear();
        landlordBoostFactor = null;
        mingPaiPlayers.clear();
        List<UUID> order = new ArrayList<>();
        for (UUID seat : seats) {
            if (!seat.equals(landlord)) {
                order.add(seat);
            }
        }
        if (landlord != null) {
            order.add(landlord);
        }
        doublingOrder = List.copyOf(order);
        currentTurn = doublingOrder.isEmpty() ? landlord : doublingOrder.get(0);
        announceAction("加倍阶段", MuzTheme.field(
            "加倍阶段",
            liveMultiplierComponent()
                .append(MuzTheme.divider(" · "))
                .append(MuzTheme.muted("农民先选，地主最后决定；只选择加倍或不加倍"))
        ));
        promptDoublingTurn();
    }

    private void startPlayPhase() {
        phase = GamePhase.PLAYING;
        currentTurn = landlord;
        announceAction("出牌阶段", MuzTheme.field(
            "出牌阶段",
            liveMultiplierComponent()
                .append(MuzTheme.divider(" · "))
                .append(identity(landlord, NamedTextColor.WHITE))
        ));
        promptPlayTurn();
    }

    private void openHandsForAll() {
        for (UUID seat : seats) {
            if (!isBot(seat)) {
                plugin.getPhysicalTableManager().refreshPrivateHand(this, seat);
            }
        }
    }

    private void refreshHands() {
        for (UUID seat : seats) {
            if (!isBot(seat)) {
                plugin.getPhysicalTableManager().refreshPrivateHand(this, seat);
            }
        }
    }

    private void skipIfNoResponse() {
        // 自动不要：只在不是领牌者且手里没有任何可压牌型时触发
        while (
            phase == GamePhase.PLAYING
                && currentTurn != null
                && leadPlayer != null
                && currentPattern != null
                && !Objects.equals(currentTurn, leadPlayer)
        ) {
            List<DoudizhuCard> hand = hands.getOrDefault(currentTurn, List.of());
            if (MoveAdvisor.hasAnyBeatingMove(hand, currentPattern)) {
                return;
            }

            UUID stuckPlayer = currentTurn;
            clearSelection(stuckPlayer);
            playEffectAll(PackSounds.autoPass());
        announceAction(displayName(stuckPlayer) + " 自动不要", actorUpdate(stuckPlayer, MuzTheme.muted("自动不要"), "手里暂时没有更大的牌"));

            UUID next = nextSeat(stuckPlayer);
            if (Objects.equals(next, leadPlayer)) {
                currentTurn = leadPlayer;
                currentPattern = null;
                currentTrickCards = List.of();
        announceAction(displayName(leadPlayer) + " 获得先手", actorUpdate(leadPlayer, MuzTheme.warm("先手"), "拿到这一轮的先手"));
                return;
            }
            currentTurn = next;
        }
    }

    private UUID nextSeat(UUID playerId) {
        int index = seats.indexOf(playerId);
        return seats.get((index + 1) % seats.size());
    }

    private void requireCurrentTurn(Player player) {
        if (!Objects.equals(currentTurn, player.getUniqueId())) {
            throw new IllegalStateException("先等等，这手还没轮到你。");
        }
    }

    private void ensurePhase(GamePhase expected, String message) {
        if (phase != expected) {
            throw new IllegalStateException(message);
        }
    }

    private void requireAtTable(Player player) {
        requireAtTable(player.getUniqueId());
    }

    private void requireAtTable(UUID playerId) {
        if (!contains(playerId)) {
            throw new IllegalStateException("你不在这张牌桌里。");
        }
    }

    private void broadcast(Component message) {
        Component full = MuzTheme.banner("斗地主", name + " 号桌", message);
        Component actionBar = message.decoration(TextDecoration.ITALIC, false);
        for (UUID seat : seats) {
            Player player = onlinePlayer(seat);
            if (player != null) {
                player.sendMessage(full);
                player.sendActionBar(actionBar);
            }
        }
    }

    private void broadcastActionBar(Component message) {
        Component actionBar = message.decoration(TextDecoration.ITALIC, false);
        for (UUID seat : seats) {
            Player player = onlinePlayer(seat);
            if (player != null) {
                player.sendActionBar(actionBar);
            }
        }
    }

    private void announceChat(String plainText, Component component) {
        setLastActionText(plainText, component);
        broadcast(component);
    }

    private void announceAction(String plainText, Component component) {
        setLastActionText(plainText, component);
        broadcastActionBar(component);
    }

    private void setLastActionText(String plainText, Component component) {
        lastActionText = plainText;
        lastActionComponent = component == null ? text(plainText, NamedTextColor.GRAY) : component.decoration(TextDecoration.ITALIC, false);
    }

    private Component text(String message, NamedTextColor color) {
        return MuzTheme.named(message, color).decoration(TextDecoration.ITALIC, false);
    }

    private Component identity(UUID playerId, NamedTextColor fallbackColor) {
        return plugin.playerIdentityComponent(playerId, displayName(playerId), fallbackColor);
    }

    private Component senderIdentity(CommandSender sender, NamedTextColor fallbackColor) {
        if (sender instanceof Player player) {
            return identity(player.getUniqueId(), fallbackColor);
        }
        return text(sender.getName(), fallbackColor);
    }

    private Component append(Component... components) {
        Component result = Component.empty();
        for (Component component : components) {
            if (component != null) {
                result = result.append(component);
            }
        }
        return result.decoration(TextDecoration.ITALIC, false);
    }

    private Component chatOutcomeLine(List<UUID> winners, List<UUID> losers) {
        return MuzTheme.field(
            "胜负",
            MuzTheme.success("胜方")
                .append(MuzTheme.divider(" · "))
                .append(playerListComponent(winners, NamedTextColor.WHITE))
                .append(MuzTheme.divider(" · "))
                .append(MuzTheme.danger("负方"))
                .append(MuzTheme.divider(" · "))
                .append(playerListComponent(losers, NamedTextColor.WHITE))
        );
    }

    private Component playerListComponent(List<UUID> players, NamedTextColor color) {
        if (players == null || players.isEmpty()) {
            return text("无", color);
        }
        Component line = Component.empty();
        for (int index = 0; index < players.size(); index++) {
            if (index > 0) {
                line = append(line, text("、", color));
            }
            line = append(line, identity(players.get(index), color));
        }
        return line;
    }

    private Player onlinePlayer(UUID playerId) {
        return Bukkit.getPlayer(playerId);
    }

    private void playSoundAll(String soundKey, float volume, float pitch) {
        for (UUID seat : seats) {
            playSound(seat, soundKey, volume, pitch);
        }
    }

    private void playEffectAll(String soundKey) {
        playSoundAll(soundKey, plugin.getEffectVolume(), 1.0f);
    }

    private void playEffect(UUID playerId, String soundKey) {
        playSound(playerId, soundKey, plugin.getEffectVolume(), 1.0f);
    }

    private void playRandomEffectAll(List<String> soundKeys) {
        if (soundKeys == null || soundKeys.isEmpty()) {
            return;
        }
        List<String> candidates = new ArrayList<>();
        for (String soundKey : soundKeys) {
            if (soundKey == null || soundKey.isBlank() || candidates.contains(soundKey)) {
                continue;
            }
            candidates.add(soundKey);
        }
        if (candidates.isEmpty()) {
            return;
        }
        List<String> filtered = candidates;
        if (lastRandomEffectKey != null && lastRandomEffectStreak >= 2 && candidates.size() > 1) {
            filtered = candidates.stream()
                .filter(soundKey -> !soundKey.equals(lastRandomEffectKey))
                .toList();
        }
        String selected = filtered.get(random.nextInt(filtered.size()));
        if (selected.equals(lastRandomEffectKey)) {
            lastRandomEffectStreak++;
        } else {
            lastRandomEffectKey = selected;
            lastRandomEffectStreak = 1;
        }
        playEffectAll(selected);
    }

    private void playPatternVoice(CardPattern pattern, CardRank primaryRank, boolean pressurePlay, boolean threeCardsLeft) {
        if (pattern == null || primaryRank == null) {
            if (threeCardsLeft) {
                playEffectAll(PackSounds.threeCardsWarning());
            }
            return;
        }
        List<String> candidates = new ArrayList<>();
        candidates.add(PackSounds.play(pattern, primaryRank));
        if (pressurePlay) {
            candidates.add(PackSounds.pressureCallout());
        }
        if (threeCardsLeft) {
            candidates.add(PackSounds.threeCardsWarning());
        }
        playRandomEffectAll(candidates);
    }

    private void playSound(UUID playerId, String soundKey, float volume, float pitch) {
        Player player = onlinePlayer(playerId);
        if (player != null) {
            player.playSound(player.getLocation(), soundKey, volume, pitch);
        }
    }

    private void playRoundMusic() {
        stopMusicAll();
        int epoch = ++musicEpoch;
        startMusicTrack(PackSounds.openingBgm(), epoch);
    }

    private void stopMusicAll() {
        musicEpoch++;
        String activeTrack = currentMusicKey;
        currentMusicKey = null;
        for (UUID seat : seats) {
            Player player = onlinePlayer(seat);
            if (player == null) {
                continue;
            }
            if (activeTrack != null && !activeTrack.isBlank()) {
                player.stopSound(activeTrack);
            }
            for (String bgm : PackSounds.bgmTracks()) {
                if (bgm.equals(activeTrack)) {
                    continue;
                }
                player.stopSound(bgm);
            }
        }
    }

    private void stopCurrentMusicTrack() {
        if (currentMusicKey == null || currentMusicKey.isBlank()) {
            return;
        }
        for (UUID seat : seats) {
            Player player = onlinePlayer(seat);
            if (player != null) {
                player.stopSound(currentMusicKey);
            }
        }
    }

    private void stopBgmTracks(Player player) {
        for (String bgm : PackSounds.bgmTracks()) {
            player.stopSound(bgm);
        }
    }

    private void updateMusicState() {
        if (!canScheduleTasks() || phase == GamePhase.LOBBY) {
            return;
        }
        String desired;
        if (shouldUseExcitedBgm()) {
            desired = PackSounds.excitedBgm();
        } else if (currentMusicKey == null
            || currentMusicKey.equals(PackSounds.openingBgm())
            || currentMusicKey.equals(PackSounds.excitedBgm())) {
            desired = PackSounds.nextBgmTrack(currentMusicKey);
        } else {
            return;
        }
        if (!Objects.equals(currentMusicKey, desired)) {
            startMusicTrack(desired, musicEpoch);
        }
    }

    private boolean shouldUseExcitedBgm() {
        return phase == GamePhase.PLAYING
            && hands.values().stream().anyMatch(hand -> hand.size() == 3);
    }

    private String nextScheduledMusicTrack(String previousTrack) {
        return shouldUseExcitedBgm() ? PackSounds.excitedBgm() : PackSounds.nextBgmTrack(previousTrack);
    }

    private void startMusicTrack(String soundKey, int epoch) {
        if (!canScheduleTasks() || soundKey == null || soundKey.isBlank()) {
            return;
        }
        for (UUID seat : seats) {
            Player player = onlinePlayer(seat);
            if (player != null) {
                stopBgmTracks(player);
            }
        }
        currentMusicKey = soundKey;
        playSoundAll(soundKey, plugin.getBgmVolume(), 1.0f);
        scheduleNextMusic(soundKey, epoch);
    }

    private void scheduleNextMusic(String soundKey, int epoch) {
        if (!canScheduleTasks()) {
            return;
        }
        long delay = PackSounds.bgmDurationTicks(soundKey);
        plugin.getServer().getScheduler().runTaskLater(plugin, () -> {
            if (epoch != musicEpoch || phase == GamePhase.LOBBY) {
                return;
            }
            startMusicTrack(nextScheduledMusicTrack(soundKey), epoch);
        }, delay);
    }

    private void refreshPhysicalTable() {
        plugin.getPhysicalTableManager().refresh(this);
    }

    private String playerName(UUID playerId) {
        return displayName(playerId);
    }

    public UUID resolveBotId(String token) {
        if (token == null || token.isBlank()) {
            return botNames.keySet().stream().reduce((first, second) -> second).orElse(null);
        }
        int numericId;
        try {
            numericId = Integer.parseInt(token.trim());
        } catch (NumberFormatException exception) {
            throw new IllegalStateException("bot id 必须是数字。");
        }
        List<UUID> matches = botNames.keySet().stream()
            .filter(id -> Objects.equals(plugin.getBotNumericId(id), numericId))
            .toList();
        if (matches.isEmpty()) {
            throw new IllegalStateException("找不到 bot id: " + token);
        }
        return matches.getFirst();
    }

    private void armTurnCountdown() {
        int seconds = currentTurnTimeoutSeconds();
        if (currentTurn == null || isBot(currentTurn) || seconds <= 0) {
            clearTurnCountdown();
            return;
        }
        turnDeadlineMillis = System.currentTimeMillis() + seconds * 1000L;
        lastCountdownSecond = Integer.MIN_VALUE;
    }

    private void clearTurnCountdown() {
        turnDeadlineMillis = -1L;
        lastCountdownSecond = Integer.MIN_VALUE;
    }

    private int remainingCountdownSeconds() {
        if (turnDeadlineMillis < 0L || currentTurn == null || isBot(currentTurn)) {
            return 0;
        }
        long remainingMillis = Math.max(0L, turnDeadlineMillis - System.currentTimeMillis());
        return (int) Math.ceil(remainingMillis / 1000.0);
    }

    private int currentTurnTimeoutSeconds() {
        return phase == GamePhase.DOUBLING ? 6 : plugin.getTurnCountdownSeconds();
    }

    private Component buildPersistentActionBar(UUID viewerId, int remainingSeconds) {
        if (phase == GamePhase.LOBBY) {
            return buildLobbyActionBar(viewerId);
        }
        if (currentTurn == null) {
            return text("牌桌正在整理下一轮。", NamedTextColor.GRAY);
        }
        if (isBot(currentTurn)) {
            return append(text("当前由 ", NamedTextColor.GRAY), identity(currentTurn, NamedTextColor.YELLOW), text(" 正在思考。", NamedTextColor.GRAY));
        }
        String countdown = currentTurnTimeoutSeconds() > 0 ? " | " + remainingSeconds + " 秒" : "";
        return switch (phase) {
            case BIDDING -> viewerId.equals(currentTurn)
                ? text((bidRound == 1 ? "轮到你定叫分" : "轮到你抢地主") + " · 点桌边按钮确认" + countdown, NamedTextColor.AQUA)
                : append(text("当前由 ", NamedTextColor.GRAY), identity(currentTurn, NamedTextColor.YELLOW), text((bidRound == 1 ? " 正在定叫分" : " 正在抢地主") + countdown, NamedTextColor.GRAY));
            case DOUBLING -> viewerId.equals(currentTurn)
                ? text("轮到你决定加倍或不加倍 · 6 秒内点桌边按钮" + countdown, NamedTextColor.AQUA)
                : append(text("当前由 ", NamedTextColor.GRAY), identity(currentTurn, NamedTextColor.YELLOW), text(" 正在决定是否加倍" + countdown, NamedTextColor.GRAY));
            case PLAYING -> viewerId.equals(currentTurn)
                ? text("轮到你出牌了 · 已选 " + getSelection(currentTurn).size() + " 张" + countdown, NamedTextColor.AQUA)
                : append(text("当前由 ", NamedTextColor.GRAY), identity(currentTurn, NamedTextColor.YELLOW), text(" 在出牌" + countdown, NamedTextColor.GRAY));
            case LOBBY -> text("还在等大家入座准备。", NamedTextColor.GRAY);
        };
    }

    private Component buildLobbyActionBar(UUID viewerId) {
        if (seats.isEmpty()) {
            return text("等待玩家加入。", NamedTextColor.GRAY);
        }
        if (seats.size() < PLAYER_COUNT) {
            return text("等待更多玩家入座 · " + seats.size() + "/" + PLAYER_COUNT, NamedTextColor.GRAY);
        }
        List<String> unreadyNames = seats.stream()
            .filter(seat -> !readyPlayers.contains(seat))
            .filter(seat -> !isBot(seat))
            .map(this::displayName)
            .toList();
        if (unreadyNames.isEmpty()) {
            return MuzTheme.success("全员就绪").append(MuzTheme.divider(" · ")).append(MuzTheme.muted("任意一位可开始"));
        }
        return text("未准备：", NamedTextColor.YELLOW)
            .append(MuzTheme.warning(String.join("、", unreadyNames)));
    }

    private void warnUnreadyPlayersForStartAttempt() {
        if (seats.size() < PLAYER_COUNT) {
            return;
        }
        List<UUID> unreadySeats = seats.stream()
            .filter(seat -> !readyPlayers.contains(seat))
            .filter(seat -> !isBot(seat))
            .toList();
        if (unreadySeats.isEmpty()) {
            return;
        }
        long now = System.currentTimeMillis();
        if (now < lobbyUiResumeAtMillis) {
            long remainingMillis = lobbyUiResumeAtMillis - now;
            if (canScheduleTasks() && delayedUnreadyReminderAtMillis < lobbyUiResumeAtMillis) {
                delayedUnreadyReminderAtMillis = lobbyUiResumeAtMillis;
                long delayTicks = Math.max(1L, (remainingMillis + 49L) / 50L);
                plugin.getServer().getScheduler().runTaskLater(plugin, () -> {
                    delayedUnreadyReminderAtMillis = 0L;
                    warnUnreadyPlayersForStartAttempt();
                }, delayTicks);
            }
            return;
        }
        if (now - lastLobbyWarningSoundAt < 500L) {
            return;
        }
        lastLobbyWarningSoundAt = now;
        DoudizhuPlugin.ConfiguredSound sound = plugin.unreadyWarningSound();
        for (UUID seat : unreadySeats) {
            Player player = onlinePlayer(seat);
            if (player != null) {
                if (sound.volume() > 0.0f) {
                    player.playSound(player.getLocation(), sound.key(), sound.volume(), sound.pitch());
                }
                player.showTitle(Title.title(
                    MINI.deserialize("<!i><gradient:#ff9ec7:#ffd670><bold>就差你没准备啦！</bold></gradient>"),
                    MINI.deserialize("<!i><#fff7fb>大家都在等你点一下 <#7ee7c1><bold>准备</bold></#7ee7c1>，马上就能开局啦</#fff7fb>"),
                    Title.Times.times(Duration.ofMillis(180), Duration.ofMillis(1800), Duration.ofMillis(320))
                ));
            }
        }
    }

    private Map<UUID, DoudizhuPlugin.SettlementResult> broadcastEconomySettlement(Map<UUID, Integer> scoreDeltas) {
        Map<UUID, DoudizhuPlugin.SettlementResult> settlementSnapshots = new LinkedHashMap<>();
        if (scoreDeltas.isEmpty()) {
            return settlementSnapshots;
        }
        if (!plugin.isDoudizhuRoomEconomyEnabled(roomLevel)) {
            if (!plugin.isChipPaymentEnabled()) {
                return settlementSnapshots;
            }
        }
        for (Map.Entry<UUID, Integer> entry : scoreDeltas.entrySet()) {
            UUID playerId = entry.getKey();
            int scoreDelta = entry.getValue();
            if (scoreDelta == 0 || isBot(playerId)) {
                continue;
            }
            DoudizhuPlugin.SettlementResult result = plugin.settleDoudizhuCurrency(roomLevel, playerId, scoreDelta);
            settlementSnapshots.put(playerId, result);
        }
        return settlementSnapshots;
    }

    private void sendRoundChatBundles(List<UUID> winners, Map<UUID, DoudizhuPlugin.SettlementResult> settlementSnapshots, Map<UUID, Integer> scoreDeltas) {
        for (UUID seat : seats) {
            if (isBot(seat)) {
                continue;
            }
            Player player = onlinePlayer(seat);
            if (player == null) {
                continue;
            }
            boolean winner = winners.contains(seat);
            DoudizhuPlugin.SettlementResult result = displaySettlementResult(seat, settlementSnapshots, scoreDeltas);
            Component message = MuzTheme.banner("斗地主", name + " 号桌", winner ? MuzTheme.success("本局结果") : MuzTheme.danger("本局结果"))
                .append(Component.newline())
                .append(settlementSummaryComponent(winners, landlord != null && winners.contains(landlord)))
                .append(Component.newline())
                .append(settlementLine(seat, result));
            List<UUID> others = seats.stream()
                .filter(other -> !other.equals(seat))
                .toList();
            if (!others.isEmpty()) {
                for (UUID other : others) {
                    DoudizhuPlugin.SettlementResult otherResult = displaySettlementResult(other, settlementSnapshots, scoreDeltas);
                    message = message.append(Component.newline())
                        .append(settlementLine(other, otherResult));
                }
            }
            player.sendMessage(message.decoration(TextDecoration.ITALIC, false));
        }
    }

    private Component settlementLine(UUID playerId, DoudizhuPlugin.SettlementResult result) {
        String amount = plugin.formatCompactAmount(Math.abs(result.delta()));
        PlayerRole role = getRole(playerId);
        Component line = identity(playerId, NamedTextColor.WHITE)
            .append(MuzTheme.divider(" · "))
            .append(role == null ? MuzTheme.muted("玩家") : role == PlayerRole.LANDLORD ? MuzTheme.landlord(role.displayName()) : MuzTheme.farmer(role.displayName()));
        if (Math.abs(result.delta()) > 0.0001) {
            line = line.append(MuzTheme.divider(" · "))
                .append(result.delta() >= 0
                    ? MuzTheme.success("赢了 " + amount + result.unitLabel())
                    : MuzTheme.danger("输了 " + amount + result.unitLabel()));
        } else {
            line = line.append(MuzTheme.divider(" · "))
                .append(MuzTheme.muted("持平"));
        }
        return MuzTheme.plain(line);
    }

    private DoudizhuPlugin.SettlementResult displaySettlementResult(UUID playerId, Map<UUID, DoudizhuPlugin.SettlementResult> settlementSnapshots, Map<UUID, Integer> scoreDeltas) {
        DoudizhuPlugin.SettlementResult stored = settlementSnapshots.get(playerId);
        if (stored != null) {
            return stored;
        }
        int scoreDelta = scoreDeltas.getOrDefault(playerId, 0);
        if (plugin.isChipPaymentEnabled()) {
            double chipDelta = Math.round(scoreDelta * plugin.roomMultiplier(roomLevel));
            return new DoudizhuPlugin.SettlementResult(chipDelta, 0.0, 0.0, false, false, "筹码");
        }
        if (plugin.isDoudizhuRoomEconomyEnabled(roomLevel)) {
            double currencyDelta = scoreDelta * plugin.doudizhuCurrencyPerPoint(roomLevel);
            return new DoudizhuPlugin.SettlementResult(currencyDelta, 0.0, 0.0, false, false, "金币");
        }
        return new DoudizhuPlugin.SettlementResult(scoreDelta, 0.0, 0.0, false, false, "分");
    }

    private void recordPlayedHand(UUID playerId) {
        playedHandCounts.merge(playerId, 1, Integer::sum);
    }

    private int playedHands(UUID playerId) {
        return playedHandCounts.getOrDefault(playerId, 0);
    }

    private int farmerPlayedHands() {
        if (landlord == null) {
            return 0;
        }
        return seats.stream()
            .filter(seat -> !seat.equals(landlord))
            .mapToInt(this::playedHands)
            .sum();
    }

    private boolean hasSpring(boolean landlordWin) {
        if (landlord == null) {
            return false;
        }
        return landlordWin ? farmerPlayedHands() == 0 : playedHands(landlord) <= 1;
    }

    private int springMultiplier(boolean landlordWin) {
        return hasSpring(landlordWin) ? 2 : 1;
    }

    private String springLabel(boolean landlordWin) {
        return landlordWin ? "春天" : "反春";
    }

    private int farmerSeatCount() {
        return landlord == null ? 0 : (int) seats.stream().filter(seat -> !seat.equals(landlord)).count();
    }

    private int boostedFarmerCount() {
        if (landlord == null) {
            return 0;
        }
        return (int) seats.stream()
            .filter(seat -> !seat.equals(landlord))
            .filter(seat -> farmerBoostFactor(seat) > 1)
            .count();
    }

    private int liveCoreScore() {
        return Math.max(1, highestBid) * bombMultiplier;
    }

    private int resolvedCoreScore(boolean landlordWin) {
        return liveCoreScore() * springMultiplier(landlordWin);
    }

    private int landlordBoostFactor() {
        return landlordBoostFactor == null ? 1 : Math.max(1, landlordBoostFactor);
    }

    private int farmerBoostFactor(UUID seat) {
        Integer factor = farmerBoostChoices.get(seat);
        return factor == null ? 1 : Math.max(1, factor);
    }

    private int seatPairFactor(UUID seat) {
        if (seat == null || Objects.equals(seat, landlord)) {
            return 1;
        }
        return farmerBoostFactor(seat) * landlordBoostFactor();
    }

    public boolean isMingPai(UUID playerId) {
        return mingPaiPlayers.contains(playerId);
    }

    private String doublingActionText(boolean landlordTurn, int boostFactor, boolean mingPai, boolean autoSkipped) {
        if (autoSkipped) {
            return " 超时跳过";
        }
        if (mingPai) {
            return " 明牌";
        }
        if (boostFactor >= 4) {
            return " 加倍";
        }
        if (boostFactor >= 2) {
            return " 加倍";
        }
        return " 不加倍";
    }

    private Component doublingActionComponent(boolean landlordTurn, int boostFactor, boolean mingPai, boolean autoSkipped) {
        if (autoSkipped) {
            return MuzTheme.muted("跳过");
        }
        if (mingPai) {
            return MuzTheme.warm("明牌");
        }
        if (boostFactor >= 4) {
            return MuzTheme.hotMetric("加倍", "x2");
        }
        if (boostFactor >= 2) {
            return MuzTheme.hotMetric("加倍", "x2");
        }
        return MuzTheme.muted("不加倍");
    }

    private String doublingActionDetail(boolean landlordTurn, int boostFactor, boolean mingPai, boolean autoSkipped) {
        if (autoSkipped) {
            return "6 秒内未操作，已自动跳过";
        }
        if (mingPai) {
            return "你的手牌将对全桌公开";
        }
        if (boostFactor >= 4) {
            return landlordTurn ? "本局对位倍率直接提升到 x4" : "你这一侧的对位倍率提升到 x4";
        }
        if (boostFactor >= 2) {
            return landlordTurn ? "地主侧倍率 x2" : "农民侧倍率 x2";
        }
        return "保持当前倍率";
    }

    private String pairMultiplierSummary(boolean resolved, boolean landlordWin) {
        int core = resolved ? resolvedCoreScore(landlordWin) : liveCoreScore();
        List<Integer> values = seats.stream()
            .filter(seat -> !Objects.equals(seat, landlord))
            .map(this::seatPairFactor)
            .distinct()
            .sorted()
            .map(factor -> core * factor)
            .toList();
        if (values.isEmpty()) {
            return "x" + core;
        }
        return values.stream()
            .map(value -> "x" + value)
            .collect(Collectors.joining("/"));
    }

    public Component currentMultiplierBannerComponent() {
        if (highestBid <= 0 || landlord == null) {
            return MuzTheme.muted("当前倍数 等待叫分");
        }
        int peak = currentMultiplierPeak(false, false);
        return multiplierTone("当前倍数", peak)
            .append(Component.text(" ").decoration(TextDecoration.ITALIC, false))
            .append(MuzTheme.multiplierToken(pairMultiplierSummary(false, false)));
    }

    private Component liveMultiplierComponent() {
        Component line = MuzTheme.warm("底分 " + Math.max(1, highestBid) + " 分")
            .append(MuzTheme.divider(" · "))
            .append(MuzTheme.hotMetric("炸弹", MuzTheme.multiplierToken("x" + bombMultiplier)));
        if (landlord != null) {
            line = line.append(MuzTheme.divider(" · "))
                .append(MuzTheme.hotMetric("农民加倍", boostedFarmerCount() + "/" + farmerSeatCount(), "人"));
            if (landlordBoostFactor != null) {
                line = line.append(MuzTheme.divider(" · "))
                    .append(landlordBoostFactor > 1 ? MuzTheme.hotMetric("地主加倍", MuzTheme.multiplierToken("x" + landlordBoostFactor)) : MuzTheme.muted("地主不加倍"));
            }
            if (!mingPaiPlayers.isEmpty()) {
                line = line.append(MuzTheme.divider(" · "))
                    .append(MuzTheme.hotMetric("明牌", String.valueOf(mingPaiPlayers.size()), "人"));
            }
        }
        return line.append(MuzTheme.divider(" · "))
            .append(MuzTheme.multiplierToken(pairMultiplierSummary(false, false)));
    }

    private Component resolvedMultiplierComponent(boolean landlordWin) {
        Component line = liveMultiplierComponent();
        if (hasSpring(landlordWin)) {
            line = line.append(MuzTheme.divider(" · "))
                .append(MuzTheme.warning(springLabel(landlordWin)).append(MuzTheme.space()).append(MuzTheme.multiplierToken("x2")));
        }
        return line.append(MuzTheme.divider(" · "))
            .append(MuzTheme.hotMetric("结算", MuzTheme.multiplierToken(pairMultiplierSummary(true, landlordWin))));
    }

    private Component settlementSummaryComponent(List<UUID> winners, boolean landlordWin) {
        List<Component> lines = new ArrayList<>();
        lines.add(
            orderedPlayersComponent(winners)
                .append(MuzTheme.divider(" · "))
                .append(landlordWin ? MuzTheme.landlord("地主") : MuzTheme.farmer("农民"))
        );
        lines.add(MuzTheme.hotMetric("最终倍数", MuzTheme.multiplierToken(pairMultiplierSummary(true, landlordWin))));
        lines.add(landlordWin ? MuzTheme.landlord("地主阵营胜出") : MuzTheme.farmer("农民阵营胜出"));
        lines.add(MuzTheme.hotMetric("本局核心", MuzTheme.multiplierToken("x" + resolvedCoreScore(landlordWin))));
        lines.add(MuzTheme.hotMetric("叫分", MuzTheme.multiplierToken("x" + Math.max(1, highestBid))));
        lines.add(MuzTheme.hotMetric("炸弹", MuzTheme.multiplierToken("x" + bombMultiplier)));
        if (landlord != null && farmerSeatCount() > 0) {
            lines.add(MuzTheme.hotMetric("农民加倍", boostedFarmerCount() + "/" + farmerSeatCount(), "人"));
        }
        if (landlordBoostFactor != null && landlordBoostFactor > 1) {
            lines.add(MuzTheme.hotMetric("地主加倍", MuzTheme.multiplierToken("x" + landlordBoostFactor)));
        }
        if (!mingPaiPlayers.isEmpty()) {
            lines.add(MuzTheme.hotMetric("明牌", String.valueOf(mingPaiPlayers.size()), "人"));
        }
        if (hasSpring(landlordWin)) {
            lines.add(MuzTheme.warning(springLabel(landlordWin)).append(MuzTheme.space()).append(MuzTheme.multiplierToken("x2")));
        }
        lines.add(MuzTheme.hotMetric("结算倍数", MuzTheme.multiplierToken(pairMultiplierSummary(true, landlordWin))));
        Component result = Component.empty();
        for (int index = 0; index < lines.size(); index++) {
            if (index > 0) {
                result = result.append(Component.newline());
            }
            result = result.append(lines.get(index));
        }
        return MuzTheme.plain(result);
    }

    private int currentMultiplierPeak(boolean resolved, boolean landlordWin) {
        int core = resolved ? resolvedCoreScore(landlordWin) : liveCoreScore();
        return seats.stream()
            .filter(seat -> !Objects.equals(seat, landlord))
            .mapToInt(seat -> core * seatPairFactor(seat))
            .max()
            .orElse(core);
    }

    private Component multiplierTone(String content, int peak) {
        if (peak <= 1) {
            return MuzTheme.warm(content);
        }
        if (peak <= 2) {
            return MuzTheme.multiplierWarm(content);
        }
        if (peak <= 4) {
            return MuzTheme.multiplierHot(content);
        }
        return MuzTheme.multiplierBlaze(content);
    }

    private Component orderedPlayersComponent(List<UUID> players) {
        Component line = Component.empty();
        for (int index = 0; index < players.size(); index++) {
            if (index > 0) {
                line = line.append(MuzTheme.divider(" -> "));
            }
            line = line.append(identity(players.get(index), NamedTextColor.WHITE));
        }
        return MuzTheme.plain(line);
    }

    private Component actorUpdate(UUID playerId, Component tag, String detail) {
        return actorUpdate(identity(playerId, NamedTextColor.WHITE), tag, detail);
    }

    private Component actorUpdate(Component actor, Component tag, String detail) {
        Component line = MuzTheme.plain(actor)
            .append(MuzTheme.divider(" · "))
            .append(MuzTheme.plain(tag));
        if (detail != null && !detail.isBlank()) {
            line = line.append(MuzTheme.divider(" · "))
                .append(MuzTheme.body(detail));
        }
        return MuzTheme.plain(line);
    }

    private Component compactLobbyEvent(UUID playerId, Component primary, Component secondary) {
        return compactLobbyEvent(identity(playerId, NamedTextColor.WHITE), primary, secondary);
    }

    private Component compactLobbyEvent(Component actor, Component primary, Component secondary) {
        Component line = MuzTheme.plain(actor)
            .append(Component.text(" "))
            .append(MuzTheme.plain(primary));
        if (secondary != null) {
            line = line.append(Component.text(" "))
                .append(MuzTheme.plain(secondary));
        }
        return MuzTheme.plain(line);
    }

    private void recordLobbyEntry(Component line) {
        if (line == null) {
            return;
        }
        recentLobbyEntries.add(0, MuzTheme.plain(line));
        if (recentLobbyEntries.size() > 5) {
            recentLobbyEntries.remove(recentLobbyEntries.size() - 1);
        }
    }

    private void reseedLobbyEntries() {
        recentLobbyEntries.clear();
        for (UUID seat : seats) {
            if (recentLobbyEntries.size() >= 5) {
                break;
            }
            recentLobbyEntries.add(MuzTheme.plain(lobbySnapshotLine(seat)));
        }
    }

    private Component lobbySnapshotLine(UUID seat) {
        return compactLobbyEvent(
            seat,
            readyPlayers.contains(seat) ? MuzTheme.success("就绪") : MuzTheme.muted("未准备"),
            null
        );
    }

    private void recordTrickEntry(UUID playerId, List<DoudizhuCard> cards, CardPattern pattern) {
        if (playerId == null || cards == null || cards.isEmpty() || pattern == null) {
            return;
        }
        Component line = MuzTheme.orange("上一手")
            .append(MuzTheme.divider(" · "))
            .append(identity(playerId, NamedTextColor.WHITE))
            .append(MuzTheme.divider(" · "))
            .append(MuzTheme.orange(describeCards(cards, pattern)));
        recentTrickEntries.add(new RecentTrickEntry(line, System.currentTimeMillis()));
        if (recentTrickEntries.size() > 5) {
            recentTrickEntries.remove(0);
        }
    }

    private void pruneExpiredTrickEntries(long nowMillis) {
        recentTrickEntries.removeIf(entry -> nowMillis - entry.createdAtMillis() > 12000L);
    }

    private Component styleRecentTrickLine(Component line, long ageMillis, int index, int total) {
        if (ageMillis < 4000L) {
            return line;
        }
        if (ageMillis < 8000L) {
            return MuzTheme.plain(line).color(NamedTextColor.GRAY);
        }
        return MuzTheme.plain(line).color(NamedTextColor.DARK_GRAY);
    }

    private String describeCards(List<DoudizhuCard> cards, CardPattern pattern) {
        Map<CardRank, Long> counts = cards.stream()
            .collect(Collectors.groupingBy(DoudizhuCard::rank, LinkedHashMap::new, Collectors.counting()));
        return switch (pattern.type()) {
            case SINGLE -> repeatRank(pattern.primaryRank(), 1);
            case PAIR -> repeatRank(pattern.primaryRank(), 2);
            case TRIPLE -> repeatRank(pattern.primaryRank(), 3);
            case TRIPLE_WITH_SINGLE, TRIPLE_WITH_PAIR ->
                repeatRank(pattern.primaryRank(), 3) + "带" + attachmentText(counts, List.of(pattern.primaryRank()));
            case FOUR_WITH_TWO_SINGLES, FOUR_WITH_TWO_PAIRS ->
                repeatRank(pattern.primaryRank(), 4) + "带" + attachmentText(counts, List.of(pattern.primaryRank()));
            case STRAIGHT -> chainText(pattern.primaryRank(), pattern.chainLength(), 1);
            case PAIR_STRAIGHT -> chainText(pattern.primaryRank(), pattern.chainLength(), 2);
            case AIRPLANE ->
                chainText(pattern.primaryRank(), pattern.chainLength(), 3);
            case AIRPLANE_WITH_SINGLES, AIRPLANE_WITH_PAIRS -> {
                List<CardRank> mains = chainRanks(pattern.primaryRank(), pattern.chainLength());
                yield chainText(pattern.primaryRank(), pattern.chainLength(), 3) + "带" + attachmentText(counts, mains);
            }
            case BOMB -> repeatRank(pattern.primaryRank(), 4);
            case JOKER_BOMB -> "王炸";
        };
    }

    private record RecentTrickEntry(Component component, long createdAtMillis) {
    }

    private String attachmentText(Map<CardRank, Long> counts, List<CardRank> excluded) {
        return counts.entrySet().stream()
            .filter(entry -> !excluded.contains(entry.getKey()))
            .sorted(Map.Entry.comparingByKey(CardRank.NATURAL))
            .map(entry -> repeatRank(entry.getKey(), entry.getValue().intValue()))
            .collect(Collectors.joining());
    }

    private String chainText(CardRank primaryRank, int chainLength, int repeatCount) {
        return chainRanks(primaryRank, chainLength).stream()
            .map(rank -> repeatRank(rank, repeatCount))
            .collect(Collectors.joining());
    }

    private List<CardRank> chainRanks(CardRank primaryRank, int chainLength) {
        List<CardRank> ranks = new ArrayList<>(chainLength);
        int start = primaryRank.strength() - chainLength + 1;
        for (int strength = start; strength <= primaryRank.strength(); strength++) {
            ranks.add(rankByStrength(strength));
        }
        return ranks;
    }

    private CardRank rankByStrength(int strength) {
        for (CardRank rank : CardRank.values()) {
            if (rank.strength() == strength) {
                return rank;
            }
        }
        throw new IllegalArgumentException("未知点数: " + strength);
    }

    private String repeatRank(CardRank rank, int times) {
        return rank.label().repeat(Math.max(1, times));
    }

    private void runBotActionIfNeeded() {
        // 用 epoch 防止旧的延迟任务在状态变化后误触发
        if (!canScheduleTasks() || currentTurn == null || !isBot(currentTurn)) {
            return;
        }
        int epoch = botActionEpoch;
        UUID botId = currentTurn;
        GamePhase scheduledPhase = phase;
        long delay = plugin.randomBotActionDelayTicks(random);
        plugin.getServer().getScheduler().runTaskLater(plugin, () -> {
            if (epoch != botActionEpoch || currentTurn == null || !isBot(currentTurn) || !Objects.equals(currentTurn, botId) || phase != scheduledPhase) {
                return;
            }
            if (scheduledPhase == GamePhase.BIDDING) {
                executeBotBid(botId, epoch);
                return;
            }
            if (scheduledPhase == GamePhase.DOUBLING) {
                executeBotDouble(botId, epoch);
                return;
            }
            if (scheduledPhase == GamePhase.PLAYING) {
                executeBotPlay(botId, epoch);
            }
        }, delay);
    }

    private boolean canScheduleTasks() {
        return plugin.isEnabled() && !plugin.isShuttingDown();
    }

    private void executeBotBid(UUID botId, int epoch) {
        if (requestAiBidDecision(botId, epoch)) {
            return;
        }
        executeLocalBotBid(botId);
    }

    private void executeLocalBotBid(UUID botId) {
        List<DoudizhuCard> hand = hands.getOrDefault(botId, List.of());
        int desired = SimpleBotBrain.chooseBid(hand);
        int points = desired;
        if (bidRound == 1 && points != 0 && points < highestBid) {
            points = 0;
        }
        processBidChoice(botId, points);
    }

    private void executeBotDouble(UUID botId, int epoch) {
        if (requestAiDoublingDecision(botId, epoch)) {
            return;
        }
        executeLocalBotDouble(botId);
    }

    private void executeLocalBotDouble(UUID botId) {
        List<DoudizhuCard> hand = hands.getOrDefault(botId, List.of());
        int strength = SimpleBotBrain.chooseBid(hand);
        boolean mingPai = strength >= 3;
        int boostFactor;
        if (strength >= 4) {
            boostFactor = 4;
        } else if (strength >= 2) {
            boostFactor = 2;
        } else {
            boostFactor = 1;
        }
        processDoublingChoice(botId, boostFactor, mingPai && boostFactor == 1, false);
    }

    private void processBidChoice(UUID playerId, int points) {
        String bidSound = PackSounds.bid(points);
        if (bidRound == 1) {
            bids.put(playerId, points);
            if (points > highestBid || (points == highestBid && points > 0)) {
                highestBid = points;
                highestBidder = playerId;
            }
            announceAction(displayName(playerId) + (points == 0 ? " 不叫" : " 叫分 " + points), actorUpdate(playerId, points == 0 ? MuzTheme.muted("不叫") : MuzTheme.accent("叫分"), points == 0 ? "这轮先不叫地主" : points + " 分"));
            if (points == 3) {
                confirmLandlord(playerId, 3, bidSound);
                return;
            }
            playEffectAll(bidSound);
            advanceFirstBidRound(playerId);
            return;
        }

        tieBreakBids.put(playerId, points);
        announceAction(displayName(playerId) + (points == 0 ? " 不抢" : " 抢地主 " + points), actorUpdate(playerId, points == 0 ? MuzTheme.muted("不抢") : MuzTheme.accent("抢地主"), points == 0 ? "这轮先不抢地主" : points + " 分"));
        if (points == 3) {
            confirmLandlord(playerId, Math.max(highestBid, 3), bidSound);
            return;
        }
        playEffectAll(bidSound);
        int currentIndex = tieBreakOrder.indexOf(playerId);
        if (currentIndex == tieBreakOrder.size() - 1) {
            resolveTieBreakRound();
            return;
        }
        currentTurn = tieBreakOrder.get(currentIndex + 1);
        promptBidTurn();
        refreshPhysicalTable();
        runBotActionIfNeeded();
    }

    private void processDoublingChoice(UUID playerId, int boostFactor, boolean mingPai, boolean autoSkipped) {
        boolean landlordTurn = Objects.equals(playerId, landlord);
        int normalizedFactor = boostFactor > 1 ? 2 : 1;
        mingPai = false;
        if (mingPai) {
            mingPaiPlayers.add(playerId);
            playEffectAll(PackSounds.mingPai());
        } else if (normalizedFactor > 1) {
            playEffectAll(normalizedFactor >= 4 ? PackSounds.superDouble() : PackSounds.doubleChoice(true, landlordTurn));
        } else {
            playEffectAll(PackSounds.doubleChoice(false, landlordTurn));
        }
        if (landlordTurn) {
            landlordBoostFactor = normalizedFactor;
            announceAction(
                displayName(playerId) + doublingActionText(landlordTurn, normalizedFactor, mingPai, autoSkipped),
                actorUpdate(playerId, doublingActionComponent(landlordTurn, normalizedFactor, mingPai, autoSkipped), doublingActionDetail(landlordTurn, normalizedFactor, mingPai, autoSkipped)))
            ;
        } else {
            farmerBoostChoices.put(playerId, normalizedFactor);
            announceAction(
                displayName(playerId) + doublingActionText(landlordTurn, normalizedFactor, mingPai, autoSkipped),
                actorUpdate(playerId, doublingActionComponent(landlordTurn, normalizedFactor, mingPai, autoSkipped), doublingActionDetail(landlordTurn, normalizedFactor, mingPai, autoSkipped)))
            ;
        }
        int currentIndex = doublingOrder.indexOf(playerId);
        if (currentIndex < 0 || currentIndex == doublingOrder.size() - 1) {
            startPlayPhase();
            refreshPhysicalTable();
            runBotActionIfNeeded();
            return;
        }
        currentTurn = doublingOrder.get(currentIndex + 1);
        promptDoublingTurn();
        refreshPhysicalTable();
        runBotActionIfNeeded();
    }

    private void advanceFirstBidRound(UUID playerId) {
        int currentIndex = bidOrder.indexOf(playerId);
        if (currentIndex == bidOrder.size() - 1) {
            if (highestBidder == null) {
        announceAction("无人叫分", MuzTheme.field("发牌", MuzTheme.danger("无人叫分").append(MuzTheme.divider(" · ")).append(MuzTheme.muted("这局重新洗牌再来"))));
                dealFreshRound();
                openHandsForAll();
                promptBidTurn();
                refreshPhysicalTable();
                runBotActionIfNeeded();
                return;
            }
            List<UUID> tiedHighest = bidOrder.stream()
                .filter(id -> bids.getOrDefault(id, 0) == highestBid && highestBid > 0)
                .toList();
            if (tiedHighest.size() <= 1) {
                confirmLandlord(highestBidder, highestBid);
                return;
            }
            startTieBreakRound(tiedHighest);
            return;
        }

        currentTurn = bidOrder.get(currentIndex + 1);
        promptBidTurn();
        refreshPhysicalTable();
        runBotActionIfNeeded();
    }

    private void startTieBreakRound(List<UUID> tiedHighest) {
        bidRound = 2;
        tieBreakOrder = List.copyOf(tiedHighest);
        tieBreakBids.clear();
        currentTurn = tieBreakOrder.get(0);
        announceAction("同分加赛", MuzTheme.field("抢地主", MuzTheme.warm("同分加赛").append(MuzTheme.divider(" · ")).append(orderedPlayersComponent(tieBreakOrder))));
        promptBidTurn();
        refreshPhysicalTable();
        runBotActionIfNeeded();
    }

    private void resolveTieBreakRound() {
        int bestBid = -1;
        UUID winner = highestBidder;
        for (UUID playerId : tieBreakOrder) {
            int value = tieBreakBids.getOrDefault(playerId, 0);
            if (value >= bestBid) {
                bestBid = value;
                winner = playerId;
            }
        }
        confirmLandlord(winner, Math.max(highestBid, Math.max(0, bestBid)));
    }

    private void executeBotPlay(UUID botId, int epoch) {
        if (requestAiPlayDecision(botId, epoch)) {
            return;
        }
        executeLocalBotPlay(botId);
    }

    private void executeLocalBotPlay(UUID botId) {
        List<DoudizhuCard> hand = hands.getOrDefault(botId, List.of());
        List<DoudizhuCard> move = SimpleBotBrain.choosePlay(hand, leadPlayer != null && !Objects.equals(leadPlayer, botId) ? currentPattern : null);
        if (move.isEmpty()) {
            if (leadPlayer == null || Objects.equals(leadPlayer, botId)) {
                move = List.of(hand.getLast());
            } else {
                clearSelection(botId);
                playEffectAll(PackSounds.autoPass());
        announceAction(displayName(botId) + " 不要", actorUpdate(botId, MuzTheme.muted("不要"), "这轮先不压牌"));
                UUID next = nextSeat(botId);
                if (Objects.equals(next, leadPlayer)) {
                    currentTurn = leadPlayer;
                    currentPattern = null;
                    currentTrickCards = List.of();
        announceAction(displayName(leadPlayer) + " 获得先手", actorUpdate(leadPlayer, MuzTheme.warm("先手"), "拿到这一轮的先手"));
                } else {
                    currentTurn = next;
                }
                promptPlayTurn();
                refreshHands();
                refreshPhysicalTable();
                runBotActionIfNeeded();
                return;
            }
        }

        applyBotMove(botId, move);
    }

    private void applyBotMove(UUID botId, List<DoudizhuCard> move) {
        List<DoudizhuCard> hand = hands.getOrDefault(botId, List.of());
        CardPattern pattern = PatternAnalyzer.analyze(move)
            .orElseThrow(() -> new IllegalStateException("机器人生成了非法牌型。"));

        hand.removeAll(move);
        hand.sort(DoudizhuCard.ORDER);

        UUID previousLead = leadPlayer;
        currentPattern = pattern;
        currentTrickCards = List.copyOf(move);
        leadPlayer = botId;
        boolean pressurePlay = previousLead != null && !Objects.equals(previousLead, botId);
        boolean multiplierRaised = pattern.type().isBombFamily();
        recordPlayedHand(botId);
        if (multiplierRaised) {
            bombMultiplier *= 2;
        }

        boolean threeCardsLeft = hand.size() == 3;
        playPatternVoice(pattern, pattern.primaryRank(), pressurePlay, threeCardsLeft);
        announceAction(
            displayName(botId) + " " + pattern.displayName(),
            actorUpdate(botId, MuzTheme.success(pattern.displayName()), move.stream().map(DoudizhuCard::displayLabel).collect(Collectors.joining(" ")))
        );
        recordTrickEntry(botId, move, pattern);
        if (multiplierRaised) {
            announceAction("倍率抬升", MuzTheme.field("倍率", liveMultiplierComponent()));
        }

        if (hand.isEmpty()) {
            finishRound(botId);
            return;
        }

        currentTurn = nextSeat(botId);
        promptPlayTurn();
        refreshHands();
        refreshPhysicalTable();
        runBotActionIfNeeded();
    }

    private void performTimedOutPlay(UUID playerId, List<DoudizhuCard> move) {
        List<DoudizhuCard> hand = hands.getOrDefault(playerId, List.of());
        CardPattern pattern = PatternAnalyzer.analyze(move)
            .orElseThrow(() -> new IllegalStateException("超时托管生成了非法牌型。"));

        hand.removeAll(move);
        hand.sort(DoudizhuCard.ORDER);
        clearSelection(playerId);

        UUID previousLead = leadPlayer;
        currentPattern = pattern;
        currentTrickCards = List.copyOf(move);
        leadPlayer = playerId;
        boolean pressurePlay = previousLead != null && !Objects.equals(previousLead, playerId);
        boolean multiplierRaised = pattern.type().isBombFamily();
        recordPlayedHand(playerId);
        if (multiplierRaised) {
            bombMultiplier *= 2;
        }

        boolean threeCardsLeft = hand.size() == 3;
        playPatternVoice(pattern, pattern.primaryRank(), pressurePlay, threeCardsLeft);
        announceAction(
            displayName(playerId) + " 超时托管出牌",
            actorUpdate(playerId, MuzTheme.warning("超时托管"), move.stream().map(DoudizhuCard::displayLabel).collect(Collectors.joining(" ")))
        );
        recordTrickEntry(playerId, move, pattern);
        if (multiplierRaised) {
            announceAction("倍率抬升", MuzTheme.field("倍率", liveMultiplierComponent()));
        }
        if (hand.isEmpty()) {
            finishRound(playerId);
            return;
        }
        currentTurn = nextSeat(playerId);
        promptPlayTurn();
        refreshHands();
        refreshPhysicalTable();
        runBotActionIfNeeded();
    }

    private void performTimedOutPass(UUID playerId) {
        clearSelection(playerId);
        playEffectAll(PackSounds.autoPass());
        announceAction(displayName(playerId) + " 超时托管不要", actorUpdate(playerId, MuzTheme.muted("超时托管"), "这轮自动不要"));
        UUID next = nextSeat(playerId);
        if (Objects.equals(next, leadPlayer)) {
            currentTurn = leadPlayer;
            currentPattern = null;
            currentTrickCards = List.of();
            announceAction(displayName(leadPlayer) + " 获得先手", actorUpdate(leadPlayer, MuzTheme.warm("先手"), "拿到这一轮的先手"));
        } else {
            currentTurn = next;
        }
        promptPlayTurn();
        refreshHands();
        refreshPhysicalTable();
        runBotActionIfNeeded();
    }

    private boolean requestAiBidDecision(UUID botId, int epoch) {
        AiChatGateway gateway = plugin.getAiChatGateway();
        if (!plugin.isBotAiEnabled() || gateway == null) {
            return false;
        }
        String prompt = buildBidAiPrompt(botId);
        gateway.chatAsync(new AiChatGateway.ChatRequest(
                List.of(
                    AiChatGateway.Message.system(botAiSystemPrompt()),
                    AiChatGateway.Message.user(prompt)
                ),
                plugin.aiModelName(),
                0.2,
                80
            ))
            .orTimeout(plugin.getBotAiTimeoutMs(), TimeUnit.MILLISECONDS)
            .whenComplete((response, error) -> plugin.getServer().getScheduler().runTask(plugin, () -> {
                if (!isAiDecisionStillValid(botId, epoch, GamePhase.BIDDING)) {
                    return;
                }
                Integer parsed = error == null ? parseAiBidDecision(response) : null;
                plugin.recordBotAiTrace(
                    DoudizhuPlugin.BotGameType.DOUDIZHU,
                    botId,
                    name,
                    "BIDDING",
                    prompt,
                    response,
                    parsed == null ? "" : String.valueOf(parsed),
                    parsed != null,
                    parsed == null ? "fallback_local" : "",
                    error == null ? "" : error.getMessage()
                );
                if (parsed == null) {
                    executeLocalBotBid(botId);
                    return;
                }
                int points = parsed;
                if (bidRound == 1 && points != 0 && points < highestBid) {
                    points = 0;
                }
                processBidChoice(botId, points);
            }));
        return true;
    }

    private boolean requestAiDoublingDecision(UUID botId, int epoch) {
        AiChatGateway gateway = plugin.getAiChatGateway();
        if (!plugin.isBotAiEnabled() || gateway == null) {
            return false;
        }
        String prompt = buildDoublingAiPrompt(botId);
        gateway.chatAsync(new AiChatGateway.ChatRequest(
                List.of(
                    AiChatGateway.Message.system(botAiSystemPrompt()),
                    AiChatGateway.Message.user(prompt)
                ),
                plugin.aiModelName(),
                0.2,
                100
            ))
            .orTimeout(plugin.getBotAiTimeoutMs(), TimeUnit.MILLISECONDS)
            .whenComplete((response, error) -> plugin.getServer().getScheduler().runTask(plugin, () -> {
                if (!isAiDecisionStillValid(botId, epoch, GamePhase.DOUBLING)) {
                    return;
                }
                String parsed = error == null ? parseAiKeywordDecision(response) : null;
                plugin.recordBotAiTrace(
                    DoudizhuPlugin.BotGameType.DOUDIZHU,
                    botId,
                    name,
                    "DOUBLING",
                    prompt,
                    response,
                    parsed == null ? "" : parsed,
                    parsed != null,
                    parsed == null ? "fallback_local" : "",
                    error == null ? "" : error.getMessage()
                );
                if (parsed == null) {
                    executeLocalBotDouble(botId);
                    return;
                }
                switch (parsed) {
                    case "DOUBLE" -> processDoublingChoice(botId, 2, false, false);
                    default -> processDoublingChoice(botId, 1, false, false);
                }
            }));
        return true;
    }

    private boolean requestAiPlayDecision(UUID botId, int epoch) {
        AiChatGateway gateway = plugin.getAiChatGateway();
        if (!plugin.isBotAiEnabled() || gateway == null) {
            return false;
        }
        String prompt = buildPlayAiPrompt(botId);
        gateway.chatAsync(new AiChatGateway.ChatRequest(
                List.of(
                    AiChatGateway.Message.system(botAiSystemPrompt()),
                    AiChatGateway.Message.user(prompt)
                ),
                plugin.aiModelName(),
                0.2,
                140
            ))
            .orTimeout(plugin.getBotAiTimeoutMs(), TimeUnit.MILLISECONDS)
            .whenComplete((response, error) -> plugin.getServer().getScheduler().runTask(plugin, () -> {
                if (!isAiDecisionStillValid(botId, epoch, GamePhase.PLAYING)) {
                    return;
                }
                List<DoudizhuCard> aiMove = error == null ? parseAiPlayDecision(botId, response) : null;
                String parsedDecision = aiMove == null
                    ? ""
                    : aiMove.isEmpty()
                        ? "PASS"
                        : aiMove.stream().map(card -> Integer.toString(card.id())).collect(Collectors.joining(","));
                plugin.recordBotAiTrace(
                    DoudizhuPlugin.BotGameType.DOUDIZHU,
                    botId,
                    name,
                    "PLAYING",
                    prompt,
                    response,
                    parsedDecision,
                    aiMove != null,
                    aiMove == null ? "fallback_local" : "",
                    error == null ? "" : error.getMessage()
                );
                if (aiMove == null) {
                    executeLocalBotPlay(botId);
                    return;
                }
                if (aiMove.isEmpty()) {
                    if (leadPlayer == null || Objects.equals(leadPlayer, botId)) {
                        executeLocalBotPlay(botId);
                        return;
                    }
                    clearSelection(botId);
                    playEffectAll(PackSounds.autoPass());
                    announceAction(displayName(botId) + " 不要", actorUpdate(botId, MuzTheme.muted("不要"), "这轮先不压牌"));
                    UUID next = nextSeat(botId);
                    if (Objects.equals(next, leadPlayer)) {
                        currentTurn = leadPlayer;
                        currentPattern = null;
                        currentTrickCards = List.of();
                        announceAction(displayName(leadPlayer) + " 获得先手", actorUpdate(leadPlayer, MuzTheme.warm("先手"), "拿到这一轮的先手"));
                    } else {
                        currentTurn = next;
                    }
                    promptPlayTurn();
                    refreshHands();
                    refreshPhysicalTable();
                    runBotActionIfNeeded();
                    return;
                }
                applyBotMove(botId, aiMove);
            }));
        return true;
    }

    private boolean isAiDecisionStillValid(UUID botId, int epoch, GamePhase expectedPhase) {
        return canScheduleTasks()
            && epoch == botActionEpoch
            && phase == expectedPhase
            && currentTurn != null
            && Objects.equals(currentTurn, botId)
            && isBot(botId);
    }

    private void handleTimedOutPlayerTurn(UUID playerId, int epoch) {
        if (pendingTimedOutPlayDecisionPlayer != null
            && pendingTimedOutPlayDecisionPlayer.equals(playerId)
            && pendingTimedOutPlayDecisionEpoch == epoch) {
            return;
        }
        pendingTimedOutPlayDecisionPlayer = playerId;
        pendingTimedOutPlayDecisionEpoch = epoch;
        if (requestAiTimedOutPlayDecision(playerId, epoch)) {
            return;
        }
        executeDefaultTimedOutPlayDecision(playerId);
    }

    private boolean requestAiTimedOutPlayDecision(UUID playerId, int epoch) {
        AiChatGateway gateway = plugin.getAiChatGateway();
        if (!plugin.isDeepseekAiEnabled() || gateway == null || !gateway.isEnabled()) {
            return false;
        }
        String prompt = buildTimedOutPlayAiPrompt(playerId);
        gateway.chatAsync(new AiChatGateway.ChatRequest(
                List.of(
                    AiChatGateway.Message.system(timedOutPlayAiSystemPrompt()),
                    AiChatGateway.Message.user(prompt)
                ),
                plugin.aiModelName(),
                0.2,
                140
            ))
            .orTimeout(plugin.getBotAiTimeoutMs(), TimeUnit.MILLISECONDS)
            .whenComplete((response, error) -> plugin.getServer().getScheduler().runTask(plugin, () -> {
                if (!isTimedOutPlayDecisionStillValid(playerId, epoch)) {
                    clearPendingTimedOutPlayDecision(playerId, epoch);
                    return;
                }
                List<DoudizhuCard> aiMove = error == null ? parseAiPlayDecision(playerId, response) : null;
                if (aiMove == null) {
                    executeDefaultTimedOutPlayDecision(playerId);
                    return;
                }
                if (aiMove.isEmpty()) {
                    if (leadPlayer == null || Objects.equals(leadPlayer, playerId)) {
                        executeDefaultTimedOutPlayDecision(playerId);
                        return;
                    }
                    clearPendingTimedOutPlayDecision(playerId, epoch);
                    performTimedOutPass(playerId);
                    return;
                }
                clearPendingTimedOutPlayDecision(playerId, epoch);
                performTimedOutPlay(playerId, aiMove);
            }));
        return true;
    }

    private void executeDefaultTimedOutPlayDecision(UUID playerId) {
        List<DoudizhuCard> hand = hands.getOrDefault(playerId, List.of());
        List<DoudizhuCard> move = SimpleBotBrain.choosePlay(hand, leadPlayer != null && !Objects.equals(leadPlayer, playerId) ? currentPattern : null);
        clearPendingTimedOutPlayDecision(playerId, pendingTimedOutPlayDecisionEpoch);
        if (move.isEmpty()) {
            if (leadPlayer == null || Objects.equals(leadPlayer, playerId)) {
                if (!hand.isEmpty()) {
                    move = List.of(hand.getLast());
                } else {
                    return;
                }
            } else {
                performTimedOutPass(playerId);
                return;
            }
        }
        performTimedOutPlay(playerId, move);
    }

    private boolean isTimedOutPlayDecisionStillValid(UUID playerId, int epoch) {
        return canScheduleTasks()
            && epoch == botActionEpoch
            && phase == GamePhase.PLAYING
            && currentTurn != null
            && Objects.equals(currentTurn, playerId)
            && !isBot(playerId);
    }

    private void clearPendingTimedOutPlayDecision(UUID playerId, int epoch) {
        if (pendingTimedOutPlayDecisionPlayer != null
            && pendingTimedOutPlayDecisionPlayer.equals(playerId)
            && pendingTimedOutPlayDecisionEpoch == epoch) {
            pendingTimedOutPlayDecisionPlayer = null;
            pendingTimedOutPlayDecisionEpoch = Integer.MIN_VALUE;
        }
    }

    private String timedOutPlayAiSystemPrompt() {
        return "你是 MUZ 斗地主超时托管决策引擎。现在要替一名超时玩家自动出牌。"
            + "你只能依据给出的牌桌状态选择一个最合适、最稳妥、合法的出牌方案。"
            + "你必须只输出 PASS 或者手牌 id 列表，不要解释。";
    }

    private String buildTimedOutPlayAiPrompt(UUID playerId) {
        return """
            当前阶段：玩家超时托管出牌
            玩家名字：%s
            上一手：%s
            你的手牌：
            %s
            只允许两种输出：
            1. PASS
            2. 只输出手牌 id，用英文逗号分隔，例如：12,18
            规则：
            - 如果你是先手，不能输出 PASS
            - 必须选择当前最合适、合法、尽量稳妥的一手
            - 不要输出解释
            """.formatted(
            displayName(playerId),
            currentPattern == null || currentTrickCards.isEmpty() ? "无，你是先手或这一轮已重置" : describeCards(currentTrickCards, currentPattern),
            handSummaryLines(hands.getOrDefault(playerId, List.of()))
        );
    }

    private String botAiSystemPrompt() {
        return "你是 MUZ 斗地主机器人决策引擎。你只能根据给出的牌桌信息做决策。"
            + "你必须只输出要求的结果格式，不要解释，不要寒暄，不要额外标点。"
            + "如果拿不准，也必须在允许格式里给出一个保守合法答案。";
    }

    private String buildBidAiPrompt(UUID botId) {
        List<DoudizhuCard> hand = hands.getOrDefault(botId, List.of());
        String roundText = bidRound == 1 ? "第一轮叫分" : "同分加赛抢地主";
        return """
            当前阶段：%s
            你的名字：%s
            当前最高分：%d
            你的手牌：%s
            只输出一个数字：0 或 1 或 2 或 3
            规则：
            - 不想叫分就输出 0
            - 第一轮时，非零分不能低于当前最高分
            - 不要输出任何解释
            """.formatted(roundText, displayName(botId), highestBid, handSummary(hand));
    }

    private String buildDoublingAiPrompt(UUID botId) {
        return """
            当前阶段：加倍决策
            你的名字：%s
            你的身份：%s
            当前基础倍率：%d
            你的手牌：%s
            只输出一个词：PASS 或 DOUBLE
            规则：
            - PASS 表示不加倍
            - DOUBLE 表示加倍
            - 不要输出解释
            """.formatted(
            displayName(botId),
            Objects.equals(botId, landlord) ? "地主" : "农民",
            Math.max(1, highestBid) * bombMultiplier,
            handSummary(hands.getOrDefault(botId, List.of()))
        );
    }

    private String buildPlayAiPrompt(UUID botId) {
        List<DoudizhuCard> hand = hands.getOrDefault(botId, List.of());
        String targetText = currentPattern == null || currentTrickCards.isEmpty()
            ? "无，你是先手或这一轮已重置"
            : describeCards(currentTrickCards, currentPattern);
        String leadText = leadPlayer == null ? "无" : displayName(leadPlayer);
        return """
            当前阶段：出牌
            你的名字：%s
            当前先手：%s
            上一手：%s
            你的手牌：
            %s
            只允许两种输出：
            1. PASS
            2. 只输出手牌 id，用英文逗号分隔，例如：12,18
            规则：
            - 如果你是先手，不能输出 PASS
            - 只能从你当前手牌里选 id
            - 必须保证选出的牌是合法牌型；如果要压上一手，必须能压过
            - 不要输出任何解释
            """.formatted(displayName(botId), leadText, targetText, handSummaryLines(hand));
    }

    private String handSummary(List<DoudizhuCard> hand) {
        return hand.stream()
            .map(card -> card.id() + ":" + card.displayLabel())
            .collect(Collectors.joining(" "));
    }

    private String handSummaryLines(List<DoudizhuCard> hand) {
        return hand.stream()
            .map(card -> "- " + card.id() + " = " + card.displayLabel())
            .collect(Collectors.joining("\n"));
    }

    private Integer parseAiBidDecision(AiChatGateway.ChatResponse response) {
        String content = aiDecisionContent(response);
        if (content.isBlank()) {
            return null;
        }
        for (String token : content.replaceAll("[^0-3]", " ").trim().split("\\s+")) {
            if (token.matches("[0-3]")) {
                return Integer.parseInt(token);
            }
        }
        return null;
    }

    private String parseAiKeywordDecision(AiChatGateway.ChatResponse response) {
        String content = aiDecisionContent(response).toUpperCase(Locale.ROOT).replace('-', '_').replace(' ', '_');
        if (content.contains("DOUBLE")) {
            return "DOUBLE";
        }
        if (content.contains("PASS")) {
            return "PASS";
        }
        return null;
    }

    private List<DoudizhuCard> parseAiPlayDecision(UUID botId, AiChatGateway.ChatResponse response) {
        String content = aiDecisionContent(response).trim();
        if (content.isBlank()) {
            return null;
        }
        if (content.toUpperCase(Locale.ROOT).contains("PASS")) {
            return List.of();
        }
        List<Integer> ids = new ArrayList<>();
        for (String token : content.replaceAll("[^0-9,]", "").split(",")) {
            if (!token.isBlank()) {
                try {
                    ids.add(Integer.parseInt(token));
                } catch (NumberFormatException ignored) {
                }
            }
        }
        if (ids.isEmpty()) {
            return null;
        }
        List<DoudizhuCard> hand = new ArrayList<>(hands.getOrDefault(botId, List.of()));
        List<DoudizhuCard> chosen = new ArrayList<>();
        for (Integer id : ids) {
            DoudizhuCard matched = hand.stream().filter(card -> card.id() == id).findFirst().orElse(null);
            if (matched == null) {
                return null;
            }
            chosen.add(matched);
            hand.remove(matched);
        }
        CardPattern analyzed = PatternAnalyzer.analyze(chosen).orElse(null);
        if (analyzed == null) {
            return null;
        }
        if (leadPlayer != null && !Objects.equals(leadPlayer, botId) && currentPattern != null && !analyzed.canBeat(currentPattern)) {
            return null;
        }
        return chosen;
    }

    private String aiDecisionContent(AiChatGateway.ChatResponse response) {
        if (response == null) {
            return "";
        }
        String content = response.content();
        if (content == null || content.isBlank()) {
            content = response.reasoningContent();
        }
        return content == null ? "" : content.trim();
    }
}

