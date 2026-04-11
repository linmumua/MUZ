package dev.mumu.doudizhu.storage;

import java.util.UUID;

public record MatchParticipantRecord(
    UUID playerId,
    String playerName,
    String roleLabel,
    String outcome,
    int scoreDelta,
    double settlementDelta,
    String unitLabel,
    double debtAfter,
    double balanceAfter,
    boolean bankrupt
) {
}
