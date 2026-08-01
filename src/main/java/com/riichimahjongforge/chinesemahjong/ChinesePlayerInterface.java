package com.riichimahjongforge.chinesemahjong;

import java.util.Optional;

/** Decision interface for Chinese mahjong players (humans and bots). */
public interface ChinesePlayerInterface {

    /**
     * @param deltaSeconds time since the last poll — bots use it for think timers.
     * @return an action from {@code request.legalActions()}, or empty to pass this poll.
     */
    Optional<ChinesePlayerAction> chooseAction(ChineseDecisionRequest request, double deltaSeconds);

    default void onEvent(ChineseMatchEvent event) {}
}
