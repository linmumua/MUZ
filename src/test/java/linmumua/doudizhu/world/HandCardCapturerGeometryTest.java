package linmumua.doudizhu.world;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import linmumua.doudizhu.world.HandCardPickGeometry.Envelope;
import org.junit.jupiter.api.Test;

/**
 * 手牌点击捕获器的几何：尺寸、底边换算、与按钮判定框的空间关系。
 *
 * <h2>这些用例守的是哪个 bug</h2>
 *
 * <p>手牌是悬浮 ItemDisplay，<b>没有任何实体判定框</b>。玩家空手时客户端右键空气发出的
 * {@code ServerboundUseItemPacket} 在服务端 {@code handleUseItem} 里被
 * {@code ItemStack.isEmpty()} 提前拦掉，而 Paper 的
 * {@code callPlayerInteractEvent(RIGHT_CLICK_AIR)} 就在被跳过的那一段里——
 * 所以空手右键空气时 {@code PlayerInteractEvent} 根本不触发，手牌右键选牌彻底失效
 * （HUD 永远「已选 0 张」）；而悬停发光走服务端每 tick 射线，与点击包无关，仍然正常。
 * 这就是「牌会发光但点不动」这个看似矛盾的现象。修法是给每张自己的手牌挂一个窄的原版
 * {@code Interaction} 当点击捕获器。
 *
 * <p>但牌上<b>曾经</b>挂过 Interaction 又被刻意删掉：它的碰撞箱在牌面之外的深度方向鼓出
 * 约半个牌宽，那圈里右键会触发事件但求交判不中，只能吞掉，于是贴着牌边点桌面既选不到牌
 * 也放不了方块。所以这一版必须把「捕获器不比拾取包络胖」钉死——它是死区不会回来的唯一依据。
 *
 * <p>纯几何、纯算术，不需要 Bukkit 运行时：数值全部由 config.yml 的默认值推出来，
 * 写法沿用 {@code HandCardPickEnvelopeTest}。
 */
class HandCardCapturerGeometryTest {

    /** render.hand-spacing 默认值。捕获器宽度与拾取通道宽度都取它。 */
    private static final double HAND_SPACING = 0.1;

    // 以下缩放来自 privateCardScale：
    // baseFactor = private-card-scale 0.8 / DEFAULT_PRIVATE_CARD_RENDER_SCALE 0.50 = 1.6
    // 静止态 faceFactor = private-card-size.* 0.50 × 1.6 = 0.8；悬停再乘 card-hover.scale 1.08。
    private static final double REST_SCALE = 0.5 * (0.8 / 0.5);
    private static final double MAX_SCALE = REST_SCALE * 1.08;

    /** 悬停抬升上界：card-hover.lift 0.06 × BACK_OUT 过冲钳位 1.15。 */
    private static final double MAX_HOVER_LIFT = 0.06 * 1.15;

    /** 选中抬升上界：render.selected-card.lift 0.18 × 过冲钳位 1.15。 */
    private static final double MAX_SELECTED_LIFT = 0.18 * 1.15;

    /**
     * 牌实体的世界 Y（相对放桌锚点）：
     * layout.hand-center.height 1.23 + private-hand-offset.vertical 1.90。
     */
    private static final double CARD_BASE_Y = 1.23 + 1.90;

    /** 生产同一套算法算出的两态拾取盒，[0] 未选中、[1] 已选中。 */
    private static Envelope[] pickEnvelopes() {
        Envelope unselected = HandCardPickGeometry.envelope(
            REST_SCALE, REST_SCALE, MAX_SCALE, MAX_SCALE,
            MAX_HOVER_LIFT, HAND_SPACING * 0.5);
        Envelope selected = HandCardPickGeometry.envelopeForSelected(
            unselected, REST_SCALE, MAX_SELECTED_LIFT);
        return HandCardPickGeometry.unifiedEnvelopes(unselected, selected);
    }

    private static Envelope capturerEnvelope() {
        Envelope[] pick = pickEnvelopes();
        return PhysicalTableManager.handCardCapturerEnvelope(pick[0], pick[1]);
    }

