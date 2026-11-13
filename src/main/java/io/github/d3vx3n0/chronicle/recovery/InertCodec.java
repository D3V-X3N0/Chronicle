package io.github.d3vx3n0.chronicle.recovery;

import io.github.d3vx3n0.chronicle.capture.BlockReconciler.Position;
import java.util.ArrayList;
import java.util.List;
import org.bukkit.Bukkit;
import org.bukkit.block.Block;

/** Pinned stone/air codec. Physics stays enabled; the complete local dependency shell must be inert. */
public final class InertCodec {
    public static final String AIR = InertStates.AIR, STONE = InertStates.STONE;
    public record Snapshot(Position target, List<String> states) {
        public Snapshot { states = List.copyOf(states); if (states.size() != 27) throw new IllegalArgumentException("Incomplete shell"); }
        public String center() { return states.get(13); }
        public Snapshot withCenter(String state) {
            var copy = new ArrayList<>(states); copy.set(13, state); return new Snapshot(target, copy);
        }
    }
    public static boolean supports(String state) { return InertStates.supports(state); }
    public static List<Position> shell(Position p) {
        var result = new ArrayList<Position>(27);
        for (int x = -1; x <= 1; x++) for (int y = -1; y <= 1; y++) for (int z = -1; z <= 1; z++)
            result.add(new Position(p.world(), p.x() + x, p.y() + y, p.z() + z));
        return List.copyOf(result);
    }
    public Snapshot snapshot(Position target) {
        checkThread();
        var states = new ArrayList<String>(27);
        for (var p : shell(target)) {
            Block block = loaded(p);
            String state = block.getBlockData().getAsString();
            if (!supports(state)) throw new IllegalArgumentException("Unsupported target/dependency at " + p + ": " + state);
            states.add(state);
        }
        return new Snapshot(target, states);
    }
    /** Read-only reconciliation evidence may describe unsupported state; it never grants codec eligibility. */
    public String currentState(Position target) {
        checkThread(); String state = loaded(target).getBlockData().getAsString();
        if (state.length() > 256) throw new IllegalArgumentException("Unsupported reconciliation payload"); return state;
    }
    public Snapshot apply(Snapshot expected, String desired) {
        checkThread();
        if (!supports(desired) || desired.equals(expected.center())) throw new IllegalArgumentException("Unsupported/no-op transition");
        if (!snapshot(expected.target()).equals(expected)) throw new IllegalArgumentException("Target/dependency changed");
        Block block = loaded(expected.target());
        if (STONE.equals(desired) && !block.getWorld().getNearbyEntities(
                new org.bukkit.util.BoundingBox(block.getX(), block.getY(), block.getZ(), block.getX() + 1, block.getY() + 1, block.getZ() + 1)).isEmpty())
            throw new IllegalArgumentException("Entity occupies target");
        block.setBlockData(Bukkit.createBlockData(desired), true);
        Snapshot actual = snapshot(expected.target());
        if (!actual.equals(expected.withCenter(desired))) throw new IllegalStateException("Post-apply state/dependency mismatch");
        return actual;
    }
    private static Block loaded(Position p) {
        var world = Bukkit.getWorld(p.world());
        if (world == null || p.y() < world.getMinHeight() || p.y() >= world.getMaxHeight()
                || !world.isChunkLoaded(Math.floorDiv(p.x(), 16), Math.floorDiv(p.z(), 16)))
            throw new IllegalArgumentException("World/chunk unavailable; recovery never loads chunks");
        return world.getBlockAt(p.x(), p.y(), p.z());
    }
    public static void checkThread() {
        if (!Bukkit.isPrimaryThread()) throw new IllegalStateException("Recovery requires the Paper server thread");
    }
}
