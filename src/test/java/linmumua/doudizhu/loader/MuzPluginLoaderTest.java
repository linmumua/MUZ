package linmumua.doudizhu.loader;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.papermc.paper.plugin.bootstrap.PluginProviderContext;
import io.papermc.paper.plugin.loader.PluginClasspathBuilder;
import io.papermc.paper.plugin.loader.PluginLoader;
import io.papermc.paper.plugin.loader.library.ClassPathLibrary;
import io.papermc.paper.plugin.loader.library.impl.MavenLibraryResolver;
import java.io.IOException;
import java.lang.reflect.Field;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import linmumua.doudizhu.MuzPluginLoader;
import org.eclipse.aether.artifact.Artifact;
import org.eclipse.aether.graph.Dependency;
import org.eclipse.aether.repository.RemoteRepository;
import org.junit.jupiter.api.Test;

/**
 * 守 {@link MuzPluginLoader} 的「坐标 + 仓库」契约。
 *
 * <p>【为什么这两件事必须被测试守住】1.10.54 起 gson / sqlite-jdbc / snakeyaml / TabooLib
 * common-reflex 及其 Kotlin、ASM、commons-lang3 全部不在插件 JAR 里，运行期能拿到它们【只】
 * 靠这个加载器声明得对。写错坐标 = 启动期 LibraryLoadingException；漏掉仓库 = 解析失败；
 * 把版本写成区间/快照 = 每次启动可能拿到不同的字节。这些都不是「少个功能」，是插件直接起不来，
 * 而且只在真实服务器上才暴露（单测编译期用的是 build.gradle.kts 那份坐标）。
 *
 * <p>【怎么测「注册」】{@code MavenLibraryResolver} 没有 getter，所以这里用一个只记录
 * {@code addLibrary} 的假 {@link PluginClasspathBuilder} 接住加载器交出的 {@code ClassPathLibrary}，
 * 再读 Aether 解析器的 {@code dependencies} / {@code repositories} 两个私有字段。
 * 反射读私有字段在测试里是本仓库既有做法（见 {@code MuzCommandLifecycleBehaviorTest} 等），
 * 生产代码不含反射。
 */
class MuzPluginLoaderTest {

    /**
     * 期望下载的全部坐标，逐条手写。
     *
     * <p>【为什么不直接引用 {@link MuzPluginLoader#DEPENDENCY_COORDINATES}】那样写的话，
     * 改加载器列表就能改绿这条测试，等于没测。这里独立写出「应该是什么」，两边不一致才报警。
     */
    private static final List<String> EXPECTED_DEPENDENCIES = List.of(
        "com.google.code.gson:gson:2.11.0",
        "org.yaml:snakeyaml:2.6",
        "org.xerial:sqlite-jdbc:3.46.1.0",
        "io.izzel.taboolib:common-reflex:6.3.0-75b18a2",
        "org.jetbrains.kotlin:kotlin-stdlib:2.3.20",
        "org.ow2.asm:asm:9.10.1",
        "org.apache.commons:commons-lang3:3.20.0"
    );

    @Test
    void implementsThePaperPluginLoaderContract() {
        assertTrue(
            PluginLoader.class.isAssignableFrom(MuzPluginLoader.class),
            "MuzPluginLoader 必须实现 io.papermc.paper.plugin.loader.PluginLoader，否则 loader 声明无法生效"
        );
        assertFalse(
            java.lang.reflect.Modifier.isAbstract(MuzPluginLoader.class.getModifiers()),
            "加载器不能是抽象类 —— Paper 会用无参构造实例化它"
        );
        // Paper 通过反射 newInstance()，必须是 public 且有无参构造。
        assertTrue(
            java.lang.reflect.Modifier.isPublic(MuzPluginLoader.class.getModifiers()),
            "加载器类必须是 public"
        );
        // Paper 用反射 newInstance() 创建它：public 无参构造必须真的可调用。
        assertNotNull(new MuzPluginLoader(), "加载器必须能被无参构造实例化");
    }

    @Test
    void coordinatesAreExactlyTheExternalizedSet() {
        // 集合比较而不是 List 比较：加载器把第一条当 Aether 的根依赖、其余当同级依赖，
        // 顺序无语义；真正要守的是「这 7 条不多不少」。
        assertEquals(
            EXPECTED_DEPENDENCIES.size(),
            MuzPluginLoader.DEPENDENCY_COORDINATES.size(),
            "外置依赖数量变了。少一条 = 运行期 NoClassDefFoundError；多一条 = 悄悄多下载一个库，"
                + "两份清单必须一起改：" + MuzPluginLoader.DEPENDENCY_COORDINATES
        );
        assertTrue(
            MuzPluginLoader.DEPENDENCY_COORDINATES.containsAll(EXPECTED_DEPENDENCIES),
            "外置依赖清单与期望不一致：" + MuzPluginLoader.DEPENDENCY_COORDINATES
        );
    }

