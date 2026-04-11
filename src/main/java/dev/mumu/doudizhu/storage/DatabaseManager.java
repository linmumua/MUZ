package dev.mumu.doudizhu.storage;

import dev.mumu.doudizhu.DoudizhuPlugin;
import dev.mumu.doudizhu.room.TableLevel;
import java.io.File;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.sql.Timestamp;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.bukkit.configuration.file.FileConfiguration;

public final class DatabaseManager {
    private static final int READ_RETRY_COUNT = 2;
    private static final int SQLITE_BUSY_TIMEOUT_MILLIS = 5000;
    private final DoudizhuPlugin plugin;
    private SqlConfig config;
    private boolean initialized;
    private String status = "未初始化";

    public DatabaseManager(DoudizhuPlugin plugin) {
        this.plugin = plugin;
    }

    public boolean initialize() {
        config = SqlConfig.fromConfig(plugin, plugin.getConfig());
        try {
            if (config.type() == SqlType.SQLITE) {
                Class.forName("org.sqlite.JDBC");
                File file = config.sqliteFile();
                File parent = file.getParentFile();
                if (parent != null) {
                    parent.mkdirs();
                }
            } else {
                Class.forName("com.mysql.cj.jdbc.Driver");
            }
            try (Connection connection = openConnection()) {
                ensureSchema(connection);
            }
            initialized = true;
            status = config.type() == SqlType.SQLITE
                ? "SQLite 已连接: " + config.sqliteFile().getAbsolutePath()
                : "MySQL 已连接: " + config.host() + ":" + config.port() + "/" + config.database();
            return true;
        } catch (Exception exception) {
            initialized = false;
            status = "数据库初始化失败: " + exception.getMessage();
            plugin.getLogger().warning(status);
            return false;
        }
    }

    public boolean isInitialized() {
        return initialized;
    }

    public String status() {
        return status;
    }

    public void close() {
        initialized = false;
    }

    public void upsertTable(PersistedTableRecord record) {
        if (!initialized || record == null) {
            return;
        }
        String sql = """
            INSERT INTO persisted_tables
            (game_type, table_name, room_level, world_name, x, y, z, yaw, max_players, owner_uuid, owner_name, updated_at)
            VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
            """;
        try (Connection connection = openConnection()) {
            connection.setAutoCommit(false);
            try (PreparedStatement delete = connection.prepareStatement("DELETE FROM persisted_tables WHERE game_type = ? AND table_name = ?")) {
                delete.setString(1, record.gameType());
                delete.setString(2, record.tableName());
                delete.executeUpdate();
            }
            try (PreparedStatement insert = connection.prepareStatement(sql)) {
                insert.setString(1, record.gameType());
                insert.setString(2, record.tableName());
                insert.setString(3, record.roomLevel().key());
                insert.setString(4, record.worldName());
                insert.setDouble(5, record.x());
                insert.setDouble(6, record.y());
                insert.setDouble(7, record.z());
                insert.setFloat(8, record.yaw());
                insert.setInt(9, record.maxPlayers());
                insert.setString(10, record.ownerUuid());
                insert.setString(11, record.ownerName());
                insert.setTimestamp(12, new Timestamp(System.currentTimeMillis()));
                insert.executeUpdate();
            }
            connection.commit();
        } catch (SQLException exception) {
            plugin.getLogger().warning("保存牌桌持久化数据失败: " + exception.getMessage());
        }
    }

    public void deleteTable(String gameType, String tableName) {
        if (!initialized || tableName == null || gameType == null) {
            return;
        }
        try (Connection connection = openConnection();
             PreparedStatement statement = connection.prepareStatement("DELETE FROM persisted_tables WHERE game_type = ? AND table_name = ?")) {
            statement.setString(1, gameType);
            statement.setString(2, tableName);
            statement.executeUpdate();
        } catch (SQLException exception) {
            plugin.getLogger().warning("删除牌桌持久化数据失败: " + exception.getMessage());
        }
    }

