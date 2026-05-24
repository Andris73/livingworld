package com.livingworld.beacon;

import com.livingworld.LivingWorld;
import com.livingworld.util.Terrain;
import com.solegendary.reignofnether.api.ReignOfNetherRegistries;
import com.solegendary.reignofnether.building.Building;
import com.solegendary.reignofnether.building.BuildingBlock;
import com.solegendary.reignofnether.building.BuildingBlockData;
import com.solegendary.reignofnether.building.BuildingPlacement;
import com.solegendary.reignofnether.building.BuildingServerEvents;
import com.solegendary.reignofnether.building.buildings.neutral.Beacon;
import com.solegendary.reignofnether.building.buildings.neutral.CapturableBeacon;
import com.solegendary.reignofnether.building.buildings.placements.BeaconPlacement;
import com.solegendary.reignofnether.player.PlayerServerEvents;
import com.solegendary.reignofnether.player.RTSPlayer;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.Set;
import javax.annotation.Nullable;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Registry;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.LevelAccessor;
import net.minecraft.world.level.block.Rotation;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;

/**
 * Manages the Beacon of Origins — a single {@link CapturableBeacon} spawned
 * at world origin (0, ground, 0) that any faction or player can fight over.
 *
 * <p><b>Spawn:</b> placed once on first server start via
 * {@link #onServerStarted}. Uses {@code selfBuilding = true} so it assembles
 * itself with no workers. Position is the world's spawn point projected to
 * ground. Persists across restarts because RoN saves buildings as part of its
 * own data.
 *
 * <p><b>Capture detection:</b> RoN's
 * {@link BuildingPlacement#checkIfCaptured} already handles ownership transfer
 * when non-owner units outnumber the owner's units within range. We just
 * observe the {@code ownerName} field every tick and, when it changes, emit a
 * server-wide chat announcement and record the event in
 * {@link BeaconLeaderboard}.
 *
 * <p><b>Win condition:</b> there is no hard win. The announcement is prestige
 * — the leaderboard keeps a permanent history of every conqueror so the server
 * has a shared narrative. See {@link BeaconLeaderboard}.
 */
public class BeaconOfOrigins {

    /** How often (in game ticks) we poll the beacon's owner. 20 = once per second. */
    private static final int POLL_INTERVAL = 20;

    /** How often (game ticks) to retry the auto-spawn until it succeeds. 100 = 5 s. */
    private static final int SPAWN_RETRY_INTERVAL = 100;

    /**
     * Our registered t0 (stone-brick) beacon building. Must be registered in
     * {@link ReignOfNetherRegistries#BUILDING} so that:
     * <ul>
     *   <li>RoN can encode it in {@code BuildingClientboundPacket} (writes the
     *       registry key to the packet, crashes with NPE if unregistered)</li>
     *   <li>RoN can save it in {@code BuildingSaveData} (same registry lookup)</li>
     *   <li>The client knows the building type and renders it correctly</li>
     * </ul>
     * Registration happens once, here in the static initializer, before any
     * world ticks fire.
     */
    public static final CapturableBeacon BUILDING = Registry.register(
        ReignOfNetherRegistries.BUILDING,
        ResourceLocation.fromNamespaceAndPath(
            "livingworld",
            "beacon_of_origins"
        ),
        new CapturableBeacon() {
            @Override
            public ArrayList<BuildingBlock> getRelativeBlockData(
                LevelAccessor level
            ) {
                return BuildingBlockData.getBuildingBlocksFromNbt(
                    "beacon_t0",
                    level
                );
            }
        }
    );

    /** Last-known owner of the beacon. Empty string = unclaimed. */
    private static String lastKnownOwner = "";

    /**
     * Last-known upgrade tier (0..5). Tracked so we can fire the
     * tier-iron/gold/emerald/diamond/netherite advancements precisely on
     * the transition rather than once per poll.
     */
    private static int lastKnownTier = 0;

    /**
     * Tracks which hold-time advancements the current owner has been
     * granted this round, so we don't re-grant them every poll while the
     * threshold remains crossed. Reset on every owner change.
     */
    private static boolean granted25 = false;
    private static boolean granted50 = false;
    private static boolean granted75 = false;
    private static boolean grantedFinal = false;

