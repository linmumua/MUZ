package linmumua.doudizhu.loader;

import io.papermc.paper.plugin.bootstrap.PluginProviderContext;
import io.papermc.paper.plugin.loader.PluginClasspathBuilder;
import io.papermc.paper.plugin.loader.library.ClassPathLibrary;
import io.papermc.paper.plugin.loader.library.LibraryStore;
import io.papermc.paper.plugin.loader.library.impl.MavenLibraryResolver;
import java.io.InputStream;
import java.lang.reflect.Field;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.sql.Connection;
import java.sql.Driver;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import linmumua.doudizhu.MuzPluginLoader;
import org.apache.maven.repository.internal.MavenRepositorySystemUtils;
import org.eclipse.aether.DefaultRepositorySystemSession;
import org.eclipse.aether.RepositorySystem;
import org.eclipse.aether.collection.CollectRequest;
import org.eclipse.aether.connector.basic.BasicRepositoryConnectorFactory;
import org.eclipse.aether.graph.Dependency;
import org.eclipse.aether.impl.DefaultServiceLocator;
import org.eclipse.aether.repository.LocalRepository;
import org.eclipse.aether.repository.RemoteRepository;
import org.eclipse.aether.resolution.DependencyRequest;
import org.eclipse.aether.resolution.DependencyResult;
import org.eclipse.aether.spi.connector.RepositoryConnectorFactory;
import org.eclipse.aether.spi.connector.transport.TransporterFactory;
import org.eclipse.aether.transport.http.HttpTransporterFactory;

/**
 * 外置依赖的【真实依赖解析 + 本地缓存 + 类加载】探针。
 *
 * <p>【这个探针证明什么，不证明什么】
 * <ul>
 *   <li>在线解析阶段直接调用【Paper 自己的】{@code MavenLibraryResolver.register(LibraryStore)}
 *       —— 也就是真实服务器启动时走的同一段代码（同样的会话设置、同样的 checksumPolicy=fail、
 *       同样的 {@code LocalRepository("libraries")}），所以它能证明「生产加载器登记的坐标与仓库
 *       确实解析得出 JAR」；</li>
 *   <li>离线阶段用同一份缓存目录、{@code setOffline(true)} 再解析一次，成功即证明「缓存齐全、
 *       断网也能启动」；</li>
 *   <li>随后用【父加载器 = 平台类加载器】的 URLClassLoader 把下载来的 JAR 加载起来并实际调用：
 *       SnakeYAML 解析 YAML、Gson 序列化、sqlite-jdbc 建表插查、TabooLib reflex 分析类并按名字
 *       取方法后调用，另外确认 ASM / commons-lang3 / kotlin-stdlib 都能加载。</li>
 * </ul>
 * <b>不证明</b>：真实 Paper/Folia 服务端的加载行为。本探针不是服务端；「插件加载器类加载顺序」
 * 「真实服务端首次启动」「离线首启的失败表现」仍必须由实服启动验证，不能由本探针替代。
 *
 * <p>【为什么用 {@code LocalRepository("libraries")} 的相对路径这件事很重要】Paper 的解析器用
 * {@code new LocalRepository("libraries")}，是相对路径，落点取决于进程工作目录。Gradle 任务因此
 * 把探针的 workingDir 设为传给本探针的缓存根目录，于是 Paper 的缓存落在
 * {@code <缓存根>/libraries/} —— 与真实服务器的 {@code <服务端根>/libraries/} 同构。
 * 离线阶段也指向同一个 {@code libraries/}，两边看的是同一份缓存。
 *
 * <p>用法：{@code java -cp <testRuntimeClasspath> linmumua.doudizhu.loader.MuzExternalDependencyProbe <cacheRoot>}，
 * 由 Gradle 任务 {@code muzExternalDependencyProbe} 驱动（它会设好 workingDir）。
 */
public final class MuzExternalDependencyProbe {

    /** 与 Paper 一致：校验和不符即失败，不静默降级。 */
    private static final String CHECKSUM_POLICY = "fail";

