package io.github.d3vx3n0.chronicle.query;

import io.github.d3vx3n0.chronicle.storage.sqlite.SqliteReader;
import java.nio.file.Path;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Semaphore;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Consumer;

/** Two dedicated readers; permits include completed results until server-thread delivery. */
public final class QueryService implements AutoCloseable {
    public static final int CAPACITY = 34;
    public static final long DEADLINE_NANOS = 2_000_000_000L;
    public record Ticket(UUID id, AtomicBoolean cancelled) {
        public void cancel() { cancelled.set(true); }
    }
    public record Continuation(LookupFilter filter, long boundary, long before) {}
    public record Result(Ticket ticket, LookupPage page, String error) {}
    public record Stats(int retained, int queued, int completed, long rejected) {}
    @FunctionalInterface public interface ReaderFactory { SqliteReader open() throws Exception; }
    private record Work(Ticket ticket, LookupInput input, Continuation cursor, int limit, long deadline) {}
    private final ArrayBlockingQueue<Work> waiting = new ArrayBlockingQueue<>(32);
    private final ArrayBlockingQueue<Result> completed = new ArrayBlockingQueue<>(CAPACITY);
    private final Semaphore slots = new Semaphore(CAPACITY);
    private final ConcurrentHashMap<UUID, Ticket> tickets = new ConcurrentHashMap<>();
    private final AtomicLong rejected = new AtomicLong();
    private final AtomicBoolean closed = new AtomicBoolean();
    private final List<Thread> workers;
    private final ReaderFactory factory;
    private final Consumer<String> log;

    public QueryService(Path database, Consumer<String> log) { this(() -> new SqliteReader(database), log); }
    public QueryService(ReaderFactory factory, Consumer<String> log) {
        this.factory = factory; this.log = log;
        workers = java.util.stream.IntStream.range(0, 2).mapToObj(i -> Thread.ofPlatform().daemon()
                .name("chronicle-reader-" + i).start(this::run)).toList();
    }
    /** No storage wait; caller retains the ticket only, not a database future. */
    public Ticket submit(LookupInput input, Continuation cursor, int limit) {
        if ((input == null) == (cursor == null) || limit < 1 || limit > 50) throw new IllegalArgumentException("Invalid request");
        if (closed.get() || !slots.tryAcquire()) { rejected.incrementAndGet(); return null; }
        var ticket = new Ticket(UUID.randomUUID(), new AtomicBoolean());
        tickets.put(ticket.id, ticket);
        if (!waiting.offer(new Work(ticket, input, cursor, limit, System.nanoTime() + DEADLINE_NANOS))) {
            tickets.remove(ticket.id); slots.release(); rejected.incrementAndGet(); return null;
        }
        return ticket;
    }
    public Stats stats() { return new Stats(CAPACITY - slots.availablePermits(), waiting.size(), completed.size(), rejected.get()); }
    public void drain(Consumer<Result> receiver) {
        for (int i = 0; i < 8; i++) {
            Result result = completed.poll();
            if (result == null) return;
            tickets.remove(result.ticket.id); slots.release();
            if (!result.ticket.cancelled.get()) receiver.accept(result);
        }
    }
    private void run() {
        SqliteReader reader = null;
        try {
            while (!closed.get()) {
                Work work = waiting.take();
                LookupPage page = null; String error = null;
                try {
                    if (work.ticket.cancelled.get() || System.nanoTime() >= work.deadline) throw new IllegalArgumentException("Lookup expired while waiting; retry with a narrower scope");
                    if (reader == null) reader = factory.open();
                    if (work.cursor == null) page = reader.first(work.input, work.limit, work.ticket.cancelled::get, work.deadline);
                    else page = reader.next(work.cursor.filter, work.cursor.boundary, work.cursor.before, work.limit, work.ticket.cancelled::get, work.deadline);
                } catch (IllegalArgumentException invalid) { error = invalid.getMessage(); }
                catch (Exception | LinkageError failure) {
                    error = "History unavailable or query budget exceeded; narrow the filters and retry. Check Chronicle status.";
                    log.accept("Chronicle lookup failed: " + failure.getClass().getSimpleName() + ": " + failure.getMessage());
                    if (reader != null) try { reader.close(); } catch (Exception ignored) { /* Original failure is reported. */ }
                    reader = null;
                }
                if (!completed.offer(new Result(work.ticket, page, error))) throw new IllegalStateException("Completion admission accounting violated");
            }
        } catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); }
        finally { if (reader != null) try { reader.close(); } catch (Exception failure) { log.accept("Reader close failed: " + failure.getMessage()); } }
    }
    @Override public void close() {
        closed.set(true); tickets.values().forEach(Ticket::cancel); workers.forEach(Thread::interrupt);
        // No server-thread wait on a native reader. Daemon workers close on return from their bounded query.
    }
}
