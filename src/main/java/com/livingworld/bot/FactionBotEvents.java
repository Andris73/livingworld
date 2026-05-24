package com.livingworld.bot;

import com.livingworld.LivingWorld;
import com.livingworld.bot.FactionResourceChest;
import com.livingworld.reputation.RepEvents;
import com.livingworld.worldgen.NeutralStructureReplacer;
import com.livingworld.worldgen.VillageReplacementQueue;
import com.livingworld.worldgen.VillageReplacer;
import com.solegendary.reignofnether.player.PlayerServerEvents;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.Level;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.event.server.ServerStoppingEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;

/**
 * Drives {@link FactionBot#brain} ticks and handles server lifecycle.
 *
 * Brains tick at a low frequency ({@value #BRAIN_TICK_INTERVAL} game ticks)
 * — strategic decisions don't need 20 Hz.
 */
public class FactionBotEvents {

    /** Brain decision cadence, in game ticks. 100 ticks ≈ 5 seconds. */
    public static final int BRAIN_TICK_INTERVAL = 100;

    private static long ticks = 0;

    @SubscribeEvent
    public static void onLevelTick(TickEvent.LevelTickEvent evt) {
        if (
            evt.phase != TickEvent.Phase.END ||
            evt.level.isClientSide() ||
            evt.level.dimension() != Level.OVERWORLD
        ) {
            return;
        }

        ticks++;
        if (ticks % BRAIN_TICK_INTERVAL != 0) return;

        for (FactionBot bot : FactionBotRegistry.all()) {
            try {
                bot.brain.tick(evt.level);
            } catch (Throwable t) {
                LivingWorld.LOGGER.error(
                    "[LivingWorld] Brain tick failed for bot {}",
                    bot.name(),
                    t
                );
            }
        }
    }

    // onPlayerJoin is now handled by RepEvents — it calls
    // ReputationManager.syncAllFactions which performs the alliance setup
    // based on saved rep (defaults to Neutral = allied if no prior data).

    @SubscribeEvent
    public static void onServerStopping(ServerStoppingEvent evt) {
        // For now we don't persist NPC villages across restarts. Clear state to
        // avoid leaking stale entries into RoN's static caches on the next load.
        // Release each village's chunk-load tickets first — if the server
        // shuts down cleanly this prevents orphan tickets entirely; an
        // unclean crash falls back to the validation callback in
        // {@link com.livingworld.LivingWorld#commonSetup}.
        var server = evt.getServer();
        if (server != null) {
            ServerLevel overworld = server.getLevel(Level.OVERWORLD);
            if (overworld != null) {
                for (FactionBot bot : FactionBotRegistry.all()) {
                    try {
                        VillageChunkLoader.unforceLoad(overworld, bot);
                    } catch (Throwable t) {
                        LivingWorld.LOGGER.warn(
                            "[LivingWorld] Failed to unforce chunks for {}",
                            bot.name(),
                            t
                        );
                    }
                }
            }
        }
        FactionBotRegistry.clear();
        VillageReplacer.clearCache();
        VillageReplacementQueue.clearAll();
        NeutralStructureReplacer.clearCache();
        FactionResourceChest.clearCache();
        VillageHealth.clearCache();
        NightShelter.clearCache();
        UnreachableResources.clearCache();
        ticks = 0;
    }

    @SubscribeEvent
    public static void onLevelTickForDecay(TickEvent.LevelTickEvent evt) {
        if (evt.phase != TickEvent.Phase.END) return;
        if (evt.level.isClientSide()) return;
        if (evt.level.dimension() != Level.OVERWORLD) return;
        if (!(evt.level instanceof ServerLevel level)) return;
        RepEvents.tickDecay(level);
    }
}