    @Test
    void everyCoordinateIsAPinnedVersion() {
        for (String coordinate : MuzPluginLoader.DEPENDENCY_COORDINATES) {
            String[] parts = coordinate.split(":");
            assertEquals(3, parts.length, "坐标必须是 groupId:artifactId:version 的三段式：" + coordinate);
            assertFalse(parts[0].isBlank(), "groupId 不能为空：" + coordinate);
            assertFalse(parts[1].isBlank(), "artifactId 不能为空：" + coordinate);
            String version = parts[2];
            assertFalse(
                version.toUpperCase().contains("SNAPSHOT"),
                "不允许 SNAPSHOT —— 同一份插件在不同时间启动会拿到不同字节：" + coordinate
            );
            assertFalse(
                version.contains("[") || version.contains("]") || version.contains(",")
                    || version.contains("+") || version.contains("LATEST") || version.contains("RELEASE"),
                "不允许版本区间或动态版本 —— 运行期加载的必须是固定版本：" + coordinate
            );
        }
    }

    @Test
    void classloaderRegistersThoseCoordinatesWithTheResolver() {
        MavenLibraryResolver resolver = onlyResolverRegisteredByLoader();
        List<String> coordinates = readResolverDependencies(resolver).stream()
            .map(dependency -> {
                Artifact artifact = dependency.getArtifact();
                return artifact.getGroupId() + ":" + artifact.getArtifactId() + ":" + artifact.getVersion();
            })
            .sorted()
            .toList();
        assertEquals(
            EXPECTED_DEPENDENCIES.stream().sorted().toList(),
            coordinates,
            "加载器交给 Aether 的坐标与期望不一致；这些坐标同时必须与 build.gradle.kts 的"
                + " compileOnly / testImplementation 完全一致（由 MuzPluginLoaderExternalizationContractTest 守护）"
        );
    }

    @Test
    void declaredScopesAreNotExcludedFromTransitiveResolution() {
        // gson 的 error_prone_annotations、sqlite-jdbc 的 slf4j-api、kotlin-stdlib 的
        // org.jetbrains:annotations 都是【传递依赖】。Aether 的默认选择器会排除 test / provided
        // 作用域，所以根依赖的作用域一旦被写成这两个，传递依赖就静默消失，
        // 表现是运行期在真正用到那些类时才 NoClassDefFoundError。
        for (Dependency dependency : readResolverDependencies(onlyResolverRegisteredByLoader())) {
            String scope = dependency.getScope();
            assertFalse(
                "test".equals(scope) || "provided".equals(scope),
                "根依赖作用域不能是 test/provided，否则传递依赖不会被收集：" + dependency.getArtifact()
            );
        }
    }

    @Test
    void mavenCentralIsReachedThroughPapersMirrorNotDirectly() {
        RemoteRepository central = repositoryById(MuzPluginLoader.CENTRAL_REPOSITORY_ID);
        assertEquals(
            MavenLibraryResolver.MAVEN_CENTRAL_DEFAULT_MIRROR,
            central.getUrl(),
            "Central 必须走 Paper 提供的镜像常量（可由 PAPER_DEFAULT_CENTRAL_REPOSITORY 环境变量或"
                + " org.bukkit.plugin.java.LibraryLoader.centralURL 系统属性替换），不得硬编码域名"
        );
        // 官方文档明确要求：直接把 *.maven.org / *.maven.apache.org 当 CDN 违反 Maven Central
        // 的服务条款，并会让使用者被限流。这里守的就是「别把它当中央仓库直连地址用」。
        assertFalse(
            central.getUrl().contains("repo1.maven.org") || central.getUrl().contains("repo.maven.apache.org"),
            "不得把原始 Maven Central 地址当仓库直连：" + central.getUrl()
        );
        assertEquals("central", central.getId(), "镜像仓库 id 应保持 central，便于运维识别");
    }

