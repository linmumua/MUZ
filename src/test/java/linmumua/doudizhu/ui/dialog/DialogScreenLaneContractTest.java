package linmumua.doudizhu.ui.dialog;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 新 Dialog 屏的源码契约。
 *
 * <p>锁三条硬约束（都来自项目既有 Dialog / owner-lane 约定）：
 * <ol>
 *   <li><b>必须走 core 的 Dialog 接缝</b>：只实现 {@link MuzDialogScreen}，用 {@link MuzDialogFactory}
 *       建屏，不自己 {@code Dialog.create(...)}、不自己管代次。</li>
 *   <li><b>不得出现裸 Player API</b>：{@code player.showDialog/closeDialog/sendMessage/playSound/...}
 *       一律不得直接调用——展示、提示、关闭由 core 的 player lane 负责，试听音效走既有
 *       {@code PlayerOutputDispatcher} 出口。这与 {@code HandGuiServicePlayerLaneContractTest}
 *       守的是同一件事。</li>
 *   <li><b>权限标注</b>：管理侧屏必须声明 {@code muz.admin}，个人侧屏不得声明权限。</li>
 * </ol>
 */
class DialogScreenLaneContractTest {

    private static final Path DIR = Path.of("src/main/java/linmumua/doudizhu/ui/dialog");
    private static final List<String> SCREENS = List.of(
        "PlayerSettingsDialogScreen.java",
        "SoundActionDialogScreen.java",
        "AiSettingsDialogScreen.java"
    );

    /** 裸 Player API：出现即视为绕过 core 的 player lane。 */
    private static final List<String> BARE_PLAYER_API = List.of(
        "player.showDialog(",
        "player.closeDialog(",
        "player.sendMessage(",
        "player.sendActionBar(",
        "player.sendTitle(",
        "player.playSound(",
        "player.closeInventory(",
        "player.openInventory(",
        "player.getOpenInventory("
    );

    private static String read(String name) throws IOException {
        return Files.readString(DIR.resolve(name));
    }

    @Test
    void 三个屏都实现core接口且不自己造框架() throws IOException {
        for (String screen : SCREENS) {
            String source = read(screen);
            assertTrue(source.contains("implements MuzDialogScreen"), screen + " 必须实现 MuzDialogScreen");
            assertFalse(source.contains("Dialog.create("), screen + " 不得自己 Dialog.create，请走 MuzDialogFactory");
            assertFalse(source.contains("new PlayerOutputDispatcher("),
                screen + " 不得自建 dispatcher，请复用 ActionBarOverlayService.outputDispatcher()");
        }
    }

    @Test
    void 三个屏都不得出现裸PlayerAPI() throws IOException {
        for (String screen : SCREENS) {
            String source = read(screen);
            for (String api : BARE_PLAYER_API) {
                assertFalse(source.contains(api), screen + " 出现裸 Player API：" + api);
            }
        }
    }

    @Test
    void 个人侧屏不得声明权限而管理侧屏必须声明() throws IOException {
        String settings = read("PlayerSettingsDialogScreen.java");
        String sound = read("SoundActionDialogScreen.java");
        String ai = read("AiSettingsDialogScreen.java");

        // 个人主页 KEY 是 "settings"，其 permission() 用接口默认（null）；全局色与 Admin 屏显式 "muz.admin"。
        assertTrue(settings.contains("\"muz.admin\""), "PlayerSettingsDialogScreen 的全局色目标必须声明 muz.admin");
        assertTrue(sound.contains("\"muz.admin\""), "SoundActionDialogScreen 的管理屏必须声明 muz.admin");
        assertTrue(ai.contains("\"muz.admin\""), "AiSettingsDialogScreen 必须声明 muz.admin");
    }

    @Test
    void 颜色输入复用core的RGB解析() throws IOException {
        String settings = read("PlayerSettingsDialogScreen.java");
        assertTrue(settings.contains("DialogInputParsers.parseRgbRequired"),
            "RGB 输入必须复用 core 的 DialogInputParsers.parseRgbRequired");
        assertTrue(settings.contains("MuzDialogFactory.textInput"),
            "颜色屏必须用 MuzDialogFactory.textInput 承载文本输入");
    }

    @Test
    void 自定义输入复用core的取消判定且试听走既有音效出口() throws IOException {
        String sound = read("SoundActionDialogScreen.java");
        assertTrue(sound.contains("DialogInputParsers.isCancel"),
            "自定义输入必须复用 core 的 DialogInputParsers.isCancel");
        assertTrue(sound.contains("outputDispatcher()"),
            "试听音效必须走既有 player lane 输出出口");
    }
}
