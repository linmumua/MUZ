package linmumua.doudizhu.world;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.lang.reflect.Proxy;
import java.util.Set;
import org.bukkit.entity.Entity;
import org.junit.jupiter.api.Test;

/**
 * 共享保护链的 tag 判定：牌桌与麻将两个子领域的实体都算受保护。
 *
 * <p>这是「麻将实体也受破坏保护」的行为级证据。麻将 tag 一旦从
 * {@link TableEntityGeometry#PROTECTED_TAGS} 里掉出去，麻将实体就会重新变成可被玩家拆掉，
 * 而破坏入口那边不会有任何报错 —— 所以必须由测试守住，而不是靠源码扫描。
 */
class SharedProtectionTagTest {

    @Test
    void tableTaggedEntityCountsAsProtected() {
        assertTrue(TableEntityGeometry.hasProtectionTag(entityTagged(TableEntityGeometry.TABLE_PROTECTED_TAG)));
    }

    /**
     * 麻将实体与牌桌实体同属共享保护链。
     *
     * <p>失败条件：把麻将 tag 从 {@code PROTECTED_TAGS} 移除，或让
     * {@code hasProtectionTag} 只认牌桌 tag。那样麻将桌实体可以被左键/破坏掉。
     */
    @Test
    void mahjongTaggedEntityCountsAsProtected() {
        assertTrue(
            TableEntityGeometry.hasProtectionTag(entityTagged(TableEntityGeometry.MAHJONG_PROTECTED_TAG)),
            "麻将实体必须和牌桌实体一样受保护，否则玩家能把麻将桌拆掉"
        );
    }

    @Test
    void untaggedEntityIsNotProtected() {
        assertFalse(TableEntityGeometry.hasProtectionTag(entityTagged("some_other_plugin_tag")));
    }

    @Test
    void nullEntityIsNotProtected() {
        assertFalse(TableEntityGeometry.hasProtectionTag(null));
    }

    /** 两个子领域的 tag 都要在共享集合里登记：各写各的字符串会让保护在某一边漏掉。 */
    @Test
    void sharedSetRegistersBothSubdomains() {
        assertTrue(
            TableEntityGeometry.PROTECTED_TAGS.containsAll(Set.of(
                TableEntityGeometry.TABLE_PROTECTED_TAG,
                TableEntityGeometry.MAHJONG_PROTECTED_TAG
            )),
            "共享保护链的 tag 集合必须同时登记牌桌与麻将"
        );
    }

    private static Entity entityTagged(String... tags) {
        Set<String> tagSet = Set.of(tags);
        return (Entity) Proxy.newProxyInstance(
            Entity.class.getClassLoader(),
            new Class<?>[] {Entity.class},
            (proxy, method, args) -> switch (method.getName()) {
                case "getScoreboardTags" -> tagSet;
                case "toString" -> "TaggedEntity" + tagSet;
                case "hashCode" -> System.identityHashCode(proxy);
                case "equals" -> args != null && args.length == 1 && proxy == args[0];
                default -> null;
            });
    }
}
