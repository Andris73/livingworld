package com.livingworld.bot;

import com.livingworld.LivingWorld;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraftforge.common.world.ForgeChunkManager;

/**
 * Keeps the chunks around an NPC village force-loaded so the village
 * continues to grow, gather, and patrol whether or not a player is nearby.
 *
 * <p>RoN already force-loads each individual building's single centre chunk
 * (see {@code BuildingPlacement.forceChunk}). That keeps the building alive,
 * but it's not enough for the <em>village</em>: workers travelling 30+ blocks
 * out to gather wood path through chunks that would otherwise unload, and
 * patrol scouts heading out toward {@link com.livingworld.bot.PatrolManager#SCOUT_RADIUS}
 * blocks would freeze in mid-stride. We force-load a square ring of chunks
 * around the capitol covering the close-quarters worker radius.
 *
 * <p>Tickets are owned by the bot's capitol {@link BlockPos} so they're
 * disjoint from RoN's per-building tickets — adding/removing ours doesn't
 * affect theirs. The Forge loading-validation callback registered in
 * {@link com.livingworld.LivingWorld#commonSetup} drops any orphan tickets
 * on world load (in case of an unclean shutdown).
 */
public final class VillageChunkLoader {

    /**
     * Chunks (in each cardinal direction from the capitol's chunk) kept
     * force-loaded. 3 → a 7×7 = 49-chunk square = 112×112 block area.
     *
     * <p>Covers the capitol, its expansion buildings, and the inner part of
     * the worker gather radius. Workers ranging further out for resources
     * will still hit unloaded chunks, but {@code WorkerNudger} keeps tabs
     * on idle workers and reassigns them, so the worst case is a worker
     * sitting idle for one brain tick rather than the whole gather loop
     * collapsing.
     *
     * <p>Per-village cost: 49 ticking chunks. For 10 villages that's ~500
     * ticking chunks, comfortably under what a real player's view distance
     * already keeps loaded.
     */
    public static final int FORCE_LOAD_RADIUS_CHUNKS = 3;

    private VillageChunkLoader() {}

    /**
     * Add a force-load ticket for every chunk in the
     * {@link #FORCE_LOAD_RADIUS_CHUNKS}-radius square around
     * {@code bot.centrePos}. Idempotent — re-calling for the same bot adds
     * tickets at the same chunks, which is a no-op in Forge's ticket system.
     */
    public static void forceLoad(ServerLevel level, FactionBot bot) {
        int cx = bot.centrePos.getX() >> 4;
        int cz = bot.centrePos.getZ() >> 4;
        int n = 0;
        for (
            int dx = -FORCE_LOAD_RADIUS_CHUNKS;
            dx <= FORCE_LOAD_RADIUS_CHUNKS;
            dx++
        ) {
            for (
                int dz = -FORCE_LOAD_RADIUS_CHUNKS;
                dz <= FORCE_LOAD_RADIUS_CHUNKS;
                dz++
            ) {
                boolean ok = ForgeChunkManager.forceChunk(
                    level,
                    LivingWorld.MOD_ID,
                    bot.centrePos, // owner — used as the ticket key
                    cx + dx,
                    cz + dz,
                    /* add= */ true,
                    /* ticking= */ true
                );
                if (ok) n++;
            }
        }
        LivingWorld.LOGGER.info(
            "[VillageChunkLoader] Force-loaded {} chunk(s) around {} (capitol chunk {},{})",
            n,
            bot.name(),
            cx,
            cz
        );
    }

    /**
     * Remove the force-load tickets previously added by {@link #forceLoad}
     * for this bot. Safe to call even if the bot was never force-loaded.
     */
    public static void unforceLoad(ServerLevel level, FactionBot bot) {
        int cx = bot.centrePos.getX() >> 4;
        int cz = bot.centrePos.getZ() >> 4;
        for (
            int dx = -FORCE_LOAD_RADIUS_CHUNKS;
            dx <= FORCE_LOAD_RADIUS_CHUNKS;
            dx++
        ) {
            for (
                int dz = -FORCE_LOAD_RADIUS_CHUNKS;
                dz <= FORCE_LOAD_RADIUS_CHUNKS;
                dz++
            ) {
                ForgeChunkManager.forceChunk(
                    level,
                    LivingWorld.MOD_ID,
                    bot.centrePos,
                    cx + dx,
                    cz + dz,
                    /* add= */ false,
                    /* ticking= */ true
                );
            }
        }
    }
}
