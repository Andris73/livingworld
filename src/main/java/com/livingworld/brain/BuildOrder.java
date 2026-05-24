package com.livingworld.brain;

import com.solegendary.reignofnether.building.Building;
import com.solegendary.reignofnether.building.Buildings;
import com.solegendary.reignofnether.faction.Faction;
import java.util.List;
import javax.annotation.Nullable;

/**
 * Per-faction build orders.
 *
 * <p>Each faction has two lists:
 * <ul>
 *   <li>{@link #mainSequence(Faction)} \u2014 the main tech-tree progression
 *       walked in order. Economy first (stockpile + house + farm), then
 *       military (barracks/dungeon/military-portal), then tech (blacksmith,
 *       library, witch hut, etc.), then late-game super-buildings
 *       (castle, stronghold, fortress).</li>
 *   <li>{@link #growthLoop(Faction)} \u2014 a small cycle of "more housing /
 *       farms / military" that the brain cycles through forever once the
 *       main sequence is complete. This is what keeps villages growing
 *       indefinitely instead of going dormant.</li>
 * </ul>
 *
 * <p>The unified accessor {@link #stepAt(Faction, int)} hides the boundary:
 * call it with any non-negative index and you get back a {@code Step}, either
 * from the main sequence or from the growth loop with appropriate wrap-around.
 *
 * <p>Hard cap on total bot-owned buildings comes from {@link FactionBrain},
 * not here \u2014 BuildOrder just describes "what next?".
 */
public final class BuildOrder {

    private BuildOrder() {}

    public record Step(String label, Building building) {}

    /** Returns the step at logical index {@code idx}, with growth-loop wrap-around. */
    public static Step stepAt(Faction faction, int idx) {
        List<Step> main = mainSequence(faction);
        if (idx < main.size()) return main.get(idx);
        List<Step> growth = growthLoop(faction);
        if (growth.isEmpty()) return main.get(main.size() - 1);
        return growth.get((idx - main.size()) % growth.size());
    }

    /** Size of the main sequence (before growth-loop). For diagnostics. */
    public static int mainSequenceSize(Faction faction) {
        return mainSequence(faction).size();
    }

    /**
     * Returns the {@link Step} at the given idx if it's still in the main
     * sequence, otherwise null. Useful for "are we still teching, or growing?"
     * checks.
     */
    @Nullable
    public static Step mainStepAt(Faction faction, int idx) {
        List<Step> main = mainSequence(faction);
        return idx < main.size() ? main.get(idx) : null;
    }

    // ---------------------------------------------------------- main sequence

