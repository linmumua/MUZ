package linmumua.doudizhu.game;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.function.Supplier;
import java.util.logging.Level;
import java.util.logging.Logger;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;

/**
 * 以玩家真实背包物品作为筹码余额的主线程服务。
 *
 * <p>筹码只扫描主背包 0..35 与副手；护甲、光标和 extra contents 永远不参与。
 * 所有变更先完成完整预演，应用失败时按原槽位快照回滚，绝不掉落物品。
 */
public final class PhysicalChipService {
    private static final int STORAGE_SIZE = 36;

    private final Supplier<ItemStack> templateSupplier;
    private final Logger logger;
    private final RuntimeAccess runtime;

    public PhysicalChipService(Supplier<ItemStack> template, Logger logger) {
        this(template, logger, RuntimeAccess.bukkit());
    }

    public PhysicalChipService(Supplier<ItemStack> template, Logger logger, RuntimeAccess runtime) {
        this.templateSupplier = Objects.requireNonNull(template, "template");
        this.logger = Objects.requireNonNull(logger, "logger");
        this.runtime = Objects.requireNonNull(runtime, "runtime");
    }

    /** 返回主背包与副手中模板筹码的总数量。 */
    public int balance(UUID playerId) {
        PlayerHandle player = requirePlayer(playerId);
        ItemStack template = template();
        InventorySnapshot snapshot = InventorySnapshot.capture(player);
        return checkedInt(snapshot.amount(template), "筹码余额");
    }

    /** 将余额设置为目标值；失败时原库存保持不变。 */
    public int setBalance(UUID playerId, int target) {
        if (target < 0) {
            throw new IllegalArgumentException("筹码余额不能为负数: " + target);
        }
        PlayerHandle player = requirePlayer(playerId);
        ItemStack template = template();
        InventorySnapshot snapshot = InventorySnapshot.capture(player);
        int current = checkedInt(snapshot.amount(template), "筹码余额");
        long difference = (long) target - current;
        if (difference == 0) {
            return current;
        }
        InventoryPlan plan = difference > 0
            ? snapshot.planAdd(template, checkedInt(difference, "筹码增加量"))
            : snapshot.planRemove(template, checkedInt(-difference, "筹码扣除量"));
        applyAtomically(List.of(plan));
        return target;
    }

    /** 按增量调整余额；正数发放，负数扣除。 */
    public int adjustBalance(UUID playerId, int delta) {
        PlayerHandle player = requirePlayer(playerId);
        ItemStack template = template();
        InventorySnapshot snapshot = InventorySnapshot.capture(player);
        long target = (long) snapshot.amount(template) + delta;
        if (target < 0) {
            throw new IllegalStateException("筹码余额不足: " + delta);
        }
        if (target > Integer.MAX_VALUE) {
            throw new IllegalArgumentException("筹码余额溢出: " + target);
        }
        if (delta == 0) {
            return checkedInt(target, "目标筹码余额");
        }
        InventoryPlan plan = delta > 0
            ? snapshot.planAdd(template, delta)
            : snapshot.planRemove(template, -delta);
        applyAtomically(List.of(plan));
        return checkedInt(target, "目标筹码余额");
    }

