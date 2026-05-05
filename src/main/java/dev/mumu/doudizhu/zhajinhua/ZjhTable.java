package dev.mumu.doudizhu.zhajinhua;

import dev.mumu.doudizhu.DoudizhuPlugin;
import dev.mumu.doudizhu.model.CardRank;
import dev.mumu.doudizhu.model.CardSuit;
import dev.mumu.doudizhu.model.DoudizhuCard;
import dev.mumu.doudizhu.room.TableLevel;
import dev.mumu.doudizhu.ui.MuzTheme;
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
    private TableLevel roomLevel;
    private final Random random = new Random();
    private final List<UUID> seats = new ArrayList<>();
    private final Set<UUID> readyPlayers = new HashSet<>();
    private final Map<UUID, Integer> chipStacks = new LinkedHashMap<>();
    private final Map<UUID, Integer> totalContributions = new LinkedHashMap<>();
    private final Map<UUID, Integer> streetContributions = new LinkedHashMap<>();
    private final Map<UUID, Integer> roundStartingStacks = new LinkedHashMap<>();
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
    private UUID smallBlindPlayer;
    private UUID bigBlindPlayer;
    private int actionEpoch;
    private boolean debugAutoLoop;
    private String lastActionText = "等待开局";
    private Component lastActionComponent = Component.text("等待开局", NamedTextColor.GRAY).decoration(TextDecoration.ITALIC, false);

    public ZjhTable(DoudizhuPlugin plugin, ZjhManager manager, String name, int maxPlayers, TableLevel roomLevel) {
        this.plugin = plugin;
        this.manager = manager;
        this.name = name.trim();
        this.maxPlayers = Math.max(2, Math.min(10, maxPlayers));
        this.roomLevel = roomLevel == null ? TableLevel.FUN : roomLevel;
    }

    public String getName() {
        return name;
    }

    public int getMaxPlayers() {
        return maxPlayers;
    }

    public TableLevel getRoomLevel() {
        return roomLevel;
    }

    public void setRoomLevel(TableLevel roomLevel) {
        this.roomLevel = roomLevel == null ? TableLevel.FUN : roomLevel;
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

    public boolean isFolded(UUID playerId) {
        return foldedPlayers.contains(playerId);
    }

    public boolean isAllIn(UUID playerId) {
        return allInPlayers.contains(playerId);
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

    public String potBreakdownText() {
        List<Integer> levels = totalContributions.values().stream().filter(value -> value > 0).distinct().sorted().toList();
        if (levels.isEmpty()) {
            return "主池 0";
        }
        List<String> parts = new ArrayList<>();
        int previous = 0;
        for (int index = 0; index < levels.size(); index++) {
            int level = levels.get(index);
            int contributors = (int) totalContributions.values().stream().filter(value -> value >= level).count();
            int segment = (level - previous) * contributors;
            previous = level;
            if (segment <= 0) {
                continue;
            }
            parts.add((index == 0 ? "主池 " : "边池" + index + " ") + segment);
        }
        return parts.isEmpty() ? "主池 0" : String.join(" | ", parts);
    }

    public String lastActionText() {
        return lastActionText;
    }

    public Component lastActionComponent() {
        return lastActionComponent;
    }

    public UUID getDealerPlayer() {
        if (dealerIndex < 0 || dealerIndex >= seats.size()) {
            return null;
        }
        return seats.get(dealerIndex);
    }

    public UUID getSmallBlindPlayer() {
        return smallBlindPlayer;
    }

    public UUID getBigBlindPlayer() {
        return bigBlindPlayer;
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
        int fallback = chipStacks.getOrDefault(playerId, DEFAULT_STACK);
        if (playerId == null || isBot(playerId) || !plugin.isTexasRoomEconomyEnabled(roomLevel)) {
            return fallback;
        }
        int synced = plugin.syncTexasChipStack(roomLevel, playerId, fallback);
        chipStacks.put(playerId, synced);
        return synced;
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

    public int streetContribution(UUID playerId) {
        return streetContributions.getOrDefault(playerId, 0);
    }

    public int totalContribution(UUID playerId) {
        return totalContributions.getOrDefault(playerId, 0);
    }

    public int toCall(UUID playerId) {
        return Math.max(0, currentBet - streetContribution(playerId));
    }

    public int suggestedRaiseTo(UUID playerId) {
        int base = Math.max(currentBet + BIG_BLIND, BIG_BLIND * 2);
        return Math.max(base, streetContribution(playerId) + BIG_BLIND);
    }

    public List<Integer> raiseOptions(UUID playerId) {
        int currentStreet = streetContribution(playerId);
        int maxTotal = currentStreet + chipStack(playerId);
        List<Integer> options = new ArrayList<>();
        int minRaise = Math.max(currentBet + BIG_BLIND, currentStreet + BIG_BLIND);
        int twoBlind = Math.max(currentBet + BIG_BLIND * 2, currentStreet + BIG_BLIND * 2);
        int halfPot = currentStreet + Math.max(BIG_BLIND, Math.max(1, getPot() / 2));
        int potRaise = currentStreet + Math.max(BIG_BLIND, Math.max(1, getPot()));
        int doublePot = currentStreet + Math.max(BIG_BLIND, Math.max(1, getPot() * 2));
        for (int value : List.of(minRaise, twoBlind, halfPot, potRaise, doublePot)) {
            if (value > currentBet && value < maxTotal && !options.contains(value)) {
                options.add(value);
            }
        }
        options.sort(Integer::compareTo);
        return options;
    }

    public String positionLabel(UUID playerId) {
        int index = seats.indexOf(playerId);
        if (index < 0 || seats.isEmpty() || dealerIndex < 0) {
            return "";
        }
        int relative = (index - dealerIndex + seats.size()) % seats.size();
        int size = seats.size();
        return switch (size) {
            case 2 -> switch (relative) {
                case 0 -> "BTN/SB";
                case 1 -> "BB";
                default -> "";
            };
            case 3 -> switch (relative) {
                case 0 -> "BTN";
                case 1 -> "SB";
                case 2 -> "BB";
                default -> "";
            };
            case 4 -> switch (relative) {
                case 0 -> "BTN";
                case 1 -> "SB";
                case 2 -> "BB";
                case 3 -> "UTG";
                default -> "";
            };
            case 5 -> switch (relative) {
                case 0 -> "BTN";
                case 1 -> "SB";
                case 2 -> "BB";
                case 3 -> "UTG";
                case 4 -> "CO";
                default -> "";
            };
            case 6 -> switch (relative) {
                case 0 -> "BTN";
                case 1 -> "SB";
                case 2 -> "BB";
                case 3 -> "UTG";
                case 4 -> "HJ";
                case 5 -> "CO";
                default -> "";
            };
            default -> switch (relative) {
                case 0 -> "BTN";
                case 1 -> "SB";
                case 2 -> "BB";
                case 3 -> "UTG";
                case 4 -> "UTG+1";
                case 5 -> "UTG+2";
                case 6 -> "LJ";
                case 7 -> "HJ";
                case 8 -> "CO";
                case 9 -> "CO+1";
                default -> "";
            };
        };
    }

    public void addPlayer(Player player) {
        if (seats.contains(player.getUniqueId())) {
            return;
        }
        ensureLobby();
        if (seats.size() >= maxPlayers) {
            throw new IllegalStateException("德州扑克牌桌已满。");
        }
        if (!plugin.canAffordEntry(player.getUniqueId(), roomLevel)) {
            throw new IllegalStateException(plugin.insufficientEntryMessage(player.getUniqueId(), roomLevel));
        }
        seats.add(player.getUniqueId());
        chipStacks.put(player.getUniqueId(), plugin.syncTexasChipStack(roomLevel, player.getUniqueId(), DEFAULT_STACK));
        announceChat(displayName(player.getUniqueId()) + " 入座", actorUpdate(player.getUniqueId(), MuzTheme.accent("入座"), seats.size() + "/" + maxPlayers + " 已经到桌"));
        refreshPhysical();
    }

    public void removePlayer(Player player, String reason) {
        UUID playerId = player.getUniqueId();
        if (!seats.contains(playerId)) {
            return;
        }
        boolean playing = phase != ZjhPhase.LOBBY;
        announceAction(displayName(playerId) + " 离桌", MuzTheme.field("离桌", MuzTheme.danger(reason)));
        seats.remove(playerId);
        readyPlayers.remove(playerId);
        foldedPlayers.remove(playerId);
        allInPlayers.remove(playerId);
        actedThisStreet.remove(playerId);
        holeCards.remove(playerId);
        streetContributions.remove(playerId);
        totalContributions.remove(playerId);
        if (plugin.isTexasRoomEconomyEnabled(roomLevel) && !isBot(playerId)) {
            chipStacks.remove(playerId);
        }
        if (botNames.remove(playerId) != null) {
            plugin.unregisterBot(playerId);
        }
        manager.unregisterPlayer(playerId);
        if (playing) {
            resetRound();
            return;
        }
        refreshPhysical();
    }

    public UUID addBot(String preferredName) {
        ensureLobby();
        if (seats.size() >= maxPlayers) {
            throw new IllegalStateException("德州扑克牌桌已满。");
        }
        UUID botId = UUID.randomUUID();
        String name = preferredName == null || preferredName.isBlank() ? "TBot-" + nextAvailableBotIndex() : preferredName.trim();
        botNames.put(botId, name);
        plugin.registerBot(botId, this.name, DoudizhuPlugin.BotGameType.TEXAS);
        seats.add(botId);
        chipStacks.putIfAbsent(botId, DEFAULT_STACK);
        readyPlayers.add(botId);
        announceChat(name + " 入座", actorUpdate(Component.text(name, NamedTextColor.WHITE).decoration(TextDecoration.ITALIC, false), MuzTheme.accent("入座"), seats.size() + "/" + maxPlayers + " 已经到桌"));
        refreshPhysical();
        return botId;
    }

    private int nextAvailableBotIndex() {
        int index = 1;
        while (botNames.containsValue("TBot-" + index)) {
            index++;
        }
        return index;
    }

    public UUID removeBot() {
        return removeBot(null);
    }

    public UUID removeBot(String token) {
        ensureLobby();
        UUID target = resolveBotId(token);
        if (target == null) {
            throw new IllegalStateException("当前没有机器人可移除。");
        }
        String botName = displayName(target);
        seats.remove(target);
        readyPlayers.remove(target);
        chipStacks.remove(target);
        botNames.remove(target);
        plugin.unregisterBot(target);
        announceAction(botName + " 离桌", actorUpdate(Component.text(botName, NamedTextColor.WHITE).decoration(TextDecoration.ITALIC, false), MuzTheme.muted("离桌"), seats.size() + "/" + maxPlayers + " 当前在桌"));
        refreshPhysical();
        return target;
    }

    public void toggleReady(Player player) {
        requireAtTable(player.getUniqueId());
        ensureLobby();
        if (!readyPlayers.contains(player.getUniqueId()) && !plugin.canAffordEntry(player.getUniqueId(), roomLevel)) {
            throw new IllegalStateException(plugin.insufficientEntryMessage(player.getUniqueId(), roomLevel));
        }
        if (!readyPlayers.remove(player.getUniqueId())) {
            readyPlayers.add(player.getUniqueId());
        announceChat(displayName(player.getUniqueId()) + " 已准备", actorUpdate(player.getUniqueId(), MuzTheme.success("已准备"), "再等等其他玩家"));
        } else {
        announceChat(displayName(player.getUniqueId()) + " 取消准备", actorUpdate(player.getUniqueId(), MuzTheme.muted("取消准备"), "先等等，随时都能再准备"));
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
        for (UUID seat : seats) {
            if (!isBot(seat) && !plugin.canAffordEntry(seat, roomLevel)) {
                throw new IllegalStateException(displayName(seat) + " 资格不足: " + plugin.insufficientEntryMessage(seat, roomLevel));
            }
        }
        phase = ZjhPhase.PLAYING;
        street = TexasStreet.PRE_FLOP;
        foldedPlayers.clear();
        allInPlayers.clear();
        actedThisStreet.clear();
        holeCards.clear();
        streetContributions.clear();
        totalContributions.clear();
        roundStartingStacks.clear();
        communityCards.clear();
        currentBet = 0;

        dealerIndex = seats.isEmpty() ? -1 : (dealerIndex + 1 + seats.size()) % seats.size();
        List<DoudizhuCard> deck = holdemDeck();
        for (UUID seat : seats) {
            chipStacks.putIfAbsent(seat, DEFAULT_STACK);
            roundStartingStacks.put(seat, chipStack(seat));
            holeCards.put(seat, List.of(deck.removeLast(), deck.removeLast()));
            streetContributions.put(seat, 0);
            totalContributions.put(seat, 0);
        }

        int smallBlindIndex = nextSeatIndex(dealerIndex);
        int bigBlindIndex = nextSeatIndex(smallBlindIndex);
        smallBlindPlayer = seats.get(smallBlindIndex);
        bigBlindPlayer = seats.get(bigBlindIndex);
        postBlind(smallBlindPlayer, SMALL_BLIND);
        postBlind(bigBlindPlayer, BIG_BLIND);
        currentBet = Math.max(streetContributions.getOrDefault(seats.get(bigBlindIndex), 0), BIG_BLIND);
        currentTurn = seats.get(nextSeatIndex(bigBlindIndex));
        actionEpoch++;
        announceAction("新局开始", actorUpdate(senderIdentity(sender, NamedTextColor.WHITE), MuzTheme.warm("新局开始"), "小盲 " + SMALL_BLIND + " · 大盲 " + BIG_BLIND));
        if (activeActors().isEmpty()) {
            runOutBoardAndShowdown();
            return;
        }
        refreshPhysical();
        runBotActionIfNeeded();
    }

    public void showHand(Player player) {
        requireAtTable(player.getUniqueId());
        player.sendMessage(MuzTheme.banner("德州", name + " 号桌", MuzTheme.field("底牌", MuzTheme.body(handText(player.getUniqueId())))));
        if (communityCards.size() >= 3) {
            TexasHand best = TexasEvaluator.evaluateBest(allCards(player.getUniqueId()));
            player.sendMessage(MuzTheme.field("当前最佳", MuzTheme.warm(best.displayName())));
        }
    }

    public void check(Player player) {
        requireActingPlayer(player);
        if (streetContributions.getOrDefault(player.getUniqueId(), 0) != currentBet) {
            throw new IllegalStateException("当前不能过牌，请选择跟注或弃牌。");
        }
        actedThisStreet.add(player.getUniqueId());
        announceAction(displayName(player.getUniqueId()) + " 过牌", actorUpdate(player.getUniqueId(), MuzTheme.muted("过牌"), "这一手先过"));
        playTableSound("minecraft:block.stone_button.click_on", 0.35f, 1.10f);
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
        announceAction(displayName(player.getUniqueId()) + " 跟注 " + toCall, actorUpdate(player.getUniqueId(), MuzTheme.accent("跟注"), toCall + " 筹码"));
        playTableSound("minecraft:entity.experience_orb.pickup", 0.35f, 1.00f);
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
        announceAction(displayName(player.getUniqueId()) + " 加注到 " + currentBet, actorUpdate(player.getUniqueId(), MuzTheme.warm("加注"), "抬到 " + currentBet));
        playTableSound("minecraft:block.note_block.bell", 0.45f, 1.05f);
        advanceAfterAction();
    }

    public void fold(Player player) {
        requireActingPlayer(player);
        foldedPlayers.add(player.getUniqueId());
        actedThisStreet.add(player.getUniqueId());
        announceAction(displayName(player.getUniqueId()) + " 弃牌", actorUpdate(player.getUniqueId(), MuzTheme.danger("弃牌"), "这一手先不继续了"));
        playTableSound("minecraft:item.shield.break", 0.30f, 0.90f);
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
        announceAction(displayName(player.getUniqueId()) + " ALL-IN", actorUpdate(player.getUniqueId(), MuzTheme.warning("全下"), "把这一手能用的筹码全压上"));
        playTableSound("minecraft:entity.ender_dragon.growl", 0.18f, 1.25f);
        advanceAfterAction();
    }

    public List<Component> buildStatusLines() {
        List<Component> lines = new ArrayList<>();
        lines.add(MuzTheme.header("德州", name + " 号桌", plugin.roomDisplayTag(roomLevel)));
        lines.add(MuzTheme.field("街道", MuzTheme.accent(street.displayName())));
        lines.add(MuzTheme.field("人数", MuzTheme.body(seats.size() + "/" + maxPlayers)));
        lines.add(MuzTheme.field("底池", MuzTheme.body(potBreakdownText() + " · 当前下注 " + currentBet)));
        lines.add(MuzTheme.field("最近动作", lastActionComponent));
        if (!communityCards.isEmpty()) {
            lines.add(MuzTheme.field("公共牌", MuzTheme.warm(communityCards.stream().map(DoudizhuCard::displayLabel).collect(Collectors.joining(" ")))));
        }
        if (dealerIndex >= 0 && dealerIndex < seats.size()) {
            lines.add(MuzTheme.field("按钮位", identity(seats.get(dealerIndex), NamedTextColor.WHITE)));
        }
        for (UUID seat : seats) {
            List<Component> details = new ArrayList<>();
            Integer botNumericId = plugin.getBotNumericId(seat);
            if (botNumericId != null) {
                details.add(MuzTheme.muted("Bot " + botNumericId));
            }
            String position = positionLabel(seat);
            if (!position.isEmpty()) {
                details.add(MuzTheme.accent(position));
            }
            details.add(MuzTheme.muted("筹码 " + chipStack(seat)));
            if (readyPlayers.contains(seat) && phase == ZjhPhase.LOBBY) {
                details.add(MuzTheme.success("已准备"));
            }
            if (foldedPlayers.contains(seat)) {
                details.add(MuzTheme.danger("已弃牌"));
            }
            if (allInPlayers.contains(seat)) {
                details.add(MuzTheme.warning("ALL-IN"));
            }
            if (Objects.equals(seat, smallBlindPlayer)) {
                details.add(MuzTheme.muted("SB"));
            }
            if (Objects.equals(seat, bigBlindPlayer)) {
                details.add(MuzTheme.muted("BB"));
            }
            if (Objects.equals(currentTurn, seat)) {
                details.add(MuzTheme.warm("当前操作"));
            }
            lines.add(MuzTheme.row(identity(seat, NamedTextColor.WHITE), details));
        }
        return lines;
    }

    public void forceEnd(CommandSender sender) {
        if (phase == ZjhPhase.LOBBY) {
            throw new IllegalStateException("当前没有德州扑克牌局。");
        }
        announceAction(sender.getName() + " 强制结束", actorUpdate(senderIdentity(sender, NamedTextColor.WHITE), MuzTheme.danger("强制结束"), "当前德州对局已终止"));
        resetRound();
    }

    public void shutdown() {
        debugAutoLoop = false;
        resetRound();
    }

    public void forceClose(String reason) {
        debugAutoLoop = false;
        for (UUID seat : new ArrayList<>(seats)) {
            if (!isBot(seat)) {
                Player player = Bukkit.getPlayer(seat);
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
        chipStacks.clear();
        roundStartingStacks.clear();
        totalContributions.clear();
        streetContributions.clear();
        holeCards.clear();
        foldedPlayers.clear();
        allInPlayers.clear();
        actedThisStreet.clear();
        botNames.clear();
        communityCards.clear();
        phase = ZjhPhase.LOBBY;
        street = TexasStreet.PRE_FLOP;
        currentTurn = null;
        currentBet = 0;
        dealerIndex = -1;
        smallBlindPlayer = null;
        bigBlindPlayer = null;
        actionEpoch++;
        setLastActionText("等待开局", text("等待开局", NamedTextColor.GRAY));
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
                player.sendActionBar(text("轮到你了 · 用桌边按钮就能操作", NamedTextColor.AQUA));
            } else {
                player.sendActionBar(append(text("当前由 ", NamedTextColor.GRAY), identity(currentTurn, NamedTextColor.YELLOW), text(" 在行动 · " + street.displayName(), NamedTextColor.GRAY)));
            }
        }
    }

    public void enableDebugAutoLoop() {
        debugAutoLoop = true;
    }

    public void disableDebugAutoLoop() {
        debugAutoLoop = false;
    }

    private void requireActingPlayer(Player player) {
        requireAtTable(player.getUniqueId());
        ensurePlaying();
        if (!Objects.equals(currentTurn, player.getUniqueId())) {
            throw new IllegalStateException("先等等，这手还没轮到你。");
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
        if (paid > 0 && plugin.isTexasRoomEconomyEnabled(roomLevel) && !isBot(playerId)) {
            chipStacks.put(playerId, plugin.withdrawTexasChips(roomLevel, playerId, paid, stack));
        } else {
            chipStacks.put(playerId, stack - paid);
        }
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
        int pot = getPot();
        awardChips(winner, pot);
        announceAction(displayName(winner) + " 收池", actorUpdate(winner, MuzTheme.warm("收池"), "把底池 " + pot + " 收下"));
        List<UUID> finalWinners = List.of(winner);
        Map<UUID, DoudizhuPlugin.SettlementResult> settlementSnapshots = buildRoundSettlementSnapshot(finalWinners);
        sendParticipantSettlementMessages(finalWinners, settlementSnapshots);
        plugin.recordTexasMatch(this, finalWinners, settlementSnapshots);
        setLastActionText(displayName(winner) + " 收下底池", append(identity(winner, NamedTextColor.GOLD), text(" 收下底池", NamedTextColor.GOLD)));
        playTableSound("minecraft:ui.toast.challenge_complete", 0.35f, 1.00f);
        resetRound();
        if (canScheduleTasks() && debugAutoLoop && seats.size() >= 2 && seats.stream().allMatch(this::isBot)) {
            plugin.scheduler().runLater(2L, () -> {
                try {
                    startRound(plugin.getServer().getConsoleSender());
                } catch (RuntimeException ignored) {
                }
            });
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
                announceAction("翻牌圈开始", MuzTheme.field("翻牌圈", MuzTheme.warm(communityText())));
                playTableSound("minecraft:block.amethyst_block.step", 0.30f, 1.00f);
            }
            case FLOP -> {
                street = TexasStreet.TURN;
                communityCards.addAll(drawCommunity(1));
                announceAction("转牌圈开始", MuzTheme.field("转牌圈", MuzTheme.warm(communityText())));
                playTableSound("minecraft:block.amethyst_block.step", 0.32f, 1.05f);
            }
            case TURN -> {
                street = TexasStreet.RIVER;
                communityCards.addAll(drawCommunity(1));
                announceAction("河牌圈开始", MuzTheme.field("河牌圈", MuzTheme.warm(communityText())));
                playTableSound("minecraft:block.amethyst_block.step", 0.34f, 1.10f);
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
        List<UUID> winners = List.of();
        if (!hands.isEmpty()) {
            TexasHand best = hands.values().stream().max(TexasEvaluator::compare).orElseThrow();
            winners = hands.entrySet().stream()
                .filter(entry -> TexasEvaluator.compare(entry.getValue(), best) == 0)
                .map(Map.Entry::getKey)
                .toList();
        }
        distributeSidePots(hands);
        if (!winners.isEmpty()) {
            List<UUID> finalWinners = winners;
            Map<UUID, DoudizhuPlugin.SettlementResult> settlementSnapshots = buildRoundSettlementSnapshot(finalWinners);
            sendParticipantSettlementMessages(finalWinners, settlementSnapshots);
            plugin.recordTexasMatch(this, winners, settlementSnapshots);
        }
        resetRound();
        if (canScheduleTasks() && debugAutoLoop && seats.size() >= 2 && seats.stream().allMatch(this::isBot)) {
            plugin.scheduler().runLater(2L, () -> {
                try {
                    startRound(plugin.getServer().getConsoleSender());
                } catch (RuntimeException ignored) {
                }
            });
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
                awardChips(winner, share);
            }
            for (int index = 0; index < remainder; index++) {
                awardChips(winners.get(index), 1);
            }
            Component winnerLine = Component.empty();
            for (int index = 0; index < winners.size(); index++) {
                if (index > 0) {
                    winnerLine = winnerLine.append(text(", ", NamedTextColor.GOLD));
                }
                winnerLine = winnerLine.append(identity(winners.get(index), NamedTextColor.GOLD));
            }
            winnerLine = actorUpdate(winnerLine, MuzTheme.warm("分池"), "赢得 " + segment + " · " + best.displayName());
            broadcastActionBar(winnerLine);
            setLastActionText(winners.stream().map(this::displayName).collect(Collectors.joining(", ")) + " 赢得 " + segment, winnerLine);
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

    private void awardChips(UUID playerId, int chips) {
        if (playerId == null || chips <= 0) {
            return;
        }
        int fallback = chipStacks.getOrDefault(playerId, DEFAULT_STACK);
        if (plugin.isTexasRoomEconomyEnabled(roomLevel) && !isBot(playerId)) {
            chipStacks.put(playerId, plugin.depositTexasChips(roomLevel, playerId, chips, fallback));
        } else {
            chipStacks.merge(playerId, chips, Integer::sum);
        }
    }

    private Map<UUID, DoudizhuPlugin.SettlementResult> buildRoundSettlementSnapshot(List<UUID> winners) {
        Map<UUID, DoudizhuPlugin.SettlementResult> snapshot = new LinkedHashMap<>();
        for (UUID seat : seats) {
            int start = roundStartingStacks.getOrDefault(seat, chipStack(seat));
            int end = chipStack(seat);
            double delta = end - start;
            DoudizhuPlugin.SettlementResult current = isBot(seat)
                ? new DoudizhuPlugin.SettlementResult(delta, 0.0, end, false, false, "筹码")
                : plugin.currentRoomStatus(roomLevel, seat);
            snapshot.put(seat, new DoudizhuPlugin.SettlementResult(
                delta,
                current.debt(),
                current.postBalance(),
                current.bankrupt(),
                current.insufficientForRoom(),
                current.unitLabel()
            ));
        }
        return snapshot;
    }

    private int nextSeatIndex(int currentIndex) {
        return (currentIndex + 1 + seats.size()) % seats.size();
    }

    private Component outcomeLine(List<UUID> winners, List<UUID> losers) {
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

    private void sendParticipantSettlementMessages(List<UUID> winners, Map<UUID, DoudizhuPlugin.SettlementResult> settlementSnapshots) {
        for (UUID seat : seats) {
            if (isBot(seat)) {
                continue;
            }
            Player player = Bukkit.getPlayer(seat);
            if (player == null) {
                continue;
            }
            DoudizhuPlugin.SettlementResult result = settlementSnapshots.getOrDefault(seat, plugin.currentRoomStatus(roomLevel, seat));
            Component message = MuzTheme.banner("德州", name + " 号桌", winners.contains(seat) ? MuzTheme.success("本局结果") : MuzTheme.danger("本局结果"))
                .append(Component.newline())
                .append(settlementLine(seat, result));
            List<UUID> others = seats.stream()
                .filter(other -> !other.equals(seat))
                .toList();
            if (!others.isEmpty()) {
                for (UUID other : others) {
                    DoudizhuPlugin.SettlementResult otherResult = settlementSnapshots.getOrDefault(other, plugin.currentRoomStatus(roomLevel, other));
                    message = message.append(Component.newline())
                        .append(settlementLine(other, otherResult));
                }
            }
            player.sendMessage(message.decoration(TextDecoration.ITALIC, false));
        }
    }

    private Component settlementLine(UUID playerId, DoudizhuPlugin.SettlementResult result) {
        String amount = plugin.formatCompactAmount(Math.abs(result.delta()));
        String role = positionLabel(playerId);
        if (role == null || role.isBlank()) {
            role = isBot(playerId) ? "机器人" : "玩家";
        }
        Component line = identity(playerId, NamedTextColor.WHITE)
            .append(MuzTheme.divider(" · "))
            .append(MuzTheme.warm(role));
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

    private boolean canScheduleTasks() {
        return plugin.isEnabled() && !plugin.isShuttingDown();
    }

    private void runBotActionIfNeeded() {
        if (!canScheduleTasks() || currentTurn == null || !isBot(currentTurn)) {
            return;
        }
        int epoch = ++actionEpoch;
        long delay = plugin.randomBotActionDelayTicks(random);
        plugin.scheduler().runLater(delay, () -> {
            if (epoch != actionEpoch || currentTurn == null || !isBot(currentTurn) || phase != ZjhPhase.PLAYING) {
                return;
            }
            executeBotTurn(currentTurn);
        });
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
                    announceAction(displayName(botId) + " 加注到 " + currentBet, actorUpdate(botId, MuzTheme.warm("加注"), "抬到 " + currentBet));
                    advanceAfterAction();
                    return;
                }
            }
            if (toCall == 0) {
                actedThisStreet.add(botId);
        announceAction(displayName(botId) + " 过牌", actorUpdate(botId, MuzTheme.muted("过牌"), "这一手先过"));
                advanceAfterAction();
                return;
            }
            if (best != null && best.type().power() <= TexasHandType.ONE_PAIR.power() && toCall >= BIG_BLIND * 4 && random.nextInt(100) < 45) {
                foldedPlayers.add(botId);
                actedThisStreet.add(botId);
        announceAction(displayName(botId) + " 弃牌", actorUpdate(botId, MuzTheme.danger("弃牌"), "这一手先不继续了"));
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
        announceAction(displayName(botId) + " ALL-IN", actorUpdate(botId, MuzTheme.warning("全下"), "把这一手能用的筹码全压上"));
                advanceAfterAction();
                return;
            }
            contribute(botId, toCall);
            actedThisStreet.add(botId);
            announceAction(displayName(botId) + " 跟注 " + toCall, actorUpdate(botId, MuzTheme.accent("跟注"), toCall + " 筹码"));
            advanceAfterAction();
        } catch (RuntimeException exception) {
            foldedPlayers.add(botId);
            finishIfOneLeft();
        }
    }

    public boolean isBot(UUID playerId) {
        return botNames.containsKey(playerId);
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
        smallBlindPlayer = null;
        bigBlindPlayer = null;
        refreshPhysical();
    }

    private void broadcast(Component component) {
        Component full = MuzTheme.banner("德州", name + " 号桌", component);
        Component actionBar = component.decoration(TextDecoration.ITALIC, false);
        for (UUID seat : seats) {
            Player player = Bukkit.getPlayer(seat);
            if (player != null) {
                player.sendMessage(full);
                player.sendActionBar(actionBar);
            }
        }
    }

    private void broadcastActionBar(Component component) {
        Component actionBar = component.decoration(TextDecoration.ITALIC, false);
        for (UUID seat : seats) {
            Player player = Bukkit.getPlayer(seat);
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

    private Component text(String message, NamedTextColor color) {
        return MuzTheme.named(message, color).decoration(TextDecoration.ITALIC, false);
    }

    private void setLastActionText(String plainText, Component component) {
        lastActionText = plainText;
        lastActionComponent = component == null ? text(plainText, NamedTextColor.GRAY) : component.decoration(TextDecoration.ITALIC, false);
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

    private void refreshPhysical() {
        if (plugin.isShuttingDown()) {
            return;
        }
        plugin.getZjhPhysicalTableManager().refresh(this);
    }

    private void playTableSound(String sound, float volume, float pitch) {
        float finalVolume = Math.max(0.0f, Math.min(2.0f, volume * plugin.getEffectVolume()));
        if (finalVolume <= 0.0f) {
            return;
        }
        for (UUID seat : seats) {
            Player player = Bukkit.getPlayer(seat);
            if (player != null) {
                player.playSound(player.getLocation(), sound, finalVolume, pitch);
            }
        }
    }
}

