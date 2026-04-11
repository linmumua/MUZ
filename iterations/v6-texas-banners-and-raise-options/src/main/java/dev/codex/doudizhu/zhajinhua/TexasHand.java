package dev.codex.doudizhu.zhajinhua;

import dev.codex.doudizhu.model.DoudizhuCard;
import java.util.List;

public record TexasHand(TexasHandType type, List<Integer> compareValues, List<DoudizhuCard> cards) {
    public String displayName() {
        return type.displayName();
    }
}
