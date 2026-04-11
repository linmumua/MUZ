package dev.codex.doudizhu.zhajinhua;

import dev.codex.doudizhu.DoudizhuPlugin;
import dev.codex.doudizhu.model.CardRank;
import dev.codex.doudizhu.model.CardSuit;
import dev.codex.doudizhu.model.DoudizhuCard;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumSet;
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
    private static final int ANTE = 1;

    private final DoudizhuPlugin plugin;
    private final ZjhManager manager;
    private final String name;
    private final int maxPlayers;
    private final Random random = new Random();
    private final List<UUID> seats = new ArrayList<>();
    private final Set<UUID> readyPlayers = new HashSet<>();
    private final Map<UUID, Integer> chipStacks = new LinkedHashMap<>();
    private final Map<UUID, Integer> roundBets = new HashMap<>();
    private final Map<UUID, List<DoudizhuCard>> hands = new HashMap<>();
    private final Set<UUID> lookedPlayers = new HashSet<>();
    private final Set<UUID> foldedPlayers = new HashSet<>();
    private final Map<UUID, String> botNames = new LinkedHashMap<>();

    private ZjhPhase phase = ZjhPhase.LOBBY;
    private UUID currentTurn;
    private int currentBet = ANTE;
    private int pot;
    private int actionEpoch;
    private boolean debugAutoLoop;

    public ZjhTable(DoudizhuPlugin plugin, ZjhManager manager, String name, int maxPlayers) {
        this.plugin = plugin;
        this.manager = manager;
        this.name = name.trim();
        this.maxPlayers = Math.max(2, Math.min(6, maxPlayers));
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
        return pot;
    }

    public int getCurrentBet() {
        return currentBet;
    }

    public boolean isLooked(UUID playerId) {
        return lookedPlayers.contains(playerId);
    }

    public boolean isFolded(UUID playerId) {
        return foldedPlayers.contains(playerId);
    }

    public List<DoudizhuCard> handOf(UUID playerId) {
        return List.copyOf(hands.getOrDefault(playerId, List.of()));
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

    public void addPlayer(Player player) {
        if (seats.contains(player.getUniqueId())) {
            return;
        }
        ensureLobby();
        if (seats.size() >= maxPlayers) {
            throw new IllegalStateException("炸金花牌桌已满。");
        }
        seats.add(player.getUniqueId());
        chipStacks.putIfAbsent(player.getUniqueId(), DEFAULT_STACK);
        broadcast(text(player.getName() + " 加入了炸金花牌桌。(" + seats.size() + "/" + maxPlayers + ")", NamedTextColor.YELLOW));
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
        lookedPlayers.remove(playerId);
        foldedPlayers.remove(playerId);
        hands.remove(playerId);
        roundBets.remove(playerId);
        botNames.remove(playerId);
        manager.unregisterPlayer(playerId);
        if (phase == ZjhPhase.PLAYING) {
            checkRoundEnd();
        }
        refreshPhysical();
    }

    public void addBot(String preferredName) {
        ensureLobby();
        if (seats.size() >= maxPlayers) {
            throw new IllegalStateException("炸金花牌桌已满。");
        }
        UUID botId = UUID.randomUUID();
        String name = preferredName == null || preferredName.isBlank() ? "ZBot-" + (botNames.size() + 1) : preferredName.trim();
        botNames.put(botId, name);
        seats.add(botId);
        chipStacks.putIfAbsent(botId, DEFAULT_STACK);
        readyPlayers.add(botId);
        broadcast(text(name + " 加入了炸金花牌桌。(" + seats.size() + "/" + maxPlayers + ")", NamedTextColor.YELLOW));
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
        if (readyPlayers.remove(player.getUniqueId())) {
            broadcast(text(player.getName() + " 取消了准备。", NamedTextColor.GRAY));
        } else {
            readyPlayers.add(player.getUniqueId());
            broadcast(text(player.getName() + " 已准备。", NamedTextColor.GREEN));
        }
        refreshPhysical();
    }

    public void startRound(CommandSender sender) {
        ensureLobby();
        if (seats.size() < 2) {
            throw new IllegalStateException("炸金花至少需要 2 名玩家。");
        }
        if (readyPlayers.size() != seats.size()) {
            throw new IllegalStateException("所有参与者都准备后才能开局。");
        }
        phase = ZjhPhase.PLAYING;
        lookedPlayers.clear();
        foldedPlayers.clear();
        hands.clear();
        roundBets.clear();
        pot = 0;
        currentBet = ANTE;
        List<DoudizhuCard> deck = zjhDeck();
        for (UUID seat : seats) {
            chipStacks.putIfAbsent(seat, DEFAULT_STACK);
            pay(seat, ANTE);
            pot += ANTE;
            roundBets.put(seat, ANTE);
            hands.put(seat, List.of(deck.removeLast(), deck.removeLast(), deck.removeLast()));
        }
        currentTurn = seats.get(random.nextInt(seats.size()));
        actionEpoch++;
        broadcast(text(sender.getName() + " 开始了炸金花新一局。底注 " + ANTE + "，当前明注 " + currentBet + "。", NamedTextColor.GOLD));
        refreshPhysical();
        runBotActionIfNeeded();
    }

    public void look(Player player) {
        requireAtTable(player.getUniqueId());
        ensurePlaying();
        requireCurrentTurn(player.getUniqueId());
        lookedPlayers.add(player.getUniqueId());
        player.sendMessage(text("你的手牌: " + handOf(player.getUniqueId()).stream().map(DoudizhuCard::displayLabel).collect(Collectors.joining(" ")), NamedTextColor.AQUA));
        player.sendMessage(text("牌型: " + ZjhEvaluator.evaluate(handOf(player.getUniqueId())).displayName(), NamedTextColor.YELLOW));
        broadcast(text(player.getName() + " 看了自己的牌。", NamedTextColor.GRAY));
        refreshPhysical();
        advanceTurn();
    }

    public void follow(Player player) {
        requireAtTable(player.getUniqueId());
        ensurePlaying();
        requireCurrentTurn(player.getUniqueId());
        int cost = followCost(player.getUniqueId());
        pay(player.getUniqueId(), cost);
        pot += cost;
        roundBets.merge(player.getUniqueId(), cost, Integer::sum);
        broadcast(text(player.getName() + " 跟注了 " + cost + "。", NamedTextColor.YELLOW));
        refreshPhysical();
        advanceTurn();
    }

    public void raise(Player player, int newBet) {
        requireAtTable(player.getUniqueId());
        ensurePlaying();
        requireCurrentTurn(player.getUniqueId());
        if (newBet <= currentBet) {
            throw new IllegalStateException("加注后的明注必须大于当前明注。");
        }
        currentBet = newBet;
        int cost = lookedPlayers.contains(player.getUniqueId()) ? newBet * 2 : newBet;
        pay(player.getUniqueId(), cost);
        pot += cost;
        roundBets.merge(player.getUniqueId(), cost, Integer::sum);
        broadcast(text(player.getName() + " 把明注加到了 " + newBet + "。", NamedTextColor.GOLD));
        refreshPhysical();
        advanceTurn();
    }

    public void fold(Player player) {
        requireAtTable(player.getUniqueId());
        ensurePlaying();
        requireCurrentTurn(player.getUniqueId());
        foldedPlayers.add(player.getUniqueId());
        broadcast(text(player.getName() + " 弃牌了。", NamedTextColor.RED));
        refreshPhysical();
        checkRoundEnd();
        if (phase == ZjhPhase.PLAYING) {
            advanceTurn();
        }
    }

    public void compare(Player player, String targetName) {
        requireAtTable(player.getUniqueId());
        ensurePlaying();
        requireCurrentTurn(player.getUniqueId());
        UUID target = seats.stream()
            .filter(seat -> !seat.equals(player.getUniqueId()))
            .filter(seat -> !foldedPlayers.contains(seat))
            .filter(seat -> displayName(seat).equalsIgnoreCase(targetName))
            .findFirst()
            .orElseThrow(() -> new IllegalStateException("找不到可比牌的目标。"));
        int cost = currentBet * 2;
        pay(player.getUniqueId(), cost);
        pot += cost;
        ZjhHand challenger = ZjhEvaluator.evaluate(handOf(player.getUniqueId()));
        ZjhHand defender = ZjhEvaluator.evaluate(handOf(target));
        int compare = ZjhEvaluator.compare(challenger, defender);
        UUID loser = compare > 0 ? target : player.getUniqueId();
        foldedPlayers.add(loser);
        broadcast(text(displayName(player.getUniqueId()) + " 与 " + displayName(target) + " 比牌，" + displayName(loser) + " 出局。", NamedTextColor.LIGHT_PURPLE));
        refreshPhysical();
        checkRoundEnd();
        if (phase == ZjhPhase.PLAYING) {
            advanceTurn();
        }
    }

    public List<Component> buildStatusLines() {
        List<Component> lines = new ArrayList<>();
        lines.add(text("炸金花牌桌: " + name, NamedTextColor.GOLD));
        lines.add(text("阶段: " + phase.name(), NamedTextColor.YELLOW));
        lines.add(text("人数: " + seats.size() + "/" + maxPlayers, NamedTextColor.GRAY));
        lines.add(text("底池: " + pot + " | 当前明注: " + currentBet, NamedTextColor.AQUA));
        for (UUID seat : seats) {
            StringBuilder line = new StringBuilder("- ").append(displayName(seat)).append(" [筹码 ").append(chipStack(seat)).append("]");
            if (readyPlayers.contains(seat)) {
                line.append(" [已准备]");
            }
            if (lookedPlayers.contains(seat)) {
                line.append(" [已看牌]");
            }
            if (foldedPlayers.contains(seat)) {
                line.append(" [已弃牌]");
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
            throw new IllegalStateException("当前没有炸金花对局。");
        }
        broadcast(text(sender.getName() + " 强制结束了炸金花对局。", NamedTextColor.RED));
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
            if (player != null) {
                if (Objects.equals(seat, currentTurn)) {
                    player.sendActionBar(text("轮到你操作: /muz look /follow /raise /fold /compare", NamedTextColor.AQUA));
                } else {
                    player.sendActionBar(text("当前 " + displayName(currentTurn) + " 正在操作。底池 " + pot, NamedTextColor.YELLOW));
                }
            }
        }
    }

    public void enableDebugAutoLoop() {
        debugAutoLoop = true;
    }

    private void ensureLobby() {
        if (phase != ZjhPhase.LOBBY) {
            throw new IllegalStateException("当前不在大厅阶段。");
        }
    }

    private void ensurePlaying() {
        if (phase != ZjhPhase.PLAYING) {
            throw new IllegalStateException("当前不在炸金花对局中。");
        }
    }

    private void requireAtTable(UUID playerId) {
        if (!seats.contains(playerId)) {
            throw new IllegalStateException("你不在这个炸金花牌桌里。");
        }
    }

    private void requireCurrentTurn(UUID playerId) {
        if (!Objects.equals(currentTurn, playerId)) {
            throw new IllegalStateException("还没轮到你操作。");
        }
    }

    private void advanceTurn() {
        checkRoundEnd();
        if (phase != ZjhPhase.PLAYING) {
            return;
        }
        int index = seats.indexOf(currentTurn);
        for (int offset = 1; offset <= seats.size(); offset++) {
            UUID next = seats.get((index + offset) % seats.size());
            if (!foldedPlayers.contains(next)) {
                currentTurn = next;
                break;
            }
        }
        actionEpoch++;
        runBotActionIfNeeded();
    }

    private void checkRoundEnd() {
        List<UUID> alive = seats.stream().filter(seat -> !foldedPlayers.contains(seat)).toList();
        if (alive.size() > 1) {
            return;
        }
        UUID winner = alive.isEmpty() ? null : alive.getFirst();
        if (winner != null) {
            chipStacks.merge(winner, pot, Integer::sum);
            broadcast(text(displayName(winner) + " 赢得了底池 " + pot + "。", NamedTextColor.GOLD));
        }
        resetRound();
        if (debugAutoLoop && seats.size() >= 2 && seats.stream().allMatch(this::isBot)) {
            plugin.getServer().getScheduler().runTaskLater(plugin, () -> {
                try {
                    startRound(plugin.getServer().getConsoleSender());
                } catch (RuntimeException ignored) {
                }
            }, 2L);
        }
    }

    private void pay(UUID playerId, int amount) {
        int current = chipStack(playerId);
        if (current < amount) {
            throw new IllegalStateException(displayName(playerId) + " 的筹码不足。");
        }
        chipStacks.put(playerId, current - amount);
    }

    private int followCost(UUID playerId) {
        return lookedPlayers.contains(playerId) ? currentBet * 2 : currentBet;
    }

    private void resetRound() {
        phase = ZjhPhase.LOBBY;
        readyPlayers.clear();
        for (UUID botId : botNames.keySet()) {
            readyPlayers.add(botId);
        }
        lookedPlayers.clear();
        foldedPlayers.clear();
        hands.clear();
        roundBets.clear();
        currentTurn = null;
        currentBet = ANTE;
        pot = 0;
        refreshPhysical();
    }

    private void runBotActionIfNeeded() {
        if (currentTurn == null || !isBot(currentTurn)) {
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
            ZjhHand hand = ZjhEvaluator.evaluate(handOf(botId));
            if (!lookedPlayers.contains(botId) && hand.type().power() <= ZjhHandType.PAIR.power() && random.nextBoolean()) {
                lookedPlayers.add(botId);
                broadcast(text(displayName(botId) + " 看了自己的牌。", NamedTextColor.GRAY));
                advanceTurn();
                return;
            }
            if (hand.type().power() >= ZjhHandType.FLUSH.power() && random.nextInt(100) < 35) {
                raiseBot(botId, currentBet + 1);
                return;
            }
            if (hand.type().power() <= ZjhHandType.HIGH_CARD.power() && random.nextInt(100) < 25) {
                foldedPlayers.add(botId);
                broadcast(text(displayName(botId) + " 弃牌了。", NamedTextColor.RED));
                checkRoundEnd();
                if (phase == ZjhPhase.PLAYING) {
                    advanceTurn();
                }
                return;
            }
            int cost = followCost(botId);
            if (chipStack(botId) < cost) {
                foldedPlayers.add(botId);
                broadcast(text(displayName(botId) + " 筹码不足，自动弃牌。", NamedTextColor.RED));
                checkRoundEnd();
                if (phase == ZjhPhase.PLAYING) {
                    advanceTurn();
                }
                return;
            }
            pay(botId, cost);
            pot += cost;
            roundBets.merge(botId, cost, Integer::sum);
            broadcast(text(displayName(botId) + " 跟注了 " + cost + "。", NamedTextColor.YELLOW));
            advanceTurn();
        } catch (RuntimeException exception) {
            foldedPlayers.add(botId);
            checkRoundEnd();
        }
    }

    private void raiseBot(UUID botId, int bet) {
        currentBet = bet;
        int cost = lookedPlayers.contains(botId) ? bet * 2 : bet;
        pay(botId, cost);
        pot += cost;
        roundBets.merge(botId, cost, Integer::sum);
        broadcast(text(displayName(botId) + " 把明注加到了 " + currentBet + "。", NamedTextColor.GOLD));
        advanceTurn();
    }

    private boolean isBot(UUID playerId) {
        return botNames.containsKey(playerId);
    }

    private List<DoudizhuCard> zjhDeck() {
        List<DoudizhuCard> deck = new ArrayList<>();
        List<CardSuit> suits = List.of(CardSuit.SPADES, CardSuit.HEARTS, CardSuit.CLUBS, CardSuit.DIAMONDS);
        List<CardRank> ranks = List.of(
            CardRank.ACE, CardRank.KING, CardRank.QUEEN, CardRank.JACK, CardRank.TEN, CardRank.NINE,
            CardRank.EIGHT, CardRank.SEVEN, CardRank.SIX, CardRank.FIVE, CardRank.FOUR, CardRank.THREE, CardRank.TWO
        );
        int id = 1000;
        for (CardRank rank : ranks) {
            for (CardSuit suit : suits) {
                deck.add(new DoudizhuCard(id++, rank, suit));
            }
        }
        deck.sort(Comparator.comparingInt(card -> random.nextInt()));
        return deck;
    }

    private void broadcast(Component component) {
        Component full = Component.text("[炸金花 " + name + "] ", NamedTextColor.GOLD).append(component.decoration(TextDecoration.ITALIC, false));
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
