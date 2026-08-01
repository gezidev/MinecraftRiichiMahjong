package com.riichimahjongforge.chinesemahjong;

import com.themahjong.TheMahjongMeld;
import com.themahjong.TheMahjongTile;
import com.themahjong.yaku.HandShape;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * Chinese fan (番) detection. Mirrors the static-checker structure of
 * {@code com.themahjong.yaku.NonYakuman}, but detects Chinese yaku from a
 * {@link HandShape} decomposition plus the win context.
 *
 * <p>Fan model: the hand's dominant shape yaku (清一色/碰碰胡/七对…) supplies the main fan;
 * the base (平胡/鸡胡) only applies when no shape yaku hits. Situational yaku
 * (自摸/杠上花/海底…) add on top. Full-win yaku score at the cap.
 */
public final class ChineseYakuChecker {

    private ChineseYakuChecker() {}

    /** Yaku list + summed fan for one decomposition. */
    public record CheckResult(List<ChineseYaku> yaku, int fan, boolean fullWin) {}

    /** Situational (context) yaku accumulated over the base shape. */
    private record Situational(List<ChineseYaku> yaku, int fan, boolean fullWin) {}

    /**
     * Evaluate a potential Chinese win. Returns empty when the hand is not a win or
     * fails the 缺一门 gate. Picks the decomposition with the highest fan.
     *
     * @param concealed concealed tiles INCLUDING the winning tile: (4-melds)*3+2 tiles
     */
    public static Optional<ChineseWinResult> evaluate(
            List<TheMahjongTile> concealed,
            List<TheMahjongMeld> melds,
            ChineseWinContext ctx,
            ChineseRules rules,
            int winnerSeat, int fromSeat, int playerCount, int dealerSeat) {

        if (ctx.missingSuit() != null && containsSuit(concealed, ctx.missingSuit())) {
            return Optional.empty(); // 缺一门 gate failed
        }

        CheckResult best = null;
        for (HandShape h : HandShape.decompose(concealed, melds)) {
            CheckResult r = checkShape(h, ctx, rules);
            if (r != null && (best == null || r.fan() > best.fan())) best = r;
        }
        if (rules.allowLongQiDui() && melds.isEmpty() && ChineseWinForms.isLongQiDui(concealed)) {
            CheckResult r = checkLongQiDui(ctx, rules);
            if (r != null && (best == null || r.fan() > best.fan())) best = r;
        }
        if (best == null) return Optional.empty();

        int fan = rules.capFan(best.fan());
        boolean fullWin = best.fullWin();
        int points = fullWin ? rules.fullWinPoints() : rules.pointsForFan(fan);
        List<Integer> deltas = ChineseScoring.winDeltas(
                points, ctx.tsumo(), winnerSeat, fromSeat, playerCount, dealerSeat, rules);
        return Optional.of(new ChineseWinResult(
                best.yaku(), fan, fullWin, points, ctx.tsumo(), ctx.winningTile(), deltas));
    }

    // ── Per-shape detection ──────────────────────────────────────────────

