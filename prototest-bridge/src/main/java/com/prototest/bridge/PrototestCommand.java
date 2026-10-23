package com.prototest.bridge;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;

import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;

import net.kyori.adventure.text.Component;

/** In-game entry point: {@code /prototest status|url|log|events|trace|help}. */
final class PrototestCommand implements CommandExecutor {

    private final PrototestBridge plugin;

    PrototestCommand(PrototestBridge plugin) {
        this.plugin = plugin;
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        String sub = args.length == 0 ? "status" : args[0].toLowerCase();
        BridgeServer server = plugin.server();
        String url = server == null ? "(not listening)" : server.url();
        switch (sub) {
            case "status" -> {
                int players = plugin.getServer().getOnlinePlayers().size();
                String state = server == null ? "down" : "listening on " + url;
                reply(sender, "prototest-bridge " + plugin.getPluginMeta().getVersion()
                        + " | " + state + " | players=" + players
                        + " | events=" + plugin.events().lastSeq()
                        + " | log=" + plugin.logs().lastSeq());
            }
            case "url" -> reply(sender, url);
            case "log" -> {
                int limit = args.length > 1 ? safeInt(args[1], 10) : 10;
                var entries = plugin.logs().since(0, limit, null);
                for (Object o : entries) {
                    reply(sender, String.valueOf(o));
                }
            }
            case "events" -> {
                int limit = args.length > 1 ? safeInt(args[1], 10) : 10;
                for (Object o : plugin.events().since(0, limit, null)) {
                    reply(sender, String.valueOf(o));
                }
            }
            case "trace" -> {
                String action = args.length > 1 ? args[1].toLowerCase() : "state";
                switch (action) {
                    case "start" -> {
                        String who = args.length > 2 ? args[2] : sender.getName();
                        plugin.events().startTrace(who, 20000);
                        reply(sender, "trace started for " + who);
                    }
                    case "stop" -> {
                        reply(sender, "trace stopped: " + plugin.events().stopTrace());
                    }
                    default -> reply(sender, "trace: " + plugin.events().traceState());
                }
            }
            case "kb" -> {
                KnockbackTuning kb = plugin.knockback();
                String action = args.length > 1 ? args[1].toLowerCase() : "status";
                switch (action) {
                    case "reload" -> {
                        kb.reload();
                        reply(sender, "knockback reloaded: " + kb.describe());
                    }
                    case "on" -> {
                        kb.setEnabled(true);
                        reply(sender, "knockback: " + kb.describe());
                    }
                    case "off" -> {
                        kb.setEnabled(false);
                        reply(sender, "knockback: " + kb.describe());
                    }
                    case "status" -> reply(sender, "knockback: " + kb.describe());
                    default -> {
                        double value;
                        try {
                            value = Double.parseDouble(args[1].replace("x", ""));
                        } catch (NumberFormatException e) {
                            reply(sender, "/prototest kb <倍率> | on | off | reload | status");
                            return true;
                        }
                        kb.setMultiplier(value);
                        reply(sender, "knockback: " + kb.describe());
                    }
                }
            }
            case "selfcheck" -> selfcheck(sender, url);
            default -> reply(sender,
                    "/prototest status | url | log [n] | events [n] | trace start|stop|state [player] | kb [倍率|on|off|reload] | selfcheck");
        }
        return true;
    }

    /**
     * Hits the bridge over loopback and prints the HTTP status, which proves the request path works end to end from
     * inside the JVM. Runs on a plain thread: the HTTP handler hops to the main thread itself.
     */
    private void selfcheck(CommandSender sender, String url) {
        if (url.startsWith("(")) {
            reply(sender, "bridge is not listening");
            return;
        }
        Thread t = new Thread(() -> {
            String result;
            try {
                HttpClient client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();
                HttpRequest req = HttpRequest.newBuilder(URI.create(url + "/status"))
                        .timeout(Duration.ofSeconds(10)).GET().build();
                HttpResponse<String> resp = client.send(req, HttpResponse.BodyHandlers.ofString());
                String body = resp.body();
                result = "HTTP " + resp.statusCode() + " " + (body.length() > 300 ? body.substring(0, 300) + "..." : body);
            } catch (Exception e) {
                result = "selfcheck failed: " + e;
            }
            String msg = result;
            plugin.getServer().getScheduler().runTask(plugin, () -> reply(sender, msg));
        }, "prototest-selfcheck");
        t.setDaemon(true);
        t.start();
        reply(sender, "selfcheck requested against " + url + "/status");
    }

    private static int safeInt(String s, int dflt) {
        try {
            return Integer.parseInt(s.trim());
        } catch (NumberFormatException e) {
            return dflt;
        }
    }

    private void reply(CommandSender sender, String text) {
        sender.sendMessage(Component.text(text));
    }
}
