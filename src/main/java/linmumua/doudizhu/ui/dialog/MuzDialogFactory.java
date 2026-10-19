package linmumua.doudizhu.ui.dialog;

import io.papermc.paper.dialog.Dialog;
import io.papermc.paper.registry.data.dialog.ActionButton;
import io.papermc.paper.registry.data.dialog.DialogBase;
import io.papermc.paper.registry.data.dialog.action.DialogAction;
import io.papermc.paper.registry.data.dialog.body.DialogBody;
import io.papermc.paper.registry.data.dialog.input.DialogInput;
import io.papermc.paper.registry.data.dialog.input.SingleOptionDialogInput;
import io.papermc.paper.registry.data.dialog.input.TextDialogInput;
import io.papermc.paper.registry.data.dialog.type.DialogType;
import java.util.List;
import net.kyori.adventure.text.Component;
import org.bukkit.inventory.ItemStack;

/**
 * 原生 Dialog 的静态构建 helper。
 *
 * <p>把 Paper 的注册构建器样板（{@code Dialog.create(f -> f.empty().base(..).type(..))}）
 * 收敛到一处，屏实现只拼「标题 + 正文 + 输入 + 按钮」。
 *
 * <p>约定 {@code afterAction} 固定为 {@link DialogBase.DialogAfterAction#NONE}：点击按钮后
 * 是否关闭由回调内显式 {@code ctx.open/push/back/close} 决定，不依赖客户端自动关闭，避免
 * 「回调里重开」与「客户端自动关闭」竞争导致闪烁或残留。
 */
public final class MuzDialogFactory {
    /** 一行的默认列数。 */
    public static final int DEFAULT_COLUMNS = 2;

    private MuzDialogFactory() {
    }

    /**
     * 构建一个多按钮 dialog。
     *
     * @param title 标题
     * @param canCloseWithEscape 是否允许 Esc 关闭
     * @param body 正文（可为空列表）
     * @param inputs 输入控件（可为空列表）
     * @param actions 按钮（可为空列表）
     * @param exitAction 退出按钮（可为 null，表示无）
     * @param columns 按钮列数
     */
    public static Dialog screen(
        Component title,
        boolean canCloseWithEscape,
        List<DialogBody> body,
        List<DialogInput> inputs,
        List<ActionButton> actions,
        ActionButton exitAction,
        int columns
    ) {
        DialogBase base = DialogBase.builder(title == null ? Component.empty() : title)
            .canCloseWithEscape(canCloseWithEscape)
            // 【必须显式 pause(false)：这是实服「管理菜单打不开」的真实根因】
            // DialogBase 的 pause 默认是 true（暂停游戏），而 Paper 有一条硬校验：
            // pause=true 时 after_action 必须能解除暂停（CLOSE / WAIT_FOR_RESPONSE），
            // 否则 build() 直接抛 IllegalArgumentException:
            //   "Dialogs that pause the game must use after_action values that unpause it after user action!"
            // 本插件刻意用 NONE（按钮回调自己决定开/关，不靠客户端 after_action），
            // 所以 pause 必须一起关掉；否则**任何** Dialog 都构建失败，表现为「点了没反应」。
            .pause(false)
            .afterAction(DialogBase.DialogAfterAction.NONE)
            .body(body == null ? List.of() : body)
            .inputs(inputs == null ? List.of() : inputs)
            .build();
        DialogType type = DialogType.multiAction(
            actions == null ? List.of() : actions,
            exitAction,
            columns <= 0 ? DEFAULT_COLUMNS : columns
        );
        return Dialog.create(factory -> factory.empty().base(base).type(type));
    }

    /** 纯文本正文，使用默认宽度。 */
    public static DialogBody body(Component text) {
        return DialogBody.plainMessage(text == null ? Component.empty() : text);
    }

    /** 指定宽度的纯文本正文。 */
    public static DialogBody body(Component text, int width) {
        return DialogBody.plainMessage(text == null ? Component.empty() : text, width);
    }

    /** 物品正文（用于道具槽预览）。 */
    public static DialogBody item(ItemStack stack, int width, int height) {
        return DialogBody.item(stack, null, false, true, width, height);
    }

