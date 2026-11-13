package io.github.d3vx3n0.chronicle.storage.sqlite;

import static org.junit.jupiter.api.Assertions.*;
import io.github.d3vx3n0.chronicle.model.BlockChange;
import io.github.d3vx3n0.chronicle.model.CaptureGroup;
import io.github.d3vx3n0.chronicle.query.LookupFilter;
import io.github.d3vx3n0.chronicle.query.LookupInput;
import io.github.d3vx3n0.chronicle.recovery.RecoveryOutcome;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.DriverManager;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

@Tag("database")
class RecoveryCrashTest {
    @TempDir Path directory;
    @ParameterizedTest @ValueSource(strings = {"recovery-before-commit", "recovery-after-commit", "before-commit", "after-commit", "acknowledged"})
    void intentAndOutcomeTransactionsSurviveKillsWithoutPartialAuditOrReplay(String boundary) throws Exception {
        Path db = directory.resolve("history.db"), log = directory.resolve("child.log");
        Path java = Path.of(System.getProperty("java.home"), "bin", System.getProperty("os.name").startsWith("Windows") ? "java.exe" : "java");
        var child = new ProcessBuilder(java.toString(), "--enable-native-access=ALL-UNNAMED", "-cp", System.getProperty("chronicle.testClasspath"),
                Child.class.getName(), db.toString(), boundary).redirectErrorStream(true).redirectOutput(log.toFile()).start();
        try {
            long until = System.nanoTime() + TimeUnit.SECONDS.toNanos(15);
            while (child.isAlive() && !Files.readString(log).contains("BOUNDARY " + boundary) && System.nanoTime() < until) Thread.sleep(10);
            assertTrue(Files.readString(log).contains("BOUNDARY " + boundary), () -> read(log));
            child.destroyForcibly(); assertTrue(child.waitFor(10, TimeUnit.SECONDS));
            try (var store = new SqliteStore(db, 100)) {
                assertEquals(1, store.previousUnclean()); store.startSession(6000, "restart");
                try (var connection = DriverManager.getConnection("jdbc:sqlite:" + db); var statement = connection.createStatement()) {
                    boolean committed = boundary.equals("after-commit") || boundary.equals("acknowledged");
                    try (var r = statement.executeQuery("SELECT status,audit_id FROM recovery_steps")) {
                        assertTrue(r.next()); assertEquals(committed ? "APPLIED" : boundary.equals("recovery-before-commit") ? "PLANNED" : "UNKNOWN", r.getString(1));
                        assertEquals(committed, r.getObject(2) != null);
                    }
                    try (var r = statement.executeQuery("SELECT count(*) FROM changes")) { assertTrue(r.next()); assertEquals(committed ? 2 : 1, r.getInt(1)); }
                    try (var r = statement.executeQuery("SELECT state FROM operations")) { assertTrue(r.next()); assertEquals("NEEDS_RECONCILIATION", r.getString(1)); }
                }
            }
        } finally { if (child.isAlive()) { child.destroyForcibly(); child.waitFor(10, TimeUnit.SECONDS); } }
    }
    private static String read(Path p) { try { return Files.readString(p); } catch (Exception error) { return error.toString(); } }
    public static final class Child {
        private Child() {}
        public static void main(String[] args) throws Exception {
            var armed = new AtomicBoolean(); String boundary = args[1];
            try (var store = new SqliteStore(Path.of(args[0]), 100, point -> { if (armed.get() && point.equals(boundary)) stop(boundary); })) {
                store.startSession(1, "crash"); var world = new UUID(0, 1); var actor = new UUID(0, 2);
                store.write(List.of(new CaptureGroup(1, 1000, 1, List.of(new BlockChange(world, "test", 0, 80, 0, actor, "Test", "PLACE",
                        "minecraft:air", "minecraft:stone", "LATER_SAMPLE", 5023)))), List.of(), false, 0);
                var input = new LookupInput(List.of(), world.toString(), 0, 2000, Set.of("PLACE"), new LookupFilter.Area(0, null, 0, 0));
                var plan = store.recovery().preview(input, "CONSOLE", 1, 3000);
                store.recovery().confirm(plan.id(), "CONSOLE", "ROLLBACK", 3001);
                armed.set(true); store.recovery().intent(plan.id(), 0, 1);
                var audit = new CaptureGroup(2, 4000, 2, List.of(new BlockChange(world, "test", 0, 80, 0, plan.id(), "Chronicle", "BREAK",
                        "minecraft:stone", "minecraft:air", "RECOVERY", 5023)), new RecoveryOutcome(plan.id(), 0, "minecraft:stone", "minecraft:air"));
                store.write(List.of(audit), List.of(), false, 0);
                if (boundary.equals("acknowledged")) stop(boundary);
                throw new AssertionError("Boundary not reached");
            }
        }
        private static void stop(String boundary) {
            System.out.println("BOUNDARY " + boundary); System.out.flush();
            try { new CountDownLatch(1).await(); } catch (InterruptedException error) { Thread.currentThread().interrupt(); }
        }
    }
}
