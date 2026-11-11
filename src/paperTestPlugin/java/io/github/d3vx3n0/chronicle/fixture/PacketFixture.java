package io.github.d3vx3n0.chronicle.fixture;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import org.bukkit.Bukkit;
import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.command.CommandSender;
import org.bukkit.command.ConsoleCommandSender;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.plugin.java.JavaPlugin;

/** Disposable protection fixture and independent world oracle. Never dispatches player events. */
final class PacketFixture implements Listener {
    private final JavaPlugin plugin;
    private final List<String> attempts = new ArrayList<>();
    private String scenario = "idle";
    private int startTick;

    PacketFixture(JavaPlugin plugin) { this.plugin = plugin; }

    boolean command(CommandSender sender, String[] args) {
        if (!(sender instanceof ConsoleCommandSender) || args.length != 2) return false;
        if (args[0].equals("prepare")) {
            scenario = args[1]; attempts.clear();
            var player = Objects.requireNonNull(Bukkit.getPlayerExact("ChronicleTest"));
            var world = player.getWorld();
            for (int x = -2; x <= 4; x++) for (int z = -2; z <= 2; z++) {
                world.getBlockAt(x, 79, z).setType(Material.STONE, false);
                for (int y = 80; y <= 83; y++) world.getBlockAt(x, y, z).setType(Material.AIR, false);
            }
            boolean breaking = scenario.contains("break") || scenario.equals("repeat");
            world.getBlockAt(0, 80, 0).setType(breaking ? Material.STONE
                    : scenario.equals("replacement") ? Material.SHORT_GRASS : Material.AIR, false);
            if (scenario.equals("noop")) world.getBlockAt(0, 80, 0).setType(Material.AIR, false);
            player.setGameMode(scenario.startsWith("survival") ? GameMode.SURVIVAL : GameMode.CREATIVE);
            player.getInventory().clear();
            player.getInventory().setHeldItemSlot(0);
            player.getInventory().setItemInMainHand(new ItemStack(scenario.equals("survival-break")
                    ? Material.DIAMOND_PICKAXE : Material.STONE, 64));
            player.teleport(new Location(world, 2.5, 80, .5, 90, 45));
            startTick = Bukkit.getCurrentTick();
            plugin.getLogger().info("PACKET_PREPARED " + scenario + " actor=" + player.getUniqueId() + " world=" + world.getUID());
            return true;
        }
        if (args[0].equals("report")) {
            for (String attempt : attempts) plugin.getLogger().info("PACKET_ATTEMPT " + scenario + " " + attempt);
            var world = Objects.requireNonNull(Bukkit.getPlayerExact("ChronicleTest")).getWorld();
            plugin.getLogger().info("PACKET_RESULT " + scenario + " count=" + attempts.size()
                    + " final=" + world.getBlockAt(0, 80, 0).getBlockData().getAsString()
                    + " elapsedTicks=" + (Bukkit.getCurrentTick() - startTick));
            return true;
        }
        return false;
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    public void protectPlace(BlockPlaceEvent event) {
        if (scenario.equals("cancel-place")) event.setCancelled(true);
        if (scenario.equals("deny-place")) event.setBuild(false);
    }
    @EventHandler(priority = EventPriority.HIGHEST)
    public void protectBreak(BlockBreakEvent event) {
        if (scenario.equals("cancel-break")) event.setCancelled(true);
    }
    @EventHandler(priority = EventPriority.MONITOR)
    public void place(BlockPlaceEvent event) {
        record("PLACE", event.getBlockReplacedState().getBlockData().getAsString(),
                event.getBlock().getBlockData().getAsString(), event.isCancelled(), event.canBuild(), event.getPlayer().getUniqueId().toString());
    }
    @EventHandler(priority = EventPriority.MONITOR)
    public void breaking(BlockBreakEvent event) {
        record("BREAK", event.getBlock().getBlockData().getAsString(),
                event.getBlock().getBlockData().getAsString(), event.isCancelled(), true, event.getPlayer().getUniqueId().toString());
    }
    private void record(String action, String before, String during, boolean cancelled, boolean build, String actor) {
        if (!Bukkit.isPrimaryThread()) throw new IllegalStateException("Off-thread fixture observation");
        if (attempts.size() >= 128) throw new IllegalStateException("Fixture overflow");
        attempts.add(action + " tick=" + Bukkit.getCurrentTick() + " before=" + before + " during=" + during
                + " cancelled=" + cancelled + " build=" + build + " actor=" + actor);
    }
}
