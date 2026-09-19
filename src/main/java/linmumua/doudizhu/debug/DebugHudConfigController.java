package linmumua.doudizhu.debug;

import com.google.gson.Gson;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParseException;
import linmumua.doudizhu.DoudizhuPlugin;
import linmumua.doudizhu.assets.HudOverlayLayout;
import linmumua.doudizhu.assets.HudResourceRequest;
import linmumua.doudizhu.assets.PackAssets;
import linmumua.doudizhu.assets.PlayerHeadRenderer;
import linmumua.doudizhu.config.MuzYamlConfig;
import linmumua.doudizhu.game.TrickHudPreview;
import linmumua.doudizhu.model.CardRank;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * Debug Web 面板可改的 HUD 配置白名单与保存入口。
 *
 * <p>这个类刻意只暴露 22 个运行期可轻量应用的键：18 个 {@code trick-hud.*}
 * 叶子键，加上 4 个 {@code hotbar-hud.*} 键。Web 请求里的其它键
 * 一律拒绝，避免调试面板变成任意 YAML 编辑器，也避免 HTTP 线程碰到牌桌坐标、经济、存储等
 * 非 HUD 运行态配置。
 */
public final class DebugHudConfigController {
    private static final Gson GSON = new Gson();

    // 记牌器预览与游戏内分层 glyph 的几何全部引用 PackAssets 同源常量。
    // 这些值由服务端统一下发，Debug Web 前端不得自行估算字体宽度。
    private final DoudizhuPlugin plugin;

    public DebugHudConfigController(DoudizhuPlugin plugin) {
        this.plugin = Objects.requireNonNull(plugin, "plugin");
    }

    /**
     * 从主配置读取一份不可变快照。
     *
     * <p>Web 保存/重载与主线程 Web 运行态应用共用插件提供的 HUD 配置锁；这只形成 Web
     * 自身的快照边界，不会替旧的其它配置入口自动加入同一事务。
     */
    public Snapshot snapshot() {
        synchronized (plugin.hudWebConfigLock()) {
            return snapshot(plugin.yamlConfig());
        }
    }

    /** 解析 JSON patch，校验后同步写盘；Debug Web 使用 coordinator 异步调用。 */
    public SaveResult savePatch(String jsonPatch) {
        try {
            Patch patch = parsePatch(jsonPatch);
            validatePatchAgainstCurrentHotbar(patch);
            return savePatch(patch);
        } catch (ValidationException exception) {
            return SaveResult.failed(snapshot(), List.of(exception.getMessage()));
        }
    }

    /**
     * 校验 patch 合并后的 hotbar scale 与 offset-y 是否都有对应资源。
     *
     * <p>scale 虽然是构建期档位，但 offset-y 的 ascent overlay 只为本次应用的当前 scale
     * 生成。不能让 writer 静默钳位，也不能让旧 scale 的 ready 状态跨档复用，否则会发送
     * 客户端没有声明的调试码位或保存后看似成功却显示错误位置。
     */
    void validatePatchAgainstCurrentHotbar(Patch patch) {
        Objects.requireNonNull(patch, "patch");
        synchronized (plugin.hudWebConfigLock()) {
            MuzYamlConfig config = plugin.yamlConfig();
            int configuredScale = config.getInt("hotbar-hud.scale", PackAssets.HOTBAR_DEFAULT_SCALE);
            int configuredOffsetY = config.getInt("hotbar-hud.offset-y", 0);
            validateHotbarPatch(configuredScale, configuredOffsetY, patch);
        }
    }

    /** 纯函数校验 patch 合并后的 hotbar 资源边界，供 Web 入口与契约测试共用。 */
    static void validateHotbarPatch(int configuredScale, int configuredOffsetY, Patch patch) {
        Object patchScale = patch.values().get("hotbar-hud.scale");
        int scale = patchScale == null ? configuredScale : intValue(patchScale);
        if (PackAssets.hotbarScaleTierOf(scale) < 0) {
            throw new ValidationException("hotbar-hud.scale=" + scale
                + " 不是当前资源包已生成的档位，需要重新生成资源包后才能使用。");
        }
        Object patchOffsetY = patch.values().get("hotbar-hud.offset-y");
        int offsetY = patchOffsetY == null ? configuredOffsetY : intValue(patchOffsetY);
        int min = HudOverlayLayout.minHotbarOffsetY(scale);
        int max = HudOverlayLayout.MAX_TRICK_OFFSET;
        if (offsetY < min || offsetY > max) {
            throw new ValidationException("hotbar-hud.offset-y=" + offsetY
                + " 不适用于当前 hotbar scale=" + scale + "%（允许 " + min + ".." + max
                + "），请重新生成该档位资源包后再应用。");
        }
    }

    /**
     * 写入已经校验过的 patch。这个方法只做内存合并与 config.yml I/O，不应用运行态，
     * 由 HudWebApplyCoordinator 在单线程执行器中调用。
     */
    DiskSaveResult savePatchToDisk(Patch patch) {
        synchronized (plugin.hudWebConfigLock()) {
            MuzYamlConfig config = plugin.yamlConfig();
            if (patch.values().isEmpty()) {
                return new DiskSaveResult(true, List.of(), List.of("没有提交任何变更。"), resources(config));
            }
            // 这是 Web 自身的快照边界：先复制当前根，再在同一把锁内合并并原子写盘。
            // 其它旧配置入口尚未统一使用此锁，因此不能宣称它能阻止所有外部配置竞态。
            Map<String, Object> before = deepCopyRoot(config.rawRoot());
            try {
                for (Map.Entry<String, Object> entry : patch.values().entrySet()) {
                    config.set(entry.getKey(), entry.getValue());
                }
                config.saveWithComments(packagedConfigTemplate());
                return new DiskSaveResult(true, new ArrayList<>(patch.values().keySet()), List.of(), resources(config));
            } catch (Exception exception) {
                config.set(null, before);
                return new DiskSaveResult(false, List.of(),
                    List.of("保存 config.yml 失败，已回滚内存配置：" + exception.getMessage()), resources(config));
            }
        }
    }

    /**
     * 只恢复 Web HUD 白名单键，保留其它配置入口在异步资源流程期间写入的值。
     */
    void restoreWebFieldsToDisk(Map<String, Object> previousRoot) throws IOException {
        Objects.requireNonNull(previousRoot, "previousRoot");
        synchronized (plugin.hudWebConfigLock()) {
            restoreWebFieldsInMemory(previousRoot);
            plugin.yamlConfig().saveWithComments(packagedConfigTemplate());
        }
    }

