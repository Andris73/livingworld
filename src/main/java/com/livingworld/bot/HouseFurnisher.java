package com.livingworld.bot;

import com.solegendary.reignofnether.building.BuildingPlacement;
import com.solegendary.reignofnether.building.BuildingServerEvents;
import com.solegendary.reignofnether.building.buildings.monsters.HauntedHouse;
import com.solegendary.reignofnether.building.buildings.piglins.PortalBasic;
import com.solegendary.reignofnether.building.buildings.villagers.VillagerHouse;
import javax.annotation.Nullable;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;

/**
 * Helpers for identifying habitable buildings and finding their interiors.
 *
 * <p>This used to <em>furnish</em> houses at runtime — punching a door hole
 * in a wall, dropping a bed in, placing a chest seeded with the village
 * loot table — because RoN's house NBTs ship as sealed boxes with no
 * interior fittings. That approach has been retired in favour of editing
 * the structure NBTs directly:
 * <ul>
 *   <li>{@code reignofnether/src/main/resources/data/reignofnether/structures/villager_house.nbt}</li>
 *   <li>{@code reignofnether/src/main/resources/data/reignofnether/structures/haunted_house.nbt}</li>
 *   <li>{@code reignofnether/src/main/resources/data/reignofnether/structures/portal_basic.nbt}</li>
 * </ul>
 * (plus mirror copies under {@code assets/reignofnether/structures/}). Doors,
 * beds, and chests should go into the schematics. Chests can be configured
 * with the vanilla village loot table via the chest block-entity NBT:
 * <pre>
 *   {LootTable:"minecraft:chests/village/village_plains_house",LootTableSeed:0L}
 * </pre>
 * which rolls items on first open.
 *
 * <p>What stays here is the building-classification and interior-lookup
 * helpers used by {@link NightShelter} to route workers indoors after dark.
 * The classification (only {@code VillagerHouse}, {@code HauntedHouse}, and
 * {@code PortalBasic} count as habitable) is the same as the old furnisher
 * used; only the post-construction block-editing has been removed.
 */
public final class HouseFurnisher {

    private HouseFurnisher() {}

    /** True iff this building type is a residential structure workers can shelter in. */
    public static boolean isHabitable(BuildingPlacement bp) {
        var b = bp.getBuilding();
        return (
            b instanceof VillagerHouse ||
            b instanceof HauntedHouse ||
            b instanceof PortalBasic
        );
    }

    /**
     * Find the interior floor of a habitable house owned by {@code ownerName},
     * preferring the building closest to {@code from}. Returns {@code null}
     * if no habitable building of this owner exists in the world.
     *
     * <p>Used by {@link NightShelter} to dispatch workers indoors after
     * sundown. The returned position is the building's centre at floor
     * level — RoN's nav goal handles the actual pathing (and walks through
     * whatever door you added to the schematic).
     */
    @Nullable
    public static BlockPos nearestInterior(
        ServerLevel level,
        String ownerName,
        BlockPos from
    ) {
        BuildingPlacement best = null;
        double bestDistSqr = Double.MAX_VALUE;
        for (BuildingPlacement bp : BuildingServerEvents.getBuildings()) {
            if (!bp.ownerName.equals(ownerName)) continue;
            if (!bp.isBuilt) continue;
            if (!isHabitable(bp)) continue;
            double d = bp.centrePos.distSqr(from);
            if (d < bestDistSqr) {
                bestDistSqr = d;
                best = bp;
            }
        }
        if (best == null) return null;
        return new BlockPos(
            best.centrePos.getX(),
            best.minCorner.getY() + 1,
            best.centrePos.getZ()
        );
    }
}
