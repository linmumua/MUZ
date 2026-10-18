package linmumua.doudizhu.ui;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * 旧箱子 UI（管理菜单 / 个人设置 / 战绩 / 选牌音效与动作 / 道具箱）的生产路径必须全部不可达。
 *
 * <p>【迁移说明：1.10.60 Dialog 迁移的验收点】计划明确要求「不能出现『新建了 Dialog 但旧箱子
 * 入口仍可打开』」。核心代理采用的收口路线是：<b>保留 {@code HandGuiService} 的箱体绘制实现，
 * 但把所有生产调用方改成 {@code plugin.open*()} Dialog 门面</b>。因此「不可达」这条不变量
 * <b>必须扫调用方</b>，而不是去断言 {@code HandGuiService} 源码里没有 {@code createInventory}
 * ——后者会误报：旧绘制作为允许暂留的死实现仍在文件里。
 *
 * <p>判定：命令层、牌桌交互（唯一的非命令生产路径）、插件门面本身，都不得再调用
 * {@code getHandGuiService().openXxx(...)} 这批老入口；它们必须改走 {@code plugin.open*} 门面，
 * 而门面必须接的是 Dialog 屏。
 *
 * <p>（旧 {@code HandGuiListener} 里仍有 {@code getHandGuiService().openXxx} 调用，但那些都挂在
 * 「点击旧箱子 Inventory」的分支上；既然不再有任何生产路径创建 {@code HandInventoryHolder}/
 * {@code HistoryInventoryHolder}，这些分支永远不会被触发，属不可达的死链。）
 */
class LegacyChestUiUnreachableTest {
    private static final Path COMMAND =
        Path.of("src/main/java/linmumua/doudizhu/command/DoudizhuCommand.java");
    private static final Path TABLE =
        Path.of("src/main/java/linmumua/doudizhu/world/PhysicalTableManager.java");
    private static final Path PLUGIN =
        Path.of("src/main/java/linmumua/doudizhu/DoudizhuPlugin.java");
    private static final Path HAND_GUI =
        Path.of("src/main/java/linmumua/doudizhu/ui/HandGuiService.java");

    /** 旧箱子菜单入口（迁 Dialog 后不得再被任何生产调用方调用）。 */
    private static final List<String> LEGACY_OPENERS = List.of(
        "getHandGuiService().openSettings(",
        "getHandGuiService().openAdminModels(",
        "getHandGuiService().openHistory(",
        "getHandGuiService().openHistoryDetail(",
        "getHandGuiService().openSelectionSoundPicker(",
        "getHandGuiService().openPlayActionPicker(",
        "getHandGuiService().openPlayActionKindMenu(",
        "getHandGuiService().openAdminSelectionSoundEditor(",
        "getHandGuiService().openAdminPlayActionKindPicker(",
        "getHandGuiService().openAdminPlayActionEditor(",
        "getHandGuiService().openCountdownSoundPicker(",
        "getHandGuiService().openUnreadyWarningSoundPicker(",
        "getHandGuiService().openPlacementBlockedSoundPicker("
    );

    @Test
    void 命令层与牌桌交互不再调用旧箱子菜单入口() throws IOException {
        for (Path caller : List.of(COMMAND, TABLE, PLUGIN)) {
            String source = Files.readString(caller);
            for (String opener : LEGACY_OPENERS) {
                assertFalse(source.contains(opener),
                    caller.getFileName() + " 仍在调用旧箱子入口 " + opener + "，生产路径没有真正改指向 Dialog");
            }
        }
    }

    @Test
    void 命令层改走Dialog门面() throws IOException {
        String command = Files.readString(COMMAND);
        for (String facade : List.of(
            "plugin.openPlayerSettings(player)",
            "plugin.openAdminMenu(player)",
            "plugin.openHistoryDialog(",
            "plugin.openGadgetPanel(player)",
            "plugin.openGadgetEditor(player)",
            "plugin.openGadgetVoice(player)",
            "plugin.selectGadgetSlot(player,"
        )) {
            assertTrue(command.contains(facade), "命令层没有改走 Dialog 门面：" + facade);
        }
    }

    /** 牌桌「个人设置」按钮是唯一的非命令生产入口，必须改走门面。 */
    @Test
    void 牌桌个人设置按钮改走Dialog门面() throws IOException {
        String table = Files.readString(TABLE);
        assertTrue(table.contains("plugin.openPlayerSettings(player)"),
            "牌桌「个人设置」按钮必须改调 plugin.openPlayerSettings(player)，而不是旧箱子 openSettings");
    }

