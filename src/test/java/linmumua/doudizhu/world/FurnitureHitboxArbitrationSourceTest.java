package linmumua.doudizhu.world;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashSet;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;

/**
 * 手牌仲裁里【furniture 这个布尔的取值来源】，按 furniture.yml 声明的 hitbox 类型逐个锁死。
 *
 * <p>守的不是真值表本身 —— 那部分由 {@code HandCardArbitrationVerdictTest} 覆盖，
 * 它把三个布尔当入参逐格验证。这里守的是另一半：<b>传进去的 furniture 到底是怎么算出来的</b>。
 * 这一半此前完全没有测试，而它恰好是最容易静默失效的一环。
 *
 * <p>历史：曾经怀疑「桌子的 shulker 判定框没被 isLikelyFurnitureEntity 认成家具，
 * 于是 yieldsToBlockingEntity 第一行 {@code if (!furniture) return true} 恒真、
 * 仲裁整体失效」。按 craft-engine-bukkit 0.0.67 与 26.7.4 的字节码核对，那条路不成立：
 * ShulkerFurnitureHitbox 只有 {@code spawnPacket / despawnPacket / int[] entityIds}，
 * 是发包伪实体，服务端没有对应的 Bukkit 实体，因此 Shulker 压根到不了那条谓词。
 * 桌子那一路走 CE 自己 fire 的 FurnitureInteractEvent，传进仲裁的是家具基座 ItemDisplay。
 * 这段结论写在这里，是为了让下一个读到 furniture.yml 里 {@code type: shulker} 的人
 * 不必重新推一遍，也不要顺手把 Shulker 加进类型谓词（那会引入野生潜影贝的回归）。
 *
 * <p>所以这个文件真正防的是：<b>以后有人把 hitbox 换成别的类型，仲裁又静默失效</b>。
 * 换类型会改变「点击以什么实体身份到达仲裁」，而那件事没有任何编译期约束。
 */
class FurnitureHitboxArbitrationSourceTest {
    private static final Path MANAGER =
        Path.of("src/main/java/linmumua/doudizhu/world/PhysicalTableManager.java");
    private static final Path CE_LISTENER =
        Path.of("src/main/java/linmumua/doudizhu/listener/CraftEngineProtectionListener.java");

    /**
     * CE 的每种 hitbox 类型，到达手牌仲裁时的实体身份。
     *
     * <p>{@code bukkitEntityClass} 为 null 表示该类型是发包伪实体，服务端没有对应的
     * Bukkit 实体，原版实体事件不产生，点击只能经由 CE 自己 fire 的家具事件到达仲裁 ——
     * 那条路传的是家具基座 ItemDisplay。
     */
    private record HitboxRoute(String yamlType, Class<?> bukkitEntityClass, String note) {
    }

    private static final Set<HitboxRoute> KNOWN_ROUTES = new LinkedHashSet<>(java.util.List.of(
        new HitboxRoute(
            "shulker",
            null,
            "发包伪实体（ShulkerFurnitureHitbox 只有 spawnPacket/despawnPacket/entityIds），"
                + "服务端无 Bukkit 实体，点击只走 CE 的 FurnitureInteractEvent/FurnitureHitEvent"
        ),
        new HitboxRoute(
            "interaction",
            org.bukkit.entity.Interaction.class,
            "CE 挂真实 Interaction 实体，原版 PlayerInteractEntityEvent 也会到"
        )
    ));

