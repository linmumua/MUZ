import org.gradle.api.tasks.bundling.Zip
import java.io.File
import java.util.jar.JarFile

plugins {
    java
    kotlin("jvm") version "2.3.20"
    id("io.izzel.taboolib") version "2.0.38"
}

group = "dev.mumu"
version = "1.9.0"

data class MuzTarget(
    val id: String,
    val paperApiDependency: String,
    val pluginApiVersion: String,
    val javaVersion: Int
)

val supportedMuzTargets = listOf(
    MuzTarget("paper-1.21.11", "1.21.11-R0.1-SNAPSHOT", "1.21.11", 21),
    MuzTarget("paper-26.1.2", "26.1.2.build.74-stable", "26.1.2", 25),
    MuzTarget("paper-26.2", "26.2.build.84-stable", "26.2", 25)
).associateBy(MuzTarget::id)
val muzTargetId = providers.gradleProperty("muzTarget").orElse("paper-26.2").get()
val muzTarget = supportedMuzTargets[muzTargetId]
    ?: throw GradleException("Unsupported muzTarget '$muzTargetId'. Supported targets: ${supportedMuzTargets.keys.joinToString()}")

layout.buildDirectory.set(layout.projectDirectory.dir("build/${muzTarget.id}"))

val sourceResourceNamespace = "doudizhupaper"
val resourceNamespace = "muz"
val generatedJarResourcesDir = layout.buildDirectory.dir("generated/resources/main")
val generatedResourcePackDir = layout.buildDirectory.dir("generated/resourcepack")
val resourcePackSourceDir = layout.projectDirectory.dir("resourcepack").asFile
val cardTextureDir = resourcePackSourceDir.resolve("assets/$sourceResourceNamespace/textures/item/cards")
val uiTextureDir = resourcePackSourceDir.resolve("assets/$sourceResourceNamespace/textures/item/ui")
val soundSourceDir = resourcePackSourceDir.resolve("assets/$sourceResourceNamespace/sounds")

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

