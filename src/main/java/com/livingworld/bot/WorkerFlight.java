package com.livingworld.bot;

import com.livingworld.LivingWorld;
import com.solegendary.reignofnether.alliance.AlliancesServerEvents;
import com.solegendary.reignofnether.resources.ResourceName;
import com.solegendary.reignofnether.unit.UnitServerEvents;
import com.solegendary.reignofnether.unit.interfaces.AttackerUnit;
import com.solegendary.reignofnether.unit.interfaces.Unit;
import com.solegendary.reignofnether.unit.interfaces.WorkerUnit;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import javax.annotation.Nullable;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.monster.Monster;
import net.minecraft.world.phys.AABB;

/**
 * Per brain-tick safety reflex for bot-owned workers: if a hostile entity is
 * sitting in the worker's nose-cone radius and the worker isn't already
 * fighting back, drop whatever the worker is doing (gather, build) and run
 * back toward the capitol.
 *
 * <p><b>State-aware design.</b> The previous version called
 * {@link WorkerUnit#resetBehaviours} every brain tick while the threat
 * persisted, which had two compounding problems: it wiped the worker's
 * gather role to {@code NONE} (because {@code stopGathering()} calls
 * {@code setTargetResourceName(NONE)}) and it kept re-asserting the move
 * target every 5 s. Combined with {@link WorkerAssignment} re-assigning
 * "NONE" workers each tick and {@link WorkerNudger} setting fresh gather
 * targets, this produced the visible bug where workers would head toward
 * a resource, turn around to the capitol, head back out, and cycle forever
 * until something gave them an attack target (e.g. taking damage).
 *
 * <p>The new flow tracks each fleeing worker's <em>pre-flight</em> role in
 * {@link #FLEEING}. Three transitions:
 * <ol>
 *   <li><b>Safe \u2192 fleeing</b>: save the role, stop gathering, set move
 *       target to the capitol. Done once.</li>
 *   <li><b>Fleeing \u2192 fleeing</b>: no-op. The worker is already running;
 *       firing {@code resetBehaviours} again would just churn.</li>
 *   <li><b>Fleeing \u2192 safe</b>: restore the saved role and clear the
 *       move target. {@link WorkerNudger} picks them back up on the next
 *       tick.</li>
 * </ol>
 *
 * <p>{@link WorkerAssignment#assignRolesToUnassignedWorkers} consults
 * {@link #isSuppressed(UUID)} so fleeing workers don't get a role re-assigned
 * to something while their original is parked in {@link #FLEEING}.
 *
 * <p>Hostile players are intentionally <em>not</em> threats here \u2014
 * {@link HostileSweep} routes the whole village to attack denounced players,
 * and we want workers joining that fight rather than fleeing it.
 */
public final class WorkerFlight {

    /**
     * Workers within this many blocks of a threat will abandon their job and
     * flee. 12 is comfortably under {@link DefenseEvents#DEFENSE_ALERT_RADIUS}
     * (30) so that workers panic before military gets involved \u2014 the
     * military's job is to engage on the perimeter, the worker's job is to
     * not die in the field.
     */
    public static final int FLEE_RADIUS = 12;

    private static final double FLEE_RADIUS_SQR =
        (double) FLEE_RADIUS * FLEE_RADIUS;

    /**
     * Workers currently in flight, mapped to the role they had before the
     * suppression kicked in so we can restore it on the safe-transition.
     * Workers actively fighting (attack target set) are removed from this
     * map and treated as no-longer-fleeing.
     */
    private static final Map<UUID, ResourceName> FLEEING =
        new ConcurrentHashMap<>();

    private WorkerFlight() {}

    /** True if this worker's role is currently parked by {@link WorkerFlight}. */
    public static boolean isSuppressed(UUID uuid) {
        return FLEEING.containsKey(uuid);
    }

