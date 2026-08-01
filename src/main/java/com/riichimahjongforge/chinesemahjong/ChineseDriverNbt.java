package com.riichimahjongforge.chinesemahjong;

import com.riichimahjongforge.themahjongcompat.MatchNbt;
import com.themahjong.TheMahjongMeld;
import com.themahjong.TheMahjongTile;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.StringTag;
import net.minecraft.nbt.Tag;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;

/**
 * NBT serialization for {@link ChineseGameDriver} — mirrors
 * {@code com.riichimahjongforge.themahjongcompat.DriverNbt} for the Chinese engine.
 * Both server (world load) and client (block-entity sync) reconstruct the driver
 * from this tag so the in-world renderer sees the live Chinese game.
 */
public final class ChineseDriverNbt {

    private ChineseDriverNbt() {}

    public static CompoundTag writeDriver(ChineseGameDriver driver) {
        ChineseMatch match = driver.match();
        CompoundTag tag = new CompoundTag();
        tag.putInt("version", 1);
        tag.putString("preset", match.preset().name());
        tag.putInt("playerCount", match.playerCount());
        ListTag rounds = new ListTag();
        for (ChineseRoundState r : match.completedRounds()) rounds.add(writeRound(r));
        tag.put("completedRounds", rounds);
        if (match.currentRound() != null) {
            tag.put("round", writeRound(match.currentRound()));
        }
        return tag;
    }

    public static ChineseGameDriver readDriver(CompoundTag tag, List<ChinesePlayerInterface> players, Random random) {
        ChineseRulePreset preset;
        try {
            preset = ChineseRulePreset.valueOf(tag.getString("preset"));
        } catch (IllegalArgumentException ignored) {
            preset = ChineseRulePreset.MASS_MAHJONG; // 老档（地区预设已移除）回退到大众麻将
        }
        ChineseMatch match = preset.newMatch();
        ListTag rounds = tag.getList("completedRounds", Tag.TAG_COMPOUND);
        for (int i = 0; i < rounds.size(); i++) {
            match.completedRounds().add(readRound(rounds.getCompound(i), match));
        }
        if (tag.contains("round", Tag.TAG_COMPOUND)) {
            match.restoreRound(readRound(tag.getCompound("round"), match));
        }
        ChineseGameDriver driver = new ChineseGameDriver(match, players, random);
        driver.restorePhase();
        return driver;
    }

    // ── Round ────────────────────────────────────────────────────────────

    private static CompoundTag writeRound(ChineseRoundState r) {
        CompoundTag t = new CompoundTag();
        t.putString("state", r.state().name());
        t.putInt("dealerSeat", r.dealerSeat());
        t.putString("roundWind", r.roundWind().name());
        t.putInt("handNumber", r.handNumber());
        t.putInt("currentTurnSeat", r.currentTurnSeat());
        t.putInt("claimFromSeat", r.claimFromSeat());
        t.putInt("lastDrawSeat", r.lastDrawSeat());
        t.putInt("wonCount", r.wonCount());
        t.putInt("drawsSoFar", r.drawsSoFar());
        t.putBoolean("anyDiscardYet", r.anyDiscardYet());
        t.putBoolean("kanDiscardPending", r.kanDiscardPending());
        t.putBoolean("lastDrawWasWallEnd", r.lastDrawWasWallEnd());
        t.putBoolean("lastDiscardWasWallEnd", r.lastDiscardWasWallEnd());
        if (r.activeTile() != null) t.put("activeTile", MatchNbt.writeTile(r.activeTile()));
        t.put("wall", MatchNbt.writeTileList(r.wall()));
        ListTag players = new ListTag();
        for (ChinesePlayerState p : r.players()) players.add(writePlayer(p));
        t.put("players", players);
        ListTag wins = new ListTag();
        for (ChineseWinResult w : r.winResults()) wins.add(writeWinResult(w));
        t.put("winResults", wins);
        return t;
    }

    private static ChineseRoundState readRound(CompoundTag t, ChineseMatch match) {
        List<ChinesePlayerState> players = new ArrayList<>();
        ListTag pt = t.getList("players", Tag.TAG_COMPOUND);
        for (int i = 0; i < pt.size(); i++) players.add(readPlayer(pt.getCompound(i)));
        List<ChineseWinResult> wins = new ArrayList<>();
        ListTag wt = t.getList("winResults", Tag.TAG_COMPOUND);
        for (int i = 0; i < wt.size(); i++) wins.add(readWinResult(wt.getCompound(i)));
        return ChineseRoundState.restore(
                match.playerCount(), t.getInt("dealerSeat"),
                TheMahjongTile.Wind.valueOf(t.getString("roundWind")), t.getInt("handNumber"),
                MatchNbt.readTileList(t.getList("wall", Tag.TAG_COMPOUND)), match.rules(),
                players, ChineseRoundState.State.valueOf(t.getString("state")),
                t.getInt("currentTurnSeat"), t.getInt("claimFromSeat"),
                t.contains("activeTile") ? MatchNbt.readTile(t.getCompound("activeTile")) : null,
                t.contains("lastDrawSeat") ? t.getInt("lastDrawSeat") : -1,
                t.getInt("wonCount"), t.getInt("drawsSoFar"),
                t.getBoolean("anyDiscardYet"), t.getBoolean("kanDiscardPending"),
                t.getBoolean("lastDrawWasWallEnd"), t.getBoolean("lastDiscardWasWallEnd"),
                wins);
    }

