import com.github.jengelman.gradle.plugins.shadow.tasks.ShadowJar
import org.gradle.api.tasks.bundling.Zip
import java.awt.BasicStroke
import java.awt.Color
import java.awt.RenderingHints
import java.awt.geom.Ellipse2D
import java.awt.geom.GeneralPath
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
version = "1.10.11"

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

// 牌行向下偏移。牌行贴屏幕顶部，80 已经能把它压到屏幕中上部。
val cardOffsetMin = tierParam("muzCardOffsetMin", 0)
val cardOffsetMax = tierParam("muzCardOffsetMax", 80)
val cardOffsetStep = tierParam("muzCardOffsetStep", 2)

// 头像行向下偏移。必须比牌行深一整个头像盒（12 * avatar-scale，最大 192），
// 所以范围天然要大得多：最坏组合 80 + 192 = 272，400 留足余量。
val avatarOffsetMin = tierParam("muzAvatarOffsetMin", 0)
val avatarOffsetMax = tierParam("muzAvatarOffsetMax", 400)
val avatarOffsetStep = tierParam("muzAvatarOffsetStep", 2)

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

// 降序：与原表 {53, 48, 42, 37, 32} 方向一致，减少 diff 噪音。
// 【索引 0 不再承载「默认」语义】—— card-height 的默认值已改为显式常量
// PackAssets.DEFAULT_CARD_HEIGHT，不再取 cardGlyphHeightAt(0)，所以顺序纯粹是数据。
val cardGlyphHeightTiers = tiersOf(cardHeightMin, cardHeightMax, cardHeightStep).reversed()








val cardGlyphDownOffsetTiers = tiersOf(cardOffsetMin, cardOffsetMax, cardOffsetStep)

// 头像行（含跟着头像走的 bot 兜底图标）自己的向下偏移档，与上面牌那张表完全独立。
// 拆两张表是因为头像行永远比牌行深一整个头像盒（12 * avatar-scale，含王冠那 2 行），
// 牌行区间 0..80、头像行要到 400；共用一张表时每一档都要无差别生成三族字形，
// 牌用不到深档，约一半条目是废的。
//
// 这张表连同下面的 scale 范围会被写进生成的 PackTiers.java（见 generatePackTiers 任务），
// 插件端直接读那份生成结果 —— 不再有「两处手写的表必须逐项一致」这种隐患。
val avatarDownOffsetTiers = tiersOf(avatarOffsetMin, avatarOffsetMax, avatarOffsetStep)















val avatarPixelCodepointStart = 0xE800
val avatarPixelMinScale = tierParam("muzAvatarScaleMin", 2)
val avatarPixelMaxScale = tierParam("muzAvatarScaleMax", 16)
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

/** 点数 id 转文件名片段：10 拼成 ten，字母一律小写（避免大小写不敏感文件系统上撞名）。 */
fun counterGlyphSlug(id: String): String = if (id == "10") "ten" else id.lowercase()

// ============================================================================
// 记牌行字形族
//
// 【单一尺寸，只按向下偏移分档】：牌面族要按高度分档是因为服主能调 card-height；
// 记牌行没有这个配置项，48px 是唯一渲染高度。少一个维度让条目数从「50 × H × M」
// 降到「50 × M」，也省掉一整套缩放贴图。
//
// 档位表【共用 avatarDownOffsetTiers】：记牌行与头像行同频下沉（两行一起被
// offset-down 推走），共用一张表就不必再生成一套只有它自己用的偏移档。
// ============================================================================
val counterGlyphFont = "minecraft:${resourceNamespace}_counter"

// 0xEF00：hotbar HUD 底部物品栏字形（单字形，无档位切分）。
// crown 占 0xE000、牌占 0xE100、头像占 0xE800、记牌器占 0xE900、bot 占 0xF910，
// 0xEF00 处于空隙，不与任何已知字形冲突。
// 【必须与 PackAssets.HOTBAR_HUD_FONT / HOTBAR_HUD_CODEPOINT 保持一致】
val hotbarHudFont = "minecraft:${resourceNamespace}_hotbar"
val hotbarHudCodepoint = 0xEF00
val hotbarHudCharEscape = "\\uef00"
// 原版 9 槽物品栏背景是 182×22；PLAYING 阶段用同尺寸的不透明字形完整盖住它，
// 再在中央绘制 5 个调试槽。宽、高、槽数与前进量必须同步 PackAssets 的同名字义常量。
val hotbarHudGlyphWidth = 182
val hotbarHudGlyphHeight = 22
val hotbarHudSlotCount = 5
val hotbarHudGlyphAdvance = hotbarHudGlyphWidth + 1

