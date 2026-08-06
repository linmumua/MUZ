package linmumua.doudizhu.world;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import linmumua.doudizhu.DoudizhuPlugin.AdminSetting;
import linmumua.doudizhu.world.PhysicalTableManager.Vector;
import org.junit.jupiter.api.Test;

/**
 * SEAT_INFO_* 三项（座位小字的左右/上下/前后偏移）必须真的驱动渲染。
 *
 * 意图：这三项曾经是死配置——GUI 里可点可调、也会写进 config.yml，
 * 但 seatInfoLocation 只是「主字条位置减去一个硬编码 gap」，
 * 三个 getter 在 src/main 里零调用点。玩家怎么调都不动，只能进服才发现。
 * 这个文件守两件事：偏移确实被读取，且轴向不许串（左右不能变成前后）。
 *
 * 另一条同等重要：这三项的出厂默认不是 0（SEAT_INFO_VERTICAL 默认 -0.22），
 * 所以「让它生效」很容易顺手把老服务器的小字整体挪位。
 * 净偏移必须以默认值为零点，升级后视觉零变化。
 */
class SeatInfoOffsetTest {
    private static final Path MANAGER = Path.of("src/main/java/linmumua/doudizhu/world/PhysicalTableManager.java");
    private static final double EPS = 1.0E-9;

    /**
     * 最关键的一条：全部取出厂默认值时，净偏移必须是零向量。
     *
     * 这守的是升级安全。现有服务器的 config.yml 里这三项就是默认值，
     * 小字现在停在「主字条减 gap」的位置上。如果实现直接把设置值加到基准上，
     * 默认的 -0.22 会让小字凭空再往下掉一行，所有老桌子一升级就跑位。
     * 断言写成遍历三个座位和多个 yaw：零向量不该受座位或朝向影响。
     */
    @Test
    void defaultSettingValuesProduceNoDisplacementAtAll() {
        double lateral = AdminSetting.SEAT_INFO_LATERAL.defaultValue();
        double vertical = AdminSetting.SEAT_INFO_VERTICAL.defaultValue();
        double depth = AdminSetting.SEAT_INFO_DEPTH.defaultValue();

        for (float yaw : new float[] {0.0f, 37.0f, 90.0f, 180.0f, -90.0f}) {
            for (int seatIndex = 0; seatIndex < 3; seatIndex++) {
                Vector offset = PhysicalTableManager.seatInfoExtraOffset(seatIndex, yaw, lateral, vertical, depth);
                String where = "座位 " + seatIndex + " yaw " + yaw;
                assertEquals(0.0, offset.x(), EPS, where + " 在默认值下产生了 X 位移，老服务器小字会跑位");
                assertEquals(0.0, offset.y(), EPS, where + " 在默认值下产生了 Y 位移，老服务器小字会跑位");
                assertEquals(0.0, offset.z(), EPS, where + " 在默认值下产生了 Z 位移，老服务器小字会跑位");
            }
        }
    }

    /**
     * 默认值非 0 这件事本身值得钉住。
     *
     * 上面那条测试在「默认值全是 0」时会自动通过，等于失去保护。
     * 一旦有人为了图方便把 SEAT_INFO_VERTICAL 的默认改成 0，
     * 这条会失败并提醒：改默认值就是在改老服务器的观感，得单独决策。
     */
    @Test
    void verticalDefaultIsNonZeroSoTheZeroPointReallyMatters() {
        // -0.5 是测试服实测调优后固化的默认值（原为 -0.22）。
        // 这条钉子的作用不变：再改就得重新确认升级后的观感。
        assertEquals(-0.5, AdminSetting.SEAT_INFO_VERTICAL.defaultValue(), EPS,
            "SEAT_INFO_VERTICAL 的默认值变了；净偏移的零点随之改变，需重新确认升级后视觉不变");
    }

    /**
     * 「左右」必须真的是左右：调 lateral 只能让小字沿该座位的横向轴走。
     *
     * 判据用座位自身的横向轴做点积，而不是写死世界坐标：
     * 座位方位以后若调整，这条表达的仍是「沿自己的左手边」这个意图。
     * 若实现把 lateral 接到了纵深轴上（最容易犯的串轴错误），
     * 点积会掉到 0，本用例失败。
     */
    @Test
    void lateralSettingMovesTheLabelSidewaysNotForwards() {
        double lateral = AdminSetting.SEAT_INFO_LATERAL.defaultValue() + 0.5;

        for (int seatIndex = 0; seatIndex < 3; seatIndex++) {
            Vector offset = PhysicalTableManager.seatInfoExtraOffset(
                seatIndex,
                0.0f,
                lateral,
                AdminSetting.SEAT_INFO_VERTICAL.defaultValue(),
                AdminSetting.SEAT_INFO_DEPTH.defaultValue()
            );
            Vector lateralAxis = PhysicalTableManager.seatLateralAxis(seatIndex);
            Vector depthAxis = PhysicalTableManager.seatDepthAxis(seatIndex);

            assertEquals(0.5, dot(offset, lateralAxis), EPS,
                "座位 " + seatIndex + " 的左右偏移没有落在自己的横向轴上");
            assertEquals(0.0, dot(offset, depthAxis), EPS,
                "座位 " + seatIndex + " 的左右偏移漏到了纵深轴，左右被当成了前后");
            assertEquals(0.0, offset.y(), EPS, "座位 " + seatIndex + " 的左右偏移不该改变高度");
        }
    }

