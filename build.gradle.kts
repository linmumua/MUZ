import com.github.jengelman.gradle.plugins.shadow.tasks.ShadowJar
import org.gradle.api.tasks.bundling.Zip
import java.awt.BasicStroke
import java.awt.Color
import java.awt.RenderingHints
import java.awt.geom.Ellipse2D
import java.awt.geom.GeneralPath
import java.awt.image.BufferedImage
import java.io.File
import java.net.HttpURLConnection
import java.net.URI
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.security.MessageDigest
import java.util.LinkedHashMap
import java.util.jar.JarFile
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import java.util.zip.ZipOutputStream
import groovy.json.JsonSlurper
import javax.imageio.ImageIO

plugins {
    java
    id("org.jetbrains.kotlin.jvm") version "2.3.20"
    // 用 shadow 而不是 TabooLib 打包：本项目源码已完全不 import taboolib，
    // 只需要把 SnakeYAML 内嵌并重定位（见下面 shadowJar 的 relocate）。
    id("com.gradleup.shadow") version "9.3.0"
}

group = "linmumua"
version = "1.10.37"

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
val generatedJavaDir = layout.buildDirectory.dir("generated/java")
val resourcePackSourceDir = layout.projectDirectory.dir("resourcepack").asFile
val cardTextureDir = resourcePackSourceDir.resolve("assets/$sourceResourceNamespace/textures/item/cards")
val soundSourceDir = resourcePackSourceDir.resolve("assets/$sourceResourceNamespace/sounds")


val tableFurnitureId = "table_large"
val chairFurnitureId = "chair_large"
val tableGadgetTomatoId = "table_gadget_tomato"
val tableGadgetWaterSheetId = "table_gadget_water_sheet"






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

// ============================================================================
// 字体切分
//
// 档位放开后单族条目数会超过一张字体能装的码位数，必须切多张。
// 容量不是固定值，按各族自己的起点算（见下面 tierFontSlot 的说明）。
//
// 【切分单位是「档」而不是「条」】：同一档内的 55 张牌（或 150 个头像格）必须落在
// 同一张字体里，否则一行 HUD 里的牌会分散在两张字体上，得套两层 <font> 标签才能画完。
// 所以先算「一张字体能装几档」（向下取整），再按档号整除得到字体序号。
// 代价是每张字体尾部浪费不到一档的码位，换来「同档同字体」这个渲染前提。
//
// 字体名：第 0 张沿用原名（muz_cards），之后是 muz_cards_2 / _3 ...
// 保留原名是为了让「只有一张字体」的族（bot_avatar、王冠）与改动前完全一致，
// 也让 diff 里只出现新增字体、不出现全体改名。
fun fontNameOf(baseFont: String, fontIndex: Int): String =
    if (fontIndex == 0) baseFont else "${baseFont}_${fontIndex + 1}"

// 可用码位的上界。再往上是 noncharacter（FFFE/FFFF），且【超过 0xFFFF 就出了 BMP】——
// images.yml 的 char: 用的是 4 位 `\uXXXX` 转义，5 位十六进制会被 YAML 截成 4 位 + 字面量，
// 整族码位错位成豆腐块。所以这是硬上界，checkGlyphCodepoint 逐条兜着。
val maxGlyphCodepoint = 0xFFFD

/**
 * 按档切分字体的地址算式。
 *
 * 【容量按各族自己的起点算，不是固定 8190】：每张切出来的字体都从该族的
 * [codepointStart] 重新起算，所以能装的档数是 `(0xFFFD - codepointStart + 1) / 每档码位数`。
 * 牌族从 0xE100 起只有 7934 个码位可用（144 档/张），头像族从 0xE800 起只有 6142 个（40 档/张）。
 * 若按 0xE000 的 8190 算，装满一张就会冲出 BMP 变成 5 位转义。
 *
 * 也可以让第 1 张之后的字体都从 0xE000 重新起算来榨满容量，但那样「同族不同字体的
 * 码位窗口不一样」，调试时对不上号；字体本身是免费的（CraftEngine 从 images.yml 的
 * font: 字段自动建），多切几张比省码位划算。
 *
 * @param glyphsPerTier 一档占几个码位（牌 55、头像 150、王冠 30、bot 3）
 * @return 该档所属的字体序号，以及该档在这张字体内的起始码位
 */
fun tierFontSlot(tier: Int, glyphsPerTier: Int, codepointStart: Int): Pair<Int, Int> {
    val tiersPerFont = (maxGlyphCodepoint - codepointStart + 1) / glyphsPerTier
    require(tiersPerFont >= 1) {
        "单档占 $glyphsPerTier 个码位，从 0x%04X 起装不下一整档，无法按档切分".format(codepointStart)
    }
    val fontIndex = tier / tiersPerFont
    val tierInFont = tier % tiersPerFont
    return fontIndex to (codepointStart + tierInFont * glyphsPerTier)
}

/** 逐条兜住 BMP 上界。越界就是构建失败，不许静默产出 5 位转义。 */
fun checkGlyphCodepoint(codepoint: Int, family: String, tier: Int) {
    check(codepoint <= maxGlyphCodepoint) {
        "%s 族第 %d 档的码位 0x%X 超出 BMP 上界 0x%04X，images.yml 的 4 位 \\u 转义会错位".format(
            family, tier, codepoint, maxGlyphCodepoint
        )
    }
}











val cardGlyphCodepointStart = 0xE100
















// ============================================================================
// 档位生成参数：范围与步长
//
// 这些值决定资源包里预生成多少套字形。改大/改密 = 服主能调的值更自由，
// 代价是 images.yml 条目数按乘法增长（牌是 55 张 × H 档高 × M 档偏移）。
//
// 全部可用 -P 覆盖，例如：
//   ./gradlew build -PmuzAvatarOffsetStep=1        精确到 1 像素（条目数翻倍）
//   ./gradlew build -PmuzCardHeightStep=4          省体积（注意会让 53/37 命中不到）
//   ./gradlew build -PmuzAvatarOffsetMin=24        砍掉必然重叠的浅档
// ============================================================================
fun tierParam(name: String, fallback: Int): Int =
    (findProperty(name) as String?)?.toIntOrNull() ?: fallback

// 牌面高。步长【必须是 1】：现有 5 个策划值 53/48/42/37/32 里 53 和 37 是奇数，
// 步长 2 的偶数网格命中不到它们，默认牌面高会静默变掉。范围收窄到 32..56 补偿条目数。
val cardHeightMin = tierParam("muzCardHeightMin", 32)
val cardHeightMax = tierParam("muzCardHeightMax", 56)
val cardHeightStep = tierParam("muzCardHeightStep", 1)

// 牌行向下偏移。牌行贴屏幕顶部，80 已经能把它压到屏幕中上部；默认每 1 像素一档，
// 让 Debug Web 的拖动与运行期合法档位不再有 1 像素吸附误差。
val cardOffsetMin = tierParam("muzCardOffsetMin", 0)
val cardOffsetMax = tierParam("muzCardOffsetMax", 80)
val cardOffsetStep = tierParam("muzCardOffsetStep", 1)

// 头像行向下偏移。必须比牌行深一整个头像盒（12 * avatar-scale，最大 192），
// 所以范围天然要大得多：最坏组合 80 + 192 = 272，400 留足余量；默认每 1 像素一档。
val avatarOffsetMin = tierParam("muzAvatarOffsetMin", 0)
val avatarOffsetMax = tierParam("muzAvatarOffsetMax", 400)
val avatarOffsetStep = tierParam("muzAvatarOffsetStep", 1)

// 记牌器自己的向下偏移表。它跟头像行同样覆盖 0..400，但只保留每 2 像素一档：
// counter 的 22 个声明按这张表分配，不能再借用头像表，否则头像步长改成 1 会把旧的
// 201 档 × 22 码位契约扩大成 401 档，并改变默认字体/PNG 资源数量。
val counterOffsetMin = tierParam("muzCounterOffsetMin", 0)
val counterOffsetMax = tierParam("muzCounterOffsetMax", 400)
val counterOffsetStep = tierParam("muzCounterOffsetStep", 2)

/** 按 [min]..[max] 步长 [step] 生成升序档位表；[max] 不在网格上时也会被包含。 */
fun tiersOf(min: Int, max: Int, step: Int): List<Int> {
    require(step >= 1) { "档位步长必须 >= 1，收到 $step" }
    require(min <= max) { "档位范围颠倒：$min..$max" }
    val values = (min..max step step).toMutableList()
    if (values.last() != max) {
        values.add(max)
    }
    return values
}

/**
 * 构建期资源 profile 的最小 YAML 读取器。
 *
 * <p>profile 是显式构建输入，不读取服务端 config.yml，避免构建结果依赖部署目录。
 * 只接受本文件约定的 version、分段和整数列表；复杂 YAML 结构应在 profile 之外维护，
 * 这样错误会在配置阶段直接失败，而不是静默生成全量资源。
 */
data class MuzResourceProfile(
    val version: Int,
    val cardHeights: List<Int>,
    val cardOffsets: List<Int>,
    val avatarScales: List<Int>,
    val avatarOffsets: List<Int>,
    val counterScales: List<Int>,
    val counterOffsets: List<Int>,
    val hotbarScale: Int
)

fun parseProfileIntList(value: String, path: String, source: File, lineNumber: Int): List<Int> {
    val text = value.trim()
    require(text.startsWith("[") && text.endsWith("]")) {
        "$source:$lineNumber 的 $path 必须是 YAML 整数列表，例如 [53, 48]"
    }
    val body = text.substring(1, text.length - 1).trim()
    require(body.isNotEmpty()) { "$source:$lineNumber 的 $path 不能为空" }
    return body.split(',').mapIndexed { index, item ->
        item.trim().toIntOrNull()
            ?: throw GradleException("$source:$lineNumber 的 $path 第 ${index + 1} 项不是整数：$item")
    }
}

fun readMuzResourceProfile(source: File): MuzResourceProfile {
    require(source.isFile) {
        "缺少版本化资源 profile：${source.absolutePath}；请通过 -PmuzResourceProfile=<path> 指定 YAML 文件"
    }
    val scalars = linkedMapOf<String, Int>()
    val lists = linkedMapOf<String, List<Int>>()
    var section = ""
    var pendingList: String? = null
    source.readLines(Charsets.UTF_8).forEachIndexed { index, raw ->
        val lineNumber = index + 1
        val withoutComment = raw.substringBefore('#').trimEnd()
        if (withoutComment.trim().isEmpty()) return@forEachIndexed
        val text = withoutComment.trim()
        if (text.startsWith("- ")) {
            val path = pendingList ?: throw GradleException("$source:$lineNumber 的 YAML 列表没有字段名")
            val item = text.substring(2).trim().toIntOrNull()
                ?: throw GradleException("$source:$lineNumber 的 $path 列表项不是整数")
            lists[path] = lists.getValue(path) + item
            return@forEachIndexed
        }
        val colon = text.indexOf(':')
        require(colon > 0) { "$source:$lineNumber 不是受支持的 YAML 键值：$text" }
        val key = text.substring(0, colon).trim()
        val value = text.substring(colon + 1).trim()
        val indent = withoutComment.length - withoutComment.trimStart().length
        if (value.isEmpty()) {
            if (indent == 0) {
                section = key
                pendingList = null
            } else {
                val path = if (section.isEmpty()) key else "$section.$key"
                lists[path] = emptyList()
                pendingList = path
            }
            return@forEachIndexed
        }
        if (indent == 0) section = ""
        val path = if (indent == 0 || section.isEmpty()) key else "$section.$key"
        pendingList = null
        if (path == "version" || path == "hotbar.scale") {
            scalars[path] = value.trim('"', '\'').toIntOrNull()
                ?: throw GradleException("$source:$lineNumber 的 $path 不是整数")
        } else {
            lists[path] = parseProfileIntList(value, path, source, lineNumber)
        }
    }
    val requiredLists = setOf(
        "card.heights", "card.offsets", "avatar.scales", "avatar.offsets",
        "counter.scales", "counter.offsets"
    )
    require(scalars["version"] == 1) {
        "$source 必须声明 version: 1，收到 ${scalars["version"] ?: "缺失"}"
    }
    require(lists.keys == requiredLists) {
        val missing = requiredLists - lists.keys
        val unknown = lists.keys - requiredLists
        throw GradleException("$source 的 profile 字段不完整；缺少=$missing，未知=$unknown")
    }
    require(scalars.keys == setOf("version", "hotbar.scale")) {
        val unknown = scalars.keys - setOf("version", "hotbar.scale")
        throw GradleException("$source 缺少 hotbar.scale 或存在未知标量字段：$unknown")
    }
    fun nonEmpty(path: String): List<Int> = lists.getValue(path).also {
        require(it.isNotEmpty()) { "$source 的 $path 不能为空" }
        require(it.distinct().size == it.size) { "$source 的 $path 不能包含重复档位：$it" }
    }
    return MuzResourceProfile(
        version = scalars.getValue("version"),
        cardHeights = nonEmpty("card.heights"),
        cardOffsets = nonEmpty("card.offsets"),
        avatarScales = nonEmpty("avatar.scales"),
        avatarOffsets = nonEmpty("avatar.offsets"),
        counterScales = nonEmpty("counter.scales"),
        counterOffsets = nonEmpty("counter.offsets"),
        hotbarScale = scalars["hotbar.scale"] ?: throw GradleException("$source 缺少 hotbar.scale")
    )
}

