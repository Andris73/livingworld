package com.livingworld.command;

import com.livingworld.beacon.BeaconLeaderboard;
import com.livingworld.beacon.BeaconOfOrigins;
import com.livingworld.bot.FactionBot;
import com.livingworld.bot.FactionBotRegistry;
import com.livingworld.bot.StuckBuilderRescue;
import com.livingworld.bot.WorkerSpawner;
import com.livingworld.config.LivingWorldConfig;
import com.livingworld.reputation.RepTier;
import com.livingworld.reputation.ReputationManager;
import com.livingworld.reputation.ReputationSaveData;
import com.livingworld.world.ResourceIndex;
import com.livingworld.worldgen.FactionPicker;
import com.livingworld.worldgen.VillageReplacer;
import com.livingworld.worldgen.VillageSiteService;
import com.mojang.brigadier.Command;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.brigadier.suggestion.SuggestionProvider;
import com.solegendary.reignofnether.faction.Faction;
import com.solegendary.reignofnether.resources.ResourceName;
import com.solegendary.reignofnether.unit.UnitServerEvents;
import com.solegendary.reignofnether.unit.interfaces.Unit;
import com.solegendary.reignofnether.unit.interfaces.WorkerUnit;
import java.util.Locale;
import java.util.Random;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.commands.SharedSuggestionProvider;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.Registries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.chunk.ChunkStatus;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.levelgen.structure.Structure;
import net.minecraft.world.level.levelgen.structure.StructureStart;
import net.minecraftforge.event.RegisterCommandsEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;

/**
 * Debug commands for the Living World mod.
 *
 * Slice-1 surface:
 * <ul>
 *   <li>{@code /livingworld spawn auto} — pick a faction by biome (with the
 *       wildcard chance) and spawn a village at the executor's position.</li>
 *   <li>{@code /livingworld spawn <faction>} — explicitly choose villagers,
 *       monsters or piglins.</li>
 *   <li>{@code /livingworld list} — print active NPC villages.</li>
 *   <li>{@code /livingworld clear} — remove all NPC villages (server state
 *       only; capitol structures remain in-world for now).</li>
 *   <li>{@code /livingworld config replace-villages <true|false>} — toggle
 *       vanilla village replacement on/off.</li>
 *   <li>{@code /livingworld replace-here} — scan a 64-block radius around
 *       the player for vanilla village blocks and replace any village found.
 *       Useful for already-loaded chunks where the chunk-load event won't
 *       fire (e.g. previously-explored villages).</li>
 * </ul>
 *
 * These commands require permission level 2 (operator) so non-OP players on a
 * server can't trigger them.
 */
public class LivingWorldCommands {

    private static final SuggestionProvider<
        CommandSourceStack
    > FACTION_SUGGESTIONS = (ctx, builder) ->
        SharedSuggestionProvider.suggest(
            new String[] { "auto", "villagers", "monsters", "piglins" },
            builder
        );

