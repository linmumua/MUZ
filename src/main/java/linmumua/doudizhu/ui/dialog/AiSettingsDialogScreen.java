package linmumua.doudizhu.ui.dialog;

import io.papermc.paper.dialog.Dialog;
import io.papermc.paper.registry.data.dialog.ActionButton;
import io.papermc.paper.registry.data.dialog.body.DialogBody;
import io.papermc.paper.registry.data.dialog.input.DialogInput;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import linmumua.doudizhu.DoudizhuPlugin;
import linmumua.doudizhu.ui.MuzTheme;

/**
 * AI 配置文本输入 Dialog（对应旧 {@code HandGuiService.beginCustomInput} 的
 * {@code ADMIN_AI_URL / ADMIN_AI_KEY / ADMIN_AI_MODEL / ADMIN_AI_SYSTEM_PROMPT} 四个目标）。
 *
 * <p>旧的 DDZ_AI 管理页由 {@code dialog-admin} 的 {@code AdminDialogScreen} 渲染；那里的
 * 「DeepSeek 链接 / 密钥 / 模型 / 全局人设词」按钮只做导航，落到本屏。主屏上的 AI 状态摘要来自
 * {@link DoudizhuPlugin#aiStatusSummary()}。
 *
 * <p>【权限】四个字段都属于管理配置，因此 {@link #permission()} 一律返回 {@code muz.admin}；
 * core 会在「打开前」与「每次回调内」复核，本类无需重复判定。
 *
 * <p>【密钥不回填】密钥输入框**不**回填真实 Key，初值留空，正文只显示掩码
 * （{@link DoudizhuPlugin#aiApiKeyMasked()}）。空输入按「没有改动」处理，避免误清空。
 *
 * <p>【owner lane】{@code build} 与回调均由 core 在 player lane 内调用，提示走
 * {@code ctx.message/ctx.error}；不直接调用裸 Player API。
 */
public final class AiSettingsDialogScreen implements MuzDialogScreen {

    /** 四个可编辑的 AI 字段。 */
    public enum AiField {
        URL("admin.ai.url", "DeepSeek 链接", "例如 https://api.deepseek.com"),
        KEY("admin.ai.key", "DeepSeek 密钥", "直接粘贴 API Key；留空表示不修改。"),
        MODEL("admin.ai.model", "DeepSeek 模型", "例如 deepseek-chat"),
        SYSTEM_PROMPT("admin.ai.system_prompt", "全局人设词", "建议强调稳健牌风、炸弹保留和严格输出格式。");

        private final String key;
        private final String label;
        private final String hint;

        AiField(String key, String label, String hint) {
            this.key = key;
            this.label = label;
            this.hint = hint;
        }

        public String key() {
            return key;
        }

        public String label() {
            return label;
        }

        public String hint() {
            return hint;
        }
    }

    /** 人设词屏允许的最大长度 / 行数。 */
    private static final int PROMPT_MAX_LENGTH = 4096;
    private static final int PROMPT_MAX_LINES = 12;

    private final DoudizhuPlugin plugin;
    private final AiField field;

    private AiSettingsDialogScreen(DoudizhuPlugin plugin, AiField field) {
        this.plugin = Objects.requireNonNull(plugin, "plugin");
        this.field = Objects.requireNonNull(field, "field");
    }

    public static AiSettingsDialogScreen forField(DoudizhuPlugin plugin, AiField field) {
        return new AiSettingsDialogScreen(plugin, field);
    }

    /**
     * 管理侧导航入口：按字符串键取字段屏（{@code URL} / {@code KEY} / {@code MODEL} / {@code SYSTEM_PROMPT}，
     * 大小写不敏感）。未知键直接抛 {@link IllegalArgumentException}，不静默回退到某个字段——否则管理员
     * 点「模型」却改了「链接」会非常难查。
     */
    public static AiSettingsDialogScreen field(DoudizhuPlugin plugin, String key) {
        String normalized = key == null ? "" : key.trim().toUpperCase(java.util.Locale.ROOT);
        for (AiField candidate : AiField.values()) {
            if (candidate.name().equals(normalized)) {
                return new AiSettingsDialogScreen(plugin, candidate);
            }
        }
        throw new IllegalArgumentException("未知的 AI 字段键：" + key);
    }

    @Override
    public String screenKey() {
        return field.key();
    }

    @Override
    public String permission() {
        return "muz.admin";
    }