    public static List<Step> mainSequence(Faction faction) {
        return switch (faction) {
            case VILLAGERS -> List.of(
                // Economy
                new Step("stockpile", Buildings.OAK_STOCKPILE),
                new Step("house", Buildings.VILLAGER_HOUSE),
                new Step("house", Buildings.VILLAGER_HOUSE),
                new Step("farm", Buildings.WHEAT_FARM),
                // Military access
                new Step("barracks", Buildings.BARRACKS),
                new Step("watchtower", Buildings.WATCHTOWER),
                // More economy to support growth
                new Step("house", Buildings.VILLAGER_HOUSE),
                new Step("farm", Buildings.WHEAT_FARM),
                // Tech tier 1
                new Step("blacksmith", Buildings.BLACKSMITH),
                new Step("witch_hut", Buildings.WITCH_HUT),
                new Step("house", Buildings.VILLAGER_HOUSE),
                // Tech tier 2
                new Step("arcane_tower", Buildings.ARCANE_TOWER),
                new Step("library", Buildings.LIBRARY),
                new Step("house", Buildings.VILLAGER_HOUSE),
                new Step("farm", Buildings.WHEAT_FARM),
                // Late game super-buildings
                new Step("castle", Buildings.CASTLE),
                new Step("iron_golem", Buildings.IRON_GOLEM_BUILDING)
            );
            case MONSTERS -> List.of(
                // Economy
                new Step("stockpile", Buildings.SPRUCE_STOCKPILE),
                new Step("house", Buildings.HAUNTED_HOUSE),
                new Step("house", Buildings.HAUNTED_HOUSE),
                new Step("farm", Buildings.PUMPKIN_FARM),
                // Military access — Graveyard is the baseline-military
                // building (produces zombies + skeletons, mirroring Villagers'
                // Barracks and Piglins' Military Portal). Dungeon is moved
                // to tech tier 1 below as a specialty-unit building.
                new Step("graveyard", Buildings.GRAVEYARD),
                new Step("watchtower", Buildings.DARK_WATCHTOWER),
                // More economy
                new Step("house", Buildings.HAUNTED_HOUSE),
                new Step("farm", Buildings.PUMPKIN_FARM),
                // Tech tier 1 — specialty unit buildings
                new Step("dungeon", Buildings.DUNGEON),
                new Step("spider_lair", Buildings.SPIDER_LAIR),
                new Step("house", Buildings.HAUNTED_HOUSE),
                // Tech tier 2
                new Step("slime_pit", Buildings.SLIME_PIT),
                new Step("laboratory", Buildings.LABORATORY),
                new Step("house", Buildings.HAUNTED_HOUSE),
                new Step("farm", Buildings.PUMPKIN_FARM),
                // Late game
                new Step("stronghold", Buildings.STRONGHOLD),
                new Step("sculk", Buildings.SCULK_CATALYST)
            );
            case PIGLINS -> List.of(
                // Economy
                new Step("stockpile", Buildings.PORTAL_CIVILIAN),
                new Step("house", Buildings.PORTAL_BASIC),
                new Step("house", Buildings.PORTAL_BASIC),
                new Step("farm", Buildings.NETHERWART_FARM),
                // Military access
                new Step("military", Buildings.PORTAL_MILITARY),
                // More economy
                new Step("house", Buildings.PORTAL_BASIC),
                new Step("farm", Buildings.NETHERWART_FARM),
                // Tech tier 1
                new Step("hoglin_stables", Buildings.HOGLIN_STABLES),
                new Step("flame_sanctuary", Buildings.FLAME_SANCTUARY),
                new Step("house", Buildings.PORTAL_BASIC),
                // Tech tier 2
                new Step("bastion", Buildings.BASTION),
                new Step("wither_shrine", Buildings.WITHER_SHRINE),
                new Step("house", Buildings.PORTAL_BASIC),
                new Step("farm", Buildings.NETHERWART_FARM),
                new Step("basalt_springs", Buildings.BASALT_SPRINGS),
                // Late game
                new Step("fortress", Buildings.FORTRESS)
            );
            default -> List.of();
        };
    }

    // ----------------------------------------------------------- growth loop

    /**
     * Cycled forever after the main sequence finishes. Heavy on housing and
     * farms (to support more workers) plus periodic extra military to keep
     * defensive strength scaling with the village size.
     */
    public static List<Step> growthLoop(Faction faction) {
        return switch (faction) {
            case VILLAGERS -> List.of(
                new Step("house", Buildings.VILLAGER_HOUSE),
                new Step("farm", Buildings.WHEAT_FARM),
                new Step("house", Buildings.VILLAGER_HOUSE),
                new Step("watchtower", Buildings.WATCHTOWER),
                new Step("farm", Buildings.WHEAT_FARM),
                new Step("barracks", Buildings.BARRACKS)
            );
            case MONSTERS -> List.of(
                new Step("house", Buildings.HAUNTED_HOUSE),
                new Step("farm", Buildings.PUMPKIN_FARM),
                new Step("house", Buildings.HAUNTED_HOUSE),
                new Step("watchtower", Buildings.DARK_WATCHTOWER),
                new Step("farm", Buildings.PUMPKIN_FARM),
                // Add another Graveyard each loop so military-target scaling
                // keeps up; specialty buildings (Dungeon) stay in the main
                // sequence rather than being part of the perpetual growth.
                new Step("graveyard", Buildings.GRAVEYARD)
            );
            case PIGLINS -> List.of(
                new Step("house", Buildings.PORTAL_BASIC),
                new Step("farm", Buildings.NETHERWART_FARM),
                new Step("house", Buildings.PORTAL_BASIC),
                new Step("farm", Buildings.NETHERWART_FARM),
                new Step("military", Buildings.PORTAL_MILITARY)
            );
            default -> List.of();
        };
    }
}
