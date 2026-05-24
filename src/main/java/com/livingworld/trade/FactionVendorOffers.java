package com.livingworld.trade;

import com.livingworld.reputation.RepTier;
import com.solegendary.reignofnether.faction.Faction;
import com.solegendary.reignofnether.registrars.EntityRegistrar;
import java.util.ArrayList;
import java.util.List;
import net.minecraft.world.entity.npc.VillagerTrades;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.enchantment.Enchantments;
import net.minecraft.world.item.trading.MerchantOffer;
import net.minecraft.world.item.trading.MerchantOffers;

/**
 * Generates {@link MerchantOffers} for the faction trade UI.
 *
 * <p>Uses Minecraft's vanilla {@code MerchantMenu} system — no custom screen
 * required. Offers are regenerated fresh each time the player opens the
 * trade menu so tier-based availability is always current.
 *
 * <p>Discount at Exalted: 15% off buy prices (rounded down). This is applied
 * by adjusting {@code MerchantOffer} input item counts directly.
 */
public final class FactionVendorOffers {

    /** Max uses for expensive/rare items before restock. */
    private static final int RARE_MAX_USES = 1;
    private static final int COMMON_MAX_USES = 8;

    private FactionVendorOffers() {}

    /**
     * Build offers for a faction at the given tier.
     * Returns an empty list if tier < FRIENDLY.
     */
    public static MerchantOffers buildOffers(Faction faction, RepTier tier) {
        MerchantOffers offers = new MerchantOffers();
        if (tier.ordinal() < RepTier.FRIENDLY.ordinal()) return offers;

        boolean exalted = tier == RepTier.EXALTED;
        float discount = exalted ? 0.85f : 1.0f;

        List<MerchantOffer> list = switch (faction) {
            case VILLAGERS -> villagerOffers(exalted, discount);
            case MONSTERS -> monsterOffers(exalted, discount);
            case PIGLINS -> piglinOffers(exalted, discount);
            default -> List.of();
        };
        list.forEach(offers::add);

        // Hire tab — Exalted only
        if (exalted) hireOffers(faction).forEach(offers::add);

        return offers;
    }

    // --------------------------------------------------------- Villagers

    private static List<MerchantOffer> villagerOffers(
        boolean exalted,
        float discount
    ) {
        List<MerchantOffer> list = new ArrayList<>();

        // Buy from player
        list.add(
            sell(
                stack(Items.WHEAT, 16),
                stack(Items.EMERALD, 4),
                COMMON_MAX_USES
            )
        );
        list.add(
            sell(
                stack(Items.OAK_LOG, 16),
                stack(Items.EMERALD, 3),
                COMMON_MAX_USES
            )
        );
        list.add(
            sell(
                stack(Items.RAW_IRON, 8),
                stack(Items.EMERALD, 5),
                COMMON_MAX_USES
            )
        );
        list.add(
            sell(
                stack(Items.DIAMOND, 1),
                stack(Items.EMERALD, 8),
                COMMON_MAX_USES
            )
        );

        // Sell to player (Friendly)
        list.add(
            buy(
                scale(8, discount),
                Items.BREAD,
                stack(Items.WHEAT, 4),
                COMMON_MAX_USES
            )
        );
        list.add(
            buy(
                scale(6, discount),
                Items.IRON_SWORD,
                stack(Items.IRON_INGOT, 1),
                COMMON_MAX_USES
            )
        );

        // Enchanted book (Unbreaking III)
        ItemStack unbreaking = new ItemStack(Items.ENCHANTED_BOOK);
        unbreaking.enchant(Enchantments.UNBREAKING, 3);
        list.add(
            new MerchantOffer(
                stack(Items.EMERALD, (int) (8 * discount)),
                stack(Items.BOOK, 1),
                unbreaking,
                COMMON_MAX_USES,
                0,
                0.05f
            )
        );

        if (exalted) {
            // Sell to player (Exalted)
            ItemStack sharpness = new ItemStack(Items.ENCHANTED_BOOK);
            sharpness.enchant(Enchantments.SHARPNESS, 4);
            list.add(
                new MerchantOffer(
                    stack(Items.EMERALD, (int) (16 * discount)),
                    stack(Items.BOOK, 1),
                    sharpness,
                    COMMON_MAX_USES,
                    0,
                    0.05f
                )
            );

            list.add(
                buy(
                    scale(24, discount),
                    Items.DIAMOND_SWORD,
                    stack(Items.DIAMOND, 1),
                    COMMON_MAX_USES
                )
            );
            list.add(
                buy(scale(32, discount), Items.TOTEM_OF_UNDYING, RARE_MAX_USES)
            );
        }
        return list;
    }

    // --------------------------------------------------------- Monsters

