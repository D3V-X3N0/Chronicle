package io.github.d3vx3n0.chronicle.model;

import java.util.List;

/** One sequence/group, admitted and committed as a whole. Member order is list order. */
public record CaptureGroup(long sequence, long occurredAt, int tick, List<BlockChange> changes) {
    public CaptureGroup {
        if (sequence < 1 || changes.isEmpty() || changes.size() > 10_000) throw new IllegalArgumentException("Invalid group bounds");
        changes = List.copyOf(changes);
        for (BlockChange change : changes) if (change.accountedBytes() > 256L << 10) throw new IllegalArgumentException("Oversized snapshot");
    }
    public long accountedBytes() { return 128L + changes.stream().mapToLong(BlockChange::accountedBytes).sum(); }
}
