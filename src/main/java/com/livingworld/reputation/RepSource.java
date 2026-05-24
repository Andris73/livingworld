package com.livingworld.reputation;

/**
 * Identifies what triggered a reputation change.
 * Used for notification text and logging; has no mechanical effect.
 */
public enum RepSource {
    KILL_WORKER,
    KILL_MILITARY,
    STEAL,
    GIFT,
    REQUEST_COMPLETE,
    ATTACK_BUILDING,
    DEFEND_FACTION,
    TRADE,
    NEARBY_VICTORY,
    ATTACK_ENEMY,
    BETRAYAL_MULTIPLIER,
    PASSIVE_DECAY,
    ADMIN_SET,
    ;

    /** Short human-readable description for the action bar notification. */
    public String description() {
        return switch (this) {
            case KILL_WORKER        -> "Killed worker";
            case KILL_MILITARY      -> "Killed military unit";
            case STEAL              -> "Stole from building";
            case GIFT               -> "Gifted resources";
            case REQUEST_COMPLETE   -> "Completed request";
            case ATTACK_BUILDING    -> "Attacked building";
            case DEFEND_FACTION     -> "Defended their units";
            case TRADE              -> "Completed trade";
            case NEARBY_VICTORY     -> "Witnessed their victory";
            case ATTACK_ENEMY       -> "Attacked their enemy";
            case BETRAYAL_MULTIPLIER -> "Betrayed allies";
            case PASSIVE_DECAY      -> "Passive decay";
            case ADMIN_SET          -> "Admin override";
        };
    }
}