val resourceProfilePath = providers.gradleProperty("muzResourceProfile").orElse("muz-resource-profile.yml").get()
val resourceProfileFile = layout.projectDirectory.file(resourceProfilePath).asFile
val resourceProfile = readMuzResourceProfile(resourceProfileFile)

// profile 只允许现有构建白名单中的值；不会把非法值静默吸附到邻近档位。
fun requireProfileTiers(name: String, selected: List<Int>, allowed: List<Int>): List<Int> = selected.also {
    val invalid = it.filterNot(allowed::contains)
    require(invalid.isEmpty()) {
        "资源 profile 的 $name 含未生成白名单档位 $invalid；可用值：${allowed.joinToString()}"
    }
}

val cardHeightAllowedTiers = tiersOf(cardHeightMin, cardHeightMax, cardHeightStep)
val cardOffsetAllowedTiers = tiersOf(cardOffsetMin, cardOffsetMax, cardOffsetStep)
val avatarOffsetAllowedTiers = tiersOf(avatarOffsetMin, avatarOffsetMax, avatarOffsetStep)
val counterOffsetAllowedTiers = tiersOf(counterOffsetMin, counterOffsetMax, counterOffsetStep)

// 降序：与旧表方向一致，减少 diff 噪音；集合内容完全由 profile 指定。
val cardGlyphHeightTiers = requireProfileTiers("card.heights", resourceProfile.cardHeights, cardHeightAllowedTiers)
    .sortedDescending()
val cardGlyphDownOffsetTiers = requireProfileTiers("card.offsets", resourceProfile.cardOffsets, cardOffsetAllowedTiers)
    .sorted()


// 头像行（含跟着头像走的 bot 兜底图标）自己的向下偏移档，与上面牌那张表完全独立。
// 拆两张表是因为头像行永远比牌行深一整个头像盒（12 * avatar-scale，含王冠那 2 行），
// 牌行区间 0..80、头像行要到 400；共用一张表时每一档都要无差别生成三族字形，
// 牌用不到深档，约一半条目是废的。
//
// 这张表连同下面的 scale 范围会被写进生成的 PackTiers.java（见 generatePackTiers 任务），
// 插件端直接读那份生成结果 —— 不再有「两处手写的表必须逐项一致」这种隐患。
val avatarDownOffsetTiers = requireProfileTiers(
    "avatar.offsets", resourceProfile.avatarOffsets, avatarOffsetAllowedTiers
).sorted()

// 记牌器独立表：不再默认生成 0..400 全量档，只生成 profile 指定的偏移。
val counterDownOffsetTiers = requireProfileTiers(
    "counter.offsets", resourceProfile.counterOffsets, counterOffsetAllowedTiers
).sorted()















val avatarPixelCodepointStart = 0xE800
val avatarPixelMinScale = tierParam("muzAvatarScaleMin", 2)
val avatarPixelMaxScale = tierParam("muzAvatarScaleMax", 16)
val avatarScaleAllowedTiers = (avatarPixelMinScale..avatarPixelMaxScale).toList()
val avatarPixelScaleTiers = requireProfileTiers(
    "avatar.scales", resourceProfile.avatarScales, avatarScaleAllowedTiers
).sorted()
requireProfileTiers("counter.scales", resourceProfile.counterScales, listOf(75, 100, 125))
require(resourceProfile.hotbarScale in listOf(75, 100, 125)) {
    "资源 profile 的 hotbar.scale=${resourceProfile.hotbarScale} 不在可用值 75/100/125 内"
}
val avatarHeadPixels = 8



val avatarOutlinedPixels = avatarHeadPixels + 2

// 王冠占的行数。王冠是【独立字形家族】，画在头像 row 0 的【上方】而不是盖住它：
// 王冠 row 0 落在 A+11*scale .. A+12*scale，row 1 落在 A+10*scale .. A+11*scale，
// 正好接在头像 row 0（A+9*scale .. A+10*scale）之上，底边锚点 A 与头像完全相同。
// 这就是「凸出来，只是底边相同」。
val avatarCrownPixels = 2

// 头像行整体占多高（含王冠）。不重叠判定必须用这个而不是 avatarOutlinedPixels ——
// 王冠比头像盒顶还高 2*scale，按 10 算会漏报，地主的王冠会压进牌行且不报警。
val avatarRowTotalPixels = avatarOutlinedPixels + avatarCrownPixels

val avatarCrownFont = "minecraft:${resourceNamespace}_avatar_crown"
val avatarCrownCodepointStart = 0xE000



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












/**
 * 点数字形的渲染高度与笔画宽度。
 *
 * 用矢量路径而不是系统字体：Font.SANS_SERIF 之类的逻辑字体在不同操作系统上会
 * fallback 到不同物理字体，同一份源码在 Windows 和 Linux 构建机上会产出不同的
 * PNG。资源包字形必须逐字节确定，所以这里把笔画直接描出来。
 */
val rankGlyphHeight = 48
val rankGlyphStroke = 7f

/** 窄字形（除 10、王 以外的全部点数、以及单个数字）的宽度。 */
fun rankGlyphWidth(id: String): Int =
    if (id == "10" || id == "王") rankGlyphHeight else (rankGlyphHeight * 0.62f).toInt()

/**
 * 在 [ox, ox+cw] × [0, rankGlyphHeight] 的框里描一个字符的笔画。
 *
 * 只认单字符：10 由调用方拆成 "1" 和 "0" 两次描。
 */
fun appendRankStrokes(path: GeneralPath, c: String, ox: Float, cw: Float) {
    val m = rankGlyphStroke * 0.75f          // 内缩，给圆头笔画留出溢出量
    val x0 = ox + m
    val x1 = ox + cw - m
    val y0 = m
    val y1 = rankGlyphHeight - m
    val xm = (x0 + x1) / 2
    val ym = (y0 + y1) / 2
    val w = x1 - x0
    val h = y1 - y0
    when (c) {
        "0" -> path.append(Ellipse2D.Float(x0, y0, w, h), false)
        "1" -> {
            path.moveTo(xm - w * 0.3f, y0 + h * 0.22f); path.lineTo(xm, y0); path.lineTo(xm, y1)
        }
        "2" -> {
            path.moveTo(x0, y0 + h * 0.28f)
            path.curveTo(x0, y0, x1, y0, x1, y0 + h * 0.30f)
            path.curveTo(x1, y0 + h * 0.52f, x0, y0 + h * 0.62f, x0, y1)
            path.lineTo(x1, y1)
        }
        "3" -> {
            path.moveTo(x0, y0); path.lineTo(x1, y0); path.lineTo(x0 + w * 0.45f, ym)
            path.curveTo(x1, ym, x1, y1, x0 + w * 0.15f, y1)
        }
        "4" -> {
            path.moveTo(x1 - w * 0.22f, y1); path.lineTo(x1 - w * 0.22f, y0)
            path.lineTo(x0, y0 + h * 0.66f); path.lineTo(x1, y0 + h * 0.66f)
        }
        "5" -> {
            path.moveTo(x1, y0); path.lineTo(x0, y0); path.lineTo(x0, ym - h * 0.06f)
            path.curveTo(x1, ym - h * 0.16f, x1, y1, x0, y1)
        }
        "6" -> {
            path.moveTo(x1, y0)
            path.curveTo(x0 - w * 0.1f, y0 + h * 0.15f, x0, ym, x0, y0 + h * 0.72f)
            path.append(Ellipse2D.Float(x0, y0 + h * 0.48f, w, h * 0.52f), false)
        }
        "7" -> {
            path.moveTo(x0, y0); path.lineTo(x1, y0); path.lineTo(x0 + w * 0.30f, y1)
        }
        "8" -> {
            path.append(Ellipse2D.Float(x0 + w * 0.08f, y0, w * 0.84f, h * 0.46f), false)
            path.append(Ellipse2D.Float(x0, y0 + h * 0.44f, w, h * 0.56f), false)
        }
        "9" -> {
            path.moveTo(x0, y1)
            path.curveTo(x1 + w * 0.1f, y1 - h * 0.15f, x1, ym, x1, y0 + h * 0.28f)
            path.append(Ellipse2D.Float(x0, y0, w, h * 0.52f), false)
        }
        "J" -> {
            path.moveTo(x1, y0); path.lineTo(x1, y0 + h * 0.74f)
            path.curveTo(x1, y1, x0, y1, x0, y0 + h * 0.74f)
        }
        "Q" -> {
            path.append(Ellipse2D.Float(x0, y0, w, h - h * 0.08f), false)
            path.moveTo(xm + w * 0.10f, y1 - h * 0.30f); path.lineTo(x1, y1)
        }
        "K" -> {
            path.moveTo(x0, y0); path.lineTo(x0, y1)
            path.moveTo(x1, y0); path.lineTo(x0, ym + h * 0.06f)
            path.moveTo(x0 + w * 0.34f, ym - h * 0.06f); path.lineTo(x1, y1)
        }
        "A" -> {
            path.moveTo(x0, y1); path.lineTo(xm, y0); path.lineTo(x1, y1)
            path.moveTo(x0 + w * 0.20f, y0 + h * 0.66f); path.lineTo(x1 - w * 0.20f, y0 + h * 0.66f)
        }
        // 王：三横一竖。双王只在记牌行出现，用符号而不是牌面缩略图——
        // 牌面是 79x63 的完整卡面（含边框与水印），缩到 48px 高会糊成一团，
        // 且不透明像素压 RGB 后置灰变体会变成一块实心灰方块。
        "王" -> {
            path.moveTo(x0, y0); path.lineTo(x1, y0)
            path.moveTo(x0 + w * 0.12f, ym); path.lineTo(x1 - w * 0.12f, ym)
            path.moveTo(x0, y1); path.lineTo(x1, y1)
            path.moveTo(xm, y0); path.lineTo(xm, y1)
        }
        else -> error("没有这个字形的笔画定义：$c")
    }
}

/** 画一个点数字形（3..10 J Q K A 2）或单个数字（0..9）。 */
fun renderRankGlyph(id: String, color: Color = Color.WHITE): BufferedImage {
    val width = rankGlyphWidth(id)
    val out = BufferedImage(width, rankGlyphHeight, BufferedImage.TYPE_INT_ARGB)
    val g = out.createGraphics()
    g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
    g.setRenderingHint(RenderingHints.KEY_STROKE_CONTROL, RenderingHints.VALUE_STROKE_PURE)
    g.color = color
    g.stroke = BasicStroke(rankGlyphStroke, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND)
    val path = GeneralPath()
    if (id == "10") {
        // 两个字符挤进一个双宽格子：1 靠左，0 右移到 40% 处。
        appendRankStrokes(path, "1", 0f, rankGlyphHeight * 0.62f)
        appendRankStrokes(path, "0", rankGlyphHeight * 0.40f, rankGlyphHeight * 0.62f)
    } else {
        appendRankStrokes(path, id, 0f, width.toFloat())
    }
    g.draw(path)
    g.dispose()
    return out
}

