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

    /** 原始按钮：直接给定 DialogAction（一般用 {@code ctx.action(...)} 而不是这个）。 */
    public static ActionButton button(Component label, Component tooltip, int width, DialogAction action) {
        return ActionButton.create(
            label == null ? Component.empty() : label,
            tooltip,
            width <= 0 ? 1 : width,
            action
        );
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