    /**
     * 「前后」必须真的是前后，且正值朝桌心，与 EMPTY_SEAT_* 同向。
     *
     * 两项设置在 GUI 里并排摆着，玩家会拿同一套直觉去调。
     * 如果小字的「前」和主字条的「前」反向，调起来会互相打架。
     */
    @Test
    void depthSettingMovesTheLabelTowardTheTableCentre() {
        double depth = AdminSetting.SEAT_INFO_DEPTH.defaultValue() + 0.3;

        for (int seatIndex = 0; seatIndex < 3; seatIndex++) {
            Vector offset = PhysicalTableManager.seatInfoExtraOffset(
                seatIndex,
                0.0f,
                AdminSetting.SEAT_INFO_LATERAL.defaultValue(),
                AdminSetting.SEAT_INFO_VERTICAL.defaultValue(),
                depth
            );
            Vector lateralAxis = PhysicalTableManager.seatLateralAxis(seatIndex);
            Vector depthAxis = PhysicalTableManager.seatDepthAxis(seatIndex);

            assertEquals(0.3, dot(offset, depthAxis), EPS,
                "座位 " + seatIndex + " 的前后偏移没有落在自己的纵深轴上，或方向背离桌心");
            assertEquals(0.0, dot(offset, lateralAxis), EPS,
                "座位 " + seatIndex + " 的前后偏移漏到了横向轴，前后被当成了左右");
        }
    }

    /**
     * 「上下」只能动 Y，且不能被桌子朝向旋转带偏。
     */
    @Test
    void verticalSettingOnlyChangesHeight() {
        double vertical = AdminSetting.SEAT_INFO_VERTICAL.defaultValue() + 0.4;

        for (float yaw : new float[] {0.0f, 45.0f, 180.0f}) {
            for (int seatIndex = 0; seatIndex < 3; seatIndex++) {
                Vector offset = PhysicalTableManager.seatInfoExtraOffset(
                    seatIndex,
                    yaw,
                    AdminSetting.SEAT_INFO_LATERAL.defaultValue(),
                    vertical,
                    AdminSetting.SEAT_INFO_DEPTH.defaultValue()
                );
                String where = "座位 " + seatIndex + " yaw " + yaw;
                assertEquals(0.4, offset.y(), EPS, where + " 的上下偏移没有生效");
                assertEquals(0.0, offset.x(), EPS, where + " 的上下偏移漏到了 X");
                assertEquals(0.0, offset.z(), EPS, where + " 的上下偏移漏到了 Z");
            }
        }
    }

    /**
     * seatInfoLocation 必须真的读这三个 getter。
     *
     * 上面几条只覆盖 seatInfoExtraOffset 这个纯函数——它算得再对，
     * 只要 seatInfoLocation 不调用它，设置项照样是死配置。
     * 这个项目跑不起 Bukkit，seatInfoLocation 依赖 GameTable 与 Location，
     * 没法直接调；但「有没有读设置」能在源码层面判定，正是本次改动的要点。
     * 判定方式与 SeatLabelParityTest 一致：扫方法体。
     */
    @Test
    void seatInfoLocationActuallyReadsAllThreeSettings() throws IOException {
        String body = methodBody("private Location seatInfoLocation(");

        List<String> missing = new ArrayList<>();
        for (String getter : List.of(
            "getSeatInfoLateralOffset()",
            "getSeatInfoVerticalOffset()",
            "getSeatInfoDepthOffset()"
        )) {
            if (!body.contains(getter)) {
                missing.add(getter);
            }
        }

        assertTrue(missing.isEmpty(),
            "seatInfoLocation 没有读这些设置，SEAT_INFO_* 又变回了死配置：" + missing);
    }

    /**
     * 硬编码的 gap 是基准行距，三个偏移只是它之上的微调，不是替代品。
     *
     * 如果有人把 gap 删掉、改由 SEAT_INFO_VERTICAL 独自决定行距，
     * 默认值下小字就会和主字条重叠。这条守住基准仍在。
     */
    @Test
    void baselineGapSurvivesAsTheRowSpacing() throws IOException {
        String body = methodBody("private Location seatInfoLocation(");

        assertTrue(body.contains("0.18"),
            "seatInfoLocation 里的基准行距 gap 不见了，小字会和主字条叠在一起");
        assertTrue(body.contains("getSmallTextScale()"),
            "基准行距不再随小字尺寸放大，字号调大后两行会互相压住");
    }

    private static double dot(Vector offset, Vector axis) {
        return offset.x() * axis.x() + offset.z() * axis.z();
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
