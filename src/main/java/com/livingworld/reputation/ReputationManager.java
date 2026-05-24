package com.livingworld.reputation;

import com.livingworld.LivingWorld;
import com.livingworld.bot.FactionBotRegistry;
import com.solegendary.reignofnether.alliance.AlliancesServerEvents;
import com.solegendary.reignofnether.faction.Faction;
import java.util.UUID;
import net.minecraft.Util;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.Style;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.util.Mth;

/**
 * Central API for all reputation operations.
 *
 * <p>All interactions with the rep system should go through this class.
 * It handles:
 * <ul>
 *   <li>Score clamping to [-1000, +1000]</li>
 *   <li>Infamy floor enforcement (prevents washing worker-kill debt)</li>
 *   <li>Betrayal multiplier (2x penalty when attacking while Exalted)</li>
 *   <li>Tier-change detection and notifications</li>
 *   <li>Alliance sync with RoN's {@code AlliancesServerEvents}</li>
 *   <li>Cross-faction propagation (enemy-of-my-enemy)</li>
 * </ul>
 */
public final class ReputationManager {

    public static final int MIN_SCORE = -1000;
    public static final int MAX_SCORE = 1000;

    /** Rep gained via trade is capped at this many points per in-game day. */
    public static final int TRADE_REP_DAILY_CAP = 40;

    /** Rep gained per completed trade transaction. */
    public static final int TRADE_REP_PER_TRADE = 8;

    /** Infamy floor offset: killing workers sets floor = max(score-100, MIN). */
    public static final int INFAMY_FLOOR_OFFSET = 100;

    /** Cross-faction rep gain for attacking an enemy faction's unit. */
    public static final int CROSS_FACTION_ATTACK_DELTA = 35;

    private ReputationManager() {}

    // ----------------------------------------------------------- read

    public static int getReputation(
        ServerLevel level,
        UUID player,
        Faction faction
    ) {
        return ReputationSaveData.get(level).getOrCreate(player, faction).score;
    }

    public static RepTier getTier(
        ServerLevel level,
        UUID player,
        Faction faction
    ) {
        return RepTier.fromScore(getReputation(level, player, faction));
    }

    // ---------------------------------------------------------- write

    /**
     * Adjust a player's reputation with a faction and handle all side-effects:
     * betrayal multiplier, infamy floor, tier transitions, alliance sync,
     * cross-faction propagation, and notifications.
     */
    public static void adjustReputation(
        ServerLevel level,
        UUID playerUUID,
        Faction faction,
        int rawDelta,
        RepSource source
    ) {
        if (faction == Faction.NONE || faction == Faction.NEUTRAL) return;

        ReputationSaveData saveData = ReputationSaveData.get(level);
        ReputationSaveData.Entry entry = saveData.getOrCreate(
            playerUUID,
            faction
        );

        RepTier tierBefore = RepTier.fromScore(entry.score);
        int delta = rawDelta;

        // --- Betrayal multiplier: 2x negative delta when attacking while Exalted ---
        if (delta < 0 && tierBefore == RepTier.EXALTED) {
            delta *= 2;
        }

        // --- Infamy floor: killing workers creates a permanent debt floor ---
        if (source == RepSource.KILL_WORKER) {
            int newFloor = Math.max(
                entry.score - INFAMY_FLOOR_OFFSET,
                MIN_SCORE
            );
            if (newFloor < entry.infamyFloor) {
                entry.infamyFloor = newFloor;
            }
        }

        // Apply delta, clamped to range
        int newScore = Mth.clamp(entry.score + delta, MIN_SCORE, MAX_SCORE);

        // Infamy floor enforcement: positive adjustments cannot push score above the floor
        // (floor is ≤ 0, so this only applies in negative territory)
        if (entry.infamyFloor < 0 && delta > 0) {
            newScore = Math.max(newScore, entry.infamyFloor);
        }
        entry.score = newScore;
        saveData.setDirty();

        RepTier tierAfter = RepTier.fromScore(newScore);

        // --- Notifications ---
        ServerPlayer player = level
            .getServer()
            .getPlayerList()
            .getPlayer(playerUUID);
        if (player != null) {
            sendActionBar(player, faction, delta, source, newScore);
            if (tierAfter != tierBefore) {
                sendTierChangeMessage(player, faction, tierBefore, tierAfter);
            }
        }

        // --- Alliance sync + hostile player tracking ---
        if (tierAfter != tierBefore) {
            syncAllianceForFaction(level, playerUUID, faction, tierAfter);
            syncHostilePlayerTracking(level, playerUUID, faction, tierAfter);
        }

        // --- Cross-faction propagation ---
        propagateCrossFaction(level, playerUUID, faction, delta, source);

        LivingWorld.LOGGER.debug(
            "[RepManager] {} {} {} {} → score={} tier={}",
            playerUUID,
            faction.name(),
            source.description(),
            delta,
            newScore,
            tierAfter.name()
        );
    }

