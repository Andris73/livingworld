package com.livingworld.bot;

import com.livingworld.LivingWorld;
import com.livingworld.beacon.BeaconOfOrigins;
import com.solegendary.reignofnether.alliance.AlliancesServerEvents;
import com.solegendary.reignofnether.building.BuildingPlacement;
import com.solegendary.reignofnether.unit.EnemySearchBehaviour;
import com.solegendary.reignofnether.unit.UnitServerEvents;
import com.solegendary.reignofnether.unit.interfaces.AttackerUnit;
import com.solegendary.reignofnether.unit.interfaces.Unit;
import com.solegendary.reignofnether.unit.interfaces.WorkerUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.LivingEntity;

/**
 * Dispatches bot-village military toward the Beacon of Origins so NPC
 * factions are genuinely competing for the central capture objective \u2014
 * not just standing in their own backyards while the player upgrades it
 * unopposed.
 *
 * <p>RoN's {@link com.solegendary.reignofnether.building.BuildingPlacement#checkIfCaptured}
 * already works for any {@code Unit} owner (including bot RTSPlayers), so
 * the missing piece was simply <em>getting bot units to the beacon</em>.
 * {@link PatrolManager}'s scout radius keeps military within 150 blocks of
 * the capitol, which for villages spawned further than 150 blocks from
 * world origin means they never reach the beacon.
 *
 * <p>BeaconRaid runs once per brain tick (\u22485 s) and, with a small
 * probability per tick, sends <em>one</em> spare military unit on an
 * attack-move toward the beacon's centre position. The slow drip is
 * deliberate: it prevents any single village from draining its garrison
 * in one go, and over a long match every village pours soldiers toward
 * world origin, creating an emergent free-for-all around the capture
 * point exactly as the player gets there to fight.
 *
 * <p>Constraints (cheap early-outs at the top of {@link #tick}):
 * <ul>
 *   <li>Skip if the beacon isn't spawned or owned by this bot already.</li>
 *   <li>Skip if the bot has fewer than {@link #MIN_SPARE_MILITARY} idle
 *       military, so we don't strip defenders from a tiny village.</li>
 *   <li>Roll {@link #DISPATCH_CHANCE_PER_TICK} \u2014 only act on a small
 *       fraction of ticks so dispatches are spread out over real time.</li>
 *   <li>Skip if the beacon is already owned by an ally so we don't fight
 *       our friends.</li>
 * </ul>
 */
public final class BeaconRaid {

    /**
     * Minimum spare (non-engaged, non-attacking, non-moving) military for
     * the bot before we siphon one off to march on the beacon. Keeps weak
     * villages from being stripped of defenders.
     */
    public static final int MIN_SPARE_MILITARY = 4;

    /**
     * Probability per brain tick (\u22485 s) that a qualifying village will
     * dispatch one unit to the beacon. 0.05 = 5% per 5 s = on average one
     * dispatch per 100 s. Slow enough that there's a real arc of "the bots
     * are sending people"; fast enough that across many villages the beacon
     * sees fresh threats regularly.
     */
    public static final double DISPATCH_CHANCE_PER_TICK = 0.05;

    private static final Random RNG = new Random();

    private BeaconRaid() {}

    public static void tick(ServerLevel level, FactionBot bot) {
        if (!BeaconOfOrigins.isSpawned()) return;
        BuildingPlacement beacon = BeaconOfOrigins.getPlacement();
        if (beacon == null) return;

        // Already ours, or owned by an ally we won't pick a fight with.
        String beaconOwner = beacon.ownerName;
        if (beaconOwner.equals(bot.name())) return;
        if (
            !beaconOwner.isEmpty() &&
            AlliancesServerEvents.isAllied(bot.name(), beaconOwner)
        ) return;

        // Probabilistic dispatch.
        if (RNG.nextDouble() > DISPATCH_CHANCE_PER_TICK) return;

        List<LivingEntity> spare = spareMilitary(bot.name());
        if (spare.size() < MIN_SPARE_MILITARY) return;

        // Pick one at random so successive ticks don't always grab the same
        // unit (which would be in transit and no longer "spare" anyway).
        LivingEntity entity = spare.get(RNG.nextInt(spare.size()));
        if (!(entity instanceof AttackerUnit attacker)) return;

        BlockPos beaconCentre = beacon.centrePos;
        // Make sure they fight things they encounter en route. PatrolManager
        // already sets this on spawn but it can be cleared by resetBehaviours.
        attacker.setEnemySearchBehaviour(EnemySearchBehaviour.NEAREST_ENEMY_UNIT);
        attacker.setAttackMoveTarget(beaconCentre);

        LivingWorld.LOGGER.info(
            "[BeaconRaid] {} dispatched 1 unit toward the beacon at {} (beacon owner: {})",
            bot.name(),
            beaconCentre.toShortString(),
            beaconOwner.isEmpty() ? "unclaimed" : beaconOwner
        );
    }

    /**
     * Bot-owned military units that are eligible for redirection: an
     * {@link AttackerUnit} that isn't a worker, isn't currently in combat,
     * and isn't already heading somewhere via attack-move (so they're
     * either standing at a patrol waypoint or fresh out of production).
     */
    private static List<LivingEntity> spareMilitary(String ownerName) {
        List<LivingEntity> result = new ArrayList<>();
        for (LivingEntity entity : UnitServerEvents.getAllUnits()) {
            if (!(entity instanceof AttackerUnit attacker)) continue;
            if (entity instanceof WorkerUnit) continue;
            if (!(entity instanceof Unit unit)) continue;
            if (!ownerName.equals(unit.getOwnerName())) continue;
            if (
                unit.getTargetGoal() != null &&
                unit.getTargetGoal().getTarget() != null
            ) continue;
            if (attacker.getAttackMoveTarget() != null) continue;
            result.add(entity);
        }
        return result;
    }
}
