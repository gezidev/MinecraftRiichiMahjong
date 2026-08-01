package com.riichimahjongforge.chinesemahjong.client;

import com.mojang.logging.LogUtils;
import com.riichimahjongforge.chinesemahjong.ChineseDecisionRequest;
import com.riichimahjongforge.chinesemahjong.ChineseGameDriver;
import com.riichimahjongforge.chinesemahjong.ChineseMatchPhase;
import com.riichimahjongforge.chinesemahjong.ChinesePlayerAction;
import com.riichimahjongforge.chinesemahjong.ChinesePlayerInterface;
import com.riichimahjongforge.chinesemahjong.ChineseRoundState;
import com.riichimahjongforge.cuterenderer.InteractKey;
import com.riichimahjongforge.mahjongcore.MahjongTileItems;
import com.riichimahjongforge.mahjongtable.MahjongTableBlockEntity;
import com.themahjong.TheMahjongTile;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import org.slf4j.Logger;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/**
 * Human player for Chinese mahjong (大众麻将). Mirrors the riichi variant's
 * physical item delivery: a freshly drawn tile is taken from the table inventory
 * (or minted) and placed into the occupant's inventory; it is consumed when the
 * turn ends. Anti-dupe checks make discards without holding the tile fail.
 */
public class ChineseMahjongTableHumanPlayer implements ChinesePlayerInterface {

    private static final Logger LOGGER = LogUtils.getLogger();
    private final List<ChinesePlayerAction> queue = new ArrayList<>();
    private UUID occupant;

    // Delivery latches — persist across save/load so a reload mid-turn never
    // re-delivers (which would dupe items).
    private boolean drawnTileDeliveryAttempted;
    private boolean drawnTileDelivered;
    private TheMahjongTile pendingConsume;

    public void setOccupant(UUID uuid) { occupant = uuid; }

    /** True iff the drawn tile was delivered to the occupant's inventory at some
     *  point this AwaitingDiscard entry. Read by the BE to expose to the renderer. */
    public boolean drawnTileDelivered() { return drawnTileDelivered; }

    @Override
    public Optional<ChinesePlayerAction> chooseAction(ChineseDecisionRequest request, double deltaSeconds) {
        if (request.phase() instanceof ChineseMatchPhase.AwaitingQueYiMen) {
            return Optional.of(pickMissing(request));
        }
        if (request.phase() instanceof ChineseMatchPhase.AwaitingDraw ad) {
            return ad.seat() == request.seat()
                    ? Optional.of(new ChinesePlayerAction.Draw())
                    : Optional.empty();
        }
        if (request.phase() instanceof ChineseMatchPhase.AwaitingClaims) {
            // Nothing claimable → auto-pass so the game doesn't stall on a manual click.
            boolean onlyPass = true;
            for (ChinesePlayerAction a : request.legalActions()) {
                if (!(a instanceof ChinesePlayerAction.Pass)) { onlyPass = false; break; }
            }
            if (onlyPass) return Optional.of(new ChinesePlayerAction.Pass());
        }
        // Win actions take priority so a queued 自摸/荣和 always fires first,
        // even if a stale discard click is also queued.
        for (int i = 0; i < queue.size(); i++) {
            ChinesePlayerAction a = queue.get(i);
            if ((a instanceof ChinesePlayerAction.Tsumo || a instanceof ChinesePlayerAction.Ron)
                    && request.legalActions().contains(a)) {
                queue.remove(i);
                return Optional.of(a);
            }
        }
        for (int i = 0; i < queue.size(); i++) {
            ChinesePlayerAction a = queue.get(i);
            if (request.legalActions().contains(a)) {
                queue.remove(i);
                return Optional.of(a);
            }
        }
        return Optional.empty();
    }

