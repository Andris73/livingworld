package com.livingworld.world;

import com.solegendary.reignofnether.resources.ResourceName;
import com.solegendary.reignofnether.resources.ResourceSource;
import com.solegendary.reignofnether.resources.ResourceSources;
import it.unimi.dsi.fastutil.longs.Long2ObjectMap;
import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap;
import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
import java.util.ArrayList;
import java.util.Collections;
import java.util.EnumMap;
import java.util.List;
import javax.annotation.Nullable;
import net.minecraft.core.BlockPos;
import net.minecraft.core.SectionPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.LongArrayTag;
import net.minecraft.nbt.Tag;
import net.minecraft.server.level.ServerChunkCache;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.level.saveddata.SavedData;

/**
 * Per-{@link ServerLevel} cache of known FOOD / WOOD / ORE block positions,
 * keyed by chunk. Lets workers find resources hundreds of blocks away with
 * O(1) lookups instead of the O(R\u00b2) box scans we were doing before.
 *
 * <p><b>Why this exists:</b> Minecraft doesn't index trees / ore / animals
 * (only POIs, structures, and heightmaps). So the only way to do a 200-block
 * resource search is either (a) scan thousands of blocks every tick, or
 * (b) maintain our own index. We do (b).
 *
 * <p><b>Lifecycle:</b>
 * <ul>
 *   <li>Populated lazily on {@code ChunkEvent.Load} by
 *       {@link ResourceIndexEvents#indexChunk}.</li>
 *   <li>Mutated on {@code BlockEvent.BreakEvent} and
 *       {@code BlockEvent.EntityPlaceEvent} so it stays accurate as the world
 *       changes.</li>
 *   <li>Persisted as Minecraft {@link SavedData}, so the index survives
 *       server restarts.</li>
 * </ul>
 *
 * <p><b>Per-chunk caps</b>: we store at most {@link #MAX_PER_RESOURCE_PER_CHUNK}
 * positions per resource per chunk so memory stays bounded for huge sessions.
 * 32 positions per chunk per resource is plenty: workers only need a few
 * options to pick from \u2014 anything denser is wasted.
 *
 * <p>This is a per-level singleton (not per-bot): two villages in the same
 * world share the same view of resources, which mirrors how players see
 * the world too.
 */
public class ResourceIndex extends SavedData {

    public static final String DATA_ID = "livingworld_resources";

    /** Max positions stored per (chunk, resource). Caps memory. */
    public static final int MAX_PER_RESOURCE_PER_CHUNK = 32;

    /** Vertical band sampled around the heightmap surface during chunk indexing. */
    public static final int Y_RANGE_ABOVE = 2;
    public static final int Y_RANGE_BELOW = 4;

    /**
     * Main map: chunkPos.toLong() \u2192 EnumMap of resource \u2192 list of positions.
     * Using fastutil's primitive long-keyed map for speed; the index is the
     * hot path of every worker scan so we cant afford {@code HashMap<ChunkPos, \u2026>}.
     */
    private final Long2ObjectMap<
        EnumMap<ResourceName, List<BlockPos>>
    > byChunk = new Long2ObjectOpenHashMap<>();

    /** Chunks we've already indexed (so we don't re-scan on every load). */
    private final LongOpenHashSet indexedChunks = new LongOpenHashSet();

    // -------------------------------------------------------------- API

    /**
     * Add a resource position to the index. Caller must have already verified
     * the block is a valid resource of the given type.
     */
    public void add(ChunkPos chunk, ResourceName resource, BlockPos pos) {
        if (resource == ResourceName.NONE) return;
        EnumMap<ResourceName, List<BlockPos>> perResource =
            byChunk.computeIfAbsent(chunk.toLong(), k ->
                new EnumMap<>(ResourceName.class)
            );
        List<BlockPos> list = perResource.computeIfAbsent(resource, k ->
            new ArrayList<>(4)
        );
        if (list.size() >= MAX_PER_RESOURCE_PER_CHUNK) return; // cap
        if (list.contains(pos)) return; // dedup
        list.add(pos);
        setDirty();
    }

    /** Remove a position from the index (e.g. block was mined). */
    public void remove(ChunkPos chunk, ResourceName resource, BlockPos pos) {
        if (resource == ResourceName.NONE) return;
        EnumMap<ResourceName, List<BlockPos>> perResource = byChunk.get(
            chunk.toLong()
        );
        if (perResource == null) return;
        List<BlockPos> list = perResource.get(resource);
        if (list == null) return;
        if (list.remove(pos)) setDirty();
    }

