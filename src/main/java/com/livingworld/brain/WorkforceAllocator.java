package com.livingworld.brain;

import com.livingworld.bot.FactionBot;
import com.solegendary.reignofnether.building.Building;
import com.solegendary.reignofnether.building.BuildingPlacement;
import com.solegendary.reignofnether.building.BuildingServerEvents;
import com.solegendary.reignofnether.building.Buildings;
import com.solegendary.reignofnether.faction.Faction;
import com.solegendary.reignofnether.resources.ResourceCost;
import com.solegendary.reignofnether.resources.ResourceName;
import com.solegendary.reignofnether.resources.Resources;
import com.solegendary.reignofnether.resources.ResourcesServerEvents;
import com.solegendary.reignofnether.unit.UnitServerEvents;
import com.solegendary.reignofnether.unit.interfaces.Unit;
import com.solegendary.reignofnether.unit.interfaces.WorkerUnit;
import java.util.EnumMap;
import java.util.Random;
import javax.annotation.Nullable;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.LivingEntity;

/**
 * Computes per-resource weights for worker-role assignment, combining
 * <em>biome priors</em> (what's reachable in this terrain) with <em>current
 * demand</em> (what the brain needs for its next build project).
 *
 * <p>The output is a probability distribution over {@code FOOD / WOOD / ORE}
 * \u2014 not a quota. We pick roles by weighted random sample rather than fixed
 * counts because:
 * <ul>
 *   <li>It naturally interpolates as workers die / new ones spawn.</li>
 *   <li>It avoids the oscillation Petra warns about; one worker assignment
 *       at a time, no full re-shuffles.</li>
 *   <li>Workers that ARE gathering aren't disturbed; only NONE workers go
 *       through this. So adjustments happen organically as old workers run
 *       out of nodes and new ones spawn.</li>
 * </ul>
 *
 * <p>Two-factor weighting:
 * <ol>
 *   <li><b>Biome prior</b> from {@link BiomeProfile} \u2014 desert WOOD weight is
 *       near zero, forest WOOD weight is maxed.</li>
 *   <li><b>Demand boost</b> from the next queued build project: if the next
 *       building costs 100 wood and the bot has 0 wood, WOOD gets a strong
 *       boost; if the bot already has 200 wood, no boost.</li>
 * </ol>
 *
 * <p>Both factors are added (not multiplied) so a "biome with no wood" never
 * gets WOOD weight zero \u2014 if demand is high enough, workers will still try
 * (and our role-cycling will keep them moving if they fail).
 */
public final class WorkforceAllocator {

    /** Multiplier for the demand contribution. Higher = more reactive to build needs. */
    public static final double DEMAND_WEIGHT = 1.5;

    /** Wood stockpile below this triggers an urgency WOOD-worker boost. */
    public static final int LOW_WOOD_THRESHOLD = 50;

    /** Max extra WOOD weight added when stockpile is at zero. */
    public static final double WOOD_URGENCY_BOOST = 2.0;

    /** Alias kept for use in computeWeights. */
    public static final double LOW_WOOD_EMERGENCY_BOOST = WOOD_URGENCY_BOOST;

    /** Max food-gathering workers per completed farm building. */
    public static final int FOOD_WORKERS_PER_FARM = 1;

    private static final Random RNG = new Random();

    private WorkforceAllocator() {}

