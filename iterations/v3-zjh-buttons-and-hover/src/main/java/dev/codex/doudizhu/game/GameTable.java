package dev.codex.doudizhu.game;

import dev.codex.doudizhu.DoudizhuPlugin;
import dev.codex.doudizhu.action.CeActionExecutor;
import dev.codex.doudizhu.assets.PackSounds;
import dev.codex.doudizhu.model.CardPattern;
import dev.codex.doudizhu.model.CardRank;
import dev.codex.doudizhu.model.DoudizhuCard;
import dev.codex.doudizhu.model.DoudizhuDeck;
import dev.codex.doudizhu.model.MoveAdvisor;
import dev.codex.doudizhu.model.PatternAnalyzer;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Random;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextDecoration;
import org.bukkit.Bukkit;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;

public final class GameTable {
    private static final int PLAYER_COUNT = 3;

    private final DoudizhuPlugin plugin;
    private final TableManager manager;
    private final String name;
    private final Random random = new Random();
    private final List<UUID> seats = new ArrayList<>(PLAYER_COUNT);
    private final Set<UUID> readyPlayers = new HashSet<>();
    private final Map<UUID, Integer> totalScores = new LinkedHashMap<>();
    private final Map<UUID, Integer> bids = new LinkedHashMap<>();
    private final Map<UUID, PlayerRole> roles = new HashMap<>();
    private final Map<UUID, List<DoudizhuCard>> hands = new HashMap<>();
    private final Map<UUID, Set<Integer>> selections = new HashMap<>();
    private final Map<UUID, String> botNames = new LinkedHashMap<>();

    private GamePhase phase = GamePhase.LOBBY;
    private List<DoudizhuCard> bottomCards = List.of();
    private List<UUID> bidOrder = List.of();
    private UUID currentTurn;
    private UUID leadPlayer;
    private UUID landlord;
    private UUID highestBidder;
    private int highestBid;
    private int bombMultiplier = 1;
    private CardPattern currentPattern;
    private List<DoudizhuCard> currentTrickCards = List.of();
    private int botCounter = 1;
    private int botActionEpoch = 0;
    private String currentMusicKey;
    private int musicEpoch = 0;
    private long turnDeadlineMillis = -1L;
    private int lastCountdownSecond = Integer.MIN_VALUE;
    private boolean debugAutoLoop;

    public GameTable(DoudizhuPlugin plugin, TableManager manager, String name) {
        this.plugin = plugin;
        this.manager = manager;
        this.name = name.trim();
    }

    public String getName() {
        return name;
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
        return bids.getOrDefault(playerId, 0);
    }

    public int getScore(UUID playerId) {
        return totalScores.getOrDefault(playerId, 0);
    }

    public void addBot(String preferredName) {
        // 机器人只允许在大厅阶段补位，避免中途进入打乱牌局状态
        ensurePhase(GamePhase.LOBBY, "开局后不能再加机器人。");
        if (seats.size() >= PLAYER_COUNT) {
            throw new IllegalStateException("牌桌已经满了。");
        }
        UUID botId = UUID.randomUUID();
        String name = preferredName == null || preferredName.isBlank() ? "Bot-" + botCounter++ : preferredName.trim();
        botNames.put(botId, name);
        seats.add(botId);
        readyPlayers.add(botId);
        totalScores.putIfAbsent(botId, 0);
        broadcast(text(name + " 加入了牌桌。(" + seats.size() + "/" + PLAYER_COUNT + ")", NamedTextColor.YELLOW));
        refreshPhysicalTable();
    }

    public void removeBot() {
        ensurePhase(GamePhase.LOBBY, "开局后不能移除机器人。");
        UUID target = botNames.keySet().stream().reduce((first, second) -> second).orElse(null);
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
        broadcast(text(name + " 离开了牌桌。(" + seats.size() + "/" + PLAYER_COUNT + ")", NamedTextColor.YELLOW));
        refreshPhysicalTable();
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
        seats.add(player.getUniqueId());
        totalScores.putIfAbsent(player.getUniqueId(), 0);
        broadcast(text(player.getName() + " 加入了牌桌。(" + seats.size() + "/" + PLAYER_COUNT + ")", NamedTextColor.YELLOW));
        refreshPhysicalTable();
    }

