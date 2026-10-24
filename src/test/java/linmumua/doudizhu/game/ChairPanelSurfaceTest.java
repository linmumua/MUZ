package linmumua.doudizhu.game;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.bukkit.util.Vector;
import org.junit.jupiter.api.Test;

/**
 * 椅侧面板几何的纯函数回归：三轴是否真的进入坐标轴、MuMenu 同款九宫格是否把局部坐标映射到正确格位。
 *
 * <p>【为什么这些断言重要】面板显示与点击命中共用这份几何。任何一处自己再推一次 yaw 或再写一套网格，
 * 都会出现「看到的卡片」和「点到的槽位」错位——那类缺陷只能在实际游玩时被发现，
 * 而且表现为「选了别的道具」，不是崩溃，很容易被当成偶发。
 *
 * <p>【1.10.73：网格不再等分】面板要与 MuMenu {@code muz-chair-gadget.yml} 同款：
 * 参考画布 300×200，九张卡片 80×36、中心 x = -90/0/90、y = 30/-14/-58。
 * 等分网格（每格 100 × 66.7）会让卡片画错位置、命中区整体偏移，因此这里锁的是**卡片矩形**：
 * 卡片中心必须映射回自己、卡片之间的空隙必须返回 OUTSIDE、画布到世界的缩放必须按配置尺寸线性换算。
 */
class ChairPanelSurfaceTest {
    private static final double EPSILON = 1.0e-6;

    /** yaw=0 时面板法线朝 -Z（观察者朝 +Z 看它），右轴为 -X：与 Minecraft 视线一致。 */
    @Test
    void 默认朝向的法线与右轴符合观察者视角() {
        ChairPanelSurface.Axes axes = ChairPanelSurface.axes(0.0f, 0.0f, 0.0f);
        assertEquals(0.0, axes.normal().getX(), EPSILON);
        assertEquals(0.0, axes.normal().getY(), EPSILON);
        assertEquals(-1.0, axes.normal().getZ(), EPSILON);
        assertEquals(-1.0, axes.right().getX(), EPSILON);
        assertEquals(0.0, axes.right().getZ(), EPSILON);
        assertEquals(1.0, axes.up().getY(), EPSILON);
    }

    /** pitch 必须真的让面板前倾：up 出现水平分量，而不是永远竖直。 */
    @Test
    void pitch让面板前倾而不是被忽略() {
        ChairPanelSurface.Axes axes = ChairPanelSurface.axes(0.0f, 30.0f, 0.0f);
        // 观察者向下看 30°（视线 y=-sin30），法线是其反向，因此面板上仰 sin30。
        assertEquals(Math.sin(Math.toRadians(30.0)), axes.normal().getY(), EPSILON);
        assertTrue(Math.abs(axes.up().getZ()) > 0.1, "pitch 生效时 up 必须离开竖直方向");
        assertTrue(Math.abs(axes.up().getY()) < 0.99, "up 不应仍是 (0,1,0)");
    }

    /** roll 绕法线自转：right/up 旋转，normal 不变。 */
    @Test
    void roll绕法线自转且不改变法线() {
        ChairPanelSurface.Axes base = ChairPanelSurface.axes(0.0f, 0.0f, 0.0f);
        ChairPanelSurface.Axes rolled = ChairPanelSurface.axes(0.0f, 0.0f, 45.0f);
        assertEquals(base.normal().getZ(), rolled.normal().getZ(), EPSILON);
        assertTrue(Math.abs(rolled.up().getX()) > 0.1, "roll 生效时 up 必须有侧向分量");
        assertEquals(0.0, rolled.up().getY() - base.up().getY() * Math.cos(Math.toRadians(45.0)), EPSILON);
    }

