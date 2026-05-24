package com.livingworld.trade;

import com.livingworld.bot.FactionBotRegistry;
import com.livingworld.reputation.RepSource;
import com.livingworld.reputation.RepTier;
import com.livingworld.reputation.ReputationManager;
import com.livingworld.reputation.ReputationSaveData;
import com.solegendary.reignofnether.faction.Faction;
import com.solegendary.reignofnether.player.PlayerServerEvents;
import com.solegendary.reignofnether.unit.interfaces.Unit;
import com.solegendary.reignofnether.unit.interfaces.WorkerUnit;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import javax.annotation.Nullable;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.Style;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.SimpleMenuProvider;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.MerchantMenu;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.trading.Merchant;
import net.minecraft.world.item.trading.MerchantOffer;
import net.minecraft.world.item.trading.MerchantOffers;
import net.minecraftforge.event.entity.player.PlayerInteractEvent;
import net.minecraftforge.event.entity.player.TradeWithVillagerEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;

/**
 * Handles right-click-on-worker for gifting and trading.
 *
 * <p>Gifting: right-click a bot worker while holding a preferred resource → +20 rep.
 * Trading: right-click a bot worker at Friendly+ → vanilla MerchantMenu opens.
 * Each completed trade → +8 rep (capped at 40/day).
 */
public class TradeInteractionEvents {

    // --------------------------------------------------------- gift item lists

    private static final Set<Item> VILLAGER_GIFTS = Set.of(
        Items.WHEAT,
        Items.BREAD,
        Items.RAW_IRON,
        Items.OAK_LOG,
        Items.BIRCH_LOG,
        Items.SPRUCE_LOG,
        Items.ACACIA_LOG,
        Items.DARK_OAK_LOG,
        Items.JUNGLE_LOG,
        Items.EMERALD
    );

    private static final Set<Item> MONSTER_GIFTS = Set.of(
        Items.BONE,
        Items.ROTTEN_FLESH,
        Items.GUNPOWDER,
        Items.SPIDER_EYE,
        Items.COAL
    );

    private static final Set<Item> PIGLIN_GIFTS = Set.of(
        Items.GOLD_INGOT,
        Items.GOLD_NUGGET,
        Items.NETHER_WART,
        Items.BLACKSTONE
    );

    private static final long GIFT_COOLDOWN = 12000L; // 10 min
    private static final int GIFT_DELTA = 20;

    // ------------------------------------------------------------- right-click

    @SubscribeEvent
    public static void onEntityInteract(
        PlayerInteractEvent.EntityInteract event
    ) {
        if (event.getLevel().isClientSide()) return;
        if (!(event.getEntity() instanceof ServerPlayer player)) return;
        if (event.getHand() != InteractionHand.MAIN_HAND) return;
        if (
            PlayerServerEvents.isRTSPlayer(player.getName().getString())
        ) return;

        if (!(event.getTarget() instanceof LivingEntity target)) return;
        if (!(target instanceof WorkerUnit)) return;
        if (!(target instanceof Unit unit)) return;

        String ownerName = unit.getOwnerName();
        if (!PlayerServerEvents.isBot(ownerName)) return;

        Faction faction = factionOf(ownerName);
        if (faction == null) return;

        ServerLevel level = player.serverLevel();
        RepTier tier = ReputationManager.getTier(
            level,
            player.getUUID(),
            faction
        );
        ItemStack held = player.getMainHandItem();

        // Gift path
        if (
            tier.ordinal() >= RepTier.NEUTRAL.ordinal() && isGift(held, faction)
        ) {
            long now = level.getGameTime();
            String key = player.getUUID() + ":" + faction.name();
            Long last = GIFT_COOLDOWNS.get(key);
            if (last == null || now - last >= GIFT_COOLDOWN) {
                GIFT_COOLDOWNS.put(key, now);
                ReputationManager.adjustReputation(
                    level,
                    player.getUUID(),
                    faction,
                    GIFT_DELTA,
                    RepSource.GIFT
                );
                player.sendSystemMessage(
                    Component.literal(
                        "[LivingWorld] Your gift is gratefully received."
                    ).withStyle(Style.EMPTY.withColor(0x55FF55))
                );
            } else {
                player.sendSystemMessage(
                    Component.literal("[LivingWorld] They have enough for now.")
                );
            }
            event.setCanceled(true);
            return;
        }

        // Trade path
        if (tier.ordinal() >= RepTier.FRIENDLY.ordinal()) {
            MerchantOffers offers = FactionVendorOffers.buildOffers(
                faction,
                tier
            );
            if (!offers.isEmpty()) {
                openTrade(player, offers, faction);
                event.setCanceled(true);
            }
        } else if (tier == RepTier.NEUTRAL || tier == RepTier.UNFRIENDLY) {
            player.sendSystemMessage(
                Component.literal(
                    "[LivingWorld] You need better standing with the " +
                        faction.name().toLowerCase() +
                        " to trade."
                )
            );
            event.setCanceled(true);
        }
    }

