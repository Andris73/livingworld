package com.livingworld.brain;

import com.solegendary.reignofnether.building.Building;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.Rotation;

import javax.annotation.Nullable;

/**
 * A planned-but-not-yet-placed building.
 *
 * Carries the {@link Building} type, an optional target site, and the {@link Need}
 * that triggered it (for telemetry and re-evaluation).
 */
public class BuildProject {

    public final Building building;
    public final Need triggeredBy;

    @Nullable public BlockPos targetSite;
    public Rotation rotation = Rotation.NONE;

    public BuildProject(Building building, Need triggeredBy) {
        this.building = building;
        this.triggeredBy = triggeredBy;
    }
}
