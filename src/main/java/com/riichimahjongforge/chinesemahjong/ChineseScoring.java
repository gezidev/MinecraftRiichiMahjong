package com.riichimahjongforge.chinesemahjong;

import java.util.List;

/**
 * Chinese point settlement.
 *
 * <p>Win scoring: 荣和 — 出牌者单付；自摸 — 其余三家各付。{@code dealerDouble} 下庄家赢则
 * 每人多付一倍、闲家赢则庄家多付一倍。刮风下雨（杠）的即时结算不入番，直接累进各座位 points。
 */
public final class ChineseScoring {

    private ChineseScoring() {}

    public enum GangKind { MING_GANG, BU_GANG, AN_GANG }

    /** Per-seat deltas for a win. Winner positive; payers negative. */
    public static List<Integer> winDeltas(int points, boolean tsumo, int winnerSeat, int fromSeat,
                                          int playerCount, int dealerSeat, ChineseRules rules) {
        int[] d = new int[playerCount];
        if (tsumo) {
            for (int seat = 0; seat < playerCount; seat++) {
                if (seat == winnerSeat) continue;
                int pay = payFor(points, seat, winnerSeat, dealerSeat, rules);
                d[seat] -= pay;
                d[winnerSeat] += pay;
            }
        } else {
            int pay = payFor(points, fromSeat, winnerSeat, dealerSeat, rules);
            d[fromSeat] -= pay;
            d[winnerSeat] += pay;
        }
        return box(d);
    }

    /**
     * 刮风下雨 — immediate gang settlement. 明杠由出牌者付、补杠/暗杠由其余三家各付（暗杠 ×2）。
     * Sichuan only; other regions leave gangs to the hand's fan.
     */
    public static List<Integer> gangDeltas(GangKind kind, int seat, int fromSeat,
                                           int playerCount, ChineseRules rules) {
        int[] d = new int[playerCount];
        int base = rules.gangBasePoints();
        switch (kind) {
            case MING_GANG -> {
                d[fromSeat] -= base;
                d[seat] += base;
            }
            case BU_GANG, AN_GANG -> {
                int each = (kind == GangKind.AN_GANG) ? base * 2 : base;
                for (int s = 0; s < playerCount; s++) {
                    if (s == seat) continue;
                    d[s] -= each;
                    d[seat] += each;
                }
            }
        }
        return box(d);
    }

    private static int payFor(int points, int payer, int winner, int dealer, ChineseRules rules) {
        if (!rules.dealerDouble()) return points;
        if (payer == dealer || winner == dealer) return points * 2;
        return points;
    }

    private static List<Integer> box(int[] d) {
        Integer[] out = new Integer[d.length];
        for (int i = 0; i < d.length; i++) out[i] = d[i];
        return List.of(out);
    }
}
