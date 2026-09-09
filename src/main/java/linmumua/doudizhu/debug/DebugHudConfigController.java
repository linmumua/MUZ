package linmumua.doudizhu.debug;

import com.google.gson.Gson;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParseException;
import linmumua.doudizhu.DoudizhuPlugin;
import linmumua.doudizhu.assets.PackAssets;
import linmumua.doudizhu.assets.PlayerHeadRenderer;
import linmumua.doudizhu.config.MuzYamlConfig;
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
 * <p>这个类刻意只暴露 19 个运行期可轻量应用的键：16 个 {@code trick-hud.*}
 * 叶子键，加上 3 个 {@code hotbar-hud.*} 键。Web 请求里的其它键
 * 一律拒绝，避免调试面板变成任意 YAML 编辑器，也避免 HTTP 线程碰到牌桌坐标、经济、存储等
 * 非 HUD 运行态配置。
 */
public final class DebugHudConfigController {
    private static final Gson GSON = new Gson();
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
            return savePatch(parsePatch(jsonPatch));
        } catch (ValidationException exception) {
            return SaveResult.failed(snapshot(), List.of(exception.getMessage()));
        }
    }

    /**
     * 写入已经校验过的 patch。这个方法只做内存合并与 config.yml I/O，不应用运行态，
     * 由 HudWebApplyCoordinator 在单线程执行器中调用。
     */
    DiskSaveResult savePatchToDisk(Patch patch) {
        synchronized (plugin.hudWebConfigLock()) {
            if (patch.values().isEmpty()) {
                return new DiskSaveResult(true, List.of(), List.of("没有提交任何变更。"),
                    plugin.yamlConfig().getInt("hotbar-hud.offset-y", 0));
            }
            MuzYamlConfig config = plugin.yamlConfig();
            // 这是 Web 自身的快照边界：先复制当前根，再在同一把锁内合并并原子写盘。
            // 其它旧配置入口尚未统一使用此锁，因此不能宣称它能阻止所有外部配置竞态。
            Map<String, Object> before = deepCopyRoot(config.rawRoot());
            try {
                for (Map.Entry<String, Object> entry : patch.values().entrySet()) {
                    config.set(entry.getKey(), entry.getValue());
                }
                config.saveWithComments(packagedConfigTemplate());
                return new DiskSaveResult(true, new ArrayList<>(patch.values().keySet()), List.of(),
                    config.getInt("hotbar-hud.offset-y", 0));
            } catch (Exception exception) {
                config.set(null, before);
                return new DiskSaveResult(false, List.of(),
                    List.of("保存 config.yml 失败，已回滚内存配置：" + exception.getMessage()),
                    config.getInt("hotbar-hud.offset-y", 0));
            }
        }
    }

    /**
     * Debug Web 专用磁盘重载：文件读取与共享配置替换都在线程池内完成，且与 Web 保存共用同一把锁。
     * 不调用插件完整 reloadVisualState，也不触发牌桌重建。
     */
    int reloadFromDiskForWeb() {
        synchronized (plugin.hudWebConfigLock()) {
            MuzYamlConfig config = plugin.yamlConfig();
            config.reload();
            return config.getInt("hotbar-hud.offset-y", 0);
        }
    }

    /** 保留旧调用点兼容；运行态应用由 Debug Web coordinator 在主线程完成。 */
    SaveResult savePatch(Patch patch) {
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
        return new Snapshot(values, warnings, fieldDtos(), currentGeometry(), dragSettings(config));
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
        return new PreviewGeometry(
            640,
            360,
            20,
            360,
            cardGeometries(),
            avatarGeometries(),
            counterGeometries(),
            PackAssets.HOTBAR_HUD_GLYPH_WIDTH,
            PackAssets.HOTBAR_HUD_GLYPH_ADVANCE,
            HotbarDebugOverlayWriter.GLYPH_HEIGHT,
            HotbarDebugOverlayWriter.BASE_ASCENT,
            HotbarDebugOverlayWriter.minOffsetY(),
            HotbarDebugOverlayWriter.maxOffsetY());
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
        for (int scale = PackAssets.AVATAR_PIXEL_MIN_SCALE; scale <= PackAssets.AVATAR_PIXEL_MAX_SCALE; scale++) {
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
        List<PreviewGeometry.CounterGeometry> geometries = new ArrayList<>();
        for (CardRank rank : CardRank.values()) {
            int remaining = switch (rank) {
                case THREE, FOUR, FIVE, SIX, SEVEN, EIGHT, NINE -> 4;
                case TEN -> 12;
                case JACK, QUEEN, KING, ACE, TWO -> 1;
                case SMALL_JOKER -> 1;
                case BIG_JOKER -> 0;
            };
            geometries.add(new PreviewGeometry.CounterGeometry(
                rank.label(),
                remaining,
                previewCounterAdvance(rank.label(), String.valueOf(remaining))
            ));
        }
        return List.copyOf(geometries);
    }

    private static int previewCounterAdvance(String label, String digits) {
        // 这里只是预览 fixture，运行期文件不改；按现有 TrickHudService.counterCells 的经验公式复算。
        return 4 + label.length() * 6 + digits.length() * 6;
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

    private static FieldSpec color(String key, String fallback, String label, String group) {
        return new FieldSpec(key, ValueType.COLOR, fallback, null, null, null, label, group, "color", List.of());
    }

    private static final Map<String, FieldSpec> FIELDS = buildFields();

    private static Map<String, FieldSpec> buildFields() {
        LinkedHashMap<String, FieldSpec> specs = new LinkedHashMap<>();
        add(specs, bool("trick-hud.enabled", true, "出牌 HUD 总开关", "Trick HUD"));
        add(specs, integer("trick-hud.avatar-scale", 6,
            PackAssets.AVATAR_PIXEL_MIN_SCALE, PackAssets.AVATAR_PIXEL_MAX_SCALE, 1, "大头像倍数", "Trick HUD"));
        add(specs, integer("trick-hud.avatar-gap", 6, null, null, 1, "头像到牌行间距", "Trick HUD"));
        add(specs, integer("trick-hud.card-step", 22, 1, null, 1, "相邻牌水平步进", "Trick HUD"));
        add(specs, tierInteger("trick-hud.card-height", PackAssets.DEFAULT_CARD_HEIGHT,
            cardHeightOptions(), "牌面高度", "Trick HUD"));
        add(specs, tierInteger("trick-hud.offset-down", 50,
            cardDownOptions(), "牌行向下偏移", "Trick HUD"));
        add(specs, tierInteger("trick-hud.avatar-offset-down", 122,
            avatarDownOptions(), "头像行向下偏移", "Trick HUD"));
        add(specs, integer("trick-hud.offset-x", 0, null, null, 1, "整条 HUD 水平偏移", "Trick HUD"));
        add(specs, integer("trick-hud.card-offset-x", 0, null, null, 1, "牌行水平偏移", "Trick HUD"));
        add(specs, integer("trick-hud.avatar-offset-x", 0, null, null, 1, "头像行水平偏移", "Trick HUD"));
        add(specs, bool("trick-hud.avatar-outline.enabled", true, "头像描边开关", "头像描边"));
        add(specs, color("trick-hud.avatar-outline.color", "#000000", "头像描边颜色", "头像描边"));
        add(specs, bool("trick-hud.counter.enabled", true, "记牌器开关", "记牌器"));
        add(specs, integer("trick-hud.counter.gap", 2, 0, null, 1, "记牌器格间距", "记牌器"));
        add(specs, bool("trick-hud.counter.hide-exhausted", false, "出完后隐藏该格", "记牌器"));
        add(specs, integer("trick-hud.counter.offset-x", 0, null, null, 1, "记牌行水平偏移", "记牌器"));
        add(specs, bool("hotbar-hud.enabled", false, "Hotbar HUD 开关", "Hotbar HUD"));
        // 水平偏移走 CE 负空格，任意整数都合法，所以不给 min/max（控件退化为普通数字框）。
        add(specs, integer("hotbar-hud.offset-x", 0, null, null, 1, "Hotbar 水平偏移", "Hotbar HUD"));
        // 垂直偏移必须落在字形 ascent 上，受 Minecraft 的 ascent <= height 限制，
        // 区间由 HotbarDebugOverlayWriter 算出（两边同源，避免这里手抄一个会过时的常量）。
        add(specs, integer("hotbar-hud.offset-y", 0,
            HotbarDebugOverlayWriter.minOffsetY(), HotbarDebugOverlayWriter.maxOffsetY(), 1,
            "Hotbar 垂直偏移（需重载资源包）", "Hotbar HUD"));
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

        /** 兼容只关心配置值的调用方（测试、旧构造点），几何按当前资源包补齐。 */
        public Snapshot(Map<String, Object> values, List<String> warnings, List<FieldDto> fields) {
            this(values, warnings, fields, currentGeometry(), DragSettings.defaults());
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
     * @param counters           记牌器几何，按 CardRank 顺序下发
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
        int hotbarWidth, int hotbarAdvance, int hotbarHeight, int hotbarBaseAscent,
        int hotbarMinOffsetY, int hotbarMaxOffsetY) {
        public record CardGeometry(int tier, int height, int width, int advance) {}

        public record AvatarGeometry(int scale, int plainAdvance, int outlinedAdvance, int rowHeight) {}

        public record CounterGeometry(String label, int remaining, int advance) {}
    }


    record DiskSaveResult(boolean ok, List<String> appliedKeys, List<String> messages, int offsetY) {
        DiskSaveResult {
            appliedKeys = List.copyOf(appliedKeys);
            messages = List.copyOf(messages);
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

        private int normalizeInteger(int raw) {
            if (!options.isEmpty()) {
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
                throw new ValidationException(key + " 不是当前资源包的合法档位：" + value);
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
