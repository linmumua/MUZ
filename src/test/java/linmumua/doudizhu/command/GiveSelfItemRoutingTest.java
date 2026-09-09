package linmumua.doudizhu.command;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

/**
 * 记牌器及其挂在 {@code /muz give} 上的取法。
 *
 * <p>用源码扫描而不是调用方法：构造物品要 Bukkit 的 ItemStack 与 ItemMeta，命令分流要
 * CommandSender 和 Player，这个项目跑不起 Bukkit。写法沿用 {@code HandCardClickRoutingTest}。
 */
class GiveSelfItemRoutingTest {
    private static final Path COMMAND =
        Path.of("src/main/java/linmumua/doudizhu/command/DoudizhuCommand.java");
    private static final Path PLUGIN =
        Path.of("src/main/java/linmumua/doudizhu/DoudizhuPlugin.java");

    private String command() throws IOException {
        return Files.readString(COMMAND);
    }

    private String plugin() throws IOException {
        return Files.readString(PLUGIN);
    }

    /**
     * counter/debug 只在恰好两参时当关键字，绝不能无条件占用第二参。
     *
     * <p>第二参原本是玩家名。一旦去掉个数判断直接把 args[1] 当关键字，服上真有人叫
     * counter 或 debug，管理员就再也没法给他放桌器——而且这种冲突只在特定玩家名下暴露，平时测不出来。
     *
     * <p>失败条件：把 {@code args.length == 2} 这个前置条件删掉或放宽。
     */
    @Test
    void 自发物品关键字必须靠参数个数消歧而不是抢占玩家名位() throws IOException {
        String source = command();
        assertTrue(source.contains("args.length == 2 && isSelfGiveToken(args[1])"),
            "counter/debug 必须同时满足「恰好两参」才当关键字，否则会遮蔽同名玩家");
        assertTrue(source.contains("normalized.equals(\"counter\") || normalized.equals(\"debug\")"),
            "自发物品关键字必须同时包含 counter 和 debug");
    }

    /**
     * 放桌器那条老语法必须还在，且现在有显式的元数校验。
     *
     * <p>改动前的代码直接取 args[1] 和 args[2] 而没有 requireArgs，只打 /muz give 会抛
     * 数组越界而不是用法提示。顺手补上的同时得保证老语法本身没被我改坏。
     *
     * <p>失败条件：放桌器分支被误删，或元数校验又被拿掉。
     */
    @Test
    void give包括调试棒和记牌器且需要管理员权限() throws IOException {
        String source = command();
        int start = source.indexOf("case \"give\" -> {");
        int end = source.indexOf("case \"history\" -> {", start);
        assertTrue(start >= 0 && end > start, "give 顶层分支应当存在");
        String region = source.substring(start, end);
        assertTrue(region.contains("hasPermission(\"muz.admin\")"),
            "give debug/counter 属于管理员物品发放，必须保留 muz.admin 权限门");
        assertTrue(region.contains("createDoudizhuTablePlacerItem(tableId, level)"),
            "放桌器分支不能被自发物品分支挤掉");
        assertTrue(region.contains("requireArgs(args, 3, \"/muz give <玩家> doudizhu"),
            "give 至少要校验到第三参，否则只打 /muz give 会抛 ArrayIndexOutOfBounds");
    }

    /**
     * 记牌器必须打上独立的 PDC 标记。
     */
    @Test
    void 记牌器使用独立PDC标记() throws IOException {
        String source = plugin();
        assertTrue(source.contains("new NamespacedKey(this, \"counter-item\")"),
            "记牌器要有自己的 PDC key");
        assertTrue(source.contains("set(counterItemKey, PersistentDataType.STRING"),
            "记牌器必须真的把标记写进 PDC");
    }

