package com.riichimahjongforge.chinesemahjong.tool;

import com.riichimahjongforge.chinesemahjong.ChineseDecisionRequest;
import com.riichimahjongforge.chinesemahjong.ChineseGameDriver;
import com.riichimahjongforge.chinesemahjong.ChineseMatch;
import com.riichimahjongforge.chinesemahjong.ChineseMatchPhase;
import com.riichimahjongforge.chinesemahjong.ChinesePlayerAction;
import com.riichimahjongforge.chinesemahjong.ChinesePlayerState;
import com.riichimahjongforge.chinesemahjong.ChineseRoundState;
import com.riichimahjongforge.chinesemahjong.ChineseRulePreset;
import com.riichimahjongforge.chinesemahjong.ChineseRules;
import com.riichimahjongforge.chinesemahjong.client.ChineseMahjongTableHumanPlayer;
import com.riichimahjongforge.cuterenderer.InteractKey;
import com.themahjong.TheMahjongMeld;
import com.themahjong.TheMahjongTile;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;

/**
 * Headless verification of the Chinese "click to pon" chain: renderer builds
 * buttons from client driver → server CuteClick → queue → driver applies.
 * Run: java -cp build/classes/java/main ...tool.ChineseClickChainCheck
 */
public final class ChineseClickChainCheck {

    private static int failures = 0;

    public static void main(String[] args) {
        checkPonClickChain();
        checkDiscardClickChain();
        checkQueYiMenClickChain();
        System.out.println(failures == 0 ? "ALL PASS" : failures + " FAILURES");
        if (failures > 0) System.exit(1);
    }

    private static void check(String name, boolean ok) {
        System.out.println((ok ? "PASS " : "FAIL ") + name);
        if (!ok) failures++;
    }

    private static TheMahjongTile t(TheMahjongTile.Suit s, int r) { return new TheMahjongTile(s, r, false); }

    /** 构造一个 CLAIM_WINDOW：seat1 打出 5万，seat0 手牌有一对 5万可碰。 */
    private static ChineseRoundState claimWindowRound() {
        ChineseRules rules = ChineseRulePreset.MASS_MAHJONG.rules();
        List<ChinesePlayerState> ps = new ArrayList<>();
        for (int i = 0; i < 4; i++) ps.add(new ChinesePlayerState(100));

        // seat 0（用户）：含一对 5万 + 11 张杂牌
        ChinesePlayerState p0 = ps.get(0);
        p0.draw(t(TheMahjongTile.Suit.MANZU,5)); p0.draw(t(TheMahjongTile.Suit.MANZU,5));
        p0.draw(t(TheMahjongTile.Suit.MANZU,1)); p0.draw(t(TheMahjongTile.Suit.MANZU,2));
        p0.draw(t(TheMahjongTile.Suit.MANZU,3)); p0.draw(t(TheMahjongTile.Suit.MANZU,6));
        p0.draw(t(TheMahjongTile.Suit.MANZU,7)); p0.draw(t(TheMahjongTile.Suit.MANZU,8));
        p0.draw(t(TheMahjongTile.Suit.MANZU,9)); p0.draw(t(TheMahjongTile.Suit.PINZU,1));
        p0.draw(t(TheMahjongTile.Suit.PINZU,1)); p0.draw(t(TheMahjongTile.Suit.PINZU,2));
        p0.draw(t(TheMahjongTile.Suit.PINZU,3));
        // seat 1（打出者）：13 张杂牌
        ChinesePlayerState p1 = ps.get(1);
        for (int i = 0; i < 13; i++) p1.draw(t(TheMahjongTile.Suit.SOUZU, i % 9 + 1));
        // seat 2/3
        for (int s = 2; s < 4; s++) for (int i = 0; i < 13; i++) ps.get(s).draw(t(TheMahjongTile.Suit.PINZU, i % 9 + 1));

        // 剩余墙（不含 5万），随便 30 张
        List<TheMahjongTile> wall = new ArrayList<>();
        for (int i = 0; i < 30; i++) wall.add(t(TheMahjongTile.Suit.SOUZU, i % 9 + 1));

        return ChineseRoundState.restore(
                4, 0, TheMahjongTile.Wind.EAST, 1, wall, rules, ps,
                ChineseRoundState.State.CLAIM_WINDOW,
                0,            // currentTurnSeat
                1,            // claimFromSeat = 1
                t(TheMahjongTile.Suit.MANZU,5), // activeTile = 5万
                1,            // lastDrawSeat
                5, 6,         // diceA, diceB
                0, 14, false, false, false, false, List.of());
    }

