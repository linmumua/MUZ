package linmumua.doudizhu.model;

import java.util.Arrays;

/**
 * 牌桌内虚拟道具。索引顺序与资源包/热键栏契约固定为：鸡蛋、水桶、番茄。
 * 不携带数量，也不映射玩家真实物品栏槽位。
 */
public enum TableGadget {
    EGG(0, "egg", "鸡蛋"),
    WATER(1, "water", "水桶"),
    TOMATO(2, "tomato", "番茄");

    private final int index;
    private final String key;
    private final String displayName;

    TableGadget(int index, String key, String displayName) {
        this.index = index;
        this.key = key;
        this.displayName = displayName;
    }

    public int index() {
        return index;
    }

    public String key() {
        return key;
    }

    public String displayName() {
        return displayName;
    }

    public static TableGadget fromIndex(int index) {
        int wrapped = Math.floorMod(index, values().length);
        return Arrays.stream(values()).filter(gadget -> gadget.index == wrapped).findFirst().orElse(EGG);
    }

    public static int wrapIndex(int index) {
        return Math.floorMod(index, values().length);
    }
}
