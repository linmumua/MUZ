package linmumua.doudizhu.ui.dialog;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import linmumua.doudizhu.config.ChairSidePanelSettings;
import linmumua.doudizhu.config.ChairSidePanelSettings.PanelLayout;
import linmumua.doudizhu.config.ChairSidePanelSettings.Side;
import org.junit.jupiter.api.Test;

/**
 * 椅子左右面板布局的意图测试（纯逻辑，不需要服务端）。
 *
 * <p>锁的是那几条**会真的把面板调坏**的规则：
 * <ol>
 *   <li>出厂左右对称、1.5 × 1.0（与 schema 同一真源，不在这里另立一套默认值）；</li>
 *   <li>单侧编辑只改那一侧——<b>绝不</b>把同一个非对称值写给两侧；</li>
 *   <li>镜像按钮的旋转对应明确：lateral/yaw/roll 取反、vertical/depth/pitch 不变，
 *       且结果仍是右手系（文字正向，不需要负 scale / 翻 U）；</li>
 *   <li>尺寸等比联动（比例必须与菜单画布一致，否则锚定面板会被拒绝且不创建实体）；</li>
 *   <li>非法输入一律拒绝：NaN、±∞、尺寸不为正；四位精度归一。</li>
 * </ol>
 */
class ChairPanelLayoutMirrorTest {

    /** 出厂：1.5 × 1.0、启用、左右精确互为镜像，且默认值来自 schema。 */
    @Test
    void defaultsAreMirroredAndComeFromTheSchema() {
        ChairPanelLayout layout = ChairPanelLayout.defaults();
        ChairSidePanelSettings schema = ChairSidePanelSettings.defaults();

        assertTrue(layout.enabled());
        assertEquals(1.5, layout.width(), 1.0E-12);
        assertEquals(1.0, layout.height(), 1.0E-12);
        assertEquals(schema.width(), layout.width(), 1.0E-12);
        assertEquals(schema.height(), layout.height(), 1.0E-12);
        assertEquals(schema.left(), layout.toSettings().left(), "左侧必须与 schema 默认值同一真源");
        assertEquals(schema.right(), layout.toSettings().right());
        assertTrue(layout.exactlyMirrored(), "出厂必须左右互为精确镜像");
        assertNotEquals(layout.toSettings().left().lateral(), layout.toSettings().right().lateral(),
            "左右 lateral 必须互为相反数，不能是同一个值");
    }

    /**
     * 需求核心：单侧编辑只动那一侧。
     *
     * <p>失败条件：把同一个非对称值同时写给左右两侧（那样面板会偏移双倍，而且看起来「对称」）。
     */
    @Test
    void editingOneSideNeverTouchesTheOther() {
        ChairPanelLayout before = ChairPanelLayout.defaults();
        PanelLayout rightBefore = before.toSettings().right();

        ChairPanelLayout after = before.withFieldValue(ChairPanelLayoutField.LEFT_LATERAL, 0.875);

        assertEquals(0.875, after.toSettings().left().lateral(), 1.0E-12);
        assertEquals(rightBefore, after.toSettings().right(), "改左侧不得改动右侧任何一个分量");
        assertFalse(after.exactlyMirrored(), "单侧改动后两侧确实不再镜像（界面会如实显示）");
    }

    /** 镜像按钮：把左侧镜像到右侧，六个分量按唯一算式对应。 */
    @Test
    void mirrorButtonCopiesTheExactGeometry() {
        ChairPanelLayout layout = ChairPanelLayout.defaults()
            .withFieldValue(ChairPanelLayoutField.LEFT_LATERAL, -0.6)
            .withFieldValue(ChairPanelLayoutField.LEFT_VERTICAL, 1.2)
            .withFieldValue(ChairPanelLayoutField.LEFT_DEPTH, 0.35)
            .withFieldValue(ChairPanelLayoutField.LEFT_PITCH, 10.0)
            .withFieldValue(ChairPanelLayoutField.LEFT_YAW, 35.0)
            .withFieldValue(ChairPanelLayoutField.LEFT_ROLL, 5.0);

        PanelLayout mirrored = layout.withMirrored(Side.LEFT).toSettings().right();

        assertEquals(0.6, mirrored.lateral(), 1.0E-12, "lateral 必须取反");
        assertEquals(1.2, mirrored.vertical(), 1.0E-12, "vertical 不变");
        assertEquals(0.35, mirrored.depth(), 1.0E-12, "depth 不变");
        assertEquals(10.0, mirrored.pitch(), 1.0E-12, "pitch 不变");
        assertEquals(-35.0, mirrored.yaw(), 1.0E-12, "yaw 必须取反");
        assertEquals(-5.0, mirrored.roll(), 1.0E-12, "roll 必须取反");
        assertTrue(layout.withMirrored(Side.LEFT).exactlyMirrored());
    }

