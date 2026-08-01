package com.riichimahjongforge.chinesemahjong;

import com.themahjong.TheMahjongTile;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * Heuristic bot for 大众麻将. Smarter than the stress-test bots:
 * <ul>
 *   <li>Discards by a cheap hand-efficiency score (triplets &gt; pairs &gt; taatsu
 *       &gt; isolated tiles; isolated honours/terminals discarded first) instead
 *       of blind tsumogiri.</li>
 *   <li>Always wins / kans; claims Pon/Daiminkan; only Chi when the hand is
 *       already open (so a concealed 门前清/七对 chance isn't thrown away).</li>
 * </ul>
 * Deterministic (no RNG) so replays stay reproducible.
 */
public class ChineseHeuristicBot extends ChineseAbstractBot {

    @Override
    protected Optional<ChinesePlayerAction> pick(ChineseDecisionRequest request) {
        if (request.phase() instanceof ChineseMatchPhase.AwaitingQueYiMen) {
            return chooseMissingSuit(request);
        }
        List<ChinesePlayerAction> legal = request.legalActions();
        if (legal.isEmpty()) return Optional.empty();

        // 1. Always take a win.
        for (ChinesePlayerAction a : legal) {
            if (a instanceof ChinesePlayerAction.Tsumo || a instanceof ChinesePlayerAction.Ron) return Optional.of(a);
        }
        // 2. Kans are always good.
        for (ChinesePlayerAction a : legal) {
            if (a instanceof ChinesePlayerAction.Ankan || a instanceof ChinesePlayerAction.Kakan) return Optional.of(a);
        }
        // 3. Claim window: Pon/Daiminkan always; Chi only when the hand is open.
        boolean hasOpenMeld = !request.round().players().get(request.seat()).melds().isEmpty();
        for (ChinesePlayerAction a : legal) {
            if (a instanceof ChinesePlayerAction.Daiminkan) return Optional.of(a);
        }
        for (ChinesePlayerAction a : legal) {
            if (a instanceof ChinesePlayerAction.Pon) return Optional.of(a);
        }
        for (ChinesePlayerAction a : legal) {
            if (a instanceof ChinesePlayerAction.Chi && hasOpenMeld) return Optional.of(a);
        }
        // 4. Draw.
        for (ChinesePlayerAction a : legal) {
            if (a instanceof ChinesePlayerAction.Draw) return Optional.of(a);
        }
        // 5. Discard by efficiency.
        Optional<ChinesePlayerAction> disc = pickSmartDiscard(request);
        if (disc.isPresent()) return disc;
        // 6. Pass as a last resort.
        for (ChinesePlayerAction a : legal) {
            if (a instanceof ChinesePlayerAction.Pass) return Optional.of(a);
        }
        return Optional.empty();
    }

    /** Discard the tile whose removal leaves the highest-scoring hand. Ties
     *  break toward the newest tile in the hand (tsumogiri tendency). */
    protected Optional<ChinesePlayerAction> pickSmartDiscard(ChineseDecisionRequest request) {
        List<TheMahjongTile> hand = request.round().players().get(request.seat()).currentHand();
        List<TheMahjongTile> candidates = new ArrayList<>();
        for (ChinesePlayerAction a : request.legalActions()) {
            if (a instanceof ChinesePlayerAction.Discard d) {
                boolean dup = false;
                for (TheMahjongTile c : candidates) if (c.equals(d.tile())) { dup = true; break; }
                if (!dup) candidates.add(d.tile());
            }
        }
        TheMahjongTile best = null;
        double bestScore = Double.NEGATIVE_INFINITY;
        int bestLastIndex = -1;
        for (TheMahjongTile t : candidates) {
            double score = handScore(removeOne(hand, t));
            int lastIndex = hand.lastIndexOf(t);
            if (score > bestScore + 1e-9
                    || (Math.abs(score - bestScore) <= 1e-9 && lastIndex > bestLastIndex)) {
                best = t;
                bestScore = score;
                bestLastIndex = lastIndex;
            }
        }
        if (best == null) return Optional.empty();
        return Optional.of(new ChinesePlayerAction.Discard(best));
    }

    private static List<TheMahjongTile> removeOne(List<TheMahjongTile> hand, TheMahjongTile t) {
        List<TheMahjongTile> out = new ArrayList<>(hand);
        for (int i = 0; i < out.size(); i++) {
            if (out.get(i).equals(t)) { out.remove(i); break; }
        }
        return out;
    }

    /** Cheap O(n) hand-efficiency score: complete sets &gt; pairs &gt; taatsu,
     *  isolated honours/terminals penalised so they get discarded first. */
    private static double handScore(List<TheMahjongTile> hand) {
        int[][] counts = new int[3][10]; // suits 0..2, rank 1..9
        int[] honorCounts = new int[7];  // winds E/S/W/N (0-3) + dragons (4-6)
        for (TheMahjongTile t : hand) {
            if (t.honor()) {
                // WIND(3) → slot 0..3, DRAGON(4) → slot 4..6 (7 distinct honours)
                honorCounts[(t.suit().ordinal() - 3) * 4 + (t.rank() - 1)]++;
            } else {
                counts[t.suit().ordinal()][t.rank()]++;
            }
        }
        double score = 0;
        for (int s = 0; s < 3; s++) {
            int[] c = counts[s];
            for (int r = 1; r <= 9; r++) {
                if (c[r] >= 3) score += 3;           // triplet
                else if (c[r] == 2) score += 2;      // pair
                else if (c[r] == 1) score += 0.2;    // single, still usable
            }
            for (int r = 1; r <= 8; r++) {
                if (c[r] >= 1 && c[r + 1] >= 1 && c[r] < 3 && c[r + 1] < 3) score += 1; // ryanmen-ish
            }
            for (int r = 1; r <= 7; r++) {
                if (c[r] >= 1 && c[r + 2] >= 1) score += 0.5; // kanchan-ish
            }
            if (c[1] == 1 && c[2] == 0) score -= 0.5; // isolated 1
            if (c[9] == 1 && c[8] == 0) score -= 0.5; // isolated 9
        }
        for (int h = 0; h < honorCounts.length; h++) {
            int n = honorCounts[h];
            if (n >= 3) score += 3;
            else if (n == 2) score += 2;
            else if (n == 1) score -= 1; // isolated honour is a dead tile
        }
        return score;
    }
}
