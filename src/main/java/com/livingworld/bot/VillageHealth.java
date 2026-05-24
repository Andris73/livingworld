package com.livingworld.bot;

import com.livingworld.LivingWorld;
import com.solegendary.reignofnether.building.BuildingPlacement;
import com.solegendary.reignofnether.building.BuildingServerEvents;
import com.solegendary.reignofnether.unit.UnitServerEvents;
import com.solegendary.reignofnether.unit.interfaces.Unit;
import java.util.ArrayList;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.LivingEntity;

/**
 * Detects villages that have become non-functional and tears them down.
 *
 * <p>A village is considered <em>dead</em> when either:
 * <ul>
 *   <li>It owns <strong>zero buildings</strong> and <strong>zero units</strong>
 *       \u2014 nothing left to recover from.</li>
 *   <li>Its capitol is destroyed <strong>and</strong> it owns zero workers
 *       \u2014 no production infrastructure to make new workers, even with the
 *       brain's auto-credit ({@code creditMissingResources}) topping up
 *       resources. The other buildings would just sit there forever.</li>
 * </ul>
 *
 * <p>Both conditions must persist for {@link #DEATH_GRACE_BRAIN_TICKS}
 * consecutive brain ticks before destruction fires. The grace window absorbs
 * transient states like "all units just died from an attack but a new one is
 * already being produced" or "capitol just got hit and is at 0 HP but the
 * destruction packet hasn't propagated".
 *
 * <p>Destruction sequence:
 * <ol>
 *   <li>Call {@link BuildingPlacement#destroy} on every remaining building
 *       (explosion animation + block cleanup).</li>
 *   <li>Release the village's chunk-load tickets via
 *       {@link VillageChunkLoader#unforceLoad}.</li>
 *   <li>Deregister the bot via {@link FactionBotRegistry#remove} (also
 *       removes the underlying RTSPlayer entry).</li>
 *   <li>Log so we can observe the rate at which villages die.</li>
 * </ol>
 */
public final class VillageHealth {

    /**
     * Consecutive brain ticks the village must be "dead" before we actually
     * destroy it. At a 5-second brain tick, 6 = 30 seconds of confirmed
     * deadness \u2014 enough margin for transient combat states without making
     * the destruction feel slow.
     */
    public static final int DEATH_GRACE_BRAIN_TICKS = 6;

    /** Per-bot dead-state countdown. Reset to 0 whenever the village is healthy. */
    private static final Map<String, Integer> DEATH_COUNTDOWN =
        new ConcurrentHashMap<>();

    private VillageHealth() {}

    /**
     * Inspect the village's health each brain tick. If it's dead long enough,
     * tear it down. Otherwise reset its countdown.
     */
    public static void tick(ServerLevel level, FactionBot bot) {
        if (isDead(bot)) {
            int n = DEATH_COUNTDOWN.merge(bot.name(), 1, Integer::sum);
            if (n == 1) {
                LivingWorld.LOGGER.info(
                    "[VillageHealth] {} has gone dark (no capitol or no units) \u2014 starting grace countdown",
                    bot.name()
                );
            }
            if (n >= DEATH_GRACE_BRAIN_TICKS) {
                destroy(level, bot);
            }
        } else if (DEATH_COUNTDOWN.containsKey(bot.name())) {
            // Recovered \u2014 unit was produced, building got rebuilt, etc.
            LivingWorld.LOGGER.info(
                "[VillageHealth] {} recovered before destruction",
                bot.name()
            );
            DEATH_COUNTDOWN.remove(bot.name());
        }
    }

    /**
     * Health predicate. Kept private so the criteria can evolve without
     * changing callers' expectations.
     */
    private static boolean isDead(FactionBot bot) {
        int totalBuildings = 0;
        boolean capitolPresent = false;
        for (BuildingPlacement bp : BuildingServerEvents.getBuildings()) {
            if (!bp.ownerName.equals(bot.name())) continue;
            totalBuildings++;
            if (bp == bot.capitol) capitolPresent = true;
        }

        int totalUnits = 0;
        int totalWorkers = 0;
        for (LivingEntity e : UnitServerEvents.getAllUnits()) {
            if (!(e instanceof Unit u)) continue;
            if (!bot.name().equals(u.getOwnerName())) continue;
            totalUnits++;
            if (
                e instanceof
                com.solegendary.reignofnether.unit.interfaces.WorkerUnit
            ) {
                totalWorkers++;
            }
        }

        // Total wipe: nothing left at all.
        if (totalBuildings == 0 && totalUnits == 0) return true;
        // Capitol gone + no workers: the brain can't produce more workers, and
        // without workers any remaining brain-queued buildings (which need
        // worker construction, see FactionBrain class doc) will never finish.
        // Self-building structures could in principle still complete, but
        // without a capitol there's no village to defend; reclaim the chunks.
        if (!capitolPresent && totalWorkers == 0) return true;
        return false;
    }

    /**
     * Tear down a confirmed-dead village. Snapshot the building list first so
     * we don't fight {@link BuildingServerEvents#getBuildings()} mutating
     * underneath us mid-iteration.
     */
    private static void destroy(ServerLevel level, FactionBot bot) {
        LivingWorld.LOGGER.warn(
            "[VillageHealth] Destroying non-functional village {} ({}) at {}",
            bot.name(),
            bot.faction,
            bot.centrePos
        );

        // 1. Demolish any remaining buildings.
        for (BuildingPlacement bp : new ArrayList<>(
            BuildingServerEvents.getBuildings()
        )) {
            if (!bp.ownerName.equals(bot.name())) continue;
            try {
                bp.destroy(level);
            } catch (Throwable t) {
                LivingWorld.LOGGER.warn(
                    "[VillageHealth] destroy() threw for {} at {}",
                    bp.getBuilding().name,
                    bp.centrePos,
                    t
                );
            }
        }

        // 2. Release force-load tickets so the abandoned chunks can unload.
        VillageChunkLoader.unforceLoad(level, bot);

        // 3. Deregister the bot (also removes the RTSPlayer entry).
        FactionBotRegistry.remove(bot);

        DEATH_COUNTDOWN.remove(bot.name());
    }

    /** Clear all state. Called on server stop. */
    public static void clearCache() {
        DEATH_COUNTDOWN.clear();
    }
}
