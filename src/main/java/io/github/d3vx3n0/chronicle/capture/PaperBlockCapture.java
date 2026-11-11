package io.github.d3vx3n0.chronicle.capture;

import io.github.d3vx3n0.chronicle.storage.Coverage;
import io.github.d3vx3n0.chronicle.storage.HistoryWriter;
import org.bukkit.Bukkit;
import org.bukkit.block.Block;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.block.BlockMultiPlaceEvent;
import org.bukkit.event.block.BlockPlaceEvent;

/** Public Paper API only; owns no file/SQL/future. Every entry point checks server thread ownership. */
public final class PaperBlockCapture implements Listener {
    private final HistoryWriter writer;
    private final BlockReconciler reconciler;
    private final int dataVersion;
    private boolean stopped;

    public PaperBlockCapture(HistoryWriter writer, int capacity, int dataVersion) {
        this.writer = writer; this.dataVersion = dataVersion;
        reconciler = new BlockReconciler(capacity, writer::offer, writer::note);
    }
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = false)
    public void place(BlockPlaceEvent event) {
        checkThread();
        // Cancelled attempts can still provide pre-state evidence for an earlier action.
        observe(event.getBlock(), event.getPlayer(), "PLACE", event.getBlockReplacedState().getBlockData().getAsString(),
                event.getBlock().getBlockData().getAsString(), event.isCancelled(), event.canBuild(), event instanceof BlockMultiPlaceEvent);
    }
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = false)
    public void breaking(BlockBreakEvent event) {
        checkThread();
        String before = event.getBlock().getBlockData().getAsString();
        observe(event.getBlock(), event.getPlayer(), "BREAK", before, before, event.isCancelled(), true, false);
    }
    private void observe(Block block, Player actor, String action, String before, String proposed, boolean cancelled, boolean build, boolean multi) {
        if (stopped) return;
        if (writer.status().state() != HistoryWriter.State.READY) { writer.note(Coverage.STORAGE_FAILURE, 0); return; }
        reconciler.observe(new BlockReconciler.Observation(new BlockReconciler.Position(block.getWorld().getUID(), block.getX(), block.getY(), block.getZ()),
                block.getWorld().getName(), actor.getUniqueId(), actor.getName(), action, before, proposed,
                Bukkit.getCurrentTick(), System.currentTimeMillis(), dataVersion), cancelled, build, multi);
    }
    public void tick() {
        checkThread();
        reconciler.sample(Bukkit.getCurrentTick(), position -> {
            var world = Bukkit.getWorld(position.world());
            if (world == null || !world.isChunkLoaded(Math.floorDiv(position.x(), 16), Math.floorDiv(position.z(), 16))) return null;
            return world.getBlockAt(position.x(), position.y(), position.z()).getBlockData().getAsString();
        });
    }
    public void stop() { checkThread(); stopped = true; reconciler.abandon(); }
    public int pending() { return reconciler.pending(); }
    public long observedSequence() { return reconciler.observedSequence(); }
    private static void checkThread() { if (!Bukkit.isPrimaryThread()) throw new IllegalStateException("Capture requires the Paper server thread"); }
}
