package linmumua.doudizhu.game;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.logging.Handler;
import java.util.logging.Level;
import java.util.logging.LogRecord;
import java.util.logging.Logger;
import org.bukkit.Material;
import org.bukkit.inventory.ItemStack;
import org.junit.jupiter.api.Test;

/** 实体物品筹码服务的原子性、槽位边界与模板相似性测试。 */
class PhysicalChipServiceTest {
    private static final Logger LOGGER = Logger.getLogger("PhysicalChipServiceTest");

    @Test
    void templateMetaDistinguishesChipStacksAndIgnoresTemplateAmount() {
        TestRuntime runtime = new TestRuntime();
        UUID playerId = runtime.addPlayer();
        TestPlayer player = runtime.testPlayer(playerId);
        player.storage[0] = chip(3, "red");
        player.storage[1] = chip(4, "blue");
        player.storage[2] = chip(5, "red");
        player.storage[3] = new TestItem(Material.PAPER, 9, "different");

        PhysicalChipService service = service(runtime, () -> chip(64, "red"));

        assertEquals(8, service.balance(playerId));
    }

    @Test
    void balanceCountsStorageAndOffhandButNotArmorOrCursor() {
        TestRuntime runtime = new TestRuntime();
        UUID playerId = runtime.addPlayer();
        TestPlayer player = runtime.testPlayer(playerId);
        player.storage[0] = chip(3, "red");
        player.offhand = chip(2, "red");
        player.armor = chip(100, "red");
        player.cursor = chip(100, "red");

        PhysicalChipService service = service(runtime, () -> chip(1, "red"));

        assertEquals(5, service.balance(playerId));
    }

    @Test
    void adjustAddsByStackingThenStorageOnlyAndRemovesStorageThenOffhand() {
        TestRuntime runtime = new TestRuntime();
        UUID playerId = runtime.addPlayer();
        TestPlayer player = runtime.testPlayer(playerId);
        player.storage[0] = chip(60, "red");
        player.storage[1] = new TestItem(Material.STONE, 1, "unrelated");
        player.offhand = chip(4, "red");

        PhysicalChipService service = service(runtime, () -> chip(64, "red"));
        assertEquals(74, service.adjustBalance(playerId, 10));
        assertEquals(64, player.storage[0].getAmount());
        assertEquals(6, player.storage[2].getAmount());
        assertEquals(4, player.offhand.getAmount(), "发放不能占用副手");
        assertEquals(74, service.balance(playerId));

        service.adjustBalance(playerId, -8);
        assertEquals(56, player.storage[0].getAmount());
        assertEquals(6, player.storage[2].getAmount());
        assertEquals(4, player.offhand.getAmount(), "有主背包筹码时先扣主背包");

        service.adjustBalance(playerId, -66);
        assertEquals(0, service.balance(playerId));
        assertTrue(player.offhand == null || player.offhand.getAmount() == 0);
    }

    @Test
    void setBalanceRejectsNegativeAndInsufficientSpaceWithoutChangingAnything() {
        TestRuntime runtime = new TestRuntime();
        UUID playerId = runtime.addPlayer();
        TestPlayer player = runtime.testPlayer(playerId);
        for (int slot = 0; slot < player.storage.length; slot++) {
            player.storage[slot] = new TestItem(Material.STONE, 64, "other-" + slot);
        }
        ItemStack[] before = player.copyStorage();
        PhysicalChipService service = service(runtime, () -> chip(1, "red"));

        assertThrows(IllegalArgumentException.class, () -> service.setBalance(playerId, -1));
        assertThrows(IllegalStateException.class, () -> service.setBalance(playerId, 1));
        assertStorageEquals(before, player.storage);
    }

    @Test
    void insufficientBalanceAndOverflowAreRejectedWithoutMutation() {
        TestRuntime runtime = new TestRuntime();
        UUID playerId = runtime.addPlayer();
        TestPlayer player = runtime.testPlayer(playerId);
        player.storage[0] = chip(2, "red");
        ItemStack[] before = player.copyStorage();
        PhysicalChipService service = service(runtime, () -> chip(1, "red"));

        assertThrows(IllegalStateException.class, () -> service.adjustBalance(playerId, -3));
        assertStorageEquals(before, player.storage);

        player.storage[0] = chip(Integer.MAX_VALUE, "red");
        ItemStack[] overflowBefore = player.copyStorage();
        assertThrows(IllegalArgumentException.class, () -> service.adjustBalance(playerId, 1));
        assertStorageEquals(overflowBefore, player.storage);
    }