    /** 三轴任意组合都必须保持右手正交基（行列式 +1），否则镜像会变成翻面。 */
    @Test
    void 任意三轴组合都保持右手正交基() {
        float[][] cases = {
            {0.0f, 0.0f, 0.0f},
            {90.0f, 0.0f, 0.0f},
            {-135.0f, 20.0f, 0.0f},
            {45.0f, -35.0f, 60.0f},
            {180.0f, 0.0f, 180.0f},
        };
        for (float[] value : cases) {
            ChairPanelSurface.Axes axes = ChairPanelSurface.axes(value[0], value[1], value[2]);
            assertUnit(axes.right(), "right");
            assertUnit(axes.up(), "up");
            assertUnit(axes.normal(), "normal");
            assertEquals(0.0, axes.right().dot(axes.up()), EPSILON, "right 与 up 必须正交");
            assertEquals(0.0, axes.right().dot(axes.normal()), EPSILON, "right 与 normal 必须正交");
            assertEquals(0.0, axes.up().dot(axes.normal()), EPSILON, "up 与 normal 必须正交");
            // 右手系：right × up = normal，等价于行列式 +1。
            Vector cross = axes.right().clone().crossProduct(axes.up());
            assertEquals(axes.normal().getX(), cross.getX(), EPSILON);
            assertEquals(axes.normal().getY(), cross.getY(), EPSILON);
            assertEquals(axes.normal().getZ(), cross.getZ(), EPSILON);
        }
    }

    /**
     * 九张卡片必须落在菜单给出的位置：x = -90/0/90、y = 30/-14/-58，尺寸 80×36（画布像素）。
     *
     * <p>这条直接对着 {@code muz-chair-gadget.yml} 抄下来的数字，是「同款布局」的最硬约束：
     * 一旦有人把它改回等分九宫格，卡片中心与尺寸都会立刻对不上。
     */
    @Test
    void 九张卡片落在菜单画布的指定位置与尺寸() {
        double width = 1.5;
        double height = 1.0;
        double scaleX = 1.5 / 300.0;
        double scaleY = 1.0 / 200.0;
        double[] expectedX = {-90.0, 0.0, 90.0};
        double[] expectedY = {30.0, -14.0, -58.0};
        double[] card = ChairPanelSurface.cardSize(width, height);
        assertEquals(80 * scaleX, card[0], EPSILON, "卡片宽必须是 80 画布像素");
        assertEquals(36 * scaleY, card[1], EPSILON, "卡片高必须是 36 画布像素");
        for (int cell = 0; cell < ChairPanelSurface.CELL_COUNT; cell++) {
            double[] center = ChairPanelSurface.cellCenter(cell, width, height);
            assertEquals(expectedX[cell % 3] * scaleX, center[0], EPSILON, "第 " + cell + " 格中心 x");
            assertEquals(expectedY[cell / 3] * scaleY, center[1], EPSILON, "第 " + cell + " 格中心 y");
        }
        // 第 0 格左上、第 8 格（语音）右下。
        double[] first = ChairPanelSurface.cellCenter(0, width, height);
        double[] last = ChairPanelSurface.cellCenter(ChairPanelSurface.VOICE_CELL, width, height);
        assertTrue(first[0] < 0.0 && first[1] > 0.0, "第 0 格应在左上");
        assertTrue(last[0] > 0.0 && last[1] < 0.0, "语音格应在右下");
    }

    /** 画布 → 世界的缩放必须随配置尺寸线性变化：1.5×1.0 是基准，3.0×2.0 必须整体放大一倍。 */
    @Test
    void 画布缩放随配置的世界尺寸线性变化() {
        assertEquals(1.5 / 300.0, ChairPanelSurface.scaleX(1.5), EPSILON);
        assertEquals(1.0 / 200.0, ChairPanelSurface.scaleY(1.0), EPSILON);
        double[] base = ChairPanelSurface.cellCenter(4, 1.5, 1.0);
        double[] doubled = ChairPanelSurface.cellCenter(4, 3.0, 2.0);
        assertEquals(base[0] * 2.0, doubled[0], EPSILON, "面板放大一倍，卡片中心也必须放大一倍");
        assertEquals(base[1] * 2.0, doubled[1], EPSILON);
    }

