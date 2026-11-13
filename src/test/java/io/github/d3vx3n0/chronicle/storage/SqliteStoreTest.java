package io.github.d3vx3n0.chronicle.storage;

import static org.junit.jupiter.api.Assertions.*;

import io.github.d3vx3n0.chronicle.model.BlockChange;
import io.github.d3vx3n0.chronicle.model.CaptureGroup;
import io.github.d3vx3n0.chronicle.storage.sqlite.SqliteStore;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

@Tag("database")
class SqliteStoreTest {
    @TempDir Path directory;
    static final UUID WORLD = UUID.fromString("00000000-0000-0000-0000-000000000001");
    static final UUID ACTOR = UUID.fromString("00000000-0000-0000-0000-000000000002");
    static CaptureGroup group(long sequence, int size) {
        var changes = new java.util.ArrayList<BlockChange>();
        for (int i = 0; i < size; i++) changes.add(new BlockChange(WORLD, "test", -17 + i, 80, -1, ACTOR, "Test",
                "PLACE", "minecraft:air", "minecraft:stone", "LATER_SAMPLE", 5023));
        return new CaptureGroup(sequence, 123456, 5, changes);
    }
    static String query(Path db, String sql) throws SQLException {
        try (var connection = DriverManager.getConnection("jdbc:sqlite:" + db); var statement = connection.createStatement(); var result = statement.executeQuery(sql)) {
            assertTrue(result.next()); return result.getString(1);
        }
    }
    static void execute(Path db, String sql) throws SQLException {
        try (var connection = DriverManager.getConnection("jdbc:sqlite:" + db); var statement = connection.createStatement()) { statement.execute(sql); }
    }
    @Test void createReopenExactIdentitiesSnapshotsAndPragmas() throws Exception {
        Path db = directory.resolve("history.db");
        try (var store = new SqliteStore(db, 100)) {
            assertEquals("3.54.1", store.engineVersion()); assertEquals(0, store.previousUnclean());
            assertEquals(1, store.startSession(123, "test"));
            assertEquals(2, store.write(List.of(group(1, 2)), List.of(), false, 0));
            store.write(List.of(), List.of(), true, 456);
        }
        try (var store = new SqliteStore(db, 100)) {
            assertEquals(0, store.previousUnclean()); assertEquals(2, store.startSession(789, "test"));
            assertEquals("2", query(db, "SELECT count(*) FROM changes"));
            assertEquals("wal", query(db, "PRAGMA journal_mode"));
            assertEquals("3", query(db, "PRAGMA user_version"));
            assertEquals("-2|-1|" + WORLD + "|" + ACTOR + "|minecraft:air|minecraft:stone|1|5023|0",
                    query(db, "SELECT chunk_x||'|'||chunk_z||'|'||world_uuid||'|'||actor_uuid||'|'||before_data||'|'||after_data||'|'||codec_version||'|'||data_version||'|'||recovery_eligible FROM changes WHERE id=1"));
        }
    }
    @Test void retryAfterLostAcknowledgementIsIdempotentAndMismatchRollsBack() throws Exception {
        Path db = directory.resolve("history.db");
        try (var store = new SqliteStore(db, 100)) {
            store.startSession(1, "test");
            var group = group(1, 2);
            store.write(List.of(group), List.of(), false, 0); // Commit acknowledgement deliberately discarded.
            store.write(List.of(group), List.of(), false, 0);
            assertEquals("2", query(db, "SELECT count(*) FROM changes"));
            assertThrows(SQLException.class, () -> store.write(List.of(group(2, 1), group(1, 1)), List.of(), false, 0));
            assertEquals("2", query(db, "SELECT count(*) FROM changes"));
            var altered = new CaptureGroup(1, 9999, 5, group.changes());
            assertThrows(SQLException.class, () -> store.write(List.of(altered), List.of(), false, 0));
            assertEquals("1", query(db, "SELECT count(*) FROM capture_groups"));
        }
    }
    @Test void failingSecondMemberCannotPersistHalfGroup() throws Exception {
        Path db = directory.resolve("history.db");
        try (var store = new SqliteStore(db, 100)) {
            store.startSession(1, "test");
            execute(db, "CREATE TRIGGER fail_member BEFORE INSERT ON changes WHEN NEW.member_index=1 BEGIN SELECT RAISE(ABORT,'injected disk failure'); END");
            assertThrows(SQLException.class, () -> store.write(List.of(group(1, 2)), List.of(), false, 0));
            assertEquals("0", query(db, "SELECT count(*) FROM changes"));
            assertEquals("0", query(db, "SELECT count(*) FROM capture_groups"));
            assertEquals("0", query(db, "SELECT count(*) FROM actors"));
        }
    }
    @Test void refusesNewerChangedPartialAndCorruptDatabasesWithoutReset() throws Exception {
        for (String kind : List.of("newer", "checksum", "partial", "corrupt")) {
            Path db = directory.resolve(kind + ".db");
            if (kind.equals("corrupt")) Files.writeString(db, "not a sqlite database");
            else if (kind.equals("partial")) execute(db, "CREATE TABLE incomplete(value TEXT)");
            else {
                try (var store = new SqliteStore(db, 100)) { store.startSession(1, "retained"); }
                execute(db, kind.equals("newer") ? "PRAGMA user_version=99" : "UPDATE schema_migrations SET checksum='tampered'");
            }
            byte[] before = Files.readAllBytes(db);
            assertThrows(SQLException.class, () -> new SqliteStore(db, 100));
            assertArrayEquals(before, Files.readAllBytes(db), kind);
        }
    }
    @Test void interruptedTransactionalUpgradeLeavesNoPartialSchema() throws Exception {
        Path db = directory.resolve("history.db");
        try (var connection = DriverManager.getConnection("jdbc:sqlite:" + db); var statement = connection.createStatement()) {
            connection.setAutoCommit(false); statement.execute("CREATE TABLE schema_migrations(version INTEGER PRIMARY KEY, checksum TEXT)");
            statement.execute("PRAGMA user_version=1"); // Closing without commit is an interrupted transaction.
        }
        try (var store = new SqliteStore(db, 100)) { assertEquals(1, store.startSession(1, "test")); }
        assertEquals("3", query(db, "PRAGMA user_version"));
    }
    @Test void unavailableStorageAndWrongThreadFailExplicitly() throws Exception {
        Path db = directory.resolve("folder.db"); Files.createDirectory(db);
        assertThrows(SQLException.class, () -> new SqliteStore(db, 100));
        try (var store = new SqliteStore(directory.resolve("valid.db"), 100)) {
            var outcome = new java.util.concurrent.atomic.AtomicReference<Throwable>();
            var thread = new Thread(() -> { try { store.startSession(1, "wrong"); } catch (Throwable error) { outcome.set(error); } });
            thread.start(); thread.join(); assertInstanceOf(IllegalStateException.class, outcome.get());
        }
    }
}
