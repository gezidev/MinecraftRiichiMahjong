package com.riichimahjongforge.chinesemahjong;

import com.themahjong.TheMahjongTile;
import com.themahjong.TheMahjongTileSet;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;

/** A whole Chinese mahjong match: a fixed number of deals with carried-over points. */
public final class ChineseMatch {

    private final int playerCount;
    private final int startingPoints;
    private final int roundCount;
    private final TheMahjongTileSet tileSet;
    private final ChineseRules rules;
    private final ChineseRulePreset preset;
    private final List<ChineseRoundState> completedRounds = new ArrayList<>();
    private ChineseRoundState currentRound;

    public ChineseMatch(int playerCount, int startingPoints, int roundCount,
                        TheMahjongTileSet tileSet, ChineseRules rules) {
        this(playerCount, startingPoints, roundCount, tileSet, rules, null);
    }

    ChineseMatch(int playerCount, int startingPoints, int roundCount,
                 TheMahjongTileSet tileSet, ChineseRules rules, ChineseRulePreset preset) {
        this.playerCount = playerCount;
        this.startingPoints = startingPoints;
        this.roundCount = roundCount;
        this.tileSet = tileSet;
        this.rules = rules;
        this.preset = preset;
    }

    public int playerCount() { return playerCount; }
    public int startingPoints() { return startingPoints; }
    public int roundCount() { return roundCount; }
    public TheMahjongTileSet tileSet() { return tileSet; }
    public ChineseRules rules() { return rules; }
    public ChineseRulePreset preset() { return preset; }
    public List<ChineseRoundState> completedRounds() { return completedRounds; }
    public ChineseRoundState currentRound() { return currentRound; }

    /** NBT 恢复用：直接置入当前局。 */
    public void restoreRound(ChineseRoundState round) { this.currentRound = round; }

    public void start(Random random) {
        List<TheMahjongTile> wall = tileSet.createShuffledWall(random);
        List<Integer> start = new ArrayList<>();
        for (int i = 0; i < playerCount; i++) start.add(startingPoints);
        currentRound = new ChineseRoundState(playerCount, 0, TheMahjongTile.Wind.EAST, 1, start, wall, rules,
                rollDice(random), rollDice(random));
    }

    private static int rollDice(Random random) { return 1 + random.nextInt(6); }

    /**
     * Archive the ended round and deal the next one. Dealer stays on a win when
     * {@code dealerStaysOnWin} (东北/广东), otherwise rotates. Points carry over.
     * Returns false when the match has no more rounds.
     */
    public boolean advanceRound(Random random) {
        if (currentRound == null) return false;
        completedRounds.add(currentRound);
        if (completedRounds.size() >= roundCount) {
            currentRound = null;
            return false;
        }
        List<Integer> start = new ArrayList<>();
        for (ChinesePlayerState p : currentRound.players()) start.add(p.points());

        boolean dealerWon = currentRound.players().get(currentRound.dealerSeat()).won();
        boolean stays = rules.dealerStaysOnWin() && dealerWon;
        int nextDealer = stays
                ? currentRound.dealerSeat()
                : (currentRound.dealerSeat() + 1) % playerCount;

        int nextHand = currentRound.handNumber() + 1;
        int windIdx = ((nextHand - 1) / playerCount) % TheMahjongTile.Wind.values().length;
        TheMahjongTile.Wind nextWind = TheMahjongTile.Wind.values()[windIdx];

        List<TheMahjongTile> wall = tileSet.createShuffledWall(random);
        currentRound = new ChineseRoundState(playerCount, nextDealer, nextWind, nextHand, start, wall, rules,
                rollDice(random), rollDice(random));
        return true;
    }

    public boolean isEnded() {
        return currentRound == null;
    }
}
