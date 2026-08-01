package com.riichimahjongforge.chinesemahjong;

import java.util.EnumMap;
import java.util.Map;

/**
 * Configuration record for a Chinese regional mahjong variant. Everything that
 * differs between 四川/东北/广东 lives here; the flow engine and scoring read
 * only these switches.
 *
 * <p>Scoring model (shared by all three):
 * <pre>
 *   points = baseFanPoints &lt;&lt; min(fan, maxFan)      // 1番=1, 2番=2, 3番=4 …
 *   full-win (爆胡/满贯) = baseFanPoints &lt;&lt; maxFan
 *   ron: 出牌者单付 · tsumo: 其余三家各付
 *   dealerDouble: 庄家赢则每人多付一倍，闲家赢则庄家多付一倍
 * </pre>
 */
public record ChineseRules(
        boolean allowChi,
        boolean requireQueYiMen,
        boolean singleWinEndsRound,
        boolean allowChitoitsu,
        boolean allowLongQiDui,
        boolean allowKokushi,
        int baseFanPoints,
        int maxFan,
        boolean dealerDouble,
        boolean dealerStaysOnWin,
        int gangBasePoints,
        boolean gangImmediate,
        boolean checkDaJiaoAtEnd,
        boolean checkHuaZhuAtEnd,
        int huaZhuPenaltyPoints,
        Map<ChineseYaku, Integer> fanTable) {

    /** 四川血战到底：108 万筒条，缺一门，无吃，血战到底，刮风下雨，查大叫/查花猪。 */
    public static ChineseRules sichuan() {
        Map<ChineseYaku, Integer> fan = new EnumMap<>(ChineseYaku.class);
        fan.put(ChineseYaku.PING_HU, 1);
        fan.put(ChineseYaku.ZIMO, 1);
        fan.put(ChineseYaku.QI_DUI, 2);
        fan.put(ChineseYaku.LONG_QI_DUI, 3);
        fan.put(ChineseYaku.PENG_PENG_HU, 2);
        fan.put(ChineseYaku.QING_YI_SE, 3);
        fan.put(ChineseYaku.JIANG_DUI, 3);
        fan.put(ChineseYaku.YAO_JIU, 2);
        fan.put(ChineseYaku.GANG_SHANG_HUA, 3);
        fan.put(ChineseYaku.GANG_SHANG_PAO, 3);
        fan.put(ChineseYaku.QIANG_GANG_HU, 2);
        fan.put(ChineseYaku.HAI_DI, 3);
        fan.put(ChineseYaku.HE_DI, 3);
        fan.put(ChineseYaku.TIAN_HU, 5);
        fan.put(ChineseYaku.DI_HU, 5);
        return new ChineseRules(
                /*allowChi*/ false,
                /*requireQueYiMen*/ true,
                /*singleWinEndsRound*/ false,   // 血战到底
                /*allowChitoitsu*/ true,
                /*allowLongQiDui*/ true,
                /*allowKokushi*/ false,          // 无字牌必然 false
                /*baseFanPoints*/ 1,
                /*maxFan*/ 5,
                /*dealerDouble*/ false,
                /*dealerStaysOnWin*/ false,
                /*gangBasePoints*/ 1,
                /*gangImmediate*/ true,          // 刮风下雨
                /*checkDaJiaoAtEnd*/ true,
                /*checkHuaZhuAtEnd*/ true,
                /*huaZhuPenaltyPoints*/ 8,
                fan);
    }

    /** 东北推倒胡：136 全牌，吃碰杠，推倒胡即和，单胡终局，连庄。 */
    public static ChineseRules dongbei() {
        Map<ChineseYaku, Integer> fan = new EnumMap<>(ChineseYaku.class);
        fan.put(ChineseYaku.PING_HU, 1);         // 推倒胡基础
        fan.put(ChineseYaku.MEN_QIAN_QING, 1);
        fan.put(ChineseYaku.QI_DUI, 2);
        fan.put(ChineseYaku.PENG_PENG_HU, 2);
        fan.put(ChineseYaku.QING_YI_SE, 3);
        fan.put(ChineseYaku.HUN_YI_SE, 2);
        fan.put(ChineseYaku.GANG_SHANG_HUA, 3);
        fan.put(ChineseYaku.QIANG_GANG_HU, 2);
        fan.put(ChineseYaku.SHI_SAN_YAO, 5);
        fan.put(ChineseYaku.TIAN_HU, 5);
        fan.put(ChineseYaku.DI_HU, 5);
        return new ChineseRules(
                true,   // allowChi
                false,  // requireQueYiMen
                true,   // singleWinEndsRound
                true,   // allowChitoitsu
                false,  // allowLongQiDui
                true,   // allowKokushi
                1, 5,   // base/max fan
                true,   // dealerDouble
                true,   // dealerStaysOnWin
                1,      // gangBasePoints
                false,  // gangImmediate (杠并入番，非即时)
                false, false, 0, // no 查大叫/花猪
                fan);
    }

    /** 大众麻将：136 全牌（含字牌），吃碰杠，常见番种，单胡终局，连庄，无定缺/无血战/无刮风下雨即时结算。 */
    public static ChineseRules mass() {
        Map<ChineseYaku, Integer> fan = new EnumMap<>(ChineseYaku.class);
        fan.put(ChineseYaku.PING_HU, 1);
        fan.put(ChineseYaku.ZIMO, 1);
        fan.put(ChineseYaku.MEN_QIAN_QING, 1);
        fan.put(ChineseYaku.QI_DUI, 2);
        fan.put(ChineseYaku.LONG_QI_DUI, 3);
        fan.put(ChineseYaku.PENG_PENG_HU, 2);
        fan.put(ChineseYaku.QING_YI_SE, 3);
        fan.put(ChineseYaku.HUN_YI_SE, 2);
        fan.put(ChineseYaku.JIANG_DUI, 3);
        fan.put(ChineseYaku.YAO_JIU, 2);
        fan.put(ChineseYaku.GANG_SHANG_HUA, 3);
        fan.put(ChineseYaku.GANG_SHANG_PAO, 3);
        fan.put(ChineseYaku.QIANG_GANG_HU, 2);
        fan.put(ChineseYaku.HAI_DI, 3);
        fan.put(ChineseYaku.HE_DI, 3);
        fan.put(ChineseYaku.TIAN_HU, 6);
        fan.put(ChineseYaku.DI_HU, 6);
        fan.put(ChineseYaku.SHI_SAN_YAO, 6);
        fan.put(ChineseYaku.DA_SAN_YUAN, 6);
        fan.put(ChineseYaku.SI_AN_KE, 6);
        return new ChineseRules(
                true,   // allowChi
                false,  // requireQueYiMen
                true,   // singleWinEndsRound
                true,   // allowChitoitsu
                true,   // allowLongQiDui
                true,   // allowKokushi
                1, 6,   // base/max fan（爆胡=6番封顶）
                true,   // dealerDouble
                true,   // dealerStaysOnWin（连庄）
                1,      // gangBasePoints
                false,  // gangImmediate（杠并入番，非即时）
                false, false, 0, // no 查大叫/花猪
                fan);
    }

    /** 广东鸡平胡：136 全牌，吃碰杠，鸡胡起步/平胡/一番/两番/爆胡。 */
    public static ChineseRules guangdong() {
        Map<ChineseYaku, Integer> fan = new EnumMap<>(ChineseYaku.class);
        fan.put(ChineseYaku.JI_HU, 1);           // 鸡胡基础
        fan.put(ChineseYaku.PING_HU, 2);         // 平胡 = 全顺子
        fan.put(ChineseYaku.ZIMO, 1);
        fan.put(ChineseYaku.MEN_QIAN_QING, 1);
        fan.put(ChineseYaku.QI_DUI, 2);
        fan.put(ChineseYaku.PENG_PENG_HU, 2);
        fan.put(ChineseYaku.HUN_YI_SE, 2);
        fan.put(ChineseYaku.QING_YI_SE, 3);
        fan.put(ChineseYaku.GANG_SHANG_HUA, 3);
        fan.put(ChineseYaku.QIANG_GANG_HU, 2);
        fan.put(ChineseYaku.SHI_SAN_YAO, 6);
        fan.put(ChineseYaku.DA_SAN_YUAN, 6);
        fan.put(ChineseYaku.SI_AN_KE, 6);
        fan.put(ChineseYaku.TIAN_HU, 6);
        fan.put(ChineseYaku.DI_HU, 6);
        return new ChineseRules(
                true,   // allowChi
                false,  // requireQueYiMen
                true,   // singleWinEndsRound
                true,   // allowChitoitsu
                false,  // allowLongQiDui
                true,   // allowKokushi
                1, 6,   // base/max fan（爆胡=6番封顶）
                true,   // dealerDouble
                true,   // dealerStaysOnWin
                1,      // gangBasePoints
                false,  // gangImmediate
                false, false, 0,
                fan);
    }

    /** Fan value for a yaku; full-win yaku return the cap. */
    public int fanOf(ChineseYaku y) {
        if (y.isFullWin()) return maxFan;
        return fanTable.getOrDefault(y, 0);
    }

    public int capFan(int fan) {
        return Math.min(fan, maxFan);
    }

    /** Score for a hand of {@code fan} fan (already capped). 1番=底分, 2番=2×底分, 3番=4×底分… */
    public int pointsForFan(int fan) {
        return baseFanPoints << Math.max(0, capFan(fan) - 1);
    }

    /** Score for a full-win (爆胡/满贯) hand. */
    public int fullWinPoints() {
        return baseFanPoints << (maxFan - 1);
    }
}
