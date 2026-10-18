package linmumua.doudizhu.ui.dialog;

import io.papermc.paper.dialog.Dialog;
import io.papermc.paper.registry.data.dialog.ActionButton;
import io.papermc.paper.registry.data.dialog.body.DialogBody;
import io.papermc.paper.registry.data.dialog.input.DialogInput;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import java.util.function.BiConsumer;
import java.util.function.Consumer;
import java.util.function.Function;
import linmumua.doudizhu.ui.MuzTheme;
import linmumua.doudizhu.ui.VirtualGadgetBar;
import net.kyori.adventure.text.Component;
import org.bukkit.inventory.ItemStack;

/**
 * 道具箱编辑屏（原生 Dialog 版）。
 *
 * <p>【为什么用 Dialog 而不是 Inventory】旧九格箱子在一个 Bukkit Inventory 里放玩家虚拟道具快照，
 * 必须靠点击/Shift 点击/拖拽事件来回搬运，既容易被拖放绕过，也无法与新版菜单统一。
 * 本屏只显示八槽只读预览 + 按钮：添加（复制主手/背包槽物品的单件快照）、移除、选中确认、语音入口。
 * 「不能 Inventory 界面、不能靠拖放」是产品要求：这里完全没有任何 Inventory 交互。
 *
 * <p>【数据与持久化边界】八槽持久化结构仍是 {@code VirtualGadgetBar}（0..7 + 第 8 格语音入口），
 * 本屏不持有状态、不改写结构。写盘一律交给 {@link GadgetBoxPersistence}（异步写 + 回 player lane 刷新），
 * 因此按钮回调里**没有**文件 IO；添加来源物品时只复制单件快照，**不消耗**玩家真实物品。
 *
 * <p>【选择/语音业务】真正的「选中某真实槽 → 重画道具栏 HUD」与「打开桌内语音面板」属于对局侧业务，
 * 由装配方经 {@code selectAction}/{@code voiceAction} 注入（复用既有 TableGadgetBarHudService 的
 * {@code select(UUID,int)} 与 {@code openVoicePanel(Player)}），本屏不复制那套逻辑、也不 import 对局类。
 *
 * <p>编辑规则（空槽/满槽/重复/稀疏/选中归一）全部收口在 {@link GadgetBoxEditor} 纯逻辑里，便于单测。
 */
public final class GadgetBoxDialogScreen implements MuzDialogScreen {

    /** 会话审计用稳定标识。 */
    public static final String SCREEN_KEY = "gadget_box";

    /** 物品预览块的宽高（Dialog item body 的列/行单位，仅影响展示，不影响数据）。 */
    private static final int SLOT_BODY_WIDTH = 16;
    private static final int SLOT_BODY_HEIGHT = 16;

    /** 背包槽位输入范围：只开放主背包 0..35，不含护甲/副手/光标。 */
    private static final int BAG_SLOT_MIN = 0;
    private static final int BAG_SLOT_MAX = 35;

    private final Function<UUID, VirtualGadgetBar> barSource;
    private final GadgetBoxPersistence persistence;
    private final BiConsumer<UUID, Integer> selectAction;
    private final Consumer<UUID> voiceAction;

    /**
     * @param barSource 打开/重画时读取该玩家当前八槽快照（player lane 内执行，禁止文件 IO）。
     *                  实现方优先给已加载快照；返回 null 时本屏按 {@link VirtualGadgetBar#defaults()} 兜底，
     *                  与道具栏 HUD「数据缺失只显示默认三件」同口径，不让面板因缺数据打不开。
     * @param persistence 异步持久化 + 回 player lane 刷新（刷新道具预览与出牌 HUD 第四行）
     * @param selectAction 选中确认：把真实槽位（0..7）设为当前选中并重画道具栏 HUD
     * @param voiceAction 语音入口：在合法 lane 打开桌内语音面板
     */
    public GadgetBoxDialogScreen(
        Function<UUID, VirtualGadgetBar> barSource,
        GadgetBoxPersistence persistence,
        BiConsumer<UUID, Integer> selectAction,
        Consumer<UUID> voiceAction
    ) {
        this.barSource = Objects.requireNonNull(barSource, "barSource");
        this.persistence = Objects.requireNonNull(persistence, "persistence");
        this.selectAction = Objects.requireNonNull(selectAction, "selectAction");
        this.voiceAction = Objects.requireNonNull(voiceAction, "voiceAction");
    }

    @Override
    public String screenKey() {
        return SCREEN_KEY;
    }

