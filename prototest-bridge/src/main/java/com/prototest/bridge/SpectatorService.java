package com.prototest.bridge;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryCloseEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.event.player.PlayerDropItemEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.player.PlayerSwapHandItemsEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitTask;

import net.kyori.adventure.text.Component;

/**
 * Lets an operator watch a registered bot's inventory.
 *
 * <p>Two patterns, both live:
 * <ul>
 *   <li>{@link Pattern#CHEST} opens a virtual 45 slot chest (36 main, 9 hotbar, 4 armour, 1 offhand) that is
 *       refreshed from the bot every few ticks. Closing the window ends the session, like any chest.</li>
 *   <li>{@link Pattern#INV} overwrites the viewer's own inventory with the bot's, after saving what the viewer
 *       carried. The viewer presses their normal inventory key and sees the bot, so closing that screen cannot mean
 *       "stop": only {@code /see stop} restores the saved items.</li>
 * </ul>
 *
 * <p>Both patterns are strictly read-only: clicks and drags are cancelled, and so are the two ways a viewer could
 * still move an item out of a mirror (dropping the cursor item, throwing a held stack). Nothing in a watched
 * inventory may be moved, or the viewer would be editing a copy that the next refresh overwrites anyway.
 */
public final class SpectatorService implements Listener {

    /** Chest view slots, index for index with the bot's inventory (0-8 hotbar, 9-35 main, 36-39 armour, 40 offhand). */
    public static final int SLOT_COUNT = 45;
    private static final int REFRESH_TICKS = 4;

    /** How the bot's inventory is shown to the watcher. */
    public enum Pattern {
        CHEST,
        INV
    }

    private static final class View {
        final UUID viewer;
        final UUID target;
        final String targetName;
        final Pattern pattern;
        Inventory chest;
        ItemStack[] saved;
        int savedSlot;
        boolean closing;

        View(UUID viewer, UUID target, String targetName, Pattern pattern) {
            this.viewer = viewer;
            this.target = target;
            this.targetName = targetName;
            this.pattern = pattern;
        }
    }

    private final JavaPlugin plugin;
    private final Map<UUID, View> views = new LinkedHashMap<>();
    private final Map<UUID, Pattern> patterns = new LinkedHashMap<>();
    private BukkitTask refresh;

    public SpectatorService(JavaPlugin plugin) {
        this.plugin = plugin;
    }

    public void start() {
        refresh = Bukkit.getScheduler().runTaskTimer(plugin, this::refreshViews, REFRESH_TICKS, REFRESH_TICKS);
    }

    public void stop() {
        if (refresh != null) {
            refresh.cancel();
            refresh = null;
        }
        for (UUID id : new ArrayList<>(views.keySet())) {
            Player viewer = Bukkit.getPlayer(id);
            if (viewer != null) {
                restore(viewer, views.get(id));
            }
        }
        views.clear();
    }

    public synchronized Pattern patternOf(Player viewer) {
        return patterns.getOrDefault(viewer.getUniqueId(), Pattern.CHEST);
    }

    public synchronized void setPattern(Player viewer, Pattern pattern) {
        patterns.put(viewer.getUniqueId(), pattern);
    }

    public synchronized boolean isWatching(Player viewer) {
        return views.containsKey(viewer.getUniqueId());
    }

    public synchronized String targetName(Player viewer) {
        View view = views.get(viewer.getUniqueId());
        return view == null ? null : view.targetName;
    }

    /** Begin (or replace) a view, returning the line to send back to the viewer. */
    public synchronized String start(Player viewer, Player target, Pattern pattern) {
        stop(viewer);
        View view = new View(viewer.getUniqueId(), target.getUniqueId(), target.getName(), pattern);
        if (pattern == Pattern.INV) {
            ItemStack[] contents = viewer.getInventory().getContents();
            view.saved = new ItemStack[contents.length];
            for (int i = 0; i < contents.length; i++) {
                view.saved[i] = contents[i] == null ? null : contents[i].clone();
            }
            view.savedSlot = viewer.getInventory().getHeldItemSlot();
            mirror(view, viewer.getInventory(), target);
        } else {
            view.chest = Bukkit.createInventory(null, SLOT_COUNT, Component.text("看 " + target.getName() + " 的背包"));
            mirror(view, view.chest, target);
            viewer.openInventory(view.chest);
        }
        views.put(viewer.getUniqueId(), view);
        if (pattern == Pattern.INV) {
            return "正在以原生背包查看 " + target.getName() + "；关闭背包不会退出，用 /see stop 恢复自己的物品";
        }
        return "正在以箱子界面查看 " + target.getName() + "；关闭窗口或 /see stop 退出";
    }

    /** End the view and put the viewer's own items back. */
    public synchronized String stop(Player viewer) {
        View view = views.remove(viewer.getUniqueId());
        if (view == null) {
            return "没有在观看任何 bot";
        }
        restore(viewer, view);
        return "已停止观看 " + view.targetName;
    }

