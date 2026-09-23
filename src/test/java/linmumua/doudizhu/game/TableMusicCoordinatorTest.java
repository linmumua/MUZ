package linmumua.doudizhu.game;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import linmumua.doudizhu.assets.PackSounds;
import linmumua.doudizhu.scheduler.MuzScheduler;
import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.bukkit.entity.Player;
import org.junit.jupiter.api.Test;

/**
 * @author linmumua
 * @Desc 牌桌背景音乐活动会话与轮播任务回归测试
 */
class TableMusicCoordinatorTest {
    @Test
    void stopDisablesSessionEvenBeforePhaseReturnsToLobby() {
        Fixture fixture = new Fixture();
        fixture.addSeat();
        fixture.coordinator.playRoundMusic();
        int stoppedBefore = fixture.player.stoppedSounds.size();

        fixture.coordinator.stopAll();
        fixture.coordinator.updateState();

        assertEquals(GamePhase.PLAYING, fixture.phase);
        assertEquals(
            PackSounds.bgmTracks(),
            fixture.player.stoppedSounds.subList(stoppedBefore, fixture.player.stoppedSounds.size())
        );
        assertEquals(1, fixture.player.playedSounds.size());
        assertEquals(1, fixture.scheduler.callbacks.size());
        assertTrue(fixture.scheduler.tasks.get(0).cancelled);
    }

    @Test
    void lobbyCannotActivateMusicSession() {
        Fixture fixture = new Fixture();
        fixture.phase = GamePhase.LOBBY;
        fixture.addSeat();

        fixture.coordinator.playRoundMusic();

        assertTrue(fixture.player.playedSounds.isEmpty());
        assertTrue(fixture.scheduler.callbacks.isEmpty());
    }

    @Test
    void disabledSchedulerCannotActivateMusicSession() {
        Fixture fixture = new Fixture();
        fixture.canSchedule = false;
        fixture.addSeat();

        fixture.coordinator.playRoundMusic();

        assertTrue(fixture.player.playedSounds.isEmpty());
        assertTrue(fixture.scheduler.callbacks.isEmpty());
    }

    @Test
    void repeatedRoundStartDoesNotRestartActiveSession() {
        Fixture fixture = new Fixture();
        fixture.addSeat();
        fixture.coordinator.playRoundMusic();
        int playedBefore = fixture.player.playedSounds.size();
        int stoppedBefore = fixture.player.stoppedSounds.size();
        int callbacksBefore = fixture.scheduler.callbacks.size();

        fixture.coordinator.playRoundMusic();

        assertEquals(playedBefore, fixture.player.playedSounds.size());
        assertEquals(stoppedBefore, fixture.player.stoppedSounds.size());
        assertEquals(callbacksBefore, fixture.scheduler.callbacks.size());
        assertEquals(1, fixture.activeTaskCount());
    }

    @Test
    void cancelledCallbackCannotPlayOrScheduleAgain() {
        Fixture fixture = new Fixture();
        fixture.addSeat();
        fixture.coordinator.playRoundMusic();
        fixture.coordinator.stopAll();

        fixture.scheduler.callbacks.get(0).run();

        assertEquals(1, fixture.player.playedSounds.size());
        assertEquals(1, fixture.scheduler.callbacks.size());
    }

    @Test
    void consumedCallbackCannotRunTwiceOrCancelNewTask() {
        Fixture fixture = new Fixture();
        fixture.addSeat();
        fixture.coordinator.playRoundMusic();
        Runnable consumedCallback = fixture.scheduler.callbacks.get(0);

        consumedCallback.run();
        int playedAfterFirstRun = fixture.player.playedSounds.size();
        int callbacksAfterFirstRun = fixture.scheduler.callbacks.size();
        CapturedTask newTask = fixture.scheduler.tasks.get(1);

        consumedCallback.run();

        assertEquals(playedAfterFirstRun, fixture.player.playedSounds.size());
        assertEquals(callbacksAfterFirstRun, fixture.scheduler.callbacks.size());
        assertFalse(newTask.cancelled);
        assertEquals(1, fixture.activeTaskCount());
    }

