package com.livingworld.beacon;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.saveddata.SavedData;

/**
 * Persistent record of every capture event on the Beacon of Origins.
 * Stored as {@link SavedData} so it survives restarts.
 */
public class BeaconLeaderboard extends SavedData {

    public static final String DATA_ID = "livingworld_beacon_leaderboard";

    public record CaptureEvent(
        long gameTick,
        String capturedBy,
        String previousOwner
    ) {}

    private final List<CaptureEvent> events = new ArrayList<>();

    public List<CaptureEvent> events() {
        return Collections.unmodifiableList(events);
    }

    public void record(long gameTick, String capturedBy, String previousOwner) {
        events.add(new CaptureEvent(gameTick, capturedBy, previousOwner));
        setDirty();
    }

    public int totalCaptures() {
        return events.size();
    }

    // ---------------------------------------------------------- accessor

    public static BeaconLeaderboard get(ServerLevel level) {
        return level
            .getDataStorage()
            .computeIfAbsent(
                BeaconLeaderboard::load,
                BeaconLeaderboard::new,
                DATA_ID
            );
    }

    // -------------------------------------------------------- persistence

    @Override
    public CompoundTag save(CompoundTag tag) {
        ListTag list = new ListTag();
        for (CaptureEvent e : events) {
            CompoundTag entry = new CompoundTag();
            entry.putLong("tick", e.gameTick());
            entry.putString("by", e.capturedBy());
            entry.putString("prev", e.previousOwner());
            list.add(entry);
        }
        tag.put("events", list);
        return tag;
    }

    public static BeaconLeaderboard load(CompoundTag tag) {
        BeaconLeaderboard lb = new BeaconLeaderboard();
        ListTag list = tag.getList("events", Tag.TAG_COMPOUND);
        for (int i = 0; i < list.size(); i++) {
            CompoundTag entry = list.getCompound(i);
            lb.events.add(
                new CaptureEvent(
                    entry.getLong("tick"),
                    entry.getString("by"),
                    entry.getString("prev")
                )
            );
        }
        return lb;
    }
}
