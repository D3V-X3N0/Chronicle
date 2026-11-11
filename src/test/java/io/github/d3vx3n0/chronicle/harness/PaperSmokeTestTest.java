package io.github.d3vx3n0.chronicle.harness;

import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class PaperSmokeTestTest {
    @TempDir
    Path directory;

    @Test
    void partialOrNativeEncodedConsoleOutputCannotAbortTheServerHarness() throws Exception {
        Path log = directory.resolve("console.log");
        byte[] prefix = "CHRONICLE_FIXTURE_PASS ".getBytes(java.nio.charset.StandardCharsets.UTF_8);
        byte[] bytes = java.util.Arrays.copyOf(prefix, prefix.length + 2);
        bytes[prefix.length] = (byte) 0x97; // Windows-1252 em dash, invalid UTF-8.
        bytes[prefix.length + 1] = (byte) 0xe2; // A partially written UTF-8 character.
        Files.write(log, bytes);
        assertTrue(PaperSmokeTest.readConsole(log).contains("CHRONICLE_FIXTURE_PASS"));
        assertTrue(java.util.Arrays.equals(bytes, Files.readAllBytes(log)), "Diagnostics must remain untouched");
    }
}
