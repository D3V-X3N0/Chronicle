package io.github.d3vx3n0.chronicle.storage.sqlite;

import static org.junit.jupiter.api.Assertions.*;

import io.github.d3vx3n0.chronicle.model.BlockChange;
import io.github.d3vx3n0.chronicle.model.CaptureGroup;
import java.nio.file.Path;
import java.sql.DriverManager;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

@Tag("database")
class UncheckedTransactionTest {
    @TempDir Path directory;

    @ParameterizedTest @ValueSource(booleans = {false, true})
    void uncheckedFailureAfterFirstMemberRollsBackWholeBatch(boolean nativeFailure) throws Exception {
        Path db = directory.resolve("history.db");
        var armed = new AtomicBoolean(true);
        var change = new BlockChange(new UUID(0, 1), "test", 0, 80, 0, new UUID(0, 2), "Test", "PLACE",
                "minecraft:air", "minecraft:stone", "LATER_SAMPLE", 5023);
        var group = new CaptureGroup(1, 123, 5, List.of(change, change));
        try (var store = new SqliteStore(db, 100, boundary -> {
            if (boundary.equals("row") && armed.getAndSet(false)) {
                if (nativeFailure) throw new UnsatisfiedLinkError("injected native failure");
                throw new IllegalStateException("injected unchecked failure");
            }
        })) {
            store.startSession(1, "test");
            Class<? extends Throwable> expected = nativeFailure ? UnsatisfiedLinkError.class : IllegalStateException.class;
            assertThrows(expected, () -> store.write(List.of(group), List.of(), false, 0));
            try (var connection = DriverManager.getConnection("jdbc:sqlite:" + db); var statement = connection.createStatement()) {
                for (String table : List.of("changes", "capture_groups", "actors", "worlds")) {
                    try (var rows = statement.executeQuery("SELECT count(*) FROM " + table)) {
                        assertTrue(rows.next()); assertEquals(0, rows.getInt(1), table + " retained a partial transaction");
                    }
                }
            }
            assertEquals(2, store.write(List.of(group), List.of(), false, 0));
        }
    }
}
