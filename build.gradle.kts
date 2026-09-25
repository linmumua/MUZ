import com.github.jengelman.gradle.plugins.shadow.tasks.ShadowJar
import org.gradle.api.tasks.bundling.Zip
import java.awt.BasicStroke
import java.awt.Color
import java.awt.RenderingHints
import java.awt.geom.Ellipse2D
import java.awt.geom.GeneralPath
import java.awt.image.BufferedImage
import java.io.File
import java.io.ByteArrayOutputStream
import java.nio.file.Files
import java.util.LinkedHashMap
import java.util.jar.JarFile
import java.util.zip.Deflater
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import java.util.zip.ZipOutputStream
import javax.imageio.ImageIO

plugins {
    java
    id("org.jetbrains.kotlin.jvm") version "2.3.20"
    // 【为什么 shadow 与 TabooLib 并存，而不是二选一】
    // 两者职责不重叠，互相替换都会丢东西：
    //   * TabooLib 插件（taboolibMainTask）只做两件事——把 JAR 里的类按 relocations 重定位，
    //     并把 TabooLib 的 loader/引导层与 taboolib/env.properties 注入同一个 JAR。
    //     它【不做】fat jar 合并：它把嵌入式依赖的收录交给 Gradle 原生的 `jar` 任务
    //     （见插件里 `tasks.jar { from(taboo.include) ... }`），而本项目的 `jar` 已 enabled=false，
    //     由 shadowJar 承担合并职责。
    //     【1.10.54 起 relocations 为空】：原先要重定位的 SnakeYAML / ASM / commons-lang3
    //     已连同 gson / sqlite-jdbc / TabooLib 反射工具一起移出 JAR，改由 MuzPluginLoader 在
    //     运行期按【原始坐标】下载。库既然按原包名提供，就绝不能再改写业务字节码里的引用
    //     （那会指向不存在的包路径），所以 shadowJar 与 taboolib 两处 relocate 都已删除。
    //   * shadow 只做 fat jar 合并与 relocate，注入不了 TabooLib 的 env 描述文件。
    // 因此保留 shadowJar 作为【合并】的生产者，由 taboolibMainTask 对它的产物做重定位；
    // 但两者不再共用同一个文件（那会让产物不可复现、taboolibMainTask 永不 up-to-date，
    // 并在 shadowJar 为 UP-TO-DATE 时对已重定位的 JAR 二次重定位）。现在的分工是：
    //   shadowJar        → build/<targetId>/tmp/shaded/MUZ-<version>.jar       （中间件）
    //   taboolibMainTask → build/<targetId>/tmp/shaded/MUZ-<version>-<targetId>.jar（重定位件）
    //   packagePluginJar → build/<targetId>/libs/MUZ-<version>-<targetId>.jar  （最终件）
    // 最终归档的路径与文件名（build/<targetId>/libs/MUZ-<version>-<targetId>.jar）完全不变，
    // 也避免让 TabooLib 插件接管 `jar` 任务后与 shadow 抢同一份归档。
    // 详细理由（含 classifier 的实测行为）见下面「发布归档的中间产物与最终路径」一段的注释。
    id("io.izzel.taboolib") version "2.0.38"
    id("com.gradleup.shadow") version "9.3.0"
}

group = "linmumua"
version = "1.10.55"

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
val tableGadgetSpeechBubbleId = "table_gadget_speech_bubble"






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
/** 桌内九格道具栏独立字体；不复用已退役的 minecraft:muz_hotbar。 */
val gadgetBarFont = "minecraft:${resourceNamespace}_gadget_bar"
val gadgetBarBaseCodepoint = 0xF700
val gadgetBarSelectCodepoint = 0xF701
val gadgetBarIconCodepointStart = 0xF702
val gadgetBarIconKinds = listOf("egg", "water", "tomato", "speech")
val gadgetBarCellWidth = 22
val gadgetBarCellHeight = 22
val gadgetBarCellAdvance = 22
val gadgetBarIconWidth = 16
val gadgetBarIconHeight = 16
val gadgetBarSlotCount = 9

