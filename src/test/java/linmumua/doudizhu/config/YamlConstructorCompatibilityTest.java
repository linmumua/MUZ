package linmumua.doudizhu.config;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.InputStream;
import java.lang.reflect.Field;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.yaml.snakeyaml.DumperOptions;
import org.yaml.snakeyaml.LoaderOptions;
import org.yaml.snakeyaml.Yaml;
import org.yaml.snakeyaml.constructor.BaseConstructor;
import org.yaml.snakeyaml.constructor.DuplicateKeyException;
import org.yaml.snakeyaml.representer.Representer;

/**
 * 锁定「MuzYamlConfig 组装的 Yaml 必须能在服务端自带的旧 SnakeYAML 上构造」。
 *
 * <p>为什么需要这组测试：项目把 SnakeYAML 外置成运行期下载（{@code MuzPluginLoader} 登记 2.6），
 * 但服务端自己也可能带一份旧的（实测 Folia/Paper 26.1.2 的
 * {@code libraries/org/yaml/snakeyaml/2.2/}）。插件侧能解析到哪一份由类加载顺序决定，
 * 因此生产代码只能使用「两份都有的 API」。
 * 事故现场：{@code MuzYamlConfig.createYaml} 用了 {@code Yaml(LoaderOptions, DumperOptions)}
 * 便捷构造器——javap 实测它 <b>2.5 才新增</b>，2.0/2.2 没有；实服因此在构造阶段抛
 * {@code NoSuchMethodError: org.yaml.snakeyaml.Yaml.<init>(LoaderOptions, DumperOptions)}，config.yml 都读不出来。
 *
 * <p>为什么既有测试漏掉了：测试编译与运行都只挂 2.6（{@code testImplementation}），
 * 类路径上从来不存在旧版 SnakeYAML，所以 2.5+ 才有的签名在测试里永远「存在」。
 * 无法在单测里同时挂两份 SnakeYAML，因此这里改用<b>字节码证据</b>兜住：
 * 检查生产类真正引用的构造器描述符是否落在「最低支持版本也存在」的白名单内。
 * 白名单来自 javap 实测（2.0 / 2.2 / 2.5 / 2.6 全都有四参构造器；二参构造器只有 2.5 / 2.6 有），
 * 新增签名的改动必须显式更新白名单，不能悄悄通过。
 *
 * <p>覆盖面按「所有 {@code new Yaml(...)} 的生产类」动态扫描，不写死类名——
 * 第一版只钉了 MuzYamlConfig，结果同一个缺陷在 {@code HudOverlayWriter}
 * （Debug Web 的 HUD overlay 写入）里又留了一处。
 */
class YamlConstructorCompatibilityTest {

    /**
     * javap 实测：{@code (BaseConstructor, Representer, DumperOptions, LoaderOptions)}
     * 在 2.0 / 2.2 / 2.5 / 2.6 上都存在，且 2.2 与 2.6 的字节码一致（都转调五参构造器、Resolver 用默认实现）。
     */
    private static final String FOUR_ARG_DESCRIPTOR =
        "(Lorg/yaml/snakeyaml/constructor/BaseConstructor;Lorg/yaml/snakeyaml/representer/Representer;"
            + "Lorg/yaml/snakeyaml/DumperOptions;Lorg/yaml/snakeyaml/LoaderOptions;)V";

    /** javap 实测：这个便捷构造器 2.5 才有，2.0 / 2.2 没有 —— 生产代码必须避开它。 */
    private static final String TWO_ARG_DESCRIPTOR_OF_NEWER_SNAKEYAML =
        "(Lorg/yaml/snakeyaml/LoaderOptions;Lorg/yaml/snakeyaml/DumperOptions;)V";

    /** 旧版也存在的单参构造器（HudResourcePackVerifier 用它读资源包内的 YAML）。 */
    private static final String ONE_ARG_DESCRIPTOR =
        "(Lorg/yaml/snakeyaml/constructor/BaseConstructor;)V";