    /** 反方向镜像（右侧 → 左侧）同样成立，且用的是同一算式。 */
    @Test
    void mirroringFromRightProducesTheSameCorrespondence() {
        ChairPanelLayout layout = ChairPanelLayout.defaults()
            .withFieldValue(ChairPanelLayoutField.RIGHT_YAW, 35.0)
            .withFieldValue(ChairPanelLayoutField.RIGHT_LATERAL, 0.9);

        ChairPanelLayout result = layout.withMirrored(Side.RIGHT);

        assertEquals(-0.9, result.toSettings().left().lateral(), 1.0E-12);
        assertEquals(-35.0, result.toSettings().left().yaw(), 1.0E-12);
        assertEquals(ChairSidePanelSettings.mirrored(result.toSettings().right()), result.toSettings().left(),
            "镜像必须与 schema 的唯一算式一致（不许本类自己再算一套）");
    }

    /**
     * 镜像结果是**右手系**：三轴取反构成 R' = M·R·M，det = +1。
     *
     * <p>这条锁的是「文字正向、不做贴图翻转」：若哪天有人把镜像改成负 scale 或翻 U，
     * 面板文字就会反字，而这里用行列式为 +1 把「不许翻手性」写成可执行断言。
     * 用 yaw/pitch/roll 的旋转矩阵按 Y-X-Z 次序算出朝向基，再取其行列式。
     */
    @Test
    void mirroredOrientationKeepsRightHandedness() {
        PanelLayout source = new PanelLayout(-0.7, 0.2, 1.1, 12.0, 41.0, 7.0);
        PanelLayout mirrored = ChairSidePanelSettings.mirrored(source);

        assertEquals(1.0, orientationDeterminant(source), 1.0E-9, "源朝向必须是纯旋转");
        assertEquals(1.0, orientationDeterminant(mirrored), 1.0E-9,
            "镜像后的朝向必须仍是右手系（det=+1）：不得靠负 scale / 翻 U 实现\"对称\"");
        assertNotEquals(source.lateral(), mirrored.lateral(), "中心必须真的镜像过去");
    }

    /**
     * 按 Y-X-Z 次序算朝向矩阵的行列式。
     *
     * <p>只用于验证「纯旋转」：三个角度都是真旋转，复合仍是真旋转，det 必须为 +1。
     * 若将来有人把镜像实现成反射（det=-1），渲染侧就只能靠负 scale 才能对得上，本断言会先红。
     */
    private static double orientationDeterminant(PanelLayout layout) {
        double yaw = Math.toRadians(layout.yaw());
        double pitch = Math.toRadians(layout.pitch());
        double roll = Math.toRadians(layout.roll());

        double[][] ry = {
            { Math.cos(yaw), 0.0, Math.sin(yaw) },
            { 0.0, 1.0, 0.0 },
            { -Math.sin(yaw), 0.0, Math.cos(yaw) }
        };
        double[][] rx = {
            { 1.0, 0.0, 0.0 },
            { 0.0, Math.cos(pitch), -Math.sin(pitch) },
            { 0.0, Math.sin(pitch), Math.cos(pitch) }
        };
        double[][] rz = {
            { Math.cos(roll), -Math.sin(roll), 0.0 },
            { Math.sin(roll), Math.cos(roll), 0.0 },
            { 0.0, 0.0, 1.0 }
        };
        return determinant(multiply(multiply(ry, rx), rz));
    }

    private static double[][] multiply(double[][] a, double[][] b) {
        double[][] out = new double[3][3];
        for (int row = 0; row < 3; row++) {
            for (int col = 0; col < 3; col++) {
                double sum = 0.0;
                for (int k = 0; k < 3; k++) {
                    sum += a[row][k] * b[k][col];
                }
                out[row][col] = sum;
            }
        }
        return out;
    }

    private static double determinant(double[][] m) {
        return m[0][0] * (m[1][1] * m[2][2] - m[1][2] * m[2][1])
            - m[0][1] * (m[1][0] * m[2][2] - m[1][2] * m[2][0])
            + m[0][2] * (m[1][0] * m[2][1] - m[1][1] * m[2][0]);
    }