    /** 退出按钮：无动作，仅关闭。 */
    public static ActionButton exitButton(Component label) {
        return ActionButton.builder(label == null ? Component.text("关闭") : label).build();
    }

    /**
     * 按钮默认宽度。
     *
     * <p>【必须给足：1 会让按钮渲染成一条竖线】Paper 的 {@code ActionButton} 宽度以**对话框宽度的
     * 1024 分之一**为单位（1..1024）。旧代码全部传 1，实服表现为「正文和关闭按钮都在，按钮区只剩
     * 一条竖线」——按钮其实存在，只是宽度接近 0，玩家点不到也看不见文字。取 150 约等于 15% 宽，
     * 与底部退出按钮视觉量级一致。
     */
    public static final int DEFAULT_BUTTON_WIDTH = 150;

    /** 原始按钮：直接给定 DialogAction（一般用 {@code ctx.action(...)} 而不是这个）。 */
    public static ActionButton button(Component label, Component tooltip, int width, DialogAction action) {
        return ActionButton.create(
            label == null ? Component.empty() : label,
            tooltip,
            width <= 0 ? DEFAULT_BUTTON_WIDTH : width,
            action
        );
    }

    /**
     * 数值滑块。
     *
     * <p>用于音量、音高这类「连续可调、需要边调边听」的参数：原生 Dialog 的
     * {@code numberRange} 客户端就是滑块，比让人手写 `0.45` 更不容易填错，
     * 也不用管理员记格式。
     *
     * <p>{@code step} 与存储精度保持一致：配置里的音效规格统一格式化为一位小数
     * （见 {@code DoudizhuPlugin.normalizeSoundSpec}），因此滑块步长取 0.1，
     * 避免「界面上显示 0.45、存下来变成 0.5」的错觉。
     *
     * @param key     输入键名，回调里用同一个 key 读取
     * @param label   显示在滑块上方的标题
     * @param min     下界
     * @param max     上界
     * @param step    步长；非正数时退化为 0.1
     * @param initial 初值，超出范围时钳到区间内
     */
    public static DialogInput numberRange(
        String key,
        Component label,
        float min,
        float max,
        float step,
        float initial
    ) {
        float low = Math.min(min, max);
        float high = Math.max(min, max);
        float clamped = Math.clamp(initial, low, high);
        return DialogInput.numberRange(key, label == null ? Component.empty() : label, low, high)
            .step(step <= 0.0f ? 0.1f : step)
            .initial(clamped)
            .labelFormat("%.1f")
            .build();
    }

    /** 单行文本输入。 */
    public static DialogInput textInput(String key, Component label, String initial, int maxLength) {
        return DialogInput.text(key, label)
            .initial(initial == null ? "" : initial)
            .maxLength(maxLength <= 0 ? 64 : maxLength)
            .build();
    }

    /** 多行文本输入（用于 AI 人设词）。 */
    public static DialogInput multilineTextInput(
        String key,
        Component label,
        String initial,
        int maxLength,
        int maxLines
    ) {
        return DialogInput.text(key, label)
            .initial(initial == null ? "" : initial)
            .maxLength(maxLength <= 0 ? 256 : maxLength)
            .multiline(TextDialogInput.MultilineOptions.create(
                maxLines <= 0 ? 4 : maxLines,
                null
            ))
            .build();
    }

    /** 数值范围输入（滑块）。 */
    public static DialogInput numberInput(
        String key,
        Component label,
        float start,
        float end,
        Float initial,
        Float step
    ) {
        return DialogInput.numberRange(key, label, start, end)
            .initial(initial)
            .step(step)
            .build();
    }

    /** 布尔输入（开关）。 */
    public static DialogInput boolInput(String key, Component label, boolean initial) {
        // 注意：DialogInput.bool(key, label, initial, onTrue, onFalse) 直接返回 BooleanDialogInput，
        // 没有 build()（它不是 Builder）；只有 Builder 形式才需要 build()。
        return DialogInput.bool(key, label, initial, "开启", "关闭");
    }

    /** 单选输入。 */
    public static DialogInput optionInput(
        String key,
        Component label,
        List<SingleOptionDialogInput.OptionEntry> entries
    ) {
        return DialogInput.singleOption(key, label, entries == null ? List.of() : entries).build();
    }
}