    @Test
    void 生产里所有Yaml构造点都只引用旧版SnakeYAML也存在的签名() throws Exception {
        // 这一条是「不再漏点」的闸门：挨个找出 src/main/java 下所有 new Yaml(...) 的类，
        // 读它们真正的字节码，确认没有任何一处引用 2.5 才新增的二参构造器。
        // 事故教训：修了 MuzYamlConfig 却漏了 HudOverlayWriter（Debug Web 保存并应用会再炸一次）。
        List<String> constructionSites = productionClassesConstructingYaml();
        assertFalse(
            constructionSites.isEmpty(),
            "扫描不到任何 new Yaml(...) 的生产类，说明扫描方式已失效（而不是真的没有构造点）");
        assertTrue(
            constructionSites.contains("linmumua.doudizhu.config.MuzYamlConfig"),
            "扫描必须覆盖 MuzYamlConfig，实际: " + constructionSites);
        assertTrue(
            constructionSites.contains("linmumua.doudizhu.debug.HudOverlayWriter"),
            "扫描必须覆盖 HudOverlayWriter（Debug Web 的 HUD overlay 写入），实际: " + constructionSites);

        for (String className : constructionSites) {
            byte[] bytecode = classBytes(Class.forName(className));
            assertFalse(
                containsSequence(bytecode, TWO_ARG_DESCRIPTOR_OF_NEWER_SNAKEYAML),
                className + " 仍引用 2.5 才新增的 Yaml(LoaderOptions, DumperOptions)："
                    + "服务端自带旧 SnakeYAML 时会在构造阶段抛 NoSuchMethodError");
            assertTrue(
                containsSequence(bytecode, FOUR_ARG_DESCRIPTOR) || containsSequence(bytecode, ONE_ARG_DESCRIPTOR),
                className + " 的 Yaml 构造器既不是四参也不是单参（BaseConstructor），需要重新核对旧版可用性");
        }
    }

    @Test
    void 四参与单参构造器在当前编译类路径上真实存在() throws Exception {
        // 防止白名单凭记忆漂移：当前 SnakeYAML 必须真的有这两个签名。
        assertNotNull(
            Yaml.class.getConstructor(BaseConstructor.class, Representer.class, DumperOptions.class, LoaderOptions.class),
            "四参构造器在当前 SnakeYAML 上不存在，测试的白名单来源需要重新核对");
        assertNotNull(
            Yaml.class.getConstructor(BaseConstructor.class),
            "单参 Yaml(BaseConstructor) 在当前 SnakeYAML 上不存在，测试的白名单来源需要重新核对");
    }

    @Test
    void 组装出的Yaml保留了全部加载与输出配置() throws Exception {
        Yaml yaml = MuzYamlConfig.createYaml(loaderOptions(), dumperOptions());

        // 加载侧：显式 SafeConstructor（不再走便捷构造器内部的「不受限」Constructor）
        assertInstanceOf(
            org.yaml.snakeyaml.constructor.SafeConstructor.class,
            field(yaml, "constructor"),
            "Yaml 内部构造器必须是显式传入的 SafeConstructor");

        LoaderOptions loadingConfig = (LoaderOptions) field(yaml, "loadingConfig");
        assertFalse(loadingConfig.isAllowDuplicateKeys(), "allowDuplicateKeys=false 必须保留（重复键要报错）");
        assertEquals(16 * 1024 * 1024, loadingConfig.getCodePointLimit(), "码点上限必须保留");

        // 输出侧：BLOCK 样式、缩进 2、宽度 120、中文不转义等全部沿用原值
        DumperOptions dumped = (DumperOptions) field(yaml, "dumperOptions");
        assertEquals(DumperOptions.FlowStyle.BLOCK, dumped.getDefaultFlowStyle(), "默认 FLOW 样式必须保留");
        assertEquals(DumperOptions.ScalarStyle.PLAIN, dumped.getDefaultScalarStyle(), "标量样式必须保留");
        assertTrue(dumped.isAllowUnicode(), "中文必须直接写出，不能被转义");
        assertTrue(dumped.isPrettyFlow(), "prettyFlow 必须保留");
        assertEquals(2, dumped.getIndent(), "缩进必须保持 2");
        assertEquals(0, dumped.getIndicatorIndent(), "指示符缩进必须保持 0");
        assertEquals(120, dumped.getWidth(), "行宽必须保持 120");
        assertFalse(dumped.getSplitLines(), "splitLines 必须保持关闭");
    }

