package linmumua.doudizhu.world;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * 手牌两端边缘瓦片的覆盖不变量。
 *
 * <h2>这些用例在锁什么业务事实</h2>
 *
 * <p>手牌点击的事件入口是每张牌挂的 Interaction 捕获器（手牌本身是 ItemDisplay，
 * 空手右键空气时 Paper 压根不触发 PlayerInteractEvent）。捕获器宽度必须严格等于
 * 铺牌步长，因此 N 个捕获器的并集<b>比拾取包络窄</b>，两端各缺一条 ——
 * 那两条上玩家看得见牌面却点不动。
 *
 * <p>缺口只在两端：中间的牌 i 其可见条被牌 i−1 的捕获器盖住（错位但连续），
 * 唯独最两端没有邻居补位。所以这里锁的核心是<b>补齐后并集恰好等于包络跨度</b>。
 */
@DisplayName("手牌边缘瓦片：补齐两端点不到的缺口")
class HandCardCapturerEdgeCoverageTest {

    /** render.hand-spacing 默认值，同时也是铺牌步长与捕获器宽度。 */
    private static final double HAND_SPACING = 0.1;

    /**
     * 默认配置下的拾取包络半宽。
     *
     * <p>由 render.private-card-scale 0.8 / DEFAULT_PRIVATE_CARD_RENDER_SCALE 0.5 = 1.6，
     * 乘 render.private-card-size.width 0.5，再乘 render.card-hover.scale 1.08，
     * 最后乘 MODEL_WIDTH/2 得出。这里写成字面量是为了让配置漂移时本用例先红 ——
     * 数值一旦变了，说明缺口大小变了，必须重新核对瓦片尺寸。
     */
    private static final double ENVELOPE_HALF_WIDTH = 0.1215;

    /**
     * 缺口必须是正的，而且正好是「包络半宽 − 捕获器半宽」。
     *
     * <p>失败条件：把 edgeTileWidth 写成常量、返回 0、或改成按包络<b>全宽</b>算。
     * 返回 0 意味着瓦片退化成零宽 Interaction，压根点不到，等于这次修复没做。
     */
    @Test
    @DisplayName("瓦片宽度 = 包络半宽 − 捕获器半宽，默认配置下为 0.0715 格")
    void edgeTileWidthFillsExactlyTheUncoveredStrip() {
        double tile = HandCardPickGeometry.edgeTileWidth(ENVELOPE_HALF_WIDTH, HAND_SPACING);
        assertEquals(0.0715, tile, 1.0e-9,
            "边缘瓦片宽度不再等于两端缺口：末张牌完整露出的牌面有 29.4% 点不动，"
                + "而斗地主每局必然经过「手上 1 张、必须点它出牌」这个状态");
        assertTrue(tile > 0.0,
            "瓦片宽度退化成 0：零宽 Interaction 压根点不到，两端缺口依然存在");
    }

    /**
     * 这是本次修复的<b>目标不变量</b>：补齐后捕获器并集恰好等于拾取包络跨度。
     *
     * <p>并集左沿 = P₀ − capHalf − offset − tile/2，右沿对称。
     * 与包络跨度 [P₀ − envHalf, P_{N−1} + envHalf] 逐端比对。
     *
     * <p>失败条件：瓦片宽度或中心偏移任一算错。偏移算小了会与端点牌的捕获器重叠、
     * 外沿仍然缺；算大了会在包络外造出「点得到事件但求交判不中」的浪费区。
     */
    @Test
    @DisplayName("补齐后：捕获器并集外沿 == 拾取包络外沿，零重叠零超出")
    void unionOfCapturersAndTilesEqualsPickEnvelopeSpan() {
        double capturerWidth = PhysicalTableManager.handCardCapturerWidth(HAND_SPACING);
        double tile = HandCardPickGeometry.edgeTileWidth(ENVELOPE_HALF_WIDTH, capturerWidth);
        double offset = HandCardPickGeometry.edgeTileCenterOffset(capturerWidth, tile);

        // 以端点牌中心为原点。捕获器覆盖到 capHalf，瓦片再往外接一段。
        double capturerOuterEdge = capturerWidth * 0.5;
        double tileInnerEdge = offset - tile * 0.5;
        double tileOuterEdge = offset + tile * 0.5;

        assertEquals(capturerOuterEdge, tileInnerEdge, 1.0e-9,
            "瓦片内沿没有与端点牌捕获器的外沿严丝合缝：留缝则缺口没补全，"
                + "重叠则白占一块判定区");
        assertEquals(ENVELOPE_HALF_WIDTH, tileOuterEdge, 1.0e-9,
            "瓦片外沿没有落在拾取包络边界上：短了则边缘仍点不到，"
                + "长了则在包络外造出点得到事件却求交判不中的浪费区");
    }

    /**
     * hand-spacing 调得比牌面还宽时不该生成瓦片。
     *
     * <p>此时捕获器半宽已经超过包络半宽，缺口是负的，并集本来就盖满了包络。
     * 再塞瓦片就是在包络外凭空造判定区。
     *
     * <p>失败条件：用 Math.abs 或不做符号判断。
     */
    @Test
    @DisplayName("间距大于牌面时缺口为负，退化成不生成瓦片")
    void noTileWhenSpacingAlreadyCoversTheEnvelope() {
        double wideSpacing = 0.5;
        double tile = HandCardPickGeometry.edgeTileWidth(ENVELOPE_HALF_WIDTH, wideSpacing);
        assertEquals(0.0, tile, 1.0e-12,
            "间距已经盖满包络还生成瓦片：会在包络之外造出点得到事件却判不中的区域");
        assertEquals(0.0, HandCardPickGeometry.edgeTileCenterOffset(wideSpacing, tile), 1.0e-12,
            "瓦片宽度为 0 时偏移量必须也是 0，否则会摆出一个零宽实体占位");
    }

    /**
     * 瓦片必须比现有捕获器<b>更窄</b>。
     *
     * <p>Interaction 横截面是正方形，宽度同时就是深度。瓦片一旦比现有捕获器宽，
     * 它在深度方向的鼓出就会超过现有捕获器，
     * {@code warnIfCapturerCouldOccludeButtons} 按 {@code handCardCapturerWidth * 0.5}
     * 推出来的「手牌盒最外面那一面」就不再是最外面的，按钮遮挡余量的推导直接失效。
     *
     * <p>失败条件：给瓦片加边距或倍率。这也是「既有 HandCardCapturerGeometryTest
     * 一条断言都不用改」的依据之一。
     */
    @Test
    @DisplayName("瓦片比现有捕获器更窄，按钮遮挡余量只增不减")
    void edgeTileIsNarrowerThanExistingCapturerSoButtonMarginOnlyGrows() {
        double capturerWidth = PhysicalTableManager.handCardCapturerWidth(HAND_SPACING);
        double tile = HandCardPickGeometry.edgeTileWidth(ENVELOPE_HALF_WIDTH, capturerWidth);
        // 双侧夹逼：只写 tile < capturerWidth 的话，瓦片退化成 0 也能满足，
        // 这条用例就成了「实现坏掉也变绿」的哑弹（Rule 9）。
        assertTrue(tile > 0.0 && tile < capturerWidth,
            "边缘瓦片宽度必须落在 (0, 捕获器宽) 区间内，实际 " + tile + "："
                + "为 0 则点不到、缺口没补；比现有捕获器还宽则深度方向鼓出更多，"
                + "warnIfCapturerCouldOccludeButtons 对「手牌盒最外面那一面」的推导会失效，"
                + "按钮点击可能被瓦片抢走射线而静默丢弃");
    }
}