    @Test
    void callbackFromOldSessionCannotInterfereWithNewSession() {
        Fixture fixture = new Fixture();
        fixture.addSeat();
        fixture.coordinator.playRoundMusic();
        Runnable oldCallback = fixture.scheduler.callbacks.get(0);

        fixture.coordinator.stopAll();
        fixture.coordinator.playRoundMusic();
        int playedAfterNewSession = fixture.player.playedSounds.size();
        int scheduledAfterNewSession = fixture.scheduler.callbacks.size();
        oldCallback.run();

        assertEquals(playedAfterNewSession, fixture.player.playedSounds.size());
        assertEquals(scheduledAfterNewSession, fixture.scheduler.callbacks.size());
    }

    @Test
    void stopUsesTrackedListenersWhenSeatsWereCleared() {
        Fixture fixture = new Fixture();
        UUID seat = fixture.addSeat();
        fixture.coordinator.playRoundMusic();
        int stoppedBefore = fixture.player.stoppedSounds.size();
        fixture.seats.clear();

        fixture.coordinator.stopAll();

        assertEquals(seat, fixture.player.id);
        assertEquals(5, fixture.player.stoppedSounds.size() - stoppedBefore);
    }

    @Test
    void stopDoesNotAffectUnrelatedPlayer() {
        Fixture fixture = new Fixture();
        fixture.addSeat();
        fixture.players.put(fixture.unrelatedPlayer.id, fixture.unrelatedPlayer.proxy());
        fixture.coordinator.playRoundMusic();
        fixture.unrelatedPlayer.stoppedSounds.clear();

        fixture.coordinator.stopAll();

        assertTrue(fixture.unrelatedPlayer.stoppedSounds.isEmpty());
    }

    @Test
    void musicStopAndPlayAreRoutedThroughSeatOwnerLane() {
        Fixture fixture = new Fixture();
        UUID seat = fixture.addSeat();

        fixture.coordinator.playRoundMusic();
        fixture.coordinator.stopAll();

        assertEquals(List.of(seat, seat), fixture.ownerLanePlayers.subList(0, 2));
    }

    @Test
    void normalLoopKeepsOneTrackAndOnePendingTaskAcrossCallbacks() {
        Fixture fixture = new Fixture();
        fixture.addSeat();
        fixture.coordinator.playRoundMusic();

        fixture.scheduler.callbacks.get(0).run();
        fixture.scheduler.callbacks.get(1).run();
        fixture.scheduler.callbacks.get(2).run();

        assertEquals(4, fixture.player.playedSounds.size());
        assertEquals(1, fixture.player.activeSounds.size());
        assertEquals(1, fixture.activeTaskCount());
        assertEquals(4, fixture.player.events.stream().filter(event -> event.startsWith("play:")).count());
        fixture.player.assertEveryPlayFollowsFullBgmStop();
    }

    @Test
    void repeatedUpdateStateDoesNotRestartAlreadySelectedTrack() {
        Fixture fixture = new Fixture();
        fixture.addSeat();
        fixture.coordinator.playRoundMusic();

        fixture.coordinator.updateState();
        fixture.coordinator.updateState();

        assertEquals(2, fixture.player.playedSounds.size());
        assertEquals(1, fixture.player.activeSounds.size());
        assertEquals(1, fixture.activeTaskCount());
    }

    @Test
    void switchingTrackCancelsOldTaskAndKeepsExcitedMusicAtTwoCards() {
        Fixture fixture = new Fixture();
        UUID seat = fixture.addSeat();
        fixture.hands.put(seat, Collections.nCopies(3, null));
        fixture.coordinator.playRoundMusic();
        int stoppedBeforeSwitch = fixture.player.stoppedSounds.size();

        fixture.coordinator.updateState();

        assertEquals(List.of("muz:doudizhu.opening", "muz:doudizhu.middle"), fixture.player.playedSounds);
        assertTrue(fixture.scheduler.tasks.get(0).cancelled);
        assertEquals(2, fixture.scheduler.callbacks.size());
        assertEquals(5, fixture.player.stoppedSounds.size() - stoppedBeforeSwitch);

        fixture.hands.put(seat, Collections.nCopies(2, null));
        fixture.coordinator.updateState();

        assertEquals(2, fixture.player.playedSounds.size());
        assertEquals(2, fixture.scheduler.callbacks.size());
        assertFalse(fixture.scheduler.tasks.get(1).cancelled);
    }

