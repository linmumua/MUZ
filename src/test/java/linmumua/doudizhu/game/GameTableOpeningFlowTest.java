package linmumua.doudizhu.game;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.Collections;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.logging.Logger;
import linmumua.doudizhu.DoudizhuPlugin;
import linmumua.doudizhu.model.CardRank;
import linmumua.doudizhu.model.CardSuit;
import linmumua.doudizhu.model.DoudizhuCard;
import linmumua.doudizhu.scheduler.MuzScheduler;
import linmumua.doudizhu.world.PhysicalTableManager;
import org.bukkit.Bukkit;
import org.bukkit.Server;
import org.bukkit.entity.Player;
import org.junit.jupiter.api.Test;
import sun.misc.Unsafe;

/**
 * 开局时间线接入 GameTable 的行为回归。
 *
 * <p>GameTable 构造器会装配 Bukkit、资源包和多个运行期服务，因此这里沿用现有测试的
 * Unsafe 夹具，只补齐本轮实际调用路径需要的字段；开局协调器、资格判断、公开倍率和
 * reset/redeal 均直接调用生产方法，不复制实现逻辑。
 */
class GameTableOpeningFlowTest {
    @Test
    void threeHumansCanRevealIndependentlyAndRepeatedRevealDoesNotStackPublicFactor() throws Exception {
        UUID first = UUID.randomUUID();
        UUID second = UUID.randomUUID();
        UUID third = UUID.randomUUID();
        GameTable table = openingTable(List.of(first, second, third), Set.of(), Map.of());

        withPlayers(Map.of(
            first, player(first, "first", true),
            second, player(second, "second", true),
            third, player(third, "third", true)
        ), () -> {
            RoundOpeningCoordinator coordinator = startReveal(table);
            assertTrue(table.canRevealHand(first));
            assertTrue(table.canRevealHand(second));
            assertTrue(table.canRevealHand(third));

            table.revealHand(first);
            table.revealHand(second);
            table.revealHand(third);

            assertTrue(table.isHandRevealed(first));
            assertTrue(table.isHandRevealed(second));
            assertTrue(table.isHandRevealed(third));
            assertEquals(2, table.publicRevealMultiplier(), "多人明牌仍只有一个公共 ×2");
            assertEquals(1, table.getRevealCount(first));
            assertThrows(IllegalStateException.class, () -> table.revealHand(first), "重复明牌必须拒绝");
            assertEquals(GamePhase.REVEALING, table.getPhase());
            assertTrue(coordinator.isRevealWindowOpen());
            return null;
        });
    }

    @Test
    void offlineHumanAndBotCannotRevealButOnlineHumanCan() throws Exception {
        UUID online = UUID.randomUUID();
        UUID offline = UUID.randomUUID();
        UUID bot = UUID.randomUUID();
        GameTable table = openingTable(List.of(online, offline, bot), Set.of(bot), Map.of(bot, "Bot-1"));

        withPlayers(Map.of(
            online, player(online, "online", true),
            offline, player(offline, "offline", false),
            bot, player(bot, "bot", true)
        ), () -> {
            startReveal(table);
            assertTrue(table.canRevealHand(online));
            assertFalse(table.canRevealHand(offline), "离线真人不应获得明牌资格");
            assertFalse(table.canRevealHand(bot), "机器人不应获得明牌资格");
            assertThrows(IllegalStateException.class, () -> table.revealHand(offline));
            assertThrows(IllegalStateException.class, () -> table.revealHand(bot));
            table.revealHand(online);
            assertEquals(2, table.publicRevealMultiplier());
            return null;
        });
    }