    @SubscribeEvent
    public static void onRegister(RegisterCommandsEvent evt) {
        LiteralArgumentBuilder<CommandSourceStack> root = Commands.literal(
            "livingworld"
        ).requires(src -> src.hasPermission(2));

        root.then(
            Commands.literal("spawn").then(
                Commands.argument("faction", StringArgumentType.word())
                    .suggests(FACTION_SUGGESTIONS)
                    .executes(LivingWorldCommands::spawnHere)
            )
        );

        root.then(Commands.literal("list").executes(LivingWorldCommands::list));

        root.then(
            Commands.literal("clear").executes(LivingWorldCommands::clear)
        );

        root.then(
            Commands.literal("config").then(
                Commands.literal("replace-villages").then(
                    Commands.argument("enabled", StringArgumentType.word())
                        .suggests((ctx, builder) ->
                            SharedSuggestionProvider.suggest(
                                new String[] { "true", "false" },
                                builder
                            )
                        )
                        .executes(LivingWorldCommands::configReplaceVillages)
                )
            )
        );

        root.then(
            Commands.literal("config").then(
                Commands.literal("replace-neutrals").then(
                    Commands.argument("enabled", StringArgumentType.word())
                        .suggests((ctx, builder) ->
                            SharedSuggestionProvider.suggest(
                                new String[] { "true", "false" },
                                builder
                            )
                        )
                        .executes(LivingWorldCommands::configReplaceNeutrals)
                )
            )
        );

        root.then(
            Commands.literal("config").then(
                Commands.literal("free-builds").then(
                    Commands.argument("enabled", StringArgumentType.word())
                        .suggests((ctx, builder) ->
                            SharedSuggestionProvider.suggest(
                                new String[] { "true", "false" },
                                builder
                            )
                        )
                        .executes(LivingWorldCommands::configFreeBuilds)
                )
            )
        );

        root.then(
            Commands.literal("config").then(
                Commands.literal("prespawn-satellites").then(
                    Commands.argument("enabled", StringArgumentType.word())
                        .suggests((ctx, builder) ->
                            SharedSuggestionProvider.suggest(
                                new String[] { "true", "false" },
                                builder
                            )
                        )
                        .executes(LivingWorldCommands::configPrespawnSatellites)
                )
            )
        );

        root.then(
            Commands.literal("replace-here").executes(
                LivingWorldCommands::replaceHere
            )
        );

        root.then(
            Commands.literal("scan-villages").executes(
                LivingWorldCommands::scanVillages
            )
        );

        // /livingworld locate <village> — print the village's coordinates.
        // Tab-completion lists every currently-registered village by name.
        root.then(
            Commands.literal("locate").then(
                Commands.argument("village", StringArgumentType.word())
                    .suggests((ctx, b) -> {
                        for (var bot : FactionBotRegistry.all()) {
                            b.suggest(bot.name());
                        }
                        return b.buildFuture();
                    })
                    .executes(LivingWorldCommands::locateVillage)
            )
        );

        root.then(
            Commands.literal("spawn-workers").executes(
                LivingWorldCommands::spawnWorkersAtNearestVillage
            )
        );

        root.then(
            Commands.literal("spawn-raid").executes(
                LivingWorldCommands::spawnRaid
            )
        );

        root.then(
            Commands.literal("beacon")
                .then(
                    Commands.literal("status").executes(
                        LivingWorldCommands::beaconStatus
                    )
                )
                .then(
                    Commands.literal("history").executes(
                        LivingWorldCommands::beaconHistory
                    )
                )
                .then(
                    Commands.literal("spawn").executes(
                        LivingWorldCommands::beaconSpawn
                    )
                )
        );

        root.then(
            Commands.literal("inspect-workers").executes(
                LivingWorldCommands::inspectWorkers
            )
        );

        // /livingworld rep — any player can check their own rep
        root.then(
            Commands.literal("rep")
                .executes(LivingWorldCommands::repSelf)
                .then(
                    Commands.argument(
                        "player",
                        net.minecraft.commands.arguments.EntityArgument.player()
                    )
                        .requires(src -> src.hasPermission(2))
                        .executes(LivingWorldCommands::repOther)
                )
                .then(
                    Commands.literal("set")
                        .requires(src -> src.hasPermission(2))
                        .then(
                            Commands.argument(
                                "faction",
                                StringArgumentType.word()
                            )
                                .suggests((ctx, b) ->
                                    net.minecraft.commands.SharedSuggestionProvider.suggest(
                                        new String[] {
                                            "villagers",
                                            "monsters",
                                            "piglins",
                                        },
                                        b
                                    )
                                )
                                .then(
                                    Commands.argument(
                                        "value",
                                        com.mojang.brigadier.arguments.IntegerArgumentType.integer(
                                            -1000,
                                            1000
                                        )
                                    ).executes(LivingWorldCommands::repSet)
                                )
                        )
                )
        );

        evt.getDispatcher().register(root);
    }

    // ---------------------------------------------------------------- spawn

    private static int spawnHere(
        com.mojang.brigadier.context.CommandContext<CommandSourceStack> ctx
    ) {
        CommandSourceStack src = ctx.getSource();
        ServerPlayer player = src.getPlayer();
        if (player == null) {
            src.sendFailure(
                Component.literal("This command must be run by a player.")
            );
            return 0;
        }

        ServerLevel level = player.serverLevel();
        BlockPos centre = player.blockPosition();
        String factionArg = StringArgumentType.getString(
            ctx,
            "faction"
        ).toLowerCase(Locale.ROOT);

        Faction faction;
        if ("auto".equals(factionArg)) {
            faction = FactionPicker.pickFor(level, centre, new Random());
        } else {
            try {
                faction = Faction.valueOf(factionArg.toUpperCase(Locale.ROOT));
            } catch (IllegalArgumentException e) {
                src.sendFailure(
                    Component.literal(
                        "Unknown faction '" +
                            factionArg +
                            "'. Use one of: auto, villagers, monsters, piglins."
                    )
                );
                return 0;
            }
        }

        FactionBot bot = VillageSiteService.spawnVillage(
            level,
            faction,
            centre
        );
        if (bot == null) {
            src.sendFailure(
                Component.literal("Failed to spawn village (see server log).")
            );
            return 0;
        }
        src.sendSuccess(
            () ->
                Component.literal(
                    "Spawned " +
                        faction.name().toLowerCase() +
                        " village '" +
                        bot.name() +
                        "' at " +
                        centre.toShortString()
                ),
            true
        );
        return Command.SINGLE_SUCCESS;
    }

