package com.riichimahjongforge.chinesemahjong;

import java.util.List;
import java.util.Optional;

/** Active bot: prefers 和牌 &gt; 杠 &gt; 碰 &gt; 吃 &gt; 摸 &gt; 打. */
public class ChineseStupidActiveBot extends ChineseAbstractBot {

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
            if (a instanceof ChinesePlayerAction.Daiminkan
                    || a instanceof ChinesePlayerAction.Ankan
                    || a instanceof ChinesePlayerAction.Kakan) return Optional.of(a);
        }
        for (ChinesePlayerAction a : legal) {
            if (a instanceof ChinesePlayerAction.Pon) return Optional.of(a);
        }
        for (ChinesePlayerAction a : legal) {
            if (a instanceof ChinesePlayerAction.Chi) return Optional.of(a);
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
