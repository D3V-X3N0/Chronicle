package io.github.d3vx3n0.chronicle.recovery;

import io.github.d3vx3n0.chronicle.capture.BlockReconciler.Position;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.List;
import java.util.UUID;

/** Immutable, bounded, versioned selection. Expected/desired states are never reconstructed at confirmation. */
public record RecoveryPlan(UUID id, String owner, String kind, UUID parent, long session, long barrier,
        long boundary, long created, long expires, String scope, List<Step> steps, UUID continuation) {
    public RecoveryPlan(UUID id, String owner, String kind, UUID parent, long session, long barrier,
            long boundary, long created, long expires, String scope, List<Step> steps) {
        this(id, owner, kind, parent, session, barrier, boundary, created, expires, scope, steps, null);
    }
    public static final int MAX_STEPS = 1000;
    public static final long TTL_MILLIS = 60_000;
    public record Step(int ordinal, long source, Integer parentStep, Position position, String expected,
            String desired, long latest, String reason) {
        public Step {
            if (ordinal < 0 || source < 1 || latest < source || expected.length() > 256 || desired.length() > 256
                    || reason.length() > 256) throw new IllegalArgumentException("Invalid recovery step");
        }
        public boolean eligible() { return reason.isEmpty(); }
    }
    public RecoveryPlan {
        steps = List.copyOf(steps);
        if (steps.isEmpty() || steps.size() > MAX_STEPS || owner.length() > 64 || scope.length() > 2048
                || !List.of("ROLLBACK", "RESTORE", "COMPENSATE").contains(kind) || session < 1 || expires <= created)
            throw new IllegalArgumentException("Invalid bounded plan");
        for (int i = 0; i < steps.size(); i++) if (steps.get(i).ordinal() != i) throw new IllegalArgumentException("Unordered plan");
    }
    public String digest() {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(toString().getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException impossible) { throw new AssertionError(impossible); }
    }
}