    @Test
    void taboolibRepositoryIsRegisteredForCommonReflex() {
        // 2026-09-24 实测：io.izzel.taboolib:common-reflex 在 Central 镜像上 404、
        // 在 TabooLib 官方仓库上 200，所以这个仓库不是可选项。
        RemoteRepository taboolib = repositoryById(MuzPluginLoader.TABOOLIB_REPOSITORY_ID);
        assertEquals(
            MuzPluginLoader.TABOOLIB_REPOSITORY_URL,
            taboolib.getUrl(),
            "TabooLib 仓库地址变了？common-reflex 只在这里发布，改错就下载不到"
        );
        assertTrue(
            MuzPluginLoader.TABOOLIB_REPOSITORY_URL.endsWith("/"),
            "仓库地址必须以 / 结尾，否则 Aether 拼接路径会出错：" + taboolib.getUrl()
        );
    }

    @Test
    void loaderRegistersExactlyOneLibraryAndNoOtherClasspathEntry() {
        // 加载器只应通过 MavenLibraryResolver 提供库；顺手 addLibrary(new JarLibrary(...)) 之类的
        // 本地文件依赖在真实服务器上路径不可控，这里钉住「只有一条注册」。
        assertEquals(
            1,
            captureLibraries(new MuzPluginLoader()).size(),
            "加载器应只注册一个 MavenLibraryResolver"
        );
    }

    @Test
    void loaderOnlyDependsOnJdkPaperAndAether() throws IOException {
        // 加载器是在插件类加载器建立【之前】被实例化的，那时插件 JAR 里的业务类与所有第三方库
        // 都还不可见 —— 任何多余 import 都会在真实服务器上以 NoClassDefFoundError 炸在插件加载
        // 阶段。这条直接扫描它的 import 语句，把「只允许 JDK / Paper loader API / Aether」写成
        // 可执行契约，而不是靠注释。
        Path source = Path.of("src/main/java/linmumua/doudizhu/MuzPluginLoader.java");
        assertTrue(Files.isRegularFile(source), "找不到加载器源码：" + source);
        List<String> imports = Files.readAllLines(source, StandardCharsets.UTF_8).stream()
            .map(String::trim)
            .filter(line -> line.startsWith("import "))
            .map(line -> line.substring("import ".length()).replace(";", "").trim())
            .filter(line -> !line.startsWith("static "))
            .toList();
        assertFalse(imports.isEmpty(), "加载器的 import 解析为空，测试自身失效了");
        for (String imported : imports) {
            boolean allowed = imported.startsWith("java.")
                || imported.startsWith("io.papermc.paper.plugin.loader.")
                || imported.startsWith("io.papermc.paper.plugin.bootstrap.")
                || imported.startsWith("org.eclipse.aether.");
            assertTrue(
                allowed,
                "加载器只能依赖 JDK / Paper loader API / Aether，出现了非法 import：" + imported
                    + "（完整清单：" + imports + "）"
            );
        }
    }

    @Test
    void loaderDoesNotSetAnyUserAgent() throws IOException {
        // 用户明确要求不要把 User-Agent 硬编码进插件。Aether 的 UA 是 Paper/服务端配置的事。
        // 【为什么不直接在这份源码里查关键词】加载器的 Javadoc 正【在说明】
        // 「本类不设置任何 User-Agent」这件事，直接全文匹配会把它自己的说明文字当成违规。
        // 所以先剥掉注释与字符串字面量，只在真正的代码上判定。
        String code = stripCommentsAndStrings(
            Files.readString(Path.of("src/main/java/linmumua/doudizhu/MuzPluginLoader.java"))
        );
        String lower = code.toLowerCase(java.util.Locale.ROOT);
        assertFalse(
            lower.contains("user-agent") || lower.contains("useragent"),
            "加载器的代码（注释除外）不得出现 User-Agent —— 不要把它硬编码进插件"
        );
        // 顺带钉住「也没有自己发 HTTP 请求/RPC 的能力」：加载器只负责登记库坐标与仓库，
        // 真正的下载由 Paper 的解析器完成。
        for (String forbidden : List.of("HttpURLConnection", "HttpClient", "Socket", "openConnection")) {
            assertFalse(
                lower.contains(forbidden.toLowerCase(java.util.Locale.ROOT)),
                "加载器不应自己发网络请求：" + forbidden
            );
        }
    }

