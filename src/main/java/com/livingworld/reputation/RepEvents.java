package com.livingworld.reputation;

import com.livingworld.LivingWorld;
import com.livingworld.bot.FactionBotRegistry;
import com.livingworld.bot.FactionResourceChest;
import com.solegendary.reignofnether.building.BuildingPlacement;
import com.solegendary.reignofnether.building.BuildingServerEvents;
import com.solegendary.reignofnether.faction.Faction;
import com.solegendary.reignofnether.player.PlayerServerEvents;
import com.solegendary.reignofnether.unit.interfaces.Unit;
import com.solegendary.reignofnether.unit.interfaces.WorkerUnit;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraftforge.event.entity.living.LivingDeathEvent;
import net.minecraftforge.event.entity.player.PlayerContainerEvent;
import net.minecraftforge.event.entity.player.PlayerEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;

/**
 * Forge event handlers that translate world events into reputation changes.
 *
 * <p>Handles:
 * <ul>
 *   <li>{@link LivingDeathEvent} — detect kills of bot-owned units by players</li>
 *   <li>{@link PlayerContainerEvent.Open} — detect accessing a bot building's container</li>
 *   <li>{@link PlayerEvent.PlayerLoggedInEvent} — sync alliance state on join</li>
 *   <li>Passive decay tick — triggered from {@code FactionBotEvents.onLevelTick}</li>
 * </ul>
 *
 * <p>Gifting and trading are handled in {@link com.livingworld.trade.TradeInteractionEvents}.
 */
public class RepEvents {

    private static long lastDecayDay = -1L;

    // --------------------------------------------------------- kill detection

    @SubscribeEvent
    public static void onLivingDeath(LivingDeathEvent event) {
        LivingEntity victim = event.getEntity();
        if (victim.level().isClientSide()) return;
        if (!(victim instanceof Unit unit)) return;

        // Only care about units owned by an NPC bot
        String ownerName = unit.getOwnerName();
        if (ownerName == null || ownerName.isBlank()) return;
        if (!PlayerServerEvents.isBot(ownerName)) return;

        // Only apply rep if the killer is a real (non-RTS) player
        var source = event.getSource();
        var directKiller = source.getDirectEntity();
        var indirectKiller = source.getEntity();
        Player killer = null;
        if (directKiller instanceof Player p) killer = p;
        else if (indirectKiller instanceof Player p) killer = p;
        if (
            killer == null || !(killer instanceof ServerPlayer serverPlayer)
        ) return;
        // RTS players are already at war — rep doesn't apply to them
        if (
            PlayerServerEvents.isRTSPlayer(killer.getName().getString())
        ) return;

        Faction faction = factionOfBot(ownerName);
        if (faction == null) return;

        ServerLevel level = serverPlayer.serverLevel();
        boolean isWorker = victim instanceof WorkerUnit;
        int delta = isWorker ? -100 : -30;
        RepSource source2 = isWorker
            ? RepSource.KILL_WORKER
            : RepSource.KILL_MILITARY;
        ReputationManager.adjustReputation(
            level,
            serverPlayer.getUUID(),
            faction,
            delta,
            source2
        );
    }

    // ------------------------------------------------------ container access

    /**
     * When a player opens a container inside a bot-owned building, record their
     * UUID so {@link FactionResourceChest} can attribute any theft to them.
     *
     * <p>The rep penalty itself is no longer fired here — it now fires only when
     * items are actually removed (detected by comparing chest snapshots in
     * {@link FactionResourceChest#tick}). This prevents penalising a player
     * for merely browsing the chest without taking anything.
     */
    @SubscribeEvent
    public static void onContainerOpen(PlayerContainerEvent.Open event) {
        if (!(event.getEntity() instanceof ServerPlayer player)) return;
        if (player.level().isClientSide()) return;
        if (
            PlayerServerEvents.isRTSPlayer(player.getName().getString())
        ) return;

        BlockPos containerPos = getContainerPos(event);
        if (containerPos == null) return;

        String ownerBot = botOwnerAtPos(containerPos);
        if (ownerBot == null) return;
        if (!PlayerServerEvents.isBot(ownerBot)) return;

        // Record who opened this chest for theft attribution in FactionResourceChest
        FactionResourceChest.LAST_OPENER.put(
            containerPos.asLong(),
            player.getUUID()
        );
    }

    @Nullable
    private static BlockPos getContainerPos(PlayerContainerEvent.Open event) {
        // The container's position is accessible through the menu's
        // level & block entity when it's a chest/barrel.
        var menu = event.getContainer();
        // Use reflection-free approach: get the player's interaction target
        var player = event.getEntity();
        if (player instanceof ServerPlayer sp) {
            var hitResult = sp.pick(4.5, 0, false);
            if (
                hitResult instanceof net.minecraft.world.phys.BlockHitResult bhr
            ) {
                return bhr.getBlockPos();
            }
        }
        return null;
    }

    @Nullable
    private static String botOwnerAtPos(BlockPos pos) {
        for (BuildingPlacement bp : BuildingServerEvents.getBuildings()) {
            if (!bp.isBuilt) continue;
            if (!PlayerServerEvents.isBot(bp.ownerName)) continue;
            if (bp.isPosInsideBuilding(pos)) return bp.ownerName;
        }
        return null;
    }

    // ----------------------------------------------------- player join

    @SubscribeEvent
    public static void onPlayerJoin(PlayerEvent.PlayerLoggedInEvent event) {
        if (!(event.getEntity() instanceof ServerPlayer player)) return;
        ServerLevel level = player.serverLevel();
        if (level == null) return;
        // Sync alliance state based on saved rep (defaults to Neutral = allied if no data)
        ReputationManager.syncAllFactions(
            level,
            player.getUUID(),
            player.getName().getString()
        );
    }

    // ---------------------------------------------------- passive decay

    /** Called from FactionBotEvents.onLevelTick — once per in-game day. */
    public static void tickDecay(ServerLevel level) {
        long currentDay = level.getGameTime() / 24000L;
        if (currentDay <= lastDecayDay) return;
        lastDecayDay = currentDay;
        ReputationManager.applyDecay(level);
    }

    // --------------------------------------------------------------- helpers

    @Nullable
    private static Faction factionOfBot(String botName) {
        for (var bot : FactionBotRegistry.all()) {
            if (bot.name().equals(botName)) return bot.faction;
        }
        return null;
    }

    // Annotation import workaround
    private @interface Nullable {}
}
