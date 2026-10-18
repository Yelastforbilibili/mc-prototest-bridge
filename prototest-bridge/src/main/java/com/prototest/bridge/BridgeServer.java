package com.prototest.bridge;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import org.bukkit.Bukkit;
import org.bukkit.OfflinePlayer;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.block.data.BlockData;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

/**
 * A tiny JSON-over-HTTP bridge onto the live server, bound to loopback by default.
 *
 * <p>Everything that touches game state runs on the main thread: a request thread hands a {@link Callable} to the
 * server scheduler and waits with a timeout, so the bridge can never deadlock the tick loop.
 */
final class BridgeServer {

    private static final int SYNC_TIMEOUT_MS = 15_000;
    private static final long MAX_REGION_BLOCKS = 200_000;

    private final PrototestBridge plugin;
    private final String host;
    private final int configuredPort;
    private final String token;
    private final boolean debug;

    private HttpServer http;
    private ExecutorService pool;

    BridgeServer(PrototestBridge plugin, String host, int port, String token, boolean debug) {
        this.plugin = plugin;
        this.host = host;
        this.configuredPort = port;
        this.token = token == null ? "" : token;
        this.debug = debug;
    }

    int port() {
        return http == null ? configuredPort : http.getAddress().getPort();
    }

    String url() {
        String h = "0.0.0.0".equals(host) || host.isEmpty() ? "127.0.0.1" : host;
        return "http://" + h + ":" + port();
    }

    void start() throws IOException {
        http = HttpServer.create(new InetSocketAddress(host, configuredPort), 64);
        pool = Executors.newFixedThreadPool(4, r -> {
            Thread t = new Thread(r, "prototest-bridge-http");
            t.setDaemon(true);
            return t;
        });
        http.setExecutor(pool);
        http.createContext("/", this::handle);
        http.start();
        plugin.logger().info("prototest bridge listening on " + url() + (token.isEmpty() ? "" : " (token required)"));
    }

    void stop() {
        if (http != null) {
            http.stop(0);
            http = null;
        }
        if (pool != null) {
            pool.shutdownNow();
            pool = null;
        }
    }

    // ------------------------------------------------------------------ dispatch

    private void handle(HttpExchange ex) throws IOException {
        long t0 = System.nanoTime();
        try {
            String path = ex.getRequestURI().getPath();
            if (path.length() > 1 && path.endsWith("/")) {
                path = path.substring(0, path.length() - 1);
            }
            Map<String, String> q = query(ex);
            ex.getResponseHeaders().add("Access-Control-Allow-Origin", "*");
            ex.getResponseHeaders().add("Access-Control-Allow-Headers", "*");
            ex.getResponseHeaders().add("Access-Control-Allow-Methods", "GET,POST,OPTIONS");
            ex.getResponseHeaders().add("Cache-Control", "no-store");
            if ("OPTIONS".equalsIgnoreCase(ex.getRequestMethod())) {
                send(ex, 204, "", "text/plain");
                return;
            }
            if (!token.isEmpty()) {
                String got = ex.getRequestHeaders().getFirst("X-Bridge-Token");
                if (got == null) {
                    got = q.get("token");
                }
                if (!token.equals(got)) {
                    send(ex, 401, "{\"ok\":false,\"error\":\"bad or missing token\"}", "application/json");
                    return;
                }
            }
            String method = ex.getRequestMethod().toUpperCase(Locale.ROOT);
            Route r = new Route(path, method, q, method.equals("POST") ? body(ex) : Map.of());
            Response out = dispatch(r);
            if (debug) {
                long ms = (System.nanoTime() - t0) / 1_000_000;
                plugin.logger().info("[bridge] " + method + " " + path + " -> " + out.status() + " (" + ms + "ms)");
            }
            send(ex, out.status(), out.body(), out.contentType());
        } catch (Throwable t) {
            plugin.logger().warning("[bridge] request failed: " + t);
            send(ex, 500, Json.encode(Json.map("ok", false, "error", String.valueOf(t))), "application/json");
        } finally {
            ex.close();
        }
    }

