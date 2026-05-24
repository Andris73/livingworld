package com.livingworld.util;

import javax.annotation.Nullable;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.phys.Vec3;

/**
 * Lightweight straight-line "is there water in the way" survey.
 *
 * <p>Not a real path planner \u2014 we don't try to follow the navigation graph
 * around obstacles. We just sample the straight line from {@code from} to
 * {@code to} at one-block intervals and look at the {@link Terrain#isLiquidAt}
 * verdict for each XZ column. That's good enough for the question we
 * actually need to answer: "is there a body of water blocking my worker
 * from reaching that tree?"
 *
 * <p>The returned {@link WaterGap} (if any) gives the start, end, and width
 * of the <em>first</em> contiguous water section encountered along the
 * line. {@link com.livingworld.bot.BridgeBuilder} uses this to decide
 * whether to place a single bridge or mark the target unreachable.
 *
 * <p>Caveats:
 * <ul>
 *   <li>This only detects water that intersects the <em>straight line</em>.
 *       A path planner might find a dry route around a small pond; we'd
 *       still report the pond as blocking. For our use case (idle workers
 *       being routed to nearby resources) the false positive is cheap \u2014
 *       worst case we place a redundant bridge.</li>
 *   <li>We don't distinguish water from lava; both trip {@code isLiquidAt}.
 *       Bridges over lava would be silly but at least not catastrophic.</li>
 * </ul>
 */
public final class PathSurvey {

    private PathSurvey() {}

    /**
     * The first water gap encountered along the straight line from
     * {@code from} to {@code to}. {@code length} is the number of
     * contiguous water-surface samples; {@code firstWater} and
     * {@code lastWater} are the two ends of that section (with their
     * Y values projected to ground via {@link Terrain#groundY}).
     * {@code dryBefore} is the last dry block walked before entering the
     * water (i.e. the bridge's near abutment); {@code dryAfter} is the
     * first dry block walked after exiting (the far abutment).
     */
    public record WaterGap(
        BlockPos firstWater,
        BlockPos lastWater,
        int length,
        @Nullable BlockPos dryBefore,
        @Nullable BlockPos dryAfter
    ) {}

    /**
     * Find the first water gap on the straight line, or {@code null} if
     * the line is entirely dry. Samples at every integer step of the
     * line (deduplicated by XZ so we don't double-count when the line
     * leaves a block diagonally).
     */
    @Nullable
    public static WaterGap firstWaterGap(
        ServerLevel level,
        BlockPos from,
        BlockPos to
    ) {
        if (from.equals(to)) return null;
        Vec3 a = Vec3.atCenterOf(from);
        Vec3 b = Vec3.atCenterOf(to);
        double dist = a.distanceTo(b);
        int steps = Math.max(1, (int) Math.ceil(dist));

        BlockPos firstWater = null;
        BlockPos lastWater = null;
        int length = 0;
        BlockPos dryBefore = null;

        long lastSampleKey = Long.MIN_VALUE;
        for (int i = 0; i <= steps; i++) {
            double t = i / (double) steps;
            Vec3 p = a.lerp(b, t);
            int x = (int) Math.round(p.x);
            int z = (int) Math.round(p.z);
            long key = ((long) x << 32) ^ (z & 0xffffffffL);
            if (key == lastSampleKey) continue;
            lastSampleKey = key;

            boolean liquid = Terrain.isLiquidAt(level, x, z);
            int y = Terrain.groundY(level, x, z);
            BlockPos sample = new BlockPos(x, y, z);

            if (liquid) {
                if (firstWater == null) firstWater = sample;
                lastWater = sample;
                length++;
            } else if (firstWater == null) {
                // Still in the dry approach; remember the last dry block
                // so we can use it as the near abutment.
                dryBefore = sample;
            } else {
                // Just exited the water. We're done; this is the far
                // abutment.
                return new WaterGap(firstWater, lastWater, length, dryBefore, sample);
            }
        }

        if (firstWater == null) return null;
        // Line ended in water; no far abutment found.
        return new WaterGap(firstWater, lastWater, length, dryBefore, null);
    }
}
