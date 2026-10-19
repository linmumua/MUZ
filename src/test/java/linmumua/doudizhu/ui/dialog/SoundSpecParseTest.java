package linmumua.doudizhu.ui.dialog;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

/**
 * 「音效名 + 音量 + 音高」解析与格式化的纯函数回归，外加一条渲染契约。
 *
 * <p>【为什么这些断言重要】管理侧音效屏现在用滑块调音量/音高，滑块初值来自当前配置字符串；
 * 解析一旦偏了，界面会显示错误的初值，管理员微调后保存的就是错的值——而且听起来只是「音量大了一点」，
 * 很难发现是解析问题。另外必须确认屏真的把滑块与「点击即试听」接上，否则又回到「点预设没声音」。
 */
class SoundSpecParseTest {

    /** 完整的 `音效名 音量 音高` 必须原样读出。 */
    @Test
    void 完整规格按音效名与两个数值解析() {
        SoundActionDialogScreen.SoundSpec parsed =
            SoundActionDialogScreen.SoundSpec.parse("minecraft:block.note_block.hat 0.4 1.0");
        assertEquals("minecraft:block.note_block.hat", parsed.key());
        assertEquals(0.4f, parsed.volume(), 1.0e-6);
        assertEquals(1.0f, parsed.pitch(), 1.0e-6);
    }

    /** 只有音效名时补默认值，不能因为缺参数抛异常（它只是用于预填滑块）。 */
    @Test
    void 只有音效名时补默认音量音高() {
        SoundActionDialogScreen.SoundSpec parsed =
            SoundActionDialogScreen.SoundSpec.parse("minecraft:ui.button.click");
        assertEquals("minecraft:ui.button.click", parsed.key());
        assertEquals(SoundActionDialogScreen.SoundSpec.DEFAULT_VOLUME, parsed.volume(), 1.0e-6);
        assertEquals(SoundActionDialogScreen.SoundSpec.DEFAULT_PITCH, parsed.pitch(), 1.0e-6);
    }

    /** 空输入不产生音效名（界面据此沿用当前音效）。 */
    @Test
    void 空输入没有音效名() {
        assertTrue(SoundActionDialogScreen.SoundSpec.parse("").key().isBlank());
        assertTrue(SoundActionDialogScreen.SoundSpec.parse(null).key().isBlank());
    }

    /** 非法数值回落默认值，而不是把整串解析成 NaN 再显示到滑块上。 */
    @Test
    void 非法数值回落默认值() {
        SoundActionDialogScreen.SoundSpec parsed =
            SoundActionDialogScreen.SoundSpec.parse("minecraft:a abc xyz");
        assertEquals("minecraft:a", parsed.key());
        assertEquals(SoundActionDialogScreen.SoundSpec.DEFAULT_VOLUME, parsed.volume(), 1.0e-6);
        assertEquals(SoundActionDialogScreen.SoundSpec.DEFAULT_PITCH, parsed.pitch(), 1.0e-6);
    }

    /**
     * 格式化与配置层存储精度一致（一位小数）。
     *
     * <p>刻意只断言**稳定可复现**的值：`0.45f` 的二进制近似略小于 0.45，
     * `%.1f` 会得到 "0.4" —— 与其把这条浮点细节写成期望，不如用 0.35 / 1.05 这类
     * 一位小数内可直接表示的值，断言语义才清楚（存储层同样用 `%.1f`，两边一致）。
     */
    @Test
    void 格式化统一一位小数() {
        assertEquals("0.4", SoundActionDialogScreen.SoundSpec.format(0.4f));
        assertEquals("0.3", SoundActionDialogScreen.SoundSpec.format(0.34f));
        assertEquals("1.0", SoundActionDialogScreen.SoundSpec.format(1.0f));
        assertEquals("2.0", SoundActionDialogScreen.SoundSpec.format(2.0f));
        assertEquals("minecraft:block.note_block.hat 0.4 1.0",
            SoundActionDialogScreen.SoundSpec.spec("minecraft:block.note_block.hat", 0.4f, 1.0f));
    }

    /** 解析与格式化互逆（对一位小数精度内成立）。 */
    @Test
    void 解析与格式化互逆() {
        String spec = SoundActionDialogScreen.SoundSpec.spec("minecraft:block.note_block.bell", 0.3f, 1.1f);
        SoundActionDialogScreen.SoundSpec parsed = SoundActionDialogScreen.SoundSpec.parse(spec);
        assertEquals("minecraft:block.note_block.bell", parsed.key());
        assertEquals(0.3f, parsed.volume(), 1.0e-6);
        assertEquals(1.1f, parsed.pitch(), 1.0e-6);
    }

    /**
     * 屏必须真的接上滑块与「点击即试听」。
     *
     * <p>源级契约：`buildAdminSound` 里要有 `numberRange("volume"`、`numberRange("pitch"`，
     * 且预设按钮的回调必须走 `applyAndPreviewSound`（只保存不发声就是用户报的那个问题）。
     */
    @Test
    void 音效屏必须提供滑块且点击即试听() throws Exception {
        String source = Files.readString(
            Path.of("src/main/java/linmumua/doudizhu/ui/dialog/SoundActionDialogScreen.java"));
        assertTrue(source.contains("numberRange(\"volume\""), "必须提供音量滑块");
        assertTrue(source.contains("numberRange(\"pitch\""), "必须提供音高滑块");
        assertTrue(source.contains("applyAndPreviewSound"), "预设与应用必须同时保存并试听");
        // 预设循环体里必须调用 applyAndPreviewSound（只保存不发声就是用户报的问题）。
        int loop = source.indexOf("for (Preset preset : countdownPresets())");
        assertTrue(loop >= 0, "必须仍有预设按钮循环");
        // 循环体到「循环的右花括号 + 紧跟的下一条 actions.add」为止：
        // 不能用 indexOf("actions.add(") 作为终点，那会命中循环体内部那一处、把证据截掉。
        int loopEnd = source.indexOf("\n        }\n", loop);
        String presetBlock = source.substring(loop, loopEnd > loop ? loopEnd : source.length());
        assertTrue(presetBlock.contains("applyAndPreviewSound"),
            "预设按钮回调必须走「保存 + 试听」，不能只保存");
        // 「试听当前」按钮用滑块值即时播放；源码里这行跨了两行，按关键调用片段断言。
        assertTrue(source.contains("soundFromView(ctx, view, current)"),
            "必须有独立的「试听当前」按钮，用滑块值即时播放");
        assertTrue(source.contains("previewSound(ctx, soundFromView"),
            "「试听当前」必须真的播放，而不是只刷新界面");
    }
}
