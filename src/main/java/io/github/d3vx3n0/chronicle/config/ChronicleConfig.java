package io.github.d3vx3n0.chronicle.config;

import java.util.Map;
import java.util.Set;
import io.github.d3vx3n0.chronicle.storage.StorageSettings;

/** Validated bootstrap settings; independent of Paper and its coercing config getters. */
public record ChronicleConfig(boolean showRuntimeDetails, StorageSettings storage) {
    public ChronicleConfig(boolean showRuntimeDetails) { this(showRuntimeDetails, StorageSettings.defaults()); }
    private static final Set<String> KEYS =
            Set.of("config-version", "diagnostics", "diagnostics.show-runtime-details", "storage",
                    "storage.max-records", "storage.max-bytes", "storage.batch-records", "storage.batch-bytes",
                    "storage.flush-millis", "storage.busy-millis", "storage.shutdown-millis", "storage.reconciliation-records");

    public static ChronicleConfig from(Map<String, Object> values) {
        for (String key : values.keySet()) {
            if (!KEYS.contains(key)) {
                throw new IllegalArgumentException("Unknown configuration key: " + key);
            }
        }
        if (!(values.get("config-version") instanceof Integer version) || version != 1) {
            throw new IllegalArgumentException("config-version must be the integer 1; automatic migration is unavailable.");
        }
        if (!(values.get("diagnostics.show-runtime-details") instanceof Boolean showRuntime)) {
            throw new IllegalArgumentException("diagnostics.show-runtime-details must be a YAML boolean (true or false).");
        }
        var defaults = StorageSettings.defaults();
        return new ChronicleConfig(showRuntime, new StorageSettings(
                integer(values, "max-records", defaults.maxRecords()), integer(values, "max-bytes", (int) defaults.maxBytes()),
                integer(values, "batch-records", defaults.batchRecords()), integer(values, "batch-bytes", (int) defaults.batchBytes()),
                integer(values, "flush-millis", defaults.flushMillis()), integer(values, "busy-millis", defaults.busyMillis()),
                integer(values, "shutdown-millis", defaults.shutdownMillis()), integer(values, "reconciliation-records", defaults.reconciliationRecords())));
    }
    private static int integer(Map<String, Object> values, String key, int defaultValue) {
        String path = "storage." + key;
        if (!values.containsKey(path)) return defaultValue; // Existing bootstrap configs keep documented defaults.
        if (!(values.get(path) instanceof Integer value)) throw new IllegalArgumentException(path + " must be a YAML integer");
        return value;
    }
}
