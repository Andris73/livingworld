package com.livingworld.bot;

import com.solegendary.reignofnether.player.PlayerServerEvents;
import com.solegendary.reignofnether.player.RTSPlayer;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * Tracks all active {@link FactionBot}s on the server.
 *
 * Lifetime is the lifetime of a server session. Persistence across restarts is
 * a separate concern (see {@code LivingWorldSaveData}, not yet implemented).
 */
public final class FactionBotRegistry {

    private static final List<FactionBot> bots = new CopyOnWriteArrayList<>();

    private FactionBotRegistry() {}

    public static List<FactionBot> all() {
        return Collections.unmodifiableList(bots);
    }

    public static void add(FactionBot bot) {
        bots.add(bot);
    }

    public static void remove(FactionBot bot) {
        bots.remove(bot);
        // Also remove from RoN's RTS player list so it stops getting ticked.
        synchronized (PlayerServerEvents.rtsPlayers) {
            PlayerServerEvents.rtsPlayers.removeIf(p -> p.id == bot.rtsPlayer.id);
        }
    }

    public static void clear() {
        // Snapshot to avoid concurrent modification while remove() mutates rtsPlayers.
        for (FactionBot bot : new ArrayList<>(bots)) {
            remove(bot);
        }
    }

    public static boolean isLivingWorldBot(RTSPlayer rtsPlayer) {
        for (FactionBot bot : bots) {
            if (bot.rtsPlayer.id == rtsPlayer.id) return true;
        }
        return false;
    }
}
