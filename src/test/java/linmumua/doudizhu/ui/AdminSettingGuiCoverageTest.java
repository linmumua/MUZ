package linmumua.doudizhu.ui;

import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import linmumua.doudizhu.DoudizhuPlugin.AdminSetting;
import linmumua.doudizhu.ui.dialog.AdminMenuModel;
import org.junit.jupiter.api.Test;

/**
 * 交叉核对 AdminSetting 枚举与管理菜单实际入口，找出玩家调不到的设置。
 *
 * <p>【迁移说明：1.10.60 起覆盖来源从「旧箱子绘制源码」改为「AdminMenuModel 纯数据」】
 * 本测试原先把「已画出的设置」判定为「{@code HandGuiService} 里出现过
 * {@code adminSettingItem(Material.X, AdminSetting.NAME)} 或 {@code AdminSetting.NAME}」。
 * 管理菜单已从箱子 Inventory 迁到原生 Dialog：生产入口不再经过旧箱子绘制（旧绘制作为
 * 允许暂留的死实现仍在 {@code HandGuiService} 里，但已不是可调入口的事实来源），
 * 真正声明「十九页 → 槽位 → 业务动作」的是 {@link AdminMenuModel#pages()} 这份纯数据。
 * 因此这里改为读 {@code AdminMenuModel} 的 SETTING 目标集合——这不是放水，而是把断言
 * 钉到新的单一事实来源上；只要某个设置项没有出现在任何一页的 SETTING 按钮里，
 * 或者页被删掉，断言照样当场变红。
 *
 * <p>这条测试的价值在于人工核对会漏：先前一轮人工审查报告「只有 1 项无入口」，
 * 实际枚举 108 项而 adminSettingItem 只出现 86 次。迁移后这个「数一数有没有漏页漏项」
 * 的职责原样保留，只是数数的对象换成了 AdminMenuModel。
 */
class AdminSettingGuiCoverageTest {
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
        // 因此这里既画不出按钮也没有分派。
        // 枚举项本身不能删的两个原因：adminSettingHint 是 exhaustive switch，
        // 少一个 case 直接编译失败；老服务器的 config.yml 里已经写着这些键，
        // 删掉枚举会让加载配置时出现无主键。
        // 注意 HOVER_CARD_SCALE 不在此名单：悬停向后偏移已删除（沿法向平移会让
        // 射线交点跟着悬停漂移），悬停突出重新由 HOVER_CARD_SCALE 独家承担，
        // 它在卡牌页有正常入口。
        "SEAT_NAME_SCALE", "SEAT_NAME_LATERAL", "SEAT_NAME_VERTICAL", "SEAT_NAME_DEPTH"
    );

    /**
     * 六个颜色分量由 AdminMenuModel 的 GLOBAL_COLOR 目标承载（不是一个独立 SETTING 按钮），
     * 它们仍属于「可调到」——只是入口类型不同。这里显式列出来，避免被当成漏项。
     */
    private static final Set<String> COLOR_COMPONENT_SETTINGS = Set.of(
        "HOVER_GLOW_RED", "HOVER_GLOW_GREEN", "HOVER_GLOW_BLUE",
        "SELECTED_GLOW_RED", "SELECTED_GLOW_GREEN", "SELECTED_GLOW_BLUE"
    );

    /** 管理菜单里所有作为 SETTING 按钮目标的设置项。 */
    private static Set<AdminSetting> settingsDeclaredInMenu() {
        Set<AdminSetting> declared = new LinkedHashSet<>();
        for (AdminMenuModel.Page page : AdminMenuModel.pages()) {
            declared.addAll(AdminMenuModel.settingsOf(page.id()));
        }
        return declared;
    }

    /**
     * 每个设置都应当能在管理菜单里被调到，
     * 要么有自己的 SETTING 按钮，要么在已知例外名单里。
     */
    @Test
    void everySettingIsReachableFromTheAdminMenu() {
        Set<AdminSetting> declared = settingsDeclaredInMenu();

        List<String> unreachable = new ArrayList<>();
        for (AdminSetting setting : AdminSetting.values()) {
            String name = setting.name();
            if (KNOWN_WITHOUT_ITEM.contains(name)) {
                continue;
            }
            if (!declared.contains(setting)) {
                unreachable.add(name);
            }
        }

        assertTrue(
            unreachable.isEmpty(),
            "这些设置玩家调不到，要么在 AdminMenuModel 里补入口，要么从枚举里删掉：" + unreachable
        );
    }

    /**
     * 反向核对：SETTING 按钮指向的设置必须是真实枚举项，且六个颜色分量确实由
     * GLOBAL_COLOR 入口承载（不是凭空消失）。
     *
     * <p>为什么值得这一条：正向那条只查「枚举项有没有入口」，漏得掉「页里写了一个
     * 不存在的设置名」或「颜色页整个被删」——前者会因为 target 只是 Object 而编译通过，
     * 后者会让六个颜色分量既不在 SETTING 集合、也不在例外名单，正向断言却仍然全绿。
     */
    @Test
    void menuTargetsAreRealAndColorComponentsHaveTheirOwnEntry() {
        Set<AdminSetting> declared = settingsDeclaredInMenu();

        // 正向下 GLOBAL_COLOR 不是 SETTING，所以六个颜色分量不该出现在 SETTING 集合里。
        List<String> unexpected = declared.stream().map(Enum::name)
            .filter(COLOR_COMPONENT_SETTINGS::contains).toList();
        assertTrue(unexpected.isEmpty(), "颜色分量不该各自开 SETTING 按钮，而应由颜色输入屏承载：" + unexpected);

        long colorEntries = AdminMenuModel.pages().stream()
            .flatMap(page -> page.entries().stream())
            .filter(entry -> entry.kind() == AdminMenuModel.Kind.GLOBAL_COLOR)
            .count();
        assertTrue(colorEntries >= 2, "全局预览色与全局选中色两个颜色入口必须都在，实得 " + colorEntries);

        // 颜色输入屏的目标必须是真实存在的颜色分量设置项名。
        List<String> known = Arrays.stream(AdminSetting.values()).map(Enum::name).toList();
        List<String> danglingColors = AdminMenuModel.pages().stream()
            .flatMap(page -> page.entries().stream())
            .filter(entry -> entry.kind() == AdminMenuModel.Kind.GLOBAL_COLOR)
            .map(entry -> entry.target().toString())
            .filter(target -> !target.equals("HOVER_GLOW") && !target.equals("SELECTED_GLOW"))
            .toList();
        assertTrue(danglingColors.isEmpty(), "颜色输入屏目标不是合法的颜色组：" + danglingColors);
        assertTrue(known.contains("HOVER_GLOW_RED") && known.contains("SELECTED_GLOW_RED"),
            "颜色分量枚举项必须仍然存在，旧 config.yml 里的键才不会变成无主键");
    }

    /** 已知例外名单本身不能烂掉：里面的枚举名必须真实存在。 */
    @Test
    void knownExceptionsStillExist() {
        List<String> all = Arrays.stream(AdminSetting.values()).map(Enum::name).toList();
        List<String> stale = KNOWN_WITHOUT_ITEM.stream().filter(n -> !all.contains(n)).toList();

        assertTrue(stale.isEmpty(), "例外名单里有已经不存在的枚举项，应当清理：" + stale);
    }
}
