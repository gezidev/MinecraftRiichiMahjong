package com.riichimahjongforge.mahjongtable;

import com.riichimahjongforge.chinesemahjong.ChineseRulePreset;
import com.themahjong.TheMahjongMatch;

import java.util.function.Supplier;

/**
 * A named ruleset / player-count combination the table can be configured to play.
 * Each preset bundles:
 * <ul>
 *   <li>The library factory that produces the {@link TheMahjongMatch} (riichi), or</li>
 *   <li>A {@link ChineseRulePreset} for Chinese play (大众麻将).</li>
 *   <li>The player count, which dictates the canonical seat layout.</li>
 *   <li>A translation key for UI display.</li>
 * </ul>
 *
 * <p>The default preset is {@link #MAHJONG_SOUL_4P}; the table also has a soft
 * fallback from MS-4P to {@link #MAHJONG_SOUL_SANMA_3P} when the player closes the
 * North seat (handled in the BE), so a freshly placed table can be played as either
 * 4-player or sanma without opening settings.
 */
public enum RuleSetPreset {
    MAHJONG_SOUL_4P("mahjong_soul_4p", 4, TheMahjongMatch::defaultMahjongSoul, null),
    TENHOU_4P("tenhou_4p", 4, TheMahjongMatch::defaultTenhou, null),
    WRC_4P("wrc_4p", 4, TheMahjongMatch::defaults, null),
    MAHJONG_SOUL_SANMA_3P("mahjong_soul_sanma_3p", 3, TheMahjongMatch::defaultMahjongSoulSanma, null),
    TENHOU_SANMA_3P("tenhou_sanma_3p", 3, TheMahjongMatch::defaultTenhouSanma, null),
    MASS_MAHJONG_4P("mass_mahjong_4p", 4, null, ChineseRulePreset.MASS_MAHJONG),
    SICHUAN_BLOOD_BATTLE_4P("sichuan_blood_battle_4p", 4, null, ChineseRulePreset.SICHUAN_BLOOD_BATTLE),
    DONGBEI_TUI_DAO_HU_4P("dongbei_tui_dao_hu_4p", 4, null, ChineseRulePreset.DONGBEI_TUI_DAO_HU),
    GUANGDONG_JI_PING_HU_4P("guangdong_ji_ping_hu_4p", 4, null, ChineseRulePreset.GUANGDONG_JI_PING_HU);

    private final String key;
    private final int playerCount;
    private final Supplier<TheMahjongMatch> factory;
    private final ChineseRulePreset chinese;

    RuleSetPreset(String key, int playerCount, Supplier<TheMahjongMatch> factory, ChineseRulePreset chinese) {
        this.key = key;
        this.playerCount = playerCount;
        this.factory = factory;
        this.chinese = chinese;
    }

    public int playerCount() { return playerCount; }

    public boolean isChinese() { return chinese != null; }

    public ChineseRulePreset chinese() { return chinese; }

    /** Riichi presets only — Chinese presets throw (the BE branches on {@link #isChinese()}). */
    public TheMahjongMatch newMatch() {
        if (factory == null) throw new UnsupportedOperationException("Chinese presets use ChineseMatch");
        return factory.get();
    }

    public String langKey() { return "riichi_mahjong_forge.preset." + key; }
}