    private static void checkPonClickChain() {
        ChineseRoundState round = claimWindowRound();
        ChineseMatch match = ChineseRulePreset.MASS_MAHJONG.newMatch();
        match.restoreRound(round);
        List<com.riichimahjongforge.chinesemahjong.ChinesePlayerInterface> players = new ArrayList<>();
        players.add(new ChineseMahjongTableHumanPlayer());          // seat 0 用户
        players.add(new com.riichimahjongforge.chinesemahjong.ChineseStupidActiveBot());
        players.add(new com.riichimahjongforge.chinesemahjong.ChineseStupidActiveBot());
        players.add(new com.riichimahjongforge.chinesemahjong.ChineseStupidActiveBot());
        ChineseGameDriver driver = new ChineseGameDriver(match, players, new Random(7));
        driver.restorePhase();

        List<ChinesePlayerAction> legal0 = driver.legalActions(0);
        boolean hasPon = legal0.stream().anyMatch(a -> a instanceof ChinesePlayerAction.Pon);
        check("seat0 可碰", hasPon);

        // 找碰按钮在按钮列表里的 idx
        List<ChinesePlayerAction> buttons = ChineseMahjongTableHumanPlayer.chineseTableButtons(driver, 0);
        int ponIdx = -1;
        for (int i = 0; i < buttons.size(); i++) {
            if (buttons.get(i) instanceof ChinesePlayerAction.Pon) { ponIdx = i; break; }
        }
        check("碰按钮在列表中", ponIdx >= 0);

        // 模拟点击碰按钮
        ChineseMahjongTableHumanPlayer human = (ChineseMahjongTableHumanPlayer) players.get(0);
        human.onCuteClick(new InteractKey.SeatSlot((byte) 0, InteractKey.SeatSlot.AREA_BUTTON, (short) ponIdx),
                driver, 0, null);

        // advance 直到碰应用
        int ticks = 0;
        while (ticks < 200 && round.players().get(0).melds().isEmpty()) {
            driver.advance(0.05);
            ticks++;
        }
        boolean ponApplied = round.players().get(0).melds().stream()
                .anyMatch(m -> m instanceof TheMahjongMeld.Pon);
        check("点击后碰生效 (ticks=" + ticks + ")", ponApplied);
        check("碰后进入出牌阶段", round.state() == ChineseRoundState.State.AWAITING_DISCARD
                && round.currentTurnSeat() == 0);
    }

    private static void checkDiscardClickChain() {
        ChineseRoundState round = claimWindowRound();
        ChineseMatch match = ChineseRulePreset.MASS_MAHJONG.newMatch();
        match.restoreRound(round);
        List<com.riichimahjongforge.chinesemahjong.ChinesePlayerInterface> players = new ArrayList<>();
        players.add(new ChineseMahjongTableHumanPlayer());
        players.add(new com.riichimahjongforge.chinesemahjong.ChineseStupidActiveBot());
        players.add(new com.riichimahjongforge.chinesemahjong.ChineseStupidActiveBot());
        players.add(new com.riichimahjongforge.chinesemahjong.ChineseStupidActiveBot());
        ChineseGameDriver driver = new ChineseGameDriver(match, players, new Random(7));
        driver.restorePhase();

        // 进入 seat0 出牌：先让 seat1 的出牌无人副露（过），轮到 seat0 摸牌→出牌
        // 手动推进：skipClaims → seat0 AWAITING_DRAW
        // 我们直接构造一个 AWAITING_DISCARD 局面更可控
        ChineseRoundState dround = ChineseRoundState.restore(
                4, 0, TheMahjongTile.Wind.EAST, 1,
                new ArrayList<>(), ChineseRulePreset.MASS_MAHJONG.rules(),
                new ArrayList<>() {{
                    for (int i = 0; i < 4; i++) add(new ChinesePlayerState(100));
                }},
                ChineseRoundState.State.AWAITING_DISCARD, 0, -1,
                t(TheMahjongTile.Suit.MANZU,5), 0, 5, 6,
                0, 14, true, false, false, false, List.of());
        ChineseMatch dm = ChineseRulePreset.MASS_MAHJONG.newMatch();
        dm.restoreRound(dround);
        ChineseGameDriver ddriver = new ChineseGameDriver(dm, players, new Random(7));
        ddriver.restorePhase();

        // 用户点手牌第一张出牌
        List<TheMahjongTile> hand = dround.handDisplayOrder(0);
        ChinesePlayerState p0 = dround.players().get(0);
        // 给 seat0 手牌
        p0.draw(t(TheMahjongTile.Suit.MANZU,1)); p0.draw(t(TheMahjongTile.Suit.MANZU,2));
        p0.draw(t(TheMahjongTile.Suit.MANZU,3)); p0.draw(t(TheMahjongTile.Suit.MANZU,6));
        p0.draw(t(TheMahjongTile.Suit.MANZU,7)); p0.draw(t(TheMahjongTile.Suit.MANZU,8));
        p0.draw(t(TheMahjongTile.Suit.MANZU,9)); p0.draw(t(TheMahjongTile.Suit.PINZU,1));
        p0.draw(t(TheMahjongTile.Suit.PINZU,2)); p0.draw(t(TheMahjongTile.Suit.PINZU,3));
        p0.draw(t(TheMahjongTile.Suit.PINZU,4)); p0.draw(t(TheMahjongTile.Suit.PINZU,5));
        p0.draw(t(TheMahjongTile.Suit.PINZU,6)); p0.draw(t(TheMahjongTile.Suit.MANZU,5)); // 摸到的 5万

        hand = dround.handDisplayOrder(0);
        ChineseMahjongTableHumanPlayer human = (ChineseMahjongTableHumanPlayer) players.get(0);
        TheMahjongTile toDiscard = hand.get(0);
        int handIdx = hand.indexOf(toDiscard);
        human.onCuteClick(new InteractKey.SeatSlot((byte) 0, InteractKey.SeatSlot.AREA_HAND, (short) handIdx),
                ddriver, 0, null);

        int ticks = 0;
        while (ticks < 200 && dround.players().get(0).discards().isEmpty()) {
            ddriver.advance(0.05);
            ticks++;
        }
        check("点击手牌出牌生效", !dround.players().get(0).discards().isEmpty());
    }

