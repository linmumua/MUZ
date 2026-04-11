import org.gradle.api.tasks.bundling.Zip
import java.io.File
import groovy.json.JsonOutput
import groovy.json.JsonSlurper

plugins {
    java
    id("io.papermc.paperweight.userdev") version "2.0.0-beta.19"
}

group = "dev.codex"
version = "1.4.0"

val sourceResourceNamespace = "doudizhupaper"
val resourceNamespace = "muz"
val generatedJarResourcesDir = layout.buildDirectory.dir("generated/resources/main")
val generatedResourcePackDir = layout.buildDirectory.dir("generated/resourcepack")
val resourcePackSourceDir = layout.projectDirectory.dir("resourcepack").asFile
val playgroundRootDir = layout.projectDirectory.asFile.parentFile
val cardTextureDir = resourcePackSourceDir.resolve("assets/$sourceResourceNamespace/textures/item/cards")
val uiTextureDir = resourcePackSourceDir.resolve("assets/$sourceResourceNamespace/textures/item/ui")
val soundSourceDir = resourcePackSourceDir.resolve("assets/$sourceResourceNamespace/sounds")
val jokerTemplateModelFile = playgroundRootDir.resolve("joker_2.json")
val jokerTemplateTextureFile = playgroundRootDir.resolve("joker_2.png")

fun jsonString(value: String): String = buildString {
    append('"')
    value.forEach { character ->
        when (character) {
            '\\' -> append("\\\\")
            '"' -> append("\\\"")
            '\n' -> append("\\n")
            '\r' -> append("\\r")
            '\t' -> append("\\t")
            else -> append(character)
        }
    }
    append('"')
}

fun writeText(target: File, text: String) {
    target.parentFile.mkdirs()
    target.writeText(text, Charsets.UTF_8)
}

fun copyFileTree(sourceRoot: File, targetRoot: File) {
    sourceRoot.walkTopDown()
        .filter(File::isFile)
        .forEach { source ->
            val relative = source.relativeTo(sourceRoot)
            val target = targetRoot.resolve(relative.invariantSeparatorsPath)
            target.parentFile.mkdirs()
            source.copyTo(target, overwrite = true)
        }
}

fun titleFromId(id: String): String = id.split('_').joinToString(" ") { part ->
    part.replaceFirstChar { if (it.isLowerCase()) it.titlecase() else it.toString() }
}

fun soundEventId(relativePath: String): String {
    val normalized = relativePath.removeSuffix(".ogg")
    return when {
        normalized.startsWith("doudizhu/effect/") -> "doudizhu." + normalized.removePrefix("doudizhu/effect/")
        normalized.startsWith("doudizhu/voice/") -> "doudizhu." + normalized.removePrefix("doudizhu/voice/")
        normalized.startsWith("doudizhu/") -> "doudizhu." + normalized.removePrefix("doudizhu/")
        else -> normalized.replace('/', '.')
    }
}

fun writeItemDefinition(target: File, modelPath: String) {
    writeText(target, """{"model":{"type":"minecraft:model","model":${jsonString(modelPath)}}}""" + "\n")
}

fun writeFlatItemModel(target: File, texturePath: String) {
    writeText(
        target,
        """
        {
          "parent": "minecraft:item/generated",
          "textures": {
            "layer0": ${jsonString(texturePath)}
          }
        }
        """.trimIndent() + "\n"
    )
}

fun writeCardTemplateModel(target: File, templateText: String, texturePath: String) {
    val parsed = JsonSlurper().parseText(templateText) as Map<*, *>
    val display = (parsed["display"] as? Map<*, *>)?.filterKeys { key ->
        key in setOf(
            "thirdperson_righthand",
            "thirdperson_lefthand",
            "firstperson_righthand",
            "firstperson_lefthand",
            "ground",
            "gui",
            "head",
            "fixed"
        )
    }.orEmpty()

    val sanitized = linkedMapOf<String, Any>(
        "textures" to mapOf(
            "0" to texturePath,
            "particle" to texturePath
        ),
        "elements" to (parsed["elements"] ?: emptyList<Any>())
    )
    parsed["gui_light"]?.let { sanitized["gui_light"] = it }
    if (display.isNotEmpty()) {
        sanitized["display"] = display
    }
    writeText(target, JsonOutput.prettyPrint(JsonOutput.toJson(sanitized)) + "\n")
}

