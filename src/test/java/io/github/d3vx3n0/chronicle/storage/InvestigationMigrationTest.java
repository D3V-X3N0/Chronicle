package io.github.d3vx3n0.chronicle.storage;

import static org.junit.jupiter.api.Assertions.*;

import io.github.d3vx3n0.chronicle.storage.sqlite.SqliteStore;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.util.HexFormat;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

@Tag("database")
class InvestigationMigrationTest {
    @TempDir Path directory;
    private void v1(Path db) throws Exception {
        String sql;
        try (var resource = getClass().getResourceAsStream("/db/001-foundation.sql")) {
            sql = new String(java.util.Objects.requireNonNull(resource).readAllBytes(), StandardCharsets.UTF_8).replace("\r\n", "\n");
        }
        try (var connection = DriverManager.getConnection("jdbc:sqlite:" + db); var statement = connection.createStatement()) {
            statement.execute("CREATE TABLE schema_migrations(version INTEGER PRIMARY KEY,checksum TEXT NOT NULL)");
            for (String part : sql.split(";")) if (!part.isBlank()) statement.execute(part);
            try (var insert = connection.prepareStatement("INSERT INTO schema_migrations VALUES(1,?)")) {
                insert.setString(1, HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(sql.getBytes(StandardCharsets.UTF_8)))); insert.executeUpdate();
            }
            statement.execute("PRAGMA user_version=1");
            statement.execute("INSERT INTO sessions(id,started_at,runtime) VALUES(1,100,'retained M1')");
            statement.execute("INSERT INTO worlds VALUES('00000000-0000-0000-0000-000000000001','world')");
            statement.execute("INSERT INTO actors VALUES('00000000-0000-0000-0000-000000000002','PLAYER','NewName')");
            statement.execute("INSERT INTO capture_groups VALUES(1,1,1)");
            statement.execute("""
                    INSERT INTO changes(session_id,capture_sequence,member_index,occurred_at,tick,world_uuid,x,y,z,chunk_x,chunk_z,
                    actor_uuid,actor_name,action,before_data,after_data,codec_version,data_version,evidence,canonical_payload)
                    VALUES(1,1,0,100,1,'00000000-0000-0000-0000-000000000001',-17,80,-1,-2,-1,
                    '00000000-0000-0000-0000-000000000002','OldName','PLACE','minecraft:air','minecraft:stone',1,5023,'LATER_SAMPLE',x'0011ff')
                    """);
        }
    }
    @Test void upgradesRetainedM1HistoryAndBothNamesWithoutRewritingPayload() throws Exception {
        Path db = directory.resolve("v1.db"); v1(db);
        try (var store = new SqliteStore(db, 100)) { assertEquals(1, store.previousUnclean()); }
        assertEquals("3", SqliteStoreTest.query(db, "PRAGMA user_version"));
        assertEquals("1|1|0011FF|PLAYER|PLAYER_ACTION|DIRECT", SqliteStoreTest.query(db,
                "SELECT id||'|'||capture_sequence||'|'||hex(canonical_payload)||'|'||actor_kind||'|'||cause_kind||'|'||attribution_quality FROM changes"));
        assertEquals("2", SqliteStoreTest.query(db, "SELECT count(*) FROM actor_names"));
        try (var store = new SqliteStore(db, 100)) { assertEquals(2, store.startSession(200, "M2")); }
    }
    @Test void failedUpgradeRollsBackEveryAddedColumnTableAndLedgerEntry() throws Exception {
        Path db = directory.resolve("v1.db"); v1(db);
        SqliteStoreTest.execute(db, "CREATE INDEX changes_actor_id ON changes(tick)"); // Failure late in migration 2.
        assertThrows(SQLException.class, () -> new SqliteStore(db, 100));
        assertEquals("1", SqliteStoreTest.query(db, "PRAGMA user_version"));
        assertEquals("1", SqliteStoreTest.query(db, "SELECT count(*) FROM schema_migrations"));
        assertEquals("0", SqliteStoreTest.query(db, "SELECT count(*) FROM pragma_table_info('changes') WHERE name='actor_kind'"));
        assertEquals("0", SqliteStoreTest.query(db, "SELECT count(*) FROM sqlite_master WHERE name='actor_names'"));
        assertEquals("0011FF", SqliteStoreTest.query(db, "SELECT hex(canonical_payload) FROM changes"));
        SqliteStoreTest.execute(db, "DROP INDEX changes_actor_id");
        try (var store = new SqliteStore(db, 100)) { assertEquals(1, store.previousUnclean()); }
    }
}
