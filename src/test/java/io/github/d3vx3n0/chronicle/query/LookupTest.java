package io.github.d3vx3n0.chronicle.query;

import static org.junit.jupiter.api.Assertions.*;

import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.Test;

class LookupTest {
    private static final UUID WORLD = new UUID(0, 1), OWNER = new UUID(0, 2);
    @Test void freezesTimeAndOriginAndCombinesLists() {
        var value = LookupInput.parse(new String[] {"time:2h", "radius:5", "player:Alex,Sam", "action:block-break"},
                10_000_000, new LookupInput.Origin(WORLD, -17, -1));
        assertEquals(2_800_000, value.from()); assertEquals(10_000_000, value.to());
        assertEquals(new LookupFilter.Area(-17, null, -1, 5), value.area());
        assertEquals(WORLD.toString(), value.world()); assertEquals(List.of("Alex", "Sam"), value.players());
        assertEquals(Set.of("BREAK"), value.actions());
    }
    @Test void rejectsMalformedOversizedAndAmbiguousSyntax() {
        for (String[] args : List.of(new String[] {"time:0h"}, new String[] {"time:1h", "time:2h"}, new String[] {"radius:5"},
                new String[] {"radius:1001", "origin:0,0", "world:w"}, new String[] {"origin:0,0"}, new String[] {"action:explode"},
                new String[] {"player:"}, new String[] {"player:a,b,c,d,e,f,g,h,i"}, new String[] {"from:2026-01-01T00:00:00Z"},
                new String[] {"from:2026-01-01T00:00:00+00:00", "to:2026-02-01T00:00:00Z"},
                new String[] {"from:2026-02-01T00:00:00Z", "to:2026-01-01T00:00:00Z"}, new String[] {"world:" + "a".repeat(1024)})) {
            assertThrows(IllegalArgumentException.class, () -> LookupInput.parse(args, 10_000_000, null));
        }
    }
    @Test void acceptsUtcHalfOpenRangeAndConsoleOrigin() {
        var input = LookupInput.parse(new String[] {"from:2026-01-01T00:00:00Z", "to:2026-01-02T00:00:00Z", "world:w", "radius:0", "origin:-16,-17"}, 1, null);
        assertEquals(86_400_000, input.to() - input.from()); assertEquals(-17, input.area().z());
    }
    @Test void cursorsAreOwnedExpiringBoundedAndKeepOriginalBoundary() {
        var time = new AtomicLong(); var cursors = new CursorRegistry(time::get);
        var filter = new LookupFilter(List.of(), WORLD, 0, 100, Set.of("BREAK"), null);
        var row = new HistoryRow(6, 1, 6, 0, 10, WORLD.toString(), 0, 80, 0, OWNER.toString(), "test", "BREAK",
                "minecraft:stone", "minecraft:air", 1, 5023, "LATER_SAMPLE", "PLAYER", "PLAYER_ACTION", "DIRECT");
        var page = new LookupPage(filter, 9, List.of(row), true);
        var cursor = cursors.create(OWNER, "chronicle.lookup", page, 1, 120);
        assertEquals(9, cursors.get(OWNER, cursor.token().toString()).continuation().boundary());
        assertEquals(6, cursor.continuation().before());
        assertThrows(IllegalArgumentException.class, () -> cursors.get(WORLD, cursor.token().toString()));
        time.set(120); assertThrows(IllegalArgumentException.class, () -> cursors.get(OWNER, cursor.token().toString()));
        for (int i = 0; i < 128; i++) cursors.create(new UUID(1, i), "chronicle.inspect", page, 10, 240);
        assertThrows(IllegalArgumentException.class, () -> cursors.create(OWNER, "chronicle.lookup", page, 10, 240));
        time.set(240); assertNotNull(cursors.create(OWNER, "chronicle.lookup", page, 10, 360));
    }
    @Test void unexpectedSnapshotAndAttributionAreNeverUnderstoodByGuessing() {
        var row = new HistoryRow(1, 1, 1, 0, 0, "w", 0, 0, 0, "actor", "name", "BREAK", "unknown", "minecraft:air",
                99, 5023, "LATER_SAMPLE", "UNKNOWN", "UNKNOWN", "UNKNOWN");
        assertFalse(row.understoodSnapshot()); assertTrue(row.attribution().startsWith("UNKNOWN"));
    }
}
