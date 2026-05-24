package com.livingworld.reputation;

import com.solegendary.reignofnether.faction.Faction;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.saveddata.SavedData;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/**
 * Persists all reputation scores across server restarts.
 *
 * <p>Stored as {@link SavedData} in the overworld data storage under
 * {@link #DATA_ID}. Structure:
 * <pre>
 * {
 *   players: [
 *     {
 *       uuid: "...",
 *       factions: [
 *         { faction: "VILLAGERS", score: 340, infamyFloor: 0,
 *           lastGiftTime: 12345L, tradeRepToday: 8, hireCount: 1 },
 *         ...
 *       ]
 *     }
 *   ]
 * }
 * </pre>
 */
public class ReputationSaveData extends SavedData {

    public static final String DATA_ID = "livingworld_reputation";

    /** per-player per-faction state */
    public final Map<UUID, Map<Faction, Entry>> data = new HashMap<>();

    public static class Entry {
        public int score       = 0;
        public int infamyFloor = 0;      // decay cannot push score above this if negative
        public long lastGiftTime = 0L;   // game tick of last successful gift
        public int tradeRepToday = 0;    // rep gained via trade this in-game day
        public long lastTradeDay = 0L;   // in-game day when tradeRepToday was last reset
        public int hireCount   = 0;      // units currently hired from this faction

        public Entry() {}
    }

    /** Get or create an entry for (player, faction). */
    public Entry getOrCreate(UUID player, Faction faction) {
        return data
                .computeIfAbsent(player, k -> new HashMap<>())
                .computeIfAbsent(faction, k -> new Entry());
    }

    // ---------------------------------------------------------- accessor

    public static ReputationSaveData get(ServerLevel level) {
        return level.getDataStorage().computeIfAbsent(
                ReputationSaveData::load,
                ReputationSaveData::new,
                DATA_ID);
    }

    // -------------------------------------------------------- persistence

    @Override
    public CompoundTag save(CompoundTag tag) {
        ListTag players = new ListTag();
        for (var playerEntry : data.entrySet()) {
            CompoundTag pt = new CompoundTag();
            pt.putString("uuid", playerEntry.getKey().toString());
            ListTag factions = new ListTag();
            for (var factionEntry : playerEntry.getValue().entrySet()) {
                CompoundTag ft = new CompoundTag();
                ft.putString("faction", factionEntry.getKey().name());
                Entry e = factionEntry.getValue();
                ft.putInt("score",         e.score);
                ft.putInt("infamyFloor",   e.infamyFloor);
                ft.putLong("lastGiftTime", e.lastGiftTime);
                ft.putInt("tradeRepToday", e.tradeRepToday);
                ft.putLong("lastTradeDay", e.lastTradeDay);
                ft.putInt("hireCount",     e.hireCount);
                factions.add(ft);
            }
            pt.put("factions", factions);
            players.add(pt);
        }
        tag.put("players", players);
        return tag;
    }

    public static ReputationSaveData load(CompoundTag tag) {
        ReputationSaveData d = new ReputationSaveData();
        ListTag players = tag.getList("players", Tag.TAG_COMPOUND);
        for (int i = 0; i < players.size(); i++) {
            CompoundTag pt = players.getCompound(i);
            UUID uuid = UUID.fromString(pt.getString("uuid"));
            ListTag factions = pt.getList("factions", Tag.TAG_COMPOUND);
            for (int j = 0; j < factions.size(); j++) {
                CompoundTag ft = factions.getCompound(j);
                try {
                    Faction f = Faction.valueOf(ft.getString("faction"));
                    Entry e = new Entry();
                    e.score         = ft.getInt("score");
                    e.infamyFloor   = ft.getInt("infamyFloor");
                    e.lastGiftTime  = ft.getLong("lastGiftTime");
                    e.tradeRepToday = ft.getInt("tradeRepToday");
                    e.lastTradeDay  = ft.getLong("lastTradeDay");
                    e.hireCount     = ft.getInt("hireCount");
                    d.data.computeIfAbsent(uuid, k -> new HashMap<>()).put(f, e);
                } catch (IllegalArgumentException ignored) {
                    // unknown faction name in old save — skip
                }
            }
        }
        return d;
    }
}