    /** 卡片中心必须与 {@code cellAt} 互逆。 */
    @Test
    void 九宫格中心与格位互逆() {
        double width = 1.5;
        double height = 1.0;
        for (int cell = 0; cell < ChairPanelSurface.CELL_COUNT; cell++) {
            double[] center = ChairPanelSurface.cellCenter(cell, width, height);
            assertEquals(cell, ChairPanelSurface.cellAt(center[0], center[1], width, height),
                "格 " + cell + " 的中心必须映射回自己");
        }
    }

    /**
     * 卡片之间的空隙、以及面板内不属于任何卡片的区域，都必须明确返回 OUTSIDE。
     *
     * <p>这是「点空隙不能误选旁边道具」的唯一保证。等分网格时代这条永远为假（每一点都落在某格内），
     * 现在卡片是不等距的 80×36 矩形，空隙真实存在。
     */
    @Test
    void 卡片间隙与面板外都不落格() {
        double width = 1.5;
        double height = 1.0;
        double[] card = ChairPanelSurface.cardSize(width, height);
        // 相邻两张卡片在 x 方向相距 90 画布像素 = 0.45 格，卡片半宽 0.2 → 间隙在 x∈(0.2,0.25)。
        assertEquals(ChairPanelSurface.OUTSIDE,
            ChairPanelSurface.cellAt((card[0] * 0.5 + 0.225) * 0.5, 0.15, width, height),
            "两张卡片之间的空隙必须不落格");
        // 行与行之间的空隙：第 2 行卡片 y∈[-0.16, 0.02]、第 1 行 y∈[0.06, 0.24]，
        // 空隙中点在 y=0.04（用实测区间推出来的，不是「面板正中」）。
        assertEquals(ChairPanelSurface.OUTSIDE, ChairPanelSurface.cellAt(0.0, 0.04, width, height),
            "行间空隙必须不落格");
        // 标题区（画布 y=64~82）也在所有卡片之外。
        assertEquals(ChairPanelSurface.OUTSIDE, ChairPanelSurface.cellAt(0.0, 0.40, width, height),
            "标题区必须不落格");
        // 面板外与非法输入。
        assertEquals(ChairPanelSurface.OUTSIDE, ChairPanelSurface.cellAt(0.76, 0.0, width, height));
        assertEquals(ChairPanelSurface.OUTSIDE, ChairPanelSurface.cellAt(Double.NaN, 0.0, width, height));
        assertEquals(ChairPanelSurface.OUTSIDE, ChairPanelSurface.cellAt(0.0, 0.0, 0.0, 0.5));
    }

    /** 卡片边缘必须仍算命中：玩家点到卡片边界不应被吞掉。 */
    @Test
    void 卡片边缘仍算命中() {
        double width = 1.5;
        double height = 1.0;
        double[] center = ChairPanelSurface.cellCenter(0, width, height);
        double[] card = ChairPanelSurface.cardSize(width, height);
        assertEquals(0, ChairPanelSurface.cellAt(
            center[0] - card[0] * 0.5 + 1.0e-9, center[1] + card[1] * 0.5 - 1.0e-9, width, height),
            "卡片左上角内侧仍必须命中第 0 格");
    }

    /** 语音格固定是最后一格；改动布局必须同步这里与渲染。 */
    @Test
    void 语音格是第九格() {
        assertEquals(8, ChairPanelSurface.VOICE_CELL);
        assertEquals(9, ChairPanelSurface.CELL_COUNT);
        assertEquals(3, ChairPanelSurface.COLUMNS);
        assertEquals(3, ChairPanelSurface.ROWS);
        assertEquals(300, ChairPanelSurface.CANVAS_WIDTH);
        assertEquals(200, ChairPanelSurface.CANVAS_HEIGHT);
        assertEquals(80, ChairPanelSurface.SLOT_WIDTH);
        assertEquals(36, ChairPanelSurface.SLOT_HEIGHT);
    }

    /** 图标缩放必须留出卡片内名称/状态两行，且随面板尺寸变化而不是写死常量。 */
    @Test
    void 图标缩放随面板尺寸变化并留出卡片内空隙() {
        float small = ChairPanelSurface.iconScale(1.5, 1.0);
        float large = ChairPanelSurface.iconScale(3.0, 2.0);
        assertTrue(large > small, "面板放大后图标也必须变大");
        assertTrue(small > 0.0f);
        double[] card = ChairPanelSurface.cardSize(1.5, 1.0);
        assertTrue(small < card[1], "图标高度必须小于卡片高度（否则会盖住名称/状态两行）");
        assertThrows(IllegalArgumentException.class, () -> ChairPanelSurface.iconScale(0.0, 1.0));
    }

