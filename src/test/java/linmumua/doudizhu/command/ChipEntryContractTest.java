package linmumua.doudizhu.command;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

/**
 * 锁住实体筹码在命令、管理 GUI 和配置说明中的入口契约。
 *
 * <p>这些入口由 Bukkit 驱动，测试环境不启动真实服务端，因此只检查源码中
 * 不应被回退的业务边界：余额非负、成功消息使用真实返回值、模板只保存完整
 * 元数据匹配规则，以及业务异常不被笼统 RuntimeException 吞掉。</p>
 */
class ChipEntryContractTest {
    private static final Path COMMAND = Path.of("src/main/java/linmumua/doudizhu/command/DoudizhuCommand.java");
    private static final Path GUI = Path.of("src/main/java/linmumua/doudizhu/ui/HandGuiService.java");
    private static final Path LISTENER = Path.of("src/main/java/linmumua/doudizhu/listener/HandGuiListener.java");
    private static final Path CONFIG = Path.of("src/main/resources/config.yml");

    @Test
    void commandUsesNonNegativeOnlineBalancesAndActualSetterResult() throws IOException {
        String source = Files.readString(COMMAND);

        assertTrue(source.contains("目标玩家必须在线，离线玩家不能操作实体筹码"));
        assertTrue(source.contains("筹码数量不能为负数"));
        assertTrue(source.contains("int actual = plugin.setChipBalance"));
        assertTrue(source.contains("的筹码现在是 \" + actual"));
        assertTrue(source.contains("保存为实体筹码匹配模板；不会自动兑换或发放物品"));
        assertFalse(source.contains("catch (RuntimeException exception)"));
    }

    @Test
    void guiValidatesNonNegativeInputAndReportsReturnedBalance() throws IOException {
        String source = Files.readString(GUI);

        assertTrue(source.contains("直接输入非负整数"));
        assertTrue(source.contains("筹码数量不能为负数"));
        assertTrue(source.contains("actualChipBalance = plugin.setChipBalance"));
        assertTrue(source.contains("筹码数量已更新为 \" + savedChipBalance"));
        assertTrue(source.contains("完整元数据，1件=1筹码；不会自动兑换或发放物品"));
        assertFalse(source.contains("可填负数"));
        assertFalse(source.contains("catch (RuntimeException exception)"));
    }

    @Test
    void listenerUsesEntityTemplateLanguageAndNarrowBusinessCatch() throws IOException {
        String source = Files.readString(LISTENER);

        assertTrue(source.contains("实体筹码匹配模板已保存；不会自动兑换或发放物品"));
        assertTrue(source.contains("catch (IllegalArgumentException | IllegalStateException exception)"));
        assertFalse(source.contains("catch (RuntimeException exception)"));
    }

    @Test
    void configExplainsEntityChipAndTemplateSemantics() throws IOException {
        String source = Files.readString(CONFIG);

        assertTrue(source.contains("true=改用实体筹码结算"));
        assertTrue(source.contains("完整元数据匹配 chip-item-stack"));
        assertTrue(source.contains("一件匹配物品计 1 筹码"));
        assertTrue(source.contains("余额必须保持为非负数"));
        assertTrue(source.contains("不会自动兑换、扣除、发放或改写玩家已有物品"));
    }
}
