package com.livingworld.bot;

import com.livingworld.LivingWorld;
import com.livingworld.util.Terrain;
import com.solegendary.reignofnether.building.BuildingPlacement;
import com.solegendary.reignofnether.building.BuildingServerEvents;
import com.solegendary.reignofnether.faction.Faction;
import com.solegendary.reignofnether.unit.EnemySearchBehaviour;
import com.solegendary.reignofnether.unit.UnitServerEvents;
import com.solegendary.reignofnether.unit.interfaces.AttackerUnit;
import com.solegendary.reignofnether.unit.interfaces.Unit;
import com.solegendary.reignofnether.unit.interfaces.WorkerUnit;
import java.util.ArrayList;
import java.util.List;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.LivingEntity;

/**
 * Replaces {@link PatrolManager} at night for villager villages: instead of
 * sending military scouts on a {@code SCOUT_RADIUS}-wide foray, military
 * units cluster around the village's habitable buildings to guard the
 * workers sheltering inside.
 *
 * <p>Mutually exclusive with {@link PatrolManager} \u2014 {@link FactionBrain}
 * branches on {@code level.isNight() && shouldGuard(faction)} to pick one or
 * the other for a given tick. Same-faction policy as {@link NightShelter}:
 * only villager villages run the guard rotation. Monsters are immune to
 * vanilla hostile-mob targeting and don't need it; piglins are aggressively
 * militarised already and their houses (portal-frame) have no interior to
 * protect.
 *
 * <p>The guard rotation is intentionally <em>sticky</em>: each idle military
 * unit (no combat target, no active attack-move target) gets stationed in a
 * ring around one of the houses. Once at the guard post, the unit's
 * {@code attackMoveTarget} stays set until the unit engages something (RoN
 * clears it on combat), so it doesn't get re-stationed every brain tick.
 * When dawn comes, {@code NightGuard.tick} early-returns and the next
 * {@code PatrolManager} tick rotates idle guards back out on scouting forays.
 */
public final class NightGuard {

    /**
     * Ring radius (blocks) where guards stand around a house's centre.
     * 4 is "just outside the wall, within combat range of anything trying
     * to break in" \u2014 close enough that mobs spawning adjacent to the
     * house get engaged immediately.
     */
    public static final int GUARD_RING_RADIUS = 4;

    private NightGuard() {}

    /**
     * Faction policy: only the {@link Faction#VILLAGERS} villages need a
     * night guard. Same factional carve-out as {@link NightShelter}.
     */
    public static boolean shouldGuard(Faction faction) {
        return faction == Faction.VILLAGERS;
    }

    /**
     * Station idle military units around the village's houses. Cheap:
     * early-returns if it's not night or if there are no houses to guard,
     * and only touches military units whose {@code attackMoveTarget} is
     * already {@code null} (otherwise they're either fighting or already
     * heading to a guard post).
     */
    public static void tick(ServerLevel level, FactionBot bot) {
        if (!shouldGuard(bot.faction)) return;
        if (!level.isNight()) return;

        List<BuildingPlacement> houses = habitableHouses(bot);
        if (houses.isEmpty()) return;

        List<LivingEntity> idleMilitary = idleMilitary(bot.name());
        if (idleMilitary.isEmpty()) return;

        int dispatched = 0;
        for (int i = 0; i < idleMilitary.size(); i++) {
            LivingEntity entity = idleMilitary.get(i);
            if (!(entity instanceof AttackerUnit attacker)) continue;
            BuildingPlacement house = houses.get(i % houses.size());
            BlockPos guardPos = guardPostFor(level, house, i);
            // Make sure the search behaviour is set in case something else
            // cleared it (resetBehaviours, etc.) \u2014 otherwise the unit
            // would arrive at the post and stop reacting to threats.
            attacker.setEnemySearchBehaviour(
                EnemySearchBehaviour.NEAREST_ENEMY_UNIT
            );
            attacker.setAttackMoveTarget(guardPos);
            dispatched++;
        }
        if (dispatched > 0) {
            LivingWorld.LOGGER.debug(
                "[NightGuard] {} stationed {} guard(s) across {} house(s)",
                bot.name(),
                dispatched,
                houses.size()
            );
        }
    }

    /**
     * Habitable buildings owned by this bot. Reuses
     * {@link HouseFurnisher#isHabitable} so the "what's a house" definition
     * stays in one place.
     */
    private static List<BuildingPlacement> habitableHouses(FactionBot bot) {
        List<BuildingPlacement> result = new ArrayList<>();
        for (BuildingPlacement bp : BuildingServerEvents.getBuildings()) {
            if (!bp.ownerName.equals(bot.name())) continue;
            if (!bp.isBuilt) continue;
            if (!HouseFurnisher.isHabitable(bp)) continue;
            result.add(bp);
        }
        return result;
    }

    /**
     * Military units owned by {@code ownerName} that are eligible for a new
     * guard assignment: AttackerUnits that aren't workers, not currently
     * engaging a target, not currently moving to an existing waypoint.
     */
    private static List<LivingEntity> idleMilitary(String ownerName) {
        List<LivingEntity> result = new ArrayList<>();
        for (LivingEntity entity : UnitServerEvents.getAllUnits()) {
            if (!(entity instanceof AttackerUnit attacker)) continue;
            if (entity instanceof WorkerUnit) continue;
            if (!(entity instanceof Unit unit)) continue;
            if (!unit.getOwnerName().equals(ownerName)) continue;
            // Already in combat \u2014 leave them alone.
            if (
                unit.getTargetGoal() != null &&
                unit.getTargetGoal().getTarget() != null
            ) continue;
            // Already heading somewhere (a previous guard post, or a
            // patrol-issued waypoint that hasn't been reached).
            if (attacker.getAttackMoveTarget() != null) continue;
            result.add(entity);
        }
        return result;
    }

    /**
     * Compute a guard post for the given house at a deterministic angle
     * derived from the assignment index, so multiple guards around the
     * same house spread out instead of overlapping. Projected to ground
     * via {@link Terrain#groundY}; the centre Y of a small house is
     * roughly at floor level which puts the guard at the house's threshold.
     */
    private static BlockPos guardPostFor(
        ServerLevel level,
        BuildingPlacement house,
        int seed
    ) {
        // Golden angle \u2248 2.39996 rad gives a good even spread when
        // multiple guards are assigned to the same house.
        double angle = seed * 2.39996;
        int x = house.centrePos.getX() +
            (int) Math.round(Math.cos(angle) * GUARD_RING_RADIUS);
        int z = house.centrePos.getZ() +
            (int) Math.round(Math.sin(angle) * GUARD_RING_RADIUS);
        int y = Terrain.groundY(level, x, z);
        return new BlockPos(x, y, z);
    }
}
