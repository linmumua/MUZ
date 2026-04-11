package dev.codex.doudizhu.ui;

import java.util.UUID;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;

public final class HandInventoryHolder implements InventoryHolder {
    public enum AdminPage {
        MODELS,
        TABLE,
        CARDS,
        SEAT,
        HITBOX,
        AUDIO,
        PLAYER_OPTIONS,
        BOTS
    }

    public enum ViewMode {
        SETTINGS,
        SETTINGS_SELECTION_SOUND_PICKER,
        SETTINGS_PLAY_ACTION_PICKER,
        ADMIN_SELECTION_SOUND_EDITOR,
        ADMIN_PLAY_ACTION_EDITOR,
        ADMIN_COUNTDOWN_SOUND_EDITOR,
        ADMIN_MODELS
    }

    public enum EditorTarget {
        PLAYER_SELECTION,
        PLAYER_PLAY_ACTION,
        ADMIN_SELECTION_SOUND,
        ADMIN_PLAY_ACTION,
        ADMIN_COUNTDOWN
    }

    private final String tableName;
    private final UUID viewerId;
    private final ViewMode viewMode;
    private final AdminPage adminPage;
    private final EditorTarget editorTarget;
    private final int profileIndex;
    private Inventory inventory;

    public HandInventoryHolder(String tableName, UUID viewerId, ViewMode viewMode) {
        this(tableName, viewerId, viewMode, null, null, -1);
    }

    public HandInventoryHolder(String tableName, UUID viewerId, ViewMode viewMode, AdminPage adminPage) {
        this(tableName, viewerId, viewMode, adminPage, null, -1);
    }

    public HandInventoryHolder(String tableName, UUID viewerId, ViewMode viewMode, EditorTarget editorTarget) {
        this(tableName, viewerId, viewMode, null, editorTarget, -1);
    }

    public HandInventoryHolder(String tableName, UUID viewerId, ViewMode viewMode, EditorTarget editorTarget, int profileIndex) {
        this(tableName, viewerId, viewMode, null, editorTarget, profileIndex);
    }

    public HandInventoryHolder(String tableName, UUID viewerId, ViewMode viewMode, AdminPage adminPage, EditorTarget editorTarget, int profileIndex) {
        this.tableName = tableName;
        this.viewerId = viewerId;
        this.viewMode = viewMode;
        this.adminPage = adminPage;
        this.editorTarget = editorTarget;
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
