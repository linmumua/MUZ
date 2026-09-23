package linmumua.doudizhu.mahjong;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicLong;
import org.bukkit.Location;

public final class MahjongTableSession {
    public enum Seat {
        EAST("东位", 0.0, -1.0),
        SOUTH("南位", 1.0, 0.0),
        WEST("西位", 0.0, 1.0),
        NORTH("北位", -1.0, 0.0);

        private final String label;
        private final double xFactor;
        private final double zFactor;

        Seat(String label, double xFactor, double zFactor) {
            this.label = label;
            this.xFactor = xFactor;
            this.zFactor = zFactor;
        }

        public String label() {
            return label;
        }

        public double xFactor() {
            return xFactor;
        }

        public double zFactor() {
            return zFactor;
        }
    }

    private final String id;
    /** 桌锚点是麻将桌所有 region 调度的 owner；不要把实体任务投递到全局线程。 */
    private final Location center;
    private final UUID ownerId;
    private final String ownerName;
    private final long createdAtMillis;
    /** 桌 owner 任务的代次闸门；每次重渲染或清理都必须推进，淘汰迟到回调。 */
    private final AtomicLong generation;
    private final Map<Seat, UUID> occupants = new LinkedHashMap<>();
    private final Map<Seat, String> occupantNames = new LinkedHashMap<>();
    private final Map<Seat, Boolean> readyStates = new LinkedHashMap<>();
    /** 渲染任务可能跨 Paper/Folia region 回调读取，使用并发列表只保存 UUID，不跨线程操作实体。 */
    private final List<UUID> visualEntityIds = new CopyOnWriteArrayList<>();
    private volatile MahjongLayoutConfig layoutConfig;

    public MahjongTableSession(String id, Location center, UUID ownerId, String ownerName, long createdAtMillis, MahjongLayoutConfig layoutConfig) {
        this.id = id;
        this.center = center.clone();
        this.ownerId = ownerId;
        this.ownerName = ownerName;
        this.createdAtMillis = createdAtMillis;
        this.generation = new AtomicLong(1L);
        this.layoutConfig = layoutConfig;
    }

    public String id() {
        return id;
    }

    public Location center() {
        return center.clone();
    }

    public UUID ownerId() {
        return ownerId;
    }

    public String ownerName() {
        return ownerName;
    }

    public long createdAtMillis() {
        return createdAtMillis;
    }

    /** 返回当前桌实例的调度代次；重渲染/删除会推进它以淘汰迟到回调。 */
    public long generation() {
        return generation.get();
    }

    /** 推进桌 owner 代次，使已提交但尚未执行的旧回调失效。 */
    public long nextGeneration() {
        return generation.incrementAndGet();
    }

    /** 判断回调是否仍属于当前桌 owner 代次。 */
    public boolean isGeneration(long expectedGeneration) {
        return generation.get() == expectedGeneration;
    }

    /** 只读桌锚点副本，供 region 调度入口使用。 */
    public Location ownerAnchor() {
        return center();
    }

    /** 桌锚点的简短别名，避免调用方把它误当成全局调度 owner。 */
    public Location anchor() {
        return ownerAnchor();
    }

    public MahjongLayoutConfig layoutConfig() {
        return layoutConfig;
    }

    /** 返回当前入座玩家 UUID 的快照；调用方不得跨 owner lane 修改桌状态。 */
    public synchronized Map<Seat, UUID> occupants() {
        return Map.copyOf(occupants);
    }

    /** 返回当前入座玩家 UUID，供玩家输出门面按 UUID 重新解析在线玩家。 */
    public synchronized List<UUID> occupantIdsSnapshot() {
        return List.copyOf(occupants.values());
    }

    /** 返回当前座位名称的快照；玩家输出应继续通过 UUID 门面投递。 */
    public synchronized Map<Seat, String> occupantNames() {
        return Map.copyOf(occupantNames);
    }

    /** 返回准备状态快照；不暴露 Session 内部可变 map。 */
    public synchronized Map<Seat, Boolean> readyStates() {
        return Map.copyOf(readyStates);
    }

    /** 兼容现有桌管理器的 UUID 登记表访问；实体操作只能在桌 owner lane 中执行。 */
    public List<UUID> visualEntityIds() {
        return visualEntityIds;
    }

    /** 返回实体 UUID 快照，供迟到清理回调按 UUID 重新解析实体。 */
    public List<UUID> visualEntityIdsSnapshot() {
        return List.copyOf(visualEntityIds);
    }

    /** 登记实体 UUID，不持有 Bukkit Entity 引用。 */
    public void rememberVisualEntity(UUID entityId) {
        if (entityId != null) {
            visualEntityIds.add(entityId);
        }
    }

    /** 判断 UUID 是否仍属于本桌视觉实体。 */
    public boolean ownsVisualEntity(UUID entityId) {
        return entityId != null && visualEntityIds.contains(entityId);
    }

    public synchronized boolean sit(Seat seat, UUID playerId, String playerName) {
        if (seat == null || playerId == null || playerName == null || occupants.containsKey(seat)) {
            return false;
        }
        occupants.put(seat, playerId);
        occupantNames.put(seat, playerName);
        readyStates.put(seat, false);
        return true;
    }

    public synchronized boolean leave(UUID playerId) {
        if (playerId == null) {
            return false;
        }
        for (Seat seat : Seat.values()) {
            if (playerId.equals(occupants.get(seat))) {
                occupants.remove(seat);
                occupantNames.remove(seat);
                readyStates.remove(seat);
                return true;
            }
        }
        return false;
    }

    public synchronized Seat seatOf(UUID playerId) {
        if (playerId == null) {
            return null;
        }
        for (Seat seat : Seat.values()) {
            if (playerId.equals(occupants.get(seat))) {
                return seat;
            }
        }
        return null;
    }

    public synchronized boolean toggleReady(UUID playerId) {
        Seat seat = seatOf(playerId);
        if (seat == null) {
            return false;
        }
        boolean next = !readyStates.getOrDefault(seat, false);
        readyStates.put(seat, next);
        return next;
    }

    public synchronized boolean isReady(Seat seat) {
        return readyStates.getOrDefault(seat, false);
    }

    public synchronized int readyCount() {
        int count = 0;
        for (Seat seat : Seat.values()) {
            if (readyStates.getOrDefault(seat, false)) {
                count++;
            }
        }
        return count;
    }

    public void clearVisuals() {
        visualEntityIds.clear();
    }

    public void applyLayout(MahjongLayoutConfig layoutConfig) {
        this.layoutConfig = layoutConfig;
    }
}