// 0xE900：crown 占 0xE000、牌占 0xE100、头像占 0xE800、bot 占 0xF910，这一段空闲。
// 201 档 × 50 码位切 2 张字体，末码位落在 0xF967，离 BMP 上界还远。
// 与 bot 的 0xF910 数值上有重叠也不冲突 —— 各族是独立字体，码位空间互不相干。
val counterGlyphCodepointStart = 0xE900

// 点数字形的文件名，顺序【就是 CardRank 枚举序】（3..2、小王、大王）。
// 插件侧 PackAssets.counterRankChar 直接拿 rank.ordinal() 当下标，所以这个顺序
// 不是排版偏好而是接口契约 —— 动一项就会让整族点数图标错位。
val counterRankGlyphFiles = listOf(
    "rank_3", "rank_4", "rank_5", "rank_6", "rank_7", "rank_8", "rank_9", "rank_ten",
    "rank_j", "rank_q", "rank_k", "rank_a", "rank_2", "rank_small_joker", "rank_big_joker"
)

/**
 * 一档内 50 个字形的排列顺序，也就是码位顺序。
 *
 * 布局：`0..14` 点数亮、`15..29` 点数暗、`30..39` 数字亮、`40..49` 数字暗。
 * 亮暗成段而不是交错，是为了让插件侧的下标算式退化成一次加法
 * （`ordinal + (dim ? 15 : 0)`），不必查表。
 */
val counterGlyphFiles: List<String> =
    counterRankGlyphFiles +
        counterRankGlyphFiles.map { "${it}_dim" } +
        (0..9).map { "digit_$it" } +
        (0..9).map { "digit_${it}_dim" }

fun writeRankGlyph(target: File, id: String) {
    target.parentFile.mkdirs()
    ImageIO.write(renderRankGlyph(id), "png", target)
}

/**
 * 置灰变体：保留 alpha，把 RGB 压成暗灰。
 *
 * 用于「这个点数已经出完」的状态——格子照样占宽，只是画成暗的。
 */
