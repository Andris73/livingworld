package com.livingworld.util;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.tags.BlockTags;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.levelgen.Heightmap;

/**
 * Small terrain-inspection helpers used by site selection and worker spawning.
 *
 * <p>The two most important checks here are:
 * <ul>
 *   <li>{@link #groundY} — projects a (x, z) column to the actual top solid
 *       block, accounting for the off-by-one of {@code WORLD_SURFACE_WG}
 *       (which returns the first <em>air</em> block above).</li>
 *   <li>{@link #isLiquidAt} — returns true if the surface at this column is
 *       water, lava, or any other fluid. Brain projects must never place on
 *       liquid (RoN units can't reliably reach buildings standing in water)
 *       and worker spawns should avoid it too.</li>
 * </ul>
 */
public final class Terrain {

    private Terrain() {}

    /**
     * Topmost real-ground Y at the given column — i.e. the first solid,
     * non-pass-through block from the heightmap going down.
     *
     * <p>The naive {@code Heightmap.WORLD_SURFACE_WG - 1} answer counts
     * <em>leaves and logs as surface</em>, so a tree at this column would make
     * us return the tree-top Y. That's how we ended up with a house sitting on
     * top of a tree. To get real ground we scan down past any:
     * <ul>
     *   <li>leaves ({@code BlockTags.LEAVES})</li>
     *   <li>logs ({@code BlockTags.LOGS})</li>
     *   <li>replaceable blocks — tall grass, snow layer, ferns, flowers,
     *       saplings, etc. (anything for which
     *       {@link BlockState#canBeReplaced()} is true)</li>
     *   <li>fluids</li>
     * </ul>
     * until we hit a real solid block. {@code clearBuildingArea} will remove
     * the tree above the chosen Y when the building actually gets placed.
     */
    public static int groundY(ServerLevel level, int x, int z) {
        int startY =
            level.getHeight(Heightmap.Types.WORLD_SURFACE_WG, x, z) - 1;
        int minY = level.getMinBuildHeight();
        BlockPos.MutableBlockPos cursor = new BlockPos.MutableBlockPos(
            x,
            startY,
            z
        );
        for (int y = startY; y > minY; y--) {
            cursor.setY(y);
            BlockState state = level.getBlockState(cursor);
            if (state.isAir()) continue;
            if (!state.getFluidState().isEmpty()) continue;
            if (isPassThrough(state)) continue;
            return y;
        }
        return startY;
    }

    /**
     * True if {@code state} is something a building or worker shouldn't sit on
     * — part of a tree, a plant, or another replaceable block.
     */
    private static boolean isPassThrough(BlockState state) {
        if (state.canBeReplaced()) return true; // grass, snow layer, ferns, saplings, flowers, tall grass...
        if (state.is(BlockTags.LEAVES)) return true;
        if (state.is(BlockTags.LOGS)) return true;
        return false;
    }

    /** Convenience: project a candidate position to its column's ground Y. */
    public static BlockPos snapToGround(ServerLevel level, BlockPos candidate) {
        return new BlockPos(
            candidate.getX(),
            groundY(level, candidate.getX(), candidate.getZ()),
            candidate.getZ()
        );
    }

    /**
     * True if {@link #snapToGround} would land the building on water OR lava
     * (or any other fluid). Same logic as {@link #isLiquidAt} — they both use
     * {@code getFluidState().isEmpty()} so they correctly cover lava too.
     *
     * <p>This is the CENTRE-ONLY check. Prefer {@link #isLiquidInFootprint}
     * when you're about to place a real (multi-block) structure, since
     * buildings can sit with centre on land but a corner in water.
     */
    public static boolean wouldSnapToLiquid(
        ServerLevel level,
        BlockPos candidate
    ) {
        return isLiquidAt(level, candidate.getX(), candidate.getZ());
    }

