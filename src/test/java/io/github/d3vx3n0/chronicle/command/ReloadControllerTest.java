package io.github.d3vx3n0.chronicle.command;

import static org.junit.jupiter.api.Assertions.*;

import io.github.d3vx3n0.chronicle.config.ChronicleConfig;
import java.lang.reflect.Proxy;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.bukkit.command.CommandSender;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ReloadControllerTest {
    @TempDir Path directory;
    private final AtomicBoolean permission = new AtomicBoolean(true);
    private final List<String> messages = new ArrayList<>();
    private CommandSender sender() {
        return (CommandSender) Proxy.newProxyInstance(CommandSender.class.getClassLoader(), new Class<?>[] {CommandSender.class},
                (proxy, method, args) -> switch (method.getName()) {
                    case "hasPermission" -> permission.get();
                    case "sendMessage" -> { for (Object arg : args) if (arg instanceof Component component)
                        messages.add(PlainTextComponentSerializer.plainText().serialize(component)); yield null; }
                    default -> throw new AssertionError("Unexpected sender API: " + method.getName());
                });
    }
    private static void finish(ReloadController reload) throws Exception {
        long deadline = System.nanoTime() + 5_000_000_000L;
        while (reload.pending() && System.nanoTime() < deadline) { reload.tick(); Thread.sleep(1); }
        assertFalse(reload.pending(), "reload has an observable completion");
    }
    @Test void onlyValidatedRuntimeSettingsApplyAndFileIsNeverOverwritten() throws Exception {
        var active = new AtomicReference<>(new ChronicleConfig(true)); Path file = directory.resolve("config.yml");
        String valid = "config-version: 1\ndiagnostics:\n  show-runtime-details: false\nlookup:\n  page-size: 2\n";
        try (var reload = new ReloadController(file, active::get, active::set)) {
            Files.writeString(file, valid); reload.request(sender()); finish(reload);
            assertFalse(active.get().showRuntimeDetails()); assertEquals(2, active.get().pageSize());
            ChronicleConfig accepted = active.get();
            for (String invalid : List.of(valid + "storage:\n  max-records: 20000\n", "config-version: [", "a".repeat(65_537))) {
                Files.writeString(file, invalid); reload.request(sender()); finish(reload);
                assertSame(accepted, active.get()); assertEquals(invalid, Files.readString(file));
            }
        }
    }
    @Test void revocationPreventsApplyingAndDeliveringReloadResultsAndDeniedCompletionUsesNoServer() throws Exception {
        Path file = directory.resolve("config.yml"); Files.writeString(file, "config-version: 1\ndiagnostics:\n  show-runtime-details: false\n");
        var active = new AtomicReference<>(new ChronicleConfig(true));
        try (var reload = new ReloadController(file, active::get, active::set)) {
            reload.request(sender()); permission.set(false); int admittedMessages = messages.size(); finish(reload);
            assertTrue(active.get().showRuntimeDetails()); assertEquals(admittedMessages, messages.size());
            reload.request(sender()); assertFalse(reload.pending()); assertTrue(messages.getLast().contains("chronicle.reload"));
        }
        var controller = new InvestigationController(null, null, active::get, () -> null, () -> 0);
        assertEquals(List.of(), controller.complete(sender(), "player:"));
    }
}