fun writeDimmedGlyph(source: BufferedImage, target: File) {
    val out = BufferedImage(source.width, source.height, BufferedImage.TYPE_INT_ARGB)
    for (y in 0 until source.height) {
        for (x in 0 until source.width) {
            val argb = source.getRGB(x, y)
            val alpha = argb ushr 24
            if (alpha == 0) {
                continue
            }
            // 按比例压暗而不是涂成固定灰，否则大王的红会和小王的白压成同一个颜色，
            // 记牌行里两个「王」就完全分不出大小了。
            val r = ((argb shr 16 and 0xFF) * 0.35f).toInt()
            val g = ((argb shr 8 and 0xFF) * 0.35f).toInt()
            val b = ((argb and 0xFF) * 0.35f).toInt()
            out.setRGB(x, y, (alpha shl 24) or (r shl 16) or (g shl 8) or b)
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

/**
 * 生成 PLAYING 阶段底部物品栏 HUD 遮罩：完整盖住原版 9 槽背景，中央绘制 5 个调试槽。
 *
 * <p>总尺寸固定为 182×22，等宽覆盖原版 hotbar；不生成或覆盖
 * {@code minecraft:textures/gui/sprites/hud/hotbar.png} / {@code hotbar_selection.png}。
 * 非槽区域先填充不透明深色，中央槽组宽 108px：5 槽 × 20px + 4 间距 × 2px，
 * 左右各留 37px 遮罩。每槽是 18px 内框 + 四周 1px 边框，垂直居中于 22px 高贴图。
 *
 * <p>贴图宽高来自 {@code hotbarHudGlyphWidth}/{@code hotbarHudGlyphHeight}；位图字形
 * 前进量是宽度加 1，即 {@code hotbarHudGlyphAdvance}。这些值必须与 PackAssets 同步。
 *
 * @param target 输出路径（muz:font/hotbar_slots.png）
 */
fun writeHotbarSlotsGlyph(target: File) {
    val slotInnerSize = 18
    val borderWidth = 1
    val slotTotalSize = slotInnerSize + borderWidth * 2  // 20px
    val gapWidth = 2
    val slotsWidth = hotbarHudSlotCount * slotTotalSize + (hotbarHudSlotCount - 1) * gapWidth  // 108px
    val slotsStartX = (hotbarHudGlyphWidth - slotsWidth) / 2  // 37px
    val slotsStartY = (hotbarHudGlyphHeight - slotTotalSize) / 2  // 1px
    check(slotsStartX * 2 + slotsWidth == hotbarHudGlyphWidth) {
        "hotbar HUD 槽组必须在 ${hotbarHudGlyphWidth}px 遮罩内水平居中"
    }
    val out = BufferedImage(hotbarHudGlyphWidth, hotbarHudGlyphHeight, BufferedImage.TYPE_INT_ARGB)
    check(out.width + 1 == hotbarHudGlyphAdvance) {
        "hotbar HUD 字形前进量必须等于贴图宽度加 1"
    }

    // ActionBar 字形只在 PLAYING 阶段推送，因此可以用不透明深色遮罩覆盖原版 9 槽；
    // 离开 PLAYING 后清空 ActionBar，原版物品栏自然恢复，不需要改 minecraft 原版 sprite。
    val maskColor = 0xFF_12_12_16.toInt()
    for (y in 0 until hotbarHudGlyphHeight) {
        for (x in 0 until hotbarHudGlyphWidth) {
            out.setRGB(x, y, maskColor)
        }
    }

    // 【调试配色】中央 5 槽保持红、橙、黄、绿、蓝，全部不透明，便于确认槽位与偏移。
    val slotColors = intArrayOf(
        0xFF_E0_3A_3A.toInt(),  // 槽 0：红
        0xFF_E0_8A_2A.toInt(),  // 槽 1：橙
        0xFF_D8_D0_30.toInt(),  // 槽 2：黄
        0xFF_3C_C0_50.toInt(),  // 槽 3：绿
        0xFF_38_88_E0.toInt()   // 槽 4：蓝
    )
    check(slotColors.size == hotbarHudSlotCount) {
        "hotbar HUD 调试配色数量必须等于槽数 $hotbarHudSlotCount"
    }
    val borderColor = 0xFF_F0_F0_F0.toInt()

    for (slotIndex in 0 until hotbarHudSlotCount) {
        val slotX = slotsStartX + slotIndex * (slotTotalSize + gapWidth)
        val slotBg = slotColors[slotIndex]
        for (y in slotsStartY until slotsStartY + slotTotalSize) {
            for (x in slotX until slotX + slotTotalSize) {
                val isTopBorder = y < slotsStartY + borderWidth
                val isBottomBorder = y >= slotsStartY + slotTotalSize - borderWidth
                val isLeftBorder = x < slotX + borderWidth
                val isRightBorder = x >= slotX + slotTotalSize - borderWidth
                out.setRGB(x, y, if (isTopBorder || isBottomBorder || isLeftBorder || isRightBorder) borderColor else slotBg)
            }
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
    // 这些参数进 inputs，改了 -P 参数就会重新生成（否则 Gradle 会误判 UP-TO-DATE）。
    inputs.property("cardHeightTiers", cardGlyphHeightTiers.toString())
    inputs.property("cardOffsetTiers", cardGlyphDownOffsetTiers.toString())
    inputs.property("avatarOffsetTiers", avatarDownOffsetTiers.toString())
    inputs.property("avatarScaleRange", "$avatarPixelMinScale..$avatarPixelMaxScale")
    inputs.property("counterGlyphFiles", counterGlyphFiles.toString())
    inputs.property("counterGlyphHeight", rankGlyphHeight)
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

                /** 牌行向下偏移档位（升序，首项必须是 0）。 */
                public static final int[] CARD_DOWN_OFFSET_TIERS = {
                    ${javaArray(cardGlyphDownOffsetTiers)}
                };

                /** 头像行向下偏移档位（升序，首项必须是 0）。 */
                public static final int[] AVATAR_DOWN_OFFSET_TIERS = {
                    ${javaArray(avatarDownOffsetTiers)}
                };

                /**
                 * 记牌行字形一档占几个码位 —— 也就是资源包里那 50 张 PNG 的张数。
                 *
                 * <p>{@link PackAssets} 的码位算式用它当乘数（一档一个连续窗口），
                 * 构建期用同一个数写 {@code images.yml}。这个值是【字形清单的长度】，
                 * 不是拍出来的：增删任何一个记牌字形都会自动跟着变。
                 */
                public static final int COUNTER_GLYPHS_PER_TIER = ${counterGlyphFiles.size};

                /**
                 * 记牌行点数字形的个数（含双王），也就是亮版点数在一档内的下标区间上界。
                 *
                 * <p>下标布局：{@code [0, RANK)} 亮点数、{@code [RANK, 2*RANK)} 暗点数、
                 * 其后 10 个亮数字、最后 10 个暗数字。点数部分按 {@code CardRank} 的
                 * 声明顺序排，所以 {@code rank.ordinal()} 直接就是亮版下标。
                 */
                public static final int COUNTER_RANK_GLYPHS = ${counterRankGlyphFiles.size};

                /**
                 * 记牌行数字字形的个数（0~9 共十个），也就是亮版数字在一档内的下标区间上界减起点。
                 *
                 * <p>下标布局：亮数字从 {@code 2 * RANK} 开始，占 {@code DIGIT} 个；
                 * 暗数字接在其后再占 {@code DIGIT} 个。
                 */
                public static final int COUNTER_DIGIT_GLYPHS = 10;

                /** 记牌行字形的渲染高度，单位像素。构建期按这个高度画 PNG，不缩放。 */
                public static final int COUNTER_GLYPH_HEIGHT = $rankGlyphHeight;

                /** 记牌行单宽字形（3..9、J、Q、K、A、2、数字）的渲染宽度，单位像素。 */
                public static final int COUNTER_GLYPH_WIDTH = ${rankGlyphWidth("3")};

                /** 记牌行双宽字形（10、双王）的渲染宽度，单位像素。 */
                public static final int COUNTER_GLYPH_WIDE_WIDTH = ${rankGlyphWidth("10")};

                /** 头像放大倍数下限（资源包只生成了这个范围内的 PNG）。 */
                public static final int AVATAR_MIN_SCALE = $avatarPixelMinScale;

                /** 头像放大倍数上限。 */
                public static final int AVATAR_MAX_SCALE = $avatarPixelMaxScale;

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
            "[muz] PackTiers：牌高 %d 档、牌偏移 %d 档、头像偏移 %d 档、scale %d..%d".format(
                cardGlyphHeightTiers.size, cardGlyphDownOffsetTiers.size,
                avatarDownOffsetTiers.size, avatarPixelMinScale, avatarPixelMaxScale
            )
        )
    }
}

sourceSets.named("main") {
    java.srcDir(generatePackTiers)
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

        // 记牌行字形：13 个点数 + 双王 + 各自的置灰变体 + 0-9 数字。
        // 亮/暗成对生成，暗的那份直接从亮的那份压 RGB，保证轮廓逐像素一致。
        val counterFontDir = outputAssetsRoot.resolve("textures/font/counter")
        val rankGlyphIds = listOf("3", "4", "5", "6", "7", "8", "9", "10", "J", "Q", "K", "A", "2")
        for (id in rankGlyphIds) {
            val slug = counterGlyphSlug(id)
            val glyph = renderRankGlyph(id)
            counterFontDir.resolve("rank_$slug.png").parentFile.mkdirs()
            ImageIO.write(glyph, "png", counterFontDir.resolve("rank_$slug.png"))
            writeDimmedGlyph(glyph, counterFontDir.resolve("rank_${slug}_dim.png"))
        }
        for (digit in 0..9) {
            val glyph = renderRankGlyph(digit.toString())
            counterFontDir.resolve("digit_$digit.png").parentFile.mkdirs()
            ImageIO.write(glyph, "png", counterFontDir.resolve("digit_$digit.png"))
            writeDimmedGlyph(glyph, counterFontDir.resolve("digit_${digit}_dim.png"))
        }
        // 大王红、小王白，沿用扑克惯例——两个字形形状一样，只能靠颜色区分。
        for ((joker, color) in listOf("small_joker" to Color.WHITE, "big_joker" to Color(0xE5, 0x3A, 0x3A))) {
            val glyph = renderRankGlyph("王", color)
            ImageIO.write(glyph, "png", counterFontDir.resolve("rank_$joker.png"))
            writeDimmedGlyph(glyph, counterFontDir.resolve("rank_${joker}_dim.png"))
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



        // PLAYING 阶段底部物品栏 HUD 遮罩：182×22 不透明底覆盖原版 9 槽，中央绘制 5 个调试槽。
        // 贴图路径：muz:font/hotbar_slots.png；码位：0xEF00（hotbarHudCodepoint）。
        // 【宽/高/槽数/advance 必须与 PackAssets 的 HOTBAR_HUD_* 常量保持一致】
        val hotbarFontDir = outputAssetsRoot.resolve("textures/font")
        writeHotbarSlotsGlyph(hotbarFontDir.resolve("hotbar_slots.png"))

        // 【不再覆盖原版 hotbar sprite】：原先这里会生成全透明的 hotbar.png 与
        // hotbar_selection.png。资源包贴图是客户端全局状态，无法按「玩家是否正在打牌」切换，
        // 结果是不在牌桌时原版 9 槽背景也永久消失，只剩悬空物品。
        //
        // 自定义 5 槽 HUD 现在只由 HotbarHudService 在 GamePhase.PLAYING 正式出牌阶段通过
        // ActionBar 字形推送；等待、叫地主、加倍、结算及普通游玩时不推送，客户端自然显示原版物品栏。
        // generateResourcePack 每次先 deleteRecursively() 清空 outputRoot（见任务开头），
        // 所以删除这两次生成调用后，旧透明 sprite 不会残留进新构建。

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
        val avatarScaleCount = avatarPixelMaxScale - avatarPixelMinScale + 1
        val avatarGlyphsPerTier = avatarScaleCount * avatarOutlinedPixels
        // 【按 scale 分组产出】：头像像素条目是 201 偏移档 × 15 scale × 10 行 = 30,150 条。
        // 用 scale 而不是偏移档做切分键：偏移档有 201 个，按它切会产生 201 个碎文件；
        // scale 只有 15 个，每份 201 × 10 = 2,010 条、约 0.2 MiB，数量和体积都合适。
        //
        // 注意码位仍由 downTier 决定（tierFontSlot 的入参没变），切分只影响
        // 「这些行写进哪个文件」，不影响任何一个字形的码位与字体归属。
        val avatarPixelImagesByScale = linkedMapOf<Int, String>()
        for (scale in avatarPixelMinScale..avatarPixelMaxScale) {
            avatarPixelImagesByScale[scale] = buildString {
                avatarDownOffsetTiers.forEachIndexed { downTier, downOffset ->
                    val (fontIndex, tierBase) =
                        tierFontSlot(downTier, avatarGlyphsPerTier, avatarPixelCodepointStart)
                    val font = fontNameOf(avatarPixelFont, fontIndex)
                    for (row in 0 until avatarOutlinedPixels) {
                        val index = (scale - avatarPixelMinScale) * avatarOutlinedPixels + row
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
                for (scale in avatarPixelMinScale..avatarPixelMaxScale) {
                    for (row in 0 until avatarCrownPixels) {
                        val index = (scale - avatarPixelMinScale) * avatarCrownPixels + row
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




        // 记牌行字形族。跟【头像表】而不是牌表：记牌行画在头像行下方、与头像同频下沉，
        // 跟错表会让整行记牌器与头像上下错开。
        //
        // 只有偏移一个维度，所以一档就是完整的 50 个字形，档号直接等于偏移档号。
        // 【按亮暗切分文件】而不是按偏移档：偏移档有 201 个、按它切会产生 201 个碎文件；
        // 点数 30 条/档 × 201 档约 1.1 MiB、数字 20 条/档约 0.7 MiB，两份都离上限很远。
        // 码位仍由整族的 index 决定（下面的 forEachIndexed 走的是完整 50 项表），
        // 切分只影响「这一行写进哪个文件」。
        val counterRankImages = StringBuilder()
        val counterDigitImages = StringBuilder()
        avatarDownOffsetTiers.forEachIndexed { downTier, downOffset ->
            val (fontIndex, tierBase) =
                tierFontSlot(downTier, counterGlyphFiles.size, counterGlyphCodepointStart)
            val font = fontNameOf(counterGlyphFont, fontIndex)
            counterGlyphFiles.forEachIndexed { index, file ->
                val codepoint = tierBase + index
                checkGlyphCodepoint(codepoint, "记牌", downTier)
                val charEscape = "\\u%04x".format(codepoint)
                // 数字字形归数字那份，其余（点数与双王）归点数那份。
                val sink = if (file.startsWith("digit_")) counterDigitImages else counterRankImages
                sink.appendLine("  $resourceNamespace:counter_${file}_d$downOffset:")
                sink.appendLine("    height: $rankGlyphHeight")
                sink.appendLine("    ascent: ${rankGlyphHeight - downOffset}")
                sink.appendLine("    font: $font")
                sink.appendLine("    file: $resourceNamespace:font/counter/$file.png")
                sink.appendLine("    char: $charEscape")
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

        // 每份都要自带 `images:` 根键——它们是独立文档，不是被拼起来的片段。
        val imageParts = linkedMapOf(
            "bot_avatar" to botAvatarBaseImages,
            "avatar_crown" to "images:\n" + avatarCrownImages,
            "counter_rank" to "images:\n" + counterRankImages,
            "counter_digit" to "images:\n" + counterDigitImages,
            // CraftEngine 位图条目没有独立 width 字段：横向尺寸取 PNG 原生宽
            // hotbarHudGlyphWidth=182；height 显式写同源常量 22，保证 1:1 不缩放。
            "hotbar_hud" to """
                images:
                  $resourceNamespace:hotbar_slots:
                    height: $hotbarHudGlyphHeight
                    ascent: -128
                    font: $hotbarHudFont
                    file: $resourceNamespace:font/hotbar_slots.png
                    char: $hotbarHudCharEscape
            """.trimIndent()
        )
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
        val counterEntryCount = avatarDownOffsetTiers.size * counterGlyphFiles.size
        val totalImageBytes = imageParts.keys.sumOf { imagesDir.resolve("$it.yml").length() }
        logger.lifecycle(
            ("[muz] configuration/images/ 共 %,d 条"
                + "（牌 %,d + 头像 %,d + 王冠 %,d + bot %,d + 记牌 %,d），%.1f MiB / %d 份").format(
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
        val counterLastSlot = tierFontSlot(
            avatarDownOffsetTiers.size - 1, counterGlyphFiles.size, counterGlyphCodepointStart
        )
        logger.lifecycle(
            "[muz] 字体切分：牌 %d 张、头像 %d 张、王冠 %d 张、记牌 %d 张（码位上界 0x%04X）".format(
                tierFontSlot(
                    cardGlyphHeightTiers.size * cardGlyphDownOffsetTiers.size - 1,
                    cardIds.size, cardGlyphCodepointStart
                ).first + 1,
                tierFontSlot(
                    avatarDownOffsetTiers.size - 1, avatarGlyphsPerTier, avatarPixelCodepointStart
                ).first + 1,
                tierFontSlot(
                    avatarDownOffsetTiers.size - 1, crownGlyphsPerTier, avatarCrownCodepointStart
                ).first + 1,
                counterLastSlot.first + 1,
                maxGlyphCodepoint
            )
        )
        // 记牌族末码位单独打一行：0xE900 段是新占的，和 bot 族的 0xF910 在数值上邻近
        // （不同字体命名空间，本不冲突）。把实际末码位打出来，方便下次加族时挑起点。
        logger.lifecycle(
            "[muz] 记牌族码位：0x%04X..0x%04X（%d 档 × %d 码位/档）".format(
                counterGlyphCodepointStart,
                counterLastSlot.second + counterGlyphFiles.size - 1,
                avatarDownOffsetTiers.size, counterGlyphFiles.size
            )
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
