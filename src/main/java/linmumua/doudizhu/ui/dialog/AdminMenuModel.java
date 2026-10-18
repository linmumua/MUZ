package linmumua.doudizhu.ui.dialog;

import java.util.List;
import linmumua.doudizhu.DoudizhuPlugin.AdminSetting;
import linmumua.doudizhu.DoudizhuPlugin.FurnitureType;
import linmumua.doudizhu.DoudizhuPlugin.PlayActionKind;
import linmumua.doudizhu.room.TableLevel;
import linmumua.doudizhu.ui.HandInventoryHolder.AdminPage;

/**
 * 管理菜单的「页 → 槽位 → 业务动作」对照模型，纯数据，不依赖 Bukkit。
 *
 * 这份模型是 1.10.59 箱子版管理菜单（{@code HandGuiService.openAdminModels} 画、
 * {@code HandGuiListener.handleAdminClick} 处理）向 Dialog 迁移时的唯一事实来源：
 * 十九个页面、每个按钮点下去要做什么、进入哪个子页，全部在这里声明一次。
 * 绘制侧（AdminDialogScreen）与测试都读这一份，避免「画了按钮但没人处理」或
 * 「换了 UI 就漏掉一整个功能」这类迁移回归。
 *
 * 槽位字段保留旧 GUI 的原始槽位号，仅用于排序与人工对照，不再是真实容器槽位；
 * 原生 Dialog 的按钮是一维列表，同一槽位出现多条表示旧代码在那个槽位上按
 * 左键/右键/中键分派了不同动作（例如选牌音效方案「左键编辑 / 右键恢复默认」），
 * 迁移后拆成两个独立按钮。
 */
public final class AdminMenuModel {
    private AdminMenuModel() {
    }

    /** 旧 GUI 里的通用页脚槽位，迁移后由 core 的导航接管，这里保留常量供对照。 */
    public static final int SLOT_STEP_CYCLE = 4;
    public static final int SLOT_BACK = 44;
    public static final int SLOT_HOME = 49;
    public static final int SLOT_RELOAD = 52;
    public static final int SLOT_CLOSE = 53;

    /** 一个按钮要做的事。target 的具体类型见每个常量的注释。 */
    public enum Kind {
        /** 进入另一个管理页；target = AdminPage。 */
        OPEN_PAGE,
        /** 调节一项 AdminSetting；target = AdminSetting。 */
        SETTING,
        /** 进入选牌音效方案编辑屏；target = Integer(profileIndex 0..3)。 */
        SELECTION_SOUND_EDITOR,
        /** 把选牌音效方案恢复默认；target = Integer(profileIndex 0..3)。 */
        SELECTION_SOUND_RESTORE,
        /** 进入某个牌型的动作子页；target = PlayActionKind。 */
        PLAY_ACTION_KIND,
        /** 进入音效选择屏；target = SoundTarget。 */
        SOUND_PICKER,
        /** 进入 AI 文本输入屏；target = AiField。 */
        AI_FIELD,
        /** 进入全局颜色输入屏；target = GlobalColor。 */
        GLOBAL_COLOR,
        /** 用主手物品设置家具外观；target = FurnitureType。 */
        FURNITURE_APPLY,
        /** 把家具外观恢复默认；target = FurnitureType。 */
        FURNITURE_RESET,
        /** 切换金币/实体筹码支付；无 target。 */
        PAYMENT_TOGGLE,
        /** 用主手物品设置实体筹码匹配模板；无 target。 */
        CHIP_TEMPLATE_SET,
        /** 恢复默认实体筹码模板；无 target。 */
        CHIP_TEMPLATE_RESET,
        /** 调整场次倍率；target = TableLevel。 */
        ROOM_MULTIPLIER,
        /** 切换场次是否启用经济；target = TableLevel。 */
        ROOM_ECONOMY,
        /** 在线玩家筹码速调面板（动态枚举在线玩家）；无 target。 */
        CHIP_BALANCE_PANEL,
        /** 进入椅侧左右面板布局编辑屏；无 target（屏自己从插件的布局端口取存储）。 */
        CHAIR_PANEL_LAYOUT
    }

    /** 音效选择屏的目标（管理员侧）。 */
    public enum SoundTarget {
        COUNTDOWN,
        UNREADY_WARNING,
        PLACEMENT_BLOCKED
    }

    /** AI 文本输入的目标字段（管理员侧）。 */
    public enum AiField {
        URL,
        KEY,
        MODEL,
        SYSTEM_PROMPT
    }

