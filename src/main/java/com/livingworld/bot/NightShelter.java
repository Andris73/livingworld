package com.livingworld.bot;

import com.livingworld.LivingWorld;
import com.solegendary.reignofnether.faction.Faction;
import com.solegendary.reignofnether.resources.ResourceName;
import com.solegendary.reignofnether.unit.UnitServerEvents;
import com.solegendary.reignofnether.unit.interfaces.Unit;
import com.solegendary.reignofnether.unit.interfaces.WorkerUnit;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.LivingEntity;

/**
 * Sends villager workers indoors at night.
 *
 * <p><b>Faction policy:</b> only the {@link Faction#VILLAGERS} faction
 * shelters. Monsters are undead (no sleep cycle + immune to vanilla hostile
 * targeting via {@link HostileMobBehavior}) so they stay out. Piglins' only
 * "house"-tier building is {@code portal_basic} which is literally a portal
 * frame with no interior to path into; their compensation is a larger
 * starter garrison (see {@link SatelliteUnits}) and being more militarised
 * overall.
 *
 * <p><b>State-aware design (mirrors {@link WorkerFlight}).</b> The previous
 * version called {@link WorkerUnit#resetBehaviours} every brain tick during
 * night, which wiped each worker's gather role and contributed to the
 * "worker oscillates between gather target and capitol" bug. The new flow
 * tracks each sheltered worker's pre-shelter role in {@link #SHELTERED}:
 * <ol>
 *   <li><b>Day \u2192 night transition</b>: for each villager worker, save role,
 *       stop gathering, dispatch to the interior of the nearest furnished
 *       house (falls back to the capitol if no house exists yet).</li>
 *   <li><b>Night steady state</b>: no-op for already-sheltered workers. A
 *       worker spawned mid-night gets sheltered on first encounter.</li>
 *   <li><b>Night \u2192 day transition</b>: restore each sheltered worker's
 *       role and clear their move target. {@link WorkerNudger} picks them
 *       back up onto gather jobs.</li>
 * </ol>
 *
 * <p>Coordination with {@link WorkerFlight}: if a worker is already in
 * flight, NightShelter doesn't touch them \u2014 flight takes priority over
 * shelter. {@link WorkerAssignment#assignRolesToUnassignedWorkers} also
 * consults {@link #isSuppressed(UUID)} so a sheltered worker's role can't be
 * re-assigned while we're holding it.
 */
public final class NightShelter {

    /**
     * Sheltered workers, mapped to their pre-shelter gather role for
     * restoration at dawn.
     */
    private static final Map<UUID, ResourceName> SHELTERED =
        new ConcurrentHashMap<>();

    private NightShelter() {}

    /** True if this worker's role is currently parked by {@link NightShelter}. */
    public static boolean isSuppressed(UUID uuid) {
        return SHELTERED.containsKey(uuid);
    }

    /**
     * Faction policy: only villagers shelter at night. Other factions get an
     * early return so the rest of the tick is a no-op for them.
     */
    public static boolean shouldShelter(Faction faction) {
        return faction == Faction.VILLAGERS;
    }

    /**
     * Day/night sweep for {@code bot}. Cheap: short-circuits for non-villager
     * factions and for villager bots that have no workers needing transition.
     */
    public static void tick(ServerLevel level, FactionBot bot) {
        if (!shouldShelter(bot.faction)) return;

        boolean night = level.isNight();
        int sheltered = 0;
        int released = 0;

        for (LivingEntity entity : UnitServerEvents.getAllUnits()) {
            if (!(entity instanceof WorkerUnit worker)) continue;
            if (!(entity instanceof Unit unit)) continue;
            if (!bot.name().equals(unit.getOwnerName())) continue;

            UUID uuid = entity.getUUID();

            // If WorkerFlight is already managing this worker, defer entirely.
            // Flight overrides shelter.
            if (WorkerFlight.isSuppressed(uuid)) continue;

            // Workers in combat: let them fight, but DO NOT remove from
            // SHELTERED. Previously this branch dropped them from the
            // sheltered set, which meant after combat ended they were
            // treated as unsheltered: WorkerAssignment re-assigned their
            // wiped (NONE) role, WorkerNudger directed them to a gather
            // target, and they walked out of the house mid-night. With
            // the entry preserved, post-combat the worker is still
            // suppressed; the moveGoal naturally pulls them back to the
            // shelter waypoint.
            if (
                unit.getTargetGoal() != null &&
                unit.getTargetGoal().getTarget() != null
            ) {
                continue;
            }

            boolean wasSheltered = SHELTERED.containsKey(uuid);

            if (night && !wasSheltered) {
                // Day → night: park them indoors.
                BlockPos shelter = HouseFurnisher.nearestInterior(
                    level,
                    bot.name(),
                    entity.blockPosition()
                );
                if (shelter == null) {
                    // No house yet — at least cluster near the capitol so
                    // they're inside the garrison's defensive radius.
                    shelter = bot.centrePos;
                }
                ResourceName savedRole = roleOf(worker);
                SHELTERED.put(uuid, savedRole);
                WorkerUnit.resetBehaviours(worker);
                unit.setMoveTarget(shelter);
                sheltered++;
            } else if (!night && wasSheltered) {
                // Night → day: release.
                ResourceName saved = SHELTERED.remove(uuid);
                if (saved != null && saved != ResourceName.NONE) {
                    worker.getGatherResourceGoal().setTargetResourceName(saved);
                }
                unit.setMoveTarget(null);
                released++;
            }
            // else: steady state, no action.
        }

        if (sheltered > 0 || released > 0) {
            LivingWorld.LOGGER.debug(
                "[NightShelter] {} sheltered+{} released+{}",
                bot.name(),
                sheltered,
                released
            );
        }
    }

    /** Read the worker's current gather role, defensively. */
    private static ResourceName roleOf(WorkerUnit worker) {
        var goal = worker.getGatherResourceGoal();
        return goal == null ? ResourceName.NONE : goal.getTargetResourceName();
    }

    /** Clear all state. Called on server stop. */
    public static void clearCache() {
        SHELTERED.clear();
    }
}