    /**
     * Compute the combined biome + demand weights for {@code bot} at its
     * current location and current build progress.
     *
     * @return an EnumMap with positive weights for FOOD, WOOD, ORE (NONE not
     *         included). Always returns non-empty.
     */
    public static EnumMap<ResourceName, Double> computeWeights(
        FactionBot bot,
        ServerLevel level,
        @Nullable BuildOrder.Step nextStep
    ) {
        BiomeProfile biome = BiomeProfile.at(level, bot.centrePos);
        EnumMap<ResourceName, Double> weights = new EnumMap<>(
            ResourceName.class
        );
        weights.put(ResourceName.FOOD, biome.food);
        weights.put(ResourceName.WOOD, biome.wood);
        weights.put(ResourceName.ORE, biome.ore);

        // Fetch the current resource pool once; used for both demand boost and
        // emergency checks below.
        Resources pool = findResources(bot.name());
        int curFood = pool == null ? 0 : pool.food;
        int curWood = pool == null ? 0 : pool.wood;
        int curOre = pool == null ? 0 : pool.ore;

        // Demand boost from the next build project, if any.
        if (nextStep != null) {
            ResourceCost cost = nextStep.building().cost;
            int needFood = Math.max(0, cost.food - curFood);
            int needWood = Math.max(0, cost.wood - curWood);
            int needOre = Math.max(0, cost.ore - curOre);
            int total = needFood + needWood + needOre;
            if (total > 0) {
                weights.merge(
                    ResourceName.FOOD,
                    (DEMAND_WEIGHT * needFood) / total,
                    Double::sum
                );
                weights.merge(
                    ResourceName.WOOD,
                    (DEMAND_WEIGHT * needWood) / total,
                    Double::sum
                );
                weights.merge(
                    ResourceName.ORE,
                    (DEMAND_WEIGHT * needOre) / total,
                    Double::sum
                );
            }
        }

        // Emergency WOOD boost: when the wood stockpile is critically low,
        // scale extra WOOD weight proportional to how depleted it is. This
        // kicks in even if the biome has low WOOD abundance or the next
        // building is food-heavy — farms shouldn't drain the last logs.
        if (curWood < LOW_WOOD_THRESHOLD) {
            double urgency = 1.0 - (double) curWood / LOW_WOOD_THRESHOLD;
            weights.merge(
                ResourceName.WOOD,
                LOW_WOOD_EMERGENCY_BOOST * urgency,
                Double::sum
            );
        }

        // Farm-based FOOD worker cap: at most FOOD_WORKERS_PER_FARM workers
        // per completed farm building. Once the cap is reached, zero out the
        // FOOD weight so the next worker is sent to WOOD or ORE instead.
        // The cap only applies once at least one farm is built — before that,
        // normal biome + demand weighting governs.
        int farmCount = countBuiltFarmsFor(bot);
        if (farmCount > 0) {
            int foodWorkers = countWorkersWithRole(
                bot.name(),
                ResourceName.FOOD
            );
            if (foodWorkers >= farmCount * FOOD_WORKERS_PER_FARM) {
                weights.put(ResourceName.FOOD, 0.0);
            }
        }

        // Anti-skew: divide each role's weight by (1 + current worker count
        // for that role). Roles already over-staffed get a progressively
        // shrinking probability, while empty roles keep their full biome
        // prior. Over many assignments this drives the worker mix toward the
        // biome-weight ratio (e.g. ~5/3/2 for plains' 1.0 / 0.6 / 0.25)
        // rather than the binomial blow-out we'd see from pure independent
        // sampling (which was producing 8/1/1-style distributions).
        int curFoodWorkers = countWorkersWithRole(
            bot.name(),
            ResourceName.FOOD
        );
        int curWoodWorkers = countWorkersWithRole(
            bot.name(),
            ResourceName.WOOD
        );
        int curOreWorkers = countWorkersWithRole(bot.name(), ResourceName.ORE);
        weights.computeIfPresent(
            ResourceName.FOOD,
            (k, v) -> v / (1.0 + curFoodWorkers)
        );
        weights.computeIfPresent(
            ResourceName.WOOD,
            (k, v) -> v / (1.0 + curWoodWorkers)
        );
        weights.computeIfPresent(
            ResourceName.ORE,
            (k, v) -> v / (1.0 + curOreWorkers)
        );

        return weights;
    }

    /**
     * Sample one role from {@code weights}, proportional to weight. Returns
     * FOOD as a fallback if the distribution is degenerate.
     */
    public static ResourceName pickRole(EnumMap<ResourceName, Double> weights) {
        double total = 0;
        for (double w : weights.values()) total += w;
        if (total <= 0) return ResourceName.FOOD;

        double r = RNG.nextDouble() * total;
        for (var entry : weights.entrySet()) {
            r -= entry.getValue();
            if (r <= 0) return entry.getKey();
        }
        return ResourceName.FOOD;
    }

    /** Convenience: compute weights and immediately sample one role. */
    public static ResourceName pickRoleFor(
        FactionBot bot,
        ServerLevel level,
        @Nullable BuildOrder.Step nextStep
    ) {
        return pickRole(computeWeights(bot, level, nextStep));
    }

    @Nullable
    private static Resources findResources(String ownerName) {
        for (Resources r : ResourcesServerEvents.resourcesList) {
            if (r.ownerName.equals(ownerName)) return r;
        }
        return null;
    }

    /**
     * Count completed farm buildings owned by {@code bot}. The farm type
     * varies by faction (Wheat Farm / Pumpkin Farm / Nether Wart Farm).
     */
    private static int countBuiltFarmsFor(FactionBot bot) {
        Building farmBuilding = farmBuildingFor(bot.faction);
        if (farmBuilding == null) return 0;
        int count = 0;
        for (BuildingPlacement bp : BuildingServerEvents.getBuildings()) {
            if (!bp.ownerName.equals(bot.name())) continue;
            if (!bp.isBuilt) continue;
            Building b = bp.getBuilding();
            if (b != null && b.isTypeOf(farmBuilding)) count++;
        }
        return count;
    }

    /** Map each faction to its farm building type. */
    @Nullable
    private static Building farmBuildingFor(Faction faction) {
        return switch (faction) {
            case VILLAGERS -> Buildings.WHEAT_FARM;
            case MONSTERS -> Buildings.PUMPKIN_FARM;
            case PIGLINS -> Buildings.NETHERWART_FARM;
            default -> null;
        };
    }

    /**
     * Count workers owned by {@code ownerName} currently assigned to
     * {@code role}. Used to enforce the farm-based FOOD worker cap.
     */
    private static int countWorkersWithRole(
        String ownerName,
        ResourceName role
    ) {
        int count = 0;
        for (LivingEntity entity : UnitServerEvents.getAllUnits()) {
            if (!(entity instanceof WorkerUnit worker)) continue;
            if (!(entity instanceof Unit unit)) continue;
            if (!unit.getOwnerName().equals(ownerName)) continue;
            var goal = worker.getGatherResourceGoal();
            if (goal == null) continue;
            if (goal.getTargetResourceName() == role) count++;
        }
        return count;
    }
}
