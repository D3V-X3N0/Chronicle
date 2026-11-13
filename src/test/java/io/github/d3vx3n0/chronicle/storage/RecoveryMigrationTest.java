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
class RecoveryMigrationTest {
    @TempDir Path directory;
    private void retainedV2(Path db) throws Exception {
        try (var connection = DriverManager.getConnection("jdbc:sqlite:" + db); var statement = connection.createStatement()) {
            statement.execute("CREATE TABLE schema_migrations(version INTEGER PRIMARY KEY, checksum TEXT NOT NULL)");
            for (int version = 1; version <= 2; version++) {
                String name = version == 1 ? "001-foundation.sql" : "002-investigation.sql";
                String sql;
                try (var input = getClass().getResourceAsStream("/db/" + name)) {
                    sql = new String(java.util.Objects.requireNonNull(input).readAllBytes(), StandardCharsets.UTF_8).replace("\r\n", "\n");
                }
                for (String part : sql.split(";")) if (!part.isBlank()) statement.execute(part);
                try (var insert = connection.prepareStatement("INSERT INTO schema_migrations VALUES(?,?)")) {
                    insert.setInt(1, version); insert.setString(2, HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(sql.getBytes(StandardCharsets.UTF_8)))); insert.executeUpdate();
                }
            }
            statement.execute("PRAGMA user_version=2");
            statement.execute("INSERT INTO sessions(id,started_at,runtime,clean) VALUES(7,1,'v2',1)");
            statement.execute("INSERT INTO worlds VALUES('00000000-0000-0000-0000-000000000001','old')");
            statement.execute("INSERT INTO actors VALUES('00000000-0000-0000-0000-000000000002','PLAYER','Retained')");
            statement.execute("INSERT INTO actor_names VALUES('Alias','00000000-0000-0000-0000-000000000002')");
            statement.execute("INSERT INTO capture_groups VALUES(7,9,1)");
            statement.execute("""
                    INSERT INTO changes(id,session_id,capture_sequence,member_index,occurred_at,tick,world_uuid,x,y,z,chunk_x,chunk_z,
                    actor_uuid,actor_name,action,before_data,after_data,codec_version,data_version,evidence,canonical_payload)
                    VALUES(42,7,9,0,999,1,'00000000-0000-0000-0000-000000000001',1,80,2,0,0,
                    '00000000-0000-0000-0000-000000000002','Retained','PLACE','minecraft:air','minecraft:stone',1,5023,'LATER_SAMPLE',x'ff001122')
                    """);
        }
    }
    @Test void v2UpgradePreservesOriginalIdsSnapshotsAliasesAndPayloads() throws Exception {
        Path db = directory.resolve("v2.db"); retainedV2(db);
        try (var store = new SqliteStore(db, 100)) { assertEquals(8, store.startSession(1000, "v3")); }
        assertEquals("3", SqliteStoreTest.query(db, "PRAGMA user_version"));
        assertEquals("42|7|9|minecraft:air|minecraft:stone|FF001122|0", SqliteStoreTest.query(db,
                "SELECT id||'|'||session_id||'|'||capture_sequence||'|'||before_data||'|'||after_data||'|'||hex(canonical_payload)||'|'||recovery_eligible FROM changes"));
        assertEquals("Alias", SqliteStoreTest.query(db, "SELECT name FROM actor_names"));
    }
    @Test void lateMigrationFailureRollsBackEntireV3AndReopensAfterRepairingFixture() throws Exception {
        Path db = directory.resolve("v2.db"); retainedV2(db);
        SqliteStoreTest.execute(db, "CREATE INDEX changes_operation_step ON changes(tick)");
        assertThrows(SQLException.class, () -> new SqliteStore(db, 100));
        assertEquals("2", SqliteStoreTest.query(db, "PRAGMA user_version"));
        assertEquals("0", SqliteStoreTest.query(db, "SELECT count(*) FROM sqlite_master WHERE name IN ('operations','recovery_steps','recovery_effects')"));
        assertEquals("0", SqliteStoreTest.query(db, "SELECT count(*) FROM pragma_table_info('changes') WHERE name='operation_id'"));
        assertEquals("FF001122", SqliteStoreTest.query(db, "SELECT hex(canonical_payload) FROM changes"));
        SqliteStoreTest.execute(db, "DROP INDEX changes_operation_step");
        try (var store = new SqliteStore(db, 100)) { assertEquals(0, store.previousUnclean()); }
    }
}
