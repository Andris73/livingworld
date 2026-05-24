package com.livingworld.worldgen;

import com.livingworld.LivingWorld;
import com.livingworld.config.LivingWorldConfig;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.level.levelgen.structure.Structure;
import net.minecraft.world.level.levelgen.structure.StructureStart;
import net.minecraftforge.event.level.ChunkEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;

import java.util.Map;
import java.util.Set;

/**
 * Detects vanilla villages and hands them off to {@link VillageReplacementQueue}
 * for deferred, throttled replacement.
 *
 * <p><b>Important:</b> This class deliberately does <em>no</em> heavy work itself.
 * Doing block clearing or building placement synchronously inside
 * {@code ChunkEvent.Load} caused a feedback loop where modifying blocks across
 * chunk boundaries forced neighbour chunks to generate, which fired more
 * {@code ChunkEvent.Load}s, which spawned more replacement attempts, etc.
 * All actual work is now deferred to {@link VillageReplacementQueue}.
 */
public class VillageReplacer {

    /** Village structure ID prefix - matches all biome variants. */
    private static final String VILLAGE_PREFIX = "minecraft:village";

    /** De-dup set of village centres we've already discovered/enqueued. */
    private static final Set<BlockPos> DISCOVERED =
            java.util.Collections.newSetFromMap(new java.util.concurrent.ConcurrentHashMap<>());

    @SubscribeEvent
    public static void onChunkLoad(ChunkEvent.Load event) {
        if (!LivingWorldConfig.REPLACE_VANILLA_VILLAGES) return;
        if (event.getLevel().isClientSide()) return;
        if (!(event.getLevel() instanceof ServerLevel level)) return;
        if (!(event.getChunk() instanceof LevelChunk chunk)) return;

        ChunkPos chunkPos = chunk.getPos();
        StructureStart villageStart = findVillageStart(level, chunk);
        if (villageStart == null) return;

        BlockPos villageCenter = villageStart.getBoundingBox().getCenter();

        // Villages span multiple chunks. De-dup so we only process each once.
        if (!DISCOVERED.add(villageCenter)) return;

        if (LivingWorldConfig.VERBOSE_VILLAGE_REPLACEMENT) {
            LivingWorld.LOGGER.info(
                    "[VillageReplacer] Detected village at {} (chunk {}) — queuing capitol placement",
                    villageCenter, chunkPos);
        }

        // Only place the capitol. No automatic block clearing — that would
        // trigger chunk-load cascades. RoN's placeBuilding with fromCommand=true
        // already clears the capitol's own footprint. Surrounding vanilla village
        // buildings can be cleaned up via /livingworld replace-here.
        VillageReplacementQueue.enqueue(villageCenter);
    }

    /**
     * Find a vanilla village whose pieces intersect this chunk.
     * Uses {@code chunk.getAllStarts()} to find any structure pieces in this chunk.
     *
     * @return the village's {@link StructureStart}, or null if none
     */
    private static StructureStart findVillageStart(ServerLevel level, LevelChunk chunk) {
        Map<Structure, StructureStart> allStarts = chunk.getAllStarts();
        if (allStarts == null || allStarts.isEmpty()) return null;

        var registry = level.registryAccess().registryOrThrow(Registries.STRUCTURE);

        for (Map.Entry<Structure, StructureStart> entry : allStarts.entrySet()) {
            Structure structure = entry.getKey();
            StructureStart start = entry.getValue();

            ResourceLocation key = registry.getKey(structure);
            if (key == null) continue;
            if (!key.toString().startsWith(VILLAGE_PREFIX)) continue;

            if (start.isValid()) return start;
        }
        return null;
    }

    /**
     * Find the nearest village centre by scanning for clusters of village blocks
     * around the given position. Used by the {@code /livingworld replace-here}
     * debug command — works on already-loaded chunks where structure data may
     * not be available.
     *
     * @return centroid of the densest village-block cluster within {@code searchRadius},
     *         or null if too few village blocks are found
     */
    public static BlockPos findVillageByBlockScan(ServerLevel level, BlockPos near, int searchRadius) {
        long sumX = 0, sumY = 0, sumZ = 0;
        int count = 0;

        for (int x = -searchRadius; x <= searchRadius; x++) {
            for (int z = -searchRadius; z <= searchRadius; z++) {
                int topY = level.getHeight(Heightmap.Types.WORLD_SURFACE,
                        near.getX() + x, near.getZ() + z);
                for (int y = topY - 5; y <= topY + 10; y++) {
                    BlockPos pos = new BlockPos(near.getX() + x, y, near.getZ() + z);
                    Block block = level.getBlockState(pos).getBlock();
                    if (VillageBlockSet.contains(block)) {
                        sumX += pos.getX();
                        sumY += pos.getY();
                        sumZ += pos.getZ();
                        count++;
                    }
                }
            }
        }

        if (count < 30) return null;
        return new BlockPos((int)(sumX / count), (int)(sumY / count), (int)(sumZ / count));
    }

    /**
     * Manually enqueue a pre-existing vanilla village for replacement, with
     * block clearing enabled. Used by the {@code /livingworld replace-here}
     * debug command.
     */
    public static void enqueueReplacement(BlockPos villageCenter) {
        DISCOVERED.add(villageCenter);
        VillageReplacementQueue.enqueueWithClearing(villageCenter);
    }

    /** Clear the discovery cache (e.g. on server stop). */
    public static void clearCache() {
        DISCOVERED.clear();
    }
}
