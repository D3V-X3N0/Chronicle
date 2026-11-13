package io.github.d3vx3n0.chronicle.command;

import io.github.d3vx3n0.chronicle.config.ChronicleConfig;
import io.github.d3vx3n0.chronicle.query.*;
import io.github.d3vx3n0.chronicle.storage.HistoryWriter;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.IntSupplier;
import java.util.function.Supplier;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.event.ClickEvent;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.Bukkit;
import org.bukkit.Server;
import org.bukkit.command.CommandSender;
import org.bukkit.command.ConsoleCommandSender;
import org.bukkit.entity.Player;
import org.bukkit.event.Event;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.inventory.EquipmentSlot;

/** Paper objects never cross to readers. All methods are called on the owning server thread. */
public final class InvestigationController implements Listener, AutoCloseable {
    private static final UUID CONSOLE = new UUID(0, 0);
    private record Pending(UUID owner, CommandSender sender, String permission, QueryService.Ticket ticket, int limit, long expires) {}
    private final QueryService queries;
    private final Server server;
    private final Supplier<ChronicleConfig> settings;
    private final Supplier<HistoryWriter.Status> health;
    private final IntSupplier reconciliation;
    private final CursorRegistry cursors = new CursorRegistry();
    private final Map<UUID, Pending> pending = new HashMap<>();
    private final Set<UUID> inspectors = new HashSet<>();
    private final LinkedHashSet<String> recentNames = new LinkedHashSet<>();