    @Test
    void revealAfterWindowExpiryIsRejectedAndBiddingIsRejectedDuringBothOpeningPhases() throws Exception {
        UUID first = UUID.randomUUID();
        UUID second = UUID.randomUUID();
        UUID third = UUID.randomUUID();
        GameTable table = openingTable(List.of(first, second, third), Set.of(), Map.of());

        withPlayers(Map.of(
            first, player(first, "first", true),
            second, player(second, "second", true),
            third, player(third, "third", true)
        ), () -> {
            RoundOpeningCoordinator coordinator = openingCoordinator(table);
            coordinator.start(deck(54), settings(3, 8, 3));
            assertEquals(GamePhase.DEALING, table.getPhase());
            assertThrows(IllegalStateException.class, () -> table.bid(player(first, "first", true), 1));

            int revealGuard = 0;
            while (!coordinator.isRevealWindowOpen() && revealGuard++ < 200) {
                coordinator.tick();
            }
            assertTrue(coordinator.isRevealWindowOpen(), "测试夹具必须在有限 tick 内进入明牌窗口");
            assertEquals(GamePhase.REVEALING, table.getPhase());
            assertThrows(IllegalStateException.class, () -> table.bid(player(first, "first", true), 1));

            int expiryGuard = 0;
            while (coordinator.isRevealWindowOpen() && expiryGuard++ < 200) {
                coordinator.tick();
            }
            assertFalse(coordinator.isRevealWindowOpen(), "明牌窗口必须在有限 tick 内结束");
            assertEquals(GamePhase.BIDDING, table.getPhase());
            assertThrows(IllegalStateException.class, () -> table.revealHand(first), "过期回调不能再明牌");
            return null;
        });
    }

    @Test
    void liveAndResolvedCoreScoreAgreeWhenNoSpringAndRevealIsClampedToOnePublicFactor() throws Exception {
        GameTable table = openingTable(List.of(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID()), Set.of(), Map.of());
        UUID landlord = table.getSeats().getFirst();
        setField(table, "landlord", landlord);
        setField(table, "highestBid", 3);
        setField(table, "revealMultiplier", 2);
        setField(table, "bombMultiplier", 4);
        Map<UUID, Integer> playedHands = new HashMap<>();
        playedHands.put(landlord, 2);
        playedHands.put(table.getSeats().get(1), 1);
        playedHands.put(table.getSeats().get(2), 1);
        setField(table, "playedHandCounts", playedHands);

        assertEquals(24, invokeInt(table, "liveCoreScore"));
        assertEquals(24, invokeInt(table, "resolvedCoreScore", false));

        setField(table, "revealMultiplier", 99);
        assertEquals(24, invokeInt(table, "liveCoreScore"), "公共明牌因子只能从 1 提升到 2");
        assertEquals(24, invokeInt(table, "resolvedCoreScore", false));
    }

    @Test
    void resetAndRedealClearRevealMultiplierCountsAndOpeningState() throws Exception {
        UUID first = UUID.randomUUID();
        UUID second = UUID.randomUUID();
        UUID third = UUID.randomUUID();
        GameTable table = openingTable(List.of(first, second, third), Set.of(), Map.of());
        withPlayers(Map.of(
            first, player(first, "first", true),
            second, player(second, "second", true),
            third, player(third, "third", true)
        ), () -> {
            setField(table, "revealMultiplier", 2);
            setField(table, "revealedHandPlayers", new java.util.HashSet<>(Set.of(first, second)));
            invoke(table, "resetRemainingRankCounts");
            invoke(table, "prepareFreshRoundState");

            assertEquals(GamePhase.DEALING, table.getPhase());
            assertEquals(1, table.publicRevealMultiplier());
            assertEquals(54, table.getRemainingCounts().values().stream().mapToInt(Integer::intValue).sum());
            assertFalse(table.isHandRevealed(first));

            setField(table, "revealMultiplier", 2);
            setField(table, "revealedHandPlayers", new java.util.HashSet<>(Set.of(first)));
            invoke(table, "resetRoundStateForLobby");

            assertEquals(GamePhase.LOBBY, table.getPhase());
            assertEquals(1, table.publicRevealMultiplier());
            assertTrue(table.getRemainingCounts().isEmpty(), "回大厅后不能残留上一局记牌器");
            assertFalse(table.isHandRevealed(first));

            invoke(table, "prepareFreshRoundState");
            assertEquals(GamePhase.DEALING, table.getPhase());
            assertEquals(1, table.publicRevealMultiplier(), "重新发牌必须重新从公共 ×1 开始");
            assertEquals(54, table.getRemainingCounts().values().stream().mapToInt(Integer::intValue).sum());
            return null;
        });
    }