    /**
     * Snapshot of RTSPlayer names that were registered on the previous
     * poll. Used to detect defeat / surrender (player removed from
     * {@code PlayerServerEvents.rtsPlayers}) and reset that player's beacon
     * advancements so they can re-earn them next time. Without this the
     * "REPEATABLE!" property only applied to the winner, not to defeated
     * opponents who'd progressed partway through the tree.
     */
    private static final Set<String> lastKnownActivePlayers = new HashSet<>();

    /** Cached reference to the beacon placement (looked up once at spawn). */
    @Nullable
    private static BuildingPlacement beaconPlacement;

    private static boolean spawned = false;

    // ------------------------------------------------------------ lifecycle

    /**
     * Deferred to the overworld tick (not {@code ServerStartedEvent}) because
     * RoN's {@code BuildingBlockData.getBuildingBlocksFromNbt} needs a
     * fully-initialised {@code serverLevel} which isn't ready until the first
     * tick. Called every {@link #SPAWN_RETRY_INTERVAL} ticks until the beacon
     * is successfully placed — handles the case where the first tick fires
     * before the spawn-chunks are fully loaded.
     */
    private static void tryInitBeacon(ServerLevel level) {
        // Check if a capturable beacon already exists from a previous session.
        beaconPlacement = findExistingBeacon();
        if (beaconPlacement != null) {
            lastKnownOwner = beaconPlacement.ownerName;
            spawned = true;
            LivingWorld.LOGGER.info(
                "[BeaconOfOrigins] Found existing beacon at {} (owner: '{}')",
                beaconPlacement.originPos,
                lastKnownOwner.isEmpty() ? "unclaimed" : lastKnownOwner
            );
            return;
        }

        // First time: spawn the beacon at world origin.
        BlockPos origin = new BlockPos(0, 0, 0);
        BlockPos ground = Terrain.snapToGround(level, origin);

        // Skip if origin is underwater / in lava.
        if (
            Terrain.isLiquidInFootprint(level, ground.getX(), ground.getZ(), 6)
        ) {
            LivingWorld.LOGGER.warn(
                "[BeaconOfOrigins] World origin is liquid — beacon NOT spawned. " +
                    "Use /livingworld beacon spawn to place it manually."
            );
            return;
        }

        try {
            // Use the registered BUILDING instance — anonymous classes are NOT
            // registered in ReignOfNetherRegistries.BUILDING, which causes RoN
            // to crash when encoding packets or saving (getKey returns null).
            BuildingPlacement placement = BuildingServerEvents.placeBuilding(
                BUILDING,
                ground,
                Rotation.NONE,
                "", // neutral / unclaimed
                new int[] {},
                false,
                false,
                true // fromCommand=true bypasses cost
            );

            if (placement == null) {
                LivingWorld.LOGGER.warn(
                    "[BeaconOfOrigins] placeBuilding returned null at {}",
                    ground
                );
                return;
            }
            placement.selfBuilding = true;
            beaconPlacement = placement;
            lastKnownOwner = "";
            spawned = true;
            LivingWorld.LOGGER.info(
                "[BeaconOfOrigins] Spawned Beacon of Origins at {} (beacon_t0 tier)",
                ground
            );
        } catch (Throwable t) {
            LivingWorld.LOGGER.error(
                "[BeaconOfOrigins] Failed to spawn beacon at {} — use /livingworld beacon spawn later",
                ground,
                t
            );
        }
    }

    // ---------------------------------------------------------- tick / detect