    /** 门面必须接的是 Dialog 屏，而不是把调用重新绕回旧箱子。 */
    @Test
    void 插件门面接的是Dialog屏() throws IOException {
        String plugin = Files.readString(PLUGIN);
        assertTrue(plugin.contains("muzDialogService.openRoot(player.getUniqueId(), new PlayerSettingsDialogScreen(this))"),
            "个人设置门面必须打开 PlayerSettingsDialogScreen");
        assertTrue(plugin.contains("muzDialogService.openRoot(player.getUniqueId(), AdminDialogScreen.home(this))"),
            "管理菜单门面必须打开 AdminDialogScreen");
        assertTrue(plugin.contains("new HistoryDialogScreen(this, muzDialogService)"),
            "战绩门面必须打开 HistoryDialogScreen");
        assertTrue(plugin.contains("gadgetBoxDialogWiring.openEditor(player)"),
            "道具箱编辑门面必须打开 GadgetBox 编辑 Dialog");
        // 悬浮面板门面走 GadgetPanelService；不锁参数名——dialog-core 按 Folia lane 要求把它改成
        // 先 enqueuePlayer 到 player lane 再以 target 调用 openPanel(target)，字面量 (player) 会变。
        assertTrue(plugin.contains("gadgetPanelService.openPanel("),
            "悬浮面板门面必须走 GadgetPanelService（MuMenu 面板）");
        assertTrue(plugin.contains("enqueuePlayer("),
            "悬浮面板门面必须在 player lane 内调用（Folia lane 要求），不得裸调玩家 API");
    }

    /**
     * 旧箱子的两个 Holder 只能由 {@code HandGuiService} 自己创建——这是「旧箱子不可达」最硬的静态证据：
     * 既然没有第二个生产文件能造出 {@code HandInventoryHolder}/{@code HistoryInventoryHolder}，
     * 而 {@code HandGuiService} 的创建入口又不再被任何生产调用方触发，箱子便无法被打开。
     *
     * <p>为什么单独一条：只扫「调用方没调 open*」还不够——有人可能在别处 new 一个 holder 再自己
     * createInventory。这条把「制造 holder 的能力」也锁死在 HandGuiService 内部。
     */
    @Test
    void 旧箱子的Holder只能由HandGuiService创建() throws IOException {
        List<Path> mainSources = mainSources();
        List<String> offenders = new ArrayList<>();
        for (Path file : mainSources) {
            if (file.getFileName().toString().equals("HandGuiService.java")) {
                continue;
            }
            String source = Files.readString(file);
            if (source.contains("new HandInventoryHolder(") || source.contains("new HistoryInventoryHolder(")) {
                offenders.add(file.getFileName().toString());
            }
        }
        assertTrue(offenders.isEmpty(),
            "这些文件能创建旧箱子 Holder，旧箱子生产路径不再唯一的收口于 HandGuiService：" + offenders);
    }

    private static List<Path> mainSources() throws IOException {
        Path root = Path.of("src/main/java/linmumua/doudizhu");
        try (var stream = Files.walk(root)) {
            List<Path> sources = stream.filter(path -> path.toString().endsWith(".java")).toList();
            assertFalse(sources.isEmpty(), "找不到主源码，这条测试的锚点已失效");
            return sources;
        }
    }

    /**
     * HandGuiService 的 public 菜单入口不得再触达旧箱子实现。
     *
     * <p>【1.10.60 改薄形态】core 把 public {@code open*()} 全部改薄为 Dialog 兼容委托
     * （例如 {@code openSettings(Player)} → {@code plugin.openPlayerSettings(player)}），
     * 旧箱子实现整体下沉为 private {@code *Legacy(...)}（保留作历史参考）。因此这条契约是
     * 「旧箱子绝不生产可达」的最直接证据：<b>凡是 public 方法体里出现
     * {@code createInventory(}、{@code .openInventory(} 或对 {@code *Legacy(} 的调用，即判红。</b>
     *
     * <p>判据按「离该出现点最近的 4 空格缩进可见性声明」归属方法，并先剥注释——否则 public
     * open* 的 javadoc 里 {@code {@link #openSettingsLegacy(Player)}} 这类历史说明会被误算成触达。
     */
    @Test
    void 旧箱子实现只允许private且不被任何public入口触达() throws IOException {
        String code = stripComments(Files.readString(HAND_GUI));

        List<String> offenders = new ArrayList<>();
        for (String needle : List.of("createInventory(", ".openInventory(", "Legacy(")) {
            int from = 0;
            while (true) {
                int at = code.indexOf(needle, from);
                if (at < 0) {
                    break;
                }
                from = at + needle.length();
                if (visibilityOfEnclosingMember(code, at).equals("public")) {
                    offenders.add(needle + " @" + lineOf(code, at));
                }
            }
        }

        assertTrue(offenders.isEmpty(),
            "public 菜单入口仍触达旧箱子实现（createInventory/openInventory/Legacy），生产路径没有真正不可达：" + offenders);
    }

    /** 离 {@code at} 最近的 4 空格缩进成员声明的可见性（public/private/protected/未知）。 */
    private static String visibilityOfEnclosingMember(String code, int at) {
        int best = -1;
        String bestVis = "未知";
        for (String vis : List.of("public", "private", "protected")) {
            int idx = code.lastIndexOf("\n    " + vis + " ", at);
            if (idx > best) {
                best = idx;
                bestVis = vis;
            }
        }
        return bestVis;
    }

    private static int lineOf(String code, int at) {
        int line = 1;
        for (int i = 0; i < at; i++) {
            if (code.charAt(i) == '\n') {
                line++;
            }
        }
        return line;
    }

    /** 去掉块注释与行注释（退役说明常以注释保留被退役 API 的名字）。 */
    private static String stripComments(String source) {
        return source.replaceAll("(?s)/\\*.*?\\*/", "").replaceAll("(?m)//.*$", "");
    }
}