// 九格道具栏并入出牌 HUD 后的固定下移像素：九格栏底图原 ascent=cellHeight，作为独立一行时会贴在 HUD 最顶；
// 并入 BossBar 第四行必须把它整体下沉到记牌器行下方，所以 provider 的 ascent 改为 cellHeight - 本值。
// 这是固定常量档（同一 JVM 里运行期不能改），要在四条行里重排必须改这里并重建资源。
// 插件侧 PackAssets.GADGET_BAR_ROW_DOWN_OFFSET / PackTiers.GADGET_BAR_ROW_DOWN_OFFSET 与此处一一对应。
// 取值依据：默认记牌器 offset-down=122 时，记牌器 frame 底边在基线下 122+3+12=137 像素；
// 九格栏顶边在基线下 本值-22。取 160 让栏顶落在 138，与记牌器留 1 像素间隙（152 会重叠 7 像素）。
val gadgetBarRowDownOffset = 160

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
    val counterOffsets: List<Int>
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
        if (path == "version") {
            scalars[path] = value.trim('\"', '\'').toIntOrNull()
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
    require(scalars.keys == setOf("version")) {
        val unknown = scalars.keys - setOf("version")
        throw GradleException("$source 存在未知标量字段：$unknown")
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
        counterOffsets = nonEmpty("counter.offsets")
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

// counter 的构建期缩放档与码位窗口；Hotbar 资源链已退役，不再从 profile 读取或生成。
val supportedHudScales = listOf(75, 100, 125)
val allCounterScaleTiers = supportedHudScales
val allCounterScaleCodepointStarts = listOf(0xED00, counterGlyphCodepointStart, 0xEE00)
val defaultHudScale = 100
val counterScaleTiers = resourceProfile.counterScales.sorted()
val counterScaleCodepointStarts = counterScaleTiers.map {
    allCounterScaleCodepointStarts[allCounterScaleTiers.indexOf(it)]
}

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

/** 九格 MUZ 道具栏底图：不含图标与选框，运行期按槽位叠加。 */
fun renderGadgetBarBase(selected: Boolean): BufferedImage {
    val out = BufferedImage(gadgetBarCellWidth, gadgetBarCellHeight, BufferedImage.TYPE_INT_ARGB)
    val g = out.createGraphics()
    g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_OFF)
    // 可见宽度按锁定后的 21 列绘制（见 writeGadgetBarGlyph）：按 22 列画会让右边框落在被裁掉的第 22 列，栏最右侧不闭合。
    val drawWidth = gadgetBarCellAdvance - 1
    g.color = if (selected) Color(0x4E, 0x42, 0x16, 0xF2) else Color(0x18, 0x18, 0x20, 0xE8)
    g.fillRect(1, 1, drawWidth - 2, gadgetBarCellHeight - 2)
    g.color = Color(0xA0, 0xA8, 0xB8, 0xD0)
    g.drawRect(0, 0, drawWidth - 1, gadgetBarCellHeight - 1)
    g.dispose()
    return out
}

fun renderGadgetBarSelect(): BufferedImage {
    val out = BufferedImage(gadgetBarCellWidth, gadgetBarCellHeight, BufferedImage.TYPE_INT_ARGB)
    val g = out.createGraphics()
    g.color = Color(0xFF, 0xE0, 0x40, 0xFF)
    g.stroke = BasicStroke(2f)
    g.drawRect(1, 1, gadgetBarCellWidth - 3, gadgetBarCellHeight - 3)
    g.dispose()
    return out
}

fun renderGadgetBarIcon(kind: String): BufferedImage {
    val icon = when (kind) {
        "egg" -> ImageIO.read(project.file("src/main/resources/debug-gadget-icons/egg.png"))
        "water" -> ImageIO.read(project.file("src/main/resources/debug-gadget-icons/water_bucket.png"))
        "tomato" -> BufferedImage(16, 16, BufferedImage.TYPE_INT_ARGB).also { drawTomatoIcon(it, 1, 0, 14, 16) }
        "speech" -> BufferedImage(16, 16, BufferedImage.TYPE_INT_ARGB).also { image ->
            val fill = 0xFFE8F1FF.toInt(); val edge = 0xFF3A526E.toInt()
            for (y in 2..11) for (x in 2..13) image.setRGB(x, y, if (x == 2 || x == 13 || y == 2 || y == 11) edge else fill)
            for (x in 4..6) image.setRGB(x, 12, edge)
            for (x in 5..6) image.setRGB(x, 13, edge)
        }
        else -> error("未知九格图标：$kind")
    }
    // 图标字形本身也必须保持 22px advance，运行期才能用负空格叠回同一槽位。
    return BufferedImage(gadgetBarCellWidth, gadgetBarCellHeight, BufferedImage.TYPE_INT_ARGB).also { canvas ->
        val x = (gadgetBarCellWidth - icon.width) / 2
        val y = (gadgetBarCellHeight - icon.height) / 2
        canvas.createGraphics().also { graphics ->
            graphics.drawImage(icon, x, y, null)
            graphics.dispose()
        }
    }
}

/**
 * 写出九格栏字形并锁定净前进量。
 *
 * <p>Minecraft BitmapProvider 的 advance = 最右不透明列 + 2（与 PNG 宽度无关）。未锁定时底图实测
 * advance 23、图标 17..19、选框 22，运行期却统一按 [gadgetBarCellAdvance]=22 回退，于是每格宽度随图标
 * 漂移，整条栏会左右抖动，固定位置布局也无法闭合。这里把画布裁成 advance-1 列，并在最右列底部放
 * alpha=1 的不可见锚点（与记牌器同一做法），保证所有九格栏字形 advance 恰好等于 22。
 * 插件侧 PackAssets.GADGET_BAR_CELL_ADVANCE 与此处一一对应。
 */
fun writeGadgetBarGlyph(target: File, image: BufferedImage) {
    val width = gadgetBarCellAdvance - 1
    val locked = BufferedImage(width, gadgetBarCellHeight, BufferedImage.TYPE_INT_ARGB)
    val g = locked.createGraphics()
    g.drawImage(image, 0, 0, null)
    g.dispose()
    val anchorX = width - 1
    val anchorY = gadgetBarCellHeight - 1
    if ((locked.getRGB(anchorX, anchorY) ushr 24) == 0) {
        locked.setRGB(anchorX, anchorY, 0x01FFFFFF)
    }
    target.parentFile.mkdirs()
    ImageIO.write(locked, "png", target)
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

/** 语音面板使用的独立气泡物品贴图；不占用任何 Hotbar 字形码位。 */
fun writeSpeechBubbleTexture(target: File) {
    val out = BufferedImage(16, 16, BufferedImage.TYPE_INT_ARGB)
    val fill = 0xFFE8F1FF.toInt()
    val edge = 0xFF3A526E.toInt()
    for (y in 2..11) for (x in 2..13) {
        val border = x == 2 || x == 13 || y == 2 || y == 11
        out.setRGB(x, y, if (border) edge else fill)
    }
    for (x in 4..6) out.setRGB(x, 12, edge)
    for (x in 5..6) out.setRGB(x, 13, edge)
    target.parentFile.mkdirs()
    ImageIO.write(out, "png", target)
}

/** 番茄物品模型的独立 16×16 纹理，避免继续依赖已退役 Hotbar 字形贴图。 */
fun writeTomatoGadgetTexture(target: File) {
    val out = BufferedImage(16, 16, BufferedImage.TYPE_INT_ARGB)
    drawTomatoIcon(out, 1, 0, 14, 16)
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


// 【1.10.54 起，这个集合刻意保持为空】
// 原先它列出真正要打进 JAR 的内嵌库（SnakeYAML / gson / sqlite-jdbc / TabooLib 反射工具
// 及其 Kotlin、ASM、commons-lang3 依赖），由 shadowJar 合并、taboolibMainTask 重定位。
// 本版本按用户要求把这些库【全部外置】：改由
// src/main/java/linmumua/doudizhu/MuzPluginLoader.java 在插件类加载器建立【之前】从 Maven
// 下载（坐标见该类的 DEPENDENCY_COORDINATES，仓库见它的两个 repository 常量）。
// 【为什么保留这个 configuration 而不删掉】shadowJar.configurations 与 taboolibMainTask 的
// inputs 都指向它（见下面两处）；留成一个空集，能让「JAR 里不内嵌任何第三方库」只有一个
// 真相来源，同时不改动已被多轮验证过的任务图形状。要恢复内嵌只需往这里加坐标。
val embeddedLibraries by configurations.creating

// ============================================================================
// TabooLib 反射工具的运行期依赖坐标
//
// 【为什么必须显式声明】io.izzel.taboolib:common-reflex 的 POM 里【没有】任何
// <dependencies>，TabooLib 自己靠运行期模块索引解析传递依赖；而本项目是把它当普通 JAR
// （1.10.54 起改为运行期下载，之前是内嵌）使用，Gradle 与 Paper 的 Aether 都解析不到它的
// 传递依赖。实测（jdeps 扫 common-reflex 全部 118 个 class）它确实引用下列外部包子集，
// 缺任何一个都会在运行期抛 NoClassDefFoundError：
//   kotlin.*                      → kotlin-stdlib（Reflex 是 Kotlin 伴生对象，大量 Intrinsics/Lazy/集合扩展）
//   org.objectweb.asm.*           → asm（ClassReader/ClassVisitor/ClassWriter/Type，以及 SignatureReader 等；
//                                   org.objectweb.asm.signature 这个包就【在 asm 核心包里】，不在 asm-tree）
//   org.apache.commons.lang3.*    → commons-lang3（ArrayUtils / StringUtils / JavaVersion）
// 版本选取原则：kotlin-stdlib 用本项目 Kotlin 插件同版本（2.3.20），避免 classpath 上出现两份
// stdlib；asm 与 commons-lang3 取本机 Gradle 缓存里已有且较新的版本。
// 【1.10.54】这 4 条现在只服务【测试】classpath：运行期由 MuzPluginLoader 用同一批坐标下载，
// 两处坐标必须逐字一致（由 MuzPluginLoaderCoordinatesTest 的源码契约守护）。
// ============================================================================
val taboolibReflexVersion = "6.3.0-75b18a2"
val taboolibReflexRuntimeNotation = listOf(
    "io.izzel.taboolib:common-reflex:$taboolibReflexVersion",
    "org.jetbrains.kotlin:kotlin-stdlib:2.3.20",
    "org.ow2.asm:asm:9.10.1",
    "org.apache.commons:commons-lang3:3.20.0"
)

repositories {
    mavenCentral()
    // TabooLib 官方仓库：io.izzel.taboolib:* 的各个模块（common、common-reflex、platform-* 等）
    // 与 taboolib-gradle-plugin 都从这里取。
    maven("https://repo.tabooproject.org/repository/releases/")
    maven("https://repo.papermc.io/repository/maven-public/")
    maven("https://repo.extendedclip.com/content/repositories/placeholderapi/")
    maven("https://repo.momirealms.net/releases/")
    maven("https://jitpack.io")
}

dependencies {
    // 【为什么 paper-api 一个坐标就够 loader 用】MuzPluginLoader 除了 Paper 的 loader API，
    // 还要用 Aether 的 Dependency / DefaultArtifact / RemoteRepository —— 它们是
    // MavenLibraryResolver.addDependency/addRepository 的形参类型，由 paper-api 的 POM 以
    // compile 作用域声明（maven-resolver-provider 3.9.6 → maven-resolver-api 1.9.18）传递进
    // 编译 classpath。因此 loader 的可依赖范围确实是「JDK / Paper / Aether」，无需额外声明。
    compileOnly("io.papermc.paper:paper-api:${muzTarget.paperApiDependency}")
    compileOnly("me.clip:placeholderapi:2.12.2")
    compileOnly("com.github.MilkBowl:VaultAPI:1.7") {
        exclude(group = "org.bukkit", module = "bukkit")
    }
    compileOnly("net.momirealms:craft-engine-bukkit:0.0.67")
    compileOnly("net.momirealms:craft-engine-core:0.0.67")
    // ---- 以下四个坐标 1.10.54 起由 implementation 改为 compileOnly ----
    // 它们不再打进插件 JAR，运行期由 MuzPluginLoader 从 Maven 下载提供；编译期只要 classpath。
    // 四者都必须保持原包名，理由分别是：
    //   * gson（com.google.gson）：Gson 的 reflective 序列化靠真实类型名解析，改名会让
    //     Javadoc 与用户配置里出现的类型名与实际不符；
    //   * sqlite-jdbc（org.sqlite）：DatabaseManager 用 Class.forName("org.sqlite.JDBC") +
    //     DriverManager 走标准 SPI 发现路径，改名会让 JDBC 驱动的自动注册失效；
    //   * snakeyaml（org.yaml.snakeyaml）：仍用它做运行期 YAML，不换解析器；
    //   * common-reflex（taboolib.library.reflex）：TabooLib 只作工具层，不参与注解生命周期。
    compileOnly("com.google.code.gson:gson:2.11.0")
    compileOnly("org.yaml:snakeyaml:2.6")
    compileOnly("org.xerial:sqlite-jdbc:3.46.1.0")
    compileOnly("io.izzel.taboolib:common-reflex:$taboolibReflexVersion")
    compileOnly("com.mysql:mysql-connector-j:8.4.0")
    testImplementation("io.papermc.paper:paper-api:${muzTarget.paperApiDependency}")
    // 【测试依赖单独保留】compileOnly 不进 testRuntimeClasspath，而测试要真实加载与运行期
    // 同一套库（SnakeYAML 解析、sqlite 驱动、gson 序列化、TabooLib 反射），所以逐条显式声明。
    // 坐标必须与 MuzPluginLoader.DEPENDENCY_COORDINATES 完全一致，否则「测试通过」就不再代表
    // 产物行为；一致性由 MuzPluginLoaderCoordinatesContractTest 的源码契约守护。
    testImplementation("com.google.code.gson:gson:2.11.0")
    testImplementation("org.yaml:snakeyaml:2.6")
    testImplementation("org.xerial:sqlite-jdbc:3.46.1.0")
    taboolibReflexRuntimeNotation.forEach { testImplementation(it) }
    // 外置依赖探针（MuzExternalDependencyProbe）要用 Aether 自己开一个【离线】会话，
    // 才能在「同一份缓存」上证明断网也能解析。这两个实现类在 paper-api 的 POM 里是
    // runtime 作用域（编译期取不到），所以测试编译期显式声明同一版本。
    // 版本 1.9.18 就是 paper-api 自己 pin 的那两个（见它的 POM），不是随手选的。
    testCompileOnly("org.apache.maven.resolver:maven-resolver-connector-basic:1.9.18")
    testCompileOnly("org.apache.maven.resolver:maven-resolver-transport-http:1.9.18")
    testImplementation(platform("org.junit:junit-bom:5.13.4"))
    testImplementation("org.junit.jupiter:junit-jupiter")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

java {
    toolchain.languageVersion.set(JavaLanguageVersion.of(muzTarget.javaVersion))
    withSourcesJar()
}



// ============================================================================
// 发布归档的中间产物与最终路径
//
// 【为什么要拆成「中间件 + 最终件」三段】TabooLibMainTask 只会把 inJar【就地重写】
// （它读 inJar、写同目录的临时文件、再覆盖回一个由 inJar 名字推出的文件），因此只要
// inJar 就是最终产物，就会出现三个真实问题：
//   1. 归档不可复现：TabooLib 重写时把每个条目的时间重置为构建时刻，同源码两次构建
//      SHA-256 必然不同；
//   2. taboolibMainTask 永不 up-to-date：它只声明 @InputFile(inJar)、不声明任何输出，
//      Gradle 无法判定增量，每次都重跑；
//   3. 二次重定位：shadowJar 是 UP-TO-DATE 时，inJar 已经是「已重定位」的内容，
//      再跑一遍就是在已重定位的产物上再重定位一次。
// 所以这里把输入输出彻底分离：
//   shadowJar        → <build>/tmp/shaded/MUZ-<version>.jar        （未重定位的中间件）
//   taboolibMainTask → <build>/tmp/shaded/MUZ-<version>-<id>.jar   （classifier 拼出的重定位件）
//   packagePluginJar → <build>/libs/MUZ-<version>-<id>.jar         （确定性重打包后的最终件）
// 中间件从不被就地改写（TabooLibMainTask 只读 inJar），因此不存在二次重定位；
// 最终件由我们自己的固定时间戳 + 排序重打包产出，因而可复现。
// 【产物路径与文件名保持不变】：最终归档仍是 build/<targetId>/libs/MUZ-<version>-<targetId>.jar。
//
// 【为什么 taboolibMainTask 用 classifier 而不是「重定位后再复制」】：实测
// TabooLibMainTask.relocate 会把 <inJar 去扩展名的名字> + "-" + classifier + ".jar" 写到
// inJar 的同目录，并且【只读】inJar。于是 inJar 保持中间件原样、输出落在另一个文件上，
// Gradle 的输入/输出指纹才能各自稳定，第二次运行才会 UP-TO-DATE。
// ============================================================================
val shadedStagingDir = layout.buildDirectory.dir("tmp/shaded")
val shadedJarFileName = "MUZ-${project.version}.jar"
val finalJarFileName = "MUZ-${project.version}-${muzTarget.id}.jar"
val shadedJarFile = shadedStagingDir.map { it.file(shadedJarFileName) }
/** taboolibMainTask 的落点：中间件名 + "-" + muzTarget.id。 */
val relocatedJarFile = shadedStagingDir.map { it.file(finalJarFileName) }
/** 最终发布归档；路径与文件名与历史上完全一致。 */
val finalJarFile = layout.buildDirectory.file("libs/$finalJarFileName")

// ZIP 能表达的最早时间（1980-02-01T00:00:00Z），也正好是 Gradle 关闭 preserveFileTimestamps
// 时使用的常量。固定它之后，同一输入的重打包逐字节一致。
val reproducibleZipTime = 318211200000L

/**
 * 以固定时间戳与按名字排序的条目重打包一个 ZIP/JAR。
 *
 * <p>【为什么需要这一步】TabooLibMainTask 重写归档时会把条目时间设为构建时刻，产物因此
 * 不可复现。这里只做「读条目 → 按名字排序 → 用固定时间写回」，不改动任何字节内容，
 * 所以既保留 TabooLib 的重定位结果，又让同等输入产出同等字节。
 */
fun repackZipDeterministically(source: File, target: File) {
    val payloads = LinkedHashMap<String, ByteArray>()
    ZipFile(source).use { zip ->
        zip.entries().asSequence()
            .filter { !it.isDirectory }
            .map { it.name }
            .sorted()
            .forEach { name ->
                payloads[name] = zip.getInputStream(zip.getEntry(name)).use { it.readBytes() }
            }
    }
    target.parentFile.mkdirs()
    val buffer = ByteArrayOutputStream()
    ZipOutputStream(buffer).use { zip ->
        zip.setLevel(Deflater.DEFAULT_COMPRESSION)
        // MANIFEST.MF 排在最前是 JAR 的约定；其余条目按名字排序。
        val manifest = payloads.remove("META-INF/MANIFEST.MF")
        if (manifest != null) {
            writeZipEntry(zip, "META-INF/MANIFEST.MF", manifest)
        }
        payloads.forEach { (name, bytes) -> writeZipEntry(zip, name, bytes) }
    }
    target.outputStream().use { it.write(buffer.toByteArray()) }
}

/** 写一个固定时间戳的 ZIP 条目；目录条目一律不写（JAR 不需要，且能让条目集合更确定）。 */
fun writeZipEntry(zip: ZipOutputStream, name: String, bytes: ByteArray) {
    zip.putNextEntry(ZipEntry(name).apply { time = reproducibleZipTime })
    zip.write(bytes)
    zip.closeEntry()
}

tasks.named<Jar>("jar") {
    enabled = false
    // 【为什么必须断开这个 finalizer】io.izzel.taboolib 插件会在它自己的 `tasks.named('jar')`
    // 配置块里执行 `jarTask.finalizedBy(taboolibMainTask)`。本项目的 `jar` 已 enabled=false，
    // 而 Kotlin 的 compileTestKotlin 会依赖 `jar`（于是 `compileTestJava`/`testClasses` 也会），
    // 结果只要跑一次测试编译就会顺着 finalizer 触发 taboolibMainTask，【就地改写发布 JAR】。
    // 发布归档改由下面显式声明的 packagePluginJar / verifyRelocatedSnakeYaml / build 链产出，
    // 不再依赖 `jar` 的 finalizer，因此这里清空它。
    setFinalizedBy(emptyList<Any>())
}

// 【为什么还要在 afterEvaluate 里再清一次】io.izzel.taboolib 插件自己是在 afterEvaluate 阶段
// 给 `jar` 挂 finalizer 的（实测：只在上面的 configuration action 里清一次并不生效，任务图里
// compileTestJava 仍会经 jar → taboolibMainTask 触发打包）。本脚本主体注册的 afterEvaluate 晚于
// 插件的，因此这一次清理必然排在最后，能把 finalizer 真正拿掉。
afterEvaluate {
    tasks.named<Jar>("jar") { setFinalizedBy(emptyList<Any>()) }
}

tasks.named<ShadowJar>("shadowJar") {
    // 中间件名不含 targetId：taboolibMainTask 会用 classifier 把它补成最终名。
    archiveFileName.set(shadedJarFileName)
    destinationDirectory.set(shadedStagingDir)

    // 【为什么仍然显式列 embeddedLibraries】：shadowJar 默认只合并 `runtimeClasspath`
    // 里那些被 shadow 判定为「应该内嵌」的依赖，而本项目把「需要内嵌」的集合单独定义成
    // embeddedLibraries。显式 set(...) 让它成为唯一真相来源，不依赖 shadow 的默认推断。
    // 【1.10.54 起这个集合是空的】：原先内嵌的 SnakeYAML / gson / sqlite-jdbc / TabooLib
    // 反射工具及其 Kotlin、ASM、commons-lang3 依赖已全部改为运行期下载，本 JAR 因此只剩
    // MUZ 自己的类与资源。这里刻意保留 `configurations.set(...)` 这一行（而不是删掉或换成
    // 默认的 runtimeClasspath），是为了让「有哪些库会被打进产物」永远只由 embeddedLibraries
    // 一个地方决定：将来若真的要再内嵌什么，加进那个 configuration 即可。
    configurations.set(listOf(embeddedLibraries))
    // 【1.10.54 起这里没有任何 relocate】relocate 是「改名躲冲突」手段，其前提是库被内嵌进
    // 这个 JAR。本版本把 SnakeYAML / gson / sqlite-jdbc / TabooLib 反射工具及其 Kotlin、ASM、
    // commons-lang3 全部改为由 MuzPluginLoader 按【原始坐标】下载，运行期加载到的就是原包名。
    // 此时若还保留 relocate，业务字节码里的 org.yaml.snakeyaml.* / org.objectweb.asm.* /
    // org.apache.commons.lang3.* 会被统一改写成 linmumua.doudizhu.libs.*，而那个包在下载下来
    // 的 JAR 里根本不存在 —— 运行期必然 NoClassDefFoundError。所以这三条 relocate 是
    // 【被外置取代】而删除的，不是「漏了」；不要按旧注释把它们补回来。
    duplicatesStrategy = DuplicatesStrategy.EXCLUDE
    exclude("META-INF/*.SF", "META-INF/*.DSA", "META-INF/*.RSA")
    // Maven 的 pom/pom.properties 对运行毫无用处，只是让归档噪声变大。
    exclude("META-INF/maven/**")
    // 【为什么要显式写 manifest 而不是让它合并】合并归档时 `META-INF/MANIFEST.MF` 会按
    // duplicatesStrategy 取「先遇到的那一份」，内容取决于合并顺序，既不可复现，也可能把
    // 某个依赖的无关属性带进产物。这里固定成项目真正需要的最小集合。
    // 【为什么不写 Multi-Release】1.10.53 及以前必须写它，是因为内嵌的 SnakeYAML 与
    // sqlite-jdbc 带 META-INF/versions/9 下的类；这两个库 1.10.54 起改为运行期下载，
    // 本 JAR 已不含任何多版本条目（只有 MANIFEST 一处声明），留着该属性会与事实不符。
    manifest {
        attributes(
            "Manifest-Version" to "1.0"
        )
    }
    // 可复现的两项开关（Gradle 9 起 preserveFileTimestamps 默认已是 false，这里显式写出来
    // 是为了不依赖 Gradle 版本默认值；fileOrder 必须显式打开，否则条目顺序跟文件系统相关）。
    isPreserveFileTimestamps = false
    isReproducibleFileOrder = true
}

// ============================================================================
// TabooLib 打包引导
//
// 【为什么 taboolibMainTask 必须由我们显式设 inJar】
// TabooLib 的 Gradle 插件只会把 taboolibMainTask 挂到 Gradle 原生的 `jar` 任务上
// （插件的 apply 逻辑是 `tasks.jar { ... }` + 把 archiveFile 赋给任务的 inJar）。
// 本项目的 `jar` 已 enabled=false，真正产出插件归档的是 shadowJar，所以我们自己做同一件事：
// 把 shadowJar 的【中间件】交给 taboolibMainTask。
//
// 【taboolibMainTask 到底做了什么 / 没做什么（以实测产物为准，不要按名字想象）】
// 做：按 taboolib.relocate 的映射把 JAR 内所有 class 及其引用重写；把 Kotlin 运行期
//     重定位到 kotlin2320/ 前缀（TabooLib 的固定行为，避免与别的插件抢 kotlin.* 包名）；
//     重新打包并写入 <inJar 去扩展名的名字> + "-" + classifier + ".jar"；写入
//     META-INF/taboolib/env.properties。
//     【勘误（本轮实测）】它【不是】无条件「就地覆盖 inJar」：relocate 只读 inJar，落点是
//     按 classifier 拼出的【另一个】文件（classifier 为 null 时才会落回 inJar 本身）。
//     本配置显式把 classifier 设成 muzTarget.id，因此 inJar 全程只读、输出独立成文件。
//     TabooLib 写归档时会把条目时间重置为构建时刻，所以产物【不可复现】——这正是后面
//     packagePluginJar 要用固定时间戳重打包一次的原因。
// 没做：它【不会】把 TabooLib 的 loader/引导层或任何功能模块塞进 JAR —— 模块收录原本由
//      Gradle 原生 `jar` 任务的 `from(taboo.include)` 负责，而我们走 shadowJar，
//      所以「JAR 里有哪些内嵌库」完全由下面 shadowJar 的 embeddedLibraries 决定。
// ============================================================================
taboolib {
    // 【1.10.54 起这里没有 relocate】原先唯一一条是把 SnakeYAML 重定位到
    // linmumua.doudizhu.libs.snakeyaml。SnakeYAML 已改为运行期按原坐标下载（包名仍是
    // org.yaml.snakeyaml），保留这条重定位会让 MuzYamlConfig 的字节码指向下载下来的 JAR 里
    // 不存在的包路径，运行期必然 NoClassDefFoundError —— 所以它是【被外置取代】而删除的。
    // （TabooLib 把 kotlin.* 重定位到 kotlin2320/ 是它自己的固定行为，不受这一行影响；
    // 本 JAR 已不含 kotlin，该行为自然成为空操作。）
    version {
        // Toolchain 版本号，写进 env.properties 供 TabooLib 生态识别。
        taboolib = "6.3.0-75b18a2"
        coroutines = "1.7.3"
        // 不重定位 TabooLib 自身：TabooLib 的 JS/脚本等特性靠「包名就是 taboolib.*」定位，
        // 重定位会破坏这部分约定。本项目的 taboolib.library.reflex.* 引用与下载产物一致。
        skipTabooLibRelocate = true

        // MUZ 自己维护 paper-plugin.yml（含 api-version、folia-supported、dependencies 的
        // join-classpath 与权限声明），绝不能让 TabooLib 平台文件覆盖它。
        // paper-plugin.yml 的 ${version} / ${apiVersion} 占位由下面 processResources 展开，
        // 这条流水线与 TabooLib 无关，开 skipPlatformFile 后也不会被改写。
        skipPlatformFile = true
    }

    env {
        // 【如实说明】：这一行只让 Gradle 插件登记「引导层」这一模块概念并生成
        // env.properties，它【不会】把 common 模块的 class 打进 JAR（原因见上面
        // 「没做」那段）。MUZ 也没有任何代码调用 TabooLib.setup()，所以 JAR 里
        // 生成的 META-INF/taboolib/env.properties 只是一份声明，没有人读它 ——
        // 唯一会读它的 taboolib.common.PrimitiveSettings 属于 loader，而 loader 不在 JAR 里。
        //
        // 【为什么不干脆把 loader 一起内嵌】：loader 会连带拉进 jar-relocator 与
        // common-platform-api，而后者假定 taboolib.platform.BukkitPlugin 是插件入口；
        // MUZ 用的是自己的 JavaPlugin（linmumua.doudizhu.DoudizhuPlugin），装上会让
        // EventBus 与 PlatformFactory 拿不到实例并刷一屏堆栈。TabooLib 在本项目里
        // 纯作工具库使用，不参与注解生命周期与插件入口装配 —— 真正需要的只是
        // common-reflex，它 1.10.54 起由 MuzPluginLoader 在运行期下载（连同
        // Kotlin/ASM/commons-lang3），同样不进这个 JAR。
        // 保留这一行是为了忠实恢复历史配置形状，不代表运行期加载 TabooLib。
        install("common")
    }
}

// 【为什么 inJar 与 classifier 必须在 afterEvaluate 里设置，而不是在下面那个配置块里】
// io.izzel.taboolib 的插件在自己的 afterEvaluate 钩子里会【按扩展配置重新赋值】这两个属性
// （实测：在脚本主体里设的 classifier 被它重置回 null，于是落点退回 inJar 本身、就地重写，
// 我们声明的输出文件永远不会出现）。本脚本注册的 afterEvaluate 晚于插件的，因此这一次赋值
// 才是最终生效的那一次 —— 与上面 `jar` finalizer 的处理是同一个原因，见那段注释。
afterEvaluate {
    tasks.named<io.izzel.taboolib.gradle.TabooLibMainTask>("taboolibMainTask") {
        // 输入是【中间件】（未重定位的 shadow 归档）；classifier 让落点变成同目录下的另一个文件，
        // 因此 inJar 全程只读，不存在「在已重定位产物上二次重定位」。
        inJar = shadedJarFile.get().asFile
        classifier = muzTarget.id
    }
}

tasks.named<io.izzel.taboolib.gradle.TabooLibMainTask>("taboolibMainTask") {
    dependsOn(tasks.named("shadowJar"))
    // 【为什么要显式声明 inputs/outputs】插件本身只给 inJar 标了 @InputFile、不给任何输出，
    // Gradle 便无法判定增量。这里补齐指纹，第二次不 clean 的打包才会 UP-TO-DATE，
    // 也才能证明「没有二次重定位」。输出路径必须与 classifier 的落点一致（见上面的 afterEvaluate）。
    inputs.file(shadedJarFile)
    inputs.files(embeddedLibraries)
    outputs.file(relocatedJarFile)
}

// ============================================================================
// 最终发布归档：把 taboolibMainTask 的重定位结果按固定时间戳与排序重新打包
//
// 【为什么要多这一步】TabooLibMainTask 重写归档时把每个条目的时间设成构建时刻，
// 于是「同一份源码连续两次打包」的 SHA-256 必然不同。它不改动条目内容，只改时间戳，
// 所以这里做一次「读 → 排序 → 固定时间写回」就能拿回可复现性，且仍保留重定位结果。
// 输入是重定位件、输出是最终归档，两者都不是彼此的输入，增量判定天然成立。
// ============================================================================
val packagePluginJar = tasks.register("packagePluginJar") {
    dependsOn(tasks.named("taboolibMainTask"))
    group = "build"
    description = "把重定位后的 JAR 以固定时间戳与排序重打包成最终发布归档"
    val source = relocatedJarFile
    val target = finalJarFile
    inputs.file(source)
    outputs.file(target)

    doLast {
        val sourceFile = source.get().asFile
        check(sourceFile.isFile) { "缺少重定位后的 JAR：$sourceFile" }
        val targetFile = target.get().asFile
        repackZipDeterministically(sourceFile, targetFile)
        logger.lifecycle(
            "[muz] 已生成发布归档：${targetFile.absolutePath}（${targetFile.length()} 字节，" +
                "条目已按名字排序并统一为固定时间戳）"
        )
    }
}

val verifyRelocatedSnakeYaml = tasks.register("verifyRelocatedSnakeYaml") {
    // 【1.10.54 起本任务的语义反转为「外置边界校验」，任务名保留是历史沿用】
    // 1.10.53 及以前它校验的是「该重定位的只剩新包名、该内嵌的原包名还在」。本版本把
    // gson / sqlite-jdbc / snakeyaml / TabooLib common-reflex 及其 Kotlin / ASM / commons-lang3
    // 全部移出 JAR、改由 MuzPluginLoader 运行期下载，于是断言方向整体反转。现在校验四件事：
    //   1. 插件加载器已声明：paper-plugin.yml（JAR 内展开后的那份）里 loader 指向
    //      MuzPluginLoader，且 JAR 里真有该类，类名两处一致；
    //   2. 全部外置依赖【一个包都不在】这个 JAR 里 —— 既没有原始包名，也没有旧的 relocate
    //      目标包 linmumua.doudizhu.libs.**；
    //   3. 业务类的字节码引用的是【原始包名】（否则下载下来的库根本对不上）；
    //   4. taboolibMainTask 真的执行过（env.properties 是它唯一还会写的东西）。
    // 【为什么不改名】任务名已被发布流程（build 的 dependsOn）与 AGENTS.md / README.md 的多轮
    // 历史记录引用，改名会波及这些调用方；按项目惯例保留名字，并在这里把语义写清楚。
    // 【为什么必须先跑完 packagePluginJar】顺序颠倒会校验到「重打包前」的中间件，看不到真产物。
    dependsOn(packagePluginJar)
    inputs.file(finalJarFile)

    doLast {
        val jarFile = finalJarFile.get().asFile

        val loaderClassName = "linmumua.doudizhu.MuzPluginLoader"
        val loaderClassEntry = "linmumua/doudizhu/MuzPluginLoader.class"
        // taboolibMainTask 只由它写入；本 JAR 已无 kotlin/asm/commons-lang3 可重定位，
        // 所以它剩下的唯一指纹就是这个文件。
        val taboolibEnvProperties = "META-INF/taboolib/env.properties"
        // 外置依赖的全部包前缀。前 12 条是【原始包名】（下载后由 loader 提供），
        // 最后一条是【旧 relocate 目标包】（1.10.53 及以前产物里才有的形态）。
        // 少写一条就等于给「某个库又被悄悄塞回 JAR」留了后门。
        val externalizedPrefixes = listOf(
            "org/yaml/snakeyaml/",
            "com/google/gson/",
            // gson 的传递依赖（POM compile 作用域）
            "com/google/errorprone/",
            "org/sqlite/",
            // sqlite-jdbc 的传递依赖（POM compile 作用域）
            "org/slf4j/",
            "taboolib/library/reflex/",
            "org/objectweb/asm/",
            "org/apache/commons/lang3/",
            "kotlin/",
            // 1.10.53 及以前的产物把 kotlin.* 重定位到这里
            "kotlin2320/",
            "org/jetbrains/annotations/",
            "org/intellij/lang/",
            "linmumua/doudizhu/libs/"
        )

        JarFile(jarFile).use { jar ->
            // ---- 1. loader 声明与类必须同时存在且一致 ----
            check(jar.getEntry(loaderClassEntry) != null) {
                "${jarFile.name} 缺少插件加载器类 $loaderClassEntry —— paper-plugin.yml 声明的 loader 无法被解析"
            }
            val metaEntry = checkNotNull(jar.getEntry("paper-plugin.yml")) {
                "${jarFile.name} 缺少 paper-plugin.yml"
            }
            val meta = jar.getInputStream(metaEntry).use { it.readBytes() }.toString(Charsets.UTF_8)
            val loaderLine = meta.lineSequence()
                .map { it.trim() }
                .firstOrNull { it.startsWith("loader:") }
            checkNotNull(loaderLine) {
                "${jarFile.name} 的 paper-plugin.yml 没有 loader 声明 —— 外置依赖不会被下载，插件会在启动期 NoClassDefFoundError"
            }
            val declaredLoader = loaderLine!!.removePrefix("loader:").trim().trim('"', '\'')
            check(declaredLoader == loaderClassName) {
                "loader 声明与加载器类不一致：paper-plugin.yml 写的是 '$declaredLoader'，期望 '$loaderClassName'"
            }

            // ---- 2. 外置依赖的包一个都不许留在 JAR 里 ----
            val hits = mutableListOf<String>()
            val entries = jar.entries()
            while (entries.hasMoreElements()) {
                val name = entries.nextElement().name
                for (prefix in externalizedPrefixes) {
                    if (name.startsWith(prefix)) {
                        hits.add(name)
                    }
                }
            }
            check(hits.isEmpty()) {
                "${jarFile.name} 里仍含外置依赖的包（应全部由 MuzPluginLoader 下载）：" +
                    hits.take(10).joinToString(", ") + "（共 ${hits.size} 项）"
            }

            // ---- 3. 业务字节码必须引用【原始包名】 ----
            // 这三条与上面的「包不存在」互为反面：只断言包不在，无法发现「relocate 把引用改到
            // 了下载包里不存在的路径」这种更隐蔽的错法（包确实不在 JAR 里，但引用也错了）。
            val configEntry = checkNotNull(jar.getJarEntry("linmumua/doudizhu/config/MuzYamlConfig.class")) {
                "Missing MuzYamlConfig.class in ${jarFile.name}"
            }
            val configBytecode = jar.getInputStream(configEntry).use { it.readBytes() }.toString(Charsets.ISO_8859_1)
            check(configBytecode.contains("org/yaml/snakeyaml/LoaderOptions")) {
                "MuzYamlConfig 未引用原始包名 org/yaml/snakeyaml/LoaderOptions —— 外置下载的 SnakeYAML 对不上"
            }
            check(!configBytecode.contains("linmumua/doudizhu/libs/snakeyaml")) {
                "MuzYamlConfig 仍引用旧的 relocate 目标包 linmumua/doudizhu/libs/snakeyaml"
            }

            val databaseEntry = checkNotNull(jar.getJarEntry("linmumua/doudizhu/storage/DatabaseManager.class")) {
                "Missing DatabaseManager.class in ${jarFile.name}"
            }
            val databaseBytecode = jar.getInputStream(databaseEntry).use { it.readBytes() }
                .toString(Charsets.ISO_8859_1)
            check(databaseBytecode.contains("org.sqlite.JDBC")) {
                "DatabaseManager 未引用原始驱动类名 org.sqlite.JDBC —— sqlite 外置后 JDBC 驱动无法自动注册"
            }

            val webEntry = checkNotNull(jar.getJarEntry("linmumua/doudizhu/debug/DebugWebServer.class")) {
                "Missing DebugWebServer.class in ${jarFile.name}"
            }
            val webBytecode = jar.getInputStream(webEntry).use { it.readBytes() }.toString(Charsets.ISO_8859_1)
            check(webBytecode.contains("com/google/gson/Gson")) {
                "DebugWebServer 未引用原始包名 com/google/gson/Gson —— 外置下载的 gson 对不上"
            }

            val reflexEntry = checkNotNull(
                jar.getJarEntry("linmumua/doudizhu/compat/CraftEngineOffsetService.class")
            ) { "Missing CraftEngineOffsetService.class in ${jarFile.name}" }
            val reflexBytecode = jar.getInputStream(reflexEntry).use { it.readBytes() }.toString(Charsets.ISO_8859_1)
            check(reflexBytecode.contains("taboolib/library/reflex/")) {
                "CraftEngineOffsetService 未引用原始包名 taboolib/library/reflex/ —— 外置下载的 common-reflex 对不上"
            }

            // ---- 4. taboolibMainTask 真的执行过 ----
            check(jar.getEntry(taboolibEnvProperties) != null) {
                "${jarFile.name} 缺少 $taboolibEnvProperties —— taboolibMainTask 未真正执行"
            }
        }

        logger.lifecycle("[muz] 已校验外置依赖边界与 loader 声明：${jarFile.name}")
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
    inputs.property("counterScaleCodepointStarts", counterScaleCodepointStarts.toString())
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

                /** 桌内九格道具栏字体族；与运行期 PackAssets 同源。 */
                public static final String GADGET_BAR_FONT = "${gadgetBarFont}";
                public static final int GADGET_BAR_BASE_CODEPOINT = ${gadgetBarBaseCodepoint};
                public static final int GADGET_BAR_SELECT_CODEPOINT = ${gadgetBarSelectCodepoint};
                public static final int GADGET_BAR_ICON_CODEPOINT_START = ${gadgetBarIconCodepointStart};
                public static final int GADGET_BAR_SLOT_COUNT = ${gadgetBarSlotCount};
                public static final int GADGET_BAR_CELL_WIDTH = ${gadgetBarCellWidth};
                public static final int GADGET_BAR_CELL_HEIGHT = ${gadgetBarCellHeight};
                public static final int GADGET_BAR_CELL_ADVANCE = ${gadgetBarCellAdvance};
                public static final int GADGET_BAR_ICON_WIDTH = ${gadgetBarIconWidth};
                public static final int GADGET_BAR_ICON_HEIGHT = ${gadgetBarIconHeight};
                /**
                 * 九格栏并入出牌 HUD 后的固定下移像素；provider 的 ascent = 格高 - 本值。
                 * 与 build.gradle.kts 的 gadgetBarRowDownOffset 一一对应。
                 */
                public static final int GADGET_BAR_ROW_DOWN_OFFSET = ${gadgetBarRowDownOffset};
                public static final int GADGET_BAR_ICON_ADVANCE = ${gadgetBarIconWidth + 1};
                public static final int GADGET_BAR_ICON_KIND_COUNT = ${gadgetBarIconKinds.size};

                /** Hotbar 兼容缩放档；仅保留旧 API 所需常量，构建不再发布 Hotbar provider。 */
                public static final int[] HOTBAR_SCALE_TIERS = {100};

                /** counter 各缩放档的每档起始码位；100 档必须继续为 0xE900。 */
                public static final int[] COUNTER_SCALE_CODEPOINT_STARTS = {
                    ${javaArray(counterScaleCodepointStarts)}
                };

                /** Hotbar 旧码位窗口仅为兼容既有 Java API 保留；构建不再声明这些 provider。 */
                public static final int[] HOTBAR_SCALE_BASE_CODEPOINTS = {61188};
                public static final int[] HOTBAR_SCALE_DEBUG_CODEPOINTS = {61191};
                public static final int[] HOTBAR_SCALE_SELECT_CODEPOINTS = {61186};
                public static final int[] HOTBAR_SCALE_SELECT_DEBUG_CODEPOINTS = {61187};

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

                /** Hotbar 旧几何常量仅供兼容 Java API；资源生成链不再读取或发布。 */
                public static final int HOTBAR_ICON_COUNT = 3;
                public static final int HOTBAR_ICON_WIDTH = 20;
                public static final int HOTBAR_ICON_HEIGHT = 22;
                public static final int HOTBAR_ICON_STEP = 24;
                public static final int HOTBAR_ICON_ADVANCE = 21;
                public static final int HOTBAR_GLYPH_WIDTH = 68;
                public static final int HOTBAR_GLYPH_HEIGHT = 22;
                public static final int HOTBAR_GLYPH_ADVANCE = 69;
                public static final int HOTBAR_SELECT_WIDTH = 20;
                public static final int HOTBAR_SELECT_HEIGHT = 22;
                public static final int HOTBAR_SELECT_ADVANCE = 21;
                public static final int HOTBAR_SLOT_COUNT = 3;
                public static final int HOTBAR_BASE_ASCENT = -43;

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
            "[muz] PackTiers profile=%s(v%d)：牌高=%s、牌偏移=%s、头像 scale=%s、头像偏移=%s、记牌 scale=%s、记牌偏移=%s".format(
                resourceProfileFile.name, resourceProfile.version,
                cardGlyphHeightTiers.joinToString("/"), cardGlyphDownOffsetTiers.joinToString("/"),
                avatarPixelScaleTiers.joinToString("/"), avatarDownOffsetTiers.joinToString("/"),
                counterScaleTiers.joinToString("/"), counterDownOffsetTiers.joinToString("/")
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



        // 桌内九格道具栏使用独立 MUZ 字形族；底图、图标和选框分层生成。
        // 运行期通过 CraftEngine 偏移字形叠加，净前进量固定为 22px，不拼接文字，也不依赖原版 hotbar sprite。
        val gadgetBarTextureDir = outputAssetsRoot.resolve("textures/font/gadget_bar")
        writeGadgetBarGlyph(gadgetBarTextureDir.resolve("base.png"), renderGadgetBarBase(false))
        writeGadgetBarGlyph(gadgetBarTextureDir.resolve("select.png"), renderGadgetBarSelect())
        gadgetBarIconKinds.forEach { kind ->
            writeGadgetBarGlyph(gadgetBarTextureDir.resolve("$kind.png"), renderGadgetBarIcon(kind))
        }

        // 桌内道具模型全部使用独立 item 纹理，不再复用已退役的 Hotbar 字形贴图。
        val tomatoTexture = "$resourceNamespace:item/$tableGadgetTomatoId"
        val waterSheetTexture = "$resourceNamespace:item/$tableGadgetWaterSheetId"
        val speechBubbleTexture = "$resourceNamespace:item/$tableGadgetSpeechBubbleId"
        writeTomatoGadgetTexture(outputAssetsRoot.resolve("textures/item/$tableGadgetTomatoId.png"))
        writeWaterSheetTexture(outputAssetsRoot.resolve("textures/item/$tableGadgetWaterSheetId.png"))
        writeSpeechBubbleTexture(outputAssetsRoot.resolve("textures/item/$tableGadgetSpeechBubbleId.png"))
        writeTomatoGadgetModel(modelGadgetDir.resolve("$tableGadgetTomatoId.json"), tomatoTexture)
        writeWaterSheetModel(modelGadgetDir.resolve("$tableGadgetWaterSheetId.json"), waterSheetTexture)
        writeFlatItemModel(modelGadgetDir.resolve("$tableGadgetSpeechBubbleId.json"), speechBubbleTexture)
        writeItemDefinition(itemGadgetDir.resolve("$tableGadgetTomatoId.json"), "$resourceNamespace:item/$tableGadgetTomatoId")
        writeItemDefinition(itemGadgetDir.resolve("$tableGadgetWaterSheetId.json"), "$resourceNamespace:item/$tableGadgetWaterSheetId")
        writeItemDefinition(itemGadgetDir.resolve("$tableGadgetSpeechBubbleId.json"), "$resourceNamespace:item/$tableGadgetSpeechBubbleId")

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

        val gadgetBarImages = buildString {
            appendLine("images:")
            val layers = listOf(
                "base" to gadgetBarBaseCodepoint,
                "select" to gadgetBarSelectCodepoint
            ) + gadgetBarIconKinds.mapIndexed { index, kind -> kind to gadgetBarIconCodepointStart + index }
            layers.forEach { (name, codepoint) ->
                checkGlyphCodepoint(codepoint, "gadget-bar", codepoint)
                appendLine("  $resourceNamespace:gadget_bar_$name:")
                appendLine("    height: $gadgetBarCellHeight")
                // 并入 BossBar 第四行后必须整体下移到记牌器行下方：ascent 从 cellHeight 减固定下移档。
                // 与 PackAssets.GADGET_BAR_ROW_DOWN_OFFSET / PackTiers.GADGET_BAR_ROW_DOWN_OFFSET 同源。
                appendLine("    ascent: ${gadgetBarCellHeight - gadgetBarRowDownOffset}")
                appendLine("    font: $gadgetBarFont")
                appendLine("    file: $resourceNamespace:font/gadget_bar/$name.png")
                appendLine("    char: \\u${codepoint.toString(16).padStart(4, '0')}")
            }
        }

        // 旧 Hotbar 三图标与选框字形仍不生成；九格道具栏使用独立 gadget_bar 字体族。
        // 保留 PackTiers/PackAssets 的旧常量仅供并行代码编译迁移，不能据此生成客户端资源。

        // 每份都要自带 `images:` 根键——它们是独立文档，不是被拼起来的片段。

        val imageParts = linkedMapOf(
            "bot_avatar" to botAvatarBaseImages,
            "avatar_crown" to "images:\n" + avatarCrownImages,
            "gadget_bar" to gadgetBarImages
        ).apply {
            putAll(counterImagesByScale)
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
        val totalImageBytes = imageParts.keys.sumOf { imagesDir.resolve("$it.yml").length() }
        logger.lifecycle(
            ("[muz] configuration/images/ 共 %,d 条"
                + "（牌 %,d + 头像 %,d + 王冠 %,d + bot %,d + 记牌 %d），%.1f MiB / %d 份").format(
                cardEntryCount + avatarEntryCount + crownEntryCount + botEntryCount + counterEntryCount,
                cardEntryCount, avatarEntryCount, crownEntryCount, botEntryCount, counterEntryCount,
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
        filteringCharset = Charsets.UTF_8.name()
        from(generatedJarResourcesDir)
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

// ============================================================================
// 独立 JUnit runner（build/junit-runner/run.py）的依赖 classpath 导出
//
// 【为什么需要这个任务】run.py 读取 build/test-runtime-cp.txt 构造 classpath（该文件不参与
// 版本控制，此前是手工生成的）。新增 TabooLib 反射工具后，测试 classpath 必须包含
// common-reflex 与它的 Kotlin/ASM/commons-lang3 依赖，否则反射驱动的测试会 NoClassDefFoundError。
// 这个任务把 Gradle 解析出的真实 testRuntimeClasspath 写成 run.py 期望的格式（分号分隔、
// 单行），保证两边不会再漂移。
//
// 【为什么必须挂上任务链】此前这个任务没有任何人依赖它，必须手工调用，于是
// build/test-runtime-cp.txt 很容易停留在上一次编译的解析结果（陈旧）。现在由 compileTestJava
// finalizedBy 它：只要重新编译测试，CP 文件就跟着刷新。用 finalizedBy 而不是 dependsOn，
// 是因为它反过来依赖 testClasses（dependsOn(testClasses)），写成 dependsOn 会成环。
// ============================================================================
val writeTestRuntimeClasspath = tasks.register("writeTestRuntimeClasspath") {
    dependsOn(tasks.named("testClasses"))
    // run.py 读的是【仓库根的 build/test-runtime-cp.txt】，而 layout.buildDirectory 已被
    // 重定向到 build/<targetId>，所以这里必须用 rootProject 的绝对路径，不能走 buildDirectory。
    val outputFile = rootProject.layout.projectDirectory.file("build/test-runtime-cp.txt")
    val runtimeClasspath = configurations.named("testRuntimeClasspath")
    outputs.file(outputFile)
    doLast {
        val target = outputFile.asFile
        target.parentFile.mkdirs()
        // run.py 只接受「单行、分号分隔」的 jar 列表；过滤掉目录与构建输出目录，
        // 那些由 run.py 自己按目标拼到 classpath 最前面。
        // 【为什么要剔除 kotlin-stdlib-jdk7/jdk8】paper-api 会传递解析出 1.8.20 的
        // kotlin-stdlib-jdk7 / jdk8，与本项目显式声明的 kotlin-stdlib:2.3.20（TabooLib 反射工具
        // 的实际运行期依赖）同时出现在测试 classpath 上，而且旧版排在前面。它们与 2.3.20 的
        // kotlin-stdlib 是同一套类的历史分包，混用会让测试加载到与运行期不同的 stdlib，
        // 于是「测试通过」不再能代表产物行为。【1.10.54 更新】：运行期加载的 stdlib 现在由
        // MuzPluginLoader 下载 kotlin-stdlib:2.3.20（不再重定位成 kotlin2320/），
        // 排除旧分包的结论不变 —— 测试 classpath 也必须只有 2.3.20 那一份。
        val excludedKotlinSplits = listOf("kotlin-stdlib-jdk7", "kotlin-stdlib-jdk8")
        val jars = runtimeClasspath.get().files
            .filter { it.isFile && it.name.endsWith(".jar") }
            .filter { file -> excludedKotlinSplits.none { file.name.startsWith("$it-") } }
            .map { it.absolutePath }
            .distinct()
            .sorted()
        target.writeText(jars.joinToString(File.pathSeparator), Charsets.UTF_8)
        logger.lifecycle("[muz] 已写出测试依赖 classpath：${target.absolutePath}（${jars.size} 个 jar）")
    }
}

// 测试编译一结束就刷新 CP 文件（见上面的理由：finalizedBy 避免与 testClasses 成环）。
tasks.named("compileTestJava") {
    finalizedBy(writeTestRuntimeClasspath)
}

// ============================================================================
// 外置依赖探针（muzExternalDependencyProbe）
//
// 【这个任务证明什么】1.10.54 把 gson / sqlite-jdbc / snakeyaml / TabooLib common-reflex 及其
// Kotlin、ASM、commons-lang3 全部改为运行期下载。构建门禁只能证明「JAR 里没有它们」，
// 证明不了「下载得到的确实能用」。这个任务跑一个真实探针：
//   1. 调【生产加载器】MuzPluginLoader，反射出它真正登记的坐标与仓库（不是照抄一份）；
//   2. 用与 Paper MavenLibraryResolver 相同的 Aether 会话（checksumPolicy=fail、
//      LocalRepository、BasicRepositoryConnectorFactory + HttpTransporterFactory）在线解析，
//      JAR 落进 build/<targetId>/tmp/external-probe/repo（Paper 用的是服务端根的 libraries/）；
//   3. 用【同一缓存目录 + setOffline(true)】再解析一次 —— 成功即证明缓存齐全、断网可启动；
//   4. 用【父加载器 = 平台类加载器】的 URLClassLoader 加载下载来的 JAR，实际调用
//      SnakeYAML（解析 YAML）、Gson（序列化）、sqlite-jdbc（建表插查）、TabooLib reflex
//      （分析类 + 按名字取方法 + 调用），并确认 ASM / commons-lang3 / kotlin-stdlib 可加载。
// 【为什么父加载器必须是平台类加载器】否则反射会命中测试 classpath 上那份库，探针就变成
// 「证明了无关的东西」。
// 【为什么不挂到 build 上】它需要联网，且只在真实验证时才有意义。默认不执行，
// 由人工显式调用（见 AGENTS.md / README.md 的记录）。
// 【不证明什么】它不是 Paper，也不启动服务端；真实服务端首次启动、插件加载顺序、
// 离线首启的失败表现仍必须由实服验证。
// ============================================================================
val muzExternalDependencyProbe = tasks.register<JavaExec>("muzExternalDependencyProbe") {
    group = "verification"
    description = "解析并缓存外置依赖，再用隔离类加载器实际加载 SnakeYAML/Gson/sqlite/reflex 做探针"
    dependsOn(tasks.named("writeTestRuntimeClasspath"))
    classpath = sourceSets["test"].runtimeClasspath
    mainClass.set("linmumua.doudizhu.loader.MuzExternalDependencyProbe")
    // 【缓存目录放在哪 / 为什么还要设 workingDir】Paper 的 MavenLibraryResolver 用
    // `new LocalRepository("libraries")` —— 相对路径，落点由进程工作目录决定，真实服务器上
    // 就是 `<服务端根>/libraries/`。这里把 workingDir 设成同一个构建输出目录，
    // 探针里的 Paper 解析器就会把缓存写进 build/<targetId>/tmp/external-probe/repo/libraries/，
    // 既不碰仓库其它位置，也不碰服务端目录；离线阶段也指向同一个 libraries/，两边看同一份缓存。
    val probeCacheRoot = layout.buildDirectory.dir("tmp/external-probe/repo").get().asFile
    workingDir = probeCacheRoot
    args(probeCacheRoot.absolutePath)
    doFirst {
        // workingDir 必须存在，否则 JVM 起不来。只建这个任务自己的目录。
        probeCacheRoot.mkdirs()
    }
}
