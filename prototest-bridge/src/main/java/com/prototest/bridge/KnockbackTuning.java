package com.prototest.bridge;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

import org.bukkit.Location;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.player.PlayerMoveEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.player.PlayerVelocityEvent;
import org.bukkit.util.Vector;

/**
 * Server-side knockback tuning for the test rig.
 *
 * <p>Two things a client-fidelity test cannot answer on its own:
 * <ul>
 *   <li>how far should a hit throw the victim -- the server decides the impulse, so the knob belongs here
 *       ({@code knockback.multiplier} scales every {@link PlayerVelocityEvent} before it leaves);</li>
 *   <li>was the knockback actually accepted -- a client that ends up back where it started was set back by a
 *       prediction check, and that is only visible from the server. Every scaled impulse is therefore watched for
 *       {@value #WATCH_TICKS} ticks and recorded as "moved and stayed" or "came back" ({@code GET /knockback}).</li>
 * </ul>
 *
 * <p>The event the server uses for both knockback paths (attack and generic impulse) is {@link PlayerVelocityEvent},
 * but its {@code getVelocity()} is the movement from <em>before</em> the hurt: paper only uses the event to let a
 * plugin replace what the victim is about to be sent. So the scaling here is applied to {@code player.getVelocity()}
 * (which the server has already updated with the knockback) and only for velocity packets that follow a damage
 * event -- otherwise the periodic gravity sync a standing player gets would be scaled too, and the client would fall
 * faster than the server thinks it should.
 */
final class KnockbackTuning implements Listener {

    /** How many finished impulses to keep for {@code GET /knockback}. */
    private static final int MAX_RECORDS = 200;
    /** Give up on an impulse after this many ticks: vanilla knockback has decayed to nothing well before that. */
    private static final int WATCH_TICKS = 40;
    /** Distance from the starting spot that counts as "the knockback really moved the player". */
    private static final double MOVED_EPSILON = 0.25;
    /** Distance from the starting spot that counts as "back where it started", i.e. a setback. */
    private static final double RETURNED_EPSILON = 0.12;

    private final PrototestBridge plugin;
    private final Deque<Map<String, Object>> records = new ArrayDeque<>();
    private final Map<UUID, Watch> watches = new HashMap<>();
    /** Player -> last tick on which it took damage; a velocity packet inside this window is a knockback. */
    private final Map<UUID, Long> damaged = new HashMap<>();
    private final List<String> targets = new ArrayList<>();

    private boolean enabled;
    private double multiplier = 1.0;
    private boolean logSetbacks = true;
    /** Ticks after damage during which a velocity packet counts as knockback rather than a movement sync. */
    private int window = 2;
    private long ticks;

    KnockbackTuning(PrototestBridge plugin) {
        this.plugin = plugin;
    }

    /** One tick counter for every watch; wall clock millis are too coarse for a 20 Hz budget. */
    void startClock() {
        plugin.getServer().getScheduler().runTaskTimer(plugin, () -> {
            ticks++;
            if (!watches.isEmpty()) {
                expire();
            }
        }, 1L, 1L);
    }

    /** Settles watches that ran out of time, so a player who simply stands still is still recorded. */
    private void expire() {
        List<UUID> done = new ArrayList<>();
        for (Map.Entry<UUID, Watch> entry : watches.entrySet()) {
            Watch watch = entry.getValue();
            long age = ticks - watch.startTick;
            if (age < WATCH_TICKS) {
                continue;
            }
            Player player = plugin.getServer().getPlayer(entry.getKey());
            Location to = player != null ? player.getLocation() : watch.last;
            done.add(entry.getKey());
            settle(watch, to, age);
        }
        for (UUID id : done) {
            watches.remove(id);
        }
    }

    /** Reads {@code knockback.*} from config.yml. Safe to call at runtime: the next knockback uses the new value. */
    void reload() {
        plugin.reloadConfig();
        var config = plugin.getConfig();
        enabled = config.getBoolean("knockback.enabled", false);
        multiplier = clamp(config.getDouble("knockback.multiplier", 1.0));
        logSetbacks = config.getBoolean("knockback.log-setbacks", true);
        window = Math.max(1, config.getInt("knockback.damage-window", 2));
        targets.clear();
        for (String name : config.getStringList("knockback.players")) {
            if (name != null && !name.isBlank()) {
                targets.add(name.trim().toLowerCase(Locale.ROOT));
            }
        }
        watches.clear();
        damaged.clear();
        plugin.logger().info("[knockback] " + describe());
    }

    private static double clamp(double value) {
        if (Double.isNaN(value) || value < 0.0) {
            return 0.0;
        }
        return Math.min(value, 20.0);
    }

    boolean targeted(Player player) {
        return enabled && (targets.isEmpty() || targets.contains(player.getName().toLowerCase(Locale.ROOT)));
    }

    /**
     * Only damage that vanilla actually turns into knockback arms the window: an entity hit, a projectile or an
     * explosion. Fall damage and drowning produce no impulse, and scaling the movement sync that follows them would
     * make the client fall faster than the server thinks it should.
     */
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onDamage(EntityDamageEvent event) {
        if (!enabled || !(event.getEntity() instanceof Player player) || !targeted(player)) {
            return;
        }
        if (!knocksBack(event)) {
            return;
        }
        damaged.put(player.getUniqueId(), ticks + window);
    }

    private static boolean knocksBack(EntityDamageEvent event) {
        if (event instanceof EntityDamageByEntityEvent) {
            return true;
        }
        return switch (event.getCause()) {
            case ENTITY_EXPLOSION, BLOCK_EXPLOSION -> true;
            default -> false;
        };
    }