    /** Paper 解析器的本地仓库目录名（相对 workingDir）——见类注释。 */
    private static final String PAPER_LOCAL_REPOSITORY_DIR = "libraries";

    private static final String SQLITE_DRIVER_CLASS = "org.sqlite.JDBC";
    private static final String SNAKEYAML_CLASS = "org.yaml.snakeyaml.Yaml";
    private static final String GSON_CLASS = "com.google.gson.Gson";
    private static final String REFLEX_ANALYSER_CLASS = "taboolib.library.reflex.ClassAnalyser";
    private static final String REFLEX_MODE_CLASS = "taboolib.library.reflex.AnalyseMode";
    private static final String REFLEX_CLASS_CLASS = "taboolib.library.reflex.ReflexClass";
    private static final String REFLEX_STRUCTURE_CLASS = "taboolib.library.reflex.ClassStructure";
    private static final String ASM_CLASS = "org.objectweb.asm.ClassReader";
    private static final String COMMONS_LANG3_CLASS = "org.apache.commons.lang3.StringUtils";
    private static final String KOTLIN_STDLIB_CLASS = "kotlin.jvm.internal.Intrinsics";

    private MuzExternalDependencyProbe() {
    }

    /**
     * 探针入口。
     *
     * @param args 第一个参数是缓存根目录（必填；Gradle 任务会同时把它设为 workingDir）
     */
    public static void main(String[] args) {
        if (args.length < 1 || args[0].isBlank()) {
            System.err.println("用法：MuzExternalDependencyProbe <cacheRoot>");
            System.exit(2);
            return;
        }
        Path cacheRoot = Path.of(args[0]).toAbsolutePath();
        Path paperCache = cacheRoot.resolve(PAPER_LOCAL_REPOSITORY_DIR);
        List<String> failures = new ArrayList<>();
        Map<String, Object> report = new LinkedHashMap<>();
        report.put("cacheRoot", cacheRoot.toString());
        report.put("paperLocalRepository", paperCache.toString());

        try {
            // ---- 0. 取生产加载器【真正登记】的解析器 ----
            MavenLibraryResolver resolver = captureProductionResolver();
            List<Dependency> dependencies = readField(resolver, "dependencies");
            List<RemoteRepository> repositories = readField(resolver, "repositories");
            report.put("registeredDependencies",
                dependencies.stream().map(d -> d.getArtifact().toString()).toList());
            report.put("registeredRepositories",
                repositories.stream().map(r -> r.getId() + " -> " + r.getUrl()).toList());
            report.put("cacheJarsBefore", countJars(paperCache));

            List<Path> jars;
            try {
                // ---- 1a. 在线解析：直接跑 Paper 自己的 register() ----
                jars = registerWithPaperResolver(resolver);
                report.put("onlineResolution", "ok");
                report.put("onlineArtifacts", jars.size());
            } catch (Exception error) {
                failures.add("在线解析失败（生产坐标/仓库不可用）：" + error);
                report.put("onlineResolution", "failed: " + error);
                emit(report, failures);
                System.exit(1);
                return;
            }
            report.put("cacheJarsAfter", countJars(paperCache));
            report.put("onlineTotalBytes", jars.stream().mapToLong(MuzExternalDependencyProbe::sizeOf).sum());

            // ---- 1b. 离线解析：同一份缓存 + setOffline(true) ----
            try {
                resolveOffline(dependencies, repositories, paperCache);
                report.put("offlineResolution", "ok");
            } catch (Exception error) {
                failures.add("离线（缓存）解析失败 —— 缓存不完整，真实服务器断网首启会加载失败：" + error);
                report.put("offlineResolution", "failed: " + error);
            }

            // ---- 2. 工件指纹 ----
            Map<String, String> fingerprints = new LinkedHashMap<>();
            for (Path jar : jars) {
                fingerprints.put(jar.getFileName().toString(), sha256(jar) + " (" + sizeOf(jar) + " bytes)");
            }
            report.put("artifacts", fingerprints);

            // ---- 3. 隔离类加载器实际调用 ----
            try (URLClassLoader loader = new URLClassLoader(
                jars.stream().map(MuzExternalDependencyProbe::toUrl).toArray(URL[]::new),
                ClassLoader.getPlatformClassLoader()
            )) {
                probeSnakeYaml(loader, report, failures);
                probeGson(loader, report, failures);
                probeSqlite(loader, cacheRoot, report, failures);
                probeReflex(loader, report, failures);
                probeLoadable(loader, ASM_CLASS, report, failures);
                probeLoadable(loader, COMMONS_LANG3_CLASS, report, failures);
                probeLoadable(loader, KOTLIN_STDLIB_CLASS, report, failures);
            }
        } catch (Exception error) {
            failures.add("探针自身异常：" + error);
            report.put("probeError", String.valueOf(error));
        }

        emit(report, failures);
        System.exit(failures.isEmpty() ? 0 : 1);
    }