    /**
     * 剥掉 Java 源码里的注释与字符串/字符字面量，只留真正的代码字符。
     *
     * <p>用状态机而不是正则：加载器里有 {@code "https://repo.tabooproject.org/..."} 这样的
     * 字符串，其中 {@code //} 会被朴素正则误判成行注释起点，把该行后半截的代码一起吃掉。
     *
     * @param source 源码
     * @return 去掉注释与字面量的代码
     */
    private static String stripCommentsAndStrings(String source) {
        StringBuilder out = new StringBuilder(source.length());
        int index = 0;
        while (index < source.length()) {
            char current = source.charAt(index);
            char next = index + 1 < source.length() ? source.charAt(index + 1) : '\0';
            if (current == '/' && next == '/') {
                while (index < source.length() && source.charAt(index) != '\n') {
                    index++;
                }
            } else if (current == '/' && next == '*') {
                index += 2;
                while (index + 1 < source.length() && !(source.charAt(index) == '*' && source.charAt(index + 1) == '/')) {
                    index++;
                }
                index = Math.min(source.length(), index + 2);
            } else if (current == '"' || current == '\'') {
                char quote = current;
                index++;
                while (index < source.length() && source.charAt(index) != quote) {
                    if (source.charAt(index) == '\\') {
                        index++;
                    }
                    index++;
                }
                index++;
            } else {
                out.append(current);
                index++;
            }
        }
        return out.toString();
    }

    /**
     * 用只记录 addLibrary 的假 builder 调用生产加载器，返回它唯一注册的解析器。
     *
     * @return 加载器注册的 {@link MavenLibraryResolver}
     */
    private static MavenLibraryResolver onlyResolverRegisteredByLoader() {
        List<ClassPathLibrary> libraries = captureLibraries(new MuzPluginLoader());
        assertEquals(1, libraries.size(), "加载器应只注册一个库条目");
        return assertInstanceOf(MavenLibraryResolver.class, libraries.get(0));
    }

    /**
     * 调用加载器的 {@code classloader}，记录它注册的库条目。
     *
     * @param loader 被测加载器
     * @return 注册顺序的库条目
     */
    private static List<ClassPathLibrary> captureLibraries(PluginLoader loader) {
        List<ClassPathLibrary> captured = new ArrayList<>();
        PluginClasspathBuilder builder = new PluginClasspathBuilder() {
            @Override
            public PluginClasspathBuilder addLibrary(ClassPathLibrary library) {
                captured.add(library);
                return this;
            }

            @Override
            public PluginProviderContext getContext() {
                // 生产加载器不应访问 context（它只用 addLibrary）；真被调到就是实现跑偏了。
                throw new AssertionError("插件加载器不应访问 PluginClasspathBuilder.getContext()");
            }
        };
        loader.classloader(builder);
        return captured;
    }

    /**
     * 读解析器私有 {@code dependencies} 字段。
     *
     * @param resolver 解析器
     * @return 已登记的 Aether 依赖列表
     */
    @SuppressWarnings("unchecked")
    private static List<Dependency> readResolverDependencies(MavenLibraryResolver resolver) {
        return (List<Dependency>) readResolverField(resolver, "dependencies");
    }

    /**
     * 读解析器私有 {@code repositories} 字段。
     *
     * @param resolver 解析器
     * @return 已登记的远程仓库列表
     */
    @SuppressWarnings("unchecked")
    private static List<RemoteRepository> readResolverRepositories(MavenLibraryResolver resolver) {
        return (List<RemoteRepository>) readResolverField(resolver, "repositories");
    }

    /**
     * 按 id 取已注册仓库，找不到就失败。
     *
     * @param id 仓库 id
     * @return 对应仓库
     */
    private static RemoteRepository repositoryById(String id) {
        List<RemoteRepository> repositories = readResolverRepositories(onlyResolverRegisteredByLoader());
        RemoteRepository found = repositories.stream()
            .filter(repository -> id.equals(repository.getId()))
            .findFirst()
            .orElse(null);
        assertNotNull(found, "加载器没有注册 id 为 '" + id + "' 的仓库；实际注册：" + repositories.stream()
            .map(repository -> repository.getId() + " -> " + repository.getUrl()).toList());
        return found;
    }

    /**
     * 反射读 {@link MavenLibraryResolver} 的私有字段。
     *
     * @param resolver 解析器
     * @param name 字段名
     * @return 字段值
     */
    private static Object readResolverField(MavenLibraryResolver resolver, String name) {
        try {
            Field field = MavenLibraryResolver.class.getDeclaredField(name);
            field.setAccessible(true);
            return field.get(resolver);
        } catch (ReflectiveOperationException error) {
            throw new AssertionError(
                "读取 MavenLibraryResolver." + name + " 失败 —— Paper 改了内部字段名？本测试需要跟着更新",
                error
            );
        }
    }
}