    @Test
    void cancellingOldOpeningTimelineBeforeStartingNewOneDoesNotLeakOldCardsOrPhase() throws Exception {
        List<UUID> seats = List.of(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID());
        GameTable table = openingTable(seats, Set.of(), Map.of());
        OpeningSupport support = new OpeningSupport(table);
        RoundOpeningCoordinator coordinator = new RoundOpeningCoordinator(support);
        setField(table, "roundOpeningCoordinator", coordinator);
        coordinator.start(deck(54), settings(3, 8, 6));
        coordinator.tick();
        assertEquals(1, table.getHand(seats.getFirst()).size());

        coordinator.cancel();
        assertFalse(coordinator.isActive());
        int refreshesAfterCancel = support.refreshes;
        coordinator.tick();
        assertEquals(refreshesAfterCancel, support.refreshes);

        setField(table, "hands", new HashMap<>());
        coordinator.start(deck(54), settings(3, 8, 6));
        coordinator.tick();
        assertEquals(1, table.getHand(seats.getFirst()).size(), "新局只应收到新时间线的第一张牌");
        assertEquals(GamePhase.DEALING, table.getPhase());
    }

    private static GameTable openingTable(List<UUID> seats, Set<UUID> bots, Map<UUID, String> botNames) throws Exception {
        GameTable table = (GameTable) unsafe().allocateInstance(GameTable.class);
        DoudizhuPlugin plugin = (DoudizhuPlugin) unsafe().allocateInstance(DoudizhuPlugin.class);
        setField(plugin, "physicalTableManager", new PhysicalTableManager(plugin));
        setPluginLogger(plugin);
        setField(table, "plugin", plugin);
        setField(table, "name", "opening-test");
        setField(table, "seats", new ArrayList<>(seats));
        setField(table, "readyPlayers", Collections.newSetFromMap(new HashMap<UUID, Boolean>()));
        setField(table, "totalScores", new LinkedHashMap<UUID, Integer>());
        setField(table, "bids", new LinkedHashMap<UUID, Integer>());
        setField(table, "tieBreakBids", new LinkedHashMap<UUID, Integer>());
        setField(table, "roles", new HashMap<UUID, PlayerRole>());
        setField(table, "hands", new HashMap<UUID, List<DoudizhuCard>>());
        setField(table, "selections", new HashMap<UUID, Set<Integer>>());
        setField(table, "playedHandCounts", new HashMap<UUID, Integer>());
        setField(table, "remainingRankCounts", new EnumMap<CardRank, Integer>(CardRank.class));
        setField(table, "botNames", new LinkedHashMap<>(botNames));
        setField(table, "revealedHandPlayers", new java.util.HashSet<UUID>());
        setField(table, "farmerBoostChoices", new LinkedHashMap<UUID, Integer>());
        setField(table, "recentLobbyEntries", new ArrayList<net.kyori.adventure.text.Component>());
        setField(table, "recentTrickEntries", new ArrayList<>());
        setField(table, "random", new Random(1L));
        setField(table, "phase", GamePhase.LOBBY);
        setField(table, "bombMultiplier", 1);
        setField(table, "revealMultiplier", 1);
        setField(table, "effectCoordinator", new TableEffectCoordinator(plugin, new Random(1L), table::getSeats, Bukkit::getPlayer));
        setField(table, "actionBarOverlay", new ActionBarOverlayService(plugin));
        setField(table, "timedOutPlayCoordinator", unsafe().allocateInstance(TimedOutPlayCoordinator.class));
        setField(table, "openingSettings", settings(3, 8, 6));
        return table;
    }