    // ---------------------------------------------------------------- list

    private static int list(
        com.mojang.brigadier.context.CommandContext<CommandSourceStack> ctx
    ) {
        CommandSourceStack src = ctx.getSource();
        var bots = FactionBotRegistry.all();
        if (bots.isEmpty()) {
            src.sendSuccess(
                () -> Component.literal("No active NPC villages."),
                false
            );
            return 0;
        }
        src.sendSuccess(
            () -> Component.literal("Active NPC villages: " + bots.size()),
            false
        );
        for (FactionBot bot : bots) {
            src.sendSuccess(
                () ->
                    Component.literal(
                        " - " +
                            bot.name() +
                            " (" +
                            bot.faction.name() +
                            ") @ " +
                            bot.centrePos.toShortString()
                    ),
                false
            );
        }
        return bots.size();
    }

    // ---------------------------------------------------------------- clear

    private static int clear(
        com.mojang.brigadier.context.CommandContext<CommandSourceStack> ctx
    ) {
        int n = FactionBotRegistry.all().size();
        FactionBotRegistry.clear();
        ctx.getSource().sendSuccess(
            () ->
                Component.literal(
                    "Cleared " + n + " NPC village(s) from server state."
                ),
            true
        );
        return n;
    }

    // ---------------------------------------------------------------- beacon

    private static int beaconStatus(
        com.mojang.brigadier.context.CommandContext<CommandSourceStack> ctx
    ) {
        CommandSourceStack src = ctx.getSource();
        if (!BeaconOfOrigins.isSpawned()) {
            src.sendSuccess(
                () ->
                    Component.literal(
                        "Beacon of Origins: not yet spawned. Use /livingworld beacon spawn."
                    ),
                false
            );
            return 0;
        }
        var bp = BeaconOfOrigins.getPlacement();
        String owner = BeaconOfOrigins.currentOwner();
        src.sendSuccess(
            () ->
                Component.literal(
                    String.format(
                        "Beacon of Origins @ %s | owner: %s | built: %s",
                        bp == null ? "?" : bp.originPos.toShortString(),
                        owner.isEmpty() ? "unclaimed" : owner,
                        bp != null && bp.isBuilt ? "yes" : "no"
                    )
                ),
            false
        );
        return 1;
    }

    private static int beaconHistory(
        com.mojang.brigadier.context.CommandContext<CommandSourceStack> ctx
    ) {
        CommandSourceStack src = ctx.getSource();
        ServerPlayer player = src.getPlayer();
        if (player == null) {
            src.sendFailure(Component.literal("Player only."));
            return 0;
        }
        BeaconLeaderboard lb = BeaconLeaderboard.get(player.serverLevel());
        var events = lb.events();
        if (events.isEmpty()) {
            src.sendSuccess(
                () -> Component.literal("Beacon of Origins: no captures yet."),
                false
            );
            return 0;
        }
        src.sendSuccess(
            () ->
                Component.literal(
                    "Beacon of Origins capture history (" +
                        events.size() +
                        " events):"
                ),
            false
        );
        for (
            int i = events.size() - 1;
            i >= Math.max(0, events.size() - 10);
            i--
        ) {
            var e = events.get(i);
            final int idx = i + 1;
            src.sendSuccess(
                () ->
                    Component.literal(
                        String.format(
                            "  #%d: %s captured from %s (tick %d)",
                            idx,
                            e.capturedBy().isEmpty()
                                ? "abandoned"
                                : e.capturedBy(),
                            e.previousOwner().isEmpty()
                                ? "unclaimed"
                                : e.previousOwner(),
                            e.gameTick()
                        )
                    ),
                false
            );
        }
        return events.size();
    }