    @Test
    void 重复键仍然被拒绝而不是被静默接受(@TempDir Path dir) throws Exception {
        // 换构造方式最怕顺手把 LoaderOptions 丢给默认值：那样重复键会被静默覆盖，配置写错不再报错。
        Path file = dir.resolve("dup.yml");
        Files.writeString(file, "a: 1\na: 2\n", StandardCharsets.UTF_8);
        assertThrows(DuplicateKeyException.class, () -> new MuzYamlConfig(file));
    }

    @Test
    void 道具栏的映射标记不会被写成全局tag且能原样读回(@TempDir Path dir) throws Exception {
        // 这是「安全构造器不得改变既有读法」的现场：VirtualGadgetBarStore 把 List<ItemStack> 交给
        // MuzYamlConfig.set()，encodeValue 把每个物品变成带 "==" 标记的普通映射。
        // 一旦它被 dump 成 !!org.bukkit.inventory.ItemStack 这种全局 tag，读回来会被拒绝
        // （实测两版 SnakeYAML 都抛 ComposerException: Global tag is not allowed），玩家道具直接丢失。
        Map<String, Object> slot = new LinkedHashMap<>();
        slot.put("==", "org.bukkit.inventory.ItemStack");
        slot.put("type", "DIAMOND");
        slot.put("amount", 1);

        Path file = dir.resolve("player-settings.yml");
        MuzYamlConfig config = new MuzYamlConfig(file);
        config.set("gadget-bar", new ArrayList<>(List.of(slot)));
        config.save();

        String dumped = Files.readString(file);
        assertFalse(
            dumped.contains("!!"),
            "道具栏被写成了 YAML 全局 tag，读回来会被拒（玩家道具丢失）：\n" + dumped);
        assertTrue(
            dumped.contains("org.bukkit.inventory.ItemStack"),
            "道具栏必须保留 == 映射标记：\n" + dumped);

        Object reloaded = MuzYamlConfig.readOnlyRoot(file).get("gadget-bar");
        List<?> slots = assertInstanceOf(List.class, reloaded, "道具栏必须读回列表");
        Map<?, ?> first = assertInstanceOf(Map.class, slots.get(0), "道具栏元素必须读回映射");
        assertEquals(
            "org.bukkit.inventory.ItemStack",
            first.get("=="),
            "== 标记必须在往返中保住，否则 VirtualGadgetBarStore.decodeItem 解不出物品");

        // 标记串本身必须留在生产字节码里（encodeValue 写它、decodeItemStack 按它认回来）。
        // JUnit 起不了 Bukkit，ItemStack 对象只能由独立 launcher 用替身类验证；
        // 这里用常量池断言兜住「映射没被删掉」这条，避免只靠字符串读源码。
        assertTrue(
            containsSequence(classBytes(MuzYamlConfig.class), "org.bukkit.inventory.ItemStack"),
            "MuzYamlConfig 的字节码里不再有物品映射标记串，encodeValue/decodeItemStack 的约定被破坏");
    }

    @Test
    void 直接序列化对象会产生全局tag并被拒绝这正是需要映射标记的原因(@TempDir Path dir) throws Exception {
        // 说明「为什么 encodeValue 要把 ItemStack 变成映射」：SnakeYAML 对未知类型会打上全局 tag，
        // 而 2.x 默认拒绝全局 tag —— 直接把对象交给 dump，写出来的文件根本读不回来。
        Path file = dir.resolve("obj.yml");
        MuzYamlConfig config = new MuzYamlConfig(file);
        config.set("raw", new Object());
        config.save();

        String dumped = Files.readString(file);
        assertTrue(
            dumped.contains("!!"),
            "未知类型本应被写成全局 tag（若 SnakeYAML 行为变了，本条测试需要重新核对）：\n" + dumped);
        assertThrows(
            Exception.class,
            () -> MuzYamlConfig.readOnlyRoot(file),
            "带全局 tag 的文件读回来必须报错——这正是不能直接 dump 对象的原因");
    }

