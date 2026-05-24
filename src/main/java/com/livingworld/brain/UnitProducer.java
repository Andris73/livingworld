package com.livingworld.brain;

import com.livingworld.LivingWorld;
import com.livingworld.bot.FactionBot;
import com.solegendary.reignofnether.building.Building;
import com.solegendary.reignofnether.building.BuildingPlacement;
import com.solegendary.reignofnether.building.BuildingServerEvents;
import com.solegendary.reignofnether.building.buildings.monsters.Graveyard;
import com.solegendary.reignofnether.building.buildings.piglins.PortalMilitary;
import com.solegendary.reignofnether.building.buildings.placements.ProductionPlacement;
import com.solegendary.reignofnether.building.buildings.villagers.Barracks;
import com.solegendary.reignofnether.building.production.ProductionItem;
import com.solegendary.reignofnether.building.production.ProductionItems;
import com.solegendary.reignofnether.faction.Faction;
import com.solegendary.reignofnether.registrars.EntityRegistrar;
import com.solegendary.reignofnether.unit.UnitServerEvents;
import com.solegendary.reignofnether.unit.interfaces.Unit;
import javax.annotation.Nullable;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.LivingEntity;

/**
 * Keeps the village's population growing over time by queueing units at the
 * appropriate production buildings.
 *
 * <p><b>Worker production</b> happens at the capitol (which is a
 * {@link ProductionPlacement} for all three factions \u2014 Town Centre, Mausoleum,
 * Central Portal). We aim for {@link #WORKER_TARGET} owned workers per village.
 *
 * <p><b>Military production</b> happens at the faction's "barracks" building
 * (Barracks for villagers, Dungeon for monsters, Military Portal for piglins)
 * once the brain has built one. We aim for {@link #MILITARY_TARGET} owned
 * combat units, queueing the basic infantry unit (Vindicator / Zombie / Brute).
 *
 * <p>Queueing goes through {@link ProductionPlacement#startProductionItem}
 * which does the affordability check and deducts resources for us. If the bot
 * can't afford the next unit, the call returns {@code false} and we silently
 * skip \u2014 next tick we'll try again, and meanwhile workers keep gathering.
 *
 * <p>We never queue more than one unit at a time: the queue is full when there
 * are pending items. This keeps the village's resource spend predictable and
 * gives the brain a chance to also pay for new buildings.
 */
public final class UnitProducer {

    /**
     * Worker target scaling: {@code base + housing_count * per_house}, capped
     * at {@link #WORKER_TARGET_MAX}. So a village with no houses yet has 4
     * workers; one with 6 villager houses targets {@code 4 + 12 = 16}.
     */
    public static final int WORKER_TARGET_BASE = 4;
    public static final int WORKER_TARGET_PER_HOUSE = 2;
    public static final int WORKER_TARGET_MAX = 20;

    /**
     * Military target scaling: {@code base + military_buildings * per_building},
     * capped at {@link #MILITARY_TARGET_MAX}. So 1 barracks = 4 soldiers,
     * 2 barracks = 6, 3 barracks = 8, etc.
     */
    public static final int MILITARY_TARGET_BASE = 2;
    public static final int MILITARY_TARGET_PER_BUILDING = 2;
    public static final int MILITARY_TARGET_MAX = 12;

    private UnitProducer() {}

    /**
     * Try to queue both a worker and (if eligible) a combat unit for this bot
     * this tick. Safe to call every brain tick \u2014 it's a no-op when targets
     * are met, queues are full, or the bot can't afford anything.
     */
    public static void tick(FactionBot bot) {
        produceWorkerIfNeeded(bot);
        produceMilitaryIfNeeded(bot);
    }

    // -------------------------------------------------------------- workers

    private static void produceWorkerIfNeeded(FactionBot bot) {
        if (!(bot.capitol instanceof ProductionPlacement capitol)) return;
        if (queueHasPending(capitol)) return;

        EntityType<?> workerType = workerEntityType(bot.faction);
        if (workerType == null) return;

        int current = countOwnedUnitsOfType(bot.name(), workerType);
        int target = workerTarget(bot);
        if (current >= target) return;

        ProductionItem prod = workerProductionItem(bot.faction);
        if (prod == null) return;

        boolean queued = capitol.startProductionItem(prod);
        if (queued) {
            LivingWorld.LOGGER.info(
                "[Brain {}] queued worker ({}/{}) at capitol",
                bot.name(),
                current + 1,
                target
            );
        }
    }

