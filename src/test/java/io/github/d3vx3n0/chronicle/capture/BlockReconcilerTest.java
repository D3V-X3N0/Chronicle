package io.github.d3vx3n0.chronicle.capture;

import static org.junit.jupiter.api.Assertions.*;

import io.github.d3vx3n0.chronicle.model.CaptureGroup;
import io.github.d3vx3n0.chronicle.storage.Coverage;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class BlockReconcilerTest {
    private final List<CaptureGroup> history = new ArrayList<>();
    private final List<Coverage> gaps = new ArrayList<>();
    private final BlockReconciler reconciler = new BlockReconciler(2, history::add, (reason, sequence) -> gaps.add(reason));
    private static BlockReconciler.Observation observation(int x, String action, String before, String proposed) {
        return new BlockReconciler.Observation(new BlockReconciler.Position(new UUID(0, 1), x, 80, -1), "world",
                new UUID(0, 2), "Test", action, before, proposed, 10, 123, 5023);
    }
    private void place(int x) { reconciler.observe(observation(x, "PLACE", "minecraft:air", "minecraft:stone"), false, true, false); }
    @Test void sameTickRoundTripPreservesIntermediateStateAndOrder() {
        reconciler.observe(observation(0, "BREAK", "minecraft:stone", "minecraft:stone"), false, true, false);
        place(0);
        assertEquals(1, history.size());
        assertEquals("minecraft:air", history.getFirst().changes().getFirst().after());
        assertEquals("NEXT_OBSERVATION", history.getFirst().changes().getFirst().evidence());
        reconciler.sample(11, position -> "minecraft:stone");
        assertEquals(List.of(1L, 2L), history.stream().map(CaptureGroup::sequence).toList());
        assertEquals(List.of("BREAK", "PLACE"), history.stream().map(group -> group.changes().getFirst().action()).toList());
        assertTrue(gaps.isEmpty());
    }
    @Test void cancelledDeniedAndAirNoopNeverBecomeChanges() {
        var place = observation(0, "PLACE", "minecraft:air", "minecraft:stone");
        reconciler.observe(place, true, true, false); reconciler.observe(place, false, false, false);
        reconciler.observe(observation(0, "BREAK", "minecraft:air", "minecraft:air"), false, true, false);
        reconciler.sample(11, position -> "minecraft:stone");
        assertTrue(history.isEmpty()); assertTrue(gaps.isEmpty());
    }
    @Test void finalSampleIsNotInventedAndUnchangedStateIsNotSuccess() {
        reconciler.observe(observation(0, "BREAK", "minecraft:stone", "minecraft:stone"), false, true, false);
        assertTrue(history.isEmpty());
        reconciler.sample(11, position -> "minecraft:stone");
        assertTrue(history.isEmpty()); assertTrue(gaps.isEmpty());
        place(0); reconciler.sample(11, position -> "minecraft:dirt");
        assertEquals(List.of(Coverage.AMBIGUOUS), gaps); assertTrue(history.isEmpty());
    }
    @Test void laterResolvedLocationCannotOvertakeEarlierPendingLocation() {
        place(0); place(1);
        reconciler.observe(observation(1, "BREAK", "minecraft:stone", "minecraft:stone"), true, true, false);
        assertTrue(history.isEmpty());
        reconciler.sample(11, position -> "minecraft:stone");
        assertEquals(List.of(1L, 2L), history.stream().map(CaptureGroup::sequence).toList());
    }
    @Test void capacityExpiryUnavailableChunksAndShutdownAreExplicit() {
        place(0); place(1); place(2);
        assertEquals(2, reconciler.pending()); assertEquals(List.of(Coverage.RECONCILIATION_FULL), gaps);
        reconciler.sample(13, position -> { throw new AssertionError("Expired observation must not read world"); });
        assertEquals(0, reconciler.pending()); assertEquals(3, gaps.size());
        place(0); reconciler.sample(11, position -> null); assertEquals(4, gaps.size());
        place(0); reconciler.abandon(); assertEquals(5, gaps.size()); assertTrue(history.isEmpty());
    }
    @Test void replacementAndMultiPlaceAreUnsupportedWithoutPartialGroups() {
        reconciler.observe(observation(0, "PLACE", "minecraft:short_grass", "minecraft:stone"), false, true, false);
        place(1);
        reconciler.observe(observation(0, "PLACE", "minecraft:air", "minecraft:stone"), false, true, true);
        assertEquals(List.of(Coverage.UNSUPPORTED, Coverage.AMBIGUOUS, Coverage.UNSUPPORTED), gaps);
        assertTrue(history.isEmpty()); assertEquals(0, reconciler.pending());
    }
    @Test void sameTickSampleCannotResolveBeforeActionCompletes() {
        place(0); reconciler.sample(10, position -> { throw new AssertionError("Callback tick is not completion evidence"); });
        assertEquals(1, reconciler.pending()); assertTrue(history.isEmpty());
    }
    @Test void reservedRecoverySlotPreservesUnrelatedNestedCaptureAndCannotBeAbandonedByMultiPlace() {
        long reserved = reconciler.reserveSynthetic();
        place(1); reconciler.sample(11, p -> "minecraft:stone"); assertTrue(history.isEmpty());
        UUID operation = new UUID(0, 3);
        var audit = new io.github.d3vx3n0.chronicle.model.BlockChange(new UUID(0, 1), "world", 0, 80, -1, operation, "Chronicle", "BREAK",
                "minecraft:stone", "minecraft:air", "RECOVERY", 5023);
        reconciler.finishSynthetic(reserved, new CaptureGroup(reserved, 120, 10, List.of(audit),
                new io.github.d3vx3n0.chronicle.recovery.RecoveryOutcome(operation, 0, audit.before(), audit.after())));
        reconciler.sample(11, p -> "minecraft:stone");
        assertEquals(List.of(1L, 2L), history.stream().map(CaptureGroup::sequence).toList());
        assertEquals("Test", history.getLast().changes().getFirst().actorName());
        reserved = reconciler.reserveSynthetic();
        reconciler.observe(observation(0, "PLACE", "minecraft:air", "minecraft:stone"), false, true, true);
        assertEquals(1, reconciler.pending());
        reconciler.finishSynthetic(reserved, null); assertEquals(0, reconciler.pending());
        assertEquals(List.of(Coverage.UNSUPPORTED), gaps);
    }
}
