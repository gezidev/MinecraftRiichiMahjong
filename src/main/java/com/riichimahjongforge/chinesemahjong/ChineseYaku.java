package com.riichimahjongforge.chinesemahjong;

/**
 * Chinese mahjong fan (番) types shared across the three regional variants.
 * <ul>
 *   <li>四川血战到底 (Sichuan blood battle): PING_HU base, no honors in deck, 缺一门 gate.</li>
 *   <li>东北推倒胡 (Northeast tui-dao-hu): any legal hand wins (推倒胡 base).</li>
 *   <li>广东鸡平胡 (Cantonese ji-ping-hu): JI_HU base, PING_HU higher tier, 爆胡 cap.</li>
 * </ul>
 */
public enum ChineseYaku {

    // ── Base / gate ─────────────────────────────────────────────────────
    /** 平胡 — basic Standard win (Sichuan/Dongbei base). */
    PING_HU,
    /** 鸡胡 — basic win (Guangdong base). */
    JI_HU,
    /** 缺一门 — Sichuan gate (hand lacks the declared missing suit). Zero fan. */
    QUE_YI_MEN,

    // ── Extra fan ───────────────────────────────────────────────────────
    /** 自摸 — self-drawn win. */
    ZIMO,
    /** 门前清 — win with a fully concealed hand (no open melds). */
    MEN_QIAN_QING,
    /** 七对 — seven distinct pairs. */
    QI_DUI,
    /** 龙七对 — one quad + five pairs (4+5×2). */
    LONG_QI_DUI,
    /** 碰碰胡 — all triplets (no sequences). */
    PENG_PENG_HU,
    /** 清一色 — one number suit only, no honors. */
    QING_YI_SE,
    /** 混一色 — one number suit plus honors. */
    HUN_YI_SE,
    /** 将对 — all triplets of 2/5/8 (Sichuan). */
    JIANG_DUI,
    /** 幺九 — all tiles terminal (1/9), no honors. */
    YAO_JIU,
    /** 杠上花 — win by self-draw right after declaring a kan. */
    GANG_SHANG_HUA,
    /** 杠上炮 — win by ron on the discard right after a kan. */
    GANG_SHANG_PAO,
    /** 抢杠胡 — rob a kakan (adding-kan) and win by ron. */
    QIANG_GANG_HU,
    /** 海底捞月 — self-draw the last wall tile. */
    HAI_DI,
    /** 河底捞鱼 — ron on the last discard of the wall. */
    HE_DI,

    // ── Full-win (爆胡 / 满贯) ──────────────────────────────────────────
    /** 天胡 — dealer self-draw on the initial hand. */
    TIAN_HU,
    /** 地胡 — non-dealer self-draw on the first turn. */
    DI_HU,
    /** 十三幺 — thirteen orphans. */
    SHI_SAN_YAO,
    /** 大三元 — three dragon triplets. */
    DA_SAN_YUAN,
    /** 四暗刻 — four concealed triplets. */
    SI_AN_KE;

    /** Full-win yaku score at the cap (爆胡/满贯). */
    public boolean isFullWin() {
        return switch (this) {
            case TIAN_HU, DI_HU, SHI_SAN_YAO, DA_SAN_YUAN, SI_AN_KE -> true;
            default -> false;
        };
    }
}
