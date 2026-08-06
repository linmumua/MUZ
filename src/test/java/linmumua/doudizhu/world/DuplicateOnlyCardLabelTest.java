package linmumua.doudizhu.world;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import linmumua.doudizhu.model.CardRank;
import org.junit.jupiter.api.Test;

/**
 * 「仅重复牌显示标签」开关（DUPLICATE_ONLY / cards.hologram-labels.duplicate-ranks-only）。
 *
 * 意图：这个开关存在的理由是「减少视觉噪音」——单张牌玩家一眼认得出，
 * 只有成对/三张/炸弹这种需要数个数的牌才值得挂数字标签。
 * 所以断言守的不是「代码调了某个 getter」，而是「单张必须不标、重复必须标」。
 *
 * 此前这个开关是死开关：shouldShowLabel 收了 rankCounts 却整个不用，
 * 方法体只有 return plugin.isCardHologramLabelsEnabled()，
 * isDuplicateOnlyCardLabels() 在 src/main 里零调用点。这组测试守的就是它不再退化回死开关。
 *
 * 直接调 static 纯函数 shouldLabelRank：判定逻辑正是为了可测才从
 * 实例私有方法里抽出来的（同 AdminSettingArithmetic 的做法），
 * 因为 PhysicalTableManager 依赖 Bukkit，这个项目的测试跑不起 Bukkit。
 */
class DuplicateOnlyCardLabelTest {

    /** 总开关是硬否决：关掉之后 duplicateOnly 怎么设都不该冒出标签。 */
    @Test
    void masterSwitchOffHidesEveryLabelRegardlessOfDuplicateOnly() {
        Map<CardRank, Integer> counts = counts(CardRank.SEVEN, 3);

        for (boolean duplicateOnly : List.of(true, false)) {
            assertFalse(
                PhysicalTableManager.shouldLabelRank(false, duplicateOnly, CardRank.SEVEN, counts),
                "总开关关闭时不该显示标签，duplicateOnly=" + duplicateOnly
            );
        }
    }

    /** duplicateOnly 关闭 = 老行为：不管重不重复，全都标。 */
    @Test
    void duplicateOnlyOffLabelsEverySingleCard() {
        Map<CardRank, Integer> counts = counts(CardRank.FIVE, 1);

        assertTrue(
            PhysicalTableManager.shouldLabelRank(true, false, CardRank.FIVE, counts),
            "duplicateOnly 关闭时单张也该显示标签，否则退化成了开关常开"
        );
        assertTrue(
            PhysicalTableManager.shouldLabelRank(true, false, CardRank.KING, counts),
            "duplicateOnly 关闭时不在统计表里的牌也该显示标签"
        );
    }

    /** 开关的本体行为：单张不标。这条一旦失效，开关就等于没实现。 */
    @Test
    void duplicateOnlyHidesLabelForLoneCard() {
        Map<CardRank, Integer> counts = counts(CardRank.NINE, 1);

        assertFalse(
            PhysicalTableManager.shouldLabelRank(true, true, CardRank.NINE, counts),
            "只标重复牌时，手里只有一张 9 就不该给它挂标签"
        );
    }

    /** 成对、三张、炸弹都属于「需要数个数」的牌，必须标。 */
    @Test
    void duplicateOnlyKeepsLabelForPairsTriplesAndBombs() {
        for (int copies : List.of(2, 3, 4)) {
            assertTrue(
                PhysicalTableManager.shouldLabelRank(true, true, CardRank.QUEEN, counts(CardRank.QUEEN, copies)),
                "只标重复牌时，" + copies + " 张同点数应当显示标签"
            );
        }
    }

