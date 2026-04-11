package dev.codex.doudizhu.game;

public enum PlayerRole {
    LANDLORD("地主"),
    FARMER("农民");

    private final String displayName;

    PlayerRole(String displayName) {
        this.displayName = displayName;
    }

    public String displayName() {
        return displayName;
    }
}

