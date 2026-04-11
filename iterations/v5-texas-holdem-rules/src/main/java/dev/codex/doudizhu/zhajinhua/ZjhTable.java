package dev.codex.doudizhu.zhajinhua;

import dev.codex.doudizhu.DoudizhuPlugin;
import dev.codex.doudizhu.model.CardRank;
import dev.codex.doudizhu.model.CardSuit;
import dev.codex.doudizhu.model.DoudizhuCard;
import java.util.ArrayList;
import java.util.Comparator;
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

public final class ZjhTable {
    private static final int DEFAULT_STACK = 100;
    private static final int SMALL_BLIND = 1;
    private static final int BIG_BLIND = 2;

    private final DoudizhuPlugin plugin;
    private final ZjhManager manager;
    private final String name;
    private final int maxPlayers;
    private final Random random = new Random();
    private final List<UUID> seats = new ArrayList<>();
    private final Set<UUID> readyPlayers = new HashSet<>();
    private final Map<UUID, Integer> chipStacks = new LinkedHashMap<>();
    private final Map<UUID, Integer> totalContributions = new LinkedHashMap<>();
    private final Map<UUID, Integer> streetContributions = new LinkedHashMap<>();
    private final Map<UUID, List<DoudizhuCard>> holeCards = new HashMap<>();
    private final Set<UUID> foldedPlayers = new HashSet<>();
    private final Set<UUID> allInPlayers = new HashSet<>();
    private final Set<UUID> actedThisStreet = new HashSet<>();
    private final Map<UUID, String> botNames = new LinkedHashMap<>();
    private final List<DoudizhuCard> communityCards = new ArrayList<>();

    private ZjhPhase phase = ZjhPhase.LOBBY;
    private TexasStreet street = TexasStreet.PRE_FLOP;
    private UUID currentTurn;
    private int currentBet;
    private int dealerIndex = -1;
    private int actionEpoch;
    private boolean debugAutoLoop;

    public ZjhTable(DoudizhuPlugin plugin, ZjhManager manager, String name, int maxPlayers) {
        this.plugin = plugin;
        this.manager = manager;
        this.name = name.trim();
        this.maxPlayers = Math.max(2, Math.min(10, maxPlayers));
    }

    public String getName() {
        return name;
    }

    public int getMaxPlayers() {
        return maxPlayers;
    }

    public ZjhPhase getPhase() {
        return phase;
    }

    public List<UUID> getSeats() {
        return List.copyOf(seats);
    }

    public boolean isReady(UUID playerId) {
        return readyPlayers.contains(playerId);
    }

    public UUID getCurrentTurn() {
        return currentTurn;
    }

    public int getPot() {
        return totalContributions.values().stream().mapToInt(Integer::intValue).sum();
    }