    @Test
    void offlineAndAsyncCallsFailClearly() {
        TestRuntime runtime = new TestRuntime();
        UUID playerId = runtime.addPlayer();
        runtime.testPlayer(playerId).online = false;
        PhysicalChipService service = service(runtime, () -> chip(1, "red"));

        assertThrows(IllegalStateException.class, () -> service.balance(playerId));
        runtime.testPlayer(playerId).online = true;
        runtime.primary = false;
        assertThrows(IllegalStateException.class, () -> service.balance(playerId));
    }

    /**
     * 区域化核心上「是 tick 线程」不等于「持有这个玩家的 region」。
     *
     * <p>测试端内核（Folia/Leaf 26.1.2）把 {@code Bukkit.isPrimaryThread()} 实现为
     * {@code TickThread.isTickThread()}，即 region 线程与 global 线程都为 true，因此
     * {@code runtime.primary = true} 正是「区域线程」的真实取值，不是虚构场景。此时若只查主线程判定，
     * 别的 region 的线程会通过校验并直接改写这个玩家的背包，而背包写入没有任何服务端归属门禁——
     * 也就是说旧行为是**静默**跨 region 写入。本用例锁住「必须按玩家 region 归属拒绝」。
     */
    @Test
    void regionizedTickThreadThatDoesNotOwnThePlayerIsRefusedWithoutMutation() {
        TestRuntime runtime = new TestRuntime();
        UUID playerId = runtime.addPlayer();
        TestPlayer player = runtime.testPlayer(playerId);
        player.storage[0] = chip(5, "red");
        player.ownedByCurrentRegion = false;
        ItemStack[] before = player.copyStorage();
        PhysicalChipService service = service(runtime, () -> chip(1, "red"));

        assertThrows(IllegalStateException.class, () -> service.balance(playerId));
        assertThrows(IllegalStateException.class, () -> service.setBalance(playerId, 9));
        assertThrows(IllegalStateException.class, () -> service.adjustBalance(playerId, -2));
        assertStorageEquals(before, player.storage);
    }

    /**
     * 归属必须逐人校验：一次批量转移会写多个玩家的背包，只要有一个人不在本 lane 的 region 内，
     * 整批都必须在**任何写入之前**被拒绝——否则会出现「先扣了付款方、收款方写不进去」的半应用状态，
     * 而零和批量转移的全部意义正是不会出现这种状态。
     */
    @Test
    void batchTransferRefusesWhenAnyParticipantIsNotOwnedBeforeTouchingAnyInventory() {
        TestRuntime runtime = new TestRuntime();
        UUID payerId = runtime.addPlayer();
        UUID receiverId = runtime.addPlayer();
        TestPlayer payer = runtime.testPlayer(payerId);
        TestPlayer receiver = runtime.testPlayer(receiverId);
        payer.storage[0] = chip(10, "red");
        receiver.ownedByCurrentRegion = false;
        ItemStack[] payerBefore = payer.copyStorage();
        ItemStack[] receiverBefore = receiver.copyStorage();
        PhysicalChipService service = service(runtime, () -> chip(1, "red"));

        Map<UUID, Integer> deltas = new LinkedHashMap<>();
        deltas.put(payerId, -4);
        deltas.put(receiverId, 4);

        assertThrows(IllegalStateException.class, () -> service.transfer(deltas));
        assertStorageEquals(payerBefore, payer.storage);
        assertStorageEquals(receiverBefore, receiver.storage);
        assertTrue(runtime.writeOrder.isEmpty(), "归属预检失败时不得写入任何玩家的背包");
    }

