package dev.mumu.doudizhu.storage;

import dev.mumu.doudizhu.room.TableLevel;

public record MatchRecord(
    String gameType,
    String tableName,
    TableLevel roomLevel,
    String outcomeLabel,
    long occurredAt,
    String worldName,
    double x,
    double y,
    double z
) {
}