    /** 非有限角度必须明确拒绝，不能静默产出一个怪异平面。 */
    @Test
    void 非有限角度被拒绝() {
        assertThrows(IllegalArgumentException.class, () -> ChairPanelSurface.axes(Float.NaN, 0.0f, 0.0f));
        assertThrows(IllegalArgumentException.class, () -> ChairPanelSurface.axes(0.0f, Float.POSITIVE_INFINITY, 0.0f));
        assertThrows(IllegalArgumentException.class, () -> ChairPanelSurface.axes(0.0f, 0.0f, Float.NEGATIVE_INFINITY));
    }

    /**
     * 方块显示必须真正以实体位置为中心：translation 必须是 {@code -scale/2}，**不是常数 -0.5**。
     *
     * <p>Bukkit {@code Transformation} 的合成顺序是 {@code T·R_left·S·R_right}：顶点先被 scale
     * 缩放、再被 translation 平移，方块模型占 {@code [0,1]³}，缩放后占 {@code [0, scale]}。
     * 因此模型中心落在 {@code translation + scale/2}。写死 {@code -0.5} 只在 scale=1 时居中：
     * 面板是 1.5×1.0×0.02，x 会偏 +0.25 格、z 会偏 −0.49 格——这正是实服看到的「底板错位」。
     *
     * <p>这条是纯数值验算，不需要服务端：只要 box 中心不为 0 就说明底板/边框/卡片三者
     * 与图标、文字、命中矩形不再同源（后三者都以实体位置为原点）。
     */
    @Test
    void 方块显示平移必须让模型中心落在实体原点() {
        double[][] cases = {
            {1.5, 1.0, 0.02},
            {3.0, 2.0, 0.02},
            {0.4, 0.18, 0.02}
        };
        for (double[] size : cases) {
            double[] t = ChairPanelSurface.plateTranslation(size[0], size[1], size[2]);
            // 模型在缩放后占 [0, scale]，加 translation 后的盒子中心必须回到 0。
            assertEquals(0.0, t[0] + size[0] * 0.5, 1.0e-9,
                "宽度 " + size[0] + " 的板在 x 上必须居中");
            assertEquals(0.0, t[1] + size[1] * 0.5, 1.0e-9,
                "高度 " + size[1] + " 的板在 y 上必须居中");
            assertEquals(0.0, t[2] + size[2] * 0.5, 1.0e-9,
                "厚度 " + size[2] + " 的板在 z 上必须居中");
        }
        // 反证：常数 -0.5 对 1.5 宽的板并不居中（盒子中心会落在 +0.25）。
        double[] wrong = {-0.5, -0.5, -0.5};
        assertEquals(0.25, wrong[0] + 1.5 * 0.5, 1.0e-9,
            "常数 -0.5 在 1.5 宽时会让中心偏 +0.25 格，必须用 -scale/2");
        assertEquals(-0.49, wrong[2] + 0.02 * 0.5, 1.0e-9,
            "常数 -0.5 在 0.02 厚时会让板沉到锚点后 0.49 格");
    }

