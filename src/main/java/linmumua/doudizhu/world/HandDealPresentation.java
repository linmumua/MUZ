package linmumua.doudizhu.world;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

/**
 * 开局发牌展示的纯内存状态。
 *
 * <p>每个座位固定保留 17 个槽位；牌面追加与 180 度排序只更新槽位内容，
 * 不改变 Display 实体身份，也不依赖普通手牌悬停动画的变换路径。
 * {@link #centeredSlotOffset(int, int)} 与普通手牌共用同一套居中偏移，避免开局左右镜像。
 */
final class HandDealPresentation {
    static final int SLOT_COUNT = 17;

    private final Map<UUID, Seat> seats = new LinkedHashMap<>();
    private double continuousDegrees;
    private boolean active;

    Seat seat(UUID playerId) {
        return seats.computeIfAbsent(playerId, ignored -> new Seat());
    }

    Map<UUID, Seat> seats() {
        return seats;
    }

    double continuousDegrees(double rawDegrees, boolean openingPhase) {
        double raw = Math.max(0.0, Math.min(360.0, rawDegrees));
        if (!openingPhase && continuousDegrees >= 360.0) {
            return continuousDegrees;
        }
        if (raw + 0.001 < continuousDegrees && continuousDegrees < 360.001) {
            raw += 360.0;
        }
        continuousDegrees = Math.min(360.0, Math.max(continuousDegrees, raw));
        return continuousDegrees;
    }

    void reset(double degrees) {
        seats.clear();
        continuousDegrees = degrees;
        active = false;
    }

    void activate() {
        active = true;
    }

    boolean active() {
        return active;
    }

    static double centeredStartOffset(int handSize) {
        if (handSize < 0) {
            throw new IllegalArgumentException("手牌数量不能为负数: " + handSize);
        }
        return -((handSize - 1) * 0.5);
    }

    static double centeredSlotOffset(int handSize, int index) {
        if (index < 0 || index >= handSize) {
            throw new IllegalArgumentException("手牌索引超出范围: " + index + "/" + handSize);
        }
        return centeredStartOffset(handSize) + index;
    }

    static double slotOffset(int slot) {
        if (slot < 0 || slot >= SLOT_COUNT) {
            throw new IllegalArgumentException("开局手牌槽位必须位于 0..16: " + slot);
        }
        return centeredSlotOffset(SLOT_COUNT, slot);
    }

    static boolean itemNeedsUpdate(Slot previous, int cardId) {
        return previous == null || previous.cardId() != cardId;
    }

    static final class Seat {
        private final Slot[] privateSlots = new Slot[SLOT_COUNT];
        private final Slot[] backsideSlots = new Slot[SLOT_COUNT];

        Slot privateSlot(int index) {
            return privateSlots[index];
        }

        void privateSlot(int index, Slot slot) {
            privateSlots[index] = slot;
        }

        Slot backsideSlot(int index) {
            return backsideSlots[index];
        }

        void backsideSlot(int index, Slot slot) {
            backsideSlots[index] = slot;
        }
    }

    record Slot(int index, UUID displayId, int cardId) {
    }
}
