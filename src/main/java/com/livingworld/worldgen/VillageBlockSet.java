package com.livingworld.worldgen;

import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;

import java.util.Set;

/**
 * The set of block types that we consider "vanilla village" — i.e. blocks
 * that should be cleared when replacing a vanilla village with an NPC capitol.
 *
 * <p>Includes both the structural blocks used by all village types (planks,
 * cobblestone, logs, stairs, doors, etc.) and biome-specific decoration
 * (sandstone for desert, acacia for savanna, packed ice for snowy, etc.).
 *
 * <p>Natural terrain blocks (dirt, grass, stone, water, leaves, etc.) are
 * deliberately <em>not</em> included so we don't accidentally bulldoze the
 * surrounding biome.
 */
public final class VillageBlockSet {

    private static final Set<Block> BLOCKS = Set.of(
            // Common village structural blocks
            Blocks.COBBLESTONE, Blocks.MOSSY_COBBLESTONE,
            Blocks.OAK_PLANKS, Blocks.SPRUCE_PLANKS, Blocks.BIRCH_PLANKS, Blocks.ACACIA_PLANKS,
            Blocks.OAK_LOG, Blocks.SPRUCE_LOG, Blocks.BIRCH_LOG, Blocks.ACACIA_LOG,
            Blocks.STRIPPED_OAK_LOG, Blocks.STRIPPED_SPRUCE_LOG,
            Blocks.OAK_STAIRS, Blocks.SPRUCE_STAIRS, Blocks.COBBLESTONE_STAIRS,
            Blocks.OAK_SLAB, Blocks.SPRUCE_SLAB, Blocks.COBBLESTONE_SLAB, Blocks.SMOOTH_STONE_SLAB,
            Blocks.GLASS, Blocks.GLASS_PANE,
            Blocks.OAK_DOOR, Blocks.SPRUCE_DOOR,
            Blocks.OAK_FENCE, Blocks.SPRUCE_FENCE, Blocks.OAK_FENCE_GATE,

            // Village furniture / job-site blocks
            Blocks.HAY_BLOCK, Blocks.COMPOSTER, Blocks.BARREL, Blocks.BELL,
            Blocks.CAULDRON, Blocks.BREWING_STAND,
            Blocks.CARTOGRAPHY_TABLE, Blocks.FLETCHING_TABLE, Blocks.SMITHING_TABLE,
            Blocks.LECTERN, Blocks.GRINDSTONE, Blocks.STONECUTTER, Blocks.LOOM,
            Blocks.SMOKER, Blocks.BLAST_FURNACE, Blocks.TORCH,

            // Village farms
            Blocks.WHEAT, Blocks.CARROTS, Blocks.POTATOES, Blocks.BEETROOTS,
            Blocks.FARMLAND, Blocks.DIRT_PATH,

            // Desert village specific
            Blocks.SANDSTONE, Blocks.SANDSTONE_STAIRS, Blocks.SANDSTONE_SLAB,
            Blocks.SMOOTH_SANDSTONE, Blocks.CUT_SANDSTONE,
            Blocks.CHISELED_SANDSTONE, Blocks.SMOOTH_SANDSTONE_STAIRS, Blocks.SMOOTH_SANDSTONE_SLAB,

            // Savanna village specific
            Blocks.ACACIA_STAIRS, Blocks.ACACIA_SLAB, Blocks.ACACIA_FENCE, Blocks.ACACIA_FENCE_GATE,
            Blocks.STRIPPED_ACACIA_LOG, Blocks.ACACIA_DOOR,

            // Taiga village specific
            Blocks.COBBLED_DEEPSLATE, Blocks.SPRUCE_FENCE_GATE,

            // Snowy village specific
            Blocks.SNOW_BLOCK, Blocks.PACKED_ICE
    );

    private VillageBlockSet() {}

    public static boolean contains(Block block) {
        return BLOCKS.contains(block);
    }
}
