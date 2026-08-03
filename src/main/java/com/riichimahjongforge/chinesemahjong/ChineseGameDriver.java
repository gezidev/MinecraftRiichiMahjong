package com.riichimahjongforge.chinesemahjong;

import com.themahjong.TheMahjongMeld;
import com.themahjong.TheMahjongTile;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Random;
import java.util.Set;

/**
 * Mutable orchestrator for a Chinese mahjong match. Mirrors the structure of
 * {@code com.themahjong.driver.TheMahjongDriver}: each tick {@link #advance(double)}
 * polls the player(s) whose decision is pending and applies their chosen action.
 */
public final class ChineseGameDriver {

    private final ChineseMatch match;
    private final List<ChinesePlayerInterface> players;
    private final Random random;
    private ChineseMatchPhase phase;
    private boolean endedFinalized;
    /** 副露窗口累计等待秒数——超过阈值自动替仍未表态的人类玩家过牌，防止卡局。 */
    private double claimElapsed;
    private static final double CLAIM_TIMEOUT_SECONDS = 25.0;
    /** 定缺累计等待秒数——人类玩家长时间未选缺门则自动选最少花色，防止卡局。 */
    private double queYiMenElapsed;
    private static final double QUE_YI_MEN_TIMEOUT_SECONDS = 30.0;

    public ChineseGameDriver(ChineseMatch match, List<ChinesePlayerInterface> players, Random random) {
        this.match = match;
        this.players = players;
        this.random = random;
        this.phase = new ChineseMatchPhase.NotStarted();
    }

    public ChineseMatch match() { return match; }
    public ChineseMatchPhase currentPhase() { return phase; }
    public ChinesePlayerInterface playerAt(int seat) { return players.get(seat); }
    public int playerCount() { return players.size(); }

    public void startMatch() {
        match.start(random);
        syncPhase();
        for (ChinesePlayerInterface p : players) p.onEvent(new ChineseMatchEvent.RoundStarted());
    }

    /** NBT 恢复用：从已恢复的 match 重建 phase。 */
    public void restorePhase() {
        syncPhase();
    }

    /** NBT 持久化用：当前局是否已结算终局（查花猪/查大叫），读档避免重复结算。 */
    public boolean endedFinalized() { return endedFinalized; }
    public void setEndedFinalized(boolean v) { endedFinalized = v; }

    /** One tick. Polls the pending decision and applies it. */
    public void advance(double dt) {
        ChineseRoundState round = match.currentRound();
        if (round == null) return;
        switch (round.state()) {
            case AWAITING_QUE_YI_MEN -> pollQueYiMen(round, dt);
            case AWAITING_DRAW -> pollSingle(round, dt, round.currentTurnSeat());
            case AWAITING_DISCARD -> pollSingle(round, dt, round.currentTurnSeat());
            case CLAIM_WINDOW -> pollClaims(round, dt);
            case ENDED -> finalizeEnded(round);
            default -> {}
        }
    }

    // ── Legal actions ────────────────────────────────────────────────────

    /** Round-version the cache was computed against; -1 until first computation. */
    private int legalCacheVersion = -1;
    private final Map<Integer, List<ChinesePlayerAction>> legalCache = new HashMap<>();

    /**
     * Legal actions for {@code seat}, cached per round mutation version. The
     * client renderer polls this every scene rebuild and the server every tick —
     * recomputing the tsumo win-probe ({@link HandShape} decomposition) each time
     * is the main perf cost. legalActions only reads round state, so the cache
     * is correct until {@link ChineseRoundState#version()} changes.
     */
    public List<ChinesePlayerAction> legalActions(int seat) {
        ChineseRoundState round = match.currentRound();
        if (round == null) return List.of();
        int v = round.version();
        if (v != legalCacheVersion) {
            legalCache.clear();
            legalCacheVersion = v;
        }
        return legalCache.computeIfAbsent(seat, s -> computeLegalActions(round, s));
    }

