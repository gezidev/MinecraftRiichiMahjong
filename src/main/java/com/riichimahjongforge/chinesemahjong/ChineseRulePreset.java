package com.riichimahjongforge.chinesemahjong;

import com.themahjong.TheMahjongTileSet;

/** Named Chinese mahjong preset: rules + deck + match config. 大众麻将（136 全牌）。 */
public enum ChineseRulePreset {

    MASS_MAHJONG(ChineseRules.mass(), 4, 0, 4);

    private final ChineseRules rules;
    private final int playerCount;
    private final int startingPoints;
    private final int roundCount;

    ChineseRulePreset(ChineseRules rules, int playerCount, int startingPoints, int roundCount) {
        this.rules = rules;
        this.playerCount = playerCount;
        this.startingPoints = startingPoints;
        this.roundCount = roundCount;
    }

    public ChineseRules rules() { return rules; }
    public int playerCount() { return playerCount; }
    public int startingPoints() { return startingPoints; }
    public int roundCount() { return roundCount; }

    /** 大众麻将用 136 张全牌（万筒条 + 东南西北中发白，无赤宝）。 */
    public TheMahjongTileSet tileSet() {
        return TheMahjongTileSet.standardRiichi(false);
    }

    public ChineseMatch newMatch() {
        return new ChineseMatch(playerCount, startingPoints, roundCount, tileSet(), rules, this);
    }
}
