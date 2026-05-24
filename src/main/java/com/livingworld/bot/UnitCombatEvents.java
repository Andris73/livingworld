package com.livingworld.bot;

import com.livingworld.LivingWorld;
import com.solegendary.reignofnether.player.PlayerServerEvents;
import com.solegendary.reignofnether.unit.EnemySearchBehaviour;
import com.solegendary.reignofnether.unit.UnitServerEvents;
import com.solegendary.reignofnether.unit.interfaces.AttackerUnit;
import com.solegendary.reignofnether.unit.interfaces.Unit;
import com.solegendary.reignofnether.unit.interfaces.WorkerUnit;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraftforge.event.entity.living.LivingHurtEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;

/**
 * Handles combat retaliation and military unit protection for NPC bot villages.
 *
 * <p>Two behaviours are implemented here:
 * <ol>
 *   <li><b>Fight-back</b>: any bot-owned {@link AttackerUnit} that takes damage
 *       from a living entity will immediately issue an attack-move toward that
 *       attacker. Workers do low damage, but at least they try — making them
 *       feel less like passive punching bags. The unit's existing AI takes over
 *       once in range and will resume normal behaviour (gather/repair) once the
 *       threat is gone.</li>
 *   <li><b>Military protection</b>: when <em>any</em> bot-owned unit is hurt,
 *       all idle military units within {@link #PROTECT_RADIUS} blocks rush to
 *       defend it by attack-moving toward the aggressor. Workers get a de-facto
 *       bodyguard without needing explicit guard assignments.</li>
 * </ol>
 */
public final class UnitCombatEvents {

    /**
     * Distance within which idle military units respond to an ally being
     * attacked. At 80 blocks this covers the entire settled area of most
     * early-game villages without pulling garrison units away from a distant
     * front.
     */
    public static final int PROTECT_RADIUS = 80;

    private UnitCombatEvents() {}

    @SubscribeEvent
    public static void onUnitHurt(LivingHurtEvent event) {
        // Only process server-side.
        if (event.getEntity().level().isClientSide()) return;

        LivingEntity victim = event.getEntity();
        Entity sourceEntity = event.getSource().getEntity();

        // We only care about damage dealt to bot-owned RoN units.
        if (!(victim instanceof Unit victimUnit)) return;
        String ownerName = victimUnit.getOwnerName();
        if (ownerName == null || ownerName.isBlank()) return;
        if (!PlayerServerEvents.isBot(ownerName)) return;

        // Only react to damage from another living entity (not fire, fall,
        // suffocation, etc. — those don't have a valid "attacker" to target).
        if (!(sourceEntity instanceof LivingEntity attacker)) return;

        // --- Fix: Fight back -----------------------------------------------
        // If the attacked unit is an AttackerUnit (includes workers, which
        // implement AttackerUnit for harvesting), make it chase and engage.
        // setEnemySearchBehaviour ensures the RoN attack AI is armed;
        // setAttackMoveTarget gives it a concrete destination so it doesn't
        // just stand still waiting for a new patrol tick.
        if (victim instanceof AttackerUnit victimAttacker) {
            victimAttacker.setEnemySearchBehaviour(
                EnemySearchBehaviour.NEAREST_ENEMY_UNIT
            );
            victimAttacker.setAttackMoveTarget(attacker.blockPosition());
        }

        // --- Fix: Military protection ----------------------------------------
        // Direct any idle military units close to the victim to rush the
        // aggressor. Workers are excluded — only combat units respond.
        alertNearbyMilitary(victim, attacker, ownerName);
    }

    /**
     * Scans all units owned by {@code ownerName} for idle military units
     * within {@link #PROTECT_RADIUS} of {@code victim} and directs them to
     * attack-move toward {@code aggressor}.
     *
     * <p>Units that are already engaged (have a target or an active
     * attack-move waypoint) are never interrupted — we don't want to pull
     * soldiers off an active fight to re-route to the same battle.
     */
    private static void alertNearbyMilitary(
        LivingEntity victim,
        LivingEntity aggressor,
        String ownerName
    ) {
        double radiusSq = (double) PROTECT_RADIUS * PROTECT_RADIUS;
        int dispatched = 0;

        for (LivingEntity entity : UnitServerEvents.getAllUnits()) {
            if (!(entity instanceof AttackerUnit responder)) continue;
            if (entity instanceof WorkerUnit) continue; // workers don't guard
            if (!(entity instanceof Unit unit)) continue;
            if (!unit.getOwnerName().equals(ownerName)) continue;

            // Must be close enough to the unit being attacked.
            if (entity.distanceToSqr(victim) > radiusSq) continue;

            // Don't pull units out of an active engagement.
            if (
                unit.getTargetGoal() != null &&
                unit.getTargetGoal().getTarget() != null
            ) continue;
            if (responder.getAttackMoveTarget() != null) continue;

            responder.setEnemySearchBehaviour(
                EnemySearchBehaviour.NEAREST_ENEMY_UNIT
            );
            responder.setAttackMoveTarget(aggressor.blockPosition());
            dispatched++;
        }

        if (dispatched > 0) {
            LivingWorld.LOGGER.debug(
                "[UnitCombatEvents] {} military unit(s) from {} responding to defend {} (attacker id={})",
                dispatched,
                ownerName,
                ((Entity) victim).getId(),
                aggressor.getId()
            );
        }
    }
}
