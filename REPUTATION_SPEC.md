# Living World — Reputation & Trading System
## Feature Specification (Slice 6)

**Status**: Design / pre-implementation  
**Author**: Feature spec, not yet coded  
**Depends on**: Slices 1–4.2 (all green in README)

---

## 1. Overview

Players currently exist in a binary state with every NPC village: allied (normal players) or hostile (RTS players). This slice introduces a continuous reputation score that replaces the binary state for normal players, unlocks faction-specific trading, and — at the highest tier — lets players hire NPC units by obtaining faction spawn eggs.

**Design philosophy**: Keep it legible. A player should be able to understand their standing from a single `/livingworld rep` command. Mechanical depth comes from the variety of actions that influence rep, not from hidden multipliers or obscure edge cases.

---

## 2. Scope

| In scope | Out of scope |
|----------|--------------|
| Per-faction reputation score for normal (non-RTS) players | RTS player rep (they're enemies by definition) |
| 5 named tiers per faction with distinct effects | Per-village rep (all villages of a faction share rep) |
| Rep-gaining/losing events (kill, steal, gift, trade, etc.) | Rep between NPC villages |
| Slow passive decay toward Neutral | Shared team/party reputation |
| Basic vendor trade UI via right-click on worker unit | A dedicated NPC quest-giver entity |
| `/livingworld rep` command | In-world rep boards or signs |
| Hire system: spawn eggs for owned units | Player-controlled NPC armies beyond spawn eggs |
| Cross-faction rep propagation (enemy-of-my-enemy) | Alliance propagation between same-faction villages |
| Persistence via `SavedData` | Rep import/export or shared server configs |

---

## 3. Core Data Model

### 3.1 Storage key

Rep is stored **per Minecraft player UUID, per `Faction` enum value**. This means:

- All Villager villages share one score: your rep with `Faction.VILLAGERS`.
- All Monster villages share one score: your rep with `Faction.MONSTERS`.
- All Piglin villages share one score: your rep with `Faction.PIGLINS`.

Per-village rep would feel more immersive but creates a bookkeeping burden that isn't worth it for this slice. If a player torches a Monster stronghold, every Monster village should feel it.

### 3.2 Numerical range

**-1000 to +1000**, stored as a clamped `int`.

This is chosen because:
- The deltas below are expressed in multiples of ~5–100, so a four-digit range provides plenty of resolution.
- It's easy to communicate to a player: "You're at +340/1000 with the Villagers."
- The tier boundaries (see §4) are round numbers.

### 3.3 New class: `ReputationManager`

Singleton backed by `ReputationSaveData` (follows the `RTSPlayerSaveData` pattern: `SavedData` subclass, keyed by player UUID string + faction name in `CompoundTag`).

```
ReputationManager
  + getReputation(UUID player, Faction f) → int
  + adjustReputation(UUID player, Faction f, int delta, RepSource source)
  + getTier(UUID player, Faction f) → RepTier
  + resetReputation(UUID player, Faction f)      // admin use
```

`RepSource` is an enum used only for logging and notification text:
`KILL_WORKER`, `KILL_MILITARY`, `STEAL`, `GIFT`, `REQUEST_COMPLETE`,
`ATTACK_BUILDING`, `DEFEND_FACTION`, `TRADE`, `NEARBY_VICTORY`,
`ATTACK_ENEMY`, `BETRAYAL_MULTIPLIER`, `PASSIVE_DECAY`.

The `adjustReputation` method:
1. Applies betrayal multiplier if applicable (see §7.4).
2. Clamps result to [-1000, +1000].
3. Checks tier transition; if the tier changes, fires a notification (§9).
4. If new tier < Neutral, removes the alliance between player and that faction's bots;  
   if new tier ≥ Neutral, re-adds the alliance.
5. Marks the `SavedData` dirty.

---

## 4. Reputation Tiers

Five tiers. The choice of five (not three or seven) is deliberate:
- **Three** loses the meaningful middle ground between Friendly and Exalted.
- **Seven** creates confusion about what practically changes between adjacent tiers.
- **Five** gives three positive and two negative tiers, matching the asymmetry in play: it should be harder to destroy rep than build it.

### 4.1 Thresholds

| Tier | Score range | Villagers name | Monsters name | Piglins name |
|------|-------------|----------------|---------------|--------------|
| 1 — **Hostile** | -1000 to -501 | Denounced | Nemesis | Outcast |
| 2 — **Unfriendly** | -500 to -101 | Suspicious | Unwanted | Shunned |
| 3 — **Neutral** | -100 to +100 | Neutral | Neutral | Neutral |
| 4 — **Friendly** | +101 to +500 | Respected | Tolerated | Accepted |
| 5 — **Exalted** | +501 to +1000 | Honoured Ally | Blood Pact | Forge-Blessed |

The starting score is **0** (Neutral) for all three factions. There is no asymmetric default — even Monsters start at Neutral. The setting that Monsters "feel more hostile by default" is already handled by their military units pairing with `NEAREST_ENEMY_UNIT` search behaviour and `HostileMobBehavior`; rep only needs to track intentional player actions.

> **Rationale for symmetric start**: making Monsters start Unfriendly would immediately gate players out of that faction's trade with no action taken. Better to let players choose to engage or ignore; Monsters' in-world aggression already communicates their nature.

---

## 5. Rep-Changing Actions

### 5.1 Reference table

All values are for a single discrete event. Cooldowns and caps where noted prevent farming.

| Action | Delta | Cooldown / cap | Notes |
|--------|-------|----------------|-------|
| Kill a **worker** unit owned by a bot | **-100** | None | Workers (implement `WorkerUnit`) are civilians; the worst single act |
| Kill a **military** unit owned by a bot | **-30** | None | Legitimate combat if you must, but you pay for it |
| **Steal** from a faction building's container | **-60** | 5 min per building | Triggered by taking ≥1 item stack from a container inside a bot-owned `BuildingPlacement` |
| **Gift** a resource bundle to a village | **+20** | Once per faction per 10 min | Player drops or offers a bundle of ≥16 items matching faction's preferred resource (§8.1) |
| Complete a village **request** | **+75** | One request active per faction at a time | Post-MVP; see §5.3 |
| **Attack** a faction building | **-20** | Once per building per 5 min | Triggered on first hit of a building HP interval; not once per tick |
| Kill a vanilla **hostile mob** that was attacking a faction unit | **+5** | Cap: +30/in-game day (1200 ticks real-world ≈ 10 min) | Defending their units without being asked |
| **Trade** with a faction vendor | **+8** | Cap: +40/in-game day | Per completed trade transaction |
| **Nearby** when a faction wins a fight | **+20** | Once per battle event | Within 80 blocks of the killing blow; "battle event" = enemy village unit within 60 blocks of any bot unit |
| **Attack an enemy** of this faction | **+35** | None | See §10 for faction enmity table |
| **Annihilate** an enemy village (last unit/building) | **+100** | One-time per village destroyed | Clearing a whole enemy settlement |

### 5.2 Delta balance justification

- Killing a **worker** (-100) is the sharpest single action because workers are the backbone of an economy — it's equivalent to a war crime by RTS standards. A player who kills five villager workers should hit Hostile immediately (5 × -100 = -500; Hostile threshold is -501).
- Killing a **military unit** (-30) is more forgiving: self-defence or territorial skirmishes should not permanently destroy relations. You'd need to kill ~17 soldiers to reach Hostile if starting from Neutral, which requires sustained aggression.
- **Trade** (+8, capped at +40/day) is intentionally slow. Trade should sustain an already-Friendly relationship, not be the primary route to Exalted. Players who want to speed-run rep should gift resources.
- **Gifting** (+20, capped at once per 10 min per faction) provides a middle-speed path. Getting from Neutral to Friendly (+101) takes ~6 gifts — roughly 60 minutes of deliberate gifting. 
- **Steal** (-60) is heavier than military kill (-30) because theft is a betrayal of trust rather than open combat. A player who regularly loots bot buildings will find their relations steadily eroding even without violence.

### 5.3 Village requests (deferred to post-MVP)

A village periodically generates a single outstanding "request" visible to nearby players (chat message or UI flag on the worker): "Bring me 32 wheat", "Kill 3 of the Grimhollow skeletons", etc. Completing it grants +75 rep. The system is intentionally noted here so the data model leaves room for it (a `Map<Faction, ActiveRequest>` in `ReputationManager`), but it is **not** part of the MVP build.

---

## 6. Passive Decay and Permanent Memory

### 6.1 Decay toward Neutral

Reputation decays toward 0 at a rate of **1 point per in-game day** (24 000 game ticks, ≈ 20 real minutes), applied during the `FactionBotEvents.onLevelTick` brain tick. Decay only applies when the score is outside the Neutral band (-100 to +100).

- Positive rep decays down toward +100.
- Negative rep decays up toward -100.
- Once inside the Neutral band, decay stops.

**Why decay?** A player who raided a village six months ago and never returned should eventually recover relations. A player who traded once shouldn't have Exalted forever.

At -1 point/day, a player at Hostile minimum (-1000) recovers to Unfriendly (-500) in ~500 in-game days ≈ ~167 real hours of server uptime. That's genuinely slow and signals that atrocities have lasting consequences.

At the same rate, a player at Exalted (+1000) decays to Friendly (+101) in ~900 in-game days unless they keep trading. This is intentional — ongoing engagement keeps the relationship alive.

### 6.2 Infamy floor (permanent memory)

If a player accumulates **-500 or worse** rep (reaches Hostile) through **killing workers specifically**, a permanent infamy floor is applied: the floor is set to `max(current_rep - 100, -1000)` and stored in save data. Decay cannot carry the player's rep **above their infamy floor** until they actively do positive actions that push through it.

**Example**: Player kills 6 villager workers in one session. Rep = -600. Infamy floor = max(-700, -1000) = -700. Decay will carry them back to -700 (Hostile) at 1/day, but they're stuck in Hostile until they earn +200 through actions (6 quests, or 10 gifting sessions, etc.).

**Why infamy?** Without it, a "kill workers, wait a week, trade again" cycle trivialises the rep system. The infamy floor only activates at Hostile tier and only for worker kills — the most egregious act — so it punishes deliberate griefing without over-penalising clumsy early encounters.

Infamy floors are stored as a separate `int infamyFloor` in `ReputationSaveData` alongside the live score.

---

## 7. Tier Effects

### 7.1 Tier 3 — Neutral (default)

- Player is **allied** to the faction via `AlliancesServerEvents.addAlliance` (same as the current auto-ally on join).
- No trade available.
- Village units ignore the player.

This is the current live behaviour. The rep system simply formalises it as one tier in a scale.

### 7.2 Tier 4 — Friendly (+101 to +500)

- Alliance maintained (units remain friendly).
- **Basic trade unlocked**: player can right-click any bot-owned **worker unit** to open a faction trade UI (see §8).
- Basic tier item list available (§8.2).
- **Village will notify player of nearby threats** (chat message: *"A patrol from Ashvale spotted you being attacked — they seem willing to assist."* — flavour only; no mechanical change in MVP).

### 7.3 Tier 5 — Exalted (+501 to +1000)

- Alliance maintained.
- **Full trade unlocked**: advanced item list available.
- **15% trade price discount** on all transactions with this faction.
- **Hire system unlocked**: a "Hire" tab appears in the trade UI with purchasable spawn eggs (§8.3).
- If a Hostile player attacks the village while an Exalted player is within 120 blocks, the village units receive a targeting hint toward the attacker (flavour: they'll fight harder to defend their ally).

### 7.4 Tier 2 — Unfriendly (-500 to -101)

- Alliance **removed** via `AlliancesServerEvents.removeAlliance`.
- Village units do not actively hunt the player but **will retaliate** on contact (standard `getWillRetaliate()` = true for `AttackerUnit`).
- No trade available.
- Player receives a warning once on first tier drop: *"[LivingWorld] The villagers of Ashvale eye you with suspicion."*

### 7.5 Tier 1 — Hostile (-1000 to -501)

- Alliance removed (if not already).
- Village military units **actively target** the player within patrol range. This is achieved by registering the hostile player name in a set on `FactionBot`, then having `PatrolManager` issue attack orders toward that player when within `PATROL_RADIUS` blocks.
- No trade. No gifting. The player cannot recover this relationship without performing at least 3 "attack faction enemy" actions — see §6.2 infamy floor.
- Player receives notification once: *"[LivingWorld] You are now considered an enemy of the Villagers."*

### 7.6 Betrayal multiplier

If a player performs a **negative action** (killing a unit or attacking a building) while currently at **Exalted tier** with that faction, the rep delta is multiplied by **2×** before being applied. Attacking your sworn allies is a betrayal.

**Example**: Exalted player kills a Villager military unit. Normal delta: -30. With betrayal multiplier: -60. This pushes them visibly toward Friendly and, with multiple acts, back to Neutral, without being instant-crushing.

The multiplier only applies when _entering_ the action from Exalted, not when re-applying after the first hit already dropped to Friendly.

---

## 8. Trading System

### 8.1 Gifting (rep farming path)

A **gift** is triggered when the player right-clicks a worker unit at Neutral or above while holding a preferred resource item. This opens a simple "donate" slot (one click = give a stack, no return item).

Preferred gift items per faction:

| Faction | Preferred gifts |
|---------|-----------------|
| Villagers | Wheat, Bread, Raw Iron, Logs (any type), Emeralds |
| Monsters | Bone, Rotten Flesh, Gunpowder, Spider Eye, Coal |
| Piglins | Gold Ingot, Gold Nugget (×9 = 1 ingot equivalent), Nether Wart, Blackstone |

Gifting non-preferred items gives no rep gain (the worker shakes its head). Gifting preferred items: +20 rep, 10-minute cooldown per faction. The cooldown is stored in `ReputationManager` as a `Map<UUID, Map<Faction, Long>>` timestamp.

### 8.2 Vendor trade UI

Triggered by right-clicking any **bot-owned worker unit** when at **Friendly** tier or above. Opens Minecraft's standard `MerchantMenu` populated with `MerchantOffer` objects at runtime. Using the vanilla merchant system means:

- No custom screen rendering required.
- Trade offers "wear out" after a fixed number of uses, then refresh after a cooldown — vanilla behaviour, gives scarcity.
- The player UI is immediately familiar.

The `FactionVendorOffers` class (new, in `com.livingworld.trade`) will hold static factory methods that return the correct `MerchantOffers` list given `(Faction f, RepTier tier)`.

#### Villagers — item list

| Item sold to player | Cost | Available at | Notes |
|---------------------|------|--------------|-------|
| Bread ×8 | 4 Wheat | Friendly | Cheap, sustaining |
| Enchanted Book (Unbreaking III) | 8 Emeralds | Friendly | Consistent useful enchant |
| Iron Sword | 6 Emeralds + 1 Iron Ingot | Friendly | Budget combat gear |
| Potion of Healing II | 5 Emeralds + 1 Glistering Melon | Friendly | |
| Enchanted Book (random tier-2 enchant) | 16 Emeralds | Exalted | Random from: Sharpness IV, Protection III, Power IV, Fortune II, Silk Touch |
| Diamond Sword | 24 Emeralds + 1 Diamond | Exalted | |
| Totem of Undying | 32 Emeralds | Exalted | Expensive but powerful; limited to 1 use per refresh |

**Players sell to Villagers:**

| Item | Price received |
|------|----------------|
| Wheat ×16 | 4 Emeralds |
| Logs ×16 (any) | 3 Emeralds |
| Raw Iron ×8 | 5 Emeralds |
| Diamonds ×1 | 8 Emeralds |

#### Monsters — item list

| Item sold to player | Cost | Available at |
|---------------------|------|--------------|
| Arrows ×32 | 4 Bone + 4 Feather | Friendly |
| Gunpowder ×8 | 4 Rotten Flesh | Friendly |
| Potion of Strength II | 3 Bone + 2 Spider Eye | Friendly |
| Wither Rose ×4 | 8 Bone | Friendly |
| TNT ×4 | 12 Gunpowder + 8 Sand | Exalted |
| Potion of Harming II | 6 Spider Eye + 2 Fermented Spider Eye | Exalted |
| Nether Star | 64 Bone + 32 Rotten Flesh + 16 Spider Eye | Exalted | Extremely expensive; max 1 use per refresh |

**Players sell to Monsters:**

| Item | Price received |
|------|----------------|
| Bone ×16 | 8 Gunpowder |
| Rotten Flesh ×16 | 4 Bone |
| Gunpowder ×4 | 2 Spider Eye |
| Coal ×16 | 4 Bone |

#### Piglins — item list

| Item sold to player | Cost | Available at |
|---------------------|------|--------------|
| Gold Ingot ×4 | 8 Nether Wart | Friendly |
| Fire Resistance Potion | 4 Gold Ingot + 2 Nether Wart | Friendly |
| Obsidian ×4 | 8 Gold Ingot | Friendly |
| Crying Obsidian ×2 | 6 Gold Ingot + 4 Nether Wart | Friendly |
| Netherite Scrap ×1 | 24 Gold Ingot + 16 Nether Wart | Exalted | |
| Potion of Fire Resistance (8:00) | 8 Gold Ingot + 4 Nether Wart | Exalted | Extends the standard 3-minute duration |
| Respawn Anchor | 16 Crying Obsidian + 8 Glowstone | Exalted | |

**Players sell to Piglins:**

| Item | Price received |
|------|----------------|
| Gold Ingot ×4 | 4 Nether Wart |
| Nether Wart ×8 | 2 Gold Ingot |
| Blackstone ×32 | 4 Gold Ingot |
| Nether Brick ×16 | 2 Gold Ingot |

### 8.3 Price scaling with rep

| Tier | Buy price | Sell price |
|------|-----------|------------|
| Friendly | base | base |
| Exalted | -15% (rounded down) | +15% (rounded up) |

The discount is applied in `FactionVendorOffers` when constructing `MerchantOffer` objects. No client-side changes required — the offer simply has lower input item counts.

### 8.4 Hire system

At **Exalted** tier, the trade UI gains a second "page" (additional `MerchantOffer` entries appended after the standard items) offering **spawn eggs** that are pre-assigned to the purchasing player.

A "hired unit" spawn egg works as follows:
1. When the player right-clicks the egg to spawn the unit, the unit's `ownerName` is set to the player's name.
2. The unit behaves exactly as if the player had placed it in RTS mode, with one difference: it follows the player (using a goal similar to `MountHoglin`'s follow logic) rather than requiring RTS commands, since normal players have no RTS HUD.
3. The hired unit is **not** an RTS-mode unit — it can't be commanded via the RTS UI. It is a vanilla-AI mob that is friendly to the player and hostile to the player's enemies (via `AlliancesServerEvents`).

> **Note for implementation**: the "follow player" behaviour for hired units is complex and can be deferred to post-MVP. The MVP version of hiring simply spawns the unit at the player's feet with the correct `ownerName` set; it will defend the player via `getWillRetaliate()` but won't actively follow. Full bodyguard AI is a v2 concern.

#### Hire catalogue

| Faction | Unit | Egg cost | Rep requirement |
|---------|------|----------|-----------------|
| Villagers | VillagerUnit (worker companion) | 16 Emeralds + 8 Wheat | Exalted |
| Villagers | MilitiaUnit | 20 Emeralds + 4 Iron Ingot | Exalted |
| Villagers | IronGolem | 48 Emeralds + 8 Iron Block | Exalted |
| Monsters | ZombieUnit (worker companion) | 8 Bone + 8 Rotten Flesh | Exalted |
| Monsters | SkeletonUnit | 12 Bone + 4 Feather | Exalted |
| Monsters | CreeperUnit | 16 Gunpowder + 8 Bone | Exalted |
| Piglins | GruntUnit (worker companion) | 16 Gold Ingot + 8 Nether Wart | Exalted |
| Piglins | BruteUnit | 20 Gold Ingot + 8 Blackstone | Exalted |
| Piglins | BlazeUnit | 24 Gold Ingot + 8 Blaze Rod | Exalted |

The choice of worker companions as hireable is intentional: they let solo players experience the gathering/building economy at small scale without needing full RTS mode.

Hire offers have a **max uses of 1** and a **restock time of 3 in-game days** (72 000 ticks) so players can't spam-hire armies. Each faction has a total hire cap of **3 units per player** tracked in `ReputationManager`; attempting to hire when at cap removes the offers from the UI.

---

## 9. Notifications

### 9.1 Rep-change action bar message

Any time rep changes, the player receives a **action bar message** (above the hotbar, not in chat) of the form:

```
Rep with Villagers: -100 (Killed worker) | Denounced [-600/1000]
Rep with Monsters: +35 (Attacked their enemy) | Neutral [+70/1000]
```

Action bar messages don't spam the chat log and disappear after ~3 seconds, so frequent small changes (trading) don't fill the screen.

### 9.2 Tier-change chat message

When the rep score **crosses a tier boundary**, a formatted **chat message** is sent to the player:

```
[LivingWorld] Your reputation with the Villagers has changed: Neutral → Suspicious
[LivingWorld] The Villagers now regard you as an enemy. Their patrols will attack on sight.
```

Tier-change messages are always in chat (not just action bar) because they have practical consequences the player must be aware of.

### 9.3 `/livingworld rep` command

New subcommand added to `LivingWorldCommands`. No permission level required (any player can check their own rep). Outputs a formatted table:

```
=== Your Reputation ===
Villagers (Ashvale, Ironholm): Suspicious [-320/1000]
Monsters  (Grimhollow):        Neutral    [+45/1000]
Piglins   (Embercrag):         Accepted   [+180/1000]
```

Village names in parentheses are the active `FactionBot.name()` values for that faction — helpful context but rep is per-faction, not per-village.

Admin variant: `/livingworld rep <playername>` — requires op permission, shows another player's rep.

### 9.4 Visual indicator (post-MVP)

A small particle effect or ambient sound near village boundaries that changes based on your rep tier:
- Friendly/Exalted: green emerald particles drifting from the village centre.
- Hostile: red smoke.
- Neutral/Unfriendly: nothing.

This is intentionally deferred — it requires client-side packet work and the command is sufficient for MVP.

---

## 10. Cross-Faction Rep Propagation

### 10.1 Faction enmity table

These are the "all bots are hostile to each other" relationships already implicit in the code. Rep propagation formalises them:

| Faction you attack | Rep gain with... |
|--------------------|------------------|
| VILLAGERS | +35 MONSTERS, +35 PIGLINS |
| MONSTERS | +35 VILLAGERS, +35 PIGLINS |
| PIGLINS | +35 VILLAGERS, +35 MONSTERS |

The +35 "attack enemy" delta (§5.1) is applied to **both** opposing factions when you kill a military unit. Killing a worker grants the same cross-faction rep as well (you're doing the enemy's other enemies a favour).

### 10.2 Rationale

This makes conflict with one faction genuinely useful for building standing elsewhere. A player who wants to align with Villagers has a clear path: attack Grimhollow and Embercrag. A player who wants to stay neutral with all three factions must avoid all combat — which is interesting tension.

### 10.3 Betrayal awareness

If a player is **Exalted** with Faction A and **Exalted** with Faction B (which are enemies of each other), the betrayal multiplier (§7.6) activates independently for whichever faction they attack. This means maintaining Exalted with all three factions simultaneously is effectively impossible: once you attack one to curry favour with another, you lose Exalted status with the attacked faction.

This is intentional. Faction alignment should be a meaningful choice.

---

## 11. Persistence

### 11.1 `ReputationSaveData` class

Extends `SavedData`. Stored in `overworld().getDataStorage()` under key `"livingworld-reputation-data"`.

NBT structure:
```nbt
{
  players: [
    {
      uuid: "xxxxxxxx-xxxx-xxxx-xxxx-xxxxxxxxxxxx",
      factions: [
        { faction: "VILLAGERS", score: 340, infamyFloor: 0, lastGiftTime: 1234567890L },
        { faction: "MONSTERS",  score: -620, infamyFloor: -620, lastGiftTime: 0L },
        { faction: "PIGLINS",   score: 0, infamyFloor: 0, lastGiftTime: 0L }
      ],
      hireCount: [
        { faction: "VILLAGERS", count: 1 },
        ...
      ]
    }
  ]
}
```

`lastGiftTime` stores the server's `Level.getGameTime()` value when the last gift was given to that faction.

### 11.2 Decay tick integration

Decay runs inside `FactionBotEvents.onLevelTick` at a new `DECAY_TICK_INTERVAL = 24000` (once per in-game day). It iterates all entries in `ReputationSaveData` and applies the ±1 decay per faction per entry. This is intentionally cheap: a server with 100 players × 3 factions = 300 simple integer adjustments per in-game day.

---

## 12. Alliance Integration

### 12.1 Replacing the current auto-ally

Currently `FactionBotEvents.onPlayerJoin` calls `AlliancesServerEvents.addAlliance(bot.name(), playerName)` for every bot. With the rep system, this is replaced by the following logic:

```java
// on join: set rep to 0 if not already stored, then sync alliance state
for (FactionBot bot : FactionBotRegistry.all()) {
    Faction f = bot.faction;
    RepTier tier = ReputationManager.getTier(player.getUUID(), f);
    if (tier.ordinal() >= RepTier.NEUTRAL.ordinal()) {
        AlliancesServerEvents.addAlliance(bot.name(), playerName);
    }
    // Hostile/Unfriendly: no alliance (units will retaliate if provoked)
}
```

Players who have not yet been assigned a rep score default to 0 (Neutral), so first-join behaviour is identical to the current live behaviour: all bots are allied.

### 12.2 Alliance re-sync on tier change

`ReputationManager.adjustReputation` calls a private `syncAllianceForFaction(UUID playerUUID, Faction f)` helper that:
1. Looks up all `FactionBot` instances with `bot.faction == f` in `FactionBotRegistry.all()`.
2. For each: if tier ≥ NEUTRAL, `addAlliance`; if tier < NEUTRAL, `removeAlliance`.

This keeps alliances consistent even if villages spawn or despawn while the player is online.

---

## 13. Implementation Plan

### 13.1 MVP (smallest deliverable with player-facing value)

The MVP proves the concept end-to-end: rep changes, tiers flip, alliance state follows, basic trade works.

**Phase 1 — Data layer** (no gameplay change yet)

- [ ] `RepTier` enum (5 values, `scoreMin`, `displayName(Faction)` method)
- [ ] `ReputationSaveData` (NBT read/write)
- [ ] `ReputationManager` (getReputation, adjustReputation, getTier)
- [ ] Unit test: adjustReputation clamps, tier transitions, infamy floor logic

**Phase 2 — Alliance sync**

- [ ] Replace `FactionBotEvents.onPlayerJoin` auto-ally with rep-aware sync
- [ ] `ReputationManager.syncAllianceForFaction` called on tier change
- [ ] Manual test: join server, rep at 0, units friendly; run `/livingworld rep` shows correct output

**Phase 3 — Negative events**

- [ ] Hook `LivingDeathEvent`: check if killed entity is `WorkerUnit` or non-worker `Unit` owned by a bot; identify killing player; call `adjustReputation`
- [ ] Hook `PlayerContainerEvent.Open` (or `BlockEvent.BreakEvent` on containers): detect if container is inside a bot-owned `BuildingPlacement` footprint; apply theft penalty
- [ ] Notifications: action bar message on rep change, chat message on tier change
- [ ] `/livingworld rep` command

**Phase 4 — Positive events (gift + trade)**

- [ ] Right-click worker unit: detect held item, check gift list, apply gift rep, cooldown check
- [ ] `FactionVendorOffers` with Friendly-tier item lists (no Exalted tier yet)
- [ ] Open `MerchantMenu` on worker right-click when at Friendly+ tier
- [ ] Trade completion hook: apply +8 rep

**Phase 5 — Exalted tier + hiring**

- [ ] Exalted-tier items added to `FactionVendorOffers`
- [ ] Hire tab: spawn egg offers for 3 basic units per faction
- [ ] Hire count tracking and cap enforcement
- [ ] Betrayal multiplier in `adjustReputation`

### 13.2 Full version (post-MVP additions)

- [ ] Passive decay tick in `FactionBotEvents.onLevelTick`
- [ ] Cross-faction rep propagation (§10)
- [ ] Hostile-tier patrol targeting (register hostile players on `FactionBot`, use in `PatrolManager`)
- [ ] "Nearby victory" rep event
- [ ] Village request system (§5.3)
- [ ] Hired unit follow-player AI
- [ ] Visual particle indicators (§9.4)
- [ ] Admin command: `/livingworld rep <player> set <faction> <value>`

### 13.3 Out of scope (explicit no)

The following will not be built as part of Slice 6, no matter how tempting:

- **Per-village rep** — the faction-level model is good enough and avoids village-death bookkeeping.
- **RTS player rep** — RTS players are enemies by design; the binary hostile model stays.
- **GUI redesign** — the vanilla `MerchantMenu` is used as-is. No custom trading screen.
- **Hired unit commands via RTS HUD** — hired units use vanilla AI only; they are not RTS-controlled.
- **Reputation propagation between players** — no "guild rep" or team-shared rep.
- **Stealing detection inside players' personal chests** — only bot-owned buildings trigger theft.

---

## 14. Open Questions (decisions needed before implementation starts)

| # | Question | Recommendation |
|---|----------|----------------|
| 1 | How do we detect that a container is "inside" a bot building? `BuildingPlacement` has `originPos` and a block set. Do we expand an AABB, or tag the chest block directly when the building is placed? | **Tag on placement**: when a `BuildingPlacement` is constructed for a bot, iterate its block set on first tick after `isBuilt = true` and add the building's `ownerName` as a NBT tag to any `ChestBlockEntity` or `BarrelBlockEntity` found. Check that tag in the container open event. |
| 2 | Which event detects "player killed a unit"? `LivingDeathEvent` fires server-side with `DamageSource`. Should we trust `source.getEntity() instanceof ServerPlayer`? | Yes, `source.getDirectEntity()` for projectile kills (bow, trident) and `source.getEntity()` for melee. Check both. |
| 3 | Should gifting require the player to **hold** the item and right-click, or **drop** it near the village centre? | **Right-click worker while holding item** is more discoverable and less accidental. Drop-detection requires a block-radius sweep every tick. |
| 4 | How do hired unit spawn eggs persist across sessions? | Store in the player's inventory as a normal `ItemStack` with NBT tag `{livingworld_hired: 1, owner: "PlayerName", faction: "VILLAGERS"}`. On use, spawn the appropriate unit entity and set `ownerName`. No separate save data needed. |
| 5 | Decay: should a player's rep decay while they are **offline**? | Yes — decay is time-based (game ticks on the server), not session-based. An offline player's rep decays just as slowly as an online one. Server hosts who want to disable decay can toggle it via a new `LivingWorldConfig.REP_DECAY_ENABLED` field. |

---

## Appendix A — Faction Roster Quick Reference

Knowing which units are workers vs military is critical for the kill-event delta.

| Faction | Workers (implements `WorkerUnit`) | Military (non-worker `Unit`) |
|---------|-----------------------------------|------------------------------|
| VILLAGERS | VillagerUnit | MilitiaUnit, PillagerUnit, VindicatorUnit, RoyalGuardUnit, EnchanterUnit, EvokerUnit, WitchUnit, WindcallerUnit, IronGolemUnit, RavagerUnit |
| MONSTERS | ZombieUnit (as worker) | SkeletonUnit, HuskUnit, StrayUnit, BoggedUnit, SpiderUnit, PoisonSpiderUnit, CreeperUnit, SlimeUnit, DrownedUnit, NecromancerUnit, WretchedWraithUnit, WraithUnit, ZoglinUnit, ZombiePiglinUnit, WardenUnit |
| PIGLINS | GruntUnit (as worker) | BruteUnit, HoglinUnit, ArmouredHoglinUnit, HeadhunterUnit, MarauderUnit, BlazeUnit, MagmaCubeUnit, GhastUnit, WildfireUnit, WitherSkeletonUnit, PiglinMerchantUnit |

> **Implementation note**: the kill event handler should check `entity instanceof WorkerUnit` first (worker penalty), then `entity instanceof Unit` (military penalty). Do not apply both.

---

## Appendix B — File Layout

New files this slice adds to `com.livingworld`:

```
reputation/
  RepTier.java              # enum: HOSTILE, UNFRIENDLY, NEUTRAL, FRIENDLY, EXALTED
  ReputationManager.java    # static methods: get/adjust/sync
  ReputationSaveData.java   # SavedData subclass (NBT read/write)
  RepSource.java            # enum of event types (for notifications)
  RepEvents.java            # Forge event handlers: LivingDeathEvent, container open, etc.

trade/
  FactionVendorOffers.java  # static factory: MerchantOffers for (Faction, RepTier)
  TradeInteractionEvents.java # right-click handler on worker units
```

Existing files modified:

```
bot/FactionBotEvents.java      # replace onPlayerJoin auto-ally with rep-aware sync; add decay tick
bot/FactionBot.java            # add Set<UUID> hostilePlayers for patrol targeting
command/LivingWorldCommands.java  # add /livingworld rep subcommand
```
