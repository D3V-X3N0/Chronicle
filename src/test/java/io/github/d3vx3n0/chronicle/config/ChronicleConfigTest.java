package io.github.d3vx3n0.chronicle.config;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.io.StringReader;
import java.util.HashMap;
import java.util.Map;
import org.bukkit.configuration.InvalidConfigurationException;
import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class ChronicleConfigTest {
    @ParameterizedTest
    @ValueSource(booleans = {true, false})
    void preservesExplicitRuntimeVisibility(boolean visible) {
        assertEquals(visible, ChronicleConfig.from(validConfig(visible)).showRuntimeDetails());
    }

    @ParameterizedTest
    @ValueSource(strings = {"1", "2", "true", ""})
    void refusesCoercedOrUnsupportedSchema(String version) {
        var values = validConfig(true);
        values.put("config-version", version);
        assertThrows(IllegalArgumentException.class, () -> ChronicleConfig.from(values));
    }

    @Test
    void refusesFutureSchema() {
        var values = validConfig(true);
        values.put("config-version", 2);
        assertThrows(IllegalArgumentException.class, () -> ChronicleConfig.from(values));
    }

    @Test
    void refusesMissingSettingInsteadOfSilentlyChangingBehaviour() {
        assertThrows(IllegalArgumentException.class, () -> ChronicleConfig.from(Map.of("config-version", 1)));
    }

    @Test
    void refusesQuotedBoolean() {
        var values = validConfig(true);
        values.put("diagnostics.show-runtime-details", "false");
        assertThrows(IllegalArgumentException.class, () -> ChronicleConfig.from(values));
    }

    @Test
    void catchesMisspelledKeys() {
        var values = validConfig(true);
        values.put("diagnostics.show-runtime-detail", false);
        assertThrows(IllegalArgumentException.class, () -> ChronicleConfig.from(values));
    }

    @Test
    void shippedYamlLoadsWithoutAServer() throws Exception {
        var resource = getClass().getResourceAsStream("/config.yml");
        try (var reader = new java.io.InputStreamReader(java.util.Objects.requireNonNull(resource),
                java.nio.charset.StandardCharsets.UTF_8)) {
            var yaml = new YamlConfiguration();
            yaml.load(reader);
            assertEquals(new ChronicleConfig(true), ChronicleConfig.from(yaml.getValues(true)));
        }
    }

    @Test
    void malformedYamlIsAnError() {
        var yaml = new YamlConfiguration();
        assertThrows(InvalidConfigurationException.class, () -> yaml.load(new StringReader("diagnostics: [")));
    }

    private static Map<String, Object> validConfig(boolean visible) {
        return new HashMap<>(Map.of("config-version", 1, "diagnostics.show-runtime-details", visible));
    }

    @ParameterizedTest @ValueSource(ints = {0, -1, 100001})
    void rejectsOutOfRangeStorageLimits(int limit) {
        var values = validConfig(true); values.put("storage.max-records", limit);
        assertThrows(IllegalArgumentException.class, () -> ChronicleConfig.from(values));
    }
    @Test void rejectsCoercedStorageAndBatchLargerThanQueue() {
        var values = validConfig(true); values.put("storage.max-records", "1000");
        assertThrows(IllegalArgumentException.class, () -> ChronicleConfig.from(values));
        values.put("storage.max-records", 10); values.put("storage.batch-records", 11);
        assertThrows(IllegalArgumentException.class, () -> ChronicleConfig.from(values));
    }
    @Test void reloadValidatesAllSettingsBeforeApplyingPresentationOnly() {
        var current = new ChronicleConfig(true);
        var values = validConfig(false); values.put("lookup.page-size", 2);
        var changed = current.runtimeReload(ChronicleConfig.from(values));
        assertEquals(false, changed.showRuntimeDetails()); assertEquals(2, changed.pageSize());
        values.put("storage.max-records", 20_000);
        assertThrows(IllegalArgumentException.class, () -> current.runtimeReload(ChronicleConfig.from(values)));
        assertEquals(new ChronicleConfig(true), current);
        for (Object invalid : java.util.List.of(0, 51, "10")) {
            values.put("lookup.page-size", invalid); assertThrows(IllegalArgumentException.class, () -> ChronicleConfig.from(values));
        }
        values.remove("lookup.page-size"); values.put("lookup", "invalid scalar");
        assertThrows(IllegalArgumentException.class, () -> ChronicleConfig.from(values));
    }
}