    @SubscribeEvent
    public static void onLevelTick(TickEvent.LevelTickEvent evt) {
        if (evt.phase != TickEvent.Phase.END) return;
        if (evt.level.isClientSide()) return;
        if (evt.level.dimension() != Level.OVERWORLD) return;
        if (!(evt.level instanceof ServerLevel level)) return;

        // Retry auto-spawn until successful. Only attempt when at least one
        // player is connected — in singleplayer the integrated-server ticks
        // before the client network is fully up, causing BuildingClientboundPacket
        // to fail encoding on tick 0 and spamming REQUEST_REPLACEMENT.
        if (
            !spawned &&
            !level.players().isEmpty() &&
            level.getGameTime() % SPAWN_RETRY_INTERVAL == 0
        ) {
            tryInitBeacon(level);
        }

        if (!spawned) return;
        if (level.getGameTime() % POLL_INTERVAL != 0) return;

        // Re-find the beacon if we lost the reference (e.g. after a world reload
        // where the placement object is recycled).
        if (
            beaconPlacement == null ||
            !BuildingServerEvents.getBuildings().contains(beaconPlacement)
        ) {
            beaconPlacement = findExistingBeacon();
            if (beaconPlacement == null) return;
            lastKnownOwner = beaconPlacement.ownerName;
        }

        String currentOwner = beaconPlacement.ownerName;
        if (!currentOwner.equals(lastKnownOwner)) {
            onCaptured(level, currentOwner, lastKnownOwner);
            lastKnownOwner = currentOwner;
        }

        // Tier advancements: poll the beacon's upgrade level and grant the
        // matching advancement to the current owner on each upward transition.
        if (beaconPlacement instanceof BeaconPlacement bp) {
            int tier = bp.getUpgradeLevel();
            if (tier > lastKnownTier && !currentOwner.isEmpty()) {
                ServerPlayer owner = BeaconAdvancements.findOnlinePlayer(
                    level,
                    currentOwner
                );
                if (owner != null) {
                    // Grant every tier between lastKnownTier and tier so we
                    // don't miss intermediate steps if the player upgraded
                    // multiple tiers between polls (rare but possible).
                    for (int t = lastKnownTier + 1; t <= tier; t++) {
                        String adv = BeaconAdvancements.tierAdvancement(t);
                        if (adv != null) {
                            BeaconAdvancements.grant(owner, adv);
                        }
                    }
                }
            }
            lastKnownTier = tier;
        }

        // Hold-time + victory advancements: only meaningful once the beacon
        // is at max tier and someone owns it. Read RoN's per-player
        // beaconOwnerTicks counter to know how long the owner has held it.
        if (!currentOwner.isEmpty()) {
            checkHoldThresholdsAndVictory(level, currentOwner);
        }

        // Defeat / surrender detection: when a player leaves
        // {@code PlayerServerEvents.rtsPlayers} we treat that as a defeat
        // (loss, surrender via /rts-surrender, or being defeated by a
        // beacon win) and reset their beacon advancements so they can
        // re-earn the whole tree on their next run. Online-only: offline
        // players' advancement data is on disk; we don't touch it.
        resetAdvancementsForDefeatedPlayers(level);
    }

    /**
     * Compare the current {@code rtsPlayers} list to our previous-tick
     * snapshot. Anyone who was in the snapshot but isn't in the current
     * list has been defeated or surrendered — reset their beacon
     * advancements so the progression is genuinely repeatable.
     */
    private static void resetAdvancementsForDefeatedPlayers(ServerLevel level) {
        Set<String> currentActive = new HashSet<>();
        synchronized (PlayerServerEvents.rtsPlayers) {
            for (RTSPlayer p : PlayerServerEvents.rtsPlayers) {
                currentActive.add(p.name);
            }
        }
        for (String name : lastKnownActivePlayers) {
            if (currentActive.contains(name)) continue;
            ServerPlayer defeated = BeaconAdvancements.findOnlinePlayer(
                level,
                name
            );
            if (defeated != null) {
                BeaconAdvancements.reset(defeated);
                LivingWorld.LOGGER.info(
                    "[BeaconOfOrigins] Reset advancements for defeated/surrendered player {}",
                    name
                );
            }
        }
        lastKnownActivePlayers.clear();
        lastKnownActivePlayers.addAll(currentActive);
    }

