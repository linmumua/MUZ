package linmumua.doudizhu.ui.dialog;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.EnumSet;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import linmumua.doudizhu.DoudizhuPlugin.AdminSetting;
import linmumua.doudizhu.DoudizhuPlugin.FurnitureType;
import linmumua.doudizhu.DoudizhuPlugin.PlayActionKind;
import linmumua.doudizhu.room.TableLevel;
import linmumua.doudizhu.ui.HandInventoryHolder.AdminPage;
import org.junit.jupiter.api.Test;

/**
 * 锁住管理菜单「十九页 → 按钮 → 业务动作」这份对照模型的完整性与正确性。
 *
 * 迁移最危险的失败模式不是编译错误，而是「换了 UI 就悄悄漏掉一整类功能」：
 * 少画一个按钮、少接一个导航、或者某个 AdminSetting 再也调不到。
 * 这一组断言把旧箱子版（openAdminModels + handleAdminClick）的实测结构固化成
 * 期望值，任何一处漏接都会在这里变红，而不是等玩家发现。
 */
class AdminMenuModelTest {
    /**
     * 旧管理菜单里本来就没有独立数值入口的设置：六个颜色分量走统一的 RGB 输入屏，
     * 四个 SEAT_NAME_* 是「座位名字」整页删除后的死设置。
     * 与 AdminSettingGuiCoverageTest 的 KNOWN_WITHOUT_ITEM 同源，不能扩大。
     */
    private static final Set<AdminSetting> NO_DEDICATED_ENTRY = EnumSet.of(
        AdminSetting.HOVER_GLOW_RED, AdminSetting.HOVER_GLOW_GREEN, AdminSetting.HOVER_GLOW_BLUE,
        AdminSetting.SELECTED_GLOW_RED, AdminSetting.SELECTED_GLOW_GREEN, AdminSetting.SELECTED_GLOW_BLUE,
        AdminSetting.SEAT_NAME_SCALE, AdminSetting.SEAT_NAME_LATERAL,
        AdminSetting.SEAT_NAME_VERTICAL, AdminSetting.SEAT_NAME_DEPTH
    );

    /** 十九个页面一个不少、一个不重。 */
    @Test
    void pagesCoverEveryAdminPageExactlyOnce() {
        Set<AdminPage> ids = new LinkedHashSet<>();
        for (AdminMenuModel.Page page : AdminMenuModel.pages()) {
            ids.add(page.id());
        }

        assertEquals(EnumSet.allOf(AdminPage.class), ids, "管理页集合与 AdminPage 枚举不一致");
        assertEquals(19, AdminMenuModel.pages().size(), "管理页数量应为 19");
    }

    /** 父页结构与旧 parentPage 完全一致，决定「返回」回到哪里。 */
    @Test
    void parentsMatchLegacyStructure() {
        assertNull(AdminMenuModel.parentOf(AdminPage.HOME), "首页没有父页");

        assertEquals(AdminPage.HOME, AdminMenuModel.parentOf(AdminPage.DDZ_HOME));
        assertEquals(AdminPage.HOME, AdminMenuModel.parentOf(AdminPage.GLOBAL_HOME));
        assertEquals(AdminPage.HOME, AdminMenuModel.parentOf(AdminPage.GLOBAL_ECONOMY));

        for (AdminPage page : List.of(
            AdminPage.DDZ_FURNITURE, AdminPage.DDZ_BUTTONS, AdminPage.DDZ_CARDS, AdminPage.DDZ_LABELS,
            AdminPage.DDZ_TEXT, AdminPage.DDZ_SEAT_TEXT, AdminPage.DDZ_HITBOX, AdminPage.DDZ_AUDIO,
            AdminPage.DDZ_PLAYER_OPTIONS, AdminPage.DDZ_BOTS, AdminPage.DDZ_AI
        )) {
            assertEquals(AdminPage.DDZ_HOME, AdminMenuModel.parentOf(page), page + " 应回到斗地主页");
        }

        assertEquals(AdminPage.GLOBAL_HOME, AdminMenuModel.parentOf(AdminPage.GLOBAL_ANIMATION));
        assertEquals(AdminPage.GLOBAL_HOME, AdminMenuModel.parentOf(AdminPage.GLOBAL_HIGHLIGHT));
        assertEquals(AdminPage.GLOBAL_HOME, AdminMenuModel.parentOf(AdminPage.GLOBAL_AVATARS));
        assertEquals(AdminPage.GLOBAL_AVATARS, AdminMenuModel.parentOf(AdminPage.GLOBAL_STATUS_NAMES));
    }