    // ── Player ───────────────────────────────────────────────────────────

    private static CompoundTag writePlayer(ChinesePlayerState p) {
        CompoundTag t = new CompoundTag();
        t.putInt("points", p.points());
        t.putBoolean("won", p.won());
        if (p.missingSuit() != null) t.putString("missingSuit", p.missingSuit().name());
        t.put("hand", MatchNbt.writeTileList(p.currentHand()));
        t.put("discards", MatchNbt.writeTileList(p.discards()));
        ListTag melds = new ListTag();
        for (TheMahjongMeld m : p.melds()) melds.add(writeMeld(m));
        t.put("melds", melds);
        return t;
    }

    private static ChinesePlayerState readPlayer(CompoundTag t) {
        ChinesePlayerState p = new ChinesePlayerState(t.getInt("points"));
        p.setWon(t.getBoolean("won"));
        if (t.contains("missingSuit")) {
            p.setMissingSuit(TheMahjongTile.Suit.valueOf(t.getString("missingSuit")));
        }
        for (TheMahjongTile x : MatchNbt.readTileList(t.getList("hand", Tag.TAG_COMPOUND))) p.draw(x);
        p.discards().addAll(MatchNbt.readTileList(t.getList("discards", Tag.TAG_COMPOUND)));
        ListTag melds = t.getList("melds", Tag.TAG_COMPOUND);
        for (int i = 0; i < melds.size(); i++) p.addMeld(readMeld(melds.getCompound(i)));
        return p;
    }

    private static CompoundTag writeMeld(TheMahjongMeld m) {
        CompoundTag t = new CompoundTag();
        if (m instanceof TheMahjongMeld.Chi c) {
            t.putString("type", "chi");
            t.putInt("claimedIdx", c.claimedTileIndex());
        } else if (m instanceof TheMahjongMeld.Pon) {
            t.putString("type", "pon");
        } else if (m instanceof TheMahjongMeld.Daiminkan) {
            t.putString("type", "daiminkan");
        } else if (m instanceof TheMahjongMeld.Ankan) {
            t.putString("type", "ankan");
        } else if (m instanceof TheMahjongMeld.Kakan) {
            t.putString("type", "kakan");
        }
        t.put("tiles", MatchNbt.writeTileList(m.tiles()));
        return t;
    }

    private static TheMahjongMeld readMeld(CompoundTag t) {
        String type = t.getString("type");
        List<TheMahjongTile> tiles = MatchNbt.readTileList(t.getList("tiles", Tag.TAG_COMPOUND));
        switch (type) {
            case "chi" -> {
                return new TheMahjongMeld.Chi(tiles, t.getInt("claimedIdx"), 0, 0);
            }
            case "pon" -> {
                return new TheMahjongMeld.Pon(tiles, 0, 0, 0);
            }
            case "daiminkan" -> {
                return new TheMahjongMeld.Daiminkan(tiles, 0, 0, 0);
            }
            case "ankan" -> {
                return new TheMahjongMeld.Ankan(tiles);
            }
            case "kakan" -> {
                TheMahjongMeld.Pon pon = new TheMahjongMeld.Pon(tiles.subList(0, 3), 0, 0, 0);
                return new TheMahjongMeld.Kakan(pon, tiles.get(3));
            }
            default -> throw new IllegalArgumentException("unknown Chinese meld type " + type);
        }
    }

    // ── Win result ───────────────────────────────────────────────────────

    private static CompoundTag writeWinResult(ChineseWinResult w) {
        CompoundTag t = new CompoundTag();
        ListTag yaku = new ListTag();
        for (ChineseYaku y : w.yaku()) yaku.add(StringTag.valueOf(y.name()));
        t.put("yaku", yaku);
        t.putInt("fan", w.fan());
        t.putBoolean("fullWin", w.fullWin());
        t.putInt("points", w.points());
        t.putBoolean("tsumo", w.tsumo());
        if (w.winTile() != null) t.put("winTile", MatchNbt.writeTile(w.winTile()));
        int[] deltas = new int[w.pointDeltas().size()];
        for (int i = 0; i < deltas.length; i++) deltas[i] = w.pointDeltas().get(i);
        t.putIntArray("deltas", deltas);
        return t;
    }

    private static ChineseWinResult readWinResult(CompoundTag t) {
        List<ChineseYaku> yaku = new ArrayList<>();
        ListTag yt = t.getList("yaku", Tag.TAG_STRING);
        for (int i = 0; i < yt.size(); i++) yaku.add(ChineseYaku.valueOf(yt.getString(i)));
        int[] deltas = t.getIntArray("deltas");
        List<Integer> dl = new ArrayList<>(deltas.length);
        for (int d : deltas) dl.add(d);
        return new ChineseWinResult(yaku, t.getInt("fan"), t.getBoolean("fullWin"), t.getInt("points"),
                t.getBoolean("tsumo"),
                t.contains("winTile") ? MatchNbt.readTile(t.getCompound("winTile")) : null,
                dl);
    }
}