    /**
     * 原子批量转移筹码。deltas 为「玩家 UUID → 余额增量」，正数收款、负数付款；总和必须为零。
     * 返回成功后的各参与玩家余额快照。
     */
    public Map<UUID, Integer> transfer(Map<UUID, Integer> deltas) {
        requirePrimaryThread();
        Objects.requireNonNull(deltas, "deltas");
        if (deltas.isEmpty()) {
            return Map.of();
        }

        long total = 0L;
        Map<UUID, PlayerHandle> players = new LinkedHashMap<>();
        Map<UUID, InventoryPlan> debitPlans = new LinkedHashMap<>();
        Map<UUID, InventoryPlan> creditPlans = new LinkedHashMap<>();
        Map<UUID, Integer> result = new LinkedHashMap<>();
        ItemStack template = template();

        for (Map.Entry<UUID, Integer> entry : deltas.entrySet()) {
            UUID playerId = Objects.requireNonNull(entry.getKey(), "deltas 中不能有空 UUID");
            Integer deltaValue = Objects.requireNonNull(entry.getValue(), "deltas 中不能有空增量");
            total += deltaValue;
            PlayerHandle player = requirePlayer(playerId);
            players.put(playerId, player);
        }
        if (total != 0L) {
            throw new IllegalArgumentException("筹码转移总和必须为零: " + total);
        }

        for (Map.Entry<UUID, Integer> entry : deltas.entrySet()) {
            UUID playerId = entry.getKey();
            int delta = entry.getValue();
            InventorySnapshot snapshot = InventorySnapshot.capture(players.get(playerId));
            int current = checkedInt(snapshot.amount(template), "筹码余额");
            long target = (long) current + delta;
            if (target < 0) {
                throw new IllegalStateException("玩家筹码余额不足: " + playerId);
            }
            if (target > Integer.MAX_VALUE) {
                throw new IllegalArgumentException("玩家筹码余额溢出: " + playerId);
            }
            InventoryPlan plan = delta > 0
                ? snapshot.planAdd(template, delta)
                : delta < 0
                    ? snapshot.planRemove(template, -delta)
                    : InventoryPlan.empty(snapshot);
            if (delta < 0) {
                debitPlans.put(playerId, plan);
            } else if (delta > 0) {
                creditPlans.put(playerId, plan);
            }
            result.put(playerId, checkedInt(target, "目标筹码余额"));
        }

        List<InventoryPlan> orderedPlans = new ArrayList<>(debitPlans.values());
        orderedPlans.addAll(creditPlans.values());
        applyAtomically(orderedPlans);
        return Collections.unmodifiableMap(result);
    }

    private PlayerHandle requirePlayer(UUID playerId) {
        requirePrimaryThread();
        Objects.requireNonNull(playerId, "playerId");
        PlayerHandle player = runtime.player(playerId);
        if (player == null || !player.online()) {
            throw new IllegalStateException("玩家必须在线: " + playerId);
        }
        return player;
    }

    private void requirePrimaryThread() {
        if (!runtime.isPrimaryThread()) {
            throw new IllegalStateException("PhysicalChipService 只能在主线程调用");
        }
    }

    private ItemStack template() {
        ItemStack supplied = templateSupplier.get();
        if (supplied == null || supplied.getType() == org.bukkit.Material.AIR) {
            throw new IllegalStateException("筹码模板必须是非空物品");
        }
        ItemStack copy = supplied.clone();
        copy.setAmount(1);
        return copy;
    }

    private void applyAtomically(List<InventoryPlan> plans) {
        List<AppliedChange> applied = new ArrayList<>();
        try {
            for (InventoryPlan plan : plans) {
                for (SlotChange change : plan.changes()) {
                    // 先登记再写入：即使底层 setItem 在已写入后抛错，也能恢复该槽位。
                    applied.add(new AppliedChange(change));
                    change.apply();
                }
            }
        } catch (RuntimeException failure) {
            logger.log(Level.SEVERE, "实体筹码事务应用失败，正在回滚库存变更", failure);
            RuntimeException rollbackFailure = null;
            for (int index = applied.size() - 1; index >= 0; index--) {
                try {
                    applied.get(index).change().restore();
                } catch (RuntimeException rollbackError) {
                    logger.log(Level.SEVERE, "实体筹码事务回滚失败", rollbackError);
                    if (rollbackFailure == null) {
                        rollbackFailure = rollbackError;
                    }
                }
            }
            if (rollbackFailure != null) {
                failure.addSuppressed(rollbackFailure);
                throw new IllegalStateException("实体筹码事务应用失败，回滚失败，库存需核查", failure);
            }
            throw new IllegalStateException("实体筹码事务未完成，库存已回滚", failure);
        }
    }