    public void removePlayer(Player player, String reason) {
        UUID playerId = player.getUniqueId();
        if (!contains(playerId)) {
            return;
        }
        if (phase != GamePhase.LOBBY) {
            broadcast(text(reason, NamedTextColor.RED));
            resetRound();
        } else {
            broadcast(text(reason, NamedTextColor.RED));
        }
        seats.remove(playerId);
        readyPlayers.remove(playerId);
        bids.remove(playerId);
        roles.remove(playerId);
        hands.remove(playerId);
        selections.remove(playerId);
        botNames.remove(playerId);
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
        if (readyPlayers.contains(playerId)) {
            readyPlayers.remove(playerId);
            broadcast(text(player.getName() + " 取消了准备。", NamedTextColor.GRAY));
        } else {
            readyPlayers.add(playerId);
            broadcast(text(player.getName() + " 已准备。", NamedTextColor.GREEN));
        }
        if (readyPlayers.size() == PLAYER_COUNT) {
            broadcast(text("三名玩家都准备好了，任意一位玩家点击开始按钮即可开局。", NamedTextColor.GOLD));
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
            throw new IllegalStateException("三位玩家都准备后才能开局。");
        }
        dealFreshRound();
        broadcast(text(sender.getName() + " 开始了新的一局斗地主。", NamedTextColor.GOLD));
        broadcast(text("叫分顺序：" + bidOrder.stream().map(this::playerName).collect(Collectors.joining(" -> ")), NamedTextColor.YELLOW));
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
        if (points != 0 && points <= highestBid) {
            throw new IllegalArgumentException("你必须叫比当前更高的分，或者输入 0 放弃。");
        }

        UUID playerId = player.getUniqueId();
        bids.put(playerId, points);
        if (points > highestBid) {
            highestBid = points;
            highestBidder = playerId;
        }

        playEffectAll(PackSounds.bid(points));

        broadcast(text(player.getName() + (points == 0 ? " 选择不叫。" : " 叫了 " + points + " 分。"), NamedTextColor.AQUA));

        if (points == 3) {
            confirmLandlord(playerId, 3);
            return;
        }

        int currentIndex = bidOrder.indexOf(playerId);
        if (currentIndex == bidOrder.size() - 1) {
            if (highestBidder == null) {
                broadcast(text("三人都没有叫分，重新洗牌发牌。", NamedTextColor.RED));
                dealFreshRound();
                openHandsForAll();
                promptBidTurn();
                refreshPhysicalTable();
                runBotActionIfNeeded();
                return;
            }
            confirmLandlord(highestBidder, highestBid);
            return;
        }

        currentTurn = bidOrder.get(currentIndex + 1);
        promptBidTurn();
        refreshPhysicalTable();
        runBotActionIfNeeded();
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

        currentPattern = pattern;
        currentTrickCards = List.copyOf(chosen);
        leadPlayer = playerId;
        if (pattern.type().isBombFamily()) {
            bombMultiplier *= 2;
        }

        playEffectAll(PackSounds.play(pattern, pattern.primaryRank()));
        broadcast(text(
            player.getName() + " 打出了 " + pattern.displayName() + "："
                + chosen.stream().map(DoudizhuCard::displayLabel).collect(Collectors.joining(" ")),
            NamedTextColor.GREEN
        ));
        CeActionExecutor.executePlayProfile(
            plugin,
            player,
            this,
            pattern,
            chosen,
            plugin.getPlayActionProfile(plugin.getPlayerPlayActionProfileIndex(playerId))
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
            throw new IllegalStateException("当前轮到你领出，不能选择不要。");
        }

        clearSelection(playerId);
        playEffectAll(PackSounds.autoPass());
        broadcast(text(player.getName() + " 选择了不要。", NamedTextColor.GRAY));
        UUID next = nextSeat(playerId);
        if (Objects.equals(next, leadPlayer)) {
            currentTurn = leadPlayer;
            currentPattern = null;
            currentTrickCards = List.of();
            broadcast(text(playerName(leadPlayer) + " 获得新一轮的先手。", NamedTextColor.GOLD));
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
        broadcast(text(sender.getName() + " 强制结束了当前对局。", NamedTextColor.RED));
        resetRound();
    }

    public List<Component> buildStatusLines() {
        List<Component> lines = new ArrayList<>();
        lines.add(text("牌桌：" + name, NamedTextColor.GOLD));
        lines.add(text("阶段：" + phase.name(), NamedTextColor.YELLOW));
        lines.add(text("座位：", NamedTextColor.YELLOW));
        for (UUID seat : seats) {
            StringBuilder builder = new StringBuilder("- ").append(playerName(seat));
            if (readyPlayers.contains(seat)) {
                builder.append(" [已准备]");
            }
            if (landlord != null && landlord.equals(seat)) {
                builder.append(" [地主]");
            }
            if (phase == GamePhase.PLAYING) {
                builder.append(" [剩余 ").append(getHand(seat).size()).append(" 张]");
            }
            builder.append(" [总分 ").append(getScore(seat)).append("]");
            lines.add(text(builder.toString(), NamedTextColor.GRAY));
        }
        if (currentTurn != null) {
            lines.add(text("当前操作：" + playerName(currentTurn), NamedTextColor.AQUA));
        }
        if (highestBid > 0 && highestBidder != null) {
            lines.add(text("当前最高叫分：" + highestBid + " 分 (" + playerName(highestBidder) + ")", NamedTextColor.GREEN));
        }
        if (currentPattern != null && !currentTrickCards.isEmpty()) {
            lines.add(text(
                "上一手：" + playerName(leadPlayer) + " 的 " + currentPattern.displayName() + " - "
                    + currentTrickCards.stream().map(DoudizhuCard::displayLabel).collect(Collectors.joining(" ")),
                NamedTextColor.GREEN
            ));
        }
        if (!bottomCards.isEmpty()) {
            lines.add(text(
                "底牌：" + bottomCards.stream().map(DoudizhuCard::displayLabel).collect(Collectors.joining(" ")),
                NamedTextColor.LIGHT_PURPLE
            ));
        }
        return lines;
    }

    public String currentTrickPreviewText() {
        if (currentPattern == null || currentTrickCards.isEmpty()) {
            return "等待出牌";
        }
        return playerName(leadPlayer) + " | " + describeCards(currentTrickCards, currentPattern);
    }

    public String describePlayedCards(CardPattern pattern, List<DoudizhuCard> cards) {
        return describeCards(cards, pattern);
    }

    public void enableDebugAutoLoop() {
        debugAutoLoop = true;
    }

    public void shutdown() {
        plugin.getHandGuiService().closeHands(this);
        resetRound();
    }

    public void tickActionBar() {
        if (phase == GamePhase.LOBBY || seats.isEmpty()) {
            return;
        }

        int remaining = remainingCountdownSeconds();
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
        roles.clear();
        hands.clear();
        selections.clear();
        currentPattern = null;
        currentTrickCards = List.of();
        leadPlayer = null;
        landlord = null;
        highestBidder = null;
        highestBid = 0;
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
        currentTurn = bidOrder.get(0);
    }

    private void confirmLandlord(UUID playerId, int bid) {
        // 地主确定后追加底牌，并切换到出牌阶段
        phase = GamePhase.PLAYING;
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

        broadcast(text(
            playerName(playerId) + " 成为了地主，底牌是："
                + bottomCards.stream().map(DoudizhuCard::displayLabel).collect(Collectors.joining(" ")),
            NamedTextColor.GOLD
        ));
        playEffectAll(PackSounds.landlordConfirmed());
        broadcast(text("当前底分：" + highestBid + "，炸弹倍数初始为 1。", NamedTextColor.YELLOW));
        openHandsForAll();
        promptPlayTurn();
        refreshPhysicalTable();
        runBotActionIfNeeded();
    }

    private void finishRound(UUID winner) {
        // 当前只做最基础的地主/农民结算
        boolean landlordWin = Objects.equals(winner, landlord);
        int roundScore = Math.max(1, highestBid) * bombMultiplier;
        stopMusicAll();
        if (landlordWin) {
            totalScores.computeIfPresent(landlord, (ignored, score) -> score + roundScore * 2);
            for (UUID seat : seats) {
                if (!seat.equals(landlord)) {
                    totalScores.computeIfPresent(seat, (ignored, score) -> score - roundScore);
                }
            }
            broadcast(text(playerName(winner) + " 率先出完手牌，地主阵营获胜。本局分数：" + roundScore, NamedTextColor.GOLD));
        } else {
            totalScores.computeIfPresent(landlord, (ignored, score) -> score - roundScore * 2);
            for (UUID seat : seats) {
                if (!seat.equals(landlord)) {
                    totalScores.computeIfPresent(seat, (ignored, score) -> score + roundScore);
                }
            }
            broadcast(text(playerName(winner) + " 率先出完手牌，农民阵营获胜。本局分数：" + roundScore, NamedTextColor.GOLD));
        }
        for (UUID seat : seats) {
            playEffect(seat, (landlordWin == seat.equals(landlord)) ? PackSounds.win() : PackSounds.lose());
        }
        for (Component line : buildStatusLines()) {
            broadcast(line);
        }
        resetRound();
    }

    private void resetRound() {
        phase = GamePhase.LOBBY;
        readyPlayers.clear();
        bids.clear();
        roles.clear();
        hands.clear();
        selections.clear();
        for (UUID botId : botNames.keySet()) {
            readyPlayers.add(botId);
        }
        bottomCards = List.of();
        bidOrder = List.of();
        currentTurn = null;
        leadPlayer = null;
        landlord = null;
        highestBidder = null;
        highestBid = 0;
        bombMultiplier = 1;
        currentPattern = null;
        currentTrickCards = List.of();
        plugin.getHandGuiService().closeHands(this);
        currentMusicKey = null;
        clearTurnCountdown();
        refreshPhysicalTable();
        if (debugAutoLoop && seats.size() == PLAYER_COUNT && seats.stream().allMatch(this::isBot)) {
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
            broadcast(text(playerName(stuckPlayer) + " 手里没有能压过的牌，自动不要。", NamedTextColor.GRAY));

            UUID next = nextSeat(stuckPlayer);
            if (Objects.equals(next, leadPlayer)) {
                currentTurn = leadPlayer;
                currentPattern = null;
                currentTrickCards = List.of();
                broadcast(text(playerName(leadPlayer) + " 获得新一轮的先手。", NamedTextColor.GOLD));
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
            throw new IllegalStateException("现在还没轮到你。");
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
        Component full = Component.text("[斗地主 " + name + "] ", NamedTextColor.GOLD)
            .append(message.decoration(TextDecoration.ITALIC, false));
        for (UUID seat : seats) {
            Player player = onlinePlayer(seat);
            if (player != null) {
                player.sendActionBar(full);
            }
        }
    }

    private Component text(String message, NamedTextColor color) {
        return Component.text(message, color).decoration(TextDecoration.ITALIC, false);
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

    private void playSound(UUID playerId, String soundKey, float volume, float pitch) {
        Player player = onlinePlayer(playerId);
        if (player != null) {
            player.playSound(player.getLocation(), soundKey, volume, pitch);
        }
    }

    private void playRoundMusic() {
        stopMusicAll();
        currentMusicKey = PackSounds.randomBgm();
        playSoundAll(currentMusicKey, plugin.getBgmVolume(), 1.0f);
        scheduleNextMusic(currentMusicKey, ++musicEpoch);
    }

    private void stopMusicAll() {
        musicEpoch++;
        for (UUID seat : seats) {
            Player player = onlinePlayer(seat);
            if (player == null) {
                continue;
            }
            for (String bgm : PackSounds.bgmTracks()) {
                player.stopSound(bgm);
            }
        }
        currentMusicKey = null;
    }

    private void scheduleNextMusic(String soundKey, int epoch) {
        long delay = PackSounds.bgmDurationTicks(soundKey);
        plugin.getServer().getScheduler().runTaskLater(plugin, () -> {
            if (epoch != musicEpoch || phase == GamePhase.LOBBY) {
                return;
            }
            currentMusicKey = PackSounds.randomBgm();
            playSoundAll(currentMusicKey, plugin.getBgmVolume(), 1.0f);
            scheduleNextMusic(currentMusicKey, epoch);
        }, delay);
    }

    private void refreshPhysicalTable() {
        plugin.getPhysicalTableManager().refresh(this);
    }

    private String playerName(UUID playerId) {
        return displayName(playerId);
    }

    private void armTurnCountdown() {
        if (currentTurn == null || isBot(currentTurn) || plugin.getTurnCountdownSeconds() <= 0) {
            clearTurnCountdown();
            return;
        }
        turnDeadlineMillis = System.currentTimeMillis() + plugin.getTurnCountdownSeconds() * 1000L;
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

    private Component buildPersistentActionBar(UUID viewerId, int remainingSeconds) {
        if (currentTurn == null) {
            return text("等待下一步操作。", NamedTextColor.GRAY);
        }
        if (isBot(currentTurn)) {
            return text("当前 " + playerName(currentTurn) + " 正在思考。", NamedTextColor.YELLOW);
        }
        String countdown = plugin.getTurnCountdownSeconds() > 0 ? " | " + remainingSeconds + " 秒" : "";
        return switch (phase) {
            case BIDDING -> viewerId.equals(currentTurn)
                ? text("轮到你叫分，点击 0/1/2/3 分按钮" + countdown, NamedTextColor.AQUA)
                : text("当前 " + playerName(currentTurn) + " 正在叫分" + countdown, NamedTextColor.YELLOW);
            case PLAYING -> viewerId.equals(currentTurn)
                ? text("轮到你出牌 | 已选 " + getSelection(currentTurn).size() + " 张" + countdown, NamedTextColor.AQUA)
                : text("当前 " + playerName(currentTurn) + " 正在出牌" + countdown, NamedTextColor.YELLOW);
            case LOBBY -> text("牌桌大厅中。", NamedTextColor.GRAY);
        };
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
        if (currentTurn == null || !isBot(currentTurn)) {
            return;
        }
        int epoch = botActionEpoch;
        long delay = plugin.randomBotActionDelayTicks(random);
        plugin.getServer().getScheduler().runTaskLater(plugin, () -> {
            if (epoch != botActionEpoch || currentTurn == null || !isBot(currentTurn)) {
                return;
            }
            if (phase == GamePhase.BIDDING) {
                executeBotBid(currentTurn);
                return;
            }
            if (phase == GamePhase.PLAYING) {
                executeBotPlay(currentTurn);
            }
        }, delay);
    }

    private void executeBotBid(UUID botId) {
        // 机器人叫分暂时使用简单估值法
        List<DoudizhuCard> hand = hands.getOrDefault(botId, List.of());
        int desired = SimpleBotBrain.chooseBid(hand);
        int points = desired == 0 ? 0 : Math.max(desired, highestBid + 1);
        if (points > 3) {
            points = 0;
        }
        String actorName = displayName(botId);
        bids.put(botId, points);
        if (points > highestBid) {
            highestBid = points;
            highestBidder = botId;
        }

        playEffectAll(PackSounds.bid(points));
        broadcast(text(actorName + (points == 0 ? " 选择不叫。" : " 叫了 " + points + " 分。"), NamedTextColor.AQUA));

        if (points == 3) {
            confirmLandlord(botId, 3);
            return;
        }

        int currentIndex = bidOrder.indexOf(botId);
        if (currentIndex == bidOrder.size() - 1) {
            if (highestBidder == null) {
                broadcast(text("三人都没有叫分，重新洗牌发牌。", NamedTextColor.RED));
                dealFreshRound();
                openHandsForAll();
                promptBidTurn();
                refreshPhysicalTable();
                runBotActionIfNeeded();
                return;
            }
            confirmLandlord(highestBidder, highestBid);
            return;
        }

        currentTurn = bidOrder.get(currentIndex + 1);
        promptBidTurn();
        refreshPhysicalTable();
        runBotActionIfNeeded();
    }

    private void executeBotPlay(UUID botId) {
        // 机器人出牌先尝试同型压制，不行再考虑炸弹/王炸兜底
        List<DoudizhuCard> hand = hands.getOrDefault(botId, List.of());
        List<DoudizhuCard> move = SimpleBotBrain.choosePlay(hand, leadPlayer != null && !Objects.equals(leadPlayer, botId) ? currentPattern : null);
        if (move.isEmpty()) {
            if (leadPlayer == null || Objects.equals(leadPlayer, botId)) {
                move = List.of(hand.getLast());
            } else {
                clearSelection(botId);
                playEffectAll(PackSounds.autoPass());
                broadcast(text(displayName(botId) + " 选择了不要。", NamedTextColor.GRAY));
                UUID next = nextSeat(botId);
                if (Objects.equals(next, leadPlayer)) {
                    currentTurn = leadPlayer;
                    currentPattern = null;
                    currentTrickCards = List.of();
                    broadcast(text(playerName(leadPlayer) + " 获得新一轮的先手。", NamedTextColor.GOLD));
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

        CardPattern pattern = PatternAnalyzer.analyze(move)
            .orElseThrow(() -> new IllegalStateException("机器人生成了非法牌型。"));

        hand.removeAll(move);
        hand.sort(DoudizhuCard.ORDER);

        currentPattern = pattern;
        currentTrickCards = List.copyOf(move);
        leadPlayer = botId;
        if (pattern.type().isBombFamily()) {
            bombMultiplier *= 2;
        }

        playEffectAll(PackSounds.play(pattern, pattern.primaryRank()));
        broadcast(text(
            displayName(botId) + " 打出了 " + pattern.displayName() + "："
                + move.stream().map(DoudizhuCard::displayLabel).collect(Collectors.joining(" ")),
            NamedTextColor.GREEN
        ));

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
}
