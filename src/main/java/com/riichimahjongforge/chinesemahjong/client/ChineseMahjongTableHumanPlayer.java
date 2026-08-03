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
    /** Fingerprint of the phase the queued actions were submitted in — used to
     *  drop stale actions (e.g. a Pon queued in a claim window must not fire in
     *  the next claim window) when the phase changes. */
    private String queuedForPhase = "";

    // Delivery latches — persist across save/load so a reload mid-turn never
    // re-delivers (which would dupe items).
    private boolean drawnTileDeliveryAttempted;
    private boolean drawnTileDelivered;
    private TheMahjongTile pendingConsume;

    public void setOccupant(UUID uuid) { occupant = uuid; }

    /** True iff the drawn tile was delivered to the occupant's inventory at some
     *  point this AwaitingDiscard entry. Read by the BE to expose to the renderer. */
    public boolean drawnTileDelivered() { return drawnTileDelivered; }

    private static String phaseKey(ChineseMatchPhase phase) {
        if (phase instanceof ChineseMatchPhase.AwaitingDiscard ad) return "discard:" + ad.seat();
        if (phase instanceof ChineseMatchPhase.AwaitingClaims ac) return "claim:" + ac.pendingSeats();
        if (phase instanceof ChineseMatchPhase.AwaitingQueYiMen) return "queyimen";
        return phase.getClass().getSimpleName();
    }

    private void queueAction(ChinesePlayerAction a, ChineseMatchPhase phase) {
        String key = phaseKey(phase);
        if (!key.equals(queuedForPhase)) {
            queue.clear();
            queuedForPhase = key;
        }
        // 去重：同一动作重复点击（如连续点碰）只入队一次，避免后续幽灵触发。
        for (ChinesePlayerAction q : queue) {
            if (actionsEqual(q, a)) return;
        }
        queue.add(a);
    }

    private static boolean actionsEqual(ChinesePlayerAction a, ChinesePlayerAction b) {
        if (a instanceof ChinesePlayerAction.Discard d1 && b instanceof ChinesePlayerAction.Discard d2) {
            return d1.tile().matchesSuitRank(d2.tile());
        }
        if (a instanceof ChinesePlayerAction.Pon p1 && b instanceof ChinesePlayerAction.Pon p2) {
            return p1.own().size() == p2.own().size();
        }
        if (a instanceof ChinesePlayerAction.Chi c1 && b instanceof ChinesePlayerAction.Chi c2) {
            return c1.own().size() == c2.own().size();
        }
        if (a instanceof ChinesePlayerAction.DeclareMissingSuit m1
                && b instanceof ChinesePlayerAction.DeclareMissingSuit m2) {
            return m1.missing() == m2.missing();
        }
        if (a instanceof ChinesePlayerAction.Tsumo && b instanceof ChinesePlayerAction.Tsumo) return true;
        if (a instanceof ChinesePlayerAction.Ron && b instanceof ChinesePlayerAction.Ron) return true;
        if (a instanceof ChinesePlayerAction.Pass && b instanceof ChinesePlayerAction.Pass) return true;
        if (a instanceof ChinesePlayerAction.Draw && b instanceof ChinesePlayerAction.Draw) return true;
        return false;
    }

    @Override
    public Optional<ChinesePlayerAction> chooseAction(ChineseDecisionRequest request, double deltaSeconds) {
        if (request.phase() instanceof ChineseMatchPhase.AwaitingQueYiMen) {
            // 定缺必须由玩家手动选择（点 缺万/缺筒/缺条 按钮）；未选择则等待，
            // 否则自动选会让人在开局完全不知道为何只能打某花色。
            for (int i = 0; i < queue.size(); i++) {
                ChinesePlayerAction a = queue.get(i);
                if (a instanceof ChinesePlayerAction.DeclareMissingSuit
                        && request.legalActions().contains(a)) {
                    queue.remove(i);
                    return Optional.of(a);
                }
            }
            return Optional.empty();
        }
        if (request.phase() instanceof ChineseMatchPhase.AwaitingDraw ad) {
            return ad.seat() == request.seat()
                    ? Optional.of(new ChinesePlayerAction.Draw())
                    : Optional.empty();
        }
        // Drop stale queued actions when the phase changed since they were
        // submitted (a Pon from a finished claim window must not fire in the
        // next one).
        String nowKey = phaseKey(request.phase());
        if (!nowKey.equals(queuedForPhase)) {
            queue.clear();
            queuedForPhase = nowKey;
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
            // 与服务端渲染一致：若摸到的牌已投递到玩家物品栏，从显示中剔除再映射点击。
            List<TheMahjongTile> hand = displayHand(round, seatIndex, drawnTileDelivered);
            int idx = ss.index();
            if (idx < 0 || idx >= hand.size()) return;
            tryDiscard(sender, hand.get(idx), driver, seatIndex);
        } else if (ss.area() == InteractKey.SeatSlot.AREA_BUTTON) {
            List<ChinesePlayerAction> buttons = chineseTableButtons(driver, seatIndex);
            int idx = ss.index();
            if (idx < 0 || idx >= buttons.size()) return;
            tryQueueButtonAction(sender, buttons.get(idx), driver, seatIndex);
        }
    }

    /** 显示用手牌列表：排序后，若刚摸的牌已投递到物品栏则按值剔除（显示 13 张，
     *  与玩家手里物理持有一致）。点击索引与渲染一一对应。 */
    public static List<TheMahjongTile> displayHand(ChineseRoundState round, int seat, boolean drawnDelivered) {
        List<TheMahjongTile> hand = new ArrayList<>(round.handDisplayOrder(seat));
        if (drawnDelivered
                && round.state() == ChineseRoundState.State.AWAITING_DISCARD
                && round.lastDrawSeat() == seat
                && round.activeTile() != null) {
            TheMahjongTile drawn = round.activeTile();
            hand.removeIf(t -> t.matchesSuitRank(drawn));
        }
        return hand;
    }

    /** RMB: discard the just-drawn tile. Note: we deliberately do NOT auto-pass a
     *  claim on RMB — a right-click that misses a button (hover failed) would
     *  silently pass, which reads as "the button doesn't work". Use the 过 button. */
    public void onTableRightClick(ChineseGameDriver driver, ChineseMatchPhase phase, int seatIndex, ServerPlayer sender) {
        if (phase instanceof ChineseMatchPhase.AwaitingDiscard ad && ad.seat() == seatIndex) {
            ChineseRoundState round = driver.match().currentRound();
            // 只允许打"刚摸的牌"（activeTile 是摸牌，且确实是本座摸的）。
            if (round != null && round.activeTile() != null && round.lastDrawSeat() == seatIndex) {
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
        queueAction(candidate, driver.currentPhase());
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
            queueAction(action, driver.currentPhase());
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
        queueAction(action, driver.currentPhase());
    }

    /**
     * Unified button list shared by the renderer and the click handler so the
     * index → action mapping always agrees. Order: 荣和/自摸, 杠, 碰, 吃, 过.
     */
    public static List<ChinesePlayerAction> chineseTableButtons(ChineseGameDriver driver, int seat) {
        List<ChinesePlayerAction> legal = driver.legalActions(seat);
        List<ChinesePlayerAction> out = new ArrayList<>(legal.size());
        // 定缺（四川开局）：缺万/缺筒/缺条 三选一，排在最前。
        for (ChinesePlayerAction a : legal) {
            if (a instanceof ChinesePlayerAction.DeclareMissingSuit) out.add(a);
        }
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
