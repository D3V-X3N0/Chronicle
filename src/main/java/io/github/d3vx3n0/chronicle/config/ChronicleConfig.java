package io.github.d3vx3n0.chronicle.config;

import java.util.Map;
import java.util.Set;

/** Validated bootstrap settings; independent of Paper and its coercing config getters. */
public record ChronicleConfig(boolean showRuntimeDetails) {
    private static final Set<String> KEYS =
            Set.of("config-version", "diagnostics", "diagnostics.show-runtime-details");

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
        return new ChronicleConfig(showRuntime);
    }
}
