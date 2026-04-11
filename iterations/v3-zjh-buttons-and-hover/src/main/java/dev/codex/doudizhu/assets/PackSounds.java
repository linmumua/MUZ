package dev.codex.doudizhu.assets;

import dev.codex.doudizhu.model.CardPattern;
import dev.codex.doudizhu.model.CardRank;
import dev.codex.doudizhu.model.PatternType;
import java.util.List;
import java.util.Random;

public final class PackSounds {
    private static final String PREFIX = "muz:doudizhu.";
    private static final Random RANDOM = new Random();

    private PackSounds() {
    }

    public static String bid(int points) {
        return points <= 0 ? PREFIX + "pass_jiao" : PREFIX + "jiaodizhu";
    }

    public static String landlordConfirmed() {
        return PREFIX + "qiangdizhu";
    }

    public static String randomBgm() {
        int index = RANDOM.nextInt(3) + 1;
        return PREFIX + "bgm" + index;
    }

    public static List<String> bgmTracks() {
        return List.of(PREFIX + "bgm1", PREFIX + "bgm2", PREFIX + "bgm3");
    }

    public static long bgmDurationTicks(String soundKey) {
        return switch (soundKey) {
            case PREFIX + "bgm1" -> 760L;
            case PREFIX + "bgm2" -> 960L;
            case PREFIX + "bgm3" -> 1000L;
            default -> 860L;
        };
    }

    public static String autoPass() {
        return PREFIX + (RANDOM.nextBoolean() ? "pass1" : "pass2");
    }

    public static String win() {
        return PREFIX + "win";
    }

    public static String lose() {
        return PREFIX + "lose";
    }

    public static String play(CardPattern pattern, CardRank primaryRank) {
        return switch (pattern.type()) {
            case SINGLE -> PREFIX + singleRank(primaryRank);
            case PAIR -> PREFIX + pairRank(primaryRank);
            case STRAIGHT, PAIR_STRAIGHT -> PREFIX + straightLength(pattern.chainLength());
            case TRIPLE, TRIPLE_WITH_SINGLE, TRIPLE_WITH_PAIR -> PREFIX + "1p";
            case AIRPLANE -> PREFIX + (pattern.chainLength() >= 3 ? "3p" : "2p");
            case AIRPLANE_WITH_SINGLES -> PREFIX + "t12";
            case AIRPLANE_WITH_PAIRS -> PREFIX + "t13";
            case FOUR_WITH_TWO_SINGLES, FOUR_WITH_TWO_PAIRS, BOMB, JOKER_BOMB -> PREFIX + "v3";
        };
    }

    public static List<String> settlement(boolean landlordWin) {
        return landlordWin ? List.of(win()) : List.of(lose());
    }

    private static String singleRank(CardRank rank) {
        return switch (rank) {
            case ACE -> "a";
            case JACK -> "j";
            case QUEEN -> "q";
            case KING -> "k";
            case SMALL_JOKER -> "joker1";
            case BIG_JOKER -> "joker2";
            case TWO -> "2";
            default -> Integer.toString(rank.strength());
        };
    }

    private static String pairRank(CardRank rank) {
        return switch (rank) {
            case ACE -> "pa";
            case JACK -> "pj";
            case QUEEN -> "pq";
            case KING -> "pk";
            case TWO -> "p2";
            case TEN -> "p10";
            default -> "p" + rank.strength();
        };
    }

    private static String straightLength(int chainLength) {
        return switch (chainLength) {
            case 3 -> "t3";
            case 4 -> "t4";
            case 5 -> "t5";
            case 6 -> "t6";
            case 7 -> "t7";
            case 8 -> "t8";
            case 12 -> "t12";
            case 13 -> "t13";
            default -> "t91011";
        };
    }
}
