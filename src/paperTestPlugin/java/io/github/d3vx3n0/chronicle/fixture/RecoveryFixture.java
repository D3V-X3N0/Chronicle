package io.github.d3vx3n0.chronicle.fixture;

import io.github.d3vx3n0.chronicle.capture.BlockReconciler.Position;
import io.github.d3vx3n0.chronicle.recovery.InertCodec;
import io.github.d3vx3n0.chronicle.recovery.SliceBudget;
import java.util.ArrayList;
import java.util.List;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.entity.ExperienceOrb;
import org.bukkit.entity.Item;
import org.bukkit.plugin.java.JavaPlugin;

/** Independent block/neighbour oracle: expected worlds are specified directly, never by inverse code. */
final class RecoveryFixture {
    private final JavaPlugin plugin;
    private final InertCodec codec = new InertCodec();
    private final List<Position> targets = new ArrayList<>();
    private int applied;
    private long maxCall, maxSlice;
    private World world;
    private int drops;
    RecoveryFixture(JavaPlugin plugin) { this.plugin = plugin; }
    void start() {
        try {
            world = Bukkit.getWorlds().getFirst();
            for (int x = -1; x <= 2; x++) for (int z = -1; z <= 2; z++) world.addPluginChunkTicket(x, z, plugin);
            for (int x = 0; x < 24; x++) for (int z = 0; z < 24; z++) for (int y = 78; y <= 82; y++)
                world.getBlockAt(x, y, z).setType(Material.AIR, false); // Fixture setup may load; production may not.
            for (int x = 2; x < 22; x += 3) for (int z = 2; z < 22; z += 3) {
                world.getBlockAt(x, 79, z).setType(Material.STONE, false);
                targets.add(new Position(world.getUID(), x, 80, z));
            }
            Position first = targets.getFirst();
            world.getBlockAt(first.x() + 1, 80, first.z()).setType(Material.SAND, false);
            boolean rejected = false;
            try { codec.snapshot(first); } catch (IllegalArgumentException expected) { rejected = true; }
            require(rejected, "unsafe dependency refused");
            world.getBlockAt(first.x() + 1, 80, first.z()).setType(Material.AIR, false);
            int loaded = world.getLoadedChunks().length;
            rejected = false;
            try { codec.snapshot(new Position(world.getUID(), 20_000_000, 80, 20_000_000)); }
            catch (IllegalArgumentException expected) { rejected = true; }
            require(rejected && world.getLoadedChunks().length == loaded, "no chunk generation/load");
            drops = drops();
            plugin.getServer().getScheduler().runTaskAsynchronously(plugin, () -> {
                try {
                    boolean refused = false;
                    try { codec.snapshot(first); } catch (IllegalStateException expected) { refused = true; }
                    require(refused, "off-thread refusal");
                    plugin.getServer().getScheduler().runTask(plugin, () -> slice(false));
                } catch (Throwable failure) { plugin.getServer().getScheduler().runTask(plugin, () -> fail(failure)); }
            });
        } catch (Throwable failure) { fail(failure); }
    }
    private void slice(boolean restoring) {
        try {
            var budget = new SliceBudget(7, 2_000_000, System::nanoTime);
            while (applied < targets.size() && budget.take()) {
                long start = System.nanoTime();
                var p = targets.get(applied++);
                codec.apply(codec.snapshot(p), restoring ? InertCodec.AIR : InertCodec.STONE);
                maxCall = Math.max(maxCall, System.nanoTime() - start);
            }
            require(budget.steps() <= 7, "step budget"); maxSlice = Math.max(maxSlice, budget.elapsed());
            if (applied < targets.size()) plugin.getServer().getScheduler().runTask(plugin, () -> slice(restoring));
            else plugin.getServer().getScheduler().runTaskLater(plugin, () -> verify(restoring), 5);
        } catch (Throwable failure) { fail(failure); }
    }
    private void verify(boolean restoring) {
        try {
            // Inspect the entire prepared region, including all dependencies and untouched cells.
            for (int x = 0; x < 24; x++) for (int z = 0; z < 24; z++) for (int y = 78; y <= 82; y++) {
                boolean column = x >= 2 && x <= 20 && (x - 2) % 3 == 0 && z >= 2 && z <= 20 && (z - 2) % 3 == 0;
                Material expected = column && (y == 79 || y == 80 && !restoring) ? Material.STONE : Material.AIR;
                require(world.getBlockAt(x, y, z).getType() == expected, "independent world at " + x + "," + y + "," + z);
            }
            require(drops() == drops, "no drops/XP");
            if (!restoring) { applied = 0; slice(true); }
            else {
                plugin.getLogger().info("CHRONICLE_RECOVERY_PASS codec calls=98 maxCallNanos=" + maxCall + " maxSliceNanos=" + maxSlice);
                Bukkit.shutdown();
            }
        } catch (Throwable failure) { fail(failure); }
    }
    private int drops() { return world.getEntitiesByClasses(Item.class, ExperienceOrb.class).size(); }
    private void fail(Throwable failure) { plugin.getLogger().severe("CHRONICLE_RECOVERY_FAIL " + failure); Bukkit.shutdown(); }
    private static void require(boolean test, String reason) { if (!test) throw new AssertionError(reason); }
}
