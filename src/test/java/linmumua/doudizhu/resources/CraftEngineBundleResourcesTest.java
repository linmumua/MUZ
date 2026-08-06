package linmumua.doudizhu.resources;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.awt.image.BufferedImage;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import javax.imageio.ImageIO;
import linmumua.doudizhu.assets.PackAssets;
import linmumua.doudizhu.game.PlayerRole;
import linmumua.doudizhu.model.CardRank;
import linmumua.doudizhu.model.CardSuit;
import linmumua.doudizhu.model.DoudizhuCard;
import org.junit.jupiter.api.Test;

class CraftEngineBundleResourcesTest {
    @Test
    void generatedCraftEngineBundleIsOnClasspath() throws IOException {
        String index = read("craftengine/muz/_bundle_index.txt");
        assertTrue(index.contains("pack.yml"));
        assertTrue(index.contains("configuration/items/doudizhu/cards.yml"));
        assertTrue(index.contains("configuration/categories.yml"));
        assertTrue(index.contains("configuration/sounds.yml"));
        assertTrue(index.contains("resourcepack/assets/muz/items/cards/clubs_3.json"));
    }

    @Test
    void generatedCraftEngineCardsConfigContainsCardItems() throws IOException {
        String cards = read("craftengine/muz/configuration/items/doudizhu/cards.yml");
        String categories = read("craftengine/muz/configuration/categories.yml");
        assertTrue(cards.contains("muz:clubs_3:"));
        assertTrue(cards.contains("model: muz:item/cards/clubs_3"));
        assertTrue(categories.contains("muz:doudizhu:"));
        assertTrue(categories.contains("muz:big_joker"));
    }

    /**
     * CE 物品名要求中文且不带颜色。
     * MiniMessage 里只允许保留 <!i>（去掉原版斜体），任何 <gold>/<red>/<white>/<#RRGGBB>
     * 之类的颜色标签都不该再出现——用户明确要求不加颜色。
     */
    /**
     * 抬升必须走 element 的 position，不能走 translation。
     *
     * 这是用户实机调试后定下的分工：
     * position 是 element 在家具内的位置，抬它不影响 hitbox 的相对基准；
     * translation 是模型自身的位移，改它会让模型与 hitbox 错位。
     * 两个模型的腿底都在 y=0 像素，所以 translation 保持 0 即可。
     */
    @Test
    void furnitureLiftUsesPositionNotTranslation() throws IOException {
        String furniture = read("craftengine/muz/configuration/furniture.yml");

        assertEquals(
            2,
            countOccurrences(furniture, "position: 0,0.5,0"),
            "桌子和椅子的 element 都要用 position: 0,0.5,0 抬升"
        );
        assertTrue(
            !furniture.contains("translation: 0,0.5,0"),
            "抬升被写进了 translation，会让模型与 hitbox 错位"
        );
        assertEquals(
            2,
            countOccurrences(furniture, "translation: 0,0,0"),
            "桌子和椅子的 translation 都必须保持 0"
        );
    }

    /**
     * hit_times 必须大于 1，否则家具一碰即毁。
     *
     * CE 的 AttackListener 字节码：
     *   338: invokevirtual FurnitureSettings.hitTimes:()I
     *   345: iconst_1
     *   346: if_icmple 535
     *   535: new FurnitureBreakEvent
     * hitTimes <= 1 时直接跳过命中累计、立刻构造摧毁事件。
     * 曾经写成 0，结果左键碰一下牌桌就整张消失。
     *
     * 这条断言按阈值判断而不是写死某个数，
     * 以后想改成"几下能拆"（如 CE 示例的 3）也不会误报，
     * 但只要有人写回 0 或 1 就会失败。
     */
    @Test
    void furnitureHitTimesExceedsInstantBreakThreshold() throws IOException {
        String furniture = read("craftengine/muz/configuration/furniture.yml");

        java.util.regex.Matcher matcher = java.util.regex.Pattern
            .compile("hit_times:\\s*(\\d+)")
            .matcher(furniture);

        int found = 0;
        while (matcher.find()) {
            found++;
            int hitTimes = Integer.parseInt(matcher.group(1));
            assertTrue(
                hitTimes > 1,
                "hit_times=" + hitTimes + " 会让家具一碰即毁（CE 在 hitTimes <= 1 时立刻摧毁）"
            );
        }
        assertEquals(2, found, "桌子和椅子各要有一处 hit_times");
    }

    /**
     * 桌子 shulker 的碰撞高必须是用户实机调定的值。
     *
     * CE 的碰撞高 =（getPhysicalPeek(peek/100) + 1.0）* scale，
     * getPhysicalPeek(x) = 0.5 - sin((0.5 + x) * PI) * 0.5
     * 该式取自 ShulkerFurnitureHitboxConfig 字节码，
     * 并用官方 bench 校验过：peek 100 得 2.000 格，其模型长正好 2.000 格。
     *
     * 桌子要求碰撞与桌面齐平（模型 1.25 格），给到 2 格会在桌面上方留一层看不见的墙。
     *
     * 椅子要求 0.8 格：peek 0 + scale 0.8 -> (0 + 1.0) * 0.8。
     * 椅子模型含靠背高 1.5625 格，但碰撞压到 0.8 格玩家才能直接走过去坐下，
     * 而不是被靠背挡在外面。
     */
    @Test
    void shulkerCollisionHeightsMatchTunedValues() throws IOException {
        String furniture = read("craftengine/muz/configuration/furniture.yml");

        assertCollisionHeight(furniture, "muz:table_large:", "muz:chair_large_model:", 1.25);
        assertCollisionHeight(furniture, "muz:chair_large:", null, 0.8);
    }

    private void assertCollisionHeight(
        String furniture,
        String startMarker,
        String endMarker,
        double expectedHeight
    ) {
        int start = furniture.indexOf(startMarker);
        assertTrue(start >= 0, "定位不到 " + startMarker);
        int end = endMarker == null ? furniture.length() : furniture.indexOf(endMarker, start);
        String section = furniture.substring(start, end);
        int hitboxStart = section.indexOf("hitboxes:");
        assertTrue(hitboxStart >= 0, startMarker + " 段里没有 hitboxes");
        String hitboxSection = section.substring(hitboxStart);

        java.util.regex.Matcher peekMatcher = java.util.regex.Pattern
            .compile("peek:\\s*(\\d+)")
            .matcher(hitboxSection);
        assertTrue(peekMatcher.find(), startMarker + " 的 hitbox 里找不到 peek");
        int peek = Integer.parseInt(peekMatcher.group(1));

        // hitbox 的 scale 是单个数值（element 的 scale 才是三元组）
        java.util.regex.Matcher scaleMatcher = java.util.regex.Pattern
            .compile("scale:\\s*([\\d.]+)\\s*\\n")
            .matcher(hitboxSection);
        assertTrue(scaleMatcher.find(), startMarker + " 的 hitbox 里找不到 scale");
        double scale = Double.parseDouble(scaleMatcher.group(1));

        double physicalPeek = 0.5 - Math.sin((0.5 + peek / 100.0) * Math.PI) * 0.5;
        double collisionHeight = (physicalPeek + 1.0) * scale;
        double delta = Math.abs(collisionHeight - expectedHeight);
        assertTrue(
            delta <= 0.05,
            String.format(
                "%s peek=%d scale=%.2f 算出碰撞高 %.4f 格，期望 %.4f 格，差 %.4f 超过 0.05",
                startMarker, peek, scale, collisionHeight, expectedHeight, delta
            )
        );
    }

    /**
     * 家具模型的腿底必须在 y=0 像素，这是 translation 可以为 0 的前提。
     *
     * 如果有人改模型把腿底挪离 0，贴地假设就不再成立、需要同步调整 translation。
     * 这条断言把这个前提固定下来，避免模型和配置各改一半。
     */
    @Test
    void furnitureModelsHaveFeetAtOrigin() throws IOException {
        for (String model : new String[] {"table_large", "chair_large"}) {
            String json = read("craftengine/muz/resourcepack/assets/muz/models/item/furniture/" + model + ".json");
            assertTrue(
                json.contains("\"from\": [") || json.contains("\"from\":["),
                model + " 模型缺少 elements 的 from 字段"
            );
            // 至少要有一个元素的 from.y 是 0，即腿底贴在模型原点
            assertTrue(
                json.matches("(?s).*\"from\":\\s*\\[\\s*-?[\\d.]+,\\s*0(\\.0)?,.*"),
                model + " 模型的腿底不在 y=0 像素，translation: 0,0,0 的贴地假设不再成立"
            );
        }
    }

    /**
     * 桌子的 shulker 碰撞箱必须张开。
     *
     * shulker 的碰撞体积由 peek 决定：peek: 0 是完全收起、碰撞盒接近于零，
     * 表现就是"配置里有 hitbox 但玩家照样穿过去"。
     * CE 自带示例的 shulker 用的是 peek 100 / 50 / 28，没有一个用 0。
     */
    @Test
    void tableShulkerHitboxesArePeekedOpen() throws IOException {
        String furniture = read("craftengine/muz/configuration/furniture.yml");
        int tableStart = furniture.indexOf("muz:table_large:");
        int chairStart = furniture.indexOf("muz:chair_large_model:");
        assertTrue(tableStart >= 0 && chairStart > tableStart, "furniture.yml 结构变了，定位不到桌子段");
        String tableSection = furniture.substring(tableStart, chairStart);

        assertEquals(
            4,
            countOccurrences(tableSection, "peek: 33"),
            "桌子四个角的 shulker 必须都是 peek: 33，碰撞高才与 1.25 格的桌面齐平"
        );
        assertTrue(
            !tableSection.contains("peek: 100"),
            "桌子有 peek: 100 的 shulker，碰撞会高到 2 格、桌面上方多出一层看不见的墙"
        );
    }

