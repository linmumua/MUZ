package linmumua.doudizhu.world;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

/**
 * 已删除的悬浮头像必须还能被坐标清场回收。
 *
 * <p>背景：座位悬浮头颅和桌心悬浮头颅两个 ItemDisplay 都已删除，生成代码和配置键一并移除。
 * 但升级前已经摆好的桌子上那两个实体还留在世界里，而持久化不存实体 id，
 * 重启恢复牌桌时只能靠 {@code purgeResidualWorldArtifacts} 的坐标扫回收。
 *
 * <p>两者命运不同：座位头颅离椅子热点 0.53 格（chairLabelHeight 1.35 + 座位偏移 0.18
 * - 支撑抬升 1.0），落在椅子热点的 1.6 垂直半径内，本来就扫得到；
 * 桌心头颅悬在 anchor 上方约 3.9 格，而其余热点全在低处，垂直半径 1.6 根本够不到——
 * 不专门补一个高处热点，它会永久留在世界里没人回收。
 *
 * <p>这不是假想：代码里已经记载过同一类事故，按钮图标删除后不再被 actionEntities 追踪，
 * 升级前生成的图标就永久留了下来，后来才补上按钮位热点。
 *
 * <p>用源码扫描而不是调用方法：清场要 Bukkit 的世界和实体，这个项目跑不起 Bukkit。
 */
class RetiredAvatarResidueSweepTest {
    private static final Path MANAGER =
        Path.of("src/main/java/linmumua/doudizhu/world/PhysicalTableManager.java");

    /** 清场的垂直半径，与 purgeResidualWorldArtifacts 里 clearResidualEntities 的第三个参数同值。 */
    private static final double PURGE_RADIUS_Y = 1.6;

    /** 旧版本生成桌心头颅时用的垂直偏移默认值，也是 RETIRED_STATUS_AVATAR_VERTICAL_OFFSET 的值。 */
    private static final double RETIRED_VERTICAL_OFFSET = 0.82;

    /** 旧版本生成座位头颅时用的垂直偏移默认值（render.seat-avatar-offset.vertical）。 */
    private static final double RETIRED_SEAT_AVATAR_VERTICAL_OFFSET = 0.18;

    /**
     * render.layout.chair-label-height 的默认值。
     *
     * <p>座位头颅当年挂在 {@code seatBase + chairLabelHeight + seatAvatarVerticalOffset}
     * （见历史版本的 {@code seatAvatarLocation}），所以这个高度是间距的一部分，不能漏掉。
     */
    private static final double CHAIR_LABEL_HEIGHT = 1.35;

    /** render.status-height 的默认值。 */
    private static final double DEFAULT_STATUS_HEIGHT = 3.10;

    /** render.layout.table-display-height 的默认值。 */
    private static final double TABLE_DISPLAY_HEIGHT = 0.55;

    /** render.layout.chair-base-height 的默认值。 */
    private static final double CHAIR_BASE_HEIGHT = 0.20;

    /** render.button-offset.height 的默认值。 */
    private static final double BUTTON_HEIGHT = 1.02;

    /**
     * 清场必须包含一个桌顶高处热点。
     *
     * <p>失败条件：删掉那条 hotspots.add，或把它挪进只有低处热点的循环里。
     * 那样升级后的老桌子上会永久悬着一个头颅，玩家没有任何手段清掉它
     * （拆桌能清，但没人会为了一个头颅拆桌重摆）。
     */
    @Test
    void purgeIncludesAHotspotAboveTheTableTop() throws IOException {
        String source = Files.readString(MANAGER);
        assertTrue(source.contains("RETIRED_STATUS_AVATAR_VERTICAL_OFFSET"),
            "清场没有桌顶高处热点：已删除的桌心悬浮头像会永久留在升级前的桌子上");
        assertTrue(source.contains("getStatusHeight() + RETIRED_STATUS_AVATAR_VERTICAL_OFFSET"),
            "桌顶热点没按 status-height 加旧偏移算高度：玩家调过桌顶高度的桌子会扫不到");
    }

    /**
     * 头像的生成代码必须真的没了。
     *
     * <p>清场热点是给残留兜底的，不是给还在生成的实体擦屁股。生成代码要是还在，
     * 就变成一边生成一边清场，桌子会闪。
     *
     * <p>失败条件：把 spawnAvatarDisplay 或任一头像位置函数加回来。
     */
    @Test
    void avatarSpawningIsActuallyGone() throws IOException {
        String source = Files.readString(MANAGER);
        assertTrue(!source.contains("spawnAvatarDisplay"),
            "头像生成代码还在：清场热点会和生成互相打架，桌子会闪");
        assertTrue(!source.contains("statusAvatarLocation"),
            "桌心头像位置函数还在：说明头像并未真正删除");
    }

