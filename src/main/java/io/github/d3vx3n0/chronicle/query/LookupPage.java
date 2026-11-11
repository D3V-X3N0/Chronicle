package io.github.d3vx3n0.chronicle.query;

import java.util.List;

public record LookupPage(LookupFilter filter, long boundary, List<HistoryRow> rows, boolean more) {
    public LookupPage { rows = List.copyOf(rows); }
    public long beforeNext() { return rows.isEmpty() ? 1 : rows.getLast().id(); }
}
