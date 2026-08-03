package com.riichimahjongforge.chinesemahjong;

import com.themahjong.TheMahjongTile;
import com.themahjong.TheMahjongTileSet;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Named Chinese regional presets: rules + deck + match config. */
public enum ChineseRulePreset {

    MASS_MAHJONG(ChineseRules.mass(), 4, 0, 4),
    SICHUAN_BLOOD_BATTLE(ChineseRules.sichuan(), 4, 0, 4),
    DONGBEI_TUI_DAO_HU(ChineseRules.dongbei(), 4, 0, 4),
    GUANGDONG_JI_PING_HU(ChineseRules.guangdong(), 4, 0, 4);

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

    /** 四川血战用 108 张万筒条（无字牌）；其余用 136 张全牌。 */
    public TheMahjongTileSet tileSet() {
        if (this == SICHUAN_BLOOD_BATTLE) {
            return sichuanTileSet();
        }
        return TheMahjongTileSet.standardRiichi(false);
    }

    public ChineseMatch newMatch() {
        return new ChineseMatch(playerCount, startingPoints, roundCount, tileSet(), rules, this);
    }

    private static TheMahjongTileSet sichuanTileSet() {
        List<TheMahjongTile> tiles = new ArrayList<>();
        Map<TheMahjongTile, Integer> copies = new LinkedHashMap<>();
        for (TheMahjongTile.Suit s : new TheMahjongTile.Suit[]{
                TheMahjongTile.Suit.MANZU, TheMahjongTile.Suit.PINZU, TheMahjongTile.Suit.SOUZU}) {
            for (int r = 1; r <= 9; r++) {
                TheMahjongTile t = new TheMahjongTile(s, r, false);
                tiles.add(t);
                copies.put(t, 4);
            }
        }
        return new TheMahjongTileSet(tiles, copies);
    }
}
