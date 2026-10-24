package linmumua.doudizhu.game;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

/**
 * 椅侧面板判定盒接线的源码契约。
 *
 * <p>为什么需要它：`ChairPanelSurface.interactionBox` 的数值正确性由
 * `ChairPanelSurfaceTest.判定盒必须从显示矩形底边起包住全部三行卡片` 保证，
 * 但那条测试无法阻止「渲染层又把 Interaction 挪回面板中心」。而这个缺陷在实服上
 * 只表现为「下面两行点不动」，既没有异常也没有日志——只能靠源码契约钉住调用关系。
 */
class ChairPanelHitboxWiringContractTest {
    private static final Path DISPLAY =
        Path.of("src/main/java/linmumua/doudizhu/game/ChairPanelDisplayService.java");

    private static String spawnPanelBody(String source) {
        int start = source.indexOf("private PanelView spawnPanel(");
        assertTrue(start >= 0, "找不到 spawnPanel");
        int end = source.indexOf("private TextDisplay spawnQuadAt(", start);
        assertTrue(end > start, "找不到 spawnPanel 的结束边界");
        return source.substring(start, end);
    }

    @Test
    void interactionAnchorComesFromInteractionBoxNotPanelCenter() throws IOException {
        String body = spawnPanelBody(Files.readString(DISPLAY));

        assertTrue(body.contains("ChairPanelSurface.interactionBox("),
            "判定盒必须由 ChairPanelSurface.interactionBox 统一换算（四角求 min/max）");
        assertTrue(body.contains("box.anchorYOffset()"),
            "判定盒实体必须落在显示矩形竖直投影的 minY（Interaction 的 AABB 从底边生长）");
        assertTrue(body.contains("box.coverHeight()"),
            "判定盒高度必须用竖直投影高度，而不是直接拿面板高度");
        assertTrue(body.contains("box.coverWidth()"),
            "判定盒宽度必须覆盖旋转后的水平投影");
        // 反证：不允许再把 Interaction 锚在面板中心并直接给面板高度。
        assertFalse(body.contains("Interaction.class, center,"),
            "判定盒不得再以面板中心为锚点（会让下半块没有判定盒）");
        assertFalse(body.contains("setInteractionHeight((float) height)"),
            "判定盒高度不得直接取面板高度（Interaction 从底边生长，会漏掉下半块）");
    }

    /**
     * 跨 region 投递必须算「已受理」，被调度器拒绝（已取消句柄）才算失败。
     *
     * <p>为什么钉住：{@code renderPanel} 直接把 {@code runOnAnchorRegion} 的返回值当成受理结果。
     * 若投递成功也返回 {@code false}，跨 region 开板会被立即回滚、晚到的创建任务再自拆，
     * 面板永远打不开且只有一条 SEVERE；若被拒绝的投递返回 {@code true}，没有任何回调会到达，
     * 尝试永久挂起（{@code isOpenPending} 一直为真，常驻守护再也不会重试）。
     * 渲染器依赖真实 Bukkit 实体，无法在单测里构造，只能用源码契约锁住这条判据。
     */
    @Test
    void crossRegionDispatchCountsAsAcceptedUnlessSchedulerRejected() throws IOException {
        String source = Files.readString(DISPLAY);
        int start = source.indexOf("private boolean runOnAnchorRegion(");
        assertTrue(start >= 0, "找不到 runOnAnchorRegion");
        int end = source.indexOf("private void runOnAnchorRegionQuietly(", start);
        assertTrue(end > start, "找不到 runOnAnchorRegion 的结束边界");
        String body = source.substring(start, end);

        assertTrue(body.contains("plugin.scheduler().runRegion(anchor, task)"),
            "跨 region 时必须投递到锚点 region");
        assertTrue(body.contains("!handle.isCancelled()"),
            "投递是否受理必须以调度句柄是否被拒（已取消）判定");
        assertFalse(body.matches("(?s).*runRegion\\(anchor, task\\);\\s*return false;.*"),
            "投递成功后不得再返回 false（会把跨 region 开板当成受理失败而回滚）");
    }
}
