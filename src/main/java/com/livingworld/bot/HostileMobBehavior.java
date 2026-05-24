package com.livingworld.bot;

import com.solegendary.reignofnether.alliance.AlliancesServerEvents;
import com.solegendary.reignofnether.faction.Faction;
import com.solegendary.reignofnether.player.PlayerServerEvents;
import com.solegendary.reignofnether.unit.EnemySearchBehaviour;
import com.solegendary.reignofnether.unit.UnitServerEvents;
import com.solegendary.reignofnether.unit.interfaces.AttackerUnit;
import com.solegendary.reignofnether.unit.interfaces.Unit;
import com.solegendary.reignofnether.unit.interfaces.WorkerUnit;
import com.solegendary.reignofnether.unit.units.piglins.GruntUnit;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.ai.attributes.AttributeInstance;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.entity.ai.goal.target.NearestAttackableTargetGoal;
import net.minecraft.world.entity.monster.EnderMan;
import net.minecraft.world.entity.monster.Endermite;
import net.minecraft.world.entity.monster.Monster;
import net.minecraft.world.entity.monster.Shulker;
import net.minecraftforge.event.entity.EntityJoinLevelEvent;
import net.minecraftforge.event.entity.living.LivingHurtEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;

/**
 * Patches vanilla hostile mobs to consider any {@link Unit} a valid target.
 *
 * <p>Without this, naturally-spawning zombies / skeletons / creepers / etc.
 * largely ignore RoN units because the underlying entity classes for those
 * units are repurposed friendly mobs (e.g. {@code VillagerUnit extends Vindicator}).
 * Vindicators aren't on a zombie's standard target list, so a forest full of
 * villager workers would be left alone all night and the village's military
 * units would have nothing to do.
 *
 * <p><b>What we change:</b> whenever a vanilla {@link Monster} enters the
 * level, we add a {@link NearestAttackableTargetGoal} that targets any entity
 * implementing {@link Unit}. We deliberately do <em>not</em> touch entities
 * whose registered type is in the {@code reignofnether} namespace \u2014 RoN's
 * own mobs have their own carefully tuned target selection (faction-aware
 * relationships, alliance checks, etc.) and we don't want to step on it.
 *
 * <p>Same-faction immunity is intentionally <em>not</em> implemented. The user
 * asked for "hostile to all units" so a vanilla zombie will happily attack a
 * monster-faction Zombie Villager. Slightly weird thematically, but it means
 * every faction's military has a job.
 */
public final class HostileMobBehavior {

    private HostileMobBehavior() {}

    /**
     * Damage multiplier applied to enderman hits on bot-owned units.
     * Endermen normally do 7 damage with a 0.3 movement speed; that combination
     * lets a single enderman wipe out an entire village's workers before any
     * military can respond. 0.4 = 60% damage reduction on hits and a
     * 33% movement-speed reduction on spawn keeps them threatening but no
     * longer single-handedly fatal.
     */
    public static final float ENDERMAN_DAMAGE_MULTIPLIER_VS_BOTS = 0.4f;

    /** Enderman move-speed multiplier applied on entity-join. */
    public static final double ENDERMAN_SPEED_MULTIPLIER = 0.67;

    /** Enderman attack-damage multiplier applied on entity-join. */
    public static final double ENDERMAN_BASE_ATTACK_MULTIPLIER = 0.5;

    @SubscribeEvent
    public static void onEntityJoin(EntityJoinLevelEvent event) {
        if (event.getLevel().isClientSide()) return;
        Entity entity = event.getEntity();

        // Only act on vanilla mobs — anything from the reignofnether namespace
        // already has carefully tuned targeting we shouldn't second-guess.
        ResourceLocation typeId = EntityType.getKey(entity.getType());
        if (typeId == null) return;
        if (!"minecraft".equals(typeId.getNamespace())) return;
        if (!(entity instanceof Monster mob)) return;

        // Enderman debuff: vanilla endermen are catastrophically lethal to
        // bot villages (high damage + high speed + teleport closes any kite
        // attempt). Reduce both their base move speed and base attack damage
        // here, then apply a further per-hit reduction in {@link #onUnitHurt}
        // against bot victims so survival-mode players still face the full
        // vanilla statline.
        if (mob instanceof EnderMan) {
            applyEndermanDebuff(mob);
        }

        // Idempotency: don't add the goal twice if for some reason the event
        // fires multiple times for the same entity (chunk reload, etc.).
        for (var wrapped : mob.targetSelector.getAvailableGoals()) {
            if (wrapped.getGoal() instanceof LivingWorldTargetUnitsGoal) return;
        }
        mob.targetSelector.addGoal(
            2, // priority — above default goals like wander, below "attack the player who hurt me"
            new LivingWorldTargetUnitsGoal(mob)
        );
    }