    /** 全局高亮颜色输入的目标（管理员侧）。 */
    public enum GlobalColor {
        HOVER_GLOW,
        SELECTED_GLOW
    }

    /**
     * 一个页内按钮。
     *
     * @param slot 旧 GUI 原始槽位，仅用于排序/对照
     * @param kind 动作类型
     * @param target 动作目标，类型随 kind 而定（见 {@link Kind}）
     * @param label 按钮文字（沿用旧 GUI 文案）
     */
    public record Entry(int slot, Kind kind, Object target, String label) {
    }

    /**
     * 一个管理页。
     *
     * @param id 页面 id
     * @param title 页面标题
     * @param parent 返回的上一页，首页为 null
     * @param entries 页内按钮
     * @param notes 纯展示的说明行（旧 GUI 里用 noteItem 展示的指引文案）
     */
    public record Page(AdminPage id, String title, AdminPage parent, List<Entry> entries, List<String> notes) {
    }

    private static Entry openPage(int slot, AdminPage page, String label) {
        return new Entry(slot, Kind.OPEN_PAGE, page, label);
    }

    private static Entry setting(int slot, AdminSetting setting) {
        return new Entry(slot, Kind.SETTING, setting, setting.label());
    }

    private static Entry selectionSoundEditor(int slot, int profileIndex) {
        return new Entry(slot, Kind.SELECTION_SOUND_EDITOR, profileIndex, "选牌音效方案 " + (profileIndex + 1));
    }

    private static Entry selectionSoundRestore(int slot, int profileIndex) {
        return new Entry(slot, Kind.SELECTION_SOUND_RESTORE, profileIndex, "选牌音效方案 " + (profileIndex + 1) + " · 恢复默认");
    }