    @EventHandler(priority = EventPriority.NORMAL)
    public void onVelocity(PlayerVelocityEvent event) {
        Player player = event.getPlayer();
        if (!targeted(player)) {
            return;
        }
        // The server has already applied the knockback to the entity by the time this event fires; the event's own
        // velocity is the pre-hurt movement, so scaling that would replace the knockback instead of enlarging it.
        Long until = damaged.get(player.getUniqueId());
        if (until == null || ticks > until) {
            return;
        }
        Vector serverVelocity = player.getVelocity().clone();
        Vector scaled = serverVelocity.clone().multiply(multiplier);
        damaged.remove(player.getUniqueId());
        event.setVelocity(scaled);
        watches.put(player.getUniqueId(), new Watch(player.getName(), player.getLocation().clone(), scaled.clone(),
                ticks, multiplier));
        plugin.logger().info(String.format(Locale.ROOT,
                "[knockback] %s x%.2f impulse (%.3f, %.3f, %.3f) -> (%.3f, %.3f, %.3f)",
                player.getName(), multiplier,
                serverVelocity.getX(), serverVelocity.getY(), serverVelocity.getZ(),
                scaled.getX(), scaled.getY(), scaled.getZ()));
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onMove(PlayerMoveEvent event) {
        if (watches.isEmpty()) {
            return;
        }
        Watch watch = watches.get(event.getPlayer().getUniqueId());
        if (watch == null) {
            return;
        }
        Location to = event.getTo();
        watch.last = to.clone();
        double moved = distance(to, watch.origin);
        if (moved > watch.peak) {
            watch.peak = moved;
        }
        if (moved >= MOVED_EPSILON) {
            watch.leftOrigin = true;
        }
        long age = ticks - watch.startTick;
        boolean setback = watch.leftOrigin && moved <= RETURNED_EPSILON;
        if (setback || age >= WATCH_TICKS) {
            watches.remove(event.getPlayer().getUniqueId());
            record(watch, moved, age, setback);
        }
    }

    /** Records "did it stay" for a watch that was still live when it expired, or when its player left. */
    private void settle(Watch watch, Location to, long age) {
        watch.last = to.clone();
        double moved = distance(to, watch.origin);
        if (moved > watch.peak) {
            watch.peak = moved;
        }
        if (moved >= MOVED_EPSILON) {
            watch.leftOrigin = true;
        }
        boolean setback = watch.leftOrigin && moved <= RETURNED_EPSILON;
        record(watch, moved, age, setback);
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        UUID id = event.getPlayer().getUniqueId();
        Watch watch = watches.remove(id);
        damaged.remove(id);
        if (watch != null) {
            settle(watch, watch.last, ticks - watch.startTick);
        }
    }

    private static double distance(Location a, Location b) {
        if (a.getWorld() != b.getWorld()) {
            return Double.MAX_VALUE;
        }
        double dx = a.getX() - b.getX();
        double dy = a.getY() - b.getY();
        double dz = a.getZ() - b.getZ();
        return Math.sqrt(dx * dx + dy * dy + dz * dz);
    }

    private void record(Watch watch, double finalDistance, long age, boolean setback) {
        Map<String, Object> row = Json.map(
                "player", watch.name,
                "multiplier", round(watch.multiplier),
                "impulse", Json.map(
                        "x", round(watch.applied.getX()),
                        "y", round(watch.applied.getY()),
                        "z", round(watch.applied.getZ())),
                "moved", round(watch.peak),
                "final", round(finalDistance),
                "ticks", age,
                "setback", setback,
                "time", System.currentTimeMillis());
        synchronized (records) {
            records.addLast(row);
            while (records.size() > MAX_RECORDS) {
                records.removeFirst();
            }
        }
        String message = String.format(Locale.ROOT, "[knockback] %s x%.2f moved %.2f blocks, %s after %d ticks",
                watch.name, watch.multiplier, watch.peak,
                !watch.leftOrigin ? "did not move at all (impulse too small?)"
                        : setback ? "then came BACK to the starting spot (setback)" : "and stayed there",
                age);
        if (setback && logSetbacks) {
            plugin.logger().warning(message);
        } else {
            plugin.logger().info(message);
        }
    }

    private static double round(double value) {
        return Math.round(value * 1000.0) / 1000.0;
    }

    Map<String, Object> snapshot() {
        List<Object> rows = new ArrayList<>();
        synchronized (records) {
            rows.addAll(records);
        }
        return Json.map(
                "enabled", enabled,
                "multiplier", round(multiplier),
                "players", new ArrayList<Object>(targets),
                "logSetbacks", logSetbacks,
                "count", rows.size(),
                "records", rows);
    }

    String describe() {
        return "enabled=" + enabled + " multiplier=" + round(multiplier)
                + (targets.isEmpty() ? " (all players)" : " players=" + targets)
                + " damage-window=" + window + " log-setbacks=" + logSetbacks;
    }

    void setMultiplier(double value) {
        multiplier = clamp(value);
        plugin.logger().info("[knockback] multiplier set to " + round(multiplier));
    }

    void setEnabled(boolean value) {
        enabled = value;
        watches.clear();
        plugin.logger().info("[knockback] " + (enabled ? "enabled" : "disabled"));
    }

    int clear() {
        synchronized (records) {
            int had = records.size();
            records.clear();
            return had;
        }
    }

    /** A scaled impulse that is still being watched for the "did it go back" question. */
    private static final class Watch {

        private final String name;
        private final Location origin;
        private final Vector applied;
        private final long startTick;
        private final double multiplier;

        private Location last;
        private double peak;
        private boolean leftOrigin;

        private Watch(String name, Location origin, Vector applied, long startTick, double multiplier) {
            this.name = name;
            this.origin = origin;
            this.last = origin.clone();
            this.applied = applied;
            this.startTick = startTick;
            this.multiplier = multiplier;
        }
    }
}
