package com.livingworld.bot;

import com.livingworld.LivingWorld;
import com.livingworld.util.Terrain;
import com.solegendary.reignofnether.faction.Faction;
import com.solegendary.reignofnether.registrars.EntityRegistrar;
import com.solegendary.reignofnether.unit.UnitServerEvents;
import com.solegendary.reignofnether.unit.interfaces.WorkerUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import javax.annotation.Nullable;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.Mob;

/**
 * Spawns the starting worker units around a newly-placed faction capitol.
 *
 * <p>Workers are positioned in a ring around the capitol centre, snapped to the
 * world-surface heightmap so they spawn on the ground rather than buried or
 * floating. Each worker has {@code autocastRepair = true} so it will idle-scan
 * for incomplete buildings (including the self-building capitol it just spawned
 * next to) and help construct them \u2014 no manual orders required.
 *
 * <p>Once the capitol finishes building, the workers will go idle. In slice 3
 * the {@code FactionBrain} will start queueing additional buildings (stockpile,
 * houses, farm, etc.) for them to work on; until then they just stand around
 * looking neutral.
 */
public final class WorkerSpawner {

    /**
     * Number of starter workers spawned per village. Bumped from 3 → 10 so
     * new villages feel populated immediately and the construction loop for
     * the early-game build order (stockpile + 2 houses + farm + barracks)
     * doesn't bottleneck on one or two stuck workers.
     */
    public static final int STARTER_WORKER_COUNT = 10;

    /**
     * Distance from capitol centre to spawn workers, in blocks. Wider than
     * before so 10 workers comfortably fit on the ring (≈7 blocks between
     * adjacent positions at 360°/10 spacing) without clipping the capitol
     * footprint.
     */
    public static final double SPAWN_RING_RADIUS = 12.0;

    /**
     * Inner fallback ring used when the primary ring position lands in
     * water/lava for all jitter retries. Kept close to the capitol — which
     * is guaranteed dry by {@link com.livingworld.worldgen.VillageSiteService}
     * — so workers always end up on land.
     */
    private static final double FALLBACK_RING_RADIUS = 5.0;

    private WorkerSpawner() {}

    /**
     * Spawn the faction-appropriate starter workers around {@code capitolCentre}.
     *
     * @return the list of spawned worker entities (size up to {@link #STARTER_WORKER_COUNT};
     *         may be empty if the faction has no worker type or all spawns failed)
     */
    public static List<Entity> spawnStarterWorkers(
        ServerLevel level,
        Faction faction,
        BlockPos capitolCentre,
        String ownerName
    ) {
        EntityType<? extends Mob> workerType = workerTypeFor(faction);
        if (workerType == null) {
            LivingWorld.LOGGER.warn(
                "[LivingWorld] No worker type for faction {} \u2014 skipping starter workers",
                faction
            );
            return List.of();
        }

        List<Entity> spawned = new ArrayList<>(STARTER_WORKER_COUNT);
        for (int i = 0; i < STARTER_WORKER_COUNT; i++) {
            BlockPos spawnPos = ringPositionOnGround(
                level,
                capitolCentre,
                i,
                STARTER_WORKER_COUNT
            );
            Entity entity = UnitServerEvents.spawnMob(
                workerType,
                level,
                spawnPos,
                ownerName
            );
            if (entity == null) {
                LivingWorld.LOGGER.warn(
                    "[LivingWorld] Failed to spawn worker {}/{} for {} at {}",
                    i + 1,
                    STARTER_WORKER_COUNT,
                    ownerName,
                    spawnPos
                );
                continue;
            }
            enableAutoRepair(entity);
            spawned.add(entity);
        }
        // Assign permanent FOOD / WOOD / ORE roles round-robin so the village
        // starts generating income as soon as workers go idle. RoN's BuildRepairGoal
        // takes priority, so the assignment is safe to set immediately — workers
        // will still help build the capitol first.
        WorkerAssignment.assignStarterRoles(spawned);

        LivingWorld.LOGGER.info(
            "[LivingWorld] Spawned {} starter worker(s) of type {} for {}",
            spawned.size(),
            workerType.toShortString(),
            ownerName
        );
        return spawned;
    }

