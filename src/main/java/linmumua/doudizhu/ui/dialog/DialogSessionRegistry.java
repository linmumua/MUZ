package linmumua.doudizhu.ui.dialog;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.LongSupplier;

/**
 * Dialog 会话代次注册表。
 *
 * <p>原生 Dialog 的按钮回调是异步到达的（客户端点完按钮，服务端才收到响应）。玩家在回调
 * 落地之前可能已经关了面板、换了页、退出游戏，甚至重新打开了另一个面板。如果回调不做校验
 * 就执行，就会出现「点了旧面板的按钮，改了当前面板的值」这类错乱。
 *
 * <p>本注册表给每次「打开某个屏」分配一个单调递增的代次（generation）。按钮回调在构建时
 * 捕获当时的代次，执行前比对：代次不再是最新，说明这一屏已经被替换或关闭，回调必须丢弃。
 *
 * <p>代次之外还有 TTL：会话在 {@link #DEFAULT_TTL_MILLIS} 内没有重新 begin 就视为过期，
 * 避免玩家长期挂着一个面板而回调无限期有效。TTL 由 {@link MuzDialogService#tick()} 周期推进。
 *
 * <p>做成独立纯类（不碰 Bukkit）是为了能直接单测代次翻转与 TTL 过期。
 */
public final class DialogSessionRegistry {
    /** 默认会话 TTL：10 分钟。 */
    public static final long DEFAULT_TTL_MILLIS = 10L * 60_000L;

    private final Map<UUID, Session> sessions = new ConcurrentHashMap<>();
    private final LongSupplier millisClock;
    private final long ttlMillis;

    /** 全局单调代次序列：跨玩家唯一，保证同一毫秒内的两次 open 也不会撞代次。 */
    private static final java.util.concurrent.atomic.AtomicLong GENERATION_SEQUENCE =
        new java.util.concurrent.atomic.AtomicLong();

    public DialogSessionRegistry() {
        this(System::currentTimeMillis, DEFAULT_TTL_MILLIS);
    }

    /** 测试构造：注入毫秒时钟，让 TTL 可被推进而不是真实等待。 */
    public DialogSessionRegistry(LongSupplier millisClock) {
        this(millisClock, DEFAULT_TTL_MILLIS);
    }

    public DialogSessionRegistry(LongSupplier millisClock, long ttlMillis) {
        this.millisClock = millisClock;
        this.ttlMillis = ttlMillis;
    }

    /**
     * 开始一次新会话：代次 +1 并返回本次代次。同一玩家的旧代次立即失效。
     *
     * @param playerId 玩家
     * @param screenKey 屏标识，仅用于审计与日志
     * @return 本次会话代次，恒 &gt; 0
     */
    public long begin(UUID playerId, String screenKey) {
        if (playerId == null) {
            return 0L;
        }
        Session session = new Session(
            GENERATION_SEQUENCE.incrementAndGet(),
            millisClock.getAsLong(),
            screenKey == null ? "" : screenKey
        );
        sessions.put(playerId, session);
        return session.generation;
    }

    /**
     * 该代次是否仍是当前会话且未过期。
     *
     * @param playerId 玩家
     * @param generation 构建时捕获的代次
     * @return true 表示回调仍可执行
     */
    public boolean current(UUID playerId, long generation) {
        if (playerId == null || generation <= 0L) {
            return false;
        }
        Session session = sessions.get(playerId);
        if (session == null || session.generation != generation) {
            return false;
        }
        return !expired(session);
    }

    /** 当前会话代次；无会话返回 0。 */
    public long currentGeneration(UUID playerId) {
        Session session = playerId == null ? null : sessions.get(playerId);
        return session == null ? 0L : session.generation;
    }

    /** 当前会话的屏标识；无会话返回 null。 */
    public String currentScreenKey(UUID playerId) {
        Session session = playerId == null ? null : sessions.get(playerId);
        return session == null ? null : session.screenKey;
    }

    /** 结束该玩家的会话（手动关闭）。 */
    public void end(UUID playerId) {
        if (playerId != null) {
            sessions.remove(playerId);
        }
    }

    /** 退出/离线清理，等价 {@link #end(UUID)}。 */
    public void clear(UUID playerId) {
        end(playerId);
    }

    public void clearAll() {
        sessions.clear();
    }

    public int size() {
        return sessions.size();
    }

    /** 当前持有会话的玩家 UUID 快照（用于 reload/关闭时逐个关闭已打开的 Dialog）。 */
    public java.util.Set<UUID> trackedPlayers() {
        return new java.util.HashSet<>(sessions.keySet());
    }

    /** 清理已过期会话；由 {@link MuzDialogService#tick()} 周期调用。 */
    public void expireStale() {
        sessions.entrySet().removeIf(entry -> expired(entry.getValue()));
    }

    private boolean expired(Session session) {
        return ttlMillis > 0L && millisClock.getAsLong() - session.openedAtMillis >= ttlMillis;
    }

    /**
     * 单次会话。
     *
     * <p>代次来自全局单调序列而不是毫秒时间戳：同一毫秒内连续 open 两次也必须得到不同代次，
     * 否则「关旧开新」会因代次相同被误判为同一会话，旧回调被判为有效。
     */
    private static final class Session {
        private final long generation;
        private final long openedAtMillis;
        private final String screenKey;

        private Session(long generation, long openedAtMillis, String screenKey) {
            this.generation = generation;
            this.openedAtMillis = openedAtMillis;
            this.screenKey = screenKey;
        }
    }
}