    private static int beaconSpawn(
        com.mojang.brigadier.context.CommandContext<CommandSourceStack> ctx
    ) {
        CommandSourceStack src = ctx.getSource();
        ServerPlayer player = src.getPlayer();
        if (player == null) {
            src.sendFailure(Component.literal("Player only."));
            return 0;
        }
        boolean ok = BeaconOfOrigins.manualSpawn(
            player.serverLevel(),
            player.blockPosition()
        );
        if (ok) {
            src.sendSuccess(
                () ->
                    Component.literal(
                        "Beacon of Origins spawned at your position!"
                    ),
                true
            );
            return 1;
        } else {
            src.sendFailure(
                Component.literal(
                    "Failed — beacon may already exist or placement was blocked."
                )
            );
            return 0;
        }
    }

    // -------------------------------------------------------------- spawn-raid

    /**
     * Spawn a small group of vanilla hostile mobs (3 zombies + 2 skeletons)
     * within ≈12 blocks of the player. Useful for testing whether
     * {@link com.livingworld.bot.HostileMobBehavior}'s targeting patch is
     * working without waiting for nightfall or exploring caves.
     */
    private static int spawnRaid(
        com.mojang.brigadier.context.CommandContext<CommandSourceStack> ctx
    ) {
        CommandSourceStack src = ctx.getSource();
        ServerPlayer player = src.getPlayer();
        if (player == null) {
            src.sendFailure(Component.literal("Player only."));
            return 0;
        }
        ServerLevel level = player.serverLevel();
        Random rng = new Random();

        int spawned = 0;
        EntityType<?>[] types = {
            EntityType.ZOMBIE,
            EntityType.ZOMBIE,
            EntityType.ZOMBIE,
            EntityType.SKELETON,
            EntityType.SKELETON,
        };
        for (EntityType<?> type : types) {
            double angle = rng.nextDouble() * Math.PI * 2;
            int dx = (int) Math.round(Math.cos(angle) * 12);
            int dz = (int) Math.round(Math.sin(angle) * 12);
            BlockPos here = player.blockPosition();
            int y = level.getHeight(
                net.minecraft.world.level.levelgen.Heightmap.Types.WORLD_SURFACE,
                here.getX() + dx,
                here.getZ() + dz
            );
            BlockPos spawnPos = new BlockPos(
                here.getX() + dx,
                y,
                here.getZ() + dz
            );
            Mob mob = (Mob) type.create(level);
            if (mob == null) continue;
            mob.moveTo(
                spawnPos.getX() + 0.5,
                spawnPos.getY(),
                spawnPos.getZ() + 0.5,
                rng.nextFloat() * 360f,
                0f
            );
            mob.setPersistenceRequired(); // don't despawn so we can observe the raid
            level.addFreshEntity(mob);
            spawned++;
        }
        final int spawnedF = spawned;
        src.sendSuccess(
            () ->
                Component.literal(
                    "Spawned a raid of " +
                        spawnedF +
                        " hostile mobs near you. They should target nearby units."
                ),
            true
        );
        return spawned;
    }

    // ---------------------------------------------------------- spawn-workers

    /**
     * Spawn an additional batch of starter workers around the player's nearest
     * NPC village. Useful for testing slice 2 on existing villages without
     * re-running {@code /livingworld spawn}.
     */
    private static int spawnWorkersAtNearestVillage(
        com.mojang.brigadier.context.CommandContext<CommandSourceStack> ctx
    ) {
        CommandSourceStack src = ctx.getSource();
        ServerPlayer player = src.getPlayer();
        if (player == null) {
            src.sendFailure(Component.literal("Player only."));
            return 0;
        }

        BlockPos here = player.blockPosition();
        FactionBot nearest = null;
        double bestDist = Double.MAX_VALUE;
        for (FactionBot bot : FactionBotRegistry.all()) {
            double d = bot.centrePos.distSqr(here);
            if (d < bestDist) {
                bestDist = d;
                nearest = bot;
            }
        }
        if (nearest == null) {
            src.sendFailure(
                Component.literal(
                    "No NPC villages registered. Run /livingworld spawn <faction> first."
                )
            );
            return 0;
        }

        final FactionBot bot = nearest;
        var spawned = WorkerSpawner.spawnStarterWorkers(
            player.serverLevel(),
            bot.faction,
            bot.centrePos,
            bot.name()
        );
        src.sendSuccess(
            () ->
                Component.literal(
                    "Spawned " +
                        spawned.size() +
                        " worker(s) for " +
                        bot.name() +
                        " at " +
                        bot.centrePos.toShortString()
                ),
            true
        );
        return spawned.size();
    }

