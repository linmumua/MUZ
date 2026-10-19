package linmumua.doudizhu.ui.dialog;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;

/**
 * Dialog 构建参数契约：{@code pause} 与 {@code afterAction} 必须成对使用。
 *
 * <p>【这条测试守的是实服已经炸过的缺陷】Paper 的 {@code DialogBase} 有硬校验：
 * 当 {@code pause = true}（默认值）时，{@code after_action} 必须能解除暂停
 * （{@code CLOSE} / {@code WAIT_FOR_RESPONSE}），否则 {@code build()} 直接抛
 * {@code IllegalArgumentException("Dialogs that pause the game must use after_action values
 * that unpause it after user action!")}。
 *
 * <p>本插件刻意用 {@code NONE}（按钮回调自己决定开/关，不依赖客户端 after_action），
 * 因此**必须**同时显式写 {@code pause(false)}。一旦有人删掉那一行，**所有** Dialog
 * （管理、设置、战绩、道具箱）都会构建失败，而失败只表现为「点了没反应」——
 * 正是现场排查很久的那个症状。编译器不会拦，单元测试也造不出真实 Dialog
 * （需要服务端注册 provider），所以这里用源级契约把关系钉死。
 */
class MuzDialogFactoryPauseContractTest {
    private static final Path MAIN_JAVA = Path.of("src/main/java");

    /**
     * 工厂必须在 {@code afterAction} 之前显式关掉 pause。
     *
     * <p>顺序也断言：先 {@code pause(false)} 再 {@code afterAction(NONE)} 读起来是
     * 「不暂停 + 不自动关」，反过来写容易让人误以为 NONE 是默认安全值。
     */
    @Test
    void 工厂必须显式关闭pause并排在afterAction之前() throws Exception {
        String source = Files.readString(
            MAIN_JAVA.resolve("linmumua/doudizhu/ui/dialog/MuzDialogFactory.java"));
        int pause = source.indexOf(".pause(false)");
        int afterAction = source.indexOf(".afterAction(");
        assertTrue(pause > 0, "MuzDialogFactory 必须显式调用 pause(false)，否则 Dialog 构建会在实服抛异常");
        assertTrue(afterAction > pause, "pause(false) 必须写在 afterAction 之前，且不能只依赖默认值");
        assertTrue(source.contains("DialogAfterAction.NONE"),
            "按钮回调自管开/关，afterAction 应为 NONE（与 pause(false) 配套）");
    }

    /**
     * 按钮宽度必须有可点击的默认值，不能再用 1。
     *
     * <p>【这条守的是实服已经出现过的症状】Paper 的 {@code ActionButton} 宽度以对话框宽度的
     * 1/1024 为单位。旧代码把默认宽度写成 1，实服表现为「正文与关闭按钮都在，按钮区只剩一条
     * 极窄竖线」——按钮确实存在、但既看不见文字也点不到。这里钉住默认值下限，并禁止
     * {@code MuzDialogContext} 再传字面量 1。
     */
    @Test
    void 按钮默认宽度必须可见可点() throws Exception {
        assertTrue(MuzDialogFactory.DEFAULT_BUTTON_WIDTH >= 100,
            "默认按钮宽度必须足够显示文字（当前 " + MuzDialogFactory.DEFAULT_BUTTON_WIDTH + "）");
        String context = Files.readString(
            MAIN_JAVA.resolve("linmumua/doudizhu/ui/dialog/MuzDialogContext.java"));
        assertTrue(!context.contains(", 1, view ->") && !context.contains(", 1, handler)"),
            "MuzDialogContext 不得再传字面量宽度 1（会渲染成一条竖线）");
        String buttons = Files.readString(
            MAIN_JAVA.resolve("linmumua/doudizhu/ui/dialog/DialogButtons.java"));
        assertTrue(!buttons.contains("label, null, 1,"),
            "DialogButtons 不得再传字面量宽度 1");
    }

    /**
     * 全仓只有工厂一处构造 {@code DialogBase}，且那一处必须带 {@code pause(false)}。
     *
     * <p>如果将来有人在别的类里直接 {@code DialogBase.builder(...)}，默认 pause 会立刻
     * 把那个 Dialog 打成「打不开」，而症状与本次现场完全一致。这条断言让人一眼看到越界点。
     */
    @Test
    void 全仓DialogBase只在工厂构造且必须关闭pause() throws Exception {
        List<Path> builders = new ArrayList<>();
        try (Stream<Path> files = Files.walk(MAIN_JAVA)) {
            for (Path file : files.filter(path -> path.toString().endsWith(".java")).toList()) {
                String text = Files.readString(file);
                if (text.contains("DialogBase.builder(")) {
                    builders.add(file);
                }
            }
        }
        assertEquals(1, builders.size(),
            "DialogBase 只应在 MuzDialogFactory 构造（发现：" + builders + "），否则 pause 默认值会漏配");
        String factory = Files.readString(builders.getFirst());
        assertTrue(factory.contains(".pause(false)"),
            "唯一的 DialogBase 构造点必须显式 pause(false)");
    }
}
