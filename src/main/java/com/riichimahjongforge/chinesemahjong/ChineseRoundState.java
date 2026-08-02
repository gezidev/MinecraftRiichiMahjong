package com.riichimahjongforge.chinesemahjong;

import com.themahjong.TheMahjongMeld;
import com.themahjong.TheMahjongTile;

import java.util.ArrayList;
import java.util.List;

/**
 * One deal (局) of a Chinese mahjong round — a mutable state machine driven by
 * {@link ChineseGameDriver}. Supports the 血战到底 multi-winner flow via per-seat
 * {@code won} flags: after a player wins they stop playing while the remaining
 * players continue until 3 have won, the wall empties, or one player is left.
 */
public final class ChineseRoundState {

    public enum State {
        DEALING,
        AWAITING_QUE_YI_MEN,   // 四川定缺
        AWAITING_DRAW,
        AWAITING_DISCARD,
        CLAIM_WINDOW,          // 有人打出/加杠，等待副露或过
        ENDED
    }

    public enum ClaimKind { NONE, DISCARD, KAKAN_ROB }

    private final int playerCount;
    private final ChineseRules rules;
    private final TheMahjongTile.Wind roundWind;
    private final int handNumber;
    private final int dealerSeat;

    private State state;
    private int currentTurnSeat;
    private int claimFromSeat;
    private TheMahjongTile activeTile;
    private ClaimKind claimKind = ClaimKind.NONE;
    /** 最近一次摸牌的座位：当前出牌家的 activeTile 是否为「真正摸到的牌」的依据。
     *  吃碰杠后出牌家未摸牌，其 activeTile 是副露牌 → 发牌/展示按此区分。 */
    private int lastDrawSeat = -1;
    /** 变更计数：每次公开变更方法自增，供 driver 缓存 {@code legalActions}。 */
    private int version;
    /** 开局两颗骰子（1-6）。0 = legacy/无骰（发牌不跳牌）。 */
    private final int diceA;
    private final int diceB;
    /** 整局初始牌墙张数（136 等），供渲染端按固定位置显示消耗。 */
    private final int initialWallSize;

    private final List<TheMahjongTile> wall;
    private final List<ChinesePlayerState> players;
    private int wonCount;

    private int drawsSoFar;
    private boolean anyDiscardYet;
    private int afterKanSeat = -1;          // 该座位刚杠完，正在补摸（杠上花候选）
    private int kanRobSeat = -1;            // 该座位刚加杠，等待抢杠窗口
    private TheMahjongMeld.Pon kanRobPon;   // 被抢的加杠前的碰
    private TheMahjongTile kanRobAdded;     // 加杠补入的牌
    private boolean lastDrawWasWallEnd;
    private boolean lastDiscardWasWallEnd;
    private boolean kanDiscardPending;      // 当前打出的牌是杠后第一张（杠上炮候选）
    private final List<ChineseWinResult> winResults = new ArrayList<>();

    public ChineseRoundState(int playerCount, int dealerSeat, TheMahjongTile.Wind roundWind,
                             int handNumber, List<Integer> startPoints,
                             List<TheMahjongTile> wallIn, ChineseRules rules,
                             int diceA, int diceB) {
        this.playerCount = playerCount;
        this.dealerSeat = dealerSeat;
        this.roundWind = roundWind;
        this.handNumber = handNumber;
        this.rules = rules;
        this.diceA = diceA;
        this.diceB = diceB;
        this.initialWallSize = wallIn.size();
        this.wall = new ArrayList<>();
        this.players = new ArrayList<>();
        for (int i = 0; i < playerCount; i++) players.add(new ChinesePlayerState(startPoints.get(i)));

        // 真实开局：掷骰 → 定开门家 → 从开门处起跳发牌（回绕）。无骰（legacy）时从墙头顺序发。
        int n = wallIn.size();
        int breakTile = computeBreakTile(playerCount, dealerSeat, diceA, diceB, n);
        int dealtCount = 13 * playerCount + 1;
        int pos = 0;
        for (int i = 0; i < 13; i++) {
            for (int s = 0; s < playerCount; s++) {
                players.get(s).draw(wallIn.get((breakTile + pos) % n));
                pos++;
            }
        }
        TheMahjongTile dealerExtra = wallIn.get((breakTile + pos) % n);
        players.get(dealerSeat).draw(dealerExtra); // 庄家多摸一张
        this.activeTile = dealerExtra;
        this.lastDrawSeat = dealerSeat;
        pos++;
        for (int i = 0; i < n - dealtCount; i++) {
            this.wall.add(wallIn.get((breakTile + pos + i) % n)); // 余下为活牌墙，顺序连续回绕
        }
        this.drawsSoFar = 1;

        if (rules.requireQueYiMen()) {
            this.state = State.AWAITING_QUE_YI_MEN;
        } else {
            this.state = State.AWAITING_DISCARD;
            this.currentTurnSeat = dealerSeat;
        }
    }

