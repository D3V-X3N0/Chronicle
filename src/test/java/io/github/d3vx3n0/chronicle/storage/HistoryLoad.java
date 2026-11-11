package io.github.d3vx3n0.chronicle.storage;

import io.github.d3vx3n0.chronicle.model.BlockChange;
import io.github.d3vx3n0.chronicle.model.CaptureGroup;
import io.github.d3vx3n0.chronicle.query.*;
import io.github.d3vx3n0.chronicle.storage.sqlite.SqliteReader;
import io.github.d3vx3n0.chronicle.storage.sqlite.SqliteStore;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.DriverManager;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

/** Opt-in reproducible synthetic storage/query measurements; never a Paper throughput claim. */
public final class HistoryLoad {
    private static final UUID WORLD = new UUID(0, 1), OTHER = new UUID(0, 2), ACTOR = new UUID(1, 7);
    private HistoryLoad() {}
    private static CaptureGroup group(long sequence, long input) {
        boolean hot = input % 4 == 0;
        UUID world = input % 5 == 0 ? OTHER : WORLD;
        UUID actor = new UUID(1, input % 100);
        int x = hot ? -17 : (int) (input * 31 % 2048) - 1024, z = hot ? -1 : (int) (input * 97 % 2048) - 1024;
        String action = input % 2 == 0 ? "PLACE" : "BREAK";
        return new CaptureGroup(sequence, 1_000_000 + input % 10_000, 1, List.of(new BlockChange(world, world.equals(WORLD) ? "world" : "other",
                x, (int) (input % 384) - 64, z, actor, "Actor" + input % 100, action,
                action.equals("PLACE") ? "minecraft:air" : "minecraft:stone", action.equals("PLACE") ? "minecraft:stone" : "minecraft:air", "LATER_SAMPLE", 5023)));
    }
    public static void main(String[] args) throws Exception {
        Path root = Path.of(args[0]); Files.createDirectories(root);
        Path run = Files.createTempDirectory(root, "history-");
        var lines = new ArrayList<String>();
        lines.add("# OS=" + System.getProperty("os.name") + " " + System.getProperty("os.version") + "; Java=" + Runtime.version()
                + "; maxHeap=" + Runtime.getRuntime().maxMemory() + "; processors=" + Runtime.getRuntime().availableProcessors()
                + "; GC=" + java.lang.management.ManagementFactory.getGarbageCollectorMXBeans().stream().map(java.lang.management.MemoryManagerMXBean::getName).toList());
        lines.add("# Generator=v1 modular sequence (no PRNG); WAL/FULL; warmup=10; rounds=3; samples=30; page=10; read deadline=2s; VM budget=10000000");
        lines.add("size\tvariant\tround\tscope\tp50_ms\tp95_ms\tp99_ms\tmax_ms");
        for (String number : args[1].split(",")) {
            int size = Integer.parseInt(number);
            if (size < 1000 || size > 1_000_000) throw new IllegalArgumentException("History size must be 1000..1000000");
            Path db = run.resolve("history-" + size + ".db");
            long seedStart = System.nanoTime();
            try (var store = new SqliteStore(db, 1000)) {
                store.startSession(1, "M2 deterministic load");
                for (int first = 1; first <= size; first += 500) {
                    var batch = new ArrayList<CaptureGroup>();
                    for (int i = first; i < Math.min(first + 500, size + 1); i++) batch.add(group(i, i));
                    store.write(batch, List.of(), false, 0);
                }
                store.write(List.of(), List.of(), true, 1);
            }
            lines.add("# seed size=" + size + " seconds=" + (System.nanoTime() - seedStart) / 1e9);
            for (String variant : List.of("position-only", "ordered", "time", "hybrid")) {
                Path copy = run.resolve("history-" + size + "-" + variant + ".db");
                Files.copy(db, copy); // All seed connections closed: complete checkpointed disposable baseline.
                measure(copy, size, variant, lines);
                Files.write(run.resolve("measurements.tsv"), lines);
            }
            Files.write(run.resolve("measurements.tsv"), lines);
            System.out.println("PASS history size=" + size + " independent query oracle and bounded concurrent writes");
        }
        Files.write(run.resolve("measurements.tsv"), lines);
        System.out.println("HISTORY_LOAD_PASS " + run.resolve("measurements.tsv"));
    }
    private static void measure(Path db, int size, String variant, List<String> lines) throws Exception {
        try (var connection = DriverManager.getConnection("jdbc:sqlite:" + db); var statement = connection.createStatement()) {
            for (String index : List.of("changes_actor_id", "changes_world_id", "candidate_actor_time", "candidate_world_time", "candidate_time")) statement.execute("DROP INDEX IF EXISTS " + index);
            if (variant.equals("ordered") || variant.equals("hybrid")) {
                statement.execute("CREATE INDEX changes_actor_id ON changes(actor_uuid,id)"); statement.execute("CREATE INDEX changes_world_id ON changes(world_uuid,id)");
                if (variant.equals("hybrid")) statement.execute("CREATE INDEX candidate_time ON changes(occurred_at,id)");
            } else if (variant.equals("time")) {
                statement.execute("CREATE INDEX candidate_actor_time ON changes(actor_uuid,occurred_at,id)");
                statement.execute("CREATE INDEX candidate_world_time ON changes(world_uuid,occurred_at,id)");
                statement.execute("CREATE INDEX candidate_time ON changes(occurred_at,id)");
            }
            statement.execute("ANALYZE");
        }
        var filters = List.of(
                new LookupFilter(List.of(ACTOR), null, 0, 2_000_000, Set.of("PLACE", "BREAK"), null),
                new LookupFilter(List.of(), WORLD, 0, 2_000_000, Set.of("PLACE", "BREAK"), null),
                new LookupFilter(List.of(), WORLD, 0, 2_000_000, Set.of("PLACE", "BREAK"), new LookupFilter.Area(-17, null, -1, 20)),
                new LookupFilter(List.of(), null, 1_005_000, 1_005_010, Set.of("PLACE", "BREAK"), null));
        String[] scopes = {"actor", "world", "circle", "narrow-time"};
        var expected = new ArrayList<List<Long>>();
        for (int scope = 0; scope < 4; scope++) {
            var ids = new ArrayList<Long>();
            for (long i = size; i >= 1 && ids.size() < 10; i--) {
                boolean hot = i % 4 == 0;
                int x = hot ? -17 : (int) (i * 31 % 2048) - 1024, z = hot ? -1 : (int) (i * 97 % 2048) - 1024;
                if (switch (scope) {
                    case 0 -> i % 100 == 7;
                    case 1 -> i % 5 != 0;
                    case 2 -> i % 5 != 0 && Math.hypot(x + 17, z + 1) <= 20;
                    default -> i % 10_000 >= 5000 && i % 10_000 < 5010;
                }) ids.add(i);
            }
            expected.add(ids);
            var sql = SqliteReader.select(filters.get(scope), size, Long.MAX_VALUE, 10);
            try (var connection = DriverManager.getConnection("jdbc:sqlite:" + db); var plan = connection.prepareStatement("EXPLAIN QUERY PLAN " + sql.text())) {
                for (int i = 0; i < sql.parameters().size(); i++) plan.setObject(i + 1, sql.parameters().get(i));
                var details = new ArrayList<String>();
                try (var rows = plan.executeQuery()) { while (rows.next()) details.add(rows.getString(4)); }
                lines.add("# plan " + size + " " + variant + " " + scopes[scope] + " " + details);
            }
        }
        var limits = new StorageSettings(2000, 4_194_304, 500, 1_048_576, 100, 1000, 10_000, 1024);
        var failure = new AtomicReference<Throwable>();
        var writer = new HistoryWriter(limits, () -> new SqliteStore(db, 1000), "M2 mixed load", ignored -> {});
        long startup = System.nanoTime();
        long readyDeadline = startup + 120_000_000_000L;
        while (writer.status().state() == HistoryWriter.State.STARTING && System.nanoTime() < readyDeadline) Thread.sleep(5);
        if (writer.status().state() != HistoryWriter.State.READY) {
            writer.stop(); throw new AssertionError("writer unavailable: " + writer.status());
        }
        lines.add("# startup " + size + " " + variant + " seconds=" + (System.nanoTime() - startup) / 1e9);
        var stop = new AtomicBoolean(); long[] counters = new long[5];
        long start = System.nanoTime();
        Thread producer = Thread.ofPlatform().name("chronicle-load-producer").start(() -> {
            try {
                long sequence = 0;
                while (!stop.get()) {
                    writer.offer(group(++sequence, size + sequence)); counters[0]++;
                    var status = writer.status();
                    if (status.pendingRecords() > limits.maxRecords() || status.pendingBytes() > limits.maxBytes()) throw new AssertionError("admission bounds");
                    counters[1] = Math.max(counters[1], status.pendingRecords()); counters[2] = Math.max(counters[2], status.pendingBytes()); counters[3] = Math.max(counters[3], status.oldestMillis());
                    // Bursts exercise rejection while the queries run; pause once per 500 attempts.
                    if (sequence % 500 == 0) java.util.concurrent.locks.LockSupport.parkNanos(1_000_000);
                }
            } catch (Throwable error) { failure.set(error); }
        });
        try (var reader = new SqliteReader(db)) {
            for (int scope = 0; scope < 4; scope++) {
                for (int warm = 0; warm < 10; warm++) query(reader, filters.get(scope), size, expected.get(scope));
                for (int round = 1; round <= 3; round++) {
                    var timings = new ArrayList<Double>();
                    for (int sample = 0; sample < 30; sample++) {
                        long began = System.nanoTime(); query(reader, filters.get(scope), size, expected.get(scope)); timings.add((System.nanoTime() - began) / 1e6);
                    }
                    timings.sort(Double::compareTo);
                    lines.add(size + "\t" + variant + "\t" + round + "\t" + scopes[scope] + "\t" + timings.get(14) + "\t" + timings.get(28) + "\t" + timings.get(29) + "\t" + timings.get(29));
                }
            }
        } finally {
            stop.set(true); producer.join(5000);
            if (producer.isAlive() || !writer.stop()) throw new AssertionError("mixed workload did not drain cleanly");
        }
        if (failure.get() != null) throw new AssertionError("load producer failed", failure.get());
        var status = writer.status();
        if (status.accepted() != status.committed() || status.pendingRecords() != 0 || counters[0] != status.accepted() + status.rejected())
            throw new AssertionError("unaccounted mixed-workload events: " + status);
        lines.add("# writes " + size + " " + variant + " seconds=" + (System.nanoTime() - start) / 1e9 + " offered=" + counters[0]
                + " committed=" + status.committed() + " rejected=" + status.rejected() + " maxPending=" + counters[1] + " maxBytes=" + counters[2]
                + " maxOldestMs=" + counters[3] + " DB=" + Files.size(db) + " WAL=" + (Files.exists(Path.of(db + "-wal")) ? Files.size(Path.of(db + "-wal")) : 0));
        try (var connection = DriverManager.getConnection("jdbc:sqlite:" + db); var statement = connection.prepareStatement("SELECT count(*) FROM changes WHERE session_id=?")) {
            statement.setLong(1, status.session());
            try (var rows = statement.executeQuery()) { if (!rows.next() || rows.getLong(1) != status.committed()) throw new AssertionError("durable count differs from acknowledgements"); }
        }
    }
    private static void query(SqliteReader reader, LookupFilter filter, int size, List<Long> expected) throws Exception {
        var page = reader.next(filter, size, Long.MAX_VALUE, 10, () -> false, System.nanoTime() + QueryService.DEADLINE_NANOS);
        if (!page.rows().stream().map(HistoryRow::id).toList().equals(expected)) throw new AssertionError("load query differs from independent oracle");
    }
}