    // -------------------------------------------------------- inspect-workers

    /**
     * List every worker owned by the player's nearest NPC village along with
     * its current state: position, gather role, gather target, build target,
     * and whether RoN considers it idle. Great for debugging "my workers are
     * just standing there".
     */
    private static int inspectWorkers(
        com.mojang.brigadier.context.CommandContext<CommandSourceStack> ctx
    ) {
        CommandSourceStack src = ctx.getSource();
        ServerPlayer player = src.getPlayer();
        if (player == null) {
            src.sendFailure(Component.literal("Player only."));
            return 0;
        }

        BlockPos here = player.blockPosition();
        FactionBot nearest = null;
        double bestDist = Double.MAX_VALUE;
        for (FactionBot bot : FactionBotRegistry.all()) {
            double d = bot.centrePos.distSqr(here);
            if (d < bestDist) {
                bestDist = d;
                nearest = bot;
            }
        }
        if (nearest == null) {
            src.sendFailure(Component.literal("No NPC villages registered."));
            return 0;
        }

        final FactionBot bot = nearest;
        int total = 0,
            idle = 0,
            gathering = 0,
            building = 0,
            none = 0;
        int foodCount = 0,
            woodCount = 0,
            oreCount = 0;

        for (LivingEntity entity : UnitServerEvents.getAllUnits()) {
            if (!(entity instanceof WorkerUnit worker)) continue;
            if (!(entity instanceof Unit unit)) continue;
            if (!unit.getOwnerName().equals(bot.name())) continue;

            total++;
            var gatherGoal = worker.getGatherResourceGoal();
            var buildGoal = worker.getBuildRepairGoal();
            ResourceName role =
                gatherGoal == null
                    ? ResourceName.NONE
                    : gatherGoal.getTargetResourceName();

            boolean isBuilding =
                buildGoal != null && buildGoal.getBuildingTarget() != null;
            boolean isGathering =
                gatherGoal != null && gatherGoal.isGathering();
            boolean isIdle = WorkerUnit.isIdle(worker);

            if (isBuilding) building++;
            else if (isGathering) gathering++;
            else if (role == ResourceName.NONE) none++;
            else if (isIdle) idle++;

            switch (role) {
                case FOOD -> foodCount++;
                case WOOD -> woodCount++;
                case ORE -> oreCount++;
                default -> {
                }
            }

            String state;
            if (isBuilding) {
                state = StuckBuilderRescue.appearsStuck(worker)
                    ? "BUILDING (stuck)"
                    : "BUILDING";
            } else if (isGathering) state = "GATHERING";
            else if (role == ResourceName.NONE) state = "NO ROLE";
            else if (isIdle) state = "IDLE";
            else state = "searching/walking";

            // For IDLE workers, ask the resource index what's nearby — helps
            // diagnose whether the issue is no-resources-in-range vs the
            // nudger silently failing to set a target.
            String diagnosticTail = "";
            if (isIdle && role != ResourceName.NONE) {
                BlockPos nearestResource = ResourceIndex.get(
                    player.serverLevel()
                ).findNearest(
                    player.serverLevel(),
                    entity.blockPosition(),
                    role,
                    200,
                    java.util.Set.of()
                );
                if (nearestResource == null) {
                    diagnosticTail = "  (no " + role + " in 200-block radius)";
                } else {
                    double dist = Math.sqrt(
                        nearestResource.distSqr(entity.blockPosition())
                    );
                    diagnosticTail = String.format(
                        "  (nearest %s @ %s, %.1f blocks)",
                        role,
                        nearestResource.toShortString(),
                        dist
                    );
                }
            }

            final int eid = entity.getId();
            final BlockPos pos = entity.blockPosition();
            final ResourceName roleFinal = role;
            final String stateFinal = state;
            final String diagFinal = diagnosticTail;
            src.sendSuccess(
                () ->
                    Component.literal(
                        String.format(
                            "  worker #%d @ %s role=%s state=%s%s",
                            eid,
                            pos.toShortString(),
                            roleFinal,
                            stateFinal,
                            diagFinal
                        )
                    ),
                false
            );
        }

        final int totalF = total,
            idleF = idle,
            gatheringF = gathering,
            buildingF = building,
            noneF = none;
        final int foodF = foodCount,
            woodF = woodCount,
            oreF = oreCount;
        src.sendSuccess(
            () ->
                Component.literal(
                    String.format(
                        "Total %d | building %d | gathering %d | idle %d | no role %d  |  roles F:%d W:%d O:%d",
                        totalF,
                        buildingF,
                        gatheringF,
                        idleF,
                        noneF,
                        foodF,
                        woodF,
                        oreF
                    )
                ),
            false
        );
        return total;
    }

