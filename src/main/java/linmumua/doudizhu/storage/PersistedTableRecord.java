package linmumua.doudizhu.storage;

import linmumua.doudizhu.room.TableLevel;

public record PersistedTableRecord(
    String gameType,
    String tableName,
    TableLevel roomLevel,
    String worldName,
    double x,
    double y,
    double z,
    float yaw,
    int maxPlayers,
    String ownerUuid,
    String ownerName
) {
}