    private static ChinesePlayerAction pickMissing(ChineseDecisionRequest request) {
        List<TheMahjongTile> hand = request.round().players().get(request.seat()).currentHand();
        int man = 0, pin = 0, sou = 0;
        for (TheMahjongTile t : hand) {
            switch (t.suit()) {
                case MANZU -> man++;
                case PINZU -> pin++;
                case SOUZU -> sou++;
                default -> {}
            }
        }
        TheMahjongTile.Suit miss;
        if (man <= pin && man <= sou) miss = TheMahjongTile.Suit.MANZU;
        else if (pin <= sou) miss = TheMahjongTile.Suit.PINZU;
        else miss = TheMahjongTile.Suit.SOUZU;
        return new ChinesePlayerAction.DeclareMissingSuit(miss);
    }

    // ---- anti-dupe / delivery ----------------------------------------------

    /**
     * Runs BEFORE {@code chineseDriver.advance} each tick. Clears a queued action
     * when the delivered drawn tile is no longer in the occupant's inventory
     * (moved/dropped) — otherwise the discard would dupe the item.
     */
    public void validateBeforeAdvance(ServerLevel level) {
        if (queue.isEmpty()) return;
        if (!drawnTileDelivered || pendingConsume == null || occupant == null) return;
        ServerPlayer sp = level.getPlayerByUUID(occupant) instanceof ServerPlayer s ? s : null;
        Item item = MahjongTileItems.itemForCode(MahjongTileItems.codeForTile(pendingConsume));
        if (sp == null || item == null || !inventoryContains(sp.getInventory(), item)) {
            queue.clear();
            if (sp != null) hint(sp, "riichi_mahjong_forge.hint.player.return_drawn");
        }
    }

    /**
     * Post-advance hook, runs after {@code chineseDriver.advance} in the same BE
     * tick. Consumes the delivered tile on phase exit, resets latches, and
     * delivers a freshly-drawn tile into the occupant's inventory. Returns true
     * when any sync-relevant latch changed.
     */
    public boolean tickAfterAdvance(ServerLevel level, MahjongTableBlockEntity table,
                                    int seatIndex, ChineseMatchPhase phase) {
        boolean ourDiscardTurn = phase instanceof ChineseMatchPhase.AwaitingDiscard ad
                && ad.seat() == seatIndex;
        boolean priorAttempted = drawnTileDeliveryAttempted;
        boolean priorDelivered = drawnTileDelivered;
        TheMahjongTile priorPending = pendingConsume;

        if (pendingConsume != null) {
            if (!ourDiscardTurn) {
                consumeFromOccupant(level, pendingConsume);
                pendingConsume = null;
            } else {
                TheMahjongTile activeDrawn = currentDrawnTile(table, seatIndex);
                if (activeDrawn != null && !activeDrawn.equals(pendingConsume)) {
                    // Kan → rinshan replacement: the previous drawn tile went into
                    // the kan, reset latches and re-deliver the new tile.
                    consumeFromOccupant(level, pendingConsume);
                    pendingConsume = null;
                    drawnTileDeliveryAttempted = false;
                    drawnTileDelivered = false;
                }
            }
        }

        if (!ourDiscardTurn) {
            drawnTileDeliveryAttempted = false;
            drawnTileDelivered = false;
            return changedSince(priorAttempted, priorDelivered, priorPending);
        }
        if (drawnTileDeliveryAttempted) {
            return changedSince(priorAttempted, priorDelivered, priorPending);
        }
        if (occupant == null) return changedSince(priorAttempted, priorDelivered, priorPending);
        Player p = level.getPlayerByUUID(occupant);
        if (!(p instanceof ServerPlayer serverPlayer)) {
            return changedSince(priorAttempted, priorDelivered, priorPending);
        }
        if (table.chineseDriver() == null) return changedSince(priorAttempted, priorDelivered, priorPending);
        ChineseRoundState round = table.chineseDriver().match().currentRound();
        if (round == null || round.state() != ChineseRoundState.State.AWAITING_DISCARD
                || round.currentTurnSeat() != seatIndex) {
            return changedSince(priorAttempted, priorDelivered, priorPending);
        }
        TheMahjongTile drawn = round.activeTile();
        if (drawn == null || round.lastDrawSeat() != seatIndex) {
            return changedSince(priorAttempted, priorDelivered, priorPending);
        }
        Item targetItem = MahjongTileItems.itemForCode(MahjongTileItems.codeForTile(drawn));
        if (targetItem == null) return changedSince(priorAttempted, priorDelivered, priorPending);

        ItemStack consumed = table.takeOneTileFromTableInventory(targetItem);
        boolean fromTable = !consumed.isEmpty();
        if (!fromTable) {
            if (!table.mintTilesFromNothing()) {
                drawnTileDeliveryAttempted = true;
                return changedSince(priorAttempted, priorDelivered, priorPending);
            }
            consumed = new ItemStack(targetItem);
        }
        boolean given = tryGiveToPlayer(serverPlayer, consumed, targetItem, table.deliverToMainHand());
        if (!given && fromTable) {
            table.restoreToTableInventory(consumed, targetItem);
        }
        drawnTileDeliveryAttempted = true;
        if (given) {
            drawnTileDelivered = true;
            pendingConsume = drawn;
        }
        return changedSince(priorAttempted, priorDelivered, priorPending);
    }

