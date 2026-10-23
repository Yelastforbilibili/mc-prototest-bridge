package com.prototest.bridge;

import java.io.IOException;
import java.util.logging.Logger;

import org.bukkit.command.PluginCommand;
import org.bukkit.plugin.java.JavaPlugin;

/**
 * Server-side probe for an automated Python Minecraft client.
 *
 * <p>Two things are exposed:
 * <ul>
 *   <li>a read-only view of live server state over loopback HTTP ({@link BridgeServer}), so a test can assert what the
 *       server actually thinks happened instead of asking the client to grade its own homework;</li>
 *   <li>event "truth" recorded independently of what the client claimed ({@link EventRecorder}), plus the console log
 *       tail ({@link LogCapture}), so protocol-level failures show up as server-side symptoms.</li>
 * </ul>
 */
public final class PrototestBridge extends JavaPlugin {

    private final BridgeState state = new BridgeState(this);
    private final EventRecorder events = new EventRecorder(this);
    private final LogCapture logs = new LogCapture();
    private final PacketTap tap = new PacketTap(this);
    private final BotRegistry bots = new BotRegistry();
    private final SpectatorService spectate = new SpectatorService(this);
    private final KnockbackTuning knockback = new KnockbackTuning(this);

    private BridgeServer server;
    private long startedAt;

    @Override
    public void onEnable() {
        startedAt = System.currentTimeMillis();
        saveDefaultConfig();

        logs.install();
        // Paper's delayed init can reset the JUL log manager after plugins enable, which drops the
        // handler silently; re-attach it every second so the ring keeps seeing console output.
        getServer().getScheduler().runTaskTimer(this, logs::ensureInstalled, 20L, 20L);
        getServer().getPluginManager().registerEvents(events, this);

        // Keep the spawn chunks resident: with no player online, a flat world keeps nothing loaded and
        // every console command that touches a position answers "That position is not loaded".
        for (org.bukkit.World world : getServer().getWorlds()) {
            try {
                world.setKeepSpawnInMemory(true);
            } catch (Throwable t) {
                getLogger().warning("could not keep spawn in memory for " + world.getName() + ": " + t);
            }
        }

        PluginCommand cmd = getCommand("prototest");
        if (cmd != null) {
            cmd.setExecutor(new PrototestCommand(this));
        }

        // Watching a bot's inventory: the bot registers itself with /botregister, an operator opens a live view
        // with /see. Both the registry and the service are session scoped on purpose -- a reconnect re-registers.
        SpectatorCommand spectator = new SpectatorCommand(this, bots, spectate);
        for (String name : new String[] {"see", "seepattern", "botregister", "botunregister"}) {
            PluginCommand spectatorCommand = getCommand(name);
            if (spectatorCommand == null) {
                getLogger().warning("command " + name + " is missing from plugin.yml");
                continue;
            }
            spectatorCommand.setExecutor(spectator);
            spectatorCommand.setTabCompleter(spectator);
        }
        getServer().getPluginManager().registerEvents(spectate, this);
        spectate.start();

        // Knockback tuning: the multiplier is applied to the impulse before it leaves the server, and every scaled
        // impulse is watched long enough to tell "moved and stayed" from "got set back to where it stood".
        getServer().getPluginManager().registerEvents(knockback, this);
        knockback.startClock();
        knockback.reload();

        // Join/quit keep the packet tap attached; players already online (this happens on a plugin reload) are
        // picked up once, here.
        getServer().getPluginManager().registerEvents(new org.bukkit.event.Listener() {
            @org.bukkit.event.EventHandler
            public void onJoin(org.bukkit.event.player.PlayerJoinEvent event) {
                getServer().getScheduler().runTaskLater(PrototestBridge.this, () -> tap.attach(event.getPlayer()), 1L);
            }

            @org.bukkit.event.EventHandler
            public void onQuit(org.bukkit.event.player.PlayerQuitEvent event) {
                tap.detach(event.getPlayer());
            }
        }, this);
        getServer().getScheduler().runTaskLater(this, tap::attachOnline, 1L);

        String host = getConfig().getString("host", "127.0.0.1");
        int port = getConfig().getInt("port", 25963);
        String token = getConfig().getString("token", "");
        boolean debug = getConfig().getBoolean("debug", false);

        server = new BridgeServer(this, host, port, token, debug);
        try {
            server.start();
            getLogger().info("prototest-bridge ready: " + server.url() + " (v" + getPluginMeta().getVersion() + ")");
        } catch (IOException e) {
            getLogger().severe("prototest-bridge could not bind " + host + ":" + port + ": " + e.getMessage());
            server = null;
        }
    }

    @Override
    public void onDisable() {
        spectate.stop();
        if (server != null) {
            server.stop();
            server = null;
        }
        logs.uninstall();
    }

    BridgeState state() {
        return state;
    }

    EventRecorder events() {
        return events;
    }

    LogCapture logs() {
        return logs;
    }

    PacketTap tap() {
        return tap;
    }

    BotRegistry bots() {
        return bots;
    }

    SpectatorService spectate() {
        return spectate;
    }

    KnockbackTuning knockback() {
        return knockback;
    }

    BridgeServer server() {
        return server;
    }

    long startedAt() {
        return startedAt;
    }

    Logger logger() {
        return getLogger();
    }
}
