package com.riichimahjongforge.chinesemahjong;

import com.themahjong.TheMahjongMeld;
import com.themahjong.TheMahjongTile;

import java.util.ArrayList;
import java.util.List;

/** Mutable per-seat state for a Chinese round. Read by the renderer; mutated by the driver. */
public final class ChinesePlayerState {

    private int points;
    private final List<TheMahjongTile> currentHand = new ArrayList<>();
    private final List<TheMahjongMeld> melds = new ArrayList<>();
    private final List<TheMahjongTile> discards = new ArrayList<>();
    private boolean won;
    private TheMahjongTile.Suit missingSuit;

    public ChinesePlayerState(int points) {
        this.points = points;
    }

    public int points() { return points; }
    public void addPoints(int delta) { points += delta; }
    public List<TheMahjongTile> currentHand() { return currentHand; }
    public List<TheMahjongMeld> melds() { return melds; }
    public List<TheMahjongTile> discards() { return discards; }
    public boolean won() { return won; }
    public void setWon(boolean w) { won = w; }
    public TheMahjongTile.Suit missingSuit() { return missingSuit; }
    public void setMissingSuit(TheMahjongTile.Suit s) { missingSuit = s; }

    public void draw(TheMahjongTile t) { currentHand.add(t); }

    /** Remove the given tile from hand (by value). Returns false if absent. */
    public boolean removeFromHand(TheMahjongTile t) { return currentHand.remove(t); }

    public void removeTilesFromHand(List<TheMahjongTile> tiles) {
        for (TheMahjongTile t : tiles) currentHand.remove(t);
    }

    public void addMeld(TheMahjongMeld m) { melds.add(m); }

    /** Remove a meld (used for kakan upgrade bookkeeping). */
    public boolean removeMeld(TheMahjongMeld m) { return melds.remove(m); }

    public void discard(TheMahjongTile t) {
        currentHand.remove(t);
        discards.add(t);
    }
}
