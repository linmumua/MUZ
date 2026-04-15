package dev.mumu.doudizhu.game;

import dev.mumu.doudizhu.DoudizhuPlugin;
import dev.mumu.doudizhu.assets.PackSounds;
import dev.mumu.doudizhu.model.CardPattern;
import dev.mumu.doudizhu.model.CardRank;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import java.util.UUID;
import java.util.function.Function;
import java.util.function.Supplier;
import org.bukkit.entity.Player;

final class TableEffectCoordinator {
    private final DoudizhuPlugin plugin;
    private final Random random;
    private final Supplier<List<UUID>> seatsSupplier;
    private final Function<UUID, Player> playerResolver;
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
        for (UUID seat : seatsSupplier.get()) {
            playSound(seat, soundKey, volume, pitch);
        }
    }

    void playEffectAll(String soundKey) {
        playSoundAll(soundKey, plugin.getEffectVolume(), 1.0f);
    }

    void playEffect(UUID playerId, String soundKey) {
        playSound(playerId, soundKey, plugin.getEffectVolume(), 1.0f);
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
            playSoundAll(sound.key(), sound.volume(), sound.pitch());
        }
    }

    void playConfiguredSound(UUID playerId, DoudizhuPlugin.ConfiguredSound sound) {
        if (sound == null || sound.volume() <= 0.0f) {
            return;
        }
        playSound(playerId, sound.key(), sound.volume(), sound.pitch());
    }

    private void playSound(UUID playerId, String soundKey, float volume, float pitch) {
        Player player = playerResolver.apply(playerId);
        if (player != null) {
            player.playSound(player.getLocation(), soundKey, volume, pitch);
        }
    }
}
