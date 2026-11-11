package io.github.d3vx3n0.chronicle.storage;

import io.github.d3vx3n0.chronicle.model.CaptureGroup;
import java.io.IOException;
import java.sql.SQLException;
import java.time.Duration;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.function.Consumer;

/** One connection-owning worker. Synchronization covers memory bookkeeping only, never I/O.
 * Pending accounting includes the in-flight transaction until acknowledgement.
 */
public final class HistoryWriter {
    public enum State { STARTING, READY, FAILED, STOPPING, CLOSED, TIMED_OUT }
    public record Status(State state, long session, String engine, long accepted, long committed, long committedSequence,
            long committedId, int pendingRecords, long pendingBytes, long oldestMillis, long rejected,
            long previousUnclean, long previousCoverageIncidents, List<Incident> incidents, String failure) {}
    @FunctionalInterface public interface StoreFactory { HistoryStore open() throws IOException, SQLException; }

    private final StorageSettings settings;
    private final StoreFactory factory;
    private final Consumer<String> alerts;
    private final String runtime;
    private final ArrayDeque<CaptureGroup> queue = new ArrayDeque<>();
    private final ArrayDeque<Long> admissionTimes = new ArrayDeque<>();
    private final java.util.function.LongSupplier ageClock;
    private List<CaptureGroup> inFlight = List.of(); // Retained on failure until lifecycle teardown, still accounted.
    private final EnumMap<Coverage, Incident> incidents = new EnumMap<>(Coverage.class);
    private final Thread worker;
    private State state = State.STARTING;
    private long session, accepted, committed, committedSequence, committedId, pendingBytes, rejected, previousUnclean, previousCoverageIncidents;
    private int pendingRecords;
    private long oldestNanos, lastAdmitted, incidentRevision, persistedIncidentRevision;
    private long stopDeadline = Long.MAX_VALUE;
    private boolean stopping;
    private String engine = "unavailable", failure = "none";

    public HistoryWriter(StorageSettings settings, StoreFactory factory, String runtime, Consumer<String> alerts) {
        this(settings, factory, runtime, alerts, System::nanoTime);
    }
    HistoryWriter(StorageSettings settings, StoreFactory factory, String runtime, Consumer<String> alerts,
            java.util.function.LongSupplier ageClock) {
        this.settings = settings; this.factory = factory; this.runtime = runtime; this.alerts = alerts;
        this.ageClock = ageClock;
        note(Coverage.STARTUP, 0);
        worker = Thread.ofPlatform().daemon().name("chronicle-sqlite-writer").start(this::run);
    }

    /** Non-blocking admission with respect to storage. Rejected work is never retained. */
    public synchronized boolean offer(CaptureGroup group) {
        if (state != State.READY || stopping) { rejected++; note(Coverage.STORAGE_FAILURE, group.sequence()); return false; }
        if (group.sequence() <= lastAdmitted) throw new IllegalArgumentException("Capture groups must be admitted in increasing order");
        long bytes = group.accountedBytes();
        if (group.changes().size() > settings.maxRecords() - pendingRecords || bytes > settings.maxBytes() - pendingBytes) {
            rejected++; note(Coverage.QUEUE_FULL, group.sequence()); return false;
        }
        lastAdmitted = group.sequence();
        queue.addLast(group); accepted += group.changes().size(); pendingRecords += group.changes().size(); pendingBytes += bytes;
        admissionTimes.addLast(ageClock.getAsLong()); oldestNanos = admissionTimes.getFirst();
        notifyAll(); return true;
    }

    public synchronized void note(Coverage reason, long sequence) {
        long now = System.currentTimeMillis();
        incidents.compute(reason, (key, old) -> old == null ? new Incident(key, 1, now, now, sequence, sequence) : old.add(now, sequence));
        incidentRevision++; notifyAll();
    }
    public synchronized Status status() {
        return new Status(state, session, engine, accepted, committed, committedSequence, committedId, pendingRecords, pendingBytes,
                admissionTimes.isEmpty() ? 0 : Duration.ofNanos(ageClock.getAsLong() - oldestNanos).toMillis(), rejected, previousUnclean,
                previousCoverageIncidents, List.copyOf(incidents.values()), failure);
    }

