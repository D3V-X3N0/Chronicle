package io.github.d3vx3n0.chronicle.harness;

import java.net.InetAddress;
import java.net.ServerSocket;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Properties;
import java.util.concurrent.TimeUnit;

/** Kill actual disposable Paper processes with separately controlled persisted world expectations. */
final class PaperRecoveryCrashTest {
    private PaperRecoveryCrashTest() {}
    static void run(Path root, Path jar, Path artifact, Path fixture, Path javaExecutable) throws Exception {
        for (String boundary : new String[] {"before-intent", "after-intent", "after-mutation", "after-outcome", "after-save", "before-summary", "clean-disable", "restore-mutation"})
            scenario(root, jar, artifact, fixture, javaExecutable, boundary);
    }
    private static void scenario(Path root, Path jar, Path artifact, Path fixture, Path javaExecutable, String boundary) throws Exception {
        Path run = Files.createTempDirectory(root, "recovery-crash-" + boundary + "-");
        Path plugins = Files.createDirectories(run.resolve("plugins"));
        Files.copy(artifact, plugins.resolve("chronicle.jar")); Files.copy(fixture, plugins.resolve("fixture.jar"));
        Files.writeString(run.resolve("eula.txt"), "eula=true\n");
        int port;
        try (var socket = new ServerSocket(0, 1, InetAddress.getLoopbackAddress())) { port = socket.getLocalPort(); }
        Files.writeString(run.resolve("server.properties"), """
                server-ip=127.0.0.1
                online-mode=false
                white-list=false
                enforce-whitelist=false
                enforce-secure-profile=false
                network-compression-threshold=-1
                enable-rcon=false
                enable-query=false
                spawn-protection=0
                view-distance=2
                simulation-distance=2
                pause-when-empty-seconds=-1
                level-type=minecraft:flat
                generate-structures=false
                """ + "server-port=" + port + "\n");
        Path log = run.resolve("console.log");
        Process process = start(run, log, javaExecutable, jar);
        String operation;
        boolean restoring = boundary.equals("restore-mutation");
        try {
            PaperPacketTest.await(log, process, "Done (", 180);
            try (var client = join(port)) {
                PaperPacketTest.response(process, log, "chroniclefixture prepare creative-place", "PACKET_PREPARED creative-place");
                Thread.sleep(250); client.place(); Thread.sleep(300); PaperRecoveryTest.drained(process, log);
                operation = PaperRecoveryTest.preview(process, log,
                        "chronicle rollback preview time:1h world:world origin:0,0 radius:0 action:block-place player:ChronicleTest", 1);
                if (restoring) {
                    PaperPacketTest.response(process, log, "chronicle rollback confirm " + operation, "Recovery: DONE");
                    operation = PaperRecoveryTest.preview(process, log, "chronicle restore preview " + operation, 1);
                }
                // Persist the independent starting world, then forbid autosave while mutating.
                PaperPacketTest.response(process, log, "save-all flush", "Saved the game");
                PaperPacketTest.response(process, log, "save-off", "Automatic saving is now disabled");
                String hook = restoring ? "after-mutation" : boundary;
                PaperPacketTest.response(process, log, "chroniclefixture recovery-crash " + hook, "RECOVERY_CRASH_ARMED " + hook);
                PaperPacketTest.command(process, "chronicle " + (restoring ? "restore" : "rollback") + " confirm " + operation);
                PaperPacketTest.await(log, process, "RECOVERY_CRASH_BOUNDARY " + hook, 20);
                process.destroyForcibly(); require(process.waitFor(10, TimeUnit.SECONDS), "child killed");
            }
        } finally { if (process.isAlive()) { process.destroyForcibly(); process.waitFor(10, TimeUnit.SECONDS); } }
        Path restart = run.resolve("restart.log");
        process = start(run, restart, javaExecutable, jar);
        try {
            PaperPacketTest.await(restart, process, "Done (", 180);
            try (var client = join(port)) {
                require(client.joined(), "restart player joined");
                String expected = boundary.equals("after-save") || restoring ? "air" : "stone";
                PaperPacketTest.response(process, restart, "chroniclefixture recovery-world " + expected, "RECOVERY_WORLD " + expected);
                String reviewed = PaperPacketTest.response(process, restart, "chronicle " + (restoring ? "restore" : "rollback") + " review " + operation,
                        "no mutation resumed.");
                String outcome = boundary.equals("before-intent") ? "PLANNED" : boundary.equals("after-intent") || boundary.equals("after-mutation") || restoring ? "UNKNOWN" : "APPLIED";
                require(reviewed.contains(" " + outcome + " live="), "exact restart step outcome: " + reviewed);
                require(reviewed.contains(boundary.equals("after-save") ? "live=DESIRED" : "live=EXPECTED"), "world-save evidence: " + reviewed);
                if (boundary.equals("clean-disable")) {
                    // No unclean-session warning can mask this test: successful outcome was never saved to the world.
                    String restore = PaperRecoveryTest.preview(process, restart, "chronicle restore preview " + operation, 0);
                    String done = PaperPacketTest.response(process, restart, "chronicle restore confirm " + restore, "Recovery: DONE");
                    require(done.contains("applied=0; skipped=1"), "retained effect revalidated despite clean logging shutdown");
                }
                PaperPacketTest.response(process, restart, "chroniclefixture recovery-world " + expected, "RECOVERY_WORLD " + expected);
                if (boundary.equals("after-intent")) {
                    PaperPacketTest.response(process, restart, "chroniclefixture recovery-diverge dirt", "RECOVERY_DIVERGED dirt");
                    reviewed = PaperPacketTest.response(process, restart, "chronicle rollback review " + operation, "no mutation resumed.");
                    require(reviewed.contains("live=CONFLICT"), "unrelated persisted/live state is explicit conflict");
                    PaperPacketTest.response(process, restart, "chroniclefixture recovery-world dirt", "RECOVERY_WORLD dirt");
                }
            }
            PaperPacketTest.command(process, "stop"); require(process.waitFor(30, TimeUnit.SECONDS), "restart stopped");
            try (var loader = new java.net.URLClassLoader(new java.net.URL[] {artifact.toUri().toURL()}, ClassLoader.getPlatformClassLoader())) {
                var driver = (java.sql.Driver) loader.loadClass("org.sqlite.JDBC").getConstructor().newInstance();
                try (var db = driver.connect("jdbc:sqlite:" + plugins.resolve("Chronicle/history.db"), new Properties()); var statement = db.createStatement()) {
                    int audits = boundary.equals("before-intent") || boundary.equals("after-intent") || boundary.equals("after-mutation") ? 0 : 1;
                    try (var rows = statement.executeQuery("SELECT count(*) FROM changes WHERE operation_id IS NOT NULL")) {
                        require(rows.next() && rows.getInt(1) == audits, "no automatic uncertain mutation/audit on restart");
                    }
                    try (var rows = statement.executeQuery("SELECT clean FROM sessions WHERE id=1")) {
                        require(rows.next() && rows.getBoolean(1) == boundary.equals("clean-disable"), "logging cleanliness independent of world save");
                    }
                }
            }
            System.out.println("CHRONICLE_RECOVERY_CRASH_PASS " + boundary + " " + restart);
        } finally { if (process.isAlive()) { process.destroyForcibly(); process.waitFor(10, TimeUnit.SECONDS); } }
    }
    static Process start(Path run, Path log, Path javaExecutable, Path jar) throws Exception {
        return new ProcessBuilder(javaExecutable.toString(), "-Xms256M", "-Xmx1G", "-Dfile.encoding=UTF-8", "-Dstdout.encoding=UTF-8",
                "-Dchronicle.fixture.packets=true", "-jar", jar.toString(), "--nogui")
                .directory(run.toFile()).redirectErrorStream(true).redirectOutput(log.toFile()).start();
    }
    static PacketClient join(int port) throws Exception {
        var client = new PacketClient(port);
        long until = System.nanoTime() + TimeUnit.SECONDS.toNanos(20);
        try {
            while (!client.joined() && System.nanoTime() < until) Thread.sleep(25);
            require(client.joined(), "actual packet player joined"); return client;
        } catch (Exception | AssertionError error) { client.close(); throw error; }
    }
    private static void require(boolean okay, String message) { if (!okay) throw new AssertionError(message); }
}
