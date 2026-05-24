package com.livingworld.worldgen;

import com.livingworld.LivingWorld;
import com.livingworld.config.LivingWorldConfig;
import com.livingworld.util.Terrain;
import com.solegendary.reignofnether.building.Building;
import com.solegendary.reignofnether.building.BuildingPlacement;
import com.solegendary.reignofnether.building.BuildingServerEvents;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentLinkedQueue;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Rotation;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.levelgen.structure.Structure;
import net.minecraft.world.level.levelgen.structure.StructureStart;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.event.level.ChunkEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;

/**
 * Detects vanilla neutral structures (ruined portals, pyramids) at chunk-load
 * and places the mapped RoN neutral building at the structure's centre.
 *
 * <p><b>Why we defer placement instead of placing in the chunk-load event:</b>
 * RoN's {@code placeBuilding} writes blocks for the building footprint, and
 * those writes can spill into not-yet-loaded neighbour chunks. Doing that
 * directly from {@code ChunkEvent.Load} causes a feedback loop \u2014 each forced
 * chunk load fires another {@code ChunkEvent.Load}, which finds another
 * structure, which queues another placement, which writes into more unloaded
 * chunks, and so on. The server thread locks up and the world hangs at 100%.
 *
 * <p>So we follow the same pattern as {@link VillageReplacementQueue}:
 * <ol>
 *   <li>{@link #onChunkLoad} just <em>enqueues</em> the work \u2014 cheap,
 *       no block writes.</li>
 *   <li>{@link #onServerTick} drains one entry every
 *       {@link #TICKS_BETWEEN_PLACEMENTS} ticks, doing the actual
 *       {@code placeBuilding} call on the server thread without contention.</li>
 * </ol>
 *
 * <p>The vanilla blocks of the original structure are blanked out by the
 * datapack NBT-path override (marker.nbt at
 * {@code data/minecraft/structures/ruined_portal/portal_*.nbt} etc.) so by the
 * time we see the structure start, no vanilla blocks exist to clean up.
 */
public class NeutralStructureReplacer {

    /** Ticks between consecutive neutral-building placements. 40 ticks \u2248 2 seconds. */
    public static final int TICKS_BETWEEN_PLACEMENTS = 40;

    /** De-dup set of structure centres we've already enqueued / replaced. */
    private static final Set<BlockPos> SEEN =
        java.util.Collections.newSetFromMap(
            new java.util.concurrent.ConcurrentHashMap<>()
        );

    /** Pending placements waiting to be processed on the server tick. */
    private static final ConcurrentLinkedQueue<Pending> PENDING =
        new ConcurrentLinkedQueue<>();

    private static int ticksSinceLastPlacement = 0;

    /** A neutral building scheduled to be placed at a known position. */
    private record Pending(
        BlockPos centre,
        Building building,
        String sourceKey
    ) {}

    // -------------------------------------------------------- discovery

    @SubscribeEvent
    public static void onChunkLoad(ChunkEvent.Load event) {
        if (!LivingWorldConfig.REPLACE_NEUTRAL_STRUCTURES) return;
        if (event.getLevel().isClientSide()) return;
        if (!(event.getLevel() instanceof ServerLevel level)) return;
        if (level.dimension() != Level.OVERWORLD) return;
        if (!(event.getChunk() instanceof LevelChunk chunk)) return;

        // Wrap all of discovery in a try/catch: chunk-load is on the critical
        // path of world joining; an exception here would block the whole load
        // and leave the player stuck at 100%.
        try {
            discoverStructures(level, chunk);
        } catch (Throwable t) {
            LivingWorld.LOGGER.error(
                "[NeutralStructureReplacer] Discovery threw on chunk {} \u2014 ignoring",
                chunk.getPos(),
                t
            );
        }
    }