    /**
     * 记牌器识别只看 PDC，不看显示名。
     *
     * <p>失败条件：isCounterItem 改成比对 displayName 或 lore。
     */
    @Test
    void 物品识别不得依赖显示名() throws IOException {
        String source = plugin();
        int start = source.indexOf("public boolean isCounterItem");
        assertTrue(start > 0, "插件必须提供 isCounterItem(ItemStack) API");
        int open = source.indexOf('{', start);
        assertTrue(open > start, "找不到 isCounterItem 方法体");
        int depth = 0;
        int end = -1;
        for (int index = open; index < source.length(); index++) {
            char character = source.charAt(index);
            if (character == '{') {
                depth++;
            } else if (character == '}' && --depth == 0) {
                end = index;
                break;
            }
        }
        assertTrue(end > start, "找不到 isCounterItem 方法结束位置");
        String region = source.substring(start, end);
        assertFalse(region.contains("displayName") || region.contains("getLore"),
            "识别物品只能看 PDC；显示名可被改名或被别的插件伪造");
        assertTrue(region.contains("hasStringMarker(itemStack, counterItemKey, \"counter\")"),
            "isCounterItem 必须复用 counter 字符串标记识别逻辑");
    }

    /**
     * 背包满时物品要掉在脚边，不能凭空消失。
     *
     * <p>addItem 塞不下会把余下的部分作为返回值交回来，忽略返回值就等于把物品删掉。
     * 放桌器原来处理了这件事，抽成 giveOrDrop 之后两条路径都得继续处理。
     *
     * <p>失败条件：giveOrDrop 丢掉 addItem 的返回值。
     */
    @Test
    void 背包塞不下时物品掉地而不是消失() throws IOException {
        String source = command();
        int start = source.indexOf("private void giveOrDrop");
        assertTrue(start > 0, "两条发放路径应共用 giveOrDrop");
        String region = source.substring(start, start + 400);
        assertTrue(region.contains("addItem(item)") && region.contains("dropItemNaturally"),
            "addItem 的剩余部分必须掉地，否则背包满时物品被静默吞掉");
    }

    /**
     * give 的回执文案不能再说「拿在手上才生效」。
     *
     * <p>记牌器已改成背包里有就生效（主手要留给选牌/出牌）。回执还写旧说法会把人
     * 引到错误的用法上——这正是上一版没显示的直接原因。
     *
     * <p>失败条件：回执退回「拿在手上」的说法。
     */
    @Test
    void 记牌器回执说的是带在身上而不是拿在手上() throws IOException {
        String source = command();
        assertTrue(source.contains("已把记牌器放进你的背包，带在身上就生效。"),
            "回执要说带在身上");
        assertFalse(source.contains("拿在手上才生效"),
            "记牌器不再要求握在主手，回执不能再这么说");
    }

    /**
     * 补全必须同时提示 counter、debug 与在线玩家名，且不丢掉放桌器语法的玩家目标。
     */
    @Test
    void 补全提示记牌器调试棒和在线玩家入口() throws IOException {
        String source = command();
        int start = source.indexOf("args.length == 2 && args[0].equalsIgnoreCase(\"give\")");
        assertTrue(start > 0, "give 的第二参补全分支应当存在");
        String region = source.substring(start, start + 360);
        assertTrue(region.contains("\"counter\""), "补全必须列出 counter");
        assertTrue(region.contains("\"debug\""), "补全必须列出 debug");
        assertTrue(region.contains("getOnlinePlayers"),
            "在线玩家名仍要继续补全，放桌器语法还要用");
    }

    @Test
    void debug物品分流只允许恰好两参且复用自发物品路径() throws IOException {
        String source = command();
        assertTrue(source.contains("if (args[1].equalsIgnoreCase(\"debug\"))"),
            "give debug 必须在自发物品分流内处理");
        assertTrue(source.contains("plugin.createHudDebugStickItem()"),
            "give debug 必须调用 HUD 调试棒工厂");
        assertTrue(source.contains("已把 HUD 调试棒放进你的背包。"),
            "give debug 必须发送中文回执");
        assertFalse(source.contains("/muz debug show"), "不得恢复旧 /muz debug show 入口");
        assertFalse(source.contains("/muz debug stick"), "不得恢复旧 /muz debug stick 入口");
        assertFalse(source.contains("/muz debug hud"), "不得恢复旧 /muz debug hud 入口");
    }

    @Test
    void debug子命令补全不包含旧入口() throws IOException {
        String source = command();
        int start = source.indexOf("args.length == 2 && args[0].equalsIgnoreCase(\"debug\")");
        assertTrue(start > 0, "debug 子命令补全分支应当存在");
        String region = source.substring(start, start + 260);
        assertFalse(region.contains("\"show\""));
        assertFalse(region.contains("\"stick\""));
        assertFalse(region.contains("\"hud\""));
    }
}
