package linmumua.doudizhu.ui.dialog;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * 源码契约：把「道具箱迁 Dialog 必须满足的硬要求」钉在源码上。
 *
 * <p>为什么不靠行为测试：本屏要真实 Bukkit + 原生 Dialog 才能跑，且 {@code MuzDialogContext}
 * 经 {@code DoudizhuPlugin} 传递依赖 Vault/PlaceholderAPI（测试运行期不在 classpath 上），
 * 加载屏类会让测试挂在与被测逻辑无关的类上。源码扫描能零侵入地守住这些要求，
 * 与同目录 {@code AdminDialogContractTest}、{@code AdminSettingRangeReleasedTest} 手法一致。
 */
class GadgetBoxDialogScreenContractTest {
    private static final Path SCREEN =
        Path.of("src/main/java/linmumua/doudizhu/ui/dialog/GadgetBoxDialogScreen.java");
    private static final Path GUI_SERVICE =
        Path.of("src/main/java/linmumua/doudizhu/ui/GadgetBoxGuiService.java");

    private static String screen() throws IOException {
        return Files.readString(SCREEN);
    }

    @Test
    void 实现Dialog屏且标识稳定() throws IOException {
        String source = screen();

        assertTrue(source.contains("implements MuzDialogScreen"), "必须是 MuzDialogScreen 实现");
        assertTrue(source.contains("SCREEN_KEY = \"gadget_box\""), "screenKey 必须稳定为 gadget_box");
        assertTrue(source.contains("public Dialog build(MuzDialogContext ctx)"), "必须实现 build 入口");
    }

    /**
     * 「不能 Inventory 界面、不能靠拖放」：屏内不得出现任何 Bukkit Inventory 窗口/事件/写入 API。
     * 读背包取来源物品（{@code getInventory().getItem(...)}）是允许的，用「打开窗口/写槽位」类 API 才算违规。
     */
    @Test
    void 屏内不得出现Inventory界面或拖放路径() throws IOException {
        String source = screen();

        for (String forbidden : List.of(
            "createInventory", "openInventory(", "setItem(", "InventoryClickEvent", "InventoryDragEvent",
            "InventoryHolder", "getOpenInventory")) {
            assertFalse(source.contains(forbidden), "Dialog 屏不得出现 Inventory 界面/拖放语义：" + forbidden);
        }
    }

    /** 展示回调里禁止文件 IO / 服务端调度：写盘一律经 GadgetBoxPersistence。 */
    @Test
    void 屏内不做IO也不碰存储实现() throws IOException {
        String source = screen();

        for (String forbidden : List.of(
            "VirtualGadgetBarStore", "MuzYamlConfig", "java.nio", "java.util.logging", "Scheduler",
            ".save(" )) {
            assertFalse(source.contains(forbidden), "屏内禁止直接 IO/调度：" + forbidden);
        }
        assertTrue(source.contains("persistence.persist("), "保存必须经 GadgetBoxPersistence 接缝");
        assertTrue(source.contains("private void save(MuzDialogContext ctx"), "保存入口必须收口成单一方法");
    }

    /** 复制必须复用纯逻辑（克隆 + 单件快照、不消耗来源），且屏内不得自己 new 物品。 */
    @Test
    void 复制复用纯逻辑且不自行造物() throws IOException {
        String source = screen();

        assertTrue(source.contains("GadgetBoxEditor.copyInto("), "复制必须复用 GadgetBoxEditor");
        assertTrue(source.contains("GadgetBoxEditor.removeSlot("), "移除必须复用 GadgetBoxEditor");
        assertTrue(source.contains("GadgetBoxEditor.occupiedMask("), "占用位判定必须复用纯逻辑");
        assertFalse(source.contains("new ItemStack"), "屏内不得自行构造物品");
        assertFalse(source.contains("setAmount("), "数量归一留给纯逻辑，屏内不直接改数量");
    }

