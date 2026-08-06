import com.github.jengelman.gradle.plugins.shadow.tasks.ShadowJar
import org.gradle.api.tasks.bundling.Zip
import java.awt.image.BufferedImage
import java.io.File
import java.util.jar.JarFile
import javax.imageio.ImageIO

plugins {
    java
    id("org.jetbrains.kotlin.jvm") version "2.3.20"
    // 用 shadow 而不是 TabooLib 打包：本项目源码已完全不 import taboolib，
    // 只需要把 SnakeYAML 内嵌并重定位（见下面 shadowJar 的 relocate）。
    id("com.gradleup.shadow") version "9.3.0"
}

group = "linmumua"
version = "1.10.3"

data class MuzTarget(
    val id: String,
    val paperApiDependency: String,
    val pluginApiVersion: String,
    val javaVersion: Int,












    val resourcePackFormat: Int
)

val supportedMuzTargets = listOf(
    MuzTarget("paper-1.21.11", "1.21.11-R0.1-SNAPSHOT", "1.21.11", 21, 75),
    MuzTarget("paper-26.1.2", "26.1.2.build.74-stable", "26.1.2", 25, 84),
    MuzTarget("paper-26.2", "26.2.build.84-stable", "26.2", 25, 88)
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
val soundSourceDir = resourcePackSourceDir.resolve("assets/$sourceResourceNamespace/sounds")


val tableFurnitureId = "table_large"
val chairFurnitureId = "chair_large"






val botAvatarCharEscape = "\\uf900"
val botAvatarChar = "\uf900"



val botAvatarLandlordCharEscape = "\\uf901"
val botAvatarLandlordChar = "\uf901"
val botAvatarFarmerCharEscape = "\\uf902"
val botAvatarFarmerChar = "\uf902"






val botAvatarDownCodepointStart = 0xF910




















val cardGlyphFont = "minecraft:${resourceNamespace}_cards"
val avatarPixelFont = "minecraft:${resourceNamespace}_avatar"
val botAvatarFont = "minecraft:${resourceNamespace}_bot_avatar"











val cardGlyphCodepointStart = 0xE100
















val cardGlyphHeightTiers = listOf(53, 48, 42, 37, 32)








val cardGlyphDownOffsetTiers = listOf(0, 4, 8, 12, 16, 20, 24, 28, 32, 36, 40, 44, 48, 50, 52)

// 头像行（含跟着头像走的 bot 兜底图标）自己的向下偏移档，与上面牌那张表完全独立。
// 拆两张表是因为头像行永远比牌行深一整个头像盒（10 * avatar-scale），
// 牌行区间 0..52、头像行区间 40..150 几乎不重叠；共用一张表时每一档都要无差别
// 生成三族字形，牌用不到深档、头像用不到浅档，约一半条目是废的。
// 必须与 PackAssets.AVATAR_DOWN_OFFSET_TIERS 逐项一致，否则头像与 bot 码位整体平移。
val avatarDownOffsetTiers = listOf(0, 40, 50, 60, 70, 80, 90, 100, 110, 120, 130, 140, 150)















val avatarPixelCodepointStart = 0xE800
val avatarPixelMinScale = 4
val avatarPixelMaxScale = 10
val avatarHeadPixels = 8



val avatarOutlinedPixels = avatarHeadPixels + 2

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











fun writeOutlinedGlyph(source: File, target: File, argb: Int) {
    val base = ImageIO.read(source)
        ?: error("读不出机器人头像贴图：${source.absolutePath}")
    val pad = 1
    val width = base.width + pad * 2
    val height = base.height + pad * 2
    val out = BufferedImage(width, height, BufferedImage.TYPE_INT_ARGB)

    fun baseOpaqueAt(x: Int, y: Int): Boolean {
        if (x < 0 || y < 0 || x >= base.width || y >= base.height) {
            return false
        }
        return (base.getRGB(x, y) ushr 24) != 0
    }

    for (y in 0 until height) {
        for (x in 0 until width) {
            val bx = x - pad
            val by = y - pad
            if (baseOpaqueAt(bx, by)) {
                out.setRGB(x, y, base.getRGB(bx, by))
                continue
            }
            val touchesIcon = baseOpaqueAt(bx - 1, by)
                || baseOpaqueAt(bx + 1, by)
                || baseOpaqueAt(bx, by - 1)
                || baseOpaqueAt(bx, by + 1)
            if (touchesIcon) {
                out.setRGB(x, y, argb)
            }
        }
    }

    target.parentFile.mkdirs()
    ImageIO.write(out, "png", target)
}















fun writeCardFaceGlyph(source: File, target: File) {
    val base = ImageIO.read(source)
        ?: error("读不出牌面贴图：${source.absolutePath}")

    check(base.width == 79 && base.height == 63) {
        "牌贴图尺寸必须是 79x63（字形裁切坐标按此推导），实际 ${base.width}x${base.height}：${source.absolutePath}"
    }
    val faceWidth = 35
    val faceHeight = 53
    val faceTop = 10
    val out = BufferedImage(faceWidth, faceHeight, BufferedImage.TYPE_INT_ARGB)
    for (y in 0 until faceHeight) {
        for (x in 0 until faceWidth) {
            out.setRGB(x, y, base.getRGB(x, faceTop + y))
        }
    }
    target.parentFile.mkdirs()
    ImageIO.write(out, "png", target)
}












fun writeAvatarPixelGlyph(target: File, scale: Int, row: Int, headPixels: Int) {
    val height = (headPixels - row) * scale
    val out = BufferedImage(scale, height, BufferedImage.TYPE_INT_ARGB)
    val white = 0xFFFFFFFF.toInt()
    for (y in 0 until scale) {
        for (x in 0 until scale) {
            out.setRGB(x, y, white)
        }
    }
    target.parentFile.mkdirs()
    ImageIO.write(out, "png", target)
}

fun titleFromId(id: String): String = id.split('_').joinToString(" ") { part ->
    part.replaceFirstChar { if (it.isLowerCase()) it.titlecase() else it.toString() }
}





fun cardDisplayName(id: String): String {
    if (id == "big_joker") return "大王"
    if (id == "small_joker") return "小王"
    if (id == "card_back") return "牌背"
    val suit = when {
        id.startsWith("clubs_") -> "梅花"
        id.startsWith("diamonds_") -> "方块"
        id.startsWith("hearts_") -> "红桃"
        id.startsWith("spades_") -> "黑桃"
        else -> ""
    }
    if (suit.isEmpty()) return titleFromId(id)
    val rank = when (val raw = id.substringAfter('_')) {
        "jack" -> "J"
        "queen" -> "Q"
        "king" -> "K"
        "ace" -> "A"
        else -> raw
    }
    return suit + rank
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


val embeddedLibraries by configurations.creating

repositories {
    mavenCentral()
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



tasks.named<Jar>("jar") {
    enabled = false
}

tasks.named<ShadowJar>("shadowJar") {
    archiveFileName.set("MUZ-${project.version}-${muzTarget.id}.jar")


    configurations.set(listOf(embeddedLibraries))
    relocate("org.yaml.snakeyaml", "linmumua.doudizhu.libs.snakeyaml")
    duplicatesStrategy = DuplicatesStrategy.EXCLUDE
    exclude("META-INF/*.SF", "META-INF/*.DSA", "META-INF/*.RSA")
}

val verifyRelocatedSnakeYaml = tasks.register("verifyRelocatedSnakeYaml") {
    val pluginJar = tasks.named<ShadowJar>("shadowJar").flatMap { it.archiveFile }
    inputs.file(pluginJar)

    doLast {
        val jarFile = pluginJar.get().asFile
        JarFile(jarFile).use { jar ->
            val relocatedLoaderOptions = "linmumua/doudizhu/libs/snakeyaml/LoaderOptions.class"
            val originalLoaderOptions = "org/yaml/snakeyaml/LoaderOptions.class"
            check(jar.getEntry(relocatedLoaderOptions) != null) {
                "Missing relocated SnakeYAML LoaderOptions in ${jarFile.name}"
            }
            check(jar.getEntry(originalLoaderOptions) == null) {
                "Unrelocated SnakeYAML LoaderOptions remains in ${jarFile.name}"
            }

            val configEntry = checkNotNull(jar.getJarEntry("linmumua/doudizhu/config/MuzYamlConfig.class")) {
                "Missing MuzYamlConfig.class in ${jarFile.name}"
            }
            val configBytecode = jar.getInputStream(configEntry).use { it.readBytes() }.toString(Charsets.ISO_8859_1)
            check(configBytecode.contains("linmumua/doudizhu/libs/snakeyaml/LoaderOptions")) {
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




        val packFormat = muzTarget.resourcePackFormat
        writeText(
            outputRoot.resolve("pack.mcmeta"),
            """
            {
              "pack": {
                "pack_format": $packFormat,
                "min_format": [$packFormat, 0],
                "max_format": [$packFormat, 0],
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


                writeCardFaceGlyph(texture, outputAssetsRoot.resolve("textures/font/cards/$id.png"))
            }



        for (scale in avatarPixelMinScale..avatarPixelMaxScale) {
            for (row in 0 until avatarOutlinedPixels) {
                writeAvatarPixelGlyph(
                    outputAssetsRoot.resolve("textures/font/avatar/pixel_${scale}_$row.png"),
                    scale,
                    row,
                    avatarOutlinedPixels
                )
            }
        }




        val uiTexturesRoot = outputAssetsRoot.resolve("textures/item/ui")
        if (uiTexturesRoot.exists()) {
            uiTexturesRoot.deleteRecursively()
        }






        writeItemDefinition(
            itemFurnitureDir.resolve("$tableFurnitureId.json"),
            "$resourceNamespace:item/furniture/$tableFurnitureId"
        )
        writeItemDefinition(
            itemFurnitureDir.resolve("$chairFurnitureId.json"),
            "$resourceNamespace:item/furniture/$chairFurnitureId"
        )


        check(modelFurnitureDir.resolve("$tableFurnitureId.json").isFile) {
            "缺少桌子模型：resourcepack/assets/$sourceResourceNamespace/models/item/furniture/$tableFurnitureId.json"
        }
        check(modelFurnitureDir.resolve("$chairFurnitureId.json").isFile) {
            "缺少椅子模型：resourcepack/assets/$sourceResourceNamespace/models/item/furniture/$chairFurnitureId.json"
        }

        modelFurnitureDir.resolve("table_visual.json").delete()
        modelFurnitureDir.resolve("seat_chair.json").delete()






        val botAvatarFontDir = outputAssetsRoot.resolve("textures/font")
        val botAvatarBase = botAvatarFontDir.resolve("bot_avatar.png")
        check(botAvatarBase.isFile) {
            "缺少机器人头像贴图：${botAvatarBase.absolutePath}"
        }
        writeOutlinedGlyph(botAvatarBase, botAvatarFontDir.resolve("bot_avatar_landlord.png"), 0xFFFFD24A.toInt())
        writeOutlinedGlyph(botAvatarBase, botAvatarFontDir.resolve("bot_avatar_farmer.png"), 0xFF141414.toInt())


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

        val cardItemsConfig = buildString {
            appendLine("items:")
            cardIds.forEach { id ->
                appendLine("  $resourceNamespace:$id:")
                appendLine("    material: paper")
                appendLine("    data:")
                appendLine("      item_name: <!i>${cardDisplayName(id)}")
                appendLine("    model: $resourceNamespace:item/cards/$id")
            }
        }
        writeText(bundleRoot.resolve("configuration/items/doudizhu/cards.yml"), cardItemsConfig)



        bundleRoot.resolve("configuration/items/doudizhu/ui.yml").delete()

        val doudizhuCategoryConfig = buildString {
            appendLine("categories:")
            appendLine("  $resourceNamespace:doudizhu:")
            appendLine("    name: <!i>斗地主")
            appendLine("    icon: $resourceNamespace:big_joker")
            appendLine("    list:")
            cardIds.forEach { id ->
                appendLine("      - $resourceNamespace:$id")
            }
            appendLine("      - $resourceNamespace:$tableFurnitureId")
            appendLine("      - $resourceNamespace:$chairFurnitureId")
        }
        writeText(bundleRoot.resolve("configuration/categories.yml"), doudizhuCategoryConfig)


























        val cardGlyphImages = buildString {
            cardGlyphHeightTiers.forEachIndexed { heightTier, height ->
                cardGlyphDownOffsetTiers.forEachIndexed { downTier, downOffset ->
                    val tier = heightTier * cardGlyphDownOffsetTiers.size + downTier
                    cardIds.forEachIndexed { index, id ->
                        val codepoint = cardGlyphCodepointStart + tier * cardIds.size + index
                        val charEscape = "\\u%04x".format(codepoint)
                        appendLine("  $resourceNamespace:card_${id}_h${height}_d$downOffset:")
                        appendLine("    height: $height")
                        appendLine("    ascent: ${height - downOffset}")
                        appendLine("    font: $cardGlyphFont")
                        appendLine("    file: $resourceNamespace:font/cards/$id.png")
                        appendLine("    char: $charEscape")
                    }
                }
            }
        }









        // 头像族走 avatarDownOffsetTiers：牌那张表的浅档头像永远用不到，
        // 生成出来只是白占 images.yml 条目与私有区码位。
        val avatarPixelImages = buildString {
            avatarDownOffsetTiers.forEachIndexed { downTier, downOffset ->
                for (scale in avatarPixelMinScale..avatarPixelMaxScale) {
                    for (row in 0 until avatarOutlinedPixels) {
                        val perTier = (avatarPixelMaxScale - avatarPixelMinScale + 1) * avatarOutlinedPixels
                        val index = (scale - avatarPixelMinScale) * avatarOutlinedPixels + row
                        val codepoint = avatarPixelCodepointStart + downTier * perTier + index
                        val charEscape = "\\u%04x".format(codepoint)
                        val size = (avatarOutlinedPixels - row) * scale
                        appendLine("  $resourceNamespace:avatar_px_${scale}_${row}_d$downOffset:")
                        appendLine("    height: $size")
                        appendLine("    ascent: ${size - downOffset}")
                        appendLine("    font: $avatarPixelFont")
                        appendLine("    file: $resourceNamespace:font/avatar/pixel_${scale}_$row.png")
                        appendLine("    char: $charEscape")
                    }
                }
            }
        }




        val botAvatarGlyphs = listOf("bot_avatar" to 10, "bot_avatar_landlord" to 11, "bot_avatar_farmer" to 11)
        // bot 兜底图标画在【头像行】（真人皮肤取不到时的替代），所以跟头像表，不跟牌表。
        // 跟错表会让 bot 玩家的图标和真人头像上下错开一整行。
        // 码位公式里的 (downTier - 1) 与「downTier == 0 跳过」是配套的：档 0 复用最上面
        // 那三个原始码位（桌边座位牌用的就是它们，不能跟着 HUD 沉），所以这里生成的第一条
        // 是 downTier == 1，它必须落在 botAvatarDownCodepointStart + 0 * 3 上。
        val botAvatarDownImages = buildString {
            avatarDownOffsetTiers.forEachIndexed { downTier, downOffset ->
                if (downTier != 0) {
                    botAvatarGlyphs.forEachIndexed { roleIndex, glyph ->
                        val name = glyph.first
                        val height = glyph.second
                        val codepoint = botAvatarDownCodepointStart +
                            (downTier - 1) * botAvatarGlyphs.size + roleIndex
                        val charEscape = "\\u%04x".format(codepoint)
                        appendLine("  $resourceNamespace:${name}_d$downOffset:")
                        appendLine("    height: $height")
                        appendLine("    ascent: ${8 - downOffset}")
                        appendLine("    font: $botAvatarFont")
                        appendLine("    file: $resourceNamespace:font/$name.png")
                        appendLine("    char: $charEscape")
                    }
                }
            }
        }

        writeText(
            bundleRoot.resolve("configuration/images.yml"),
            """
            images:
              $resourceNamespace:bot_avatar:
                height: 10
                ascent: 8
                font: $botAvatarFont
                file: $resourceNamespace:font/bot_avatar.png
                char: $botAvatarCharEscape
              $resourceNamespace:bot_avatar_landlord:
                height: 11
                ascent: 8
                font: $botAvatarFont
                file: $resourceNamespace:font/bot_avatar_landlord.png
                char: $botAvatarLandlordCharEscape
              $resourceNamespace:bot_avatar_farmer:
                height: 11
                ascent: 8
                font: $botAvatarFont
                file: $resourceNamespace:font/bot_avatar_farmer.png
                char: $botAvatarFarmerCharEscape
            """.trimIndent() + "\n" + botAvatarDownImages + cardGlyphImages + avatarPixelImages
        )

        val furnitureConfig = buildString {
            appendLine("items:")
            appendLine("  $resourceNamespace:${tableFurnitureId}_model:")
            appendLine("    material: paper")
            appendLine("    data:")
            appendLine("      item_name: <!i>斗地主桌子模型")
            appendLine("    model:")
            appendLine("      type: minecraft:model")
            appendLine("      path: $resourceNamespace:item/furniture/$tableFurnitureId")
            appendLine("  $resourceNamespace:$tableFurnitureId:")
            appendLine("    material: paper")
            appendLine("    data:")
            appendLine("      item_name: <!i>斗地主桌子")
            appendLine("    model:")
            appendLine("      type: minecraft:model")
            appendLine("      path: $resourceNamespace:item/furniture/$tableFurnitureId")
            appendLine("    behavior:")
            appendLine("      type: furniture_item")
            appendLine("      rules:")
            appendLine("        ground:")
            appendLine("          rotation: four")
            appendLine("          alignment: center")
            appendLine("      furniture:")
            appendLine("        settings:")
            appendLine("          item: $resourceNamespace:$tableFurnitureId")










            appendLine("          hit_times: 2147483647")
            appendLine("          sounds:")
            appendLine("            break: minecraft:block.wood.break")
            appendLine("            place: minecraft:block.wood.place")
            appendLine("            hit: minecraft:block.wood.hit")
            appendLine("        variants:")
            appendLine("          ground:")
            appendLine("            elements:")
            appendLine("              - item: $resourceNamespace:${tableFurnitureId}_model")
            appendLine("                display_transform: none")
            appendLine("                billboard: fixed")



            appendLine("                position: 0,0.5,0")














            appendLine("                translation: 0,0,0")


            appendLine("                scale: 1,1,1")
            appendLine("                shadow_radius: 0")
            appendLine("                shadow_strength: 0")
























            appendLine("            hitboxes:")
            for (offsetZ in listOf("-0.75", "0.75")) {
                for (offsetX in listOf("-0.75", "0.75")) {
                    appendLine("              - type: shulker")
                    appendLine("                position: $offsetX,0,$offsetZ")
                    appendLine("                direction: up")
                        appendLine("                peek: 33")
                        appendLine("                scale: 1")
                    appendLine("                blocks_building: true")
                    appendLine("                interactive: true")
                    appendLine("                invisible: true")
                }
            }
            appendLine("  $resourceNamespace:${chairFurnitureId}_model:")
            appendLine("    material: paper")
            appendLine("    data:")
            appendLine("      item_name: <!i>斗地主椅子模型")
            appendLine("    model:")
            appendLine("      type: minecraft:model")
            appendLine("      path: $resourceNamespace:item/furniture/$chairFurnitureId")
            appendLine("  $resourceNamespace:$chairFurnitureId:")
            appendLine("    material: paper")
            appendLine("    data:")
            appendLine("      item_name: <!i>斗地主椅子")
            appendLine("    model:")
            appendLine("      type: minecraft:model")
            appendLine("      path: $resourceNamespace:item/furniture/$chairFurnitureId")
            appendLine("    behavior:")
            appendLine("      type: furniture_item")
            appendLine("      rules:")
            appendLine("        ground:")
            appendLine("          rotation: four")
            appendLine("          alignment: center")
            appendLine("      furniture:")
            appendLine("        settings:")
            appendLine("          item: $resourceNamespace:$chairFurnitureId")
            appendLine("          hit_times: 2147483647")
            appendLine("          sounds:")
            appendLine("            break: minecraft:block.wood.break")
            appendLine("            place: minecraft:block.wood.place")
            appendLine("            hit: minecraft:block.wood.hit")
            appendLine("        variants:")
            appendLine("          ground:")
            appendLine("            elements:")
            appendLine("              - item: $resourceNamespace:${chairFurnitureId}_model")
            appendLine("                display_transform: none")
            appendLine("                billboard: fixed")

            appendLine("                position: 0,0.5,0")


            appendLine("                translation: 0,0,0")
            appendLine("                scale: 1,1,1")
            appendLine("                shadow_radius: 0")
            appendLine("                shadow_strength: 0")



















            appendLine("            hitboxes:")




                appendLine("              - type: shulker")
                appendLine("                position: 0,0,0")
                appendLine("                direction: up")
                appendLine("                peek: 0")
            appendLine("                scale: 0.8")
            appendLine("                blocks_building: true")
            appendLine("                interactive: true")
            appendLine("                interaction_entity: true")
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
        filesMatching("paper-plugin.yml") {
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
        systemProperty("muz.expectedResourcePackFormat", muzTarget.resourcePackFormat.toString())
    }

    build {
        dependsOn(verifyRelocatedSnakeYaml)
        dependsOn(zipResourcePack)
        dependsOn(zipCraftEngineBundle)
    }
}
