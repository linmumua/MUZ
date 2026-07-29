package linmumua.doudizhu.placeholder;

import org.bukkit.OfflinePlayer;
import org.jetbrains.annotations.Nullable;

public final class MuzHeadPlaceholderFormat {
    private static final String DEFAULT_HEAD_TAG = "<head:MHF_Steve>";
    private static final String SIZE_MARKER = "_size_";

    private MuzHeadPlaceholderFormat() {
    }

    public static @Nullable HeadRequest parse(String params, @Nullable OfflinePlayer viewer) {
        if (params == null) {
            return null;
        }
        String trimmed = params.trim();
        if (trimmed.isEmpty()) {
            return null;
        }

        Integer size = null;
        String target = trimmed;

        int explicitSizeIndex = lastIndexOfIgnoreCase(trimmed, SIZE_MARKER);
        if (explicitSizeIndex > 0) {
            Integer parsed = parsePositiveInteger(trimmed.substring(explicitSizeIndex + SIZE_MARKER.length()));
            if (parsed != null) {
                size = parsed;
                target = trimmed.substring(0, explicitSizeIndex);
            }
        }

        String normalizedTarget = normalizeTargetValue(target, viewer);
        if (normalizedTarget == null || normalizedTarget.isBlank()) {
            return null;
        }
        return new HeadRequest(normalizedTarget, size);
    }

    public static String buildTag(@Nullable HeadRequest request) {
        if (request == null || request.playerName().isBlank()) {
            return DEFAULT_HEAD_TAG;
        }
        return "<head:" + request.playerName() + ">";
    }

    public static String buildPlaceholder(String playerName, @Nullable Integer size) {
        return size == null ? "%muz_" + playerName + "%" : "%muz_" + playerName + "_size_" + size + "%";
    }

    public static @Nullable String normalizeTargetValue(String target, @Nullable OfflinePlayer viewer) {
        if (target == null) {
            return null;
        }
        String trimmed = target.trim();
        if (trimmed.isEmpty()) {
            return null;
        }
        if (trimmed.equalsIgnoreCase("self") || trimmed.equalsIgnoreCase("viewer") || trimmed.equalsIgnoreCase("me")) {
            return viewer == null ? null : viewer.getName();
        }
        return trimmed;
    }

    private static @Nullable Integer parsePositiveInteger(String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        for (int index = 0; index < raw.length(); index++) {
            if (!Character.isDigit(raw.charAt(index))) {
                return null;
            }
        }
        try {
            int value = Integer.parseInt(raw);
            return value > 0 ? value : null;
        } catch (NumberFormatException ignored) {
            return null;
        }
    }

    private static int lastIndexOfIgnoreCase(String input, String search) {
        return input.toLowerCase(java.util.Locale.ROOT).lastIndexOf(search.toLowerCase(java.util.Locale.ROOT));
    }

    public record HeadRequest(String playerName, @Nullable Integer size) {
    }
}
