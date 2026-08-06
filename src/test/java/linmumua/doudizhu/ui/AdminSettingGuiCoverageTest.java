package linmumua.doudizhu.ui;

import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import linmumua.doudizhu.DoudizhuPlugin.AdminSetting;
import org.junit.jupiter.api.Test;

/**
 * 交叉核对 AdminSetting 枚举与管理菜单实际入口，找出玩家调不到的设置。
 *
 * 为什么用源码扫描而不是反射：槽位到设置的映射写死在 switch 里，
 * 运行期拿不到，而这个项目的测试又不能启动 Bukkit。
 * 直接读源文件虽然朴素，但能真实反映「GUI 里有没有这一项」。
 *
 * 这条测试的价值在于人工核对会漏：先前一轮人工审查报告「只有 1 项无入口」，
 * 实际枚举 108 项而 adminSettingItem 只出现 86 次。
 */
class AdminSettingGuiCoverageTest {
    private static final Path GUI = Path.of("src/main/java/linmumua/doudizhu/ui/HandGuiService.java");
    private static final Path LISTENER = Path.of("src/main/java/linmumua/doudizhu/listener/HandGuiListener.java");

    /**
     * 已知没有独立调整按钮、但确实有其它入口或用途的设置。
     * 每一项都要写清理由，避免这里变成掩盖问题的垃圾桶。
     */
    private static final List<String> KNOWN_WITHOUT_ITEM = List.of(
        // 六个颜色分量由一个 RGB 输入框统一编辑，不各自开按钮
        "HOVER_GLOW_RED", "HOVER_GLOW_GREEN", "HOVER_GLOW_BLUE",
        "SELECTED_GLOW_RED", "SELECTED_GLOW_GREEN", "SELECTED_GLOW_BLUE",
        // 以下四项是生产代码里已经没有任何读取点的死设置：改它们的值不会
        // 影响任何渲染结果。座位名字缩放/偏移统一走 EMPTY_SEAT_* 那组
        //（有人无人同源，见 SeatLabelParityTest）。
        // 菜单入口已全部移除，「座位名字」整页连同 GLOBAL_SEAT_NAMES 一起删掉，
        // 因此这里既画不出 adminSettingItem 也没有 Listener 分派。
        // 枚举项本身不能删的两个原因：adminSettingHint 是 exhaustive switch，
        // 少一个 case 直接编译失败；老服务器的 config.yml 里已经写着这些键，
        // 删掉枚举会让加载配置时出现无主键。
        // 注意 HOVER_CARD_SCALE 不在此名单：悬停向后偏移已删除（沿法向平移会让
        // 射线交点跟着悬停漂移），悬停突出重新由 HOVER_CARD_SCALE 独家承担，
        // 它在手牌页槽位 25 有正常入口。
        "SEAT_NAME_SCALE", "SEAT_NAME_LATERAL", "SEAT_NAME_VERTICAL", "SEAT_NAME_DEPTH"
    );

    private static String readAll(Path path) throws IOException {
        return Files.readString(path);
    }

    /**
     * 每个设置都应当能在管理菜单里被调到，
     * 要么有自己的 adminSettingItem，要么在已知例外名单里。
     */
    @Test
    void everySettingIsReachableFromTheAdminMenu() throws IOException {
        String gui = readAll(GUI);
        String listener = readAll(LISTENER);

        List<String> unreachable = new ArrayList<>();
        for (AdminSetting setting : AdminSetting.values()) {
            String name = setting.name();
            if (KNOWN_WITHOUT_ITEM.contains(name)) {
                continue;
            }
            boolean drawn = gui.contains("AdminSetting." + name)
                || gui.contains("adminSettingItem(Material." + name);
            boolean handled = listener.contains("AdminSetting." + name);
            if (!drawn || !handled) {
                unreachable.add(name + (drawn ? "" : "[GUI未画]") + (handled ? "" : "[无处理]"));
            }
        }

        assertTrue(
            unreachable.isEmpty(),
            "这些设置玩家调不到，要么补 GUI 入口，要么从枚举里删掉：" + unreachable
        );
    }

    /** 已知例外名单本身不能烂掉：里面的枚举名必须真实存在。 */
    @Test
    void knownExceptionsStillExist() {
        List<String> all = Arrays.stream(AdminSetting.values()).map(Enum::name).toList();
        List<String> stale = KNOWN_WITHOUT_ITEM.stream().filter(n -> !all.contains(n)).toList();

        assertTrue(stale.isEmpty(), "例外名单里有已经不存在的枚举项，应当清理：" + stale);
    }
}
