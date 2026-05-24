package com.livingworld.brain;

import com.livingworld.LivingWorld;
import com.livingworld.bot.BeaconRaid;
import com.livingworld.bot.FactionBot;
import com.livingworld.bot.FactionResourceChest;
import com.livingworld.bot.HostileSweep;
import com.livingworld.bot.MobVigil;
import com.livingworld.bot.NightGuard;
import com.livingworld.bot.NightShelter;
import com.livingworld.bot.PatrolManager;
import com.livingworld.bot.StuckBuilderRescue;
import com.livingworld.bot.VillageHealth;
import com.livingworld.bot.WorkerAssignment;
import com.livingworld.bot.WorkerFlight;
import com.livingworld.bot.WorkerNudger;
import com.livingworld.config.LivingWorldConfig;
import com.solegendary.reignofnether.building.Building;
import com.solegendary.reignofnether.building.BuildingPlacement;
import com.solegendary.reignofnether.building.BuildingServerEvents;
import com.solegendary.reignofnether.faction.Faction;
import com.solegendary.reignofnether.resources.ResourceCost;
import com.solegendary.reignofnether.resources.ResourceName;
import com.solegendary.reignofnether.resources.ResourcesServerEvents;
import java.util.Random;
import javax.annotation.Nullable;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Rotation;

/**
 * Strategic AI for one {@link FactionBot}.
 *
 * <p><b>Slice 3 behaviour:</b> walk through a hard-coded {@link BuildOrder}
 * one project at a time. Each tick:
 * <ol>
 *   <li>If the capitol isn't finished yet, do nothing (workers handle it
 *       via {@code autocastRepair}).</li>
 *   <li>If a project is currently in progress, do nothing — wait for it.</li>
 *   <li>If we've finished all steps, do nothing (slice 4 will replace this with
 *       a reactive needs loop).</li>
 *   <li>Otherwise, pick the next step, find a site near the capitol, and
 *       place the building with {@code selfBuilding = true}. Idle workers
 *       (with {@code autocastRepair = true}) will path to it and help.</li>
 * </ol>
 *
 * <p><b>Resource cost is honest.</b> Brain projects use {@code fromCommand = false}
 * so RoN deducts the building's cost from the bot's resource pool, and refuses
 * to place if the bot can't afford it. When the bot is short on resources the
 * brain simply waits — each tick it re-checks, and meanwhile the idle workers
 * gather their assigned resource and deposit at the capitol. Once the pool
 * recovers, the next building gets placed. This gives villages a real gather →
 * build economy loop instead of materialising buildings out of thin air.
 *
 * <p>The <em>capitol</em> is still placed with {@code fromCommand = true} in
 * {@code VillageSiteService} so the village always bootstraps even if the seed
 * resources somehow get tampered with — only post-capitol expansion costs
 * resources.
 *
 * <p><b>Why {@code selfBuilding = false} on brain projects:</b> the capitol
 * uses {@code selfBuilding = true} as a safety net so the village always
 * bootstraps even with zero workers. After the capitol is up the village has
 * its starter workers, and we want <em>them</em> to do the construction so the
 * RTS feel comes through — workers visibly walking to sites and placing
 * blocks, not buildings spawning out of thin air. Workers have
 * {@code autocastRepair = true} so they'll find and build these projects on
 * their own as soon as they go idle.
 */
public class FactionBrain {

    private static final Random RNG = new Random();

    /** Available rotations for new buildings, picked at random for variety. */
    private static final Rotation[] ROTATIONS = Rotation.values();

    /**
     * How many brain ticks between patrol sweeps for military units.
     * Matches {@link PatrolManager#PATROL_INTERVAL_TICKS}.
     */
    private int patrolTick = 0;

    /**
     * How many consecutive brain ticks with no progress on the current build
     * before we abandon it. Brain ticks every 5s, so 18 ≈ 90 seconds without
     * a single block being placed. Long enough to weather slow construction
     * (workers cycling between gather and build) but short enough that
     * genuinely unreachable projects get cleaned up quickly. After abandon
     * we {@code destroy()} the placement so {@code autocastRepair} stops
     * finding it as a candidate — otherwise stuck workers get re-assigned
     * to the same unreachable building immediately.
     */
    private static final int PROJECT_ABANDON_TICKS = 18;