    /** The drawn tile for {@code seat} right now, or null (not a draw / not their turn). */
    private static TheMahjongTile currentDrawnTile(MahjongTableBlockEntity table, int seat) {
        if (table.chineseDriver() == null) return null;
        ChineseRoundState round = table.chineseDriver().match().currentRound();
        if (round == null || round.state() != ChineseRoundState.State.AWAITING_DISCARD
                || round.currentTurnSeat() != seat || round.lastDrawSeat() != seat) {
            return null;
        }
        return round.activeTile();
    }

    private boolean changedSince(boolean priorAttempted, boolean priorDelivered,
                                 TheMahjongTile priorPending) {
        return priorAttempted != drawnTileDeliveryAttempted
                || priorDelivered != drawnTileDelivered
                || !Objects.equals(priorPending, pendingConsume);
    }

    // ---- persistence --------------------------------------------------------

    /** Persistence — write transient turn-state into a tag for round-trip. */
    public void writeNbt(CompoundTag tag) {
        tag.putBoolean("DeliveryAttempted", drawnTileDeliveryAttempted);
        tag.putBoolean("Delivered", drawnTileDelivered);
        if (pendingConsume != null) {
            tag.putString("PendingConsumeSuit", pendingConsume.suit().name());
            tag.putInt("PendingConsumeRank", pendingConsume.rank());
            tag.putBoolean("PendingConsumeRedDora", pendingConsume.redDora());
        }
    }

    /** Persistence — read state previously written by {@link #writeNbt}. */
    public void readNbt(CompoundTag tag) {
        drawnTileDeliveryAttempted = tag.getBoolean("DeliveryAttempted");
        drawnTileDelivered = tag.getBoolean("Delivered");
        if (tag.contains("PendingConsumeSuit")) {
            try {
                TheMahjongTile.Suit suit = TheMahjongTile.Suit.valueOf(tag.getString("PendingConsumeSuit"));
                int rank = tag.getInt("PendingConsumeRank");
                boolean red = tag.getBoolean("PendingConsumeRedDora");
                pendingConsume = new TheMahjongTile(suit, rank, red);
            } catch (IllegalArgumentException ignored) {
                pendingConsume = null;
            }
        } else {
            pendingConsume = null;
        }
    }

    // ---- click routing -------------------------------------------------------

