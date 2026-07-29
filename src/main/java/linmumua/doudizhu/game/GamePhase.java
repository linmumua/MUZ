package linmumua.doudizhu.game;

public enum GamePhase {
    LOBBY("等待中"),
    BIDDING("叫分中"),
    DOUBLING("加倍中"),
    PLAYING("游戏中");

    private final String displayName;

    GamePhase(String displayName) {
        this.displayName = displayName;
    }

    public String displayName() {
        return displayName;
    }
}


