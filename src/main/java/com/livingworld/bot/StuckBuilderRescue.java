package com.livingworld.bot;

import com.livingworld.LivingWorld;
import com.solegendary.reignofnether.building.BuildingPlacement;
import com.solegendary.reignofnether.unit.UnitServerEvents;
import com.solegendary.reignofnether.unit.goals.BuildRepairGoal;
import com.solegendary.reignofnether.unit.interfaces.Unit;
import com.solegendary.reignofnether.unit.interfaces.WorkerUnit;
import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;

import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;
import java.util.UUID;

/**
 * Detects workers stuck on an unreachable build target and unblocks them.
 *
 * <p><b>The bug we're fixing:</b> RoN's {@link BuildRepairGoal} has no
 * stationary-timeout for build targets (unlike {@code GatherResourcesGoal}
 * which has {@code TICKS_STATIONARY_TIMEOUT}). So a worker assigned via
 * {@code autocastRepair} to a building they can't path to (across a river,
 * up a cliff, behind walls) just stands there forever. Because
 * {@code WorkerUnit.isIdle()} returns false whenever {@code buildingTarget}
 * is set, our other rescue paths ({@link WorkerNudger}, {@link WorkerAssignment})
 * skip these workers entirely.
 *
 * <p><b>How we detect:</b> we sample each worker's position when they enter
 * a build target. If their position hasn't changed for {@link #STUCK_TICKS}
 * brain ticks (about 30 seconds), we treat them as stuck and call
 * {@link BuildRepairGoal#stopBuilding()} to free them up. They'll go idle,
 * the gather goal takes over, and any nudger / role-cycle logic can act.
 *
 * <p>If the worker's autocastRepair re-assigns the same unreachable building
 * on the next brain tick, we'll just detect-and-clear again. Not ideal, but
 * eventually the building completes via other workers or gets destroyed.
 */
public final class StuckBuilderRescue {

    /**
     * How many brain ticks of "same building target + same position" before
     * we consider the worker stuck. Brain ticks every 5s, so 6 ticks \u2248 30s.
     */
    public static final int STUCK_TICKS = 6;

    /** Per-worker tracking of where they last were and how long they've sat. */
    private static final Map<UUID, Watch> WATCHED = new HashMap<>();

    private static final class Watch {
        BlockPos lastBuildingOrigin;   // identifies the building they're on
        BlockPos lastWorkerPos;        // worker's last seen position
        int sameStateTicks;            // consecutive ticks unchanged
    }

    private StuckBuilderRescue() {}

    public static int rescueStuck(String ownerName) {
        int rescued = 0;
        java.util.Set<UUID> stillAlive = new java.util.HashSet<>();

        for (LivingEntity entity : UnitServerEvents.getAllUnits()) {
            if (!(entity instanceof WorkerUnit worker)) continue;
            if (!(entity instanceof Unit unit)) continue;
            if (!unit.getOwnerName().equals(ownerName)) continue;

            UUID uuid = ((Entity) worker).getUUID();
            stillAlive.add(uuid);

            BuildRepairGoal goal = worker.getBuildRepairGoal();
            if (goal == null) continue;

            BuildingPlacement target = goal.getBuildingTarget();
            if (target == null) {
                // Not building \u2014 don't track.
                WATCHED.remove(uuid);
                continue;
            }

            BlockPos here = entity.blockPosition();
            Watch w = WATCHED.computeIfAbsent(uuid, k -> new Watch());

            if (target.originPos.equals(w.lastBuildingOrigin)
                    && here.equals(w.lastWorkerPos)) {
                w.sameStateTicks++;
            } else {
                w.lastBuildingOrigin = target.originPos;
                w.lastWorkerPos = here;
                w.sameStateTicks = 1;
            }

            if (w.sameStateTicks >= STUCK_TICKS) {
                LivingWorld.LOGGER.info(
                        "[StuckBuilderRescue] Worker {} stuck on {} at {} for {}+ brain ticks \u2014 clearing build target",
                        entity.getId(),
                        target.getBuilding() == null ? "?" : target.getBuilding().name,
                        target.originPos,
                        STUCK_TICKS);
                goal.stopBuilding();
                w.sameStateTicks = 0;
                rescued++;
            }
        }

        // Drop tracking entries for workers that died or changed owner.
        Iterator<UUID> it = WATCHED.keySet().iterator();
        while (it.hasNext()) {
            UUID uuid = it.next();
            if (!stillAlive.contains(uuid)) it.remove();
        }
        return rescued;
    }

    /**
     * For diagnostics: is this worker currently in the "stuck on a build"
     * state? Returns true once we've ticked them at the same state for at
     * least 2 brain ticks \u2014 enough to filter out workers who just arrived.
     */
    public static boolean appearsStuck(WorkerUnit worker) {
        UUID uuid = ((Entity) worker).getUUID();
        Watch w = WATCHED.get(uuid);
        return w != null && w.sameStateTicks >= 2;
    }
}