    /** Admin override — directly set a score. */
    public static void setReputation(
        ServerLevel level,
        UUID playerUUID,
        Faction faction,
        int score
    ) {
        adjustReputation(
            level,
            playerUUID,
            faction,
            score - getReputation(level, playerUUID, faction),
            RepSource.ADMIN_SET
        );
    }

    // --------------------------------------------------------- alliance sync

    /**
     * Sync RoN alliance state for every bot of this faction with this player.
     * Called whenever a tier boundary is crossed.
     */
    public static void syncAllianceForFaction(
        ServerLevel level,
        UUID playerUUID,
        Faction faction,
        RepTier tier
    ) {
        ServerPlayer player = level
            .getServer()
            .getPlayerList()
            .getPlayer(playerUUID);
        if (player == null) return;
        String playerName = player.getName().getString();
        boolean shouldAlly = tier.isAllied();
        for (var bot : FactionBotRegistry.all()) {
            if (bot.faction != faction) continue;
            if (shouldAlly) {
                AlliancesServerEvents.addAlliance(bot.name(), playerName);
            } else {
                AlliancesServerEvents.removeAlliance(bot.name(), playerName);
            }
        }
    }

    /**
     * Re-sync ALL factions for a player (used on join).
     * Players who have no rep data default to 0 = NEUTRAL = allied, matching
     * pre-rep-system behaviour.
     */
    public static void syncAllFactions(
        ServerLevel level,
        UUID playerUUID,
        String playerName
    ) {
        ReputationSaveData saveData = ReputationSaveData.get(level);
        for (Faction faction : new Faction[] {
            Faction.VILLAGERS,
            Faction.MONSTERS,
            Faction.PIGLINS,
        }) {
            ReputationSaveData.Entry e = saveData.getOrCreate(
                playerUUID,
                faction
            );
            RepTier tier = RepTier.fromScore(e.score);
            boolean shouldAlly = tier.isAllied();
            for (var bot : FactionBotRegistry.all()) {
                if (bot.faction != faction) continue;
                if (shouldAlly) {
                    AlliancesServerEvents.addAlliance(bot.name(), playerName);
                } else {
                    AlliancesServerEvents.removeAlliance(
                        bot.name(),
                        playerName
                    );
                }
            }
        }
    }

    /**
     * When a player crosses into or out of HOSTILE tier, update each bot's
     * {@link com.livingworld.bot.FactionBot#hostilePlayers} set. PatrolManager
     * uses this to occasionally send patrols toward known hostile players.
     */
    private static void syncHostilePlayerTracking(
        ServerLevel level,
        UUID playerUUID,
        Faction faction,
        RepTier tier
    ) {
        ServerPlayer player = level
            .getServer()
            .getPlayerList()
            .getPlayer(playerUUID);
        if (player == null) return;
        String playerName = player.getName().getString();
        boolean isHostile = (tier == RepTier.HOSTILE);
        for (var bot : com.livingworld.bot.FactionBotRegistry.all()) {
            if (bot.faction != faction) continue;
            if (isHostile) bot.hostilePlayers.add(playerName);
            else bot.hostilePlayers.remove(playerName);
        }
    }

