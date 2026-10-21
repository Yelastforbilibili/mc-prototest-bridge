package com.prototest.bridge;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

import io.netty.channel.Channel;
import io.netty.channel.ChannelDuplexHandler;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelPipeline;
import io.netty.channel.ChannelPromise;

import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;

/**
 * Records the packets a single player exchanges with the server.
 *
 * <p>Why this exists: the server decides whether a dig is real from the packets the client sends, and it answers with
 * packets the client draws from (the crack stages, the acks, the corrections). Reading the Python client's guesses from
 * the outside can only get you so far; a real client digging by hand is the reference behaviour. This tap sits on the
 * player's Netty pipeline and copies the interesting packets into a small ring buffer, so a test can ask "what does a
 * vanilla client actually send for this dig" and diff it against the bot.
 *
 * <p>Implemented with reflection on the NMS side (packet classes, the connection channel) so the plugin keeps building
 * against the plain Paper API; only Netty is a compile dependency, and the server already provides it.
 */
public final class PacketTap {

    /** How many packets per player are kept; a dig is a dozen packets, so this holds many minutes of play. */
    private static final int MAX_ENTRIES = 4000;

    private static final String HANDLER_PREFIX = "prototest-tap-";

    /** Packet classes worth keeping, keyed by simple class name, with the field/accessor names used to read them. */
    private static final Map<String, String[]> INTERESTING = new LinkedHashMap<>();

    static {
        // Inbound: what the client claims to be doing.
        INTERESTING.put("ServerboundPlayerActionPacket", new String[] {"action", "pos", "direction", "sequence"});
        INTERESTING.put("ServerboundSwingPacket", new String[] {"hand"});
        INTERESTING.put("ServerboundPunchPacket", new String[] {});
        INTERESTING.put("ServerboundInteractPacket", new String[] {"entityId", "hand", "location", "usingSecondaryAction"});
        INTERESTING.put("ServerboundUseItemOnPacket", new String[] {"hand", "hitResult", "sequence"});
        // Movement: the packets a prediction check rebuilds the player's tick from.  Keeping them
        // makes "which position claim reached the server when" visible, which is what a
        // teleport-versus-movement mismatch looks like from the outside.
        INTERESTING.put("ServerboundAcceptTeleportationPacket", new String[] {"id"});
        // 26.3 nests these as ServerboundMovePlayerPacket$Pos and friends, and
        // the simple name of a nested class still carries the outer name, so a
        // single entry covers every movement packet.
        INTERESTING.put("ServerboundMovePlayerPacket", new String[] {"yRot", "xRot", "onGround"});
        INTERESTING.put("ServerboundPlayerCommandPacket", new String[] {"id", "action", "data"});
        INTERESTING.put("ServerboundPlayerInputPacket", new String[] {"input"});
        INTERESTING.put("ServerboundClientTickEndPacket", new String[] {});
        INTERESTING.put("ClientboundPlayerPositionPacket", new String[] {"id", "change", "relatives"});
        // Outbound: what the server tells the client about the dig.
        INTERESTING.put("ClientboundBlockDestructionPacket", new String[] {"id", "pos", "progress"});
        INTERESTING.put("ClientboundBlockChangedAckPacket", new String[] {"sequence"});
        INTERESTING.put("ClientboundBlockUpdatePacket", new String[] {"pos", "blockState"});
        INTERESTING.put("ClientboundSwingAnimationPacket", new String[] {"entityId", "hand", "animation"});
        INTERESTING.put("ClientboundAnimatePacket", new String[] {"id", "action"});
        INTERESTING.put("ClientboundPlayerCombatKillPacket", new String[] {"playerId"});
    }

    private final JavaPlugin plugin;
    private final AtomicLong clock = new AtomicLong();
    private final Map<UUID, Deque<Map<String, Object>>> buffers = new ConcurrentHashMap<>();
    private final Map<UUID, String> names = new ConcurrentHashMap<>();
    private final Set<UUID> tapped = ConcurrentHashMap.newKeySet();

    PacketTap(JavaPlugin plugin) {
        this.plugin = plugin;
    }