fun writeCardModel(target: File, texturePath: String) {
    writeText(
        target,
        """
        {
          "textures": {
            "0": ${jsonString(texturePath)},
            "particle": ${jsonString(texturePath)}
          },
          "elements": [
            {
              "from": [8, 4.25, 5.75],
              "to": [8.25, 10.6, 10.25],
              "rotation": {"x": 0, "y": -90, "z": 0, "origin": [8.125, 8, 8]},
              "faces": {
                "north": {"uv": [2.37658, 2.5981, 2.62658, 2.94304], "texture": "#0"},
                "east": {"uv": [0, 2.53165, 7.08228, 16], "texture": "#0"},
                "south": {"uv": [1.82278, 2.70886, 2.07278, 2.85127], "texture": "#0"},
                "west": {"uv": [6.88608, 2.53165, 13.96835, 16], "texture": "#0"},
                "up": {"uv": [2.95886, 2.69304, 2.70886, 2.5981], "texture": "#0"},
                "down": {"uv": [3.18038, 2.63291, 2.93038, 2.72785], "texture": "#0"}
              }
            }
          ],
          "gui_light": "front",
          "display": {
            "thirdperson_righthand": {"translation": [-2.25, 2.75, 0.75]},
            "thirdperson_lefthand": {"translation": [-2.25, 2.75, 0.75]},
            "firstperson_righthand": {"rotation": [-8, -37, 1], "translation": [0, 4, 0]},
            "firstperson_lefthand": {"rotation": [-8, -37, 1], "translation": [0, 4, 0]},
            "ground": {"translation": [0, 2.5, 0], "scale": [1.28, 1.28, 1.28]},
            "gui": {"rotation": [0, 0, -13], "translation": [-0.25, 1, 0], "scale": [2.09, 2.09, 2.09]},
            "head": {"rotation": [0, -180, 0], "translation": [0.75, 7.5, -7.5], "scale": [1.51, 1.51, 1.51]},
            "fixed": {"rotation": [0, -180, 0], "translation": [0.75, 1, 0], "scale": [1.91, 1.91, 1.91]},
            "on_shelf": {"translation": [0, 1.5, -1.75], "scale": [2.21, 2.21, 2.21]}
          }
        }
        """.trimIndent() + "\n"
    )
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

val embeddedLibraries by configurations.creating

repositories {
    mavenCentral()
    maven("https://repo.tabooproject.org/repository/releases/")
    maven("https://repo.papermc.io/repository/maven-public/")
    maven("https://repo.extendedclip.com/content/repositories/placeholderapi/")
    maven("https://repo.momirealms.net/releases/")
    maven("https://jitpack.io")
}

dependencies {
    compileOnly("io.papermc.paper:paper-api:${muzTarget.paperApiDependency}")
    compileOnly("me.clip:placeholderapi:2.12.2")
    compileOnly("com.github.MilkBowl:VaultAPI:1.7") {
        exclude(group = "org.bukkit", module = "bukkit")
    }
    compileOnly("net.momirealms:craft-engine-bukkit:0.0.67")
    compileOnly("net.momirealms:craft-engine-core:0.0.67")
    implementation("com.google.code.gson:gson:2.11.0")
    implementation("org.yaml:snakeyaml:2.6")
    embeddedLibraries("org.yaml:snakeyaml:2.6")
    implementation("org.xerial:sqlite-jdbc:3.46.1.0")
    compileOnly("com.mysql:mysql-connector-j:8.4.0")
    testImplementation("io.papermc.paper:paper-api:${muzTarget.paperApiDependency}")
    testImplementation(platform("org.junit:junit-bom:5.13.4"))
    testImplementation("org.junit.jupiter:junit-jupiter")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

java {
    toolchain.languageVersion.set(JavaLanguageVersion.of(muzTarget.javaVersion))
    withSourcesJar()
}

tasks.withType<Jar>().configureEach {
    extensions.extraProperties["archivePath"] = archiveFile.get().asFile
}

tasks.named<Jar>("jar") {
    archiveFileName.set("MUZ-${project.version}-${muzTarget.id}.jar")
    from(embeddedLibraries.map { dependency ->
        if (dependency.isDirectory) dependency else zipTree(dependency)
    })
    duplicatesStrategy = DuplicatesStrategy.EXCLUDE
    exclude("META-INF/*.SF", "META-INF/*.DSA", "META-INF/*.RSA")
}

taboolib {
    relocate("org.yaml.snakeyaml", "dev.mumu.doudizhu.libs.snakeyaml")
    version {
        taboolib = "6.2.3"
        coroutines = "1.7.3"
        skipKotlinRelocate = true
        skipTabooLibRelocate = true
    }
    env {
        install(
            "common",
            "common-platform-api"
        )
    }
}

val verifyRelocatedSnakeYaml = tasks.register("verifyRelocatedSnakeYaml") {
    dependsOn("taboolibMainTask")
    val pluginJar = tasks.named<Jar>("jar").flatMap { it.archiveFile }
    inputs.file(pluginJar)

    doLast {
        val jarFile = pluginJar.get().asFile
        JarFile(jarFile).use { jar ->
            val relocatedLoaderOptions = "dev/mumu/doudizhu/libs/snakeyaml/LoaderOptions.class"
            val originalLoaderOptions = "org/yaml/snakeyaml/LoaderOptions.class"
            check(jar.getEntry(relocatedLoaderOptions) != null) {
                "Missing relocated SnakeYAML LoaderOptions in ${jarFile.name}"
            }
            check(jar.getEntry(originalLoaderOptions) == null) {
                "Unrelocated SnakeYAML LoaderOptions remains in ${jarFile.name}"
            }

            val configEntry = checkNotNull(jar.getJarEntry("dev/mumu/doudizhu/config/MuzYamlConfig.class")) {
                "Missing MuzYamlConfig.class in ${jarFile.name}"
            }
            val configBytecode = jar.getInputStream(configEntry).use { it.readBytes() }.toString(Charsets.ISO_8859_1)
            check(configBytecode.contains("dev/mumu/doudizhu/libs/snakeyaml/LoaderOptions")) {
                "MuzYamlConfig does not reference relocated SnakeYAML"
            }
            check(!configBytecode.contains("org/yaml/snakeyaml/LoaderOptions")) {
                "MuzYamlConfig still references server-provided SnakeYAML"
            }
        }
    }
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

        cardTextureDir.listFiles()
            ?.filter { it.isFile && it.extension.equals("png", ignoreCase = true) }
            ?.sortedBy { it.nameWithoutExtension }
            ?.forEach { texture ->
                val id = texture.nameWithoutExtension
                val texturePath = "$resourceNamespace:item/cards/$id"
                writeItemDefinition(itemCardsDir.resolve("$id.json"), texturePath)
                writeCardModel(modelCardsDir.resolve("$id.json"), texturePath)
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
            appendLine("          hit-times: 2147483647")
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
            appendLine("          hit-times: 2147483647")
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
        options.release.set(muzTarget.javaVersion)
    }

    processResources {
        dependsOn(generateCraftEngineBundle)
        filteringCharset = Charsets.UTF_8.name()
        from(generatedJarResourcesDir)
        filesMatching("plugin.yml") {
            expand(
                "version" to project.version,
                "apiVersion" to muzTarget.pluginApiVersion
            )
        }
    }

    test {
        useJUnitPlatform()
        systemProperty("muz.expectedPluginVersion", project.version.toString())
        systemProperty("muz.expectedApiVersion", muzTarget.pluginApiVersion)
    }

    build {
        dependsOn(verifyRelocatedSnakeYaml)
        dependsOn(zipResourcePack)
        dependsOn(zipCraftEngineBundle)
    }
}

