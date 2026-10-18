package com.prototest.bridge;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.KeybindComponent;
import net.kyori.adventure.text.SelectorComponent;
import net.kyori.adventure.text.TextComponent;
import net.kyori.adventure.text.TranslatableComponent;
import net.kyori.adventure.translation.GlobalTranslator;

/**
 * Flattens an Adventure {@link Component} to plain text.
 *
 * <p>Server-side feedback is mostly {@link TranslatableComponent}s (vanilla sends the key plus arguments
 * and lets the client localise), and the plain serializer would print the bare key - {@code
 * commands.list.players} - which hides the arguments entirely. This tries the platform-wide translator
 * first and falls back to {@code key(arg1, arg2)} so a test client can both read the message and see
 * exactly which translation key the server chose.
 */
final class Plain {

    private Plain() {
    }

    static String of(Component c) {
        if (c == null) {
            return "";
        }
        Component rendered = c;
        try {
            rendered = GlobalTranslator.render(c, Locale.US);
        } catch (Throwable ignored) {
            // no translator registered: fall through to the manual renderer
        }
        if (rendered == null) {
            rendered = c;
        }
        StringBuilder sb = new StringBuilder();
        walk(rendered, sb, 0);
        if (sb.toString().isEmpty()) {
            walk(c, sb, 0);
        }
        return sb.toString();
    }

    /** Every translation key the component (and its children) references, in order. */
    static List<String> keys(Component c) {
        List<String> out = new ArrayList<>();
        collectKeys(c, out);
        return out;
    }

    private static void collectKeys(Component c, List<String> out) {
        if (c == null) {
            return;
        }
        if (c instanceof TranslatableComponent t) {
            out.add(t.key());
        }
        for (Component child : c.children()) {
            collectKeys(child, out);
        }
    }

    private static void walk(Component c, StringBuilder sb, int depth) {
        if (c == null || depth > 32) {
            return;
        }
        if (c instanceof TextComponent t) {
            sb.append(t.content());
        } else if (c instanceof TranslatableComponent t) {
            sb.append(t.key());
            List<net.kyori.adventure.text.TranslationArgument> args = t.arguments();
            if (!args.isEmpty()) {
                sb.append('(');
                for (int i = 0; i < args.size(); i++) {
                    if (i > 0) {
                        sb.append(", ");
                    }
                    StringBuilder arg = new StringBuilder();
                    walk(args.get(i).asComponent(), arg, depth + 1);
                    sb.append(arg);
                }
                sb.append(')');
            }
        } else if (c instanceof SelectorComponent t) {
            sb.append(t.pattern());
        } else if (c instanceof KeybindComponent t) {
            sb.append(t.keybind());
        }
        for (Component child : c.children()) {
            walk(child, sb, depth + 1);
        }
    }
}
