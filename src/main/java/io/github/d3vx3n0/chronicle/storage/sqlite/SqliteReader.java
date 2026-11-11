package io.github.d3vx3n0.chronicle.storage.sqlite;

import io.github.d3vx3n0.chronicle.query.HistoryRow;
import io.github.d3vx3n0.chronicle.query.LookupFilter;
import io.github.d3vx3n0.chronicle.query.LookupInput;
import io.github.d3vx3n0.chronicle.query.LookupPage;
import java.io.IOException;
import java.net.URLClassLoader;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.function.BooleanSupplier;

/** One read-only connection per worker, never shared with the writer or server thread. */
public final class SqliteReader implements AutoCloseable {
    private final Thread owner = Thread.currentThread();
    private final Connection connection;
    private final URLClassLoader loader;
    private BooleanSupplier cancelled = () -> false;
    private long deadline;
    private int progressCalls;
    private boolean querying;
    private int maxProgressCalls = 10_000; // At most about ten million VM instructions per request.

    public SqliteReader(Path path) throws SQLException, IOException {
        var source = SqliteReader.class.getProtectionDomain().getCodeSource().getLocation();
        URLClassLoader isolated = null;
        Connection opened = null;
        try {
            String url = "jdbc:sqlite:" + path.toAbsolutePath().toUri().toASCIIString() + "?mode=ro";
            if (source.getPath().endsWith(".jar")) {
                isolated = new URLClassLoader(new java.net.URL[] {source}, ClassLoader.getPlatformClassLoader());
                opened = (Connection) isolated.loadClass(IsolatedDriver.class.getName()).getMethod("connect", String.class).invoke(null, url);
            } else opened = new org.sqlite.JDBC().connect(url, new java.util.Properties());
            connection = opened; loader = isolated;
            try (var statement = connection.createStatement()) {
                statement.execute("PRAGMA busy_timeout=100"); statement.execute("PRAGMA query_only=ON");
                statement.execute("PRAGMA cache_size=-2048"); statement.execute("PRAGMA temp_store=FILE");
                try (var result = statement.executeQuery("PRAGMA user_version")) {
                    if (!result.next() || result.getInt(1) != SqliteStore.SCHEMA_VERSION) throw new SQLException("History schema unavailable");
                }
            }
            BooleanSupplier abort = () -> querying && (++progressCalls > maxProgressCalls || System.nanoTime() >= deadline
                    || cancelled.getAsBoolean() || Thread.currentThread().isInterrupted());
            if (loader == null) IsolatedDriver.progress(connection, abort);
            else loader.loadClass(IsolatedDriver.class.getName()).getMethod("progress", Connection.class, BooleanSupplier.class).invoke(null, connection, abort);
        } catch (ReflectiveOperationException | SQLException | RuntimeException | LinkageError failure) {
            if (opened != null) try { opened.close(); } catch (SQLException suppressed) { failure.addSuppressed(suppressed); }
            if (isolated != null) try { isolated.close(); } catch (IOException suppressed) { failure.addSuppressed(suppressed); }
            if (failure instanceof SQLException sql) throw sql;
            throw new IOException("Read-only SQLite initialization failed", failure);
        }
    }

    public LookupPage first(LookupInput input, int limit, BooleanSupplier cancellation, long deadlineNanos) throws SQLException {
        begin(cancellation, deadlineNanos);
        try {
            long boundary;
            try (var statement = connection.createStatement(); var result = statement.executeQuery("SELECT coalesce(max(id),0) FROM changes")) {
                boundary = result.getLong(1);
            }
            var actors = new ArrayList<UUID>();
            for (String player : input.players()) {
                UUID id = resolve(player, true);
                if (!actors.contains(id)) actors.add(id);
            }
            UUID world = input.world() == null ? null : resolve(input.world(), false);
            var filter = new LookupFilter(actors, world, input.from(), input.to(), input.actions(), input.area());
            return read(filter, boundary, Long.MAX_VALUE, limit);
        } finally { end(); }
    }

    public LookupPage next(LookupFilter filter, long boundary, long before, int limit, BooleanSupplier cancellation, long deadlineNanos) throws SQLException {
        begin(cancellation, deadlineNanos);
        try { return read(filter, boundary, before, limit); } finally { end(); }
    }

    private UUID resolve(String text, boolean actor) throws SQLException {
        boolean uuid = text.matches("[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}");
        String sql = uuid ? "SELECT substr(uuid,1,64) FROM " + (actor ? "actors" : "worlds") + " WHERE uuid=? LIMIT 2"
                : actor ? "SELECT substr(actor_uuid,1,64) FROM actor_names WHERE name=? COLLATE NOCASE LIMIT 2"
                : "SELECT substr(uuid,1,64) FROM worlds WHERE last_name=? COLLATE NOCASE LIMIT 2";
        try (var statement = connection.prepareStatement(sql)) {
            statement.setString(1, uuid ? UUID.fromString(text).toString() : text);
            try (var result = statement.executeQuery()) {
                if (!result.next()) throw new IllegalArgumentException("Unknown stored " + (actor ? "player" : "world") + ": " + text);
                String identity = result.getString(1);
                if (!HistoryRow.validIdentity(identity)) throw new IllegalArgumentException("Unsupported stored identity; check database integrity");
                UUID id = UUID.fromString(identity);
                if (result.next()) throw new IllegalArgumentException("Ambiguous name: " + text + "; use a UUID");
                return id;
            }
        }
    }

