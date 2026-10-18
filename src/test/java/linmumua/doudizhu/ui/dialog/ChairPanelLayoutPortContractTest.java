package linmumua.doudizhu.ui.dialog;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;
import org.junit.jupiter.api.Test;

/**
 * 「保存 → 应用」必须成对、且失败绝不应用的行为测试。
 *
 * <p>为什么值得单独测：落盘与重建是两件事，各自都可能被后来的人「顺手优化」掉——少一个就会出现
 * 「配置改了但世界面板没动」或「面板动了但配置没写」的半截状态；而**失败时还去 apply** 更糟：
 * 配置层会回滚到旧值，世界里的面板却已经是新布局，两边对不上还看不出哪边是对的。
 *
 * <p>这条约束收口在 {@link ChairPanelLayoutPort#saveThenApply}，所以可以用记录型替身直接打
 * 行为断言，不必退化成扫源码字符串。
 */
class ChairPanelLayoutPortContractTest {

    /** 记录调用顺序的替身，可模拟保存成功 / 失败。 */
    private static final class RecordingPort implements ChairPanelLayoutPort {
        private final List<String> calls = new ArrayList<>();
        private final boolean saveSucceeds;
        private ChairPanelLayout applied;

        private RecordingPort(boolean saveSucceeds) {
            this.saveSucceeds = saveSucceeds;
        }

        @Override
        public ChairPanelLayout read() {
            calls.add("read");
            return ChairPanelLayout.defaults();
        }

        @Override
        public void save(ChairPanelLayout next, Consumer<ChairPanelLayout> onSaved, Consumer<String> onRejected) {
            calls.add("save");
            if (saveSucceeds) {
                onSaved.accept(next);
            } else {
                onRejected.accept("模拟落盘失败");
            }
        }

        @Override
        public void apply(ChairPanelLayout layout) {
            calls.add("apply");
            applied = layout;
        }
    }

    @Test
    void saveHappensBeforeApply() {
        RecordingPort port = new RecordingPort(true);
        ChairPanelLayout layout = ChairPanelLayout.defaults()
            .withFieldValue(ChairPanelLayoutField.LEFT_LATERAL, 0.9);
        List<String> seen = new ArrayList<>();

        ChairPanelLayoutPort.saveThenApply(port, layout, saved -> seen.add("applied"), message -> seen.add("rejected"));

        assertEquals(List.of("save", "apply"), port.calls, "必须先保存、成功后才应用");
        assertEquals(List.of("applied"), seen, "成功后要通知调用方重画");
        assertEquals(layout, port.applied, "应用的必须是生效后的布局");
    }

    /** 保存失败：不应用（否则世界面板会先于配置变样）、把原因原样上报。 */
    @Test
    void failedSaveNeverAppliesAndReportsTheReason() {
        RecordingPort port = new RecordingPort(false);
        List<String> seen = new ArrayList<>();

        ChairPanelLayoutPort.saveThenApply(
            port,
            ChairPanelLayout.defaults(),
            saved -> seen.add("applied"),
            seen::add
        );

        assertEquals(List.of("save"), port.calls, "保存失败时绝不能调用 apply");
        assertEquals(List.of("模拟落盘失败"), seen, "失败原因必须原样交给调用方展示");
    }

    @Test
    void nullArgumentsAreRejected() {
        RecordingPort port = new RecordingPort(true);
        assertThrows(IllegalArgumentException.class,
            () -> ChairPanelLayoutPort.saveThenApply(null, ChairPanelLayout.defaults(), null, null));
        assertThrows(IllegalArgumentException.class,
            () -> ChairPanelLayoutPort.saveThenApply(port, null, null, null));
        assertTrue(port.calls.isEmpty(), "参数非法时不得发起任何保存");
    }
}
