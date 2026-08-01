package com.riichimahjongforge.chinesemahjong;

import com.themahjong.TheMahjongTile;

import java.util.List;

/** Driver-level phase of a Chinese match. */
public sealed interface ChineseMatchPhase {

    record NotStarted() implements ChineseMatchPhase {}

    /** 四川定缺：列未定缺的座位。 */
    record AwaitingQueYiMen(List<Integer> pendingSeats) implements ChineseMatchPhase {}

    record AwaitingDraw(int seat) implements ChineseMatchPhase {}

    record AwaitingDiscard(int seat) implements ChineseMatchPhase {}

    /** 副露窗口：heldTile 是被宣告的牌；kanRob=true 表示抢杠窗口（只能荣和/过）。 */
    record AwaitingClaims(List<Integer> pendingSeats, TheMahjongTile heldTile, boolean kanRob) implements ChineseMatchPhase {}

    record RoundEnded(List<ChineseWinResult> results) implements ChineseMatchPhase {}

    record BetweenRounds() implements ChineseMatchPhase {}

    record MatchEnded() implements ChineseMatchPhase {}
}
