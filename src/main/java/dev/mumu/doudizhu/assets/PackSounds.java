package dev.mumu.doudizhu.assets;

import dev.mumu.doudizhu.model.CardPattern;
import dev.mumu.doudizhu.model.CardRank;
import dev.mumu.doudizhu.model.PatternType;
import java.util.List;
import java.util.Random;

public final class PackSounds {
    private static final String PREFIX = "muz:doudizhu.";
    private static final Random RANDOM = new Random();
    private static final List<String> LOOP_BGMS = List.of(
        PREFIX + "bgm1",
        PREFIX + "bgm2",
        PREFIX + "bgm3"
    );

    private PackSounds() {
    }

    public static String bid(int points) {
        return switch (points) {
            case 1 -> PREFIX + "bid1";
            case 2 -> PREFIX + "bid2";
            case 3 -> RANDOM.nextBoolean() ? PREFIX + "bid3" : PREFIX + "qiangdizhu";
            default -> PREFIX + "pass_jiao";
        };
    }

    public static String landlordConfirmed() {
        return PREFIX + "qiangdizhu";
    }

    public static String randomBgm() {
        return nextBgmTrack(null);
    }

    public static List<String> bgmTracks() {
        return List.of(PREFIX + "opening", PREFIX + "middle", PREFIX + "bgm1", PREFIX + "bgm2", PREFIX + "bgm3");
    }

    public static long bgmDurationTicks(String soundKey) {
        return switch (soundKey) {
            case PREFIX + "opening" -> 320L;
            case PREFIX + "middle" -> 20L * 120L;
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

    public static String openingBgm() {
        return PREFIX + "opening";
    }

    public static String loopBgm() {
        return PREFIX + "bgm1";
    }

    public static String excitedBgm() {
        return PREFIX + "middle";
    }

    public static String nextBgmTrack(String previousTrack) {
        if (LOOP_BGMS.isEmpty()) {
            return openingBgm();
        }
        List<String> candidates = LOOP_BGMS;
        if (previousTrack != null && LOOP_BGMS.size() > 1) {
            candidates = LOOP_BGMS.stream()
                .filter(track -> !track.equals(previousTrack))
                .toList();
        }
        return candidates.get(RANDOM.nextInt(candidates.size()));
    }

    public static String pressureCallout() {
        return PREFIX + (RANDOM.nextBoolean() ? "pressure1" : "pressure2");
    }

    public static String threeCardsWarning() {
        return PREFIX + (RANDOM.nextBoolean() ? "three_left_1" : "three_left_2");
    }

    public static String twoCardsWarning() {
        return PREFIX + (RANDOM.nextBoolean() ? "left1" : "left2");
    }

    public static String doubleChoice(boolean doubled, boolean landlordTurn) {
        if (!doubled) {
            return PREFIX + "double_no";
        }
        return landlordTurn ? PREFIX + "double_yes_landlord" : PREFIX + "double_yes";
    }

    public static String mingPai() {
        return PREFIX + "mingpai";
    }

    public static String superDouble() {
        return PREFIX + "super_double";
    }

    public static String play(CardPattern pattern, CardRank primaryRank) {
        return switch (pattern.type()) {
            case SINGLE -> PREFIX + singleRank(primaryRank);
            case PAIR -> PREFIX + pairRank(primaryRank);
            case STRAIGHT -> PREFIX + "straight";
            case PAIR_STRAIGHT -> PREFIX + "pair_straight";
            case TRIPLE -> PREFIX + tripleRank(primaryRank);
            case TRIPLE_WITH_SINGLE -> PREFIX + "triple_with_single";
            case TRIPLE_WITH_PAIR -> PREFIX + "triple_with_pair";
            case AIRPLANE -> PREFIX + "airplane";
            case AIRPLANE_WITH_SINGLES -> PREFIX + "airplane_with_singles";
            case AIRPLANE_WITH_PAIRS -> PREFIX + "airplane_with_pairs";
            case FOUR_WITH_TWO_SINGLES -> PREFIX + "four_with_two_singles";
            case FOUR_WITH_TWO_PAIRS -> PREFIX + "four_with_two_pairs";
            case BOMB -> PREFIX + bombRank(primaryRank);
            case JOKER_BOMB -> PREFIX + "joker_bomb";
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

    private static String tripleRank(CardRank rank) {
        return switch (rank) {
            case ACE -> "triple_a";
            case JACK -> "triple_j";
            case QUEEN -> "triple_q";
            case KING -> "triple_k";
            case TWO -> "triple_2";
            case TEN -> "triple_10";
            default -> "triple_" + rank.strength();
        };
    }

    private static String bombRank(CardRank rank) {
        return switch (rank) {
            case ACE -> "bomb_a";
            case JACK -> "bomb_j";
            case QUEEN -> "bomb_q";
            case KING -> "bomb_k";
            case TWO -> "bomb_2";
            case TEN -> "bomb_10";
            default -> "bomb_" + rank.strength();
        };
    }
}