    // ----------------------------------------------------------- scan-villages

    /**
     * Diagnostic: scan loaded chunks around the player for vanilla village
     * structure starts and print their bounding boxes. Helps verify whether the
     * datapack override is taking effect (bbox span should be ≤1 for new
     * villages) versus pre-existing vanilla villages (bbox span ≥50).
     */
    private static int scanVillages(
        com.mojang.brigadier.context.CommandContext<CommandSourceStack> ctx
    ) {
        CommandSourceStack src = ctx.getSource();
        ServerPlayer player = src.getPlayer();
        if (player == null) {
            src.sendFailure(Component.literal("Player only."));
            return 0;
        }
        ServerLevel level = player.serverLevel();
        ChunkPos centre = new ChunkPos(player.blockPosition());
        var structureRegistry = level
            .registryAccess()
            .registryOrThrow(Registries.STRUCTURE);

        int found = 0;
        // Scan a 9x9 chunk area around the player using LOADED-ONLY access.
        for (int dx = -4; dx <= 4; dx++) {
            for (int dz = -4; dz <= 4; dz++) {
                var chunkAccess = level.getChunk(
                    centre.x + dx,
                    centre.z + dz,
                    ChunkStatus.FULL,
                    false
                );
                if (!(chunkAccess instanceof LevelChunk chunk)) continue;

                for (var entry : chunk.getAllStarts().entrySet()) {
                    Structure structure = entry.getKey();
                    StructureStart start = entry.getValue();
                    ResourceLocation key = structureRegistry.getKey(structure);
                    if (
                        key == null ||
                        !key.toString().startsWith("minecraft:village")
                    ) continue;
                    if (!start.isValid()) continue;

                    var bbox = start.getBoundingBox();
                    int span = Math.max(bbox.getXSpan(), bbox.getZSpan());
                    String kind =
                        span > 4 ? "pre-existing" : "empty (datapack)";
                    found++;
                    src.sendSuccess(
                        () ->
                            Component.literal(
                                String.format(
                                    "  %s %s @ %s, span=%d",
                                    key,
                                    kind,
                                    bbox.getCenter().toShortString(),
                                    span
                                )
                            ),
                        false
                    );
                }
            }
        }
        final int total = found;
        src.sendSuccess(
            () ->
                Component.literal(
                    "Scan complete: " +
                        total +
                        " village structure(s) in loaded chunks within 4 chunks."
                ),
            false
        );
        return total;
    }

    // ------------------------------------------------------------ locate

    /**
     * {@code /livingworld locate <village>} — prints the village's centre
     * coordinates, distance from the caller (if a player), and faction.
     * Tab-completion on the {@code village} argument suggests every
     * currently-registered bot name from {@link FactionBotRegistry}.
     *
     * <p>Equivalent in spirit to vanilla {@code /locate structure ...} but
     * for our autonomous villages, which aren't tagged as structures and
     * therefore don't show up in the vanilla command. Useful for picking
     * up where you left off after exploring a long way from a known village.
     */
    private static int locateVillage(
        com.mojang.brigadier.context.CommandContext<CommandSourceStack> ctx
    ) {
        CommandSourceStack src = ctx.getSource();
        String name = StringArgumentType.getString(ctx, "village");

        FactionBot match = null;
        for (FactionBot bot : FactionBotRegistry.all()) {
            if (bot.name().equals(name)) {
                match = bot;
                break;
            }
        }
        if (match == null) {
            src.sendFailure(
                Component.literal("No registered village named '" + name + "'.")
            );
            return 0;
        }

        final BlockPos pos = match.centrePos;
        final String factionName = match.faction.name().toLowerCase();

        // Distance from caller if available, otherwise just the coordinates.
        ServerPlayer player = src.getPlayer();
        final String distanceLine;
        if (player != null) {
            double dist = Math.sqrt(player.blockPosition().distSqr(pos));
            distanceLine = String.format(" (%.0f blocks from you)", dist);
        } else {
            distanceLine = "";
        }

        src.sendSuccess(
            () ->
                Component.literal(
                    String.format(
                        "%s (%s) is at %d, %d, %d%s",
                        name,
                        factionName,
                        pos.getX(),
                        pos.getY(),
                        pos.getZ(),
                        distanceLine
                    )
                ),
            false
        );
        return 1;
    }

