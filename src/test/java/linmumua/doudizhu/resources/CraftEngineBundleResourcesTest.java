package linmumua.doudizhu.resources;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;

class CraftEngineBundleResourcesTest {
    @Test
    void generatedCraftEngineBundleIsOnClasspath() throws IOException {
        String index = read("craftengine/muz/_bundle_index.txt");
        assertTrue(index.contains("pack.yml"));
        assertTrue(index.contains("configuration/items/doudizhu/cards.yml"));
        assertTrue(index.contains("configuration/items/doudizhu/ui.yml"));
        assertTrue(index.contains("configuration/categories.yml"));
        assertTrue(index.contains("configuration/sounds.yml"));
        assertTrue(index.contains("resourcepack/assets/muz/items/cards/clubs_3.json"));
    }

    @Test
    void generatedCraftEngineCardsConfigContainsCardAndUiItems() throws IOException {
        String cards = read("craftengine/muz/configuration/items/doudizhu/cards.yml");
        String ui = read("craftengine/muz/configuration/items/doudizhu/ui.yml");
        String categories = read("craftengine/muz/configuration/categories.yml");
        assertTrue(cards.contains("muz:clubs_3:"));
        assertTrue(cards.contains("model: muz:item/cards/clubs_3"));
        assertTrue(ui.contains("muz:ui_play:"));
        assertTrue(categories.contains("muz:doudizhu:"));
        assertTrue(categories.contains("muz:big_joker"));
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

        String pluginDescriptor = read("plugin.yml");
        assertTrue(pluginDescriptor.contains("version: " + expectedPluginVersion));
        assertTrue(pluginDescriptor.contains("api-version: \"" + expectedApiVersion + "\""));
    }

    private static String read(String path) throws IOException {
        InputStream stream = CraftEngineBundleResourcesTest.class.getClassLoader().getResourceAsStream(path);
        assertNotNull(stream, "Missing resource " + path);
        try (stream) {
            return new String(stream.readAllBytes(), StandardCharsets.UTF_8);
        }
    }
}