    /**
     * 开门位置（tile 索引，0-based，指向洗好的墙）：从庄家逆时针数 {@code diceA+diceB}
     * 定开门家，再从开门家墙段右端（发牌方向）数 {@code diceA+diceB} 墩（2 张/墩）起跳。
     * 纯函数、确定性，NBT 恢复一致。
     */
    private static int computeBreakTile(int playerCount, int dealerSeat, int diceA, int diceB, int n) {
        if (diceA <= 0 || diceB <= 0) return 0; // legacy：不跳牌
        int total = diceA + diceB;
        int breaker = (dealerSeat + (total - 1)) % playerCount;
        if (n % (playerCount * 2) != 0) return (total * 2) % n; // 每段不整除则回退
        int stacksPerPlayer = n / (playerCount * 2);
        return ((breaker + 1) * stacksPerPlayer * 2 - total * 2) % n;
    }

    // ── Getters ─────────────────────────────────────────────────────────

    public int playerCount() { return playerCount; }
    public ChineseRules rules() { return rules; }
    public TheMahjongTile.Wind roundWind() { return roundWind; }
    public int handNumber() { return handNumber; }
    public int dealerSeat() { return dealerSeat; }
    public State state() { return state; }
    public int currentTurnSeat() { return currentTurnSeat; }
    public int claimFromSeat() { return claimFromSeat; }
    public TheMahjongTile activeTile() { return activeTile; }
    public ClaimKind claimKind() { return claimKind; }
    /** 最近一次摸牌座位；{@code lastDrawSeat()==seat && state()==AWAITING_DISCARD} 时
     *  {@code activeTile()} 才是该座位真正摸到的牌（供发牌/展示）。 */
    public int lastDrawSeat() { return lastDrawSeat; }

    /** 开局骰子 A（1-6；0 = legacy）。 */
    public int diceA() { return diceA; }
    /** 开局骰子 B（1-6；0 = legacy）。 */
    public int diceB() { return diceB; }
    /** 开门家座位：从庄家起逆时针数 {@code diceA+diceB}（庄家=1）。无骰时返回庄家。 */
    public int breakerSeat() {
        if (diceA <= 0 || diceB <= 0) return dealerSeat;
        return (dealerSeat + (diceA + diceB - 1)) % playerCount;
    }
    /** 该座位（相对庄家）的方位风。 */
    public TheMahjongTile.Wind seatWind(int seat) {
        int rel = ((seat - dealerSeat) % playerCount + playerCount) % playerCount;
        return TheMahjongTile.Wind.values()[rel];
    }

    /** 变更计数，供 {@link ChineseGameDriver} 缓存 legalActions。 */
    public int version() { return version; }

    private void touch() { version++; }
    public List<ChinesePlayerState> players() { return players; }
    public int wonCount() { return wonCount; }
    public int wallSize() { return wall.size(); }
    public boolean wallEmpty() { return wall.isEmpty(); }
    /** 整局初始牌墙张数（发牌前），供渲染端按固定位置显示墙消耗。 */
    public int initialWallSize() { return initialWallSize; }
    public List<TheMahjongTile> wall() { return wall; }
    public int drawsSoFar() { return drawsSoFar; }
    public boolean anyDiscardYet() { return anyDiscardYet; }
    public boolean lastDrawWasWallEnd() { return lastDrawWasWallEnd; }
    public boolean lastDiscardWasWallEnd() { return lastDiscardWasWallEnd; }
    public int afterKanSeat() { return afterKanSeat; }
    public boolean kanDiscardPending() { return kanDiscardPending; }
    public List<ChineseWinResult> winResults() { return winResults; }

    // ── 定缺 ────────────────────────────────────────────────────────────

