package com.livingworld.bot;

import com.livingworld.brain.FactionBrain;
import com.solegendary.reignofnether.building.BuildingPlacement;
import com.solegendary.reignofnether.faction.Faction;
import com.solegendary.reignofnether.player.RTSPlayer;
import java.util.Collections;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import javax.annotation.Nullable;
import net.minecraft.core.BlockPos;

/**
 * A single autonomous NPC village.
 *
 * Wraps the underlying {@link RTSPlayer} that owns the village's buildings and
 * units, plus a {@link FactionBrain} that decides what to build next.
 *
 * One {@code FactionBot} corresponds to one settlement on the map. Many bots
 * may share the same {@link Faction}.
 */
public class FactionBot {

    public final RTSPlayer rtsPlayer;
    public final Faction faction;
    public final BlockPos centrePos;
    public final FactionBrain brain;

    @Nullable
    public BuildingPlacement capitol;

    /**
     * Players whose reputation with this faction is at HOSTILE tier.
     * {@link com.livingworld.bot.PatrolManager} uses this set to occasionally
     * dispatch patrols toward known hostile players rather than random directions.
     */
    public final Set<String> hostilePlayers = Collections.newSetFromMap(
        new ConcurrentHashMap<>()
    );

    public FactionBot(
        RTSPlayer rtsPlayer,
        Faction faction,
        BlockPos centrePos
    ) {
        this.rtsPlayer = rtsPlayer;
        this.faction = faction;
        this.centrePos = centrePos;
        this.brain = new FactionBrain(this);
    }

    public String name() {
        return rtsPlayer.name;
    }
}
