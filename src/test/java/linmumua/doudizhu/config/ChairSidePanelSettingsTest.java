package linmumua.doudizhu.config;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** 椅侧双面板布局的读取与镜像语义。 */
class ChairSidePanelSettingsTest {
    @TempDir
    Path tempDir;

    private MuzYamlConfig config(String yaml) throws IOException {
        Path file = tempDir.resolve("config.yml");
        Files.writeString(file, yaml, StandardCharsets.UTF_8);
        return new MuzYamlConfig(file);
    }

    @Test
    void missingSectionFallsBackToSymmetricDefaults() throws IOException {
        // 老配置升级后这一整段都还不存在，必须能直接用默认布局起来，
        // 而不是因为缺键就抛异常或把面板摆到原点。
        ChairSidePanelSettings settings = ChairSidePanelSettings.from(config("table:\n  spawn-offset-y: 0\n"));

        assertEquals(ChairSidePanelSettings.DEFAULT_ENABLED, settings.enabled());
        assertEquals(1.5, settings.width());
        assertEquals(1.0, settings.height());
        assertEquals(-1.2, settings.left().lateral());
        assertEquals(1.2, settings.right().lateral());
        assertEquals(ChairSidePanelSettings.mirrored(settings.left()), settings.right(),
            "出厂默认的左右两块必须互为精确镜像");
    }

    @Test
    void readsEveryFieldFromConfiguration() throws IOException {
        ChairSidePanelSettings settings = ChairSidePanelSettings.from(config("""
            chair-side-panels:
              enabled: false
              width: 1.5
              height: 1.0
              left:
                lateral: -1.35
                depth: 0.25
                vertical: 0.7
                pitch: -12.5
                yaw: 30
                roll: 5
              right:
                lateral: 1.35
                depth: 0.25
                vertical: 0.7
                pitch: -12.5
                yaw: -30
                roll: -5
            """));

        assertFalse(settings.enabled());
        assertEquals(-1.35, settings.left().lateral(), "左侧 lateral 是负值");
        assertEquals(0.25, settings.left().depth());
        assertEquals(0.7, settings.left().vertical());
        assertEquals(-12.5, settings.left().pitch());
        assertEquals(30.0, settings.left().yaw());
        assertEquals(5.0, settings.left().roll());
        assertEquals(-30.0, settings.right().yaw());
        assertEquals(-5.0, settings.right().roll());
    }

    @Test
    void singleSidedConfigurationKeepsTheOtherSideAtItsOwnDefault() throws IOException {
        // 只写 left 时，right 必须回到「右侧出厂默认」，不能因为 left 被改就跟着漂。
        ChairSidePanelSettings settings = ChairSidePanelSettings.from(config("""
            chair-side-panels:
              left:
                lateral: -2.0
                depth: 0.0
                vertical: 0.55
                pitch: 0.0
                yaw: 0.0
                roll: 0.0
            """));

        assertEquals(-2.0, settings.left().lateral());
        assertEquals(ChairSidePanelSettings.DEFAULT_LATERAL, settings.right().lateral());
    }

    @Test
    void mirrorNegatesLateralYawAndRollOnly() {
        // 镜像语义是对平面反射做旋转共轭的结果：lateral / yaw / roll 变号，
        // depth / vertical / pitch 不变。任何一处反了都会让右侧面板翻到椅子背面。
        ChairSidePanelSettings.PanelLayout left =
            new ChairSidePanelSettings.PanelLayout(-1.4, 0.3, 0.8, 17.5, 42.0, -9.0);
        ChairSidePanelSettings.PanelLayout right = ChairSidePanelSettings.mirrored(left);

        assertEquals(1.4, right.lateral());
        assertEquals(0.3, right.depth(), "深度沿前后轴，镜像不变");
        assertEquals(0.8, right.vertical(), "高度沿竖直轴，镜像不变");
        assertEquals(17.5, right.pitch(), "俯仰绕左右轴，镜像不变");
        assertEquals(-42.0, right.yaw(), "水平朝向必须变号，否则右侧面板会朝反方向");
        assertEquals(9.0, right.roll(), "自转必须变号");
        assertEquals(left, ChairSidePanelSettings.mirrored(right), "镜像必须是对合运算，来回一次回到原值");
    }