    // ------------------------------------------------------------ replace-here

    private static int replaceHere(
        com.mojang.brigadier.context.CommandContext<CommandSourceStack> ctx
    ) {
        CommandSourceStack src = ctx.getSource();
        ServerPlayer player = src.getPlayer();
        if (player == null) {
            src.sendFailure(
                Component.literal("This command must be run by a player.")
            );
            return 0;
        }

        ServerLevel level = player.serverLevel();
        BlockPos near = player.blockPosition();

        BlockPos villageCenter = VillageReplacer.findVillageByBlockScan(
            level,
            near,
            64
        );
        if (villageCenter == null) {
            src.sendFailure(
                Component.literal(
                    "No vanilla village blocks found within 64 blocks. " +
                        "Try standing closer to the village centre."
                )
            );
            return 0;
        }

        VillageReplacer.enqueueReplacement(villageCenter);
        src.sendSuccess(
            () ->
                Component.literal(
                    "Queued village at " +
                        villageCenter.toShortString() +
                        " for replacement. " +
                        "Capitol will appear within a few seconds; surrounding blocks clear gradually."
                ),
            true
        );
        return 1;
    }

    // ---------------------------------------------------------------- config

    private static int configReplaceVillages(
        com.mojang.brigadier.context.CommandContext<CommandSourceStack> ctx
    ) {
        String enabledArg = StringArgumentType.getString(
            ctx,
            "enabled"
        ).toLowerCase(Locale.ROOT);
        boolean enabled;
        if ("true".equals(enabledArg)) {
            enabled = true;
        } else if ("false".equals(enabledArg)) {
            enabled = false;
        } else {
            ctx.getSource().sendFailure(
                Component.literal("Use 'true' or 'false'")
            );
            return 0;
        }

        LivingWorldConfig.REPLACE_VANILLA_VILLAGES = enabled;
        ctx.getSource().sendSuccess(
            () ->
                Component.literal(
                    "Vanilla village replacement: " +
                        (enabled ? "enabled" : "disabled")
                ),
            true
        );
        return enabled ? 1 : 0;
    }

    private static int configReplaceNeutrals(
        com.mojang.brigadier.context.CommandContext<CommandSourceStack> ctx
    ) {
        String enabledArg = StringArgumentType.getString(
            ctx,
            "enabled"
        ).toLowerCase(Locale.ROOT);
        boolean enabled;
        if ("true".equals(enabledArg)) {
            enabled = true;
        } else if ("false".equals(enabledArg)) {
            enabled = false;
        } else {
            ctx.getSource().sendFailure(
                Component.literal("Use 'true' or 'false'")
            );
            return 0;
        }

        LivingWorldConfig.REPLACE_NEUTRAL_STRUCTURES = enabled;
        ctx.getSource().sendSuccess(
            () ->
                Component.literal(
                    "Neutral structure replacement: " +
                        (enabled ? "enabled" : "disabled")
                ),
            true
        );
        return enabled ? 1 : 0;
    }

    private static int configFreeBuilds(
        com.mojang.brigadier.context.CommandContext<CommandSourceStack> ctx
    ) {
        String enabledArg = StringArgumentType.getString(
            ctx,
            "enabled"
        ).toLowerCase(Locale.ROOT);
        boolean enabled;
        if ("true".equals(enabledArg)) {
            enabled = true;
        } else if ("false".equals(enabledArg)) {
            enabled = false;
        } else {
            ctx.getSource().sendFailure(
                Component.literal("Use 'true' or 'false'")
            );
            return 0;
        }

        LivingWorldConfig.FREE_BUILDS = enabled;
        ctx.getSource().sendSuccess(
            () ->
                Component.literal(
                    "Free builds (no resource cost): " +
                        (enabled ? "enabled" : "disabled")
                ),
            true
        );
        return enabled ? 1 : 0;
    }

