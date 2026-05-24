package com.livingworld.util;

import com.solegendary.reignofnether.faction.Faction;
import com.solegendary.reignofnether.player.PlayerServerEvents;
import java.util.Random;
import net.minecraft.core.BlockPos;

/**
 * Generates human-readable, faction-themed village names.
 *
 * <p>Names are derived <em>deterministically</em> from the village's world
 * coordinates so the same village always gets the same name across server
 * restarts. The coordinate pair is hashed into a seed and used to pick from
 * a per-faction name pool.
 *
 * <p>Collision handling: if two villages (improbable but possible) resolve
 * to the same name, we append a roman numeral suffix up to IV, then fall back
 * to the old coord-based name as a guaranteed-unique safety net.
 *
 * <p>The generated name is used as the RoN {@code ownerName} directly, so it
 * shows up in the diplomacy screen, unit tooltips, building hover text, and
 * our own leaderboard/inspector output without any extra mapping layer.
 */
public final class Names {

    private Names() {}

    // --------------------------------------------------------- public API

    /**
     * Generate a unique village name for the given faction and position.
     * Checks {@link PlayerServerEvents#rtsPlayers} for collisions and appends
     * a roman numeral suffix if needed.
     */
    public static String villageBotName(Faction faction, BlockPos centre) {
        String[] pool = poolFor(faction);
        long seed = seed(faction, centre);
        String base = pool[(int) Math.floorMod(seed, pool.length)];

        // Try the plain name first, then with roman numeral suffixes.
        if (isNameFree(base)) return base;
        for (int i = 2; i <= 4; i++) {
            String candidate = base + " " + roman(i);
            if (isNameFree(candidate)) return candidate;
        }
        // Fall back to coord-based as a guaranteed-unique safety net.
        return (
            faction.name().toLowerCase() +
            "_" +
            centre.getX() +
            "_" +
            centre.getZ()
        );
    }

    // --------------------------------------------------------- name pools

    private static final String[] VILLAGER_NAMES = {
        "Ashvale",
        "Ironhold",
        "Stonebridge",
        "Millhaven",
        "Willowcross",
        "Hearthshire",
        "Goldenmere",
        "Oakford",
        "Riverton",
        "Cedarholm",
        "Thornfield",
        "Clearwater",
        "Elmridge",
        "Frostford",
        "Greenvale",
        "Hillshire",
        "Ivywood",
        "Kingsford",
        "Lochwood",
        "Meadowbrook",
        "Northgate",
        "Pebbleford",
        "Ravenhill",
        "Silverwood",
        "Thistledale",
        "Westgate",
        "Briarwood",
        "Coppergate",
        "Dunskeld",
        "Eldermark",
        "Fairhaven",
        "Glenwall",
        "Hammerstone",
        "Ironmere",
        "Jadewatch",
        "Keldmoor",
        "Lorn Keep",
        "Marshgate",
        "Nettleford",
        "Owlswick",
    };

    private static final String[] MONSTER_NAMES = {
        "Shadowmere",
        "Grimhollow",
        "Bonecroft",
        "Darkspire",
        "Ashcrag",
        "Dreadmoor",
        "Vilewatch",
        "Skulkridge",
        "Deathknell",
        "Festermoor",
        "Ghostfen",
        "Havenfall",
        "Jadecrypt",
        "Lichgate",
        "Malfern",
        "Nettlecrag",
        "Putrefax",
        "Rotwatch",
        "Shadowfen",
        "Tombwatch",
        "Withercroft",
        "Deathmere",
        "Gloomhaven",
        "Grimspire",
        "Hexhollow",
        "Ironbone",
        "Blightwatch",
        "Cursemark",
        "Dunecrag",
        "Embermaw",
        "Fellstone",
        "Ghoulfen",
        "Hexmire",
        "Ironblight",
        "Jadescorn",
        "Killstone",
        "Lychmark",
        "Mordenfell",
        "Necrospire",
        "Oathbreaker",
    };

    private static final String[] PIGLIN_NAMES = {
        "Embercrag",
        "Blazehold",
        "Scorchmere",
        "Ashcinder",
        "Magmafen",
        "Sunderstone",
        "Ironscorch",
        "Fiercewall",
        "Cinderveil",
        "Moltengate",
        "Scorchridge",
        "Blazewatch",
        "Emberstone",
        "Ashscorch",
        "Fiercehold",
        "Flamegate",
        "Hardfang",
        "Ironscald",
        "Kinefire",
        "Lavarock",
        "Mouldforge",
        "Pyrehold",
        "Quickfire",
        "Ragestone",
        "Scorchfield",
        "Trailfire",
        "Underblaze",
        "Voidstone",
        "Warstone",
        "Xanthrock",
        "Ashjaw",
        "Brimstone",
        "Cindermaw",
        "Duskscorch",
        "Emberfang",
        "Flinthold",
        "Goldscorch",
        "Hellstone",
        "Ignismark",
        "Jadecinder",
    };

    private static String[] poolFor(Faction faction) {
        return switch (faction) {
            case VILLAGERS -> VILLAGER_NAMES;
            case MONSTERS -> MONSTER_NAMES;
            case PIGLINS -> PIGLIN_NAMES;
            default -> new String[] {faction.name()};
        };
    }

    // ------------------------------------------------------------- helpers

    /** Deterministic seed from faction + coords. */
    private static long seed(Faction faction, BlockPos centre) {
        return (
            ((long) centre.getX() * 0x9E3779B97F4A7C15L) ^
            ((long) centre.getZ() * 0x6C62272E07BB0142L) ^
            faction.ordinal()
        );
    }

    private static boolean isNameFree(String name) {
        return PlayerServerEvents.getRTSPlayer(name) == null;
    }

    private static String roman(int n) {
        return switch (n) {
            case 2 -> "II";
            case 3 -> "III";
            case 4 -> "IV";
            default -> String.valueOf(n);
        };
    }
}
