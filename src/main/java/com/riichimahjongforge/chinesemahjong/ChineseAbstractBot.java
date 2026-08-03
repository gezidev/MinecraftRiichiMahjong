package com.riichimahjongforge.chinesemahjong;

import com.themahjong.TheMahjongTile;

import java.util.List;
import java.util.Optional;

/** Think-timer base for Chinese bots; subclasses supply {@link #pick}. */
public abstract class ChineseAbstractBot implements ChinesePlayerInterface {

    private double thinkTime;

    @Override
    public Optional<ChinesePlayerAction> chooseAction(ChineseDecisionRequest request, double deltaSeconds) {
        thinkTime += deltaSeconds;
        double needed = secondsFor(request);
        if (thinkTime < needed) return Optional.empty();
        thinkTime = 0;
        return pick(request);
    }

    protected abstract Optional<ChinesePlayerAction> pick(ChineseDecisionRequest request);

    protected double secondsFor(ChineseDecisionRequest request) {
        for (ChinesePlayerAction a : request.legalActions()) {
            if (a instanceof ChinesePlayerAction.Tsumo || a instanceof ChinesePlayerAction.Ron) return 0.10;
            if (a instanceof ChinesePlayerAction.Pon
                    || a instanceof ChinesePlayerAction.Chi
                    || a instanceof ChinesePlayerAction.Daiminkan) return 0.08;
            if (a instanceof ChinesePlayerAction.Discard) return 0.05;
            if (a instanceof ChinesePlayerAction.Draw) return 0.02;
        }
        return 0.02;
    }

    /** 定缺：选手中最少的一门花色作为缺门。 */
    protected Optional<ChinesePlayerAction> chooseMissingSuit(ChineseDecisionRequest request) {
        List<TheMahjongTile> hand = request.round().players().get(request.seat()).currentHand();
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
        return Optional.of(new ChinesePlayerAction.DeclareMissingSuit(miss));
    }

    /** 主动打牌：选最后一手牌的任意同种。 */
    protected Optional<ChinesePlayerAction> pickDiscard(ChineseDecisionRequest request) {
        List<TheMahjongTile> hand = request.round().players().get(request.seat()).currentHand();
        for (int i = hand.size() - 1; i >= 0; i--) {
            TheMahjongTile t = hand.get(i);
            ChinesePlayerAction cand = new ChinesePlayerAction.Discard(t);
            if (request.legalActions().contains(cand)) return Optional.of(cand);
        }
        return Optional.empty();
    }
}
