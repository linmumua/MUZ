package linmumua.doudizhu;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import linmumua.doudizhu.config.MuzYamlConfig;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * {@code debug.*} 缺键补回的行为契约。
 *
 * <p>用户报告：{@code /muz reload} 后 config.yml 里缺失的 {@code debug.enabled} 没有被补回。
 * 根因是「补键完全依赖打包模板里存在该键」——{@code mergeDefaultYamlConfig()} 走
 * {@code mergeMissingFrom(打包模板)}，模板缺键就补不回来（1.10.57 及更早的包里 {@code debug} 段
 * 只有 table-spacing 与 web-ui，{@code debug.enabled} 是 1.10.58 才进模板的）。所以本轮加了
 * 与模板无关的 {@link DoudizhuPlugin#ensureDebugConfig(MuzYamlConfig)}。
 *
 * <p>【这些断言为什么能变红】：第一条用「模板缺 debug.enabled」的夹具复现了用户看到的场景，
 * 只要 ensureDebugConfig 被删掉或改回「只在键已存在时才补」，它就是失败的。
 */
class DebugConfigIntegrityTest {
    @TempDir
    Path tempDir;

    private static Path template() {
        return Path.of("src/main/resources/config.yml");
    }

    private MuzYamlConfig write(String name, String content) throws IOException {
        Path file = tempDir.resolve(name);
        Files.writeString(file, content, StandardCharsets.UTF_8);
        return new MuzYamlConfig(file);
    }

    @Test
    void 模板缺debug_enabled时补键仍然生效() throws IOException {
        // 复现 1.10.57 的模板形态：debug 段只有 table-spacing 与 web-ui，没有 enabled。
        String legacyTemplate = """
            debug:
              table-spacing: 0
              web-ui:
                enabled: false
                port: 2000
            """;
        MuzYamlConfig config = write("legacy.yml", legacyTemplate);
        try (InputStream in = new java.io.ByteArrayInputStream(
            legacyTemplate.getBytes(StandardCharsets.UTF_8))) {
            assertFalse(config.mergeMissingFrom(in), "合并旧模板不会带来 debug.enabled");
        }
        assertFalse(config.contains("debug.enabled"), "前置条件：合并后仍然缺键");

        assertTrue(DoudizhuPlugin.ensureDebugConfig(config), "缺键必须报告变化以触发落盘");
        assertTrue(config.contains("debug.enabled"), "补键后 debug.enabled 必须存在，否则门禁永远读不到值");
        assertEquals(false, config.getBoolean("debug.enabled", true), "缺键补的默认值必须是 false");
    }

    @Test
    void 缺table_spacing时一并补回() throws IOException {
        MuzYamlConfig config = write("no-spacing.yml", "debug:\n  enabled: true\n");

        assertTrue(DoudizhuPlugin.ensureDebugConfig(config));

        assertTrue(config.contains("debug.table-spacing"), "debug.table-spacing 也必须缺键才补");
        assertEquals(0.0, config.getDouble("debug.table-spacing", -1.0), 1e-9,
            "补 0 表示沿用源码默认值 6.5 的既有语义，不能写成 6.5 覆盖掉这条语义");
        assertEquals(true, config.getBoolean("debug.enabled", false), "已有值不得被覆盖");
    }

    @Test
    void 已存在的用户值一律原样保留() throws IOException {
        MuzYamlConfig config = write("user.yml",
            "debug:\n  enabled: true\n  table-spacing: 9.5\n");

        assertFalse(DoudizhuPlugin.ensureDebugConfig(config), "两个键都在时不应报告变化");

        assertEquals(true, config.getBoolean("debug.enabled", false), "用户打开的日志门禁不能被关掉");
        assertEquals(9.5, config.getDouble("debug.table-spacing", -1.0), 1e-9);
    }

    @Test
    void 真实模板下补键写入磁盘并保留键上注释() throws IOException {
        // 端到端：真实模板 + 缺键文件 → 补键 → 按模板写出 → 重新读取仍带注释。
        Path file = tempDir.resolve("real.yml");
        Files.writeString(file, "debug:\n  table-spacing: 0\n", StandardCharsets.UTF_8);
        String template = Files.readString(template(), StandardCharsets.UTF_8);
        MuzYamlConfig config = new MuzYamlConfig(file);
        assertTrue(DoudizhuPlugin.ensureDebugConfig(config));
        config.saveWithComments(template);

        String serialized = Files.readString(file, StandardCharsets.UTF_8);
        assertTrue(serialized.contains("是否输出 MUZ 的 WARNING 日志"),
            "补回的键必须带上模板注释，否则玩家看不到取值说明：\n" + serialized);
        MuzYamlConfig reloaded = new MuzYamlConfig(file);
        assertEquals(false, reloaded.getBoolean("debug.enabled", true), "落盘后重新读取必须拿到补回的值");
    }

    @Test
    void 补键接线在ensureConfigIntegrity内且会触发保存() throws IOException {
        String source = Files.readString(Path.of("src/main/java/linmumua/doudizhu/DoudizhuPlugin.java"));
        int at = source.indexOf("private void ensureConfigIntegrity()");
        assertTrue(at > 0, "ensureConfigIntegrity 应当存在");
        String body = source.substring(at, Math.min(source.length(), at + 1200));
        assertTrue(body.contains("changed |= ensureDebugConfig()"),
            "补键必须纳入 changed，否则只进内存不落盘");
        assertTrue(source.contains("static boolean ensureDebugConfig(MuzYamlConfig config)"),
            "补键核心必须可直接驱动测试，而不是只能靠源码扫描");
    }
}