    @Test
    void 随包发布的config模板仍然能被解析() throws Exception {
        // 真实数据回归：出厂 config.yml 是玩家最常改的文件，构造方式变更后必须仍然可解析。
        // 按仓库既有约定读工作目录下的源文件（类路径上有同名 config.yml，会取到别的插件那份）。
        Path template = Path.of("src/main/resources/config.yml");
        assertTrue(Files.isRegularFile(template), "缺少出厂模板: " + template.toAbsolutePath());
        Map<String, Object> root = MuzYamlConfig.readOnlyRoot(template);
        assertFalse(root.isEmpty(), "出厂 config.yml 不应解析成空配置");
        assertTrue(root.containsKey("table"), "出厂 config.yml 必须能读出 table 段，实际键: " + root.keySet());
        assertTrue(root.containsKey("trick-hud"), "出厂 config.yml 必须能读出 trick-hud 段，实际键: " + root.keySet());
    }

    // ---------- 工具 ----------

    /**
     * 找出生产源码里所有调用 {@code new Yaml(...)} 的类（返回全限定名）。
     *
     * <p>为什么按源码找而不是写死类名：写死清单会在新增构造点时静默放过去，
     * 上一版就是这么漏掉 HudOverlayWriter 的。找到类名后再读它的<b>字节码</b>做判断，
     * 所以判定依据仍然是编译产物，不是源码文本匹配。
     */
    private static List<String> productionClassesConstructingYaml() throws Exception {
        Path root = Path.of("src/main/java");
        assertTrue(Files.isDirectory(root), "找不到生产源码目录: " + root.toAbsolutePath());
        List<String> classNames = new ArrayList<>();
        try (var paths = Files.walk(root)) {
            for (Path path : paths.filter(p -> p.toString().endsWith(".java")).toList()) {
                String source = Files.readString(path, StandardCharsets.UTF_8);
                if (!source.contains("new Yaml(")) {
                    continue;
                }
                String relative = root.relativize(path).toString().replace('\\', '/');
                classNames.add(relative.substring(0, relative.length() - ".java".length()).replace('/', '.'));
            }
        }
        return classNames;
    }

    private static LoaderOptions loaderOptions() {
        LoaderOptions options = new LoaderOptions();
        options.setAllowDuplicateKeys(false);
        options.setCodePointLimit(16 * 1024 * 1024);
        return options;
    }

    private static DumperOptions dumperOptions() {
        DumperOptions options = new DumperOptions();
        options.setDefaultFlowStyle(DumperOptions.FlowStyle.BLOCK);
        options.setDefaultScalarStyle(DumperOptions.ScalarStyle.PLAIN);
        options.setAllowUnicode(true);
        options.setPrettyFlow(true);
        options.setIndent(2);
        options.setIndicatorIndent(0);
        options.setWidth(120);
        options.setSplitLines(false);
        return options;
    }

    private static Object field(Yaml yaml, String name) throws Exception {
        Field field = Yaml.class.getDeclaredField(name);
        field.setAccessible(true);
        return field.get(yaml);
    }

    private static byte[] classBytes(Class<?> type) throws Exception {
        try (InputStream stream = type.getResourceAsStream(type.getSimpleName() + ".class")) {
            return stream.readAllBytes();
        }
    }

    private static boolean containsSequence(byte[] haystack, String needle) {
        byte[] pattern = needle.getBytes(StandardCharsets.ISO_8859_1);
        outer:
        for (int index = 0; index + pattern.length <= haystack.length; index++) {
            for (int offset = 0; offset < pattern.length; offset++) {
                if (haystack[index + offset] != pattern[offset]) {
                    continue outer;
                }
            }
            return true;
        }
        return false;
    }
}