    /**
     * 调生产加载器，接住它交给 Paper 的解析器。
     *
     * @return 生产加载器登记的唯一 {@link MavenLibraryResolver}
     */
    private static MavenLibraryResolver captureProductionResolver() {
        List<ClassPathLibrary> captured = new ArrayList<>();
        new MuzPluginLoader().classloader(new PluginClasspathBuilder() {
            @Override
            public PluginClasspathBuilder addLibrary(ClassPathLibrary library) {
                captured.add(library);
                return this;
            }

            @Override
            public PluginProviderContext getContext() {
                throw new AssertionError("加载器不应访问 getContext()");
            }
        });
        if (captured.size() != 1 || !(captured.get(0) instanceof MavenLibraryResolver resolver)) {
            throw new IllegalStateException("生产加载器应只登记一个 MavenLibraryResolver，实际：" + captured);
        }
        return resolver;
    }

    /**
     * 用 Paper 自己的解析器执行解析（真实服务端启动走的同一段代码），收集落地的 JAR 路径。
     *
     * @param resolver 生产加载器登记的解析器
     * @return 解析出的 JAR 路径
     * @throws Exception 解析失败
     */
    private static List<Path> registerWithPaperResolver(MavenLibraryResolver resolver) throws Exception {
        List<Path> jars = new ArrayList<>();
        LibraryStore store = jars::add;
        resolver.register(store);
        return jars;
    }

    /**
     * 用自己的 Aether 会话在【同一份缓存】上离线解析，证明缓存齐全。
     *
     * <p>用的是与 Paper 解析器相同的连接器与传输实现（BasicRepositoryConnectorFactory +
     * HttpTransporterFactory）、相同的 checksumPolicy，唯一区别是 {@code setOffline(true)}。
     *
     * @param dependencies 生产登记的依赖
     * @param repositories 生产登记的仓库
     * @param paperCache Paper 的本地仓库目录
     * @throws Exception 离线解析失败（说明缓存缺东西）
     */
    private static void resolveOffline(
        List<Dependency> dependencies,
        List<RemoteRepository> repositories,
        Path paperCache
    ) throws Exception {
        DefaultServiceLocator locator = MavenRepositorySystemUtils.newServiceLocator();
        locator.addService(RepositoryConnectorFactory.class, BasicRepositoryConnectorFactory.class);
        locator.addService(TransporterFactory.class, HttpTransporterFactory.class);
        RepositorySystem system = locator.getService(RepositorySystem.class);

        DefaultRepositorySystemSession session = MavenRepositorySystemUtils.newSession();
        session.setSystemProperties(System.getProperties());
        session.setChecksumPolicy(CHECKSUM_POLICY);
        session.setLocalRepositoryManager(
            system.newLocalRepositoryManager(session, new LocalRepository(paperCache.toFile()))
        );
        session.setOffline(true);

        CollectRequest collect = new CollectRequest();
        collect.setRepositories(repositories);
        // 与 Paper 的 MavenLibraryResolver 同形：第一条当根，其余同级。
        collect.setRoot(dependencies.get(0));
        for (int index = 1; index < dependencies.size(); index++) {
            collect.addDependency(dependencies.get(index));
        }
        DependencyResult result = system.resolveDependencies(session, new DependencyRequest(collect, null));
        for (var artifactResult : result.getArtifactResults()) {
            if (artifactResult.getArtifact().getFile() == null) {
                throw new IllegalStateException("离线解析里出现没有文件的工件：" + artifactResult.getArtifact());
            }
        }
    }

