package io.github.d3vx3n0.chronicle.query;

import java.time.Instant;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/** Fully resolved, immutable scope. A null world means all stored worlds. */
public record LookupFilter(List<UUID> actors, UUID world, long from, long to, Set<String> actions, Area area) {
    public LookupFilter {
        actors = List.copyOf(actors); actions = Set.copyOf(actions);
        if (actors.size() > 8 || from < 0 || to <= from || actions.isEmpty()
                || !Set.of("PLACE", "BREAK").containsAll(actions) || area != null && world == null)
            throw new IllegalArgumentException("Invalid lookup scope");
    }
    public record Area(int x, Integer y, int z, int radius) {
        public Area {
            if (Math.abs((long) x) > 30_000_000 || Math.abs((long) z) > 30_000_000
                    || radius < 0 || radius > 1000 || y != null && (radius != 0 || y < -2048 || y > 2048))
                throw new IllegalArgumentException("Origin must be within +/-30000000; radius 0..1000; exact Y -2048..2048");
        }
    }
    public String describe() {
        return "UTC [" + Instant.ofEpochMilli(from) + ", " + Instant.ofEpochMilli(to) + "); world="
                + (world == null ? "all" : world) + "; actors=" + (actors.isEmpty() ? "all" : actors)
                + "; actions=" + actions.stream().sorted().toList() + (area == null ? "" : "; origin=" + area.x + "," + area.z
                + (area.y == null ? "; radius=" + area.radius : "; y=" + area.y));
    }
}
