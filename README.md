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
- ✅ **Slice 6 — Reputation, trade & hires**: Six tiers (Hated → Exalted) tracked per (player, faction) in NBT-backed `ReputationSaveData`. Rep moves via combat, theft (`PlayerContainerEvent.Open` attribution), gifts (faction-specific gift items), trade (`MerchantMenu` at Friendly+, daily-cap +8/day), tier-change notifications, alliance sync, cross-faction propagation. `FactionVendorOffers` ships per-faction goods at Friendly and Exalted. At Exalted, vendors sell `HireToken` Echo Shards — right-click spawns a player-owned faction unit, capped at 3 per (player, faction). `/livingworld rep` command shows tier + score; `rep set` is the admin override
- ✅ **Slice 7 — Inhabited villages on spawn**: Newly-placed villages skip ahead through the build order so they appear inhabited from the moment they're discovered. `SatelliteStructures` pre-spawns 5 main-sequence buildings (stockpile + 2 houses + farm + military building) using `selfBuilding=true`; `SatelliteUnits` adds 2 starter military around the capitol (4 for piglins, with BRUTE_UNIT for piglin satellites). `FactionBrain.advanceBrainSteps(int)` walks the build cursor past pre-spawned steps so production starts immediately on first brain tick. `/livingworld set-spawn-satellites <true|false>` toggles live
- ✅ **Slice 8 — Force-load + village health**: `VillageChunkLoader` keeps a 7×7 chunk square force-loaded around each capitol with ticking enabled. `VillageHealth` detects non-functional villages (capitol destroyed + no workers, or zero buildings + zero units) and after a 30 s grace destroys remaining buildings, unforces chunks, deregisters the bot
- ✅ **Slice 9 — Defense & retaliation**: `UnitCombatEvents` triggers retaliation when bot units are hit: victim retaliates and same-bot idle military within radius rally to the attacker. `HostileSweep` per brain-tick targets every unit (workers included) at any HOSTILE-tier player within 50 blocks of the capitol. `ReputationManager.syncHostilePlayerTracking` keeps a server-wide `hostilePlayers` set that `PatrolManager` aims its scouts at
- ✅ **Slice 9.1 — Night cycle behaviours (villagers)**: `NightShelter` saves each worker's role at dusk, sends them to the nearest house interior, restores the role + clears the move target at dawn. `NightGuard` replaces `PatrolManager` at night for villagers, stationing idle military in a 4-block ring around each house via golden-angle spreading. Workers in the SHELTERED side-map don't get their roles reset by `WorkerAssignment`. Villager houses use an open doorway (no doors) to avoid worker-queue blocking; the schematic ships with a red bed and a chest using vanilla `village_plains_house` loot table for first-open randomness
- ✅ **Slice 9.2 — Worker robustness**: Workers `setNavigation(Float, true)` so they path through and float on water. `UnreachableResources` keeps a 10-min TTL of resource positions whose straight-line path had a water gap; `WorkerNudger.findNearest` filters those out. `WorkerFlight` rewritten with correct `instanceof` order (WorkerUnit first, then Unit alliance check, then Monster) so workers no longer flee from each other indefinitely. `WorkforceAllocator` anti-skew: each role weight divided by `(1 + current count)` before sampling so distribution converges to biome priors
- ✅ **Slice 9.3 — MobVigil + opportunistic hunting**: `MobVigil` runs 24/7 for villager and piglin bots, scanning every `AttackerUnit` for vanilla `Monster` entities within 15 blocks and assigning them as targets. Runs before `WorkerFlight` so workers fight instead of flee. Vanilla Enderman gets a 50% base-attack / 33% speed debuff against bot units, plus a further 0.4× on hit. Idle FOOD workers scan a 30-block radius for huntable animals (`ResourceSources.isHuntableAnimal`) and engage them; RoN's `onDropItem` handler converts the kill into food
- ✅ **Slice 10 — Per-faction melee + ranged military**: `UnitProducer` extended with separate `meleeEntityType` / `rangedEntityType` and corresponding production lookups per faction. `produceMilitaryIfNeeded` picks whichever class has the lower fill ratio relative to half-target, driving to a ~50/50 split over time. Ranged unit per faction: Pillager (villagers), Skeleton (monsters), Headhunter (piglins). Military building for monsters is **Graveyard** (not Dungeon — Dungeon silently rejected Zombie/Skeleton queues). Piglin Grunt worker damage bumped 1.0 → 3.0 because piglins have no walls / shelter and workers need to hold their own
- ✅ **Slice 11 — Beacon of Origins**: `CapturableBeacon` spawned at world origin (0, ground, 0). `BeaconOfOrigins` polls owner every second, detects tier changes (0–5) and hold-time thresholds (25 % / 50 % / 75 % / final minute / victory). `BeaconLeaderboard` (`SavedData`) records every capture event. `BeaconAdvancements` grants and revokes a `livingworld:beacon/` advancement tree using `minecraft:impossible` triggers — driven entirely from the tick handler, with full reset on capture so the tree is REPEATABLE. `BeaconRaid` makes NPC villages compete for the beacon (5 % chance per brain-tick per village to send an idle military toward it). RoN's `BeaconPlacement.sendWarning` is suppressed in the fork; the void is filled by vanilla advancement chat lines for players, and a manual `tellraw`-style broadcast for bots
- ✅ **Slice 12 — GitHub distribution**: Repository now lives at [Andris73/livingworld](https://github.com/Andris73/livingworld) with `reignofnether` as a submodule. GitHub Actions builds every push (`build.yml`), uploads the jar as an artifact, and auto-cuts a pre-release tagged `v<version>` whenever a new version appears in `build.gradle`. Pre-release while the major is 0; promotes to a full release at 1.x
- ❌ **Slice 13+**: See *Future improvements* below

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
4. **Slice 4** _(✅)_: Reactive brain with biome-aware resource discovery + workforce allocation.
5. **Slice 5** _(✅)_: Replace vanilla village generation with NPC village spawning.
6. **Slice 6** _(✅)_: Reputation, trading, hire-able units.
7. **Slice 7** _(✅)_: Inhabited villages on spawn (satellite buildings + units).
8. **Slices 8–9** _(✅)_: Defense layer, chunk-loading, night cycle (`NightShelter` / `NightGuard`), worker robustness, opportunistic hunting, `MobVigil`.
9. **Slice 10** _(✅)_: Per-faction melee + ranged military with auto-balanced production.
10. **Slice 11** _(✅)_: Beacon of Origins capture point + advancement tree + leaderboard + NPC raid parties.
11. **Slice 12** _(✅)_: GitHub distribution — submodule layout + CI pipeline + auto-release.
12. **Slice 13+**: Personality, inter-village diplomacy, settler migration, seasonal events, radiant quests — see *Future improvements* above for the curated backlog.

Each slice is independently testable. The final goal is autonomous villages that feel alive to Minecraft players while providing genuine RTS opponents for Reign of Nether players.

## Future improvements

The core slices (1–12) cover the "each village is a working RTS opponent" baseline. The ideas below are the next wave — grouped by theme and tagged with the RTS / sim-game that inspired each one, so design intent is clear at start-of-work. Nothing here is committed scope; this is a research-driven backlog to pull from when picking the next slice.

### Bot personality & strategy

- **Personality vectors per village** *(Age of Empires II custom AI scripts, C&C Generals "aggressive / defensive / turtle" personalities, Civ leader traits)*. Sample a handful of floats at village creation — `aggression`, `mercantile`, `isolationist`, `expansionist` — and multiply them into `WorkforceAllocator` weights, `MILITARY_TARGET`, `BeaconRaid` dispatch chance, and gift-trade caps. Two same-faction villages should feel different even with identical biomes.
- **Phase state machine** *(StarCraft BWAPI bots, AIIDE convention)*. Explicit EARLY / MID / LATE phases keyed off game time + building count + military strength. Each phase overlays a different priority bias on top of the existing `BuildOrder`: early prioritises housing/economy, mid prioritises military + tech, late prioritises Wonders and raids.
- **Doctrine drift** *(CK3, EU4)*. Personality vectors mutate slowly in response to events: repeated raids tilt a village toward `aggression`; long trade streaks tilt toward `mercantile`. Surfaces through `/livingworld inspect <name>`.

### Inter-village & cross-faction interactions

- **Village ↔ village reputation** *(CK3 alliance webs, EU4 opinions)*. Extend Slice 6 to track per-village-vs-per-village scores too, not just per-(player, faction). Same-faction villages default to NEUTRAL and slide toward FRIENDLY on adjacency + trade; cross-faction default to UNFRIENDLY. Enables every entry below.
- **Trade caravans between allied villages** *(Anno 1800, The Settlers, Civilization trade routes)*. A worker carrying a chest-icon item walks from village A to village B along a navigated path. Safe arrival → both villages gain resources; killed in transit → the items drop and the killer becomes an attribution target for retaliation. Reuses the existing patrol/movement infrastructure.
- **Reinforcement requests** *(Northgard clan aid, Total War same-faction calls)*. When a village under attack drops below half military strength, it broadcasts a request to same-faction villages within N blocks. The nearest idle military squad from a responder dispatches toward the calling capitol.
- **Bounty board** *(Stellaris criminal syndicates, Skyrim radiant bounties)*. A Hated-tier village can post a bounty on a player via chat broadcast. Other villages — and players — earn rep + resources for delivering the kill. Lets the world propagate consequence without the bot itself having to march.
- **Tribute demands** *(Total War client states, AoE2 tribute)*. A village at Exalted rep with a player can demand periodic tribute; non-payment slides rep back toward Hostile over time.

### Population & expansion

- **Settler parties** *(Northgard scouts → new clan tiles, Songs of Syx population pressure, Banished immigration)*. When a village hits its building cap with full housing, dispatch a settler party (2 workers + 1 military) that walks toward an unexplored biome edge and founds a new village via `VillageSiteService`. Mid/late-game world-fill without relying solely on world-gen.
- **Refugees & migrants** *(Frostpunk newcomers, Rimworld wanderers)*. A stray worker entity occasionally spawns near a player's base with a take-me-in interaction. Accept → +rep with the worker's faction and they join the player's hire pool; refuse → small −rep. Cheap roleplay flavour.
- **Defectors** *(Dwarf Fortress fleeing migrants, Rimworld mental break wandering)*. A worker in a village at Hated rep with a neighbouring faction can defect during night-shelter, walking to the nearest opposite-faction village. Cosmetic but reinforces the "alive" feel.

### Combat & defense

- **Walls and towers under repeated siege** *(Age of Empires II castles, Stronghold)*. `VillageHealth` already tracks attack history; once a village has been raided N times the brain queues defensive structures from RoN's existing wall / tower set. Persistent fortification instead of the current full-rebuild-after-destruction pattern.
- **Champion units** *(Warcraft III heroes, Civ great generals)*. Each village has one named hero (RoN already has a hero system). Survives across attacks; dies → respawns when the military building is rebuilt. Named in defeat / capture chat lines for narrative weight.
- **Scouts & known-world map** *(StarCraft observers, AoE2 explorers)*. Dedicated SCOUT worker role. Wanders outward from capitol, marks chunks as `seen` in a per-bot `Set<ChunkPos>`. `BeaconRaid` and bounty/raid dispatchers target only seen players / villages — removes the current implicit omniscience.
- **Siege parties** *(AoE2 attack-move on enemy castle, WC3 base assault)*. When village-vs-village rep drops to WAR, dispatch a coordinated party (mix of melee + ranged + a single "officer") at that village's capitol instead of just the beacon.

### World events & emergent storytelling

- **Seasonal / scheduled events** *(Northgard winters, Frostpunk gales, Rimworld storyteller events)*. Slow global tick fires:
  - **Blood moon** every N nights → all monster villages coordinate raids on the closest player.
  - **Gold rush** → ORE veins spawn at random coords for one in-game day; first claimant gets a major bonus.
  - **Drought** → biome FOOD income halved for a day; trade prices spike correspondingly.
  - **Plague** → one random village loses 50 % of its workers; can be averted if a player gifts medicine items (potions / golden apples) before a timer expires.
- **Wonders** *(AoE2 wonder-victory timer, Civilization national wonders)*. A late-game super-structure unique per faction. Construction begins at village score threshold, takes many in-game hours, completion fires a server broadcast and applies a unique buff to nearby allies. Mid-build destruction = major rep penalty + big resource haul to the attacker.
- **Radiant quest offerings** *(Spore civ-stage missions, Stellaris anomalies, Skyrim radiant quests)*. At Friendly+ rep a village periodically posts a quest in chat: clear monsters near (X, Z), recapture a portal, escort caravan Z to (X, Z). Completion checked via existing events (`LivingDeathEvent` for clears, container open for caravan delivery). Reward = rep + faction-specific item.

### Memory, identity & lore

- **Per-bot encounter memory** *(Dwarf Fortress legends, Rimworld relationships)*. Each bot's `SavedData` adds per-player counters: kills, deaths, trades, gifts, container thefts. Long-term grudges and friendships drive modifier multipliers on rep deltas ("every kill from Durandiel costs 1.5× normal") and unlock special vendor stock for long-term friends.
- **Banners and heraldry** *(Total War faction banners, CK3 coats of arms)*. Each village places a randomly-generated banner block at its capitol entrance, themed by faction (red+skull for monsters, green+wheat for villagers, gold+flame for piglins). Visible identity at a glance, zero gameplay impact.
- **Village stat board** *(Civilization scoreboard, server `/stats` boards)*. Extend `BeaconLeaderboard` with per-village stats: buildings built, units killed, trades completed, captures held, raids survived. Surfaces "biggest village", "most-attacked", "longest-lived" — fuel for chat narrative.

### Quality of life & polish

- **Roads between buildings** *(Anno 1800, The Settlers, Civilization roads)*. Brain paves dirt-path or cobble lines between placed buildings. Same-faction villages within 500 blocks auto-connect via a longer cobble road. Cosmetic, but immediately readable as "this is a town, not a cluster".
- **Persistent Champion hire at Exalted**: extend `HireToken` with a named, persistent Champion variant (vs the current generic unit). Doesn't despawn; follows the player; named on death broadcast.
- **Workforce allocation %**: commit only X % of idle workers to a single build project so the gather → deposit loop keeps moving in parallel with construction (was on the prior TODO; still wanted).
- **`livingworld:freeBuilds` gamerule**: debug toggle that flips brain projects to `fromCommand = true` so admins can stress-test build orders without waiting on the economy (was on the prior TODO; still wanted).
- **More neutral-structure mappings**: igloos → frozen outposts, swamp huts → witch towers, shipwrecks → ocean ruin variants. Slice 3.10's NBT-path-shadowing trick generalises directly.
- **Deeper villager profession use**: drive XP gain when a worker stays in-role for long; keep one or two specialists per role and rotate the rest based on need.

### Done / superseded since last TODO refresh

Kept here briefly for traceability:

- ~~**0,0 capture beacon**~~ → shipped as Slice 11 (Beacon of Origins + advancement tree + leaderboard + raid party).
- ~~**Inter-village conflict (combat layer)**~~ → covered by Slice 9's `UnitCombatEvents`, `HostileSweep`, `MobVigil`, `NightGuard`, plus Slice 4.2's hostile-mob retargeting. The remaining "coordination" work is split into the explicit items above (village ↔ village rep, reinforcement requests, siege parties).
- ~~**Search-and-wander when nodes deplete**~~ → implemented as Slice 4's 200-block `WorkerNudger` scan + Slice 9.2's `UnreachableResources` TTL.
- ~~**Bridge construction for cross-water resources**~~ → explicitly abandoned in favour of the simpler "mark unreachable + skip" approach (Slice 9.2). Leaving it deferred indefinitely; revisit only if river-locked villages become a real problem.
- ~~**Capitol upgrade tiers**~~ → superseded by Slice 4.1's endless tech-tree growth loop; fold tier upgrades in as a normal build-order entry if/when they're worth queuing.

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