    /**
     * Inspect RoN's {@link RTSPlayer#beaconOwnerTicks} against the
     * 25%/50%/75%/final-minute thresholds of {@link Beacon#getTicksToWin}
     * and grant the matching advancement when each is first crossed.
     * Also detects victory (ticks ≥ ticksToWin) and resets the winner's
     * beacon advancements so they can earn them again next round.
     *
     * <p>The per-threshold {@code granted*} booleans are reset whenever
     * ownership changes (see {@link #onCaptured}) so a new owner starts
     * with a clean slate.
     */
    private static void checkHoldThresholdsAndVictory(
        ServerLevel level,
        String currentOwner
    ) {
        RTSPlayer rts = null;
        synchronized (PlayerServerEvents.rtsPlayers) {
            for (RTSPlayer p : PlayerServerEvents.rtsPlayers) {
                if (p.name.equals(currentOwner)) {
                    rts = p;
                    break;
                }
            }
        }
        if (rts == null) return;

        long held = rts.beaconOwnerTicks;
        long winTicks = Beacon.getTicksToWin(level);
        if (winTicks <= 0) return;

        ServerPlayer player = BeaconAdvancements.findOnlinePlayer(
            level,
            currentOwner
        );
        if (player == null) return;

        if (!granted25 && held >= winTicks / 4) {
            BeaconAdvancements.grant(player, BeaconAdvancements.HOLD_25);
            granted25 = true;
        }
        if (!granted50 && held >= winTicks / 2) {
            BeaconAdvancements.grant(player, BeaconAdvancements.HOLD_50);
            granted50 = true;
        }
        if (!granted75 && held >= (winTicks * 3) / 4) {
            BeaconAdvancements.grant(player, BeaconAdvancements.HOLD_75);
            granted75 = true;
        }
        if (!grantedFinal && held >= winTicks - 1200) {
            BeaconAdvancements.grant(player, BeaconAdvancements.HOLD_FINAL);
            grantedFinal = true;
        }
        if (held >= winTicks) {
            // Victory. Grant the challenge advancement, then wipe the
            // winner's beacon advancements so the whole tree is earnable
            // again on the next round (the user-facing "REPEATABLE!"
            // requirement). Do the grant BEFORE the reset so the victory
            // toast actually fires.
            BeaconAdvancements.grant(player, BeaconAdvancements.VICTORY);
            BeaconAdvancements.reset(player);
            // Clear the hold flags so we don't keep firing if RoN's win
            // handling doesn't immediately reset beaconOwnerTicks.
            granted25 = false;
            granted50 = false;
            granted75 = false;
            grantedFinal = false;
        }
    }

    // -------------------------------------------------------- capture handler

