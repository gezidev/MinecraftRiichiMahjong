package com.riichimahjongforge.chinesemahjong.tool;

import com.riichimahjongforge.chinesemahjong.ChineseDriverNbt;
import com.riichimahjongforge.chinesemahjong.ChineseGameDriver;
import com.riichimahjongforge.chinesemahjong.ChineseMatch;
import com.riichimahjongforge.chinesemahjong.ChineseMatchPhase;
import com.riichimahjongforge.chinesemahjong.ChinesePlayerInterface;
import com.riichimahjongforge.chinesemahjong.ChineseRoundState;
import com.riichimahjongforge.chinesemahjong.ChineseRulePreset;
import com.riichimahjongforge.chinesemahjong.ChineseRules;
import com.riichimahjongforge.chinesemahjong.ChineseStupidActiveBot;
import com.riichimahjongforge.chinesemahjong.ChineseWinContext;
import com.riichimahjongforge.chinesemahjong.ChineseWinResult;
import com.riichimahjongforge.chinesemahjong.ChineseYaku;
import com.riichimahjongforge.chinesemahjong.ChineseYakuChecker;
import com.themahjong.TheMahjongTile;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Random;

/** Headless smoke checks for the Chinese engine (大众麻将). Run with: java -cp build/classes/java/main ... */
public final class ChineseEngineCheck {

    private static int failures = 0;

    public static void main(String[] args) {
        checkPingHu();
        checkQiDui();
        checkLongQiDui();
        checkQingYiSe();
        checkScoringDeltas();
        checkWallNbtRoundTrip();
        checkMassFullGame();
        System.out.println(failures == 0 ? "ALL PASS" : failures + " FAILURES");
        if (failures > 0) System.exit(1);
    }

    private static void check(String name, boolean ok) {
        System.out.println((ok ? "PASS " : "FAIL ") + name);
        if (!ok) failures++;
    }

    private static TheMahjongTile t(TheMahjongTile.Suit s, int r) { return new TheMahjongTile(s, r, false); }

    private static List<TheMahjongTile> list(TheMahjongTile... ts) { return List.of(ts); }

    private static ChineseWinResult eval(List<TheMahjongTile> hand, ChineseRules rules) {
        return ChineseYakuChecker.evaluate(hand, List.of(),
                new ChineseWinContext(false, false, hand.get(hand.size() - 1), null,
                        false, false, false, false, false, false),
                rules, 0, 0, 4, 0).orElse(null);
    }

    private static void checkPingHu() {
        // 2 花色 Standard 平胡：万123 万666 万789 筒456 筒22 → 平胡 + 门前清 = 2 番
        List<TheMahjongTile> hand = list(
                t(TheMahjongTile.Suit.MANZU,1), t(TheMahjongTile.Suit.MANZU,2), t(TheMahjongTile.Suit.MANZU,3),
                t(TheMahjongTile.Suit.MANZU,6), t(TheMahjongTile.Suit.MANZU,6), t(TheMahjongTile.Suit.MANZU,6),
                t(TheMahjongTile.Suit.MANZU,7), t(TheMahjongTile.Suit.MANZU,8), t(TheMahjongTile.Suit.MANZU,9),
                t(TheMahjongTile.Suit.PINZU,4), t(TheMahjongTile.Suit.PINZU,5), t(TheMahjongTile.Suit.PINZU,6),
                t(TheMahjongTile.Suit.PINZU,2), t(TheMahjongTile.Suit.PINZU,2));
        ChineseWinResult r = eval(hand, ChineseRules.mass());
        check("大众平胡", r != null && r.fan() == 2 && r.yaku().contains(ChineseYaku.PING_HU));
    }