    private List<ChinesePlayerAction> computeLegalActions(ChineseRoundState round, int seat) {
        switch (round.state()) {
            case AWAITING_QUE_YI_MEN -> {
                if (round.players().get(seat).missingSuit() != null) return List.of();
                return List.of(
                        new ChinesePlayerAction.DeclareMissingSuit(TheMahjongTile.Suit.MANZU),
                        new ChinesePlayerAction.DeclareMissingSuit(TheMahjongTile.Suit.PINZU),
                        new ChinesePlayerAction.DeclareMissingSuit(TheMahjongTile.Suit.SOUZU));
            }
            case AWAITING_DRAW -> {
                if (round.currentTurnSeat() != seat) return List.of();
                return List.of(new ChinesePlayerAction.Draw());
            }
            case AWAITING_DISCARD -> {
                if (round.currentTurnSeat() != seat) return List.of();
                List<ChinesePlayerAction> a = new ArrayList<>();
                // 自摸仅在真正摸过牌后合法（碰/吃后未摸牌不能自摸）。
                if (round.lastDrawSeat() == seat) {
                    ChineseWinResult tsumo = tryTsumo(round, seat);
                    if (tsumo != null) a.add(new ChinesePlayerAction.Tsumo(tsumo));
                }
                List<TheMahjongTile> hand = round.players().get(seat).currentHand();
                for (TheMahjongTile t : distinct(hand)) {
                    if (forcedMissing(round, seat, t)) continue;
                    a.add(new ChinesePlayerAction.Discard(t));
                }
                for (List<TheMahjongTile> quad : quadsInHand(round, seat)) {
                    a.add(new ChinesePlayerAction.Ankan(quad));
                }
                for (TheMahjongMeld.Pon pon : ponsOf(round, seat)) {
                    TheMahjongTile fourth = findFourth(round, seat, pon);
                    if (fourth != null) a.add(new ChinesePlayerAction.Kakan(pon, fourth));
                }
                return a;
            }
            case CLAIM_WINDOW -> {
                return claimActions(round, seat);
            }
            default -> { return List.of(); }
        }
    }

    private boolean forcedMissing(ChineseRoundState round, int seat, TheMahjongTile t) {
        ChineseRules rules = round.rules();
        if (!rules.requireQueYiMen()) return false;
        TheMahjongTile.Suit miss = round.players().get(seat).missingSuit();
        if (miss == null || t.suit() == miss) return false;
        // 手牌仍有缺门牌时，只能先打缺门牌
        for (TheMahjongTile h : round.players().get(seat).currentHand()) {
            if (h.suit() == miss) return true;
        }
        return false;
    }

    private List<ChinesePlayerAction> claimActions(ChineseRoundState round, int seat) {
        ChinesePlayerState p = round.players().get(seat);
        if (p.won()) return List.of();
        List<ChinesePlayerAction> a = new ArrayList<>();
        if (round.claimKind() == ChineseRoundState.ClaimKind.KAKAN_ROB) {
            if (seat != round.kanRobSeat()) {
                ChineseWinResult r = tryRon(round, seat, round.kanRobAdded());
                if (r != null) a.add(new ChinesePlayerAction.Ron(r));
            }
            a.add(new ChinesePlayerAction.Pass());
            return a;
        }
        // DISCARD claim
        TheMahjongTile held = round.activeTile();
        if (seat != round.claimFromSeat()) {
            ChineseWinResult r = tryRon(round, seat, held);
            if (r != null) a.add(new ChinesePlayerAction.Ron(r));
        }
        TheMahjongTile.Suit miss = p.missingSuit();
        boolean blockedByMissing = round.rules().requireQueYiMen()
                && miss != null && held != null && held.suit() == miss;
        if (!blockedByMissing) {
            List<TheMahjongTile> pon = findNOf(round, seat, held, 2);
            if (pon != null) a.add(new ChinesePlayerAction.Pon(pon));
            List<TheMahjongTile> dm = findNOf(round, seat, held, 3);
            if (dm != null) a.add(new ChinesePlayerAction.Daiminkan(dm));
            if (round.rules().allowChi() && seat == round.nextActiveAfter(round.claimFromSeat())) {
                for (List<TheMahjongTile> pair : chiPairs(round, seat, held)) {
                    a.add(new ChinesePlayerAction.Chi(pair));
                }
            }
        }
        a.add(new ChinesePlayerAction.Pass());
        return a;
    }

    // ── Win probing ──────────────────────────────────────────────────────