// ============================================================================
// 记牌行分层字形族
//
// 【每档固定 22 个 glyph】：标签下标 0..14、数字 15..19、普通/耗尽框 20..21。
// 码位排列不等于绘制顺序；TrickHudView 按 label → frame → digit 输出，后层按紧凑 advance=22 回退。
// 标签/框/数字是三层独立贴图，同一档只改变 ascent，不为「每个点数 × 每个已出数」
// 生成组合 PNG，因此不会再出现 30,150 张组合资源。所有图形都由确定性整数像素字模绘制，
// 不依赖构建机上的系统字体。
// ============================================================================
val counterGlyphFont = "minecraft:${resourceNamespace}_counter"
val counterGlyphCodepointStart = 0xE900
// 紧凑仪表盘基础几何：标签/框 21×12、数字 21×8；三层通过 ascent 叠到同一格。
// 这组值必须与 PackAssets 的 counter 几何常量、CraftEngine images 声明保持一致。
val counterGlyphLabelWidth = 21
val counterGlyphLabelHeight = 12
val counterGlyphDigitCount = 5
val counterGlyphFrameCount = 2
val counterGlyphDigitWidth = 21
val counterGlyphDigitHeight = 8
val counterGlyphDigitAscent = -6
val counterGlyphFrameWidth = 21
val counterGlyphFrameHeight = 12
val counterGlyphFrameAscent = -3
val counterGlyphLabelAscent = 12
val counterGlyphAdvance = 22

// Hotbar HUD 三张独立物品图标：鸡蛋、水桶、番茄。每张图标独立占用 20×22，
// 图标之间留 4px，水平 step=24；三张图标视觉宽度 68px，整体 advance=69。
// 【必须与 PackAssets.hotbarIcon* / HOTBAR_* 几何保持一致】
val hotbarHudFont = "minecraft:${resourceNamespace}_hotbar"
val hotbarIconNames = listOf("egg", "water", "tomato")
val hotbarIconCount = hotbarIconNames.size
val hotbarIconGlyphWidth = 20
val hotbarIconGlyphHeight = 22
val hotbarIconStep = 24
val hotbarIconAdvance = hotbarIconGlyphWidth + 1
val hotbarHudGlyphWidth = hotbarIconGlyphWidth * hotbarIconCount + (hotbarIconCount - 1) * (hotbarIconStep - hotbarIconGlyphWidth)
val hotbarHudGlyphHeight = hotbarIconGlyphHeight
val hotbarHudSlotCount = hotbarIconCount
val hotbarHudGlyphAdvance = hotbarHudGlyphWidth + 1
// Hotbar 三图标基础 ascent；必须与 PackAssets.HOTBAR_BASE_ASCENT 和
// HotbarDebugOverlayWriter.BASE_ASCENT 保持同源，offset-y=0 时构建期/运行期位置重合。
val hotbarBaseAscent = -43

// 0xEF02：hotbar HUD「选中槽」高亮框字形（muz:font/hotbar_select.png）。
// 【必须与 PackAssets.HOTBAR_SELECT_CODEPOINT / WIDTH / HEIGHT / ADVANCE 保持一致】
// 选框继续独立于三张图标，运行期由 HotbarHudService 用零净前进量夹心定位到 0..2 图标。
val hotbarSelectFont = hotbarHudFont
val hotbarSelectCodepoint = 0xEF02
val hotbarSelectCharEscape = "\\uef02"
val hotbarSelectGlyphWidth = 20
val hotbarSelectGlyphHeight = 22
val hotbarSelectGlyphAdvance = hotbarSelectGlyphWidth + 1

// counter/hotbar 的构建期缩放档。每个 hotbar scale 保持原有窗口起点：75% 从 EF10、
// 100% 从 EF04、125% 从 EF20；每个窗口依次放置三基础图标、三 overlay 图标、两选框。
// EF00/EF01 旧九槽底板不再生成或发布。数组会写入 PackTiers.java，PackAssets 读取同源结果。
val supportedHudScales = listOf(75, 100, 125)
val allCounterScaleTiers = supportedHudScales
val allHotbarScaleTiers = supportedHudScales
val allCounterScaleCodepointStarts = listOf(0xED00, counterGlyphCodepointStart, 0xEE00)
val allHotbarScaleBaseCodepoints = listOf(0xEF10, 0xEF04, 0xEF20)
val allHotbarScaleDebugCodepoints = listOf(0xEF13, 0xEF07, 0xEF23)
val allHotbarScaleSelectCodepoints = listOf(0xEF16, hotbarSelectCodepoint, 0xEF26)
// 选框 overlay 码位紧跟在各 scale 窗口末尾；默认 100% 继续为 EF03。
val allHotbarScaleSelectDebugCodepoints = listOf(0xEF17, 0xEF03, 0xEF27)
val allHotbarCodepoints = buildList {
    allHotbarScaleBaseCodepoints.forEach { start -> addAll(start until start + hotbarIconCount) }
    allHotbarScaleDebugCodepoints.forEach { start -> addAll(start until start + hotbarIconCount) }
    addAll(allHotbarScaleSelectCodepoints)
    addAll(allHotbarScaleSelectDebugCodepoints)
}
check(allHotbarCodepoints.size == allHotbarCodepoints.toSet().size) {
    "hotbar 基础/overlay/选框码位发生冲突：${allHotbarCodepoints}"
}
check(allHotbarCodepoints.none { it == 0xEF00 || it == 0xEF01 }) {
    "旧九槽 hotbar 码位 EF00/EF01 不得重新发布"
}
allHotbarCodepoints.forEach { checkGlyphCodepoint(it, "hotbar", 0) }
val defaultHudScale = 100
val counterScaleTiers = resourceProfile.counterScales.sorted()
val hotbarScaleTiers = listOf(resourceProfile.hotbarScale)
val counterScaleCodepointStarts = counterScaleTiers.map { allCounterScaleCodepointStarts[allCounterScaleTiers.indexOf(it)] }
val hotbarScaleBaseCodepoints = hotbarScaleTiers.map { allHotbarScaleBaseCodepoints[allHotbarScaleTiers.indexOf(it)] }
val hotbarScaleDebugCodepoints = hotbarScaleTiers.map { allHotbarScaleDebugCodepoints[allHotbarScaleTiers.indexOf(it)] }
val hotbarScaleSelectCodepoints = hotbarScaleTiers.map { allHotbarScaleSelectCodepoints[allHotbarScaleTiers.indexOf(it)] }
val hotbarScaleSelectDebugCodepoints = hotbarScaleTiers.map { allHotbarScaleSelectDebugCodepoints[allHotbarScaleTiers.indexOf(it)] }

fun scaleFontName(baseFont: String, scale: Int): String =
    if (scale == defaultHudScale) baseFont else "${baseFont}_s$scale"

fun scaleIndex(scale: Int, scales: List<Int>): Int = scales.indexOf(scale).also {
    require(it >= 0) { "不支持的 HUD scale：$scale；可用值：${scales.joinToString()}" }
}

/** 以最近整数缩放像素几何；构建与 PackAssets 侧必须使用同一舍入规则。 */
fun scaledPixels(value: Int, scale: Int): Int = maxOf(1, Math.round(value * scale / 100f))

/** 缩放带符号的 ascent，负值也按最近整数而不是截断。 */
fun scaledSigned(value: Int, scale: Int): Int = Math.round(value * scale / 100f)

// 点数字形文件名，顺序【就是 CardRank 枚举序】（3..2、小、大）。王牌使用专用
// 9x9 方正像素标签，使用单字“小”“大”表达大小王，避免依赖系统中文字体，
// 同时保留 CardRank.ordinal() 映射契约。
val counterRankGlyphFiles = listOf(
    "label_3", "label_4", "label_5", "label_6", "label_7", "label_8", "label_9", "label_10",
    "label_j", "label_q", "label_k", "label_a", "label_2", "label_small", "label_big"
)
val counterRankGlyphSymbols = listOf(
    "3", "4", "5", "6", "7", "8", "9", "10",
    "J", "Q", "K", "A", "2", "small", "big"
)

/** 一档内 22 个字形的排列顺序，也就是码位顺序。 */
val counterGlyphFiles: List<String> = counterRankGlyphFiles + (0 until counterGlyphDigitCount).map { "digit_$it" } + listOf("frame_normal", "frame_exhausted")

check(counterRankGlyphFiles.size == 15 && counterRankGlyphSymbols.size == 15) {
    "记牌器标签必须严格保持 15 个且与 CardRank 顺序一一对应"
}
check(counterGlyphFiles.size == counterRankGlyphFiles.size + counterGlyphFrameCount + counterGlyphDigitCount) {
    "记牌器每档必须严格占用 22 个字形"
}

/**
 * 记牌器全部 label/digit 使用固定整数像素字模；禁止依赖构建机字体、抗锯齿或浮点描边。
 *
 * label 盒为 21x12，采用 7x9（10 为 11x9、小/大为 9x9）；digit 盒为 21x8，采用 5x7。
 * 所有可见像素都是纯白不透明，右下角 alpha=1 只用于锁定 BitmapProvider 的实际宽度。
 */
val counterBitmapLabels: Map<String, Array<String>> = mapOf(
    "2" to arrayOf("0111110", "1100011", "0000011", "0000110", "0001100", "0011000", "0110000", "1100000", "1111111"),
    "3" to arrayOf("1111110", "0000011", "0000011", "0011110", "0000011", "0000011", "0000011", "0000011", "1111110"),
    "4" to arrayOf("0001110", "0011110", "0110110", "1100110", "1111111", "0000110", "0000110", "0000110", "0000110"),
    "5" to arrayOf("1111111", "1100000", "1100000", "1111110", "0000011", "0000011", "0000011", "0000011", "1111110"),
    "6" to arrayOf("0011110", "0110000", "1100000", "1100000", "1111110", "1100011", "1100011", "1100011", "0111110"),
    "7" to arrayOf("1111111", "0000011", "0000110", "0001100", "0011000", "0110000", "0110000", "0110000", "0110000"),
    "8" to arrayOf("0111110", "1100011", "1100011", "0111110", "1100011", "1100011", "1100011", "1100011", "0111110"),
    "9" to arrayOf("0111110", "1100011", "1100011", "1100011", "0111111", "0000011", "0000011", "0000110", "1111100"),
    "10" to arrayOf("00100011110", "01100011011", "00100011011", "00100011011", "00100011011", "00100011011", "00100011011", "00100011011", "01111001110"),
    "J" to arrayOf("0000110", "0000110", "0000110", "0000110", "0000110", "0000110", "1100110", "1100110", "0111100"),
    "Q" to arrayOf("0111110", "1100011", "1100011", "1100011", "1100011", "1101011", "0111110", "0000110", "0000011"),
    "K" to arrayOf("1100110", "1101100", "1111000", "1110000", "1110000", "1111000", "1101100", "1100110", "1100011"),
    "A" to arrayOf("0011100", "0111110", "1100110", "1100110", "1111110", "1100110", "1100110", "1100110", "1100110"),
    // 小 / 大：9×9 固定像素字模，仅用于牌类标签上行；下行数字仍由 digit bitmap 提供。
    "small" to arrayOf("000100000", "000100000", "100100001", "010111010", "001100100", "000100000", "001000100", "010000010", "100000001"),
    "big" to arrayOf("000100000", "000100000", "111111111", "000100000", "001010000", "010001000", "100000100", "000000010", "000000001")
)

val counterBitmapDigits: Map<Char, Array<String>> = mapOf(
    '0' to arrayOf("01110", "11011", "11011", "11011", "11011", "11011", "01110"),
    '1' to arrayOf("00110", "01110", "00110", "00110", "00110", "00110", "01111"),
    '2' to arrayOf("01110", "11011", "00011", "00110", "01100", "11000", "11111"),
    '3' to arrayOf("11110", "00011", "00011", "01110", "00011", "00011", "11110"),
    '4' to arrayOf("00110", "01110", "11010", "11010", "11111", "00010", "00010")
)

fun renderCounterBitmap(bitmap: Array<String>, width: Int, height: Int, name: String): BufferedImage {
    val bitmapWidth = bitmap.maxOf { it.length }
    check(bitmap.all { it.length == bitmapWidth }) { "记牌器 bitmap 字形行宽必须一致：$name" }
    check(width >= bitmapWidth && height >= bitmap.size) {
        "记牌器 bitmap 盒 ${width}x${height} 装不下 $name 的 ${bitmapWidth}x${bitmap.size} 字身"
    }
    val out = BufferedImage(width, height, BufferedImage.TYPE_INT_ARGB)
    val left = (width - bitmapWidth) / 2
    val top = (height - bitmap.size) / 2
    bitmap.forEachIndexed { row, pixels ->
        pixels.forEachIndexed { column, pixel ->
            if (pixel == '1') out.setRGB(left + column, top + row, 0xFFFFFFFF.toInt())
        }
    }
    out.setRGB(width - 1, height - 1, 0x01FFFFFF)
    return out
}

