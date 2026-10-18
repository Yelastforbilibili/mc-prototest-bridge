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