    private ChineseWinResult tryTsumo(ChineseRoundState round, int seat) {
        ChinesePlayerState p = round.players().get(seat);
        ChineseWinContext ctx = buildContext(round, seat, true, seat);
        return ChineseYakuChecker.evaluate(p.currentHand(), p.melds(), ctx, round.rules(),
                seat, seat, players.size(), round.dealerSeat()).orElse(null);
    }

    private ChineseWinResult tryRon(ChineseRoundState round, int seat, TheMahjongTile winTile) {
        if (winTile == null) return null;
        ChinesePlayerState p = round.players().get(seat);
        List<TheMahjongTile> concealed = new ArrayList<>(p.currentHand());
        concealed.add(winTile);
        ChineseWinContext ctx = buildContext(round, seat, false, round.claimFromSeat());
        return ChineseYakuChecker.evaluate(concealed, p.melds(), ctx, round.rules(),
                seat, round.claimFromSeat(), players.size(), round.dealerSeat()).orElse(null);
    }

    private ChineseWinContext buildContext(ChineseRoundState round, int seat, boolean tsumo, int fromSeat) {
        ChinesePlayerState p = round.players().get(seat);
        TheMahjongTile winTile = tsumo ? round.activeTile() : round.activeTile();
        if (winTile == null && !p.currentHand().isEmpty()) {
            winTile = p.currentHand().get(p.currentHand().size() - 1);
        }
        boolean kanRob = round.claimKind() == ChineseRoundState.ClaimKind.KAKAN_ROB;
        return new ChineseWinContext(
                tsumo,
                seat == round.dealerSeat(),
                winTile,
                p.missingSuit(),
                // 抢杠不是弃牌，不能算河底捞鱼。
                kanRob ? false : (tsumo ? round.lastDrawWasWallEnd() : round.lastDiscardWasWallEnd()),
                tsumo && round.afterKanSeat() == seat,
                !tsumo && round.kanDiscardPending(),
                kanRob,
                seat == round.dealerSeat() && round.drawsSoFar() == 1 && !round.anyDiscardYet(),
                seat != round.dealerSeat() && round.drawsSoFar() == 2 && !round.anyDiscardYet());
    }

    // ── Poll & apply ─────────────────────────────────────────────────────

    private void pollQueYiMen(ChineseRoundState round, double dt) {
        queYiMenElapsed += dt;
        boolean timeout = queYiMenElapsed >= QUE_YI_MEN_TIMEOUT_SECONDS;
        for (int s = 0; s < players.size(); s++) {
            if (round.players().get(s).missingSuit() != null) continue;
            if (timeout) {
                // 兜底：超时仍未定缺（人类挂机）则自动选最少花色，防止整局卡死。
                applyAction(round, s, autoMissing(round, s));
                syncPhase();
                if (round.state() != ChineseRoundState.State.AWAITING_QUE_YI_MEN) return;
                continue;
            }
            pollSingle(round, dt, s);
            if (round.state() != ChineseRoundState.State.AWAITING_QUE_YI_MEN) return;
        }
        if (round.state() != ChineseRoundState.State.AWAITING_QUE_YI_MEN) queYiMenElapsed = 0;
    }

    /** 超时兜底：自动选手牌最少的花色作为缺门（与机器人策略一致）。 */
    private static ChinesePlayerAction autoMissing(ChineseRoundState round, int seat) {
        List<TheMahjongTile> hand = round.players().get(seat).currentHand();
        int man = 0, pin = 0, sou = 0;
        for (TheMahjongTile t : hand) {
            switch (t.suit()) {
                case MANZU -> man++;
                case PINZU -> pin++;
                case SOUZU -> sou++;
                default -> {}
            }
        }
        TheMahjongTile.Suit miss;
        if (man <= pin && man <= sou) miss = TheMahjongTile.Suit.MANZU;
        else if (pin <= sou) miss = TheMahjongTile.Suit.PINZU;
        else miss = TheMahjongTile.Suit.SOUZU;
        return new ChinesePlayerAction.DeclareMissingSuit(miss);
    }

    private void pollSingle(ChineseRoundState round, double dt, int seat) {
        List<ChinesePlayerAction> legal = legalActions(seat);
        ChineseDecisionRequest req = new ChineseDecisionRequest(seat, phase, legal, round, round.rules());
        Optional<ChinesePlayerAction> chosen = players.get(seat).chooseAction(req, dt);
        if (chosen.isEmpty()) return;
        ChinesePlayerAction a = chosen.get();
        if (!legal.contains(a)) return;
        applyAction(round, seat, a);
        syncPhase();
    }