    /** Click routing: hand tile → discard; button → queued action. */
    public void onCuteClick(InteractKey key, ChineseGameDriver driver, int seatIndex, ServerPlayer sender) {
        if (!(key instanceof InteractKey.SeatSlot ss)) return;
        if (ss.seat() != seatIndex) return;
        ChineseRoundState round = driver.match().currentRound();
        if (round == null) return;
        if (ss.area() == InteractKey.SeatSlot.AREA_HAND) {
            List<TheMahjongTile> hand = round.handDisplayOrder(seatIndex);
            int idx = ss.index();
            if (idx < 0 || idx >= hand.size()) return;
            tryDiscard(sender, hand.get(idx), driver, seatIndex);
        } else if (ss.area() == InteractKey.SeatSlot.AREA_BUTTON) {
            List<ChinesePlayerAction> buttons = chineseTableButtons(driver, seatIndex);
            int idx = ss.index();
            if (idx < 0 || idx >= buttons.size()) {
                LOGGER.info("CuteClick DEBUG: button idx {} out of range ({} buttons)", idx, buttons.size());
                return;
            }
            tryQueueButtonAction(sender, buttons.get(idx), driver, seatIndex);
        }
    }

    /** RMB: pass a claim, or discard the just-drawn tile. */
    public void onTableRightClick(ChineseGameDriver driver, ChineseMatchPhase phase, int seatIndex, ServerPlayer sender) {
        if (phase instanceof ChineseMatchPhase.AwaitingClaims) {
            queue.add(new ChinesePlayerAction.Pass());
        } else if (phase instanceof ChineseMatchPhase.AwaitingDiscard ad && ad.seat() == seatIndex) {
            ChineseRoundState round = driver.match().currentRound();
            if (round != null && round.activeTile() != null) {
                tryDiscard(sender, round.activeTile(), driver, seatIndex);
            }
        }
    }

    /**
     * Discard path with legality + anti-dupe checks. Refuses when the phase isn't
     * {@code AwaitingDiscard{us}}, the tile isn't a legal discard, or — when the
     * drawn tile was delivered — the occupant no longer holds the tile item.
     */
    private void tryDiscard(ServerPlayer sender, TheMahjongTile tile,
                            ChineseGameDriver driver, int seatIndex) {
        ChineseRoundState round = driver.match().currentRound();
        if (round == null) return;
        if (round.state() != ChineseRoundState.State.AWAITING_DISCARD
                || round.currentTurnSeat() != seatIndex) {
            hint(sender, "riichi_mahjong_forge.hint.player.wait_turn");
            return;
        }
        ChinesePlayerAction candidate = new ChinesePlayerAction.Discard(tile);
        if (!driver.legalActions(seatIndex).contains(candidate)) {
            hint(sender, "riichi_mahjong_forge.hint.player.illegal_discard");
            return;
        }
        if (drawnTileDelivered && round.activeTile() != null && pendingConsume != null
                && pendingConsume.equals(round.activeTile())) {
            Item drawnItem = MahjongTileItems.itemForCode(MahjongTileItems.codeForTile(round.activeTile()));
            if (drawnItem == null || !inventoryContains(sender.getInventory(), drawnItem)) {
                hint(sender, "riichi_mahjong_forge.hint.player.retrieve_drawn_to_discard");
                return;
            }
            pendingConsume = round.activeTile();
        }
        queue.add(candidate);
    }

    /**
     * Universal queue path for any action produced by a button click. During
     * {@code AwaitingDiscard{us}} with a delivered drawn tile, every action ends
     * our hold on that tile (Tsumo / Ankan / Kakan) — require the item in inv,
     * then arm pendingConsume. Claim-window actions just get queued.
     */
    private void tryQueueButtonAction(ServerPlayer sender, ChinesePlayerAction action,
                                      ChineseGameDriver driver, int seatIndex) {
        ChineseRoundState round = driver.match().currentRound();
        if (round == null) {
            queue.add(action);
            return;
        }
        boolean ourDiscardTurn =
                round.state() == ChineseRoundState.State.AWAITING_DISCARD
                        && round.currentTurnSeat() == seatIndex;
        if (ourDiscardTurn && drawnTileDelivered && round.activeTile() != null
                && round.lastDrawSeat() == seatIndex) {
            Item drawnItem = MahjongTileItems.itemForCode(MahjongTileItems.codeForTile(round.activeTile()));
            if (drawnItem != null && !inventoryContains(sender.getInventory(), drawnItem)) {
                hint(sender, "riichi_mahjong_forge.hint.player.retrieve_drawn_first");
                return;
            }
            pendingConsume = round.activeTile();
        }
        queue.add(action);
    }