    private static RoundOpeningCoordinator startReveal(GameTable table) throws Exception {
        RoundOpeningCoordinator coordinator = openingCoordinator(table);
        coordinator.start(deck(54), settings(3, 8, 6));
        int guard = 0;
        while (!coordinator.isRevealWindowOpen() && guard++ < 200) {
            coordinator.tick();
        }
        assertTrue(coordinator.isRevealWindowOpen(), "测试夹具必须进入真实明牌窗口");
        return coordinator;
    }

    private static RoundOpeningCoordinator openingCoordinator(GameTable table) throws Exception {
        OpeningSupport support = new OpeningSupport(table);
        RoundOpeningCoordinator coordinator = new RoundOpeningCoordinator(support);
        setField(table, "roundOpeningCoordinator", coordinator);
        return coordinator;
    }

    private static RoundOpeningSettings settings(int batchInterval, int flipTicks, int revealTicks) {
        return new RoundOpeningSettings(
            batchInterval,
            flipTicks,
            revealTicks,
            new RoundOpeningSettings.Messages("发牌", "翻转", "明牌 %seconds%", "明牌", "明牌 ×2", "结束", "已明", "机器人", "不可明")
        );
    }

    private static List<DoudizhuCard> deck(int size) {
        List<DoudizhuCard> deck = new ArrayList<>();
        CardRank[] ranks = CardRank.values();
        CardSuit[] suits = CardSuit.values();
        for (int id = 0; id < size; id++) {
            deck.add(new DoudizhuCard(id, ranks[id % ranks.length], suits[id % suits.length]));
        }
        return deck;
    }

    private static int invokeInt(GameTable table, String methodName, Object... args) throws Exception {
        Class<?>[] types = new Class<?>[args.length];
        for (int index = 0; index < args.length; index++) {
            types[index] = args[index] instanceof Boolean ? boolean.class : args[index].getClass();
        }
        Method method = GameTable.class.getDeclaredMethod(methodName, types);
        method.setAccessible(true);
        return (int) method.invoke(table, args);
    }

    private static Object invoke(GameTable table, String methodName, Object... args) throws Exception {
        Class<?>[] types = new Class<?>[args.length];
        for (int index = 0; index < args.length; index++) {
            types[index] = args[index].getClass();
        }
        Method method = GameTable.class.getDeclaredMethod(methodName, types);
        method.setAccessible(true);
        return method.invoke(table, args);
    }

    private static Player player(UUID id, String name, boolean online) {
        return (Player) Proxy.newProxyInstance(
            Player.class.getClassLoader(),
            new Class<?>[] {Player.class},
            (proxy, method, args) -> switch (method.getName()) {
                case "getUniqueId" -> id;
                case "getName" -> name;
                case "isOnline" -> online;
                case "getPlayerProfile" -> null;
                default -> defaultValue(method.getReturnType());
            }
        );
    }

    private static <T> T withPlayers(Map<UUID, Player> players, Callable<T> action) throws Exception {
        Field serverField = Bukkit.class.getDeclaredField("server");
        serverField.setAccessible(true);
        Server previous = (Server) serverField.get(null);
        Server server = (Server) Proxy.newProxyInstance(
            Server.class.getClassLoader(),
            new Class<?>[] {Server.class},
            (proxy, method, args) -> {
                if (method.getName().equals("getPlayer") && args != null && args.length == 1) {
                    if (args[0] instanceof UUID uuid) {
                        return players.get(uuid);
                    }
                    if (args[0] instanceof String name) {
                        return players.values().stream().filter(player -> player.getName().equals(name)).findFirst().orElse(null);
                    }
                }
                return defaultValue(method.getReturnType());
            }
        );
        serverField.set(null, server);
        try {
            return action.call();
        } finally {
            serverField.set(null, previous);
        }
    }