    /**
     * Hard cap on owned buildings per village. Once we reach this many,
     * we stop queuing growth-loop projects so villages don't sprawl forever
     * and crowd out other factions / players.
     */
    public static final int MAX_BUILDINGS_PER_VILLAGE = 30;

    private final FactionBot bot;
    private int currentStepIndex = 0;

    /**
     * The placement we most recently issued. {@code null} means we have no
     * active project and can plan the next one on the next tick.
     */
    @Nullable
    private BuildingPlacement currentProject;

    /**
     * Snapshot of {@code currentProject.getBlocksPlaced()} from the previous
     * brain tick — used to detect stalled builds for abandonment.
     */
    private int lastBlocksPlaced = -1;

    /**
     * How many consecutive brain ticks the current project has shown no
     * progress. Reset to 0 every time blocksPlaced increases.
     */
    private int projectStallTicks = 0;

    public FactionBrain(FactionBot bot) {
        this.bot = bot;
    }

    public FactionBot bot() {
        return bot;
    }

    public int currentStepIndex() {
        return currentStepIndex;
    }

    /**
     * Advance the build-order cursor past {@code count} steps without actually
     * executing them. Used by {@link com.livingworld.bot.SatelliteStructures}
     * to skip the early-game economy steps that get pre-spawned at village
     * creation; the brain then picks up at the first un-built step (usually
     * barracks/watchtower).
     *
     * <p>Negative or zero counts are ignored. Indices past the main sequence
     * naturally wrap into the growth loop via {@link BuildOrder#stepAt},
     * so no clamping is needed.
     */
    public void skipSteps(int count) {
        if (count <= 0) return;
        currentStepIndex += count;
        LivingWorld.LOGGER.debug(
            "[Brain {}] skipped {} build step(s) — cursor now at {}",
            bot.name(),
            count,
            currentStepIndex
        );
    }

    public int mainSequenceSize() {
        return BuildOrder.mainSequenceSize(bot.faction);
    }

    /**
     * Whether we're past the main tech-tree sequence and into the growth
     * loop phase. Diagnostic only.
     */
    public boolean inGrowthPhase() {
        return currentStepIndex >= mainSequenceSize();
    }

    /**
     * The next build-order step we'd attempt, or {@code null} if we're already
     * actively on one or have hit the building cap. Used by
     * {@link WorkforceAllocator} to bias worker roles toward whatever resource
     * the next building will need.
     */
    @Nullable
    public BuildOrder.Step peekNextStep() {
        if (currentProject != null) return null;
        if (atBuildingCap()) return null;
        return BuildOrder.stepAt(bot.faction, currentStepIndex);
    }

    /** True if the bot already owns the maximum allowed number of buildings. */
    private boolean atBuildingCap() {
        int owned = 0;
        for (BuildingPlacement bp : com.solegendary.reignofnether.building.BuildingServerEvents.getBuildings()) {
            if (bp.ownerName.equals(bot.name())) owned++;
        }
        return owned >= MAX_BUILDINGS_PER_VILLAGE;
    }

    @Nullable
    public BuildingPlacement currentProject() {
        return currentProject;
    }

