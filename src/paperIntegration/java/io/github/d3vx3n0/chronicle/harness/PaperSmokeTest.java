package io.github.d3vx3n0.chronicle.harness;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.time.Duration;
import java.util.HexFormat;
import java.util.Properties;
import java.util.concurrent.TimeUnit;

/** Opt-in subprocess harness. It only creates fresh directories beneath the supplied build directory. */
public final class PaperSmokeTest {
    private PaperSmokeTest() {}

    public static void main(String[] args) throws Exception {
        if (args.length != 5 || !args[4].equals("true")) {
            throw new IllegalArgumentException("Paper smoke tests require explicit Minecraft EULA acceptance: "
                    + "https://www.minecraft.net/eula . After accepting, pass -PacceptMinecraftEula=true. "
                    + "No server has been downloaded or started.");
        }
        var pin = new Properties();
        try (var input = Files.newInputStream(Path.of(args[0]))) {
            pin.load(input);
        }
        Path root = Path.of(args[1]).toAbsolutePath().normalize();
        Files.createDirectories(root);
        Path serverJar = root.resolve("paper-" + pin.getProperty("minecraft-version") + "-" + pin.getProperty("paper-build") + ".jar");
        downloadVerified(pin, serverJar);
        runServer(root, serverJar, Path.of(args[2]), Path.of(args[3]), false);
        runServer(root, serverJar, Path.of(args[2]), Path.of(args[3]), true);
    }

    static void downloadVerified(Properties pin, Path destination) throws Exception {
        String expected = pin.getProperty("server-sha256");
        if (Files.exists(destination)) {
            verifyChecksum(destination, expected);
            return;
        }
        Path temporary = Files.createTempFile(destination.getParent(), "paper-download-", ".part");
        try (var client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(30))
                .followRedirects(HttpClient.Redirect.NORMAL).build()) {
            var request = HttpRequest.newBuilder(URI.create(pin.getProperty("server-url")))
                    .timeout(Duration.ofMinutes(3))
                    .header("User-Agent", "Chronicle-test-harness/0.1 (https://github.com/D3V-X3N0/Chronicle)").GET().build();
            var response = client.send(request, HttpResponse.BodyHandlers.ofFile(temporary));
            if (response.statusCode() != 200) {
                throw new IOException("Paper download returned HTTP " + response.statusCode());
            }
            verifyChecksum(temporary, expected);
            Files.move(temporary, destination);
        } finally {
            Files.deleteIfExists(temporary);
        }
    }

    private static void verifyChecksum(Path path, String expected) throws Exception {
        var digest = MessageDigest.getInstance("SHA-256");
        try (var input = Files.newInputStream(path)) {
            byte[] buffer = new byte[65536];
            int count;
            while ((count = input.read(buffer)) != -1) {
                digest.update(buffer, 0, count);
            }
        }
        if (!HexFormat.of().formatHex(digest.digest()).equals(expected)) {
            throw new IOException("Paper SHA-256 mismatch; refusing to execute " + path);
        }
    }

    private static void runServer(Path root, Path serverJar, Path pluginJar, Path fixtureJar, boolean invalidConfig) throws Exception {
        Path run = Files.createTempDirectory(root, invalidConfig ? "invalid-config-" : "smoke-");
        Path plugins = Files.createDirectories(run.resolve("plugins"));
        Files.copy(pluginJar, plugins.resolve("chronicle.jar"));
        if (!invalidConfig) {
            Files.copy(fixtureJar, plugins.resolve("chronicle-fixture.jar"));
        } else {
            Files.createDirectories(plugins.resolve("Chronicle"));
            Files.writeString(plugins.resolve("Chronicle/config.yml"), "config-version: 999\ndiagnostics:\n  show-runtime-details: true\n");
        }
        // Explicit opt-in was checked before any side effects in main().
        Files.writeString(run.resolve("eula.txt"), "eula=true\n", StandardCharsets.UTF_8);
        Files.writeString(run.resolve("server.properties"), """
                server-ip=127.0.0.1
                server-port=0
                online-mode=true
                enable-rcon=false
                enable-query=false
                level-type=minecraft:flat
                generator-settings={"layers":[{"block":"minecraft:bedrock","height":1},{"block":"minecraft:dirt","height":2},{"block":"minecraft:grass_block","height":1}],"biome":"minecraft:plains"}
                generate-structures=false
                spawn-protection=0
                view-distance=2
                simulation-distance=2
                max-players=1
                pause-when-empty-seconds=-1
                motd=Chronicle disposable test server
                """, StandardCharsets.UTF_8);
        Path output = run.resolve("console.log");
        String executable = System.getProperty("os.name").startsWith("Windows") ? "java.exe" : "java";
        Path java = Path.of(System.getProperty("java.home"), "bin", executable);
        var process = new ProcessBuilder(java.toString(), "-Xms256M", "-Xmx1G", "-Dfile.encoding=UTF-8",
                "-Dstdout.encoding=UTF-8", "-Dstderr.encoding=UTF-8",
                "-Dchronicle.fixture.autorun=true", "-jar", serverJar.toString(), "--nogui")
                .directory(run.toFile()).redirectErrorStream(true).redirectOutput(output.toFile()).start();
        long deadline = System.nanoTime() + Duration.ofMinutes(4).toNanos();
        boolean stopSent = false;
        try {
            while (process.isAlive() && System.nanoTime() < deadline) {
                String log = readConsole(output);
                if (invalidConfig && !stopSent && log.contains("Done (")) {
                    process.outputWriter(StandardCharsets.UTF_8).append("stop\n").flush();
                    stopSent = true;
                }
                process.waitFor(250, TimeUnit.MILLISECONDS);
            }
            if (process.isAlive()) {
                throw new IllegalStateException("Paper test timed out. Inspect " + output);
            }
            String log = readConsole(output);
            if (process.exitValue() != 0) {
                throw new IllegalStateException("Paper exited with " + process.exitValue() + ". Inspect " + output);
            }
            if (invalidConfig) {
                if (!log.contains("config-version must be the integer 1") || log.contains("Chronicle starting SQLite")
                        || !Files.readString(plugins.resolve("Chronicle/config.yml")).contains("999")) {
                    throw new IllegalStateException("Invalid-config refusal/preservation failed. Inspect " + output);
                }
            } else if (!log.contains("CHRONICLE_FIXTURE_PASS") || log.contains("CHRONICLE_FIXTURE_FAIL")
                    || !log.contains("Chronicle stopped: clean=true")) {
                throw new IllegalStateException("Paper fixture/lifecycle assertions failed. Inspect " + output);
            }
            System.out.println("PASS " + (invalidConfig ? "invalid configuration refusal" : "packaged plugin + synthetic event fixture") + ": " + output);
        } finally {
            if (process.isAlive()) {
                process.descendants().forEach(ProcessHandle::destroyForcibly);
                process.destroyForcibly();
                process.waitFor(10, TimeUnit.SECONDS);
            }
        }
    }

    static String readConsole(Path output) throws IOException {
        // A concurrent write can end this read halfway through a multibyte character.
        // Console diagnostics may also contain native-encoding bytes on Windows.
        // Preserve the original file; ASCII readiness/result markers are unaffected.
        return Files.exists(output) ? new String(Files.readAllBytes(output), StandardCharsets.UTF_8) : "";
    }
}
