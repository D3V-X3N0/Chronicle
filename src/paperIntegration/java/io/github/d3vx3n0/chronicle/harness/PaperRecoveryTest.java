package io.github.d3vx3n0.chronicle.harness;

import java.nio.file.Files;
import java.net.InetAddress;
import java.nio.file.Path;
import java.util.Properties;
import java.util.regex.Pattern;
import java.util.concurrent.TimeUnit;

/** Real Paper application qualification. All worlds are newly created, loopback-only and disposable. */
public final class PaperRecoveryTest {
    private PaperRecoveryTest() {}
    public static void main(String[] args) throws Exception {
        if (args.length != 5 || !args[4].equals("true")) throw new IllegalArgumentException("Requires -PacceptMinecraftEula=true; no server started");
        var pin = new Properties();
        try (var input = Files.newInputStream(Path.of(args[0]))) { pin.load(input); }
        Path root = Path.of(args[1]).toAbsolutePath(); Files.createDirectories(root);
        Path jar = root.resolve("paper-26.3-248.jar"); PaperSmokeTest.downloadVerified(pin, jar);
        Path run = Files.createTempDirectory(root, "recovery-");
        Path plugins = Files.createDirectories(run.resolve("plugins"));
        Files.copy(Path.of(args[2]), plugins.resolve("chronicle.jar"));
        Files.copy(Path.of(args[3]), plugins.resolve("fixture.jar"));
        Files.writeString(run.resolve("eula.txt"), "eula=true\n");
        Files.writeString(run.resolve("server.properties"), """
                server-ip=127.0.0.1
                server-port=0
                online-mode=true
                enable-rcon=false
                enable-query=false
                level-type=minecraft:flat
                generate-structures=false
                view-distance=2
                simulation-distance=2
                pause-when-empty-seconds=-1
                """);
        Path output = run.resolve("console.log");
        Path java = Path.of(System.getProperty("java.home"), "bin", System.getProperty("os.name").startsWith("Windows") ? "java.exe" : "java");
        var process = new ProcessBuilder(java.toString(), "-Xms256M", "-Xmx1G", "-Dfile.encoding=UTF-8", "-Dstdout.encoding=UTF-8",
                "-Dchronicle.fixture.recovery=true", "-jar", jar.toString(), "--nogui")
                .directory(run.toFile()).redirectErrorStream(true).redirectOutput(output.toFile()).start();
        try {
            long deadline = System.nanoTime() + TimeUnit.MINUTES.toNanos(4);
            while (process.isAlive() && System.nanoTime() < deadline) process.waitFor(250, TimeUnit.MILLISECONDS);
            if (process.isAlive() || process.exitValue() != 0 || !PaperSmokeTest.readConsole(output).contains("CHRONICLE_RECOVERY_PASS")
                    || PaperSmokeTest.readConsole(output).contains("CHRONICLE_RECOVERY_FAIL"))
                throw new IllegalStateException("Recovery qualification failed: " + output);
            System.out.println("CHRONICLE_RECOVERY_PASS " + output);
        } finally {
            if (process.isAlive()) { process.destroyForcibly(); process.waitFor(10, TimeUnit.SECONDS); }
        }
        operations(root, jar, Path.of(args[2]), Path.of(args[3]), java);
        PaperRecoveryCrashTest.run(root, jar, Path.of(args[2]), Path.of(args[3]), java);
        PaperRecoveryBackupTest.run(root, jar, Path.of(args[2]), Path.of(args[3]), java);
    }
    private static void operations(Path root, Path jar, Path artifact, Path fixture, Path java) throws Exception {
        Path run = Files.createTempDirectory(root, "recovery-packets-");
        Path plugins = Files.createDirectories(run.resolve("plugins"));
        Files.copy(artifact, plugins.resolve("chronicle.jar")); Files.copy(fixture, plugins.resolve("fixture.jar"));
        Files.writeString(run.resolve("eula.txt"), "eula=true\n");
        int port;
        try (var socket = new java.net.ServerSocket(0, 1, InetAddress.getByName("127.0.0.1"))) { port = socket.getLocalPort(); }
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
        var process = new ProcessBuilder(java.toString(), "-Xms256M", "-Xmx1G", "-Dfile.encoding=UTF-8", "-Dstdout.encoding=UTF-8",
                "-Dchronicle.fixture.packets=true", "-jar", jar.toString(), "--nogui")
                .directory(run.toFile()).redirectErrorStream(true).redirectOutput(log.toFile()).start();
        String rollback;
        try {
            PaperPacketTest.await(log, process, "Done (", 180);
            try (var client = new PacketClient(port)) {
                long until = System.nanoTime() + TimeUnit.SECONDS.toNanos(20);
                while (!client.joined() && System.nanoTime() < until) Thread.sleep(25);
                require(client.joined(), "joined");
                PaperPacketTest.response(process, log, "chroniclefixture prepare creative-place", "PACKET_PREPARED creative-place");
                Thread.sleep(250); client.place(); Thread.sleep(300);
                PaperPacketTest.response(process, log, "chroniclefixture recovery-world stone", "RECOVERY_WORLD stone");
                drained(process, log);
                String scope = "preview time:1h world:world origin:0,0 radius:0 action:block-place player:ChronicleTest";
                client.clearMessages();
                PaperPacketTest.response(process, log, "chroniclefixture recovery-player denied rollback " + scope, "RECOVERY_PLAYER denied");
                PaperPacketTest.awaitClient(client, "You need chronicle.rollback");
                require(!client.received("Preview "), "denied preview");
                client.clearMessages();
                PaperPacketTest.response(process, log, "chroniclefixture recovery-player allowed rollback " + scope, "RECOVERY_PLAYER allowed");
                PaperPacketTest.awaitClient(client, "Preview ");
                var playerPreview = Pattern.compile("Preview ([0-9a-f-]+)").matcher(client.messageDiagnostics());
                require(playerPreview.find(), "player owns actual frozen preview");
                PaperPacketTest.response(process, log, "chroniclefixture recovery-crash revoke-intent", "RECOVERY_CRASH_ARMED revoke-intent");
                PaperPacketTest.response(process, log, "chroniclefixture recovery-player allowed rollback confirm " + playerPreview.group(1), "RECOVERY_PLAYER allowed");
                PaperPacketTest.await(log, process, "RECOVERY_PERMISSION_REVOKED after-intent", 10);
                String permissionStatus = "";
                until = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
                while (System.nanoTime() < until) {
                    permissionStatus = PaperPacketTest.response(process, log, "chronicle status", "Recovery:");
                    if (permissionStatus.contains("Recovery: DONE")) break;
                    Thread.sleep(25);
                }
                require(permissionStatus.contains("applied=0; skipped=1"), "permission loss after durable intent prevents mutation");
                PaperPacketTest.response(process, log, "chroniclefixture recovery-world stone", "RECOVERY_WORLD stone");
                rollback = preview(process, log, "chronicle rollback " + scope, 1);
                String done = PaperPacketTest.response(process, log, "chronicle rollback confirm " + rollback, "Recovery: DONE");
                require(done.contains("applied=1; skipped=0"), "rollback complete: " + done);
                PaperPacketTest.response(process, log, "chroniclefixture recovery-world air", "RECOVERY_WORLD air");
                done = PaperPacketTest.response(process, log, "chronicle rollback confirm " + rollback, "Recovery: DONE");
                require(done.contains("applied=1"), "duplicate confirmation idempotent");
                String restore = preview(process, log, "chronicle restore preview " + rollback, 1);
                done = PaperPacketTest.response(process, log, "chronicle restore confirm " + restore, "Recovery: DONE");
                require(done.contains("applied=1; skipped=0"), "restoration complete: " + done);
                PaperPacketTest.response(process, log, "chroniclefixture recovery-world stone", "RECOVERY_WORLD stone");
                PaperPacketTest.response(process, log, "chronicle restore preview " + rollback, "No verified, applied, unrestored steps");
                client.repeat(); Thread.sleep(350); drained(process, log);
                String paused = preview(process, log, "chronicle rollback " + scope, 1);
                PaperPacketTest.response(process, log, "chroniclefixture recovery-pause-confirm " + paused, "RECOVERY_PAUSE_REQUESTED");
                until = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
                String pausedStatus = "";
                while (System.nanoTime() < until) {
                    pausedStatus = PaperPacketTest.response(process, log, "chronicle status", "Recovery:");
                    if (pausedStatus.contains("Recovery: PAUSED")) break;
                    Thread.sleep(25);
                }
                require(pausedStatus.contains("Recovery: PAUSED") && pausedStatus.contains("applied=0"), "explicit pause before mutation");
                String continued = preview(process, log, "chronicle rollback continue " + paused, 1);
                PaperPacketTest.response(process, log, "chronicle rollback confirm " + continued, "Recovery: DONE");
                PaperPacketTest.response(process, log, "chroniclefixture recovery-world air", "RECOVERY_WORLD air");
                String continuedRestore = preview(process, log, "chronicle restore preview " + continued, 1);
                PaperPacketTest.response(process, log, "chronicle restore confirm " + continuedRestore, "Recovery: DONE");
                PaperPacketTest.response(process, log, "chroniclefixture recovery-world stone", "RECOVERY_WORLD stone");
                // A fresh pair creates recoverable player history after the earlier restore audit.
                client.repeat(); Thread.sleep(350); drained(process, log);
                String newer = preview(process, log, "chronicle rollback preview time:1h world:world origin:0,0 radius:0 action:block-place,block-break player:ChronicleTest", 2);
                client.repeat(); Thread.sleep(350); drained(process, log);
                done = PaperPacketTest.response(process, log, "chronicle rollback confirm " + newer, "Recovery: DONE");
                require(done.contains("applied=0"), "identical-state intervening player edits refused");
                PaperPacketTest.response(process, log, "chroniclefixture recovery-world stone", "RECOVERY_WORLD stone");
                client.clearMessages();
                PaperPacketTest.response(process, log, "chroniclefixture recovery-player revoke rollback " + scope, "RECOVERY_PLAYER revoke");
                Thread.sleep(500); require(!client.received("Preview "), "post-admission permission revocation suppresses preview");
                rollback = preview(process, log, "chronicle rollback " + scope, 1);
                done = PaperPacketTest.response(process, log, "chronicle rollback confirm " + rollback, "Recovery: DONE");
                require(done.contains("applied=1"), "retained partial rollback");
                PaperPacketTest.response(process, log, "chroniclefixture recovery-world air", "RECOVERY_WORLD air");
            }
            PaperPacketTest.command(process, "stop");
            require(process.waitFor(30, TimeUnit.SECONDS) && process.exitValue() == 0, "clean stop");
            try (var loader = new java.net.URLClassLoader(new java.net.URL[] {artifact.toUri().toURL()}, ClassLoader.getPlatformClassLoader())) {
                var driver = (java.sql.Driver) loader.loadClass("org.sqlite.JDBC").getConstructor().newInstance();
                try (var db = driver.connect("jdbc:sqlite:" + plugins.resolve("Chronicle/history.db"), new Properties()); var statement = db.createStatement()) {
                    try (var rows = statement.executeQuery("SELECT count(*) FROM changes WHERE operation_id IS NOT NULL")) { require(rows.next() && rows.getInt(1) == 5, "exactly five operation audit rows"); }
                    try (var rows = statement.executeQuery("SELECT count(*) FROM recovery_steps WHERE status='APPLIED'")) { require(rows.next() && rows.getInt(1) == 5, "only verified steps applied"); }
                }
            }
            System.out.println("CHRONICLE_RECOVERY_COMMAND_PASS " + log);
        } finally { if (process.isAlive()) { process.destroyForcibly(); process.waitFor(10, TimeUnit.SECONDS); } }
        Path restartLog = run.resolve("restart.log");
        var restarted = new ProcessBuilder(java.toString(), "-Xms256M", "-Xmx1G", "-Dfile.encoding=UTF-8", "-Dstdout.encoding=UTF-8",
                "-Dchronicle.fixture.packets=true", "-jar", jar.toString(), "--nogui")
                .directory(run.toFile()).redirectErrorStream(true).redirectOutput(restartLog.toFile()).start();
        try {
            PaperPacketTest.await(restartLog, restarted, "Done (", 180);
            try (var client = new PacketClient(port)) {
                long until = System.nanoTime() + TimeUnit.SECONDS.toNanos(20);
                while (!client.joined() && System.nanoTime() < until) Thread.sleep(25);
                require(client.joined(), "restart joined");
                PaperPacketTest.response(restarted, restartLog, "chroniclefixture recovery-world air", "RECOVERY_WORLD air");
                String restore = preview(restarted, restartLog, "chronicle restore preview " + rollback, 1);
                String done = PaperPacketTest.response(restarted, restartLog, "chronicle restore confirm " + restore, "Recovery: DONE");
                require(done.contains("applied=1; skipped=0"), "only verified retained step restored after restart");
                PaperPacketTest.response(restarted, restartLog, "chroniclefixture recovery-world stone", "RECOVERY_WORLD stone");
            }
            PaperPacketTest.command(restarted, "stop"); require(restarted.waitFor(30, TimeUnit.SECONDS), "restart stopped");
            System.out.println("CHRONICLE_RECOVERY_RETAINED_PASS " + restartLog);
        } finally { if (restarted.isAlive()) { restarted.destroyForcibly(); restarted.waitFor(10, TimeUnit.SECONDS); } }
    }
    static String preview(Process process, Path log, String command, int eligible) throws Exception {
        String result = PaperPacketTest.response(process, log, command, "No economy conservation.");
        var match = java.util.regex.Pattern.compile("Preview ([0-9a-f-]+) kind=[A-Z]+ eligible=(\\d+)").matcher(result);
        require(match.find() && Integer.parseInt(match.group(2)) == eligible, "preview expected eligible=" + eligible + ": " + result);
        return match.group(1);
    }
    static void drained(Process process, Path log) throws Exception {
        long until = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
        while (System.nanoTime() < until) {
            String result = PaperPacketTest.response(process, log, "chronicle status", "Recovery:");
            if (result.contains("Writer pending=0/") && result.contains("Reconciliation pending=0")) return;
            Thread.sleep(25);
        }
        throw new AssertionError("capture did not drain");
    }
    private static void require(boolean okay, String message) { if (!okay) throw new AssertionError(message); }
}