    public void tick(Level level) {
        if (!(level instanceof ServerLevel serverLevel)) return;
        if (bot.capitol == null) return;

        // 1. Wait for the capitol to finish before starting expansion.
        if (!bot.capitol.isBuilt) return;

        // 2. Keep workers / military units topped up over time. Safe to call
        //    every tick — it's a no-op when targets are met or queues are full.
        UnitProducer.tick(bot);

        // 2b/2c. Worker job scheduling — role assignment + gather nudging.
        //     Skipped wholesale for villager bots at night per the shelter
        //     design: sheltered villagers have their role wiped to NONE
        //     and we don't want a job re-assigned that would lure them
        //     out of the house. NightShelter's own suppression normally
        //     handles this but can't be relied on alone (e.g. if a
        //     sheltered worker engages in combat, their shelter entry
        //     used to get cleared, leaving them re-assignable). This
        //     blanket time-based gate provides defence-in-depth.
        //     Non-villager factions (monsters, piglins) are unaffected
        //     and keep gathering at night as before.
        boolean villagerNight =
            serverLevel.isNight() && bot.faction == Faction.VILLAGERS;
        if (!villagerNight) {
            // 2b. Assign FOOD/WOOD/ORE/HUNTER roles to any worker without one
            //     (e.g. units just produced by the capitol). Starter workers get
            //     roles in WorkerSpawner already; this catches everyone else.
            WorkerAssignment.assignRolesToUnassignedWorkers(bot, serverLevel);

            // 2c. Wider-radius rescue: idle workers with a role whose 25-block
            //     RoN search can't reach a resource get directly fed a target
            //     from a 200-block index lookup. Also runs the water-crossing
            //     decision (BridgeBuilder) and unreachable-resource cache
            //     (UnreachableResources) so targets across water are either
            //     bridged or shelved.
            WorkerNudger.nudgeIdleWorkers(serverLevel, bot);
        }

        // 2d. Stuck-builder rescue: workers assigned to an unreachable
        //     building (e.g. across water) appear "BUILDING" but never make
        //     progress. Clear their build target so other rescue paths can
        //     redirect them.
        StuckBuilderRescue.rescueStuck(bot.name());

        // 2e-pre. MobVigil: 24/7 proactive engagement of nearby vanilla
        //         hostile mobs by ALL bot AttackerUnits (workers and
        //         military). Runs BEFORE patrol/guard so military with a
        //         vigil target doesn't get yanked off to a waypoint, and
        //         before WorkerFlight so workers fight instead of flee.
        MobVigil.tick(serverLevel, bot);

        // 2e. Military disposition. Two mutually-exclusive systems based on
        //     time-of-day + faction policy:
        //       • Night for villager bots — NightGuard stations military in
        //         a ring around each house to defend the workers sheltering
        //         inside. Runs every brain tick so newly-produced units join
        //         the rotation quickly.
        //       • Everything else — PatrolManager dispatches scouts at the
        //         {@code PATROL_INTERVAL_TICKS} cadence, with hostile-player
        //         hunting baked in.
        //     The patrol cadence counter only advances on patrol ticks so a
        //     dawn handoff resumes patrols promptly rather than waiting up
        //     to PATROL_INTERVAL_TICKS into the morning.
        if (serverLevel.isNight() && NightGuard.shouldGuard(bot.faction)) {
            NightGuard.tick(serverLevel, bot);
        } else {
            patrolTick++;
            if (patrolTick >= PatrolManager.PATROL_INTERVAL_TICKS) {
                PatrolManager.tick(serverLevel, bot, bot.centrePos);
                patrolTick = 0;
            }
        }

        // 2e′. Beacon raid: every brain tick, a small per-village chance
        //      to send one spare military unit toward the Beacon of
        //      Origins. Over many ticks across many villages this fills
        //      the area around world origin with NPC competition for the
        //      capture point. Skipped automatically when the bot already
        //      owns the beacon or is allied to its current owner.
        BeaconRaid.tick(serverLevel, bot);

        // 2e′. If a denounced (HOSTILE-tier) player is inside the village
        //      awareness radius, retarget every unit — workers included — at
        //      them. Cheap when nobody's denounced (early-out on empty set).
        HostileSweep.tick(serverLevel, bot);

        // 2f. Sync faction resource pool ↔ physical chest in storage buildings.
        FactionResourceChest.tick(serverLevel, bot);

        // 2g. Worker safety reflexes. Note that mob engagement is now
        //     handled by MobVigil at the top of military disposition
        //     (step 2e-pre) — it runs first and covers both workers
        //     and military, day and night.
        //     1. WorkerFlight: flee from non-allied threats (other-faction
        //        military, etc.) that MobVigil didn't engage.
        //     2. NightShelter: send villager workers indoors at night so
        //        military can hold the perimeter (via NightGuard).
        WorkerFlight.tick(bot);
        NightShelter.tick(serverLevel, bot);

        // 2h. Tear down non-functional villages (no capitol + no workers,
        //     or zero buildings + zero units) after a 30 s grace period.
        //     This must run AFTER everything else so the destruction call
        //     doesn't yank state from under the other ticks.
        VillageHealth.tick(serverLevel, bot);

        // 3. If there's an active project, check whether it's done OR stalled.
        if (currentProject != null) {
            if (!BuildingServerEvents.getBuildings().contains(currentProject)) {
                // Building was destroyed before completion — abandon and move on.
                LivingWorld.LOGGER.info(
                    "[Brain {}] project {} disappeared, advancing",
                    bot.name(),
                    describe(currentProject)
                );
                clearCurrentProject();
                currentStepIndex++;
                return;
            }
            if (currentProject.isBuilt) {
                LivingWorld.LOGGER.info(
                    "[Brain {}] project {} complete",
                    bot.name(),
                    describe(currentProject)
                );
                clearCurrentProject();
                currentStepIndex++;
                return;
            }

            // Stall detection: if blocksPlaced hasn't increased in
            // PROJECT_ABANDON_TICKS, the project is genuinely stuck —
            // probably unreachable. Destroy it so autocastRepair stops
            // sending workers there.
            int placed = currentProject.getBlocksPlaced();
            if (placed > lastBlocksPlaced) {
                lastBlocksPlaced = placed;
                projectStallTicks = 0;
            } else {
                projectStallTicks++;
                if (projectStallTicks >= PROJECT_ABANDON_TICKS) {
                    LivingWorld.LOGGER.warn(
                        "[Brain {}] abandoning unreachable project {} — no progress in {} brain ticks ({}/{} blocks placed)",
                        bot.name(),
                        describe(currentProject),
                        projectStallTicks,
                        placed,
                        currentProject.getBlocksTotal()
                    );
                    try {
                        currentProject.destroy(serverLevel);
                    } catch (Throwable t) {
                        LivingWorld.LOGGER.error(
                            "[Brain {}] destroy() threw while abandoning project",
                            bot.name(),
                            t
                        );
                    }
                    clearCurrentProject();
                    currentStepIndex++;
                }
            }
            return;
        }

        // 4. Hit the soft cap on total buildings? Stop queuing.
        if (atBuildingCap()) return;

        // 5. Plan and place the next project. BuildOrder cycles through the
        //    growth loop automatically once the main sequence is exhausted,
        //    so we never run out of things to build until the cap.
        BuildOrder.Step step = BuildOrder.stepAt(bot.faction, currentStepIndex);
        startProject(serverLevel, step);
    }