    private Response dispatch(Route r) {
        String p = r.path();
        if (p.isEmpty() || p.equals("/")) {
            return Response.json(doc());
        }
        switch (p) {
            case "/status":
                return syncJson(() -> {
                    Map<String, Object> m = new LinkedHashMap<>(plugin.state().status());
                    m.put("ok", true);
                    m.put("url", url());
                    m.put("endpoints", doc().get("endpoints"));
                    return m;
                });
            case "/players":
                return syncJson(() -> {
                    List<Object> list = new ArrayList<>();
                    for (Player pl : Bukkit.getOnlinePlayers()) {
                        list.add(plugin.state().playerBrief(pl));
                    }
                    Map<String, Object> m = new LinkedHashMap<>();
                    m.put("ok", true);
                    m.put("count", list.size());
                    m.put("players", list);
                    return m;
                });
            case "/player":
                return withPlayer(r, (p1, m) -> {
                    m.put("player", plugin.state().player(p1));
                    m.put("inventory", plugin.state().inventory(p1));
                });
            case "/inventory":
                return withPlayer(r, (p1, m) -> {
                    m.put("inventory", plugin.state().inventory(p1));
                    m.put("equipment", plugin.state().equipmentSlots(p1));
                    m.put("heldItem", plugin.state().item(p1.getInventory().getItemInMainHand()));
                });
            case "/block":
                return syncJson(() -> {
                    Block b = resolveBlock(r);
                    return Json.map("ok", true, "block", plugin.state().block(b));
                });
            case "/blocks":
                return syncJson(() -> {
                    World w = resolveWorld(String.valueOf(r.req("world", null)));
                    Object list = r.body().get("blocks");
                    if (!(list instanceof List<?> coords)) {
                        return Json.map("ok", false, "error", "body needs {\"blocks\":[[x,y,z],...]}");
                    }
                    List<Object> out = new ArrayList<>();
                    for (Object o : coords) {
                        if (o instanceof List<?> c && c.size() >= 3) {
                            int bx = ((Number) c.get(0)).intValue();
                            int by = ((Number) c.get(1)).intValue();
                            int bz = ((Number) c.get(2)).intValue();
                            loadChunkAt(w, bx, bz);
                            Block b = w.getBlockAt(bx, by, bz);
                            out.add(plugin.state().block(b));
                        }
                    }
                    return Json.map("ok", true, "world", w.getName(), "blocks", out);
                });
            case "/region":
                return syncJson(() -> {
                    World w = resolveWorld(String.valueOf(r.req("world", null)));
                    int x = r.reqInt("x", 0), y = r.reqInt("y", 0), z = r.reqInt("z", 0);
                    int dx = r.reqInt("dx", 8), dy = r.reqInt("dy", 8), dz = r.reqInt("dz", 8);
                    if ((long) dx * dy * dz > MAX_REGION_BLOCKS) {
                        return Json.map("ok", false, "error", "region too big: " + dx + "x" + dy + "x" + dz);
                    }
                    for (int cx = x >> 4; cx <= (x + dx - 1) >> 4; cx++) {
                        for (int cz = z >> 4; cz <= (z + dz - 1) >> 4; cz++) {
                            loadChunkAt(w, cx << 4, cz << 4);
                        }
                    }
                    return Json.map("ok", true, "world", w.getName(),
                            "region", plugin.state().region(w, x, y, z, dx, dy, dz));
                });
            case "/setblock":
                return syncJson(() -> {
                    World w = resolveWorld(String.valueOf(r.req("world", null)));
                    int x = r.reqInt("x", 0), y = r.reqInt("y", 0), z = r.reqInt("z", 0);
                    Object data = r.body().getOrDefault("data", r.req("data"));
                    if (data == null) {
                        return Json.map("ok", false, "error", "missing data (e.g. minecraft:stone)");
                    }
                    BlockData bd;
                    try {
                        bd = Bukkit.createBlockData(String.valueOf(data));
                    } catch (IllegalArgumentException e) {
                        return Json.map("ok", false, "error", "bad block data: " + data);
                    }
                    loadChunkAt(w, x, z);
                    Block b = w.getBlockAt(x, y, z);
                    b.setBlockData(bd, false);
                    return Json.map("ok", true, "world", w.getName(), "set", bd.getAsString(),
                            "block", plugin.state().block(b));
                });
            case "/fill":
                return syncJson(() -> {
                    World w = resolveWorld(String.valueOf(r.req("world", null)));
                    int x1 = r.reqInt("x1", 0), y1 = r.reqInt("y1", 0), z1 = r.reqInt("z1", 0);
                    int x2 = r.reqInt("x2", 0), y2 = r.reqInt("y2", 0), z2 = r.reqInt("z2", 0);
                    Object data = r.body().getOrDefault("data", r.req("data"));
                    if (data == null) {
                        return Json.map("ok", false, "error", "missing data");
                    }
                    long volume = (long) (Math.abs(x2 - x1) + 1) * (Math.abs(y2 - y1) + 1) * (Math.abs(z2 - z1) + 1);
                    if (volume > MAX_REGION_BLOCKS) {
                        return Json.map("ok", false, "error", "fill too big: " + volume + " > " + MAX_REGION_BLOCKS);
                    }
                    BlockData bd = Bukkit.createBlockData(String.valueOf(data));
                    long n = 0;
                    for (int cx = Math.min(x1, x2) >> 4; cx <= Math.max(x1, x2) >> 4; cx++) {
                        for (int cz = Math.min(z1, z2) >> 4; cz <= Math.max(z1, z2) >> 4; cz++) {
                            if (!w.isChunkLoaded(cx, cz)) {
                                w.getChunkAt(cx, cz);
                            }
                        }
                    }
                    for (int x = Math.min(x1, x2); x <= Math.max(x1, x2); x++) {
                        for (int y = Math.min(y1, y2); y <= Math.max(y1, y2); y++) {
                            for (int z = Math.min(z1, z2); z <= Math.max(z1, z2); z++) {
                                w.getBlockAt(x, y, z).setBlockData(bd, false);
                                n++;
                            }
                        }
                    }
                    return Json.map("ok", true, "world", w.getName(), "data", bd.getAsString(), "blocks", n);
                });
            case "/chunk":
                return syncJson(() -> {
                    World w = resolveWorld(String.valueOf(r.req("world", null)));
                    int cx = r.reqInt("x", 0), cz = r.reqInt("z", 0);
                    boolean load = !"0".equals(r.req("load", "1"));
                    boolean unload = "1".equals(r.req("unload", "0"));
                    boolean wasLoaded = w.isChunkLoaded(cx, cz);
                    if (unload) {
                        w.unloadChunk(cx, cz, false);
                        return Json.map("ok", true, "world", w.getName(), "cx", cx, "cz", cz,
                                "wasLoaded", wasLoaded, "loaded", w.isChunkLoaded(cx, cz), "action", "unload");
                    }
                    if (load && !wasLoaded) {
                        w.getChunkAt(cx, cz);
                    }
                    return Json.map("ok", true, "world", w.getName(), "cx", cx, "cz", cz,
                            "wasLoaded", wasLoaded, "loaded", w.isChunkLoaded(cx, cz),
                            "action", load ? "load" : "probe");
                });
            case "/section":
                return syncJson(() -> {
                    World w = resolveWorld(String.valueOf(r.req("world", null)));
                    int x = r.reqInt("x", 0), y = r.reqInt("y", 0), z = r.reqInt("z", 0);
                    loadChunkAt(w, x, z);
                    return Json.map("ok", true, "world", w.getName(), "x", x, "y", y, "z", z,
                            "order", "(y*16+z)*16+x",
                            "ids", plugin.state().sectionIds(w, x, y, z));
                });
            case "/entities":
                return syncJson(() -> {
                    World w = resolveWorld(String.valueOf(r.req("world", null)));
                    return Json.map("ok", true, "world", w.getName(), "entities", plugin.state().entities(w,
                            r.reqDouble("x", 0), r.reqDouble("y", 0), r.reqDouble("z", 0), r.reqDouble("radius", 16)));
                });
            case "/worlds":
                return syncJson(() -> {
                    List<Object> list = new ArrayList<>();
                    for (World w : Bukkit.getWorlds()) {
                        list.add(plugin.state().world(w));
                    }
                    return Json.map("ok", true, "worlds", list);
                });
            case "/world":
                return syncJson(() -> {
                    World w = resolveWorld(String.valueOf(r.req("name", null)));
                    return Json.map("ok", true, "world", plugin.state().world(w));
                });
            case "/world/keepspawn":
                return syncJson(() -> {
                    World w = resolveWorld(String.valueOf(r.req("name", null)));
                    boolean keep = !"0".equals(r.req("keep", "1"));
                    if (w.isGameRule("spawnChunkRadius")) {
                        w.setGameRuleValue("spawnChunkRadius", keep ? "3" : "0");
                    }
                    w.setKeepSpawnInMemory(keep);
                    return Json.map("ok", true, "world", w.getName(), "keepSpawnLoaded", w.getKeepSpawnInMemory(),
                            "spawnChunkRadius", w.isGameRule("spawnChunkRadius") ? w.getGameRuleValue("spawnChunkRadius") : null,
                            "loadedChunks", w.getLoadedChunks().length,
                            "forceLoadedChunks", w.getForceLoadedChunks().size());
                });
            case "/world/forceload":
                return syncJson(() -> {
                    World w = resolveWorld(String.valueOf(r.req("name", null)));
                    int cx = r.reqInt("cx", 0);
                    int cz = r.reqInt("cz", 0);
                    int radius = Math.min(r.reqInt("radius", 1), 16);
                    boolean force = !"0".equals(r.req("force", "1"));
                    int touched = 0;
                    for (int x = cx - radius; x <= cx + radius; x++) {
                        for (int z = cz - radius; z <= cz + radius; z++) {
                            w.setChunkForceLoaded(x, z, force);
                            touched++;
                        }
                    }
                    return Json.map("ok", true, "world", w.getName(), "touched", touched, "force", force,
                            "forceLoadedChunks", w.getForceLoadedChunks().size(),
                            "loadedChunks", w.getLoadedChunks().length);
                });
            case "/spawn":
                return syncJson(() -> {
                    World w = resolveWorld(String.valueOf(r.req("world", null)));
                    if (r.req("x") != null) {
                        w.setSpawnLocation(r.reqInt("x", 0), r.reqInt("y", 0), r.reqInt("z", 0),
                                (float) r.reqDouble("yaw", 0));
                    }
                    return Json.map("ok", true, "world", w.getName(), "spawn", plugin.state().world(w).get("spawn"),
                            "keepSpawnLoaded", w.getKeepSpawnInMemory());
                });
            case "/command":
                return runCommand(r);
            case "/log":
                return syncJson(() -> {
                    long since = (long) r.reqDouble("since", 0);
                    List<Object> entries = plugin.logs().since(since, r.reqInt("limit", 200), r.req("pattern"));
                    return Json.map("ok", true, "since", since, "next", plugin.logs().lastSeq(),
                            "count", entries.size(), "entries", entries);
                });
            case "/log/file":
                return Response.json(Json.map("ok", true, "lines", plugin.logs().tailFile(r.reqInt("tail", 100))));
            case "/events":
                return syncJson(() -> {
                    long since = r.req("since") == null ? 0 : (long) r.reqDouble("since", 0);
                    List<Object> entries = plugin.events().since(since, r.reqInt("limit", 200), r.req("type"));
                    return Json.map("ok", true, "since", since, "next", plugin.events().lastSeq(),
                            "count", entries.size(), "entries", entries);
                });
            case "/events/clear":
                return Response.json(Json.map("ok", true, "cleared", plugin.events().clear()));
            case "/trace":
                return Response.json(Json.map("ok", true, "trace", plugin.events().traceState()));
            case "/trace/start":
                return syncJson(() -> {
                    String name = r.req("player");
                    if (name == null || name.isEmpty()) {
                        return Json.map("ok", false, "error", "missing ?player=");
                    }
                    return Json.map("ok", true, "trace", plugin.events().startTrace(name, r.reqInt("limit", 20000)));
                });
            case "/trace/stop":
                return Response.json(Json.map("ok", true, "trace", plugin.events().stopTrace()));
            case "/trace/data":
                return Response.json(Json.map("ok", true, "state", plugin.events().traceState(),
                        "samples", plugin.events().traceData(r.reqInt("limit", 100000))));
            case "/registry/blocks":
                return Response.text(plugin.state().blockStates(isFresh(r)));
            case "/registry/items":
                return Response.text(plugin.state().items(isFresh(r)));
            case "/registry/entities":
                return Response.text(plugin.state().entityTypes());
            case "/registry/item":
                return Response.json(Json.map("ok", true, "item", plugin.state().itemTypeDetail(r.req("key"))));
            case "/registry/block":
                return Response.json(Json.map("ok", true, "block", plugin.state().blockTypeDetail(r.req("key"))));
            case "/op":
                return syncJson(() -> {
                    String name = String.valueOf(r.body().getOrDefault("name", r.req("name", "")));
                    if (name.isEmpty()) {
                        return Json.map("ok", false, "error", "missing name");
                    }
                    OfflinePlayer off = Bukkit.getOfflinePlayer(name);
                    off.setOp(true);
                    Player online = Bukkit.getPlayerExact(name);
                    if (online != null) {
                        online.setWhitelisted(true);
                    }
                    return Json.map("ok", true, "player", name, "uuid", off.getUniqueId().toString(),
                            "op", off.isOp(), "online", online != null);
                });
            case "/tick/wait":
                return waitTicks(r);
            default:
                if (p.startsWith("/player/") || p.startsWith("/hand/")) {
                    String name = p.substring(p.lastIndexOf('/') + 1);
                    return syncJson(() -> {
                        Player pl = resolvePlayer(name);
                        if (pl == null) {
                            return Json.map("ok", false, "error", "no such online player: " + name);
                        }
                        return Json.map("ok", true, "player", plugin.state().player(pl),
                                "inventory", plugin.state().inventory(pl));
                    });
                }
                return bad("unknown endpoint: " + p, 404);
        }
    }

