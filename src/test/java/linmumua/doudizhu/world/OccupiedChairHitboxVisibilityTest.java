package linmumua.doudizhu.world;

import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * 椅子判定框必须对所有人保持可见，包括占座玩家自己。
 *
 * 意图：这条守的是"加入座位后卡住玩家"这个真实缺陷不许复发。
 *
 * 旧实现在玩家入座后对他自己调 {@code viewer.hideEntity} 与 CE 的
 * {@code hideHitboxes}。这两个调用都只让客户端看不见：
 * CE 的 {@code hideHitboxes} 只遍历 hitboxes 列表调 {@code hide}，
 * 而 {@code ShulkerFurnitureHitbox.hide} 只发一个 despawnPacket；
 * {@code viewer.hideEntity} 同样只是停发实体包。
 * 两者都不销毁服务端的 BukkitCollider。
 * 于是客户端以为那里没实体、预测能走过去，服务端仍用 Collider 判定被挡住
 * —— 玩家表现为"陷进实体、走不动"；判定框对自己隐藏后还点不到椅子，
 * 表现为"坐不下"。用户实测正是"未加入时能站能坐，加入后卡人且坐不下"。
 *
 * 用源码扫描而不是调用方法：这段逻辑依赖 Bukkit 的 World / Player / Entity，
 * 这个项目的测试跑不起 Bukkit；而"有没有在椅子判定框上调隐藏"恰好能在
 * 源码层面判定，且正是本次修复的要点。写法沿用 SeatLabelParityTest。
 */
class OccupiedChairHitboxVisibilityTest {
    private static final Path MANAGER = Path.of("src/main/java/linmumua/doudizhu/world/PhysicalTableManager.java");
    private static final String METHOD = "private void restoreOccupiedChairHitboxVisibility(";

    /**
     * 椅子判定框的可见性同步里不许出现任何隐藏调用。
     *
     * hideEntity 会造成幽灵碰撞（客户端看不见但服务端仍挡人），
     * setFurnitureHitboxesVisible(..., false) 走的是 CE 的 hideHitboxes，
     * 效果同样是只藏客户端、不动 Collider。两条都不能再出现。
     */
    @Test
    void chairHitboxSyncNeverHidesFromAnyone() throws IOException {
        String body = methodBody(METHOD);

        List<String> leaked = new ArrayList<>();
        if (body.contains("hideEntity")) {
            leaked.add("viewer.hideEntity（客户端不可见但服务端 Collider 仍挡人）");
        }
        if (body.contains("false)")) {
            leaked.add("setFurnitureHitboxesVisible 传了 false（走 CE hideHitboxes，同样只藏客户端）");
        }

        assertTrue(leaked.isEmpty(), "椅子判定框又被隐藏了，入座后会卡住玩家：" + leaked);
    }

    /** 必须主动显式恢复可见：hideEntity 的状态按玩家持久，旧版本藏起来的实体不会自愈。 */
    @Test
    void chairHitboxIsExplicitlyRestoredForEveryViewer() throws IOException {
        String body = methodBody(METHOD);

        assertTrue(body.contains("showEntity"), "没有显式 showEntity，旧版本已隐藏的实体不会恢复");
        assertTrue(
            body.contains("setFurnitureHitboxesVisible") && body.contains("true)"),
            "没有把 CE hitbox 显式恢复为可见"
        );
    }

    /**
     * 不许再按"座位是否被占"分叉。
     *
     * 只查 hideEntity 有个漏洞：有人可能换个写法重新引入按占座隐藏的逻辑。
     * 判定框的可见性与谁坐在上面无关，这个方法体里不该再读 seatAssignments。
     */
    @Test
    void visibilityDoesNotDependOnSeatOccupancy() throws IOException {
        String body = methodBody(METHOD);

        assertTrue(
            !body.contains("seatAssignments"),
            "判定框可见性又开始看座位归属了，占座玩家会重新被卡住"
        );
    }

    /** 那个"该不该对占座者隐藏"的判定必须彻底消失，留着就会有人重新接上。 */
    @Test
    void theRetiredHidePredicateIsGone() throws IOException {
        String source = Files.readString(MANAGER);

        assertTrue(
            !source.contains("shouldHideOccupiedChairHitbox"),
            "shouldHideOccupiedChairHitbox 仍存在，隐藏逻辑可能被重新接上"
        );
    }

    private static String methodBody(String signature) throws IOException {
        String source = Files.readString(MANAGER);
        int start = source.indexOf(signature);
        assertTrue(start >= 0, "找不到 " + signature + "，这条测试的锚点已失效");
        int end = source.indexOf("\n    }", start);
        assertTrue(end > start, "找不到 " + signature + " 的结束锚点");
        return source.substring(start, end);
    }
}