    public List<PersistedTableRecord> loadTables() {
        List<PersistedTableRecord> result = new ArrayList<>();
        if (!initialized) {
            return result;
        }
        return withReadConnection("读取持久化牌桌失败", result, connection -> {
            Map<String, PersistedTableRecord> deduped = new LinkedHashMap<>();
            try (PreparedStatement statement = connection.prepareStatement(
                "SELECT game_type, table_name, room_level, world_name, x, y, z, yaw, max_players, owner_uuid, owner_name FROM persisted_tables ORDER BY updated_at DESC, game_type, table_name"
            );
                 ResultSet rs = statement.executeQuery()) {
                while (rs.next()) {
                    PersistedTableRecord record = new PersistedTableRecord(
                        rs.getString("game_type"),
                        rs.getString("table_name"),
                        parseLevel(rs.getString("room_level")),
                        rs.getString("world_name"),
                        rs.getDouble("x"),
                        rs.getDouble("y"),
                        rs.getDouble("z"),
                        rs.getFloat("yaw"),
                        rs.getInt("max_players"),
                        rs.getString("owner_uuid"),
                        rs.getString("owner_name")
                    );
                    deduped.putIfAbsent(record.gameType() + "|" + record.tableName().toLowerCase(Locale.ROOT), record);
                }
            }
            return new ArrayList<>(deduped.values());
        });
    }

    public long insertMatch(MatchRecord match, List<MatchParticipantRecord> participants) {
        if (!initialized || match == null) {
            return -1L;
        }
        String sql = """
            INSERT INTO match_records
            (game_type, table_name, room_level, outcome_label, occurred_at, world_name, x, y, z)
            VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)
            """;
        try (Connection connection = openConnection()) {
            connection.setAutoCommit(false);
            long matchId;
            try (PreparedStatement statement = connection.prepareStatement(sql, Statement.RETURN_GENERATED_KEYS)) {
                statement.setString(1, match.gameType());
                statement.setString(2, match.tableName());
                statement.setString(3, match.roomLevel().key());
                statement.setString(4, match.outcomeLabel());
                statement.setLong(5, match.occurredAt());
                statement.setString(6, match.worldName());
                statement.setDouble(7, match.x());
                statement.setDouble(8, match.y());
                statement.setDouble(9, match.z());
                statement.executeUpdate();
                try (ResultSet keys = statement.getGeneratedKeys()) {
                    matchId = keys.next() ? keys.getLong(1) : -1L;
                }
            }
            if (matchId > 0L && participants != null) {
                insertParticipants(connection, matchId, participants);
            }
            connection.commit();
            return matchId;
        } catch (SQLException exception) {
            plugin.getLogger().warning("写入战绩失败: " + exception.getMessage());
            return -1L;
        }
    }

    public List<PlayerHistoryEntry> loadPlayerHistory(UUID playerId, int limit, int offset) {
        List<PlayerHistoryEntry> entries = new ArrayList<>();
        if (!initialized || playerId == null) {
            return entries;
        }
        String sql = """
            SELECT mr.id, mr.game_type, mr.table_name, mr.room_level, mr.outcome_label, mr.occurred_at, mr.world_name, mr.x, mr.y, mr.z
            FROM match_participants mp
            JOIN match_records mr ON mr.id = mp.match_id
            WHERE mp.player_uuid = ?
            ORDER BY mr.occurred_at DESC, mr.id DESC
            LIMIT ? OFFSET ?
            """;
        return withReadConnection("读取玩家历史战绩失败", entries, connection -> {
            Map<Long, MatchRecord> matches = new LinkedHashMap<>();
            try (PreparedStatement statement = connection.prepareStatement(sql)) {
                statement.setString(1, playerId.toString());
                statement.setInt(2, Math.max(1, limit));
                statement.setInt(3, Math.max(0, offset));
                try (ResultSet rs = statement.executeQuery()) {
                    while (rs.next()) {
                        long matchId = rs.getLong("id");
                        matches.put(matchId, new MatchRecord(
                            rs.getString("game_type"),
                            rs.getString("table_name"),
                            parseLevel(rs.getString("room_level")),
                            rs.getString("outcome_label"),
                            rs.getLong("occurred_at"),
                            rs.getString("world_name"),
                            rs.getDouble("x"),
                            rs.getDouble("y"),
                            rs.getDouble("z")
                        ));
                    }
                }
            }
            if (matches.isEmpty()) {
                return new ArrayList<>();
            }

            Map<Long, List<MatchParticipantRecord>> participantsByMatch = loadParticipants(connection, matches.keySet());
            List<PlayerHistoryEntry> loaded = new ArrayList<>(matches.size());
            for (Map.Entry<Long, MatchRecord> entry : matches.entrySet()) {
                List<MatchParticipantRecord> participants = participantsByMatch.getOrDefault(entry.getKey(), List.of());
                MatchParticipantRecord self = participants.stream()
                    .filter(participant -> participant.playerId().equals(playerId))
                    .findFirst()
                    .orElse(null);
                loaded.add(new PlayerHistoryEntry(entry.getKey(), entry.getValue(), participants, self));
            }
            return loaded;
        });
    }