    // ------------------------------------------------------------------- helpers

    private Response runCommand(Route r) {
        Object cmd = r.body().getOrDefault("command", r.req("command"));
        if (cmd == null) {
            return bad("missing command");
        }
        String as = String.valueOf(r.body().getOrDefault("as", r.req("as", "")));
        final String command = String.valueOf(cmd);
        return syncJson(() -> {
            Player player = as == null || as.isEmpty() || "null".equals(as) ? null : Bukkit.getPlayerExact(as);
            List<Object> lines = new ArrayList<>();
            long before = plugin.logs().lastSeq();
            boolean ok;
            String who;
            if (player != null) {
                // Dispatched as the real player so entity-scoped commands (tp, give, ...) resolve normally.
                ok = player.performCommand(command);
                who = player.getName();
            } else {
                // A sender built by Paper itself: a hand-written CommandSender is rejected as
                // "Cannot make ... a vanilla command listener".
                org.bukkit.command.CommandSender console = Bukkit.createCommandSender(
                        component -> lines.add(Plain.of(component)));
                ok = Bukkit.dispatchCommand(console, command);
                who = "CONSOLE";
            }
            List<Object> logTail = new ArrayList<>();
            for (Object entry : plugin.logs().since(before, 50, null)) {
                Map<String, Object> m = Json.asMap(entry);
                logTail.add(Json.map("level", Json.str(m, "level", ""), "source", Json.str(m, "source", ""),
                        "msg", Json.str(m, "msg", "")));
            }
            return Json.map("ok", true, "command", command, "as", who, "dispatched", ok,
                    "output", lines, "log", logTail);
        });
    }

