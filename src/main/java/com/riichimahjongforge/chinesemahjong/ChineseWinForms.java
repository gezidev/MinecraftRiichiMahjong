package com.riichimahjongforge.chinesemahjong;

import com.themahjong.TheMahjongMeld;
import com.themahjong.TheMahjongTile;
import com.themahjong.yaku.HandShape;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Chinese win-shape detection. Reuses {@link HandShape} for Standard / Chitoitsu /
 * Kokushimusou; adds a count-based check for 龙七对 (one quad + five pairs), which
 * {@code HandShape} cannot express.
 */
public final class ChineseWinForms {

    private ChineseWinForms() {}

    /**
     * True when {@code concealed} (including the winning tile) + {@code melds} form a
     * legal Chinese winning hand under {@code rules}.
     * {@code concealed.size()} must be {@code (4 - melds.size())*3 + 2}.
     */
    public static boolean isWinning(List<TheMahjongTile> concealed, List<TheMahjongMeld> melds, ChineseRules rules) {
        for (HandShape h : HandShape.decompose(concealed, melds)) {
            if (h instanceof HandShape.Standard) return true;
            if (rules.allowChitoitsu() && h instanceof HandShape.Chitoitsu) return true;
            if (rules.allowKokushi() && h instanceof HandShape.Kokushimusou) return true;
        }
        if (rules.allowLongQiDui() && melds.isEmpty()) {
            return isLongQiDui(concealed);
        }
        return false;
    }

    /** 七对 — exactly 7 distinct pairs. */
    public static boolean isQiDui(List<TheMahjongTile> tiles) {
        if (tiles.size() != 14) return false;
        Map<Long, Integer> c = counts(tiles);
        return c.size() == 7 && c.values().stream().allMatch(v -> v == 2);
    }

    /** 龙七对 — one quad (four of a kind) plus five pairs: 4+5×2. */
    public static boolean isLongQiDui(List<TheMahjongTile> tiles) {
        if (tiles.size() != 14) return false;
        Map<Long, Integer> c = counts(tiles);
        if (c.size() != 6) return false;
        int quads = 0;
        for (int v : c.values()) {
            if (v == 4) quads++;
            else if (v != 2) return false;
        }
        return quads == 1;
    }

    /**
     * Tenpai (听牌) detection: every tile kind that, when added to {@code hand},
     * completes the hand. Used for 查大叫 and helpful hints. Probes the full tile
     * universe (number suits + honors); candidates outside the deck simply never match.
     */
    public static List<TheMahjongTile> winningTiles(List<TheMahjongTile> hand, List<TheMahjongMeld> melds, ChineseRules rules) {
        List<TheMahjongTile> out = new ArrayList<>();
        for (TheMahjongTile.Suit suit : TheMahjongTile.Suit.values()) {
            for (int rank = 1; rank <= suit.maxRank(); rank++) {
                TheMahjongTile cand = new TheMahjongTile(suit, rank, false);
                List<TheMahjongTile> probe = new ArrayList<>(hand);
                probe.add(cand);
                if (isWinning(probe, melds, rules)) out.add(cand);
            }
        }
        return out;
    }

    static Map<Long, Integer> counts(List<TheMahjongTile> tiles) {
        Map<Long, Integer> m = new HashMap<>();
        for (TheMahjongTile t : tiles) m.merge(suitRankKey(t), 1, Integer::sum);
        return m;
    }

    static long suitRankKey(TheMahjongTile t) {
        return (long) t.suit().ordinal() * 100 + t.rank();
    }
}