    private static List<MerchantOffer> monsterOffers(
        boolean exalted,
        float discount
    ) {
        List<MerchantOffer> list = new ArrayList<>();

        // Buy from player
        list.add(
            sell(
                stack(Items.BONE, 16),
                stack(Items.GUNPOWDER, 8),
                COMMON_MAX_USES
            )
        );
        list.add(
            sell(
                stack(Items.ROTTEN_FLESH, 16),
                stack(Items.BONE, 4),
                COMMON_MAX_USES
            )
        );
        list.add(
            sell(
                stack(Items.GUNPOWDER, 4),
                stack(Items.SPIDER_EYE, 2),
                COMMON_MAX_USES
            )
        );
        list.add(
            sell(stack(Items.COAL, 16), stack(Items.BONE, 4), COMMON_MAX_USES)
        );

        // Sell (Friendly)
        list.add(
            buy(
                stack(Items.BONE, (int) (4 * discount)),
                stack(Items.FEATHER, 4),
                stack(Items.ARROW, 32),
                COMMON_MAX_USES
            )
        );
        list.add(
            buy(
                stack(Items.ROTTEN_FLESH, 4),
                Items.GUNPOWDER,
                8,
                COMMON_MAX_USES
            )
        );
        list.add(
            buy(
                stack(Items.WITHER_ROSE, 4),
                stack(Items.BONE, (int) (8 * discount)),
                COMMON_MAX_USES
            )
        );

        if (exalted) {
            list.add(
                buy(
                    stack(Items.GUNPOWDER, (int) (12 * discount)),
                    stack(Items.SAND, 8),
                    stack(Items.TNT, 4),
                    COMMON_MAX_USES
                )
            );
            list.add(
                buy(
                    stack(Items.NETHER_STAR, 1),
                    stack(Items.BONE, 64),
                    stack(Items.ROTTEN_FLESH, 32),
                    RARE_MAX_USES
                )
            );
        }
        return list;
    }

    // --------------------------------------------------------- Piglins

    private static List<MerchantOffer> piglinOffers(
        boolean exalted,
        float discount
    ) {
        List<MerchantOffer> list = new ArrayList<>();

        // Buy from player
        list.add(
            sell(
                stack(Items.GOLD_INGOT, 4),
                stack(Items.NETHER_WART, 4),
                COMMON_MAX_USES
            )
        );
        list.add(
            sell(
                stack(Items.NETHER_WART, 8),
                stack(Items.GOLD_INGOT, 2),
                COMMON_MAX_USES
            )
        );
        list.add(
            sell(
                stack(Items.BLACKSTONE, 32),
                stack(Items.GOLD_INGOT, 4),
                COMMON_MAX_USES
            )
        );

        // Sell (Friendly)
        list.add(
            buy(
                scale(4, discount),
                Items.GOLD_INGOT,
                stack(Items.NETHER_WART, 8),
                COMMON_MAX_USES
            )
        );
        list.add(
            buy(
                stack(Items.GOLD_INGOT, (int) (4 * discount)),
                stack(Items.NETHER_WART, 2),
                fireResistancePotion(),
                COMMON_MAX_USES
            )
        );
        list.add(buy(scale(8, discount), Items.OBSIDIAN, 4, COMMON_MAX_USES));
        list.add(
            buy(
                stack(Items.GOLD_INGOT, (int) (6 * discount)),
                stack(Items.NETHER_WART, 4),
                stack(Items.CRYING_OBSIDIAN, 2),
                COMMON_MAX_USES
            )
        );

        if (exalted) {
            list.add(
                buy(
                    stack(Items.GOLD_INGOT, (int) (24 * discount)),
                    stack(Items.NETHER_WART, 16),
                    stack(Items.NETHERITE_SCRAP, 1),
                    RARE_MAX_USES
                )
            );
            list.add(
                buy(
                    stack(Items.GOLD_INGOT, (int) (16 * discount)),
                    stack(Items.CRYING_OBSIDIAN, 8),
                    stack(Items.RESPAWN_ANCHOR, 1),
                    RARE_MAX_USES
                )
            );
        }
        return list;
    }

    // ----------------------------------------------------------------- helpers

    private static ItemStack stack(
        net.minecraft.world.item.Item item,
        int count
    ) {
        return new ItemStack(item, count);
    }

    private static int scale(int base, float discount) {
        return Math.max(1, (int) (base * discount));
    }

    /** Sell item to player: player gives cost1 (+cost2 if non-null), gets result. */
    private static MerchantOffer buy(
        ItemStack cost1,
        ItemStack cost2,
        ItemStack result,
        int maxUses
    ) {
        return new MerchantOffer(cost1, cost2, result, maxUses, 0, 0.05f);
    }

    private static MerchantOffer buy(
        ItemStack cost1,
        ItemStack result,
        int maxUses
    ) {
        return new MerchantOffer(cost1, result, maxUses, 0, 0.05f);
    }

    private static MerchantOffer buy(
        int cost1Count,
        net.minecraft.world.item.Item item,
        int resultCount,
        int maxUses
    ) {
        return buy(
            stack(Items.EMERALD, cost1Count),
            stack(item, resultCount),
            maxUses
        );
    }

    private static MerchantOffer buy(
        int cost1Count,
        net.minecraft.world.item.Item item,
        ItemStack cost2,
        int maxUses
    ) {
        return buy(
            stack(Items.EMERALD, cost1Count),
            cost2,
            stack(item, 1),
            maxUses
        );
    }

