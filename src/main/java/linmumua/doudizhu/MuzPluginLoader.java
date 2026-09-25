package linmumua.doudizhu;

import io.papermc.paper.plugin.loader.PluginClasspathBuilder;
import io.papermc.paper.plugin.loader.PluginLoader;
import io.papermc.paper.plugin.loader.library.impl.MavenLibraryResolver;
import java.util.List;
import org.eclipse.aether.artifact.DefaultArtifact;
import org.eclipse.aether.graph.Dependency;
import org.eclipse.aether.repository.RemoteRepository;

/**
 * MUZ 的 Paper 插件加载器：在插件自己的类加载器建立【之前】，把全部第三方运行期库从
 * Maven 下载到服务端本地缓存并加入插件 classpath。
 *
 * <p>【为什么要有这个类】本版本把原先内嵌（含重定位）进插件 JAR 的 gson / sqlite-jdbc /
 * snakeyaml / TabooLib common-reflex 及其 Kotlin、ASM、commons-lang3 依赖全部改为
 * 「运行期下载」，因此这些库的类【不在】插件 JAR 里。真正让它们可用的是这个加载器：
 * Paper 解析 paper-plugin.yml 时看到 {@code loader:} 声明，先实例化本类、调用
 * {@link #classloader(PluginClasspathBuilder)}，把库装进类路径之后，才去构造插件类加载器。
 * 插件源码里的 {@code import com.google.gson.*} / {@code org.yaml.snakeyaml.*} /
 * {@code org.sqlite.*} / {@code taboolib.library.reflex.*} 因此仍然指向【原始包名】——
 * 这也是本版本刻意【移除】全部 relocate 的原因：库既然按原坐标下载，业务字节码再被改名就
 * 会指向不存在的包路径，运行期必然 NoClassDefFoundError。
 *
 * <p>【本类只能依赖 JDK / Paper / Aether】加载器是在插件类加载器建立之前被实例化的，此时
 * 插件 JAR 里的业务类与所有第三方库都还不可见。所以这里只允许出现：
 * <ul>
 *   <li>JDK 类（{@link java.util.List}）；</li>
 *   <li>Paper 的 loader API（{@link PluginLoader}、{@link PluginClasspathBuilder}、
 *       {@link MavenLibraryResolver}）；</li>
 *   <li>Aether（Maven Resolver）的坐标与仓库模型：{@link DefaultArtifact}、
 *       {@link Dependency}、{@link RemoteRepository}。它们是
 *       {@code MavenLibraryResolver.addDependency/addRepository} 的形参类型，由 Paper
 *       服务端自身提供（paper-api 的 POM 以 compile/runtime 作用域声明
 *       {@code maven-resolver-provider} 与 {@code maven-resolver-connector-basic}、
 *       {@code maven-resolver-transport-http}）。</li>
 * </ul>
 * 任何人往这个类里加 MUZ 自己的类型或其它第三方 import，都会在真实服务器上以
 * NoClassDefFoundError 的形式炸在插件加载阶段。
 *
 * <p>【版本一律钉死】{@link #DEPENDENCY_COORDINATES} 里全部是精确版本号，没有区间、没有
 * SNAPSHOT：加载器下载的内容必须与 {@code build.gradle.kts} 的编译期坐标、以及测试 classpath
 * 用的坐标同源，否则「编译通过的类」与「运行期加载的类」可能不是同一份。
 *
 * <p>【仓库地址的来源】见 {@link #CENTRAL_REPOSITORY_ID} 与 {@link #TABOOLIB_REPOSITORY_URL}
 * 的说明；两处都在 2026-09-24 用 HTTP 实测确认过能提供对应的 POM/JAR。
 *
 * <p>【失败即不启动】本类不吞任何异常。下载失败（离线首启、仓库不可达、校验和不符）会让
 * {@code MavenLibraryResolver.register} 抛 {@code LibraryLoadingException}，Paper 随即判定
 * 插件加载失败——这是刻意选择：让「库缺失」在启动阶段明确报错，而不是等某个功能用到时才
 * 抛 NoClassDefFoundError。首次启动必须联网；库已进本地缓存后重启不再下载（缓存边界详见
 * README.md）。本类【不】设置任何 User-Agent，沿用服务端/Aether 的默认值。
 */
public final class MuzPluginLoader implements PluginLoader {

    /**
     * Paper 默认的 Maven Central 镜像仓库 id。
     *
     * <p>为什么不直接写 Maven Central 的域名：官方文档明确要求「从 Maven Central 解析库必须
     * 使用镜像」，直接把 {@code *.maven.org} / {@code *.maven.apache.org} 当 CDN 违反 Maven
     * Central 的服务条款，且会给使用者带来限流。所以这里用
     * {@link MavenLibraryResolver#MAVEN_CENTRAL_DEFAULT_MIRROR}——它由 Paper 提供，默认是
     * Google 的 Maven Central 只读镜像，管理员仍可按官方文档用
     * {@code PAPER_DEFAULT_CENTRAL_REPOSITORY} 环境变量或
     * {@code org.bukkit.plugin.java.LibraryLoader.centralURL} 系统属性替换。本插件不硬编码
     * 具体域名，以免绕过管理员选定的镜像。
     */
    public static final String CENTRAL_REPOSITORY_ID = "central";

