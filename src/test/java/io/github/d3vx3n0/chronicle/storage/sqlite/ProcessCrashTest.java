package io.github.d3vx3n0.chronicle.storage.sqlite;

import static org.junit.jupiter.api.Assertions.*;

import io.github.d3vx3n0.chronicle.model.BlockChange;
import io.github.d3vx3n0.chronicle.model.CaptureGroup;
import io.github.d3vx3n0.chronicle.storage.HistoryWriter;
import io.github.d3vx3n0.chronicle.storage.StorageSettings;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.DriverManager;
import java.time.Duration;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

@Tag("database")
class ProcessCrashTest {
    @TempDir Path directory;
    @ParameterizedTest @ValueSource(strings = {"buffered", "row", "before-commit", "after-commit", "acknowledged"})
    void killAtKnownBoundaryPreservesAtomicityAndUncleanSession(String boundary) throws Exception {
        Path db = directory.resolve("history.db"), log = directory.resolve("child.txt");
        Path java = Path.of(System.getProperty("java.home"), "bin", System.getProperty("os.name").startsWith("Windows") ? "java.exe" : "java");
        var process = new ProcessBuilder(java.toString(), "--enable-native-access=ALL-UNNAMED", "-cp", System.getProperty("chronicle.testClasspath"),
                Child.class.getName(), db.toString(), boundary).redirectErrorStream(true).redirectOutput(log.toFile()).start();
        try {
            long deadline = System.nanoTime() + Duration.ofSeconds(15).toNanos();
            while (process.isAlive() && !Files.readString(log).contains("BOUNDARY " + boundary) && System.nanoTime() < deadline) Thread.sleep(10);
            assertTrue(Files.readString(log).contains("BOUNDARY " + boundary), () -> "Child failed: " + read(log));
            process.destroyForcibly(); assertTrue(process.waitFor(5, TimeUnit.SECONDS));
            try (var store = new SqliteStore(db, 100)) {
                assertEquals(1, store.previousUnclean());
                try (var connection = DriverManager.getConnection("jdbc:sqlite:" + db); var statement = connection.createStatement();
                        var rows = statement.executeQuery("SELECT count(*) FROM changes")) {
                    assertTrue(rows.next());
                    assertEquals(boundary.equals("after-commit") || boundary.equals("acknowledged") ? 2 : 0, rows.getInt(1));
                }
            }
        } finally { if (process.isAlive()) { process.destroyForcibly(); process.waitFor(5, TimeUnit.SECONDS); } }
    }
    private static String read(Path path) { try { return Files.readString(path); } catch (Exception error) { return error.toString(); } }
    public static final class Child {
        private Child() {}
        public static void main(String[] args) throws Exception {
            String boundary = args[1];
            var writer = new HistoryWriter(StorageSettings.defaults(), () -> new SqliteStore(Path.of(args[0]), 100,
                    step -> { if (step.equals(boundary)) pause(boundary); }), "process-fixture", message -> {});
            long deadline = System.nanoTime() + Duration.ofSeconds(10).toNanos();
            while (writer.status().state() == HistoryWriter.State.STARTING && System.nanoTime() < deadline) Thread.sleep(5);
            if (writer.status().state() != HistoryWriter.State.READY) throw new IllegalStateException(writer.status().toString());
            var change = new BlockChange(new UUID(0, 1), "test", -1, 80, -17, new UUID(0, 2), "Test", "PLACE",
                    "minecraft:air", "minecraft:stone", "LATER_SAMPLE", 5023);
            if (!writer.offer(new CaptureGroup(1, 123, 5, List.of(change, change)))) throw new IllegalStateException("admission rejected");
            while (writer.status().committed() != 2 && System.nanoTime() < deadline) Thread.sleep(5);
            if (boundary.equals("acknowledged") && writer.status().committed() == 2) pause(boundary);
            throw new IllegalStateException("Boundary not reached: " + writer.status());
        }
        private static void pause(String boundary) {
            System.out.println("BOUNDARY " + boundary); System.out.flush();
            try { new CountDownLatch(1).await(); } catch (InterruptedException error) { Thread.currentThread().interrupt(); }
        }
    }
}