    private void applyAction(ChineseRoundState round, int seat, ChinesePlayerAction a) {
        if (a instanceof ChinesePlayerAction.Draw) {
            round.drawNext();
        } else if (a instanceof ChinesePlayerAction.Discard d) {
            round.discard(d.tile());
        } else if (a instanceof ChinesePlayerAction.Ankan an) {
            round.applyAnkan(seat, an.tiles());
            applyGangScoring(round, seat, -1, ChineseScoring.GangKind.AN_GANG);
        } else if (a instanceof ChinesePlayerAction.Kakan k) {
            round.openKakanRobWindow(seat, k.pon(), k.added());
            applyGangScoring(round, seat, -1, ChineseScoring.GangKind.BU_GANG);
        } else if (a instanceof ChinesePlayerAction.DeclareMissingSuit dm) {
            round.declareMissingSuit(seat, dm.missing());
        } else if (a instanceof ChinesePlayerAction.Tsumo t) {
            round.declareWin(seat, true, seat, t.result());
        } else if (a instanceof ChinesePlayerAction.Pon po) {
            round.applyPon(seat, po.own());
        } else if (a instanceof ChinesePlayerAction.Chi c) {
            round.applyChi(seat, c.own());
        } else if (a instanceof ChinesePlayerAction.Daiminkan dk) {
            round.applyDaiminkan(seat, dk.own());
            applyGangScoring(round, seat, round.claimFromSeat(), ChineseScoring.GangKind.MING_GANG);
        }
        // Pass / Ron handled by pollClaims
    }

    private void applyGangScoring(ChineseRoundState round, int seat, int fromSeat, ChineseScoring.GangKind kind) {
        if (!round.rules().gangImmediate()) return;
        List<Integer> deltas = ChineseScoring.gangDeltas(kind, seat, fromSeat, players.size(), round.rules());
        for (int s = 0; s < players.size(); s++) round.players().get(s).addPoints(deltas.get(s));
        for (ChinesePlayerInterface p : players) p.onEvent(new ChineseMatchEvent.PointsSettled(seat, kind.ordinal(), deltas));
    }

    private void pollClaims(ChineseRoundState round, double dt) {
        List<Integer> pending = eligibleClaimSeats(round);
        claimElapsed += dt;
        boolean timeout = claimElapsed >= CLAIM_TIMEOUT_SECONDS;
        Map<Integer, ChinesePlayerAction> decisions = new HashMap<>();
        for (int s : pending) {
            List<ChinesePlayerAction> legal = claimActions(round, s);
            // Seats with nothing to claim (only Pass) auto-pass instead of
            // blocking the whole claim window on a manual click.
            boolean onlyPass = true;
            for (ChinesePlayerAction a : legal) {
                if (!(a instanceof ChinesePlayerAction.Pass)) { onlyPass = false; break; }
            }
            if (onlyPass) {
                decisions.put(s, new ChinesePlayerAction.Pass());
                continue;
            }
            if (timeout) {
                // 超时兜底：人类玩家未表态则自动过，避免卡局。
                decisions.put(s, new ChinesePlayerAction.Pass());
                continue;
            }
            ChineseDecisionRequest req = new ChineseDecisionRequest(s, phase, legal, round, round.rules());
            Optional<ChinesePlayerAction> chosen = players.get(s).chooseAction(req, dt);
            if (chosen.isEmpty()) return; // wait for the deciding player
            decisions.put(s, legal.contains(chosen.get()) ? chosen.get() : new ChinesePlayerAction.Pass());
        }
        claimElapsed = 0;
        resolveClaims(round, decisions, pending);
        syncPhase();
    }

    private List<Integer> eligibleClaimSeats(ChineseRoundState round) {
        List<Integer> out = new ArrayList<>();
        for (int s = 0; s < players.size(); s++) {
            if (round.players().get(s).won()) continue;
            if (round.claimKind() == ChineseRoundState.ClaimKind.KAKAN_ROB && s == round.kanRobSeat()) continue;
            if (round.claimKind() == ChineseRoundState.ClaimKind.DISCARD && s == round.claimFromSeat()) continue;
            out.add(s);
        }
        return out;
    }

