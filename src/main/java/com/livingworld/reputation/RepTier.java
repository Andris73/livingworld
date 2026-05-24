package com.livingworld.reputation;

import com.solegendary.reignofnether.faction.Faction;

/**
 * Five-tier reputation scale per faction.
 *
 * <p>Score range: -1000 (worst) to +1000 (best). Each tier has a minimum
 * score, a generic name, and faction-flavoured display names that match
 * each faction's lore.
 */
public enum RepTier {

    /** -1000 to -501. Attacked on sight. */
    HOSTILE(-1000),

    /** -500 to -101. Trade refused; guards watchful. */
    UNFRIENDLY(-500),

    /** -100 to +100. Default starting state. */
    NEUTRAL(-100),

    /** +101 to +500. Basic trade unlocked; alliance maintained. */
    FRIENDLY(101),

    /** +501 to +1000. Full trade, 15 % discount, hire system. */
    EXALTED(501);

    /** Inclusive minimum score for this tier. */
    public final int minScore;

    RepTier(int minScore) {
        this.minScore = minScore;
    }

    /** Resolve a raw score to its tier. */
    public static RepTier fromScore(int score) {
        if (score >= EXALTED.minScore) return EXALTED;
        if (score >= FRIENDLY.minScore) return FRIENDLY;
        if (score >= NEUTRAL.minScore) return NEUTRAL;
        if (score >= UNFRIENDLY.minScore) return UNFRIENDLY;
        return HOSTILE;
    }

    /** Faction-flavoured display name shown in notifications and /rep. */
    public String displayName(Faction faction) {
        return switch (this) {
            case HOSTILE -> switch (faction) {
                case VILLAGERS -> "Denounced";
                case MONSTERS  -> "Nemesis";
                case PIGLINS   -> "Outcast";
                default        -> "Hostile";
            };
            case UNFRIENDLY -> switch (faction) {
                case VILLAGERS -> "Suspicious";
                case MONSTERS  -> "Unwanted";
                case PIGLINS   -> "Shunned";
                default        -> "Unfriendly";
            };
            case NEUTRAL -> "Neutral";
            case FRIENDLY -> switch (faction) {
                case VILLAGERS -> "Respected";
                case MONSTERS  -> "Tolerated";
                case PIGLINS   -> "Accepted";
                default        -> "Friendly";
            };
            case EXALTED -> switch (faction) {
                case VILLAGERS -> "Honoured Ally";
                case MONSTERS  -> "Blood Pact";
                case PIGLINS   -> "Forge-Blessed";
                default        -> "Exalted";
            };
        };
    }

    /** True if the player is allied to (non-hostile toward) this faction at this tier. */
    public boolean isAllied() {
        return this.ordinal() >= NEUTRAL.ordinal();
    }
}
