package io.github.d3vx3n0.chronicle.recovery;

import static org.junit.jupiter.api.Assertions.*;
import io.github.d3vx3n0.chronicle.query.HistoryRow;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import org.junit.jupiter.api.Test;

class RecoveryPlannerTest {
    private static HistoryRow row(long id, boolean place) {
        return new HistoryRow(id, 1, id, 0, 1000 - id, "00000000-0000-0000-0000-000000000001", 0, 80, 0,
                "00000000-0000-0000-0000-000000000002", "Test", place ? "PLACE" : "BREAK",
                place ? "minecraft:air" : "minecraft:stone", place ? "minecraft:stone" : "minecraft:air", 1, 5023,
                "LATER_SAMPLE", "PLAYER", "PLAYER_ACTION", "DIRECT");
    }
    @Test void randomPartialSelectionsMatchIndependentSuffixOracleDespiteBackwardsClocks() {
        var random = new Random(731);
        for (int run = 0; run < 500; run++) {
            int count = random.nextInt(40) + 1;
            var evidence = new ArrayList<RecoveryPlanner.Evidence>();
            var expected = new ArrayList<Long>();
            boolean interrupted = false;
            for (int id = count; id > 0; id--) {
                boolean select = random.nextBoolean();
                if (!select) { interrupted = true; continue; }
                if (!interrupted) expected.add((long) id);
                evidence.add(new RecoveryPlanner.Evidence(row(id, id % 2 == 1), 1, id - 1, count, true, false));
            }
            assertEquals(expected, RecoveryPlanner.rollback(evidence).stream().filter(RecoveryPlan.Step::eligible).map(RecoveryPlan.Step::source).toList());
        }
    }
    @Test void groupsAndUnknownSnapshotsBlockDependencies() {
        var rows = List.of(new RecoveryPlanner.Evidence(row(2, false), 2, 1, 2, true, false),
                new RecoveryPlanner.Evidence(row(1, true), 1, 0, 2, true, false));
        assertTrue(RecoveryPlanner.rollback(rows).stream().noneMatch(RecoveryPlan.Step::eligible));
    }
}
