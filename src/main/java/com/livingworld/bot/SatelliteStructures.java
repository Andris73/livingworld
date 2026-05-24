package com.livingworld.bot;

import com.livingworld.LivingWorld;
import com.livingworld.brain.BuildOrder;
import com.livingworld.brain.SiteFinder;
import com.livingworld.config.LivingWorldConfig;
import com.solegendary.reignofnether.building.BuildingPlacement;
import com.solegendary.reignofnether.building.BuildingServerEvents;
import java.util.Random;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.Rotation;

/**
 * Pre-spawns a small set of supporting buildings around a newly-created
 * village so players who discover the village see a settled, populated
 * place — not a lone capitol with workers loitering on a ring waiting for
 * the brain's first build tick.
 *
 * <p>The "starter pack" is exactly the first four entries of each faction's
 * {@link BuildOrder#mainSequence} — stockpile, two houses, farm — so the
 * brain's normal progression simply resumes from step five (barracks /
 * watchtower tier) once the pre-spawn completes. We don't invent a new
 * curated list; reusing the same data keeps satellites consistent with
 * whatever the brain would have built anyway.
 *
 * <p>Each satellite is placed with {@code selfBuilding=true} and
 * {@code fromCommand=true}:
 * <ul>
 *   <li>{@code fromCommand} bypasses cost and terrain checks — the village's
 *       resource pool isn't drained, and we don't refuse to place because of
 *       a stray block. {@link SiteFinder} already ensures a clear, on-land
 *       site, so RoN's extra terrain filter is redundant here.</li>
 *   <li>{@code selfBuilding} makes RoN's tick loop construct the building
 *       block-by-block at ~1 block/tick. Buildings finish within seconds
 *       of placement; since the village's chunks are force-loaded by
 *       {@link VillageChunkLoader} the construction happens whether or not
 *       a player is nearby, and the village is fully built by the time a
 *       player walks up.</li>
 * </ul>
 *
 * <p>Failure to place a single satellite (no dry site in the radius, RoN
 * refuses the placement) is logged at debug and skipped — the brain just
 * picks up that step itself once it starts ticking.
 */
public final class SatelliteStructures {

    /**
     * Number of main-sequence steps to pre-spawn for every faction. Five
     * matches "stockpile + 2 houses + farm + military building" — the
     * smallest set that makes the village look settled AND has the
     * production infrastructure for follow-up military training to begin
     * immediately.
     *
     * <p>Step index 4 is the military building for every faction:
     * {@code BARRACKS} / {@code DUNGEON} / {@code PORTAL_MILITARY}.
     * Pre-spawning it (via {@code selfBuilding=true} so it finishes in
     * seconds with no worker dependency) means {@link com.livingworld.brain.UnitProducer}
     * starts queueing combat units on the first brain tick after the
     * building completes — no longer waiting on the brain-→-worker-→-build
     * chain to actually produce the building, which was historically
     * fragile (workers stuck on something else, no progress, brain
     * abandons after 90 s, retries, repeats).
     */
    public static final int SATELLITE_COUNT = 5;

    /**
     * Per-faction satellite step count. Retained as a hook so we can
     * differentiate later without changing call sites; currently all
     * factions get {@link #SATELLITE_COUNT}.
     */
    public static int satelliteCountFor(
        @SuppressWarnings(
            "unused"
        ) com.solegendary.reignofnether.faction.Faction faction
    ) {
        return SATELLITE_COUNT;
    }

    /** Inner radius (blocks) from the capitol for satellite placement. */
    public static final int SATELLITE_MIN_RADIUS = 22;

    /** Outer radius (blocks) from the capitol for satellite placement. */
    public static final int SATELLITE_MAX_RADIUS = 35;

    private static final Random RNG = new Random();

    private static final Rotation[] ROTATIONS = Rotation.values();

    private SatelliteStructures() {}

    /**
     * Place up to {@link #satelliteCountFor faction-appropriate} satellite
     * buildings around the given village. Returns the number actually
     * placed.
     *
     * <p>The caller is expected to advance the bot's build-order cursor by
     * this number via {@link com.livingworld.brain.FactionBrain#skipSteps}.
     *
     * <p>No-op (returns 0) if {@link LivingWorldConfig#PRESPAWN_SATELLITES}
     * is disabled.
     */
    public static int prespawn(ServerLevel level, FactionBot bot) {
        if (!LivingWorldConfig.PRESPAWN_SATELLITES) return 0;

        int count = satelliteCountFor(bot.faction);
        int placed = 0;
        for (int i = 0; i < count; i++) {
            BuildOrder.Step step = BuildOrder.mainStepAt(bot.faction, i);
            if (step == null) break;

            BlockPos site = SiteFinder.findNear(
                level,
                bot.centrePos,
                SATELLITE_MIN_RADIUS,
                SATELLITE_MAX_RADIUS
            );
            if (site == null) {
                LivingWorld.LOGGER.debug(
                    "[SatelliteStructures] {} couldn't find a site for satellite #{} ({}) — skipping",
                    bot.name(),
                    i,
                    step.label()
                );
                continue;
            }

            Rotation rotation = ROTATIONS[RNG.nextInt(ROTATIONS.length)];
            BuildingPlacement placement = BuildingServerEvents.placeBuilding(
                step.building(),
                site,
                rotation,
                bot.name(),
                new int[] {}, // no specific builders
                false, // queue
                false, // diagonal bridge
                true // fromCommand: skip cost + terrain checks
            );
            if (placement == null) {
                LivingWorld.LOGGER.debug(
                    "[SatelliteStructures] {} placeBuilding refused satellite #{} ({}) at {}",
                    bot.name(),
                    i,
                    step.label(),
                    site
                );
                continue;
            }
            // Construct quickly without needing worker assignment. RoN's
            // tick loop will place one block per tick until isBuilt; with
            // force-loaded chunks this happens regardless of player presence.
            placement.selfBuilding = true;
            placed++;
        }

        if (placed > 0) {
            LivingWorld.LOGGER.info(
                "[SatelliteStructures] Pre-spawned {} satellite building(s) for {} ({})",
                placed,
                bot.name(),
                bot.faction
            );
        }
        return placed;
    }
}
