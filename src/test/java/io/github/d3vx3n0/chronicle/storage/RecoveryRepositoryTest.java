package io.github.d3vx3n0.chronicle.storage;

import static org.junit.jupiter.api.Assertions.*;
import io.github.d3vx3n0.chronicle.model.BlockChange;
import io.github.d3vx3n0.chronicle.model.CaptureGroup;
import io.github.d3vx3n0.chronicle.query.LookupFilter;
import io.github.d3vx3n0.chronicle.query.LookupInput;
import io.github.d3vx3n0.chronicle.recovery.RecoveryOutcome;
import io.github.d3vx3n0.chronicle.recovery.RecoveryPlan;
import io.github.d3vx3n0.chronicle.storage.sqlite.SqliteStore;
import java.nio.file.Path;
import java.sql.SQLException;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

@Tag("database")
class RecoveryRepositoryTest {
    @TempDir Path directory;
    static final UUID WORLD = new UUID(0, 1), ACTOR = new UUID(0, 2);
    static final String AIR = "minecraft:air", STONE = "minecraft:stone";
    static CaptureGroup player(long seq, String before, String after) {
        return new CaptureGroup(seq, 1000 + seq, 1, List.of(new BlockChange(WORLD, "fixture", -17, 80, -1, ACTOR, "Test",
                after.equals(STONE) ? "PLACE" : "BREAK", before, after, "LATER_SAMPLE", 5023)));
    }
    static CaptureGroup audit(RecoveryPlan plan, int step, long sequence) {
        var s = plan.steps().get(step);
        return new CaptureGroup(sequence, 2000 + sequence, 2, List.of(new BlockChange(WORLD, "fixture", -17, 80, -1, plan.id(), "Chronicle",
                s.desired().equals(STONE) ? "PLACE" : "BREAK", s.expected(), s.desired(), "RECOVERY", 5023)),
                new RecoveryOutcome(plan.id(), step, s.expected(), s.desired()));
    }
    static LookupInput input() { return new LookupInput(List.of(), WORLD.toString(), 0, 2000, Set.of("PLACE", "BREAK"), new LookupFilter.Area(-17, null, -1, 1)); }
    @Test void reverseChainsRestoreExactlyOnceAndKeepOriginalEvidence() throws Exception {
        Path db = directory.resolve("history.db");
        try (var store = new SqliteStore(db, 100)) {
            store.startSession(1, "test");
            store.write(List.of(player(1, STONE, AIR), player(2, AIR, STONE)), List.of(), false, 0);
            String originals = SqliteStoreTest.query(db, "SELECT group_concat(hex(canonical_payload),'|') FROM changes");
            var r = store.recovery(); var plan = r.preview(input(), "CONSOLE", 2, 3000);
            assertEquals(List.of(2L, 1L), plan.steps().stream().map(RecoveryPlan.Step::source).toList());
            assertTrue(plan.steps().stream().allMatch(RecoveryPlan.Step::eligible));
            assertThrows(IllegalArgumentException.class, () -> r.confirm(plan.id(), "foreign", "ROLLBACK", 3001));
            assertEquals("PREPARED", r.confirm(plan.id(), "CONSOLE", "ROLLBACK", 3001).state());
            assertEquals("PREPARED", r.confirm(plan.id(), "CONSOLE", "ROLLBACK", 3002).state());
            assertEquals("", r.intent(plan.id(), 0, 2));
            var first = audit(plan, 0, 3); store.write(List.of(first), List.of(), false, 0);
            store.write(List.of(first), List.of(), false, 0);
            assertEquals("", r.intent(plan.id(), 1, 3)); store.write(List.of(audit(plan, 1, 4)), List.of(), false, 0);
            r.state(plan.id(), "COMPLETED", "");
            assertEquals("4", SqliteStoreTest.query(db, "SELECT count(*) FROM changes"));
            assertEquals(originals, SqliteStoreTest.query(db, "SELECT group_concat(hex(canonical_payload),'|') FROM changes WHERE id<=2"));
            assertEquals("2", SqliteStoreTest.query(db, "SELECT count(*) FROM changes WHERE actor_kind='SYSTEM' AND cause_kind='OPERATION' AND operation_id IS NOT NULL"));
            var restored = r.restore(plan.id(), "CONSOLE", 4, 4000);
            assertEquals(List.of(1, 0), restored.steps().stream().map(RecoveryPlan.Step::parentStep).toList());
            assertTrue(restored.steps().stream().allMatch(RecoveryPlan.Step::eligible));
            r.confirm(restored.id(), "CONSOLE", "RESTORE", 4001);
            assertEquals("", r.intent(restored.id(), 0, r.operation(plan.id()).steps().get(1).audit())); store.write(List.of(audit(restored, 0, 5)), List.of(), false, 0);
            assertEquals("", r.intent(restored.id(), 1, r.operation(restored.id()).steps().get(0).audit())); store.write(List.of(audit(restored, 1, 6)), List.of(), false, 0);
            r.state(restored.id(), "COMPLETED", "");
            assertThrows(IllegalArgumentException.class, () -> r.restore(plan.id(), "CONSOLE", 6, 5000));
            assertEquals("0", SqliteStoreTest.query(db, "SELECT count(*) FROM recovery_effects"));
            assertEquals("6", SqliteStoreTest.query(db, "SELECT count(*) FROM changes"));
        }
    }
    @Test void sameStateInterveningEditsAndGapsAreConflictsAndSkippedStepsCannotRestore() throws Exception {
        Path db = directory.resolve("history.db");
        try (var store = new SqliteStore(db, 100)) {
            store.startSession(1, "test"); store.write(List.of(player(1, AIR, STONE)), List.of(), false, 0);
            var r = store.recovery(); var p = r.preview(input(), "CONSOLE", 1, 3000); r.confirm(p.id(), "CONSOLE", "ROLLBACK", 3001);
            store.write(List.of(player(2, STONE, AIR), player(3, AIR, STONE)), List.of(), false, 0);
            assertEquals("Fresh history conflict", r.intent(p.id(), 0, 1));
            r.state(p.id(), "COMPLETED_WITH_SKIPS", "");
            assertThrows(IllegalArgumentException.class, () -> r.restore(p.id(), "CONSOLE", 3, 4000));
            var selected = new LookupInput(List.of(), WORLD.toString(), 0, 1002, Set.of("PLACE", "BREAK"), input().area());
            var conflict = r.preview(selected, "CONSOLE", 3, 4000);
            assertFalse(conflict.steps().getFirst().eligible());
            store.write(List.of(), List.of(new Incident(Coverage.AMBIGUOUS, 1, 5000, 5000, 3, 3)), false, 0);
            assertTrue(r.preview(input(), "CONSOLE", 3, 6000).steps().stream().noneMatch(RecoveryPlan.Step::eligible));
        }
    }
    @Test void outcomesRequireIntentsAndExpiredOrRestartedPreviewsCannotExecute() throws Exception {
        Path db = directory.resolve("history.db"); UUID retained;
        try (var store = new SqliteStore(db, 100)) {
            store.startSession(1, "test"); store.write(List.of(player(1, AIR, STONE)), List.of(), false, 0);
            var p = store.recovery().preview(input(), "CONSOLE", 1, 3000); retained = p.id();
            assertThrows(SQLException.class, () -> store.write(List.of(audit(p, 0, 2)), List.of(), false, 0));
            assertEquals("1", SqliteStoreTest.query(db, "SELECT count(*) FROM changes"));
            assertEquals("EXPIRED", store.recovery().confirm(p.id(), "CONSOLE", "ROLLBACK", p.expires()).state());
            var fresh = store.recovery().preview(input(), "CONSOLE", 1, 4000);
            store.recovery().confirm(fresh.id(), "CONSOLE", "ROLLBACK", 4001);
            store.recovery().intent(fresh.id(), 0, 1);
            store.write(List.of(), List.of(), true, 5000); // Clean logging shutdown is not a world checkpoint.
        }
        try (var reopened = new SqliteStore(db, 100)) {
            reopened.startSession(6000, "restart");
            assertEquals("EXPIRED", reopened.recovery().operation(retained).state());
            assertEquals("UNKNOWN", SqliteStoreTest.query(db, "SELECT status FROM recovery_steps WHERE status<>'PLANNED'"));
            assertEquals("NEEDS_RECONCILIATION", SqliteStoreTest.query(db, "SELECT state FROM operations WHERE state<>'EXPIRED'"));
            assertEquals("1", SqliteStoreTest.query(db, "SELECT count(*) FROM changes"));
        }
    }
    @Test void reviewedContinuationUsesOnlyUnattemptedStepsAndCompensationNeverVerifiesUnknownSource() throws Exception {
        Path db = directory.resolve("review.db");
        try (var store = new SqliteStore(db, 100)) {
            store.startSession(1, "test"); store.write(List.of(player(1, AIR, STONE)), List.of(), false, 0);
            var r = store.recovery(); var old = r.preview(input(), "CONSOLE", 1, 3000);
            r.confirm(old.id(), "CONSOLE", "ROLLBACK", 3001); r.state(old.id(), "PAUSED", "operator pause");
            var continuation = r.reviewed(old.id(), "CONSOLE", 1, 4000, false);
            assertEquals(1, continuation.steps().size());
            r.confirm(continuation.id(), "CONSOLE", "ROLLBACK", 4001);
            assertEquals("SKIPPED", r.operation(old.id()).steps().getFirst().status());
            assertEquals("", r.intent(continuation.id(), 0, 1));
            // A caller cannot tell whether its mutation completed; retain uncertainty explicitly.
            r.skip(continuation.id(), 0, "UNKNOWN", "lost post-check"); r.state(continuation.id(), "NEEDS_RECONCILIATION", "review");
            assertThrows(IllegalArgumentException.class, () -> r.restore(continuation.id(), "CONSOLE", 1, 4500));
            var compensate = r.reviewed(continuation.id(), "CONSOLE", 1, 5000, true);
            assertEquals(AIR, compensate.steps().getFirst().expected()); assertEquals(STONE, compensate.steps().getFirst().desired());
            r.confirm(compensate.id(), "CONSOLE", "COMPENSATE", 5001);
            assertEquals("", r.intent(compensate.id(), 0, 1));
            store.write(List.of(audit(compensate, 0, 2)), List.of(), false, 0);
            r.state(compensate.id(), "COMPLETED", "");
            assertEquals("UNKNOWN", r.operation(continuation.id()).steps().getFirst().status());
            assertEquals(compensate.id().toString(), r.operation(continuation.id()).steps().getFirst().restoredBy());
            assertThrows(IllegalArgumentException.class, () -> r.reviewed(continuation.id(), "CONSOLE", 2, 6000, true));
        }
    }
    @Test void oversizeSelectionAndUnsupportedMetadataFailClosed() throws Exception {
        Path db = directory.resolve("bounds.db");
        try (var store = new SqliteStore(db, 100)) {
            store.startSession(1, "test");
            var rows = new java.util.ArrayList<CaptureGroup>();
            for (int i = 1; i <= 1001; i++) rows.add(player(i, i % 2 == 1 ? AIR : STONE, i % 2 == 1 ? STONE : AIR));
            store.write(rows, List.of(), false, 0);
            var wide = new LookupInput(List.of(), WORLD.toString(), 0, 3000, Set.of("PLACE", "BREAK"), input().area());
            assertThrows(IllegalArgumentException.class, () -> store.recovery().preview(wide, "CONSOLE", 1001, 4000));
            assertEquals("0", SqliteStoreTest.query(db, "SELECT count(*) FROM operations"));
            SqliteStoreTest.execute(db, "UPDATE changes SET data_version=999 WHERE id=1001");
            var narrow = new LookupInput(List.of(), WORLD.toString(), 2001, 2002, Set.of("PLACE"), input().area());
            var invalid = store.recovery().preview(narrow, "CONSOLE", 1001, 4000);
            assertFalse(invalid.steps().getFirst().eligible());
        }
    }
    @Test void partialRestoreContinuationKeepsOriginalRollbackLinksAndNeverRepeatsAppliedSteps() throws Exception {
        Path db = directory.resolve("continue-restore.db");
        try (var store = new SqliteStore(db, 100)) {
            store.startSession(1, "test"); store.write(List.of(player(1, STONE, AIR), player(2, AIR, STONE)), List.of(), false, 0);
            var r = store.recovery(); var rollback = r.preview(input(), "CONSOLE", 2, 3000);
            r.confirm(rollback.id(), "CONSOLE", "ROLLBACK", 3001);
            assertEquals("", r.intent(rollback.id(), 0, 2)); store.write(List.of(audit(rollback, 0, 3)), List.of(), false, 0);
            assertEquals("", r.intent(rollback.id(), 1, 3)); store.write(List.of(audit(rollback, 1, 4)), List.of(), false, 0);
            r.state(rollback.id(), "COMPLETED", "");
            var restore = r.restore(rollback.id(), "CONSOLE", 4, 4000); r.confirm(restore.id(), "CONSOLE", "RESTORE", 4001);
            assertEquals("", r.intent(restore.id(), 0, 4)); store.write(List.of(audit(restore, 0, 5)), List.of(), false, 0);
            r.state(restore.id(), "PAUSED", "operator pause");
            var continued = r.reviewed(restore.id(), "CONSOLE", 5, 5000, false);
            assertEquals("RESTORE", continued.kind()); assertEquals(rollback.id(), continued.parent()); assertEquals(restore.id(), continued.continuation());
            assertEquals(1, continued.steps().size()); assertEquals(2, continued.steps().getFirst().source()); assertEquals(0, continued.steps().getFirst().parentStep());
            assertTrue(continued.steps().getFirst().eligible());
            r.confirm(continued.id(), "CONSOLE", "RESTORE", 5001);
            assertEquals("", r.intent(continued.id(), 0, 5)); store.write(List.of(audit(continued, 0, 6)), List.of(), false, 0);
            r.state(continued.id(), "COMPLETED", "");
            assertEquals("COMPLETED_WITH_SKIPS", r.operation(restore.id()).state());
            assertThrows(IllegalArgumentException.class, () -> r.restore(rollback.id(), "CONSOLE", 6, 6000));
            assertEquals("6", SqliteStoreTest.query(db, "SELECT count(*) FROM changes"));
        }
    }
}
