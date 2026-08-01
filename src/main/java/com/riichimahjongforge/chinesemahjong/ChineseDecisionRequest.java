package com.riichimahjongforge.chinesemahjong;

import java.util.List;

/** What a Chinese player sees when it's their turn to decide. */
public record ChineseDecisionRequest(
        int seat,
        ChineseMatchPhase phase,
        List<ChinesePlayerAction> legalActions,
        ChineseRoundState round,
        ChineseRules rules) {
}
