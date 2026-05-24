package com.livingworld.brain;

/**
 * High-level needs the brain can identify and resolve by placing a building or
 * training a unit. Each need carries a priority computed from current world
 * state.
 *
 * Stub for slice 4 — not yet consumed by {@link FactionBrain}.
 */
public enum Need {
    /** Food production rate insufficient for current population. */
    FOOD_PRODUCTION,
    /** Current population approaching the population cap. */
    HOUSING,
    /** Workers travelling too far to drop off resources. */
    DROP_OFF_PROXIMITY,
    /** Not enough workers for active resource nodes. */
    WORKERS,
    /** Hostile units detected or low reputation with a nearby player. */
    MILITARY,
}