    void restoreWebFieldsInMemory(Map<String, Object> previousRoot) {
        Objects.requireNonNull(previousRoot, "previousRoot");
        synchronized (plugin.hudWebConfigLock()) {
            MuzYamlConfig config = plugin.yamlConfig();
            for (String key : FIELDS.keySet()) {
                config.set(key, deepCopyValue(valueAt(previousRoot, key)));
            }
        }
    }

    @SuppressWarnings("unchecked")
    private static Object valueAt(Map<String, Object> root, String dottedKey) {
        Object current = root;
        for (String part : dottedKey.split("\\.")) {
            if (!(current instanceof Map<?, ?> map) || !map.containsKey(part)) {
                return null;
            }
            current = map.get(part);
        }
        return current;
    }

    private static Object deepCopyValue(Object value) {
        if (value instanceof Map<?, ?> map) {
            return deepCopyRoot((Map<String, Object>) map);
        }
        if (value instanceof List<?> list) {
            return new ArrayList<>(list);
        }
        return value;
    }

    /**
     * Debug Web 专用磁盘重载：文件读取与共享配置替换都在线程池内完成，且与 Web 保存共用同一把锁。
     * 不调用插件完整 reloadVisualState，也不触发牌桌重建；返回值覆盖四层 Y 与 hotbar 尺寸档。
     */
    HudResourceRequest reloadResourcesFromDiskForWeb() {
        synchronized (plugin.hudWebConfigLock()) {
            MuzYamlConfig config = plugin.yamlConfig();
            config.reload();
            return resources(config);
        }
    }

    /** 保留旧调用入口兼容；新 pipeline 使用 reloadResourcesFromDiskForWeb() 的完整快照。 */
    int reloadFromDiskForWeb() {
        return reloadResourcesFromDiskForWeb().hotbarOffsetY();
    }

    /** 保留旧调用入口兼容；调用方必须与 reloadResourcesFromDiskForWeb 同线程串行调用。 */
    int hotbarScaleForWeb() {
        return reloadResourcesFromDiskForWeb().hotbarScale();
    }

    private static HudResourceRequest resources(MuzYamlConfig config) {
        int cardOffset = strictConfiguredInteger(config, "trick-hud.offset-down", 50,
            HudOverlayLayout.MIN_TRICK_OFFSET, HudOverlayLayout.MAX_TRICK_OFFSET);
        int avatarOffset = strictConfiguredInteger(config, "trick-hud.avatar-offset-down", 122,
            HudOverlayLayout.MIN_TRICK_OFFSET, HudOverlayLayout.MAX_TRICK_OFFSET);
        int counterOffset = strictConfiguredInteger(config, "trick-hud.counter.offset-down", 122,
            HudOverlayLayout.MIN_TRICK_OFFSET, HudOverlayLayout.MAX_TRICK_OFFSET);
        int hotbarScale = strictConfiguredInteger(config, "hotbar-hud.scale", PackAssets.HOTBAR_DEFAULT_SCALE,
            Integer.MIN_VALUE, Integer.MAX_VALUE);
        if (PackAssets.hotbarScaleTierOf(hotbarScale) < 0) {
            throw new ValidationException("hotbar-hud.scale=" + hotbarScale
                + " 不是当前资源包已生成的档位，需要重新生成资源包后才能重载。");
        }
        int hotbarOffset = strictConfiguredInteger(config, "hotbar-hud.offset-y", 0,
            HudOverlayLayout.minHotbarOffsetY(hotbarScale), HudOverlayLayout.MAX_TRICK_OFFSET);
        return new HudResourceRequest(cardOffset, avatarOffset, counterOffset, hotbarOffset, hotbarScale);
    }

    private static int strictConfiguredInteger(MuzYamlConfig config, String key, int fallback, int min, int max) {
        Object raw = config.get(key);
        if (raw == null) {
            return fallback;
        }
        if (!(raw instanceof Number number)) {
            throw new ValidationException(key + " 必须是整数 Number。");
        }
        final int value;
        try {
            value = new java.math.BigDecimal(number.toString()).intValueExact();
        } catch (ArithmeticException | NumberFormatException exception) {
            throw new ValidationException(key + " 必须是 32 位整数。");
        }
        if (value < min || value > max) {
            throw new ValidationException(key + " 超出范围（" + min + ".." + max + "）：" + value);
        }
        return value;
    }

    /** 保留旧调用点兼容；运行态应用由 Debug Web coordinator 在主线程完成。 */
    SaveResult savePatch(Patch patch) {
        try {
            validatePatchAgainstCurrentHotbar(patch);
        } catch (ValidationException exception) {
            return SaveResult.failed(snapshot(), List.of(exception.getMessage()));
        }
        DiskSaveResult disk = savePatchToDisk(patch);
        Snapshot next = snapshot();
        return disk.ok()
            ? SaveResult.success(next, disk.appliedKeys(), disk.messages())
            : SaveResult.failed(next, disk.messages());
    }

