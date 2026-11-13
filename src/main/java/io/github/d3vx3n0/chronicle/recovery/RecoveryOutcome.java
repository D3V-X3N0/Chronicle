package io.github.d3vx3n0.chronicle.recovery;

import java.util.UUID;

/** Copied actual evidence, committed atomically with its single ordered synthetic history row. */
public record RecoveryOutcome(UUID operation, int step, String before, String after) {
    public RecoveryOutcome {
        if (operation == null || step < 0 || !InertStates.supports(before) || !InertStates.supports(after) || before.equals(after))
            throw new IllegalArgumentException("Invalid applied outcome");
    }
}