    @Test
    void mirrorIsExactForCompoundRotation() {
        // 用旋转矩阵验证「逐轴取反」不是近似：对 M = diag(-1,1,1) 做共轭 M·R·M 后
        // 结果必须等于 Ry(-yaw)·Rx(pitch)·Rz(-roll)。这里取一个三轴都不为零的角度组合。
        double pitch = Math.toRadians(23.0);
        double yaw = Math.toRadians(37.0);
        double roll = Math.toRadians(-14.0);
        ChairSidePanelSettings.PanelLayout left =
            new ChairSidePanelSettings.PanelLayout(-1.0, 0.0, 0.0, 23.0, 37.0, -14.0);
        ChairSidePanelSettings.PanelLayout right = ChairSidePanelSettings.mirrored(left);

        double[] source = rotation(pitch, yaw, roll);
        double[] mirroredDirect = rotation(
            Math.toRadians(right.pitch()), Math.toRadians(right.yaw()), Math.toRadians(right.roll()));
        double[] conjugated = conjugate(source);

        for (int index = 0; index < 9; index++) {
            assertEquals(conjugated[index], mirroredDirect[index], 1e-12,
                "第 " + index + " 个矩阵分量：逐轴取反的结果与真实镜像必须完全一致");
        }
    }

    @Test
    void mirroredLayoutReportsMatchingSide() {
        ChairSidePanelSettings.PanelLayout left =
            new ChairSidePanelSettings.PanelLayout(-1.2, 0.0, 0.55, 0.0, 0.0, 0.0);
        assertEquals(ChairSidePanelSettings.Side.LEFT, left.side());
        assertEquals(ChairSidePanelSettings.Side.RIGHT, ChairSidePanelSettings.mirrored(left).side());
    }

    @Test
    void rejectsNonFiniteNumbersInsteadOfClampingThem() throws IOException {
        // NaN / 无穷不会被下游的旋转与 AABB 计算接住，必须在解析层明确报错；
        // 静默钳成一个"看起来合法"的值会让面板位置莫名其妙，比直接报错难查得多。
        for (String value : List.of(".nan", ".inf", "-.inf")) {
            MuzYamlConfig nan = config("""
                chair-side-panels:
                  left:
                    lateral: %s
                    depth: 0.0
                    vertical: 0.55
                    pitch: 0.0
                    yaw: 0.0
                    roll: 0.0
                """.formatted(value));
            IllegalArgumentException exception = assertThrows(IllegalArgumentException.class,
                () -> ChairSidePanelSettings.from(nan), value + " 必须被拒绝");
            assertTrue(exception.getMessage().contains("chair-side-panels.left.lateral"),
                "报错必须带完整键路径，管理员才知道改哪一处：" + exception.getMessage());
            assertTrue(exception.getMessage().contains("有限"),
                "报错必须说明是有限性要求：" + exception.getMessage());
        }
    }

    @Test
    void rejectsNonNumericOffsets() throws IOException {
        MuzYamlConfig config = config("""
            chair-side-panels:
              right:
                lateral: "远一点"
                depth: 0
                vertical: 0
                pitch: 0
                yaw: 0
                roll: 0
            """);
        IllegalArgumentException exception = assertThrows(IllegalArgumentException.class,
            () -> ChairSidePanelSettings.from(config));
        assertTrue(exception.getMessage().contains("必须是数字"), exception.getMessage());
    }

    @Test
    void rejectsNonPositivePanelSize() throws IOException {
        // 0 或负尺寸造不出可渲染、可点击的面板，而且宽高同时决定命中框，
        // 放行会得到一个"看得见却点不到"的幽灵面板。
        for (String size : List.of("0", "-1.5")) {
            MuzYamlConfig zeroWidth = config("""
                chair-side-panels:
                  width: %s
                  height: 1.0
                """.formatted(size));
            assertThrows(IllegalArgumentException.class, () -> ChairSidePanelSettings.from(zeroWidth),
                "width=" + size + " 必须被拒绝");
        }
        MuzYamlConfig zeroHeight = config("""
            chair-side-panels:
              width: 1.5
              height: 0
            """);
        assertThrows(IllegalArgumentException.class, () -> ChairSidePanelSettings.from(zeroHeight));
    }

    @Test
    void rejectsSectionThatIsNotAMapping() throws IOException {
        // `chair-side-panels: false` 这种写法如果被当成"缺键 → 全用默认值"，
        // 管理员会以为关掉了面板、实际却全开，属于最该 fail-loud 的情形。
        for (String path : List.of("chair-side-panels", "chair-side-panels.left")) {
            MuzYamlConfig config = path.equals("chair-side-panels")
                ? config("chair-side-panels: false\n")
                : config("chair-side-panels:\n  left: 1.2\n");
            IllegalArgumentException exception = assertThrows(IllegalArgumentException.class,
                () -> ChairSidePanelSettings.from(config), path + " 非映射必须被拒绝");
            assertTrue(exception.getMessage().contains("必须是映射"), exception.getMessage());
        }
    }

