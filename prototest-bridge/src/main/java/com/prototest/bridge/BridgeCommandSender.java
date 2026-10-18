package com.prototest.bridge;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.bukkit.Bukkit;
import org.bukkit.Server;
import org.bukkit.command.CommandSender;
import org.bukkit.permissions.Permission;
import org.bukkit.permissions.PermissionAttachment;
import org.bukkit.permissions.PermissionAttachmentInfo;
import org.bukkit.plugin.Plugin;

/**
 * A {@link CommandSender} that answers every permission query with "yes" and collects everything
 * sent back, so {@code POST /command} can return the command's output to the caller.
 */
final class BridgeCommandSender implements CommandSender {

    private final List<String> output = Collections.synchronizedList(new ArrayList<>());
    private final String name;

    BridgeCommandSender(String name) {
        this.name = name;
    }

    List<String> output() {
        synchronized (output) {
            return new ArrayList<>(output);
        }
    }

    private void add(String line) {
        if (line != null && !line.isEmpty()) {
            output.add(line);
        }
    }
    public void sendMessage(String message) {
        add(message);
    }
    public void sendMessage(String message, UUID sender) {
        add(message);
    }
    public void sendMessage(UUID sender, String message) {
        add(message);
    }
    public void sendMessage(String... messages) {
        for (String m : messages) {
            add(m);
        }
    }
    public void sendMessage(UUID sender, String... messages) {
        sendMessage(messages);
    }
    public void sendRawMessage(String message) {
        add(message);
    }
    public void sendRawMessage(UUID sender, String message) {
        add(message);
    }
    public void sendMessage(Component message) {
        add(PlainTextComponentSerializer.plainText().serialize(message));
    }
    public void sendMessage(Component message, UUID sender) {
        sendMessage(message);
    }
    public void sendMessage(Component... messages) {
        for (Component c : messages) {
            sendMessage(c);
        }
    }
    public void sendMessage(UUID sender, Component... messages) {
        sendMessage(messages);
    }
    public Server getServer() {
        return Bukkit.getServer();
    }
    public Component name() {
        return Component.text(name);
    }

    @Deprecated
    public String getName() {
        return name;
    }
    public boolean isPermissionSet(String permission) {
        return true;
    }
    public boolean isPermissionSet(Permission permission) {
        return true;
    }
    public boolean hasPermission(String permission) {
        return true;
    }
    public boolean hasPermission(Permission permission) {
        return true;
    }
    public PermissionAttachment addAttachment(Plugin plugin, String name, boolean value) {
        return null;
    }
    public PermissionAttachment addAttachment(Plugin plugin) {
        return null;
    }
    public PermissionAttachment addAttachment(Plugin plugin, String name, boolean value, int ticks) {
        return null;
    }
    public PermissionAttachment addAttachment(Plugin plugin, int ticks) {
        return null;
    }
    public void removeAttachment(PermissionAttachment attachment) {
    }
    public void recalculatePermissions() {
    }
    public Set<PermissionAttachmentInfo> getEffectivePermissions() {
        return Collections.emptySet();
    }
    public boolean isOp() {
        return true;
    }
    public void setOp(boolean value) {
    }
    public org.bukkit.command.CommandSender.Spigot spigot() {
        return new org.bukkit.command.CommandSender.Spigot() {
        };
    }
}