/** 生成记牌器 label；label 与 digit 不能按字符串共用入口，因为 2/3/4 同时存在于两套字模。 */
fun renderCounterLabel(symbol: String, width: Int, height: Int): BufferedImage =
    renderCounterBitmap(counterBitmapLabels[symbol] ?: error("没有这个记牌器 label bitmap：$symbol"), width, height, "label_$symbol")

/** 生成记牌器 digit；只写整数像素，不经过 Java2D 抗锯齿。 */
fun renderCounterDigit(digit: Int, width: Int, height: Int): BufferedImage =
    renderCounterBitmap(counterBitmapDigits[digit.toString().single()] ?: error("没有这个记牌器 digit bitmap：$digit"), width, height, "digit_$digit")

/** 生成普通框或耗尽框；框独立于标签/数字，运行期按状态选择叠加。 */
fun renderCounterFrame(exhausted: Boolean): BufferedImage {
    val out = BufferedImage(counterGlyphFrameWidth, counterGlyphFrameHeight, BufferedImage.TYPE_INT_ARGB)
    val g = out.createGraphics()
    g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
    g.setRenderingHint(RenderingHints.KEY_STROKE_CONTROL, RenderingHints.VALUE_STROKE_PURE)
    g.color = if (exhausted) Color(0x55, 0x55, 0x55, 0xCC) else Color(0xB0, 0xB0, 0xB0, 0xCC)
    g.stroke = BasicStroke(1.0f)
    g.drawRect(0, 0, counterGlyphFrameWidth - 1, counterGlyphFrameHeight - 1)
    g.dispose()
    return out
}

/** 只用最近邻从基础 PNG 派生缩放资源；counter/hotbar 运行期不再组合绘制点数×数量。 */
fun scaleNearest(source: BufferedImage, scale: Int, preserveBottomRightAnchor: Boolean = false): BufferedImage {
    val width = scaledPixels(source.width, scale)
    val height = scaledPixels(source.height, scale)
    val out = BufferedImage(width, height, BufferedImage.TYPE_INT_ARGB)
    for (y in 0 until height) for (x in 0 until width) {
        val sourceX = minOf(source.width - 1, x * source.width / width)
        val sourceY = minOf(source.height - 1, y * source.height / height)
        out.setRGB(x, y, source.getRGB(sourceX, sourceY))
    }
    if (preserveBottomRightAnchor) {
        // 缩放后仍锁住 BitmapProvider 的实际宽度扫描到声明宽度；仅用于 label/digit 的透明锚点。
        out.setRGB(width - 1, height - 1, 0x01FFFFFF)
    }
    return out
}

