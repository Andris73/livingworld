package com.livingworld.worldgen;

import com.solegendary.reignofnether.building.Building;
import com.solegendary.reignofnether.building.Buildings;

import javax.annotation.Nullable;
import java.util.Map;

/**
 * Vanilla overworld structure \u2192 RoN neutral building.
 *
 * <p>The replacement happens in two parts:
 * <ol>
 *   <li>A datapack override (NBT-path trick, same as villages) blanks out the
 *       vanilla structure's blocks. The marker NBT is shipped at the same
 *       paths vanilla uses for these structures' templates.</li>
 *   <li>{@link NeutralStructureReplacer} watches {@code chunk.getAllStarts()}
 *       at chunk-load and places the mapped RoN neutral building at the
 *       structure's bounding-box centre.</li>
 * </ol>
 *
 * <p>Rarity comes naturally from vanilla generation frequency:
 * <ul>
 *   <li>Ruined portals: every few chunks \u2192 <b>Neutral Transport Portal</b> (common)</li>
 *   <li>Desert / jungle pyramids: one per biome instance \u2192 <b>Healing Fountain</b> (rare)</li>
 * </ul>
 *
 * <p><b>Excluded</b> on purpose: {@code minecraft:end_portal} \u2014 we don't want to
 * make end access easier, and RoN's End Portal building is explicitly not
 * placed by this system.
 */
public final class NeutralStructureMappings {

    /** Vanilla structure ID prefix \u2192 RoN building to spawn at its centre. */
    public static final Map<String, Building> MAPPINGS = Map.ofEntries(
            // Ruined portals (every biome variant)
            Map.entry("minecraft:ruined_portal",          Buildings.NEUTRAL_TRANSPORT_PORTAL),
            Map.entry("minecraft:ruined_portal_desert",   Buildings.NEUTRAL_TRANSPORT_PORTAL),
            Map.entry("minecraft:ruined_portal_jungle",   Buildings.NEUTRAL_TRANSPORT_PORTAL),
            Map.entry("minecraft:ruined_portal_mountain", Buildings.NEUTRAL_TRANSPORT_PORTAL),
            Map.entry("minecraft:ruined_portal_ocean",    Buildings.NEUTRAL_TRANSPORT_PORTAL),
            Map.entry("minecraft:ruined_portal_swamp",    Buildings.NEUTRAL_TRANSPORT_PORTAL),
            // Pyramids (one per biome instance \u2014 rare)
            Map.entry("minecraft:desert_pyramid", Buildings.HEALING_FOUNTAIN),
            Map.entry("minecraft:jungle_pyramid", Buildings.HEALING_FOUNTAIN)
            // Deliberately NOT included: minecraft:end_portal, minecraft:stronghold,
            // minecraft:woodland_mansion (player loot landmarks we shouldn't gut).
    );

    private NeutralStructureMappings() {}

    /** Returns the RoN building for the given vanilla structure key, or null if not mapped. */
    @Nullable
    public static Building buildingFor(String structureKey) {
        return MAPPINGS.get(structureKey);
    }
}
