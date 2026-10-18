package com.prototest.bridge;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.logging.Handler;
import java.util.logging.Level;
import java.util.logging.LogRecord;
import java.util.logging.Logger;

/**
 * Captures the server console log into a bounded ring buffer so an automated client can ask
 * "what did the server print after my last command" without tailing files.
 *
 * <p>Two things make this fiddly on Paper: the platform logs through log4j with a JUL bridge, and its
 * delayed initialisation resets the JUL log manager after plugins enable - which silently removes a
 * handler attached during {@code onEnable}. So the handler is attached to several loggers, re-attached
 * periodically by {@link #ensureInstalled()}, and every record is de-duplicated by identity in case a
 * record reaches us through both a named logger and the root logger.
 */
final class LogCapture {

    private final ArrayDeque<Map<String, Object>> ring = new ArrayDeque<>();
    private final Object lock = new Object();
    /** Recently seen records, so one record published through several loggers is stored once. */
    private final Set<LogRecord> seen = Collections.newSetFromMap(new IdentityHashMap<>());
    private final ArrayDeque<LogRecord> seenOrder = new ArrayDeque<>();
    private long seq;
    private Handler handler;
    private boolean installed;

    /** The loggers the console output actually flows through on a Paper server. */
    private static final String[] LOGGERS = {"", "Minecraft", "net.minecraft.server", "org.bukkit.craftbukkit"};

    void install() {
        if (handler == null) {
            handler = new Handler() {
                @Override
                public void publish(LogRecord record) {
                    if (record != null) {
                        LogCapture.this.publish(record);
                    }
                }

                @Override
                public void flush() {
                }

                @Override
                public void close() {
                }
            };
            handler.setLevel(Level.ALL);
        }
        for (String name : LOGGERS) {
            Logger logger = Logger.getLogger(name);
            boolean present = false;
            for (Handler h : logger.getHandlers()) {
                if (h == handler) {
                    present = true;
                    break;
                }
            }
            if (!present) {
                logger.addHandler(handler);
            }
        }
        installed = true;
        record("prototest-bridge log capture installed");
    }

    /** Re-attach after a log-manager reset; cheap enough to call from a repeating task. */
    void ensureInstalled() {
        if (handler == null) {
            return;
        }
        for (String name : LOGGERS) {
            Logger logger = Logger.getLogger(name);
            boolean present = false;
            for (Handler h : logger.getHandlers()) {
                if (h == handler) {
                    present = true;
                    break;
                }
            }
            if (!present) {
                logger.addHandler(handler);
                installed = true;
            }
        }
    }

    boolean installed() {
        return installed;
    }

    void uninstall() {
        if (handler != null) {
            for (String name : LOGGERS) {
                Logger.getLogger(name).removeHandler(handler);
            }
            handler = null;
        }
    }

    private void publish(LogRecord record) {
        synchronized (lock) {
            if (!seen.add(record)) {
                return;
            }
            seenOrder.addLast(record);
            while (seenOrder.size() > 512) {
                seen.remove(seenOrder.removeFirst());
            }
        }
        String message = record.getMessage();
        if (message != null && record.getParameters() != null && record.getParameters().length > 0) {
            try {
                message = java.text.MessageFormat.format(message, record.getParameters());
            } catch (IllegalArgumentException ignored) {
                // keep the raw pattern when the parameters do not match it
            }
        }
        Throwable thrown = record.getThrown();
        if (thrown != null) {
            message = (message == null ? "" : message) + " | " + thrown;
        }
        add(record.getLevel(), record.getLoggerName(), message);
    }

    private void add(Level level, String source, String message) {
        Map<String, Object> entry = new LinkedHashMap<>();
        synchronized (lock) {
            entry.put("seq", ++seq);
            entry.put("t", System.currentTimeMillis());
            entry.put("level", level == null ? "INFO" : level.getName());
            entry.put("source", source == null ? "" : source);
            entry.put("msg", message == null ? "" : message);
            ring.addLast(entry);
            while (ring.size() > 4000) {
                ring.removeFirst();
            }
        }
    }

    void record(String message) {
        add(Level.INFO, "prototest-bridge", message);
    }

    long lastSeq() {
        synchronized (lock) {
            return seq;
        }
    }

    List<Object> since(long fromSeq, int limit, String pattern) {
        List<Object> out = new ArrayList<>();
        synchronized (lock) {
            for (Map<String, Object> e : ring) {
                if ((long) (Long) e.get("seq") <= fromSeq) {
                    continue;
                }
                if (pattern != null && !pattern.isEmpty()) {
                    String msg = String.valueOf(e.get("msg"));
                    if (!msg.toLowerCase().contains(pattern.toLowerCase())) {
                        continue;
                    }
                }
                out.add(e);
                if (out.size() >= limit) {
                    break;
                }
            }
        }
        return out;
    }

    /**
     * Tail the platform's own {@code logs/latest.log}. Used as a fallback oracle: it shows the console
     * exactly as a human operator sees it, including lines the JUL bridge swallows.
     */
    List<String> tailFile(int lines) {
        List<String> out = new ArrayList<>();
        File file = new File("logs/latest.log");
        if (!file.isFile()) {
            return out;
        }
        try {
            List<String> all = Files.readAllLines(file.toPath(), StandardCharsets.UTF_8);
            int from = Math.max(0, all.size() - Math.max(1, lines));
            for (int i = from; i < all.size(); i++) {
                out.add(all.get(i));
            }
        } catch (IOException ignored) {
            // a rotated or locked log simply yields no lines
        }
        return out;
    }
}
