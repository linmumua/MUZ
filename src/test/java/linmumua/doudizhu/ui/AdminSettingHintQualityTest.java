package linmumua.doudizhu.ui;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import linmumua.doudizhu.DoudizhuPlugin.AdminSetting;
import org.junit.jupiter.api.Test;

/**
 * 锁住管理菜单里 94 条设置项提示文案的「写给玩家看」这条底线。
 *
 * 为什么值得整整一个测试类：adminSettingHint 那个 switch 是 exhaustive 的，
 * 编译器只保证「每个枚举都有一条提示」，完全不管提示里写了什么。
 * 于是三类问题可以一路进到线上而现有测试全绿：
 * 1. 开发术语直接漏给玩家（Title、ItemDisplay、tick、config.yml、枚举名本身）；
 * 2. 复制粘贴新增一项后忘了改文字，两项提示一模一样；
 * 3. 提示里写死的数字和枚举真实值不一致——文案在骗玩家。
 *
 * adminSettingHint 是 private 且不碰 plugin 字段，但这个项目跑不起 Bukkit，
 * 实例化 HandGuiService 去调它不现实。所以这里走源码文本解析：
 * 零侵入，不需要为测试放宽生产代码的可见性。解析方式与同目录
 * HoverCardScaleSettingTest 的 section() 保持一致。
 */
class AdminSettingHintQualityTest {
    private static final Path GUI = Path.of("src/main/java/linmumua/doudizhu/ui/HandGuiService.java");

    /** 单条提示的码位上限。中文按 1 计，超过就是一屏 lore 塞不下、玩家不会读。 */
    private static final int MAX_CODE_POINTS = 40;

    /**
     * 开发术语黑名单：这些词出现在提示里，玩家读不懂。
     *
     * 「槽位」故意收进来：物品栏槽位玩家能懂，但这批提示里它只会出现在
     * 「Title 占哪个槽位」这类描述上，属于术语泄漏。真要说物品栏位置，
     * 有「格子」这类玩家词可用。
     */
    private static final List<String> FORBIDDEN_TERMS = List.of(
        "Title", "subtitle", "Component", "ItemDisplay", "TextDisplay",
        "Interaction", "Billboard", "Transformation", "tick",
        "码位", "字形", "枚举", "通道", "步进", "速度曲线", "槽位",
        "config.yml", "CraftEngine", "Bukkit", "Vault", "渲染器", "压层", "bot", "spec"
    );

    /** 枚举名泄漏：UPPER_SNAKE_CASE 只可能是代码标识符被直接抄进文案。 */
    private static final Pattern ENUM_NAME_LEAK = Pattern.compile("[A-Z][A-Z0-9]*_[A-Z0-9_]*");

    /** 配置键泄漏：点分小写路径就是 config.yml 里的 key。 */
    private static final Pattern CONFIG_PATH_LEAK = Pattern.compile("[a-z]+\\.[a-z-]+\\.[a-z-]+");

    /** 提示里写死的固定步长，例如「固定 0.001 步长」。 */
    private static final Pattern FIXED_STEP_IN_TEXT = Pattern.compile("固定\\s*([0-9.]+)");

    private static Map<String, String> hints() throws IOException {
        String block = section(
            Files.readString(GUI),
            "private String adminSettingHint(",
            "\n    }"
        );
        Matcher matcher = Pattern.compile("case ([A-Z_]+) -> \"([^\"]*)\";").matcher(block);
        Map<String, String> found = new LinkedHashMap<>();
        while (matcher.find()) {
            found.put(matcher.group(1), matcher.group(2));
        }
        assertFalse(found.isEmpty(), "没从 adminSettingHint 解析出任何提示，这条测试的正则已经跟代码脱节");
        return found;
    }

    private static String section(String source, String from, String to) {
        int start = source.indexOf(from);
        assertTrue(start >= 0, "源码里找不到 " + from + "，这条测试的锚点已失效");
        int end = source.indexOf(to, start);
        assertTrue(end > start, "源码里找不到 " + from + " 之后的结束锚点");
        return source.substring(start, end);
    }

    /**
     * 每个枚举都要有一条非空提示。
     *
     * 为什么值得这一条：switch 的 exhaustive 只在编译期成立，
     * 而这条测试同时兼任「解析没脱节」的自检——解析出的条数必须正好等于
     * AdminSetting.values().length。少一条说明有 case 写法不匹配正则
     * （比如换行、拼接、调了方法），后面所有断言都会漏检那一条，
     * 那才是最危险的情况：测试全绿但实际没覆盖。
     */
    @Test
    void everySettingHasANonEmptyHint() throws IOException {
        Map<String, String> hints = hints();

        assertEquals(
            AdminSetting.values().length,
            hints.size(),
            "解析出的提示条数与枚举数量不一致，正则跟 adminSettingHint 脱节了。已解析：" + hints.keySet()
        );

        List<String> missing = new ArrayList<>();
        List<String> blank = new ArrayList<>();
        for (AdminSetting setting : AdminSetting.values()) {
            String hint = hints.get(setting.name());
            if (hint == null) {
                missing.add(setting.name());
            } else if (hint.isBlank()) {
                blank.add(setting.name());
            }
        }

        assertTrue(missing.isEmpty(), "这些设置没有解析到提示：" + missing);
        assertTrue(blank.isEmpty(), "这些设置的提示是空的：" + blank);
    }

