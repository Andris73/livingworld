package com.livingworld.worldgen;

import com.livingworld.LivingWorld;
import com.livingworld.bot.FactionBot;
import com.livingworld.bot.FactionBotRegistry;
import com.livingworld.bot.SatelliteStructures;
import com.livingworld.bot.SatelliteUnits;
import com.livingworld.bot.VillageChunkLoader;
import com.livingworld.bot.WorkerSpawner;
import com.livingworld.util.Names;
import com.livingworld.util.Terrain;
import com.solegendary.reignofnether.alliance.AlliancesServerEvents;
import com.solegendary.reignofnether.building.Building;
import com.solegendary.reignofnether.building.BuildingPlacement;
import com.solegendary.reignofnether.building.BuildingServerEvents;
import com.solegendary.reignofnether.building.Buildings;
import com.solegendary.reignofnether.faction.Faction;
import com.solegendary.reignofnether.player.PlayerServerEvents;
import com.solegendary.reignofnether.player.RTSPlayer;
import com.solegendary.reignofnether.resources.Resources;
import com.solegendary.reignofnether.resources.ResourcesServerEvents;
import javax.annotation.Nullable;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.block.Rotation;

/**
 * Bootstraps a single NPC village at a given position.
 *
 * <p>Steps performed (mirrors the design proposal in the project README):
 * <ol>
 *   <li>Create a bot {@link RTSPlayer} and register it with RoN.</li>
 *   <li>Seed a starting {@link Resources} pool for the bot.</li>
 *   <li>Pick a faction-appropriate capitol building.</li>
 *   <li>Place the capitol via
 *       {@link BuildingServerEvents#placeBuilding(Building, BlockPos, Rotation, String, int[], boolean, boolean, boolean) placeBuilding}
 *       with {@code fromCommand=true} to bypass resource and terrain checks,
 *       then flip {@code selfBuilding=true} so RoN's tick loop will construct
 *       it block-by-block with no workers needed.</li>
 *   <li>Ally the bot with every connected player so units don't auto-attack
 *       — this is the "neutral by default" starting reputation.</li>
 *   <li>Register the bot with {@link FactionBotRegistry} so its brain ticks.</li>
 * </ol>
 *
 * <p>Workers, additional buildings, and the brain build-loop are layered on top
 * in later slices.
 */
public final class VillageSiteService {

    /** Starting resources for a newly-spawned village. Tuned for slice 1 debug use. */
    public static final int STARTING_FOOD = 300;
    public static final int STARTING_WOOD = 300;
    public static final int STARTING_ORE = 100;

    private VillageSiteService() {}

    /**
     * Project a candidate position to the column's real ground Y.
     *
     * <p>Delegates to {@link Terrain#snapToGround} — see that method for the
     * heightmap off-by-one correction and the tree/leaf skip logic that
     * prevents capitols from landing on tree-tops.
     */
    public static BlockPos snapToGround(ServerLevel level, BlockPos candidate) {
        return Terrain.snapToGround(level, candidate);
    }