    private static int checkedInt(long value, String description) {
        if (value < 0 || value > Integer.MAX_VALUE) {
            throw new IllegalArgumentException(description + "超出 int 范围: " + value);
        }
        return (int) value;
    }

    /** 生产环境适配器；所有 Bukkit 访问集中在此处，测试可替换整个接口。 */
    public interface RuntimeAccess {
        boolean isPrimaryThread();

        PlayerHandle player(UUID playerId);

        static RuntimeAccess bukkit() {
            return new RuntimeAccess() {
                @Override
                public boolean isPrimaryThread() {
                    return Bukkit.isPrimaryThread();
                }

                @Override
                public PlayerHandle player(UUID playerId) {
                    Player player = Bukkit.getPlayer(playerId);
                    return player == null ? null : new BukkitPlayerHandle(player);
                }
            };
        }
    }

    /** 供测试注入的最小在线玩家/背包访问面。 */
    public interface PlayerHandle {
        boolean online();

        ItemStack storageItem(int slot);

        void setStorageItem(int slot, ItemStack item);

        ItemStack offhandItem();

        void setOffhandItem(ItemStack item);
    }

    private static final class BukkitPlayerHandle implements PlayerHandle {
        private final Player player;

        private BukkitPlayerHandle(Player player) {
            this.player = player;
        }

        @Override
        public boolean online() {
            return player.isOnline();
        }

        @Override
        public ItemStack storageItem(int slot) {
            return player.getInventory().getItem(slot);
        }

        @Override
        public void setStorageItem(int slot, ItemStack item) {
            player.getInventory().setItem(slot, item);
        }

        @Override
        public ItemStack offhandItem() {
            return player.getInventory().getItemInOffHand();
        }

        @Override
        public void setOffhandItem(ItemStack item) {
            player.getInventory().setItemInOffHand(item);
        }
    }

    private record SlotRef(PlayerHandle player, int slot, boolean offhand) {
        ItemStack read() {
            return offhand ? player.offhandItem() : player.storageItem(slot);
        }

        void write(ItemStack item) {
            if (offhand) {
                player.setOffhandItem(item);
            } else {
                player.setStorageItem(slot, item);
            }
        }
    }

    private record SlotChange(SlotRef slot, ItemStack before, ItemStack after) {
        void apply() {
            slot.write(copy(after));
        }

        void restore() {
            slot.write(copy(before));
        }
    }

    private record AppliedChange(SlotChange change) {
    }

    private record InventoryPlan(List<SlotChange> changes) {
        static InventoryPlan empty(InventorySnapshot snapshot) {
            return new InventoryPlan(List.of());
        }
    }

    private static final class InventorySnapshot {
        private final PlayerHandle player;
        private final ItemStack[] storage;
        private final ItemStack offhand;

        private InventorySnapshot(PlayerHandle player, ItemStack[] storage, ItemStack offhand) {
            this.player = player;
            this.storage = storage;
            this.offhand = offhand;
        }

        static InventorySnapshot capture(PlayerHandle player) {
            ItemStack[] storage = new ItemStack[STORAGE_SIZE];
            for (int slot = 0; slot < STORAGE_SIZE; slot++) {
                storage[slot] = copy(player.storageItem(slot));
            }
            return new InventorySnapshot(player, storage, copy(player.offhandItem()));
        }

        int amount(ItemStack template) {
            long total = 0L;
            for (ItemStack item : storage) {
                if (similar(item, template)) {
                    total += item.getAmount();
                }
            }
            if (similar(offhand, template)) {
                total += offhand.getAmount();
            }
            return checkedInt(total, "筹码数量");
        }