    private void resolveClaims(ChineseRoundState round, Map<Integer, ChinesePlayerAction> decisions, List<Integer> pending) {
        List<Integer> ronSeats = new ArrayList<>();
        for (int s : pending) {
            if (decisions.get(s) instanceof ChinesePlayerAction.Ron) ronSeats.add(s);
        }
        if (!ronSeats.isEmpty()) {
            boolean single = round.rules().singleWinEndsRound();
            int fromSeat = round.claimKind() == ChineseRoundState.ClaimKind.KAKAN_ROB
                    ? round.kanRobSeat() : round.claimFromSeat();
            List<Integer> winners = single ? List.of(nearestOf(ronSeats, fromSeat)) : ronSeats;
            for (int w : winners) {
                ChinesePlayerAction.Ron ron = (ChinesePlayerAction.Ron) decisions.get(w);
                round.declareWin(w, false, fromSeat, ron.result());
            }
            return;
        }
        if (round.claimKind() == ChineseRoundState.ClaimKind.KAKAN_ROB) {
            round.finishKakanRobPassed();
            return;
        }
        int fromSeat = round.claimFromSeat();
        for (int step = 1; step < players.size(); step++) {
            int s = (fromSeat + step) % players.size();
            ChinesePlayerAction a = decisions.get(s);
            if (a == null || a instanceof ChinesePlayerAction.Pass) continue;
            if (a instanceof ChinesePlayerAction.Pon po) { round.applyPon(s, po.own()); return; }
            if (a instanceof ChinesePlayerAction.Daiminkan dk) {
                round.applyDaiminkan(s, dk.own());
                applyGangScoring(round, s, fromSeat, ChineseScoring.GangKind.MING_GANG);
                return;
            }
            if (a instanceof ChinesePlayerAction.Chi c) { round.applyChi(s, c.own()); return; }
        }
        round.skipClaims();
    }

    private void finalizeEnded(ChineseRoundState round) {
        if (endedFinalized) return;
        endedFinalized = true;
        round.settleEndOfDeal();
        List<ChineseWinResult> results = round.winResults();
        phase = new ChineseMatchPhase.RoundEnded(results);
        for (ChinesePlayerInterface p : players) p.onEvent(new ChineseMatchEvent.RoundEnded(results));
    }

    /** Advance to the next deal after a result screen. No-op if the match is over. */
    public void advanceRound() {
        if (match.advanceRound(random)) {
            endedFinalized = false;
            queYiMenElapsed = 0; // 新局定缺计时清零，避免沿用上一局超时立即自动选
            syncPhase();
            for (ChinesePlayerInterface p : players) p.onEvent(new ChineseMatchEvent.RoundStarted());
        } else {
            phase = new ChineseMatchPhase.MatchEnded();
            for (ChinesePlayerInterface p : players) p.onEvent(new ChineseMatchEvent.MatchEnded());
        }
    }

    private void syncPhase() {
        ChineseRoundState round = match.currentRound();
        if (round == null) {
            phase = new ChineseMatchPhase.MatchEnded();
            return;
        }
        switch (round.state()) {
            case AWAITING_QUE_YI_MEN -> {
                List<Integer> pending = new ArrayList<>();
                for (int s = 0; s < players.size(); s++) if (round.players().get(s).missingSuit() == null) pending.add(s);
                phase = new ChineseMatchPhase.AwaitingQueYiMen(pending);
            }
            case AWAITING_DRAW -> phase = new ChineseMatchPhase.AwaitingDraw(round.currentTurnSeat());
            case AWAITING_DISCARD -> phase = new ChineseMatchPhase.AwaitingDiscard(round.currentTurnSeat());
            case CLAIM_WINDOW -> phase = new ChineseMatchPhase.AwaitingClaims(
                    eligibleClaimSeats(round), round.activeTile(),
                    round.claimKind() == ChineseRoundState.ClaimKind.KAKAN_ROB);
            case ENDED -> {
                // Round already finalized → expose RoundEnded directly instead of
                // letting finalizeEnded re-run (which would re-settle 查花猪/查大叫).
                if (endedFinalized) {
                    phase = new ChineseMatchPhase.RoundEnded(round.winResults());
                }
            }
            default -> {}
        }
    }

