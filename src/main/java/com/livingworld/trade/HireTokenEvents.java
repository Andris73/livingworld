package com.livingworld.trade;

import com.livingworld.LivingWorld;
import com.livingworld.reputation.ReputationSaveData;
import com.solegendary.reignofnether.faction.Faction;
import com.solegendary.reignofnether.unit.EnemySearchBehaviour;
import com.solegendary.reignofnether.unit.UnitServerEvents;
import com.solegendary.reignofnether.unit.interfaces.AttackerUnit;
import com.solegendary.reignofnether.unit.interfaces.WorkerUnit;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.item.ItemStack;
import net.minecraftforge.event.entity.player.PlayerInteractEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.registries.ForgeRegistries;

/**
 * Handles right-click use of hire tokens produced by {@link HireToken}.
 *
 * <p>On use:
 * <ol>
 *   <li>Validate the player has the token and is within the hire cap.</li>
 *   <li>Spawn the appropriate RoN unit owned by the player.</li>
 *   <li>Set search behaviour to {@code NEAREST_ENEMY_UNIT} on military units
 *       so they defend the player automatically.</li>
 *   <li>Consume the token from the player's hand.</li>
 *   <li>Increment the hire count in {@link ReputationSaveData}.</li>
 * </ol>
 */
public class HireTokenEvents {

    /** Maximum hired units per faction per player. */
    public static final int MAX_HIRE_PER_FACTION = 3;

    @SubscribeEvent
    public static void onRightClickItem(
        PlayerInteractEvent.RightClickItem event
    ) {
        if (event.getHand() != InteractionHand.MAIN_HAND) return;
        tryDeployHireToken(event.getEntity(), event.getItemStack(), event);
    }

    @SubscribeEvent
    public static void onRightClickBlock(
        PlayerInteractEvent.RightClickBlock event
    ) {
        if (event.getHand() != InteractionHand.MAIN_HAND) return;
        tryDeployHireToken(event.getEntity(), event.getItemStack(), event);
    }

    private static void tryDeployHireToken(
        net.minecraft.world.entity.player.Player player,
        ItemStack held,
        PlayerInteractEvent event
    ) {
        if (!(player instanceof ServerPlayer sp)) return;
        if (!HireToken.isHireToken(held)) return;

        event.setCanceled(true);
        event.setCancellationResult(InteractionResult.SUCCESS);

        Faction faction = HireToken.getFaction(held);
        String unitType = HireToken.getUnitType(held);
        String label = HireToken.getLabel(held);
        if (faction == null || unitType == null) return;

        ServerLevel level = sp.serverLevel();

        // Enforce hire cap
        ReputationSaveData saveData = ReputationSaveData.get(level);
        ReputationSaveData.Entry entry = saveData.getOrCreate(
            sp.getUUID(),
            faction
        );
        if (entry.hireCount >= MAX_HIRE_PER_FACTION) {
            sp.sendSystemMessage(
                Component.literal(
                    "[LivingWorld] You already have the maximum " +
                        MAX_HIRE_PER_FACTION +
                        " hired units from the " +
                        faction.name().toLowerCase() +
                        "."
                )
            );
            return;
        }

        // Spawn the unit. We look up by registry ID (e.g. "villager_unit")
        // rather than going through EntityRegistrar.getEntityType, which only
        // recognises *Prod display names ("Villager", "Iron Golem", …) and
        // has no entry at all for transitional unit types like militia_unit.
        EntityType<? extends Mob> entityType = resolveUnitEntityType(unitType);
        if (entityType == null) {
            sp.sendSystemMessage(
                Component.literal(
                    "[LivingWorld] Unknown unit type: " + unitType
                )
            );
            return;
        }

        String ownerName = sp.getName().getString();
        Entity spawned = UnitServerEvents.spawnMob(
            entityType,
            level,
            sp.blockPosition(),
            ownerName
        );
        if (spawned == null) {
            sp.sendSystemMessage(
                Component.literal("[LivingWorld] Failed to spawn unit.")
            );
            return;
        }

        // Military units: search for enemies autonomously
        if (
            spawned instanceof AttackerUnit attacker &&
            !(spawned instanceof WorkerUnit)
        ) {
            attacker.setEnemySearchBehaviour(
                EnemySearchBehaviour.NEAREST_ENEMY_UNIT
            );
        }

        // Workers: start gathering food
        if (
            spawned instanceof WorkerUnit worker &&
            worker.getGatherResourceGoal() != null
        ) {
            worker
                .getGatherResourceGoal()
                .setTargetResourceName(
                    com.solegendary.reignofnether.resources.ResourceName.FOOD
                );
        }

        // Consume the token and update hire count
        held.shrink(1);
        entry.hireCount++;
        saveData.setDirty();

        sp.sendSystemMessage(
            Component.literal(
                "[LivingWorld] " +
                    (label != null ? label : unitType) +
                    " deployed!"
            ).withStyle(
                net.minecraft.network.chat.Style.EMPTY.withColor(0x55FF55)
            )
        );
    }

    /**
     * Resolve a registry ID like {@code "villager_unit"} to its RoN
     * {@link EntityType}. Returns {@code null} if the entry isn't registered
     * or isn't a {@code Mob}.
     */
    @SuppressWarnings("unchecked")
    private static EntityType<? extends Mob> resolveUnitEntityType(
        String registryId
    ) {
        ResourceLocation key = new ResourceLocation(
            "reignofnether",
            registryId
        );
        EntityType<?> type = ForgeRegistries.ENTITY_TYPES.getValue(key);
        if (type == null) {
            LivingWorld.LOGGER.warn(
                "[HireTokenEvents] No entity registered for {}",
                key
            );
            return null;
        }
        // Sanity: all RoN units extend Mob.
        return (EntityType<? extends Mob>) type;
    }
}