    /**
     * 其余低处热点确实都够不到那个头颅，所以桌顶热点不可省。
     *
     * <p>上面两条都是源码扫描，只能证明「那行代码在」，证不了它非有不可。
     * 这条把数字算出来：三个低处热点里最高的是桌面（display-height 0.55 + 支撑面 1.0
     * = 1.55），离头颅 3.92 还差 2.37 格，超出垂直半径 1.6。
     *
     * <p>反过来也是这条的用途：哪天有人把垂直半径调大到桌面就能够到 3.92，
     * 这条会失败——那时该做的是删掉专设热点而不是留着两套重叠的扫描。
     */
    @Test
    void lowHotspotsCannotReachTheTableTopSkull() {
        double skullHeight = DEFAULT_STATUS_HEIGHT + RETIRED_VERTICAL_OFFSET;
        double highestLowHotspot = Math.max(
            TABLE_DISPLAY_HEIGHT + PhysicalTableManager.SUPPORT_SURFACE_LIFT,
            Math.max(CHAIR_BASE_HEIGHT, BUTTON_HEIGHT));

        assertTrue(skullHeight - highestLowHotspot > PURGE_RADIUS_Y,
            "最高的低处热点 y=" + highestLowHotspot + " 已经够得到 y=" + skullHeight
                + " 的头颅了，这个专设热点应当删掉而不是留着两套重叠扫描");
    }

    /**
     * 座位头颅落在椅子热点的垂直半径内，所以不需要单独补热点。
     *
     * <h2>间距怎么来的</h2>
     *
     * <p>两个坐标的公共项会消掉，所以间距只由三个量决定：
     * <ul>
     *   <li>历史座位头颅 = {@code seatBase + chairLabelHeight + seatAvatarVerticalOffset}，
     *       而当年的 {@code seatBase} 就是 {@code chairBaseHeight + chairVisualVerticalOffset}
     *       —— {@code SUPPORT_SURFACE_LIFT} 是后来才加的，那时还不存在；</li>
     *   <li>现在的椅子热点 = {@code chairBaseHeight + chairVisualVerticalOffset + SUPPORT_SURFACE_LIFT}。</li>
     * </ul>
     * 相减后 {@code chairBaseHeight} 与 {@code chairVisualVerticalOffset} 抵消，
     * 剩下 {@code chairLabelHeight + seatAvatarVerticalOffset - SUPPORT_SURFACE_LIFT}
     * = 1.35 + 0.18 - 1.0 = 0.53 格，落在 1.6 半径内。
     *
     * <p><b>为什么不能只写 0.18</b>：这条原先直接拿 {@code seatAvatarVerticalOffset} 当间距，
     * 漏掉了 {@code chairLabelHeight} 和 {@code SUPPORT_SURFACE_LIFT}，算出的 0.18 比真实值
     * 小 0.35。那样它永远不会失败——把 {@code chair-label-height} 调到 2.42 以上时，
     * 真实间距就超出垂直半径、座位头颅变成永久残留，而断言还在拿 0.18 和 1.6 比。
     *
     * <p>失败条件：{@code chair-label-height} 调高越界，或椅子热点的垂直半径被收窄，
     * 两者都会让座位头颅像桌心头颅那样成为永久残留——而且它贴着椅子、看着像椅子的一部分，
     * 比桌心那个更不容易被发现。
     */
    @Test
    void seatSkullIsAlreadyCoveredByTheChairHotspot() {
        double gap = CHAIR_LABEL_HEIGHT
            + RETIRED_SEAT_AVATAR_VERTICAL_OFFSET
            - PhysicalTableManager.SUPPORT_SURFACE_LIFT;

        assertTrue(gap <= PURGE_RADIUS_Y,
            "座位头颅离椅子热点 " + gap + " 格（chairLabelHeight " + CHAIR_LABEL_HEIGHT
                + " + 座位偏移 " + RETIRED_SEAT_AVATAR_VERTICAL_OFFSET
                + " - 支撑抬升 " + PhysicalTableManager.SUPPORT_SURFACE_LIFT
                + "），超出垂直半径 " + PURGE_RADIUS_Y + "，会变成永久残留");
    }

    /**
     * 退休偏移常量不许被改动。
     *
     * <p>它记的是【历史事实】——旧版本生成头颅时用的偏移默认值，不是可调参数。
     * 改了它，热点就对不上世界里那些实体的实际高度，清场会扫空。
     */
    @Test
    void retiredOffsetIsPinnedToTheHistoricalDefault() {
        assertEquals(0.82, RETIRED_VERTICAL_OFFSET, 1e-9,
            "退休偏移记的是旧版本的生成默认值，改它会让热点对不上世界里实体的真实高度");
    }
}
