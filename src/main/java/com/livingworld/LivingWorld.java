package com.livingworld;

import com.livingworld.beacon.BeaconOfOrigins;
import com.livingworld.bot.DefenseEvents;
import com.livingworld.bot.FactionBotEvents;
import com.livingworld.bot.HostileMobBehavior;
import com.livingworld.bot.UnitCombatEvents;
import com.livingworld.command.LivingWorldCommands;
import com.livingworld.reputation.RepEvents;
import com.livingworld.trade.HireTokenEvents;
import com.livingworld.trade.TradeInteractionEvents;
import com.livingworld.world.ResourceIndexEvents;
import com.livingworld.worldgen.NeutralStructureReplacer;
import com.livingworld.worldgen.VillageReplacementQueue;
import com.livingworld.worldgen.VillageReplacer;
import com.mojang.logging.LogUtils;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.common.world.ForgeChunkManager;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.event.lifecycle.FMLCommonSetupEvent;
import net.minecraftforge.fml.javafmlmod.FMLJavaModLoadingContext;
import org.slf4j.Logger;

@Mod(LivingWorld.MOD_ID)
public class LivingWorld {

    public static final String MOD_ID = "livingworld";
    public static final Logger LOGGER = LogUtils.getLogger();

    public LivingWorld() {
        var modBus = FMLJavaModLoadingContext.get().getModEventBus();
        modBus.addListener(this::commonSetup);

        // Forge-bus event subscribers (server tick, command register, etc.).
        MinecraftForge.EVENT_BUS.register(FactionBotEvents.class);
        MinecraftForge.EVENT_BUS.register(LivingWorldCommands.class);
        MinecraftForge.EVENT_BUS.register(VillageReplacer.class);
        MinecraftForge.EVENT_BUS.register(VillageReplacementQueue.class);
        MinecraftForge.EVENT_BUS.register(NeutralStructureReplacer.class);
        MinecraftForge.EVENT_BUS.register(ResourceIndexEvents.class);
        MinecraftForge.EVENT_BUS.register(HostileMobBehavior.class);
        MinecraftForge.EVENT_BUS.register(UnitCombatEvents.class);
        MinecraftForge.EVENT_BUS.register(DefenseEvents.class);
        MinecraftForge.EVENT_BUS.register(BeaconOfOrigins.class);
        MinecraftForge.EVENT_BUS.register(RepEvents.class);
        MinecraftForge.EVENT_BUS.register(TradeInteractionEvents.class);
        MinecraftForge.EVENT_BUS.register(HireTokenEvents.class);

        LOGGER.info("[LivingWorld] Mod constructed");
    }

    private void commonSetup(final FMLCommonSetupEvent event) {
        // Register a chunk-loading validation callback so any forced chunks
        // left over from a previous (possibly crashed) session get released
        // when the world loads. Our village bots are not persisted across
        // restarts — they're re-discovered via village chunk loads — so any
        // pre-existing ticket is by definition an orphan; better to drop
        // them than slowly accumulate ghost-loaded chunks.
        event.enqueueWork(() ->
            ForgeChunkManager.setForcedChunkLoadingCallback(
                MOD_ID,
                (level, ticketHelper) -> {
                    int dropped = 0;
                    for (var entry : ticketHelper
                        .getBlockTickets()
                        .entrySet()) {
                        ticketHelper.removeAllTickets(entry.getKey());
                        dropped++;
                    }
                    if (dropped > 0) {
                        LOGGER.info(
                            "[LivingWorld] Dropped {} orphan force-load ticket(s) in {}",
                            dropped,
                            level.dimension().location()
                        );
                    }
                }
            )
        );
        LOGGER.info("[LivingWorld] Common setup complete");
    }
}
