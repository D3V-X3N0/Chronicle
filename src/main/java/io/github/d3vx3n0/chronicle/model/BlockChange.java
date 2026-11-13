package io.github.d3vx3n0.chronicle.model;

import java.util.Objects;
import java.util.UUID;

/** Copied, immutable evidence only. No Paper objects. No M1 row is recovery-eligible. */
public record BlockChange(UUID world, String worldName, int x, int y, int z, UUID actor, String actorName,
        String action, String before, String after, String evidence, int dataVersion) {
    public BlockChange {
        Objects.requireNonNull(world); Objects.requireNonNull(actor);
        bounded(worldName, 128); bounded(actorName, 64); bounded(before, 16_384); bounded(after, 16_384);
        if (!action.equals("PLACE") && !action.equals("BREAK")) throw new IllegalArgumentException("Unknown action");
        if (!evidence.equals("NEXT_OBSERVATION") && !evidence.equals("LATER_SAMPLE") && !evidence.equals("RECOVERY")) throw new IllegalArgumentException("Unknown evidence");
        if (before.equals(after) || dataVersion < 1) throw new IllegalArgumentException("No-op or missing data version");
        if (!(before.equals(action.equals("PLACE") ? "minecraft:air" : "minecraft:stone")
                && after.equals(action.equals("PLACE") ? "minecraft:stone" : "minecraft:air"))) {
            throw new IllegalArgumentException("Only qualified stone/air snapshots belong in M1 history");
        }
    }
    private static void bounded(String text, int max) {
        if (Objects.requireNonNull(text).isEmpty() || text.length() > max) throw new IllegalArgumentException("Invalid bounded field");
    }
    public long accountedBytes() { return 512L + 2L * (worldName.length() + actorName.length() + before.length() + after.length()); }
}
