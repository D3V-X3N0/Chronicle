package io.github.d3vx3n0.chronicle;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.jar.JarFile;
import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.Test;

class ArtifactTest {
    @Test
    void distributableHasExpandedDescriptorAndNoServerOrFixtureClasses() throws Exception {
        try (var jar = new JarFile(System.getProperty("chronicle.artifact"))) {
            var descriptor = new YamlConfiguration();
            try (var reader = new InputStreamReader(jar.getInputStream(jar.getJarEntry("plugin.yml")), StandardCharsets.UTF_8)) {
                descriptor.load(reader);
            }
            String version = descriptor.getString("version");
            assertNotNull(version);
            assertFalse(version.contains("${"));
            assertEquals(jar.getManifest().getMainAttributes().getValue("Implementation-Version"), version);
            assertEquals("26.3", descriptor.getString("api-version"));
            for (String permission : java.util.List.of("status", "inspect", "lookup", "reload"))
                assertEquals(false, descriptor.get("permissions.chronicle." + permission + ".default"));
            assertFalse(descriptor.contains("commands.chronicle.permission"),
                    "Executor must enforce player permissions without blocking the trusted console in dispatch");
            assertFalse(descriptor.getBoolean("folia-supported"));
            assertNotNull(jar.getJarEntry("META-INF/LICENSE"));
            assertNotNull(jar.getJarEntry("META-INF/THIRD_PARTY.md"));
            assertNotNull(jar.getJarEntry("config.yml"));
            String entryPoint = descriptor.getString("main");
            assertNotNull(entryPoint);
            byte[] bytecode = jar.getInputStream(jar.getJarEntry(entryPoint.replace('.', '/') + ".class")).readAllBytes();
            assertEquals(69, ((bytecode[6] & 0xff) << 8) | (bytecode[7] & 0xff), "Java 25 bytecode required");
            assertTrue(jar.stream().noneMatch(entry -> entry.getName().startsWith("org/bukkit/")
                    || entry.getName().contains("/fixture/") || entry.getName().contains("/harness/")));
        }
    }
}
