package com.riichimahjongforge.chinesemahjong;

import com.themahjong.TheMahjongTile;

import java.util.List;

/**
 * The result of a Chinese hand: which yaku were hit, the capped fan count,
 * the single-bet points, and per-seat point deltas (winner positive, payers negative).
 * Mirrors the shape of {@code com.themahjong.yaku.WinResult} for the renderer.
 */
public record ChineseWinResult(
        List<ChineseYaku> yaku,
        int fan,
        boolean fullWin,
        int points,
        boolean tsumo,
        TheMahjongTile winTile,
        List<Integer> pointDeltas) {
}