    /**
     * 生产装配必须真的做 region 归属判定。
     *
     * <p>{@code RuntimeAccess.bukkit()} 里把归属判定写成恒定 true（或只留主线程判定）会让上面的行为测试
     * 全绿、却在实服上失去保护，所以这里把生产适配器必须调用的 Bukkit API 钉死。
     *
     * <p><b>必须先剥注释再断言</b>：本服务的类注释里就写着 {@code Bukkit.isOwnedByCurrentRegion(entity)}
     * 作为依据说明，直接 {@code contains} 会被自己的注释喂饱，从而在实现退化成恒定 true 时仍然变绿。
     */
    @Test
    void productionRuntimeChecksBukkitEntityRegionOwnership() throws IOException {
        String source = stripComments(Files.readString(
            Path.of("src/main/java/linmumua/doudizhu/game/PhysicalChipService.java")));

        assertTrue(source.contains("Bukkit.isOwnedByCurrentRegion("),
            "生产适配器必须用 Bukkit.isOwnedByCurrentRegion 判定玩家实体的 region 归属");
        assertTrue(source.contains("Bukkit.isPrimaryThread()"),
            "非区域化核心上仍必须保留主线程判定，否则异步线程可以改玩家背包");
        assertTrue(source.contains("runtime.isOwnedByCurrentRegion(player)"),
            "库存读写入口必须真的调用归属判定");
    }

    /** 去掉行注释与块注释，避免注释里的字样把源码契约断言带偏（与 FoliaRuntimeLaneContractTest 同法）。 */
    private static String stripComments(String source) {
        StringBuilder out = new StringBuilder(source.length());
        boolean inLine = false;
        boolean inBlock = false;
        for (int i = 0; i < source.length(); i++) {
            char current = source.charAt(i);
            char next = i + 1 < source.length() ? source.charAt(i + 1) : '\0';
            if (inLine) {
                if (current == '\n') {
                    inLine = false;
                    out.append(current);
                }
                continue;
            }
            if (inBlock) {
                if (current == '*' && next == '/') {
                    inBlock = false;
                    i++;
                }
                continue;
            }
            if (current == '/' && next == '/') {
                inLine = true;
                i++;
                continue;
            }
            if (current == '/' && next == '*') {
                inBlock = true;
                i++;
                continue;
            }
            out.append(current);
        }
        return out.toString();
    }

    @Test
    void mutatorsReturnTheNewBalance() {
        TestRuntime runtime = new TestRuntime();
        UUID playerId = runtime.addPlayer();
        runtime.testPlayer(playerId).storage[0] = chip(3, "red");
        PhysicalChipService service = service(runtime, () -> chip(1, "red"));

        assertEquals(8, service.setBalance(playerId, 8));
        assertEquals(5, service.adjustBalance(playerId, -3));
        assertEquals(5, service.balance(playerId));
    }

    @Test
    void transferRequiresZeroSumAndPlansAllPlayersBeforeApplying() {
        TestRuntime runtime = new TestRuntime();
        UUID payerId = runtime.addPlayer();
        UUID receiverId = runtime.addPlayer();
        runtime.testPlayer(payerId).storage[0] = chip(10, "red");
        PhysicalChipService service = service(runtime, () -> chip(1, "red"));

        assertThrows(IllegalArgumentException.class, () -> service.transfer(Map.of(payerId, -3)));
        assertEquals(10, service.balance(payerId));

        Map<UUID, Integer> deltas = new LinkedHashMap<>();
        deltas.put(payerId, -4);
        deltas.put(receiverId, 4);
        Map<UUID, Integer> result = service.transfer(deltas);
        assertEquals(6, result.get(payerId));
        assertEquals(4, result.get(receiverId));
        assertEquals(6, service.balance(payerId));
        assertEquals(4, service.balance(receiverId));
    }

    @Test
    void transferAppliesPaymentsBeforeCreditsRegardlessOfMapOrder() {
        TestRuntime runtime = new TestRuntime();
        UUID payerId = runtime.addPlayer();
        UUID receiverId = runtime.addPlayer();
        runtime.testPlayer(payerId).storage[0] = chip(10, "red");
        PhysicalChipService service = service(runtime, () -> chip(1, "red"));

        Map<UUID, Integer> deltas = new LinkedHashMap<>();
        deltas.put(receiverId, 4);
        deltas.put(payerId, -4);
        service.transfer(deltas);

        assertEquals(List.of("player-0", "player-1"), runtime.writeOrder,
            "批转必须先写付款玩家，再写收款玩家");
    }

