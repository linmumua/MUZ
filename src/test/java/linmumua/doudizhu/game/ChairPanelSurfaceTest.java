package linmumua.doudizhu.game;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.bukkit.util.Vector;
import org.junit.jupiter.api.Test;

/**
 * 椅侧面板几何的纯函数回归：三轴是否真的进入坐标轴、九宫格是否把局部坐标映射到正确格位。
 *
 * <p>【为什么这些断言重要】面板显示与点击命中共用这份几何。任何一处自己再推一次 yaw，
 * 都会出现「看到的图标」和「点到的槽位」差一格——那类缺陷只能在实际游玩时被发现，
 * 而且表现为「选了别的道具」，不是崩溃，很容易被当成偶发。这里把关系钉死：
 * <ul>
 *   <li>三轴必须真实生效（pitch 改变 up、roll 绕法线自转），不是只存不用；</li>
 *   <li>右手系行列式必须为 +1：左右对称要做几何镜像，不能靠负缩放把文字镜像；</li>
 *   <li>九宫格边界与中心必须与 {@code cellCenter} 互逆，且面板外返回 {@link ChairPanelSurface#OUTSIDE}。</li>
 * </ul>
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

    /** 九宫格：每格中心必须与 {@code cellAt} 互逆，且顺序是从左上到右下。 */
    @Test
    void 九宫格中心与格位互逆() {
        double width = 1.5;
        double height = 1.0;
        double halfWidth = width / 2.0;
        double halfHeight = height / 2.0;
        for (int cell = 0; cell < ChairPanelSurface.CELL_COUNT; cell++) {
            double[] center = ChairPanelSurface.cellCenter(cell, width, height);
            assertEquals(cell, ChairPanelSurface.cellAt(center[0], center[1], halfWidth, halfHeight),
                "格 " + cell + " 的中心必须映射回自己");
        }
        // 第 0 格在左上：x 最小、y 最大；第 8 格在右下。
        double[] first = ChairPanelSurface.cellCenter(0, width, height);
        double[] last = ChairPanelSurface.cellCenter(ChairPanelSurface.VOICE_CELL, width, height);
        assertTrue(first[0] < 0.0 && first[1] > 0.0, "第 0 格应在左上");
        assertTrue(last[0] > 0.0 && last[1] < 0.0, "语音格应在右下");
    }

    /** 面板外必须明确返回 OUTSIDE，不能就近取一格（否则会选到不该选的道具）。 */
    @Test
    void 面板外不落格() {
        assertEquals(ChairPanelSurface.OUTSIDE, ChairPanelSurface.cellAt(0.76, 0.0, 0.75, 0.5));
        assertEquals(ChairPanelSurface.OUTSIDE, ChairPanelSurface.cellAt(0.0, 0.51, 0.75, 0.5));
        assertEquals(ChairPanelSurface.OUTSIDE, ChairPanelSurface.cellAt(Double.NaN, 0.0, 0.75, 0.5));
        assertEquals(ChairPanelSurface.OUTSIDE, ChairPanelSurface.cellAt(0.0, 0.0, 0.0, 0.5));
        // 边界上仍算命中：玩家点到边缘不应被吞掉。
        assertEquals(0, ChairPanelSurface.cellAt(-0.75, 0.5, 0.75, 0.5));
    }

    /** 语音格固定是最后一格；改动布局必须同步这里与渲染。 */
    @Test
    void 语音格是第九格() {
        assertEquals(8, ChairPanelSurface.VOICE_CELL);
        assertEquals(9, ChairPanelSurface.CELL_COUNT);
        assertEquals(3, ChairPanelSurface.COLUMNS);
        assertEquals(3, ChairPanelSurface.ROWS);
    }

    /** 图标缩放必须留出格间空隙，且随面板尺寸变化而不是写死常量。 */
    @Test
    void 图标缩放随面板尺寸变化并留出空隙() {
        float small = ChairPanelSurface.iconScale(1.5, 1.0);
        float large = ChairPanelSurface.iconScale(3.0, 2.0);
        assertTrue(large > small, "面板放大后图标也必须变大");
        assertTrue(small > 0.0f);
        // 单个格子的边长是 0.5，图标不能占满整格（否则相邻图标粘连）。
        assertTrue(small < 0.5f, "图标必须小于格子边长");
        assertThrows(IllegalArgumentException.class, () -> ChairPanelSurface.iconScale(0.0, 1.0));
    }

    /** 非有限角度必须明确拒绝，不能静默产出一个怪异平面。 */
    @Test
    void 非有限角度被拒绝() {
        assertThrows(IllegalArgumentException.class, () -> ChairPanelSurface.axes(Float.NaN, 0.0f, 0.0f));
        assertThrows(IllegalArgumentException.class, () -> ChairPanelSurface.axes(0.0f, Float.POSITIVE_INFINITY, 0.0f));
        assertThrows(IllegalArgumentException.class, () -> ChairPanelSurface.axes(0.0f, 0.0f, Float.NEGATIVE_INFINITY));
    }

    private static void assertUnit(Vector vector, String name) {
        assertEquals(1.0, vector.length(), EPSILON, name + " 必须是单位向量");
    }
}