    private static void onCaptured(
        ServerLevel level,
        String newOwner,
        String previousOwner
    ) {
        // Record in persistent leaderboard.
        BeaconLeaderboard lb = BeaconLeaderboard.get(level);
        lb.record(level.getGameTime(), newOwner, previousOwner);

        // Reset per-owner advancement bookkeeping: new owner hasn't held
        // anything yet. Also reset the tracked tier since the beacon is
        // about to be reset to t0 below — the new owner needs to re-earn
        // each tier advancement.
        granted25 = false;
        granted50 = false;
        granted75 = false;
        grantedFinal = false;
        lastKnownTier = 0;

        // Grant the "take control" advancement to the new owner.
        if (!newOwner.isEmpty()) {
            ServerPlayer player = BeaconAdvancements.findOnlinePlayer(
                level,
                newOwner
            );
            if (player != null) {
                BeaconAdvancements.grant(
                    player,
                    BeaconAdvancements.TAKE_CONTROL
                );
            }
        }

        // Reset to a freshly-spawned beacon on every capture so the new
        // owner (or unclaimed state) starts from t0 (stone) with no aura
        // and no carried-over beaconOwnerTicks.
        //
        // We DO NOT call BuildingPlacement.destroy() here — it triggers TNT
        // explosions on roughly half the building blocks, damages nearby
        // entities, and (more importantly) doesn't remove the placement
        // from {@code BuildingServerEvents.buildings} until RoN's tick
        // loop's next cleanup pass. That left the next {@code placeBuilding}
        // call colliding with the not-yet-removed old placement, which
        // returned null, which made us wait for the auto-spawn retry to
        // place a neutral beacon, which the captor's units would then
        // re-capture, which fired this handler again — an infinite cycle.
        //
        // Instead: take the placement out of {@code buildings} ourselves
        // and let {@link BuildingServerEvents#placeBuilding}'s
        // {@code clearBuildingArea} pass handle the leftover blocks as
        // part of the fresh placement. Atomic, no explosions, no loop.
        if (beaconPlacement != null) {
            try {
                BlockPos pos = beaconPlacement.originPos;
                Rotation rotation = beaconPlacement.rotation;

                // Release the old placement's chunk-load ticket; the new
                // placement will register its own.
                beaconPlacement.forceChunk(false);

                // Remove from the tracked-buildings list BEFORE calling
                // placeBuilding so its "already a building at this pos"
                // check passes. clearBuildingArea (inside placeBuilding)
                // then sees the old beacon's blocks as un-tracked and
                // clears them as part of placing the new structure.
                BuildingServerEvents.getBuildings().remove(beaconPlacement);

                // Wipe everyone's beacon-hold timer so no one resumes the
                // win countdown from a previous session of ownership.
                synchronized (PlayerServerEvents.rtsPlayers) {
                    for (RTSPlayer p : PlayerServerEvents.rtsPlayers) {
                        p.beaconOwnerTicks = 0;
                    }
                }

                BuildingPlacement fresh = BuildingServerEvents.placeBuilding(
                    BUILDING,
                    pos,
                    rotation,
                    newOwner, // empty string = unclaimed; otherwise the new captor
                    new int[] {},
                    false,
                    false,
                    true // fromCommand: bypass cost / terrain, run clearBuildingArea
                );
                if (fresh != null) {
                    fresh.selfBuilding = true;
                    beaconPlacement = fresh;
                    LivingWorld.LOGGER.info(
                        "[BeaconOfOrigins] Reset beacon at {} (owner: {})",
                        pos,
                        newOwner.isEmpty() ? "unclaimed" : newOwner
                    );
                } else {
                    // Shouldn't happen now that the duplicate check passes,
                    // but if it does, log loudly and let the auto-spawn
                    // retry recover on the next SPAWN_RETRY_INTERVAL tick.
                    LivingWorld.LOGGER.error(
                        "[BeaconOfOrigins] Failed to re-place beacon at {} after removing from tracking",
                        pos
                    );
                    beaconPlacement = null;
                    spawned = false;
                }
            } catch (Throwable t) {
                LivingWorld.LOGGER.warn(
                    "[BeaconOfOrigins] Exception during beacon reset on capture",
                    t
                );
            }
        }

        // RoN's own beacon capture system already broadcasts
        // "X has gained control of the Beacon!" via sendWarning("capture_warning")
        // — no need for a second chat message here. Just log to the server log.
        String msg = newOwner.isEmpty()
            ? "The Beacon of Origins is now unclaimed."
            : newOwner +
              " captured the Beacon of Origins (tick " +
              level.getGameTime() +
              ")";
        LivingWorld.LOGGER.info("[BeaconOfOrigins] {}", msg);
    }

    // ------------------------------------------------------------ lookup

    @Nullable
    private static BuildingPlacement findExistingBeacon() {
        for (BuildingPlacement bp : BuildingServerEvents.getBuildings()) {
            // Match specifically our registered beacon, not any CapturableBeacon
            // (players could have their own from the normal RTS build menu).
            Building b = bp.getBuilding();
            if (b != null && b == BUILDING) return bp;
            // Fallback: accept any CapturableBeacon at roughly 0,0 if we haven't
            // registered yet (handles upgrades from older saves).
            if (
                b instanceof CapturableBeacon &&
                Math.abs(bp.originPos.getX()) < 20 &&
                Math.abs(bp.originPos.getZ()) < 20
            ) return bp;
        }
        return null;
    }

    /** For commands: is the beacon spawned and tracked? */
    public static boolean isSpawned() {
        return spawned;
    }

    @Nullable
    public static BuildingPlacement getPlacement() {
        return beaconPlacement;
    }

    public static String currentOwner() {
        return lastKnownOwner;
    }

    /** Manual spawn (debug). */
    public static boolean manualSpawn(ServerLevel level, BlockPos pos) {
        if (findExistingBeacon() != null) return false; // already exists
        BlockPos ground = Terrain.snapToGround(level, pos);
        // Use the registered BUILDING instance (same as tryInitBeacon).
        BuildingPlacement placement = BuildingServerEvents.placeBuilding(
            BUILDING,
            ground,
            Rotation.NONE,
            "",
            new int[] {},
            false,
            false,
            true
        );
        if (placement == null) return false;
        placement.selfBuilding = true;
        beaconPlacement = placement;
        lastKnownOwner = "";
        spawned = true;
        return true;
    }
}