    @Test
    void transferFailureRollsBackChangedSlotsAndLeavesUnrelatedSlotsUntouched() {
        RecordingHandler handler = new RecordingHandler();
        Logger logger = Logger.getLogger("PhysicalChipServiceRollbackTest");
        logger.setUseParentHandlers(false);
        logger.addHandler(handler);
        try {
            TestRuntime runtime = new TestRuntime();
            UUID payerId = runtime.addPlayer();
            UUID receiverId = runtime.addPlayer();
            TestPlayer payer = runtime.testPlayer(payerId);
            TestPlayer receiver = runtime.testPlayer(receiverId);
            payer.storage[0] = chip(70, "red");
            payer.storage[1] = new TestItem(Material.STONE, 7, "unrelated");
            receiver.storage[0] = chip(62, "red");
            receiver.storage[1] = chip(60, "red");
            receiver.failOnWrite = 1;
            ItemStack[] payerBefore = payer.copyStorage();
            ItemStack[] receiverBefore = receiver.copyStorage();

            PhysicalChipService service = service(runtime, () -> chip(1, "red"), logger);
            Map<UUID, Integer> deltas = new LinkedHashMap<>();
            deltas.put(payerId, -4);
            deltas.put(receiverId, 4);

            assertThrows(IllegalStateException.class, () -> service.transfer(deltas));
            assertStorageEquals(payerBefore, payer.storage);
            assertStorageEquals(receiverBefore, receiver.storage);
            assertTrue(handler.severe, "应用失败必须记录异常");
        } finally {
            logger.removeHandler(handler);
        }
    }

    @Test
    void transferReportsRollbackFailureAsUncertainInventoryState() {
        RecordingHandler handler = new RecordingHandler();
        Logger logger = Logger.getLogger("PhysicalChipServiceRollbackFailureTest");
        logger.setUseParentHandlers(false);
        logger.addHandler(handler);
        try {
            TestRuntime runtime = new TestRuntime();
            UUID payerId = runtime.addPlayer();
            UUID receiverId = runtime.addPlayer();
            TestPlayer payer = runtime.testPlayer(payerId);
            TestPlayer receiver = runtime.testPlayer(receiverId);
            payer.storage[0] = chip(10, "red");
            payer.failOnWrite = 2;
            payer.failBeforeWrite = true;
            receiver.failOnWrite = 1;

            Map<UUID, Integer> deltas = new LinkedHashMap<>();
            deltas.put(receiverId, 4);
            deltas.put(payerId, -4);
            PhysicalChipService service = service(runtime, () -> chip(1, "red"), logger);

            IllegalStateException failure = assertThrows(
                IllegalStateException.class, () -> service.transfer(deltas));
            assertTrue(failure.getMessage().contains("回滚失败"));
            assertEquals(6, service.balance(payerId), "回滚失败时必须明确保留需核查的实际状态");
            assertTrue(handler.severe, "应用或回滚失败必须记录异常");
        } finally {
            logger.removeHandler(handler);
        }
    }

    @Test
    void transferPreflightFailureDoesNotTouchAnyPlayer() {
        TestRuntime runtime = new TestRuntime();
        UUID payerId = runtime.addPlayer();
        UUID receiverId = runtime.addPlayer();
        runtime.testPlayer(payerId).storage[0] = chip(2, "red");
        for (int slot = 0; slot < 36; slot++) {
            runtime.testPlayer(receiverId).storage[slot] = new TestItem(Material.STONE, 64, "full-" + slot);
        }
        ItemStack[] payerBefore = runtime.testPlayer(payerId).copyStorage();
        ItemStack[] receiverBefore = runtime.testPlayer(receiverId).copyStorage();
        PhysicalChipService service = service(runtime, () -> chip(1, "red"));

        Map<UUID, Integer> deltas = new LinkedHashMap<>();
        deltas.put(payerId, -2);
        deltas.put(receiverId, 2);
        assertThrows(IllegalStateException.class, () -> service.transfer(deltas));
        assertStorageEquals(payerBefore, runtime.testPlayer(payerId).storage);
        assertStorageEquals(receiverBefore, runtime.testPlayer(receiverId).storage);
    }

    private static PhysicalChipService service(TestRuntime runtime, java.util.function.Supplier<ItemStack> template) {
        return service(runtime, template, LOGGER);
    }

    private static PhysicalChipService service(
        TestRuntime runtime, java.util.function.Supplier<ItemStack> template, Logger logger) {
        return new PhysicalChipService(template, logger, runtime);
    }