    /**
     * 捕获器宽度必须与拾取通道宽度同源。
     *
     * <p>这是「命中捕获器 ⟹ 几乎必然命中拾取包络」的前提，也是死区不会回来的依据：
     * 捕获器一旦比通道胖，多出来的那一圈就是「点得到事件、求交判不中」的区域，
     * 而那正是当年 Interaction 被删掉的原因。
     *
     * <p>失败条件：给捕获器加边距、倍率或最小尺寸。任何「顺手放宽一点让它好点」的改动
     * 都会在这里变红——那类改动恰好就是死区的成因。
     */
    @Test
    void capturerWidthMatchesThePickLaneWidth() {
        assertEquals(HAND_SPACING,
            PhysicalTableManager.handCardCapturerWidth(HAND_SPACING), 1.0e-9,
            "捕获器宽度不再等于拾取通道宽度：胖出来的那一圈会点得到事件却求交判不中，"
                + "贴着牌边点桌面又会既选不到牌也放不了方块——正是当年删掉 Interaction 的那个死区");
    }

    /**
     * hand-spacing 配成 0 时捕获器不能退化成零宽。
     *
     * <p>钳位下限 0.02 必须与 {@code privateHandStep} 和 {@code unifiedHandCardEnvelopes} 里的
     * {@code Math.max(0.02, ...)} 同口径：口径不一致就会出现「牌铺开用一个间距、判定用另一个」
     * 的错位。而零宽的 Interaction 压根点不到，等于右键选牌又整体失效一次。
     */
    @Test
    void capturerWidthIsClampedSoZeroSpacingStillClickable() {
        assertEquals(0.02, PhysicalTableManager.handCardCapturerWidth(0.0), 1.0e-9,
            "hand-spacing 配 0 时捕获器退化成零宽：右键选牌会再次整体失效");
        assertEquals(0.02, PhysicalTableManager.handCardCapturerWidth(-1.0), 1.0e-9,
            "负间距没被钳住：Interaction 宽度为负会直接点不到");
    }

    /**
     * <b>捕获器必须盖住未选中与已选中两个拾取盒的并集。</b>
     *
     * <p>这条是本文件最重要的一条，守的是一个会让「取消选中」失效的真实缺陷。
     *
     * <p>{@code unifiedEnvelopes} 只把两态的 {@code halfHeight} 取了 max，
     * <b>两态中心的差异被它丢掉了</b>：默认配置下未选中盒 {@code [2.8712, 3.3957]}、
     * 已选中盒 {@code [2.9425, 3.4670]}，各自高 0.5245 但中心差 0.0713。
     * 照 {@code halfHeight × 2 = 0.5245} 配一个盒子，摆在哪一态的中心上都会漏掉另一态：
     * 摆在未选中态时已选中盒的上半截落在盒外，<b>玩家选中一张牌后就点不到它的上半部分，
     * 取消选中失败</b>。选中抬升 0.18 格比牌本体全高 0.139 格还大，两态几乎完全错开，
     * 这不是理论风险。
     *
     * <p>失败条件：把高度改回 {@code unifiedEnvelopes} 的 {@code halfHeight × 2}，
     * 或改成只取某一态。
     */
    @Test
    void capturerCoversTheUnionOfBothPickBoxesSoDeselectingStillWorks() {
        Envelope[] pick = pickEnvelopes();
        Envelope capturer = capturerEnvelope();
        double capturerBottom = capturer.centerVOffset() - capturer.halfHeight();
        double capturerTop = capturer.centerVOffset() + capturer.halfHeight();

        for (int state = 0; state < 2; state++) {
            String name = state == 0 ? "未选中" : "已选中";
            double pickBottom = pick[state].centerVOffset() - pick[state].halfHeight();
            double pickTop = pick[state].centerVOffset() + pick[state].halfHeight();
            assertTrue(capturerBottom <= pickBottom + 1.0e-12,
                "捕获器盖不住" + name + "拾取盒的下沿：那一截判定区里点得到牌却没有实体接事件");
            assertTrue(capturerTop >= pickTop - 1.0e-12,
                "捕获器盖不住" + name + "拾取盒的上沿：" + name
                    + "的牌上半部分点不到——选中一张后就取消不了选中");
        }

        // 并集必然比单态高。这条断言把「有人把它改回 halfHeight × 2」直接拦住：
        // 那种写法下两者相等，上面的覆盖断言反而可能因为浮点相等而侥幸通过。
        assertTrue(capturer.halfHeight() > pick[0].halfHeight() + 1.0e-9,
            "捕获器高度退回到单态高度：两态中心差 0.0713 格，必然漏掉一态");
        // 锁住具体数值，让配置漂移也能被照见。0.5958 = 并集 [2.8712, 3.4670] 的高度。
        assertEquals(0.5958, capturer.halfHeight() * 2.0, 1.0e-4,
            "并集包络全高不再是默认配置推出来的 0.5958：请复核捕获器与按钮的竖直余量");
    }