    // ── Helpers ──────────────────────────────────────────────────────────

    private static List<TheMahjongTile> distinct(List<TheMahjongTile> tiles) {
        Set<Long> seen = new LinkedHashSet<>();
        List<TheMahjongTile> out = new ArrayList<>();
        for (TheMahjongTile t : tiles) {
            // 区分红五与普通五（中式牌组无赤宝，但防御性处理）。
            long key = (long) t.suit().ordinal() * 1000 + t.rank() * 10 + (t.redDora() ? 1 : 0);
            if (seen.add(key)) out.add(t);
        }
        return out;
    }

    private static List<List<TheMahjongTile>> quadsInHand(ChineseRoundState round, int seat) {
        List<List<TheMahjongTile>> out = new ArrayList<>();
        List<TheMahjongTile> hand = round.players().get(seat).currentHand();
        for (TheMahjongTile t : distinct(hand)) {
            List<TheMahjongTile> quad = new ArrayList<>();
            for (TheMahjongTile h : hand) if (h.matchesSuitRank(t)) quad.add(h);
            if (quad.size() >= 4) out.add(quad.subList(0, 4));
        }
        return out;
    }

    private static List<TheMahjongMeld.Pon> ponsOf(ChineseRoundState round, int seat) {
        List<TheMahjongMeld.Pon> out = new ArrayList<>();
        for (TheMahjongMeld m : round.players().get(seat).melds()) {
            if (m instanceof TheMahjongMeld.Pon pon) out.add(pon);
        }
        return out;
    }

    private static TheMahjongTile findFourth(ChineseRoundState round, int seat, TheMahjongMeld.Pon pon) {
        TheMahjongTile base = pon.tiles().get(0);
        for (TheMahjongTile h : round.players().get(seat).currentHand()) {
            if (h.matchesSuitRank(base)) return h;
        }
        return null;
    }

    private static List<TheMahjongTile> findNOf(ChineseRoundState round, int seat, TheMahjongTile ref, int n) {
        if (ref == null) return null;
        List<TheMahjongTile> out = new ArrayList<>();
        for (TheMahjongTile h : round.players().get(seat).currentHand()) {
            if (h.matchesSuitRank(ref)) {
                out.add(h);
                if (out.size() == n) return out;
            }
        }
        return out.size() == n ? out : null;
    }

    /** Chi pairs: two hand tiles that together with {@code held} form a sequence. */
    private static List<List<TheMahjongTile>> chiPairs(ChineseRoundState round, int seat, TheMahjongTile held) {
        List<List<TheMahjongTile>> out = new ArrayList<>();
        if (held == null || held.honor()) return out;
        int r = held.rank();
        if (r >= 3) {
            List<TheMahjongTile> lo = findPair(round, seat, held.suit(), r - 2, r - 1);
            if (lo != null) out.add(lo);
        }
        if (r >= 2 && r <= 8) {
            List<TheMahjongTile> mid = findPair(round, seat, held.suit(), r - 1, r + 1);
            if (mid != null) out.add(mid);
        }
        if (r <= 7) {
            List<TheMahjongTile> hi = findPair(round, seat, held.suit(), r + 1, r + 2);
            if (hi != null) out.add(hi);
        }
        return out;
    }

    private static List<TheMahjongTile> findPair(ChineseRoundState round, int seat, TheMahjongTile.Suit suit, int r1, int r2) {
        TheMahjongTile t1 = null, t2 = null;
        for (TheMahjongTile h : round.players().get(seat).currentHand()) {
            if (h.suit() == suit && h.rank() == r1 && t1 == null) t1 = h;
            else if (h.suit() == suit && h.rank() == r2 && t2 == null) t2 = h;
        }
        if (t1 != null && t2 != null) return List.of(t1, t2);
        return null;
    }

    private static int nearestOf(List<Integer> seats, int fromSeat) {
        int best = seats.get(0);
        for (int s : seats) {
            if (distance(s, fromSeat) < distance(best, fromSeat)) best = s;
        }
        return best;
    }

    private static int distance(int seat, int from) {
        return ((seat - from) % 4 + 4) % 4;
    }
}
