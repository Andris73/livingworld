package com.livingworld.worldgen;

import com.livingworld.LivingWorld;
import com.livingworld.bot.FactionBot;
import com.livingworld.config.LivingWorldConfig;
import com.solegendary.reignofnether.faction.Faction;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.chunk.ChunkStatus;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.Random;
import java.util.concurrent.ConcurrentLinkedQueue;

/**
 * Defers and throttles village replacement work so it never blocks the server tick.
 *
 * <p>Why this exists: doing replacement synchronously inside {@code ChunkEvent.Load}
 * was disastrous — clearing tens of thousands of blocks with {@code setBlockAndUpdate}
 * triggered lighting recalculations, neighbour notifications, and chunk-load cascades
 * into neighbouring chunks (which then fired their own {@code ChunkEvent.Load} events).
 * The result was a feedback loop of forced chunk generation that locked up the server.
 *
 * <p>This class fixes that by:
 * <ul>
 *   <li>Decoupling discovery (cheap, runs on chunk-load) from replacement (expensive,
 *       runs on server tick with a budget).</li>
 *   <li>Using {@link Level#setBlock(BlockPos, BlockState, int)} with flag {@code 2}
 *       (clients-only update) instead of {@code setBlockAndUpdate}, so neighbour
 *       lighting and chunk loads aren't triggered.</li>
 *   <li>Spreading large block-clearing operations across many ticks via a
 *       {@link Job} state machine.</li>
 *   <li>Skipping air blocks entirely so we don't pay the per-block packet cost
 *       on the 90%+ of cubes that are already empty.</li>
 * </ul>
 */
public class VillageReplacementQueue {

    /** Pending villages waiting to be processed. Thread-safe so chunk-load can enqueue. */
    private static final ConcurrentLinkedQueue<PendingEntry> PENDING = new ConcurrentLinkedQueue<>();

    /** A queued village waiting to be processed. {@code clearBlocks} only applies to manual replacements. */
    private record PendingEntry(BlockPos centre, boolean clearBlocks) {}

    /** Active clear jobs, processed incrementally. */
    private static final Deque<Job> ACTIVE_JOBS = new ArrayDeque<>();

    /** Block-clearing budget per server tick (across all active jobs). */
    public static final int BLOCKS_PER_TICK_BUDGET = 4000;

    /** Server-tick interval between starting new replacement jobs. */
    public static final int TICKS_BETWEEN_REPLACEMENTS = 40; // ~2 seconds

    private static final Random RANDOM = new Random();
    private static int ticksSinceLastJobStart = 0;

    /**
     * Enqueue a village for capitol placement with NO surrounding-block clearing.
     * Used by the automatic chunk-load detector for "empty" villages produced by
     * our datapack override (no vanilla blocks to clear).
     */
    public static void enqueue(BlockPos villageCenter) {
        PENDING.offer(new PendingEntry(villageCenter, false));
    }

    /**
     * Enqueue a village for capitol placement AND surrounding-block clearing.
     * Used by the manual {@code /livingworld replace-here} command for
     * pre-existing vanilla villages.
     */
    public static void enqueueWithClearing(BlockPos villageCenter) {
        PENDING.offer(new PendingEntry(villageCenter, true));
    }

    @SubscribeEvent
    public static void onServerTick(TickEvent.LevelTickEvent evt) {
        if (evt.phase != TickEvent.Phase.END) return;
        if (evt.level.isClientSide()) return;
        if (evt.level.dimension() != Level.OVERWORLD) return;
        if (!(evt.level instanceof ServerLevel level)) return;

        // 1. Make progress on any active clearing jobs (block-by-block).
        progressActiveJobs(level);

        // 2. Periodically start a new replacement job from the pending queue.
        ticksSinceLastJobStart++;
        if (ticksSinceLastJobStart >= TICKS_BETWEEN_REPLACEMENTS && ACTIVE_JOBS.size() < 2) {
            PendingEntry next = PENDING.poll();
            if (next != null) {
                startReplacement(level, next);
                ticksSinceLastJobStart = 0;
            }
        }
    }

