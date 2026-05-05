package dev.mumu.doudizhu.mahjong;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
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
    private final Location center;
    private final UUID ownerId;
    private final String ownerName;
    private final long createdAtMillis;
    private final Map<Seat, UUID> occupants = new LinkedHashMap<>();
    private final Map<Seat, String> occupantNames = new LinkedHashMap<>();
    private final Map<Seat, Boolean> readyStates = new LinkedHashMap<>();
    private final List<UUID> visualEntityIds = new ArrayList<>();
    private volatile MahjongLayoutConfig layoutConfig;

    public MahjongTableSession(String id, Location center, UUID ownerId, String ownerName, long createdAtMillis, MahjongLayoutConfig layoutConfig) {
        this.id = id;
        this.center = center.clone();
        this.ownerId = ownerId;
        this.ownerName = ownerName;
        this.createdAtMillis = createdAtMillis;
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

    public MahjongLayoutConfig layoutConfig() {
        return layoutConfig;
    }

    public Map<Seat, UUID> occupants() {
        return occupants;
    }

    public Map<Seat, String> occupantNames() {
        return occupantNames;
    }

    public Map<Seat, Boolean> readyStates() {
        return readyStates;
    }

    public List<UUID> visualEntityIds() {
        return visualEntityIds;
    }

    public boolean sit(Seat seat, UUID playerId, String playerName) {
        if (seat == null || playerId == null || playerName == null || occupants.containsKey(seat)) {
            return false;
        }
        occupants.put(seat, playerId);
        occupantNames.put(seat, playerName);
        readyStates.put(seat, false);
        return true;
    }

    public boolean leave(UUID playerId) {
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

    public Seat seatOf(UUID playerId) {
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

    public boolean toggleReady(UUID playerId) {
        Seat seat = seatOf(playerId);
        if (seat == null) {
            return false;
        }
        boolean next = !readyStates.getOrDefault(seat, false);
        readyStates.put(seat, next);
        return next;
    }

    public boolean isReady(Seat seat) {
        return readyStates.getOrDefault(seat, false);
    }

    public int readyCount() {
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
