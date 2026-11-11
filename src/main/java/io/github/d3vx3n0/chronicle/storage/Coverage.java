package io.github.d3vx3n0.chronicle.storage;

/** Fixed-cardinality reasons: failures never allocate one control item per dropped event. */
public enum Coverage {
    STARTUP, PREVIOUS_UNCLEAN, UNSUPPORTED, AMBIGUOUS, RECONCILIATION_FULL, QUEUE_FULL, STORAGE_FAILURE, SHUTDOWN_TIMEOUT
}