    /** 总开关与恢复默认。 */
    @Test
    void enabledToggleAndRestoreDefaults() {
        ChairPanelLayout layout = ChairPanelLayout.defaults().withEnabled(false);
        assertFalse(layout.enabled());
        assertTrue(layout.withEnabled(true).enabled());

        ChairPanelLayout changed = layout
            .withFieldValue(ChairPanelLayoutField.LEFT_YAW, 30.0)
            .withFieldValue(ChairPanelLayoutField.WIDTH, 3.0);
        assertEquals(30.0, changed.toSettings().left().yaw(), 1.0E-12);
        // 恢复默认＝重新取 schema 默认，不是本类另存一份常量。
        assertEquals(ChairSidePanelSettings.defaults(), ChairPanelLayout.defaults().toSettings());
    }

    /**
     * 尺寸等比联动：改宽 → 高按当前比例缩放；改高 → 宽按当前比例缩放。
     *
     * <p>硬约束来源：锚定面板用 canvas.width/widthBlocks 反解 ppb 再校验高度，比例不符会拒绝创建
     * 且没有任何实体。所以「单独改一边」等于把面板弄没。
     */
    @Test
    void sizeEditsScaleTheOtherDimensionToKeepTheAspectRatio() {
        ChairPanelLayout wider = ChairPanelLayout.defaults().withFieldValue(ChairPanelLayoutField.WIDTH, 3.0);
        assertEquals(3.0, wider.width(), 1.0E-12);
        assertEquals(2.0, wider.height(), 1.0E-12, "改宽必须等比联动高度（1.5:1.0 → 3.0:2.0）");

        ChairPanelLayout taller = ChairPanelLayout.defaults().withFieldValue(ChairPanelLayoutField.HEIGHT, 0.5);
        assertEquals(0.75, taller.width(), 1.0E-12, "改高必须等比联动宽度");
        assertEquals(0.5, taller.height(), 1.0E-12);

        assertEquals(ChairPanelLayout.defaults().aspectRatio(), wider.aspectRatio(), 1.0E-12,
            "联动后比例必须保持不变（画布是 3:2）");
    }

    /** 尺寸不为正一律拒绝，且不静默钳制。 */
    @Test
    void nonPositiveSizesAreRejected() {
        ChairPanelLayout layout = ChairPanelLayout.defaults();
        assertThrows(IllegalArgumentException.class,
            () -> layout.withFieldValue(ChairPanelLayoutField.WIDTH, 0.0));
        assertThrows(IllegalArgumentException.class,
            () -> layout.withFieldValue(ChairPanelLayoutField.HEIGHT, -1.0));
        assertThrows(IllegalArgumentException.class,
            () -> ChairPanelLayout.applyRaw(layout, ChairPanelLayoutField.WIDTH, "0"));
        assertThrows(IllegalArgumentException.class,
            () -> ChairPanelLayout.applyRaw(layout, ChairPanelLayoutField.HEIGHT, "-1.5"));
    }

    /** 非有限值一律拒绝（不设魔法上下限，但 NaN/±∞ 永远非法）。 */
    @Test
    void nonFiniteValuesAreRejected() {
        ChairPanelLayout layout = ChairPanelLayout.defaults();
        assertThrows(IllegalArgumentException.class,
            () -> layout.withFieldValue(ChairPanelLayoutField.LEFT_LATERAL, Double.NaN));
        assertThrows(IllegalArgumentException.class,
            () -> layout.withFieldValue(ChairPanelLayoutField.LEFT_VERTICAL, Double.POSITIVE_INFINITY));
        assertThrows(IllegalArgumentException.class,
            () -> layout.withFieldValue(ChairPanelLayoutField.LEFT_DEPTH, Double.NEGATIVE_INFINITY));
        // 侧布局本身也不接受非有限值（schema 紧凑构造器兜底）
        assertThrows(IllegalArgumentException.class,
            () -> new PanelLayout(Double.NaN, 0.0, 0.0, 0.0, 0.0, 0.0));
    }

    /** 文本入口：非法文本抛出可展示的中文异常；合法文本（含空格、负数）正常落库。 */
    @Test
    void textParsingAcceptsFiniteNumbersAndRejectsJunk() {
        ChairPanelLayout layout = ChairPanelLayout.defaults();

        assertEquals(0.5, ChairPanelLayout.applyRaw(layout, ChairPanelLayoutField.LEFT_LATERAL, " 0.5 ")
            .toSettings().left().lateral(), 1.0E-12);
        assertEquals(-0.5, ChairPanelLayout.applyRaw(layout, ChairPanelLayoutField.LEFT_YAW, "-0.5")
            .toSettings().left().yaw(), 1.0E-12);

        assertThrows(IllegalArgumentException.class,
            () -> ChairPanelLayout.applyRaw(layout, ChairPanelLayoutField.LEFT_LATERAL, ""));
        assertThrows(IllegalArgumentException.class,
            () -> ChairPanelLayout.applyRaw(layout, ChairPanelLayoutField.LEFT_LATERAL, "abc"));
        assertThrows(IllegalArgumentException.class,
            () -> ChairPanelLayout.applyRaw(layout, ChairPanelLayoutField.LEFT_LATERAL, "NaN"));
        assertThrows(IllegalArgumentException.class,
            () -> ChairPanelLayout.applyRaw(layout, ChairPanelLayoutField.LEFT_LATERAL, "Infinity"));
    }

