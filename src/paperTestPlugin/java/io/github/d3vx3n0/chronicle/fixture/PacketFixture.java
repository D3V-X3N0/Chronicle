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
    private org.bukkit.permissions.PermissionAttachment permissions;

    PacketFixture(JavaPlugin plugin) { this.plugin = plugin; }

    boolean command(CommandSender sender, String[] args) {
        if (sender instanceof ConsoleCommandSender && args.length >= 3 && args[0].equals("recovery-player")) {
            var player = Objects.requireNonNull(Bukkit.getPlayerExact("ChronicleTest"));
            if (permissions == null) permissions = player.addAttachment(plugin);
            permissions.setPermission("chronicle.rollback", !args[1].equals("denied"));
            permissions.setPermission("chronicle.restore", !args[1].equals("denied"));
            Objects.requireNonNull(Bukkit.getPluginCommand("chronicle")).execute(player, "chronicle", java.util.Arrays.copyOfRange(args, 2, args.length));
            if (args[1].equals("revoke")) { permissions.setPermission("chronicle.rollback", false); permissions.setPermission("chronicle.restore", false); }
            plugin.getLogger().info("RECOVERY_PLAYER " + args[1]); return true;
        }
        if (!(sender instanceof ConsoleCommandSender) || args.length != 2) return false;
        if (args[0].equals("recovery-pause-confirm")) {
            var command = Objects.requireNonNull(Bukkit.getPluginCommand("chronicle"));
            command.execute(sender, "chronicle", new String[] {"rollback", "confirm", args[1]});
            command.execute(sender, "chronicle", new String[] {"rollback", "pause"});
            plugin.getLogger().info("RECOVERY_PAUSE_REQUESTED"); return true;
        }
        if (args[0].equals("recovery-diverge")) {
            Objects.requireNonNull(Bukkit.getPlayerExact("ChronicleTest")).getWorld().getBlockAt(0, 80, 0).setType(Material.DIRT, false);
            plugin.getLogger().info("RECOVERY_DIVERGED dirt"); return true;
        }
        if (args[0].equals("recovery-crash")) {
            try {
                var chronicle = Objects.requireNonNull(Bukkit.getPluginManager().getPlugin("Chronicle"));
                var field = chronicle.getClass().getDeclaredField("recovery"); field.setAccessible(true);
                var recovery = field.get(chronicle);
                var probe = recovery.getClass().getDeclaredField("boundaryProbe"); probe.setAccessible(true);
                String requested = args[1];
                java.util.function.Consumer<String> stop = boundary -> {
                    if (requested.equals("revoke-intent") && boundary.equals("after-intent")) {
                        permissions.setPermission("chronicle.rollback", false); permissions.setPermission("chronicle.restore", false);
                        plugin.getLogger().info("RECOVERY_PERMISSION_REVOKED after-intent"); return;
                    }
                    boolean save = requested.equals("after-save") && boundary.equals("after-outcome");
                    boolean clean = requested.equals("clean-disable") && boundary.equals("after-summary");
                    if (!requested.equals(boundary) && !save && !clean) return;
                    if (save) Bukkit.getWorlds().getFirst().save();
                    if (clean) Bukkit.getPluginManager().disablePlugin(chronicle);
                    plugin.getLogger().info("RECOVERY_CRASH_BOUNDARY " + requested);
                    // Disposable fixture only. The external harness forcibly kills this child JVM.
                    try { new java.util.concurrent.CountDownLatch(1).await(); }
                    catch (InterruptedException error) { Thread.currentThread().interrupt(); }
                };
                probe.set(recovery, stop);
                plugin.getLogger().info("RECOVERY_CRASH_ARMED " + requested); return true;
            } catch (ReflectiveOperationException error) { throw new IllegalStateException(error); }
        }
        if (args[0].equals("recovery-world")) {
            var world = Objects.requireNonNull(Bukkit.getPlayerExact("ChronicleTest")).getWorld();
            for (int x = -2; x <= 4; x++) for (int z = -2; z <= 2; z++) for (int y = 79; y <= 83; y++) {
                Material target = switch (args[1]) { case "stone" -> Material.STONE; case "dirt" -> Material.DIRT; default -> Material.AIR; };
                Material expected = y == 79 ? Material.STONE : x == 0 && y == 80 && z == 0 ? target : Material.AIR;
                if (world.getBlockAt(x, y, z).getType() != expected) throw new AssertionError("Reference world mismatch at " + x + "," + y + "," + z);
            }
            if (!world.getEntitiesByClasses(org.bukkit.entity.Item.class, org.bukkit.entity.ExperienceOrb.class).isEmpty()) throw new AssertionError("Unexpected drops/XP");
            plugin.getLogger().info("RECOVERY_WORLD " + args[1]); return true;
        }
        if (args[0].equals("fence")) {
            Objects.requireNonNull(Bukkit.getPlayerExact("ChronicleTest")).sendMessage(net.kyori.adventure.text.Component.text("Lookup fence " + args[1]));
            return true;
        }
        if (args[0].equals("grant")) {
            var player = Objects.requireNonNull(Bukkit.getPlayerExact("ChronicleTest"));
            permissions = player.addAttachment(plugin);
            permissions.setPermission("chronicle.inspect", true); permissions.setPermission("chronicle.lookup", true);
            if (!player.hasPermission("chronicle.inspect")) throw new AssertionError("Rejoined inspector permission missing");
            plugin.getLogger().info("PERMISSIONS_GRANTED " + args[1]); return true;
        }
        if (args[0].equals("inspect")) {
            var player = Objects.requireNonNull(Bukkit.getPlayerExact("ChronicleTest"));
            if (permissions == null) permissions = player.addAttachment(plugin);
            permissions.setPermission("chronicle.inspect", true);
            permissions.setPermission("chronicle.lookup", true);
            player.performCommand("chronicle inspect");
            plugin.getLogger().info("INSPECT_TOGGLED " + args[1]); return true;
        }
        if (args[0].equals("lookup")) {
            var player = Objects.requireNonNull(Bukkit.getPlayerExact("ChronicleTest"));
            if (permissions == null) permissions = player.addAttachment(plugin);
            permissions.setPermission("chronicle.lookup", !args[1].equals("denied"));
            // Invoke the registered executor synchronously so revocation is strictly after admission,
            // before its later tick delivery, rather than racing Paper's command scheduling.
            Objects.requireNonNull(Bukkit.getPluginCommand("chronicle")).execute(player, "chronicle",
                    new String[] {"lookup", "time:1d", "player:ChronicleTest"});
            if (args[1].equals("revoke")) permissions.setPermission("chronicle.lookup", false);
            plugin.getLogger().info("PLAYER_LOOKUP " + args[1]); return true;
        }
        if (args[0].equals("assert")) {
            var player = Objects.requireNonNull(Bukkit.getPlayerExact("ChronicleTest"));
            var world = player.getWorld();
            boolean chest = args[1].contains("chest");
            boolean okay = world.getBlockAt(0, 80, 0).getType() == (chest ? Material.CHEST : Material.STONE)
                    && world.getBlockAt(0, 81, 0).getType() == Material.AIR
                    && player.getOpenInventory().getTopInventory().getType() == (args[1].equals("logout-chest")
                            ? org.bukkit.event.inventory.InventoryType.CHEST : org.bukkit.event.inventory.InventoryType.CRAFTING);
            if (!okay) throw new AssertionError("Inspection changed block or opened inventory: " + args[1]);
            plugin.getLogger().info("INSPECT_UNCHANGED " + args[1]); return true;
        }
        if (args[0].equals("prepare")) {
            scenario = args[1]; attempts.clear();
            var player = Objects.requireNonNull(Bukkit.getPlayerExact("ChronicleTest"));
            var world = player.getWorld();
            for (int x = -2; x <= 4; x++) for (int z = -2; z <= 2; z++) {
                world.getBlockAt(x, 79, z).setType(Material.STONE, false);
                for (int y = 80; y <= 83; y++) world.getBlockAt(x, y, z).setType(Material.AIR, false);
            }
            boolean breaking = scenario.contains("break") || scenario.equals("repeat") || scenario.startsWith("inspect");
            world.getBlockAt(0, 80, 0).setType(breaking ? Material.STONE
                    : scenario.equals("replacement") ? Material.SHORT_GRASS : Material.AIR, false);
            if (scenario.equals("noop")) world.getBlockAt(0, 80, 0).setType(Material.AIR, false);
            if (scenario.contains("chest")) world.getBlockAt(0, 80, 0).setType(Material.CHEST, false);
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
