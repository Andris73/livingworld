package com.livingworld.bot;

import com.livingworld.LivingWorld;
import com.livingworld.config.LivingWorldConfig;
import com.livingworld.util.Terrain;
import com.solegendary.reignofnether.faction.Faction;
import com.solegendary.reignofnether.registrars.EntityRegistrar;
import com.solegendary.reignofnether.unit.EnemySearchBehaviour;
import com.solegendary.reignofnether.unit.UnitServerEvents;
import com.solegendary.reignofnether.unit.interfaces.AttackerUnit;
import java.util.Random;
import javax.annotation.Nullable;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.Mob;

/**
 * Spawns a small starter garrison around a newly-created village.
 *
 * <p>Counterpart to {@link WorkerSpawner}: that drops in 10 workers on the
 * close ring; this drops in {@link #GARRISON_COUNT} basic combat units on a
 * wider ring so the village has visible defenders before the brain has
 * teched up to a barracks. Each garrison unit has
 * {@link EnemySearchBehaviour#NEAREST_ENEMY_UNIT} set so it auto-targets
 * hostile entities passing nearby — exactly the behaviour
 * {@link PatrolManager} would otherwise apply once military buildings exist.
 *
 * <p>Faction-appropriate basic combat unit:
 * <ul>
 *   <li>{@code VILLAGERS} → {@code VINDICATOR_UNIT} (axe-wielding melee)</li>
 *   <li>{@code MONSTERS} → {@code ZOMBIE_UNIT} (basic melee shambler)</li>
 *   <li>{@code PIGLINS} → {@code BRUTE_UNIT} (axe-wielding piglin brute)</li>
 * </ul>
 */
public final class SatelliteUnits {

    /**
     * Default garrison count for villager and monster faction villages.
     * Villagers shelter in walled houses, monsters are immune to vanilla
     * hostile-mob targeting — both can get away with a small garrison.
     */
    public static final int DEFAULT_GARRISON_COUNT = 2;

    /**
     * Larger garrison count for piglins. Piglin workers have no walls (their
     * "house" is a portal frame) and don't shelter at night, so the village
     * needs more military presence on the perimeter from minute zero.
     * The brain's {@link com.livingworld.brain.UnitProducer} will keep this
     * topped up once {@code portal_military} is in place (which the same
     * piglin spawn pipeline pre-spawns; see {@link SatelliteStructures}).
     */
    public static final int PIGLIN_GARRISON_COUNT = 4;

    /** Per-faction garrison count. */
    public static int garrisonCountFor(
        com.solegendary.reignofnether.faction.Faction faction
    ) {
        return faction == com.solegendary.reignofnether.faction.Faction.PIGLINS
            ? PIGLIN_GARRISON_COUNT
            : DEFAULT_GARRISON_COUNT;
    }

    /** Ring radius (blocks) for garrison spawning. Wider than the worker ring. */
    public static final double GARRISON_RING_RADIUS = 16.0;

    private static final Random RNG = new Random();

    private SatelliteUnits() {}

    /**
     * Spawn {@link #GARRISON_COUNT} military units around the bot's capitol.
     * No-op if {@link LivingWorldConfig#PRESPAWN_SATELLITES} is disabled or
     * the faction has no basic combat unit defined.
     */
    public static void prespawn(ServerLevel level, FactionBot bot) {
        if (!LivingWorldConfig.PRESPAWN_SATELLITES) return;

        EntityType<? extends Mob> type = combatUnitFor(bot.faction);
        if (type == null) {
            LivingWorld.LOGGER.debug(
                "[SatelliteUnits] No basic combat unit for {} — skipping garrison",
                bot.faction
            );
            return;
        }

        int count = garrisonCountFor(bot.faction);
        int spawned = 0;
        for (int i = 0; i < count; i++) {
            BlockPos pos = ringPosition(level, bot.centrePos, i, count);
            Entity entity = UnitServerEvents.spawnMob(
                type,
                level,
                pos,
                bot.name()
            );
            if (entity == null) {
                LivingWorld.LOGGER.warn(
                    "[SatelliteUnits] Failed to spawn garrison unit {}/{} for {} at {}",
                    i + 1,
                    count,
                    bot.name(),
                    pos
                );
                continue;
            }
            // Auto-target nearby hostile units. PatrolManager will later issue
            // explicit waypoints; until then they hold the village.
            if (entity instanceof AttackerUnit attacker) {
                attacker.setEnemySearchBehaviour(
                    EnemySearchBehaviour.NEAREST_ENEMY_UNIT
                );
            }
            spawned++;
        }

        if (spawned > 0) {
            LivingWorld.LOGGER.info(
                "[SatelliteUnits] Spawned {} garrison unit(s) for {}",
                spawned,
                bot.name()
            );
        }
    }

    /**
     * Faction → basic combat unit. Picked for "low tier, visually unambiguous
     * faction marker, available without research" criteria.
     */
    @Nullable
    private static EntityType<? extends Mob> combatUnitFor(Faction faction) {
        return switch (faction) {
            case VILLAGERS -> EntityRegistrar.VINDICATOR_UNIT.get();
            case MONSTERS -> EntityRegistrar.ZOMBIE_UNIT.get();
            case PIGLINS -> EntityRegistrar.BRUTE_UNIT.get();
            default -> null;
        };
    }

    /**
     * Find a dry-land position at approximately {@link #GARRISON_RING_RADIUS}
     * blocks from {@code centre}, at angle {@code 2π * i / count}. Falls back
     * to {@code centre} (guaranteed dry) if no dry ring position can be found
     * — matches {@link WorkerSpawner}'s policy.
     */
    private static BlockPos ringPosition(
        ServerLevel level,
        BlockPos centre,
        int i,
        int count
    ) {
        double baseAngle = (2.0 * Math.PI * i) / Math.max(1, count);
        for (int attempt = 0; attempt < 8; attempt++) {
            double jitter =
                attempt == 0 ? 0 : (RNG.nextDouble() - 0.5) * (Math.PI / 4);
            double angle = baseAngle + jitter;
            int x =
                centre.getX() +
                (int) Math.round(Math.cos(angle) * GARRISON_RING_RADIUS);
            int z =
                centre.getZ() +
                (int) Math.round(Math.sin(angle) * GARRISON_RING_RADIUS);
            if (Terrain.isLiquidAt(level, x, z)) continue;
            return new BlockPos(x, Terrain.groundY(level, x, z), z);
        }
        return new BlockPos(
            centre.getX(),
            Terrain.groundY(level, centre.getX(), centre.getZ()),
            centre.getZ()
        );
    }
}
