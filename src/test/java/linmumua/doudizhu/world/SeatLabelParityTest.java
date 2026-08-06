package linmumua.doudizhu.world;

import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * 椅子外侧的字条，在「空位」和「有人坐」两种状态下必须读同一组参数。
 *
 * 意图：玩家只看得见空位那条字，也只会去调它（EMPTY_SEAT_*）。
 * 旧实现一入座就切读 SEAT_NAME_*，两组出厂默认值虽然相同，
 * 但玩家调过 EMPTY_SEAT_* 之后，坐下瞬间字条就跳回没调过的尺寸和位置
 * （实测症状：抬高的大字变成贴脸的小字）。
 * 这条测试守的就是「不许再按有人无人分叉」。
 *
 * 用源码扫描而不是调用方法：seatNameScale / seatNameLocation 依赖
 * GameTable 与 Bukkit Location，这个项目的测试跑不起 Bukkit；
 * 而「读的是哪一组设置」恰好能在源码层面判定，且正是本次改动的要点。
 */
class SeatLabelParityTest {
    private static final Path MANAGER = Path.of("src/main/java/linmumua/doudizhu/world/PhysicalTableManager.java");

    /** 有人无人的字条缩放必须同源，不能再有 SEAT_NAME_SCALE 这条分叉。 */
    @Test
    void labelScaleIsIdenticalWhetherTheSeatIsTaken() throws IOException {
        String body = methodBody("private float seatNameScale(");

        assertTrue(body.contains("getEmptySeatScale()"), "字条缩放应当统一读 EMPTY_SEAT_SCALE");
        assertTrue(
            !body.contains("getSeatNameScale()"),
            "字条缩放仍在按有人无人分叉读 SEAT_NAME_SCALE，入座瞬间会跳变"
        );
    }

    /** 三个方向的偏移同理：入座后必须沿用空位那一组，不能各读一套。 */
    @Test
    void labelOffsetsAreIdenticalWhetherTheSeatIsTaken() throws IOException {
        String body = methodBody("private Location seatNameLocation(");

        List<String> missing = new ArrayList<>();
        for (String getter : List.of("getEmptySeatLateralOffset()", "getEmptySeatVerticalOffset()", "getEmptySeatDepthOffset()")) {
            if (!body.contains(getter)) {
                missing.add(getter);
            }
        }
        assertTrue(missing.isEmpty(), "字条偏移应当统一读这些 EMPTY_SEAT_* 取值，缺少：" + missing);

        List<String> leaked = new ArrayList<>();
        for (String getter : List.of("getSeatNameLateralOffset()", "getSeatNameVerticalOffset()", "getSeatNameDepthOffset()")) {
            if (body.contains(getter)) {
                leaked.add(getter);
            }
        }
        assertTrue(leaked.isEmpty(), "字条偏移仍在按有人无人分叉，入座瞬间会跳位：" + leaked);
    }

    /**
     * 最直接的意图断言：这两个方法体里不该再出现「座位是否为空」的三元分支。
     * 只查 getter 名有个漏洞——有人可能换个写法重新引入分叉。
     */
    @Test
    void neitherMethodBranchesOnSeatOccupancy() throws IOException {
        for (String signature : List.of("private float seatNameScale(", "private Location seatNameLocation(")) {
            String body = methodBody(signature);
            assertTrue(
                !body.contains("seat == null"),
                signature + " 里仍按座位是否为空分叉，入座字条会跳变"
            );
        }
    }

    /** 已停用的 SEAT_NAME_* 不能再驱动任何渲染。 */
    @Test
    void retiredSeatNameSettingsNoLongerDriveRendering() throws IOException {
        String source = Files.readString(MANAGER);

        List<String> leaked = new ArrayList<>();
        for (String getter : List.of(
            "getSeatNameScale()",
            "getSeatNameLateralOffset()",
            "getSeatNameVerticalOffset()",
            "getSeatNameDepthOffset()"
        )) {
            if (source.contains(getter)) {
                leaked.add(getter);
            }
        }

        assertTrue(leaked.isEmpty(), "渲染代码里仍在使用已停用的 SEAT_NAME_*：" + leaked);
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
