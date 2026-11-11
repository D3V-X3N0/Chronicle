package io.github.d3vx3n0.chronicle.command;

import io.github.d3vx3n0.chronicle.config.ChronicleConfig;
import java.util.List;
import java.util.Locale;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.Server;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.command.ConsoleCommandSender;
import org.bukkit.command.TabExecutor;

public final class ChronicleCommand implements TabExecutor {
    private final String version;
    private final ChronicleConfig settings;
    private final Server server;

    public ChronicleCommand(String version, ChronicleConfig settings, Server server) {
        this.version = version;
        this.settings = settings;
        this.server = server;
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (!canViewStatus(sender)) {
            sender.sendMessage(Component.text("You need chronicle.status to view Chronicle status.", NamedTextColor.RED));
            return true;
        }
        if (args.length != 1 || !args[0].equalsIgnoreCase("status")) {
            sender.sendMessage(Component.text("Usage: /chronicle status", NamedTextColor.YELLOW));
            return true;
        }
        sender.sendMessage(Component.text("Chronicle " + version + " — development foundation", NamedTextColor.GOLD));
        sender.sendMessage(Component.text(
                "History capture, storage, inspection and rollback: NOT IMPLEMENTED. This build does not protect your server.",
                NamedTextColor.YELLOW));
        if (settings.showRuntimeDetails()) {
            sender.sendMessage(Component.text(
                    "Runtime: " + server.getName() + " / Minecraft " + server.getMinecraftVersion()
                            + " / Java " + Runtime.version().feature()));
        }
        return true;
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        if (canViewStatus(sender) && args.length == 1 && "status".startsWith(args[0].toLowerCase(Locale.ROOT))) {
            return List.of("status");
        }
        return List.of();
    }

    private static boolean canViewStatus(CommandSender sender) {
        return sender instanceof ConsoleCommandSender || sender.hasPermission("chronicle.status");
    }
}
