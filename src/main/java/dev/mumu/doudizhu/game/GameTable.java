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
    private final TableMusicCoordinator musicCoordinator;
    private final TableEffectCoordinator effectCoordinator;
    private final TimedOutPlayCoordinator timedOutPlayCoordinator;
    private final BotAiCoordinator botAiCoordinator;
    private final RoundSettlementCoordinator roundSettlementCoordinator;

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
    private int bombMultiplier = 1;
    private CardPattern currentPattern;
    private List<DoudizhuCard> currentTrickCards = List.of();
    private int botActionEpoch = 0;
    private long roundStartedAtMillis = -1L;
    private long turnDeadlineMillis = -1L;
    private int lastCountdownSecond = Integer.MIN_VALUE;
    private long lastLobbyWarningSoundAt;
    private long lobbyUiResumeAtMillis;
    private long delayedUnreadyReminderAtMillis;
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
        this.musicCoordinator = new TableMusicCoordinator(
            plugin,
            this::canScheduleTasks,
            () -> phase,
            () -> hands,
            () -> seats,
            this::onlinePlayer
        );
        this.effectCoordinator = new TableEffectCoordinator(
            plugin,
            random,
            () -> seats,
            this::onlinePlayer
        );
        this.timedOutPlayCoordinator = new TimedOutPlayCoordinator(new TimedOutPlayCoordinator.Support() {
            @Override
            public DoudizhuPlugin plugin() {
                return plugin;
            }

            @Override
            public boolean canScheduleTasks() {
                return GameTable.this.canScheduleTasks();
            }

            @Override
            public int botActionEpoch() {
                return botActionEpoch;
            }

            @Override
            public GamePhase phase() {
                return phase;
            }

            @Override
            public UUID currentTurn() {
                return currentTurn;
            }

            @Override
            public boolean isBot(UUID playerId) {
                return GameTable.this.isBot(playerId);
            }

            @Override
            public UUID leadPlayer() {
                return leadPlayer;
            }

            @Override
            public boolean isDeepseekAiEnabled() {
                return plugin.isDeepseekAiEnabled();
            }

            @Override
            public dev.mumu.doudizhu.ai.AiChatGateway aiGateway() {
                return plugin.getAiChatGateway();
            }

            @Override
            public String aiModelName() {
                return plugin.aiModelName();
            }

            @Override
            public int botAiTimeoutMs() {
                return plugin.getBotAiTimeoutMs();
            }

            @Override
            public String timedOutPlayAiSystemPrompt() {
                return GameTable.this.timedOutPlayAiSystemPrompt();
            }

            @Override
            public String buildTimedOutPlayAiPrompt(UUID playerId) {
                return GameTable.this.buildTimedOutPlayAiPrompt(playerId);
            }

            @Override
            public List<DoudizhuCard> parseAiPlayDecision(UUID playerId, dev.mumu.doudizhu.ai.AiChatGateway.ChatResponse response) {
                return GameTable.this.parseAiPlayDecision(playerId, response);
            }

            @Override
            public void executeDefaultTimedOutPlayDecision(UUID playerId) {
                GameTable.this.executeDefaultTimedOutPlayDecision(playerId);
            }

            @Override
            public void performTimedOutPass(UUID playerId) {
                GameTable.this.performTimedOutPass(playerId);
            }

            @Override
            public void performTimedOutPlay(UUID playerId, List<DoudizhuCard> move) {
                GameTable.this.performTimedOutPlay(playerId, move);
            }
        });
        this.botAiCoordinator = new BotAiCoordinator(new BotAiCoordinator.Support() {
            @Override
            public DoudizhuPlugin plugin() {
                return plugin;
            }

            @Override
            public boolean canScheduleTasks() {
                return GameTable.this.canScheduleTasks();
            }

            @Override
            public int botActionEpoch() {
                return botActionEpoch;
            }

            @Override
            public GamePhase phase() {
                return phase;
            }

            @Override
            public UUID currentTurn() {
                return currentTurn;
            }

            @Override
            public boolean isBot(UUID botId) {
                return GameTable.this.isBot(botId);
            }

            @Override
            public String tableName() {
                return name;
            }

            @Override
            public String aiModelName() {
                return plugin.aiModelName();
            }

            @Override
            public int botAiTimeoutMs() {
                return plugin.getBotAiTimeoutMs();
            }

            @Override
            public boolean isBotAiEnabled() {
                return plugin.isBotAiEnabled();
            }

            @Override
            public dev.mumu.doudizhu.ai.AiChatGateway aiGateway() {
                return plugin.getAiChatGateway();
            }

            @Override
            public String botAiSystemPrompt() {
                return GameTable.this.botAiSystemPrompt();
            }

            @Override
            public String buildBidAiPrompt(UUID botId) {
                return GameTable.this.buildBidAiPrompt(botId);
            }

            @Override
            public String buildDoublingAiPrompt(UUID botId) {
                return GameTable.this.buildDoublingAiPrompt(botId);
            }

            @Override
            public String buildPlayAiPrompt(UUID botId) {
                return GameTable.this.buildPlayAiPrompt(botId);
            }

            @Override
            public Integer parseAiBidDecision(dev.mumu.doudizhu.ai.AiChatGateway.ChatResponse response) {
                return GameTable.this.parseAiBidDecision(response);
            }

            @Override
            public String parseAiKeywordDecision(dev.mumu.doudizhu.ai.AiChatGateway.ChatResponse response) {
                return GameTable.this.parseAiKeywordDecision(response);
            }

            @Override
            public List<DoudizhuCard> parseAiPlayDecision(UUID botId, dev.mumu.doudizhu.ai.AiChatGateway.ChatResponse response) {
                return GameTable.this.parseAiPlayDecision(botId, response);
            }

            @Override
            public void recordTrace(UUID botId, String stage, String prompt, dev.mumu.doudizhu.ai.AiChatGateway.ChatResponse response, String parsedDecision, boolean applied, String fallbackReason, String errorMessage) {
                GameTable.this.recordBotAiTrace(botId, stage, prompt, response, parsedDecision, applied, fallbackReason, errorMessage);
            }

            @Override
            public int normalizeBidDecision(int points) {
                return GameTable.this.normalizeBidDecision(points);
            }

            @Override
            public void executeLocalBotBid(UUID botId) {
                GameTable.this.executeLocalBotBid(botId);
            }

            @Override
            public void executeLocalBotDouble(UUID botId) {
                GameTable.this.executeLocalBotDouble(botId);
            }

            @Override
            public void executeLocalBotPlay(UUID botId) {
                GameTable.this.executeLocalBotPlay(botId);
            }

            @Override
            public void processBidChoice(UUID botId, int points) {
                GameTable.this.processBidChoice(botId, points);
            }

            @Override
            public void processDoublingChoice(UUID botId, int boostFactor, boolean autoSkipped) {
                GameTable.this.processDoublingChoice(botId, boostFactor, autoSkipped);
            }

            @Override
            public void applyBotMove(UUID botId, List<DoudizhuCard> move) {
                GameTable.this.applyBotMove(botId, move);
            }

            @Override
            public void performBotPass(UUID botId) {
                GameTable.this.performBotPass(botId);
            }
        });
        this.roundSettlementCoordinator = new RoundSettlementCoordinator(new RoundSettlementCoordinator.Support() {
            @Override
            public DoudizhuPlugin plugin() {
                return plugin;
            }

            @Override
            public TableLevel roomLevel() {
                return roomLevel;
            }

            @Override
            public List<UUID> seats() {
                return List.copyOf(seats);
            }

            @Override
            public UUID landlord() {
                return landlord;
            }

            @Override
            public int resolvedCoreScore(boolean landlordWin) {
                return GameTable.this.resolvedCoreScore(landlordWin);
            }

            @Override
            public int seatPairFactor(UUID seat) {
                return GameTable.this.seatPairFactor(seat);
            }

            @Override
            public void applyTotalScoreDelta(UUID playerId, int delta) {
                totalScores.merge(playerId, delta, Integer::sum);
            }

            @Override
            public boolean isBot(UUID playerId) {
                return GameTable.this.isBot(playerId);
            }
        });
    }

    public String getName() {
        return name;
    }

    public GamePhase getPhase() {
        return phase;
    }

    public TableLevel getRoomLevel() {
        return roomLevel;
    }

    public void setRoomLevel(TableLevel roomLevel) {
        this.roomLevel = roomLevel == null ? TableLevel.FUN : roomLevel;
    }

    public UUID getLandlord() {
        return landlord;
    }

    public List<UUID> getSeats() {
        return List.copyOf(seats);
    }

    public List<DoudizhuCard> getHand(UUID playerId) {
        return List.copyOf(hands.getOrDefault(playerId, List.of()));
    }

    public Set<Integer> getSelection(UUID playerId) {
        return Set.copyOf(selections.getOrDefault(playerId, Set.of()));
    }

    public PlayerRole getRole(UUID playerId) {
        return roles.get(playerId);
    }

    public int getScore(UUID playerId) {
        return totalScores.getOrDefault(playerId, 0);
    }

    public int getBid(UUID playerId) {
        if (bidRound == 2 && tieBreakBids.containsKey(playerId)) {
            return tieBreakBids.getOrDefault(playerId, 0);
        }
        return bids.getOrDefault(playerId, 0);
    }

    public boolean isBot(UUID playerId) {
        return playerId != null && botNames.containsKey(playerId);
    }

    public boolean contains(UUID playerId) {
        return playerId != null && seats.contains(playerId);
    }

    public boolean isEmpty() {
        return seats.isEmpty();
    }

    public String displayName(UUID playerId) {
        if (playerId == null) {
            return "未知玩家";
        }
        String botName = botNames.get(playerId);
        if (botName != null && !botName.isBlank()) {
            return botName;
        }
        Player online = onlinePlayer(playerId);
        if (online != null) {
            return online.getName();
        }
        String offlineName = Bukkit.getOfflinePlayer(playerId).getName();
        return offlineName == null || offlineName.isBlank() ? playerId.toString().substring(0, 8) : offlineName;
    }

    public UUID getCurrentTurn() {
        return currentTurn;
    }

    public UUID getLeadPlayer() {
        return leadPlayer;
    }

    public CardPattern getCurrentPattern() {
        return currentPattern;
    }

    public List<DoudizhuCard> getCurrentTrickCards() {
        return List.copyOf(currentTrickCards);
    }

    public List<DoudizhuCard> getBottomCards() {
        return List.copyOf(bottomCards);
    }

    public boolean isReady(UUID playerId) {
        return playerId != null && readyPlayers.contains(playerId);
    }

    public UUID addBot(String preferredName) {
        ensurePhase(GamePhase.LOBBY, "开局后不能再加机器人。");
        if (seats.size() >= PLAYER_COUNT) {
            throw new IllegalStateException("牌桌已经满了。");
        }
        UUID botId = UUID.randomUUID();
        String botName = preferredName == null || preferredName.isBlank() ? "Bot-" + nextAvailableBotIndex() : preferredName.trim();
        botNames.put(botId, botName);
        plugin.registerBot(botId, this.name, DoudizhuPlugin.BotGameType.DOUDIZHU);
        occupySeat(botId, true);
        Component update = compactLobbyEvent(
            Component.text(botName, NamedTextColor.WHITE).decoration(TextDecoration.ITALIC, false),
            MuzTheme.accent("加入"),
            MuzTheme.success("就绪")
        );
        announceChat(botName + " 加入 · 就绪", update);
        recordLobbyEntry(update);
        refreshPhysicalTable();
        return botId;
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
        String botName = botNames.get(target);
        discardSeatState(target);
        Component update = compactLobbyEvent(
            Component.text(botName, NamedTextColor.WHITE).decoration(TextDecoration.ITALIC, false),
            MuzTheme.muted("离桌"),
            null
        );
        announceAction(botName + " 离桌", update);
        recordLobbyEntry(update);
        refreshPhysicalTable();
        return target;
    }

    public void addPlayer(Player player) {
        UUID playerId = player.getUniqueId();
        if (contains(playerId)) {
            return;
        }
        ensurePhase(GamePhase.LOBBY, "这一局已经开始了，暂时不能中途加入。");
        if (seats.size() >= PLAYER_COUNT) {
            throw new IllegalStateException("牌桌已满。");
        }
        if (!plugin.canAffordEntry(playerId, roomLevel)) {
            throw new IllegalStateException(plugin.insufficientEntryMessage(playerId, roomLevel));
        }
        occupySeat(playerId, false);
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
        discardSeatState(playerId);
        manager.unregisterPlayer(playerId);
        if (seats.isEmpty() && !plugin.getPhysicalTableManager().isPlaced(name)) {
            manager.unregisterTable(name);
        }
        refreshPhysicalTable();
    }

    private int nextAvailableBotIndex() {
        int index = 1;
        while (botNames.containsValue("Bot-" + index)) {
            index++;
        }
        return index;
    }

    private void occupySeat(UUID playerId, boolean ready) {
        seats.add(playerId);
        totalScores.putIfAbsent(playerId, 0);
        if (ready) {
            readyPlayers.add(playerId);
        }
    }

    private void discardSeatState(UUID playerId) {
        seats.remove(playerId);
        readyPlayers.remove(playerId);
        bids.remove(playerId);
        roles.remove(playerId);
        hands.remove(playerId);
        selections.remove(playerId);
        if (botNames.remove(playerId) != null) {
            plugin.unregisterBot(playerId);
        }
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

    private void ensureRoundCanStart() {
        ensurePhase(GamePhase.LOBBY, "当前不是可开局状态。");
        ensureSeatCountForStart();
        ensureReadyStateForStart();
        ensureSeatEntryEligibility();
    }

    private void ensureSeatCountForStart() {
        if (seats.size() != PLAYER_COUNT) {
            throw new IllegalStateException("斗地主需要刚好 3 位玩家。");
        }
    }

    private void ensureReadyStateForStart() {
        if (readyPlayers.size() != PLAYER_COUNT) {
            warnUnreadyPlayersForStartAttempt();
            throw new IllegalStateException("三位玩家都准备后才能开局。");
        }
    }

    private void ensureSeatEntryEligibility() {
        for (UUID seat : seats) {
            if (!isBot(seat) && !plugin.canAffordEntry(seat, roomLevel)) {
                throw new IllegalStateException(displayName(seat) + " 资格不足: " + plugin.insufficientEntryMessage(seat, roomLevel));
            }
        }
    }

    public void startRound(CommandSender sender) {
        ensureRoundCanStart();
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
        processDoublingChoice(player.getUniqueId(), doubled ? 2 : 1, false);
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
        requireAtTable(player);
        ensurePhase(GamePhase.PLAYING, "当前不是出牌阶段。");
        requireCurrentTurn(player);

        UUID playerId = player.getUniqueId();
        List<DoudizhuCard> chosen = selectedCardsForPlay(playerId);
        ensureSelectedMoveCanBeatCurrentPattern(playerId, chosen);

        MoveResolution resolution = applyMoveResolution(playerId, chosen, true, "这组牌型不合法，不能这样出。");
        CeActionExecutor.executePlayProfile(
            plugin,
            player,
            this,
            resolution.pattern(),
            resolution.move(),
            plugin.resolvePlayActionProfile(playerId, resolution.pattern())
        );
        finalizePlayedMove(
            playerId,
            resolution,
            displayName(playerId) + " " + resolution.pattern().displayName(),
            MuzTheme.success(resolution.pattern().displayName()),
            resolution.cardLabels()
        );
    }

    private List<DoudizhuCard> selectedCardsForPlay(UUID playerId) {
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
        return chosen;
    }

    private void ensureSelectedMoveCanBeatCurrentPattern(UUID playerId, List<DoudizhuCard> chosen) {
        CardPattern pattern = PatternAnalyzer.analyze(chosen)
            .orElseThrow(() -> new IllegalArgumentException("这组牌型不合法，不能这样出。"));
        if (leadPlayer != null && !Objects.equals(leadPlayer, playerId) && currentPattern != null && !pattern.canBeat(currentPattern)) {
            throw new IllegalArgumentException("这手牌压不过上一手。");
        }
    }

    public void pass(Player player) {
        requireAtTable(player);
        ensurePhase(GamePhase.PLAYING, "当前不是出牌阶段。");
        requireCurrentTurn(player);
        UUID playerId = player.getUniqueId();
        if (leadPlayer == null || Objects.equals(leadPlayer, playerId)) {
            throw new IllegalStateException("这轮是你先手，不能直接点不要。");
        }
        finalizePass(playerId, displayName(playerId) + " 不要", MuzTheme.muted("不要"), "这轮先不压牌");
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
            lines.add(statusSeatLine(seat));
        }
        appendCurrentTurnStatusLine(lines);
        appendBidAndMultiplierStatusLines(lines);
        appendTieBreakAndCardStatusLines(lines);
        return lines;
    }

    private Component statusSeatLine(UUID seat) {
        return MuzTheme.row(identity(seat, NamedTextColor.WHITE), statusSeatDetails(seat));
    }

    private List<Component> statusSeatDetails(UUID seat) {
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
        return details;
    }

    private void appendCurrentTurnStatusLine(List<Component> lines) {
        if (currentTurn != null) {
            lines.add(MuzTheme.field(currentTurnStatusLabel(), identity(currentTurn, NamedTextColor.WHITE)));
        }
    }

    private String currentTurnStatusLabel() {
        return switch (phase) {
            case BIDDING -> "当前叫分";
            case DOUBLING -> "当前加倍";
            case PLAYING -> "当前出牌";
            case LOBBY -> "当前操作";
        };
    }

    private void appendBidAndMultiplierStatusLines(List<Component> lines) {
        if (highestBid <= 0 || highestBidder == null) {
            return;
        }
        lines.add(MuzTheme.field(
            bidRound == 1 ? "最高叫分" : "最高抢分",
            MuzTheme.warm(highestBid + " 分").append(MuzTheme.divider(" · ")).append(identity(highestBidder, NamedTextColor.WHITE))
        ));
        lines.add(MuzTheme.field("倍率", liveMultiplierComponent()));
    }

    private void appendTieBreakAndCardStatusLines(List<Component> lines) {
        if (bidRound == 2 && !tieBreakOrder.isEmpty()) {
            lines.add(MuzTheme.field("抢地主顺序", orderedPlayersComponent(tieBreakOrder)));
        }
        if (currentPattern != null && !currentTrickCards.isEmpty()) {
            lines.add(currentTrickPreviewComponent());
        }
        if (!bottomCards.isEmpty()) {
            lines.add(MuzTheme.field("底牌", MuzTheme.warm(bottomCards.stream().map(DoudizhuCard::displayLabel).collect(Collectors.joining(" ")))));
        }
    }

    public String currentTrickPreviewText() {
        return TableStatusViews.currentTrickPreviewText(
            leadPlayer,
            currentPattern,
            currentTrickCards,
            this::playerName,
            this::describeCards
        );
    }

    public Component currentTrickPreviewComponent() {
        return TableStatusViews.currentTrickPreviewComponent(
            leadPlayer,
            currentPattern,
            currentTrickCards,
            playerId -> identity(playerId, NamedTextColor.WHITE),
            this::describeCards
        );
    }

    public List<Component> slidingTrickPreviewComponents(long nowMillis) {
        if (recentTrickEntries.isEmpty()) {
            return List.of(currentTrickPreviewComponent());
        }
        List<RecentTrickEntry> visible = recentTrickEntries.size() <= 5
            ? List.copyOf(recentTrickEntries)
            : List.copyOf(recentTrickEntries.subList(recentTrickEntries.size() - 5, recentTrickEntries.size()));
        List<Component> lines = new ArrayList<>(visible.size());
        for (RecentTrickEntry entry : visible) {
            lines.add(entry.component());
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
        return TableStatusViews.multiplierStatusText(
            phase,
            highestBid,
            landlord,
            bombMultiplier,
            boostedFarmerCount(),
            farmerSeatCount(),
            landlordBoostFactor,
            pairMultiplierSummary(false, false)
        );
    }

    public Component liveMultiplierStatusComponent() {
        return TableStatusViews.multiplierStatusComponent(
            phase,
            highestBid,
            landlord,
            bombMultiplier,
            boostedFarmerCount(),
            farmerSeatCount(),
            landlordBoostFactor,
            pairMultiplierSummary(false, false)
        );
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
        detachAllSeatsForForceClose(reason);
        clearTableStateForForceClose();
    }

    public void tickActionBar() {
        if (seats.isEmpty()) {
            return;
        }
        if (phase == GamePhase.LOBBY) {
            broadcastLobbyActionBarIfVisible();
            return;
        }

        int remaining = remainingCountdownSeconds();
        if (handleExpiredHumanTurn(remaining)) {
            return;
        }
        updateCountdownSoundState(remaining);
        broadcastPersistentActionBar(remaining);
    }

    private void dealFreshRound() {
        prepareFreshRoundState();
        List<DoudizhuCard> deck = DoudizhuDeck.shuffled(random);
        dealHandsFromDeck(deck);
        assignBottomCards(deck);
        bidOrder = seedBidOrder();
        tieBreakOrder = List.of();
        currentTurn = bidOrder.get(0);
    }

    private void confirmLandlord(UUID playerId, int bid) {
        confirmLandlord(playerId, bid, null);
    }

    private void prepareFreshRoundState() {
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
        bombMultiplier = 1;
        readyPlayers.clear();
    }

    private void dealHandsFromDeck(List<DoudizhuCard> deck) {
        for (int index = 0; index < PLAYER_COUNT; index++) {
            List<DoudizhuCard> hand = new ArrayList<>(deck.subList(index * 17, index * 17 + 17));
            hand.sort(DoudizhuCard.ORDER);
            hands.put(seats.get(index), hand);
        }
    }

    private void assignBottomCards(List<DoudizhuCard> deck) {
        bottomCards = new ArrayList<>(deck.subList(51, 54));
        bottomCards.sort(DoudizhuCard.ORDER);
    }

    private List<UUID> seedBidOrder() {
        int startIndex = random.nextInt(PLAYER_COUNT);
        List<UUID> order = new ArrayList<>(PLAYER_COUNT);
        for (int index = 0; index < PLAYER_COUNT; index++) {
            order.add(seats.get((startIndex + index) % PLAYER_COUNT));
        }
        return List.copyOf(order);
    }

    private void assignLandlordRoles() {
        roles.clear();
        for (UUID seat : seats) {
            roles.put(seat, seat.equals(landlord) ? PlayerRole.LANDLORD : PlayerRole.FARMER);
        }
    }

    private void appendBottomCardsToLandlord(UUID playerId) {
        List<DoudizhuCard> landlordHand = new ArrayList<>(hands.getOrDefault(playerId, List.of()));
        landlordHand.addAll(bottomCards);
        landlordHand.sort(DoudizhuCard.ORDER);
        hands.put(playerId, landlordHand);
    }

    private void confirmLandlord(UUID playerId, int bid, String priorTriggerSound) {
        landlord = playerId;
        highestBid = Math.max(1, bid);
        assignLandlordRoles();
        appendBottomCardsToLandlord(playerId);
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
        stopMusicAll();
        RoundSettlementCoordinator.RoundSettlement settlement = roundSettlementCoordinator.settle(winner);
        RoundSettlementView settlementView = settlementView(settlement);
        Component summary = settlementView.summary(orderedPlayersComponent(settlement.winners()));
        // IMPORTANT:
        // Keep round-end chat on a single send path.
        // `sendRoundChatBundles(...)` already includes the full multiplier block, so broadcasting summary here again
        // would resend the same final multiplier text and cause duplicate settlement chat after a round ends.
        setLastActionText(settlement.landlordWin() ? "地主阵营胜出" : "农民阵营胜出", summary);
        plugin.recordDoudizhuMatch(this, settlement.winners(), settlement.scoreDeltas(), settlement.settlementSnapshots());
        sendRoundChatBundles(settlement, settlementView);
        broadcastStickyOutcomeActionBar(settlement.winners());
        for (UUID seat : seats) {
            playEffect(seat, (settlement.landlordWin() == seat.equals(landlord)) ? PackSounds.win() : PackSounds.lose());
        }
        resetRound();
    }

    private void broadcastStickyOutcomeActionBar(List<UUID> winners) {
        lobbyUiResumeAtMillis = System.currentTimeMillis() + 5500L;
        for (int index = 0; index < 5; index++) {
            long delay = index * 20L;
            plugin.scheduler().runLater(delay, () -> {
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
            });
        }
    }

    private void resetRound() {
        stopMusicAll();
        resetRoundStateForLobby();
        plugin.getHandGuiService().closeHands(this);
        refreshPhysicalTable();
        scheduleDebugAutoLoopRestartIfEligible();
    }

    private void detachAllSeatsForForceClose(String reason) {
        for (UUID seat : new ArrayList<>(seats)) {
            if (!isBot(seat)) {
                Player player = onlinePlayer(seat);
                if (player != null) {
                    player.sendMessage(text(reason, NamedTextColor.RED));
                }
                manager.unregisterPlayer(seat);
                continue;
            }
            plugin.unregisterBot(seat);
        }
    }

    private void clearTableStateForForceClose() {
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
        bombMultiplier = 1;
        currentPattern = null;
        currentTrickCards = List.of();
        roundStartedAtMillis = -1L;
        timedOutPlayCoordinator.reset();
        clearTurnCountdown();
        lastLobbyWarningSoundAt = 0L;
        delayedUnreadyReminderAtMillis = 0L;
        phase = GamePhase.LOBBY;
    }

    private void resetRoundStateForLobby() {
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
        bombMultiplier = 1;
        currentPattern = null;
        currentTrickCards = List.of();
        roundStartedAtMillis = -1L;
        timedOutPlayCoordinator.reset();
        clearTurnCountdown();
    }

    private void scheduleDebugAutoLoopRestartIfEligible() {
        if (!canScheduleTasks() || !debugAutoLoop || seats.size() != PLAYER_COUNT || !seats.stream().allMatch(this::isBot)) {
            return;
        }
        plugin.scheduler().runLater(2L, () -> {
            try {
                startRound(plugin.getServer().getConsoleSender());
            } catch (RuntimeException ignored) {
            }
        });
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
        while (shouldAutoPassCurrentTurn()) {
            UUID stuckPlayer = currentTurn;
            performAutoSkippedPass(stuckPlayer);
            if (Objects.equals(currentTurn, leadPlayer)) {
                return;
            }
        }
    }

    private boolean shouldAutoPassCurrentTurn() {
        if (
            phase != GamePhase.PLAYING
                || currentTurn == null
                || leadPlayer == null
                || currentPattern == null
                || Objects.equals(currentTurn, leadPlayer)
        ) {
            return false;
        }
        List<DoudizhuCard> hand = hands.getOrDefault(currentTurn, List.of());
        return !MoveAdvisor.hasAnyBeatingMove(hand, currentPattern);
    }

    private void performAutoSkippedPass(UUID playerId) {
        clearSelection(playerId);
        playEffectAll(PackSounds.autoPass());
        announceAction(displayName(playerId) + " 自动不要", actorUpdate(playerId, MuzTheme.muted("自动不要"), "手里暂时没有更大的牌"));
        advanceAfterResolvedTurn(playerId, false);
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
        effectCoordinator.playSoundAll(soundKey, volume, pitch);
    }

    private void playEffectAll(String soundKey) {
        effectCoordinator.playEffectAll(soundKey);
    }

    private void playEffect(UUID playerId, String soundKey) {
        effectCoordinator.playEffect(playerId, soundKey);
    }

    private void playRandomEffectAll(List<String> soundKeys) {
        effectCoordinator.playRandomEffectAll(soundKeys);
    }

    private void playPatternVoice(CardPattern pattern, CardRank primaryRank, boolean pressurePlay, boolean threeCardsLeft, boolean twoCardsLeft) {
        effectCoordinator.playPatternVoice(pattern, primaryRank, pressurePlay, threeCardsLeft, twoCardsLeft);
    }

    private void playRoundMusic() {
        musicCoordinator.playRoundMusic();
    }

    private void stopMusicAll() {
        musicCoordinator.stopAll();
    }

    private void updateMusicState() {
        musicCoordinator.updateState();
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

    private void broadcastLobbyActionBarIfVisible() {
        if (System.currentTimeMillis() < lobbyUiResumeAtMillis) {
            return;
        }
        broadcastPersistentActionBar(0);
    }

    private boolean handleExpiredHumanTurn(int remaining) {
        if (currentTurn == null || isBot(currentTurn) || remaining > 0) {
            return false;
        }
        if (phase == GamePhase.DOUBLING) {
            processDoublingChoice(currentTurn, 1, true);
            return true;
        }
        if (phase == GamePhase.PLAYING) {
            handleTimedOutPlayerTurn(currentTurn, botActionEpoch);
            return true;
        }
        return false;
    }

    private void updateCountdownSoundState(int remaining) {
        if (currentTurn == null || isBot(currentTurn) || remaining == lastCountdownSecond) {
            return;
        }
        effectCoordinator.playCountdownCue(remaining);
        lastCountdownSecond = remaining;
    }

    private void broadcastPersistentActionBar(int remainingSeconds) {
        for (UUID seat : seats) {
            Player player = onlinePlayer(seat);
            if (player != null) {
                player.sendActionBar(buildPersistentActionBar(player.getUniqueId(), remainingSeconds));
            }
        }
    }

    private Component buildPersistentActionBar(UUID viewerId, int remainingSeconds) {
        return TableStatusViews.persistentActionBar(
            phase,
            viewerId,
            currentTurn,
            isBot(currentTurn),
            bidRound,
            remainingSeconds,
            currentTurn == null ? 0 : getSelection(currentTurn).size(),
            playerId -> identity(playerId, NamedTextColor.YELLOW),
            buildLobbyActionBar(viewerId),
            currentTurnTimeoutSeconds()
        );
    }

    private Component buildLobbyActionBar(UUID viewerId) {
        List<String> unreadyNames = seats.stream()
            .filter(seat -> !readyPlayers.contains(seat))
            .filter(seat -> !isBot(seat))
            .map(this::displayName)
            .toList();
        return TableStatusViews.lobbyActionBar(seats.size(), PLAYER_COUNT, unreadyNames);
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
        if (scheduleDelayedUnreadyReminderIfNeeded(now)) {
            return;
        }
        if (now - lastLobbyWarningSoundAt < 500L) {
            return;
        }
        lastLobbyWarningSoundAt = now;
        playUnreadyWarning(unreadySeats);
    }

    private boolean scheduleDelayedUnreadyReminderIfNeeded(long now) {
        if (now >= lobbyUiResumeAtMillis) {
            return false;
        }
        long remainingMillis = lobbyUiResumeAtMillis - now;
        if (canScheduleTasks() && delayedUnreadyReminderAtMillis < lobbyUiResumeAtMillis) {
            delayedUnreadyReminderAtMillis = lobbyUiResumeAtMillis;
            long delayTicks = Math.max(1L, (remainingMillis + 49L) / 50L);
            plugin.scheduler().runLater(delayTicks, () -> {
                delayedUnreadyReminderAtMillis = 0L;
                warnUnreadyPlayersForStartAttempt();
            });
        }
        return true;
    }

    private void playUnreadyWarning(List<UUID> unreadySeats) {
        DoudizhuPlugin.ConfiguredSound sound = plugin.unreadyWarningSound();
        for (UUID seat : unreadySeats) {
            Player player = onlinePlayer(seat);
            if (player != null) {
                effectCoordinator.playConfiguredSound(seat, sound);
                player.showTitle(Title.title(
                    MINI.deserialize("<!i><gradient:#ff9ec7:#ffd670><bold>就差你没准备啦！</bold></gradient>"),
                    MINI.deserialize("<!i><#fff7fb>大家都在等你点一下 <#7ee7c1><bold>准备</bold></#7ee7c1>，马上就能开局啦</#fff7fb>"),
                    Title.Times.times(Duration.ofMillis(180), Duration.ofMillis(1800), Duration.ofMillis(320))
                ));
            }
        }
    }

    private void sendRoundChatBundles(RoundSettlementCoordinator.RoundSettlement settlement, RoundSettlementView settlementView) {
        Component summary = settlementView.summary(orderedPlayersComponent(settlement.winners()));
        for (UUID seat : seats) {
            if (isBot(seat)) {
                continue;
            }
            Player player = onlinePlayer(seat);
            if (player == null) {
                continue;
            }
            player.sendMessage(roundChatMessageForSeat(seat, settlement, settlementView, summary).decoration(TextDecoration.ITALIC, false));
        }
    }

    private Component roundChatMessageForSeat(
        UUID seat,
        RoundSettlementCoordinator.RoundSettlement settlement,
        RoundSettlementView settlementView,
        Component summary
    ) {
        boolean winner = settlement.winners().contains(seat);
        Component message = MuzTheme.banner("斗地主", name + " 号桌", winner ? MuzTheme.success("本局结果") : MuzTheme.danger("本局结果"))
            .append(Component.newline())
            .append(summary)
            .append(Component.newline())
            .append(roundChatPlayerLine(seat, settlement, settlementView));
        for (UUID other : seats) {
            if (!other.equals(seat)) {
                message = message.append(Component.newline()).append(roundChatPlayerLine(other, settlement, settlementView));
            }
        }
        return message;
    }

    private Component roundChatPlayerLine(
        UUID seat,
        RoundSettlementCoordinator.RoundSettlement settlement,
        RoundSettlementView settlementView
    ) {
        DoudizhuPlugin.SettlementResult result = settlement.displayResultFor(seat, plugin, roomLevel);
        return settlementView.playerLine(
            identity(seat, NamedTextColor.WHITE),
            getRole(seat),
            plugin.formatCompactAmount(Math.abs(result.delta())),
            result.delta(),
            result.unitLabel()
        );
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

    private RoundSettlementView settlementView(RoundSettlementCoordinator.RoundSettlement settlement) {
        return new RoundSettlementView(
            settlement.landlordWin(),
            resolvedCoreScore(settlement.landlordWin()),
            Math.max(1, highestBid),
            bombMultiplier,
            boostedFarmerCount(),
            farmerSeatCount(),
            landlordBoostFactor,
            hasSpring(settlement.landlordWin()),
            springLabel(settlement.landlordWin()),
            pairMultiplierSummary(true, settlement.landlordWin())
        );
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
        plugin.scheduler().runLater(delay, () -> {
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
        });
    }

    private boolean canScheduleTasks() {
        return plugin.isEnabled() && !plugin.isShuttingDown();
    }

    private void executeBotBid(UUID botId, int epoch) {
        if (botAiCoordinator.requestBidDecision(botId, epoch)) {
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
        if (botAiCoordinator.requestDoublingDecision(botId, epoch)) {
            return;
        }
        executeLocalBotDouble(botId);
    }

    private void executeLocalBotDouble(UUID botId) {
        List<DoudizhuCard> hand = hands.getOrDefault(botId, List.of());
        boolean doubled = SimpleBotBrain.chooseDouble(hand);
        processDoublingChoice(botId, doubled ? 2 : 1, false);
    }

    private void refreshAndRunBot() {
        refreshPhysicalTable();
        runBotActionIfNeeded();
    }

    private void continueBidTurn(UUID nextPlayer) {
        currentTurn = nextPlayer;
        promptBidTurn();
        refreshAndRunBot();
    }

    private void continueDoublingTurn(UUID nextPlayer) {
        currentTurn = nextPlayer;
        promptDoublingTurn();
        refreshAndRunBot();
    }

    private void restartBidPhaseAfterRedeal() {
        dealFreshRound();
        openHandsForAll();
        promptBidTurn();
        refreshAndRunBot();
    }

    private void processBidChoice(UUID playerId, int points) {
        String bidSound = PackSounds.bid(points);
        if (bidRound == 1) {
            handleFirstBidChoice(playerId, points, bidSound);
            return;
        }
        handleTieBreakBidChoice(playerId, points, bidSound);
    }

    private void handleFirstBidChoice(UUID playerId, int points, String bidSound) {
        bids.put(playerId, points);
        if (points > highestBid || (points == highestBid && points > 0)) {
            highestBid = points;
            highestBidder = playerId;
        }
        announceAction(
            displayName(playerId) + (points == 0 ? " 不叫" : " 叫分 " + points),
            actorUpdate(playerId, points == 0 ? MuzTheme.muted("不叫") : MuzTheme.accent("叫分"), points == 0 ? "这轮先不叫地主" : points + " 分")
        );
        if (points == 3) {
            confirmLandlord(playerId, 3, bidSound);
            return;
        }
        playEffectAll(bidSound);
        advanceFirstBidRound(playerId);
    }

    private void handleTieBreakBidChoice(UUID playerId, int points, String bidSound) {
        tieBreakBids.put(playerId, points);
        announceAction(
            displayName(playerId) + (points == 0 ? " 不抢" : " 抢地主 " + points),
            actorUpdate(playerId, points == 0 ? MuzTheme.muted("不抢") : MuzTheme.accent("抢地主"), points == 0 ? "这轮先不抢地主" : points + " 分")
        );
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
        continueBidTurn(tieBreakOrder.get(currentIndex + 1));
    }

    private void processDoublingChoice(UUID playerId, int boostFactor, boolean autoSkipped) {
        boolean landlordTurn = Objects.equals(playerId, landlord);
        int normalizedFactor = boostFactor > 1 ? 2 : 1;
        DoublingDecisionView decisionView = DoublingDecisionView.of(landlordTurn, normalizedFactor, autoSkipped);
        playEffectAll(PackSounds.doubleChoice(normalizedFactor > 1, landlordTurn));
        if (landlordTurn) {
            landlordBoostFactor = normalizedFactor;
            announceAction(
                displayName(playerId) + decisionView.actionText(),
                actorUpdate(playerId, decisionView.actionComponent(), decisionView.actionDetail())
            );
        } else {
            farmerBoostChoices.put(playerId, normalizedFactor);
            announceAction(
                displayName(playerId) + decisionView.actionText(),
                actorUpdate(playerId, decisionView.actionComponent(), decisionView.actionDetail())
            );
        }
        int currentIndex = doublingOrder.indexOf(playerId);
        if (currentIndex < 0 || currentIndex == doublingOrder.size() - 1) {
            startPlayPhase();
            refreshAndRunBot();
            return;
        }
        continueDoublingTurn(doublingOrder.get(currentIndex + 1));
    }

    private void advanceFirstBidRound(UUID playerId) {
        int currentIndex = bidOrder.indexOf(playerId);
        if (currentIndex == bidOrder.size() - 1) {
            if (highestBidder == null) {
        announceAction("无人叫分", MuzTheme.field("发牌", MuzTheme.danger("无人叫分").append(MuzTheme.divider(" · ")).append(MuzTheme.muted("这局重新洗牌再来"))));
                restartBidPhaseAfterRedeal();
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
        refreshAndRunBot();
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
        if (botAiCoordinator.requestPlayDecision(botId, epoch)) {
            return;
        }
        executeLocalBotPlay(botId);
    }

    private void executeLocalBotPlay(UUID botId) {
        List<DoudizhuCard> hand = hands.getOrDefault(botId, List.of());
        List<DoudizhuCard> move = SimpleBotBrain.choosePlay(
            hand,
            leadPlayer != null && !Objects.equals(leadPlayer, botId) ? currentPattern : null,
            botPlayContext(botId)
        );
        if (move.isEmpty()) {
            if (leadPlayer == null || Objects.equals(leadPlayer, botId)) {
                move = List.of(hand.getLast());
            } else {
                performBotPass(botId);
                return;
            }
        }

        applyBotMove(botId, move);
    }

    private void applyBotMove(UUID botId, List<DoudizhuCard> move) {
        MoveResolution resolution = applyMoveResolution(botId, move, false, "机器人生成了非法牌型。");
        finalizePlayedMove(
            botId,
            resolution,
            displayName(botId) + " " + resolution.pattern().displayName(),
            MuzTheme.success(resolution.pattern().displayName()),
            resolution.cardLabels()
        );
    }

    private void performTimedOutPlay(UUID playerId, List<DoudizhuCard> move) {
        MoveResolution resolution = applyMoveResolution(playerId, move, true, "超时托管生成了非法牌型。");
        finalizePlayedMove(
            playerId,
            resolution,
            displayName(playerId) + " 超时托管出牌",
            MuzTheme.warning("超时托管"),
            resolution.cardLabels()
        );
    }

    private void performBotPass(UUID botId) {
        finalizePass(botId, displayName(botId) + " 不要", MuzTheme.muted("不要"), "这轮先不压牌");
    }

    private void performTimedOutPass(UUID playerId) {
        finalizePass(playerId, displayName(playerId) + " 超时托管不要", MuzTheme.muted("超时托管"), "这轮自动不要");
    }


    private void handleTimedOutPlayerTurn(UUID playerId, int epoch) {
        timedOutPlayCoordinator.handleTimedOutPlayerTurn(playerId, epoch);
    }

    private MoveResolution applyMoveResolution(UUID playerId, List<DoudizhuCard> move, boolean clearSelectionFirst, String invalidMessage) {
        List<DoudizhuCard> hand = hands.getOrDefault(playerId, List.of());
        CardPattern pattern = PatternAnalyzer.analyze(move)
            .orElseThrow(() -> new IllegalStateException(invalidMessage));
        hand.removeAll(move);
        hand.sort(DoudizhuCard.ORDER);
        if (clearSelectionFirst) {
            clearSelection(playerId);
        }
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
        return new MoveResolution(
            List.copyOf(move),
            pattern,
            move.stream().map(DoudizhuCard::displayLabel).collect(Collectors.joining(" ")),
            pressurePlay,
            multiplierRaised,
            hand.size() == 3,
            hand.size() == 2,
            hand.isEmpty()
        );
    }

    private void finalizePlayedMove(UUID playerId, MoveResolution resolution, String title, Component badge, String detail) {
        playPatternVoice(
            resolution.pattern(),
            resolution.pattern().primaryRank(),
            resolution.pressurePlay(),
            resolution.threeCardsLeft(),
            resolution.twoCardsLeft()
        );
        announceAction(title, actorUpdate(playerId, badge, detail));
        recordTrickEntry(playerId, resolution.move(), resolution.pattern());
        if (resolution.threeCardsLeft()) {
            announceChat(
                displayName(playerId) + " 只剩三张牌了",
                actorUpdate(playerId, MuzTheme.warning("三张预警"), "只剩三张牌了")
            );
        }
        if (resolution.multiplierRaised()) {
            announceAction("倍率抬升", MuzTheme.field("倍率", liveMultiplierComponent()));
        }
        if (resolution.handEmpty()) {
            if (shouldIgnoreEarlyFinish(playerId)) {
                return;
            }
            finishRound(playerId);
            return;
        }
        advanceAfterResolvedTurn(playerId);
    }

    private boolean shouldIgnoreEarlyFinish(UUID winner) {
        if (phase != GamePhase.PLAYING || winner == null || roundStartedAtMillis <= 0L || seats.isEmpty()) {
            return false;
        }
        if (System.currentTimeMillis() - roundStartedAtMillis > 2000L) {
            return false;
        }
        return seats.stream().allMatch(seat -> hands.getOrDefault(seat, List.of()).isEmpty());
    }

    private void finalizePass(UUID playerId, String title, Component badge, String detail) {
        clearSelection(playerId);
        playEffectAll(PackSounds.autoPass());
        announceAction(title, actorUpdate(playerId, badge, detail));
        advanceAfterResolvedTurn(playerId);
    }

    private void advanceAfterResolvedTurn(UUID playerId) {
        advanceAfterResolvedTurn(playerId, true);
    }

    private void advanceAfterResolvedTurn(UUID playerId, boolean continueFlow) {
        UUID next = nextSeat(playerId);
        if (Objects.equals(next, leadPlayer)) {
            currentTurn = leadPlayer;
            currentPattern = null;
            currentTrickCards = List.of();
            announceAction(displayName(leadPlayer) + " 获得先手", actorUpdate(leadPlayer, MuzTheme.warm("先手"), "拿到这一轮的先手"));
        } else {
            currentTurn = next;
        }
        if (!continueFlow) {
            return;
        }
        promptPlayTurn();
        refreshHands();
        refreshPhysicalTable();
        runBotActionIfNeeded();
    }

    private void executeDefaultTimedOutPlayDecision(UUID playerId) {
        List<DoudizhuCard> move = resolveDefaultAutoPlayMove(playerId);
        if (move.isEmpty()) {
            if (canLeadCurrentTrick(playerId)) {
                return;
            }
            performTimedOutPass(playerId);
            return;
        }
        performTimedOutPlay(playerId, move);
    }

    private List<DoudizhuCard> resolveDefaultAutoPlayMove(UUID playerId) {
        List<DoudizhuCard> hand = hands.getOrDefault(playerId, List.of());
        List<DoudizhuCard> move = SimpleBotBrain.choosePlay(hand, canLeadCurrentTrick(playerId) ? null : currentPattern, botPlayContext(playerId));
        if (!move.isEmpty()) {
            return move;
        }
        if (canLeadCurrentTrick(playerId) && !hand.isEmpty()) {
            return List.of(hand.getLast());
        }
        return List.of();
    }

    private boolean canLeadCurrentTrick(UUID playerId) {
        return leadPlayer == null || Objects.equals(leadPlayer, playerId);
    }

    private int normalizeBidDecision(int points) {
        if (bidRound == 1 && points != 0 && points < highestBid) {
            return 0;
        }
        return points;
    }

    private SimpleBotBrain.PlayContext botPlayContext(UUID playerId) {
        List<DoudizhuCard> hand = hands.getOrDefault(playerId, List.of());
        return new SimpleBotBrain.PlayContext(canLeadCurrentTrick(playerId), hand.size(), minOpponentHandCount(playerId));
    }

    private int minOpponentHandCount(UUID playerId) {
        return seats.stream()
            .filter(seat -> !Objects.equals(seat, playerId))
            .filter(seat -> isOpponentSeat(playerId, seat))
            .mapToInt(seat -> hands.getOrDefault(seat, List.of()).size())
            .min()
            .orElse(Integer.MAX_VALUE);
    }

    private boolean isOpponentSeat(UUID viewerId, UUID targetId) {
        if (viewerId == null || targetId == null || Objects.equals(viewerId, targetId) || landlord == null) {
            return false;
        }
        boolean viewerLandlord = Objects.equals(viewerId, landlord);
        boolean targetLandlord = Objects.equals(targetId, landlord);
        return viewerLandlord != targetLandlord;
    }

    private String aiIdentityLabel(UUID playerId) {
        if (playerId == null) {
            return "未知身份";
        }
        if (Objects.equals(playerId, landlord)) {
            return "地主";
        }
        if (landlord != null && seats.contains(playerId)) {
            return "农民";
        }
        return "未定";
    }

    private String aiTableStateSummary(UUID playerId) {
        StringBuilder builder = new StringBuilder();
        for (UUID seat : seats) {
            if (seat == null) {
                continue;
            }
            String relation = Objects.equals(seat, playerId)
                ? "自己"
                : isOpponentSeat(playerId, seat)
                    ? "对手"
                    : "队友";
            builder.append("- ")
                .append(displayName(seat))
                .append("：")
                .append(relation)
                .append("，")
                .append(aiIdentityLabel(seat))
                .append("，剩余 ")
                .append(hands.getOrDefault(seat, List.of()).size())
                .append(" 张");
            if (Objects.equals(seat, leadPlayer)) {
                builder.append("，本轮先手");
            }
            if (Objects.equals(seat, currentTurn)) {
                builder.append("，当前行动");
            }
            builder.append('\n');
        }
        int minOpponent = minOpponentHandCount(playerId);
        if (minOpponent == Integer.MAX_VALUE) {
            builder.append("- 对手剩牌压力：未知");
        } else {
            builder.append("- 对手剩牌压力：最近的对手还剩 ").append(minOpponent).append(" 张");
        }
        return builder.toString();
    }

    private String conservativeAiSuggestion(UUID playerId) {
        List<DoudizhuCard> hand = hands.getOrDefault(playerId, List.of());
        List<DoudizhuCard> move = SimpleBotBrain.choosePlay(hand, canLeadCurrentTrick(playerId) ? null : currentPattern, botPlayContext(playerId));
        if (move.isEmpty()) {
            return canLeadCurrentTrick(playerId) ? "没有稳定组合时，优先拆低风险小牌起手，不要先手开炸。" : "PASS";
        }
        CardPattern pattern = PatternAnalyzer.analyze(move).orElse(null);
        String ids = move.stream().map(card -> Integer.toString(card.id())).collect(Collectors.joining(","));
        if (pattern == null) {
            return ids;
        }
        return describeCards(move, pattern) + "（id: " + ids + "）";
    }

    private void recordBotAiTrace(
        UUID botId,
        String stage,
        String prompt,
        AiChatGateway.ChatResponse response,
        String parsedDecision,
        boolean applied,
        String fallbackReason,
        String errorMessage
    ) {
        plugin.recordBotAiTrace(
            DoudizhuPlugin.BotGameType.DOUDIZHU,
            botId,
            name,
            stage,
            prompt,
            response,
            parsedDecision,
            applied,
            fallbackReason,
            errorMessage
        );
    }

    private String timedOutPlayAiSystemPrompt() {
        return BotAiDecisionCodec.timedOutSystemPrompt();
    }

    private String buildTimedOutPlayAiPrompt(UUID playerId) {
        return BotAiDecisionCodec.buildTimedOutPlayPrompt(
            displayName(playerId),
            aiIdentityLabel(playerId),
            aiTableStateSummary(playerId),
            currentPattern == null || currentTrickCards.isEmpty() ? "无，你是先手或这一轮已重置" : describeCards(currentTrickCards, currentPattern),
            hands.getOrDefault(playerId, List.of()),
            conservativeAiSuggestion(playerId)
        );
    }

    private String botAiSystemPrompt() {
        return BotAiDecisionCodec.botSystemPrompt();
    }

    private String buildBidAiPrompt(UUID botId) {
        return BotAiDecisionCodec.buildBidPrompt(displayName(botId), bidRound, highestBid, hands.getOrDefault(botId, List.of()));
    }

    private String buildDoublingAiPrompt(UUID botId) {
        return BotAiDecisionCodec.buildDoublingPrompt(
            displayName(botId),
            Objects.equals(botId, landlord),
            Math.max(1, highestBid) * bombMultiplier,
            hands.getOrDefault(botId, List.of())
        );
    }

    private String buildPlayAiPrompt(UUID botId) {
        return BotAiDecisionCodec.buildPlayPrompt(
            displayName(botId),
            aiIdentityLabel(botId),
            aiTableStateSummary(botId),
            leadPlayer == null ? "无" : displayName(leadPlayer),
            currentPattern == null || currentTrickCards.isEmpty() ? "无，你是先手或这一轮已重置" : describeCards(currentTrickCards, currentPattern),
            hands.getOrDefault(botId, List.of()),
            conservativeAiSuggestion(botId)
        );
    }

    private Integer parseAiBidDecision(AiChatGateway.ChatResponse response) {
        return BotAiDecisionCodec.parseBidDecision(response);
    }

    private String parseAiKeywordDecision(AiChatGateway.ChatResponse response) {
        return BotAiDecisionCodec.parseKeywordDecision(response);
    }

    private List<DoudizhuCard> parseAiPlayDecision(UUID botId, AiChatGateway.ChatResponse response) {
        return BotAiDecisionCodec.parsePlayDecision(
            new BotAiDecisionCodec.UUIDOwnerHand(botId, hands.getOrDefault(botId, List.of())),
            currentPattern,
            new BotAiDecisionCodec.UUIDHolder(leadPlayer),
            response
        );
    }

    private record MoveResolution(
        List<DoudizhuCard> move,
        CardPattern pattern,
        String cardLabels,
        boolean pressurePlay,
        boolean multiplierRaised,
        boolean threeCardsLeft,
        boolean twoCardsLeft,
        boolean handEmpty
    ) {
    }
}