    @Test
    void acceptsExtremeButFiniteLayouts() throws IOException {
        // 用户明确要求「无魔法硬极限」：摆得远、转得大是布局自由，只拒绝非有限值。
        ChairSidePanelSettings settings = ChairSidePanelSettings.from(config("""
            chair-side-panels:
              left:
                lateral: -42.5
                depth: -18.25
                vertical: 1234.5
                pitch: 720
                yaw: -1080
                roll: 359.9
            """));
        assertEquals(-42.5, settings.left().lateral());
        assertEquals(-18.25, settings.left().depth());
        assertEquals(1234.5, settings.left().vertical());
        assertEquals(720.0, settings.left().pitch());
        assertEquals(-1080.0, settings.left().yaw());
        assertEquals(359.9, settings.left().roll());
    }

    @Test
    void programmaticConstructionRejectsNonFiniteValues() {
        // 校验放在紧凑构造器里：从 Dialog 或其它代码路径直接 new 也不可能有 NaN 漏进来。
        assertThrows(IllegalArgumentException.class, () -> new ChairSidePanelSettings.PanelLayout(
            Double.NaN, 0.0, 0.0, 0.0, 0.0, 0.0));
        assertThrows(IllegalArgumentException.class, () -> new ChairSidePanelSettings.PanelLayout(
            0.0, Double.POSITIVE_INFINITY, 0.0, 0.0, 0.0, 0.0));
        assertThrows(IllegalArgumentException.class, () -> new ChairSidePanelSettings(
            true, Double.NaN, 1.0, ChairSidePanelSettings.defaults().left(),
            ChairSidePanelSettings.defaults().right()));
    }

    @Test
    void writeThenReadRoundTripsEveryField() throws IOException {
        // 读写必须共用同一套键路径：任何一侧拼错键都会让「保存成功但重载回旧值」。
        ChairSidePanelSettings original = ChairSidePanelSettings.defaults()
            .withEnabled(false)
            .withSize(1.75, 1.25)
            .withLayout(ChairSidePanelSettings.Side.LEFT,
                new ChairSidePanelSettings.PanelLayout(-1.35, 0.2, 0.9, -11.5, 28.0, 4.5))
            .withLayout(ChairSidePanelSettings.Side.RIGHT,
                new ChairSidePanelSettings.PanelLayout(1.35, 0.2, 0.9, -11.5, -28.0, -4.5));

        MuzYamlConfig config = config("chair-side-panels: {}\n");
        original.writeTo(config);

        ChairSidePanelSettings reloaded = ChairSidePanelSettings.from(config);
        assertEquals(original, reloaded);

        // 同时钉住实际键名：键名漂移会让老配置静默失效（缺键回落默认值）。
        assertFalse(config.getBoolean("chair-side-panels.enabled", true));
        assertEquals(1.35, config.getDouble("chair-side-panels.right.lateral", Double.NaN));
        assertEquals(-28.0, config.getDouble("chair-side-panels.right.yaw", Double.NaN));
        assertEquals(-4.5, config.getDouble("chair-side-panels.right.roll", Double.NaN));
        assertEquals(0.2, config.getDouble("chair-side-panels.left.depth", Double.NaN));
        assertEquals(0.9, config.getDouble("chair-side-panels.left.vertical", Double.NaN));
        assertEquals(-11.5, config.getDouble("chair-side-panels.left.pitch", Double.NaN));
    }

    @Test
    void layoutPathMatchesSideKeys() {
        assertEquals("chair-side-panels.left", ChairSidePanelSettings.layoutPath(ChairSidePanelSettings.Side.LEFT));
        assertEquals("chair-side-panels.right", ChairSidePanelSettings.layoutPath(ChairSidePanelSettings.Side.RIGHT));
        assertEquals(ChairSidePanelSettings.Side.RIGHT, ChairSidePanelSettings.Side.LEFT.opposite());
        assertEquals(-1.0, ChairSidePanelSettings.Side.LEFT.sign());
        assertEquals(1.0, ChairSidePanelSettings.Side.RIGHT.sign());
    }