    private static MerchantOffer buy(
        ItemStack cost1,
        net.minecraft.world.item.Item item,
        int count,
        int maxUses
    ) {
        return buy(cost1, stack(item, count), maxUses);
    }

    /** Buy 1 of item for emeralds — convenience for single-output trades. */
    private static MerchantOffer buy(
        int emeraldCost,
        net.minecraft.world.item.Item item,
        int maxUses
    ) {
        return new MerchantOffer(
            stack(Items.EMERALD, emeraldCost),
            stack(item, 1),
            maxUses,
            0,
            0.05f
        );
    }

    /** Player sells item to faction; player gives input, gets output. */
    private static MerchantOffer sell(
        ItemStack playerGives,
        ItemStack playerGets,
        int maxUses
    ) {
        return new MerchantOffer(playerGives, playerGets, maxUses, 0, 0.05f);
    }

    // ------------------------------------------------------------ hire offers

    /**
     * Hire tokens purchasable at Exalted tier. Each offer has maxUses=3 and
     * a 72 000-tick (3-day) restock so players can't spam-hire armies.
     * The result is a {@link HireToken} echo shard with NBT identifying the
     * unit type; right-clicking it spawns the unit owned by the player.
     */
    private static List<MerchantOffer> hireOffers(Faction faction) {
        List<MerchantOffer> list = new ArrayList<>();
        int maxUses = 3;
        int restock = 72000; // 3 in-game days
        switch (faction) {
            case VILLAGERS -> {
                list.add(
                    new MerchantOffer(
                        stack(Items.EMERALD, 16),
                        stack(Items.WHEAT, 8),
                        HireToken.create(
                            Faction.VILLAGERS,
                            "villager_unit",
                            "Villager Worker"
                        ),
                        maxUses,
                        restock,
                        0.05f
                    )
                );
                list.add(
                    new MerchantOffer(
                        stack(Items.EMERALD, 20),
                        stack(Items.IRON_INGOT, 4),
                        HireToken.create(
                            Faction.VILLAGERS,
                            "militia_unit",
                            "Militia Soldier"
                        ),
                        maxUses,
                        restock,
                        0.05f
                    )
                );
                list.add(
                    new MerchantOffer(
                        stack(Items.EMERALD, 48),
                        stack(Items.IRON_BLOCK, 8),
                        HireToken.create(
                            Faction.VILLAGERS,
                            "iron_golem_unit",
                            "Iron Golem"
                        ),
                        1,
                        restock,
                        0.05f
                    )
                ); // rare — max 1
            }
            case MONSTERS -> {
                list.add(
                    new MerchantOffer(
                        stack(Items.BONE, 8),
                        stack(Items.ROTTEN_FLESH, 8),
                        HireToken.create(
                            Faction.MONSTERS,
                            "zombie_villager_unit",
                            "Zombie Worker"
                        ),
                        maxUses,
                        restock,
                        0.05f
                    )
                );
                list.add(
                    new MerchantOffer(
                        stack(Items.BONE, 12),
                        stack(Items.FEATHER, 4),
                        HireToken.create(
                            Faction.MONSTERS,
                            "skeleton_unit",
                            "Skeleton Archer"
                        ),
                        maxUses,
                        restock,
                        0.05f
                    )
                );
                list.add(
                    new MerchantOffer(
                        stack(Items.GUNPOWDER, 16),
                        stack(Items.BONE, 8),
                        HireToken.create(
                            Faction.MONSTERS,
                            "creeper_unit",
                            "Creeper"
                        ),
                        maxUses,
                        restock,
                        0.05f
                    )
                );
            }
            case PIGLINS -> {
                list.add(
                    new MerchantOffer(
                        stack(Items.GOLD_INGOT, 16),
                        stack(Items.NETHER_WART, 8),
                        HireToken.create(
                            Faction.PIGLINS,
                            "grunt_unit",
                            "Grunt Worker"
                        ),
                        maxUses,
                        restock,
                        0.05f
                    )
                );
                list.add(
                    new MerchantOffer(
                        stack(Items.GOLD_INGOT, 20),
                        stack(Items.BLACKSTONE, 8),
                        HireToken.create(
                            Faction.PIGLINS,
                            "brute_unit",
                            "Brute"
                        ),
                        maxUses,
                        restock,
                        0.05f
                    )
                );
                list.add(
                    new MerchantOffer(
                        stack(Items.GOLD_INGOT, 24),
                        stack(Items.BLAZE_ROD, 8),
                        HireToken.create(
                            Faction.PIGLINS,
                            "blaze_unit",
                            "Blaze"
                        ),
                        maxUses,
                        restock,
                        0.05f
                    )
                );
            }
        }
        return list;
    }

    private static ItemStack fireResistancePotion() {
        return net.minecraft.world.item.alchemy.PotionUtils.setPotion(
            new ItemStack(Items.POTION),
            net.minecraft.world.item.alchemy.Potions.FIRE_RESISTANCE
        );
    }
}