    /**
     * 数缓存目录下的 JAR 数量（用于区分冷/热缓存）。
     *
     * @param cacheDir 缓存目录
     * @return JAR 个数
     */
    private static long countJars(Path cacheDir) {
        if (!Files.isDirectory(cacheDir)) {
            return 0L;
        }
        try (var stream = Files.walk(cacheDir)) {
            return stream.filter(path -> path.toString().endsWith(".jar")).count();
        } catch (Exception error) {
            return -1L;
        }
    }

    /**
     * SnakeYAML：解析一小段 YAML 并取值。
     *
     * @param loader 隔离类加载器
     * @param report 报告
     * @param failures 失败收集
     */
    private static void probeSnakeYaml(URLClassLoader loader, Map<String, Object> report, List<String> failures) {
        try {
            Class<?> yamlClass = Class.forName(SNAKEYAML_CLASS, true, loader);
            Object yaml = yamlClass.getConstructor().newInstance();
            Object loaded = yamlClass.getMethod("load", String.class).invoke(yaml, "muz:\n  version: 1\n");
            Object muz = loaded instanceof Map<?, ?> map ? map.get("muz") : null;
            Object version = muz instanceof Map<?, ?> inner ? inner.get("version") : null;
            boolean ok = version instanceof Integer value && value == 1;
            report.put("snakeyaml", ok ? "ok" : "unexpected: " + loaded);
            if (!ok) {
                failures.add("SnakeYAML 解析结果不符合预期：" + loaded);
            }
        } catch (Exception error) {
            report.put("snakeyaml", "failed: " + error);
            failures.add("SnakeYAML 探针失败：" + error);
        }
    }

    /**
     * Gson：序列化一个 map。
     *
     * @param loader 隔离类加载器
     * @param report 报告
     * @param failures 失败收集
     */
    private static void probeGson(URLClassLoader loader, Map<String, Object> report, List<String> failures) {
        try {
            Class<?> gsonClass = Class.forName(GSON_CLASS, true, loader);
            Object gson = gsonClass.getConstructor().newInstance();
            Object json = gsonClass.getMethod("toJson", Object.class).invoke(gson, Map.of("plugin", "MUZ"));
            boolean ok = "{\"plugin\":\"MUZ\"}".equals(json);
            report.put("gson", ok ? "ok" : "unexpected: " + json);
            if (!ok) {
                failures.add("Gson 序列化结果不符合预期：" + json);
            }
        } catch (Exception error) {
            report.put("gson", "failed: " + error);
            failures.add("Gson 探针失败：" + error);
        }
    }

