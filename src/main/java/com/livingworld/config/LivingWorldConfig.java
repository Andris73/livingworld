package com.livingworld.config;

/**
 * Configuration options for the Living World mod.
 *
 * For now these are simple static fields. In future versions we might
 * use Forge's config system, but this keeps slice 2 simple.
 */
public final class LivingWorldConfig {

    private LivingWorldConfig() {}

    /** Whether to replace vanilla villages with NPC faction villages when chunks load. */
    public static boolean REPLACE_VANILLA_VILLAGES = true;

    /** Log level for village replacement operations (for debugging). */
    public static boolean VERBOSE_VILLAGE_REPLACEMENT = true;

    /**
     * Radius around the village centre to clear vanilla buildings.
     *
     * <p><b>With our datapack active, this is now mostly a safety net.</b>
     * The datapack in {@code data/minecraft/worldgen/structure/village_*.json}
     * overrides vanilla villages to use an empty start pool, so new chunks
     * generate no village blocks at all. This radius only matters for
     * <em>pre-existing</em> villages in chunks that were generated before the
     * mod was installed.
     *
     * <p>Cleared incrementally over many ticks by {@code VillageReplacementQueue}
     * so even large values don't lag the server, but larger radii take longer
     * to finish. Set to {@code 0} to disable block clearing entirely.
     */
    public static int VILLAGE_CLEAR_RADIUS = 24;

    /**
     * Whether to replace vanilla neutral structures (ruined portals, pyramids)
     * with RoN neutral buildings (transport portals, healing fountains).
     *
     * <p>If you ever see worldgen hang at 100% loading, set this to {@code false}
     * via {@code /livingworld config replace-neutrals false} (or just disable
     * the datapack overrides) to isolate the cause.
     */
    public static boolean REPLACE_NEUTRAL_STRUCTURES = true;

    /**
     * Debug toggle: when {@code true}, the brain places buildings with
     * {@code fromCommand = true} so RoN bypasses the cost / terrain checks.
     * Lets you stress-test the build order and brain logic without waiting on
     * the gather → deposit loop. Should not normally be on — it bypasses the
     * economic feedback that's the whole point of the RTS sim.
     *
     * <p>Toggle live via {@code /livingworld config free-builds <true|false>}.
     */
    public static boolean FREE_BUILDS = false;

    /**
     * When {@code true} (default), every newly-spawned village gets a
     * starter pack of supporting structures (stockpile, 2 houses, farm) and
     * a small military garrison pre-spawned around the capitol. This makes
     * villages feel inhabited the moment a player discovers them rather than
     * looking like a freshly-placed capitol with workers loitering on a ring.
     *
     * <p>The buildings are exactly the first four steps of the faction's
     * normal build order, so the brain just picks up at step five.
     *
     * <p>Toggle live via {@code /livingworld config prespawn-satellites <true|false>}.
     */
    public static boolean PRESPAWN_SATELLITES = true;
}