    /** 四类按钮与满槽提示必须都在；选中/移除按「真实槽位」而不是物品类型。 */
    @Test
    void 按钮覆盖选中移除添加与语音且满槽有提示() throws IOException {
        String source = screen();

        assertTrue(source.contains("selectAction.accept(playerId, realSlot)"), "选中必须传真实槽位");
        assertTrue(source.contains("\"移除 \""), "缺少移除按钮");
        assertTrue(source.contains("添加主手物品"), "缺少添加主手按钮");
        assertTrue(source.contains("添加背包槽位"), "缺少按背包槽位添加按钮");
        assertTrue(source.contains("getItemInMainHand()"), "主手来源必须取主手物品");
        assertTrue(source.contains("getItem(bagSlot)"), "背包来源必须按槽位取物品");
        assertTrue(source.contains("语音面板"), "缺少语音入口按钮");
        assertTrue(source.contains("八格已满"), "缺少满槽提示文案");
        assertTrue(source.contains("MuzDialogFactory.item("), "八槽预览必须用 item 正文块");
    }

    /** 语音必须先关编辑屏再由装配方在合法 lane 打开面板，避免界面互相压栈。 */
    @Test
    void 语音按钮先关屏再打开面板() throws IOException {
        String source = screen();
        int button = source.indexOf("语音面板");
        int close = source.indexOf("ctx.close();", button);
        int voice = source.indexOf("voiceAction.accept(playerId);", button);

        assertTrue(button >= 0 && close > button && voice > close, "必须先 ctx.close() 再 voiceAction.accept(...)");
    }

    /** 回调只走 ctx.action（带代次/权限/player lane 包装），禁止绕过包装的工厂回调。 */
    @Test
    void 回调统一走上下文包装() throws IOException {
        String source = screen();

        assertTrue(source.contains("ctx.action("), "按钮必须用 ctx.action 构建");
        assertFalse(source.contains("MuzDialogFactory.callback("), "不得绕过代次包装自造回调");
        assertFalse(source.contains("showDialog"), "屏内不得直接展示 Dialog");
    }

    /** 四个接缝参数 fail loud，避免装配漏接线时静默变成不可用面板。 */
    @Test
    void 接缝参数必须failLoud() throws IOException {
        String source = screen();

        for (String parameter : List.of("barSource", "persistence", "selectAction", "voiceAction")) {
            assertTrue(source.contains("Objects.requireNonNull(" + parameter + ", \"" + parameter + "\")"),
                "接缝参数必须 requireNonNull：" + parameter);
        }
    }

    /** 保存完成后回本屏重建，才能显示最新快照（而不是留在旧数据上）。 */
    @Test
    void 保存后重建本屏() throws IOException {
        String source = screen();

        assertTrue(source.contains("() -> ctx.open(this)"), "保存完成后必须重建本屏");
    }

    /**
     * 旧箱子生产路径必须不可达：{@code GadgetBoxGuiService.open} 委托 Dialog opener，
     * 且整个类不再保留 Inventory 创建路径；同时旧测试依赖的锚点与退役注释必须仍在。
     */
    @Test
    void 旧箱子open改薄为Dialog委托且Inventory创建路径消失() throws IOException {
        String source = Files.readString(GUI_SERVICE);

        assertFalse(source.contains("Bukkit.createInventory("),
            "旧 Inventory 箱子创建 API 必须彻底不可达（退役说明注释可保留该词）");
        assertTrue(source.contains("public void setDialogOpener(GadgetBoxDialogOpener opener)"),
            "必须提供 opener 注入接缝");
        assertTrue(source.contains("private volatile GadgetBoxDialogOpener dialogOpener"),
            "必须持有可注入的 opener 字段");

        int start = source.indexOf("public void open(Player player)");
        assertTrue(start >= 0, "缺少 open(Player)");
        int end = source.indexOf("\n    public ", start + "public void open(Player player)".length());
        if (end < 0) {
            end = source.length();
        }
        String openBody = source.substring(start, end);
        assertTrue(openBody.contains("opener.open(player)"), "open 必须委托 dialogOpener");
        assertTrue(openBody.contains("requirePrimaryThread(\"open\")"), "open 必须保留主线程门禁");
        assertFalse(openBody.contains("store.load"), "open 不得再读取存储");

        // 已退役的能力仍保留给旧窗口（历史兼容），且注释必须留下（禁止整类删除式收口）。
        assertTrue(source.contains("private void saveAndRender("), "旧保存链锚点必须仍在");
        assertTrue(source.contains("void notifySaved(UUID playerId)"), "旧通知锚点必须仍在");
        assertTrue(source.contains("handleClick(InventoryClickEvent event)"), "旧窗口点击处理保留");
        assertTrue(source.contains("【已退役：open 路径改 Dialog 委托】"), "必须留下退役说明注释");
    }
}