    // -------------------------------------------------------- cross-faction

    /**
     * Enemy-of-my-enemy: attacking/killing units of faction X gives rep with
     * every other faction that is hostile to X.
     */
    private static void propagateCrossFaction(
        ServerLevel level,
        UUID playerUUID,
        Faction faction,
        int delta,
        RepSource source
    ) {
        boolean isAttackAction =
            source == RepSource.KILL_WORKER ||
            source == RepSource.KILL_MILITARY ||
            source == RepSource.ATTACK_BUILDING ||
            source == RepSource.ATTACK_ENEMY;
        if (!isAttackAction || delta >= 0) return;

        // All other playable factions gain +35 rep for the player attacking faction X
        for (Faction f : new Faction[] {
            Faction.VILLAGERS,
            Faction.MONSTERS,
            Faction.PIGLINS,
        }) {
            if (f == faction) continue;
            adjustReputation(
                level,
                playerUUID,
                f,
                CROSS_FACTION_ATTACK_DELTA,
                RepSource.ATTACK_ENEMY
            );
        }
    }

    // ---------------------------------------------------- passive decay

    /** Called once per in-game day. Nudges score 1 point toward Neutral. */
    public static void applyDecay(ServerLevel level) {
        ReputationSaveData saveData = ReputationSaveData.get(level);
        for (var playerEntry : saveData.data.entrySet()) {
            UUID uuid = playerEntry.getKey();
            for (var factionEntry : playerEntry.getValue().entrySet()) {
                ReputationSaveData.Entry e = factionEntry.getValue();
                int score = e.score;
                // Only decay outside Neutral band
                if (score > RepTier.NEUTRAL.minScore + 100) {
                    // Positive side: decay toward +100
                    e.score = score - 1;
                    saveData.setDirty();
                } else if (score < RepTier.NEUTRAL.minScore) {
                    // Negative side: decay toward -100, but not past infamy floor
                    int decayed = score + 1;
                    if (e.infamyFloor < 0 && decayed > e.infamyFloor) {
                        decayed = e.infamyFloor; // stuck at floor
                    }
                    if (decayed != score) {
                        e.score = decayed;
                        saveData.setDirty();
                    }
                }
                // Reset daily trade cap if needed (handled in adjustReputation on trade)
            }
        }
    }

    // --------------------------------------------------- notifications

    private static void sendActionBar(
        ServerPlayer player,
        Faction faction,
        int delta,
        RepSource source,
        int newScore
    ) {
        String sign = delta >= 0 ? "+" : "";
        String msg = String.format(
            "Rep with %s: %s%d (%s) | %s [%d/1000]",
            faction.name().toLowerCase(),
            sign,
            delta,
            source.description(),
            getTier(
                player.serverLevel(),
                player.getUUID(),
                faction
            ).displayName(faction),
            newScore
        );
        player.sendSystemMessage(
            Component.literal(msg).withStyle(
                delta >= 0
                    ? Style.EMPTY
                          .withColor(0x55FF55) // green for gain
                    : Style.EMPTY.withColor(0xFF5555) // red for loss
            )
        );
    }

    private static void sendTierChangeMessage(
        ServerPlayer player,
        Faction faction,
        RepTier before,
        RepTier after
    ) {
        String msg = String.format(
            "[LivingWorld] Your reputation with the %s has changed: %s → %s",
            faction.name().toLowerCase(),
            before.displayName(faction),
            after.displayName(faction)
        );
        player.sendSystemMessage(
            Component.literal(msg).withStyle(
                after.ordinal() > before.ordinal()
                    ? Style.EMPTY.withColor(0xFFD700)
                          .withBold(true) // gold on improve
                    : Style.EMPTY.withColor(0xFF4444).withBold(true) // red on decline
            )
        );

        // Extra warning on entering Hostile
        if (after == RepTier.HOSTILE) {
            player.sendSystemMessage(
                Component.literal(
                    "[LivingWorld] Their patrols will now attack you on sight."
                ).withStyle(Style.EMPTY.withColor(0xFF4444))
            );
        }
    }
}