    /**
     * Return the positions known for {@code resource} in {@code chunk}, or
     * an empty list. Result is unmodifiable; do not edit.
     */
    public List<BlockPos> get(ChunkPos chunk, ResourceName resource) {
        EnumMap<ResourceName, List<BlockPos>> perResource = byChunk.get(
            chunk.toLong()
        );
        if (perResource == null) return Collections.emptyList();
        List<BlockPos> list = perResource.get(resource);
        return list == null
            ? Collections.emptyList()
            : Collections.unmodifiableList(list);
    }

    /** True if we've already scanned this chunk into the index. */
    public boolean isIndexed(ChunkPos chunk) {
        return indexedChunks.contains(chunk.toLong());
    }

    /** Mark this chunk as scanned (called by {@link ResourceIndexEvents}). */
    public void markIndexed(ChunkPos chunk) {
        if (indexedChunks.add(chunk.toLong())) setDirty();
    }

    /**
     * Scan {@code chunk} for all FOOD / WOOD / ORE blocks and add them to the
     * index. Idempotent: subsequent calls on the same chunk are no-ops.
     *
     * <p>Cost is bounded by 16\u00d716 = 256 columns \u00d7 7 Y samples \u2248 1800 block
     * reads. On an already-loaded chunk that's well under a millisecond.
     */
    public void indexChunk(LevelChunk chunk) {
        ChunkPos cp = chunk.getPos();
        if (indexedChunks.contains(cp.toLong())) return;

        int baseX = cp.getMinBlockX();
        int baseZ = cp.getMinBlockZ();
        BlockPos.MutableBlockPos cursor = new BlockPos.MutableBlockPos();

        for (int dx = 0; dx < 16; dx++) {
            for (int dz = 0; dz < 16; dz++) {
                int x = baseX + dx;
                int z = baseZ + dz;
                int top = chunk.getHeight(
                    Heightmap.Types.WORLD_SURFACE_WG,
                    x,
                    z
                );
                for (int dy = -Y_RANGE_BELOW; dy <= Y_RANGE_ABOVE; dy++) {
                    cursor.set(x, top - 1 + dy, z);
                    BlockState bs = chunk.getBlockState(cursor);
                    ResourceSource src = ResourceSources.getFromBlockState(bs);
                    if (src == null) continue;
                    if (src.resourceName == ResourceName.NONE) continue;
                    add(cp, src.resourceName, cursor.immutable());
                }
            }
        }

        markIndexed(cp);
    }

    /**
     * Drop all cached entries for {@code chunk} \u2014 e.g. if a chunk gets reset
     * or we want to force a re-scan. Rarely needed.
     */
    public void forgetChunk(ChunkPos chunk) {
        if (byChunk.remove(chunk.toLong()) != null) setDirty();
        if (indexedChunks.remove(chunk.toLong())) setDirty();
    }

    public int totalKnownPositions() {
        int n = 0;
        for (var perChunk : byChunk.values()) {
            for (var list : perChunk.values()) n += list.size();
        }
        return n;
    }

    public int indexedChunkCount() {
        return indexedChunks.size();
    }

    // ---------------------------------------------------------- accessor

    public static ResourceIndex get(ServerLevel level) {
        return level
            .getDataStorage()
            .computeIfAbsent(ResourceIndex::load, ResourceIndex::new, DATA_ID);
    }

    // --------------------------------------------------------- persistence

    @Override
    public CompoundTag save(CompoundTag tag) {
        // Encoded as one ListTag of compound entries per chunk.
        ListTag chunks = new ListTag();
        for (Long2ObjectMap.Entry<
            EnumMap<ResourceName, List<BlockPos>>
        > entry : byChunk.long2ObjectEntrySet()) {
            CompoundTag chunkTag = new CompoundTag();
            chunkTag.putLong("c", entry.getLongKey());
            for (var resourceEntry : entry.getValue().entrySet()) {
                long[] packed = new long[resourceEntry.getValue().size()];
                int i = 0;
                for (BlockPos p : resourceEntry.getValue())
                    packed[i++] = p.asLong();
                chunkTag.put(
                    resourceEntry.getKey().name(),
                    new LongArrayTag(packed)
                );
            }
            chunks.add(chunkTag);
        }
        tag.put("chunks", chunks);

        long[] indexed = new long[indexedChunks.size()];
        int i = 0;
        var it = indexedChunks.iterator();
        while (it.hasNext()) indexed[i++] = it.nextLong();
        tag.put("indexed", new LongArrayTag(indexed));
        return tag;
    }

