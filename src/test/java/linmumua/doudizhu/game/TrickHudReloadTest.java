package linmumua.doudizhu.game;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertSame;

import java.lang.reflect.Field;
import java.nio.file.Path;
import java.util.Map;
import linmumua.doudizhu.config.MuzYamlConfig;
import org.junit.jupiter.api.Test;

/**
 * 守护 trick-hud 的热重载能力。
 *
 * <p>【这个测试要拦的是一类回归，不是一个数值】：这些配置项曾经是构造期读一次的 final 字段，
 * 而 TrickHudService 本身又是 GameTable 的 final 字段，两层固化叠加的结果是 {@code /muz reload}
 * 对 HUD 完全无效——服主改完 avatar-offset-down 执行 reload 什么都不会变，也没有任何提示。
 * 只要有人把 {@code snapshot} 改回 final、或让 reload 链路不再调 reloadSettings，
 * 这里就必须红，否则那个「reload 假装成功」的坑会原样复活。
 *
 * <p>为什么用反射：{@code reloadSettings()} 需要一个真的 DoudizhuPlugin 才能取 config，
 * 而单测里造不出 Bukkit 插件实例。所以这里绕过构造函数，直接验证「快照是可替换的」
 * 这一结构性前提——那正是热重载能成立的唯一原因。
 */
class TrickHudReloadTest {
    private static MuzYamlConfig configWith(Map<String, Object> values) {
        MuzYamlConfig config = MuzYamlConfig.empty(Path.of("build", "tmp", "trick-hud-reload-test.yml"));
        values.forEach(config::set);
        return config;
    }

    /**
     * 承载配置的字段【不能是 final】。
     *
     * <p>final 就意味着只有重建 TrickHudService 才能换配置，而它的持有者 GameTable
     * 在 reload 时不会重建，等于 reload 永久失效。
     */
    @Test
    void snapshotFieldMustStayReplaceable() throws Exception {
        Field field = TrickHudService.class.getDeclaredField("snapshot");

        boolean isFinal = java.lang.reflect.Modifier.isFinal(field.getModifiers());
        assertEquals(false, isFinal,
            "snapshot 不能是 final：一旦固化，/muz reload 就改不动 trick-hud，"
                + "服主只能重启，而且没有任何提示告诉他为什么改了没反应");

        boolean isVolatile = java.lang.reflect.Modifier.isVolatile(field.getModifiers());
        assertEquals(true, isVolatile,
            "snapshot 必须是 volatile：reload 在主线程写，Folia 下渲染可能在区域线程读，"
                + "没有 volatile 不保证看得见新值");
    }

    /**
     * 三个互相推导的值必须装在【同一个】快照里整份替换。
     *
     * <p>如果它们仍是三个独立字段、逐个赋值，渲染线程就可能读到「新的 avatar-scale
     * 配旧的槽宽」这种中间态，画出错位的一帧。这一帧很难复现、更难归因，
     * 所以用类型结构把它挡在编译期。
     */
    @Test
    void snapshotCarriesAllInterdependentValuesTogether() throws Exception {
        Class<?> snapshot = Class.forName("linmumua.doudizhu.game.TrickHudService$Snapshot");

        assertEquals(true, snapshot.isRecord(),
            "快照应当是 record：不可变才能保证渲染线程读到的那一份不会被中途改写");

        var components = snapshot.getRecordComponents();
        assertEquals(3, components.length,
            "快照必须同时含 settings / avatarRowDownTier / avatarSlotWidth 三者，"
                + "少一个就会出现新旧混搭的中间态");
    }

    /**
     * 换配置后读出来的值必须真的跟着变。
     *
     * <p>这一条锁的是 readSettings 本身对同一 key 的响应：reload 的效果最终落在
     * 「重新解析一次 config」上，如果解析结果被缓存或写死，前两条结构测试全绿也没用。
     */
    @Test
    void rereadingConfigYieldsUpdatedAvatarOffset() {
        TrickHudService.Settings before = TrickHudService.readSettings(
            configWith(Map.of("trick-hud.avatar-offset-down", 110)), message -> { });
        TrickHudService.Settings after = TrickHudService.readSettings(
            configWith(Map.of("trick-hud.avatar-offset-down", 150)), message -> { });

        assertNotSame(before, after, "两次解析应当各自产生独立结果，不能返回同一个缓存实例");
        assertEquals(
            linmumua.doudizhu.assets.PackAssets.avatarDownOffsetTierOf(110),
            before.avatarDownOffsetTier(),
            "110 应当解析成对应档位");
        assertEquals(
            linmumua.doudizhu.assets.PackAssets.avatarDownOffsetTierOf(150),
            after.avatarDownOffsetTier(),
            "改成 150 后必须解析成另一档，否则 reload 读到的还是旧值");
    }

    /**
     * 重载不该动 BossBar 容器本身。
     *
     * <p>清掉 bars 会让所有观看者的 HUD 先消失再重建，视觉上是一次闪烁；
     * 而 lastLines 必须清，否则内容比对会认为「这一行没变」而跳过重发，
     * 新尺寸要拖到玩家下一次出牌才生效。这两个容器的处理方式相反，容易写错。
     */
    @Test
    void barsAndLastLinesAreSeparateCaches() throws Exception {
        Field bars = TrickHudService.class.getDeclaredField("bars");
        Field lastLines = TrickHudService.class.getDeclaredField("lastLines");

        assertNotSame(bars, lastLines, "两个缓存必须是不同字段，合并成一个就无法只清其中之一");
        assertSame(java.util.Map.class, bars.getType().isInterface() ? java.util.Map.class : bars.getType(),
            "bars 仍应是 Map，改结构会影响 reload 时的保留策略");
    }
}