    /**
     * 提示里不得出现开发术语。
     *
     * 为什么值得这一条：这批文案的唯一读者是在游戏里点菜单的服主/玩家，
     * 他们没读过源码。写「调整 TextDisplay 的 Transformation」在代码评审里
     * 看着精确，对玩家等于没写。这条断言把「精确但没人懂」直接判为不合格。
     */
    @Test
    void hintsAvoidDeveloperJargon() throws IOException {
        List<String> offenders = jargonViolations(hints());

        assertTrue(offenders.isEmpty(), "提示里出现了玩家读不懂的开发术语：\n" + String.join("\n", offenders));
    }

    /**
     * 提示里不得出现枚举名或 config.yml 配置键。
     *
     * 为什么和上一条分开：术语黑名单是靠人维护的枚举清单，永远追不上新词；
     * 这两个正则抓的是「代码标识符被整段复制到文案里」这个动作本身，
     * 不依赖具体词表，新增设置时自动生效。
     */
    @Test
    void hintsDoNotLeakCodeIdentifiers() throws IOException {
        List<String> offenders = identifierLeakViolations(hints());

        assertTrue(offenders.isEmpty(), "提示里泄漏了代码标识符：\n" + String.join("\n", offenders));
    }

    private static List<String> jargonViolations(Map<String, String> hints) {
        List<String> offenders = new ArrayList<>();
        hints.forEach((name, text) -> {
            for (String term : FORBIDDEN_TERMS) {
                if (text.contains(term)) {
                    offenders.add(name + " 含术语「" + term + "」：" + text);
                }
            }
        });
        return offenders;
    }

    private static List<String> identifierLeakViolations(Map<String, String> hints) {
        List<String> offenders = new ArrayList<>();
        hints.forEach((name, text) -> {
            Matcher enumLeak = ENUM_NAME_LEAK.matcher(text);
            if (enumLeak.find()) {
                offenders.add(name + " 泄漏枚举名「" + enumLeak.group() + "」：" + text);
            }
            Matcher pathLeak = CONFIG_PATH_LEAK.matcher(text);
            if (pathLeak.find()) {
                offenders.add(name + " 泄漏配置键「" + pathLeak.group() + "」：" + text);
            }
        });
        return offenders;
    }

    /**
     * 判据自检：往检测器里塞几条已知不合格的假提示，它必须全部抓到。
     *
     * 为什么值得这一条：前面几条断言的失败信息很有用，但「没有失败」既可能是
     * 文案确实合格，也可能是检测器写坏了（正则打错、黑名单被清空、循环没进去）
     * ——后者会让整个测试类变成一句永远为真的空话。这条用固定样本把检测器本身
     * 钉住，样本不来自源码，所以文案怎么改都不影响它。
     */
    @Test
    void detectorsActuallyCatchPlantedViolations() {
        Map<String, String> planted = new LinkedHashMap<>();
        planted.put("FAKE_JARGON", "调整 Title 的 Transformation，每 5 tick 刷新一次。");
        planted.put("FAKE_ENUM_LEAK", "对应 HOVER_CARD_LIFT 这一项。");
        planted.put("FAKE_CONFIG_LEAK", "改的是 render.card-hover.lift 的值。");
        planted.put("FAKE_CLEAN", "看向手牌时上浮多少。");

        List<String> jargon = jargonViolations(planted);
        assertTrue(
            jargon.stream().anyMatch(line -> line.startsWith("FAKE_JARGON")),
            "术语黑名单没抓到明显含 Title / Transformation / tick 的假提示，检测器坏了：" + jargon
        );

        List<String> leaks = identifierLeakViolations(planted);
        assertTrue(
            leaks.stream().anyMatch(line -> line.startsWith("FAKE_ENUM_LEAK")),
            "枚举名正则没抓到 HOVER_CARD_LIFT，检测器坏了：" + leaks
        );
        assertTrue(
            leaks.stream().anyMatch(line -> line.startsWith("FAKE_CONFIG_LEAK")),
            "配置键正则没抓到 render.card-hover.lift，检测器坏了：" + leaks
        );

        assertTrue(
            jargon.stream().noneMatch(line -> line.startsWith("FAKE_CLEAN"))
                && leaks.stream().noneMatch(line -> line.startsWith("FAKE_CLEAN")),
            "检测器把一条合格文案也判成了违规，说明它会误报：" + jargon + leaks
        );
    }