    private void startProject(ServerLevel level, BuildOrder.Step step) {
        // Check affordability up-front. Previously we'd bail and log a
        // "waiting for resources" message here, which made villages feel
        // stagnant whenever the gather → deposit loop couldn't keep up with
        // the cost curve. The brain now *credits* the bot with the missing
        // delta so progression never blocks on economy. We still go through
        // the normal (non-fromCommand) placement path so RoN does its terrain
        // and overlap checks, and the bot's pool ends up at exactly
        // "max(current, cost)" — i.e. surplus from gathering is preserved
        // and the credit only covers what's missing.
        //
        // FREE_BUILDS is still the escape hatch that goes through
        // placeBuilding(…, fromCommand=true) and bypasses terrain too, used
        // for testing only.
        ResourceCost cost = step.building().cost;
        if (!LivingWorldConfig.FREE_BUILDS && !canAfford(cost)) {
            creditMissingResources(step, cost);
        }
        // No longer blocked on cost — either we could already afford it,
        // or creditMissingResources just topped us up.

        BlockPos site = SiteFinder.findNearCapitol(level, bot.centrePos);
        if (site == null) {
            // Couldn't find a clear spot this tick; try again next tick.
            return;
        }

        Rotation rotation = ROTATIONS[RNG.nextInt(ROTATIONS.length)];
        BuildingPlacement placement = BuildingServerEvents.placeBuilding(
            step.building(),
            site,
            rotation,
            bot.name(),
            new int[] {}, // no specific builder units assigned
            false, // queue?
            false, // diagonal bridge?
            LivingWorldConfig
                .FREE_BUILDS // fromCommand: only true in debug mode
        );

        if (placement == null) {
            // RoN refused the placement (overlap or last-second cost issue).
            // Try again next tick.
            return;
        }

        // Workers (autocastRepair=true) will do the actual construction.
        // See class-level Javadoc for why this differs from the capitol.
        placement.selfBuilding = false;
        currentProject = placement;
        // Reset stall tracking for the new project.
        lastBlocksPlaced = placement.getBlocksPlaced();
        projectStallTicks = 0;

        boolean inGrowth = inGrowthPhase();
        LivingWorld.LOGGER.info(
            "[Brain {}] queued step {} ({}: {}) at {} rot={} cost=F{}/W{}/O{} {}",
            bot.name(),
            currentStepIndex + 1,
            step.label(),
            step.building().name,
            site.toShortString(),
            rotation,
            cost.food,
            cost.wood,
            cost.ore,
            inGrowth ? "[growth-loop]" : "[main-sequence]"
        );
    }

