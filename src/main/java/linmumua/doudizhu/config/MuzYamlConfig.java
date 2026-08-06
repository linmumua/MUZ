package linmumua.doudizhu.config;

import java.io.IOException;
import java.io.InputStream;
import java.io.Reader;
import java.io.StringWriter;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;
import org.bukkit.inventory.ItemStack;
import org.yaml.snakeyaml.DumperOptions;
import org.yaml.snakeyaml.LoaderOptions;
import org.yaml.snakeyaml.Yaml;
import org.yaml.snakeyaml.constructor.DuplicateKeyException;
import org.yaml.snakeyaml.error.YAMLException;

public final class MuzYamlConfig {
    private final Path file;
    private final Yaml yaml;
    private Map<String, Object> root = new LinkedHashMap<>();

    public MuzYamlConfig(Path file) {
        this.file = Objects.requireNonNull(file, "file");
        this.yaml = createYaml();
        reload();
    }

    public static MuzYamlConfig empty(Path file) {
        MuzYamlConfig config = new MuzYamlConfig(file);
        config.root = new LinkedHashMap<>();
        return config;
    }

    private static Yaml createYaml() {
        LoaderOptions loaderOptions = new LoaderOptions();
        loaderOptions.setAllowDuplicateKeys(false);
        loaderOptions.setCodePointLimit(16 * 1024 * 1024);
        DumperOptions dumperOptions = new DumperOptions();
        dumperOptions.setDefaultFlowStyle(DumperOptions.FlowStyle.BLOCK);
        dumperOptions.setDefaultScalarStyle(DumperOptions.ScalarStyle.PLAIN);
        dumperOptions.setAllowUnicode(true);
        dumperOptions.setPrettyFlow(true);
        dumperOptions.setIndent(2);
        dumperOptions.setIndicatorIndent(0);
        dumperOptions.setWidth(120);
        dumperOptions.setSplitLines(false);
        return new Yaml(loaderOptions, dumperOptions);
    }

    /**
     * 在启动阶段预加载 SnakeYAML 输出空映射所需的 Emitter 内部类。
     *
     * <p>空映射会被按 FLOW 样式输出（{@code {}}），这条分支用到的 Emitter 内部类
     * 在只输出 BLOCK 样式时从不加载。而 onDisable 阶段 Paper 已停止为插件
     * ClassLoader 提供新类，那时首次加载会抛 NoClassDefFoundError，
     * 导致 savePlayerSettings() 中断、玩家设置丢失。
     *
     * <p>这里在内存中 dump 一次空映射，把相关类提前加载好，让关服时的写入只走已加载的代码路径。
     *
     * @return 预热时产生的 YAML 文本，供测试确认确实走了 FLOW 分支
     */
    public static String warmUpFlowEmitter() {
        Map<String, Object> probe = new LinkedHashMap<>();
        probe.put("players", new LinkedHashMap<String, Object>());
        StringWriter writer = new StringWriter();
        createYaml().dump(probe, writer);
        return writer.toString();
    }

    public void reload() {
        if (!Files.isRegularFile(file)) {
            root = new LinkedHashMap<>();
            return;
        }
        try (Reader reader = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
            Object loaded = yaml.load(reader);
            root = normalizeRoot(loaded);
        } catch (IOException exception) {
            throw new IllegalStateException("读取 YAML 失败: " + file + " | " + exception.getMessage(), exception);
        } catch (YAMLException exception) {
            // 重复键之类的人为写错要如实报错，交给使用者修。
            if (exception instanceof DuplicateKeyException) {
                throw exception;
            }
            // 但文件被写到一半截断时不能让整个插件启动失败。留一份 .broken 备份便于排查，
            // 然后按空配置继续，后续保存会重新生成一份完好的文件。
            root = new LinkedHashMap<>();
            quarantineBrokenFile(exception);
        }
    }

    /**
     * 把解析失败的配置挪到 .broken 备份，避免反复启动失败
     * @param cause 解析失败的原因
     */
    private void quarantineBrokenFile(YAMLException cause) {
        Path broken = file.resolveSibling(file.getFileName() + ".broken");
        try {
            Files.move(file, broken, StandardCopyOption.REPLACE_EXISTING);
            System.err.println("[MUZ] 配置 " + file.getFileName() + " 解析失败，已备份为 "
                + broken.getFileName() + " 并按默认值继续: " + cause.getMessage());
        } catch (IOException moveFailure) {
            System.err.println("[MUZ] 配置 " + file.getFileName() + " 解析失败且无法备份: "
                + moveFailure.getMessage());
        }
    }

