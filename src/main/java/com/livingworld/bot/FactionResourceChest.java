package com.livingworld.bot;

import com.livingworld.LivingWorld;
import com.livingworld.reputation.RepSource;
import com.livingworld.reputation.ReputationManager;
import com.solegendary.reignofnether.building.BuildingBlock;
import com.solegendary.reignofnether.building.BuildingPlacement;
import com.solegendary.reignofnether.building.BuildingServerEvents;
import com.solegendary.reignofnether.building.buildings.piglins.PortalCivilian;
import com.solegendary.reignofnether.building.buildings.shared.AbstractStockpile;
import com.solegendary.reignofnether.faction.Faction;
import com.solegendary.reignofnether.player.PlayerServerEvents;
import com.solegendary.reignofnether.resources.Resources;
import com.solegendary.reignofnether.resources.ResourcesServerEvents;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import javax.annotation.Nullable;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.ChestBlockEntity;
import net.minecraft.world.level.block.state.BlockState;

/**
 * Maintains a bidirectional sync between a faction's abstract resource pool
 * and the physical chest block(s) inside their storage buildings.
 *
 * <p>Each storage building ({@link AbstractStockpile} subclasses and
 * {@link PortalCivilian}) has a {@code minecraft:chest} embedded in its
 * structure NBT. This class:
 * <ul>
 *   <li>Reads that chest's contents every sync cycle and compares to the
 *       previous snapshot. If items disappeared (player theft), the delta
 *       is deducted from the resource pool and a rep penalty is applied to
 *       whoever was last seen opening the chest.</li>
 *   <li>Writes the current pool values back into the chest so the visual
 *       representation stays accurate: wheat=FOOD, oak_log=WOOD,
 *       raw_iron=ORE, each occupying 9 slots (max 576 units displayed).</li>
 * </ul>
 *
 * <p>Theft detection relies on comparing snapshots, not container events,
 * so it correctly handles items taken by any means (left-click drag,
 * shift-click, hotkey swap, etc.).
 */
public final class FactionResourceChest {

    // ---------------------------------------------------------- display layout

    /** Each resource type occupies this many chest slots (9 × 64 = 576 max). */
    private static final int SLOTS_PER_RESOURCE = 9;

    private static final int FOOD_START = 0;
    private static final int WOOD_START = 9;
    private static final int ORE_START = 18;

    private static final Item FOOD_ITEM = Items.WHEAT;
    private static final Item WOOD_ITEM = Items.OAK_LOG;
    private static final Item ORE_ITEM = Items.RAW_IRON;

    /**
     * Rep lost per item stolen. At 3.0, stealing a full stack of 64 wheat
     * costs -192 rep, dropping a Neutral player straight into Suspicious; a
     * second stack pushes them into Denounced (Hostile). Scale up to make
     * theft more punishing, down to make it more forgiving.
     */
    public static double REP_PER_STOLEN_ITEM = 3.0;

    /** Hard cap so a single theft event can’t exceed this penalty. */
    private static final int MAX_STEAL_REP_PER_EVENT = -500;

    /**
     * After a theft event we leave the chest empty for this many brain
     * ticks (each ~5 s) before resuming the pool → chest refill. Without
     * this the chest visually re-stocks on the very next sync — since the
     * pool is the source of truth and the bot's pool typically has
     * plenty more than the chest can display — letting a player open it
     * again immediately and steal another full chest's worth.
     *
     * <p>6 brain ticks ≈ 30 seconds, long enough to be inconvenient but
     * short enough that legitimate gameplay (workers depositing into the
     * stockpile) keeps the visual in sync most of the time.
     */
    private static final int REFILL_COOLDOWN_TICKS = 6;

    // ----------------------------------------------------------- internal state

    /**
     * Snapshot of displayed item counts for each chest ({@code pos.asLong()} key).
     * {@code int[0]} = food items, {@code [1]} = wood, {@code [2]} = ore.
     */
    private static final Map<Long, int[]> LAST_SNAPSHOT =
        new ConcurrentHashMap<>();

