package com.livingworld.brain;

import com.solegendary.reignofnether.resources.ResourceName;
import java.util.Map;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Holder;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.tags.BiomeTags;
import net.minecraft.world.level.biome.Biome;

/**
 * Per-biome resource priority priors.
 *
 * <p>Different biomes have wildly different resource profiles \u2014 a plains
 * village has plenty of food and wood, a desert village has neither, a
 * mountain village is ore-rich but wood-poor. Hand-coded percentages would be
 * a poor fit; instead we look up the biome's resource priors and use them as
 * weights when deciding how many workers should be on each role.
 *
 * <p>This is the "runtime classification" pattern from Petra
 * ({@code startingStrategy.js}) adapted to Minecraft's labelled biomes. We
 * benefit from Mojang's classification work: the biome registry already tells
 * us "this is a forest" or "this is a desert" without any analysis.
 *
 * <p>Weights are unitless priors in [0, 1] indicating local abundance. They
 * get combined with current resource <em>demand</em> (from the build queue) to
 * decide each worker's role.
 */
public final class BiomeProfile {

    /** Weights for "plenty available", "scarce", and "absent". */
    public static final double PLENTIFUL = 1.00;
    public static final double NORMAL = 0.60;
    public static final double SCARCE = 0.25;
    public static final double ABSENT = 0.05;

    public final double food;
    public final double wood;
    public final double ore;

    public BiomeProfile(double food, double wood, double ore) {
        this.food = food;
        this.wood = wood;
        this.ore = ore;
    }

    /** Weight for the given resource. */
    public double weightFor(ResourceName resource) {
        return switch (resource) {
            case FOOD -> food;
            case WOOD -> wood;
            case ORE -> ore;
            default -> 0.0;
        };
    }

    /** Lookup the resource profile of the biome at {@code pos}. */
    public static BiomeProfile at(ServerLevel level, BlockPos pos) {
        Holder<Biome> biomeHolder = level.getBiome(pos);
        return classify(biomeHolder);
    }

    /**
     * Classify by biome tags first (handles modded biomes); falls back to
     * specific vanilla biome IDs; finally returns a balanced default.
     */
    public static BiomeProfile classify(Holder<Biome> biome) {
        // Tag-based classification first \u2014 catches modded biomes that tag
        // themselves correctly.
        if (biome.is(BiomeTags.IS_FOREST)) return FOREST;
        if (biome.is(BiomeTags.IS_JUNGLE)) return JUNGLE;
        if (biome.is(BiomeTags.IS_TAIGA)) return TAIGA;
        if (biome.is(BiomeTags.IS_SAVANNA)) return SAVANNA;
        if (biome.is(BiomeTags.IS_BADLANDS)) return BADLANDS;
        if (biome.is(BiomeTags.IS_OCEAN)) return OCEAN;
        if (biome.is(BiomeTags.IS_BEACH)) return BEACH;
        if (biome.is(BiomeTags.IS_RIVER)) return RIVER;
        if (biome.is(BiomeTags.IS_MOUNTAIN)) return MOUNTAIN;

        // Specific-biome fallbacks.
        ResourceLocation key = biome
            .unwrapKey()
            .map(k -> k.location())
            .orElse(null);
        if (key != null) {
            BiomeProfile specific = SPECIFIC.get(key.toString());
            if (specific != null) return specific;
        }

        return DEFAULT;
    }

    // -------------------------------------------------------- well-known profiles

    /** Open grassy fields: animals + grass + small trees. */
    public static final BiomeProfile PLAINS = new BiomeProfile(
        /*food*/ PLENTIFUL,
        /*wood*/ NORMAL,
        /*ore*/ SCARCE
    );

    /** Dense forests: tons of wood, decent food, hardly any exposed stone. */
    public static final BiomeProfile FOREST = new BiomeProfile(
        /*food*/ NORMAL,
        /*wood*/ PLENTIFUL,
        /*ore*/ SCARCE
    );

    public static final BiomeProfile JUNGLE = FOREST;

    /** Cold taigas: lots of spruce, animals, less exposed stone. */
    public static final BiomeProfile TAIGA = new BiomeProfile(
        /*food*/ NORMAL,
        /*wood*/ PLENTIFUL,
        /*ore*/ SCARCE
    );

    /** Savanna: acacia trees, some animals, little exposed stone. */
    public static final BiomeProfile SAVANNA = new BiomeProfile(
        /*food*/ NORMAL,
        /*wood*/ NORMAL,
        /*ore*/ SCARCE
    );

    /** Deserts: no trees, hardly any animals, some sand/terracotta. */
    public static final BiomeProfile DESERT = new BiomeProfile(
        /*food*/ SCARCE,
        /*wood*/ ABSENT,
        /*ore*/ NORMAL
    );

    /** Badlands: ore-rich, almost no wood, very little food. */
    public static final BiomeProfile BADLANDS = new BiomeProfile(
        /*food*/ SCARCE,
        /*wood*/ ABSENT,
        /*ore*/ PLENTIFUL
    );

    /** Snowy: some wood, scarce food, modest ore. */
    public static final BiomeProfile SNOWY = new BiomeProfile(
        /*food*/ SCARCE,
        /*wood*/ NORMAL,
        /*ore*/ NORMAL
    );

    /** Mountains: exposed stone/ore everywhere, little else. */
    public static final BiomeProfile MOUNTAIN = new BiomeProfile(
        /*food*/ SCARCE,
        /*wood*/ SCARCE,
        /*ore*/ PLENTIFUL
    );

    /** Ocean / beach / river: lots of water, treat as scarce for everything. */
    public static final BiomeProfile OCEAN = new BiomeProfile(
        /*food*/ SCARCE,
        /*wood*/ ABSENT,
        /*ore*/ ABSENT
    );
    public static final BiomeProfile BEACH = new BiomeProfile(
        /*food*/ SCARCE,
        /*wood*/ SCARCE,
        /*ore*/ SCARCE
    );
    public static final BiomeProfile RIVER = new BiomeProfile(
        /*food*/ NORMAL,
        /*wood*/ SCARCE,
        /*ore*/ SCARCE
    );

    /** Fallback when we can't classify the biome. */
    public static final BiomeProfile DEFAULT = new BiomeProfile(
        /*food*/ NORMAL,
        /*wood*/ NORMAL,
        /*ore*/ NORMAL
    );

    private static final Map<String, BiomeProfile> SPECIFIC =
        new java.util.HashMap<>();

    static {
        SPECIFIC.put("minecraft:plains", PLAINS);
        SPECIFIC.put("minecraft:sunflower_plains", PLAINS);
        SPECIFIC.put("minecraft:meadow", PLAINS);
        SPECIFIC.put("minecraft:desert", DESERT);
        SPECIFIC.put("minecraft:snowy_plains", SNOWY);
        SPECIFIC.put("minecraft:snowy_taiga", SNOWY);
        SPECIFIC.put("minecraft:ice_spikes", SNOWY);
    }
}