    /**
     * Faction \u2192 worker {@link EntityType} mapping.
     * <ul>
     *   <li>{@code VILLAGERS} \u2192 {@code VillagerUnit}</li>
     *   <li>{@code MONSTERS} \u2192 {@code ZombieVillagerUnit}</li>
     *   <li>{@code PIGLINS} \u2192 {@code GruntUnit}</li>
     * </ul>
     */
    @Nullable
    public static EntityType<? extends Mob> workerTypeFor(Faction faction) {
        return switch (faction) {
            case VILLAGERS -> EntityRegistrar.VILLAGER_UNIT.get();
            case MONSTERS -> EntityRegistrar.ZOMBIE_VILLAGER_UNIT.get();
            case PIGLINS -> EntityRegistrar.GRUNT_UNIT.get();
            default -> null;
        };
    }

    /** Max angle-perturb attempts when the ideal ring position lands in water. */
    private static final int WATER_RETRY_ATTEMPTS = 8;

    private static final Random RNG = new Random();

    /**
     * Compute the {@code i}th of {@code count} positions on a ring around
     * {@code centre} at {@link #SPAWN_RING_RADIUS} blocks, projected to real
     * ground via {@link Terrain#groundY}.
     *
     * <p>Tries the ideal angle first; if that's over water we jitter up to
     * {@link #WATER_RETRY_ATTEMPTS} times around it. If <em>every</em>
     * outer-ring attempt is wet we retry the same sweep on a smaller inner
     * ring near the capitol. The capitol itself is guaranteed dry (the
     * spawn was rejected upstream otherwise), so the inner ring is a safe
     * last resort — better than dropping a worker into water or stacking
     * everyone on the capitol's centre block.
     */
    private static BlockPos ringPositionOnGround(
        ServerLevel level,
        BlockPos centre,
        int i,
        int count
    ) {
        double baseAngle = (2.0 * Math.PI * i) / Math.max(1, count);
        BlockPos found = tryRing(level, centre, baseAngle, SPAWN_RING_RADIUS);
        if (found != null) return found;
        found = tryRing(level, centre, baseAngle, FALLBACK_RING_RADIUS);
        if (found != null) return found;
        // Absolute last resort — the capitol centre. Should only happen on
        // genuinely pathological terrain (peninsula entirely surrounded by
        // water in every direction). The spawn will still work because the
        // capitol footprint is dry by construction.
        LivingWorld.LOGGER.warn(
            "[WorkerSpawner] No dry spawn position found for worker {} of {} around {} — falling back to capitol centre",
            i,
            count,
            centre
        );
        return new BlockPos(
            centre.getX(),
            Terrain.groundY(level, centre.getX(), centre.getZ()),
            centre.getZ()
        );
    }

    /**
     * Try the base angle plus a series of jittered angles on a ring of the
     * given radius. Returns the first dry ground position found, or null if
     * every attempt landed in liquid.
     */
    @Nullable
    private static BlockPos tryRing(
        ServerLevel level,
        BlockPos centre,
        double baseAngle,
        double radius
    ) {
        for (int attempt = 0; attempt < WATER_RETRY_ATTEMPTS; attempt++) {
            double jitter =
                attempt == 0 ? 0 : (RNG.nextDouble() - 0.5) * (Math.PI / 4); // ±22.5°
            double angle = baseAngle + jitter;
            int x = centre.getX() + (int) Math.round(Math.cos(angle) * radius);
            int z = centre.getZ() + (int) Math.round(Math.sin(angle) * radius);
            if (Terrain.isLiquidAt(level, x, z)) continue;
            return new BlockPos(x, Terrain.groundY(level, x, z), z);
        }
        return null;
    }

    /**
     * Turn on {@code BuildRepairGoal.autocastRepair} so the worker will
     * automatically find and help build incomplete owned buildings whenever it
     * goes idle.
     */
    private static void enableAutoRepair(Entity entity) {
        if (entity instanceof WorkerUnit worker) {
            var goal = worker.getBuildRepairGoal();
            if (goal != null) {
                goal.autocastRepair = true;
            }
        }
    }
}