    /** Last player UUID to open a given chest, used for theft attribution. */
    public static final Map<Long, UUID> LAST_OPENER = new ConcurrentHashMap<>();

    /**
     * Per-chest refill cooldown: {@code chestPos → remaining brain ticks
     * before we resume pool→chest refill}. Decremented each sync cycle.
     * Set to {@link #REFILL_COOLDOWN_TICKS} whenever theft is detected so
     * the chest stays empty long enough that a player can't just re-open
     * and steal again.
     */
    private static final Map<Long, Integer> REFILL_COOLDOWN =
        new ConcurrentHashMap<>();

    private FactionResourceChest() {}

    // ----------------------------------------------------------------- tick

    /**
     * Called every brain tick (5 s) for each bot. Syncs all storage
     * buildings owned by this bot.
     */
    public static void tick(ServerLevel level, FactionBot bot) {
        Resources pool = findResources(bot.name());
        if (pool == null) return;

        for (BuildingPlacement bp : BuildingServerEvents.getBuildings()) {
            if (!bp.ownerName.equals(bot.name())) continue;
            if (!bp.isBuilt) continue;
            if (!isStorageBuilding(bp)) continue;

            syncBuilding(level, bot, bp, pool);
        }
    }

    // ----------------------------------------------------------- core sync

    private static void syncBuilding(
        ServerLevel level,
        FactionBot bot,
        BuildingPlacement bp,
        Resources pool
    ) {
        BlockPos chestPos = findChestPos(level, bp);
        if (chestPos == null) return;

        BlockEntity be = level.getBlockEntity(chestPos);
        if (!(be instanceof ChestBlockEntity chest)) return;

        long key = chestPos.asLong();

        // 1. Read current chest state
        int curFood = countInSlots(chest, FOOD_ITEM, FOOD_START);
        int curWood = countInSlots(chest, WOOD_ITEM, WOOD_START);
        int curOre = countInSlots(chest, ORE_ITEM, ORE_START);

        // 2. Compare to last snapshot — detect items removed
        int[] last = LAST_SNAPSHOT.get(key);
        boolean stolenThisTick = false;
        if (last != null) {
            int deltaFood = curFood - last[0];
            int deltaWood = curWood - last[1];
            int deltaOre = curOre - last[2];

            boolean stolen = deltaFood < 0 || deltaWood < 0 || deltaOre < 0;
            if (stolen) {
                stolenThisTick = true;

                // Deduct from resource pool
                ResourcesServerEvents.addSubtractResources(
                    new Resources(
                        bot.name(),
                        Math.min(0, deltaFood),
                        Math.min(0, deltaWood),
                        Math.min(0, deltaOre)
                    )
                );

                // Scale rep penalty by how much was stolen.
                int totalStolen =
                    Math.abs(Math.min(0, deltaFood)) +
                    Math.abs(Math.min(0, deltaWood)) +
                    Math.abs(Math.min(0, deltaOre));
                int repDelta = Math.max(
                    MAX_STEAL_REP_PER_EVENT,
                    -(int) Math.ceil(totalStolen * REP_PER_STOLEN_ITEM)
                );

                UUID openerUUID = LAST_OPENER.get(key);
                if (openerUUID != null) {
                    ReputationManager.adjustReputation(
                        level,
                        openerUUID,
                        bot.faction,
                        repDelta,
                        RepSource.STEAL
                    );
                    LivingWorld.LOGGER.info(
                        "[FactionResourceChest] {} stole {} items from {} → {} rep",
                        openerUUID,
                        totalStolen,
                        bot.name(),
                        repDelta
                    );
                } else {
                    LivingWorld.LOGGER.warn(
                        "[FactionResourceChest] {} items stolen from {} but no opener attributed",
                        totalStolen,
                        bot.name()
                    );
                }

                // Arm the refill cooldown: chest will stay empty for the
                // next REFILL_COOLDOWN_TICKS brain ticks, preventing the
                // "open / steal / wait 5s / repeat" exploit.
                REFILL_COOLDOWN.put(key, REFILL_COOLDOWN_TICKS);
            }
        }

        // 3. Refill cooldown: if armed (theft happened recently), force the
        //    chest to display zero items this tick and decrement the timer.
        //    Otherwise write pool → chest as normal.
        int dispFood;
        int dispWood;
        int dispOre;
        Integer cooldownLeft = REFILL_COOLDOWN.get(key);
        if (stolenThisTick || (cooldownLeft != null && cooldownLeft > 0)) {
            dispFood = 0;
            dispWood = 0;
            dispOre = 0;
            if (!stolenThisTick) {
                // Decrement only on ticks where we didn't *just* arm it.
                int remaining = cooldownLeft - 1;
                if (remaining <= 0) REFILL_COOLDOWN.remove(key);
                else REFILL_COOLDOWN.put(key, remaining);
            }
        } else {
            // Normal refill from pool (caps at SLOTS_PER_RESOURCE × 64 per type)
            dispFood = Math.min(
                Math.max(0, pool.food),
                SLOTS_PER_RESOURCE * 64
            );
            dispWood = Math.min(
                Math.max(0, pool.wood),
                SLOTS_PER_RESOURCE * 64
            );
            dispOre = Math.min(Math.max(0, pool.ore), SLOTS_PER_RESOURCE * 64);
        }

        boolean changed =
            last == null ||
            dispFood != last[0] ||
            dispWood != last[1] ||
            dispOre != last[2];
        if (changed) {
            fillSlots(chest, FOOD_ITEM, FOOD_START, dispFood);
            fillSlots(chest, WOOD_ITEM, WOOD_START, dispWood);
            fillSlots(chest, ORE_ITEM, ORE_START, dispOre);
            chest.setChanged();
        }

        // 4. Update snapshot
        LAST_SNAPSHOT.put(key, new int[] { dispFood, dispWood, dispOre });
    }