    @Override
    public Dialog build(MuzDialogContext ctx) {
        UUID playerId = ctx.playerId();
        VirtualGadgetBar bar = currentBar(playerId);
        int mask = GadgetBoxEditor.occupiedMask(bar);

        List<DialogBody> body = new ArrayList<>();
        body.add(MuzDialogFactory.body(MuzTheme.accent(
            "道具箱 · 已用 " + GadgetBoxEditor.occupiedCount(mask) + "/" + VirtualGadgetBar.SLOT_COUNT + " 格")));
        body.add(MuzDialogFactory.body(MuzTheme.muted(
            "添加只复制单件快照，不消耗你的真实物品；两个槽可以放同一种道具。")));
        if (GadgetBoxEditor.isFull(mask)) {
            // 满槽提示：按钮里也有拒绝，但正文先行说明，避免玩家以为是显示 bug。
            body.add(MuzDialogFactory.body(MuzTheme.warning("八格已满：请先移除一格，再添加新道具。")));
        }

        List<ActionButton> actions = new ArrayList<>();
        for (int slot = 0; slot < VirtualGadgetBar.SLOT_COUNT; slot++) {
            ItemStack item = bar.slot(slot);
            if (item == null) {
                continue;
            }
            // 重复道具按「真实槽位」保留各自的按钮，不按物品类型合并。
            // 按钮标签只用材质名（不含物品自定义显示名）：显示名可能带 § 颜色码或尖括号，
            // 直接塞进带渐变主题的标签会渲染成乱码；物品本体由上面的 item 正文块按原样展示。
            final int realSlot = slot;
            body.add(MuzDialogFactory.item(item, SLOT_BODY_WIDTH, SLOT_BODY_HEIGHT));
            actions.add(ctx.action(MuzTheme.accent("选中 " + (slot + 1) + " · " + item.getType().name()), () -> {
                selectAction.accept(playerId, realSlot);
                ctx.close();
            }));
            actions.add(ctx.action(MuzTheme.danger("移除 " + (slot + 1)), () -> removeAt(ctx, playerId, realSlot)));
        }

        actions.add(ctx.action(MuzTheme.success("添加主手物品"), () -> addFrom(
            ctx, playerId, ctx.player().getInventory().getItemInMainHand())));

        List<DialogInput> inputs = new ArrayList<>();
        inputs.add(MuzDialogFactory.numberInput(
            "bag.slot",
            Component.text("背包槽位（0-" + BAG_SLOT_MAX + "）"),
            BAG_SLOT_MIN,
            BAG_SLOT_MAX,
            (float) BAG_SLOT_MIN,
            1.0f
        ));
        actions.add(ctx.action(MuzTheme.accent("添加背包槽位"), view -> {
            Float raw = ctx.number(view, "bag.slot");
            int bagSlot = raw == null ? -1 : Math.round(raw);
            if (bagSlot < BAG_SLOT_MIN || bagSlot > BAG_SLOT_MAX) {
                ctx.error("背包槽位必须在 " + BAG_SLOT_MIN + "-" + BAG_SLOT_MAX + "。");
                return;
            }
            addFrom(ctx, playerId, ctx.player().getInventory().getItem(bagSlot));
        }));

        actions.add(ctx.action(MuzTheme.warm("语音面板"), () -> {
            // 先关编辑屏，再由装配方在合法 lane 打开桌内语音面板（语音是 Display 实体面板，不是背包界面）。
            ctx.close();
            voiceAction.accept(playerId);
        }));

        return MuzDialogFactory.screen(
            MuzTheme.accent("道具箱"),
            true,
            body,
            inputs,
            actions,
            ctx.exit(MuzTheme.muted("关闭")),
            MuzDialogFactory.DEFAULT_COLUMNS
        );
    }

    /** 添加：复制来源物品的单件快照进第一个空槽；来源为空/满槽时明确提示，不静默。 */
    private void addFrom(MuzDialogContext ctx, UUID playerId, ItemStack source) {
        GadgetBoxEditor.Edit edit = GadgetBoxEditor.copyInto(currentBar(playerId), source);
        switch (edit.outcome()) {
            case NO_SOURCE -> ctx.error("这个位置没有可复制的物品。");
            case FULL -> ctx.error("八格已满，请先移除一格再添加。");
            case COPIED -> save(ctx, playerId, edit.bar());
            default -> { }
        }
    }

    /** 移除：按真实槽位移除并左移紧凑；槽已空/越界时保持原样。 */
    private void removeAt(MuzDialogContext ctx, UUID playerId, int slot) {
        GadgetBoxEditor.Edit edit = GadgetBoxEditor.removeSlot(currentBar(playerId), slot);
        if (edit.outcome() == GadgetBoxEditor.Outcome.REMOVED) {
            save(ctx, playerId, edit.bar());
        }
    }

    /**
     * 交给持久化接缝：异步写盘，写完后在 player lane 重开本屏（重建即拿到最新快照）。
     * 这里刻意不做任何 IO，也不在保存前改动内存快照。
     */
    private void save(MuzDialogContext ctx, UUID playerId, VirtualGadgetBar next) {
        persistence.persist(playerId, next, () -> ctx.open(this));
    }

    private VirtualGadgetBar currentBar(UUID playerId) {
        VirtualGadgetBar bar = barSource.apply(playerId);
        // 与 TableGadgetBarHudService 同口径：数据缺失只兜底默认三件，不让面板整体打不开。
        return bar == null ? VirtualGadgetBar.defaults() : bar;
    }
}