    @Override
    public Dialog build(MuzDialogContext ctx) {
        List<DialogBody> body = new ArrayList<>();
        body.add(MuzDialogFactory.body(MuzTheme.accent(field.label())));
        body.add(MuzDialogFactory.body(MuzTheme.muted(field.hint())));
        body.add(MuzDialogFactory.body(MuzTheme.muted("当前 · " + currentDisplay())));
        if (field == AiField.SYSTEM_PROMPT) {
            for (String previewLine : previewLines()) {
                body.add(MuzDialogFactory.body(MuzTheme.muted(previewLine)));
            }
        }
        if (field == AiField.URL || field == AiField.MODEL) {
            body.add(MuzDialogFactory.body(MuzTheme.muted("AI 状态 · " + plugin.aiStatusSummary())));
        }

        List<DialogInput> inputs = new ArrayList<>();
        if (field == AiField.SYSTEM_PROMPT) {
            inputs.add(MuzDialogFactory.multilineTextInput(
                "value",
                PlayerSettingsDialogScreen.noItalic(MuzTheme.accent(field.label())),
                plugin.aiSystemPrompt(),
                PROMPT_MAX_LENGTH,
                PROMPT_MAX_LINES
            ));
        } else {
            inputs.add(MuzDialogFactory.textInput(
                "value",
                PlayerSettingsDialogScreen.noItalic(MuzTheme.accent(field.label())),
                initialText(),
                256
            ));
        }

        List<ActionButton> actions = new ArrayList<>();
        actions.add(ctx.action(PlayerSettingsDialogScreen.noItalic(MuzTheme.success("保存")), view -> {
            String raw = ctx.text(view, "value");
            if (raw == null) {
                raw = "";
            }
            // 密钥屏：留空 / cancel 视为「不修改」，避免把密钥误清成空。
            if (field == AiField.KEY && DialogInputParsers.isCancel(raw)) {
                ctx.error("没有改动，密钥保持原样。");
                return;
            }
            if (field != AiField.KEY && DialogInputParsers.isCancel(raw)) {
                ctx.error("输入为空，没有改动。");
                return;
            }
            try {
                apply(raw);
            } catch (IllegalArgumentException exception) {
                ctx.error(exception.getMessage() == null ? "这个值用不了。" : exception.getMessage());
                return;
            }
            ctx.message(MuzTheme.success(successText()));
            ctx.back();
        }));
        actions.add(ctx.action(PlayerSettingsDialogScreen.noItalic(MuzTheme.muted("返回")), ctx::back));

        ActionButton exit = ctx.exit(PlayerSettingsDialogScreen.noItalic(MuzTheme.muted("关闭")));
        return MuzDialogFactory.screen(
            PlayerSettingsDialogScreen.noItalic(MuzTheme.accent(field.label())),
            true,
            body,
            inputs,
            actions,
            exit,
            2
        );
    }

    /** 正文里的「当前值」展示：密钥只显示掩码。 */
    private String currentDisplay() {
        return switch (field) {
            case URL -> plugin.aiBaseUrl();
            case KEY -> plugin.aiApiKeyMasked();
            case MODEL -> plugin.aiModelName();
            case SYSTEM_PROMPT -> "已配置 " + plugin.aiSystemPromptPreviewLines().size() + " 行预览";
        };
    }

    /** 输入框初值：密钥留空（不回填明文），其余回填当前值方便编辑。 */
    private String initialText() {
        return switch (field) {
            case URL -> plugin.aiBaseUrl();
            case KEY -> "";
            case MODEL -> plugin.aiModelName();
            case SYSTEM_PROMPT -> "";
        };
    }

    private List<String> previewLines() {
        List<String> lines = plugin.aiSystemPromptPreviewLines();
        return lines.size() <= 4 ? lines : lines.subList(0, 4);
    }

    private void apply(String raw) {
        switch (field) {
            case URL -> plugin.setAiBaseUrl(raw);
            case KEY -> plugin.setAiApiKey(raw);
            case MODEL -> plugin.setAiModelName(raw);
            case SYSTEM_PROMPT -> plugin.setAiSystemPrompt(raw);
        }
    }

    private String successText() {
        return switch (field) {
            case URL -> "DeepSeek 链接已更新";
            case KEY -> "DeepSeek 密钥已更新";
            case MODEL -> "DeepSeek 模型已更新";
            case SYSTEM_PROMPT -> "全局人设词已更新";
        };
    }
}