    private static CheckResult checkShape(HandShape shape, ChineseWinContext ctx, ChineseRules rules) {
        List<ChineseYaku> yaku = new ArrayList<>();
        int fan = 0;
        boolean fullWin = false;

        if (shape instanceof HandShape.Kokushimusou) {
            if (!rules.fanTable().containsKey(ChineseYaku.SHI_SAN_YAO)) return null;
            yaku.add(ChineseYaku.SHI_SAN_YAO);
            fan += rules.fanOf(ChineseYaku.SHI_SAN_YAO);
            fullWin = true;
        } else if (shape instanceof HandShape.Chitoitsu) {
            if (!rules.allowChitoitsu()) return null;
            yaku.add(ChineseYaku.QI_DUI);
            fan += rules.fanOf(ChineseYaku.QI_DUI);
        } else if (shape instanceof HandShape.Standard s) {
            List<List<TheMahjongTile>> groups = allGroups(s);
            boolean allTrip = allTriplets(groups);

            // Stackable shape majors
            boolean qing = rules.fanTable().containsKey(ChineseYaku.QING_YI_SE) && isQingYiSe(s);
            if (qing) {
                yaku.add(ChineseYaku.QING_YI_SE);
                fan += rules.fanOf(ChineseYaku.QING_YI_SE);
            }
            if (!qing && rules.fanTable().containsKey(ChineseYaku.HUN_YI_SE) && isHunYiSe(s)) {
                yaku.add(ChineseYaku.HUN_YI_SE);
                fan += rules.fanOf(ChineseYaku.HUN_YI_SE);
            }
            if (allTrip) {
                if (rules.fanTable().containsKey(ChineseYaku.JIANG_DUI) && allJiangTiles(s)) {
                    yaku.add(ChineseYaku.JIANG_DUI);
                    fan += rules.fanOf(ChineseYaku.JIANG_DUI);
                } else if (rules.fanTable().containsKey(ChineseYaku.PENG_PENG_HU)) {
                    yaku.add(ChineseYaku.PENG_PENG_HU);
                    fan += rules.fanOf(ChineseYaku.PENG_PENG_HU);
                }
            }
            if (rules.fanTable().containsKey(ChineseYaku.YAO_JIU) && isYaoJiu(s)) {
                yaku.add(ChineseYaku.YAO_JIU);
                fan += rules.fanOf(ChineseYaku.YAO_JIU);
            }
            // Full-win standard hands
            if (rules.fanTable().containsKey(ChineseYaku.SI_AN_KE) && allTrip && s.closed()) {
                yaku.add(ChineseYaku.SI_AN_KE);
                fan += rules.fanOf(ChineseYaku.SI_AN_KE);
                fullWin = true;
            }
            if (rules.fanTable().containsKey(ChineseYaku.DA_SAN_YUAN) && hasDragonTriplets(groups)) {
                yaku.add(ChineseYaku.DA_SAN_YUAN);
                fan += rules.fanOf(ChineseYaku.DA_SAN_YUAN);
                fullWin = true;
            }
            // Base only when no shape yaku hit
            if (fan == 0) {
                ChineseYaku base = pickBase(s, rules);
                yaku.add(base);
                fan += rules.fanOf(base);
            }
            // 门前清 (concealed, no open melds) — Standard only
            if (rules.fanTable().containsKey(ChineseYaku.MEN_QIAN_QING) && s.closed()) {
                yaku.add(ChineseYaku.MEN_QIAN_QING);
                fan += rules.fanOf(ChineseYaku.MEN_QIAN_QING);
            }
        } else {
            return null;
        }

        Situational st = situational(ctx, rules);
        yaku.addAll(st.yaku());
        fan += st.fan();
        fullWin |= st.fullWin();
        return new CheckResult(yaku, fan, fullWin);
    }

    private static CheckResult checkLongQiDui(ChineseWinContext ctx, ChineseRules rules) {
        List<ChineseYaku> yaku = new ArrayList<>();
        yaku.add(ChineseYaku.LONG_QI_DUI);
        int fan = rules.fanOf(ChineseYaku.LONG_QI_DUI);
        Situational st = situational(ctx, rules);
        yaku.addAll(st.yaku());
        fan += st.fan();
        return new CheckResult(yaku, fan, st.fullWin());
    }

    /** Base yaku for a Standard hand: 广东平胡(全顺子)/鸡胡, 四川/东北平胡. */
    private static ChineseYaku pickBase(HandShape.Standard s, ChineseRules rules) {
        if (rules.fanTable().containsKey(ChineseYaku.JI_HU)) {
            boolean allSeq = allSequences(allGroups(s))
                    && rules.fanTable().containsKey(ChineseYaku.PING_HU);
            return allSeq ? ChineseYaku.PING_HU : ChineseYaku.JI_HU;
        }
        return ChineseYaku.PING_HU;
    }

    private static Situational situational(ChineseWinContext ctx, ChineseRules rules) {
        List<ChineseYaku> yaku = new ArrayList<>();
        int fan = 0;
        boolean fullWin = false;
        if (ctx.tsumo() && rules.fanTable().containsKey(ChineseYaku.ZIMO)) {
            yaku.add(ChineseYaku.ZIMO);
            fan += rules.fanOf(ChineseYaku.ZIMO);
        }
        if (ctx.gangShangHua() && rules.fanTable().containsKey(ChineseYaku.GANG_SHANG_HUA)) {
            yaku.add(ChineseYaku.GANG_SHANG_HUA);
            fan += rules.fanOf(ChineseYaku.GANG_SHANG_HUA);
        }
        if (ctx.gangShangPao() && rules.fanTable().containsKey(ChineseYaku.GANG_SHANG_PAO)) {
            yaku.add(ChineseYaku.GANG_SHANG_PAO);
            fan += rules.fanOf(ChineseYaku.GANG_SHANG_PAO);
        }
        if (ctx.qiangGang() && rules.fanTable().containsKey(ChineseYaku.QIANG_GANG_HU)) {
            yaku.add(ChineseYaku.QIANG_GANG_HU);
            fan += rules.fanOf(ChineseYaku.QIANG_GANG_HU);
        }
        if (ctx.lastTile()) {
            if (ctx.tsumo() && rules.fanTable().containsKey(ChineseYaku.HAI_DI)) {
                yaku.add(ChineseYaku.HAI_DI);
                fan += rules.fanOf(ChineseYaku.HAI_DI);
            } else if (!ctx.tsumo() && rules.fanTable().containsKey(ChineseYaku.HE_DI)) {
                yaku.add(ChineseYaku.HE_DI);
                fan += rules.fanOf(ChineseYaku.HE_DI);
            }
        }
        if (ctx.firstRoundDealerTsumo() && rules.fanTable().containsKey(ChineseYaku.TIAN_HU)) {
            yaku.add(ChineseYaku.TIAN_HU);
            fan += rules.fanOf(ChineseYaku.TIAN_HU);
            fullWin = true;
        }
        if (ctx.firstRoundNonDealerTsumo() && rules.fanTable().containsKey(ChineseYaku.DI_HU)) {
            yaku.add(ChineseYaku.DI_HU);
            fan += rules.fanOf(ChineseYaku.DI_HU);
            fullWin = true;
        }
        return new Situational(yaku, fan, fullWin);
    }