    /**
     * Spawn a village of the given faction at {@code centre}. Returns the
     * created bot, or {@code null} if placement failed.
     *
     * <p>The {@code centre} position is automatically projected to the
     * world-surface heightmap so the capitol always sits on the ground rather
     * than floating in mid-air.
     */
    @Nullable
    public static FactionBot spawnVillage(
        ServerLevel level,
        Faction faction,
        BlockPos centre
    ) {
        // Snap to ground level so the capitol never spawns in the air.
        centre = snapToGround(level, centre);

        // Refuse to spawn the capitol if any part of its footprint sits on
        // water or lava — workers can't path to it and it'd be half-submerged.
        // halfExtent=12 covers all three faction capitols (TOWN_CENTRE,
        // MAUSOLEUM, CENTRAL_PORTAL) which are ~18–20 blocks across, plus a
        // little margin for the worker spawn ring around the capitol. The
        // previous 8 occasionally let a corner end up in a lake/river.
        if (
            Terrain.isLiquidInFootprint(level, centre.getX(), centre.getZ(), 12)
        ) {
            LivingWorld.LOGGER.warn(
                "[LivingWorld] Refusing to spawn {} village at {} — footprint overlaps liquid (water/lava)",
                faction,
                centre
            );
            return null;
        }
        if (faction == Faction.NEUTRAL || faction == Faction.NONE) {
            LivingWorld.LOGGER.warn(
                "[LivingWorld] Refusing to spawn village with faction {}",
                faction
            );
            return null;
        }

        Building capitol = capitolFor(faction);
        if (capitol == null) {
            LivingWorld.LOGGER.warn(
                "[LivingWorld] No capitol building defined for faction {}",
                faction
            );
            return null;
        }

        String botName = Names.villageBotName(faction, centre);

        // 1. Bot RTSPlayer
        RTSPlayer bot;
        synchronized (PlayerServerEvents.rtsPlayers) {
            if (PlayerServerEvents.getRTSPlayer(botName) != null) {
                LivingWorld.LOGGER.warn(
                    "[LivingWorld] Village {} already exists, skipping",
                    botName
                );
                return null;
            }
            bot = RTSPlayer.getNewBot(botName, faction);
            PlayerServerEvents.rtsPlayers.add(bot);
        }

        // 2. Resource pool — required so RoN's canAfford / addSubtractResources
        //    paths can mutate this bot's totals when workers deposit / buildings
        //    consume.
        ResourcesServerEvents.resourcesList.add(
            new Resources(botName, STARTING_FOOD, STARTING_WOOD, STARTING_ORE)
        );

        // 3. + 4. Place the capitol. fromCommand=true means RoN will:
        //    - skip the resource cost check
        //    - skip the terrain-overlap check
        //    - clear the building area for us
        // We then mark it as selfBuilding so the BuildingServerEvents tick
        // loop constructs it block-by-block with no workers required.
        BuildingPlacement placement = BuildingServerEvents.placeBuilding(
            capitol,
            centre,
            Rotation.NONE,
            botName,
            new int[] {},
            /*queue=*/ false,
            /*isDiagonalBridge=*/ false,
            /*fromCommand=*/ true
        );
        if (placement == null) {
            LivingWorld.LOGGER.error(
                "[LivingWorld] placeBuilding returned null for {} at {}",
                capitol.name,
                centre
            );
            rollback(botName);
            return null;
        }
        placement.selfBuilding = true;

        FactionBot factionBot = new FactionBot(bot, faction, centre);
        factionBot.capitol = placement;

        // 5. Alliance with non-RTS players only, so vanilla / survival
        //    players can walk through villages without getting attacked.
        //    RTS players are deliberately excluded — they should be HOSTILE
        //    to NPC factions so they have enemies to fight.
        for (ServerPlayer player : level
            .getServer()
            .getPlayerList()
            .getPlayers()) {
            String playerName = player.getName().getString();
            if (!PlayerServerEvents.isRTSPlayer(playerName)) {
                AlliancesServerEvents.addAlliance(botName, playerName);
            }
        }

        // 6. Register so brain ticks pick it up.
        FactionBotRegistry.add(factionBot);

        // 6b. Force-load the chunks around the capitol so the village keeps
        //     ticking even when no player is nearby — brain progression,
        //     worker gather, and patrol scouts all need the surrounding
        //     chunks live, not just the capitol's own centre chunk that
        //     RoN auto-force-loads per-building.
        VillageChunkLoader.forceLoad(level, factionBot);

        // 6c. Pre-spawn satellite buildings (stockpile + 2 houses + farm) so
        //     the village looks settled the moment a player discovers it.
        //     The buildings self-construct over the next few seconds via
        //     selfBuilding=true; the chunks are force-loaded so this happens
        //     regardless of player presence. We advance the brain's build
        //     cursor by the number actually placed so it resumes at the
        //     next un-built step (typically barracks / watchtower tier).
        //     Runs BEFORE worker spawn so the outer ring (22–35 blocks) is
        //     occupied before the worker ring (12 blocks) fills in around it.
        int satellitesPlaced = SatelliteStructures.prespawn(level, factionBot);
        if (satellitesPlaced > 0) {
            factionBot.brain.skipSteps(satellitesPlaced);
        }

        // 6d. Starter garrison — a small group of military units to defend
        //     the village while the brain teches up to barracks. Same
        //     PRESPAWN_SATELLITES toggle.
        SatelliteUnits.prespawn(level, factionBot);

        // 7. Spawn starter workers around the capitol. They have autocastRepair
        // enabled so they will immediately start helping construct the capitol,
        // and once it's complete they remain available for follow-up brain
        // build orders (slice 3+).
        WorkerSpawner.spawnStarterWorkers(level, faction, centre, botName);

        LivingWorld.LOGGER.info(
            "[LivingWorld] Spawned village {} ({}) at {}",
            botName,
            faction,
            centre
        );
        return factionBot;
    }

    /** Picks the canonical "town hall" capitol building for each faction. */
    @Nullable
    public static Building capitolFor(Faction faction) {
        return switch (faction) {
            case VILLAGERS -> Buildings.TOWN_CENTRE;
            case MONSTERS -> Buildings.MAUSOLEUM;
            case PIGLINS -> Buildings.CENTRAL_PORTAL;
            default -> null;
        };
    }

    /** Undo a partial bootstrap if capitol placement fails after registry mutation. */
    private static void rollback(String botName) {
        ResourcesServerEvents.resourcesList.removeIf(r ->
            r.ownerName.equals(botName)
        );
        synchronized (PlayerServerEvents.rtsPlayers) {
            PlayerServerEvents.rtsPlayers.removeIf(p -> p.name.equals(botName));
        }
    }
}