    /**
     * CE 家具字段统一用下划线，与官方示例一致。
     * CE 两种写法都能解析，但混用会让人误判问题出在命名上，统一成官方写法。
     */
    @Test
    void furnitureConfigUsesUnderscoreFieldNames() throws IOException {
        String furniture = read("craftengine/muz/configuration/furniture.yml");

        for (String underscored : new String[] {
            "item_name",
            "hit_times",
            "display_transform",
            "blocks_building",
            "shadow_radius",
            "shadow_strength"
        }) {
            assertTrue(furniture.contains(underscored), "缺少下划线字段 " + underscored);
        }
        for (String hyphenated : new String[] {
            "item-name",
            "hit-times",
            "display-transform",
            "blocks-building",
            "shadow-radius",
            "shadow-strength"
        }) {
            assertTrue(!furniture.contains(hyphenated), "仍有连字符字段 " + hyphenated + "，应统一为下划线");
        }
    }

    @Test
    void craftEngineItemNamesAreChineseWithoutColorTags() throws IOException {
        String cards = read("craftengine/muz/configuration/items/doudizhu/cards.yml");
        String furniture = read("craftengine/muz/configuration/furniture.yml");
        String categories = read("craftengine/muz/configuration/categories.yml");

        assertTrue(cards.contains("item_name: <!i>大王"), "大王没有用中文名");
        assertTrue(cards.contains("item_name: <!i>梅花3"), "梅花3 没有用中文名");
        assertTrue(cards.contains("item_name: <!i>黑桃J"), "黑桃J 没有用中文名");
        assertTrue(furniture.contains("item_name: <!i>斗地主桌子"), "桌子没有用中文名");
        assertTrue(furniture.contains("item_name: <!i>斗地主椅子"), "椅子没有用中文名");
        assertTrue(categories.contains("name: <!i>斗地主"), "分类没有用中文名");

        for (String colorTag : new String[] {"<gold>", "<red>", "<white>", "<gray>", "<yellow>", "<#"}) {
            assertTrue(!cards.contains(colorTag), "卡牌名仍带颜色标签 " + colorTag);
            assertTrue(!furniture.contains(colorTag), "家具名仍带颜色标签 " + colorTag);
            assertTrue(!categories.contains(colorTag), "分类名仍带颜色标签 " + colorTag);
        }
        // 旧的英文名不该再出现
        assertTrue(!furniture.contains("Dou Dizhu Table"), "家具仍是旧英文名");
        assertTrue(!furniture.contains("Dou Dizhu Seat"), "家具仍是旧英文名");
    }

    /**
     * 家具模型必须自带纹理引用且能在包里找到对应 PNG，否则客户端显示紫黑格。
     */
    @Test
    void furnitureModelTextureReferencesResolveInsideBundle() throws IOException {
        String index = read("craftengine/muz/_bundle_index.txt");
        String table = read("craftengine/muz/resourcepack/assets/muz/models/item/furniture/table_large.json");
        String chair = read("craftengine/muz/resourcepack/assets/muz/models/item/furniture/chair_large.json");

        // 模型里写的是 muz:item/furniture/xxx，对应包内 textures/item/furniture/xxx.png
        for (String texture : new String[] {"table_wood", "table_wood_leg", "table_felt"}) {
            assertTrue(table.contains("muz:item/furniture/" + texture), "桌子模型没引用 " + texture);
            assertTrue(
                index.contains("resourcepack/assets/muz/textures/item/furniture/" + texture + ".png"),
                "桌子纹理没进包：" + texture
            );
        }
        for (String texture : new String[] {"chair_wood", "chair_seat"}) {
            assertTrue(chair.contains("muz:item/furniture/" + texture), "椅子模型没引用 " + texture);
            assertTrue(
                index.contains("resourcepack/assets/muz/textures/item/furniture/" + texture + ".png"),
                "椅子纹理没进包：" + texture
            );
        }
    }

    /**
     * 家具的完整解析链必须闭合，这是紫黑格排查的核心结论。
     *
     * 客户端解析顺序是三跳：
     *   item_model 组件 muz:furniture/<id>
     *     -> items/furniture/<id>.json          （item definition）
     *     -> models/item/furniture/<id>.json    （模型本体）
     *     -> textures/item/furniture/*.png      （纹理）
     * 这条链在本地 CraftEngine 26.8-SNAPSHOT 服务端上逐跳核对通过。
     * 注意 item definition 必须放在 items/furniture/ 这个【按模型路径】的位置，
     * 不是 items/<物品id>.json —— CE 实际导出的就是前者，改成后者反而会断链。
     */
    @Test
    void furnitureItemDefinitionChainIsClosed() throws IOException {
        for (String id : new String[] {"table_large", "chair_large"}) {
            String itemDefinition =
                read("craftengine/muz/resourcepack/assets/muz/items/furniture/" + id + ".json");

            // 第一跳落点存在，且指向第二跳
            assertTrue(
                itemDefinition.contains("\"muz:item/furniture/" + id + "\""),
                id + " 的 item definition 没指向模型 muz:item/furniture/" + id
            );
            assertTrue(
                itemDefinition.contains("minecraft:model"),
                id + " 的 item definition 不是 minecraft:model 类型"
            );

            // 第二跳落点存在
            String model = read("craftengine/muz/resourcepack/assets/muz/models/item/furniture/" + id + ".json");
            assertTrue(model.contains("\"elements\""), id + " 模型没有 elements，客户端渲染不出实体");
            assertTrue(model.contains("\"textures\""), id + " 模型没有 textures 段");
        }
    }

    /**
     * 桌子模型必须保持 2.5 格跨度。
     * 用户明确要求按 JSON 原型的 2.5x2.5 摆放，缩放靠 furniture.yml 的 scale: 1,1,1 保持原样，
     * 一旦有人改小模型或加缩放，这里会失败。
     */
    @Test
    void tableModelKeepsItsAuthoredFootprint() throws IOException {
        String furniture = read("craftengine/muz/configuration/furniture.yml");

        assertTrue(furniture.contains("scale: 1,1,1"), "家具 element 没有显式 scale，尺寸会依赖隐含缺省值");
        // 40 像素 = 2.5 格：模型最外沿是 -12 与 28
        String table = read("craftengine/muz/resourcepack/assets/muz/models/item/furniture/table_large.json");
        assertTrue(table.contains("-12"), "桌子模型最小边界不再是 -12，跨度可能被改小");
        assertTrue(table.contains("28"), "桌子模型最大边界不再是 28，跨度可能被改小");
    }

    /**
     * 按钮图标已改成纯文字加判定框，那 14 个 muz:ui_* 是死物品。
     * 这里锁住它们不再进包：物品配置、物品定义、模型、纹理都不该出现。
     */
    @Test
    void buttonIconItemsAreNotBundled() throws IOException {
        String index = read("craftengine/muz/_bundle_index.txt");
        String categories = read("craftengine/muz/configuration/categories.yml");

        assertTrue(
            !index.contains("configuration/items/doudizhu/ui.yml"),
            "按钮物品配置仍在包里"
        );
        assertTrue(!categories.contains("muz:ui_"), "分类列表里仍挂着按钮物品");
        assertTrue(
            !index.contains("resourcepack/assets/muz/models/item/ui/"),
            "按钮模型仍在包里"
        );
        assertTrue(
            !index.contains("resourcepack/assets/muz/textures/item/ui/"),
            "按钮纹理仍在包里"
        );
    }

    @Test
    void generatedCardModelUsesItsOwnDoubleSidedTextureAtlas() throws IOException {
        String cardModel = read("craftengine/muz/resourcepack/assets/muz/models/item/cards/big_joker.json");
        assertTrue(cardModel.contains("\"textures\""));
        assertTrue(cardModel.contains("\"elements\""));
        assertTrue(cardModel.contains("\"0\": \"muz:item/cards/big_joker\""));
        assertTrue(cardModel.contains("\"east\": {\"uv\": [0, 2.53165, 7.08228, 16]"));
        assertTrue(cardModel.contains("\"west\": {\"uv\": [6.88608, 2.53165, 13.96835, 16]"));
    }

    @Test
    void generatedResourcePackContainsSoundRegistry() throws IOException {
        String sounds = read("craftengine/muz/resourcepack/assets/muz/sounds.json");
        assertTrue(sounds.contains("\"doudizhu.pass1\""));
        assertTrue(sounds.contains("\"doudizhu/voice/v1\""));
    }

    @Test
    void generatedResourcePackMetadataContainsMumuCredits() throws IOException {
        String packMeta = read("craftengine/muz/resourcepack/pack.mcmeta");
        assertTrue(packMeta.contains("MUMU"));
        assertTrue(packMeta.contains("linmumua"));
        assertTrue(packMeta.contains("356013496"));
    }

    @Test
    void generatedPluginDescriptorUsesSelectedPaperApiVersion() throws IOException {
        String expectedPluginVersion = System.getProperty("muz.expectedPluginVersion");
        String expectedApiVersion = System.getProperty("muz.expectedApiVersion");
        assertNotNull(expectedPluginVersion, "Missing selected plugin version");
        assertNotNull(expectedApiVersion, "Missing selected Paper API version");

        String pluginDescriptor = read("paper-plugin.yml");
        assertTrue(pluginDescriptor.contains("version: " + expectedPluginVersion));
        assertTrue(pluginDescriptor.contains("api-version: \"" + expectedApiVersion + "\""));
    }

