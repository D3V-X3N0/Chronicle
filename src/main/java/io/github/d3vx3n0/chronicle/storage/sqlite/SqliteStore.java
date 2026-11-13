package io.github.d3vx3n0.chronicle.storage.sqlite;

import io.github.d3vx3n0.chronicle.model.BlockChange;
import io.github.d3vx3n0.chronicle.model.CaptureGroup;
import io.github.d3vx3n0.chronicle.storage.Incident;
import io.github.d3vx3n0.chronicle.storage.HistoryStore;
import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.Arrays;
import java.util.HexFormat;
import java.util.List;
import java.util.Properties;
import org.sqlite.JDBC;

/** All methods (including close) belong to the creating worker. Never called from capture. */
public final class SqliteStore implements HistoryStore {
    private final Thread owner = Thread.currentThread();
    private final Connection connection;
    private final FileChannel lockChannel;
    private final FileLock lock;
    private final java.net.URLClassLoader driverLoader;
    private final String engineVersion;
    private long session;
    private final java.util.function.Consumer<String> transactionProbe;

    public SqliteStore(Path path, int busyMillis) throws IOException, SQLException {
        this(path, busyMillis, boundary -> {});
    }
    SqliteStore(Path path, int busyMillis, java.util.function.Consumer<String> transactionProbe) throws IOException, SQLException {
        this.transactionProbe = transactionProbe;
        Files.createDirectories(path.toAbsolutePath().getParent());
        lockChannel = FileChannel.open(path.resolveSibling(path.getFileName() + ".lock"), StandardOpenOption.CREATE, StandardOpenOption.WRITE);
        FileLock acquired = null;
        Connection opened = null;
        java.net.URLClassLoader isolated = null;
        try {
            acquired = lockChannel.tryLock();
            if (acquired == null) throw new IOException("Another Chronicle writer owns this database");
            var source = SqliteStore.class.getProtectionDomain().getCodeSource().getLocation();
            String url = "jdbc:sqlite:" + path.toAbsolutePath();
            if (source.getPath().endsWith(".jar")) {
                isolated = new java.net.URLClassLoader(new java.net.URL[] {source}, ClassLoader.getPlatformClassLoader());
                try {
                    opened = (Connection) isolated.loadClass(IsolatedDriver.class.getName()).getMethod("connect", String.class).invoke(null, url);
                } catch (ReflectiveOperationException failure) { throw new SQLException("Packaged SQLite driver failed to load", failure.getCause() == null ? failure : failure.getCause()); }
            } else opened = new JDBC().connect(url, new Properties()); // Gradle source tests use the pinned dependency.
            driverLoader = isolated;
            connection = opened; lock = acquired;
            engineVersion = scalar("SELECT sqlite_version()");
            String[] version = engineVersion.split("\\.");
            if (version.length < 3 || Integer.parseInt(version[0]) < 3
                    || Integer.parseInt(version[0]) == 3 && (Integer.parseInt(version[1]) < 51
                    || Integer.parseInt(version[1]) == 51 && Integer.parseInt(version[2]) < 3)) {
                throw new SQLException("SQLite >= 3.51.3 required; loaded " + engineVersion);
            }
            execute("PRAGMA busy_timeout=" + busyMillis);
            validateSchema(); // Refuse unknown input before journal/schema changes.
            if (!scalar("PRAGMA quick_check(1)").equals("ok")) throw new SQLException("SQLite integrity check failed");
            if (!scalar("PRAGMA journal_mode=WAL").equalsIgnoreCase("wal")) throw new SQLException("WAL unavailable");
            execute("PRAGMA synchronous=FULL"); execute("PRAGMA foreign_keys=ON");
            if (!scalar("PRAGMA synchronous").equals("2") || !scalar("PRAGMA foreign_keys").equals("1")
                    || !scalar("PRAGMA busy_timeout").equals(Integer.toString(busyMillis))) throw new SQLException("Required SQLite settings unavailable");
            migrate();
        } catch (IOException | SQLException | RuntimeException | LinkageError failure) {
            if (opened != null) try { opened.close(); } catch (SQLException close) { failure.addSuppressed(close); }
            if (acquired != null) try { acquired.close(); } catch (IOException close) { failure.addSuppressed(close); }
            if (isolated != null) try { isolated.close(); } catch (IOException close) { failure.addSuppressed(close); }
            try { lockChannel.close(); } catch (IOException close) { failure.addSuppressed(close); }
            throw failure;
        }
    }