    /**
     * furniture.yml 里声明的每种 hitbox type，都必须有一条已知的仲裁到达路径。
     *
     * <p>这是本文件的主断言，也是最能防住漂移的那一条。换 hitbox 类型会改变
     * 「点击以什么实体身份到达仲裁」：换成有真实 Bukkit 实体的类型，点击就走原版实体事件，
     * 那时 furniture 由 {@code isFurnitureEntityClass} 按类型算；换成发包伪实体，
     * 就只能走 CE 的家具事件、传家具基座。这两条路的 furniture 取值来源完全不同，
     * 而配置文件改一行不会有任何编译错误。
     *
     * <p>失败条件：有人在 furniture.yml 里用了一种这里没登记的 hitbox type。
     * 届时该做的不是往 KNOWN_ROUTES 里随手补一行，而是先确认那种类型到达仲裁时是什么实体，
     * 再决定 {@code isFurnitureEntityClass} 要不要认它。
     */
    @Test
    void everyDeclaredHitboxTypeHasAKnownArbitrationRoute() throws IOException {
        for (String declared : declaredHitboxTypes()) {
            HitboxRoute route = KNOWN_ROUTES.stream()
                .filter(candidate -> candidate.yamlType().equals(declared))
                .findFirst()
                .orElse(null);
            assertNotNull(route,
                "furniture.yml 用了未登记的 hitbox type「" + declared + "」："
                    + "没人确认过这种判定框上的点击以什么实体身份到达手牌仲裁，"
                    + "furniture 那个布尔可能算错、导致仲裁静默失效（点牌没反应且没有提示）");

            if (route.bukkitEntityClass() == null) {
                // 伪实体这一路：仲裁拿到的是家具基座 ItemDisplay，必须被认成家具。
                assertTrue(
                    PhysicalTableManager.isFurnitureEntityClass(org.bukkit.entity.ItemDisplay.class),
                    "hitbox type「" + declared + "」是发包伪实体（" + route.note() + "），"
                        + "点击只能带着家具基座 ItemDisplay 进仲裁；"
                        + "而 ItemDisplay 现在不算家具 → yieldsToBlockingEntity 第一行恒真、"
                        + "手牌仲裁整体失效");
            } else {
                assertTrue(
                    PhysicalTableManager.isFurnitureEntityClass(route.bukkitEntityClass()),
                    "hitbox type「" + declared + "」会产生真实 Bukkit 实体 "
                        + route.bukkitEntityClass().getSimpleName() + "（" + route.note() + "），"
                        + "但它现在不算家具 → 点击会被当成非家具让位、手牌仲裁失效");
            }
        }
    }

    /**
     * 发包伪实体那条路必须真的接线：CE 家具事件上要挂着手牌仲裁。
     *
     * <p>上一条断言的前提是「桌子的点击经由 CE 的家具事件到达仲裁」。这个前提不是自动成立的，
     * 它靠 CraftEngineProtectionListener 里那两个 handler 撑着。哪天有人把它们摘掉，
     * 桌子那一路就再没有任何事件送到仲裁面前 —— 而上一条断言照样全绿，
     * 因为 ItemDisplay 仍然算家具。这条把那个前提本身钉住。
     *
     * <p>用源码扫描而不是调用：这条链路要 Bukkit 的事件与 Player，本项目跑不起 Bukkit。
     * 写法沿用 {@code HandCardClickRoutingTest}。
     */
    @Test
    void packetOnlyHitboxesReachArbitrationThroughCraftEngineFurnitureEvents() throws IOException {
        String listener = Files.readString(CE_LISTENER, StandardCharsets.UTF_8);

        assertTrue(listener.contains("FurnitureInteractEvent"),
            "CE 家具右键事件的 handler 不见了：桌子判定框是发包伪实体、不产生原版实体事件，"
                + "摘掉这一路右键选牌会彻底没有事件送到仲裁面前");
        assertTrue(listener.contains("FurnitureHitEvent"),
            "CE 家具左键事件的 handler 不见了：同上，左键出牌会收不到任何事件");
        assertTrue(listener.contains("handleHandCardClickBlockedBy"),
            "CE 家具事件没有转交手牌仲裁：桌子上的点击不会被识别成点牌");
    }

    /**
     * 野生生物和玩家必须仍然算「非家具」。
     *
     * <p>这条守的是一个已取证的回归方向：怪物晃到手牌那条带上时，
     * 如果它被判成家具，左键攻击就会被仲裁接手、取消掉，还弹一句「请先右键选择要出的牌」。
     *
     * <p>Shulker 单独列在这里而不是随便挑个怪物：桌子的 hitbox 配的正是
     * {@code type: shulker}，所以「把 Shulker 加进家具类型谓词」是个看起来很自然的改法。
     * 它是错的 —— CE 的 shulker 判定框是发包伪实体，加了既救不了任何东西
     * （那种实体到不了这条谓词），又会顺手把野生潜影贝拖进家具，
     * 换来上面那个回归。这条断言就是拦这一手。
     */
    @Test
    void wildMobsAndPlayersAreNeverFurniture() {
        assertFalse(PhysicalTableManager.isFurnitureEntityClass(org.bukkit.entity.Shulker.class),
            "野生潜影贝被判成家具：牌桌附近左键攻击会被仲裁接手、取消掉，"
                + "还弹一句「请先右键选择要出的牌」。"
                + "注意桌子的 shulker 判定框是 CE 发包伪实体，服务端没有 Bukkit 实体，"
                + "把 Shulker 加进家具类型救不了任何东西，只会带来这个回归");
        assertFalse(PhysicalTableManager.isFurnitureEntityClass(org.bukkit.entity.Zombie.class),
            "怪物被判成家具：攻击会被判成点牌");
        assertFalse(PhysicalTableManager.isFurnitureEntityClass(org.bukkit.entity.Player.class),
            "玩家被判成家具：对玩家的交互会被判成点牌");
        assertFalse(PhysicalTableManager.isFurnitureEntityClass(null),
            "null 类型被判成家具");
    }

