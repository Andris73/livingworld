package com.livingworld.beacon;

import com.livingworld.LivingWorld;
import java.util.ArrayList;
import java.util.List;
import javax.annotation.Nullable;
import net.minecraft.advancements.Advancement;
import net.minecraft.advancements.AdvancementProgress;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;

/**
 * Programmatic advancement grants and revokes for the Beacon of Origins
 * progression.
 *
 * <p>All beacon advancements are defined in
 * {@code data/livingworld/advancements/beacon/*.json} with a
 * {@code minecraft:impossible} trigger \u2014 they can <strong>only</strong> be
 * granted via this class. This lets us drive them from the
 * {@link BeaconOfOrigins} tick handler at exactly the right beacon-state
 * transitions (capture, tier upgrade, hold-time thresholds, victory) and
 * keeps the player-facing feedback in one place (vanilla advancement toast +
 * "[Player] has made the advancement [...]" chat line, rather than the\n * ad-hoc {@code sendWarning} broadcasts RoN ships with).
 *
 * <p>Repeatability: when a player wins the beacon round, {@link #reset}
 * revokes every beacon advancement for that player so the whole tree is
 * earnable again on the next round. This is the "REPEATABLE!" piece of the
 * feature \u2014 advancements wouldn't normally re-fire once completed; we
 * explicitly wipe them.
 */
public final class BeaconAdvancements {

    public static final String NAMESPACE = "livingworld";

    // Advancement identifiers under the beacon/ subdirectory.
    public static final String TAKE_CONTROL = "beacon/take_control";
    public static final String TIER_IRON = "beacon/tier_iron";
    public static final String TIER_GOLD = "beacon/tier_gold";
    public static final String TIER_EMERALD = "beacon/tier_emerald";
    public static final String TIER_DIAMOND = "beacon/tier_diamond";
    public static final String TIER_NETHERITE = "beacon/tier_netherite";
    public static final String HOLD_25 = "beacon/hold_25";
    public static final String HOLD_50 = "beacon/hold_50";
    public static final String HOLD_75 = "beacon/hold_75";
    public static final String HOLD_FINAL = "beacon/hold_final";
    public static final String VICTORY = "beacon/victory";

    /** All player-facing beacon advancements (root is excluded — it's always granted). */
    private static final String[] ALL_PLAYER_FACING = new String[] {
        TAKE_CONTROL,
        TIER_IRON,
        TIER_GOLD,
        TIER_EMERALD,
        TIER_DIAMOND,
        TIER_NETHERITE,
        HOLD_25,
        HOLD_50,
        HOLD_75,
        HOLD_FINAL,
        VICTORY,
    };

    private BeaconAdvancements() {}

    /**
     * Tier index (0 = stone, 5 = netherite) \u2192 the advancement id that
     * marks reaching that tier. Returns {@code null} for tier 0 (no
     * advancement \u2014 the starting state).
     */
    @Nullable
    public static String tierAdvancement(int tier) {
        return switch (tier) {
            case 1 -> TIER_IRON;
            case 2 -> TIER_GOLD;
            case 3 -> TIER_EMERALD;
            case 4 -> TIER_DIAMOND;
            case 5 -> TIER_NETHERITE;
            default -> null;
        };
    }

    /**
     * Grant {@code advancementKey} (one of the constants above) to
     * {@code player}, awarding every remaining criterion so the advancement
     * is fully completed in one call.
     */
    public static void grant(ServerPlayer player, String advancementKey) {
        if (player == null) return;
        MinecraftServer server = player.getServer();
        if (server == null) return;
        Advancement adv = server
            .getAdvancements()
            .getAdvancement(new ResourceLocation(NAMESPACE, advancementKey));
        if (adv == null) {
            LivingWorld.LOGGER.warn(
                "[BeaconAdvancements] No advancement registered for {}",
                advancementKey
            );
            return;
        }
        AdvancementProgress progress = player
            .getAdvancements()
            .getOrStartProgress(adv);
        if (progress.isDone()) return;
        // getRemainingCriteria returns an Iterable<String> view backed by
        // the underlying progress map. Copy it into a list before awarding
        // so award() can mutate the map without ConcurrentModification.
        List<String> remaining = new ArrayList<>();
        for (String criterion : progress.getRemainingCriteria()) {
            remaining.add(criterion);
        }
        for (String criterion : remaining) {
            player.getAdvancements().award(adv, criterion);
        }
    }

    /**
     * Revoke every beacon advancement currently granted to {@code player}
     * so the entire tree is earnable again on the next round. Called from
     * {@link BeaconOfOrigins} on the {@code beaconOwnerTicks ==
     * Beacon.getTicksToWin} transition (i.e. immediately after a victory).
     */
    public static void reset(ServerPlayer player) {
        if (player == null) return;
        MinecraftServer server = player.getServer();
        if (server == null) return;
        int revoked = 0;
        for (String key : ALL_PLAYER_FACING) {
            Advancement adv = server
                .getAdvancements()
                .getAdvancement(new ResourceLocation(NAMESPACE, key));
            if (adv == null) continue;
            AdvancementProgress progress = player
                .getAdvancements()
                .getOrStartProgress(adv);
            // getCompletedCriteria returns an Iterable; copy via explicit
            // loop so revoke() can mutate the underlying progress without
            // ConcurrentModification.
            List<String> completed = new ArrayList<>();
            for (String criterion : progress.getCompletedCriteria()) {
                completed.add(criterion);
            }
            if (completed.isEmpty()) continue;
            for (String criterion : completed) {
                player.getAdvancements().revoke(adv, criterion);
            }
            revoked++;
        }
        if (revoked > 0) {
            LivingWorld.LOGGER.info(
                "[BeaconAdvancements] Reset {} beacon advancement(s) for {}",
                revoked,
                player.getName().getString()
            );
        }
    }

    /**
     * Look up a {@link ServerPlayer} by RTSPlayer name. Returns {@code null}
     * if the player is offline (we silently skip advancement grants in that
     * case \u2014 advancements only matter for online players).
     */
    @Nullable
    public static ServerPlayer findOnlinePlayer(
        ServerLevel level,
        String name
    ) {
        if (name == null || name.isEmpty()) return null;
        MinecraftServer server = level.getServer();
        if (server == null) return null;
        return server.getPlayerList().getPlayerByName(name);
    }
}
