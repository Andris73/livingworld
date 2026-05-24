package com.livingworld.worldgen;

import com.solegendary.reignofnether.faction.Faction;
import net.minecraft.core.Holder;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.biome.Biome;

import java.util.Random;
import java.util.Set;

/**
 * Decides which {@link Faction} should own a village at a given position,
 * based on the biome at that position with a configurable random override.
 *
 * The defaults lean towards <em>biome-appropriate</em> factions but every roll
 * has a small chance to be wildcard-replaced with a random faction, so that
 * even a plains-heavy world will eventually surface piglin or monster
 * settlements.
 */
public final class FactionPicker {

    /** Chance per village that the biome mapping is overridden by a random pick. */
    public static final double WILDCARD_CHANCE = 0.25;

    /** Biomes that lean towards monster factions. */
    private static final Set<ResourceLocation> MONSTER_BIOMES = Set.of(
            new ResourceLocation("minecraft", "dark_forest"),
            new ResourceLocation("minecraft", "swamp"),
            new ResourceLocation("minecraft", "mangrove_swamp"),
            new ResourceLocation("minecraft", "deep_dark"),
            new ResourceLocation("minecraft", "dripstone_caves"),
            new ResourceLocation("minecraft", "old_growth_spruce_taiga"),
            new ResourceLocation("minecraft", "old_growth_pine_taiga")
    );

    /** Biomes that lean towards piglin factions (rare in overworld). */
    private static final Set<ResourceLocation> PIGLIN_BIOMES = Set.of(
            new ResourceLocation("minecraft", "badlands"),
            new ResourceLocation("minecraft", "eroded_badlands"),
            new ResourceLocation("minecraft", "wooded_badlands")
    );

    private FactionPicker() {}

    public static Faction pickFor(ServerLevel level, BlockPos pos, Random rng) {
        if (rng.nextDouble() < WILDCARD_CHANCE) {
            return wildcard(rng);
        }
        Holder<Biome> biomeHolder = level.getBiome(pos);
        ResourceLocation biomeId = biomeHolder.unwrapKey()
                .map(k -> k.location())
                .orElse(null);
        if (biomeId == null) return Faction.VILLAGERS;

        if (MONSTER_BIOMES.contains(biomeId)) return Faction.MONSTERS;
        if (PIGLIN_BIOMES.contains(biomeId))  return Faction.PIGLINS;
        return Faction.VILLAGERS;
    }

    private static Faction wildcard(Random rng) {
        return switch (rng.nextInt(3)) {
            case 0  -> Faction.VILLAGERS;
            case 1  -> Faction.MONSTERS;
            default -> Faction.PIGLINS;
        };
    }
}