    /**
     * 94 条提示不能有两条完全相同。
     *
     * 为什么值得这一条：新增设置项的标准动作是复制上一行改枚举名，
     * 忘改文案时编译照过、菜单照显示，玩家看到两项写着同一句话，
     * 只能靠猜哪项是哪项。
     *
     * 判定用完全相等而不是相似度：SEAT_NAME_SCALE / LATERAL / VERTICAL / DEPTH
     * 这四项本来就只差「大小 / 左右 / 上下 / 前后」一个词，相似度会把
     * 正确文案判成重复，误报比漏报更糟——测试一喊狼来了就没人看了。
     */
    @Test
    void noTwoHintsAreIdentical() throws IOException {
        Map<String, List<String>> byText = new LinkedHashMap<>();
        hints().forEach((name, text) -> byText.computeIfAbsent(text, ignored -> new ArrayList<>()).add(name));

        List<String> duplicates = byText.entrySet().stream()
            .filter(entry -> entry.getValue().size() > 1)
            .map(entry -> entry.getValue() + " 共用同一句：" + entry.getKey())
            .toList();

        assertTrue(duplicates.isEmpty(), "有设置项共用完全相同的提示，玩家分不清：\n" + String.join("\n", duplicates));
    }

    /**
     * 单条提示不超过 40 个码位。
     *
     * 为什么用 codePointCount 而不是 length()：中文在 UTF-16 里 length() 也是 1，
     * 但这批文案将来可能带表情或私有区图标字符（项目里已有 \\uf900 一类头像码位），
     * 那些是代理对，length() 会翻倍算，导致上限随字符种类漂移。
     * 上限本身守的是「一行 lore 能不能一眼读完」，超长提示玩家直接跳过不看。
     */
    @Test
    void hintsStayShortEnoughToRead() throws IOException {
        List<String> tooLong = new ArrayList<>();
        for (Map.Entry<String, String> entry : hints().entrySet()) {
            String text = entry.getValue();
            int length = text.codePointCount(0, text.length());
            if (length > MAX_CODE_POINTS) {
                tooLong.add(entry.getKey() + "（" + length + " 字符）：" + text);
            }
        }

        assertTrue(
            tooLong.isEmpty(),
            "这些提示超过 " + MAX_CODE_POINTS + " 个字符，玩家不会读完：\n" + String.join("\n", tooLong)
        );
    }

    /**
     * 提示里写死的「固定 X」必须等于该项真实的 fixedStep()。
     *
     * 为什么这条价值最高：其它断言守的是「读不读得懂」，这条守的是
     * 「说的是不是真的」。固定步长是枚举构造参数里的一个数字，
     * 改它不会碰到 HandGuiService 的文案；文案里那个 0.001 就此变成谎言。
     * 玩家照着提示以为一次点击走 0.001，实际走 0.01，会以为按钮坏了，
     * 而这种偏差在代码里横跨两个文件，评审时几乎不可能对出来。
     */
    @Test
    void hardCodedStepNumbersMatchTheEnum() throws IOException {
        Map<String, String> hints = hints();
        List<String> lies = new ArrayList<>();

        for (AdminSetting setting : AdminSetting.values()) {
            if (!setting.hasFixedStep()) {
                continue;
            }
            String text = hints.get(setting.name());
            if (text == null) {
                continue;
            }
            Matcher matcher = FIXED_STEP_IN_TEXT.matcher(text);
            while (matcher.find()) {
                double claimed = Double.parseDouble(matcher.group(1));
                if (Math.abs(claimed - setting.fixedStep()) > 1.0E-12) {
                    lies.add(
                        setting.name() + " 的文案在骗玩家：提示写「固定 " + matcher.group(1)
                            + "」，实际 fixedStep() 是 " + setting.fixedStep() + "。原文：" + text
                    );
                }
            }
        }

        assertTrue(lies.isEmpty(), "提示里写死的步长与枚举真实值不一致：\n" + String.join("\n", lies));
    }

    /**
     * 引号统一用「」，不许出现全角 “ ”。
     *
     * 为什么值得这一条：同一个菜单里两种引号并存看着像没做完的半成品，
     * 而引号风格是纯粹的约定，没有任何编译期或运行期信号，
     * 全靠写的时候记得——正好是测试该接手的那类约束。
     */
    @Test
    void hintsUseCornerBracketQuotesOnly() throws IOException {
        List<String> offenders = new ArrayList<>();
        for (Map.Entry<String, String> entry : hints().entrySet()) {
            if (entry.getValue().indexOf('\u201C') >= 0 || entry.getValue().indexOf('\u201D') >= 0) {
                offenders.add(entry.getKey() + "：" + entry.getValue());
            }
        }

        assertTrue(
            offenders.isEmpty(),
            "这些提示用了全角引号 \u201C\u201D，项目统一要求「」：\n" + String.join("\n", offenders)
        );
    }
}
