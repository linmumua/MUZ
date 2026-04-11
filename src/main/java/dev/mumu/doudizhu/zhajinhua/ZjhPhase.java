package dev.mumu.doudizhu.zhajinhua;

public enum ZjhPhase {
    LOBBY("等待中"),
    PLAYING("游戏中");

    private final String displayName;

    ZjhPhase(String displayName) {
        this.displayName = displayName;
    }

    public String displayName() {
        return displayName;
    }
}

