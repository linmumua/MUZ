package dev.mumu.doudizhu.game;

import dev.mumu.doudizhu.DoudizhuPlugin;
import dev.mumu.doudizhu.room.TableLevel;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

final class RoundSettlementCoordinator {
    interface Support {
        DoudizhuPlugin plugin();
        TableLevel roomLevel();
        List<UUID> seats();
        UUID landlord();
        int resolvedCoreScore(boolean landlordWin);
        int seatPairFactor(UUID seat);
        void applyTotalScoreDelta(UUID playerId, int delta);
        boolean isBot(UUID playerId);
    }

    record RoundSettlement(
        List<UUID> winners,
        boolean landlordWin,
        Map<UUID, Integer> scoreDeltas,
        Map<UUID, DoudizhuPlugin.SettlementResult> settlementSnapshots
    ) {
        DoudizhuPlugin.SettlementResult displayResultFor(UUID playerId, DoudizhuPlugin plugin, TableLevel roomLevel) {
            DoudizhuPlugin.SettlementResult stored = settlementSnapshots.get(playerId);
            if (stored != null) {
                return stored;
            }
            int scoreDelta = scoreDeltas.getOrDefault(playerId, 0);
            if (plugin.isChipPaymentEnabled()) {
                double chipDelta = Math.round(scoreDelta * plugin.roomMultiplier(roomLevel));
                return new DoudizhuPlugin.SettlementResult(chipDelta, 0.0, 0.0, false, false, "筹码");
            }
            if (plugin.isDoudizhuRoomEconomyEnabled(roomLevel)) {
                double currencyDelta = scoreDelta * plugin.doudizhuCurrencyPerPoint(roomLevel);
                return new DoudizhuPlugin.SettlementResult(currencyDelta, 0.0, 0.0, false, false, "金币");
            }
            return new DoudizhuPlugin.SettlementResult(scoreDelta, 0.0, 0.0, false, false, "分");
        }
    }

    private final Support support;

    RoundSettlementCoordinator(Support support) {
        this.support = support;
    }

    RoundSettlement settle(UUID winner) {
        UUID landlord = support.landlord();
        boolean landlordWin = Objects.equals(winner, landlord);
        List<UUID> winningSeats = landlordWin
            ? List.of(landlord)
            : support.seats().stream().filter(seat -> !Objects.equals(seat, landlord)).toList();
        int roundScore = support.resolvedCoreScore(landlordWin);
        Map<UUID, Integer> scoreDeltas = new LinkedHashMap<>();
        if (landlordWin) {
            int landlordGain = 0;
            for (UUID seat : support.seats()) {
                if (Objects.equals(seat, landlord)) {
                    continue;
                }
                int loss = roundScore * support.seatPairFactor(seat);
                landlordGain += loss;
                scoreDeltas.put(seat, -loss);
                support.applyTotalScoreDelta(seat, -loss);
            }
            scoreDeltas.put(landlord, landlordGain);
            support.applyTotalScoreDelta(landlord, landlordGain);
        } else {
            int landlordLoss = 0;
            for (UUID seat : support.seats()) {
                if (Objects.equals(seat, landlord)) {
                    continue;
                }
                int gain = roundScore * support.seatPairFactor(seat);
                landlordLoss += gain;
                scoreDeltas.put(seat, gain);
                support.applyTotalScoreDelta(seat, gain);
            }
            scoreDeltas.put(landlord, -landlordLoss);
            support.applyTotalScoreDelta(landlord, -landlordLoss);
        }
        return new RoundSettlement(winningSeats, landlordWin, scoreDeltas, settleEconomy(scoreDeltas));
    }

    private Map<UUID, DoudizhuPlugin.SettlementResult> settleEconomy(Map<UUID, Integer> scoreDeltas) {
        Map<UUID, DoudizhuPlugin.SettlementResult> settlementSnapshots = new LinkedHashMap<>();
        if (scoreDeltas.isEmpty()) {
            return settlementSnapshots;
        }
        DoudizhuPlugin plugin = support.plugin();
        if (!plugin.isDoudizhuRoomEconomyEnabled(support.roomLevel()) && !plugin.isChipPaymentEnabled()) {
            return settlementSnapshots;
        }
        for (Map.Entry<UUID, Integer> entry : scoreDeltas.entrySet()) {
            UUID playerId = entry.getKey();
            int scoreDelta = entry.getValue();
            if (scoreDelta == 0 || support.isBot(playerId)) {
                continue;
            }
            DoudizhuPlugin.SettlementResult result = plugin.settleDoudizhuCurrency(support.roomLevel(), playerId, scoreDelta);
            settlementSnapshots.put(playerId, result);
        }
        return settlementSnapshots;
    }
}
