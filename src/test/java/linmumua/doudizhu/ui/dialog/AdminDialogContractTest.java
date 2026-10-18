package linmumua.doudizhu.ui.dialog;

import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

/**
 * 源码契约：把「管理页迁移必须满足的交互要求」钉在源码上。
 *
 * 为什么不靠行为测试：这些要求（±步长与 ×10 分离、步长预设+自定义、直接数字与滑块
 * 分别确认、整数步索引滑块、非有限值拒绝、不得重新施加已放开的硬上下限）跨越
 * 整屏的构造，且要真实 Bukkit + 原生 Dialog 才能跑；源码扫描能零侵入地守住它们，
 * 与同目录 AdminSettingRangeReleasedTest 的手法一致。
 */
class AdminDialogContractTest {
    private static final Path SETTING_SCREEN =
        Path.of("src/main/java/linmumua/doudizhu/ui/dialog/AdminSettingDialogScreen.java");
    private static final Path ADMIN_SCREEN =
        Path.of("src/main/java/linmumua/doudizhu/ui/dialog/AdminDialogScreen.java");

    /**
     * 数值编辑屏必须同时给出：± 一步、显式 ×10 倍率、直接数字确认、滑块确认。
     * 失败条件：把 ×10 又藏回 Shift、或删掉其中一种输入方式。
     */
    @Test
    void numericEditorOffersStepMultiplierAndBothConfirmations() throws IOException {
        String source = Files.readString(SETTING_SCREEN);

        assertTrue(source.contains("一步"), "缺少 ± 一步按钮");
        assertTrue(source.contains("×10"), "缺少显式 ×10 倍率按钮（原 Shift 行为）");
        assertTrue(source.contains("确认数字"), "缺少直接数字的独立确认");
        assertTrue(source.contains("确认滑块"), "缺少滑块的独立确认");
    }

    /**
     * 滑块必须用「相对原值的小整数偏移」构造与读回（core 的 {@code sliderWindow}）：
     * 中心锚在原值、偏移 0＝原值不变（不被步长网格吸附）、偏移 ±k＝原值 ± k*step，
     * 读回走唯一入口 {@code window.valueAt(...)}。
     * 不得使用绝对步索引语义（{@code fromStepIndex}）——那会把 0.1234 悄悄吸成 0.1，
     * 大值+极小步长时绝对长索引转 float 还会并档跨步。
     */
    @Test
    void sliderUsesRelativeDeltaFromTheOriginalValue() throws IOException {
        String source = Files.readString(SETTING_SCREEN);

        assertTrue(source.contains("AdminSettingArithmetic.sliderWindow("), "滑块窗口没有用 core 的相对窗口");
        assertTrue(source.contains(".valueAt("), "滑块读回没有走 valueAt（相对索引唯一入口）");
        assertTrue(source.contains("ctx.number(view, \"slider\")"), "滑块确认没有读滑块输入");
        assertTrue(source.contains("ctx.text(view, \"value\")"), "直接数字确认没有读数字输入");
        assertTrue(!source.contains("fromStepIndex("), "又用回了绝对步索引回写（会吸值 / 大值丢精度）");
        assertTrue(!source.contains("toStepIndex("), "又用回了绝对步索引换算");
    }

    /**
     * 非有限值必须明确报错，不能静默写进配置。
     */
    @Test
    void nonFiniteValuesAreRejected() throws IOException {
        String source = Files.readString(SETTING_SCREEN);

        assertTrue(source.contains("DialogInputParsers.parseFiniteDoubleRequired("),
            "直接数字没有走拒绝 NaN/无穷的解析");
        assertTrue(source.contains("ctx.error("), "非法输入没有给玩家明确提示");
    }

    /**
     * 数值路径：± 走 adjustAdminSetting（内部传 ±无穷，不夹声明区间），
     * 绝对设置走 setAdminSettingAbsolute；并且**不得**重新按声明 min/max 夹紧。
     */
    @Test
    void numericPathsDoNotReapplyReleasedHardLimits() throws IOException {
        String source = Files.readString(SETTING_SCREEN);

        assertTrue(source.contains("plugin.adjustAdminSetting("), "± 没有走既有相对调节");
        assertTrue(source.contains("plugin.setAdminSettingAbsolute("), "绝对设置没有走 core 的接口");
        assertTrue(!source.contains("Math.max(setting.minValue()"), "又按声明下限夹紧了");
        assertTrue(!source.contains("Math.min(setting.maxValue("), "又按声明上限夹紧了");
    }

    /** 步长语义（固定/整数/跟随）收口在 AdminStepPolicy，屏里不得自行重写一套。 */
    @Test
    void stepSemanticsComeFromAdminStepPolicy() throws IOException {
        String source = Files.readString(SETTING_SCREEN);

        assertTrue(source.contains("AdminStepPolicy.effectiveStep("), "步长没有走统一策略");
        assertTrue(source.contains("AdminStepPolicy.adjustable("), "没有按策略决定是否给步长预设");
        assertTrue(source.contains("AdminStepPolicy.PRESETS"), "步长预设没有走统一来源");
    }

    /**
     * 管理页必须处理对照模型里的**每一个**动作类型，否则就是「画了按钮没人接」。
     */
    @Test
    void adminScreenHandlesEveryMenuActionKind() throws IOException {
        String source = Files.readString(ADMIN_SCREEN);

        for (AdminMenuModel.Kind kind : AdminMenuModel.Kind.values()) {
            assertTrue(
                source.contains("case " + kind.name() + " ->"),
                "AdminDialogScreen 没有处理动作类型 " + kind
            );
        }
        assertTrue(source.contains("ctx.push("), "管理页没有用导航打开子屏");
        assertTrue(source.contains("muz.admin"), "管理页没有声明 admin 权限");
    }

    /**
     * 经济页必须消费不可变在线 UUID 快照，且**不得**在管理者 lane 枚举 {@code Bukkit.getOnlinePlayers()}，
     * 也不得在管理者 lane 直接读改他人筹码（区域化核心上都会抛 / 越权）。
     */
    @Test
    void economyPageConsumesSnapshotAndDispatchesToTargetLane() throws IOException {
        String source = Files.readString(ADMIN_SCREEN);

        assertTrue(source.contains("plugin.onlinePlayerIdsSnapshot()"), "在线名单没有消费不可变 UUID 快照");
        assertTrue(!source.contains("Bukkit.getOnlinePlayers("), "又在 player lane 枚举在线玩家了");
        assertTrue(source.contains("enqueuePlayer("), "筹码读写没有投递到目标玩家自己的 player lane");
        // 目标筹码的读写必须落在 enqueuePlayer 的投递体内（不能裸调）。
        assertTrue(source.contains("enqueuePlayer(targetId"), "目标筹码读写没有按 target UUID 投递");
    }

    /** 精确筹码输入（ADMIN_CHIP_BALANCE）必须写回 plugin.setChipBalance 并拒绝负数。 */
    @Test
    void chipBalanceEditorWritesBackAndRejectsNegative() throws IOException {
        String source = Files.readString(ADMIN_SCREEN);

        assertTrue(source.contains("plugin.setChipBalance("), "精确筹码没有写回");
        assertTrue(source.contains("不能为负数"), "精确筹码没有拒绝负数");
    }
}
