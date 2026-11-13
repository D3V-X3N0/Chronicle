package io.github.d3vx3n0.chronicle.recovery;

import static org.junit.jupiter.api.Assertions.*;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.Test;

class SliceBudgetTest {
    @Test void yieldsAtTimeOrCountAndDoesNotHideSingleCallOverrun() {
        var clock = new AtomicLong(); var budget = new SliceBudget(50, 2_000_000, clock::get);
        assertTrue(budget.take()); clock.set(3_000_000); assertFalse(budget.take());
        assertEquals(3_000_000, budget.elapsed()); assertEquals(1, budget.steps());
        budget = new SliceBudget(2, 2_000_000, clock::get);
        assertTrue(budget.take()); assertTrue(budget.take()); assertFalse(budget.take());
    }
}
