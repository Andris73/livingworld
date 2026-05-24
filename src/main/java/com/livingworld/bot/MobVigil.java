package com.livingworld.bot;

import com.livingworld.LivingWorld;
import com.solegendary.reignofnether.faction.Faction;
import com.solegendary.reignofnether.unit.UnitServerEvents;
import com.solegendary.reignofnether.unit.interfaces.AttackerUnit;
import com.solegendary.reignofnether.unit.interfaces.Unit;
import javax.annotation.Nullable;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.monster.Monster;
import net.minecraft.world.phys.AABB;

/**
 * Proactive engagement of vanilla hostile mobs by bot units.
 *
 * <p>RoN units already retaliate when hit ({@link DefenseEvents},
 * {@link HostileMobBehavior#onUnitHurt}) but they don't <em>preemptively</em>
 * engage hostile mobs. Two problems with that:
 *
 * <ol>
 *   <li>For workers, the first hit from a vanilla mob is usually fatal \u2014
 *       zombies do 3+ damage and a worker has ~20 HP, so two unblocked
 *       hits and they're dead before retaliation gets a chance.</li>
 *   <li>For <em>military</em>, RoN's {@code EnemySearchBehaviour.NEAREST_ENEMY_UNIT}
 *       only finds RoN {@link Unit} instances, <strong>not</strong> vanilla
 *       {@link Monster} instances. That left a real gap: a single high-HP
 *       vanilla mob (e.g. a zombified piglin that spawned from a
 *       lightning-struck pig) could walk into a village and chain-kill
 *       workers while the village's brutes / vindicators just stood there.</li>
 * </ol>
 *
 * <p>This class closes both gaps. Every brain tick (24/7 \u2014 not
 * night-restricted; previous "NightVigil" naming was a misnomer) we scan
 * every bot-owned {@link AttackerUnit} that isn't already in combat. If a
 * vanilla {@link Monster} is within {@link #VIGIL_RADIUS}, we set it as the
 * unit's attack target via {@link AttackerUnit#setUnitAttackTarget}. The
 * unit walks to the mob, RoN's combat tick handles the fight, and after
 * the kill the target clears and the unit's existing logic (gather goal /
 * patrol / guard post) resumes.
 *
 * <p>Vanilla {@link Monster} subclasses include {@link Monster}-on-paper
 * neutrals like the zombified piglin and enderman: this is intentional.
 * Whether or not vanilla treats them as "neutral by default", we already
 * added {@code LivingWorldTargetUnitsGoal} to every vanilla mob on
 * entity-join (see {@link HostileMobBehavior#onEntityJoin}), which makes
 * them aggro on our units. By extension, our units should aggro back.
 *
 * <p><b>Faction policy:</b> {@link Faction#MONSTERS} villages skip the
 * vigil entirely. Monster-faction units are already invisible to vanilla
 * mob targeting (see {@link HostileMobBehavior.LivingWorldTargetUnitsGoal}'s
 * predicate \u2014 monster-faction units are only attacked by end creatures),
 * and having an undead zombie-villager fight another zombie wandering past
 * would be thematically nonsensical.
 *
 * <p>Tick ordering: runs before {@link WorkerFlight} so the flight pass
 * sees the attack target and skips the worker. Runs before
 * {@link PatrolManager}/{@link NightGuard} so military with a vigil
 * target isn't yanked off to a patrol waypoint.
 */
public final class MobVigil {

    /**
     * Range (blocks) within which a unit will preemptively engage a
     * vanilla hostile mob. 15 is slightly bigger than {@link WorkerFlight}'s
     * {@code FLEE_RADIUS} of 12 \u2014 we want vigil to <em>preempt</em>
     * the flight reflex, not run after it. Same radius applies to both
     * workers and military so the village reacts as a single integrated
     * defence rather than two separate zones.
     */
    public static final int VIGIL_RADIUS = 15;

    private static final double VIGIL_RADIUS_SQR =
        (double) VIGIL_RADIUS * VIGIL_RADIUS;

    private MobVigil() {}

    /**
     * Whether units of this faction should run the mob vigil.
     * Monsters are excluded \u2014 they're left alone by vanilla mobs
     * anyway and engaging them mid-night looks wrong.
     */
    public static boolean shouldVigil(Faction faction) {
        return faction != Faction.MONSTERS;
    }

    /**
     * Scan every bot-owned attacker unit (workers + military) for an
     * approaching vanilla mob and assign an attack target if one is in
     * range. No-op for monster-faction villages.
     */
    public static void tick(ServerLevel level, FactionBot bot) {
        if (!shouldVigil(bot.faction)) return;

        int engaged = 0;
        for (LivingEntity entity : UnitServerEvents.getAllUnits()) {
            if (!(entity instanceof AttackerUnit attacker)) continue;
            if (!(entity instanceof Unit unit)) continue;
            if (!bot.name().equals(unit.getOwnerName())) continue;

            // Already fighting something \u2014 don't yank them off.
            if (
                unit.getTargetGoal() != null &&
                unit.getTargetGoal().getTarget() != null
            ) continue;

            LivingEntity mob = nearestVanillaMonster(entity);
            if (mob == null) continue;

            attacker.setUnitAttackTarget(mob);
            engaged++;
        }

        if (engaged > 0) {
            LivingWorld.LOGGER.debug(
                "[MobVigil] {} engaged {} approaching mob(s)",
                bot.name(),
                engaged
            );
        }
    }

    /**
     * Closest <em>vanilla</em> {@link Monster} (not a RoN {@link Unit})
     * within {@link #VIGIL_RADIUS} of the unit. RoN unit classes
     * transitively extend Monster too, so we filter those out and leave
     * cross-faction engagement to existing combat resolution.
     */
    @Nullable
    private static LivingEntity nearestVanillaMonster(LivingEntity unit) {
        AABB box = unit.getBoundingBox().inflate(VIGIL_RADIUS);
        LivingEntity nearest = null;
        double bestDistSqr = VIGIL_RADIUS_SQR;
        for (LivingEntity other : unit
            .level()
            .getEntitiesOfClass(LivingEntity.class, box)) {
            if (other == unit) continue;
            if (other instanceof Unit) continue; // skip RoN units
            if (!(other instanceof Monster)) continue;
            double d = other.distanceToSqr(unit);
            if (d < bestDistSqr) {
                bestDistSqr = d;
                nearest = other;
            }
        }
        return nearest;
    }
}