    private void insertParticipants(Connection connection, long matchId, List<MatchParticipantRecord> participants) throws SQLException {
        String sql = """
            INSERT INTO match_participants
            (match_id, player_uuid, player_name, role_label, outcome, score_delta, settlement_delta, unit_label, debt_after, balance_after, bankrupt)
            VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
            """;
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            for (MatchParticipantRecord participant : participants) {
                statement.setLong(1, matchId);
                statement.setString(2, participant.playerId().toString());
                statement.setString(3, participant.playerName());
                statement.setString(4, participant.roleLabel());
                statement.setString(5, participant.outcome());
                statement.setInt(6, participant.scoreDelta());
                statement.setDouble(7, participant.settlementDelta());
                statement.setString(8, participant.unitLabel());
                statement.setDouble(9, participant.debtAfter());
                statement.setDouble(10, participant.balanceAfter());
                statement.setInt(11, participant.bankrupt() ? 1 : 0);
                statement.addBatch();
            }
            statement.executeBatch();
        }
    }

    private Map<Long, List<MatchParticipantRecord>> loadParticipants(Connection connection, Set<Long> matchIds) throws SQLException {
        Map<Long, List<MatchParticipantRecord>> participantsByMatch = new LinkedHashMap<>();
        if (matchIds == null || matchIds.isEmpty()) {
            return participantsByMatch;
        }
        String placeholders = String.join(",", matchIds.stream().map(id -> "?").toList());
        String sql = """
            SELECT match_id, player_uuid, player_name, role_label, outcome, score_delta, settlement_delta, unit_label, debt_after, balance_after, bankrupt
            FROM match_participants
            WHERE match_id IN (%s)
            ORDER BY match_id ASC, id ASC
            """.formatted(placeholders);
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            int index = 1;
            for (Long matchId : matchIds) {
                statement.setLong(index++, matchId);
            }
            try (ResultSet rs = statement.executeQuery()) {
                while (rs.next()) {
                    UUID participantId = parseUuid(rs.getString("player_uuid"));
                    if (participantId == null) {
                        continue;
                    }
                    long matchId = rs.getLong("match_id");
                    participantsByMatch.computeIfAbsent(matchId, ignored -> new ArrayList<>()).add(new MatchParticipantRecord(
                        participantId,
                        rs.getString("player_name"),
                        rs.getString("role_label"),
                        rs.getString("outcome"),
                        rs.getInt("score_delta"),
                        rs.getDouble("settlement_delta"),
                        rs.getString("unit_label"),
                        rs.getDouble("debt_after"),
                        rs.getDouble("balance_after"),
                        rs.getInt("bankrupt") == 1
                    ));
                }
            }
        }
        return participantsByMatch;
    }

    private void ensureSchema(Connection connection) throws SQLException {
        try (Statement statement = connection.createStatement()) {
            if (config.type() == SqlType.SQLITE) {
                statement.executeUpdate("""
                    CREATE TABLE IF NOT EXISTS persisted_tables (
                        game_type VARCHAR(16) NOT NULL,
                        table_name VARCHAR(64) NOT NULL,
                        room_level VARCHAR(16) NOT NULL,
                        world_name VARCHAR(64) NOT NULL,
                        x DOUBLE NOT NULL,
                        y DOUBLE NOT NULL,
                        z DOUBLE NOT NULL,
                        yaw FLOAT NOT NULL,
                        max_players INTEGER NOT NULL,
                        owner_uuid VARCHAR(36),
                        owner_name VARCHAR(64),
                        updated_at TIMESTAMP NOT NULL,
                        PRIMARY KEY (game_type, table_name)
                    )
                    """);
                statement.executeUpdate("""
                    CREATE TABLE IF NOT EXISTS match_records (
                        id INTEGER PRIMARY KEY AUTOINCREMENT,
                        game_type VARCHAR(16) NOT NULL,
                        table_name VARCHAR(64) NOT NULL,
                        room_level VARCHAR(16) NOT NULL,
                        outcome_label VARCHAR(32) NOT NULL,
                        occurred_at BIGINT NOT NULL,
                        world_name VARCHAR(64),
                        x DOUBLE,
                        y DOUBLE,
                        z DOUBLE
                    )
                    """);
                statement.executeUpdate("""
                    CREATE TABLE IF NOT EXISTS match_participants (
                        id INTEGER PRIMARY KEY AUTOINCREMENT,
                        match_id BIGINT NOT NULL,
                        player_uuid VARCHAR(36) NOT NULL,
                        player_name VARCHAR(64) NOT NULL,
                        role_label VARCHAR(32) NOT NULL,
                        outcome VARCHAR(16) NOT NULL,
                        score_delta INTEGER NOT NULL,
                        settlement_delta DOUBLE NOT NULL,
                        unit_label VARCHAR(16) NOT NULL,
                        debt_after DOUBLE NOT NULL,
                        balance_after DOUBLE NOT NULL,
                        bankrupt INTEGER NOT NULL
                    )
                    """);
            } else {
                statement.executeUpdate("""
                    CREATE TABLE IF NOT EXISTS persisted_tables (
                        game_type VARCHAR(16) NOT NULL,
                        table_name VARCHAR(64) NOT NULL,
                        room_level VARCHAR(16) NOT NULL,
                        world_name VARCHAR(64) NOT NULL,
                        x DOUBLE NOT NULL,
                        y DOUBLE NOT NULL,
                        z DOUBLE NOT NULL,
                        yaw FLOAT NOT NULL,
                        max_players INT NOT NULL,
                        owner_uuid VARCHAR(36),
                        owner_name VARCHAR(64),
                        updated_at TIMESTAMP NOT NULL,
                        PRIMARY KEY (game_type, table_name)
                    )
                    """);
                statement.executeUpdate("""
                    CREATE TABLE IF NOT EXISTS match_records (
                        id BIGINT PRIMARY KEY AUTO_INCREMENT,
                        game_type VARCHAR(16) NOT NULL,
                        table_name VARCHAR(64) NOT NULL,
                        room_level VARCHAR(16) NOT NULL,
                        outcome_label VARCHAR(32) NOT NULL,
                        occurred_at BIGINT NOT NULL,
                        world_name VARCHAR(64),
                        x DOUBLE,
                        y DOUBLE,
                        z DOUBLE
                    )
                    """);
                statement.executeUpdate("""
                    CREATE TABLE IF NOT EXISTS match_participants (
                        id BIGINT PRIMARY KEY AUTO_INCREMENT,
                        match_id BIGINT NOT NULL,
                        player_uuid VARCHAR(36) NOT NULL,
                        player_name VARCHAR(64) NOT NULL,
                        role_label VARCHAR(32) NOT NULL,
                        outcome VARCHAR(16) NOT NULL,
                        score_delta INT NOT NULL,
                        settlement_delta DOUBLE NOT NULL,
                        unit_label VARCHAR(16) NOT NULL,
                        debt_after DOUBLE NOT NULL,
                        balance_after DOUBLE NOT NULL,
                        bankrupt INT NOT NULL
                    )
                    """);
            }
            try {
                statement.executeUpdate("ALTER TABLE match_participants ADD COLUMN unit_label VARCHAR(16) NOT NULL DEFAULT '金币'");
            } catch (SQLException ignored) {
            }
            try {
                statement.executeUpdate("ALTER TABLE persisted_tables ADD COLUMN owner_uuid VARCHAR(36)");
            } catch (SQLException ignored) {
            }
            try {
                statement.executeUpdate("ALTER TABLE persisted_tables ADD COLUMN owner_name VARCHAR(64)");
            } catch (SQLException ignored) {
            }
            statement.executeUpdate("CREATE INDEX IF NOT EXISTS idx_persisted_tables_updated_at ON persisted_tables(updated_at)");
            statement.executeUpdate("CREATE INDEX IF NOT EXISTS idx_match_records_occurred_at ON match_records(occurred_at DESC, id DESC)");
            statement.executeUpdate("CREATE INDEX IF NOT EXISTS idx_match_participants_player_uuid ON match_participants(player_uuid, match_id)");
            statement.executeUpdate("CREATE INDEX IF NOT EXISTS idx_match_participants_match_id ON match_participants(match_id)");
        }
    }

    private Connection openConnection() throws SQLException {
        Connection connection;
        if (config.type() == SqlType.SQLITE) {
            connection = DriverManager.getConnection("jdbc:sqlite:" + config.sqliteFile().getAbsolutePath());
            try (Statement statement = connection.createStatement()) {
                statement.execute("PRAGMA journal_mode=WAL");
                statement.execute("PRAGMA synchronous=NORMAL");
                statement.execute("PRAGMA foreign_keys=ON");
                statement.execute("PRAGMA temp_store=MEMORY");
                statement.execute("PRAGMA busy_timeout=" + SQLITE_BUSY_TIMEOUT_MILLIS);
            }
            return connection;
        }
        String url = "jdbc:mysql://"
            + config.host()
            + ":"
            + config.port()
            + "/"
            + config.database()
            + "?"
            + config.parameters();
        connection = DriverManager.getConnection(url, config.username(), config.password());
        connection.setReadOnly(false);
        return connection;
    }

    private UUID parseUuid(String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        try {
            return UUID.fromString(raw);
        } catch (IllegalArgumentException exception) {
            plugin.getLogger().warning("跳过一条无效玩家 UUID 的战绩记录: " + raw);
            return null;
        }
    }

    private <T> T withReadConnection(String failureMessage, T fallback, SqlReader<T> reader) {
        SQLException last = null;
        for (int attempt = 1; attempt <= READ_RETRY_COUNT; attempt++) {
            try (Connection connection = openConnection()) {
                return reader.read(connection);
            } catch (SQLException exception) {
                last = exception;
                if (attempt >= READ_RETRY_COUNT) {
                    plugin.getLogger().warning(failureMessage + ": " + exception.getMessage());
                }
            }
        }
        return fallback;
    }

    @FunctionalInterface
    private interface SqlReader<T> {
        T read(Connection connection) throws SQLException;
    }

    private TableLevel parseLevel(String raw) {
        TableLevel level = TableLevel.parse(raw);
        return level == null ? TableLevel.FUN : level;
    }

    private record SqlConfig(
        SqlType type,
        File sqliteFile,
        String host,
        int port,
        String database,
        String username,
        String password,
        String parameters
    ) {
        private static SqlConfig fromConfig(DoudizhuPlugin plugin, FileConfiguration configuration) {
            String type = configuration.getString("storage.sql.type", "sqlite").trim().toLowerCase(Locale.ROOT);
            SqlType sqlType = "mysql".equals(type) ? SqlType.MYSQL : SqlType.SQLITE;
            File sqliteFile = new File(plugin.getDataFolder(), configuration.getString("storage.sql.sqlite.file", "storage/mumu-data.db"));
            return new SqlConfig(
                sqlType,
                sqliteFile,
                configuration.getString("storage.sql.mysql.host", "127.0.0.1"),
                configuration.getInt("storage.sql.mysql.port", 3306),
                configuration.getString("storage.sql.mysql.database", "muz"),
                configuration.getString("storage.sql.mysql.username", "root"),
                configuration.getString("storage.sql.mysql.password", ""),
                configuration.getString("storage.sql.mysql.parameters", "useSSL=false&characterEncoding=utf8&serverTimezone=Asia/Shanghai")
            );
        }
    }

    private enum SqlType {
        SQLITE,
        MYSQL
    }
}
