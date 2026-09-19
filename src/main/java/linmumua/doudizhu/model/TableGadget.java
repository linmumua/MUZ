package linmumua.doudizhu.model;

import java.util.Arrays;

/**
 * 牌桌道具音效兼容标识。实际桌内选择由 TableGadgetService 保存 ItemStack 快照，
 * 不再使用本枚举映射 Hotbar 槽位；枚举仅保留给既有音效协调器的兼容契约。
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
