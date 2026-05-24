package com.livingworld.bot;

import com.livingworld.LivingWorld;
import com.livingworld.brain.WorkforceAllocator;
import com.solegendary.reignofnether.resources.ResourceName;
import com.solegendary.reignofnether.unit.UnitServerEvents;
import com.solegendary.reignofnether.unit.interfaces.Unit;
import com.solegendary.reignofnether.unit.interfaces.WorkerUnit;
import com.solegendary.reignofnether.unit.units.villagers.VillagerUnit;
import com.solegendary.reignofnether.unit.units.villagers.VillagerUnitProfession;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;

/**
 * Assigns a permanent resource-gathering role (FOOD / WOOD / ORE) to each
 * worker so the village starts generating income on its own.
 *
 * <p>The role is set via
 * {@link com.solegendary.reignofnether.unit.goals.GatherResourcesGoal#setTargetResourceName(ResourceName)}.
 * RoN's existing goal handles the rest: find a node of that type, walk to it,
 * gather, deposit at the nearest building with {@code canAcceptResources}
 * (capitol and stockpile both qualify), and repeat. If the worker can't find a
 * node it keeps the role — only the per-target search state is reset (see
 * {@code NO_TARGET_TIMEOUT} in {@code GatherResourcesGoal}).
 *
 * <p>Priority interaction with the build goal: the
 * {@link com.solegendary.reignofnether.unit.goals.BuildRepairGoal} ticks first,
 * so a worker with {@code autocastRepair = true} will always help finish an
 * incomplete owned building before falling back to gathering. That means
 * setting the role at spawn time is safe — workers won't abandon the capitol
 * just because they have a gather assignment.
 *
 * <p>Future workforce-allocation improvements (see README): currently all
 * starter workers are assigned at once with a fixed round-robin distribution.
 * Slice 4+ should dynamically reallocate based on resource needs (e.g. drain
 * more workers onto WOOD when wood income is low, route some onto FOOD when
 * housing fills up, etc.) and only commit a fraction of the workforce to any
 * single build project so other workers can keep gathering in parallel.
 */
public final class WorkerAssignment {

    /**
     * Round-robin order for starter worker assignments.
     * <p>FOOD first so the village starts producing food immediately; WOOD
     * second because most early buildings cost wood; ORE last because RoN
     * advanced buildings/upgrades need it but it's not urgent.
     */
    private static final ResourceName[] STARTER_ROLES = {
        ResourceName.FOOD,
        ResourceName.WOOD,
        ResourceName.ORE,
    };

    /**
     * Per-worker memory of the last role we assigned. When a worker comes back
     * with {@code NONE} (meaning RoN's {@code GatherResourcesGoal} called
     * {@code stopGathering()} after {@code MAX_FAILED_SEARCHES} — i.e. no
     * reachable resource of that type within 25 blocks), we rotate to the next
     * role instead of re-assigning the same failing one. Over a couple of
     * cycles each worker converges on whichever role actually has nearby
     * gatherables.
     *
     * <p>Keyed by entity UUID so it survives chunk-unload (entity instance may
     * change). Entries for dead workers are cleaned up each pass via
     * {@link Map#keySet}{@code .retainAll}.
     */
    private static final Map<UUID, ResourceName> LAST_ROLE_ASSIGNED =
        new ConcurrentHashMap<>();

    private WorkerAssignment() {}

    /**
     * Assign a starter role to each worker in the list using
     * {@link #STARTER_ROLES} round-robin. Non-worker entities are skipped
     * silently.
     */
    public static void assignStarterRoles(List<? extends Entity> workers) {
        int i = 0;
        for (Entity entity : workers) {
            if (!(entity instanceof WorkerUnit worker)) continue;
            ResourceName role = STARTER_ROLES[i % STARTER_ROLES.length];
            var goal = worker.getGatherResourceGoal();
            if (goal == null) continue;
            goal.setTargetResourceName(role);
            i++;
            LivingWorld.LOGGER.info(
                "[LivingWorld] Assigned {} role to worker {}",
                role,
                ((Entity) worker).getId()
            );
        }
    }

    /**
     * Find all of {@code ownerName}'s workers with no gather role assigned
     * ({@link ResourceName#NONE}) and give them a role.
     *
     * <p>Two paths:
     * <ul>
     *   <li><b>Brand-new worker</b> (no entry in {@link #LAST_ROLE_ASSIGNED}):
     *       picks a role via {@link com.livingworld.brain.WorkforceAllocator}
     *       — a weighted sample that combines biome resource priors
     *       (forest=WOOD, desert=ORE, plains=balanced) with current build-queue
     *       demand.</li>
     *   <li><b>Returning to NONE</b> (we've assigned this worker before but
     *       RoN's {@code stopGathering()} cleared the role because no resource
     *       was reachable): rotates to the <em>next</em> role in
     *       {@link #STARTER_ROLES}. Repeated failures cycle FOOD → WOOD → ORE
     *       → FOOD … until the worker hits a role with gatherables in range.</li>
     * </ul>
     *
     * <p>Called on every brain tick.
     *
     * @param bot used to compute biome + demand weights for first-time assignments
     * @param level the server level (for biome lookup)
     */
    public static void assignRolesToUnassignedWorkers(
        com.livingworld.bot.FactionBot bot,
        net.minecraft.server.level.ServerLevel level
    ) {
        assignRolesToUnassignedWorkers(bot.name(), () ->
            WorkforceAllocator.pickRoleFor(bot, level, bot.brain.peekNextStep())
        );
    }

