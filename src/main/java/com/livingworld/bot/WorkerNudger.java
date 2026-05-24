package com.livingworld.bot;

import com.livingworld.LivingWorld;
import com.livingworld.world.ResourceIndex;
import com.solegendary.reignofnether.resources.ResourceName;
import com.solegendary.reignofnether.resources.ResourceSources;
import com.solegendary.reignofnether.unit.UnitServerEvents;
import com.solegendary.reignofnether.unit.goals.GatherResourcesGoal;
import com.solegendary.reignofnether.unit.interfaces.AttackerUnit;
import com.solegendary.reignofnether.unit.interfaces.Unit;
import com.solegendary.reignofnether.unit.interfaces.WorkerUnit;
import java.util.HashSet;
import java.util.Set;
import javax.annotation.Nullable;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.phys.AABB;

/**
 * Rescues idle workers whose RoN-internal 25-block search can't reach a
 * resource, by handing them a target from the much-wider {@link ResourceIndex}.
 *
 * <p><b>Problem.</b> RoN's {@link GatherResourcesGoal} searches at most
 * {@code REACH_RANGE * (failedSearches + 1) = 25} blocks for a target. In most
 * biomes, the nearest tree / animal / exposed stone is sometimes slightly
 * further than that, so workers cycle: search 25 blocks \u2192 fail four times
 * \u2192 {@code stopGathering()} \u2192 role wiped to NONE \u2192 our brain reassigns \u2192
 * fail again. They perma-idle near the capitol.
 *
 * <p><b>Fix.</b> Every brain tick we query {@link ResourceIndex} for the
 * nearest known resource of each idle worker's role within
 * {@link #SCAN_RADIUS} blocks (currently 200) and hand it to them via
 * {@link GatherResourcesGoal#setMoveTarget(BlockPos)}. The index is populated
 * via chunk-load events and updated on block break/place, so this is O(1) per
 * chunk regardless of radius \u2014 we can crank the radius up without per-tick
 * cost.
 *
 * <p>If the index doesn't yet know about a chunk (loaded before the mod
 * attached, or never loaded), the search lazily indexes any currently-loaded
 * chunks it encounters. Chunks that aren't loaded at all are skipped \u2014
 * we never force-load.
 */
public final class WorkerNudger {

    /**
     * Effective scan radius (blocks). Eight times RoN's max search range —
     * possible because the {@link ResourceIndex} makes this an O(chunks)
     * lookup, not an O(blocks) scan.
     */
    public static final int SCAN_RADIUS = 200;

    /**
     * How far an idle FOOD worker will scan for huntable passive animals
     * before falling through to the crop / berry / farm search. 30 blocks
     * keeps the hunt local to the village rather than sending the worker
     * on an extended safari that risks pulling them out of military range.
     *
     * <p>The hunting payoff itself is implemented inside RoN: when a worker
     * kills any {@link ResourceSources#isHuntableAnimal} target, the
     * {@code onDropItem} hook in {@code UnitServerEvents} converts the
     * animal's drops directly into the worker's resource inventory and
     * triggers a deposit run when they hit the threshold. We just have to
     * give the worker the attack target.
     */
    public static final int HUNT_RADIUS = 30;

    private static final double HUNT_RADIUS_SQR =
        (double) HUNT_RADIUS * HUNT_RADIUS;

    private WorkerNudger() {}

