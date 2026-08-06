package linmumua.doudizhu.config;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 把模板里的注释按键路径搬到已序列化的 YAML 文本上。
 *
 * <p>为什么需要它：SnakeYAML 的注释挂在 Node 上，而这里的配置是以 {@code Map} 形态存取的，
 * {@code dump(Map)} 拿不到注释。开 {@code processComments} 也没用——那只对
 * {@code compose}/{@code serialize} 的 Node 流程生效。于是每次 {@code save()} 都会写出
 * 一份没有任何注释的裸配置，玩家改配置时看不到取值范围。
 *
 * <p>做法是纯文本的：从模板里按缩进解析出「键路径 -&gt; 它上方那几行注释」，
 * 再逐行扫描目标文本，遇到同一个键路径就把注释插回去。不碰值，也不重排键。
 */
public final class YamlCommentMerger {

    private YamlCommentMerger() {
    }

    /**
     * @param template 带注释的模板文本
     * @param target   已序列化但没有注释的文本
     * @return 补上注释后的文本
     */
    public static String merge(String template, String target) {
        Map<String, List<String>> comments = collectComments(template);
        StringBuilder out = new StringBuilder();
        // 目标已经带头部注释时不再插一份，否则每保存一次就多一段。
        if (!target.stripLeading().startsWith("#")) {
            out.append(collectHeader(template));
        }
        Deque<String> path = new ArrayDeque<>();
        int previousIndent = -1;
        // 目标里已经有注释的键不再补模板注释，否则会叠成两份。
        boolean previousLineWasComment = false;
        for (String line : target.split("\n", -1)) {
            String trimmed = line.strip();
            if (trimmed.startsWith("#")) {
                previousLineWasComment = true;
                out.append(line).append('\n');
                continue;
            }
            if (trimmed.isEmpty()) {
                previousLineWasComment = false;
                out.append(line).append('\n');
                continue;
            }
            int indent = indentOf(line);
            int colon = keyColonIndex(trimmed);
            if (colon > 0) {
                String key = trimmed.substring(0, colon).strip();
                while (previousIndent >= indent && !path.isEmpty()) {
                    path.removeLast();
                    previousIndent -= 2;
                }
                path.addLast(key);
                previousIndent = indent;
                List<String> block = comments.get(String.join(".", path));
                if (block != null && !previousLineWasComment) {
                    String pad = " ".repeat(indent);
                    for (String comment : block) {
                        out.append(pad).append(comment).append('\n');
                    }
                }
            }
            previousLineWasComment = false;
            out.append(line).append('\n');
        }
        // split 会在末尾多出一个空串，去掉它带来的多余换行
        int length = out.length();
        while (length >= 2 && out.charAt(length - 1) == '\n' && out.charAt(length - 2) == '\n') {
            out.setLength(--length);
        }
        return out.toString();
    }

    /** 模板开头、第一个键之前的整段注释。 */
    private static String collectHeader(String template) {
        StringBuilder header = new StringBuilder();
        for (String line : template.split("\n", -1)) {
            String trimmed = line.strip();
            if (trimmed.startsWith("#")) {
                header.append(trimmed).append('\n');
                continue;
            }
            if (trimmed.isEmpty() && header.length() > 0) {
                header.append('\n');
                continue;
            }
            break;
        }
        return header.toString();
    }

    private static Map<String, List<String>> collectComments(String template) {
        Map<String, List<String>> comments = new LinkedHashMap<>();
        List<String> pending = new ArrayList<>();
        Deque<String> path = new ArrayDeque<>();
        int previousIndent = -1;
        boolean seenKey = false;
        for (String line : template.split("\n", -1)) {
            String trimmed = line.strip();
            if (trimmed.startsWith("#")) {
                // 文件开头那段属于 header，不要挂到第一个键上重复输出
                if (seenKey) {
                    pending.add(trimmed);
                }
                continue;
            }
            if (trimmed.isEmpty()) {
                pending.clear();
                continue;
            }
            int colon = keyColonIndex(trimmed);
            if (colon <= 0) {
                pending.clear();
                continue;
            }
            seenKey = true;
            int indent = indentOf(line);
            String key = trimmed.substring(0, colon).strip();
            while (previousIndent >= indent && !path.isEmpty()) {
                path.removeLast();
                previousIndent -= 2;
            }
            path.addLast(key);
            previousIndent = indent;
            if (!pending.isEmpty()) {
                comments.put(String.join(".", path), List.copyOf(pending));
                pending.clear();
            }
        }
        return comments;
    }

    private static int indentOf(String line) {
        int index = 0;
        while (index < line.length() && line.charAt(index) == ' ') {
            index++;
        }
        return index;
    }

    /**
     * 找到「键: 值」里那个冒号的位置；不是键值行时返回 -1。
     *
     * <p>列表项（{@code - foo}）不算键，注释也不往列表项上挂。
     */
    private static int keyColonIndex(String trimmed) {
        if (trimmed.startsWith("-")) {
            return -1;
        }
        boolean quoted = false;
        for (int index = 0; index < trimmed.length(); index++) {
            char current = trimmed.charAt(index);
            if (current == '"' || current == '\'') {
                quoted = !quoted;
            } else if (current == ':' && !quoted) {
                boolean endOfLine = index == trimmed.length() - 1;
                if (endOfLine || trimmed.charAt(index + 1) == ' ') {
                    return index;
                }
            }
        }
        return -1;
    }
}
