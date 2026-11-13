package io.github.d3vx3n0.chronicle.query;

/** Bounded display evidence. It is never a recovery codec. */
public record HistoryRow(long id, long session, long sequence, int member, long occurredAt,
        String world, int x, int y, int z, String actor, String name, String action,
        String before, String after, int codec, int dataVersion, String evidence,
        String actorKind, String causeKind, String quality, String operation, Integer operationStep) {
    public HistoryRow(long id, long session, long sequence, int member, long occurredAt,
            String world, int x, int y, int z, String actor, String name, String action,
            String before, String after, int codec, int dataVersion, String evidence,
            String actorKind, String causeKind, String quality) {
        this(id, session, sequence, member, occurredAt, world, x, y, z, actor, name, action, before, after, codec, dataVersion,
                evidence, actorKind, causeKind, quality, null, null);
    }
    public boolean understoodSnapshot() {
        return codec == 1 && dataVersion == 5023 && (action.equals("PLACE") || action.equals("BREAK"))
                && before.equals(action.equals("PLACE") ? "minecraft:air" : "minecraft:stone")
                && after.equals(action.equals("PLACE") ? "minecraft:stone" : "minecraft:air")
                && (evidence.equals("NEXT_OBSERVATION") || evidence.equals("LATER_SAMPLE") || evidence.equals("RECOVERY") && validIdentity(operation));
    }
    public String attribution() {
        if (validIdentity(operation) && actor.equals(operation) && actorKind.equals("SYSTEM") && causeKind.equals("OPERATION") && quality.equals("EXACT"))
            return "SYSTEM / OPERATION / EXACT";
        return validIdentity(actor) && validIdentity(world) && actorKind.equals("PLAYER") && causeKind.equals("PLAYER_ACTION") && quality.equals("DIRECT")
                ? "PLAYER / PLAYER_ACTION / DIRECT" : "UNKNOWN attribution/identity (" + actorKind + "/" + causeKind + "/" + quality + ")";
    }
    public static boolean validIdentity(String identity) {
        return identity != null && identity.matches("[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}");
    }
}