    public InvestigationController(QueryService queries, Server server, Supplier<ChronicleConfig> settings,
            Supplier<HistoryWriter.Status> health, IntSupplier reconciliation) {
        this.queries = queries; this.server = server; this.settings = settings; this.health = health; this.reconciliation = reconciliation;
    }
    public static boolean allowed(CommandSender sender, String permission) {
        return sender instanceof ConsoleCommandSender || sender.hasPermission(permission);
    }
    public void lookup(CommandSender sender, String[] args) {
        checkThread();
        try {
            UUID owner = owner(sender);
            if (args.length == 1 && args[0].startsWith("page:")) {
                var cursor = cursors.get(owner, args[0].substring(5));
                if (!allowed(sender, cursor.permission())) { cursors.clear(owner); deny(sender, cursor.permission()); return; }
                submit(sender, cursor.permission(), null, cursor.continuation(), cursor.limit(), cursor.expires());
            } else {
                if (!allowed(sender, "chronicle.lookup")) { deny(sender, "chronicle.lookup"); return; }
                LookupInput.Origin origin = null;
                if (sender instanceof Player player) {
                    var location = player.getLocation();
                    origin = new LookupInput.Origin(player.getWorld().getUID(), location.getBlockX(), location.getBlockZ());
                }
                var input = LookupInput.parse(args, System.currentTimeMillis(), origin);
                submit(sender, "chronicle.lookup", input, null, settings.get().pageSize(), System.nanoTime() + CursorRegistry.TTL_NANOS);
            }
        } catch (IllegalArgumentException error) { error(sender, error.getMessage()); }
    }
    public void toggle(CommandSender sender) {
        checkThread();
        if (!allowed(sender, "chronicle.inspect")) { deny(sender, "chronicle.inspect"); return; }
        if (!(sender instanceof Player player)) { error(sender, "Inspection needs a player; console can use lookup with world and origin."); return; }
        UUID id = player.getUniqueId();
        if (inspectors.remove(id)) { cancel(id); message(sender, "Inspection disabled."); }
        else if (inspectors.size() >= 128) error(sender, "Inspection capacity reached; try later.");
        else { inspectors.add(id); message(sender, "Inspection enabled: main-hand left/right block clicks. Protected/denied clicks return no history."); }
    }
    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = false)
    public void interact(PlayerInteractEvent event) {
        checkThread();
        var player = event.getPlayer();
        if (!inspecting(player) || event.getClickedBlock() == null
                || event.getAction() != Action.LEFT_CLICK_BLOCK && event.getAction() != Action.RIGHT_CLICK_BLOCK) return;
        boolean denied = event.useInteractedBlock() == Event.Result.DENY || event.useItemInHand() == Event.Result.DENY;
        event.setUseInteractedBlock(Event.Result.DENY); event.setUseItemInHand(Event.Result.DENY);
        if (event.getHand() != EquipmentSlot.HAND) return;
        if (denied) { message(player, "Inspection denied by interaction/protection state; no history requested."); return; }
        // One outstanding click per inspector; rapid repeated/off-hand packets cannot create a result flood.
        if (pending.containsKey(player.getUniqueId())) return;
        var block = event.getClickedBlock();
        var input = new LookupInput(List.of(), block.getWorld().getUID().toString(), 0, System.currentTimeMillis(),
                Set.of("PLACE", "BREAK"), new LookupFilter.Area(block.getX(), block.getY(), block.getZ(), 0));
        submit(player, "chronicle.inspect", input, null, settings.get().pageSize(), System.nanoTime() + CursorRegistry.TTL_NANOS);
    }
    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = false)
    public void preventBreak(BlockBreakEvent event) { checkThread(); if (inspecting(event.getPlayer())) event.setCancelled(true); }
    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = false)
    public void preventPlace(BlockPlaceEvent event) { checkThread(); if (inspecting(event.getPlayer())) event.setCancelled(true); }
    @EventHandler public void quit(PlayerQuitEvent event) { checkThread(); UUID owner = event.getPlayer().getUniqueId(); inspectors.remove(owner); cancel(owner); }
    private boolean inspecting(Player player) {
        UUID owner = player.getUniqueId();
        if (!inspectors.contains(owner)) return false;
        if (!allowed(player, "chronicle.inspect")) { inspectors.remove(owner); cancel(owner); return false; }
        return true;
    }
    private void submit(CommandSender sender, String permission, LookupInput input, QueryService.Continuation cursor, int limit, long expires) {
        var status = health.get();
        if (status == null || status.session() == 0 || status.state() == HistoryWriter.State.STARTING
                || status.state() == HistoryWriter.State.STOPPING || status.state() == HistoryWriter.State.CLOSED) {
            error(sender, "History unavailable while storage is starting or stopping; check /chronicle status."); return;
        }
        var limits = settings.get().storage();
        if (status.state() == HistoryWriter.State.READY && (status.pendingRecords() >= Math.max(1, limits.maxRecords() * 4 / 5)
                || status.pendingBytes() >= limits.maxBytes() * 4 / 5)) {
            error(sender, "Lookups suspended while recording backlog is high; retry when storage catches up."); return;
        }
        UUID owner = owner(sender);
        if (pending.containsKey(owner)) { error(sender, "A lookup is already pending; wait for it or disconnect to cancel."); return; }
        var ticket = queries.submit(input, cursor, limit);
        if (ticket == null) { error(sender, "Lookup capacity reached; retry shortly."); return; }
        if (cursor == null) cursors.clear(owner);
        pending.put(owner, new Pending(owner, sender, permission, ticket, limit, expires));
        message(sender, "Lookup queued; only committed history will be shown.");
    }
    public void tick() {
        checkThread();
        // Permission checks also cancel active SQL; recheck again at delivery.
        for (Pending value : List.copyOf(pending.values())) {
            if (!deliverable(value.sender, value.permission)) { cancel(value.owner); }
        }
        queries.drain(this::deliver); cursors.purge();
    }
    private void deliver(QueryService.Result result) {
        Pending request = pending.values().stream().filter(p -> p.ticket.id().equals(result.ticket().id())).findFirst().orElse(null);
        if (request == null) return;
        pending.remove(request.owner);
        if (!deliverable(request.sender, request.permission)) { cursors.clear(request.owner); return; }
        if (System.nanoTime() >= request.expires) { cursors.clear(request.owner); error(request.sender, "Lookup expired; start a new request."); return; }
        if (result.error() != null) { error(request.sender, result.error()); return; }
        var page = result.page();
        message(request.sender, "Committed history boundary=" + page.boundary() + "; newest capture first; " + page.rows().size() + " row(s).");
        message(request.sender, page.filter().describe());
        warnings(request.sender);
        if (page.rows().isEmpty()) message(request.sender, "No matching committed rows. This does not prove no changes occurred.");
        for (var row : page.rows()) {
            String name = plain(row.name());
            recentNames.remove(name); recentNames.add(name);
            if (recentNames.size() > 128) recentNames.removeFirst();
            message(request.sender, "#" + row.id() + " " + Instant.ofEpochMilli(row.occurredAt()) + " " + row.action()
                    + " " + name + " (" + row.actor() + ") " + row.attribution());
            message(request.sender, "  " + row.world() + " @ " + row.x() + "," + row.y() + "," + row.z()
                    + " " + plain(row.before()) + " -> " + plain(row.after()) + "; capture=" + row.session() + "/" + row.sequence() + "/" + row.member()
                    + "; " + row.evidence() + "; codec=" + row.codec() + "/data=" + row.dataVersion()
                    + (row.understoodSnapshot() ? "; complete qualified snapshot" : "; UNKNOWN/UNSUPPORTED snapshot")
                    + "; recovery requires a fresh preview; operation=" + (row.operation() == null ? "none" : row.operation() + "/" + row.operationStep()));
        }
        try {
            var cursor = cursors.create(request.owner, request.permission, page, request.limit, request.expires);
            if (cursor != null) request.sender.sendMessage(Component.text("Next: /chronicle lookup page:" + cursor.token(), NamedTextColor.AQUA)
                    .clickEvent(ClickEvent.runCommand("/chronicle lookup page:" + cursor.token())));
        } catch (IllegalArgumentException full) { error(request.sender, full.getMessage()); }
        message(request.sender, "Lookup page complete.");
    }
    private void warnings(CommandSender sender) {
        var status = health.get();
        message(sender, "Coverage LIMITED: only qualified direct-player stone/air. Unsupported/direct-plugin changes can be missing.");
        if (status != null) message(sender, "Storage=" + status.state() + "; acknowledged history=" + status.committedId()
                + "; writer pending=" + status.pendingRecords() + "; oldest=" + status.oldestMillis() + "ms; reconciliation=" + reconciliation.getAsInt()
                + "; previous unclean=" + status.previousUnclean() + "; previous incidents=" + status.previousCoverageIncidents()
                + "; current=" + status.incidents().stream().map(i -> i.reason() + ":" + i.count()).toList());
    }
    public List<String> complete(CommandSender sender, String prefix) {
        if (!allowed(sender, "chronicle.lookup")) return List.of();
        var choices = new ArrayList<>(List.of("player:", "world:", "time:1h", "time:2h", "radius:20", "origin:", "from:", "to:",
                "action:block-break", "action:block-place", "action:block-place,block-break"));
        server.getWorlds().stream().limit(32).forEach(world -> choices.add("world:" + world.getName()));
        recentNames.stream().filter(n -> n.matches("[A-Za-z0-9_]{1,16}")).forEach(name -> choices.add("player:" + name));
        if (sender instanceof Player || sender instanceof ConsoleCommandSender) {
            String token = cursors.token(owner(sender)); if (!token.isEmpty()) choices.add("page:" + token);
        }
        return choices.stream().filter(s -> s.startsWith(prefix)).limit(50).toList();
    }
    private void cancel(UUID owner) {
        var old = pending.remove(owner); if (old != null) old.ticket.cancel(); cursors.clear(owner);
    }
    private static UUID owner(CommandSender sender) {
        if (sender instanceof Player player) return player.getUniqueId();
        if (sender instanceof ConsoleCommandSender) return CONSOLE;
        throw new IllegalArgumentException("Investigation requires a player or local console");
    }
    public static boolean deliverable(CommandSender sender, String permission) {
        return allowed(sender, permission) && (!(sender instanceof Player player) || player.isOnline());
    }
    public static void deny(CommandSender sender, String permission) { error(sender, "You need " + permission + " for this command."); }
    public static void error(CommandSender sender, String text) { sender.sendMessage(Component.text(plain(text), NamedTextColor.RED)); }
    public static void message(CommandSender sender, String text) { sender.sendMessage(Component.text(plain(text))); }
    private static String plain(String text) { return text.replaceAll("[\\p{Cntrl}§]", "?"); }
    private static void checkThread() { if (!Bukkit.isPrimaryThread()) throw new IllegalStateException("Investigation called off the server thread"); }
    @Override public void close() { checkThread(); pending.values().forEach(p -> p.ticket.cancel()); pending.clear(); cursors.clear(); inspectors.clear(); queries.close(); }
}
