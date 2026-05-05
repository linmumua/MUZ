package dev.mumu.doudizhu.config;

import java.io.IOException;
import java.io.InputStream;
import java.io.Reader;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
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
        loaderOptions.setWarnOnDuplicateKeys(true);
        loaderOptions.setCodePointLimit(16 * 1024 * 1024);

        DumperOptions dumperOptions = new DumperOptions();
        dumperOptions.setDefaultFlowStyle(DumperOptions.FlowStyle.BLOCK);
        dumperOptions.setDefaultScalarStyle(DumperOptions.ScalarStyle.PLAIN);
        dumperOptions.setAllowUnicode(true);
        dumperOptions.setPrettyFlow(true);
        dumperOptions.setIndent(2);
        dumperOptions.setIndicatorIndent(2);
        dumperOptions.setWidth(120);
        dumperOptions.setSplitLines(false);
        return new Yaml(loaderOptions, dumperOptions);
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
        }
    }

    public void save() throws IOException {
        Path parent = file.getParent();
        if (parent != null) {
            Files.createDirectories(parent);
        }
        try (Writer writer = Files.newBufferedWriter(file, StandardCharsets.UTF_8)) {
            yaml.dump(root, writer);
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