    public static ResourceIndex load(CompoundTag tag) {
        ResourceIndex idx = new ResourceIndex();
        ListTag chunks = tag.getList("chunks", Tag.TAG_COMPOUND);
        for (int i = 0; i < chunks.size(); i++) {
            CompoundTag chunkTag = chunks.getCompound(i);
            long chunkKey = chunkTag.getLong("c");
            EnumMap<ResourceName, List<BlockPos>> perResource = new EnumMap<>(
                ResourceName.class
            );
            for (ResourceName r : ResourceName.values()) {
                if (r == ResourceName.NONE) continue;
                if (!chunkTag.contains(r.name())) continue;
                long[] packed = chunkTag.getLongArray(r.name());
                List<BlockPos> list = new ArrayList<>(packed.length);
                for (long p : packed) list.add(BlockPos.of(p));
                perResource.put(r, list);
            }
            idx.byChunk.put(chunkKey, perResource);
        }
        for (long c : tag.getLongArray("indexed")) idx.indexedChunks.add(c);
        return idx;
    }

    // ------------------------------------------------------------- search

    /**
     * Find the nearest indexed resource of {@code role} to {@code centre},
     * within {@code radiusBlocks}. Iterates chunks in expanding rings; bails
     * as soon as any further ring can't beat the current best.
     *
     * @param excluded positions to skip (e.g. already claimed by another worker
     *                 this tick)
     * @return closest known position, or null if none found in range
     */
    @Nullable
    public BlockPos findNearest(
        ServerLevel level,
        BlockPos centre,
        ResourceName role,
        int radiusBlocks,
        java.util.Set<BlockPos> excluded
    ) {
        if (role == ResourceName.NONE) return null;

        ServerChunkCache scc = level.getChunkSource();
        int cxCentre = SectionPos.blockToSectionCoord(centre.getX());
        int czCentre = SectionPos.blockToSectionCoord(centre.getZ());
        int chunkRadius = (radiusBlocks >> 4) + 1;
        double radiusSqr = (double) radiusBlocks * radiusBlocks;

        BlockPos best = null;
        double bestDistSqr = Double.MAX_VALUE;

        for (int ring = 0; ring <= chunkRadius; ring++) {
            // Early-out: if our best is closer than the nearest possible point
            // in this ring, stop.
            double ringMinDist = Math.max(0, ring - 1) * 16.0;
            if (best != null && bestDistSqr < ringMinDist * ringMinDist) break;

            for (int dx = -ring; dx <= ring; dx++) {
                for (int dz = -ring; dz <= ring; dz++) {
                    // perimeter of ring only
                    if (Math.max(Math.abs(dx), Math.abs(dz)) != ring) continue;
                    int cx = cxCentre + dx;
                    int cz = czCentre + dz;
                    ChunkPos cp = new ChunkPos(cx, cz);

                    // Lazy-index this chunk if it's loaded but never scanned.
                    if (!isIndexed(cp)) {
                        LevelChunk lc = scc.getChunkNow(cx, cz);
                        if (lc != null) indexChunk(lc);
                        else continue; // chunk not loaded; skip entirely (no force-load)
                    }

                    // Snapshot the list — we may mutate it via remove() if we
                    // find a stale entry.
                    List<BlockPos> cached = new java.util.ArrayList<>(
                        get(cp, role)
                    );
                    LevelChunk lc = scc.getChunkNow(cx, cz);
                    for (BlockPos p : cached) {
                        if (excluded.contains(p)) continue;
                        double d = p.distSqr(centre);
                        if (d > radiusSqr || d >= bestDistSqr) continue;

                        // Verify the block is still the right resource type.
                        // Self-heals stale entries (sapling grew into a tree but
                        // we missed the event; another mod replaced the block;
                        // etc.) by removing them from the index. Without this,
                        // workers get directed to phantom targets and idle.
                        if (lc != null) {
                            ResourceSource src =
                                ResourceSources.getFromBlockState(
                                    lc.getBlockState(p)
                                );
                            if (src == null || src.resourceName != role) {
                                remove(cp, role, p);
                                continue;
                            }
                        }

                        best = p;
                        bestDistSqr = d;
                    }
                }
            }
        }
        return best;
    }
}