    /**
     * 边框必须与 MuMenu 参考一致：外框 298×198、线宽 2 像素、相对 300×200 的底板各内缩 1 像素。
     *
     * <p>旧实现画的是 304×204 的实心板并压在底板背后——既放大了 4 像素，又让边框整块被底板盖住，
     * 只剩外扩的一圈，视觉上就是「板子变大」而不是「有蓝边」。
     */
    @Test
    void 边框是298乘198的两像素描边而不是外扩实心板() {
        assertEquals(298, ChairPanelSurface.BORDER_CANVAS_WIDTH);
        assertEquals(198, ChairPanelSurface.BORDER_CANVAS_HEIGHT);
        assertEquals(2, ChairPanelSurface.BORDER_THICKNESS);

        double[][] strips = ChairPanelSurface.borderStrips();
        assertEquals(4, strips.length, "边框由上下左右四条细条组成");

        double minX = Double.POSITIVE_INFINITY;
        double maxX = Double.NEGATIVE_INFINITY;
        double minY = Double.POSITIVE_INFINITY;
        double maxY = Double.NEGATIVE_INFINITY;
        for (double[] strip : strips) {
            double halfW = strip[2] * 0.5;
            double halfH = strip[3] * 0.5;
            minX = Math.min(minX, strip[0] - halfW);
            maxX = Math.max(maxX, strip[0] + halfW);
            minY = Math.min(minY, strip[1] - halfH);
            maxY = Math.max(maxY, strip[1] + halfH);
            // 每条边都必须是 2 像素粗的细条：至少一个方向正好是 2。
            assertTrue(Math.abs(strip[2] - 2.0) < 1.0e-9 || Math.abs(strip[3] - 2.0) < 1.0e-9,
                "边框条必须有一边正好 2 像素粗");
        }
        assertEquals(298.0, maxX - minX, 1.0e-9, "边框外沿宽度必须是 298 像素");
        assertEquals(198.0, maxY - minY, 1.0e-9, "边框外沿高度必须是 198 像素");
        // 相对 300×200 的底板各内缩 1 像素（不是外扩）。
        assertEquals(-149.0, minX, 1.0e-9, "左边框必须内缩 1 像素（−149 而不是 −150）");
        assertEquals(149.0, maxX, 1.0e-9);
        assertEquals(-99.0, minY, 1.0e-9, "下边框必须内缩 1 像素（−99 而不是 −100）");
        assertEquals(99.0, maxY, 1.0e-9);
    }

    /**
     * 卡片内「图标方框」与「两行文字」必须完全不重叠。
     *
     * <p>卡片只有 80×36：名称在 {@code cy+6}（字号 11）、状态在 {@code cy−8}（字号 8），
     * 中间只剩约 4.5 像素。把图标居中放（旧实现）会同时压住两行字。
     * 现在图标固定在左侧 22 像素方框、文字放右侧 52 像素列，两者水平分开；
     * 名称与状态两行之间也留出间隙。这条把三组坐标一次算清。
     */
    @Test
    void 卡片图标与两行文字不重叠() {
        double width = 1.5;
        double height = 1.0;
        double scaleX = ChairPanelSurface.scaleX(width);
        double scaleY = ChairPanelSurface.scaleY(height);
        double[] card = ChairPanelSurface.cardSize(width, height);

        // 图标方框：以 SLOT_ICON_DX 为中心、SLOT_ICON_SIZE 为边长。
        double iconLeft = (ChairPanelSurface.SLOT_ICON_DX - ChairPanelSurface.SLOT_ICON_SIZE * 0.5) * scaleX;
        double iconRight = (ChairPanelSurface.SLOT_ICON_DX + ChairPanelSurface.SLOT_ICON_SIZE * 0.5) * scaleX;
        // 文字列：以 SLOT_TEXT_DX 为中心、SLOT_TEXT_WIDTH 为宽。
        double textLeft = (ChairPanelSurface.SLOT_TEXT_DX - ChairPanelSurface.SLOT_TEXT_WIDTH * 0.5) * scaleX;
        double textRight = (ChairPanelSurface.SLOT_TEXT_DX + ChairPanelSurface.SLOT_TEXT_WIDTH * 0.5) * scaleX;

        assertTrue(iconRight < textLeft,
            "图标右沿（" + iconRight + "）必须小于文字列左沿（" + textLeft + "），否则图标压到文字");
        // 两者都必须落在卡片宽度内。
        assertTrue(iconLeft > -card[0] * 0.5, "图标不得越出卡片左边缘");
        assertTrue(textRight < card[0] * 0.5, "文字列不得越出卡片右边缘");

        // 图标方框高度不得超出卡片高度。
        assertTrue(ChairPanelSurface.iconBoxSize(width, height) < card[1],
            "图标方框必须小于卡片高度");

        // 两行文字之间要有间隙：名称带底 = SLOT_NAME_DY − 字号/2，状态带顶 = SLOT_STATE_DY + 字号/2。
        double nameBottom = ChairPanelSurface.SLOT_NAME_DY - 11.0 * 0.5;
        double stateTop = ChairPanelSurface.SLOT_STATE_DY + 8.0 * 0.5;
        assertTrue(nameBottom > stateTop,
            "名称行（底 " + nameBottom + "）必须在状态行（顶 " + stateTop + "）之上，不能重叠");
        // 两行都不得越出卡片上下边缘（卡片高 36，半高 18）。
        assertTrue(ChairPanelSurface.SLOT_NAME_DY + 11.0 * 0.5 < 18.0, "名称行不得越出卡片上沿");
        assertTrue(ChairPanelSurface.SLOT_STATE_DY - 8.0 * 0.5 > -18.0, "状态行不得越出卡片下沿");
    }