    public record Sql(String text, List<Object> parameters) {
        public Sql { parameters = List.copyOf(parameters); }
    }
    /** Exposed as a concrete query plan for reproducible EXPLAIN measurements, never accepts SQL fragments. */
    public static Sql select(LookupFilter filter, long boundary, long before, int limit) {
        if (limit < 1 || limit > 50 || boundary < 0 || before < 1) throw new IllegalArgumentException("Invalid page bounds");
        var sql = new StringBuilder("""
                SELECT id,session_id,capture_sequence,member_index,occurred_at,substr(world_uuid,1,64),x,y,z,substr(actor_uuid,1,64),
                substr(actor_name,1,64),substr(action,1,64),substr(before_data,1,256),substr(after_data,1,256),codec_version,
                data_version,substr(evidence,1,64),substr(actor_kind,1,32),substr(cause_kind,1,32),substr(attribution_quality,1,32)
                FROM changes WHERE id<=? AND id<? AND occurred_at>=? AND occurred_at<?
                """);
        var params = new ArrayList<Object>(List.of(boundary, before, filter.from(), filter.to()));
        if (filter.world() != null) { sql.append(" AND world_uuid=?"); params.add(filter.world().toString()); }
        if (!filter.actors().isEmpty()) {
            sql.append(" AND actor_uuid IN (").append(String.join(",", java.util.Collections.nCopies(filter.actors().size(), "?"))).append(')');
            filter.actors().forEach(id -> params.add(id.toString()));
        }
        sql.append(" AND action IN (").append(String.join(",", java.util.Collections.nCopies(filter.actions().size(), "?"))).append(')');
        filter.actions().stream().sorted().forEach(params::add);
        var area = filter.area();
        if (area != null) {
            if (area.y() != null) {
                sql.append(" AND x=? AND y=? AND z=?"); params.add(area.x()); params.add(area.y()); params.add(area.z());
            } else {
                sql.append(" AND x BETWEEN ? AND ? AND z BETWEEN ? AND ? AND ((x-?)*(x-?)+(z-?)*(z-?))<=?");
                params.add((long) area.x() - area.radius()); params.add((long) area.x() + area.radius());
                params.add((long) area.z() - area.radius()); params.add((long) area.z() + area.radius());
                params.add(area.x()); params.add(area.x()); params.add(area.z()); params.add(area.z());
                params.add((long) area.radius() * area.radius());
            }
        }
        sql.append(" ORDER BY id DESC LIMIT ?"); params.add(limit + 1);
        return new Sql(sql.toString(), params);
    }

    private LookupPage read(LookupFilter filter, long boundary, long before, int limit) throws SQLException {
        var query = select(filter, boundary, before, limit);
        var rows = new ArrayList<HistoryRow>();
        try (var statement = connection.prepareStatement(query.text())) {
            for (int i = 0; i < query.parameters().size(); i++) statement.setObject(i + 1, query.parameters().get(i));
            try (var result = statement.executeQuery()) {
                while (result.next()) rows.add(new HistoryRow(result.getLong(1), result.getLong(2), result.getLong(3), result.getInt(4),
                        result.getLong(5), result.getString(6), result.getInt(7), result.getInt(8), result.getInt(9), result.getString(10),
                        result.getString(11), result.getString(12), result.getString(13), result.getString(14), result.getInt(15), result.getInt(16),
                        result.getString(17), result.getString(18), result.getString(19), result.getString(20)));
            }
        }
        checkDeadline();
        boolean more = rows.size() > limit;
        if (more) rows.removeLast();
        return new LookupPage(filter, boundary, rows, more);
    }
    private void begin(BooleanSupplier cancellation, long until) throws SQLException {
        checkOwner(); cancelled = cancellation; deadline = until; progressCalls = 0; checkDeadline();
        connection.setAutoCommit(false); querying = true;
    }
    private void checkDeadline() throws SQLException {
        if (cancelled.getAsBoolean() || System.nanoTime() >= deadline || Thread.currentThread().isInterrupted())
            throw new SQLException("Lookup cancelled or deadline exceeded");
    }
    private void end() throws SQLException {
        // Disable the progress interrupt during transaction cleanup.
        querying = false; cancelled = () -> false; deadline = Long.MAX_VALUE; progressCalls = 0;
        connection.rollback(); connection.setAutoCommit(true);
    }
    private void checkOwner() { if (Thread.currentThread() != owner) throw new IllegalStateException("Reader accessed outside owning worker"); }
    @Override public void close() throws SQLException, IOException {
        checkOwner(); try { connection.close(); } finally { if (loader != null) loader.close(); }
    }
}