    /**
     * 页标题必须与旧 pageTitle 完全一致——这是玩家看得见的文案，
     * 迁移后本模型成了唯一来源，改名就是玩家可见的回归。
     */
    @Test
    void pageTitlesMatchLegacy() {
        assertEquals("首页", AdminMenuModel.titleOf(AdminPage.HOME));
        assertEquals("斗地主", AdminMenuModel.titleOf(AdminPage.DDZ_HOME));
        assertEquals("通用视觉", AdminMenuModel.titleOf(AdminPage.GLOBAL_HOME));
        assertEquals("经济与场次", AdminMenuModel.titleOf(AdminPage.GLOBAL_ECONOMY));
        assertEquals("桌椅", AdminMenuModel.titleOf(AdminPage.DDZ_FURNITURE));
        assertEquals("按钮", AdminMenuModel.titleOf(AdminPage.DDZ_BUTTONS));
        assertEquals("卡牌", AdminMenuModel.titleOf(AdminPage.DDZ_CARDS));
        assertEquals("牌面标签", AdminMenuModel.titleOf(AdminPage.DDZ_LABELS));
        assertEquals("桌面文字", AdminMenuModel.titleOf(AdminPage.DDZ_TEXT));
        assertEquals("座位文字", AdminMenuModel.titleOf(AdminPage.DDZ_SEAT_TEXT));
        assertEquals("碰撞交互", AdminMenuModel.titleOf(AdminPage.DDZ_HITBOX));
        assertEquals("音频", AdminMenuModel.titleOf(AdminPage.DDZ_AUDIO));
        assertEquals("玩家选项", AdminMenuModel.titleOf(AdminPage.DDZ_PLAYER_OPTIONS));
        assertEquals("机器人行为", AdminMenuModel.titleOf(AdminPage.DDZ_BOTS));
        assertEquals("AI 配置", AdminMenuModel.titleOf(AdminPage.DDZ_AI));
        assertEquals("动画", AdminMenuModel.titleOf(AdminPage.GLOBAL_ANIMATION));
        assertEquals("预选高亮", AdminMenuModel.titleOf(AdminPage.GLOBAL_HIGHLIGHT));
        assertEquals("头像组件", AdminMenuModel.titleOf(AdminPage.GLOBAL_AVATARS));
        assertEquals("顶栏名字", AdminMenuModel.titleOf(AdminPage.GLOBAL_STATUS_NAMES));

        for (AdminMenuModel.Page page : AdminMenuModel.pages()) {
            assertTrue(page.title() != null && !page.title().isBlank(), page.id() + " 标题为空");
        }
    }

    /** 每个按钮的 target 类型必须与其 kind 声明一致，避免运行期强转 ClassCastException。 */
    @Test
    void everyEntryTargetTypeMatchesItsKind() {
        for (AdminMenuModel.Page page : AdminMenuModel.pages()) {
            for (AdminMenuModel.Entry entry : page.entries()) {
                assertNotNull(entry.label(), page.id() + " 有按钮没有文字");
                switch (entry.kind()) {
                    case OPEN_PAGE -> assertInstanceOf(AdminPage.class, entry.target(), page.id() + " OPEN_PAGE");
                    case SETTING -> assertInstanceOf(AdminSetting.class, entry.target(), page.id() + " SETTING");
                    case SELECTION_SOUND_EDITOR, SELECTION_SOUND_RESTORE ->
                        assertInstanceOf(Integer.class, entry.target(), page.id() + " 选牌音效");
                    case PLAY_ACTION_KIND ->
                        assertInstanceOf(PlayActionKind.class, entry.target(), page.id() + " 动作牌型");
                    case SOUND_PICKER ->
                        assertInstanceOf(AdminMenuModel.SoundTarget.class, entry.target(), page.id() + " 音效选择");
                    case AI_FIELD ->
                        assertInstanceOf(AdminMenuModel.AiField.class, entry.target(), page.id() + " AI 字段");
                    case GLOBAL_COLOR ->
                        assertInstanceOf(AdminMenuModel.GlobalColor.class, entry.target(), page.id() + " 全局颜色");
                    case FURNITURE_APPLY, FURNITURE_RESET ->
                        assertInstanceOf(FurnitureType.class, entry.target(), page.id() + " 家具");
                    case ROOM_MULTIPLIER, ROOM_ECONOMY ->
                        assertInstanceOf(TableLevel.class, entry.target(), page.id() + " 场次");
                    case PAYMENT_TOGGLE, CHIP_TEMPLATE_SET, CHIP_TEMPLATE_RESET, CHIP_BALANCE_PANEL ->
                        assertNull(entry.target(), page.id() + " " + entry.kind() + " 不应有 target");
                }
            }
        }
    }

    /**
     * 每个 AdminSetting 都要能被调到，缺的只允许是那 10 个已知例外。
     * 失败条件：漏画某一项，或把已知例外名单扩大（掩盖问题）。
     */
    @Test
    void everySettingIsReachableExceptKnownExceptions() {
        Set<AdminSetting> covered = new HashSet<>();
        for (AdminMenuModel.Page page : AdminMenuModel.pages()) {
            covered.addAll(AdminMenuModel.settingsOf(page.id()));
        }

        Set<AdminSetting> expected = EnumSet.allOf(AdminSetting.class);
        expected.removeAll(NO_DEDICATED_ENTRY);

        assertEquals(expected, covered, "有设置项在管理菜单里没有入口，或多了预期外的入口");

        for (AdminSetting exception : NO_DEDICATED_ENTRY) {
            assertTrue(!covered.contains(exception), exception + " 不应有独立数值入口");
        }
    }

