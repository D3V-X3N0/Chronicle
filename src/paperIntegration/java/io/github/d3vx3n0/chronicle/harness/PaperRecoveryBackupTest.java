package io.github.d3vx3n0.chronicle.harness;

import java.net.InetAddress;
import java.net.ServerSocket;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.concurrent.TimeUnit;

/** Stopped-server world and database copies are deliberately restored from different points. */
final class PaperRecoveryBackupTest {
    private PaperRecoveryBackupTest() {}
    static void run(Path root, Path jar, Path artifact, Path fixture, Path javaExecutable) throws Exception {
        Path run = Files.createTempDirectory(root, "recovery-backups-");
        Path plugins = Files.createDirectories(run.resolve("plugins"));
        Files.copy(artifact, plugins.resolve("chronicle.jar")); Files.copy(fixture, plugins.resolve("fixture.jar"));
        Path backups = Files.createDirectories(run.resolve("backups"));
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
        Path db = plugins.resolve("Chronicle/history.db");
        String rollback = null;
        String selection = "chronicle rollback preview time:1h world:world origin:0,0 radius:0 action:block-place player:ChronicleTest";
        for (int phase = 0; phase < 4; phase++) {
            if (phase == 2) copyTree(backups.resolve("stone-world"), run.resolve("world"));
            if (phase == 3) {
                copyTree(backups.resolve("air-world"), run.resolve("world"));
                require(!Files.exists(db.resolveSibling("history.db-wal")), "offline DB destination has no uncheckpointed WAL");
                Files.copy(backups.resolve("before.db"), db, StandardCopyOption.REPLACE_EXISTING);
            }
            Path log = run.resolve(phase == 0 ? "console.log" : "restart-" + phase + ".log");
            Process process = PaperRecoveryCrashTest.start(run, log, javaExecutable, jar);
            try {
                PaperPacketTest.await(log, process, "Done (", 180);
                try (var client = PaperRecoveryCrashTest.join(port)) {
                    if (phase == 0) {
                        PaperPacketTest.response(process, log, "chroniclefixture prepare creative-place", "PACKET_PREPARED creative-place");
                        Thread.sleep(250); client.place(); Thread.sleep(300); PaperRecoveryTest.drained(process, log);
                    } else if (phase == 1) {
                        rollback = PaperRecoveryTest.preview(process, log, selection, 1);
                        String done = PaperPacketTest.response(process, log, "chronicle rollback confirm " + rollback, "Recovery: DONE");
                        require(done.contains("applied=1; skipped=0"), "rollback before separate backups");
                    } else {
                        String preview = PaperRecoveryTest.preview(process, log, phase == 2 ? "chronicle restore preview " + rollback : selection, 0);
                        String done = PaperPacketTest.response(process, log, "chronicle " + (phase == 2 ? "restore" : "rollback") + " confirm " + preview, "Recovery: DONE");
                        require(done.contains("applied=0; skipped=1"), "independently restored world/database cannot authorize stale effects");
                    }
                    String expected = phase == 0 || phase == 2 ? "stone" : "air";
                    PaperPacketTest.response(process, log, "chroniclefixture recovery-world " + expected, "RECOVERY_WORLD " + expected);
                }
                PaperPacketTest.command(process, "stop");
                require(process.waitFor(30, TimeUnit.SECONDS) && process.exitValue() == 0, "server exited before any copy");
            } finally { if (process.isAlive()) { process.destroyForcibly(); process.waitFor(10, TimeUnit.SECONDS); } }
            if (phase == 0) {
                require(!Files.exists(db.resolveSibling("history.db-wal")), "offline source has checkpointed WAL");
                Files.copy(db, backups.resolve("before.db")); copyTree(run.resolve("world"), backups.resolve("stone-world"));
            } else if (phase == 1) copyTree(run.resolve("world"), backups.resolve("air-world"));
        }
        System.out.println("CHRONICLE_RECOVERY_BACKUP_PASS " + run);
    }
    private static void copyTree(Path from, Path to) throws Exception {
        // Both roots are fixed children of this fresh disposable run. No deletion, live copy or production paths.
        try (var files = Files.walk(from)) {
            for (Path path : files.toList()) {
                Path destination = to.resolve(from.relativize(path));
                if (Files.isDirectory(path)) Files.createDirectories(destination);
                else Files.copy(path, destination, StandardCopyOption.REPLACE_EXISTING);
            }
        }
    }
    private static void require(boolean okay, String message) { if (!okay) throw new AssertionError(message); }
}