    /**
     * sqlite-jdbc：加载驱动、建库建表、插查一条。
     *
     * <p>刻意【不走 DriverManager】：驱动的类由隔离类加载器加载，而 {@code DriverManager}
     * 按「调用者类加载器」校验驱动可用性，容易把「类加载器可见性」问题误报成「驱动不可用」。
     * 生产里 {@code DatabaseManager} 与驱动同处插件类加载器，不存在这个落差；这里直接拿驱动
     * 实例连接，测的是同一件事（原生库能解压、SQL 能跑通），另外确认 SPI 元数据确实在 JAR 里。
     *
     * @param loader 隔离类加载器
     * @param cacheRoot 缓存根目录（临时库文件放它下面）
     * @param report 报告
     * @param failures 失败收集
     */
    private static void probeSqlite(
        URLClassLoader loader,
        Path cacheRoot,
        Map<String, Object> report,
        List<String> failures
    ) {
        Path database = cacheRoot.resolve("probe-sqlite.db");
        try {
            Class<?> driverClass = Class.forName(SQLITE_DRIVER_CLASS, true, loader);
            Driver driver = (Driver) driverClass.getConstructor().newInstance();
            Files.deleteIfExists(database);
            try (Connection connection = driver.connect("jdbc:sqlite:" + database, new Properties());
                 Statement statement = connection.createStatement()) {
                statement.execute("CREATE TABLE probe (id INTEGER PRIMARY KEY, name TEXT)");
                statement.execute("INSERT INTO probe (id, name) VALUES (1, 'MUZ')");
                try (ResultSet rows = statement.executeQuery("SELECT name FROM probe WHERE id = 1")) {
                    boolean ok = rows.next() && "MUZ".equals(rows.getString(1));
                    report.put("sqlite", ok ? "ok" : "unexpected row");
                    if (!ok) {
                        failures.add("sqlite 查询结果不符合预期");
                    }
                }
            }
            report.put("sqliteSpiMetadataPresent",
                loader.findResource("META-INF/services/java.sql.Driver") != null);
        } catch (Exception error) {
            report.put("sqlite", "failed: " + error);
            failures.add("sqlite 探针失败：" + error);
        }
    }

    /**
     * TabooLib reflex：分析一个类、按名字取方法并真实调用。
     *
     * <p>用生产同一套入口：{@code ClassAnalyser.INSTANCE.analyse(Class, AnalyseMode.REFLECTION_ONLY)}
     * → {@code new ReflexClass(structure, mode)} → {@code getMethodByTypes(name, isStatic, isNative, types...)}
     * → {@code ClassMethod.invoke(target, args)}（与 {@code CraftEngineOffsetService} 同形）。
     * 分析目标取 JDK 的 {@code java.util.ArrayList}：两个类加载器都可见，于是「分析 + 取方法 +
     * 调用」这条链完全落在下载下来的 reflex 代码里。
     *
     * @param loader 隔离类加载器
     * @param report 报告
     * @param failures 失败收集
     */
    private static void probeReflex(URLClassLoader loader, Map<String, Object> report, List<String> failures) {
        try {
            Class<?> analyserClass = Class.forName(REFLEX_ANALYSER_CLASS, true, loader);
            Class<?> modeClass = Class.forName(REFLEX_MODE_CLASS, true, loader);
            Class<?> reflexClassClass = Class.forName(REFLEX_CLASS_CLASS, true, loader);
            Class<?> structureClass = Class.forName(REFLEX_STRUCTURE_CLASS, true, loader);
            Object analyser = analyserClass.getField("INSTANCE").get(null);
            Object reflectionOnly = modeClass.getField("REFLECTION_ONLY").get(null);
            Object structure = analyserClass
                .getMethod("analyse", Class.class, modeClass)
                .invoke(analyser, ArrayList.class, reflectionOnly);
            Object reflex = reflexClassClass
                .getConstructor(structureClass, modeClass)
                .newInstance(structure, reflectionOnly);
            // 按「方法名 + 显式形参类型」取，与生产 getMethodByTypes("createMiniMessageOffsets",
            // false, false, int.class) 同形；不传空数组，避免走到生产不会走的分支。
            Object getMethod = reflexClassClass
                .getMethod("getMethodByTypes", String.class, boolean.class, boolean.class, Class[].class)
                .invoke(reflex, "get", false, false, new Class<?>[]{int.class});
            if (getMethod == null) {
                failures.add("reflex 未能取到 java.util.ArrayList#get(int)");
                report.put("reflex", "method lookup returned null");
                return;
            }
            ArrayList<String> target = new ArrayList<>();
            target.add("MUZ");
            // 实参必须按【装箱类型】给：ClassMethod.invoke 走 Object 可变参数，形参 int 时
            // 实参要给 Integer（生产注释记录了给 String 会 ClassCastException）。
            Object value = getMethod.getClass()
                .getMethod("invoke", Object.class, Object[].class)
                .invoke(getMethod, target, new Object[]{Integer.valueOf(0)});
            boolean ok = "MUZ".equals(value);
            report.put("reflex", ok ? "ok" : "unexpected value: " + value);
            if (!ok) {
                failures.add("reflex 调用 ArrayList#get(0) 结果不符合预期：" + value);
            }
        } catch (Exception error) {
            report.put("reflex", "failed: " + error);
            failures.add("reflex 探针失败：" + error);
        }
    }