fun writeTableVisualModel(target: File) {
    writeText(
        target,
        """
        {
          "textures": {
            "wood": "minecraft:block/dark_oak_planks",
            "felt": "minecraft:block/green_wool"
          },
          "elements": [
            {
              "from": [1, 10, 1],
              "to": [15, 12, 15],
              "faces": {
                "up": {"texture": "#felt"},
                "down": {"texture": "#wood"},
                "north": {"texture": "#wood"},
                "south": {"texture": "#wood"},
                "west": {"texture": "#wood"},
                "east": {"texture": "#wood"}
              }
            },
            {
              "from": [2, 0, 2],
              "to": [4, 10, 4],
              "faces": {
                "up": {"texture": "#wood"},
                "down": {"texture": "#wood"},
                "north": {"texture": "#wood"},
                "south": {"texture": "#wood"},
                "west": {"texture": "#wood"},
                "east": {"texture": "#wood"}
              }
            },
            {
              "from": [12, 0, 2],
              "to": [14, 10, 4],
              "faces": {
                "up": {"texture": "#wood"},
                "down": {"texture": "#wood"},
                "north": {"texture": "#wood"},
                "south": {"texture": "#wood"},
                "west": {"texture": "#wood"},
                "east": {"texture": "#wood"}
              }
            },
            {
              "from": [2, 0, 12],
              "to": [4, 10, 14],
              "faces": {
                "up": {"texture": "#wood"},
                "down": {"texture": "#wood"},
                "north": {"texture": "#wood"},
                "south": {"texture": "#wood"},
                "west": {"texture": "#wood"},
                "east": {"texture": "#wood"}
              }
            },
            {
              "from": [12, 0, 12],
              "to": [14, 10, 14],
              "faces": {
                "up": {"texture": "#wood"},
                "down": {"texture": "#wood"},
                "north": {"texture": "#wood"},
                "south": {"texture": "#wood"},
                "west": {"texture": "#wood"},
                "east": {"texture": "#wood"}
              }
            }
          ],
          "display": {
            "gui": {
              "rotation": [25, 225, 0],
              "translation": [0, 0, 0],
              "scale": [0.78, 0.78, 0.78]
            },
            "fixed": {
              "rotation": [0, 0, 0],
              "translation": [0, 0, 0],
              "scale": [1, 1, 1]
            }
          }
        }
        """.trimIndent() + "\n"
    )
}

fun writeSeatChairModel(target: File) {
    writeText(
        target,
        """
        {
          "textures": {
            "wood": "minecraft:block/spruce_planks",
            "cushion": "minecraft:block/red_wool"
          },
          "elements": [
            {
              "from": [3, 7, 3],
              "to": [13, 9, 13],
              "faces": {
                "up": {"texture": "#cushion"},
                "down": {"texture": "#wood"},
                "north": {"texture": "#wood"},
                "south": {"texture": "#wood"},
                "west": {"texture": "#wood"},
                "east": {"texture": "#wood"}
              }
            },
            {
              "from": [3, 9, 11],
              "to": [13, 16, 13],
              "faces": {
                "up": {"texture": "#wood"},
                "down": {"texture": "#wood"},
                "north": {"texture": "#cushion"},
                "south": {"texture": "#wood"},
                "west": {"texture": "#wood"},
                "east": {"texture": "#wood"}
              }
            },
            {
              "from": [3, 0, 3],
              "to": [5, 7, 5],
              "faces": {
                "up": {"texture": "#wood"},
                "down": {"texture": "#wood"},
                "north": {"texture": "#wood"},
                "south": {"texture": "#wood"},
                "west": {"texture": "#wood"},
                "east": {"texture": "#wood"}
              }
            },
            {
              "from": [11, 0, 3],
              "to": [13, 7, 5],
              "faces": {
                "up": {"texture": "#wood"},
                "down": {"texture": "#wood"},
                "north": {"texture": "#wood"},
                "south": {"texture": "#wood"},
                "west": {"texture": "#wood"},
                "east": {"texture": "#wood"}
              }
            },
            {
              "from": [3, 0, 11],
              "to": [5, 7, 13],
              "faces": {
                "up": {"texture": "#wood"},
                "down": {"texture": "#wood"},
                "north": {"texture": "#wood"},
                "south": {"texture": "#wood"},
                "west": {"texture": "#wood"},
                "east": {"texture": "#wood"}
              }
            },
            {
              "from": [11, 0, 11],
              "to": [13, 7, 13],
              "faces": {
                "up": {"texture": "#wood"},
                "down": {"texture": "#wood"},
                "north": {"texture": "#wood"},
                "south": {"texture": "#wood"},
                "west": {"texture": "#wood"},
                "east": {"texture": "#wood"}
              }
            }
          ],
          "display": {
            "gui": {
              "rotation": [22, 225, 0],
              "translation": [0, 0, 0],
              "scale": [0.95, 0.95, 0.95]
            },
            "fixed": {
              "rotation": [0, 0, 0],
              "translation": [0, 0, 0],
              "scale": [1, 1, 1]
            }
          }
        }
        """.trimIndent() + "\n"
    )
}

