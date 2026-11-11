package io.github.d3vx3n0.chronicle.bootstrap;

import io.github.d3vx3n0.chronicle.command.ChronicleCommand;
import io.github.d3vx3n0.chronicle.config.ChronicleConfig;
import io.github.d3vx3n0.chronicle.capture.PaperBlockCapture;
import io.github.d3vx3n0.chronicle.storage.HistoryWriter;
import io.github.d3vx3n0.chronicle.storage.sqlite.SqliteStore;
import java.io.File;
import java.io.IOException;
import java.util.Objects;
import org.bukkit.configuration.InvalidConfigurationException;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.plugin.java.JavaPlugin;

public final class ChroniclePlugin extends JavaPlugin {
    private HistoryWriter writer;
    private PaperBlockCapture capture;
    @Override
    public void onEnable() {
        final ChronicleConfig settings;
        try {
            saveDefaultConfig();
            var yaml = new YamlConfiguration();
            // loadConfiguration(File) can swallow malformed-file errors. Loading explicitly fails closed.
            yaml.load(new File(getDataFolder(), "config.yml"));
            settings = ChronicleConfig.from(yaml.getValues(true));
        } catch (IOException | InvalidConfigurationException | IllegalArgumentException exception) {
            getLogger().severe("Chronicle could not load config.yml: " + exception.getMessage());
            getLogger().severe("Plugin disabled. Correct the configuration and restart; the existing file was preserved.");
            getServer().getPluginManager().disablePlugin(this);
            return;
        }

        var build = io.papermc.paper.ServerBuildInfo.buildInfo();
        if (!build.brandId().equals(io.papermc.paper.ServerBuildInfo.BRAND_PAPER_ID)
                || !build.minecraftVersionId().equals("26.3") || build.buildNumber().orElse(-1) != 248 || dataVersion() != 5023) {
            getLogger().severe("Capture is qualified only for Paper 26.3 build 248 / data version 5023. Plugin disabled; database preserved.");
            getServer().getPluginManager().disablePlugin(this); return;
        }
        var database = getDataFolder().toPath().resolve("history.db");
        var limits = settings.storage();
        String runtime = getServer().getVersion() + " / Java " + Runtime.version();
        writer = new HistoryWriter(limits, () -> new SqliteStore(database, limits.busyMillis()), runtime, getLogger()::warning);
        capture = new PaperBlockCapture(writer, limits.reconciliationRecords(), dataVersion());
        var executor = new ChronicleCommand(getPluginMeta().getVersion(), settings, getServer(), writer::status, capture::pending, capture::observedSequence);
        var command = Objects.requireNonNull(getCommand("chronicle"), "Missing chronicle command in plugin.yml");
        command.setExecutor(executor);
        command.setTabCompleter(executor);
        getServer().getPluginManager().registerEvents(capture, this);
        getServer().getScheduler().runTaskTimer(this, capture::tick, 1, 1);
        getLogger().warning("Chronicle starting SQLite asynchronously. Qualified capture: stone/air only; inspection and rollback NOT IMPLEMENTED.");
    }

    @SuppressWarnings("deprecation") // Pinned Paper exposes data version only through this public legacy accessor.
    private int dataVersion() { return getServer().getUnsafe().getDataVersion(); }

    @Override
    public void onDisable() {
        if (capture != null) capture.stop();
        if (writer != null) {
            boolean clean = writer.stop();
            var status = writer.status();
            getLogger().info("Chronicle stopped: clean=" + clean + ", committed=" + status.committed()
                    + ", pending=" + status.pendingRecords() + ", state=" + status.state());
            if (!clean) getLogger().severe("Unclean history shutdown. Pending records may be lost; preserve the database and inspect status after restart.");
        }
    }
}
