package com.riichimahjongforge.chinesemahjong;

import java.util.List;

/** Lifecycle events broadcast to {@link ChinesePlayerInterface#onEvent}. */
public sealed interface ChineseMatchEvent {

    record RoundStarted() implements ChineseMatchEvent {}

    /** 刮风下雨即时结算等事件的通用载体：deltas 按座位。 */
    record PointsSettled(int seat, int kind, List<Integer> deltas) implements ChineseMatchEvent {}

    record RoundEnded(List<ChineseWinResult> results) implements ChineseMatchEvent {}

    record MatchEnded() implements ChineseMatchEvent {}
}