    // --------------------------------------------------------- helpers

    /** True if the building type uses a chest to display faction resources. */
    private static boolean isStorageBuilding(BuildingPlacement bp) {
        var b = bp.getBuilding();
        return b instanceof AbstractStockpile || b instanceof PortalCivilian;
    }

    /**
     * Scan the building's placed blocks for the first {@code minecraft:chest}.
     * Returns the world position of that chest, or {@code null} if none found.
     */
    @Nullable
    private static BlockPos findChestPos(
        ServerLevel level,
        BuildingPlacement bp
    ) {
        for (BuildingBlock bb : bp.getBlocks()) {
            BlockPos p = bb.getBlockPos();
            BlockState state = level.getBlockState(p);
            if (
                state.getBlock() == net.minecraft.world.level.block.Blocks.CHEST
            ) {
                return p;
            }
        }
        return null;
    }

    /** Count total items of the given type in {@code count} consecutive slots from {@code start}. */
    private static int countInSlots(
        ChestBlockEntity chest,
        Item item,
        int start
    ) {
        int total = 0;
        for (int i = start; i < start + SLOTS_PER_RESOURCE; i++) {
            ItemStack s = chest.getItem(i);
            if (!s.isEmpty() && s.getItem() == item) total += s.getCount();
        }
        return total;
    }

    /** Fill {@code count} consecutive slots starting at {@code start} with stacks of {@code item}. */
    private static void fillSlots(
        ChestBlockEntity chest,
        Item item,
        int start,
        int total
    ) {
        int remaining = total;
        for (int i = start; i < start + SLOTS_PER_RESOURCE; i++) {
            if (remaining > 0) {
                int stackSize = Math.min(remaining, item.getMaxStackSize());
                chest.setItem(i, new ItemStack(item, stackSize));
                remaining -= stackSize;
            } else {
                chest.setItem(i, ItemStack.EMPTY);
            }
        }
    }

    @Nullable
    private static Resources findResources(String ownerName) {
        for (Resources r : ResourcesServerEvents.resourcesList) {
            if (r.ownerName.equals(ownerName)) return r;
        }
        return null;
    }

    /** Clear all caches (called on server stop). */
    public static void clearCache() {
        LAST_SNAPSHOT.clear();
        LAST_OPENER.clear();
        REFILL_COOLDOWN.clear();
    }
}