    private Response waitTicks(Route r) {
        final int ticks = Math.max(1, r.reqInt("ticks", 1));
        final java.util.concurrent.CountDownLatch latch = new java.util.concurrent.CountDownLatch(1);
        Bukkit.getScheduler().runTaskLater(plugin, latch::countDown, ticks);
        try {
            if (!latch.await(SYNC_TIMEOUT_MS, TimeUnit.MILLISECONDS)) {
                return bad("tick wait timed out", 504);
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return bad("interrupted", 500);
        }
        return Response.json(Json.map("ok", true, "waitedTicks", ticks, "tick", Bukkit.getCurrentTick()));
    }

    private interface PlayerFiller {
        void fill(Player p, Map<String, Object> out);
    }

    private Response withPlayer(Route r, PlayerFiller filler) {
        String name = r.req("name");
        if (name == null || name.isEmpty()) {
            return bad("missing ?name=");
        }
        return syncJson(() -> {
            Player p = resolvePlayer(name);
            if (p == null) {
                return Json.map("ok", false, "error", "no such online player: " + name);
            }
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("ok", true);
            filler.fill(p, m);
            return m;
        });
    }

    private boolean isFresh(Route r) {
        String f = r.req("fresh");
        return "1".equals(f) || "true".equalsIgnoreCase(f);
    }

    private Block resolveBlock(Route r) {
        World w = resolveWorld(r.req("world", null));
        int x = r.reqInt("x", Integer.MIN_VALUE);
        int y = r.reqInt("y", Integer.MIN_VALUE);
        int z = r.reqInt("z", Integer.MIN_VALUE);
        if (x == Integer.MIN_VALUE || y == Integer.MIN_VALUE || z == Integer.MIN_VALUE) {
            throw new IllegalArgumentException("missing x,y,z");
        }
        loadChunkAt(w, x, z);
        return w.getBlockAt(x, y, z);
    }

    /**
     * Force-load the chunk holding (x, z). A flat test world keeps no chunks loaded, so reads and writes
     * would otherwise see the unloaded "void" copy and vanilla commands answer "That position is not loaded".
     */
    private static void loadChunkAt(World w, int x, int z) {
        if (!w.isChunkLoaded(x >> 4, z >> 4)) {
            w.getChunkAt(x >> 4, z >> 4);
        }
    }

    private static Player resolvePlayer(String nameOrUuid) {
        if (nameOrUuid == null || nameOrUuid.isEmpty()) {
            return null;
        }
        Player p = Bukkit.getPlayerExact(nameOrUuid);
        if (p != null) {
            return p;
        }
        try {
            p = Bukkit.getPlayer(java.util.UUID.fromString(nameOrUuid));
        } catch (IllegalArgumentException ignored) {
            // not a uuid
        }
        if (p != null) {
            return p;
        }
        for (Player online : Bukkit.getOnlinePlayers()) {
            if (online.getName().equalsIgnoreCase(nameOrUuid)) {
                return online;
            }
        }
        return null;
    }

    private static World resolveWorld(String name) {
        if (name != null && !name.isEmpty() && !"null".equals(name)) {
            World w = Bukkit.getWorld(name);
            if (w != null) {
                return w;
            }
        }
        if (Bukkit.getWorlds().isEmpty()) {
            throw new IllegalStateException("no worlds loaded");
        }
        return Bukkit.getWorlds().get(0);
    }

    private Map<String, Object> doc() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("ok", true);
        m.put("plugin", "prototest-bridge");
        m.put("version", plugin.getPluginMeta().getVersion());
        m.put("mc", Bukkit.getMinecraftVersion());
        m.put("endpoints", List.of(
                "GET  /status",
                "GET  /players",
                "GET  /player?name=",
                "GET  /inventory?name=",
                "GET  /block?world=&x=&y=&z=",
                "POST /blocks {world, blocks:[[x,y,z],...]}",
                "GET  /region?world=&x=&y=&z=&dx=&dy=&dz=",
                "GET  /section?world=&x=&y=&z=  (4096 vanilla state ids of one 16^3 section)",
                "POST /setblock {world,x,y,z,block}  -> authoritatively applies + reports stateId",
                "POST /fill {world,x,y,z,dx,dy,dz,block}  (cap 32768 blocks)",
                "GET  /chunk?world=&cx=&cz=  (16x16x384 flattened state ids)",
                "GET  /entities?world=&x=&y=&z=&radius=",
                "GET  /worlds",
                "GET  /world?name=",
                "GET  /world/keepspawn?name=&keep=  -> also spawnChunkRadius when supported",
                "GET  /world/forceload?name=&cx=&cz=&radius=&force=",
                "GET  /spawn?world=&x=&y=&z=",
                "POST /command {command, as?}",
                "GET  /log?since=&limit=&pattern=",
                "GET  /log/file?name=&tail=",
                "GET  /events?since=&limit=&type=",
                "POST /events/clear",
                "GET  /trace | POST /trace/start?player=&limit= | POST /trace/stop | GET /trace/data",
                "GET  /registry/blocks?fresh=",
                "GET  /registry/items?fresh=",
                "GET  /registry/entities",
                "GET  /registry/item?key=",
                "GET  /registry/block?key=",
                "POST /op {name}",
                "POST /tick/wait?ticks="));
        return m;
    }

