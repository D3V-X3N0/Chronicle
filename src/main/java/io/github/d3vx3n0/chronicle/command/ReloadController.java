package io.github.d3vx3n0.chronicle.command;

import io.github.d3vx3n0.chronicle.config.ChronicleConfig;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;
import java.util.function.Supplier;
import org.bukkit.command.CommandSender;
import org.bukkit.configuration.file.YamlConfiguration;

/** One admitted reload, including its undelivered result. The worker never retains a sender. */
public final class ReloadController implements AutoCloseable {
    private record Result(ChronicleConfig settings, String error) {}
    private final Path file;
    private final Supplier<ChronicleConfig> current;
    private final Consumer<ChronicleConfig> apply;
    private final AtomicReference<Result> completed = new AtomicReference<>();
    private CommandSender requester;
    private boolean closed;
    public ReloadController(Path file, Supplier<ChronicleConfig> current, Consumer<ChronicleConfig> apply) {
        this.file = file; this.current = current; this.apply = apply;
    }
    public void request(CommandSender sender) {
        if (!InvestigationController.allowed(sender, "chronicle.reload")) { InvestigationController.deny(sender, "chronicle.reload"); return; }
        if (closed || requester != null) { InvestigationController.error(sender, "A reload is already pending or Chronicle is stopping."); return; }
        requester = sender;
        Thread.ofPlatform().daemon().name("chronicle-config-reader").start(() -> {
            try { completed.set(new Result(read(file), null)); }
            catch (Exception failure) { completed.set(new Result(null, "Reload rejected; active settings unchanged: " + failure.getMessage())); }
        });
        InvestigationController.message(sender, "Configuration validation queued.");
    }
    public static ChronicleConfig read(Path file) throws Exception {
        byte[] bytes;
        try (var input = Files.newInputStream(file)) { bytes = input.readNBytes(65_537); }
        if (bytes.length > 65_536) throw new IllegalArgumentException("config.yml exceeds 64 KiB");
        var yaml = new YamlConfiguration(); yaml.loadFromString(new String(bytes, StandardCharsets.UTF_8));
        return ChronicleConfig.from(yaml.getValues(true));
    }
    public void tick() {
        Result result = completed.getAndSet(null);
        if (result == null || closed) return;
        CommandSender sender = requester; requester = null;
        if (!InvestigationController.deliverable(sender, "chronicle.reload")) return;
        if (result.error != null) { InvestigationController.error(sender, result.error); return; }
        try {
            apply.accept(current.get().runtimeReload(result.settings));
            InvestigationController.message(sender, "Reload applied: runtime details=" + result.settings.showRuntimeDetails()
                    + "; lookup page size=" + result.settings.pageSize() + ". Existing cursors retain their page size.");
        } catch (IllegalArgumentException invalid) { InvestigationController.error(sender, invalid.getMessage()); }
    }
    public boolean pending() { return requester != null; }
    @Override public void close() { closed = true; requester = null; completed.set(null); }
}