    // ------------------------------------------------------------------ lifecycle

    /** Attaches to every online player (used on enable, when no join event will fire for them). */
    void attachOnline() {
        for (Player player : Bukkit.getOnlinePlayers()) {
            attach(player);
        }
    }

    void attach(Player player) {
        UUID id = player.getUniqueId();
        names.put(id, player.getName());
        buffers.computeIfAbsent(id, key -> new ArrayDeque<>());
        if (!tapped.add(id)) {
            return;
        }
        try {
            Channel channel = channelOf(player);
            if (channel == null) {
                tapped.remove(id);
                return;
            }
            channel.eventLoop().execute(() -> {
                try {
                    ChannelPipeline pipeline = channel.pipeline();
                    String name = HANDLER_PREFIX + id;
                    if (pipeline.get(name) != null) {
                        return;
                    }
                    pipeline.addBefore("packet_handler", name, new TapHandler(id));
                    plugin.getLogger().info("[tap] recording packets for " + player.getName());
                } catch (Throwable t) {
                    plugin.getLogger().warning("[tap] could not hook " + player.getName() + ": " + t);
                }
            });
        } catch (Throwable t) {
            tapped.remove(id);
            plugin.getLogger().warning("[tap] no channel for " + player.getName() + ": " + t);
        }
    }

    void detach(Player player) {
        UUID id = player.getUniqueId();
        tapped.remove(id);
        try {
            Channel channel = channelOf(player);
            if (channel == null) {
                return;
            }
            channel.eventLoop().execute(() -> {
                try {
                    ChannelPipeline pipeline = channel.pipeline();
                    String name = HANDLER_PREFIX + id;
                    if (pipeline.get(name) != null) {
                        pipeline.remove(name);
                    }
                } catch (Throwable ignored) {
                    // The channel is usually already gone by the time a quit is handled.
                }
            });
        } catch (Throwable ignored) {
            // Same: nothing to detach if the player object no longer resolves a channel.
        }
    }

    void clear() {
        buffers.replaceAll((id, buffer) -> new ArrayDeque<>());
        clock.set(0L);
    }

    // ------------------------------------------------------------------ reading

    List<Map<String, Object>> entries(String wanted, long since, int limit) {
        UUID id = resolve(wanted);
        Deque<Map<String, Object>> buffer = id == null ? null : buffers.get(id);
        if (buffer == null) {
            return List.of();
        }
        List<Map<String, Object>> out = new ArrayList<>();
        synchronized (buffer) {
            for (Map<String, Object> entry : buffer) {
                Object seq = entry.get("seq");
                if (seq instanceof Long value && value <= since) {
                    continue;
                }
                out.add(entry);
                if (out.size() >= limit) {
                    break;
                }
            }
        }
        return out;
    }

