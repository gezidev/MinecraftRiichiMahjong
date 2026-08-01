package com.riichimahjongforge.chinesemahjong;

import com.themahjong.TheMahjongMeld;
import com.themahjong.TheMahjongTile;

import java.util.List;

/**
 * Actions a Chinese player can submit. Parallel to {@code com.themahjong.driver.PlayerAction}
 * minus all riichi-only actions (立直/北/九种九牌).
 */
public sealed interface ChinesePlayerAction {

    /** 摸牌 (only legal in AWAITING_DRAW). */
    record Draw() implements ChinesePlayerAction {}

    /** 打出一张手牌. */
    record Discard(TheMahjongTile tile) implements ChinesePlayerAction {}

    /** 碰: 自己两张 + 打出的那张. */
    record Pon(List<TheMahjongTile> own) implements ChinesePlayerAction {}

    /** 吃: 自己两张 + 上家打出的那张. */
    record Chi(List<TheMahjongTile> own) implements ChinesePlayerAction {}

    /** 大明杠: 自己三张 + 打出的那张. */
    record Daiminkan(List<TheMahjongTile> own) implements ChinesePlayerAction {}

    /** 暗杠: 自己四张. */
    record Ankan(List<TheMahjongTile> tiles) implements ChinesePlayerAction {}

    /** 加杠: 把碰升级为杠（可被抢杠）. */
    record Kakan(TheMahjongMeld.Pon pon, TheMahjongTile added) implements ChinesePlayerAction {}

    /** 四川定缺: 声明缺门花色. */
    record DeclareMissingSuit(TheMahjongTile.Suit missing) implements ChinesePlayerAction {}

    /** 自摸和牌. */
    record Tsumo(ChineseWinResult result) implements ChinesePlayerAction {}

    /** 荣和. */
    record Ron(ChineseWinResult result) implements ChinesePlayerAction {}

    /** 过. */
    record Pass() implements ChinesePlayerAction {}
}