    public int getCurrentBet() {
        return currentBet;
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

    public int chipStack(UUID playerId) {
        return chipStacks.getOrDefault(playerId, DEFAULT_STACK);
    }

    public List<DoudizhuCard> handOf(UUID playerId) {
        return List.copyOf(holeCards.getOrDefault(playerId, List.of()));
    }

    public List<DoudizhuCard> communityCards() {
        return List.copyOf(communityCards);
    }

    public TexasStreet getStreet() {
        return street;
    }

    public void addPlayer(Player player) {
        if (seats.contains(player.getUniqueId())) {
            return;
        }
        ensureLobby();
        if (seats.size() >= maxPlayers) {
            throw new IllegalStateException("德州扑克牌桌已满。");
        }
        seats.add(player.getUniqueId());
        chipStacks.putIfAbsent(player.getUniqueId(), DEFAULT_STACK);
        broadcast(text(player.getName() + " 加入了德州扑克牌桌。(" + seats.size() + "/" + maxPlayers + ")", NamedTextColor.YELLOW));
        refreshPhysical();
    }

    public void removePlayer(Player player, String reason) {
        UUID playerId = player.getUniqueId();
        if (!seats.contains(playerId)) {
            return;
        }
        broadcast(text(reason, NamedTextColor.RED));
        seats.remove(playerId);
        readyPlayers.remove(playerId);
        foldedPlayers.remove(playerId);
        allInPlayers.remove(playerId);
        actedThisStreet.remove(playerId);
        holeCards.remove(playerId);
        streetContributions.remove(playerId);
        totalContributions.remove(playerId);
        botNames.remove(playerId);
        manager.unregisterPlayer(playerId);
        if (phase == ZjhPhase.PLAYING) {
            finishIfOneLeft();
        }
        refreshPhysical();
    }

    public void addBot(String preferredName) {
        ensureLobby();
        if (seats.size() >= maxPlayers) {
            throw new IllegalStateException("德州扑克牌桌已满。");
        }
        UUID botId = UUID.randomUUID();
        String name = preferredName == null || preferredName.isBlank() ? "TBot-" + (botNames.size() + 1) : preferredName.trim();
        botNames.put(botId, name);
        seats.add(botId);
        chipStacks.putIfAbsent(botId, DEFAULT_STACK);
        readyPlayers.add(botId);
        refreshPhysical();
    }

    public void removeBot() {
        ensureLobby();
        UUID target = botNames.keySet().stream().reduce((first, second) -> second).orElse(null);
        if (target == null) {
            throw new IllegalStateException("当前没有机器人可移除。");
        }
        seats.remove(target);
        readyPlayers.remove(target);
        chipStacks.remove(target);
        botNames.remove(target);
        refreshPhysical();
    }

    public void toggleReady(Player player) {
        requireAtTable(player.getUniqueId());
        ensureLobby();
        if (!readyPlayers.remove(player.getUniqueId())) {
            readyPlayers.add(player.getUniqueId());
            broadcast(text(player.getName() + " 已准备。", NamedTextColor.GREEN));
        } else {
            broadcast(text(player.getName() + " 取消了准备。", NamedTextColor.GRAY));
        }
        refreshPhysical();
    }

    public void startRound(CommandSender sender) {
        ensureLobby();
        if (seats.size() < 2) {
            throw new IllegalStateException("德州扑克至少需要 2 名玩家。");
        }
        if (readyPlayers.size() != seats.size()) {
            throw new IllegalStateException("所有玩家都准备后才能开局。");
        }
        phase = ZjhPhase.PLAYING;
        street = TexasStreet.PRE_FLOP;
        foldedPlayers.clear();
        allInPlayers.clear();
        actedThisStreet.clear();
        holeCards.clear();
        streetContributions.clear();
        totalContributions.clear();
        communityCards.clear();
        currentBet = 0;

        dealerIndex = seats.isEmpty() ? -1 : (dealerIndex + 1 + seats.size()) % seats.size();
        List<DoudizhuCard> deck = holdemDeck();
        for (UUID seat : seats) {
            chipStacks.putIfAbsent(seat, DEFAULT_STACK);
            holeCards.put(seat, List.of(deck.removeLast(), deck.removeLast()));
            streetContributions.put(seat, 0);
            totalContributions.put(seat, 0);
        }

        int smallBlindIndex = nextSeatIndex(dealerIndex);
        int bigBlindIndex = nextSeatIndex(smallBlindIndex);
        postBlind(seats.get(smallBlindIndex), SMALL_BLIND);
        postBlind(seats.get(bigBlindIndex), BIG_BLIND);
        currentBet = Math.max(streetContributions.getOrDefault(seats.get(bigBlindIndex), 0), BIG_BLIND);
        currentTurn = seats.get(nextSeatIndex(bigBlindIndex));
        actionEpoch++;
        broadcast(text(sender.getName() + " 开始了德州扑克新一局。小盲 " + SMALL_BLIND + "，大盲 " + BIG_BLIND + "。", NamedTextColor.GOLD));
        if (activeActors().isEmpty()) {
            runOutBoardAndShowdown();
            return;
        }
        refreshPhysical();
        runBotActionIfNeeded();
    }

    public void showHand(Player player) {
        requireAtTable(player.getUniqueId());
        player.sendMessage(text("你的底牌: " + handText(player.getUniqueId()), NamedTextColor.AQUA));
        if (communityCards.size() >= 3) {
            TexasHand best = TexasEvaluator.evaluateBest(allCards(player.getUniqueId()));
            player.sendMessage(text("当前最佳牌型: " + best.displayName(), NamedTextColor.YELLOW));
        }
    }

    public void check(Player player) {
        requireActingPlayer(player);
        if (streetContributions.getOrDefault(player.getUniqueId(), 0) != currentBet) {
            throw new IllegalStateException("当前不能过牌，请选择跟注或弃牌。");
        }
        actedThisStreet.add(player.getUniqueId());
        broadcast(text(player.getName() + " 过牌。", NamedTextColor.GRAY));
        advanceAfterAction();
    }

    public void call(Player player) {
        requireActingPlayer(player);
        int toCall = Math.max(0, currentBet - streetContributions.getOrDefault(player.getUniqueId(), 0));
        if (toCall == 0) {
            check(player);
            return;
        }
        contribute(player.getUniqueId(), toCall);
        actedThisStreet.add(player.getUniqueId());
        broadcast(text(player.getName() + " 跟注了 " + toCall + "。", NamedTextColor.YELLOW));
        advanceAfterAction();
    }

    public void raise(Player player, int totalStreetBet) {
        requireActingPlayer(player);
        int currentStreet = streetContributions.getOrDefault(player.getUniqueId(), 0);
        if (totalStreetBet <= currentBet) {
            throw new IllegalStateException("加注后的总下注额必须大于当前下注。");
        }
        int delta = totalStreetBet - currentStreet;
        if (delta >= chipStack(player.getUniqueId())) {
            allIn(player);
            return;
        }
        contribute(player.getUniqueId(), delta);
        currentBet = streetContributions.getOrDefault(player.getUniqueId(), 0);
        actedThisStreet.clear();
        actedThisStreet.add(player.getUniqueId());
        broadcast(text(player.getName() + " 加注到 " + currentBet + "。", NamedTextColor.GOLD));
        advanceAfterAction();
    }

    public void fold(Player player) {
        requireActingPlayer(player);
        foldedPlayers.add(player.getUniqueId());
        actedThisStreet.add(player.getUniqueId());
        broadcast(text(player.getName() + " 弃牌。", NamedTextColor.RED));
        advanceAfterAction();
    }

    public void allIn(Player player) {
        requireActingPlayer(player);
        int stack = chipStack(player.getUniqueId());
        if (stack <= 0) {
            throw new IllegalStateException("你已经没有可下注筹码。");
        }
        contribute(player.getUniqueId(), stack);
        if (streetContributions.getOrDefault(player.getUniqueId(), 0) > currentBet) {
            currentBet = streetContributions.get(player.getUniqueId());
            actedThisStreet.clear();
        }
        actedThisStreet.add(player.getUniqueId());
        broadcast(text(player.getName() + " 全下。", NamedTextColor.LIGHT_PURPLE));
        advanceAfterAction();
    }

    public List<Component> buildStatusLines() {
        List<Component> lines = new ArrayList<>();
        lines.add(text("德州扑克牌桌: " + name, NamedTextColor.GOLD));
        lines.add(text("街道: " + street.displayName(), NamedTextColor.YELLOW));
        lines.add(text("人数: " + seats.size() + "/" + maxPlayers, NamedTextColor.GRAY));
        lines.add(text("底池: " + getPot() + " | 当前下注: " + currentBet, NamedTextColor.AQUA));
        if (!communityCards.isEmpty()) {
            lines.add(text("公共牌: " + communityCards.stream().map(DoudizhuCard::displayLabel).collect(Collectors.joining(" ")), NamedTextColor.GREEN));
        }
        if (dealerIndex >= 0 && dealerIndex < seats.size()) {
            lines.add(text("按钮位: " + displayName(seats.get(dealerIndex)), NamedTextColor.GRAY));
        }
        for (UUID seat : seats) {
            StringBuilder line = new StringBuilder("- ").append(displayName(seat)).append(" [筹码 ").append(chipStack(seat)).append("]");
            if (readyPlayers.contains(seat) && phase == ZjhPhase.LOBBY) {
                line.append(" [已准备]");
            }
            if (foldedPlayers.contains(seat)) {
                line.append(" [已弃牌]");
            }
            if (allInPlayers.contains(seat)) {
                line.append(" [ALL-IN]");
            }
            if (Objects.equals(currentTurn, seat)) {
                line.append(" [当前操作]");
            }
            lines.add(text(line.toString(), NamedTextColor.GRAY));
        }
        return lines;
    }

    public void forceEnd(CommandSender sender) {
        if (phase == ZjhPhase.LOBBY) {
            throw new IllegalStateException("当前没有德州扑克牌局。");
        }
        broadcast(text(sender.getName() + " 强制结束了德州扑克牌局。", NamedTextColor.RED));
        resetRound();
    }

    public void shutdown() {
        resetRound();
    }

    public void tickActionBar() {
        if (phase != ZjhPhase.PLAYING || currentTurn == null) {
            return;
        }
        for (UUID seat : seats) {
            Player player = Bukkit.getPlayer(seat);
            if (player == null) {
                continue;
            }
            if (Objects.equals(seat, currentTurn)) {
                player.sendActionBar(text("轮到你操作: /muz hand /check /call /raise /fold /allin", NamedTextColor.AQUA));
            } else {
                player.sendActionBar(text("当前 " + displayName(currentTurn) + " 正在行动 | " + street.displayName(), NamedTextColor.YELLOW));
            }
        }
    }

    public void enableDebugAutoLoop() {
        debugAutoLoop = true;
    }

    private void requireActingPlayer(Player player) {
        requireAtTable(player.getUniqueId());
        ensurePlaying();
        if (!Objects.equals(currentTurn, player.getUniqueId())) {
            throw new IllegalStateException("还没轮到你操作。");
        }
    }

    private void ensureLobby() {
        if (phase != ZjhPhase.LOBBY) {
            throw new IllegalStateException("当前不在大厅阶段。");
        }
    }

    private void ensurePlaying() {
        if (phase != ZjhPhase.PLAYING) {
            throw new IllegalStateException("当前不在德州扑克牌局中。");
        }
    }

    private void requireAtTable(UUID playerId) {
        if (!seats.contains(playerId)) {
            throw new IllegalStateException("你不在这个德州扑克牌桌里。");
        }
    }

    private void postBlind(UUID playerId, int blind) {
        contribute(playerId, blind);
        actedThisStreet.add(playerId);
    }

    private void contribute(UUID playerId, int requested) {
        int stack = chipStack(playerId);
        int paid = Math.min(stack, Math.max(0, requested));
        chipStacks.put(playerId, stack - paid);
        streetContributions.merge(playerId, paid, Integer::sum);
        totalContributions.merge(playerId, paid, Integer::sum);
        if (chipStack(playerId) <= 0) {
            allInPlayers.add(playerId);
        }
        refreshPhysical();
    }

    private void advanceAfterAction() {
        if (finishIfOneLeft()) {
            return;
        }
        if (activeActors().isEmpty()) {
            runOutBoardAndShowdown();
            return;
        }
        if (streetFinished()) {
            advanceStreet();
            return;
        }
        currentTurn = nextActingPlayer(currentTurn);
        actionEpoch++;
        refreshPhysical();
        runBotActionIfNeeded();
    }

    private boolean finishIfOneLeft() {
        List<UUID> active = activePlayers();
        if (active.size() != 1) {
            return false;
        }
        UUID winner = active.getFirst();
        chipStacks.merge(winner, getPot(), Integer::sum);
        broadcast(text(displayName(winner) + " 收下了底池 " + getPot() + "。", NamedTextColor.GOLD));
        resetRound();
        if (canScheduleTasks() && debugAutoLoop && seats.size() >= 2 && seats.stream().allMatch(this::isBot)) {
            plugin.getServer().getScheduler().runTaskLater(plugin, () -> {
                try {
                    startRound(plugin.getServer().getConsoleSender());
                } catch (RuntimeException ignored) {
                }
            }, 2L);
        }
        return true;
    }

    private boolean streetFinished() {
        List<UUID> actors = activeActors();
        if (actors.isEmpty()) {
            return true;
        }
        for (UUID actor : actors) {
            if (!actedThisStreet.contains(actor)) {
                return false;
            }
            if (streetContributions.getOrDefault(actor, 0) != currentBet) {
                return false;
            }
        }
        return true;
    }

    private void advanceStreet() {
        switch (street) {
            case PRE_FLOP -> {
                street = TexasStreet.FLOP;
                communityCards.addAll(drawCommunity(3));
                broadcast(text("翻牌圈: " + communityText(), NamedTextColor.GREEN));
            }
            case FLOP -> {
                street = TexasStreet.TURN;
                communityCards.addAll(drawCommunity(1));
                broadcast(text("转牌圈: " + communityText(), NamedTextColor.GREEN));
            }
            case TURN -> {
                street = TexasStreet.RIVER;
                communityCards.addAll(drawCommunity(1));
                broadcast(text("河牌圈: " + communityText(), NamedTextColor.GREEN));
            }
            case RIVER, SHOWDOWN -> {
                showdown();
                return;
            }
        }
        actedThisStreet.clear();
        streetContributions.clear();
        for (UUID seat : seats) {
            streetContributions.put(seat, 0);
        }
        currentBet = 0;
        if (activeActors().isEmpty()) {
            runOutBoardAndShowdown();
            return;
        }
        currentTurn = seats.get(nextActingFromIndex(dealerIndex));
        actionEpoch++;
        refreshPhysical();
        runBotActionIfNeeded();
    }

    private void runOutBoardAndShowdown() {
        while (street != TexasStreet.RIVER) {
            advanceStreet();
            if (phase == ZjhPhase.LOBBY) {
                return;
            }
        }
        showdown();
    }

    private void showdown() {
        street = TexasStreet.SHOWDOWN;
        Map<UUID, TexasHand> hands = new HashMap<>();
        for (UUID playerId : activePlayers()) {
            hands.put(playerId, TexasEvaluator.evaluateBest(allCards(playerId)));
        }
        distributeSidePots(hands);
        resetRound();
        if (canScheduleTasks() && debugAutoLoop && seats.size() >= 2 && seats.stream().allMatch(this::isBot)) {
            plugin.getServer().getScheduler().runTaskLater(plugin, () -> {
                try {
                    startRound(plugin.getServer().getConsoleSender());
                } catch (RuntimeException ignored) {
                }
            }, 2L);
        }
    }

    private void distributeSidePots(Map<UUID, TexasHand> hands) {
        List<Integer> levels = totalContributions.values().stream().filter(value -> value > 0).distinct().sorted().toList();
        int previous = 0;
        for (int level : levels) {
            List<UUID> contributors = seats.stream().filter(seat -> totalContributions.getOrDefault(seat, 0) >= level).toList();
            int segment = (level - previous) * contributors.size();
            previous = level;
            List<UUID> eligible = contributors.stream().filter(hands::containsKey).toList();
            if (eligible.isEmpty() || segment <= 0) {
                continue;
            }
            TexasHand best = eligible.stream().map(hands::get).max(TexasEvaluator::compare).orElseThrow();
            List<UUID> winners = eligible.stream().filter(playerId -> TexasEvaluator.compare(hands.get(playerId), best) == 0).toList();
            int share = segment / winners.size();
            int remainder = segment % winners.size();
            for (UUID winner : winners) {
                chipStacks.merge(winner, share, Integer::sum);
            }
            for (int index = 0; index < remainder; index++) {
                chipStacks.merge(winners.get(index), 1, Integer::sum);
            }
            broadcast(text(
                winners.stream().map(this::displayName).collect(Collectors.joining(", "))
                    + " 赢得 " + segment + "，牌型: " + best.displayName(),
                NamedTextColor.GOLD
            ));
        }
    }

    private List<DoudizhuCard> drawCommunity(int amount) {
        List<DoudizhuCard> pool = buildRemainingDeck();
        List<DoudizhuCard> drawn = new ArrayList<>(amount);
        for (int index = 0; index < amount && !pool.isEmpty(); index++) {
            drawn.add(pool.removeLast());
        }
        return drawn;
    }

    private List<DoudizhuCard> buildRemainingDeck() {
        List<DoudizhuCard> deck = holdemDeck();
        List<Integer> usedIds = new ArrayList<>();
        holeCards.values().forEach(cards -> cards.forEach(card -> usedIds.add(card.id())));
        communityCards.forEach(card -> usedIds.add(card.id()));
        deck.removeIf(card -> usedIds.contains(card.id()));
        return deck;
    }

    private List<DoudizhuCard> allCards(UUID playerId) {
        List<DoudizhuCard> cards = new ArrayList<>(holeCards.getOrDefault(playerId, List.of()));
        cards.addAll(communityCards);
        return cards;
    }

    private List<UUID> activePlayers() {
        return seats.stream().filter(seat -> !foldedPlayers.contains(seat)).toList();
    }

    private List<UUID> activeActors() {
        return seats.stream()
            .filter(seat -> !foldedPlayers.contains(seat))
            .filter(seat -> !allInPlayers.contains(seat))
            .toList();
    }

    private UUID nextActingPlayer(UUID from) {
        int index = seats.indexOf(from);
        return seats.get(nextActingFromIndex(index));
    }

    private int nextActingFromIndex(int startIndex) {
        for (int offset = 1; offset <= seats.size(); offset++) {
            int next = (startIndex + offset) % seats.size();
            UUID candidate = seats.get(next);
            if (!foldedPlayers.contains(candidate) && !allInPlayers.contains(candidate)) {
                return next;
            }
        }
        return Math.max(0, startIndex);
    }

    private int nextSeatIndex(int currentIndex) {
        return (currentIndex + 1 + seats.size()) % seats.size();
    }

    private boolean canScheduleTasks() {
        return plugin.isEnabled() && !plugin.isShuttingDown();
    }

    private void runBotActionIfNeeded() {
        if (!canScheduleTasks() || currentTurn == null || !isBot(currentTurn)) {
            return;
        }
        int epoch = ++actionEpoch;
        long delay = plugin.randomBotActionDelayTicks(random);
        plugin.getServer().getScheduler().runTaskLater(plugin, () -> {
            if (epoch != actionEpoch || currentTurn == null || !isBot(currentTurn) || phase != ZjhPhase.PLAYING) {
                return;
            }
            executeBotTurn(currentTurn);
        }, delay);
    }

    private void executeBotTurn(UUID botId) {
        try {
            int toCall = Math.max(0, currentBet - streetContributions.getOrDefault(botId, 0));
            TexasHand best = communityCards.size() >= 3 ? TexasEvaluator.evaluateBest(allCards(botId)) : null;
            if (toCall == 0 && best != null && best.type().power() >= TexasHandType.THREE_OF_A_KIND.power() && random.nextInt(100) < 35) {
                int raiseTo = currentBet + Math.max(2, BIG_BLIND);
                int currentStreet = streetContributions.getOrDefault(botId, 0);
                int delta = raiseTo - currentStreet;
                if (delta > 0 && chipStack(botId) > delta) {
                    contribute(botId, delta);
                    currentBet = streetContributions.getOrDefault(botId, 0);
                    actedThisStreet.clear();
                    actedThisStreet.add(botId);
                    broadcast(text(displayName(botId) + " 加注到 " + currentBet + "。", NamedTextColor.GOLD));
                    advanceAfterAction();
                    return;
                }
            }
            if (toCall == 0) {
                actedThisStreet.add(botId);
                broadcast(text(displayName(botId) + " 过牌。", NamedTextColor.GRAY));
                advanceAfterAction();
                return;
            }
            if (best != null && best.type().power() <= TexasHandType.ONE_PAIR.power() && toCall >= BIG_BLIND * 4 && random.nextInt(100) < 45) {
                foldedPlayers.add(botId);
                actedThisStreet.add(botId);
                broadcast(text(displayName(botId) + " 弃牌。", NamedTextColor.RED));
                advanceAfterAction();
                return;
            }
            if (chipStack(botId) <= toCall) {
                int stack = chipStack(botId);
                contribute(botId, stack);
                if (streetContributions.getOrDefault(botId, 0) > currentBet) {
                    currentBet = streetContributions.get(botId);
                    actedThisStreet.clear();
                }
                actedThisStreet.add(botId);
                broadcast(text(displayName(botId) + " 全下。", NamedTextColor.LIGHT_PURPLE));
                advanceAfterAction();
                return;
            }
            contribute(botId, toCall);
            actedThisStreet.add(botId);
            broadcast(text(displayName(botId) + " 跟注了 " + toCall + "。", NamedTextColor.YELLOW));
            advanceAfterAction();
        } catch (RuntimeException exception) {
            foldedPlayers.add(botId);
            finishIfOneLeft();
        }
    }

    private boolean isBot(UUID playerId) {
        return botNames.containsKey(playerId);
    }

    private String handText(UUID playerId) {
        return handOf(playerId).stream().map(DoudizhuCard::displayLabel).collect(Collectors.joining(" "));
    }

    private String communityText() {
        return communityCards.stream().map(DoudizhuCard::displayLabel).collect(Collectors.joining(" "));
    }

    private List<DoudizhuCard> holdemDeck() {
        List<DoudizhuCard> deck = new ArrayList<>();
        List<CardSuit> suits = List.of(CardSuit.SPADES, CardSuit.HEARTS, CardSuit.CLUBS, CardSuit.DIAMONDS);
        List<CardRank> ranks = List.of(
            CardRank.ACE, CardRank.KING, CardRank.QUEEN, CardRank.JACK, CardRank.TEN, CardRank.NINE,
            CardRank.EIGHT, CardRank.SEVEN, CardRank.SIX, CardRank.FIVE, CardRank.FOUR, CardRank.THREE, CardRank.TWO
        );
        int id = 2000;
        for (CardRank rank : ranks) {
            for (CardSuit suit : suits) {
                deck.add(new DoudizhuCard(id++, rank, suit));
            }
        }
        deck.sort(Comparator.comparingInt(card -> random.nextInt()));
        return deck;
    }

    private void resetRound() {
        phase = ZjhPhase.LOBBY;
        street = TexasStreet.PRE_FLOP;
        readyPlayers.clear();
        for (UUID botId : botNames.keySet()) {
            readyPlayers.add(botId);
        }
        foldedPlayers.clear();
        allInPlayers.clear();
        actedThisStreet.clear();
        holeCards.clear();
        streetContributions.clear();
        totalContributions.clear();
        communityCards.clear();
        currentTurn = null;
        currentBet = 0;
        refreshPhysical();
    }

    private void broadcast(Component component) {
        Component full = Component.text("[德州 " + name + "] ", NamedTextColor.GOLD).append(component.decoration(TextDecoration.ITALIC, false));
        for (UUID seat : seats) {
            Player player = Bukkit.getPlayer(seat);
            if (player != null) {
                player.sendMessage(full);
                player.sendActionBar(full);
            }
        }
    }

    private Component text(String message, NamedTextColor color) {
        return Component.text(message, color).decoration(TextDecoration.ITALIC, false);
    }

    private void refreshPhysical() {
        plugin.getZjhPhysicalTableManager().refresh(this);
    }
}