    private String packagedConfigTemplate() throws IOException {
        try (InputStream stream = plugin.getResource("config.yml")) {
            if (stream == null) {
                return null;
            }
            return new String(stream.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    static Snapshot snapshot(MuzYamlConfig config) {
        LinkedHashMap<String, Object> values = new LinkedHashMap<>();
        List<String> warnings = new ArrayList<>();
        for (FieldSpec spec : FIELDS.values()) {
            try {
                values.put(spec.key(), spec.read(config));
            } catch (ValidationException exception) {
                values.put(spec.key(), spec.fallback());
                warnings.add(exception.getMessage() + "，页面暂按默认值显示。");
            }
        }
        warnings.addAll(overlapWarnings(values));
        return new Snapshot(values, warnings, fieldDtos(), currentGeometry(values), dragSettings(config));
    }

    private static DragSettings dragSettings(MuzYamlConfig config) {
        return new DragSettings(
            config.getBoolean("debug.web-ui.drag.snap-enabled", true),
            Math.max(0, config.getInt("debug.web-ui.drag.snap-threshold", 4)),
            config.getBoolean("debug.web-ui.drag.center-guides-enabled", true),
            config.getBoolean("debug.web-ui.drag.alt-axis-lock", true)
        );
    }

    /**
     * 预览需要的几何常量，全部由服务端集中下发，前端不复算字形表。
     *
     * <p>【为什么必须服务端下发】：预览之所以和游戏内对不上，根因就是它按 CSS 盒模型
     * 自己估了一套宽度，而游戏内是按字形 advance 累加算行宽、再取各行最大值居中。
     * 前端要画准就得知道真实的 advance；一旦在 JS 里手抄一份常量表，就把
     * 「两处手写的表必须逐项一致」这个老问题搬到了前端 —— 资源包档位一改，
     * 预览会静默错位，而且没有任何测试能发现。所以这里统一从服务端生成后下发。
     *
     * <p>screenWidth / screenHeight / bossBarBaselineY / actionBarBottomY 是 Debug Web 的预览
     * 校准值，不是配置键。
     */
    static PreviewGeometry currentGeometry() {
        return currentGeometry(defaultAvatarScale(), true);
    }

    /**
     * 按当前运行期配置生成预览几何。视口仍是页面侧可调的校准值，头像槽位则必须随配置中的
     * 中间头像倍数和描边开关变化；否则页面只改了表单，预览仍会拿旧的三槽宽度。
     */
    static PreviewGeometry currentGeometry(MuzYamlConfig config) {
        int avatarScale = validAvatarScale(config.getInt("trick-hud.avatar-scale", defaultAvatarScale()));
        int counterScale = validCounterScale(config.getInt("trick-hud.counter.scale", PackAssets.COUNTER_DEFAULT_SCALE));
        int counterDownOffset = config.contains("trick-hud.counter.offset-down")
            ? config.getInt("trick-hud.counter.offset-down", 122)
            : config.getInt("trick-hud.avatar-offset-down", 122);
        int hotbarScale = validHotbarScale(config.getInt("hotbar-hud.scale", PackAssets.HOTBAR_DEFAULT_SCALE));
        return currentGeometry(
            avatarScale,
            config.getBoolean("trick-hud.avatar-outline.enabled", true),
            counterScale,
            counterDownOffset,
            hotbarScale);
    }

    private static PreviewGeometry currentGeometry(Map<String, Object> values) {
        int avatarScale = validAvatarScale(intValue(values.getOrDefault(
            "trick-hud.avatar-scale", defaultAvatarScale())));
        int counterScale = validCounterScale(intValue(values.getOrDefault(
            "trick-hud.counter.scale", PackAssets.COUNTER_DEFAULT_SCALE)));
        int counterDownOffset = intValue(values.getOrDefault("trick-hud.counter.offset-down", 122));
        int hotbarScale = validHotbarScale(intValue(values.getOrDefault(
            "hotbar-hud.scale", PackAssets.HOTBAR_DEFAULT_SCALE)));
        boolean outlined = Boolean.TRUE.equals(values.getOrDefault("trick-hud.avatar-outline.enabled", true));
        return currentGeometry(avatarScale, outlined, counterScale, counterDownOffset, hotbarScale);
    }

    private static PreviewGeometry currentGeometry(int avatarScale, boolean outlined) {
        return currentGeometry(avatarScale, outlined,
            PackAssets.COUNTER_DEFAULT_SCALE,
            122,
            PackAssets.HOTBAR_DEFAULT_SCALE);
    }

    private static PreviewGeometry currentGeometry(int avatarScale, boolean outlined,
                                                   int counterScale, int counterOffsetDown, int hotbarScale) {
        PackAssets.HotbarTier compatibilityHotbar = PackAssets.hotbarTier(PackAssets.HOTBAR_DEFAULT_SCALE);
        return new PreviewGeometry(
            640,
            360,
            20,
            360,
            cardGeometries(),
            avatarGeometries(),
            counterGeometries(),
            sampleCardFixtures(),
            avatarSlotGeometries(avatarScale, outlined),
            avatarLayoutGeometries(),
            counterTierGeometries(counterOffsetDown),
            hotbarGeometries(),
            // 以下字段是旧前端兼容字段，继续固定为 100% 资源档的默认几何；新前端使用
            // counterTiers/hotbars，避免把当前选择误当成字形表的唯一档位。
            PackAssets.COUNTER_CELL_WIDTH,
            PackAssets.COUNTER_CELL_HEIGHT,
            PackAssets.COUNTER_CELL_ADVANCE,
            PackAssets.COUNTER_LABEL_HEIGHT,
            PackAssets.COUNTER_FRAME_HEIGHT,
            PackAssets.COUNTER_DIGIT_HEIGHT,
            PackAssets.COUNTER_LABEL_ASCENT,
            PackAssets.COUNTER_FRAME_TOP_DELTA,
            PackAssets.COUNTER_DIGIT_INSET,
            compatibilityHotbar.width(),
            compatibilityHotbar.advance(),
            compatibilityHotbar.height(),
            compatibilityHotbar.baseAscent(),
            HudOverlayLayout.minHotbarOffsetY(compatibilityHotbar.scale()),
            HudOverlayLayout.maxHotbarOffsetY(compatibilityHotbar.scale()));
    }

    private static int defaultAvatarScale() {
        return PackAssets.avatarPixelScaleTierOf(6) >= 0
            ? 6
            : PackAssets.avatarPixelScaleAt(0);
    }

    private static int sideAvatarScale() {
        return PackAssets.avatarPixelScaleTierOf(4) >= 0
            ? 4
            : PackAssets.avatarPixelScaleAt(0);
    }

    private static int validAvatarScale(int scale) {
        return PackAssets.avatarPixelScaleTierOf(scale) < 0 ? defaultAvatarScale() : scale;
    }

    private static int validCounterScale(int scale) {
        return PackAssets.counterScaleTierOf(scale) < 0 ? PackAssets.COUNTER_DEFAULT_SCALE : scale;
    }

    private static int validHotbarScale(int scale) {
        return PackAssets.hotbarScaleTierOf(scale) < 0 ? PackAssets.HOTBAR_DEFAULT_SCALE : scale;
    }

    static Patch parsePatch(String jsonPatch) {
        if (jsonPatch == null || jsonPatch.isBlank()) {
            throw new ValidationException("请求体必须是 JSON 对象。可提交 {\"trick-hud.enabled\":true} 或 {\"values\":{...}}。");
        }
        JsonElement root;
        try {
            root = GSON.fromJson(jsonPatch, JsonElement.class);
        } catch (JsonParseException exception) {
            throw new ValidationException("JSON 解析失败：" + exception.getMessage());
        }
        if (root == null || !root.isJsonObject()) {
            throw new ValidationException("请求体必须是 JSON 对象。");
        }
        JsonObject object = root.getAsJsonObject();
        if (object.has("values")) {
            JsonElement values = object.get("values");
            if (!values.isJsonObject()) {
                throw new ValidationException("values 必须是 JSON 对象。");
            }
            object = values.getAsJsonObject();
        }

        LinkedHashMap<String, Object> parsed = new LinkedHashMap<>();
        Set<String> invalidKeys = new LinkedHashSet<>();
        for (Map.Entry<String, JsonElement> entry : object.entrySet()) {
            FieldSpec spec = FIELDS.get(entry.getKey());
            if (spec == null) {
                invalidKeys.add(entry.getKey());
                continue;
            }
            parsed.put(spec.key(), spec.parse(entry.getValue()));
        }
        if (!invalidKeys.isEmpty()) {
            throw new ValidationException("不允许修改非白名单键：" + String.join(", ", invalidKeys));
        }
        return new Patch(parsed);
    }

    static List<String> overlapWarnings(Map<String, Object> values) {
        int offsetDown = intValue(values.get("trick-hud.offset-down"));
        int avatarOffsetDown = intValue(values.get("trick-hud.avatar-offset-down"));
        int avatarScale = intValue(values.get("trick-hud.avatar-scale"));
        int required = PackAssets.avatarRowDownOffset(offsetDown, avatarScale);
        if (avatarOffsetDown >= required) {
            return List.of();
        }
        return List.of("头像行会与牌行重叠：trick-hud.avatar-offset-down=" + avatarOffsetDown
            + "，至少需要 " + required + "（offset-down=" + offsetDown
            + " + 12*avatar-scale=" + (PackAssets.AVATAR_ROW_TOTAL_PIXELS * avatarScale) + "）。");
    }

    private static int intValue(Object value) {
        return value instanceof Number number ? number.intValue() : Integer.parseInt(String.valueOf(value));
    }

    private static List<PreviewGeometry.CardGeometry> cardGeometries() {
        List<PreviewGeometry.CardGeometry> geometries = new ArrayList<>();
        for (int tier = 0; tier < PackAssets.cardGlyphHeightTierCount(); tier++) {
            int height = PackAssets.cardGlyphHeightAt(tier);
            geometries.add(new PreviewGeometry.CardGeometry(
                tier,
                height,
                PackAssets.cardGlyphWidth(tier),
                PackAssets.cardGlyphAdvance(tier)
            ));
        }
        return List.copyOf(geometries);
    }

    private static List<PreviewGeometry.AvatarGeometry> avatarGeometries() {
        List<PreviewGeometry.AvatarGeometry> geometries = new ArrayList<>();
        for (int scale : PackAssets.AVATAR_PIXEL_SCALE_TIERS) {
            geometries.add(new PreviewGeometry.AvatarGeometry(
                scale,
                PlayerHeadRenderer.advanceWidth(scale, false),
                PlayerHeadRenderer.advanceWidth(scale, true),
                PackAssets.AVATAR_ROW_TOTAL_PIXELS * scale
            ));
        }
        return List.copyOf(geometries);
    }

    private static List<PreviewGeometry.CounterGeometry> counterGeometries() {
        TrickHudPreview.CounterSnapshot counters = TrickHudPreview.counterSnapshot();
        List<PreviewGeometry.CounterGeometry> geometries = new ArrayList<>();
        for (CardRank rank : CardRank.values()) {
            // 固定预览 fixture 与游戏内调试棒共用；这里只负责下发已推导好的 played/remaining/exhausted。
            geometries.add(new PreviewGeometry.CounterGeometry(
                rank.label(), counters.remaining(rank), counters.played(rank), counters.exhausted(rank)
            ));
        }
        return List.copyOf(geometries);
    }

    /** 按当前 counter 原始 Y 偏移为每个合法 scale 下发完整的三层 cell 几何。 */
    private static List<PreviewGeometry.CounterTierGeometry> counterTierGeometries(int offsetDown) {
        List<PreviewGeometry.CounterTierGeometry> geometries = new ArrayList<>();
        for (int scale : PackAssets.COUNTER_SCALE_TIERS) {
            PackAssets.CounterTier tier = PackAssets.counterTierForOffset(scale, offsetDown);
            geometries.add(new PreviewGeometry.CounterTierGeometry(
                tier.scale(),
                tier.width(),
                tier.height(),
                tier.advance(),
                tier.labelHeight(),
                tier.frameHeight(),
                tier.digitHeight(),
                tier.labelAscent(),
                tier.frameTopDelta(),
                tier.digitInset()));
        }
        return List.copyOf(geometries);
    }

    /** 下发完整 hotbar 三图标几何，前端只消费这些字段，不复算槽位/advance。 */
    private static List<PreviewGeometry.HotbarGeometry> hotbarGeometries() {
        List<PreviewGeometry.HotbarGeometry> geometries = new ArrayList<>();
        for (int scale : PackAssets.HOTBAR_SCALE_TIERS) {
            PackAssets.HotbarTier tier = PackAssets.hotbarTier(scale);
            List<PreviewGeometry.HotbarIconGeometry> icons = new ArrayList<>();
            for (int index = 0; index < 3; index++) {
                icons.add(new PreviewGeometry.HotbarIconGeometry(
                    index,
                    PackAssets.hotbarIconTexture(index, scale),
                    PackAssets.hotbarIconWidth(scale),
                    PackAssets.hotbarIconHeight(scale),
                    PackAssets.hotbarIconStep(scale),
                    PackAssets.hotbarIconAdvance(scale),
                    PackAssets.hotbarIconChar(index, scale, false).codePointAt(0),
                    PackAssets.hotbarIconChar(index, scale, true).codePointAt(0)));
            }
            geometries.add(new PreviewGeometry.HotbarGeometry(
                tier.scale(), tier.width(), tier.height(), tier.advance(),                 tier.baseAscent(),
                HudOverlayLayout.minHotbarOffsetY(tier.scale()),
                HudOverlayLayout.maxHotbarOffsetY(tier.scale()),
                tier.font(), icons,
                tier.selectTexture(), tier.selectWidth(), tier.selectHeight(), tier.selectAdvance(),
                3, PackAssets.hotbarIconStep(scale), tier.selectStartX(), tier.selectStartY(),
                tier.selectCodepoint(), tier.selectDebugCodepoint()));
        }
        return List.copyOf(geometries);
    }

    private static List<PreviewGeometry.CardFixture> sampleCardFixtures() {
        return TrickHudPreview.sampleCards().stream()
            .map(card -> new PreviewGeometry.CardFixture(card.displayLabel(), card.rank().label(), card.suit().name()))
            .toList();
    }

    private static List<PreviewGeometry.AvatarSlotGeometry> avatarSlotGeometries(int middleScale, boolean outlined) {
        int sideScale = sideAvatarScale();
        int sideContent = PlayerHeadRenderer.advanceWidth(sideScale, outlined);
        int middleContent = PlayerHeadRenderer.advanceWidth(middleScale, outlined);
        int slotWidth = Math.max(sideContent, middleContent);
        return List.of(
            avatarSlot("left", sideScale, slotWidth, sideContent, sideScale, false, false),
            avatarSlot("middle", middleScale, slotWidth, middleContent, middleScale, true, false),
            avatarSlot("right", sideScale, slotWidth, sideContent, sideScale, false, false)
        );
    }

    /**
     * 为每个合法的中间头像倍数和描边组合下发完整三槽几何；前端切换表单时只查这张表。
     */
    private static List<PreviewGeometry.AvatarLayoutGeometry> avatarLayoutGeometries() {
        List<PreviewGeometry.AvatarLayoutGeometry> layouts = new ArrayList<>();
        for (int scale : PackAssets.AVATAR_PIXEL_SCALE_TIERS) {
            for (boolean outlined : new boolean[]{false, true}) {
                List<PreviewGeometry.AvatarSlotGeometry> slots = avatarSlotGeometries(scale, outlined);
                int slotWidth = slots.get(0).slotWidth();
                int rowHeight = slots.stream()
                    .mapToInt(slot -> slot.rowHeight() + (slot.crowned() ? slot.crownHeight() : 0))
                    .max()
                    .orElse(0);
                layouts.add(new PreviewGeometry.AvatarLayoutGeometry(
                    scale, outlined, slotWidth, rowHeight, slots));
            }
        }
        return List.copyOf(layouts);
    }

    private static PreviewGeometry.AvatarSlotGeometry avatarSlot(
        String position, int scale, int slotWidth, int contentWidth, int faceScale,
        boolean crowned, boolean empty) {
        return new PreviewGeometry.AvatarSlotGeometry(
            position, scale, slotWidth, contentWidth,
            PackAssets.AVATAR_OUTLINED_PIXELS * scale,
            contentWidth,
            PackAssets.AVATAR_CROWN_PIXELS * faceScale,
            crowned, empty);
    }

    static Map<String, FieldSpec> fields() {
        return FIELDS;
    }

    private static List<FieldDto> fieldDtos() {
        List<FieldDto> fields = new ArrayList<>(FIELDS.size());
        for (FieldSpec spec : FIELDS.values()) {
            fields.add(spec.dto());
        }
        return Collections.unmodifiableList(fields);
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> deepCopyRoot(Map<String, Object> source) {
        LinkedHashMap<String, Object> copy = new LinkedHashMap<>();
        for (Map.Entry<String, Object> entry : source.entrySet()) {
            Object value = entry.getValue();
            if (value instanceof Map<?, ?> map) {
                copy.put(entry.getKey(), deepCopyRoot((Map<String, Object>) map));
            } else if (value instanceof List<?> list) {
                copy.put(entry.getKey(), new ArrayList<>(list));
            } else {
                copy.put(entry.getKey(), value);
            }
        }
        return copy;
    }

    private static FieldSpec bool(String key, boolean fallback, String label, String group) {
        return new FieldSpec(key, ValueType.BOOLEAN, fallback, null, null, null, label, group, "checkbox", List.of());
    }

    private static FieldSpec integer(String key, int fallback, Integer min, Integer max, int step,
                                     String label, String group) {
        String control = min != null && max != null ? "range" : "number";
        return new FieldSpec(key, ValueType.INTEGER, fallback, min, max, step, label, group, control, List.of());
    }

    /** 连续布局偏移必须保持普通 number 控件，不能被网页误解为离散资源档位。 */
    private static FieldSpec continuousInteger(String key, int fallback, int min, int max,
                                               String label, String group) {
        return new FieldSpec(key, ValueType.INTEGER, fallback, min, max, 1, label, group, "number", List.of());
    }

    private static FieldSpec tierInteger(String key, int fallback, List<Integer> options,
                                         String label, String group) {
        List<Integer> sorted = new ArrayList<>(options);
        sorted.sort(Integer::compareTo);
        int min = sorted.get(0);
        int max = sorted.get(sorted.size() - 1);
        int step = uniformStep(sorted);
        return new FieldSpec(key, ValueType.INTEGER, fallback, min, max, step,
            label, group, "select", List.copyOf(sorted));
    }

    private static int uniformStep(List<Integer> values) {
        if (values.size() < 2) {
            return 1;
        }
        int step = values.get(1) - values.get(0);
        for (int index = 2; index < values.size(); index++) {
            if (values.get(index) - values.get(index - 1) != step) {
                return 1;
            }
        }
        return Math.max(1, step);
    }

    private static List<Integer> avatarScaleOptions() {
        List<Integer> values = new ArrayList<>();
        for (int scale : PackAssets.AVATAR_PIXEL_SCALE_TIERS) {
            values.add(scale);
        }
        return values;
    }

    private static List<Integer> cardHeightOptions() {
        List<Integer> values = new ArrayList<>();
        for (int tier = 0; tier < PackAssets.cardGlyphHeightTierCount(); tier++) {
            values.add(PackAssets.cardGlyphHeightAt(tier));
        }
        return values;
    }

    private static List<Integer> cardDownOptions() {
        List<Integer> values = new ArrayList<>();
        for (int tier = 0; tier < PackAssets.cardGlyphDownOffsetTierCount(); tier++) {
            values.add(PackAssets.cardGlyphDownOffsetAt(tier));
        }
        return values;
    }

    private static List<Integer> avatarDownOptions() {
        List<Integer> values = new ArrayList<>();
        for (int tier = 0; tier < PackAssets.avatarDownOffsetTierCount(); tier++) {
            values.add(PackAssets.avatarDownOffsetAt(tier));
        }
        return values;
    }

    private static List<Integer> counterDownOptions() {
        List<Integer> values = new ArrayList<>();
        for (int tier = 0; tier < PackAssets.counterDownOffsetTierCount(); tier++) {
            values.add(PackAssets.counterDownOffsetAt(tier));
        }
        return values;
    }

    private static List<Integer> counterScaleOptions() {
        List<Integer> values = new ArrayList<>();
        for (int scale : PackAssets.COUNTER_SCALE_TIERS) {
            values.add(scale);
        }
        return values;
    }

    private static List<Integer> hotbarScaleOptions() {
        List<Integer> values = new ArrayList<>();
        for (int scale : PackAssets.HOTBAR_SCALE_TIERS) {
            values.add(scale);
        }
        return values;
    }

    private static FieldSpec color(String key, String fallback, String label, String group) {
        return new FieldSpec(key, ValueType.COLOR, fallback, null, null, null, label, group, "color", List.of());
    }

    private static final Map<String, FieldSpec> FIELDS = buildFields();

    private static Map<String, FieldSpec> buildFields() {
        LinkedHashMap<String, FieldSpec> specs = new LinkedHashMap<>();
        add(specs, bool("trick-hud.enabled", true, "出牌 HUD 总开关", "Trick HUD"));
        add(specs, tierInteger("trick-hud.avatar-scale", defaultAvatarScale(),
            avatarScaleOptions(), "大头像倍数", "Trick HUD"));
        add(specs, integer("trick-hud.avatar-gap", 6, null, null, 1, "头像到牌行间距", "Trick HUD"));
        add(specs, integer("trick-hud.card-step", 22, 1, null, 1, "相邻牌水平步进", "Trick HUD"));
        add(specs, tierInteger("trick-hud.card-height", PackAssets.DEFAULT_CARD_HEIGHT,
            cardHeightOptions(), "牌面高度", "Trick HUD"));
        add(specs, continuousInteger("trick-hud.offset-down", 50,
            HudOverlayLayout.MIN_TRICK_OFFSET, HudOverlayLayout.MAX_TRICK_OFFSET, "牌行向下偏移", "Trick HUD"));
        add(specs, continuousInteger("trick-hud.avatar-offset-down", 122,
            HudOverlayLayout.MIN_TRICK_OFFSET, HudOverlayLayout.MAX_TRICK_OFFSET, "头像行向下偏移", "Trick HUD"));
        add(specs, integer("trick-hud.offset-x", 0, null, null, 1, "整条 HUD 水平偏移", "Trick HUD"));
        add(specs, integer("trick-hud.card-offset-x", 0, null, null, 1, "牌行水平偏移", "Trick HUD"));
        add(specs, integer("trick-hud.avatar-offset-x", 0, null, null, 1, "头像行水平偏移", "Trick HUD"));
        add(specs, bool("trick-hud.avatar-outline.enabled", true, "头像描边开关", "头像描边"));
        add(specs, color("trick-hud.avatar-outline.color", "#000000", "头像描边颜色", "头像描边"));
        add(specs, bool("trick-hud.counter.enabled", true, "记牌器开关", "记牌器"));
        add(specs, tierInteger("trick-hud.counter.scale", PackAssets.COUNTER_DEFAULT_SCALE,
            counterScaleOptions(), "记牌器缩放档", "记牌器"));
        add(specs, continuousInteger("trick-hud.counter.offset-down", 122,
            HudOverlayLayout.MIN_TRICK_OFFSET, HudOverlayLayout.MAX_TRICK_OFFSET, "记牌器向下偏移", "记牌器"));
        add(specs, integer("trick-hud.counter.gap", 2, 0, null, 1, "记牌器格间距", "记牌器"));
        add(specs, bool("trick-hud.counter.hide-exhausted", false, "出完后隐藏该格", "记牌器"));
        add(specs, integer("trick-hud.counter.offset-x", 0, null, null, 1, "记牌行水平偏移", "记牌器"));
        add(specs, tierInteger("hotbar-hud.scale", PackAssets.HOTBAR_DEFAULT_SCALE,
            hotbarScaleOptions(), "Hotbar 缩放档", "Hotbar HUD"));
        add(specs, bool("hotbar-hud.enabled", false, "Hotbar HUD 开关", "Hotbar HUD"));
        // 水平偏移走 CE 负空格，任意整数都合法，所以不给 min/max（控件退化为普通数字框）。
        add(specs, integer("hotbar-hud.offset-x", 0, null, null, 1, "Hotbar 水平偏移", "Hotbar HUD"));
        // 垂直偏移属于四层连续布局资源请求，范围由 HudOverlayLayout 与当前 hotbar scale 同源。
        // 它必须保持普通 number 控件，不能把真实 37/83/157 等位置吸附到旧离散档。
        add(specs, continuousInteger("hotbar-hud.offset-y", 0,
            HudOverlayLayout.minHotbarOffsetY(PackAssets.HOTBAR_DEFAULT_SCALE), HudOverlayLayout.MAX_TRICK_OFFSET,
            "Hotbar 垂直偏移（需重建资源包并客户端下载）", "Hotbar HUD"));
        return Collections.unmodifiableMap(specs);
    }

    private static void add(Map<String, FieldSpec> specs, FieldSpec spec) {
        specs.put(spec.key(), spec);
    }

    public record Snapshot(Map<String, Object> values, List<String> warnings, List<FieldDto> fields,
                          PreviewGeometry geometry, DragSettings drag) {
        public Snapshot {
            values = Collections.unmodifiableMap(new LinkedHashMap<>(values));
            warnings = List.copyOf(warnings);
            fields = List.copyOf(fields);
            drag = drag == null ? DragSettings.defaults() : drag;
        }

        /** 兼容只关心配置值的调用方（测试、旧构造点），几何按这些配置值补齐。 */
        public Snapshot(Map<String, Object> values, List<String> warnings, List<FieldDto> fields) {
            this(values, warnings, fields, currentGeometry(values), DragSettings.defaults());
        }

        public Snapshot(Map<String, Object> values, List<String> warnings, List<FieldDto> fields,
                        PreviewGeometry geometry) {
            this(values, warnings, fields, geometry, DragSettings.defaults());
        }
    }

    public record DragSettings(boolean snapEnabled, int snapThreshold,
                               boolean centerGuidesEnabled, boolean altAxisLock) {
        public static DragSettings defaults() {
            return new DragSettings(true, 4, true, true);
        }
    }

    /**
     * 下发给预览页的像素几何，单位一律是【Minecraft 像素】，不是 CSS 像素。
     *
     * <p>前端只负责乘上 GUI scale 画矩形，以及把鼠标位移除回 MC 像素，
     * 不做任何字形表复算。
     *
     * @param screenWidth        预览屏幕宽度（Debug Web 校准值，不是配置键）
     * @param screenHeight       预览屏幕高度（Debug Web 校准值，不是配置键）
     * @param bossBarBaselineY    BossBar 基线 Y（Debug Web 校准值，不是配置键）
     * @param actionBarBottomY    ActionBar 底边 Y（Debug Web 校准值，不是配置键）
     * @param cards              牌几何，按资源包档位顺序下发
     * @param avatars            头像几何，按头像倍数顺序下发
     * @param counters           记牌器各格动态数据，按 CardRank 顺序下发
     * @param counterTiers       counter 各缩放档的完整分层 cell 几何；当前 counter Y 已折入 ascent
     * @param hotbars             hotbar 各缩放档的完整九槽与选中框几何
     * @param counterCellWidth   记牌器 cell 宽度（旧前端兼容字段，固定 33px）
     * @param counterCellHeight  记牌器 cell 高度（固定 36px）
     * @param counterAdvance     记牌器 cell 前进量（固定 34px）
     * @param counterLabelHeight 记牌器上方牌类区域高度（固定 16px）
     * @param counterFrameHeight 记牌器闭合矩形高度（固定 16px）
     * @param counterDigitHeight 记牌器下方数字区域高度（固定 10px）
     * @param counterLabelAscent 记牌器标签层基准 ascent，预览按 BossBar baseline 对齐实际 provider
     * @param counterFrameTopDelta 闭合矩形相对 cell 顶部的偏移（固定 20px）
     * @param counterDigitInset  数字相对闭合矩形的内缩（固定 3px）
     * @param hotbarWidth        hotbar 底图贴图宽
     * @param hotbarAdvance      hotbar 底图前进量
     * @param hotbarHeight       hotbar 底图贴图高（= images.yml 的 height，1:1 渲染）
     * @param hotbarBaseAscent   hotbar 基准 ascent（offset-y = 0 时的值）
     * @param hotbarMinOffsetY   offset-y 下界（受 ascent &lt;= height 限制）
     * @param hotbarMaxOffsetY   offset-y 上界
     */
    public record PreviewGeometry(
        int screenWidth, int screenHeight, int bossBarBaselineY, int actionBarBottomY,
        List<CardGeometry> cards, List<AvatarGeometry> avatars, List<CounterGeometry> counters,
        List<CardFixture> sampleCards, List<AvatarSlotGeometry> avatarSlots,
        List<AvatarLayoutGeometry> avatarLayouts,
        List<CounterTierGeometry> counterTiers, List<HotbarGeometry> hotbars,
        int counterCellWidth, int counterCellHeight, int counterAdvance,
        int counterLabelHeight, int counterFrameHeight, int counterDigitHeight,
        int counterLabelAscent, int counterFrameTopDelta, int counterDigitInset,
        int hotbarWidth, int hotbarAdvance, int hotbarHeight, int hotbarBaseAscent,
        int hotbarMinOffsetY, int hotbarMaxOffsetY) {
        public PreviewGeometry {
            cards = List.copyOf(cards);
            avatars = List.copyOf(avatars);
            counters = List.copyOf(counters);
            sampleCards = List.copyOf(sampleCards);
            avatarSlots = List.copyOf(avatarSlots);
            avatarLayouts = List.copyOf(avatarLayouts);
            counterTiers = List.copyOf(counterTiers);
            hotbars = List.copyOf(hotbars);
        }

        public record CardGeometry(int tier, int height, int width, int advance) {}

        public record CardFixture(String label, String rank, String suit) {}

        public record AvatarGeometry(int scale, int plainAdvance, int outlinedAdvance, int rowHeight) {}

        /**
         * 一个中间头像倍数与描边开关组合下的完整三槽布局。前端切换表单时只查找此表，
         * 不自行复制头像 advance、槽宽或王冠盒高公式。
         */
        public record AvatarLayoutGeometry(int middleScale, boolean outlined, int slotWidth,
                                           int rowHeight, List<AvatarSlotGeometry> slots) {
            public AvatarLayoutGeometry {
                slots = List.copyOf(slots);
            }
        }

        /**
         * 头像预览槽位的真实占位几何。side 槽固定为 4 倍，中间槽跟随 avatar-scale；
         * slotWidth 是三槽统一宽度，contentAdvance 是该槽实际头像前进量，前端据此做槽内居中。
         * crowned 与 empty 是语义字段，不能靠 CSS 颜色或高度猜地主王冠、空槽。
         */
        public record AvatarSlotGeometry(
            String position, int scale, int slotWidth, int contentAdvance, int rowHeight,
            int faceAdvance, int crownHeight, boolean crowned, boolean empty) {}

        /** counter 的三层位图 cell 几何；ascent 已包含当前独立 counter Y 档。 */
        public record CounterTierGeometry(
            int scale, int cellWidth, int cellHeight, int advance,
            int labelHeight, int frameHeight, int digitHeight,
            int labelAscent, int frameTopDelta, int digitInset) {}

        /** hotbar 的完整缩放、三图标、选中框与 overlay 码位几何。 */
        public record HotbarGeometry(
            int scale, int width, int height, int advance, int baseAscent,
            int minOffsetY, int maxOffsetY, String font, List<HotbarIconGeometry> icons,
            String selectTexture, int selectWidth, int selectHeight, int selectAdvance,
            int slotCount, int slotStep, int selectStartX, int selectStartY,
            int baseCodepoint, int debugCodepoint) {
            public HotbarGeometry {
                icons = List.copyOf(icons);
            }
        }

        /** 单个独立道具图标；texture、尺寸、advance 与构建期 PNG 同源。 */
        public record HotbarIconGeometry(
            int index, String texture, int width, int height, int step, int advance,
            int codepoint, int debugCodepoint) {}

        /**
         * 记牌器 cell 的动态数据；宽度、高度与前进量由 PreviewGeometry 固定下发。
         * playedCount 是累计已出数量，exhausted 明确表示该点数已耗尽，前端不推导状态。
         */
        public record CounterGeometry(String label, int remaining, int playedCount, boolean exhausted) {}
    }


    record DiskSaveResult(boolean ok, List<String> appliedKeys, List<String> messages,
                          HudResourceRequest resources) {
        DiskSaveResult {
            appliedKeys = List.copyOf(appliedKeys);
            messages = List.copyOf(messages);
            resources = Objects.requireNonNull(resources, "resources");
            if (PackAssets.hotbarScaleTierOf(resources.hotbarScale()) < 0) {
                throw new ValidationException("hotbar-hud.scale=" + resources.hotbarScale()
                    + " 不是当前资源包已生成的档位，需要重新生成资源包后才能使用。");
            }
        }

        /** 兼容旧 coordinator/test 调用点；新 pipeline 只读取完整 resources()。 */
        int offsetY() {
            return resources.hotbarOffsetY();
        }

        /** 兼容旧 coordinator/test 调用点；新 pipeline 只读取完整 resources()。 */
        int hotbarScale() {
            return resources.hotbarScale();
        }
    }

    public record SaveResult(boolean ok, Snapshot snapshot, List<String> appliedKeys, List<String> messages) {
        public SaveResult {
            appliedKeys = List.copyOf(appliedKeys);
            messages = List.copyOf(messages);
        }

        static SaveResult success(Snapshot snapshot, List<String> appliedKeys, List<String> messages) {
            return new SaveResult(true, snapshot, appliedKeys, messages);
        }

        static SaveResult failed(Snapshot snapshot, List<String> messages) {
            return new SaveResult(false, snapshot, List.of(), messages);
        }
    }

    record Patch(Map<String, Object> values) {
        Patch {
            values = Collections.unmodifiableMap(new LinkedHashMap<>(values));
        }
    }

    public record FieldDto(
        String key,
        String type,
        Object fallback,
        Integer min,
        Integer max,
        Integer step,
        String label,
        String group,
        String control,
        List<Integer> options
    ) {
        public FieldDto {
            options = List.copyOf(options);
        }
    }

    record FieldSpec(
        String key,
        ValueType type,
        Object fallback,
        Integer min,
        Integer max,
        Integer step,
        String label,
        String group,
        String control,
        List<Integer> options
    ) {
        FieldSpec {
            options = List.copyOf(options);
        }

        Object read(MuzYamlConfig config) {
            return switch (type) {
                case BOOLEAN -> config.getBoolean(key, (Boolean) fallback);
                case INTEGER -> normalizeInteger(config.getInt(key, (Integer) fallback));
                case COLOR -> normalizeColor(config.getString(key, String.valueOf(fallback)), key);
            };
        }

        private boolean isScaleField() {
            return "trick-hud.avatar-scale".equals(key)
                || "trick-hud.counter.scale".equals(key)
                || "hotbar-hud.scale".equals(key);
        }

        private boolean isContinuousOffsetField() {
            return "trick-hud.offset-down".equals(key)
                || "trick-hud.avatar-offset-down".equals(key)
                || "trick-hud.counter.offset-down".equals(key)
                || "hotbar-hud.offset-y".equals(key);
        }

        private int normalizeInteger(int raw) {
            if (!options.isEmpty()) {
                if (isScaleField() && !options.contains(raw)) {
                    throw new ValidationException(key + "=" + raw
                        + " 不是当前资源包已生成的档位，需要重新生成资源包后才能使用。");
                }
                int nearest = options.get(0);
                int distance = Math.abs(raw - nearest);
                for (int option : options) {
                    int optionDistance = Math.abs(raw - option);
                    if (optionDistance < distance || optionDistance == distance && option < nearest) {
                        nearest = option;
                        distance = optionDistance;
                    }
                }
                return nearest;
            }
            if (min != null && raw < min || max != null && raw > max) {
                if (isContinuousOffsetField()) {
                    throw new ValidationException(key + " 超出范围（" + min + ".." + max + "）：" + raw);
                }
                return (Integer) fallback;
            }
            return raw;
        }

        Object parse(JsonElement element) {
            if (element == null || element.isJsonNull()) {
                throw new ValidationException(key + " 不允许为空。");
            }
            return switch (type) {
                case BOOLEAN -> parseBoolean(element);
                case INTEGER -> parseInteger(element);
                case COLOR -> normalizeColor(parseString(element), key);
            };
        }

        FieldDto dto() {
            return new FieldDto(key, type.name().toLowerCase(Locale.ROOT), fallback, min, max, step,
                label, group, control, options);
        }

        private boolean parseBoolean(JsonElement element) {
            if (!element.isJsonPrimitive() || !element.getAsJsonPrimitive().isBoolean()) {
                throw new ValidationException(key + " 必须是布尔值。");
            }
            return element.getAsBoolean();
        }

        private int parseInteger(JsonElement element) {
            if (!element.isJsonPrimitive() || !element.getAsJsonPrimitive().isNumber()) {
                throw new ValidationException(key + " 必须是整数。");
            }
            final int value;
            try {
                value = element.getAsBigDecimal().intValueExact();
            } catch (ArithmeticException | NumberFormatException exception) {
                throw new ValidationException(key + " 必须是整数。");
            }
            if (!options.isEmpty() && !options.contains(value)) {
                throw new ValidationException(key + " 不是当前资源包的合法档位：" + value
                    + "，需要重新生成资源包后才能使用。");
            }
            if (min != null && value < min || max != null && value > max) {
                String lower = min == null ? "无下限" : String.valueOf(min);
                String upper = max == null ? "无上限" : String.valueOf(max);
                throw new ValidationException(key + " 超出范围（" + lower + ".." + upper + "）：" + value);
            }
            int base = min == null ? 0 : min;
            if (step != null && step > 1 && Math.floorMod(value - base, step) != 0) {
                throw new ValidationException(key + " 必须符合步长 " + step + "（从 " + base + " 起算）：" + value);
            }
            return value;
        }

        private String parseString(JsonElement element) {
            if (!element.isJsonPrimitive() || !element.getAsJsonPrimitive().isString()) {
                throw new ValidationException(key + " 必须是字符串。");
            }
            return element.getAsString();
        }
    }

    enum ValueType {
        BOOLEAN,
        INTEGER,
        COLOR
    }

    static String normalizeColor(String raw, String key) {
        if (raw == null) {
            throw new ValidationException(key + " 颜色不能为空。");
        }
        String color = raw.trim();
        if (!color.matches("#(?i:[0-9a-f]{6}|[0-9a-f]{8})")) {
            throw new ValidationException(key + " 必须是 #RRGGBB 或 #AARRGGBB：" + raw);
        }
        return "#" + color.substring(1).toUpperCase(Locale.ROOT);
    }

    static final class ValidationException extends RuntimeException {
        ValidationException(String message) {
            super(message);
        }
    }
}
