package io.github.d3vx3n0.chronicle.recovery;

import io.github.d3vx3n0.chronicle.capture.BlockReconciler.Position;
import io.github.d3vx3n0.chronicle.query.HistoryRow;
import io.github.d3vx3n0.chronicle.query.LookupFilter;
import io.github.d3vx3n0.chronicle.query.LookupInput;
import io.github.d3vx3n0.chronicle.storage.sqlite.SqliteReader;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/** Concrete journal repository, used only by the existing writer. All selections and transactions are bounded. */
public final class RecoveryRepository {
    private final Connection db;
    private final long session;
    private final java.util.function.Consumer<String> transactionProbe;
    public record StepState(RecoveryPlan.Step step, String status, String reason, String before, String after,
            Long audit, long intentSession, long verifiedSession, String restoredBy) {}
    public record Operation(RecoveryPlan plan, String state, String reason, List<StepState> steps) {
        public Operation { steps = List.copyOf(steps); }
    }
    public RecoveryRepository(Connection db, long session) { this(db, session, boundary -> {}); }
    public RecoveryRepository(Connection db, long session, java.util.function.Consumer<String> transactionProbe) {
        this.db = db; this.session = session; this.transactionProbe = transactionProbe;
    }
    @FunctionalInterface public interface Work<T> { T run() throws SQLException; }
    public <T> T transaction(Work<T> work) throws SQLException {
        db.setAutoCommit(false);
        try {
            T result = work.run(); transactionProbe.accept("recovery-before-commit"); db.commit();
            transactionProbe.accept("recovery-after-commit"); db.setAutoCommit(true); return result;
        }
        catch (SQLException | RuntimeException | Error failure) {
            try { db.rollback(); db.setAutoCommit(true); } catch (SQLException | RuntimeException | Error rollback) { failure.addSuppressed(rollback); }
            throw failure;
        }
    }
    public void startup() throws SQLException {
        transaction(() -> {
            update("UPDATE recovery_steps SET status='UNKNOWN',reason='Restart after durable intent; effect uncertain' WHERE status='INTENT'");
            update("UPDATE operations SET state='NEEDS_RECONCILIATION',reason='Restart; explicit review required' WHERE state IN ('PREPARED','APPLYING','PAUSED')");
            update("UPDATE operations SET state='EXPIRED',reason='Preview does not survive restart' WHERE state='PREVIEW_READY'");
            return null;
        });
    }
    public RecoveryPlan preview(LookupInput input, String owner, long barrier, long now) throws SQLException {
        if (input.world() == null || input.area() == null || input.area().radius() > 100)
            throw new IllegalArgumentException("Recovery requires one world and radius <=100");
        return transaction(() -> {
            var actors = new ArrayList<UUID>();
            for (String actor : input.players()) actors.add(resolve(actor, true));
            var filter = new LookupFilter(actors, resolve(input.world(), false), input.from(), input.to(), input.actions(), input.area());
            long boundary = number("SELECT coalesce(max(id),0) FROM changes");
            var evidence = new ArrayList<RecoveryPlanner.Evidence>();
            long before = Long.MAX_VALUE;
            boolean more;
            do {
                var query = SqliteReader.select(filter, boundary, before, 50);
                var rows = new ArrayList<HistoryRow>();
                try (var statement = db.prepareStatement(query.text())) {
                    bind(statement, query.parameters().toArray());
                    try (var r = statement.executeQuery()) { while (r.next()) rows.add(row(r)); }
                }
                more = rows.size() > 50;
                if (more) rows.removeLast();
                for (var r : rows) {
                    if (evidence.size() == RecoveryPlan.MAX_STEPS) throw new IllegalArgumentException("Selection exceeds 1000 changes; narrow scope");
                    var p = new Position(UUID.fromString(r.world()), r.x(), r.y(), r.z());
                    long previous = number("SELECT coalesce(max(id),0) FROM changes WHERE world_uuid=? AND x=? AND y=? AND z=? AND id<?",
                            r.world(), r.x(), r.y(), r.z(), r.id());
                    int members = (int) number("SELECT member_count FROM capture_groups WHERE session_id=? AND sequence=?", r.session(), r.sequence());
                    boolean effective = number("SELECT count(*) FROM recovery_effects WHERE source=?", r.id()) == 0;
                    evidence.add(new RecoveryPlanner.Evidence(r, members, previous, latest(p), effective, gap(r.id())));
                    before = r.id();
                }
            } while (more);
            if (evidence.isEmpty()) throw new IllegalArgumentException("No matching committed changes");
            var plan = new RecoveryPlan(UUID.randomUUID(), owner, "ROLLBACK", null, session, barrier, boundary, now,
                    now + RecoveryPlan.TTL_MILLIS, filter + (actors.isEmpty() ? " ALL ACTORS" : ""), RecoveryPlanner.rollback(evidence));
            save(plan); return plan;
        });
    }
    public RecoveryPlan restore(UUID parent, String owner, long barrier, long now) throws SQLException {
        return transaction(() -> {
            var original = operation(parent);
            if (!original.plan().kind().equals("ROLLBACK")) throw new IllegalArgumentException("Restore requires a rollback ID");
            if (List.of("PREPARED", "APPLYING", "PAUSED", "NEEDS_RECONCILIATION").contains(original.state()))
                throw new IllegalArgumentException("Review/finish the source operation before restoration");
            var steps = new ArrayList<RecoveryPlan.Step>();
            var expectedLatest = new java.util.HashMap<Position, Long>();
            var blocked = new java.util.HashSet<Position>();
            for (var s : original.steps().reversed()) {
                if (!s.status().equals("APPLIED") || s.restoredBy() != null) continue;
                var p = s.step().position();
                long latest = latest(p);
                String reason = "";
                if (s.audit() == null || !InertStates.supports(s.before()) || !InertStates.supports(s.after())) reason = "Unverified source snapshots";
                else if (blocked.contains(p) || expectedLatest.getOrDefault(p, latest).longValue() != s.audit()) reason = "Later change after rollback";
                else if (gap(s.audit())) reason = "Known coverage gap since rollback";
                else if (number("SELECT count(*) FROM recovery_effects WHERE source=? AND rollback_operation=? AND rollback_step=? AND state='UNDONE' AND restore_operation IS NULL",
                        s.step().source(), parent.toString(), s.step().ordinal()) != 1) reason = "Source is reserved or no longer undone";
                if (!reason.isEmpty()) blocked.add(p);
                expectedLatest.put(p, previousAudit(parent, s.step().ordinal(), p));
                steps.add(new RecoveryPlan.Step(steps.size(), s.step().source(), s.step().ordinal(), p, s.after(), s.before(), latest, reason));
            }
            if (steps.isEmpty()) throw new IllegalArgumentException("No verified, applied, unrestored steps");
            var plan = new RecoveryPlan(UUID.randomUUID(), owner, "RESTORE", parent, session, barrier,
                    number("SELECT coalesce(max(id),0) FROM changes"), now, now + RecoveryPlan.TTL_MILLIS,
                    "restore rollback=" + parent, steps);
            save(plan); return plan;
        });
    }
    private long previousAudit(UUID parent, int ordinal, Position p) throws SQLException {
        return number("SELECT coalesce(max(audit_id),0) FROM recovery_steps WHERE operation=? AND ordinal<? AND world=? AND x=? AND y=? AND z=? AND status='APPLIED'",
                parent.toString(), ordinal, p.world().toString(), p.x(), p.y(), p.z());
    }
    /** Explicit operator review produces a new frozen plan, never replays a prior intent. */
    public RecoveryPlan reviewed(UUID parent, String owner, long barrier, long now, boolean compensate) throws SQLException {
        return transaction(() -> {
            var old = operation(parent);
            if (!List.of("ROLLBACK", "RESTORE").contains(old.plan().kind()) || compensate && !old.plan().kind().equals("ROLLBACK")
                    || !List.of("PAUSED", "NEEDS_RECONCILIATION").contains(old.state()))
                throw new IllegalArgumentException("Continuation requires a paused/interrupted rollback or restore; compensation requires rollback");
            var steps = new ArrayList<RecoveryPlan.Step>();
            for (var s : compensate ? old.steps().reversed() : old.steps()) {
                if (!s.status().equals(compensate ? "UNKNOWN" : "PLANNED") || s.restoredBy() != null) continue;
                var p = s.step().position(); long latest = latest(p);
                long own = number("SELECT coalesce(max(audit_id),0) FROM recovery_steps WHERE operation=? AND world=? AND x=? AND y=? AND z=?",
                        parent.toString(), p.world().toString(), p.x(), p.y(), p.z());
                long expectedLatest = Math.max(s.step().latest(), own);
                String reason = "";
                if (latest != expectedLatest) reason = "Later history prevents reviewed continuation/compensation";
                else if (!compensate && number("SELECT count(*) FROM recovery_steps WHERE operation=? AND world=? AND x=? AND y=? AND z=? AND status IN ('UNKNOWN','FAILED')",
                        parent.toString(), p.world().toString(), p.x(), p.y(), p.z()) > 0) reason = "Uncertain dependency remains quarantined";
                // Unclean session coverage remains a blocker. Operator review does not erase missing history.
                else if (gap(s.step().source())) reason = "Coverage gap prevents safe reviewed work";
                steps.add(new RecoveryPlan.Step(steps.size(), s.step().source(), old.plan().kind().equals("RESTORE") ? s.step().parentStep() : s.step().ordinal(), p,
                        compensate ? s.step().desired() : s.step().expected(), compensate ? s.step().expected() : s.step().desired(), latest, reason));
            }
            if (steps.isEmpty()) throw new IllegalArgumentException("No " + (compensate ? "uncertain effects to compensate" : "unattempted steps to continue"));
            var plan = new RecoveryPlan(UUID.randomUUID(), owner, compensate ? "COMPENSATE" : old.plan().kind(), old.plan().kind().equals("RESTORE") ? old.plan().parent() : parent, session, barrier,
                    number("SELECT coalesce(max(id),0) FROM changes"), now, now + RecoveryPlan.TTL_MILLIS,
                    (compensate ? "Operator-reviewed compensation of uncertain effects; equality does not prove cause: " : "Continuation of unattempted steps: ") + parent, steps, compensate ? null : parent);
            save(plan); return plan;
        });
    }
    private void save(RecoveryPlan p) throws SQLException {
        update("INSERT INTO operations(id,owner,kind,parent,session_id,barrier,boundary,created,expires,scope,digest,state,continuation_of) VALUES(?,?,?,?,?,?,?,?,?,?,?,'PREVIEW_READY',?)",
                p.id().toString(), p.owner(), p.kind(), p.parent() == null ? null : p.parent().toString(), p.session(), p.barrier(), p.boundary(),
                p.created(), p.expires(), p.scope(), p.digest(), p.continuation() == null ? null : p.continuation().toString());
        try (var statement = db.prepareStatement("INSERT INTO recovery_steps(operation,ordinal,source,parent_step,world,x,y,z,expected,desired,latest,exclusion) VALUES(?,?,?,?,?,?,?,?,?,?,?,?)")) {
            for (var s : p.steps()) {
                bind(statement, p.id().toString(), s.ordinal(), s.source(), s.parentStep(), s.position().world().toString(), s.position().x(), s.position().y(), s.position().z(),
                        s.expected(), s.desired(), s.latest(), s.reason()); statement.addBatch();
            }
            statement.executeBatch();
        }
    }
    public Operation operation(UUID id) throws SQLException {
        var steps = new ArrayList<StepState>();
        try (var statement = db.prepareStatement("""
                SELECT ordinal,source,parent_step,substr(world,1,37) world,x,y,z,substr(expected,1,257) expected,
                substr(desired,1,257) desired,latest,substr(exclusion,1,257) exclusion,substr(status,1,32) status,
                substr(reason,1,257) reason,substr(actual_before,1,257) actual_before,substr(actual_after,1,257) actual_after,
                audit_id,intent_session,verified_session,substr(restored_by,1,37) restored_by
                FROM recovery_steps WHERE operation=? ORDER BY ordinal LIMIT 1001
                """)) {
            statement.setString(1, id.toString());
            try (var r = statement.executeQuery()) {
                while (r.next()) {
                    if (steps.size() == RecoveryPlan.MAX_STEPS) throw new IllegalArgumentException("Oversized stored plan");
                    var s = new RecoveryPlan.Step(r.getInt("ordinal"), r.getLong("source"), (Integer) r.getObject("parent_step"),
                            new Position(UUID.fromString(r.getString("world")), r.getInt("x"), r.getInt("y"), r.getInt("z")),
                            r.getString("expected"), r.getString("desired"), r.getLong("latest"), r.getString("exclusion"));
                    long audit = r.getLong("audit_id"); Long optionalAudit = r.wasNull() ? null : audit;
                    steps.add(new StepState(s, r.getString("status"), r.getString("reason"), r.getString("actual_before"), r.getString("actual_after"),
                            optionalAudit, r.getLong("intent_session"), r.getLong("verified_session"), r.getString("restored_by")));
                }
            }
        }
        try (var statement = db.prepareStatement("""
                SELECT substr(owner,1,65) owner,substr(kind,1,32) kind,substr(parent,1,37) parent,substr(continuation_of,1,37) continuation_of,session_id,barrier,boundary,created,expires,
                substr(scope,1,2049) scope,substr(digest,1,65) digest,substr(state,1,32) state,substr(reason,1,257) reason FROM operations WHERE id=?
                """)) {
            statement.setString(1, id.toString());
            try (var r = statement.executeQuery()) {
                if (!r.next()) throw new IllegalArgumentException("Unknown operation ID");
                String parent = r.getString("parent"), continuation = r.getString("continuation_of");
                var plan = new RecoveryPlan(id, r.getString("owner"), r.getString("kind"), parent == null ? null : UUID.fromString(parent),
                        r.getLong("session_id"), r.getLong("barrier"), r.getLong("boundary"), r.getLong("created"), r.getLong("expires"),
                        r.getString("scope"), steps.stream().map(StepState::step).toList(), continuation == null ? null : UUID.fromString(continuation));
                if (!plan.digest().equals(r.getString("digest"))) throw new IllegalArgumentException("Stored plan digest mismatch");
                return new Operation(plan, r.getString("state"), r.getString("reason"), steps);
            }
        }
    }
    public Operation confirm(UUID id, String owner, String kind, long now) throws SQLException {
        return transaction(() -> {
            var op = operation(id);
            if (!op.plan().owner().equals(owner) || !op.plan().kind().equals(kind)) throw new IllegalArgumentException("Preview belongs to another requester/kind");
            if (!op.state().equals("PREVIEW_READY")) return op;
            if (now >= op.plan().expires() || op.plan().session() != session) {
                update("UPDATE operations SET state='EXPIRED' WHERE id=?", id.toString()); return operation(id);
            }
            UUID replaced = op.plan().continuation() != null ? op.plan().continuation() : op.plan().kind().equals("COMPENSATE") ? op.plan().parent() : null;
            if (number("SELECT count(*) FROM operations WHERE state IN ('PREPARED','APPLYING','PAUSED') AND id<>?",
                    replaced == null ? "" : replaced.toString()) != 0)
                throw new IllegalArgumentException("Another operation is active");
            if (op.plan().continuation() != null) {
                for (var step : op.plan().steps()) {
                    if (update("UPDATE recovery_steps SET status='SKIPPED',reason=? WHERE operation=? AND source=? AND status='PLANNED'",
                            "Transferred to reviewed plan " + id, op.plan().continuation().toString(), step.source()) != 1)
                        throw new IllegalArgumentException("Continuation source changed");
                }
                update("UPDATE operations SET state=CASE WHEN EXISTS(SELECT 1 FROM recovery_steps WHERE operation=? AND status='UNKNOWN') THEN 'NEEDS_RECONCILIATION' ELSE 'COMPLETED_WITH_SKIPS' END WHERE id=?",
                        op.plan().continuation().toString(), op.plan().continuation().toString());
            }
            if (op.plan().kind().equals("COMPENSATE")) update("UPDATE operations SET state='NEEDS_RECONCILIATION',reason='Operator-reviewed compensation; original effects remain unverified' WHERE id=?", op.plan().parent().toString());
            update("UPDATE operations SET state='PREPARED' WHERE id=?", id.toString()); return operation(id);
        });
    }
    /** Fresh history check and intent claim are one transaction. Caller rechecks live revisions after acknowledgement. */
    public String intent(UUID id, int ordinal, long expectedLatest) throws SQLException {
        return transaction(() -> {
            var op = operation(id); var s = op.steps().get(ordinal);
            if (!List.of("PREPARED", "APPLYING").contains(op.state()) || !s.status().equals("PLANNED"))
                throw new IllegalArgumentException("Step is not executable");
            String reason = s.step().reason();
            if (reason.isEmpty() && latest(s.step().position()) != expectedLatest) reason = "Fresh history conflict";
            if (reason.isEmpty() && gap(s.step().source())) reason = "Known coverage gap";
            if (!reason.isEmpty()) { skipInternal(id, ordinal, "SKIPPED", reason); return reason; }
            if (op.plan().kind().equals("ROLLBACK")) {
                if (number("SELECT count(*) FROM recovery_effects WHERE source=?", s.step().source()) > 0) reason = "Effective change already reserved/undone";
                else update("INSERT INTO recovery_effects(source,rollback_operation,rollback_step,state) VALUES(?,?,?,'RESERVED')", s.step().source(), id.toString(), ordinal);
            } else {
                int claimed = update("UPDATE recovery_effects SET restore_operation=?,restore_step=? WHERE source=? AND rollback_operation=? AND rollback_step=? AND state=? AND restore_operation IS NULL",
                        id.toString(), ordinal, s.step().source(), op.plan().parent().toString(), s.step().parentStep(), op.plan().kind().equals("COMPENSATE") ? "RESERVED" : "UNDONE");
                if (claimed != 1) reason = "Source already restored/reserved";
            }
            if (!reason.isEmpty()) { skipInternal(id, ordinal, "SKIPPED", reason); return reason; }
            update("UPDATE recovery_steps SET status='INTENT',intent_session=? WHERE operation=? AND ordinal=?", session, id.toString(), ordinal);
            update("UPDATE operations SET state='APPLYING' WHERE id=?", id.toString()); return "";
        });
    }
    /** Called inside SqliteStore's history transaction. Repeated identical history/outcomes are idempotent. */
    public void applied(RecoveryOutcome outcome, long historySession, long sequence) throws SQLException {
        var op = operation(outcome.operation()); var s = op.steps().get(outcome.step());
        long audit = number("SELECT id FROM changes WHERE session_id=? AND capture_sequence=? AND member_index=0", historySession, sequence);
        var p = s.step().position();
        if (number("SELECT count(*) FROM changes WHERE id=? AND world_uuid=? AND x=? AND y=? AND z=? AND actor_uuid=? AND evidence='RECOVERY' AND before_data=? AND after_data=?",
                audit, p.world().toString(), p.x(), p.y(), p.z(), outcome.operation().toString(), outcome.before(), outcome.after()) != 1)
            throw new SQLException("Audit target/snapshot differs from durable intent");
        if (s.status().equals("APPLIED")) {
            if (s.audit() == null || s.audit() != audit || !outcome.before().equals(s.before()) || !outcome.after().equals(s.after()))
                throw new SQLException("Outcome retry mismatch");
            return;
        }
        if (!s.status().equals("INTENT") || s.intentSession() != session || !s.step().expected().equals(outcome.before()) || !s.step().desired().equals(outcome.after()))
            throw new SQLException("Applied outcome lacks matching durable intent");
        update("UPDATE recovery_steps SET status='APPLIED',actual_before=?,actual_after=?,audit_id=?,verified_session=? WHERE operation=? AND ordinal=?",
                outcome.before(), outcome.after(), audit, session, outcome.operation().toString(), outcome.step());
        if (op.plan().kind().equals("ROLLBACK")) {
            if (update("UPDATE recovery_effects SET state='UNDONE' WHERE source=? AND rollback_operation=? AND rollback_step=? AND state='RESERVED'",
                    s.step().source(), outcome.operation().toString(), outcome.step()) != 1) throw new SQLException("Missing rollback claim");
        } else {
            if (update("DELETE FROM recovery_effects WHERE source=? AND restore_operation=? AND restore_step=?", s.step().source(), outcome.operation().toString(), outcome.step()) != 1)
                throw new SQLException("Missing restore claim");
            if (update("UPDATE recovery_steps SET restored_by=? WHERE operation=? AND ordinal=? AND status=? AND restored_by IS NULL",
                    outcome.operation().toString(), op.plan().parent().toString(), s.step().parentStep(), op.plan().kind().equals("COMPENSATE") ? "UNKNOWN" : "APPLIED") != 1) throw new SQLException("Source restored/compensated twice");
        }
    }
    public void skip(UUID id, int ordinal, String status, String reason) throws SQLException {
        if (!List.of("SKIPPED", "FAILED", "UNKNOWN").contains(status)) throw new IllegalArgumentException("Invalid outcome");
        transaction(() -> { skipInternal(id, ordinal, status, reason); return null; });
    }
    public void uncertain(UUID id, int ordinal, String before, String after, String reason) throws SQLException {
        if (before != null && before.length() > 256 || after != null && after.length() > 256) throw new IllegalArgumentException("Oversized uncertain evidence");
        transaction(() -> {
            skipInternal(id, ordinal, "UNKNOWN", reason);
            update("UPDATE recovery_steps SET actual_before=?,actual_after=? WHERE operation=? AND ordinal=?", before, after, id.toString(), ordinal);
            update("UPDATE operations SET state='NEEDS_RECONCILIATION',reason=? WHERE id=?", reason.length() > 256 ? reason.substring(0, 256) : reason, id.toString());
            return null;
        });
    }
    private void skipInternal(UUID id, int ordinal, String status, String reason) throws SQLException {
        if (reason.length() > 256) reason = reason.substring(0, 256);
        if (update("UPDATE recovery_steps SET status=?,reason=? WHERE operation=? AND ordinal=? AND status IN ('PLANNED','INTENT')", status, reason, id.toString(), ordinal) != 1)
            throw new IllegalArgumentException("Step outcome already recorded");
        if (!status.equals("UNKNOWN")) {
            update("DELETE FROM recovery_effects WHERE rollback_operation=? AND rollback_step=? AND state='RESERVED'", id.toString(), ordinal);
            update("UPDATE recovery_effects SET restore_operation=NULL,restore_step=NULL WHERE restore_operation=? AND restore_step=?", id.toString(), ordinal);
        }
    }
    public void state(UUID id, String state, String reason) throws SQLException {
        if (!List.of("PAUSED", "COMPLETED", "COMPLETED_WITH_SKIPS", "NEEDS_RECONCILIATION").contains(state)) throw new IllegalArgumentException("Invalid operation state");
        transaction(() -> {
            if (state.startsWith("COMPLETED") && number("SELECT count(*) FROM recovery_steps WHERE operation=? AND status IN ('PLANNED','INTENT','UNKNOWN')", id.toString()) > 0)
                throw new IllegalArgumentException("Operation retains unfinished/unknown steps");
            update("UPDATE operations SET state=?,reason=? WHERE id=?", state, reason, id.toString()); return null;
        });
    }
    public long latest(Position p) throws SQLException {
        return number("SELECT coalesce(max(id),0) FROM changes WHERE world_uuid=? AND x=? AND y=? AND z=?", p.world().toString(), p.x(), p.y(), p.z());
    }
    private boolean gap(long source) throws SQLException {
        // STARTUP alone is a diagnostic interval; actual observations before READY record STORAGE_FAILURE.
        return number("""
                SELECT count(*) FROM coverage_incidents i JOIN changes c ON c.id=? WHERE i.reason<>'STARTUP'
                AND (i.session_id>c.session_id OR (i.session_id=c.session_id AND i.last_sequence>=c.capture_sequence))
                """, source) > 0;
    }
    private UUID resolve(String text, boolean actor) throws SQLException {
        boolean uuid = HistoryRow.validIdentity(text);
        String sql = uuid ? "SELECT uuid FROM " + (actor ? "actors" : "worlds") + " WHERE uuid=? LIMIT 2"
                : actor ? "SELECT actor_uuid FROM actor_names WHERE name=? COLLATE NOCASE LIMIT 2" : "SELECT uuid FROM worlds WHERE last_name=? COLLATE NOCASE LIMIT 2";
        try (var statement = db.prepareStatement(sql)) {
            statement.setString(1, uuid ? UUID.fromString(text).toString() : text);
            try (var r = statement.executeQuery()) {
                if (!r.next()) throw new IllegalArgumentException("Unknown stored identity: " + text);
                UUID result = UUID.fromString(r.getString(1));
                if (r.next()) throw new IllegalArgumentException("Ambiguous identity; use UUID"); return result;
            }
        }
    }
    private long number(String sql, Object... values) throws SQLException {
        try (var statement = db.prepareStatement(sql)) {
            bind(statement, values); try (var r = statement.executeQuery()) {
                if (!r.next()) throw new SQLException("Missing recovery evidence"); return r.getLong(1);
            }
        }
    }
    private int update(String sql, Object... values) throws SQLException {
        try (var statement = db.prepareStatement(sql)) { bind(statement, values); return statement.executeUpdate(); }
    }
    private static void bind(java.sql.PreparedStatement statement, Object... values) throws SQLException {
        for (int i = 0; i < values.length; i++) statement.setObject(i + 1, values[i]);
    }
    private static HistoryRow row(ResultSet r) throws SQLException {
        return new HistoryRow(r.getLong(1), r.getLong(2), r.getLong(3), r.getInt(4), r.getLong(5), r.getString(6), r.getInt(7), r.getInt(8), r.getInt(9),
                r.getString(10), r.getString(11), r.getString(12), r.getString(13), r.getString(14), r.getInt(15), r.getInt(16), r.getString(17), r.getString(18), r.getString(19), r.getString(20));
    }
}
