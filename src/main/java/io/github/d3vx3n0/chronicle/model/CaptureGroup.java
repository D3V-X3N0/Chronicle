package io.github.d3vx3n0.chronicle.model;

import java.util.List;

/** One sequence/group, admitted and committed as a whole. Member order is list order. */
public record CaptureGroup(long sequence, long occurredAt, int tick, List<BlockChange> changes,
        io.github.d3vx3n0.chronicle.recovery.RecoveryOutcome outcome) {
    public CaptureGroup(long sequence, long occurredAt, int tick, List<BlockChange> changes) { this(sequence, occurredAt, tick, changes, null); }
    public CaptureGroup {
        if (sequence < 1 || changes.isEmpty() || changes.size() > 10_000) throw new IllegalArgumentException("Invalid group bounds");
        changes = List.copyOf(changes);
        if (outcome != null && (changes.size() != 1 || !changes.getFirst().evidence().equals("RECOVERY")
                || !changes.getFirst().before().equals(outcome.before()) || !changes.getFirst().after().equals(outcome.after())
                || !changes.getFirst().actor().equals(outcome.operation()))) throw new IllegalArgumentException("Outcome/audit mismatch");
        if (outcome == null && changes.stream().anyMatch(c -> c.evidence().equals("RECOVERY"))) throw new IllegalArgumentException("Missing journal outcome");
        for (BlockChange change : changes) if (change.accountedBytes() > 256L << 10) throw new IllegalArgumentException("Oversized snapshot");
    }
    public long accountedBytes() { return 128L + changes.stream().mapToLong(BlockChange::accountedBytes).sum(); }
}
