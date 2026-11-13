package io.github.d3vx3n0.chronicle.recovery;

import io.github.d3vx3n0.chronicle.capture.BlockReconciler.Position;
import io.github.d3vx3n0.chronicle.capture.PaperBlockCapture;
import io.github.d3vx3n0.chronicle.command.InvestigationController;
import io.github.d3vx3n0.chronicle.model.BlockChange;
import io.github.d3vx3n0.chronicle.model.CaptureGroup;
import io.github.d3vx3n0.chronicle.query.LookupInput;
import io.github.d3vx3n0.chronicle.storage.HistoryWriter;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Consumer;
import org.bukkit.Bukkit;
import org.bukkit.block.Block;
import org.bukkit.command.CommandSender;
import org.bukkit.command.ConsoleCommandSender;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockPhysicsEvent;

/** One bounded recovery context, all Paper access on the server thread, no blocking I/O waits. */
public final class RecoveryController implements Listener, AutoCloseable {
    private final HistoryWriter writer;
    private final PaperBlockCapture capture;
    private final InertCodec codec = new InertCodec();
    private final Map<Position, Long> revisions = new HashMap<>();
    private final Map<Position, String> simulated = new HashMap<>();
    private final Map<Position, Long> latest = new HashMap<>();
    private final Map<Integer, InertCodec.Snapshot> snapshots = new HashMap<>();
    private final Map<Integer, String> exclusions = new HashMap<>();
    private HistoryWriter.Control<?> pending;
    private Runnable completion;
    private RecoveryPlan plan;
    private CommandSender requester;
    private String phase = "IDLE", pauseReason = "";
    private int cursor, applied, skipped;
    private long barrier, reservation, maxCallNanos, maxSliceNanos, overruns;
    private long previewDeadlineNanos;
    private Position mutation;
    private InertCodec.Snapshot post;
    private boolean closed, pauseRequested;
    private RecoveryRepository.Operation review;
    private String reviewPermission;
    private String reviewReturnPhase;
    private int reviewCursor;
    private String requestPermission = "chronicle.rollback";
    // Test fixtures can replace this through reflection to kill disposable servers at exact boundaries.
    // Production has no configuration/system-property path to enable fault injection.
    private Consumer<String> boundaryProbe = boundary -> {};
    public RecoveryController(HistoryWriter writer, PaperBlockCapture capture) {
        this.writer = writer; this.capture = capture; capture.revisions(this::observed);
    }
    public void command(CommandSender sender, String kind, String[] args) {
        InertCodec.checkThread();
        String permission = kind.equals("ROLLBACK") ? "chronicle.rollback" : "chronicle.restore";
        if (!allowed(sender, permission)) { message(sender, "You need " + permission); return; }
        try {
            String requestOwner = owner(sender); // Copy player identity before crossing to the writer.
            if (args.length == 2 && args[0].equals("confirm")) {
                UUID id = UUID.fromString(args[1]);
                if (plan != null && plan.id().equals(id) && !phase.equals("READY")) {
                    if (!plan.owner().equals(requestOwner) || !matchesKind(plan, kind)) throw new IllegalArgumentException("Foreign preview");
                    message(sender, status()); return;
                }
                if (pending != null || !phase.equals("READY") || plan == null || !plan.id().equals(id)) {
                    if (pending != null) throw new IllegalArgumentException("Operation " + id + ": recovery query busy; retry status");
                    submitRead(r -> r.operation(id), op -> {
                        if (!op.plan().owner().equals(requestOwner) || !matchesKind(op.plan(), kind)) throw new IllegalArgumentException("Foreign preview");
                        if (op.state().equals("PREVIEW_READY")) throw new IllegalArgumentException("No live preview context; request a new preview");
                        if (allowed(sender, permission)) message(sender, "Operation " + id + ": " + op.state());
                    }, sender, permission);
                    return;
                }
                if (!plan.owner().equals(requestOwner) || !matchesKind(plan, kind)) throw new IllegalArgumentException("Foreign preview");
                if (System.nanoTime() >= previewDeadlineNanos) { phase = "DONE"; throw new IllegalArgumentException("Preview expired; request a new preview"); }
                requester = sender; requestPermission = permission;
                submit(r -> r.confirm(id, requestOwner, plan.kind(), System.currentTimeMillis()), op -> {
                    if (op.state().equals("PREPARED")) { phase = "EXECUTE"; cursor = 0; feedback("Operation " + id + " prepared"); }
                    else { phase = "DONE"; feedback("Operation " + id + ": " + op.state()); }
                }); return;
            }
            if (args.length == 1 && args[0].equals("pause")) {
                if (plan == null || !plan.owner().equals(requestOwner)) throw new IllegalArgumentException("No owned active operation");
                pauseRequested = true; pauseReason = "Operator requested pause"; message(sender, "Pause requested; committed/applied work is retained"); return;
            }
            if (args.length == 2 && (args[0].equals("continue") || kind.equals("ROLLBACK") && args[0].equals("compensate"))) {
                if (pending != null || !List.of("IDLE", "DONE", "PAUSED").contains(phase)) throw new IllegalArgumentException("Recovery busy");
                if (!healthy() || capture.pending() != 0 || writer.status().pendingRecords() != 0) throw new IllegalArgumentException("Wait for healthy drained capture");
                UUID parent = UUID.fromString(args[1]); boolean compensate = args[0].equals("compensate");
                reset(); requester = sender; requestPermission = permission; barrier = capture.observedSequence(); phase = "QUERY";
                submit(r -> {
                    if (!matchesKind(r.operation(parent).plan(), kind)) throw new IllegalArgumentException("Use the matching rollback/restore continuation command");
                    return r.reviewed(parent, requestOwner, barrier, System.currentTimeMillis(), compensate);
                }, this::beginPreview); return;
            }
            if (args.length == 2 && args[0].equals("review")) {
                if (pending != null || !List.of("IDLE", "DONE", "PAUSED").contains(phase)) throw new IllegalArgumentException("Recovery busy");
                UUID id = UUID.fromString(args[1]); requester = sender; requestPermission = permission;
                submitRead(r -> r.operation(id), op -> {
                    if (!allowed(sender, permission)) return;
                    review = op; reviewPermission = permission; reviewCursor = 0; reviewReturnPhase = phase; phase = "REVIEW";
                    message(sender, "Operation " + id + " " + op.state() + " " + op.reason());
                }, sender, permission); return;
            }
            if (args.length < 1 || !args[0].equals("preview")) throw new IllegalArgumentException("Use preview <filters/rollback-id>, confirm <preview-id>, pause, review <operation-id>");
            if (pending != null || !List.of("IDLE", "DONE", "READY").contains(phase)) throw new IllegalArgumentException("One recovery operation at a time");
            if (!healthy() || capture.pending() != 0 || writer.status().pendingRecords() != 0) throw new IllegalArgumentException("Wait for READY and drained capture/history before preview");
            reset(); requester = sender; requestPermission = permission; barrier = capture.observedSequence(); phase = "QUERY";
            long now = System.currentTimeMillis();
            if (kind.equals("RESTORE")) {
                if (args.length != 2) throw new IllegalArgumentException("restore preview <rollback-id>");
                UUID parent = UUID.fromString(args[1]);
                submit(r -> r.restore(parent, requestOwner, barrier, now), this::beginPreview);
            } else {
                var filters = Arrays.copyOfRange(args, 1, args.length);
                if (Arrays.stream(filters).noneMatch(a -> a.startsWith("time:") || a.startsWith("from:"))
                        || Arrays.stream(filters).noneMatch(a -> a.startsWith("world:"))
                        || Arrays.stream(filters).noneMatch(a -> a.startsWith("action:")))
                    throw new IllegalArgumentException("Recovery needs explicit time, world, action and radius");
                LookupInput.Origin origin = null;
                if (sender instanceof Player player) {
                    var l = player.getLocation(); origin = new LookupInput.Origin(player.getWorld().getUID(), l.getBlockX(), l.getBlockZ());
                }
                var input = LookupInput.parse(filters, now, origin);
                submit(r -> r.preview(input, requestOwner, barrier, now), this::beginPreview);
            }
        } catch (IllegalArgumentException error) { if (phase.equals("QUERY") && pending == null) phase = "IDLE"; message(sender, error.getMessage()); }
    }
    private void beginPreview(RecoveryPlan result) {
        if (capture.observedSequence() != barrier || capture.pending() != 0) { phase = "DONE"; feedback("Capture changed during selection; request a fresh preview"); return; }
        plan = result;
        cursor = 0; phase = "WATCH";
    }
    public void observed(Position p) { if (revisions.containsKey(p)) revisions.compute(p, (k, v) -> v + 1); }
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = false)
    public void physics(BlockPhysicsEvent event) {
        if (revisions.isEmpty()) return;
        InertCodec.checkThread();
        Position p = position(event.getBlock()), source = position(event.getSourceBlock());
        // The qualified call emits synchronous neighbour checks. Only that exact source and inert shell are correlated.
        if (mutation != null && mutation.equals(source) && InertCodec.shell(mutation).contains(p)
                && InertCodec.supports(event.getBlock().getBlockData().getAsString())) return;
        observed(p); observed(source);
    }
    public void tick() {
        InertCodec.checkThread(); if (closed) return;
        var budget = new SliceBudget(50, 2_000_000, System::nanoTime);
        try {
            if (pending != null && !pending.done() && writer.status().state() != HistoryWriter.State.READY) {
                if (!phase.equals("PAUSED")) {
                    phase = "PAUSED"; pauseReason = "Storage unavailable; outstanding journal work is unacknowledged";
                    feedback(pauseReason + "; preserve files and review after restart");
                }
                return;
            }
            if (pending != null && pending.done()) { var done = completion; done.run(); }
            if (phase.equals("REVIEW")) {
                if (!allowed(requester, reviewPermission) || requester instanceof Player p && !p.isOnline()) { review = null; phase = reviewReturnPhase; return; }
                int delivered = 0;
                while (reviewCursor < review.steps().size() && delivered++ < 8 && budget.take()) {
                    var s = review.steps().get(reviewCursor++); String live;
                    try {
                        String state = codec.currentState(s.step().position());
                        live = state.equals(s.step().expected()) ? "EXPECTED (not applied or save reverted)"
                                : state.equals(s.step().desired()) ? "DESIRED (possibly applied)" : "CONFLICT";
                    } catch (IllegalArgumentException error) { live = "UNAVAILABLE/UNSUPPORTED"; }
                    message(requester, "step=" + s.step().ordinal() + " source=" + s.step().source() + " " + s.status() + " live=" + live
                            + " actual=" + s.before() + " -> " + s.after() + " at=" + s.step().position() + " " + s.reason());
                }
                if (reviewCursor == review.steps().size()) { review = null; phase = reviewReturnPhase; message(requester, "Equality does not prove cause. Unknown effects remain quarantined; no mutation resumed."); }
                return;
            }
            if (requester != null && plan != null && !permitted()) {
                pauseRequested = true; pauseReason = "Requester disconnected or permission revoked";
            }
            if (phase.equals("WATCH")) {
                while (cursor < plan.steps().size() && budget.take()) {
                    var step = plan.steps().get(cursor++);
                    for (var p : InertCodec.shell(step.position())) revisions.put(p, 0L);
                    latest.put(step.position(), step.latest());
                }
                if (cursor == plan.steps().size()) {
                    if (capture.observedSequence() != barrier) { phase = "DONE"; feedback("Capture changed while freezing dependencies; request a new preview"); }
                    else { phase = "PREFLIGHT"; cursor = 0; }
                }
            }
            if (phase.equals("PREFLIGHT")) {
                while (cursor < plan.steps().size() && budget.take()) preflight(plan.steps().get(cursor++));
                if (cursor == plan.steps().size()) {
                    phase = "READY";
                    if (permitted()) {
                        feedback("Preview " + plan.id() + " kind=" + plan.kind() + " eligible=" + (plan.steps().size() - exclusions.size())
                                + " excluded=" + exclusions.size() + " expires=" + java.time.Instant.ofEpochMilli(plan.expires()) + " digest=" + plan.digest());
                        feedback(plan.scope());
                        exclusions.entrySet().stream().limit(8).forEach(e -> feedback("step=" + e.getKey() + " excluded: " + e.getValue()));
                        feedback("Block-only recovery creates no drops/XP; mined items remain and placement costs are not refunded. No economy conservation.");
                    }
                }
            }
            if (phase.equals("VERIFY") && budget.take()) verifyApplied();
            if (phase.equals("EXECUTE") && pending == null && budget.take()) {
                if (cursor == plan.steps().size()) {
                    boundaryProbe.accept("before-summary");
                    submit(r -> { r.state(plan.id(), skipped == 0 ? "COMPLETED" : "COMPLETED_WITH_SKIPS", ""); return true; }, unused -> {
                        phase = "DONE"; revisions.clear(); feedback(status());
                        boundaryProbe.accept("after-summary");
                    }); return;
                }
                if (pauseRequested || !healthy()) { pause(pauseReason.isEmpty() ? "Storage/coverage health changed" : pauseReason); return; }
                if (capture.pending() != 0 || writer.status().pendingRecords() != 0) return;
                var step = plan.steps().get(cursor);
                String reason = exclusions.getOrDefault(cursor, freshConflict(step));
                if (!reason.isEmpty()) { skip(reason); return; }
                long expectedLatest = latest.get(step.position());
                boundaryProbe.accept("before-intent");
                submit(r -> r.intent(plan.id(), step.ordinal(), expectedLatest), conflict -> {
                    if (!conflict.isEmpty()) { skipped++; cursor++; feedback("Skipped step " + step.ordinal() + ": " + conflict); }
                    else { phase = "INTENT_ACK"; boundaryProbe.accept("after-intent"); }
                });
            }
            if (phase.equals("INTENT_ACK") && pending == null && budget.take()) apply();
        } catch (RuntimeException failure) {
            if (reservation != 0) { capture.finishSynthetic(reservation, null); reservation = 0; }
            phase = plan == null || List.of("QUERY", "WATCH", "PREFLIGHT", "READY").contains(phase) ? "DONE" : "PAUSED";
            pauseReason = "Recovery failed: " + (failure.getCause() == null ? failure.getMessage() : failure.getCause().getMessage());
            if (requester != null && permitted()) feedback(pauseReason + "; preserve journal and review before continuing");
        } finally {
            long elapsed = budget.elapsed(); maxSliceNanos = Math.max(maxSliceNanos, elapsed); if (elapsed > 2_000_000) overruns++;
        }
    }
    private void preflight(RecoveryPlan.Step step) {
        String reason = step.reason();
        try {
            if (!reason.isEmpty()) throw new IllegalArgumentException(reason);
            var live = codec.snapshot(step.position());
            var positions = InertCodec.shell(step.position());
            for (int i = 0; i < positions.size(); i++) simulated.putIfAbsent(positions.get(i), live.states().get(i));
            var expected = new InertCodec.Snapshot(step.position(), positions.stream().map(simulated::get).toList());
            if (!expected.center().equals(step.expected())) throw new IllegalArgumentException("Current state/selected chain differs");
            if (changed(step.position())) throw new IllegalArgumentException("Observed dependency changed during preview");
            snapshots.put(step.ordinal(), expected); simulated.put(step.position(), step.desired());
        } catch (IllegalArgumentException error) { exclusions.put(step.ordinal(), error.getMessage()); }
    }
    private String freshConflict(RecoveryPlan.Step step) {
        if (changed(step.position())) return "Revision conflict (including same-state intervening edits)";
        try {
            if (!codec.snapshot(step.position()).equals(snapshots.get(step.ordinal()))) return "Fresh state/dependency conflict";
        } catch (IllegalArgumentException error) { return error.getMessage(); }
        return "";
    }
    private boolean changed(Position p) { return InertCodec.shell(p).stream().anyMatch(s -> revisions.getOrDefault(s, 0L) != 0); }
    private void apply() {
        var step = plan.steps().get(cursor);
        if (pauseRequested || !permitted() || !healthy()) { skipIntentThenPause("Permission/health/pause changed after intent"); return; }
        if (capture.pending() != 0 || writer.status().pendingRecords() != 0) return;
        String reason = freshConflict(step);
        if (!reason.isEmpty()) { skip(reason); phase = "EXECUTE"; return; }
        reservation = capture.reserveSynthetic(); mutation = step.position(); post = null;
        long start = System.nanoTime();
        try { post = codec.apply(snapshots.get(cursor), step.desired()); phase = "VERIFY"; boundaryProbe.accept("after-mutation"); }
        catch (IllegalArgumentException failure) {
            // The codec can throw after a mutation too. Never infer that an exception proves no effect.
            capture.finishSynthetic(reservation, null); reservation = 0;
            unknown("Application exception: " + failure.getMessage());
        } catch (RuntimeException failure) {
            capture.finishSynthetic(reservation, null); reservation = 0; unknown("Uncertain application: " + failure.getMessage());
        } finally { mutation = null; maxCallNanos = Math.max(maxCallNanos, System.nanoTime() - start); }
    }
    private void verifyApplied() {
        var step = plan.steps().get(cursor);
        try {
            if (!codec.snapshot(step.position()).equals(post) || changed(step.position())) throw new IllegalStateException("Follow-up state/revision conflict");
        } catch (RuntimeException error) {
            capture.finishSynthetic(reservation, null); reservation = 0; unknown(error.getMessage()); return;
        }
        var world = Bukkit.getWorld(step.position().world());
        if (world == null) throw new IllegalStateException("World unavailable");
        var change = new BlockChange(step.position().world(), world.getName(), step.position().x(), step.position().y(), step.position().z(),
                plan.id(), "Chronicle", step.desired().equals(InertCodec.STONE) ? "PLACE" : "BREAK", step.expected(), step.desired(), "RECOVERY", 5023);
        capture.finishSynthetic(reservation, new CaptureGroup(reservation, System.currentTimeMillis(), Bukkit.getCurrentTick(), List.of(change),
                new RecoveryOutcome(plan.id(), cursor, step.expected(), step.desired())));
        reservation = 0; phase = "OUTCOME";
        submit(r -> r.operation(plan.id()), op -> {
            var s = op.steps().get(cursor);
            if (!s.status().equals("APPLIED") || s.audit() == null) throw new IllegalStateException("Outcome acknowledgement missing");
            latest.put(step.position(), s.audit()); applied++; cursor++; phase = "EXECUTE";
            boundaryProbe.accept("after-outcome");
        });
    }
    private void unknown(String reason) {
        String observedAfter = null;
        try { observedAfter = codec.snapshot(plan.steps().get(cursor).position()).center(); } catch (RuntimeException unavailable) { /* Null explicitly means unknown. */ }
        String actualAfter = observedAfter, actualBefore = snapshots.get(cursor) == null ? null : snapshots.get(cursor).center();
        submit(r -> { r.uncertain(plan.id(), cursor, actualBefore, actualAfter, reason); return true; }, unused -> {
            phase = "PAUSED"; feedback("Operation " + plan.id() + " needs reconciliation: " + reason);
        });
        phase = "OUTCOME";
    }
    private void skip(String reason) {
        int index = cursor;
        submit(r -> { r.skip(plan.id(), index, "SKIPPED", reason); return true; }, unused -> {
            skipped++; cursor++; feedback("Skipped step " + index + ": " + reason);
        });
    }
    private void skipIntentThenPause(String reason) {
        int index = cursor;
        boolean finished = index + 1 == plan.steps().size();
        submit(r -> { r.skip(plan.id(), index, "SKIPPED", reason); r.state(plan.id(), finished ? "COMPLETED_WITH_SKIPS" : "PAUSED", reason); return true; }, unused -> {
            skipped++; cursor++; phase = finished ? "DONE" : "PAUSED"; feedback(status());
        });
    }
    private void pause(String reason) {
        submit(r -> { r.state(plan.id(), "PAUSED", reason); return true; }, unused -> { phase = "PAUSED"; feedback(status()); });
    }
    private <T> void submit(HistoryWriter.RecoveryWork<T> work, Consumer<T> success) {
        var task = writer.control(work); pending = task;
        completion = () -> {
            T result;
            try { result = task.result(); }
            finally { writer.release(task); pending = null; completion = null; }
            success.accept(result);
        };
    }
    private <T> void submitRead(HistoryWriter.RecoveryWork<T> work, Consumer<T> success, CommandSender sender, String permission) {
        submit(work, success);
        Runnable delivery = completion;
        completion = () -> {
            try { delivery.run(); }
            catch (RuntimeException error) {
                if (allowed(sender, permission) && (!(sender instanceof Player p) || p.isOnline()))
                    message(sender, "Recovery query failed: " + (error.getCause() == null ? error.getMessage() : error.getCause().getMessage()));
            }
        };
    }
    private boolean healthy() {
        var s = writer.status();
        return s.state() == HistoryWriter.State.READY && s.rejected() == 0
                && s.incidents().stream().allMatch(i -> i.reason() == io.github.d3vx3n0.chronicle.storage.Coverage.STARTUP
                        || i.reason() == io.github.d3vx3n0.chronicle.storage.Coverage.PREVIOUS_UNCLEAN);
    }
    private boolean permitted() {
        return requester != null && (!(requester instanceof Player player) || player.isOnline())
                && allowed(requester, plan == null ? requestPermission : plan.kind().equals("RESTORE") ? "chronicle.restore" : "chronicle.rollback");
    }
    private static boolean matchesKind(RecoveryPlan p, String route) { return route.equals("RESTORE") == p.kind().equals("RESTORE"); }
    private static boolean allowed(CommandSender sender, String permission) { return InvestigationController.allowed(sender, permission); }
    private static String owner(CommandSender sender) {
        if (sender instanceof ConsoleCommandSender) return "CONSOLE";
        if (sender instanceof Player player) return player.getUniqueId().toString();
        throw new IllegalArgumentException("Recovery requires local console or player");
    }
    private void reset() {
        plan = null; revisions.clear(); simulated.clear(); latest.clear(); snapshots.clear(); exclusions.clear();
        cursor = 0; applied = 0; skipped = 0; pauseRequested = false; pauseReason = "";
        previewDeadlineNanos = System.nanoTime() + 60_000_000_000L;
    }
    public String status() {
        return "Recovery: " + phase + "; operation=" + (plan == null ? "none" : plan.id()) + "; applied=" + applied + "; skipped=" + skipped
                + "; maxCallNanos=" + maxCallNanos + "; maxSliceNanos=" + maxSliceNanos + "; overruns=" + overruns + "; " + pauseReason;
    }
    public void operationStatus(CommandSender sender, String text) {
        InertCodec.checkThread();
        if (!allowed(sender, "chronicle.status")) { message(sender, "You need chronicle.status"); return; }
        try {
            UUID id = UUID.fromString(text);
            if (plan != null && plan.id().equals(id) && !List.of("IDLE", "DONE").contains(phase)) { message(sender, status()); return; }
            if (pending != null) throw new IllegalArgumentException("Recovery query busy; retry status");
            submitRead(r -> r.operation(id), op -> {
                if (!allowed(sender, "chronicle.status") || sender instanceof Player p && !p.isOnline()) return;
                var counts = op.steps().stream().collect(java.util.stream.Collectors.groupingBy(RecoveryRepository.StepState::status, java.util.TreeMap::new, java.util.stream.Collectors.counting()));
                message(sender, "Operation " + id + " " + op.plan().kind() + " " + op.state() + " outcomes=" + counts + "; " + op.reason());
                message(sender, "Retained effects require fresh live validation after restart. Use rollback/restore review with the corresponding permission for per-step evidence.");
            }, sender, "chronicle.status");
        } catch (IllegalArgumentException error) { message(sender, error.getMessage()); }
    }
    private static Position position(Block b) { return new Position(b.getWorld().getUID(), b.getX(), b.getY(), b.getZ()); }
    private static void message(CommandSender sender, String text) { if (sender != null) sender.sendMessage(net.kyori.adventure.text.Component.text(text)); }
    private void feedback(String text) { if (permitted()) message(requester, text); }
    @Override public void close() {
        InertCodec.checkThread(); closed = true;
        // No further mutation. Durable INTENT remains explicitly unresolved on restart; capture stop abandons its slot.
        revisions.clear();
    }
}
