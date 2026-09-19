package linmumua.doudizhu.game;

import linmumua.doudizhu.DoudizhuPlugin;
import linmumua.doudizhu.assets.PackSounds;
import linmumua.doudizhu.model.DoudizhuCard;
import linmumua.doudizhu.scheduler.MuzScheduler;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.function.BiFunction;
import java.util.function.Function;
import java.util.function.Supplier;
import org.bukkit.entity.Player;

/**
 * 牌桌背景音乐的单会话轮播协调器：同一局只允许一个活动播放链，切曲前清理本插件的全部 BGM。
 */
final class TableMusicCoordinator {
    private final Supplier<Boolean> canScheduleTasks;
    private final Supplier<GamePhase> phaseSupplier;
    private final Supplier<Map<UUID, List<DoudizhuCard>>> handsSupplier;
    private final Supplier<List<UUID>> seatsSupplier;
    private final Function<UUID, Player> playerResolver;
    private final Supplier<Float> volumeSupplier;
    private final BiFunction<Long, Runnable, MuzScheduler.TaskHandle> scheduler;
    private final Set<UUID> activeListeners = new HashSet<>();
    private String currentMusicKey;
    private MuzScheduler.TaskHandle scheduledTask;
    private int musicEpoch;
    private boolean activeSession;

    TableMusicCoordinator(
        DoudizhuPlugin plugin,
        Supplier<Boolean> canScheduleTasks,
        Supplier<GamePhase> phaseSupplier,
        Supplier<Map<UUID, List<DoudizhuCard>>> handsSupplier,
        Supplier<List<UUID>> seatsSupplier,
        Function<UUID, Player> playerResolver
    ) {
        this(
            canScheduleTasks,
            phaseSupplier,
            handsSupplier,
            seatsSupplier,
            playerResolver,
            plugin::getBgmVolume,
            (delay, runnable) -> plugin.scheduler().runLater(delay, runnable)
        );
    }

    TableMusicCoordinator(
        Supplier<Boolean> canScheduleTasks,
        Supplier<GamePhase> phaseSupplier,
        Supplier<Map<UUID, List<DoudizhuCard>>> handsSupplier,
        Supplier<List<UUID>> seatsSupplier,
        Function<UUID, Player> playerResolver,
        Supplier<Float> volumeSupplier,
        BiFunction<Long, Runnable, MuzScheduler.TaskHandle> scheduler
    ) {
        this.canScheduleTasks = canScheduleTasks;
        this.phaseSupplier = phaseSupplier;
        this.handsSupplier = handsSupplier;
        this.seatsSupplier = seatsSupplier;
        this.playerResolver = playerResolver;
        this.volumeSupplier = volumeSupplier;
        this.scheduler = scheduler;
    }

    void playRoundMusic() {
        // GameTable 只在正式开局调用；同局重复调用不能打断当前曲目并重建轮播任务。
        if (!canScheduleTasks.get() || phaseSupplier.get() == GamePhase.LOBBY || activeSession) {
            return;
        }
        stopAll();
        activeSession = true;
        startTrack(PackSounds.openingBgm(), ++musicEpoch);
    }

    void stopAll() {
        activeSession = false;
        musicEpoch++;
        cancelScheduledTask();
        currentMusicKey = null;
        Set<UUID> listenersToStop = new HashSet<>(activeListeners);
        listenersToStop.addAll(seatsSupplier.get());
        for (UUID playerId : listenersToStop) {
            Player player = playerResolver.apply(playerId);
            if (player != null) {
                stopBgmTracks(player);
            }
        }
        activeListeners.clear();
    }

    void updateState() {
        if (!isSessionUsable()) {
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
            startTrack(desired, ++musicEpoch);
        }
    }

    private void stopBgmTracks(Player player) {
        for (String bgm : PackSounds.bgmTracks()) {
            player.stopSound(bgm);
        }
    }

    /**
     * 只要场上任何一家手牌降到 3 张及以下就一直保持紧张 BGM。
     * 早期写的是 size() == 3，出到 2 张时音乐会退回普通循环，紧张感断在残局最关键的时候。
     */
    private boolean shouldUseExcitedBgm() {
        return phaseSupplier.get() == GamePhase.PLAYING
            && handsSupplier.get().values().stream().anyMatch(hand -> !hand.isEmpty() && hand.size() <= 3);
    }

    private String nextScheduledTrack(String previousTrack) {
        return shouldUseExcitedBgm() ? PackSounds.excitedBgm() : PackSounds.nextBgmTrack(previousTrack);
    }

    private void startTrack(String soundKey, int epoch) {
        if (!isSessionUsable(epoch) || soundKey == null || soundKey.isBlank()) {
            return;
        }
        cancelScheduledTask();
        for (UUID seat : seatsSupplier.get()) {
            Player player = playerResolver.apply(seat);
            if (player != null) {
                stopBgmTracks(player);
                player.playSound(player.getLocation(), soundKey, volumeSupplier.get(), 1.0f);
                activeListeners.add(seat);
            }
        }
        currentMusicKey = soundKey;
        scheduleNext(soundKey, epoch);
    }

    private void scheduleNext(String soundKey, int epoch) {
        if (!isSessionUsable(epoch)) {
            return;
        }
        long delay = PackSounds.bgmDurationTicks(soundKey);
        scheduledTask = scheduler.apply(delay, () -> {
            if (!isSessionUsable(epoch)) {
                return;
            }
            // 回调一旦消费就进入新 epoch；重复执行同一个回调必须失效，不能取消或覆盖新轮播任务。
            startTrack(nextScheduledTrack(soundKey), ++musicEpoch);
        });
    }

    private boolean isSessionUsable() {
        return activeSession && canScheduleTasks.get() && phaseSupplier.get() != GamePhase.LOBBY;
    }

    private boolean isSessionUsable(int epoch) {
        return isSessionUsable() && epoch == musicEpoch;
    }

    private void cancelScheduledTask() {
        if (scheduledTask != null) {
            scheduledTask.cancel();
            scheduledTask = null;
        }
    }
}
