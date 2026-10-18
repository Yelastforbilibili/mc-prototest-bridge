package com.prototest.bridge;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.block.Block;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.event.block.SignChangeEvent;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.entity.EntityPickupItemEvent;
import org.bukkit.event.entity.EntityPotionEffectEvent;
import org.bukkit.event.entity.FoodLevelChangeEvent;
import org.bukkit.event.entity.PlayerDeathEvent;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryCloseEvent;
import org.bukkit.event.inventory.InventoryOpenEvent;
import org.bukkit.event.player.PlayerAnimationEvent;
import org.bukkit.event.player.PlayerCommandPreprocessEvent;
import org.bukkit.event.player.PlayerDropItemEvent;
import org.bukkit.event.player.PlayerEditBookEvent;
import org.bukkit.event.player.PlayerExpChangeEvent;
import org.bukkit.event.player.PlayerGameModeChangeEvent;
import org.bukkit.event.player.PlayerInteractAtEntityEvent;
import org.bukkit.event.player.PlayerInteractEntityEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerItemConsumeEvent;
import org.bukkit.event.player.PlayerItemHeldEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerMoveEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.player.PlayerRespawnEvent;
import org.bukkit.event.player.PlayerSwapHandItemsEvent;
import org.bukkit.event.player.PlayerToggleFlightEvent;
import org.bukkit.event.player.PlayerToggleSneakEvent;
import org.bukkit.event.player.PlayerToggleSprintEvent;
import org.bukkit.inventory.ItemStack;

import io.papermc.paper.event.player.AsyncChatEvent;

/**
 * Records server-side "truth" for everything a client causes.
 *
 * <p>The point is to give an automated client an independent oracle: the client says "I broke block X at tick T",
 * and this log says whether the server actually saw a break, where, and at which tick. Movement is only recorded
 * while a trace session is active, otherwise a single walking bot would flood the buffer at 20 events/second.
 */
final class EventRecorder implements Listener {

    private static final int CAPACITY = 6000;

    private final PrototestBridge plugin;
    private final Object lock = new Object();
    private final ArrayDeque<Map<String, Object>> ring = new ArrayDeque<>();
    private long seq;

    // ---- movement trace session
    private volatile String tracePlayer;
    private volatile int traceLimit = 20000;
    private final List<Map<String, Object>> trace = new ArrayList<>();
    private long traceStartedAt;
    private int traceDropped;

    EventRecorder(PrototestBridge plugin) {
        this.plugin = plugin;
    }

    // --------------------------------------------------------------- recording

    void record(String type, Object... kv) {
        Map<String, Object> e = new LinkedHashMap<>();
        e.put("seq", 0L);
        e.put("t", System.currentTimeMillis());
        e.put("tick", Bukkit.getCurrentTick());
        e.put("type", type);
        for (int i = 0; i + 1 < kv.length; i += 2) {
            e.put(String.valueOf(kv[i]), kv[i + 1]);
        }
        synchronized (lock) {
            e.put("seq", ++seq);
            ring.addLast(e);
            while (ring.size() > CAPACITY) {
                ring.removeFirst();
            }
        }
    }

    long lastSeq() {
        synchronized (lock) {
            return seq;
        }
    }

    List<Object> since(long from, int limit, String typeFilter) {
        List<String> types = new ArrayList<>();
        if (typeFilter != null && !typeFilter.isEmpty()) {
            for (String t : typeFilter.split(",")) {
                if (!t.isBlank()) {
                    types.add(t.trim());
                }
            }
        }
        List<Object> out = new ArrayList<>();
        synchronized (lock) {
            for (Map<String, Object> e : ring) {
                if ((Long) e.get("seq") <= from) {
                    continue;
                }
                if (!types.isEmpty() && !types.contains(String.valueOf(e.get("type")))) {
                    continue;
                }
                out.add(e);
                if (out.size() >= limit) {
                    break;
                }
            }
        }
        return out;
    }

    int clear() {
        synchronized (lock) {
            int n = ring.size();
            ring.clear();
            return n;
        }
    }

    // ------------------------------------------------------------------- trace

    synchronized Map<String, Object> startTrace(String player, int limit) {
        tracePlayer = player;
        traceLimit = limit <= 0 ? 20000 : limit;
        traceStartedAt = System.currentTimeMillis();
        traceDropped = 0;
        synchronized (lock) {
            trace.clear();
        }
        record("trace_start", "player", player, "limit", traceLimit);
        return traceState();
    }

