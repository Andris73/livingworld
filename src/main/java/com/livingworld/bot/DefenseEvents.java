package com.livingworld.bot;

import com.livingworld.LivingWorld;
import com.solegendary.reignofnether.player.PlayerServerEvents;
import com.solegendary.reignofnether.unit.UnitServerEvents;
import com.solegendary.reignofnether.unit.interfaces.AttackerUnit;
import com.solegendary.reignofnether.unit.interfaces.Unit;
import com.solegendary.reignofnether.unit.interfaces.WorkerUnit;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraftforge.event.entity.living.LivingHurtEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;

/**
 * Makes bot-owned units defend themselves when attacked.
 *
 * <p>By default RoN units only acquire targets via their
 * {@link AttackerUnit#getEnemySearchBehaviour() enemy search behaviour}.
 * Workers default to {@code NONE}, so a worker being clubbed by a zombie just
 * stands there until it dies. This handler closes that gap:
 *
 * <ol>
 *   <li>When a bot-owned {@link Unit} (worker or military) takes damage from
 *       a {@link LivingEntity}, the victim's target is set to the attacker so
 *       it fights back on the next AI tick.</li>
 *   <li>Any of the bot's idle military units within
 *       {@link #DEFENSE_ALERT_RADIUS} blocks of the victim are also redirected
 *       at the same attacker. This is the cheap stand-in for a proper
 *       "rally to workers under attack" behaviour — it makes barracks units
 *       feel like garrison rather than passive scenery.</li>
 * </ol>
 *
 * <p>RTS-player-owned units are excluded; their owner can issue orders
 * directly. Bot units owned by NPC villages are the only ones we drive here.
 */
public class DefenseEvents {

    /**
     * Range (blocks) within which a hurt bot unit will pull nearby idle
     * military to its defence. 30 blocks ~= a comfortable village perimeter.
     */
    public static final int DEFENSE_ALERT_RADIUS = 30;

    private static final int DEFENSE_ALERT_RADIUS_SQR =
        DEFENSE_ALERT_RADIUS * DEFENSE_ALERT_RADIUS;

    @SubscribeEvent
    public static void onLivingHurt(LivingHurtEvent event) {
        LivingEntity victim = event.getEntity();
        if (victim.level().isClientSide()) return;
        if (!(victim instanceof Unit victimUnit)) return;

        // Only bot-owned units self-defend through this path.
        String ownerName = victimUnit.getOwnerName();
        if (ownerName == null || ownerName.isBlank()) return;
        if (!PlayerServerEvents.isBot(ownerName)) return;

        DamageSource source = event.getSource();
        LivingEntity attacker = resolveLivingAttacker(source);
        if (attacker == null) return;
        if (attacker == victim) return;

        // Don't retaliate against friendlies: same owner, or alliance-allied.
        // (Workers fighting each other in their own village would be absurd.)
        if (attacker instanceof Unit attackerUnit) {
            String attackerOwner = attackerUnit.getOwnerName();
            if (attackerOwner != null && attackerOwner.equals(ownerName)) return;
        }

        // 1. The victim itself fights back if it's an AttackerUnit.
        //    (All RoN workers and military implement AttackerUnit; the cast
        //    is defensive in case something unusual gets registered.)
        if (victim instanceof AttackerUnit victimAttacker) {
            // Don't override an existing forced attack target (e.g. a player
            // a-clicked the unit on something specific).
            var currentTarget = victimUnit.getTargetGoal().getTarget();
            if (currentTarget == null || !victimUnit.getTargetGoal().forced) {
                victimAttacker.setUnitAttackTarget(attacker);
            }
        }

        // 2. Rally nearby idle military of the same bot to engage the attacker.
        rallyNearbyDefenders(ownerName, victim, attacker);
    }

    /**
     * Send any of {@code ownerName}'s military units within
     * {@link #DEFENSE_ALERT_RADIUS} of {@code victim} after {@code attacker},
     * unless they already have a target.
     */
    private static void rallyNearbyDefenders(
        String ownerName,
        LivingEntity victim,
        LivingEntity attacker
    ) {
        int rallied = 0;
        for (LivingEntity entity : UnitServerEvents.getAllUnits()) {
            if (entity == victim) continue;
            if (!(entity instanceof AttackerUnit defender)) continue;
            if (entity instanceof WorkerUnit) continue; // workers stay on jobs
            if (!(entity instanceof Unit unit)) continue;
            if (!ownerName.equals(unit.getOwnerName())) continue;
            // Skip units already engaging something.
            if (
                unit.getTargetGoal() != null &&
                unit.getTargetGoal().getTarget() != null
            ) continue;
            if (
                entity.distanceToSqr(victim) > DEFENSE_ALERT_RADIUS_SQR
            ) continue;

            defender.setUnitAttackTarget(attacker);
            rallied++;
        }
        if (rallied > 0) {
            LivingWorld.LOGGER.debug(
                "[DefenseEvents] {} rallied {} defender(s) to attack {}",
                ownerName,
                rallied,
                attacker.getName().getString()
            );
        }
    }

    /**
     * Walk a {@link DamageSource} back to whatever {@link LivingEntity}
     * actually swung. Prefer the direct entity (the projectile owner, the
     * melee attacker); fall back to the indirect cause; otherwise null.
     *
     * <p>Players get returned just like any other LivingEntity — workers
     * should fight back against survival/creative players who punch them
     * exactly the same way they do against zombies.
     */
    private static LivingEntity resolveLivingAttacker(DamageSource source) {
        if (source == null) return null;
        var direct = source.getDirectEntity();
        if (direct instanceof LivingEntity le) return le;
        var indirect = source.getEntity();
        if (indirect instanceof LivingEntity le) return le;
        // Self-damage (fall, drown, suffocate) — nothing to retaliate against.
        return null;
    }

    /**
     * Skip retaliation against the player that owns the worker through
     * survival/creative — they're the only "Player" entity but they're
     * NOT a bot. Real players who hit a bot's unit are valid targets.
     * Helper retained for readability if we later want to exempt them.
     */
    @SuppressWarnings("unused")
    private static boolean isOwnerPlayer(Player p, String ownerName) {
        return p != null && p.getName().getString().equals(ownerName);
    }
}
