package com.livingworld.bot;

import com.livingworld.LivingWorld;
import com.solegendary.reignofnether.unit.EnemySearchBehaviour;
import com.solegendary.reignofnether.unit.UnitServerEvents;
import com.solegendary.reignofnether.unit.interfaces.AttackerUnit;
import com.solegendary.reignofnether.unit.interfaces.Unit;
import com.solegendary.reignofnether.unit.interfaces.WorkerUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import javax.annotation.Nullable;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.level.levelgen.Heightmap;

/**
 * Manages patrol behaviour for a village's military units.
 *
 * <p>Inter-village conflict happens automatically once units are within range
 * of each other — RoN's {@link AttackerUnit} tick will call
 * {@link AttackerUnit#attackMoveNearestEnemyUnit} when the unit's
 * {@code enemySearchBehaviour} is set to {@code NEAREST_ENEMY_UNIT} and it
 * has no current target. Units from different bots are already HOSTILE to each
 * other (different ownerNames, no alliance), so no extra relationship code is
 * needed.
 *
 * <p>The challenge is distance: villages spawn 300–500+ blocks apart, so
 * units that just stand at the capitol will never find each other.
 * {@code PatrolManager} solves this by giving idle military units two kinds of
 * movement orders:
 *
 * <ul>
 *   <li><b>Local defence ring</b> (radius {@link #DEFENCE_RADIUS}): a tight
 *       patrol around the capitol. Keeps a portion of the garrison close to
 *       home so the village isn't completely undefended while others roam.</li>
 *   <li><b>Scouting foray</b> (radius {@link #SCOUT_RADIUS}): a longer-range
 *       push in a random direction. Over time scouts will encounter other
 *       villages naturally, triggering combat without us hard-coding any
 *       attack targets.</li>
 * </ul>
 *
 * <p>Units continue independently once given their waypoint \u2014 we set
 * {@code NEAREST_ENEMY_UNIT} search behaviour so they attack anything hostile
 * they see en route or at the patrol point, then return to idle for the next
 * order.
 */
public final class PatrolManager {

    /** Radius of the local defence ring around the capitol. */
    public static final int DEFENCE_RADIUS = 40;

    /** Radius of scouting forays. */
    public static final int SCOUT_RADIUS = 150;

    /** Fraction of idle military dispatched on local defence vs scouting. */
    public static final float SCOUT_FRACTION = 0.4f;

    /** Brain ticks between patrol sweeps. At 5s per tick, 24 = 2 minutes. */
    public static final int PATROL_INTERVAL_TICKS = 24;

    private static final Random RNG = new Random();

    private PatrolManager() {}

    /**
     * Issue patrol orders to idle military units owned by {@code ownerName}.
     *
     * <p>When the bot has known hostile players ({@link FactionBot#hostilePlayers}),
     * scouts are directed toward the nearest one rather than a random direction.
     * This makes villages that the player has wronged actively hunt them.
     */
    public static void tick(
        ServerLevel level,
        FactionBot bot,
        BlockPos capitolCentre
    ) {
        String ownerName = bot.name();
        List<LivingEntity> idleMilitary = getIdleMilitary(ownerName);
        if (idleMilitary.isEmpty()) return;

        // Find the nearest online hostile player (if any)
        BlockPos hostileTarget = nearestHostilePlayer(
            level,
            bot,
            capitolCentre
        );

        int scouts = Math.max(1, (int) (idleMilitary.size() * SCOUT_FRACTION));

        int dispatched = 0;
        for (int i = 0; i < idleMilitary.size(); i++) {
            LivingEntity entity = idleMilitary.get(i);
            if (!(entity instanceof AttackerUnit attacker)) continue;

            boolean isScouting = i < scouts;
            BlockPos waypoint;
            if (isScouting && hostileTarget != null) {
                // Send scouts toward the hostile player
                waypoint = hostileTarget;
            } else if (isScouting) {
                waypoint = randomWaypoint(level, capitolCentre, SCOUT_RADIUS);
            } else {
                waypoint = randomWaypoint(level, capitolCentre, DEFENCE_RADIUS);
            }

            if (waypoint == null) continue;

            attacker.setEnemySearchBehaviour(
                EnemySearchBehaviour.NEAREST_ENEMY_UNIT
            );
            attacker.setAttackMoveTarget(waypoint);
            dispatched++;
        }

        if (dispatched > 0) {
            LivingWorld.LOGGER.debug(
                "[PatrolManager] {} dispatched {} unit(s) on patrol{}",
                ownerName,
                dispatched,
                hostileTarget != null ? " (hunting hostile player)" : ""
            );
        }
    }

    /**
     * Returns the block position of the nearest online player in
     * {@code bot.hostilePlayers}, or {@code null} if none are online.
     */
    @Nullable
    private static BlockPos nearestHostilePlayer(
        ServerLevel level,
        FactionBot bot,
        BlockPos capitolCentre
    ) {
        if (bot.hostilePlayers.isEmpty()) return null;
        BlockPos nearest = null;
        double bestDistSqr = Double.MAX_VALUE;
        for (ServerPlayer player : level
            .getServer()
            .getPlayerList()
            .getPlayers()) {
            if (
                !bot.hostilePlayers.contains(player.getName().getString())
            ) continue;
            double d = player.blockPosition().distSqr(capitolCentre);
            if (d < bestDistSqr) {
                bestDistSqr = d;
                nearest = player.blockPosition();
            }
        }
        return nearest;
    }

    /** Returns all military (non-worker) AttackerUnits owned by this bot with no current target. */
    private static List<LivingEntity> getIdleMilitary(String ownerName) {
        List<LivingEntity> result = new ArrayList<>();
        for (LivingEntity entity : UnitServerEvents.getAllUnits()) {
            if (!(entity instanceof AttackerUnit attacker)) continue;
            if (entity instanceof WorkerUnit) continue;
            if (!(entity instanceof Unit unit)) continue;
            if (!unit.getOwnerName().equals(ownerName)) continue;
            // Skip units already engaging something
            if (
                unit.getTargetGoal() != null &&
                unit.getTargetGoal().getTarget() != null
            ) continue;
            if (attacker.getAttackMoveTarget() != null) continue;
            result.add(entity);
        }
        return result;
    }

    /** Pick a random ground-level waypoint at approximately the given radius. */
    private static BlockPos randomWaypoint(
        ServerLevel level,
        BlockPos centre,
        int radius
    ) {
        for (int attempt = 0; attempt < 8; attempt++) {
            double angle = RNG.nextDouble() * 2.0 * Math.PI;
            int jitter = (int) (radius * 0.3);
            int r = radius - jitter + RNG.nextInt(jitter * 2 + 1);
            int x = centre.getX() + (int) Math.round(Math.cos(angle) * r);
            int z = centre.getZ() + (int) Math.round(Math.sin(angle) * r);
            int y = level.getHeight(Heightmap.Types.WORLD_SURFACE_WG, x, z) - 1;
            return new BlockPos(x, y, z);
        }
        return null;
    }
}