repositories {
    mavenCentral()
    maven("https://repo.papermc.io/repository/maven-public/")
}

dependencies {
    paperweight.paperDevBundle("1.21.11-R0.1-SNAPSHOT")
    testImplementation(platform("org.junit:junit-bom:5.13.4"))
    testImplementation("org.junit.jupiter:junit-jupiter")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

java {
    toolchain.languageVersion.set(JavaLanguageVersion.of(21))
    withSourcesJar()
}

paperweight {
    reobfArtifactConfiguration = io.papermc.paperweight.userdev.ReobfArtifactConfiguration.REOBF_PRODUCTION
}

val generateResourcePack = tasks.register("generateResourcePack") {
    inputs.dir(resourcePackSourceDir)
    outputs.dir(generatedResourcePackDir)

    doLast {
        val outputRoot = generatedResourcePackDir.get().asFile
        outputRoot.deleteRecursively()
        resourcePackSourceDir.copyRecursively(outputRoot, overwrite = true)
        val legacyAssetsRoot = outputRoot.resolve("assets").resolve(sourceResourceNamespace)
        val namespacedAssetsRoot = outputRoot.resolve("assets").resolve(resourceNamespace)
        if (legacyAssetsRoot.exists() && sourceResourceNamespace != resourceNamespace) {
            legacyAssetsRoot.copyRecursively(namespacedAssetsRoot, overwrite = true)
            legacyAssetsRoot.deleteRecursively()
        }

        writeText(
            outputRoot.resolve("pack.mcmeta"),
            """
            {
              "pack": {
                "pack_format": 94,
                "supported_formats": {
                  "min_inclusive": 94,
                  "max_inclusive": 94
                },
                "description": "✦ MUMU ✦\n作者 linmumua · QQ 356013496\n加载正常成功"
              }
            }
            """.trimIndent() + "\n"
        )

        val outputAssetsRoot = outputRoot.resolve("assets").resolve(resourceNamespace)
        val itemCardsDir = outputAssetsRoot.resolve("items/cards")
        val itemUiDir = outputAssetsRoot.resolve("items/ui")
        val itemFurnitureDir = outputAssetsRoot.resolve("items/furniture")
        val modelCardsDir = outputAssetsRoot.resolve("models/item/cards")
        val modelUiDir = outputAssetsRoot.resolve("models/item/ui")
        val modelFurnitureDir = outputAssetsRoot.resolve("models/item/furniture")
        val outputCardTextureDir = outputAssetsRoot.resolve("textures/item/cards")
        val jokerTemplateModel = jokerTemplateModelFile.takeIf(File::isFile)?.readText(Charsets.UTF_8)
        val sharedCardTexturePath = "$resourceNamespace:item/cards/joker_2"

        if (jokerTemplateTextureFile.isFile) {
            outputCardTextureDir.mkdirs()
            jokerTemplateTextureFile.copyTo(outputCardTextureDir.resolve("joker_2.png"), overwrite = true)
        }

        cardTextureDir.listFiles()
            ?.filter { it.isFile && it.extension.equals("png", ignoreCase = true) }
            ?.sortedBy { it.nameWithoutExtension }
            ?.forEach { texture ->
                val id = texture.nameWithoutExtension
                writeItemDefinition(itemCardsDir.resolve("$id.json"), "$resourceNamespace:item/cards/$id")
                if (jokerTemplateModel != null && jokerTemplateTextureFile.isFile) {
                    writeCardTemplateModel(modelCardsDir.resolve("$id.json"), jokerTemplateModel, sharedCardTexturePath)
                } else if (jokerTemplateTextureFile.isFile) {
                    writeFlatItemModel(modelCardsDir.resolve("$id.json"), sharedCardTexturePath)
                } else {
                    writeFlatItemModel(modelCardsDir.resolve("$id.json"), "$resourceNamespace:item/cards/$id")
                }
            }

        uiTextureDir.listFiles()
            ?.filter { it.isFile && it.extension.equals("png", ignoreCase = true) }
            ?.sortedBy { it.nameWithoutExtension }
            ?.forEach { texture ->
                val id = texture.nameWithoutExtension
                writeItemDefinition(itemUiDir.resolve("$id.json"), "$resourceNamespace:item/ui/$id")
                writeFlatItemModel(modelUiDir.resolve("$id.json"), "$resourceNamespace:item/ui/$id")
            }

        writeItemDefinition(itemFurnitureDir.resolve("table_visual.json"), "$resourceNamespace:item/furniture/table_visual")
        writeItemDefinition(itemFurnitureDir.resolve("seat_chair.json"), "$resourceNamespace:item/furniture/seat_chair")
        writeTableVisualModel(modelFurnitureDir.resolve("table_visual.json"))
        writeSeatChairModel(modelFurnitureDir.resolve("seat_chair.json"))

        val soundFiles = soundSourceDir.walkTopDown()
            .filter { it.isFile && it.extension.equals("ogg", ignoreCase = true) }
            .sortedBy { it.relativeTo(soundSourceDir).invariantSeparatorsPath }
            .toList()

        val soundsJson = buildString {
            appendLine("{")
            soundFiles.forEachIndexed { index, file ->
                val relative = file.relativeTo(soundSourceDir).invariantSeparatorsPath.removeSuffix(".ogg")
                val suffix = if (index + 1 == soundFiles.size) "" else ","
                appendLine("  ${jsonString(soundEventId(relative))}: {")
                appendLine("    \"replace\": false,")
                appendLine("    \"sounds\": [")
                appendLine("      {\"name\": ${jsonString(relative)}, \"stream\": true}")
                appendLine("    ]")
                appendLine("  }$suffix")
            }
            appendLine("}")
        }
        writeText(outputAssetsRoot.resolve("sounds.json"), soundsJson)
    }
}

val generateCraftEngineBundle = tasks.register("generateCraftEngineBundle") {
    dependsOn(generateResourcePack)
    inputs.dir(resourcePackSourceDir)
    outputs.dir(generatedJarResourcesDir)

    doLast {
        val bundleRoot = generatedJarResourcesDir.get().asFile.resolve("craftengine").resolve(resourceNamespace)
        bundleRoot.deleteRecursively()

        val preparedPackRoot = generatedResourcePackDir.get().asFile
        copyFileTree(preparedPackRoot.resolve("assets"), bundleRoot.resolve("resourcepack/assets"))
        preparedPackRoot.resolve("pack.mcmeta").copyTo(bundleRoot.resolve("resourcepack/pack.mcmeta"), overwrite = true)
        preparedPackRoot.resolve("pack.png").copyTo(bundleRoot.resolve("resourcepack/pack.png"), overwrite = true)

        writeText(
            bundleRoot.resolve("pack.yml"),
            """
            author: linmumua
            version: ${project.version}
            description: "MUMU CraftEngine bundle | 作者 linmumua | QQ 356013496 | 加载正常成功"
            namespace: $resourceNamespace
            """.trimIndent() + "\n"
        )

        val cardIds = cardTextureDir.listFiles()
            ?.filter { it.isFile && it.extension.equals("png", ignoreCase = true) }
            ?.map { it.nameWithoutExtension }
            ?.sorted()
            .orEmpty()

        val uiIds = uiTextureDir.listFiles()
            ?.filter { it.isFile && it.extension.equals("png", ignoreCase = true) }
            ?.map { it.nameWithoutExtension }
            ?.sorted()
            .orEmpty()

        val cardItemsConfig = buildString {
            appendLine("items:")
            cardIds.forEach { id ->
                appendLine("  $resourceNamespace:$id:")
                appendLine("    material: paper")
                appendLine("    data:")
                appendLine("      item-name: <!i><white>${titleFromId(id)}</white>")
                appendLine("    model: $resourceNamespace:item/cards/$id")
            }
        }
        writeText(bundleRoot.resolve("configuration/items/doudizhu/cards.yml"), cardItemsConfig)

        val uiItemsConfig = buildString {
            appendLine("items:")
            uiIds.forEach { id ->
                appendLine("  $resourceNamespace:ui_$id:")
                appendLine("    material: paper")
                appendLine("    data:")
                appendLine("      item-name: <!i><gold>${titleFromId(id)}</gold>")
                appendLine("    model: $resourceNamespace:item/ui/$id")
            }
        }
        writeText(bundleRoot.resolve("configuration/items/doudizhu/ui.yml"), uiItemsConfig)

        val doudizhuCategoryConfig = buildString {
            appendLine("categories:")
            appendLine("  $resourceNamespace:doudizhu:")
            appendLine("    name: <!i><gold>斗地主</gold>")
            appendLine("    icon: $resourceNamespace:big_joker")
            appendLine("    list:")
            cardIds.forEach { id ->
                appendLine("      - $resourceNamespace:$id")
            }
            uiIds.forEach { id ->
                appendLine("      - $resourceNamespace:ui_$id")
            }
            appendLine("      - $resourceNamespace:table_visual")
            appendLine("      - $resourceNamespace:seat_chair")
        }
        writeText(bundleRoot.resolve("configuration/categories.yml"), doudizhuCategoryConfig)

        val furnitureConfig = buildString {
            appendLine("items:")
            appendLine("  $resourceNamespace:table_visual_model:")
            appendLine("    material: paper")
            appendLine("    data:")
            appendLine("      item-name: <!i><gold>Dou Dizhu Table</gold>")
            appendLine("    model: $resourceNamespace:item/furniture/table_visual")
            appendLine("  $resourceNamespace:table_visual:")
            appendLine("    material: paper")
            appendLine("    data:")
            appendLine("      item-name: <!i><gold>Dou Dizhu Table Furniture</gold>")
            appendLine("    model: $resourceNamespace:item/furniture/table_visual")
            appendLine("    behavior:")
            appendLine("      type: furniture_item")
            appendLine("      rules:")
            appendLine("        ground:")
            appendLine("          rotation: four")
            appendLine("          alignment: center")
            appendLine("      furniture:")
            appendLine("        settings:")
            appendLine("          item: $resourceNamespace:table_visual")
            appendLine("          sounds:")
            appendLine("            break: minecraft:block.wood.break")
            appendLine("            place: minecraft:block.wood.place")
            appendLine("            hit: minecraft:block.wood.hit")
            appendLine("        variants:")
            appendLine("          ground:")
            appendLine("            elements:")
            appendLine("              - item: $resourceNamespace:table_visual_model")
            appendLine("                display-transform: none")
            appendLine("                billboard: fixed")
            appendLine("                position: 0,0,0")
            appendLine("                translation: 0,0,0")
            appendLine("                shadow-radius: 0")
            appendLine("                shadow-strength: 0")
            appendLine("  $resourceNamespace:seat_chair_model:")
            appendLine("    material: paper")
            appendLine("    data:")
            appendLine("      item-name: <!i><red>Dou Dizhu Chair</red>")
            appendLine("    model: $resourceNamespace:item/furniture/seat_chair")
            appendLine("  $resourceNamespace:seat_chair:")
            appendLine("    material: paper")
            appendLine("    data:")
            appendLine("      item-name: <!i><red>Dou Dizhu Seat</red>")
            appendLine("    model: $resourceNamespace:item/furniture/seat_chair")
            appendLine("    behavior:")
            appendLine("      type: furniture_item")
            appendLine("      rules:")
            appendLine("        ground:")
            appendLine("          rotation: four")
            appendLine("          alignment: center")
            appendLine("      furniture:")
            appendLine("        settings:")
            appendLine("          item: $resourceNamespace:seat_chair")
            appendLine("          sounds:")
            appendLine("            break: minecraft:block.wood.break")
            appendLine("            place: minecraft:block.wood.place")
            appendLine("            hit: minecraft:block.wood.hit")
            appendLine("        variants:")
            appendLine("          ground:")
            appendLine("            elements:")
            appendLine("              - item: $resourceNamespace:seat_chair_model")
            appendLine("                display-transform: none")
            appendLine("                billboard: fixed")
            appendLine("                position: 0,0,0")
            appendLine("                translation: 0,0,0")
            appendLine("                shadow-radius: 0")
            appendLine("                shadow-strength: 0")
            appendLine("            hitboxes:")
            appendLine("              - type: interaction")
            appendLine("                position: 0,0.2,0")
            appendLine("                width: 0.8")
            appendLine("                height: 1.2")
            appendLine("                blocks-building: false")
            appendLine("                interactive: true")
            appendLine("                invisible: true")
            appendLine("                seats:")
            appendLine("                  - 0,0.1,0 180")
        }
        writeText(bundleRoot.resolve("configuration/furniture.yml"), furnitureConfig)

        val soundFiles = soundSourceDir.walkTopDown()
            .filter { it.isFile && it.extension.equals("ogg", ignoreCase = true) }
            .sortedBy { it.relativeTo(soundSourceDir).invariantSeparatorsPath }
            .toList()

        val soundsConfig = buildString {
            appendLine("sounds:")
            soundFiles.forEach { file ->
                val relative = file.relativeTo(soundSourceDir).invariantSeparatorsPath.removeSuffix(".ogg")
                appendLine("  $resourceNamespace:${soundEventId(relative)}:")
                appendLine("    replace: false")
                appendLine("    sounds:")
                appendLine("      - name: \"$resourceNamespace:$relative\"")
                appendLine("        stream: true")
            }
        }
        writeText(bundleRoot.resolve("configuration/sounds.yml"), soundsConfig)

        val bundleEntries = bundleRoot.walkTopDown()
            .filter(File::isFile)
            .map { it.relativeTo(bundleRoot).invariantSeparatorsPath }
            .sorted()
            .toList()
        writeText(bundleRoot.resolve("_bundle_index.txt"), bundleEntries.joinToString("\n", postfix = "\n"))
    }
}

val zipResourcePack = tasks.register<Zip>("zipResourcePack") {
    dependsOn(generateResourcePack)
    from(generatedResourcePackDir)
    destinationDirectory.set(layout.buildDirectory.dir("distributions"))
    archiveFileName.set("MUZ-resourcepack-${project.version}.zip")
}

val zipCraftEngineBundle = tasks.register<Zip>("zipCraftEngineBundle") {
    dependsOn(generateCraftEngineBundle)
    from(generatedJarResourcesDir.map { it.dir("craftengine/$resourceNamespace") })
    destinationDirectory.set(layout.buildDirectory.dir("distributions"))
    archiveFileName.set("MUZ-craftengine-${project.version}.zip")
}

tasks {
    withType<JavaCompile>().configureEach {
        options.encoding = Charsets.UTF_8.name()
        options.release.set(21)
    }

    processResources {
        dependsOn(generateCraftEngineBundle)
        filteringCharset = Charsets.UTF_8.name()
        from(generatedJarResourcesDir)
        filesMatching("plugin.yml") {
            expand("version" to project.version)
        }
    }

    test {
        useJUnitPlatform()
    }

    assemble {
        dependsOn(reobfJar)
    }

    build {
        dependsOn(zipResourcePack)
        dependsOn(zipCraftEngineBundle)
    }
}