    /** Lifecycle-only bounded wait. The worker never depends on a Paper callback. */
    public boolean stop() {
        long deadline = System.nanoTime() + Duration.ofMillis(settings.shutdownMillis()).toNanos();
        synchronized (this) {
            stopping = true; stopDeadline = deadline;
            if (state == State.READY || state == State.STARTING) state = State.STOPPING;
            notifyAll();
        }
        try {
            long remaining;
            while (worker.isAlive() && (remaining = deadline - System.nanoTime()) > 0) {
                worker.join(Math.max(1, Math.min(100, Duration.ofNanos(remaining).toMillis())));
            }
        } catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); }
        synchronized (this) {
            if (worker.isAlive()) {
                state = State.TIMED_OUT; note(Coverage.SHUTDOWN_TIMEOUT, lastAdmitted);
                failure = "Shutdown deadline exceeded; pending work is not acknowledged";
                return false;
            }
            return state == State.CLOSED;
        }
    }

    private void run() {
        try (HistoryStore store = factory.open()) {
            long unclean = store.previousUnclean();
            long priorIncidents = store.previousCoverageIncidents();
            long uncertainStart = store.earliestUncleanStart();
            long id = store.startSession(System.currentTimeMillis(), runtime);
            synchronized (this) {
                session = id; engine = store.engineVersion(); previousUnclean = unclean; previousCoverageIncidents = priorIncidents;
                long now = System.currentTimeMillis();
                var startup = incidents.get(Coverage.STARTUP);
                incidents.put(Coverage.STARTUP, new Incident(Coverage.STARTUP, 1, startup.firstTime(), now, 0, 0));
                if (unclean > 0) {
                    incidents.put(Coverage.PREVIOUS_UNCLEAN, new Incident(Coverage.PREVIOUS_UNCLEAN, unclean, uncertainStart, now, 0, 0));
                    incidentRevision++;
                }
            }
            persist(store, List.of(), snapshotIncidents(), false);
            synchronized (this) { if (!stopping) state = State.READY; }
            int announcedReasons = 0;
            while (true) {
                List<CaptureGroup> batch = new ArrayList<>();
                List<Incident> control;
                long revision;
                boolean finish;
                synchronized (this) {
                    if (expired()) break;
                    if (queue.isEmpty() && incidentRevision == persistedIncidentRevision && !stopping) wait(settings.flushMillis());
                    if (expired()) break;
                    int records = 0; long bytes = 0;
                    while (!queue.isEmpty()) {
                        CaptureGroup next = queue.getFirst();
                        if (!batch.isEmpty() && (records + next.changes().size() > settings.batchRecords()
                                || bytes + next.accountedBytes() > settings.batchBytes())) break;
                        batch.add(queue.removeFirst()); records += next.changes().size(); bytes += next.accountedBytes();
                    }
                    control = List.copyOf(incidents.values()); revision = incidentRevision;
                    inFlight = List.copyOf(batch);
                    finish = stopping && batch.isEmpty() && pendingRecords == 0;
                    if (!finish && batch.isEmpty() && revision == persistedIncidentRevision) continue;
                }
                long position = persist(store, batch, control, finish);
                synchronized (this) {
                    for (CaptureGroup group : batch) {
                        committed += group.changes().size(); pendingRecords -= group.changes().size(); pendingBytes -= group.accountedBytes();
                        admissionTimes.removeFirst();
                        committedSequence = group.sequence();
                    }
                    committedId = position; persistedIncidentRevision = revision;
                    inFlight = List.of();
                    oldestNanos = admissionTimes.isEmpty() ? 0 : admissionTimes.getFirst();
                }
                if (control.size() > announcedReasons) {
                    alerts.accept("Chronicle coverage limits: " + control.stream().map(value -> value.reason().name()).toList());
                    announcedReasons = control.size();
                }
                if (finish) {
                    synchronized (this) {
                        if (!expired()) { state = State.CLOSED; return; }
                    }
                    // A final seal that returned after the deadline is not a clean-stop acknowledgement.
                    store.write(List.of(), snapshotIncidents(), false, 0);
                    break;
                }
            }
        } catch (IOException | SQLException | RuntimeException | LinkageError failureCause) {
            synchronized (this) {
                if (state != State.TIMED_OUT) state = State.FAILED;
                failure = failureCause.getClass().getSimpleName() + ": " + failureCause.getMessage();
                note(Coverage.STORAGE_FAILURE, lastAdmitted);
            }
            alerts.accept("Chronicle storage failed; history coverage is incomplete: " + failureCause.getMessage());
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            synchronized (this) { state = State.FAILED; failure = "Writer interrupted; session unclean"; }
        }
    }

    private synchronized boolean expired() {
        if (state == State.TIMED_OUT) return true;
        if (stopping && System.nanoTime() >= stopDeadline) {
            state = State.TIMED_OUT; note(Coverage.SHUTDOWN_TIMEOUT, lastAdmitted);
            failure = "Shutdown deadline exceeded; session unclean"; return true;
        }
        return false;
    }
    private synchronized List<Incident> snapshotIncidents() { return List.copyOf(incidents.values()); }

    private long persist(HistoryStore store, List<CaptureGroup> batch, List<Incident> control, boolean clean) throws SQLException, InterruptedException {
        // Reuse exactly the same immutable keys/content on BUSY/LOCKED only. Other errors fail closed.
        for (int attempt = 0; ; attempt++) {
            if (expired()) throw new SQLException("Shutdown deadline expired before transaction");
            try { return store.write(batch, control, clean, System.currentTimeMillis()); }
            catch (SQLException error) {
                int primary = error.getErrorCode() & 255;
                if (attempt >= 2 || primary != 5 && primary != 6) throw error;
                Thread.sleep(25L * (attempt + 1));
            }
        }
    }
}
