package com.livingworld.brain;

import com.livingworld.util.Terrain;
import com.solegendary.reignofnether.building.BuildingPlacement;
import com.solegendary.reignofnether.building.BuildingServerEvents;
import com.solegendary.reignofnether.building.BuildingUtils;
import java.util.Random;
import javax.annotation.Nullable;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.phys.Vec3;

/**
 * Picks a placement site for a new building near a faction's capitol.
 *
 * <p>Strategy: try up to N random angles in a ring around {@code centre} at a
 * distance between {@code minRadius} and {@code maxRadius}. For each candidate
 * we:
 * <ol>
 *   <li>Project to the world-surface heightmap so the building sits on the
 *       ground (with the same {@code - 1} off-by-one correction we use for
 *       capitols).</li>
 *   <li>Reject positions already inside another building.</li>
 *   <li>Reject positions too close to any existing building, so capitols don't
 *       crowd each other and houses have breathing room.</li>
 * </ol>
 * If all attempts fail, return {@code null} and the brain will try again next
 * tick. This is deliberately stateless and cheap.
 */
public final class SiteFinder {

    /** How many ring positions to try before giving up for this tick. */
    public static final int MAX_ATTEMPTS = 24;

    /** Minimum distance between two building centres (blocks, squared distance check). */
    public static final int MIN_BUILDING_SEPARATION = 12;

    /**
     * Half-extent (in blocks) for the water-clearance check around a candidate
     * site. 10 covers the footprint of every brain-queued building — the
     * widest are barracks-class structures at ≈18 blocks square — plus a
     * small margin so a stockpile placed near a coast doesn't have its
     * corner submerged. Previously 7, which was tight for the larger
     * military buildings and caused intermittent submerged placements.
     */
    public static final int WATER_CHECK_RADIUS = 10;

    private static final Random RNG = new Random();

    private SiteFinder() {}

    /**
     * Find a valid placement site for a building near the given capitol centre.
     *
     * @param level       the overworld
     * @param centre      the capitol's ground-level centre
     * @param minRadius   minimum distance from the capitol centre (blocks)
     * @param maxRadius   maximum distance from the capitol centre (blocks)
     * @return a valid ground-level site, or {@code null} if none found
     */
    @Nullable
    public static BlockPos findNear(
        ServerLevel level,
        BlockPos centre,
        int minRadius,
        int maxRadius
    ) {
        int minSepSqr = MIN_BUILDING_SEPARATION * MIN_BUILDING_SEPARATION;

        for (int attempt = 0; attempt < MAX_ATTEMPTS; attempt++) {
            double angle = RNG.nextDouble() * 2.0 * Math.PI;
            int radius =
                minRadius + RNG.nextInt(Math.max(1, maxRadius - minRadius + 1));
            int x = centre.getX() + (int) Math.round(Math.cos(angle) * radius);
            int z = centre.getZ() + (int) Math.round(Math.sin(angle) * radius);

            // Reject sites where any part of the building's footprint overlaps
            // water/lava. Sampling a 2*radius+1 square covers the whole area
            // a typical brain building (house, farm, barracks) would occupy.
            if (
                Terrain.isLiquidInFootprint(level, x, z, WATER_CHECK_RADIUS)
            ) continue;

            BlockPos pos = new BlockPos(x, Terrain.groundY(level, x, z), z);

            if (BuildingUtils.isPosInsideAnyBuilding(false, pos)) continue;

            BuildingPlacement closest = BuildingUtils.findClosestBuilding(
                false,
                Vec3.atCenterOf(pos),
                b -> true
            );
            if (
                closest != null && closest.centrePos.distSqr(pos) < minSepSqr
            ) continue;

            return pos;
        }
        return null;
    }

    /**
     * Convenience overload using sensible defaults for village expansion
     * (15–28 blocks from the capitol centre).
     */
    @Nullable
    public static BlockPos findNearCapitol(
        ServerLevel level,
        BlockPos capitolCentre
    ) {
        return findNear(level, capitolCentre, 15, 28);
    }

    /** Returns true if there is at least one building owned by {@code ownerName}. */
    public static boolean hasAnyBuilding(String ownerName) {
        for (BuildingPlacement bp : BuildingServerEvents.getBuildings()) {
            if (bp.ownerName.equals(ownerName)) return true;
        }
        return false;
    }
}
