package io.github.d3vx3n0.chronicle.bootstrap;

import io.github.d3vx3n0.chronicle.command.ChronicleCommand;
import io.github.d3vx3n0.chronicle.config.ChronicleConfig;
import java.io.File;
import java.io.IOException;
import java.util.Objects;
import org.bukkit.configuration.InvalidConfigurationException;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.plugin.java.JavaPlugin;

public final class ChroniclePlugin extends JavaPlugin {
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

        var executor = new ChronicleCommand(getPluginMeta().getVersion(), settings, getServer());
        var command = Objects.requireNonNull(getCommand("chronicle"), "Missing chronicle command in plugin.yml");
        command.setExecutor(executor);
        command.setTabCompleter(executor);
        getLogger().warning("Chronicle foundation enabled. Activity logging, persistence and rollback are NOT implemented.");
    }

    @Override
    public void onDisable() {
        getLogger().info("Chronicle foundation disabled; no history writer or pending records exist in this build.");
    }
}
