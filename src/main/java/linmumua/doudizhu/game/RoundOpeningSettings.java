package linmumua.doudizhu.game;

import linmumua.doudizhu.config.MuzYamlConfig;
import java.util.Objects;

/**
 * 单局开局时间线配置快照。
 *
 * <p>快照只在开局时读取一次；正在发牌或明牌的牌局不会因为配置重载而改变节奏。
 */
public record RoundOpeningSettings(
    int batchIntervalTicks,
    int flipTicks,
    int revealTicks,
    Messages messages
) {
    public static final int DEFAULT_BATCH_INTERVAL_TICKS = 4;
    public static final int DEFAULT_FLIP_TICKS = 20;
    public static final int DEFAULT_REVEAL_TICKS = 60;
    public static final int MIN_BATCH_INTERVAL_TICKS = 3;
    public static final int MAX_BATCH_INTERVAL_TICKS = 200;
    public static final int MIN_FLIP_TICKS = 8;
    public static final int MAX_FLIP_TICKS = 200;
    public static final int MIN_REVEAL_TICKS = 1;
    public static final int MAX_REVEAL_TICKS = 1200;

    public RoundOpeningSettings {
        if (batchIntervalTicks < MIN_BATCH_INTERVAL_TICKS || batchIntervalTicks > MAX_BATCH_INTERVAL_TICKS) {
            throw new IllegalArgumentException("batchIntervalTicks 必须在 3 到 200 之间");
        }
        if (flipTicks < MIN_FLIP_TICKS || flipTicks > MAX_FLIP_TICKS || (flipTicks & 1) != 0) {
            throw new IllegalArgumentException("flipTicks 必须是 8 到 200 之间的偶数");
        }
        if (revealTicks < MIN_REVEAL_TICKS || revealTicks > MAX_REVEAL_TICKS) {
            throw new IllegalArgumentException("revealTicks 必须在 1 到 1200 之间");
        }
        messages = Objects.requireNonNull(messages, "messages");
    }

    public static RoundOpeningSettings from(MuzYamlConfig config) {
        Objects.requireNonNull(config, "config");
        int batchInterval = readTicks(config,
            "round-opening.batch-interval-ticks", DEFAULT_BATCH_INTERVAL_TICKS);
        int flip = readTicks(config, "round-opening.flip-ticks", DEFAULT_FLIP_TICKS);
        int reveal = readTicks(config, "round-opening.reveal-ticks", DEFAULT_REVEAL_TICKS);
        return new RoundOpeningSettings(
            batchInterval,
            flip,
            reveal,
            Messages.from(config)
        );
    }

    private static int readTicks(MuzYamlConfig config, String key, int fallback) {
        Object value = config.get(key);
        if (value == null) {
            return fallback;
        }
        // 时长必须为整数，不能让通用 getInt 把小数截断或溢出的大数转换成合法小值。
        if (value instanceof Number number) {
            double ticks = number.doubleValue();
            if (Double.isFinite(ticks) && ticks == Math.rint(ticks)
                && ticks >= Integer.MIN_VALUE && ticks <= Integer.MAX_VALUE) {
                return (int) ticks;
            }
        } else if (value instanceof String text) {
            try {
                return Integer.parseInt(text.trim());
            } catch (NumberFormatException exception) {
                throw new IllegalArgumentException(key + " 必须为整数 tick", exception);
            }
        }
        throw new IllegalArgumentException(key + " 必须为整数 tick");
    }

    public record Messages(
        String dealing,
        String flipping,
        String revealing,
        String revealed,
        String revealButton,
        String revealClosed,
        String alreadyRevealed,
        String botCannotReveal,
        String revealUnavailable
    ) {
        public Messages {
            dealing = nonBlank(dealing, "正在发牌，请稍候。");
            flipping = nonBlank(flipping, "手牌翻转中，请稍候。");
            revealing = nonBlank(revealing, "明牌窗口：还剩 %seconds% 秒，可自愿明牌（整局最多 ×2）。");
            revealed = nonBlank(revealed, "%player% 已明牌，本局公共倍率变为 ×2。");
            revealButton = nonBlank(revealButton, "明牌 ×2");
            revealClosed = nonBlank(revealClosed, "明牌窗口已结束，开始叫地主。");
            alreadyRevealed = nonBlank(alreadyRevealed, "你这一局已经明牌了。");
            botCannotReveal = nonBlank(botCannotReveal, "机器人默认不明牌。");
            revealUnavailable = nonBlank(revealUnavailable, "现在不能明牌。");
        }

        public static Messages from(MuzYamlConfig config) {
            return new Messages(
                config.getString("round-opening.messages.dealing", "正在发牌，请稍候。"),
                config.getString("round-opening.messages.flipping", "手牌翻转中，请稍候。"),
                config.getString("round-opening.messages.revealing", "明牌窗口：还剩 %seconds% 秒，可自愿明牌（整局最多 ×2）。"),
                config.getString("round-opening.messages.revealed", "%player% 已明牌，本局公共倍率变为 ×2。"),
                config.getString("round-opening.messages.reveal-button", "明牌 ×2"),
                config.getString("round-opening.messages.reveal-closed", "明牌窗口已结束，开始叫地主。"),
                config.getString("round-opening.messages.already-revealed", "你这一局已经明牌了。"),
                config.getString("round-opening.messages.bot-cannot-reveal", "机器人默认不明牌。"),
                config.getString("round-opening.messages.reveal-unavailable", "现在不能明牌。")
            );
        }

        private static String nonBlank(String value, String fallback) {
            return value == null || value.isBlank() ? fallback : value;
        }
    }
}
