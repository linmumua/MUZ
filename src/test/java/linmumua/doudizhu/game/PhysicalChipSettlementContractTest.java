package linmumua.doudizhu.game;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import linmumua.doudizhu.DoudizhuPlugin;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

/** 锁定实体筹码对局结算的批量、精确整数与失败回滚语义。 */
class PhysicalChipSettlementContractTest {
    private static final Path PLUGIN = Path.of("src/main/java/linmumua/doudizhu/DoudizhuPlugin.java");
    private static final Path COORDINATOR = Path.of("src/main/java/linmumua/doudizhu/game/RoundSettlementCoordinator.java");
    private static final Path TABLE = Path.of("src/main/java/linmumua/doudizhu/game/GameTable.java");

    @Test
    void pluginUsesPhysicalServiceAndStopsVirtualBalancePersistence() throws IOException {
        String source = Files.readString(PLUGIN);
        assertTrue(source.contains("new PhysicalChipService(this::chipPaymentItem, getLogger())"));
        assertTrue(source.contains("physicalChipService.balance(playerId)"));
        assertTrue(source.contains("physicalChipService.setBalance(playerId, amount)"));
        assertTrue(source.contains("physicalChipService.adjustBalance(playerId, delta)"));
        assertFalse(source.contains("playerChipBalances"), "旧虚拟余额映射不得继续存在");
        assertFalse(source.contains("chip-balance"), "旧 chip-balance 键不得再被插件读写或删除");
    }

    @Test
    void chipSettlementUsesExactIntegerConversionAndOneBatchTransfer() throws IOException {
        String plugin = Files.readString(PLUGIN);
        String coordinator = Files.readString(COORDINATOR);
        assertTrue(plugin.contains("intValueExact()"), "实体筹码金额必须拒绝小数与 int 溢出");
        assertTrue(plugin.contains("physicalChipService.transfer(chipDeltas)"), "筹码结算必须走单次批量转移");
        assertTrue(coordinator.contains("return settlePhysicalChips(scoreDeltas, plugin);"));
        assertFalse(coordinator.contains("settleDoudizhuCurrency(support.roomLevel(), playerId, scoreDelta);\n                } catch"),
            "筹码模式不得退回逐人 Vault 式结算");
    }

    @Test
    void failedChipSettlementResultIsNotASettledZeroSnapshot() {
        DoudizhuPlugin.SettlementResult failed = new DoudizhuPlugin.SettlementResult(
            0.0,
            0.0,
            0.0,
            false,
            false,
            "筹码",
            DoudizhuPlugin.SettlementStatus.FAILED
        );
        DoudizhuPlugin.SettlementResult unavailable = new DoudizhuPlugin.SettlementResult(
            0.0,
            0.0,
            0.0,
            false,
            false,
            "筹码",
            DoudizhuPlugin.SettlementStatus.UNAVAILABLE
        );
        assertEquals(DoudizhuPlugin.SettlementStatus.FAILED, failed.status());
        assertEquals(DoudizhuPlugin.SettlementStatus.UNAVAILABLE, unavailable.status());
        assertFalse(failed.hasCurrencySnapshot(), "失败快照不能被当成成功货币余额");
        assertFalse(unavailable.hasCurrencySnapshot(), "不可查询快照不能被当成成功货币余额");
    }

    @Test
    void failedChipSettlementReportsIncompleteStatusWithoutFalseZeroChangeClaim() throws IOException {
        String source = Files.readString(COORDINATOR);
        assertTrue(source.contains("实体筹码整局结算未完成，请核查日志与库存"));
        assertFalse(source.contains("已回滚且未产生任何筹码变更"),
            "结算协调器不得在底层回滚结果未知时承诺库存零变更");
        assertTrue(source.contains("plugin.failedChipSettlement()"),
            "批量失败必须使用 FAILED 状态快照，不得伪造已收付 delta 或欠账");
        assertTrue(source.contains("support.notifySettlementFailure(message"), "批量失败必须显式通知在线玩家");
    }

    @Test
    void pluginReturnsPhysicalServiceMutationResultsDirectly() throws IOException {
        String source = Files.readString(PLUGIN);
        assertTrue(source.contains("return physicalChipService.setBalance(playerId, amount);"));
        assertTrue(source.contains("return physicalChipService.adjustBalance(playerId, delta);"));
        assertFalse(source.contains("physicalChipService.setBalance(playerId, amount);\n        return physicalChipService.balance(playerId);"));
        assertFalse(source.contains("physicalChipService.adjustBalance(playerId, delta);\n        return physicalChipService.balance(playerId);"));
    }

    @Test
    void paidChipRoomsRejectBotsAndFinishRoundIsIdempotent() throws IOException {
        String table = Files.readString(TABLE);
        assertTrue(table.contains("plugin.isChipPaymentEnabled()"));
        assertTrue(table.contains("seats.stream().anyMatch(this::isBot)"), "实体筹码付费房必须拒绝机器人");
        assertTrue(table.contains("if (roundSettlementInProgress || phase != GamePhase.PLAYING)"), "重复结算必须被拒绝");
        assertTrue(table.contains("roundSettlementInProgress = false;"), "回大厅/强制关桌必须重置结算保护");
    }

    @Test
    void activeRoundEconomyAndRoomSettingsAreProtected() throws IOException {
        String table = Files.readString(TABLE);
        String plugin = Files.readString(PLUGIN);
        assertTrue(table.contains("roundEconomyFingerprint = plugin.economyFingerprint(roomLevel)"),
            "开局必须记录经济指纹");
        assertTrue(table.contains("economyFingerprintMatches()"),
            "结算前必须校验经济指纹");
        assertTrue(table.contains("只有大厅阶段才能修改房间等级"),
            "活动局不得修改房间等级");
        assertTrue(plugin.contains("ensureEconomyMutationAllowed(\"切换筹码支付模式\")"));
        assertTrue(plugin.contains("ensureEconomyMutationAllowed(\"修改实体筹码模板\")"));
        assertTrue(plugin.contains("ensureEconomyMutationAllowed(\"修改房间倍率\")"));
        assertTrue(plugin.contains("ensureEconomyMutationAllowed(\"切换房间经济\")"));
    }

    @Test
    void failedSettlementNeverFallsBackToTieDisplay() throws IOException {
        String table = Files.readString(TABLE);
        assertTrue(table.contains("SettlementStatus.FAILED"), "失败状态必须进入聊天展示分支");
        assertTrue(table.contains("实体筹码结算未完成，请核查日志与库存"),
            "筹码结算失败不得显示持平或余额 0");
        assertTrue(table.contains("SettlementStatus.UNAVAILABLE"), "不可用状态必须显式展示");
    }
}