    /**
     * 捕获器盒子必须与选中状态无关。
     *
     * <p>并集包络的意义就在这里：位置不随选中变化，<b>选牌时捕获器压根不需要动</b>。
     * 这比「选中就 teleport」结实得多——点击与 teleport 之间隔着至少一个 tick，
     * 按状态搬盒子必然存在一帧窗口，盒子还在旧位置而判定区已经换了，那一帧的点击会丢，
     * 表现为「偶尔点一下没反应」，是最难复现的那类问题。
     *
     * <p>失败条件：让捕获器的尺寸或中心重新依赖 isSelected。
     */
    @Test
    void capturerBoxIsIndependentOfSelectionState() {
        Envelope[] pick = pickEnvelopes();
        Envelope fromUnselectedFirst =
            PhysicalTableManager.handCardCapturerEnvelope(pick[0], pick[1]);
        Envelope fromSelectedFirst =
            PhysicalTableManager.handCardCapturerEnvelope(pick[1], pick[0]);

        assertEquals(fromUnselectedFirst.centerVOffset(), fromSelectedFirst.centerVOffset(), 1.0e-12,
            "并集包络的中心依赖入参顺序：说明它其实没在取并集，捕获器会随选中状态漂移");
        assertEquals(fromUnselectedFirst.halfHeight(), fromSelectedFirst.halfHeight(), 1.0e-12,
            "并集包络的高度依赖入参顺序：说明它其实没在取并集");
    }

    /**
     * 捕获器底边 = 包络中心 − 高/2。
     *
     * <p><b>Interaction 是从底边往上长的</b>，而包络是中心锚定的。少了这一步换算，
     * 把牌实体 Y 直接当底边，盒子会整体浮到牌上方（默认 {@code [3.13, 3.726]}
     * 而不是 {@code [2.871, 3.467]}），既点不到牌又白挡视线。按钮那条路踩过同一个坑，
     * 所以那边有 {@code hitboxBottomForLabel}。
     *
     * <p>失败条件：直接把包络中心当成实体 Y，或把减号写成加号。
     */
    @Test
    void capturerBottomEdgeIsDerivedFromTheEnvelopeCenter() {
        Envelope capturer = capturerEnvelope();
        double height = capturer.halfHeight() * 2.0;
        double centerY = CARD_BASE_Y + capturer.centerVOffset();
        double bottom = PhysicalTableManager.handCardCapturerBottomY(centerY, height);

        assertEquals(centerY - height / 2.0, bottom, 1.0e-12,
            "捕获器没做底边换算：Interaction 从底边往上长，直接用中心会让整个盒子上移半个高");
        // 换算之后盒子的竖直中点必须正好落回包络中心——这才是「捕获器包住包络」的定义。
        assertEquals(centerY, bottom + height / 2.0, 1.0e-12,
            "盒子的竖直中点没落回包络中心：判定区与接事件的实体错位");
        // 锁住实机绝对高度：底边 2.8712、顶边 3.4670（相对放桌锚点）。
        // 这两个数字同时是下面按钮余量那条用例的输入。
        assertEquals(2.8712, bottom, 1.0e-3,
            "捕获器底边不再落在 2.8712：请重新验算与按钮判定框的竖直余量");
        assertEquals(3.4670, bottom + height, 1.0e-3,
            "捕获器顶边不再落在 3.4670：请复核它是否仍盖住已选中态");
    }

