package linmumua.doudizhu.compat;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.function.Supplier;
import org.junit.jupiter.api.Test;

/**
 * bundle 内容指纹决定了资源到底会不会被导出到 CraftEngine，判错的代价是资源静默失效。
 *
 * <p>这个判定原先只比对 {@code pack.yml} 的字节，而那个文件只有 author / version /
 * description / namespace，不含任何文件清单。于是版本号没变时，新加的文件永远不会被复制
 * 过去 —— BossBar 轨道的两张透明贴图就是这么静默失效的：打进了 jar、单元测试全过，
 * 但从未落到 CraftEngine 的 resources 目录，客户端拿到的仍是原版不透明贴图。
 *
 * <p>所以指纹必须对四种变化全部敏感：内容改、文件增、文件删、文件改名。
 * 少覆盖任何一种，那一类改动就会重现「jar 里有、客户端没有」的静默失效。
 */
class BundleFingerprintTest {
    @Test
    void sameContentYieldsSameFingerprint() throws IOException {
        // 幂等性是前提：同样的输入必须得到同样的指纹，否则每次启动都会判定为「需要重导」，
        // 7.3 MB 的 bundle 会被反复复制，等于把这套缓存机制彻底废掉。
        assertEquals(
            fingerprint(entry("a.png", "AAA"), entry("b.yml", "BBB")),
            fingerprint(entry("a.png", "AAA"), entry("b.yml", "BBB")));
    }

    @Test
    void changedContentChangesFingerprint() throws IOException {
        // 最基本的一条：某个文件内容变了就必须重导。
        assertNotEquals(
            fingerprint(entry("a.png", "AAA")),
            fingerprint(entry("a.png", "AAB")));
    }

    @Test
    void addedFileChangesFingerprint() throws IOException {
        // 这正是 BossBar 贴图失效的那一类：清单里多了两个新文件，
        // 旧判定（只比 pack.yml）完全看不见，指纹必须看得见。
        assertNotEquals(
            fingerprint(entry("a.png", "AAA")),
            fingerprint(entry("a.png", "AAA"), entry("white_background.png", "transparent")));
    }

    @Test
    void removedFileChangesFingerprint() throws IOException {
        // 删文件也要重导，否则 CraftEngine 目录里会留着已经废弃的资源。
        assertNotEquals(
            fingerprint(entry("a.png", "AAA"), entry("b.yml", "BBB")),
            fingerprint(entry("a.png", "AAA")));
    }

    @Test
    void renamedFileChangesFingerprintEvenWhenContentIsIdentical() throws IOException {
        // 内容一模一样、只改了路径。若指纹只吃内容不吃路径，改名就会被漏掉，
        // 客户端会继续按老路径找贴图从而找不到。
        assertNotEquals(
            fingerprint(entry("boss_bar/white_background.png", "same")),
            fingerprint(entry("boss_bar/white_progress.png", "same")));
    }

    @Test
    void missingResourceFailsLoudlyInsteadOfSilentlySkipping() {
        // 清单里写了但 jar 里没有的条目属于构建出错。这里必须抛异常，
        // 不能当成「内容为空」算进指纹 —— 那样会算出一个看似正常的指纹，
        // 导出被跳过，而缺失的文件永远不会被发现。
        Map<String, Supplier<InputStream>> sources = new LinkedHashMap<>();
        sources.put("missing.png", () -> null);

        IOException failure =
            assertThrows(IOException.class, () -> CraftEngineBundleExporter.fingerprintOf(sources));
        assertEquals("Missing bundled resource: missing.png", failure.getMessage());
    }

    private static Map.Entry<String, String> entry(String path, String content) {
        return Map.entry(path, content);
    }

    @SafeVarargs
    private static String fingerprint(Map.Entry<String, String>... entries) throws IOException {
        Map<String, Supplier<InputStream>> sources = new LinkedHashMap<>();
        for (Map.Entry<String, String> item : entries) {
            byte[] bytes = item.getValue().getBytes(StandardCharsets.UTF_8);
            sources.put(item.getKey(), () -> new ByteArrayInputStream(bytes));
        }
        return CraftEngineBundleExporter.fingerprintOf(sources);
    }
}