    /** viewer -> target -> pattern, for the bridge's /bots endpoint. */
    public synchronized List<Object[]> snapshot() {
        List<Object[]> rows = new ArrayList<>();
        for (View view : views.values()) {
            rows.add(new Object[] {nameOf(view.viewer), view.targetName, view.pattern.name().toLowerCase()});
        }
        return rows;
    }

    private static String nameOf(UUID id) {
        Player player = Bukkit.getPlayer(id);
        return player == null ? id.toString() : player.getName();
    }

    private void restore(Player viewer, View view) {
        if (view == null) {
            return;
        }
        if (view.chest != null) {
            view.closing = true;
            if (viewer.getOpenInventory().getTopInventory() == view.chest) {
                viewer.closeInventory();
            }
            view.chest = null;
        }
        if (view.saved != null) {
            for (int i = 0; i < view.saved.length && i < viewer.getInventory().getSize(); i++) {
                viewer.getInventory().setItem(i, view.saved[i]);
            }
            viewer.getInventory().setHeldItemSlot(view.savedSlot);
            viewer.updateInventory();
            view.saved = null;
        }
    }

    private void refreshViews() {
        List<Runnable> actions = new ArrayList<>();
        synchronized (this) {
            for (UUID id : new ArrayList<>(views.keySet())) {
                View view = views.get(id);
                Player viewer = Bukkit.getPlayer(id);
                if (viewer == null || !viewer.isOnline()) {
                    views.remove(id);
                    continue;
                }
                Player target = Bukkit.getPlayer(view.target);
                if (target == null || !target.isOnline()) {
                    views.remove(id);
                    viewer.sendMessage(Component.text("bot " + view.targetName + " 已离线，观看结束"));
                    restore(viewer, view);
                    continue;
                }
                if (view.pattern == Pattern.CHEST) {
                    if (viewer.getOpenInventory().getTopInventory() != view.chest) {
                        // The window was closed: watching a chest ends with the chest.
                        views.remove(id);
                        continue;
                    }
                    Inventory chest = view.chest;
                    actions.add(() -> mirror(view, chest, target));
                } else {
                    actions.add(() -> mirror(view, viewer.getInventory(), target));
                }
            }
        }
        for (Runnable action : actions) {
            action.run();
        }
    }

    /** Copy the bot's 41 slots into the destination slots 0.., leaving the rest empty. */
    private static void mirror(View view, Inventory destination, Player target) {
        ItemStack[] source = target.getInventory().getContents();
        int limit = Math.min(Math.min(source.length, destination.getSize()), SLOT_COUNT);
        for (int i = 0; i < limit; i++) {
            ItemStack want = source[i];
            ItemStack have = destination.getItem(i);
            if (want == null || want.getType() == Material.AIR) {
                if (have != null) {
                    destination.setItem(i, null);
                }
                continue;
            }
            if (have != null && have.isSimilar(want) && have.getAmount() == want.getAmount()) {
                continue;
            }
            destination.setItem(i, want.clone());
        }
    }

    @EventHandler(priority = EventPriority.LOWEST)
    public void onInventoryClick(InventoryClickEvent event) {
        if (!(event.getWhoClicked() instanceof Player viewer)) {
            return;
        }
        if (isWatching(viewer)) {
            event.setCancelled(true);
        }
    }

    /**
     * A drag never produces an {@link InventoryClickEvent}, so cancelling clicks alone left the one remaining way to
     * change what the viewer sees (and, with the player pattern, to shove items into their own inventory).
     */
    @EventHandler(priority = EventPriority.LOWEST)
    public void onInventoryDrag(InventoryDragEvent event) {
        if (!(event.getWhoClicked() instanceof Player viewer)) {
            return;
        }
        if (isWatching(viewer)) {
            event.setCancelled(true);
        }
    }

    /** With the player pattern the viewer's own inventory shows the bot's items, so a drop would conjure a real copy. */
    @EventHandler(priority = EventPriority.LOWEST)
    public void onDropItem(PlayerDropItemEvent event) {
        if (isWatching(event.getPlayer())) {
            event.setCancelled(true);
        }
    }

    /** Same reason as {@link #onDropItem}: swapping hands would move a copied stack around for real. */
    @EventHandler(priority = EventPriority.LOWEST)
    public void onSwapHands(PlayerSwapHandItemsEvent event) {
        if (isWatching(event.getPlayer())) {
            event.setCancelled(true);
        }
    }

    @EventHandler
    public void onInventoryClose(InventoryCloseEvent event) {
        if (!(event.getPlayer() instanceof Player viewer)) {
            return;
        }
        View view;
        synchronized (this) {
            view = views.get(viewer.getUniqueId());
            if (view == null || view.closing || view.chest == null) {
                return;
            }
            if (event.getInventory() != view.chest) {
                return;
            }
            views.remove(viewer.getUniqueId());
        }
        viewer.sendMessage(Component.text("已关闭观看窗口，结束观看 " + view.targetName));
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        Player player = event.getPlayer();
        synchronized (this) {
            View view = views.remove(player.getUniqueId());
            if (view != null) {
                restore(player, view);
            }
            patterns.remove(player.getUniqueId());
        }
    }
}
