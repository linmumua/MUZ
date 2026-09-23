package linmumua.doudizhu.storage;

import linmumua.doudizhu.DoudizhuPlugin;
import linmumua.doudizhu.room.TableLevel;
import linmumua.doudizhu.scheduler.MuzScheduler;
import java.io.File;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.sql.Timestamp;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ConcurrentHashMap;
import linmumua.doudizhu.config.MuzYamlConfig;

public final class DatabaseManager {
    private static final int READ_RETRY_COUNT = 2;
    private static final int SQLITE_BUSY_TIMEOUT_MILLIS = 5000;
    private final DoudizhuPlugin plugin;
    /**
     * 这三个字段全部 {@code volatile}：主线程在 {@link #initialize()} / {@link #close()}
     * 里写，异步写库线程要读。
     *
     * <p>【为什么现在必须加】：写操作下沉到 {@link #runWrite} 的异步线程之后，
     * 异步体会经 {@link #openConnection()} 读 {@code config}，也会读 {@code initialized}。
     * 没有 volatile，JMM 不保证异步线程看得到主线程刚写入的值——可能读到
     * {@code initialized == true} 却配 {@code config == null}，直接 NPE；
     * 或者关服后仍看到旧的 true 而去开一条已经该关掉的连接。
     *
     * <p>{@code status} 也一并标上：它被 {@code /muz status} 从命令线程读，
     * 而命令线程与主线程在 Paper 上并不总是同一个。
     */
    private volatile SqlConfig config;
    private volatile boolean initialized;
    private volatile boolean closing;
    private volatile String status = "尚未连接";
    /** 所有异步数据库操作都登记在这里，关闭时等待其完成，避免任务被 Paper 直接取消。 */
    private final Set<CompletableFuture<?>> pendingOperations = ConcurrentHashMap.newKeySet();
    /** 把关闭与新写入串起来，避免 flush 开始后又偷偷登记一笔新的数据库操作。 */
    private final Object lifecycleLock = new Object();

    public DatabaseManager(DoudizhuPlugin plugin) {
        this.plugin = plugin;
    }

    public boolean initialize() {
        synchronized (lifecycleLock) {
            closing = false;
        }
        config = SqlConfig.fromConfig(plugin, plugin.yamlConfig());
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
                ? "SQLite: " + config.sqliteFile().getAbsolutePath()
                : "MySQL: " + config.host() + ":" + config.port() + "/" + config.database();
            return true;
        } catch (Exception exception) {
            initialized = false;
            status = "数据库连接失败，本次不记录战绩: " + exception.getMessage();
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
        synchronized (lifecycleLock) {
            if (closing) {
                return;
            }
            closing = true;
            initialized = false;
        }
        status = "数据库已关闭";
        flushPendingWrites(5000L);
    }

    /**
     * 等待已经提交的数据库操作完成。正常运行期不调用；关服时由插件入口调用，
     * 让已排队的异步写库有机会落盘。超时只记录警告，不伪造“已完成”。
     */
    public void flushPendingWrites(long timeoutMillis) {
        long timeout = Math.max(0L, timeoutMillis);
        long deadline = System.nanoTime() + timeout * 1_000_000L;
        while (!pendingOperations.isEmpty()) {
            List<CompletableFuture<?>> snapshot = List.copyOf(pendingOperations);
            try {
                CompletableFuture.allOf(snapshot.toArray(CompletableFuture[]::new)).get(
                    Math.max(1L, deadline - System.nanoTime()),
                    java.util.concurrent.TimeUnit.NANOSECONDS
                );
                return;
            } catch (java.util.concurrent.TimeoutException exception) {
                plugin.getLogger().warning("等待数据库异步写入超时，仍有 " + pendingOperations.size() + " 个操作未完成");
                return;
            } catch (InterruptedException exception) {
                Thread.currentThread().interrupt();
                plugin.getLogger().warning("等待数据库异步写入时被中断");
                return;
            } catch (java.util.concurrent.ExecutionException exception) {
                plugin.getLogger().warning("数据库异步操作失败: " + exception.getCause());
                return;
            }
        }
    }