    private static final class Fixture {
        private final List<UUID> seats = new ArrayList<>();
        private final Map<UUID, List<linmumua.doudizhu.model.DoudizhuCard>> hands = new HashMap<>();
        private final Map<UUID, Player> players = new HashMap<>();
        private final CapturedPlayer player = new CapturedPlayer(UUID.randomUUID());
        private final CapturedPlayer unrelatedPlayer = new CapturedPlayer(UUID.randomUUID());
        private final SchedulerCapture scheduler = new SchedulerCapture();
        private final List<UUID> ownerLanePlayers = new ArrayList<>();
        private final PlayerOutputDispatcher outputDispatcher = new PlayerOutputDispatcher(
            new PlayerTaskRegistry(
                players::get,
                (target, delay, task) -> {
                    ownerLanePlayers.add(target.getUniqueId());
                    task.run();
                    return new CapturedTask();
                }
            )
        );
        private GamePhase phase = GamePhase.PLAYING;
        private boolean canSchedule = true;
        private final TableMusicCoordinator coordinator = new TableMusicCoordinator(
            outputDispatcher,
            () -> canSchedule,
            () -> phase,
            () -> hands,
            () -> seats,
            () -> 0.75f,
            scheduler::schedule
        );

        private UUID addSeat() {
            seats.add(player.id);
            players.put(player.id, player.proxy());
            return player.id;
        }

        private long activeTaskCount() {
            return scheduler.tasks.stream().filter(task -> !task.cancelled).count();
        }
    }

    private static final class CapturedPlayer {
        private final UUID id;
        private final List<String> playedSounds = new ArrayList<>();
        private final List<String> stoppedSounds = new ArrayList<>();
        private final List<String> events = new ArrayList<>();
        private final Set<String> activeSounds = new HashSet<>();

        private CapturedPlayer(UUID id) {
            this.id = id;
        }

        private void assertEveryPlayFollowsFullBgmStop() {
            Set<String> stoppedSincePlay = new HashSet<>();
            Set<String> expectedTracks = new HashSet<>(PackSounds.bgmTracks());
            for (String event : events) {
                if (event.startsWith("stop:")) {
                    stoppedSincePlay.add(event.substring("stop:".length()));
                } else if (event.startsWith("play:")) {
                    assertEquals(expectedTracks, stoppedSincePlay);
                    stoppedSincePlay.clear();
                }
            }
        }

        private Player proxy() {
            return (Player) Proxy.newProxyInstance(
                Player.class.getClassLoader(),
                new Class<?>[] {Player.class},
                (proxy, method, args) -> {
                    switch (method.getName()) {
                        case "getUniqueId" -> {
                            return id;
                        }
                        case "isOnline" -> {
                            return true;
                        }
                        case "getLocation" -> {
                            return null;
                        }
                        case "stopSound" -> {
                            String sound = (String) args[0];
                            stoppedSounds.add(sound);
                            events.add("stop:" + sound);
                            activeSounds.remove(sound);
                            return null;
                        }
                        case "playSound" -> {
                            String sound = (String) args[1];
                            assertTrue(activeSounds.isEmpty(), "播放 " + sound + " 前仍有活跃 BGM: " + activeSounds);
                            playedSounds.add(sound);
                            events.add("play:" + sound);
                            activeSounds.add(sound);
                            return null;
                        }
                        default -> {
                            return defaultValue(method.getReturnType());
                        }
                    }
                }
            );
        }
    }

    private static final class SchedulerCapture {
        private final List<Runnable> callbacks = new ArrayList<>();
        private final List<CapturedTask> tasks = new ArrayList<>();

        private MuzScheduler.TaskHandle schedule(long delay, Runnable callback) {
            callbacks.add(callback);
            CapturedTask task = new CapturedTask();
            tasks.add(task);
            return task;
        }
    }

    private static final class CapturedTask implements MuzScheduler.TaskHandle {
        private boolean cancelled;

        @Override
        public void cancel() {
            cancelled = true;
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
