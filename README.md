# Living World

A companion mod for **[Reign of Nether](./reignofnether/)** (vendored as a submodule of the [Andris73/reignofnether](https://github.com/Andris73/reignofnether) fork) that replaces vanilla villages with autonomous NPC factions (villagers, monsters or piglins) that build, expand, and manage their settlements without player input.

## Downloads

Builds are produced automatically by [GitHub Actions](.github/workflows/build.yml) on every push. Tagged versions (`v<release>.<feature>.<patch>`) appear as Releases under the repository — pre-releases while the release-major is `0`, regular releases once it hits `1`.

## What it does

- **Vanilla village replacement**: Removes vanilla village generation and spawns faction-specific "capitol" buildings instead.
- **Autonomous bots**: Each village is owned by an AI "bot" that registers as an RTS player in Reign of Nether.
- **Self-building**: Capitol buildings construct themselves using RoN's `selfBuilding` flag — no workers required.
- **Strategic AI**: Villages evaluate needs (food production, housing, resource proximity) and autonomously place appropriate buildings and train units.
- **Neutral reputation**: Villages start allied with players. Future plans include dynamic reputation and trading.

## Current implementation status

- ✅ **Slice 1**: Working Forge mod skeleton with dependency on Reign of Nether, debug commands, bot registration, resource pools, capitol placement with `selfBuilding`
- ✅ **Slice 5** (jumped ahead): Vanilla village replacement via NBT-path datapack override (no JSON, just `data/minecraft/structures/village/*/town_centers/*.nbt` shadowing) and capitol auto-spawn at proper ground level via `WORLD_SURFACE_WG` heightmap with `-1` offset
- ✅ **Slice 2**: Three starter workers spawn in a ring around each new capitol, with `autocastRepair = true` so they immediately help finish construction
- ✅ **Slice 3**: Hard-coded build order per faction (stockpile → house → house → farm → barracks). Brain places one project at a time with `selfBuilding = false` so workers do the building visibly
- ✅ **Slice 3.5**: Workers get permanent FOOD / WOOD / ORE roles assigned round-robin at spawn time; once idle they auto-gather and deposit to the capitol (or a stockpile once built)
- ✅ **Slice 3.6**: Brain projects use honest resource accounting (`fromCommand = false`). If the bot can't afford the next building, expansion blocks and workers spend that time gathering until the pool recovers
- ✅ **Slice 3.7**: Terrain robustness — site selection and worker spawns refuse water/lava sites (`Terrain.isLiquidNear`) and project to real ground past leaves, logs, and replaceable plants (fixes the "capitol on top of a tree" bug)
- ✅ **Slice 3.8**: Unit production via `UnitProducer`. Capitols produce workers up to `WORKER_TARGET = 6`; once the brain has built a barracks/dungeon/military-portal, that building produces basic combat units up to `MILITARY_TARGET = 4`. New workers get FOOD/WOOD/ORE roles assigned automatically based on the least-staffed role
- ✅ **Slice 3.9**: Role-cycling for stuck workers — when RoN's `stopGathering()` clears a worker's role after `MAX_FAILED_SEARCHES`, we rotate FOOD → WOOD → ORE → FOOD… until they find a reachable resource. Villager professions (LUMBERJACK / FARMER / MINER / etc) earned through experience are respected when picking roles. Unique-bot-per-village guaranteed via exact-coord naming
- ✅ **Slice 3.10**: Neutral structure replacement. Ruined portals (every biome variant) become RoN **Neutral Transport Portals** (common); desert and jungle pyramids become **Healing Fountains** (rare). Same NBT-path-override datapack trick as villages; `NeutralStructureReplacer` detects the structure starts on chunk-load and places the mapped RoN building at the centre with `ownerName = ""` so any player can use them. End portals and other player-loot landmarks (strongholds, woodland mansions) are deliberately untouched
- ✅ **Slice 4**: Long-range resource discovery + biome-aware allocation. New `ResourceIndex` (per-`ServerLevel` `SavedData` keyed by chunk) maintains a live map of FOOD/WOOD/ORE positions, populated on `ChunkEvent.Load` and updated on block break/place. `WorkerNudger` now queries it for a **200-block** effective scan radius at O(1) per chunk — fixes the "idle near capitol" problem when resources are 30+ blocks out. New `BiomeProfile` classifies biomes by tags (forest=WOOD-heavy, desert=no WOOD, mountains=ORE-rich, etc.) and `WorkforceAllocator` picks roles by a weighted sample of (biome priors) + (current build-queue demand) so a desert village naturally biases ORE/FOOD while a forest village biases WOOD. Architecture inspired by 0 A.D. Petra (most relevant open-source RTS AI) and Minecolonies (closest Forge precedent for indexed worker pathfinding)
- ✅ **Slice 4.1**: Endless tech-tree expansion. `BuildOrder` now defines a full per-faction sequence (economy → military → tech tier 1 → tech tier 2 → late game super-buildings) plus a `growthLoop()` that cycles housing/farms/military forever after the main sequence is done. Worker target scales with housing count (4 base + 2 per house, capped at 20), military target scales with military-building count (2 base + 2 per barracks/dungeon/military-portal, capped at 12). Soft cap of 30 total buildings per village to prevent infinite sprawl. Workers now alternate gather → deposit → build forever, naturally bottlenecked by resource income
- ✅ **Slice 4.2**: Hostile-mob targeting patch. Vanilla zombies / skeletons / creepers / etc. get a target goal added on spawn that makes them attack any RoN unit. Gives every faction's military something to fight
- ❌ **Slice 4.5+**: Full Petra-style task / queue / account economy; dedicated scout entities; base expansion to a second outpost when local resources deplete

## Build instructions

Living World is a companion mod for Reign of Nether — it doesn't modify RoN's source, but it does need a compiled RoN jar to link against. The RoN fork is vendored as a git submodule at `reignofnether/`.

1. **Clone with submodules**:
   ```bash
   git clone --recurse-submodules https://github.com/Andris73/livingworld.git
   cd livingworld
   ```
   If you already cloned without `--recurse-submodules`:
   ```bash
   git submodule update --init --recursive
   ```

2. **Build Reign of Nether**:
   ```bash
   # The upstream RoN repo doesn't ship a settings.gradle; create a one-liner
   # so gradle treats the submodule as its own build root instead of trying
   # to attach to livingworld's.
   [ -f reignofnether/settings.gradle ] || \
       echo "rootProject.name = 'reignofnether'" > reignofnether/settings.gradle

   cd reignofnether
   ./gradlew build
   cd ..
   ```
   This produces `reignofnether-1.3.3d.jar` in `reignofnether/build/libs/`.

3. **Build this mod**:
   ```bash
   ./gradlew build
   ```

4. **Run in development**:
   ```bash
   ./gradlew runServer
   # or
   ./gradlew runClient
   ```

Alternatively, use the reproducible Docker build:
```bash
docker build -f Dockerfile.build -t mc-mod-builder . --no-cache
```

Both mods' jars go in your server's `mods/` folder for production use.

### Pointing the build at a different RoN checkout

The `ron_path` / `ron_version` properties in `build.gradle` default to the bundled submodule. Override them on the gradle command line if you want to consume a sibling checkout instead:

```bash
./gradlew build -Pron_path=/path/to/reignofnether -Pron_version=1.3.3d
```

## Usage (slice 1)

1. Start a world with both Reign of Nether and Living World installed.
2. Get operator permissions (`/op <yourname>`).
3. Use the debug commands:
   - `/livingworld spawn auto` — spawn a village of a biome-appropriate faction at your position
   - `/livingworld spawn villagers` — explicitly spawn a villagers village
   - `/livingworld spawn monsters` — explicitly spawn a monsters village
   - `/livingworld spawn piglins` — explicitly spawn a piglins village
   - `/livingworld list` — list all active NPC villages
   - `/livingworld clear` — remove all NPC villages from server state

Watch the capitol building auto-construct itself block by block!

## Design philosophy

This mod is structured as **vertical slices**:

1. **Slice 1** _(✅)_: Debug commands that create a village bot and place a self-building capitol.
2. **Slice 2** _(✅)_: Spawn starting workers with `autocastRepair=true` so they help build the capitol.
3. **Slice 3** _(✅)_: Hard-coded build order (stockpile → houses → farm → barracks).
4. **Slice 4**: Reactive brain that evaluates needs dynamically.
5. **Slice 5** _(✅)_: Replace vanilla village generation with NPC village spawning.
6. **Slice 6+**: Reputation, trading, hire-able units.

Each slice is independently testable. The final goal is autonomous villages that feel alive to Minecraft players while providing genuine RTS opponents for Reign of Nether players.

## Future improvements (post-slice-4)

Things we know we want but are deferring to keep slices small:

- **Inter-village conflict**: Each village now has its own bot (`Slice 3.9`), but bots are currently passive towards other bots — their combat units don't scout or engage. Future work: combat units patrol around the village; same-faction villages default to NEUTRAL but become hostile under certain conditions (resource competition, low rep); cross-faction villages default to HOSTILE; villages allied with a player inherit that player's enemies.
- **Same-faction village coordination**: When several villages share a faction, they could trade resources, share intel about enemies, or send reinforcements to attacked allies. Currently each bot is fully independent.
- **Workforce allocation**: Currently the brain commits all idle workers to the same build project via RoN's `autocastRepair`. Better behaviour would be to dedicate only `X%` of the workforce to building so the remaining workers stay on resource gathering. This lets construction and economy run in parallel.
- **`livingworld:freeBuilds` gamerule**: A debug toggle that flips brain projects back to `fromCommand = true` so admins can stress-test the build order / brain logic without waiting on the gather → deposit loop. Useful for rapid iteration; should not be on by default.
- **Bridge construction for cross-water resources**: RoN units don't path well through water. When the brain detects that the closest source of a needed resource is on the far side of a river/lake, it should queue a faction bridge (Oak / Spruce / Blackstone) towards that resource so workers can reach it. Also requires deeper pathfinding awareness than the current site finder.
- **Search-and-wander when nodes deplete**: RoN's `GatherResourcesGoal` keeps the FOOD/WOOD/ORE role even after local nodes run out, but the worker just idles. Adding a wander-search behaviour so workers travel further to find more of their assigned resource would feel more alive.
- **More neutral-structure mappings**: Slice 3.10 covers ruined portals and pyramids. Igloos, swamp huts, shipwrecks, and ocean ruins are also candidates — each could become a small themed neutral building (or just be ignored, to preserve their loot-discovery feel).
- **0,0 capture beacon**: At world generation, spawn an unowned, unbuilt `Buildings.CAPTURABLE_BEACON` at world spawn (`0, ground, 0`). It's a high-value target that any village or player can fight over and claim. RoN already handles capture mechanics; we just need to place the structure. **Win condition** (decided): a global chat announcement when captured ("The Village of Ironholm has captured the Beacon of Origins!") plus a persistent leaderboard entry in our save data so the server keeps a history of conquerors. Open-ended sandbox — no hard end-game.
- **Capitol upgrade tiers**: RoN supports tiered upgrades on the capitol; the brain could queue these once resources accumulate.
- **Deeper villager profession use**: Slice 3.9 respects existing professions when picking roles, but doesn't drive XP gain or specialize role splits (e.g. don't park 6 workers on FARMER when one specialist is enough). A smarter scheduler could keep a few specialists per role and rotate the rest based on need.

## Architecture

- **`FactionBot`**: Wraps a Reign of Nether `RTSPlayer` + strategic brain.
- **`FactionBrain`**: Evaluates needs (food, housing, defense) and queues buildings.
- **`VillageSiteService`**: Bootstraps a village (create bot, register with RoN, place capitol).
- **`FactionPicker`**: Biome → faction mapping with wildcard randomness.

The mod reuses as much of Reign of Nether's existing infrastructure as possible — bot players, resource pools, building placement, `selfBuilding` mechanics, worker AI, alliance system — minimizing the custom code surface.

## Project structure

```
src/main/java/com/livingworld/
├── LivingWorld.java                 # Main @Mod class
├── bot/
│   ├── FactionBot.java              # Wrapper for RTSPlayer + brain
│   ├── FactionBotRegistry.java      # Tracks all active villages
│   └── FactionBotEvents.java        # Forge event handlers (tick, lifecycle)
├── brain/
│   ├── FactionBrain.java            # Strategic AI (stub for now)
│   ├── BuildProject.java            # A planned-but-not-placed building
│   └── Need.java                    # High-level needs (food, housing, etc.)
├── worldgen/
│   ├── VillageSiteService.java      # Bootstrap a village at a position
│   └── FactionPicker.java           # Biome → faction with randomness
├── command/
│   └── LivingWorldCommands.java     # Debug commands
└── util/
    └── Names.java                   # Generate stable bot names per location
```

## Configuration

Tweak behavior by editing the constants in:
- `FactionPicker.WILDCARD_CHANCE` — chance to override biome-based faction choice
- `VillageSiteService.STARTING_*` — resources given to new villages
- `FactionBotEvents.BRAIN_TICK_INTERVAL` — how often brains re-evaluate (currently 5 seconds)

## Compatibility

- **Minecraft**: 1.20.1
- **Forge**: 47.4.0+
- **Reign of Nether**: 1.3.3d+

## License

GNU GPLv3 (same as Reign of Nether)