    Map<String, Object> summary() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("tapped", new ArrayList<>(names.values()));
        Map<String, Object> counts = new LinkedHashMap<>();
        for (Map.Entry<UUID, Deque<Map<String, Object>>> entry : buffers.entrySet()) {
            String name = names.getOrDefault(entry.getKey(), entry.getKey().toString());
            synchronized (entry.getValue()) {
                counts.put(name, entry.getValue().size());
            }
        }
        m.put("counts", counts);
        m.put("cursor", clock.get());
        return m;
    }

    private UUID resolve(String wanted) {
        if (wanted == null || wanted.isEmpty()) {
            return names.isEmpty() ? null : names.keySet().iterator().next();
        }
        for (Map.Entry<UUID, String> entry : names.entrySet()) {
            if (entry.getValue().equalsIgnoreCase(wanted)) {
                return entry.getKey();
            }
        }
        try {
            return UUID.fromString(wanted);
        } catch (IllegalArgumentException ignored) {
            return null;
        }
    }

    // ------------------------------------------------------------------ recording

    private void record(UUID id, String direction, Object packet) {
        String simple = packet.getClass().getSimpleName();
        int dollar = simple.indexOf('$');
        if (dollar > 0) {
            simple = simple.substring(0, dollar);
        }
        String[] fields = INTERESTING.get(simple);
        if (fields == null) {
            return;
        }
        Map<String, Object> entry = new LinkedHashMap<>();
        entry.put("seq", clock.incrementAndGet());
        entry.put("t", System.currentTimeMillis());
        entry.put("dir", direction);
        entry.put("packet", simple);
        for (String field : fields) {
            Object value = read(packet, field);
            if (value != null) {
                entry.put(field, describe(value));
            }
        }
        Deque<Map<String, Object>> buffer = buffers.computeIfAbsent(id, key -> new ArrayDeque<>());
        synchronized (buffer) {
            buffer.addLast(entry);
            while (buffer.size() > MAX_ENTRIES) {
                buffer.removeFirst();
            }
        }
    }

    private Object describe(Object value) {
        Class<?> type = value.getClass();
        if (type.isEnum()) {
            return ((Enum<?>) value).name();
        }
        if (value instanceof Number || value instanceof Boolean || value instanceof String) {
            return value;
        }
        // BlockPos, Vec3 and friends: flatten the coordinates and keep the type name for context.
        Map<String, Object> m = new LinkedHashMap<>();
        boolean any = false;
        for (String axis : new String[] {"x", "y", "z", "getX", "getY", "getZ"}) {
            Object part = read(value, axis);
            if (part instanceof Number number) {
                m.put(axis.length() == 1 ? axis.toUpperCase(Locale.ROOT) : axis, number.doubleValue());
                any = true;
            }
        }
        if (any) {
            return m;
        }
        for (String accessor : new String[] {"asString", "toString"}) {
            Object text = call(value, accessor);
            if (text != null) {
                String s = String.valueOf(text);
                return s.length() > 160 ? s.substring(0, 160) + "..." : s;
            }
        }
        return String.valueOf(value);
    }

    /** Reads a record component or a getter or a field, whichever the class happens to have. */
    private Object read(Object target, String name) {
        for (String candidate : new String[] {name, "get" + capitalise(name), "is" + capitalise(name)}) {
            Object value = call(target, candidate);
            if (value != null) {
                return value;
            }
        }
        Field field = find(target.getClass(), name);
        if (field != null) {
            try {
                return field.get(target);
            } catch (Throwable ignored) {
                return null;
            }
        }
        return null;
    }

    private Object call(Object target, String method) {
        try {
            Method m = target.getClass().getMethod(method);
            m.setAccessible(true);
            return m.invoke(target);
        } catch (Throwable ignored) {
            return null;
        }
    }

    private Field find(Class<?> type, String name) {
        for (Class<?> c = type; c != null && c != Object.class; c = c.getSuperclass()) {
            try {
                Field field = c.getDeclaredField(name);
                field.setAccessible(true);
                return field;
            } catch (NoSuchFieldException ignored) {
                // Keep walking up; obfuscation-free names still live on one of these classes.
            }
        }
        return null;
    }

    private String capitalise(String name) {
        return name.isEmpty() ? name : Character.toUpperCase(name.charAt(0)) + name.substring(1);
    }

    private Channel channelOf(Player player) throws Exception {
        Object handle = player.getClass().getMethod("getHandle").invoke(player);
        Object connection = read(handle, "connection");
        if (connection == null) {
            return null;
        }
        Object listener = read(connection, "connection");
        if (listener == null) {
            listener = connection;
        }
        Object channel = read(listener, "channel");
        return channel instanceof Channel c ? c : null;
    }

    private final class TapHandler extends ChannelDuplexHandler {

        private final UUID id;

        private TapHandler(UUID id) {
            this.id = id;
        }

        @Override
        public void channelRead(ChannelHandlerContext ctx, Object msg) throws Exception {
            try {
                record(id, "in", msg);
            } catch (Throwable ignored) {
                // A tap must never break the connection it is watching.
            }
            super.channelRead(ctx, msg);
        }

        @Override
        public void write(ChannelHandlerContext ctx, Object msg, ChannelPromise promise) throws Exception {
            try {
                record(id, "out", msg);
            } catch (Throwable ignored) {
                // Same: never swallow or fail the real write.
            }
            super.write(ctx, msg, promise);
        }
    }
}
