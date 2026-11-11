package io.github.d3vx3n0.chronicle.query;

/** Bounded display evidence. It is never a recovery codec. */
public record HistoryRow(long id, long session, long sequence, int member, long occurredAt,
        String world, int x, int y, int z, String actor, String name, String action,
        String before, String after, int codec, int dataVersion, String evidence,
        String actorKind, String causeKind, String quality) {
    public boolean understoodSnapshot() {
        return codec == 1 && dataVersion == 5023 && (action.equals("PLACE") || action.equals("BREAK"))
                && before.equals(action.equals("PLACE") ? "minecraft:air" : "minecraft:stone")
                && after.equals(action.equals("PLACE") ? "minecraft:stone" : "minecraft:air")
                && (evidence.equals("NEXT_OBSERVATION") || evidence.equals("LATER_SAMPLE"));
    }
    public String attribution() {
        return validIdentity(actor) && validIdentity(world) && actorKind.equals("PLAYER") && causeKind.equals("PLAYER_ACTION") && quality.equals("DIRECT")
                ? "PLAYER / PLAYER_ACTION / DIRECT" : "UNKNOWN attribution/identity (" + actorKind + "/" + causeKind + "/" + quality + ")";
    }
    public static boolean validIdentity(String identity) {
        return identity != null && identity.matches("[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}");
    }
}
