package io.github.d3vx3n0.chronicle.fixture;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.UUID;
import org.junit.jupiter.api.Test;

class ObservationLogTest {
    private static final UUID ACTOR = UUID.fromString("bbad96a7-3666-4164-b160-45842390be7a");
    private static final ObservationLog.Position POSITION = new ObservationLog.Position(ACTOR, -17, 64, -1);

    @Test
    void preservesSameTickSequenceAndDoesNotTreatLaterSampleAsCommittedState() {
        var log = new ObservationLog(4);
        long first = log.observe("BREAK_ATTEMPT", ACTOR, POSITION, 12, false, true, "stone", "stone");
        long second = log.observe("PLACE_ATTEMPT", ACTOR, POSITION, 12, false, true, "air", "stone");
        log.sample(second, "stone", 13, "LATER_SAMPLE_ONLY");
        log.sample(first, "stone", 13, "LATER_SAMPLE_ONLY");
        var entries = log.snapshot();
        assertEquals(first, entries.get(0).sequence());
        assertEquals(second, entries.get(1).sequence());
        assertEquals("stone", entries.get(0).before());
        assertEquals("air", entries.get(1).before());
        assertEquals("LATER_SAMPLE_ONLY", entries.get(0).outcome());
    }

    @Test
    void capacityRejectsWithoutEvictingExistingEvidence() {
        var log = new ObservationLog(1);
        long first = log.observe("PLACE_ATTEMPT", ACTOR, POSITION, 1, true, false, "air", "stone");
        assertEquals(-1, log.observe("PLACE_ATTEMPT", ACTOR, POSITION, 2, false, true, "air", "stone"));
        assertEquals(1, log.rejected());
        assertEquals(first, log.snapshot().getFirst().sequence());
        assertEquals(true, log.snapshot().getFirst().cancelled());
        assertEquals(false, log.snapshot().getFirst().canBuild());
    }

    @Test
    void unavailableChunkRemainsUnknownAndDuplicateCompletionCannotReplaceEvidence() {
        var log = new ObservationLog(1);
        long id = log.observe("BREAK_ATTEMPT", ACTOR, POSITION, 1, false, true, "stone", "stone");
        log.sample(id, null, 2, "WORLD_OR_CHUNK_UNAVAILABLE");
        log.sample(id, "air", 3, "LATER_SAMPLE_ONLY");
        assertNull(log.snapshot().getFirst().later());
        assertEquals("WORLD_OR_CHUNK_UNAVAILABLE", log.snapshot().getFirst().outcome());
    }

    @Test
    void invalidCapacityIsRejected() {
        assertThrows(IllegalArgumentException.class, () -> new ObservationLog(0));
        assertThrows(IllegalArgumentException.class, () -> new ObservationLog(4097));
    }
}
