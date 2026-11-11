package io.github.d3vx3n0.chronicle.storage;

import static org.junit.jupiter.api.Assertions.*;

import io.github.d3vx3n0.chronicle.model.CaptureGroup;
import io.github.d3vx3n0.chronicle.storage.sqlite.SqliteStore;
import java.nio.file.Path;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

@Tag("database")
class HistoryWriterTest {
    @TempDir Path directory;
    private static StorageSettings settings(int records, long bytes, int shutdown) { return new StorageSettings(records, bytes, 1, 512, 10, 30, shutdown, 2); }
    static void await(BooleanSupplier condition) throws Exception {
        long deadline = System.nanoTime() + Duration.ofSeconds(5).toNanos();
        while (!condition.getAsBoolean() && System.nanoTime() < deadline) Thread.sleep(5);
        assertTrue(condition.getAsBoolean(), "condition did not complete within deadline");
    }
    private HistoryWriter writer(Path db, StorageSettings limits) { return new HistoryWriter(limits, () -> new SqliteStore(db, limits.busyMillis()), "test", message -> {}); }
    @Test void cleanDrainAndRestartKeepHistoryAndDurabilityPosition() throws Exception {
        Path db = directory.resolve("history.db");
        var writer = writer(db, settings(10, 100_000, 3000));
        await(() -> writer.status().state() == HistoryWriter.State.READY);
        assertTrue(writer.offer(SqliteStoreTest.group(1, 2))); assertTrue(writer.offer(SqliteStoreTest.group(2, 2)));
        assertTrue(writer.stop());
        assertEquals(4, writer.status().committed()); assertEquals(2, writer.status().committedSequence()); assertEquals(0, writer.status().pendingRecords());
        assertEquals("1", SqliteStoreTest.query(db, "SELECT clean FROM sessions WHERE id=1"));
        var reopened = writer(db, settings(10, 100_000, 3000));
        await(() -> reopened.status().state() == HistoryWriter.State.READY);
        assertEquals(0, reopened.status().previousUnclean()); assertTrue(reopened.stop());
        assertEquals("4", SqliteStoreTest.query(db, "SELECT count(*) FROM changes"));
    }
    @Test void inFlightCountsBytesAndWholeGroupAdmissionStayBounded() throws Exception {
        for (boolean byteLimit : List.of(false, true)) {
            Path db = directory.resolve(byteLimit + ".db");
            var entered = new CountDownLatch(1); var release = new CountDownLatch(1);
            var limits = settings(byteLimit ? 10 : 2, byteLimit ? SqliteStoreTest.group(1, 2).accountedBytes() : 100_000, 3000);
            var writer = new HistoryWriter(limits, () -> new DelayedStore(new SqliteStore(db, 30), entered, release, false), "test", message -> {});
            try {
                await(() -> writer.status().state() == HistoryWriter.State.READY);
                assertTrue(writer.offer(SqliteStoreTest.group(1, 2))); assertTrue(entered.await(3, TimeUnit.SECONDS));
                assertFalse(writer.offer(SqliteStoreTest.group(2, 1)));
                assertEquals(2, writer.status().pendingRecords()); assertEquals(0, writer.status().committed());
                assertTrue(writer.status().pendingBytes() <= limits.maxBytes()); assertEquals(1, writer.status().rejected());
            } finally { release.countDown(); assertTrue(writer.stop()); }
            assertEquals("2", SqliteStoreTest.query(db, "SELECT count(*) FROM changes"));
            assertEquals("1", SqliteStoreTest.query(db, "SELECT count FROM coverage_incidents WHERE reason='QUEUE_FULL'"));
        }
    }
    @Test void realBusyLockHasFiniteFailureAndRetainsPendingUncertainty() throws Exception {
        Path db = directory.resolve("history.db"); var writer = writer(db, settings(10, 100_000, 3000));
        await(() -> writer.status().state() == HistoryWriter.State.READY);
        try (var connection = DriverManager.getConnection("jdbc:sqlite:" + db); var statement = connection.createStatement()) {
            statement.execute("BEGIN IMMEDIATE"); assertTrue(writer.offer(SqliteStoreTest.group(1, 1)));
            await(() -> writer.status().state() == HistoryWriter.State.FAILED);
            assertEquals(1, writer.status().pendingRecords()); assertEquals(0, writer.status().committed());
            assertFalse(writer.offer(SqliteStoreTest.group(2, 1))); statement.execute("ROLLBACK");
        } finally { assertFalse(writer.stop()); }
        assertEquals("0", SqliteStoreTest.query(db, "SELECT clean FROM sessions"));
        var next = writer(db, settings(10, 100_000, 3000)); await(() -> next.status().state() == HistoryWriter.State.READY);
        assertEquals(1, next.status().previousUnclean()); assertTrue(next.stop());
    }
    @Test void forcedShutdownDeadlineIsBoundedAndCannotLaterSealClean() throws Exception {
        Path db = directory.resolve("history.db"); var entered = new CountDownLatch(1); var release = new CountDownLatch(1);
        var writer = new HistoryWriter(settings(10, 100_000, 50), () -> new DelayedStore(new SqliteStore(db, 30), entered, release, false), "test", message -> {});
        await(() -> writer.status().state() == HistoryWriter.State.READY);
        writer.offer(SqliteStoreTest.group(1, 1)); assertTrue(entered.await(3, TimeUnit.SECONDS));
        long start = System.nanoTime(); assertFalse(writer.stop());
        assertTrue(Duration.ofNanos(System.nanoTime() - start).toMillis() < 1000);
        assertEquals(HistoryWriter.State.TIMED_OUT, writer.status().state()); release.countDown();
        await(() -> writer.status().committed() == 1);
        assertEquals("0", SqliteStoreTest.query(db, "SELECT clean FROM sessions"));
    }
    @Test void ioFailureIsVisibleWithoutAcknowledgingOrEvictingAcceptedHistory() throws Exception {
        Path db = directory.resolve("history.db"); var entered = new CountDownLatch(1); var release = new CountDownLatch(0);
        var writer = new HistoryWriter(settings(10, 100_000, 3000), () -> new DelayedStore(new SqliteStore(db, 30), entered, release, true), "test", message -> {});
        await(() -> writer.status().state() == HistoryWriter.State.READY); writer.offer(SqliteStoreTest.group(1, 1));
        await(() -> writer.status().state() == HistoryWriter.State.FAILED);
        assertFalse(writer.stop()); assertEquals(1, writer.status().pendingRecords()); assertEquals(0, writer.status().committed());
        assertTrue(writer.status().failure().contains("injected I/O")); assertEquals("0", SqliteStoreTest.query(db, "SELECT count(*) FROM changes"));
    }
    @Test void oldestPendingAgeAdvancesAfterEarlierBatchCommits() throws Exception {
        Path db = directory.resolve("age.db");
        var firstEntered = new CountDownLatch(1); var firstRelease = new CountDownLatch(1);
        var secondEntered = new CountDownLatch(1); var secondRelease = new CountDownLatch(1);
        var clock = new java.util.concurrent.atomic.AtomicLong(1_000_000_000L);
        var writer = new HistoryWriter(settings(10, 100_000, 3000), () -> new DelayedStore(new SqliteStore(db, 30),
                firstEntered, firstRelease, false) {
            @Override public long write(List<CaptureGroup> groups, List<Incident> incidents, boolean clean, long stoppedAt) throws SQLException {
                if (!groups.isEmpty() && groups.getFirst().sequence() == 2) {
                    secondEntered.countDown();
                    try { if (!secondRelease.await(5, TimeUnit.SECONDS)) throw new SQLException("second gate timeout"); }
                    catch (InterruptedException error) { Thread.currentThread().interrupt(); throw new SQLException(error); }
                }
                return super.write(groups, incidents, clean, stoppedAt);
            }
        }, "test", message -> {}, clock::get);
        try {
            await(() -> writer.status().state() == HistoryWriter.State.READY);
            assertTrue(writer.offer(SqliteStoreTest.group(1, 1))); assertTrue(firstEntered.await(3, TimeUnit.SECONDS));
            clock.set(3_000_000_000L); assertTrue(writer.offer(SqliteStoreTest.group(2, 1)));
            clock.set(5_000_000_000L); assertEquals(4000, writer.status().oldestMillis());
            firstRelease.countDown(); assertTrue(secondEntered.await(3, TimeUnit.SECONDS));
            assertEquals(1, writer.status().committed()); assertEquals(2000, writer.status().oldestMillis());
        } finally { firstRelease.countDown(); secondRelease.countDown(); assertTrue(writer.stop()); }
    }
    private static class DelayedStore implements HistoryStore {
        private final SqliteStore delegate;
        private final CountDownLatch entered, release;
        private final boolean fail;
        DelayedStore(SqliteStore delegate, CountDownLatch entered, CountDownLatch release, boolean fail) {
            this.delegate = delegate; this.entered = entered; this.release = release; this.fail = fail;
        }
        @Override public String engineVersion() { return delegate.engineVersion(); }
        @Override public long previousUnclean() throws SQLException { return delegate.previousUnclean(); }
        @Override public long previousCoverageIncidents() throws SQLException { return delegate.previousCoverageIncidents(); }
        @Override public long earliestUncleanStart() throws SQLException { return delegate.earliestUncleanStart(); }
        @Override public long startSession(long time, String runtime) throws SQLException { return delegate.startSession(time, runtime); }
        @Override public long write(List<CaptureGroup> groups, List<Incident> incidents, boolean clean, long stoppedAt) throws SQLException {
            if (!groups.isEmpty()) {
                entered.countDown();
                try { if (!release.await(5, TimeUnit.SECONDS)) throw new SQLException("test gate timeout"); }
                catch (InterruptedException error) { Thread.currentThread().interrupt(); throw new SQLException(error); }
                if (fail) throw new SQLException("injected I/O failure", "", 10);
            }
            return delegate.write(groups, incidents, clean, stoppedAt);
        }
        @Override public void close() throws SQLException, java.io.IOException { delegate.close(); }
    }
}
