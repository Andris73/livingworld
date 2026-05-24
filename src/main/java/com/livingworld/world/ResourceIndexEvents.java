package com.livingworld.world;

import com.livingworld.LivingWorld;
import com.solegendary.reignofnether.resources.ResourceName;
import com.solegendary.reignofnether.resources.ResourceSource;
import com.solegendary.reignofnether.resources.ResourceSources;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraftforge.event.level.BlockEvent;
import net.minecraftforge.event.level.ChunkEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;

/**
 * Keeps {@link ResourceIndex} in sync with the world.
 *
 * <p>Three hooks:
 * <ul>
 *   <li>{@code ChunkEvent.Load}: scan once per chunk to populate the index
 *       lazily. Cheap (~1ms per chunk) and we cache the "indexed" flag so we
 *       only scan once per chunk per save.</li>
 *   <li>{@code BlockEvent.BreakEvent}: if a known resource block is mined,
 *       remove it from the index so workers don't path to it any more.</li>
 *   <li>{@code BlockEvent.EntityPlaceEvent}: if a player/mob places a
 *       trackable resource (e.g. a sapling that'll grow, a wheat crop),
 *       add it to the index.</li>
 * </ul>
 *
 * <p>Overworld only \u2014 we don't waste cycles scanning the nether or end
 * (factions don't expand there yet).
 */
public class ResourceIndexEvents {

    @SubscribeEvent
    public static void onChunkLoad(ChunkEvent.Load event) {
        if (event.getLevel().isClientSide()) return;
        if (!(event.getLevel() instanceof ServerLevel level)) return;
        if (level.dimension() != Level.OVERWORLD) return;
        if (!(event.getChunk() instanceof LevelChunk chunk)) return;

        try {
            ResourceIndex.get(level).indexChunk(chunk);
        } catch (Throwable t) {
            LivingWorld.LOGGER.error(
                    "[ResourceIndex] indexChunk threw on {} \u2014 skipping",
                    chunk.getPos(), t);
        }
    }

    @SubscribeEvent
    public static void onBlockBreak(BlockEvent.BreakEvent event) {
        if (event.getLevel().isClientSide()) return;
        if (!(event.getLevel() instanceof ServerLevel level)) return;
        if (level.dimension() != Level.OVERWORLD) return;

        BlockState state = event.getState();
        ResourceSource src = ResourceSources.getFromBlockState(state);
        if (src == null || src.resourceName == ResourceName.NONE) return;

        BlockPos pos = event.getPos();
        ChunkPos cp = new ChunkPos(pos);
        ResourceIndex.get(level).remove(cp, src.resourceName, pos);
    }

    @SubscribeEvent
    public static void onBlockPlace(BlockEvent.EntityPlaceEvent event) {
        if (event.getLevel().isClientSide()) return;
        if (!(event.getLevel() instanceof ServerLevel level)) return;
        if (level.dimension() != Level.OVERWORLD) return;

        BlockState state = event.getPlacedBlock();
        ResourceSource src = ResourceSources.getFromBlockState(state);
        if (src == null || src.resourceName == ResourceName.NONE) return;

        BlockPos pos = event.getPos();
        ChunkPos cp = new ChunkPos(pos);
        ResourceIndex.get(level).add(cp, src.resourceName, pos);
    }
}
