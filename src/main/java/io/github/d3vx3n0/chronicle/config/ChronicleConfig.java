package io.github.d3vx3n0.chronicle.config;

import java.util.Map;
import java.util.Set;
import io.github.d3vx3n0.chronicle.storage.StorageSettings;

/** Validated bootstrap settings; independent of Paper and its coercing config getters. */
public record ChronicleConfig(boolean showRuntimeDetails, StorageSettings storage, int pageSize) {
    public ChronicleConfig(boolean showRuntimeDetails) { this(showRuntimeDetails, StorageSettings.defaults(), 10); }
    public ChronicleConfig(boolean showRuntimeDetails, StorageSettings storage) { this(showRuntimeDetails, storage, 10); }
    public ChronicleConfig {
        if (pageSize < 1 || pageSize > 50) throw new IllegalArgumentException("lookup.page-size must be 1..50");
    }
    private static final Set<String> KEYS =
            Set.of("config-version", "diagnostics", "diagnostics.show-runtime-details", "lookup", "lookup.page-size", "storage",
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
                integer(values, "shutdown-millis", defaults.shutdownMillis()), integer(values, "reconciliation-records", defaults.reconciliationRecords())),
                pageSize(values));
    }
    public ChronicleConfig runtimeReload(ChronicleConfig candidate) {
        if (!storage.equals(candidate.storage)) throw new IllegalArgumentException("Storage and reconciliation settings require restart; no settings changed");
        return candidate;
    }
    private static int pageSize(Map<String, Object> values) {
        if (values.containsKey("lookup") && !values.containsKey("lookup.page-size"))
            throw new IllegalArgumentException("lookup must be a section containing page-size");
        Object value = values.getOrDefault("lookup.page-size", 10);
        if (!(value instanceof Integer size)) throw new IllegalArgumentException("lookup.page-size must be a YAML integer");
        return size;
    }
    private static int integer(Map<String, Object> values, String key, int defaultValue) {
        String path = "storage." + key;
        if (!values.containsKey(path)) return defaultValue; // Existing bootstrap configs keep documented defaults.
        if (!(values.get(path) instanceof Integer value)) throw new IllegalArgumentException(path + " must be a YAML integer");
        return value;
    }
}