    /**
     * 把一次写库丢到异步线程；关服/禁用时退化成同步执行。
     *
     * <p>【为什么关服必须有同步回退】：Bukkit 在插件 disable 之后再提交异步任务会抛
     * IllegalPluginAccessException，而且已排队未执行的异步任务会被直接丢弃——
     * 关服那一刻提交的写库就永久丢失了。所以关服路径上宁可阻塞调用线程也要把数据落盘，
     * 防止关服/禁用阶段提交的写库被静默丢掉。
     *
     * <p>【如实记录代价】：同步回退会在调用线程（可能是 onDisable 主线程，也可能是迟到的
     * region/owner 线程）直接执行同步 JDBC，会阻塞该线程。这是明确选择的取舍：宁可让关服/迟到
     * 线程短暂卡住，也不接受关服那一刻的写库永久丢失。
     *
     * <p>【异步段仍保留】：插件仍启用时照常异步提交，由插件入口在 scheduler/backend 关闭前
     * 调用 {@link #flushPendingWrites(long)} 等待已登记操作完成。若调度门面在「插件仍启用但
     * scheduler 已关闭」这类残余窗口返回 null 或已取消句柄，则就地移除该 completion、以异常
     * 结束并记 warning，避免 flush 空等到超时同时静默丢数据。
     *
     * <p>【为什么写操作可以 fire-and-forget】：upsertTable / deleteTable / insertMatch
     * 三个方法的返回值在所有调用点都被丢弃（insertMatch 虽然返回 matchId，但
     * DoudizhuPlugin:864 没有接），没有任何调用方依赖"写完了"这个时刻，
     * 因此不需要回调，也不会引入时序问题。
     *
     * @param what 失败时写进日志的操作名
     */
    private void runWrite(String what, SqlWrite write) {
        CompletableFuture<Void> completion = new CompletableFuture<>();
        synchronized (lifecycleLock) {
            if (closing || !initialized) {
                plugin.getLogger().warning(what + "被拒绝：数据库正在关闭或尚未初始化");
                return;
            }
            pendingOperations.add(completion);
        }
        Runnable body = () -> {
            try {
                write.run();
                completion.complete(null);
            } catch (SQLException exception) {
                // 异步线程里的异常不会自动进控制台，必须自己记，否则数据静默丢失。
                plugin.getLogger().warning(what + "失败: " + exception.getMessage());
                completion.completeExceptionally(exception);
            } catch (RuntimeException exception) {
                plugin.getLogger().warning(what + "失败: " + exception.getMessage());
                completion.completeExceptionally(exception);
            } finally {
                pendingOperations.remove(completion);
            }
        };
        // 关服/禁用：退化成同步执行，防止关服那一刻提交的写库在异步队列里被永久丢弃。
        // 如实记录代价：这会阻塞调用线程（可能是 onDisable 主线程，也可能是迟到的 region/owner
        // 线程）直接跑同步 JDBC；这是明确选择的取舍，宁可短暂卡住也不接受写库丢失。
        if (plugin.isShuttingDown() || !plugin.isEnabled()) {
            body.run();
            return;
        }
        try {
            // 插件仍启用时保持异步提交，flushPendingWrites() 会在 scheduler/backend 关闭前等待它。
            MuzScheduler.TaskHandle handle = plugin.scheduler().runAsync(body);
            if (handle == null || handle.isCancelled()) {
                // 残余窗口：插件仍启用但 scheduler 已关闭，调度门面会拒绝注册并返回 null/已取消句柄，
                // body 永远不会执行。必须在这里就地收尾，否则这个 completion 会永远留在 pendingOperations 里，
                // 让 flushPendingWrites() 空等到超时，同时这条写库被静默丢弃。
                pendingOperations.remove(completion);
                completion.completeExceptionally(
                    new IllegalStateException("关闭阶段拒绝了异步写库任务: " + what));
                plugin.getLogger().warning(what + "提交失败：调度门面在关闭阶段拒绝了异步写库任务");
            }
        } catch (RuntimeException exception) {
            pendingOperations.remove(completion);
            completion.completeExceptionally(exception);
            plugin.getLogger().warning(what + "提交异步任务失败: " + exception.getMessage());
        }
    }

    /** 允许抛 SQLException 的写库动作，交给 {@link #runWrite} 统一兜异常与选线程。 */
    private interface SqlWrite {
        void run() throws SQLException;
    }

    public void upsertTable(PersistedTableRecord record) {
        if (!initialized || record == null) {
            return;
        }
        runWrite("保存牌桌持久化数据", () -> upsertTableSync(record));
    }