    public void declareMissingSuit(int seat, TheMahjongTile.Suit missing) {
        players.get(seat).setMissingSuit(missing);
        if (queYiMenAllDeclared()) {
            state = State.AWAITING_DISCARD;
            currentTurnSeat = dealerSeat;
        }
        touch();
    }

    public boolean queYiMenAllDeclared() {
        for (ChinesePlayerState p : players) if (p.missingSuit() == null) return false;
        return true;
    }

    // ── Turn flow ────────────────────────────────────────────────────────

    /** Current player draws from the wall head. Returns the tile, or null on empty wall (→流局). */
    public TheMahjongTile drawNext() {
        if (wall.isEmpty()) {
            state = State.ENDED;
            return null;
        }
        TheMahjongTile t = wall.remove(0);
        players.get(currentTurnSeat).draw(t);
        drawsSoFar++;
        lastDrawWasWallEnd = wall.isEmpty();
        lastDiscardWasWallEnd = false;
        activeTile = t;
        lastDrawSeat = currentTurnSeat;
        state = State.AWAITING_DISCARD;
        touch();
        return t;
    }

    /** Current player discards a hand tile. Enters the claim window. */
    public void discard(TheMahjongTile tile) {
        ChinesePlayerState p = players.get(currentTurnSeat);
        p.discard(tile);
        anyDiscardYet = true;
        claimFromSeat = currentTurnSeat;
        activeTile = tile;
        claimKind = ClaimKind.DISCARD;
        lastDiscardWasWallEnd = wall.isEmpty();
        kanDiscardPending = afterKanSeat == currentTurnSeat;
        if (afterKanSeat == currentTurnSeat) {
            afterKanSeat = -1; // 杠后第一张打出（杠上炮候选）已落牌
        }
        state = State.CLAIM_WINDOW;
        touch();
    }

    /** Kakan (加杠): move pon→kakan, then open a 抢杠 window. */
    public void openKakanRobWindow(int seat, TheMahjongMeld.Pon pon, TheMahjongTile added) {
        ChinesePlayerState p = players.get(seat);
        p.removeFromHand(added);
        p.removeMeld(pon);
        p.addMeld(new TheMahjongMeld.Kakan(pon, added));
        kanRobSeat = seat;
        kanRobPon = pon;
        kanRobAdded = added;
        claimFromSeat = seat;
        activeTile = added;
        claimKind = ClaimKind.KAKAN_ROB;
        state = State.CLAIM_WINDOW;
        touch();
    }

    /** 抢杠窗口无人抢 → 杠家补摸。 */
    public void finishKakanRobPassed() {
        kanRobSeat = -1;
        kanRobPon = null;
        kanRobAdded = null;
        currentTurnSeat = claimFromSeat;
        afterKanSeat = claimFromSeat;
        claimKind = ClaimKind.NONE;
        drawReplacement(claimFromSeat);
    }

    // ── Melds (claims) ───────────────────────────────────────────────────

    public void applyPon(int seat, List<TheMahjongTile> own) {
        ChinesePlayerState p = players.get(seat);
        p.removeTilesFromHand(own);
        List<TheMahjongTile> meldTiles = new ArrayList<>(own);
        meldTiles.add(activeTile);
        int claimedIdx = meldTiles.indexOf(activeTile);
        p.addMeld(new TheMahjongMeld.Pon(meldTiles, claimedIdx, claimFromSeat, 0));
        removeActiveFromRiver();
        currentTurnSeat = seat;
        claimKind = ClaimKind.NONE;
        state = State.AWAITING_DISCARD;
        touch();
    }

    public void applyChi(int seat, List<TheMahjongTile> own) {
        ChinesePlayerState p = players.get(seat);
        p.removeTilesFromHand(own);
        List<TheMahjongTile> meldTiles = new ArrayList<>(own);
        meldTiles.add(activeTile);
        int claimedIdx = meldTiles.indexOf(activeTile);
        p.addMeld(new TheMahjongMeld.Chi(meldTiles, claimedIdx, claimFromSeat, 0));
        removeActiveFromRiver();
        currentTurnSeat = seat;
        claimKind = ClaimKind.NONE;
        state = State.AWAITING_DISCARD;
        touch();
    }