    /** 四个固定步长项必须仍然可调（FixedStepSettingsTest 在枚举侧锁步长，这里锁入口）。 */
    @Test
    void fixedStepSettingsStillHaveEntries() {
        Set<AdminSetting> covered = new HashSet<>();
        for (AdminMenuModel.Page page : AdminMenuModel.pages()) {
            covered.addAll(AdminMenuModel.settingsOf(page.id()));
        }

        assertTrue(covered.contains(AdminSetting.CARD_DEPTH_OFFSET));
        assertTrue(covered.contains(AdminSetting.CHAIR_ROTATION_DEGREES));
        assertTrue(covered.contains(AdminSetting.BUTTON_ARC_SMALL_ANGLE));
        assertTrue(covered.contains(AdminSetting.BUTTON_ARC_LARGE_ANGLE));
    }

    /** 页脚槽位常量与旧 GUI 一致（用于人工对照，不参与原生 Dialog 布局）。 */
    @Test
    void chromeSlotConstantsMatchLegacy() {
        assertEquals(4, AdminMenuModel.SLOT_STEP_CYCLE);
        assertEquals(44, AdminMenuModel.SLOT_BACK);
        assertEquals(49, AdminMenuModel.SLOT_HOME);
        assertEquals(52, AdminMenuModel.SLOT_RELOAD);
        assertEquals(53, AdminMenuModel.SLOT_CLOSE);
    }

    /** 关键页的按钮结构逐条对照旧 GUI，防止「少接一个大类」。 */
    @Test
    void keyPagesKeepTheirLegacyButtons() {
        assertEquals(4, count(AdminPage.DDZ_PLAYER_OPTIONS, AdminMenuModel.Kind.SELECTION_SOUND_EDITOR));
        assertEquals(4, count(AdminPage.DDZ_PLAYER_OPTIONS, AdminMenuModel.Kind.SELECTION_SOUND_RESTORE));
        assertEquals(6, count(AdminPage.DDZ_PLAYER_OPTIONS, AdminMenuModel.Kind.PLAY_ACTION_KIND));

        assertEquals(2, count(AdminPage.DDZ_AUDIO, AdminMenuModel.Kind.SOUND_PICKER));
        assertEquals(4, count(AdminPage.DDZ_AI, AdminMenuModel.Kind.AI_FIELD));

        assertEquals(2, count(AdminPage.GLOBAL_HIGHLIGHT, AdminMenuModel.Kind.GLOBAL_COLOR));
        assertEquals(2, count(AdminPage.GLOBAL_HIGHLIGHT, AdminMenuModel.Kind.SETTING));

        assertEquals(1, count(AdminPage.GLOBAL_ECONOMY, AdminMenuModel.Kind.PAYMENT_TOGGLE));
        assertEquals(1, count(AdminPage.GLOBAL_ECONOMY, AdminMenuModel.Kind.CHIP_TEMPLATE_SET));
        assertEquals(1, count(AdminPage.GLOBAL_ECONOMY, AdminMenuModel.Kind.CHIP_TEMPLATE_RESET));
        assertEquals(4, count(AdminPage.GLOBAL_ECONOMY, AdminMenuModel.Kind.ROOM_MULTIPLIER));
        assertEquals(4, count(AdminPage.GLOBAL_ECONOMY, AdminMenuModel.Kind.ROOM_ECONOMY));
        assertEquals(1, count(AdminPage.GLOBAL_ECONOMY, AdminMenuModel.Kind.CHIP_BALANCE_PANEL));

        assertEquals(2, count(AdminPage.DDZ_FURNITURE, AdminMenuModel.Kind.FURNITURE_APPLY));
        assertEquals(2, count(AdminPage.DDZ_FURNITURE, AdminMenuModel.Kind.FURNITURE_RESET));
        assertEquals(1, count(AdminPage.DDZ_FURNITURE, AdminMenuModel.Kind.SOUND_PICKER));
    }

    /** 六个动作牌型一个不少，且必须是全部 PlayActionKind。 */
    @Test
    void everyPlayActionKindAppearsInPlayerOptions() {
        Set<PlayActionKind> kinds = EnumSet.noneOf(PlayActionKind.class);
        for (AdminMenuModel.Entry entry : AdminMenuModel.page(AdminPage.DDZ_PLAYER_OPTIONS).entries()) {
            if (entry.kind() == AdminMenuModel.Kind.PLAY_ACTION_KIND) {
                kinds.add((PlayActionKind) entry.target());
            }
        }

        assertEquals(EnumSet.allOf(PlayActionKind.class), kinds);
    }

    private static long count(AdminPage page, AdminMenuModel.Kind kind) {
        return AdminMenuModel.page(page).entries().stream().filter(entry -> entry.kind() == kind).count();
    }
}