    // ── Shape inspection helpers ─────────────────────────────────────────

    static List<List<TheMahjongTile>> allGroups(HandShape.Standard s) {
        List<List<TheMahjongTile>> groups = new ArrayList<>();
        for (TheMahjongMeld m : s.melds()) groups.add(m.tiles());
        for (HandShape.ConcealedGroup g : s.closedGroups()) groups.add(g.tiles());
        return groups;
    }

    private static List<TheMahjongTile> allTiles(HandShape.Standard s) {
        List<TheMahjongTile> out = new ArrayList<>();
        for (List<TheMahjongTile> g : allGroups(s)) out.addAll(g);
        out.add(s.pair());
        return out;
    }

    static boolean allTriplets(List<List<TheMahjongTile>> groups) {
        for (List<TheMahjongTile> g : groups) {
            TheMahjongTile f = g.get(0);
            for (TheMahjongTile t : g) if (!t.matchesSuitRank(f)) return false;
        }
        return true;
    }

    static boolean allSequences(List<List<TheMahjongTile>> groups) {
        for (List<TheMahjongTile> g : groups) {
            if (g.size() != 3) return false;
            TheMahjongTile a = g.get(0), b = g.get(1), c = g.get(2);
            if (a.honor()) return false;
            if (a.suit() != b.suit() || b.suit() != c.suit()) return false;
            if (b.rank() != a.rank() + 1 || c.rank() != b.rank() + 1) return false;
        }
        return true;
    }

    static boolean isQingYiSe(HandShape.Standard s) {
        List<TheMahjongTile> all = allTiles(s);
        TheMahjongTile.Suit first = all.get(0).suit();
        if (!first.isNumber()) return false;
        for (TheMahjongTile t : all) if (t.suit() != first) return false;
        return true;
    }

    static boolean isHunYiSe(HandShape.Standard s) {
        List<TheMahjongTile> all = allTiles(s);
        TheMahjongTile.Suit numSuit = null;
        boolean honor = false;
        for (TheMahjongTile t : all) {
            if (t.honor()) {
                honor = true;
            } else {
                if (numSuit == null) numSuit = t.suit();
                else if (t.suit() != numSuit) return false;
            }
        }
        return numSuit != null && honor;
    }

    static boolean allJiangTiles(HandShape.Standard s) {
        for (TheMahjongTile t : allTiles(s)) {
            if (t.rank() != 2 && t.rank() != 5 && t.rank() != 8) return false;
        }
        return true;
    }

    static boolean isYaoJiu(HandShape.Standard s) {
        for (TheMahjongTile t : allTiles(s)) {
            if (t.rank() != 1 && t.rank() != 9) return false;
        }
        return true;
    }

    static boolean hasDragonTriplets(List<List<TheMahjongTile>> groups) {
        boolean haku = false, hatsu = false, chun = false;
        for (List<TheMahjongTile> g : groups) {
            if (g.get(0).suit() != TheMahjongTile.Suit.DRAGON) continue;
            switch (TheMahjongTile.Dragon.fromTileRank(g.get(0).rank())) {
                case HAKU -> haku = true;
                case HATSU -> hatsu = true;
                case CHUN -> chun = true;
            }
        }
        return haku && hatsu && chun;
    }

    private static boolean containsSuit(List<TheMahjongTile> tiles, TheMahjongTile.Suit suit) {
        for (TheMahjongTile t : tiles) if (t.suit() == suit) return true;
        return false;
    }
}