    public void applyDaiminkan(int seat, List<TheMahjongTile> own) {
        ChinesePlayerState p = players.get(seat);
        p.removeTilesFromHand(own);
        List<TheMahjongTile> meldTiles = new ArrayList<>(own);
        meldTiles.add(activeTile);
        int claimedIdx = meldTiles.indexOf(activeTile);
        p.addMeld(new TheMahjongMeld.Daiminkan(meldTiles, claimedIdx, claimFromSeat, 0));
        removeActiveFromRiver();
        currentTurnSeat = seat;
        afterKanSeat = seat;
        claimKind = ClaimKind.NONE;
        drawReplacement(seat);
    }

    public void applyAnkan(int seat, List<TheMahjongTile> tiles) {
        ChinesePlayerState p = players.get(seat);
        p.removeTilesFromHand(tiles);
        p.addMeld(new TheMahjongMeld.Ankan(tiles));
        currentTurnSeat = seat;
        afterKanSeat = seat;
        claimKind = ClaimKind.NONE;
        drawReplacement(seat);
    }

    private void drawReplacement(int seat) {
        if (wall.isEmpty()) {
            state = State.ENDED;
            touch();
            return;
        }
        TheMahjongTile t = wall.remove(0);
        players.get(seat).draw(t);
        drawsSoFar++;
        lastDrawWasWallEnd = wall.isEmpty();
        activeTile = t;
        lastDrawSeat = seat;
        state = State.AWAITING_DISCARD;
        touch();
    }

    private void removeActiveFromRiver() {
        if (activeTile != null && claimFromSeat >= 0 && claimFromSeat < playerCount) {
            players.get(claimFromSeat).discards().remove(activeTile);
        }
    }

    // ── Win ──────────────────────────────────────────────────────────────

    /**
     * Apply a win. Deltas are applied to all seats; the winner is marked won.
     * Single-win regions end the deal; 血战到底 continues while {@code wonCount < playerCount-1}
     * and more than one active player remains.
     */
    public void declareWin(int winner, boolean tsumo, int fromSeat, ChineseWinResult result) {
        touch();
        List<Integer> deltas = result.pointDeltas();
        for (int s = 0; s < playerCount; s++) players.get(s).addPoints(deltas.get(s));
        ChinesePlayerState w = players.get(winner);
        w.setWon(true);
        wonCount++;
        winResults.add(result);
        if (tsumo && afterKanSeat == winner) {
            afterKanSeat = -1; // 杠上花已结算
        }

        if (rules.singleWinEndsRound()) {
            state = State.ENDED;
            return;
        }
        // 血战到底：3人胡、牌墙尽、或只剩1人未胡 → 终局
        if (wonCount >= playerCount - 1 || activeSeats().size() <= 1 || wall.isEmpty()) {
            state = State.ENDED;
            return;
        }
        currentTurnSeat = nextActiveAfter(tsumo ? winner : fromSeat);
        claimKind = ClaimKind.NONE;
        state = State.AWAITING_DRAW;
    }

    /** No one claims the discard → advance to the next active player. */
    public void skipClaims() {
        touch();
        claimKind = ClaimKind.NONE;
        if (wall.isEmpty()) {
            state = State.ENDED;
            return;
        }
        currentTurnSeat = nextActiveAfter(claimFromSeat);
        state = State.AWAITING_DRAW;
    }

    /** Next non-won seat after {@code seat}. */
    public int nextActiveAfter(int seat) {
        for (int step = 1; step <= playerCount; step++) {
            int s = (seat + step) % playerCount;
            if (!players.get(s).won()) return s;
        }
        return seat;
    }

    public List<Integer> activeSeats() {
        List<Integer> out = new ArrayList<>();
        for (int s = 0; s < playerCount; s++) if (!players.get(s).won()) out.add(s);
        return out;
    }

    /** 查花猪/查大叫 — only for Sichuan (non-won seats). */
    public void settleEndOfDeal() {
        touch();
        if (!rules.checkDaJiaoAtEnd() && !rules.checkHuaZhuAtEnd()) return;
        List<Integer> unwon = activeSeats();
        int penalty = rules.huaZhuPenaltyPoints();
        if (rules.checkHuaZhuAtEnd()) {
            for (int x : unwon) {
                if (isHuaZhu(x)) {
                    for (int s = 0; s < playerCount; s++) {
                        if (s == x) continue;
                        players.get(s).addPoints(penalty);
                        players.get(x).addPoints(-penalty);
                    }
                }
            }
        }
        if (rules.checkDaJiaoAtEnd()) {
            for (int x : unwon) {
                if (hasJiao(x)) continue; // 有叫者不付
                for (int y : unwon) {
                    if (y == x || !hasJiao(y)) continue;
                    players.get(y).addPoints(penalty);
                    players.get(x).addPoints(-penalty);
                }
            }
        }
    }