        InventoryPlan planAdd(ItemStack template, int amount) {
            if (amount < 0) {
                throw new IllegalArgumentException("增加量不能为负数");
            }
            int max = template.getMaxStackSize();
            long capacity = 0L;
            for (ItemStack item : storage) {
                if (similar(item, template)) {
                    capacity += Math.max(0, max - item.getAmount());
                } else if (empty(item)) {
                    capacity += max;
                }
            }
            if (capacity < amount) {
                throw new IllegalStateException("筹码空间不足: 需要 " + amount + "，可用 " + capacity);
            }

            ItemStack[] next = copy(storage);
            int remaining = amount;
            for (int slot = 0; slot < STORAGE_SIZE && remaining > 0; slot++) {
                ItemStack item = next[slot];
                if (similar(item, template)) {
                    int add = Math.min(remaining, max - item.getAmount());
                    if (add > 0) {
                        ItemStack updated = item.clone();
                        updated.setAmount(item.getAmount() + add);
                        next[slot] = updated;
                        remaining -= add;
                    }
                }
            }
            for (int slot = 0; slot < STORAGE_SIZE && remaining > 0; slot++) {
                if (empty(next[slot])) {
                    int add = Math.min(remaining, max);
                    ItemStack updated = template.clone();
                    updated.setAmount(add);
                    next[slot] = updated;
                    remaining -= add;
                }
            }
            return changes(next);
        }

        InventoryPlan planRemove(ItemStack template, int amount) {
            if (amount < 0) {
                throw new IllegalArgumentException("扣除量不能为负数");
            }
            if (amount(template) < amount) {
                throw new IllegalStateException("筹码余额不足: 需要 " + amount);
            }
            ItemStack[] next = copy(storage);
            int remaining = amount;
            for (int slot = 0; slot < STORAGE_SIZE && remaining > 0; slot++) {
                if (similar(next[slot], template)) {
                    int remove = Math.min(remaining, next[slot].getAmount());
                    ItemStack updated = next[slot].clone();
                    updated.setAmount(updated.getAmount() - remove);
                    next[slot] = empty(updated) ? null : updated;
                    remaining -= remove;
                }
            }
            if (remaining > 0 && similar(offhand, template)) {
                int remove = Math.min(remaining, offhand.getAmount());
                ItemStack updated = offhand.clone();
                updated.setAmount(updated.getAmount() - remove);
                // 副手只在扣除计划中修改，发放永远不占副手。
                return changes(next, empty(updated) ? null : updated);
            }
            return changes(next, offhand);
        }

        private InventoryPlan changes(ItemStack[] next) {
            return changes(next, offhand);
        }

        private InventoryPlan changes(ItemStack[] next, ItemStack nextOffhand) {
            List<SlotChange> changes = new ArrayList<>();
            for (int slot = 0; slot < STORAGE_SIZE; slot++) {
                if (!same(storage[slot], next[slot])) {
                    changes.add(new SlotChange(new SlotRef(player, slot, false), storage[slot], next[slot]));
                }
            }
            if (!same(offhand, nextOffhand)) {
                changes.add(new SlotChange(new SlotRef(player, 0, true), offhand, nextOffhand));
            }
            return new InventoryPlan(List.copyOf(changes));
        }
    }

    private static boolean similar(ItemStack left, ItemStack right) {
        return left != null && right != null && left.getType() != org.bukkit.Material.AIR
            && left.isSimilar(right);
    }

    private static boolean empty(ItemStack item) {
        return item == null || item.getType() == org.bukkit.Material.AIR || item.getAmount() <= 0;
    }

    private static boolean same(ItemStack left, ItemStack right) {
        if (empty(left) && empty(right)) {
            return true;
        }
        return Objects.equals(left, right);
    }

    private static ItemStack copy(ItemStack item) {
        return item == null ? null : item.clone();
    }

    private static ItemStack[] copy(ItemStack[] items) {
        ItemStack[] copy = new ItemStack[items.length];
        for (int index = 0; index < items.length; index++) {
            copy[index] = copy(items[index]);
        }
        return copy;
    }
}
