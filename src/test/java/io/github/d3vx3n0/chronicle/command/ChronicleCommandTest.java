package io.github.d3vx3n0.chronicle.command;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.d3vx3n0.chronicle.config.ChronicleConfig;
import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.List;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.bukkit.Server;
import org.bukkit.command.CommandSender;
import org.junit.jupiter.api.Test;

class ChronicleCommandTest {
    private final List<String> messages = new ArrayList<>();

    @Test
    void statusReportsMissingProtectionAndOptionalRuntimeDetails() {
        command(true).onCommand(sender(true), null, "chronicle", new String[] {"status"});
        assertTrue(messages.stream().anyMatch(text -> text.contains("inert loaded targets")));
        assertTrue(messages.stream().anyMatch(text -> text.contains("Runtime: Paper")));
    }

    @Test
    void configuredRuntimeSuppressionIsRespected() {
        command(false).onCommand(sender(true), null, "chronicle", new String[] {"status"});
        assertFalse(messages.stream().anyMatch(text -> text.contains("Runtime:")));
        assertTrue(messages.stream().anyMatch(text -> text.contains("inert loaded targets")));
    }

    @Test
    void permissionDenialHidesStatusAndCompletions() {
        var denied = sender(false);
        command(true).onCommand(denied, null, "chronicle", new String[] {"status"});
        assertEquals(List.of("You need chronicle.status to view Chronicle status."), messages);
        assertEquals(List.of(), command(true).onTabComplete(denied, null, "chronicle", new String[] {""}));
    }

    @Test
    void unknownOrExtraArgumentsCannotPretendToRunRecovery() {
        command(true).onCommand(sender(true), null, "chronicle", new String[] {"rollback"});
        command(true).onCommand(sender(true), null, "chronicle", new String[] {"status", "extra"});
        assertEquals(List.of("Usage: /chronicle status", "Usage: /chronicle status"), messages);
    }

    @Test
    void completionIsCaseInsensitiveAndContainsOnlyImplementedCommands() {
        assertEquals(List.of("status"), command(true).onTabComplete(sender(true), null, "chronicle", new String[] {"ST"}));
        assertEquals(List.of(), command(true).onTabComplete(sender(true), null, "chronicle", new String[] {"rollback"}));
    }

    private ChronicleCommand command(boolean runtimeDetails) {
        var server = (Server) Proxy.newProxyInstance(Server.class.getClassLoader(), new Class<?>[] {Server.class},
                (proxy, method, args) -> switch (method.getName()) {
                    case "getName" -> "Paper";
                    case "getMinecraftVersion" -> "26.3";
                    default -> throw new AssertionError("Unexpected server call: " + method.getName());
                });
        return new ChronicleCommand("test", new ChronicleConfig(runtimeDetails), server);
    }

    private CommandSender sender(boolean permission) {
        return (CommandSender) Proxy.newProxyInstance(CommandSender.class.getClassLoader(), new Class<?>[] {CommandSender.class},
                (proxy, method, args) -> switch (method.getName()) {
                    case "hasPermission" -> permission;
                    case "sendMessage" -> {
                        for (Object arg : args) {
                            if (arg instanceof Component component) {
                                messages.add(PlainTextComponentSerializer.plainText().serialize(component));
                            }
                        }
                        yield null;
                    }
                    default -> throw new AssertionError("Unexpected sender call: " + method.getName());
                });
    }
}
