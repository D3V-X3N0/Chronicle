package io.github.d3vx3n0.chronicle.storage;

/** Hard ceilings also bound malicious/mistaken configuration. Payload accounting includes fixed overhead. */
public record StorageSettings(int maxRecords, long maxBytes, int batchRecords, long batchBytes,
        int flushMillis, int busyMillis, int shutdownMillis, int reconciliationRecords) {
    public static StorageSettings defaults() { return new StorageSettings(10_000, 32L << 20, 500, 1L << 20, 100, 1000, 10_000, 1024); }
    public StorageSettings {
        if (maxRecords < 1 || maxRecords > 100_000 || maxBytes < 1024 || maxBytes > 256L << 20
                || batchRecords < 1 || batchRecords > maxRecords || batchBytes < 512 || batchBytes > maxBytes
                || flushMillis < 1 || flushMillis > 1000 || busyMillis < 1 || busyMillis > 5000
                || shutdownMillis < 1 || shutdownMillis > 60_000 || reconciliationRecords < 1 || reconciliationRecords > 10_000) {
            throw new IllegalArgumentException("Invalid storage bounds; see config.yml development limits.");
        }
    }
}
