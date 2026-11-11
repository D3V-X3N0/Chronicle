package io.github.d3vx3n0.chronicle.storage;

/** Cumulative per-session aggregate; upserts replace counts, so retries cannot double-count. Scope is global. */
public record Incident(Coverage reason, long count, long firstTime, long lastTime, long firstSequence, long lastSequence) {
    public Incident add(long time, long sequence) {
        return new Incident(reason, count == Long.MAX_VALUE ? count : count + 1, firstTime, time,
                Math.min(firstSequence, sequence), Math.max(lastSequence, sequence));
    }
}
