package io.github.d3vx3n0.chronicle.recovery;

import io.github.d3vx3n0.chronicle.capture.BlockReconciler.Position;
import io.github.d3vx3n0.chronicle.query.HistoryRow;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Pure inverse-chain planning. Any unselected later edge blocks that target's remaining chain. */
public final class RecoveryPlanner {
    private RecoveryPlanner() {}
    public record Evidence(HistoryRow row, int members, long previous, long latest, boolean effective, boolean gap) {}
    public static List<RecoveryPlan.Step> rollback(List<Evidence> selected) {
        if (selected.size() > RecoveryPlan.MAX_STEPS) throw new IllegalArgumentException("Selection exceeds 1000 changes; narrow scope");
        var result = new ArrayList<RecoveryPlan.Step>();
        Map<Position, Long> next = new HashMap<>();
        Map<Position, String> state = new HashMap<>();
        Set<Position> blocked = new java.util.HashSet<>();
        long prior = Long.MAX_VALUE;
        for (Evidence e : selected) {
            var r = e.row();
            if (r.id() >= prior) throw new IllegalArgumentException("Selection must use descending capture/history order");
            prior = r.id();
            Position p = new Position(java.util.UUID.fromString(r.world()), r.x(), r.y(), r.z());
            String reason = "";
            if (!r.understoodSnapshot() || !r.attribution().equals("PLAYER / PLAYER_ACTION / DIRECT") || e.members() != 1)
                reason = "Unsupported snapshot, attribution or group";
            else if (e.gap()) reason = "Known coverage gap crosses this change";
            else if (!e.effective()) reason = "Change already undone or reserved by another operation";
            else if (blocked.contains(p) || next.getOrDefault(p, e.latest()) != r.id()) reason = "Later unselected change/dependency";
            else if (state.containsKey(p) && !state.get(p).equals(r.after())) reason = "Incomplete state chain";
            if (!reason.isEmpty()) blocked.add(p);
            else { next.put(p, e.previous()); state.put(p, r.before()); }
            result.add(new RecoveryPlan.Step(result.size(), r.id(), null, p, r.after(), r.before(), e.latest(), reason));
        }
        return List.copyOf(result);
    }
}
