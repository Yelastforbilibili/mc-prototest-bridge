package com.prototest.bridge;

import java.util.ArrayList;
import java.util.List;

import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;

import net.kyori.adventure.text.Component;

/**
 * The bot self-registration and the operator side of the spectator view.
 *
 * <ul>
 *   <li>{@code /botregister} (alias {@code /botreg}) — a bot announces itself. Bots call this right after joining;
 *       a player may call it too, nothing here trusts the claim for anything but viewing.</li>
 *   <li>{@code /see [bot]} — watch a registered bot's inventory. With one bot online the name is optional; with
 *       several it is required. Watching yourself is refused.</li>
 *   <li>{@code /see stop} — end the view and give the viewer their own items back.</li>
 *   <li>{@code /seepattern chest|inv} — pick how the inventory is shown; switching while watching restarts the
 *       view in the new pattern.</li>
 * </ul>
 */
public final class SpectatorCommand implements CommandExecutor, TabCompleter {

    private final JavaPlugin plugin;
    private final BotRegistry bots;
    private final SpectatorService spectate;

    public SpectatorCommand(JavaPlugin plugin, BotRegistry bots, SpectatorService spectate) {
        this.plugin = plugin;
        this.bots = bots;
        this.spectate = spectate;
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        String name = command.getName().toLowerCase(java.util.Locale.ROOT);
        if (name.equals("botregister") || name.equals("botreg") || name.equals("botunregister")) {
            return register(sender, name, args);
        }
        if (name.equals("seepattern")) {
            return pattern(sender, args);
        }
        return see(sender, args);
    }

    private boolean register(CommandSender sender, String name, String[] args) {
        if (!(sender instanceof Player player)) {
            reply(sender, "这个命令只能由玩家执行");
            return true;
        }
        if (name.equals("botunregister")) {
            boolean removed = bots.unregister(player);
            reply(sender, removed ? "已取消 bot 注册" : "你本来就没有注册为 bot");
            return true;
        }
        if (!player.hasPermission("prototest.bot")) {
            reply(sender, "你没有权限注册为 bot");
            return true;
        }
        boolean added = bots.register(player);
        reply(sender, added
                ? "已注册为 bot：" + player.getName() + "（op 可用 /see 观看）"
                : "已经是注册 bot 了：" + player.getName());
        return true;
    }

    private boolean pattern(CommandSender sender, String[] args) {
        Player viewer = sender instanceof Player player ? player : null;
        if (viewer == null) {
            reply(sender, "这个命令只能由玩家执行");
            return true;
        }
        if (!viewer.hasPermission("prototest.see")) {
            reply(sender, "你没有权限使用观看模式");
            return true;
        }
        if (args.length == 0) {
            reply(sender, "当前观看方式：" + spectate.patternOf(viewer).name().toLowerCase(java.util.Locale.ROOT)
                    + "（用 /seepattern chest 或 /seepattern inv 切换）");
            return true;
        }
        String wanted = args[0].toLowerCase(java.util.Locale.ROOT);
        if (wanted.equals("chest") || wanted.equals("inv")) {
            SpectatorService.Pattern pattern = wanted.equals("chest")
                    ? SpectatorService.Pattern.CHEST
                    : SpectatorService.Pattern.INV;
            spectate.setPattern(viewer, pattern);
            String watching = spectate.targetName(viewer);
            if (watching != null) {
                Player target = bots.find(watching);
                if (target != null) {
                    reply(sender, spectate.start(viewer, target, pattern));
                    return true;
                }
            }
            reply(sender, "观看方式已切换为 " + wanted
                    + (wanted.equals("chest") ? "（箱子界面，关闭即退出）" : "（原生背包，关闭不退出，需 /see stop）"));
            return true;
        }
        reply(sender, "未知的观看方式：" + args[0] + "（chest 或 inv）");
        return true;
    }

    private boolean see(CommandSender sender, String[] args) {
        if (!(sender instanceof Player viewer)) {
            reply(sender, "这个命令只能由玩家执行；bot 列表：" + names());
            return true;
        }
        if (!viewer.hasPermission("prototest.see")) {
            reply(sender, "你没有权限使用观看模式");
            return true;
        }
        String arg = args.length == 0 ? "" : args[0];
        if (arg.equalsIgnoreCase("stop") || arg.equalsIgnoreCase("off")) {
            reply(sender, spectate.stop(viewer));
            return true;
        }
        if (arg.equalsIgnoreCase("list") || arg.equalsIgnoreCase("?")) {
            reply(sender, "注册的 bot：" + names());
            return true;
        }
        Player target;
        if (arg.isEmpty()) {
            List<Player> online = bots.online();
            if (online.isEmpty()) {
                reply(sender, "现在没有已注册的 bot 在线；bot 连上后会自己执行 /botregister");
                return true;
            }
            if (online.size() > 1) {
                List<String> names = new ArrayList<>();
                for (Player bot : online) {
                    names.add(bot.getName());
                }
                reply(sender, "有多个 bot 在线，请用 /see <名字>：" + String.join(", ", names));
                return true;
            }
            target = online.get(0);
        } else {
            target = bots.find(arg);
            if (target == null) {
                reply(sender, "没有叫 " + arg + " 的注册 bot；注册的 bot：" + names());
                return true;
            }
        }
        if (target.getUniqueId().equals(viewer.getUniqueId())) {
            reply(sender, "不能观看自己");
            return true;
        }
        reply(sender, spectate.start(viewer, target, spectate.patternOf(viewer)));
        return true;
    }

    private String names() {
        List<String> names = bots.names();
        return names.isEmpty() ? "（无）" : String.join(", ", names);
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        String name = command.getName().toLowerCase(java.util.Locale.ROOT);
        if (name.equals("seepattern")) {
            return args.length == 1 ? filter(List.of("chest", "inv"), args[0]) : List.of();
        }
        if (name.equals("see")) {
            if (args.length != 1) {
                return List.of();
            }
            List<String> options = new ArrayList<>(bots.names());
            options.add("stop");
            options.add("list");
            return filter(options, args[0]);
        }
        return List.of();
    }

    private static List<String> filter(List<String> options, String prefix) {
        List<String> matched = new ArrayList<>();
        String lower = prefix.toLowerCase(java.util.Locale.ROOT);
        for (String option : options) {
            if (option.toLowerCase(java.util.Locale.ROOT).startsWith(lower)) {
                matched.add(option);
            }
        }
        return matched;
    }

    private void reply(CommandSender sender, String text) {
        sender.sendMessage(Component.text(text));
    }
}