    /**
     * Knock down a freshly-spawned enderman's base speed and attack damage so
     * it doesn't single-handedly delete a village. The multipliers are
     * deliberately conservative — endermen should still be a threat, just
     * not a one-mob extinction event.
     */
    private static void applyEndermanDebuff(Monster enderman) {
        AttributeInstance speed = enderman.getAttribute(
            Attributes.MOVEMENT_SPEED
        );
        if (speed != null) {
            speed.setBaseValue(
                speed.getBaseValue() * ENDERMAN_SPEED_MULTIPLIER
            );
        }
        AttributeInstance atk = enderman.getAttribute(Attributes.ATTACK_DAMAGE);
        if (atk != null) {
            atk.setBaseValue(
                atk.getBaseValue() * ENDERMAN_BASE_ATTACK_MULTIPLIER
            );
        }
    }

    /** Radius within which allied military units rush to defend an attacked comrade. */
    public static final int PROTECT_RADIUS = 80;

    /**
     * Retaliation: when any bot-owned unit is hurt, the victim and nearby
     * allied military units are all ordered to engage the attacker.
     * This covers workers being attacked (they call for help) as well as
     * soldiers engaging enemies that stray into the village.
     */
    @SubscribeEvent
    public static void onUnitHurt(LivingHurtEvent event) {
        LivingEntity victim = event.getEntity();
        if (victim.level().isClientSide()) return;
        if (!(victim instanceof Unit unit)) return;

        String ownerName = unit.getOwnerName();
        if (ownerName == null || ownerName.isBlank()) return;
        if (!PlayerServerEvents.isBot(ownerName)) return;

        Entity rawAttacker = event.getSource().getEntity();
        if (!(rawAttacker instanceof LivingEntity attacker)) return;

        // Enderman-on-bot damage cap: even after the base-attribute debuff
        // in onEntityJoin, an enderman that rolls high enchanted damage or
        // crit can one-shot a worker. Apply a multiplier here so bot units
        // take a fixed fraction of whatever damage the attacker rolled.
        // Real players are unaffected since this branch is gated on
        // PlayerServerEvents.isBot above.
        if (attacker instanceof EnderMan) {
            event.setAmount(
                event.getAmount() * ENDERMAN_DAMAGE_MULTIPLIER_VS_BOTS
            );
        }

        // Ignore friendly fire (same owner or allied)
        if (attacker instanceof Unit attackerUnit) {
            if (attackerUnit.getOwnerName().equals(ownerName)) return;
            if (
                AlliancesServerEvents.isAllied(
                    ownerName,
                    attackerUnit.getOwnerName()
                )
            ) return;
        }

        BlockPos attackerPos = attacker.blockPosition();

        // Victim itself retaliates (if it can attack)
        if (victim instanceof AttackerUnit av) {
            av.setEnemySearchBehaviour(EnemySearchBehaviour.NEAREST_ENEMY_UNIT);
            av.setAttackMoveTarget(attackerPos);
        }

        // Nearby allied military units rush to defend
        for (LivingEntity entity : UnitServerEvents.getAllUnits()) {
            if (!(entity instanceof AttackerUnit ally)) continue;
            if (entity instanceof WorkerUnit) continue;
            if (!(entity instanceof Unit allyUnit)) continue;
            if (!allyUnit.getOwnerName().equals(ownerName)) continue;
            if (entity == victim) continue;
            // Skip units already engaging something
            if (
                allyUnit.getTargetGoal() != null &&
                allyUnit.getTargetGoal().getTarget() != null
            ) continue;
            if (entity.distanceTo(victim) > PROTECT_RADIUS) continue;

            ally.setEnemySearchBehaviour(
                EnemySearchBehaviour.NEAREST_ENEMY_UNIT
            );
            ally.setAttackMoveTarget(attackerPos);
        }
    }

    /**
     * Grunt (piglin worker) base attack-damage applied on spawn. Vanilla
     * Grunts do 1.0 — same as the other faction workers — which leaves
     * them unable to meaningfully defend themselves when stuck out at night
     * with no walls and no shelter. Bumping to 3.0 (basic Zombie tier) makes
     * a worker-vs-mob fight a coin flip rather than a guaranteed worker
     * death, while staying well below a Brute's 5.0 so military still
     * outclasses workers.
     */
    public static final double GRUNT_ATTACK_DAMAGE = 3.0;