    private Response syncJson(Callable<Map<String, Object>> c) {
        try {
            return Response.json(sync(c));
        } catch (RuntimeException e) {
            return bad(String.valueOf(e.getMessage()), 500);
        }
    }

    private <T> T sync(Callable<T> c) {
        if (Bukkit.isPrimaryThread()) {
            try {
                return c.call();
            } catch (Exception e) {
                throw new RuntimeException(e);
            }
        }
        Future<T> f = Bukkit.getScheduler().callSyncMethod(plugin, c);
        try {
            return f.get(SYNC_TIMEOUT_MS, TimeUnit.MILLISECONDS);
        } catch (TimeoutException e) {
            throw new RuntimeException("timed out waiting for the server thread");
        } catch (ExecutionException e) {
            Throwable cause = e.getCause() == null ? e : e.getCause();
            throw new RuntimeException(String.valueOf(cause), cause);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new RuntimeException("interrupted");
        }
    }

    private static Response bad(String msg) {
        return bad(msg, 400);
    }

    private static Response bad(String msg, int status) {
        return new Response(status, Json.encode(Json.map("ok", false, "error", msg)), "application/json");
    }

    private static Map<String, String> query(HttpExchange ex) {
        Map<String, String> m = new LinkedHashMap<>();
        String raw = ex.getRequestURI().getRawQuery();
        if (raw == null || raw.isEmpty()) {
            return m;
        }
        for (String pair : raw.split("&")) {
            int i = pair.indexOf('=');
            String k = i < 0 ? pair : pair.substring(0, i);
            String v = i < 0 ? "" : pair.substring(i + 1);
            m.put(URLDecoder.decode(k, StandardCharsets.UTF_8), URLDecoder.decode(v, StandardCharsets.UTF_8));
        }
        return m;
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> body(HttpExchange ex) {
        try (InputStream in = ex.getRequestBody()) {
            String text = new String(in.readAllBytes(), StandardCharsets.UTF_8);
            if (text.isBlank()) {
                return new LinkedHashMap<>();
            }
            Object parsed = Json.parse(text);
            return parsed instanceof Map ? (Map<String, Object>) parsed : new LinkedHashMap<>();
        } catch (Exception e) {
            return new LinkedHashMap<>();
        }
    }

    private static void send(HttpExchange ex, int status, String body, String contentType) throws IOException {
        byte[] bytes = body == null ? new byte[0] : body.getBytes(StandardCharsets.UTF_8);
        ex.getResponseHeaders().set("Content-Type", contentType + "; charset=utf-8");
        ex.sendResponseHeaders(status, bytes.length == 0 ? -1 : bytes.length);
        if (bytes.length > 0) {
            try (OutputStream out = ex.getResponseBody()) {
                out.write(bytes);
            }
        }
    }

    private record Route(String path, String method, Map<String, String> query, Map<String, Object> body) {
        String req(String k) {
            String v = query.get(k);
            return v == null || v.isEmpty() ? null : v;
        }

        String req(String k, String dflt) {
            String v = req(k);
            return v == null ? dflt : v;
        }

        int reqInt(String k, int dflt) {
            String v = req(k);
            if (v == null) {
                return dflt;
            }
            try {
                return Integer.parseInt(v.trim());
            } catch (NumberFormatException e) {
                return dflt;
            }
        }

        double reqDouble(String k, double dflt) {
            String v = req(k);
            if (v == null) {
                return dflt;
            }
            try {
                return Double.parseDouble(v.trim());
            } catch (NumberFormatException e) {
                return dflt;
            }
        }
    }

    private record Response(int status, String body, String contentType) {
        static Response json(Object o) {
            return new Response(200, Json.encode(o), "application/json");
        }

        static Response text(String s) {
            return new Response(200, s == null ? "" : s, "text/plain");
        }
    }
}
