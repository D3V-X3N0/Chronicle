package io.github.d3vx3n0.chronicle.capture;

import io.github.d3vx3n0.chronicle.model.BlockChange;
import io.github.d3vx3n0.chronicle.model.CaptureGroup;
import io.github.d3vx3n0.chronicle.storage.Coverage;
import java.util.ArrayDeque;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.BiConsumer;
import java.util.function.Consumer;
import java.util.function.Function;

/** Thread-confined ordered reconciliation. Candidate post-state is never sufficient evidence.
 * The only qualified snapshots are canonical minecraft:air and minecraft:stone (codec v1).
 */
public final class BlockReconciler {
    public record Position(UUID world, int x, int y, int z) {}
    public record Observation(Position position, String worldName, UUID actor, String actorName,
            String action, String before, String proposed, int tick, long time, int dataVersion) {}
    private static final String AIR = "minecraft:air", STONE = "minecraft:stone";
    private static final class Pending {
        final long sequence;
        final Observation observation;
        boolean resolved;
        CaptureGroup group;
        Pending(long sequence, Observation observation) { this.sequence = sequence; this.observation = observation; }
    }
    private final int capacity;
    private final Consumer<CaptureGroup> history;
    private final BiConsumer<Coverage, Long> gaps;
    private final ArrayDeque<Pending> ordered = new ArrayDeque<>();
    private final Map<Position, Pending> byPosition = new HashMap<>();
    private long sequence;

    public BlockReconciler(int capacity, Consumer<CaptureGroup> history, BiConsumer<Coverage, Long> gaps) {
        if (capacity < 1 || capacity > 10_000) throw new IllegalArgumentException("Invalid reconciliation capacity");
        this.capacity = capacity; this.history = history; this.gaps = gaps;
    }

    public void observe(Observation observation, boolean cancelled, boolean canBuild, boolean multiPlace) {
        long id = ++sequence;
        Pending previous = byPosition.get(observation.position());
        if (previous != null) resolve(previous, observation.before(), "NEXT_OBSERVATION");
        drain();
        if (cancelled || !canBuild) return;
        if (observation.action().equals("BREAK") && observation.before().equals(AIR)) return;
        if (multiPlace) {
            // Unqualified multi-target action may overlap a pending target other than the primary block.
            abandon(); gaps.accept(Coverage.UNSUPPORTED, id); return;
        }
        boolean eligible = observation.action().equals("PLACE") && observation.before().equals(AIR) && observation.proposed().equals(STONE)
                || observation.action().equals("BREAK") && observation.before().equals(STONE);
        if (!eligible) { gaps.accept(Coverage.UNSUPPORTED, id); return; }
        if (ordered.size() >= capacity) { gaps.accept(Coverage.RECONCILIATION_FULL, id); return; }
        var pending = new Pending(id, observation); ordered.addLast(pending); byPosition.put(observation.position(), pending);
    }

    public void sample(int tick, Function<Position, String> world) {
        int budget = 128;
        drain();
        while (budget-- > 0 && !ordered.isEmpty()) {
            Pending pending = ordered.getFirst();
            if (pending.observation.tick() == tick) break;
            // Never load chunks; absent/unloaded/expired evidence is explicit uncertainty.
            String state = tick - pending.observation.tick() > 2 ? null : world.apply(pending.observation.position());
            resolve(pending, state, "LATER_SAMPLE"); drain();
        }
    }

    private void resolve(Pending pending, String observed, String evidence) {
        if (pending.resolved) return;
        pending.resolved = true; byPosition.remove(pending.observation.position(), pending);
        Observation o = pending.observation;
        String expected = o.action().equals("BREAK") ? AIR : o.proposed();
        if (observed == null || !observed.equals(expected) && !observed.equals(o.before())) {
            gaps.accept(Coverage.AMBIGUOUS, pending.sequence); return;
        }
        if (observed.equals(o.before())) return; // Proven no-op; not a successful transition.
        var change = new BlockChange(o.position().world(), o.worldName(), o.position().x(), o.position().y(), o.position().z(),
                o.actor(), o.actorName(), o.action(), o.before(), observed, evidence, o.dataVersion());
        pending.group = new CaptureGroup(pending.sequence, o.time(), o.tick(), List.of(change));
    }

    private void drain() {
        while (!ordered.isEmpty() && ordered.getFirst().resolved) {
            Pending pending = ordered.removeFirst();
            if (pending.group != null) history.accept(pending.group);
        }
    }
    public void abandon() {
        for (Pending pending : ordered) if (!pending.resolved) resolve(pending, null, "LATER_SAMPLE");
        drain();
    }
    public int pending() { return ordered.size(); }
    public long observedSequence() { return sequence; }
}
