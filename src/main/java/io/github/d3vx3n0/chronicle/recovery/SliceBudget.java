package io.github.d3vx3n0.chronicle.recovery;

import java.util.function.LongSupplier;

/** A call cannot be preempted. Overruns are measured and the next call is deferred. */
public final class SliceBudget {
    private final LongSupplier clock;
    private final long start, nanos;
    private final int limit;
    private int steps;
    public SliceBudget(int limit, long nanos, LongSupplier clock) {
        if (limit < 1 || nanos < 1) throw new IllegalArgumentException("Invalid budget");
        this.limit = limit; this.nanos = nanos; this.clock = clock; start = clock.getAsLong();
    }
    public boolean take() {
        if (steps >= limit || clock.getAsLong() - start >= nanos) return false;
        steps++; return true;
    }
    public long elapsed() { return clock.getAsLong() - start; }
    public int steps() { return steps; }
}