    private static void discoverStructures(
        ServerLevel level,
        LevelChunk chunk
    ) {
        Map<Structure, StructureStart> allStarts = chunk.getAllStarts();
        if (allStarts == null || allStarts.isEmpty()) return;

        var registry = level
            .registryAccess()
            .registryOrThrow(Registries.STRUCTURE);

        for (Map.Entry<
            Structure,
            StructureStart
        > entry : allStarts.entrySet()) {
            Structure structure = entry.getKey();
            StructureStart start = entry.getValue();

            ResourceLocation key = registry.getKey(structure);
            if (key == null) continue;
            if (!start.isValid()) continue;

            Building target = NeutralStructureMappings.buildingFor(
                key.toString()
            );
            if (target == null) continue;

            BlockPos centre = start.getBoundingBox().getCenter();
            if (!SEEN.add(centre)) continue;

            PENDING.offer(new Pending(centre, target, key.toString()));

            if (LivingWorldConfig.VERBOSE_VILLAGE_REPLACEMENT) {
                LivingWorld.LOGGER.info(
                    "[NeutralStructureReplacer] Queued {} \u2192 {} at {}",
                    key,
                    target.name,
                    centre
                );
            }
        }
    }

    // -------------------------------------------------------- processing

    @SubscribeEvent
    public static void onServerTick(TickEvent.LevelTickEvent evt) {
        if (evt.phase != TickEvent.Phase.END) return;
        if (evt.level.isClientSide()) return;
        if (evt.level.dimension() != Level.OVERWORLD) return;
        if (!(evt.level instanceof ServerLevel level)) return;

        ticksSinceLastPlacement++;
        if (ticksSinceLastPlacement < TICKS_BETWEEN_PLACEMENTS) return;

        Pending next = PENDING.poll();
        if (next == null) return;
        ticksSinceLastPlacement = 0;

        try {
            spawnNeutralBuilding(level, next);
        } catch (Throwable t) {
            LivingWorld.LOGGER.error(
                "[NeutralStructureReplacer] Placement of {} at {} threw \u2014 skipping",
                next.building().name,
                next.centre(),
                t
            );
        }
    }

    /**
     * Place {@code building} at the snapped-to-ground centre as a neutral,
     * self-building structure. {@code ownerName = ""} marks it as NEUTRAL so
     * it's usable by any player and not auto-attacked by enemies.
     */
    private static void spawnNeutralBuilding(ServerLevel level, Pending entry) {
        BlockPos groundCentre = Terrain.snapToGround(level, entry.centre());

        // Refuse to drop a neutral building if any part of its footprint sits
        // on water or lava. Vanilla can put ruined portals in lava lakes and
        // our marker inherits that position. halfExtent=5 covers the
        // ~8-block footprints of healing fountains / transport portals.
        if (
            Terrain.isLiquidInFootprint(
                level,
                groundCentre.getX(),
                groundCentre.getZ(),
                5
            )
        ) {
            LivingWorld.LOGGER.info(
                "[NeutralStructureReplacer] Skipping {} at {} — footprint overlaps liquid (water/lava)",
                entry.building().name,
                groundCentre
            );
            return;
        }

        BuildingPlacement placement = BuildingServerEvents.placeBuilding(
            entry.building(),
            groundCentre,
            Rotation.NONE,
            "", // empty owner = NEUTRAL
            new int[] {},
            false, // queue
            false, // diagonal bridge
            true // fromCommand: bypass cost / terrain checks
        );
        if (placement == null) {
            LivingWorld.LOGGER.warn(
                "[NeutralStructureReplacer] Failed to place {} at {} (source: {})",
                entry.building().name,
                groundCentre,
                entry.sourceKey()
            );
            return;
        }
        placement.selfBuilding = true;

        LivingWorld.LOGGER.info(
            "[NeutralStructureReplacer] Replaced {} with neutral {} at {}",
            entry.sourceKey(),
            entry.building().name,
            groundCentre
        );
    }

    /** Clear the de-dup cache and pending queue (e.g. on server stop). */
    public static void clearCache() {
        SEEN.clear();
        PENDING.clear();
        ticksSinceLastPlacement = 0;
    }
}