    /**
     * 桌椅模型改成了源包里的 Blockbench 导出件，不再由构建脚本内联生成。
     * 这里用只有新模型才有的特征锁住替换结果：新桌子有 26 个 element 并引用自带纹理，
     * 旧的内联模型只有 5 个 element 且用的是原版 minecraft:block/... 纹理。
     */
    @Test
    void furnitureModelsComeFromTheNewBlockbenchExports() throws IOException {
        String table = read("craftengine/muz/resourcepack/assets/muz/models/item/furniture/table_large.json");
        String chair = read("craftengine/muz/resourcepack/assets/muz/models/item/furniture/chair_large.json");

        assertTrue(table.contains("muz:item/furniture/table_felt"), "桌子没用上新的绒面纹理");
        assertTrue(table.contains("muz:item/furniture/table_wood_leg"), "桌子没用上新的桌腿纹理");
        assertTrue(chair.contains("muz:item/furniture/chair_seat"), "椅子没用上新的坐垫纹理");
        assertTrue(chair.contains("muz:item/furniture/chair_wood"), "椅子没用上新的木纹纹理");
        assertTrue(
            !table.contains("minecraft:block/dark_oak_planks"),
            "桌子仍是旧的内联模型：还在引用原版 dark_oak_planks"
        );
        assertTrue(
            !chair.contains("minecraft:block/red_wool"),
            "椅子仍是旧的内联模型：还在引用原版 red_wool"
        );
    }

    /** 新模型自带的纹理必须一起进包，否则桌椅在客户端会显示成紫黑格。 */
    @Test
    void furnitureTexturesAreBundled() throws IOException {
        String index = read("craftengine/muz/_bundle_index.txt");

        for (String texture : new String[] {
            "table_wood.png",
            "table_wood_leg.png",
            "table_felt.png",
            "chair_wood.png",
            "chair_seat.png"
        }) {
            assertTrue(
                index.contains("resourcepack/assets/muz/textures/item/furniture/" + texture),
                "家具纹理没进包：" + texture
            );
        }
    }

    /**
     * 桌子必须带 shulker 碰撞箱。
     * interaction 类型只能点击和阻止放方块，玩家会直接穿过去；
     * 只有 shulker 才提供实体物理碰撞。改造前桌子完全没有 hitboxes。
     */
    @Test
    void tableFurnitureHasShulkerCollision() throws IOException {
        String furniture = read("craftengine/muz/configuration/furniture.yml");
        int tableStart = furniture.indexOf("muz:table_large:");
        int chairStart = furniture.indexOf("muz:chair_large_model:");
        assertTrue(tableStart >= 0 && chairStart > tableStart, "furniture.yml 结构变了，定位不到桌子段");
        String tableSection = furniture.substring(tableStart, chairStart);

        assertTrue(tableSection.contains("hitboxes:"), "桌子没有任何 hitboxes");
        assertTrue(tableSection.contains("type: shulker"), "桌子的碰撞箱不是 shulker，玩家会穿过去");
        assertTrue(tableSection.contains("blocks_building: true"), "桌子没有阻止在其中放方块");
        assertEquals(
            4,
            countOccurrences(tableSection, "type: shulker"),
            "桌子四个角各要一个 shulker，对应四条桌腿"
        );
    }

    /**
     * 桌子碰撞是四个角，且偏移必须恰好是 ±0.75。
     *
     * shulker 碰撞体 1x1，放在 ±0.75 时两侧内沿间只剩 0.5 格缝隙，
     * 小于玩家碰撞箱宽 0.6 格，所以玩家挤不进桌子中间。
     * 一旦有人把偏移挪到 ±0.9（缝隙 0.8）或 ±1.0（缝隙 1.0），玩家就能直接穿过去。
     * 两个轴都必须铺，只沿单轴排开时另一方向等于没有碰撞。
     */
    @Test
    void tableCollisionSitsAtFourCornersWithNoWalkableGap() throws IOException {
        String furniture = read("craftengine/muz/configuration/furniture.yml");
        int tableStart = furniture.indexOf("muz:table_large:");
        int chairStart = furniture.indexOf("muz:chair_large_model:");
        String tableSection = furniture.substring(tableStart, chairStart);

        for (String offsetZ : new String[] {"-0.75", "0.75"}) {
            for (String offsetX : new String[] {"-0.75", "0.75"}) {
                assertTrue(
                    tableSection.contains("position: " + offsetX + ",0," + offsetZ),
                    "桌子碰撞缺少角点 " + offsetX + ",0," + offsetZ + "，该方向能穿过去"
                );
            }
        }
        // 缝隙宽度 = 2*(偏移-0.5)，必须小于玩家宽 0.6，即偏移必须小于 0.8
        double offset = 0.75;
        double gap = 2 * (offset - 0.5);
        assertTrue(gap < 0.6, "四角偏移 " + offset + " 会留出 " + gap + " 格缝隙，玩家能钻过去");
    }

    /**
     * 椅子的 hitbox 必须是 shulker，而且必须保留 seats。
     *
     * 为什么必须是 shulker（这是用户明确要求的行为，不是风格偏好）：
     * 没人坐的时候玩家要能站在椅子上，站得上去需要真实的物理碰撞体。
     * 而 CE 里只有 shulker 会创建 BukkitCollider——字节码核对结果是
     * ShulkerFurnitureHitboxConfig 里 BukkitCollider 出现 2 次，
     * InteractionFurnitureHitboxConfig 里 0 次。
     * 换成 interaction 玩家会直接穿过椅子，站不上来。
     *
     * 曾经错误地把这里改成 interaction，动机是"消除入座后卡人"。
     * 那个判断是错的：卡人的真凶不是碰撞体，而是插件自己在入座后调用
     * viewer.hideEntity 与 CE 的 hideHitboxes 把判定框藏了起来，
     * 两者都只让客户端看不见、不销毁服务端 Collider，于是客户端以为能走、
     * 服务端判定被挡 = 幽灵碰撞。那段隐藏逻辑已删除，
     * 见 OccupiedChairHitboxVisibilityTest。
     */
    @Test
    void chairHitboxMustBeShulkerSoPlayersCanStandOnIt() throws IOException {
        String furniture = read("craftengine/muz/configuration/furniture.yml");
        int chairStart = furniture.indexOf("muz:chair_large:");
        assertTrue(chairStart >= 0, "定位不到椅子段");
        String chairSection = furniture.substring(chairStart);
        int hitboxStart = chairSection.indexOf("hitboxes:");
        assertTrue(hitboxStart >= 0, "椅子没有 hitboxes");
        String chairHitboxes = chairSection.substring(hitboxStart);

        assertTrue(
            chairHitboxes.contains("type: shulker"),
            "椅子 hitbox 不是 shulker：只有 shulker 会创建 BukkitCollider，"
                + "换成 interaction 玩家就站不到椅子上了"
        );
        assertTrue(
            !chairHitboxes.contains("type: interaction"),
            "椅子被换成了 interaction，没有物理碰撞体，玩家会直接穿过去"
        );

        // interaction_entity 让 CE 在 shulker 之外再挂一个交互实体，保证点击精度
        assertTrue(
            chairHitboxes.contains("interaction_entity: true"),
            "椅子丢了 interaction_entity，点击精度会下降"
        );

        // 没有 seats 就只是个碰撞体，玩家点了也坐不下去
        assertTrue(chairHitboxes.contains("seats:"), "椅子丢了 seats，玩家坐不下去");
        assertTrue(
            chairHitboxes.contains("- 0,0.1,0 180"),
            "座位挂点被改了，入座朝向与相对位置是实机调定值"
        );
        assertTrue(chairHitboxes.contains("blocks_building: true"), "椅子没有阻止在其中放方块");
    }

    /**
     * 桌子的 4 个 shulker 必须保持原样。
     *
     * 桌子要的是碰撞与桌面齐平：peek: 33 让碰撞高对上 1.25 格的桌面，
     * 玩家挤不进桌腿之间、也没法站到桌面上放方块。
     * 桌子没有入座这回事，所以不该有 seats。
     */
    @Test
    void tableKeepsItsFourShulkerHitboxes() throws IOException {
        String furniture = read("craftengine/muz/configuration/furniture.yml");
        int tableStart = furniture.indexOf("muz:table_large:");
        int chairModelStart = furniture.indexOf("muz:chair_large_model:");
        assertTrue(tableStart >= 0 && chairModelStart > tableStart, "定位不到桌子段");
        String tableSection = furniture.substring(tableStart, chairModelStart);

        assertEquals(4, countOccurrences(tableSection, "type: shulker"), "桌子的四个 shulker 少了");
        assertEquals(4, countOccurrences(tableSection, "peek: 33"), "桌子的 peek 被改了，碰撞不再与桌面齐平");
        assertTrue(
            !tableSection.contains("type: interaction"),
            "桌子被换成了 interaction，玩家会直接穿过桌子"
        );
        assertTrue(!tableSection.contains("seats:"), "桌子不该有座位");
    }