    /**
     * MUZ 自己生成的家具/按钮实体类型必须全部算家具。
     *
     * <p>牌桌上真正会作为 blocking 实体走到仲裁的就是这几类：家具基座与牌是 ItemDisplay，
     * 文字标签是 TextDisplay，按钮与椅子的 interaction_entity 是 Interaction。
     * 少认任何一类，对应实体上的点击都会走成「非家具 → 让位」，手牌仲裁在那个位置失效。
     */
    @Test
    void everyEntityTypeMuzSpawnsOnTheTableCountsAsFurniture() {
        for (Class<?> spawned : java.util.List.of(
            org.bukkit.entity.ItemDisplay.class,
            org.bukkit.entity.TextDisplay.class,
            org.bukkit.entity.Interaction.class
        )) {
            assertTrue(PhysicalTableManager.isFurnitureEntityClass(spawned),
                spawned.getSimpleName() + " 不算家具了：牌桌上这类实体身上的点击会被判成非家具、"
                    + "直接让位，手牌仲裁在那个位置静默失效");
        }
    }

    /**
     * 仲裁那条链上的家具判定必须走 {@code isFurnitureEntityClass}，不许另立一套。
     *
     * <p>上面几条断言全都打在这个纯谓词上。如果 {@code isLikelyFurnitureEntity} 哪天自己
     * 重新写一遍 instanceof 链、不再委托给它，那些断言就变成了对一段没人用的代码的断言 ——
     * 全绿，而生产行为可以随意漂移。
     */
    @Test
    void productionFurnitureCheckDelegatesToTheTestablePredicate() throws IOException {
        String manager = Files.readString(MANAGER, StandardCharsets.UTF_8);
        String body = manager.substring(manager.indexOf("private boolean isLikelyFurnitureEntity(Entity entity)"));
        body = body.substring(0, body.indexOf('}') + 1);

        assertTrue(body.contains("isFurnitureEntityClass"),
            "isLikelyFurnitureEntity 不再委托给 isFurnitureEntityClass："
                + "本文件所有断言都会变成对死代码的断言，生产判据可以静默漂移");
        assertEquals(1, countOccurrences(manager, "static boolean isFurnitureEntityClass("),
            "家具类型谓词出现了多份实现：仲裁走哪一份不再确定");
    }

    private static int countOccurrences(String text, String needle) {
        int count = 0;
        int index = text.indexOf(needle);
        while (index >= 0) {
            count++;
            index = text.indexOf(needle, index + needle.length());
        }
        return count;
    }

    /** furniture.yml 里出现的每种 hitbox type。 */
    private static Set<String> declaredHitboxTypes() throws IOException {
        String furniture = read("craftengine/muz/configuration/furniture.yml");
        Set<String> types = new LinkedHashSet<>();
        Matcher matcher = Pattern.compile("-\\s*type:\\s*(\\S+)").matcher(furniture);
        while (matcher.find()) {
            types.add(matcher.group(1).trim());
        }
        assertFalse(types.isEmpty(), "furniture.yml 里一个 hitbox type 都没解析出来，这条测试等于没在守");
        return types;
    }

    private static String read(String path) throws IOException {
        InputStream stream =
            FurnitureHitboxArbitrationSourceTest.class.getClassLoader().getResourceAsStream(path);
        assertNotNull(stream, "Missing resource " + path);
        try (stream) {
            return new String(stream.readAllBytes(), StandardCharsets.UTF_8);
        }
    }
}
