package linmumua.doudizhu.ui;

import linmumua.doudizhu.DoudizhuPlugin;
import java.util.UUID;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;

public final class HandInventoryHolder implements InventoryHolder {
    public enum AdminPage {
        HOME,
        DDZ_HOME,
        GLOBAL_HOME,
        DDZ_FURNITURE,
        DDZ_BUTTONS,
        DDZ_CARDS,
        DDZ_LABELS,
        DDZ_TEXT,
        DDZ_SEAT_TEXT,
        DDZ_HITBOX,
        DDZ_AUDIO,
        DDZ_PLAYER_OPTIONS,
        DDZ_BOTS,
        DDZ_AI,
        GLOBAL_ECONOMY,
        GLOBAL_ANIMATION,
        GLOBAL_HIGHLIGHT,
        GLOBAL_AVATARS,
        GLOBAL_STATUS_NAMES
    }

    public enum ViewMode {
        SETTINGS,
        SETTINGS_ACTION_KIND_MENU,
        SETTINGS_SELECTION_SOUND_PICKER,
        SETTINGS_PLAY_ACTION_PICKER,
        ADMIN_PLAY_ACTION_KIND_PICKER,
        ADMIN_SELECTION_SOUND_EDITOR,
        ADMIN_PLAY_ACTION_EDITOR,
        ADMIN_COUNTDOWN_SOUND_EDITOR,
        ADMIN_MODELS
    }

    public enum EditorTarget {
        PLAYER_SELECTION,
        PLAYER_PLAY_ACTION,
        PLAYER_PREVIEW_GLOW,
        PLAYER_SELECTED_GLOW,
        ADMIN_SELECTION_SOUND,
        ADMIN_PLAY_ACTION,
        ADMIN_COUNTDOWN,
        ADMIN_UNREADY_WARNING,
        ADMIN_PLACEMENT_BLOCKED_WARNING,
        ADMIN_CHIP_BALANCE,
        ADMIN_AI_URL,
        ADMIN_AI_KEY,
        ADMIN_AI_MODEL,
        ADMIN_AI_SYSTEM_PROMPT,
        ADMIN_HOVER_GLOW,
        ADMIN_SELECTED_GLOW
    }

    private final String tableName;
    private final UUID viewerId;
    private final ViewMode viewMode;
    private final AdminPage adminPage;
    private final EditorTarget editorTarget;
    private final DoudizhuPlugin.PlayActionKind playActionKind;
    private final int profileIndex;
    private Inventory inventory;

    public HandInventoryHolder(String tableName, UUID viewerId, ViewMode viewMode) {
        this(tableName, viewerId, viewMode, null, null, null, -1);
    }

    public HandInventoryHolder(String tableName, UUID viewerId, ViewMode viewMode, AdminPage adminPage) {
        this(tableName, viewerId, viewMode, adminPage, null, null, -1);
    }

    public HandInventoryHolder(String tableName, UUID viewerId, ViewMode viewMode, EditorTarget editorTarget) {
        this(tableName, viewerId, viewMode, null, editorTarget, null, -1);
    }

    public HandInventoryHolder(String tableName, UUID viewerId, ViewMode viewMode, EditorTarget editorTarget, int profileIndex) {
        this(tableName, viewerId, viewMode, null, editorTarget, null, profileIndex);
    }

    public HandInventoryHolder(String tableName, UUID viewerId, ViewMode viewMode, EditorTarget editorTarget, DoudizhuPlugin.PlayActionKind playActionKind, int profileIndex) {
        this(tableName, viewerId, viewMode, null, editorTarget, playActionKind, profileIndex);
    }

    public HandInventoryHolder(String tableName, UUID viewerId, ViewMode viewMode, AdminPage adminPage, EditorTarget editorTarget, DoudizhuPlugin.PlayActionKind playActionKind, int profileIndex) {
        this.tableName = tableName;
        this.viewerId = viewerId;
        this.viewMode = viewMode;
        this.adminPage = adminPage;
        this.editorTarget = editorTarget;
        this.playActionKind = playActionKind;
        this.profileIndex = profileIndex;
    }

    public String tableName() {
        return tableName;
    }

    public UUID viewerId() {
        return viewerId;
    }

    public ViewMode viewMode() {
        return viewMode;
    }

    public AdminPage adminPage() {
        return adminPage;
    }

    public EditorTarget editorTarget() {
        return editorTarget;
    }

    public DoudizhuPlugin.PlayActionKind playActionKind() {
        return playActionKind;
    }

    public int profileIndex() {
        return profileIndex;
    }

    public void setInventory(Inventory inventory) {
        this.inventory = inventory;
    }

    @Override
    public Inventory getInventory() {
        return inventory;
    }
}