    private static TestItem chip(int amount, String meta) {
        return new TestItem(Material.PAPER, amount, meta);
    }

    private static void assertStorageEquals(ItemStack[] expected, ItemStack[] actual) {
        assertEquals(expected.length, actual.length);
        for (int slot = 0; slot < expected.length; slot++) {
            assertEquals(expected[slot], actual[slot], "槽位 " + slot + " 被意外修改");
        }
    }

    private static final class TestRuntime implements PhysicalChipService.RuntimeAccess {
        private final Map<UUID, TestPlayer> players = new HashMap<>();
        private final List<String> writeOrder = new ArrayList<>();
        private boolean primary = true;

        UUID addPlayer() {
            UUID id = UUID.randomUUID();
            players.put(id, new TestPlayer("player-" + players.size(), writeOrder));
            return id;
        }

        TestPlayer testPlayer(UUID id) {
            return players.get(id);
        }

        @Override
        public boolean isPrimaryThread() {
            return primary;
        }

        @Override
        public boolean isOwnedByCurrentRegion(PhysicalChipService.PlayerHandle player) {
            return ((TestPlayer) player).ownedByCurrentRegion;
        }

        @Override
        public PhysicalChipService.PlayerHandle player(UUID playerId) {
            return players.get(playerId);
        }
    }

    private static final class TestPlayer implements PhysicalChipService.PlayerHandle {
        private final ItemStack[] storage = new ItemStack[36];
        private final String label;
        private final List<String> writeOrder;
        private ItemStack offhand;
        private ItemStack armor;
        private ItemStack cursor;
        private boolean online = true;
        /** 当前线程是否持有该玩家 region；默认 true，只在区域化 lane 用例里改成 false。 */
        private boolean ownedByCurrentRegion = true;
        private int writes;
        private int failOnWrite = -1;
        private boolean failBeforeWrite;

        private TestPlayer(String label, List<String> writeOrder) {
            this.label = label;
            this.writeOrder = writeOrder;
        }

        ItemStack[] copyStorage() {
            ItemStack[] result = new ItemStack[storage.length];
            for (int slot = 0; slot < storage.length; slot++) {
                result[slot] = storage[slot] == null ? null : storage[slot].clone();
            }
            return result;
        }

        @Override
        public boolean online() {
            return online;
        }

        @Override
        public ItemStack storageItem(int slot) {
            return storage[slot];
        }

        @Override
        public void setStorageItem(int slot, ItemStack item) {
            writeOrder.add(label);
            writes++;
            if (writes == failOnWrite) {
                if (!failBeforeWrite) {
                    storage[slot] = item == null ? null : item.clone();
                }
                throw new IllegalStateException("测试注入库存写入失败");
            }
            storage[slot] = item == null ? null : item.clone();
        }

        @Override
        public ItemStack offhandItem() {
            return offhand;
        }

        @Override
        public void setOffhandItem(ItemStack item) {
            offhand = item == null ? null : item.clone();
        }
    }

    private static final class TestItem extends ItemStack {
        private final Material material;
        private final String meta;
        private int amount;

        private TestItem(Material material, int amount, String meta) {
            this.material = material;
            this.amount = amount;
            this.meta = meta;
        }

        @Override
        public Material getType() {
            return material;
        }

        @Override
        public int getAmount() {
            return amount;
        }

        @Override
        public int getMaxStackSize() {
            return 64;
        }

        @Override
        public void setAmount(int amount) {
            this.amount = amount;
        }

        @Override
        public boolean isSimilar(ItemStack stack) {
            return stack instanceof TestItem other
                && material == other.material
                && meta.equals(other.meta);
        }

        @Override
        public TestItem clone() {
            return new TestItem(material, amount, meta);
        }

        @Override
        public boolean equals(Object other) {
            if (!(other instanceof TestItem item)) {
                return false;
            }
            return material == item.material && amount == item.amount && meta.equals(item.meta);
        }

        @Override
        public int hashCode() {
            return 31 * (31 * material.hashCode() + amount) + meta.hashCode();
        }
    }

    private static final class RecordingHandler extends Handler {
        private boolean severe;

        @Override
        public void publish(LogRecord record) {
            severe |= record.getLevel().intValue() >= Level.SEVERE.intValue();
        }

        @Override
        public void flush() {
        }

        @Override
        public void close() {
        }
    }
}