    /**
     * 同一手牌里必须能按点数分别判定，不能一刀切。
     * 混合场景是真实牌局的常态：对 3 带单张 A。
     */
    @Test
    void duplicateOnlyDecidesPerRankWithinOneHand() {
        Map<CardRank, Integer> hand = new EnumMap<>(CardRank.class);
        hand.put(CardRank.THREE, 2);
        hand.put(CardRank.ACE, 1);

        assertTrue(
            PhysicalTableManager.shouldLabelRank(true, true, CardRank.THREE, hand),
            "一手牌里的对 3 应当显示标签"
        );
        assertFalse(
            PhysicalTableManager.shouldLabelRank(true, true, CardRank.ACE, hand),
            "同一手牌里的单张 A 不该显示标签"
        );
    }

    /**
     * 统计表缺键或整个为 null 时不能崩，也不能默认放行。
     * 缺键意味着「这张牌不在统计范围内」，按不重复处理才符合开关语义。
     */
    @Test
    void missingOrNullCountsAreTreatedAsNonDuplicateWithoutCrashing() {
        assertFalse(
            PhysicalTableManager.shouldLabelRank(true, true, CardRank.TEN, counts(CardRank.SEVEN, 2)),
            "统计表里没有这个点数时应当按不重复处理"
        );
        assertFalse(
            PhysicalTableManager.shouldLabelRank(true, true, CardRank.TEN, Map.of()),
            "空统计表应当按不重复处理"
        );
        assertFalse(
            PhysicalTableManager.shouldLabelRank(true, true, CardRank.TEN, null),
            "统计表为 null 时应当按不重复处理，且不能抛 NPE"
        );
        assertFalse(
            PhysicalTableManager.shouldLabelRank(true, true, null, counts(CardRank.TEN, 2)),
            "点数为 null 时应当按不重复处理，且不能抛 NPE"
        );
    }

    /**
     * 大小王在斗地主里各只有一张，天然永不重复，所以开启开关后它们的标签会消失。
     * 这是「只标重复牌」的直接推论，而非漏洞：这里显式钉住这个结论，
     * 避免日后有人误以为大小王被特殊照顾过。
     */
    @Test
    void jokersLoseTheirLabelBecauseTheyCanNeverDuplicate() {
        for (CardRank joker : List.of(CardRank.SMALL_JOKER, CardRank.BIG_JOKER)) {
            assertTrue(joker.isJoker(), joker + " 应当被识别为王牌");
            assertFalse(
                PhysicalTableManager.shouldLabelRank(true, true, joker, counts(joker, 1)),
                joker + " 只有一张，开启「仅重复牌」后标签消失属于预期"
            );
            assertTrue(
                PhysicalTableManager.shouldLabelRank(true, false, joker, counts(joker, 1)),
                joker + " 在 duplicateOnly 关闭时仍应显示标签"
            );
        }
    }

    /**
     * 纯函数正确不代表渲染真的接上了：这条守的是接线本身。
     * shouldShowLabel 必须把 isDuplicateOnlyCardLabels() 喂给判定，
     * 否则纯函数再对，开关也还是死的（这正是改动前的状态）。
     */
    @Test
    void renderingPathActuallyConsultsTheDuplicateOnlySetting() throws IOException {
        String source = Files.readString(
            Path.of("src/main/java/linmumua/doudizhu/world/PhysicalTableManager.java")
        );
        int start = source.indexOf("private boolean shouldShowLabel(");
        assertTrue(start >= 0, "找不到 shouldShowLabel，这条测试的锚点已失效");
        int end = source.indexOf("\n    }", start);
        assertTrue(end > start, "找不到 shouldShowLabel 的结束锚点");
        String body = source.substring(start, end);

        assertTrue(
            body.contains("isDuplicateOnlyCardLabels()"),
            "shouldShowLabel 没有读 isDuplicateOnlyCardLabels()，开关又变成死开关了"
        );
        assertTrue(
            body.contains("rankCounts"),
            "shouldShowLabel 收了 rankCounts 却不用，无法判定重复"
        );
    }

    private static Map<CardRank, Integer> counts(CardRank rank, int copies) {
        Map<CardRank, Integer> counts = new EnumMap<>(CardRank.class);
        counts.put(rank, copies);
        return counts;
    }
}