    private static final List<Page> PAGES = List.of(
        new Page(AdminPage.HOME, "首页", null, List.of(
            openPage(19, AdminPage.DDZ_HOME, "斗地主牌桌"),
            openPage(23, AdminPage.GLOBAL_HOME, "通用视觉"),
            openPage(25, AdminPage.GLOBAL_ECONOMY, "经济与场次")
        ), List.of("斗地主 · 桌椅、按钮、卡牌、文字。", "通用视觉 · 动画、高亮、头像。", "经济与场次 · 支付、筹码、场次、数据库。")),

        new Page(AdminPage.DDZ_HOME, "斗地主", AdminPage.HOME, List.of(
            openPage(19, AdminPage.DDZ_FURNITURE, "桌椅摆位"),
            openPage(20, AdminPage.DDZ_BUTTONS, "按钮布局"),
            openPage(21, AdminPage.DDZ_CARDS, "卡牌表现"),
            openPage(22, AdminPage.DDZ_TEXT, "桌面文字"),
            openPage(23, AdminPage.DDZ_LABELS, "牌面标签"),
            openPage(28, AdminPage.DDZ_SEAT_TEXT, "座位文字"),
            openPage(29, AdminPage.DDZ_HITBOX, "碰撞交互"),
            openPage(30, AdminPage.DDZ_AUDIO, "音频"),
            openPage(31, AdminPage.DDZ_PLAYER_OPTIONS, "玩家选项"),
            openPage(32, AdminPage.DDZ_BOTS, "机器人行为"),
            openPage(33, AdminPage.DDZ_AI, "AI 配置")
        ), List.of()),

        new Page(AdminPage.GLOBAL_HOME, "通用视觉", AdminPage.HOME, List.of(
            openPage(20, AdminPage.GLOBAL_ANIMATION, "动画节奏"),
            openPage(22, AdminPage.GLOBAL_HIGHLIGHT, "高亮颜色"),
            openPage(24, AdminPage.GLOBAL_AVATARS, "头像组件")
        ), List.of()),

        new Page(AdminPage.GLOBAL_ECONOMY, "经济与场次", AdminPage.HOME, List.of(
            new Entry(10, Kind.PAYMENT_TOGGLE, null, "支付模式"),
            new Entry(12, Kind.CHIP_TEMPLATE_SET, null, "设置实体筹码匹配模板"),
            new Entry(14, Kind.CHIP_TEMPLATE_RESET, null, "恢复默认实体筹码模板"),
            new Entry(19, Kind.ROOM_MULTIPLIER, TableLevel.LOW, "低级场倍率"),
            new Entry(20, Kind.ROOM_MULTIPLIER, TableLevel.MID, "中级场倍率"),
            new Entry(21, Kind.ROOM_MULTIPLIER, TableLevel.HIGH, "高级场倍率"),
            new Entry(22, Kind.ROOM_MULTIPLIER, TableLevel.FUN, "娱乐场倍率"),
            new Entry(28, Kind.ROOM_ECONOMY, TableLevel.LOW, "低级场经济开关"),
            new Entry(29, Kind.ROOM_ECONOMY, TableLevel.MID, "中级场经济开关"),
            new Entry(30, Kind.ROOM_ECONOMY, TableLevel.HIGH, "高级场经济开关"),
            new Entry(31, Kind.ROOM_ECONOMY, TableLevel.FUN, "娱乐场经济开关"),
            new Entry(45, Kind.CHIP_BALANCE_PANEL, null, "在线玩家筹码")
        ), List.of("常用命令 · /muz set <牌桌id> <high|mid|low|fun>、/muz chip mode <gold|chip>、/muz chip balance <玩家> [数量]",
            "实体筹码模板按主手物品的完整元数据匹配，不会自动兑换或发放物品。")),

        new Page(AdminPage.DDZ_FURNITURE, "桌椅", AdminPage.DDZ_HOME, List.of(
            new Entry(10, Kind.FURNITURE_APPLY, FurnitureType.TABLE, "主手设为桌子"),
            new Entry(12, Kind.FURNITURE_RESET, FurnitureType.TABLE, "桌子恢复默认"),
            new Entry(14, Kind.FURNITURE_APPLY, FurnitureType.CHAIR, "主手设为椅子"),
            new Entry(16, Kind.FURNITURE_RESET, FurnitureType.CHAIR, "椅子恢复默认"),
            setting(19, AdminSetting.TABLE_SPAWN_OFFSET_Y),
            setting(21, AdminSetting.CHAIR_VISUAL_LATERAL),
            setting(23, AdminSetting.CHAIR_VISUAL_VERTICAL),
            setting(25, AdminSetting.CHAIR_ROTATION_DEGREES),
            setting(27, AdminSetting.CHAIR_DISTANCE),
            new Entry(29, Kind.SOUND_PICKER, SoundTarget.PLACEMENT_BLOCKED, "放置阻挡警告音"),
            // 椅侧左右面板的 14 个数值共享「镜像/比例」约束（一侧改动不能连带写另一侧、宽高必须等比），
            // 塞进「一项一个标量」的 AdminSetting 会让这些约束无处安放，因此单独一屏（概览 + 单字段）。
            new Entry(31, Kind.CHAIR_PANEL_LAYOUT, null, "椅子左右面板")
        ), List.of()),

        new Page(AdminPage.DDZ_BUTTONS, "按钮", AdminPage.DDZ_HOME, List.of(
            setting(10, AdminSetting.BUTTON_DISTANCE),
            setting(11, AdminSetting.BUTTON_HEIGHT),
            setting(19, AdminSetting.BUTTON_FRONT_BASE_DISTANCE),
            setting(20, AdminSetting.BUTTON_SIDE_BASE_DISTANCE),
            setting(21, AdminSetting.BUTTON_DISTANCE_FACTOR),
            setting(22, AdminSetting.BUTTON_SPACING),
            setting(23, AdminSetting.BUTTON_ARC_SMALL_ANGLE),
            setting(24, AdminSetting.BUTTON_ARC_LARGE_ANGLE),
            setting(25, AdminSetting.BUTTON_ARC_SMALL_RADIUS),
            setting(26, AdminSetting.BUTTON_ARC_LARGE_RADIUS)
        ), List.of()),

        new Page(AdminPage.DDZ_CARDS, "卡牌", AdminPage.DDZ_HOME, List.of(
            setting(10, AdminSetting.PRIVATE_CARD_SCALE),
            setting(12, AdminSetting.PRIVATE_CARD_WIDTH_SCALE),
            setting(13, AdminSetting.PRIVATE_CARD_HEIGHT_SCALE),
            setting(14, AdminSetting.PRIVATE_CARD_DEPTH_SCALE),
            setting(19, AdminSetting.HAND_SPACING),
            setting(22, AdminSetting.CARD_DEPTH_OFFSET),
            setting(24, AdminSetting.HOVER_CARD_LIFT),
            setting(25, AdminSetting.HOVER_CARD_SCALE),
            setting(28, AdminSetting.GLOBAL_HAND_LATERAL),
            setting(29, AdminSetting.GLOBAL_HAND_VERTICAL),
            setting(30, AdminSetting.GLOBAL_HAND_DEPTH)
        ), List.of()),

        new Page(AdminPage.GLOBAL_ANIMATION, "动画", AdminPage.GLOBAL_HOME, List.of(
            setting(11, AdminSetting.HOVER_CARD_INTERPOLATION_TICKS),
            setting(13, AdminSetting.HOVER_CARD_ANIMATION_TYPE)
        ), List.of("可切换线性、缓出、缓入缓出、回弹。时长越大越柔和。")),

        new Page(AdminPage.GLOBAL_HIGHLIGHT, "预选高亮", AdminPage.GLOBAL_HOME, List.of(
            setting(10, AdminSetting.HOVER_GLOW_ENABLED),
            new Entry(12, Kind.GLOBAL_COLOR, GlobalColor.HOVER_GLOW, "全局预览色"),
            setting(28, AdminSetting.SELECTED_GLOW_ENABLED),
            new Entry(30, Kind.GLOBAL_COLOR, GlobalColor.SELECTED_GLOW, "全局选中色")
        ), List.of("上半区控制预览发光，下半区控制已选发光。颜色支持 r,g,b，也支持十六进制。")),

        new Page(AdminPage.GLOBAL_AVATARS, "头像组件", AdminPage.GLOBAL_HOME, List.of(
            openPage(19, AdminPage.GLOBAL_STATUS_NAMES, "顶栏名字")
        ), List.of("顶部玩家名字的位置与大小在这里调。")),

        new Page(AdminPage.GLOBAL_STATUS_NAMES, "顶栏名字", AdminPage.GLOBAL_AVATARS, List.of(
            setting(10, AdminSetting.STATUS_NAME_SCALE),
            setting(12, AdminSetting.STATUS_NAME_LATERAL),
            setting(14, AdminSetting.STATUS_NAME_VERTICAL),
            setting(16, AdminSetting.STATUS_NAME_DEPTH)
        ), List.of("调整顶部玩家名字的大小和位置。")),

        new Page(AdminPage.DDZ_SEAT_TEXT, "座位文字", AdminPage.DDZ_HOME, List.of(
            setting(10, AdminSetting.EMPTY_SEAT_SCALE),
            setting(12, AdminSetting.EMPTY_SEAT_LATERAL),
            setting(14, AdminSetting.EMPTY_SEAT_VERTICAL),
            setting(16, AdminSetting.EMPTY_SEAT_DEPTH),
            setting(28, AdminSetting.SEAT_INFO_SCALE),
            setting(30, AdminSetting.SEAT_INFO_LATERAL),
            setting(32, AdminSetting.SEAT_INFO_VERTICAL),
            setting(34, AdminSetting.SEAT_INFO_DEPTH)
        ), List.of("上排是空位主文字「空位」。下排是座位副标题，如座位号、准备状态、分数。")),

        new Page(AdminPage.DDZ_TEXT, "桌面文字", AdminPage.DDZ_HOME, List.of(
            setting(10, AdminSetting.STATUS_HEIGHT),
            setting(12, AdminSetting.PLAY_DETAIL_HEIGHT),
            setting(19, AdminSetting.JOIN_LABEL_HEIGHT),
            setting(20, AdminSetting.JOIN_LABEL_SCALE),
            setting(21, AdminSetting.ACTION_LABEL_HEIGHT),
            setting(22, AdminSetting.ACTION_LABEL_SCALE)
        ), List.of("上排只调桌顶状态和上一手提示。下排只调加入按钮文字和准备/开始/离开文字。")),

        new Page(AdminPage.DDZ_LABELS, "牌面标签", AdminPage.DDZ_HOME, List.of(
            setting(10, AdminSetting.CARD_LABEL_HEIGHT),
            setting(12, AdminSetting.CARD_LABEL_LATERAL),
            setting(14, AdminSetting.CARD_LABEL_DEPTH),
            setting(28, AdminSetting.LABELS_ENABLED),
            setting(30, AdminSetting.DUPLICATE_ONLY)
        ), List.of("调整牌上数字标签的高度、左右和前后位置。")),

        new Page(AdminPage.DDZ_HITBOX, "碰撞交互", AdminPage.DDZ_HOME, List.of(
            setting(10, AdminSetting.BUTTON_HITBOX_LATERAL),
            setting(11, AdminSetting.BUTTON_HITBOX_DEPTH),
            setting(12, AdminSetting.BUTTON_HITBOX_VERTICAL),
            setting(28, AdminSetting.CHAIR_HITBOX_LATERAL),
            setting(29, AdminSetting.CHAIR_HITBOX_VERTICAL)
        ), List.of()),

        new Page(AdminPage.DDZ_AUDIO, "音频", AdminPage.DDZ_HOME, List.of(
            setting(10, AdminSetting.BGM_VOLUME),
            setting(11, AdminSetting.EFFECT_VOLUME),
            setting(12, AdminSetting.TURN_COUNTDOWN_SECONDS),
            new Entry(13, Kind.SOUND_PICKER, SoundTarget.COUNTDOWN, "倒计时音效"),
            new Entry(14, Kind.SOUND_PICKER, SoundTarget.UNREADY_WARNING, "未准备提醒音")
        ), List.of()),

        new Page(AdminPage.DDZ_PLAYER_OPTIONS, "玩家选项", AdminPage.DDZ_HOME, List.of(
            selectionSoundEditor(10, 0),
            selectionSoundEditor(11, 1),
            selectionSoundEditor(12, 2),
            selectionSoundEditor(13, 3),
            selectionSoundRestore(10, 0),
            selectionSoundRestore(11, 1),
            selectionSoundRestore(12, 2),
            selectionSoundRestore(13, 3),
            new Entry(19, Kind.PLAY_ACTION_KIND, PlayActionKind.AIRPLANE, PlayActionKind.AIRPLANE.label()),
            new Entry(20, Kind.PLAY_ACTION_KIND, PlayActionKind.STRAIGHT, PlayActionKind.STRAIGHT.label()),
            new Entry(21, Kind.PLAY_ACTION_KIND, PlayActionKind.PAIR_STRAIGHT, PlayActionKind.PAIR_STRAIGHT.label()),
            new Entry(28, Kind.PLAY_ACTION_KIND, PlayActionKind.TRIPLE_WITH_SINGLE, PlayActionKind.TRIPLE_WITH_SINGLE.label()),
            new Entry(29, Kind.PLAY_ACTION_KIND, PlayActionKind.BOMB, PlayActionKind.BOMB.label()),
            new Entry(30, Kind.PLAY_ACTION_KIND, PlayActionKind.JOKER_BOMB, PlayActionKind.JOKER_BOMB.label())
        ), List.of("左键进入对应牌型，右键在子页恢复默认。")),

        new Page(AdminPage.DDZ_BOTS, "机器人行为", AdminPage.DDZ_HOME, List.of(
            setting(10, AdminSetting.BOT_DELAY_MIN),
            setting(12, AdminSetting.BOT_DELAY_MAX),
            setting(14, AdminSetting.HINT_GROUP_LIMIT)
        ), List.of("机器人出牌快慢和提示数量。AI 相关设置在「AI 配置」页。")),

        new Page(AdminPage.DDZ_AI, "AI 配置", AdminPage.DDZ_HOME, List.of(
            new Entry(12, Kind.AI_FIELD, AiField.URL, "DeepSeek 链接"),
            new Entry(13, Kind.AI_FIELD, AiField.KEY, "DeepSeek 密钥"),
            new Entry(14, Kind.AI_FIELD, AiField.MODEL, "DeepSeek 模型"),
            new Entry(16, Kind.AI_FIELD, AiField.SYSTEM_PROMPT, "全局人设词")
        ), List.of("调试命令 · /muz debug bot 信息、/muz debug bot 信息 <id> 你好",
            "压测命令 · /muz debug add [数量]，在附近生成观察桌。",
            "默认牌风：稳健、保炸、残局再提爆发。全局人设词会同时影响机器人聊天和实战决策。"))
    );

    /** 全部十九个管理页。 */
    public static List<Page> pages() {
        return PAGES;
    }

    public static Page page(AdminPage id) {
        for (Page page : PAGES) {
            if (page.id() == id) {
                return page;
            }
        }
        throw new IllegalArgumentException("未知管理页：" + id);
    }

    public static String titleOf(AdminPage id) {
        return page(id).title();
    }

    public static AdminPage parentOf(AdminPage id) {
        return page(id).parent();
    }

    /** 该页所有 SETTING 类按钮指向的设置项（用于覆盖核对）。 */
    public static List<AdminSetting> settingsOf(AdminPage id) {
        java.util.ArrayList<AdminSetting> settings = new java.util.ArrayList<>();
        for (Entry entry : page(id).entries()) {
            if (entry.kind() == Kind.SETTING) {
                settings.add((AdminSetting) entry.target());
            }
        }
        return List.copyOf(settings);
    }
}