    /**
     * True if any sampled point in a {@code 2*halfExtent+1} square around
     * {@code (x, z)} is a fluid surface.
     *
     * <p>This is the right check before placing a multi-block building.
     * Sampling step is {@code max(2, halfExtent / 3)} so the cost stays
     * bounded (≈49 reads for halfExtent=10) regardless of building size.
     *
     * <p>For capitols (≈15 blocks wide) use halfExtent = 8.
     * For neutral buildings (≈8 blocks wide) use halfExtent = 5.
     * For brain projects (≈houses, farms; 8–12 blocks) use halfExtent = 6.
     */
    public static boolean isLiquidInFootprint(
        ServerLevel level,
        int x,
        int z,
        int halfExtent
    ) {
        // Tighter sampling than before: halfExtent / 4 (was /3) so a 1-block
        // wide stream or a small lava pocket between sample points doesn't
        // get missed and let a building's foundation end up half-submerged.
        int step = Math.max(1, halfExtent / 4);
        for (int dx = -halfExtent; dx <= halfExtent; dx += step) {
            for (int dz = -halfExtent; dz <= halfExtent; dz += step) {
                if (isLiquidAt(level, x + dx, z + dz)) return true;
            }
        }
        // Always sample the four corners exactly, even if the step missed them.
        if (isLiquidAt(level, x + halfExtent, z + halfExtent)) return true;
        if (isLiquidAt(level, x + halfExtent, z - halfExtent)) return true;
        if (isLiquidAt(level, x - halfExtent, z + halfExtent)) return true;
        if (isLiquidAt(level, x - halfExtent, z - halfExtent)) return true;
        // And the cardinal mid-edges; these are the most common miss for
        // narrow east-west rivers running through a square footprint.
        if (isLiquidAt(level, x + halfExtent, z)) return true;
        if (isLiquidAt(level, x - halfExtent, z)) return true;
        if (isLiquidAt(level, x, z + halfExtent)) return true;
        if (isLiquidAt(level, x, z - halfExtent)) return true;
        return false;
    }

    /**
     * True if this column has water or lava sitting on top — even one block
     * deep. The previous implementation was buggy: it asked {@link #groundY}
     * for the column's top solid block, which deliberately <em>skips fluids</em>
     * on its way down, then checked the block at that Y. The result was that
     * any column with water above sand (a shallow puddle, a stream, a 1-deep
     * shoreline) was reported as <em>dry</em> because the check landed on the
     * sand, not the water. That's why we were still placing capitols and
     * spawning workers in shallow water.
     *
     * <p>The correct check is to look at the <em>topmost non-air block</em>
     * from the worldgen heightmap. {@code WORLD_SURFACE_WG} counts water and
     * lava as surface, so {@code getHeight() - 1} is the Y of the topmost
     * non-air block; if that block is a fluid, the column is liquid. We also
     * peek one block down to catch the rare case where the very top is a
     * passthrough block (snow layer, lily pad, kelp) sitting directly on
     * water — in that situation the surface is functionally still water.
     */
    public static boolean isLiquidAt(ServerLevel level, int x, int z) {
        int topY = level.getHeight(Heightmap.Types.WORLD_SURFACE_WG, x, z) - 1;
        BlockState top = level.getBlockState(new BlockPos(x, topY, z));
        if (!top.getFluidState().isEmpty()) return true;
        // Lily pads, kelp tips, snow layers can sit on top of water; the column
        // is still functionally aquatic in that case.
        if (topY > level.getMinBuildHeight()) {
            BlockState below = level.getBlockState(
                new BlockPos(x, topY - 1, z)
            );
            if (!below.getFluidState().isEmpty()) return true;
        }
        return false;
    }

    /**
     * True if any of the column or its 4 cardinal neighbours at {@code radius}
     * blocks distance is a fluid surface. Cheap sanity-check for site
     * selection so we don't place buildings half-on-shore-half-in-lake.
     */
    public static boolean isLiquidNear(
        ServerLevel level,
        int x,
        int z,
        int radius
    ) {
        if (isLiquidAt(level, x, z)) return true;
        if (isLiquidAt(level, x + radius, z)) return true;
        if (isLiquidAt(level, x - radius, z)) return true;
        if (isLiquidAt(level, x, z + radius)) return true;
        if (isLiquidAt(level, x, z - radius)) return true;
        return false;
    }
}