    private static int configPrespawnSatellites(
        com.mojang.brigadier.context.CommandContext<CommandSourceStack> ctx
    ) {
        String enabledArg = StringArgumentType.getString(
            ctx,
            "enabled"
        ).toLowerCase(Locale.ROOT);
        boolean enabled;
        if ("true".equals(enabledArg)) {
            enabled = true;
        } else if ("false".equals(enabledArg)) {
            enabled = false;
        } else {
            ctx.getSource().sendFailure(
                Component.literal("Use 'true' or 'false'")
            );
            return 0;
        }

        LivingWorldConfig.PRESPAWN_SATELLITES = enabled;
        ctx.getSource().sendSuccess(
            () ->
                Component.literal(
                    "Pre-spawn satellite buildings + garrison: " +
                        (enabled ? "enabled" : "disabled") +
                        " (affects newly-spawned villages only)"
                ),
            true
        );
        return enabled ? 1 : 0;
    }

    // ------------------------------------------------------------------ rep

    private static int repSelf(
        com.mojang.brigadier.context.CommandContext<CommandSourceStack> ctx
    ) {
        ServerPlayer player = ctx.getSource().getPlayer();
        if (player == null) {
            ctx.getSource().sendFailure(Component.literal("Player only."));
            return 0;
        }
        return showRep(ctx.getSource(), player);
    }

    private static int repOther(
        com.mojang.brigadier.context.CommandContext<CommandSourceStack> ctx
    ) throws com.mojang.brigadier.exceptions.CommandSyntaxException {
        ServerPlayer target =
            net.minecraft.commands.arguments.EntityArgument.getPlayer(
                ctx,
                "player"
            );
        return showRep(ctx.getSource(), target);
    }

    private static int showRep(CommandSourceStack src, ServerPlayer target) {
        ServerLevel level = target.serverLevel();
        java.util.UUID uuid = target.getUUID();
        src.sendSuccess(
            () ->
                Component.literal(
                    String.format(
                        "=== Reputation: %s ===",
                        target.getName().getString()
                    )
                ),
            false
        );
        for (com.solegendary.reignofnether.faction.Faction f : new com.solegendary.reignofnether.faction.Faction[] {
            com.solegendary.reignofnether.faction.Faction.VILLAGERS,
            com.solegendary.reignofnether.faction.Faction.MONSTERS,
            com.solegendary.reignofnether.faction.Faction.PIGLINS,
        }) {
            int score = ReputationManager.getReputation(level, uuid, f);
            RepTier tier = RepTier.fromScore(score);
            // Collect active village names for this faction
            java.util.List<String> names = FactionBotRegistry.all()
                .stream()
                .filter(b -> b.faction == f)
                .map(b -> b.name())
                .collect(java.util.stream.Collectors.toList());
            String villageList = names.isEmpty()
                ? "(none)"
                : String.join(", ", names);
            final String line = String.format(
                "%-10s (%s): %-14s [%+d/1000]",
                capitalize(f.name()),
                villageList,
                tier.displayName(f),
                score
            );
            src.sendSuccess(() -> Component.literal(line), false);
        }
        return 1;
    }

    private static int repSet(
        com.mojang.brigadier.context.CommandContext<CommandSourceStack> ctx
    ) {
        ServerPlayer player = ctx.getSource().getPlayer();
        if (player == null) {
            ctx.getSource().sendFailure(Component.literal("Player only."));
            return 0;
        }
        String factionArg = StringArgumentType.getString(
            ctx,
            "faction"
        ).toUpperCase(Locale.ROOT);
        int value =
            com.mojang.brigadier.arguments.IntegerArgumentType.getInteger(
                ctx,
                "value"
            );
        com.solegendary.reignofnether.faction.Faction f;
        try {
            f = com.solegendary.reignofnether.faction.Faction.valueOf(
                factionArg
            );
        } catch (IllegalArgumentException e) {
            ctx.getSource().sendFailure(Component.literal("Unknown faction."));
            return 0;
        }
        ReputationManager.setReputation(
            player.serverLevel(),
            player.getUUID(),
            f,
            value
        );
        ctx.getSource().sendSuccess(
            () ->
                Component.literal(
                    String.format(
                        "Set %s rep for %s to %d",
                        f.name(),
                        player.getName().getString(),
                        value
                    )
                ),
            true
        );
        return 1;
    }

    private static String capitalize(String s) {
        if (s == null || s.isEmpty()) return s;
        return s.substring(0, 1) + s.substring(1).toLowerCase(Locale.ROOT);
    }
}
