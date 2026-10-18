package linmumua.doudizhu.world;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/**
 * 椅侧面板用的座位姿态快照：只描述「椅子在哪、朝哪、谁坐着」，不持有任何 Bukkit 实体。
 *
 * <p>这组断言锁的是两条**意图**，不是实现细节：
 * <ol>
 *   <li>面板层按玩家查座位时只能拿到纯数据——所以快照必须能从 UUID 直接查出座位，
 *       而不是逼调用方再去解析实体或牌桌；</li>
 *   <li>快照的几何来源必须仍是牌桌那一套椅子几何。若有人图省事在快照里另写一份
 *       椅子偏移或面板尺寸常量，两侧几何迟早漂移，面板就会浮在椅子外面——
 *       这类缺陷在实服上只表现为「面板位置不对」，不看代码根本查不出来。</li>
 * </ol>
 */
class TableSeatPoseTest {
    private static final Path MANAGER =
        Path.of("src/main/java/linmumua/doudizhu/world/PhysicalTableManager.java");
    private static final UUID WORLD = UUID.fromString("6f0f0f18-6a1c-4e2f-9a1e-1d5c9f2b7a11");
    private static final UUID SEAT_TWO = UUID.fromString("11111111-2222-3333-4444-555555555555");

    private static TableSeatPose snapshot() {
        TableSeatPose.Pose anchor = new TableSeatPose.Pose(10.0, 64.0, -3.0, 90.0f, 0.0f);
        return new TableSeatPose("1", WORLD, anchor, List.of(
            new TableSeatPose.Seat(0, null,
                new TableSeatPose.Pose(10.0, 63.0, -4.5, 90.0f, 0.0f), 1.0, 0.0),
            new TableSeatPose.Seat(1, UUID.fromString("99999999-8888-7777-6666-555555555555"),
                new TableSeatPose.Pose(8.5, 63.0, -3.0, 0.0f, 0.0f), 0.0, 1.0),
            new TableSeatPose.Seat(2, SEAT_TWO,
                new TableSeatPose.Pose(11.5, 63.0, -3.0, 180.0f, 0.0f), 0.0, -1.0)
        ));
    }

    @Test
    void seatIndexOfUuidFindsOccupiedChairWithoutTouchingEntities() {
        TableSeatPose pose = snapshot();

        TableSeatPose.Seat seat = pose.seatOf(SEAT_TWO);

        assertEquals(2, seat.seatIndex());
        assertEquals(11.5, seat.chair().x());
        assertEquals(180.0f, seat.facingYaw(), "就座朝向必须与椅子朝向同源，不能另存一份");
        assertEquals(-1.0, seat.lateralZ(), "横向轴要有符号，左右面板才能镜像");
    }

    @Test
    void emptySeatHasNoOccupantButStillHasGeometry() {
        TableSeatPose pose = snapshot();

        TableSeatPose.Seat seat = pose.seat(0);

        assertNull(seat.occupantId(), "空座不该伪造占位玩家");
        assertEquals(1.0, seat.lateralX());
        assertEquals(90.0f, seat.facingYaw());
    }

    @Test
    void unknownSeatOrUnknownPlayerYieldsNullInsteadOfGuessing() {
        TableSeatPose pose = snapshot();

        assertNull(pose.seat(7));
        assertNull(pose.seatOf(UUID.randomUUID()));
        assertNull(pose.seatOf(null), "null 玩家必须安全返回，面板入口可能拿不到 UUID");
    }

    @Test
    void seatLookupReturnsTheSameSnapshotInstance() {
        TableSeatPose pose = snapshot();

        assertSame(pose.seats().get(2), pose.seat(2));
        assertSame(pose.seats().get(2), pose.seatOf(SEAT_TWO));
    }

    /**
     * 源级契约：快照必须继续复用牌桌既有的椅子几何，且不得解析任何实体。
     *
     * <p>「不解析实体」不是洁癖：这张快照的唯一用途就是在**玩家 lane** 上给面板定位，
     * 那条 lane 不拥有牌桌 region，读实体必然抛 owner-region 门禁异常。
     */
    @Test
    void poseAccessorReusesChairGeometryAndNeverResolvesEntities() throws IOException {
        String source = Files.readString(MANAGER);
        int start = source.indexOf("public TableSeatPose tableSeatPose(String tableName)");
        assertTrue(start >= 0, "座位姿态快照入口必须存在");
        int end = source.indexOf("public TableSeatPose tableSeatPoseOf(", start);
        assertTrue(end > start, "tableSeatPoseOf 必须紧跟 tableSeatPose，便于一次性审阅");
        String body = source.substring(start, end);

        assertTrue(body.contains("chairLocation(placed,"), "椅子坐标必须复用既有 chairLocation");
        assertTrue(body.contains("handCardYaw(placed.yaw()"), "就座朝向必须复用既有 handCardYaw");
        assertTrue(body.contains("actionStep(seatIndex)"), "横向轴必须复用座位横向轴口径");
        assertTrue(body.contains("placed.seatAssignments()"), "占位玩家必须取自放置快照");

        assertFalse(body.contains("Bukkit.getEntity"), "快照不得解析 Bukkit 实体");
        assertFalse(body.contains("getNearbyEntities"), "快照不得做实体范围查询");
        assertFalse(body.contains("getLocation()"), "快照不得读取实体位置（Player/Entity 都不行）");
        assertFalse(body.contains("getChunk"), "快照不得触发区块访问");
        assertFalse(body.contains("teleport"), "快照不得做世界写入");
    }

    /**
     * 源级契约：世界层不定义面板尺寸/左右偏移。
     *
     * <p>面板画布与偏移属于面板层（MuMenu 画布 + 配置）。若这里也写一份，
     * 就变成两套布局来源，改一边另一边必然漂移——正是「不要重复造布局服务」要防的事。
     */
    @Test
    void worldLayerDoesNotInventPanelSizeOrSideOffsets() throws IOException {
        String source = Files.readString(Path.of("src/main/java/linmumua/doudizhu/world/TableSeatPose.java"));

        assertFalse(source.contains("panelWidth"), "面板宽度不得由世界层定义");
        assertFalse(source.contains("panelOffset"), "面板偏移不得由世界层定义");
        assertFalse(source.contains("1.5"), "面板尺寸这类布局常量不得出现在座位快照里");
        assertTrue(source.contains("lateralX"),
            "世界层只提供一条横向轴，左右对称偏移由面板层自取 ±");
    }
}
