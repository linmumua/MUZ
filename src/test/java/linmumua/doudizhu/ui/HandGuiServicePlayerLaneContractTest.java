package linmumua.doudizhu.ui;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * HandGuiService 的玩家输出必须经 player lane 门面，不得裸调 {@code Player} 输出 API。
 *
 * <p>【迁移说明：1.10.60 菜单迁 Dialog】管理/个人/音效/战绩等菜单的 public {@code open*()}
 * 入口正被改薄成 Dialog 委托；本类若还留有旧箱体绘制（作为允许暂留的 private 死实现），
 * 只要它仍自行发起玩家输出，就必须经 {@code PlayerOutputDispatcher} 收口。
 *
 * <p>因此本测试的组织方式是：<b>负向不变量任何形态都成立</b>（不得裸调 Player 输出 API）；
 * <b>正向「必须经门面」只在「本类仍在产出玩家输出」时要求</b>——改薄到不再产出输出后，
 * 正向锚点已无对象，不应因此判红。这不是放宽，而是把「必须经门面」限定在它真正适用的前提上；
 * 「旧箱子生产不可达」这条另由 {@code LegacyChestUiUnreachableTest} 的调用方扫描与
 * public {@code open*()} body 检查单独守住。
 */
class HandGuiServicePlayerLaneContractTest {
    private static final Path SOURCE = Path.of("src/main/java/linmumua/doudizhu/ui/HandGuiService.java");

    @Test
    void playerOutputMustGoThroughPlayerLaneDispatcher() throws IOException {
        String source = Files.readString(SOURCE);

        // 负向不变量（任何形态都成立）：不得绕过门面裸调 Player 输出 API。
        assertFalse(source.contains("player.openInventory("), "不得裸调 player.openInventory(");
        assertFalse(source.contains("viewer.openInventory("), "不得裸调 viewer.openInventory(");
        assertFalse(source.contains("player.closeInventory("), "不得裸调 player.closeInventory(");
        assertFalse(source.contains("viewer.closeInventory("), "不得裸调 viewer.closeInventory(");
        assertFalse(source.contains("player.sendMessage("), "不得裸调 player.sendMessage(");
        assertFalse(source.contains("viewer.sendMessage("), "不得裸调 viewer.sendMessage(");
        assertFalse(source.contains("player.sendActionBar("), "不得裸调 player.sendActionBar(");
        assertFalse(source.contains("player.playSound("), "不得裸调 player.playSound(");

        // 正向：只要本类仍自行产出玩家输出（旧箱体绘制尚在），就必须经营运门面收口。
        boolean stillEmitsPlayerOutput = source.contains("openInventory(")
            || source.contains("sendMessage(")
            || source.contains("sendActionBar(")
            || source.contains("playSound(");
        if (stillEmitsPlayerOutput) {
            assertTrue(source.contains("output.runPlayer("),
                "本类仍在产出玩家输出，必须经 PlayerOutputDispatcher 的 player lane 收口");
        }
    }
}
