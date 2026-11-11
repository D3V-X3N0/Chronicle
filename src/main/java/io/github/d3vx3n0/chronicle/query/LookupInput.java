package io.github.d3vx3n0.chronicle.query;

import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/** Syntax validation only; identity resolution belongs to the database worker. */
public record LookupInput(List<String> players, String world, long from, long to, Set<String> actions, LookupFilter.Area area) {
    public LookupInput {
        players = List.copyOf(players); actions = Set.copyOf(actions);
        if (players.size() > 8 || players.stream().anyMatch(s -> s.isEmpty() || s.length() > 64)
                || world != null && (world.isEmpty() || world.length() > 128) || from < 0 || to <= from
                || actions.isEmpty() || !Set.of("PLACE", "BREAK").containsAll(actions) || area != null && world == null)
            throw new IllegalArgumentException("Invalid lookup input");
    }
    public record Origin(UUID world, int x, int z) {}

    public static LookupInput parse(String[] args, long now, Origin origin) {
        if (args.length > 8 || Arrays.stream(args).mapToInt(String::length).sum() > 1024)
            throw new IllegalArgumentException("Lookup input is too long");
        var values = new HashMap<String, String>();
        for (String arg : args) {
            int colon = arg.indexOf(':');
            if (colon < 1 || colon == arg.length() - 1) throw new IllegalArgumentException("Use filter:value");
            String key = arg.substring(0, colon), value = arg.substring(colon + 1);
            if (!Set.of("player", "world", "time", "from", "to", "action", "radius", "origin").contains(key))
                throw new IllegalArgumentException("Unknown filter: " + key);
            if (values.putIfAbsent(key, value) != null) throw new IllegalArgumentException("Duplicate filter: " + key);
        }
        long from, to;
        try {
            if (values.containsKey("from") || values.containsKey("to")) {
                if (values.containsKey("time") || !values.containsKey("from") || !values.containsKey("to"))
                    throw new IllegalArgumentException("Use both from: and to:, without time:");
                from = utc(values.get("from")); to = utc(values.get("to"));
            } else {
                String relative = values.getOrDefault("time", "1h");
                if (!relative.matches("[1-9][0-9]{0,5}[smhd]")) throw new IllegalArgumentException("Time must be a positive duration such as 2h");
                long unit = switch (relative.charAt(relative.length() - 1)) { case 's' -> 1000; case 'm' -> 60_000; case 'h' -> 3_600_000; default -> 86_400_000; };
                long duration = Long.parseLong(relative.substring(0, relative.length() - 1)) * unit;
                if (duration > 3650L * 86_400_000) throw new IllegalArgumentException("Time range exceeds 3650 days");
                to = now; from = Math.max(0, to - duration);
            }
            var actions = new java.util.HashSet<String>();
            for (String action : values.getOrDefault("action", "block-place,block-break").split(",", -1)) {
                actions.add(switch (action) { case "block-place" -> "PLACE"; case "block-break" -> "BREAK";
                    default -> throw new IllegalArgumentException("Actions: block-place,block-break"); });
            }
            String world = values.get("world");
            LookupFilter.Area area = null;
            if (values.containsKey("origin") && !values.containsKey("radius")) throw new IllegalArgumentException("origin requires radius");
            if (values.containsKey("radius")) {
                int radius = Integer.parseInt(values.get("radius"));
                int x, z;
                if (values.containsKey("origin")) {
                    String[] coordinates = values.get("origin").split(",", -1);
                    if (coordinates.length != 2) throw new IllegalArgumentException("origin requires x,z");
                    x = Integer.parseInt(coordinates[0]); z = Integer.parseInt(coordinates[1]);
                } else {
                    if (origin == null) throw new IllegalArgumentException("Console radius needs world: and origin:x,z");
                    x = origin.x; z = origin.z;
                }
                if (world == null && origin != null) world = origin.world.toString();
                if (world == null) throw new IllegalArgumentException("Console radius needs world:");
                area = new LookupFilter.Area(x, null, z, radius);
            }
            return new LookupInput(values.containsKey("player") ? List.of(values.get("player").split(",", -1)) : List.of(), world, from, to, actions, area);
        } catch (NumberFormatException | DateTimeParseException | ArithmeticException error) { throw new IllegalArgumentException("Invalid number or UTC timestamp", error); }
    }
    private static long utc(String text) {
        if (!text.endsWith("Z")) throw new IllegalArgumentException("Timestamps must use UTC Z");
        return Instant.parse(text).toEpochMilli();
    }
}