    /**
     * For each idle worker owned by {@code bot} that has a non-NONE role,
     * find a nearby resource of that role (via the index) and direct the
     * worker to it.
     *
     * <p>Water-crossing policy: if the straight line from the worker to
     * the candidate target passes through any water at all (see
     * {@link com.livingworld.util.PathSurvey}), the target is added to
     * {@link UnreachableResources} with the standard 10-minute TTL and
     * skipped. The worker idles this tick — next tick {@code findNearest}
     * filters out the cached position and they pick something else.
     * Workers retain {@code setCanFloat(true)} from spawn so they don't
     * drown if combat or terrain pushes them into water; they just don't
     * <em>plan</em> paths through it.
     */
    public static void nudgeIdleWorkers(ServerLevel level, FactionBot bot) {
        String ownerName = bot.name();
        ResourceIndex index = ResourceIndex.get(level);
        long now = level.getGameTime();
        // Snapshot the unreachable cache once per tick. Combined with
        // claimedThisTick below to form the per-call exclusion set.
        Set<BlockPos> unreachable = UnreachableResources.currentSet(now);
        Set<BlockPos> claimedThisTick = new HashSet<>();
        int nudged = 0;

        for (LivingEntity entity : UnitServerEvents.getAllUnits()) {
            if (!(entity instanceof WorkerUnit worker)) continue;
            if (!(entity instanceof Unit unit)) continue;
            if (!unit.getOwnerName().equals(ownerName)) continue;

            GatherResourcesGoal goal = worker.getGatherResourceGoal();
            if (goal == null) continue;

            ResourceName role = goal.getTargetResourceName();
            if (role == ResourceName.NONE) continue;
            // Worker already has a target or is gathering / building / moving — leave alone.
            if (!WorkerUnit.isIdle(worker)) continue;
            if (goal.getGatherTarget() != null) continue;

            // Opportunistic hunting: a FOOD worker that finds a passive
            // animal within HUNT_RADIUS gets sent at it instead of being
            // routed to a crop block. RoN's onDropItem handler takes care
            // of converting the kill into food items in the worker's
            // inventory — we just provide the attack target. If no animal
            // is in range, fall through to the existing crop-block search
            // so we don't trade a slow farm gather for nothing.
            if (
                role == ResourceName.FOOD &&
                entity instanceof AttackerUnit attacker
            ) {
                LivingEntity prey = nearestHuntable(entity, ownerName);
                if (prey != null) {
                    attacker.setUnitAttackTarget(prey);
                    nudged++;
                    LivingWorld.LOGGER.info(
                        "[WorkerNudger] Directed worker {} (FOOD) to hunt {} at {}",
                        entity.getId(),
                        prey.getType().toShortString(),
                        prey.blockPosition().toShortString()
                    );
                    continue;
                }
            }

            // Build the exclusion set: claimedThisTick mutates as we go,
            // unreachable is per-tick. Cheap to copy each iteration; both
            // are small (max ~20 workers per village).
            Set<BlockPos> excluded = new HashSet<>(unreachable);
            excluded.addAll(claimedThisTick);

            BlockPos target = index.findNearest(
                level,
                entity.blockPosition(),
                role,
                SCAN_RADIUS,
                excluded
            );
            if (target == null) continue;

            // Water-crossing check: if the straight line from worker to
            // target passes through any water, void the target. Bridge
            // construction was attempted in an earlier iteration but
            // rolled back — the placement geometry was too fragile.
            // Simpler policy: shelve the resource and let the worker
            // pick a different (dry-reachable) one next tick.
            if (
                com.livingworld.util.PathSurvey.firstWaterGap(
                    level,
                    entity.blockPosition(),
                    target
                ) != null
            ) {
                UnreachableResources.mark(
                    target,
                    now,
                    UnreachableResources.UNREACHABLE_TTL_TICKS
                );
                unreachable.add(target);
                LivingWorld.LOGGER.debug(
                    "[WorkerNudger] {} target {} crosses water — marked unreachable",
                    bot.name(),
                    target.toShortString()
                );
                continue;
            }

            // Path is dry: direct the worker normally.
            goal.setMoveTarget(target);
            claimedThisTick.add(target);
            nudged++;
            LivingWorld.LOGGER.info(
                "[WorkerNudger] Directed worker {} ({}) to {} (dist² = {})",
                entity.getId(),
                role,
                target.toShortString(),
                target.distSqr(entity.blockPosition())
            );
        }

        if (nudged > 0 && LivingWorld.LOGGER.isDebugEnabled()) {
            LivingWorld.LOGGER.debug(
                "[WorkerNudger] {} workers nudged for owner {}",
                nudged,
                ownerName
            );
        }
    }

    /**
     * Closest passive animal in {@link #HUNT_RADIUS} of {@code worker} that
     * counts as huntable per RoN's resource sources (cow, pig, sheep,
     * chicken, rabbit, horse, donkey, mule, goat, mooshroom). Returns
     * {@code null} if nothing is in range or in line of sight.
     *
     * <p>Animals owned by other bots are skipped to keep cross-faction
     * livestock raids from being a worker behaviour — if a player wants
     * that they can a-click manually. Wild, unowned animals are fair game.
     */
    @Nullable
    private static LivingEntity nearestHuntable(
        LivingEntity worker,
        String ownerName
    ) {
        AABB box = worker.getBoundingBox().inflate(HUNT_RADIUS);
        LivingEntity nearest = null;
        double bestDistSqr = HUNT_RADIUS_SQR;
        for (LivingEntity other : worker
            .level()
            .getEntitiesOfClass(LivingEntity.class, box)) {
            if (other == worker) continue;
            if (!ResourceSources.isHuntableAnimal(other)) continue;
            // Don't poach another bot's livestock; only target wild animals.
            if (other instanceof Unit ownedUnit) {
                String otherOwner = ownedUnit.getOwnerName();
                if (otherOwner != null && !otherOwner.isBlank()) continue;
            }
            double d = other.distanceToSqr(worker);
            if (d < bestDistSqr) {
                bestDistSqr = d;
                nearest = other;
            }
        }
        return nearest;
    }
}