    private void upsertTableSync(PersistedTableRecord record) throws SQLException {
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
        }
    }

    public void deleteTable(String gameType, String tableName) {
        if (!initialized || tableName == null || gameType == null) {
            return;
        }
        runWrite("删除牌桌持久化数据", () -> deleteTableSync(gameType, tableName));
    }

    private void deleteTableSync(String gameType, String tableName) throws SQLException {
        try (Connection connection = openConnection();
             PreparedStatement statement = connection.prepareStatement("DELETE FROM persisted_tables WHERE game_type = ? AND table_name = ?")) {
            statement.setString(1, gameType);
            statement.setString(2, tableName);
            statement.executeUpdate();
        }
    }

    /** 异步加载牌桌的不可变结果，区分数据库未就绪、成功空表和读取失败。 */
    public record TableLoadResult(
        boolean ready,
        List<PersistedTableRecord> records,
        Throwable failure
    ) {
        public TableLoadResult {
            records = records == null ? List.of() : List.copyOf(records);
        }

        public static TableLoadResult success(List<PersistedTableRecord> records) {
            return new TableLoadResult(true, records, null);
        }

        public static TableLoadResult notReady() {
            return new TableLoadResult(false, List.of(), null);
        }

        public static TableLoadResult failure(Throwable failure) {
            return new TableLoadResult(false, List.of(), failure);
        }
    }

    public List<PersistedTableRecord> loadTables() {
        List<PersistedTableRecord> result = new ArrayList<>();
        if (!initialized) {
            return result;
        }
        return readTablesNow();
    }

    /**
     * 异步读取持久化牌桌。DTO 明确携带成功状态，调用方不会把数据库失败误判为空表。
     * 旧的 {@link #loadTables()} 保留给兼容调用方，但启动恢复应优先使用此入口。
     */
    public CompletableFuture<TableLoadResult> loadTablesAsync() {
        CompletableFuture<TableLoadResult> completion = new CompletableFuture<>();
        pendingOperations.add(completion);
        Runnable body = () -> {
            try {
                if (!initialized) {
                    completion.complete(TableLoadResult.notReady());
                } else {
                    completion.complete(TableLoadResult.success(readTablesNow()));
                }
            } catch (RuntimeException exception) {
                plugin.getLogger().warning("读取持久化牌桌失败: " + exception.getMessage());
                completion.complete(TableLoadResult.failure(exception));
            } finally {
                pendingOperations.remove(completion);
            }
        };
        synchronized (lifecycleLock) {
            if (closing || !initialized) {
                pendingOperations.remove(completion);
                completion.complete(TableLoadResult.notReady());
                return completion;
            }
        }
        try {
            // 关闭阶段不再把读取退化到当前 owner 线程，避免迟到 region/player 回调同步触碰 JDBC。
            plugin.scheduler().runAsync(body);
        } catch (RuntimeException exception) {
            pendingOperations.remove(completion);
            completion.complete(TableLoadResult.failure(exception));
        }
        return completion;
    }

    private List<PersistedTableRecord> readTablesNow() {
        List<PersistedTableRecord> result = new ArrayList<>();
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

    /**
     * 写入一局战绩。
     *
     * <p>【为什么返回 void 而不是 matchId】：这个方法改成异步之后，matchId 要到异步线程
     * 才拿得到，同步返回一个值只能是假的。原先唯一的调用方（DoudizhuPlugin:864）本来
     * 也没接返回值，所以直接把签名收成 void，避免留一个"永远返回 -1"的骗人接口。
     * 将来真需要 matchId，应该加一个带回调的重载，而不是把这个改回同步。
     */
    public void insertMatch(MatchRecord match, List<MatchParticipantRecord> participants) {
        if (!initialized || match == null) {
            return;
        }
        runWrite("写入战绩", () -> insertMatchSync(match, participants));
    }

    private void insertMatchSync(MatchRecord match, List<MatchParticipantRecord> participants) throws SQLException {
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
        private static SqlConfig fromConfig(DoudizhuPlugin plugin, MuzYamlConfig configuration) {
            String type = configuration.getString("storage.sql.type", "sqlite").trim().toLowerCase(Locale.ROOT);
            SqlType sqlType = "mysql".equals(type) ? SqlType.MYSQL : SqlType.SQLITE;
            File sqliteFile = new File(plugin.getDataFolder(), configuration.getString("storage.sql.sqlite.file", "storage/data.db"));
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