    /**
     * 带注释模板的保存：写盘前把模板里的注释按键路径补回去。
     *
     * <p>{@link #save()} 走的是 {@code dump(Map)}，注释挂在 Node 上，拿不到，所以每次保存都会
     * 写出一份裸配置。需要保留注释的文件（目前只有 config.yml）走这个方法。
     *
     * @param template 打包在 jar 里的同名模板内容；为 null 时等价于 {@link #save()}
     */
    public void saveWithComments(String template) throws IOException {
        if (template == null || template.isBlank()) {
            save();
            return;
        }
        writeAtomically(YamlCommentMerger.merge(template, dumpToString()));
    }

    private String dumpToString() {
        StringWriter writer = new StringWriter();
        yaml.dump(root, writer);
        return writer.toString();
    }

    public void save() throws IOException {
        writeAtomically(dumpToString());
    }

    private void writeAtomically(String content) throws IOException {
        Path parent = file.getParent();
        if (parent != null) {
            Files.createDirectories(parent);
        }
        // 先写临时文件再整体替换。直接写目标文件的话，中途崩溃或进程被杀会留下
        // 截断的 YAML，下次启动解析失败会让整个插件被禁用。
        Path temp = file.resolveSibling(file.getFileName() + ".tmp");
        try (Writer writer = Files.newBufferedWriter(temp, StandardCharsets.UTF_8)) {
            writer.write(content);
        }
        try {
            Files.move(temp, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (java.nio.file.AtomicMoveNotSupportedException ignored) {
            // 某些文件系统不支持原子移动，退回普通替换。
            Files.move(temp, file, StandardCopyOption.REPLACE_EXISTING);
        }
    }

    public Path file() {
        return file;
    }

    public Map<String, Object> rawRoot() {
        return root;
    }

    public boolean contains(String path) {
        return get(path) != null;
    }

    public Object get(String path) {
        if (path == null || path.isBlank()) {
            return root;
        }
        Map<String, Object> section = root;
        String[] parts = path.split("\\.");
        for (int index = 0; index < parts.length; index++) {
            Object value = section.get(parts[index]);
            if (index == parts.length - 1) {
                return decodeItemStack(value);
            }
            if (!(value instanceof Map<?, ?> map)) {
                return null;
            }
            section = normalizeMap(map);
        }
        return null;
    }

    public String getString(String path) {
        Object value = get(path);
        return value == null ? null : String.valueOf(value);
    }

    public String getString(String path, String fallback) {
        String value = getString(path);
        return value == null ? fallback : value;
    }

    public int getInt(String path, int fallback) {
        Object value = get(path);
        if (value instanceof Number number) {
            return number.intValue();
        }
        if (value instanceof String string) {
            try {
                return Integer.parseInt(string.trim());
            } catch (NumberFormatException ignored) {
            }
        }
        return fallback;
    }

    public double getDouble(String path, double fallback) {
        Object value = get(path);
        if (value instanceof Number number) {
            return number.doubleValue();
        }
        if (value instanceof String string) {
            try {
                return Double.parseDouble(string.trim());
            } catch (NumberFormatException ignored) {
            }
        }
        return fallback;
    }

    public boolean getBoolean(String path, boolean fallback) {
        Object value = get(path);
        if (value instanceof Boolean bool) {
            return bool;
        }
        if (value instanceof String string) {
            String normalized = string.trim().toLowerCase(java.util.Locale.ROOT);
            if (normalized.equals("true") || normalized.equals("yes") || normalized.equals("on") || normalized.equals("1")) {
                return true;
            }
            if (normalized.equals("false") || normalized.equals("no") || normalized.equals("off") || normalized.equals("0")) {
                return false;
            }
        }
        return fallback;
    }

    public List<String> getStringList(String path) {
        Object value = get(path);
        if (value instanceof List<?> list) {
            return list.stream().map(String::valueOf).collect(Collectors.toCollection(ArrayList::new));
        }
        if (value instanceof String string && !string.isBlank()) {
            return List.of(string);
        }
        return List.of();
    }

    public Set<String> getKeys(String path) {
        Object value = get(path);
        if (value instanceof Map<?, ?> map) {
            return map.keySet().stream().map(String::valueOf).collect(Collectors.toCollection(java.util.LinkedHashSet::new));
        }
        return Set.of();
    }

    public MuzYamlConfig section(String path) {
        Object value = get(path);
        MuzYamlConfig config = new MuzYamlConfig(file);
        config.root = value instanceof Map<?, ?> map ? normalizeMap(map) : new LinkedHashMap<>();
        return config;
    }

    public void set(String path, Object value) {
        if (path == null || path.isBlank()) {
            if (value instanceof Map<?, ?> map) {
                root = normalizeMap(map);
            } else if (value == null) {
                root = new LinkedHashMap<>();
            } else {
                throw new IllegalArgumentException("根节点只能设置为 Map 或 null");
            }
            return;
        }
        String[] parts = path.split("\\.");
        Map<String, Object> section = root;
        for (int index = 0; index < parts.length - 1; index++) {
            Object current = section.get(parts[index]);
            if (!(current instanceof Map<?, ?>)) {
                current = new LinkedHashMap<String, Object>();
                section.put(parts[index], current);
            }
            section = normalizeStoredMap(section, parts[index]);
        }
        String key = parts[parts.length - 1];
        if (value == null) {
            section.remove(key);
            pruneEmptyParents(parts);
        } else {
            section.put(key, encodeValue(value));
        }
    }

    public ItemStack getItemStack(String path) {
        Object value = get(path);
        return value instanceof ItemStack itemStack ? itemStack : null;
    }

    public boolean mergeMissingFrom(InputStream inputStream) {
        if (inputStream == null) {
            return false;
        }
        Object loaded = yaml.load(inputStream);
        return mergeMissing(root, normalizeRoot(loaded));
    }

    private boolean mergeMissing(Map<String, Object> target, Map<String, Object> defaults) {
        boolean changed = false;
        for (Map.Entry<String, Object> entry : defaults.entrySet()) {
            Object existing = target.get(entry.getKey());
            Object defaultValue = entry.getValue();
            if (existing == null) {
                target.put(entry.getKey(), defaultValue);
                changed = true;
            } else if (existing instanceof Map<?, ?> existingMap && defaultValue instanceof Map<?, ?> defaultMap) {
                changed |= mergeMissing(normalizeStoredMap(target, entry.getKey()), normalizeMap(defaultMap));
            }
        }
        return changed;
    }

    private void pruneEmptyParents(String[] parts) {
        pruneEmpty(root, parts, 0);
    }

    @SuppressWarnings("unchecked")
    private boolean pruneEmpty(Map<String, Object> section, String[] parts, int index) {
        if (index >= parts.length - 1) {
            return section.isEmpty();
        }
        Object child = section.get(parts[index]);
        if (child instanceof Map<?, ?> map && pruneEmpty((Map<String, Object>) map, parts, index + 1)) {
            section.remove(parts[index]);
        }
        return section.isEmpty();
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> normalizeStoredMap(Map<String, Object> parent, String key) {
        Object value = parent.get(key);
        if (value instanceof Map<?, ?> map) {
            if (value instanceof LinkedHashMap<?, ?>) {
                return (Map<String, Object>) value;
            }
            Map<String, Object> normalized = normalizeMap(map);
            parent.put(key, normalized);
            return normalized;
        }
        Map<String, Object> created = new LinkedHashMap<>();
        parent.put(key, created);
        return created;
    }

    private static Map<String, Object> normalizeRoot(Object loaded) {
        if (loaded instanceof Map<?, ?> map) {
            return normalizeMap(map);
        }
        return new LinkedHashMap<>();
    }

    private static Map<String, Object> normalizeMap(Map<?, ?> map) {
        Map<String, Object> normalized = new LinkedHashMap<>();
        for (Map.Entry<?, ?> entry : map.entrySet()) {
            normalized.put(String.valueOf(entry.getKey()), normalizeValue(entry.getValue()));
        }
        return normalized;
    }

    private static Object normalizeValue(Object value) {
        if (value instanceof Map<?, ?> map) {
            return normalizeMap(map);
        }
        if (value instanceof List<?> list) {
            List<Object> normalized = new ArrayList<>(list.size());
            for (Object element : list) {
                normalized.add(normalizeValue(element));
            }
            return normalized;
        }
        return value;
    }

    private static Object encodeValue(Object value) {
        if (value instanceof ItemStack itemStack) {
            Map<String, Object> serialized = new LinkedHashMap<>();
            serialized.put("==", "org.bukkit.inventory.ItemStack");
            serialized.putAll(itemStack.serialize());
            return serialized;
        }
        if (value instanceof Map<?, ?> map) {
            return normalizeMap(map);
        }
        if (value instanceof List<?> list) {
            List<Object> encoded = new ArrayList<>(list.size());
            for (Object element : list) {
                encoded.add(encodeValue(element));
            }
            return encoded;
        }
        return value;
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    private static Object decodeItemStack(Object value) {
        if (!(value instanceof Map<?, ?> map)) {
            return value;
        }
        Object marker = map.get("==");
        if (marker == null || !String.valueOf(marker).contains("ItemStack")) {
            return value;
        }
        Map<String, Object> serialized = new LinkedHashMap<>();
        for (Map.Entry<?, ?> entry : map.entrySet()) {
            if (!"==".equals(String.valueOf(entry.getKey()))) {
                serialized.put(String.valueOf(entry.getKey()), entry.getValue());
            }
        }
        try {
            return ItemStack.deserialize((Map) serialized);
        } catch (RuntimeException exception) {
            return null;
        }
    }
}