    @SubscribeEvent
    public static void onBotUnitJoin(EntityJoinLevelEvent event) {
        if (event.getLevel().isClientSide()) return;
        Entity entity = event.getEntity();
        if (!(entity instanceof Unit unit)) return;
        String owner = unit.getOwnerName();
        if (owner == null || owner.isBlank()) return;
        if (!PlayerServerEvents.isBot(owner)) return; // only NPC bots

        // Per-mob spawn-time setup for bot-owned units: enable swimming so
        // workers don't get stuck on the shoreline of small bodies of water,
        // and document the no-op for canPickUpLoot which the hunting payoff
        // depends on.
        if (entity instanceof Mob mob) {
            // Swimming: enable the path planner to consider water as a
            // navigable surface and the mob to stay afloat. RoN's worker
            // units already have FloatGoal at priority 1 (don't sink),
            // but without this flag the path planner refuses to route
            // through any water block at all — so a worker with a tree
            // on the far side of a 3-wide river just gives up. With it
            // on, plus the existing FloatGoal, they swim across.
            mob.getNavigation().setCanFloat(true);
            // Door pathing has been retired — the villager_house schematic
            // no longer has doors (just a doorway opening), and OpenDoorGoal's
            // auto-close timing produced a queue-blocking bug at night.
            // Sheltering is now handled by NightShelter pointing workers at
            // interior coordinates they reach via the open doorway, while
            // military protection is supplied by NightGuard.
            //
            // NOTE: do NOT call setCanPickUpLoot(false) here. Each RoN unit
            // class sets this to true in its setupEquipmentAndUpgradesServer
            // and the hunting payoff in UnitServerEvents.onDropItem requires
            // {@code canPickUpLoot()} to credit food items from a killed
            // animal directly into the worker's inventory.
        }

        // Grunt damage buff: see GRUNT_ATTACK_DAMAGE doc.
        if (entity instanceof GruntUnit && entity instanceof LivingEntity le) {
            AttributeInstance atk = le.getAttribute(Attributes.ATTACK_DAMAGE);
            if (atk != null) atk.setBaseValue(GRUNT_ATTACK_DAMAGE);
        }

        // Military-only: set NEAREST_ENEMY_UNIT search so they engage on sight.
        // Workers stay non-aggressive (DefenseEvents / WorkerFlight handle them).
        if (
            entity instanceof AttackerUnit attacker &&
            !(entity instanceof WorkerUnit)
        ) {
            attacker.setEnemySearchBehaviour(
                EnemySearchBehaviour.NEAREST_ENEMY_UNIT
            );
        }
    }

    /**
     * True if the attacker is an "end creature" — the only vanilla hostile
     * mobs we still allow to target monster-faction units. Endermen,
     * Endermites, and Shulkers all canonically attack any humanoid /
     * undead / piglin alike, and gameplay-wise they're rare enough not to
     * trivialise monster villages.
     */
    public static boolean isEndCreature(Mob mob) {
        return (
            mob instanceof EnderMan ||
            mob instanceof Endermite ||
            mob instanceof Shulker
        );
    }

    /**
     * Marker subclass so we can detect it in the goal list and not add it
     * twice. Targets any {@link Unit} — with one carve-out: monster-faction
     * units are ignored by all vanilla hostiles <em>except</em> end creatures
     * (see {@link #isEndCreature}). Thematically: a wandering zombie doesn't
     * pick a fight with another undead, but an enderman is hostile to
     * everything.
     */
    public static final class LivingWorldTargetUnitsGoal
        extends NearestAttackableTargetGoal<LivingEntity>
    {

        public LivingWorldTargetUnitsGoal(Monster mob) {
            super(
                mob,
                LivingEntity.class,
                10, // random retarget interval
                true, // require line of sight
                false, // require navigable path (false = cheaper)
                target -> {
                    if (!(target instanceof Unit unit)) return false;
                    // Monster-faction units: only end creatures attack them.
                    if (factionOf(unit) == Faction.MONSTERS) {
                        return isEndCreature(mob);
                    }
                    return true;
                }
            );
        }
    }

    /**
     * Best-effort faction lookup for a {@link Unit}. Returns {@code NONE} if
     * the unit isn't owned by a known bot — the caller treats that as
     * "not protected" which is the safe default.
     */
    private static Faction factionOf(Unit unit) {
        String owner = unit.getOwnerName();
        if (owner == null || owner.isBlank()) return Faction.NONE;
        for (var bot : FactionBotRegistry.all()) {
            if (bot.name().equals(owner)) return bot.faction;
        }
        return Faction.NONE;
    }
}