    /**
     * Re-evaluate each of {@code bot}'s workers and transition them in/out
     * of the flight state as appropriate. See class doc for the state
     * machine.
     */
    public static void tick(FactionBot bot) {
        int triggered = 0;
        int released = 0;
        for (LivingEntity entity : UnitServerEvents.getAllUnits()) {
            if (!(entity instanceof WorkerUnit worker)) continue;
            if (!(entity instanceof Unit unit)) continue;
            if (!bot.name().equals(unit.getOwnerName())) continue;

            UUID uuid = entity.getUUID();
            boolean wasFleeing = FLEEING.containsKey(uuid);

            // If the worker is fighting (combat target acquired), defer to
            // DefenseEvents / HostileMobBehavior and clear any flight state
            // so they're free to retaliate without us tugging them home.
            if (
                unit.getTargetGoal() != null &&
                unit.getTargetGoal().getTarget() != null
            ) {
                if (wasFleeing) {
                    // Don't restore role here — combat takes priority. Once
                    // they finish fighting they go idle and WorkerAssignment
                    // will re-assign normally.
                    FLEEING.remove(uuid);
                }
                continue;
            }

            LivingEntity threat = nearestThreat(entity, bot.name());
            boolean nowFleeing = (threat != null);

            if (nowFleeing && !wasFleeing) {
                // Safe → fleeing: save role, stop gather, send home.
                ResourceName savedRole = roleOf(worker);
                FLEEING.put(uuid, savedRole);
                WorkerUnit.resetBehaviours(worker);
                unit.setMoveTarget(bot.centrePos);
                triggered++;
            } else if (!nowFleeing && wasFleeing) {
                // Fleeing → safe: restore role, clear move target.
                ResourceName saved = FLEEING.remove(uuid);
                if (saved != null && saved != ResourceName.NONE) {
                    worker.getGatherResourceGoal().setTargetResourceName(saved);
                }
                unit.setMoveTarget(null);
                released++;
            }
            // else: steady state (still fleeing or still safe). No-op.
        }

        if (triggered > 0 || released > 0) {
            LivingWorld.LOGGER.debug(
                "[WorkerFlight] {} fled+{} returned+{}",
                bot.name(),
                triggered,
                released
            );
        }
    }

    /**
     * Drop entries for workers that no longer exist (died, despawned).
     * Called from {@link FactionBotEvents#onServerStopping} to fully reset.
     */
    public static void clearCache() {
        FLEEING.clear();
    }

    /** Read the worker's current gather role, defensively. */
    private static ResourceName roleOf(WorkerUnit worker) {
        var goal = worker.getGatherResourceGoal();
        return goal == null ? ResourceName.NONE : goal.getTargetResourceName();
    }

    /**
     * Closest hostile entity within {@link #FLEE_RADIUS} of {@code worker}.
     *
     * <p><b>Critical ordering note:</b> RoN's worker classes (VillagerUnit,
     * ZombieVillagerUnit, GruntUnit) all transitively extend
     * {@link Monster} — e.g. {@code VillagerUnit extends Vindicator}, and
     * {@code Vindicator -> AbstractIllager -> Raider -> PatrollingMonster ->
     * Monster}. So a naive {@code if (other instanceof Monster)} matches
     * <em>every other bot worker</em> within 12 blocks, which is why the
     * earlier version of this method had 10 freshly-spawned workers flee
     * from each other into the capitol and lock there permanently.
     *
     * <p>We now reject {@link WorkerUnit} instances <em>first</em>, then
     * branch on {@link Unit} (RoN entities — require AttackerUnit + cross-
     * faction + non-allied), and only fall through to the raw {@code Monster}
     * test for entities that aren't RoN units at all (i.e. genuine vanilla
     * zombies / skeletons / etc.).
     */
    @Nullable
    private static LivingEntity nearestThreat(
        LivingEntity worker,
        String ownerName
    ) {
        AABB box = worker.getBoundingBox().inflate(FLEE_RADIUS);
        LivingEntity nearest = null;
        double bestDistSqr = FLEE_RADIUS_SQR;
        for (LivingEntity other : worker
            .level()
            .getEntitiesOfClass(LivingEntity.class, box)) {
            if (other == worker) continue;
            // Workers (any faction, any owner) are NEVER a threat. This must
            // come before the Monster check because every RoN worker IS a
            // Monster instance.
            if (other instanceof WorkerUnit) continue;

            boolean hostile;
            if (other instanceof Unit otherUnit) {
                // RoN unit (military). Friendly fire / alliance filtering.
                String otherOwner = otherUnit.getOwnerName();
                if (
                    otherOwner != null && otherOwner.equals(ownerName)
                ) continue;
                if (!(other instanceof AttackerUnit)) continue;
                if (
                    otherOwner != null &&
                    AlliancesServerEvents.isAllied(ownerName, otherOwner)
                ) continue;
                hostile = true;
            } else if (other instanceof Monster) {
                // Genuine vanilla hostile mob (not a RoN unit).
                hostile = true;
            } else {
                hostile = false;
            }
            if (!hostile) continue;

            double d = other.distanceToSqr(worker);
            if (d < bestDistSqr) {
                bestDistSqr = d;
                nearest = other;
            }
        }
        return nearest;
    }
}
