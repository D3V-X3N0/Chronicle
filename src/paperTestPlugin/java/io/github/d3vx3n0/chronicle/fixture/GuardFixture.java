package io.github.d3vx3n0.chronicle.fixture;

import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.command.ConsoleCommandSender;
import org.bukkit.event.Event;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.plugin.java.JavaPlugin;

/** A second, separately loaded protection plugin. Installed only in disposable packet tests. */
public final class GuardFixture extends JavaPlugin implements Listener {
    private String mode = "OFF";
    @Override public void onEnable() {
        for (EventPriority priority : EventPriority.values()) {
            if (priority == EventPriority.MONITOR) continue;
            getServer().getPluginManager().registerEvent(BlockPlaceEvent.class, this, priority,
                    (listener, event) -> { if (mode.equals(priority.name())) ((BlockPlaceEvent) event).setCancelled(true); }, this, false);
            getServer().getPluginManager().registerEvent(BlockBreakEvent.class, this, priority,
                    (listener, event) -> { if (mode.equals(priority.name())) ((BlockBreakEvent) event).setCancelled(true); }, this, false);
        }
        getServer().getPluginManager().registerEvent(PlayerInteractEvent.class, this, EventPriority.HIGH,
                (listener, event) -> {
                    var interaction = (PlayerInteractEvent) event;
                    if (mode.equals("BLOCK")) interaction.setUseInteractedBlock(Event.Result.DENY);
                    if (mode.equals("ITEM")) interaction.setUseItemInHand(Event.Result.DENY);
                }, this, false);
    }
    @Override public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (!(sender instanceof ConsoleCommandSender) || args.length != 1) return false;
        mode = args[0]; getLogger().info("GUARD_MODE " + mode); return true;
    }
}
