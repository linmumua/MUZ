package linmumua.doudizhu.world;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/**
 * 锁定开局牌的默认安全可见性、增量物品更新和正常手牌坐标契约。
 */
class OpeningPresentationSafetyTest {
    private static final Path MANAGER =
        Path.of("src/main/java/linmumua/doudizhu/world/PhysicalTableManager.java");

    @Test
    void updatesOpeningItemOnlyWhenSlotIsNewOrCardChanges() {
        HandDealPresentation.Slot previous = new HandDealPresentation.Slot(0, UUID.randomUUID(), 12);
        assertTrue(HandDealPresentation.itemNeedsUpdate(null, 12));
        assertFalse(HandDealPresentation.itemNeedsUpdate(previous, 12));
        assertTrue(HandDealPresentation.itemNeedsUpdate(previous, 13));
    }

    @Test
    void normalHandOffsetsUseSameLeftToRightCenteredDirectionAsOpeningSlots() {
        assertEquals(-8.0, HandDealPresentation.centeredStartOffset(17));
        assertEquals(8.0, HandDealPresentation.centeredSlotOffset(17, 16));
        for (int index = 0; index < HandDealPresentation.SLOT_COUNT; index++) {
            assertEquals(
                HandDealPresentation.slotOffset(index),
                HandDealPresentation.centeredSlotOffset(17, index)
            );
        }
    }

    @Test
    void openingCardsAreInvisibleBeforePerViewerAuthorization() throws IOException {
        String source = Files.readString(MANAGER);
        int spawn = source.indexOf("private ItemDisplay spawnOpeningCard(");
        assertTrue(spawn >= 0, "找不到开局牌生成方法");
        int callback = source.indexOf("spawned -> {", spawn);
        int visible = source.indexOf("spawned.setVisibleByDefault(false);", callback);
        int item = source.indexOf("spawned.setItemStack(item);", callback);
        assertTrue(visible >= 0 && item >= 0 && visible < item,
            "开局牌必须先关闭默认可见性，再由 applyPrivateVisibility/applyBacksideVisibility 授权");
        assertTrue(source.indexOf("applyPrivateVisibility(playerId, display", spawn) >= 0,
            "正面开局牌必须走牌主授权可见性");
        assertTrue(source.indexOf("applyBacksideVisibility(playerId, display", spawn) >= 0,
            "背面开局牌必须走旁观者授权可见性");
    }

    @Test
    void openingRefreshDoesNotRewriteUnchangedItemsEveryTick() throws IOException {
        String source = Files.readString(MANAGER);
        int render = source.indexOf("private void renderOpeningLayer(");
        int next = source.indexOf("private void replaceOpeningEntityId(", render);
        assertTrue(render >= 0 && next > render, "找不到开局渲染方法边界");
        String body = source.substring(render, next);
        assertTrue(body.contains("HandDealPresentation.itemNeedsUpdate(previous, cardId)"));
        assertTrue(body.contains("if (cardChanged)"));
        assertTrue(body.contains("if (!backside && cardId >= 0 && (created || cardChanged))"));
    }

    @Test
    void handLockHintUsesRoundOpeningLabel() throws IOException {
        String source = Files.readString(MANAGER);
        // 机制变更（非弱化）：提示改走 player lane 的 UUID 形式（hint(UUID, ...)），不再是 Player 形参；
        // 仍然要求使用开局明牌标签而非硬编码文案，下一行的禁止断言不变。
        assertTrue(source.contains("hint(player.getUniqueId(), table.openingRevealLabel(), NamedTextColor.YELLOW);"));
        assertFalse(source.contains("开局发牌与明牌展示完成前不能操作手牌。"));
    }

    @Test
    void normalTransitionAttemptsDisplayMigrationBeforeFallbackRebuild() throws IOException {
        String source = Files.readString(MANAGER);
        int refresh = source.indexOf("private void refreshPrivateHands(");
        int migration = source.indexOf("migrateOpeningPresentation(table, placed);", refresh);
        int clear = source.indexOf("clearOpeningPresentation(table, placed);", refresh);
        assertTrue(refresh >= 0 && migration > refresh, "正常手牌刷新必须先尝试开局实体迁移");
        assertTrue(clear < 0 || clear > migration, "迁移失败回退清理不能先于迁移尝试");
        assertTrue(source.contains("placed.privateVisualsByPlayer().put(playerId, visuals);"));
        assertTrue(source.contains("placed.backsideVisualsByPlayer().put(playerId, backsideVisuals);"));
    }

    @Test
    void openingRotationUsesLocalYOnly() throws IOException {
        String source = Files.readString(MANAGER);
        int transform = source.indexOf("private Transformation openingCardTransformation(");
        int end = source.indexOf("private void configureOpeningAnimation", transform);
        assertTrue(transform >= 0 && end > transform, "找不到开局旋转变换");
        String body = source.substring(transform, end);
        assertTrue(body.contains("new AxisAngle4f((float) Math.toRadians(degrees), 0.0f, 1.0f, 0.0f)"));
        assertFalse(body.contains("Math.toRadians(degrees), 1.0f, 0.0f, 0.0f"));
        assertFalse(body.contains("Math.toRadians(degrees), 0.0f, 0.0f, 1.0f"));
    }
}