    // --------------------------------------------------------------- trade rep

    @SubscribeEvent
    public static void onTradeComplete(TradeWithVillagerEvent event) {
        if (!(event.getEntity() instanceof ServerPlayer player)) return;
        // Skip if this wasn't one of our faction trade sessions
        Faction faction = OPEN_VENDOR_FACTIONS.get(player.getUUID());
        if (faction == null) return;

        ServerLevel level = player.serverLevel();
        ReputationSaveData.Entry entry = ReputationSaveData.get(
            level
        ).getOrCreate(player.getUUID(), faction);

        long currentDay = level.getGameTime() / 24000L;
        if (currentDay != entry.lastTradeDay) {
            entry.tradeRepToday = 0;
            entry.lastTradeDay = currentDay;
        }
        if (entry.tradeRepToday < ReputationManager.TRADE_REP_DAILY_CAP) {
            int gain = Math.min(
                ReputationManager.TRADE_REP_PER_TRADE,
                ReputationManager.TRADE_REP_DAILY_CAP - entry.tradeRepToday
            );
            entry.tradeRepToday += gain;
            ReputationManager.adjustReputation(
                level,
                player.getUUID(),
                faction,
                gain,
                RepSource.TRADE
            );
        }
    }

    // ---------------------------------------------------------------- helpers

    private static final Map<String, Long> GIFT_COOLDOWNS =
        new ConcurrentHashMap<>();
    private static final Map<UUID, Faction> OPEN_VENDOR_FACTIONS =
        new ConcurrentHashMap<>();

    private static void openTrade(
        ServerPlayer player,
        MerchantOffers offers,
        Faction faction
    ) {
        OPEN_VENDOR_FACTIONS.put(player.getUUID(), faction);

        // Implement Merchant from net.minecraft.world.item.trading.Merchant
        Merchant merchant = new Merchant() {
            @Nullable
            private Player tradingPlayer;

            @Override
            public void setTradingPlayer(@Nullable Player p) {
                tradingPlayer = p;
            }

            @Override
            @Nullable
            public Player getTradingPlayer() {
                return tradingPlayer;
            }

            @Override
            public boolean isClientSide() {
                return false;
            }

            @Override
            public MerchantOffers getOffers() {
                return offers;
            }

            @Override
            public void overrideOffers(MerchantOffers o) {}

            @Override
            public void overrideXp(int xp) {}

            @Override
            public void notifyTrade(MerchantOffer o) {}

            @Override
            public void notifyTradeUpdated(ItemStack stack) {}

            @Override
            public int getVillagerXp() {
                return 0;
            }

            @Override
            public boolean showProgressBar() {
                return false;
            }

            @Override
            public SoundEvent getNotifyTradeSound() {
                return SoundEvents.VILLAGER_YES;
            }
        };

        String label =
            faction.name().charAt(0) +
            faction.name().substring(1).toLowerCase() +
            " Faction";
        player.openMenu(
            new SimpleMenuProvider(
                (id, inv, p) -> new MerchantMenu(id, inv, merchant),
                Component.literal(label)
            )
        );
    }

    private static boolean isGift(ItemStack stack, Faction faction) {
        if (stack.isEmpty()) return false;
        return switch (faction) {
            case VILLAGERS -> VILLAGER_GIFTS.contains(stack.getItem());
            case MONSTERS -> MONSTER_GIFTS.contains(stack.getItem());
            case PIGLINS -> PIGLIN_GIFTS.contains(stack.getItem());
            default -> false;
        };
    }

    @Nullable
    private static Faction factionOf(String botName) {
        for (var bot : FactionBotRegistry.all()) {
            if (bot.name().equals(botName)) return bot.faction;
        }
        return null;
    }
}
