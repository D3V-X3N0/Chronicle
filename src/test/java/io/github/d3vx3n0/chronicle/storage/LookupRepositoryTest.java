package io.github.d3vx3n0.chronicle.storage;

import static org.junit.jupiter.api.Assertions.*;

import io.github.d3vx3n0.chronicle.model.BlockChange;
import io.github.d3vx3n0.chronicle.model.CaptureGroup;
import io.github.d3vx3n0.chronicle.query.*;
import io.github.d3vx3n0.chronicle.storage.sqlite.SqliteReader;
import io.github.d3vx3n0.chronicle.storage.sqlite.SqliteStore;
import java.nio.file.Path;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Random;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

@Tag("database")
class LookupRepositoryTest {
    @TempDir Path directory;
    private static final UUID WORLD = new UUID(0, 1), OTHER = new UUID(0, 2), ACTOR = new UUID(1, 1), SECOND = new UUID(1, 2);
    private record Expected(long id, long time, UUID world, UUID actor, int x, int y, int z, String action) {}
    private final List<Expected> oracle = new ArrayList<>();
    private CaptureGroup group(long sequence, UUID world, UUID actor, String name, int x, int y, int z, long time, String action) {
        oracle.add(new Expected(sequence, time, world, actor, x, y, z, action));
        return new CaptureGroup(sequence, time, 1, List.of(new BlockChange(world, world.equals(WORLD) ? "world" : "other", x, y, z, actor, name, action,
                action.equals("PLACE") ? "minecraft:air" : "minecraft:stone", action.equals("PLACE") ? "minecraft:stone" : "minecraft:air", "LATER_SAMPLE", 5023)));
    }
    private static long deadline() { return System.nanoTime() + 2_000_000_000L; }
    @Test void everyFilterMatchesIndependentSeededOracleAcrossInsertsAndRestart() throws Exception {
        Path db = directory.resolve("history.db");
        var random = new Random(718_231);
        try (var store = new SqliteStore(db, 100)) {
            store.startSession(1, "test"); var groups = new ArrayList<CaptureGroup>();
            for (int i = 1; i <= 1000; i++) groups.add(group(i, i % 3 == 0 ? OTHER : WORLD, i % 2 == 0 ? ACTOR : SECOND,
                    i % 2 == 0 ? "Alex" : "Sam", random.nextInt(61) - 30, i % 3 - 1, random.nextInt(61) - 30, 100 + i % 19,
                    i % 5 == 0 ? "BREAK" : "PLACE"));
            // Exact circle edges, outside diagonal, negative chunk boundary and all heights.
            groups.add(group(1001, WORLD, ACTOR, "Alex", -20, -64, -5, 110, "BREAK"));
            groups.add(group(1002, WORLD, ACTOR, "Alex", -13, 319, -6, 110, "BREAK"));
            groups.add(group(1003, WORLD, ACTOR, "Alex", -13, 80, -5, 110, "BREAK"));
            groups.add(group(1004, WORLD, ACTOR, "Alex", -17, 80, -1, 109, "PLACE"));
            store.write(groups, List.of(), true, 200);
        }
        var filters = new ArrayList<LookupFilter>();
        for (UUID world : new UUID[] {null, WORLD, OTHER}) for (Set<String> actions : List.of(Set.of("PLACE"), Set.of("BREAK"), Set.of("PLACE", "BREAK")))
            for (List<UUID> actors : List.of(List.<UUID>of(), List.of(ACTOR), List.of(ACTOR, SECOND)))
                filters.add(new LookupFilter(actors, world, 105, 115, actions, null));
        filters.add(new LookupFilter(List.of(), WORLD, 0, 1000, Set.of("PLACE", "BREAK"), new LookupFilter.Area(-17, null, -1, 5)));
        filters.add(new LookupFilter(List.of(), WORLD, 0, 1000, Set.of("PLACE", "BREAK"), new LookupFilter.Area(-17, 80, -1, 0)));
        try (var reader = new SqliteReader(db); var writer = new SqliteStore(db, 100)) {
            writer.startSession(201, "continued");
            for (var filter : filters) {
                List<Long> expected = oracle.stream().filter(e -> e.id <= 1004 && e.time >= filter.from() && e.time < filter.to()
                        && (filter.world() == null || e.world.equals(filter.world())) && (filter.actors().isEmpty() || filter.actors().contains(e.actor))
                        && filter.actions().contains(e.action)).filter(e -> {
                            var area = filter.area(); if (area == null) return true;
                            if (area.y() != null) return e.x == area.x() && e.y == area.y() && e.z == area.z();
                            // Independent geometric oracle; all Y included.
                            return Math.hypot(e.x - area.x(), e.z - area.z()) <= area.radius();
                        }).map(Expected::id).sorted(Comparator.reverseOrder()).toList();
                var actual = new ArrayList<Long>(); long before = Long.MAX_VALUE;
                do {
                    var page = reader.next(filter, 1004, before, 7, () -> false, deadline());
                    actual.addAll(page.rows().stream().map(HistoryRow::id).toList()); before = page.beforeNext();
                    if (oracle.size() == 1004) writer.write(List.of(group(1005, WORLD, ACTOR, "Renamed", -17, 80, -1, 110, "BREAK")), List.of(), false, 0);
                    if (!page.more()) break;
                } while (true);
                assertEquals(expected, actual, filter.describe());
            }
            var oldName = reader.first(new LookupInput(List.of("aLeX"), "world", 0, 1000, Set.of("PLACE", "BREAK"), null), 50, () -> false, deadline());
            assertEquals(List.of(ACTOR), oldName.filter().actors()); assertEquals(1005, oldName.boundary());
            var newName = reader.first(new LookupInput(List.of("Renamed"), WORLD.toString(), 0, 1000, Set.of("BREAK"), null), 1, () -> false, deadline());
            assertEquals(1005, newName.rows().getFirst().id());
            var progress = new java.util.concurrent.atomic.AtomicInteger();
            var expensive = new LookupFilter(List.of(), null, 900, 901, Set.of("BREAK"), null);
            assertThrows(SQLException.class, () -> reader.next(expensive, 1005, Long.MAX_VALUE, 10,
                    () -> progress.incrementAndGet() > 2, deadline()));
            assertTrue(progress.get() > 2, "cancellation must interrupt running SQL, not only admission");
            assertEquals(1005, reader.next(newName.filter(), 1005, Long.MAX_VALUE, 1, () -> false, deadline()).rows().getFirst().id(),
                    "transaction cleanup permits reuse after an interrupted query");
            var offThread = new java.util.concurrent.atomic.AtomicReference<Throwable>();
            var thread = new Thread(() -> { try { reader.next(expensive, 1005, Long.MAX_VALUE, 1, () -> false, deadline()); }
                catch (Throwable error) { offThread.set(error); } });
            thread.start(); thread.join(); assertInstanceOf(IllegalStateException.class, offThread.get());
        }
    }
    @Test void ambiguityUnknownIdentityCancellationAndUnsupportedVersionsAreExplicit() throws Exception {
        Path db = directory.resolve("history.db");
        try (var writer = new SqliteStore(db, 100)) {
            writer.startSession(1, "test"); writer.write(List.of(group(1, WORLD, ACTOR, "Alex", 0, 80, 0, 1, "PLACE"),
                    group(2, OTHER, SECOND, "alex", 0, 80, 0, 2, "BREAK")), List.of(), true, 3);
        }
        SqliteStoreTest.execute(db, "UPDATE worlds SET last_name='same'");
        SqliteStoreTest.execute(db, "PRAGMA ignore_check_constraints=ON");
        // Unknown data version is retained and labelled by the reader, not coerced.
        SqliteStoreTest.execute(db, "UPDATE changes SET data_version=9999,attribution_quality='UNKNOWN' WHERE id=1");
        try (var reader = new SqliteReader(db)) {
            for (var input : List.of(new LookupInput(List.of("Alex"), null, 0, 10, Set.of("PLACE"), null),
                    new LookupInput(List.of("missing' OR 1=1 --"), null, 0, 10, Set.of("PLACE"), null),
                    new LookupInput(List.of(), "same", 0, 10, Set.of("PLACE"), null),
                    new LookupInput(List.of(), UUID.randomUUID().toString(), 0, 10, Set.of("PLACE"), null)))
                assertThrows(IllegalArgumentException.class, () -> reader.first(input, 10, () -> false, deadline()));
            var filter = new LookupFilter(List.of(), null, 0, 10, Set.of("PLACE"), null);
            assertThrows(SQLException.class, () -> reader.next(filter, 2, 3, 10, () -> true, deadline()));
            assertThrows(SQLException.class, () -> reader.next(filter, 2, 3, 10, () -> false, System.nanoTime() - 1));
            var row = reader.next(filter, 2, 3, 10, () -> false, deadline()).rows().getFirst();
            assertFalse(row.understoodSnapshot()); assertTrue(row.attribution().startsWith("UNKNOWN"));
            assertEquals("minecraft:air", row.before());
        }
        assertThrows(Exception.class, () -> new SqliteReader(directory.resolve("missing.db")));
        assertFalse(java.nio.file.Files.exists(directory.resolve("missing.db")));
    }
    @Test void oversizedStoredIdentitiesAreBoundedAndNeverSilentlyParsedAsRealUuids() throws Exception {
        Path db = directory.resolve("space # caf\u00e9/history.db");
        try (var writer = new SqliteStore(db, 100)) {
            writer.startSession(1, "test"); writer.write(List.of(group(1, WORLD, ACTOR, "Alex", 0, 80, 0, 1, "PLACE")), List.of(), true, 2);
        }
        // Deliberately corrupted disposable input, not possible through normal immutable capture.
        SqliteStoreTest.execute(db, "UPDATE changes SET actor_uuid=printf('%100000s','x'),world_uuid=printf('%100000s','y')");
        SqliteStoreTest.execute(db, "UPDATE actor_names SET actor_uuid=printf('%100000s','x')");
        try (var reader = new SqliteReader(db)) {
            var row = reader.next(new LookupFilter(List.of(), null, 0, 10, Set.of("PLACE"), null), 1, 2, 10, () -> false, deadline()).rows().getFirst();
            assertEquals(64, row.actor().length()); assertEquals(64, row.world().length()); assertTrue(row.attribution().startsWith("UNKNOWN"));
            assertThrows(IllegalArgumentException.class, () -> reader.first(new LookupInput(List.of("Alex"), null, 0, 10, Set.of("PLACE"), null), 10, () -> false, deadline()));
        }
    }
}