    public String engineVersion() { return engineVersion; }
    public long session() { return session; }
    public long previousUnclean() throws SQLException { checkOwner(); return Long.parseLong(scalar("SELECT count(*) FROM sessions WHERE clean=0")); }
    public long previousCoverageIncidents() throws SQLException { checkOwner(); return Long.parseLong(scalar("SELECT count(*) FROM coverage_incidents WHERE reason<>'STARTUP'")); }
    public long earliestUncleanStart() throws SQLException { checkOwner(); return Long.parseLong(scalar("SELECT coalesce(min(started_at),0) FROM sessions WHERE clean=0")); }

    public long startSession(long startedAt, String runtime) throws SQLException {
        checkOwner();
        try (var statement = connection.prepareStatement("INSERT INTO sessions(started_at,runtime) VALUES (?,?)")) {
            statement.setLong(1, startedAt); statement.setString(2, runtime); statement.executeUpdate();
        }
        session = Long.parseLong(scalar("SELECT last_insert_rowid()"));
        recovery().startup();
        return session;
    }

    public static final int SCHEMA_VERSION = 3;
    private static String migration(int version) throws IOException {
        String resource = switch (version) { case 1 -> "001-foundation.sql"; case 2 -> "002-investigation.sql"; case 3 -> "003-recovery.sql";
            default -> throw new IOException("Unknown migration"); };
        try (var input = SqliteStore.class.getResourceAsStream("/db/" + resource)) {
            if (input == null) throw new IOException("Missing migration resource");
            return new String(input.readAllBytes(), StandardCharsets.UTF_8).replace("\r\n", "\n");
        }
    }
    private static String checksum(String sql) {
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(sql.getBytes(StandardCharsets.UTF_8))); }
        catch (NoSuchAlgorithmException impossible) { throw new AssertionError(impossible); }
    }
    private void validateSchema() throws SQLException, IOException {
        int version = Integer.parseInt(scalar("PRAGMA user_version"));
        if (version > SCHEMA_VERSION || version < 0) throw new SQLException("Unsupported schema version " + version + "; database preserved");
        if (version == 0) {
            if (!scalar("SELECT count(*) FROM sqlite_master WHERE name NOT LIKE 'sqlite_%'").equals("0"))
                throw new SQLException("Unversioned/partial schema; database preserved");
        } else {
            if (!scalar("SELECT count(*) FROM schema_migrations").equals(Integer.toString(version))) throw new SQLException("Partial migration ledger");
            for (int applied = 1; applied <= version; applied++) {
                try (var statement = connection.prepareStatement("SELECT checksum FROM schema_migrations WHERE version=?")) {
                    statement.setInt(1, applied);
                    try (var result = statement.executeQuery()) {
                        if (!result.next() || !result.getString(1).equals(checksum(migration(applied)))) throw new SQLException("Applied migration checksum mismatch");
                    }
                }
            }
        }
    }
    private void migrate() throws SQLException, IOException {
        int version = Integer.parseInt(scalar("PRAGMA user_version"));
        if (version == SCHEMA_VERSION) return;
        connection.setAutoCommit(false);
        try {
            if (version == 0) execute("CREATE TABLE schema_migrations(version INTEGER PRIMARY KEY, checksum TEXT NOT NULL)");
            for (int next = version + 1; next <= SCHEMA_VERSION; next++) {
                String sql = migration(next);
                for (String statement : sql.split(";")) if (!statement.isBlank()) execute(statement);
                try (var statement = connection.prepareStatement("INSERT INTO schema_migrations VALUES(?,?)")) {
                    statement.setInt(1, next); statement.setString(2, checksum(sql)); statement.executeUpdate();
                }
                execute("PRAGMA user_version=" + next);
            }
            connection.commit(); connection.setAutoCommit(true);
        } catch (SQLException | RuntimeException | Error failure) { rollback(failure); throw failure; }
    }

    /** Same keys require exactly the same canonical contents, including all identity/evidence fields. */
    public long write(List<CaptureGroup> groups, List<Incident> incidents, boolean clean, long stoppedAt) throws SQLException {
        checkOwner();
        if (session == 0) throw new IllegalStateException("Session not started");
        if (!groups.isEmpty()) transactionProbe.accept("buffered");
        connection.setAutoCommit(false);
        try (var world = connection.prepareStatement("INSERT INTO worlds VALUES (?,?) ON CONFLICT(uuid) DO UPDATE SET last_name=excluded.last_name");
                var actor = connection.prepareStatement("INSERT INTO actors VALUES (?,'PLAYER',?) ON CONFLICT(uuid) DO UPDATE SET last_name=excluded.last_name");
                var alias = connection.prepareStatement("INSERT INTO actor_names VALUES (?,?) ON CONFLICT DO NOTHING");
                var groupInsert = connection.prepareStatement("INSERT INTO capture_groups VALUES (?,?,?) ON CONFLICT DO NOTHING");
                var groupRead = connection.prepareStatement("SELECT member_count FROM capture_groups WHERE session_id=? AND sequence=?");
                var insert = connection.prepareStatement("""
                    INSERT INTO changes(session_id,capture_sequence,member_index,occurred_at,tick,world_uuid,x,y,z,chunk_x,chunk_z,
                        actor_uuid,actor_name,action,before_data,after_data,codec_version,data_version,evidence,canonical_payload)
                    VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,1,?,?,?) ON CONFLICT(session_id,capture_sequence,member_index) DO NOTHING
                    """);
                var existing = connection.prepareStatement("SELECT canonical_payload FROM changes WHERE session_id=? AND capture_sequence=? AND member_index=?")) {
            for (CaptureGroup group : groups) {
                groupInsert.setLong(1, session); groupInsert.setLong(2, group.sequence()); groupInsert.setInt(3, group.changes().size()); groupInsert.executeUpdate();
                groupRead.setLong(1, session); groupRead.setLong(2, group.sequence());
                try (var result = groupRead.executeQuery()) {
                    if (!result.next() || result.getInt(1) != group.changes().size()) throw new SQLException("Capture group payload mismatch");
                }
                for (int member = 0; member < group.changes().size(); member++) {
                    BlockChange change = group.changes().get(member);
                    world.setString(1, change.world().toString()); world.setString(2, change.worldName()); world.executeUpdate();
                    actor.setString(1, change.actor().toString()); actor.setString(2, change.actorName()); actor.executeUpdate();
                    alias.setString(1, change.actorName()); alias.setString(2, change.actor().toString()); alias.executeUpdate();
                    byte[] payload = canonical(group, member);
                    int p = 1;
                    insert.setLong(p++, session); insert.setLong(p++, group.sequence()); insert.setInt(p++, member);
                    insert.setLong(p++, group.occurredAt()); insert.setInt(p++, group.tick()); insert.setString(p++, change.world().toString());
                    insert.setInt(p++, change.x()); insert.setInt(p++, change.y()); insert.setInt(p++, change.z());
                    insert.setInt(p++, Math.floorDiv(change.x(), 16)); insert.setInt(p++, Math.floorDiv(change.z(), 16));
                    insert.setString(p++, change.actor().toString()); insert.setString(p++, change.actorName()); insert.setString(p++, change.action());
                    insert.setString(p++, change.before()); insert.setString(p++, change.after());
                    insert.setInt(p++, change.dataVersion()); insert.setString(p++, change.evidence()); insert.setBytes(p, payload);
                    insert.executeUpdate();
                    if (group.outcome() != null) {
                        var outcome = group.outcome();
                        try (var audit = connection.prepareStatement("UPDATE changes SET operation_id=?,operation_step=?,actor_kind='SYSTEM',cause_kind='OPERATION',attribution_quality='EXACT' WHERE session_id=? AND capture_sequence=? AND member_index=0")) {
                            audit.setString(1, outcome.operation().toString()); audit.setInt(2, outcome.step());
                            audit.setLong(3, session); audit.setLong(4, group.sequence()); audit.executeUpdate();
                        }
                        recovery().applied(outcome, session, group.sequence());
                    }
                    transactionProbe.accept("row");
                    existing.setLong(1, session); existing.setLong(2, group.sequence()); existing.setInt(3, member);
                    try (var result = existing.executeQuery()) {
                        if (!result.next() || !Arrays.equals(payload, result.getBytes(1))) throw new SQLException("Capture key payload mismatch");
                    }
                }
            }
            try (var incident = connection.prepareStatement("""
                    INSERT INTO coverage_incidents(session_id,reason,count,first_time,last_time,first_sequence,last_sequence)
                    VALUES (?,?,?,?,?,?,?) ON CONFLICT(session_id,reason) DO UPDATE SET count=excluded.count,
                    last_time=excluded.last_time,last_sequence=excluded.last_sequence
                    """)) {
                for (Incident value : incidents) {
                    incident.setLong(1, session); incident.setString(2, value.reason().name()); incident.setLong(3, value.count());
                    incident.setLong(4, value.firstTime()); incident.setLong(5, value.lastTime());
                    incident.setLong(6, value.firstSequence()); incident.setLong(7, value.lastSequence()); incident.addBatch();
                }
                incident.executeBatch();
            }
            if (clean) {
                try (var pause = connection.prepareStatement("UPDATE operations SET state='PAUSED',reason='Plugin shutdown; fresh review required' WHERE session_id=? AND state IN ('PREPARED','APPLYING')")) {
                    pause.setLong(1, session); pause.executeUpdate();
                }
            }
            try (var statement = connection.prepareStatement("UPDATE sessions SET last_sequence=max(last_sequence,?),clean=?,stopped_at=? WHERE id=?")) {
                statement.setLong(1, groups.isEmpty() ? 0 : groups.getLast().sequence()); statement.setBoolean(2, clean);
                if (clean) statement.setLong(3, stoppedAt); else statement.setNull(3, java.sql.Types.BIGINT);
                statement.setLong(4, session); statement.executeUpdate();
            }
            if (!groups.isEmpty()) transactionProbe.accept("before-commit");
            connection.commit();
            if (!groups.isEmpty()) transactionProbe.accept("after-commit");
            long position = Long.parseLong(scalar("SELECT coalesce(max(id),0) FROM changes"));
            connection.setAutoCommit(true);
            return position;
        } catch (SQLException | RuntimeException | Error failure) { rollback(failure); throw failure; }
    }

    private static byte[] canonical(CaptureGroup group, int member) {
        BlockChange c = group.changes().get(member);
        try {
            var bytes = new ByteArrayOutputStream(); var out = new DataOutputStream(bytes);
            out.writeInt(1); out.writeLong(group.sequence()); out.writeLong(group.occurredAt()); out.writeInt(group.tick());
            out.writeInt(member); out.writeInt(group.changes().size());
            for (String text : List.of(c.world().toString(), c.worldName(), c.actor().toString(), c.actorName(), c.action(), c.before(), c.after(), c.evidence())) {
                byte[] value = text.getBytes(StandardCharsets.UTF_8); out.writeInt(value.length); out.write(value);
            }
            out.writeInt(c.x()); out.writeInt(c.y()); out.writeInt(c.z()); out.writeInt(c.dataVersion());
            if (group.outcome() != null) { out.writeUTF(group.outcome().operation().toString()); out.writeInt(group.outcome().step()); }
            return bytes.toByteArray();
        } catch (IOException impossible) { throw new AssertionError(impossible); }
    }
    private void rollback(Throwable original) {
        // Enabling auto-commit can commit an active transaction. Never do that after a failed rollback,
        // and roll back unchecked/native failures as well as JDBC exceptions before resetting the mode.
        try { connection.rollback(); connection.setAutoCommit(true); }
        catch (SQLException | RuntimeException | Error failure) { original.addSuppressed(failure); }
    }
    private String scalar(String sql) throws SQLException {
        try (var statement = connection.createStatement(); var result = statement.executeQuery(sql)) {
            if (!result.next()) throw new SQLException("Missing SQLite setting/result"); return result.getString(1);
        }
    }
    private void execute(String sql) throws SQLException { try (var statement = connection.createStatement()) { statement.execute(sql); } }
    private void checkOwner() { if (Thread.currentThread() != owner) throw new IllegalStateException("SQLite accessed outside its owning worker"); }
    @Override public io.github.d3vx3n0.chronicle.recovery.RecoveryRepository recovery() {
        checkOwner(); return new io.github.d3vx3n0.chronicle.recovery.RecoveryRepository(connection, session, transactionProbe);
    }
    @Override public <T> T recoveryWork(io.github.d3vx3n0.chronicle.storage.HistoryWriter.RecoveryWork<T> work) throws SQLException {
        checkOwner();
        long deadline = System.nanoTime() + 2_000_000_000L;
        var instructions = new java.util.concurrent.atomic.AtomicInteger();
        progress(() -> instructions.incrementAndGet() > 10_000 || System.nanoTime() >= deadline);
        try { return work.run(recovery()); }
        finally { progress(() -> false); }
    }
    private void progress(java.util.function.BooleanSupplier abort) throws SQLException {
        if (driverLoader == null) IsolatedDriver.progress(connection, abort);
        else try { driverLoader.loadClass(IsolatedDriver.class.getName()).getMethod("progress", Connection.class, java.util.function.BooleanSupplier.class).invoke(null, connection, abort); }
        catch (ReflectiveOperationException error) { throw new SQLException("Recovery SQL budget unavailable", error); }
    }
    @Override public void close() throws SQLException, IOException {
        checkOwner();
        try { connection.close(); } finally {
            try { lock.close(); } finally { try { lockChannel.close(); } finally { if (driverLoader != null) driverLoader.close(); } }
        }
    }
}