    /**
     * 【可点击性】Interaction 判定盒必须完整包住显示平面——尤其是第 2/3 行（含语音格）。
     *
     * <p>仓库既有契约：{@code Interaction} 的 AABB **从实体位置沿 +Y 向上生长**，
     * 而显示平面以实体位置**居中**。旧实现把判定盒放在面板中心、并直接给面板高度，
     * 实际覆盖 {@code [centerY, centerY+h]}，可视板却是 {@code [centerY−h/2, centerY+h/2]}——
     * 下半块（第 2、3 行与语音格）完全没有判定盒，玩家点不到。
     *
     * <p>这里对三种姿态（无倾斜 / 俯仰 / 俯仰+翻滚）逐张卡片验算四角：
     * 竖直方向必须落在 {@code [anchorYOffset, anchorYOffset+coverHeight]}，
     * 水平方向必须落在 {@code ±coverWidth/2} 内。
     */
    @Test
    void 判定盒必须从显示矩形底边起包住全部三行卡片() {
        double width = 1.5;
        double height = 1.0;
        float[][] poses = {
            {0.0f, 0.0f, 0.0f},
            {0.0f, 30.0f, 0.0f},
            {0.0f, 20.0f, 35.0f},
            {37.0f, 15.0f, -25.0f}
        };
        for (float[] pose : poses) {
            ChairPanelSurface.Axes axes = ChairPanelSurface.axes(pose[0], pose[1], pose[2]);
            ChairPanelSurface.InteractionBox box =
                ChairPanelSurface.interactionBox(width, height, pose[0], pose[1], pose[2]);
            String tag = "yaw=" + pose[0] + " pitch=" + pose[1] + " roll=" + pose[2];

            // 判定盒底边必须在面板中心之下，否则下半块永远没有盒子。
            assertTrue(box.anchorYOffset() < 0.0, tag + "：判定盒锚点必须低于面板中心");
            assertTrue(box.coverHeight() > 0.0, tag + "：判定盒高度必须为正");
            // 盒顶 = anchorYOffset + coverHeight = 竖直投影的 maxY。只有**无倾斜**时它才等于 +h/2；
            // 有 pitch/roll 时竖直投影必然收缩（例如 pitch=30° 时是 h·cos30），
            // 那种姿态由下面的「逐格四角包络」保证，不能拿 h/2 当普适等式。
            if (pose[1] == 0.0f && pose[2] == 0.0f) {
                assertEquals(height * 0.5, box.anchorYOffset() + box.coverHeight(), 1.0e-9,
                    tag + "：无倾斜时盒顶必须正好到 +h/2（盒高=竖直投影）");
            } else {
                // 【不要给上界】roll 会把矩形在平面内旋转，其轴对齐包围盒的竖直投影
                // **可能超过**面板原始高度（35° roll + 20° pitch 就会），所以「盒高 <= 面板高」
                // 是个假不变量。倾斜姿态真正的约束只有下面那条：四角必须全部落在盒内。
                assertTrue(box.coverHeight() > 0.0, tag + "：有倾斜时竖直投影必须为正");
                assertTrue(box.coverWidth() >= height * 0.5, tag + "：盒宽必须覆盖旋转后的横向投影");
            }
        }

        // 无倾斜时：盒底 = −h/2、盒高 = h、盒宽 = w（与可视板严格一致）。
        ChairPanelSurface.InteractionBox plain = ChairPanelSurface.interactionBox(width, height, 0.0f, 0.0f, 0.0f);
        assertEquals(-height * 0.5, plain.anchorYOffset(), 1.0e-9);
        assertEquals(height, plain.coverHeight(), 1.0e-9);
        assertEquals(width, plain.coverWidth(), 1.0e-9);

        // 逐格四角包络：三行都要被包住（第 1/2/3 行）。
        for (float[] pose : poses) {
            ChairPanelSurface.Axes axes = ChairPanelSurface.axes(pose[0], pose[1], pose[2]);
            ChairPanelSurface.InteractionBox box =
                ChairPanelSurface.interactionBox(width, height, pose[0], pose[1], pose[2]);
            double boxMinY = box.anchorYOffset();
            double boxMaxY = box.anchorYOffset() + box.coverHeight();
            double halfCover = box.coverWidth() * 0.5;
            double[] card = ChairPanelSurface.cardSize(width, height);
            for (int cell = 0; cell < ChairPanelSurface.CELL_COUNT; cell++) {
                double[] center = ChairPanelSurface.cellCenter(cell, width, height);
                for (int sx = -1; sx <= 1; sx += 2) {
                    for (int sy = -1; sy <= 1; sy += 2) {
                        double localX = center[0] + card[0] * 0.5 * sx;
                        double localY = center[1] + card[1] * 0.5 * sy;
                        Vector corner = axes.right().clone().multiply(localX)
                            .add(axes.up().clone().multiply(localY));
                        assertTrue(corner.getY() >= boxMinY - 1.0e-9 && corner.getY() <= boxMaxY + 1.0e-9,
                            "第 " + cell + " 格角点 y=" + corner.getY() + " 必须落在判定盒 ["
                                + boxMinY + ", " + boxMaxY + "] 内（yaw=" + pose[0]
                                + " pitch=" + pose[1] + " roll=" + pose[2] + "）");
                        assertTrue(Math.abs(corner.getX()) <= halfCover + 1.0e-9
                                && Math.abs(corner.getZ()) <= halfCover + 1.0e-9,
                            "第 " + cell + " 格角点的水平投影必须落在判定盒半宽 " + halfCover + " 内");
                    }
                }
            }
        }
    }

