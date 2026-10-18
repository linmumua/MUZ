package linmumua.doudizhu.ui;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import linmumua.doudizhu.ui.HandInventoryHolder.AdminPage;
import linmumua.doudizhu.ui.dialog.AdminMenuModel;
import linmumua.doudizhu.ui.dialog.AdminMenuModel.Kind;
import org.junit.jupiter.api.Test;

/**
 * 管理菜单迁 Dialog 的「跨入口导航与双动按钮」补充契约。
 *
 * <p>为什么与 {@code ui.dialog.AdminMenuModelTest} 分开：owner 那份测试锁的是「页集合、
 * 父页具体值、标题、target 类型、按钮数量」这些逐项事实；本类只补 owner 测试没覆盖的
 * 两个跨入口性质——它们只有在「把菜单当一张图整体看」时才会暴露：
 * <ol>
 *   <li><b>导航自洽</b>：每个 OPEN_PAGE 按钮指向的页，其 parent 必须正是当前页；
 *       否则「返回」会跳到别处或死页。逐项断言父页值查不出「某页多了个指向别处的按钮」。</li>
 *   <li><b>双动按钮成对</b>：旧箱子里「左键编辑 / 右键恢复默认」这种同槽双动按钮，
 *       迁移后拆成两个独立按钮；必须成对存在同一套方案索引，缺一个就悄悄丢了右键功能。</li>
 * </ol>
 *
 * <p>这是纯数据断言（读 {@code AdminMenuModel} 的公开 record），不启动 Bukkit、不构造屏幕。
 */
class AdminMenuMigrationCoverageTest {

    /**
     * 每个 OPEN_PAGE 按钮指向的页，其 parent 必须正是当前页。
     *
     * <p>失败条件：在某一页加了个「跳到 DDZ_AI」的按钮，但 DDZ_AI 的父页是 DDZ_HOME——
     * 玩家点进去、再点返回会回到斗地主页而不是来处，导航链断。
     */
    @Test
    void 进入子页的按钮指向的页其父页正是当前页() {
        Map<AdminPage, AdminMenuModel.Page> byId = new EnumMap<>(AdminPage.class);
        AdminMenuModel.pages().forEach(page -> byId.put(page.id(), page));

        List<String> dangling = new ArrayList<>();
        for (AdminMenuModel.Page page : AdminMenuModel.pages()) {
            for (AdminMenuModel.Entry entry : page.entries()) {
                if (entry.kind() != Kind.OPEN_PAGE) {
                    continue;
                }
                assertTrue(entry.target() instanceof AdminPage, page.id() + " 的子页按钮目标不是 AdminPage");
                AdminPage target = (AdminPage) entry.target();
                AdminMenuModel.Page targetPage = byId.get(target);
                if (targetPage == null) {
                    dangling.add(page.id() + " → " + target + "（没有这一页）");
                } else if (targetPage.parent() != page.id()) {
                    dangling.add(page.id() + " → " + target + "（该页父页是 " + targetPage.parent() + "，返回会去别处）");
                }
            }
        }
        assertTrue(dangling.isEmpty(), "子页链路不自洽，点了会跳到不存在的页或返回错页：" + dangling);
    }

    /** 每个非首页都能顺着 parent 一路回到首页，没有孤岛页、没有环。 */
    @Test
    void 每个非首页都能顺父页链回到首页() {
        Map<AdminPage, AdminMenuModel.Page> byId = new EnumMap<>(AdminPage.class);
        AdminMenuModel.pages().forEach(page -> byId.put(page.id(), page));

        AdminMenuModel.Page home = byId.get(AdminPage.HOME);
        assertTrue(home != null && home.parent() == null, "首页必须是 HOME 且没有父页");

        List<String> orphans = new ArrayList<>();
        for (AdminMenuModel.Page page : AdminMenuModel.pages()) {
            if (page.id() == AdminPage.HOME) {
                continue;
            }
            AdminPage parent = page.parent();
            int guard = 0;
            while (parent != null && parent != AdminPage.HOME && guard++ < AdminPage.values().length) {
                AdminMenuModel.Page parentPage = byId.get(parent);
                parent = parentPage == null ? null : parentPage.parent();
            }
            if (parent != AdminPage.HOME) {
                orphans.add(page.id() + " 回溯不到首页（父页=" + page.parent() + "）");
            }
        }
        assertTrue(orphans.isEmpty(), "这些页从首页点不到，玩家无法进入：" + orphans);
    }

    /**
     * 选牌音效方案的「编辑」与「恢复默认」必须成对，且覆盖同一套方案索引。
     *
     * <p>旧箱子里这是同一个槽位的左键/右键双动按钮；迁到原生 Dialog 的一维按钮列表后，
     * 任何一个方向漏掉都会让「右键恢复默认」这条功能静默消失。
     */
    @Test
    void 选牌音效编辑与恢复默认成对覆盖同一套方案() {
        Map<Kind, List<AdminMenuModel.Entry>> byKind = new EnumMap<>(Kind.class);
        for (AdminMenuModel.Page page : AdminMenuModel.pages()) {
            for (AdminMenuModel.Entry entry : page.entries()) {
                byKind.computeIfAbsent(entry.kind(), ignored -> new ArrayList<>()).add(entry);
            }
        }

        Set<Integer> edited = intTargets(byKind, Kind.SELECTION_SOUND_EDITOR);
        Set<Integer> restored = intTargets(byKind, Kind.SELECTION_SOUND_RESTORE);
        assertEquals(edited, restored,
            "每套选牌音效方案的「编辑」与「恢复默认」必须成对存在，缺一个就会丢掉双动功能");
        assertEquals(Set.of(0, 1, 2, 3), edited, "选牌音效方案应当是四套（索引 0..3）");
    }

    private static Set<Integer> intTargets(Map<Kind, List<AdminMenuModel.Entry>> byKind, Kind kind) {
        Set<Integer> actual = new LinkedHashSet<>();
        for (AdminMenuModel.Entry entry : byKind.getOrDefault(kind, List.of())) {
            actual.add((Integer) entry.target());
        }
        return actual;
    }
}
