package linmumua.doudizhu.game;

import linmumua.doudizhu.DoudizhuPlugin;
import linmumua.doudizhu.assets.PackSounds;
import linmumua.doudizhu.model.DoudizhuCard;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.function.Function;
import java.util.function.Supplier;
import org.bukkit.entity.Player;

final class TableMusicCoordinator {
    private final DoudizhuPlugin plugin;
    private final Supplier<Boolean> canScheduleTasks;
    private final Supplier<GamePhase> phaseSupplier;
    private final Supplier<Map<UUID, List<DoudizhuCard>>> handsSupplier;
    private final Supplier<List<UUID>> seatsSupplier;
    private final Function<UUID, Player> playerResolver;
    private String currentMusicKey;
    private int musicEpoch;

    TableMusicCoordinator(
        DoudizhuPlugin plugin,
        Supplier<Boolean> canScheduleTasks,
        Supplier<GamePhase> phaseSupplier,
        Supplier<Map<UUID, List<DoudizhuCard>>> handsSupplier,
        Supplier<List<UUID>> seatsSupplier,
        Function<UUID, Player> playerResolver
    ) {
        this.plugin = plugin;
        this.canScheduleTasks = canScheduleTasks;
        this.phaseSupplier = phaseSupplier;
        this.handsSupplier = handsSupplier;
        this.seatsSupplier = seatsSupplier;
        this.playerResolver = playerResolver;
    }

    void playRoundMusic() {
        stopAll();
        startTrack(PackSounds.openingBgm(), ++musicEpoch);
    }

    void stopAll() {
        musicEpoch++;
        currentMusicKey = null;
        for (UUID seat : seatsSupplier.get()) {
            Player player = playerResolver.apply(seat);
            if (player != null) {
                stopBgmTracks(player);
            }
        }
    }

    void updateState() {
        if (!canScheduleTasks.get() || phaseSupplier.get() == GamePhase.LOBBY) {
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
        if (!canScheduleTasks.get() || soundKey == null || soundKey.isBlank()) {
            return;
        }
        for (UUID seat : seatsSupplier.get()) {
            Player player = playerResolver.apply(seat);
            if (player != null) {
                stopBgmTracks(player);
                player.playSound(player.getLocation(), soundKey, plugin.getBgmVolume(), 1.0f);
            }
        }
        currentMusicKey = soundKey;
        scheduleNext(soundKey, epoch);
    }

    private void scheduleNext(String soundKey, int epoch) {
        if (!canScheduleTasks.get()) {
            return;
        }
        long delay = PackSounds.bgmDurationTicks(soundKey);
        plugin.scheduler().runLater(delay, () -> {
            if (epoch != musicEpoch || phaseSupplier.get() == GamePhase.LOBBY) {
                return;
            }
            startTrack(nextScheduledTrack(soundKey), epoch);
        });
    }
}
