package linmumua.doudizhu.game;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import linmumua.doudizhu.scheduler.MuzScheduler;
import org.bukkit.entity.Player;
import org.junit.jupiter.api.Test;

/** 牌桌普通音效必须经玩家 UUID owner lane 投递，并保持按玩家与音效去重。 */
class TableEffectCoordinatorTest {
    @Test
    void soundIsDispatchedToEachSeatOwnerLane() {
        Fixture fixture = new Fixture();
        UUID first = fixture.addPlayer();
        UUID second = fixture.addPlayer();

        fixture.coordinator.playSoundAll("muz:test", 0.4f, 1.1f);

        assertEquals(List.of(first, second), fixture.ownerLanePlayers);
        assertEquals(List.of("muz:test"), fixture.players.get(first).playedSounds);
        assertEquals(List.of("muz:test"), fixture.players.get(second).playedSounds);
    }

    @Test
    void duplicateSoundIsSuppressedPerPlayerUntilCooldownExpires() {
        Fixture fixture = new Fixture();
        UUID playerId = fixture.addPlayer();

        fixture.coordinator.playSoundAll("muz:test", 0.4f, 1.1f);
        fixture.coordinator.playSoundAll("muz:test", 0.4f, 1.1f);
        fixture.tick.set(2);
        fixture.coordinator.playSoundAll("muz:test", 0.4f, 1.1f);

        assertEquals(List.of("muz:test", "muz:test"), fixture.players.get(playerId).playedSounds);
        assertEquals(List.of(playerId, playerId), fixture.ownerLanePlayers);
    }

    @Test
    void offlinePlayerDoesNotEnterOwnerLane() {
        Fixture fixture = new Fixture();
        UUID playerId = UUID.randomUUID();
        fixture.players.put(playerId, new CapturedPlayer(playerId, false));
        fixture.seats.add(playerId);

        fixture.coordinator.playSoundAll("muz:test", 0.4f, 1.1f);

        assertTrue(fixture.ownerLanePlayers.isEmpty());
        assertTrue(fixture.players.get(playerId).playedSounds.isEmpty());
    }

    private static final class Fixture {
        private final List<UUID> seats = new ArrayList<>();
        private final Map<UUID, CapturedPlayer> players = new HashMap<>();
        private final List<UUID> ownerLanePlayers = new ArrayList<>();
        private final AtomicInteger tick = new AtomicInteger();
        private final PlayerOutputDispatcher dispatcher = new PlayerOutputDispatcher(
            new PlayerTaskRegistry(
                id -> {
                    CapturedPlayer captured = players.get(id);
                    return captured == null || !captured.online ? null : captured.proxy();
                },
                (player, delay, task) -> {
                    ownerLanePlayers.add(player.getUniqueId());
                    task.run();
                    return new CapturedTask();
                }
            )
        );
        private final TableEffectCoordinator coordinator = new TableEffectCoordinator(
            null,
            dispatcher,
            new Random(1L),
            () -> seats,
            tick::get
        );

        private UUID addPlayer() {
            UUID id = UUID.randomUUID();
            players.put(id, new CapturedPlayer(id, true));
            seats.add(id);
            return id;
        }
    }

    private static final class CapturedPlayer {
        private final UUID id;
        private final boolean online;
        private final List<String> playedSounds = new ArrayList<>();

        private CapturedPlayer(UUID id, boolean online) {
            this.id = id;
            this.online = online;
        }

        private Player proxy() {
            return (Player) Proxy.newProxyInstance(
                Player.class.getClassLoader(),
                new Class<?>[] {Player.class},
                (proxy, method, args) -> switch (method.getName()) {
                    case "getUniqueId" -> id;
                    case "isOnline" -> online;
                    case "getLocation" -> null;
                    case "playSound" -> {
                        playedSounds.add((String) args[1]);
                        yield null;
                    }
                    default -> defaultValue(method.getReturnType());
                }
            );
        }
    }

    private static final class CapturedTask implements MuzScheduler.TaskHandle {
        private boolean cancelled;

        @Override
        public void cancel() {
            cancelled = true;
        }

        @Override
        public boolean isCancelled() {
            return cancelled;
        }

        @Override
        public void onTermination(Runnable listener) {
        }
    }

    private static Object defaultValue(Class<?> type) {
        if (type == boolean.class) return false;
        if (type == byte.class) return (byte) 0;
        if (type == short.class) return (short) 0;
        if (type == int.class) return 0;
        if (type == long.class) return 0L;
        if (type == float.class) return 0.0f;
        if (type == double.class) return 0.0d;
        if (type == char.class) return '\0';
        return null;
    }
}
