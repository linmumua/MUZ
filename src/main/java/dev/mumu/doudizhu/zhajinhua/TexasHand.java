package dev.mumu.doudizhu.zhajinhua;

import dev.mumu.doudizhu.model.DoudizhuCard;
import java.util.List;

public record TexasHand(TexasHandType type, List<Integer> compareValues, List<DoudizhuCard> cards) {
    public String displayName() {
        return type.displayName();
    }
}

