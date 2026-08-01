package com.riichimahjongforge.chinesemahjong;

import java.util.List;
import java.util.Optional;

/** Passive bot: wins, draws, discards; never claims melds. */
public class ChineseStupidPassiveBot extends ChineseAbstractBot {

    @Override
    protected Optional<ChinesePlayerAction> pick(ChineseDecisionRequest request) {
        if (request.phase() instanceof ChineseMatchPhase.AwaitingQueYiMen) {
            return chooseMissingSuit(request);
        }
        List<ChinesePlayerAction> legal = request.legalActions();
        if (legal.isEmpty()) return Optional.empty();
        for (ChinesePlayerAction a : legal) {
            if (a instanceof ChinesePlayerAction.Tsumo || a instanceof ChinesePlayerAction.Ron) return Optional.of(a);
        }
        for (ChinesePlayerAction a : legal) {
            if (a instanceof ChinesePlayerAction.Draw) return Optional.of(a);
        }
        Optional<ChinesePlayerAction> disc = pickDiscard(request);
        if (disc.isPresent()) return disc;
        for (ChinesePlayerAction a : legal) {
            if (a instanceof ChinesePlayerAction.Pass) return Optional.of(a);
        }
        return Optional.empty();
    }
}
