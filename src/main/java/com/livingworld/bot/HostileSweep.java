package com.livingworld.bot;

import com.livingworld.LivingWorld;
import com.solegendary.reignofnether.unit.UnitServerEvents;
import com.solegendary.reignofnether.unit.interfaces.AttackerUnit;
import com.solegendary.reignofnether.unit.interfaces.Unit;
import javax.annotation.Nullable;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.LivingEntity;

/**
 * "Denounced" players (at HOSTILE tier with a faction) get attacked by every
 * unit of an offended village as soon as they cross into the village's
 * awareness radius — not just the patrol scouts. Workers drop their gather
 * job and pick up swords, military with no current target reroute to the
 * intruder, and the result is the village functioning as a coherent threat
 * to a hated player rather than a passive collection of jobs.
 *
 * <p>Called from {@link com.livingworld.brain.FactionBrain#tick}, once per
 * brain tick (≈5 s). Cheap: we only do anything if {@link FactionBot#hostilePlayers}
 * is non-empty and at least one of them is within {@link #VILLAGE_AWARENESS_RADIUS}
 * of the capitol.
 *
 * <p>Once a worker's target is set, RoN's AttackerUnit tick handles the chase
 * and the {@code GatherResourcesGoal} naturally yields while the unit is
 * attacking. When the hostile player dies or leaves the area the worker
 * returns to idle and {@link WorkerNudger} / {@link WorkerAssignment} put
 * them back on a resource job.
 */
public final class HostileSweep {

    /**
     * If a hostile player is closer than this many blocks to the capitol,
     * the whole village goes after them. 50 blocks is "you're in the village,
     * not just passing by on the horizon" — comfortably outside the capitol
     * footprint but inside what any villager could reasonably see / shout
     * about.
     */
    public static final int VILLAGE_AWARENESS_RADIUS = 50;

    private static final double VILLAGE_AWARENESS_RADIUS_SQR =
        (double) VILLAGE_AWARENESS_RADIUS * VILLAGE_AWARENESS_RADIUS;

    private HostileSweep() {}

    /**
     * If any of {@code bot.hostilePlayers} is currently within
     * {@link #VILLAGE_AWARENESS_RADIUS} of the capitol, retarget every one of
     * the bot's combat-capable units (workers <em>and</em> military) at them.
     */
    public static void tick(ServerLevel level, FactionBot bot) {
        if (bot.hostilePlayers.isEmpty()) return;

        ServerPlayer intruder = nearestHostileIntruder(level, bot);
        if (intruder == null) return;

        String ownerName = bot.name();
        int retargeted = 0;
        for (LivingEntity entity : UnitServerEvents.getAllUnits()) {
            if (!(entity instanceof Unit unit)) continue;
            if (!ownerName.equals(unit.getOwnerName())) continue;
            if (!(entity instanceof AttackerUnit attacker)) continue;

            // Skip units already locked on to this exact player.
            LivingEntity existing = unit.getTargetGoal() != null
                ? unit.getTargetGoal().getTarget()
                : null;
            if (existing == intruder) continue;

            // Respect a *forced* target (e.g. one the brain explicitly assigned
            // to something else) — but only if it's still alive and reachable.
            // Otherwise override; a hostile player in the village is higher
            // priority than chasing a stray zombie 80 blocks out.
            if (existing != null && unit.getTargetGoal().forced && existing.isAlive()) {
                continue;
            }

            attacker.setUnitAttackTarget(intruder);
            retargeted++;
        }

        if (retargeted > 0) {
            LivingWorld.LOGGER.info(
                "[HostileSweep] {} sent {} unit(s) after denounced player {}",
                ownerName,
                retargeted,
                intruder.getName().getString()
            );
        }
    }

    /**
     * Closest online player whose name is in {@code bot.hostilePlayers} AND
     * who is inside the village awareness radius. Returns {@code null} if no
     * such player exists right now.
     */
    @Nullable
    private static ServerPlayer nearestHostileIntruder(
        ServerLevel level,
        FactionBot bot
    ) {
        BlockPos centre = bot.centrePos;
        ServerPlayer nearest = null;
        double bestDistSqr = VILLAGE_AWARENESS_RADIUS_SQR;
        for (ServerPlayer player : level
            .getServer()
            .getPlayerList()
            .getPlayers()) {
            if (
                !bot.hostilePlayers.contains(player.getName().getString())
            ) continue;
            double d = player.blockPosition().distSqr(centre);
            if (d <= bestDistSqr) {
                bestDistSqr = d;
                nearest = player;
            }
        }
        return nearest;
    }
}
