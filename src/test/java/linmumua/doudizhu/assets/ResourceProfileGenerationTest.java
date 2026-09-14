package linmumua.doudizhu.assets;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;

/**
 * 守护构建期 resource profile 到 PackTiers 与 CraftEngine provider 的裁剪边界。
 *
 * <p>这些断言只针对仓库默认 muz-resource-profile.yml；profile 改档位时应同时更新
 * profile 与本测试的预期，避免构建又悄悄退回全量生成。测试读取真实生成目录，不手造
 * YAML 片段，因此能发现「PackTiers 已裁剪但 provider 仍全量」这类两侧不同步问题。
 */
class ResourceProfileGenerationTest {

    private static final Path GENERATED_ROOT = Path.of(
        "build", "paper-26.2", "generated", "resources", "main", "craftengine", "muz"
    );
    private static final Path IMAGES_DIR = GENERATED_ROOT.resolve("configuration/images");

    private static void requireGeneratedBundle() {
        Assumptions.assumeTrue(Files.isDirectory(IMAGES_DIR),
            "构建生成物不存在，请先执行 generateCraftEngineBundle：" + IMAGES_DIR);
    }

    private static List<String> imagePartNames() throws IOException {
        requireGeneratedBundle();
        try (var stream = Files.list(IMAGES_DIR)) {
            return stream
                .map(path -> path.getFileName().toString())
                .filter(name -> name.endsWith(".yml"))
                .sorted()
                .toList();
        }
    }

    @Test
    void 默认Profile只把指定档位写入PackTiers() {
        assertArrayEquals(new int[] {53}, PackTiers.CARD_HEIGHT_TIERS);
        assertArrayEquals(new int[] {0, 50}, PackTiers.CARD_DOWN_OFFSET_TIERS);
        assertArrayEquals(new int[] {4, 6}, PackTiers.AVATAR_SCALE_TIERS);
        assertArrayEquals(new int[] {0, 122}, PackTiers.AVATAR_DOWN_OFFSET_TIERS);
        assertArrayEquals(new int[] {100}, PackTiers.COUNTER_SCALE_TIERS);
        assertArrayEquals(new int[] {0, 122}, PackTiers.COUNTER_DOWN_OFFSET_TIERS);
        assertArrayEquals(new int[] {100}, PackTiers.HOTBAR_SCALE_TIERS);
    }

    @Test
    void provider文件只包含Profile档位() throws IOException {
        List<String> names = imagePartNames();
        assertTrue(names.contains("card_h53.yml"), "缺少 profile 指定的牌高 provider");
        assertFalse(names.stream().anyMatch(name -> name.startsWith("card_h") && !name.equals("card_h53.yml")),
            "不应生成 profile 外的牌高 provider：" + names);
        assertTrue(names.contains("avatar_px_s4.yml"), "缺少 profile 指定的 4 倍头像 scale provider");
        assertTrue(names.contains("avatar_px_s6.yml"), "缺少 profile 指定的 6 倍头像 scale provider");
        assertFalse(names.stream().anyMatch(name -> name.startsWith("avatar_px_s")
                && !name.equals("avatar_px_s4.yml") && !name.equals("avatar_px_s6.yml")),
            "不应生成 profile 外的头像 scale provider：" + names);
        assertTrue(names.contains("counter.yml"), "缺少 profile 指定的默认 counter provider");
        assertFalse(names.stream().anyMatch(name -> name.startsWith("counter_s")),
            "不应生成 profile 外的 counter scale provider：" + names);
        assertTrue(names.contains("hotbar_hud.yml"), "缺少 profile 指定的 Hotbar provider");
        assertFalse(names.stream().anyMatch(name -> name.startsWith("hotbar_hud_s")),
            "Hotbar 只允许当前 scale，不应存在其它 scale provider：" + names);
    }

    @Test
    void hotbar当前Scale只声明底图和选中框() throws IOException {
        requireGeneratedBundle();
        Path hotbar = IMAGES_DIR.resolve("hotbar_hud.yml");
        Assumptions.assumeTrue(Files.isRegularFile(hotbar), "缺少 hotbar provider：" + hotbar);
        String yaml = Files.readString(hotbar, StandardCharsets.UTF_8);
        assertEquals(2, countOccurrences(yaml, "muz:hotbar_"),
            "当前 Hotbar scale 必须只声明底图与选中框");
        assertTrue(yaml.contains("muz:hotbar_slots:"), "缺少 Hotbar 底图声明");
        assertTrue(yaml.contains("muz:hotbar_select:"), "缺少 Hotbar 选中框声明");
        assertFalse(yaml.contains("hotbar_slots_s75") || yaml.contains("hotbar_slots_s125"),
            "Hotbar provider 不得声明 profile 外的 scale");
    }

    private static int countOccurrences(String text, String needle) {
        int count = 0;
        for (int at = 0; (at = text.indexOf(needle, at)) >= 0; at += needle.length()) {
            count++;
        }
        return count;
    }
}
