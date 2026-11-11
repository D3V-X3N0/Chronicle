package io.github.d3vx3n0.chronicle.command;

import io.github.d3vx3n0.chronicle.config.ChronicleConfig;
import java.util.List;
import java.util.Locale;
import java.util.function.Supplier;
import io.github.d3vx3n0.chronicle.storage.HistoryWriter;
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
    private final Supplier<HistoryWriter.Status> health;
    private final java.util.function.IntSupplier reconciliationPending;
    private final java.util.function.LongSupplier observedSequence;

    public ChronicleCommand(String version, ChronicleConfig settings, Server server) {
        this(version, settings, server, () -> null);
    }
    public ChronicleCommand(String version, ChronicleConfig settings, Server server, Supplier<HistoryWriter.Status> health) {
        this(version, settings, server, health, () -> 0, () -> 0);
    }
    public ChronicleCommand(String version, ChronicleConfig settings, Server server, Supplier<HistoryWriter.Status> health,
            java.util.function.IntSupplier reconciliationPending, java.util.function.LongSupplier observedSequence) {
        this.version = version;
        this.settings = settings;
        this.server = server;
        this.health = health;
        this.reconciliationPending = reconciliationPending; this.observedSequence = observedSequence;
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
                "Qualified capture: stone/air player transitions only. Inspection and rollback: NOT IMPLEMENTED.",
                NamedTextColor.YELLOW));
        var status = health.get();
        if (status != null) {
            sender.sendMessage(Component.text("Storage: " + status.state() + "; schema=1; SQLite=" + status.engine() + "; session=" + status.session()));
            sender.sendMessage(Component.text("Accepted=" + status.accepted() + "; committed=" + status.committed()
                    + "; capture sequence=" + status.committedSequence() + "; history ID=" + status.committedId()));
            sender.sendMessage(Component.text("Reconciliation pending=" + reconciliationPending.getAsInt()
                    + "; observed sequence=" + observedSequence.getAsLong()));
            sender.sendMessage(Component.text("Writer pending=" + status.pendingRecords() + "/" + settings.storage().maxRecords()
                    + "; bytes=" + status.pendingBytes() + "/" + settings.storage().maxBytes()
                    + "; oldest=" + status.oldestMillis() + "ms; rejected=" + status.rejected()));
            sender.sendMessage(Component.text("Coverage is limited. Previous unclean sessions=" + status.previousUnclean()
                    + "; previous incident aggregates=" + status.previousCoverageIncidents()
                    + "; incidents=" + status.incidents().stream().map(value -> value.reason() + ":" + value.count()).toList(), NamedTextColor.YELLOW));
            if (!status.failure().equals("none")) sender.sendMessage(Component.text("Storage failure: " + status.failure(), NamedTextColor.RED));
        }
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
