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
    private final Supplier<ChronicleConfig> configuration;
    private final Server server;
    private final Supplier<HistoryWriter.Status> health;
    private final java.util.function.IntSupplier reconciliationPending;
    private final java.util.function.LongSupplier observedSequence;
    private final InvestigationController investigation;
    private final ReloadController reload;
    private final io.github.d3vx3n0.chronicle.query.QueryService queries;

    public ChronicleCommand(String version, ChronicleConfig settings, Server server) {
        this(version, settings, server, () -> null);
    }
    public ChronicleCommand(String version, ChronicleConfig settings, Server server, Supplier<HistoryWriter.Status> health) {
        this(version, settings, server, health, () -> 0, () -> 0);
    }
    public ChronicleCommand(String version, ChronicleConfig settings, Server server, Supplier<HistoryWriter.Status> health,
            java.util.function.IntSupplier reconciliationPending, java.util.function.LongSupplier observedSequence) {
        this(version, () -> settings, server, health, reconciliationPending, observedSequence, null, null, null);
    }
    public ChronicleCommand(String version, Supplier<ChronicleConfig> settings, Server server, Supplier<HistoryWriter.Status> health,
            java.util.function.IntSupplier reconciliationPending, java.util.function.LongSupplier observedSequence,
            InvestigationController investigation, ReloadController reload, io.github.d3vx3n0.chronicle.query.QueryService queries) {
        this.version = version;
        this.configuration = settings;
        this.server = server;
        this.health = health;
        this.reconciliationPending = reconciliationPending; this.observedSequence = observedSequence;
        this.investigation = investigation; this.reload = reload; this.queries = queries;
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (args.length > 0 && investigation != null) {
            switch (args[0].toLowerCase(Locale.ROOT)) {
                case "lookup" -> { investigation.lookup(sender, java.util.Arrays.copyOfRange(args, 1, args.length)); return true; }
                case "inspect" -> {
                    if (args.length == 1) investigation.toggle(sender); else InvestigationController.error(sender, "Usage: /chronicle inspect");
                    return true;
                }
                case "reload" -> {
                    if (args.length == 1) reload.request(sender); else InvestigationController.error(sender, "Usage: /chronicle reload");
                    return true;
                }
                default -> { /* Status and usage below. */ }
            }
        }
        if (!canViewStatus(sender)) {
            sender.sendMessage(Component.text("You need chronicle.status to view Chronicle status.", NamedTextColor.RED));
            return true;
        }
        if (args.length != 1 || !args[0].equalsIgnoreCase("status")) {
            sender.sendMessage(Component.text(investigation == null ? "Usage: /chronicle status" : "Usage: /chronicle <inspect|lookup|status|reload>", NamedTextColor.YELLOW));
            return true;
        }
        ChronicleConfig settings = configuration.get();
        sender.sendMessage(Component.text("Chronicle " + version + " — development investigation", NamedTextColor.GOLD));
        sender.sendMessage(Component.text(
                "Qualified capture: stone/air player transitions only. Recovery: NOT IMPLEMENTED.",
                NamedTextColor.YELLOW));
        var status = health.get();
        if (status != null) {
            sender.sendMessage(Component.text("Storage: " + status.state() + "; schema=2; SQLite=" + status.engine() + "; session=" + status.session()));
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
        if (queries != null) sender.sendMessage(Component.text("Queries: " + queries.stats() + "; readers=2; waiting<=32; retained<=34; page size="
                + settings.pageSize() + "; deadline=2s; cursors<=128/120s. Reload: presentation only; storage requires restart."));
        if (settings.showRuntimeDetails()) {
            sender.sendMessage(Component.text(
                    "Runtime: " + server.getName() + " / Minecraft " + server.getMinecraftVersion()
                            + " / Java " + Runtime.version().feature()));
        }
        return true;
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        if (investigation != null && args.length == 1) {
            return List.of("inspect", "lookup", "status", "reload").stream()
                    .filter(name -> InvestigationController.allowed(sender, "chronicle." + name))
                    .filter(name -> name.startsWith(args[0].toLowerCase(Locale.ROOT))).toList();
        }
        if (investigation != null && args.length >= 2 && args.length <= 9 && args[0].equalsIgnoreCase("lookup"))
            return investigation.complete(sender, args[args.length - 1]);
        if (canViewStatus(sender) && args.length == 1 && "status".startsWith(args[0].toLowerCase(Locale.ROOT))) {
            return List.of("status");
        }
        return List.of();
    }

    private static boolean canViewStatus(CommandSender sender) {
        return sender instanceof ConsoleCommandSender || sender.hasPermission("chronicle.status");
    }
}
