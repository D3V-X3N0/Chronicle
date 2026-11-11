package io.github.d3vx3n0.chronicle.fixture;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/** Bounded, thread-confined diagnostic attempts. No entry represents a committed world transition. */
public final class ObservationLog {
    public record Position(UUID world, int x, int y, int z) {}

    public record Entry(long sequence, String action, UUID actor, Position position, int eventTick,
            boolean cancelled, boolean canBuild, String before, String during,
            String later, int sampledTick, String outcome) {}

    private final int capacity;
    private final Map<Long, Entry> entries = new LinkedHashMap<>();
    private long sequence;
    private long rejected;

    public ObservationLog(int capacity) {
        if (capacity < 1 || capacity > 4096) {
            throw new IllegalArgumentException("Observation capacity must be between 1 and 4096.");
        }
        this.capacity = capacity;
    }

    public long observe(String action, UUID actor, Position position, int tick, boolean cancelled,
            boolean canBuild, String before, String during) {
        if (entries.size() >= capacity) {
            rejected++;
            return -1;
        }
        long id = ++sequence;
        entries.put(id, new Entry(id, action, actor, position, tick, cancelled, canBuild,
                before, during, null, -1, "PENDING_SAMPLE"));
        return id;
    }

    public void sample(long id, String state, int tick, String outcome) {
        Entry entry = entries.get(id);
        if (entry == null || !entry.outcome().equals("PENDING_SAMPLE")) {
            return;
        }
        entries.put(id, new Entry(id, entry.action(), entry.actor(), entry.position(), entry.eventTick(),
                entry.cancelled(), entry.canBuild(), entry.before(), entry.during(), state, tick, outcome));
    }

    public List<Entry> snapshot() {
        return List.copyOf(entries.values());
    }

    Entry entry(long id) {
        return entries.get(id);
    }

    public long rejected() {
        return rejected;
    }
}