    synchronized Map<String, Object> stopTrace() {
        Map<String, Object> m = traceState();
        tracePlayer = null;
        record("trace_stop", "player", m.get("player"), "samples", m.get("samples"), "dropped", traceDropped);
        return m;
    }

    synchronized Map<String, Object> traceState() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("active", tracePlayer != null);
        m.put("player", tracePlayer);
        m.put("limit", traceLimit);
        m.put("startedAt", traceStartedAt);
        m.put("dropped", traceDropped);
        synchronized (lock) {
            m.put("samples", trace.size());
        }
        return m;
    }

    List<Object> traceData(int limit) {
        synchronized (lock) {
            List<Object> out = new ArrayList<>();
            for (Map<String, Object> e : trace) {
                out.add(e);
                if (out.size() >= limit) {
                    break;
                }
            }
            return out;
        }
    }

    // ---------------------------------------------------------------- listeners

    @EventHandler(priority = EventPriority.MONITOR)
    public void onJoin(PlayerJoinEvent e) {
        Player p = e.getPlayer();
        record("join", "player", p.getName(), "uuid", p.getUniqueId().toString(),
                "address", p.getAddress() == null ? null : p.getAddress().getAddress().getHostAddress(),
                "port", p.getAddress() == null ? 0 : p.getAddress().getPort(),
                "locale", p.getLocale(), "brand", p.getClientBrandName(), "viewDistance", p.getClientViewDistance(),
                "x", p.getX(), "y", p.getY(), "z", p.getZ(), "world", p.getWorld().getName(),
                "gameMode", p.getGameMode().name());
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onQuit(PlayerQuitEvent e) {
        Player p = e.getPlayer();
        record("quit", "player", p.getName(), "uuid", p.getUniqueId().toString(), "reason", String.valueOf(e.getReason()));
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onMove(PlayerMoveEvent e) {
        String target = tracePlayer;
        if (target == null || !target.equals(e.getPlayer().getName())) {
            return;
        }
        synchronized (lock) {
            if (trace.size() >= traceLimit) {
                traceDropped++;
                return;
            }
            Location from = e.getFrom();
            Location to = e.getTo();
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("tick", Bukkit.getCurrentTick());
            m.put("t", System.currentTimeMillis());
            m.put("from", Json.list(from.getX(), from.getY(), from.getZ()));
            m.put("to", Json.list(to.getX(), to.getY(), to.getZ()));
            m.put("onGround", e.getPlayer().isOnGround());
            m.put("yaw", to.getYaw());
            m.put("pitch", to.getPitch());
            m.put("velocity", Json.list(e.getPlayer().getVelocity().getX(), e.getPlayer().getVelocity().getY(),
                    e.getPlayer().getVelocity().getZ()));
            trace.add(m);
        }
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onSwing(PlayerAnimationEvent e) {
        record("swing", "player", e.getPlayer().getName(), "animation", e.getAnimationType().name());
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onInteract(PlayerInteractEvent e) {
        Block b = e.getClickedBlock();
        record("interact",
                "player", e.getPlayer().getName(),
                "action", e.getAction().name(),
                "hand", e.getHand() == null ? null : e.getHand().name(),
                "block", b == null ? null : Json.list(b.getX(), b.getY(), b.getZ()),
                "face", e.getBlockFace().name(),
                "item", plugin.state().item(e.getItem()),
                "useItemInHand", e.useItemInHand().name(),
                "useInteractedBlock", e.useInteractedBlock().name());
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onInteractEntity(PlayerInteractEntityEvent e) {
        Entity t = e.getRightClicked();
        record("interact_entity",
                "player", e.getPlayer().getName(),
                "target", t.getType().getKey().toString(),
                "targetUuid", t.getUniqueId().toString(),
                "hand", e.getHand() == null ? null : e.getHand().name(),
                "at", e instanceof PlayerInteractAtEntityEvent at
                        ? Json.list(at.getClickedPosition().getX(), at.getClickedPosition().getY(), at.getClickedPosition().getZ())
                        : null);
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onAttack(EntityDamageByEntityEvent e) {
        if (!(e.getDamager() instanceof Player p)) {
            return;
        }
        Entity t = e.getEntity();
        record("attack",
                "player", p.getName(),
                "target", t.getType().getKey().toString(),
                "targetUuid", t.getUniqueId().toString(),
                "damage", e.getDamage(),
                "cause", e.getCause().name(),
                "critical", e.isCritical(),
                "targetHealth", t instanceof org.bukkit.entity.Damageable d ? d.getHealth() : null);
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onDamage(EntityDamageEvent e) {
        if (!(e.getEntity() instanceof Player p)) {
            return;
        }
        record("damage",
                "player", p.getName(),
                "damage", e.getDamage(),
                "finalDamage", e.getFinalDamage(),
                "cause", e.getCause().name(),
                "health", p.getHealth(),
                "cancelled", e.isCancelled());
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onBreak(BlockBreakEvent e) {
        Block b = e.getBlock();
        record("break",
                "player", e.getPlayer().getName(),
                "block", Json.list(b.getX(), b.getY(), b.getZ()),
                "type", b.getType().getKey().toString(),
                "dataBefore", b.getBlockData().getAsString(),
                "exp", e.getExpToDrop(),
                "cancelled", e.isCancelled());
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onPlace(BlockPlaceEvent e) {
        Block b = e.getBlockPlaced();
        Block against = e.getBlockAgainst();
        record("place",
                "player", e.getPlayer().getName(),
                "block", Json.list(b.getX(), b.getY(), b.getZ()),
                "type", b.getType().getKey().toString(),
                "data", b.getBlockData().getAsString(),
                "against", Json.list(against.getX(), against.getY(), against.getZ()),
                "hand", e.getHand() == null ? null : e.getHand().name(),
                "item", plugin.state().item(e.getItemInHand()),
                "cancelled", e.isCancelled());
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onConsume(PlayerItemConsumeEvent e) {
        record("consume", "player", e.getPlayer().getName(), "item", plugin.state().item(e.getItem()));
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onDrop(PlayerDropItemEvent e) {
        record("drop", "player", e.getPlayer().getName(), "item", plugin.state().item(e.getItemDrop().getItemStack()),
                "location", location(e.getItemDrop().getLocation()));
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onPickup(EntityPickupItemEvent e) {
        if (!(e.getEntity() instanceof Player p)) {
            return;
        }
        record("pickup", "player", p.getName(), "item", plugin.state().item(e.getItem().getItemStack()),
                "remaining", e.getRemaining());
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onChat(AsyncChatEvent e) {
        record("chat", "player", e.getPlayer().getName(), "message", Plain.of(e.message()));
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onCommand(PlayerCommandPreprocessEvent e) {
        record("command", "player", e.getPlayer().getName(), "message", e.getMessage());
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onDeath(PlayerDeathEvent e) {
        Player p = e.getEntity();
        record("death", "player", p.getName(), "message", Plain.of(e.deathMessage()),
                "keepInventory", e.getKeepInventory(), "droppedExp", e.getDroppedExp(),
                "location", location(p.getLocation()), "cause", p.getLastDamageCause() == null ? null
                        : p.getLastDamageCause().getCause().name());
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onRespawn(PlayerRespawnEvent e) {
        record("respawn", "player", e.getPlayer().getName(), "reason", e.getRespawnReason().name(),
                "location", location(e.getRespawnLocation()), "isBedSpawn", e.isBedSpawn());
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onOpen(InventoryOpenEvent e) {
        if (!(e.getPlayer() instanceof Player p)) {
            return;
        }
        record("container_open", "player", p.getName(),
                "type", e.getView().getType().name(),
                "title", Plain.of(e.getView().title()),
                "size", e.getView().getTopInventory().getSize(),
                "location", e.getView().getTopInventory().getLocation() == null ? null
                        : location(e.getView().getTopInventory().getLocation()));
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onClose(InventoryCloseEvent e) {
        if (!(e.getPlayer() instanceof Player p)) {
            return;
        }
        record("container_close", "player", p.getName(), "type", e.getView().getType().name());
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onClick(InventoryClickEvent e) {
        if (!(e.getWhoClicked() instanceof Player p)) {
            return;
        }
        int rawSlot = e.getRawSlot();
        String viewType = e.getView().getType().name();
        Map<String, Object> before = plugin.state().item(e.getCurrentItem());
        Object cursor = plugin.state().item(e.getCursor());
        record("click", "player", p.getName(),
                "view", viewType,
                "rawSlot", rawSlot,
                "slot", e.getSlot(),
                "clickType", e.getClick().name(),
                "action", e.getAction().name(),
                "shift", e.isShiftClick(),
                "rightClick", e.isRightClick(),
                "leftClick", e.isLeftClick(),
                "hotbarButton", e.getHotbarButton(),
                "before", before,
                "cursor", cursor,
                "result", e.getResult().name());
        // Snapshot the clicked slot one tick later: that is where the outcome of the click is visible.
        final var view = e.getView();
        final Player clicker = p;
        Bukkit.getGlobalRegionScheduler().execute(plugin, () -> {
            int topSize = view.getTopInventory().getSize();
            ItemStack after = null;
            if (rawSlot >= 0 && rawSlot < topSize) {
                after = view.getTopInventory().getItem(rawSlot);
            } else if (rawSlot >= topSize) {
                after = clicker.getInventory().getItem(rawSlot - topSize);
            }
            record("click_after", "player", clicker.getName(), "view", viewType, "rawSlot", rawSlot,
                    "after", plugin.state().item(after), "cursor", plugin.state().item(clicker.getItemOnCursor()));
        });
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onHeld(PlayerItemHeldEvent e) {
        record("held_change", "player", e.getPlayer().getName(), "previous", e.getPreviousSlot(), "new", e.getNewSlot(),
                "item", plugin.state().item(e.getPlayer().getInventory().getItem(e.getNewSlot())));
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onSwapHands(PlayerSwapHandItemsEvent e) {
        record("swap_hands", "player", e.getPlayer().getName(),
                "mainHand", plugin.state().item(e.getMainHandItem()),
                "offHand", plugin.state().item(e.getOffHandItem()));
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onToggleFlight(PlayerToggleFlightEvent e) {
        record("toggle_flight", "player", e.getPlayer().getName(), "flying", e.isFlying());
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onToggleSneak(PlayerToggleSneakEvent e) {
        record("toggle_sneak", "player", e.getPlayer().getName(), "sneaking", e.isSneaking());
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onToggleSprint(PlayerToggleSprintEvent e) {
        record("toggle_sprint", "player", e.getPlayer().getName(), "sprinting", e.isSprinting());
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onGameMode(PlayerGameModeChangeEvent e) {
        record("game_mode", "player", e.getPlayer().getName(), "from", e.getPlayer().getGameMode().name(),
                "to", e.getNewGameMode().name());
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onExp(PlayerExpChangeEvent e) {
        record("exp", "player", e.getPlayer().getName(), "amount", e.getAmount(),
                "level", e.getPlayer().getLevel(), "exp", e.getPlayer().getExp());
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onFood(FoodLevelChangeEvent e) {
        if (!(e.getEntity() instanceof Player p)) {
            return;
        }
        record("food", "player", p.getName(), "from", p.getFoodLevel(), "to", e.getFoodLevel(),
                "saturation", p.getSaturation());
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onEffect(EntityPotionEffectEvent e) {
        if (!(e.getEntity() instanceof Player p)) {
            return;
        }
        record("effect", "player", p.getName(), "action", e.getAction().name(),
                "cause", e.getCause() == null ? null : e.getCause().name(),
                "effect", e.getNewEffect() != null ? e.getNewEffect().getType().getKey().toString()
                        : (e.getOldEffect() != null ? e.getOldEffect().getType().getKey().toString() : null),
                "duration", e.getNewEffect() != null ? e.getNewEffect().getDuration()
                        : (e.getOldEffect() != null ? e.getOldEffect().getDuration() : null),
                "amplifier", e.getNewEffect() != null ? e.getNewEffect().getAmplifier()
                        : (e.getOldEffect() != null ? e.getOldEffect().getAmplifier() : null));
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onBook(PlayerEditBookEvent e) {
        record("book_edit", "player", e.getPlayer().getName(),
                "isSigning", e.isSigning(), "title", e.getNewBookMeta().hasTitle() ? e.getNewBookMeta().getTitle() : null,
                "pages", e.getNewBookMeta().getPageCount());
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onSign(SignChangeEvent e) {
        List<Object> lines = new ArrayList<>();
        for (var c : e.lines()) {
            lines.add(Plain.of(c));
        }
        record("sign_change", "player", e.getPlayer().getName(),
                "block", Json.list(e.getBlock().getX(), e.getBlock().getY(), e.getBlock().getZ()),
                "lines", lines, "side", e.getSide().name());
    }

    private static List<Object> location(Location l) {
        return l == null ? null : Json.list(l.getX(), l.getY(), l.getZ(), l.getYaw(), l.getPitch());
    }
}