fun writeCounterGlyph(target: File, image: BufferedImage) {
    target.parentFile.mkdirs()
    ImageIO.write(image, "png", target)
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

/**
 * 生成三张独立 Hotbar 物品图标：鸡蛋、水桶、番茄。
 *
 * <p>每张贴图固定为 20×22，运行期通过负空格按 step=24 横向组合；不再生成旧的
 * 九槽底板或其固定示例数字。图标 PNG 同时供 HUD 字形与物品模型复用。
 */
fun writeHotbarIconGlyph(target: File, iconIndex: Int) {
    check(iconIndex in 0 until hotbarIconCount) { "Hotbar 图标下标越界：$iconIndex" }
    val out = BufferedImage(hotbarIconGlyphWidth, hotbarIconGlyphHeight, BufferedImage.TYPE_INT_ARGB)
    drawHotbarIcon(out, iconIndex, 0, 0, hotbarIconGlyphWidth, hotbarIconGlyphHeight)
    // 右下角 alpha=1 锚点锁定 BitmapProvider 的 20px 实际扫描宽度，不改变可见图形。
    out.setRGB(hotbarIconGlyphWidth - 1, hotbarIconGlyphHeight - 1, 0x01FFFFFF)
    target.parentFile.mkdirs()
    ImageIO.write(out, "png", target)
}

fun drawHotbarIcon(out: BufferedImage, iconIndex: Int, x: Int, y: Int, width: Int, height: Int) {
    when (iconIndex) {
        0 -> drawEggIcon(out, x, y, width, height)
        1 -> drawBucketIcon(out, x, y, width, height)
        2 -> drawTomatoIcon(out, x, y, width, height)
        else -> error("Hotbar 图标只支持 egg/water/tomato，收到 $iconIndex")
    }
}

/** 白字带 1px 深色描边的 3×5 像素小字体，供 hotbar 图标槽烘焙示例数字。key 为字符。 */
val hotbarTinyDigits: Map<Char, Array<String>> = mapOf(
    '0' to arrayOf("111", "101", "101", "101", "111"),
    '1' to arrayOf("010", "110", "010", "010", "111"),
    '2' to arrayOf("111", "001", "111", "100", "111"),
    '3' to arrayOf("111", "001", "111", "001", "111"),
    '4' to arrayOf("101", "101", "111", "001", "001"),
    '5' to arrayOf("111", "100", "111", "001", "111"),
    '6' to arrayOf("111", "100", "111", "101", "111"),
    '7' to arrayOf("111", "001", "010", "010", "010"),
    '8' to arrayOf("111", "101", "111", "101", "111"),
    '9' to arrayOf("111", "101", "111", "001", "111")
)

/**
 * 在槽块右下角烘焙固定示例数字：先描 1px 深色边（(#0A0A0Cee)）保可读，再点亮白字。
 * 3×5 一位数字，位与位之间留 1px 间隔；从右下角向左排布。
 */
fun drawHotbarSlotNumber(out: BufferedImage, text: String, slotX: Int, slotY: Int, slotW: Int, slotH: Int) {
    val white = 0xFF_FF_FF_FF.toInt()
    // 描边必须全不透明：整幅 hotbar 底图是不透明遮罩，任何半透明像素都会被校验器拒绝。
    val outline = 0xFF_0A_0A_0C.toInt()
    val digitW = 3
    val digitH = 5
    val kerning = 1
    val totalW = text.length * digitW + (text.length - 1) * kerning
    val baseX = slotX + slotW - 1 - totalW
    val baseY = slotY + slotH - 1 - digitH
    // 先描边：白像素的 8 邻域填深色（不覆盖已存在的白像素）。
    val whitePixels = ArrayList<IntArray>()
    text.forEachIndexed { index, ch ->
        val rows = hotbarTinyDigits[ch] ?: return@forEachIndexed
        val ox = baseX + index * (digitW + kerning)
        for (ry in 0 until digitH) for (rx in 0 until digitW) {
            if (rows[ry][rx] == '1') whitePixels.add(intArrayOf(ox + rx, baseY + ry))
        }
    }
    for (p in whitePixels) for (dy in -1..1) for (dx in -1..1) {
        val nx = p[0] + dx; val ny = p[1] + dy
        if (nx in slotX until slotX + slotW && ny in slotY until slotY + slotH) {
            if (out.getRGB(nx, ny) != white) out.setRGB(nx, ny, outline)
        }
    }
    for (p in whitePixels) out.setRGB(p[0], p[1], white)
}

/**
 * 在槽块内烘焙一枚可辨识的确定性像素物品图标：6=水桶、7=鸡蛋、8=番茄。
 * 只用 setRGB 直接点像素（与记牌器 bitmap 字模同为构建期一份确定性逻辑，风格更朴素）；
 * 图标画在槽左上区域，右下角留给示例数字。
 */
fun drawHotbarSlotIcon(out: BufferedImage, slotIndex: Int, slotX: Int, slotY: Int, slotW: Int, slotH: Int) {
    when (slotIndex) {
        6 -> drawBucketIcon(out, slotX, slotY, slotW, slotH)
        7 -> drawEggIcon(out, slotX, slotY, slotW, slotH)
        8 -> drawTomatoIcon(out, slotX, slotY, slotW, slotH)
        else -> error("hotbar 图标槽只支持 6/7/8，收到 $slotIndex")
    }
}

/** 水桶：灰白梯形桶身 + 顶部提手弧线。 */
fun drawBucketIcon(out: BufferedImage, slotX: Int, slotY: Int, slotW: Int, slotH: Int) {
    val body = 0xFF_C8_D0_D8.toInt()
    val edge = 0xFF_60_68_70.toInt()
    val handle = 0xFF_90_98_A0.toInt()
    val cx = slotX + slotW / 2
    val topY = slotY + 5
    // 提手弧：从桶口两侧向上拱起。
    for (dx in -4..4) {
        val hx = cx + dx
        val hy = topY - 3 + (dx * dx) / 8
        if (hy in slotY until slotY + slotH) out.setRGB(hx, hy, handle)
    }
    // 桶身：上宽下窄梯形，高 9px。
    val bodyH = 9
    for (row in 0 until bodyH) {
        val y = topY + row
        val halfTop = 5
        val half = halfTop - row * 2 / bodyH
        for (x in cx - half..cx + half) {
            val color = if (x == cx - half || x == cx + half || row == 0 || row == bodyH - 1) edge else body
            if (y in slotY until slotY + slotH && x in slotX until slotX + slotW) out.setRGB(x, y, color)
        }
    }
}

/** 鸡蛋：竖椭圆，米白蛋壳 + 浅色高光。 */
fun drawEggIcon(out: BufferedImage, slotX: Int, slotY: Int, slotW: Int, slotH: Int) {
    val shell = 0xFF_F4_EC_D8.toInt()
    val edge = 0xFF_B8_A8_80.toInt()
    val cx = slotX + slotW / 2
    val cy = slotY + slotH / 2 - 1
    val rx = 5.0
    val ry = 7.0
    for (dy in -8..8) for (dx in -6..6) {
        val nx = dx / rx
        val ny = (dy - 1) / ry  // 上尖下圆：整体上移一点
        val d = nx * nx + ny * ny
        if (d <= 1.0) {
            val x = cx + dx; val y = cy + dy
            if (x in slotX until slotX + slotW && y in slotY until slotY + slotH) {
                out.setRGB(x, y, if (d > 0.72) edge else shell)
            }
        }
    }
}

/** 番茄：红色圆身 + 顶部绿色萼片。 */
fun drawTomatoIcon(out: BufferedImage, slotX: Int, slotY: Int, slotW: Int, slotH: Int) {
    val red = 0xFF_E0_3A_2A.toInt()
    val edge = 0xFF_A0_20_18.toInt()
    val leaf = 0xFF_3C_A0_40.toInt()
    val cx = slotX + slotW / 2
    val cy = slotY + slotH / 2 + 1
    val r = 6.0
    for (dy in -7..7) for (dx in -7..7) {
        val d = (dx * dx + dy * dy) / (r * r)
        if (d <= 1.0) {
            val x = cx + dx; val y = cy + dy
            if (x in slotX until slotX + slotW && y in slotY until slotY + slotH) {
                out.setRGB(x, y, if (d > 0.68) edge else red)
            }
        }
    }
    // 顶部萼片：一小撮绿色。
    for (dx in -3..3) {
        val x = cx + dx; val y = cy - 6
        if (x in slotX until slotX + slotW && y in slotY until slotY + slotH) out.setRGB(x, y, leaf)
    }
    out.setRGB(cx, cy - 7, leaf)
}

/**
 * 生成 hotbar「选中槽」高亮框字形（0xEF02，muz:font/hotbar_select.png）。
 *
 * <p>尺寸 {@code hotbarSelectGlyphWidth}×{@code hotbarSelectGlyphHeight}=20×22，比 18×20
 * 槽块每边多 1px：画一圈 2px 亮黄空心描边框，中间透明。运行期由 HotbarHudService 用零净
 * 前进量的负空格夹心定位到玩家当前持槽像素位置，不覆盖原版 sprite。
 * 宽/高/advance 必须同步 PackAssets 的 HOTBAR_SELECT_* 常量。
 *
 * @param target 输出路径（muz:font/hotbar_select.png）
 */
fun writeHotbarSelectGlyph(target: File) {
    val out = BufferedImage(hotbarSelectGlyphWidth, hotbarSelectGlyphHeight, BufferedImage.TYPE_INT_ARGB)
    check(out.width + 1 == hotbarSelectGlyphAdvance) {
        "hotbar 选中框字形前进量必须等于贴图宽度加 1"
    }
    val border = 0xFF_FF_E0_40.toInt()  // 亮黄，与九色槽块都不同，便于辨识选中位置
    val thickness = 2
    // 选中框覆盖槽块（18×20）外扩 1px，即 y=0..21、x=0..19 的边框区。
    for (y in 0 until hotbarSelectGlyphHeight) for (x in 0 until hotbarSelectGlyphWidth) {
        val onBorder = x < thickness || x >= hotbarSelectGlyphWidth - thickness ||
            y < thickness || y >= hotbarSelectGlyphHeight - thickness
        if (onBorder) out.setRGB(x, y, border)
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

/** 番茄桌面道具的自绘薄片模型；纹理复用 Hotbar 的番茄图标，避免两套视觉资产漂移。 */
fun writeTomatoGadgetModel(target: File, texturePath: String) {
    writeText(
        target,
        """
        {
          "textures": {"0": ${jsonString(texturePath)}, "particle": ${jsonString(texturePath)}},
          "elements": [
            {
              "from": [2, 2, 7.5], "to": [14, 14, 8.5],
              "faces": {
                "north": {"uv": [0, 0, 16, 16], "texture": "#0"},
                "south": {"uv": [16, 0, 0, 16], "texture": "#0"},
                "east": {"texture": "#0"}, "west": {"texture": "#0"},
                "up": {"texture": "#0"}, "down": {"texture": "#0"}
              }
            }
          ],
          "gui_light": "front"
        }
        """.trimIndent() + "\n"
    )
}

/** 透明水幕桌面道具：独立半透明纹理 + 0.5 格厚立体薄片，避免透明面被当作不透明方块。 */
fun writeWaterSheetModel(target: File, texturePath: String) {
    writeText(
        target,
        """
        {
          "render_type": "minecraft:translucent",
          "textures": {"0": ${jsonString(texturePath)}, "particle": ${jsonString(texturePath)}},
          "elements": [
            {
              "from": [1, 0, 7.75], "to": [15, 16, 8.25],
              "faces": {
                "north": {"uv": [0, 0, 16, 16], "texture": "#0"},
                "south": {"uv": [16, 0, 0, 16], "texture": "#0"},
                "east": {"texture": "#0"}, "west": {"texture": "#0"},
                "up": {"texture": "#0"}, "down": {"texture": "#0"}
              }
            }
          ],
          "gui_light": "front"
        }
        """.trimIndent() + "\n"
    )
}

fun writeWaterSheetTexture(target: File) {
    val out = BufferedImage(16, 16, BufferedImage.TYPE_INT_ARGB)
    for (y in 0 until 16) for (x in 0 until 16) {
        val edge = x == 0 || x == 15 || y == 0 || y == 15
        val alpha = if (edge) 92 else 132
        val blue = if ((x + y) % 5 == 0) 255 else 220
        out.setRGB(x, y, (alpha shl 24) or (70 shl 16) or (170 shl 8) or blue)
    }
    target.parentFile.mkdirs()
    ImageIO.write(out, "png", target)
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

// ============================================================================
// 生成 PackTiers.java
//
// 【为什么要生成而不是手写】档位表同时被两处消费：构建期算 images.yml 的码位，
// 运行期算「配置值 → 哪个字形」。两处必须逐项一致，否则整族字形平移成豆腐块。
// 原先靠 PackAssets 里的手写数组 + 一条读 build.gradle.kts 文本的测试来保证，
// 现在档位表由参数算出来，手写已不可能跟上 —— 改成单一源生成。
//
// 生成 Java 而不是 properties：档位表要参与码位算式，编译期常量数组比运行期解析
// 更直接，也不会有「资源没打进 jar」的问题；而且能被测试直接 import 比对。
// ============================================================================
val generatePackTiers = tasks.register("generatePackTiers") {
    // profile 本身也是输入；即便档位数值不变，版本/来源变化也不应被增量缓存吞掉。
    inputs.file(resourceProfileFile)
    // 这些参数进 inputs，改了 -P 参数就会重新生成（否则 Gradle 会误判 UP-TO-DATE）。
    inputs.property("cardHeightTiers", cardGlyphHeightTiers.toString())
    inputs.property("cardOffsetTiers", cardGlyphDownOffsetTiers.toString())
    inputs.property("avatarOffsetTiers", avatarDownOffsetTiers.toString())
    inputs.property("counterOffsetTiers", counterDownOffsetTiers.toString())
    inputs.property("avatarScaleTiers", avatarPixelScaleTiers.toString())
    inputs.property("counterScaleTiers", counterScaleTiers.toString())
    inputs.property("hotbarScaleTiers", hotbarScaleTiers.toString())
    inputs.property("counterScaleCodepointStarts", counterScaleCodepointStarts.toString())
    inputs.property("hotbarScaleCodepoints", "${hotbarScaleBaseCodepoints}/${hotbarScaleDebugCodepoints}/${hotbarScaleSelectCodepoints}/${hotbarScaleSelectDebugCodepoints}")
    inputs.property("counterGlyphFiles", counterGlyphFiles.toString())
    inputs.property("counterGlyphGeometry", "${counterGlyphLabelWidth}x${counterGlyphLabelHeight}/${counterGlyphDigitWidth}x${counterGlyphDigitHeight}/${counterGlyphFrameWidth}x${counterGlyphFrameHeight}/$counterGlyphAdvance")
    inputs.property("counterGlyphAscents", "$counterGlyphLabelAscent/$counterGlyphFrameAscent/$counterGlyphDigitAscent")
    outputs.dir(generatedJavaDir)

    doLast {
        val target = generatedJavaDir.get().asFile
            .resolve("linmumua/doudizhu/assets/PackTiers.java")
        target.parentFile.mkdirs()

        // 续行缩进必须【不小于】下面那个 raw string 的公共缩进（20 格），否则
        // trimIndent() 会把最小缩进算到这些续行上，把整个模板往左拉歪。
        fun javaArray(values: List<Int>): String =
            values.chunked(16).joinToString(",\n" + " ".repeat(20)) { chunk ->
                chunk.joinToString(", ")
            }

        writeText(
            target,
            """
            package linmumua.doudizhu.assets;

            /**
             * 资源包实际生成了哪些档位 —— 【构建期自动生成，不要手改】。
             *
             * <p>由 {@code build.gradle.kts} 的 {@code generatePackTiers} 任务写出，源头是那份
             * 范围/步长参数。改档位请改构建参数（如 {@code -PmuzAvatarOffsetStep=1}）后重新构建，
             * 手改这个文件只会在下次构建时被覆盖，且立刻与 {@code images.yml} 里的码位错开。
             *
             * <p>{@link PackAssets} 读这里的表来算「配置值 → 哪个字形」，构建期用同一批数字算
             * {@code images.yml} 的码位。两边同源，不存在「两处手写的表不同步」这种问题。
             */
            public final class PackTiers {

                private PackTiers() {
                }

                /** 牌面高档位（降序）。 */
                public static final int[] CARD_HEIGHT_TIERS = {
                    ${javaArray(cardGlyphHeightTiers)}
                };

                /** 牌行向下偏移档位（升序；profile 若需兼容无档位调用方应包含 0）。 */
                public static final int[] CARD_DOWN_OFFSET_TIERS = {
                    ${javaArray(cardGlyphDownOffsetTiers)}
                };

                /** 头像行向下偏移档位（升序；profile 若需兼容无档位调用方应包含 0）。 */
                public static final int[] AVATAR_DOWN_OFFSET_TIERS = {
                    ${javaArray(avatarDownOffsetTiers)}
                };

                /** 记牌器独立向下偏移档位（升序，profile 精确指定；通常应包含 0 基准）。 */
                public static final int[] COUNTER_DOWN_OFFSET_TIERS = {
                    ${javaArray(counterDownOffsetTiers)}
                };

                /** counter 可用缩放档，顺序与下面的码位起点数组一致。 */
                public static final int[] COUNTER_SCALE_TIERS = {
                    ${javaArray(counterScaleTiers)}
                };

                /** hotbar 可用缩放档，顺序与下面的四组码位数组一致。 */
                public static final int[] HOTBAR_SCALE_TIERS = {
                    ${javaArray(hotbarScaleTiers)}
                };

                /** counter 各缩放档的每档起始码位；100 档必须继续为 0xE900。 */
                public static final int[] COUNTER_SCALE_CODEPOINT_STARTS = {
                    ${javaArray(counterScaleCodepointStarts)}
                };

                /** hotbar 各缩放档三张基础图标的起始码位，顺序为 egg、water、tomato。 */
                public static final int[] HOTBAR_SCALE_BASE_CODEPOINTS = {
                    ${javaArray(hotbarScaleBaseCodepoints)}
                };

                /** hotbar 各缩放档三张 Debug Web 覆盖层图标的起始码位。 */
                public static final int[] HOTBAR_SCALE_DEBUG_CODEPOINTS = {
                    ${javaArray(hotbarScaleDebugCodepoints)}
                };

                /** hotbar 各缩放档的选中框码位；100 档必须继续为 0xEF02。 */
                public static final int[] HOTBAR_SCALE_SELECT_CODEPOINTS = {
                    ${javaArray(hotbarScaleSelectCodepoints)}
                };

                /** hotbar 各缩放档的 Debug Web 覆盖层选中框码位；100 档新增约定为 0xEF03。 */
                public static final int[] HOTBAR_SCALE_SELECT_DEBUG_CODEPOINTS = {
                    ${javaArray(hotbarScaleSelectDebugCodepoints)}
                };

                /** 记牌器每档占用的分层字形数：15 个标签、普通/耗尽框 2 个、0..4 数字 5 个。 */
                public static final int COUNTER_GLYPHS_PER_TIER = ${counterGlyphFiles.size};
                /** 记牌器标签字形数，顺序与 CardRank 枚举严格一致。 */
                public static final int COUNTER_RANK_GLYPHS = ${counterRankGlyphFiles.size};
                /** 记牌器框字形数，顺序为普通框、耗尽框。 */
                public static final int COUNTER_FRAME_GLYPHS = $counterGlyphFrameCount;
                /** 记牌器数字字形数，仅生成 0..4 五个剩余张数。 */
                public static final int COUNTER_DIGIT_GLYPHS = $counterGlyphDigitCount;
                /** 记牌器标签/框基础渲染高度，单位像素（紧凑几何 21×12）。 */
                public static final int COUNTER_GLYPH_HEIGHT = $counterGlyphLabelHeight;
                /** 记牌器每层字形的固定视觉宽度，单位像素（紧凑几何 21px）。 */
                public static final int COUNTER_GLYPH_WIDTH = $counterGlyphLabelWidth;
                public static final int COUNTER_GLYPH_WIDE_WIDTH = $counterGlyphLabelWidth;
                /** 记牌器数字层基础渲染高度，单位像素（紧凑几何 21×8）。 */
                public static final int COUNTER_DIGIT_GLYPH_HEIGHT = $counterGlyphDigitHeight;
                /** 记牌器分层 ascent：label=12-downOffset、frame=-3-downOffset、digit=-6-downOffset。 */
                public static final int COUNTER_LABEL_ASCENT = $counterGlyphLabelAscent;
                public static final int COUNTER_FRAME_ASCENT = $counterGlyphFrameAscent;
                public static final int COUNTER_DIGIT_ASCENT = $counterGlyphDigitAscent;
                /** 记牌器字形前进量，标签、数字、框三层统一为 22px。 */
                public static final int COUNTER_GLYPH_ADVANCE = $counterGlyphAdvance;
                public static final int COUNTER_LABEL_WIDTH = $counterGlyphLabelWidth;
                public static final int COUNTER_LABEL_HEIGHT = $counterGlyphLabelHeight;
                public static final int COUNTER_DIGIT_WIDTH = $counterGlyphDigitWidth;
                public static final int COUNTER_FRAME_WIDTH = $counterGlyphFrameWidth;
                public static final int COUNTER_FRAME_HEIGHT = $counterGlyphFrameHeight;

                /** hotbar 三图标默认 100 档的位图几何；其它档由 PackAssets 按最近整数缩放。 */
                public static final int HOTBAR_ICON_COUNT = $hotbarIconCount;
                public static final int HOTBAR_ICON_WIDTH = $hotbarIconGlyphWidth;
                public static final int HOTBAR_ICON_HEIGHT = $hotbarIconGlyphHeight;
                public static final int HOTBAR_ICON_STEP = $hotbarIconStep;
                public static final int HOTBAR_ICON_ADVANCE = $hotbarIconAdvance;
                public static final int HOTBAR_GLYPH_WIDTH = $hotbarHudGlyphWidth;
                public static final int HOTBAR_GLYPH_HEIGHT = $hotbarHudGlyphHeight;
                public static final int HOTBAR_GLYPH_ADVANCE = $hotbarHudGlyphAdvance;
                public static final int HOTBAR_SELECT_WIDTH = $hotbarSelectGlyphWidth;
                public static final int HOTBAR_SELECT_HEIGHT = $hotbarSelectGlyphHeight;
                public static final int HOTBAR_SELECT_ADVANCE = $hotbarSelectGlyphAdvance;
                public static final int HOTBAR_SLOT_COUNT = $hotbarHudSlotCount;
                public static final int HOTBAR_BASE_ASCENT = $hotbarBaseAscent;

                /** 头像放大倍数档位（按资源 profile 精确生成，可能不是连续范围）。 */
                public static final int[] AVATAR_SCALE_TIERS = {
                    ${javaArray(avatarPixelScaleTiers)}
                };

                /** 头像放大倍数下限（资源包只生成了这些档位中的最小值）。 */
                public static final int AVATAR_MIN_SCALE = ${avatarPixelScaleTiers.min()};

                /** 头像放大倍数上限（资源包只生成了这些档位中的最大值）。 */
                public static final int AVATAR_MAX_SCALE = ${avatarPixelScaleTiers.max()};

                /**
                 * 可用码位上界。字体切分算式用它算「一张字体能装几档」：
                 * {@code (MAX_GLYPH_CODEPOINT - 该族起点 + 1) / 每档码位数}。
                 *
                 * <p>再往上就出了 BMP，{@code images.yml} 用的 4 位十六进制 unicode 转义
                 * 会被 YAML 截断，整族码位错位成豆腐块。
                 */
                public static final int MAX_GLYPH_CODEPOINT = $maxGlyphCodepoint;
            }
            """.trimIndent() + "\n"
        )
        logger.lifecycle(
            "[muz] PackTiers profile=%s(v%d)：牌高=%s、牌偏移=%s、头像 scale=%s、头像偏移=%s、记牌 scale=%s、记牌偏移=%s、hotbar scale=%s".format(
                resourceProfileFile.name, resourceProfile.version,
                cardGlyphHeightTiers.joinToString("/"), cardGlyphDownOffsetTiers.joinToString("/"),
                avatarPixelScaleTiers.joinToString("/"), avatarDownOffsetTiers.joinToString("/"),
                counterScaleTiers.joinToString("/"), counterDownOffsetTiers.joinToString("/"),
                hotbarScaleTiers.single()
            )
        )
    }
}

sourceSets.named("main") {
    java.srcDir(generatePackTiers)
}

val generateResourcePack = tasks.register("generateResourcePack") {
    inputs.dir(resourcePackSourceDir)
    inputs.file(resourceProfileFile)
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
        val itemGadgetDir = outputAssetsRoot.resolve("items")
        val modelCardsDir = outputAssetsRoot.resolve("models/item/cards")
        val modelUiDir = outputAssetsRoot.resolve("models/item/ui")
        val modelFurnitureDir = outputAssetsRoot.resolve("models/item/furniture")
        val modelGadgetDir = outputAssetsRoot.resolve("models/item")

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

        // 记牌行分层字形：默认 100 档仍只保留 22 张基础 PNG，运行期按偏移档注册 22 个字形声明。
        // 75/125 档只从这 22 张默认 PNG 最近邻派生，不生成「点数 × 已出数」组合贴图。
        // 运行期四层连续覆盖层不改这里的 profile 离散生成逻辑：Trick 使用独立
        // baseFont_continuous/base tier 0 char，hotbar 复用既有 debug font/char；连续 alias
        // 与 baseAscent、scale 必须和本循环生成的基础 PNG/字体声明逐项同源，避免覆盖正式 char。
        val counterFontDir = outputAssetsRoot.resolve("textures/font/counter")
        val counterBaseImages = linkedMapOf<String, BufferedImage>()
        counterRankGlyphFiles.forEachIndexed { index, file ->
            counterBaseImages[file] = renderCounterLabel(counterRankGlyphSymbols[index], counterGlyphLabelWidth, counterGlyphLabelHeight)
        }
        for (digit in 0..4) counterBaseImages["digit_$digit"] = renderCounterDigit(digit, counterGlyphDigitWidth, counterGlyphDigitHeight)
        counterBaseImages["frame_normal"] = renderCounterFrame(false)
        counterBaseImages["frame_exhausted"] = renderCounterFrame(true)
        counterBaseImages.forEach { (file, image) ->
            writeCounterGlyph(counterFontDir.resolve("$file.png"), image)
        }
        for (scale in counterScaleTiers.filter { it != defaultHudScale }) {
            val scaledDir = counterFontDir.resolve("scale_$scale")
            counterBaseImages.forEach { (file, image) ->
                writeCounterGlyph(
                    scaledDir.resolve("$file.png"),
                    scaleNearest(image, scale, file.startsWith("label_") || file.startsWith("digit_"))
                )
            }
        }

        for (scale in avatarPixelScaleTiers) {
            for (row in 0 until avatarOutlinedPixels) {
                writeAvatarPixelGlyph(
                    outputAssetsRoot.resolve("textures/font/avatar/pixel_${scale}_$row.png"),
                    scale,
                    row,
                    avatarOutlinedPixels
                )
            }
            // 王冠那 2 行：同一个函数，只是总行数按 12 算，于是白块落在头像盒【上方】。
            // 画的仍是纯白方块，颜色由渲染时的 <color> 标签给（金色王冠、黑色描边共用它）。
            for (row in 0 until avatarCrownPixels) {
                writeAvatarPixelGlyph(
                    outputAssetsRoot.resolve("textures/font/avatar/crown_${scale}_$row.png"),
                    scale,
                    row,
                    avatarRowTotalPixels
                )
            }
        }



        // PLAYING 阶段只生成三张独立 Hotbar 图标；每张 20×22，图标间隔 4px，step=24。
        // 75/125 档从各自基础图标最近邻派生；每档使用独立字体和独立码位窗口。
        // 旧 EF00/EF01 九槽底板不再生成，选中框仍独立生成并继续使用 EF02/EF03 默认码位。
        // 运行期 hotbar_debug 连续 alias 只引用这里生成的基础/Debug char 与同 scale 资源，
        // 不改变本循环的 profile 档位数量，也不覆盖正式 hotbar font/char。
        // 【尺寸/step/advance/码位必须与 PackAssets.hotbarIcon* 保持一致】
        val hotbarFontDir = outputAssetsRoot.resolve("textures/font")
        val hotbarIconSources = hotbarIconNames.indices.map { index ->
            val file = hotbarFontDir.resolve("hotbar_${hotbarIconNames[index]}.png")
            writeHotbarIconGlyph(file, index)
            file to ImageIO.read(file)
        }
        val hotbarSelectSource = run {
            writeHotbarSelectGlyph(hotbarFontDir.resolve("hotbar_select.png"))
            ImageIO.read(hotbarFontDir.resolve("hotbar_select.png"))
        }
        for (scale in hotbarScaleTiers.filter { it != defaultHudScale }) {
            val scaledDir = hotbarFontDir.resolve("scale_$scale")
            hotbarIconSources.forEach { (file, image) ->
                writeCounterGlyph(scaledDir.resolve(file.name), scaleNearest(image, scale))
            }
            writeCounterGlyph(scaledDir.resolve("hotbar_select.png"), scaleNearest(hotbarSelectSource, scale))
        }

        // 桌面道具模型：番茄模型复用 Hotbar 番茄图标；水幕使用独立半透明纹理并以薄片模型渲染。
        val tomatoTexture = "$resourceNamespace:font/hotbar_tomato"
        val waterSheetTexture = "$resourceNamespace:item/$tableGadgetWaterSheetId"
        writeWaterSheetTexture(outputAssetsRoot.resolve("textures/item/$tableGadgetWaterSheetId.png"))
        writeTomatoGadgetModel(modelGadgetDir.resolve("$tableGadgetTomatoId.json"), tomatoTexture)
        writeWaterSheetModel(modelGadgetDir.resolve("$tableGadgetWaterSheetId.json"), waterSheetTexture)
        writeItemDefinition(itemGadgetDir.resolve("$tableGadgetTomatoId.json"), "$resourceNamespace:item/$tableGadgetTomatoId")
        writeItemDefinition(itemGadgetDir.resolve("$tableGadgetWaterSheetId.json"), "$resourceNamespace:item/$tableGadgetWaterSheetId")

        // 【不再覆盖原版 hotbar sprite】：自定义 HUD 只由 HotbarHudService 在 PLAYING 阶段
        // 通过 ActionBar 推送，等待、叫地主、加倍、结算及普通游玩时保留原版物品栏。

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

// 26.1.2 客户端字体度量归档：只嵌入字体 JSON 及其递归引用的 PNG/unihex，运行期
// HotbarFontMetrics.load(InputStream, Path, int) 直接读取该 ZIP；不把整份 client.jar 带入插件。
val vanillaHotbarFontArchive = layout.buildDirectory.file("hotbar-font/vanilla-26.1.2.zip")

private fun sha1Hex(file: File): String = MessageDigest.getInstance("SHA-1").let { digest ->
    file.inputStream().use { input ->
        val buffer = ByteArray(8192)
        var count: Int
        while (input.read(buffer).also { count = it } != -1) digest.update(buffer, 0, count)
    }
    digest.digest().joinToString("") { "%02x".format(it) }
}

private fun downloadVerified(url: String, destination: File, expectedSha1: String) {
    destination.parentFile.mkdirs()
    val temp = destination.resolveSibling(".${destination.name}.${System.nanoTime()}.part")
    temp.delete()
    try {
        val connection = (URI(url).toURL().openConnection() as HttpURLConnection).apply {
            connectTimeout = 30_000
            readTimeout = 60_000
            instanceFollowRedirects = true
            requestMethod = "GET"
        }
        try {
            check(connection.responseCode == HttpURLConnection.HTTP_OK) {
                "下载失败 ${connection.responseCode}：$url"
            }
            connection.inputStream.use { input -> temp.outputStream().use { output -> input.copyTo(output) } }
        } finally {
            connection.disconnect()
        }
        check(sha1Hex(temp) == expectedSha1) { "下载内容 SHA-1 校验失败：$url" }
        try {
            Files.move(temp.toPath(), destination.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
        } catch (_: AtomicMoveNotSupportedException) {
            Files.move(temp.toPath(), destination.toPath(), StandardCopyOption.REPLACE_EXISTING)
        }
    } finally {
        temp.delete()
    }
}

private fun cachedVerified(cacheDir: File, name: String, url: String, expectedSha1: String): File {
    val target = cacheDir.resolve(name)
    if (target.isFile && sha1Hex(target).equals(expectedSha1, ignoreCase = true)) return target
    if (target.exists()) target.delete()
    downloadVerified(url, target, expectedSha1)
    check(target.isFile && sha1Hex(target).equals(expectedSha1, ignoreCase = true)) {
        "缓存写入后 SHA-1 校验失败：$target"
    }
    return target
}

private fun jsonMap(value: Any?): Map<*, *>? = value as? Map<*, *>
private fun jsonString(map: Map<*, *>, key: String): String? = map[key] as? String
private fun normalizedArchivePath(value: String, defaultNamespace: String = "minecraft"): String {
    val trimmed = value.trim().replace('\\', '/')
    require(trimmed.isNotEmpty() && !trimmed.startsWith('/') && !trimmed.split('/').contains("..")) {
        "字体资源路径非法：$value"
    }
    if (trimmed.startsWith("assets/")) return trimmed
    val colon = trimmed.indexOf(':')
    return if (colon >= 0) {
        "assets/${trimmed.substring(0, colon)}/${trimmed.substring(colon + 1)}"
    } else {
        "assets/$defaultNamespace/$trimmed"
    }
}

private fun fontArchivePath(id: String): String {
    val normalized = id.trim().replace('\\', '/')
    require(normalized.isNotEmpty()) { "字体 reference id 不能为空" }
    if (normalized.startsWith("assets/")) {
        return normalizedArchivePath(normalized.removeSuffix(".json"), "minecraft") + ".json"
    }
    val colon = normalized.indexOf(':')
    val namespace = if (colon >= 0) normalized.substring(0, colon) else "minecraft"
    val path = (if (colon >= 0) normalized.substring(colon + 1) else normalized).removeSuffix(".json")
    // Minecraft 的 reference id 既可能是 `minecraft:include/space`，也可能已经带有
    // `font/` 前缀；后者不能再次拼接 namespace/font，否则会变成
    // assets/minecraft/minecraft/font/include/space.json。
    val resourcePath = if (path.startsWith("font/")) path else "font/$path"
    return normalizedArchivePath("$namespace:$resourcePath.json")
}

private fun bitmapArchivePath(file: String): String {
    val normalized = file.trim().replace('\\', '/')
    val colon = normalized.indexOf(':')
    val namespace = if (colon >= 0) normalized.substring(0, colon) else "minecraft"
    val path = if (colon >= 0) normalized.substring(colon + 1) else normalized
    val texturePath = if (path.startsWith("textures/")) path else "textures/$path"
    return normalizedArchivePath("$namespace:$texturePath")
}

val generateHotbarVanillaFonts = tasks.register("generateHotbarVanillaFonts") {
    onlyIf { muzTarget.id == "paper-26.1.2" }
    // 每次任务被要求执行时都重新校验 Gradle 缓存；不能仅凭归档文件存在就跳过 SHA-1 校验。
    outputs.upToDateWhen { false }
    outputs.file(vanillaHotbarFontArchive)
    doLast {
        val cacheDir = gradle.gradleUserHomeDir.resolve("caches/muz-font")
        val clientSha1 = "4e618f09a0c649dde3fdf829df443ce0b8831e65"
        val assetIndexSha1 = "c0f421369aed2b80c0490be50630519c49a2db77"
        val client = cachedVerified(
            cacheDir,
            "client-$clientSha1.jar",
            "https://piston-data.mojang.com/v1/objects/$clientSha1/client.jar",
            clientSha1
        )
        val assetIndex = cachedVerified(
            cacheDir,
            "asset-index-$assetIndexSha1.json",
            "https://piston-meta.mojang.com/v1/packages/$assetIndexSha1/30.json",
            assetIndexSha1
        )
        val parsedIndex = JsonSlurper().parse(assetIndex) as? Map<*, *>
            ?: throw GradleException("Minecraft asset index 根节点不是对象")
        val objects = jsonMap(parsedIndex["objects"]) ?: emptyMap<Any, Any>()
        fun assetHash(archivePath: String): String? =
            jsonMap(objects[archivePath.removePrefix("assets/")])?.get("hash") as? String
        val objectCache = cacheDir.resolve("objects")
        fun indexedBytes(archivePath: String): ByteArray? {
            val hash = assetHash(archivePath) ?: return null
            require(hash.matches(Regex("[0-9a-fA-F]{40}"))) { "asset index 的 hash 非 SHA-1：$archivePath" }
            return cachedVerified(
                objectCache,
                hash,
                "https://resources.download.minecraft.net/${hash.substring(0, 2)}/$hash",
                hash
            ).readBytes()
        }

        ZipFile(client).use { clientZip ->
            val clientEntries = LinkedHashMap<String, ByteArray>()
            clientZip.entries().asSequence()
                .filter { !it.isDirectory && it.name.startsWith("assets/minecraft/font/") &&
                    it.name.endsWith(".json") }
                .sortedBy { it.name }
                .forEach { entry -> clientEntries[entry.name] = clientZip.getInputStream(entry).use { it.readBytes() } }
            require(clientEntries.isNotEmpty()) { "client.jar 未找到 assets/minecraft/font/*.json" }

            val selected = linkedMapOf<String, ByteArray>()
            val visiting = mutableSetOf<String>()
            fun readClient(path: String): ByteArray? = clientEntries[path] ?: clientZip.getEntry(path)?.let { entry ->
                clientZip.getInputStream(entry).use { it.readBytes() }
            }
            fun readResource(path: String): ByteArray {
                return indexedBytes(path) ?: readClient(path)
                    ?: throw GradleException("字体资源缺失：$path")
            }
            fun readFont(path: String): ByteArray {
                // asset index 的字体 JSON 是客户端高包的权威内容；只要索引中存在，始终
                // 覆盖 client.jar 内对应声明，而不是只对空 unifont 做特判。
                return indexedBytes(path) ?: readClient(path)
                    ?: throw GradleException("字体 JSON 缺失：$path")
            }
            fun visitFont(path: String) {
                val normalized = normalizedArchivePath(path)
                if (!visiting.add(normalized)) return
                try {
                    val bytes = readFont(normalized)
                    selected[normalized] = bytes
                    val root = jsonMap(JsonSlurper().parseText(bytes.toString(Charsets.UTF_8)))
                        ?: throw GradleException("字体 JSON 根节点不是对象：$normalized")
                    val providers = root["providers"] as? List<*> ?: return
                    providers.forEach { raw ->
                        val provider = jsonMap(raw) ?: return@forEach
                        when (jsonString(provider, "type")) {
                            "reference" -> jsonString(provider, "id")?.let { visitFont(fontArchivePath(it)) }
                            "bitmap" -> jsonString(provider, "file")?.let {
                                val resource = bitmapArchivePath(it)
                                selected[resource] = readResource(resource)
                            }
                            "unihex" -> (jsonString(provider, "hex_file") ?: jsonString(provider, "file"))?.let {
                                val resource = normalizedArchivePath(it)
                                selected[resource] = readResource(resource)
                            }
                        }
                    }
                } finally {
                    visiting.remove(normalized)
                }
            }
            clientEntries.keys.sorted().forEach(::visitFont)

            val output = vanillaHotbarFontArchive.get().asFile
            output.parentFile.mkdirs()
            val temp = output.resolveSibling(".${output.name}.${System.nanoTime()}.part")
            temp.delete()
            try {
                ZipOutputStream(temp.outputStream().buffered()).use { zip ->
                    selected.toSortedMap().forEach { (name, bytes) ->
                        ZipEntry(name).also { it.time = 0L }.let { entry ->
                            zip.putNextEntry(entry)
                            zip.write(bytes)
                            zip.closeEntry()
                        }
                    }
                }
                try {
                    Files.move(temp.toPath(), output.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
                } catch (_: AtomicMoveNotSupportedException) {
                    Files.move(temp.toPath(), output.toPath(), StandardCopyOption.REPLACE_EXISTING)
                }
            } finally {
                temp.delete()
            }
            logger.lifecycle("[muz] 已生成 26.1.2 Hotbar 原版字体归档：${output}（${selected.size} 个文件）")
        }
    }
}

val generateCraftEngineBundle = tasks.register("generateCraftEngineBundle") {
    dependsOn(generateResourcePack)
    inputs.dir(resourcePackSourceDir)
    inputs.file(resourceProfileFile)
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


























        // 【按牌高档分别产出】：牌面条目是 55 张 × 25 高度档 × 41 偏移档 = 56,375 条、
        // 单份 8.4 MiB，仍然超 SnakeYAML 的 3 MiB 单文档上限，所以这一族要再切一层。
        // 用牌高做切分键是因为它是最外层循环，切开后每份 ~0.34 MiB，余量充足；
        // 而且档位含义清晰，出问题时按文件名就能定位到是哪一档。
        val cardGlyphImagesByHeight = linkedMapOf<Int, String>()
        cardGlyphHeightTiers.forEachIndexed { heightTier, height ->
            cardGlyphImagesByHeight[height] = buildString {
                cardGlyphDownOffsetTiers.forEachIndexed { downTier, downOffset ->
                    val tier = heightTier * cardGlyphDownOffsetTiers.size + downTier
                    val (fontIndex, tierBase) = tierFontSlot(tier, cardIds.size, cardGlyphCodepointStart)
                    val font = fontNameOf(cardGlyphFont, fontIndex)
                    cardIds.forEachIndexed { index, id ->
                        val codepoint = tierBase + index
                        checkGlyphCodepoint(codepoint, "牌", tier)
                        val charEscape = "\\u%04x".format(codepoint)
                        appendLine("  $resourceNamespace:card_${id}_h${height}_d$downOffset:")
                        appendLine("    height: $height")
                        appendLine("    ascent: ${height - downOffset}")
                        appendLine("    font: $font")
                        appendLine("    file: $resourceNamespace:font/cards/$id.png")
                        appendLine("    char: $charEscape")
                    }
                }
            }
        }









        // 头像族走 avatarDownOffsetTiers：牌那张表的浅档头像永远用不到，
        // 生成出来只是白占 images.yml 条目与私有区码位。
        val avatarScaleCount = avatarPixelScaleTiers.size
        val avatarGlyphsPerTier = avatarScaleCount * avatarOutlinedPixels
        // 【按 scale 分组产出】：头像像素条目是 201 偏移档 × 15 scale × 10 行 = 30,150 条。
        // 用 scale 而不是偏移档做切分键：偏移档有 201 个，按它切会产生 201 个碎文件；
        // scale 只有 15 个，每份 201 × 10 = 2,010 条、约 0.2 MiB，数量和体积都合适。
        //
        // 注意码位仍由 downTier 决定（tierFontSlot 的入参没变），切分只影响
        // 「这些行写进哪个文件」，不影响任何一个字形的码位与字体归属。
        val avatarPixelImagesByScale = linkedMapOf<Int, String>()
        for (scale in avatarPixelScaleTiers) {
            avatarPixelImagesByScale[scale] = buildString {
                avatarDownOffsetTiers.forEachIndexed { downTier, downOffset ->
                    val (fontIndex, tierBase) =
                        tierFontSlot(downTier, avatarGlyphsPerTier, avatarPixelCodepointStart)
                    val font = fontNameOf(avatarPixelFont, fontIndex)
                    for (row in 0 until avatarOutlinedPixels) {
                        val index = avatarPixelScaleTiers.indexOf(scale) * avatarOutlinedPixels + row
                        val codepoint = tierBase + index
                        checkGlyphCodepoint(codepoint, "头像", downTier)
                        val charEscape = "\\u%04x".format(codepoint)
                        val size = (avatarOutlinedPixels - row) * scale
                        appendLine("  $resourceNamespace:avatar_px_${scale}_${row}_d$downOffset:")
                        appendLine("    height: $size")
                        appendLine("    ascent: ${size - downOffset}")
                        appendLine("    font: $font")
                        appendLine("    file: $resourceNamespace:font/avatar/pixel_${scale}_$row.png")
                        appendLine("    char: $charEscape")
                    }
                }
            }
        }

        // 王冠家族。与头像像素族的唯一差别是 row 只取 0..1，且高度按 12 行算 ——
        // 于是白块落在锚点上方 10*scale..12*scale，正好接在头像 row 0 之上。
        // 单独成族而不是塞进头像族：这样「戴冠」= 多画一段字形，不必为「戴冠版头像」
        // 再生成一整套 150 张 PNG，也不必让王冠与描边互斥。
        val crownGlyphsPerTier = avatarScaleCount * avatarCrownPixels
        val avatarCrownImages = buildString {
            avatarDownOffsetTiers.forEachIndexed { downTier, downOffset ->
                val (fontIndex, tierBase) =
                    tierFontSlot(downTier, crownGlyphsPerTier, avatarCrownCodepointStart)
                val font = fontNameOf(avatarCrownFont, fontIndex)
                for (scale in avatarPixelScaleTiers) {
                    for (row in 0 until avatarCrownPixels) {
                        val index = avatarPixelScaleTiers.indexOf(scale) * avatarCrownPixels + row
                        val codepoint = tierBase + index
                        checkGlyphCodepoint(codepoint, "王冠", downTier)
                        val charEscape = "\\u%04x".format(codepoint)
                        val size = (avatarRowTotalPixels - row) * scale
                        appendLine("  $resourceNamespace:avatar_crown_${scale}_${row}_d$downOffset:")
                        appendLine("    height: $size")
                        appendLine("    ascent: ${size - downOffset}")
                        appendLine("    font: $font")
                        appendLine("    file: $resourceNamespace:font/avatar/crown_${scale}_$row.png")
                        appendLine("    char: $charEscape")
                    }
                }
            }
        }




        // 记牌行分层字形族：使用 profile 指定的 counterDownOffsetTiers（默认 0..400、步长 2），
        // 每档仍占用 22 个码位；紧凑基础 PNG 为标签/框 21×12、数字 21×8，75/125 档拥有
        // 独立字体、码位窗口和最近邻派生 PNG。
        val counterImagesByScale = linkedMapOf<String, String>()
        counterScaleTiers.forEachIndexed { scaleIndex, scale ->
            val scaleCodepointStart = counterScaleCodepointStarts[scaleIndex]
            val font = scaleFontName(counterGlyphFont, scale)
            val textureDirectory = if (scale == defaultHudScale) "counter" else "counter/scale_$scale"
            val body = StringBuilder()
            counterDownOffsetTiers.forEachIndexed { downTier, downOffset ->
                val tierBase = scaleCodepointStart + downTier * counterGlyphFiles.size
                counterGlyphFiles.forEachIndexed { index, file ->
                    val codepoint = tierBase + index
                    checkGlyphCodepoint(codepoint, "记牌 scale=$scale", downTier)
                    val charEscape = "\\u%04x".format(codepoint)
                    val height = when {
                        index < counterRankGlyphFiles.size -> scaledPixels(counterGlyphLabelHeight, scale)
                        index < counterRankGlyphFiles.size + counterGlyphDigitCount -> scaledPixels(counterGlyphDigitHeight, scale)
                        else -> scaledPixels(counterGlyphFrameHeight, scale)
                    }
                    val name = if (scale == defaultHudScale) {
                        "$resourceNamespace:counter_${file}_d$downOffset"
                    } else {
                        "$resourceNamespace:counter_${file}_s${scale}_d$downOffset"
                    }
                    body.appendLine("  $name:")
                    body.appendLine("    height: $height")
                    val ascent = when {
                        index < counterRankGlyphFiles.size -> scaledSigned(counterGlyphLabelAscent, scale) - downOffset
                        index < counterRankGlyphFiles.size + counterGlyphDigitCount -> scaledSigned(counterGlyphDigitAscent, scale) - downOffset
                        else -> scaledSigned(counterGlyphFrameAscent, scale) - downOffset
                    }
                    body.appendLine("    ascent: $ascent")
                    body.appendLine("    font: $font")
                    body.appendLine("    file: $resourceNamespace:font/$textureDirectory/$file.png")
                    body.appendLine("    char: $charEscape")
                }
            }
            val partName = if (scale == defaultHudScale) "counter" else "counter_s$scale"
            counterImagesByScale[partName] = "images:\n$body"
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
                        checkGlyphCodepoint(codepoint, "bot", downTier)
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

        // 【为什么必须拆成多份】：CraftEngine 用 SnakeYAML Engine 读这些文件，
        // 那个解析器对【单份文档】有 3,145,728 code point 的硬上限
        // （YamlEngineException: The incoming YAML document exceeds the limit）。
        // 档位放开后条目数是乘出来的（头像 201 偏移档 × 15 缩放档 × 10 张 = 30,150 条），
        // 单份 images.yml 已到 13.8 MiB，服务端加载时直接抛异常、整包不生效。
        //
        // CraftEngine 是用 Files.walkFileTree 递归扫 configuration/ 下所有 yml、
        // 逐份独立解析的（见 AbstractPackManager.updateCachedConfigFiles），
        // 所以按族拆开每份各自受限、互不影响，档位范围与码位布局完全不用动。
        val imagesDir = bundleRoot.resolve("configuration/images")
        // 旧版本装过单份 images.yml；不删掉会和拆分后的文件重复定义同一批字形。
        bundleRoot.resolve("configuration/images.yml").delete()
        imagesDir.deleteRecursively()

        val botAvatarBaseImages = """
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
        """.trimIndent() + "\n" + botAvatarDownImages

        // hotbar 每档是独立字体/码位窗口；每档声明三张基础图标与一张选中框。
        val hotbarImagesByScale = linkedMapOf<String, String>()
        hotbarScaleTiers.forEachIndexed { scaleIndex, scale ->
            val suffix = if (scale == defaultHudScale) "" else "_s$scale"
            val font = scaleFontName(hotbarHudFont, scale)
            val textureDirectory = if (scale == defaultHudScale) "font" else "font/scale_$scale"
            val width = scaledPixels(hotbarHudGlyphWidth, scale)
            val height = scaledPixels(hotbarHudGlyphHeight, scale)
            val iconWidth = scaledPixels(hotbarIconGlyphWidth, scale)
            val iconHeight = scaledPixels(hotbarIconGlyphHeight, scale)
            val selectWidth = scaledPixels(hotbarSelectGlyphWidth, scale)
            val selectHeight = scaledPixels(hotbarSelectGlyphHeight, scale)
            val baseCodepoint = hotbarScaleBaseCodepoints[scaleIndex]
            val debugCodepoint = hotbarScaleDebugCodepoints[scaleIndex]
            val selectCharEscape = "\\u%04x".format(hotbarScaleSelectCodepoints[scaleIndex])
            val body = buildString {
                appendLine("images:")
                hotbarIconNames.forEachIndexed { iconIndex, iconName ->
                    appendLine("  $resourceNamespace:hotbar_${iconName}$suffix:")
                    appendLine("    height: $iconHeight")
                    appendLine("    ascent: ${scaledSigned(hotbarBaseAscent, scale)}")
                    appendLine("    font: $font")
                    appendLine("    file: $resourceNamespace:$textureDirectory/hotbar_${iconName}.png")
                    appendLine("    char: \\u%04x".format(baseCodepoint + iconIndex))
                }
                appendLine("  $resourceNamespace:hotbar_select$suffix:")
                appendLine("    height: $selectHeight")
                appendLine("    ascent: ${scaledSigned(hotbarBaseAscent, scale)}")
                appendLine("    font: $font")
                appendLine("    file: $resourceNamespace:$textureDirectory/hotbar_select.png")
                appendLine("    char: $selectCharEscape")
            }
            check(width == iconWidth * hotbarIconCount + scaledPixels(hotbarIconStep - hotbarIconGlyphWidth, scale) * (hotbarIconCount - 1)) {
                "hotbar scale=$scale 三图标总宽与 step 不一致"
            }
            check(width + 1 == if (scale == defaultHudScale) hotbarHudGlyphAdvance else scaledPixels(hotbarHudGlyphAdvance - 1, scale) + 1) {
                "hotbar scale=$scale 三图标整体前进量与宽度不一致"
            }
            check(selectWidth + 1 == if (scale == defaultHudScale) hotbarSelectGlyphAdvance else scaledPixels(hotbarSelectGlyphAdvance - 1, scale) + 1) {
                "hotbar scale=$scale 选中框前进量与宽度不一致"
            }
            check(debugCodepoint == baseCodepoint + hotbarIconCount) {
                "hotbar scale=$scale overlay 图标码位必须紧跟基础图标"
            }
            hotbarImagesByScale[if (scale == defaultHudScale) "hotbar_hud" else "hotbar_hud_s$scale"] = body
        }

        // 每份都要自带 `images:` 根键——它们是独立文档，不是被拼起来的片段。
        val imageParts = linkedMapOf(
            "bot_avatar" to botAvatarBaseImages,
            "avatar_crown" to "images:\n" + avatarCrownImages
        ).apply {
            putAll(counterImagesByScale)
            putAll(hotbarImagesByScale)
        }
        cardGlyphImagesByHeight.forEach { (height, body) ->
            imageParts["card_h$height"] = "images:\n" + body
        }
        avatarPixelImagesByScale.forEach { (scale, body) ->
            imageParts["avatar_px_s$scale"] = "images:\n" + body
        }
        imageParts.forEach { (name, content) ->
            writeText(imagesDir.resolve("$name.yml"), content)
        }

        // 【硬上限校验，不只是打日志】：这个故障之前已经在线上炸过一次——
        // 生成器当时只把体积打印出来，没人盯着构建日志，直到服务端抛
        // YamlEngineException 才发现。留一道构建期断言，下次谁把档位调大到
        // 单份再次越界，构建就当场失败，而不是等运行时。
        //
        // 阈值取 3,000,000 而不是正好 3,145,728：留一点余量，且 code point 数
        // 与字节数在纯 ASCII 下相等（这些文件只有 ASCII），可以直接比字节数。
        val yamlCodePointLimit = 3_000_000L
        imageParts.keys.forEach { name ->
            val part = imagesDir.resolve("$name.yml")
            check(part.length() < yamlCodePointLimit) {
                "configuration/images/$name.yml 有 %,d 字节，超过 SnakeYAML 单文档上限（%,d）。".format(
                    part.length(), yamlCodePointLimit
                ) + "把这一族再拆细，或调小对应的档位范围/步长。"
            }
        }

        // 构建期自检 + 体积可见性。档位放开后条目数是乘出来的，很容易一个参数改大就爆量；
        // 这里把各族条目数打出来，省得等到客户端加载卡顿才发现。
        val cardEntryCount = cardGlyphHeightTiers.size * cardGlyphDownOffsetTiers.size * cardIds.size
        val avatarEntryCount = avatarDownOffsetTiers.size * avatarGlyphsPerTier
        val crownEntryCount = avatarDownOffsetTiers.size * crownGlyphsPerTier
        val botEntryCount = (avatarDownOffsetTiers.size - 1) * botAvatarGlyphs.size + botAvatarGlyphs.size
        val counterEntryCountPerScale = counterDownOffsetTiers.size * counterGlyphFiles.size
        val counterEntryCount = counterScaleTiers.size * counterEntryCountPerScale
        val hotbarEntryCount = hotbarScaleTiers.size * (hotbarIconCount + 1)
        val totalImageBytes = imageParts.keys.sumOf { imagesDir.resolve("$it.yml").length() }
        logger.lifecycle(
            ("[muz] configuration/images/ 共 %,d 条"
                + "（牌 %,d + 头像 %,d + 王冠 %,d + bot %,d + 记牌 %,d + hotbar %,d），%.1f MiB / %d 份").format(
                cardEntryCount + avatarEntryCount + crownEntryCount + botEntryCount + counterEntryCount + hotbarEntryCount,
                cardEntryCount, avatarEntryCount, crownEntryCount, botEntryCount, counterEntryCount, hotbarEntryCount,
                totalImageBytes / 1024.0 / 1024.0,
                imageParts.size
            )
        )
        imageParts.keys.forEach { name ->
            val part = imagesDir.resolve("$name.yml")
            logger.lifecycle(
                "[muz]   images/%-13s %6.2f MiB（上限 %.1f）".format(
                    "$name.yml", part.length() / 1024.0 / 1024.0, yamlCodePointLimit / 1024.0 / 1024.0
                )
            )
        }
        // 末码位直接按默认 100 档已生成条目总数计算，避免把「最后档起点」误当成族末码位。
        // 75/125 另有独立字体与码位窗口，不改变旧 100 档 E900..FA45 契约。
        val counterLastCodepoint = counterGlyphCodepointStart + counterEntryCountPerScale - 1
        check(counterLastCodepoint == counterGlyphCodepointStart
            + counterDownOffsetTiers.size * counterGlyphFiles.size - 1) {
            "默认 100 档记牌族末码位计算与生成条目数量不一致：$counterLastCodepoint"
        }
        counterScaleTiers.forEachIndexed { scaleIndex, scale ->
            val start = counterScaleCodepointStarts[scaleIndex]
            val last = start + counterEntryCountPerScale - 1
            check(last <= maxGlyphCodepoint) { "记牌 scale=$scale 末码位越界：$last" }
            val startHex = Integer.toHexString(start).uppercase().padStart(4, '0')
            val lastHex = Integer.toHexString(last).uppercase().padStart(4, '0')
            logger.lifecycle(
                "[muz] 记牌 scale=$scale 码位：0x$startHex..0x$lastHex"
                    + "（${counterDownOffsetTiers.size} 档 × ${counterGlyphFiles.size} 码位/档）"
            )
        }

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
        dependsOn(generateHotbarVanillaFonts)
        filteringCharset = Charsets.UTF_8.name()
        from(generatedJarResourcesDir)
        // 仅 26.1.2 目标将经过 SHA-1 校验的原版字体快照嵌入 JAR；其它目标不声明缺失的源文件。
        if (muzTarget.id == "paper-26.1.2") {
            from(vanillaHotbarFontArchive) {
                into("hotbar-font")
            }
        }
        // 【清掉上一次构建留下的单份 images.yml】：images.yml 已按族拆成
        // configuration/images/*.yml，但 Gradle 的增量拷贝只做「源 → 目标」，
        // 不会删除目标里那些源已经不存在的旧文件。留着它的后果很重：
        // jar 里同时存在单体与拆分两份，所有字形被重复定义，而且那份单体必然
        // 超过 SnakeYAML 的 3 MiB 单文档上限，CraftEngine 加载时直接抛异常。
        //
        // 放在 doFirst 而不是靠 outputs.upToDateWhen：只删一个已知的失效文件，
        // 不必让整个 processResources 每次都重跑。
        doFirst {
            destinationDir
                .resolve("craftengine/$resourceNamespace/configuration/images.yml")
                .delete()
        }
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