    /**
     * 机器人头像图标的字形声明必须与插件用的码位完全一致。
     *
     * 这一项容易悄悄坏掉：图标是位图字体字形，插件运行时靠拼一个字符来显示它。
     * 如果 images.yml 的 char 与 PackAssets.BOT_AVATAR_CHAR 不同，
     * 客户端找不到字形，桌边机器人名字前面会出现一个豆腐块，
     * 而且服务端不会报任何错——只有肉眼进游戏才能发现。
     *
     * font 必须是 PackAssets.BOT_AVATAR_FONT：图标挂在这张独立码位表上，
     * 与牌面/头像互不干扰。插件侧靠 TrickHudService 包 {@code <font:...>} 标签，
     * 两边写的字体名不一致就会变豆腐块。
     */
    @Test
    void botAvatarImageDeclaresTheSameCharThePluginRenders() throws IOException {
        String images = read("craftengine/muz/configuration/images.yml");

        assertTrue(images.contains("muz:bot_avatar:"), "images.yml 里缺少机器人头像图标声明");
        assertTrue(
            images.contains("file: muz:font/bot_avatar.png"),
            "图标贴图路径变了，字形会渲染成空白"
        );
        assertTrue(
            images.contains("font: " + PackAssets.BOT_AVATAR_FONT),
            "图标的字体名必须与 PackAssets.BOT_AVATAR_FONT 一致，否则插件包的标签对不上"
        );
        assertFalse(
            images.contains("font: minecraft:default"),
            "任何字形都不能再挂 minecraft:default —— 三家挤同一张码位表就是 CE 报"
                + "一千多条「字符已被占用」的原因"
        );
        assertEquals(
            "\\u" + String.format("%04x", (int) PackAssets.BOT_AVATAR_CHAR.charAt(0)),
            extractValue(images, "char: "),
            "images.yml 的 char 与 PackAssets.BOT_AVATAR_CHAR 不一致，桌边会显示豆腐块"
        );

        String index = read("craftengine/muz/_bundle_index.txt");
        assertTrue(
            index.contains("configuration/images.yml"),
            "images.yml 没进资源包，CE 不会注册这个字形"
        );
        assertTrue(
            index.contains("resourcepack/assets/muz/textures/font/bot_avatar.png"),
            "图标贴图没进资源包，字形会渲染成空白"
        );
    }

    /**
     * 描边版机器人头像的两个字形也必须码位一致、贴图进包。
     *
     * 描边只能画在贴图里（原生头像渲染器不开放描边参数），所以地主金边、
     * 农民黑边各是一个独立字形。这两个码位同样是"错了不报错、只在游戏里
     * 显示成豆腐块"的类型，必须和 PackAssets 的常量对死。
     */
    @Test
    void outlinedBotAvatarGlyphsMatchThePluginCodepoints() throws IOException {
        String images = read("craftengine/muz/configuration/images.yml");
        String index = read("craftengine/muz/_bundle_index.txt");

        record Glyph(String id, String character, String texture) {
        }
        List<Glyph> glyphs = List.of(
            new Glyph("bot_avatar_landlord", PackAssets.BOT_AVATAR_LANDLORD_CHAR, "bot_avatar_landlord.png"),
            new Glyph("bot_avatar_farmer", PackAssets.BOT_AVATAR_FARMER_CHAR, "bot_avatar_farmer.png")
        );

        for (Glyph glyph : glyphs) {
            assertTrue(
                images.contains("muz:" + glyph.id() + ":"),
                "images.yml 里缺少描边图标声明：" + glyph.id()
            );
            assertTrue(
                images.contains("file: muz:font/" + glyph.texture()),
                "描边图标贴图路径不对，字形会渲染成空白：" + glyph.id()
            );
            String expected = "\\u" + String.format("%04x", (int) glyph.character().charAt(0));
            assertTrue(
                images.contains("char: " + expected),
                "images.yml 里找不到 " + glyph.id() + " 的码位 " + expected + "，游戏里会显示豆腐块"
            );
            assertTrue(
                index.contains("resourcepack/assets/muz/textures/font/" + glyph.texture()),
                "描边图标贴图没进资源包：" + glyph.texture()
            );
        }
    }

    /**
     * 三个机器人头像码位必须互不相同。
     *
     * 复制粘贴改码位时最容易犯的错：两个字形指到同一个码位，
     * 结果地主和农民显示成同一张图，而所有测试仍然是绿的。
     */
    @Test
    void theThreeBotAvatarCodepointsAreDistinct() {
        Set<String> codepoints = new HashSet<>(List.of(
            PackAssets.BOT_AVATAR_CHAR,
            PackAssets.BOT_AVATAR_LANDLORD_CHAR,
            PackAssets.BOT_AVATAR_FARMER_CHAR
        ));

        assertEquals(3, codepoints.size(), "机器人头像码位有重复，两种角色会显示成同一张图");
    }

