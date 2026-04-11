package dev.mumu.doudizhu.assets;

import dev.mumu.doudizhu.DoudizhuPlugin;
import dev.mumu.doudizhu.game.PlayerRole;
import dev.mumu.doudizhu.model.CardRank;
import dev.mumu.doudizhu.model.CardSuit;
import dev.mumu.doudizhu.model.DoudizhuCard;
import org.bukkit.NamespacedKey;

public final class PackAssets {
    private PackAssets() {
    }

    public static NamespacedKey cardModel(DoudizhuPlugin plugin, DoudizhuCard card) {
        return new NamespacedKey(plugin, "cards/" + cardAssetName(card));
    }

    public static NamespacedKey backModel(DoudizhuPlugin plugin) {
        return new NamespacedKey(plugin, "cards/card_back");
    }

    public static NamespacedKey uiModel(DoudizhuPlugin plugin, String id) {
        return new NamespacedKey(plugin, "ui/" + id);
    }

    public static NamespacedKey configuredUiModel(DoudizhuPlugin plugin, String configured, String fallbackId) {
        if (configured != null && configured.contains(":")) {
            NamespacedKey parsed = NamespacedKey.fromString(configured);
            if (parsed != null) {
                return parsed;
            }
        }
        return uiModel(plugin, fallbackId);
    }

    public static NamespacedKey furnitureModel(DoudizhuPlugin plugin, String id) {
        return new NamespacedKey(plugin, "furniture/" + id);
    }

    public static NamespacedKey roleModel(DoudizhuPlugin plugin, PlayerRole role) {
        return uiModel(plugin, role == PlayerRole.LANDLORD ? "landlord" : "farmer");
    }

    public static String cardAssetName(DoudizhuCard card) {
        if (card.rank() == CardRank.SMALL_JOKER) {
            return "small_joker";
        }
        if (card.rank() == CardRank.BIG_JOKER) {
            return "big_joker";
        }
        return suitName(card.suit()) + "_" + rankName(card.rank());
    }

    private static String suitName(CardSuit suit) {
        return switch (suit) {
            case CLUBS -> "clubs";
            case DIAMONDS -> "diamonds";
            case HEARTS -> "hearts";
            case SPADES -> "spades";
            case JOKER -> "joker";
        };
    }

    private static String rankName(CardRank rank) {
        return switch (rank) {
            case THREE -> "3";
            case FOUR -> "4";
            case FIVE -> "5";
            case SIX -> "6";
            case SEVEN -> "7";
            case EIGHT -> "8";
            case NINE -> "9";
            case TEN -> "10";
            case JACK -> "jack";
            case QUEEN -> "queen";
            case KING -> "king";
            case ACE -> "ace";
            case TWO -> "2";
            case SMALL_JOKER -> "small_joker";
            case BIG_JOKER -> "big_joker";
        };
    }
}

