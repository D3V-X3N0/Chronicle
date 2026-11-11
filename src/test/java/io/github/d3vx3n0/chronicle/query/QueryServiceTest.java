package io.github.d3vx3n0.chronicle.query;

import static org.junit.jupiter.api.Assertions.*;

import java.util.List;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;

class QueryServiceTest {
    @Test void queuedRunningAndUndeliveredResultsShareOneBoundAndCancellationHidesDelivery() throws Exception {
        var started = new CountDownLatch(2); var release = new CountDownLatch(1);
        var input = new LookupInput(List.of(), null, 0, 100, Set.of("BREAK"), null);
        try (var service = new QueryService(() -> { started.countDown(); release.await(); throw new IllegalArgumentException("test completion"); }, ignored -> {})) {
            var cancelled = service.submit(input, null, 10); assertNotNull(cancelled);
            assertNotNull(service.submit(input, null, 10)); assertTrue(started.await(5, TimeUnit.SECONDS));
            for (int i = 0; i < 32; i++) assertNotNull(service.submit(input, null, 10));
            assertNull(service.submit(input, null, 10)); assertEquals(34, service.stats().retained()); assertEquals(32, service.stats().queued());
            cancelled.cancel(); release.countDown();
            long until = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
            while (service.stats().completed() < 34 && System.nanoTime() < until) Thread.sleep(5);
            assertEquals(34, service.stats().completed()); assertNull(service.submit(input, null, 10));
            var results = new java.util.ArrayList<QueryService.Result>();
            while (service.stats().retained() > 0) service.drain(results::add);
            assertEquals(33, results.size()); assertTrue(results.stream().allMatch(r -> r.page() == null && r.error() != null));
            assertEquals(0, service.stats().retained()); assertEquals(2, service.stats().rejected());
        } finally { release.countDown(); }
    }
}