    @Test
    void mirrorNeverProducesNegativeZero() {
        // 镜像把 0 取反会得到 -0.0，而 record 的 equals 走 Double.compare、判定 -0.0 与 0.0 不等。
        // 不归一的话「出厂默认」与「从配置读回的同一份值」会不相等，还会把 -0.0 写进 YAML。
        ChairSidePanelSettings.PanelLayout zero =
            new ChairSidePanelSettings.PanelLayout(0.0, 0.0, 0.0, 0.0, 0.0, 0.0);
        ChairSidePanelSettings.PanelLayout mirrored = ChairSidePanelSettings.mirrored(zero);

        assertEquals(zero, mirrored, "0 的镜像必须仍等于 0（不能变成 -0.0）");
        assertEquals(0.0, Double.compare(0.0, mirrored.lateral()));
        assertEquals(0.0, Double.compare(0.0, mirrored.yaw()));
        assertEquals(0.0, Double.compare(0.0, mirrored.roll()));
        assertTrue(Double.toString(mirrored.yaw()).equals("0.0"), "写进 YAML 的必须是 0.0 而不是 -0.0");
    }

    @Test
    void shippedConfigDeclaresTheDocumentedDefaults() throws IOException {
        // 打包模板是启动补全的唯一来源（mergeMissingFrom 按它补缺键）：
        // 模板里的默认值必须与代码默认值一致，否则新装与老服补出来的布局会不同。
        String template = Files.readString(Path.of("src/main/resources/config.yml"), StandardCharsets.UTF_8);
        Path file = tempDir.resolve("shipped.yml");
        Files.writeString(file, template, StandardCharsets.UTF_8);

        ChairSidePanelSettings shipped = ChairSidePanelSettings.from(new MuzYamlConfig(file));
        assertEquals(ChairSidePanelSettings.defaults(), shipped,
            "config.yml 的 chair-side-panels 段必须与 ChairSidePanelSettings.defaults() 完全一致");
    }

    @Test
    void shippedSectionIsCompleteSoOldConfigsGetEveryKeyBackfilled() throws IOException {
        // 缺键补全靠模板里有这个键。少写一个键，老服 /muz reload 永远补不回来。
        String template = Files.readString(Path.of("src/main/resources/config.yml"), StandardCharsets.UTF_8);
        Path file = tempDir.resolve("backfill.yml");
        Files.writeString(file, "table:\n  spawn-offset-y: 0\n", StandardCharsets.UTF_8);
        MuzYamlConfig config = new MuzYamlConfig(file);

        assertTrue(config.mergeMissingFrom(
            new ByteArrayInputStream(template.getBytes(StandardCharsets.UTF_8))),
            "老配置必须能从模板补出 chair-side-panels 段");

        for (ChairSidePanelSettings.Side side : ChairSidePanelSettings.Side.values()) {
            String base = ChairSidePanelSettings.layoutPath(side);
            for (String field : List.of("lateral", "depth", "vertical", "pitch", "yaw", "roll")) {
                assertNotEquals(null, config.get(base + "." + field),
                    base + "." + field + " 必须出现在打包模板里，否则老配置补不到这个键");
            }
        }
        assertEquals(1.5, config.getDouble("chair-side-panels.width", Double.NaN));
        assertEquals(1.0, config.getDouble("chair-side-panels.height", Double.NaN));
    }

    /** Ry(yaw)·Rx(pitch)·Rz(roll)，行主序九个分量。 */
    private static double[] rotation(double pitch, double yaw, double roll) {
        double cy = Math.cos(yaw);
        double sy = Math.sin(yaw);
        double cp = Math.cos(pitch);
        double sp = Math.sin(pitch);
        double cr = Math.cos(roll);
        double sr = Math.sin(roll);
        double[] ry = {cy, 0, sy, 0, 1, 0, -sy, 0, cy};
        double[] rx = {1, 0, 0, 0, cp, -sp, 0, sp, cp};
        double[] rz = {cr, -sr, 0, sr, cr, 0, 0, 0, 1};
        return multiply(ry, multiply(rx, rz));
    }

    /** M·R·M，其中 M = diag(-1, 1, 1)。 */
    private static double[] conjugate(double[] r) {
        double[] m = {-1, 0, 0, 0, 1, 0, 0, 0, 1};
        return multiply(m, multiply(r, m));
    }

    private static double[] multiply(double[] a, double[] b) {
        double[] result = new double[9];
        for (int row = 0; row < 3; row++) {
            for (int column = 0; column < 3; column++) {
                double sum = 0;
                for (int k = 0; k < 3; k++) {
                    sum += a[row * 3 + k] * b[k * 3 + column];
                }
                result[row * 3 + column] = sum;
            }
        }
        return result;
    }
}