    private static String extractValue(String text, String keyPrefix) {
        int start = text.indexOf(keyPrefix);
        assertTrue(start >= 0, "找不到键 " + keyPrefix);
        int valueStart = start + keyPrefix.length();
        int lineEnd = text.indexOf('\n', valueStart);
        return (lineEnd < 0 ? text.substring(valueStart) : text.substring(valueStart, lineEnd)).trim();
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

    /**
     * 牌面字形的码位必须与资源包里生成的完全对齐（每个缩放档 x 向下偏移档都要对齐）。
     *
     * <p>出牌 HUD 要把头像和牌排进同一行文本，牌只能用字形拼。码位是「构建期扫贴图
     * 目录后按文件名字母序编号」，插件侧 PackAssets 自己枚举同一套名字复算一遍。
     * 两边独立实现就可能错位，而错位的后果不是显示成豆腐块（那一眼能看出来），
     * 而是【把 3 显示成 4】这种静默串牌，靠肉眼验收极难发现，所以必须逐档逐张比对。
     *
     * <p>扩成多档之后错位的花样更多：档序号公式（缩放档 * 偏移档数 + 偏移档）算歪一步，
     * 就会整档串成隔壁缩放档的牌 —— 尺寸差 5 像素，肉眼几乎看不出来，但牌面是错的。
     */
    @Test
    void cardGlyphCodepointsMatchGeneratedImages() throws IOException {
        Map<String, Map<String, String>> entries = glyphEntries();

        // images.yml 是构建期照着真实贴图目录生成的，这里拿它当 ground truth。
        Set<String> generatedAssetNames = new HashSet<>();
        for (String name : entries.keySet()) {
            if (name.startsWith("card_")) {
                generatedAssetNames.add(name);
            }
        }
        int heightTiers = PackAssets.cardGlyphHeightTierCount();
        int downTiers = PackAssets.cardGlyphDownOffsetTierCount();
        assertEquals(
            55 * heightTiers * downTiers,
            generatedAssetNames.size(),
            "牌面字形应当是「54 张牌加一张牌背」乘以每个缩放档与向下偏移档的组合"
        );

        // 插件侧枚举出的名字集合必须和 ground truth 一模一样。
        // 只要少一个名字，它后面每张牌的下标都会整体前移一位，全盘串牌。
        Set<String> pluginAssetNames = new HashSet<>();
        for (int heightTier = 0; heightTier < heightTiers; heightTier++) {
            for (int downTier = 0; downTier < downTiers; downTier++) {
                for (String cardName : allCardAssetNames()) {
                    pluginAssetNames.add(PackAssets.cardGlyphAssetName(cardName, heightTier, downTier));
                }
            }
        }
        assertEquals(generatedAssetNames, pluginAssetNames, "插件侧枚举的牌名与资源包里的牌面字形对不上");

        for (int heightTier = 0; heightTier < heightTiers; heightTier++) {
            for (int downTier = 0; downTier < downTiers; downTier++) {
                for (DoudizhuCard card : fullDeck()) {
                    String entry = PackAssets.cardGlyphAssetName(
                        PackAssets.cardAssetName(card), heightTier, downTier);
                    String expected = String.format(
                        "\\u%04x", PackAssets.cardGlyphChar(card, heightTier, downTier).codePointAt(0));
                    assertEquals(
                        expected,
                        entries.get(entry).get("char"),
                        "牌 " + entry + " 的字形码位和资源包不一致，运行时会显示成别的牌"
                    );

                    // 字形必须挂在牌面自己的字体上。挂回 minecraft:default 会和头像
                    // 抢同一张码位表，扩档时互相盖掉（CE 会报「字符已被另一张图片占用」）。
                    assertEquals(
                        PackAssets.CARD_GLYPH_FONT,
                        entries.get(entry).get("font"),
                        "牌面字形的字体名必须与 PackAssets.CARD_GLYPH_FONT 一致"
                    );
                }
            }
        }
    }

    /**
     * 【码位两侧同源】偏移档数一变，所有 heightTier &gt;= 1 的牌面码位都会整体平移。
     *
     * <p>码位公式是 {@code 起点 + (缩放档 * 偏移档数 + 偏移档) * 55 + 牌下标}，那个
     * 「偏移档数」是乘数：档数从 15 加到 16，缩放档 1 的第一张牌就从
     * {@code 起点 + 15*55} 挪到 {@code 起点 + 16*55}。插件侧从
     * {@code PackAssets.cardGlyphDownOffsetTierCount()} 取，构建侧从
     * {@code cardGlyphDownOffsetTiers.size} 取 —— 两侧只要有一处写死成字面量，
     * 玩家用旧缓存资源包就会看到【错误的牌面】（不是豆腐块，是静默串牌）。
     *
     * <p>{@link #cardGlyphCodepointsMatchGeneratedImages} 已经逐档逐张比对过码位，
     * 这条补的是它验不到的一件事：档数本身。那条测试两侧都用同一个
     * {@code cardGlyphDownOffsetTierCount()} 循环，如果构建侧少生成一整档，
     * 它会在「条目缺失」上失败、但报出的原因是「某张牌对不上」，指向完全错的地方。
     * 这里直接按 images.yml 里实际出现的 {@code _d<偏移>} 后缀反推构建侧用了哪些档，
     * 与插件侧的档位表逐项比对，失败信息直接指向档位表不一致。
     */
    @Test
    void downOffsetTierListIsIdenticalOnBothSides() throws IOException {
        Set<Integer> generated = new java.util.TreeSet<>();
        Pattern suffix = Pattern.compile("^card_.+_h\\d+_d(-?\\d+)$");
        for (String name : glyphEntries().keySet()) {
            Matcher matcher = suffix.matcher(name);
            if (matcher.matches()) {
                generated.add(Integer.parseInt(matcher.group(1)));
            }
        }

        Set<Integer> plugin = new java.util.TreeSet<>();
        for (int tier = 0; tier < PackAssets.cardGlyphDownOffsetTierCount(); tier++) {
            plugin.add(PackAssets.cardGlyphDownOffsetAt(tier));
        }

        assertEquals(
            plugin,
            generated,
            "插件侧与构建侧的向下偏移档位表不一致。这不是「少个字形」那种小事：档数是码位公式里的"
                + "乘数，差一档会让所有缩放档 >=1 的牌面码位整体平移，玩家用旧缓存资源包会看到错误的牌面。"
                + "两侧必须都从档位表推导（PackAssets.CARD_GLYPH_DOWN_OFFSET_TIERS 与"
                + " build.gradle.kts 的 cardGlyphDownOffsetTiers），不许任何一侧写死档数。"
        );
    }

    /**
     * 【码位两侧同源·头像表】头像档位表一变，所有头像与 bot 图标的码位都会整体平移。
     *
     * <p>这是 {@link #downOffsetTierListIsIdenticalOnBothSides} 的头像侧对应物。拆表之后
     * 头像与 bot 不再跟着牌那张表走，它们的码位公式用的是
     * {@code PackAssets.avatarDownOffsetTierCount()}，构建侧用 {@code avatarDownOffsetTiers.size} ——
     * 那条牌表测试对这张表完全不设防，少了这条，头像表就成了【没人守的一侧】。
     *
     * <p>失败的后果和牌表那条一样严重但表现不同：头像码位是
     * {@code 起点 + 偏移档 * 70 + (倍数, 行)}，档数不是乘数、但档【位置】是。构建侧多生成或少生成
     * 一档，插件侧发出的码位就会落到别的档的声明上 —— 表现是头像整体错位到另一个垂直位置
     * （不是豆腐块，是静默错位），玩家用旧缓存资源包就会看到。
     *
     * <p>这里按 images.yml 里实际出现的 {@code avatar_px_<倍数>_<行>_d<偏移>} 后缀反推构建侧
     * 用了哪些档，与插件侧的头像档位表逐项比对，不做任何估算。
     */
    @Test
    void avatarDownOffsetTierListIsIdenticalOnBothSides() throws IOException {
        Set<Integer> generated = new java.util.TreeSet<>();
        Pattern suffix = Pattern.compile("^avatar_px_\\d+_\\d+_d(-?\\d+)$");
        for (String name : glyphEntries().keySet()) {
            Matcher matcher = suffix.matcher(name);
            if (matcher.matches()) {
                generated.add(Integer.parseInt(matcher.group(1)));
            }
        }

        Set<Integer> plugin = new java.util.TreeSet<>();
        for (int tier = 0; tier < PackAssets.avatarDownOffsetTierCount(); tier++) {
            plugin.add(PackAssets.avatarDownOffsetAt(tier));
        }

        assertEquals(
            plugin,
            generated,
            "插件侧与构建侧的【头像行】向下偏移档位表不一致。头像与 bot 图标的码位按档位下标排布，"
                + "两侧差一档就会让头像整体错位到另一个垂直位置，玩家用旧缓存资源包会看到错位的头像。"
                + "两侧必须都从档位表推导（PackAssets.AVATAR_DOWN_OFFSET_TIERS 与"
                + " build.gradle.kts 的 avatarDownOffsetTiers），不许任何一侧写死档数。"
        );
    }

    /**
     * 【两张表必须是两张】牌表不许含头像专用的深档，头像表必须含默认那一档。
     *
     * <p>守的风险是「拆表被悄悄合回去」。上一版两族共用一张表，为了两行不重叠往牌表里塞了
     * 110 这一档 —— 于是每一档都无差别生成三族字形，牌永远用不到 110、头像永远用不到 0..52 的
     * 浅档，约一半 images.yml 条目是废的（5568 条里废掉将近 500 条）。
     *
     * <p>为什么上面那两条「两侧同源」测试守不住这件事：它们只验「插件侧与构建侧一致」，
     * 两边一起把表合回去、一起塞回 110，两条测试照旧全绿，但废条目又回来了，
     * 而且牌行会重新放行一个 110 —— 那是把牌推到屏幕外的值。
     */
    @Test
    void 牌表与头像表是相互独立的两张表() {
        Set<Integer> cardTiers = new java.util.TreeSet<>();
        for (int tier = 0; tier < PackAssets.cardGlyphDownOffsetTierCount(); tier++) {
            cardTiers.add(PackAssets.cardGlyphDownOffsetAt(tier));
        }
        Set<Integer> avatarTiers = new java.util.TreeSet<>();
        for (int tier = 0; tier < PackAssets.avatarDownOffsetTierCount(); tier++) {
            avatarTiers.add(PackAssets.avatarDownOffsetAt(tier));
        }

        assertFalse(
            cardTiers.contains(110),
            "牌表又含上了 110。牌行的合法区间是 0..52（那是「把牌从屏幕顶边往下推一点」的量），"
                + "110 会把牌推到屏幕中间；它是头像行专用值，混进牌表意味着两张表被合回去了，"
                + "275 条/档的牌字形会白生成一整档"
        );
        assertTrue(
            avatarTiers.contains(110),
            "头像表缺了 110。它是默认组合（offset-down=50 + avatar-scale=6）两行精确相接的那一档，"
                + "缺了它默认配置自己就会触发回退警告"
        );
        assertEquals(
            0, PackAssets.cardGlyphDownOffsetAt(0),
            "牌表索引 0 必须是 0：不带档位的旧调用方走的就是档 0，挪了它老资源包会整体串牌"
        );
        assertEquals(
            0, PackAssets.avatarDownOffsetAt(0),
            "头像表索引 0 必须是 0：avatarPixelChar(scale,row) 与 botAvatarChar(role) 这两个"
                + "不带档位的重载走档 0，桌边座位牌和 Title 用的是它们，不能跟着 HUD 一起下沉"
        );
    }

    /**
     * 头像字形盒必须按 {@code 10 * scale} 生成，不是 {@code 8 * scale}。
     *
     * <p>这条锁的是出牌 HUD 两行布局的垂直几何依据。字形按 {@code AVATAR_OUTLINED_PIXELS}(=10)
     * 行预生成，第 row 行的贴图高 {@code (10 - row) * scale}，所以 row 0 的 height 就是
     * {@code 10 * scale} —— 6 倍是 60 像素，不是 48。
     *
     * <p>守的风险：代码注释里长期写着「6 倍是 48 像素高」（那说的是关掉描边后可见的 8x8 脸），
     * 照着它算两行间距会让头像顶边压进牌里 12 像素。有人「修正」生成循环为 8 行时，
     * 现有的码位比对测试仍会全绿（码位没变），只有这条会红。
     */
    @Test
    void avatarPixelGlyphBoxIsTenRowsTall() throws IOException {
        Map<String, Map<String, String>> entries = glyphEntries();

        for (int scale = PackAssets.AVATAR_PIXEL_MIN_SCALE; scale <= PackAssets.AVATAR_PIXEL_MAX_SCALE; scale++) {
            for (int row = 0; row < PackAssets.AVATAR_OUTLINED_PIXELS; row++) {
                String entry = PackAssets.avatarPixelAssetName(scale, row, 0);
                Map<String, String> fields = entries.get(entry);
                assertNotNull(fields, "images.yml 里缺少头像字形条目 muz:" + entry);
                assertEquals(
                    String.valueOf((PackAssets.AVATAR_OUTLINED_PIXELS - row) * scale),
                    fields.get("height"),
                    entry + " 的 height 不等于 (10-row)*scale。row 0 的 height 就是头像字形盒高，"
                        + "两行 HUD 的垂直间距按它算；按 8*scale 算会让头像压进牌里"
                );
            }
        }

        // 把「盒高 = 10*scale」这条直接钉在两行布局用的算式上，避免有人只改算式不改字形。
        assertEquals(
            60,
            PackAssets.avatarRowDownOffset(0, 6),
            "6 倍头像的字形盒必须按 60 像素算（10*6），不是 48（那是可见的 8x8 脸）"
        );

        // 全倍数都钉一遍：只钉 6 倍的话，有人把算式改成「6 倍特判 + 其余按 8 算」也能全绿。
        // 4..10 每一档都验，是因为 avatar-scale 放行的就是这个区间。
        for (int scale = PackAssets.AVATAR_PIXEL_MIN_SCALE; scale <= PackAssets.AVATAR_PIXEL_MAX_SCALE; scale++) {
            assertEquals(
                10 * scale,
                PackAssets.avatarRowDownOffset(0, scale),
                scale + " 倍头像的盒高必须是 10*" + scale + "=" + (10 * scale)
                    + "，不是 8*" + scale + "=" + (8 * scale)
                    + "。描边那两行永远参与字形度量，运行期关 avatar-outline 只是不画像素、"
                    + "不改度量；按 8 算会让头像顶边压进牌里 " + (2 * scale) + " 像素"
            );
        }
        assertEquals(
            10, PackAssets.AVATAR_OUTLINED_PIXELS,
            "头像字形盒的行数被改了。它是 8x8 脸向外扩一圈的 10 行，两行 HUD 的垂直几何全靠它"
        );
    }

    /**
     * 机器人兜底图标的宽度算式必须与 images.yml 里的 height 一致。
     *
     * <p>HUD 头像行要把每个头像在自己槽位里居中，机器人那一槽用的是这个宽度。
     * 贴图是正方形（16x16 / 18x18），位图字形按宽高比等比缩放，所以渲染宽度 = height。
     * 算错只是让机器人图标在槽里偏一点，不会豆腐块 —— 正因为不显眼才需要测试守。
     */
    @Test
    void botAvatarAdvanceWidthMatchesDeclaredHeight() throws IOException {
        Map<String, Map<String, String>> entries = glyphEntries();
        for (PlayerRole role : new PlayerRole[] {null, PlayerRole.LANDLORD, PlayerRole.FARMER}) {
            Map<String, String> fields = entries.get(botAvatarEntryName(role));
            assertNotNull(fields, "images.yml 里缺少机器人图标条目：" + botAvatarEntryName(role));
            assertEquals(
                Integer.parseInt(fields.get("height")) + 1,
                PackAssets.botAvatarAdvanceWidth(role),
                botAvatarEntryName(role) + " 的前进宽度算式和 images.yml 的 height 对不上，"
                    + "HUD 头像行里机器人那一槽会偏离槽位中心"
            );
        }
    }

    /**
     * 默认档（缩放档 0、偏移档 0）的码位必须还是原来那 55 个。
     *
     * <p>默认档就是所有老配置升级上来后实际用的那一档。扩多档时如果顺手把起点或
     * 档序号的基准挪了，默认档的牌会整体串位，而「所有档都自洽」的比对测试仍然全绿 ——
     * 所以要单独把默认档钉在 0xE100 起的连续 55 个码位上。
     */
    @Test
    void defaultTierCardCodepointsAreUnchanged() {
        assertEquals(0xE100, PackAssets.CARD_GLYPH_CODEPOINT_START, "牌面码位起点不能挪，默认档会整体串位");

        Set<Integer> defaultTierCodepoints = new HashSet<>();
        for (DoudizhuCard card : fullDeck()) {
            // 单参重载是全项目的默认入口，它必须等价于「缩放档 0 + 偏移档 0」。
            assertEquals(
                PackAssets.cardGlyphChar(card, 0, 0),
                PackAssets.cardGlyphChar(card),
                "单参 cardGlyphChar 必须走默认档，否则老调用方会静默换牌"
            );
            int codepoint = PackAssets.cardGlyphChar(card).codePointAt(0);
            assertTrue(
                codepoint >= 0xE100 && codepoint < 0xE100 + 55,
                "默认档的牌 " + PackAssets.cardAssetName(card) + " 码位跑出了 0xE100 起的那 55 个：" + codepoint
            );
            defaultTierCodepoints.add(codepoint);
        }
        assertEquals(54, defaultTierCodepoints.size(), "默认档有两张牌撞到了同一个码位");
    }

    /**
     * 每一档的 height/ascent 必须正好等于该档的「缩放高度」与「向下偏移」。
     *
     * <p>这两个字段就是缩放和下移的全部实现：height 决定客户端把牌缩到多大，
     * ascent 决定字形相对基线抬多高（ascent 比 height 小多少，就等于往下沉多少像素）。
     * 少了这条守护，把 ascent 写成固定值也能让上面的码位比对全绿，但 config 里
     * 调 offset-down 会完全没反应 —— 这正是这两个配置项存在的意义。
     */
    @Test
    void cardGlyphTiersEncodeScaleAndDownOffset() throws IOException {
        Map<String, Map<String, String>> entries = glyphEntries();
        // 抽一张真牌当样本：几何是全档统一算的，逐张再验一遍只是重复。
        String sampleCard = PackAssets.cardAssetName(fullDeck().getFirst());

        for (int heightTier = 0; heightTier < PackAssets.cardGlyphHeightTierCount(); heightTier++) {
            for (int downTier = 0; downTier < PackAssets.cardGlyphDownOffsetTierCount(); downTier++) {
                int height = PackAssets.cardGlyphHeightAt(heightTier);
                int downOffset = PackAssets.cardGlyphDownOffsetAt(downTier);
                String entry = PackAssets.cardGlyphAssetName(sampleCard, heightTier, downTier);
                Map<String, String> fields = entries.get(entry);
                assertNotNull(fields, "images.yml 里缺少字形条目 muz:" + entry);

                assertEquals(
                    String.valueOf(height),
                    fields.get("height"),
                    entry + " 的 height 和缩放档对不上，config 里的 card-height 会调不动牌的大小"
                );
                assertEquals(
                    String.valueOf(height - downOffset),
                    fields.get("ascent"),
                    entry + " 的 ascent 不等于 height 减向下偏移，config 里的 offset-down 会不生效或位移错"
                );
            }
        }

        // 缩放只许缩小：贴图本身 35x53，放大只会得到插值糊掉的牌，而且满手 20 张会横出屏幕。
        for (int heightTier = 0; heightTier < PackAssets.cardGlyphHeightTierCount(); heightTier++) {
            assertTrue(
                PackAssets.cardGlyphHeightAt(heightTier) <= 53,
                "缩放档 " + heightTier + " 比贴图原始高度还大，牌会被放大插值糊掉"
            );
        }
        assertEquals(53, PackAssets.cardGlyphHeightAt(0), "缩放档 0 必须是 1:1，它是默认档也是上限");
    }

    /**
     * 机器人兜底图标每个向下偏移档都得有自己的字形，且必须与牌沉得一样多。
     *
     * <p>【档位走头像那张表，不是牌那张】：这个图标画在 HUD 的【头像行】—— 它是真人皮肤取不到
     * 时的替代品，位置必须和真人头像一致。TrickHudService 给它传的就是头像行档位。
     * 跟着牌表走会让 bot 座位的图标和真人头像上下错开一整行，而这只在「桌上有机器人」时才显形。
     *
     * <p>机器人和「皮肤还没下载好」的真人都落到这条兜底路径上，是常见路径而非边缘情况。
     */
    @Test
    void botAvatarIconFollowsAvatarDownOffsetTiers() throws IOException {
        Map<String, Map<String, String>> entries = glyphEntries();

        for (int downTier = 0; downTier < PackAssets.avatarDownOffsetTierCount(); downTier++) {
            int downOffset = PackAssets.avatarDownOffsetAt(downTier);
            for (PlayerRole role : new PlayerRole[] {null, PlayerRole.LANDLORD, PlayerRole.FARMER}) {
                String glyph = PackAssets.botAvatarChar(role, downTier);
                String expectedChar = String.format("\\u%04x", glyph.codePointAt(0));

                // 档 0 复用桌边和 Title 那三个原码位：它们不能跟着 HUD 一起沉。
                if (downTier == 0) {
                    assertEquals(
                        PackAssets.botAvatarChar(role),
                        glyph,
                        "偏移档 0 的机器人图标必须还是原码位，否则桌边座位牌和 Title 会跟着 HUD 一起动"
                    );
                    continue;
                }

                String entry = botAvatarEntryName(role) + "_d" + downOffset;
                Map<String, String> fields = entries.get(entry);
                assertNotNull(fields, "images.yml 里缺少机器人图标偏移档条目 muz:" + entry);
                assertEquals(
                    expectedChar,
                    fields.get("char"),
                    entry + " 的码位和插件算出来的不一致，机器人 HUD 会显示成豆腐块"
                );
                assertEquals(
                    String.valueOf(8 - downOffset),
                    fields.get("ascent"),
                    entry + " 的 ascent 不等于原 ascent 8 减向下偏移，图标会和牌错开"
                );
            }
        }

        // 所有档所有角色的码位必须互不相同：撞码位会让某个角色或某一档显示成别的图。
        Set<String> allChars = new HashSet<>();
        int expected = 0;
        for (int downTier = 0; downTier < PackAssets.avatarDownOffsetTierCount(); downTier++) {
            for (PlayerRole role : new PlayerRole[] {null, PlayerRole.LANDLORD, PlayerRole.FARMER}) {
                allChars.add(PackAssets.botAvatarChar(role, downTier));
                expected++;
            }
        }
        assertEquals(expected, allChars.size(), "机器人图标的档位码位有重复");
    }

    /**
     * 【bot 换表的 off-by-one】bot 图标生成的档位集合必须恰好是「头像表去掉档 0」。
     *
     * <p>bot 的码位公式是 {@code 起点 + (档 - 1) * 3 + 角色}，配套「档 0 跳过不生成」——
     * 档 0 复用桌边座位牌那三个原始码位。那个 {@code -1} 与跳过逻辑是一对：换表时只改循环、
     * 忘了这对关系就会 off-by-one，表现是所有 bot 图标整体错开一档（约 10 像素），
     * 或者第一档撞上原始码位。
     *
     * <p>上面那条 {@link #botAvatarIconFollowsAvatarDownOffsetTiers} 是「插件算出的码位与
     * images.yml 对齐」，两侧一起错开一格它仍然全绿。这条从另一个方向验：
     * 构建侧实际生成了哪些档（按条目名反推），以及第一条生成条目是否正好落在码位起点上。
     */
    @Test
    void botAvatarDownTiersSkipTierZeroWithoutOffByOne() throws IOException {
        Map<String, Map<String, String>> entries = glyphEntries();

        // 构建侧实际生成的 bot 偏移档（按 bot_avatar_d<偏移> 这种条目名反推）。
        Set<Integer> generated = new java.util.TreeSet<>();
        Pattern suffix = Pattern.compile("^bot_avatar(?:_landlord|_farmer)?_d(-?\\d+)$");
        for (String name : entries.keySet()) {
            Matcher matcher = suffix.matcher(name);
            if (matcher.matches()) {
                generated.add(Integer.parseInt(matcher.group(1)));
            }
        }

        // 期望：头像表的每一档，除了档 0。
        Set<Integer> expectedTiers = new java.util.TreeSet<>();
        for (int tier = 1; tier < PackAssets.avatarDownOffsetTierCount(); tier++) {
            expectedTiers.add(PackAssets.avatarDownOffsetAt(tier));
        }

        assertEquals(
            expectedTiers,
            generated,
            "bot 图标生成的偏移档不等于「头像表去掉档 0」。它画在 HUD 头像行（真人皮肤取不到时的"
                + "替代品），必须跟头像表；跟着牌表走会让 bot 座位的图标和真人头像上下错开一整行。"
                + "档 0 不生成是刻意的：桌边座位牌与 Title 用的是那三个原始码位，不能跟着 HUD 沉。"
        );

        // 档 1（头像表里第一个非 0 档）的三个角色必须正好占码位起点起的前三个。
        // 这就是 (档 - 1) 那个减一的全部含义，写错就整体平移三个码位。
        int firstTierBase = PackAssets.botAvatarChar(null, 1).codePointAt(0);
        assertEquals(
            firstTierBase + 1,
            PackAssets.botAvatarChar(PlayerRole.LANDLORD, 1).codePointAt(0),
            "地主 bot 图标不在「无角色 + 1」上，roleIndex 排布和构建侧对不上"
        );
        assertEquals(
            firstTierBase + 2,
            PackAssets.botAvatarChar(PlayerRole.FARMER, 1).codePointAt(0),
            "农民 bot 图标不在「无角色 + 2」上，roleIndex 排布和构建侧对不上"
        );
        // 第二个非 0 档必须紧接着，步长恰好等于角色数 3。
        assertEquals(
            firstTierBase + 3,
            PackAssets.botAvatarChar(null, 2).codePointAt(0),
            "相邻两个 bot 偏移档的码位间隔不是 3（角色数），档位一多就会互相盖穿"
        );
        // 档 0 必须仍是原码位，不许落进 _d 那一段。
        assertEquals(
            PackAssets.botAvatarChar(null),
            PackAssets.botAvatarChar(null, 0),
            "档 0 的 bot 图标不再是原码位，桌边座位牌和 Title 会跟着 HUD 一起往下沉"
        );
    }

    /** 机器人图标在 images.yml 里的基础条目名，顺序与 PackAssets 里的 roleIndex 一致。 */
    private static String botAvatarEntryName(PlayerRole role) {
        if (role == PlayerRole.LANDLORD) {
            return "bot_avatar_landlord";
        }
        if (role == PlayerRole.FARMER) {
            return "bot_avatar_farmer";
        }
        return "bot_avatar";
    }

    /** 牌面字形涉及的 55 个贴图名：54 张牌加一张牌背。 */
    private static List<String> allCardAssetNames() {
        List<String> names = new ArrayList<>();
        names.add("card_back");
        for (DoudizhuCard card : fullDeck()) {
            names.add(PackAssets.cardAssetName(card));
        }
        return names;
    }

    /**
     * 把 images.yml 整个解析成「条目名 -&gt; 字段表」。
     *
     * <p>档位扩到 30 档后光牌面就有 1650 条，逐条 indexOf 全文扫描会把这个测试拖慢一个
     * 数量级，所以一次解析、反复查表。
     */
    /**
     * images.yml 里【任何两条声明都不许共用同一组 (font, char)】。
     *
     * <p>这正是 CraftEngine 自己在启动时会抱怨的那件事（「某个私有区字符已被另一张图片占用」）。
     * 之所以要在这里复现一遍：CE 只在运行时报，而且一撞就是成百上千条，翻日志才能发现；
     * 放到构建期测试里，扩档时立刻就红。
     *
     * <p>历史教训：以前牌面和头像都挂 minecraft:default，牌面偏移档从 6 扩到 15 之后
     * 占用从 1650 涨到 4125 个码位，直接盖穿头像起点 0xE800，CE 报了 1050 条冲突。
     * 当时守这条的测试是拿「起点 + 55」估的，没算档位倍数，所以全绿放过。
     * 现在按声明逐条比对，不做任何估算。
     */
    @Test
    void noTwoGlyphDeclarationsShareTheSameFontAndChar() throws IOException {
        Map<String, Map<String, String>> entries = glyphEntries();
        Map<String, String> owner = new HashMap<>();
        for (Map.Entry<String, Map<String, String>> entry : entries.entrySet()) {
            String font = entry.getValue().get("font");
            String ch = entry.getValue().get("char");
            if (font == null || ch == null) {
                continue;
            }
            String key = font + " " + ch;
            String previous = owner.put(key, entry.getKey());
            assertNull(
                previous,
                "字形声明 " + entry.getKey() + " 与 " + previous + " 抢同一个码位（"
                    + font + " " + ch + "）—— CE 启动会报「字符已被另一张图片占用」，"
                    + "客户端上后来者会显示成豆腐块"
            );
        }
    }

    /**
     * 头像方块字形：渲染器能发出的每个码位都必须在 images.yml 里有声明。
     *
     * <p>牌面和 bot 头像的码位都有测试逐个比对，头像像素这条却只验了「字体名互不相同」
     * 和「外层套了 &lt;font:&gt;」——码位表本身有没有洞没人查。而它的码位是三重乘出来的
     * （7 档 scale x 10 行 x 15 个偏移档 = 1050），任何一维的生成循环边界写错，
     * 都会漏掉一批码位，表现是特定倍数或特定偏移档下头像某几行变成豆腐块。
     * 这种缺陷只在玩家刚好用到那个组合时才显形，比整体不显示更难发现。
     *
     * <p>反向也一并查：声明了但渲染器永远发不出来的码位说明两侧公式已经不一致，
     * 那是同一个错误的另一半。
     */
    @Test
    void everyAvatarPixelCodepointTheRendererCanEmitIsDeclared() throws IOException {
        Set<Integer> declared = new HashSet<>();
        for (Map<String, String> fields : glyphEntries().values()) {
            if (PackAssets.AVATAR_PIXEL_FONT.equals(fields.get("font"))) {
                String ch = fields.get("char");
                if (ch != null && ch.startsWith("\\u")) {
                    declared.add(Integer.parseInt(ch.substring(2).trim(), 16));
                }
            }
        }

        Set<Integer> emitted = new HashSet<>();
        // 头像走自己那张档位表（拆表后与牌表相互独立）。
        for (int tier = 0; tier < PackAssets.avatarDownOffsetTierCount(); tier++) {
            for (int scale = PackAssets.AVATAR_PIXEL_MIN_SCALE;
                 scale <= PackAssets.AVATAR_PIXEL_MAX_SCALE; scale++) {
                for (int row = 0; row < PackAssets.AVATAR_OUTLINED_PIXELS; row++) {
                    emitted.add(PackAssets.avatarPixelChar(scale, row, tier).codePointAt(0));
                }
            }
        }

        Set<Integer> missing = new java.util.TreeSet<>(emitted);
        missing.removeAll(declared);
        assertTrue(missing.isEmpty(),
            "images.yml 缺少这些头像码位，对应的倍数/行/偏移组合会显示成豆腐块: " + hexList(missing));

        Set<Integer> unused = new java.util.TreeSet<>(declared);
        unused.removeAll(emitted);
        assertTrue(unused.isEmpty(),
            "images.yml 声明了渲染器发不出的头像码位，说明构建侧与插件侧公式已不一致: " + hexList(unused));
    }

    /**
     * 头像字形的【条目名】必须逐档与资源包对齐，不只是码位。
     *
     * <p>条目名里带 {@code _d<偏移>} 后缀，那个偏移值由插件侧
     * {@code avatarPixelAssetName} 查表算出。上面
     * {@link #everyAvatarPixelCodepointTheRendererCanEmitIsDeclared} 只比对码位集合，
     * 而码位公式里根本不含偏移【像素值】（只含档【下标】）—— 所以插件侧若把名字里的偏移
     * 值查错表（例如借牌表算，同一下标在两张表是完全不同的像素值），码位测试照旧全绿。
     *
     * <p>后果：CraftEngineBundleExporter 之类按名字定位条目的代码会找不到条目，
     * 或者找到另一档的条目。这是牌面侧早就有对应测试的事
     * （{@code cardGlyphCodepointsMatchGeneratedImages} 里那段名字集合比对），
     * 拆表后头像侧必须有同等强度的一条。
     */
    @Test
    void avatarPixelAssetNamesMatchGeneratedImages() throws IOException {
        Set<String> generated = new HashSet<>();
        for (String name : glyphEntries().keySet()) {
            if (name.startsWith("avatar_px_")) {
                generated.add(name);
            }
        }

        Set<String> plugin = new HashSet<>();
        for (int tier = 0; tier < PackAssets.avatarDownOffsetTierCount(); tier++) {
            for (int scale = PackAssets.AVATAR_PIXEL_MIN_SCALE;
                 scale <= PackAssets.AVATAR_PIXEL_MAX_SCALE; scale++) {
                for (int row = 0; row < PackAssets.AVATAR_OUTLINED_PIXELS; row++) {
                    plugin.add(PackAssets.avatarPixelAssetName(scale, row, tier));
                }
            }
        }

        assertEquals(
            generated,
            plugin,
            "插件侧算出的头像字形条目名与资源包里的对不上。名字里的 _d<偏移> 必须查【头像】档位表，"
                + "借牌表算会得到另一档的偏移值（同一下标在两张表是不同像素值），"
                + "而只比对码位的测试对此完全不设防"
        );
    }

    /**
     * 每条字形声明引用的贴图都必须真的打进了 bundle。
     *
     * <p>声明在、码位对，但 file 指向的 png 没打包，客户端一样是豆腐块，
     * 而且 CE 启动时不一定报错。改名或移动贴图目录时最容易踩到。
     */
    @Test
    void everyGlyphDeclarationPointsAtABundledTexture() throws IOException {
        String index = read("craftengine/muz/_bundle_index.txt");
        for (Map.Entry<String, Map<String, String>> entry : glyphEntries().entrySet()) {
            String file = entry.getValue().get("file");
            if (file == null || !file.startsWith("muz:")) {
                continue;
            }
            String path = "resourcepack/assets/muz/textures/" + file.substring(4);
            assertTrue(index.contains(path),
                "字形声明 " + entry.getKey() + " 引用的贴图没打进 bundle: " + path
                    + "，客户端会显示成豆腐块");
        }
    }

    private static String hexList(Set<Integer> codepoints) {
        StringBuilder text = new StringBuilder();
        int shown = 0;
        for (int codepoint : codepoints) {
            if (shown++ == 20) {
                text.append(" ...共 ").append(codepoints.size()).append(" 个");
                break;
            }
            text.append(shown == 1 ? "" : ", ").append(String.format("U+%04X", codepoint));
        }
        return text.toString();
    }

    private static Map<String, Map<String, String>> glyphEntries() throws IOException {
        String images = read("craftengine/muz/configuration/images.yml").replace("\r\n", "\n");
        Map<String, Map<String, String>> entries = new HashMap<>();
        Map<String, String> current = null;
        for (String line : images.split("\n")) {
            Matcher header = Pattern.compile("^ {2}muz:(\\S+):$").matcher(line);
            if (header.matches()) {
                current = new HashMap<>();
                entries.put(header.group(1), current);
                continue;
            }
            Matcher field = Pattern.compile("^ {4}(\\w+): (.+)$").matcher(line);
            if (current != null && field.matches()) {
                current.put(field.group(1), field.group(2).trim());
            }
        }
        return entries;
    }

    /**
     * 每一档的 ascent 都不许超过 height —— 这是 Minecraft 对位图字形的硬限制。
     *
     * <p>违反时客户端会直接拒绝该字形，整片牌变豆腐块。这条限制在
     * {@code PackAssets.CARD_GLYPH_DOWN_OFFSET_TIERS} 的注释里有记载
     * （「Minecraft 限制 ascent 不得大于 height」），但一直没有测试守。
     *
     * <p><b>为什么等式测试守不住这条</b>：{@link #cardGlyphTiersEncodeScaleAndDownOffset}
     * 断言的是 {@code ascent == height - offsetDown}。只要有人给偏移档加一个负值
     * （想让牌往上移就会这么改，而注释也正是在解释「为什么只有向下没有向上」），
     * 等式依然成立、那条测试照旧全绿，但 ascent 已经大于 height，牌全成豆腐块。
     *
     * <p>失败条件：偏移档里出现负值。届时该做的不是放宽这条断言，而是回去读
     * {@code CARD_GLYPH_DOWN_OFFSET_TIERS} 的注释——向上位移得靠加大 height 实现，
     * 那等于拉伸牌面，不是纯位移。
     */
    @Test
    void cardGlyphAscentNeverExceedsHeight() {
        for (int heightTier = 0; heightTier < PackAssets.cardGlyphHeightTierCount(); heightTier++) {
            int height = PackAssets.cardGlyphHeightAt(heightTier);
            for (int downTier = 0; downTier < PackAssets.cardGlyphDownOffsetTierCount(); downTier++) {
                int downOffset = PackAssets.cardGlyphDownOffsetAt(downTier);
                int ascent = height - downOffset;

                assertTrue(ascent <= height,
                    "缩放档 " + heightTier + "(height " + height + ") 配偏移档 " + downTier
                        + "(offset " + downOffset + ") 得到 ascent " + ascent
                        + " > height " + height
                        + "，Minecraft 会拒绝这个字形、牌全变豆腐块；向上位移不能靠负偏移实现");
            }
        }
    }

    /**
     * 牌面字形贴图必须是从 UV 图集里裁出来的 35x53 纯牌面。
     *
     * <p>源贴图是 79x63 的 item 模型 UV 展开图，里面同时有正面、牌背、侧边条和
     * 水印。要是哪天有人把裁切去掉直接拿整张图当字形，牌面上就会糊上牌背和水印，
     * 所以这里把尺寸钉死。
     */
    @Test
    void cardGlyphTexturesAreCroppedCardFaces() throws IOException {
        for (DoudizhuCard card : fullDeck()) {
            String assetName = PackAssets.cardAssetName(card);
            String path = "craftengine/muz/resourcepack/assets/muz/textures/font/cards/" + assetName + ".png";
            BufferedImage texture = readImage(path);
            assertEquals(35, texture.getWidth(), path + " 宽度不是裁切后的牌面宽度");
            assertEquals(53, texture.getHeight(), path + " 高度不是裁切后的牌面高度");
        }
    }

    /**
     * 血条槽轨道必须靠「与原版同路径同尺寸的全透明贴图」盖掉，四个条件缺一不可。
     *
     * <p>牌面 HUD 用 {@code BossBar.Color.WHITE} + {@code Overlay.PROGRESS}
     * （见 {@code TrickHudService.BAR_COLOR}），客户端因此只读 {@code white_background}
     * 与 {@code white_progress} 这两张 sprite；PROGRESS 无刻痕，不涉及 notched_* 贴图。
     * 改成别的颜色档会让这两张贴图失效、原版深色槽重新露在屏幕顶部。
     *
     * <p>四条断言各对应一种真实失效方式：
     * <ul>
     *   <li><b>路径</b>——原版 sprite 路径错一个字符就不再是覆盖，只是多一张没人读的图；</li>
     *   <li><b>182x5</b>——原版血条槽尺寸，尺寸不符会让 atlas 缝合错位、露出原图；</li>
     *   <li><b>alpha 全 0</b>——只要一个像素不透明，那一列就会显形；</li>
     *   <li><b>在 _bundle_index.txt 里</b>——已取证的静默故障：贴图打进了 jar、单测也全过，
     *       但没进清单就永远不会被导出到 CraftEngine 的 resources 目录，
     *       客户端拿到的仍是原版不透明贴图（见 {@code CraftEngineBundleExporter} 类注释）。</li>
     * </ul>
     */
    @Test
    void bossBarTrackTexturesAreFullyTransparentAndExported() throws IOException {
        String manifest = read("craftengine/muz/_bundle_index.txt");
        for (String sprite : List.of("white_background", "white_progress")) {
            String relative = "resourcepack/assets/minecraft/textures/gui/sprites/boss_bar/" + sprite + ".png";
            assertTrue(manifest.lines().anyMatch(line -> line.trim().equals(relative)),
                relative + " 不在 _bundle_index.txt 里：贴图会留在 jar 内、永不导出到 CraftEngine，"
                    + "客户端仍拿原版不透明贴图，轨道照旧显形");

            BufferedImage image = readImage("craftengine/muz/" + relative);
            assertEquals(182, image.getWidth(),
                relative + " 宽必须是原版血条槽的 182px，否则 atlas 缝合错位会露出原图");
            assertEquals(5, image.getHeight(),
                relative + " 高必须是原版血条槽的 5px，否则 atlas 缝合错位会露出原图");

            int opaque = 0;
            for (int y = 0; y < image.getHeight(); y++) {
                for (int x = 0; x < image.getWidth(); x++) {
                    if ((image.getRGB(x, y) >>> 24) != 0) {
                        opaque++;
                    }
                }
            }
            assertEquals(0, opaque,
                relative + " 有 " + opaque + " 个像素不透明：任何非零 alpha 都会让那条槽在屏幕顶部显形");
        }
    }

    /** 一整副 54 张牌。id 不参与字形计算，这里只是占位。 */
    private static List<DoudizhuCard> fullDeck() {
        List<DoudizhuCard> deck = new ArrayList<>();
        int id = 0;
        for (CardSuit suit : CardSuit.values()) {
            if (suit == CardSuit.JOKER) {
                continue;
            }
            for (CardRank rank : CardRank.values()) {
                if (rank == CardRank.SMALL_JOKER || rank == CardRank.BIG_JOKER) {
                    continue;
                }
                deck.add(new DoudizhuCard(id++, rank, suit));
            }
        }
        deck.add(new DoudizhuCard(id++, CardRank.SMALL_JOKER, CardSuit.JOKER));
        deck.add(new DoudizhuCard(id, CardRank.BIG_JOKER, CardSuit.JOKER));
        assertEquals(54, deck.size(), "一副斗地主牌应当是 54 张");
        return deck;
    }

    /** 从 images.yml 里取出某个字形条目下的某个字段值。 */
    private static String glyphField(String images, String imageId, String field) {
        String header = "  muz:" + imageId + ":\n";
        int start = images.indexOf(header);
        assertTrue(start >= 0, "images.yml 里缺少字形条目 muz:" + imageId);
        int bodyStart = start + header.length();
        int nextEntry = images.indexOf("\n  muz:", bodyStart);
        String body = nextEntry < 0 ? images.substring(bodyStart) : images.substring(bodyStart, nextEntry);
        Matcher matcher = Pattern.compile("^ {4}" + field + ": (.+)$", Pattern.MULTILINE).matcher(body);
        assertTrue(matcher.find(), "字形条目 muz:" + imageId + " 缺少字段 " + field);
        return matcher.group(1).trim();
    }

    private static BufferedImage readImage(String path) throws IOException {
        InputStream stream = CraftEngineBundleResourcesTest.class.getClassLoader().getResourceAsStream(path);
        assertNotNull(stream, "Missing resource " + path);
        try (stream) {
            BufferedImage image = ImageIO.read(stream);
            assertNotNull(image, "读不出图片 " + path);
            return image;
        }
    }

    private static String read(String path) throws IOException {
        InputStream stream = CraftEngineBundleResourcesTest.class.getClassLoader().getResourceAsStream(path);
        assertNotNull(stream, "Missing resource " + path);
        try (stream) {
            return new String(stream.readAllBytes(), StandardCharsets.UTF_8);
        }
    }
}

