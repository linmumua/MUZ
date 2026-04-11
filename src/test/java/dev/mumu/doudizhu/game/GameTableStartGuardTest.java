package dev.mumu.doudizhu.game;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import dev.mumu.doudizhu.model.CardRank;
import dev.mumu.doudizhu.model.CardSuit;
import dev.mumu.doudizhu.model.DoudizhuCard;
import sun.misc.Unsafe;

class GameTableStartGuardTest {
    @Test
    void ignoresImpossibleImmediateFinishWhenAllHandsAreEmpty() throws Exception {
        GameTable table = allocateTable();
        UUID landlord = UUID.randomUUID();
        UUID farmerA = UUID.randomUUID();
        UUID farmerB = UUID.randomUUID();
        List<UUID> seats = List.of(landlord, farmerA, farmerB);
        setField(table, "phase", GamePhase.PLAYING);
        setField(table, "landlord", landlord);
        setField(table, "seats", new ArrayList<>(seats));
        setField(table, "hands", new HashMap<>(Map.of(
            landlord, new ArrayList<>(),
            farmerA, new ArrayList<>(),
            farmerB, new ArrayList<>()
        )));
        setField(table, "roundStartedAtMillis", System.currentTimeMillis());

        assertTrue(invokeShouldIgnoreEarlyFinish(table, landlord));
    }

    @Test
    void keepsRealFinishWhenOtherPlayersStillHaveCards() throws Exception {
        GameTable table = allocateTable();
        UUID landlord = UUID.randomUUID();
        UUID farmerA = UUID.randomUUID();
        UUID farmerB = UUID.randomUUID();
        List<UUID> seats = List.of(landlord, farmerA, farmerB);
        setField(table, "phase", GamePhase.PLAYING);
        setField(table, "landlord", landlord);
        setField(table, "seats", new ArrayList<>(seats));
        setField(table, "hands", new HashMap<>(Map.of(
            landlord, cards(100, 20),
            farmerA, cards(1, 17),
            farmerB, cards(18, 17)
        )));
        setField(table, "roundStartedAtMillis", System.currentTimeMillis());

        assertFalse(invokeShouldIgnoreEarlyFinish(table, landlord));
    }

    private static boolean invokeShouldIgnoreEarlyFinish(GameTable table, UUID winner) throws Exception {
        Method method = GameTable.class.getDeclaredMethod("shouldIgnoreEarlyFinish", UUID.class);
        method.setAccessible(true);
        return (boolean) method.invoke(table, winner);
    }

    private static GameTable allocateTable() throws Exception {
        Unsafe unsafe = unsafe();
        GameTable table = (GameTable) unsafe.allocateInstance(GameTable.class);
        setField(table, "random", new Random());
        setField(table, "seats", new ArrayList<UUID>());
        setField(table, "readyPlayers", java.util.Collections.newSetFromMap(new HashMap<UUID, Boolean>()));
        setField(table, "totalScores", new LinkedHashMap<UUID, Integer>());
        setField(table, "bids", new LinkedHashMap<UUID, Integer>());
        setField(table, "roles", new HashMap<UUID, PlayerRole>());
        setField(table, "hands", new HashMap<UUID, List<?>>());
        setField(table, "selections", new HashMap<UUID, Set<Integer>>());
        setField(table, "playedHandCounts", new HashMap<UUID, Integer>());
        setField(table, "botNames", new LinkedHashMap<UUID, String>());
        setField(table, "recentLobbyEntries", new ArrayList<>());
        setField(table, "recentTrickEntries", new ArrayList<>());
        return table;
    }

    private static ArrayList<DoudizhuCard> cards(int startId, int count) {
        ArrayList<DoudizhuCard> cards = new ArrayList<>();
        for (int index = 0; index < count; index++) {
            cards.add(new DoudizhuCard(startId + index, CardRank.THREE, CardSuit.CLUBS));
        }
        return cards;
    }

    private static void setField(Object target, String fieldName, Object value) throws Exception {
        Field field = GameTable.class.getDeclaredField(fieldName);
        field.setAccessible(true);
        field.set(target, value);
    }

    private static Unsafe unsafe() throws Exception {
        Field field = Unsafe.class.getDeclaredField("theUnsafe");
        field.setAccessible(true);
        return (Unsafe) field.get(null);
    }
}
