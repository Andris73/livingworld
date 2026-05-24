package com.livingworld.bot;

import java.util.HashSet;
import java.util.Iterator;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import net.minecraft.core.BlockPos;

/**
 * Global TTL cache of resource positions that workers can't currently reach.
 *
 * <p>Used by {@link WorkerNudger} to short-circuit "send worker after the
 * tree across that lake \u2014 again". When a worker's path to a resource
 * crosses water (or hits any other blocker) we add the resource position
 * here with an expiry game-tick. Subsequent {@link com.livingworld.world.ResourceIndex#findNearest}
 * calls receive this set as an exclusion so the same dead-end target
 * doesn't get picked again until the TTL lapses.
 *
 * <p>Currently the only blocker that flags a resource is "the straight line
 * to it crosses water" (see {@link com.livingworld.util.PathSurvey} and
 * {@link WorkerNudger}). Bridge construction was tried and rolled back due
 * to placement-orientation fragility; the simpler policy is to just void
 * any water-crossing path and shelve the resource for a while.
 *
 * <p>Cleared on server stop via {@link #clearCache()}.
 */
public final class UnreachableResources {

    /**
     * TTL applied when a resource is marked unreachable. 12000 ticks =
     * ten minutes of game time. After that window the entry lapses and
     * workers retry the target — useful if terrain has changed (water
     * drained, the player built a bridge themselves, etc.).
     */
    public static final long UNREACHABLE_TTL_TICKS = 12000L;

    /** Per-position expiry: BlockPos.asLong() \u2192 game time at which the entry lapses. */
    private static final Map<Long, Long> EXPIRY = new ConcurrentHashMap<>();

    private UnreachableResources() {}

    /**
     * Mark {@code pos} as unreachable until {@code currentTime + ttlTicks}.
     * If an entry already exists, the later expiry wins (so an unreachable
     * spot doesn't get "re-shortened" by a bridge-pending event).
     */
    public static void mark(BlockPos pos, long currentTime, long ttlTicks) {
        long expiry = currentTime + ttlTicks;
        EXPIRY.merge(pos.asLong(), expiry, Math::max);
    }

    /**
     * True iff {@code pos} is currently marked unreachable. Side-effect:
     * lazy-prunes the entry if its expiry has passed.
     */
    public static boolean isUnreachable(BlockPos pos, long currentTime) {
        Long expiry = EXPIRY.get(pos.asLong());
        if (expiry == null) return false;
        if (currentTime >= expiry) {
            EXPIRY.remove(pos.asLong());
            return false;
        }
        return true;
    }

    /**
     * Snapshot the currently-unreachable positions, pruning any expired
     * entries on the way. {@link WorkerNudger} calls this once per tick
     * and passes the result as an exclusion to
     * {@link com.livingworld.world.ResourceIndex#findNearest}.
     */
    public static Set<BlockPos> currentSet(long currentTime) {
        Set<BlockPos> result = new HashSet<>();
        Iterator<Map.Entry<Long, Long>> it = EXPIRY.entrySet().iterator();
        while (it.hasNext()) {
            Map.Entry<Long, Long> entry = it.next();
            if (currentTime >= entry.getValue()) {
                it.remove();
                continue;
            }
            result.add(BlockPos.of(entry.getKey()));
        }
        return result;
    }

    /** Clear all entries. Called on server stop. */
    public static void clearCache() {
        EXPIRY.clear();
    }
}