    /** Worker target scales with completed housing buildings. */
    private static int workerTarget(FactionBot bot) {
        Building housing = housingBuildingFor(bot.faction);
        if (housing == null) return WORKER_TARGET_BASE;
        int houses = countOwnedBuiltBuildings(bot.name(), housing);
        int target = WORKER_TARGET_BASE + WORKER_TARGET_PER_HOUSE * houses;
        return Math.min(WORKER_TARGET_MAX, target);
    }

    /** Faction → housing building. */
    @Nullable
    private static Building housingBuildingFor(
        com.solegendary.reignofnether.faction.Faction f
    ) {
        return switch (f) {
            case VILLAGERS -> com.solegendary.reignofnether.building.Buildings.VILLAGER_HOUSE;
            case MONSTERS -> com.solegendary.reignofnether.building.Buildings.HAUNTED_HOUSE;
            case PIGLINS -> com.solegendary.reignofnether.building.Buildings.PORTAL_BASIC;
            default -> null;
        };
    }

    // ------------------------------------------------------------ military

    /**
     * Produce a mixed melee/ranged army by queueing whichever class is more
     * under-staffed relative to its half-of-target quota. Net effect over
     * time: ~50% melee, ~50% ranged among the bot's military units.
     *
     * <p>Picking the more-under-staffed class each tick (rather than naive
     * alternation) keeps the army balanced even when one class takes heavier
     * losses — e.g. 4 melee + 0 ranged after a fight will queue ranged for
     * the next four production cycles before reverting to alternating.
     */
    private static void produceMilitaryIfNeeded(FactionBot bot) {
        ProductionPlacement barracks = findBarracks(bot);
        if (barracks == null) return;
        if (!barracks.isBuilt) return;
        if (queueHasPending(barracks)) return;

        EntityType<?> meleeType = meleeEntityType(bot.faction);
        EntityType<?> rangedType = rangedEntityType(bot.faction);
        if (meleeType == null || rangedType == null) return;

        int meleeCurrent = countOwnedUnitsOfType(bot.name(), meleeType);
        int rangedCurrent = countOwnedUnitsOfType(bot.name(), rangedType);
        int target = militaryTarget(bot);
        if (meleeCurrent + rangedCurrent >= target) return;

        // Each class targets half of the total; the more under-staffed class
        // wins. Tie-break favours melee since it's the front-line role.
        double halfTarget = target / 2.0;
        double meleeFill = meleeCurrent / halfTarget;
        double rangedFill = rangedCurrent / halfTarget;
        boolean queueRanged = rangedFill < meleeFill;

        ProductionItem prod = queueRanged
            ? rangedProductionItem(bot.faction)
            : meleeProductionItem(bot.faction);
        if (prod == null) {
            // Shouldn't normally happen — every faction has both classes
            // defined. Fall through to the other class as a safety net.
            prod = queueRanged
                ? meleeProductionItem(bot.faction)
                : rangedProductionItem(bot.faction);
            queueRanged = !queueRanged;
            if (prod == null) return;
        }

        boolean queued = barracks.startProductionItem(prod);
        if (queued) {
            LivingWorld.LOGGER.info(
                "[Brain {}] queued {} military unit (M:{}/R:{} of target {}) at barracks",
                bot.name(),
                queueRanged ? "ranged" : "melee",
                meleeCurrent + (queueRanged ? 0 : 1),
                rangedCurrent + (queueRanged ? 1 : 0),
                target
            );
        }
    }

    /** Military target scales with completed military buildings (barracks etc.). */
    private static int militaryTarget(FactionBot bot) {
        Building militaryBuilding = militaryBuildingFor(bot.faction);
        if (militaryBuilding == null) return MILITARY_TARGET_BASE;
        int n = countOwnedBuiltBuildings(bot.name(), militaryBuilding);
        int target = MILITARY_TARGET_BASE + MILITARY_TARGET_PER_BUILDING * n;
        return Math.min(MILITARY_TARGET_MAX, target);
    }

    @Nullable
    private static Building militaryBuildingFor(
        com.solegendary.reignofnether.faction.Faction f
    ) {
        return switch (f) {
            case VILLAGERS -> com.solegendary.reignofnether.building.Buildings.BARRACKS;
            // Monsters: Graveyard is the baseline-military-equivalent
            // (produces zombie / husk / drowned / skeleton / stray / bogged —
            // proper melee + ranged). The Dungeon, which we used previously,
            // only produces creeper / wraith — specialty units, not a
            // baseline army. With the Dungeon, startProductionItem silently
            // rejected our ZOMBIE / SKELETON queues and no follow-up military
            // ever spawned post-village-creation.
            case MONSTERS -> com.solegendary.reignofnether.building.Buildings.GRAVEYARD;
            case PIGLINS -> com.solegendary.reignofnether.building.Buildings.PORTAL_MILITARY;
            default -> null;
        };
    }

