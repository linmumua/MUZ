package linmumua.doudizhu.game;

import linmumua.doudizhu.DoudizhuPlugin;
import linmumua.doudizhu.assets.PackSounds;
import linmumua.doudizhu.model.CardPattern;
import linmumua.doudizhu.model.CardRank;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.UUID;
import org.bukkit.Bukkit;
import java.util.function.Function;
import java.util.function.Supplier;
import org.bukkit.entity.Player;

final class TableEffectCoordinator {
    private final DoudizhuPlugin plugin;
    private final Random random;
    private final Supplier<List<UUID>> seatsSupplier;
    private final Function<UUID, Player> playerResolver;
    private static final int EFFECT_DEDUPLICATION_TICKS = 2;
    private static final int COUNTDOWN_DEDUPLICATION_TICKS = 16;
    private final Map<String, Integer> lastPlayedTicks = new HashMap<>();
    private String lastRandomEffectKey;
    private int lastRandomEffectStreak;

    TableEffectCoordinator(
        DoudizhuPlugin plugin,
        Random random,
        Supplier<List<UUID>> seatsSupplier,
        Function<UUID, Player> playerResolver
    ) {
        this.plugin = plugin;
        this.random = random;
        this.seatsSupplier = seatsSupplier;
        this.playerResolver = playerResolver;
    }

    void playSoundAll(String soundKey, float volume, float pitch) {
        playSoundAll(soundKey, volume, pitch, EFFECT_DEDUPLICATION_TICKS);
    }

    private void playSoundAll(String soundKey, float volume, float pitch, int cooldownTicks) {
        for (UUID seat : seatsSupplier.get()) {
            playSound(seat, soundKey, volume, pitch, cooldownTicks);
        }
    }

    void playEffectAll(String soundKey) {
        playSoundAll(soundKey, plugin.getEffectVolume(), 1.0f);
    }

    void playEffect(UUID playerId, String soundKey) {
        playSound(playerId, soundKey, plugin.getEffectVolume(), 1.0f, EFFECT_DEDUPLICATION_TICKS);
    }

    void playRandomEffectAll(List<String> soundKeys) {
        if (soundKeys == null || soundKeys.isEmpty()) {
            return;
        }
        List<String> candidates = new ArrayList<>();
        for (String soundKey : soundKeys) {
            if (soundKey == null || soundKey.isBlank() || candidates.contains(soundKey)) {
                continue;
            }
            candidates.add(soundKey);
        }
        if (candidates.isEmpty()) {
            return;
        }
        List<String> filtered = candidates;
        if (lastRandomEffectKey != null && lastRandomEffectStreak >= 2 && candidates.size() > 1) {
            filtered = candidates.stream()
                .filter(soundKey -> !soundKey.equals(lastRandomEffectKey))
                .toList();
        }
        String selected = filtered.get(random.nextInt(filtered.size()));
        if (selected.equals(lastRandomEffectKey)) {
            lastRandomEffectStreak++;
        } else {
            lastRandomEffectKey = selected;
            lastRandomEffectStreak = 1;
        }
        playEffectAll(selected);
    }

    void playPatternVoice(CardPattern pattern, CardRank primaryRank, boolean pressurePlay, boolean threeCardsLeft, boolean twoCardsLeft) {
        if (twoCardsLeft) {
            playEffectAll(PackSounds.twoCardsWarning());
            return;
        }
        if (pattern == null || primaryRank == null) {
            if (threeCardsLeft) {
                playEffectAll(PackSounds.threeCardsWarning());
            }
            return;
        }
        List<String> candidates = new ArrayList<>();
        candidates.add(PackSounds.play(pattern, primaryRank));
        if (pressurePlay) {
            candidates.add(PackSounds.pressureCallout());
        }
        if (threeCardsLeft) {
            candidates.add(PackSounds.threeCardsWarning());
        }
        playRandomEffectAll(candidates);
    }

    void playCountdownCue(int remaining) {
        if (remaining <= 0 || remaining > 5) {
            return;
        }
        DoudizhuPlugin.ConfiguredSound sound = plugin.countdownSound();
        if (sound.volume() > 0.0f) {
            playSoundAll(sound.key(), sound.volume(), sound.pitch(), COUNTDOWN_DEDUPLICATION_TICKS);
        }
    }

    void playConfiguredSound(UUID playerId, DoudizhuPlugin.ConfiguredSound sound) {
        if (sound == null || sound.volume() <= 0.0f) {
            return;
        }
        playSound(playerId, sound.key(), sound.volume(), sound.pitch(), EFFECT_DEDUPLICATION_TICKS);
    }

    private void playSound(UUID playerId, String soundKey, float volume, float pitch, int cooldownTicks) {
        if (playerId == null || soundKey == null || soundKey.isBlank()) {
            return;
        }
        Player player = playerResolver.apply(playerId);
        if (player == null || !player.isOnline()) {
            return;
        }
        int currentTick = Bukkit.getCurrentTick();
        String deduplicationKey = playerId + "\u0000" + soundKey;
        Integer lastTick = lastPlayedTicks.get(deduplicationKey);
        if (lastTick != null && currentTick - lastTick < cooldownTicks) {
            return;
        }
        lastPlayedTicks.put(deduplicationKey, currentTick);
        if (lastPlayedTicks.size() > 256) {
            Iterator<Map.Entry<String, Integer>> iterator = lastPlayedTicks.entrySet().iterator();
            while (iterator.hasNext()) {
                if (currentTick - iterator.next().getValue() >= COUNTDOWN_DEDUPLICATION_TICKS) {
                    iterator.remove();
                }
            }
        }
        player.playSound(player.getLocation(), soundKey, volume, pitch);
    }
}
