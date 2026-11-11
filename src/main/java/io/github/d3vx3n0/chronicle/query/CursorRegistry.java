package io.github.d3vx3n0.chronicle.query;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import java.util.function.LongSupplier;

/** Server-thread-only, at most one next-page context per requester and 128 globally. */
public final class CursorRegistry {
    public static final long TTL_NANOS = 120_000_000_000L;
    public record Cursor(UUID token, UUID owner, String permission, QueryService.Continuation continuation, int limit, long expires) {}
    private final Map<UUID, Cursor> cursors = new HashMap<>();
    private final LongSupplier clock;
    public CursorRegistry() { this(System::nanoTime); }
    public CursorRegistry(LongSupplier clock) { this.clock = clock; }
    public Cursor create(UUID owner, String permission, LookupPage page, int limit, long expires) {
        purge(); cursors.remove(owner);
        if (!page.more()) return null;
        if (cursors.size() >= 128 || expires <= clock.getAsLong()) throw new IllegalArgumentException("Cursor capacity or expiry reached; start a new lookup");
        var cursor = new Cursor(UUID.randomUUID(), owner, permission,
                new QueryService.Continuation(page.filter(), page.boundary(), page.beforeNext()), limit, expires);
        cursors.put(owner, cursor); return cursor;
    }
    public Cursor get(UUID owner, String token) {
        purge(); Cursor cursor = cursors.get(owner);
        if (cursor == null || !cursor.token.toString().equals(token)) throw new IllegalArgumentException("Invalid, foreign or expired cursor; start a new lookup");
        return cursor;
    }
    public String token(UUID owner) { purge(); var cursor = cursors.get(owner); return cursor == null ? "" : cursor.token.toString(); }
    public void clear(UUID owner) { cursors.remove(owner); }
    public void clear() { cursors.clear(); }
    public void purge() { long now = clock.getAsLong(); cursors.values().removeIf(cursor -> now >= cursor.expires); }
}