    /**
     * Returns true iff the bot has at least the cost's worth of every resource
     * type required. RoN's {@link ResourcesServerEvents#canAfford} checks one
     * resource at a time, so we and-combine the three.
     */
    private boolean canAfford(ResourceCost cost) {
        return (
            ResourcesServerEvents.canAfford(
                bot.name(),
                ResourceName.FOOD,
                cost.food
            ) &&
            ResourcesServerEvents.canAfford(
                bot.name(),
                ResourceName.WOOD,
                cost.wood
            ) &&
            ResourcesServerEvents.canAfford(
                bot.name(),
                ResourceName.ORE,
                cost.ore
            )
        );
    }

    /**
     * Credit the bot just enough of each resource to afford {@code cost}.
     *
     * <p>Computes {@code missing = max(0, cost - current)} per resource and
     * calls {@link ResourcesServerEvents#addSubtractResources} with the
     * positive delta. Surplus from active gathering is untouched, so the
     * "economic loop" still has meaning — workers that gather faster end
     * up with bigger reserves, the credit just guarantees a floor.
     *
     * <p>Logged at INFO so we can see how heavily a particular village is
     * being subsidised; if a village constantly needs full credits it's
     * a hint that the gather loop is broken (water-locked, mis-rolled
     * biome) rather than just slow.
     */
    private void creditMissingResources(
        BuildOrder.Step step,
        ResourceCost cost
    ) {
        int currentFood = currentResource(ResourceName.FOOD);
        int currentWood = currentResource(ResourceName.WOOD);
        int currentOre = currentResource(ResourceName.ORE);
        int dFood = Math.max(0, cost.food - currentFood);
        int dWood = Math.max(0, cost.wood - currentWood);
        int dOre = Math.max(0, cost.ore - currentOre);
        if (dFood == 0 && dWood == 0 && dOre == 0) return;

        ResourcesServerEvents.addSubtractResources(
            new com.solegendary.reignofnether.resources.Resources(
                bot.name(),
                dFood,
                dWood,
                dOre
            )
        );
        LivingWorld.LOGGER.info(
            "[Brain {}] credited F+{}/W+{}/O+{} to build {} ({})",
            bot.name(),
            dFood,
            dWood,
            dOre,
            step.label(),
            step.building().name
        );
    }

    /** Current pool amount for a single resource (0 if no pool yet). */
    private int currentResource(ResourceName name) {
        for (com.solegendary.reignofnether.resources.Resources r : ResourcesServerEvents.resourcesList) {
            if (!r.ownerName.equals(bot.name())) continue;
            return switch (name) {
                case FOOD -> r.food;
                case WOOD -> r.wood;
                case ORE -> r.ore;
                case NONE -> 0;
            };
        }
        return 0;
    }

    /** Reset the per-project tracking fields when we move off a project. */
    private void clearCurrentProject() {
        currentProject = null;
        lastBlocksPlaced = -1;
        projectStallTicks = 0;
    }

    private static String describe(BuildingPlacement placement) {
        Building b = placement.getBuilding();
        return (
            (b != null ? b.name : "?") +
            "@" +
            placement.originPos.toShortString()
        );
    }
}