    /**
     * 捕获器与按钮判定框在空间上不重叠——这是按钮不会被静默吞掉的依据。
     *
     * <p>动作按钮也用 Interaction 判定框。捕获器若挡在玩家与按钮之间，客户端射线先命中捕获器，
     * 事件实体就不是按钮，{@code handleActionButtonOnce} 判 false，<b>按钮点击被静默丢弃</b>：
     * 没有异常、没有提示，只有玩家报「按钮点不动」。这类事故有先例——曾经往椅子上放大判定框，
     * 结果既坐不上椅子又点不动按钮。
     *
     * <p>两个方向各锁一条，互相独立：
     * <ul>
     *   <li><b>深度序</b>是决定性的那条，也是拓扑性质：按钮离桌心 1.745（近面）、
     *       手牌盒最外 1.1675，按钮更靠外也就是更靠近玩家，
     *       捕获器永远在按钮<b>背后</b>，从拓扑上不可能挡住射线；</li>
     *   <li><b>竖直余量</b>是第二道保险：捕获器底边 2.8712、按钮判定框顶边 2.795，差 0.076 格。</li>
     * </ul>
     *
     * <p>失败条件：调大捕获器高度、下移手牌、上移按钮，或改动 button-layout 的距离参数。
     * 变红时请重新验算；生产侧还有 {@code warnIfCapturerCouldOccludeButtons} 按实机配置
     * 再核一次深度序，那条覆盖服主改配置的情况。
     */
    @Test
    void capturerNeverSitsBetweenThePlayerAndAnActionButton() {
        Envelope capturer = capturerEnvelope();

        // 深度序：离桌心越远越靠近玩家。
        // 手牌盒最靠玩家的那一面 = hand-center.distance 1.62 − private-hand-offset.depth 0.55
        //   + 逐张错层 card-depth-offset 0.005 × 9.5 + 捕获器半宽 0.05。
        // 9.5 取地主那手：20 张牌时 delta = −9.5…+9.5，是错层的最坏情况。
        // 用 17 张（农民）会算出 1.16，偏乐观 0.0075 格——余量分析必须取最坏那手。
        double handNearFace = 1.62 - 0.55 + 0.005 * 9.5 + HAND_SPACING * 0.5;
        // 按钮中心 = (front 1.40 + side 1.72)/2 + (button-offset.distance 1.40 − 1.10) × factor 0.45
        //   − 弧线深度补偿（actionArcOffset 的 depth 项，默认参数下约 0.045）。
        double buttonCenter = (1.40 + 1.72) / 2.0 + (1.40 - 1.10) * 0.45 - 0.045;
        // 按钮判定框在深度方向也有半宽：Interaction 的横截面是正方形，宽度按文字墨迹算。
        // 取最宽的那个标签「加入座位1」，它把按钮盒朝手牌方向伸得最多，是最坏情况。
        double widestButtonHalfWidth =
            PhysicalTableManager.resolveHitboxWidth("加入座位1", 0.4f, true) / 2.0;
        double buttonFarFace = buttonCenter - widestButtonHalfWidth;

        assertEquals(1.1675, handNearFace, 1.0e-9, "手牌近面推导变了，这条用例的输入已失效");
        assertEquals(1.650, buttonCenter, 1.0e-9, "按钮中心推导变了，这条用例的输入已失效");
        // 真正要守的是这一条：按钮盒<b>整个</b>都比手牌盒更靠近玩家。
        // 只比中心是不够的——按钮盒朝手牌那一侧还伸出半个宽度，最宽的标签伸得最多。
        assertTrue(buttonFarFace > handNearFace,
            "按钮判定框朝手牌那一侧（" + buttonFarFace + "）已经伸到手牌盒最外面（"
                + handNearFace + "）以内：两者在深度上重叠，捕获器可能挡在玩家与按钮之间，"
                + "按钮点击会被静默丢弃、连提示都没有");
        assertTrue(buttonFarFace > handNearFace + 0.2,
            "按钮与手牌的深度余量已不足 0.2 格（按钮远面 " + buttonFarFace
                + " vs 手牌近面 " + handNearFace + "）：请重新验算遮挡");

        // 竖直余量：捕获器底边 vs 按钮判定框顶边。
        double capturerBottom = PhysicalTableManager.handCardCapturerBottomY(
            CARD_BASE_Y + capturer.centerVOffset(), capturer.halfHeight() * 2.0);
        // 按钮判定框：文字在 button-offset.height 2.5 + action-label-height 0.2，
        // 框中心再加 button-hitbox-offset.vertical 0.02 与 TypewriterTextStyle 基准位移 0.03，
        // 框高 = 单行 9 像素 × action-label-scale 0.4 / 40 像素每格 = 0.09。
        double buttonBoxHeight = PhysicalTableManager.resolveHitboxHeight("不叫", 0.4f);
        double buttonBoxTop = 2.5 + 0.2 + 0.02 + 0.03 + buttonBoxHeight / 2.0;
        assertEquals(0.09, buttonBoxHeight, 1.0e-6,
            "按钮判定框高度不再是 0.09：竖直余量只有 0.076 格，请重新验算");
        assertTrue(capturerBottom > buttonBoxTop,
            "捕获器底边 " + capturerBottom + " 已经低到按钮判定框顶边 " + buttonBoxTop
                + " 以下：两个 Interaction 在竖直方向重叠，按钮点击可能被捕获器抢走并静默丢弃");
    }
}