    /**
     * 落库精度四位；负零归成 +0.0；超大值不被取整静默改写。
     *
     * <p>最后一条是刻意的：布局按用户要求不设魔法上限，若把 1e13 也塞进
     * {@code Math.round(v*10000)/10000} 会被改成完全不同的数。宁可少一次取整。
     */
    @Test
    void storedValuesUseFourDecimalsWithoutManglingHugeNumbers() {
        ChairPanelLayout layout = ChairPanelLayout.defaults()
            .withFieldValue(ChairPanelLayoutField.LEFT_LATERAL, 0.123456)
            .withFieldValue(ChairPanelLayoutField.LEFT_DEPTH, -0.0);

        assertEquals(0.1235, layout.toSettings().left().lateral(), 1.0E-12);
        assertEquals(0, Double.compare(0.0, layout.toSettings().left().depth()), "负零必须归成 +0.0");
        assertEquals("0.0000", ChairPanelLayout.format(layout.toSettings().left().depth()));

        ChairPanelLayout huge = layout.withFieldValue(ChairPanelLayoutField.LEFT_LATERAL, 1.0E13);
        assertEquals(1.0E13, huge.toSettings().left().lateral(), 1.0,
            "超大值必须原样保留，不能被四位取整改成 long 上限");
    }

    /** 步长语义：位置/尺寸跟随玩家步长，角度项钉死 1°，×10 乘在当前步长上。 */
    @Test
    void stepSemanticsFollowPlayerStepExceptForAngles() {
        ChairPanelLayoutField position = ChairPanelLayoutField.LEFT_LATERAL;
        assertEquals(0.8, position.stepBy(0.75, 0.05, 1, true), 1.0E-12);
        assertEquals(0.70, position.stepBy(0.75, 0.05, 1, false), 1.0E-12);
        assertEquals(1.25, position.stepBy(0.75, 0.05, 10, true), 1.0E-12);
        assertEquals(0.7501, position.stepBy(0.75, 0.0001, 1, true), 1.0E-12);

        ChairPanelLayoutField angle = ChairPanelLayoutField.LEFT_YAW;
        assertTrue(angle.hasFixedStep(), "角度项必须钉死步长");
        assertEquals(36.0, angle.stepBy(35.0, 0.0001, 1, true), 1.0E-12,
            "角度项不吃玩家的 0.0001 步长，必须仍按 1 度走");
        assertEquals(45.0, angle.stepBy(35.0, 0.0001, 10, true), 1.0E-12);
    }

    /** 字段默认值必须与 schema 出厂布局一致（防止本类另抄一份默认值后漂移）。 */
    @Test
    void fieldDefaultsMatchTheSchemaDefaults() {
        ChairPanelLayout defaults = ChairPanelLayout.defaults();
        for (ChairPanelLayoutField field : ChairPanelLayoutField.values()) {
            assertEquals(defaults.valueOf(field), field.defaultValue(), 1.0E-12,
                field.name() + " 的默认值与 schema 出厂布局不一致");
        }
    }

    /** 字段标签唯一：概览屏按标签排按钮，重名会让玩家分不清在调哪一项。 */
    @Test
    void fieldLabelsAreUnique() {
        java.util.Set<String> labels = new java.util.HashSet<>();
        for (ChairPanelLayoutField field : ChairPanelLayoutField.values()) {
            assertTrue(labels.add(field.label()), "字段标签重复：" + field.label());
        }
    }

    /** 尺寸字段不属于任何一侧：误用要立刻报错，而不是悄悄改到左侧。 */
    @Test
    void sizeFieldsDoNotBelongToAnySide() {
        assertThrows(IllegalArgumentException.class, ChairPanelLayoutField.WIDTH::panelSide);
        assertThrows(IllegalArgumentException.class,
            () -> ChairPanelLayoutField.WIDTH.read(ChairSidePanelSettings.defaults().left()));
        assertThrows(IllegalArgumentException.class,
            () -> ChairPanelLayoutField.HEIGHT.write(ChairSidePanelSettings.defaults().right(), 1.0));
    }
}