    /** 四川定缺点击链：缺筒按钮 → 点击 → 入队 → driver 应用 → missingSuit=筒。
     *  手牌万5/筒5/条3，自动选会选条；必须点击后才定筒，验证手动选择生效。 */
    private static void checkQueYiMenClickChain() {
        ChineseRules rules = ChineseRulePreset.SICHUAN_BLOOD_BATTLE.rules();
        List<ChinesePlayerState> ps = new ArrayList<>();
        for (int i = 0; i < 4; i++) ps.add(new ChinesePlayerState(100));
        ChinesePlayerState p0 = ps.get(0);
        for (int i = 1; i <= 5; i++) p0.draw(t(TheMahjongTile.Suit.MANZU, i));
        for (int i = 1; i <= 5; i++) p0.draw(t(TheMahjongTile.Suit.PINZU, i));
        for (int i = 1; i <= 3; i++) p0.draw(t(TheMahjongTile.Suit.SOUZU, i));
        for (int s = 1; s < 4; s++) for (int i = 1; i <= 13; i++) ps.get(s).draw(t(TheMahjongTile.Suit.PINZU, i % 9 + 1));
        List<TheMahjongTile> wall = new ArrayList<>();
        for (int i = 0; i < 30; i++) wall.add(t(TheMahjongTile.Suit.MANZU, i % 9 + 1));

        ChineseRoundState round = ChineseRoundState.restore(
                4, 0, TheMahjongTile.Wind.EAST, 1, wall, rules, ps,
                ChineseRoundState.State.AWAITING_QUE_YI_MEN,
                0, -1, null, -1, 5, 6, 0, 1, false, false, false, false, List.of());
        ChineseMatch match = ChineseRulePreset.SICHUAN_BLOOD_BATTLE.newMatch();
        match.restoreRound(round);
        List<com.riichimahjongforge.chinesemahjong.ChinesePlayerInterface> players = new ArrayList<>();
        players.add(new ChineseMahjongTableHumanPlayer());
        players.add(new com.riichimahjongforge.chinesemahjong.ChineseStupidActiveBot());
        players.add(new com.riichimahjongforge.chinesemahjong.ChineseStupidActiveBot());
        players.add(new com.riichimahjongforge.chinesemahjong.ChineseStupidActiveBot());
        ChineseGameDriver driver = new ChineseGameDriver(match, players, new Random(7));
        driver.restorePhase();

        List<ChinesePlayerAction> buttons = ChineseMahjongTableHumanPlayer.chineseTableButtons(driver, 0);
        int pinIdx = -1;
        for (int i = 0; i < buttons.size(); i++) {
            if (buttons.get(i) instanceof ChinesePlayerAction.DeclareMissingSuit dm
                    && dm.missing() == TheMahjongTile.Suit.PINZU) { pinIdx = i; break; }
        }
        check("定缺按钮含缺筒", pinIdx >= 0);

        ChineseMahjongTableHumanPlayer human = (ChineseMahjongTableHumanPlayer) players.get(0);
        human.onCuteClick(new InteractKey.SeatSlot((byte) 0, InteractKey.SeatSlot.AREA_BUTTON, (short) pinIdx),
                driver, 0, null);

        int ticks = 0;
        while (ticks < 200 && round.players().get(0).missingSuit() == null) {
            driver.advance(0.05);
            ticks++;
        }
        check("点击缺筒生效", round.players().get(0).missingSuit() == TheMahjongTile.Suit.PINZU);
    }
}