    private static void checkQiDui() {
        // 隔号对子，无法拆成顺子 → 只能七对（2 番）
        List<TheMahjongTile> hand = list(
                t(TheMahjongTile.Suit.MANZU,1), t(TheMahjongTile.Suit.MANZU,1),
                t(TheMahjongTile.Suit.MANZU,3), t(TheMahjongTile.Suit.MANZU,3),
                t(TheMahjongTile.Suit.MANZU,5), t(TheMahjongTile.Suit.MANZU,5),
                t(TheMahjongTile.Suit.MANZU,7), t(TheMahjongTile.Suit.MANZU,7),
                t(TheMahjongTile.Suit.MANZU,9), t(TheMahjongTile.Suit.MANZU,9),
                t(TheMahjongTile.Suit.PINZU,2), t(TheMahjongTile.Suit.PINZU,2),
                t(TheMahjongTile.Suit.PINZU,8), t(TheMahjongTile.Suit.PINZU,8));
        ChineseWinResult r = eval(hand, ChineseRules.mass());
        check("七对(大众)", r != null && r.yaku().contains(ChineseYaku.QI_DUI) && r.fan() == 2);
    }

    private static void checkLongQiDui() {
        // 四张5万 + 五对
        List<TheMahjongTile> hand = list(
                t(TheMahjongTile.Suit.MANZU,5), t(TheMahjongTile.Suit.MANZU,5),
                t(TheMahjongTile.Suit.MANZU,5), t(TheMahjongTile.Suit.MANZU,5),
                t(TheMahjongTile.Suit.MANZU,1), t(TheMahjongTile.Suit.MANZU,1),
                t(TheMahjongTile.Suit.MANZU,2), t(TheMahjongTile.Suit.MANZU,2),
                t(TheMahjongTile.Suit.MANZU,3), t(TheMahjongTile.Suit.MANZU,3),
                t(TheMahjongTile.Suit.MANZU,4), t(TheMahjongTile.Suit.MANZU,4),
                t(TheMahjongTile.Suit.PINZU,6), t(TheMahjongTile.Suit.PINZU,6));
        ChineseWinResult r = eval(hand, ChineseRules.mass());
        check("龙七对(大众)", r != null && r.yaku().contains(ChineseYaku.LONG_QI_DUI) && r.fan() == 3);
    }

    private static void checkQingYiSe() {
        List<TheMahjongTile> hand = list(
                t(TheMahjongTile.Suit.MANZU,1), t(TheMahjongTile.Suit.MANZU,2), t(TheMahjongTile.Suit.MANZU,3),
                t(TheMahjongTile.Suit.MANZU,4), t(TheMahjongTile.Suit.MANZU,5), t(TheMahjongTile.Suit.MANZU,6),
                t(TheMahjongTile.Suit.MANZU,7), t(TheMahjongTile.Suit.MANZU,8), t(TheMahjongTile.Suit.MANZU,9),
                t(TheMahjongTile.Suit.MANZU,2), t(TheMahjongTile.Suit.MANZU,3), t(TheMahjongTile.Suit.MANZU,4),
                t(TheMahjongTile.Suit.MANZU,5), t(TheMahjongTile.Suit.MANZU,5));
        ChineseWinResult r = eval(hand, ChineseRules.mass());
        check("清一色(大众)", r != null && r.yaku().contains(ChineseYaku.QING_YI_SE) && r.fan() == 4);
    }

    private static void checkScoringDeltas() {
        // 大众 荣和 庄家赢 1番: dealerDouble → 出牌者(非庄)付 2
        List<Integer> d = com.riichimahjongforge.chinesemahjong.ChineseScoring.winDeltas(
                1, false, 0, 2, 4, 0, ChineseRules.mass());
        check("大众庄家荣和翻倍", d.get(0) == 2 && d.get(2) == -2);
        // 大众 自摸 闲家赢 1番: 庄家付 2，其余各付 1 → winner +4
        List<Integer> d2 = com.riichimahjongforge.chinesemahjong.ChineseScoring.winDeltas(
                1, true, 1, 1, 4, 0, ChineseRules.mass());
        check("大众闲家自摸", d2.get(1) == 4 && d2.get(0) == -2 && d2.get(2) == -1 && d2.get(3) == -1);
    }

