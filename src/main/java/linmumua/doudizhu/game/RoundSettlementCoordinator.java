package linmumua.doudizhu.game;

import linmumua.doudizhu.DoudizhuPlugin;
import linmumua.doudizhu.room.TableLevel;
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

    /**
     * 单局基础分的硬上限。
     *
     * <p>取 1 亿：一局分差乘上座位系数（最大 2）再三家累加也只到 6 亿，
     * 离 int 上限 21.47 亿有充足余量，不会在累加阶段二次溢出。
     * 同时这个值远高于正常对局的量级（实测峰值约 3.2 万），
     * 正常玩法永远碰不到它，只在配置被填成极端值时兜底。
     */
    static final int MAX_ROUND_SCORE = 100_000_000;

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
        // 【为什么要夹上限】：roundScore = 叫分 × 明牌公共倍率 × 炸弹倍数 × 春天2，
        // 这里的核心分仍由 GameTable 统一计算，座位加倍在分差层单独处理。当前配置下实测峰值约 3.2 万，
        // 离 int 上限还远，但这条链上每加一个乘子门槛就掉一个数量级，溢出后会变成负数——那意味着输家反而收钱。
        // 夹在这里而不是各乘法点：这是所有分差的唯一源头，夹一次就够，且不改变正常量级的结果。
        roundScore = Math.clamp(roundScore, 0, MAX_ROUND_SCORE);
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
            // 【逐人隔离，不许让一个人的失败带走整局】：settleDoudizhuCurrency 在 Vault
            // 拒绝交易时抛 IllegalStateException。这个循环以前不接异常，首个失败的玩家会
            // 让后面所有人的结算被整段跳过——已扣的扣了、该发的没发，而且 finishRound
            // 后续的战绩落库、结算聊天、HUD 刷新全部一起没了（那边也没有 try/catch）。
            //
            // 现在把失败者单独记成「全额欠账」快照：debt 记满，余额按当前值给。
            // 这样这一局的账面是完整的，运维能从日志和欠账里对出是谁没结算成功。
            try {
                DoudizhuPlugin.SettlementResult result =
                    plugin.settleDoudizhuCurrency(support.roomLevel(), playerId, scoreDelta);
                settlementSnapshots.put(playerId, result);
            } catch (RuntimeException exception) {
                settlementSnapshots.put(playerId, plugin.failedSettlement(support.roomLevel(), playerId, scoreDelta));
                // 经济失败必须留证据：吞掉的话，玩家来问「我的钱呢」就无从查证。
                plugin.getLogger().log(
                    java.util.logging.Level.SEVERE,
                    "结算失败，已记为欠账。玩家=" + playerId + " 分差=" + scoreDelta
                        + " 场次=" + (support.roomLevel() == null ? "无" : support.roomLevel().key()),
                    exception
                );
            }
        }
        return settlementSnapshots;
    }
}