    /** Backwards-compat overload: fall back to least-staffed if no allocator provided. */
    public static void assignRolesToUnassignedWorkers(String ownerName) {
        assignRolesToUnassignedWorkers(ownerName, null);
    }

    private static void assignRolesToUnassignedWorkers(
        String ownerName,
        @javax.annotation.Nullable java.util.function.Supplier<
            ResourceName
        > firstTimeRolePicker
    ) {
        int[] counts = new int[STARTER_ROLES.length];
        List<WorkerUnit> unassigned = new ArrayList<>();
        java.util.Set<UUID> seen = new java.util.HashSet<>();

        for (LivingEntity entity : UnitServerEvents.getAllUnits()) {
            if (!(entity instanceof WorkerUnit worker)) continue;
            if (!(entity instanceof Unit unit)) continue;
            if (!unit.getOwnerName().equals(ownerName)) continue;

            UUID uuid = entity.getUUID();
            seen.add(uuid);

            // Workers whose role has been parked by WorkerFlight or
            // NightShelter aren't really "unassigned" — they have a saved
            // role waiting to be restored. Skip them entirely or we'd race
            // those classes by re-assigning roles every tick.
            if (
                WorkerFlight.isSuppressed(uuid) ||
                NightShelter.isSuppressed(uuid)
            ) continue;

            var goal = worker.getGatherResourceGoal();
            if (goal == null) continue;

            ResourceName role = goal.getTargetResourceName();
            if (role == ResourceName.NONE) {
                unassigned.add(worker);
                continue;
            }
            for (int i = 0; i < STARTER_ROLES.length; i++) {
                if (STARTER_ROLES[i] == role) {
                    counts[i]++;
                    break;
                }
            }
        }

        // Prune dead workers from the memory map so it doesn't grow forever.
        LAST_ROLE_ASSIGNED.keySet().retainAll(seen);

        for (WorkerUnit worker : unassigned) {
            UUID uuid = ((Entity) worker).getUUID();
            ResourceName previous = LAST_ROLE_ASSIGNED.get(uuid);
            ResourceName chosen;

            // Profession-aware shortcut: if a villager has earned a profession
            // through past gathering experience (LUMBERJACK / FARMER / MINER),
            // assign the matching role so we don't waste their bonus. RoN's
            // GatherResourcesGoal applies a speed multiplier when profession
            // matches the resource being gathered.
            ResourceName roleFromProfession = roleForProfession(worker);

            if (previous == null) {
                if (roleFromProfession != null) {
                    chosen = roleFromProfession;
                } else if (firstTimeRolePicker != null) {
                    // Biome + demand weighted pick (slice 4 allocator).
                    chosen = firstTimeRolePicker.get();
                } else {
                    // No allocator (legacy path) — least-staffed fallback.
                    int minIdx = 0;
                    for (int i = 1; i < STARTER_ROLES.length; i++) {
                        if (counts[i] < counts[minIdx]) minIdx = i;
                    }
                    chosen = STARTER_ROLES[minIdx];
                }
            } else if (
                roleFromProfession != null && roleFromProfession != previous
            ) {
                // Worker has a profession but we last assigned them something
                // else (probably because their profession's resource was
                // depleted). Try the profession role again — maybe it's
                // gatherable now.
                chosen = roleFromProfession;
            } else {
                // Re-assignment after failure: rotate to the next role.
                chosen = nextRole(previous);
            }

            worker.getGatherResourceGoal().setTargetResourceName(chosen);
            LAST_ROLE_ASSIGNED.put(uuid, chosen);

            for (int i = 0; i < STARTER_ROLES.length; i++) {
                if (STARTER_ROLES[i] == chosen) {
                    counts[i]++;
                    break;
                }
            }

            LivingWorld.LOGGER.info(
                "[LivingWorld] Assigned {} role to worker {} (prev={})",
                chosen,
                ((Entity) worker).getId(),
                previous
            );
        }
    }

    /**
     * Return the next {@link #STARTER_ROLES} entry after {@code previous},
     * wrapping back to the start. Falls back to FOOD if {@code previous} isn't
     * one of the tracked starter roles.
     */
    private static ResourceName nextRole(ResourceName previous) {
        for (int i = 0; i < STARTER_ROLES.length; i++) {
            if (STARTER_ROLES[i] == previous) {
                return STARTER_ROLES[(i + 1) % STARTER_ROLES.length];
            }
        }
        return ResourceName.FOOD;
    }

    /**
     * If {@code worker} is a {@link VillagerUnit} with a tracked profession,
     * return the {@link ResourceName} that profession specializes in:
     * <ul>
     *   <li>LUMBERJACK → WOOD</li>
     *   <li>FARMER / HUNTER → FOOD</li>
     *   <li>MINER / MASON → ORE</li>
     * </ul>
     * Returns {@code null} for non-villager workers ({@code GruntUnit},
     * {@code ZombieVillagerUnit}) or villagers with no profession yet.
     *
     * <p>Note: RoN itself grants professions through experience, not at spawn,
     * so this only kicks in for veteran workers that have already accumulated
     * gathering XP.
     */
    private static ResourceName roleForProfession(WorkerUnit worker) {
        if (!(worker instanceof VillagerUnit vUnit)) return null;
        VillagerUnitProfession prof = vUnit.getUnitProfession();
        if (prof == null || prof == VillagerUnitProfession.NONE) return null;
        return switch (prof) {
            case LUMBERJACK -> ResourceName.WOOD;
            case FARMER, HUNTER -> ResourceName.FOOD;
            case MINER, MASON -> ResourceName.ORE;
            default -> null;
        };
    }
}
