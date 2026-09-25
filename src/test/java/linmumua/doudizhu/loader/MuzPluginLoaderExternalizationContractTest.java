package linmumua.doudizhu.loader;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.stream.Stream;
import linmumua.doudizhu.MuzPluginLoader;
import org.junit.jupiter.api.Test;

/**
 * 把「全部第三方运行期库已外置」写成源码级契约。
 *
 * <p>【为什么需要源码契约而不只靠构建门禁】{@code verifyRelocatedSnakeYaml} 与
 * {@code build/muz-release-audit.py} 检查的是【产物】——它们能发现「JAR 里还有 gson」，
 * 但发现不了「构建脚本又把这个库塞回 embeddedLibraries」的意图，也发现不了「源码里出现
 * 指向旧 relocate 目标包 linmumua.doudizhu.libs.* 的引用」这类更早就能拦下的错误。
 * 这里补的是构建前的快速失败，与产物门禁互为补充，不是替代。
 */
class MuzPluginLoaderExternalizationContractTest {

    /** 本版本外置的库在源码里的坐标片段（group:artifact），用于确认 build 侧与加载器侧一致。 */
    private static final List<String> EXTERNALIZED_ARTIFACTS = List.of(
        "com.google.code.gson:gson",
        "org.yaml:snakeyaml",
        "org.xerial:sqlite-jdbc",
        "io.izzel.taboolib:common-reflex",
        "org.jetbrains.kotlin:kotlin-stdlib",
        "org.ow2.asm:asm",
        "org.apache.commons:commons-lang3"
    );

    @Test
    void buildScriptEmbedsNothingAndRelocatesNothing() throws IOException {
        String script = read(Path.of("build.gradle.kts"));
        // relocate 是「改名躲冲突」手段，前提是库被内嵌。外置之后业务字节码必须继续指向
        // 原始包名，任何 relocate 都会把它改到下载包里不存在的路径 —— 这是本版本最容易
        // 被「顺手补回来」的地方，所以直接禁止出现该调用。
        assertFalse(
            script.contains("relocate("),
            "build.gradle.kts 不得再有 relocate：库已按原始坐标运行期下载，改名会让业务引用指向不存在的包路径"
        );
        // embeddedLibraries 只允许保持空集：任何 `embeddedLibraries("...")` 都意味着又有库被打进 JAR。
        assertFalse(
            script.contains("embeddedLibraries(\""),
            "embeddedLibraries 必须保持为空（外置后不得再往最终 JAR 里塞第三方库）"
        );
        // 外置依赖此前是用 implementation 进包的；它进 runtimeClasspath，是「被 shadow 合并」的前提。
        // 现在它们只允许以 compileOnly（编译期）与 testImplementation（测试期）出现。
        assertFalse(
            script.contains("implementation("),
            "外置依赖不得再以 implementation 声明 —— 它会把库重新带进产物打包范围"
        );
        // 旧的重定位目标包名不允许再作为【字符串字面量】出现在构建脚本里（注释里提到它是允许的，
        // 因为注释在记录历史；但一旦成为带引号的字面量，通常就是 relocate 或包名断言回来了）。
        assertFalse(
            script.contains("\"linmumua.doudizhu.libs"),
            "构建脚本里不应再出现旧 relocate 目标包的字面量"
        );
    }

    @Test
    void buildScriptAndLoaderAgreeOnEveryCoordinate() throws IOException {
        String script = read(Path.of("build.gradle.kts"));
        for (String artifact : EXTERNALIZED_ARTIFACTS) {
            assertTrue(
                script.contains("\"" + artifact + ":"),
                "build.gradle.kts 里没有外置坐标 " + artifact + ": —— 编译期/测试期与运行期下载的必须是同一批库"
            );
        }
        // 版本号也必须逐条出现在构建脚本里：改了加载器的版本却不同步 build 侧，
        // 就会出现「编译通过的类」与「运行期加载的类」不是同一份。
        for (String coordinate : MuzPluginLoader.DEPENDENCY_COORDINATES) {
            String version = coordinate.substring(coordinate.lastIndexOf(':') + 1);
            assertTrue(
                script.contains(version),
                "build.gradle.kts 里没有版本字面量 " + version + "（来自 " + coordinate + "），两侧版本已漂移"
            );
        }
    }

    @Test
    void mainSourcesNeverReferenceTheOldRelocationTargetPackage() throws IOException {
        // 旧产物把 snakeyaml/asm/commons-lang3 重定位到 linmumua.doudizhu.libs.*。
        // 源码一旦引用那个包，外置后必然 NoClassDefFoundError。
        List<Path> offenders;
        try (Stream<Path> sources = Files.walk(Path.of("src/main/java"))) {
            offenders = sources
                .filter(path -> path.toString().endsWith(".java"))
                .filter(path -> {
                    try {
                        return Files.readString(path, StandardCharsets.UTF_8).contains("linmumua.doudizhu.libs");
                    } catch (IOException error) {
                        return false;
                    }
                })
                .toList();
        }
        assertTrue(
            offenders.isEmpty(),
            "主源码不得引用旧 relocate 目标包 linmumua.doudizhu.libs.*：" + offenders
        );
    }

    @Test
    void jarPackagingStaysWiredToTheLoaderInsteadOfEmbedding() throws IOException {
        String script = read(Path.of("build.gradle.kts"));
        // 产物必须真的声明加载器；否则外置依赖永远不会被下载。
        assertTrue(
            script.contains("MuzPluginLoader") || read(Path.of("src/main/resources/paper-plugin.yml"))
                .contains("linmumua.doudizhu.MuzPluginLoader"),
            "构建/描述文件里找不到 MuzPluginLoader 的装配，外置依赖不会被下载"
        );
    }

    /**
     * 读一个项目根相对路径的文本文件。
     *
     * @param relative 相对项目根的路径
     * @return 文件内容（UTF-8）
     * @throws IOException 读不到文件
     */
    private static String read(Path relative) throws IOException {
        assertTrue(Files.isRegularFile(relative), "找不到文件：" + relative.toAbsolutePath());
        return Files.readString(relative, StandardCharsets.UTF_8);
    }
}