    private static Object defaultValue(Class<?> type) {
        if (!type.isPrimitive()) {
            return null;
        }
        if (type == boolean.class) {
            return false;
        }
        if (type == byte.class) {
            return (byte) 0;
        }
        if (type == short.class) {
            return (short) 0;
        }
        if (type == int.class) {
            return 0;
        }
        if (type == long.class) {
            return 0L;
        }
        if (type == float.class) {
            return 0.0f;
        }
        if (type == double.class) {
            return 0.0d;
        }
        if (type == char.class) {
            return '\0';
        }
        return null;
    }

    private static void setPluginLogger(DoudizhuPlugin plugin) throws Exception {
        setField(plugin, "logger", Logger.getLogger("GameTableOpeningFlowTest"));
    }

    private static void setField(Object target, String fieldName, Object value) throws Exception {
        Field field = findField(target.getClass(), fieldName);
        field.setAccessible(true);
        field.set(target, value);
    }

    private static Field findField(Class<?> type, String fieldName) throws NoSuchFieldException {
        Class<?> current = type;
        while (current != null) {
            try {
                return current.getDeclaredField(fieldName);
            } catch (NoSuchFieldException ignored) {
                current = current.getSuperclass();
            }
        }
        throw new NoSuchFieldException(fieldName);
    }

    private static Unsafe unsafe() throws Exception {
        Field field = Unsafe.class.getDeclaredField("theUnsafe");
        field.setAccessible(true);
        return (Unsafe) field.get(null);
    }

    private static final class OpeningSupport implements RoundOpeningCoordinator.Support {
        private final GameTable table;
        private int refreshes;

        private OpeningSupport(GameTable table) {
            this.table = table;
        }

        @Override
        public boolean canScheduleTasks() {
            return false;
        }

        @Override
        public MuzScheduler scheduler() {
            throw new AssertionError("该行为测试不应创建 Bukkit 任务");
        }

        @Override
        public List<UUID> seats() {
            return table.getSeats();
        }

        @Override
        public void setOpeningPhase(GamePhase phase) {
            setPhase(phase);
        }

        @Override
        public void appendOpeningCard(UUID playerId, DoudizhuCard card) {
            try {
                Field field = GameTable.class.getDeclaredField("hands");
                field.setAccessible(true);
                @SuppressWarnings("unchecked")
                Map<UUID, List<DoudizhuCard>> hands = (Map<UUID, List<DoudizhuCard>>) field.get(table);
                hands.computeIfAbsent(playerId, ignored -> new ArrayList<>()).add(card);
            } catch (ReflectiveOperationException exception) {
                throw new AssertionError(exception);
            }
        }

        @Override
        public void assignOpeningBottomCards(List<DoudizhuCard> cards) {
            try {
                setField(table, "bottomCards", List.copyOf(cards));
            } catch (Exception exception) {
                throw new AssertionError(exception);
            }
        }

        @Override
        public void sortOpeningHands() {
            for (UUID seat : table.getSeats()) {
                List<DoudizhuCard> hand = new ArrayList<>(table.getHand(seat));
                hand.sort(DoudizhuCard.ORDER);
                try {
                    Field field = GameTable.class.getDeclaredField("hands");
                    field.setAccessible(true);
                    @SuppressWarnings("unchecked")
                    Map<UUID, List<DoudizhuCard>> hands = (Map<UUID, List<DoudizhuCard>>) field.get(table);
                    hands.put(seat, hand);
                } catch (ReflectiveOperationException exception) {
                    throw new AssertionError(exception);
                }
            }
        }

        @Override
        public void refreshPhysicalTable() {
            refreshes++;
        }

        @Override
        public void tickOpeningActionBar() {
        }

        @Override
        public void onOpeningRevealWindowStarted() {
            setPhase(GamePhase.REVEALING);
        }

        @Override
        public void onOpeningRevealWindowFinished() {
            setPhase(GamePhase.BIDDING);
        }

        private void setPhase(GamePhase phase) {
            try {
                setField(table, "phase", phase);
            } catch (Exception exception) {
                throw new AssertionError(exception);
            }
        }
    }
}