    private boolean hasJiao(int seat) {
        ChinesePlayerState p = players.get(seat);
        return !ChineseWinForms.winningTiles(p.currentHand(), p.melds(), rules).isEmpty();
    }

    private boolean isHuaZhu(int seat) {
        boolean man = false, pin = false, sou = false;
        for (TheMahjongTile t : players.get(seat).currentHand()) {
            switch (t.suit()) {
                case MANZU -> man = true;
                case PINZU -> pin = true;
                case SOUZU -> sou = true;
                default -> {}
            }
        }
        return man && pin && sou;
    }

    /** 抢杠窗口状态查询。 */
    public int kanRobSeat() { return kanRobSeat; }
    public TheMahjongMeld.Pon kanRobPon() { return kanRobPon; }
    public TheMahjongTile kanRobAdded() { return kanRobAdded; }

    /**
     * Sorted hand for display/click consistency. Mirrors riichi: when this seat
     * is on-turn in {@code AWAITING_DISCARD} and just drew ({@code lastDrawSeat}),
     * the drawn tile ({@code activeTile}) is pinned rightmost so the renderer can
     * strip it when delivered while click indices stay aligned. After a meld
     * (Pon/Chi/Kan) the turn player did not draw — the guard leaves the hand sorted.
     */
    public List<TheMahjongTile> handDisplayOrder(int seat) {
        List<TheMahjongTile> hand = new ArrayList<>(players.get(seat).currentHand());
        hand.sort(TheMahjongTile.DISPLAY_ORDER);
        if (state == State.AWAITING_DISCARD && currentTurnSeat == seat
                && lastDrawSeat == seat && activeTile != null) {
            for (int i = 0; i < hand.size(); i++) {
                if (hand.get(i).equals(activeTile)) {
                    TheMahjongTile t = hand.remove(i);
                    hand.add(t);
                    break;
                }
            }
        }
        return hand;
    }

    /** 重建一局（NBT 恢复）：直接采用已构造的 players 与余牌墙，不重新发牌。 */
    public static ChineseRoundState restore(
            int playerCount, int dealerSeat, TheMahjongTile.Wind roundWind, int handNumber,
            List<TheMahjongTile> wall, ChineseRules rules,
            List<ChinesePlayerState> players, State state, int currentTurnSeat,
            int claimFromSeat, TheMahjongTile activeTile, int lastDrawSeat,
            int diceA, int diceB,
            int wonCount, int drawsSoFar,
            boolean anyDiscardYet, boolean kanDiscardPending,
            boolean lastDrawWasWallEnd, boolean lastDiscardWasWallEnd,
            List<ChineseWinResult> winResults) {
        ChineseRoundState r = new ChineseRoundState(playerCount, dealerSeat, roundWind,
                handNumber, wall, rules, players, diceA, diceB);
        r.state = state;
        r.currentTurnSeat = currentTurnSeat;
        r.claimFromSeat = claimFromSeat;
        r.activeTile = activeTile;
        r.lastDrawSeat = lastDrawSeat;
        r.wonCount = wonCount;
        r.drawsSoFar = drawsSoFar;
        r.anyDiscardYet = anyDiscardYet;
        r.kanDiscardPending = kanDiscardPending;
        r.lastDrawWasWallEnd = lastDrawWasWallEnd;
        r.lastDiscardWasWallEnd = lastDiscardWasWallEnd;
        r.winResults.clear();
        r.winResults.addAll(winResults);
        return r;
    }

    private ChineseRoundState(int playerCount, int dealerSeat, TheMahjongTile.Wind roundWind,
                              int handNumber, List<TheMahjongTile> wall, ChineseRules rules,
                              List<ChinesePlayerState> players, int diceA, int diceB) {
        this.playerCount = playerCount;
        this.dealerSeat = dealerSeat;
        this.roundWind = roundWind;
        this.handNumber = handNumber;
        this.rules = rules;
        this.wall = new ArrayList<>(wall);
        this.players = players;
        this.diceA = diceA;
        this.diceB = diceB;
        this.initialWallSize = 13 * playerCount + 1 + wall.size(); // 已发 + 余墙
        this.drawsSoFar = 1;
    }
}