    /** 回归：墙消耗在 NBT 往返（服务器 → 客户端同步路径）后仍随摸牌递减。
     *  此前 initialWallSize 未持久化，客户端按「13N+1+余墙」反推导致
     *  taken 恒为 13N+1，牌墙在客户端看起来永不消耗（或每局只跳变一次）。 */
    private static void checkWallNbtRoundTrip() {
        try {
            Random random = new Random(42);
            ChineseRulePreset preset = ChineseRulePreset.SICHUAN_BLOOD_BATTLE;
            List<ChinesePlayerInterface> bots = new ArrayList<>();
            for (int i = 0; i < 4; i++) bots.add(new ChineseStupidActiveBot());
            ChineseGameDriver driver = new ChineseGameDriver(preset.newMatch(), bots, random);
            driver.startMatch();

            ChineseRoundState r0 = driver.match().currentRound();
            int fullWall = r0.initialWallSize();
            int wall0 = r0.wallSize();
            // 四川 108 张，发 13*4+1=53 → 余墙 55
            check("四川墙总数", fullWall == 108 && wall0 == 55);

            // 服务器侧：taken = 108 - wallSize，随摸牌递增
            int taken0 = fullWall - r0.wallSize();
            int draws0 = r0.drawsSoFar();
            for (int i = 0; i < 3; i++) r0.drawNext();
            int taken3 = fullWall - r0.wallSize();
            check("服务器墙递减", taken0 == 53 && taken3 == 56 && r0.drawsSoFar() == draws0 + 3);

            // 客户端侧：NBT 往返后 initialWallSize 保留、taken 同样递增
            ChineseGameDriver restored = ChineseDriverNbt.readDriver(
                    ChineseDriverNbt.writeDriver(driver), bots, random);
            ChineseRoundState r1 = restored.match().currentRound();
            int takenRestored0 = r1.initialWallSize() - r1.wallSize();
            r1.drawNext();
            int takenRestored1 = r1.initialWallSize() - r1.wallSize();
            check("NBT往返墙递减",
                    r1.initialWallSize() == 108
                            && takenRestored0 == 56
                            && takenRestored1 == 57
                            && r1.wallSize() == r0.wallSize() - 1);
        } catch (Exception e) {
            System.out.println("FAIL 墙NBT往返 (异常): " + e);
            failures++;
        }
    }

    private static void checkMassFullGame() {
        ChineseMatch match = ChineseRulePreset.MASS_MAHJONG.newMatch();
        List<ChinesePlayerInterface> bots = new ArrayList<>();
        for (int i = 0; i < 4; i++) bots.add(new ChineseStupidActiveBot());
        ChineseGameDriver driver = new ChineseGameDriver(match, bots, new Random(12345));
        driver.startMatch();
        int ticks = runToEnd(driver);
        boolean ended = driver.currentPhase() instanceof ChineseMatchPhase.MatchEnded;
        check("大众整场结束(ticks=" + ticks + ")", ended && ticks < 50000);

        check("东北整场可打完", runFullMatch(ChineseRulePreset.DONGBEI_TUI_DAO_HU) < 50000);
        check("广东整场可打完", runFullMatch(ChineseRulePreset.GUANGDONG_JI_PING_HU) < 50000);
        check("四川整场可打完", runFullMatch(ChineseRulePreset.SICHUAN_BLOOD_BATTLE) < 50000);
    }

    /** 跑完整场对局直到 MatchEnded，返回 tick 数（超时返回 50001）。 */
    private static int runFullMatch(ChineseRulePreset preset) {
        List<ChinesePlayerInterface> bots = new ArrayList<>();
        for (int i = 0; i < 4; i++) bots.add(new ChineseStupidActiveBot());
        ChineseGameDriver driver = new ChineseGameDriver(preset.newMatch(), bots, new Random(777));
        driver.startMatch();
        return runToEnd(driver);
    }

    private static int runToEnd(ChineseGameDriver driver) {
        int ticks = 0;
        while (ticks < 50000 && !(driver.currentPhase() instanceof ChineseMatchPhase.MatchEnded)) {
            driver.advance(0.1);
            if (driver.currentPhase() instanceof ChineseMatchPhase.RoundEnded) {
                // 进入下一局
                driver.advanceRound();
            }
            ticks++;
            if (ticks % 5000 == 0) System.out.println("  ... tick " + ticks + " phase=" + driver.currentPhase());
        }
        return ticks;
    }
}