    /** Count built buildings owned by {@code ownerName} matching {@code building}. */
    private static int countOwnedBuiltBuildings(
        String ownerName,
        Building building
    ) {
        int count = 0;
        for (BuildingPlacement bp : BuildingServerEvents.getBuildings()) {
            if (!bp.ownerName.equals(ownerName)) continue;
            if (!bp.isBuilt) continue;
            if (
                bp.getBuilding() != null && bp.getBuilding().isTypeOf(building)
            ) count++;
        }
        return count;
    }

    // ----------------------------------------------------------- lookups

    /**
     * Find a built faction-appropriate "barracks" placement owned by the bot.
     * Returns {@code null} if the brain hasn't built one yet.
     */
    @Nullable
    private static ProductionPlacement findBarracks(FactionBot bot) {
        for (BuildingPlacement bp : BuildingServerEvents.getBuildings()) {
            if (!bp.ownerName.equals(bot.name())) continue;
            if (!(bp instanceof ProductionPlacement pp)) continue;
            Building b = bp.getBuilding();
            if (b == null) continue;
            switch (bot.faction) {
                case VILLAGERS -> {
                    if (b instanceof Barracks) return pp;
                }
                case MONSTERS -> {
                    // See militaryBuildingFor for why this is Graveyard
                    // rather than Dungeon.
                    if (b instanceof Graveyard) return pp;
                }
                case PIGLINS -> {
                    if (b instanceof PortalMilitary) return pp;
                }
                default -> {
                }
            }
        }
        return null;
    }

    @Nullable
    private static EntityType<?> workerEntityType(Faction faction) {
        return switch (faction) {
            case VILLAGERS -> EntityRegistrar.VILLAGER_UNIT.get();
            case MONSTERS -> EntityRegistrar.ZOMBIE_VILLAGER_UNIT.get();
            case PIGLINS -> EntityRegistrar.GRUNT_UNIT.get();
            default -> null;
        };
    }

    /** Faction → basic melee combat unit produced at the barracks-equivalent. */
    @Nullable
    private static EntityType<?> meleeEntityType(Faction faction) {
        return switch (faction) {
            case VILLAGERS -> EntityRegistrar.VINDICATOR_UNIT.get();
            case MONSTERS -> EntityRegistrar.ZOMBIE_UNIT.get();
            case PIGLINS -> EntityRegistrar.BRUTE_UNIT.get();
            default -> null;
        };
    }

    /**
     * Faction → basic <em>ranged</em> combat unit. Each faction's ranged
     * baseline unit is produced at the same building as the melee one — no
     * extra tech tree requirement.
     */
    @Nullable
    private static EntityType<?> rangedEntityType(Faction faction) {
        return switch (faction) {
            case VILLAGERS -> EntityRegistrar.PILLAGER_UNIT.get();
            case MONSTERS -> EntityRegistrar.SKELETON_UNIT.get();
            case PIGLINS -> EntityRegistrar.HEADHUNTER_UNIT.get();
            default -> null;
        };
    }

    @Nullable
    private static ProductionItem workerProductionItem(Faction faction) {
        return switch (faction) {
            case VILLAGERS -> ProductionItems.VILLAGER;
            case MONSTERS -> ProductionItems.ZOMBIE_VILLAGER;
            case PIGLINS -> ProductionItems.GRUNT;
            default -> null;
        };
    }

    @Nullable
    private static ProductionItem meleeProductionItem(Faction faction) {
        return switch (faction) {
            case VILLAGERS -> ProductionItems.VINDICATOR;
            case MONSTERS -> ProductionItems.ZOMBIE;
            case PIGLINS -> ProductionItems.BRUTE;
            default -> null;
        };
    }

    @Nullable
    private static ProductionItem rangedProductionItem(Faction faction) {
        return switch (faction) {
            case VILLAGERS -> ProductionItems.PILLAGER;
            case MONSTERS -> ProductionItems.SKELETON;
            case PIGLINS -> ProductionItems.HEADHUNTER;
            default -> null;
        };
    }

    // ------------------------------------------------------------- helpers

    private static boolean queueHasPending(ProductionPlacement placement) {
        return !placement.productionQueue.isEmpty();
    }

    private static int countOwnedUnitsOfType(
        String ownerName,
        EntityType<?> type
    ) {
        int count = 0;
        for (LivingEntity entity : UnitServerEvents.getAllUnits()) {
            if (entity.getType() != type) continue;
            if (!(entity instanceof Unit unit)) continue;
            if (!unit.getOwnerName().equals(ownerName)) continue;
            count++;
        }
        return count;
    }
}
