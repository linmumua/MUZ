package linmumua.doudizhu.config;

import java.util.Objects;
import java.util.logging.Logger;

/**
 * 旧安装的配置文案迁移：把写死倍率的明牌文案升级成带 {@code %multiplier%} 的模板。
 *
 * <p>【为什么需要单独一步】{@code mergeMissingFrom} 只补<b>缺失</b>的键，不会覆盖已存在值。
 * 老服升级后 {@code round-opening.messages.*} 三键都在，于是新模板永远进不去，
 * 玩家会继续看到「整局最多 ×2」，而实际结算已经是 3→2→1 阶梯——文案与规则对不上。
 *
 * <p>【为什么不能无条件覆盖】这三个键是玩家可改的文案。管理员可能已经写过自己的措辞，
 * 升级时把它冲成官方默认属于破坏用户数据。因此迁移只在<b>当前值恰好等于某个已知旧默认</b>
 * 时才替换：
 * <ul>
 *   <li>等于旧默认 → 说明没人改过，安全替换成新模板；</li>
 *   <li>等于新模板 → 已经迁过，不动（幂等）；</li>
 *   <li>其它任何值 → 视为自定义文案，<b>原样保留</b>，只记一条提示说明该怎么加占位符。</li>
 * </ul>
 * 判据是<b>逐键精确字符串相等</b>，不做正则、不做模糊匹配、不替换文本里所有的 "2"。
 *
 * <p>【调用点】由插件装配层（{@code DoudizhuPlugin.ensureConfigIntegrity}）在本类返回值上
 * 做 {@code changed |= ...}，与其它补键步骤同点位，因此启动与 {@code /muz reload} 都会走到。
 * 本类自己不做 IO、不碰 Bukkit、不写盘——只改内存里的配置对象，落盘交给既有流程。
 */
public final class RevealMessageMigration {
    public static final String REVEALING = "round-opening.messages.revealing";
    public static final String REVEALED = "round-opening.messages.revealed";
    public static final String REVEAL_BUTTON = "round-opening.messages.reveal-button";

    /**
     * 1.10.62 及更早版本出厂模板里这三条的确切文本。
     *
     * <p>只有当前值与其中之一<b>逐字符相等</b>才认为「没人改过」。这些字符串是从历史
     * config.yml 原文抄下来的，不要顺手改措辞——改动会让本来能命中的迁移静默失效。
     */
    private static final String OLD_REVEALING = "明牌窗口：还剩 %seconds% 秒，可自愿明牌（整局最多 ×2）。";
    private static final String OLD_REVEALED = "%player% 已明牌，本局公共倍率变为 ×2。";
    private static final String OLD_REVEAL_BUTTON = "明牌 ×2";

    /** 新模板：与 {@code RoundOpeningSettings.Messages} 的代码默认值逐字一致。 */
    private static final String NEW_REVEALING = "明牌窗口：还剩 %seconds% 秒，可自愿明牌（当前 ×%multiplier%）。";
    private static final String NEW_REVEALED = "%player% 已明牌，本局公共倍率变为 ×%multiplier%。";
    private static final String NEW_REVEAL_BUTTON = "明牌 ×%multiplier%";

    private RevealMessageMigration() {
    }

    /**
     * 就地迁移三条明牌文案。
     *
     * @param config 主配置对象；为 null 时直接返回 false
     * @param logger 自定义文案提示的输出口；为 null 时不提示
     * @return 是否有键被改写（供 {@code ensureConfigIntegrity} 累积 changed 后决定是否落盘）
     */
    public static boolean migrate(MuzYamlConfig config, Logger logger) {
        if (config == null) {
            return false;
        }
        boolean changed = false;
        changed |= migrateOne(config, logger, REVEALING, OLD_REVEALING, NEW_REVEALING);
        changed |= migrateOne(config, logger, REVEALED, OLD_REVEALED, NEW_REVEALED);
        changed |= migrateOne(config, logger, REVEAL_BUTTON, OLD_REVEAL_BUTTON, NEW_REVEAL_BUTTON);
        return changed;
    }

    private static boolean migrateOne(MuzYamlConfig config, Logger logger, String path,
                                      String oldDefault, String newTemplate) {
        Object raw = config.get(path);
        if (!(raw instanceof String current)) {
            // 缺键交给 mergeMissingFrom 补；类型不对则不属于本迁移的职责范围。
            return false;
        }
        if (current.equals(newTemplate)) {
            return false;
        }
        if (current.equals(oldDefault)) {
            config.set(path, newTemplate);
            return true;
        }
        if (logger != null && !current.contains("%multiplier%")) {
            // 自定义文案：不悄改，只提示。缺占位符时玩家会看到固定倍率，但那是他自己的选择。
            logger.info(path + " 是自定义文案且不含 %multiplier% 占位符，已原样保留（明牌倍率现在是 3→2→1 阶梯，"
                + "如需随倍率变化请自行加上 %multiplier%）。");
        }
        return false;
    }

    /** 旧默认值（供测试与审计对齐，避免测试里再抄一份字符串）。 */
    public static String legacyDefault(String path) {
        Objects.requireNonNull(path, "path");
        return switch (path) {
            case REVEALING -> OLD_REVEALING;
            case REVEALED -> OLD_REVEALED;
            case REVEAL_BUTTON -> OLD_REVEAL_BUTTON;
            default -> throw new IllegalArgumentException("没有登记旧默认值的键：" + path);
        };
    }

    /** 新模板（供测试与文档对齐）。 */
    public static String newTemplate(String path) {
        Objects.requireNonNull(path, "path");
        return switch (path) {
            case REVEALING -> NEW_REVEALING;
            case REVEALED -> NEW_REVEALED;
            case REVEAL_BUTTON -> NEW_REVEAL_BUTTON;
            default -> throw new IllegalArgumentException("没有登记新模板的键：" + path);
        };
    }
}
