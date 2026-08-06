package linmumua.doudizhu;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.yaml.snakeyaml.Yaml;

/**
 * paper-plugin.yml 有两个会让插件直接起不来的坑，必须由测试守住。
 *
 * 一是 join-classpath：paper-plugin.yml 默认给每个插件独立 ClassLoader，
 * 而 MUZ 直接 import 了 CraftEngine、PlaceholderAPI、Vault 的 API 类。
 * 漏掉 join-classpath 的后果不是"少个功能"，是启动时 NoClassDefFoundError 崩溃。
 *
 * 二是 commands 段：paper-plugin.yml 不认这个字段，/muz 改由 CommandMap 注册。
 * 谁要是按老习惯把 commands 段加回来，Paper 会直接拒绝加载这份描述文件。
 */
class PaperPluginDescriptorTest {
    @Test
    void classpathSharingDependenciesAreJoined() throws IOException {
        // 这三个在 Java 代码里有直接 import：
        // VaultEconomyBridge 用 net.milkbowl.vault.economy.Economy、
        // MuzPlaceholderExpansion 继承 PlaceholderExpansion、
        // CraftEngineProtectionListener 用 net.momirealms 的事件类。
        // 独立 ClassLoader 取不到这些类，插件会在启动阶段崩掉。
        Map<String, Object> servers = serverDependencies();
        for (String plugin : List.of("CraftEngine", "PlaceholderAPI", "Vault")) {
            Object entry = servers.get(plugin);
            assertNotNull(entry, plugin + " 必须声明在 dependencies.server 下");
            assertEquals(
                Boolean.TRUE,
                asMap(entry).get("join-classpath"),
                plugin + " 的 API 类被直接 import，join-classpath 必须为 true，否则启动崩溃"
            );
        }
    }

    @Test
    void optionalEconomyPluginsStayIsolated() throws IOException {
        // 这几个只用插件管理器查存在性，没有 import。
        // 保持隔离是 paper-plugin.yml 的默认好处，随手打开会白白扩大类冲突面。
        Map<String, Object> servers = serverDependencies();
        for (String plugin : List.of("CMI", "CMILib", "EzEconomy", "XConomy")) {
            Object entry = servers.get(plugin);
            assertNotNull(entry, plugin + " 必须声明在 dependencies.server 下");
            assertEquals(
                Boolean.FALSE,
                asMap(entry).get("join-classpath"),
                plugin + " 代码里没有 import，不该共享 ClassLoader"
            );
        }
    }

    @Test
    void noDependencyIsRequired() throws IOException {
        // 原来全是 softdepend，缺哪个都能正常启动。
        // 谁把 required 写成 true，没装那个插件的服务器就直接起不来了。
        Map<String, Object> servers = serverDependencies();
        for (Map.Entry<String, Object> entry : servers.entrySet()) {
            assertEquals(
                Boolean.FALSE,
                asMap(entry.getValue()).get("required"),
                entry.getKey() + " 原本是 softdepend，required 必须为 false"
            );
        }
    }

    @Test
    void commandsSectionIsAbsent() throws IOException {
        // paper-plugin.yml 不支持 commands 段，写了 Paper 会拒绝加载。
        // /muz 由 DoudizhuPlugin.registerMuzCommand() 通过 CommandMap 注册。
        assertFalse(
            descriptor().containsKey("commands"),
            "paper-plugin.yml 不支持 commands 段，/muz 应由 CommandMap 注册"
        );
    }

    @Test
    void noRuntimeLibrariesAreRequested() throws IOException {
        // MUZ 唯一的第三方运行时 SnakeYAML 已重定位进 JAR，不需要 Paper 从 Maven 下载任何东西。
        // 这条守的是"别再加空 libraries 键"：Paper 解析空列表会报错，
        // 而随手补一条依赖又会让启动多一次联网下载。
        assertFalse(descriptor().containsKey("libraries"), "不再需要 libraries 段，SnakeYAML 已打进 JAR");
    }

    @Test
    void permissionsSurviveMigration() throws IOException {
        // 权限声明从 plugin.yml 搬过来时最容易漏。
        // muz.command 默认 true、muz.admin 默认 op，漏了会导致所有人都能用管理命令。
        Map<String, Object> permissions = asMap(descriptor().get("permissions"));
        assertEquals(true, asMap(permissions.get("muz.command")).get("default"), "muz.command 默认应为 true");
        assertEquals("op", asMap(permissions.get("muz.admin")).get("default"), "muz.admin 默认应为 op");
    }

    /**
     * 取 dependencies.server 段。
     *
     * @return 插件名到依赖配置
     * @throws IOException 读不到描述文件
     */
    private Map<String, Object> serverDependencies() throws IOException {
        Map<String, Object> dependencies = asMap(descriptor().get("dependencies"));
        return asMap(dependencies.get("server"));
    }

    /**
     * 解析 paper-plugin.yml。
     *
     * @return 描述文件根节点
     * @throws IOException 读不到描述文件
     */
    private Map<String, Object> descriptor() throws IOException {
        try (InputStream in = getClass().getClassLoader().getResourceAsStream("paper-plugin.yml")) {
            if (in == null) {
                throw new IOException("classpath 里没有 paper-plugin.yml");
            }
            String text = new String(in.readAllBytes(), StandardCharsets.UTF_8);
            return asMap(new Yaml().load(text));
        }
    }

    /**
     * 把 YAML 节点当成 map 用。
     *
     * @param node YAML 节点
     * @return map 视图
     */
    @SuppressWarnings("unchecked")
    private Map<String, Object> asMap(Object node) {
        assertNotNull(node, "这里应该是一个 YAML 映射，但拿到 null");
        return (Map<String, Object>) node;
    }
}
