package com.riichimahjongforge.chinesemahjong;

import com.themahjong.TheMahjongTile;

/**
 * Context describing how a Chinese hand was won — consumed by {@link ChineseYakuChecker}
 * and {@link ChineseScoring}. Mirrors {@code com.themahjong.yaku.WinContext} minus all
 * riichi-only fields.
 */
public record ChineseWinContext(
        boolean tsumo,
        boolean dealer,
        TheMahjongTile winningTile,
        /** Sichuan 定缺花色；其余地区为 null。 */
        TheMahjongTile.Suit missingSuit,
        /** 海底捞月/河底捞鱼（牌墙最后一张）。 */
        boolean lastTile,
        /** 杠后补摸自摸（杠上花）。 */
        boolean gangShangHua,
        /** 杠后打出的第一张被荣和（杠上炮）。 */
        boolean gangShangPao,
        /** 抢他人补杠胡。 */
        boolean qiangGang,
        /** 首巡庄家自摸（天胡）。 */
        boolean firstRoundDealerTsumo,
        /** 首巡闲家自摸（地胡）。 */
        boolean firstRoundNonDealerTsumo) {
}
