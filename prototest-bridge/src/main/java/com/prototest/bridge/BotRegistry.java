package com.prototest.bridge;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.bukkit.Bukkit;
import org.bukkit.entity.Player;

/**
 * Players that announced themselves as automated clients.
 *
 * <p>A bot registers itself with {@code /botregister} right after it joins, so an operator can later watch its
 * inventory without having to guess which of the players on the server is the machine. The registry is per session:
 * a bot re-registers when it reconnects, which also keeps a stale name from pointing at a player that took it over.
 */
public final class BotRegistry {

    private final Map<UUID, String> bots = new LinkedHashMap<>();

    /** @return true when this player was not registered yet */
    public synchronized boolean register(Player player) {
        return bots.put(player.getUniqueId(), player.getName()) == null;
    }

    /** @return true when this player was registered */
    public synchronized boolean unregister(Player player) {
        return bots.remove(player.getUniqueId()) != null;
    }

    public synchronized boolean isBot(Player player) {
        return bots.containsKey(player.getUniqueId());
    }

    public synchronized List<String> names() {
        return new ArrayList<>(bots.values());
    }

    /** Registered bots that are online right now, in registration order. */
    public synchronized List<Player> online() {
        List<Player> players = new ArrayList<>();
        for (UUID id : bots.keySet()) {
            Player player = Bukkit.getPlayer(id);
            if (player != null && player.isOnline()) {
                players.add(player);
            }
        }
        return players;
    }

    /** A registered bot by (case-insensitive) name, or null. */
    public synchronized Player find(String name) {
        for (Map.Entry<UUID, String> entry : bots.entrySet()) {
            if (entry.getValue().equalsIgnoreCase(name)) {
                return Bukkit.getPlayer(entry.getKey());
            }
        }
        return null;
    }

    public synchronized void forget(Player player) {
        bots.remove(player.getUniqueId());
    }
}