    /**
     * 反证：旧做法（锚点放在面板中心、高度直接取面板高度）必然漏掉下半块。
     *
     * <p>把这条写死，是为了让「如果有人把 hitbox 挪回 center」立刻变红——
     * 这个缺陷在实服上只表现为「下面两行点不动」，不看代码很难定位。
     */
    @Test
    void 旧的中心锚点做法会漏掉下半块面板() {
        double height = 1.0;
        // 旧做法覆盖区间
        double legacyMin = 0.0;
        double legacyMax = height;
        // 第 3 行（最下）卡片中心的局部 y
        double[] bottomCenter = ChairPanelSurface.cellCenter(6, 1.5, height);
        assertTrue(bottomCenter[1] < legacyMin,
            "最下一行卡片中心 y=" + bottomCenter[1] + " 必须低于旧做法的盒底 " + legacyMin
                + "（这正是「点不到」的成因）");
        assertTrue(ChairPanelSurface.interactionBox(1.5, height, 0.0f, 0.0f, 0.0f).anchorYOffset()
                < legacyMin,
            "修正后的盒锚点必须低于旧做法的盒底");
        assertTrue(bottomCenter[1] >= ChairPanelSurface.interactionBox(
                1.5, height, 0.0f, 0.0f, 0.0f).anchorYOffset(),
            "最下一行必须落在修正后的盒内");
        assertTrue(legacyMax > 0.0);
    }

    private static void assertUnit(Vector vector, String name) {
        assertEquals(1.0, vector.length(), EPSILON, name + " 必须是单位向量");
    }
}
