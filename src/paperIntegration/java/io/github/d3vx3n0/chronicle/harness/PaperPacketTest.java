package io.github.d3vx3n0.chronicle.harness;

import java.net.ServerSocket;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Properties;
import java.util.concurrent.TimeUnit;
import java.util.regex.Pattern;

/** Independent packet/world oracle. No synthetic event dispatch or internal server calls. */
public final class PaperPacketTest {
    private PaperPacketTest() {}
    public static void main(String[] args) throws Exception {
        if (args.length != 5 || !args[4].equals("true")) throw new IllegalArgumentException(
                "Requires Minecraft EULA acceptance: -PacceptMinecraftEula=true; no server started.");
        var pin = new Properties();
        try (var input = Files.newInputStream(Path.of(args[0]))) { pin.load(input); }
        Path root = Path.of(args[1]).toAbsolutePath(); Files.createDirectories(root);
        Path jar = root.resolve("paper-26.3-248.jar"); PaperSmokeTest.downloadVerified(pin, jar);
        Path run = Files.createTempDirectory(root, "packets-");
        Path plugins = Files.createDirectories(run.resolve("plugins"));
        Files.copy(Path.of(args[2]), plugins.resolve("chronicle.jar"));
        Files.copy(Path.of(args[3]), plugins.resolve("fixture.jar"));
        guardJar(Path.of(args[3]), plugins.resolve("guard.jar"));
        Path config = Files.createDirectories(plugins.resolve("Chronicle")).resolve("config.yml");
        Files.writeString(config, "config-version: 1\ndiagnostics:\n  show-runtime-details: true\nlookup:\n  page-size: 2\n");
        int port;
        try (var reservation = new ServerSocket(0, 1, java.net.InetAddress.getLoopbackAddress())) { port = reservation.getLocalPort(); }
        Files.writeString(run.resolve("eula.txt"), "eula=true\n");
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
                max-players=1
                pause-when-empty-seconds=-1
                level-type=minecraft:flat
                generate-structures=false
                """ + "server-port=" + port + "\n");
        Path output = run.resolve("console.log");
        Path java = Path.of(System.getProperty("java.home"), "bin", System.getProperty("os.name").startsWith("Windows") ? "java.exe" : "java");
        var process = new ProcessBuilder(java.toString(), "-Xms256M", "-Xmx1G", "-Dfile.encoding=UTF-8",
                "-Dstdout.encoding=UTF-8", "-Dchronicle.fixture.packets=true", "-jar", jar.toString(), "--nogui")
                .directory(run.toFile()).redirectErrorStream(true).redirectOutput(output.toFile()).start();
        try {
            await(output, process, "Done (", 180);
            try (var client = new PacketClient(port)) {
                long deadline = System.nanoTime() + Duration.ofSeconds(30).toNanos();
                while (!client.joined() && System.nanoTime() < deadline) Thread.sleep(25);
                require(client.joined(), "network client joined");
                String[] cases = {"creative-place", "creative-break", "survival-place", "survival-break",
                        "cancel-place", "deny-place", "cancel-break", "replacement", "noop", "repeat"};
                for (String scenario : cases) {
                    command(process, "chroniclefixture prepare " + scenario);
                    await(output, process, "PACKET_PREPARED " + scenario, 10);
                    Thread.sleep(250); // Let the client acknowledge the fixture teleport, not a success oracle.
                    if (scenario.equals("repeat")) client.repeat();
                    else if (scenario.contains("break") || scenario.equals("noop")) {
                        client.dig(0);
                        if (scenario.equals("survival-break")) { Thread.sleep(1500); client.dig(3); }
                    } else client.place();
                    Thread.sleep(400);
                    client.check();
                    command(process, "chroniclefixture report " + scenario);
                    await(output, process, "PACKET_RESULT " + scenario, 10);
                    String log = PaperSmokeTest.readConsole(output);
                    String result = log.lines().filter(line -> line.contains("PACKET_RESULT " + scenario + " ")).findFirst().orElseThrow();
                    String expected = scenario.equals("noop") ? "count=1 final=minecraft:air"
                            : scenario.equals("repeat") ? "count=2 final=minecraft:stone"
                            : "count=1 final=minecraft:" + (scenario.endsWith("-place") && !scenario.startsWith("cancel") && !scenario.startsWith("deny")
                                    || scenario.equals("cancel-break") || scenario.equals("replacement") ? "stone" : "air");
                    require(result.contains(expected), scenario + ": expected " + expected + "; " + result);
                    var attempts = log.lines().filter(line -> line.contains("PACKET_ATTEMPT " + scenario + " ")).toList();
                    for (String attempt : attempts) require(attempt.contains("actor=" + PacketClient.ACTOR), "actual player UUID");
                    if (scenario.startsWith("cancel")) require(attempts.getFirst().contains("cancelled=true"), "protection cancellation");
                    if (scenario.equals("deny-place")) require(attempts.getFirst().contains("build=false"), "build denial");
                    if (scenario.equals("noop")) require(attempts.getFirst().contains("before=minecraft:air during=minecraft:air"), "air no-op evidence");
                    if (scenario.equals("repeat")) {
                        require(attempts.get(0).contains("BREAK") && attempts.get(1).contains("PLACE"), "repeat order");
                        var tick = Pattern.compile("tick=(\\d+)");
                        var first = tick.matcher(attempts.get(0)); var second = tick.matcher(attempts.get(1));
                        require(first.find() && second.find() && first.group(1).equals(second.group(1)), "repeat must actually share a tick");
                        require(attempts.get(1).contains("before=minecraft:air"), "next pre-state proves intermediate air");
                    }
                    System.out.println("PASS packet scenario " + scenario);
                }
                // A second plugin cancels both action types at every legitimate mutation priority.
                for (String priority : new String[] {"LOWEST", "LOW", "NORMAL", "HIGH", "HIGHEST"}) {
                    command(process, "chronicleguard " + priority); await(output, process, "GUARD_MODE " + priority, 10);
                    for (String action : new String[] {"place", "break"}) {
                        String scenario = "guard-" + priority + "-" + action;
                        command(process, "chroniclefixture prepare " + scenario); await(output, process, "PACKET_PREPARED " + scenario, 10);
                        Thread.sleep(250);
                        if (action.equals("break")) client.dig(0); else client.place();
                        Thread.sleep(250); command(process, "chroniclefixture report " + scenario);
                        await(output, process, "PACKET_RESULT " + scenario, 10);
                        String log = PaperSmokeTest.readConsole(output);
                        String result = log.lines().filter(line -> line.contains("PACKET_RESULT " + scenario + " ")).findFirst().orElseThrow();
                        require(result.contains("count=1 final=minecraft:" + (action.equals("break") ? "stone" : "air")), "second-plugin protected world: " + result);
                        require(log.lines().anyMatch(line -> line.contains("PACKET_ATTEMPT " + scenario + " ") && line.contains("cancelled=true")), "second-plugin cancellation at " + priority);
                    }
                }
                command(process, "chronicleguard OFF");
                investigate(process, output, "before-restart");
                playerPermissions(process, output, client);
                inspection(process, output, client);
                reload(process, output, config);
                command(process, "chronicle status");
                client.clearMessages(); command(process, "chroniclefixture inspect logout"); awaitClient(client, "Inspection enabled");
            }
            await(output, process, "ChronicleTest lost connection", 10);
            try (var rejoined = new PacketClient(port)) {
                long deadline = System.nanoTime() + Duration.ofSeconds(15).toNanos();
                while (!rejoined.joined() && System.nanoTime() < deadline) Thread.sleep(25);
                require(rejoined.joined(), "player rejoined after inspection logout");
                command(process, "chroniclefixture prepare logout-chest"); await(output, process, "PACKET_PREPARED logout-chest", 10);
                Thread.sleep(250); rejoined.interact(80, 0); Thread.sleep(250);
                command(process, "chroniclefixture assert logout-chest"); await(output, process, "INSPECT_UNCHANGED logout-chest", 10);
                System.out.println("PASS logout clears inspection mode; normal chest interaction restored");
            }
            command(process, "stop");
            require(process.waitFor(30, TimeUnit.SECONDS) && process.exitValue() == 0, "clean server shutdown");
            String log = PaperSmokeTest.readConsole(output);
            var worldMatch = Pattern.compile("PACKET_PREPARED creative-place actor=[^ ]+ world=([0-9a-f-]+)").matcher(log);
            require(worldMatch.find(), "independent world UUID");
            Path database = plugins.resolve("Chronicle/history.db");
            verifyHistory(database, Path.of(args[2]), worldMatch.group(1), 1);
            Path restartLog = run.resolve("restart.log");
            var restarted = new ProcessBuilder(java.toString(), "-Xms256M", "-Xmx1G", "-Dfile.encoding=UTF-8", "-Dstdout.encoding=UTF-8",
                    "-jar", jar.toString(), "--nogui").directory(run.toFile()).redirectErrorStream(true).redirectOutput(restartLog.toFile()).start();
            try {
                await(restartLog, restarted, "Done (", 120);
                command(restarted, "chronicle status"); await(restartLog, restarted, "Storage: READY", 15);
                investigate(restarted, restartLog, "after-restart");
                command(restarted, "stop");
                require(restarted.waitFor(30, TimeUnit.SECONDS) && restarted.exitValue() == 0, "restart clean shutdown");
            } finally { if (restarted.isAlive()) { restarted.destroyForcibly(); restarted.waitFor(10, TimeUnit.SECONDS); } }
            verifyHistory(database, Path.of(args[2]), worldMatch.group(1), 2);
            System.out.println("CHRONICLE_PACKET_PASS " + output);
        } finally {
            if (process.isAlive()) { process.descendants().forEach(ProcessHandle::destroyForcibly); process.destroyForcibly(); process.waitFor(10, TimeUnit.SECONDS); }
        }
    }
    private static void guardJar(Path fixture, Path destination) throws Exception {
        try (var input = new java.util.jar.JarFile(fixture.toFile()); var output = new java.util.jar.JarOutputStream(Files.newOutputStream(destination))) {
            String path = "io/github/d3vx3n0/chronicle/fixture/GuardFixture.class";
            output.putNextEntry(new java.util.jar.JarEntry(path));
            try (var bytes = input.getInputStream(input.getJarEntry(path))) { bytes.transferTo(output); }
            output.closeEntry(); output.putNextEntry(new java.util.jar.JarEntry("plugin.yml"));
            output.write(("name: ChronicleGuard\nversion: 'test'\napi-version: '26.3'\nmain: io.github.d3vx3n0.chronicle.fixture.GuardFixture\n"
                    + "commands:\n  chronicleguard:\n    description: Disposable protection fixture\n").getBytes(StandardCharsets.UTF_8)); output.closeEntry();
        }
    }
    private static void investigate(Process process, Path output, String stage) throws Exception {
        String query = "chronicle lookup player:ChronicleTest time:1d world:world radius:0 origin:0,0 action:block-break,block-place";
        var ids = new java.util.ArrayList<Long>();
        for (int page = 0; page < 3; page++) {
            String result = response(process, output, query, "Lookup page complete.");
            require(result.contains("Committed history boundary=6") && result.contains("Coverage LIMITED") && result.contains("current=[STARTUP"), "lookup boundary/coverage: " + result);
            var rows = Pattern.compile("#(\\d+) ").matcher(result);
            while (rows.find()) ids.add(Long.parseLong(rows.group(1)));
            require(result.contains("PLAYER / PLAYER_ACTION / DIRECT") && result.contains("complete qualified snapshot"), "interpreted snapshot and direct attribution");
            var next = Pattern.compile("Next: /chronicle lookup page:([0-9a-f-]+)").matcher(result);
            if (page < 2) { require(next.find(), "cursor for next page"); query = "chronicle lookup page:" + next.group(1); }
            else require(!next.find(), "exact final page");
        }
        require(ids.equals(java.util.List.of(6L, 5L, 4L, 3L, 2L, 1L)), "independent lookup IDs " + stage + ": " + ids);
        String empty = response(process, output, "chronicle lookup player:ChronicleTest time:1d world:world radius:0 origin:-17,-17", "Lookup page complete.");
        require(empty.contains("No matching committed rows") && empty.contains("does not prove"), "empty is not complete history");
        System.out.println("PASS command investigation " + stage + " IDs=" + ids);
    }
    private static void playerPermissions(Process process, Path output, PacketClient client) throws Exception {
        client.clearMessages(); command(process, "chroniclefixture lookup denied");
        awaitClient(client, "You need chronicle.lookup"); require(!client.received("Committed history"), "denied player has no history");
        client.clearMessages(); command(process, "chroniclefixture lookup revoke"); awaitClient(client, "Lookup queued");
        drain(process, output); command(process, "chroniclefixture fence revoked"); awaitClient(client, "Lookup fence revoked");
        require(!client.received("Committed history"), "revoked permission suppresses asynchronous results");
        client.clearMessages(); command(process, "chroniclefixture lookup allowed"); awaitClient(client, "Lookup page complete.");
        require(client.received("Committed history boundary=6"), "permitted actual player receives committed history");
        System.out.println("PASS real-player lookup permission and revoked-delivery checks");
    }
    private static void inspection(Process process, Path output, PacketClient client) throws Exception {
        command(process, "chroniclefixture inspect on"); awaitClient(client, "Inspection enabled");
        for (String scenario : new String[] {"inspect-stone", "inspect-break", "inspect-chest", "inspect-protected-chest", "inspect-item-chest", "inspect-offhand-chest"}) {
            command(process, "chronicleguard " + (scenario.contains("protected") ? "BLOCK" : scenario.contains("item") ? "ITEM" : "OFF"));
            command(process, "chroniclefixture prepare " + scenario); await(output, process, "PACKET_PREPARED " + scenario, 10);
            Thread.sleep(250); client.clearMessages();
            if (scenario.equals("inspect-break")) client.dig(0); else client.interact(80, scenario.contains("offhand") ? 1 : 0);
            boolean denied = scenario.contains("protected") || scenario.contains("item");
            if (denied) awaitClient(client, "Inspection denied");
            else if (!scenario.contains("offhand")) awaitClient(client, "Lookup page complete.");
            drain(process, output); command(process, "chroniclefixture fence " + scenario); awaitClient(client, "Lookup fence " + scenario);
            if (denied || scenario.contains("offhand")) require(!client.received("Committed history"), "denied/offhand inspection cannot disclose history");
            command(process, "chroniclefixture assert " + scenario); await(output, process, "INSPECT_UNCHANGED " + scenario, 10);
        }
        command(process, "chroniclefixture inspect off"); awaitClient(client, "Inspection disabled");
        System.out.println("PASS packet inspection: blocks, held items, chest, protection and offhand");
    }
    private static void reload(Process process, Path output, Path config) throws Exception {
        String base = "config-version: 1\ndiagnostics:\n  show-runtime-details: false\nlookup:\n  page-size: 2\n";
        Files.writeString(config, base);
        response(process, output, "chronicle reload", "Reload applied: runtime details=false");
        Files.writeString(config, base + "storage:\n  max-records: 20000\n");
        response(process, output, "chronicle reload", "require restart; no settings changed");
        Files.writeString(config, "config-version: 999\n");
        response(process, output, "chronicle reload", "Reload rejected; active settings unchanged");
        String status = response(process, output, "chronicle status", "Queries:");
        require(!status.contains("Runtime:") && status.contains("/10000"), "rejected reload preserves active settings");
        Files.writeString(config, base); System.out.println("PASS validated atomic presentation reload and rejected storage/malformed changes");
    }
    private static void drain(Process process, Path output) throws Exception {
        long deadline = System.nanoTime() + Duration.ofSeconds(10).toNanos();
        while (System.nanoTime() < deadline) {
            if (response(process, output, "chronicle status", "Queries:").contains("retained=0")) return;
            Thread.sleep(25);
        }
        throw new AssertionError("queries did not drain");
    }
    private static String response(Process process, Path output, String command, String marker) throws Exception {
        int start = PaperSmokeTest.readConsole(output).length(); command(process, command);
        long deadline = System.nanoTime() + Duration.ofSeconds(15).toNanos();
        while (process.isAlive() && System.nanoTime() < deadline) {
            String log = PaperSmokeTest.readConsole(output); String tail = log.substring(Math.min(start, log.length()));
            if (tail.contains(marker)) return tail;
            Thread.sleep(25);
        }
        throw new AssertionError("Missing response " + marker + " to " + command + "; inspect " + output);
    }
    private static void awaitClient(PacketClient client, String marker) throws Exception {
        long deadline = System.nanoTime() + Duration.ofSeconds(10).toNanos();
        while (System.nanoTime() < deadline) { client.check(); if (client.received(marker)) return; Thread.sleep(10); }
        throw new AssertionError("Client did not receive " + marker + ": " + client.messageDiagnostics());
    }
    private static void verifyHistory(Path database, Path artifact, String world, int sessions) throws Exception {
        try (var loader = new java.net.URLClassLoader(new java.net.URL[] {artifact.toUri().toURL()}, ClassLoader.getPlatformClassLoader())) {
            var driver = (java.sql.Driver) loader.loadClass("org.sqlite.JDBC").getConstructor().newInstance();
            try (var connection = driver.connect("jdbc:sqlite:" + database, new Properties()); var statement = connection.createStatement()) {
                String[] actions = {"PLACE", "BREAK", "PLACE", "BREAK", "BREAK", "PLACE"};
                long[] sequences = {1, 2, 3, 4, 10, 11};
                try (var rows = statement.executeQuery("SELECT * FROM changes ORDER BY id")) {
                    for (int i = 0; i < actions.length; i++) {
                        require(rows.next(), "missing durable row " + i);
                        require(rows.getLong("capture_sequence") == sequences[i] && rows.getInt("member_index") == 0, "ordered unique capture keys");
                        require(rows.getString("world_uuid").equals(world) && rows.getString("actor_uuid").equals(PacketClient.ACTOR.toString()), "durable world/actor identities");
                        require(rows.getInt("x") == 0 && rows.getInt("y") == 80 && rows.getInt("z") == 0, "durable coordinates");
                        require(rows.getString("action").equals(actions[i]), "durable action " + i);
                        require(rows.getString("before_data").equals(actions[i].equals("PLACE") ? "minecraft:air" : "minecraft:stone"), "before snapshot");
                        require(rows.getString("after_data").equals(actions[i].equals("PLACE") ? "minecraft:stone" : "minecraft:air"), "after snapshot");
                        require(rows.getInt("codec_version") == 1 && rows.getInt("data_version") == 5023 && rows.getInt("recovery_eligible") == 0, "versioned non-recovery snapshot");
                        require(rows.getString("evidence").equals(i == 4 ? "NEXT_OBSERVATION" : "LATER_SAMPLE"), "completion evidence");
                    }
                    require(!rows.next(), "cancelled/denied/unsupported/no-op actions must not create successful rows");
                }
                try (var result = statement.executeQuery("SELECT count(*),sum(clean) FROM sessions")) {
                    require(result.next() && result.getInt(1) == sessions && result.getInt(2) == sessions, "clean durable sessions");
                }
                try (var result = statement.executeQuery("SELECT count FROM coverage_incidents WHERE session_id=1 AND reason='UNSUPPORTED'")) {
                    require(result.next() && result.getInt(1) == 1, "unsupported replacement is explicit");
                }
            }
        }
        System.out.println("PASS exact durable history after " + sessions + " server session(s)");
    }
    private static void command(Process process, String command) throws Exception {
        process.outputWriter(StandardCharsets.UTF_8).append(command).append('\n').flush();
    }
    private static void await(Path log, Process process, String marker, int seconds) throws Exception {
        long deadline = System.nanoTime() + Duration.ofSeconds(seconds).toNanos();
        while (process.isAlive() && System.nanoTime() < deadline) {
            if (PaperSmokeTest.readConsole(log).contains(marker)) return;
            Thread.sleep(50);
        }
        throw new IllegalStateException("Missing " + marker + "; inspect " + log);
    }
    private static void require(boolean condition, String message) { if (!condition) throw new AssertionError(message); }
}