    /**
     * TabooLib 官方仓库 id：{@code io.izzel.taboolib:common-reflex} 及其同族模块【不发布】
     * 到 Maven Central，只在这里。（2026-09-24 实测：同一坐标在 Central 镜像上是 404、
     * 在该仓库上是 200；反向地，gson / sqlite-jdbc / snakeyaml / kotlin-stdlib / asm /
     * commons-lang3 在该仓库上是 404、在 Central 镜像是 200，所以两个仓库都必须注册。）
     */
    public static final String TABOOLIB_REPOSITORY_ID = "tabooproject";

    /** {@link #TABOOLIB_REPOSITORY_ID} 对应的地址，与 {@code build.gradle.kts} 的 repositories 逐字一致。 */
    public static final String TABOOLIB_REPOSITORY_URL = "https://repo.tabooproject.org/repository/releases/";

    /**
     * 需要由本加载器下载的坐标，格式 {@code groupId:artifactId:version}。
     *
     * <p>这 7 条是「插件运行期自己要用」的全部第三方库。末尾 3 条不是冗余：
     * {@code io.izzel.taboolib:common-reflex} 的 POM 【没有声明任何依赖】，TabooLib 是用它自己的
     * 运行期模块索引解析传递依赖的；我们把它当普通 JAR 下载时 Gradle 与 Aether 都拿不到它的
     * 传递依赖，所以 Kotlin、ASM、commons-lang3 必须显式列出来，少一条就会在运行期抛
     * NoClassDefFoundError。其余传递依赖（gson → error_prone_annotations、sqlite-jdbc →
     * slf4j-api，以及 kotlin-stdlib → org.jetbrains:annotations）由 Aether 按各自 POM 的
     * compile 作用域正常收集，不需要在此重复声明。
     *
     * <p>顺序无语义（Aether 会把第一条当根、其余当同级依赖一并收集），但改动时要保持与
     * {@code build.gradle.kts} 的 compileOnly / testImplementation 坐标完全一致。
     */
    public static final List<String> DEPENDENCY_COORDINATES = List.of(
        // Debug Web、HUD 资源校验与 AI 网关的 JSON 解析。保持原包名 com.google.gson，
        // Gson 的反射式序列化依赖真实类型名，改名会让 Javadoc/配置里出现的类型名与实现不符。
        "com.google.code.gson:gson:2.11.0",
        // 运行期 YAML 唯一入口（MuzYamlConfig / MuzYamlConfig 的只读预览）。
        // 仍走 org.yaml.snakeyaml，不换解析器。
        "org.yaml:snakeyaml:2.6",
        // 默认存储后端；DatabaseManager 走 Class.forName("org.sqlite.JDBC") 与 JDBC SPI
        // 自动注册，因此必须保持原包名 org.sqlite。
        "org.xerial:sqlite-jdbc:3.46.1.0",
        // TabooLib 反射工具（taboolib.library.reflex.*），只作工具层，不参与注解生命周期。
        "io.izzel.taboolib:common-reflex:6.3.0-75b18a2",
        // 下面三条是 common-reflex 的运行期依赖，见上面的说明。
        "org.jetbrains.kotlin:kotlin-stdlib:2.3.20",
        "org.ow2.asm:asm:9.10.1",
        "org.apache.commons:commons-lang3:3.20.0"
    );

    @Override
    public void classloader(PluginClasspathBuilder classpathBuilder) {
        MavenLibraryResolver resolver = new MavenLibraryResolver();
        for (String coordinate : DEPENDENCY_COORDINATES) {
            // scope 传 null（与官方文档示例一致）：让库按默认作用域进入解析，
            // 从而其 POM 声明的 compile 传递依赖（error_prone_annotations / slf4j-api /
            // annotations）也会被一并下载。
            resolver.addDependency(new Dependency(new DefaultArtifact(coordinate), null));
        }
        // 顺序：Central 镜像优先命中绝大多数坐标，TabooLib 仓库只兜住 common-reflex。
        // 用 "default" 作为仓库类型，与官方文档示例一致。
        resolver.addRepository(new RemoteRepository.Builder(
            CENTRAL_REPOSITORY_ID, "default", MavenLibraryResolver.MAVEN_CENTRAL_DEFAULT_MIRROR
        ).build());
        resolver.addRepository(new RemoteRepository.Builder(
            TABOOLIB_REPOSITORY_ID, "default", TABOOLIB_REPOSITORY_URL
        ).build());
        classpathBuilder.addLibrary(resolver);
    }
}