    private static void startReplacement(ServerLevel level, PendingEntry entry) {
        BlockPos villageCenter = entry.centre();
        if (LivingWorldConfig.VERBOSE_VILLAGE_REPLACEMENT) {
            LivingWorld.LOGGER.info("[VillageReplacer] Starting replacement at {} (clearBlocks={})",
                    villageCenter, entry.clearBlocks());
        }

        Faction faction = FactionPicker.pickFor(level, villageCenter, RANDOM);
        FactionBot bot = VillageSiteService.spawnVillage(level, faction, villageCenter);
        if (bot == null) {
            LivingWorld.LOGGER.warn(
                    "[VillageReplacer] Failed to spawn replacement village at {}", villageCenter);
            return;
        }
        LivingWorld.LOGGER.info(
                "[VillageReplacer] Placed {} capitol '{}' at {}",
                faction.name().toLowerCase(), bot.name(), villageCenter);

        // Only queue the slow block-clearing work for manual replacements of
        // pre-existing vanilla villages. Datapack-generated empty villages have
        // nothing to clear.
        if (entry.clearBlocks() && LivingWorldConfig.VILLAGE_CLEAR_RADIUS > 0) {
            ACTIVE_JOBS.offer(new Job(villageCenter, LivingWorldConfig.VILLAGE_CLEAR_RADIUS));
        }
    }

    private static void progressActiveJobs(ServerLevel level) {
        if (ACTIVE_JOBS.isEmpty()) return;

        int budget = BLOCKS_PER_TICK_BUDGET;
        while (budget > 0 && !ACTIVE_JOBS.isEmpty()) {
            Job job = ACTIVE_JOBS.peekFirst();
            int spent = job.tickClear(level, budget);
            budget -= spent;
            if (job.isDone()) {
                ACTIVE_JOBS.pollFirst();
                if (LivingWorldConfig.VERBOSE_VILLAGE_REPLACEMENT) {
                    LivingWorld.LOGGER.info(
                            "[VillageReplacer] Finished clearing job at {} ({} blocks cleared)",
                            job.center, job.cleared);
                }
            }
        }
    }

    /** Clear the queue and any in-progress jobs (e.g. on server stop). */
    public static void clearAll() {
        PENDING.clear();
        ACTIVE_JOBS.clear();
        ticksSinceLastJobStart = 0;
    }

    public static int pendingCount() { return PENDING.size(); }
    public static int activeJobCount() { return ACTIVE_JOBS.size(); }

    // ---------------------------------------------------------------- Job

    /**
     * An in-progress block-clearing operation. Tracks an x/z scan cursor so we can
     * resume mid-operation across many ticks.
     */
    private static final class Job {
        final BlockPos center;
        final int radius;
        int x;          // current x offset (-radius .. +radius)
        int z;          // current z offset (-radius .. +radius)
        int cleared = 0;

        Job(BlockPos center, int radius) {
            this.center = center;
            this.radius = radius;
            this.x = -radius;
            this.z = -radius;
        }

        boolean isDone() { return x > radius; }

        /**
         * Clear up to {@code budget} blocks worth of work. Each x/z column does
         * the full y-range, so the budget is checked between columns.
         * Returns the number of "budget units" spent (blocks touched, not just cleared).
         */
        int tickClear(ServerLevel level, int budget) {
            int spent = 0;
            while (spent < budget && !isDone()) {
                spent += clearColumn(level);
                advanceCursor();
            }
            return spent;
        }

        private int clearColumn(ServerLevel level) {
            int worldX = center.getX() + x;
            int worldZ = center.getZ() + z;
            int chunkX = worldX >> 4;
            int chunkZ = worldZ >> 4;

            // CRITICAL: use load=false so we never force-generate an unloaded chunk.
            // Calling level.getBlockState() directly would force chunk generation,
            // cascading into neighbouring chunks (the original freeze cause).
            ChunkAccess chunkAccess = level.getChunk(chunkX, chunkZ, ChunkStatus.FULL, false);
            if (chunkAccess == null) {
                // Chunk not loaded — skip this column entirely. Budget still consumed
                // so the cursor still advances and we don't stall.
                return 1;
            }

            int touched = 0;
            for (int dy = -5; dy <= 20; dy++) {
                BlockPos pos = new BlockPos(worldX, center.getY() + dy, worldZ);
                BlockState state = chunkAccess.getBlockState(pos);
                Block block = state.getBlock();
                touched++; // counts toward budget even if we skip

                // Skip air immediately — no work to do, no packet to send.
                if (state.isAir()) continue;

                if (VillageBlockSet.contains(block)) {
                    // Flag 2 = UPDATE_CLIENTS only. No neighbour updates, no lighting
                    // cascade, no forced chunk loads.
                    level.setBlock(pos, Blocks.AIR.defaultBlockState(), 2);
                    cleared++;
                }
            }
            return touched;
        }

        private void advanceCursor() {
            z++;
            if (z > radius) {
                z = -radius;
                x++;
            }
        }
    }
}