    /**
     * Unified button list shared by the renderer and the click handler so the
     * index → action mapping always agrees. Order: 荣和/自摸, 杠, 碰, 吃, 过.
     */
    public static List<ChinesePlayerAction> chineseTableButtons(ChineseGameDriver driver, int seat) {
        List<ChinesePlayerAction> legal = driver.legalActions(seat);
        List<ChinesePlayerAction> out = new ArrayList<>(legal.size());
        boolean hasClaim = false;
        for (ChinesePlayerAction a : legal) {
            if (!(a instanceof ChinesePlayerAction.Pass)) { hasClaim = true; break; }
        }
        for (ChinesePlayerAction a : legal) {
            if (a instanceof ChinesePlayerAction.Ron || a instanceof ChinesePlayerAction.Tsumo) out.add(a);
        }
        for (ChinesePlayerAction a : legal) {
            if (a instanceof ChinesePlayerAction.Kakan
                    || a instanceof ChinesePlayerAction.Ankan
                    || a instanceof ChinesePlayerAction.Daiminkan) out.add(a);
        }
        for (ChinesePlayerAction a : legal) {
            if (a instanceof ChinesePlayerAction.Pon) out.add(a);
        }
        for (ChinesePlayerAction a : legal) {
            if (a instanceof ChinesePlayerAction.Chi) out.add(a);
        }
        if (hasClaim) {
            for (ChinesePlayerAction a : legal) {
                if (a instanceof ChinesePlayerAction.Pass) out.add(a);
            }
        }
        return out;
    }

    // ---- inventory helpers (mirrored from the riichi human player) ----------

    /** Puts {@code stack} in the player's inventory: the selected hotbar slot
     *  when {@code mainHand} is true (and that slot is empty), else anywhere in
     *  the main 36-slot inventory. */
    private static boolean tryGiveToPlayer(ServerPlayer player, ItemStack stack,
                                           Item targetItem, boolean mainHand) {
        Inventory inv = player.getInventory();
        if (mainHand) {
            int slot = inv.selected;
            if (!inv.getItem(slot).isEmpty()) return false;
            inv.setItem(slot, stack);
            return true;
        }
        if (!hasRoomForOneItem(inv, targetItem)) return false;
        return inv.add(stack) && stack.isEmpty();
    }

    private static boolean hasRoomForOneItem(Inventory inv, Item targetItem) {
        for (int slot = 0; slot < 36; slot++) {
            ItemStack here = inv.getItem(slot);
            if (here.isEmpty()) return true;
            if (here.getItem() == targetItem && here.getCount() < here.getMaxStackSize()) {
                return true;
            }
        }
        return false;
    }

    private static boolean inventoryContains(Inventory inv, Item targetItem) {
        for (int slot = 0; slot < inv.getContainerSize(); slot++) {
            if (inv.getItem(slot).getItem() == targetItem) return true;
        }
        return false;
    }

    /** Removes one {@code tile} item from the occupant's inventory (selected
     *  slot first, then anywhere). */
    private void consumeFromOccupant(ServerLevel level, TheMahjongTile tile) {
        if (occupant == null) return;
        ServerPlayer sp = level.getPlayerByUUID(occupant) instanceof ServerPlayer s ? s : null;
        if (sp == null) return;
        Item item = MahjongTileItems.itemForCode(MahjongTileItems.codeForTile(tile));
        if (item == null) return;
        Inventory inv = sp.getInventory();
        for (int slot = 0; slot < inv.getContainerSize(); slot++) {
            ItemStack here = inv.getItem(slot);
            if (here.getItem() == item) {
                here.shrink(1);
                return;
            }
        }
    }

    private static void hint(ServerPlayer p, String key) {
        p.displayClientMessage(net.minecraft.network.chat.Component.translatable(key), true);
    }
}