    /**
     * 断言某个类能被下载下来的 JAR 加载（用于 ASM / commons-lang3 / kotlin-stdlib）。
     *
     * @param loader 隔离类加载器
     * @param className 全限定类名
     * @param report 报告
     * @param failures 失败收集
     */
    private static void probeLoadable(
        URLClassLoader loader,
        String className,
        Map<String, Object> report,
        List<String> failures
    ) {
        try {
            Class.forName(className, false, loader);
            report.put(className, "ok");
        } catch (Throwable error) {
            report.put(className, "failed: " + error);
            failures.add("类无法从下载的 JAR 加载：" + className + " -> " + error);
        }
    }

    /**
     * 把路径转成 URL。
     *
     * @param path 路径
     * @return URL
     */
    private static URL toUrl(Path path) {
        try {
            return path.toUri().toURL();
        } catch (Exception error) {
            throw new IllegalStateException("无法把路径转成 URL：" + path, error);
        }
    }

    /**
     * 读文件大小（读不到返回 0）。
     *
     * @param path 路径
     * @return 字节数
     */
    private static long sizeOf(Path path) {
        try {
            return Files.size(path);
        } catch (Exception error) {
            return 0L;
        }
    }

    /**
     * 算 SHA-256。
     *
     * @param path 文件
     * @return 十六进制摘要
     * @throws Exception 读文件失败
     */
    private static String sha256(Path path) throws Exception {
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        try (InputStream in = Files.newInputStream(path)) {
            byte[] buffer = new byte[8192];
            int read;
            while ((read = in.read(buffer)) > 0) {
                digest.update(buffer, 0, read);
            }
        }
        StringBuilder hex = new StringBuilder();
        for (byte value : digest.digest()) {
            hex.append(String.format("%02x", value));
        }
        return hex.toString();
    }

    /**
     * 反射读 {@link MavenLibraryResolver} 的私有字段（读的是生产加载器真正登记的内容）。
     *
     * @param resolver 解析器
     * @param name 字段名
     * @param <T> 期望类型
     * @return 字段值
     */
    @SuppressWarnings("unchecked")
    private static <T> T readField(MavenLibraryResolver resolver, String name) {
        try {
            Field field = MavenLibraryResolver.class.getDeclaredField(name);
            field.setAccessible(true);
            return (T) field.get(resolver);
        } catch (ReflectiveOperationException error) {
            throw new IllegalStateException("读取 MavenLibraryResolver." + name + " 失败", error);
        }
    }

    /**
     * 输出报告与结论。
     *
     * @param report 报告内容
     * @param failures 失败列表
     */
    private static void emit(Map<String, Object> report, List<String> failures) {
        StringBuilder text = new StringBuilder();
        text.append("=== MuzExternalDependencyProbe ===\n");
        report.forEach((key, value) -> text.append(key).append(" = ").append(value).append('\n'));
        text.append("FAILURES: ").append(failures.size()).append('\n');
        for (String failure : failures) {
            text.append("  - ").append(failure).append('\n');
        }
        // Windows 控制台默认代码页可能编不出报告里的字符；显式按 UTF-8 字节写出，
        // 绝不因为输出编码把「探针通过/失败」的退出码弄丢。
        try {
            System.out.write(text.toString().getBytes(StandardCharsets.UTF_8));
            System.out.flush();
        } catch (Exception error) {
            System.out.print(text);
        }
    }
}
